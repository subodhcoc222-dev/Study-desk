package com.desk.sentry

import android.Manifest
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.Calendar
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var isSentryArmed = true
    private var isAlwaysActiveMode = false
    private var absenceThresholdMs = 180000L // 3 min default
    private var lastSeenTimestamp = System.currentTimeMillis()
    private var isPersonCurrentlyPresent = false
    private var mediaPlayer: MediaPlayer? = null

    // UI elements
    private lateinit var previewView: PreviewView
    private lateinit var tvLiveStatus: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvSlot1Display: TextView
    private lateinit var tvSlot2Display: TextView
    private lateinit var btnEditSlot1: Button
    private lateinit var btnEditSlot2: Button
    private lateinit var tvAdminStatus: TextView
    private lateinit var btnActivateAdmin: Button
    private lateinit var switchMasterSentry: SwitchMaterial
    private lateinit var switchAlwaysActive: SwitchMaterial
    private lateinit var rgGracePeriod: RadioGroup
    private lateinit var btnEnterStealth: Button
    private lateinit var btnChangePin: Button
    private lateinit var btnTestAlarm: Button
    private lateinit var stealthOverlay: LinearLayout
    private lateinit var dashboardLayout: LinearLayout

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTapTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("DeskSentryPrefs", Context.MODE_PRIVATE)

        initViews()
        setupListeners()
        updateSlotDisplays()
        initAlarmSound()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }

        startMonitoringLoop()
    }

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        tvLiveStatus = findViewById(R.id.tvLiveStatus)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvSlot1Display = findViewById(R.id.tvSlot1Display)
        tvSlot2Display = findViewById(R.id.tvSlot2Display)
        btnEditSlot1 = findViewById(R.id.btnEditSlot1)
        btnEditSlot2 = findViewById(R.id.btnEditSlot2)
        tvAdminStatus = findViewById(R.id.tvAdminStatus)
        btnActivateAdmin = findViewById(R.id.btnActivateAdmin)
        switchMasterSentry = findViewById(R.id.switchMasterSentry)
        switchAlwaysActive = findViewById(R.id.switchAlwaysActive)
        rgGracePeriod = findViewById(R.id.rgGracePeriod)
        btnEnterStealth = findViewById(R.id.btnEnterStealth)
        btnChangePin = findViewById(R.id.btnChangePin)
        btnTestAlarm = findViewById(R.id.btnTestAlarm)
        stealthOverlay = findViewById(R.id.stealthOverlay)
        dashboardLayout = findViewById(R.id.dashboardLayout)

        updateAdminStatusUI()
    }

    private fun setupListeners() {
        switchMasterSentry.setOnCheckedChangeListener { _, isChecked ->
            isSentryArmed = isChecked
            if (!isChecked) stopAlarm()
        }

        switchAlwaysActive.setOnCheckedChangeListener { _, isChecked ->
            isAlwaysActiveMode = isChecked
        }

        rgGracePeriod.setOnCheckedChangeListener { _, checkedId ->
            absenceThresholdMs = when (checkedId) {
                R.id.rb30s -> 30000L
                R.id.rb1m -> 60000L
                R.id.rb5m -> 300000L
                else -> 180000L
            }
        }

        btnEditSlot1.setOnClickListener { showSetSlotDialog(1) }
        btnEditSlot2.setOnClickListener { showSetSlotDialog(2) }

        btnActivateAdmin.setOnClickListener { requestDeviceAdmin() }

        btnEnterStealth.setOnClickListener {
            stealthOverlay.visibility = View.VISIBLE
            dashboardLayout.visibility = View.GONE
        }

        stealthOverlay.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTapTime < 500) {
                showUnlockPinDialog()
            }
            lastTapTime = currentTime
        }

        btnChangePin.setOnClickListener { showChangePinDialog() }

        btnTestAlarm.setOnClickListener {
            if (mediaPlayer?.isPlaying == true) {
                stopAlarm()
                btnTestAlarm.text = "Test Loud Alarm"
            } else {
                startAlarm()
                btnTestAlarm.text = "Stop Alarm Test"
            }
        }
    }

    private fun showSetSlotDialog(slotNumber: Int) {
        val defaultStart = if (slotNumber == 1) 5 else 10
        val defaultEnd = if (slotNumber == 1) 9 else 14

        val currentStartHour = prefs.getInt("slot_${slotNumber}_start_h", defaultStart)
        val currentStartMin = prefs.getInt("slot_${slotNumber}_start_m", 0)
        val currentEndHour = prefs.getInt("slot_${slotNumber}_end_h", defaultEnd)
        val currentEndMin = prefs.getInt("slot_${slotNumber}_end_m", 0)

        // Pick Start Time
        val startPicker = TimePickerDialog(this, { _, startH, startM ->
            // Pick End Time
            val endPicker = TimePickerDialog(this, { _, endH, endM ->
                prefs.edit().apply {
                    putInt("slot_${slotNumber}_start_h", startH)
                    putInt("slot_${slotNumber}_start_m", startM)
                    putInt("slot_${slotNumber}_end_h", endH)
                    putInt("slot_${slotNumber}_end_m", endM)
                    apply()
                }
                updateSlotDisplays()
                Toast.makeText(this, "Slot $slotNumber Updated!", Toast.LENGTH_SHORT).show()
            }, currentEndHour, currentEndMin, false)
            endPicker.setTitle("Set Slot $slotNumber End Time")
            endPicker.show()
        }, currentStartHour, currentStartMin, false)

        startPicker.setTitle("Set Slot $slotNumber Start Time")
        startPicker.show()
    }

    private fun updateSlotDisplays() {
        val s1StartH = prefs.getInt("slot_1_start_h", 5)
        val s1StartM = prefs.getInt("slot_1_start_m", 0)
        val s1EndH = prefs.getInt("slot_1_end_h", 9)
        val s1EndM = prefs.getInt("slot_1_end_m", 0)

        val s2StartH = prefs.getInt("slot_2_start_h", 10)
        val s2StartM = prefs.getInt("slot_2_start_m", 0)
        val s2EndH = prefs.getInt("slot_2_end_h", 14)
        val s2EndM = prefs.getInt("slot_2_end_m", 0)

        tvSlot1Display.text = "Slot 1: ${formatTime(s1StartH, s1StartM)} – ${formatTime(s1EndH, s1EndM)}"
        tvSlot2Display.text = "Slot 2: ${formatTime(s2StartH, s2StartM)} – ${formatTime(s2EndH, s2EndM)}"
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

    private fun isEffectiveStudyTime(): Boolean {
        if (!isSentryArmed) return false
        if (isAlwaysActiveMode) return true

        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val s1Start = prefs.getInt("slot_1_start_h", 5) * 60 + prefs.getInt("slot_1_start_m", 0)
        val s1End = prefs.getInt("slot_1_end_h", 9) * 60 + prefs.getInt("slot_1_end_m", 0)

        val s2Start = prefs.getInt("slot_2_start_h", 10) * 60 + prefs.getInt("slot_2_start_m", 0)
        val s2End = prefs.getInt("slot_2_end_h", 14) * 60 + prefs.getInt("slot_2_end_m", 0)

        val inSlot1 = currentMinutes in s1Start until s1End
        val inSlot2 = currentMinutes in s2Start until s2End

        return inSlot1 || inSlot2
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val options = PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
            val poseDetector = PoseDetection.getClient(options)

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                processCameraFrame(imageProxy, poseDetector)
            }

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                // Backlight Compensation: Boost exposure
                val range = camera.cameraInfo.exposureState.exposureCompensationRange
                if (range.upper > 0) {
                    camera.cameraControl.setExposureCompensationIndex(range.upper.coerceAtMost(2))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processCameraFrame(imageProxy: ImageProxy, poseDetector: com.google.mlkit.vision.pose.PoseDetector) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            poseDetector.process(image)
                .addOnSuccessListener { pose ->
                    isPersonCurrentlyPresent = pose.allPoseLandmarks.isNotEmpty()
                    if (isPersonCurrentlyPresent) {
                        lastSeenTimestamp = System.currentTimeMillis()
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun startMonitoringLoop() {
        mainHandler.post(object : Runnable {
            override fun run() {
                val isMonitoring = isEffectiveStudyTime()

                if (!isMonitoring) {
                    tvLiveStatus.text = "● STANDBY (OUTSIDE STUDY HOURS)"
                    tvLiveStatus.setTextColor(Color.GRAY)
                    tvCountdown.text = "Schedule: Inactive"
                    stopAlarm()
                } else {
                    if (isPersonCurrentlyPresent) {
                        tvLiveStatus.text = "● USER PRESENT & DETECTED"
                        tvLiveStatus.setTextColor(Color.parseColor("#22C55E"))
                        tvCountdown.text = "Desk Status: Normal"
                        stopAlarm()
                    } else {
                        val awayDuration = System.currentTimeMillis() - lastSeenTimestamp
                        val remainingMs = absenceThresholdMs - awayDuration

                        if (remainingMs <= 0) {
                            tvLiveStatus.text = "⚠ ALARM TRIGGERED: USER AWAY"
                            tvLiveStatus.setTextColor(Color.parseColor("#EF4444"))
                            tvCountdown.text = "STATUS: ALARM ACTIVE"
                            startAlarm()
                        } else {
                            val secondsLeft = (remainingMs / 1000).toInt()
                            tvLiveStatus.text = "● USER AWAY FROM DESK"
                            tvLiveStatus.setTextColor(Color.parseColor("#F59E0B"))
                            tvCountdown.text = "Grace Timeout: ${secondsLeft}s remaining"
                            stopAlarm()
                        }
                    }
                }
                mainHandler.postDelayed(this, 500)
            }
        })
    }

    private fun initAlarmSound() {
        try {
            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer().apply {
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

    private fun startAlarm() {
        if (mediaPlayer?.isPlaying == false) {
            mediaPlayer?.start()
        }
    }

    private fun stopAlarm() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            mediaPlayer?.seekTo(0)
        }
    }

    private fun showUnlockPinDialog() {
        val input = EditText(this).apply {
            hint = "Enter 4-digit PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.BLACK)
            setBackgroundResource(android.R.drawable.edit_text)
        }

        val container = LinearLayout(this).apply {
            setPadding(50, 30, 50, 10)
            addView(input)
        }

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("Unlock Desk Sentry")
            .setMessage("Enter PIN to return to Dashboard / Mute Alarm:")
            .setView(container)
            .setPositiveButton("Unlock") { _, _ ->
                val savedPin = prefs.getString("user_pin", "1234") ?: "1234"
                if (input.text.toString().trim() == savedPin) {
                    stealthOverlay.visibility = View.GONE
                    dashboardLayout.visibility = View.VISIBLE
                    stopAlarm()
                    Toast.makeText(this, "Unlocked Successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Incorrect PIN!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChangePinDialog() {
        val input = EditText(this).apply {
            hint = "New 4-digit PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.BLACK)
            setBackgroundResource(android.R.drawable.edit_text)
        }

        val container = LinearLayout(this).apply {
            setPadding(50, 30, 50, 10)
            addView(input)
        }

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("Set Master Security PIN")
            .setView(container)
            .setPositiveButton("Save PIN") { _, _ ->
                val newPin = input.text.toString().trim()
                if (newPin.length >= 4) {
                    prefs.edit().putString("user_pin", newPin).apply()
                    Toast.makeText(this, "New PIN Saved!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Protects Desk Sentry from being uninstalled during study sessions.")
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        updateAdminStatusUI()
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
