package com.anekar.wakelock.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.anekar.wakelock.data.database.WakelockDatabase
import kotlinx.coroutines.runBlocking

class WakelockAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "WAKELOCK // Tamper Protection Armed", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val isUnderSurveillance = runBlocking {
            val db = WakelockDatabase.getDatabase(context)
            val surveillance = db.alarmDao().getActiveSurveillanceAlarm()
            val punishment = db.alarmDao().getActivePunishmentAlarm()
            surveillance != null || punishment != null
        }

        return if (isUnderSurveillance) {
            "CRITICAL WARNING: Tamper-proof protocol is currently active. Deactivating device admin is restricted until all cycles complete."
        } else {
            "WAKELOCK protection will be deactivated."
        }
    }
}
