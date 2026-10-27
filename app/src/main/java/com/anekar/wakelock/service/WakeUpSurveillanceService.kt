package com.anekar.wakelock.service

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

class WakeUpSurveillanceService : Service() {

    private lateinit var audioHijackManager: AudioHijackManager
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private var alarmId: Long = -1L
    private var taperIntervalSeconds: Int = 0
    private var taperDurationSeconds: Int = 0
    private var secondaryIntervalSeconds: Int = 600
    private var totalDurationSeconds: Int = 7200

    private var taperChecksCount: Int = 0
    private var secondaryChecksCount: Int = 0
    private var totalChecksCount: Int = 1
    private var currentCheckIndex: Int = 1
    private var isAwaitingResponse: Boolean = false

    companion object {
        const val ACTION_START_SURVEILLANCE = "ACTION_START_SURVEILLANCE"
        const val ACTION_CONFIRM_AWAKE = "ACTION_CONFIRM_AWAKE"
        const val ACTION_RESUME_FROM_PENALTY = "ACTION_RESUME_FROM_PENALTY"
        const val ACTION_CANCEL_SURVEILLANCE = "ACTION_CANCEL_SURVEILLANCE"
        const val EXTRA_ALARM_ID = "EXTRA_ALARM_ID"
        private const val NOTIFICATION_CHECK_ALERT_ID = 4004
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
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_SURVEILLANCE

        when (action) {
            ACTION_START_SURVEILLANCE -> {
                val incomingAlarmId = intent?.getLongExtra(EXTRA_ALARM_ID, -1L) ?: -1L
                val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
                val isAlreadyActive = prefs.getBoolean("is_surveillance_active", false)
                val existingActiveAlarmId = prefs.getLong("active_alarm_id", -1L)

                if (isAlreadyActive && existingActiveAlarmId != -1L && incomingAlarmId != -1L && incomingAlarmId != existingActiveAlarmId) {
                    return START_STICKY
                }
                if (isAlreadyActive && alarmId != -1L && alarmId == existingActiveAlarmId) {
                    return START_STICKY
                }

                alarmId = if (incomingAlarmId != -1L) incomingAlarmId else existingActiveAlarmId
                currentCheckIndex = 1

                prefs.edit()
                    .putBoolean("is_surveillance_active", true)
                    .putLong("active_alarm_id", alarmId)
                    .putInt("cached_current_check", 1)
                    .putInt("failed_check_index", 0)
                    .apply()

                startSurveillanceForeground()
                loadAlarmAndBeginSurveillance()
            }

            ACTION_RESUME_FROM_PENALTY -> {
                // FIXED DEAD LOOP: Resumes next check immediately after penalty math completion
                isAwaitingResponse = false
                handler.removeCallbacks(penaltyEscalationRunnable)

                val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
                val incomingAlarmId = intent?.getLongExtra(EXTRA_ALARM_ID, -1L) ?: -1L
                if (alarmId <= 0) {
                    alarmId = if (incomingAlarmId > 0) incomingAlarmId else prefs.getLong("active_alarm_id", -1L)
                }

                currentCheckIndex++
                prefs.edit()
                    .putBoolean("is_surveillance_active", true)
                    .putInt("cached_current_check", currentCheckIndex)
                    .putInt("failed_check_index", 0)
                    .apply()

                startSurveillanceForeground()

                if (totalChecksCount <= 1 || secondaryIntervalSeconds <= 0) {
                    loadAlarmAndBeginSurveillance()
                } else if (currentCheckIndex <= totalChecksCount) {
                    scheduleNextCheck()
                } else {
                    terminateSurveillance()
                }
            }

            ACTION_CONFIRM_AWAKE -> {
                isAwaitingResponse = false
                handler.removeCallbacks(penaltyEscalationRunnable)

                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_CHECK_ALERT_ID)

                currentCheckIndex++
                val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
                prefs.edit().putInt("cached_current_check", currentCheckIndex).apply()

                if (currentCheckIndex <= totalChecksCount) {
                    scheduleNextCheck()
                } else {
                    terminateSurveillance()
                }
            }

            ACTION_CANCEL_SURVEILLANCE -> {
                terminateSurveillance()
            }
        }
        return START_STICKY
    }

    private fun startSurveillanceForeground() {
        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_SURVEILLANCE_ID)
            .setSmallIcon(R.drawable.ic_lock_alarm)
            .setContentTitle("WAKELOCK // SURVEILLANCE ARMED")
            .setContentText("Active awake surveillance cycle in progress.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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

    private fun loadAlarmAndBeginSurveillance() {
        CoroutineScope(Dispatchers.IO).launch {
            val db = WakelockDatabase.getDatabase(applicationContext)
            val alarm = db.alarmDao().getAlarmById(alarmId) ?: return@launch

            taperIntervalSeconds = alarm.taperIntervalSeconds
            taperDurationSeconds = alarm.taperDurationSeconds
            secondaryIntervalSeconds = alarm.wakeUpIntervalSeconds.coerceAtLeast(10)
            totalDurationSeconds = alarm.wakeUpTotalDurationSeconds.coerceAtLeast(10)

            val isTaperActive = taperIntervalSeconds > 0 && taperDurationSeconds > 0

            if (isTaperActive) {
                val effectiveTaperDuration = taperDurationSeconds.coerceAtMost(totalDurationSeconds)
                taperChecksCount = (effectiveTaperDuration / taperIntervalSeconds).coerceAtLeast(1)

                val remainingDuration = totalDurationSeconds - effectiveTaperDuration
                secondaryChecksCount = if (remainingDuration > 0) {
                    (remainingDuration / secondaryIntervalSeconds).coerceAtLeast(0)
                } else {
                    0
                }
                totalChecksCount = (taperChecksCount + secondaryChecksCount).coerceAtLeast(1)
            } else {
                taperChecksCount = 0
                secondaryChecksCount = (totalDurationSeconds / secondaryIntervalSeconds).coerceAtLeast(1)
                totalChecksCount = secondaryChecksCount
            }

            val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("cached_current_check", currentCheckIndex)
                .putInt("cached_total_checks", totalChecksCount)
                .apply()

            val startTime = System.currentTimeMillis()
            val endTime = startTime + (totalDurationSeconds * 1000L)
            val initialDelay = getIntervalForNextCheck(currentCheckIndex)

            db.alarmDao().updateSurveillanceState(
                id = alarmId,
                isActive = true,
                startTime = startTime,
                endTime = endTime,
                nextCheck = startTime + (initialDelay * 1000L)
            )

            scheduleNextCheck()
        }
    }

    private fun getIntervalForNextCheck(checkIndex: Int): Int {
        return if (taperIntervalSeconds > 0 && checkIndex <= taperChecksCount) {
            taperIntervalSeconds
        } else {
            secondaryIntervalSeconds
        }
    }

    private fun scheduleNextCheck() {
        handler.removeCallbacks(checkTriggerRunnable)
        val delaySec = getIntervalForNextCheck(currentCheckIndex)
        handler.postDelayed(checkTriggerRunnable, delaySec * 1000L)
    }

    private val checkTriggerRunnable = Runnable {
        triggerCheckAlert()
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

        // UNIVERSAL FULL-SCREEN INTENT (Wakes up lock-screens across Xiaomi, Oppo, Vivo, Samsung, Stock Android)
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

        handler.postDelayed(penaltyEscalationRunnable, 30 * 1000L)
    }

    private val penaltyEscalationRunnable = Runnable {
        if (isAwaitingResponse) {
            isAwaitingResponse = false
            handler.removeCallbacks(checkTriggerRunnable)

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_CHECK_ALERT_ID)

            stopForeground(STOP_FOREGROUND_REMOVE)

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

    private fun terminateSurveillance() {
        handler.removeCallbacksAndMessages(null)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_CHECK_ALERT_ID)

        val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("is_surveillance_active", false)
            .putInt("cached_current_check", 1)
            .putInt("failed_check_index", 0)
            .apply()

        CoroutineScope(Dispatchers.IO).launch {
            val db = WakelockDatabase.getDatabase(applicationContext)
            db.alarmDao().updateSurveillanceState(alarmId, false, 0L, 0L, 0L)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
