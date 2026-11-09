package com.neoproc.neoproccamx.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.location.Location
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.camera2.params.TonemapCurve
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.StabilizationMode
import com.neoproc.neoproccamx.recording.Npv10CaptureSourcePlan
import com.neoproc.neoproccamx.recording.Npv10CaptureSourcePolicy
import com.neoproc.neoproccamx.recording.Npv10InFlightBufferPolicy
import com.neoproc.neoproccamx.utils.OrientationManager
import com.neoproc.neoproccamx.core.RuntimeStageTelemetry
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.pipeline.PipelineHealthMonitor
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.stabilization.EisFrameOrientation
import com.neoproc.neoproccamx.stabilization.GyroEisOrientationFeed
import com.neoproc.neoproccamx.stabilization.GyroExposureWindowReconciler
import com.neoproc.neoproccamx.stabilization.GyroGlobalMotionTerm
import com.neoproc.neoproccamx.stabilization.GyroRingHealth
import com.neoproc.neoproccamx.stabilization.StabilizationSessionConfigPolicy
import com.neoproc.neoproccamx.stabilization.StabilizationSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import android.util.Rational
import com.neoproc.neoproccamx.BuildConfig
import kotlin.math.abs
import kotlin.math.roundToInt

enum class StillCaptureState {
    IDLE, WAITING_PRECAPTURE, WAITING_NON_PRECAPTURE
}

object CameraControllerConfig {
    object ReaderDepth {
        const val STRICT_MAX_JPEG = 5
        const val MAX_JPEG = 5
        const val STRICT_MAX_RAW = 24
        const val MAX_RAW = 16
        const val STRICT_MAX_YUV = 16
        const val MAX_YUV = 12
        const val STRICT_MAX_HARDWARE_IMPORT = 16
        const val MAX_HARDWARE_IMPORT = 6
    }
    
    object RequestTuning {
        const val STRICT_MIN_JPEG_QUALITY = 100
    }
    
    const val MAX_CAMERA_REOPEN_ATTEMPTS = 5
    
    object Performance {
        const val SLOW_OPEN_CAMERA_MS = 300L
        const val SLOW_START_PREVIEW_REQUEST_MS = 300L
        const val SLOW_START_VIDEO_REQUEST_MS = 150L
        const val SLOW_CREATE_SESSION_MS = 600L
        const val SLOW_REFRESH_SESSION_MS = 250L
    }
    
    object Focus {
        const val TAP_METERING_WINDOW_RATIO = 0.12f
    }
}


/**
 * CameraController — Production Camera2 API engine.
 */
class CameraController(private val context: Context) : CameraControllerHost, SensorEventListener {

    companion object {
        private const val TAG = "CameraController"
        private const val TAP_FOCUS_METERING_UNLOCK_MS = 900L
        private const val TAP_FOCUS_METERING_RELOCK_MARGIN_MS = 80L
        private const val READER_CLOSE_FRAME_PIPELINE_IDLE_TIMEOUT_MS = 1_000L
        private const val STILL_CAPTURE_SESSION_WAIT_INTERVAL_MS = 80L
        private const val STILL_CAPTURE_SESSION_WAIT_MAX_ATTEMPTS = 250
        // CP17: frames to let the HAL settle into the requested ISP state before reading it back.
        private const val ISP_BYPASS_READBACK_SETTLE_FRAMES = 5
        // Manual green-magenta WB tint range (P0.1) — matches CinemaAutoWhiteBalanceRequestPolicy.
        private const val MANUAL_WB_TINT_MIN = -150
        private const val MANUAL_WB_TINT_MAX = 150
    }
    
    // ── Sensor Info ───────────────────────────────────────────────────────
    fun hardwareLevelLabel(level: Int): String =
        CameraSensorInfo.hardwareLevelLabel(level)



    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun largestByArea(vararg sizes: Size?): Size? =
        CameraSensorInfo.largestByArea(*sizes)

