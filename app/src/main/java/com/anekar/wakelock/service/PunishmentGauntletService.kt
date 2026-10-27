package com.anekar.wakelock.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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

class PunishmentGauntletService : Service() {

    private lateinit var audioHijackManager: AudioHijackManager
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_START_PUNISHMENT = "ACTION_START_PUNISHMENT"
        const val ACTION_BLAST_SIREN = "ACTION_BLAST_SIREN"
        const val ACTION_MUTE_SIREN = "ACTION_MUTE_SIREN"
        const val ACTION_SOLVED_ROUND = "ACTION_SOLVED_ROUND"
        const val ACTION_TERMINATE_WITH_PIN = "ACTION_TERMINATE_WITH_PIN"
        const val ACTION_COMPLETE_GAUNTLET = "ACTION_COMPLETE_GAUNTLET"
        const val EXTRA_ALARM_ID = "EXTRA_ALARM_ID"
        private const val NOTIFICATION_PUNISHMENT_ID = 9009
    }

    override fun onCreate() {
        super.onCreate()
        audioHijackManager = AudioHijackManager(this)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "WAKELOCK::PunishmentGauntletWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(48 * 60 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_PUNISHMENT
        val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)

        when (action) {
            ACTION_START_PUNISHMENT -> {
                val alarmId = intent?.getLongExtra(EXTRA_ALARM_ID, -1L) ?: prefs.getLong("active_alarm_id", -1L)

                CoroutineScope(Dispatchers.IO).launch {
                    val db = WakelockDatabase.getDatabase(applicationContext)
                    val alarm = if (alarmId > 0) db.alarmDao().getAlarmById(alarmId) else db.alarmDao().getAllAlarms().firstOrNull()

                    val cooldownDuration = alarm?.penaltyCooldownSeconds ?: 43200L
                    val cooldownEndTime = System.currentTimeMillis() + (cooldownDuration * 1000L)

                    prefs.edit()
                        .putBoolean("is_punishment_active", true)
                        .putLong("active_alarm_id", alarm?.id ?: -1L)
                        .putInt("punishment_current_round", 1)
                        .putInt("punishment_total_rounds", alarm?.penaltyRoundsCount ?: 100)
                        .putLong("punishment_cooldown_end_time", cooldownEndTime)
                        .apply()

                    withContext(Dispatchers.Main) {
                        audioHijackManager.startAlarmTone(alarm?.ringtoneUri)
                        launchPunishmentScreen(alarm?.id ?: -1L)
                    }
                }

                startForegroundServiceNotification()
            }

            ACTION_BLAST_SIREN -> {
                val alarmId = prefs.getLong("active_alarm_id", -1L)
                CoroutineScope(Dispatchers.IO).launch {
                    val db = WakelockDatabase.getDatabase(applicationContext)
                    val alarm = if (alarmId > 0) db.alarmDao().getAlarmById(alarmId) else null
                    withContext(Dispatchers.Main) {
                        audioHijackManager.startAlarmTone(alarm?.ringtoneUri)
                    }
                }
            }

            ACTION_MUTE_SIREN -> {
                audioHijackManager.stopAlarmTone()
            }

            ACTION_COMPLETE_GAUNTLET, ACTION_TERMINATE_WITH_PIN -> {
                terminatePunishment()
            }
        }
        return START_STICKY
    }

    private fun launchPunishmentScreen(alarmId: Long) {
        val gauntletIntent = Intent(this, AlarmTriggerActivity::class.java).apply {
            putExtra("ALARM_ID", alarmId)
            putExtra("IS_PUNISHMENT", true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_PUNISHMENT_ID,
            gauntletIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ALARM_ID)
            .setSmallIcon(R.drawable.ic_lock_alarm)
            .setContentTitle("WAKELOCK // PUNISHMENT GAUNTLET ACTIVE")
            .setContentText("Tamper violation triggered. Complete math rounds to unlock.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pendingIntent, true)
            .setOngoing(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_PUNISHMENT_ID, notification)

        try {
            startActivity(gauntletIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startForegroundServiceNotification() {
        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ALARM_ID)
            .setSmallIcon(R.drawable.ic_lock_alarm)
            .setContentTitle("WAKELOCK // GAUNTLET LOCKDOWN")
            .setContentText("Solve all math rounds or wait for master cooldown to expire.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_PUNISHMENT_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_PUNISHMENT_ID, notification)
        }
    }

    private fun terminatePunishment() {
        audioHijackManager.stopAlarmTone()
        val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("is_punishment_active", false)
            .putInt("punishment_current_round", 1)
            .putLong("punishment_cooldown_end_time", 0L)
            .apply()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_PUNISHMENT_ID)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        audioHijackManager.stopAlarmTone()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
