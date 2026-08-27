package com.desk.sentry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.util.Calendar

class SentryService : Service() {

    private val CHANNEL_ID = "DeskSentryServiceChannel"
    private val NOTIFICATION_ID = 9001
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var bgMediaPlayer: MediaPlayer? = null
    private lateinit var audioManager: AudioManager

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        var isAppInForeground = false
        var isMainActivityVisible = false
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("DeskSentryPrefs", Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "DeskSentry::BgLock"
        ).apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }

        initBackgroundAlarm()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("24/7 Desk Guard Active"))
        startWatchdogLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundSafely()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun initBackgroundAlarm() {
        try {
            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            bgMediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alertUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Desk Sentry Persistent Guard",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Enforces study sessions and sounds alarms."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Desk Sentry (No-Negotiation Mode)")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun startWatchdogLoop() {
        handler.post(object : Runnable {
            override fun run() {
                val isBgGuardEnabled = prefs.getBoolean("bg_guard_enabled", true)
                if (!isBgGuardEnabled) {
                    stopForegroundSafely()
                    stopSelf()
                    return
                }

                val activeSlot = getActiveStudySlot()

                // Only pull MainActivity if the user is outside the Desk Sentry app completely
                if (activeSlot != -1 && !isAppInForeground) {
                    val intent = Intent(applicationContext, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    }
                    startActivity(intent)
                }

                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun getActiveStudySlot(): Int {
        val isSentryArmed = prefs.getBoolean("sentry_armed", true)
        val isAlwaysActive = prefs.getBoolean("always_active_mode", false)

        if (!isSentryArmed) return -1
        if (isAlwaysActive) return 1

        val now = Calendar.getInstance()
        val currentDayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val defaultTimes = arrayOf(
            Pair(5, 9), Pair(10, 14), Pair(15, 18), Pair(19, 21), Pair(21, 23)
        )

        for (i in 1..5) {
            val isEnabled = prefs.getBoolean("slot_${i}_enabled", i <= 2)
            if (isEnabled) {
                val isDayActive = prefs.getBoolean("slot_${i}_day_$currentDayOfWeek", currentDayOfWeek != Calendar.SUNDAY)
                if (isDayActive) {
                    val idx = i - 1
                    val start = prefs.getInt("slot_${i}_start_h", defaultTimes[idx].first) * 60 + prefs.getInt("slot_${i}_start_m", 0)
                    val end = prefs.getInt("slot_${i}_end_h", defaultTimes[idx].second) * 60 + prefs.getInt("slot_${i}_end_m", if (idx == 4) 30 else 0)

                    if (currentMinutes in start until end) {
                        return i
                    }
                }
            }
        }
        return -1
    }

    private fun stopForegroundSafely() {
        handler.removeCallbacksAndMessages(null)
        try {
            if (bgMediaPlayer?.isPlaying == true) {
                bgMediaPlayer?.stop()
                bgMediaPlayer?.release()
                bgMediaPlayer = null
            }
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForegroundSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
