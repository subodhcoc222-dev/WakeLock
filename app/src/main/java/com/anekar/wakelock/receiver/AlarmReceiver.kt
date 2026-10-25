package com.anekar.wakelock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.anekar.wakelock.service.AlarmForegroundService

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private var wakeLock: PowerManager.WakeLock? = null

        fun acquireWakeLock(context: Context) {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "WAKELOCK::AlarmReceiverWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(60 * 1000L)
        }

        fun releaseWakeLock() {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        acquireWakeLock(context)

        // Reset check index to 0 for initial scheduled alarm
        val prefs = context.getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("failed_check_index", 0).apply()

        val alarmId = intent.getLongExtra("ALARM_ID", -1L)

        val serviceIntent = Intent(context, AlarmForegroundService::class.java).apply {
            action = AlarmForegroundService.ACTION_START_ALARM
            putExtra(AlarmForegroundService.EXTRA_ALARM_ID, alarmId)
            putExtra("FAILED_CHECK_INDEX", 0)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
