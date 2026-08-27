package com.desk.sentry

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
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

        // If both Master Switch and 24/7 Guard are turned OFF with PIN, allow normal phone use
        if (!isSentryArmed && !isBgGuardEnabled) return

        val pkgName = event?.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // 1. IF USER IS INSIDE DESK SENTRY (MainActivity or EventsActivity), ALLOW IT
        if (pkgName == packageName) {
            SentryService.isAppInForeground = true
            return
        }

        // 2. PREVENT TURN OFF (Block Power Off / Restart Dialogs Instantly in 0.01s)
        if (className.contains("GlobalActions", ignoreCase = true) ||
            className.contains("PowerDialog", ignoreCase = true) ||
            className.contains("Shutdown", ignoreCase = true) ||
            pkgName.contains("power", ignoreCase = true) ||
            pkgName.contains("shutdown", ignoreCase = true)) {
            
            performGlobalAction(GLOBAL_ACTION_BACK)
            bringAppToFront()
            return
        }

        // 3. ALLOW SYSTEM KEYBOARD (So user can type PIN)
        if (pkgName.contains("inputmethod") || pkgName.contains("keyboard")) {
            return
        }

        // 4. HARD SNAP-BACK: User pressed Home, Back, Recent Apps, or opened another app
        SentryService.isAppInForeground = false
        bringAppToFront()
    }

    private fun bringAppToFront() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }

            // Using PendingIntent bypasses Android 10-14 & Oppo Background Launch blocks
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent.send()
        } catch (e: Exception) {
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(intent)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    override fun onInterrupt() {}
}
