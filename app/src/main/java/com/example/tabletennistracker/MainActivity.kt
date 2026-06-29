package com.example.tabletennistracker

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.camera2.params.RggbChannelVector
import android.os.Bundle
import android.util.Range
import android.util.Size
import android.widget.SeekBar
import androidx.annotation.OptIn
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.tabletennistracker.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    private data class CameraCapabilities(
        val manualSensorSupported: Boolean = false,
        val manualWhiteBalanceSupported: Boolean = false,
        val isoRange: Range<Int>? = null,
        val exposureTimeRange: Range<Long>? = null,
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

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var diagnosticExecutor: ExecutorService

    private var camera: Camera? = null
    private var camera2CameraControl: Camera2CameraControl? = null
    private var exposureStep = 0f
    private var cameraCapabilities = CameraCapabilities()
    private val proCameraSettings = ProCameraSettings()

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            binding.statusText.text = "Camera permission is required."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        diagnosticExecutor = Executors.newSingleThreadExecutor()

        setupControls()
        setupDiagnostics()
        runCameraDiagnostics()
        if (hasCameraPermission()) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        diagnosticExecutor.shutdown()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()

                val previewBuilder = Preview.Builder()
                Camera2Interop.Extender(previewBuilder).applyDefaultCaptureOptions()
                val preview = previewBuilder.build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                val analysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                Camera2Interop.Extender(analysisBuilder).applyDefaultCaptureOptions()
                val imageAnalysis = analysisBuilder.build().also {
                    it.setAnalyzer(
                        cameraExecutor,
                        BallTrackerAnalyzer { trackerResult ->
                            runOnUiThread {
                                binding.overlayView.updateResult(trackerResult)
                                binding.statusText.text = if (trackerResult == null) {
                                    getString(R.string.status_no_ball)
                                } else {
                                    "${getString(R.string.status_ball_found)} ${(trackerResult.confidence * 100f).roundToInt()}%"
                                }
                            }
                        },
                    )
                }

                try {
                    cameraProvider.unbindAll()
                    camera = cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                    camera2CameraControl = camera?.let { Camera2CameraControl.from(it.cameraControl) }
                    binding.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    configureExposureSlider()
                    configureZoomSlider()
                    configureAdvancedCapabilities()
                    applyAdvancedCaptureOptions()
                    runCameraDiagnostics()
                } catch (error: Exception) {
                    binding.statusText.text = "Camera start failed: ${error.message}"
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun setupDiagnostics() {
        binding.refreshDiagnosticsButton.setOnClickListener {
            runCameraDiagnostics()
        }
    }

    private fun setupControls() {
        binding.ballProfileGroup.setOnCheckedChangeListener { _, checkedId ->
            NativeBallTracker.selectedProfile = when (checkedId) {
                R.id.profileOrangeRadio -> NativeBallTracker.BallProfile.ORANGE
                R.id.profileWhiteRadio -> NativeBallTracker.BallProfile.WHITE
                else -> NativeBallTracker.BallProfile.AUTO
            }
        }

        binding.zoomSeekBar.min = 10
        binding.zoomSeekBar.max = 40
        binding.zoomSeekBar.progress = 10
        binding.zoomSeekBar.setOnSeekBarChangeListener(simpleSeekBarListener { value ->
            val zoomRatio = value / 10f
            binding.zoomLabel.text = "Zoom: ${"%.1f".format(zoomRatio)}x"
            camera?.cameraControl?.setZoomRatio(zoomRatio)
        })

        binding.torchSwitch.setOnCheckedChangeListener { _, enabled ->
            camera?.cameraControl?.enableTorch(enabled)
        }

        binding.manualExposureSwitch.setOnCheckedChangeListener { _, enabled ->
            proCameraSettings.manualExposureEnabled = enabled
            updateAdvancedLabels()
            updateAdvancedControlAvailability()
            applyAdvancedCaptureOptions()
        }

        binding.manualFocusSwitch.setOnCheckedChangeListener { _, enabled ->
            proCameraSettings.manualFocusEnabled = enabled
            updateAdvancedLabels()
            updateAdvancedControlAvailability()
            applyAdvancedCaptureOptions()
        }

        binding.manualWhiteBalanceSwitch.setOnCheckedChangeListener { _, enabled ->
            proCameraSettings.manualWhiteBalanceEnabled = enabled
            updateAdvancedLabels()
            updateAdvancedControlAvailability()
            applyAdvancedCaptureOptions()
        }

        binding.isoSeekBar.min = 100
        binding.isoSeekBar.max = 3200
        binding.isoSeekBar.progress = 400
        binding.isoSeekBar.setOnSeekBarChangeListener(simpleSeekBarListener { value ->
            proCameraSettings.iso = value
            updateIsoLabel()
            applyAdvancedCaptureOptions()
        })

        binding.shutterSeekBar.min = 0
        binding.shutterSeekBar.max = 1000
        binding.shutterSeekBar.progress = 320
        binding.shutterSeekBar.setOnSeekBarChangeListener(simpleSeekBarListener { value ->
            proCameraSettings.shutterNs = progressToShutterNs(value)
            updateShutterLabel()
            applyAdvancedCaptureOptions()
        })

        binding.focusSeekBar.min = 0
        binding.focusSeekBar.max = 1000
        binding.focusSeekBar.progress = 0
        binding.focusSeekBar.setOnSeekBarChangeListener(simpleSeekBarListener { value ->
            proCameraSettings.focusDistanceDiopters =
                (cameraCapabilities.minFocusDistance * (value / 1000f)).coerceAtLeast(0f)
            updateFocusLabel()
            applyAdvancedCaptureOptions()
        })

        binding.warmthSeekBar.min = 2500
        binding.warmthSeekBar.max = 8500
        binding.warmthSeekBar.progress = proCameraSettings.warmthKelvin
        binding.warmthSeekBar.setOnSeekBarChangeListener(simpleSeekBarListener { value ->
            proCameraSettings.warmthKelvin = value
            updateWarmthLabel()
            applyAdvancedCaptureOptions()
        })

        binding.resetProButton.setOnClickListener {
            resetProControls()
        }

        updateAdvancedLabels()
        updateAdvancedControlAvailability()
    }

    private fun configureExposureSlider() {
        val exposureState = camera?.cameraInfo?.exposureState ?: return
        val range = exposureState.exposureCompensationRange
        exposureStep = exposureState.exposureCompensationStep.toFloat()
        binding.exposureSeekBar.min = range.lower
        binding.exposureSeekBar.max = range.upper
        binding.exposureSeekBar.progress = exposureState.exposureCompensationIndex
        updateExposureLabel(exposureState.exposureCompensationIndex)
        binding.exposureSeekBar.setOnSeekBarChangeListener(simpleSeekBarListener { value ->
            camera?.cameraControl?.setExposureCompensationIndex(value)
            updateExposureLabel(value)
        })
    }

    private fun configureZoomSlider() {
        val zoomState = camera?.cameraInfo?.zoomState?.value ?: return
        val currentZoom = zoomState.zoomRatio.coerceIn(1f, 4f)
        binding.zoomSeekBar.progress = (currentZoom * 10f).roundToInt()
        binding.zoomLabel.text = "Zoom: ${"%.1f".format(currentZoom)}x"
    }

    private fun updateExposureLabel(index: Int) {
        val evValue = index * exposureStep
        binding.exposureLabel.text = "Exposure: ${"%.1f".format(Locale.US, evValue)} EV"
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

    private fun configureAdvancedCapabilities() {
        val boundCamera = camera ?: return
        val camera2Info = Camera2CameraInfo.from(boundCamera.cameraInfo)
        val capabilities = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
        ) ?: intArrayOf()
        val availableAwbModes = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,
        ) ?: intArrayOf()
        val isoRange = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
        )
        val exposureRange = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
        )
        val minFocusDistance = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
        ) ?: 0f

        cameraCapabilities = CameraCapabilities(
            manualSensorSupported =
                capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            manualWhiteBalanceSupported =
                capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) &&
                    availableAwbModes.contains(CaptureRequest.CONTROL_AWB_MODE_OFF),
            isoRange = isoRange,
            exposureTimeRange = exposureRange,
            minFocusDistance = minFocusDistance,
        )

        isoRange?.let {
            binding.isoSeekBar.min = it.lower
            binding.isoSeekBar.max = it.upper
            proCameraSettings.iso = proCameraSettings.iso.coerceIn(it.lower, it.upper)
            binding.isoSeekBar.progress = proCameraSettings.iso
        }

        exposureRange?.let {
            proCameraSettings.shutterNs = proCameraSettings.shutterNs.coerceIn(it.lower, it.upper)
            binding.shutterSeekBar.progress = shutterNsToProgress(proCameraSettings.shutterNs)
        }

        if (minFocusDistance <= 0f) {
            proCameraSettings.focusDistanceDiopters = 0f
            binding.focusSeekBar.progress = 0
        }

        updateAdvancedLabels()
        updateAdvancedControlAvailability()
    }

    private fun updateAdvancedControlAvailability() {
        val manualExposureAvailable =
            cameraCapabilities.manualSensorSupported &&
                cameraCapabilities.isoRange != null &&
                cameraCapabilities.exposureTimeRange != null
        val manualFocusAvailable = cameraCapabilities.minFocusDistance > 0f
        val manualWhiteBalanceAvailable = cameraCapabilities.manualWhiteBalanceSupported

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
        binding.exposureSeekBar.isEnabled = !binding.manualExposureSwitch.isChecked
        binding.focusSeekBar.isEnabled = manualFocusAvailable && binding.manualFocusSwitch.isChecked
        binding.warmthSeekBar.isEnabled = manualWhiteBalanceAvailable && binding.manualWhiteBalanceSwitch.isChecked
    }

    private fun applyAdvancedCaptureOptions() {
        val control = camera2CameraControl ?: return
        control.clearCaptureRequestOptions()
        val isoRange = cameraCapabilities.isoRange
        val exposureTimeRange = cameraCapabilities.exposureTimeRange

        val optionsBuilder = CaptureRequestOptions.Builder()
        optionsBuilder.setCaptureRequestOption(
            CaptureRequest.CONTROL_MODE,
            CaptureRequest.CONTROL_MODE_AUTO,
        )

        if (
            proCameraSettings.manualExposureEnabled &&
            cameraCapabilities.manualSensorSupported &&
            isoRange != null &&
            exposureTimeRange != null
        ) {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF,
            )
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY,
                proCameraSettings.iso.coerceIn(
                    isoRange.lower,
                    isoRange.upper,
                ),
            )
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                proCameraSettings.shutterNs.coerceIn(
                    exposureTimeRange.lower,
                    exposureTimeRange.upper,
                ),
            )
        } else {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON,
            )
        }

        if (proCameraSettings.manualFocusEnabled && cameraCapabilities.minFocusDistance > 0f) {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF,
            )
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                proCameraSettings.focusDistanceDiopters.coerceIn(0f, cameraCapabilities.minFocusDistance),
            )
        } else {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            )
        }

        if (proCameraSettings.manualWhiteBalanceEnabled && cameraCapabilities.manualWhiteBalanceSupported) {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_OFF,
            )
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_FAST,
            )
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                warmthToGains(proCameraSettings.warmthKelvin),
            )
        } else {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO,
            )
        }

        control.setCaptureRequestOptions(optionsBuilder.build())
    }

    private fun resetProControls() {
        binding.manualExposureSwitch.isChecked = false
        binding.manualFocusSwitch.isChecked = false
        binding.manualWhiteBalanceSwitch.isChecked = false

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

        updateAdvancedLabels()
        updateAdvancedControlAvailability()
        applyAdvancedCaptureOptions()
    }

    private fun updateAdvancedLabels() {
        updateIsoLabel()
        updateShutterLabel()
        updateFocusLabel()
        updateWarmthLabel()
    }

    private fun updateIsoLabel() {
        binding.isoLabel.text = if (
            !cameraCapabilities.manualSensorSupported ||
            cameraCapabilities.isoRange == null
        ) {
            "ISO: Not supported on this camera"
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
        } else if (!binding.manualExposureSwitch.isChecked) {
            "Shutter: Auto"
        } else {
            "Shutter: ${formatShutterSpeed(proCameraSettings.shutterNs)}"
        }
    }

    private fun updateFocusLabel() {
        binding.focusLabel.text = if (cameraCapabilities.minFocusDistance <= 0f) {
            "Focus: Fixed focus lens"
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
        } else if (!binding.manualWhiteBalanceSwitch.isChecked) {
            "Warmth: Auto"
        } else {
            "Warmth: ${proCameraSettings.warmthKelvin}K"
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

    private fun <T> Camera2Interop.Extender<T>.applyDefaultCaptureOptions() {
        setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
        )
        setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AE_MODE_ON,
        )
        setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO,
        )
    }

    private fun runCameraDiagnostics() {
        binding.diagnosticText.text = getString(R.string.camera_diagnostics_loading)
        diagnosticExecutor.execute {
            val report = buildCameraDiagnosticsReport()
            runOnUiThread {
                binding.diagnosticText.text = report
            }
        }
    }

    private fun buildCameraDiagnosticsReport(): String {
        val cameraManager = getSystemService(CameraManager::class.java)
            ?: return "CameraManager is not available on this device."

        val diagnostics = mutableListOf<CameraDiagnostic>()
        val errors = mutableListOf<String>()

        cameraManager.cameraIdList.sorted().forEach { cameraId ->
            try {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val capabilities = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
                ) ?: intArrayOf()
                val fpsRanges = characteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
                )?.sortedWith(compareBy<Range<Int>> { it.lower }.thenBy { it.upper }) ?: emptyList()
                val streamMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
                )
                val highSpeedSummary = summarizeHighSpeedModes(streamMap)
                val lensFacing = lensFacingName(
                    characteristics.get(CameraCharacteristics.LENS_FACING),
                )
                val hardwareLevel = hardwareLevelName(
                    characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
                )
                val isoRange = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
                )
                val exposureRange = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
                )
                val minFocusDistance = characteristics.get(
                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
                ) ?: 0f
                val supportsManual = capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
                )
                val supportsHighSpeedCapability = capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO,
                )

                diagnostics += CameraDiagnostic(
                    cameraId = cameraId,
                    lensFacing = lensFacing,
                    hardwareLevel = hardwareLevel,
                    capabilities = capabilities,
                    fpsRanges = fpsRanges,
                    highSpeedSummary = highSpeedSummary,
                    isoRange = isoRange,
                    exposureRange = exposureRange,
                    minFocusDistance = minFocusDistance,
                    supportsManual = supportsManual,
                    supportsHighSpeedCapability = supportsHighSpeedCapability,
                )
            } catch (error: Exception) {
                errors += "Camera ID $cameraId: failed to read characteristics (${error.message})"
            }
        }

        val report = StringBuilder()
        val preferredManualCamera = diagnostics
            .filter { it.supportsManual }
            .maxWithOrNull(compareBy<CameraDiagnostic> { it.lensFacing != "BACK" }
                .thenByDescending { hardwareLevelScore(it.hardwareLevel) })
        val preferredHighSpeedCamera = diagnostics
            .filter { it.highSpeedSummary.bestMode != null }
            .maxWithOrNull(compareBy<CameraDiagnostic> { it.lensFacing != "BACK" }
                .thenByDescending { it.highSpeedSummary.bestMode?.fps ?: 0 }
                .thenByDescending { hardwareLevelScore(it.hardwareLevel) })
        val backCameraCount = diagnostics.count { it.lensFacing == "BACK" }

        report.appendLine("Quick answer")
        report.appendLine("- Important: 'AE FPS 120' only means the auto-exposure pipeline can run that fast. It does not automatically mean a real 120 FPS video mode exists.")
        report.appendLine(
            "- Current app camera path: DEFAULT_BACK_CAMERA. Manual ISO/shutter is ${
                if (
                    cameraCapabilities.manualSensorSupported &&
                    cameraCapabilities.isoRange != null &&
                    cameraCapabilities.exposureTimeRange != null
                ) {
                    "available on the active preview."
                } else {
                    "not exposed on the active preview."
                }
            }",
        )
        report.appendLine(
            if (preferredManualCamera == null) {
                "- Best manual-control candidate: none. Camera2 does not expose MANUAL_SENSOR on any camera ID."
            } else {
                "- Best manual-control candidate: Camera ID ${preferredManualCamera.cameraId} (${preferredManualCamera.lensFacing}, ${preferredManualCamera.hardwareLevel}) with ISO ${formatIsoRange(preferredManualCamera.isoRange)} and shutter ${formatExposureRange(preferredManualCamera.exposureRange)}."
            },
        )
        report.appendLine(
            if (preferredHighSpeedCamera == null) {
                "- Best high-speed candidate: none. No real constrained high-speed recording mode is exposed to third-party apps."
            } else {
                val bestMode = preferredHighSpeedCamera.highSpeedSummary.bestMode!!
                "- Best high-speed candidate: Camera ID ${preferredHighSpeedCamera.cameraId} (${preferredHighSpeedCamera.lensFacing}) exposes a real Camera2 high-speed mode at ${bestMode.width}x${bestMode.height} @ ${bestMode.fps} FPS."
            },
        )
        report.appendLine(
            "- Rear cameras seen by Camera2: $backCameraCount. If the app says 'not supported', another rear lens may still expose manual controls better than the default one.",
        )
        report.appendLine("- OEM camera apps can still show extra pro controls through private vendor APIs.")
        report.appendLine()

        report.appendLine("Per-camera breakdown")
        diagnostics.forEach { diagnostic ->
            report.appendLine(
                "Camera ID ${diagnostic.cameraId} (${diagnostic.lensFacing}, ${diagnostic.hardwareLevel})",
            )
            report.appendLine(
                "Summary: ${
                    buildCameraVerdict(
                        supportsManual = diagnostic.supportsManual,
                        supportsHighSpeedCapability = diagnostic.supportsHighSpeedCapability,
                        highSpeedSummary = diagnostic.highSpeedSummary,
                        fpsRanges = diagnostic.fpsRanges,
                    )
                }",
            )
            report.appendLine(
                "Recording classification: ${recordingClassification(diagnostic)}",
            )
            report.appendLine(
                "Manual: ${if (diagnostic.supportsManual) "yes" else "no"} | ISO ${formatIsoRange(diagnostic.isoRange)} | Shutter ${formatExposureRange(diagnostic.exposureRange)}",
            )
            report.appendLine(
                "Focus: ${if (diagnostic.minFocusDistance > 0f) "manual focus supported" else "fixed focus only"} | Auto-exposure FPS range ${formatRanges(diagnostic.fpsRanges)}",
            )
            report.appendLine(
                "High-speed capability flag: ${if (diagnostic.supportsHighSpeedCapability) "yes" else "no"}",
            )
            report.appendLine("Constrained high-speed recording modes:")
            if (diagnostic.highSpeedSummary.lines.isEmpty()) {
                report.appendLine("- none exposed")
            } else {
                diagnostic.highSpeedSummary.lines.forEach { line ->
                    report.appendLine("- $line")
                }
            }
            report.appendLine("Capabilities: ${formatCapabilities(diagnostic.capabilities)}")
            report.appendLine()
        }

        if (errors.isNotEmpty()) {
            report.appendLine("Read errors")
            errors.forEach { error ->
                report.appendLine("- $error")
            }
        }

        return report.toString().trim()
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
                val fastestForSize = ranges.maxByOrNull { it.upper }
                if (fastestForSize != null) {
                    val candidate = HighSpeedMode(size.width, size.height, fastestForSize.upper)
                    if (bestMode == null || candidate.fps > bestMode!!.fps) {
                        bestMode = candidate
                    }
                }
            }

        return HighSpeedSummary(lines = lines, bestMode = bestMode)
    }

    private fun buildCameraVerdict(
        supportsManual: Boolean,
        supportsHighSpeedCapability: Boolean,
        highSpeedSummary: HighSpeedSummary,
        fpsRanges: List<Range<Int>>,
    ): String {
        val maxAeFps = fpsRanges.maxOfOrNull { it.upper } ?: 0
        return when {
            supportsHighSpeedCapability && highSpeedSummary.bestMode != null -> {
                val bestMode = highSpeedSummary.bestMode
                val manualText = if (supportsManual) " Manual ISO/shutter is also exposed." else ""
                "Real third-party constrained high-speed recording is exposed. Best mode: ${bestMode.width}x${bestMode.height} @ ${bestMode.fps} FPS.$manualText"
            }
            supportsHighSpeedCapability -> {
                "Camera advertises constrained high-speed capability, but no usable high-speed sizes were returned."
            }
            maxAeFps > 60 -> {
                "Auto-exposure can run up to $maxAeFps FPS, but that is not a real constrained high-speed recording mode."
            }
            supportsManual -> {
                "Manual ISO/shutter is exposed, but constrained high-speed video is not."
            }
            else -> {
                "No manual sensor or constrained high-speed Camera2 path is exposed to third-party apps."
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
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RES_SENSOR"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "REMOSAIC_REPROCESSING"
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "STREAM_USE_CASE"
            else -> "CAP_$capability"
        }
    }

    private fun formatRanges(ranges: List<Range<Int>>): String {
        if (ranges.isEmpty()) {
            return "none"
        }
        return ranges.joinToString(", ") { range ->
            if (range.lower == range.upper) {
                "${range.upper}"
            } else {
                "${range.lower}-${range.upper}"
            }
        }
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

    private fun recordingClassification(diagnostic: CameraDiagnostic): String {
        val maxAeFps = diagnostic.fpsRanges.maxOfOrNull { it.upper } ?: 0
        return when {
            diagnostic.supportsHighSpeedCapability && diagnostic.highSpeedSummary.bestMode != null -> {
                val bestMode = diagnostic.highSpeedSummary.bestMode
                "REAL HIGH-SPEED MODE EXPOSED (${bestMode?.fps} FPS constrained session)"
            }
            diagnostic.supportsHighSpeedCapability -> {
                "HIGH-SPEED FLAG ONLY (no usable constrained session details returned)"
            }
            maxAeFps > 60 -> {
                "AE RANGE ONLY ($maxAeFps FPS auto-exposure, not a real high-speed recording mode)"
            }
            else -> {
                "STANDARD CAMERA PATH"
            }
        }
    }

    private data class HighSpeedMode(
        val width: Int,
        val height: Int,
        val fps: Int,
    )

    private data class HighSpeedSummary(
        val lines: List<String> = emptyList(),
        val bestMode: HighSpeedMode? = null,
    )

    private data class CameraDiagnostic(
        val cameraId: String,
        val lensFacing: String,
        val hardwareLevel: String,
        val capabilities: IntArray,
        val fpsRanges: List<Range<Int>>,
        val highSpeedSummary: HighSpeedSummary,
        val isoRange: Range<Int>?,
        val exposureRange: Range<Long>?,
        val minFocusDistance: Float,
        val supportsManual: Boolean,
        val supportsHighSpeedCapability: Boolean,
    )
}
