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
            // 1. Settings me Force Stop block karna
            if (packageName.contains("com.android.settings")) {
                val textContent = event.text.toString()
                if (textContent.contains("WAKELOCK", ignoreCase = true) ||
                    textContent.contains("Force stop", ignoreCase = true)
                ) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }

            // 2. Strict Kiosk: Dusre app ya launcher khulne par WAKELOCK wapas laayein
            if (packageName.isNotEmpty() &&
                packageName != applicationContext.packageName &&
                !packageName.contains("inputmethod")
            ) {
                val activeAlarmId = prefs.getLong("active_alarm_id", -1L)
                val isPunishment = prefs.getBoolean("is_punishment_active", false)
                val isWakeUp = prefs.getBoolean("is_wakeup_active", false)
                val cachedCurrentCheck = prefs.getInt("cached_current_check", 1)
                val cachedTotalChecks = prefs.getInt("cached_total_checks", 1)

                val lockdownIntent = Intent(applicationContext, AlarmTriggerActivity::class.java).apply {
                    putExtra("ALARM_ID", activeAlarmId)
                    putExtra("IS_PUNISHMENT", isPunishment)
                    putExtra("IS_WAKEUP_CHECK", isWakeUp)
                    putExtra("CURRENT_CHECK", cachedCurrentCheck)
                    putExtra("TOTAL_CHECKS", cachedTotalChecks)
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
