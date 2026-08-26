package com.desk.sentry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            val prefs = context.getSharedPreferences("DeskSentryPrefs", Context.MODE_PRIVATE)
            val isBgGuardEnabled = prefs.getBoolean("bg_guard_enabled", true)

            // Only start service if 24/7 background guard is ON
            if (isBgGuardEnabled) {
                val serviceIntent = Intent(context, SentryService::class.java).apply {
                    action = SentryService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
