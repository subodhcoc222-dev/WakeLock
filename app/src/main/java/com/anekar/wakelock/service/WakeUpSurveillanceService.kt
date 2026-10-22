package com.anekar.wakelock.service

import android.app.AlarmManager
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
    private var intervalSeconds: Int = 600
    private var totalDurationSeconds: Int = 7200
    private var totalChecksCount: Int = 1
    private var currentCheckIndex: Int = 1
    private var isAwaitingResponse: Boolean = false

    companion object {
        const val ACTION_START_SURVEILLANCE = "ACTION_START_SURVEILLANCE"
        const val ACTION_CONFIRM_AWAKE = "ACTION_CONFIRM_AWAKE"
        const val ACTION_CANCEL_SURVEILLANCE = "ACTION_CANCEL_SURVEILLANCE"
        const val EXTRA_ALARM_ID = "EXTRA_ALARM_ID"
    }

    override fun onCreate() {
        super.onCreate()
        audioHijackManager = AudioHijackManager(this)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        // पूरे सर्विलांस के दौरान CPU स्लीप को ब्लॉक रखने के लिए लगातार WakeLock
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "WAKELOCK::SurveillancePersistentWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // 6 घंटे तक सुरक्षित लॉक
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_SURVEILLANCE

        when (action) {
            ACTION_START_SURVEILLANCE -> {
                alarmId = intent?.getLongExtra(EXTRA_ALARM_ID, -1L) ?: -1L
                currentCheckIndex = 1
                startSurveillanceForeground()
                loadAlarmAndBeginSurveillance()
            }
            ACTION_CONFIRM_AWAKE -> {
                isAwaitingResponse = false
                handler.removeCallbacks(penaltyEscalationRunnable)
                
                // अगला चेक शेड्यूल करना
                currentCheckIndex++
                if (currentCheckIndex <= totalChecksCount) {
                    scheduleNextCheck()
                } else {
                    // सभी चेक पूरे हो गए -> सेशन सफलतापूर्वक समाप्त
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

            intervalSeconds = alarm.wakeUpIntervalSeconds
            totalDurationSeconds = alarm.wakeUpTotalDurationSeconds
            // सटीक अनुपात: जितने चेक बनते हैं, ठीक उतने ही चलेंगे
            totalChecksCount = (totalDurationSeconds / intervalSeconds).coerceAtLeast(1)

            val startTime = System.currentTimeMillis()
            val endTime = startTime + (totalDurationSeconds * 1000L)

            db.alarmDao().updateSurveillanceState(
                id = alarmId,
                isActive = true,
                startTime = startTime,
                endTime = endTime,
                nextCheck = startTime + (intervalSeconds * 1000L)
            )

            scheduleNextCheck()
        }
    }

    private fun scheduleNextCheck() {
        handler.removeCallbacks(checkTriggerRunnable)
        handler.postDelayed(checkTriggerRunnable, intervalSeconds * 1000L)
    }

    private val checkTriggerRunnable = Runnable {
        triggerCheckAlert()
    }

    private fun triggerCheckAlert() {
        audioHijackManager.playDoubleBeep()
        isAwaitingResponse = true

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
        startActivity(checkIntent)

        // 30 सेकंड में रिस्पॉन्स न मिलने पर अलार्म सायरन ब्लास्ट
        handler.postDelayed(penaltyEscalationRunnable, 30 * 1000L)
    }

    private val penaltyEscalationRunnable = Runnable {
        if (isAwaitingResponse) {
            val alarmServiceIntent = Intent(this, AlarmForegroundService::class.java).apply {
                action = AlarmForegroundService.ACTION_START_ALARM
                putExtra(AlarmForegroundService.EXTRA_ALARM_ID, alarmId)
            }
            startService(alarmServiceIntent)
        }
    }

    private fun terminateSurveillance() {
        handler.removeCallbacksAndMessages(null)
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
