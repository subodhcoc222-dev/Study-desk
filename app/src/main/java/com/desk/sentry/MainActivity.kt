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
    private var isSentryArmed = true
    private var isAlwaysActiveMode = false
    private var absenceThresholdMs = 180000L // Default 3 mins buffer (Gold Standard)
    private var lastSeenTimestamp = System.currentTimeMillis()
    private var isPersonCurrentlyPresent = false
    private var isAnchorCurrentlyPresent = false
    private var lastAnchorSeenTimestamp = 0L
    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isUsingBackCamera = true
    private lateinit var audioManager: AudioManager

    // Anti-Ghosting Frame Confirmation
    private var sustainedPresentFrameCount = 0
    private var sustainedAbsentFrameCount = 0
    private val REQUIRED_FRAMES_TO_CONFIRM_PRESENT = 5
    private val REQUIRED_FRAMES_TO_CONFIRM_ABSENT = 6

    // Real-Time Analytics State
    private var currentActiveSlot: Int = -1
    private var lastTrackedSlot: Int = -1
    private var alarmTriggerStartMs: Long = 0L
    private var isAlarmCurrentlyTracking: Boolean = false

    // 2-Stage Auto Break Bank Tracking
    private var isCurrentlyTakingAutoBreak: Boolean = false
    private var autoBreakStartMs: Long = 0L

    // Rear Camera Alignment Sound State
    private var wasStationAlignedLastTick = false
    private var lastAdjustmentBeepMs = 0L

    // -------------------------------------------------------------
    // ADVANCED AI SPEECH, DEBOUNCE & ADAPTIVE RADAR STATES
    // -------------------------------------------------------------
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isTtsSpeaking = false

    private enum class AbsenceState { NONE, BUFFER, BREAK }
    private var currentAbsenceState = AbsenceState.NONE
    private var currentBufferTripNum = 0
    private var deskLostTimestamp = 0L
    private var hasAnnouncedExitForCurrentAbsence = false

    // 8-Second Debounce Threshold for Fallen Pens / Short Movements
    private val FALSE_EXIT_DEBOUNCE_MS = 8000L

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
    private lateinit var switchBgGuard: SwitchMaterial
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

    data class SlotViewHolder(
        val checkBox: CheckBox,
        val textView: TextView,
        val setButton: Button,
        val slotNum: Int
    )

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

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 85)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        initTTS()
        checkAllPermissions()
        if (prefs.getBoolean("bg_guard_enabled", true)) {
            startPersistentBackgroundService()
        }

        initViews()
        setupListeners()
        loadAllSlots()
        updateBufferLimitUI()
        initAlarmSound()

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

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
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
                isTtsReady = true
            }
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isTtsSpeaking = true
            }
            override fun onDone(utteranceId: String?) {
                isTtsSpeaking = false
            }
            override fun onError(utteranceId: String?) {
                isTtsSpeaking = false
            }
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
        } catch (e: Exception) {
            -1
        }
    }

    private fun checkAllPermissions() {
        if (!isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
                .setTitle("🔒 Enable 'Prevent Turn Off' & App Lock")
                .setMessage("To permanently lock Desk Sentry on screen and block power off, please turn ON 'Desk Sentry' in Accessibility Settings.")
                .setCancelable(false)
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                }
                .show()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, SentryAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expectedComponentName.flattenToString()) ||
               enabledServices.contains(expectedComponentName.flattenToShortString()) ||
               enabledServices.contains(SentryAccessibilityService::class.java.simpleName)
    }

    private fun startPersistentBackgroundService() {
        val serviceIntent = Intent(this, SentryService::class.java).apply {
            action = SentryService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopPersistentBackgroundService() {
        val serviceIntent = Intent(this, SentryService::class.java).apply {
            action = SentryService.ACTION_STOP
        }
        startService(serviceIntent)
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
        // Strict No-Negotiation: Back Button does nothing
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
        switchBgGuard = findViewById(R.id.switchBgGuard)
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

        slotViews.clear()
        slotViews.add(SlotViewHolder(findViewById(R.id.cbSlot1), findViewById(R.id.tvSlot1), findViewById(R.id.btnSetSlot1), 1))
        slotViews.add(SlotViewHolder(findViewById(R.id.cbSlot2), findViewById(R.id.tvSlot2), findViewById(R.id.btnSetSlot2), 2))
        slotViews.add(SlotViewHolder(findViewById(R.id.cbSlot3), findViewById(R.id.tvSlot3), findViewById(R.id.btnSetSlot3), 3))
        slotViews.add(SlotViewHolder(findViewById(R.id.cbSlot4), findViewById(R.id.tvSlot4), findViewById(R.id.btnSetSlot4), 4))
        slotViews.add(SlotViewHolder(findViewById(R.id.cbSlot5), findViewById(R.id.tvSlot5), findViewById(R.id.btnSetSlot5), 5))

        switchBgGuard.isChecked = prefs.getBoolean("bg_guard_enabled", true)
        updateAdminStatusUI()
    }

    private fun setupListeners() {
        switchMasterSentry.setOnClickListener {
            val targetState = switchMasterSentry.isChecked
            switchMasterSentry.isChecked = !targetState
            requirePinVerification("Modify Master Sentry State") {
                switchMasterSentry.isChecked = targetState
                isSentryArmed = targetState
                prefs.edit().putBoolean("sentry_armed", targetState).apply()
                if (!targetState) stopAlarmAndFinishAbsence()
            }
        }

        switchAlwaysActive.setOnClickListener {
            val targetState = switchAlwaysActive.isChecked
            switchAlwaysActive.isChecked = !targetState
            requirePinVerification("Toggle Override Mode") {
                switchAlwaysActive.isChecked = targetState
                isAlwaysActiveMode = targetState
                prefs.edit().putBoolean("always_active_mode", targetState).apply()
                lastSeenTimestamp = System.currentTimeMillis()
            }
        }

        switchBgGuard.setOnClickListener {
            val targetState = switchBgGuard.isChecked
            switchBgGuard.isChecked = !targetState
            val actionText = if (targetState) "Enable 24/7 Background Guard" else "Disable 24/7 Background Guard"
            requirePinVerification(actionText) {
                switchBgGuard.isChecked = targetState
                prefs.edit().putBoolean("bg_guard_enabled", targetState).apply()
                if (targetState) {
                    startPersistentBackgroundService()
                    Toast.makeText(this, "24/7 Guard Activated!", Toast.LENGTH_SHORT).show()
                } else {
                    stopPersistentBackgroundService()
                    Toast.makeText(this, "24/7 Guard Stopped!", Toast.LENGTH_SHORT).show()
                }
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
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTapTime < 500) {
                stealthOverlay.visibility = View.GONE
                dashboardLayout.visibility = View.VISIBLE
            }
            lastTapTime = currentTime
        }

        for (i in 0 until rgGracePeriod.childCount) {
            val rb = rgGracePeriod.getChildAt(i) as? RadioButton
            rb?.setOnClickListener {
                requirePinVerification("Change Free Buffer Duration") {
                    absenceThresholdMs = when (rb.id) {
                        R.id.rb10s -> 10000L
                        R.id.rb1m -> 60000L
                        R.id.rb2m -> 120000L
                        R.id.rb3m -> 180000L
                        else -> 180000L
                    }
                    rgGracePeriod.check(rb.id)
                }
            }
        }

        btnSetBufferLimit.setOnClickListener {
            requirePinVerification("Change Free Buffer Usage Limit") {
                showSetBufferLimitDialog()
            }
        }

        for (holder in slotViews) {
            holder.setButton.setOnClickListener {
                requirePinVerification("Edit Slot ${holder.slotNum} Schedule") {
                    showComprehensiveSlotDialog(holder.slotNum)
                }
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

        btnActivateAdmin.setOnClickListener {
            requirePinVerification("Enable Device Admin Protection") {
                requestDeviceAdmin()
            }
        }

        btnChangePin.setOnClickListener {
            showChangePinTwoStepWorkflow()
        }

        btnChangeAlarmTone.setOnClickListener {
            requirePinVerification("Select Alarm Sound") {
                openRingtonePicker()
            }
        }

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
            val currentUriStr = prefs.getString("custom_alarm_uri", null)
            if (currentUriStr != null) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentUriStr))
            }
        }
        ringtonePickerLauncher.launch(intent)
    }

    private fun getBufferUsedCount(slotNum: Int): Int {
        if (slotNum == -1) return 0
        val dateKey = getTodayDateKey()
        val key = "quick_buffer_used_${dateKey}_slot_$slotNum"
        return prefs.getInt(key, 0)
    }

    private fun incrementBufferUsedCount(slotNum: Int) {
        if (slotNum == -1) return
        val dateKey = getTodayDateKey()
        val key = "quick_buffer_used_${dateKey}_slot_$slotNum"
        val current = prefs.getInt(key, 0)
        prefs.edit().putInt(key, current + 1).apply()
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
        val container = LinearLayout(this).apply {
            setPadding(50, 30, 50, 10)
            addView(input)
        }

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
        val dayCalendarConsts = intArrayOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )

        val selectedDays = BooleanArray(7) { idx ->
            val calConst = dayCalendarConsts[idx]
            prefs.getBoolean("slot_${slotNumber}_day_$calConst", calConst != Calendar.SUNDAY)
        }

        val scrollView = ScrollView(this).apply { isFillViewport = true }
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 16, 30, 16)
        }

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 8)
        }

        val btnStartTime = Button(this).apply {
            text = "Start: ${formatTime(startH, startM)}"
            textSize = 12f
            setOnClickListener {
                TimePickerDialog(context, { _, h, m ->
                    startH = h
                    startM = m
                    text = "Start: ${formatTime(startH, startM)}"
                }, startH, startM, false).show()
            }
        }

        val btnEndTime = Button(this).apply {
            text = "End: ${formatTime(endH, endM)}"
            textSize = 12f
            setOnClickListener {
                TimePickerDialog(context, { _, h, m ->
                    endH = h
                    endM = m
                    text = "End: ${formatTime(endH, endM)}"
                }, endH, endM, false).show()
            }
        }

        timeRow.addView(btnStartTime, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 })
        timeRow.addView(btnEndTime, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        dialogView.addView(timeRow)

        val breakRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 8)
        }

        val tvBreakLabel = TextView(this).apply {
            text = "Slot Break Bank (Mins):"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }

        val etBreakMins = EditText(this).apply {
            hint = "30"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentBreakMins.toString())
            setTextColor(Color.BLACK)
            setBackgroundResource(android.R.drawable.edit_text)
            setPadding(16, 10, 16, 10)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
        }

        breakRow.addView(tvBreakLabel)
        breakRow.addView(etBreakMins)
        dialogView.addView(breakRow)

        val tvDaysHeader = TextView(this).apply {
            text = "Active Days for Slot $slotNumber:"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, 4, 0, 4)
        }
        dialogView.addView(tvDaysHeader)

        val daysRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val checkBoxes = ArrayList<CheckBox>()
        for (i in 0..6) {
            val cb = CheckBox(this).apply {
                text = dayNames[i]
                isChecked = selectedDays[i]
                textSize = 10f
                setPadding(2, 0, 4, 0)
            }
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
                    editor.putBoolean("slot_${slotNumber}_day_${dayCalendarConsts[i]}", isChecked)
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

    data class SlotTimeConfig(
        val startH: Int,
        val startM: Int,
        val endH: Int,
        val endM: Int,
        val defaultBreakMins: Int
    )

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
        val dateKey = getTodayDateKey()
        val key = "break_bank_${dateKey}_slot_$slotNum"
        val def = getDefaultSlotTimes(slotNum)
        val configuredMins = prefs.getInt("slot_${slotNum}_break_bank_mins", def.defaultBreakMins)
        return prefs.getLong(key, configuredMins * 60L)
    }

    private fun setRemainingBreakAllowanceSec(slotNum: Int, sec: Long) {
        if (slotNum == -1) return
        val dateKey = getTodayDateKey()
        val key = "break_bank_${dateKey}_slot_$slotNum"
        prefs.edit().putLong(key, sec.coerceAtLeast(0L)).apply()
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
            put("presentSec", 0L)
            put("absentSec", 0L)
            put("officialBreakSec", 0L)
            put("absences", JSONArray())
            put("breaks", JSONArray())
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

    private fun showFirstTimeSetPinDialog() {
        val input = EditText(this).apply {
            hint = "Create 4-digit Master PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.BLACK)
            setBackgroundResource(android.R.drawable.edit_text)
        }
        val container = LinearLayout(this).apply { setPadding(50, 30, 50, 10); addView(input) }

        val dialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🔑 Set Master Security PIN")
            .setMessage("Welcome to Desk Sentry! Please create your master PIN.")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Save PIN", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString().trim()
                if (pin.length >= 4) {
                    prefs.edit().putString("user_pin", pin).apply()
                    Toast.makeText(this, "Master PIN Saved!", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
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
                if (input.text.toString().trim() == savedPin) {
                    onVerified()
                } else {
                    Toast.makeText(this, "Incorrect PIN!", Toast.LENGTH_SHORT).show()
                }
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
        }
        val container = LinearLayout(this).apply { setPadding(50, 30, 50, 10); addView(inputOld) }

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🔑 Change Master PIN - Step 1/2")
            .setMessage("Enter current master PIN:")
            .setView(container)
            .setPositiveButton("Next") { _, _ ->
                if (inputOld.text.toString().trim() == savedPin) {
                    showNewPinPrompt()
                } else {
                    Toast.makeText(this, "Incorrect Current PIN!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewPinPrompt() {
        val inputNew = EditText(this).apply {
            hint = "New 4-digit PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.BLACK)
            setBackgroundResource(android.R.drawable.edit_text)
        }
        val container = LinearLayout(this).apply { setPadding(50, 30, 50, 10); addView(inputNew) }

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("🔑 Change Master PIN - Step 2/2")
            .setMessage("Enter your new master PIN:")
            .setView(container)
            .setPositiveButton("Update PIN") { _, _ ->
                val newPin = inputNew.text.toString().trim()
                if (newPin.length >= 4) {
                    prefs.edit().putString("user_pin", newPin).apply()
                    Toast.makeText(this, "Master PIN updated!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "PIN must be at least 4 digits!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

                    if (currentMinutes in start until end) {
                        return i
                    }
                }
            }
        }
        return -1
    }

    /**
     * 10-MINUTE PRE-SLOT SETUP WINDOW
     * Returns Pair(isPreSlotActive, slotNumber)
     */
    private fun getPreSlotWindowInfo(): Pair<Boolean, Int> {
        val isSentryArmed = prefs.getBoolean("sentry_armed", true)
        if (!isSentryArmed || isAlwaysActiveMode) return Pair(false, 0)

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
                    if (diff in 1..10) {
                        return Pair(true, i)
                    }
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

                val barcodeOptions = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
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
                    if (range != null && range.upper > 0) {
                        camera.cameraControl.setExposureCompensationIndex(range.upper.coerceAtMost(2))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                btnFlipCamera.text = if (isUsingBackCamera) "📷 Rear" else "📷 Front"
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * CALIBRATED SPECIFICALLY FOR USER'S DESK POSTURE (INCLUDING BOWED HEAD WRITING)
     */
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
            if (shoulderSpan < imgWidth * 0.10f || shoulderSpan > imgWidth * 0.95f) {
                return false
            }
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

        val refY = if (hasLeftShoulder && hasRightShoulder) {
            (leftShoulder!!.position.y + rightShoulder!!.position.y) / 2f
        } else if (hasLeftShoulder) {
            leftShoulder!!.position.y
        } else {
            rightShoulder!!.position.y
        }

        if (refY < imgHeight * 0.05f || refY > imgHeight * 0.98f) {
            return false
        }

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
                        if (sustainedPresentFrameCount >= REQUIRED_FRAMES_TO_CONFIRM_PRESENT) {
                            isPersonCurrentlyPresent = true
                        }
                    } else {
                        sustainedAbsentFrameCount++
                        sustainedPresentFrameCount = 0
                        if (sustainedAbsentFrameCount >= REQUIRED_FRAMES_TO_CONFIRM_ABSENT) {
                            isPersonCurrentlyPresent = false
                        }
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

            Tasks.whenAllComplete(poseTask, barcodeTask)
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    /**
     * DYNAMIC 3-STAGE PROXIMITY RADAR BEEP CALCULATOR
     * Adapts automatically to any buffer length or break bank.
     */
    private fun getAdaptiveBeepIntervalMs(isBreak: Boolean, remainingSec: Long, totalSec: Long): Long {
        return if (isBreak) {
            when {
                remainingSec > 120L -> 2000L  // Green Zone (> 2 mins left): Calm 2.0s
                remainingSec in 31L..120L -> 1000L // Yellow Zone (2m to 30s left): Alert 1.0s
                else -> 400L                  // Red Zone (Last 30s): Rapid 0.4s pulse
            }
        } else {
            when {
                totalSec <= 15L -> { // 10s testing buffer
                    when {
                        remainingSec > 4L -> 1500L
                        remainingSec in 2L..4L -> 800L
                        else -> 350L
                    }
                }
                totalSec <= 65L -> { // 1 min buffer
                    when {
                        remainingSec > 25L -> 2000L
                        remainingSec in 10L..25L -> 1000L
                        else -> 400L
                    }
                }
                else -> { // 2m or 3m Gold Standard buffer
                    when {
                        remainingSec > 60L -> 2000L  // Green Zone (3:00 to 1:00): Calm 2.0s
                        remainingSec in 20L..60L -> 1000L // Yellow Zone (1:00 to 0:20): Alert 1.0s
                        else -> 400L                 // Red Zone (Last 20s): Rapid 0.4s pulse
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

                // Detect Slot Completion
                if (lastTrackedSlot != -1 && activeSlot == -1 && !isAlwaysActiveMode) {
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
                }

                // ========================================================
                // 1. DUAL PRESENCE ENTRY & HARDWARE CHIME LOGIC
                // ========================================================
                if (isFullyVerifiedAtDesk) {
                    deskLostTimestamp = 0L
                    hasAnnouncedExitForCurrentAbsence = false

                    if (!wasStationAlignedLastTick) {
                        try {
                            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 300)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        wasStationAlignedLastTick = true

                        // ENTRY ANNOUNCEMENT FLOW
                        if (activeSlot != -1 && !slotLaunchAnnounced[activeSlot]) {
                            slotLaunchAnnounced[activeSlot] = true
                            mainHandler.postDelayed({
                                speak("Owner detected. Study Slot $activeSlot getting ready... 3... 2... 1... Go!")
                            }, 400)
                        } else if (isPreSlotActive && !preSlotReadyAnnounced[preSlotNum]) {
                            preSlotReadyAnnounced[preSlotNum] = true
                            val bat = getBatteryPercentage()
                            val batStr = if (bat > 0) "Battery $bat percent." else "Battery ready."
                            mainHandler.postDelayed({
                                speak("Camera ready. $batStr")
                            }, 400)
                        } else if (currentAbsenceState == AbsenceState.BUFFER) {
                            val maxBuffers = prefs.getInt("max_quick_buffer_count", 2)
                            val used = getBufferUsedCount(activeSlot)
                            val left = (maxBuffers - used).coerceAtLeast(0)
                            val leftStr = if (left == 1) "1 buffer left" else "$left buffers left"
                            val bNum = currentBufferTripNum
                            mainHandler.postDelayed({
                                speak("Buffer $bNum complete. $leftStr.")
                            }, 400)
                            currentAbsenceState = AbsenceState.NONE
                        } else if (currentAbsenceState == AbsenceState.BREAK) {
                            val remainingSec = getRemainingBreakAllowanceSec(activeSlot)
                            val mins = (remainingSec / 60).toInt()
                            mainHandler.postDelayed({
                                speak("Break paused. $mins minutes left.")
                            }, 400)
                            currentAbsenceState = AbsenceState.NONE
                        }
                    }
                } else {
                    wasStationAlignedLastTick = false
                    if (deskLostTimestamp == 0L) {
                        deskLostTimestamp = System.currentTimeMillis()
                    }

                    val awaySinceMs = System.currentTimeMillis() - deskLostTimestamp

                    // 8-SECOND FALSE-EXIT DEBOUNCE
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
                            if (mins >= 1) {
                                speak("Break on. $mins minutes left.")
                            } else {
                                speak("Break ending. Under one minute left.")
                            }
                            currentAbsenceState = AbsenceState.BREAK
                        }
                        hasAnnouncedExitForCurrentAbsence = true
                    }

                    // ========================================================
                    // 3-STAGE DYNAMIC AUDIO RADAR BEACON BEEP
                    // ========================================================
                    val shouldAudioAssistBeActive = isPreSlotActive || (activeSlot != -1 && hasAnnouncedExitForCurrentAbsence)

                    if (shouldAudioAssistBeActive && mediaPlayer?.isPlaying != true && !isTtsSpeaking) {
                        val now = System.currentTimeMillis()

                        val dynamicIntervalMs: Long = if (isPreSlotActive) {
                            2000L // Calm, unhurried pace during pre-slot camera setup
                        } else if (activeSlot != -1) {
                            if (currentAbsenceState == AbsenceState.BUFFER) {
                                val awayDurationMs = System.currentTimeMillis() - lastSeenTimestamp
                                val remainingBufferSec = ((absenceThresholdMs - awayDurationMs) / 1000L).coerceAtLeast(0L)
                                val totalBufferSec = absenceThresholdMs / 1000L
                                getAdaptiveBeepIntervalMs(false, remainingBufferSec, totalBufferSec)
                            } else if (currentAbsenceState == AbsenceState.BREAK) {
                                val remainingSec = getRemainingBreakAllowanceSec(activeSlot)
                                getAdaptiveBeepIntervalMs(true, remainingSec, 1800L)
                            } else {
                                2000L
                            }
                        } else {
                            2000L
                        }

                        if (now - lastAdjustmentBeepMs > dynamicIntervalMs) {
                            try {
                                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 80)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            lastAdjustmentBeepMs = now
                        }
                    }
                }

                // ========================================================
                // 2. CORE DISCIPLINE AND ALARM LOGIC
                // ========================================================
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

                            if (hasFreeBufferLeft && awayDurationMs <= absenceThresholdMs) {
                                val secondsLeft = ((absenceThresholdMs - awayDurationMs) / 1000).toInt()
                                tvLiveStatus.text = String.format("● QUICK BUFFER [Use %d/%d]", usedBufferCount, maxBuffers)
                                tvLiveStatus.setTextColor(Color.parseColor("#F59E0B"))
                                tvCountdown.text = "Buffer Remaining: ${secondsLeft}s (Radar Beacon Active)"
                                stopAlarmAndFinishAbsence()
                            } else {
                                if (remainingBankSec > 0) {
                                    val m = remainingBankSec / 60
                                    val s = remainingBankSec % 60
                                    val reason = if (!hasFreeBufferLeft) "BUFFERS EXHAUSTED" else "AUTO-DEDUCTING"
                                    val missingWhat = if (!isAnchorValid) "ANCHOR MISSING" else "USER AWAY"
                                    tvLiveStatus.text = "☕ ON BREAK ($missingWhat • $reason)"
                                    tvLiveStatus.setTextColor(Color.parseColor("#38BDF8"))
                                    tvCountdown.text = String.format("Break Bank Left: %02dm %02ds before Alarm (Radar Active)", m, s)
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
                mainHandler.postDelayed(this, 500)
            }
        })
    }

    private fun startPeriodicTimeTracker() {
        val trackerHandler = Handler(Looper.getMainLooper())
        trackerHandler.post(object : Runnable {
            override fun run() {
                if (currentActiveSlot != -1) {
                    val remainingBank = getRemainingBreakAllowanceSec(currentActiveSlot)
                    val usedBufferCount = getBufferUsedCount(currentActiveSlot)
                    val maxBuffers = prefs.getInt("max_quick_buffer_count", 2)
                    val hasFreeBufferLeft = usedBufferCount < maxBuffers
                    val isAnchorValid = (System.currentTimeMillis() - lastAnchorSeenTimestamp) < 3500L
                    val isFullyVerifiedAtDesk = isPersonCurrentlyPresent && isAnchorValid

                    // Low Battery Check (< 20%)
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
                            val isInBufferTime = hasFreeBufferLeft && awayDurationMs <= absenceThresholdMs

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
                recordAbsentInterval(currentActiveSlot, alarmTriggerStartMs, endMs, durationSec)
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
            put("presentSec", 0L)
            put("absentSec", 0L)
            put("officialBreakSec", 0L)
            put("absences", JSONArray())
            put("breaks", JSONArray())
        }

        val currentPresent = slotObj.optLong("presentSec", 0L)
        slotObj.put("presentSec", currentPresent + sec)
        slots.put(slotNum.toString(), slotObj)
        json.put("slots", slots)
        saveDayJson(dateKey, json)
    }

    private fun recordAbsentInterval(slotNum: Int, startMs: Long, endMs: Long, durationSec: Long) {
        val dateKey = getTodayDateKey()
        val json = getDayJson(dateKey)
        val slots = json.optJSONObject("slots") ?: JSONObject()
        val slotObj = slots.optJSONObject(slotNum.toString()) ?: JSONObject().apply {
            put("presentSec", 0L)
            put("absentSec", 0L)
            put("officialBreakSec", 0L)
            put("absences", JSONArray())
            put("breaks", JSONArray())
        }

        val currentAbsent = slotObj.optLong("absentSec", 0L)
        slotObj.put("absentSec", currentAbsent + durationSec)

        val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
        val item = JSONObject().apply {
            put("start", timeFormat.format(Date(startMs)))
            put("end", timeFormat.format(Date(endMs)))
            put("durationSec", durationSec)
        }

        val absences = slotObj.optJSONArray("absences") ?: JSONArray()
        absences.put(item)
        slotObj.put("absences", absences)

        slots.put(slotNum.toString(), slotObj)
        json.put("slots", slots)
        saveDayJson(dateKey, json)
    }

    private fun initAlarmSound() {
        try {
            mediaPlayer?.release()
            val customUriStr = prefs.getString("custom_alarm_uri", null)
            val alertUri = if (customUriStr != null) {
                Uri.parse(customUriStr)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alertUri)
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                isLooping = true
                prepare()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startAlarm() {
        try {
            val maxAlarmVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVol, 0)

            val maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVol, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (mediaPlayer?.isPlaying == false) {
            mediaPlayer?.start()
        }
    }

    private fun updateAdminStatusUI() {
        val compName = ComponentName(this, AdminReceiver::class.java)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(compName)) {
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
        val compName = ComponentName(this, AdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
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
        mediaPlayer?.release()
        mediaPlayer = null
        toneGenerator?.release()
        toneGenerator = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
