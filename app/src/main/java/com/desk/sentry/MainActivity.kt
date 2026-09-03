package com.desk.sentry

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var isSentryArmed = false
    private var isAlwaysActiveMode = false
    private var absenceThresholdMs = 180000L
    private var lastSeenTimestamp = System.currentTimeMillis()
    private var isPersonCurrentlyPresent = false
    private var isAnchorCurrentlyPresent = false
    private var lastAnchorSeenTimestamp = 0L
    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isUsingBackCamera = true
    private lateinit var audioManager: AudioManager

    private var sustainedPresentFrameCount = 0
    private var sustainedAbsentFrameCount = 0
    private val REQUIRED_FRAMES_TO_CONFIRM_PRESENT = 5
    private val REQUIRED_FRAMES_TO_CONFIRM_ABSENT = 6

    private var currentActiveSlot: Int = -1
    private var lastTrackedSlot: Int = -1
    private var alarmTriggerStartMs: Long = 0L
    private var isAlarmCurrentlyTracking: Boolean = false

    private var isCurrentlyTakingAutoBreak: Boolean = false
    private var autoBreakStartMs: Long = 0L
    private var wasStationAlignedLastTick = false

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isTtsSpeaking = false

    private enum class AbsenceState { NONE, BUFFER, BREAK }
    private var currentAbsenceState = AbsenceState.NONE
    private var currentBufferTripNum = 0
    private var deskLostTimestamp = 0L
    private var hasAnnouncedExitForCurrentAbsence = false

    // 5-Second False-Exit Debounce
    private val FALSE_EXIT_DEBOUNCE_MS = 5000L

    // 45-Second Placement Grace Window
    private var isArmingGraceActive = false
    private var armingGraceRemainingSec = 0

    // Ultra-Fast Dedicated Beep Loop Variables
    private var currentBeepIntervalMs = 2000L
    private var currentBeepTone = ToneGenerator.TONE_PROP_BEEP2
    private var currentBeepDurationMs = 70
    private var isAudioRadarActive = false

    private val slotLaunchAnnounced = BooleanArray(6) { false }
    private val preSlotReadyAnnounced = BooleanArray(6) { false }
    private var hasAnnouncedLowBattery = false

    // UI Components
    private lateinit var previewView: PreviewView
    private lateinit var tvLiveStatus: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var btnFlipCamera: Button
    private lateinit var btnOpenEvents: Button
    private lateinit var btnEnterStealth: Button
    private lateinit var switchMasterSentry: SwitchMaterial
    private lateinit var switchAlwaysActive: SwitchMaterial
    private lateinit var rgGracePeriod: RadioGroup
    private lateinit var tvBufferLimitTitle: TextView
    private lateinit var tvBufferRemainingLive: TextView
    private lateinit var btnSetBufferLimit: Button
    private lateinit var tvAdminStatus: TextView
    private lateinit var btnActivateAdmin: Button
    private lateinit var btnChangePin: Button
    private lateinit var btnChangeAlarmTone: Button
    private lateinit var btnTestAlarm: Button
    private lateinit var stealthOverlay: LinearLayout
    private lateinit var dashboardLayout: LinearLayout
    private lateinit var tvBreakBankHeader: TextView

    private val slotViews = ArrayList<SlotViewHolder>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTapTime = 0L

    private val ringtonePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                prefs.edit().putString("custom_alarm_uri", uri.toString()).apply()
                initAlarmSound()
                Toast.makeText(this, "New Alarm Sound Applied!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    data class SlotViewHolder(val checkBox: CheckBox, val textView: TextView, val setButton: Button, val slotNum: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("DeskSentryPrefs", Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        isSentryArmed = prefs.getBoolean("sentry_armed", false)

        try {
            // STREAM_ALARM with maximum 100% volume for dual-output punch
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        initTTS()
        checkAllPermissions()
        checkAndProcessDeviceShutdownRecovery()

        if (isSentryArmed) {
            startPersistentBackgroundService()
        }

        initViews()
        setupListeners()
        loadAllSlots()
        updateBufferLimitUI()
        initAlarmSound()

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        // Double-Confirmation PIN Setup on first launch
        if (!prefs.contains("user_pin")) {
            showFirstTimeSetPinDialog()
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }

        startMonitoringLoop()
        startPeriodicTimeTracker()
        startDedicatedRadarBeepEngine()
    }

    /**
     * SHUTDOWN / BATTERY DRAIN RECOVERY ENGINE:
     * Automatically logs any powered-off downtime during scheduled study slots as Absent.
     */
    private fun checkAndProcessDeviceShutdownRecovery() {
        val lastBeat = prefs.getLong("last_heartbeat_timestamp", 0L)
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last_heartbeat_timestamp", now).apply()

        if (lastBeat == 0L) return

        val downtimeMs = now - lastBeat
        if (downtimeMs > 40000L) {
            processDowntimeAbsence(lastBeat, now)
        }
    }

    private fun processDowntimeAbsence(offStartMs: Long, offEndMs: Long) {
        val calStart = Calendar.getInstance().apply { timeInMillis = offStartMs }
        val dayOfWeek = calStart.get(Calendar.DAY_OF_WEEK)

        for (i in 1..5) {
            val isEnabled = prefs.getBoolean("slot_${i}_enabled", i <= 2)
            val isDayActive = prefs.getBoolean("slot_${i}_day_$dayOfWeek", dayOfWeek != Calendar.SUNDAY)

            if (isEnabled && isDayActive) {
                val def = getDefaultSlotTimes(i)
                val sH = prefs.getInt("slot_${i}_start_h", def.startH)
                val sM = prefs.getInt("slot_${i}_start_m", def.startM)
                val eH = prefs.getInt("slot_${i}_end_h", def.endH)
                val eM = prefs.getInt("slot_${i}_end_m", def.endM)

                val slotStartCal = Calendar.getInstance().apply {
                    timeInMillis = offStartMs
                    set(Calendar.HOUR_OF_DAY, sH)
                    set(Calendar.MINUTE, sM)
                    set(Calendar.SECOND, 0)
                }

                val slotEndCal = Calendar.getInstance().apply {
                    timeInMillis = offStartMs
                    set(Calendar.HOUR_OF_DAY, eH)
                    set(Calendar.MINUTE, eM)
                    set(Calendar.SECOND, 0)
                }

                val overlapStart = maxOf(offStartMs, slotStartCal.timeInMillis)
                val overlapEnd = minOf(offEndMs, slotEndCal.timeInMillis)

                if (overlapEnd > overlapStart) {
                    val absentSec = (overlapEnd - overlapStart) / 1000L
                    if (absentSec >= 15L) {
                        recordAbsentInterval(i, overlapStart, overlapEnd, absentSec, "Device Powered Off / Drained")
                    }
                }
            }
        }
    }

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val inLocale = Locale("en", "IN")
                val result = tts?.setLanguage(inLocale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.ENGLISH)
                }
                tts?.setSpeechRate(0.85f)
                tts?.setPitch(1.0f)

                // DUAL ROUTING: USAGE_ALARM + FLAG_AUDIBILITY_ENFORCED
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
                isTtsReady = true
            }
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isTtsSpeaking = true }
            override fun onDone(utteranceId: String?) { isTtsSpeaking = false }
            override fun onError(utteranceId: String?) { isTtsSpeaking = false }
        })
    }

    private fun speak(text: String) {
        if (!isTtsReady || tts == null) return
        mainHandler.post {
            val utteranceId = UUID.randomUUID().toString()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    private fun getBatteryPercentage(): Int {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) { -1 }
    }

    private fun checkAllPermissions() {
        if (!isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
                .setTitle("🔒 Enable 'Prevent Turn Off' & App Lock")
                .setMessage("To permanently lock Desk Sentry on screen and block power off, please turn ON 'Desk Sentry' in Accessibility Settings.")
                .setCancelable(false)
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .show()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) { e.printStackTrace() }
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, SentryAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(expected.flattenToString()) || enabled.contains(expected.flattenToShortString()) || enabled.contains(SentryAccessibilityService::class.java.simpleName)
    }

    private fun startPersistentBackgroundService() {
        val intent = Intent(this, SentryService::class.java).apply { action = SentryService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(this, intent) else startService(intent)
    }

    private fun stopPersistentBackgroundService() {
        startService(Intent(this, SentryService::class.java).apply { action = SentryService.ACTION_STOP })
    }

    override fun onResume() {
        super.onResume()
        SentryService.isMainActivityVisible = true
        SentryService.lastAppActiveTimestamp = System.currentTimeMillis()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateAdminStatusUI()
        updateBreakBankUI()
        updateBufferLimitUI()
        if (allPermissionsGranted() && cameraProvider == null) startCamera()
    }

    override fun onPause() {
        super.onPause()
        SentryService.isMainActivityVisible = false
        SentryService.lastAppActiveTimestamp = System.currentTimeMillis()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!isSentryArmed) super.onBackPressed()
    }

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        tvLiveStatus = findViewById(R.id.tvLiveStatus)
        tvCountdown = findViewById(R.id.tvCountdown)
        btnFlipCamera = findViewById(R.id.btnFlipCamera)
        btnOpenEvents = findViewById(R.id.btnOpenEvents)
        btnEnterStealth = findViewById(R.id.btnEnterStealth)
        switchMasterSentry = findViewById(R.id.switchMasterSentry)
        switchAlwaysActive = findViewById(R.id.switchAlwaysActive)
        rgGracePeriod = findViewById(R.id.rgGracePeriod)
        tvBufferLimitTitle = findViewById(R.id.tvBufferLimitTitle)
        tvBufferRemainingLive = findViewById(R.id.tvBufferRemainingLive)
        btnSetBufferLimit = findViewById(R.id.btnSetBufferLimit)
        tvAdminStatus = findViewById(R.id.tvAdminStatus)
        btnActivateAdmin = findViewById(R.id.btnActivateAdmin)
        btnChangePin = findViewById(R.id.btnChangePin)
        btnChangeAlarmTone = findViewById(R.id.btnChangeAlarmTone)
        btnTestAlarm = findViewById(R.id.btnTestAlarm)
        stealthOverlay = findViewById(R.id.stealthOverlay)
        dashboardLayout = findViewById(R.id.dashboardLayout)
        tvBreakBankHeader = findViewById(R.id.tvBreakBankHeader)

        switchMasterSentry.isChecked = isSentryArmed
        switchAlwaysActive.isChecked = prefs.getBoolean("always_active_mode", false)

        slotViews.clear()
        slotViews.add(SlotViewHolder(findViewById(R.id.cbSlot1), findViewById(R.id.tvSlot1), findViewById(R.id.btnSetSlot1), 1))
        slotViews.add(SlotViewHolder(findViewById(R.id.cbSlot2), findViewById(R.id.tvSlot2), findViewById(R.id.btnSetSlot2), 2))
        slotViews.add(SlotViewHolder(findViewById(R.id.cbSlot3), findViewById(R.id.tvSlot3), findViewById(R.id.btnSetSlot3), 3))
        slotViews.add(SlotViewHolder(findViewById(R.id.cbSlot4), findViewById(R.id.tvSlot4), findViewById(R.id.btnSetSlot4), 4))
        slotViews.add(SlotViewHolder(findViewById(R.id.cbSlot5), findViewById(R.id.tvSlot5), findViewById(R.id.btnSetSlot5), 5))

        updateAdminStatusUI()
    }

    private fun setupListeners() {
        switchMasterSentry.setOnClickListener {
            val target = switchMasterSentry.isChecked
            switchMasterSentry.isChecked = !target

            if (target) {
                switchMasterSentry.isChecked = true
                isSentryArmed = true
                prefs.edit().putBoolean("sentry_armed", true).apply()
                startPersistentBackgroundService()
                isArmingGraceActive = true
                armingGraceRemainingSec = 45
                Toast.makeText(this, "Sentry Armed! 45s to place phone on stand.", Toast.LENGTH_LONG).show()
            } else {
                requirePinVerification("Disarm Master Sentry & Unlock Device") {
                    switchMasterSentry.isChecked = false
                    isSentryArmed = false
                    prefs.edit().putBoolean("sentry_armed", false).apply()
                    stopPersistentBackgroundService()
                    stopAlarmAndFinishAbsence()
                    isArmingGraceActive = false
                    isAudioRadarActive = false
                    Toast.makeText(this, "Sentry Disarmed. System Unlocked.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        switchAlwaysActive.setOnClickListener {
            val target = switchAlwaysActive.isChecked
            switchAlwaysActive.isChecked = !target
            requirePinVerification("Toggle Override Mode") {
                switchAlwaysActive.isChecked = target
                isAlwaysActiveMode = target
                prefs.edit().putBoolean("always_active_mode", target).apply()
                lastSeenTimestamp = System.currentTimeMillis()
            }
        }

        btnFlipCamera.setOnClickListener {
            isUsingBackCamera = !isUsingBackCamera
            startCamera()
        }

        btnOpenEvents.setOnClickListener {
            SentryService.isEventsActivityVisible = true
            SentryService.lastAppActiveTimestamp = System.currentTimeMillis()
            startActivity(Intent(this, EventsActivity::class.java))
        }

        btnEnterStealth.setOnClickListener {
            stealthOverlay.visibility = View.VISIBLE
            dashboardLayout.visibility = View.GONE
        }

        stealthOverlay.setOnClickListener {
            val cur = System.currentTimeMillis()
            if (cur - lastTapTime < 500) {
                stealthOverlay.visibility = View.GONE
                dashboardLayout.visibility = View.VISIBLE
            }
            lastTapTime = cur
        }

        for (i in 0 until rgGracePeriod.childCount) {
            val rb = rgGracePeriod.getChildAt(i) as? RadioButton
            rb?.setOnClickListener {
                requirePinVerification("Change Free Buffer Duration") {
                    absenceThresholdMs = when (rb.id) {
                        R.id.rb10s -> 10000L
                        R.id.rb1m -> 60000L
                        R.id.rb2m -> 120000L
                        else -> 180000L
                    }
                    rgGracePeriod.check(rb.id)
                }
            }
        }

        btnSetBufferLimit.setOnClickListener {
            requirePinVerification("Change Free Buffer Usage Limit") { showSetBufferLimitDialog() }
        }

        for (holder in slotViews) {
            holder.setButton.setOnClickListener {
                requirePinVerification("Edit Slot ${holder.slotNum} Schedule") { showComprehensiveSlotDialog(holder.slotNum) }
            }
            holder.checkBox.setOnClickListener {
                val target = holder.checkBox.isChecked
                holder.checkBox.isChecked = !target
                requirePinVerification("Enable/Disable Slot ${holder.slotNum}") {
                    holder.checkBox.isChecked = target
                    prefs.edit().putBoolean("slot_${holder.slotNum}_enabled", target).apply()
                }
            }
        }

        btnActivateAdmin.setOnClickListener { requirePinVerification("Enable Admin Protection") { requestDeviceAdmin() } }
        btnChangePin.setOnClickListener { showChangePinTwoStepWorkflow() }
        btnChangeAlarmTone.setOnClickListener { requirePinVerification("Select Alarm Sound") { openRingtonePicker() } }

        btnTestAlarm.setOnClickListener {
            if (mediaPlayer?.isPlaying == true) {
                stopAlarmAndFinishAbsence()
                btnTestAlarm.text = "🚨 Test"
            } else {
                startAlarm()
                btnTestAlarm.text = "⏹ Stop"
            }
        }
    }

    private fun openRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Irritating Alarm Tone")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            val currentUri = prefs.getString("custom_alarm_uri", null)
            if (currentUri != null) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentUri))
        }
        ringtonePickerLauncher.launch(intent)
    }

    private fun getBufferUsedCount(slotNum: Int): Int {
        if (slotNum == -1) return 0
        return prefs.getInt("quick_buffer_used_${getTodayDateKey()}_slot_$slotNum", 0)
    }

    private fun incrementBufferUsedCount(slotNum: Int) {
        if (slotNum == -1) return
        val key = "quick_buffer_used_${getTodayDateKey()}_slot_$slotNum"
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
        updateBufferLimitUI()
    }

    private fun updateBufferLimitUI() {
        val maxLimit = prefs.getInt("max_quick_buffer_count", 2)
        val slotNum = if (currentActiveSlot != -1) currentActiveSlot else 1
        val used = getBufferUsedCount(slotNum)
        val left = (maxLimit - used).coerceAtLeast(0)
        tvBufferLimitTitle.text = "🎯 Buffer Limit: $maxLimit Uses / Slot"
        tvBufferRemainingLive.text = "Slot $slotNum: $used Used • $left Left"
    }

    private fun showSetBufferLimitDialog() {
        val currentLimit = prefs.getInt("max_quick_buffer_count", 2)
        val input = EditText(this).apply {
            hint = "e.g. 2"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentLimit.toString())
            setTextColor(Color.BLACK)
            setBackgroundResource(android.R.drawable.edit_text)
            setPadding(30, 20, 30, 20)
        }
        val container = LinearLayout(this).apply { setPadding(50, 30, 50, 10); addView(input) }

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🎯 Set Quick Buffer Limit Per Slot")
            .setMessage("Enter max number of free quick buffer breaks allowed in each study slot:")
            .setView(container)
            .setPositiveButton("Save Limit") { _, _ ->
                val count = input.text.toString().trim().toIntOrNull() ?: currentLimit
                prefs.edit().putInt("max_quick_buffer_count", count.coerceAtLeast(0)).apply()
                updateBufferLimitUI()
                Toast.makeText(this, "Buffer limit updated to $count uses/slot!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showComprehensiveSlotDialog(slotNumber: Int) {
        val def = getDefaultSlotTimes(slotNumber)
        var startH = prefs.getInt("slot_${slotNumber}_start_h", def.startH)
        var startM = prefs.getInt("slot_${slotNumber}_start_m", def.startM)
        var endH = prefs.getInt("slot_${slotNumber}_end_h", def.endH)
        var endM = prefs.getInt("slot_${slotNumber}_end_m", def.endM)
        val currentBreakMins = prefs.getInt("slot_${slotNumber}_break_bank_mins", def.defaultBreakMins)

        val dayNames = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val dayConsts = intArrayOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
        val selectedDays = BooleanArray(7) { idx -> prefs.getBoolean("slot_${slotNumber}_day_${dayConsts[idx]}", dayConsts[idx] != Calendar.SUNDAY) }

        val scrollView = ScrollView(this).apply { isFillViewport = true }
        val dialogView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 16, 30, 16) }
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 8) }

        val btnStartTime = Button(this).apply {
            text = "Start: ${formatTime(startH, startM)}"
            textSize = 12f
            setOnClickListener {
                TimePickerDialog(context, { _, h, m -> startH = h; startM = m; text = "Start: ${formatTime(startH, startM)}" }, startH, startM, false).show()
            }
        }
        val btnEndTime = Button(this).apply {
            text = "End: ${formatTime(endH, endM)}"
            textSize = 12f
            setOnClickListener {
                TimePickerDialog(context, { _, h, m -> endH = h; endM = m; text = "End: ${formatTime(endH, endM)}" }, endH, endM, false).show()
            }
        }

        timeRow.addView(btnStartTime, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 })
        timeRow.addView(btnEndTime, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        dialogView.addView(timeRow)

        val breakRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 4, 0, 8) }
        val tvBreakLabel = TextView(this).apply { text = "Slot Break Bank (Mins):"; textSize = 12f; setTextColor(Color.DKGRAY); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f) }
        val etBreakMins = EditText(this).apply { hint = "30"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(currentBreakMins.toString()); setTextColor(Color.BLACK); setBackgroundResource(android.R.drawable.edit_text); setPadding(16, 10, 16, 10); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f) }

        breakRow.addView(tvBreakLabel)
        breakRow.addView(etBreakMins)
        dialogView.addView(breakRow)

        val daysRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val checkBoxes = ArrayList<CheckBox>()
        for (i in 0..6) {
            val cb = CheckBox(this).apply { text = dayNames[i]; isChecked = selectedDays[i]; textSize = 10f; setPadding(2, 0, 4, 0) }
            checkBoxes.add(cb)
            daysRow.addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        dialogView.addView(daysRow)
        scrollView.addView(dialogView)

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("⚙️ Configure Slot $slotNumber")
            .setView(scrollView)
            .setPositiveButton("Save Settings") { _, _ ->
                val editor = prefs.edit()
                editor.putInt("slot_${slotNumber}_start_h", startH)
                editor.putInt("slot_${slotNumber}_start_m", startM)
                editor.putInt("slot_${slotNumber}_end_h", endH)
                editor.putInt("slot_${slotNumber}_end_m", endM)
                editor.putBoolean("slot_${slotNumber}_enabled", true)
                val parsedMins = etBreakMins.text.toString().trim().toIntOrNull() ?: currentBreakMins
                editor.putInt("slot_${slotNumber}_break_bank_mins", parsedMins.coerceAtLeast(0))

                var activeDayCount = 0
                for (i in 0..6) {
                    val isChecked = checkBoxes[i].isChecked
                    editor.putBoolean("slot_${slotNumber}_day_${dayConsts[i]}", isChecked)
                    if (isChecked) activeDayCount++
                }
                editor.putString("slot_${slotNumber}_days", if (activeDayCount == 7) "All Days" else "$activeDayCount Days")
                editor.apply()
                loadAllSlots()
                updateBreakBankUI()
                Toast.makeText(this, "Slot $slotNumber Updated Successfully!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    data class SlotTimeConfig(val startH: Int, val startM: Int, val endH: Int, val endM: Int, val defaultBreakMins: Int)

    private fun getDefaultSlotTimes(slotNum: Int): SlotTimeConfig {
        return when (slotNum) {
            1 -> SlotTimeConfig(5, 0, 9, 0, 30)
            2 -> SlotTimeConfig(10, 0, 14, 0, 30)
            3 -> SlotTimeConfig(15, 0, 18, 0, 20)
            4 -> SlotTimeConfig(19, 0, 21, 0, 15)
            else -> SlotTimeConfig(21, 30, 23, 30, 15)
        }
    }

    private fun getRemainingBreakAllowanceSec(slotNum: Int): Long {
        if (slotNum == -1) return 1800L
        val key = "break_bank_${getTodayDateKey()}_slot_$slotNum"
        val def = getDefaultSlotTimes(slotNum)
        val configuredMins = prefs.getInt("slot_${slotNum}_break_bank_mins", def.defaultBreakMins)
        return prefs.getLong(key, configuredMins * 60L)
    }

    private fun setRemainingBreakAllowanceSec(slotNum: Int, sec: Long) {
        if (slotNum == -1) return
        prefs.edit().putLong("break_bank_${getTodayDateKey()}_slot_$slotNum", sec.coerceAtLeast(0L)).apply()
    }

    private fun updateBreakBankUI() {
        val slotNum = if (currentActiveSlot != -1) currentActiveSlot else 1
        val remainingSec = getRemainingBreakAllowanceSec(slotNum)
        val def = getDefaultSlotTimes(slotNum)
        val maxMins = prefs.getInt("slot_${slotNum}_break_bank_mins", def.defaultBreakMins)
        val m = remainingSec / 60
        val s = remainingSec % 60
        tvBreakBankHeader.text = String.format("☕ Break Bank: %02dm %02ds Left / %dm (Slot %d)", m, s, maxMins, slotNum)
    }

    private fun recordOfficialBreakInterval(slotNum: Int, startMs: Long, endMs: Long, durationSec: Long) {
        if (durationSec <= 0) return
        val dateKey = getTodayDateKey()
        val json = getDayJson(dateKey)
        val slots = json.optJSONObject("slots") ?: JSONObject()
        val slotObj = slots.optJSONObject(slotNum.toString()) ?: JSONObject().apply {
            put("presentSec", 0L); put("absentSec", 0L); put("officialBreakSec", 0L)
            put("absences", JSONArray()); put("breaks", JSONArray())
        }

        val curBreakSec = slotObj.optLong("officialBreakSec", 0L)
        slotObj.put("officialBreakSec", curBreakSec + durationSec)

        val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
        val item = JSONObject().apply {
            put("start", timeFormat.format(Date(startMs)))
            put("end", timeFormat.format(Date(endMs)))
            put("durationSec", durationSec)
        }
        val breaksArray = slotObj.optJSONArray("breaks") ?: JSONArray()
        breaksArray.put(item)
        slotObj.put("breaks", breaksArray)

        slots.put(slotNum.toString(), slotObj)
        json.put("slots", slots)
        saveDayJson(dateKey, json)
    }

    /**
     * DOUBLE-CONFIRMATION PIN SETUP:
     * Two input fields (Create PIN + Confirm PIN) preventing mistyping.
     */
    private fun showFirstTimeSetPinDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
        }

        val inputPin = EditText(this).apply {
            hint = "Create 4-digit Master PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.BLACK)
            setBackgroundResource(android.R.drawable.edit_text)
            setPadding(30, 24, 30, 24)
        }

        val inputConfirm = EditText(this).apply {
            hint = "Confirm Master PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.BLACK)
            setBackgroundResource(android.R.drawable.edit_text)
            setPadding(30, 24, 30, 24)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.topMargin = 20
            layoutParams = params
        }

        container.addView(inputPin)
        container.addView(inputConfirm)

        val dialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🔑 Set Master Security PIN")
            .setMessage("Welcome to Desk Sentry! Create your 4-digit PIN and confirm it below:")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Save PIN", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = inputPin.text.toString().trim()
                val confirm = inputConfirm.text.toString().trim()

                when {
                    pin.length < 4 -> {
                        Toast.makeText(this, "PIN must be at least 4 digits!", Toast.LENGTH_SHORT).show()
                    }
                    pin != confirm -> {
                        Toast.makeText(this, "PINs do not match! Please re-enter.", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        prefs.edit().putString("user_pin", pin).apply()
                        Toast.makeText(this, "Master PIN Saved Successfully!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun requirePinVerification(actionDescription: String, onVerified: () -> Unit) {
        val savedPin = prefs.getString("user_pin", "1234") ?: "1234"
        val input = EditText(this).apply {
            hint = "Enter Master PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.BLACK)
            setBackgroundResource(android.R.drawable.edit_text)
        }
        val container = LinearLayout(this).apply { setPadding(50, 30, 50, 10); addView(input) }

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🔒 PIN Required")
            .setMessage("Enter Master PIN to $actionDescription:")
            .setView(container)
            .setPositiveButton("Authorize") { _, _ ->
                if (input.text.toString().trim() == savedPin) onVerified() else Toast.makeText(this, "Incorrect PIN!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChangePinTwoStepWorkflow() {
        val savedPin = prefs.getString("user_pin", "1234") ?: "1234"
        val inputOld = EditText(this).apply {
            hint = "Current Master PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.BLACK)
            setBackgroundResource(android.R.drawable.edit_text)
            setPadding(30, 24, 30, 24)
        }
        val container = LinearLayout(this).apply { setPadding(50, 30, 50, 10); addView(inputOld) }

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🔑 Change Master PIN - Step 1/2")
            .setMessage("Enter your CURRENT master PIN:")
            .setView(container)
            .setPositiveButton("Next") { _, _ ->
                if (inputOld.text.toString().trim() == savedPin) showNewPinPrompt() else Toast.makeText(this, "Incorrect Current PIN!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewPinPrompt() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
        }

        val inputNew = EditText(this).apply {
            hint = "New 4-digit PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.BLACK)
            setBackgroundResource(android.R.drawable.edit_text)
            setPadding(30, 24, 30, 24)
        }

        val inputConfirm = EditText(this).apply {
            hint = "Confirm New PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.BLACK)
            setBackgroundResource(android.R.drawable.edit_text)
            setPadding(30, 24, 30, 24)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.topMargin = 20
            layoutParams = params
        }

        container.addView(inputNew)
        container.addView(inputConfirm)

        val dialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🔑 Change Master PIN - Step 2/2")
            .setMessage("Enter your new PIN and confirm it below:")
            .setView(container)
            .setPositiveButton("Update PIN", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newPin = inputNew.text.toString().trim()
                val confirmPin = inputConfirm.text.toString().trim()

                when {
                    newPin.length < 4 -> {
                        Toast.makeText(this, "PIN must be at least 4 digits!", Toast.LENGTH_SHORT).show()
                    }
                    newPin != confirmPin -> {
                        Toast.makeText(this, "New PINs do not match! Try again.", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        prefs.edit().putString("user_pin", newPin).apply()
                        Toast.makeText(this, "Master PIN updated successfully!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun loadAllSlots() {
        for (holder in slotViews) {
            val def = getDefaultSlotTimes(holder.slotNum)
            val isEnabled = prefs.getBoolean("slot_${holder.slotNum}_enabled", holder.slotNum <= 2)
            val startH = prefs.getInt("slot_${holder.slotNum}_start_h", def.startH)
            val startM = prefs.getInt("slot_${holder.slotNum}_start_m", def.startM)
            val endH = prefs.getInt("slot_${holder.slotNum}_end_h", def.endH)
            val endM = prefs.getInt("slot_${holder.slotNum}_end_m", def.endM)
            val daysStr = prefs.getString("slot_${holder.slotNum}_days", "Mon-Sat") ?: "Mon-Sat"
            val breakMins = prefs.getInt("slot_${holder.slotNum}_break_bank_mins", def.defaultBreakMins)

            holder.checkBox.isChecked = isEnabled
            holder.textView.text = "Slot ${holder.slotNum}: ${formatTime(startH, startM)} – ${formatTime(endH, endM)} [$daysStr] • Break: ${breakMins}m"
        }
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val amPm = if (hour >= 12) "PM" else "AM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format("%02d:%02d %s", displayHour, minute, amPm)
    }

    private fun getActiveStudySlot(): Int {
        if (!isSentryArmed) return -1
        if (isAlwaysActiveMode) return 1

        val now = Calendar.getInstance()
        val currentDayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        for (i in 1..5) {
            val isEnabled = prefs.getBoolean("slot_${i}_enabled", i <= 2)
            if (isEnabled) {
                val isDayActive = prefs.getBoolean("slot_${i}_day_$currentDayOfWeek", currentDayOfWeek != Calendar.SUNDAY)
                if (isDayActive) {
                    val def = getDefaultSlotTimes(i)
                    val start = prefs.getInt("slot_${i}_start_h", def.startH) * 60 + prefs.getInt("slot_${i}_start_m", def.startM)
                    val end = prefs.getInt("slot_${i}_end_h", def.endH) * 60 + prefs.getInt("slot_${i}_end_m", def.endM)

                    if (currentMinutes in start until end) return i
                }
            }
        }
        return -1
    }

    private fun getPreSlotWindowInfo(): Pair<Boolean, Int> {
        val isArmed = prefs.getBoolean("sentry_armed", false)
        if (!isArmed || isAlwaysActiveMode) return Pair(false, 0)

        val now = Calendar.getInstance()
        val currentDayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        for (i in 1..5) {
            val isEnabled = prefs.getBoolean("slot_${i}_enabled", i <= 2)
            if (isEnabled) {
                val isDayActive = prefs.getBoolean("slot_${i}_day_$currentDayOfWeek", currentDayOfWeek != Calendar.SUNDAY)
                if (isDayActive) {
                    val def = getDefaultSlotTimes(i)
                    val start = prefs.getInt("slot_${i}_start_h", def.startH) * 60 + prefs.getInt("slot_${i}_start_m", def.startM)
                    val diff = start - currentMinutes
                    if (diff in 1..10) return Pair(true, i)
                }
            }
        }
        return Pair(false, 0)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val cameraSelector = if (isUsingBackCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA

                val poseOptions = PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()
                val poseDetector = PoseDetection.getClient(poseOptions)

                val barcodeOptions = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                val barcodeScanner = BarcodeScanning.getClient(barcodeOptions)

                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                    processCameraFrame(imageProxy, poseDetector, barcodeScanner)
                }

                cameraProvider?.unbindAll()
                val camera = cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

                try {
                    val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange
                    if (range != null && range.upper > 0) camera.cameraControl.setExposureCompensationIndex(range.upper.coerceAtMost(2))
                } catch (e: Exception) { e.printStackTrace() }

                btnFlipCamera.text = if (isUsingBackCamera) "📷 Rear" else "📷 Front"
            } catch (e: Exception) { e.printStackTrace() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun isRealDeskUser(pose: Pose, imgWidth: Float, imgHeight: Float): Boolean {
        val minConfidence = 0.35f
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
        val leftEye = pose.getPoseLandmark(PoseLandmark.LEFT_EYE)
        val rightEye = pose.getPoseLandmark(PoseLandmark.RIGHT_EYE)
        val leftEar = pose.getPoseLandmark(PoseLandmark.LEFT_EAR)
        val rightEar = pose.getPoseLandmark(PoseLandmark.RIGHT_EAR)
        val leftMouth = pose.getPoseLandmark(PoseLandmark.LEFT_MOUTH)
        val rightMouth = pose.getPoseLandmark(PoseLandmark.RIGHT_MOUTH)
        val leftWrist = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
        val rightWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
        val leftElbow = pose.getPoseLandmark(PoseLandmark.LEFT_ELBOW)
        val rightElbow = pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW)

        val hasLeftShoulder = leftShoulder != null && leftShoulder.inFrameLikelihood >= minConfidence
        val hasRightShoulder = rightShoulder != null && rightShoulder.inFrameLikelihood >= minConfidence

        if (!hasLeftShoulder && !hasRightShoulder) return false

        if (hasLeftShoulder && hasRightShoulder) {
            val shoulderSpan = abs(leftShoulder!!.position.x - rightShoulder!!.position.x)
            if (shoulderSpan < imgWidth * 0.10f || shoulderSpan > imgWidth * 0.95f) return false
        }

        val hasHeadOrFace = (nose != null && nose.inFrameLikelihood >= 0.18f) ||
                (leftEye != null && leftEye.inFrameLikelihood >= 0.18f) ||
                (rightEye != null && rightEye.inFrameLikelihood >= 0.18f) ||
                (leftEar != null && leftEar.inFrameLikelihood >= 0.18f) ||
                (rightEar != null && rightEar.inFrameLikelihood >= 0.18f) ||
                (leftMouth != null && leftMouth.inFrameLikelihood >= 0.18f) ||
                (rightMouth != null && rightMouth.inFrameLikelihood >= 0.18f)

        val hasWritingArms = (leftWrist != null && leftWrist.inFrameLikelihood >= 0.25f) ||
                (rightWrist != null && rightWrist.inFrameLikelihood >= 0.25f) ||
                (leftElbow != null && leftElbow.inFrameLikelihood >= 0.25f) ||
                (rightElbow != null && rightElbow.inFrameLikelihood >= 0.25f)

        val refY = if (hasLeftShoulder && hasRightShoulder) (leftShoulder!!.position.y + rightShoulder!!.position.y) / 2f
        else if (hasLeftShoulder) leftShoulder!!.position.y else rightShoulder!!.position.y

        if (refY < imgHeight * 0.05f || refY > imgHeight * 0.98f) return false
        return hasHeadOrFace || hasWritingArms
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processCameraFrame(
        imageProxy: ImageProxy,
        poseDetector: com.google.mlkit.vision.pose.PoseDetector,
        barcodeScanner: com.google.mlkit.vision.barcode.BarcodeScanner
    ) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val isRotated = rotation == 90 || rotation == 270
            val effWidth = (if (isRotated) mediaImage.height else mediaImage.width).toFloat()
            val effHeight = (if (isRotated) mediaImage.width else mediaImage.height).toFloat()
            val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

            val poseTask = poseDetector.process(inputImage)
                .addOnSuccessListener { pose ->
                    val frameValid = isRealDeskUser(pose, effWidth, effHeight)
                    if (frameValid) {
                        sustainedPresentFrameCount++
                        sustainedAbsentFrameCount = 0
                        if (sustainedPresentFrameCount >= REQUIRED_FRAMES_TO_CONFIRM_PRESENT) isPersonCurrentlyPresent = true
                    } else {
                        sustainedAbsentFrameCount++
                        sustainedPresentFrameCount = 0
                        if (sustainedAbsentFrameCount >= REQUIRED_FRAMES_TO_CONFIRM_ABSENT) isPersonCurrentlyPresent = false
                    }
                }

            val barcodeTask = barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        lastAnchorSeenTimestamp = System.currentTimeMillis()
                        isAnchorCurrentlyPresent = true
                    } else {
                        isAnchorCurrentlyPresent = (System.currentTimeMillis() - lastAnchorSeenTimestamp) < 3500L
                    }
                }

            Tasks.whenAllComplete(poseTask, barcodeTask).addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    private fun startDedicatedRadarBeepEngine() {
        val radarHandler = Handler(Looper.getMainLooper())
        radarHandler.post(object : Runnable {
            override fun run() {
                if (isAudioRadarActive && mediaPlayer?.isPlaying != true && !isTtsSpeaking && toneGenerator != null) {
                    try {
                        toneGenerator?.startTone(currentBeepTone, currentBeepDurationMs)
                    } catch (e: Exception) { e.printStackTrace() }
                    radarHandler.postDelayed(this, currentBeepIntervalMs)
                } else {
                    radarHandler.postDelayed(this, 200)
                }
            }
        })
    }

    data class BeepProfile(val intervalMs: Long, val tone: Int, val durationMs: Int)

    private fun calculateUrgentRadarProfile(isBreak: Boolean, remainingSec: Long, totalSec: Long): BeepProfile {
        return if (isBreak) {
            when {
                remainingSec > 120L -> BeepProfile(2000L, ToneGenerator.TONE_PROP_BEEP2, 70)
                remainingSec in 31L..120L -> BeepProfile(700L, ToneGenerator.TONE_PROP_BEEP, 80)
                else -> BeepProfile(150L, ToneGenerator.TONE_PROP_BEEP, 60)
            }
        } else {
            when {
                totalSec <= 15L -> {
                    when {
                        remainingSec > 4L -> BeepProfile(1500L, ToneGenerator.TONE_PROP_BEEP2, 70)
                        remainingSec in 2L..4L -> BeepProfile(600L, ToneGenerator.TONE_PROP_BEEP, 70)
                        else -> BeepProfile(140L, ToneGenerator.TONE_PROP_BEEP, 50)
                    }
                }
                totalSec <= 65L -> {
                    when {
                        remainingSec > 25L -> BeepProfile(2000L, ToneGenerator.TONE_PROP_BEEP2, 70)
                        remainingSec in 10L..25L -> BeepProfile(700L, ToneGenerator.TONE_PROP_BEEP, 80)
                        else -> BeepProfile(150L, ToneGenerator.TONE_PROP_BEEP, 60)
                    }
                }
                else -> {
                    when {
                        remainingSec > 60L -> BeepProfile(2000L, ToneGenerator.TONE_PROP_BEEP2, 70)
                        remainingSec in 20L..60L -> BeepProfile(700L, ToneGenerator.TONE_PROP_BEEP, 80)
                        else -> BeepProfile(150L, ToneGenerator.TONE_PROP_BEEP, 60)
                    }
                }
            }
        }
    }

    private fun startMonitoringLoop() {
        mainHandler.post(object : Runnable {
            override fun run() {
                val activeSlot = getActiveStudySlot()
                val (isPreSlotActive, preSlotNum) = getPreSlotWindowInfo()

                if (lastTrackedSlot != -1 && activeSlot == -1 && !isAlwaysActiveMode && isSentryArmed) {
                    speak("Study Slot $lastTrackedSlot completed. Great session. You can take your break now.")
                }
                lastTrackedSlot = activeSlot
                currentActiveSlot = activeSlot

                updateBreakBankUI()
                updateBufferLimitUI()

                val isAnchorValid = (System.currentTimeMillis() - lastAnchorSeenTimestamp) < 3500L
                val isFullyVerifiedAtDesk = isPersonCurrentlyPresent && isAnchorValid

                if (isFullyVerifiedAtDesk) {
                    lastSeenTimestamp = System.currentTimeMillis()
                    if (isArmingGraceActive) isArmingGraceActive = false
                }

                if (!isSentryArmed) {
                    isAudioRadarActive = false
                    tvLiveStatus.text = "● SENTRY DISARMED (SAFE MODE)"
                    tvLiveStatus.setTextColor(Color.GRAY)
                    tvCountdown.text = "Normal Phone Mode • Turn on Master Sentry to Arm"
                    stopAlarmAndFinishAbsence()
                } else if (isArmingGraceActive) {
                    isAudioRadarActive = false
                    tvLiveStatus.text = "⏱️ PLACEMENT GRACE ACTIVE (${armingGraceRemainingSec}s)"
                    tvLiveStatus.setTextColor(Color.parseColor("#38BDF8"))
                    tvCountdown.text = "Place phone on stand and sit at desk. No alarms active."
                    stopAlarmAndFinishAbsence()
                } else {
                    if (isFullyVerifiedAtDesk) {
                        deskLostTimestamp = 0L
                        hasAnnouncedExitForCurrentAbsence = false
                        isAudioRadarActive = false

                        if (!wasStationAlignedLastTick) {
                            try { toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 300) } catch (e: Exception) { e.printStackTrace() }
                            wasStationAlignedLastTick = true

                            if (activeSlot != -1 && !slotLaunchAnnounced[activeSlot]) {
                                slotLaunchAnnounced[activeSlot] = true
                                mainHandler.postDelayed({ speak("Owner detected. Study Slot $activeSlot getting ready... 3... 2... 1... Go!") }, 400)
                            } else if (isPreSlotActive && !preSlotReadyAnnounced[preSlotNum]) {
                                preSlotReadyAnnounced[preSlotNum] = true
                                val bat = getBatteryPercentage()
                                val batStr = if (bat > 0) "Battery $bat percent." else "Battery ready."
                                mainHandler.postDelayed({ speak("Camera ready. $batStr") }, 400)
                            } else if (currentAbsenceState == AbsenceState.BUFFER) {
                                val maxBuffers = prefs.getInt("max_quick_buffer_count", 2)
                                val used = getBufferUsedCount(activeSlot)
                                val left = (maxBuffers - used).coerceAtLeast(0)
                                val leftStr = if (left == 1) "1 buffer left" else "$left buffers left"
                                val bNum = currentBufferTripNum
                                mainHandler.postDelayed({ speak("Buffer $bNum complete. $leftStr.") }, 400)
                                currentAbsenceState = AbsenceState.NONE
                            } else if (currentAbsenceState == AbsenceState.BREAK) {
                                val remainingSec = getRemainingBreakAllowanceSec(activeSlot)
                                val mins = (remainingSec / 60).toInt()
                                mainHandler.postDelayed({ speak("Break paused. $mins minutes left.") }, 400)
                                currentAbsenceState = AbsenceState.NONE
                            }
                        }
                    } else {
                        wasStationAlignedLastTick = false
                        if (deskLostTimestamp == 0L) deskLostTimestamp = System.currentTimeMillis()

                        val awaySinceMs = System.currentTimeMillis() - deskLostTimestamp

                        if (awaySinceMs >= FALSE_EXIT_DEBOUNCE_MS && !hasAnnouncedExitForCurrentAbsence && activeSlot != -1) {
                            val usedBufferCount = getBufferUsedCount(activeSlot)
                            val maxBuffers = prefs.getInt("max_quick_buffer_count", 2)

                            if (usedBufferCount < maxBuffers) {
                                incrementBufferUsedCount(activeSlot)
                                currentBufferTripNum = usedBufferCount + 1
                                val left = (maxBuffers - currentBufferTripNum).coerceAtLeast(0)
                                val leftStr = if (left == 1) "1 buffer left" else "$left buffers left"
                                speak("Buffer $currentBufferTripNum on. $leftStr.")
                                currentAbsenceState = AbsenceState.BUFFER
                            } else {
                                val remainingSec = getRemainingBreakAllowanceSec(activeSlot)
                                val mins = (remainingSec / 60).toInt()
                                if (mins >= 1) speak("Break on. $mins minutes left.") else speak("Break ending. Under one minute left.")
                                currentAbsenceState = AbsenceState.BREAK
                            }
                            hasAnnouncedExitForCurrentAbsence = true
                        }

                        if (hasAnnouncedExitForCurrentAbsence && currentAbsenceState == AbsenceState.BUFFER) {
                            val awayDurationMs = System.currentTimeMillis() - lastSeenTimestamp
                            if (awayDurationMs > absenceThresholdMs) {
                                currentAbsenceState = AbsenceState.BREAK
                                val remainingSec = getRemainingBreakAllowanceSec(activeSlot)
                                val mins = (remainingSec / 60).toInt()
                                if (mins >= 1) speak("Buffer $currentBufferTripNum expired. Break on. $mins minutes left.")
                                else speak("Buffer $currentBufferTripNum expired. Break ending. Under one minute left.")
                            }
                        }

                        val shouldAudioAssistBeActive = isPreSlotActive || (activeSlot != -1 && hasAnnouncedExitForCurrentAbsence)

                        if (shouldAudioAssistBeActive) {
                            if (isPreSlotActive) {
                                currentBeepIntervalMs = 2000L
                                currentBeepTone = ToneGenerator.TONE_PROP_BEEP2
                                currentBeepDurationMs = 70
                            } else if (activeSlot != -1) {
                                val profile = if (currentAbsenceState == AbsenceState.BUFFER) {
                                    val awayDurationMs = System.currentTimeMillis() - lastSeenTimestamp
                                    val remainingBufferSec = ((absenceThresholdMs - awayDurationMs) / 1000L).coerceAtLeast(0L)
                                    val totalBufferSec = absenceThresholdMs / 1000L
                                    calculateUrgentRadarProfile(false, remainingBufferSec, totalBufferSec)
                                } else if (currentAbsenceState == AbsenceState.BREAK) {
                                    val remainingSec = getRemainingBreakAllowanceSec(activeSlot)
                                    calculateUrgentRadarProfile(true, remainingSec, 1800L)
                                } else {
                                    BeepProfile(2000L, ToneGenerator.TONE_PROP_BEEP2, 70)
                                }
                                currentBeepIntervalMs = profile.intervalMs
                                currentBeepTone = profile.tone
                                currentBeepDurationMs = profile.durationMs
                            }
                            isAudioRadarActive = true
                        } else {
                            isAudioRadarActive = false
                        }
                    }

                    if (activeSlot == -1) {
                        if (isPreSlotActive) {
                            tvLiveStatus.text = "⏱️ PRE-SLOT SETUP (SLOT $preSlotNum)"
                            tvLiveStatus.setTextColor(Color.parseColor("#38BDF8"))
                            tvCountdown.text = if (isFullyVerifiedAtDesk) "Desk: Aligned ✓ Ready" else "Audio Beacon Active • Align Phone on Stand"
                        } else {
                            tvLiveStatus.text = "● STANDBY (OUTSIDE ACTIVE HOURS)"
                            tvLiveStatus.setTextColor(Color.GRAY)
                            tvCountdown.text = "Schedule: Inactive (Silent Mode)"
                        }
                        stopAlarmAndFinishAbsence()
                    } else {
                        val remainingBankSec = getRemainingBreakAllowanceSec(activeSlot)
                        val usedBufferCount = getBufferUsedCount(activeSlot)
                        val maxBuffers = prefs.getInt("max_quick_buffer_count", 2)
                        val hasFreeBufferLeft = usedBufferCount < maxBuffers

                        if (isFullyVerifiedAtDesk) {
                            tvLiveStatus.text = "● STATION LOCKED (STUDENT + ANCHOR VERIFIED)"
                            tvLiveStatus.setTextColor(Color.parseColor("#22C55E"))
                            val m = remainingBankSec / 60
                            val s = remainingBankSec % 60
                            val leftBuff = (maxBuffers - usedBufferCount).coerceAtLeast(0)
                            tvCountdown.text = String.format("Desk: Verified ✓ | Buffers: %d left | Bank: %02dm %02ds", leftBuff, m, s)
                            stopAlarmAndFinishAbsence()
                        } else {
                            val awaySinceMs = if (deskLostTimestamp > 0L) System.currentTimeMillis() - deskLostTimestamp else 0L

                            if (awaySinceMs < FALSE_EXIT_DEBOUNCE_MS) {
                                val remainingGraceSec = ((FALSE_EXIT_DEBOUNCE_MS - awaySinceMs) / 1000).toInt() + 1
                                tvLiveStatus.text = "● VERIFYING MOVEMENT (${remainingGraceSec}s)"
                                tvLiveStatus.setTextColor(Color.parseColor("#FBBF24"))
                                tvCountdown.text = "Hold on: Movement detected (Checking if seat abandoned)"
                                stopAlarmAndFinishAbsence()
                            } else {
                                val awayDurationMs = System.currentTimeMillis() - lastSeenTimestamp

                                if (currentAbsenceState == AbsenceState.BUFFER && awayDurationMs <= absenceThresholdMs) {
                                    val secondsLeft = ((absenceThresholdMs - awayDurationMs) / 1000).toInt()
                                    tvLiveStatus.text = String.format("● QUICK BUFFER [Use %d/%d]", currentBufferTripNum, maxBuffers)
                                    tvLiveStatus.setTextColor(Color.parseColor("#F59E0B"))
                                    tvCountdown.text = "Buffer Remaining: ${secondsLeft}s (Radar Active: ${currentBeepIntervalMs}ms)"
                                    stopAlarmAndFinishAbsence()
                                } else {
                                    if (remainingBankSec > 0) {
                                        val m = remainingBankSec / 60
                                        val s = remainingBankSec % 60
                                        val reason = if (!hasFreeBufferLeft) "BUFFERS EXHAUSTED" else "BUFFER EXPIRED"
                                        val missingWhat = if (!isAnchorValid) "ANCHOR MISSING" else "USER AWAY"
                                        tvLiveStatus.text = "☕ ON BREAK ($missingWhat • $reason)"
                                        tvLiveStatus.setTextColor(Color.parseColor("#38BDF8"))
                                        tvCountdown.text = String.format("Break Bank Left: %02dm %02ds before Alarm (Radar: %dms)", m, s, currentBeepIntervalMs)
                                        stopAlarmAndFinishAbsence()
                                    } else {
                                        tvLiveStatus.text = "⚠ BREAK EXHAUSTED: ALARM ACTIVE"
                                        tvLiveStatus.setTextColor(Color.parseColor("#EF4444"))
                                        tvCountdown.text = "STATUS: 100% MAX ALARM RINGING (RETURN TO DESK)"

                                        if (!isAlarmCurrentlyTracking) {
                                            alarmTriggerStartMs = System.currentTimeMillis()
                                            isAlarmCurrentlyTracking = true
                                        }
                                        startAlarm()
                                    }
                                }
                            }
                        }
                    }
                }
                mainHandler.postDelayed(this, 500)
            }
        })
    }

    private fun startPeriodicTimeTracker() {
        val trackerHandler = Handler(Looper.getMainLooper())
        trackerHandler.post(object : Runnable {
            override fun run() {
                prefs.edit().putLong("last_heartbeat_timestamp", System.currentTimeMillis()).apply()

                if (isArmingGraceActive) {
                    armingGraceRemainingSec--
                    if (armingGraceRemainingSec <= 0) isArmingGraceActive = false
                }

                if (isSentryArmed && !isArmingGraceActive && currentActiveSlot != -1) {
                    val remainingBank = getRemainingBreakAllowanceSec(currentActiveSlot)
                    val isAnchorValid = (System.currentTimeMillis() - lastAnchorSeenTimestamp) < 3500L
                    val isFullyVerifiedAtDesk = isPersonCurrentlyPresent && isAnchorValid

                    val bat = getBatteryPercentage()
                    if (bat in 1..20 && !hasAnnouncedLowBattery) {
                        hasAnnouncedLowBattery = true
                        speak("Alert: Battery low at $bat percent. Please connect charger.")
                    } else if (bat > 25) {
                        hasAnnouncedLowBattery = false
                    }

                    if (isFullyVerifiedAtDesk) {
                        incrementPresentTime(currentActiveSlot, 1)

                        if (isCurrentlyTakingAutoBreak) {
                            val durationSec = ((System.currentTimeMillis() - autoBreakStartMs) / 1000L).coerceAtLeast(1L)
                            recordOfficialBreakInterval(currentActiveSlot, autoBreakStartMs, System.currentTimeMillis(), durationSec)
                            isCurrentlyTakingAutoBreak = false
                        }
                    } else {
                        val awaySinceMs = if (deskLostTimestamp > 0L) System.currentTimeMillis() - deskLostTimestamp else 0L

                        if (awaySinceMs >= FALSE_EXIT_DEBOUNCE_MS) {
                            val awayDurationMs = System.currentTimeMillis() - lastSeenTimestamp
                            val isInBufferTime = (currentAbsenceState == AbsenceState.BUFFER) && (awayDurationMs <= absenceThresholdMs)

                            if (!isInBufferTime) {
                                if (remainingBank > 0) {
                                    setRemainingBreakAllowanceSec(currentActiveSlot, remainingBank - 1)

                                    if (!isCurrentlyTakingAutoBreak) {
                                        autoBreakStartMs = System.currentTimeMillis()
                                        isCurrentlyTakingAutoBreak = true
                                    }
                                } else {
                                    if (isCurrentlyTakingAutoBreak) {
                                        val durationSec = ((System.currentTimeMillis() - autoBreakStartMs) / 1000L).coerceAtLeast(1L)
                                        recordOfficialBreakInterval(currentActiveSlot, autoBreakStartMs, System.currentTimeMillis(), durationSec)
                                        isCurrentlyTakingAutoBreak = false
                                    }
                                }
                            }
                        }
                    }
                }
                trackerHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun stopAlarmAndFinishAbsence() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            mediaPlayer?.seekTo(0)
        }
        if (isAlarmCurrentlyTracking) {
            val endMs = System.currentTimeMillis()
            val durationSec = (endMs - alarmTriggerStartMs) / 1000
            if (durationSec > 0 && currentActiveSlot != -1) {
                recordAbsentInterval(currentActiveSlot, alarmTriggerStartMs, endMs, durationSec, "User Left Desk")
            }
            isAlarmCurrentlyTracking = false
            alarmTriggerStartMs = 0L
        }
    }

    private fun getTodayDateKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private fun getDayJson(dateKey: String): JSONObject {
        val raw = prefs.getString("event_data_$dateKey", null)
        return if (raw != null) JSONObject(raw) else {
            JSONObject().apply {
                put("date", dateKey)
                put("dayName", SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateKey) ?: Date()))
                put("slots", JSONObject())
            }
        }
    }

    private fun saveDayJson(dateKey: String, json: JSONObject) {
        prefs.edit().putString("event_data_$dateKey", json.toString()).apply()
        val existingDates = prefs.getStringSet("event_dates_set", HashSet()) ?: HashSet()
        val newSet = HashSet(existingDates)
        newSet.add(dateKey)
        prefs.edit().putStringSet("event_dates_set", newSet).apply()
    }

    private fun incrementPresentTime(slotNum: Int, sec: Long) {
        val dateKey = getTodayDateKey()
        val json = getDayJson(dateKey)
        val slots = json.optJSONObject("slots") ?: JSONObject()
        val slotObj = slots.optJSONObject(slotNum.toString()) ?: JSONObject().apply {
            put("presentSec", 0L); put("absentSec", 0L); put("officialBreakSec", 0L)
            put("absences", JSONArray()); put("breaks", JSONArray())
        }

        slotObj.put("presentSec", slotObj.optLong("presentSec", 0L) + sec)
        slots.put(slotNum.toString(), slotObj)
        json.put("slots", slots)
        saveDayJson(dateKey, json)
    }

    private fun recordAbsentInterval(slotNum: Int, startMs: Long, endMs: Long, durationSec: Long, reason: String = "User Away") {
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(startMs))
        val json = getDayJson(dateKey)
        val slots = json.optJSONObject("slots") ?: JSONObject()
        val slotObj = slots.optJSONObject(slotNum.toString()) ?: JSONObject().apply {
            put("presentSec", 0L); put("absentSec", 0L); put("officialBreakSec", 0L)
            put("absences", JSONArray()); put("breaks", JSONArray())
        }

        slotObj.put("absentSec", slotObj.optLong("absentSec", 0L) + durationSec)

        val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
        val item = JSONObject().apply {
            put("start", timeFormat.format(Date(startMs)))
            put("end", timeFormat.format(Date(endMs)))
            put("durationSec", durationSec)
            put("reason", reason)
        }

        val absences = slotObj.optJSONArray("absences") ?: JSONArray()
        absences.put(item)
        slotObj.put("absences", absences)

        slots.put(slotNum.toString(), slotObj)
        json.put("slots", slots)
        saveDayJson(dateKey, json)
    }

    /**
     * DUAL-OUTPUT ALARM ROUTING:
     * Plays through both internal phone speaker and 3.5mm connected speaker simultaneously.
     */
    private fun initAlarmSound() {
        try {
            mediaPlayer?.release()
            val customUri = prefs.getString("custom_alarm_uri", null)
            val alertUri = if (customUri != null) Uri.parse(customUri)
            else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED) // Dual hardware routing flag
                .build()

            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alertUri)
                setAudioAttributes(audioAttributes)
                isLooping = true
                prepare()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun startAlarm() {
        try {
            val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
        } catch (e: Exception) { e.printStackTrace() }

        if (mediaPlayer?.isPlaying == false) mediaPlayer?.start()
    }

    private fun updateAdminStatusUI() {
        val comp = ComponentName(this, AdminReceiver::class.java)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(comp)) {
            tvAdminStatus.text = "Anti-Uninstall: Active ✓"
            tvAdminStatus.setTextColor(Color.parseColor("#22C55E"))
            btnActivateAdmin.isEnabled = false
            btnActivateAdmin.text = "Protected"
        } else {
            tvAdminStatus.text = "Anti-Uninstall: Inactive ✗"
            tvAdminStatus.setTextColor(Color.parseColor("#EF4444"))
            btnActivateAdmin.isEnabled = true
            btnActivateAdmin.text = "Enable Admin"
        }
    }

    private fun requestDeviceAdmin() {
        val comp = ComponentName(this, AdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Protects Desk Sentry.")
        }
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera()
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(baseContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        isAudioRadarActive = false
        mediaPlayer?.release()
        mediaPlayer = null
        toneGenerator?.release()
        toneGenerator = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
