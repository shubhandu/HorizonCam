package com.horizoncam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.horizoncam.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraHelper
    private lateinit var stabilizer: HorizonStabilizer
    private val mainHandler = Handler(Looper.getMainLooper())

    private val PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val PERMISSION_REQUEST = 1

    private var isHorizonMode = false
    private var hasGyroscope = true
    private var isSwitchingCamera = false
    private var isUltraWide = false

    private var currentAspect = CameraHelper.AspectRatio.RATIO_16_9

    // Recording timer
    private var recordingStartMs = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - recordingStartMs
            val s = (elapsed / 1000).toInt()
            binding.tvTimer.text = String.format("%02d:%02d", s / 60, s % 60)
            mainHandler.postDelayed(this, 500L)
        }
    }

    private val recBlink = object : Runnable {
        private var visible = true
        override fun run() {
            visible = !visible
            binding.recDot.alpha = if (visible) 1f else 0f
            mainHandler.postDelayed(this, 600L)
        }
    }

    private val choreographerCallback: Choreographer.FrameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!camera.isActionMode) return
                binding.overlayView.rollAngle = stabilizer.rollAngle
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) { if (hasPermissions()) openCamera() }
        override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
        override fun surfaceDestroyed(holder: SurfaceHolder) {}
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sensorMgr = getSystemService(SENSOR_SERVICE) as SensorManager
        stabilizer = HorizonStabilizer(sensorMgr)

        val renderer = GlRenderer(stabilizer)
        binding.glCameraView.renderer = renderer
        binding.glCameraView.holder.addCallback(surfaceCallback)

        hasGyroscope = sensorMgr.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        if (!hasGyroscope) {
            binding.tvNoGyro.visibility = View.VISIBLE
            binding.tabHorizon.alpha = 0.3f
            binding.tabHorizon.isEnabled = false
        }

        camera = CameraHelper(this, binding.glCameraView).apply {
            onRecordingStarted = { mainHandler.post { onRecordingUI() } }
            onRecordingStopped = { name -> mainHandler.post { onStoppedUI(name) } }
            onError = { msg -> mainHandler.post { toast(msg) } }
        }

        // Fade overlay starts invisible
        binding.fadeOverlay.alpha = 0f
        binding.fadeOverlay.visibility = View.GONE

        setupModeTabs()
        setupAspectRatio()
        setupLensToggle()
        setupButtons()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        camera.isPausing = false
        camera.startBackgroundThread()
        stabilizer.start()
        if (binding.glCameraView.holder.surface.isValid && hasPermissions()) openCamera()
        if (camera.isActionMode) {
            Choreographer.getInstance().postFrameCallback(choreographerCallback)
        }
    }

    override fun onPause() {
        camera.isPausing = true
        if (camera.isRecording) camera.stopRecording(restartPreview = false)
        camera.closeCamera()
        camera.stopBackgroundThread()
        stabilizer.stop()
        Choreographer.getInstance().removeFrameCallback(choreographerCallback)
        mainHandler.removeCallbacks(recBlink)
        mainHandler.removeCallbacks(timerRunnable)
        super.onPause()
    }

    private fun openCamera() {
        if (!hasPermissions()) { requestPermissionsIfNeeded(); return }
        camera.openCamera()
    }

    // -------------------------------------------------------------------------
    // Mode tabs with fade transition
    // -------------------------------------------------------------------------

    private fun setupModeTabs() {
        updateTabVisuals()

        binding.tabNormal.setOnClickListener {
            if (camera.isRecording || isSwitchingCamera) return@setOnClickListener
            if (isHorizonMode) {
                haptic(it)
                isHorizonMode = false
                switchModeWithFade(actionMode = false)
                updateTabVisuals()
            }
        }

        binding.tabHorizon.setOnClickListener {
            if (camera.isRecording || isSwitchingCamera) return@setOnClickListener
            if (!hasGyroscope) { toast("Gyroscope not available"); return@setOnClickListener }
            if (!isHorizonMode) {
                haptic(it)
                isHorizonMode = true
                switchModeWithFade(actionMode = true)
                updateTabVisuals()
            }
        }
    }

    /**
     * Smooth camera switch: fade to black → swap camera → fade back in.
     */
    private fun switchModeWithFade(actionMode: Boolean) {
        isSwitchingCamera = true
        Choreographer.getInstance().removeFrameCallback(choreographerCallback)

        // Phase 1: fade to black (200ms)
        binding.fadeOverlay.visibility = View.VISIBLE
        binding.fadeOverlay.animate()
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                // Phase 2: swap camera while screen is black
                camera.isPausing = true
                camera.closeCamera()
                camera.isActionMode = actionMode
                binding.overlayView.isActionMode = actionMode
                binding.overlayView.aspectRatio = currentAspect
                camera.isPausing = false
                camera.startBackgroundThread()

                if (actionMode) {
                    stabilizer.reset()
                    Choreographer.getInstance().postFrameCallback(choreographerCallback)
                } else {
                    binding.overlayView.rollAngle = 0f
                }

                openCamera()

                // Phase 3: fade back in after brief delay for camera init (300ms delay + 200ms fade)
                mainHandler.postDelayed({
                    binding.fadeOverlay.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            binding.fadeOverlay.visibility = View.GONE
                            isSwitchingCamera = false
                        }
                        .start()
                }, 300)
            }
            .start()
    }

    private fun updateTabVisuals() {
        binding.tabNormalText.setTextColor(
            if (!isHorizonMode) Color.WHITE else Color.argb(128, 255, 255, 255))
        binding.tabNormalIndicator.visibility =
            if (!isHorizonMode) View.VISIBLE else View.INVISIBLE

        binding.tabHorizonText.setTextColor(
            if (isHorizonMode) Color.WHITE else Color.argb(128, 255, 255, 255))
        binding.tabHorizonIndicator.visibility =
            if (isHorizonMode) View.VISIBLE else View.INVISIBLE
    }

    // -------------------------------------------------------------------------
    // Aspect ratio pills
    // -------------------------------------------------------------------------

    private fun setupAspectRatio() {
        val pills = mapOf(
            binding.pill169 to CameraHelper.AspectRatio.RATIO_16_9,
            binding.pill43  to CameraHelper.AspectRatio.RATIO_4_3,
            binding.pill11  to CameraHelper.AspectRatio.RATIO_1_1
        )

        for ((view, ratio) in pills) {
            view.setOnClickListener {
                if (camera.isRecording) return@setOnClickListener
                if (currentAspect != ratio) {
                    haptic(it)
                    currentAspect = ratio
                    camera.aspectRatio = ratio
                    binding.overlayView.aspectRatio = ratio
                    updateAspectPills()
                }
            }
        }

        updateAspectPills()
    }

    private fun updateAspectPills() {
        val pills = mapOf(
            binding.pill169 to CameraHelper.AspectRatio.RATIO_16_9,
            binding.pill43  to CameraHelper.AspectRatio.RATIO_4_3,
            binding.pill11  to CameraHelper.AspectRatio.RATIO_1_1
        )
        for ((view, ratio) in pills) {
            val selected = ratio == currentAspect
            view.setBackgroundResource(
                if (selected) R.drawable.aspect_pill_selected else R.drawable.aspect_pill_default
            )
            view.setTextColor(if (selected) Color.BLACK else Color.argb(200, 255, 255, 255))
        }
    }

    // -------------------------------------------------------------------------
    // Lens toggle (1× / 0.5×)
    // -------------------------------------------------------------------------

    private fun setupLensToggle() {
        if (!camera.hasUltraWide) {
            binding.lensToggle.visibility = View.GONE
            return
        }
        binding.lensToggle.visibility = View.VISIBLE
        updateLensPills()

        binding.lens1x.setOnClickListener {
            if (camera.isRecording || isSwitchingCamera) return@setOnClickListener
            if (isUltraWide) {
                haptic(it)
                isUltraWide = false
                switchLensWithFade()
            }
        }
        binding.lens05x.setOnClickListener {
            if (camera.isRecording || isSwitchingCamera) return@setOnClickListener
            if (!isUltraWide) {
                haptic(it)
                isUltraWide = true
                switchLensWithFade()
            }
        }
    }

    private fun switchLensWithFade() {
        isSwitchingCamera = true
        binding.fadeOverlay.visibility = View.VISIBLE
        binding.fadeOverlay.animate()
            .alpha(1f)
            .setDuration(150)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                camera.isPausing = true
                camera.closeCamera()
                camera.useUltraWide = isUltraWide
                camera.isPausing = false
                camera.startBackgroundThread()
                updateLensPills()
                openCamera()

                mainHandler.postDelayed({
                    binding.fadeOverlay.animate()
                        .alpha(0f)
                        .setDuration(150)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            binding.fadeOverlay.visibility = View.GONE
                            isSwitchingCamera = false
                        }
                        .start()
                }, 250)
            }
            .start()
    }

    private fun updateLensPills() {
        binding.lens1x.setBackgroundResource(
            if (!isUltraWide) R.drawable.lens_pill_selected else R.drawable.lens_pill_default
        )
        binding.lens1x.setTextColor(
            if (!isUltraWide) Color.BLACK else Color.argb(200, 255, 255, 255)
        )

        binding.lens05x.setBackgroundResource(
            if (isUltraWide) R.drawable.lens_pill_selected else R.drawable.lens_pill_default
        )
        binding.lens05x.setTextColor(
            if (isUltraWide) Color.BLACK else Color.argb(200, 255, 255, 255)
        )
    }

    // -------------------------------------------------------------------------
    // Buttons
    // -------------------------------------------------------------------------

    private fun setupButtons() {
        binding.btnRecord.setOnClickListener {
            haptic(it)
            if (camera.isRecording) {
                camera.stopRecording()
            } else {
                if (!hasPermissions()) { toast("Permissions required"); return@setOnClickListener }
                camera.startRecording()
            }
        }

        binding.btnFlip.setOnClickListener {
            if (camera.isRecording || isSwitchingCamera) {
                toast("Stop recording first"); return@setOnClickListener
            }
            haptic(it)
            camera.isPausing = true
            camera.closeCamera()
            camera.useFrontCamera = !camera.useFrontCamera
            camera.isPausing = false
            if (camera.useFrontCamera && isHorizonMode) {
                isHorizonMode = false
                camera.isActionMode = false
                binding.overlayView.isActionMode = false
                Choreographer.getInstance().removeFrameCallback(choreographerCallback)
                updateTabVisuals()
            }
            // Hide lens toggle for front camera, show for back if ultra-wide exists
            if (camera.useFrontCamera) {
                binding.lensToggle.visibility = View.GONE
            } else if (camera.hasUltraWide) {
                binding.lensToggle.visibility = View.VISIBLE
            }
            camera.startBackgroundThread()
            openCamera()
        }
    }

    private fun onRecordingUI() {
        binding.btnRecord.isRecording = true
        binding.recBar.visibility = View.VISIBLE
        recordingStartMs = SystemClock.elapsedRealtime()
        mainHandler.post(timerRunnable)
        mainHandler.post(recBlink)
        binding.btnFlip.isEnabled = false; binding.btnFlip.alpha = 0.4f
        binding.modeTabs.alpha = 0.4f
        binding.aspectBar.alpha = 0.4f
        binding.lensToggle.alpha = 0.4f
        setControlsEnabled(false)
    }

    private fun onStoppedUI(displayName: String) {
        binding.btnRecord.isRecording = false
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.removeCallbacks(recBlink)
        binding.recBar.visibility = View.GONE
        binding.tvTimer.text = "00:00"
        binding.recDot.alpha = 1f
        binding.btnFlip.isEnabled = true; binding.btnFlip.alpha = 1f
        binding.modeTabs.alpha = 1f
        binding.aspectBar.alpha = 1f
        binding.lensToggle.alpha = 1f
        setControlsEnabled(true)
        toast("Saved to DCIM/HorizonCam")
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.tabNormal.isClickable = enabled
        binding.tabHorizon.isClickable = enabled && hasGyroscope
        binding.pill169.isClickable = enabled
        binding.pill43.isClickable = enabled
        binding.pill11.isClickable = enabled
        binding.lens1x.isClickable = enabled
        binding.lens05x.isClickable = enabled
    }

    // -------------------------------------------------------------------------
    // Haptics
    // -------------------------------------------------------------------------

    private fun haptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private fun hasPermissions() = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsIfNeeded() {
        val missing = PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty())
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (binding.glCameraView.holder.surface.isValid) openCamera()
            } else {
                toast("Camera and microphone permissions are required.")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}