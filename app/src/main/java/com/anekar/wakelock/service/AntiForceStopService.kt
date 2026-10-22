package com.anekar.wakelock.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.anekar.wakelock.data.database.WakelockDatabase
import kotlinx.coroutines.runBlocking

class AntiForceStopService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: ""

        // Intercept if user enters Android System Settings
        if (packageName.contains("com.android.settings")) {
            val isLocked = runBlocking {
                val db = WakelockDatabase.getDatabase(applicationContext)
                val surveillance = db.alarmDao().getActiveSurveillanceAlarm()
                val punishment = db.alarmDao().getActivePunishmentAlarm()
                surveillance != null || punishment != null
            }

            if (isLocked) {
                val textContent = event.text.toString()
                if (textContent.contains("WAKELOCK", ignoreCase = true) ||
                    textContent.contains("Force stop", ignoreCase = true)
                ) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
        }
    }

    override fun onInterrupt() {}
}
