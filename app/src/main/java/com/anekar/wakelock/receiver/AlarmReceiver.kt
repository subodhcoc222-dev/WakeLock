package com.anekar.wakelock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.anekar.wakelock.data.database.WakelockDatabase
import com.anekar.wakelock.service.AlarmForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra("ALARM_ID", -1L)
        if (alarmId == -1L) return

        // 1. Immediately force the CPU out of Deep Sleep / Low Power State
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "WAKELOCK::AlarmReceiverImmediateWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(30 * 1000L) // 30-second failsafe hold
        }

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = WakelockDatabase.getDatabase(context)
                val alarm = db.alarmDao().getAlarmById(alarmId)

                if (alarm != null && alarm.isEnabled) {
                    val currentTime = System.currentTimeMillis()

                    // Check Option 1: 1-Time Skip
                    if (alarm.skipNextOnce) {
                        val rearmedAlarm = alarm.copy(skipNextOnce = false)
                        db.alarmDao().updateAlarm(rearmedAlarm)
                        AlarmScheduler.scheduleAlarm(context, rearmedAlarm)
                        return@launch
                    }

                    // Check Option 2: Paused Until Date
                    if (alarm.pauseUntilTimestamp > currentTime) {
                        AlarmScheduler.scheduleAlarm(context, alarm)
                        return@launch
                    } else if (alarm.pauseUntilTimestamp > 0L) {
                        val resumedAlarm = alarm.copy(pauseUntilTimestamp = 0L)
                        db.alarmDao().updateAlarm(resumedAlarm)
                    }

                    // 2. Launch Foreground Service while CPU is held fully awake
                    val startServiceIntent = Intent(context, AlarmForegroundService::class.java).apply {
                        action = AlarmForegroundService.ACTION_START_ALARM
                        putExtra(AlarmForegroundService.EXTRA_ALARM_ID, alarmId)
                    }
                    ContextCompat.startForegroundService(context, startServiceIntent)

                    AlarmScheduler.scheduleAlarm(context, alarm)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // 3. Release WakeLock only when service has been dispatched
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                pendingResult.finish()
            }
        }
    }
}
