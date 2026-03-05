package com.horizoncam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.*
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class CameraHelper(private val context: Context, private val glView: GlCameraView) {

    companion object {
        private const val TAG = "CameraHelper"
        private const val MAX_VIDEO_WIDTH_NORMAL = 1920
        private const val MAX_VIDEO_WIDTH_ACTION = 3840
    }

    // -------------------------------------------------------------------------
    // Aspect ratio
    // -------------------------------------------------------------------------

    enum class AspectRatio(val label: String, val w: Int, val h: Int) {
        RATIO_16_9("16:9", 1080, 1920),
        RATIO_4_3("4:3",   1080, 1440),
        RATIO_1_1("1:1",   1080, 1080)
    }

    var aspectRatio: AspectRatio = AspectRatio.RATIO_16_9
        set(value) {
            field = value
            glView.renderer?.recordSize = Size(value.w, value.h)
        }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private val cameraOpenCloseLock = Semaphore(1)
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var mediaRecorder: MediaRecorder? = null
    private var cameraId: String = ""
    private var videoSize: Size = Size(1280, 720)
    private var sensorOrientation: Int = 90

    private var supportsOis   = false
    private var supportsVStab = false

    private var mainBackCameraId: String? = null
    private var ultraWideCameraId: String? = null
    var hasUltraWide: Boolean = false
        private set

    var isRecording = false
        private set
    @Volatile var isPausing = false
    var useFrontCamera = false

    /** User-selected lens: true = ultra-wide (0.5×), false = main (1×). */
    var useUltraWide = false

    var isActionMode = false
        set(value) {
            field = value
            glView.renderer?.isActionMode = value
        }

    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingStopped: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var currentTempFile: File? = null
    private var currentDisplayName: String? = null

    init {
        detectCameras()
    }

    // -------------------------------------------------------------------------
    // Ultra-wide detection
    // -------------------------------------------------------------------------

    private fun detectCameras() {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            data class CamInfo(val id: String, val focalLength: Float)
            val backCams = mutableListOf<CamInfo>()

            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
                val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val minFocal = focals?.minOrNull() ?: Float.MAX_VALUE
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                if (map.getOutputSizes(MediaRecorder::class.java).isNullOrEmpty()) continue
                backCams.add(CamInfo(id, minFocal))
            }

            backCams.sortBy { it.focalLength }
            if (backCams.size >= 2) {
                ultraWideCameraId = backCams[0].id
                mainBackCameraId = backCams[1].id
                hasUltraWide = true
                Log.d(TAG, "Ultra-wide: ${backCams[0]} | Main: ${backCams[1]}")
            } else if (backCams.size == 1) {
                mainBackCameraId = backCams[0].id
                hasUltraWide = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera detection failed", e)
        }
    }

    // -------------------------------------------------------------------------
    // Background thread
    // -------------------------------------------------------------------------

    fun startBackgroundThread() {
        if (backgroundThread?.isAlive == true) return
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: InterruptedException) {}
        backgroundThread = null; backgroundHandler = null
    }

    // -------------------------------------------------------------------------
    // Open / Close
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun openCamera() {
        val handler = backgroundHandler ?: return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            cameraId = when {
                useFrontCamera -> manager.cameraIdList.first { id ->
                    manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                }
                useUltraWide && ultraWideCameraId != null -> ultraWideCameraId!!
                mainBackCameraId != null -> mainBackCameraId!!
                else -> manager.cameraIdList.first { id ->
                    manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }
            }

            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw RuntimeException("No stream config map")

            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val maxW = if (isActionMode) MAX_VIDEO_WIDTH_ACTION else MAX_VIDEO_WIDTH_NORMAL
            videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java), maxW)

            val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            supportsOis = ois?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
            val vstab = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            supportsVStab = vstab?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true

            glView.renderer?.videoSize = videoSize

            val effectiveRatio = if (sensorOrientation == 90 || sensorOrientation == 270)
                videoSize.height.toFloat() / videoSize.width.toFloat()
            else videoSize.width.toFloat() / videoSize.height.toFloat()
            glView.renderer?.effectiveSrcRatio = effectiveRatio
            glView.renderer?.recordSize = Size(aspectRatio.w, aspectRatio.h)

            Log.d(TAG, "Camera: $cameraId  Capture: $videoSize  SrcRatio: $effectiveRatio  " +
                    "Action: $isActionMode  Aspect: ${aspectRatio.label}  " +
                    "OIS: $supportsOis  VStab: $supportsVStab")

            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS))
                throw RuntimeException("Timeout locking camera")

            manager.openCamera(cameraId, stateCallback, handler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot access camera", e); onError?.invoke("Cannot access camera")
        } catch (e: NoSuchElementException) {
            Log.e(TAG, "Camera not found", e); onError?.invoke("Camera not found")
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted opening camera", e)
        }
    }

    fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            closeSession()
            cameraDevice?.close(); cameraDevice = null
            mediaRecorder?.release(); mediaRecorder = null
        } catch (_: InterruptedException) {
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release(); cameraDevice = camera; startPreview()
        }
        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release(); camera.close(); cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release(); camera.close(); cameraDevice = null
            Log.e(TAG, "Camera error $error"); onError?.invoke("Camera error: $error")
        }
    }

    // -------------------------------------------------------------------------
    // Preview
    // -------------------------------------------------------------------------

    fun startPreview(attempt: Int = 0) {
        if (isPausing) return
        val device  = cameraDevice ?: return
        val handler = backgroundHandler ?: return
        val surface = glView.renderer?.cameraSurface

        // GL thread may not have finished init yet (race condition on cold start).
        // Retry up to 20 times at 50ms intervals (1 second total).
        if (surface == null) {
            if (attempt < 20) {
                handler.postDelayed({ startPreview(attempt + 1) }, 50)
            } else {
                Log.e(TAG, "GL surface not ready after ${attempt} attempts")
            }
            return
        }

        try {
            closeSession()
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                applyHardwareStabilization(this)
            }
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try { session.setRepeatingRequest(builder.build(), null, handler) }
                    catch (e: Exception) { Log.e(TAG, "Preview request failed", e) }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Preview session failed")
                }
            }, handler)
        } catch (e: CameraAccessException) { Log.e(TAG, "startPreview error", e) }
    }

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    fun startRecording() {
        if (isRecording) return
        val device  = cameraDevice ?: return
        val handler = backgroundHandler ?: return
        val surface = glView.renderer?.cameraSurface ?: return

        try {
            closeSession()
            val tempFile = createTempFile()
            currentTempFile = tempFile
            currentDisplayName = tempFile.name
            val recorder = buildMediaRecorder(tempFile)
            mediaRecorder = recorder

            glView.renderer?.attachRecorderSurface(recorder.surface)

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                applyHardwareStabilization(this)
            }
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, handler)
                        recorder.start()
                        isRecording = true
                        onRecordingStarted?.invoke()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start recording", e)
                        cleanupFailedRecording(recorder, tempFile)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Recording session failed")
                    cleanupFailedRecording(recorder, tempFile)
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "startRecording error", e)
            onError?.invoke("Failed to start recording: ${e.message}")
        }
    }

    fun stopRecording(restartPreview: Boolean = true) {
        if (!isRecording) return
        isRecording = false
        closeSession()
        glView.renderer?.detachRecorderSurface()

        var ok = false
        try { mediaRecorder?.stop(); ok = true }
        catch (e: RuntimeException) { Log.w(TAG, "MediaRecorder stop failed: ${e.message}") }

        mediaRecorder?.release(); mediaRecorder = null
        val tempFile = currentTempFile
        val displayName = currentDisplayName ?: "video.mp4"
        currentTempFile = null; currentDisplayName = null

        if (ok && tempFile != null && tempFile.exists() && tempFile.length() > 0) {
            val savedName = saveToGallery(tempFile, displayName)
            onRecordingStopped?.invoke(savedName)
        } else {
            tempFile?.delete()
        }
        if (restartPreview && !isPausing) startPreview()
    }

    private fun closeSession() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
    }

    private fun cleanupFailedRecording(recorder: MediaRecorder, file: File) {
        try { recorder.stop() } catch (_: Exception) {}
        recorder.release(); mediaRecorder = null
        glView.renderer?.detachRecorderSurface()
        file.delete(); currentTempFile = null; currentDisplayName = null; isRecording = false
    }

    // -------------------------------------------------------------------------
    // Save to DCIM/HorizonCam
    // -------------------------------------------------------------------------

    private fun saveToGallery(tempFile: File, displayName: String): String {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/HorizonCam")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        tempFile.inputStream().use { it.copyTo(out) }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val dir = File(dcim, "HorizonCam")
                dir.mkdirs()
                val dest = File(dir, displayName)
                tempFile.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to gallery", e)
        }
        tempFile.delete()
        return displayName
    }

    // -------------------------------------------------------------------------
    // MediaRecorder
    // -------------------------------------------------------------------------

    private fun buildMediaRecorder(outputFile: File): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()

        val profile = when {
            CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_1080P) ->
                CamcorderProfile.get(CamcorderProfile.QUALITY_1080P)
            CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_720P) ->
                CamcorderProfile.get(CamcorderProfile.QUALITY_720P)
            else -> CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH)
        }

        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputFile.absolutePath)
            setVideoSize(aspectRatio.w, aspectRatio.h)
            setVideoFrameRate(profile.videoFrameRate)
            setVideoEncodingBitRate(profile.videoBitRate)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(profile.audioBitRate)
            setAudioSamplingRate(profile.audioSampleRate)
            setOrientationHint(0)
            prepare()
        }
        return recorder
    }

    private fun applyHardwareStabilization(builder: CaptureRequest.Builder) {
        // OIS: physical lens stabilization — reduces jitter BEFORE the sensor
        // captures the frame. No conflict with our GL rotation. Always enable.
        if (supportsOis)
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

        // EIS/VStab: digital frame warping — conflicts with our horizon lock
        // rotation shader, causing left-right oscillation feedback loop.
        // Explicitly disable to prevent interference.
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
    }

    private fun chooseVideoSize(choices: Array<Size>, maxWidth: Int): Size {
        val sorted = choices.sortedByDescending { it.width * it.height }
        for (s in sorted) if (s.width == s.height * 16 / 9 && s.width <= maxWidth) return s
        for (s in sorted) if (s.width == s.height *  4 / 3 && s.width <= maxWidth) return s
        return sorted.firstOrNull { it.width <= maxWidth } ?: choices[0]
    }

    private fun createTempFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(context.cacheDir, "HCAM_$ts.mp4")
    }
}