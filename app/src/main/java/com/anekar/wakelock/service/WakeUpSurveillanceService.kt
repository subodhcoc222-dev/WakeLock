package com.anekar.wakelock.service

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
    private var surveillanceStartTime: Long = 0L
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
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "WAKELOCK::SurveillanceWakeLock"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_SURVEILLANCE

        when (action) {
            ACTION_START_SURVEILLANCE -> {
                alarmId = intent?.getLongExtra(EXTRA_ALARM_ID, -1L) ?: -1L
                startSurveillanceForeground()
                loadAlarmAndBeginSurveillance()
            }
            ACTION_CONFIRM_AWAKE -> {
                isAwaitingResponse = false
                handler.removeCallbacks(penaltyEscalationRunnable)
                scheduleNextCheck()
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
            .setContentText("Monitoring wakefulness cycle. Next check approaching.")
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
            surveillanceStartTime = System.currentTimeMillis()
            val surveillanceEndTime = surveillanceStartTime + (totalDurationSeconds * 1000L)
            val nextCheckTime = surveillanceStartTime + (intervalSeconds * 1000L)

            db.alarmDao().updateSurveillanceState(
                id = alarmId,
                isActive = true,
                startTime = surveillanceStartTime,
                endTime = surveillanceEndTime,
                nextCheck = nextCheckTime
            )

            handler.post(surveillanceRunnable)
        }
    }

    private val surveillanceRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val elapsedSec = (now - surveillanceStartTime) / 1000L

            if (elapsedSec >= totalDurationSeconds) {
                terminateSurveillance()
                return
            }

            triggerCheckAlert()
        }
    }

    private fun triggerCheckAlert() {
        wakeLock?.acquire(35 * 1000L)
        audioHijackManager.playDoubleBeep()
        isAwaitingResponse = true

        val checkIntent = Intent(this, AlarmTriggerActivity::class.java).apply {
            putExtra("ALARM_ID", alarmId)
            putExtra("IS_WAKEUP_CHECK", true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(checkIntent)

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

    private fun scheduleNextCheck() {
        val nextCheckMillis = System.currentTimeMillis() + (intervalSeconds * 1000L)
        CoroutineScope(Dispatchers.IO).launch {
            val db = WakelockDatabase.getDatabase(applicationContext)
            db.alarmDao().updateSurveillanceState(
                id = alarmId,
                isActive = true,
                startTime = surveillanceStartTime,
                endTime = surveillanceStartTime + (totalDurationSeconds * 1000L),
                nextCheck = nextCheckMillis
            )
        }
        handler.postDelayed(surveillanceRunnable, intervalSeconds * 1000L)
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
