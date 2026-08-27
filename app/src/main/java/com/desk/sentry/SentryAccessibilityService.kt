package com.desk.sentry

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent

class SentryAccessibilityService : AccessibilityService() {

    private lateinit var prefs: SharedPreferences

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("DeskSentryPrefs", Context.MODE_PRIVATE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::prefs.isInitialized) {
            prefs = getSharedPreferences("DeskSentryPrefs", Context.MODE_PRIVATE)
        }

        val isSentryArmed = prefs.getBoolean("sentry_armed", true)
        val isBgGuardEnabled = prefs.getBoolean("bg_guard_enabled", true)

        // Only enforce lock when Master Switch or Background Guard is ON
        if (!isSentryArmed && !isBgGuardEnabled) return

        val pkgName = event?.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // 1. PREVENT TURN OFF (Block Power Off / Restart Dialog)
        if (className.contains("GlobalActions", ignoreCase = true) ||
            className.contains("PowerDialog", ignoreCase = true) ||
            className.contains("Shutdown", ignoreCase = true) ||
            pkgName.contains("power", ignoreCase = true)) {
            
            // Instantly dismiss the power menu
            performGlobalAction(GLOBAL_ACTION_BACK)
            bringAppToFront()
            return
        }

        // 2. PREVENT ESCAPING THE APP (Block Home, Launcher, Recent Apps, Other Apps)
        if (pkgName != packageName && 
            pkgName != "com.android.settings" && // Allow settings only if explicitly needed
            !pkgName.contains("camera", ignoreCase = true)) {
            bringAppToFront()
        }
    }

    private fun bringAppToFront() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(intent)
    }

    override fun onInterrupt() {}
}
