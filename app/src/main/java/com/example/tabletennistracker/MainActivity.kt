package com.example.tabletennistracker

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.os.Bundle
import android.util.Range
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

        setupControls()
        if (hasCameraPermission()) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
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
                } catch (error: Exception) {
                    binding.statusText.text = "Camera start failed: ${error.message}"
                }
            },
            ContextCompat.getMainExecutor(this),
        )
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
}
