package com.anekar.wakelock.service

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

class PunishmentGauntletService : Service() {

    private lateinit var audioHijackManager: AudioHijackManager
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private var alarmId: Long = -1L
    private val punishmentDurationMillis = 6 * 60 * 60 * 1000L
    private val checkIntervalMillis = 5 * 60 * 1000L
    private var punishmentEndTime = 0L

    companion object {
        const val ACTION_START_PUNISHMENT = "ACTION_START_PUNISHMENT"
        const val ACTION_SOLVED_ROUND = "ACTION_SOLVED_ROUND"
        const val ACTION_TERMINATE_WITH_PIN = "ACTION_TERMINATE_WITH_PIN"
        const val EXTRA_ALARM_ID = "EXTRA_ALARM_ID"
    }

    override fun onCreate() {
        super.onCreate()
        audioHijackManager = AudioHijackManager(this)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "WAKELOCK::PunishmentWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(punishmentDurationMillis)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_PUNISHMENT

        when (action) {
            ACTION_START_PUNISHMENT -> {
                alarmId = intent?.getLongExtra(EXTRA_ALARM_ID, -1L) ?: -1L
                val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("is_punishment_active", true)
                    .putLong("active_alarm_id", alarmId)
                    .apply()

                startPunishmentForeground()
                initiatePunishmentCycle()
            }
            ACTION_SOLVED_ROUND -> {
                audioHijackManager.stopAlarmTone()
                handler.postDelayed(gauntletCycleRunnable, checkIntervalMillis)
            }
            ACTION_TERMINATE_WITH_PIN -> {
                terminatePunishment()
            }
        }
        return START_STICKY
    }

    private fun startPunishmentForeground() {
        val fullScreenIntent = Intent(this, AlarmTriggerActivity::class.java).apply {
            putExtra("ALARM_ID", alarmId)
            putExtra("IS_PUNISHMENT", true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            NotificationHelper.NOTIFICATION_PUNISHMENT_ID,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_PUNISHMENT_ID)
            .setSmallIcon(R.drawable.ic_lock_alarm)
            .setContentTitle("WAKELOCK // 6-HR TAMPER GAUNTLET")
            .setContentText("REBOOT EVASION DETECTED. 5-minute math enforcement active.")
            .setColor(0xFFFF3B30.toInt())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIFICATION_PUNISHMENT_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_PUNISHMENT_ID, notification)
        }
    }

    private fun initiatePunishmentCycle() {
        punishmentEndTime = System.currentTimeMillis() + punishmentDurationMillis
        CoroutineScope(Dispatchers.IO).launch {
            val db = WakelockDatabase.getDatabase(applicationContext)
            db.alarmDao().updatePunishmentState(alarmId, true, punishmentEndTime)
        }
        triggerPunishmentAlarm()
    }

    private val gauntletCycleRunnable = Runnable {
        if (System.currentTimeMillis() >= punishmentEndTime) {
            terminatePunishment()
            return@Runnable
        }
        triggerPunishmentAlarm()
    }

    private fun triggerPunishmentAlarm() {
        audioHijackManager.startAlarmTone(null)

        val triggerIntent = Intent(this, AlarmTriggerActivity::class.java).apply {
            putExtra("ALARM_ID", alarmId)
            putExtra("IS_PUNISHMENT", true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(triggerIntent)
    }

    private fun terminatePunishment() {
        handler.removeCallbacksAndMessages(null)
        audioHijackManager.stopAlarmTone()

        val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("is_punishment_active", false)
            .putBoolean("is_alarm_active", false)
            .putBoolean("is_surveillance_active", false)
            .apply()

        CoroutineScope(Dispatchers.IO).launch {
            val db = WakelockDatabase.getDatabase(applicationContext)
            db.alarmDao().updatePunishmentState(alarmId, false, 0L)
            db.alarmDao().updateSurveillanceState(alarmId, false, 0L, 0L, 0L)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        audioHijackManager.stopAlarmTone()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
