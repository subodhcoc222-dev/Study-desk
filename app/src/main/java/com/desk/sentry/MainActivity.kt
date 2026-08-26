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
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
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
    private var cameraProvider: ProcessCameraProvider? = null
    private var isUsingBackCamera = true

    // Anti-flicker frame counters
    private var consecutivePresentFrames = 0
    private var consecutiveAbsentFrames = 0

    // UI Elements
    private lateinit var previewView: PreviewView
    private lateinit var tvLiveStatus: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var btnFlipCamera: Button
    private lateinit var btnEnterStealth: Button
    private lateinit var switchMasterSentry: SwitchMaterial
    private lateinit var switchAlwaysActive: SwitchMaterial
    private lateinit var rgGracePeriod: RadioGroup
    private lateinit var tvAdminStatus: TextView
    private lateinit var btnActivateAdmin: Button
    private lateinit var btnChangePin: Button
    private lateinit var btnTestAlarm: Button
    private lateinit var stealthOverlay: LinearLayout
    private lateinit var dashboardLayout: LinearLayout

    private val slotViews = ArrayList<SlotViewHolder>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTapTime = 0L

    data class SlotViewHolder(
        val checkBox: CheckBox,
        val textView: TextView,
        val setButton: Button,
        val slotNum: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("DeskSentryPrefs", Context.MODE_PRIVATE)

        initViews()
        setupListeners()
        loadAllSlots()
        initAlarmSound()

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

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
        btnFlipCamera = findViewById(R.id.btnFlipCamera)
        btnEnterStealth = findViewById(R.id.btnEnterStealth)
        switchMasterSentry = findViewById(R.id.switchMasterSentry)
        switchAlwaysActive = findViewById(R.id.switchAlwaysActive)
        rgGracePeriod = findViewById(R.id.rgGracePeriod)
        tvAdminStatus = findViewById(R.id.tvAdminStatus)
        btnActivateAdmin = findViewById(R.id.btnActivateAdmin)
        btnChangePin = findViewById(R.id.btnChangePin)
        btnTestAlarm = findViewById(R.id.btnTestAlarm)
        stealthOverlay = findViewById(R.id.stealthOverlay)
        dashboardLayout = findViewById(R.id.dashboardLayout)

        slotViews.clear()
        slotViews.add(SlotViewHolder(findViewById(R.id.cbSlot1), findViewById(R.id.tvSlot1), findViewById(R.id.btnSetSlot1), 1))
        slotViews.add(SlotViewHolder(findViewById(R.id.cbSlot2), findViewById(R.id.tvSlot2), findViewById(R.id.btnSetSlot2), 2))
        slotViews.add(SlotViewHolder(findViewById(R.id.cbSlot3), findViewById(R.id.tvSlot3), findViewById(R.id.btnSetSlot3), 3))
        slotViews.add(SlotViewHolder(findViewById(R.id.cbSlot4), findViewById(R.id.tvSlot4), findViewById(R.id.btnSetSlot4), 4))
        slotViews.add(SlotViewHolder(findViewById(R.id.cbSlot5), findViewById(R.id.tvSlot5), findViewById(R.id.btnSetSlot5), 5))

        updateAdminStatusUI()
    }

    private fun setupListeners() {
        switchMasterSentry.setOnCheckedChangeListener { _, isChecked ->
            isSentryArmed = isChecked
            if (!isChecked) stopAlarm()
        }

        switchAlwaysActive.setOnCheckedChangeListener { _, isChecked ->
            isAlwaysActiveMode = isChecked
            lastSeenTimestamp = System.currentTimeMillis()
        }

        btnFlipCamera.setOnClickListener {
            isUsingBackCamera = !isUsingBackCamera
            startCamera()
        }

        rgGracePeriod.setOnCheckedChangeListener { _, checkedId ->
            absenceThresholdMs = when (checkedId) {
                R.id.rb30s -> 30000L
                R.id.rb1m -> 60000L
                R.id.rb5m -> 300000L
                else -> 180000L
            }
        }

        for (holder in slotViews) {
            holder.setButton.setOnClickListener { showSetSlotDialog(holder.slotNum) }
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("slot_${holder.slotNum}_enabled", isChecked).apply()
            }
        }

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

    private fun loadAllSlots() {
        val defaultTimes = arrayOf(
            Pair(5, 9),
            Pair(10, 14),
            Pair(15, 18),
            Pair(19, 21),
            Pair(21, 23)
        )

        for (holder in slotViews) {
            val idx = holder.slotNum - 1
            val isEnabled = prefs.getBoolean("slot_${holder.slotNum}_enabled", idx < 2)
            val startH = prefs.getInt("slot_${holder.slotNum}_start_h", defaultTimes[idx].first)
            val startM = prefs.getInt("slot_${holder.slotNum}_start_m", 0)
            val endH = prefs.getInt("slot_${holder.slotNum}_end_h", defaultTimes[idx].second)
            val endM = prefs.getInt("slot_${holder.slotNum}_end_m", if (idx == 4) 30 else 0)

            holder.checkBox.isChecked = isEnabled
            holder.textView.text = "Slot ${holder.slotNum}: ${formatTime(startH, startM)} – ${formatTime(endH, endM)}"
        }
    }

    private fun showSetSlotDialog(slotNumber: Int) {
        val currentStartHour = prefs.getInt("slot_${slotNumber}_start_h", 6)
        val currentStartMin = prefs.getInt("slot_${slotNumber}_start_m", 0)
        val currentEndHour = prefs.getInt("slot_${slotNumber}_end_h", 9)
        val currentEndMin = prefs.getInt("slot_${slotNumber}_end_m", 0)

        val startPicker = TimePickerDialog(this, { _, startH, startM ->
            val endPicker = TimePickerDialog(this, { _, endH, endM ->
                prefs.edit().apply {
                    putInt("slot_${slotNumber}_start_h", startH)
                    putInt("slot_${slotNumber}_start_m", startM)
                    putInt("slot_${slotNumber}_end_h", endH)
                    putInt("slot_${slotNumber}_end_m", endM)
                    putBoolean("slot_${slotNumber}_enabled", true)
                    apply()
                }
                loadAllSlots()
                Toast.makeText(this, "Slot $slotNumber Saved & Enabled!", Toast.LENGTH_SHORT).show()
            }, currentEndHour, currentEndMin, false)
            endPicker.setTitle("Set Slot $slotNumber End Time")
            endPicker.show()
        }, currentStartHour, currentStartMin, false)

        startPicker.setTitle("Set Slot $slotNumber Start Time")
        startPicker.show()
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

        for (i in 1..5) {
            val isEnabled = prefs.getBoolean("slot_${i}_enabled", i <= 2)
            if (isEnabled) {
                val start = prefs.getInt("slot_${i}_start_h", 0) * 60 + prefs.getInt("slot_${i}_start_m", 0)
                val end = prefs.getInt("slot_${i}_end_h", 0) * 60 + prefs.getInt("slot_${i}_end_m", 0)
                if (currentMinutes in start until end) {
                    return true
                }
            }
        }
        return false
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val cameraSelector = if (isUsingBackCamera) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }

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

                cameraProvider?.unbindAll()
                val camera = cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                try {
                    val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange
                    if (range != null && range.upper > 0) {
                        camera.cameraControl.setExposureCompensationIndex(range.upper.coerceAtMost(2))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                btnFlipCamera.text = if (isUsingBackCamera) "📷 Lens: Rear" else "📷 Lens: Front"

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * ACCURATE UPPER BODY DETECTION FILTER
     * Eliminates false positives from chandeliers, coolers, shadows, and furniture.
     */
    private fun isRealUserPresent(pose: Pose): Boolean {
        val minConfidence = 0.65f // Must be at least 65% confident

        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val leftEye = pose.getPoseLandmark(PoseLandmark.LEFT_EYE)
        val rightEye = pose.getPoseLandmark(PoseLandmark.RIGHT_EYE)
        val leftEar = pose.getPoseLandmark(PoseLandmark.LEFT_EAR)
        val rightEar = pose.getPoseLandmark(PoseLandmark.RIGHT_EAR)

        val hasNose = nose != null && nose.inFrameLikelihood >= minConfidence
        val hasLeftShoulder = leftShoulder != null && leftShoulder.inFrameLikelihood >= minConfidence
        val hasRightShoulder = rightShoulder != null && rightShoulder.inFrameLikelihood >= minConfidence
        val hasEyesOrEars = (leftEye != null && leftEye.inFrameLikelihood >= minConfidence) ||
                            (rightEye != null && rightEye.inFrameLikelihood >= minConfidence) ||
                            (leftEar != null && leftEar.inFrameLikelihood >= minConfidence) ||
                            (rightEar != null && rightEar.inFrameLikelihood >= minConfidence)

        // Rule: Must detect (Both Shoulders) OR (Head/Face AND At Least One Shoulder)
        val hasBothShoulders = hasLeftShoulder && hasRightShoulder
        val hasFaceAndShoulder = (hasNose || hasEyesOrEars) && (hasLeftShoulder || hasRightShoulder)

        return hasBothShoulders || hasFaceAndShoulder
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processCameraFrame(imageProxy: ImageProxy, poseDetector: com.google.mlkit.vision.pose.PoseDetector) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            poseDetector.process(image)
                .addOnSuccessListener { pose ->
                    val frameDetected = isRealUserPresent(pose)

                    // Temporal Smoothing Debounce (Requires 3 stable frames to switch)
                    if (frameDetected) {
                        consecutivePresentFrames++
                        consecutiveAbsentFrames = 0
                        if (consecutivePresentFrames >= 2) {
                            isPersonCurrentlyPresent = true
                            lastSeenTimestamp = System.currentTimeMillis()
                        }
                    } else {
                        consecutiveAbsentFrames++
                        consecutivePresentFrames = 0
                        if (consecutiveAbsentFrames >= 3) {
                            isPersonCurrentlyPresent = false
                        }
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
                    tvLiveStatus.text = "● STANDBY (OUTSIDE ACTIVE HOURS)"
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateAdminStatusUI()
        if (allPermissionsGranted() && cameraProvider == null) {
            startCamera()
        }
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
