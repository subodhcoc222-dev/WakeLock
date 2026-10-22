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

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_REBOOT
        ) {
            val pendingResult = goAsync()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val prefs = context.getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
                    val isAlarmActive = prefs.getBoolean("is_alarm_active", false)
                    val isSurveillanceActive = prefs.getBoolean("is_surveillance_active", false)
                    val isPunishmentActive = prefs.getBoolean("is_punishment_active", false)
                    val activeAlarmId = prefs.getLong("active_alarm_id", -1L)

                    val db = WakelockDatabase.getDatabase(context)
                    val dbSurveillance = db.alarmDao().getActiveSurveillanceAlarm()
                    val dbPunishment = db.alarmDao().getActivePunishmentAlarm()

                    // अगर फोन अलार्म बजते वक्त, वेकअप-चेक के दौरान या पेनल्टी के दौरान रीस्टार्ट हुआ -> TAMPER DETECTED!
                    val isTamperDetected = isAlarmActive || isSurveillanceActive || isPunishmentActive ||
                            dbSurveillance != null || dbPunishment != null

                    if (isTamperDetected) {
                        val targetId = when {
                            activeAlarmId != -1L -> activeAlarmId
                            dbSurveillance != null -> dbSurveillance.id
                            dbPunishment != null -> dbPunishment.id
                            else -> db.alarmDao().getAllAlarms().firstOrNull()?.id ?: -1L
                        }

                        val punishmentIntent = Intent(context, PunishmentGauntletService::class.java).apply {
                            action = PunishmentGauntletService.ACTION_START_PUNISHMENT
                            putExtra(PunishmentGauntletService.EXTRA_ALARM_ID, targetId)
                        }
                        ContextCompat.startForegroundService(context, punishmentIntent)
                    } else {
                        // सामान्य रीबूट: सभी सक्रिय अलार्म्स को दोबारा री-शेड्यूल करें
                        val alarms = db.alarmDao().getAllAlarms()
                        alarms.filter { it.isEnabled }.forEach { alarm ->
                            AlarmScheduler.scheduleAlarm(context, alarm)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
