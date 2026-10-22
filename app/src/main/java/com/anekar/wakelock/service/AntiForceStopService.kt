package com.anekar.wakelock.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.anekar.wakelock.ui.AlarmTriggerActivity

class AntiForceStopService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: ""

        val prefs = getSharedPreferences("wakelock_prefs", Context.MODE_PRIVATE)
        val isLockdown = prefs.getBoolean("is_lockdown_active", false)

        if (isLockdown) {
            // 1. Settings me Force Stop karne ki koshish ko block karna
            if (packageName.contains("com.android.settings")) {
                val textContent = event.text.toString()
                if (textContent.contains("WAKELOCK", ignoreCase = true) ||
                    textContent.contains("Force stop", ignoreCase = true)
                ) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }

            // 2. Strict Kiosk: Agar koi dusra app ya launcher khulne ki koshish kare toh WAKELOCK ko wapas upar lao
            if (packageName.isNotEmpty() &&
                packageName != applicationContext.packageName &&
                !packageName.contains("inputmethod") // Keyboard ko block na karein
            ) {
                val activeAlarmId = prefs.getLong("active_alarm_id", -1L)
                val isPunishment = prefs.getBoolean("is_punishment_active", false)
                val isWakeUp = prefs.getBoolean("is_wakeup_active", false)

                val lockdownIntent = Intent(applicationContext, AlarmTriggerActivity::class.java).apply {
                    putExtra("ALARM_ID", activeAlarmId)
                    putExtra("IS_PUNISHMENT", isPunishment)
                    putExtra("IS_WAKEUP_CHECK", isWakeUp)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                startActivity(lockdownIntent)
            }
        }
    }

    override fun onInterrupt() {}
}