    private fun resolvePixelArraySize(characteristics: CameraCharacteristics): Size? {
        val standard = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val maximumResolution = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
        } else null
        return largestByArea(standard, maximumResolution)
    }

    private fun resolveStreamConfigurationMaps(characteristics: CameraCharacteristics): List<StreamConfigurationMap> {
        val standard = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val maximumResolution = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
        } else null
        return listOfNotNull(maximumResolution, standard)
    }

    private fun logCriticalHardwareFailure(operation: String, throwable: Throwable?) {
        PipelineHealthMonitor.reportError(TAG, "CRITICAL_HW_FAILURE: $operation", throwable)
    }

    private fun StreamConfigurationMap.getOutputSizesSafely(format: Int): List<Size> =
        runCatching { getOutputSizes(format)?.toList().orEmpty() }
            .getOrElse { error ->
                logCriticalHardwareFailure("StreamConfigurationMap.getOutputSizes(format=$format)", error)
                emptyList()
            }

    private fun <T> StreamConfigurationMap.getOutputSizesSafely(outputClass: Class<T>, label: String): List<Size> =
        runCatching { getOutputSizes(outputClass)?.toList().orEmpty() }
            .getOrElse { error ->
                logCriticalHardwareFailure("StreamConfigurationMap.getOutputSizes($label)", error)
                emptyList()
            }

    private fun StreamConfigurationMap.getHighResolutionOutputSizesSafely(format: Int): List<Size> =
        runCatching { getHighResolutionOutputSizes(format)?.toList().orEmpty() }
            .getOrElse { error ->
                logCriticalHardwareFailure("StreamConfigurationMap.getHighResolutionOutputSizes(format=$format)", error)
                emptyList()
            }

    private fun List<Size>.distinctSortedCameraSizes(): List<Size> =
        distinctBy { "${it.width}x${it.height}" }
            .sortedByDescending { CameraSensorInfo.sizeArea(it) }

    private fun scanVendorReadoutCandidates(
        characteristics: CameraCharacteristics,
        safeExposedSizes: Set<CameraStreamSize>
    ): CameraVendorReadoutScanReport {
        val vendorSizesByKey = characteristics.keys
            .filter { key -> vendorKeyLooksLikeReadout(key.name) }
            .mapNotNull { key ->
                val sizes = extractCameraStreamSizes(readCameraCharacteristicValue(characteristics, key))
                    .distinct()
                if (sizes.isEmpty()) null else key.name to sizes
            }
            .toMap()

        return CameraVendorStreamCandidatePolicy.resolve(
            vendorSizesByKey = vendorSizesByKey,
            safeExposedSizes = safeExposedSizes
        )
    }

    private fun vendorKeyLooksLikeReadout(keyName: String): Boolean {
        val lower = keyName.lowercase()
        if (lower.startsWith("android.")) return false
        return listOf(
            "stream",
            "size",
            "resolution",
            "raw",
            "sensor",
            "video",
            "picture",
            "preview",
            "private",
            "yuv",
            "jpeg",
            "8k",
            "high"
        ).any { lower.contains(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readCameraCharacteristicValue(
        characteristics: CameraCharacteristics,
        key: CameraCharacteristics.Key<*>
    ): Any? = runCatching {
        characteristics.get(key as CameraCharacteristics.Key<Any>)
    }.getOrNull()

    private fun extractCameraStreamSizes(value: Any?): List<CameraStreamSize> = when (value) {
        is Size -> listOf(value.toCameraStreamSize())
        is Array<*> -> value.filterIsInstance<Size>().map { it.toCameraStreamSize() }
        is Collection<*> -> value.filterIsInstance<Size>().map { it.toCameraStreamSize() }
        is IntArray -> value.toPossibleCameraStreamSizes()
        is LongArray -> value.toPossibleCameraStreamSizes()
        else -> emptyList()
    }.filter { it.width > 0 && it.height > 0 }

    @Suppress("UNCHECKED_CAST")
    private fun readStreamBasedFpsTable(characteristics: CameraCharacteristics): IntArray? {
        val key = characteristics.keys
            .firstOrNull { it.name == CameraStreamBasedFpsPolicy.STREAM_TABLE_KEY_NAME }
            ?: return null
        return runCatching {
            characteristics.get(key as CameraCharacteristics.Key<IntArray>)
        }.getOrNull()
    }

    private fun cacheVendorFpsKeys(characteristics: CameraCharacteristics) {
        cachedCaptureRequestKeysByName = runCatching {
            characteristics.availableCaptureRequestKeys.associateBy { it.name }
        }.getOrDefault(emptyMap())
        cachedSessionRequestKeysByName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                characteristics.availableSessionKeys?.associateBy { it.name }.orEmpty()
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        cachedStreamBasedFpsEntries = CameraStreamBasedFpsPolicy.parseStreamBasedTable(
            readStreamBasedFpsTable(characteristics)
        )
        Log.i(
            TAG,
            "Stream-based FPS capability entries=${cachedStreamBasedFpsEntries.size} " +
                "requestKeys=${cachedCaptureRequestKeysByName.keys.filterVendorFpsKeys()} " +
                "sessionKeys=${cachedSessionRequestKeysByName.keys.filterVendorFpsKeys()}"
        )
    }

    private fun Set<String>.filterVendorFpsKeys(): List<String> =
        filter { keyName ->
            keyName == CameraStreamBasedFpsPolicy.PREVIEW_FPS_KEY_NAME ||
                keyName == CameraStreamBasedFpsPolicy.VIDEO_FPS_KEY_NAME ||
                keyName == CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME ||
                keyName == CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME
        }.sorted()

    private fun IntArray.toPossibleCameraStreamSizes(): List<CameraStreamSize> {
        val sizes = mutableListOf<CameraStreamSize>()
        var index = 0
        while (index + 1 < size) {
            addPossibleCameraStreamSize(sizes, this[index].toLong(), this[index + 1].toLong())
            index += 2
        }
        return sizes
    }

    private fun LongArray.toPossibleCameraStreamSizes(): List<CameraStreamSize> {
        val sizes = mutableListOf<CameraStreamSize>()
        var index = 0
        while (index + 1 < size) {
            addPossibleCameraStreamSize(sizes, this[index], this[index + 1])
            index += 2
        }
        return sizes
    }

    private fun addPossibleCameraStreamSize(
        destination: MutableList<CameraStreamSize>,
        width: Long,
        height: Long
    ) {
        if (width !in 16L..20_000L || height !in 16L..20_000L) return
        val size = CameraStreamSize(width.toInt(), height.toInt())
        if (size.area < 300_000L) return
        destination += size
    }

    private fun StreamConfigurationMap.highResolutionRecordingOutputSizes(): List<Size> =
        (
            getHighResolutionOutputSizesSafely(ImageFormat.PRIVATE) +
                getHighResolutionOutputSizesSafely(ImageFormat.YUV_420_888) +
                getHighResolutionOutputSizesSafely(ImageFormat.RAW_SENSOR)
            ).distinctSortedCameraSizes()

    private fun StreamConfigurationMap.sensorBackedRecordingOutputSizes(): List<Size> =
        (
            highResolutionRecordingOutputSizes() +
                getOutputSizesSafely(ImageFormat.PRIVATE) +
                getOutputSizesSafely(ImageFormat.YUV_420_888) +
                getOutputSizesSafely(ImageFormat.RAW_SENSOR)
            ).distinctSortedCameraSizes()

    private fun StreamConfigurationMap.p010OutputSizes(): List<Size> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val p010Format = ImageFormat.YCBCR_P010
            (
                getHighResolutionOutputSizesSafely(p010Format) +
                    getOutputSizesSafely(p010Format)
                ).distinctSortedCameraSizes()
        } else {
            emptyList()
        }

    private fun List<Size>.toDistinctCameraStreamSizes(): List<CameraStreamSize> =
        map { it.toCameraStreamSize() }
            .distinctBy { "${it.width}x${it.height}" }

    private fun buildCameraCapabilityInventory(
        cameraId: String,
        characteristics: CameraCharacteristics,
        streamMaps: List<StreamConfigurationMap>,
        capabilities: IntArray
    ): CameraCapabilityInventory {
        val tonemapModes = characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)
        val supportsContrastCurve = tonemapModes.contains(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
        val rawOutputs = streamMaps.flatMap {
            it.getHighResolutionOutputSizesSafely(ImageFormat.RAW_SENSOR) +
                it.getOutputSizesSafely(ImageFormat.RAW_SENSOR)
        }
        val yuvOutputs = streamMaps.flatMap {
            it.getHighResolutionOutputSizesSafely(ImageFormat.YUV_420_888) +
                it.getOutputSizesSafely(ImageFormat.YUV_420_888)
        }
        val jpegOutputs = streamMaps.flatMap {
            it.getHighResolutionOutputSizesSafely(ImageFormat.JPEG) +
                it.getOutputSizesSafely(ImageFormat.JPEG)
        }
        val encoderOutputs = streamMaps.flatMap {
            (
                it.getHighResolutionOutputSizesSafely(ImageFormat.PRIVATE) +
                    it.getOutputSizesSafely(MediaCodec::class.java, "MediaCodec") +
                    it.getOutputSizesSafely(MediaRecorder::class.java, "MediaRecorder")
                ).distinctSortedCameraSizes()
        }
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val bypassPlan = ISPBypassRequestPlanPolicy.resolve(characteristics.toIspBypassAvailability())
        val tenBitHdrSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT) ||
                HdrDynamicRangePolicy.detectSupport(characteristics).anyHdrSupported
        } else {
            false
        }
        val oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?: IntArray(0)
        val videoStabilizationModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?: IntArray(0)

        return CameraCapabilityInventory(
            cameraId = cameraId,
            pixelArraySize = resolvePixelArraySize(characteristics)?.toCameraStreamSize(),
            activeArraySize = active?.let { CameraStreamSize(it.width(), it.height()) },
            rawOutputSizes = rawOutputs.toDistinctCameraStreamSizes(),
            yuvOutputSizes = yuvOutputs.toDistinctCameraStreamSizes(),
            jpegOutputSizes = jpegOutputs.toDistinctCameraStreamSizes(),
            encoderOutputSizes = encoderOutputs.toDistinctCameraStreamSizes(),
            supportsRaw = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            supportsTonemapContrastCurve = supportsContrastCurve,
            supportsManualSensor = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            supportsIspDisableRequestKeys = bypassPlan.supportedFeatureCount > 0,
            supportsCompleteIspBypassRequest = bypassPlan.complete,
            hardwareLevel = CameraHardwareLevelTier.fromCamera2Level(
                characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ),
            supportsPrivateReprocessing = capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING
            ),
            supportsYuvReprocessing = capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING
            ),
            supportsTenBitHdr = tenBitHdrSupported,
            supportsOis = oisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON),
            supportsVideoStabilization = videoStabilizationModes.contains(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            ),
            maxPipelineDepth = characteristics.get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH)?.toInt() ?: 0
        )
    }

    private fun rebuildCameraStreamPlans(inventory: CameraCapabilityInventory) {
        cameraStreamPlans = CinemaVideoMode.values().associateWith { mode ->
            val preferredEncoderSize = inventory.encoderOutputSizes.maxWithOrNull(
                compareBy<CameraStreamSize> { it.area }.thenBy { it.width }.thenBy { it.height }
            )
            val requestedReadoutSize = inventory.pixelArraySize ?: preferredEncoderSize
            CameraStreamPlanResolver.resolve(
                request = CameraStreamPlanRequest(
                    cinemaMode = mode,
                    preferredEncoderSize = preferredEncoderSize,
                    requestedReadoutSize = requestedReadoutSize
                ),
                capability = inventory
            )
        }
        cameraStreamPlans.forEach { (mode, plan) ->
            Log.i(TAG, "Camera contract $mode: ${plan.toLogLine()}")
        }
    }

    private fun CameraStreamPlan.toLogLine(): String =
        "tier=$readoutTier isp=$ispBypassLevel primary=${primaryReadoutSize?.compactLabel() ?: "none"} " +
            "raw=${rawSize?.compactLabel() ?: "none"} yuv=${yuvAnalysisSize?.compactLabel() ?: "none"} " +
            "encoder=${encoderSize?.compactLabel() ?: "none"} fullSensor=$truthfulFullSensorReadout " +
            "fallback=${fallbackChain.joinToString(" > ")} warning=${fallbackWarning ?: "none"} " +
            "diagnostics=${diagnostics.joinToString("; ")}"

    private fun CameraStreamSize.compactLabel(): String = "${width}x${height}"

    private fun Rect.compactBounds(): String = "$left,$top,$right,$bottom"

    private fun jpegReaderDepth(): Int = if (strictHighEndEnabled()) CameraControllerConfig.ReaderDepth.STRICT_MAX_JPEG else CameraControllerConfig.ReaderDepth.MAX_JPEG
    private fun rawReaderDepth(
        rawSize: Size,
        streamGraph: CinemaStreamGraphPlan
    ): RawSensorReaderDepthPlan =
        RawSensorReaderDepthPolicy.resolve(
            rawSize = rawSize.toCameraStreamSize(),
            recordingActive = streamGraph.mode == CinemaStreamGraphMode.RAW_ORIGINAL_RECORDING
        )
    private fun yuvReaderDepth(): Int = if (strictHighEndEnabled()) CameraControllerConfig.ReaderDepth.STRICT_MAX_YUV else CameraControllerConfig.ReaderDepth.MAX_YUV
    private fun npv10AnalysisImageFormat(): Int =
        if (npv10TenBitCaptureEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ImageFormat.YCBCR_P010
        } else {
            ImageFormat.YUV_420_888
        }
    private fun hardwareImportImageFormat(): Int =
        if (hardwareImportP010SourceEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ImageFormat.YCBCR_P010
        } else {
            ImageFormat.PRIVATE
        }

    private fun Size.normalizedForCameraOutput(): Size =
        if (width >= height) this else Size(height, width)

    private fun hardwareImportReaderDepth(): Int = if (strictHighEndEnabled()) CameraControllerConfig.ReaderDepth.STRICT_MAX_HARDWARE_IMPORT else CameraControllerConfig.ReaderDepth.MAX_HARDWARE_IMPORT
    private fun hardwareImportUsageFlags(): Long {
        return HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
            HardwareBuffer.USAGE_VIDEO_ENCODE
    }

    private fun effectiveJpegQuality(): Int {
        val minQuality = if (strictHighEndEnabled()) CameraControllerConfig.RequestTuning.STRICT_MIN_JPEG_QUALITY else 50
        return jpegQuality.coerceIn(minQuality, 100)
    }

    private fun maximumResolutionPixelModeActive(): Boolean =
        (recordingSensorPixelModeMaximumResolution && ultraHighResRawSupported) ||
            (maximumResolutionStillCaptureActive && maximumResolutionSessionPhotoSizes.isNotEmpty())

    private fun currentCinemaStreamGraphPlan(
        includeHardwareImport: Boolean = hardwareImportTargetSize != null
    ): CinemaStreamGraphPlan {
        val isolatedMaximumStill = maximumResolutionStillCaptureActive && !recordingActive
        val jpegSessionSizes = when {
            isolatedMaximumStill -> maximumResolutionSessionPhotoSizes
            recordingSensorPixelModeMaximumResolution -> maximumPixelModeConcurrentPhotoSizes
            else -> normalSessionPhotoSizes
        }.ifEmpty { photoSizes }
        val fallbackPhotoSize = jpegSessionSizes.maxByOrNull { CameraSensorInfo.sizeArea(it) }
            ?: maxPhotoSize
            ?: pixelArraySize
            ?: Size(1920, 1080)
        val encoderSizes = (
            recordingSizes +
                listOfNotNull(recordingEncoderTargetSize)
            ).distinctBy { "${it.width}x${it.height}" }
        val npv10ReadoutTargetSize = npv10CaptureTargetSize ?: analysisTargetSize
        val readoutSizes = if (npv10TenBitCaptureEnabled) {
            val p010Sizes = characteristics
                ?.let(::resolveStreamConfigurationMaps)
                ?.flatMap { it.p010OutputSizes() }
                ?.distinctSortedCameraSizes()
                .orEmpty()
            p010Sizes
        } else {
            yuvSizes
        }
        val jpegSizes = (
            jpegSessionSizes +
                listOfNotNull(fallbackPhotoSize)
            ).distinctBy { "${it.width}x${it.height}" }

        return CinemaStreamGraphPolicy.resolve(
            request = CinemaStreamGraphRequest(
                recordingActive = recordingActive,
                // A dedicated maximum-resolution idle still uses preview + JPEG only. The temporary
                // graph intentionally detaches pre-armed P010/RAW/encoder outputs so the maximum-map
                // JPEG cannot be silently substituted by the HAL.
                videoPreviewActive = videoPreviewActive || isolatedMaximumStill,
                uncompressedReadoutRecording = npv10TenBitCaptureEnabled && !isolatedMaximumStill,
                rawVideoCaptureEnabled = rawVideoCaptureEnabled,
                rawVideoPreviewActive = rawPreviewCaptureEnabled && !isolatedMaximumStill,
                recordingSafeSessionFallback = recordingSafeSessionFallback,
                hardwareImportRequested = !isolatedMaximumStill &&
                    includeHardwareImport && hardwareImportTargetSize != null,
                internalEncoderHardwareImportActive = internalRecordingHardwareImportEnabled,
                readoutSidecarMode = readoutSidecarMode,
                analysisTargetSize = (if (npv10TenBitCaptureEnabled) {
                    npv10ReadoutTargetSize
                } else {
                    analysisTargetSize
                })?.toCameraStreamSize(),
                recordingReadoutOverrideSize = recordingReadoutOverrideSize?.toCameraStreamSize(),
                recordingEncoderTargetSize = recordingEncoderTargetSize?.toCameraStreamSize(),
                previewAnalysisRequested = previewAnalysisRequested && !isolatedMaximumStill
            ),
            inventory = CinemaStreamGraphInventory(
                supportsRaw = supportsRaw,
                rawOutputSizes = rawSizes.map { it.toCameraStreamSize() },
                yuvOutputSizes = readoutSizes.map { it.toCameraStreamSize() },
                jpegOutputSizes = jpegSizes.map { it.toCameraStreamSize() },
                encoderOutputSizes = encoderSizes.map { it.toCameraStreamSize() },
                fallbackPhotoSize = fallbackPhotoSize.toCameraStreamSize()
            )
        )
    }

    // ── State ────────────────────────────────────────────────────────────
    private val stateMachine = CameraStateMachine(tag = TAG)
    override val stateFlow = MutableStateFlow<CameraState>(CameraState.Closed)
    val state: StateFlow<CameraState> = stateMachine.state
    private fun transitionTo(newState: CameraState, reason: String? = null) {
        CameraLifecycleStateBridge.apply(stateMachine, stateFlow, newState, reason)
    }

    override fun transitionCameraState(state: CameraState, reason: String?) {
        transitionTo(state, reason)
    }

    override val paramsFlow = MutableStateFlow(CameraParams())
    val params: StateFlow<CameraParams> = paramsFlow.asStateFlow()
    override val resultFlow = MutableStateFlow<LiveResult?>(null)
    val result: StateFlow<LiveResult?> = resultFlow.asStateFlow()
    private val previewFrameTimestampFlow = MutableSharedFlow<Long>(
        extraBufferCapacity = 120,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val previewFrameTimestamps: SharedFlow<Long> = previewFrameTimestampFlow
    override val frameGeometryFlow = MutableStateFlow<FrameGeometry?>(null)
    val frameGeometry: StateFlow<FrameGeometry?> = frameGeometryFlow.asStateFlow()

    override val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    // @Volatile: written on the camera background thread (onConfigured/startPreview) and read on Main
    // (e.g. updatePreview()); without it those reads can see a stale/closed session on rapid mode switch.
    @Volatile override var cameraDevice: CameraDevice? = null
    @Volatile override var captureSession: CameraCaptureSession? = null
    private val previousSessionClosedCallbacks =
        java.util.IdentityHashMap<CameraCaptureSession, MutableList<() -> Unit>>()
    private val configuredSessionSurfaces =
        java.util.IdentityHashMap<CameraCaptureSession, MutableSet<Surface>>()
    private val cameraReleaseAcknowledgements = CameraReleaseAcknowledgementGate()
    // B7: configuredSessionSurfaces and deferredReaderCloseBatches are mutated on the camera
    // background thread (session onConfigured/onClosed callbacks) but are also cleared by
    // releaseCameraResources() on the caller (ViewModel/Default) thread BEFORE stopBackground()
    // quiesces the camera thread. Guard every structural access with this lock so the
    // non-thread-safe IdentityHashMap / ArrayList cannot be read+written concurrently and tear.
    private val sessionCollectionsLock = Any()

    private var pendingSessionCreateGeneration = 0L
    private var pendingSessionCreateStartElapsedMs = 0L
    val sessionRefreshPending = AtomicBoolean(false)
    private var pendingSessionRefreshStartElapsedMs = 0L
    private data class PendingSessionRefreshRequest(
        var rebuildReaders: Boolean,
        var forceRebuild: Boolean,
        val onConfiguredCallbacks: MutableList<() -> Unit> = mutableListOf(),
        val onPreviousSessionClosedCallbacks: MutableList<() -> Unit> = mutableListOf()
    )
    // #11 fix: guards trailingSessionRefreshRequest across queueTrailingSessionRefresh /
    // runTrailingSessionRefreshIfNeeded (camera thread) and clearPendingInFlightRefresh (invoked from
    // CameraDeviceOrchestrator.closeCamera on Dispatchers.Default). Without it, clear iterating/mutating the
    // request's MutableList callbacks races the camera thread → ConcurrentModificationException / torn read
    // during teardown.
    private val trailingRefreshLock = Any()
    private var trailingSessionRefreshRequest: PendingSessionRefreshRequest? = null

    val availableFpsRanges: Array<Range<Int>> get() = fpsList
    val evDisplayRange: Range<Int> get() = evRange
    val evDisplayStepStops: Float get() = evCompensationStepStops

    override val sessionGeneration = AtomicLong(0L)
    @Volatile override var previewBuilder: CaptureRequest.Builder? = null
    private var previewBuilderHasTargets: Boolean = false

    private var pendingCameraOpenStartElapsedMs = 0L
    private val cameraCallbackLock = Any()
    private var cameraCallbacksEnabled = false
    @Volatile private var bgThread: HandlerThread? = null
    @Volatile override var backgroundHandler: Handler? = null
    private val openLock = Semaphore(1)
    override val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    override val cameraCallbackExecutor = Executor { command ->
        val posted = synchronized(cameraCallbackLock) {
            val handler = backgroundHandler
            val thread = bgThread
            if (
                !CameraLifecyclePolicy.shouldPostCameraCallback(
                    callbacksEnabled = cameraCallbacksEnabled,
                    handlerPresent = handler != null,
                    threadAlive = thread?.isAlive == true
                )
            ) {
                false
            } else {
                handler?.post(command) == true
            }
        }
        if (!posted) {
            Log.d(TAG, "Camera callback dropped after background thread stopped")
        }
    }
    
    override fun runOnCameraThread(action: () -> Unit) {
        ThreadPoolManager.cameraExecutor.execute {
            val posted = backgroundHandler?.post(action) == true
            if (!posted) {
                Log.d(TAG, "Camera executor dropped action after background handler stopped")
            }
        }
    }

    /**
     * Shutter work must outrank continuous ImageReader callbacks. RAW preview can keep the ordinary
     * camera queue permanently busy; posting at the front guarantees the one-shot request runs after
     * the current callback instead of waiting behind another full frame backlog.
     */
    private fun runPrioritizedCameraControl(action: () -> Unit) {
        val handler = backgroundHandler
        if (handler == null) {
            onStillCaptureFailed?.invoke("Camera thread is not available for photo capture")
            return
        }
        if (Looper.myLooper() === handler.looper) {
            action()
            return
        }
        if (!handler.postAtFrontOfQueue(action)) {
            onStillCaptureFailed?.invoke("Camera thread stopped before photo capture could start")
        }
    }

    override var currentCameraId: String = ""
    override var facing: Int = CameraCharacteristics.LENS_FACING_BACK
    override var characteristics: CameraCharacteristics? = null
    override var activeArray: Rect? = null
    override var currentFocalLength: Float = 0f
    override var hardwareLevel = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
    override var highEndHardwareSupported = false
    override var pixelArraySize: Size? = null
    @Volatile var sensorNoiseModel: SensorNoiseModel = SensorNoiseModel.FALLBACK
        private set
    @Volatile var physicalImagingTraits: CameraPhysicalImagingTraits = CameraPhysicalImagingTraits.DEFAULT
        private set
    @Volatile var motionTelemetry: CameraMotionTelemetry = CameraMotionTelemetry.DEFAULT
        private set
    @Volatile private var latestCaptureTimestampNs: Long = 0L
    @Volatile private var previousCaptureTimestampNs: Long = 0L
    @Volatile private var latestSensorNoiseProfilePairs: Int = 0
    @Volatile private var latestOisSampleCount: Int = 0
    val motionTelemetryRingBuffer = CameraMotionTelemetryRingBuffer(
        capacity = HardwareAbstractionLayer.OMNIPOTENT_CINEMA_ENGINE.motionTelemetryRingBufferCapacity
    )
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyroscopeSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gyroscopeTelemetryRegistered = AtomicBoolean(false)

    // ── s1-f04 / CP60: timestamp-reconciled gyro telemetry hand-off (DORMANT, DEVICE-GATED) ──────────
    // DEVICE-GATED dormant master switch: no gyro-EIS (s3-f06) / MFSR (s1-f06) consumer reads the
    // hand-off below until this is enabled after the on-device gyro↔SENSOR_TIMESTAMP clock + OIS
    // calibration. Default false ⇒ the hand-off runs for nobody and touches no pixel (no-downgrade).
    @Volatile var gyroEisTelemetryConsumerEnabled: Boolean = false
    // DEVICE-GATED measured clock offset: gyroNs = cameraSensorTimestampNs + this. 0 until calibrated
    // (correct when SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME and the gyro uses elapsedRealtimeNanos).
    @Volatile var gyroToCameraClockOffsetNs: Long = 0L
    // s3-f05 / CP60: the latest per-frame EIS orientation published by the live consumer (see
    // publishGyroEisFrameOrientation, called per frame from processResult while the consumer is enabled).
    // This is the dormant orientation feed s3-f06's (OFF-by-default) warp grid producer reads; it reaches
    // no pixel until EIS is enabled device-side. Null while the consumer is dormant.
    @Volatile private var latestEisFrameOrientation: EisFrameOrientation? = null

    // s9 / CP-QV22: the dormant custom-EIS consumer seam. Built lazily from the real camera geometry
    // (focal length px, active-array rows/centre) the first time the gated consumer runs, so on a stock
    // device (gyroEisTelemetryConsumerEnabled=false) it is NEVER instantiated — zero allocation, byte-for-
    // byte identical to today. Even when enabled its [StabilizationSessionController.mode] stays OFF (identity
    // grid) and the produced grid reaches NO pixel: there is no GPU EIS upload path (DEVICE-GATED).
    private var stabilizationSession: StabilizationSessionController? = null

    override var supportsRaw = false
    override var supportsManualSensor = false
    override var supportsOIS = false
    override var supportsVideoStabilization = false
    override var supportsHdrSceneMode = false
    override var supportsTonemapContrastCurve = false
    override var ultraHighResRawSupported = false
    override var minZoom = 1f
    override var maxZoom = 1f
    override var maxAfRegions = 0
    override var maxAeRegions = 0
    private var maxAwbRegions = 0
    override var isoRange = Range(100, 3200)
    var postRawSensitivityBoostRange = Range(100, 100)
        private set
    val effectiveIsoRange: Range<Int>
        get() = Range(
            isoRange.lower,
            EffectiveIsoRequestPolicy.effectiveUpperIso(
                sensorUpperIso = isoRange.upper,
                boostUpper = postRawSensitivityBoostRange.upper
            )
        )
    override var exposureRange = Range(1_000L, 1_000_000_000L)
    private var maxFrameDurationNs = 1_000_000_000L
    override var evRange = Range(-4, 4)
    private var evCompensationStepStops = 1f
    var manualWhiteBalanceRange: Range<Int> = Range(2_000, 12_000)
        private set
    private var supportsCctWhiteBalance = false
    // Sensor's DNG dual-illuminant color calibration, cached from CameraCharacteristics. Non-null
    // only when the sensor publishes usable calibration; drives the device-true manual-WB path
    // (DeviceTrueWhiteBalanceFlag). Null => the legacy WhiteBalanceConversion approximation is used.
    private var deviceColorCalibration: DeviceColorCalibration? = null
    // Physical camera id of the most recent capture session, so a same-sensor session rebuild (mode
    // toggle) can carry the temporal white point forward (P1.8) while a lens switch drops it.
    private var lastSessionCameraId: String? = null
    private var cachedAwbModes: IntArray = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)
    private var cachedColorCorrectionModes: IntArray = intArrayOf(
        CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY,
        CaptureRequest.COLOR_CORRECTION_MODE_FAST
    )
    private var lastLoggedHdrColorRequestSignature: String? = null
    private var lastLoggedVideoColorStabilitySignature: String? = null
    private var lastLoggedHdrMeteringFallbackSignature: String? = null
    private var lastLoggedDeviceTrueWbSignature: String? = null
    private var lastLoggedPhysicalTelemetrySignature: String? = null
    // Temporal white-point governor: consumes the per-frame color-drift budget that
    // VideoColorStabilityPolicy publishes, bounding measured AWB drift in CIE u'v'.
    private val colorDriftGovernor = ColorDriftGovernor()
    // Latest per-frame u'v' drift budget, mirrored from the active VideoColorStabilityPlan so
    // the capture-result callback can enforce it without re-resolving the whole plan. Seeded with
    // the adaptive (looser) default so the very first results before a plan is resolved are lenient.
    @Volatile
    private var activeColorDriftBudgetUv = 0.0060f
    // True while a contiguous run of budget-exceeding (clamped) frames is in progress, so the
    // governor logs once per hunting episode rather than once per frame.
    private var colorDriftClampingActive = false
    // Latest HAL-measured color-correction signals, cached from CaptureResults so the experimental
    // active override (ColorDriftEnforcementFlag) can preserve the HAL's per-frame CCM and absolute
    // gain scale rather than reconstructing them.
    @Volatile
    private var latestMeasuredColorTransform: ColorSpaceTransform? = null
    @Volatile
    private var latestMeasuredColorGains: RggbChannelVector? = null
    @Volatile
    private var latestMeasuredGreenGain: Float = 1f
    // NDR/NIVUKX red-cast probe: throttle the observe-only measured-AWB-gains diagnostic (no pixel change).
    private var lastLoggedNdrColorProbeSignature: String? = null
    // Edge-trigger for the active-override engage/disengage log.
    private var colorDriftOverrideActive = false
    // Damps the AE/AWB convergence signal so a brief reframe / AF-scan blip does not collapse the
    // SDR auto-lock (which caused the native exposure-overshoot washout + white-point skin shift).
    private val meteringHysteresis = MeteringConvergenceHysteresis()
    // #4: coalesces a burst of non-immediate updatePreview() rebuilds into one queued rebuild
    // (CoalesceRequestRebuildFlag). Cleared per session in createSession so it can never get stuck.
    private val pendingPreviewRebuild = AtomicBoolean(false)
    // RC5: defers the AWB lock across a same-sensor rebuild until genuine post-rebuild convergence.
    private val postRebuildConvergenceGate = PostRebuildConvergenceGate()
    // RC5: set in createSession, consumed when the FRESH session goes live (onConfigured) so the gate
    // is armed only once fresh-session frames are flowing — never off stale outgoing-session frames.
    private var pendingRebuildColorHoldArm = false
    override var minFocusDist = 0f
    override var fpsList: Array<Range<Int>> = emptyArray()
    private var cachedFpsIntRanges: List<CameraIntRange> = emptyList()
    private var cachedAntibandingModes: IntArray = IntArray(0)
    private var cachedStreamBasedFpsEntries: List<CameraStreamBasedFpsEntry> = emptyList()
    private var cachedCaptureRequestKeysByName: Map<String, CaptureRequest.Key<*>> = emptyMap()
    private var cachedSessionRequestKeysByName: Map<String, CaptureRequest.Key<*>> = emptyMap()
    private val disabledStreamFpsKeySignatures = mutableSetOf<String>()
    private var lastLoggedStreamBasedFpsOverrideSignature: String? = null
    @Volatile
    private var lastAppliedFpsRequestPlan: CameraFpsRequestPlan? = null
    @Volatile
    private var lastAppliedStreamBasedFpsOverridePlan: CameraStreamBasedFpsOverridePlan? = null
    private var targetCameraFps = 60
    private var requestedCameraFpsHint: Int? = null
    // X3/R3: targetCameraFps is the USER-selected RECORD fps. previewCadenceFps pins idle/armed
    // preview to 25fps in ALL modes so 4K pre-arm does not switch sensor cadence at record start.
    // CameraPreviewFpsPolicy snaps 25 to the nearest available device range when needed.
    private val previewCadenceFps = 25
    override var photoSizes: List<Size> = emptyList()
    override var maxPhotoSize: Size? = null
    private var normalSessionPhotoSizes: List<Size> = emptyList()
    private var maximumResolutionSessionPhotoSizes: List<Size> = emptyList()
    private var maximumPixelModeConcurrentPhotoSizes: List<Size> = emptyList()
    override var previewSizes: List<Size> = emptyList()
    override var recordingSizes: List<Size> = emptyList()
    override var videoSizes: List<Size> = emptyList()
    override var rawSizes: List<Size> = emptyList()
    override var maxRawSize: Size? = null
    // Distinct RAW size tiers for the RAW capture-resolution policy: the largest un-binned
    // MAXIMUM_RESOLUTION RAW size (from the high-res config map; null when none exposed) and the
    // largest default active-array RAW size (used for the FULL_ACTIVE_ARRAY fallback).
    // See RawSensorMaxResCapabilityPolicy / RawCaptureResolutionModePolicy.
    override var maxResolutionRawSize: Size? = null
    override var activeArrayRawSize: Size? = null
    private var yuvSizes: List<Size> = emptyList()
    override var maxYuvSize: Size? = null
    
    // Phase 1: Sensor readout policy (maximum megapixel unlock)
    private var sensorReadoutPolicy: SensorReadoutPolicy? = null
    
    // Phase 1b: ISP bypass policy (linear tonemap + all ISP off)
    private var ispBypassPolicy: ISPBypassPolicy? = null
    private var loggedIspBypassRequestApplication = false
    private var loggedStabilizationResolution = false
    private var stabilizationCapabilitySummary: String = ""
    // CP17: observe-only ISP-bypass read-back verification state. lastRequestedIspBypassModes is the
    // final requested ISP-block set snapshotted from the built request; processResult() compares it
    // to the device's TotalCaptureResult and records the honest verdict. Never mutates the request.
    @Volatile
    private var lastRequestedIspBypassModes: IspBypassRequestedModes? = null
    @Volatile
    private var lastIspBypassVerification: IspBypassVerificationReport? = null
    private var loggedIspBypassVerification = false
    // Frames observed since the active ISP-bypass request was (re)built. The result stream lags the
    // request by a few frames, so verification waits for the HAL to settle; combined with the
    // once-per-request log guard this bounds read-back cost to a single verification per request.
    private var ispBypassReadbackFramesObserved = 0
    @Volatile
    private var appliedProcessedVideoQualityEvidence: Map<String, String> = emptyMap()

    private var cameraCapabilityInventory: CameraCapabilityInventory? = null
    private var cameraStreamPlans: Map<CinemaVideoMode, CameraStreamPlan> = emptyMap()
    private var cameraBypassCapabilityReport: CameraBypassCapabilityReport? = null
    private var cameraVendorReadoutScanReport: CameraVendorReadoutScanReport = CameraVendorReadoutScanReport.EMPTY
    
    // Phase 1c: Bit-depth precision policy (10-bit/12-bit validation)
    private var bitDepthPrecisionPolicy: BitDepthPrecisionPolicy? = null
    private var hdrDynamicRangeMode = CameraInitialDynamicRangeModePolicy.defaultMode()
    private var hdrSupport = HdrDynamicRangePolicy.Support.NONE
    private var lastAppliedPreviewHdrProfile: Long? = null
    private var lastAppliedEncoderHdrProfile: Long? = null

    // s1-f05: honest record-path frame-delivery accounting (observe-only). Fed from the shared
    // capture callback below; reconfigure transitions are bracketed in refreshOutputsAndSession /
    // the session StateCallback so a drop during a rebuild is bounded-and-reported, never silent.
    private val recordFrameDeliveryLedger = CameraRecordFrameDeliveryLedger()
    private val previewHealthMonitor = PreviewHealthMonitor(tag = TAG)

    @set:JvmName("applyPreviewSurfaceField")
    @get:JvmName("readPreviewSurfaceField")
    var previewSurface: Surface? = null
    @set:JvmName("applyRecordSurfaceField")
    var recordSurface: Surface? = null
    override var jpegReader: ImageReader? = null
    override var rawReader: ImageReader? = null
    override var yuvReader: ImageReader? = null
    override var hardwareImportReader: ImageReader? = null

    private data class ReaderSet(
        val jpeg: ImageReader?,
        val raw: ImageReader?,
        val yuv: ImageReader?,
        val hardwareImport: ImageReader?
    ) {
        val hasReaders: Boolean
            get() = jpeg != null || raw != null || yuv != null || hardwareImport != null
    }

    private val deferredReaderCloseBatches = mutableListOf<ReaderSet>()

    override var stillCaptureJpegEnabled = false
    override var stillCaptureRawEnabled = false

    private var manIso = -1
    override var manualIso: Int get() = manIso; set(v) { manIso = v }
    private var manExpNs = -1L
    override var manualExposureNs: Long get() = manExpNs; set(v) { manExpNs = v }
    private var manFocusDist = -1f
    override var manualFocusDist: Float get() = manFocusDist; set(v) { manFocusDist = v }
    // s5-f07 / CP-F8: cinema focus-pull control. Dormant unless CinematicFocusPullFlag is ON; with
    // the flag OFF the manual-lens path below is byte-for-byte today. s5-f08 wires its
    // AIFocusDistanceController pull stepper into this same control behind the same flag
    // (see beginCinematicStylePull, which rebuilds it with AICinematicPullStepper for a named style).
    private var cinematicFocusPull = CinematicFocusPullControl()
    // CP-QV20: bridge that lets the in-process DebugCommandReceiver reach this live camera's gated
    // focus-pull methods (mirrors the flag/registerOnChanged pattern). Every method it forwards is
    // itself gated behind CinematicFocusPullFlag, so it is a no-op when the flag is OFF.
    private val cinematicFocusPullDebugHandler = object : CinematicFocusPullDebugCommands.Handler {
        override fun setCinematicFocusPullMark(isA: Boolean, diopters: Float) =
            this@CameraController.setCinematicFocusPullMark(isA, diopters)

        override fun configureCinematicFocusPull(
            curve: CinematicFocusPullControl.EasingCurve,
            durationMs: Long
        ) = this@CameraController.configureCinematicFocusPull(curve, durationMs)

        override fun triggerCinematicFocusPull(): Boolean =
            this@CameraController.triggerCinematicFocusPull()
    }
    private var manWbK = -1
    private var manWbTint = 0
    override var manualWbKelvin: Int get() = manWbK; set(v) { manWbK = v }
    override var flashMode = FlashMode.OFF
    override var evIndex = 0
    // Neutral (EV=0) ISO baseline for LOG/RAW cinematic auto-exposure. With AE OFF the reported
    // SENSOR_SENSITIVITY reads back what we last set, so EV must be computed from a frozen neutral —
    // captured once at activation — instead of that feedback value, or successive EV changes compound
    // and EV=0 never restores the metered look. Reset to -1 whenever cinematic auto-exposure is inactive.
    private var cinemaEvNeutralBaselineIso = -1
    @set:JvmName("applyThermalResourceCeilingField")
    var thermalResourceCeiling = 1.0f
    @set:JvmName("applyStabilizationModeField")
    var stabilizationMode = StabilizationMode.OIS
    override var zoomRatio = 1f
    override var targetOutputAspectRatio: Float? = null
        set(value) {
            if (field == value || (field != null && value != null && abs(field!! - value) < 0.0001f)) return
            field = value
            refreshFrameGeometry()
        }
    @set:JvmName("applyDeviceRotationDegreesField")
    var deviceRotationDegrees = 0
        set(value) {
            field = ((value % 360) + 360) % 360
            refreshFrameGeometry()
        }
    override var aiDrivenFocusDist = -1f
    @set:JvmName("applyNoiseReductionField")
    var noiseReductionEnabled = true
    private var selectedVideoDetailProfile: VideoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL
    @Volatile
    private var processedVideoMotionRisk = 0f
    @Volatile
    private var processedVideoNoiseRisk = 0f
    @Volatile
    private var extremeDetailFallbackExposureRequestApplied = false
    @Volatile
    private var lastExtremeDetailFallbackExposureLogSignature: String? = null
    // NDR10+/NIVUKX closed-loop anti-motion-blur shutter cap state (see NdrAntiMotionBlurShutterPolicy).
    // `engaged` is the applied state; `capNs`/`isoFloor` are cached from the last request build so the
    // capture-result feedback loop can cheaply decide whether the engage state should flip.
    @Volatile
    private var hdrAntiBlurEngaged = false
    @Volatile
    private var hdrAntiBlurCapNs = 0L
    @Volatile
    private var hdrAntiBlurIsoFloor = 0
    @Volatile
    private var lastHdrAntiBlurLogSignature: String? = null
    // Closed-loop ADAPTIVE highlight protection (Fix B). Smoothed extra negative-EV stops added on top
    // of the -0.67 floor, driven by the live scene avg-luma + highlight-clip fraction pushed from the
    // analysis stream via [setHdrExposureSceneMetrics]. Only ever <= 0 (darker to save highlights).
    @Volatile
    private var hdrAdaptiveHighlightExtraStops = 0f
    // Last extra-stops value that actually reached a built request, tracked so we only rebuild the
    // capture request when the resulting compensation index would truly change (avoids per-frame churn).
    @Volatile
    private var hdrAdaptiveHighlightAppliedStops = 0f
    // Timestamp of the last real analysis frame. If the optional meter surface is rejected or stalls,
    // request assembly uses the conservative no-analysis highlight fallback instead of silently losing
    // NDR exposure protection.
    @Volatile
    private var lastHdrExposureMetricsElapsedMs = 0L
    @Volatile
    private var lastRequestedNdrExposureCompensationIndex: Int? = null
    @Volatile
    private var lastHdrExposureReadbackElapsedMs = 0L
    // Throttled evidence that the NDR YUV analysis loop is receiving both luminance-highlight and
    // reconstructed-red-highlight data.
    @Volatile
    private var lastHdrExposureEvidenceElapsedMs = 0L
    @set:JvmName("applyFaceDetectionField")
    var faceDetectionEnabled = false
    override var aeAwbLocked = false
    @Volatile
    private var tapFocusMeteringUnlockUntilElapsedMs = 0L
    @set:JvmName("applyJpegQualityField")
    var jpegQuality = 100
    @set:JvmName("applyRawVideoCaptureField")
    var rawVideoCaptureEnabled = false
    @set:JvmName("applyNpv10TenBitCaptureField")
    var npv10TenBitCaptureEnabled = false
    private var analysisTargetSize: Size? = null
    private var npv10CaptureTargetSize: Size? = null
    private var recordingReadoutOverrideSize: Size? = null
    private var recordingEncoderTargetSize: Size? = null
    // MAXIMUM_SENSOR RAW tier: when true (and the device exposes it), the RAW record request engages
    // SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION for the un-binned native readout. Cleared on fallback / stop.
    private var recordingSensorPixelModeMaximumResolution = false
    @Volatile
    private var maximumResolutionStillCaptureActive = false
    private val stillCaptureRequestTag = Any()
    private var resumeRepeatingAfterStillCapture = false
    // RAW cinema-preview debayer feed: keep the RAW_SENSOR reader in-session + repeating-request target
    // during VIDEO+RAW preview (not recording) so the GPU cinema-debayer produces a genuine sensor-RAW
    // monitor image. Independent of rawVideoCaptureEnabled (never implies a RAW recording session).
    private var rawPreviewCaptureEnabled = false
    private var internalRecordingHardwareImportEnabled = false
    private var hardwareImportP010SourceEnabled = false
    private var previewContinuityRecordSurfaceEnabled = false
    private var unconfiguredRecordSurfaceRecoveryPending = false
    private var readoutSidecarMode: CameraReadoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
    override var previewLogLookEnabled = false
    override var previewHdrLookEnabled = false
    override var previewRawLookEnabled = false
    private var videoPreviewActive = true
    // Gated CPU-analysis (YUV) stream request for the HUD monitors. The default video-preview graph
    // omits the analysis stream (recording-safe / WYSIWYG parity); this flag re-adds it while a
    // monitor is enabled so histogram/waveform/focus-peaking/zebra actually receive frames.
    @Volatile private var previewAnalysisRequested = false
    // s5-f10 / CP-F11: task-aware lens-selection hint, consumed only at camera-open / mode-switch by
    // selectCamera() via CameraLensSelectionPolicy.selectForTask. DORMANT by default: None + the
    // CameraLensSelectionTaskFlag gate OFF -> selectForTask returns exactly selectPreferredCandidate
    // (today's opened lens, bit-for-bit). @Volatile because a UI/scene caller may set it off the camera
    // thread while selectCamera() reads it on the camera thread.
    @Volatile
    private var lensSelectionTaskHint: LensSelectionTaskHint = LensSelectionTaskHint.None
    // @Volatile: written off the camera thread (Dispatchers.IO/Default via the ViewModel) and read
    // on the camera-callback executor by the s1-f05 record-frame ledger wiring, matching every other
    // cross-thread field in this class. Establishes the happens-before edge for those reads.
    @Volatile
    private var recordingActive = false
    @set:JvmName("applyCinemaFidelityOverridesField")
    var cinemaFidelityOverridesEnabled = false

    override var onYuvFrame: ((ImageReader) -> Unit)? = null
    override var onHardwareFrame: ((ImageReader) -> Unit)? = null
    override var onJpegReady: ((ImageReader) -> Unit)? = null
    override var onRawReady: ((ImageReader) -> Unit)? = null
    override var onCaptureResult: ((TotalCaptureResult) -> Unit)? = null
    override var onStillCaptureFailed: ((String) -> Unit)? = null
    override var onCameraPipelineWarning: ((String) -> Unit)? = null
    var awaitFramePipelineIdleBeforeReaderClose: ((Long) -> Boolean)? = null

    override var previewLogToneCurve: TonemapCurve? = null
    override var previewHdrToneCurve: TonemapCurve? = null
    override var previewRawToneCurve: TonemapCurve? = null
    override var recordingLogToneCurve: TonemapCurve? = null
    override var recordingHdrToneCurve: TonemapCurve? = null
    override var recordingRawToneCurve: TonemapCurve? = null

    override var persistentAfRegion: Rect? = null
    override var persistentAeRegion: Rect? = null
    private var persistentAwbRegion: Rect? = null
    private var lastAeMeteringUpdateElapsedMs = 0L

    override var lastOpenReadyCallback: (() -> Unit)? = null
    override var lastOpenErrorCallback: ((String) -> Unit)? = null
    override var initializationAttempts: Int = 0
    override var reopenAttempts: Int = 0
    @Volatile override var sessionRecoveryPending: Boolean = false
    var lastKnownLocation: Location? = null
    @Volatile override var recordingSafeSessionFallback: Boolean = false
    @Volatile override var conservativeSessionFallback: Boolean = false
    
    @set:JvmName("applyHardwareImportTargetSizeField")
    var hardwareImportTargetSize: Size? = null
    private val refreshBatchDepth = AtomicInteger(0)
    private val refreshBatchLock = Any()
    private var batchedSessionRefreshRequest: PendingSessionRefreshRequest? = null

    val activeProfileName: String
        get() = HardwareAbstractionLayer.activeProfileName().takeIf { it != "NONE" }
            ?: cameraBypassCapabilityReport?.halProfile?.badgeLabel
            ?: "HAL ${hardwareLevelLabel(hardwareLevel)}"
    val cameraCaptureToVulkanIsolationSignature: String
        get() = "capture-thread=CameraBackground|vulkan-thread=native-isolated|jni=no-blocking-frame-copy|metadata=volatile-snapshot|ui=decoupled|haptics=decoupled|storage=decoupled"
    val omnipotentPipelineMetadataSignature: String
        get() = "noise=SENSOR_NOISE_PROFILE|motion=STATISTICS_OIS_SAMPLES|gyro=Sensor.TYPE_GYROSCOPE|color=DynamicAcesIdtCalibration|lens=physicalImagingTraits|metadata=volatile-snapshot"
    val gyroTelemetryHandoffSignature: String
        get() = "consumer=DORMANT|gated=gyroEisTelemetryConsumerEnabled|" +
            "clock=gyroNs=cameraNs+gyroToCameraClockOffsetNs|domain=GYRO-only|" +
            "reconciler=GyroExposureWindowReconciler|mfsr=GyroGlobalMotionTerm|health=GyroRingHealth|pixels=untouched"
    val captureResult: LiveResult? get() = resultFlow.value
    val outputAspectRatio: Float? get() = targetOutputAspectRatio
    fun sensorBaseAspectRatio(): Float = CameraSensorInfo.sensorBaseAspectRatio(fullSensorSize())
    fun outputJpegOrientation(): Int =
        OrientationManager.getJpegOrientation(sensorOrientation(), deviceRotationDegrees, facing)

    fun singularityCaptureStage2Metadata(): Map<String, String> =
        runCatching {
            val streamGraph = currentCinemaStreamGraphPlan()
            val requestPlan = resolveCamera2PipelineRequestPlan()
            val chars = characteristics
            val live = resultFlow.value
            val params = paramsFlow.value
            val activeBounds = activeArray?.compactBounds().orEmpty()
            val blackLevelPattern =
                chars?.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val blackLevelSummary = blackLevelPattern?.let { pattern ->
                listOf(
                    pattern.getOffsetForIndex(0, 0),
                    pattern.getOffsetForIndex(1, 0),
                    pattern.getOffsetForIndex(0, 1),
                    pattern.getOffsetForIndex(1, 1)
                ).joinToString(separator = ",")
            }.orEmpty()
            val whiteLevel = chars?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 0
            val bayerPattern = chars
                ?.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                ?.let { "camera2_cfa_$it" }
                .orEmpty()
            val hotPixelModes = chars
                ?.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
                ?: IntArray(0)
            val lensShadingModes = chars
                ?.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                ?: IntArray(0)
            val motionSamples = motionTelemetryRingBuffer.snapshotNewestFirst(32)
            val imuStart = motionSamples.minOfOrNull { it.timestampNs } ?: 0L
            val imuEnd = motionSamples.maxOfOrNull { it.timestampNs } ?: 0L
            val capabilitySnapshot = buildString {
                append("hardware=").append(hardwareLevelLabel(hardwareLevel))
                append(";raw=").append(supportsRaw)
                append(";manual=").append(supportsManualSensor)
                append(";ois=").append(supportsOIS)
                append(";videoStabilization=").append(supportsVideoStabilization)
                append(";tenBitHdr=").append(cameraCapabilityInventory?.supportsTenBitHdr ?: false)
                append(";rawSizes=").append(rawSizes.size)
                append(";yuvSizes=").append(yuvSizes.size)
                append(";recordingSizes=").append(recordingSizes.size)
            }
            val calibrationSignals = listOf(
                blackLevelPattern != null,
                whiteLevel > 0,
                bayerPattern.isNotBlank(),
                activeBounds.isNotBlank(),
                latestSensorNoiseProfilePairs > 0
            )
            val calibrationConfidence =
                calibrationSignals.count { it }.toDouble() / calibrationSignals.size.toDouble()
            buildMap {
                put("singularityCameraId", currentCameraId)
                put("singularityCameraSessionGeneration", sessionGeneration.get().toString())
                put(
                    "singularityFrameIdentity",
                    "camera=$currentCameraId;timestampNs=${live?.timestampNs ?: latestCaptureTimestampNs}"
                )
                put(
                    "singularitySessionIdentity",
                    "camera=$currentCameraId;sessionGeneration=${sessionGeneration.get()}"
                )
                put(
                    "singularityRecordingIntent",
                    "recording=$recordingActive;raw=$rawVideoCaptureEnabled;videoPreview=$videoPreviewActive"
                )
                put("singularityDeviceCapabilitySnapshot", capabilitySnapshot)
                put(
                    "singularityPhysicalCameraId",
                    chars?.physicalCameraIds?.sorted()?.joinToString(separator = ",").orEmpty()
                )
                put("singularityPhysicalCameraStreamProven", "false")
                put(
                    "singularityStreamCombinationValidated",
                    (streamGraph.mode != CinemaStreamGraphMode.UNSUPPORTED).toString()
                )
                put(
                    "singularityStreamCombinationUnsupported",
                    (streamGraph.mode == CinemaStreamGraphMode.UNSUPPORTED).toString()
                )
                put("singularityStreamGraphMode", streamGraph.mode.name)
                put("singularityStreamGraphDiagnostics", streamGraph.diagnostics.joinToString(separator = "|"))
                put("singularitySensorRequestValidated", (previewBuilder != null).toString())
                put("singularityRequestTemplate", requestPlan.template.toString())
                put("singularityRequestTargets", requestPlan.targets.toString())
                put("singularityCaptureIntent", requestPlan.captureIntent.toString())
                put("singularityManualIso", params.iso.toString())
                put("singularityManualExposureNs", params.exposureNs.toString())
                put("singularityManualWhiteBalanceKelvin", params.wbKelvin.toString())
                put("singularityManualFocusDistanceDiopters", params.focusDist.toString())
                put(
                    "singularityFrameDurationNs",
                    if (params.fps > 0) (1_000_000_000L / params.fps.toLong()).toString() else "0"
                )
                put("singularityRequestedFps", params.fps.toString())
                put("singularityCaptureTimestampNs", (live?.timestampNs ?: latestCaptureTimestampNs).toString())
                put("singularityPreviousCaptureTimestampNs", previousCaptureTimestampNs.toString())
                put("singularityImuSampleCount", maxOf(motionSamples.size, latestOisSampleCount).toString())
                put("singularityImuWindowStartNs", imuStart.toString())
                put("singularityImuWindowEndNs", imuEnd.toString())
                put(
                    "singularityRollingShutterSkewNs",
                    physicalImagingTraits.rollingShutterSkewNs.coerceAtLeast(0L).toString()
                )
                put("singularityCaptureResultAvailable", (live != null).toString())
                put("singularitySensorSensitivityIso", (live?.iso ?: 0).toString())
                put("singularityExposureTimeNs", (live?.exposureNs ?: 0L).toString())
                put(
                    "singularityFocusDistanceDiopters",
                    (live?.focusDist ?: physicalImagingTraits.focusDistanceDiopters).toString()
                )
                put("singularityBlackLevelAvailable", (blackLevelPattern != null).toString())
                put("singularityBlackLevelPattern", blackLevelSummary)
                put("singularityWhiteLevel", whiteLevel.toString())
                put("singularityBayerPattern", bayerPattern)
                put("singularityActiveArray", activeBounds)
                put("singularityCropRegion", activeBounds)
                put("singularityOpticalBlackCorrectionProven", (blackLevelPattern != null).toString())
                put("singularityFixedPatternNoiseProfile", sensorNoiseModel.signature)
                put("singularityHotPixelMapAvailable", hotPixelModes.isNotEmpty().toString())
                put(
                    "singularitySensorDefectRepairProven",
                    hotPixelModes.contains(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY).toString()
                )
                put("singularityLensShadingMapAvailable", lensShadingModes.isNotEmpty().toString())
                put(
                    "singularityVignettingCorrectionProven",
                    lensShadingModes.contains(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON).toString()
                )
                put("singularityRowColumnBandingModel", "camera2_sensor_noise_profile")
                put("singularitySensorNoiseProfilePairs", latestSensorNoiseProfilePairs.toString())
                put("singularityWhiteSaturationTagged", (whiteLevel > 0).toString())
                put("singularityCalibrationConfidence", String.format(Locale.US, "%.3f", calibrationConfidence))
                put(
                    "singularityCalibratedSensorOutput",
                    if (calibrationConfidence > 0.0) "camera2_calibrated_sensor_metadata" else ""
                )
            }
        }.getOrElse { throwable ->
            mapOf(
                "singularityCaptureEvidenceError" to
                    (throwable.message ?: throwable.javaClass.simpleName)
            )
        }

    // ── Implementation ───────────────────────────────────────────────────

    fun startBackground() {
        synchronized(cameraCallbackLock) {
            if (backgroundHandler != null && bgThread?.isAlive == true) {
                cameraCallbacksEnabled = true
                return
            }
            cameraCallbacksEnabled = false
            val thread = HandlerThread("CameraBackground", Process.THREAD_PRIORITY_URGENT_AUDIO).also { it.start() }
            bgThread = thread
            backgroundHandler = Handler(thread.looper)
            cameraCallbacksEnabled = true
        }
        startMotionTelemetry()
    }

    fun stopBackground() {
        stopMotionTelemetry()
        stopPreviewWatchdog()
        val thread = synchronized(cameraCallbackLock) {
            cameraCallbacksEnabled = false
            val thread = bgThread
            bgThread = null
            backgroundHandler = null
            thread
        }
        thread?.quitSafely()
        try {
            thread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "stopBackground interrupted", e)
            Thread.currentThread().interrupt()
        }
    }

    override fun setupReaders() {
        val handler = backgroundHandler ?: run {
            Log.w(TAG, "setupReaders skipped: background handler unavailable")
            return
        }
        val streamGraph = currentCinemaStreamGraphPlan()

        val oldJpeg = jpegReader
        val oldRaw = rawReader
        val oldYuv = yuvReader
        val oldHw = hardwareImportReader

        val targetJpegSize = streamGraph.jpegStill.size?.takeIf { streamGraph.jpegStill.createReader }?.toAndroidSize()
        val keepJpeg = targetJpegSize != null &&
                oldJpeg != null &&
                oldJpeg.width == targetJpegSize.width &&
                oldJpeg.height == targetJpegSize.height &&
                oldJpeg.imageFormat == ImageFormat.JPEG &&
                oldJpeg.maxImages == jpegReaderDepth()

        val targetRawSize = streamGraph.rawSensor.size?.takeIf { streamGraph.rawSensor.createReader }?.toAndroidSize()
        val targetRawDepth = targetRawSize?.let { rawReaderDepth(it, streamGraph).maxImages } ?: 0
        val keepRaw = targetRawSize != null &&
                oldRaw != null &&
                oldRaw.width == targetRawSize.width &&
                oldRaw.height == targetRawSize.height &&
                oldRaw.imageFormat == ImageFormat.RAW_SENSOR &&
                oldRaw.maxImages == targetRawDepth

        val targetYuvSize = streamGraph.yuvAnalysis.size?.takeIf { streamGraph.yuvAnalysis.createReader }?.toAndroidSize()
        val targetYuvFormat = npv10AnalysisImageFormat()
        val targetYuvDepth = targetYuvSize?.let { size ->
            if (targetYuvFormat == ImageFormat.YCBCR_P010) {
                Npv10InFlightBufferPolicy.readerMaxImages(size.width, size.height)
            } else {
                yuvReaderDepth()
            }
        } ?: 0
        val keepYuv = targetYuvSize != null &&
                oldYuv != null &&
                oldYuv.width == targetYuvSize.width &&
                oldYuv.height == targetYuvSize.height &&
                oldYuv.imageFormat == targetYuvFormat &&
                oldYuv.maxImages == targetYuvDepth

        val targetHwSize = hardwareImportTargetSize?.takeIf { streamGraph.hardwareImport.createReader }
        val targetHwFormat = hardwareImportImageFormat()
        val targetHwDepth = hardwareImportReaderDepth()
        val keepHw = targetHwSize != null &&
                oldHw != null &&
                oldHw.width == targetHwSize.width &&
                oldHw.height == targetHwSize.height &&
                oldHw.imageFormat == targetHwFormat &&
                oldHw.maxImages == targetHwDepth

        if (keepJpeg || keepRaw || keepYuv || keepHw) {
            Log.i(
                TAG,
                "setupReaders: reusing matched ImageReaders " +
                    "(jpeg=$keepJpeg, raw=$keepRaw, yuv=$keepYuv, hw=$keepHw) " +
                    "to prevent recreation stutters"
            )
        }

        val readersToClose = ReaderSet(
            jpeg = if (keepJpeg) null else oldJpeg,
            raw = if (keepRaw) null else oldRaw,
            yuv = if (keepYuv) null else oldYuv,
            hardwareImport = if (keepHw) null else oldHw
        )
        if (!keepJpeg && !keepRaw && !keepYuv && !keepHw) {
            deferCurrentReadersUntilSessionClose()
        } else {
            deferCurrentReadersUntilSessionClose(readersToClose)
        }

        jpegReader = if (keepJpeg) oldJpeg else null
        rawReader = if (keepRaw) oldRaw else null
        yuvReader = if (keepYuv) oldYuv else null
        hardwareImportReader = if (keepHw) oldHw else null

        try {
            if (jpegReader == null && targetJpegSize != null) {
                jpegReader = ImageReader.newInstance(
                    targetJpegSize.width, targetJpegSize.height, ImageFormat.JPEG, jpegReaderDepth()
                ).apply { setOnImageAvailableListener(::dispatchJpegReady, handler) }
            }
            if (rawReader == null && targetRawSize != null) {
                rawReader = ImageReader.newInstance(
                    targetRawSize.width, targetRawSize.height, ImageFormat.RAW_SENSOR, targetRawDepth
                ).apply { setOnImageAvailableListener({ onRawReady?.invoke(it) }, handler) }
                Log.i(
                    TAG,
                    "RAW_SENSOR reader ${targetRawSize.width}x${targetRawSize.height} " +
                        "depth=${targetRawDepth} " +
                        "estimatedReaderMiB=${rawReaderDepth(targetRawSize, streamGraph).estimatedReaderBytes / (1024L * 1024L)} " +
                        "reason=${rawReaderDepth(targetRawSize, streamGraph).reason}"
                )
            }
            if (yuvReader == null && targetYuvSize != null) {
                yuvReader = ImageReader.newInstance(
                    targetYuvSize.width, targetYuvSize.height, targetYuvFormat, targetYuvDepth
                ).apply {
                    setOnImageAvailableListener(
                        {
                            previewHealthMonitor.trackYuvFrame()
                            onYuvFrame?.invoke(it)
                        },
                        handler
                    )
                }
                Log.i(
                    TAG,
                    "Analysis reader ${targetYuvSize.width}x${targetYuvSize.height} format=$targetYuvFormat " +
                        "depth=$targetYuvDepth npv10TenBit=$npv10TenBitCaptureEnabled"
                )
            }
            if (hardwareImportReader == null && targetHwSize != null) {
                val hardwareImportFormat = targetHwFormat
                val hardwareDepth = targetHwDepth
                hardwareImportReader = ImageReader.newInstance(
                    targetHwSize.width, targetHwSize.height, hardwareImportFormat, hardwareDepth,
                    hardwareImportUsageFlags()
                ).apply {
                    setOnImageAvailableListener(
                        {
                            previewHealthMonitor.trackYuvFrame()
                            onHardwareFrame?.invoke(it)
                        },
                        handler
                    )
                }
                Log.i(
                    TAG,
                    "Hardware import reader ${targetHwSize.width}x${targetHwSize.height} " +
                        "format=$targetHwFormat depth=$targetHwDepth " +
                        "p010Source=$hardwareImportP010SourceEnabled"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup readers, cleaning up partially created resources", e)
            val partial = listOf(jpegReader, rawReader, yuvReader, hardwareImportReader)
            jpegReader = null
            rawReader = null
            yuvReader = null
            hardwareImportReader = null
            partial.forEach { r ->
                try {
                    r?.close()
                } catch (ignored: Exception) {
                    Log.w(TAG, "Partial reader close failed during setup cleanup", ignored)
                }
            }
            throw e
        }

        Log.i(
            TAG,
            "Cinema stream graph readers mode=${streamGraph.mode} " +
                "jpeg=${streamGraph.jpegStill.size?.compactLabel() ?: "off"} " +
                "raw=${streamGraph.rawSensor.size?.compactLabel() ?: "off"} " +
                "yuv=${streamGraph.yuvAnalysis.size?.compactLabel() ?: "off"} " +
                "encoder=${streamGraph.encoder.size?.compactLabel() ?: "off"}"
        )
    }

    private fun currentReaderSet(): ReaderSet =
        ReaderSet(
            jpeg = jpegReader,
            raw = rawReader,
            yuv = yuvReader,
            hardwareImport = hardwareImportReader
        )

    private fun deferCurrentReadersUntilSessionClose(readers: ReaderSet = currentReaderSet()) {
        if (!readers.hasReaders) return
        if (captureSession == null) {
            awaitFramePipelineIdleForReaderClose("no active session")
            closeReaderSetSafely(readers)
        } else {
            synchronized(sessionCollectionsLock) { deferredReaderCloseBatches += readers }
            Log.d(
                TAG,
                "Deferred ImageReader close until previous Camera2 session closes"
            )
        }
    }

    private fun closeDeferredReadersSafely(reason: String) {
        val batches = synchronized(sessionCollectionsLock) {
            if (deferredReaderCloseBatches.isEmpty()) return
            deferredReaderCloseBatches.toList().also { deferredReaderCloseBatches.clear() }
        }
        awaitFramePipelineIdleForReaderClose(reason)
        batches.forEach(::closeReaderSetSafely)
        Log.d(TAG, "Closed deferred ImageReaders: $reason")
    }

    private fun awaitFramePipelineIdleForReaderClose(reason: String) {
        val callback = awaitFramePipelineIdleBeforeReaderClose ?: return
        val idle = runCatching { callback(READER_CLOSE_FRAME_PIPELINE_IDLE_TIMEOUT_MS) }
            .getOrDefault(false)
        if (!idle) {
            Log.w(TAG, "Closing ImageReaders before FramePipeline fully idle: $reason")
        }
    }

    private fun closeReaderSetSafely(readers: ReaderSet) {
        closeReaderSafely(readers.jpeg)
        closeReaderSafely(readers.raw)
        closeReaderSafely(readers.yuv)
        closeReaderSafely(readers.hardwareImport)
    }

    private fun closeReaderSafely(reader: ImageReader?) {
        try { reader?.close() } catch (e: Exception) { Log.w(TAG, "Reader close failed", e) }
    }

    fun openCamera(onReady: () -> Unit, onError: (String) -> Unit) {
        CameraDeviceOrchestrator(this, openLock).openCamera(onReady, onError)
    }

    fun closeCamera() {
        CameraDeviceOrchestrator(this, openLock).closeCamera()
    }

    private fun continuousAutoFocusMode(): Int {
        val modes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return CaptureRequest.CONTROL_AF_MODE_OFF
        return CameraFocusControl.selectAvailableContinuousAutoFocusMode(
            availableModes = modes,
            isRecording = recordingActive || videoPreviewActive
        )
    }

    private fun singleShotAutoFocusMode(): Int {
        val modes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: return continuousAutoFocusMode()
        // s5-f01 / CP-F2: preference table (AUTO -> MACRO -> continuous) lives in the pure,
        // golden-locked CameraFocusControl selector; this is an extract-only delegation with no
        // behavior change.
        return CameraFocusControl.selectSingleShotAutoFocusMode(
            availableModes = modes,
            isRecording = recordingActive || videoPreviewActive
        )
    }

    private fun updatePreview(immediate: Boolean = false) {
        val sess = captureSession ?: return
        val builder = previewBuilder ?: return
        if (!previewBuilderHasTargets) {
            Log.d(TAG, "updatePreview skipped: preview request has no active surface target")
            return
        }
        val generation = sessionGeneration.get()
        // #4 (reversible, default ON): collapse a burst of non-immediate rebuilds (a pinch/EV/WB drag
        // fires dozens of setter->updatePreview calls) into a single queued rebuild. applyAll reads the
        // CURRENT mutable state at exec time, so the coalesced final request is byte-identical. The flag
        // is cleared at the START of the runnable below, so a setter firing mid-rebuild re-arms exactly
        // one trailing rebuild (last-write-wins) — no update is lost. immediate=true is never coalesced.
        val coalesceRebuild = CoalesceRequestRebuildFlag.enabled && !immediate
        if (coalesceRebuild && !pendingPreviewRebuild.compareAndSet(false, true)) {
            return
        }
        runOnCameraThread {
            if (coalesceRebuild) pendingPreviewRebuild.set(false)
            if (captureSession != sess || generation != sessionGeneration.get()) return@runOnCameraThread
            try {
                applyAll(builder, forStillCapture = false)
                val request = builder.build()
                // Always install the change on the REPEATING request so it persists. A one-shot
                // capture() alone (the old immediate path) applied for a single frame and then the
                // previously-installed repeating request resumed with the old parameters — so the
                // locked-exposure slider flickered to target for one frame and snapped back. When
                // immediate is requested we additionally fire one priority capture so the change lands
                // on the very next frame without waiting for the repeating cadence.
                sess.setRepeatingRequest(request, captureCallback, backgroundHandler)
                if (immediate) {
                    runCatching { sess.capture(request, captureCallback, backgroundHandler) }
                }
            } catch (e: IllegalStateException) {
                // Session was closed, this is expected during camera teardown
                Log.d(TAG, "updatePreview: session closed, skipping (normal during teardown)")
            } catch (e: Exception) {
                Log.e(TAG, "updatePreview failed", e)
            }
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            previewHealthMonitor.trackCaptureResult()
            recordFrameDeliveryLedger.onFrameCompleted(recordingActive)
            if (r.tag === stillCaptureRequestTag) {
                Log.i(
                    TAG,
                    "Photo capture Camera2 result completed frame=${result.frameNumber} " +
                        "sensorTimestamp=${result.get(CaptureResult.SENSOR_TIMESTAMP) ?: -1L}"
                )
            }
            processResult(result)
        }

        // s1-f05: a non-flushed capture failure on the record path is a real dropped frame; a
        // FLUSHED failure is the intended consequence of a reconfigure (flush/abort/close). The
        // ledger classifies steady-state vs reconfigure-window so a drop is never silent.
        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
            recordFrameDeliveryLedger.onCaptureFailed(
                recordingActive = recordingActive,
                flushed = failure.reason == CaptureFailure.REASON_FLUSHED
            )
            if (r.tag === stillCaptureRequestTag && failure.reason != CaptureFailure.REASON_FLUSHED) {
                failStillCaptureRequest("Photo capture failed in the camera HAL")
            }
        }

        // s1-f05: a lost target buffer is a dropped frame; bounded while reconfiguring, unintended otherwise.
        override fun onCaptureBufferLost(
            s: CameraCaptureSession,
            r: CaptureRequest,
            target: Surface,
            frameNumber: Long
        ) {
            recordFrameDeliveryLedger.onBufferLost(recordingActive)
            if (r.tag === stillCaptureRequestTag && target == jpegReader?.surface) {
                failStillCaptureRequest("Photo buffer was lost before it could be saved")
            }
        }
    }

    private fun maybeLogPhysicalTelemetryForShaderProfiles(
        rollingShutterSkewNs: Long?,
        noiseProfilePairs: Int,
        oisSampleCount: Int
    ) {
        if (rollingShutterSkewNs == null && noiseProfilePairs <= 0 && oisSampleCount <= 0) return
        val signature = "${rollingShutterSkewNs ?: -1L}|$noiseProfilePairs|$oisSampleCount"
        if (signature == lastLoggedPhysicalTelemetrySignature) return
        lastLoggedPhysicalTelemetrySignature = signature
        rollingShutterSkewNs?.let {
            Log.d(TAG, "Rolling shutter skew for shader profiles: ${it}ns")
        }
        if (noiseProfilePairs > 0) {
            Log.d(TAG, "SENSOR_NOISE_PROFILE coefficients for shader profiles: pairs=$noiseProfilePairs")
        }
        if (oisSampleCount > 0) {
            Log.d(TAG, "OIS telemetry for shader profiles: samples=$oisSampleCount")
        }
    }

    private fun processResult(r: CaptureResult) {
        val tr = r as? TotalCaptureResult
        if (tr != null) onCaptureResult?.invoke(tr)
        // CP17: verify the device honored the requested ISP-bypass (observe-only telemetry).
        verifyIspBypassReadback(r)
        maybeLogNdrExposureReadback(r)
        // F5 probe: surface the AF servo state vs recording so an idle-vs-recording AF freeze is visible
        // in logcat ("AFPROBE"). Throttled to ~1/sec. afState 3=PASSIVE_FOCUSED, 4=PASSIVE_SCAN,
        // 5=FOCUSED_LOCKED, 6=NOT_FOCUSED_LOCKED; a value stuck constant across a refocus while
        // recording (but scanning in preview) confirms the servo freeze.
        if (r.frameNumber % 30L == 0L) {
            Log.i(
                TAG,
                "AFPROBE afState=${r.get(CaptureResult.CONTROL_AF_STATE)} " +
                    "afMode=${r.get(CaptureResult.CONTROL_AF_MODE)} recording=$recordingActive " +
                    "afUnlock=${AfRecordingUnlockFlag.enabled}"
            )
        }
        // F4 diagnostic: log the lens-shading correction map's centre vs corner per-channel gains so the
        // sensor's optical colour-shading shape (magenta-centre / green-edge) is quantified on-device.
        // Channels: 0=R, 1=Geven, 2=Godd, 3=B. Throttled to ~1/2s.
        if (r.frameNumber % 60L == 0L) {
            r.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { m ->
                val cols = m.columnCount
                val rows = m.rowCount
                if (cols > 0 && rows > 0) {
                    val cc = cols / 2
                    val cr = rows / 2
                    Log.i(
                        TAG,
                        "LSCPROBE grid=${cols}x$rows " +
                            "center R=${"%.3f".format(m.getGainFactor(0, cc, cr))} " +
                            "G=${"%.3f".format(m.getGainFactor(1, cc, cr))} " +
                            "B=${"%.3f".format(m.getGainFactor(3, cc, cr))} | " +
                            "corner R=${"%.3f".format(m.getGainFactor(0, 0, 0))} " +
                            "G=${"%.3f".format(m.getGainFactor(1, 0, 0))} " +
                            "B=${"%.3f".format(m.getGainFactor(3, 0, 0))}"
                    )
                }
            }
        }
        val resultNoiseProfile = r.get(CaptureResult.SENSOR_NOISE_PROFILE)
        latestSensorNoiseProfilePairs = resultNoiseProfile?.size ?: 0
        val colorCorrectionGains = r.get(CaptureResult.COLOR_CORRECTION_GAINS)
        CameraSensorNoiseModelPolicy.fromCaptureResult(r)?.let { model ->
            sensorNoiseModel = model
        }
        val rollingShutterSkewNs = r.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)
        physicalImagingTraits = CameraPhysicalImagingTraits.updateFromCaptureResult(
            physicalImagingTraits,
            r,
            colorCorrectionGains
        )
        // Bound the measured white point against the active mode's per-frame u'v' budget. This
        // observes (does not mutate) the request stream — it consumes the previously declared-only
        // drift budget and surfaces AWB hunting; the stabilized white point is available via
        // colorDriftGovernor for a future on-device correction stage.
        colorCorrectionGains?.let { gains ->
            // Cache the HAL's measured gains + absolute green scale + per-frame CCM so the
            // experimental active override can reproduce the HAL's color exactly when engaged.
            latestMeasuredColorGains = gains
            latestMeasuredGreenGain = ((gains.greenEven + gains.greenOdd) * 0.5f).coerceAtLeast(1e-4f)
            r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { latestMeasuredColorTransform = it }
            // NDRCOLOR probe (observe-only, no pixel change): in an NDR/NIVUKX HDR mode, surface the HAL's
            // measured white-balance gains + whether a per-frame CCM is present. This is the one datum
            // needed to size the global white-point correction: the live tone operators are hue-preserving
            // and cannot originate the reported all-object red cast, so it must live in the vendor AWB/CCM
            // read here. Throttled on a coarse signature so it never spams logcat.
            if (hdrDynamicRangeMode != HdrDynamicRangeMode.SDR) {
                val greenGain = latestMeasuredGreenGain.coerceAtLeast(1e-4f)
                val rOverG = gains.red / greenGain
                val bOverG = gains.blue / greenGain
                val probe = "%.2f|%.2f|%b".format(rOverG, bOverG, latestMeasuredColorTransform != null)
                if (probe != lastLoggedNdrColorProbeSignature) {
                    lastLoggedNdrColorProbeSignature = probe
                    Log.i(
                        TAG,
                        "NDRCOLOR measured AWB gains R=${"%.3f".format(gains.red)} " +
                            "Gr=${"%.3f".format(gains.greenEven)} Gb=${"%.3f".format(gains.greenOdd)} " +
                            "B=${"%.3f".format(gains.blue)} R/G=${"%.3f".format(rOverG)} " +
                            "B/G=${"%.3f".format(bOverG)} ccmPresent=${latestMeasuredColorTransform != null} " +
                            "mode=$hdrDynamicRangeMode"
                    )
                }
            }
            // P2.9: tighten the per-frame u'v' budget in low light (high ISO ⇒ noisier AWB) so the
            // governor hunts less; nominal ISO leaves the mode budget unchanged (scale == 1.0).
            val perFrameIso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: -1
            val budgetUv = activeColorDriftBudgetUv *
                VideoColorStabilityPolicy.lowLightBudgetScale(perFrameIso)
            // CP47: feed a real illuminant-estimate confidence so the governor's confidence-ceiling
            // scaling actually engages (previously always defaulted to 1f = full budget every frame).
            // The HAL AWB state is the available proxy: a CONVERGED/LOCKED estimate is trustworthy
            // (full budget), a SEARCHING estimate is noisy/mid-transition (tighter ceiling so we don't
            // chase it — the 0.25 floor still tracks a genuine illuminant change slowly).
            val awbConfidence = when (r.get(CaptureResult.CONTROL_AWB_STATE)) {
                CaptureResult.CONTROL_AWB_STATE_CONVERGED,
                CaptureResult.CONTROL_AWB_STATE_LOCKED -> 1f
                CaptureResult.CONTROL_AWB_STATE_SEARCHING -> 0.35f
                else -> 0.7f
            }
            colorDriftGovernor.accept(
                red = gains.red,
                greenEven = gains.greenEven,
                greenOdd = gains.greenOdd,
                blue = gains.blue,
                maxFrameToFrameColorDriftUv = budgetUv,
                confidence = awbConfidence
            )?.let { drift -> maybeLogColorDrift(drift, budgetUv) }
        }
        motionTelemetry = CameraMotionTelemetry.fromCaptureResult(motionTelemetry, r)
        motionTelemetryRingBuffer.push(motionTelemetry)
        var oisSampleCountForLog = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val oisSamples = r.get(CaptureResult.STATISTICS_OIS_SAMPLES)
            oisSampleCountForLog = oisSamples?.size ?: 0
            latestOisSampleCount = oisSampleCountForLog
        } else {
            latestOisSampleCount = 0
        }
        if (strictHighEndEnabled()) {
            maybeLogPhysicalTelemetryForShaderProfiles(
                rollingShutterSkewNs = rollingShutterSkewNs,
                noiseProfilePairs = resultNoiseProfile?.size ?: 0,
                oisSampleCount = oisSampleCountForLog
            )
        }
        val liveResult = LiveResult(
            iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
            exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            afState = r.get(CaptureResult.CONTROL_AF_STATE) ?: 0,
            aeState = r.get(CaptureResult.CONTROL_AE_STATE) ?: 0,
            awbState = r.get(CaptureResult.CONTROL_AWB_STATE) ?: 0,
            focusDist = r.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
            timestampNs = r.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
        )
        previousCaptureTimestampNs = latestCaptureTimestampNs
        latestCaptureTimestampNs = liveResult.timestampNs
        if (liveResult.timestampNs > 0L) {
            previewFrameTimestampFlow.tryEmit(liveResult.timestampNs)
        }
        resultFlow.value = liveResult
        // s3-f05 / CP60 — per-frame gyro-EIS orientation consumer. Gated OFF by default
        // (gyroEisTelemetryConsumerEnabled) so on a stock device this is a single boolean check and
        // publishes nothing; when enabled device-side it integrates the live gyro stream into the per-frame
        // EIS orientation for s3-f06's (still-OFF) warp grid — reaching no pixel.
        publishGyroEisFrameOrientation(liveResult)
        // s9 / CP-QV22 — dormant custom-EIS consumer. Gated OFF by default (gyroEisTelemetryConsumerEnabled),
        // so on a stock device this is a single boolean check that does nothing: the StabilizationSessionController
        // is never even allocated (zero per-frame allocation, byte-for-byte identical to today). When enabled
        // device-side it advances the trajectory smoother and produces a warp grid that goes NOWHERE (no GPU EIS
        // upload path; controller mode stays OFF). runCatching so a telemetry hiccup never crashes this capture
        // callback.
        if (gyroEisTelemetryConsumerEnabled) {
            runCatching { consumeGyroEisFrame(liveResult) }
                .onFailure { Log.w(TAG, "gyro-EIS consumer frame skipped", it) }
        }
        com.neoproc.neoproccamx.utils.BootTrace.markFirstFrameOnce()
        maybeDampMeteringConvergence(liveResult)
        maybeRefreshExtremeDetailFallbackExposure(liveResult)
        maybeRefreshHdrAntiBlurShutter(liveResult)
    }

    /** Verify that the HAL honored the NDR AE-compensation request; telemetry only. */
    private fun maybeLogNdrExposureReadback(result: CaptureResult) {
        if (hdrDynamicRangeMode == HdrDynamicRangeMode.SDR) return
        val requested = lastRequestedNdrExposureCompensationIndex ?: return
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (nowElapsedMs - lastHdrExposureReadbackElapsedMs < 1_000L) return
        lastHdrExposureReadbackElapsedMs = nowElapsedMs
        val reported = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
        val message =
            "NDREXPOSURE readback requestedIndex=$requested reportedIndex=${reported ?: "unset"} " +
                "iso=${result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0} " +
                "exposureNs=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L} " +
                "analysisFresh=${hdrExposureMetricsFresh(nowElapsedMs)}"
        if (reported != null && reported != requested) {
            Log.w(TAG, "$message honored=false")
        } else {
            Log.i(TAG, "$message honored=${reported == requested}")
        }
    }

    private fun hdrExposureMetricsFresh(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean =
        lastHdrExposureMetricsElapsedMs > 0L &&
            nowElapsedMs - lastHdrExposureMetricsElapsedMs <=
            NdrAdaptiveHighlightExposurePolicy.LIVE_METRICS_MAX_AGE_MS

    // ── CP17: ISP-bypass read-back verification (observe-only) ──────────────────────────────────
    // Camera2 cannot prove vendor-private ISP stages are off, so this verifies the *public* blocks:
    // it compares the modes we requested (snapshotted from the built request) against the modes the
    // device reported in the TotalCaptureResult and surfaces an honest PROVEN/NOT_PROVEN verdict.
    // It changes reporting only — the request stream is never touched here.

    private fun snapshotRequestedIspBypassModes(builder: CaptureRequest.Builder): IspBypassRequestedModes {
        val tonemapMode = builder.getOrNullKey(CaptureRequest.TONEMAP_MODE)
        // Only claim we expect a linear curve when the requested curve is actually the identity ramp,
        // so a (hypothetical) non-identity recording curve can't force a false NOT_PROVEN later.
        val expectsLinearTonemapCurve =
            tonemapMode == CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE &&
                isIdentityTonemapCurve(builder.getOrNullKey(CaptureRequest.TONEMAP_CURVE)) == true
        return IspBypassRequestedModes(
            edgeMode = builder.getOrNullKey(CaptureRequest.EDGE_MODE),
            noiseReductionMode = builder.getOrNullKey(CaptureRequest.NOISE_REDUCTION_MODE),
            hotPixelMode = builder.getOrNullKey(CaptureRequest.HOT_PIXEL_MODE),
            shadingMode = builder.getOrNullKey(CaptureRequest.SHADING_MODE),
            colorAberrationMode = builder.getOrNullKey(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE),
            tonemapMode = tonemapMode,
            distortionCorrectionMode = builder.getOrNullKey(CaptureRequest.DISTORTION_CORRECTION_MODE),
            expectsLinearTonemapCurve = expectsLinearTonemapCurve
        )
    }

    private fun verifyIspBypassReadback(r: CaptureResult) {
        val requested = lastRequestedIspBypassModes ?: return
        if (!requested.isBypassRequest || loggedIspBypassVerification) return
        // Let the HAL settle into the requested ISP state before judging — the result stream lags the
        // request — then verify exactly once per request (≈10 µs of result reads + a 65-point curve
        // scan, off the per-pixel path; subsequent frames skip via loggedIspBypassVerification).
        if (++ispBypassReadbackFramesObserved < ISP_BYPASS_READBACK_SETTLE_FRAMES) return
        val report = CameraIspBypassVerificationPolicy.verify(requested, reportedIspBypassModes(r))
        lastIspBypassVerification = report
        loggedIspBypassVerification = true
        Log.i(TAG, "CP17 ISP-bypass read-back: ${report.toLogLine()}")
        if (report.aggregate == IspBypassHonestyVerdict.NOT_PROVEN) {
            emitCameraPipelineWarning(report.warning)
        }
    }

    private fun reportedIspBypassModes(r: CaptureResult): IspBypassReportedModes =
        IspBypassReportedModes(
            edgeMode = r.getOrNullKey(CaptureResult.EDGE_MODE),
            noiseReductionMode = r.getOrNullKey(CaptureResult.NOISE_REDUCTION_MODE),
            hotPixelMode = r.getOrNullKey(CaptureResult.HOT_PIXEL_MODE),
            shadingMode = r.getOrNullKey(CaptureResult.SHADING_MODE),
            colorAberrationMode = r.getOrNullKey(CaptureResult.COLOR_CORRECTION_ABERRATION_MODE),
            tonemapMode = r.getOrNullKey(CaptureResult.TONEMAP_MODE),
            distortionCorrectionMode = r.getOrNullKey(CaptureResult.DISTORTION_CORRECTION_MODE),
            tonemapCurveLinear = isIdentityTonemapCurve(r.getOrNullKey(CaptureResult.TONEMAP_CURVE))
        )

    private fun <T> CaptureRequest.Builder.getOrNullKey(key: CaptureRequest.Key<T>): T? =
        runCatching { get(key) }.getOrNull()

    private fun <T> CaptureResult.getOrNullKey(key: CaptureResult.Key<T>): T? =
        runCatching { get(key) }.getOrNull()

    /** True if every channel of the reported tonemap curve is the identity ramp; null if absent. */
    private fun isIdentityTonemapCurve(curve: TonemapCurve?): Boolean? {
        curve ?: return null
        return runCatching {
            val epsilon = 1e-3f
            for (channel in 0 until 3) { // CHANNEL_RED, CHANNEL_GREEN, CHANNEL_BLUE
                val count = curve.getPointCount(channel)
                if (count < 2) return@runCatching false
                for (i in 0 until count) {
                    val point = curve.getPoint(channel, i) // PointF(in, out)
                    if (kotlin.math.abs(point.x - point.y) > epsilon) return@runCatching false
                }
            }
            true
        }.getOrNull()
    }

    /**
     * CP17: the recorded distortion geometry for the active ISP-bypass session, for capture sidecars.
     * Only reports a geometry once the read-back has verified it from the device's result — until then
     * (and for processed/non-bypass captures) it is UNKNOWN rather than an unverified SENSOR_NATIVE claim.
     */
    fun ispBypassDistortionGeometryLabel(): String =
        lastIspBypassVerification?.geometry?.label ?: IspBypassGeometry.UNKNOWN.label

    /** CP17: the honest aggregate read-back verdict (PROVEN/NOT_PROVEN/NOT_APPLICABLE) for sidecars. */
    fun ispBypassVerdictLabel(): String =
        lastIspBypassVerification?.aggregate?.name ?: IspBypassHonestyVerdict.NOT_APPLICABLE.name

    /**
     * Runtime-health-monitor recovery hook (non-destructive): re-arm the CP17 ISP metadata read-back
     * so the next settled frames re-verify the requested ISP mode against CaptureResult metadata. Only
     * clears the one-shot latch; it never touches the capture session or requests.
     */
    fun requestIspBypassReadbackRearm() {
        loggedIspBypassVerification = false
        ispBypassReadbackFramesObserved = 0
    }

    /**
     * Issue 4 (ISP Native health): the last CP17 read-back warning — the offending ISP block when the HAL
     * did NOT honor the requested mode, or null. Observe-only, no side effects.
     */
    fun ispBypassReadbackWarning(): String? = lastIspBypassVerification?.warning

    /**
     * Issue 4: is a RAW_SENSOR stream currently live in the negotiated session (raw reader bound AND the
     * stream graph includes it)? Pure query — distinguishes an engaged sensor-native RAW path from a
     * plain processed preview.
     */
    fun isRawStreamActive(): Boolean =
        rawReader != null && currentCinemaStreamGraphPlan().rawSensor.includeInSession

    /**
     * Issue 4: can this HAL genuinely bypass the vendor ISP (sensor-native RAW)? true when a RAW-bypass
     * path exists, false when the vendor ISP is mandatory, and null before the capability report is
     * resolved (camera not fully open) — null MUST be treated as "unknown", never as unsupported.
     * Translated to a plain Boolean here so the Android-free monitor package never sees camera internals.
     */
    fun ispBypassSupportedByHardware(): Boolean? =
        cameraBypassCapabilityReport?.let {
            it.raw != BypassCapabilityScore.VENDOR_REQUIRED || it.ispOffApplied
        }

    /** Issue 4: is the vendor ISP mandatory (bypass impossible) on this hardware? null = not yet reported. */
    fun vendorIspRequired(): Boolean? =
        cameraBypassCapabilityReport?.let { it.raw == BypassCapabilityScore.VENDOR_REQUIRED }

    /**
     * Issue 3 (Pipeline Core health): the real, CameraCharacteristics-interrogated HAL capability badge
     * (e.g. "Pro Cinema 83/100"), or null before the report is resolved. Projected to primitives so the
     * Android-free monitor package never imports camera internals.
     */
    fun capabilityHealthBadge(): String? = cameraBypassCapabilityReport?.halProfile?.badgeLabel

    /** Issue 3: the 0..100 capability score derived from actual hardware, or null. */
    fun capabilityHealthScore(): Int? = cameraBypassCapabilityReport?.halProfile?.score

    /** Issue 3: the EXACT per-cause limitation reasons (why RAW is limited, 10-bit not exposed, ...). */
    fun capabilityHealthLimitations(): List<String> =
        cameraBypassCapabilityReport?.halProfile?.limitations ?: emptyList()

    /**
     * Per-frame driver for [meteringHysteresis]. Debounces the AE/AWB convergence used by the
     * SDR auto-lock so a brief reframe / continuous-AF re-scan does not collapse the lock and
     * trigger the native exposure-overshoot washout + white-point shift. When the debounced state
     * flips (and the lock actually matters for the active mode), it rebuilds the request promptly so
     * the new lock decision is applied — `updatePreview` is otherwise not called per frame.
     */
    private fun maybeDampMeteringConvergence(result: LiveResult) {
        val aeConvergedRaw = result.aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            result.aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
        val awbConvergedRaw = result.awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
            result.awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED
        val afScanActive = result.afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
            result.afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
        val gateChanged = postRebuildConvergenceGate.update(awbConvergedRaw)
        val stableChanged = meteringHysteresis.update(aeConvergedRaw, awbConvergedRaw, afScanActive)
        val dampingRebuild = stableChanged && MeteringDampingFlag.enabled
        // RC5: when the deferral window clears (genuine post-rebuild convergence, or the safety cap),
        // rebuild the request so the now-permitted AWB lock engages immediately. Gate is only ever
        // armed when the flag is ON, so gateChanged is always false when OFF (identical to today).
        val gateRebuild = gateChanged && SessionRebuildColorHoldFlag.enabled
        if ((dampingRebuild || gateRebuild) && isMeteringDampingRelevant()) {
            updatePreview()
        }
    }

    /**
     * RC5: arm the post-rebuild AWB-lock deferral, but ONLY once the fresh session is live (called from
     * onConfigured, after the outgoing hot-swap session has been closed). Arming here — not in the
     * synchronous createSession body — guarantees the gate is advanced only by fresh-session frames, so
     * a stale "converged" frame from the outgoing session can never flip the gate open off the old
     * white point. Default OFF (or a lens switch): [pendingRebuildColorHoldArm] is false and the gate
     * stays disarmed (identity). Camera-thread only.
     */
    private fun maybeArmPostRebuildColorHold() {
        if (pendingRebuildColorHoldArm && SessionRebuildColorHoldFlag.enabled) {
            postRebuildConvergenceGate.armForRebuild()
            Log.i(TAG, "RC5 post-rebuild AWB lock deferral armed (fresh session live)")
        } else {
            postRebuildConvergenceGate.disarm()
        }
        pendingRebuildColorHoldArm = false
    }

    /**
     * P1.5: the convergence-hysteresis debounce governs the auto-WB lock in SDR **and** in the
     * NDR/HDR/LOG preview paths (whose `ndrLogPreviewAutoColorLock` gate reads the same debounced
     * `awbStable`/`aeStable` signal). Extending the prompt-rebuild trigger to those modes makes their
     * AWB lock engage/release on convergence just as responsively as SDR — killing the reframe/tilt
     * white-point shift in HDR/LOG too — while the RAW master path (which must stay HAL-unmodified)
     * is still excluded. Idle contexts (no preview/record surface) are skipped.
     */
    private fun isMeteringDampingRelevant(): Boolean =
        !rawVideoCaptureEnabled &&
            (videoPreviewActive || recordingActive || previewContinuityRecordSurfaceEnabled)

    private fun maybeRefreshExtremeDetailFallbackExposure(result: LiveResult) {
        if (extremeDetailFallbackExposureRequestApplied) return
        if (!recordingActive && !previewContinuityRecordSurfaceEnabled) return
        if (selectedVideoDetailProfile != VideoDetailProfile.EXTREME_DETAIL) return
        if (rawVideoCaptureEnabled || !supportsManualSensor) return
        if (manIso > 0 || manExpNs > 0L) return
        if (result.iso <= 0 || result.exposureNs <= 0L) return
        Log.i(
            TAG,
            "Extreme Detail fallback exposure metered refresh " +
                "iso=${result.iso} exposureNs=${result.exposureNs}"
        )
        extremeDetailFallbackExposureRequestApplied = true
        updatePreview()
    }

    /**
     * Whether the NDR10+/NIVUKX closed-loop anti-blur shutter cap is eligible on the current request:
     * an HDR mode on the auto-exposure path (never RAW, never user-manual sensor), on a live
     * preview/record surface, with a device that can take a manual exposure time.
     */
    private fun hdrAntiMotionBlurEligible(): Boolean =
        NdrAntiMotionBlurFlag.enabled &&
            hdrDynamicRangeMode != HdrDynamicRangeMode.SDR &&
            supportsManualSensor &&
            !rawVideoCaptureEnabled &&
            manIso <= 0 &&
            manExpNs <= 0L &&
            (videoPreviewActive || recordingActive || previewContinuityRecordSurfaceEnabled)

    /**
     * Apply the NDR10+/NIVUKX anti-motion-blur shutter cap into an auto-exposure request. Caches the
     * cap + ISO floor so the capture-result feedback loop ([maybeRefreshHdrAntiBlurShutter]) can decide
     * when the engage state should flip. Uses `SENSOR_EXPOSURE_TIME_PRIORITY` (API 36+) so AE keeps
     * controlling ISO while we pin only the exposure time; on older devices / unsupported keys it is a
     * no-op (the cap simply is not enforced — never a downgrade of today's behaviour).
     */
    @SuppressLint("NewApi", "InlinedApi")
    private fun applyHdrAntiMotionBlurShutter(
        builder: CaptureRequest.Builder,
        frameDurationNs: Long,
        exposureLowerNs: Long,
        exposureUpperNs: Long,
        isoFloor: Int
    ) {
        val priorityModeSupported =
            Build.VERSION.SDK_INT >= 36 &&
                supportsCaptureRequestKey(CaptureRequest.CONTROL_AE_PRIORITY_MODE) &&
                supportsCaptureRequestKey(CaptureRequest.SENSOR_EXPOSURE_TIME)
        if (!hdrAntiMotionBlurEligible() || !priorityModeSupported) {
            // If we were previously engaged, mirror the normal disengage path so the persistent,
            // reused previewBuilder does not keep a stale SENSOR_EXPOSURE_TIME_PRIORITY + pinned
            // SENSOR_EXPOSURE_TIME after the feature becomes ineligible. Only write the key when it
            // is actually supported (never write an unsupported key) and only when previously
            // engaged (avoid needless writes / never engage on unsupported hardware).
            if (hdrAntiBlurEngaged && priorityModeSupported) {
                runCatching {
                    builder[CaptureRequest.CONTROL_AE_PRIORITY_MODE] =
                        CameraMetadata.CONTROL_AE_PRIORITY_MODE_OFF
                }.onFailure { error ->
                    Log.w(TAG, "Failed to disengage NDR anti-blur shutter on ineligibility", error)
                }
            }
            hdrAntiBlurEngaged = false
            hdrAntiBlurCapNs = 0L
            return
        }
        val latest = resultFlow.value
        val decision = NdrAntiMotionBlurShutterPolicy.resolve(
            requested = true,
            currentlyEngaged = hdrAntiBlurEngaged,
            meteredExposureNs = latest?.exposureNs ?: 0L,
            meteredIso = latest?.iso ?: 0,
            isoFloor = isoFloor,
            frameDurationNs = frameDurationNs,
            exposureRange = CameraLongRange(exposureLowerNs, exposureUpperNs)
        )
        hdrAntiBlurCapNs = decision.capNs
        hdrAntiBlurIsoFloor = isoFloor
        hdrAntiBlurEngaged = decision.engage
        val applied = runCatching {
            if (decision.engage) {
                builder[CaptureRequest.CONTROL_AE_PRIORITY_MODE] =
                    CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY
                builder[CaptureRequest.SENSOR_EXPOSURE_TIME] =
                    decision.pinnedExposureNs.coerceIn(exposureLowerNs, exposureUpperNs)
            } else {
                builder[CaptureRequest.CONTROL_AE_PRIORITY_MODE] =
                    CameraMetadata.CONTROL_AE_PRIORITY_MODE_OFF
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to apply NDR anti-blur shutter (engage=${decision.engage})", error)
        }.isSuccess
        appliedProcessedVideoQualityEvidence = appliedProcessedVideoQualityEvidence + mapOf(
            "ndrAntiMotionBlurEngaged" to decision.engage.toString(),
            "ndrAntiMotionBlurCapNs" to decision.capNs.toString(),
            "ndrAntiMotionBlurReason" to decision.reason,
            "ndrAntiMotionBlurApplied" to applied.toString()
        )
        val signature = "${decision.engage}|${decision.capNs}|${decision.reason}|$applied"
        if (signature != lastHdrAntiBlurLogSignature) {
            lastHdrAntiBlurLogSignature = signature
            Log.i(
                TAG,
                "NDR anti-blur shutter engage=${decision.engage} capNs=${decision.capNs} " +
                    "pinnedNs=${decision.pinnedExposureNs} reason=${decision.reason} applied=$applied " +
                    "meteredExposureNs=${resultFlow.value?.exposureNs ?: 0L} " +
                    "meteredIso=${resultFlow.value?.iso ?: 0} isoFloor=$isoFloor"
            )
        }
    }

    /**
     * Capture-result feedback for the anti-blur shutter loop: using the cap/ISO-floor cached from the
     * last request build, decide whether the engage state should flip (dim scene crossed the cap, or a
     * pinned bright scene dropped to floor ISO) and, if so, rebuild the request so the new posture
     * takes effect. Cheap no-op until the HDR anti-blur path has run at least once ([hdrAntiBlurCapNs]).
     */
    private fun maybeRefreshHdrAntiBlurShutter(result: LiveResult) {
        val capNs = hdrAntiBlurCapNs
        if (capNs <= 0L) return
        if (!hdrAntiMotionBlurEligible()) return
        if (result.iso <= 0 || result.exposureNs <= 0L) return
        val decision = NdrAntiMotionBlurShutterPolicy.resolveWithCap(
            requested = true,
            currentlyEngaged = hdrAntiBlurEngaged,
            meteredExposureNs = result.exposureNs,
            meteredIso = result.iso,
            isoFloor = hdrAntiBlurIsoFloor,
            capNs = capNs
        )
        if (decision.engage != hdrAntiBlurEngaged) {
            Log.i(
                TAG,
                "NDR anti-blur shutter feedback flip engage=${decision.engage} reason=${decision.reason} " +
                    "meteredExposureNs=${result.exposureNs} meteredIso=${result.iso}"
            )
            updatePreview()
        }
    }

    /**
     * Fix B closed loop: receive the live scene average luma + highlight-clip fraction (pushed from the
     * analysis stream by the ViewModel) and update the ADAPTIVE highlight-protect bias. Rebuilds the
     * capture request ONLY when the resulting AE-compensation index would actually change, so it never
     * churns per frame. No-op unless an HDR (NDR/NIVUKX) mode is active and [NdrAdaptiveHighlightFlag]
     * is on. The bias is <= 0 (only ever darker, to save highlights — a strict no-regression lever).
     */
    override fun setHdrExposureSceneMetrics(
        averageLuma: Float,
        highlightClipFraction: Float,
        redHighlightClipFraction: Float,
        analysisValid: Boolean
    ) {
        if (!NdrAdaptiveHighlightFlag.enabled) {
            if (hdrAdaptiveHighlightExtraStops != 0f) {
                hdrAdaptiveHighlightExtraStops = 0f
                if (hdrDynamicRangeMode != HdrDynamicRangeMode.SDR) updatePreview()
            }
            return
        }
        if (hdrDynamicRangeMode == HdrDynamicRangeMode.SDR) return
        if (!analysisValid) return
        lastHdrExposureMetricsElapsedMs = SystemClock.elapsedRealtime()
        val effectiveRedHighlightClipFraction =
            if (NdrRedClipHighlightFlag.enabled) redHighlightClipFraction else 0f
        val target =
            NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(
                averageLuma,
                highlightClipFraction,
                effectiveRedHighlightClipFraction
            )
        // Fast attack protects a channel before it flattens; slow release prevents visible pumping.
        val smoothed = NdrAdaptiveHighlightExposurePolicy.smoothExtraStops(
            currentStops = hdrAdaptiveHighlightExtraStops,
            targetStops = target
        )
        hdrAdaptiveHighlightExtraStops = smoothed
        // Rebuild only when the smoothed bias would move the compensation index by >= one step vs what
        // the last built request applied (the AE-comp is quantised to evCompensationStepStops anyway).
        val step = if (evCompensationStepStops > 0f) evCompensationStepStops else 1f
        val appliedSteps = (hdrAdaptiveHighlightAppliedStops / step).roundToInt()
        val smoothedSteps = (smoothed / step).roundToInt()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (nowElapsedMs - lastHdrExposureEvidenceElapsedMs >= 1_000L) {
            lastHdrExposureEvidenceElapsedMs = nowElapsedMs
            val baseProtectStops = if (NdrHighlightProtectFlag.enabled) {
                HdrHighlightProtectExposurePolicy.DEFAULT_HIGHLIGHT_PROTECT_STOPS
            } else {
                0f
            }
            val appliedTotalStops = baseProtectStops + hdrAdaptiveHighlightAppliedStops
            Log.i(
                TAG,
                "NDREXPOSURE analysisActive=true recording=$recordingActive " +
                    "avgLuma=${averageLuma.toEvidenceDecimal()} " +
                    "highlightClip=${highlightClipFraction.toEvidenceDecimal()} " +
                    "redHighlightClip=${effectiveRedHighlightClipFraction.toEvidenceDecimal()} " +
                    "targetStops=${target.toEvidenceDecimal()} " +
                    "smoothedStops=${smoothed.toEvidenceDecimal()} " +
                    "adaptiveAppliedStops=${hdrAdaptiveHighlightAppliedStops.toEvidenceDecimal()} " +
                    "baseProtectStops=${baseProtectStops.toEvidenceDecimal()} " +
                    "appliedTotalStops=${appliedTotalStops.toEvidenceDecimal()}"
            )
        }
        if (smoothedSteps != appliedSteps) {
            updatePreview()
        }
    }

    private fun mutateControlState(
        immediate: Boolean,
        unlockAeAwb: Boolean = false,
        stateMutation: () -> Unit,
        paramsMutation: (CameraParams) -> CameraParams
    ) {
        runOnCameraThread {
            stateMutation()
            paramsFlow.value = paramsMutation(paramsFlow.value)
            if (unlockAeAwb) aeAwbLocked = false
            updatePreview(immediate = immediate)
        }
    }

    private fun applyZoomAndAspectCrop(builder: CaptureRequest.Builder, includeAspectCrop: Boolean) {
        val sensorRect = activeArray ?: return
        val sensorAspect = CameraSensorInfo.sensorBaseAspectRatio(fullSensorSize())
        val targetAspect = CameraControlMath.resolveCaptureRequestAspectRatio(
            sensorAspectRatio = sensorAspect,
            targetOutputAspectRatio = targetOutputAspectRatio,
            includeAspectCrop = includeAspectCrop
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && abs(targetAspect - sensorAspect) < 0.01f) {
            builder[CaptureRequest.CONTROL_ZOOM_RATIO] = zoomRatio.coerceIn(minZoom, maxZoom)
            builder[CaptureRequest.SCALER_CROP_REGION] = sensorRect
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Non-native aspect on API 30+: keep zoom on CONTROL_ZOOM_RATIO so optical lens switching
            // (ultrawide < 1x, tele) still engages exactly like the native-aspect / preview / video
            // paths. SCALER_CROP_REGION carries ONLY the centered aspect letterbox (zoom = 1f), applied
            // within the zoomed FOV — previously this branch forced ZOOM_RATIO=1 and folded zoom into a
            // digital crop, which clamped ultrawide to 1x and never engaged the optical tele.
            builder[CaptureRequest.CONTROL_ZOOM_RATIO] = zoomRatio.coerceIn(minZoom, maxZoom)
            builder[CaptureRequest.SCALER_CROP_REGION] = CameraControlMath.buildAspectAndZoomCropRegion(
                sensorRect = sensorRect,
                targetAspectRatio = targetAspect,
                zoom = 1f,
                maxZoom = maxZoom
            )
        } else {
            // Pre-R: no CONTROL_ZOOM_RATIO API — must fold zoom into the crop region (legacy digital zoom).
            builder[CaptureRequest.SCALER_CROP_REGION] = CameraControlMath.buildAspectAndZoomCropRegion(
                sensorRect = sensorRect,
                targetAspectRatio = targetAspect,
                zoom = zoomRatio,
                maxZoom = maxZoom
            )
        }
    }

    private fun CameraRect.toAndroidRect(): Rect = Rect(left, top, right, bottom)

    private fun Rect.toCameraRect(): CameraRect = CameraRect(left, top, right, bottom)

    private fun CameraWeightedMeteringRegion.toMeteringRectangle(): MeteringRectangle =
        MeteringRectangle(rect.toAndroidRect(), weight)

    private fun activeSensorCameraRect(): CameraRect? =
        activeArray?.let { CameraRect(it.left, it.top, it.right, it.bottom) }

    private fun resolveHdrSubjectMeteringFallback(
        activeSensorRect: CameraRect?
    ): HdrSubjectMeteringFallbackPlan =
        HdrSubjectMeteringFallbackPolicy.resolve(
            hdrDynamicRangeMode = hdrDynamicRangeMode,
            activeArray = activeSensorRect,
            persistentExposureRegion = persistentAeRegion?.toCameraRect(),
            persistentAwbRegion = persistentAwbRegion?.toCameraRect(),
            maxAeRegions = maxAeRegions,
            maxAwbRegions = maxAwbRegions,
            // Zoom-out highlight-blow fix: full-frame HDR metering (no centred patch) is zoom-insensitive.
            zoomStableExposureMetering = NdrZoomStableMeteringFlag.enabled
        )

    private fun applyPersistentMeteringRegions(
        builder: CaptureRequest.Builder,
        applyAfRegions: Boolean
    ) {
        val activeSensorRect = activeSensorCameraRect()
        val hdrFallbackPlan = resolveHdrSubjectMeteringFallback(activeSensorRect)
        if (applyAfRegions && maxAfRegions > 0) {
            val focusRegions = CameraMeteringRegionPlanPolicy.resolveFocusRegions(
                primaryRegion = persistentAfRegion?.toCameraRect(),
                activeArray = activeSensorRect,
                maxRegions = maxAfRegions
            )
            if (focusRegions.isNotEmpty()) {
                builder[CaptureRequest.CONTROL_AF_REGIONS] =
                    focusRegions.map { it.toMeteringRectangle() }.toTypedArray()
            } else {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
        }
        if (maxAeRegions > 0) {
            val exposureRegions = CameraMeteringRegionPlanPolicy.resolveExposureRegions(
                primaryRegion = persistentAeRegion?.toCameraRect() ?: hdrFallbackPlan.exposureRegion,
                activeArray = activeSensorRect,
                maxRegions = maxAeRegions
            )
            if (exposureRegions.isNotEmpty()) {
                builder[CaptureRequest.CONTROL_AE_REGIONS] =
                    exposureRegions.map { it.toMeteringRectangle() }.toTypedArray()
            } else {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            }
        }
        maybeLogHdrMeteringFallback(hdrFallbackPlan)
    }

    private fun applyAutoWhiteBalanceRegions(builder: CaptureRequest.Builder) {
        if (maxAwbRegions <= 0) return
        val activeSensorRect = activeSensorCameraRect()
        val hdrFallbackPlan = resolveHdrSubjectMeteringFallback(activeSensorRect)
        val subjectRegion = persistentAwbRegion?.toCameraRect()
            ?: hdrFallbackPlan.awbRegion
        val awbRegions = CinemaAutoWhiteBalanceRegionPolicy.resolve(
            subjectRegion = subjectRegion,
            activeArray = activeSensorRect,
            maxRegions = maxAwbRegions
        )
        if (awbRegions.isNotEmpty()) {
            builder[CaptureRequest.CONTROL_AWB_REGIONS] =
                awbRegions.map { it.toMeteringRectangle() }.toTypedArray()
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_REGIONS, null)
        }
    }

    private fun maybeLogHdrMeteringFallback(plan: HdrSubjectMeteringFallbackPlan) {
        if (hdrDynamicRangeMode == HdrDynamicRangeMode.SDR) return
        val signature = listOf(
            hdrDynamicRangeMode.name,
            plan.exposureRegion,
            plan.awbRegion,
            persistentAeRegion,
            persistentAwbRegion,
            maxAeRegions,
            maxAwbRegions
        ).joinToString(separator = "|")
        if (lastLoggedHdrMeteringFallbackSignature == signature) return
        lastLoggedHdrMeteringFallbackSignature = signature
        Log.i(
            TAG,
            "HDR subject metering dynamicRange=$hdrDynamicRangeMode " +
                "aeFallback=${plan.exposureRegion} awbFallback=${plan.awbRegion} " +
                "persistentAe=$persistentAeRegion persistentAwb=$persistentAwbRegion " +
                "maxAe=$maxAeRegions maxAwb=$maxAwbRegions"
        )
    }

    private fun resolveRecordingNoiseCleanupDistortionMode(
        profile: Camera2NoiseCleanupProfile,
        requestedMode: Int?
    ): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null

        val availableModes = characteristics
            ?.toIspBypassAvailability()
            ?.distortionCorrectionModes

        return when (profile) {
            Camera2NoiseCleanupProfile.PREVIEW -> null
            Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT -> when {
                availableModes == null -> requestedMode
                else -> DistortionCorrectionModePolicy.bestOnMode(availableModes)
            }
            Camera2NoiseCleanupProfile.RAW_SENSOR_TRUTHFUL -> when {
                requestedMode == null -> null
                availableModes == null -> requestedMode
                availableModes.contains(requestedMode) -> requestedMode
                else -> null
            }
        }
    }

    private fun applyAll(
        builder: CaptureRequest.Builder,
        forStillCapture: Boolean = false,
        pipelineRequestPlan: Camera2PipelineRequestPlan? = null,
        videoSnapshot: Boolean = false
    ) {
        val requestPlan = pipelineRequestPlan ?: resolveCamera2PipelineRequestPlan()
        val ispAvailability = characteristics?.toIspBypassAvailability()
        applyZoomAndAspectCrop(builder, includeAspectCrop = forStillCapture)

        // Engage the un-binned pixel mode for either the RAW maximum-sensor record tier or the
        // dedicated ultra-high-resolution still session. Reader dimensions and pixel mode are kept
        // in lockstep here; all ordinary video sessions explicitly remain in DEFAULT mode.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            supportsCaptureRequestKey(CaptureRequest.SENSOR_PIXEL_MODE)
        ) {
            builder[CaptureRequest.SENSOR_PIXEL_MODE] =
                if (maximumResolutionPixelModeActive()) {
                    CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                } else {
                    CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT
                }
        }

        builder[CaptureRequest.CONTROL_MODE] = CaptureRequest.CONTROL_MODE_AUTO
        builder[CaptureRequest.CONTROL_CAPTURE_INTENT] = when {
            videoSnapshot -> CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT
            forStillCapture -> CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
            else -> requestPlan.captureIntent
        }
        val videoRecordingRequestActive = recordingActive || previewContinuityRecordSurfaceEnabled
        // X1/R2: idle video preview adopts the record-grade extreme-detail exposure posture so the
        // preview stops visibly improving only when recording starts. videoPreviewActive is true for
        // VIDEO/PRO surfaces (set by CameraViewModel) and false for stills. The record-only
        // color-stability lock stays keyed on videoRecordingRequestActive (below) to preserve the
        // reframe-washout fix; this predicate only widens the extreme-detail exposure gate.
        val videoRenderContractActive = videoRecordingRequestActive || videoPreviewActive

        builder[CaptureRequest.STATISTICS_FACE_DETECT_MODE] = if (faceDetectionEnabled) {
            CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL
        } else {
            CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
        }
        // F4 diagnostic: request the lens-shading correction map so we can measure the sensor's residual
        // optical colour shading (the "magenta-centre / green-edge" cast) on-device. Observe-only —
        // enabling the map mode does not change any output pixels. See the "LSCPROBE" logcat line.
        if (supportsCaptureRequestKey(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE)) {
            builder[CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE] =
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
        }

        val consumerAutoVideo = isConsumerAutoVideoRequest(forStillCapture)
        // Centralized public Camera2 processing intent for consumer versus cinema paths.
        val baseProfile = HardwareAbstractionLayer.resolveProfile(
            isRecording = videoRecordingRequestActive,
            isRawCapture = rawVideoCaptureEnabled,
            isLogMode = previewLogLookEnabled,
            isCinemaFidelity = cinemaFidelityOverridesEnabled,
            noiseReductionEnabled = noiseReductionEnabled,
            isHdrMode = hdrDynamicRangeMode != HdrDynamicRangeMode.SDR,
            isStillCapture = forStillCapture,
            isHighSpeed = targetCameraFps >= 60,
            isConsumerMode = consumerAutoVideo
        )
        val profile = HardwareAbstractionLayer.thermalAwareProfile(baseProfile, thermalResourceCeiling)
        // RC2 (default OFF): during RAW video recording the SHARED preview request otherwise carries the
        // SUPREME_FIDELITY_BYPASS linear identity tonemap -> flat/washed RAW monitor. Swap in a viewable
        // HIGH_QUALITY tonemap for the repeating request only. Recorded RAW comes from the dedicated
        // RAW_SENSOR reader (RawVideoManager) and is pre-ISP-tonemap, so this cannot degrade RAW/DNG.
        val effectiveProfile =
            if (RawPreviewViewableTonemapFlag.shouldSubstituteViewableTonemap(
                    flagEnabled = RawPreviewViewableTonemapFlag.enabled,
                    rawVideoCaptureEnabled = rawVideoCaptureEnabled,
                    forStillCapture = forStillCapture,
                    profileAppliesLinearTonemap = profile.applyLinearTonemapCurve
                )
            ) {
                profile.copy(
                    tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
                    applyLinearTonemapCurve = false
                )
            } else {
                profile
            }
        effectiveProfile.apply(builder, characteristics)

        val stabilizationPlan = CameraVideoStabilizationRequestPolicy.resolve(
            requestedMode = stabilizationMode,
            supportsOis = supportsOIS,
            supportsVideoStabilization = supportsVideoStabilization,
            rawVideoCaptureEnabled = rawVideoCaptureEnabled
        )
        stabilizationPlan.oisMode?.let {
            builder[CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE] = it
        }
        stabilizationPlan.videoStabilizationMode?.let {
            builder[CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE] = it
        }
        // F-STAB: never silently swallow a stabilization selection. If the user picked a
        // mode whose required hardware this camera does not advertise, surface it once
        // (log + one-time UI warning) — the applied fallback is unchanged honest behavior.
        if (stabilizationMode != StabilizationMode.OFF &&
            !rawVideoCaptureEnabled &&
            !loggedStabilizationResolution &&
            CameraStabilizationCapabilityReport.requiredHardwareMissing(
                requested = stabilizationMode,
                supportsOis = supportsOIS,
                supportsVideoStabilization = supportsVideoStabilization
            )
        ) {
            loggedStabilizationResolution = true
            Log.i(
                TAG,
                "Stabilization requested=$stabilizationMode resolved=${stabilizationPlan.resolvedMode} " +
                    "caps=$stabilizationCapabilitySummary"
            )
            emitCameraPipelineWarning(
                "Stabilization: $stabilizationMode not available on this camera — " +
                    "using ${stabilizationPlan.resolvedMode}"
            )
        }

        // Apply sensor-truthful bypass only where policy explicitly permits it.
        val bypassPolicy = ispBypassPolicy
        val bypassDecision = CameraIspBypassApplicationPolicy.resolve(
            cinemaFidelityOverridesEnabled = cinemaFidelityOverridesEnabled,
            rawVideoCaptureEnabled = rawVideoCaptureEnabled,
            recordingActive = videoRecordingRequestActive,
            readoutSidecarActive = recordingReadoutOverrideSize != null || analysisTargetSize != null,
            hdrDynamicRangeMode = hdrDynamicRangeMode,
            appOwnedGpuProcessingEnabled = internalRecordingHardwareImportEnabled,
            strictHighEndEnabled = strictHighEndEnabled()
        )
        if (bypassDecision.applyRequestKeys && bypassPolicy != null) {
            val modifiedKeys = bypassPolicy.applyTotalISPBypass(
                captureRequest = builder,
                includeLinearTonemap = bypassDecision.includeLinearTonemap
            ) {
                recordingRawToneCurve ?: TonemapCurve(
                    floatArrayOf(0f, 0f, 1f, 1f),
                    floatArrayOf(0f, 0f, 1f, 1f),
                    floatArrayOf(0f, 0f, 1f, 1f)
                )
            }
            if (!loggedIspBypassRequestApplication) {
                loggedIspBypassRequestApplication = true
                Log.i(
                    TAG,
                    "Phase 1b: ISP-off request applied modifiedKeys=$modifiedKeys " +
                        "linearTonemap=${bypassDecision.includeLinearTonemap} " +
                        "reason=${bypassDecision.reason} complete=${bypassPolicy.supportsISPBypass()} " +
                        bypassPolicy.describeSupport()
                )
                emitCameraPipelineWarning(bypassPolicy.userWarning())
            }
        }

        val processedVideoPlan = if (
            !forStillCapture &&
            !rawVideoCaptureEnabled &&
            requestPlan.noiseCleanupProfile == Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT
        ) {
            CameraProcessedVideoQualityRequestPolicy.resolve(
                strictHighEndEnabled = strictHighEndEnabled(),
                rawVideoCaptureEnabled = false,
                consumerAutoVideo = consumerAutoVideo,
                availability = ispAvailability,
                requestedFps = targetCameraFps,
                availableAeFpsRanges = cachedFpsIntRanges,
                exposureRange = CameraLongRange(exposureRange.lower, exposureRange.upper),
                motionRisk = processedVideoMotionRisk,
                noiseRisk = processedVideoNoiseRisk,
                videoDetailProfile = selectedVideoDetailProfile,
                noiseReductionEnabled = noiseReductionEnabled
            )
        } else {
            null
        }
        val processedVideoEvidence = if (processedVideoPlan != null) {
            processedVideoPlan.edgeMode?.let { builder[CaptureRequest.EDGE_MODE] = it }
            processedVideoPlan.noiseReductionMode?.let { builder[CaptureRequest.NOISE_REDUCTION_MODE] = it }
            processedVideoPlan.hotPixelMode?.let { builder[CaptureRequest.HOT_PIXEL_MODE] = it }
            processedVideoPlan.shadingMode?.let { builder[CaptureRequest.SHADING_MODE] = it }
            processedVideoPlan.tonemapMode?.let { builder[CaptureRequest.TONEMAP_MODE] = it }
            processedVideoPlan.aberrationMode?.let {
                builder[CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE] = it
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                processedVideoPlan.distortionCorrectionMode?.let {
                    builder[CaptureRequest.DISTORTION_CORRECTION_MODE] = it
                }
            }
            mapOf(
                "ispFallbackRequestApplied" to "true",
                "ispFallbackRequestSummary" to processedVideoPlan.toEvidenceSummary(),
                "motionRisk" to processedVideoPlan.motionRisk.toEvidenceDecimal(),
                "noiseRisk" to processedVideoPlan.noiseRisk.toEvidenceDecimal(),
                "sourceQualityTier" to processedVideoPlan.sourceQualityTier.name,
                "sourceQualityBudgetMultiplier" to
                    processedVideoPlan.sourceQualityBudgetMultiplier.toEvidenceDecimal(),
                "antiMotionBlurEnabled" to processedVideoPlan.antiMotionBlurEnabled.toString(),
                "v20ProcessedVideoMotionRisk" to processedVideoPlan.motionRisk.toEvidenceDecimal(),
                "v20ProcessedVideoNoiseRisk" to processedVideoPlan.noiseRisk.toEvidenceDecimal(),
                "v20ProcessedVideoSourceQualityTier" to processedVideoPlan.sourceQualityTier.name,
                "v20ProcessedVideoSourceQualityBudgetMultiplier" to
                    processedVideoPlan.sourceQualityBudgetMultiplier.toEvidenceDecimal(),
                "v20AntiMotionBlurRequested" to processedVideoPlan.antiMotionBlurEnabled.toString(),
                "v20MotionStableFpsRequested" to processedVideoPlan.motionStableFps.evidenceValue(),
                "v20MotionStableAeTargetFpsRangeRequested" to
                    processedVideoPlan.motionStableAeTargetFpsRange.evidenceValue(),
                "v20MotionStableFrameDurationNsRequested" to
                    processedVideoPlan.motionStableFrameDurationNs.evidenceValue(),
                "v20ExposureTimeCapNsRequested" to processedVideoPlan.maxExposureTimeNs.evidenceValue(),
                "v20AntiMotionBlurApplicationStatus" to "NOT_EVALUATED",
                "v20MotionStableAeTargetFpsRangeApplied" to "unset",
                "v20SensorFrameDurationNsApplied" to "unset",
                "v20ExposureTimePriorityNsApplied" to "unset"
            )
        } else {
            emptyMap()
        }
        val gpuOwnedDistortionMode = CameraGpuOwnedGeometryPolicy.resolveDistortionCorrectionMode(
            appOwnedGpuProcessingEnabled = internalRecordingHardwareImportEnabled && !rawVideoCaptureEnabled,
            strictHighEndEnabled = strictHighEndEnabled(),
            availability = ispAvailability
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            gpuOwnedDistortionMode?.let {
                builder[CaptureRequest.DISTORTION_CORRECTION_MODE] = it
            }
        }
        val gpuOwnedGeometryEvidence = if (internalRecordingHardwareImportEnabled && !rawVideoCaptureEnabled) {
            mapOf(
                "gpuOwnedDistortionCorrectionOffRequested" to "true",
                "gpuOwnedDistortionCorrectionOff" to CameraGpuOwnedGeometryPolicy
                    .isDistortionCorrectionOffConfirmed(true, gpuOwnedDistortionMode)
                    .toString()
            )
        } else {
            emptyMap()
        }
        appliedProcessedVideoQualityEvidence = processedVideoEvidence + gpuOwnedGeometryEvidence

        // LOG is convert-only: recorded RAW is developed to NeoLog offline, so NeoLog is NEVER
        // baked into recorded (or preview) pixels via the ISP tone curve. The direct-LOG HAL
        // tonemap bake was removed here (NeoLogHalTonemapPolicy is retired). Any on-screen
        // LOG-style monitor look is handled by the GL preview compositor only, never the record path.

        // CP17: snapshot the final requested ISP-bypass modes from the built request (pure read, no
        // mutation) so the capture-result callback can verify requested-vs-honored. This sits after
        // every ISP-block writer (HAL profile, strict-bypass override, processed-video, GPU geometry)
        // so it captures the authoritative final state regardless of which writer won. Re-arm the
        // read-back only when the requested set actually changes, so unrelated request rebuilds (AF
        // regions, exposure) neither re-verify nor reset the settle window.
        val requestedIspBypassSnapshot = snapshotRequestedIspBypassModes(builder)
        if (requestedIspBypassSnapshot != lastRequestedIspBypassModes) {
            lastRequestedIspBypassModes = requestedIspBypassSnapshot
            lastIspBypassVerification = null
            loggedIspBypassVerification = false
            ispBypassReadbackFramesObserved = 0
        }

        val effectiveManFocusDist = resolveEffectiveManualFocusDistance()
        val autofocusPlan = CameraAutofocusRequestPolicy.resolve(
            manualFocusDistance = effectiveManFocusDist,
            aiDrivenFocusDistance = aiDrivenFocusDist,
            selectedAfMode = continuousAutoFocusMode(),
            maxAfRegions = maxAfRegions,
            maxFocusDistance = minFocusDist,
            // effectiveManFocusDist is 0f ONLY from an explicit setFocusDist(0f) — the user dragging
            // manual focus to the infinity endpoint (the default/reset sentinel is -1f). That is a
            // genuine infinity-lock request, so honor it (#22) without weakening the 0f="stale" guard.
            manualFocusInfinityRequested = effectiveManFocusDist == 0f
        )
        applyPersistentMeteringRegions(builder, autofocusPlan.applyAfRegions)

        builder[CaptureRequest.CONTROL_AF_MODE] = autofocusPlan.afMode
        if (autofocusPlan.lensFocusDistance != null) {
            val focusDistance = autofocusPlan.lensFocusDistance
            builder[CaptureRequest.LENS_FOCUS_DISTANCE] = focusDistance
        } else {
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, null)
        }

        val normalSdrVideoPreviewActive =
            videoPreviewActive &&
                !videoRecordingRequestActive &&
                !rawVideoCaptureEnabled &&
                hdrDynamicRangeMode == HdrDynamicRangeMode.SDR &&
                !previewLogLookEnabled &&
                !previewHdrLookEnabled &&
                !previewRawLookEnabled
        val ndrOrLogModeActive = hdrDynamicRangeMode != HdrDynamicRangeMode.SDR || previewLogLookEnabled
        // NDR/LOG live preview (not recording, not RAW-master) — eligible for the convergence-gated
        // AWB-only white-point hold so preview colour stops drifting on tilt/reframe.
        val ndrOrLogVideoPreviewActive =
            videoPreviewActive &&
                !videoRecordingRequestActive &&
                !rawVideoCaptureEnabled &&
                ndrOrLogModeActive
        val tapFocusMeteringUnlockActive = isTapFocusMeteringUnlockActive()
        val latest = resultFlow.value
        val rawAeConverged = latest?.aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            latest?.aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
        val rawAwbConverged = latest?.awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
            latest?.awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED
        // When damping is ON, use the hysteresis-damped convergence (updated per-frame in
        // processResult) rather than the instantaneous capture-result state, so a brief reframe /
        // AF-scan blip does not collapse the SDR auto-lock and trigger the native exposure-overshoot
        // washout + white-point shift. OFF restores the raw single-frame behaviour (for A/B).
        val autoExposureConvergedOrLocked =
            if (MeteringDampingFlag.enabled) meteringHysteresis.aeStable else rawAeConverged
        val awbConvergedOrLockedSignal =
            if (MeteringDampingFlag.enabled) meteringHysteresis.awbStable else rawAwbConverged
        // RC5: while the post-rebuild gate is armed (same-sensor rebuild, flag ON) suppress the AWB
        // lock until a genuine fresh convergence cycle completes, so it cannot latch onto the stale
        // pre-rebuild converged white point captured during the fresh-session AE overshoot. The read is
        // itself flag-gated (not just relying on lockPermitted==true when OFF): flipping the flag OFF
        // mid-deferral restores today's behaviour on the very next frame — byte-identical when OFF.
        val autoWhiteBalanceConvergedOrLocked =
            if (SessionRebuildColorHoldFlag.enabled) {
                awbConvergedOrLockedSignal && postRebuildConvergenceGate.lockPermitted
            } else {
                awbConvergedOrLockedSignal
            }
        val colorStabilityPlan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = videoRecordingRequestActive,
            sensorMasterActive = rawVideoCaptureEnabled,
            manualWhiteBalanceSelected = manWbK > 0,
            existingAeAwbLock = aeAwbLocked,
            normalSdrVideoPreviewActive = normalSdrVideoPreviewActive,
            ndrOrLogVideoPreviewActive = ndrOrLogVideoPreviewActive,
            autoExposureConvergedOrLocked = autoExposureConvergedOrLocked,
            autoWhiteBalanceConvergedOrLocked = autoWhiteBalanceConvergedOrLocked,
            ndrOrLogModeActive = ndrOrLogModeActive,
            tapFocusMeteringUnlockActive = tapFocusMeteringUnlockActive,
            automaticRecordingWhiteBalanceLockEnabled = false
        )
        // Publish this mode's per-frame drift budget so the capture-result governor enforces it.
        activeColorDriftBudgetUv = colorStabilityPlan.maxFrameToFrameColorDriftUv
        val effectiveManualKelvin =
            if (colorStabilityPlan.allowManualWhiteBalance) manWbK else -1
        // P1.6: when the shot-stable path must fall back to AWB_OFF+fixed-Kelvin (device whose AUTO
        // mode is not lockable), hold the last measured device-true white point instead of jumping to
        // a hardcoded 5500K — no visible white-point step when the lock engages.
        val shotStableFallback = deviceTrueShotStableFallbackWhitePoint()
        val whiteBalancePlan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = effectiveManualKelvin,
            manualTint = manWbTint,
            kelvinRange = CameraIntRange(manualWhiteBalanceRange.lower, manualWhiteBalanceRange.upper),
            availableAwbModes = cachedAwbModes,
            supportsCctWhiteBalance = supportsCctWhiteBalance,
            awbLocked = colorStabilityPlan.awbLockRequested,
            forceAutoForHdr = false,
            sdkInt = Build.VERSION.SDK_INT,
            requireLockableAutoWhiteBalance = colorStabilityPlan.requiresLockableAutoWhiteBalance,
            shotStableFallbackKelvin = shotStableFallback?.first ?: 5_500,
            shotStableFallbackTint = shotStableFallback?.second ?: 0
        )
        builder[CaptureRequest.CONTROL_AWB_MODE] = whiteBalancePlan.awbMode
        val requestedKelvin = whiteBalancePlan.colorTemperatureKelvin
        val requestedTint = whiteBalancePlan.colorTint ?: 0
        if (requestedKelvin != null &&
            whiteBalancePlan.colorCorrectionMode == CameraMetadata.COLOR_CORRECTION_MODE_CCT &&
            Build.VERSION.SDK_INT >= 36
        ) {
            builder[CaptureRequest.COLOR_CORRECTION_MODE] =
                CameraMetadata.COLOR_CORRECTION_MODE_CCT
            builder[CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE] = requestedKelvin
            builder[CaptureRequest.COLOR_CORRECTION_COLOR_TINT] = requestedTint
        } else if (requestedKelvin != null && whiteBalancePlan.useTransformMatrix) {
            builder[CaptureRequest.COLOR_CORRECTION_MODE] =
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            applyManualTransformMatrixWhiteBalance(builder, requestedKelvin, requestedTint)
        } else {
            applyAutoWhiteBalanceRegions(builder)
        }
        val cctManualColorTransformActive =
            Build.VERSION.SDK_INT >= 36 &&
                whiteBalancePlan.colorCorrectionMode == CameraMetadata.COLOR_CORRECTION_MODE_CCT
        val manualColorTransformActive = requestedKelvin != null && (
            cctManualColorTransformActive ||
                whiteBalancePlan.useTransformMatrix
            )
        val hdrColorCorrectionMode = CameraHdrColorCorrectionPolicy.resolve(
            hdrDynamicRangeMode = hdrDynamicRangeMode,
            availableColorCorrectionModes = cachedColorCorrectionModes,
            strictProcessedVideo = strictHighEndEnabled(),
            rawVideoCaptureEnabled = rawVideoCaptureEnabled,
            manualColorTransformActive = manualColorTransformActive,
            preferFastForHdr = CameraHdrColorFastModeFlag.enabled
        )
        hdrColorCorrectionMode?.let { colorCorrectionMode ->
            builder[CaptureRequest.COLOR_CORRECTION_MODE] = colorCorrectionMode
        }
        maybeLogHdrColorRequest(colorStabilityPlan, whiteBalancePlan, hdrColorCorrectionMode)
        maybeLogVideoColorStabilityRequest(
            videoRecordingRequestActive,
            colorStabilityPlan,
            whiteBalancePlan
        )

        val streamFpsOverride = resolveCurrentStreamBasedFpsOverridePlan()
        val fpsRequest = resolveCurrentFpsRequestPlan(
            videoRecordingRequestActive = videoRecordingRequestActive,
            streamBasedFpsOverride = streamFpsOverride,
            antibandingMode = requestPlan.antibandingMode
        )
        fpsRequest.aeTargetFpsRange?.let { range ->
            builder[CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE] = Range(range.lower, range.upper)
        }
        val streamFpsRequestKeysApplied = applyStreamBasedFpsKeys(
            builder = builder,
            plan = streamFpsOverride,
            sessionParameters = false
        )
        lastAppliedStreamBasedFpsOverridePlan = streamFpsOverride
        lastAppliedFpsRequestPlan = fpsRequest
        maybeLogStreamBasedFpsOverride(
            plan = streamFpsOverride,
            requestKeysApplied = streamFpsRequestKeysApplied,
            sessionKeysApplied = false
        )
        requestPlan.antibandingMode?.let { mode ->
            builder[CaptureRequest.CONTROL_AE_ANTIBANDING_MODE] = mode
        }

        // X1/R2: idle preview only takes the extreme-detail manual-exposure posture once AE has
        // produced metered values, so it never hits the ISO-ceiling bootstrap branch (which would
        // cause a cold-start brightness pop on preview that recording does not exhibit). The record
        // path is unchanged and keeps its bootstrap behavior.
        val extremeDetailMeteredReady = (latest?.iso ?: 0) > 0 && (latest?.exposureNs ?: 0L) > 0L
        val extremeDetailFallbackExposureActive =
            CameraExtremeDetailFallbackExposurePolicy.isGateActive(
                videoRenderContractActive = videoRenderContractActive,
                videoRecordingRequestActive = videoRecordingRequestActive,
                meteredReady = extremeDetailMeteredReady,
                rawVideoCaptureEnabled = rawVideoCaptureEnabled,
                encodedVideoStrict =
                    requestPlan.noiseCleanupProfile == Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT,
                extremeDetailProfile = selectedVideoDetailProfile == VideoDetailProfile.EXTREME_DETAIL,
                recordingRequiresMetered = true
            )
        val extremeDetailFallbackExposurePlan = CameraExtremeDetailFallbackExposurePolicy.resolve(
            active = extremeDetailFallbackExposureActive,
            supportsManualSensor = supportsManualSensor,
            userManualIso = manIso,
            userManualExposureNs = manExpNs,
            latestIso = latest?.iso,
            latestExposureNs = latest?.exposureNs ?: 0L,
            frameDurationNs = fpsRequest.frameDurationNs,
            exposureRange = CameraLongRange(exposureRange.lower, exposureRange.upper),
            isoRange = CameraIntRange(effectiveIsoRange.lower, effectiveIsoRange.upper)
        )
        val extremeDetailFallbackLogSignature =
            "$extremeDetailFallbackExposureActive|" +
                "$supportsManualSensor|" +
                "$selectedVideoDetailProfile|" +
                "$rawVideoCaptureEnabled|" +
                "${requestPlan.noiseCleanupProfile}|" +
                "${manIso > 0}|" +
                "${manExpNs > 0L}|" +
                "${extremeDetailFallbackExposurePlan.activateManualExposure}|" +
                extremeDetailFallbackExposurePlan.reason
        if (!forStillCapture &&
            videoRenderContractActive &&
            lastExtremeDetailFallbackExposureLogSignature != extremeDetailFallbackLogSignature
        ) {
            lastExtremeDetailFallbackExposureLogSignature = extremeDetailFallbackLogSignature
            Log.i(
                TAG,
                "Extreme Detail fallback exposure decision active=$extremeDetailFallbackExposureActive " +
                    "supportsManualSensor=$supportsManualSensor " +
                    "detailProfile=$selectedVideoDetailProfile raw=$rawVideoCaptureEnabled " +
                    "noiseProfile=${requestPlan.noiseCleanupProfile} manIso=$manIso manExposureNs=$manExpNs " +
                    "latestIso=${latest?.iso ?: "none"} latestExposureNs=${latest?.exposureNs ?: 0L} " +
                    "frameDurationNs=${fpsRequest.frameDurationNs} isoRange=${effectiveIsoRange.lower}-${effectiveIsoRange.upper} " +
                    "exposureRange=${exposureRange.lower}-${exposureRange.upper} " +
                    "activateManual=${extremeDetailFallbackExposurePlan.activateManualExposure} " +
                    "reason=${extremeDetailFallbackExposurePlan.reason} " +
                    "iso=${extremeDetailFallbackExposurePlan.iso} " +
                    "exposureNs=${extremeDetailFallbackExposurePlan.exposureNs}"
            )
        }
        if (extremeDetailFallbackExposurePlan.activateManualExposure) {
            appliedProcessedVideoQualityEvidence = appliedProcessedVideoQualityEvidence + mapOf(
                "extremeDetailFallbackExposure" to "true",
                "extremeDetailFallbackExposureReason" to extremeDetailFallbackExposurePlan.reason,
                "extremeDetailFallbackIso" to extremeDetailFallbackExposurePlan.iso.toString(),
                "extremeDetailFallbackIsoCeiling" to extremeDetailFallbackExposurePlan.isoCeiling.toString(),
                "extremeDetailFallbackExposureNs" to extremeDetailFallbackExposurePlan.exposureNs.toString()
            )
        }
        val cinematicExposurePlan = CameraCinematicExposurePolicy.resolve(
            cinematicActive = isCinemaLowProcessingRequestActive(),
            supportsManualSensor = supportsManualSensor,
            requestedIso = manIso,
            requestedExposureNs = manExpNs,
            latestIso = latest?.iso,
            targetFps = targetCameraFps
        )
        val extremeDetailFallbackManualExposure =
            extremeDetailFallbackExposurePlan.activateManualExposure
        val v20TimingBlockReason = when {
            processedVideoPlan == null -> "NO_PROCESSED_VIDEO_PLAN"
            // Eligible when EITHER the high-motion short-shutter OR the low-noise long-shutter posture
            // requested a timing key (both surface as maxExposureTimeNs); block only when neither did.
            !processedVideoPlan.antiMotionBlurEnabled &&
                processedVideoPlan.maxExposureTimeNs == null -> "NOT_REQUESTED"
            !videoRecordingRequestActive -> "NOT_RECORDING"
            rawVideoCaptureEnabled -> "RAW_VIDEO"
            manIso > 0 || manExpNs > 0L -> "USER_MANUAL_SENSOR"
            hdrDynamicRangeMode != HdrDynamicRangeMode.SDR ||
                previewLogLookEnabled ||
                previewHdrLookEnabled ||
                previewRawLookEnabled -> "NDR_LOG_OR_RAW_LOOK"
            extremeDetailFallbackManualExposure -> "EXTREME_DETAIL_MANUAL_EXPOSURE"
            cinematicExposurePlan.activateManualExposure -> "CINEMATIC_MANUAL_EXPOSURE"
            else -> null
        }
        if (processedVideoPlan != null) {
            appliedProcessedVideoQualityEvidence = appliedProcessedVideoQualityEvidence + mapOf(
                "v20AntiMotionBlurEligible" to (v20TimingBlockReason == null).toString(),
                "v20AntiMotionBlurApplicationStatus" to
                    (v20TimingBlockReason?.let { "BLOCKED:$it" } ?: "ELIGIBLE")
            )
        }
        if (supportsManualSensor && (
                extremeDetailFallbackManualExposure ||
                    cinematicExposurePlan.activateManualExposure ||
                    manIso > 0 ||
                    manExpNs > 0L
                )
        ) {
            val resolvedIso = if (extremeDetailFallbackManualExposure) {
                extremeDetailFallbackExposurePlan.iso
            } else if (cinematicExposurePlan.activateManualExposure) {
                cinematicExposurePlan.iso
            } else {
                if (manIso > 0) manIso else (latest?.iso ?: 100)
            }
            val resolvedExp = if (extremeDetailFallbackManualExposure) {
                extremeDetailFallbackExposurePlan.exposureNs
            } else if (cinematicExposurePlan.activateManualExposure) {
                cinematicExposurePlan.exposureNs
            } else {
                if (manExpNs > 0L) manExpNs else (latest?.exposureNs ?: 33333333L)
            }
            val baseManualFpsRequest = resolveCurrentFpsRequestPlan(
                videoRecordingRequestActive = videoRecordingRequestActive,
                requestedExposureNs = resolvedExp,
                antibandingMode = requestPlan.antibandingMode,
                maximumShutterAngleDegrees = if (extremeDetailFallbackManualExposure) 360f else null
            )
            // EV ANCHOR for LOG/RAW cinematic AUTO exposure: capture the neutral (first-activation,
            // AE-metered) ISO once and drive EV from it, so the dial is repeatable and EV=0 restores the
            // metered look. Without this, resolvedIso == the AE-off feedback ISO we last set, so each EV
            // change compounds. Extreme-detail fallback and user-manual-ISO keep their own base (reset).
            val cinemaAutoManualExposure =
                cinematicExposurePlan.activateManualExposure &&
                    !extremeDetailFallbackManualExposure &&
                    manIso <= 0
            if (cinemaAutoManualExposure) {
                if (cinemaEvNeutralBaselineIso <= 0) {
                    cinemaEvNeutralBaselineIso = resolvedIso.coerceAtLeast(1)
                }
            } else {
                cinemaEvNeutralBaselineIso = -1
            }
            val cinemaEvBaseIso =
                if (cinemaAutoManualExposure && cinemaEvNeutralBaselineIso > 0) {
                    cinemaEvNeutralBaselineIso
                } else {
                    resolvedIso
                }
            val manualEvCompensation = if (
                extremeDetailFallbackManualExposure ||
                cinematicExposurePlan.activateManualExposure
            ) {
                CameraCinematicExposurePolicy.applyLockedShutterEv(
                    baseIso = cinemaEvBaseIso,
                    lockedExposureNs = baseManualFpsRequest.exposureNs,
                    evIndex = evIndex.coerceIn(evRange.lower, evRange.upper),
                    evStepStops = evCompensationStepStops,
                    isoRange = CameraIntRange(effectiveIsoRange.lower, effectiveIsoRange.upper),
                    exposureRange = CameraLongRange(exposureRange.lower, exposureRange.upper),
                    frameExposureBudgetNs = baseManualFpsRequest.frameDurationNs
                )
            } else {
                ManualSensorEvCompensationPolicy.resolve(
                    baseIso = resolvedIso,
                    baseExposureNs = baseManualFpsRequest.exposureNs,
                    evIndex = evIndex.coerceIn(evRange.lower, evRange.upper),
                    evStepStops = evCompensationStepStops,
                    isoRange = CameraIntRange(effectiveIsoRange.lower, effectiveIsoRange.upper),
                    exposureRange = CameraLongRange(exposureRange.lower, exposureRange.upper),
                    frameExposureBudgetNs = baseManualFpsRequest.frameDurationNs
                )
            }
            val manualFpsRequest = resolveCurrentFpsRequestPlan(
                videoRecordingRequestActive = videoRecordingRequestActive,
                requestedExposureNs = manualEvCompensation.exposureNs,
                antibandingMode = requestPlan.antibandingMode,
                maximumShutterAngleDegrees = if (extremeDetailFallbackManualExposure) 360f else null
            )
            val effectiveIsoRequest = EffectiveIsoRequestPolicy.resolve(
                requestedIso = manualEvCompensation.iso,
                sensorLowerIso = isoRange.lower,
                sensorUpperIso = isoRange.upper,
                boostLower = postRawSensitivityBoostRange.lower,
                boostUpper = postRawSensitivityBoostRange.upper
            )
            
            builder[CaptureRequest.CONTROL_AE_MODE] = CaptureRequest.CONTROL_AE_MODE_OFF
            lastRequestedNdrExposureCompensationIndex = null
            builder[CaptureRequest.SENSOR_SENSITIVITY] = effectiveIsoRequest.sensorIso
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder[CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST] =
                    effectiveIsoRequest.postRawSensitivityBoost
            }
            builder[CaptureRequest.SENSOR_EXPOSURE_TIME] = manualFpsRequest.exposureNs
                .coerceIn(exposureRange.lower, exposureRange.upper)
            builder[CaptureRequest.SENSOR_FRAME_DURATION] = manualFpsRequest.frameDurationNs
                .coerceIn(exposureRange.lower, maxFrameDurationNs.coerceAtLeast(exposureRange.lower))
        } else {
            val currentFlash = flashMode
            if (currentFlash == FlashMode.AUTO) {
                builder[CaptureRequest.CONTROL_AE_MODE] = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            } else if (currentFlash == FlashMode.ON) {
                builder[CaptureRequest.CONTROL_AE_MODE] = CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            } else {
                builder[CaptureRequest.CONTROL_AE_MODE] = CaptureRequest.CONTROL_AE_MODE_ON
            }
            
            if (currentFlash == FlashMode.TORCH) {
                builder[CaptureRequest.FLASH_MODE] = CaptureRequest.FLASH_MODE_TORCH
            } else {
                builder[CaptureRequest.FLASH_MODE] = CaptureRequest.FLASH_MODE_OFF
            }
            // NDR10+/NIVUKX highlight protection: bias auto-exposure a touch darker so the sensor keeps
            // highlight headroom (the tone operator lifts the midtones/shadows back in the grade). SDR is
            // untouched (byte-for-byte today). User manual EV still rides on top via [evIndex].
            val hdrHighlightProtectActive =
                hdrDynamicRangeMode != HdrDynamicRangeMode.SDR && NdrHighlightProtectFlag.enabled
            val requestedExposureCompensation = if (hdrHighlightProtectActive) {
                // Fix B: fold the closed-loop adaptive extra bias into the -0.67 floor. When the scene
                // shows real highlight clipping the total protection deepens. If the optional analysis
                // surface is absent/stale, use a conservative fixed fallback instead of silently losing
                // protection. `hdrAdaptiveHighlightExtraStops` is <= 0 and gated by its own flag.
                val adaptiveExtra =
                    if (NdrAdaptiveHighlightFlag.enabled) {
                        NdrAdaptiveHighlightExposurePolicy.resolveAppliedExtraStops(
                            measuredExtraStops = hdrAdaptiveHighlightExtraStops,
                            liveMetricsFresh = hdrExposureMetricsFresh()
                        )
                    } else {
                        0f
                    }
                hdrAdaptiveHighlightAppliedStops = adaptiveExtra
                HdrHighlightProtectExposurePolicy.resolveExposureCompensationIndex(
                    baseEvIndex = evIndex,
                    evStepStops = evCompensationStepStops,
                    evLowerIndex = evRange.lower,
                    evUpperIndex = evRange.upper,
                    highlightProtectStops =
                        HdrHighlightProtectExposurePolicy.DEFAULT_HIGHLIGHT_PROTECT_STOPS + adaptiveExtra
                )
            } else {
                hdrAdaptiveHighlightAppliedStops = 0f
                evIndex.coerceIn(evRange.lower, evRange.upper)
            }
            builder[CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION] = requestedExposureCompensation
            lastRequestedNdrExposureCompensationIndex =
                requestedExposureCompensation.takeIf { hdrHighlightProtectActive }
            appliedProcessedVideoQualityEvidence = appliedProcessedVideoQualityEvidence + mapOf(
                "ndrHighlightProtectActive" to hdrHighlightProtectActive.toString(),
                "ndrAdaptiveHighlightActive" to
                    (hdrHighlightProtectActive && NdrAdaptiveHighlightFlag.enabled).toString(),
                "ndrAdaptiveHighlightExtraStops" to hdrAdaptiveHighlightAppliedStops.toEvidenceDecimal(),
                "ndrExposureAnalysisFresh" to hdrExposureMetricsFresh().toString(),
                "ndrRedClipHighlightActive" to
                    (hdrHighlightProtectActive && NdrRedClipHighlightFlag.enabled).toString()
            )

            // NDR10+/NIVUKX motion-blur elimination: a closed-loop shutter cap that keeps AE adaptive but
            // pins the exposure time at the anti-blur cap ONLY when the metered (dim-scene) exposure would
            // otherwise run long enough to smear. Bright scenes stay in plain AUTO so highlights are safe.
            applyHdrAntiMotionBlurShutter(
                builder = builder,
                frameDurationNs = fpsRequest.frameDurationNs,
                exposureLowerNs = exposureRange.lower,
                exposureUpperNs = exposureRange.upper,
                isoFloor = effectiveIsoRange.lower
            )

            val videoSensorTimingPlan = CameraVideoSensorTimingPolicy.resolve(
                recordingActive = videoRecordingRequestActive,
                strictHighEndEnabled = strictHighEndEnabled(),
                supportsManualSensor = supportsManualSensor,
                manualSensorTimingActive = false,
                fpsRequest = fpsRequest,
                maxFrameDurationNs = maxFrameDurationNs
            )
            videoSensorTimingPlan.sensorFrameDurationNs?.let { frameDurationNs ->
                // F5: under AUTO AE an explicit SENSOR_FRAME_DURATION freezes the vivo CONTINUOUS_VIDEO AF
                // servo while recording (this value is emitted only while recording). Skip it when the
                // AF-recording-unlock flag is on — cadence is held by CONTROL_AE_TARGET_FPS_RANGE below.
                if (!(AfRecordingUnlockFlag.enabled && videoRecordingRequestActive)) {
                    builder[CaptureRequest.SENSOR_FRAME_DURATION] = frameDurationNs
                }
            }
            if (processedVideoPlan != null && v20TimingBlockReason == null) {
                val timingEvidence = applyV20ProcessedVideoTiming(
                    builder = builder,
                    plan = processedVideoPlan
                )
                appliedProcessedVideoQualityEvidence =
                    appliedProcessedVideoQualityEvidence + timingEvidence
            }
            if (videoRecordingRequestActive) {
                fpsRequest.aeTargetFpsRange?.let { range ->
                    builder[CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE] =
                        Range(range.lower, range.upper)
                }
                // F5: keep the AE FPS range (holds recording cadence) but skip the explicit
                // SENSOR_FRAME_DURATION under AUTO AE while recording so the AF servo is not frozen.
                videoSensorTimingPlan.sensorFrameDurationNs?.let { frameDurationNs ->
                    if (!AfRecordingUnlockFlag.enabled) {
                        builder[CaptureRequest.SENSOR_FRAME_DURATION] = frameDurationNs
                    }
                }
            }
        }

        builder[CaptureRequest.CONTROL_AE_LOCK] = colorStabilityPlan.aeLockRequested
        builder[CaptureRequest.CONTROL_AWB_LOCK] = whiteBalancePlan.awbLock
        applyTapFocusMeteringUnlock(builder, colorStabilityPlan.tapFocusMeteringUnlockAllowed)
        // Experimental active color-drift enforcement: applied LAST so it is the final authority on
        // the color-correction triplet (the HDR color-correction block above can otherwise reset the
        // mode). No-op unless the runtime flag is enabled.
        maybeApplyColorDriftOverride(builder, colorStabilityPlan, manualColorTransformActive)
    }

    /**
     * Realise a manual (Kelvin, tint) white balance in `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` —
     * the path used on sensors without the API-36 hardware CCT correction.
     *
     * Device-true ([DeviceTrueWhiteBalanceFlag] ON + the sensor publishes usable DNG calibration +
     * the solve succeeds): [CaptureRequest.COLOR_CORRECTION_GAINS] are derived from the sensor's own
     * dual-illuminant color matrices ([DeviceColorCalibration]/[DngWhiteBalanceMath]) so the chosen
     * white neutralises exactly on this sensor, and the paired neutral-preserving
     * [CaptureRequest.COLOR_CORRECTION_TRANSFORM] is set from the same solve. When the solve cannot
     * derive a transform (no forward matrix + singular color matrix) we fall back to the HAL's
     * last-measured CCM — the Blackmagic "harvest the live transform" trick — and only if that too
     * is absent do we leave the transform to the HAL.
     *
     * Fallback (flag OFF / no calibration / solve failed): the exact legacy Tanner-Helland gains
     * with no explicit transform — byte-for-byte today's behaviour, so this is a strict no-downgrade
     * upgrade.
     */
    private fun applyManualTransformMatrixWhiteBalance(
        builder: CaptureRequest.Builder,
        requestedKelvin: Int,
        requestedTint: Int
    ) {
        val deviceTrue = if (DeviceTrueWhiteBalanceFlag.enabled) {
            deviceColorCalibration?.solve(requestedKelvin, requestedTint)
        } else {
            null
        }
        if (deviceTrue != null) {
            val green = 1f
            builder[CaptureRequest.COLOR_CORRECTION_GAINS] =
                RggbChannelVector(deviceTrue.rGain, green, green, deviceTrue.bGain)
            val ccm = deviceTrue.colorCorrectionTransform?.let(::toColorSpaceTransform)
                ?: latestMeasuredColorTransform
            if (ccm != null) {
                builder[CaptureRequest.COLOR_CORRECTION_TRANSFORM] = ccm
            }
            maybeLogDeviceTrueWhiteBalance(requestedKelvin, requestedTint, deviceTrue, ccm != null)
            return
        }
        builder[CaptureRequest.COLOR_CORRECTION_GAINS] =
            WhiteBalanceConversion.kelvinToRggb(
                requestedKelvin,
                CinemaAutoWhiteBalanceRequestPolicy.tintToGreenMagentaScale(requestedTint),
                characteristics
            )
    }

    /** Row-major 3×3 [FloatArray] → Camera2 [ColorSpaceTransform] (rationals over a fixed denominator). */
    private fun toColorSpaceTransform(m: FloatArray): ColorSpaceTransform {
        val den = 1_000_000
        val elements = Array(9) { i -> Rational((m[i] * den).roundToInt(), den) }
        return ColorSpaceTransform(elements)
    }

    private fun maybeLogDeviceTrueWhiteBalance(
        kelvin: Int,
        tint: Int,
        wb: DngWhiteBalanceMath.DeviceWhiteBalance,
        transformApplied: Boolean
    ) {
        val signature = "$kelvin/$tint/${wb.rGain}/${wb.bGain}/$transformApplied"
        if (signature == lastLoggedDeviceTrueWbSignature) return
        lastLoggedDeviceTrueWbSignature = signature
        Log.i(
            TAG,
            "Device-true manual WB kelvin=$kelvin tint=$tint " +
                "gains(r=${wb.rGain}, b=${wb.bGain}) transform=$transformApplied"
        )
    }

    /**
     * P1.6: the governor's last stabilized white point expressed as (Kelvin, tint), for the
     * shot-stable AWB_OFF fallback so it holds the last measured device-true white instead of a
     * hardcoded 5500K. Returns `null` before any usable measurement (⇒ caller uses 5500K/0).
     */
    private fun deviceTrueShotStableFallbackWhitePoint(): Pair<Int, Int>? {
        val uv = colorDriftGovernor.stabilizedWhitePoint ?: return null
        val xy = WhiteBalanceChromaticity.uvToXy(uv) ?: return null
        val (kelvin, tint) = DngWhiteBalanceMath.xyToKelvinTint(xy[0].toDouble(), xy[1].toDouble())
        if (!kelvin.isFinite() || !tint.isFinite()) return null
        return kelvin.roundToInt().coerceIn(manualWhiteBalanceRange.lower, manualWhiteBalanceRange.upper) to
            tint.roundToInt().coerceIn(MANUAL_WB_TINT_MIN, MANUAL_WB_TINT_MAX)
    }

    /**
     * Capture-time colour facts for the record-then-develop colour sidecar: the last device-measured
     * white-balance gains (RggbChannelVector → R/Gr/Gb/B) and the drift-governor's stabilised white point
     * as (Kelvin, tint). Returns null when no measured gains are available yet (fresh session / RAW-locked),
     * so the develop pass falls back to its neutral defaults. Snapshot at record-stop BEFORE any teardown.
     */
    data class CaptureColorFacts(
        val wbR: Float,
        val wbGr: Float,
        val wbGb: Float,
        val wbB: Float,
        val kelvin: Int?,
        val tint: Int?
    )

    fun captureColorFactsSnapshot(): CaptureColorFacts? {
        val gains = latestMeasuredColorGains ?: return null
        val whitePoint = deviceTrueShotStableFallbackWhitePoint()
        return CaptureColorFacts(
            wbR = gains.red,
            wbGr = gains.greenEven,
            wbGb = gains.greenOdd,
            wbB = gains.blue,
            kelvin = whitePoint?.first,
            tint = whitePoint?.second
        )
    }

    /**
     * Active enforcement of the color-drift budget (default-ON via [ColorDriftEnforcementFlag]). When
     * enabled AND we are in a lockable-auto SDR shot (never RAW or manual WB), holds the temporally
     * stabilized white point so colour does not wander frame-to-frame.
     *
     * vivo-safe strategy: in the common within-budget case the converged white point is held with
     * CONTROL_AWB_LOCK (vendor colour pipeline stays live — no desaturation). Only when the governor
     * actually clamps drift do we fall to a COLOR_CORRECTION_TRANSFORM_MATRIX correction for those
     * transitional frames. This avoids the "gray/washed SDR preview" regression that resulted from
     * forcing TRANSFORM_MATRIX with the reported bare 3x3 CCM on every stable frame.
     */
    private fun maybeApplyColorDriftOverride(
        builder: CaptureRequest.Builder,
        plan: VideoColorStabilityPlan,
        manualColorTransformActive: Boolean
    ) {
        val engaged = ColorDriftEnforcementFlag.enabled &&
            !rawVideoCaptureEnabled &&
            !manualColorTransformActive &&
            plan.requiresLockableAutoWhiteBalance &&
            latestMeasuredColorTransform != null &&
            latestMeasuredColorGains != null
        if (!engaged) {
            if (colorDriftOverrideActive) {
                colorDriftOverrideActive = false
                Log.i(TAG, "ColorDriftOverride disengaged")
            }
            return
        }
        val ccm = latestMeasuredColorTransform ?: return
        val measured = latestMeasuredColorGains ?: return
        // vivo-safe white-point hold (fixes the intermittent SDR "gray/washed" preview):
        //
        // Forcing COLOR_CORRECTION_MODE_TRANSFORM_MATRIX with the HAL's *reported* bare 3x3 CCM drops the
        // vendor's proprietary AUTO colour rendering (memory-colour / 3D-LUT saturation the reported matrix
        // does NOT carry), so on vivo the frame desaturates to gray the moment the shot stabilizes and the
        // governor engages. That is a quality DOWNGRADE, not stabilization.
        //
        // In the common (within-budget) case we now hold the converged white point with CONTROL_AWB_LOCK
        // and leave COLOR_CORRECTION_MODE at the vendor default, so the full vendor colour pipeline stays
        // live — no gray, white point still frozen. Only when the governor actually had to CLAMP drift do
        // we fall to the TRANSFORM_MATRIX correction (the rare transitional frames), matching the original
        // corrective intent without paying the desaturation cost on every stable frame.
        if (colorDriftGovernor.lastFrameClamped) {
            val normalized = colorDriftGovernor.stabilizedGains()
            val green = latestMeasuredGreenGain
            val gains = if (normalized != null && green > 0f) {
                // Rescale the chromaticity-normalized (green=1) stabilized gains back to the HAL's
                // measured absolute scale so overall channel balance/brightness is preserved.
                RggbChannelVector(normalized[0] * green, green, green, normalized[3] * green)
            } else {
                measured
            }
            builder[CaptureRequest.COLOR_CORRECTION_MODE] =
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            builder[CaptureRequest.COLOR_CORRECTION_GAINS] = gains
            builder[CaptureRequest.COLOR_CORRECTION_TRANSFORM] = ccm
        } else {
            // Within budget: freeze AWB to hold the converged white point while preserving vendor colour.
            builder[CaptureRequest.CONTROL_AWB_LOCK] = true
        }
        if (!colorDriftOverrideActive) {
            colorDriftOverrideActive = true
            Log.i(
                TAG,
                "ColorDriftOverride engaged label=${plan.evidenceLabel} " +
                    "mode=${if (colorDriftGovernor.lastFrameClamped) "TRANSFORM_MATRIX(clamped)" else "AWB_LOCK(hold)"} " +
                    "budgetUv=${plan.maxFrameToFrameColorDriftUv}"
            )
        }
    }

    @SuppressLint("NewApi", "InlinedApi")
    private fun applyV20ProcessedVideoTiming(
        builder: CaptureRequest.Builder,
        plan: CameraProcessedVideoQualityRequestPlan
    ): Map<String, String> {
        var requestedKeyCount = 0
        var appliedKeyCount = 0
        val evidence = mutableMapOf<String, String>()

        plan.motionStableAeTargetFpsRange?.let { range ->
            requestedKeyCount++
            val applied =
                supportsCaptureRequestKey(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE) &&
                    runCatching {
                        builder[CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE] =
                            Range(range.lower, range.upper)
                    }.onFailure { error ->
                        Log.w(TAG, "Failed to apply V20 AE FPS range $range", error)
                    }.isSuccess
            if (applied) appliedKeyCount++
            evidence["v20MotionStableAeTargetFpsRangeApplied"] =
                if (applied) range.evidenceValue() else "unsupported_or_failed"
        }

        plan.motionStableFrameDurationNs?.let { requestedFrameDurationNs ->
            requestedKeyCount++
            val frameDurationNs = requestedFrameDurationNs.coerceIn(
                exposureRange.lower.coerceAtLeast(1L),
                maxFrameDurationNs.coerceAtLeast(exposureRange.lower.coerceAtLeast(1L))
            )
            // F5: under AUTO AE an explicit SENSOR_FRAME_DURATION freezes the vivo AF servo while
            // recording. Skip it when the AF-recording-unlock flag is on; the V20 AE FPS range above
            // (motionStableAeTargetFpsRange) already holds recording cadence.
            val applied =
                !AfRecordingUnlockFlag.enabled &&
                    supportsCaptureRequestKey(CaptureRequest.SENSOR_FRAME_DURATION) &&
                    runCatching {
                        builder[CaptureRequest.SENSOR_FRAME_DURATION] = frameDurationNs
                    }.onFailure { error ->
                        Log.w(TAG, "Failed to apply V20 frame duration $frameDurationNs", error)
                    }.isSuccess
            if (applied) appliedKeyCount++
            evidence["v20SensorFrameDurationNsApplied"] =
                if (applied) frameDurationNs.toString()
                else if (AfRecordingUnlockFlag.enabled) "skipped_af_recording_unlock"
                else "unsupported_or_failed"
        }

        plan.maxExposureTimeNs?.let { requestedExposureTimeNs ->
            requestedKeyCount++
            val exposureTimeNs = requestedExposureTimeNs.coerceIn(
                exposureRange.lower.coerceAtLeast(1L),
                exposureRange.upper.coerceAtLeast(exposureRange.lower.coerceAtLeast(1L))
            )
            val exposurePrioritySupported =
                Build.VERSION.SDK_INT >= 36 &&
                    supportsCaptureRequestKey(CaptureRequest.CONTROL_AE_PRIORITY_MODE) &&
                    supportsCaptureRequestKey(CaptureRequest.SENSOR_EXPOSURE_TIME)
            val applied = exposurePrioritySupported && runCatching {
                builder[CaptureRequest.CONTROL_AE_PRIORITY_MODE] =
                    CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY
                builder[CaptureRequest.SENSOR_EXPOSURE_TIME] = exposureTimeNs
            }.onFailure { error ->
                runCatching {
                    builder[CaptureRequest.CONTROL_AE_PRIORITY_MODE] =
                        CameraMetadata.CONTROL_AE_PRIORITY_MODE_OFF
                }
                Log.w(TAG, "Failed to apply V20 AE exposure priority $exposureTimeNs", error)
            }.isSuccess
            if (applied) appliedKeyCount++
            evidence["v20ExposureTimePriorityNsApplied"] =
                if (applied) exposureTimeNs.toString() else "unsupported_or_failed"
        }

        evidence["v20AntiMotionBlurApplicationStatus"] = when {
            requestedKeyCount == 0 -> "NO_TIMING_KEYS_REQUESTED"
            appliedKeyCount == requestedKeyCount -> "APPLIED"
            appliedKeyCount > 0 -> "PARTIAL:$appliedKeyCount/$requestedKeyCount"
            else -> "UNSUPPORTED"
        }
        evidence["v20AntiMotionBlurApplied"] = (appliedKeyCount > 0).toString()
        return evidence
    }

    private fun supportsCaptureRequestKey(key: CaptureRequest.Key<*>): Boolean =
        cachedCaptureRequestKeysByName.containsKey(key.name)

    private fun Float.sanitizedSceneRisk(): Float =
        if (isFinite()) coerceIn(0f, 1f) else 0f

    private fun Float.toEvidenceDecimal(): String =
        String.format(Locale.US, "%.3f", this)

    private fun Double.toEvidenceDecimal(): String =
        String.format(Locale.US, "%.3f", this)

    private fun Int?.evidenceValue(): String = this?.toString() ?: "unset"

    private fun Long?.evidenceValue(): String = this?.toString() ?: "unset"

    private fun CameraIntRange?.evidenceValue(): String =
        this?.let { "${it.lower}-${it.upper}" } ?: "unset"

    private fun maybeLogHdrColorRequest(
        colorStabilityPlan: VideoColorStabilityPlan,
        whiteBalancePlan: CinemaAutoWhiteBalanceRequestPlan,
        colorCorrectionMode: Int?
    ) {
        if (hdrDynamicRangeMode == HdrDynamicRangeMode.SDR) return
        val signature = listOf(
            hdrDynamicRangeMode.name,
            whiteBalancePlan.awbMode,
            whiteBalancePlan.awbLock,
            colorStabilityPlan.aeLockRequested,
            colorCorrectionMode,
            manWbK,
            manWbTint
        ).joinToString(separator = "|")
        if (lastLoggedHdrColorRequestSignature == signature) return
        lastLoggedHdrColorRequestSignature = signature
        Log.i(
            TAG,
                "HDR request color controls dynamicRange=$hdrDynamicRangeMode " +
                "awbMode=${whiteBalancePlan.awbMode} awbLock=${whiteBalancePlan.awbLock} " +
                "aeLock=${colorStabilityPlan.aeLockRequested} colorCorrectionMode=$colorCorrectionMode " +
                "manualKelvin=$manWbK manualTint=$manWbTint"
        )
    }

    private fun maybeLogVideoColorStabilityRequest(
        videoRecordingRequestActive: Boolean,
        colorStabilityPlan: VideoColorStabilityPlan,
        whiteBalancePlan: CinemaAutoWhiteBalanceRequestPlan
    ) {
        if (!videoRecordingRequestActive) return
        val signature = listOf(
            colorStabilityPlan.evidenceLabel,
            colorStabilityPlan.aeLockRequested,
            whiteBalancePlan.awbMode,
            whiteBalancePlan.awbLock,
            whiteBalancePlan.colorTemperatureKelvin,
            colorStabilityPlan.requiresLockableAutoWhiteBalance,
            colorStabilityPlan.sceneAdaptiveColorAllowed,
            colorStabilityPlan.maxFrameToFrameColorDriftUv,
            rawVideoCaptureEnabled,
            npv10TenBitCaptureEnabled
        ).joinToString(separator = "|")
        if (lastLoggedVideoColorStabilitySignature == signature) return
        lastLoggedVideoColorStabilitySignature = signature
        Log.i(
            TAG,
            "Video color stability state=${colorStabilityPlan.evidenceLabel} " +
                "awbMode=${whiteBalancePlan.awbMode} awbLock=${whiteBalancePlan.awbLock} " +
                "manualKelvin=${whiteBalancePlan.colorTemperatureKelvin ?: -1} " +
                "requiresLockableAutoWb=${colorStabilityPlan.requiresLockableAutoWhiteBalance} " +
                "sceneAdaptiveColor=${colorStabilityPlan.sceneAdaptiveColorAllowed} " +
                "maxFrameToFrameColorDriftUv=${colorStabilityPlan.maxFrameToFrameColorDriftUv} " +
                "rawMaster=$rawVideoCaptureEnabled npv10=$npv10TenBitCaptureEnabled"
        )
    }

    private fun maybeLogColorDrift(
        result: TemporalColorStabilizationResult,
        budgetUv: Float
    ) {
        // Edge-trigger: log once when a hunting episode begins (first clamped frame after a calm
        // run) and once when it ends, so a sustained pan does not flood the log every frame.
        val clamped = result.clamped
        if (clamped == colorDriftClampingActive) return
        colorDriftClampingActive = clamped
        if (clamped) {
            Log.i(
                TAG,
                "Color drift clamped: AWB drift exceeded budget — " +
                    "appliedDriftUv=${result.appliedDriftUv} budgetUv=$budgetUv " +
                    "stabilizedUv=(${result.stabilized.u},${result.stabilized.v}) " +
                    "clampedFrames=${colorDriftGovernor.clampedFrameCount}/" +
                    "${colorDriftGovernor.stabilizedFrameCount}"
            )
        } else {
            Log.i(
                TAG,
                "Color drift settled within budget budgetUv=$budgetUv " +
                    "clampedFrames=${colorDriftGovernor.clampedFrameCount}/" +
                    "${colorDriftGovernor.stabilizedFrameCount}"
            )
        }
    }

    override fun refreshFrameGeometry() {
        val sensorRect = activeArray ?: return
        val sensorAspect = CameraSensorInfo.sensorBaseAspectRatio(fullSensorSize())
        val effectiveAspect = CameraControlMath.resolveCaptureRequestAspectRatio(
            sensorAspectRatio = sensorAspect,
            targetOutputAspectRatio = targetOutputAspectRatio,
            includeAspectCrop = false
        )
        
        val cropRect = CameraControlMath.buildAspectAndZoomCropRegion(
            sensorRect = sensorRect,
            targetAspectRatio = effectiveAspect,
            zoom = zoomRatio,
            maxZoom = maxZoom
        )
        val normalizedCrop = NormalizedCropRegion(
            left = (cropRect.left - sensorRect.left).toFloat() / sensorRect.width(),
            top = (cropRect.top - sensorRect.top).toFloat() / sensorRect.height(),
            right = (cropRect.right - sensorRect.left).toFloat() / sensorRect.width(),
            bottom = (cropRect.bottom - sensorRect.top).toFloat() / sensorRect.height()
        )
        frameGeometryFlow.value = FrameGeometry(
            sensorOrientationDegrees = sensorOrientation(),
            deviceRotationDegrees = deviceRotationDegrees,
            finalRotationDegrees = OrientationManager.getSensorToOutputRotation(sensorOrientation(), deviceRotationDegrees, facing),
            lensFacing = facing,
            sensorAspectRatio = sensorAspect,
            outputAspectRatio = effectiveAspect,
            cropRegion = cropRect,
            normalizedCropRegion = normalizedCrop
        )
    }

    fun fullSensorSize(): Size = CameraSensorInfo.fullSensorSize(pixelArraySize, maxRawSize, maxYuvSize, maxPhotoSize)
    fun sensorOrientation(): Int = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    private fun resolveCurrentStreamBasedFpsOverridePlan(
        requestedFpsOverride: Int? = null
    ): CameraStreamBasedFpsOverridePlan =
        CameraStreamBasedFpsPolicy.resolve(
            // NPV10 has no encoder Surface by design: its camera-owned P010 readout is the recording
            // stream. The start path therefore clears recordingEncoderTargetSize before it switches the
            // repeating request to ENCODED_RECORDING_WITH_READOUT. Keep the stream-based FPS contract
            // bound to the real NPV10 capture size so that transition cannot silently relax a fixed
            // 25-25 request back to the public variable 15-30 AE range.
            requestedSize = (
                npv10CaptureTargetSize.takeIf { npv10TenBitCaptureEnabled }
                    ?: recordingEncoderTargetSize
                )?.toCameraStreamSize(),
            requestedFps = requestedFpsOverride ?: requestedCameraFpsHint ?: targetCameraFps,
            publicAeRanges = cachedFpsIntRanges,
            streamBasedEntries = cachedStreamBasedFpsEntries,
            requestKeyNames = cachedCaptureRequestKeysByName.keys,
            sessionKeyNames = cachedSessionRequestKeysByName.keys
        )

    private fun updateRequestedCameraFpsHint(fps: Int) {
        requestedCameraFpsHint = fps
    }

    private fun applyStreamBasedFpsKeys(
        builder: CaptureRequest.Builder,
        plan: CameraStreamBasedFpsOverridePlan,
        sessionParameters: Boolean
    ): Boolean {
        if (!plan.enabled) return false
        val keyMap = if (sessionParameters) {
            cachedSessionRequestKeysByName
        } else {
            cachedCaptureRequestKeysByName
        }
        fun setNumeric(name: String, value: Float): Boolean {
            val key = keyMap[name] ?: return false
            return applyStreamBasedFpsNumericKey(builder, key, name, value, sessionParameters)
        }

        return when (plan.requestMode) {
            CameraStreamBasedFpsRequestMode.STREAM_PREVIEW_VIDEO -> {
                val previewApplied = setNumeric(CameraStreamBasedFpsPolicy.PREVIEW_FPS_KEY_NAME, plan.previewFps.toFloat())
                val videoApplied = setNumeric(CameraStreamBasedFpsPolicy.VIDEO_FPS_KEY_NAME, plan.videoFps.toFloat())
                previewApplied || videoApplied
            }
            CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS -> {
                val dynamicApplied = plan.dynamicFpsConfig?.let {
                    setNumeric(CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME, it)
                } ?: false
                val videoApplied = plan.vendorVideoFrameRate?.let {
                    setNumeric(CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME, it.toFloat())
                } ?: false
                dynamicApplied || videoApplied
            }
            CameraStreamBasedFpsRequestMode.NONE -> false
        }
    }

    private fun applyStreamBasedFpsNumericKey(
        builder: CaptureRequest.Builder,
        key: CaptureRequest.Key<*>,
        name: String,
        value: Float,
        sessionParameters: Boolean
    ): Boolean {
        val kind = if (sessionParameters) "session" else "request"
        val candidates = when (name) {
            CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME ->
                listOf("floatArray", "intArray", "float", "int")
            CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME ->
                listOf("intArray", "floatArray", "int", "float")
            else -> listOf("int", "intArray", "float", "floatArray")
        }
        for (candidate in candidates) {
            val signature = "$kind|$name|$candidate"
            if (disabledStreamFpsKeySignatures.contains(signature)) continue
            val applied = runCatching {
                applyStreamBasedFpsCandidate(builder, key, candidate, value)
            }.onFailure { error ->
                disabledStreamFpsKeySignatures += signature
                Log.w(TAG, "Failed to apply stream FPS $kind key $name candidate=$candidate value=$value", error)
            }.isSuccess
            if (applied) return true
        }
        return false
    }

    private fun applyStreamBasedFpsCandidate(
        builder: CaptureRequest.Builder,
        key: CaptureRequest.Key<*>,
        candidate: String,
        value: Float
    ) {
        @Suppress("UNCHECKED_CAST")
        when (candidate) {
            "int" -> builder.set(key as CaptureRequest.Key<Int>, value.toInt())
            "float" -> builder.set(key as CaptureRequest.Key<Float>, value)
            "intArray" -> builder.set(key as CaptureRequest.Key<IntArray>, intArrayOf(value.toInt()))
            "floatArray" -> builder.set(key as CaptureRequest.Key<FloatArray>, floatArrayOf(value))
        }
    }

    private fun maybeLogStreamBasedFpsOverride(
        plan: CameraStreamBasedFpsOverridePlan,
        requestKeysApplied: Boolean,
        sessionKeysApplied: Boolean
    ) {
        val publicRange = plan.publicAeRange?.let { "${it.lower}-${it.upper}" } ?: "none"
        val signature = listOf(
            plan.enabled,
            plan.appliedFps,
            plan.previewFps,
            plan.videoFps,
            publicRange,
            plan.requestMode,
            plan.reason,
            requestKeysApplied,
            sessionKeysApplied
        ).joinToString("|")
        if (lastLoggedStreamBasedFpsOverrideSignature == signature) return
        lastLoggedStreamBasedFpsOverrideSignature = signature
        Log.i(
            TAG,
            "Stream-based FPS override enabled=${plan.enabled} mode=${plan.requestMode} " +
                "target=$targetCameraFps applied=${plan.appliedFps} preview=${plan.previewFps} " +
                "video=${plan.videoFps} publicAe=$publicRange requestKeysApplied=$requestKeysApplied " +
                "sessionKeysApplied=$sessionKeysApplied reason=${plan.reason}"
        )
    }

    private fun resolveCurrentFpsRequestPlan(
        videoRecordingRequestActive: Boolean,
        requestedExposureNs: Long = resultFlow.value?.exposureNs ?: 0L,
        antibandingMode: Int? = null,
        streamBasedFpsOverride: CameraStreamBasedFpsOverridePlan? = null,
        maximumShutterAngleDegrees: Float? = null
    ): CameraFpsRequestPlan =
        CameraFpsRequestPolicy.resolve(
            requestedFps = CameraFpsRequestPolicy.previewOrRecordRequestedFps(
                videoRecordingRequestActive = videoRecordingRequestActive,
                previewCadenceFps = previewCadenceFps,
                recordFps = targetCameraFps
            ),
            availableRanges = cachedFpsIntRanges,
            requestedExposureNs = requestedExposureNs,
            exposureRange = CameraLongRange(exposureRange.lower, exposureRange.upper),
            antibandingMode = antibandingMode,
            maximumShutterAngleDegrees = maximumShutterAngleDegrees
                ?: if (manExpNs > 0L) 360f else 180f,
            streamBasedFpsOverride = streamBasedFpsOverride
        )

    private fun isCinemaLowProcessingRequestActive(): Boolean =
        cinemaFidelityOverridesEnabled || previewLogLookEnabled || rawVideoCaptureEnabled

    private fun isConsumerAutoVideoRequest(forStillCapture: Boolean): Boolean =
        !forStillCapture &&
            !isCinemaLowProcessingRequestActive() &&
            hdrDynamicRangeMode == HdrDynamicRangeMode.SDR

    private fun manualSensorTimingActive(): Boolean =
        supportsManualSensor && (
            isCinemaLowProcessingRequestActive() ||
                manIso > 0 ||
                manExpNs > 0L
            )

    private fun resolveCamera2PipelineRequestPlan(
        plan: CinemaStreamGraphPlan? = null
    ): Camera2PipelineRequestPlan =
        (plan ?: currentCinemaStreamGraphPlan()).let { streamGraph ->
            Camera2PipelineRequestPolicy.resolve(
                recordingActive = recordingActive,
                videoPreviewActive = videoPreviewActive,
                rawVideoCaptureEnabled = rawVideoCaptureEnabled,
                rawPreviewActive = rawPreviewCaptureEnabled,
                uncompressedReadoutRecording = streamGraph.mode == CinemaStreamGraphMode.ENCODED_CINEMA_WITH_YUV_READOUT,
                previewAnalysisRequested =
                    previewAnalysisRequested && streamGraph.yuvAnalysis.includeInSession,
                hasRawReader = rawReader != null && streamGraph.rawSensor.includeInSession,
                hasYuvReader = yuvReader != null && streamGraph.yuvAnalysis.includeInSession,
                recordingReadoutOverrideSize = recordingReadoutOverrideSize,
                analysisTargetSize = analysisTargetSize,
                readoutSidecarMode = readoutSidecarMode,
                requestedFps = targetCameraFps,
                availableAntibandingModes = cachedAntibandingModes,
                manualExposureActive = manualSensorTimingActive()
            )
        }

    override fun releaseCameraResources(clearPreviewSurface: Boolean) {
        sessionGeneration.incrementAndGet()
        try { captureSession?.close() } catch (e: Exception) {
            Log.w(TAG, "Failed to close capture session during resource release", e)
        }
        try { cameraDevice?.close() } catch (e: Exception) {
            Log.w(TAG, "Failed to close camera device during resource release", e)
        }
        synchronized(sessionCollectionsLock) { configuredSessionSurfaces.clear() }
        closeDeferredReadersSafely("camera resources released")
        awaitFramePipelineIdleForReaderClose("camera resources released")
        closeReaderSetSafely(currentReaderSet())
        captureSession = null
        previewBuilder = null
        previewBuilderHasTargets = false
        cameraDevice = null
        jpegReader = null
        rawReader = null
        yuvReader = null
        hardwareImportReader = null
        recordingActive = false
        unconfiguredRecordSurfaceRecoveryPending = false
        if (clearPreviewSurface) previewSurface = null
    }

    override fun beginCameraRelease(): CameraReleaseTicket {
        val sessions = listOfNotNull(captureSession)
        return cameraReleaseAcknowledgements.begin(cameraDevice, sessions)
    }

    override fun acknowledgeCameraDeviceClosed(device: CameraDevice) {
        cameraReleaseAcknowledgements.acknowledgeDevice(device)
    }

    override fun awaitCameraRelease(ticket: CameraReleaseTicket, timeoutMs: Long): Boolean =
        cameraReleaseAcknowledgements.await(ticket, timeoutMs)

    override fun isCameraReleaseFullyAcknowledged(ticket: CameraReleaseTicket): Boolean =
        cameraReleaseAcknowledgements.isFullyAcknowledged(ticket)

    override fun pendingCameraReleaseDescription(ticket: CameraReleaseTicket): String =
        cameraReleaseAcknowledgements.pendingDescription(ticket)

    override fun startPreview(expectedGeneration: Long, expectedSession: CameraCaptureSession?) {
        val sess = expectedSession ?: captureSession ?: return
        if (expectedGeneration != sessionGeneration.get()) return
        val dev = cameraDevice ?: return

        // Section 5 P0 FIX (Video records as KB-sized unplayable files):
        //
        // The recording encoder consumes its input via Surface. That surface is added to the
        // CameraCaptureSession's outputs by setRecordSurface() -> refreshOutputsAndSession(),
        // BUT it must ALSO be a target of the repeating request for frames to actually flow.
        // Previously this method only added previewSurface; recordSurface was a session output
        // with zero requests targeting it, so the encoder received zero frames, the muxer wrote
        // zero samples, and the resulting .mp4/.mov was just the moov atom with no media data.
        //
        // FIX: when recordSurface is attached, keep the same video-record ISP request contract
        // that armed video preview already uses and add recordSurface as a request target so
        // every camera frame is duplicated into the encoder's input surface. RAW_SENSOR
        // recording remains free to use the record/manual contract because it owns the
        // truthful sensor path.
        val streamGraph = currentCinemaStreamGraphPlan()
        val requestPlan = resolveCamera2PipelineRequestPlan(streamGraph)
        val readoutTargetPlan = requestPlan.targets
        try {
            var targetCount = 0
            val requestTargets = mutableListOf<Surface>()
            val builder = dev.createCaptureRequest(requestPlan.template).apply {
                fun addRepeatingTarget(surface: Surface) {
                    if (requestTargets.any { it === surface }) return
                    addTarget(surface)
                    requestTargets.add(surface)
                    targetCount++
                }

                previewSurface?.let {
                    addRepeatingTarget(it)
                }
                if (!maximumResolutionStillCaptureActive &&
                    (recordingActive || previewContinuityRecordSurfaceEnabled)
                ) {
                    recordSurface?.let {
                        addRepeatingTarget(it)
                    }
                }
                if (readoutTargetPlan.includeRawSensor) {
                    rawReader?.surface?.let {
                        addRepeatingTarget(it)
                    }
                }
                if (readoutTargetPlan.includeYuvReadout) {
                    yuvReader?.surface?.let {
                        addRepeatingTarget(it)
                    }
                }
                if (streamGraph.hardwareImport.includeInSession) {
                    hardwareImportReader?.surface?.let {
                        addRepeatingTarget(it)
                    }
                }
                applyAll(this, pipelineRequestPlan = requestPlan)
            }
            if (targetCount == 0) {
                previewBuilder = null
                previewBuilderHasTargets = false
                Log.w(
                    TAG,
                    "startPreview skipped: no active target surfaces for pipeline=${requestPlan.pipelineMode} " +
                        "preview=${previewSurface != null} record=${recordSurface != null} " +
                        "raw=${rawReader?.surface != null} yuv=${yuvReader?.surface != null} " +
                        "hardwareImport=${hardwareImportReader?.surface != null}"
                )
                return
            }
            if (
                videoPreviewActive ||
                    readoutTargetPlan.hasReadoutSidecar ||
                    recordSurface != null ||
                    streamGraph.hardwareImport.includeInSession
            ) {
                val appliedFpsPlan = lastAppliedFpsRequestPlan
                val appliedAeRange = appliedFpsPlan?.aeTargetFpsRange
                    ?.let { "${it.lower}-${it.upper}" }
                    ?: "none"
                val streamFpsPlan = lastAppliedStreamBasedFpsOverridePlan
                Log.i(
                    TAG,
                        "Camera2 pipeline=${requestPlan.pipelineMode} template=${requestPlan.template} " +
                        "captureIntent=${requestPlan.captureIntent} " +
                        "noiseProfile=${requestPlan.noiseCleanupProfile} " +
                        "fps=$targetCameraFps appliedFps=${appliedFpsPlan?.appliedFps ?: "unknown"} " +
                        "aeFps=$appliedAeRange streamFps=${streamFpsPlan?.enabled ?: false} " +
                        "videoPreview=$videoPreviewActive " +
                        "previewRecord=${previewContinuityRecordSurfaceEnabled} " +
                        "targets=$targetCount visiblePreview=${previewSurface != null} " +
                        "recordSurface=${recordSurface != null} " +
                        "antibanding=${requestPlan.antibandingMode ?: "unchanged"} " +
                        "raw=${readoutTargetPlan.includeRawSensor} " +
                        "yuv=${readoutTargetPlan.includeYuvReadout} " +
                        "hardwareImport=${streamGraph.hardwareImport.includeInSession} " +
                        "mode=$readoutSidecarMode " +
                        "override=${recordingReadoutOverrideSize?.let { "${it.width}x${it.height}" } ?: "none"} " +
                        "encoder=${recordingEncoderTargetSize?.let { "${it.width}x${it.height}" } ?: "none"} " +
                        "analysis=${analysisTargetSize?.let { "${it.width}x${it.height}" } ?: "none"}"
                )
            }
            if (!repeatingTargetsConfiguredForSession(sess, expectedGeneration, requestTargets)) {
                previewBuilder = null
                previewBuilderHasTargets = false
                return
            }
            previewBuilder = builder
            previewBuilderHasTargets = true
            sess.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
            unconfiguredRecordSurfaceRecoveryPending = false
            if (!recordingActive) {
                transitionTo(CameraState.Streaming)
            }
        } catch (e: IllegalStateException) {
            handleStartPreviewIllegalState(
                error = e,
                device = dev,
                session = sess,
                expectedGeneration = expectedGeneration
            )
        } catch (e: Exception) {
            if (recoverUnconfiguredRecordSurfaceIfNeeded(e)) return
            Log.e(TAG, "startPreview failed", e)
        }
    }

    private fun identitySurfaceSet(surfaces: Collection<Surface>): MutableSet<Surface> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Surface, Boolean>()).apply {
            addAll(surfaces)
        }

    private fun repeatingTargetsConfiguredForSession(
        session: CameraCaptureSession,
        expectedGeneration: Long,
        requestTargets: List<Surface>
    ): Boolean {
        if (captureSession !== session || expectedGeneration != sessionGeneration.get()) return false
        val configuredSurfaces = synchronized(sessionCollectionsLock) { configuredSessionSurfaces[session] }
        if (configuredSurfaces == null) {
            Log.w(TAG, "startPreview skipped: no configured surface registry for current Camera2 session; rebuilding")
            rebuildSessionForUnconfiguredRepeatingTargets()
            return false
        }
        val missingTargets = requestTargets.filterNot { configuredSurfaces.contains(it) }
        if (missingTargets.isEmpty()) return true
        Log.w(
            TAG,
            "startPreview skipped: request targets not configured in current Camera2 session " +
                "missing=${missingTargets.joinToString(",") { describeSessionSurfaceForLog(it) }} " +
                "configured=${configuredSurfaces.joinToString(",") { describeSessionSurfaceForLog(it) }}; rebuilding"
        )
        rebuildSessionForUnconfiguredRepeatingTargets()
        return false
    }

    private fun rebuildSessionForUnconfiguredRepeatingTargets() {
        if (unconfiguredRecordSurfaceRecoveryPending || sessionRefreshPending.get()) return
        unconfiguredRecordSurfaceRecoveryPending = true
        refreshOutputsAndSession(rebuildReaders = false, forceRebuild = true) {
            unconfiguredRecordSurfaceRecoveryPending = false
        }
    }

    private fun describeSessionSurfaceForLog(surface: Surface): String =
        "${sessionSurfaceKindFor(surface).name}@${Integer.toHexString(System.identityHashCode(surface))}"

    private fun recoverUnconfiguredRecordSurfaceIfNeeded(error: Throwable): Boolean {
        if (
            !CameraPreviewStartFailurePolicy.isUnconfiguredSurfaceFailure(error) ||
            unconfiguredRecordSurfaceRecoveryPending
        ) {
            return false
        }
        unconfiguredRecordSurfaceRecoveryPending = true
        Log.w(
            TAG,
            "startPreview targeted an output surface not present in the active Camera2 session; forcing session rebuild",
            error
        )
        refreshOutputsAndSession(rebuildReaders = false, forceRebuild = true) {
            unconfiguredRecordSurfaceRecoveryPending = false
        }
        return true
    }

    private fun handleStartPreviewIllegalState(
        error: IllegalStateException,
        device: CameraDevice,
        session: CameraCaptureSession,
        expectedGeneration: Long
    ) {
        val closingCamera = stateFlow.value is CameraState.Closing || stateFlow.value is CameraState.Closed
        val decision = CameraPreviewStartFailurePolicy.resolve(
            error = error,
            sessionGenerationCurrent = expectedGeneration == sessionGeneration.get(),
            sessionStillCurrent = captureSession === session,
            cameraDeviceStillCurrent = cameraDevice === device,
            closingCamera = closingCamera
        )
        if (decision.shouldIgnoreFailure) {
            Log.d(TAG, "startPreview: ${decision.message} (normal during teardown)")
            return
        }
        if (!decision.shouldSurfaceError) {
            Log.w(TAG, "startPreview: ${decision.message}; reopening camera", error)
            sessionGeneration.incrementAndGet()
            if (captureSession === session) captureSession = null
            if (cameraDevice === device) cameraDevice = null
            runCatching { session.close() }
            runCatching { device.close() }
            val onReady = lastOpenReadyCallback
            val onError = lastOpenErrorCallback
            if (!closingCamera && onReady != null && onError != null && previewSurface != null) {
                sessionRecoveryPending = true
                openCamera(onReady, onError)
            } else if (!closingCamera) {
                transitionTo(CameraState.Error(decision.message))
            }
            return
        }
        Log.e(TAG, "startPreview failed", error)
        transitionTo(CameraState.Error(decision.message))
    }

    override fun startPreviewWatchdog() {
        val handler = backgroundHandler ?: return
        previewHealthMonitor.start(
            handler = handler,
            onFreezeDetected = {
                val closingCamera =
                    stateFlow.value is CameraState.Closing || stateFlow.value is CameraState.Closed
                if (closingCamera || cameraDevice == null || previewSurface == null) {
                    return@start false
                }
                if (recordingActive) {
                    val message = "Preview watchdog detected stale preview during recording; preserving active encoder session"
                    Log.w(TAG, message)
                    emitCameraPipelineWarning(CameraPipelineDiagnostics.experimentalFallbackWarning(message))
                    return@start false
                }
                Log.w(TAG, "Preview watchdog rebuilding stale preview session")
                refreshOutputsAndSession(rebuildReaders = false, forceRebuild = true)
                true
            },
            onFatalFreeze = {
                val message = "Preview watchdog recovery limit reached"
                Log.e(TAG, message)
                emitCameraPipelineWarning(CameraPipelineDiagnostics.experimentalFallbackWarning(message))
                if (!recordingActive) {
                    val onReady = lastOpenReadyCallback
                    val onError = lastOpenErrorCallback
                    if (onReady != null && onError != null && previewSurface != null) {
                        sessionRecoveryPending = true
                        sessionGeneration.incrementAndGet()
                        val staleSession = captureSession
                        val staleDevice = cameraDevice
                        captureSession = null
                        cameraDevice = null
                        runCatching { staleSession?.close() }
                            .onFailure { Log.w(TAG, "Failed to close stale session after preview freeze", it) }
                        runCatching { staleDevice?.close() }
                            .onFailure { Log.w(TAG, "Failed to close stale camera after preview freeze", it) }
                        openCamera(onReady, onError)
                    } else {
                        transitionTo(CameraState.Error(message))
                    }
                }
            }
        )
    }

    override fun stopPreviewWatchdog() {
        previewHealthMonitor.stop(backgroundHandler)
    }

    override fun clearPendingInFlightRefresh() {
        sessionRefreshPending.set(false)
        pendingPreviewRebuild.set(false)
        // #11 fix: swap-and-null the request synchronously under the lock (so closeCamera's ordering holds —
        // no trailing refresh can run after this because the field is null), then fire the abandoned
        // refresh's callbacks on the CAMERA thread. The callbacks touch camera/GL state, so running them on
        // the caller's Dispatchers.Default thread ran the GL-preview handoff off-thread / double-released.
        // Fire BOTH lists — the previous code dropped onPreviousSessionClosedCallbacks (a cleanup leak).
        val request = synchronized(trailingRefreshLock) {
            val current = trailingSessionRefreshRequest
            trailingSessionRefreshRequest = null
            current
        } ?: return
        runOnCameraThread {
            request.onConfiguredCallbacks.forEach { callback ->
                runCatching(callback)
                    .onFailure { Log.w(TAG, "Pending session refresh onConfigured callback failed during clear", it) }
            }
            request.onPreviousSessionClosedCallbacks.forEach { callback ->
                runCatching(callback)
                    .onFailure { Log.w(TAG, "Pending session refresh onPreviousSessionClosed callback failed during clear", it) }
            }
        }
    }

    private fun queueTrailingSessionRefresh(
        rebuildReaders: Boolean,
        forceRebuild: Boolean,
        onConfigured: () -> Unit,
        onPreviousSessionClosed: (() -> Unit)?
    ) {
        // #11 fix: mutate the request under the shared lock so a concurrent clearPendingInFlightRefresh
        // (from closeCamera on Dispatchers.Default) cannot iterate the callback lists while we append.
        synchronized(trailingRefreshLock) {
            val request = trailingSessionRefreshRequest
            if (request == null) {
                trailingSessionRefreshRequest = PendingSessionRefreshRequest(
                    rebuildReaders = rebuildReaders,
                    forceRebuild = forceRebuild,
                    onConfiguredCallbacks = mutableListOf(onConfigured),
                    onPreviousSessionClosedCallbacks =
                        mutableListOf<() -> Unit>().apply { onPreviousSessionClosed?.let { add(it) } }
                )
            } else {
                request.rebuildReaders = request.rebuildReaders || rebuildReaders
                request.forceRebuild = request.forceRebuild || forceRebuild
                request.onConfiguredCallbacks += onConfigured
                onPreviousSessionClosed?.let { request.onPreviousSessionClosedCallbacks += it }
            }
        }
        Log.d(
            TAG,
            "Session refresh coalesced behind in-flight rebuild " +
                "rebuildReaders=$rebuildReaders forceRebuild=$forceRebuild"
        )
    }

    private fun runTrailingSessionRefreshIfNeeded() {
        // #11 fix: swap-and-null under the shared lock (synchronized is inline, so the ?: return is a
        // legal non-local return from this function) so clearPendingInFlightRefresh cannot race the field.
        val request = synchronized(trailingRefreshLock) {
            val current = trailingSessionRefreshRequest ?: return
            trailingSessionRefreshRequest = null
            current
        }
        Log.i(
            TAG,
            "Running coalesced trailing session refresh " +
                "rebuildReaders=${request.rebuildReaders} forceRebuild=${request.forceRebuild}"
        )
        refreshOutputsAndSession(
            rebuildReaders = request.rebuildReaders,
            forceRebuild = request.forceRebuild,
            onConfigured = {
                request.onConfiguredCallbacks.forEach { callback ->
                    runCatching(callback)
                        .onFailure { Log.w(TAG, "Trailing session refresh callback failed", it) }
                }
            },
            onPreviousSessionClosed = {
                request.onPreviousSessionClosedCallbacks.forEach { callback ->
                    runCatching(callback)
                        .onFailure { Log.w(TAG, "Trailing previous-session callback failed", it) }
                }
            }
        )
    }

    private fun queueBatchedSessionRefresh(
        rebuildReaders: Boolean,
        forceRebuild: Boolean,
        onConfigured: () -> Unit,
        onPreviousSessionClosed: (() -> Unit)?
    ) {
        synchronized(refreshBatchLock) {
            val request = batchedSessionRefreshRequest
            if (request == null) {
                batchedSessionRefreshRequest = PendingSessionRefreshRequest(
                    rebuildReaders = rebuildReaders,
                    forceRebuild = forceRebuild,
                    onConfiguredCallbacks = mutableListOf(onConfigured),
                    onPreviousSessionClosedCallbacks =
                        mutableListOf<() -> Unit>().apply { onPreviousSessionClosed?.let { add(it) } }
                )
            } else {
                request.rebuildReaders = request.rebuildReaders || rebuildReaders
                request.forceRebuild = request.forceRebuild || forceRebuild
                request.onConfiguredCallbacks += onConfigured
                onPreviousSessionClosed?.let { request.onPreviousSessionClosedCallbacks += it }
            }
        }
        Log.d(
            TAG,
            "Session refresh batched during mode switch " +
                "rebuildReaders=$rebuildReaders forceRebuild=$forceRebuild"
        )
    }

    fun refreshOutputsAndSession(
        rebuildReaders: Boolean = true,
        forceRebuild: Boolean = false,
        onPreviousSessionClosed: (() -> Unit)? = null,
        onConfigured: () -> Unit = {}
    ) {
        if (refreshBatchDepth.get() > 0) {
            queueBatchedSessionRefresh(
                rebuildReaders = rebuildReaders,
                forceRebuild = forceRebuild,
                onConfigured = onConfigured,
                onPreviousSessionClosed = onPreviousSessionClosed
            )
            return
        }
        runOnCameraThread {
            if (cameraDevice == null) {
                onConfigured()
                onPreviousSessionClosed?.invoke()
                return@runOnCameraThread
            }
            if (!sessionRefreshPending.compareAndSet(false, true)) {
                if (forceRebuild || rebuildReaders) {
                    queueTrailingSessionRefresh(
                        rebuildReaders = rebuildReaders,
                        forceRebuild = forceRebuild,
                        onConfigured = onConfigured,
                        onPreviousSessionClosed = onPreviousSessionClosed
                    )
                    return@runOnCameraThread
                }
                onConfigured()
                return@runOnCameraThread
            }
            if (rebuildReaders) {
                setupReaders()
            }
            // s1-f05: open the reconfigure transition window so any frame dropped while the
            // replacement session configures is attributed to this bounded transition, not counted
            // as a silent steady-state drop. Settled in the session StateCallback / failure handlers.
            recordFrameDeliveryLedger.markReconfigureStart()
            Log.d(TAG, "Hot-session rebuild requested; keeping current session streaming until replacement configures")
            createSession(
                onConfigured = onConfigured,
                onPreviousSessionClosed = onPreviousSessionClosed
            )
        }
    }

    private fun hdrSurfaceRoleFor(surface: Surface): HdrSurfaceRole = when (surface) {
        previewSurface -> HdrSurfaceRole.PREVIEW
        recordSurface -> HdrSurfaceRole.ENCODER
        jpegReader?.surface -> HdrSurfaceRole.JPEG
        rawReader?.surface -> HdrSurfaceRole.RAW
        yuvReader?.surface -> HdrSurfaceRole.ANALYSIS
        hardwareImportReader?.surface -> HdrSurfaceRole.ENCODER
        else -> HdrSurfaceRole.ANALYSIS
    }

    private fun sessionSurfaceKindFor(surface: Surface): CameraSessionSurfaceKind {
        if (npv10TenBitCaptureEnabled && recordingActive && surface == yuvReader?.surface) {
            return CameraSessionSurfaceKind.RECORD
        }
        return when (surface) {
            recordSurface -> CameraSessionSurfaceKind.RECORD
            previewSurface -> CameraSessionSurfaceKind.PREVIEW
            jpegReader?.surface -> CameraSessionSurfaceKind.JPEG
            rawReader?.surface -> CameraSessionSurfaceKind.RAW
            yuvReader?.surface -> CameraSessionSurfaceKind.YUV_ANALYSIS
            hardwareImportReader?.surface -> CameraSessionSurfaceKind.HARDWARE_IMPORT
            else -> CameraSessionSurfaceKind.UNKNOWN
        }
    }

    private fun streamUseCaseRoleFor(surface: Surface): StreamUseCasePolicy.StreamRole =
        CameraSessionSurfaceRolePolicy.resolveStreamUseCaseRole(
            kind = sessionSurfaceKindFor(surface),
            previewParityRecording = surface == recordSurface && !rawVideoCaptureEnabled
        )

    private fun createSession(
        onConfigured: () -> Unit = {},
        includeHardwareImport: Boolean = true,
        applyUseCaseHints: Boolean = true,
        onPreviousSessionClosed: (() -> Unit)? = null
    ) {
        val dev = cameraDevice ?: return
        // P1.8: a session rebuild on the SAME physical sensor is almost always a mode toggle
        // (SDR↔HDR, resolution change) with the camera still pointed at the same scene — carry the
        // stabilized white point across it so color does not re-converge-flash. A different camera id
        // (lens switch / cold open) is a genuine context discontinuity and drops the history. Seeding
        // is self-correcting (the next measurement slew-limits toward it), so it can never regress.
        val sameSensorRebuild = lastSessionCameraId == dev.id
        val carriedWhitePoint = colorDriftGovernor.stabilizedWhitePoint
            ?.takeIf { sameSensorRebuild }
        lastSessionCameraId = dev.id
        colorDriftGovernor.reset()
        if (carriedWhitePoint != null) colorDriftGovernor.seed(carriedWhitePoint)
        colorDriftClampingActive = false
        colorDriftOverrideActive = false
        latestMeasuredColorTransform = null
        latestMeasuredColorGains = null
        latestMeasuredGreenGain = 1f
        meteringHysteresis.reset()
        // #4: clear any pending coalesced rebuild so the flag can never stay latched across a session
        // teardown/rebuild (runOnCameraThread drops its action if the bg thread is gone).
        pendingPreviewRebuild.set(false)
        // RC5 (default OFF): on a same-sensor rebuild (mode toggle / record-start, camera still on the
        // same scene) do NOT let the AWB lock re-engage off a stale converged signal — defer it until a
        // genuine post-rebuild SEARCHING->CONVERGED cycle (bounded by a frame cap). We only REMEMBER the
        // intent here and DISARM for now: the gate is armed once the FRESH session is actually live
        // (maybeArmPostRebuildColorHold in onConfigured), because until then the outgoing hot-swap
        // session is still streaming through the shared capture callback and its stale frames must not
        // drive the gate. OFF (or a lens switch) = today's full 3A reset, gate disarmed = byte-identical.
        postRebuildConvergenceGate.disarm()
        pendingRebuildColorHoldArm = SessionRebuildColorHoldFlag.enabled && sameSensorRebuild
        // Rebuild the preview request immediately when the in-app debug toggle (or ADB) flips the
        // active color-drift enforcement or reframe-damping flags, so the change is visible at once.
        ColorDriftEnforcementFlag.registerOnChanged { runOnCameraThread { updatePreview() } }
        MeteringDampingFlag.registerOnChanged { runOnCameraThread { updatePreview() } }
        // RC5 (default OFF): rebuild the request when the same-sensor AWB-washout deferral flips (ADB
        // A/B); the gate itself is armed on the next session rebuild (mode toggle / record start).
        SessionRebuildColorHoldFlag.registerOnChanged { runOnCameraThread { updatePreview() } }
        DeviceTrueWhiteBalanceFlag.registerOnChanged { runOnCameraThread { updatePreview() } }
        // RC2: rebuild the repeating request when the RAW-preview viewable-tonemap flag flips (ADB A/B),
        // so the change lands mid-recording without a session rebuild.
        RawPreviewViewableTonemapFlag.registerOnChanged { runOnCameraThread { updatePreview() } }
        // CP-QV20: rebuild the preview when the cine focus-pull flag flips (via ADB) so the gated
        // step path engages/disengages at once, and route ADB focus-pull commands to this live camera.
        CinematicFocusPullFlag.registerOnChanged { runOnCameraThread { updatePreview() } }
        CinematicFocusPullDebugCommands.register(cinematicFocusPullDebugHandler)
        val streamGraph = currentCinemaStreamGraphPlan(
            includeHardwareImport = includeHardwareImport
        )
        val surfaces = buildList {
            previewSurface?.let { add(it) }
            if (!maximumResolutionStillCaptureActive) {
                recordSurface?.let { add(it) }
            }
            if (streamGraph.jpegStill.includeInSession) {
                jpegReader?.surface?.let { add(it) }
            }
            if (streamGraph.rawSensor.includeInSession) {
                rawReader?.surface?.let { add(it) }
            }
            if (streamGraph.yuvAnalysis.includeInSession) {
                yuvReader?.surface?.let { add(it) }
            }
            if (streamGraph.hardwareImport.includeInSession) {
                hardwareImportReader?.surface?.let { add(it) }
            }
        }.distinct()
        if (surfaces.isEmpty()) {
            sessionRefreshPending.set(false)
            // s1-f05: no session to build — settle any open reconfigure window so it cannot stay stuck.
            recordFrameDeliveryLedger.markReconfigureSettled()
            onConfigured()
            onPreviousSessionClosed?.invoke()
            runTrailingSessionRefreshIfNeeded()
            return
        }

        val generation = sessionGeneration.incrementAndGet()
        val previousSessionForHotSwap = captureSession
        Log.i(
            TAG,
            "Cinema stream graph session mode=${streamGraph.mode} " +
                "surfaces=${surfaces.size} fallback=${streamGraph.fallbackDropOrder.joinToString(">")}"
        )
        val surfaceKindSummary = surfaces.joinToString(",") { sessionSurfaceKindFor(it).name }
        NeoLogger.i(
            "CameraController",
            "createSession attempt mode=${streamGraph.mode} hwLevel=${hardwareLevelLabel(hardwareLevel)} " +
                "surfaces=${surfaces.size}[$surfaceKindSummary] hwImport=$includeHardwareImport " +
                "useCaseHints=$applyUseCaseHints recordingActive=$recordingActive rawVideo=$rawVideoCaptureEnabled " +
                "npv10=$npv10TenBitCaptureEnabled"
        )
        try {
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    runOnCameraThread {
                        if (cameraDevice == null || generation != sessionGeneration.get()) {
                            s.close()
                            return@runOnCameraThread
                        }
                        sessionRefreshPending.set(false)
                        // s1-f05: replacement session is live — close the reconfigure transition window.
                        recordFrameDeliveryLedger.markReconfigureSettled()
                        if (previousSessionForHotSwap != null && previousSessionForHotSwap !== s) {
                            onPreviousSessionClosed?.let { callback ->
                                previousSessionClosedCallbacks
                                    .getOrPut(previousSessionForHotSwap) { mutableListOf() }
                                    .add(callback)
                            }
                            runCatching { previousSessionForHotSwap.close() }
                                .onFailure { Log.w(TAG, "Failed to close previous hot-swap session", it) }
                        } else {
                            closeDeferredReadersSafely("no previous session")
                            onPreviousSessionClosed?.invoke()
                        }
                        captureSession = s
                        synchronized(sessionCollectionsLock) { configuredSessionSurfaces[s] = identitySurfaceSet(surfaces) }
                        // RC5: the fresh session is now the frame source (outgoing session closed
                        // above) — safe to arm the AWB-lock deferral so ONLY fresh frames drive it.
                        maybeArmPostRebuildColorHold()
                        startPreview(generation, s)
                        NeoLogger.i(
                            "CameraController",
                            "Camera session CONFIGURED ok mode=${streamGraph.mode} " +
                                "surfaces=${surfaces.size}[$surfaceKindSummary]"
                        )
                        onConfigured()
                        runTrailingSessionRefreshIfNeeded()
                    }
                }
                override fun onClosed(s: CameraCaptureSession) {
                    cameraReleaseAcknowledgements.acknowledgeSession(s)
                    runOnCameraThread {
                        synchronized(sessionCollectionsLock) { configuredSessionSurfaces.remove(s) }
                        closeDeferredReadersSafely("previous hot-swap session closed")
                        previousSessionClosedCallbacks.remove(s)?.forEach { callback ->
                            runCatching(callback)
                                .onFailure { error ->
                                    Log.w(TAG, "Previous session release callback failed", error)
                                }
                        }
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    // Camera2 requires closing a session that failed to configure so its surfaces /
                    // ImageReader buffers are released. Without this, every fallback retry leaks native
                    // graphics memory and can eventually wedge the camera HAL ("too many buffers").
                    runCatching { s.close() }
                    if (cameraDevice == null || generation != sessionGeneration.get()) {
                        Log.d(TAG, "Ignoring stale Camera2 session configuration failure generation=$generation current=${sessionGeneration.get()}")
                        return
                    }
                    if (maximumResolutionStillCaptureActive) {
                        sessionRefreshPending.set(false)
                        recordFrameDeliveryLedger.markReconfigureSettled()
                        failStillCaptureRequest(
                            "This lens cannot combine its maximum-resolution sensor mode with a live preview"
                        )
                        return
                    }
                    if (includeHardwareImport && hardwareImportReader?.surface != null) {
                        Log.w(TAG, "Camera session rejected hardware import surface; retrying without optional import")
                        emitCameraPipelineWarning(
                            CameraPipelineDiagnostics.experimentalFallbackWarning(
                                "Camera session rejected hardware import surface"
                            )
                        )
                        createSession(
                            onConfigured = onConfigured,
                            includeHardwareImport = false,
                            applyUseCaseHints = false,
                            onPreviousSessionClosed = onPreviousSessionClosed
                        )
                        return
                    }
                    if (previewAnalysisRequested &&
                        streamGraph.yuvAnalysis.requirement == CinemaStreamRequirement.OPTIONAL &&
                        streamGraph.yuvAnalysis.includeInSession) {
                        // Some HDR stream combinations cannot mix the proprietary NDR preview/encoder
                        // transport with a STANDARD YUV analysis surface. Drop only the optional meter
                        // and retry; never reduce encoder resolution, bit depth, bitrate, or cadence.
                        previewAnalysisRequested = false
                        Log.w(TAG, "Camera session rejected optional NDR analysis; retrying without it")
                        setupReaders()
                        createSession(
                            onConfigured = onConfigured,
                            includeHardwareImport = false,
                            applyUseCaseHints = applyUseCaseHints,
                            onPreviousSessionClosed = onPreviousSessionClosed
                        )
                        return
                    }
                    if (recordSurface != null && !recordingSafeSessionFallback) {
                        recordingSafeSessionFallback = true
                        Log.w(TAG, "Camera session rejected full cinema graph; retrying recording-safe stream graph")
                        emitCameraPipelineWarning(
                            CameraPipelineDiagnostics.experimentalFallbackWarning(
                                "Camera session rejected optional still/analysis streams"
                            )
                        )
                        setupReaders()
                        createSession(
                            onConfigured = onConfigured,
                            includeHardwareImport = false,
                            applyUseCaseHints = applyUseCaseHints,
                            onPreviousSessionClosed = onPreviousSessionClosed
                        )
                        return
                    }
                    val sidecarFallback = resolveReadoutSidecarSessionFallback()
                    if (sidecarFallback.shouldRetry && sidecarFallback.nextMode != null) {
                        readoutSidecarMode = sidecarFallback.nextMode
                        Log.w(
                            TAG,
                            "Camera session rejected readout sidecar; retrying mode=$readoutSidecarMode"
                        )
                        emitCameraPipelineWarning(sidecarFallback.warning)
                        setupReaders()
                        createSession(
                            onConfigured = onConfigured,
                            includeHardwareImport = false,
                            applyUseCaseHints = false,
                            onPreviousSessionClosed = onPreviousSessionClosed
                        )
                        return
                    }
                    if (applyUseCaseHints) {
                        Log.w(TAG, "Camera session rejected stream use-case hints; retrying without hints")
                        emitCameraPipelineWarning(
                            CameraPipelineDiagnostics.experimentalFallbackWarning(
                                "Camera session rejected stream use-case hints"
                            )
                        )
                        createSession(
                            onConfigured = onConfigured,
                            includeHardwareImport = includeHardwareImport,
                            applyUseCaseHints = false,
                            onPreviousSessionClosed = onPreviousSessionClosed
                        )
                        return
                    }
                    Log.e(TAG, "Camera session configuration failed")
                    emitCameraPipelineWarning(
                        CameraPipelineDiagnostics.experimentalFallbackWarning(
                            "Camera session configuration failed"
                        )
                    )
                    sessionRefreshPending.set(false)
                    // s1-f05: reconfigure failed terminally — close the transition window.
                    recordFrameDeliveryLedger.markReconfigureSettled()
                    runTrailingSessionRefreshIfNeeded()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lastAppliedPreviewHdrProfile = null
                lastAppliedEncoderHdrProfile = null
                val outputConfigs = surfaces.map { surface ->
                    val config = OutputConfiguration(surface)
                    val role = hdrSurfaceRoleFor(surface)
                    val hdrProfile = HdrDynamicRangePolicy.apply(
                        config,
                        hdrDynamicRangeMode,
                        hdrSupport,
                        role
                    )
                    when (role) {
                        HdrSurfaceRole.PREVIEW -> lastAppliedPreviewHdrProfile = hdrProfile
                        HdrSurfaceRole.ENCODER -> lastAppliedEncoderHdrProfile = hdrProfile
                        else -> Unit
                    }
                    if (applyUseCaseHints) {
                        StreamUseCasePolicy.apply(config, characteristics, streamUseCaseRoleFor(surface))
                    }
                    config
                }
                Log.i(
                    TAG,
                    "HDR session profiles requested=$hdrDynamicRangeMode " +
                        "preview=${lastAppliedPreviewHdrProfile ?: HdrDynamicRangePolicy.PROFILE_STANDARD} " +
                        "encoder=${lastAppliedEncoderHdrProfile ?: HdrDynamicRangePolicy.PROFILE_STANDARD} " +
                        "support=$hdrSupport"
                )
                val sessionConfiguration = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    cameraCallbackExecutor,
                    callback
                )
                val streamFpsOverride = resolveCurrentStreamBasedFpsOverridePlan()
                // The MAXIMUM_RESOLUTION stream configuration only binds when the pixel mode is also a
                // session parameter (not just per-request), so stamp it here too when the tier is active.
                val engageMaxResSessionParam = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    maximumResolutionPixelModeActive() &&
                    cachedSessionRequestKeysByName.containsKey(CaptureRequest.SENSOR_PIXEL_MODE.name)
                var streamFpsSessionApplied = false
                if (streamFpsOverride.enabled || engageMaxResSessionParam) {
                    runCatching {
                        dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).let { sessionBuilder ->
                            var applied = false
                            if (streamFpsOverride.enabled &&
                                applyStreamBasedFpsKeys(sessionBuilder, streamFpsOverride, sessionParameters = true)
                            ) {
                                streamFpsSessionApplied = true
                                applied = true
                            }
                            if (engageMaxResSessionParam) {
                                sessionBuilder[CaptureRequest.SENSOR_PIXEL_MODE] =
                                    CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                                applied = true
                            }
                            if (applied) {
                                sessionConfiguration.setSessionParameters(sessionBuilder.build())
                            }
                        }
                    }.onFailure { error ->
                        Log.w(TAG, "Failed to build session parameters (fps/maxRes)", error)
                    }
                }
                maybeLogStreamBasedFpsOverride(
                    plan = streamFpsOverride,
                    requestKeysApplied = false,
                    sessionKeysApplied = streamFpsSessionApplied
                )
                if (SessionSupportProbeFlag.enabled) {
                    val sessionSupport = describeSessionConfigurationSupport(dev, sessionConfiguration)
                    NeoLogger.i(
                        "CameraController",
                        "createSession isSessionConfigurationSupported=$sessionSupport mode=${streamGraph.mode} " +
                            "surfaces=${surfaces.size}[$surfaceKindSummary]"
                    )
                    if (sessionSupport == "UNSUPPORTED") {
                        NeoLogger.critical(
                            "CameraController",
                            "PREDICTED-UNSUPPORTED Camera2 stream combination mode=${streamGraph.mode} " +
                                "hwLevel=${hardwareLevelLabel(hardwareLevel)} surfaces=[$surfaceKindSummary] — " +
                                "session configuration is expected to fail; reactive fallback will engage"
                        )
                    }
                }
                dev.createCaptureSession(sessionConfiguration)
            } else {
                lastAppliedPreviewHdrProfile = null
                lastAppliedEncoderHdrProfile = null
                @Suppress("DEPRECATION")
                dev.createCaptureSession(surfaces, callback, backgroundHandler)
            }
        } catch (e: IllegalStateException) {
            handleCreateSessionIllegalState(
                error = e,
                device = dev,
                generation = generation
            )
        } catch (e: Exception) {
            sessionRefreshPending.set(false)
            // s1-f05: createSession threw before configuring — settle the reconfigure window.
            recordFrameDeliveryLedger.markReconfigureSettled()
            emitCameraPipelineWarning(
                CameraPipelineDiagnostics.experimentalFallbackWarning(
                    e.message ?: "Camera session creation threw an exception"
                )
            )
            runTrailingSessionRefreshIfNeeded()
        }
    }

    private fun handleCreateSessionIllegalState(
        error: IllegalStateException,
        device: CameraDevice,
        generation: Long
    ) {
        val closingCamera = stateFlow.value is CameraState.Closing || stateFlow.value is CameraState.Closed
        val decision = CameraSessionCreateFailurePolicy.resolve(
            error = error,
            sessionGenerationCurrent = generation == sessionGeneration.get(),
            cameraDeviceStillCurrent = cameraDevice === device,
            closingCamera = closingCamera
        )
        sessionRefreshPending.set(false)
        // s1-f05: createSession threw — close the reconfigure transition window regardless of the
        // recovery decision below so a stuck window cannot mislabel later steady-state frames.
        recordFrameDeliveryLedger.markReconfigureSettled()
        runTrailingSessionRefreshIfNeeded()
        if (decision.shouldIgnoreFailure) {
            Log.d(TAG, "createSession: ${decision.message} (normal during teardown)")
            return
        }
        if (decision.shouldRecoverCamera) {
            Log.w(TAG, "createSession: ${decision.message}; reopening camera", error)
            sessionGeneration.incrementAndGet()
            val previousSession = captureSession
            captureSession = null
            if (cameraDevice === device) {
                cameraDevice = null
            }
            runCatching { previousSession?.close() }
                .onFailure { Log.w(TAG, "Failed to close stale session after createSession failure", it) }
            runCatching { device.close() }
                .onFailure { Log.w(TAG, "Failed to close stale camera after createSession failure", it) }
            val onReady = lastOpenReadyCallback
            val onError = lastOpenErrorCallback
            if (!closingCamera && onReady != null && onError != null && previewSurface != null) {
                sessionRecoveryPending = true
                openCamera(onReady, onError)
            } else if (!closingCamera) {
                transitionTo(CameraState.Error(decision.message))
            }
            return
        }
        Log.e(TAG, "createSession failed", error)
        emitCameraPipelineWarning(
            CameraPipelineDiagnostics.experimentalFallbackWarning(decision.message)
        )
        transitionTo(CameraState.Error(decision.message))
    }

    private fun resolveReadoutSidecarSessionFallback(): CameraReadoutSidecarFallbackDecision =
        CameraReadoutSidecarFallbackPolicy.resolve(
            currentMode = readoutSidecarMode,
            recordingActive = recordingActive,
            uncompressedReadoutRecording = npv10TenBitCaptureEnabled,
            rawVideoCaptureEnabled = rawVideoCaptureEnabled,
            hasYuvReader = yuvReader != null,
            recordingReadoutOverrideSize = recordingReadoutOverrideSize?.toCameraStreamSize(),
            recordingEncoderTargetSize = recordingEncoderTargetSize?.toCameraStreamSize(),
            supportedYuvSizes = yuvSizes.map { it.toCameraStreamSize() }
        )

    private fun resetReadoutSidecarFallback() {
        readoutSidecarMode = CameraReadoutSidecarPerformancePolicy.resolvePreferredMode(
            targetFps = targetCameraFps,
            uncompressedReadoutRecording = npv10TenBitCaptureEnabled,
            rawVideoCaptureEnabled = rawVideoCaptureEnabled,
            recordingReadoutOverrideSize = recordingReadoutOverrideSize?.toCameraStreamSize(),
            recordingEncoderTargetSize = recordingEncoderTargetSize?.toCameraStreamSize(),
            supportedYuvSizes = yuvSizes.map { it.toCameraStreamSize() }
        )
    }

    override fun onDeviceOpened(device: CameraDevice, onReady: () -> Unit, onError: (String) -> Unit) {
        createSession(onReady)
    }

    override fun onSessionConfigured(session: CameraCaptureSession, generation: Long, onConfigured: () -> Unit) {
        onConfigured()
    }

    override fun buildTonemapCurves(maxCurvePoints: Int) {
        // LOG is convert-only: NeoLog is NEVER baked into recorded pixels, so no recording tone
        // curve is built (recordingLogToneCurve stays null). Only the preview monitor curve is
        // built for the on-screen LOG-style look; recorded RAW is developed to NeoLog offline.
        val points = maxCurvePoints.coerceAtLeast(2)
        recordingLogToneCurve = null
        previewLogToneCurve = TonemapCurveFactory.buildPreviewLogCurve(points)
    }

    fun beginBatchUpdate() { refreshBatchDepth.incrementAndGet() }
    fun endBatchUpdate() {
        val depth = refreshBatchDepth.decrementAndGet()
        if (depth > 0) return
        if (depth < 0) {
            refreshBatchDepth.set(0)
            Log.w(TAG, "endBatchUpdate called without a matching beginBatchUpdate")
            return
        }
        val request = synchronized(refreshBatchLock) {
            val current = batchedSessionRefreshRequest
            batchedSessionRefreshRequest = null
            current
        }
        if (request == null) {
            refreshOutputsAndSession()
        } else {
            refreshOutputsAndSession(
                rebuildReaders = request.rebuildReaders,
                forceRebuild = request.forceRebuild,
                onConfigured = {
                    request.onConfiguredCallbacks.forEach { callback ->
                        runCatching(callback)
                            .onFailure { Log.w(TAG, "Batched session refresh callback failed", it) }
                    }
                },
                onPreviousSessionClosed = {
                    request.onPreviousSessionClosedCallbacks.forEach { callback ->
                        runCatching(callback)
                            .onFailure { Log.w(TAG, "Batched previous-session callback failed", it) }
                    }
                }
            )
        }
    }

    fun hasCameraFacing(facing: Int): Boolean {
        return cameraManager.cameraIdList.any { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    override fun setAiDrivenFocusDistance(dist: Float) {
        if (!dist.isFinite()) {
            clearAiDrivenFocusDistance()
            return
        }
        if (minFocusDist <= 0f) {
            clearAiDrivenFocusDistance()
            return
        }
        val resolvedDistance = dist.coerceIn(0f, minFocusDist)
        if (aiDrivenFocusDist >= 0f && abs(aiDrivenFocusDist - resolvedDistance) < 0.01f) return
        aiDrivenFocusDist = resolvedDistance
        updatePreview()
    }

    override fun clearAiDrivenFocusDistance() {
        aiDrivenFocusDist = -1f
        updatePreview()
    }

    override fun setSubjectMeteringRegions(af: Rect?, ae: Rect?) {
        persistentAfRegion = af
        persistentAeRegion = ae
        persistentAwbRegion = null
        updatePreview()
    }

    override fun clearFocusRegion() {
        persistentAfRegion = null
        persistentAeRegion = null
        persistentAwbRegion = null
        updatePreview()
    }

    private fun clearLensFocusOverridesForTapAf() {
        if (manFocusDist < 0f && aiDrivenFocusDist < 0f) return
        manFocusDist = -1f
        aiDrivenFocusDist = -1f
        paramsFlow.value = paramsFlow.value.copy(focusDist = -1f)
    }

    private fun beginTapFocusMeteringUnlock() {
        tapFocusMeteringUnlockUntilElapsedMs =
            SystemClock.elapsedRealtime() + TAP_FOCUS_METERING_UNLOCK_MS
        backgroundHandler?.postDelayed({
            if (!isTapFocusMeteringUnlockActive()) {
                updatePreview()
            }
        }, TAP_FOCUS_METERING_UNLOCK_MS + TAP_FOCUS_METERING_RELOCK_MARGIN_MS)
    }

    private fun isTapFocusMeteringUnlockActive(): Boolean =
        SystemClock.elapsedRealtime() < tapFocusMeteringUnlockUntilElapsedMs

    private fun applyTapFocusMeteringUnlock(
        builder: CaptureRequest.Builder,
        tapFocusMeteringUnlockActive: Boolean
    ) {
        if (!tapFocusMeteringUnlockActive) return
        builder[CaptureRequest.CONTROL_AE_LOCK] = false
        builder[CaptureRequest.CONTROL_AWB_LOCK] = false
    }

    override fun triggerSubjectAutoFocus(
        focusRegion: Rect,
        exposureRegion: Rect,
        focusNormalizedX: Float,
        focusNormalizedY: Float
    ) {
        runAutoFocusTrigger(focusRegion = focusRegion, exposureRegion = exposureRegion)
    }

    /**
     * Fire a single-shot AF scan (CONTROL_AF_TRIGGER_START) at the given metering regions, then hand
     * back to the repeating (continuous) request. [focusRegion] may be null on sensors that expose no
     * AF metering regions (maxAfRegions == 0) — in that case applyAll simply skips the AF region and
     * the scan is full-frame, so tap-to-focus and the startup centre-prime still re-focus instead of
     * doing nothing. persistentAfRegion is only honoured downstream when the device actually supports
     * AF regions, so passing null here is safe.
     */
    private fun runAutoFocusTrigger(focusRegion: Rect?, exposureRegion: Rect?) {
        persistentAfRegion = focusRegion
        persistentAeRegion = exposureRegion
        persistentAwbRegion = null
        beginTapFocusMeteringUnlock()

        runOnCameraThread {
            val builder = previewBuilder ?: return@runOnCameraThread
            val sess = captureSession ?: return@runOnCameraThread
            if (!previewBuilderHasTargets) {
                Log.d(TAG, "runAutoFocusTrigger skipped: preview request has no active surface target")
                return@runOnCameraThread
            }
            applyAll(builder, false)
            builder[CaptureRequest.CONTROL_AF_MODE] = singleShotAutoFocusMode()
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            try {
                sess.capture(builder.build(), captureCallback, backgroundHandler)
            } catch (e: IllegalStateException) {
                Log.d(TAG, "runAutoFocusTrigger: session closed (normal during teardown)")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "runAutoFocusTrigger skipped invalid request during mode transition", e)
            }
            // Reset trigger for next repeating request
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            updatePreview()
        }
    }

    /** True when the device advertises any real (non-OFF) autofocus mode. */
    private fun hasAutoFocusCapability(): Boolean {
        val modes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return false
        return modes.any { it != CaptureRequest.CONTROL_AF_MODE_OFF }
    }

    override fun captureStill(
        captureRaw: Boolean,
        captureJpeg: Boolean,
        overrides: StillCaptureOverrides?
    ) {
        runPrioritizedCameraControl {
            if (shouldUseDedicatedMaximumResolutionStill(captureRaw, captureJpeg)) {
                maximumResolutionStillCaptureActive = true
                Log.i(
                    TAG,
                    "Preparing dedicated maximum-resolution JPEG session " +
                        "size=${maximumResolutionSessionPhotoSizes.firstOrNull()?.let { "${it.width}x${it.height}" }}"
                )
                refreshOutputsAndSession(
                    rebuildReaders = true,
                    forceRebuild = true,
                    onConfigured = {
                        if (maximumResolutionStillCaptureActive) {
                            submitStillCaptureOnCameraThread(captureRaw, captureJpeg, overrides)
                        }
                    }
                )
                return@runPrioritizedCameraControl
            }
            submitStillCaptureWhenSessionStable(captureRaw, captureJpeg, overrides)
        }
    }

    private fun submitStillCaptureWhenSessionStable(
        captureRaw: Boolean,
        captureJpeg: Boolean,
        overrides: StillCaptureOverrides?,
        attempt: Int = 0
    ) {
        if (sessionRefreshPending.get()) {
            if (attempt >= STILL_CAPTURE_SESSION_WAIT_MAX_ATTEMPTS) {
                failStillCaptureRequest("Camera mode transition did not settle in time for photo capture")
                return
            }
            val handler = backgroundHandler
            if (handler == null) {
                failStillCaptureRequest("Camera thread is not available for photo capture")
                return
            }
            if (attempt == 0) {
                Log.i(TAG, "Photo capture queued until the active Camera2 session rebuild settles")
            }
            handler.postDelayed(
                {
                    submitStillCaptureWhenSessionStable(
                        captureRaw = captureRaw,
                        captureJpeg = captureJpeg,
                        overrides = overrides,
                        attempt = attempt + 1
                    )
                },
                STILL_CAPTURE_SESSION_WAIT_INTERVAL_MS
            )
            return
        }
        submitStillCaptureOnCameraThread(
            captureRaw = captureRaw,
            captureJpeg = captureJpeg,
            overrides = overrides,
            retryAttempt = attempt
        )
    }

    private fun shouldUseDedicatedMaximumResolutionStill(
        captureRaw: Boolean,
        captureJpeg: Boolean
    ): Boolean {
        if (recordingActive || captureRaw || !captureJpeg || maximumResolutionStillCaptureActive) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !supportsCaptureRequestKey(CaptureRequest.SENSOR_PIXEL_MODE)
        ) {
            return false
        }
        val normalArea = normalSessionPhotoSizes.maxOfOrNull { CameraSensorInfo.sizeArea(it) } ?: 0L
        val maximumArea = maximumResolutionSessionPhotoSizes
            .maxOfOrNull { CameraSensorInfo.sizeArea(it) } ?: 0L
        return maximumArea > normalArea
    }

    private fun submitStillCaptureOnCameraThread(
        captureRaw: Boolean,
        captureJpeg: Boolean,
        overrides: StillCaptureOverrides?,
        retryAttempt: Int = 0
    ) {
        val dev = cameraDevice
        val session = captureSession
        if (dev == null || session == null) {
            failStillCaptureRequest("Camera is not ready for photo capture")
            return
        }

        val videoSnapshot = recordingActive
        val captureTemplate = if (videoSnapshot) {
            CameraDevice.TEMPLATE_VIDEO_SNAPSHOT
        } else {
            CameraDevice.TEMPLATE_STILL_CAPTURE
        }
        val builder = dev.createCaptureRequest(captureTemplate)
        val streamGraph = currentCinemaStreamGraphPlan()
        val targetPlan = RawStillCaptureTargetPolicy.resolve(
            captureRaw = captureRaw,
            captureJpeg = captureJpeg,
            rawStreamActive = streamGraph.rawSensor.includeInSession && rawReader != null,
            jpegStreamActive = streamGraph.jpegStill.includeInSession && jpegReader != null
        )
        targetPlan.failureReason?.let { reason ->
            failStillCaptureRequest(reason)
            return
        }
        if (targetPlan.includeJpeg) {
            jpegReader?.surface?.let(builder::addTarget)
        }
        if (targetPlan.includeRaw) {
            rawReader?.surface?.let(builder::addTarget)
        }

        applyAll(builder, forStillCapture = true, videoSnapshot = videoSnapshot)
        builder.set(CaptureRequest.JPEG_QUALITY, effectiveJpegQuality().toByte())
        if (targetPlan.includeJpeg) {
            builder.set(CaptureRequest.JPEG_ORIENTATION, outputJpegOrientation())
        }
        builder.setTag(stillCaptureRequestTag)

        overrides?.let {
            it.iso?.let { iso -> builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso) }
            it.exposureNs?.let { exp -> builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp) }
            it.evCompensation?.let { ev -> builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev) }
            if (it.aeLock) builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
            if (it.awbLock) builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }

        try {
            val pauseIdleRawRepeating = !videoSnapshot &&
                targetPlan.includeJpeg &&
                streamGraph.rawSensor.includeInSession &&
                rawReader != null
            if (pauseIdleRawRepeating) {
                // A full-rate RAW repeating stream can indefinitely hold a vendor HAL's JPEG stage.
                // Stop only the idle preview cadence; recording snapshots must remain uninterrupted.
                session.stopRepeating()
                resumeRepeatingAfterStillCapture = true
                Log.i(TAG, "Paused idle RAW repeating stream for full-resolution JPEG capture")
            }
            val request = builder.build()
            val sequenceId = session.capture(request, captureCallback, backgroundHandler)
            Log.i(
                TAG,
                    "Photo capture submitted sequence=$sequenceId generation=${sessionGeneration.get()} " +
                    "videoSnapshot=$videoSnapshot jpeg=${targetPlan.includeJpeg} raw=${targetPlan.includeRaw} " +
                    "quality=${effectiveJpegQuality()} orientation=${outputJpegOrientation()}"
            )
        } catch (error: IllegalStateException) {
            if (retryAttempt < STILL_CAPTURE_SESSION_WAIT_MAX_ATTEMPTS && cameraDevice != null) {
                Log.i(TAG, "Photo capture met a closing session; rebuilding and retrying on its replacement")
                refreshOutputsAndSession(
                    rebuildReaders = false,
                    forceRebuild = true,
                    onConfigured = {
                        submitStillCaptureWhenSessionStable(
                            captureRaw = captureRaw,
                            captureJpeg = captureJpeg,
                            overrides = overrides,
                            attempt = retryAttempt + 1
                        )
                    }
                )
            } else {
                Log.w(TAG, "captureStill request submission failed", error)
                failStillCaptureRequest("Photo capture could not start: camera session closed")
            }
        } catch (error: Exception) {
            Log.w(TAG, "captureStill request submission failed", error)
            failStillCaptureRequest("Photo capture could not start: ${error.message ?: "camera session error"}")
        }
    }

    private fun dispatchJpegReady(reader: ImageReader) {
        try {
            onJpegReady?.invoke(reader)
        } finally {
            resumePreviewRepeatingAfterStillCapture()
            restoreNormalSessionAfterMaximumResolutionStill("JPEG dequeued")
        }
    }

    fun recoverStillCaptureSessionAfterFailure() {
        runOnCameraThread {
            restoreNormalSessionAfterMaximumResolutionStill("capture recovery")
        }
    }

    private fun failStillCaptureRequest(message: String) {
        resumePreviewRepeatingAfterStillCapture()
        restoreNormalSessionAfterMaximumResolutionStill("failure")
        onStillCaptureFailed?.invoke(message)
    }

    private fun resumePreviewRepeatingAfterStillCapture() {
        if (!resumeRepeatingAfterStillCapture) return
        resumeRepeatingAfterStillCapture = false
        updatePreview(immediate = true)
    }

    private fun restoreNormalSessionAfterMaximumResolutionStill(reason: String) {
        if (!maximumResolutionStillCaptureActive) return
        maximumResolutionStillCaptureActive = false
        Log.i(TAG, "Restoring normal video stream graph after maximum-resolution still ($reason)")
        if (cameraDevice != null) {
            refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true)
        }
    }

    override fun captureStillBurst(
        captureRaw: Boolean,
        overridesList: List<StillCaptureOverrides>
    ) {
        runOnCameraThread {
            val dev = cameraDevice ?: return@runOnCameraThread
            val session = captureSession ?: return@runOnCameraThread
            val streamGraph = currentCinemaStreamGraphPlan()
            val targetPlan = RawStillCaptureTargetPolicy.resolve(
                captureRaw = captureRaw,
                captureJpeg = true,
                rawStreamActive = streamGraph.rawSensor.includeInSession && rawReader != null,
                jpegStreamActive = streamGraph.jpegStill.includeInSession && jpegReader != null
            )
            targetPlan.failureReason?.let { reason ->
                onStillCaptureFailed?.invoke(reason)
                return@runOnCameraThread
            }
            
            val requests = overridesList.map { overrides ->
                val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                if (targetPlan.includeJpeg) {
                    jpegReader?.surface?.let {
                        builder.addTarget(it)
                    }
                }
                if (targetPlan.includeRaw) {
                    rawReader?.surface?.let {
                        builder.addTarget(it)
                    }
                }
                
                applyAll(builder, true)
                builder.set(CaptureRequest.JPEG_QUALITY, effectiveJpegQuality().toByte())
                if (targetPlan.includeJpeg) {
                    builder.set(CaptureRequest.JPEG_ORIENTATION, outputJpegOrientation())
                }

                overrides.iso?.let { iso -> builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso) }
                overrides.exposureNs?.let { exp -> builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp) }
                overrides.evCompensation?.let { ev -> builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev) }
                if (overrides.aeLock) builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                if (overrides.awbLock) builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)

                builder.build()
            }
            
            try {
                session.captureBurst(requests, captureCallback, backgroundHandler)
            } catch (e: IllegalStateException) {
                Log.d(TAG, "captureStillBurst: session closed (normal during teardown)")
            }
        }
    }

    override fun stillOutputAspectRatio(): Float {
        val size = maxPhotoSize ?: pixelArraySize ?: Size(4000, 3000)
        return CameraSensorInfo.sensorBaseAspectRatio(size)
    }

    override fun setISO(iso: Int) {
        val resolvedIso = iso.coerceIn(effectiveIsoRange.lower, effectiveIsoRange.upper)
        mutateControlState(immediate = false, stateMutation = { manualIso = resolvedIso }) {
            it.copy(iso = resolvedIso)
        }
    }

    override fun setAutoISO() {
        mutateControlState(immediate = false, stateMutation = { manualIso = -1 }) { it.copy(iso = -1) }
    }

    override fun setExposure(ns: Long) {
        val resolvedExposure = ns.coerceIn(exposureRange.lower, exposureRange.upper)
        mutateControlState(immediate = false, stateMutation = { manualExposureNs = resolvedExposure }) {
            it.copy(exposureNs = resolvedExposure)
        }
    }

    override fun setAutoExposure() {
        mutateControlState(immediate = false, stateMutation = { manualExposureNs = -1 }) { it.copy(exposureNs = -1L) }
    }

    override fun setLockedExposure(ns: Long) {
        mutateControlState(immediate = true, stateMutation = {
            manualExposureNs = ns
            aeAwbLocked = true
        }) { it.copy(exposureNs = ns) }
    }

    // Preserve the current green-magenta tint when only the Kelvin is changed (P0.1). Historically
    // this hard-reset the tint to 0; since no caller set a non-zero tint yet, preserving it is
    // behaviour-identical today and lets a Pro-Control tint survive a temperature adjustment.
    override fun setWB(kelvin: Int) = setWB(kelvin, manWbTint)

    override fun setWB(kelvin: Int, tint: Int) {
        val resolvedKelvin = kelvin.coerceIn(
            manualWhiteBalanceRange.lower,
            manualWhiteBalanceRange.upper
        )
        // Matches CinemaAutoWhiteBalanceRequestPolicy's accepted tint range; the policy re-clamps.
        val resolvedTint = tint.coerceIn(MANUAL_WB_TINT_MIN, MANUAL_WB_TINT_MAX)
        mutateControlState(immediate = false, stateMutation = {
            manualWbKelvin = resolvedKelvin
            manWbTint = resolvedTint
        }) {
            it.copy(wbKelvin = resolvedKelvin)
        }
    }

    override fun setAutoWB() {
        mutateControlState(immediate = false, stateMutation = {
            manualWbKelvin = -1
            manWbTint = 0
        }) { it.copy(wbKelvin = -1) }
    }

    // s5-f07 / CP-F8: when the cine focus-pull flag is ON and a pull is active, step its diopter and
    // route it through the existing manual-lens-distance path. Flag OFF (default) or no active pull
    // returns manFocusDist unchanged, so CameraAutofocusRequestPolicy.resolve() sees today's input.
    private fun resolveEffectiveManualFocusDistance(): Float {
        if (!CinematicFocusPullFlag.enabled) return manFocusDist
        if (!cinematicFocusPull.isPulling) return manFocusDist
        // CP-QV20 endpoint hold: capture the pulling state around the step. When the pull COMPLETES
        // this frame (isPulling flips false and the control returned the endpoint), persist
        // manFocusDist = that endpoint so the lens HOLDS B (AF_MODE_OFF stays forced) instead of
        // reverting to autofocus and re-acquiring (a visible snap-back). Strictly inside the flag-on
        // branch — the flag-off early-return above keeps today's path byte-for-byte.
        val wasPulling = cinematicFocusPull.isPulling
        val stepped = cinematicFocusPull.stepDiopters(
            nowElapsedMs = SystemClock.elapsedRealtime(),
            maxFocusDiopters = minFocusDist
        ) ?: return manFocusDist
        manFocusDist = CinematicFocusPullEndpointHoldPolicy.resolveHold(
            wasPulling = wasPulling,
            isPullingNow = cinematicFocusPull.isPulling,
            steppedOrNull = stepped,
            manFocusDist = manFocusDist
        )
        return stepped
    }

    /**
     * s5-f08 / CP-F9: begin a named-style cinematic focus pull from [fromDiopters] to [toDiopters]
     * via the AIFocusDistanceController pull API (SMOOTH/RACK/CREEP/SNAP), routed through the same
     * f07 manual-lens path. Gated: returns false (no-op) unless [CinematicFocusPullFlag] is ON, so
     * with the flag OFF the lens drive is byte-for-byte today. Returns true if a pull started.
     */
    internal fun beginCinematicStylePull(
        fromDiopters: Float,
        toDiopters: Float,
        style: com.neoproc.neoproccamx.ai.AIFocusDistanceController.FocusPullStyle
    ): Boolean {
        if (!CinematicFocusPullFlag.enabled) return false
        cinematicFocusPull = CinematicFocusPullControl(AICinematicPullStepper(style)).also {
            it.setEasingCurve(CinematicFocusPullControl.EasingCurve.EASE_IN_OUT)
            it.setDurationMs(AICinematicPullStepper.styleDurationMs(style))
        }
        return cinematicFocusPull.triggerPull(
            nowElapsedMs = SystemClock.elapsedRealtime(),
            fromDiopters = fromDiopters,
            toDiopters = toDiopters
        )
    }

    // CP-QV20: set focus-pull mark A or B (diopters). Gated no-op when the cine flag is OFF, so the
    // default focus path is untouched. Marks are stateful config on the control; no request rebuild.
    internal fun setCinematicFocusPullMark(isA: Boolean, diopters: Float) {
        if (!CinematicFocusPullFlag.enabled) return
        if (isA) cinematicFocusPull.setMarkA(diopters) else cinematicFocusPull.setMarkB(diopters)
    }

    // CP-QV20: configure the pull easing curve + duration. Gated no-op when the cine flag is OFF.
    internal fun configureCinematicFocusPull(
        curve: CinematicFocusPullControl.EasingCurve,
        durationMs: Long
    ) {
        if (!CinematicFocusPullFlag.enabled) return
        cinematicFocusPull.setEasingCurve(curve)
        cinematicFocusPull.setDurationMs(durationMs)
    }

    /**
     * CP-QV20: trigger a mark-A -> mark-B cinematic focus pull. Gated: returns false (no-op) when the
     * cine flag is OFF or either mark is unset, so the default focus path stays byte-for-byte today.
     *
     * Endpoint-hold precondition: set manFocusDist = mark A FIRST so the lens ENTERS manual focus at A
     * ([CameraAutofocusRequestPolicy] forces AF_MODE_OFF for a positive manual distance) — this is
     * what lets the endpoint hold at B on completion instead of reverting to AF. Then trigger the pull
     * and rebuild the preview request so [resolveEffectiveManualFocusDistance] steps it each frame.
     */
    internal fun triggerCinematicFocusPull(): Boolean {
        if (!CinematicFocusPullFlag.enabled) return false
        // The control owns the marks and validates them inside triggerPull() (false when either is
        // unset/invalid — a dormant-safe no-op). Trigger first, which latches pullStartDiopters = mark A.
        val started = cinematicFocusPull.triggerPull(SystemClock.elapsedRealtime())
        if (!started) return false
        // Endpoint-hold precondition: enter manual focus at A so CameraAutofocusRequestPolicy forces
        // AF_MODE_OFF for the positive manual distance (the lens can then hold B on completion).
        // status().startDiopters is exactly the latched mark A.
        manFocusDist = cinematicFocusPull.status().startDiopters
        aiDrivenFocusDist = -1f
        // Rebuild the preview request so resolveEffectiveManualFocusDistance() steps the pull per frame.
        runOnCameraThread { updatePreview() }
        return true
    }

    override fun setFocusDist(dist: Float) {
        if (!dist.isFinite() || minFocusDist <= 0f) {
            setAutoFocus()
            return
        }
        val resolvedDistance = dist.coerceIn(0f, minFocusDist)
        mutateControlState(immediate = false, stateMutation = {
            manualFocusDist = resolvedDistance
            aiDrivenFocusDist = -1f
        }) { it.copy(focusDist = resolvedDistance) }
    }

    override fun setAutoFocus() {
        mutateControlState(immediate = false, stateMutation = {
            manualFocusDist = -1f
            aiDrivenFocusDist = -1f
            persistentAfRegion = null
            persistentAeRegion = null
            persistentAwbRegion = null
        }) { it.copy(focusDist = -1f) }
    }

    override fun setEV(index: Int) {
        val resolvedEv = index.coerceIn(evRange.lower, evRange.upper)
        mutateControlState(immediate = false, stateMutation = { evIndex = resolvedEv }) {
            it.copy(ev = resolvedEv)
        }
    }

    override fun setFlash(mode: FlashMode) {
        mutateControlState(immediate = false, stateMutation = { flashMode = mode }) { it.copy(flash = mode) }
    }

    override fun setZoom(ratio: Float) {
        zoomRatio = ratio
        paramsFlow.value = paramsFlow.value.copy(zoom = ratio)
        updatePreview()
    }

    override fun setFocusRegion(region: Rect?) {
        persistentAfRegion = region
        persistentAwbRegion = null
        updatePreview()
    }

    override fun setAeAwbLock(locked: Boolean) {
        if (locked) tapFocusMeteringUnlockUntilElapsedMs = 0L
        aeAwbLocked = locked
        updatePreview()
    }

    override fun isAeAwbLocked(): Boolean = aeAwbLocked

    override fun setNoiseReductionEnabled(enabled: Boolean) {
        noiseReductionEnabled = enabled
        updatePreview()
    }

    override fun setVideoDetailProfile(profile: VideoDetailProfile) {
        selectedVideoDetailProfile = profile
        extremeDetailFallbackExposureRequestApplied = false
        updatePreview()
    }

    override fun setProcessedVideoSceneQuality(motionRisk: Float, noiseRisk: Float) {
        val sanitizedMotionRisk = motionRisk.sanitizedSceneRisk()
        val sanitizedNoiseRisk = noiseRisk.sanitizedSceneRisk()
        val previousRequestTier = processedVideoSceneRequestTier(
            motionRisk = processedVideoMotionRisk,
            noiseRisk = processedVideoNoiseRisk
        )
        val nextRequestTier = processedVideoSceneRequestTier(
            motionRisk = sanitizedMotionRisk,
            noiseRisk = sanitizedNoiseRisk
        )
        if (
            processedVideoMotionRisk == sanitizedMotionRisk &&
            processedVideoNoiseRisk == sanitizedNoiseRisk
        ) {
            return
        }
        processedVideoMotionRisk = sanitizedMotionRisk
        processedVideoNoiseRisk = sanitizedNoiseRisk
        val processedSdrAutoExposureRecording =
            recordingActive &&
                !rawVideoCaptureEnabled &&
                hdrDynamicRangeMode == HdrDynamicRangeMode.SDR &&
                !previewLogLookEnabled &&
                !previewHdrLookEnabled &&
                !previewRawLookEnabled &&
                manIso <= 0 &&
                manExpNs <= 0L
        if (processedSdrAutoExposureRecording && previousRequestTier != nextRequestTier) {
            updatePreview()
        }
    }

    private fun processedVideoSceneRequestTier(motionRisk: Float, noiseRisk: Float): String {
        val motionTier = when {
            motionRisk >= 0.85f -> "MOTION_QUARTER_FRAME"
            motionRisk >= 0.70f -> "MOTION_THIRD_FRAME"
            motionRisk >= 0.58f -> "MOTION_HALF_FRAME"
            else -> "MOTION_STANDARD"
        }
        val noiseTier = if (noiseRisk >= 0.55f) "NOISE_HIGH" else "NOISE_STANDARD"
        return "$motionTier|$noiseTier"
    }

    override fun setFaceDetectionEnabled(enabled: Boolean) {
        faceDetectionEnabled = enabled
        updatePreview()
    }

    override fun setStabilizationMode(mode: StabilizationMode) {
        stabilizationMode = mode
        updatePreview()
    }

    override fun setJpegQuality(quality: Int) {
        jpegQuality = quality
    }

    override fun setRawVideoCaptureEnabled(enabled: Boolean) {
        val changed = rawVideoCaptureEnabled != enabled
        rawVideoCaptureEnabled = enabled
        if (changed) resetReadoutSidecarFallback()
        if (enabled) {
            emitCameraPipelineWarning(cameraStreamPlans[CinemaVideoMode.RAW]?.fallbackWarning)
        }
        if (changed && cameraDevice != null && recordingActive) {
            refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true)
        }
    }

    override fun setNpv10TenBitCaptureEnabled(enabled: Boolean) {
        val changed = npv10TenBitCaptureEnabled != enabled
        npv10TenBitCaptureEnabled = enabled
        if (!enabled) npv10CaptureTargetSize = null
        if (changed) resetReadoutSidecarFallback()
        if (changed && cameraDevice != null && recordingActive) {
            refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true)
        }
    }

    override fun setNpv10CaptureTargetSize(size: Size?) {
        if (npv10CaptureTargetSize == size) return
        npv10CaptureTargetSize = size
        resetReadoutSidecarFallback()
        if (cameraDevice != null && npv10TenBitCaptureEnabled && recordingActive) {
            refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true)
        }
    }

    override fun isNpv10TenBitSessionConfigured(size: Size): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !npv10TenBitCaptureEnabled ||
            sessionRefreshPending.get()
        ) {
            return false
        }
        val session = captureSession ?: return false
        val reader = yuvReader ?: return false
        if (reader.width != size.width ||
            reader.height != size.height ||
            reader.imageFormat != ImageFormat.YCBCR_P010
        ) {
            return false
        }
        val readerSurface = reader.surface
        return synchronized(sessionCollectionsLock) {
            configuredSessionSurfaces[session]?.contains(readerSurface) == true
        }
    }

    override fun resolveNpv10CaptureSourcePlan(
        selectedResolution: Size,
        rawModeActive: Boolean
    ): Npv10CaptureSourcePlan {
        val chars = characteristics
        val streamMaps = if (chars != null) resolveStreamConfigurationMaps(chars) else emptyList()
        val p010Sizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            streamMaps.flatMap { map -> map.p010OutputSizes() }.distinctSortedCameraSizes()
        } else {
            emptyList()
        }
        val yuv420Sizes = yuvSizes
        val rawSensorSizes = streamMaps
            .flatMap { map -> map.getOutputSizesSafely(ImageFormat.RAW_SENSOR) }
            .distinctSortedCameraSizes()
        val rawPrivateSizes = streamMaps
            .flatMap { map -> map.getOutputSizesSafely(ImageFormat.RAW_PRIVATE) }
            .distinctSortedCameraSizes()
        val maximumResolutionRawSensorSizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            streamMaps
                .flatMap { map -> map.getHighResolutionOutputSizesSafely(ImageFormat.RAW_SENSOR) }
                .distinctSortedCameraSizes()
        } else {
            emptyList()
        }
        val supportsMaximumResolutionPixelMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
            }.getOrDefault(false) == true
        } else {
            false
        }

        return Npv10CaptureSourcePolicy.resolve(
            selectedResolution = selectedResolution,
            p010Sizes = p010Sizes,
            yuv420Sizes = yuv420Sizes,
            requireTenBit = npv10TenBitCaptureEnabled,
            rawSensorSizes = rawSensorSizes,
            rawPrivateSizes = rawPrivateSizes,
            maximumResolutionRawSensorSizes = maximumResolutionRawSensorSizes,
            rawModeActive = rawModeActive,
            supportsMaximumResolutionPixelMode = supportsMaximumResolutionPixelMode
        )
    }

    override fun setThermalResourceCeiling(ceiling: Float) {
        thermalResourceCeiling = ceiling
        updatePreview()
    }

    override fun setHdrDynamicRangeMode(mode: HdrDynamicRangeMode) {
        // NDR highlight/exposure integrity needs a small analysis surface. Change its session membership
        // atomically with the dynamic-range mode so entering/leaving NDR causes one rebuild, not two.
        val ndrExposureAnalysisRequested = mode != HdrDynamicRangeMode.SDR
        val modeChanged = hdrDynamicRangeMode != mode
        val analysisChanged = previewAnalysisRequested != ndrExposureAnalysisRequested
        if (!modeChanged && !analysisChanged) return
        hdrDynamicRangeMode = mode
        if (modeChanged) {
            lastHdrExposureMetricsElapsedMs = 0L
            hdrAdaptiveHighlightExtraStops = 0f
            hdrAdaptiveHighlightAppliedStops = 0f
            lastRequestedNdrExposureCompensationIndex = null
        }
        previewAnalysisRequested = ndrExposureAnalysisRequested
        characteristics?.let { hdrSupport = HdrDynamicRangePolicy.detectSupport(it) }
        if (cameraDevice != null) {
            refreshOutputsAndSession(rebuildReaders = analysisChanged, forceRebuild = analysisChanged)
        }
    }

    override fun describeHdrDynamicRangeState(): HdrDynamicRangeState {
        val resolvedProfile = HdrDynamicRangePolicy.resolveProfilePure(
            mode = hdrDynamicRangeMode,
            support = hdrSupport,
            role = HdrSurfaceRole.PREVIEW
        )
        return HdrDynamicRangeState(
            requestedMode = hdrDynamicRangeMode,
            previewProfile = lastAppliedPreviewHdrProfile,
            encoderProfile = lastAppliedEncoderHdrProfile,
            resolvedProfileForCurrentMode = resolvedProfile,
            support = hdrSupport
        )
    }

    /**
     * s1-f05: honest record-path frame-delivery snapshot (dropped-frame accounting). The device
     * soak reads this to assert 0 unintended steady-state drops and bounded drops per reconfigure.
     */
    internal fun recordFrameDeliverySnapshot(): CameraRecordFrameDeliverySnapshot =
        recordFrameDeliveryLedger.snapshot()

    /**
     * s1-f05: dynamic-range coherence verdict for the live session — whether the negotiated
     * transport equals the requested 10-bit mode, an honest hardware downgrade, or a silent 8-bit
     * fallback (which must never be certified as a 10-bit transport by the Omega gate).
     */
    internal fun describeDynamicRangeCoherence(): CameraDynamicRangeCoherenceReport =
        CameraDynamicRangeCoherencePolicy.evaluate(
            state = describeHdrDynamicRangeState(),
            sessionConfigured = captureSession != null,
            recordingEncoderActive = recordingActive
        )

    override fun setCinemaFidelityOverridesEnabled(enabled: Boolean) {
        if (cinemaFidelityOverridesEnabled == enabled) return
        cinemaFidelityOverridesEnabled = enabled
        updatePreview()
    }

    override fun setPreviewLookControls(logEnabled: Boolean, hdrEnabled: Boolean, rawEnabled: Boolean) {
        if (previewLogLookEnabled == logEnabled &&
            previewHdrLookEnabled == hdrEnabled &&
            previewRawLookEnabled == rawEnabled
        ) return
        previewLogLookEnabled = logEnabled
        previewHdrLookEnabled = hdrEnabled
        previewRawLookEnabled = rawEnabled
        updatePreview()
    }

    override fun setVideoPreviewActive(active: Boolean) {
        if (videoPreviewActive == active) return
        videoPreviewActive = active
        updatePreview()
    }

    override fun setPreviewViewport(width: Int, height: Int, contentAspectRatio: Float?) {
        // Viewport framing is owned by CameraViewModel/CameraVulkanPreviewView. Do not feed it
        // back into the live Camera2 request or mode switches turn into unintended sensor zoom.
    }

    override fun setAnalysisTargetSize(size: Size?) {
        if (analysisTargetSize == size) return
        if (npv10TenBitCaptureEnabled && recordingActive && analysisTargetSize != null && size != null) {
            Log.w(
                TAG,
                "Ignoring analysis target retune during NPV10 recording: " +
                    "locked=${analysisTargetSize?.width}x${analysisTargetSize?.height} " +
                    "requested=${size.width}x${size.height}"
            )
            return
        }
        analysisTargetSize = size
        resetReadoutSidecarFallback()
        if (cameraDevice != null && recordingActive) {
            refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true)
        }
    }

    override fun setHardwareImportTargetSize(size: Size?) {
        if (hardwareImportTargetSize == size) return
        hardwareImportTargetSize = size
    }

    override fun setHardwareImportP010SourceEnabled(enabled: Boolean) {
        if (hardwareImportP010SourceEnabled == enabled) return
        hardwareImportP010SourceEnabled = enabled
    }

    override fun supportsHardwareImportP010Source(size: Size, fps: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val normalized = size.normalizedForCameraOutput()
        val targetFrameDurationNs = 1_000_000_000L / fps.coerceAtLeast(1).toLong()
        val streamMaps = characteristics
            ?.let(::resolveStreamConfigurationMaps)
            .orEmpty()
        return streamMaps.any { map ->
            val matchedSize = map.p010OutputSizes()
                .firstOrNull { it.normalizedForCameraOutput() == normalized }
                ?: return@any false
            val minFrameDurationNs = runCatching {
                map.getOutputMinFrameDuration(ImageFormat.YCBCR_P010, matchedSize)
            }.getOrDefault(0L)
            minFrameDurationNs <= 0L || minFrameDurationNs <= targetFrameDurationNs
        }
    }

    override fun setInternalRecordingHardwareImportEnabled(enabled: Boolean) {
        if (internalRecordingHardwareImportEnabled == enabled) return
        internalRecordingHardwareImportEnabled = enabled
        if (!enabled) hardwareImportP010SourceEnabled = false
    }

    override fun setPreviewContinuityRecordSurfaceEnabled(enabled: Boolean) {
        if (previewContinuityRecordSurfaceEnabled == enabled) return
        previewContinuityRecordSurfaceEnabled = enabled
        if (recordSurface != null && cameraDevice != null && captureSession != null) {
            restartRepeatingRequestForRecordTargetChange()
        }
    }

    override fun setPreviewAnalysisRequested(enabled: Boolean) {
        if (previewAnalysisRequested == enabled) return
        previewAnalysisRequested = enabled
        // Attaching/detaching the analysis YUV reader changes the session OUTPUT set, so a repeating-
        // request retarget is not enough — a hot session rebuild is required (setupReaders recreates
        // the reader, createSession swaps outputs while the old session keeps streaming). Preview only:
        // during active recording the encoded/uncompressed graph owns the stream set, so we leave it
        // untouched to avoid disturbing the record path.
        if (cameraDevice != null && captureSession != null && !recordingActive) {
            refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true)
        }
    }

    override fun setRecordingReadoutOverrideSize(size: Size?) {
        if (recordingReadoutOverrideSize == size) return
        recordingReadoutOverrideSize = size
        resetReadoutSidecarFallback()
        if (cameraDevice != null && recordingActive) {
            refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true)
        }
    }

    override fun setRecordingEncoderTargetSize(size: Size?) {
        if (recordingEncoderTargetSize == size) return
        recordingEncoderTargetSize = size
        resetReadoutSidecarFallback()
        if (cameraDevice != null && recordingActive) {
            refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true)
        }
    }

    override fun setRawPreviewCaptureEnabled(enabled: Boolean) {
        if (rawPreviewCaptureEnabled == enabled) return
        rawPreviewCaptureEnabled = enabled
        resetReadoutSidecarFallback()
        // Adding/removing the RAW reader from the PREVIEW session changes the stream graph, so a full
        // reader+session rebuild is required. Gate to the idle/preview case — never perturb an active
        // recording (the record path owns the RAW reader while recordingActive).
        if (cameraDevice != null && !recordingActive) {
            refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true)
        }
    }

    override fun setRecordingSensorPixelModeMaximumResolution(enabled: Boolean) {
        // Never claim the max-res pixel mode on a device that does not expose an ultra-high-res sensor.
        val effective = enabled && ultraHighResRawSupported
        if (recordingSensorPixelModeMaximumResolution == effective) return
        recordingSensorPixelModeMaximumResolution = effective
        resetReadoutSidecarFallback()
        // Engaging/clearing the pixel mode changes the RAW reader dimensions, so a full reader+session
        // rebuild is required (a bare repeating-request retarget is insufficient).
        if (cameraDevice != null && recordingActive) {
            refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true)
        }
    }

    override fun setRecordSurface(surface: Surface?) {
        setRecordSurface(surface = surface, refreshSession = true)
    }

    fun setRecordSurface(surface: Surface?, refreshSession: Boolean) {
        recordSurface = surface
        if (surface == null) {
            recordingActive = false
            unconfiguredRecordSurfaceRecoveryPending = false
        }
        val decision = CameraRecordSurfaceRefreshPolicy.resolve(
            surfaceAttached = surface != null,
            refreshSessionRequested = refreshSession
        )
        if (decision.clearRecordingEncoderTargets) {
            recordingEncoderTargetSize = null
            recordingSafeSessionFallback = false
        }
        resetReadoutSidecarFallback()
        if (decision.refreshSessionImmediately) refreshOutputsAndSession()
    }

    override fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
    }

    override fun getPreviewSurface(): Surface? = previewSurface

    override fun processedVideoQualityEvidence(): Map<String, String> =
        appliedProcessedVideoQualityEvidence

    override fun startVideoCapture() {
        recordingActive = true
        extremeDetailFallbackExposureRequestApplied = false
        transitionTo(CameraState.Recording)
        restartRepeatingRequestForRecordTargetChange()
    }

    override fun stopVideoCapture() {
        recordingActive = false
        extremeDetailFallbackExposureRequestApplied = false
        restartRepeatingRequestForRecordTargetChange()
        transitionTo(CameraState.Streaming)
    }

    private fun restartRepeatingRequestForRecordTargetChange() {
        val session = captureSession ?: return
        val generation = sessionGeneration.get()
        runOnCameraThread {
            startPreview(generation, session)
        }
    }

    override fun drainRawReaderSynchronously() {
        // Flush RAW frames the HAL has delivered into the reader ring but not yet dispatched,
        // routing them through the normal onRawReady path before the session is reconfigured or
        // torn down — otherwise the final frames of a clip are silently lost at stop. Each callback
        // uses acquireLatestImage (coalescing), so this recovers the freshest buffered tail frames;
        // an empty ring makes every invocation a safe no-op. Bounded by the reader depth so it can
        // never spin. Runs on the caller thread (the recording-stop worker), never the camera thread.
        val reader = rawReader ?: return
        val callback = onRawReady ?: return
        val maxDrain = reader.maxImages.coerceIn(1, 16)
        repeat(maxDrain) {
            if (Thread.currentThread().isInterrupted) return
            runCatching { callback.invoke(reader) }
            try {
                Thread.sleep(2L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    override fun switchFacing() {
        facing = if (facing == CameraCharacteristics.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        currentCameraId = ""
        selectCamera()
    }

    override fun selectCamera() {
        val ids = runCatching { cameraManager.cameraIdList }.getOrElse {
            Log.e(TAG, "selectCamera: cameraIdList unavailable", it)
            emptyArray()
        }
        if (ids.isEmpty()) {
            Log.w(TAG, "selectCamera: no camera ids reported by the system")
            return
        }

        // D-12 (default OFF): reuse the CameraCharacteristics that enumeration already fetched for the
        // winning id instead of re-querying it in loadCharacteristics. Short-lived LOCAL map, scoped to
        // this single selectCamera call (GC'd on return); NO process-wide memoization, nothing stashed on
        // the candidate. Flag OFF => sink is null, buildLensCandidate writes nothing (byte-identical today).
        val enumeratedChars: MutableMap<String, CameraCharacteristics>? =
            if (SelectCameraCharacteristicsReuseFlag.enabled) HashMap(ids.size) else null
        val candidates = ids.mapNotNull { id -> buildLensCandidate(id, enumeratedChars) }
        if (candidates.isEmpty()) {
            Log.w(TAG, "selectCamera: no usable camera candidates after enumeration")
            return
        }

        // s5-f10 / CP-F11: gated task-aware routing. CameraLensSelectionTaskFlag OFF (default) and
        // lensSelectionTaskHint == None (default) => selectForTask returns exactly the live
        // selectPreferredCandidate pick; no live behavior change until enabled device-side.
        val preferred = CameraLensSelectionPolicy.selectForTask(
            facing,
            candidates,
            lensSelectionTaskHint,
            CameraLensSelectionTaskFlag.enabled
        ) ?: candidates.first()

        if (preferred.cameraId == currentCameraId && characteristics != null) {
            return
        }

        currentCameraId = preferred.cameraId
        val preloadedChars = enumeratedChars?.get(preferred.cameraId)
        if (preloadedChars != null) {
            loadCharacteristics(preferred.cameraId, preloadedChars)
        } else {
            loadCharacteristics(preferred.cameraId)
        }
        Log.i(TAG, "selectCamera: id=${preferred.cameraId} facing=$facing logical=${preferred.logical} zoom=${preferred.zoomLower}-${preferred.zoomUpper}x level=${hardwareLevelLabel(hardwareLevel)}")
    }

    private fun buildLensCandidate(
        cameraId: String,
        charsSink: MutableMap<String, CameraCharacteristics>? = null
    ): CameraLensCandidate? = runCatching {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        // D-12: capture the enumeration fetch so selectCamera can reuse the winner's chars (sink null => no-op).
        charsSink?.put(cameraId, chars)
        val candidateFacing = chars.get(CameraCharacteristics.LENS_FACING)
        if (candidateFacing != null && candidateFacing != facing) return@runCatching null
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val openable = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ||
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        if (!openable) return@runCatching null

        val level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focalLength = focals?.firstOrNull() ?: 0f
        val logical = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else null
        val digitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoomLower = zoomRange?.lower ?: 1f
        val zoomUpper = zoomRange?.upper ?: digitalZoom.coerceAtLeast(1f)
        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        val aperture = apertures?.firstOrNull() ?: 1.8f
        val pixelArray = resolvePixelArraySize(chars) ?: chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val sensorPixels = pixelArray?.let { it.width.toLong() * it.height.toLong() } ?: 0L
        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
        val hasOis = oisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        val hasPhaseDetect = afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ||
                afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        val supportsRawCap = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val supports10Bit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT)
        } else false
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val maxFps = fpsRanges.maxOfOrNull { it.upper } ?: 25

        CameraLensCandidate(
            cameraId = cameraId,
            level = level,
            focalLength = focalLength,
            logical = logical,
            zoomLower = zoomLower,
            zoomUpper = zoomUpper,
            aperture = aperture,
            sensorPixels = sensorPixels,
            hasOis = hasOis,
            hasPhaseDetect = hasPhaseDetect,
            supportsRaw = supportsRawCap,
            supports10BitHdr = supports10Bit,
            maxFps = maxFps,
            lensType = CameraLensSelectionPolicy.classifyLensType(focalLength),
            openable = true
        )
    }.getOrElse {
        Log.w(TAG, "buildLensCandidate failed for id=$cameraId", it)
        null
    }

    private fun loadCharacteristics(cameraId: String) {
        val chars = runCatching { cameraManager.getCameraCharacteristics(cameraId) }.getOrElse {
            Log.e(TAG, "loadCharacteristics: failed to load characteristics for $cameraId", it)
            return
        }
        loadCharacteristics(cameraId, chars)
    }

    // D-12 overload: apply already-fetched characteristics (from the selectCamera enumeration sink) so the
    // chosen id is not re-queried. The single-arg overload above keeps the original fetch-then-apply path;
    // both funnel into this identical body, so the flag-OFF path is byte-identical to today.
    private fun loadCharacteristics(cameraId: String, chars: CameraCharacteristics) {
        characteristics = chars
        facing = chars.get(CameraCharacteristics.LENS_FACING) ?: facing
        activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        pixelArraySize = resolvePixelArraySize(chars)
        sensorNoiseModel = CameraSensorNoiseModelPolicy.fromCharacteristics(chars)
        val sensorPhysicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        physicalImagingTraits = CameraPhysicalImagingTraits.fromCharacteristics(chars)
        val dynamicAcesIdtCalibration = physicalImagingTraits.dynamicAcesIdtCalibration
        if (sensorPhysicalSize != null && strictHighEndEnabled()) {
            Log.d(
                TAG,
                "Physical imaging traits for shader profiles: sensor=${sensorPhysicalSize.width}x${sensorPhysicalSize.height}mm " +
                "focal=${physicalImagingTraits.focalLengthMm}mm aperture=f/${physicalImagingTraits.aperture} " +
                "idt=${dynamicAcesIdtCalibration.signature}"
            )
        }
        cameraVendorReadoutScanReport = CameraVendorReadoutScanReport.EMPTY
        cameraBypassCapabilityReport = null
        
        // Phase 1a: Initialize sensor readout policy (maximum megapixel unlock)
        sensorReadoutPolicy = SensorReadoutPolicy(chars)
        if (strictHighEndEnabled()) {
            val readout = sensorReadoutPolicy?.buildMaximumReadout()
            if (readout != null) {
                Log.i(TAG, "Phase 1a: Maximum sensor readout - ${readout.megapixelCount}MP at ${readout.pixelArraySize.width}x${readout.pixelArraySize.height}")
            }
        }
        
        // Phase 1b: Initialize strict full ISP bypass policy (linear tonemap + all exposed ISP stages OFF)
        ispBypassPolicy = ISPBypassPolicy(
            characteristics = chars,
            distortionCorrectionPreference = IspBypassDistortionCorrectionPreference.SENSOR_NATIVE_OFF
        )
        loggedIspBypassRequestApplication = false
        loggedStabilizationResolution = false
        // CP17: a new camera/profile may honor ISP-off differently — re-arm read-back verification.
        loggedIspBypassVerification = false
        lastRequestedIspBypassModes = null
        lastIspBypassVerification = null
        ispBypassReadbackFramesObserved = 0
        val supportsBypass = ispBypassPolicy?.supportsISPBypass() ?: false
        Log.i(TAG, "Phase 1b: strict full ISP bypass request support = $supportsBypass ${ispBypassPolicy?.describeSupport().orEmpty()}")
        
        // Phase 1c: Initialize bit-depth precision policy and validate
        bitDepthPrecisionPolicy = BitDepthPrecisionPolicy(chars)
        if (strictHighEndEnabled()) {
            try {
                bitDepthPrecisionPolicy?.validateMandatoryBitDepth()
                Log.i(TAG, "Phase 1c: Bit-depth validation passed ✓")
                Log.i(TAG, bitDepthPrecisionPolicy?.getValidationReport() ?: "")
            } catch (e: CriticalHardwareException) {
                Log.e(TAG, "Phase 1c: CRITICAL - Device incompatible: ${e.message}", e)
                // In strict mode, this is fatal — do NOT continue
                throw e
            }
        }
        
        currentFocalLength = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        hardwareLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED

        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        supportsRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        supportsManualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        ultraHighResRawSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) && supportsRaw
        } else false

        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
        supportsOIS = oisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        val videoStabModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
        supportsVideoStabilization = videoStabModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        // F-STAB: surface the real advertised stabilization caps (previously discarded) so
        // they are verifiable from logcat/HUD instead of guessed. Pure formatter; no key change.
        stabilizationCapabilitySummary =
            CameraStabilizationCapabilityReport.describe(oisModes, videoStabModes).diagnostic
        Log.i(TAG, "Stabilization caps: $stabilizationCapabilitySummary")
        val sceneModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: IntArray(0)
        supportsHdrSceneMode = sceneModes.contains(CameraCharacteristics.CONTROL_SCENE_MODE_HDR)
        val tonemapModes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)
        supportsTonemapContrastCurve = tonemapModes.contains(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)

        highEndHardwareSupported = hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ||
                hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3

        hdrSupport = HdrDynamicRangePolicy.detectSupport(characteristics)
        runCatching {
            // Decides whether a zero-copy 10-bit recording path (camera -> encoder surface via a
            // 10-bit DynamicRangeProfile, no CPU readback) is feasible on this device — the only
            // route to full-fps 10-bit when the GPU encoder is a hard-wall and the CPU P010 copy
            // is bandwidth-bound.
            NeoLogger.i(
                "CameraController",
                "HDR dynamic-range profiles: anyHdr=${hdrSupport.anyHdrSupported} " +
                    "hlg10=${hdrSupport.hlg10Supported} hdr10=${hdrSupport.hdr10Supported} " +
                    "hdr10plus=${hdrSupport.hdr10PlusSupported} standardOnly=${hdrSupport.standardOnly}"
            )
        }

        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else null
        val digitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        minZoom = zoomRange?.lower ?: 1f
        maxZoom = zoomRange?.upper ?: digitalZoom.coerceAtLeast(1f)
        zoomRatio = zoomRatio.coerceIn(minZoom, maxZoom)

        maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        maxAwbRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) ?: 0

        val isoR = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (isoR != null) isoRange = Range(isoR.lower, isoR.upper)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val boostRange = chars.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
            postRawSensitivityBoostRange = boostRange?.let { Range(it.lower, it.upper) } ?: Range(100, 100)
        } else {
            postRawSensitivityBoostRange = Range(100, 100)
        }
        val expR = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (expR != null) exposureRange = Range(expR.lower, expR.upper)
        maxFrameDurationNs = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
            ?.takeIf { it > 0L }
            ?: exposureRange.upper
        val evR = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        if (evR != null) evRange = Range(evR.lower, evR.upper)
        evCompensationStepStops =
            chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat()
                ?.takeIf { it > 0f }
                ?: 1f
        if (Build.VERSION.SDK_INT >= 36) {
            val colorCorrectionModes =
                chars.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES) ?: IntArray(0)
            cachedColorCorrectionModes = colorCorrectionModes
            val cctRange = chars.get(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE)
            manualWhiteBalanceRange = cctRange?.let { Range(it.lower, it.upper) }
                ?: Range(2_000, 12_000)
            supportsCctWhiteBalance =
                cctRange != null &&
                    colorCorrectionModes.contains(CameraMetadata.COLOR_CORRECTION_MODE_CCT)
        } else {
            cachedColorCorrectionModes = IntArray(0)
            manualWhiteBalanceRange = Range(2_000, 12_000)
            supportsCctWhiteBalance = false
        }
        cachedAwbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?: intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)
        // Cache the sensor's DNG color calibration once so the device-true manual-WB solve does not
        // re-parse ColorSpaceTransforms every frame. Null on sensors without full calibration.
        deviceColorCalibration = DeviceColorCalibration.fromCharacteristics(chars)
        if (BuildConfig.DEBUG) {
            // One-shot capability + self-test diagnostic (per camera open): which manual-WB path this
            // sensor takes, and the device-true solve output at two reference temperatures.
            val cal = deviceColorCalibration
            val p3200 = cal?.solve(3200, 0)
            val p6500 = cal?.solve(6500, 0)
            Log.i(
                TAG,
                "DeviceTrueWB capability: calibrationPresent=${cal != null} " +
                    "supportsCct=$supportsCctWhiteBalance " +
                    "wbRange=${manualWhiteBalanceRange.lower}-${manualWhiteBalanceRange.upper} " +
                    "probe3200K(r=${p3200?.rGain}, b=${p3200?.bGain}, ccm=${p3200?.colorCorrectionTransform != null}) " +
                    "probe6500K(r=${p6500?.rGain}, b=${p6500?.bGain}, ccm=${p6500?.colorCorrectionTransform != null})"
            )
        }
        minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        fpsList = fpsRanges
        cachedFpsIntRanges = fpsRanges.map { CameraIntRange(it.lower, it.upper) }
        cachedAntibandingModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
            ?: IntArray(0)
        cacheVendorFpsKeys(chars)

        val streamMaps = resolveStreamConfigurationMaps(chars)
        val standardStreamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val maximumResolutionStreamMap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
        } else {
            null
        }
        if (streamMaps.isNotEmpty()) {
            // Keep the two Camera2 pixel-mode maps separate. Feeding an 8192x6144 size that exists
            // only in MAXIMUM_RESOLUTION into the default multi-output video session made the vivo
            // HAL accept the reader but deliver a 1920x1440 JPEG. The dedicated maximum-res capture
            // path below changes pixel mode and stream graph before using that tier.
            val standardJpegSizes = standardStreamMap?.let { map ->
                map.getHighResolutionOutputSizesSafely(ImageFormat.JPEG) +
                    map.getOutputSizesSafely(ImageFormat.JPEG)
            }.orEmpty()
            val maximumResolutionJpegSizes = maximumResolutionStreamMap?.let { map ->
                map.getHighResolutionOutputSizesSafely(ImageFormat.JPEG) +
                    map.getOutputSizesSafely(ImageFormat.JPEG)
            }.orEmpty()
            val photoResolutionPlan = StillPhotoResolutionPolicy.resolve(
                standardMapSizes = standardJpegSizes.map { it.toCameraStreamSize() },
                maximumResolutionMapSizes = maximumResolutionJpegSizes.map { it.toCameraStreamSize() }
            )
            normalSessionPhotoSizes = photoResolutionPlan.normalSessionSizes.map { it.toAndroidSize() }
            maximumResolutionSessionPhotoSizes =
                photoResolutionPlan.maximumResolutionSessionSizes.map { it.toAndroidSize() }
            maximumPixelModeConcurrentPhotoSizes =
                photoResolutionPlan.maximumPixelModeConcurrentSizes.map { it.toAndroidSize() }
            photoSizes = photoResolutionPlan.allAdvertisedSizes.map { it.toAndroidSize() }
            maxPhotoSize = photoSizes.maxByOrNull { CameraSensorInfo.sizeArea(it) }
            Log.i(
                TAG,
                "JPEG tiers normal=${normalSessionPhotoSizes.firstOrNull()?.let { "${it.width}x${it.height}" } ?: "none"} " +
                    "maximum=${maximumResolutionSessionPhotoSizes.firstOrNull()?.let { "${it.width}x${it.height}" } ?: "none"} " +
                    "maxPixelConcurrent=${maximumPixelModeConcurrentPhotoSizes.firstOrNull()?.let { "${it.width}x${it.height}" } ?: "none"}"
            )
            previewSizes = runCatching {
                (standardStreamMap ?: streamMaps.first()).getOutputSizes(SurfaceTexture::class.java)?.toList()
            }.getOrNull().orEmpty()
            
            // Phase 1b: Full-resolution bypass for recording sizes
            val mediaRecorderSizes = streamMaps
                .flatMap { it.getOutputSizesSafely(MediaRecorder::class.java, "MediaRecorder") }
                .distinctSortedCameraSizes()
            val mediaCodecSizes = streamMaps
                .flatMap { it.getOutputSizesSafely(MediaCodec::class.java, "MediaCodec") }
                .distinctSortedCameraSizes()
            val highResolutionRecordingSizes = streamMaps
                .flatMap { it.highResolutionRecordingOutputSizes() }
                .distinctSortedCameraSizes()
            val sensorBackedRecordingSizes = streamMaps
                .flatMap { it.sensorBackedRecordingOutputSizes() }
                .distinctSortedCameraSizes()
            val policyMaximumRecordingSize = streamMaps
                .mapNotNull { sensorReadoutPolicy?.getMaximumRawVideoResolution(it) }
                .maxByOrNull { CameraSensorInfo.sizeArea(it) }
                ?.takeIf { candidate -> sensorBackedRecordingSizes.any { it == candidate } }
            val maxRecordingSize = listOfNotNull(policyMaximumRecordingSize)
                .plus(highResolutionRecordingSizes)
                .plus(mediaCodecSizes)
                .plus(mediaRecorderSizes)
                .maxByOrNull { CameraSensorInfo.sizeArea(it) }

            recordingSizes = (
                listOfNotNull(maxRecordingSize, policyMaximumRecordingSize) +
                    highResolutionRecordingSizes +
                    mediaCodecSizes +
                    mediaRecorderSizes
                ).distinctSortedCameraSizes()
            videoSizes = recordingSizes
            
            val activeArrayRawCandidates = streamMaps
                .flatMap { it.getOutputSizesSafely(ImageFormat.RAW_SENSOR) }
                .distinctSortedCameraSizes()
            val maxResolutionRawCandidates = streamMaps
                .flatMap { it.getHighResolutionOutputSizesSafely(ImageFormat.RAW_SENSOR) }
                .distinctSortedCameraSizes()
            rawSizes = (maxResolutionRawCandidates + activeArrayRawCandidates).distinctSortedCameraSizes()
            maxRawSize = rawSizes.maxByOrNull { CameraSensorInfo.sizeArea(it) }
            // Tier split for the RAW capture-resolution policy (kept separate from the merged
            // rawSizes pool so the FULL_ACTIVE_ARRAY fallback never accidentally selects a max-res
            // dimension without SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION engaged).
            activeArrayRawSize = activeArrayRawCandidates.maxByOrNull { CameraSensorInfo.sizeArea(it) }
            maxResolutionRawSize = maxResolutionRawCandidates.maxByOrNull { CameraSensorInfo.sizeArea(it) }
            yuvSizes = (
                streamMaps.flatMap { it.getHighResolutionOutputSizesSafely(ImageFormat.YUV_420_888) } +
                    streamMaps.flatMap { it.getOutputSizesSafely(ImageFormat.YUV_420_888) }
                ).distinctSortedCameraSizes()
            maxYuvSize = yuvSizes.maxByOrNull { CameraSensorInfo.sizeArea(it) }

            val safeExposedReadoutSizes = (
                highResolutionRecordingSizes +
                    sensorBackedRecordingSizes +
                    mediaCodecSizes +
                    mediaRecorderSizes +
                    rawSizes +
                    yuvSizes
                ).map { it.toCameraStreamSize() }.toSet()
            cameraVendorReadoutScanReport = scanVendorReadoutCandidates(
                characteristics = chars,
                safeExposedSizes = safeExposedReadoutSizes
            )
            val safeVendorRecordingSizes = cameraVendorReadoutScanReport.safeCandidates
                .map { it.size.toAndroidSize() }
                .filter { candidate -> safeExposedReadoutSizes.contains(candidate.toCameraStreamSize()) }
            if (safeVendorRecordingSizes.isNotEmpty()) {
                recordingSizes = (recordingSizes + safeVendorRecordingSizes).distinctSortedCameraSizes()
                videoSizes = recordingSizes
            }

            Log.i(
                TAG,
                "Full sensor stream candidates highRes=${highResolutionRecordingSizes.firstOrNull()?.let { "${it.width}x${it.height}" } ?: "none"} " +
                    "raw=${maxRawSize?.let { "${it.width}x${it.height}" } ?: "none"} " +
                    "yuv=${maxYuvSize?.let { "${it.width}x${it.height}" } ?: "none"} " +
                    "record=${maxRecordingSize?.let { "${it.width}x${it.height}" } ?: "none"}"
            )
            Log.i(TAG, "Vendor readout scan: ${cameraVendorReadoutScanReport.toLogLine()}")
        }

        cameraCapabilityInventory = buildCameraCapabilityInventory(
            cameraId = cameraId,
            characteristics = chars,
            streamMaps = streamMaps,
            capabilities = caps
        ).also { inventory ->
            rebuildCameraStreamPlans(inventory)
            val requestPlan = ISPBypassRequestPlanPolicy.resolve(chars.toIspBypassAvailability())
            cameraBypassCapabilityReport = CameraBypassCapabilityScorePolicy.resolve(
                capability = inventory,
                requestPlan = requestPlan,
                vendorScanReport = cameraVendorReadoutScanReport
            ).also { report ->
                Log.i(TAG, "ISP-off capability score: ${report.toLogLine()}")
                // Issue 3: the capability report is a DESCRIPTIVE real-hardware profile (e.g.
                // "HAL Pro Cinema 83/100 | RAW SENSOR RAW LIMITED ISP | ..."), NOT a fault. Emitting it
                // through the pipeline-WARNING channel made uiState.hardwareWarning non-null, which the
                // Pipeline Core health chip then rendered as a permanent generic "Hardware advisory" even
                // while the pipeline was perfectly healthy and the capability actually EXISTS. It is now
                // surfaced honestly instead: as the capability badge and, with its EXACT per-cause
                // limitation reasons, in the Pipeline Core diagnostics (via getCameraBypassCapabilityReport()).
                // A genuine hardware advisory (thermal / memory / background restriction) still flows
                // through emitCameraPipelineWarning() from its own dedicated call sites.
            }
        }

        buildTonemapCurves(maxCurvePoints = 64)
        refreshFrameGeometry()
    }

    override fun setFPS(fps: Int): Int {
        val streamFpsOverride = resolveCurrentStreamBasedFpsOverridePlan(requestedFpsOverride = fps)
        val fpsPlan = CameraFpsRequestPolicy.resolve(
            requestedFps = fps,
            availableRanges = cachedFpsIntRanges,
            requestedExposureNs = resultFlow.value?.exposureNs ?: 0L,
            exposureRange = CameraLongRange(exposureRange.lower, exposureRange.upper),
            streamBasedFpsOverride = streamFpsOverride
        )
        updateRequestedCameraFpsHint(fps)
        val previousFps = targetCameraFps
        val previousSidecarMode = readoutSidecarMode
        targetCameraFps = fpsPlan.appliedFps
        paramsFlow.value = paramsFlow.value.copy(fps = fpsPlan.appliedFps)
        resetReadoutSidecarFallback()

        val sidecarChanged = previousSidecarMode != readoutSidecarMode
        if (cameraDevice != null && (previousFps != targetCameraFps || sidecarChanged)) {
            if (sidecarChanged && recordingActive) {
                refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true)
            } else {
                val generation = sessionGeneration.get()
                val session = captureSession
                if (session != null) {
                    runOnCameraThread { startPreview(generation, session) }
                }
            }
        }
        return fpsPlan.appliedFps
    }

    override fun triggerTapAF(nx: Float, ny: Float) {
        clearLensFocusOverridesForTapAf()
        val request = TapFocusRequestPolicy.resolve(
            activeArray = activeArray?.let { CameraRect(it.left, it.top, it.right, it.bottom) },
            normalizedX = nx,
            normalizedY = ny,
            maxAfRegions = maxAfRegions,
            maxAeRegions = maxAeRegions,
            frameGeometry = frameGeometryFlow.value,
            afSupported = hasAutoFocusCapability()
        )
        val focusRegion = request.focusRegion?.toAndroidRect()
        val exposureRegion = request.exposureRegion?.toAndroidRect()
        when {
            request.shouldTriggerAf && focusRegion != null -> {
                triggerSubjectAutoFocus(
                    focusRegion = focusRegion,
                    exposureRegion = exposureRegion ?: focusRegion,
                    focusNormalizedX = nx,
                    focusNormalizedY = ny
                )
            }
            request.shouldTriggerAf -> {
                // AF supported but no AF metering regions (maxAfRegions == 0): fire a region-less
                // full-frame AF scan so tap-to-focus and the startup centre-prime actually focus.
                runAutoFocusTrigger(focusRegion = null, exposureRegion = exposureRegion)
            }
            exposureRegion != null -> {
                setSubjectMeteringRegions(af = null, ae = exposureRegion)
            }
            else -> updatePreview()
        }
    }

    override fun setDeviceRotationDegrees(degrees: Int) {
        deviceRotationDegrees = degrees
    }

    override fun resetPreviewActivityTracking() {
        previewHealthMonitor.reset()
    }

    override fun hasRecentPreviewActivity(): Boolean =
        previewHealthMonitor.hasRecentActivity(withinMs = 1_500L)
    override fun videoOrientationHint(): Int =
        CameraRecordingOrientationPolicy.resolve(
            frameGeometry = frameGeometryFlow.value,
            sensorOrientationDegrees = sensorOrientation(),
            deviceRotationDegrees = deviceRotationDegrees,
            lensFacing = facing
        )

    override val hardwareLevelName: String get() = hardwareLevelLabel(hardwareLevel)
    override val focusDistanceRange: Float get() = minFocusDist

    override fun destroy() {
        // Release the process-global flag callbacks registered in createSession (each captures `this`) so this
        // dead controller is not retained after teardown until the next controller overwrites the slots.
        ColorDriftEnforcementFlag.registerOnChanged(null)
        MeteringDampingFlag.registerOnChanged(null)
        SessionRebuildColorHoldFlag.registerOnChanged(null)
        DeviceTrueWhiteBalanceFlag.registerOnChanged(null)
        RawPreviewViewableTonemapFlag.registerOnChanged(null)
        CinematicFocusPullFlag.registerOnChanged(null)
        CinematicFocusPullDebugCommands.register(null)
        releaseCameraResources(true)
        stopBackground()
    }

    private fun startMotionTelemetry() {
        val handler = backgroundHandler ?: return
        val sensor = gyroscopeSensor ?: return
        if (gyroscopeTelemetryRegistered.compareAndSet(false, true)) {
            val registered = sensorManager?.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_FASTEST,
                handler
            ) == true
            if (!registered) {
                gyroscopeTelemetryRegistered.set(false)
            }
        }
    }

    private fun stopMotionTelemetry() {
        if (gyroscopeTelemetryRegistered.compareAndSet(true, false)) {
            sensorManager?.unregisterListener(this)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE || event.values.size < 3) return
        motionTelemetry = CameraMotionTelemetry.fromGyroscope(
            base = motionTelemetry,
            timestampNs = event.timestamp,
            gyroXRadPerSec = event.values[0],
            gyroYRadPerSec = event.values[1],
            gyroZRadPerSec = event.values[2]
        )
        motionTelemetryRingBuffer.push(motionTelemetry)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ── s1-f04 / CP60: read-only gyro telemetry hand-offs (DORMANT, DEVICE-GATED) ────────────────────
    // The capture side above (onSensorChanged / processResult ring pushes) is unchanged. These read-only
    // hand-offs turn the live motion ring into the timestamp-reconciled orientation / MFSR global-motion
    // / ring-health that the still-dormant gyro-EIS warp (s3-f06) and MFSR (s1-f06) will consume. They
    // are gated OFF by default (gyroEisTelemetryConsumerEnabled), so they run for nobody and apply to no
    // pixel; enabling requires the on-device gyro↔SENSOR_TIMESTAMP clock + OIS sign/scale calibration.

    private val gyroHandoffMaxSamples = 256

    /**
     * Timestamp-reconciled orientation over the latest frame's exposure window, or null while the
     * consumer is dormant / no frame timing is known / the gyro window is under-sampled. Reads only the
     * GYRO-domain samples of the live ring and applies nothing — this is the dormant feed for s3-f06.
     */
    fun gyroExposureWindowHandoff(): GyroExposureWindowReconciler.ReconciledOrientation? {
        if (!gyroEisTelemetryConsumerEnabled) return null
        val frame = resultFlow.value ?: return null
        if (frame.timestampNs <= 0L) return null
        val samples = motionTelemetryRingBuffer.snapshotGyroOldestFirst(gyroHandoffMaxSamples)
        if (samples.size < 2) return null
        return GyroExposureWindowReconciler.reconcile(
            samples = samples,
            sensorTimestampNs = frame.timestampNs,
            exposureDurationNs = frame.exposureNs,
            gyroToCameraOffsetNs = gyroToCameraClockOffsetNs
        )
    }

    /**
     * Per-frame global-motion term (s1-f06 MFSR form) of the latest frame relative to the burst
     * reference frame ([referenceTimestampNs] / [referenceExposureNs]), OIS-fused against the latest OIS
     * sample. Null while dormant or under-sampled. Produces a value; applies nothing.
     */
    fun gyroGlobalMotionForMfsr(
        referenceTimestampNs: Long,
        referenceExposureNs: Long,
        focalLengthPx: Float
    ): GyroGlobalMotionTerm.GlobalMotion? {
        if (!gyroEisTelemetryConsumerEnabled) return null
        val frame = resultFlow.value ?: return null
        if (frame.timestampNs <= 0L || referenceTimestampNs <= 0L) return null
        val samples = motionTelemetryRingBuffer.snapshotGyroOldestFirst(gyroHandoffMaxSamples)
        if (samples.size < 2) return null
        val refMid = GyroExposureWindowReconciler
            .exposureWindow(referenceTimestampNs, referenceExposureNs, gyroToCameraClockOffsetNs).midNs
        val frameMid = GyroExposureWindowReconciler
            .exposureWindow(frame.timestampNs, frame.exposureNs, gyroToCameraClockOffsetNs).midNs
        val ois = motionTelemetryRingBuffer.latestOisSample()
        return GyroGlobalMotionTerm.betweenOisFused(
            samples = samples,
            refMidNs = refMid,
            frameMidNs = frameMid,
            focalLengthPx = focalLengthPx,
            oisShiftXPixels = ois?.oisShiftXPixels ?: 0f,
            oisShiftYPixels = ois?.oisShiftYPixels ?: 0f
        )
    }

    /**
     * Ring-health report over the latest GYRO-domain window (monotonicity / contiguity / gap bound). A
     * diagnostic invariant producer — always computable, wired to no live behavior. [maxExpectedGapNs]
     * is the largest "no-drop" inter-sample interval for the device's gyro rate.
     */
    fun gyroRingHealth(maxExpectedGapNs: Long): GyroRingHealth.RingHealthReport =
        GyroRingHealth.analyse(
            motionTelemetryRingBuffer.snapshotGyroOldestFirst(gyroHandoffMaxSamples),
            maxExpectedGapNs
        )

    // ── s3-f05 / CP60: live per-frame gyro-EIS orientation consumer (DORMANT, DEVICE-GATED) ───────────
    // The s1-f04 hand-offs above are PULL-based (zero callers). This is the PRODUCTION consumer s3-f05
    // adds: processResult calls publishGyroEisFrameOrientation each frame, which — only while the consumer
    // is enabled — integrates the live gyro ring into the per-frame EIS orientation and publishes it for
    // s3-f06's warp grid. While dormant it is a single boolean check and publishes nothing; even when
    // enabled the orientation reaches no pixel until s3-f06's GPU warp is turned on device-side.

    /** Per-frame integration of the live gyro stream into the EIS orientation feed. Gated; no pixel effect. */
    private fun publishGyroEisFrameOrientation(frame: LiveResult) {
        if (!gyroEisTelemetryConsumerEnabled) return
        if (frame.timestampNs <= 0L) {
            latestEisFrameOrientation = null
            return
        }
        latestEisFrameOrientation = GyroEisOrientationFeed.frameOrientation(
            samples = motionTelemetryRingBuffer.snapshotGyroOldestFirst(gyroHandoffMaxSamples),
            sensorTimestampNs = frame.timestampNs,
            exposureDurationNs = frame.exposureNs,
            gyroToCameraOffsetNs = gyroToCameraClockOffsetNs,
        )
    }

    /**
     * The latest per-frame EIS orientation the live consumer published, or null while the consumer is
     * dormant. This is the dormant input s3-f06's warp grid producer reads; it drives no pixel until EIS
     * is enabled device-side.
     */
    fun latestGyroEisFrameOrientation(): EisFrameOrientation? =
        if (gyroEisTelemetryConsumerEnabled) latestEisFrameOrientation else null

    // ── s9 / CP-QV22: dormant custom-EIS consumer seam (DEVICE-GATED, no GPU upload path) ─────────────
    // Lazily build the [StabilizationSessionController] from the real camera geometry the first time the
    // (default-OFF) consumer runs. Only ever called from inside a gyroEisTelemetryConsumerEnabled guard, so
    // on a stock device this method is never entered and the controller is never allocated. The config uses
    // the reported physical/active-array geometry where present and documented sane fallbacks otherwise
    // (this is a dormant seam). [StabilizationSessionController.mode] stays at its OFF default (identity grid).
    private fun stabilizationSessionOrBuild(): StabilizationSessionController {
        stabilizationSession?.let { return it }
        val active = activeArray
        val traits = physicalImagingTraits
        val config = StabilizationSessionConfigPolicy.build(
            focalLengthMm = traits.focalLengthMm,
            sensorWidthMm = traits.sensorWidthMm,
            activeArrayWidthPx = active?.width() ?: 0,
            activeArrayHeightPx = active?.height() ?: 0,
        )
        return StabilizationSessionController(config).also { stabilizationSession = it }
    }

    /**
     * s9 / CP-QV22 — the dormant per-frame custom-EIS consumer. Called from processResult ONLY inside a
     * gyroEisTelemetryConsumerEnabled guard, so it never runs (and the controller is never built) on a stock
     * device. Advances the trajectory smoother and produces a per-scanline warp grid that goes NOWHERE:
     * there is no GPU EIS upload path, and the controller's mode is OFF (identity grid). Pure plumbing.
     */
    private fun consumeGyroEisFrame(frame: LiveResult) {
        // MANDATORY null-guard: latestGyroEisFrameOrientation() is null when the flag is off (unreachable
        // here) AND can be null when enabled if the frame timestamp was <=0 — skip that frame safely.
        val orientation = latestGyroEisFrameOrientation() ?: return
        val samples = motionTelemetryRingBuffer.snapshotGyroOldestFirst(gyroHandoffMaxSamples)
        val ois = motionTelemetryRingBuffer.latestOisSample()
        // Per-row readout ≈ full-frame rolling-shutter skew / row count (0 if skew unreported).
        val rows = (activeArray?.height() ?: 0).coerceAtLeast(1)
        val rowReadoutNs = physicalImagingTraits.rollingShutterSkewNs.coerceAtLeast(0L) / rows
        stabilizationSessionOrBuild().onFrame(
            orientation = orientation,
            samples = samples,
            frameStartNs = frame.timestampNs,
            rowReadoutNs = rowReadoutNs,
            oisShiftXPixels = ois?.oisShiftXPixels ?: 0f,
            oisShiftYPixels = ois?.oisShiftYPixels ?: 0f,
        )
        // Grid intentionally discarded: no GPU EIS upload path exists (dormant seam, DEVICE-GATED).
    }

    fun getCameraCharacteristics(): CameraCharacteristics? = characteristics

    internal fun getCameraCapabilityInventory(): CameraCapabilityInventory? = cameraCapabilityInventory

    internal fun getCameraStreamPlan(mode: CinemaVideoMode): CameraStreamPlan? = cameraStreamPlans[mode]

    internal fun getCameraBypassCapabilityReport(): CameraBypassCapabilityReport? = cameraBypassCapabilityReport

    internal fun getCameraVendorReadoutScanReport(): CameraVendorReadoutScanReport = cameraVendorReadoutScanReport

    private fun emitCameraPipelineWarning(message: String?) {
        val warning = message?.takeIf { it.isNotBlank() } ?: return
        // Persist to the on-device camera diagnostics log with an immediate flush. These warnings
        // only fire on real session/encoder fallback or failure, and this device family suppresses
        // third-party logcat, so the file is the only place the reason survives.
        runCatching { NeoLogger.critical("CameraController", warning) }
        onCameraPipelineWarning?.invoke(warning)
    }

    /**
     * Asks the framework whether a stream combination is actually configurable BEFORE we attempt it.
     * Used as a diagnostic so the on-device camera log records, for every session, whether the
     * negotiated Camera2 stream graph is legal on this exact hardware. Pure query, no side effects.
     */
    private fun describeSessionConfigurationSupport(
        dev: CameraDevice,
        config: SessionConfiguration
    ): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (dev.isSessionConfigurationSupported(config)) "SUPPORTED" else "UNSUPPORTED"
            } catch (e: UnsupportedOperationException) {
                "UNKNOWN(query-unsupported)"
            } catch (e: IllegalArgumentException) {
                "UNKNOWN(${e.message})"
            } catch (e: Throwable) {
                "UNKNOWN(${e.javaClass.simpleName})"
            }
        } else {
            "UNKNOWN(pre-Q)"
        }
}
