package com.desk.sentry

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.Calendar
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val masterPin = "1234" // आपका सुरक्षा पिन
    private var isAlarmMutedByPin = false
    private var mediaPlayer: MediaPlayer? = null
    private var lastSeenTimestamp = System.currentTimeMillis()
    
    // 3 मिनट (180,000 मिलीसेकंड) का ग्रेस पीरियड
    private val absenceThresholdMs = 180000L

    private lateinit var blackOverlay: View
    private lateinit var previewView: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // स्क्रीन को हमेशा चालू रखना
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val rootLayout = FrameLayout(this)
        previewView = PreviewView(this)
        
        // फेक ब्लैक स्क्रीन (OLED बैटरी सेवर)
        blackOverlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.VISIBLE
            setOnClickListener { showPinDialog() }
        }

        rootLayout.addView(previewView)
        rootLayout.addView(blackOverlay)
        setContentView(rootLayout)

        // डिवाइस एडमिन एक्टिवेशन चेक
        checkAndRequestDeviceAdmin()

        initContinuousAlarm()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    private fun checkAndRequestDeviceAdmin() {
        val compName = ComponentName(this, AdminReceiver::class.java)
        val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!devicePolicyManager.isAdminActive(compName)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "डेस्क गार्ड को अनइंस्टॉल और बंद होने से रोकने के लिए एडमिन अनुमति सक्रिय करें।"
                )
            }
            startActivity(intent)
        }
    }

    private fun initContinuousAlarm() {
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

    private fun isStudyTime(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        // 5:00 AM से 9:00 AM (5..8) और 10:00 AM से 2:00 PM (10..13)
        return (hour in 5..8) || (hour in 10..13)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA // रियर कैमरा

            val options = PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
            val poseDetector = PoseDetection.getClient(options)

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                processFrame(imageProxy, poseDetector)
            }

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)

                // बैकलाइट सुधार: कैमरे का एक्सपोज़र +2 तक बढ़ाना
                val cameraControl = camera.cameraControl
                val cameraInfo = camera.cameraInfo
                val range = cameraInfo.exposureState.exposureCompensationRange
                if (range.upper > 0) {
                    cameraControl.setExposureCompensationIndex(range.upper.coerceAtMost(2))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processFrame(imageProxy: ImageProxy, poseDetector: com.google.mlkit.vision.pose.PoseDetector) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && isStudyTime() && !isAlarmMutedByPin) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            poseDetector.process(image)
                .addOnSuccessListener { pose ->
                    val landmarks = pose.allPoseLandmarks
                    // अगर शरीर (सिर/कंधे) का कोई हिस्सा फ्रेम में है
                    if (landmarks.isNotEmpty()) {
                        lastSeenTimestamp = System.currentTimeMillis()
                        stopAlarm()
                    } else {
                        checkAbsenceAndTriggerAlarm()
                    }
                }
                .addOnFailureListener {
                    checkAbsenceAndTriggerAlarm()
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
            stopAlarm()
        }
    }

    private fun checkAbsenceAndTriggerAlarm() {
        if (System.currentTimeMillis() - lastSeenTimestamp > absenceThresholdMs) {
            runOnUiThread {
                if (mediaPlayer?.isPlaying == false && !isAlarmMutedByPin) {
                    mediaPlayer?.start()
                }
            }
        }
    }

    private fun stopAlarm() {
        runOnUiThread {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                mediaPlayer?.seekTo(0)
            }
        }
    }

    private fun showPinDialog() {
        val input = EditText(this).apply {
            hint = "पिन दर्ज करें"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Desk Sentry Unlock")
            .setMessage("सत्र समाप्त या अलार्म बंद करने के लिए पिन डालें:")
            .setView(input)
            .setPositiveButton("Unmute / Stop") { _, _ ->
                if (input.text.toString() == masterPin) {
                    isAlarmMutedByPin = true
                    stopAlarm()
                    Toast.makeText(this, "सत्र समाप्त हुआ। अलार्म म्यूट है।", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "गलत पिन!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("रद्द करें", null)
            .show()
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
