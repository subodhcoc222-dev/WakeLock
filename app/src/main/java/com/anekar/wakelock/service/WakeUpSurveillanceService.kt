package com.anekar.wakelock.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.anekar.wakelock.R
import com.anekar.wakelock.data.database.WakelockDatabase
import com.anekar.wakelock.service.audio.AudioHijackManager
import com.anekar.wakelock.ui.AlarmTriggerActivity
import com.anekar.wakelock.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WakeUpSurveillanceService : Service() {

    private lateinit var audioHijackManager: AudioHijackManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private var alarmId: Long = -1L
    private var currentCheckIndex: Int = 1
    private var totalChecksCount: Int = 1
    private var isAwaitingResponse: Boolean = false

    companion object {
        const val ACTION_START_SURVEILLANCE = "ACTION_START_SURVEILLANCE"
        const val ACTION_TRIGGER_CHECK_ALARM = "ACTION_TRIGGER_CHECK_ALARM"
        const val ACTION_CONFIRM_AWAKE = "ACTION_CONFIRM_AWAKE"
        const val ACTION_RESUME_FROM_PENALTY = "ACTION_RESUME_FROM_PENALTY"
        const val ACTION_CANCEL_SURVEILLANCE = "ACTION_CANCEL_SURVEILLANCE"
        const val EXTRA_ALARM_ID = "EXTRA_ALARM_ID"

        private const val NOTIFICATION_CHECK_ALERT_ID = 4004
        private const val PENDING_CHECK_REQUEST_CODE = 8800
    }

    override fun onCreate() {
        super.onCreate()
        audioHijackManager = AudioHijackManager(this)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "WAKELOCK::SurveillancePersistentWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)

        // 1. ZOMBIE KILL GUARD: If OS revived service with null intent after completion
        if (intent == null || intent.action == null) {
            val isStillActive = prefs.getBoolean("is_surveillance_active", false)
            if (!isStillActive) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            syncStateFromPrefs()
            startSurveillanceForeground()
            return START_STICKY
        }

        syncStateFromPrefs()

        when (intent.action) {
            ACTION_START_SURVEILLANCE -> {
                val incomingAlarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
                val targetAlarmId = if (incomingAlarmId != -1L) incomingAlarmId else alarmId

                if (targetAlarmId != -1L) {
                    alarmId = targetAlarmId
                    currentCheckIndex = 1
                    initializeAndCalculateSurveillance(targetAlarmId)
                } else {
                    stopSelf()
                }
            }

            ACTION_TRIGGER_CHECK_ALARM -> {
                val isStillActive = prefs.getBoolean("is_surveillance_active", false)
                if (!isStillActive || currentCheckIndex > totalChecksCount) {
                    terminateSurveillance()
                } else {
                    triggerCheckAlert()
                }
            }

            ACTION_CONFIRM_AWAKE -> {
                isAwaitingResponse = false
                mainHandler.removeCallbacks(penaltyEscalationRunnable)

                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_CHECK_ALERT_ID)

                // 2. CRITICAL EXIT GUARD: Advance or Terminate
                if (currentCheckIndex >= totalChecksCount) {
                    terminateSurveillance()
                } else {
                    currentCheckIndex++
                    prefs.edit().putInt("cached_current_check", currentCheckIndex).apply()
                    startSurveillanceForeground()
                    scheduleNextHardwareCheck()
                }
            }

            ACTION_RESUME_FROM_PENALTY -> {
                isAwaitingResponse = false
                mainHandler.removeCallbacks(penaltyEscalationRunnable)

                val incomingAlarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
                if (incomingAlarmId > 0) alarmId = incomingAlarmId

                if (currentCheckIndex >= totalChecksCount) {
                    terminateSurveillance()
                } else {
                    currentCheckIndex++
                    prefs.edit()
                        .putBoolean("is_surveillance_active", true)
                        .putInt("cached_current_check", currentCheckIndex)
                        .putInt("failed_check_index", 0)
                        .apply()

                    startSurveillanceForeground()
                    scheduleNextHardwareCheck()
                }
            }

            ACTION_CANCEL_SURVEILLANCE -> {
                terminateSurveillance()
            }
        }
        return START_STICKY
    }

    private fun syncStateFromPrefs() {
        val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
        alarmId = prefs.getLong("active_alarm_id", alarmId)
        currentCheckIndex = prefs.getInt("cached_current_check", currentCheckIndex)
        totalChecksCount = prefs.getInt("cached_total_checks", totalChecksCount).coerceAtLeast(1)
    }

    private fun initializeAndCalculateSurveillance(targetId: Long) {
        val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)

        CoroutineScope(Dispatchers.IO).launch {
            val db = WakelockDatabase.getDatabase(applicationContext)
            val alarm = db.alarmDao().getAlarmById(targetId) ?: return@launch

            val taperInterval = alarm.taperIntervalSeconds
            val taperDuration = alarm.taperDurationSeconds
            val secondaryInterval = alarm.wakeUpIntervalSeconds.coerceAtLeast(10)
            val totalDuration = alarm.wakeUpTotalDurationSeconds.coerceAtLeast(10)

            val calculatedChecks = if (taperInterval > 0 && taperDuration > 0) {
                val effectiveTaper = taperDuration.coerceAtMost(totalDuration)
                val tCount = effectiveTaper / taperInterval
                val remainingSec = (totalDuration - effectiveTaper).coerceAtLeast(0)
                val sCount = remainingSec / secondaryInterval
                (tCount + sCount).coerceAtLeast(1)
            } else {
                (totalDuration / secondaryInterval).coerceAtLeast(1)
            }

            currentCheckIndex = 1
            totalChecksCount = calculatedChecks

            prefs.edit()
                .putBoolean("is_surveillance_active", true)
                .putLong("active_alarm_id", targetId)
                .putInt("cached_current_check", 1)
                .putInt("cached_total_checks", totalChecksCount)
                .putInt("failed_check_index", 0)
                .apply()

            val now = System.currentTimeMillis()
            db.alarmDao().updateSurveillanceState(
                id = targetId,
                isActive = true,
                startTime = now,
                endTime = now + (totalDuration * 1000L),
                nextCheck = now + (taperInterval.takeIf { it > 0 } ?: secondaryInterval) * 1000L
            )

            withContext(Dispatchers.Main) {
                startSurveillanceForeground()
                scheduleNextHardwareCheck()
            }
        }
    }

    private fun scheduleNextHardwareCheck() {
        val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("is_surveillance_active", false)) return

        CoroutineScope(Dispatchers.IO).launch {
            val db = WakelockDatabase.getDatabase(applicationContext)
            val alarm = db.alarmDao().getAlarmById(alarmId) ?: return@launch

            val taperInterval = alarm.taperIntervalSeconds
            val taperDuration = alarm.taperDurationSeconds
            val taperCheckCount = if (taperInterval > 0 && taperDuration > 0) taperDuration / taperInterval else 0

            val intervalSeconds = if (currentCheckIndex <= taperCheckCount && taperInterval > 0) {
                taperInterval
            } else {
                alarm.wakeUpIntervalSeconds.coerceAtLeast(10)
            }

            val triggerAtMillis = System.currentTimeMillis() + (intervalSeconds * 1000L)

            // HARDWARE ALARM CLOCK (Wakes up Samsung One UI Deep Sleep 100% reliably)
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(applicationContext, WakeUpSurveillanceService::class.java).apply {
                action = ACTION_TRIGGER_CHECK_ALARM
            }

            val pendingIntent = PendingIntent.getService(
                applicationContext,
                PENDING_CHECK_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        }
    }

    private fun triggerCheckAlert() {
        audioHijackManager.playDoubleBeep()
        isAwaitingResponse = true

        val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("cached_current_check", currentCheckIndex)
            .putInt("cached_total_checks", totalChecksCount)
            .apply()

        val checkIntent = Intent(this, AlarmTriggerActivity::class.java).apply {
            putExtra("ALARM_ID", alarmId)
            putExtra("IS_WAKEUP_CHECK", true)
            putExtra("CURRENT_CHECK", currentCheckIndex)
            putExtra("TOTAL_CHECKS", totalChecksCount)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_CHECK_ALERT_ID,
            checkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenNotification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ALARM_ID)
            .setSmallIcon(R.drawable.ic_lock_alarm)
            .setContentTitle("WAKELOCK // CHECK $currentCheckIndex OF $totalChecksCount")
            .setContentText("Tap to confirm awake status immediately.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_CHECK_ALERT_ID, fullScreenNotification)

        try {
            startActivity(checkIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mainHandler.removeCallbacks(penaltyEscalationRunnable)
        mainHandler.postDelayed(penaltyEscalationRunnable, 45 * 1000L) // 45s response window
    }

    private val penaltyEscalationRunnable = Runnable {
        if (isAwaitingResponse) {
            isAwaitingResponse = false

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_CHECK_ALERT_ID)

            val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("failed_check_index", currentCheckIndex).apply()

            val alarmServiceIntent = Intent(this, AlarmForegroundService::class.java).apply {
                action = AlarmForegroundService.ACTION_START_ALARM
                putExtra(AlarmForegroundService.EXTRA_ALARM_ID, alarmId)
                putExtra("FAILED_CHECK_INDEX", currentCheckIndex)
            }
            startService(alarmServiceIntent)
        }
    }

    private fun startSurveillanceForeground() {
        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_SURVEILLANCE_ID)
            .setSmallIcon(R.drawable.ic_lock_alarm)
            .setContentTitle("WAKELOCK // SURVEILLANCE ARMED")
            .setContentText("Check $currentCheckIndex of $totalChecksCount in progress.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIFICATION_SURVEILLANCE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_SURVEILLANCE_ID, notification)
        }
    }

    private fun terminateSurveillance() {
        mainHandler.removeCallbacksAndMessages(null)

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cancelIntent = Intent(applicationContext, WakeUpSurveillanceService::class.java).apply {
            action = ACTION_TRIGGER_CHECK_ALARM
        }
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            PENDING_CHECK_REQUEST_CODE,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)

        val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("is_surveillance_active", false)
            .putBoolean("is_lockdown_active", false)
            .putInt("cached_current_check", 1)
            .putInt("cached_total_checks", 1)
            .putInt("failed_check_index", 0)
            .putLong("active_alarm_id", -1L)
            .apply()

        CoroutineScope(Dispatchers.IO).launch {
            val db = WakelockDatabase.getDatabase(applicationContext)
            db.alarmDao().updateSurveillanceState(alarmId, false, 0L, 0L, 0L)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_CHECK_ALERT_ID)
        notificationManager.cancel(NotificationHelper.NOTIFICATION_SURVEILLANCE_ID)

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
