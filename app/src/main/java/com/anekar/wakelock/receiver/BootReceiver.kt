package com.anekar.wakelock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.anekar.wakelock.data.database.WakelockDatabase
import com.anekar.wakelock.service.PunishmentGauntletService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val validActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_REBOOT
        )

        if (!validActions.contains(intent.action)) return

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = WakelockDatabase.getDatabase(context)
                val dao = db.alarmDao()

                // Check 1: Was phone in active punishment before reboot?
                val activePunishment = dao.getActivePunishmentAlarm()
                if (activePunishment != null) {
                    val punishmentIntent = Intent(context, PunishmentGauntletService::class.java).apply {
                        action = PunishmentGauntletService.ACTION_START_PUNISHMENT
                        putExtra(PunishmentGauntletService.EXTRA_ALARM_ID, activePunishment.id)
                    }
                    ContextCompat.startForegroundService(context, punishmentIntent)
                    return@launch
                }

                // Check 2: Was surveillance active and interrupted by reboot? (Tamper Trap)
                val surveillanceAlarm = dao.getActiveSurveillanceAlarm()
                val now = System.currentTimeMillis()

                if (surveillanceAlarm != null && surveillanceAlarm.surveillanceEndTime > now) {
                    // User switched off phone to evade wake-up checks -> Trap Sprung
                    val punishmentIntent = Intent(context, PunishmentGauntletService::class.java).apply {
                        action = PunishmentGauntletService.ACTION_START_PUNISHMENT
                        putExtra(PunishmentGauntletService.EXTRA_ALARM_ID, surveillanceAlarm.id)
                    }
                    ContextCompat.startForegroundService(context, punishmentIntent)
                    return@launch
                }

                // Normal Boot: Reschedule all enabled alarms
                val enabledAlarms = dao.getEnabledAlarms()
                for (alarm in enabledAlarms) {
                    AlarmScheduler.scheduleAlarm(context, alarm)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
