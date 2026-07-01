package com.example.tabletennistracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SizeF
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.tabletennistracker.databinding.ActivityMainBinding
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private enum class AppCameraMode {
        STABLE_TRACKING,
        HIGH_SPEED_PREVIEW_TEST,
    }

    private data class CameraCapabilities(
        val manualSensorSupported: Boolean = false,
        val manualWhiteBalanceSupported: Boolean = false,
        val isoRange: Range<Int>? = null,
        val exposureTimeRange: Range<Long>? = null,
        val exposureCompensationRange: Range<Int>? = null,
        val minFocusDistance: Float = 0f,
    )

    private data class ProCameraSettings(
        var manualExposureEnabled: Boolean = false,
        var iso: Int = 400,
        var shutterNs: Long = 1_000_000L,
        var manualFocusEnabled: Boolean = false,
        var focusDistanceDiopters: Float = 0f,
        var manualWhiteBalanceEnabled: Boolean = false,
        var warmthKelvin: Int = 5500,
    )

    private data class CapturePlan(
        val cameraId: String,
        val previewSize: Size,
        val fpsRange: Range<Int>,
        val useConstrainedHighSpeed: Boolean,
        val label: String,
    )

    private data class CameraChoice(
        val cameraId: String,
        val characteristics: CameraCharacteristics,
        val requestedPlan: CapturePlan,
    )

    private data class HighSpeedMode(
        val size: Size,
        val range: Range<Int>,
    )

    private data class HighSpeedSummary(
        val lines: List<String> = emptyList(),
        val bestMode: HighSpeedMode? = null,
    )

    private data class DirectCameraSpec(
        val cameraId: String,
        val lensFacing: String,
        val hardwareLevel: String,
        val capabilities: IntArray,
        val fpsRanges: List<Range<Int>>,
        val highSpeedSummary: HighSpeedSummary,
        val stillSizes: List<Size>,
        val videoSizes: List<Size>,
        val yuvSizes: List<Size>,
        val isoRange: Range<Int>?,
        val exposureRange: Range<Long>?,
        val minFocusDistance: Float,
    )

    private data class DiagnosticsScreenModel(
        val summaryLines: List<String> = emptyList(),
        val sections: List<CameraDiagnosticSection> = emptyList(),
        val errors: List<String> = emptyList(),
    )

    private data class CameraDiagnosticSection(
        val title: String,
        val subtitle: String,
        val rows: List<DiagnosticRow>,
    )

    private data class DiagnosticRow(
        val label: String,
        val value: String,
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var diagnosticExecutor: ExecutorService
    private lateinit var frameProcessor: BallTrackerFrameProcessor

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var highSpeedSession: CameraConstrainedHighSpeedCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var activeChoice: CameraChoice? = null
    private var activePlan: CapturePlan? = null
    private var activeCameraCharacteristics: CameraCharacteristics? = null
    private var cameraCapabilities = CameraCapabilities()
    private val proCameraSettings = ProCameraSettings()
    private var selectedCameraMode = AppCameraMode.STABLE_TRACKING

    private var flashAvailable = false
    private var activeArrayRect: Rect? = null
    private var maxDigitalZoom = 1f
    private var exposureStep = 0f
    private var currentZoomRatio = 1f
    private var currentFrameRotationDegrees = 0
    private val previewCoordinateMapper = CameraCoordinateMapper()
    private val overlayCoordinateMapper = CameraCoordinateMapper()
    private var showCandidateDots = false
    private var diagnosticsExpanded = false
    private var controlsExpanded = false
    private var constrainedHighSpeedActive = false
    private var isOpeningCamera = false

    private var measuredFps = 0.0
    private var captureGapDropCount = 0
    private var analysisDropCount = 0
    private var lastSensorTimestampNs = 0L
    private var lastDebugUiUpdateMs = 0L
    private var lastDebugLogMs = 0L
    private var activeFallbackReason: String? = null
    private var modeUnavailableReason: String? = null
    private var restartCameraAfterClose = false
    private var framesReceived = 0L
    private var framesProcessed = 0L
    private var framesDropped = 0L
    private var detectorCalls = 0L
    private var detectionsFound = 0L
    private var detectionsPerSecond = 0.0
    private var lastDetectionSnapshotMs = 0L
    private var lastDetectionSnapshotCount = 0L
    private var lastDetectionReason = "Waiting for frames."
    private var lastTrackingFrameResult: TrackingFrameResult? = null
    private val isProcessingFrame = AtomicBoolean(false)

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            framesReceived += 1
            if (!isProcessingFrame.compareAndSet(false, true)) {
                analysisDropCount += 1
                framesDropped += 1
                lastDetectionReason = "Dropped frame because processing was still busy."
                maybeRefreshDebugOverlay()
                return@OnImageAvailableListener
            }

            val frameData = copyImageToYuvFrame(image)
            if (frameData == null) {
                framesDropped += 1
                lastDetectionReason = "Image copy failed before detection."
                isProcessingFrame.set(false)
                maybeRefreshDebugOverlay()
                return@OnImageAvailableListener
            }

            cameraExecutor.execute {
                try {
                    frameProcessor.process(frameData)
                } catch (error: Exception) {
                    Log.e(TAG, "Frame processing failed.", error)
                    lastDetectionReason = "Frame processing crashed: ${error.message ?: "unknown"}"
                } finally {
                    isProcessingFrame.set(false)
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to enqueue frame processing.", error)
            lastDetectionReason = "Failed to enqueue frame processing: ${error.message ?: "unknown"}"
            isProcessingFrame.set(false)
            framesDropped += 1
            maybeRefreshDebugOverlay()
        } finally {
            image.close()
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            updateMeasuredFps(timestampNs)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            maybeStartCamera()
        } else {
            binding.statusText.text = "Camera permission is required."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(CameraManager::class.java)
        cameraExecutor = Executors.newSingleThreadExecutor()
        diagnosticExecutor = Executors.newSingleThreadExecutor()
        frameProcessor = BallTrackerFrameProcessor(
            onResult = { frameResult ->
                handleTrackingFrameResult(frameResult)
            },
        )
        binding.statusText.visibility = View.GONE
        binding.statusText.text = ""

        binding.previewTextureView.surfaceTextureListener = this
        setupControls()
        setupDiagnostics()
        runCameraDiagnostics()
        renderDebugOverlay()
    }

    override fun onResume() {
        super.onResume()
        startCameraThread()
        if (hasCameraPermission()) {
            maybeStartCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        restartCameraAfterClose = false
        closeCamera()
        stopCameraThread()
        super.onPause()
    }

    override fun onDestroy() {
        diagnosticExecutor.shutdown()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        maybeStartCamera()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        activePlan?.previewSize?.let { configurePreviewTransform(it) }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        restartCameraAfterClose = false
        closeCamera()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startCameraThread() {
        if (cameraThread != null) {
            return
        }
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun stopCameraThread() {
        val thread = cameraThread ?: return
        thread.quitSafely()
        thread.join()
        cameraThread = null
        cameraHandler = null
    }

    private fun maybeStartCamera() {
        if (!hasCameraPermission()) {
            return
        }
        if (!binding.previewTextureView.isAvailable) {
            return
        }
        if (cameraHandler == null || isOpeningCamera || cameraDevice != null) {
            return
        }

        val choice = selectCameraChoice(selectedCameraMode)
        if (choice == null) {
            val reason = modeUnavailableReason ?: when (selectedCameraMode) {
                AppCameraMode.STABLE_TRACKING -> "No back camera exposed a usable 1280x720 YUV tracking stream."
                AppCameraMode.HIGH_SPEED_PREVIEW_TEST -> "No back camera exposed a usable constrained high-speed preview mode."
            }
            setStatusText(reason)
            activeFallbackReason = reason
            renderDebugOverlay()
            return
        }
        openCamera(choice)
    }

    private fun openCamera(choice: CameraChoice) {
        if (!hasCameraPermission()) {
            return
        }
        val handler = cameraHandler ?: return

        isOpeningCamera = true
        activeChoice = choice
        activeCameraCharacteristics = choice.characteristics
        activeFallbackReason = null
        resetRuntimeMetrics()
        configureCameraCharacteristics(choice.characteristics)
        renderDebugOverlay()

        try {
            cameraManager.openCamera(
                choice.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        isOpeningCamera = false
                        cameraDevice = camera
                        runOnMainThread {
                            createSessionForPlan(choice.requestedPlan)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        isOpeningCamera = false
                        Log.w(TAG, "Camera ${choice.cameraId} disconnected.")
                        camera.close()
                        if (cameraDevice === camera) {
                            cameraDevice = null
                        }
                        setStatusText("Camera disconnected.")
                        renderDebugOverlay()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        isOpeningCamera = false
                        Log.e(TAG, "Camera ${choice.cameraId} error: $error")
                        camera.close()
                        if (cameraDevice === camera) {
                            cameraDevice = null
                        }
                        setStatusText("Camera error: $error")
                        activeFallbackReason = "Camera open failed with error code $error."
                        renderDebugOverlay()
                    }

                    override fun onClosed(camera: CameraDevice) {
                        if (cameraDevice === camera) {
                            cameraDevice = null
                        }
                        if (restartCameraAfterClose) {
                            restartCameraAfterClose = false
                            runOnMainThread {
                                maybeStartCamera()
                            }
                        }
                    }
                },
                handler,
            )
        } catch (error: Exception) {
            isOpeningCamera = false
            setStatusText("Camera open failed: ${error.message}")
            activeFallbackReason = "Camera open failed: ${error.message}"
            renderDebugOverlay()
        }
    }

    private fun createSessionForPlan(plan: CapturePlan) {
        if (!isMainThread()) {
            runOnMainThread {
                createSessionForPlan(plan)
            }
            return
        }
        val camera = cameraDevice ?: return
        val surfaceTexture = binding.previewTextureView.surfaceTexture ?: return

        closeCurrentSession()
        resetRuntimeMetrics()

        surfaceTexture.setDefaultBufferSize(plan.previewSize.width, plan.previewSize.height)
        previewSurface = Surface(surfaceTexture)
        if (!plan.useConstrainedHighSpeed) {
            imageReader = ImageReader.newInstance(
                plan.previewSize.width,
                plan.previewSize.height,
                ImageFormat.YUV_420_888,
                3,
            ).also {
                it.setOnImageAvailableListener(imageAvailableListener, cameraHandler)
            }
        }

        activePlan = plan
        constrainedHighSpeedActive = false
        currentFrameRotationDegrees = computeFrameRotationDegrees(activeCameraCharacteristics)
        configurePreviewTransform(plan.previewSize)
        updateAdvancedControlAvailability()
        renderDebugOverlay()

        val surfaces = if (plan.useConstrainedHighSpeed) {
            listOfNotNull(previewSurface)
        } else {
            listOfNotNull(previewSurface, imageReader?.surface)
        }
        val minimumSurfaces = if (plan.useConstrainedHighSpeed) 1 else 2
        if (surfaces.size < minimumSurfaces) {
            setStatusText("Camera surfaces were not created.")
            activeFallbackReason = "Preview or YUV analysis surface creation failed."
            renderDebugOverlay()
            return
        }

        if (plan.useConstrainedHighSpeed) {
            createHighSpeedSession(camera, plan, surfaces)
        } else {
            createStandardSession(camera, surfaces)
        }
    }

    private fun createHighSpeedSession(
        camera: CameraDevice,
        plan: CapturePlan,
        surfaces: List<Surface>,
    ) {
        try {
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    val constrainedSession = session as? CameraConstrainedHighSpeedCaptureSession
                    if (constrainedSession == null) {
                        session.close()
                        handleHighSpeedFailure(
                            "Configured session was not a constrained high-speed session.",
                        )
                        return
                    }

                    captureSession = session
                    highSpeedSession = constrainedSession
                    constrainedHighSpeedActive = true
                    // Detecting a 120fps mode in CameraCharacteristics is only a capability check.
                    // The camera does not actually run at 120fps until we bind a constrained
                    // high-speed session and submit the expanded burst list returned below.
                    submitRepeatingRequest()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    handleHighSpeedFailure(
                        "Constrained high-speed session configuration failed for ${plan.previewSize.width}x${plan.previewSize.height} @ ${formatRange(plan.fpsRange)}.",
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val configuration = SessionConfiguration(
                    SessionConfiguration.SESSION_HIGH_SPEED,
                    surfaces.map(::OutputConfiguration),
                    ContextCompat.getMainExecutor(this),
                    callback,
                )
                camera.createCaptureSession(configuration)
            } else {
                camera.createConstrainedHighSpeedCaptureSession(surfaces, callback, cameraHandler)
            }
        } catch (error: Exception) {
            handleHighSpeedFailure("High-speed session creation threw ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun createStandardSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
    ) {
        try {
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    highSpeedSession = null
                    constrainedHighSpeedActive = false
                    submitRepeatingRequest()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    setStatusText("Camera session configuration failed.")
                    activeFallbackReason = "Standard Camera2 session configuration failed."
                    renderDebugOverlay()
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val configuration = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    surfaces.map(::OutputConfiguration),
                    ContextCompat.getMainExecutor(this),
                    callback,
                )
                camera.createCaptureSession(configuration)
            } else {
                camera.createCaptureSession(surfaces, callback, cameraHandler)
            }
        } catch (error: Exception) {
            setStatusText("Camera session failed: ${error.message}")
            activeFallbackReason = "Standard Camera2 session creation failed: ${error.message}"
            renderDebugOverlay()
        }
    }

    private fun submitRepeatingRequest() {
        val camera = cameraDevice ?: return
        val plan = activePlan ?: return
        val preview = previewSurface ?: return
        val handler = cameraHandler ?: return

        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(preview)
                if (!plan.useConstrainedHighSpeed) {
                    val analysisSurface = imageReader?.surface ?: return
                    addTarget(analysisSurface)
                }
                applyRequestSettings(this, plan)
            }
            val request = requestBuilder.build()

            if (plan.useConstrainedHighSpeed) {
                val constrainedSession = highSpeedSession
                if (constrainedSession == null) {
                    handleHighSpeedFailure("High-speed session was not available when submitting burst.")
                    return
                }
                val burst = constrainedSession.createHighSpeedRequestList(request)
                constrainedSession.setRepeatingBurst(burst, captureCallback, handler)
                setStatusText("High-speed preview test is running. Tracking is disabled in this mode.")
                Log.i(
                    TAG,
                    "Using constrained high-speed preview only: ${plan.previewSize.width}x${plan.previewSize.height} @ ${formatRange(plan.fpsRange)} FPS",
                )
            } else {
                captureSession?.setRepeatingRequest(request, captureCallback, handler)
                setStatusText(getString(R.string.status_no_ball))
                Log.i(
                    TAG,
                    "Using stable tracking mode with ImageReader: ${plan.previewSize.width}x${plan.previewSize.height} @ ${formatRange(plan.fpsRange)} FPS",
                )
            }
            renderDebugOverlay()
        } catch (error: Exception) {
            if (plan.useConstrainedHighSpeed) {
                handleHighSpeedFailure("High-speed repeating burst failed: ${error.message}")
            } else {
                setStatusText("Camera request failed: ${error.message}")
                activeFallbackReason = "Standard repeating request failed: ${error.message}"
                renderDebugOverlay()
            }
        }
    }

    private fun applyRequestSettings(
        builder: CaptureRequest.Builder,
        plan: CapturePlan,
    ) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, plan.fpsRange)

        if (plan.useConstrainedHighSpeed) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        } else {
            val isoRange = cameraCapabilities.isoRange
            val exposureRange = cameraCapabilities.exposureTimeRange

            if (
                proCameraSettings.manualExposureEnabled &&
                cameraCapabilities.manualSensorSupported &&
                isoRange != null &&
                exposureRange != null
            ) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    proCameraSettings.iso.coerceIn(isoRange.lower, isoRange.upper),
                )
                builder.set(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    proCameraSettings.shutterNs.coerceIn(exposureRange.lower, exposureRange.upper),
                )
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                cameraCapabilities.exposureCompensationRange?.let {
                    builder.set(
                        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        binding.exposureSeekBar.progress.coerceIn(it.lower, it.upper),
                    )
                }
            }

            if (proCameraSettings.manualFocusEnabled && cameraCapabilities.minFocusDistance > 0f) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    proCameraSettings.focusDistanceDiopters.coerceIn(0f, cameraCapabilities.minFocusDistance),
                )
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }

            if (proCameraSettings.manualWhiteBalanceEnabled && cameraCapabilities.manualWhiteBalanceSupported) {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
                builder.set(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    warmthToGains(proCameraSettings.warmthKelvin),
                )
            } else {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }

            builder.set(
                CaptureRequest.FLASH_MODE,
                if (binding.torchSwitch.isChecked && flashAvailable) {
                    CaptureRequest.FLASH_MODE_TORCH
                } else {
                    CaptureRequest.FLASH_MODE_OFF
                },
            )
        }

        if (currentZoomRatio > 1.01f) {
            activeArrayRect?.let { sensorRect ->
                builder.set(
                    CaptureRequest.SCALER_CROP_REGION,
                    zoomRatioToCropRect(sensorRect, currentZoomRatio, maxDigitalZoom),
                )
            }
        }
    }

    private fun configureCameraCharacteristics(characteristics: CameraCharacteristics) {
        if (!isMainThread()) {
            runOnMainThread {
                configureCameraCharacteristics(characteristics)
            }
            return
        }
        val capabilities = characteristics.get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
        ) ?: intArrayOf()
        val availableAwbModes = characteristics.get(
            CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,
        ) ?: intArrayOf()

        cameraCapabilities = CameraCapabilities(
            manualSensorSupported =
                capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            manualWhiteBalanceSupported =
                capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) &&
                    availableAwbModes.contains(CaptureRequest.CONTROL_AWB_MODE_OFF),
            isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            exposureCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
            minFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
        )

        flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        activeArrayRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        maxDigitalZoom =
            (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
                .coerceAtLeast(1f)
        currentZoomRatio = 1f
        currentFrameRotationDegrees = computeFrameRotationDegrees(characteristics)

        configureExposureSlider(characteristics)
        configureZoomSlider()

        cameraCapabilities.isoRange?.let {
            binding.isoSeekBar.min = it.lower
            binding.isoSeekBar.max = it.upper
            proCameraSettings.iso = proCameraSettings.iso.coerceIn(it.lower, it.upper)
            binding.isoSeekBar.progress = proCameraSettings.iso
        }

        cameraCapabilities.exposureTimeRange?.let {
            proCameraSettings.shutterNs = proCameraSettings.shutterNs.coerceIn(it.lower, it.upper)
            binding.shutterSeekBar.progress = shutterNsToProgress(proCameraSettings.shutterNs)
        }

        if (cameraCapabilities.minFocusDistance <= 0f) {
            proCameraSettings.focusDistanceDiopters = 0f
            binding.focusSeekBar.progress = 0
        }

        updateAdvancedLabels()
        updateAdvancedControlAvailability()
    }

    private fun configureExposureSlider(characteristics: CameraCharacteristics) {
        if (!isMainThread()) {
            runOnMainThread {
                configureExposureSlider(characteristics)
            }
            return
        }
        val range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)

        if (range == null || step == null) {
            exposureStep = 0f
            binding.exposureSeekBar.min = 0
            binding.exposureSeekBar.max = 0
            binding.exposureSeekBar.progress = 0
            binding.exposureSeekBar.isEnabled = false
            binding.exposureLabel.text = "Exposure: Not supported on this camera"
            return
        }

        exposureStep = if (step.denominator != 0) {
            step.numerator.toFloat() / step.denominator.toFloat()
        } else {
            0f
        }
        binding.exposureSeekBar.min = range.lower
        binding.exposureSeekBar.max = range.upper
        binding.exposureSeekBar.progress = binding.exposureSeekBar.progress.coerceIn(range.lower, range.upper)
        updateExposureLabel(binding.exposureSeekBar.progress)
    }

    private fun configureZoomSlider() {
        if (!isMainThread()) {
            runOnMainThread {
                configureZoomSlider()
            }
            return
        }
        val maxZoomForUi = max(10, (min(maxDigitalZoom, 8f) * 10f).roundToInt())
        binding.zoomSeekBar.min = 10
        binding.zoomSeekBar.max = maxZoomForUi
        binding.zoomSeekBar.progress = currentZoomRatio.times(10f).roundToInt().coerceIn(10, maxZoomForUi)
        binding.zoomLabel.text = "Zoom: ${"%.1f".format(Locale.US, currentZoomRatio)}x"
    }

    private fun refreshRepeatingRequest() {
        if (activePlan == null) {
            return
        }
        cameraHandler?.post {
            submitRepeatingRequest()
        }
    }

    private fun requestCameraRestart() {
        if (!hasCameraPermission() || !binding.previewTextureView.isAvailable) {
            return
        }
        restartCameraAfterClose = true
        if (cameraDevice != null || isOpeningCamera) {
            closeCamera()
        } else {
            restartCameraAfterClose = false
            maybeStartCamera()
        }
    }

    private fun closeCamera() {
        if (!isMainThread()) {
            runOnMainThread {
                closeCamera()
            }
            return
        }
        isOpeningCamera = false
        closeCurrentSession()
        cameraDevice?.close()
        cameraDevice = null
        activePlan = null
        activeChoice = null
        frameProcessor.reset()
        lastTrackingFrameResult = null
        binding.overlayView.updateFrameResult(null)
        constrainedHighSpeedActive = false
        isProcessingFrame.set(false)
        renderDebugOverlay()
    }

    private fun closeCurrentSession() {
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            captureSession?.abortCaptures()
        } catch (_: Exception) {
        }
        highSpeedSession = null
        captureSession?.close()
        captureSession = null
        imageReader?.close()
        imageReader = null
        previewSurface?.release()
        previewSurface = null
        lastSensorTimestampNs = 0L
        isProcessingFrame.set(false)
    }

    private fun handleHighSpeedFailure(reason: String) {
        if (!isMainThread()) {
            runOnMainThread {
                handleHighSpeedFailure(reason)
            }
            return
        }
        Log.w(TAG, reason)
        activeFallbackReason = reason
        val choice = activeChoice ?: return
        if (!choice.requestedPlan.useConstrainedHighSpeed) {
            setStatusText("Camera request failed.")
            renderDebugOverlay()
            return
        }

        setStatusText("High-speed preview test failed.")
        Log.i(TAG, "High-speed preview test mode failed. Stable tracking mode remains isolated and must be selected manually.")
        closeCamera()
    }

    private fun resetRuntimeMetrics() {
        measuredFps = 0.0
        captureGapDropCount = 0
        analysisDropCount = 0
        framesReceived = 0L
        framesProcessed = 0L
        framesDropped = 0L
        detectorCalls = 0L
        detectionsFound = 0L
        detectionsPerSecond = 0.0
        lastDetectionSnapshotMs = SystemClock.elapsedRealtime()
        lastDetectionSnapshotCount = 0L
        lastDetectionReason = if (selectedCameraMode == AppCameraMode.STABLE_TRACKING) {
            "Waiting for ImageReader frames."
        } else {
            "Tracking disabled in high-speed preview test mode."
        }
        lastTrackingFrameResult = null
        lastSensorTimestampNs = 0L
        lastDebugUiUpdateMs = 0L
        lastDebugLogMs = 0L
    }

    private fun copyImageToYuvFrame(image: Image): YuvFrameData? {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3) {
            Log.w(TAG, "Ignoring non-YUV frame: format=${image.format} planes=${image.planes.size}")
            return null
        }

        return YuvFrameData(
            yPlane = copyPlaneBytes(image.planes[0].buffer),
            uPlane = copyPlaneBytes(image.planes[1].buffer),
            vPlane = copyPlaneBytes(image.planes[2].buffer),
            width = image.width,
            height = image.height,
            imageFormat = image.format,
            timestampNs = image.timestamp,
            rowStrideY = image.planes[0].rowStride,
            rowStrideUV = image.planes[1].rowStride,
            pixelStrideUV = image.planes[1].pixelStride,
            rotationDegrees = currentFrameRotationDegrees,
        )
    }

    private fun copyPlaneBytes(buffer: ByteBuffer): ByteArray {
        val duplicate = buffer.duplicate()
        duplicate.rewind()
        return ByteArray(duplicate.remaining()).also(duplicate::get)
    }

    private fun handleTrackingFrameResult(frameResult: TrackingFrameResult) {
        framesProcessed += 1
        lastTrackingFrameResult = frameResult
        val sensorOrientation = currentSensorOrientation()
        val displayRotationDegrees = currentDisplayRotationDegrees()
        val previewWidth = binding.previewTextureView.width
        val previewHeight = binding.previewTextureView.height
        val overlayWidth = binding.overlayView.width
        val overlayHeight = binding.overlayView.height

        if (frameResult.debugInfo.detectorCalled) {
            detectorCalls += 1
        }
        if (frameResult.debugInfo.detectionFound) {
            detectionsFound += 1
        }

        lastDetectionReason = frameResult.debugInfo.detectionReason
        updateDetectionRate()
        maybeRefreshDebugOverlay()

        val trackerResult = frameResult.trackerResult
        Log.d(
            TAG,
            buildString {
                append("tracking mode=")
                append(cameraModeLabel(selectedCameraMode))
                append(" framesReceived=")
                append(framesReceived)
                append(" framesProcessed=")
                append(framesProcessed)
                append(" framesDropped=")
                append(framesDropped)
                append(" detectorCalls=")
                append(detectorCalls)
                append(" detectionsFound=")
                append(detectionsFound)
                append(" found=")
                append(frameResult.debugInfo.detectionFound)
                append(" reason=")
                append(frameResult.debugInfo.detectionReason)
                append(" frame=")
                append(frameResult.debugInfo.frameWidth)
                append("x")
                append(frameResult.debugInfo.frameHeight)
                append(" format=")
                append(frameResult.debugInfo.imageFormat)
                append(" sensor=")
                append(sensorOrientation)
                append(" display=")
                append(displayRotationDegrees)
                append(" preview=")
                append(previewWidth)
                append("x")
                append(previewHeight)
                append(" overlay=")
                append(overlayWidth)
                append("x")
                append(overlayHeight)
                append(" ts=")
                append(frameResult.debugInfo.timestampNs)
                append(" strideY=")
                append(frameResult.debugInfo.rowStrideY)
                append(" strideUV=")
                append(frameResult.debugInfo.rowStrideUV)
                append(" pixelStrideUV=")
                append(frameResult.debugInfo.pixelStrideUV)
                append(" candidates=")
                append(frameResult.debugInfo.candidateCount)
                append("/")
                append(frameResult.debugInfo.totalComponents)
                append(" reject[small=")
                append(frameResult.debugInfo.rejectedTooSmall)
                append(", large=")
                append(frameResult.debugInfo.rejectedTooLarge)
                append(", edge=")
                append(frameResult.debugInfo.rejectedEdge)
                append(", aspect=")
                append(frameResult.debugInfo.rejectedAspect)
                append(", fill=")
                append(frameResult.debugInfo.rejectedFill)
                append(", luma=")
                append(frameResult.debugInfo.rejectedLuma)
                append(", contrast=")
                append(frameResult.debugInfo.rejectedContrast)
                append(", circularity=")
                append(frameResult.debugInfo.rejectedCircularity)
                append(", color=")
                append(frameResult.debugInfo.rejectedColor)
                append(", confidence=")
                append(frameResult.debugInfo.rejectedConfidence)
                append("] roi=disabled motion=disabled")
                if (trackerResult != null) {
                    append(" score=")
                    append("%.2f".format(Locale.US, trackerResult.confidence))
                    append(" ballX=")
                    append("%.1f".format(Locale.US, trackerResult.centerX))
                    append(" ballY=")
                    append("%.1f".format(Locale.US, trackerResult.centerY))
                    append(" radius=")
                    append("%.1f".format(Locale.US, trackerResult.radius))
                }
            },
        )

        runOnMainThread {
            binding.overlayView.updateFrameResult(frameResult)
            binding.statusText.text = if (trackerResult == null) {
                getString(R.string.status_no_ball)
            } else {
                "${getString(R.string.status_ball_found)} ${(trackerResult.confidence * 100f).roundToInt()}%"
            }
            renderDebugOverlay()
        }
    }

    private fun updateDetectionRate() {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - lastDetectionSnapshotMs
        if (elapsedMs >= 1_000L) {
            detectionsPerSecond =
                (detectionsFound - lastDetectionSnapshotCount) * 1_000.0 / elapsedMs.toDouble()
            lastDetectionSnapshotMs = now
            lastDetectionSnapshotCount = detectionsFound
        }
    }

    private fun updateMeasuredFps(sensorTimestampNs: Long) {
        val expectedFps = activePlan?.fpsRange?.upper?.toDouble() ?: return
        if (lastSensorTimestampNs > 0L) {
            val deltaNs = sensorTimestampNs - lastSensorTimestampNs
            if (deltaNs > 0L) {
                val instantFps = 1_000_000_000.0 / deltaNs.toDouble()
                measuredFps = if (measuredFps == 0.0) {
                    instantFps
                } else {
                    measuredFps * 0.85 + instantFps * 0.15
                }

                val expectedIntervalNs = 1_000_000_000.0 / expectedFps
                if (deltaNs > expectedIntervalNs * 1.5) {
                    captureGapDropCount += max(
                        0,
                        (deltaNs / expectedIntervalNs).roundToInt() - 1,
                    )
                }
            }
        }
        lastSensorTimestampNs = sensorTimestampNs
        maybeRefreshDebugOverlay()
    }

    private fun maybeRefreshDebugOverlay() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDebugUiUpdateMs >= DEBUG_UI_INTERVAL_MS) {
            lastDebugUiUpdateMs = now
            runOnUiThread {
                renderDebugOverlay()
            }
        }
        if (now - lastDebugLogMs >= DEBUG_LOG_INTERVAL_MS) {
            lastDebugLogMs = now
            val trackingSummary = lastTrackingFrameResult?.debugInfo?.let { debugInfo ->
                " tracking=[received=$framesReceived processed=$framesProcessed dropped=$framesDropped detector=$detectorCalls found=$detectionsFound dps=${"%.1f".format(Locale.US, detectionsPerSecond)} reason=${debugInfo.detectionReason}]"
            } ?: if (selectedCameraMode == AppCameraMode.STABLE_TRACKING) {
                " tracking=[received=$framesReceived processed=$framesProcessed dropped=$framesDropped detector=$detectorCalls found=$detectionsFound dps=${"%.1f".format(Locale.US, detectionsPerSecond)} reason=$lastDetectionReason]"
            } else {
                " tracking=[disabled in high-speed preview test mode]"
            }
            Log.d(
                TAG,
                "cam=${activeChoice?.cameraId} plan=${activePlan?.label} size=${activePlan?.previewSize?.width}x${activePlan?.previewSize?.height} fps=${formatRange(activePlan?.fpsRange)} hs=$constrainedHighSpeedActive measured=${"%.1f".format(Locale.US, measuredFps)} drops=${captureGapDropCount + analysisDropCount}$trackingSummary fallback=${activeFallbackReason ?: "none"}",
            )
        }
    }

    private fun renderDebugOverlay() {
        if (!isMainThread()) {
            runOnMainThread {
                renderDebugOverlay()
            }
            return
        }
        val plan = activePlan ?: activeChoice?.requestedPlan
        val fpsRange = formatRange(plan?.fpsRange)
        val measuredText = if (measuredFps > 0.0) {
            "${"%.1f".format(Locale.US, measuredFps)} fps"
        } else {
            "waiting..."
        }

        binding.debugText.text = buildString {
            append("FPS: ")
            append(fpsRange)
            append('\n')
            append("Measured: ")
            append(measuredText)
        }
    }

    private fun setupDiagnostics() {
        binding.refreshDiagnosticsButton.setOnClickListener {
            runCameraDiagnostics()
        }
        binding.toggleDiagnosticsButton.setOnClickListener {
            diagnosticsExpanded = !diagnosticsExpanded
            applyDiagnosticsExpansionState()
        }
        applyDiagnosticsExpansionState()
    }

    private fun setupControls() {
        binding.toggleControlsButton.setOnClickListener {
            controlsExpanded = !controlsExpanded
            applyControlsExpansionState()
        }

        binding.cameraModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.modeHighSpeedRadio -> AppCameraMode.HIGH_SPEED_PREVIEW_TEST
                else -> AppCameraMode.STABLE_TRACKING
            }
            switchCameraMode(newMode)
        }

        binding.ballProfileGroup.setOnCheckedChangeListener { _, checkedId ->
            NativeBallTracker.selectedProfile = when (checkedId) {
                R.id.profileOrangeRadio -> NativeBallTracker.BallProfile.ORANGE
                R.id.profileWhiteRadio -> NativeBallTracker.BallProfile.WHITE
                else -> NativeBallTracker.BallProfile.AUTO
            }
        }

        binding.showCandidatesSwitch.setOnCheckedChangeListener { _, isChecked ->
            showCandidateDots = isChecked
            binding.overlayView.setShowCandidateDots(isChecked)
        }
        binding.overlayView.setShowCandidateDots(showCandidateDots)

        binding.zoomSeekBar.min = 10
        binding.zoomSeekBar.max = 40
        binding.zoomSeekBar.progress = 10
        binding.zoomSeekBar.setOnSeekBarChangeListener(simpleSeekBarListener { value ->
            currentZoomRatio = (value / 10f).coerceAtLeast(1f)
            binding.zoomLabel.text = "Zoom: ${"%.1f".format(Locale.US, currentZoomRatio)}x"
            refreshRepeatingRequest()
        })

        binding.torchSwitch.setOnCheckedChangeListener { _, _ ->
            refreshRepeatingRequest()
        }

        binding.manualExposureSwitch.setOnCheckedChangeListener { _, enabled ->
            proCameraSettings.manualExposureEnabled = enabled
            updateAdvancedLabels()
            updateAdvancedControlAvailability()
            refreshRepeatingRequest()
        }

        binding.manualFocusSwitch.setOnCheckedChangeListener { _, enabled ->
            proCameraSettings.manualFocusEnabled = enabled
            updateAdvancedLabels()
            updateAdvancedControlAvailability()
            refreshRepeatingRequest()
        }

        binding.manualWhiteBalanceSwitch.setOnCheckedChangeListener { _, enabled ->
            proCameraSettings.manualWhiteBalanceEnabled = enabled
            updateAdvancedLabels()
            updateAdvancedControlAvailability()
            refreshRepeatingRequest()
        }

        binding.exposureSeekBar.setOnSeekBarChangeListener(simpleSeekBarListener { value ->
            updateExposureLabel(value)
            refreshRepeatingRequest()
        })

        binding.isoSeekBar.min = 100
        binding.isoSeekBar.max = 3200
        binding.isoSeekBar.progress = 400
        binding.isoSeekBar.setOnSeekBarChangeListener(simpleSeekBarListener { value ->
            proCameraSettings.iso = value
            updateIsoLabel()
            refreshRepeatingRequest()
        })

        binding.shutterSeekBar.min = 0
        binding.shutterSeekBar.max = 1000
        binding.shutterSeekBar.progress = 320
        binding.shutterSeekBar.setOnSeekBarChangeListener(simpleSeekBarListener { value ->
            proCameraSettings.shutterNs = progressToShutterNs(value)
            updateShutterLabel()
            refreshRepeatingRequest()
        })

        binding.focusSeekBar.min = 0
        binding.focusSeekBar.max = 1000
        binding.focusSeekBar.progress = 0
        binding.focusSeekBar.setOnSeekBarChangeListener(simpleSeekBarListener { value ->
            proCameraSettings.focusDistanceDiopters =
                (cameraCapabilities.minFocusDistance * (value / 1000f)).coerceAtLeast(0f)
            updateFocusLabel()
            refreshRepeatingRequest()
        })

        binding.warmthSeekBar.min = 2500
        binding.warmthSeekBar.max = 8500
        binding.warmthSeekBar.progress = proCameraSettings.warmthKelvin
        binding.warmthSeekBar.setOnSeekBarChangeListener(simpleSeekBarListener { value ->
            proCameraSettings.warmthKelvin = value
            updateWarmthLabel()
            refreshRepeatingRequest()
        })

        binding.resetProButton.setOnClickListener {
            resetProControls()
        }

        updateAdvancedLabels()
        updateAdvancedControlAvailability()
        applyControlsExpansionState()
    }

    private fun switchCameraMode(newMode: AppCameraMode) {
        if (selectedCameraMode == newMode) {
            return
        }

        selectedCameraMode = newMode
        activeFallbackReason = null
        modeUnavailableReason = null
        lastDetectionReason = when (newMode) {
            AppCameraMode.STABLE_TRACKING -> "Switching to stable tracking mode."
            AppCameraMode.HIGH_SPEED_PREVIEW_TEST -> "Tracking disabled in high-speed preview test mode."
        }
        lastTrackingFrameResult = null
        binding.overlayView.updateFrameResult(null)
        setStatusText(
            when (newMode) {
                AppCameraMode.STABLE_TRACKING -> "Switching to stable tracking mode..."
                AppCameraMode.HIGH_SPEED_PREVIEW_TEST -> "Switching to high-speed preview test mode..."
            },
        )
        updateAdvancedLabels()
        updateAdvancedControlAvailability()
        renderDebugOverlay()
        requestCameraRestart()
    }

    private fun updateAdvancedControlAvailability() {
        if (!isMainThread()) {
            runOnMainThread {
                updateAdvancedControlAvailability()
            }
            return
        }
        val highSpeedLocked = isHighSpeedModeSelectedOrActive()
        val manualExposureAvailable =
            cameraCapabilities.manualSensorSupported &&
                cameraCapabilities.isoRange != null &&
                cameraCapabilities.exposureTimeRange != null &&
                !highSpeedLocked
        val manualFocusAvailable = cameraCapabilities.minFocusDistance > 0f && !highSpeedLocked
        val manualWhiteBalanceAvailable = cameraCapabilities.manualWhiteBalanceSupported && !highSpeedLocked

        binding.torchSwitch.isEnabled = flashAvailable && !highSpeedLocked
        binding.ballProfileGroup.isEnabled = !highSpeedLocked
        binding.showCandidatesSwitch.isEnabled = !highSpeedLocked
        binding.manualExposureSwitch.isEnabled = manualExposureAvailable
        binding.manualFocusSwitch.isEnabled = manualFocusAvailable
        binding.manualWhiteBalanceSwitch.isEnabled = manualWhiteBalanceAvailable

        if (!manualExposureAvailable && binding.manualExposureSwitch.isChecked) {
            binding.manualExposureSwitch.isChecked = false
        }
        if (!manualFocusAvailable && binding.manualFocusSwitch.isChecked) {
            binding.manualFocusSwitch.isChecked = false
        }
        if (!manualWhiteBalanceAvailable && binding.manualWhiteBalanceSwitch.isChecked) {
            binding.manualWhiteBalanceSwitch.isChecked = false
        }

        binding.isoSeekBar.isEnabled = manualExposureAvailable && binding.manualExposureSwitch.isChecked
        binding.shutterSeekBar.isEnabled = manualExposureAvailable && binding.manualExposureSwitch.isChecked
        binding.exposureSeekBar.isEnabled =
            cameraCapabilities.exposureCompensationRange != null && !binding.manualExposureSwitch.isChecked
        binding.focusSeekBar.isEnabled = manualFocusAvailable && binding.manualFocusSwitch.isChecked
        binding.warmthSeekBar.isEnabled = manualWhiteBalanceAvailable && binding.manualWhiteBalanceSwitch.isChecked
    }

    private fun resetProControls() {
        binding.manualExposureSwitch.isChecked = false
        binding.manualFocusSwitch.isChecked = false
        binding.manualWhiteBalanceSwitch.isChecked = false
        binding.torchSwitch.isChecked = false

        cameraCapabilities.isoRange?.let { range ->
            proCameraSettings.iso = 400.coerceIn(range.lower, range.upper)
            binding.isoSeekBar.progress = proCameraSettings.iso
        }
        cameraCapabilities.exposureTimeRange?.let { range ->
            proCameraSettings.shutterNs = 1_000_000L.coerceIn(range.lower, range.upper)
            binding.shutterSeekBar.progress = shutterNsToProgress(proCameraSettings.shutterNs)
        }
        proCameraSettings.focusDistanceDiopters = 0f
        binding.focusSeekBar.progress = 0
        proCameraSettings.warmthKelvin = 5500
        binding.warmthSeekBar.progress = proCameraSettings.warmthKelvin
        binding.exposureSeekBar.progress = binding.exposureSeekBar.progress.coerceAtLeast(binding.exposureSeekBar.min)

        updateAdvancedLabels()
        updateAdvancedControlAvailability()
        refreshRepeatingRequest()
    }

    private fun updateAdvancedLabels() {
        updateExposureLabel(binding.exposureSeekBar.progress)
        updateIsoLabel()
        updateShutterLabel()
        updateFocusLabel()
        updateWarmthLabel()
    }

    private fun updateExposureLabel(index: Int) {
        if (cameraCapabilities.exposureCompensationRange == null) {
            binding.exposureLabel.text = "Exposure: Not supported on this camera"
            return
        }
        val evValue = index * exposureStep
        binding.exposureLabel.text = "Exposure: ${"%.1f".format(Locale.US, evValue)} EV"
    }

    private fun updateIsoLabel() {
        binding.isoLabel.text = if (
            !cameraCapabilities.manualSensorSupported ||
            cameraCapabilities.isoRange == null
        ) {
            "ISO: Not supported on this camera"
        } else if (isHighSpeedModeSelectedOrActive()) {
            "ISO: Locked to auto in constrained high-speed mode"
        } else if (!binding.manualExposureSwitch.isChecked) {
            "ISO: Auto"
        } else {
            "ISO: ${proCameraSettings.iso}"
        }
    }

    private fun updateShutterLabel() {
        binding.shutterLabel.text = if (
            !cameraCapabilities.manualSensorSupported ||
            cameraCapabilities.exposureTimeRange == null
        ) {
            "Shutter: Not supported on this camera"
        } else if (isHighSpeedModeSelectedOrActive()) {
            "Shutter: Locked to auto in constrained high-speed mode"
        } else if (!binding.manualExposureSwitch.isChecked) {
            "Shutter: Auto"
        } else {
            "Shutter: ${formatShutterSpeed(proCameraSettings.shutterNs)}"
        }
    }

    private fun updateFocusLabel() {
        binding.focusLabel.text = if (cameraCapabilities.minFocusDistance <= 0f) {
            "Focus: Fixed focus lens"
        } else if (isHighSpeedModeSelectedOrActive()) {
            "Focus: Continuous video in constrained high-speed mode"
        } else if (!binding.manualFocusSwitch.isChecked || proCameraSettings.focusDistanceDiopters <= 0.001f) {
            "Focus: Auto / Infinity"
        } else {
            val distanceMeters = 1f / proCameraSettings.focusDistanceDiopters
            "Focus: ${"%.2f".format(Locale.US, distanceMeters)} m"
        }
    }

    private fun updateWarmthLabel() {
        binding.warmthLabel.text = if (!cameraCapabilities.manualWhiteBalanceSupported) {
            "Warmth: Not supported on this camera"
        } else if (isHighSpeedModeSelectedOrActive()) {
            "Warmth: Locked to auto in constrained high-speed mode"
        } else if (!binding.manualWhiteBalanceSwitch.isChecked) {
            "Warmth: Auto"
        } else {
            "Warmth: ${proCameraSettings.warmthKelvin}K"
        }
    }

    private fun simpleSeekBarListener(onChange: (Int) -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onChange(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
    }

    private fun progressToShutterNs(progress: Int): Long {
        val range = cameraCapabilities.exposureTimeRange ?: return 1_000_000L
        val minNs = range.lower.toDouble().coerceAtLeast(1.0)
        val maxNs = range.upper.toDouble().coerceAtLeast(minNs + 1.0)
        val ratio = progress / binding.shutterSeekBar.max.toDouble()
        val logSpan = ln(maxNs) - ln(minNs)
        if (logSpan <= 0.0) {
            return range.lower
        }
        val logValue = ln(minNs) + logSpan * ratio
        return kotlin.math.exp(logValue).roundToLong().coerceIn(range.lower, range.upper)
    }

    private fun shutterNsToProgress(shutterNs: Long): Int {
        val range = cameraCapabilities.exposureTimeRange ?: return 0
        val minNs = range.lower.toDouble().coerceAtLeast(1.0)
        val maxNs = range.upper.toDouble().coerceAtLeast(minNs + 1.0)
        val clampedNs = shutterNs.toDouble().coerceIn(minNs, maxNs)
        val logSpan = ln(maxNs) - ln(minNs)
        if (logSpan <= 0.0) {
            return 0
        }
        val ratio = (ln(clampedNs) - ln(minNs)) / logSpan
        return (ratio * binding.shutterSeekBar.max).roundToInt().coerceIn(0, binding.shutterSeekBar.max)
    }

    private fun formatShutterSpeed(shutterNs: Long): String {
        return if (shutterNs >= 1_000_000_000L) {
            "${"%.2f".format(Locale.US, shutterNs / 1_000_000_000f)} s"
        } else {
            val denominator = (1_000_000_000.0 / shutterNs.toDouble()).roundToInt().coerceAtLeast(1)
            "1/$denominator s"
        }
    }

    private fun warmthToGains(kelvin: Int): RggbChannelVector {
        val normalized = ((kelvin - 2500f) / (8500f - 2500f)).coerceIn(0f, 1f)
        val redGain = 2.3f - normalized * 0.9f
        val blueGain = 1.15f + normalized * 1.35f
        val greenGain = 1.55f
        return RggbChannelVector(redGain, greenGain, greenGain, blueGain)
    }

    private fun zoomRatioToCropRect(
        activeArray: Rect,
        requestedZoomRatio: Float,
        maxZoom: Float,
    ): Rect {
        val zoom = requestedZoomRatio.coerceIn(1f, maxZoom)
        val cropWidth = activeArray.width() / zoom
        val cropHeight = activeArray.height() / zoom
        val left = activeArray.centerX() - cropWidth / 2f
        val top = activeArray.centerY() - cropHeight / 2f
        return Rect(
            left.roundToInt(),
            top.roundToInt(),
            (left + cropWidth).roundToInt(),
            (top + cropHeight).roundToInt(),
        )
    }

    private fun computeFrameRotationDegrees(characteristics: CameraCharacteristics?): Int {
        // Keep the tracking pipeline at the camera's default angle for now.
        // We still log sensor/display orientation separately, but we avoid
        // forcing another preview rotation here because that extra transform
        // is what is currently rotating the view and breaking ball mapping.
        return 0
    }

    private fun currentSensorOrientation(characteristics: CameraCharacteristics? = activeCameraCharacteristics): Int {
        return characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    }

    private fun currentDisplayRotationDegrees(): Int {
        return when (binding.previewTextureView.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun configurePreviewTransform(previewSize: Size) {
        if (!isMainThread()) {
            runOnMainThread {
                configurePreviewTransform(previewSize)
            }
            return
        }
        val textureView = binding.previewTextureView
        if (!textureView.isAvailable || textureView.width == 0 || textureView.height == 0) {
            return
        }

        val characteristics = activeCameraCharacteristics
        val lensFacing = characteristics?.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_BACK
        val previewMirror = lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        val matrix = previewCoordinateMapper.update(
            CameraCoordinateMapper.Config(
                imageWidth = previewSize.width,
                imageHeight = previewSize.height,
                viewWidth = textureView.width,
                viewHeight = textureView.height,
                rotationDegrees = computeFrameRotationDegrees(characteristics),
                mirrorHorizontally = previewMirror,
            ),
        )
        overlayCoordinateMapper.update(
            CameraCoordinateMapper.Config(
                imageWidth = previewSize.width,
                imageHeight = previewSize.height,
                viewWidth = textureView.width,
                viewHeight = textureView.height,
                rotationDegrees = computeFrameRotationDegrees(characteristics),
                mirrorHorizontally = !previewMirror,
            ),
        )
        textureView.setTransform(matrix)
        binding.overlayView.updateCoordinateMapper(overlayCoordinateMapper)
        renderDebugOverlay()
    }

    private fun selectCameraChoice(mode: AppCameraMode): CameraChoice? {
        modeUnavailableReason = null
        val candidates = cameraManager.cameraIdList.mapNotNull { cameraId ->
            val characteristics = try {
                cameraManager.getCameraCharacteristics(cameraId)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return@mapNotNull null
            val yuvSizes = sortSizes(streamMap.getOutputSizes(ImageFormat.YUV_420_888))
            val aeRanges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
            )?.toList().orEmpty()
            if (yuvSizes.isEmpty() || aeRanges.isEmpty()) {
                return@mapNotNull null
            }

            val requestedPlan = when (mode) {
                AppCameraMode.STABLE_TRACKING -> chooseStableTrackingPlan(cameraId, yuvSizes, aeRanges)
                AppCameraMode.HIGH_SPEED_PREVIEW_TEST -> chooseHighSpeedPlan(cameraId, streamMap)
            } ?: return@mapNotNull null
            val hardwareLevel = hardwareLevelScore(
                hardwareLevelName(characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
            )
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

            Triple(
                CameraChoice(
                    cameraId = cameraId,
                    characteristics = characteristics,
                    requestedPlan = requestedPlan,
                ),
                lensFacing == CameraCharacteristics.LENS_FACING_BACK,
                hardwareLevel,
            )
        }

        if (candidates.isEmpty()) {
            modeUnavailableReason = when (mode) {
                AppCameraMode.STABLE_TRACKING -> "Stable tracking mode needs a 1280x720 YUV stream at 30fps or 60fps."
                AppCameraMode.HIGH_SPEED_PREVIEW_TEST -> "High-speed preview test mode needs a constrained high-speed Camera2 stream."
            }
            return null
        }

        return candidates
            .sortedWith(
                compareByDescending<Triple<CameraChoice, Boolean, Int>> { it.second }
                    .thenByDescending { it.third }
                    .thenByDescending {
                        if (it.first.requestedPlan.useConstrainedHighSpeed) {
                            highSpeedPlanScore(it.first.requestedPlan)
                        } else {
                            stablePlanScore(it.first.requestedPlan)
                        }
                    }
                    .thenByDescending { it.first.requestedPlan.fpsRange.upper }
            )
            .firstOrNull()
            ?.first
    }

    private fun chooseStableTrackingPlan(
        cameraId: String,
        yuvSizes: List<Size>,
        aeRanges: List<Range<Int>>,
    ): CapturePlan? {
        val normalSize = yuvSizes.firstOrNull { it.width == 1280 && it.height == 720 } ?: return null
        val normalRange = chooseStableFpsRange(aeRanges) ?: return null
        return CapturePlan(
            cameraId = cameraId,
            previewSize = normalSize,
            fpsRange = normalRange,
            useConstrainedHighSpeed = false,
            label = "Stable ${formatRange(normalRange)}",
        )
    }

    private fun chooseHighSpeedPlan(
        cameraId: String,
        streamMap: StreamConfigurationMap,
    ): CapturePlan? {
        val candidates = mutableListOf<CapturePlan>()
        streamMap.highSpeedVideoSizes.forEach { size ->
            streamMap.getHighSpeedVideoFpsRangesFor(size).forEach { range ->
                if (range.upper >= 120 || range.upper >= 60) {
                    candidates += CapturePlan(
                        cameraId = cameraId,
                        previewSize = size,
                        fpsRange = range,
                        useConstrainedHighSpeed = true,
                        label = "Constrained ${formatRange(range)}",
                    )
                }
            }
        }

        return candidates.maxWithOrNull(
            compareBy<CapturePlan>(
                { highSpeedPlanScore(it) },
                { it.fpsRange.upper },
                { if (it.previewSize.width == 1280 && it.previewSize.height == 720) 1 else 0 },
            ),
        )
    }

    private fun chooseStableFpsRange(ranges: List<Range<Int>>): Range<Int>? {
        return ranges.maxWithOrNull(
            compareBy<Range<Int>>(
                { stableRangeScore(it) },
                { it.upper },
                { it.lower },
            ),
        )
    }

    private fun chooseTrackingSize(sizes: List<Size>): Size? {
        if (sizes.isEmpty()) {
            return null
        }
        val targetArea = 1280L * 720L
        return sizes.minWithOrNull(
            compareBy<Size>(
                { if (it.width == 1280 && it.height == 720) 0 else 1 },
                { abs(it.width.toFloat() / it.height.toFloat() - 16f / 9f) },
                { abs(it.width.toLong() * it.height.toLong() - targetArea) },
            ),
        )
    }

    private fun highSpeedPlanScore(plan: CapturePlan): Int {
        val is720p = plan.previewSize.width == 1280 && plan.previewSize.height == 720
        val isFixed120 = plan.fpsRange.lower == 120 && plan.fpsRange.upper == 120
        return when {
            is720p && isFixed120 -> 6
            isFixed120 -> 5
            is720p && plan.fpsRange.upper >= 120 -> 4
            plan.fpsRange.upper >= 120 -> 3
            is720p && plan.fpsRange.upper >= 60 -> 2
            plan.fpsRange.upper >= 60 -> 1
            else -> 0
        }
    }

    private fun stablePlanScore(plan: CapturePlan): Int {
        val is720p = plan.previewSize.width == 1280 && plan.previewSize.height == 720
        return stableRangeScore(plan.fpsRange) * 10 + if (is720p) 1 else 0
    }

    private fun stableRangeScore(range: Range<Int>): Int {
        return when {
            range.lower == 60 && range.upper == 60 -> 5
            range.upper >= 60 && range.lower <= 60 -> 4
            range.lower == 30 && range.upper == 30 -> 3
            range.upper >= 30 && range.lower <= 30 -> 2
            else -> 1
        }
    }

    private fun runCameraDiagnostics() {
        binding.diagnosticSummaryText.text = getString(R.string.camera_diagnostics_loading)
        binding.diagnosticContent.removeAllViews()
        diagnosticExecutor.execute {
            val model = buildCameraDiagnosticsModel()
            runOnUiThread {
                renderCameraDiagnostics(model)
            }
        }
    }

    private fun buildCameraDiagnosticsModel(): DiagnosticsScreenModel {
        val specs = mutableListOf<DirectCameraSpec>()
        val errors = mutableListOf<String>()

        cameraManager.cameraIdList.sorted().forEach { cameraId ->
            try {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                specs += readDirectCameraSpec(cameraId, characteristics)
            } catch (error: Exception) {
                errors += "Camera $cameraId could not be read (${error.message})."
            }
        }

        val highSpeedIds = specs.filter { it.highSpeedSummary.bestMode != null }.map { it.cameraId }
        val summaryLines = buildList {
            add("Direct Camera2 read. Found ${specs.size} cameras.")
            add(
                if (highSpeedIds.isEmpty()) {
                    "No camera reported a usable constrained high-speed mode for tracking."
                } else {
                    "Usable constrained high-speed tracking candidates: ${highSpeedIds.joinToString(", ")}."
                },
            )
            add("120fps detection is not enough by itself; actual capture is verified from SENSOR_TIMESTAMP in the live overlay.")
        }

        return DiagnosticsScreenModel(
            summaryLines = summaryLines,
            sections = specs.map(::buildCameraDiagnosticSection),
            errors = errors,
        )
    }

    private fun readDirectCameraSpec(
        cameraId: String,
        characteristics: CameraCharacteristics,
    ): DirectCameraSpec {
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val fpsRanges = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
        )?.toList().orEmpty()
        val capabilities = characteristics.get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
        ) ?: intArrayOf()

        return DirectCameraSpec(
            cameraId = cameraId,
            lensFacing = lensFacingName(characteristics.get(CameraCharacteristics.LENS_FACING)),
            hardwareLevel = hardwareLevelName(
                characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            ),
            capabilities = capabilities,
            fpsRanges = fpsRanges,
            highSpeedSummary = summarizeHighSpeedModes(streamMap),
            stillSizes = sortSizes(streamMap?.getOutputSizes(ImageFormat.JPEG)),
            videoSizes = sortSizes(streamMap?.getOutputSizes(MediaRecorder::class.java)),
            yuvSizes = sortSizes(streamMap?.getOutputSizes(ImageFormat.YUV_420_888)),
            isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocusDistance = characteristics.get(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
            ) ?: 0f,
        )
    }

    private fun buildCameraDiagnosticSection(spec: DirectCameraSpec): CameraDiagnosticSection {
        val preferredTrackingSize = chooseTrackingSize(spec.yuvSizes)
        val preferredNormalRange = chooseStableFpsRange(spec.fpsRanges)

        val rows = buildList {
            add(DiagnosticRow("Camera", "${spec.cameraId} - ${spec.lensFacing}"))
            add(DiagnosticRow("Camera2 level", spec.hardwareLevel))
            add(DiagnosticRow("Still sizes", formatSizes(spec.stillSizes)))
            add(DiagnosticRow("Video sizes", formatSizes(spec.videoSizes)))
            add(DiagnosticRow("YUV sizes", formatSizes(spec.yuvSizes)))
            add(DiagnosticRow("AE FPS ranges", formatRanges(spec.fpsRanges)))
            add(DiagnosticRow("High-speed video", spec.highSpeedSummary.lines.ifEmpty { listOf("none") }.joinToString("\n")))
            add(DiagnosticRow("Preferred tracking size", preferredTrackingSize?.let { "${it.width}x${it.height}" } ?: "none"))
            add(DiagnosticRow("Preferred normal FPS", formatRange(preferredNormalRange)))
            add(DiagnosticRow("ISO", formatIsoRange(spec.isoRange)))
            add(DiagnosticRow("Exposure range", formatExposureRange(spec.exposureRange)))
            add(DiagnosticRow("Min focus distance", if (spec.minFocusDistance > 0f) "${trimDecimal(spec.minFocusDistance, 2)} diopters" else "fixed focus"))
            add(DiagnosticRow("Capabilities", formatCapabilities(spec.capabilities)))
            add(DiagnosticRow("Verdict", buildCameraVerdict(spec)))
        }

        return CameraDiagnosticSection(
            title = "${spec.cameraId} - ${spec.lensFacing}",
            subtitle = "Direct Camera2 report.",
            rows = rows,
        )
    }

    private fun renderCameraDiagnostics(model: DiagnosticsScreenModel) {
        binding.diagnosticSummaryText.text = model.summaryLines.joinToString("\n")
        binding.diagnosticContent.removeAllViews()

        if (model.sections.isEmpty()) {
            binding.diagnosticContent.addView(createInfoTextView("No cameras were reported by Camera2."))
        } else {
            model.sections.forEach { section ->
                binding.diagnosticContent.addView(createSectionView(section))
            }
        }

        if (model.errors.isNotEmpty()) {
            binding.diagnosticContent.addView(
                createSectionView(
                    CameraDiagnosticSection(
                        title = "Read errors",
                        subtitle = "Some camera IDs could not be parsed.",
                        rows = model.errors.mapIndexed { index, error ->
                            DiagnosticRow("Error ${index + 1}", error)
                        },
                    ),
                ),
            )
        }
    }

    private fun createSectionView(section: CameraDiagnosticSection): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(12)
            }
        }

        container.addView(
            TextView(this).apply {
                setBackgroundColor(Color.parseColor("#2C2C2C"))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                text = section.title
            },
        )

        container.addView(
            TextView(this).apply {
                setBackgroundColor(Color.parseColor("#1E2A22"))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextColor(Color.parseColor("#CFE6D7"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                text = section.subtitle
            },
        )

        section.rows.forEachIndexed { index, row ->
            container.addView(createRowView(row, index))
        }

        return container
    }

    private fun createRowView(row: DiagnosticRow, index: Int): View {
        val rowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setBackgroundColor(if (index % 2 == 0) Color.parseColor("#262626") else Color.parseColor("#1D1D1D"))
            setPadding(dp(10), dp(7), dp(10), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        rowLayout.addView(
            TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f)
                setTextColor(Color.parseColor("#D0D0D0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                text = row.label
            },
        )

        rowLayout.addView(
            TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.58f)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                text = row.value
            },
        )

        return rowLayout
    }

    private fun createInfoTextView(message: String): View {
        return TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            text = message
        }
    }

    private fun applyDiagnosticsExpansionState() {
        binding.diagnosticSummaryText.visibility = if (diagnosticsExpanded) View.VISIBLE else View.GONE
        binding.diagnosticScroll.visibility = if (diagnosticsExpanded) View.VISIBLE else View.GONE
        binding.toggleDiagnosticsButton.text =
            getString(
                if (diagnosticsExpanded) {
                    R.string.camera_diagnostics_collapse
                } else {
                    R.string.camera_diagnostics_expand
                },
            )

        binding.diagnosticCard.layoutParams = binding.diagnosticCard.layoutParams.apply {
            height = if (diagnosticsExpanded) {
                dp(DIAGNOSTIC_CARD_EXPANDED_HEIGHT_DP)
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    private fun applyControlsExpansionState() {
        val controlsVisibility = if (controlsExpanded) View.VISIBLE else View.GONE
        binding.cameraModeTitle.visibility = controlsVisibility
        binding.cameraModeGroup.visibility = controlsVisibility
        binding.ballProfileTitle.visibility = controlsVisibility
        binding.ballProfileGroup.visibility = controlsVisibility
        binding.showCandidatesSwitch.visibility = controlsVisibility
        binding.exposureLabel.visibility = controlsVisibility
        binding.exposureSeekBar.visibility = controlsVisibility
        binding.zoomLabel.visibility = controlsVisibility
        binding.zoomSeekBar.visibility = controlsVisibility
        binding.torchSwitch.visibility = controlsVisibility
        binding.manualExposureSwitch.visibility = controlsVisibility
        binding.isoLabel.visibility = controlsVisibility
        binding.isoSeekBar.visibility = controlsVisibility
        binding.shutterLabel.visibility = controlsVisibility
        binding.shutterSeekBar.visibility = controlsVisibility
        binding.manualFocusSwitch.visibility = controlsVisibility
        binding.focusLabel.visibility = controlsVisibility
        binding.focusSeekBar.visibility = controlsVisibility
        binding.manualWhiteBalanceSwitch.visibility = controlsVisibility
        binding.warmthLabel.visibility = controlsVisibility
        binding.warmthSeekBar.visibility = controlsVisibility
        binding.resetProButton.visibility = controlsVisibility

        binding.toggleControlsButton.text =
            getString(
                if (controlsExpanded) {
                    R.string.camera_controls_collapse
                } else {
                    R.string.camera_controls_expand
                },
            )

        binding.controlCard.layoutParams = binding.controlCard.layoutParams.apply {
            height = if (controlsExpanded) {
                dp(CONTROL_CARD_EXPANDED_HEIGHT_DP)
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    private fun summarizeHighSpeedModes(streamMap: StreamConfigurationMap?): HighSpeedSummary {
        if (streamMap == null) {
            return HighSpeedSummary()
        }

        val lines = mutableListOf<String>()
        var bestMode: HighSpeedMode? = null
        streamMap.highSpeedVideoSizes
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
            .forEach { size ->
                val ranges = streamMap.getHighSpeedVideoFpsRangesFor(size)
                    .sortedWith(compareByDescending<Range<Int>> { it.upper }.thenByDescending { it.lower })
                lines += "${size.width}x${size.height}: ${formatRanges(ranges)}"
                val fastestRange = ranges.maxByOrNull { it.upper }
                if (fastestRange != null) {
                    val candidate = HighSpeedMode(size, fastestRange)
                    val candidateScore = candidate.range.upper * 10 +
                        if (size.width == 1280 && size.height == 720) 1 else 0
                    val currentScore = bestMode?.let {
                        it.range.upper * 10 + if (it.size.width == 1280 && it.size.height == 720) 1 else 0
                    } ?: -1
                    if (candidateScore > currentScore) {
                        bestMode = candidate
                    }
                }
            }

        return HighSpeedSummary(lines = lines, bestMode = bestMode)
    }

    private fun buildCameraVerdict(spec: DirectCameraSpec): String {
        return when {
            spec.highSpeedSummary.bestMode != null -> {
                val mode = spec.highSpeedSummary.bestMode
                "Constrained high-speed is exposed. Fastest advertised mode: ${mode.size.width}x${mode.size.height} @ ${formatRange(mode.range)}."
            }
            spec.capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) -> {
                "Constrained high-speed capability is advertised, but no usable mode list was returned."
            }
            else -> {
                "Standard Camera2 pipeline only."
            }
        }
    }

    private fun lensFacingName(lensFacing: Int?): String {
        return when (lensFacing) {
            CameraMetadata.LENS_FACING_FRONT -> "FRONT"
            CameraMetadata.LENS_FACING_BACK -> "BACK"
            CameraMetadata.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }
    }

    private fun cameraModeLabel(mode: AppCameraMode): String {
        return when (mode) {
            AppCameraMode.STABLE_TRACKING -> "Standard Tracking"
            AppCameraMode.HIGH_SPEED_PREVIEW_TEST -> "HS Preview Test"
        }
    }

    private fun isHighSpeedModeSelectedOrActive(): Boolean {
        return selectedCameraMode == AppCameraMode.HIGH_SPEED_PREVIEW_TEST ||
            activePlan?.useConstrainedHighSpeed == true
    }

    private fun hardwareLevelName(level: Int?): String {
        return when (level) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }
    }

    private fun hardwareLevelScore(level: String): Int {
        return when (level) {
            "LEVEL_3" -> 4
            "FULL" -> 3
            "LIMITED" -> 2
            "EXTERNAL" -> 1
            "LEGACY" -> 0
            else -> -1
        }
    }

    private fun formatCapabilities(capabilities: IntArray): String {
        if (capabilities.isEmpty()) {
            return "none"
        }
        return capabilities
            .map { capabilityName(it) }
            .sorted()
            .joinToString(", ")
    }

    private fun capabilityName(capability: Int): String {
        return when (capability) {
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
            else -> "CAP_$capability"
        }
    }

    private fun formatRange(range: Range<Int>?): String {
        return if (range == null) {
            "n/a"
        } else if (range.lower == range.upper) {
            "${range.upper}"
        } else {
            "${range.lower}-${range.upper}"
        }
    }

    private fun formatRanges(ranges: List<Range<Int>>): String {
        if (ranges.isEmpty()) {
            return "none"
        }
        return ranges.joinToString(", ") { formatRange(it) }
    }

    private fun sortSizes(sizes: Array<Size>?): List<Size> {
        return sizes
            ?.distinctBy { "${it.width}x${it.height}" }
            ?.sortedByDescending { it.width.toLong() * it.height.toLong() }
            ?: emptyList()
    }

    private fun formatSizes(sizes: List<Size>): String {
        if (sizes.isEmpty()) {
            return "not exposed"
        }
        return sizes.take(6).joinToString("\n") { "${it.width}x${it.height}" }
    }

    private fun formatIsoRange(range: Range<Int>?): String {
        return if (range == null) {
            "not exposed"
        } else {
            "${range.lower}-${range.upper}"
        }
    }

    private fun formatExposureRange(range: Range<Long>?): String {
        return if (range == null) {
            "not exposed"
        } else {
            "${formatShutterSpeed(range.lower)} to ${formatShutterSpeed(range.upper)}"
        }
    }

    private fun trimDecimal(value: Float, digits: Int): String {
        return "%.${digits}f".format(Locale.US, value)
    }

    private fun yesNo(enabled: Boolean): String = if (enabled) "yes" else "no"

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).roundToInt()
    }

    private fun setStatusText(text: String) {
        if (isMainThread()) {
            binding.statusText.text = text
        } else {
            runOnMainThread {
                binding.statusText.text = text
            }
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (isMainThread()) {
            action()
        } else {
            runOnUiThread(action)
        }
    }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private companion object {
        private const val TAG = "TableTennisTracker"
        private const val DIAGNOSTIC_CARD_EXPANDED_HEIGHT_DP = 332
        private const val CONTROL_CARD_EXPANDED_HEIGHT_DP = 320
        private const val DEBUG_UI_INTERVAL_MS = 250L
        private const val DEBUG_LOG_INTERVAL_MS = 1_000L
    }
}
