package com.anekar.wakelock.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.anekar.wakelock.data.database.WakelockDatabase
import com.anekar.wakelock.service.audio.AudioHijackManager
import com.anekar.wakelock.ui.AlarmTriggerActivity
import com.anekar.wakelock.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audioHijackManager: AudioHijackManager

    companion object {
        const val ACTION_START_ALARM = "ACTION_START_ALARM"
        const val ACTION_STOP_ALARM = "ACTION_STOP_ALARM"
        const val EXTRA_ALARM_ID = "EXTRA_ALARM_ID"
    }

    override fun onCreate() {
        super.onCreate()
        audioHijackManager = AudioHijackManager(this)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "WAKELOCK::AlarmServiceWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_ALARM
        val alarmId = intent?.getLongExtra(EXTRA_ALARM_ID, -1L) ?: -1L

        when (action) {
            ACTION_START_ALARM -> {
                val notification = NotificationHelper.buildAlarmNotification(
                    this,
                    alarmId,
                    "CRITICAL AWAKE PROTOCOL"
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this,
                        NotificationHelper.NOTIFICATION_ALARM_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NotificationHelper.NOTIFICATION_ALARM_ID, notification)
                }

                CoroutineScope(Dispatchers.IO).launch {
                    val db = WakelockDatabase.getDatabase(applicationContext)
                    val alarm = db.alarmDao().getAlarmById(alarmId)
                    audioHijackManager.startAlarmTone(alarm?.ringtoneUri)
                }

                val triggerIntent = Intent(this, AlarmTriggerActivity::class.java).apply {
                    putExtra("ALARM_ID", alarmId)
                    putExtra("IS_PUNISHMENT", false)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                startActivity(triggerIntent)
            }
            ACTION_STOP_ALARM -> {
                audioHijackManager.stopAlarmTone()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
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
