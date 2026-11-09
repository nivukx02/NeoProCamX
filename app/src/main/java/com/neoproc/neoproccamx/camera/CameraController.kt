package com.neoproc.neoproccamx.camera

import android.app.ActivityManager
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
import android.hardware.camera2.params.DynamicRangeProfiles
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
import com.neoproc.neoproccamx.recording.Npv10CaptureSize
import com.neoproc.neoproccamx.recording.Npv10CaptureSourcePlan
import com.neoproc.neoproccamx.recording.Npv10CaptureSourcePolicy
import com.neoproc.neoproccamx.recording.Npv10InFlightBufferPolicy
import com.neoproc.neoproccamx.utils.OrientationManager
import com.neoproc.neoproccamx.core.RuntimeStageTelemetry
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.niv99.Niv99ImageIngressSchedulingPolicy
import com.neoproc.neoproccamx.niv99.Niv99MetadataIngressSchedulingPolicy
import com.neoproc.neoproccamx.pipeline.PipelineHealthMonitor
import com.neoproc.neoproccamx.pipeline.SynchronousImageIngressGate
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
        /** Large-memory strict P010 recording vault; isolated from PRIVATE/preview reader depth. */
        const val STRICT_MAX_HARDWARE_IMPORT_P010 = 55
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

/** Exact Camera2 ordering boundary used to reopen a paused RAW-original writer. */
internal data class RawCaptureResumeBoundary(
    val sensorTimestampNs: Long,
    val sessionGeneration: Long,
    val frameNumber: Long
)

/** One private Camera2 request and its single caller/callback completion claim. */
private class RawCaptureResumeBoundaryRequestToken(
    val session: CameraCaptureSession,
    val sessionGeneration: Long,
    val commitAdmission: (RawCaptureResumeBoundary) -> Boolean,
    val rollbackAdmission: () -> Unit
) {
    /** 0=pending, 1=capture callback owns completion, 2=caller cancelled, 3=completed. */
    val completionState = AtomicInteger(0)
    val markerSequenceId = AtomicInteger(-1)
    val admissionCommitted = AtomicBoolean(false)
    val repeatingRestartIssued = AtomicBoolean(false)
    val completion = CompletableFuture<Boolean>()
}

private data class P010RepeatingFrameProof(
    val frameNumber: Long,
    val sensorTimestampNs: Long,
    val receivedAtElapsedRealtimeNs: Long
)

internal data class CameraRepeatingReadinessSnapshot(
    val publicationGeneration: Long,
    val sessionGeneration: Long,
    val sequenceId: Int,
    val firstFrameSensorTimestampNs: Long?,
    val firstFrameReceivedAtElapsedRealtimeNs: Long?
)

private data class ActiveCameraRepeatingSequence(
    val publicationGeneration: Long,
    val identity: P010RepeatingSequenceIdentity<CameraCaptureSession>,
    val request: CaptureRequest,
    val p010Surface: Surface?,
    val firstFrameProof: CompletableFuture<P010RepeatingFrameProof> = CompletableFuture()
)

/** One fail-closed Camera2 producer-stop transaction for the NPV10 P010 reader. */
private class P010StopBoundaryRequestToken(
    val session: CameraCaptureSession,
    val sessionGeneration: Long,
    val reader: ImageReader,
    val p010Surface: Surface,
    val repeatingSequence: ActiveCameraRepeatingSequence,
    val state: P010StopBoundaryState<CameraCaptureSession>,
    val capturedProducerTail: P010ProducerTailTracker<CameraCaptureSession>? = null
)

/** Delayed Camera2 cleanup is authorized only by the exact still-active stop-fence token. */
internal object P010StopFenceAuthorizationPolicy {
    fun <T : Any> delayedStopAuthorized(requestedToken: T?, activeToken: T?): Boolean =
        requestedToken != null && activeToken === requestedToken

    /**
     * Revalidates the live owner and executes its logical stop as one transaction with owner
     * retirement/replacement. [activeToken] is deliberately a provider: accepting a pre-read value
     * would reopen the check/use race this boundary is designed to close.
     */
    fun <T : Any> executeDelayedStop(
        requestedToken: T?,
        activeToken: () -> T?,
        serializationLock: Any,
        stopAction: () -> Unit
    ): Boolean = synchronized(serializationLock) {
        val authorized = delayedStopAuthorized(requestedToken, activeToken())
        if (authorized) stopAction()
        authorized
    }
}

internal data class P010StopBoundaryCleanupResult(
    val sinkDetached: Boolean,
    val finalizationStarted: Boolean
) {
    val completed: Boolean get() = sinkDetached && finalizationStarted
}

internal data class CameraReaderCloseBarrierResult(
    val closeCompleted: Boolean,
    val closeActionInvoked: Boolean,
    val failure: Throwable?
)

data class CameraReaderCloseRequest(
    val jpeg: Boolean,
    val raw: Boolean,
    val yuv: Boolean,
    val hardwareImport: Boolean
) {
    val requiresStillCaptureDrain: Boolean get() = jpeg || raw
    val requiresRawVideoDrain: Boolean get() = raw
    val requiresFramePipelineDrain: Boolean get() = yuv || hardwareImport
}

/**
 * Pure execution contract for ImageReader retirement. A bound callback owns both pipeline sealing and
 * the bounded idle proof; consequently the destructive action can only be reached from inside that
 * callback. A false/throwing barrier never triggers a fallback close.
 */
internal object CameraReaderCloseBarrierPolicy {
    fun execute(
        barrier: ((Long, () -> Unit) -> Boolean)?,
        timeoutMs: Long,
        directCloseAllowed: Boolean,
        closeAction: () -> Unit
    ): CameraReaderCloseBarrierResult {
        val closeActionInvoked = AtomicBoolean(false)
        var failure: Throwable? = null
        val closeCompleted = try {
            if (barrier == null) {
                if (!directCloseAllowed) {
                    false
                } else {
                    check(closeActionInvoked.compareAndSet(false, true)) {
                        "ImageReader direct close action invoked more than once"
                    }
                    closeAction()
                    true
                }
            } else {
                val barrierScopeActive = AtomicBoolean(true)
                val barrierCompleted = try {
                    barrier(timeoutMs) {
                        check(barrierScopeActive.get()) {
                            "ImageReader close action escaped its pipeline barrier scope"
                        }
                        check(closeActionInvoked.compareAndSet(false, true)) {
                            "ImageReader close action invoked more than once"
                        }
                        closeAction()
                    }
                } finally {
                    // A callback may not retain and invoke the destructive action after returning.
                    barrierScopeActive.set(false)
                }
                barrierCompleted && closeActionInvoked.get()
            }
        } catch (throwable: Throwable) {
            if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
            failure = throwable
            false
        }
        return CameraReaderCloseBarrierResult(
            closeCompleted = closeCompleted,
            closeActionInvoked = closeActionInvoked.get(),
            failure = failure
        )
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
        private const val TAP_AF_SCAN_TIMEOUT_MS = 1_200L
        private const val READER_CLOSE_FRAME_PIPELINE_IDLE_TIMEOUT_MS = 1_000L
        // A replacement session may configure before the outgoing Qualcomm/Vivo session has
        // delivered onClosed (610 ms was observed on the I2405). Keep every trailing graph rebuild
        // behind that definitive ownership callback. The timeout is only a vendor-callback omission
        // escape hatch; normal hot swaps complete immediately from onClosed.
        private const val HOT_SWAP_SESSION_CLOSE_CALLBACK_TIMEOUT_MS = 2_500L
        /** One fresh-snapshot retry is enough to absorb a SurfaceView/EGL ownership handoff. */
        private const val MAX_SURFACE_SESSION_RECOVERY_ATTEMPTS = 1
        private const val STALE_SESSION_REFRESH_OWNER_TIMEOUT_MS = 15_000L
        private const val STILL_CAPTURE_SESSION_WAIT_INTERVAL_MS = 80L
        private const val STILL_CAPTURE_SESSION_WAIT_MAX_ATTEMPTS = 250
        private const val STILL_HDR_SERIAL_FRAME_SETTLE_MS = 55L
        private const val CAMERA_AVAILABILITY_SETTLE_MS = 80L
        private const val CAPTURE_CALLBACK_SLOW_NS = 20_000_000L
        private const val NIV99_FAST_LANE_SLOW_NS = 8_000_000L
        private const val CAPTURE_CALLBACK_TIMING_LOG_INTERVAL_NS = 1_000_000_000L
        // CP17: frames to let the HAL settle into the requested ISP state before reading it back.
        private const val ISP_BYPASS_READBACK_SETTLE_FRAMES = 5
        // Manual green-magenta WB tint range (P0.1) — matches CinemaAutoWhiteBalanceRequestPolicy.
        private const val MANUAL_WB_TINT_MIN = -150
        private const val MANUAL_WB_TINT_MAX = 150
        // API 36 constants are kept as their stable platform values so guarded use of the
        // dynamically discovered key remains lint-clean on the app's lower minSdk.
        private const val AE_PRIORITY_MODE_OFF = 0
        private const val AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY = 1
        private const val AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY = 2
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
            supportsVideoStabilization =
                videoStabilizationModes.contains(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                ) || videoStabilizationModes.contains(
                    CameraVideoStabilizationRequestPolicy.PREVIEW_STABILIZATION_MODE
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
            recordingActive = RawSensorReaderDepthPolicy.requiresSustainedCaptureDepth(
                rawVideoPrearmEnabled = rawVideoPrearmEnabled,
                streamGraphMode = streamGraph.mode,
                rawRouteReason = streamGraph.rawSensor.reason
            )
        )
    private fun yuvReaderDepth(): Int = if (strictHighEndEnabled()) CameraControllerConfig.ReaderDepth.STRICT_MAX_YUV else CameraControllerConfig.ReaderDepth.MAX_YUV
    private fun npv10AnalysisImageFormat(): Int =
        if ((npv10TenBitCaptureEnabled ||
                (modeSwitchStandbyAnalysisTenBit &&
                    modeSwitchStandbyAnalysisTargetSize != null &&
                    modeSwitchStandbyOutputsAllowed())) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
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

    private val deviceTotalMemoryBytes: Long by lazy(LazyThreadSafetyMode.NONE) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        activityManager?.let { manager ->
            ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem
        } ?: 0L
    }

    private fun hardwareImportReaderDepth(size: Size, imageFormat: Int): Int =
        HardwareImportReaderDepthPolicy.resolve(
            size = size.toCameraStreamSize(),
            p010 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && imageFormat == ImageFormat.YCBCR_P010,
            strictHighEnd = strictHighEndEnabled(),
            deviceTotalMemoryBytes = deviceTotalMemoryBytes
        ).maxImages
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
        val effectiveRecordingSessionActive =
            recordingActive || processedRecordingSessionPreparationActive
        val isolatedMaximumStill = maximumResolutionStillCaptureActive && !recordingActive
        val isolatedP010Still = p010CadenceStillCaptureActive && !recordingActive
        val isolatedStill = isolatedMaximumStill || isolatedP010Still
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
        val standbyAnalysisRequested =
            modeSwitchStandbyOutputsAllowed() && modeSwitchStandbyAnalysisTargetSize != null
        val useP010Readout = npv10TenBitCaptureEnabled ||
            (standbyAnalysisRequested && modeSwitchStandbyAnalysisTenBit)
        val npv10ReadoutTargetSize = npv10CaptureTargetSize
            ?: modeSwitchStandbyAnalysisTargetSize.takeIf { standbyAnalysisRequested }
            ?: analysisTargetSize
        val readoutSizes = if (useP010Readout) {
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
                recordingActive = effectiveRecordingSessionActive,
                // A dedicated maximum-resolution idle still uses preview + JPEG only. The temporary
                // graph intentionally detaches pre-armed P010/RAW/encoder outputs so the maximum-map
                // JPEG cannot be silently substituted by the HAL.
                videoPreviewActive = videoPreviewActive || isolatedStill,
                uncompressedReadoutRecording = npv10TenBitCaptureEnabled && !isolatedStill,
                rawVideoCaptureEnabled = rawVideoCaptureEnabled,
                sensorLinearNdrCaptureEnabled = sensorLinearNdrCaptureEnabled,
                rawVideoPrearmEnabled = rawVideoPrearmEnabled && !isolatedStill,
                rawVideoPreviewActive = rawPreviewCaptureEnabled && !isolatedStill,
                recordingSafeSessionFallback = recordingSafeSessionFallback,
                hardwareImportRequested = !isolatedStill &&
                    includeHardwareImport && hardwareImportTargetSize != null,
                internalEncoderHardwareImportActive = internalRecordingHardwareImportEnabled,
                readoutSidecarMode = readoutSidecarMode,
                analysisTargetSize = (if (npv10TenBitCaptureEnabled || standbyAnalysisRequested) {
                    npv10ReadoutTargetSize
                } else {
                    analysisTargetSize
                })?.toCameraStreamSize(),
                recordingReadoutOverrideSize = recordingReadoutOverrideSize?.toCameraStreamSize(),
                recordingEncoderTargetSize = recordingEncoderTargetSize?.toCameraStreamSize(),
                previewAnalysisRequested = previewAnalysisRequested && !isolatedStill,
                optionalAnalysisSuspendedForRecording =
                    recordingThermalAnalysisIsolationEnabled ||
                        processedRecordingSessionPreparationActive,
                modeSwitchRawStandbyRequested =
                    !isolatedStill && modeSwitchStandbyOutputsAllowed(),
                modeSwitchAnalysisStandbyRequested =
                    !isolatedStill && standbyAnalysisRequested
            ),
            inventory = CinemaStreamGraphInventory(
                supportsRaw = supportsRaw,
                rawOutputSizes = rawSizes.map { it.toCameraStreamSize() },
                yuvOutputSizes = readoutSizes.map { it.toCameraStreamSize() },
                jpegOutputSizes = jpegSizes.map { it.toCameraStreamSize() },
                encoderOutputSizes = encoderSizes.map { it.toCameraStreamSize() },
                fallbackPhotoSize = fallbackPhotoSize.toCameraStreamSize(),
                activeArrayRawSize = activeArrayRawSize?.toCameraStreamSize()
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
    @Volatile private var lastPreviewFrameCallbackElapsedNs = 0L
    private var lastUnifiedVideoControlSensorTimestampNs = 0L
    private var lastUnifiedVideoControlMode: UnifiedVideoControlMode? = null
    private var lastUnifiedVideoControlSessionGeneration = -1L
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
    // Camera2 is allowed to enqueue the outgoing session's onClosed immediately before the
    // replacement onConfigured callback. Remember only that narrow, in-flight ordering case so the
    // release fence cannot miss an already-proven close acknowledgement.
    private val sessionsClosedBeforeReleaseBarrier =
        java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<CameraCaptureSession, Boolean>()
        )
    private val configuredSessionSurfaces =
        java.util.IdentityHashMap<CameraCaptureSession, MutableSet<Surface>>()
    /** Generation bound to each configured session; capture callbacks must not sample the global epoch. */
    private val configuredSessionGenerations =
        java.util.IdentityHashMap<CameraCaptureSession, Long>()
    private data class CaptureResultSessionBinding(
        val session: CameraCaptureSession,
        val generation: Long
    )
    /** Lock-free hot-path binding; lifecycle maps remain separately guarded for teardown bookkeeping. */
    private val captureResultSessionBinding =
        AtomicReference<CaptureResultSessionBinding?>(null)
    private val cameraReleaseAcknowledgements = CameraReleaseAcknowledgementGate()
    // B7: configuredSessionSurfaces and deferredReaderCloseBatches are mutated on the camera
    // background thread (session onConfigured/onClosed callbacks) but are also cleared by
    // releaseCameraResources() on the caller (ViewModel/Default) thread BEFORE stopBackground()
    // quiesces the camera thread. Guard every structural access with this lock so the
    // non-thread-safe IdentityHashMap / ArrayList cannot be read+written concurrently and tear.
    private val sessionCollectionsLock = Any()

    @Volatile private var pendingSessionCreateGeneration = 0L
    @Volatile private var pendingSessionCreateStartElapsedMs = 0L
    val sessionRefreshPending = AtomicBoolean(false)
    @Volatile private var pendingSessionRefreshStartElapsedMs = 0L
    @Volatile private var configuredSessionFingerprint: CameraSessionConfigurationFingerprint? = null
    @Volatile private var inFlightSessionFingerprint: CameraSessionConfigurationFingerprint? = null
    @Volatile private var latestDesiredSessionFingerprint: CameraSessionConfigurationFingerprint? = null
    @Volatile private var inFlightSessionFingerprintGeneration = 0L
    /**
     * Incremented whenever an externally owned session Surface changes. It closes the window where a
     * fingerprint can describe a Surface that the UI retires before CameraDevice receives it.
     */
    private val sessionSurfaceOwnershipEpoch = AtomicLong(0L)
    private data class PendingSessionRefreshRequest(
        var rebuildReaders: Boolean,
        var forceRebuild: Boolean,
        var allowIdenticalRecoveryRebuild: Boolean,
        val reasons: MutableSet<String> = linkedSetOf(),
        val onConfiguredCallbacks: MutableList<() -> Unit> = mutableListOf(),
        val onPreviousSessionClosedCallbacks: MutableList<() -> Unit> = mutableListOf()
    )
    private data class CameraSessionSurfaceSnapshot(
        val ownershipEpoch: Long,
        val streamGraph: CinemaStreamGraphPlan,
        val surfaces: List<Surface>
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

    private fun advanceSessionGeneration(reason: String): Long {
        val generation = synchronized(cameraSubmissionLock) {
            val advanced = sessionGeneration.incrementAndGet()
            npv10ProducerStartupLeaseGate.invalidate(
                "Camera2 session generation advanced during NPV10 startup: $reason"
            )
            npv10AutonomousControlRefreshGate.invalidate()
            advanced
        }
        activeP010StopBoundary.get()?.state?.fail(
            "Camera2 session generation advanced during P010 stop boundary: $reason"
        )
        activeRepeatingSequence.set(null)
        activeRawResumeBoundary.get()?.let {
            failRawResumeBoundary(it, restartRepeating = false)
        }
        activeRepeatingSequence.set(null)
        softwareAeRequestRevisions.clear()
        invalidateSoftwareAeMeterContext()
        runCatching { onSessionGenerationAdvanced?.invoke(generation) }
            .onFailure { Log.w(TAG, "Session-generation listener failed reason=$reason", it) }
        return generation
    }

    private fun recordSoftwareAeControlRevision(
        request: CaptureRequest,
        controlRevision: Long
    ): CaptureRequest = softwareAeRequestRevisions.record(request, controlRevision)

    /** Configure and freeze one request under the same control-state snapshot. */
    private fun buildSoftwareAeTrackedRequest(
        builder: CaptureRequest.Builder,
        configure: () -> Unit = {}
    ): CaptureRequest = synchronized(softwareAeControlStateLock) {
        configure()
        recordSoftwareAeControlRevision(
            request = builder.build(),
            controlRevision = softwareAeControlRevision.get()
        )
    }

    /**
     * Build a related one-shot request from an already configured builder. Its metadata represents
     * [controlRevision] even if a newer UI value was committed between the two Camera2 submissions.
     */
    private fun buildSoftwareAeTrackedRequestForRevision(
        builder: CaptureRequest.Builder,
        controlRevision: Long,
        configure: () -> Unit = {}
    ): CaptureRequest {
        configure()
        return recordSoftwareAeControlRevision(builder.build(), controlRevision)
    }

    private fun softwareAeControlRevisionFor(request: CaptureRequest): Long =
        softwareAeRequestRevisions.revisionFor(request)

    private fun notifySessionGenerationConfigured(generation: Long) {
        runCatching { onSessionGenerationConfigured?.invoke(generation) }
            .onFailure { Log.w(TAG, "Session-configured listener failed generation=$generation", it) }
    }

    private fun clearCaptureResultSessionBinding(session: CameraCaptureSession?) {
        if (session == null) return
        while (true) {
            val current = captureResultSessionBinding.get() ?: return
            if (current.session !== session) return
            if (captureResultSessionBinding.compareAndSet(current, null)) return
        }
    }

    @Volatile override var previewBuilder: CaptureRequest.Builder? = null
    private var previewBuilderHasTargets: Boolean = false
    private var previewBuilderTargetSnapshot: List<Surface> = emptyList()

    private var pendingCameraOpenStartElapsedMs = 0L
    private val cameraCallbackLock = Any()
    private var cameraCallbacksEnabled = false
    @Volatile private var bgThread: HandlerThread? = null
    @Volatile override var backgroundHandler: Handler? = null
    @Volatile private var captureResultThread: HandlerThread? = null
    @Volatile private var captureResultHandler: Handler? = null
    private var appliedCaptureResultPriorityThreadId = -1
    private var appliedCaptureResultPriorityNice = Int.MAX_VALUE
    @Volatile private var jpegIngressThread: HandlerThread? = null
    @Volatile private var jpegIngressHandler: Handler? = null
    @Volatile private var rawIngressThread: HandlerThread? = null
    @Volatile private var rawIngressHandler: Handler? = null
    @Volatile private var analysisIngressThread: HandlerThread? = null
    @Volatile private var analysisIngressHandler: Handler? = null
    /**
     * Recording P010 import has a dedicated urgent-display lane. Full-frame analysis and deferred
     * diagnostics may take longer than one sensor interval and must never queue ahead of an encoder
     * input image.
     */
    @Volatile private var hardwareImportIngressThread: HandlerThread? = null
    @Volatile private var hardwareImportIngressHandler: Handler? = null
    @Volatile private var captureTelemetryThread: HandlerThread? = null
    @Volatile private var captureTelemetryHandler: Handler? = null
    private var appliedAnalysisIngressPriorityThreadId = -1
    private var appliedAnalysisIngressPriorityNice = Int.MAX_VALUE
    @Volatile private var motionTelemetryThread: HandlerThread? = null
    @Volatile private var motionTelemetryHandler: Handler? = null
    private val openLock = Semaphore(1)
    /** One lifecycle owner for every open/retry/close; recreating it per call lost stale-retry state. */
    private val cameraDeviceOrchestrator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CameraDeviceOrchestrator(this, openLock)
    }
    private val cameraAvailabilityRecoveryArmed = AtomicBoolean(false)
    private val cameraAvailabilityCallbackRegistered = AtomicBoolean(false)
    private val cameraAvailabilityRecoveryRunnable = Runnable {
        if (!cameraAvailabilityRecoveryArmed.compareAndSet(true, false)) return@Runnable

        val state = stateFlow.value
        if (state == CameraState.Closing || state == CameraState.Closed || cameraDevice != null) {
            return@Runnable
        }
        val onReady = lastOpenReadyCallback
        val onError = lastOpenErrorCallback
        val surface = previewSurface
        if (onReady == null || onError == null || surface == null || !surface.isValid) {
            Log.d(TAG, "Camera availability recovery skipped: foreground owner or surface is gone")
            return@Runnable
        }

        // A vendor camera-service restart can invalidate the old characteristics snapshot even when the
        // logical ID is unchanged. Re-enumerate once after availability settles, then use the normal
        // full-quality open/session path. There is no polling and no quality fallback.
        currentCameraId = ""
        selectCamera()
        if (currentCameraId.isBlank()) {
            cameraAvailabilityRecoveryArmed.set(true)
            Log.w(TAG, "Camera availability callback arrived before camera enumeration settled")
            return@Runnable
        }
        reopenAttempts = 0
        Log.i(TAG, "Camera became available after service outage; reopening once")
        openCamera(onReady, onError)
    }
    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (!cameraAvailabilityRecoveryArmed.get()) return
            val requestedId = currentCameraId
            if (requestedId.isNotBlank() && requestedId != cameraId) return
            val handler = backgroundHandler ?: return
            handler.removeCallbacks(cameraAvailabilityRecoveryRunnable)
            handler.postDelayed(
                cameraAvailabilityRecoveryRunnable,
                CAMERA_AVAILABILITY_SETTLE_MS
            )
        }
    }
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
        val handler = backgroundHandler
        if (handler != null && Looper.myLooper() === handler.looper) {
            // Camera2 state callbacks already arrive on this Handler. Bouncing through the shared
            // executor and back delays startPreview() during every replacement-session handoff.
            action()
            return
        }
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
    private fun runPrioritizedCameraControl(
        token: StillCaptureRequestToken,
        action: () -> Unit
    ) {
        val handler = backgroundHandler
        if (handler == null) {
            failStillCaptureRequest(token, "Camera thread is not available for photo capture")
            return
        }
        val guardedAction: () -> Unit = {
            if (ownsStillOperation(token)) {
                action()
            } else {
                Log.d(
                    TAG,
                    "Dropped stale still work captureId=${token.captureId} phase=${token.phase} " +
                        "epoch=${token.operationEpoch}"
                )
            }
            Unit
        }
        if (Looper.myLooper() === handler.looper) {
            guardedAction()
            return
        }
        if (!handler.postAtFrontOfQueue(guardedAction)) {
            failStillCaptureRequest(token, "Camera thread stopped before photo capture could start")
        }
    }

    private fun nextStillOperationEpoch(): Long =
        stillCaptureOperationEpoch.updateAndGet { previous ->
            if (previous == Long.MAX_VALUE) 1L else previous + 1L
        }

    private fun sameStillOperation(
        left: StillCaptureRequestToken?,
        right: StillCaptureRequestToken
    ): Boolean = left != null &&
        left.captureId == right.captureId &&
        left.phase == right.phase &&
        left.frameCount == right.frameCount &&
        left.operationEpoch > 0L &&
        left.operationEpoch == right.operationEpoch

    private fun ownsStillOperation(token: StillCaptureRequestToken): Boolean =
        sameStillOperation(activeStillCaptureToken, token)

    /**
     * Posts interactive camera controls ahead of queued frame-maintenance work. This removes the
     * executor-to-handler hop from tap/eye AF without changing Camera2 ordering on its single owner
     * thread.
     */
    private fun runUrgentCameraControl(label: String, action: () -> Unit) {
        val handler = backgroundHandler
        if (handler == null) {
            Log.d(TAG, "$label dropped: camera thread is unavailable")
            return
        }
        if (Looper.myLooper() === handler.looper) {
            action()
        } else if (!handler.postAtFrontOfQueue(action)) {
            Log.d(TAG, "$label dropped: camera thread stopped")
        }
    }

    @Volatile override var currentCameraId: String = ""
    override var facing: Int = CameraCharacteristics.LENS_FACING_BACK
    override var characteristics: CameraCharacteristics? = null
    override var activeArray: Rect? = null
    override var currentFocalLength: Float = 0f
    override var hardwareLevel = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
    override var highEndHardwareSupported = false
    private var modeSwitchStandbyOutputsRejectedCameraId: String? = null
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
    private val accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscopeTelemetryRegistered = AtomicBoolean(false)

    // ── s1-f04 / CP60: timestamp-reconciled gyro telemetry hand-off (DORMANT, DEVICE-GATED) ──────────
    // DEVICE-GATED dormant master switch: no gyro-EIS (s3-f06) / MFSR (s1-f06) consumer reads the
    // hand-off below until this is enabled after the on-device gyro↔SENSOR_TIMESTAMP clock + OIS
    // calibration. Default false ⇒ the hand-off runs for nobody and touches no pixel (no-downgrade).
    @Volatile var gyroEisTelemetryConsumerEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            reconcileMotionTelemetryRegistration()
        }
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
    @Volatile override var supportsManualSensor = false
    override var supportsOIS = false
    override var supportsVideoStabilization = false
    override var supportsPreviewStabilization = false
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
    private var supportsManualColorCorrection = false
    private var supportsAutoWhiteBalanceLock = false
    // Sensor's DNG dual-illuminant color calibration, cached from CameraCharacteristics. Non-null
    // only when the sensor publishes usable calibration; drives the device-true manual-WB path.
    // A pre-CCT manual request is not emitted unless this calibration yields one complete, finite
    // gains+CCM solution for the requested white point.
    private var deviceColorCalibration: DeviceColorCalibration? = null
    // Physical camera id of the most recent capture session, so a same-sensor session rebuild (mode
    // toggle) can carry the temporal white point forward (P1.8) while a lens switch drops it.
    private var lastSessionCameraId: String? = null
    private var cachedAwbModes: IntArray = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)
    private var cachedAeModes: IntArray = intArrayOf(CaptureRequest.CONTROL_AE_MODE_ON)
    private var cachedColorCorrectionModes: IntArray = intArrayOf(
        CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY,
        CaptureRequest.COLOR_CORRECTION_MODE_FAST
    )
    @Volatile private var cachedAePriorityModes: IntArray = intArrayOf(AE_PRIORITY_MODE_OFF)
    private data class SoftwareAeMeterContext(
        val sessionGeneration: Long,
        val mode: UnifiedVideoControlMode,
        val domain: SoftwareAeSignalDomain,
        val controlRevision: Long
    )

    private val softwareAeMeterLock = Any()
    private val softwareAeControlStateLock = Any()
    private val softwareAeControlRevision = AtomicLong(0L)
    /**
     * Exact immutable-request lineage for sensor-linear metering. A weak-identity ledger prevents
     * retired repeating requests from being retained or conflated by structural equality while its
     * monitor covers Camera2 callbacks and UI/control threads. Never infer this value from current
     * UI state after a frame arrives.
     */
    private val softwareAeRequestRevisions = ExactRequestRevisionLedger<CaptureRequest>()
    private val softwareAePriorityController = SoftwareAePriorityController()
    @Volatile private var latestSoftwareAeSceneSample: SoftwareAeSceneSample? = null
    @Volatile private var softwareAeMeterContext: SoftwareAeMeterContext? = null
    @Volatile private var softwareAeMeterNotBeforeElapsedMs = 0L
    @Volatile private var lastSoftwareAePriorityLogSignature: String? = null
    private val softwareAePriorityStaleReleaseRunnable = Runnable {
        releaseStaleSoftwareAePriorityAuthority()
    }
    private var lastLoggedHdrColorRequestSignature: String? = null
    private var lastLoggedVideoColorStabilitySignature: String? = null
    private var lastLoggedHdrMeteringFallbackSignature: String? = null
    private var lastLoggedDeviceTrueWbSignature: String? = null
    private var lastLoggedManualWbFallbackSignature: String? = null
    private var lastLoggedPhysicalTelemetrySignature: String? = null
    private var lastLoggedWhiteBalanceResultSignature: String? = null
    @Volatile private var lastNiv99AnalysisRouteSignature: String? = null
    private var lastWhiteBalanceResultLogElapsedMs = 0L
    // Temporal white-point governor: consumes the per-frame color-drift budget that
    // VideoColorStabilityPolicy publishes, bounding measured AWB drift in CIE u'v'.
    private val colorDriftGovernor = ColorDriftGovernor()
    // Latest per-frame u'v' drift budget, mirrored from the active VideoColorStabilityPlan so
    // the capture-result callback can enforce it without re-resolving the whole plan. Seeded with
    // the adaptive (looser) default so the very first results before a plan is resolved are lenient.
    @Volatile private var activeColorDriftBudgetUv = 0.0060f
    // True while a contiguous run of budget-exceeding (clamped) frames is in progress, so the
    // governor logs once per hunting episode rather than once per frame.
    @Volatile private var colorDriftClampingActive = false
    private data class MeasuredColorCorrectionSnapshot(
        val provenance: WhiteBalanceMeasurementProvenance,
        val gains: RggbChannelVector?,
        val transform: ColorSpaceTransform?,
        val awbConfidence: Float,
        val stabilizationAccepted: Boolean = false
    )

    // One immutable result-scoped snapshot replaces the old independently sticky gains/CCM fields.
    // It is overwritten for every accepted result, including results where either key is absent.
    @Volatile private var latestMeasuredColorSnapshot: MeasuredColorCorrectionSnapshot? = null

    private fun currentMeasuredColorSnapshot(): MeasuredColorCorrectionSnapshot? {
        val snapshot = latestMeasuredColorSnapshot ?: return null
        val activeCameraId = cameraDevice?.id ?: currentCameraId
        if (activeCameraId.isBlank()) return null
        return snapshot.takeIf {
            it.provenance.isCurrent(activeCameraId, sessionGeneration.get())
        }
    }
    // NDR/NIVUKX red-cast probe: throttle the observe-only measured-AWB-gains diagnostic (no pixel change).
    private var lastLoggedNdrColorProbeSignature: String? = null
    private data class DeferredCaptureResultDiagnostics(
        val result: CaptureResult,
        val captureSessionGeneration: Long,
        val liveResult: LiveResult,
        val colorCorrectionGains: RggbChannelVector?,
        val awbConfidence: Float,
        val hdrMode: HdrDynamicRangeMode,
        val sensorLinearNdrEnabled: Boolean,
        val recording: Boolean,
        val strictHighEnd: Boolean,
        val requestedIspBypassModes: IspBypassRequestedModes?
    )
    private val pendingCaptureResultDiagnostics =
        AtomicReference<DeferredCaptureResultDiagnostics?>(null)
    private val captureResultDiagnosticsScheduled = AtomicBoolean(false)
    private val lastCaptureCallbackTimingLogNs = AtomicLong(0L)
    private val captureResultDiagnosticsRunnable = Runnable {
        val work = pendingCaptureResultDiagnostics.getAndSet(null)
        try {
            if (work != null && work.captureSessionGeneration == sessionGeneration.get()) {
                processDeferredCaptureResultDiagnostics(work)
            }
        } finally {
            captureResultDiagnosticsScheduled.set(false)
            if (pendingCaptureResultDiagnostics.get() != null) {
                scheduleCaptureResultDiagnostics()
            }
        }
    }
    // Edge-trigger for the active-override engage/disengage log.
    private var colorDriftOverrideActive = false
    // Damps the AE/AWB convergence signal so a brief reframe / AF-scan blip does not collapse the
    // SDR auto-lock (which caused the native exposure-overshoot washout + white-point skin shift).
    private val meteringHysteresis = MeteringConvergenceHysteresis()
    // #4: coalesces a burst of non-immediate updatePreview() rebuilds into one queued rebuild
    // (CoalesceRequestRebuildFlag). Cleared per session in createSession so it can never get stuck.
    private val pendingPreviewRebuild = AtomicBoolean(false)
    // A mode boundary can keep the configured output graph while changing the repeating targets
    // (RAW_SENSOR <-> standby P010). Coalesce those full request rebuilds separately from parameter-only
    // updates so every burst installs exactly one builder created from the latest settled mode state.
    private val pendingRepeatingRequestRefresh = AtomicBoolean(false)
    /** Serializes stop-fence installation with every Camera2 request submission. */
    private val cameraSubmissionLock = Any()
    /** Exact currently-installed repeating sequence, including P010 target identity when present. */
    private val activeRepeatingSequence =
        AtomicReference<ActiveCameraRepeatingSequence?>(null)
    private val repeatingPublicationGeneration = AtomicLong(0L)
    /** Defers control-only request replacement until NPV10 writer/sink ownership is durably proven. */
    private val npv10ProducerStartupLeaseGate = Npv10ProducerStartupLeaseGate()
    /** Coalesces engine-owned AE/AWB/AF/detail feedback without weakening strict user-control gating. */
    private val npv10AutonomousControlRefreshGate = Npv10AutonomousControlRefreshGate()
    /** Installed on the caller thread before dispatching the NPV10 stop transaction. */
    private val activeP010StopBoundary =
        AtomicReference<P010StopBoundaryRequestToken?>(null)
    private val p010ProducerTailTracker =
        AtomicReference<P010ProducerTailTracker<CameraCaptureSession>?>(null)
    private val p010StopDeferredRequestRefresh =
        RawResumeDeferredRefreshGate<P010StopBoundaryRequestToken>(
            activeOwner = { activeP010StopBoundary.get() },
            restart = ::restartRepeatingRequestForRecordTargetChange
        )
    /** Present only while a RAW resume marker has committed writer admission and awaits setRepeating. */
    private val pendingRawResumeRestart =
        AtomicReference<RawCaptureResumeBoundaryRequestToken?>(null)
    /** Gates competing Camera2 submissions from stopRepeating through marker terminal completion. */
    private val activeRawResumeBoundary =
        AtomicReference<RawCaptureResumeBoundaryRequestToken?>(null)
    private val rawResumeDeferredRequestRefresh =
        RawResumeDeferredRefreshGate<RawCaptureResumeBoundaryRequestToken>(
            activeOwner = { activeRawResumeBoundary.get() },
            restart = ::restartRepeatingRequestForRecordTargetChange
        )
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
    /**
     * Opt-in ultra-high-resolution still mode. Keeping this false uses the largest JPEG surface already
     * attached to the live session, preserving preview continuity and low shutter latency. When true,
     * capable Android 12+ devices may switch into SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION for the shot.
     */
    @Volatile
    var maximumResolutionStillEnabled: Boolean = false
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
    @Volatile private var lastRequestedIspBypassModes: IspBypassRequestedModes? = null
    @Volatile
    private var lastIspBypassVerification: IspBypassVerificationReport? = null
    @Volatile
    private var loggedIspBypassVerification = false
    // Frames observed since the active ISP-bypass request was (re)built. The result stream lags the
    // request by a few frames, so verification waits for the HAL to settle; combined with the
    // once-per-request log guard this bounds read-back cost to a single verification per request.
    @Volatile
    private var ispBypassReadbackFramesObserved = 0
    @Volatile
    private var appliedProcessedVideoQualityEvidence: Map<String, String> = emptyMap()

    private var cameraCapabilityInventory: CameraCapabilityInventory? = null
    private var cameraStreamPlans: Map<CinemaVideoMode, CameraStreamPlan> = emptyMap()
    private var cameraBypassCapabilityReport: CameraBypassCapabilityReport? = null
    private var cameraVendorReadoutScanReport: CameraVendorReadoutScanReport = CameraVendorReadoutScanReport.EMPTY
    
    // Phase 1c: Bit-depth precision policy (10-bit/12-bit validation)
    private var bitDepthPrecisionPolicy: BitDepthPrecisionPolicy? = null
    @Volatile private var hdrDynamicRangeMode = CameraInitialDynamicRangeModePolicy.defaultMode()
    private var hdrSupport = HdrDynamicRangePolicy.Support.NONE
    private var lastAppliedPreviewHdrProfile: Long? = null
    private var lastAppliedEncoderHdrProfile: Long? = null

    private data class AppliedSurfaceDynamicRangeProfile(
        val surface: Surface,
        val profile: Long?
    )

    private data class AppliedDynamicRangeSessionSnapshot(
        val session: CameraCaptureSession,
        val generation: Long,
        val surfaceProfiles: List<AppliedSurfaceDynamicRangeProfile>
    ) {
        fun bindingFor(surface: Surface?): AppliedSurfaceDynamicRangeProfile? =
            surface?.let { target -> surfaceProfiles.firstOrNull { it.surface === target } }
    }

    @Volatile
    private var appliedDynamicRangeSessionSnapshot: AppliedDynamicRangeSessionSnapshot? = null

    private fun clearAppliedDynamicRangeSnapshotFor(session: CameraCaptureSession?) {
        if (session == null || appliedDynamicRangeSessionSnapshot?.session !== session) return
        appliedDynamicRangeSessionSnapshot = null
        lastAppliedPreviewHdrProfile = null
        lastAppliedEncoderHdrProfile = null
    }

    // s1-f05: honest record-path frame-delivery accounting (observe-only). Fed from the shared
    // capture callback below; reconfigure transitions are bracketed in refreshOutputsAndSession /
    // the session StateCallback so a drop during a rebuild is bounded-and-reported, never silent.
    private val recordFrameDeliveryLedger = CameraRecordFrameDeliveryLedger()
    private val previewHealthMonitor = PreviewHealthMonitor(tag = TAG)

    @set:JvmName("applyPreviewSurfaceField")
    @get:JvmName("readPreviewSurfaceField")
    @Volatile
    var previewSurface: Surface? = null
    @Volatile private var previewWarmupSurface: Surface? = null
    @Volatile private var persistentSdrPreviewSurface: Surface? = null
    @Volatile private var persistentHdrPreviewSurface: Surface? = null
    @Volatile private var persistentPreviewProfilesCanShareRequest = false
    @Volatile private var persistentPreviewOutputsRejectedCameraId: String? = null
    @set:JvmName("applyRecordSurfaceField")
    @Volatile
    var recordSurface: Surface? = null
    @Volatile override var jpegReader: ImageReader? = null
    @Volatile override var rawReader: ImageReader? = null
    @Volatile override var yuvReader: ImageReader? = null
    @Volatile override var hardwareImportReader: ImageReader? = null

    private fun replacePreviewSessionSurface(surface: Surface?): Boolean =
        synchronized(cameraSubmissionLock) {
            if (previewSurface === surface) return@synchronized false
            previewSurface = surface
            sessionSurfaceOwnershipEpoch.incrementAndGet()
            true
        }

    private fun replaceRecordSessionSurface(surface: Surface?): Boolean =
        synchronized(cameraSubmissionLock) {
            if (recordSurface === surface) return@synchronized false
            recordSurface = surface
            sessionSurfaceOwnershipEpoch.incrementAndGet()
            true
        }

    private fun replacePersistentPreviewSessionSurfaces(
        sdrSurface: Surface?,
        hdrSurface: Surface?
    ): Boolean = synchronized(cameraSubmissionLock) {
        if (persistentSdrPreviewSurface === sdrSurface && persistentHdrPreviewSurface === hdrSurface) {
            return@synchronized false
        }
        persistentSdrPreviewSurface = sdrSurface
        persistentHdrPreviewSurface = hdrSurface
        sessionSurfaceOwnershipEpoch.incrementAndGet()
        true
    }

    private val readerCallbackIngress = SynchronousImageIngressGate {}
    private val readerBindings = ReaderBindingRegistry<ImageReader>()
    private val readerCallbackAdmission = ReaderCallbackAdmission(
        bindings = readerBindings,
        ingress = readerCallbackIngress
    )

    private data class ReaderSet(
        val jpeg: ImageReader?,
        val raw: ImageReader?,
        val yuv: ImageReader?,
        val hardwareImport: ImageReader?
    ) {
        val hasReaders: Boolean
            get() = jpeg != null || raw != null || yuv != null || hardwareImport != null
    }

    /**
     * Readers are owned by the exact CameraCaptureSession that still references their surfaces.
     * Identity semantics are intentional: a callback from an older session must never close readers
     * deferred for a newer session.
     */
    private val deferredReaderCloseBatches =
        java.util.IdentityHashMap<CameraCaptureSession, MutableList<ReaderSet>>()
    /**
     * Fail-closed ownership for readers whose pipeline barrier did not prove idle. These exact
     * ReaderSet instances remain strongly reachable and are retried by the next guarded close.
     */
    private val quarantinedReaderCloseBatches = mutableListOf<ReaderSet>()

    override var stillCaptureJpegEnabled = false
    override var stillCaptureRawEnabled = false

    @Volatile private var manIso = -1
    override var manualIso: Int
        get() = manIso
        set(value) {
            synchronized(softwareAeControlStateLock) {
                if (manIso != value) {
                    manIso = value
                    softwareAeControlRevision.incrementAndGet()
                }
            }
        }
    @Volatile private var manExpNs = -1L
    override var manualExposureNs: Long
        get() = manExpNs
        set(value) {
            synchronized(softwareAeControlStateLock) {
                if (manExpNs != value) {
                    manExpNs = value
                    softwareAeControlRevision.incrementAndGet()
                }
            }
        }
    private var manFocusDist = -1f
    override var manualFocusDist: Float get() = manFocusDist; set(v) { manFocusDist = v }
    // s5-f07 / CP-F8: cinema focus-pull control. Dormant unless CinematicFocusPullFlag is ON; with
    // the flag OFF the manual-lens path below is byte-for-byte today. s5-f08 wires its
    // AIFocusDistanceController pull stepper into this same control behind the same flag
    // (see beginCinematicStylePull, which rebuilds it with AICinematicPullStepper for a named style).
    /**
     * The focus-pull control is also reachable from the debug BroadcastReceiver (main thread), while
     * request construction and capture-result feedback run on CameraBackground. Keep all mutable pull
     * state behind one lock so a trigger/configuration command can never race a lens-request step.
     */
    private val cinematicFocusPullLock = Any()
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
    @Volatile private var manWbK = -1
    @Volatile private var manWbTint = 0
    override var manualWbKelvin: Int get() = manWbK; set(v) { manWbK = v }
    override var flashMode = FlashMode.OFF
    override var evIndex = 0
    // Neutral (EV=0) ISO baseline for LOG/RAW cinematic auto-exposure. With AE OFF the reported
    // SENSOR_SENSITIVITY reads back what we last set, so EV must be computed from a frozen neutral —
    // captured once at activation — instead of that feedback value, or successive EV changes compound
    // and EV=0 never restores the metered look. Reset to -1 whenever cinematic auto-exposure is inactive.
    private var cinemaEvNeutralBaselineIso = -1
    @set:JvmName("applyThermalResourceCeilingField")
    @Volatile
    var thermalResourceCeiling = 1.0f
    @set:JvmName("applyStabilizationModeField")
    var stabilizationMode = StabilizationMode.OIS
    override var zoomRatio = 1f
    override var targetOutputAspectRatio: Float? = null
        set(value) {
            val targetUnchanged =
                field == value || (field != null && value != null && abs(field!! - value) < 0.0001f)
            // Closing Camera2 deliberately clears frameGeometryFlow so consumers cannot use stale
            // sensor geometry. A same-camera reopen retains characteristics and usually reapplies the
            // same aspect target, though, so an unconditional value-only early return would leave the
            // cleared flow null until process restart. Treat an existing published geometry as part of
            // this setter's idempotence contract: identical target + missing geometry must republish.
            if (targetUnchanged && frameGeometryFlow.value != null) return
            field = value
            refreshFrameGeometry()
        }
    @set:JvmName("applyDeviceRotationDegreesField")
    var deviceRotationDegrees = 0
        set(value) {
            field = ((value % 360) + 360) % 360
            refreshFrameGeometry()
        }
    @Volatile
    private var aiFocusControlSignal = AiFocusControlSignal.NONE
    override var aiDrivenFocusDist: Float
        get() = aiFocusControlSignal.distanceDiopters
        set(value) {
            aiFocusControlSignal = if (value.isFinite() && value >= 0f) {
                AiFocusControlSignal.create(
                    distanceDiopters = value,
                    confidence = 0f,
                    sessionGeneration = sessionGeneration.get()
                )
            } else {
                AiFocusControlSignal.NONE
            }
        }
    @set:JvmName("applyNoiseReductionField")
    var noiseReductionEnabled = true
    @Volatile private var selectedVideoDetailProfile: VideoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL
    @Volatile
    private var processedVideoMotionRisk = 0f
    @Volatile
    private var processedVideoNoiseRisk = 0f
    @Volatile private var extremeDetailFallbackExposureRequestApplied = false
    @Volatile
    private var lastExtremeDetailFallbackExposureLogSignature: String? = null
    // NDR10+/NIVUKX closed-loop anti-motion-blur shutter cap state (see NdrAntiMotionBlurShutterPolicy).
    // `engaged` is the applied state; `capNs`/`isoFloor` are cached from the last request build so the
    // capture-result feedback loop can cheaply decide whether the engage state should flip.
    @Volatile private var hdrAntiBlurEngaged = false
    @Volatile private var hdrAntiBlurCapNs = 0L
    @Volatile private var hdrAntiBlurIsoFloor = 0
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
    // Sensor-linear NDR has its own RAW-domain bidirectional governor. It has no fixed dark baseline:
    // measured headroom may lift a dark scene, while real RAW-channel clipping attacks negative EV.
    @Volatile
    private var sensorLinearNdrExposureStops = 0f
    @Volatile
    private var sensorLinearNdrExposureAppliedStops = 0f
    @Volatile
    private var lastSensorLinearNdrExposureMetricsElapsedMs = 0L
    @Volatile
    private var lastSensorLinearNdrExposureRequestElapsedMs = 0L
    @Volatile
    private var lastSensorLinearNdrExposureControlElapsedMs = 0L
    // True-SDR uses its own neutral-baseline governor. Unlike NDR, this has no fixed negative EV
    // floor: ordinary scenes stay at the user's requested EV and only measured clipping adds bias.
    @Volatile
    private var sdrAdaptiveHighlightExtraStops = 0f
    @Volatile
    private var sdrAdaptiveHighlightAppliedStops = 0f
    // Timestamp of the last real analysis frame. If the optional meter surface is rejected or stalls,
    // request assembly briefly holds, then releases, only the last evidence-backed adaptive correction.
    @Volatile
    private var lastHdrExposureMetricsElapsedMs = 0L
    @Volatile
    private var lastRequestedNdrExposureCompensationIndex: Int? = null
    @Volatile
    private var lastRequestedSdrExposureCompensationIndex: Int? = null
    @Volatile
    private var lastHdrExposureReadbackElapsedMs = 0L
    // Throttled evidence that the NDR YUV analysis loop is receiving both luminance-highlight and
    // reconstructed-red-highlight data.
    @Volatile
    private var lastHdrExposureEvidenceElapsedMs = 0L
    // True only for the project's NDR/NIVUKX modes. Generic HLG/HDR10 transports keep their existing
    // Camera2 exposure behavior and never inherit this proprietary governor or its analysis stream.
    @Volatile
    private var ndrExposureGovernorEnabled = false
    private val hdrExposureStaleReleaseRunnable = Runnable { releaseStaleHdrExposureCorrection() }
    private val sdrExposureStaleReleaseRunnable = Runnable { releaseStaleSdrExposureCorrection() }
    @set:JvmName("applyFaceDetectionField")
    var faceDetectionEnabled = false
    override var aeAwbLocked = false
    @Volatile
    private var tapFocusMeteringUnlockUntilElapsedMs = 0L
    @Volatile private var tapAfScanActive = false
    @Volatile private var tapAfTriggerFrameNumber = -1L
    @Volatile
    private var tapAfRequestStartedElapsedNs = 0L
    private val tapAutoFocusRequestTag = Any()
    private val tapAfScanTimeoutRunnable = Runnable {
        completeTapAfScan(reason = "timeout", restoreContinuousAf = true)
    }
    @set:JvmName("applyJpegQualityField")
    var jpegQuality = 100
    @set:JvmName("applyRawVideoCaptureField")
    var rawVideoCaptureEnabled = false
    @Volatile private var sensorLinearNdrCaptureEnabled = false
    @set:JvmName("applyNpv10TenBitCaptureField")
    var npv10TenBitCaptureEnabled = false
    private var analysisTargetSize: Size? = null
    private var npv10CaptureTargetSize: Size? = null
    private var modeSwitchStandbyAnalysisTargetSize: Size? = null
    private var modeSwitchStandbyAnalysisTenBit = false
    private var recordingReadoutOverrideSize: Size? = null
    private var recordingEncoderTargetSize: Size? = null
    // MAXIMUM_SENSOR RAW tier: when true (and the device exposes it), the RAW record request engages
    // SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION for the un-binned native readout. Cleared on fallback / stop.
    private var recordingSensorPixelModeMaximumResolution = false
    // RAW mode keeps its final recording reader configured, targeted, and drained with the exact
    // recording request contract. Start/stop then opens only the writer gate: no HAL warm-up or session
    // replacement is required at shutter time.
    private var rawVideoPrearmEnabled = false
    @Volatile
    private var maximumResolutionStillCaptureActive = false
    @Volatile
    private var p010CadenceStillCaptureActive = false
    @Volatile
    private var stillCaptureSessionRestorationPending = false
    @Volatile
    private var activeStillCaptureToken: StillCaptureRequestToken? = null
    @Volatile
    private var lastStillCaptureQualityReadback: StillCaptureQualityReadbackReport? = null
    private var maximumResolutionStillCaptureToken: StillCaptureRequestToken? = null
    private var pausedRepeatingStillCaptureToken: StillCaptureRequestToken? = null
    private val stillCaptureOperationEpoch = AtomicLong(0L)
    // RAW cinema-preview debayer feed: keep the RAW_SENSOR reader in-session + repeating-request target
    // during VIDEO+RAW preview (not recording) so the GPU cinema-debayer produces a genuine sensor-RAW
    // monitor image. Independent of rawVideoCaptureEnabled (never implies a RAW recording session).
    private var rawPreviewCaptureEnabled = false
    private var internalRecordingHardwareImportEnabled = false
    private var hardwareImportP010SourceEnabled = false
    @Volatile private var previewContinuityRecordSurfaceEnabled = false
    private var unconfiguredRecordSurfaceRecoveryPending = false
    private var readoutSidecarMode: CameraReadoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM
    override var previewLogLookEnabled = false
    override var previewHdrLookEnabled = false
    override var previewRawLookEnabled = false
    @Volatile private var videoPreviewActive = true
    // Gated CPU-analysis (YUV) stream request for proprietary NDR exposure/highlight analysis. The
    // default video-preview graph omits this stream to preserve recording-safe WYSIWYG parity.
    @Volatile private var previewAnalysisRequested = false
    // Thermal recording isolation leaves the reader configured but removes only its repeating target.
    // This releases ISP bandwidth without a session rebuild and cannot alter the encoder pixel path.
    @Volatile private var recordingThermalAnalysisIsolationEnabled = false
    // ViewModel-owned analysis demand is tracked separately from NDR's mandatory meter so mode
    // transitions cannot briefly detach and immediately reattach the same surface (double rebuild).
    @Volatile private var clientPreviewAnalysisRequested = false
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
    @Volatile private var recordingActive = false
    /**
     * True from processed hardware-import preparation through recording stop. Camera2 must keep the
     * final recording output set for that whole interval; otherwise idle analysis/preview outputs can
     * be re-admitted after the clip clock opens and force a cadence-breaking session rebuild.
     *
     * This flag changes only Camera2 session membership/request intent. It does not start the frame
     * ledger, encoder, audio, MediaStore publication, or recording state, so preparation cannot be
     * reported as captured video.
     */
    @Volatile private var processedRecordingSessionPreparationActive = false
    internal val isVideoRecordingActive: Boolean
        get() = recordingActive
    @set:JvmName("applyCinemaFidelityOverridesField")
    var cinemaFidelityOverridesEnabled = false
    private var professionalVideoControlsActive = false

    override var onYuvFrame: ((ImageReader) -> Unit)? = null
    override var onHardwareFrame: ((ImageReader) -> Unit)? = null
    override var onJpegReady: ((ImageReader) -> Unit)? = null
    override var onRawReady: ((ImageReader) -> Unit)? = null
    override var onCaptureResult: ((TotalCaptureResult) -> Unit)? = null
    override var onRawCaptureResultWithSessionGeneration: ((TotalCaptureResult, Long) -> Unit)? = null
    override var onNiv99CaptureResultWithSessionGeneration: ((TotalCaptureResult, Long) -> Unit)? = null
    override var onCaptureResultWithSessionGeneration: ((TotalCaptureResult, Long) -> Unit)? = null
    /** Real SensorEvent ingress for asynchronous frame metadata; never consumed on the capture callback. */
    var onMotionSensorSample: ((Int, Long, Float, Float, Float) -> Unit)? = null
    override var onSessionGenerationAdvanced: ((Long) -> Unit)? = null
    override var onSessionGenerationConfigured: ((Long) -> Unit)? = null
    override var onStillCaptureFailed: ((StillCaptureFailure) -> Unit)? = null
    override var onCameraPipelineWarning: ((String) -> Unit)? = null
    /**
     * Runs the supplied destructive close action only after sealing pipeline ingress and proving the
     * complete pipeline idle. Returning false must leave the action untouched. The ViewModel binds
     * this for the entire period in which Camera2 callbacks can reach FramePipelineManager.
     */
    @Volatile
    var closeReadersInsideFramePipelineBarrier:
        ((CameraReaderCloseRequest, Long, () -> Unit) -> Boolean)? = null

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
    /**
     * Closes the caller-thread -> CameraBackground admission gap for a full session refresh.
     * Guarded by [refreshBatchLock], together with the mode-switch batch handoff below.
     */
    private val sessionRefreshDispatchTracker = CameraSessionRefreshDispatchTracker()
    private val queuedSessionRefreshDispatches: Int
        get() = sessionRefreshDispatchTracker.pendingCount()
    private var batchSessionRefreshHandoffPending = false
    private var batchedRepeatingRequestRefreshPending = false

    val activeProfileName: String
        get() = HardwareAbstractionLayer.activeProfileName().takeIf { it != "NONE" }
            ?: cameraBypassCapabilityReport?.halProfile?.badgeLabel
            ?: "HAL ${hardwareLevelLabel(hardwareLevel)}"
    val cameraCaptureToVulkanIsolationSignature: String
        get() = "control-thread=CameraBackground|capture-thread=CameraResultIngress|vulkan-thread=native-isolated|jni=no-blocking-frame-copy|metadata=volatile-snapshot|ui=decoupled|haptics=decoupled|storage=decoupled"
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
            if (backgroundHandler != null && bgThread?.isAlive == true &&
                captureResultHandler != null && captureResultThread?.isAlive == true &&
                jpegIngressHandler != null && jpegIngressThread?.isAlive == true &&
                rawIngressHandler != null && rawIngressThread?.isAlive == true &&
                analysisIngressHandler != null && analysisIngressThread?.isAlive == true &&
                hardwareImportIngressHandler != null && hardwareImportIngressThread?.isAlive == true &&
                captureTelemetryHandler != null && captureTelemetryThread?.isAlive == true &&
                motionTelemetryHandler != null && motionTelemetryThread?.isAlive == true
            ) {
                sessionRefreshDispatchTracker.resumeLifecycle()
                cameraCallbacksEnabled = true
                reconcileNiv99MetadataIngressPriority("background-already-running")
                reconcileNiv99ImageIngressPriority("background-already-running")
                return
            }
            cameraCallbacksEnabled = false
            if (bgThread?.isAlive != true) {
                bgThread = HandlerThread(
                    "CameraBackground",
                    Process.THREAD_PRIORITY_URGENT_AUDIO
                ).also { it.start() }
            }
            backgroundHandler = Handler(requireNotNull(bgThread).looper)
            if (captureResultThread?.isAlive != true) {
                captureResultThread = HandlerThread(
                    "CameraResultIngress",
                    Process.THREAD_PRIORITY_DISPLAY
                ).also { it.start() }
            }
            captureResultHandler = Handler(requireNotNull(captureResultThread).looper)
            reconcileNiv99MetadataIngressPriority("background-start")
            if (jpegIngressThread?.isAlive != true) {
                jpegIngressThread = HandlerThread(
                    "StillJpegIngress",
                    Process.THREAD_PRIORITY_DISPLAY
                ).also { it.start() }
            }
            jpegIngressHandler = Handler(requireNotNull(jpegIngressThread).looper)
            if (rawIngressThread?.isAlive != true) {
                rawIngressThread = HandlerThread(
                    "RawImageIngress",
                    Process.THREAD_PRIORITY_DISPLAY
                ).also { it.start() }
            }
            rawIngressHandler = Handler(requireNotNull(rawIngressThread).looper)
            if (analysisIngressThread?.isAlive != true) {
                analysisIngressThread = HandlerThread(
                    "AnalysisImageIngress",
                    Process.THREAD_PRIORITY_MORE_FAVORABLE
                ).also { it.start() }
            }
            analysisIngressHandler = Handler(requireNotNull(analysisIngressThread).looper)
            if (hardwareImportIngressThread?.isAlive != true) {
                hardwareImportIngressThread = HandlerThread(
                    "HardwareImportIngress",
                    Process.THREAD_PRIORITY_URGENT_DISPLAY
                ).also { it.start() }
            }
            hardwareImportIngressHandler = Handler(requireNotNull(hardwareImportIngressThread).looper)
            reconcileNiv99ImageIngressPriority("background-start")
            if (captureTelemetryThread?.isAlive != true) {
                captureTelemetryThread = HandlerThread(
                    "CaptureTelemetry",
                    Process.THREAD_PRIORITY_MORE_FAVORABLE
                ).also { it.start() }
            }
            captureTelemetryHandler = Handler(requireNotNull(captureTelemetryThread).looper)
            if (motionTelemetryThread?.isAlive != true) {
                motionTelemetryThread = HandlerThread(
                    "MotionTelemetry",
                    Process.THREAD_PRIORITY_DEFAULT
                ).also { it.start() }
            }
            motionTelemetryHandler = Handler(requireNotNull(motionTelemetryThread).looper)
            sessionRefreshDispatchTracker.resumeLifecycle()
            cameraCallbacksEnabled = true
        }
        registerCameraAvailabilityCallback()
        reconcileMotionTelemetryRegistration()
    }

    fun stopBackground() {
        pendingCaptureResultDiagnostics.set(null)
        stopPreviewWatchdog()
        clearCameraAvailabilityRecovery()
        unregisterCameraAvailabilityCallback()
        val threads = synchronized(cameraCallbackLock) {
            cameraCallbacksEnabled = false
            stopMotionTelemetryLocked()
            val threads = listOfNotNull(
                bgThread,
                captureResultThread,
                jpegIngressThread,
                rawIngressThread,
                analysisIngressThread,
                hardwareImportIngressThread,
                captureTelemetryThread,
                motionTelemetryThread
            ).distinct()
            bgThread = null
            backgroundHandler = null
            captureResultThread = null
            captureResultHandler = null
            appliedCaptureResultPriorityThreadId = -1
            appliedCaptureResultPriorityNice = Int.MAX_VALUE
            jpegIngressThread = null
            jpegIngressHandler = null
            rawIngressThread = null
            rawIngressHandler = null
            analysisIngressThread = null
            analysisIngressHandler = null
            hardwareImportIngressThread = null
            hardwareImportIngressHandler = null
            captureTelemetryThread = null
            captureTelemetryHandler = null
            appliedAnalysisIngressPriorityThreadId = -1
            appliedAnalysisIngressPriorityNice = Int.MAX_VALUE
            motionTelemetryThread = null
            motionTelemetryHandler = null
            threads
        }
        threads.forEach(HandlerThread::quitSafely)
        for (thread in threads) {
            if (thread === Thread.currentThread()) continue
            try {
                thread.join()
            } catch (e: InterruptedException) {
                Log.e(TAG, "stopBackground interrupted while joining ${thread.name}", e)
                Thread.currentThread().interrupt()
                break
            }
        }
        captureResultDiagnosticsScheduled.set(false)
    }

    /**
     * Keeps exact-frame Camera2 metadata schedulable beside the fixed NIV99/NPV10 compute lanes.
     * This runs only at lifecycle/mode transitions, never per frame. The read-back prevents a
     * denied or OEM-altered priority request from being mistaken for an applied fix.
     */
    private fun reconcileNiv99MetadataIngressPriority(reason: String): Boolean =
        synchronized(cameraCallbackLock) {
            val thread = captureResultThread
            if (thread?.isAlive != true) {
                appliedCaptureResultPriorityThreadId = -1
                appliedCaptureResultPriorityNice = Int.MAX_VALUE
                Log.w(
                    TAG,
                    "NIV99_METADATA_PRIORITY_APPLY applied=false reason=$reason " +
                        "failure=capture-result-thread-unavailable"
                )
                return@synchronized false
            }
            val threadId = thread.threadId
            if (threadId <= 0) {
                Log.e(
                    TAG,
                    "NIV99_METADATA_PRIORITY_APPLY applied=false reason=$reason " +
                        "failure=invalid-thread-id threadId=$threadId"
                )
                return@synchronized false
            }
            val tier = Niv99MetadataIngressSchedulingPolicy.resolve(
                recordingActive = recordingActive,
                npv10TenBitCaptureEnabled = npv10TenBitCaptureEnabled
            )
            val requestedNice = when (tier) {
                Niv99MetadataIngressSchedulingPolicy.Tier.BASELINE ->
                    Process.THREAD_PRIORITY_DISPLAY
                Niv99MetadataIngressSchedulingPolicy.Tier.RECORDING_CRITICAL ->
                    Process.THREAD_PRIORITY_URGENT_DISPLAY
            }
            if (appliedCaptureResultPriorityThreadId == threadId &&
                appliedCaptureResultPriorityNice == requestedNice
            ) {
                return@synchronized true
            }
            runCatching {
                Process.setThreadPriority(threadId, requestedNice)
                Process.getThreadPriority(threadId)
            }.fold(
                onSuccess = { effectiveNice ->
                    val applied = Niv99MetadataIngressSchedulingPolicy.isApplied(
                        tier = tier,
                        requestedNice = requestedNice,
                        effectiveNice = effectiveNice
                    )
                    if (applied) {
                        appliedCaptureResultPriorityThreadId = threadId
                        appliedCaptureResultPriorityNice = requestedNice
                    } else {
                        appliedCaptureResultPriorityThreadId = -1
                        appliedCaptureResultPriorityNice = Int.MAX_VALUE
                    }
                    val message =
                        "NIV99_METADATA_PRIORITY_APPLY applied=$applied reason=$reason " +
                            "tier=$tier threadId=$threadId requestedNice=$requestedNice " +
                            "effectiveNice=$effectiveNice recording=$recordingActive " +
                            "npv10=$npv10TenBitCaptureEnabled"
                    if (applied) Log.i(TAG, message) else Log.e(TAG, message)
                    applied
                },
                onFailure = { failure ->
                    appliedCaptureResultPriorityThreadId = -1
                    appliedCaptureResultPriorityNice = Int.MAX_VALUE
                    Log.e(
                        TAG,
                        "NIV99_METADATA_PRIORITY_APPLY applied=false reason=$reason " +
                            "tier=$tier threadId=$threadId requestedNice=$requestedNice " +
                            "recording=$recordingActive npv10=$npv10TenBitCaptureEnabled",
                        failure
                    )
                    false
                }
            )
        }

    /** Applies/restores P010 ImageReader priority only at lifecycle transitions, never per frame. */
    private fun reconcileNiv99ImageIngressPriority(reason: String): Boolean =
        synchronized(cameraCallbackLock) {
            val thread = hardwareImportIngressThread
            if (thread?.isAlive != true) {
                appliedAnalysisIngressPriorityThreadId = -1
                appliedAnalysisIngressPriorityNice = Int.MAX_VALUE
                Log.w(
                    TAG,
                    "NIV99_IMAGE_PRIORITY_APPLY applied=false reason=$reason " +
                        "failure=hardware-import-ingress-thread-unavailable"
                )
                return@synchronized false
            }
            val threadId = thread.threadId
            if (threadId <= 0) {
                Log.e(
                    TAG,
                    "NIV99_IMAGE_PRIORITY_APPLY applied=false reason=$reason " +
                        "failure=invalid-thread-id threadId=$threadId"
                )
                return@synchronized false
            }
            val tier = Niv99ImageIngressSchedulingPolicy.resolve(
                recordingActive = recordingActive,
                npv10TenBitCaptureEnabled = npv10TenBitCaptureEnabled
            )
            val requestedNice = when (tier) {
                Niv99ImageIngressSchedulingPolicy.Tier.BASELINE ->
                    Process.THREAD_PRIORITY_MORE_FAVORABLE
                Niv99ImageIngressSchedulingPolicy.Tier.RECORDING_CRITICAL ->
                    Process.THREAD_PRIORITY_URGENT_DISPLAY
            }
            if (appliedAnalysisIngressPriorityThreadId == threadId &&
                appliedAnalysisIngressPriorityNice == requestedNice
            ) {
                return@synchronized true
            }
            runCatching {
                Process.setThreadPriority(threadId, requestedNice)
                Process.getThreadPriority(threadId)
            }.fold(
                onSuccess = { effectiveNice ->
                    val applied = Niv99ImageIngressSchedulingPolicy.isApplied(
                        tier = tier,
                        requestedNice = requestedNice,
                        effectiveNice = effectiveNice
                    )
                    if (applied) {
                        appliedAnalysisIngressPriorityThreadId = threadId
                        appliedAnalysisIngressPriorityNice = requestedNice
                    } else {
                        appliedAnalysisIngressPriorityThreadId = -1
                        appliedAnalysisIngressPriorityNice = Int.MAX_VALUE
                    }
                    val message =
                        "NIV99_IMAGE_PRIORITY_APPLY applied=$applied reason=$reason " +
                            "tier=$tier threadId=$threadId requestedNice=$requestedNice " +
                            "effectiveNice=$effectiveNice recording=$recordingActive " +
                            "npv10=$npv10TenBitCaptureEnabled"
                    if (applied) Log.i(TAG, message) else Log.e(TAG, message)
                    applied
                },
                onFailure = { failure ->
                    appliedAnalysisIngressPriorityThreadId = -1
                    appliedAnalysisIngressPriorityNice = Int.MAX_VALUE
                    Log.e(
                        TAG,
                        "NIV99_IMAGE_PRIORITY_APPLY applied=false reason=$reason " +
                            "tier=$tier threadId=$threadId requestedNice=$requestedNice " +
                            "recording=$recordingActive npv10=$npv10TenBitCaptureEnabled",
                        failure
                    )
                    false
                }
            )
        }

    private fun registerCameraAvailabilityCallback() {
        val handler = backgroundHandler ?: return
        if (!cameraAvailabilityCallbackRegistered.compareAndSet(false, true)) return
        runCatching {
            cameraManager.registerAvailabilityCallback(cameraAvailabilityCallback, handler)
        }.onFailure { error ->
            cameraAvailabilityCallbackRegistered.set(false)
            Log.w(TAG, "Unable to register camera availability recovery", error)
        }
    }

    private fun unregisterCameraAvailabilityCallback() {
        if (!cameraAvailabilityCallbackRegistered.compareAndSet(true, false)) return
        runCatching {
            cameraManager.unregisterAvailabilityCallback(cameraAvailabilityCallback)
        }.onFailure { error ->
            Log.w(TAG, "Unable to unregister camera availability recovery", error)
        }
    }

    override fun armCameraAvailabilityRecovery(reason: String) {
        if (backgroundHandler == null || lastOpenReadyCallback == null || lastOpenErrorCallback == null) {
            return
        }
        if (cameraAvailabilityRecoveryArmed.compareAndSet(false, true)) {
            Log.w(TAG, "Camera availability recovery armed: $reason")
        }
    }

    override fun clearCameraAvailabilityRecovery() {
        cameraAvailabilityRecoveryArmed.set(false)
        backgroundHandler?.removeCallbacks(cameraAvailabilityRecoveryRunnable)
    }

    override fun setupReaders() {
        val handler = backgroundHandler ?: run {
            Log.w(TAG, "setupReaders skipped: background handler unavailable")
            return
        }
        val jpegHandler = jpegIngressHandler ?: handler
        val rawHandler = rawIngressHandler ?: handler
        val analysisHandler = analysisIngressHandler ?: handler
        val hardwareImportHandler = hardwareImportIngressHandler ?: handler
        promoteModeSwitchStandbyP010TargetBeforeReaderSetup()
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
        val targetYuvTransport = if (targetYuvFormat == ImageFormat.YCBCR_P010) {
            Npv10P010ReaderTransportPolicy.resolve(hdrDynamicRangeMode, hdrSupport)
        } else {
            null
        }
        val targetYuvDepth = targetYuvSize?.let { size ->
            if (targetYuvFormat == ImageFormat.YCBCR_P010) {
                Npv10InFlightBufferPolicy.readerMaxImages(size.width, size.height)
            } else {
                yuvReaderDepth()
            }
        } ?: 0
        // Native P010 is a lossless recording source, not best-effort analysis. Bind its
        // ImageReader callback to the dedicated urgent-display import lane that
        // reconcileNiv99ImageIngressPriority() promotes for an active NPV10 session. Keeping P010
        // on AnalysisImageIngress left the real callback at nice=-1 while an unused thread was
        // promoted to nice=-8; under sustained memory/storage pressure that allowed the reader ring
        // to fill and forced the camera HAL to omit sensor intervals. Ordinary YUV analysis remains
        // isolated on its original lane, and the hardware-import and P010 surfaces are mutually
        // exclusive in the stream graph.
        val yuvIngressHandler = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            targetYuvFormat == ImageFormat.YCBCR_P010
        ) {
            hardwareImportHandler
        } else {
            analysisHandler
        }
        val keepYuvTransport = Npv10P010ImageReaderFactory.matches(
            reader = oldYuv,
            imageFormat = targetYuvFormat,
            transport = targetYuvTransport
        )
        val keepIdleP010Standby =
            shouldRetainIdleP010StandbyReader(oldYuv, streamGraph) && keepYuvTransport
        val keepYuv = keepIdleP010Standby ||
            (targetYuvSize != null &&
                oldYuv != null &&
                oldYuv.width == targetYuvSize.width &&
                oldYuv.height == targetYuvSize.height &&
                oldYuv.imageFormat == targetYuvFormat &&
                oldYuv.maxImages == targetYuvDepth &&
                keepYuvTransport)

        val targetHwSize = hardwareImportTargetSize?.takeIf { streamGraph.hardwareImport.createReader }
        val targetHwFormat = hardwareImportImageFormat()
        val targetHwDepth = targetHwSize?.let { hardwareImportReaderDepth(it, targetHwFormat) } ?: 0
        val keepIdleHardwareImportStandby =
            shouldRetainIdleHardwareImportStandbyReader(oldHw, streamGraph)
        val keepHw = keepIdleHardwareImportStandby ||
            (targetHwSize != null &&
                oldHw != null &&
                oldHw.width == targetHwSize.width &&
                oldHw.height == targetHwSize.height &&
                oldHw.imageFormat == targetHwFormat &&
                oldHw.maxImages == targetHwDepth)

        if (keepJpeg || keepRaw || keepYuv || keepHw) {
            Log.i(
                TAG,
                "setupReaders: reusing matched ImageReaders " +
                    "(jpeg=$keepJpeg, raw=$keepRaw, yuv=$keepYuv, hw=$keepHw, " +
                    "hwStandby=$keepIdleHardwareImportStandby) " +
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

        if (!keepJpeg) oldJpeg?.let { readerBindings.retire(ReaderKind.JPEG, it) }
        if (!keepRaw) oldRaw?.let { readerBindings.retire(ReaderKind.RAW, it) }
        if (!keepYuv) oldYuv?.let { readerBindings.retire(ReaderKind.YUV, it) }
        if (!keepHw) oldHw?.let { readerBindings.retire(ReaderKind.HARDWARE_IMPORT, it) }
        jpegReader = if (keepJpeg) oldJpeg else null
        rawReader = if (keepRaw) oldRaw else null
        yuvReader = if (keepYuv) oldYuv else null
        hardwareImportReader = if (keepHw) oldHw else null

        try {
            if (jpegReader == null && targetJpegSize != null) {
                val createdReader = ImageReader.newInstance(
                    targetJpegSize.width, targetJpegSize.height, ImageFormat.JPEG, jpegReaderDepth()
                )
                jpegReader = createdReader
            }
            if (rawReader == null && targetRawSize != null) {
                val createdReader = ImageReader.newInstance(
                    targetRawSize.width, targetRawSize.height, ImageFormat.RAW_SENSOR, targetRawDepth
                )
                rawReader = createdReader
                Log.i(
                    TAG,
                    "RAW_SENSOR reader ${targetRawSize.width}x${targetRawSize.height} " +
                        "depth=${targetRawDepth} " +
                        "estimatedReaderMiB=${rawReaderDepth(targetRawSize, streamGraph).estimatedReaderBytes / (1024L * 1024L)} " +
                        "reason=${rawReaderDepth(targetRawSize, streamGraph).reason}"
                )
            }
            if (yuvReader == null && targetYuvSize != null) {
                val createdReader = Npv10P010ImageReaderFactory.create(
                    width = targetYuvSize.width,
                    height = targetYuvSize.height,
                    imageFormat = targetYuvFormat,
                    maxImages = targetYuvDepth,
                    transport = targetYuvTransport
                )
                yuvReader = createdReader
                Log.i(
                    TAG,
                    "Analysis reader ${targetYuvSize.width}x${targetYuvSize.height} format=$targetYuvFormat " +
                        "depth=$targetYuvDepth npv10TenBit=$npv10TenBitCaptureEnabled " +
                        "requestedDataspace=${targetYuvTransport?.dataSpace ?: 0} " +
                        "requestedDataspaceAuthority=${targetYuvTransport?.authority ?: "IMAGE_FORMAT_DEFAULT"} " +
                        "readerContract=${if (targetYuvFormat == ImageFormat.YCBCR_P010) {
                            Npv10P010ImageReaderFactory.P010_READER_CONTRACT
                        } else {
                            "IMAGE_FORMAT_DEFAULT"
                        }}"
                )
            }
            if (hardwareImportReader == null && targetHwSize != null) {
                val hardwareImportFormat = targetHwFormat
                val hardwareDepth = targetHwDepth
                val createdReader = ImageReader.newInstance(
                    targetHwSize.width, targetHwSize.height, hardwareImportFormat, hardwareDepth,
                    hardwareImportUsageFlags()
                )
                hardwareImportReader = createdReader
                Log.i(
                    TAG,
                    "Hardware import reader ${targetHwSize.width}x${targetHwSize.height} " +
                        "format=$targetHwFormat depth=$targetHwDepth " +
                        "p010Source=$hardwareImportP010SourceEnabled"
                )
            }
            jpegReader?.let { bindReaderCallback(ReaderKind.JPEG, it, jpegHandler) }
            rawReader?.let { bindReaderCallback(ReaderKind.RAW, it, rawHandler) }
            yuvReader?.let { bindReaderCallback(ReaderKind.YUV, it, yuvIngressHandler) }
            hardwareImportReader?.let {
                bindReaderCallback(ReaderKind.HARDWARE_IMPORT, it, hardwareImportHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup readers, cleaning up partially created resources", e)
            val partial = ReaderSet(
                jpeg = jpegReader,
                raw = rawReader,
                yuv = yuvReader,
                hardwareImport = hardwareImportReader
            )
            jpegReader = null
            rawReader = null
            yuvReader = null
            hardwareImportReader = null
            closeReaderSetsWithPipelineBarrier(
                requested = listOf(partial),
                reason = "partial reader setup cleanup"
            )
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

    private fun modeSwitchStandbyOutputsAllowed(): Boolean =
        highEndHardwareSupported &&
            supportsRaw &&
            modeSwitchStandbyAnalysisTargetSize != null &&
            (!recordingActive || (rawVideoPrearmEnabled && rawVideoCaptureEnabled)) &&
            modeSwitchStandbyOutputsRejectedCameraId != currentCameraId

    private fun resolveModeSwitchStandbyP010Target(
        requestedSize: Size?,
        tenBit: Boolean
    ): Size? {
        val normalizedRequest = requestedSize?.normalizedForCameraOutput() ?: return null
        if (!tenBit || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return normalizedRequest
        val chars = characteristics ?: return normalizedRequest
        val p010Sizes = resolveStreamConfigurationMaps(chars)
            .flatMap { map -> map.p010OutputSizes() }
            .distinctSortedCameraSizes()
        val exact = Npv10CaptureSourcePolicy.resolveNativeP010TargetForSizes(
            selectedResolution = Npv10CaptureSize(normalizedRequest.width, normalizedRequest.height),
            p010Sizes = p010Sizes.map { size -> Npv10CaptureSize(size.width, size.height) }
        ) ?: return normalizedRequest
        return Size(exact.width, exact.height).normalizedForCameraOutput()
    }

    private fun promoteModeSwitchStandbyP010TargetBeforeReaderSetup() {
        val provisional = modeSwitchStandbyAnalysisTargetSize ?: return
        val exact = resolveModeSwitchStandbyP010Target(
            requestedSize = provisional,
            tenBit = modeSwitchStandbyAnalysisTenBit
        ) ?: return
        if (exact == provisional) return
        modeSwitchStandbyAnalysisTargetSize = exact
        Log.i(
            TAG,
            "Cold-open standby P010 target resolved before reader setup " +
                "requested=${provisional.width}x${provisional.height} exact=${exact.width}x${exact.height}"
        )
    }

    /**
     * Retain an already-allocated native P010 reader across high-end mode changes. Reader retention
     * and Camera2 surface attachment are intentionally separate: a retained buffer queue is cheap,
     * while attaching it beside a required RAW route can crash affected Qualcomm camera providers.
     */
    private fun shouldRetainIdleP010StandbyReader(
        reader: ImageReader?,
        streamGraph: CinemaStreamGraphPlan
    ): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !recordingActive &&
            videoPreviewActive &&
            !streamGraph.yuvAnalysis.includeInSession &&
            modeSwitchStandbyOutputsAllowed() &&
            reader?.imageFormat == ImageFormat.YCBCR_P010

    /** Attach the retained P010 queue only when no live RAW Surface is present in the session. */
    private fun shouldAttachIdleP010StandbySurface(
        reader: ImageReader?,
        streamGraph: CinemaStreamGraphPlan
    ): Boolean =
        !processedRecordingSessionPreparationActive &&
            !streamGraph.rawSensorSurfaceActive &&
            shouldRetainIdleP010StandbyReader(reader, streamGraph)

    /**
     * Keeps the already allocated 4K P010 hardware-import queue across an idle RAW session without
     * attaching its Surface beside RAW_SENSOR. Recreating this eight-buffer queue on every RAW ->
     * processed transition adds native allocation pressure directly to the Camera2 hand-off and was
     * observed to push otherwise healthy later transitions beyond the one-second presentation gate.
     *
     * Compatibility is checked against the immutable mode-switch standby target rather than the live
     * hardware-import fields: RAW intentionally clears those fields while it owns the sensor route.
     */
    private fun shouldRetainIdleHardwareImportStandbyReader(
        reader: ImageReader?,
        streamGraph: CinemaStreamGraphPlan
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            recordingActive ||
            !videoPreviewActive ||
            !streamGraph.rawSensorSurfaceActive ||
            streamGraph.hardwareImport.includeInSession ||
            !modeSwitchStandbyOutputsAllowed() ||
            reader == null ||
            reader.imageFormat != ImageFormat.YCBCR_P010
        ) {
            return false
        }
        val standbySize = modeSwitchStandbyAnalysisTargetSize
            ?.normalizedForCameraOutput()
            ?: return false
        return reader.width == standbySize.width &&
            reader.height == standbySize.height &&
            reader.maxImages == hardwareImportReaderDepth(
                standbySize,
                ImageFormat.YCBCR_P010
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
        val ownerSession = captureSession
        if (ownerSession == null) {
            closeReaderSetsWithPipelineBarrier(
                requested = listOf(readers),
                reason = "no active session"
            )
        } else {
            synchronized(sessionCollectionsLock) {
                deferredReaderCloseBatches
                    .getOrPut(ownerSession) { mutableListOf() }
                    .add(readers)
            }
            Log.d(
                TAG,
                "Deferred ImageReader close until owning Camera2 session closes"
            )
        }
    }

    private fun closeDeferredReadersSafely(ownerSession: CameraCaptureSession, reason: String) {
        val batches = synchronized(sessionCollectionsLock) {
            deferredReaderCloseBatches.remove(ownerSession) ?: return
        }
        if (closeReaderSetsWithPipelineBarrier(batches, reason)) {
            Log.d(TAG, "Closed deferred ImageReaders: $reason")
        }
    }

    private fun closeAllDeferredReadersSafely(reason: String): Boolean {
        val batches = synchronized(sessionCollectionsLock) {
            deferredReaderCloseBatches.values.flatten().also { deferredReaderCloseBatches.clear() }
        }
        val closed = closeReaderSetsWithPipelineBarrier(batches, reason)
        if (closed) {
            Log.d(TAG, "Closed all deferred ImageReaders: $reason")
        }
        return closed
    }

    /**
     * The barrier callback is nullable only for construction before pipeline binding, or teardown
     * after every ImageReader callback has been detached and its owner has already drained ingress.
     * A missing callback while any reader callback is attached is never treated as permission to close.
     */
    private fun readerCallbacksDetachedForUnboundClose(): Boolean =
        onYuvFrame == null && onHardwareFrame == null && onJpegReady == null && onRawReady == null

    private fun closeReaderSetsWithPipelineBarrier(
        requested: List<ReaderSet>,
        reason: String
    ): Boolean {
        val readerSets = synchronized(sessionCollectionsLock) {
            ArrayList<ReaderSet>(quarantinedReaderCloseBatches.size + requested.size).apply {
                addAll(quarantinedReaderCloseBatches)
                quarantinedReaderCloseBatches.clear()
                addAll(requested.filter { readers -> readers.hasReaders })
            }
        }
        if (readerSets.isEmpty()) return true

        val closeRequest = CameraReaderCloseRequest(
            jpeg = readerSets.any { it.jpeg != null },
            raw = readerSets.any { it.raw != null },
            yuv = readerSets.any { it.yuv != null },
            hardwareImport = readerSets.any { it.hardwareImport != null }
        )
        val downstreamBarrier = closeReadersInsideFramePipelineBarrier
        val callbackSeal = readerCallbackIngress.seal()
        retireReaderBindings(readerSets)
        retireCurrentReaderIdentities(readerSets)
        val result = try {
            CameraReaderCloseBarrierPolicy.execute(
                barrier = { timeoutMs, closeAction ->
                    val startedNs = SystemClock.elapsedRealtimeNanos()
                    if (!readerCallbackIngress.awaitIdle(timeoutMs)) {
                        false
                    } else {
                        val elapsedNs = (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0L)
                        val timeoutNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
                        val remainingNs = (timeoutNs - elapsedNs).coerceAtLeast(0L)
                        val remainingMs = if (remainingNs <= 0L) 0L else {
                            (remainingNs + 999_999L) / 1_000_000L
                        }
                        when {
                            downstreamBarrier != null && remainingMs > 0L ->
                                downstreamBarrier(closeRequest, remainingMs, closeAction)
                            downstreamBarrier == null && readerCallbacksDetachedForUnboundClose() -> {
                                closeAction()
                                true
                            }
                            else -> false
                        }
                    }
                },
                timeoutMs = READER_CLOSE_FRAME_PIPELINE_IDLE_TIMEOUT_MS,
                directCloseAllowed = false,
                closeAction = { closeReaderSetsNow(readerSets) }
            )
        } finally {
            callbackSeal.close()
        }
        if (result.closeCompleted) return true

        synchronized(sessionCollectionsLock) {
            // Retain the exact owning sets. A later guarded close retries them; no timeout path is
            // permitted to invalidate an Image that may still be owned by a queued callback.
            quarantinedReaderCloseBatches.addAll(readerSets)
        }
        val message = "ImageReader close quarantined: reason=$reason sets=${readerSets.size} " +
            "barrierBound=${downstreamBarrier != null} closeActionInvoked=${result.closeActionInvoked}"
        val failure = result.failure
        if (failure != null) {
            Log.e(TAG, message, failure)
        } else {
            Log.e(TAG, message)
        }
        return false
    }

    private fun retireCurrentReaderIdentities(readerSets: List<ReaderSet>) {
        readerSets.forEach { readers ->
            if (jpegReader === readers.jpeg) jpegReader = null
            if (rawReader === readers.raw) rawReader = null
            if (yuvReader === readers.yuv) yuvReader = null
            if (hardwareImportReader === readers.hardwareImport) hardwareImportReader = null
        }
    }

    private fun retireReaderBindings(readerSets: List<ReaderSet>) {
        readerSets.forEach { readers ->
            readers.jpeg?.let { readerBindings.retire(ReaderKind.JPEG, it) }
            readers.raw?.let { readerBindings.retire(ReaderKind.RAW, it) }
            readers.yuv?.let { readerBindings.retire(ReaderKind.YUV, it) }
            readers.hardwareImport?.let {
                readerBindings.retire(ReaderKind.HARDWARE_IMPORT, it)
            }
        }
    }

    /** Detach each exact listener before its native ImageReader is closed. */
    private fun detachReaderCallbacks(readerSets: List<ReaderSet>) {
        val detachedReaders = java.util.IdentityHashMap<ImageReader, Boolean>()
        readerSets.forEach { readers ->
            listOf(readers.jpeg, readers.raw, readers.yuv, readers.hardwareImport).forEach { reader ->
                if (reader != null && detachedReaders.put(reader, true) == null) {
                    runCatching { reader.setOnImageAvailableListener(null, null) }
                        .onFailure { failure ->
                            Log.w(TAG, "ImageReader listener detach failed before close", failure)
                        }
                }
            }
        }
    }

    private fun closeReaderSetsNow(readerSets: List<ReaderSet>) {
        detachReaderCallbacks(readerSets)
        val closedReaders = java.util.IdentityHashMap<ImageReader, Boolean>()
        var firstFailure: Throwable? = null
        fun closeOnce(reader: ImageReader?) {
            if (reader != null && closedReaders.put(reader, true) == null) {
                val failure = closeReaderSafely(reader)
                if (failure != null) {
                    val first = firstFailure
                    if (first == null) {
                        firstFailure = failure
                    } else if (first !== failure) {
                        first.addSuppressed(failure)
                    }
                }
            }
        }
        readerSets.forEach { readers ->
            closeOnce(readers.jpeg)
            closeOnce(readers.raw)
            closeOnce(readers.yuv)
            closeOnce(readers.hardwareImport)
        }
        firstFailure?.let { failure ->
            throw IllegalStateException("One or more ImageReaders failed to close", failure)
        }
    }

    private fun closeReaderSafely(reader: ImageReader): Throwable? = try {
        reader.close()
        null
    } catch (failure: Exception) {
        Log.e(TAG, "Reader close failed; ownership retained for guarded retry", failure)
        failure
    }

    fun openCamera(onReady: () -> Unit, onError: (String) -> Unit) {
        cameraDeviceOrchestrator.openCamera(onReady, onError)
    }

    fun closeCamera() {
        cameraDeviceOrchestrator.closeCamera()
    }

    private fun continuousAutoFocusMode(): Int {
        val modes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return CaptureRequest.CONTROL_AF_MODE_OFF
        return CameraFocusControl.selectAvailableContinuousAutoFocusMode(
            availableModes = modes,
            // CONTINUOUS_PICTURE acquires faster while composing. Switch to the damped video servo
            // only for an actual recording, where focus-pump suppression matters more than snap.
            isRecording = recordingActive
        )
    }

    private fun stillPictureAutoFocusMode(): Int {
        val modes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: return continuousAutoFocusMode()
        return when {
            modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> continuousAutoFocusMode()
        }
    }

    private fun singleShotAutoFocusMode(): Int {
        val modes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: return continuousAutoFocusMode()
        // s5-f01 / CP-F2: preference table (AUTO -> MACRO -> continuous) lives in the pure,
        // golden-locked CameraFocusControl selector; this is an extract-only delegation with no
        // behavior change.
        return CameraFocusControl.selectSingleShotAutoFocusMode(
            availableModes = modes,
            isRecording = recordingActive
        )
    }

    /** Defers repeating-request work behind whichever exact Camera2 boundary currently owns it. */
    private fun deferRepeatingRefreshBehindActiveBoundary(): Boolean {
        activeP010StopBoundary.get()?.let { owner ->
            p010StopDeferredRequestRefresh.defer(owner)
            return true
        }
        activeRawResumeBoundary.get()?.let { owner ->
            rawResumeDeferredRequestRefresh.defer(owner)
            return true
        }
        return false
    }

    /**
     * The only repeating-request submission point. The lock makes stop-fence installation atomic
     * with submission and records the exact Camera2 sequence id returned by the HAL.
     */
    private fun submitTrackedRepeatingRequest(
        session: CameraCaptureSession,
        request: CaptureRequest,
        callback: CameraCaptureSession.CaptureCallback,
        p010Surface: Surface?,
        authorizedRawResumeBoundary: RawCaptureResumeBoundaryRequestToken? = null,
        authorizedNpv10StartupLease: Npv10ProducerStartupLeaseGate.Lease? = null
    ): Int? = synchronized(cameraSubmissionLock) {
        val generation = sessionGeneration.get()
        when (
            npv10ProducerStartupLeaseGate.admitSubmission(
                sessionGeneration = generation,
                authorization = authorizedNpv10StartupLease
            )
        ) {
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.ALLOWED -> Unit
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.DEFERRED,
            Npv10ProducerStartupLeaseGate.SubmissionAdmission.STALE_AUTHORIZATION -> return null
        }
        val p010StopOwner = activeP010StopBoundary.get()
        if (p010StopOwner != null) {
            p010StopDeferredRequestRefresh.defer(p010StopOwner)
            return null
        }
        val rawResumeOwner = activeRawResumeBoundary.get()
        if (rawResumeOwner != null && rawResumeOwner !== authorizedRawResumeBoundary) {
            rawResumeDeferredRequestRefresh.defer(rawResumeOwner)
            return null
        }
        val resultHandler = captureResultHandler ?: run {
            Log.e(TAG, "Camera2 repeating submission rejected: capture-result handler unavailable")
            return null
        }
        val sequenceId = session.setRepeatingRequest(request, callback, resultHandler)
        if (p010Surface != null) registerP010ProducerSequence(session, generation, sequenceId)
        val publicationGeneration = repeatingPublicationGeneration.updateAndGet { current ->
            if (current == Long.MAX_VALUE) 1L else current + 1L
        }
        activeRepeatingSequence.set(
            ActiveCameraRepeatingSequence(
                publicationGeneration = publicationGeneration,
                identity = P010RepeatingSequenceIdentity(
                    session = session,
                    sessionGeneration = generation,
                    sequenceId = sequenceId
                ),
                request = request,
                p010Surface = p010Surface
            )
        )
        if (authorizedNpv10StartupLease?.releaseRequested?.get() == true) {
            val commitEligible = Npv10ProducerStartupCommitPolicy.canCommitReplay(
                releaseRequested = true,
                p010TargetPresent = p010Surface != null,
                sessionCurrent = session === captureSession,
                submissionGeneration = generation,
                leaseGeneration = authorizedNpv10StartupLease.sessionGeneration
            )
            if (commitEligible) {
                npv10ProducerStartupLeaseGate.completeDeferredReplay(authorizedNpv10StartupLease)
            } else {
                npv10ProducerStartupLeaseGate.revoke(
                    authorizedNpv10StartupLease,
                    "Authorized NPV10 replay did not publish the current P010 producer target"
                )
            }
        }
        sequenceId
    }

    /**
     * Submits one current-generation preview-only request ahead of a RAW/P010 family hand-off.
     * The full repeating request is submitted immediately afterwards and remains the sole sustained
     * pipeline owner. This request cannot run during recording, a RAW resume boundary, or an NPV10
     * stop transaction, and therefore cannot steal ownership from a capture/recording sequence.
     */
    private fun submitModeSwitchFirstFramePrimeCapture(
        session: CameraCaptureSession,
        expectedGeneration: Long,
        request: CaptureRequest
    ): Int? = synchronized(cameraSubmissionLock) {
        if (recordingActive ||
            session !== captureSession ||
            expectedGeneration != sessionGeneration.get() ||
            activeP010StopBoundary.get() != null ||
            activeRawResumeBoundary.get() != null
        ) {
            return null
        }
        submitTrackedCaptureRequest(
            session = session,
            request = request,
            callback = captureCallback,
            targetsP010 = false
        )
    }

    internal fun currentRepeatingReadinessSnapshot(): CameraRepeatingReadinessSnapshot? {
        val repeating = activeRepeatingSequence.get() ?: return null
        val currentSession = captureSession ?: return null
        val currentGeneration = sessionGeneration.get()
        if (repeating.identity.session !== currentSession ||
            repeating.identity.sessionGeneration != currentGeneration
        ) return null
        val firstFrame = runCatching { repeating.firstFrameProof.getNow(null) }.getOrNull()
        return CameraRepeatingReadinessSnapshot(
            publicationGeneration = repeating.publicationGeneration,
            sessionGeneration = repeating.identity.sessionGeneration,
            sequenceId = repeating.identity.sequenceId,
            firstFrameSensorTimestampNs = firstFrame?.sensorTimestampNs,
            firstFrameReceivedAtElapsedRealtimeNs = firstFrame?.receivedAtElapsedRealtimeNs
        )
    }

    /** Every one-shot submission that could contain P010 passes through this stop-fence gate. */
    private fun submitTrackedCaptureRequest(
        session: CameraCaptureSession,
        request: CaptureRequest,
        callback: CameraCaptureSession.CaptureCallback,
        targetsP010: Boolean,
        authorizedP010StopBoundary: P010StopBoundaryRequestToken? = null
    ): Int? = synchronized(cameraSubmissionLock) {
        val stopOwner = activeP010StopBoundary.get()
        if (stopOwner != null && stopOwner !== authorizedP010StopBoundary && targetsP010) {
            p010StopDeferredRequestRefresh.defer(stopOwner)
            return null
        }
        val resultHandler = captureResultHandler ?: run {
            Log.e(TAG, "Camera2 capture submission rejected: capture-result handler unavailable")
            return null
        }
        session.capture(request, callback, resultHandler).also { sequenceId ->
            if (targetsP010) registerP010ProducerSequence(session, sessionGeneration.get(), sequenceId)
        }
    }

    /** Caller holds cameraSubmissionLock so results cannot race registration of a returned ID. */
    private fun registerP010ProducerSequence(session: CameraCaptureSession, generation: Long, sequenceId: Int) {
        val previous = p010ProducerTailTracker.get()
        val tracker = if (previous != null && previous.belongsTo(session, generation) && !previous.isFrozen()) {
            previous
        } else {
            P010ProducerTailTracker(session, generation).also(p010ProducerTailTracker::set)
        }
        check(tracker.registerSequence(sequenceId)) { "P010 producer sequence registration failed id=$sequenceId" }
    }

    /** Resolves the API-31 P010 reader surface without touching its format constant on API 29/30. */
    private fun p010ReaderSurfaceOrNull(reader: ImageReader?): Surface? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return reader
            ?.takeIf { it.imageFormat == ImageFormat.YCBCR_P010 }
            ?.surface
    }

    private fun updatePreview(immediate: Boolean = false) {
        if (deferRepeatingRefreshBehindActiveBoundary()) return
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
            if (deferRepeatingRefreshBehindActiveBoundary()) return@runOnCameraThread
            if (captureSession != sess || generation != sessionGeneration.get()) return@runOnCameraThread
            if (previewBuilder !== builder) {
                // A target retarget won the race. Re-apply this control update to its new builder instead
                // of allowing the obsolete builder to restore RAW/P010 targets from the previous mode.
                updatePreview(immediate = immediate)
                return@runOnCameraThread
            }
            try {
                val activeP010Surface = p010ReaderSurfaceOrNull(yuvReader)
                val request = buildSoftwareAeTrackedRequest(builder) {
                    applyAll(builder, forStillCapture = false)
                }
                // Always install the change on the REPEATING request so it persists. A one-shot
                // capture() alone (the old immediate path) applied for a single frame and then the
                // previously-installed repeating request resumed with the old parameters — so the
                // locked-exposure slider flickered to target for one frame and snapped back. When
                // immediate is requested we additionally fire one priority capture so the change lands
                // on the very next frame without waiting for the repeating cadence.
                val repeatingSubmitted = submitTrackedRepeatingRequest(
                    session = sess,
                    request = request,
                    callback = captureCallback,
                    p010Surface = activeP010Surface
                        ?.takeIf { target ->
                            previewBuilderTargetSnapshot.any { it === target }
                        }
                ) != null
                if (!repeatingSubmitted) return@runOnCameraThread
                if (immediate) {
                    runCatching {
                        submitTrackedCaptureRequest(
                            session = sess,
                            request = request,
                            callback = captureCallback,
                            targetsP010 = activeP010Surface?.let { p010Surface ->
                                previewBuilderTargetSnapshot.any { it === p010Surface }
                            } == true
                        )
                    }
                }
            } catch (e: IllegalStateException) {
                // Session was closed, this is expected during camera teardown
                Log.d(TAG, "updatePreview: session closed, skipping (normal during teardown)")
            } catch (e: Exception) {
                Log.e(TAG, "updatePreview failed", e)
            }
        }
    }

    /**
     * Applies engine feedback immediately except while an NPV10 producer startup owns the exact
     * Camera2 request boundary. During that bounded interval mutable controller state keeps receiving
     * the newest AE/AWB/AF/detail decision, while this gate coalesces request publication into a
     * retained-lease replay before writer attachment. User and external setters intentionally continue
     * through [updatePreview], where the strict producer lease fails closed if they race the final
     * cadence proof.
     */
    private fun updatePreviewFromAutonomousControlFeedback(immediate: Boolean = false) {
        val deferred = synchronized(cameraSubmissionLock) {
            npv10AutonomousControlRefreshGate.deferIfActive(sessionGeneration.get())
        }
        if (deferred) return
        updatePreview(immediate = immediate)
    }

    /**
     * Establishes an exact Camera2 ordering boundary before a paused RAW-original writer reopens.
     *
     * The current repeating sequence is stopped, then one tagged request is submitted with the same
     * configured RAW target. Camera2 orders that request after every already-submitted pause-era
     * capture. Its SENSOR_TIMESTAMP is therefore a lossless boundary: the writer rejects the marker
     * and every older RAW Image, publishes its new admission token, and only then is repeating
     * restarted. No wall-clock conversion, cadence estimate, or callback-queue-depth guess is used.
     */
    internal fun resumeRawVideoCaptureAtExactBoundary(
        timeoutMs: Long,
        commitAdmission: (RawCaptureResumeBoundary) -> Boolean,
        rollbackAdmission: () -> Unit
    ): Boolean {
        if (timeoutMs <= 0L) return false
        val handler = backgroundHandler ?: return false
        val resultHandler = captureResultHandler ?: return false
        if (Looper.myLooper() === handler.looper || Looper.myLooper() === resultHandler.looper) {
            Log.e(TAG, "RAW resume boundary cannot block the Camera2 callback looper")
            return false
        }
        val session = captureSession ?: return false
        val generation = sessionGeneration.get()
        val token = RawCaptureResumeBoundaryRequestToken(
            session = session,
            sessionGeneration = generation,
            commitAdmission = commitAdmission,
            rollbackAdmission = rollbackAdmission
        )
        val rawBoundaryInstalled = synchronized(cameraSubmissionLock) {
            activeP010StopBoundary.get() == null &&
                activeRawResumeBoundary.compareAndSet(null, token)
        }
        if (!rawBoundaryInstalled) return false
        val dispatchFailure = runCatching {
            runOnCameraThread {
                // The caller can time out before this two-hop executor/Handler dispatch runs. A cancelled
                // token must never stop a recovered repeating stream after its logical transaction ended.
                if (activeRawResumeBoundary.get() !== token || token.completionState.get() != 0) {
                    return@runOnCameraThread
                }
                if (captureSession !== session || generation != sessionGeneration.get() ||
                    !recordingActive || !rawVideoCaptureEnabled || rawReader == null
                ) {
                    finishRawResumeBoundary(token, success = false)
                    return@runOnCameraThread
                }
                val builder = previewBuilder
                if (builder == null || !previewBuilderHasTargets) {
                    finishRawResumeBoundary(token, success = false)
                    return@runOnCameraThread
                }
                var repeatingStopped = false
                try {
                    // Revalidate at the destructive Camera2 boundary. Timeout/interrupt cancellation can
                    // race the validation above from the blocking caller thread.
                    if (activeRawResumeBoundary.get() !== token || token.completionState.get() != 0) {
                        return@runOnCameraThread
                    }
                    session.stopRepeating()
                    repeatingStopped = true
                    val previousTag = builder.build().tag
                    val markerRequest = try {
                        builder.setTag(token)
                        buildSoftwareAeTrackedRequest(builder)
                    } finally {
                        builder.setTag(previousTag)
                    }
                    val rawSurface = rawReader?.surface
                        ?: throw IllegalStateException("RAW resume marker lost its configured reader")
                    check(previewBuilderTargetSnapshot.any { it === rawSurface }) {
                        "RAW resume marker must target the authoritative RAW_SENSOR surface"
                    }
                    val activeP010Surface = p010ReaderSurfaceOrNull(yuvReader)
                    val markerSequence = submitTrackedCaptureRequest(
                        session = session,
                        request = markerRequest,
                        callback = captureCallback,
                        targetsP010 = activeP010Surface?.let { p010Surface ->
                            previewBuilderTargetSnapshot.any { it === p010Surface }
                        } == true
                    ) ?: throw IllegalStateException(
                        "RAW resume marker was fenced by a competing P010 stop"
                    )
                    token.markerSequenceId.set(markerSequence)
                } catch (failure: Throwable) {
                    Log.e(TAG, "Unable to submit exact RAW resume boundary", failure)
                    if (repeatingStopped) restartPausedRawRepeating(token)
                    finishRawResumeBoundary(token, success = false)
                }
            }
        }.exceptionOrNull()
        if (dispatchFailure != null) {
            Log.e(TAG, "Unable to dispatch exact RAW resume boundary", dispatchFailure)
            finishRawResumeBoundary(token, success = false)
            return false
        }

        return try {
            token.completion.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            if (token.completionState.compareAndSet(0, 2)) {
                // Restart is best effort on the Camera2 thread, but rollback and token retirement are
                // synchronous. runOnCameraThread is a void two-hop post and can be dropped during
                // teardown; allowing cleanup to depend on it would wedge every future request update.
                runCatching {
                    runOnCameraThread { restartPausedRawRepeating(token) }
                }.onFailure {
                    Log.w(TAG, "Unable to dispatch RAW resume timeout restart", it)
                }
                finishRawResumeBoundary(token, success = false)
                false
            } else {
                // The Camera2 callback already owns the tiny commit/restart critical section.
                runCatching { token.completion.get(1_000L, TimeUnit.MILLISECONDS) }
                    .getOrDefault(false)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            if (token.completionState.compareAndSet(0, 2)) {
                runCatching {
                    runOnCameraThread { restartPausedRawRepeating(token) }
                }.onFailure {
                    Log.w(TAG, "Unable to dispatch interrupted RAW resume restart", it)
                }
                finishRawResumeBoundary(token, success = false)
            }
            false
        } catch (failure: Throwable) {
            Log.e(TAG, "Exact RAW resume boundary failed", failure)
            false
        }
    }

    /** Invoked only by the exact old repeating sequence's terminal completion callback. */
    private fun submitP010StopMarkerAfterRepeatingCompletion(
        token: P010StopBoundaryRequestToken
    ) {
        val handler = backgroundHandler
        if (handler == null) {
            token.state.fail("Camera control handler unavailable for P010 stop marker")
            return
        }
        if (Looper.myLooper() !== handler.looper) {
            if (!handler.post { submitP010StopMarkerAfterRepeatingCompletion(token) }) {
                token.state.fail("Camera control thread rejected the P010 stop marker")
            }
            return
        }
        synchronized(cameraSubmissionLock) {
            if (activeP010StopBoundary.get() !== token ||
                captureSession !== token.session ||
                sessionGeneration.get() != token.sessionGeneration ||
                yuvReader !== token.reader || token.reader.surface !== token.p010Surface
            ) {
                token.state.fail("P010 stop marker ownership became stale after repeating completion")
                return
            }
            try {
                val device = cameraDevice
                    ?: throw IllegalStateException("Camera device is unavailable for P010 stop marker")
                val requestPlan = resolveCamera2PipelineRequestPlan(currentCinemaStreamGraphPlan())
                val markerRequest = device.createCaptureRequest(requestPlan.template).apply {
                    addTarget(token.p010Surface)
                    applyAll(this, pipelineRequestPlan = requestPlan)
                    setTag(token)
                }.build()
                val markerSequence = submitTrackedCaptureRequest(
                    session = token.session,
                    request = markerRequest,
                    callback = captureCallback,
                    targetsP010 = true,
                    authorizedP010StopBoundary = token
                ) ?: throw IllegalStateException("P010 stop marker submission was fenced")
                check(
                    token.state.registerMarkerSequence(
                        session = token.session,
                        sessionGeneration = token.sessionGeneration,
                        sequenceId = markerSequence
                    )
                ) { "P010 stop marker sequence could not be registered" }
            } catch (failure: Throwable) {
                Log.e(TAG, "Unable to submit exact P010 stop marker", failure)
                token.state.fail(
                    "P010 stop marker submission failed: " +
                        (failure.message ?: failure.javaClass.simpleName)
                )
            }
        }
    }

    /**
     * Stops the exact P010 producer without allowing a buffered suffix to escape the NPV10 ledger.
     *
     * The fence is installed synchronously before camera-thread dispatch. It remains installed while
     * [proveMarkerAccepted] drains the live reader and while [sealDetachAndBeginFinalization] seals
     * pipeline ingress, detaches the sink and closes recorder admission. Any failure poisons the
     * active recorder before cleanup so a `.verified` marker cannot be published.
     */
    internal fun stopNpv10P010CaptureAtExactBoundary(
        timeoutMs: Long,
        proveMarkerAccepted: (ImageReader, Long, Long) -> Boolean,
        establishProducerBoundary: (Long) -> Boolean,
        poisonRecorder: (String) -> Unit,
        sealDetachAndBeginFinalization: (Long) -> P010StopBoundaryCleanupResult
    ): Boolean {
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        val startedNs = System.nanoTime()
        val deadlineNs = if (timeoutNanos >= Long.MAX_VALUE - startedNs) {
            Long.MAX_VALUE
        } else {
            startedNs + timeoutNanos
        }
        fun remainingNanos(): Long = (deadlineNs - System.nanoTime()).coerceAtLeast(0L)
        fun remainingMillis(): Long {
            val remaining = remainingNanos()
            if (remaining <= 0L) return 0L
            return ((remaining + 999_999L) / 1_000_000L).coerceAtLeast(1L)
        }

        var failureReason: String? = null
        val poisonIssued = AtomicBoolean(false)
        fun poison(reason: String) {
            if (failureReason == null) failureReason = reason
            if (poisonIssued.compareAndSet(false, true)) {
                runCatching { poisonRecorder(reason) }
                    .onFailure { Log.e(TAG, "Unable to poison failed NPV10 stop boundary", it) }
            }
        }

        val handler = backgroundHandler
        val resultHandler = captureResultHandler
        if (timeoutMs <= 0L) failureReason = "P010 stop boundary requires a positive timeout"
        if (handler == null) failureReason = failureReason ?: "Camera control thread is unavailable"
        if (resultHandler == null) failureReason = failureReason ?: "Camera2 callback thread is unavailable"
        if ((handler != null && Looper.myLooper() === handler.looper) ||
            (resultHandler != null && Looper.myLooper() === resultHandler.looper)
        ) {
            failureReason = failureReason ?: "P010 stop boundary cannot block the Camera2 callback thread"
        }

        var installFailure: String? = failureReason
        val token = if (installFailure == null) {
            synchronized(cameraSubmissionLock) {
                val session = captureSession
                val generation = sessionGeneration.get()
                val reader = yuvReader
                val p010Surface = p010ReaderSurfaceOrNull(reader)
                val repeating = activeRepeatingSequence.get()
                val capturedTail = p010ProducerTailTracker.get()
                when {
                    activeRawResumeBoundary.get() != null -> {
                        installFailure = "RAW resume and P010 stop boundaries are mutually exclusive"
                        null
                    }
                    activeP010StopBoundary.get() != null -> {
                        installFailure = "Another P010 stop boundary is already active"
                        null
                    }
                    session == null || reader == null || p010Surface == null -> {
                        installFailure = "Active Camera2 P010 reader/session is unavailable"
                        null
                    }
                    repeating == null || repeating.identity.session !== session ||
                        repeating.identity.sessionGeneration != generation ||
                        repeating.p010Surface !== p010Surface -> {
                        installFailure = "Exact active P010 repeating sequence is not proven"
                        null
                    }
                    capturedTail == null || !capturedTail.belongsTo(session, generation) ||
                        !capturedTail.freeze(repeating.identity.sequenceId) -> {
                        installFailure = "Exact P010 submitted-sequence tail registry is unavailable"
                        null
                    }
                     else -> P010StopBoundaryRequestToken(
                         session = session,
                         sessionGeneration = generation,
                         reader = reader,
                         p010Surface = p010Surface,
                         repeatingSequence = repeating,
                         state = P010StopBoundaryState(repeating.identity),
                         capturedProducerTail = capturedTail
                     ).also { candidate ->
                        if (!activeP010StopBoundary.compareAndSet(null, candidate)) {
                            installFailure = "P010 stop fence installation lost ownership"
                        }
                    }.takeIf { activeP010StopBoundary.get() === it }
                }
            }
        } else {
            null
        }

        var boundaryProven = false
        var logicalCaptureStopped = false
        var cleanupCompleted = false
        var cleanupSinkDetached = false
        try {
            if (token == null) {
                poison(installFailure ?: "P010 stop fence could not be installed")
            } else {
                // A newly replaced repeating sequence is not stoppable until Camera2 proves that its
                // exact request produced a current-generation sensor frame. The already-installed stop
                // fence blocks all competing submissions while this bounded wait completes, preventing
                // the HAL from reporting an immediate sequence abort for a request that never started.
                val firstFrameProof = try {
                    token.repeatingSequence.firstFrameProof.get(
                        remainingNanos(),
                        TimeUnit.NANOSECONDS
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                } catch (_: Throwable) {
                    null
                }
                if (firstFrameProof == null) {
                    token.state.fail(
                        "Exact P010 repeating sequence produced no current-generation frame before stop"
                    )
                } else {
                    val dispatched = requireNotNull(handler).post {
                        if (activeP010StopBoundary.get() !== token) {
                            token.state.fail("P010 stop fence ownership changed before Camera2 dispatch")
                            return@post
                        }
                        synchronized(cameraSubmissionLock) {
                            if (activeP010StopBoundary.get() !== token ||
                                activeRepeatingSequence.get() !== token.repeatingSequence ||
                                captureSession !== token.session ||
                                sessionGeneration.get() != token.sessionGeneration ||
                                yuvReader !== token.reader ||
                                token.reader.surface !== token.p010Surface
                            ) {
                                token.state.fail("P010 stop Camera2 ownership became stale before submission")
                                return@synchronized
                            }
                            if (!previewBuilderHasTargets ||
                                previewBuilderTargetSnapshot.none { it === token.p010Surface }
                            ) {
                                token.state.fail("P010 stop marker lost its authoritative target")
                                return@synchronized
                            }
                            try {
                                // stopRepeating preserves every already-submitted frame; abortCaptures is
                                // deliberately forbidden because it can discard a valid suffix.
                                token.session.stopRepeating()
                            } catch (failure: Throwable) {
                                Log.e(TAG, "Unable to stop exact P010 repeating sequence", failure)
                                token.state.fail(
                                    "P010 stopRepeating failed: " +
                                        (failure.message ?: failure.javaClass.simpleName)
                                )
                            }
                        }
                    }
                    if (!dispatched) {
                        token.state.fail("Camera2 thread rejected P010 stop dispatch")
                    }
                }

                token.capturedProducerTail?.let { tracker ->
                    when (val tail = tracker.await(remainingNanos())) {
                        is P010CapturedProducerTail.Failed -> token.state.fail(tail.reason)
                        is P010CapturedProducerTail.Proven -> {
                            token.state.onCompletedCapturedProducerTail(
                                token.session, token.sessionGeneration, tail.frameNumber, tail.sensorTimestampNs
                            )
                            Log.i(TAG, "P010_CAPTURED_PRODUCER_TAIL frame=${tail.frameNumber} " +
                                "timestampNs=${tail.sensorTimestampNs} extraExposures=0")
                        }
                    }
                }
                val cameraResult = token.state.awaitCameraMarker(remainingNanos())
                when (cameraResult) {
                    is P010StopBoundaryCameraResult.Failed -> poison(cameraResult.reason)
                    is P010StopBoundaryCameraResult.MarkerReady -> {
                        val markerTimestampNs = cameraResult.markerTimestampNs
                        val markerAccepted = runCatching {
                            proveMarkerAccepted(
                                token.reader,
                                markerTimestampNs,
                                remainingMillis()
                            )
                        }.onFailure {
                            Log.e(TAG, "P010 marker ownership proof failed", it)
                        }.getOrDefault(false)
                        if (!markerAccepted) {
                            val reason = "Exact P010 stop marker timestamp=$markerTimestampNs " +
                                "was not accepted by the live sink"
                            token.state.fail(reason)
                            poison(reason)
                        } else {
                            token.state.onRecorderImageOwnershipTransferred(markerTimestampNs)
                            val drainClaimed = token.state.claimReaderFifoDrain()
                            val analysisFencePassed = drainClaimed && awaitP010AnalysisIngressFence(
                                handler = analysisIngressHandler,
                                timeoutNanos = remainingNanos()
                            )
                            if (analysisFencePassed) {
                                token.state.onReaderFifoDrained(establishProducerBoundary)
                                boundaryProven = token.state.await(remainingNanos()) is
                                    P010StopBoundaryResult.Proven
                                if (!boundaryProven) {
                                    poison("P010 stop boundary did not reach its exact terminal proof")
                                }
                            } else {
                                token.state.fail(
                                    "P010 analysis callback FIFO did not drain after marker ownership"
                                )
                                poison("P010 analysis callback FIFO did not drain after marker ownership")
                            }
                        }
                    }
                }
            }

            logicalCaptureStopped = stopP010RecordingStateWithoutRetarget(
                token = token,
                handler = handler,
                timeoutNanos = remainingNanos()
            )
            if (!logicalCaptureStopped) {
                poison("Camera recording state could not stop inside the P010 fence")
            }

            // Boundary failures are poisoned above, before this callback can begin finalization.
            val cleanupResult = runCatching {
                sealDetachAndBeginFinalization(remainingMillis())
            }.onFailure { failure ->
                Log.e(TAG, "P010 stop cleanup failed", failure)
            }.getOrDefault(
                P010StopBoundaryCleanupResult(
                    sinkDetached = false,
                    finalizationStarted = false
                )
            )
            cleanupSinkDetached = cleanupResult.sinkDetached
            cleanupCompleted = cleanupResult.completed
            if (!cleanupCompleted) {
                poison("P010 sink detach/finalization boundary was not completed")
            }
        } finally {
            if (token != null) {
                val stopBoundaryReleased = synchronized(cameraSubmissionLock) {
                    activeP010StopBoundary.compareAndSet(token, null)
                }
                if (stopBoundaryReleased) {
                    if (cleanupSinkDetached) {
                        // Repeating and full-graph refreshes that reached this exact owner are replayed
                        // once, only after confirmed sink detach and exact token retirement.
                        p010StopDeferredRequestRefresh.drain()
                        runTrailingSessionRefreshIfNeeded()
                    } else {
                        // Never replay toward an unproven live sink. VM emergency cleanup/restore emits a
                        // fresh request from current state after it has detached the sink itself.
                        p010StopDeferredRequestRefresh.discard()
                    }
                }
            }
        }
        return boundaryProven && logicalCaptureStopped && cleanupCompleted && failureReason == null
    }

    private fun awaitP010AnalysisIngressFence(
        handler: Handler?,
        timeoutNanos: Long
    ): Boolean {
        if (handler == null || timeoutNanos <= 0L) return false
        if (Looper.myLooper() === handler.looper) return true
        val completion = CompletableFuture<Boolean>()
        if (!handler.post { completion.complete(true) }) return false
        return try {
            completion.get(timeoutNanos, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun stopP010RecordingStateWithoutRetarget(
        token: P010StopBoundaryRequestToken?,
        handler: Handler?,
        timeoutNanos: Long
    ): Boolean {
        // A missing fence has no generation identity that a delayed callback could revalidate. Apply
        // the fail-closed logical stop synchronously; never enqueue unowned work that could arrive
        // after a replacement recording generation has started.
        if (token == null) {
            stopVideoCapture(retargetRepeating = false)
            return false
        }
        if (handler == null || timeoutNanos <= 0L) {
            P010StopFenceAuthorizationPolicy.executeDelayedStop(
                requestedToken = token,
                activeToken = activeP010StopBoundary::get,
                serializationLock = cameraSubmissionLock
            ) { stopVideoCapture(retargetRepeating = false) }
            return false
        }
        val completion = CompletableFuture<Boolean>()
        val posted = handler.post {
            val ownsFence = P010StopFenceAuthorizationPolicy.executeDelayedStop(
                requestedToken = token,
                activeToken = activeP010StopBoundary::get,
                serializationLock = cameraSubmissionLock
            ) { stopVideoCapture(retargetRepeating = false) }
            completion.complete(ownsFence)
        }
        if (!posted) {
            P010StopFenceAuthorizationPolicy.executeDelayedStop(
                requestedToken = token,
                activeToken = activeP010StopBoundary::get,
                serializationLock = cameraSubmissionLock
            ) { stopVideoCapture(retargetRepeating = false) }
            return false
        }
        return try {
            completion.get(timeoutNanos, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Throwable) {
            false
        }
    }

    /** Returns true when [request] was the private RAW resume marker and was fully consumed here. */
    private fun completeRawResumeBoundary(
        token: RawCaptureResumeBoundaryRequestToken,
        session: CameraCaptureSession,
        result: TotalCaptureResult
    ): Boolean {
        if (!token.completionState.compareAndSet(0, 1)) {
            if (token.completionState.get() == 2) {
                restartPausedRawRepeating(token)
                finishRawResumeBoundary(token, success = false)
            }
            return true
        }
        val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP)
        val boundary = timestampNs?.takeIf { it > 0L }?.let {
            RawCaptureResumeBoundary(
                sensorTimestampNs = it,
                sessionGeneration = token.sessionGeneration,
                frameNumber = result.frameNumber
            )
        }
        val validSession = session === token.session && captureSession === token.session &&
            token.sessionGeneration == sessionGeneration.get()
        val committed = boundary != null && validSession && runCatching {
            token.commitAdmission(boundary)
        }.onFailure { failure ->
            Log.e(TAG, "RAW resume admission commit failed", failure)
        }.getOrDefault(false)
        if (!committed) {
            restartPausedRawRepeating(token)
            finishRawResumeBoundary(token, success = false)
            return true
        }
        token.admissionCommitted.set(true)

        if (!token.repeatingRestartIssued.compareAndSet(false, true) ||
            !pendingRawResumeRestart.compareAndSet(null, token)
        ) {
            rawResumeDeferredRequestRefresh.defer(token)
            finishRawResumeBoundary(token, success = false)
            return true
        }
        startPreviewInternal(
            expectedGeneration = token.sessionGeneration,
            expectedSession = token.session,
            authorizedRawResumeBoundary = token
        )
        // Every successful setRepeatingRequest consumes [pendingRawResumeRestart]. If it remains,
        // startPreview returned through a stale-session/target/error path and the writer must roll back.
        if (pendingRawResumeRestart.compareAndSet(token, null)) {
            rawResumeDeferredRequestRefresh.defer(token)
            finishRawResumeBoundary(token, success = false)
        }
        return true
    }

    private fun failRawResumeBoundary(
        token: RawCaptureResumeBoundaryRequestToken,
        restartRepeating: Boolean
    ) {
        if (token.completionState.compareAndSet(0, 1) || token.completionState.get() == 2) {
            if (restartRepeating) restartPausedRawRepeating(token)
            finishRawResumeBoundary(token, success = false)
        }
    }

    private fun restartPausedRawRepeating(token: RawCaptureResumeBoundaryRequestToken) {
        if (!token.repeatingRestartIssued.compareAndSet(false, true)) return
        val active = activeRawResumeBoundary.get()
        if (active != null && active !== token) {
            rawResumeDeferredRequestRefresh.defer(active)
            return
        }
        val controlHandler = backgroundHandler ?: run {
            Log.e(TAG, "RAW resume repeating restart rejected: camera control handler unavailable")
            return
        }
        val restart = {
            if (captureSession === token.session &&
                token.sessionGeneration == sessionGeneration.get()
            ) {
                startPreviewInternal(
                    expectedGeneration = token.sessionGeneration,
                    expectedSession = token.session,
                    authorizedRawResumeBoundary = token
                )
            }
        }
        if (Looper.myLooper() === controlHandler.looper) {
            restart()
        } else if (!controlHandler.post(restart)) {
            Log.e(TAG, "RAW resume repeating restart rejected by camera control thread")
        }
    }

    private fun finishRawResumeBoundary(
        token: RawCaptureResumeBoundaryRequestToken,
        success: Boolean
    ) {
        if (!success && token.admissionCommitted.compareAndSet(true, false)) {
            runCatching(token.rollbackAdmission)
                .onFailure { Log.e(TAG, "RAW resume admission rollback failed", it) }
        }
        pendingRawResumeRestart.compareAndSet(token, null)
        activeRawResumeBoundary.compareAndSet(token, null)
        token.completionState.set(3)
        token.completion.complete(success)
        rawResumeDeferredRequestRefresh.drain()
    }

    private fun acknowledgeRawResumeRestart(
        session: CameraCaptureSession,
        generation: Long,
        authorizedRawResumeBoundary: RawCaptureResumeBoundaryRequestToken?
    ) {
        val token = authorizedRawResumeBoundary ?: return
        if (activeRawResumeBoundary.get() !== token || pendingRawResumeRestart.get() !== token) return
        if (token.session !== session || token.sessionGeneration != generation) return
        if (pendingRawResumeRestart.compareAndSet(token, null)) {
            finishRawResumeBoundary(token, success = true)
        }
    }

    /**
     * Publishes exact Camera2 identity to RAW/NDR pairing before observational result consumers.
     * A faulty optional consumer must not stop the repeating callback or delay later consumers, while
     * VM-fatal failures retain their process-level semantics.
     */
    private fun publishRawCaptureResultFastLane(
        result: TotalCaptureResult,
        captureSessionGeneration: Long
    ) {
        try {
            onRawCaptureResultWithSessionGeneration?.invoke(result, captureSessionGeneration)
        } catch (throwable: Throwable) {
            if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
            Log.e(TAG, "RAW capture-result fast-lane consumer failed independently", throwable)
        }
    }

    /**
     * Publishes the exact Camera2 result to NIV99 before the matching ImageReader callback can time
     * out its bounded metadata join. RAW remains the first fast-lane consumer, and an optional NIV99
     * failure cannot suppress Camera2 health, autofocus, still-capture, or diagnostic consumers.
     */
    private fun publishNiv99CaptureResultFastLane(
        result: TotalCaptureResult,
        captureSessionGeneration: Long
    ) {
        try {
            onNiv99CaptureResultWithSessionGeneration?.invoke(result, captureSessionGeneration)
        } catch (throwable: Throwable) {
            if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
            Log.e(TAG, "NIV99 capture-result fast-lane consumer failed independently", throwable)
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            val callbackStartedNs = SystemClock.elapsedRealtimeNanos()
            // A hot-swap can deliver the outgoing session's final callback after the replacement is
            // already authoritative. Never let that metadata join a RAW image from the new session.
            val rawResumeBoundary = r.tag as? RawCaptureResumeBoundaryRequestToken
            val p010StopBoundary = r.tag as? P010StopBoundaryRequestToken
            val binding = captureResultSessionBinding.get()
            if (binding?.session !== s || binding.generation != sessionGeneration.get()) {
                rawResumeBoundary?.let { failRawResumeBoundary(it, restartRepeating = false) }
                p010StopBoundary?.state?.fail(
                    "Stale Camera2 session delivered the P010 stop marker result"
                )
                return
            }
            if (rawResumeBoundary != null) {
                completeRawResumeBoundary(rawResumeBoundary, s, result)
            }
            if (p010StopBoundary != null) {
                val markerSequenceId = p010StopBoundary.state.markerSequenceId()
                p010StopBoundary.state.onMarkerCaptureResult(
                    session = s,
                    sessionGeneration = binding.generation,
                    sequenceId = markerSequenceId,
                    sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                )
            }
            val captureSessionGeneration = binding.generation
            val boundaryCompletedNs = SystemClock.elapsedRealtimeNanos()
            // Boundary markers above must commit first. RAW/NDR pairing remains first, followed by
            // NIV99's exact-frame metadata publication. Both run before health, AF, still-capture,
            // and diagnostic observers so an Image-first startup burst cannot exhaust its join.
            publishRawCaptureResultFastLane(result, captureSessionGeneration)
            val rawFastLaneCompletedNs = SystemClock.elapsedRealtimeNanos()
            publishNiv99CaptureResultFastLane(result, captureSessionGeneration)
            val niv99FastLaneCompletedNs = SystemClock.elapsedRealtimeNanos()
            val sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
            synchronized(cameraSubmissionLock) {
                p010ProducerTailTracker.get()?.onFrameCompleted(
                    s, captureSessionGeneration, result.sequenceId, result.frameNumber, sensorTimestampNs
                )
            }
            activeRepeatingSequence.get()?.let { repeating ->
                if (activeRepeatingSequence.get() === repeating &&
                    repeating.identity.session === s &&
                    repeating.identity.sessionGeneration == captureSessionGeneration &&
                    repeating.identity.sessionGeneration == sessionGeneration.get() &&
                    repeating.request === r &&
                    result.frameNumber >= 0L &&
                    sensorTimestampNs > 0L
                ) {
                    repeating.firstFrameProof.complete(
                        P010RepeatingFrameProof(
                            frameNumber = result.frameNumber,
                            sensorTimestampNs = sensorTimestampNs,
                            receivedAtElapsedRealtimeNs = callbackStartedNs
                        )
                    )
                }
            }
            val repeatingProofCompletedNs = SystemClock.elapsedRealtimeNanos()
            previewHealthMonitor.trackCaptureResult()
            val stillToken = r.tag as? StillCaptureRequestToken
            // Observe the entire active recording, not only the short lineage-certificate window.
            // Explicit stop/resume/still boundary requests are control frames rather than media
            // cadence and are therefore excluded from the recording lattice.
            val recordCadenceFrame = rawResumeBoundary == null &&
                p010StopBoundary == null &&
                stillToken == null
            recordFrameDeliveryLedger.onFrameCompleted(
                recordingActive = recordingActive && recordCadenceFrame,
                frameNumber = result.frameNumber,
                sensorTimestampNs = sensorTimestampNs
            )
            if (stillToken != null) {
                val qualityReadback = StillCaptureQualityReadbackPolicy.verify(
                    requested = snapshotRequestedStillCaptureQuality(r),
                    reported = snapshotReportedStillCaptureQuality(result)
                )
                lastStillCaptureQualityReadback = qualityReadback
                Log.i(
                    TAG,
                    "Photo capture Camera2 result completed captureId=${stillToken.captureId} " +
                        "phase=${stillToken.phase} index=${stillToken.frameIndex}/${stillToken.frameCount} " +
                        "frame=${result.frameNumber} " +
                        "sensorTimestamp=${result.get(CaptureResult.SENSOR_TIMESTAMP) ?: -1L} " +
                        "qualityReadback={${qualityReadback.toEvidenceLine()}}"
                )
                if (qualityReadback.verdict == StillCaptureQualityVerdict.NOT_PROVEN) {
                    emitCameraPipelineWarning(
                        "Still-capture quality request was contradicted by Camera2 readback: " +
                            qualityReadback.mismatchedKeys.joinToString(",")
                    )
                }
            }
            if (r.tag === tapAutoFocusRequestTag && tapAfScanActive) {
                tapAfTriggerFrameNumber = result.frameNumber
            }
            maybeCompleteTapAfScan(result)
            val observersCompletedNs = SystemClock.elapsedRealtimeNanos()
            processResult(result, captureSessionGeneration)
            val callbackCompletedNs = SystemClock.elapsedRealtimeNanos()
            maybeLogCaptureCallbackTiming(
                result = result,
                callbackStartedNs = callbackStartedNs,
                boundaryCompletedNs = boundaryCompletedNs,
                rawFastLaneCompletedNs = rawFastLaneCompletedNs,
                niv99FastLaneCompletedNs = niv99FastLaneCompletedNs,
                repeatingProofCompletedNs = repeatingProofCompletedNs,
                observersCompletedNs = observersCompletedNs,
                callbackCompletedNs = callbackCompletedNs
            )
        }

        // s1-f05: a non-flushed capture failure on the record path is a real dropped frame; a
        // FLUSHED failure is the intended consequence of a reconfigure (flush/abort/close). The
        // ledger classifies steady-state vs reconfigure-window so a drop is never silent.
        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
            // The outgoing hot-swap session can report FLUSHED after the replacement became
            // authoritative. Do not charge that stale callback to the new session's frame ledger or
            // let it disturb tap/still state owned by the replacement generation.
            val rawResumeBoundary = r.tag as? RawCaptureResumeBoundaryRequestToken
            val p010StopBoundary = r.tag as? P010StopBoundaryRequestToken
            val binding = captureResultSessionBinding.get()
            if (binding?.session !== s || binding.generation != sessionGeneration.get()) {
                rawResumeBoundary?.let { failRawResumeBoundary(it, restartRepeating = false) }
                p010StopBoundary?.state?.fail(
                    "Stale Camera2 session failed the P010 stop marker"
                )
                return
            }
            if (rawResumeBoundary != null) {
                failRawResumeBoundary(rawResumeBoundary, restartRepeating = true)
                return
            }
            if (p010StopBoundary != null) {
                p010StopBoundary.state.fail(
                    "Camera2 failed the P010 stop marker frame=${failure.frameNumber}"
                )
                return
            }
            p010ProducerTailTracker.get()?.failSequenceWhileFrozen(
                s, binding.generation, failure.sequenceId,
                "Camera2 failed a captured P010 producer tail frame=${failure.frameNumber}"
            )
            recordFrameDeliveryLedger.onCaptureFailed(
                recordingActive = recordingActive,
                flushed = failure.reason == CaptureFailure.REASON_FLUSHED
            )
            val stillToken = r.tag as? StillCaptureRequestToken
            if (stillToken != null && s === captureSession) {
                val reason = if (failure.reason == CaptureFailure.REASON_FLUSHED) {
                    "Photo capture was flushed before its still buffers completed"
                } else {
                    "Photo capture failed in the camera HAL"
                }
                failStillCaptureRequest(
                    token = stillToken,
                    message = reason,
                    frameNumber = failure.frameNumber
                )
            }
            if (r.tag === tapAutoFocusRequestTag) {
                completeTapAfScan(reason = "capture-failed", restoreContinuousAf = true)
            }
        }

        // s1-f05: a lost target buffer is a dropped frame; bounded while reconfiguring, unintended otherwise.
        override fun onCaptureBufferLost(
            s: CameraCaptureSession,
            r: CaptureRequest,
            target: Surface,
            frameNumber: Long
        ) {
            val binding = captureResultSessionBinding.get()
            if (binding?.session !== s || binding.generation != sessionGeneration.get()) return
            if (r.tag is RawCaptureResumeBoundaryRequestToken) return
            val p010StopBoundary = r.tag as? P010StopBoundaryRequestToken
            if (p010StopBoundary != null && target === p010StopBoundary.p010Surface) {
                p010StopBoundary.state.fail(
                    "Camera2 lost the exact P010 stop marker buffer frame=$frameNumber"
                )
                return
            }
            if (target === p010ReaderSurfaceOrNull(yuvReader)) {
                p010ProducerTailTracker.get()?.failWhileFrozen(s, binding.generation,
                    "Camera2 lost a captured P010 producer tail buffer frame=$frameNumber")
            }
            recordFrameDeliveryLedger.onBufferLost(recordingActive)
            val stillToken = r.tag as? StillCaptureRequestToken
            val lostStillOutput = stillToken != null && s === captureSession && (
                (stillToken.expectsJpeg && target == jpegReader?.surface) ||
                    (stillToken.expectsRaw && target == rawReader?.surface)
                )
            if (lostStillOutput) {
                failStillCaptureRequest(
                    token = requireNotNull(stillToken),
                    message = "Photo buffer was lost before it could be saved",
                    frameNumber = frameNumber
                )
            }
        }

        override fun onCaptureSequenceAborted(s: CameraCaptureSession, sequenceId: Int) {
            synchronized(cameraSubmissionLock) {
                p010ProducerTailTracker.get()?.onSequenceAborted(s, sessionGeneration.get(), sequenceId)
            }
            activeP010StopBoundary.get()?.let { token ->
                token.state.onSequenceAborted(
                    session = s,
                    sessionGeneration = token.sessionGeneration,
                    sequenceId = sequenceId
                )
            }
            activeRepeatingSequence.get()?.let { repeating ->
                if (repeating.identity.session === s && repeating.identity.sequenceId == sequenceId) {
                    repeating.firstFrameProof.completeExceptionally(
                        IllegalStateException(
                            "Camera2 repeating sequence aborted before a current-generation frame was proven"
                        )
                    )
                    activeRepeatingSequence.compareAndSet(repeating, null)
                }
            }
            val token = activeRawResumeBoundary.get() ?: return
            if (token.session === s && token.markerSequenceId.get() == sequenceId) {
                failRawResumeBoundary(token, restartRepeating = true)
            }
        }

        override fun onCaptureSequenceCompleted(
            s: CameraCaptureSession,
            sequenceId: Int,
            frameNumber: Long
        ) {
            synchronized(cameraSubmissionLock) {
                p010ProducerTailTracker.get()?.onSequenceCompleted(s, sessionGeneration.get(), sequenceId, frameNumber)
            }
            activeP010StopBoundary.get()?.let { token ->
                // New stops consume the exact already-captured tail. Keep the old explicit marker
                // protocol available for legacy callers, but never append it to a production clip.
                if (token.capturedProducerTail != null) return@let
                val submitMarker = token.state.onRepeatingSequenceCompleted(
                    session = s,
                    sessionGeneration = token.sessionGeneration,
                    sequenceId = sequenceId
                )
                if (submitMarker) {
                    submitP010StopMarkerAfterRepeatingCompletion(token)
                } else {
                    token.state.onMarkerSequenceCompleted(
                        session = s,
                        sessionGeneration = token.sessionGeneration,
                        sequenceId = sequenceId
                    )
                }
            }
            activeRepeatingSequence.get()?.let { repeating ->
                if (repeating.identity.session === s && repeating.identity.sequenceId == sequenceId) {
                    repeating.firstFrameProof.completeExceptionally(
                        IllegalStateException(
                            "Camera2 repeating sequence completed before a current-generation frame was proven"
                        )
                    )
                    activeRepeatingSequence.compareAndSet(repeating, null)
                }
            }
        }
    }

    /**
     * Allocation-free on the normal path. Slow callbacks emit one rate-limited stage breakdown so
     * physical-device evidence can distinguish NIV99 metadata publication from unrelated Camera2
     * observers without flooding logcat and creating a new source of callback jitter.
     */
    private fun maybeLogCaptureCallbackTiming(
        result: TotalCaptureResult,
        callbackStartedNs: Long,
        boundaryCompletedNs: Long,
        rawFastLaneCompletedNs: Long,
        niv99FastLaneCompletedNs: Long,
        repeatingProofCompletedNs: Long,
        observersCompletedNs: Long,
        callbackCompletedNs: Long
    ) {
        val totalNs = (callbackCompletedNs - callbackStartedNs).coerceAtLeast(0L)
        val niv99Ns = (niv99FastLaneCompletedNs - rawFastLaneCompletedNs).coerceAtLeast(0L)
        if (totalNs < CAPTURE_CALLBACK_SLOW_NS && niv99Ns < NIV99_FAST_LANE_SLOW_NS) return
        val previousLogNs = lastCaptureCallbackTimingLogNs.get()
        if (callbackCompletedNs - previousLogNs < CAPTURE_CALLBACK_TIMING_LOG_INTERVAL_NS ||
            !lastCaptureCallbackTimingLogNs.compareAndSet(previousLogNs, callbackCompletedNs)
        ) return
        Log.w(
            TAG,
            "NIV99_CAPTURE_CALLBACK_TIMING frame=${result.frameNumber} " +
                "thread=${Thread.currentThread().name} " +
                "sensorTimestampNs=${result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L} " +
                "totalMs=${totalNs / 1_000_000.0} " +
                "boundaryMs=${(boundaryCompletedNs - callbackStartedNs) / 1_000_000.0} " +
                "rawFastLaneMs=${(rawFastLaneCompletedNs - boundaryCompletedNs) / 1_000_000.0} " +
                "niv99FastLaneMs=${niv99Ns / 1_000_000.0} " +
                "proofMs=${(repeatingProofCompletedNs - niv99FastLaneCompletedNs) / 1_000_000.0} " +
                "observersMs=${(observersCompletedNs - repeatingProofCompletedNs) / 1_000_000.0} " +
                "processResultMs=${(callbackCompletedNs - observersCompletedNs) / 1_000_000.0}"
        )
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

    private fun enqueueCaptureResultDiagnostics(
        result: CaptureResult,
        captureSessionGeneration: Long,
        liveResult: LiveResult,
        colorCorrectionGains: RggbChannelVector?,
        awbConfidence: Float
    ) {
        val requestedIspBypassModes = lastRequestedIspBypassModes
        pendingCaptureResultDiagnostics.set(
            DeferredCaptureResultDiagnostics(
                result = result,
                captureSessionGeneration = captureSessionGeneration,
                liveResult = liveResult,
                colorCorrectionGains = colorCorrectionGains,
                awbConfidence = awbConfidence,
                hdrMode = hdrDynamicRangeMode,
                sensorLinearNdrEnabled = sensorLinearNdrCaptureEnabled,
                recording = recordingActive,
                strictHighEnd = strictHighEndEnabled(),
                requestedIspBypassModes = requestedIspBypassModes
            )
        )
        scheduleCaptureResultDiagnostics()
    }

    private fun scheduleCaptureResultDiagnostics() {
        if (!captureResultDiagnosticsScheduled.compareAndSet(false, true)) return
        val posted = captureTelemetryHandler?.post(captureResultDiagnosticsRunnable) == true
        if (!posted) {
            captureResultDiagnosticsScheduled.set(false)
            pendingCaptureResultDiagnostics.set(null)
        }
    }

    /** Latest-only physical/color telemetry and diagnostics on a dedicated secondary worker. */
    private fun processDeferredCaptureResultDiagnostics(work: DeferredCaptureResultDiagnostics) {
        val result = work.result
        // Keep physical telemetry and colour-state work off CameraResultIngress. The single-slot
        // scheduler retains only the newest immutable CaptureResult, so overload cannot accumulate
        // an unbounded history or delay RAW/NIV99 identity publication.
        val measuredColorSnapshot = MeasuredColorCorrectionSnapshot(
            provenance = WhiteBalanceMeasurementProvenance(
                cameraId = cameraDevice?.id ?: currentCameraId,
                sessionGeneration = work.captureSessionGeneration,
                frameNumber = result.frameNumber,
                sensorTimestampNs = work.liveResult.timestampNs.takeIf { it > 0L }
            ),
            gains = work.colorCorrectionGains,
            transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM),
            awbConfidence = work.awbConfidence
        )
        val previousColorSnapshot = latestMeasuredColorSnapshot
        if (previousColorSnapshot == null ||
            measuredColorSnapshot.provenance.isNewerThan(previousColorSnapshot.provenance)
        ) {
            latestMeasuredColorSnapshot = measuredColorSnapshot
        }
        val resultNoiseProfile = result.get(CaptureResult.SENSOR_NOISE_PROFILE)
        latestSensorNoiseProfilePairs = resultNoiseProfile?.size ?: 0
        CameraSensorNoiseModelPolicy.fromCaptureResult(result)?.let { model ->
            sensorNoiseModel = model
        }
        physicalImagingTraits = CameraPhysicalImagingTraits.updateFromCaptureResult(
            physicalImagingTraits,
            result,
            work.colorCorrectionGains
        )
        work.colorCorrectionGains?.let { gains ->
            val budgetUv = activeColorDriftBudgetUv *
                VideoColorStabilityPolicy.lowLightBudgetScale(work.liveResult.iso)
            val drift = colorDriftGovernor.accept(
                red = gains.red,
                greenEven = gains.greenEven,
                greenOdd = gains.greenOdd,
                blue = gains.blue,
                maxFrameToFrameColorDriftUv = budgetUv,
                confidence = work.awbConfidence,
                sensorTimestampNs = work.liveResult.timestampNs.takeIf { it > 0L }
            )
            if (drift != null && latestMeasuredColorSnapshot === measuredColorSnapshot) {
                latestMeasuredColorSnapshot = measuredColorSnapshot.copy(
                    stabilizationAccepted = true
                )
            }
            drift?.let { maybeLogColorDrift(it, budgetUv) }
        }
        latestOisSampleCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            result.get(CaptureResult.STATISTICS_OIS_SAMPLES)?.size ?: 0
        } else {
            0
        }
        val bypassVerificationPending =
            work.requestedIspBypassModes?.isBypassRequest == true && !loggedIspBypassVerification
        val sampledDiagnosticFrame = result.frameNumber % 15L == 0L
        if (!bypassVerificationPending && !sampledDiagnosticFrame) return
        maybeLogWhiteBalanceResult(result, work.captureSessionGeneration)
        verifyIspBypassReadback(result, work.requestedIspBypassModes)
        maybeLogNdrExposureReadback(
            result = result,
            hdrMode = work.hdrMode,
            sensorLinearNdrEnabled = work.sensorLinearNdrEnabled
        )
        if (result.frameNumber % 30L == 0L) {
            Log.i(
                TAG,
                "AFPROBE afState=${result.get(CaptureResult.CONTROL_AF_STATE)} " +
                    "afMode=${result.get(CaptureResult.CONTROL_AF_MODE)} " +
                    "recording=${work.recording} afUnlock=${AfRecordingUnlockFlag.enabled}"
            )
        }
        if (result.frameNumber % 60L == 0L) {
            result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { map ->
                val columns = map.columnCount
                val rows = map.rowCount
                if (columns > 0 && rows > 0) {
                    val centerColumn = columns / 2
                    val centerRow = rows / 2
                    Log.i(
                        TAG,
                        "LSCPROBE grid=${columns}x$rows " +
                            "center R=${"%.3f".format(map.getGainFactor(0, centerColumn, centerRow))} " +
                            "G=${"%.3f".format(map.getGainFactor(1, centerColumn, centerRow))} " +
                            "B=${"%.3f".format(map.getGainFactor(3, centerColumn, centerRow))} | " +
                            "corner R=${"%.3f".format(map.getGainFactor(0, 0, 0))} " +
                            "G=${"%.3f".format(map.getGainFactor(1, 0, 0))} " +
                            "B=${"%.3f".format(map.getGainFactor(3, 0, 0))}"
                    )
                }
            }
        }
        if (work.hdrMode != HdrDynamicRangeMode.SDR) {
            result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { gains ->
                val greenGain = ((gains.greenEven + gains.greenOdd) * 0.5f).coerceAtLeast(1e-4f)
                val redOverGreen = gains.red / greenGain
                val blueOverGreen = gains.blue / greenGain
                val ccmPresent = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) != null
                val signature = "%.2f|%.2f|%b".format(redOverGreen, blueOverGreen, ccmPresent)
                if (signature != lastLoggedNdrColorProbeSignature) {
                    lastLoggedNdrColorProbeSignature = signature
                    Log.i(
                        TAG,
                        "NDRCOLOR measured AWB gains R=${"%.3f".format(gains.red)} " +
                            "Gr=${"%.3f".format(gains.greenEven)} " +
                            "Gb=${"%.3f".format(gains.greenOdd)} " +
                            "B=${"%.3f".format(gains.blue)} " +
                            "R/G=${"%.3f".format(redOverGreen)} " +
                            "B/G=${"%.3f".format(blueOverGreen)} ccmPresent=$ccmPresent " +
                            "mode=${work.hdrMode}"
                    )
                }
            }
        }
        if (work.strictHighEnd) {
            maybeLogPhysicalTelemetryForShaderProfiles(
                rollingShutterSkewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
                noiseProfilePairs = result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.size ?: 0,
                oisSampleCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    result.get(CaptureResult.STATISTICS_OIS_SAMPLES)?.size ?: 0
                } else {
                    0
                }
            )
        }
    }

    private fun processResult(r: CaptureResult, captureSessionGeneration: Long) {
        // Session generation advances before the replacement graph is configured. Revalidate at the
        // actual metadata publication boundary so the outgoing session cannot mutate colour/WB state
        // or join an NDR RAW image during that handoff window.
        if (captureSessionGeneration != sessionGeneration.get()) return
        val colorCorrectionGains = r.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val awbConfidence = CameraAwbConfidencePolicy.resolve(
            r.get(CaptureResult.CONTROL_AWB_STATE),
            colorCorrectionGains
        )
        val tr = r as? TotalCaptureResult
        if (tr != null) {
            var fatalFailure: Throwable? = null
            try {
                onCaptureResult?.invoke(tr)
            } catch (throwable: Throwable) {
                if (throwable is VirtualMachineError || throwable is ThreadDeath) {
                    fatalFailure = throwable
                } else {
                    Log.e(TAG, "Capture-result consumer failed independently", throwable)
                }
            }
            try {
                // Sensor identity, direct-surface bookkeeping, and lineage share this authoritative
                // session-bound callback; one consumer failure must never suppress the callback chain.
                onCaptureResultWithSessionGeneration?.invoke(tr, captureSessionGeneration)
            } catch (throwable: Throwable) {
                if (throwable is VirtualMachineError || throwable is ThreadDeath) {
                    if (fatalFailure == null) fatalFailure = throwable
                } else {
                    Log.e(TAG, "Session-aware capture-result consumer failed independently", throwable)
                }
            }
            fatalFailure?.let { throw it }
        }
        // Debug/verification probes use a bounded latest-result slot on CaptureTelemetry. They can
        // neither queue an unbounded history nor delay Camera2 identity/RAW/live-state publication.
        val liveResult = LiveResult(
            iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
            exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            afState = r.get(CaptureResult.CONTROL_AF_STATE) ?: 0,
            aeState = r.get(CaptureResult.CONTROL_AE_STATE) ?: 0,
            awbState = r.get(CaptureResult.CONTROL_AWB_STATE) ?: 0,
            focusDist = r.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
            timestampNs = r.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
        )
        // Preserve every current-generation OIS result in the lock-free motion ring. This must stay
        // on the non-coalescing capture path: the diagnostics worker intentionally keeps only its
        // newest item, which is correct for probes but would silently discard stabilization samples
        // under load. The generation check above rejects outgoing-session results before publication.
        motionTelemetry = CameraMotionTelemetry.fromCaptureResult(motionTelemetry, r)
        motionTelemetryRingBuffer.push(motionTelemetry)
        enqueueCaptureResultDiagnostics(
            result = r,
            captureSessionGeneration = captureSessionGeneration,
            liveResult = liveResult,
            colorCorrectionGains = colorCorrectionGains,
            awbConfidence = awbConfidence
        )
        previousCaptureTimestampNs = latestCaptureTimestampNs
        latestCaptureTimestampNs = liveResult.timestampNs
        if (liveResult.timestampNs > 0L) {
            val callbackElapsedNs = SystemClock.elapsedRealtimeNanos()
            val callbackGapNs = callbackElapsedNs - lastPreviewFrameCallbackElapsedNs
            if (lastPreviewFrameCallbackElapsedNs > 0L && callbackGapNs >= 80_000_000L) {
                NeoLogger.i(
                    "CameraController",
                    "PreviewFrameGap callbackGapMs=${callbackGapNs / 1_000_000.0} " +
                        "sensorGapMs=${(liveResult.timestampNs - previousCaptureTimestampNs) / 1_000_000.0} " +
                        "sessionRefreshPending=${sessionRefreshPending.get()}"
                )
            }
            lastPreviewFrameCallbackElapsedNs = callbackElapsedNs
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
        // A Camera2 repeating request is immutable. A cinematic pull therefore needs a fresh request
        // for every time-varying lens-distance step; the old trigger rebuilt once and then repeated one
        // frozen distance forever. Drive the next request from the authoritative capture-result cadence
        // (never a free-running timer), so request pressure cannot exceed delivered camera frames and
        // time-based easing still catches up cleanly if the HAL skips a callback.
        maybeAdvanceCinematicFocusPullRequest()
    }

    /**
     * Debug-build evidence for the exact request/result pair carried by one Camera2 frame. This is
     * deliberately change- and time-throttled, and reads the request attached to TotalCaptureResult
     * instead of comparing against mutable UI state that may already belong to a newer frame.
     */
    @SuppressLint("NewApi")
    private fun maybeLogWhiteBalanceResult(
        result: CaptureResult,
        captureSessionGeneration: Long
    ) {
        if (!BuildConfig.DEBUG || result.frameNumber % 15L != 0L) return
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (nowElapsedMs - lastWhiteBalanceResultLogElapsedMs < 1_000L) return
        lastWhiteBalanceResultLogElapsedMs = nowElapsedMs

        val total = result as? TotalCaptureResult ?: return
        val request = total.request
        val requestCct = if (Build.VERSION.SDK_INT >= 36) {
            request.get(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE)
        } else null
        val requestTint = if (Build.VERSION.SDK_INT >= 36) {
            request.get(CaptureRequest.COLOR_CORRECTION_COLOR_TINT)
        } else null
        val resultCct = if (Build.VERSION.SDK_INT >= 36) {
            result.get(CaptureResult.COLOR_CORRECTION_COLOR_TEMPERATURE)
        } else null
        val resultTint = if (Build.VERSION.SDK_INT >= 36) {
            result.get(CaptureResult.COLOR_CORRECTION_COLOR_TINT)
        } else null

        val requestGains = request.get(CaptureRequest.COLOR_CORRECTION_GAINS).wbDiagnosticString()
        val requestCcm = request.get(CaptureRequest.COLOR_CORRECTION_TRANSFORM).wbDiagnosticString()
        val resultGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS).wbDiagnosticString()
        val resultCcm = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM).wbDiagnosticString()
        val signature = listOf(
            cameraDevice?.id ?: currentCameraId,
            captureSessionGeneration,
            request.get(CaptureRequest.CONTROL_AWB_MODE),
            request.get(CaptureRequest.CONTROL_AWB_LOCK),
            request.get(CaptureRequest.COLOR_CORRECTION_MODE),
            requestCct,
            requestTint,
            requestGains,
            requestCcm,
            result.get(CaptureResult.CONTROL_AWB_MODE),
            result.get(CaptureResult.CONTROL_AWB_STATE),
            result.get(CaptureResult.CONTROL_AWB_LOCK),
            result.get(CaptureResult.COLOR_CORRECTION_MODE),
            resultCct,
            resultTint,
            resultGains,
            resultCcm
        ).joinToString(separator = "|")
        if (signature == lastLoggedWhiteBalanceResultSignature) return
        lastLoggedWhiteBalanceResultSignature = signature

        Log.i(
            TAG,
            "WB_RESULT camera=${cameraDevice?.id ?: currentCameraId} " +
                "session=$captureSessionGeneration frame=${result.frameNumber} " +
                "sensorTs=${result.get(CaptureResult.SENSOR_TIMESTAMP)} " +
                "selectedKelvin=$manWbK selectedTint=$manWbTint " +
                "request{awbMode=${request.get(CaptureRequest.CONTROL_AWB_MODE)} " +
                "lock=${request.get(CaptureRequest.CONTROL_AWB_LOCK)} " +
                "colorMode=${request.get(CaptureRequest.COLOR_CORRECTION_MODE)} " +
                "cct=$requestCct tint=$requestTint gains=$requestGains ccm=$requestCcm} " +
                "result{awbMode=${result.get(CaptureResult.CONTROL_AWB_MODE)} " +
                "state=${result.get(CaptureResult.CONTROL_AWB_STATE)} " +
                "lock=${result.get(CaptureResult.CONTROL_AWB_LOCK)} " +
                "colorMode=${result.get(CaptureResult.COLOR_CORRECTION_MODE)} " +
                "cct=$resultCct tint=$resultTint gains=$resultGains ccm=$resultCcm}"
        )
    }

    private fun RggbChannelVector?.wbDiagnosticString(): String = this?.let {
        String.format(
            Locale.US,
            "[%.4f,%.4f,%.4f,%.4f]",
            it.red,
            it.greenEven,
            it.greenOdd,
            it.blue
        )
    } ?: "null"

    private fun ColorSpaceTransform?.wbDiagnosticString(): String = this?.let { transform ->
        (0 until 9).joinToString(prefix = "[", postfix = "]", separator = ",") { index ->
            val row = index / 3
            val column = index % 3
            String.format(Locale.US, "%.5f", transform.getElement(column, row).toFloat())
        }
    } ?: "null"

    /** Verify that the HAL honored the active adaptive AE-compensation request; telemetry only. */
    private fun maybeLogNdrExposureReadback(
        result: CaptureResult,
        hdrMode: HdrDynamicRangeMode,
        sensorLinearNdrEnabled: Boolean
    ) {
        val sdr = hdrMode == HdrDynamicRangeMode.SDR && !sensorLinearNdrEnabled
        val requested = if (sdr) {
            lastRequestedSdrExposureCompensationIndex
        } else {
            lastRequestedNdrExposureCompensationIndex
        } ?: return
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (nowElapsedMs - lastHdrExposureReadbackElapsedMs < 1_000L) return
        lastHdrExposureReadbackElapsedMs = nowElapsedMs
        val reported = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
        val label = if (sdr) "SDREXPOSURE" else "NDREXPOSURE"
        val analysisFresh = if (sdr) {
            sdrExposureMetricsFresh(nowElapsedMs)
        } else if (sensorLinearNdrEnabled) {
            sensorLinearNdrExposureMetricsAgeMs(nowElapsedMs) <=
                SensorLinearNdrExposurePolicy.LIVE_METRICS_MAX_AGE_MS
        } else {
            hdrExposureMetricsFresh(nowElapsedMs)
        }
        val message =
            "$label readback requestedIndex=$requested reportedIndex=${reported ?: "unset"} " +
                "iso=${result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0} " +
                "exposureNs=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L} " +
                "analysisFresh=$analysisFresh"
        if (reported != null && reported != requested) {
            Log.w(TAG, "$message honored=false")
        } else {
            Log.i(TAG, "$message honored=${reported == requested}")
        }
    }

    private fun hdrExposureMetricsFresh(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean =
        hdrExposureMetricsAgeMs(nowElapsedMs) <=
            NdrAdaptiveHighlightExposurePolicy.LIVE_METRICS_MAX_AGE_MS

    private fun sdrExposureMetricsFresh(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean =
        hdrExposureMetricsAgeMs(nowElapsedMs) <=
            SdrAdaptiveHighlightExposurePolicy.LIVE_METRICS_MAX_AGE_MS

    private fun hdrExposureMetricsAgeMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        val measuredAt = lastHdrExposureMetricsElapsedMs
        if (measuredAt <= 0L) return Long.MAX_VALUE
        return (nowElapsedMs - measuredAt).coerceAtLeast(0L)
    }

    private fun sensorLinearNdrExposureMetricsAgeMs(
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): Long {
        val measuredAt = lastSensorLinearNdrExposureMetricsElapsedMs
        if (measuredAt <= 0L) return Long.MAX_VALUE
        return (nowElapsedMs - measuredAt).coerceAtLeast(0L)
    }

    /**
     * Runs only when a non-zero NDR correction loses its analysis feed. Rebuilds the repeating request
     * only when the camera's quantized AE index will change, so recovery cannot create per-frame churn.
     */
    private fun releaseStaleHdrExposureCorrection() {
        if (!ndrExposureGovernorEnabled || !NdrHighlightProtectFlag.enabled) return
        val rawSensorGovernor = sensorLinearNdrCaptureEnabled
        if (!rawSensorGovernor && hdrDynamicRangeMode == HdrDynamicRangeMode.SDR) return
        val ageMs = if (rawSensorGovernor) {
            sensorLinearNdrExposureMetricsAgeMs()
        } else {
            hdrExposureMetricsAgeMs()
        }
        val releasedStops = if (rawSensorGovernor) {
            SensorLinearNdrExposurePolicy.resolveAppliedStops(
                measuredStops = sensorLinearNdrExposureStops,
                metricsAgeMs = ageMs
            )
        } else {
            NdrAdaptiveHighlightExposurePolicy.resolveAppliedExtraStops(
                measuredExtraStops = hdrAdaptiveHighlightExtraStops,
                metricsAgeMs = ageMs
            )
        }
        val step = if (evCompensationStepStops > 0f) evCompensationStepStops else 1f
        val releasedIndex = ExposureCompensationHysteresisPolicy.resolveIndex(
            currentIndex = lastRequestedNdrExposureCompensationIndex,
            baseEvIndex = evIndex,
            biasStops = if (rawSensorGovernor) {
                releasedStops
            } else {
                HdrHighlightProtectExposurePolicy.DEFAULT_HIGHLIGHT_PROTECT_STOPS + releasedStops
            },
            evStepStops = step,
            evLowerIndex = evRange.lower,
            evUpperIndex = evRange.upper
        )
        if (releasedIndex != lastRequestedNdrExposureCompensationIndex) {
            updatePreviewFromAutonomousControlFeedback()
        }

        val releaseEndMs = if (rawSensorGovernor) {
            SensorLinearNdrExposurePolicy.LIVE_METRICS_MAX_AGE_MS +
                SensorLinearNdrExposurePolicy.STALE_METRICS_RELEASE_MS
        } else {
            NdrAdaptiveHighlightExposurePolicy.LIVE_METRICS_MAX_AGE_MS +
                NdrAdaptiveHighlightExposurePolicy.STALE_METRICS_RELEASE_MS
        }
        if (ageMs < releaseEndMs && releasedStops != 0f) {
            backgroundHandler?.postDelayed(
                hdrExposureStaleReleaseRunnable,
                if (rawSensorGovernor) {
                    SensorLinearNdrExposurePolicy.STALE_METRICS_RELEASE_TICK_MS
                } else {
                    NdrAdaptiveHighlightExposurePolicy.STALE_METRICS_RELEASE_TICK_MS
                }
            )
        }
    }

    private fun scheduleHdrExposureStaleRelease(smoothedStops: Float) {
        val handler = backgroundHandler ?: return
        handler.removeCallbacks(hdrExposureStaleReleaseRunnable)
        if (smoothedStops != 0f && ndrExposureGovernorEnabled) {
            handler.postDelayed(
                hdrExposureStaleReleaseRunnable,
                if (sensorLinearNdrCaptureEnabled) {
                    SensorLinearNdrExposurePolicy.LIVE_METRICS_MAX_AGE_MS + 1L
                } else {
                    NdrAdaptiveHighlightExposurePolicy.LIVE_METRICS_MAX_AGE_MS + 1L
                }
            )
        }
    }

    private fun sdrAdaptiveHighlightExposureEligible(): Boolean =
        SdrAdaptiveHighlightFlag.enabled &&
            hdrDynamicRangeMode == HdrDynamicRangeMode.SDR &&
            previewAnalysisRequested &&
            !previewRawLookEnabled &&
            !rawVideoCaptureEnabled

    /** Release a stale SDR correction without ever inventing an exposure bias. */
    private fun releaseStaleSdrExposureCorrection() {
        if (!sdrAdaptiveHighlightExposureEligible()) return
        val ageMs = hdrExposureMetricsAgeMs()
        val releasedStops = SdrAdaptiveHighlightExposurePolicy.resolveAppliedProtectionStops(
            measuredStops = sdrAdaptiveHighlightExtraStops,
            metricsAgeMs = ageMs
        )
        val step = if (evCompensationStepStops > 0f) evCompensationStepStops else 1f
        val releasedIndex = ExposureCompensationHysteresisPolicy.resolveIndex(
            currentIndex = lastRequestedSdrExposureCompensationIndex,
            baseEvIndex = evIndex,
            biasStops = releasedStops,
            evStepStops = step,
            evLowerIndex = evRange.lower,
            evUpperIndex = evRange.upper
        )
        if (releasedIndex != lastRequestedSdrExposureCompensationIndex) {
            updatePreviewFromAutonomousControlFeedback()
        }

        val releaseEndMs = SdrAdaptiveHighlightExposurePolicy.LIVE_METRICS_MAX_AGE_MS +
            SdrAdaptiveHighlightExposurePolicy.STALE_METRICS_RELEASE_MS
        if (ageMs < releaseEndMs && releasedStops < 0f) {
            backgroundHandler?.postDelayed(
                sdrExposureStaleReleaseRunnable,
                SdrAdaptiveHighlightExposurePolicy.STALE_METRICS_RELEASE_TICK_MS
            )
        }
    }

    private fun scheduleSdrExposureStaleRelease(smoothedStops: Float) {
        val handler = backgroundHandler ?: return
        handler.removeCallbacks(sdrExposureStaleReleaseRunnable)
        if (smoothedStops < 0f && sdrAdaptiveHighlightExposureEligible()) {
            handler.postDelayed(
                sdrExposureStaleReleaseRunnable,
                SdrAdaptiveHighlightExposurePolicy.LIVE_METRICS_MAX_AGE_MS + 1L
            )
        }
    }

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

    private fun verifyIspBypassReadback(
        r: CaptureResult,
        requested: IspBypassRequestedModes?
    ) {
        requested ?: return
        if (lastRequestedIspBypassModes != requested) return
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

    private fun <T> CaptureRequest.getOrNullKey(key: CaptureRequest.Key<T>): T? =
        runCatching { get(key) }.getOrNull()

    private fun <T> CaptureResult.getOrNullKey(key: CaptureResult.Key<T>): T? =
        runCatching { get(key) }.getOrNull()

    /** Exact final-request snapshot for the tagged still frame delivered to [captureCallback]. */
    private fun snapshotRequestedStillCaptureQuality(request: CaptureRequest): StillCaptureQualitySnapshot =
        StillCaptureQualitySnapshot(
            edgeMode = request.getOrNullKey(CaptureRequest.EDGE_MODE),
            noiseReductionMode = request.getOrNullKey(CaptureRequest.NOISE_REDUCTION_MODE),
            hotPixelMode = request.getOrNullKey(CaptureRequest.HOT_PIXEL_MODE),
            shadingMode = request.getOrNullKey(CaptureRequest.SHADING_MODE),
            tonemapMode = request.getOrNullKey(CaptureRequest.TONEMAP_MODE),
            colorAberrationMode = request.getOrNullKey(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE),
            distortionCorrectionMode = request.getOrNullKey(CaptureRequest.DISTORTION_CORRECTION_MODE),
            aeMode = request.getOrNullKey(CaptureRequest.CONTROL_AE_MODE),
            awbMode = request.getOrNullKey(CaptureRequest.CONTROL_AWB_MODE),
            afMode = request.getOrNullKey(CaptureRequest.CONTROL_AF_MODE),
            colorCorrectionMode = request.getOrNullKey(CaptureRequest.COLOR_CORRECTION_MODE),
            sensorSensitivityIso = request.getOrNullKey(CaptureRequest.SENSOR_SENSITIVITY),
            exposureTimeNs = request.getOrNullKey(CaptureRequest.SENSOR_EXPOSURE_TIME),
            focusDistanceDiopters = request.getOrNullKey(CaptureRequest.LENS_FOCUS_DISTANCE),
            jpegQuality = request.getOrNullKey(CaptureRequest.JPEG_QUALITY)?.toInt()?.and(0xFF),
            jpegOrientationDegrees = request.getOrNullKey(CaptureRequest.JPEG_ORIENTATION)
        )

    /** Public Camera2 result snapshot paired with the exact tagged still request. */
    private fun snapshotReportedStillCaptureQuality(result: CaptureResult): StillCaptureQualitySnapshot =
        StillCaptureQualitySnapshot(
            edgeMode = result.getOrNullKey(CaptureResult.EDGE_MODE),
            noiseReductionMode = result.getOrNullKey(CaptureResult.NOISE_REDUCTION_MODE),
            hotPixelMode = result.getOrNullKey(CaptureResult.HOT_PIXEL_MODE),
            shadingMode = result.getOrNullKey(CaptureResult.SHADING_MODE),
            tonemapMode = result.getOrNullKey(CaptureResult.TONEMAP_MODE),
            colorAberrationMode = result.getOrNullKey(CaptureResult.COLOR_CORRECTION_ABERRATION_MODE),
            distortionCorrectionMode = result.getOrNullKey(CaptureResult.DISTORTION_CORRECTION_MODE),
            aeMode = result.getOrNullKey(CaptureResult.CONTROL_AE_MODE),
            awbMode = result.getOrNullKey(CaptureResult.CONTROL_AWB_MODE),
            afMode = result.getOrNullKey(CaptureResult.CONTROL_AF_MODE),
            colorCorrectionMode = result.getOrNullKey(CaptureResult.COLOR_CORRECTION_MODE),
            sensorSensitivityIso = result.getOrNullKey(CaptureResult.SENSOR_SENSITIVITY),
            exposureTimeNs = result.getOrNullKey(CaptureResult.SENSOR_EXPOSURE_TIME),
            focusDistanceDiopters = result.getOrNullKey(CaptureResult.LENS_FOCUS_DISTANCE)
        )

    /** Latest observe-only still-quality proof. A PARTIAL verdict means the HAL omitted readback keys. */
    internal fun latestStillCaptureQualityReadbackReport(): StillCaptureQualityReadbackReport? =
        lastStillCaptureQualityReadback

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
    fun requestIspBypassReadbackRearm(): Boolean {
        // A live RAW_SENSOR stream alone does not make processed ISP read-back applicable. In the
        // mixed NDR graph RAW owns image truth while companion targets intentionally use the HAL's
        // high-quality processed path, so repeatedly re-arming CP17 would create a false recovery loop.
        if (lastRequestedIspBypassModes?.isBypassRequest != true) return false
        loggedIspBypassVerification = false
        ispBypassReadbackFramesObserved = 0
        return true
    }

    /** True only while the final repeating request actually contains the public ISP-off contract. */
    fun isIspBypassReadbackApplicable(): Boolean =
        lastRequestedIspBypassModes?.isBypassRequest == true

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
        rawReader != null &&
            configuredSessionFingerprint?.streamGraph?.rawSensor?.includeInSession == true

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
            updatePreviewFromAutonomousControlFeedback()
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
     * The convergence-hysteresis debounce governs the automatic SDR white-point hold. Proprietary
     * NDR/NeoLog remains per-frame adaptive, so convergence cannot trigger a delayed request rebuild
     * or latch the provisional startup white point in those modes. RAW sensor masters are excluded.
     */
    private fun isMeteringDampingRelevant(): Boolean =
        !currentRawRequestContract().rawSensorActive &&
            hdrDynamicRangeMode == HdrDynamicRangeMode.SDR &&
            !previewLogLookEnabled &&
            !previewHdrLookEnabled &&
            !previewRawLookEnabled &&
            (videoPreviewActive || recordingActive || previewContinuityRecordSurfaceEnabled)

    private fun maybeRefreshExtremeDetailFallbackExposure(result: LiveResult) {
        if (extremeDetailFallbackExposureRequestApplied) return
        if (!recordingActive && !previewContinuityRecordSurfaceEnabled) return
        if (selectedVideoDetailProfile != VideoDetailProfile.EXTREME_DETAIL) return
        if (currentRawRequestContract().rawSensorActive || !supportsManualSensor) return
        if (manIso > 0 || manExpNs > 0L) return
        if (result.iso <= 0 || result.exposureNs <= 0L) return
        Log.i(
            TAG,
            "Extreme Detail fallback exposure metered refresh " +
                "iso=${result.iso} exposureNs=${result.exposureNs}"
        )
        extremeDetailFallbackExposureRequestApplied = true
        updatePreviewFromAutonomousControlFeedback()
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
            !currentRawRequestContract().rawSensorActive &&
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
        val priorityModeSupported = supportsAePriorityMode(
            AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY,
            CaptureRequest.SENSOR_EXPOSURE_TIME
        )
        if (!hdrAntiMotionBlurEligible() || !priorityModeSupported) {
            // If we were previously engaged, mirror the normal disengage path so the persistent,
            // reused previewBuilder does not keep a stale SENSOR_EXPOSURE_TIME_PRIORITY + pinned
            // SENSOR_EXPOSURE_TIME after the feature becomes ineligible. Only write the key when it
            // is actually supported (never write an unsupported key) and only when previously
            // engaged (avoid needless writes / never engage on unsupported hardware).
            if (hdrAntiBlurEngaged && priorityModeSupported) {
                runCatching {
                    builder[CaptureRequest.CONTROL_AE_PRIORITY_MODE] =
                        AE_PRIORITY_MODE_OFF
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
                    AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY
                builder[CaptureRequest.SENSOR_EXPOSURE_TIME] =
                    decision.pinnedExposureNs.coerceIn(exposureLowerNs, exposureUpperNs)
            } else {
                builder[CaptureRequest.CONTROL_AE_PRIORITY_MODE] =
                    AE_PRIORITY_MODE_OFF
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
            updatePreviewFromAutonomousControlFeedback()
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
        pqHighlightPressureFraction: Float,
        pqRedHighlightPressureFraction: Float,
        sourcePqCandidate: Boolean,
        analysisValid: Boolean,
        measuredAtElapsedRealtimeMs: Long,
        sourceSessionGeneration: Long
    ) {
        if (!analysisValid) return
        if (sourceSessionGeneration >= 0L &&
            sourceSessionGeneration != sessionGeneration.get()
        ) return
        val nowElapsedMs = SystemClock.elapsedRealtime()
        lastHdrExposureMetricsElapsedMs = measuredAtElapsedRealtimeMs
            .takeIf { it > 0L }
            ?.coerceAtMost(nowElapsedMs)
            ?: nowElapsedMs
        val softwareAeSample = SoftwareAeSceneSample(
            domain = SoftwareAeSignalDomain.PROCESSED_YUV,
            averageLuma = averageLuma,
            highlightClipFraction = highlightClipFraction,
            redHighlightClipFraction = redHighlightClipFraction,
            measuredAtElapsedRealtimeMs = lastHdrExposureMetricsElapsedMs
        )
        if (acceptSoftwareAeSceneSample(softwareAeSample, sourceSessionGeneration)) {
            maybeRefreshSoftwareAePriority()
        }

        if (currentNpv10CadenceExposurePlan().active) {
            // The cadence guard is the sole exposure authority for this request. Its fixed-shutter,
            // platform-AE-derived pair already incorporates user EV and highlight safety. Letting the
            // legacy SDR compensation loop run as a second authority clears its own cached request
            // index in applyAll(), then schedules another rebuild for every meter sample. On HALs
            // which drop one sensor interval per repeating-request replacement, that loop both kept
            // pre-writer stabilization from completing and created 66.6 ms holes in live capture.
            backgroundHandler?.removeCallbacks(sdrExposureStaleReleaseRunnable)
            sdrAdaptiveHighlightExtraStops = 0f
            sdrAdaptiveHighlightAppliedStops = 0f
            return
        }

        // True SDR has no fixed exposure offset. Ordinary scenes remain exactly at the user's EV;
        // only measured highlight/channel clipping adds a bounded negative correction.
        if (hdrDynamicRangeMode == HdrDynamicRangeMode.SDR && !sensorLinearNdrCaptureEnabled) {
            if (!sdrAdaptiveHighlightExposureEligible()) {
                backgroundHandler?.removeCallbacks(sdrExposureStaleReleaseRunnable)
                if (sdrAdaptiveHighlightExtraStops != 0f) {
                    sdrAdaptiveHighlightExtraStops = 0f
                    updatePreviewFromAutonomousControlFeedback()
                }
                return
            }
            val target = SdrAdaptiveHighlightExposurePolicy.resolveProtectionStops(
                averageLuma = averageLuma,
                highlightClipFraction = highlightClipFraction,
                redHighlightClipFraction = redHighlightClipFraction
            )
            val smoothed = SdrAdaptiveHighlightExposurePolicy.smoothProtectionStops(
                currentStops = sdrAdaptiveHighlightExtraStops,
                targetStops = target
            )
            sdrAdaptiveHighlightExtraStops = smoothed
            scheduleSdrExposureStaleRelease(smoothed)
            val step = if (evCompensationStepStops > 0f) evCompensationStepStops else 1f
            val smoothedIndex = ExposureCompensationHysteresisPolicy.resolveIndex(
                currentIndex = lastRequestedSdrExposureCompensationIndex,
                baseEvIndex = evIndex,
                biasStops = smoothed,
                evStepStops = step,
                evLowerIndex = evRange.lower,
                evUpperIndex = evRange.upper
            )
            if (nowElapsedMs - lastHdrExposureEvidenceElapsedMs >= 1_000L) {
                lastHdrExposureEvidenceElapsedMs = nowElapsedMs
                Log.i(
                    TAG,
                    "SDREXPOSURE analysisActive=true recording=$recordingActive " +
                        "avgLuma=${averageLuma.toEvidenceDecimal()} " +
                        "highlightClip=${highlightClipFraction.toEvidenceDecimal()} " +
                        "redHighlightClip=${redHighlightClipFraction.toEvidenceDecimal()} " +
                        "targetStops=${target.toEvidenceDecimal()} " +
                        "smoothedStops=${smoothed.toEvidenceDecimal()} " +
                        "adaptiveAppliedStops=${sdrAdaptiveHighlightAppliedStops.toEvidenceDecimal()}"
                )
            }
            if (smoothedIndex != lastRequestedSdrExposureCompensationIndex) {
                updatePreviewFromAutonomousControlFeedback()
            }
            return
        }

        // Sensor-linear NDR is metered from its exact RAW frame by RawVideoManager. Ignore any stale
        // YUV-flow emission so vendor/processed pixels can never influence the proprietary RAW governor.
        if (sensorLinearNdrCaptureEnabled) return

        if (!NdrAdaptiveHighlightFlag.enabled) {
            backgroundHandler?.removeCallbacks(hdrExposureStaleReleaseRunnable)
            if (hdrAdaptiveHighlightExtraStops != 0f) {
                hdrAdaptiveHighlightExtraStops = 0f
                if (ndrExposureGovernorEnabled) updatePreviewFromAutonomousControlFeedback()
            }
            return
        }
        if (!ndrExposureGovernorEnabled) return
        val pqMeterActive = sourcePqCandidate && (
            hdrDynamicRangeMode == HdrDynamicRangeMode.HDR10 ||
                hdrDynamicRangeMode == HdrDynamicRangeMode.HDR10_PLUS
            )
        val effectiveHighlightClipFraction = if (pqMeterActive) {
            maxOf(highlightClipFraction, pqHighlightPressureFraction)
        } else {
            highlightClipFraction
        }
        val effectiveRedHighlightClipFraction = if (NdrRedClipHighlightFlag.enabled) {
            if (pqMeterActive) maxOf(redHighlightClipFraction, pqRedHighlightPressureFraction)
            else redHighlightClipFraction
        } else {
            0f
        }
        val target =
            NdrAdaptiveHighlightExposurePolicy.resolveExtraStops(
                averageLuma,
                effectiveHighlightClipFraction,
                effectiveRedHighlightClipFraction
            )
        // Fast attack protects a channel before it flattens; slow release prevents visible pumping.
        val smoothed = NdrAdaptiveHighlightExposurePolicy.smoothExtraStops(
            currentStops = hdrAdaptiveHighlightExtraStops,
            targetStops = target
        )
        hdrAdaptiveHighlightExtraStops = smoothed
        scheduleHdrExposureStaleRelease(smoothed)
        // Rebuild only when the smoothed bias would move the compensation index by >= one step vs what
        // the last built request applied (the AE-comp is quantised to evCompensationStepStops anyway).
        val step = if (evCompensationStepStops > 0f) evCompensationStepStops else 1f
        val baseProtectStops = if (NdrHighlightProtectFlag.enabled) {
            HdrHighlightProtectExposurePolicy.DEFAULT_HIGHLIGHT_PROTECT_STOPS
        } else {
            0f
        }
        val smoothedIndex = ExposureCompensationHysteresisPolicy.resolveIndex(
            currentIndex = lastRequestedNdrExposureCompensationIndex,
            baseEvIndex = evIndex,
            biasStops = baseProtectStops + smoothed,
            evStepStops = step,
            evLowerIndex = evRange.lower,
            evUpperIndex = evRange.upper
        )
        if (nowElapsedMs - lastHdrExposureEvidenceElapsedMs >= 1_000L) {
            lastHdrExposureEvidenceElapsedMs = nowElapsedMs
            val appliedTotalStops = baseProtectStops + hdrAdaptiveHighlightAppliedStops
            Log.i(
                TAG,
                "NDREXPOSURE analysisActive=true recording=$recordingActive " +
                    "avgLuma=${averageLuma.toEvidenceDecimal()} " +
                    "highlightClip=${effectiveHighlightClipFraction.toEvidenceDecimal()} " +
                    "redHighlightClip=${effectiveRedHighlightClipFraction.toEvidenceDecimal()} " +
                    "pqMeter=$pqMeterActive " +
                    "targetStops=${target.toEvidenceDecimal()} " +
                    "smoothedStops=${smoothed.toEvidenceDecimal()} " +
                    "adaptiveAppliedStops=${hdrAdaptiveHighlightAppliedStops.toEvidenceDecimal()} " +
                    "baseProtectStops=${baseProtectStops.toEvidenceDecimal()} " +
                    "appliedTotalStops=${appliedTotalStops.toEvidenceDecimal()}"
            )
        }
        if (NdrHighlightProtectFlag.enabled &&
            smoothedIndex != lastRequestedNdrExposureCompensationIndex
        ) {
            updatePreviewFromAutonomousControlFeedback()
        }
    }

    override fun setSensorLinearNdrExposureSceneMetrics(
        averageLuma: Float,
        medianLuma: Float,
        p95Luma: Float,
        p99Luma: Float,
        p99Red: Float,
        p99Green: Float,
        p99Blue: Float,
        highlightClipFraction: Float,
        redHighlightClipFraction: Float,
        sampleCount: Int,
        sensorSensitivityIso: Int,
        exposureTimeNs: Long,
        noiseS: Float,
        noiseO: Float,
        reportedExposureCompensationIndex: Int?,
        measuredAtElapsedRealtimeMs: Long,
        sourceSessionGeneration: Long,
        sourceSensorTimestampNs: Long,
        sourceControlRevision: Long
    ) {
        if (sampleCount <= 0) return
        if (sourceSessionGeneration >= 0L &&
            sourceSessionGeneration != sessionGeneration.get()
        ) return
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val sampleTimestampMs = measuredAtElapsedRealtimeMs
            .takeIf { it > 0L }
            ?.coerceAtMost(nowElapsedMs)
            ?: nowElapsedMs
        val softwareAeSample = SoftwareAeSceneSample(
            domain = SoftwareAeSignalDomain.SENSOR_LINEAR_RAW,
            averageLuma = averageLuma,
            highlightClipFraction = highlightClipFraction,
            redHighlightClipFraction = redHighlightClipFraction,
            measuredAtElapsedRealtimeMs = sampleTimestampMs,
            meteredIso = sensorSensitivityIso.takeIf { it > 0 },
            meteredExposureTimeNs = exposureTimeNs.takeIf { it > 0L },
            sensorTimestampNs = sourceSensorTimestampNs,
            controlRevision = sourceControlRevision
        )
        if (acceptSoftwareAeSceneSample(softwareAeSample, sourceSessionGeneration)) {
            maybeRefreshSoftwareAePriority()
        }
        if (!sensorLinearNdrCaptureEnabled || !ndrExposureGovernorEnabled) return
        if (!NdrHighlightProtectFlag.enabled) return
        // A timestamp-matched RAW frame proves the metric feed is alive even while it still reflects the
        // previous Camera2 EV request. Refresh liveness before the lineage guard so the stale-release task
        // cannot fight an active camera pipeline. Mismatched pixels remain excluded from exposure math.
        lastSensorLinearNdrExposureMetricsElapsedMs = sampleTimestampMs
        // The RAW frame carries an exact timestamp-matched CaptureResult. Ignore frames still reporting
        // the previous Camera2 EV index; feeding those delayed pixels into the next control decision is
        // the classic cause of alternating bright/dark exposure pumping on deep camera pipelines.
        val expectedIndex = lastRequestedNdrExposureCompensationIndex
        if (expectedIndex != null && reportedExposureCompensationIndex != null &&
            reportedExposureCompensationIndex != expectedIndex) {
            scheduleHdrExposureStaleRelease(sensorLinearNdrExposureStops)
            return
        }
        scheduleHdrExposureStaleRelease(sensorLinearNdrExposureStops)
        if (nowElapsedMs - lastSensorLinearNdrExposureControlElapsedMs <
            SensorLinearNdrExposurePolicy.CONTROL_EVALUATION_INTERVAL_MS) {
            return
        }
        lastSensorLinearNdrExposureControlElapsedMs = nowElapsedMs
        val nextStops = SensorLinearNdrExposurePolicy.resolveNextStops(
            currentStops = sensorLinearNdrExposureStops,
            averageLuma = averageLuma,
            medianLuma = medianLuma,
            p95Luma = p95Luma,
            p99Luma = p99Luma,
            p99Red = p99Red,
            p99Green = p99Green,
            p99Blue = p99Blue,
            highlightClipFraction = highlightClipFraction,
            redHighlightClipFraction = redHighlightClipFraction,
            sensorSensitivityIso = sensorSensitivityIso,
            // SENSOR_SENSITIVITY reports the analog sensor range, not post-RAW boost. Comparing it
            // against effectiveIsoRange (which includes digital boost) falsely claimed headroom at the
            // physical ISO-3200 ceiling and allowed a residual positive EV command.
            sensorSensitivityUpperIso = isoRange.upper,
            exposureTimeNs = exposureTimeNs,
            frameDurationNs = lastAppliedFpsRequestPlan?.frameDurationNs ?: 0L,
            noiseS = noiseS,
            noiseO = noiseO
        )
        sensorLinearNdrExposureStops = nextStops
        val step = if (evCompensationStepStops > 0f) evCompensationStepStops else 1f
        val smoothedIndex = ExposureCompensationHysteresisPolicy.resolveIndex(
            currentIndex = lastRequestedNdrExposureCompensationIndex,
            baseEvIndex = evIndex,
            biasStops = nextStops,
            evStepStops = step,
            evLowerIndex = evRange.lower,
            evUpperIndex = evRange.upper,
            maxDecreaseSteps = SensorLinearNdrExposurePolicy.MAX_HIGHLIGHT_PROTECT_INDEX_STEP,
            maxIncreaseSteps = SensorLinearNdrExposurePolicy.MAX_SHADOW_LIFT_INDEX_STEP
        )
        if (nowElapsedMs - lastHdrExposureEvidenceElapsedMs >= 1_000L) {
            lastHdrExposureEvidenceElapsedMs = nowElapsedMs
            Log.i(
                TAG,
                "NDREXPOSURE source=RAW_SENSOR samples=$sampleCount " +
                    "avg=${averageLuma.toEvidenceDecimal()} median=${medianLuma.toEvidenceDecimal()} " +
                    "p95=${p95Luma.toEvidenceDecimal()} p99=${p99Luma.toEvidenceDecimal()} " +
                    "p99Rgb=${p99Red.toEvidenceDecimal()}/${p99Green.toEvidenceDecimal()}/" +
                    "${p99Blue.toEvidenceDecimal()} " +
                    "highlightClip=${highlightClipFraction.toEvidenceDecimal()} " +
                    "redHighlightClip=${redHighlightClipFraction.toEvidenceDecimal()} " +
                    "iso=$sensorSensitivityIso exposureNs=$exposureTimeNs " +
                    "noise=${noiseS.toEvidenceDecimal()}/${noiseO.toEvidenceDecimal()} " +
                    "nextStops=${nextStops.toEvidenceDecimal()}"
            )
        }
        if (smoothedIndex != lastRequestedNdrExposureCompensationIndex &&
            nowElapsedMs - lastSensorLinearNdrExposureRequestElapsedMs >=
                SensorLinearNdrExposurePolicy.MIN_REQUEST_INTERVAL_MS) {
            lastSensorLinearNdrExposureRequestElapsedMs = nowElapsedMs
            updatePreviewFromAutonomousControlFeedback()
        }
    }

    private fun mutateControlState(
        immediate: Boolean,
        unlockAeAwb: Boolean = false,
        stateMutation: () -> Unit,
        paramsMutation: (CameraParams) -> CameraParams
    ) {
        // Control state is the durable source of truth for the next request/session. Persist it at the
        // call boundary even when CameraBackground has not started yet (startup preference restore) or
        // has already stopped (activity recreation). Putting these mutations inside runOnCameraThread
        // made them disappear when that method correctly rejected work for a stopped handler; the next
        // camera open then reused stale ISO/exposure/WB/focus/EV/flash values. updatePreview() performs
        // its own generation-checked camera-thread dispatch when a live session exists, and is a safe
        // no-op before a session is configured.
        stateMutation()
        paramsFlow.value = paramsMutation(paramsFlow.value)
        if (unlockAeAwb) aeAwbLocked = false
        updatePreview(immediate = immediate)
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

    /**
     * Complete visible sensor region used by automatic white balance. Unlike AF/AE subject regions,
     * this follows only framing (aspect + zoom), never a face, tap, or focused object.
     */
    private fun activeWhiteBalanceFrameRect(): CameraRect? {
        val sensorRect = activeSensorCameraRect() ?: return null
        val sensorAspect = sensorRect.width.toFloat() / sensorRect.height.toFloat()
        val framingAspect = targetOutputAspectRatio
            ?.takeIf { it.isFinite() && it > 0f }
            ?: sensorAspect
        return CameraControlMath.buildAspectAndZoomCropRegion(
            sensorRect = sensorRect,
            targetAspectRatio = framingAspect,
            zoom = zoomRatio,
            maxZoom = maxZoom
        )
    }

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
        if (maxAwbRegions <= 0 || !supportsCaptureRequestKey(CaptureRequest.CONTROL_AWB_REGIONS)) {
            clearAutoWhiteBalanceRegions(builder)
            return
        }
        val activeSensorRect = activeWhiteBalanceFrameRect()
        val awbRegions = CinemaAutoWhiteBalanceRegionPolicy.resolve(
            // Keep AWB independent from subject/tap-focus metering. AF and AE can follow the
            // selected object while the complete frame remains the white-balance authority.
            subjectRegion = null,
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

    private fun clearAutoWhiteBalanceRegions(builder: CaptureRequest.Builder) {
        if (supportsCaptureRequestKey(CaptureRequest.CONTROL_AWB_REGIONS)) {
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
        videoSnapshot: Boolean = false,
        cadenceProtectedSnapshot: Boolean = videoSnapshot
    ) {
        val requestPlan = pipelineRequestPlan ?: resolveCamera2PipelineRequestPlan()
        val rawRequestContract = currentRawRequestContract()
        val rawSensorRequestActive = rawRequestContract.rawSensorActive
        val rawOriginalCaptureActive = rawRequestContract.rawOriginalCaptureActive
        val sensorLinearNdrRequestActive = rawRequestContract.sensorLinearNdrActive
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
        val videoRecordingRequestActive =
            rawRequestContract.recordingActive || previewContinuityRecordSurfaceEnabled
        // X1/R2: idle video preview adopts the record-grade extreme-detail exposure posture so the
        // preview stops visibly improving only when recording starts. videoPreviewActive is true for
        // VIDEO/PRO surfaces (set by CameraViewModel) and false for stills. The record-only
        // color-stability lock stays keyed on videoRecordingRequestActive (below) to preserve the
        // reframe-washout fix; this predicate only widens the extreme-detail exposure gate.
        // A full-resolution still taken from the video UI must not inherit the video's frame-duration,
        // anti-motion exposure ceiling, or continuous-video AF posture. Those controls were the source of
        // dark/soft indoor photos even though the JPEG itself was full resolution.
        val videoRenderContractActive = !forStillCapture &&
            (videoRecordingRequestActive || videoPreviewActive)

        builder[CaptureRequest.STATISTICS_FACE_DETECT_MODE] = if (faceDetectionEnabled) {
            CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL
        } else {
            CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
        }
        // F4 diagnostic: request the lens-shading correction map so we can measure the sensor's residual
        // optical colour shading (the "magenta-centre / green-edge" cast) on-device. Observe-only —
        // Enabling the map does not alter output pixels. Keep it for stills and RAW_SENSOR, but
        // suppress its native payload for the full processed preview/record session. This matters
        // because recording surfaces are pre-armed before recordingActive changes; pixel correction
        // remains independently preserved by the SHADING_MODE quality request below.
        if (supportsCaptureRequestKey(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE)) {
            builder[CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE] =
                CameraRealtimeMetadataPolicy.lensShadingMapMode(
                    rawSensorActive = rawSensorRequestActive,
                    stillCapture = forStillCapture
                )
        }

        val consumerAutoVideo = isConsumerAutoVideoRequest(forStillCapture)
        // Centralized public Camera2 processing intent for consumer versus cinema paths.
        val baseProfile = HardwareAbstractionLayer.resolveProfile(
            isRecording = videoRecordingRequestActive,
            // Camera2 processing controls apply to every processed target in a shared request but do
            // not alter RAW_SENSOR samples. NDR therefore keeps its genuine RAW master while asking
            // the HAL for a valid, high-quality processed companion image instead of an identity curve.
            isRawCapture = rawOriginalCaptureActive,
            isLogMode = previewLogLookEnabled,
            isCinemaFidelity = cinemaFidelityOverridesEnabled || sensorLinearNdrRequestActive,
            noiseReductionEnabled = noiseReductionEnabled,
            isHdrMode = hdrDynamicRangeMode != HdrDynamicRangeMode.SDR,
            isStillCapture = forStillCapture,
            isHighSpeed = targetCameraFps >= 60,
            isConsumerMode = consumerAutoVideo
        )
        val profile = HardwareAbstractionLayer.thermalAwareProfile(baseProfile, thermalResourceCeiling)
        // RAW_SENSOR remains pre-tonemap, while the processed companion monitor receives a viewable SDR
        // transform. This covers high-resolution and active-recording RAW paths that intentionally use the
        // responsive Camera2 monitor instead of the app-owned sensor-debayer monitor.
        var effectiveProfile =
            if (RawPreviewViewableTonemapFlag.shouldSubstituteViewableTonemap(
                    flagEnabled = RawPreviewViewableTonemapFlag.enabled,
                    rawVideoCaptureEnabled = rawSensorRequestActive,
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
        // Keep RAW_SENSOR sensor-original while correcting the processed monitor's optical colour
        // shading and chromatic aberration. This prevents a spatial pink/magenta centre cast without
        // baking any correction into the RAW master.
        val rawPreviewOpticalPlan = RawPreviewOpticalCorrectionPolicy.resolve(
            rawSensorRequestActive = rawSensorRequestActive,
            forStillCapture = forStillCapture,
            availability = ispAvailability
        )
        rawPreviewOpticalPlan?.let { optical ->
            effectiveProfile = effectiveProfile.copy(
                shadingMode = optical.shadingMode ?: effectiveProfile.shadingMode,
                aberrationCorrectionMode =
                    optical.aberrationMode ?: effectiveProfile.aberrationCorrectionMode
            )
        }
        effectiveProfile.apply(builder, characteristics)

        val stabilizationPlan = CameraVideoStabilizationRequestPolicy.resolve(
            requestedMode = stabilizationMode,
            supportsOis = supportsOIS,
            supportsVideoStabilization = supportsVideoStabilization,
            rawVideoCaptureEnabled = rawSensorRequestActive,
            supportsPreviewStabilization = supportsPreviewStabilization,
            stillCapture = forStillCapture
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
            !rawSensorRequestActive &&
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
            rawVideoCaptureEnabled = rawOriginalCaptureActive,
            recordingActive = videoRecordingRequestActive,
            readoutSidecarActive = recordingReadoutOverrideSize != null || analysisTargetSize != null,
            hdrDynamicRangeMode = hdrDynamicRangeMode,
            appOwnedGpuProcessingEnabled = internalRecordingHardwareImportEnabled,
            strictHighEndEnabled = strictHighEndEnabled(),
            sensorLinearNdrCaptureEnabled = sensorLinearNdrRequestActive,
            processedStillCapture = forStillCapture
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
        // Total-ISP-bypass deliberately writes SHADING/CA OFF, so restore the preview-only optical
        // request after it. Camera2 RAW_SENSOR pixels remain unaffected by these processed-output keys.
        rawPreviewOpticalPlan?.shadingMode?.let { builder[CaptureRequest.SHADING_MODE] = it }
        rawPreviewOpticalPlan?.aberrationMode?.let {
            builder[CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE] = it
        }

        val processedVideoPlan = if (
            !forStillCapture &&
            !rawSensorRequestActive &&
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
            appOwnedGpuProcessingEnabled = internalRecordingHardwareImportEnabled && !rawSensorRequestActive,
            strictHighEndEnabled = strictHighEndEnabled(),
            processedStillCapture = forStillCapture,
            availability = ispAvailability
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            gpuOwnedDistortionMode?.let {
                builder[CaptureRequest.DISTORTION_CORRECTION_MODE] = it
            }
        }
        val gpuOwnedGeometryEvidence = if (
            internalRecordingHardwareImportEnabled && !rawSensorRequestActive && !forStillCapture
        ) {
            mapOf(
                "gpuOwnedDistortionCorrectionOffRequested" to "true",
                "gpuOwnedDistortionCorrectionOff" to CameraGpuOwnedGeometryPolicy
                    .isDistortionCorrectionOffConfirmed(true, gpuOwnedDistortionMode)
                    .toString()
            )
        } else {
            emptyMap()
        }
        // The NDR private master is the only source from which every delivery format is developed. Do not
        // let single-frame HIGH_QUALITY NR erase texture in that master. Preserve it here, then let the
        // offline edge-aware/temporal stage remove measured noise without inventing detail.
        val ndrMasterQualityPlan = NdrMasterCaptureQualityPolicy.resolve(
            active = !forStillCapture &&
                npv10TenBitCaptureEnabled &&
                hdrDynamicRangeMode != HdrDynamicRangeMode.SDR,
            availability = ispAvailability
        )
        ndrMasterQualityPlan?.let { plan ->
            builder[CaptureRequest.EDGE_MODE] = plan.edgeMode
            builder[CaptureRequest.NOISE_REDUCTION_MODE] = plan.noiseReductionMode
            builder[CaptureRequest.HOT_PIXEL_MODE] = plan.hotPixelMode
        }
        val ndrMasterQualityEvidence = ndrMasterQualityPlan?.let { plan ->
            mapOf(
                "ndrMasterDetailPreservingRequestApplied" to "true",
                "ndrMasterEdgeMode" to plan.edgeMode.toString(),
                "ndrMasterNoiseReductionMode" to plan.noiseReductionMode.toString(),
                "ndrMasterHotPixelMode" to plan.hotPixelMode.toString()
            )
        } ?: emptyMap()
        appliedProcessedVideoQualityEvidence =
            processedVideoEvidence + gpuOwnedGeometryEvidence + ndrMasterQualityEvidence

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

        // Resolve a cinematic focus-pull step once. The unified coordinator consumes this exact value
        // later, after the mode-specific metering state has been assembled; still capture retains the
        // established CameraAutofocusRequestPolicy path.
        val effectiveManFocusDist = resolveEffectiveManualFocusDistance()

        val normalSdrVideoPreviewActive =
            videoPreviewActive &&
                !videoRecordingRequestActive &&
                !rawSensorRequestActive &&
                hdrDynamicRangeMode == HdrDynamicRangeMode.SDR &&
                !previewLogLookEnabled &&
                !previewHdrLookEnabled &&
                !previewRawLookEnabled
        val ndrOrLogModeActive = hdrDynamicRangeMode != HdrDynamicRangeMode.SDR || previewLogLookEnabled
        // Preserve NDR/NeoLog identity so the colour-stability policy keeps its timestamp-matched
        // per-frame WB/CCM path distinct from the normal-SDR automatic white-point hold.
        val ndrOrLogVideoPreviewActive =
            videoPreviewActive &&
                !videoRecordingRequestActive &&
                !rawSensorRequestActive &&
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
        val rawSensorNdrExposureActive =
            sensorLinearNdrCaptureEnabled && ndrExposureGovernorEnabled
        val hdrHighlightProtectActive =
            ndrExposureGovernorEnabled &&
                (hdrDynamicRangeMode != HdrDynamicRangeMode.SDR || rawSensorNdrExposureActive) &&
                NdrHighlightProtectFlag.enabled
        val sdrHighlightProtectActive =
            !rawSensorRequestActive && sdrAdaptiveHighlightExposureEligible()
        val sensorLinearNdrBiasStops = if (rawSensorNdrExposureActive) {
            SensorLinearNdrExposurePolicy.resolveAppliedStops(
                measuredStops = sensorLinearNdrExposureStops,
                metricsAgeMs = sensorLinearNdrExposureMetricsAgeMs()
            )
        } else {
            0f
        }
        val processedHdrAdaptiveBiasStops = if (
            hdrHighlightProtectActive &&
            !rawSensorNdrExposureActive &&
            NdrAdaptiveHighlightFlag.enabled
        ) {
            NdrAdaptiveHighlightExposurePolicy.resolveAppliedExtraStops(
                measuredExtraStops = hdrAdaptiveHighlightExtraStops,
                metricsAgeMs = hdrExposureMetricsAgeMs()
            )
        } else {
            0f
        }
        val processedHdrProtectionBiasStops = if (
            hdrHighlightProtectActive && !rawSensorNdrExposureActive
        ) {
            HdrHighlightProtectExposurePolicy.DEFAULT_HIGHLIGHT_PROTECT_STOPS +
                processedHdrAdaptiveBiasStops
        } else {
            0f
        }
        val sdrProtectionBiasStops = if (sdrHighlightProtectActive) {
            SdrAdaptiveHighlightExposurePolicy.resolveAppliedProtectionStops(
                measuredStops = sdrAdaptiveHighlightExtraStops,
                metricsAgeMs = hdrExposureMetricsAgeMs()
            )
        } else {
            0f
        }
        val colorStabilityPlan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = videoRecordingRequestActive,
            sensorMasterActive = rawSensorRequestActive,
            manualWhiteBalanceSelected = manWbK > 0,
            existingAeAwbLock = aeAwbLocked,
            normalSdrVideoPreviewActive = normalSdrVideoPreviewActive,
            ndrOrLogVideoPreviewActive = ndrOrLogVideoPreviewActive,
            autoExposureConvergedOrLocked = autoExposureConvergedOrLocked,
            autoWhiteBalanceConvergedOrLocked = autoWhiteBalanceConvergedOrLocked,
            ndrOrLogModeActive = ndrOrLogModeActive,
            tapFocusMeteringUnlockActive = tapFocusMeteringUnlockActive,
            automaticRecordingWhiteBalanceLockEnabled = true
        )
        val unifiedControlDecision = if (videoRenderContractActive) {
            resolveUnifiedVideoControlDecision(
                rawRequestContract = rawRequestContract,
                videoRecordingRequestActive = videoRecordingRequestActive,
                manualWhiteBalanceAllowed = colorStabilityPlan.allowManualWhiteBalance,
                effectiveManualFocusDistance = effectiveManFocusDist,
                autoWhiteBalanceConverged = autoWhiteBalanceConvergedOrLocked,
                highlightProtectionBiasStops = when {
                    hdrHighlightProtectActive && !rawSensorNdrExposureActive ->
                        processedHdrProtectionBiasStops
                    sdrHighlightProtectActive -> sdrProtectionBiasStops
                    else -> 0f
                },
                sensorLinearNdrBiasStops = sensorLinearNdrBiasStops,
                previousExposureCompensationIndex = when {
                    hdrHighlightProtectActive -> lastRequestedNdrExposureCompensationIndex
                    sdrHighlightProtectActive -> lastRequestedSdrExposureCompensationIndex
                    else -> null
                }
            )
        } else {
            null
        }
        val selectedAutoFocusMode = when {
            tapAfScanActive && !forStillCapture -> singleShotAutoFocusMode()
            forStillCapture && !cadenceProtectedSnapshot -> stillPictureAutoFocusMode()
            else -> continuousAutoFocusMode()
        }
        val unifiedFocus = unifiedControlDecision?.focus
        val autofocusPlan = CameraAutofocusRequestPolicy.resolve(
            manualFocusDistance = when (unifiedFocus?.authority) {
                UnifiedFocusAuthority.USER_MANUAL ->
                    unifiedFocus.lensFocusDistanceDiopters ?: -1f
                null -> effectiveManFocusDist
                else -> -1f
            },
            aiDrivenFocusDistance = when (unifiedFocus?.authority) {
                UnifiedFocusAuthority.AI_LENS_DISTANCE ->
                    unifiedFocus.lensFocusDistanceDiopters ?: -1f
                null -> aiDrivenFocusDist
                else -> -1f
            },
            // CameraAutofocusRequestPolicy applies an AI lens-distance only under AF_MODE_OFF. A tap
            // scan remains the higher-priority transient owner and has already cleared lens overrides.
            selectedAfMode = if (
                unifiedFocus?.authority == UnifiedFocusAuthority.AI_LENS_DISTANCE &&
                !tapAfScanActive
            ) {
                CaptureRequest.CONTROL_AF_MODE_OFF
            } else {
                selectedAutoFocusMode
            },
            maxAfRegions = maxAfRegions,
            maxFocusDistance = minFocusDist,
            // effectiveManFocusDist is 0f ONLY from an explicit setFocusDist(0f). Preserve that
            // explicit infinity lock through the unified decision; no automatic/AI zero is promoted.
            manualFocusInfinityRequested =
                unifiedFocus?.authority == UnifiedFocusAuthority.USER_MANUAL &&
                    unifiedFocus.lensFocusDistanceDiopters == 0f ||
                    (unifiedFocus == null && effectiveManFocusDist == 0f)
        )
        applyPersistentMeteringRegions(builder, autofocusPlan.applyAfRegions)
        builder[CaptureRequest.CONTROL_AF_MODE] = autofocusPlan.afMode
        if (autofocusPlan.lensFocusDistance != null) {
            builder[CaptureRequest.LENS_FOCUS_DISTANCE] = autofocusPlan.lensFocusDistance
        } else {
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, null)
        }
        unifiedControlDecision?.let { decision ->
            appliedProcessedVideoQualityEvidence = appliedProcessedVideoQualityEvidence + mapOf(
                "unifiedVideoControlMode" to decision.mode.name,
                "unifiedVideoControlDetailProfile" to decision.detailProfile.name,
                "unifiedVideoExposureAuthority" to decision.exposure.authority.name,
                "unifiedVideoWhiteBalanceAuthority" to decision.whiteBalance.authority.name,
                "unifiedVideoFocusAuthority" to decision.focus.authority.name,
                "unifiedVideoControlRejections" to
                    decision.rejections.joinToString(separator = ",") { it.name }
            )
        }
        // Publish this mode's per-frame drift budget so the capture-result governor enforces it.
        activeColorDriftBudgetUv = colorStabilityPlan.maxFrameToFrameColorDriftUv
        val effectiveManualKelvin = when {
            unifiedControlDecision != null ->
                unifiedControlDecision.whiteBalance.fixedKelvin ?: -1
            colorStabilityPlan.allowManualWhiteBalance -> manWbK
            else -> -1
        }
        val effectiveManualTint = when {
            unifiedControlDecision != null ->
                unifiedControlDecision.whiteBalance.fixedTint ?: 0
            effectiveManualKelvin > 0 -> manWbTint
            else -> 0
        }
        // P1.6: when the shot-stable path must fall back to AWB_OFF+fixed-Kelvin (device whose AUTO
        // mode is not lockable), hold the last measured device-true white point instead of jumping to
        // a hardcoded 5500K — no visible white-point step when the lock engages.
        val shotStableFallback = deviceTrueShotStableFallbackWhitePoint()
        val unifiedStableWhiteBalance = unifiedControlDecision?.whiteBalance?.let { decision ->
            decision.stableFallbackKelvin?.let { kelvin ->
                kelvin to decision.stableFallbackTint
            }
        }
        val shotStableFallbackKelvin =
            unifiedStableWhiteBalance?.first
                ?: shotStableFallback?.first
                ?: 5_500
        val shotStableFallbackTint =
            unifiedStableWhiteBalance?.second
                ?: shotStableFallback?.second
                ?: 0
        val fixedWhiteBalanceKelvin = if (effectiveManualKelvin > 0) {
            effectiveManualKelvin.coerceIn(manualWhiteBalanceRange.lower, manualWhiteBalanceRange.upper)
        } else {
            shotStableFallbackKelvin.coerceIn(manualWhiteBalanceRange.lower, manualWhiteBalanceRange.upper)
        }
        val fixedWhiteBalanceTint = if (effectiveManualKelvin > 0) {
            effectiveManualTint
        } else {
            shotStableFallbackTint
        }
        val completeCctRequestSupported = supportsCompleteCctWhiteBalanceRequest()
        // Resolve and validate the complete calibration payload before the policy is allowed to choose
        // AWB_OFF. A missing/invalid CCM therefore produces an AUTO/preset plan, never a partial manual
        // request or a transform borrowed from an earlier capture result.
        val manualWhiteBalanceTransform = if (!completeCctRequestSupported) {
            resolveManualWhiteBalanceTransform(fixedWhiteBalanceKelvin, fixedWhiteBalanceTint)
        } else {
            null
        }
        val whiteBalancePlan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = effectiveManualKelvin,
            manualTint = effectiveManualTint,
            kelvinRange = CameraIntRange(manualWhiteBalanceRange.lower, manualWhiteBalanceRange.upper),
            availableAwbModes = cachedAwbModes,
            supportsCctWhiteBalance = completeCctRequestSupported,
            manualTransformReady = manualWhiteBalanceTransform != null,
            // Proprietary NDR/NeoLog and RAW keep their existing adaptive/sensor-master policies.
            // For ordinary VIDEO/PRO recording, the unified confidence gate is the final automatic
            // lock authority; preview lock and explicit operator lock retain their established rules.
            awbLocked = colorStabilityPlan.awbLockRequested && when {
                aeAwbLocked -> true
                ndrOrLogModeActive || rawSensorRequestActive -> true
                videoRecordingRequestActive && unifiedControlDecision != null ->
                    unifiedControlDecision.whiteBalance.requestAutoLock
                else -> true
            },
            awbLockAvailable = supportsAutoWhiteBalanceLock,
            forceAutoForHdr = false,
            sdkInt = Build.VERSION.SDK_INT,
            requireLockableAutoWhiteBalance = colorStabilityPlan.requiresLockableAutoWhiteBalance,
            shotStableFallbackKelvin = shotStableFallbackKelvin,
            shotStableFallbackTint = shotStableFallbackTint
        )
        val validatedTransform = if (whiteBalancePlan.useTransformMatrix) {
            requireNotNull(manualWhiteBalanceTransform) {
                "Transform WB plan must carry its validated calibration payload"
            }
        } else {
            null
        }
        clearWhiteBalanceRequestOverrides(builder)
        builder[CaptureRequest.CONTROL_AWB_MODE] = whiteBalancePlan.awbMode
        val requestedKelvin = whiteBalancePlan.colorTemperatureKelvin
        val requestedTint = whiteBalancePlan.colorTint ?: 0
        if (manWbK > 0 && requestedKelvin == null && colorStabilityPlan.allowManualWhiteBalance) {
            val signature = "$currentCameraId|awb=${cachedAwbModes.joinToString()}|" +
                "cct=$completeCctRequestSupported|manualTransform=${manualWhiteBalanceTransform != null}"
            if (signature != lastLoggedManualWbFallbackSignature) {
                lastLoggedManualWbFallbackSignature = signature
                Log.w(
                    TAG,
                    "Manual white balance requested but unsupported by this camera; " +
                        "keeping a supported AUTO/preset AWB mode ($signature)"
                )
            }
        }
        if (requestedKelvin != null) clearAutoWhiteBalanceRegions(builder)
        if (requestedKelvin != null &&
            whiteBalancePlan.colorCorrectionMode == CameraMetadata.COLOR_CORRECTION_MODE_CCT &&
            Build.VERSION.SDK_INT >= 36
        ) {
            builder[CaptureRequest.COLOR_CORRECTION_MODE] =
                CameraMetadata.COLOR_CORRECTION_MODE_CCT
            builder[CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE] = requestedKelvin
            builder[CaptureRequest.COLOR_CORRECTION_COLOR_TINT] = requestedTint
        } else if (requestedKelvin != null &&
            whiteBalancePlan.useTransformMatrix &&
            validatedTransform != null
        ) {
            builder[CaptureRequest.COLOR_CORRECTION_MODE] =
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            applyManualTransformMatrixWhiteBalance(builder, validatedTransform)
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
            rawVideoCaptureEnabled = rawSensorRequestActive,
            manualColorTransformActive = manualColorTransformActive,
            preferFastForHdr = CameraHdrColorFastModeFlag.enabled,
            processedStillCapture = forStillCapture
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

        val cadenceExposurePlan = currentNpv10CadenceExposurePlan()
        val appliedAntibandingMode = Npv10CadenceExposurePolicy.resolveAntibandingMode(
            plan = cadenceExposurePlan,
            requestedMode = requestPlan.antibandingMode,
            availableModes = cachedAntibandingModes,
            stillCapture = forStillCapture
        )
        val streamFpsOverride = resolveCurrentStreamBasedFpsOverridePlan()
        val fpsRequest = resolveCurrentFpsRequestPlan(
            videoRecordingRequestActive = videoRecordingRequestActive,
            streamBasedFpsOverride = streamFpsOverride,
            antibandingMode = appliedAntibandingMode
        )
        if (!forStillCapture || cadenceProtectedSnapshot) {
            fpsRequest.aeTargetFpsRange?.let { range ->
                builder[CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE] = Range(range.lower, range.upper)
            }
        }
        val streamFpsRequestKeysApplied = if (!forStillCapture || cadenceProtectedSnapshot) {
            applyStreamBasedFpsKeys(
                builder = builder,
                plan = streamFpsOverride,
                sessionParameters = false
            )
        } else {
            false
        }
        lastAppliedStreamBasedFpsOverridePlan = streamFpsOverride
        lastAppliedFpsRequestPlan = fpsRequest
        maybeLogStreamBasedFpsOverride(
            plan = streamFpsOverride,
            requestKeysApplied = streamFpsRequestKeysApplied,
            sessionKeysApplied = false
        )
        appliedAntibandingMode?.let { mode ->
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
                rawVideoCaptureEnabled = rawSensorRequestActive,
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
                "$rawSensorRequestActive|" +
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
                    "detailProfile=$selectedVideoDetailProfile raw=$rawSensorRequestActive " +
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
            // A still uses its own AE/manual overrides and must not inherit the video mode's
            // fixed-cadence cinematic exposure posture. This prevents dark Raw/Log-mode JPEGs.
            cinematicActive = !forStillCapture && isCinemaLowProcessingRequestActive(),
            supportsManualSensor = supportsManualSensor,
            requestedIso = manIso,
            requestedExposureNs = manExpNs,
            latestIso = latest?.iso,
            targetFps = targetCameraFps
        )
        val extremeDetailFallbackManualExposure =
            extremeDetailFallbackExposurePlan.activateManualExposure
        val unifiedManualSensorExposure = unifiedControlDecision?.exposure
            ?.takeIf {
                it.authority == UnifiedExposureAuthority.USER_MANUAL_SENSOR ||
                    it.authority == UnifiedExposureAuthority.USER_SOFTWARE_ISO_PRIORITY ||
                    it.authority == UnifiedExposureAuthority.USER_SOFTWARE_SHUTTER_PRIORITY ||
                    it.authority == UnifiedExposureAuthority.SYSTEM_NPV10_CADENCE_GUARD
            }
        // The unified decision owns explicit user controls.  The cinematic path is retained only as
        // a legacy/no-unified fallback; allowing it to run first replaced the live metered axis of a
        // partial-manual request (for example manual ISO + automatic shutter) with a fixed 180-degree
        // shutter.  A pan from a dark subject toward a practical light could then blow the entire
        // frame white even though the coordinator had already resolved a safe metered shutter.
        val legacyCinematicManualExposure =
            unifiedControlDecision == null && cinematicExposurePlan.activateManualExposure
        val legacyManualSensorExposure =
            unifiedControlDecision == null && (manIso > 0 || manExpNs > 0L)
        val v20TimingBlockReason = when {
            processedVideoPlan == null -> "NO_PROCESSED_VIDEO_PLAN"
            // Eligible when EITHER the high-motion short-shutter OR the low-noise long-shutter posture
            // requested a timing key (both surface as maxExposureTimeNs); block only when neither did.
            !processedVideoPlan.antiMotionBlurEnabled &&
                processedVideoPlan.maxExposureTimeNs == null -> "NOT_REQUESTED"
            !videoRecordingRequestActive -> "NOT_RECORDING"
            rawSensorRequestActive -> "RAW_VIDEO"
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
                    unifiedManualSensorExposure != null ||
                    legacyCinematicManualExposure ||
                    legacyManualSensorExposure
                )
        ) {
            clearAePriorityMode(builder)
            val resolvedIso = when {
                extremeDetailFallbackManualExposure -> extremeDetailFallbackExposurePlan.iso
                unifiedManualSensorExposure != null ->
                    requireNotNull(unifiedManualSensorExposure.sensorSensitivityIso)
                legacyCinematicManualExposure -> cinematicExposurePlan.iso
                else -> if (manIso > 0) manIso else (latest?.iso ?: 100)
            }
            val resolvedExp = when {
                extremeDetailFallbackManualExposure ->
                    extremeDetailFallbackExposurePlan.exposureNs
                unifiedManualSensorExposure != null ->
                    requireNotNull(unifiedManualSensorExposure.exposureTimeNs)
                legacyCinematicManualExposure -> cinematicExposurePlan.exposureNs
                else -> if (manExpNs > 0L) manExpNs else (latest?.exposureNs ?: 33333333L)
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
                legacyCinematicManualExposure &&
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
            val manualEvCompensation = when {
                extremeDetailFallbackManualExposure ->
                    CameraCinematicExposurePolicy.applyLockedShutterEv(
                    baseIso = cinemaEvBaseIso,
                    lockedExposureNs = baseManualFpsRequest.exposureNs,
                    evIndex = evIndex.coerceIn(evRange.lower, evRange.upper),
                    evStepStops = evCompensationStepStops,
                    isoRange = CameraIntRange(effectiveIsoRange.lower, effectiveIsoRange.upper),
                    exposureRange = CameraLongRange(exposureRange.lower, exposureRange.upper),
                    frameExposureBudgetNs = baseManualFpsRequest.frameDurationNs
                )
                unifiedManualSensorExposure != null -> ManualSensorEvCompensationResult(
                    iso = requireNotNull(unifiedManualSensorExposure.sensorSensitivityIso),
                    exposureNs = requireNotNull(unifiedManualSensorExposure.exposureTimeNs),
                    evStops = unifiedManualSensorExposure.appliedManualEvStops
                )
                legacyCinematicManualExposure ->
                    CameraCinematicExposurePolicy.applyLockedShutterEv(
                        baseIso = cinemaEvBaseIso,
                        lockedExposureNs = baseManualFpsRequest.exposureNs,
                        evIndex = evIndex.coerceIn(evRange.lower, evRange.upper),
                        evStepStops = evCompensationStepStops,
                        isoRange = CameraIntRange(effectiveIsoRange.lower, effectiveIsoRange.upper),
                        exposureRange = CameraLongRange(exposureRange.lower, exposureRange.upper),
                        frameExposureBudgetNs = baseManualFpsRequest.frameDurationNs
                    )
                else -> ManualSensorEvCompensationPolicy.resolve(
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
            lastRequestedSdrExposureCompensationIndex = null
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
            clearAutomaticExposureSensorOverrides(builder)
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
            // NDR10+/NIVUKX and SDR analysis remain the measurement authorities. Their legacy result is
            // retained for still capture; a live video request routes the same measured bias through the
            // unified coordinator so only one component writes the final AE-compensation key.
            val legacyRequestedExposureCompensation = when {
                hdrHighlightProtectActive -> {
                    // Sensor-linear NDR is driven directly by RAW percentiles and has no permanent
                    // negative baseline. Processed HDR retains its established highlight-protect floor.
                    val adaptiveExtra = if (rawSensorNdrExposureActive) {
                        SensorLinearNdrExposurePolicy.resolveAppliedStops(
                            measuredStops = sensorLinearNdrExposureStops,
                            metricsAgeMs = sensorLinearNdrExposureMetricsAgeMs()
                        )
                    } else if (NdrAdaptiveHighlightFlag.enabled) {
                            NdrAdaptiveHighlightExposurePolicy.resolveAppliedExtraStops(
                                measuredExtraStops = hdrAdaptiveHighlightExtraStops,
                                metricsAgeMs = hdrExposureMetricsAgeMs()
                            )
                    } else {
                        0f
                    }
                    val baseProtectStops = if (rawSensorNdrExposureActive) {
                        0f
                    } else {
                        HdrHighlightProtectExposurePolicy.DEFAULT_HIGHLIGHT_PROTECT_STOPS
                    }
                    sensorLinearNdrExposureAppliedStops =
                        if (rawSensorNdrExposureActive) adaptiveExtra else 0f
                    hdrAdaptiveHighlightAppliedStops =
                        if (rawSensorNdrExposureActive) 0f else adaptiveExtra
                    sdrAdaptiveHighlightAppliedStops = 0f
                    ExposureCompensationHysteresisPolicy.resolveIndex(
                        currentIndex = lastRequestedNdrExposureCompensationIndex,
                        baseEvIndex = evIndex,
                        biasStops = baseProtectStops + adaptiveExtra,
                        evStepStops = evCompensationStepStops,
                        evLowerIndex = evRange.lower,
                        evUpperIndex = evRange.upper,
                        maxDecreaseSteps = if (rawSensorNdrExposureActive) {
                            SensorLinearNdrExposurePolicy.MAX_HIGHLIGHT_PROTECT_INDEX_STEP
                        } else {
                            Int.MAX_VALUE
                        },
                        maxIncreaseSteps = if (rawSensorNdrExposureActive) {
                            SensorLinearNdrExposurePolicy.MAX_SHADOW_LIFT_INDEX_STEP
                        } else {
                            Int.MAX_VALUE
                        }
                    )
                }
                sdrHighlightProtectActive -> {
                    // SDR stays neutral unless the live meter proves clipping. Applying this before
                    // the tone curve preserves real sky/window samples that post-processing cannot restore.
                    val adaptiveProtection =
                        SdrAdaptiveHighlightExposurePolicy.resolveAppliedProtectionStops(
                            measuredStops = sdrAdaptiveHighlightExtraStops,
                            metricsAgeMs = hdrExposureMetricsAgeMs()
                        )
                    sdrAdaptiveHighlightAppliedStops = adaptiveProtection
                    hdrAdaptiveHighlightAppliedStops = 0f
                    ExposureCompensationHysteresisPolicy.resolveIndex(
                        currentIndex = lastRequestedSdrExposureCompensationIndex,
                        baseEvIndex = evIndex,
                        biasStops = adaptiveProtection,
                        evStepStops = evCompensationStepStops,
                        evLowerIndex = evRange.lower,
                        evUpperIndex = evRange.upper
                    )
                }
                else -> {
                    hdrAdaptiveHighlightAppliedStops = 0f
                    sensorLinearNdrExposureAppliedStops = 0f
                    sdrAdaptiveHighlightAppliedStops = 0f
                    evIndex.coerceIn(evRange.lower, evRange.upper)
                }
            }
            val requestedExposureCompensation = unifiedControlDecision?.exposure
                ?.takeIf {
                    it.authority == UnifiedExposureAuthority.PLATFORM_AE ||
                        it.authority == UnifiedExposureAuthority.USER_ISO_PRIORITY ||
                        it.authority == UnifiedExposureAuthority.USER_SHUTTER_PRIORITY
                }
                ?.exposureCompensationIndex
                ?: legacyRequestedExposureCompensation
            builder[CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION] = requestedExposureCompensation
            lastRequestedNdrExposureCompensationIndex =
                requestedExposureCompensation.takeIf { hdrHighlightProtectActive }
            lastRequestedSdrExposureCompensationIndex =
                requestedExposureCompensation.takeIf { sdrHighlightProtectActive }
            appliedProcessedVideoQualityEvidence = appliedProcessedVideoQualityEvidence + mapOf(
                "ndrHighlightProtectActive" to hdrHighlightProtectActive.toString(),
                "ndrAdaptiveHighlightActive" to
                    (hdrHighlightProtectActive && NdrAdaptiveHighlightFlag.enabled).toString(),
                "ndrAdaptiveHighlightExtraStops" to hdrAdaptiveHighlightAppliedStops.toEvidenceDecimal(),
                "ndrSensorLinearStops" to sensorLinearNdrExposureAppliedStops.toEvidenceDecimal(),
                "ndrExposureSource" to if (rawSensorNdrExposureActive) "RAW_SENSOR" else "ANALYSIS_YUV",
                "ndrExposureAnalysisFresh" to if (rawSensorNdrExposureActive) {
                    (sensorLinearNdrExposureMetricsAgeMs() <=
                        SensorLinearNdrExposurePolicy.LIVE_METRICS_MAX_AGE_MS).toString()
                } else {
                    hdrExposureMetricsFresh().toString()
                },
                "ndrRedClipHighlightActive" to
                    (hdrHighlightProtectActive && NdrRedClipHighlightFlag.enabled).toString(),
                "sdrAdaptiveHighlightActive" to sdrHighlightProtectActive.toString(),
                "sdrAdaptiveHighlightStops" to sdrAdaptiveHighlightAppliedStops.toEvidenceDecimal(),
                "sdrExposureAnalysisFresh" to sdrExposureMetricsFresh().toString()
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
            unifiedControlDecision?.exposure?.let { exposure ->
                if (exposure.authority == UnifiedExposureAuthority.USER_ISO_PRIORITY ||
                    exposure.authority == UnifiedExposureAuthority.USER_SHUTTER_PRIORITY
                ) {
                    applyUnifiedAePriorityExposure(builder, exposure)
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

    @SuppressLint("NewApi")
    private fun supportsCompleteCctWhiteBalanceRequest(): Boolean {
        if (Build.VERSION.SDK_INT < 36 || !supportsCctWhiteBalance) return false
        return cachedColorCorrectionModes.contains(CameraMetadata.COLOR_CORRECTION_MODE_CCT) &&
            supportsCaptureRequestKey(CaptureRequest.COLOR_CORRECTION_MODE) &&
            supportsCaptureRequestKey(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE) &&
            supportsCaptureRequestKey(CaptureRequest.COLOR_CORRECTION_COLOR_TINT)
    }

    /** Remove every mutually-exclusive WB payload before applying the final plan to the reused builder. */
    @SuppressLint("NewApi")
    private fun clearWhiteBalanceRequestOverrides(builder: CaptureRequest.Builder) {
        if (supportsCaptureRequestKey(CaptureRequest.COLOR_CORRECTION_MODE)) {
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, null)
        }
        if (supportsCaptureRequestKey(CaptureRequest.COLOR_CORRECTION_GAINS)) {
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, null)
        }
        if (supportsCaptureRequestKey(CaptureRequest.COLOR_CORRECTION_TRANSFORM)) {
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, null)
        }
        if (Build.VERSION.SDK_INT >= 36) {
            if (supportsCaptureRequestKey(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE)) {
                builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, null)
            }
            if (supportsCaptureRequestKey(CaptureRequest.COLOR_CORRECTION_COLOR_TINT)) {
                builder.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, null)
            }
        }
    }

    /**
     * Resolve the whole pre-CCT payload before AWB is disabled. MANUAL_POST_PROCESSING, all request
     * keys, the mode contract, and one finite device-calibration gains+CCM solve are mandatory.
     */
    private fun resolveManualWhiteBalanceTransform(
        requestedKelvin: Int,
        requestedTint: Int
    ): CinemaManualWhiteBalanceTransform? {
        val transformModeSupported = supportsManualColorCorrection &&
            supportsCaptureRequestKey(CaptureRequest.COLOR_CORRECTION_MODE) &&
            supportsCaptureRequestKey(CaptureRequest.COLOR_CORRECTION_GAINS) &&
            supportsCaptureRequestKey(CaptureRequest.COLOR_CORRECTION_TRANSFORM) &&
            (Build.VERSION.SDK_INT < 36 ||
                cachedColorCorrectionModes.contains(CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX))
        if (!transformModeSupported || !DeviceTrueWhiteBalanceFlag.enabled) return null
        return CinemaManualWhiteBalanceTransformPolicy.fromDeviceCalibration(
            deviceColorCalibration?.solve(requestedKelvin, requestedTint)
        )
    }

    /** Apply one already-validated atomic gains+CCM payload; this function has no partial fallback. */
    private fun applyManualTransformMatrixWhiteBalance(
        builder: CaptureRequest.Builder,
        transform: CinemaManualWhiteBalanceTransform
    ) {
        val green = 1f
        builder[CaptureRequest.COLOR_CORRECTION_GAINS] =
            RggbChannelVector(transform.redGain, green, green, transform.blueGain)
        builder[CaptureRequest.COLOR_CORRECTION_TRANSFORM] =
            toColorSpaceTransform(transform.colorCorrectionTransform)
        maybeLogDeviceTrueWhiteBalance(transform)
    }

    /** Row-major 3×3 [FloatArray] → Camera2 [ColorSpaceTransform] (rationals over a fixed denominator). */
    private fun toColorSpaceTransform(m: FloatArray): ColorSpaceTransform {
        val den = 1_000_000
        val elements = Array(9) { i -> Rational((m[i] * den).roundToInt(), den) }
        return ColorSpaceTransform(elements)
    }

    private fun maybeLogDeviceTrueWhiteBalance(wb: CinemaManualWhiteBalanceTransform) {
        val signature = "${wb.redGain}/${wb.blueGain}/${wb.colorCorrectionTransform.contentHashCode()}"
        if (signature == lastLoggedDeviceTrueWbSignature) return
        lastLoggedDeviceTrueWbSignature = signature
        Log.i(
            TAG,
            "Device-true manual WB gains(r=${wb.redGain}, b=${wb.blueGain}) transform=true"
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
        val gains = currentMeasuredColorSnapshot()?.gains ?: return null
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
     * The converged white point is held through CONTROL_AWB_LOCK so the complete vendor ISP colour
     * pipeline remains active. A capture-result CCM is not a complete replacement for vendor colour
     * rendering and is therefore never replayed as a transient transform-matrix override.
     */
    private fun maybeApplyColorDriftOverride(
        builder: CaptureRequest.Builder,
        plan: VideoColorStabilityPlan,
        manualColorTransformActive: Boolean
    ) {
        val measuredSnapshot = currentMeasuredColorSnapshot()
        val engaged = ColorDriftEnforcementFlag.enabled &&
            !currentRawRequestContract().rawSensorActive &&
            !manualColorTransformActive &&
            plan.requiresLockableAutoWhiteBalance &&
            supportsAutoWhiteBalanceLock &&
            measuredSnapshot?.stabilizationAccepted == true
        if (!engaged) {
            if (colorDriftOverrideActive) {
                colorDriftOverrideActive = false
                Log.i(TAG, "ColorDriftOverride disengaged")
            }
            return
        }
        // Preserve the complete vendor ISP colour transform on every processed frame. Replaying a
        // capture-result CCM through TRANSFORM_MATRIX is not equivalent to the vendor AUTO pipeline:
        // it omits proprietary colour rendering and caused intermittent flat/white-washed frames.
        // A drift spike therefore holds the already-converged vendor solution instead of changing
        // colour-correction mode for only a subset of frames.
        builder[CaptureRequest.CONTROL_AWB_LOCK] = true
        if (!colorDriftOverrideActive) {
            colorDriftOverrideActive = true
            Log.i(
                TAG,
                "ColorDriftOverride engaged label=${plan.evidenceLabel} " +
                    "mode=AWB_LOCK(vendor_color_preserved) " +
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
            val exposurePrioritySupported = supportsAePriorityMode(
                AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY,
                CaptureRequest.SENSOR_EXPOSURE_TIME
            )
            val applied = exposurePrioritySupported && runCatching {
                builder[CaptureRequest.CONTROL_AE_PRIORITY_MODE] =
                    AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY
                builder[CaptureRequest.SENSOR_EXPOSURE_TIME] = exposureTimeNs
            }.onFailure { error ->
                runCatching {
                    builder[CaptureRequest.CONTROL_AE_PRIORITY_MODE] =
                        AE_PRIORITY_MODE_OFF
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

    /**
     * Android 16 priority keys are usable only when both the request keys and the exact priority mode
     * are advertised. Some API-36 devices expose CONTROL_AE_PRIORITY_MODE but publish only OFF; key
     * presence alone is therefore not a valid capability check and can make the HAL reject a request.
     */
    @SuppressLint("NewApi", "InlinedApi")
    private fun supportsAePriorityMode(
        priorityMode: Int,
        controlledSensorKey: CaptureRequest.Key<*>
    ): Boolean =
        Build.VERSION.SDK_INT >= 36 &&
            cachedAePriorityModes.contains(priorityMode) &&
            cachedAeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON) &&
            supportsCaptureRequestKey(CaptureRequest.CONTROL_AE_MODE) &&
            supportsCaptureRequestKey(CaptureRequest.CONTROL_AE_PRIORITY_MODE) &&
            supportsCaptureRequestKey(controlledSensorKey)

    @SuppressLint("NewApi", "InlinedApi")
    private fun clearAePriorityMode(builder: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT < 36 ||
            !supportsCaptureRequestKey(CaptureRequest.CONTROL_AE_PRIORITY_MODE)
        ) {
            return
        }
        runCatching {
            builder[CaptureRequest.CONTROL_AE_PRIORITY_MODE] =
                AE_PRIORITY_MODE_OFF
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear stale AE-priority mode", error)
        }
    }

    /**
     * The preview builder is reused across control changes. Clear sensor keys owned by a previous
     * AE-OFF or priority request before returning authority to platform AE; later timing policies may
     * deliberately write fresh values. This prevents a manual ISO/shutter value from surviving an
     * AUTO transition on vendor HALs that inspect stale builder entries.
     */
    private fun clearAutomaticExposureSensorOverrides(builder: CaptureRequest.Builder) {
        clearAePriorityMode(builder)
        if (supportsCaptureRequestKey(CaptureRequest.SENSOR_SENSITIVITY)) {
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, null)
        }
        if (supportsCaptureRequestKey(CaptureRequest.SENSOR_EXPOSURE_TIME)) {
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null)
        }
        if (supportsCaptureRequestKey(CaptureRequest.SENSOR_FRAME_DURATION)) {
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, null)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            supportsCaptureRequestKey(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST)
        ) {
            builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, null)
        }
    }

    /** Apply one capability-proven Android 16 partial-manual authority as the final exposure writer. */
    @SuppressLint("NewApi", "InlinedApi")
    private fun applyUnifiedAePriorityExposure(
        builder: CaptureRequest.Builder,
        exposure: UnifiedExposureDecision
    ): Boolean {
        val priorityMode: Int
        val controlledKey: CaptureRequest.Key<*>
        when (exposure.authority) {
            UnifiedExposureAuthority.USER_ISO_PRIORITY -> {
                priorityMode = AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY
                controlledKey = CaptureRequest.SENSOR_SENSITIVITY
            }
            UnifiedExposureAuthority.USER_SHUTTER_PRIORITY -> {
                priorityMode = AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY
                controlledKey = CaptureRequest.SENSOR_EXPOSURE_TIME
            }
            else -> return false
        }
        if (!supportsAePriorityMode(priorityMode, controlledKey)) return false

        val applied = runCatching {
            when (exposure.authority) {
                UnifiedExposureAuthority.USER_ISO_PRIORITY -> {
                    val requestedIso = requireNotNull(exposure.sensorSensitivityIso)
                    val effectiveIso = EffectiveIsoRequestPolicy.resolve(
                        requestedIso = requestedIso,
                        sensorLowerIso = isoRange.lower,
                        sensorUpperIso = isoRange.upper,
                        boostLower = postRawSensitivityBoostRange.lower,
                        boostUpper = postRawSensitivityBoostRange.upper
                    )
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null)
                    builder[CaptureRequest.SENSOR_SENSITIVITY] = effectiveIso.sensorIso
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        supportsCaptureRequestKey(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST)
                    ) {
                        builder[CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST] =
                            effectiveIso.postRawSensitivityBoost
                    } else {
                        check(effectiveIso.postRawSensitivityBoost == 100) {
                            "ISO $requestedIso requires unsupported post-RAW sensitivity boost"
                        }
                    }
                }
                UnifiedExposureAuthority.USER_SHUTTER_PRIORITY -> {
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, null)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        supportsCaptureRequestKey(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST)
                    ) {
                        builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, null)
                    }
                    builder[CaptureRequest.SENSOR_EXPOSURE_TIME] =
                        requireNotNull(exposure.exposureTimeNs)
                            .coerceIn(exposureRange.lower, exposureRange.upper)
                }
            }
            builder[CaptureRequest.CONTROL_AE_PRIORITY_MODE] = priorityMode
        }.onFailure { error ->
            clearAePriorityMode(builder)
            if (supportsCaptureRequestKey(CaptureRequest.SENSOR_SENSITIVITY)) {
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, null)
            }
            if (supportsCaptureRequestKey(CaptureRequest.SENSOR_EXPOSURE_TIME)) {
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, null)
            }
            Log.e(TAG, "Failed to apply unified ${exposure.authority}; retaining platform AE", error)
        }.isSuccess

        appliedProcessedVideoQualityEvidence = appliedProcessedVideoQualityEvidence + mapOf(
            "unifiedAePriorityAuthority" to exposure.authority.name,
            "unifiedAePriorityApplied" to applied.toString(),
            "unifiedAePriorityMode" to priorityMode.toString()
        )
        return applied
    }

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

    /**
     * Non-mutating admission query for a user-selected recording size and cadence. This uses the
     * same public and vendor stream-based FPS policy as [setFPS], but substitutes the proposed
     * encoder size so callers can reject an unsupported pair before changing Camera2 or preferences.
     */
    internal fun supportsExactRecordingFps(size: Size, fps: Int): Boolean {
        if (size.width <= 0 || size.height <= 0 || fps <= 0 || cachedFpsIntRanges.isEmpty()) {
            return false
        }
        val streamFpsOverride = CameraStreamBasedFpsPolicy.resolve(
            requestedSize = size.toCameraStreamSize(),
            requestedFps = fps,
            publicAeRanges = cachedFpsIntRanges,
            streamBasedEntries = cachedStreamBasedFpsEntries,
            requestKeyNames = cachedCaptureRequestKeysByName.keys,
            sessionKeyNames = cachedSessionRequestKeysByName.keys,
            preferVendorCadenceLockWhenAvailable = npv10TenBitCaptureEnabled
        )
        val plan = CameraFpsRequestPolicy.resolve(
            requestedFps = fps,
            availableRanges = cachedFpsIntRanges,
            requestedExposureNs = resultFlow.value?.exposureNs ?: 0L,
            exposureRange = CameraLongRange(exposureRange.lower, exposureRange.upper),
            streamBasedFpsOverride = streamFpsOverride
        )
        return plan.requestedFps == fps && plan.appliedFps == fps
    }

    private fun resolveCurrentStreamBasedFpsOverridePlan(
        requestedFpsOverride: Int? = null
    ): CameraStreamBasedFpsOverridePlan =
        CameraStreamBasedFpsPolicy.resolve(
            // NPV10 has no encoder Surface by design: its camera-owned P010 readout is the recording
            // stream. The start path therefore clears recordingEncoderTargetSize before it switches the
            // repeating request to ENCODED_RECORDING_WITH_READOUT. Keep the stream-based FPS contract
            // bound to the real NPV10 capture size so that transition cannot silently relax a fixed
            // 25-25 request back to the public variable 15-30 AE range.
            requestedSize = CameraStreamBasedFpsCaptureSizePolicy.resolve(
                npv10CaptureEnabled = npv10TenBitCaptureEnabled,
                npv10CaptureSize = npv10CaptureTargetSize?.toCameraStreamSize(),
                rawPrearmEnabled = rawVideoPrearmEnabled,
                rawCaptureEnabled = rawVideoCaptureEnabled,
                rawReadoutSize = recordingReadoutOverrideSize?.toCameraStreamSize(),
                encoderSize = recordingEncoderTargetSize?.toCameraStreamSize()
            ),
            requestedFps = requestedFpsOverride ?: requestedCameraFpsHint ?: targetCameraFps,
            publicAeRanges = cachedFpsIntRanges,
            streamBasedEntries = cachedStreamBasedFpsEntries,
            requestKeyNames = cachedCaptureRequestKeysByName.keys,
            sessionKeyNames = cachedSessionRequestKeysByName.keys,
            preferVendorCadenceLockWhenAvailable = npv10TenBitCaptureEnabled
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

    private fun currentRawRequestContract(): CameraRawRequestContract =
        CameraRawRequestContractPolicy.resolve(
            recordingActive = recordingActive,
            rawVideoCaptureEnabled = rawVideoCaptureEnabled,
            rawVideoPrearmEnabled = rawVideoPrearmEnabled,
            sensorLinearNdrCaptureEnabled = sensorLinearNdrCaptureEnabled
        )

    /**
     * Maps runtime transport ownership to the operator-visible video mode. Sensor-linear NDR is
     * intentionally evaluated before RAW_SENSOR ownership: its RAW input is an NDR source, not a
     * RAW-original delivery mode.
     */
    private fun currentUnifiedVideoControlMode(
        rawRequestContract: CameraRawRequestContract
    ): UnifiedVideoControlMode = UnifiedVideoControlMode.fromRuntime(
        cinemaMode = when {
            rawRequestContract.rawOriginalCaptureActive -> CinemaVideoMode.RAW
            previewLogLookEnabled -> CinemaVideoMode.LOG
            else -> CinemaVideoMode.PRO
        },
        hdrMode = when {
            rawRequestContract.sensorLinearNdrActive -> "SENSOR_LINEAR_NDR"
            hdrDynamicRangeMode != HdrDynamicRangeMode.SDR || previewHdrLookEnabled ->
                hdrDynamicRangeMode.name
            else -> "OFF"
        },
        // NDR may consume RAW_SENSOR, but only a RAW-original contract owns RAW control semantics.
        rawSensorActive = rawRequestContract.rawOriginalCaptureActive,
        professionalControlsActive = professionalVideoControlsActive
    )

    private fun supportsUnifiedFixedWhiteBalance(
        requestedKelvin: Int?,
        requestedTint: Int
    ): Boolean {
        val kelvin = requestedKelvin ?: return false
        if (!cachedAwbModes.contains(CaptureRequest.CONTROL_AWB_MODE_OFF)) return false
        return supportsCompleteCctWhiteBalanceRequest() ||
            resolveManualWhiteBalanceTransform(kelvin, requestedTint) != null
    }

    private fun currentNpv10CadenceExposurePlan(
        targetFps: Int = targetCameraFps
    ): Npv10CadenceExposurePlan = Npv10CadenceExposurePolicy.resolve(
        npv10TenBitCaptureEnabled = npv10TenBitCaptureEnabled,
        // A byte-buffer encoder also uses a P010 reader but is not the private Cinema master whose
        // producer cadence was measured here. Its non-null encoder target keeps that path untouched.
        privateMasterPipeline = recordingEncoderTargetSize == null,
        sdrProcessedTransport =
            hdrDynamicRangeMode == HdrDynamicRangeMode.SDR &&
                !previewLogLookEnabled &&
                !previewHdrLookEnabled &&
                !previewRawLookEnabled &&
                !sensorLinearNdrCaptureEnabled,
        captureWidth = npv10CaptureTargetSize?.width,
        captureHeight = npv10CaptureTargetSize?.height,
        targetFps = targetFps,
        supportsManualSensor = supportsManualSensor,
        manualIso = manIso,
        manualExposureTimeNs = manExpNs
    )

    private fun softwareAePriorityRequestedForCurrentControls(): Boolean {
        if (!supportsManualSensor) return false
        if (currentNpv10CadenceExposurePlan().active) return true
        return when {
            manIso > 0 && manExpNs <= 0L -> !supportsAePriorityMode(
                AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY,
                CaptureRequest.SENSOR_SENSITIVITY
            )
            manIso <= 0 && manExpNs > 0L -> !supportsAePriorityMode(
                AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY,
                CaptureRequest.SENSOR_EXPOSURE_TIME
            )
            else -> false
        }
    }

    override fun softwareAePriorityMeterRequirement(): SoftwareAePriorityMeterRequirement =
        synchronized(softwareAeControlStateLock) {
            SoftwareAePriorityMeterRequirement(
                required = softwareAePriorityRequestedForCurrentControls(),
                controlRevision = softwareAeControlRevision.get()
            )
        }

    override fun softwareAeControlRevisionFor(result: TotalCaptureResult): Long =
        softwareAeControlRevisionFor(result.request)

    override fun isSoftwareAePriorityMeterRequired(): Boolean =
        softwareAePriorityMeterRequirement().required

    private fun invalidateSoftwareAeMeterContext() {
        backgroundHandler?.removeCallbacks(softwareAePriorityStaleReleaseRunnable)
        synchronized(softwareAeMeterLock) {
            latestSoftwareAeSceneSample = null
            softwareAeMeterContext = null
            softwareAeMeterNotBeforeElapsedMs = SystemClock.elapsedRealtime()
            softwareAePriorityController.reset()
        }
        lastSoftwareAePriorityLogSignature = null
    }

    /** Accept only a meter sample proven to belong to the active Camera2 session and control domain. */
    private fun acceptSoftwareAeSceneSample(
        sample: SoftwareAeSceneSample,
        sourceSessionGeneration: Long
    ): Boolean {
        val requirement = softwareAePriorityMeterRequirement()
        if (!requirement.required) return false
        // Sensor-linear samples must prove which immutable Camera2 request produced them. Processed
        // YUV analysis has no CaptureResult join and therefore retains its existing current-context
        // contract; RAW/NDR never falls back to that weaker authority.
        if (sample.domain == SoftwareAeSignalDomain.SENSOR_LINEAR_RAW &&
            sample.controlRevision < 0L
        ) return false
        if (sample.controlRevision >= 0L &&
            sample.controlRevision != requirement.controlRevision
        ) return false
        val activeGeneration = sessionGeneration.get()
        if (sourceSessionGeneration >= 0L && sourceSessionGeneration != activeGeneration) return false
        val activeMode = currentUnifiedVideoControlMode(currentRawRequestContract())
        val accepted = synchronized(softwareAeMeterLock) {
            val context = softwareAeMeterContext
            if (context != null && (
                        context.sessionGeneration != activeGeneration ||
                        context.mode != activeMode ||
                        context.domain != sample.domain ||
                        context.controlRevision != requirement.controlRevision
                    )
            ) {
                false
            } else if (sample.measuredAtElapsedRealtimeMs < softwareAeMeterNotBeforeElapsedMs) {
                false
            } else if (sample.sensorTimestampNs > 0L &&
                latestSoftwareAeSceneSample?.sensorTimestampNs?.let { it >= sample.sensorTimestampNs } == true
            ) {
                false
            } else {
                latestSoftwareAeSceneSample = sample
                true
            }
        }
        if (accepted) scheduleSoftwareAePriorityStaleRelease(sample)
        return accepted
    }

    private fun scheduleSoftwareAePriorityStaleRelease(sample: SoftwareAeSceneSample) {
        val handler = backgroundHandler ?: return
        handler.removeCallbacks(softwareAePriorityStaleReleaseRunnable)
        val ageMs = (SystemClock.elapsedRealtime() - sample.measuredAtElapsedRealtimeMs)
            .coerceAtLeast(0L)
        val delayMs = (SoftwareAePriorityController.MAX_SAMPLE_AGE_MS - ageMs + 1L)
            .coerceAtLeast(1L)
        handler.postDelayed(softwareAePriorityStaleReleaseRunnable, delayMs)
    }

    private fun releaseStaleSoftwareAePriorityAuthority() {
        if (!softwareAePriorityRequestedForCurrentControls()) {
            invalidateSoftwareAeMeterContext()
            return
        }
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val sample = synchronized(softwareAeMeterLock) { latestSoftwareAeSceneSample } ?: return
        val ageMs = (nowElapsedMs - sample.measuredAtElapsedRealtimeMs).coerceAtLeast(0L)
        if (ageMs <= SoftwareAePriorityController.MAX_SAMPLE_AGE_MS) {
            scheduleSoftwareAePriorityStaleRelease(sample)
            return
        }
        val released = synchronized(softwareAeMeterLock) {
            if (latestSoftwareAeSceneSample !== sample) {
                false
            } else {
                latestSoftwareAeSceneSample = null
                true
            }
        }
        if (released) {
            lastSoftwareAePriorityLogSignature = null
            // Rebuild once so AE-OFF and both sensor keys are cleared immediately. Waiting for an
            // unrelated UI event would leave the final software exposure pair frozen indefinitely.
            updatePreviewFromAutonomousControlFeedback()
        }
    }

    /**
     * Resolve the app-owned complementary exposure axis only when the HAL cannot provide the exact
     * Android 16 priority mode. Processed modes require the configured YUV meter; RAW/NDR modes
     * require timestamped sensor-linear metrics. No fresh meter means no software authority.
     */
    private fun resolveSoftwareAePriorityDecision(
        rawRequestContract: CameraRawRequestContract,
        mode: UnifiedVideoControlMode,
        detailProfile: VideoDetailProfile,
        targetFps: Int,
        frameExposureBudgetNs: Long,
        latest: LiveResult?
    ): SoftwareAePriorityDecision? {
        val cadenceExposurePlan = currentNpv10CadenceExposurePlan(targetFps)
        val axis = when {
            manIso > 0 && manExpNs <= 0L -> SoftwareAePriorityAxis.ISO
            manIso <= 0 && manExpNs > 0L -> SoftwareAePriorityAxis.SHUTTER
            cadenceExposurePlan.active -> SoftwareAePriorityAxis.SHUTTER
            else -> {
                if (softwareAeMeterContext != null || latestSoftwareAeSceneSample != null) {
                    invalidateSoftwareAeMeterContext()
                } else {
                    softwareAePriorityController.reset()
                }
                return null
            }
        }
        val hardwarePriorityAvailable = when (axis) {
            SoftwareAePriorityAxis.ISO -> supportsAePriorityMode(
                AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY,
                CaptureRequest.SENSOR_SENSITIVITY
            )
            SoftwareAePriorityAxis.SHUTTER -> supportsAePriorityMode(
                AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY,
                CaptureRequest.SENSOR_EXPOSURE_TIME
            )
        }
        // Use the app-owned luma loop for this guard even on a future HAL that advertises exposure
        // priority. The measured failure requires a deterministic 25 ms sensor ceiling, and the same
        // request/result evidence must prove it across vendors.
        if ((hardwarePriorityAvailable && !cadenceExposurePlan.active) || !supportsManualSensor) {
            if (softwareAeMeterContext != null || latestSoftwareAeSceneSample != null) {
                invalidateSoftwareAeMeterContext()
            } else {
                softwareAePriorityController.reset()
            }
            return null
        }

        val requiredDomain = if (rawRequestContract.rawSensorActive) {
            SoftwareAeSignalDomain.SENSOR_LINEAR_RAW
        } else {
            SoftwareAeSignalDomain.PROCESSED_YUV
        }
        val analysisAvailable = when (requiredDomain) {
            // During NPV10 ownership the recorder consumes the P010 Image before ordinary scene
            // analysis. Its allocation-free cadence meter publishes the same processed-luma contract,
            // including while thermal isolation suspends nonessential preview analysis.
            SoftwareAeSignalDomain.PROCESSED_YUV ->
                previewAnalysisRequested || cadenceExposurePlan.active
            SoftwareAeSignalDomain.SENSOR_LINEAR_RAW -> rawRequestContract.rawSensorActive
        }
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val controlRevision = softwareAePriorityMeterRequirement().controlRevision
        val expectedContext = SoftwareAeMeterContext(
            sessionGeneration = sessionGeneration.get(),
            mode = mode,
            domain = requiredDomain,
            controlRevision = controlRevision
        )
        var contextChanged = false
        val sample = synchronized(softwareAeMeterLock) {
            if (softwareAeMeterContext != expectedContext) {
                softwareAeMeterContext = expectedContext
                softwareAeMeterNotBeforeElapsedMs = nowElapsedMs
                latestSoftwareAeSceneSample = null
                softwareAePriorityController.reset()
                contextChanged = true
                null
            } else {
                latestSoftwareAeSceneSample?.takeIf {
                    analysisAvailable &&
                        it.domain == requiredDomain &&
                        it.measuredAtElapsedRealtimeMs >= softwareAeMeterNotBeforeElapsedMs
                }
            }
        }
        if (contextChanged) {
            backgroundHandler?.removeCallbacks(softwareAePriorityStaleReleaseRunnable)
            lastSoftwareAePriorityLogSignature = null
        }
        val decision = softwareAePriorityController.resolve(
            request = SoftwareAePriorityRequest(
                sessionGeneration = sessionGeneration.get(),
                mode = mode,
                detailProfile = detailProfile,
                axis = axis,
                fixedIso = manIso.takeIf { axis == SoftwareAePriorityAxis.ISO && it > 0 },
                fixedExposureTimeNs = when {
                    axis != SoftwareAePriorityAxis.SHUTTER -> null
                    cadenceExposurePlan.active -> cadenceExposurePlan.fixedExposureTimeNs
                    manExpNs > 0L -> manExpNs
                    else -> null
                },
                requestedEvStops =
                    evIndex.coerceIn(evRange.lower, evRange.upper) * evCompensationStepStops,
                meteredExposureIncludesRequestedEv = cadenceExposurePlan.active,
                lockComplementaryAxisAfterInitialization = cadenceExposurePlan.active,
                // RAW luminance must stay paired with the exact frame's exposure product. The global
                // LiveResult can be one callback behind or ahead of the analytics executor.
                meteredIso = when (requiredDomain) {
                    SoftwareAeSignalDomain.SENSOR_LINEAR_RAW -> sample?.meteredIso
                    SoftwareAeSignalDomain.PROCESSED_YUV -> latest?.iso?.takeIf { it > 0 }
                },
                meteredExposureTimeNs = when (requiredDomain) {
                    SoftwareAeSignalDomain.SENSOR_LINEAR_RAW -> sample?.meteredExposureTimeNs
                    SoftwareAeSignalDomain.PROCESSED_YUV -> latest?.exposureNs?.takeIf { it > 0L }
                },
                isoRange = CameraIntRange(effectiveIsoRange.lower, effectiveIsoRange.upper),
                exposureTimeRangeNs = CameraLongRange(exposureRange.lower, exposureRange.upper),
                frameExposureBudgetNs = frameExposureBudgetNs.coerceAtMost(
                    (1_000_000_000L / targetFps.coerceAtLeast(1)).coerceAtLeast(1L)
                )
            ),
            sample = sample,
            nowElapsedRealtimeMs = nowElapsedMs
        )
        val signature = "${decision.axis}|${decision.ready}|${decision.reason}|" +
            "${decision.sensorSensitivityIso}|${decision.exposureTimeNs}|${decision.revision}"
        if (signature != lastSoftwareAePriorityLogSignature) {
            lastSoftwareAePriorityLogSignature = signature
            Log.i(
                TAG,
                "Software AE priority axis=${decision.axis} ready=${decision.ready} " +
                    "iso=${decision.sensorSensitivityIso} exposureNs=${decision.exposureTimeNs} " +
                    "targetLuma=${decision.targetLuma.toEvidenceDecimal()} " +
                    "measuredLuma=${decision.measuredLuma.toEvidenceDecimal()} " +
                    "correctionStops=${decision.correctionStops.toEvidenceDecimal()} " +
                    "saturated=${decision.saturated} revision=${decision.revision} " +
                    "reason=${decision.reason} cadenceGuard=${cadenceExposurePlan.active} " +
                    "cadenceGuardReason=${cadenceExposurePlan.reason}"
            )
        }
        return decision
    }

    /** Rebuild only when a fresh frame changed the bounded complementary axis or invalidated it. */
    private fun maybeRefreshSoftwareAePriority() {
        val rawRequestContract = currentRawRequestContract()
        val targetFps = CameraFpsRequestPolicy.previewOrRecordRequestedFps(
            videoRecordingRequestActive = rawRequestContract.recordingActive ||
                previewContinuityRecordSurfaceEnabled,
            previewCadenceFps = previewCadenceFps,
            recordFps = targetCameraFps
        ).coerceAtLeast(1)
        val frameBudget = (1_000_000_000L / targetFps.toLong())
            .coerceAtMost(maxFrameDurationNs.coerceAtLeast(1L))
        val decision = resolveSoftwareAePriorityDecision(
            rawRequestContract = rawRequestContract,
            mode = currentUnifiedVideoControlMode(rawRequestContract),
            detailProfile = selectedVideoDetailProfile,
            targetFps = targetFps,
            frameExposureBudgetNs = frameBudget,
            latest = resultFlow.value
        )
        if (decision?.changed == true) updatePreviewFromAutonomousControlFeedback()
    }

    /**
     * Builds one immutable WB/exposure/focus ownership decision for a live video request. Existing
     * NDR/RAW measurements are inputs to this coordinator; they never become competing request-key
     * writers. Still-photo requests do not call this method.
     */
    private fun resolveUnifiedVideoControlDecision(
        rawRequestContract: CameraRawRequestContract,
        videoRecordingRequestActive: Boolean,
        manualWhiteBalanceAllowed: Boolean,
        effectiveManualFocusDistance: Float,
        autoWhiteBalanceConverged: Boolean,
        highlightProtectionBiasStops: Float,
        sensorLinearNdrBiasStops: Float,
        previousExposureCompensationIndex: Int?
    ): UnifiedVideoControlDecision {
        val latest = resultFlow.value
        val targetFps = CameraFpsRequestPolicy.previewOrRecordRequestedFps(
            videoRecordingRequestActive = videoRecordingRequestActive,
            previewCadenceFps = previewCadenceFps,
            recordFps = targetCameraFps
        ).coerceAtLeast(1)
        val frameExposureBudgetNs = (1_000_000_000L / targetFps.toLong())
            .coerceAtMost(maxFrameDurationNs.coerceAtLeast(1L))
        val mode = currentUnifiedVideoControlMode(rawRequestContract)
        val controlSessionGeneration = sessionGeneration.get()
        val hardwareIsoPrioritySupported = supportsAePriorityMode(
            AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY,
            CaptureRequest.SENSOR_SENSITIVITY
        )
        val hardwareShutterPrioritySupported = supportsAePriorityMode(
            AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY,
            CaptureRequest.SENSOR_EXPOSURE_TIME
        )
        val cadenceExposurePlan = currentNpv10CadenceExposurePlan(targetFps)
        val softwarePriorityDecision = resolveSoftwareAePriorityDecision(
            rawRequestContract = rawRequestContract,
            mode = mode,
            detailProfile = selectedVideoDetailProfile,
            targetFps = targetFps,
            frameExposureBudgetNs = frameExposureBudgetNs,
            latest = latest
        )
        val nominalFrameIntervalSeconds = 1f / targetFps.toFloat()
        val latestSensorTimestampNs = latest?.timestampNs?.takeIf { it > 0L }
        val focusResultFresh = latestSensorTimestampNs != null &&
            lastPreviewFrameCallbackElapsedNs > 0L &&
            SystemClock.elapsedRealtimeNanos() - lastPreviewFrameCallbackElapsedNs <=
                maxOf(250_000_000L, (4_000_000_000L / targetFps.toLong()))
        val previousFocusDistance = latest?.focusDist?.takeIf { distance ->
            focusResultFresh &&
                distance.isFinite() &&
                distance >= 0f &&
                minFocusDist > 0f &&
                distance <= minFocusDist
        }
        val measuredDecisionIntervalSeconds = latestSensorTimestampNs?.let { timestampNs ->
            val previousTimestampNs = lastUnifiedVideoControlSensorTimestampNs
            (timestampNs - previousTimestampNs)
                .takeIf {
                    lastUnifiedVideoControlSessionGeneration == controlSessionGeneration &&
                    lastUnifiedVideoControlMode == mode &&
                        previousTimestampNs > 0L &&
                        it > 0L
                }
                ?.div(1_000_000_000.0)
                ?.toFloat()
                ?.takeIf(Float::isFinite)
        }
        val focusDecisionIntervalSeconds = measuredDecisionIntervalSeconds
            ?.coerceIn(nominalFrameIntervalSeconds, 0.25f)
            ?: nominalFrameIntervalSeconds
        val measuredColorSignal = currentMeasuredColorSnapshot()?.takeIf { snapshot ->
            snapshot.stabilizationAccepted &&
                latestSensorTimestampNs != null &&
                snapshot.provenance.sensorTimestampNs == latestSensorTimestampNs
        }
        val measuredWhitePoint = measuredColorSignal?.let {
            deviceTrueShotStableFallbackWhitePoint()
        }
        val currentAiFocusSignal = aiFocusControlSignal.takeIf {
            it.isCurrent(controlSessionGeneration)
        }
        val requestedWhiteBalanceKelvin = manWbK
            .takeIf { manualWhiteBalanceAllowed && it > 0 }
        val requestedManualSensor = manIso > 0 || manExpNs > 0L
        val decision = UnifiedVideoControlCoordinator.resolve(
            UnifiedVideoControlRequest(
                generation = controlSessionGeneration,
                mode = mode,
                detailProfile = selectedVideoDetailProfile,
                targetFps = targetFps,
                frameExposureBudgetNs = frameExposureBudgetNs,
                recording = videoRecordingRequestActive,
                limits = UnifiedVideoSensorLimits(
                    isoRange = CameraIntRange(effectiveIsoRange.lower, effectiveIsoRange.upper),
                    exposureTimeRangeNs = CameraLongRange(exposureRange.lower, exposureRange.upper),
                    exposureCompensationRange = CameraIntRange(evRange.lower, evRange.upper),
                    exposureCompensationStepStops = evCompensationStepStops,
                    whiteBalanceRangeKelvin = CameraIntRange(
                        manualWhiteBalanceRange.lower,
                        manualWhiteBalanceRange.upper
                    ),
                    maxFocusDistanceDiopters = minFocusDist,
                    supportsManualSensor = supportsManualSensor,
                    supportsFixedWhiteBalance = supportsUnifiedFixedWhiteBalance(
                        requestedKelvin = requestedWhiteBalanceKelvin,
                        requestedTint = manWbTint
                    ),
                    supportsManualFocusDistance =
                        minFocusDist > 0f &&
                            supportsCaptureRequestKey(CaptureRequest.LENS_FOCUS_DISTANCE),
                    supportsIsoPriority = hardwareIsoPrioritySupported,
                    supportsShutterPriority = hardwareShutterPrioritySupported,
                    supportsSoftwareIsoPriority =
                        softwarePriorityDecision?.ready == true &&
                            softwarePriorityDecision.axis == SoftwareAePriorityAxis.ISO,
                    supportsSoftwareShutterPriority =
                        softwarePriorityDecision?.ready == true &&
                            softwarePriorityDecision.axis == SoftwareAePriorityAxis.SHUTTER
                ),
                manual = UnifiedVideoManualControls(
                    iso = manIso.takeIf { it > 0 },
                    exposureTimeNs = manExpNs.takeIf { it > 0L },
                    exposureCompensationIndex = evIndex,
                    // Auto AE treats EV as its base and retains NDR/SDR protective bias. With one
                    // manual sensor axis, EV is realized only on the automatic axis; with both axes
                    // explicitly fixed, the coordinator rejects a contradictory EV write.
                    exposureCompensationOverrideActive = requestedManualSensor,
                    whiteBalanceKelvin = requestedWhiteBalanceKelvin,
                    whiteBalanceTint = manWbTint,
                    focusDistanceDiopters =
                        effectiveManualFocusDistance.takeIf { it >= 0f },
                    focusOverrideActive = effectiveManualFocusDistance >= 0f
                ),
                automatic = UnifiedVideoAutomaticSignals(
                    meteredIso = latest?.iso?.takeIf { it > 0 },
                    meteredExposureTimeNs = latest?.exposureNs?.takeIf { it > 0L },
                    highlightProtectionBiasStops = highlightProtectionBiasStops,
                    sensorLinearNdrBiasStops = sensorLinearNdrBiasStops,
                    measuredWhiteBalanceKelvin = measuredWhitePoint?.first,
                    measuredWhiteBalanceTint = measuredWhitePoint?.second ?: 0,
                    measuredWhiteBalanceConfidence = measuredColorSignal
                        ?.awbConfidence
                        ?.takeIf(Float::isFinite)
                        ?.coerceIn(0f, 1f)
                        ?: 0f,
                    autoWhiteBalanceConverged = autoWhiteBalanceConverged,
                    aiFocusDistanceDiopters = currentAiFocusSignal?.distanceDiopters,
                    aiFocusConfidence = currentAiFocusSignal?.confidence ?: 0f,
                    previousExposureCompensationIndex = previousExposureCompensationIndex,
                    previousFocusDistanceDiopters = previousFocusDistance,
                    elapsedSincePreviousDecisionSeconds = focusDecisionIntervalSeconds,
                    softwareIsoPriorityExposureTimeNs = softwarePriorityDecision
                        ?.takeIf { it.ready && it.axis == SoftwareAePriorityAxis.ISO }
                        ?.exposureTimeNs,
                    softwareShutterPriorityIso = softwarePriorityDecision
                        ?.takeIf { it.ready && it.axis == SoftwareAePriorityAxis.SHUTTER }
                        ?.sensorSensitivityIso,
                    cadenceGuardExposureTimeNs = cadenceExposurePlan.fixedExposureTimeNs
                        ?.takeIf {
                            softwarePriorityDecision?.ready == true &&
                                softwarePriorityDecision.axis == SoftwareAePriorityAxis.SHUTTER
                        },
                    cadenceGuardSensorSensitivityIso = softwarePriorityDecision
                        ?.takeIf {
                            cadenceExposurePlan.active &&
                                it.ready &&
                                it.axis == SoftwareAePriorityAxis.SHUTTER
                        }
                        ?.sensorSensitivityIso
                )
            )
        )
        lastUnifiedVideoControlSensorTimestampNs = latestSensorTimestampNs ?: 0L
        lastUnifiedVideoControlMode = mode
        lastUnifiedVideoControlSessionGeneration = controlSessionGeneration
        return decision
    }

    private fun isCinemaLowProcessingRequestActive(): Boolean =
        cinemaFidelityOverridesEnabled || previewLogLookEnabled ||
            currentRawRequestContract().rawSensorActive

    private fun isConsumerAutoVideoRequest(forStillCapture: Boolean): Boolean =
        !forStillCapture &&
            !isCinemaLowProcessingRequestActive() &&
            hdrDynamicRangeMode == HdrDynamicRangeMode.SDR

    private fun manualSensorTimingActive(): Boolean =
        supportsManualSensor && (manIso > 0 || manExpNs > 0L)

    private fun resolveCamera2PipelineRequestPlan(
        plan: CinemaStreamGraphPlan? = null
    ): Camera2PipelineRequestPlan =
        (plan ?: currentCinemaStreamGraphPlan()).let { streamGraph ->
            Camera2PipelineRequestPolicy.resolve(
                recordingActive = recordingActive || processedRecordingSessionPreparationActive,
                videoPreviewActive = videoPreviewActive,
                rawVideoCaptureEnabled = rawVideoCaptureEnabled,
                rawVideoPrearmEnabled = rawVideoPrearmEnabled,
                sensorLinearNdrCaptureEnabled = sensorLinearNdrCaptureEnabled,
                rawPreviewActive = rawPreviewCaptureEnabled,
                // The same flag also identifies an idle native-P010 prearm consumer. The request
                // policy uses it only to decide whether a configured standby P010 output is live;
                // recordingActive remains the gate for the recording pipeline contract.
                uncompressedReadoutRecording =
                    npv10TenBitCaptureEnabled && streamGraph.yuvAnalysis.includeInSession,
                previewAnalysisRequested =
                    previewAnalysisRequested && streamGraph.yuvAnalysis.includeInSession,
                recordingThermalAnalysisIsolationEnabled =
                    recordingThermalAnalysisIsolationEnabled,
                hasRawReader = rawReader != null && streamGraph.rawSensor.includeInSession,
                hasYuvReader = yuvReader != null && streamGraph.yuvAnalysis.includeInSession,
                yuvReaderConfiguredAsStandby =
                    streamGraph.yuvAnalysis.reason == MODE_SWITCH_YUV_STANDBY_REASON,
                recordingReadoutOverrideSize = recordingReadoutOverrideSize,
                analysisTargetSize = analysisTargetSize,
                readoutSidecarMode = readoutSidecarMode,
                requestedFps = targetCameraFps,
                availableAntibandingModes = cachedAntibandingModes,
                manualExposureActive = manualSensorTimingActive()
            )
        }

    private fun logNiv99AnalysisRoute(
        streamGraph: CinemaStreamGraphPlan,
        requestPlan: Camera2PipelineRequestPlan
    ) {
        val yuvRoute = streamGraph.yuvAnalysis
        val signature = buildString {
            append("camera=").append(currentCameraId)
            append(";generation=").append(sessionGeneration.get())
            append(";client=").append(clientPreviewAnalysisRequested)
            append(";effective=").append(previewAnalysisRequested)
            append(";thermalIsolated=").append(recordingThermalAnalysisIsolationEnabled)
            append(";recording=").append(recordingActive)
            append(";raw=").append(rawVideoCaptureEnabled)
            append(";rawPreview=").append(rawPreviewCaptureEnabled)
            append(";sensorLinearNdr=").append(sensorLinearNdrCaptureEnabled)
            append(";npv10=").append(npv10TenBitCaptureEnabled)
            append(";standbyAllowed=").append(modeSwitchStandbyOutputsAllowed())
            append(";inSession=").append(yuvRoute.includeInSession)
            append(";reader=").append(yuvReader != null)
            append(";target=").append(requestPlan.targets.includeYuvReadout)
            append(";route=").append(yuvRoute.reason)
        }
        if (signature == lastNiv99AnalysisRouteSignature) return
        lastNiv99AnalysisRouteSignature = signature
        NeoLogger.i(TAG, "NIV99_ANALYSIS_ROUTE $signature")
    }

    override fun releaseCameraResources(clearPreviewSurface: Boolean) {
        if (!releaseCameraResourcesWithProof(clearPreviewSurface)) {
            Log.e(TAG, "Camera resources released with ImageReaders quarantined for guarded retry")
        }
    }

    internal fun releaseCameraResourcesWithProof(clearPreviewSurface: Boolean): Boolean {
        advanceSessionGeneration("camera-resources-release")
        captureResultSessionBinding.set(null)
        cancelTapAfScanForSessionTransition()
        pendingPreviewRebuild.set(false)
        pendingRepeatingRequestRefresh.set(false)
        clearSessionConfigurationFingerprints()
        try { captureSession?.close() } catch (e: Exception) {
            Log.w(TAG, "Failed to close capture session during resource release", e)
        }
        try { cameraDevice?.close() } catch (e: Exception) {
            Log.w(TAG, "Failed to close camera device during resource release", e)
        }
        synchronized(sessionCollectionsLock) {
            configuredSessionSurfaces.clear()
            configuredSessionGenerations.clear()
        }
        val deferredReadersClosed = closeAllDeferredReadersSafely("camera resources released")
        val allReadersClosed = closeReaderSetsWithPipelineBarrier(
            requested = listOf(currentReaderSet()),
            reason = "current camera resources released"
        )
        captureSession = null
        appliedDynamicRangeSessionSnapshot = null
        lastAppliedPreviewHdrProfile = null
        lastAppliedEncoderHdrProfile = null
        previewBuilder = null
        previewBuilderHasTargets = false
        previewBuilderTargetSnapshot = emptyList()
        cameraDevice = null
        jpegReader = null
        rawReader = null
        yuvReader = null
        hardwareImportReader = null
        // Frame timestamps belong to the released Camera2 session. Keeping them across a deliberate
        // Settings pause reports the off-time as a preview stall and pollutes FPS/health telemetry.
        lastPreviewFrameCallbackElapsedNs = 0L
        previousCaptureTimestampNs = 0L
        latestCaptureTimestampNs = 0L
        recordingActive = false
        processedRecordingSessionPreparationActive = false
        reconcileNiv99MetadataIngressPriority("camera-resources-release")
        reconcileNiv99ImageIngressPriority("camera-resources-release")
        activeStillCaptureToken = null
        maximumResolutionStillCaptureToken = null
        pausedRepeatingStillCaptureToken = null
        maximumResolutionStillCaptureActive = false
        p010CadenceStillCaptureActive = false
        stillCaptureSessionRestorationPending = false
        unconfiguredRecordSurfaceRecoveryPending = false
        previewWarmupSurface = null
        if (clearPreviewSurface) {
            replacePreviewSessionSurface(null)
            replacePersistentPreviewSessionSurfaces(null, null)
            persistentPreviewProfilesCanShareRequest = false
        }
        val quarantineEmpty = synchronized(sessionCollectionsLock) {
            quarantinedReaderCloseBatches.isEmpty()
        }
        if (!deferredReadersClosed && allReadersClosed && quarantineEmpty) {
            Log.w(TAG, "Deferred ImageReader close required a guarded retry before release completed")
        }
        return allReadersClosed && quarantineEmpty
    }

    override fun prepareCameraSessionForDeviceRecovery() {
        advanceSessionGeneration("camera-device-recovery")
        val staleSession = captureSession
        clearCaptureResultSessionBinding(staleSession)
        captureSession = null
        pendingPreviewRebuild.set(false)
        pendingRepeatingRequestRefresh.set(false)
        clearSessionRefreshOwnership()
        pendingSessionCreateGeneration = 0L
        pendingSessionCreateStartElapsedMs = 0L
        clearSessionConfigurationFingerprints()
        appliedDynamicRangeSessionSnapshot = null
        lastAppliedPreviewHdrProfile = null
        lastAppliedEncoderHdrProfile = null
        previewBuilder = null
        previewBuilderHasTargets = false
        previewBuilderTargetSnapshot = emptyList()
        if (staleSession != null) {
            synchronized(sessionCollectionsLock) {
                configuredSessionSurfaces.remove(staleSession)
                configuredSessionGenerations.remove(staleSession)
            }
        }
        runCatching { staleSession?.close() }
            .onFailure { Log.w(TAG, "Failed to close stale session before device recovery", it) }
        if (staleSession != null) {
            closeDeferredReadersSafely(staleSession, "camera device recovery")
            completePreviousSessionRelease(staleSession, fallback = true)
        }
        // Keep current ImageReaders, preview surface, recordingActive, and mode state. setupReaders()
        // will reuse matching readers for the replacement device/session without a black cold rebuild.
        recordFrameDeliveryLedger.markReconfigureSettled()
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
        startPreviewInternal(
            expectedGeneration = expectedGeneration,
            expectedSession = expectedSession,
            authorizedRawResumeBoundary = null
        )
    }

    /**
     * Installs a repeating request without crossing the private RAW resume marker transaction.
     * Ordinary callers defer while a marker owns Camera2 ordering. Only that exact token may install
     * its success/failure restart; a stale authorized call also yields to any newer transaction.
     */
    private fun startPreviewInternal(
        expectedGeneration: Long,
        expectedSession: CameraCaptureSession?,
        authorizedRawResumeBoundary: RawCaptureResumeBoundaryRequestToken?,
        authorizedNpv10StartupLease: Npv10ProducerStartupLeaseGate.Lease? = null,
        primeModeSwitchFirstFrame: Boolean = false
    ) {
        activeP010StopBoundary.get()?.let { owner ->
            p010StopDeferredRequestRefresh.defer(owner)
            return
        }
        val activeRawResume = activeRawResumeBoundary.get()
        if (activeRawResume != null && activeRawResume !== authorizedRawResumeBoundary) {
            rawResumeDeferredRequestRefresh.defer(activeRawResume)
            return
        }
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
        logNiv99AnalysisRoute(streamGraph, requestPlan)
        try {
            var targetCount = 0
            val requestTargets = mutableListOf<Surface>()
            val builder = dev.createCaptureRequest(requestPlan.template)
            fun addRepeatingTarget(surface: Surface) {
                if (requestTargets.any { it === surface }) return
                builder.addTarget(surface)
                requestTargets.add(surface)
                targetCount++
            }

            val visiblePreviewTargets = PersistentPreviewRepeatingTargetPolicy.resolve(
                activePreview = previewWarmupSurface ?: previewSurface,
                persistentSdrPreview = persistentSdrPreviewSurface,
                persistentHdrPreview = persistentHdrPreviewSurface,
                profilesCanShareRequest = persistentPreviewProfilesCanShareRequest,
                recordingActive = recordingActive || processedRecordingSessionPreparationActive
            )
            visiblePreviewTargets.forEach(::addRepeatingTarget)
            val primeRequest = if (primeModeSwitchFirstFrame && visiblePreviewTargets.isNotEmpty()) {
                buildSoftwareAeTrackedRequest(builder) {
                    applyAll(builder, pipelineRequestPlan = requestPlan)
                }
            } else {
                null
            }
            val primeControlRevision = primeRequest?.let(::softwareAeControlRevisionFor)

            if (!maximumResolutionStillCaptureActive &&
                streamGraph.encoder.includeInSession &&
                (recordingActive || previewContinuityRecordSurfaceEnabled)
            ) {
                recordSurface?.let(::addRepeatingTarget)
            }
            if (readoutTargetPlan.includeRawSensor) {
                rawReader?.surface?.let(::addRepeatingTarget)
            }
            if (readoutTargetPlan.includeYuvReadout) {
                yuvReader?.surface?.let(::addRepeatingTarget)
            }
            if (streamGraph.hardwareImport.includeInSession) {
                hardwareImportReader?.surface?.let(::addRepeatingTarget)
            }
            if (targetCount == 0) {
                previewBuilder = null
                previewBuilderHasTargets = false
                previewBuilderTargetSnapshot = emptyList()
                Log.w(
                    TAG,
                    "startPreview skipped: no active target surfaces for pipeline=${requestPlan.pipelineMode} " +
                        "preview=${previewSurface != null} record=${recordSurface != null} " +
                        "raw=${rawReader?.surface != null} yuv=${yuvReader?.surface != null} " +
                        "hardwareImport=${hardwareImportReader?.surface != null}"
                )
                return
            }
            val repeatingRequest = if (primeControlRevision != null) {
                buildSoftwareAeTrackedRequestForRevision(builder, primeControlRevision)
            } else {
                buildSoftwareAeTrackedRequest(builder) {
                    applyAll(builder, pipelineRequestPlan = requestPlan)
                }
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
                        "antibanding=${Npv10CadenceExposurePolicy.resolveAntibandingMode(
                            plan = currentNpv10CadenceExposurePlan(),
                            requestedMode = requestPlan.antibandingMode,
                            availableModes = cachedAntibandingModes,
                            stillCapture = false
                        ) ?: "unchanged"} " +
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
                previewBuilderTargetSnapshot = emptyList()
                return
            }
            previewBuilder = builder
            previewBuilderHasTargets = true
            previewBuilderTargetSnapshot = requestTargets.toList()
            val finalActiveRawResume = activeRawResumeBoundary.get()
            if (finalActiveRawResume != null &&
                finalActiveRawResume !== authorizedRawResumeBoundary
            ) {
                rawResumeDeferredRequestRefresh.defer(finalActiveRawResume)
                return
            }
            if (primeRequest != null) {
                runCatching {
                    submitModeSwitchFirstFramePrimeCapture(
                        session = sess,
                        expectedGeneration = expectedGeneration,
                        request = primeRequest
                    )
                }.onSuccess { sequenceId ->
                    if (sequenceId != null) {
                        NeoLogger.i(
                            TAG,
                            "MODE_SWITCH_FIRST_FRAME_PRIME_SUBMITTED generation=$expectedGeneration " +
                                "sequence=$sequenceId visibleTargets=${visiblePreviewTargets.size} " +
                                "fullTargets=${requestTargets.size}"
                        )
                    }
                }.onFailure { error ->
                    // The complete repeating graph remains authoritative and is submitted below.
                    // A failed latency optimization must never suppress or weaken preview recovery.
                    Log.w(TAG, "Mode-switch first-frame prime was rejected; continuing full graph", error)
                }
            }
            if (submitTrackedRepeatingRequest(
                    session = sess,
                    request = repeatingRequest,
                    callback = captureCallback,
                    p010Surface = requestTargets
                        .firstOrNull { target -> target === yuvReader?.surface },
                    authorizedRawResumeBoundary = authorizedRawResumeBoundary,
                    authorizedNpv10StartupLease = authorizedNpv10StartupLease
                ) == null
            ) {
                return
            }
            acknowledgeRawResumeRestart(
                session = sess,
                generation = expectedGeneration,
                authorizedRawResumeBoundary = authorizedRawResumeBoundary
            )
            unconfiguredRecordSurfaceRecoveryPending = false
            // A Camera2 device/session recovery temporarily moves the lifecycle through
            // Opening -> Open even when a RAW/NPV10 capture is still active. Restore the
            // authoritative recording state only after the replacement repeating request is
            // accepted; otherwise ViewModel startup can reject a recorder that is already
            // receiving and durably writing sensor packets.
            transitionTo(
                if (recordingActive) CameraState.Recording else CameraState.Streaming,
                reason = "repeating-request-active"
            )
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
        refreshOutputsAndSession(
            rebuildReaders = false,
            forceRebuild = true,
            allowIdenticalRecoveryRebuild = true,
            reason = "unconfigured-repeating-target"
        ) {
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
        refreshOutputsAndSession(
            rebuildReaders = false,
            forceRebuild = true,
            allowIdenticalRecoveryRebuild = true,
            reason = "repeating-target-illegal-argument"
        ) {
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
            advanceSessionGeneration("preview-start-recovery")
            if (captureSession === session) {
                clearCaptureResultSessionBinding(session)
                captureSession = null
                clearAppliedDynamicRangeSnapshotFor(session)
            }
            if (cameraDevice === device) cameraDevice = null
            clearSessionConfigurationFingerprints()
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

    private fun markSessionRefreshOwnershipStarted() {
        pendingSessionRefreshStartElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun clearSessionRefreshOwnership() {
        sessionRefreshPending.set(false)
        pendingSessionRefreshStartElapsedMs = 0L
    }

    private fun retireStaleSessionRefreshOwnershipIfNeeded(nowElapsedMs: Long): Boolean {
        val started = pendingSessionRefreshStartElapsedMs
        if (
            !sessionRefreshPending.get() ||
            started <= 0L ||
            nowElapsedMs - started < STALE_SESSION_REFRESH_OWNER_TIMEOUT_MS
        ) {
            return false
        }
        val generation = pendingSessionCreateGeneration
        val retired = if (generation != 0L) {
            settleFailedSessionCreateAttempt(generation)
        } else {
            clearSessionRefreshOwnership()
            clearInFlightSessionFingerprint(inFlightSessionFingerprintGeneration)
            recordFrameDeliveryLedger.markReconfigureSettled()
            true
        }
        if (retired) {
            Log.e(
                TAG,
                "Retired stale Camera2 session-refresh owner after " +
                    "${nowElapsedMs - started}ms generation=$generation"
            )
        }
        return retired
    }

    private fun previewRecoveryInFlight(): Boolean {
        retireStaleSessionRefreshOwnershipIfNeeded(SystemClock.elapsedRealtime())
        return sessionRefreshTransitionActive() ||
            sessionRecoveryPending ||
            inFlightSessionFingerprint != null ||
            pendingSessionCreateGeneration != 0L ||
            pendingRepeatingRequestRefresh.get()
    }

    override fun startPreviewWatchdog() {
        val handler = backgroundHandler ?: return
        previewHealthMonitor.start(
            handler = handler,
            isRecoveryInFlight = ::previewRecoveryInFlight,
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
                refreshOutputsAndSession(
                    rebuildReaders = false,
                    forceRebuild = true,
                    allowIdenticalRecoveryRebuild = true,
                    reason = "preview-watchdog-recovery"
                )
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
                        advanceSessionGeneration("preview-watchdog-fatal-recovery")
                        val staleSession = captureSession
                        val staleDevice = cameraDevice
                        clearCaptureResultSessionBinding(staleSession)
                        captureSession = null
                        clearAppliedDynamicRangeSnapshotFor(staleSession)
                        cameraDevice = null
                        clearSessionConfigurationFingerprints()
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
        // Acquire the tracker lifecycle write barrier without holding refreshBatchLock. This waits
        // for an already-admitted camera action to finish and prevents a refreshBatch/tracker lock
        // cycle while close/reopen invalidates all queued dispatches.
        val invalidatedDispatchCallbacks = sessionRefreshDispatchTracker.invalidateLifecycle()
        // Reset ownership only after the execution barrier. A dispatch admitted just before close
        // may have set these fields while invalidation waited for its read lease to finish.
        clearSessionRefreshOwnership()
        pendingPreviewRebuild.set(false)
        pendingRepeatingRequestRefresh.set(false)
        pendingSessionCreateGeneration = 0L
        pendingSessionCreateStartElapsedMs = 0L
        clearInFlightSessionFingerprint(inFlightSessionFingerprintGeneration)
        val batchedRequest = synchronized(refreshBatchLock) {
            refreshBatchDepth.set(0)
            batchSessionRefreshHandoffPending = false
            batchedRepeatingRequestRefreshPending = false
            val current = batchedSessionRefreshRequest
            batchedSessionRefreshRequest = null
            current
        }
        // #11 fix: swap-and-null the request synchronously under the lock (so closeCamera's ordering holds —
        // no trailing refresh can run after this because the field is null), then fire the abandoned
        // refresh's callbacks on the CAMERA thread. The callbacks touch camera/GL state, so running them on
        // the caller's Dispatchers.Default thread ran the GL-preview handoff off-thread / double-released.
        // Fire BOTH lists — the previous code dropped onPreviousSessionClosedCallbacks (a cleanup leak).
        val trailingRequest = synchronized(trailingRefreshLock) {
            val current = trailingSessionRefreshRequest
            trailingSessionRefreshRequest = null
            current
        }
        val abandonedRequests = listOfNotNull(batchedRequest, trailingRequest)
        if (invalidatedDispatchCallbacks.isEmpty() && abandonedRequests.isEmpty()) return
        runOnCameraThread {
            invalidatedDispatchCallbacks.forEach { callback ->
                runCatching(callback)
                    .onFailure { Log.w(TAG, "Queued session refresh callback failed during lifecycle invalidation", it) }
            }
            abandonedRequests.forEach { request ->
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
    }

    private fun queueTrailingSessionRefresh(
        rebuildReaders: Boolean,
        forceRebuild: Boolean,
        allowIdenticalRecoveryRebuild: Boolean,
        reason: String,
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
                    allowIdenticalRecoveryRebuild = allowIdenticalRecoveryRebuild,
                    reasons = linkedSetOf(reason),
                    onConfiguredCallbacks = mutableListOf(onConfigured),
                    onPreviousSessionClosedCallbacks =
                        mutableListOf<() -> Unit>().apply { onPreviousSessionClosed?.let { add(it) } }
                )
            } else {
                request.rebuildReaders = request.rebuildReaders || rebuildReaders
                request.forceRebuild = request.forceRebuild || forceRebuild
                request.allowIdenticalRecoveryRebuild =
                    request.allowIdenticalRecoveryRebuild || allowIdenticalRecoveryRebuild
                request.reasons += reason
                request.onConfiguredCallbacks += onConfigured
                onPreviousSessionClosed?.let { request.onPreviousSessionClosedCallbacks += it }
            }
        }
        Log.d(
            TAG,
            "Session refresh coalesced behind in-flight rebuild " +
                "rebuildReaders=$rebuildReaders forceRebuild=$forceRebuild " +
                "recovery=$allowIdenticalRecoveryRebuild reason=$reason"
        )
    }

    /**
     * Coalesces a full Camera2 graph refresh behind the exact P010 stop owner.
     *
     * The first atomic read is only an observation. Revalidating that exact token while holding
     * [cameraSubmissionLock] closes the retirement race: either the request is queued before the
     * owner can release (and that release drains it), or the owner has already retired and the
     * caller is free to rebuild. A replacement owner is retried instead of being bypassed.
     */
    private fun deferSessionRefreshBehindP010StopBoundary(
        rebuildReaders: Boolean,
        forceRebuild: Boolean,
        allowIdenticalRecoveryRebuild: Boolean,
        reason: String,
        onConfigured: () -> Unit,
        onPreviousSessionClosed: (() -> Unit)?
    ): Boolean {
        while (true) {
            val observedOwner = activeP010StopBoundary.get() ?: return false
            val deferred = synchronized(cameraSubmissionLock) {
                if (activeP010StopBoundary.get() !== observedOwner) {
                    false
                } else {
                    queueTrailingSessionRefresh(
                        rebuildReaders = rebuildReaders,
                        forceRebuild = forceRebuild,
                        allowIdenticalRecoveryRebuild = allowIdenticalRecoveryRebuild,
                        reason = reason,
                        onConfigured = onConfigured,
                        onPreviousSessionClosed = onPreviousSessionClosed
                    )
                    true
                }
            }
            if (deferred) {
                Log.i(
                    TAG,
                    "Full session refresh deferred behind exact P010 stop boundary " +
                        "generation=${observedOwner.sessionGeneration} reason=$reason"
                )
                return true
            }
        }
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
                "rebuildReaders=${request.rebuildReaders} forceRebuild=${request.forceRebuild} " +
                "recovery=${request.allowIdenticalRecoveryRebuild} reasons=${request.reasons.joinToString("+")}"
        )
        refreshOutputsAndSession(
            rebuildReaders = request.rebuildReaders,
            forceRebuild = request.forceRebuild,
            allowIdenticalRecoveryRebuild = request.allowIdenticalRecoveryRebuild,
            reason = request.reasons.joinToString("+"),
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

    private fun completePreviousSessionRelease(
        session: CameraCaptureSession,
        fallback: Boolean
    ): Boolean {
        val callbacks = synchronized(sessionCollectionsLock) {
            sessionsClosedBeforeReleaseBarrier.remove(session)
            previousSessionClosedCallbacks.remove(session)
        } ?: return false
        if (fallback) {
            Log.w(
                TAG,
                "Previous Camera2 session omitted onClosed for " +
                    "${HOT_SWAP_SESSION_CLOSE_CALLBACK_TIMEOUT_MS}ms; releasing the bounded hot-swap fence"
            )
        }
        callbacks.forEach { callback ->
            runCatching(callback)
                .onFailure { error ->
                    Log.w(TAG, "Previous session release callback failed", error)
                }
        }
        clearSessionRefreshOwnership()
        runTrailingSessionRefreshIfNeeded()
        return true
    }

    /**
     * Installs an ownership fence for every outgoing session, including refreshes that do not have
     * an external surface-release callback. This is what keeps a coalesced trailing rebuild from
     * entering the vendor HAL until the previous CameraCaptureSession has actually retired.
     *
     * @return true when Camera2 delivered onClosed just before the replacement's onConfigured.
     */
    private fun registerPreviousSessionReleaseBarrier(
        session: CameraCaptureSession,
        callback: (() -> Unit)?
    ): Boolean = synchronized(sessionCollectionsLock) {
        val callbacks = previousSessionClosedCallbacks.getOrPut(session) { mutableListOf() }
        callback?.let { candidate ->
            if (callbacks.none { existing -> existing === candidate }) callbacks.add(candidate)
        }
        sessionsClosedBeforeReleaseBarrier.remove(session)
    }

    private fun schedulePreviousSessionReleaseFallback(session: CameraCaptureSession) {
        val handler = backgroundHandler
        if (handler == null) {
            // The camera thread is already shutting down. Resource release owns the remaining close.
            return
        }
        handler.postDelayed(
            {
                completePreviousSessionRelease(session, fallback = true)
            },
            HOT_SWAP_SESSION_CLOSE_CALLBACK_TIMEOUT_MS
        )
    }

    private fun queueBatchedSessionRefreshIfActive(
        rebuildReaders: Boolean,
        forceRebuild: Boolean,
        allowIdenticalRecoveryRebuild: Boolean,
        reason: String,
        onConfigured: () -> Unit,
        onPreviousSessionClosed: (() -> Unit)?
    ): Boolean {
        val queued = synchronized(refreshBatchLock) {
            if (refreshBatchDepth.get() <= 0) return@synchronized false
            val request = batchedSessionRefreshRequest
            if (request == null) {
                batchedSessionRefreshRequest = PendingSessionRefreshRequest(
                    rebuildReaders = rebuildReaders,
                    forceRebuild = forceRebuild,
                    allowIdenticalRecoveryRebuild = allowIdenticalRecoveryRebuild,
                    reasons = linkedSetOf(reason),
                    onConfiguredCallbacks = mutableListOf(onConfigured),
                    onPreviousSessionClosedCallbacks =
                        mutableListOf<() -> Unit>().apply { onPreviousSessionClosed?.let { add(it) } }
                )
            } else {
                request.rebuildReaders = request.rebuildReaders || rebuildReaders
                request.forceRebuild = request.forceRebuild || forceRebuild
                request.allowIdenticalRecoveryRebuild =
                    request.allowIdenticalRecoveryRebuild || allowIdenticalRecoveryRebuild
                request.reasons += reason
                request.onConfiguredCallbacks += onConfigured
                onPreviousSessionClosed?.let { request.onPreviousSessionClosedCallbacks += it }
            }
            true
        }
        if (!queued) return false
        Log.d(
            TAG,
            "Session refresh batched during mode switch " +
                "rebuildReaders=$rebuildReaders forceRebuild=$forceRebuild " +
                "recovery=$allowIdenticalRecoveryRebuild reason=$reason"
        )
        return true
    }

    /** Marks the executor/Handler hop as owned before it can race a repeating-request retarget. */
    private fun beginSessionRefreshDispatch(
        onInvalidated: () -> Unit
    ): CameraSessionRefreshDispatchTracker.Ticket? =
        synchronized(refreshBatchLock) {
        val ticket = sessionRefreshDispatchTracker.begin(onInvalidated)
        // If this dispatch is the flush of an outermost mode batch, the counted dispatch now owns
        // the handoff. Keeping both flags is unnecessary and would make teardown diagnostics noisy.
        batchSessionRefreshHandoffPending = false
        ticket
    }

    private fun completeSessionRefreshDispatch(
        ticket: CameraSessionRefreshDispatchTracker.Ticket
    ): Boolean = sessionRefreshDispatchTracker.complete(ticket)

    /**
     * Performs the executor -> Handler hop with exact dispatch ownership. Handler.post() can legally
     * return false after stopBackground(); that path must release the ticket just like an executed
     * action. A stale lifecycle ticket is discarded and cannot mutate the reopened camera.
     */
    private fun dispatchSessionRefreshOnCameraThread(
        ticket: CameraSessionRefreshDispatchTracker.Ticket,
        onRejected: () -> Unit,
        action: () -> Unit
    ) {
        fun rejectCurrentDispatch(reason: String, failure: Throwable? = null) {
            if (!completeSessionRefreshDispatch(ticket)) return
            if (failure != null) {
                Log.w(TAG, "Camera session refresh dispatch rejected: $reason", failure)
            } else {
                Log.w(TAG, "Camera session refresh dispatch rejected: $reason")
            }
            runCatching(onRejected)
                .onFailure { Log.w(TAG, "Rejected session-refresh completion callback failed", it) }
        }

        try {
            ThreadPoolManager.cameraExecutor.execute {
                val cameraAction = Runnable {
                    val executed = sessionRefreshDispatchTracker.executeIfActive(ticket) {
                        action()
                    }
                    if (!executed) {
                        Log.d(TAG, "Discarded stale Camera2 session-refresh dispatch epoch=${ticket.epoch}")
                    }
                }
                val posted = try {
                    backgroundHandler?.post(cameraAction) == true
                } catch (failure: RuntimeException) {
                    rejectCurrentDispatch("background Handler threw while posting", failure)
                    return@execute
                }
                if (!posted) {
                    rejectCurrentDispatch("background Handler is stopped")
                }
            }
        } catch (failure: RuntimeException) {
            rejectCurrentDispatch("camera executor rejected work", failure)
        }
    }

    /**
     * Returns true when the current/new session transaction will publish the latest repeating
     * request. A request observed while the batch itself is open is remembered only when the batch
     * ends without a session refresh and therefore still needs an explicit replay.
     */
    private fun deferRepeatingRefreshToSessionTransaction(): Boolean = synchronized(refreshBatchLock) {
        val batchActive = refreshBatchDepth.get() > 0
        val defer = CameraRepeatingRefreshAdmissionPolicy.shouldDeferToSessionTransaction(
            batchDepth = refreshBatchDepth.get(),
            batchRefreshHandoffPending = batchSessionRefreshHandoffPending,
            queuedSessionRefreshDispatches = queuedSessionRefreshDispatches,
            sessionRefreshPending = sessionRefreshPending.get()
        )
        if (defer && batchActive) batchedRepeatingRequestRefreshPending = true
        defer
    }

    private fun sessionRefreshTransitionActive(): Boolean = synchronized(refreshBatchLock) {
        CameraRepeatingRefreshAdmissionPolicy.shouldDeferToSessionTransaction(
            batchDepth = refreshBatchDepth.get(),
            batchRefreshHandoffPending = batchSessionRefreshHandoffPending,
            queuedSessionRefreshDispatches = queuedSessionRefreshDispatches,
            sessionRefreshPending = sessionRefreshPending.get()
        )
    }

    /**
     * Keeps final repeating-request construction/submission atomic with mode-batch admission. The
     * lock is held only for the Camera2 request publication, never for frame processing.
     */
    private fun publishRepeatingRequestIfSessionStable(action: () -> Unit): Boolean =
        synchronized(refreshBatchLock) {
            val batchActive = refreshBatchDepth.get() > 0
            val defer = CameraRepeatingRefreshAdmissionPolicy.shouldDeferToSessionTransaction(
                batchDepth = refreshBatchDepth.get(),
                batchRefreshHandoffPending = batchSessionRefreshHandoffPending,
                queuedSessionRefreshDispatches = queuedSessionRefreshDispatches,
                sessionRefreshPending = sessionRefreshPending.get()
            )
            if (defer) {
                if (batchActive) batchedRepeatingRequestRefreshPending = true
                false
            } else {
                action()
                true
            }
        }

    fun refreshOutputsAndSession(
        rebuildReaders: Boolean = true,
        forceRebuild: Boolean = false,
        allowIdenticalRecoveryRebuild: Boolean = false,
        reason: String = "configuration-change",
        onPreviousSessionClosed: (() -> Unit)? = null,
        onConfigured: () -> Unit = {}
    ) {
        val rejectedCompletion: () -> Unit = {
            // A close/reopen lifecycle or rejected queue hop leaves no Camera2 callback that can
            // settle these waiters. Complete both independently so one failing callback cannot leak
            // the other resource owner.
            runCatching(onConfigured)
                .onFailure { Log.w(TAG, "Rejected session-refresh configured callback failed", it) }
            onPreviousSessionClosed?.let { callback ->
                runCatching(callback)
                    .onFailure { Log.w(TAG, "Rejected previous-session callback failed", it) }
            }
        }
        if (stateFlow.value is CameraState.Closing || stateFlow.value is CameraState.Closed) {
            Log.d(TAG, "Camera session refresh rejected after lifecycle close admission")
            rejectedCompletion()
            return
        }
        // Caller-side admission closes the common lifecycle race before another executor hop. The
        // exact owner is revalidated under cameraSubmissionLock by the helper before anything queues.
        if (deferSessionRefreshBehindP010StopBoundary(
                rebuildReaders = rebuildReaders,
                forceRebuild = forceRebuild,
                allowIdenticalRecoveryRebuild = allowIdenticalRecoveryRebuild,
                reason = reason,
                onConfigured = onConfigured,
                onPreviousSessionClosed = onPreviousSessionClosed
            )
        ) {
            return
        }
        if (queueBatchedSessionRefreshIfActive(
                rebuildReaders = rebuildReaders,
                forceRebuild = forceRebuild,
                allowIdenticalRecoveryRebuild = allowIdenticalRecoveryRebuild,
                reason = reason,
                onConfigured = onConfigured,
                onPreviousSessionClosed = onPreviousSessionClosed
            )
        ) {
            return
        }
        val dispatchTicket = beginSessionRefreshDispatch(rejectedCompletion)
        if (dispatchTicket == null) {
            Log.d(TAG, "Camera session refresh rejected while lifecycle dispatch admission is closed")
            rejectedCompletion()
            return
        }
        dispatchSessionRefreshOnCameraThread(
            ticket = dispatchTicket,
            onRejected = rejectedCompletion
        ) refreshDispatch@{
            // The stop fence may have been installed after caller-side admission but before this
            // Camera2-thread action. Recheck before acquiring refresh ownership or touching readers.
            if (deferSessionRefreshBehindP010StopBoundary(
                    rebuildReaders = rebuildReaders,
                    forceRebuild = forceRebuild,
                    allowIdenticalRecoveryRebuild = allowIdenticalRecoveryRebuild,
                    reason = reason,
                    onConfigured = onConfigured,
                    onPreviousSessionClosed = onPreviousSessionClosed
                )
            ) {
                return@refreshDispatch
            }
            if (
                cameraDevice == null ||
                stateFlow.value is CameraState.Closing ||
                stateFlow.value is CameraState.Closed
            ) {
                onConfigured()
                onPreviousSessionClosed?.invoke()
                return@refreshDispatch
            }
            if (!sessionRefreshPending.compareAndSet(false, true)) {
                if (allowIdenticalRecoveryRebuild && reason == "preview-watchdog-recovery") {
                    // The in-flight configuration already replaces the stale producer. Queuing another
                    // identical recovery here makes the HAL drain the session it just configured and can
                    // turn one bounded freeze into an overlapping multi-session failure.
                    Log.d(TAG, "Preview watchdog recovery already in flight; duplicate rebuild suppressed")
                    onConfigured()
                    return@refreshDispatch
                }
                // A dynamic-range/profile-only change legitimately requests neither reader rebuild nor
                // forced identical rebuild. It still changes the desired OutputConfiguration fingerprint.
                // Dropping it here allowed an older in-flight mode switch to configure last, leaving the
                // visible/recording transport on the previous SDR/HDR profile. Queue every refresh intent;
                // createSession's fingerprint deduplication makes an actually-identical trailing request
                // cheap while preserving callbacks and latest-state authority.
                queueTrailingSessionRefresh(
                    rebuildReaders = rebuildReaders,
                    forceRebuild = forceRebuild,
                    allowIdenticalRecoveryRebuild = allowIdenticalRecoveryRebuild,
                    reason = reason,
                    onConfigured = onConfigured,
                    onPreviousSessionClosed = onPreviousSessionClosed
                )
                return@refreshDispatch
            }
            markSessionRefreshOwnershipStarted()
            // Serialize final stop-fence admission and reader mutation. Generation admission remains
            // inside createSession's own cameraSubmissionLock transaction after this narrow block.
            val sessionRefreshAdmitted = synchronized(cameraSubmissionLock) {
                if (deferSessionRefreshBehindP010StopBoundary(
                        rebuildReaders = rebuildReaders,
                        forceRebuild = forceRebuild,
                        allowIdenticalRecoveryRebuild = allowIdenticalRecoveryRebuild,
                        reason = reason,
                        onConfigured = onConfigured,
                        onPreviousSessionClosed = onPreviousSessionClosed
                    )
                ) {
                    clearSessionRefreshOwnership()
                    false
                } else {
                    if (rebuildReaders) {
                        setupReaders()
                    }
                    // s1-f05: open the reconfigure transition window so any frame dropped while the
                    // replacement session configures is attributed to this bounded transition, not counted
                    // as a silent steady-state drop. Settled in the session StateCallback / failure handlers.
                    recordFrameDeliveryLedger.markReconfigureStart()
                    Log.d(TAG, "Hot-session rebuild requested; keeping current session streaming until replacement configures")
                    true
                }
            }
            if (!sessionRefreshAdmitted) return@refreshDispatch
            // createSession can synchronously drain a trailing request, which reacquires
            // refreshBatchLock. Never call it while cameraSubmissionLock is held: repeating-request
            // publication takes the opposite refreshBatchLock -> cameraSubmissionLock order.
            createSession(
                onConfigured = onConfigured,
                allowIdenticalRecoveryRebuild = allowIdenticalRecoveryRebuild,
                refreshReason = reason,
                onPreviousSessionClosed = onPreviousSessionClosed
            )
        }
    }

    private fun hdrSurfaceRoleFor(surface: Surface): HdrSurfaceRole = when (surface) {
        previewSurface -> HdrSurfaceRole.PREVIEW
        persistentSdrPreviewSurface, persistentHdrPreviewSurface -> HdrSurfaceRole.PREVIEW
        recordSurface -> HdrSurfaceRole.ENCODER
        jpegReader?.surface -> HdrSurfaceRole.JPEG
        rawReader?.surface -> HdrSurfaceRole.RAW
        yuvReader?.surface -> if (npv10TenBitCaptureEnabled) {
            HdrSurfaceRole.ENCODER
        } else {
            HdrSurfaceRole.ANALYSIS
        }
        hardwareImportReader?.surface -> HdrSurfaceRole.ENCODER
        else -> HdrSurfaceRole.ANALYSIS
    }

    private fun sessionSurfaceKindFor(surface: Surface): CameraSessionSurfaceKind {
        // The idle NPV10 P010 output is an untargeted recording prearm, not an analysis stream.
        // Keeping its role/use-case byte-identical before and after shutter admission prevents a
        // 300-600 ms CameraCaptureSession rebuild at the first recorded frame.
        if (npv10TenBitCaptureEnabled && surface == yuvReader?.surface) {
            return CameraSessionSurfaceKind.RECORD
        }
        return when (surface) {
            recordSurface -> CameraSessionSurfaceKind.RECORD
            previewSurface -> CameraSessionSurfaceKind.PREVIEW
            persistentSdrPreviewSurface, persistentHdrPreviewSurface -> CameraSessionSurfaceKind.PREVIEW
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

    private fun persistentPreviewOutputsEnabled(): Boolean =
        persistentSdrPreviewSurface != null && persistentHdrPreviewSurface != null

    private fun p010CadenceSafeSessionActive(streamGraph: CinemaStreamGraphPlan): Boolean =
        P010CadenceSafeSessionFlag.enabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !p010CadenceStillCaptureActive &&
            yuvReader?.imageFormat == ImageFormat.YCBCR_P010 &&
            (streamGraph.yuvAnalysis.includeInSession ||
                shouldAttachIdleP010StandbySurface(yuvReader, streamGraph))

    /** Prewarmed Neo HDR Camera2 input; independent from the selected output look/delivery. */
    private fun persistentNeoHdrCaptureMode(): HdrDynamicRangeMode = NdrCaptureTransportPolicy.resolve(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        device = Build.DEVICE,
        product = Build.PRODUCT,
        hdr10Supported = hdrSupport.hdr10Supported,
        hdr10PlusSupported = hdrSupport.hdr10PlusSupported,
        hlg10Supported = hdrSupport.hlg10Supported
    )

    private fun refreshPersistentPreviewProfileCoexistence(): Boolean {
        if (!persistentPreviewOutputsEnabled() || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            persistentPreviewProfilesCanShareRequest = false
            return false
        }
        val hdrProfile = HdrDynamicRangePolicy.resolveProfile(
            persistentNeoHdrCaptureMode(),
            hdrSupport,
            HdrSurfaceRole.PREVIEW
        ) ?: HdrDynamicRangePolicy.PROFILE_STANDARD
        if (hdrProfile == HdrDynamicRangePolicy.PROFILE_STANDARD) {
            persistentPreviewProfilesCanShareRequest = true
            return true
        }
        val profiles = runCatching {
            characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
        }.getOrNull()
        val compatible = if (profiles == null) {
            false
        } else {
            runCatching {
                val supported = profiles.supportedProfiles
                // Resolve only the Camera2 carrier profiles this project can request here. Keeping
                // the framework constants at each call site also proves the @Profile contract to
                // lint; a runtime Long from the capability set is not sufficient for that check.
                val hdrConstraints = when (hdrProfile) {
                    DynamicRangeProfiles.HLG10 -> profiles.getProfileCaptureRequestConstraints(
                        DynamicRangeProfiles.HLG10
                    )
                    DynamicRangeProfiles.HDR10 -> profiles.getProfileCaptureRequestConstraints(
                        DynamicRangeProfiles.HDR10
                    )
                    DynamicRangeProfiles.HDR10_PLUS -> profiles.getProfileCaptureRequestConstraints(
                        DynamicRangeProfiles.HDR10_PLUS
                    )
                    else -> null
                }
                if (
                    HdrDynamicRangePolicy.PROFILE_STANDARD !in supported ||
                    hdrProfile !in supported ||
                    hdrConstraints == null
                ) {
                    false
                } else {
                    PersistentPreviewDynamicRangeCoexistencePolicy.canShareRequest(
                        firstProfile = HdrDynamicRangePolicy.PROFILE_STANDARD,
                        firstConstraints = profiles.getProfileCaptureRequestConstraints(
                            DynamicRangeProfiles.STANDARD
                        ),
                        secondProfile = hdrProfile,
                        secondConstraints = hdrConstraints
                    )
                }
            }.onFailure {
                Log.w(TAG, "Dynamic-range coexistence query failed; using one preview target", it)
            }.getOrDefault(false)
        }
        val changed = compatible != persistentPreviewProfilesCanShareRequest
        persistentPreviewProfilesCanShareRequest = compatible
        if (changed) {
            Log.i(
                TAG,
                "Persistent SDR/Neo-input request coexistence=$compatible hdrProfile=$hdrProfile"
            )
        }
        return compatible
    }

    private fun resolvedDynamicRangeProfileForSessionSurface(surface: Surface): Long? = when (surface) {
        persistentSdrPreviewSurface -> null
        persistentHdrPreviewSurface -> HdrDynamicRangePolicy.resolveProfile(
            // This selects only the Camera2 input carrier. NDR/NIVUKX identity and PQ delivery remain
            // project-owned and are applied after transfer-aware scene-linear decoding.
            persistentNeoHdrCaptureMode(),
            hdrSupport,
            HdrSurfaceRole.PREVIEW
        )
        else -> HdrDynamicRangePolicy.resolveProfile(
            hdrDynamicRangeMode,
            hdrSupport,
            hdrSurfaceRoleFor(surface)
        )
    }

    private fun sessionReaderFingerprint(
        kind: CameraSessionSurfaceKind,
        reader: ImageReader?
    ): CameraSessionReaderFingerprint? {
        reader ?: return null
        val usage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { reader.usage }.getOrDefault(0L)
        } else {
            0L
        }
        return CameraSessionReaderFingerprint(
            kind = kind,
            width = reader.width,
            height = reader.height,
            imageFormat = reader.imageFormat,
            maxImages = reader.maxImages,
            usage = usage
        )
    }

    private fun recordingSessionSurfaceBudgetConstrained(
        streamGraph: CinemaStreamGraphPlan
    ): Boolean =
        (recordingActive || processedRecordingSessionPreparationActive) &&
            streamGraph.mode == CinemaStreamGraphMode.ENCODED_CINEMA &&
            streamGraph.hardwareImport.includeInSession

    private fun resolveSessionSurfaces(streamGraph: CinemaStreamGraphPlan): List<Surface> = buildList {
        addAll(
            PersistentPreviewSessionOutputPolicy.resolve(
                activePreview = previewWarmupSurface ?: previewSurface,
                persistentSdrPreview = persistentSdrPreviewSurface,
                persistentHdrPreview = persistentHdrPreviewSurface,
                constrainToVisiblePreview = recordingSessionSurfaceBudgetConstrained(streamGraph)
            )
        )
        if (!maximumResolutionStillCaptureActive && streamGraph.encoder.includeInSession) {
            recordSurface?.let(::add)
        }
        if (streamGraph.jpegStill.includeInSession) {
            if (!p010CadenceSafeSessionActive(streamGraph)) jpegReader?.surface?.let(::add)
        }
        if (streamGraph.rawSensor.includeInSession) rawReader?.surface?.let(::add)
        if (streamGraph.yuvAnalysis.includeInSession) {
            yuvReader?.surface?.let(::add)
        } else if (shouldAttachIdleP010StandbySurface(yuvReader, streamGraph)) {
            yuvReader?.surface?.let(::add)
        }
        if (streamGraph.hardwareImport.includeInSession) hardwareImportReader?.surface?.let(::add)
    }.distinct()

    private fun captureSessionSurfaceSnapshot(
        includeHardwareImport: Boolean
    ): CameraSessionSurfaceSnapshot = synchronized(cameraSubmissionLock) {
        val streamGraph = currentCinemaStreamGraphPlan(includeHardwareImport = includeHardwareImport)
        CameraSessionSurfaceSnapshot(
            ownershipEpoch = sessionSurfaceOwnershipEpoch.get(),
            streamGraph = streamGraph,
            surfaces = resolveSessionSurfaces(streamGraph)
        )
    }

    private fun sameSurfaceIdentitySet(first: List<Surface>, second: List<Surface>): Boolean =
        first.size == second.size && first.all { candidate -> second.any { it === candidate } }

    private fun allSessionSurfacesValid(surfaces: List<Surface>): Boolean =
        surfaces.isNotEmpty() && surfaces.all { surface ->
            runCatching { surface.isValid }.getOrDefault(false)
        }

    /** Must be called while [cameraSubmissionLock] is held immediately before createCaptureSession. */
    private fun evaluateSessionSurfaceSubmission(
        device: CameraDevice,
        generation: Long,
        snapshot: CameraSessionSurfaceSnapshot,
        desiredFingerprint: CameraSessionConfigurationFingerprint,
        includeHardwareImport: Boolean,
        applyUseCaseHints: Boolean
    ): CameraSessionSurfaceSubmissionPolicy.Decision {
        val currentGraph = currentCinemaStreamGraphPlan(includeHardwareImport = includeHardwareImport)
        val currentSurfaces = resolveSessionSurfaces(currentGraph)
        val currentFingerprint = currentSurfaces.takeIf { it.isNotEmpty() }?.let {
            buildSessionConfigurationFingerprint(
                device = device,
                streamGraph = currentGraph,
                surfaces = it,
                applyUseCaseHints = applyUseCaseHints
            )
        }
        return CameraSessionSurfaceSubmissionPolicy.evaluate(
            cameraClosing = stateFlow.value is CameraState.Closing || stateFlow.value is CameraState.Closed,
            cameraDeviceCurrent = cameraDevice === device,
            sessionGenerationCurrent = generation == sessionGeneration.get(),
            surfaceOwnershipEpochCurrent = snapshot.ownershipEpoch == sessionSurfaceOwnershipEpoch.get(),
            surfaceIdentitySetCurrent = sameSurfaceIdentitySet(snapshot.surfaces, currentSurfaces),
            sessionConfigurationCurrent = currentFingerprint == desiredFingerprint,
            allSurfacesValid = allSessionSurfacesValid(snapshot.surfaces)
        )
    }

    private fun buildSessionConfigurationFingerprint(
        device: CameraDevice,
        streamGraph: CinemaStreamGraphPlan,
        surfaces: List<Surface>,
        applyUseCaseHints: Boolean
    ): CameraSessionConfigurationFingerprint {
        val streamFpsPlan = resolveCurrentStreamBasedFpsOverridePlan()
        val maxResolutionPixelMode = maximumResolutionPixelModeActive()
        val maxResolutionSessionParameterApplied =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                maxResolutionPixelMode &&
                cachedSessionRequestKeysByName.containsKey(CaptureRequest.SENSOR_PIXEL_MODE.name)
        val outputs = surfaces.map { surface ->
            val useCaseRole = streamUseCaseRoleFor(surface).takeIf { applyUseCaseHints }
            val resolvedUseCase = if (
                useCaseRole != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                val defaultUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT.toLong()
                runCatching { StreamUseCasePolicy.resolveUseCase(characteristics, useCaseRole) }
                    .getOrNull()
                    ?.takeUnless { it == defaultUseCase }
            } else {
                null
            }
            CameraSessionOutputFingerprint(
                identity = CameraSessionSurfaceIdentity(surface),
                // Surface does not expose its native generation through the public SDK. Its
                // diagnostic descriptor changes with the backing buffer queue on affected OEMs,
                // while the reference-identity token above remains the authoritative key.
                generationId = surface.toString().hashCode(),
                kind = sessionSurfaceKindFor(surface),
                dynamicRangeProfile = resolvedDynamicRangeProfileForSessionSurface(surface),
                streamUseCaseRole = useCaseRole,
                streamUseCase = resolvedUseCase
            )
        }
        fun ImageReader?.isSessionOutput(): Boolean =
            this != null && surfaces.any { it === surface }
        val readers = buildList {
            if (jpegReader.isSessionOutput()) {
                sessionReaderFingerprint(CameraSessionSurfaceKind.JPEG, jpegReader)?.let(::add)
            }
            if (rawReader.isSessionOutput()) {
                sessionReaderFingerprint(CameraSessionSurfaceKind.RAW, rawReader)?.let(::add)
            }
            if (yuvReader.isSessionOutput()) {
                sessionReaderFingerprint(CameraSessionSurfaceKind.YUV_ANALYSIS, yuvReader)?.let(::add)
            }
            if (hardwareImportReader.isSessionOutput()) {
                sessionReaderFingerprint(CameraSessionSurfaceKind.HARDWARE_IMPORT, hardwareImportReader)?.let(::add)
            }
        }
        return CameraSessionConfigurationFingerprint(
            cameraId = device.id,
            outputs = outputs,
            readers = readers,
            streamGraph = streamGraph,
            streamBasedFpsPlan = streamFpsPlan,
            maximumResolutionPixelMode = maxResolutionPixelMode,
            maximumResolutionSessionParameterApplied = maxResolutionSessionParameterApplied
        )
    }

    private fun clearInFlightSessionFingerprint(generation: Long) {
        if (inFlightSessionFingerprintGeneration != generation) return
        inFlightSessionFingerprint = null
        inFlightSessionFingerprintGeneration = 0L
    }

    private fun clearSessionConfigurationFingerprints() {
        configuredSessionFingerprint = null
        inFlightSessionFingerprint = null
        latestDesiredSessionFingerprint = null
        inFlightSessionFingerprintGeneration = 0L
    }

    private fun prepareForCameraSessionRebuild(device: CameraDevice) {
        // Preserve a stable white point across a same-sensor graph change while ensuring only fresh
        // replacement-session frames can drive the post-rebuild convergence gate.
        val sameSensorRebuild = lastSessionCameraId == device.id
        val carriedWhitePoint = colorDriftGovernor.stabilizedWhitePoint
            ?.takeIf { sameSensorRebuild }
        lastSessionCameraId = device.id
        colorDriftGovernor.reset()
        if (carriedWhitePoint != null) colorDriftGovernor.seed(carriedWhitePoint)
        colorDriftClampingActive = false
        colorDriftOverrideActive = false
        latestMeasuredColorSnapshot = null
        meteringHysteresis.reset()
        pendingPreviewRebuild.set(false)
        postRebuildConvergenceGate.disarm()
        pendingRebuildColorHoldArm = SessionRebuildColorHoldFlag.enabled && sameSensorRebuild
        ColorDriftEnforcementFlag.registerOnChanged { runOnCameraThread { updatePreview() } }
        MeteringDampingFlag.registerOnChanged { runOnCameraThread { updatePreview() } }
        SessionRebuildColorHoldFlag.registerOnChanged { runOnCameraThread { updatePreview() } }
        DeviceTrueWhiteBalanceFlag.registerOnChanged { runOnCameraThread { updatePreview() } }
        RawPreviewViewableTonemapFlag.registerOnChanged { runOnCameraThread { updatePreview() } }
        CinematicFocusPullFlag.registerOnChanged { onCinematicFocusPullFlagChanged() }
        CinematicFocusPullDebugCommands.register(cinematicFocusPullDebugHandler)
    }

    /**
     * An equivalent-session bookkeeping refresh must not replace the live lossless P010 sequence.
     * Camera controls use [updatePreview] for explicit request changes; this guard only preserves the
     * already-authoritative NPV10 target during an output-configuration no-op.
     */
    private fun canPreserveActiveNpv10RepeatingSequence(
        retainedSession: CameraCaptureSession,
        generation: Long
    ): Boolean {
        if (!recordingActive || !npv10TenBitCaptureEnabled) return false
        val p010Surface = p010ReaderSurfaceOrNull(yuvReader) ?: return false
        val repeating = activeRepeatingSequence.get() ?: return false
        return repeating.identity.session === retainedSession &&
            repeating.identity.sessionGeneration == generation &&
            repeating.p010Surface === p010Surface &&
            previewBuilderHasTargets &&
            previewBuilderTargetSnapshot.any { it === p010Surface }
    }

    /**
     * Retires only the exact failed create attempt. A late callback from an old generation cannot
     * clear a newer attempt's recovery ownership or watchdog evidence.
     */
    private fun settleFailedSessionCreateAttempt(generation: Long): Boolean {
        val ownsPendingAttempt = pendingSessionCreateGeneration == generation
        clearInFlightSessionFingerprint(generation)
        if (!ownsPendingAttempt) return false
        pendingSessionCreateGeneration = 0L
        pendingSessionCreateStartElapsedMs = 0L
        clearSessionRefreshOwnership()
        recordFrameDeliveryLedger.markReconfigureSettled()
        return true
    }

    /**
     * Retries once from a fresh surface/configuration snapshot. The retry never lowers resolution,
     * bit depth, bitrate, colour profile, or any reader; it only discards stale Surface ownership.
     */
    private fun retrySessionCreateAfterSurfaceRace(
        device: CameraDevice,
        generation: Long,
        includeHardwareImport: Boolean,
        applyUseCaseHints: Boolean,
        allowIdenticalRecoveryRebuild: Boolean,
        refreshReason: String,
        surfaceRecoveryAttempt: Int,
        failureDescription: String,
        onConfigured: () -> Unit,
        onPreviousSessionClosed: (() -> Unit)?
    ): Boolean {
        val ownedFailure = settleFailedSessionCreateAttempt(generation)
        if (!ownedFailure || surfaceRecoveryAttempt >= MAX_SURFACE_SESSION_RECOVERY_ATTEMPTS) {
            return false
        }
        val retrySnapshot = captureSessionSurfaceSnapshot(includeHardwareImport)
        val closing = stateFlow.value is CameraState.Closing || stateFlow.value is CameraState.Closed
        if (
            closing ||
            cameraDevice !== device ||
            generation != sessionGeneration.get() ||
            !allSessionSurfacesValid(retrySnapshot.surfaces)
        ) {
            return false
        }
        if (!sessionRefreshPending.compareAndSet(false, true)) {
            queueTrailingSessionRefresh(
                rebuildReaders = false,
                forceRebuild = true,
                allowIdenticalRecoveryRebuild = true,
                reason = "$refreshReason/surface-race-trailing",
                onConfigured = onConfigured,
                onPreviousSessionClosed = onPreviousSessionClosed
            )
            return true
        }
        markSessionRefreshOwnershipStarted()
        recordFrameDeliveryLedger.markReconfigureStart()
        Log.w(
            TAG,
            "Retrying Camera2 session from fresh Surface ownership " +
                "attempt=${surfaceRecoveryAttempt + 1}/$MAX_SURFACE_SESSION_RECOVERY_ATTEMPTS " +
                "reason=$failureDescription"
        )
        createSession(
            onConfigured = onConfigured,
            includeHardwareImport = includeHardwareImport,
            applyUseCaseHints = applyUseCaseHints,
            allowIdenticalRecoveryRebuild = allowIdenticalRecoveryRebuild,
            refreshReason = "$refreshReason/surface-race-${surfaceRecoveryAttempt + 1}",
            onPreviousSessionClosed = onPreviousSessionClosed,
            surfaceRecoveryAttempt = surfaceRecoveryAttempt + 1
        )
        return true
    }

    private fun createSession(
        onConfigured: () -> Unit = {},
        includeHardwareImport: Boolean = true,
        applyUseCaseHints: Boolean = true,
        allowIdenticalRecoveryRebuild: Boolean = false,
        refreshReason: String = "camera-open",
        onPreviousSessionClosed: (() -> Unit)? = null,
        surfaceRecoveryAttempt: Int = 0
    ) {
        val dev = cameraDevice ?: run {
            // A device-loss callback can win after refreshOutputsAndSession acquired the in-flight
            // flag but before this camera-thread action runs. Always settle ownership and callbacks;
            // otherwise every later mode switch/still/foreground open observes a permanent refresh.
            clearSessionRefreshOwnership()
            clearInFlightSessionFingerprint(inFlightSessionFingerprintGeneration)
            recordFrameDeliveryLedger.markReconfigureSettled()
            onConfigured()
            onPreviousSessionClosed?.invoke()
            runTrailingSessionRefreshIfNeeded()
            return
        }
        if (stateFlow.value is CameraState.Closing || stateFlow.value is CameraState.Closed) {
            clearSessionRefreshOwnership()
            recordFrameDeliveryLedger.markReconfigureSettled()
            onConfigured()
            onPreviousSessionClosed?.invoke()
            return
        }
        cancelTapAfScanForSessionTransition()
        refreshPersistentPreviewProfileCoexistence()
        val surfaceSnapshot = captureSessionSurfaceSnapshot(includeHardwareImport)
        val streamGraph = surfaceSnapshot.streamGraph
        val surfaces = surfaceSnapshot.surfaces
        if (surfaces.isEmpty()) {
            clearSessionRefreshOwnership()
            // s1-f05: no session to build — settle any open reconfigure window so it cannot stay stuck.
            recordFrameDeliveryLedger.markReconfigureSettled()
            onConfigured()
            onPreviousSessionClosed?.invoke()
            runTrailingSessionRefreshIfNeeded()
            return
        }

        val desiredFingerprint = buildSessionConfigurationFingerprint(
            device = dev,
            streamGraph = streamGraph,
            surfaces = surfaces,
            applyUseCaseHints = applyUseCaseHints
        )
        latestDesiredSessionFingerprint = desiredFingerprint
        val retainedSession = captureSession
        val primeModeSwitchFirstFrame = CameraModeSwitchFirstFramePrimePolicy.shouldPrime(
            refreshReason = refreshReason,
            hasPreviousSession = retainedSession != null && configuredSessionFingerprint != null,
            recordingActive = recordingActive,
            videoPreviewActive = videoPreviewActive,
            maximumResolutionStillCaptureActive = maximumResolutionStillCaptureActive,
            previousRawSurfaceActive =
                configuredSessionFingerprint?.streamGraph?.rawSensorSurfaceActive == true,
            nextRawSurfaceActive = streamGraph.rawSensorSurfaceActive
        )
        if (retainedSession != null && configuredSessionFingerprint != null &&
            configuredSessionFingerprint != desiredFingerprint
        ) {
            NeoLogger.i(
                "CameraController",
                "Camera session fingerprint changed reason=$refreshReason " +
                    "configured={${configuredSessionFingerprint?.diagnosticDescription()}} " +
                    "desired={${desiredFingerprint.diagnosticDescription()}}"
            )
        }
        if (CameraSessionConfigurationDeduplicationPolicy.shouldReuseConfiguredSession(
                hasActiveSession = retainedSession != null,
                configured = configuredSessionFingerprint,
                desired = desiredFingerprint,
                allowIdenticalRecoveryRebuild = allowIdenticalRecoveryRebuild
            )
        ) {
            clearSessionRefreshOwnership()
            recordFrameDeliveryLedger.markReconfigureSettled()
            // The retained session may still reference deferred reader surfaces. Its own onClosed
            // callback remains the only safe point at which those readers can be released.
            val retainedGeneration = sessionGeneration.get()
            val preserveNpv10Repeating = retainedSession != null &&
                canPreserveActiveNpv10RepeatingSequence(retainedSession, retainedGeneration)
            if (!preserveNpv10Repeating) {
                retainedSession?.let { startPreview(retainedGeneration, it) }
            } else {
                NeoLogger.i(
                    "CameraController",
                    "Equivalent session preserved active NPV10 repeating sequence " +
                        "reason=$refreshReason generation=$retainedGeneration"
                )
            }
            NeoLogger.i(
                "CameraController",
                "Camera session REUSED identical configuration reason=$refreshReason " +
                    desiredFingerprint.compactDescription()
            )
            notifySessionGenerationConfigured(sessionGeneration.get())
            onPreviousSessionClosed?.let { callback ->
                runCatching(callback)
                    .onFailure { Log.w(TAG, "Equivalent-session release callback failed", it) }
            }
            runCatching(onConfigured)
                .onFailure { Log.w(TAG, "Equivalent-session configured callback failed", it) }
            runTrailingSessionRefreshIfNeeded()
            return
        }

        // Final admission and generation advance are one transaction with stop-fence installation.
        // This is deliberately rechecked after fingerprint work: the fence may have appeared while
        // this Camera2-thread invocation was computing its desired stream graph.
        val generation = synchronized(cameraSubmissionLock) {
            if (deferSessionRefreshBehindP010StopBoundary(
                    rebuildReaders = false,
                    forceRebuild = true,
                    allowIdenticalRecoveryRebuild = allowIdenticalRecoveryRebuild,
                    reason = refreshReason,
                    onConfigured = onConfigured,
                    onPreviousSessionClosed = onPreviousSessionClosed
                )
            ) {
                // The current rebuild acquired sessionRefreshPending and opened the cadence window;
                // the queued trailing request will reacquire both after the exact stop owner retires.
                clearSessionRefreshOwnership()
                recordFrameDeliveryLedger.markReconfigureSettled()
                return
            }
            prepareForCameraSessionRebuild(dev)
            advanceSessionGeneration("create-session:$refreshReason")
        }
        inFlightSessionFingerprint = desiredFingerprint
        inFlightSessionFingerprintGeneration = generation
        pendingSessionCreateGeneration = generation
        pendingSessionCreateStartElapsedMs = SystemClock.elapsedRealtime()
        val previousSessionForHotSwap = captureSession
        Log.i(
            TAG,
            "Cinema stream graph session mode=${streamGraph.mode} " +
                "surfaces=${surfaces.size} fallback=${streamGraph.fallbackDropOrder.joinToString(">")} " +
                "reason=$refreshReason recovery=$allowIdenticalRecoveryRebuild"
        )
        val surfaceKindSummary = surfaces.joinToString(",") { sessionSurfaceKindFor(it).name }
        val cadenceSafeJpegDetached = p010CadenceSafeSessionActive(streamGraph) &&
            jpegReader?.surface?.let { jpeg -> surfaces.none { it === jpeg } } == true
        val recordingSurfaceBudgetConstrained = recordingSessionSurfaceBudgetConstrained(streamGraph)
        val thermalAnalysisSurfaceDetached =
            recordingActive &&
                recordingThermalAnalysisIsolationEnabled &&
                yuvReader?.surface?.let { yuv -> surfaces.none { it === yuv } } != false
        NeoLogger.i(
            "CameraController",
            "createSession attempt mode=${streamGraph.mode} hwLevel=${hardwareLevelLabel(hardwareLevel)} " +
                "surfaces=${surfaces.size}[$surfaceKindSummary] hwImport=$includeHardwareImport " +
                "useCaseHints=$applyUseCaseHints recordingActive=$recordingActive rawVideo=$rawVideoCaptureEnabled " +
                "npv10=$npv10TenBitCaptureEnabled cadenceSafeJpegDetached=$cadenceSafeJpegDetached " +
                "recordingSurfaceBudgetConstrained=$recordingSurfaceBudgetConstrained " +
                "thermalAnalysisSurfaceDetached=$thermalAnalysisSurfaceDetached"
        )
        try {
            fun submitIfSnapshotIsCurrent(submit: () -> Unit): CameraSessionSurfaceSubmissionPolicy.Decision =
                synchronized(cameraSubmissionLock) {
                    val decision = evaluateSessionSurfaceSubmission(
                        device = dev,
                        generation = generation,
                        snapshot = surfaceSnapshot,
                        desiredFingerprint = desiredFingerprint,
                        includeHardwareImport = includeHardwareImport,
                        applyUseCaseHints = applyUseCaseHints
                    )
                    if (decision.shouldSubmit) submit()
                    decision
                }

            val appliedSurfaceProfiles = mutableListOf<AppliedSurfaceDynamicRangeProfile>()
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    runOnCameraThread {
                        if (cameraDevice == null || generation != sessionGeneration.get()) {
                            settleFailedSessionCreateAttempt(generation)
                            s.close()
                            return@runOnCameraThread
                        }
                        val waitsForPreviousSessionRelease =
                            previousSessionForHotSwap != null && previousSessionForHotSwap !== s
                        var previousSessionWasAlreadyClosed = false
                        if (!waitsForPreviousSessionRelease) {
                            clearSessionRefreshOwnership()
                        }
                        // s1-f05: replacement session is live — close the reconfigure transition window.
                        recordFrameDeliveryLedger.markReconfigureSettled()
                        if (previousSessionForHotSwap != null && previousSessionForHotSwap !== s) {
                            // Always create the barrier entry. Previously this happened only when an
                            // optional external callback existed, so ordinary NDR mode changes cleared
                            // sessionRefreshPending immediately and launched a third session while the
                            // outgoing Qualcomm session was still closing.
                            previousSessionWasAlreadyClosed = registerPreviousSessionReleaseBarrier(
                                previousSessionForHotSwap,
                                onPreviousSessionClosed
                            )
                            runCatching { previousSessionForHotSwap.close() }
                                .onFailure { Log.w(TAG, "Failed to close previous hot-swap session", it) }
                        } else {
                            onPreviousSessionClosed?.invoke()
                        }
                        captureSession = s
                        val profileSnapshot = AppliedDynamicRangeSessionSnapshot(
                            session = s,
                            generation = generation,
                            surfaceProfiles = appliedSurfaceProfiles.toList()
                        )
                        appliedDynamicRangeSessionSnapshot = profileSnapshot
                        lastAppliedPreviewHdrProfile = profileSnapshot.bindingFor(previewSurface)?.profile
                        lastAppliedEncoderHdrProfile = profileSnapshot.bindingFor(recordSurface)?.profile
                        configuredSessionFingerprint = desiredFingerprint
                        clearInFlightSessionFingerprint(generation)
                        synchronized(sessionCollectionsLock) {
                            configuredSessionSurfaces[s] = identitySurfaceSet(surfaces)
                            configuredSessionGenerations[s] = generation
                        }
                        captureResultSessionBinding.set(
                            CaptureResultSessionBinding(session = s, generation = generation)
                        )
                        // RC5: the fresh session is now the frame source (outgoing session closed
                        // above) — safe to arm the AWB-lock deferral so ONLY fresh frames drive it.
                        maybeArmPostRebuildColorHold()
                        startPreviewInternal(
                            expectedGeneration = generation,
                            expectedSession = s,
                            authorizedRawResumeBoundary = null,
                            primeModeSwitchFirstFrame = primeModeSwitchFirstFrame
                        )
                        notifySessionGenerationConfigured(generation)
                        NeoLogger.i(
                            "CameraController",
                            "Camera session CONFIGURED ok mode=${streamGraph.mode} " +
                                "surfaces=${surfaces.size}[$surfaceKindSummary] reason=$refreshReason " +
                                "latencyMs=${(SystemClock.elapsedRealtime() - pendingSessionCreateStartElapsedMs).coerceAtLeast(0L)}"
                        )
                        pendingSessionCreateGeneration = 0L
                        pendingSessionCreateStartElapsedMs = 0L
                        onConfigured()
                        if (waitsForPreviousSessionRelease) {
                            val previous = previousSessionForHotSwap
                            if (previousSessionWasAlreadyClosed) {
                                completePreviousSessionRelease(previous, fallback = false)
                            } else {
                                schedulePreviousSessionReleaseFallback(previous)
                            }
                        } else {
                            runTrailingSessionRefreshIfNeeded()
                        }
                    }
                }
                override fun onClosed(s: CameraCaptureSession) {
                    cameraReleaseAcknowledgements.acknowledgeSession(s)
                    runOnCameraThread {
                        if (captureSession === s) {
                            clearCaptureResultSessionBinding(s)
                            configuredSessionFingerprint = null
                            appliedDynamicRangeSessionSnapshot = null
                            lastAppliedPreviewHdrProfile = null
                            lastAppliedEncoderHdrProfile = null
                        }
                        synchronized(sessionCollectionsLock) {
                            configuredSessionSurfaces.remove(s)
                            configuredSessionGenerations.remove(s)
                            if (
                                !previousSessionClosedCallbacks.containsKey(s) &&
                                sessionRefreshPending.get() &&
                                captureSession === s
                            ) {
                                sessionsClosedBeforeReleaseBarrier.add(s)
                            }
                        }
                        closeDeferredReadersSafely(s, "owning hot-swap session closed")
                        completePreviousSessionRelease(s, fallback = false)
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    // Camera2 requires closing a session that failed to configure so its surfaces /
                    // ImageReader buffers are released. Without this, every fallback retry leaks native
                    // graphics memory and can eventually wedge the camera HAL ("too many buffers").
                    runCatching { s.close() }
                    if (cameraDevice == null || generation != sessionGeneration.get()) {
                        settleFailedSessionCreateAttempt(generation)
                        Log.d(TAG, "Ignoring stale Camera2 session configuration failure generation=$generation current=${sessionGeneration.get()}")
                        return
                    }
                    clearInFlightSessionFingerprint(generation)
                    if (maximumResolutionStillCaptureActive) {
                        settleFailedSessionCreateAttempt(generation)
                        val token = maximumResolutionStillCaptureToken
                        if (token != null) {
                            failStillCaptureRequest(
                                token,
                                "This lens cannot combine its maximum-resolution sensor mode with a live preview"
                            )
                        } else {
                            maximumResolutionStillCaptureActive = false
                            Log.e(TAG, "Maximum-resolution still session failed without an owning token")
                        }
                        return
                    }
                    val standbyRawOutput =
                        streamGraph.rawSensor.reason == MODE_SWITCH_RAW_STANDBY_REASON &&
                            surfaces.any { it === rawReader?.surface }
                    val standbyP010Output =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            (streamGraph.yuvAnalysis.reason == MODE_SWITCH_YUV_STANDBY_REASON ||
                            !streamGraph.yuvAnalysis.includeInSession) &&
                            surfaces.any { it === yuvReader?.surface } &&
                            yuvReader?.imageFormat == ImageFormat.YCBCR_P010
                    if (standbyRawOutput || standbyP010Output) {
                        modeSwitchStandbyOutputsRejectedCameraId = dev.id
                        Log.i(
                            TAG,
                            "Camera session rejected optional RAW/P010 mode-switch standby outputs; " +
                                "retrying the active full-quality graph"
                        )
                        setupReaders()
                        createSession(
                            onConfigured = onConfigured,
                            includeHardwareImport = includeHardwareImport,
                            applyUseCaseHints = applyUseCaseHints,
                            allowIdenticalRecoveryRebuild = true,
                            refreshReason = "$refreshReason/fallback-mode-switch-standby",
                            onPreviousSessionClosed = onPreviousSessionClosed,
                            surfaceRecoveryAttempt = surfaceRecoveryAttempt
                        )
                        return
                    }
                    if (persistentPreviewOutputsEnabled()) {
                        persistentPreviewOutputsRejectedCameraId = dev.id
                        replacePersistentPreviewSessionSurfaces(null, null)
                        previewWarmupSurface = null
                        persistentPreviewProfilesCanShareRequest = false
                        Log.w(
                            TAG,
                            "Camera session rejected persistent SDR+HDR preview outputs; " +
                                "retrying the active preview output without reducing capture quality"
                        )
                        createSession(
                            onConfigured = onConfigured,
                            includeHardwareImport = includeHardwareImport,
                            applyUseCaseHints = applyUseCaseHints,
                            allowIdenticalRecoveryRebuild = true,
                            refreshReason = "$refreshReason/fallback-single-preview",
                            onPreviousSessionClosed = onPreviousSessionClosed,
                            surfaceRecoveryAttempt = surfaceRecoveryAttempt
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
                            allowIdenticalRecoveryRebuild = true,
                            refreshReason = "$refreshReason/fallback-hardware-import",
                            onPreviousSessionClosed = onPreviousSessionClosed,
                            surfaceRecoveryAttempt = surfaceRecoveryAttempt
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
                            allowIdenticalRecoveryRebuild = true,
                            refreshReason = "$refreshReason/fallback-analysis",
                            onPreviousSessionClosed = onPreviousSessionClosed,
                            surfaceRecoveryAttempt = surfaceRecoveryAttempt
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
                            allowIdenticalRecoveryRebuild = true,
                            refreshReason = "$refreshReason/fallback-recording-safe",
                            onPreviousSessionClosed = onPreviousSessionClosed,
                            surfaceRecoveryAttempt = surfaceRecoveryAttempt
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
                            allowIdenticalRecoveryRebuild = true,
                            refreshReason = "$refreshReason/fallback-sidecar",
                            onPreviousSessionClosed = onPreviousSessionClosed,
                            surfaceRecoveryAttempt = surfaceRecoveryAttempt
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
                            allowIdenticalRecoveryRebuild = true,
                            refreshReason = "$refreshReason/fallback-use-case",
                            onPreviousSessionClosed = onPreviousSessionClosed,
                            surfaceRecoveryAttempt = surfaceRecoveryAttempt
                        )
                        return
                    }
                    Log.e(TAG, "Camera session configuration failed")
                    emitCameraPipelineWarning(
                        CameraPipelineDiagnostics.experimentalFallbackWarning(
                            "Camera session configuration failed"
                        )
                    )
                    settleFailedSessionCreateAttempt(generation)
                    runTrailingSessionRefreshIfNeeded()
                }
            }
            val submissionDecision = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfigs = surfaces.map { surface ->
                    val config = OutputConfiguration(surface)
                    // VIDEO_ENCODE ImageReaders otherwise default to MONOTONIC while the analysis
                    // and RAW readers use SENSOR. Configure identity at its source; never infer a
                    // different physical frame from callback delay or a suspend-clock offset.
                    val sensorTimestampBaseApplied = CameraImageTimestampBasePolicy.apply(
                        output = config,
                        appOwnedImageReader = surface == hardwareImportReader?.surface ||
                            surface == yuvReader?.surface || surface == rawReader?.surface ||
                            surface == jpegReader?.surface
                    )
                    if (sensorTimestampBaseApplied) {
                        Log.i(TAG, "CAMERA_OUTPUT_TIMESTAMP_BASE kind=${sessionSurfaceKindFor(surface)} base=SENSOR")
                    }
                    val role = hdrSurfaceRoleFor(surface)
                    val hdrProfile = if (
                        surface === persistentSdrPreviewSurface ||
                        surface === persistentHdrPreviewSurface
                    ) {
                        HdrDynamicRangePolicy.applyResolvedProfile(
                            config,
                            resolvedDynamicRangeProfileForSessionSurface(surface),
                            role
                        )
                    } else {
                        HdrDynamicRangePolicy.apply(
                            config,
                            hdrDynamicRangeMode,
                            hdrSupport,
                            role
                        )
                    }
                    appliedSurfaceProfiles += AppliedSurfaceDynamicRangeProfile(surface, hdrProfile)
                    if (applyUseCaseHints) {
                        StreamUseCasePolicy.apply(config, characteristics, streamUseCaseRoleFor(surface))
                    }
                    config
                }
                val pendingPreviewProfile = appliedSurfaceProfiles
                    .firstOrNull { it.surface === previewSurface }
                    ?.profile
                val pendingEncoderProfile = appliedSurfaceProfiles
                    .firstOrNull { it.surface === recordSurface }
                    ?.profile
                Log.i(
                    TAG,
                    "HDR session profiles requested=$hdrDynamicRangeMode " +
                        "preview=${pendingPreviewProfile ?: HdrDynamicRangePolicy.PROFILE_STANDARD} " +
                        "encoder=${pendingEncoderProfile ?: HdrDynamicRangePolicy.PROFILE_STANDARD} " +
                        "support=$hdrSupport"
                )
                val sessionConfiguration = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    cameraCallbackExecutor,
                    callback
                )
                val streamFpsOverride = desiredFingerprint.streamBasedFpsPlan
                // The MAXIMUM_RESOLUTION stream configuration only binds when the pixel mode is also a
                // session parameter (not just per-request), so stamp it here too when the tier is active.
                val engageMaxResSessionParam =
                    desiredFingerprint.maximumResolutionSessionParameterApplied
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
                            if (engageMaxResSessionParam && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                sessionBuilder[CaptureRequest.SENSOR_PIXEL_MODE] =
                                    CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                                applied = true
                            }
                            if (applied) {
                                sessionConfiguration.setSessionParameters(sessionBuilder.build())
                            }
                        }
                    }.onFailure { error ->
                        // A closed CameraDevice is a lifecycle transition, not an optional-parameter
                        // failure. Let the outer policy suppress it during teardown or reopen the
                        // camera when the current device closed unexpectedly.
                        if (error is IllegalStateException) throw error
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
                submitIfSnapshotIsCurrent {
                    dev.createCaptureSession(sessionConfiguration)
                }
            } else {
                surfaces.forEach { surface ->
                    appliedSurfaceProfiles += AppliedSurfaceDynamicRangeProfile(surface, null)
                }
                submitIfSnapshotIsCurrent {
                    @Suppress("DEPRECATION")
                    dev.createCaptureSession(surfaces, callback, backgroundHandler)
                }
            }
            if (!submissionDecision.shouldSubmit) {
                val rejection = submissionDecision.rejection
                if (CameraSessionSurfaceSubmissionPolicy.isRecoverableSurfaceRace(rejection)) {
                    if (retrySessionCreateAfterSurfaceRace(
                            device = dev,
                            generation = generation,
                            includeHardwareImport = includeHardwareImport,
                            applyUseCaseHints = applyUseCaseHints,
                            allowIdenticalRecoveryRebuild = true,
                            refreshReason = refreshReason,
                            surfaceRecoveryAttempt = surfaceRecoveryAttempt,
                            failureDescription = rejection.name,
                            onConfigured = onConfigured,
                            onPreviousSessionClosed = onPreviousSessionClosed
                        )
                    ) {
                        return
                    }
                    val message = "Camera session Surface ownership did not stabilize: ${rejection.name}"
                    Log.w(TAG, message)
                    emitCameraPipelineWarning(CameraPipelineDiagnostics.experimentalFallbackWarning(message))
                    runTrailingSessionRefreshIfNeeded()
                } else {
                    val ownedFailure = settleFailedSessionCreateAttempt(generation)
                    Log.d(TAG, "Camera session submission cancelled: ${rejection.name}")
                    if (ownedFailure) {
                        onConfigured()
                        onPreviousSessionClosed?.invoke()
                        runTrailingSessionRefreshIfNeeded()
                    }
                }
                return
            }
        } catch (e: IllegalStateException) {
            if (CameraSessionSurfaceSubmissionPolicy.isSurfaceLifecycleFailure(e)) {
                if (retrySessionCreateAfterSurfaceRace(
                        device = dev,
                        generation = generation,
                        includeHardwareImport = includeHardwareImport,
                        applyUseCaseHints = applyUseCaseHints,
                        allowIdenticalRecoveryRebuild = true,
                        refreshReason = refreshReason,
                        surfaceRecoveryAttempt = surfaceRecoveryAttempt,
                        failureDescription = e.message ?: e.javaClass.simpleName,
                        onConfigured = onConfigured,
                        onPreviousSessionClosed = onPreviousSessionClosed
                    )
                ) {
                    return
                }
                emitCameraPipelineWarning(
                    CameraPipelineDiagnostics.experimentalFallbackWarning(
                        e.message ?: "Camera session Surface lifecycle failure"
                    )
                )
                runTrailingSessionRefreshIfNeeded()
                return
            }
            handleCreateSessionIllegalState(
                error = e,
                device = dev,
                generation = generation
            )
        } catch (e: Exception) {
            if (CameraSessionSurfaceSubmissionPolicy.isSurfaceLifecycleFailure(e)) {
                if (retrySessionCreateAfterSurfaceRace(
                        device = dev,
                        generation = generation,
                        includeHardwareImport = includeHardwareImport,
                        applyUseCaseHints = applyUseCaseHints,
                        allowIdenticalRecoveryRebuild = true,
                        refreshReason = refreshReason,
                        surfaceRecoveryAttempt = surfaceRecoveryAttempt,
                        failureDescription = e.message ?: e.javaClass.simpleName,
                        onConfigured = onConfigured,
                        onPreviousSessionClosed = onPreviousSessionClosed
                    )
                ) {
                    return
                }
            } else {
                settleFailedSessionCreateAttempt(generation)
            }
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
        val ownedFailure = settleFailedSessionCreateAttempt(generation)
        if (ownedFailure) runTrailingSessionRefreshIfNeeded()
        if (decision.shouldIgnoreFailure) {
            Log.d(TAG, "createSession: ${decision.message} (normal during teardown)")
            return
        }
        if (decision.shouldRecoverCamera) {
            Log.w(TAG, "createSession: ${decision.message}; reopening camera", error)
            advanceSessionGeneration("create-session-recovery")
            val previousSession = captureSession
            clearCaptureResultSessionBinding(previousSession)
            captureSession = null
            clearAppliedDynamicRangeSnapshotFor(previousSession)
            clearSessionConfigurationFingerprints()
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
        sessionRefreshDispatchTracker.resumeLifecycle()
        createSession(onReady)
    }

    override fun onSessionConfigured(session: CameraCaptureSession, generation: Long, onConfigured: () -> Unit) {
        notifySessionGenerationConfigured(generation)
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

    fun beginBatchUpdate() {
        synchronized(refreshBatchLock) {
            refreshBatchDepth.incrementAndGet()
        }
    }
    fun endBatchUpdate() {
        var request: PendingSessionRefreshRequest? = null
        var replayRepeatingRequest = false
        val depth = synchronized(refreshBatchLock) {
            val remaining = refreshBatchDepth.decrementAndGet()
            if (remaining == 0) {
                request = batchedSessionRefreshRequest
                batchedSessionRefreshRequest = null
                val sessionRefreshQueued = request != null
                batchSessionRefreshHandoffPending = sessionRefreshQueued
                replayRepeatingRequest = CameraRepeatingRefreshAdmissionPolicy.shouldReplayAfterBatch(
                    repeatingRefreshDeferred = batchedRepeatingRequestRefreshPending,
                    sessionRefreshQueued = sessionRefreshQueued
                )
                batchedRepeatingRequestRefreshPending = false
            } else if (remaining < 0) {
                refreshBatchDepth.set(0)
                batchedRepeatingRequestRefreshPending = false
                batchSessionRefreshHandoffPending = false
            }
            remaining
        }
        if (depth > 0) return
        if (depth < 0) {
            Log.w(TAG, "endBatchUpdate called without a matching beginBatchUpdate")
            return
        }
        if (request != null) {
            val batchedRequest = request
            refreshOutputsAndSession(
                rebuildReaders = batchedRequest.rebuildReaders,
                forceRebuild = batchedRequest.forceRebuild,
                allowIdenticalRecoveryRebuild = batchedRequest.allowIdenticalRecoveryRebuild,
                reason = batchedRequest.reasons.joinToString("+"),
                onConfigured = {
                    batchedRequest.onConfiguredCallbacks.forEach { callback ->
                        runCatching(callback)
                            .onFailure { Log.w(TAG, "Batched session refresh callback failed", it) }
                    }
                },
                onPreviousSessionClosed = {
                    batchedRequest.onPreviousSessionClosedCallbacks.forEach { callback ->
                        runCatching(callback)
                            .onFailure { Log.w(TAG, "Batched previous-session callback failed", it) }
                    }
                }
            )
        } else if (replayRepeatingRequest) {
            restartRepeatingRequestForRecordTargetChange()
        }
    }

    fun hasCameraFacing(facing: Int): Boolean {
        return cameraManager.cameraIdList.any { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    override fun setAiDrivenFocusDistance(dist: Float, confidence: Float) {
        if (!dist.isFinite() || !confidence.isFinite() || confidence <= 0f) {
            clearAiDrivenFocusDistance()
            return
        }
        if (minFocusDist <= 0f) {
            clearAiDrivenFocusDistance()
            return
        }
        val resolvedDistance = dist.coerceIn(0f, minFocusDist)
        val generation = sessionGeneration.get()
        val resolvedSignal = AiFocusControlSignal.create(
            distanceDiopters = resolvedDistance,
            confidence = confidence,
            sessionGeneration = generation
        )
        val previous = aiFocusControlSignal
        if (previous.isCurrent(generation) &&
            abs(previous.distanceDiopters - resolvedSignal.distanceDiopters) < 0.01f &&
            abs(previous.confidence - resolvedSignal.confidence) < 0.01f
        ) return
        aiFocusControlSignal = resolvedSignal
        updatePreviewFromAutonomousControlFeedback()
    }

    override fun clearAiDrivenFocusDistance() {
        aiDrivenFocusDist = -1f
        updatePreviewFromAutonomousControlFeedback()
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
        cancelCinematicFocusPull()
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
                updatePreviewFromAutonomousControlFeedback()
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
        // A tap is allowed to re-meter exposure, but the mode-specific white-balance plan remains
        // authoritative. In VIDEO/PRO/NDR/LOG that plan deliberately holds a converged white point;
        // overriding CONTROL_AWB_LOCK here made the final request contradict the stability policy and
        // caused a visible colour-temperature jump on every tap. Idle photo preview still gets an
        // unlocked AWB because whiteBalancePlan.awbLock is already false for that mode.
        builder[CaptureRequest.CONTROL_AE_LOCK] = false
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
        activeP010StopBoundary.get()?.let { owner ->
            p010StopDeferredRequestRefresh.defer(owner)
            return
        }
        if (activeRawResumeBoundary.get() != null) {
            backgroundHandler?.postDelayed(
                { runAutoFocusTrigger(focusRegion, exposureRegion) },
                50L
            )
            return
        }
        val requestedAtElapsedNs = SystemClock.elapsedRealtimeNanos()
        tapAfRequestStartedElapsedNs = requestedAtElapsedNs
        persistentAfRegion = focusRegion
        persistentAeRegion = exposureRegion
        persistentAwbRegion = null
        beginTapFocusMeteringUnlock()

        runUrgentCameraControl("autofocus") {
            // The urgent action is posted after the caller-side check above. Recheck on the Camera2
            // thread so an AF request queued just before RAW resume cannot overtake its marker.
            activeP010StopBoundary.get()?.let { owner ->
                p010StopDeferredRequestRefresh.defer(owner)
                return@runUrgentCameraControl
            }
            if (activeRawResumeBoundary.get() != null) {
                backgroundHandler?.postDelayed(
                    { runAutoFocusTrigger(focusRegion, exposureRegion) },
                    50L
                )
                return@runUrgentCameraControl
            }
            val builder = previewBuilder ?: return@runUrgentCameraControl
            val sess = captureSession ?: return@runUrgentCameraControl
            if (!previewBuilderHasTargets) {
                Log.d(TAG, "runAutoFocusTrigger skipped: preview request has no active surface target")
                return@runUrgentCameraControl
            }
            tapAfScanActive = true
            tapAfTriggerFrameNumber = -1L
            backgroundHandler?.removeCallbacks(tapAfScanTimeoutRunnable)
            var triggerSubmitted = false
            try {
                val repeatingRequest = buildSoftwareAeTrackedRequest(builder) {
                    applyAll(builder, false)
                    builder[CaptureRequest.CONTROL_AF_MODE] = singleShotAutoFocusMode()
                    // Keep AUTO/IDLE installed as the repeating request while the lens scans.
                    // Switching immediately back to CONTINUOUS_* cancels scans on many HALs.
                    builder.set(
                        CaptureRequest.CONTROL_AF_TRIGGER,
                        CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                    )
                    builder.setTag(null)
                }
                val requestControlRevision = softwareAeControlRevisionFor(repeatingRequest)
                val triggerRequest = buildSoftwareAeTrackedRequestForRevision(
                    builder = builder,
                    controlRevision = requestControlRevision
                ) {
                    builder.setTag(tapAutoFocusRequestTag)
                    builder.set(
                        CaptureRequest.CONTROL_AF_TRIGGER,
                        CaptureRequest.CONTROL_AF_TRIGGER_START
                    )
                }
                builder.setTag(null)
                builder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                )
                val repeatingSubmitted = submitTrackedRepeatingRequest(
                    session = sess,
                    request = repeatingRequest,
                    callback = captureCallback,
                    p010Surface = previewBuilderTargetSnapshot
                        .firstOrNull { target -> target === yuvReader?.surface }
                ) != null
                val triggerSequence = if (repeatingSubmitted) {
                    submitTrackedCaptureRequest(
                        session = sess,
                        request = triggerRequest,
                        callback = captureCallback,
                        targetsP010 = previewBuilderTargetSnapshot.any { target ->
                            target === yuvReader?.surface
                        }
                    )
                } else {
                    null
                }
                triggerSubmitted = repeatingSubmitted && triggerSequence != null
                Log.d(
                    TAG,
                    "AF trigger submitted dispatchMs=${(SystemClock.elapsedRealtimeNanos() - requestedAtElapsedNs) / 1_000_000.0}"
                )
            } catch (e: IllegalStateException) {
                Log.d(TAG, "runAutoFocusTrigger: session closed (normal during teardown)")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "runAutoFocusTrigger skipped invalid request during mode transition", e)
            } finally {
                builder.setTag(null)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            if (triggerSubmitted) {
                backgroundHandler?.postDelayed(tapAfScanTimeoutRunnable, TAP_AF_SCAN_TIMEOUT_MS)
            } else {
                completeTapAfScan(reason = "submit-failed", restoreContinuousAf = true)
            }
        }
    }

    private fun maybeCompleteTapAfScan(result: TotalCaptureResult) {
        if (!tapAfScanActive) return
        val triggerFrame = tapAfTriggerFrameNumber
        if (triggerFrame < 0L || result.frameNumber < triggerFrame) return
        when (result.get(CaptureResult.CONTROL_AF_STATE)) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ->
                completeTapAfScan(reason = "focused", restoreContinuousAf = true)
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ->
                completeTapAfScan(reason = "not-focused", restoreContinuousAf = true)
        }
    }

    private fun completeTapAfScan(reason: String, restoreContinuousAf: Boolean) {
        if (!tapAfScanActive) return
        tapAfScanActive = false
        tapAfTriggerFrameNumber = -1L
        backgroundHandler?.removeCallbacks(tapAfScanTimeoutRunnable)
        val startedNs = tapAfRequestStartedElapsedNs
        tapAfRequestStartedElapsedNs = 0L
        val totalMs = if (startedNs > 0L) {
            (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0
        } else {
            -1.0
        }
        Log.d(TAG, "Tap AF scan completed reason=$reason totalMs=$totalMs")
        if (restoreContinuousAf) updatePreview(immediate = true)
    }

    private fun cancelTapAfScanForSessionTransition() {
        tapAfScanActive = false
        tapAfTriggerFrameNumber = -1L
        tapAfRequestStartedElapsedNs = 0L
        backgroundHandler?.removeCallbacks(tapAfScanTimeoutRunnable)
    }

    /** True when the device advertises any real (non-OFF) autofocus mode. */
    private fun hasAutoFocusCapability(): Boolean {
        val modes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return false
        return modes.any { it != CaptureRequest.CONTROL_AF_MODE_OFF }
    }

    override fun captureStill(
        token: StillCaptureRequestToken,
        captureRaw: Boolean,
        captureJpeg: Boolean,
        overrides: StillCaptureOverrides?
    ) {
        val ownedToken = token.copy(operationEpoch = nextStillOperationEpoch())
        activeStillCaptureToken = ownedToken
        runPrioritizedCameraControl(ownedToken) {
            beginStillCaptureWhenControllerStable(ownedToken, captureRaw, captureJpeg, overrides)
        }
    }

    private fun beginStillCaptureWhenControllerStable(
        token: StillCaptureRequestToken,
        captureRaw: Boolean,
        captureJpeg: Boolean,
        overrides: StillCaptureOverrides?,
        attempt: Int = 0
    ) {
        if (!ownsStillOperation(token)) return
        val restoredStaleMaximumSession = releaseStaleStillResourcesBefore(token)
        if (restoredStaleMaximumSession || sessionRefreshPending.get()) {
            if (attempt >= STILL_CAPTURE_SESSION_WAIT_MAX_ATTEMPTS) {
                failStillCaptureRequest(token, "Camera session recovery did not settle for photo capture")
                return
            }
            val handler = backgroundHandler ?: run {
                failStillCaptureRequest(token, "Camera thread is not available for photo capture")
                return
            }
            handler.postDelayed(
                {
                    beginStillCaptureWhenControllerStable(
                        token,
                        captureRaw,
                        captureJpeg,
                        overrides,
                        attempt + 1
                    )
                },
                STILL_CAPTURE_SESSION_WAIT_INTERVAL_MS
            )
            return
        }
        val dedicatedMaximumResolution =
            shouldUseDedicatedMaximumResolutionStill(captureRaw, captureJpeg)
        val dedicatedP010Cadence =
            shouldUseDedicatedP010CadenceStill(captureRaw, captureJpeg)
        if (dedicatedMaximumResolution || dedicatedP010Cadence) {
            maximumResolutionStillCaptureActive = dedicatedMaximumResolution
            p010CadenceStillCaptureActive = dedicatedP010Cadence
            maximumResolutionStillCaptureToken = token
            Log.i(
                TAG,
                "Preparing dedicated still session reason=" +
                    if (dedicatedMaximumResolution) {
                        "maximum-resolution size=" +
                            maximumResolutionSessionPhotoSizes.firstOrNull()
                                ?.let { "${it.width}x${it.height}" }
                    } else {
                        "P010-cadence JPEG isolation"
                    }
            )
            refreshOutputsAndSession(
                rebuildReaders = true,
                forceRebuild = true,
                onConfigured = {
                    if ((maximumResolutionStillCaptureActive || p010CadenceStillCaptureActive) &&
                        sameStillOperation(maximumResolutionStillCaptureToken, token) &&
                        ownsStillOperation(token)
                    ) {
                        submitStillCaptureOnCameraThread(token, captureRaw, captureJpeg, overrides)
                    }
                }
            )
            return
        }
        submitStillCaptureOnCameraThread(token, captureRaw, captureJpeg, overrides)
    }

    private fun submitStillCaptureWhenSessionStable(
        token: StillCaptureRequestToken,
        captureRaw: Boolean,
        captureJpeg: Boolean,
        overrides: StillCaptureOverrides?,
        attempt: Int = 0
    ) {
        if (!ownsStillOperation(token)) return
        if (activeRawResumeBoundary.get() != null) {
            val handler = backgroundHandler
            if (handler == null) {
                failStillCaptureRequest(token, "Camera thread is not available for photo capture")
                return
            }
            handler.postDelayed(
                {
                    submitStillCaptureWhenSessionStable(
                        token = token,
                        captureRaw = captureRaw,
                        captureJpeg = captureJpeg,
                        overrides = overrides,
                        attempt = attempt
                    )
                },
                STILL_CAPTURE_SESSION_WAIT_INTERVAL_MS
            )
            return
        }
        if (sessionRefreshPending.get()) {
            if (attempt >= STILL_CAPTURE_SESSION_WAIT_MAX_ATTEMPTS) {
                failStillCaptureRequest(token, "Camera mode transition did not settle in time for photo capture")
                return
            }
            val handler = backgroundHandler
            if (handler == null) {
                failStillCaptureRequest(token, "Camera thread is not available for photo capture")
                return
            }
            if (attempt == 0) {
                Log.i(TAG, "Photo capture queued until the active Camera2 session rebuild settles")
            }
            handler.postDelayed(
                {
                    submitStillCaptureWhenSessionStable(
                        token = token,
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
            token = token,
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
        val normalArea = normalSessionPhotoSizes.maxOfOrNull { CameraSensorInfo.sizeArea(it) } ?: 0L
        val maximumArea = maximumResolutionSessionPhotoSizes
            .maxOfOrNull { CameraSensorInfo.sizeArea(it) } ?: 0L
        return StillPhotoResolutionPolicy.shouldUseDedicatedMaximumResolutionSession(
            maximumResolutionEnabled = maximumResolutionStillEnabled,
            recordingActive = recordingActive,
            captureRaw = captureRaw,
            captureJpeg = captureJpeg,
            maximumResolutionCaptureActive = maximumResolutionStillCaptureActive,
            platformSupportsMaximumResolution = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                supportsCaptureRequestKey(CaptureRequest.SENSOR_PIXEL_MODE),
            normalMaximumArea = normalArea,
            maximumResolutionArea = maximumArea
        )
    }

    private fun shouldUseDedicatedP010CadenceStill(
        captureRaw: Boolean,
        captureJpeg: Boolean
    ): Boolean {
        if (!P010CadenceSafeSessionFlag.enabled ||
            recordingActive ||
            !captureJpeg ||
            captureRaw ||
            maximumResolutionStillCaptureActive ||
            p010CadenceStillCaptureActive ||
            !npv10TenBitCaptureEnabled ||
            yuvReader?.imageFormat != ImageFormat.YCBCR_P010
        ) {
            return false
        }
        return !isSurfaceConfiguredInActiveSession(jpegReader?.surface)
    }

    private fun isSurfaceConfiguredInActiveSession(surface: Surface?): Boolean {
        val session = captureSession ?: return false
        surface ?: return false
        return synchronized(sessionCollectionsLock) {
            configuredSessionSurfaces[session]?.contains(surface) == true
        }
    }

    private fun submitStillCaptureOnCameraThread(
        token: StillCaptureRequestToken,
        captureRaw: Boolean,
        captureJpeg: Boolean,
        overrides: StillCaptureOverrides?,
        retryAttempt: Int = 0
    ) {
        if (!ownsStillOperation(token)) return
        // This function is reached both directly and through the stable-session retry. Gate at the
        // final Camera2 construction boundary so neither entry path can submit across a RAW marker.
        if (activeP010StopBoundary.get() != null) {
            failStillCaptureRequest(token, "Photo capture was gated by the exact P010 stop boundary")
            return
        }
        if (activeRawResumeBoundary.get() != null) {
            submitStillCaptureWhenSessionStable(
                token = token,
                captureRaw = captureRaw,
                captureJpeg = captureJpeg,
                overrides = overrides,
                attempt = retryAttempt
            )
            return
        }
        val dev = cameraDevice
        val session = captureSession
        if (dev == null || session == null) {
            failStillCaptureRequest(token, "Camera is not ready for photo capture")
            return
        }

        val recordingSnapshot = recordingActive
        // VIDEO_SNAPSHOT is valid only while an encoder recording target is active. Some vendor HALs
        // accept it during idle preview but defer the JPEG indefinitely because no recording lane is
        // running. Idle photos always use the sensor-truthful STILL_CAPTURE template.
        val videoSnapshot = recordingSnapshot
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
            rawStreamActive = isSurfaceConfiguredInActiveSession(rawReader?.surface),
            jpegStreamActive = isSurfaceConfiguredInActiveSession(jpegReader?.surface)
        )
        targetPlan.failureReason?.let { reason ->
            failStillCaptureRequest(token, reason)
            return
        }
        if (targetPlan.includeJpeg) {
            jpegReader?.surface?.let(builder::addTarget)
        }
        if (targetPlan.includeRaw) {
            rawReader?.surface?.let(builder::addTarget)
        }

        applyAll(
            builder,
            forStillCapture = true,
            videoSnapshot = videoSnapshot,
            cadenceProtectedSnapshot = videoSnapshot
        )
        builder.set(CaptureRequest.JPEG_QUALITY, effectiveJpegQuality().toByte())
        if (targetPlan.includeJpeg) {
            builder.set(CaptureRequest.JPEG_ORIENTATION, outputJpegOrientation())
        }
        builder.setTag(token)

        overrides?.let {
            it.iso?.let { iso -> builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso) }
            it.exposureNs?.let { exp -> builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp) }
            it.evCompensation?.let { ev -> builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev) }
            if (it.aeLock) builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
            applyStillCaptureAutoWhiteBalanceLock(builder, it.awbLock)
        }

        try {
            val rawRepeatingTargetActive =
                resolveCamera2PipelineRequestPlan(streamGraph).targets.includeRawSensor
            val pauseIdleRawRepeating = !videoSnapshot &&
                (targetPlan.includeJpeg || targetPlan.includeRaw) &&
                rawRepeatingTargetActive &&
                streamGraph.rawSensor.includeInSession &&
                rawReader != null
            if (pauseIdleRawRepeating) {
                // A genuinely targeted full-rate RAW stream can indefinitely hold a vendor HAL's still
                // stage or overtake a one-shot RAW in ImageReader. A merely preconfigured RAW surface is
                // not a repeating target and must not stop preview during an unrelated JPEG capture.
                session.stopRepeating()
                pausedRepeatingStillCaptureToken = token
                Log.i(TAG, "Paused idle RAW repeating stream for full-resolution JPEG capture")
            }
            val request = builder.build()
            val sequenceId = submitTrackedCaptureRequest(
                session = session,
                request = request,
                callback = captureCallback,
                targetsP010 = false
            ) ?: throw IllegalStateException("Photo capture crossed an active P010 stop fence")
            Log.i(
                TAG,
                    "Photo capture submitted sequence=$sequenceId generation=${sessionGeneration.get()} " +
                    "videoSnapshot=$videoSnapshot jpeg=${targetPlan.includeJpeg} raw=${targetPlan.includeRaw} " +
                    "recordingSnapshot=$recordingSnapshot " +
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
                            token = token,
                            captureRaw = captureRaw,
                            captureJpeg = captureJpeg,
                            overrides = overrides,
                            attempt = retryAttempt + 1
                        )
                    }
                )
            } else {
                Log.w(TAG, "captureStill request submission failed", error)
                failStillCaptureRequest(token, "Photo capture could not start: camera session closed")
            }
        } catch (error: Exception) {
            Log.w(TAG, "captureStill request submission failed", error)
            failStillCaptureRequest(token, "Photo capture could not start: ${error.message ?: "camera session error"}")
        }
    }

    private fun bindReaderCallback(
        kind: ReaderKind,
        reader: ImageReader,
        handler: Handler
    ) {
        val binding = readerBindings.bind(kind, reader)
        try {
            reader.setOnImageAvailableListener(
                { callbackReader -> dispatchBoundReaderReady(binding, callbackReader) },
                handler
            )
        } catch (failure: Throwable) {
            readerBindings.retire(binding)
            throw failure
        }
    }

    private fun dispatchBoundReaderReady(
        binding: ReaderBinding<ImageReader>,
        reader: ImageReader
    ) {
        when (binding.kind) {
            ReaderKind.JPEG -> dispatchJpegReady(binding, reader)
            ReaderKind.RAW -> dispatchRawReady(binding, reader)
            ReaderKind.YUV -> dispatchYuvReady(binding, reader)
            ReaderKind.HARDWARE_IMPORT -> dispatchHardwareReady(binding, reader)
        }
    }

    private fun dispatchJpegReady(binding: ReaderBinding<ImageReader>, reader: ImageReader) {
        readerCallbackAdmission.dispatch(binding, reader) {
            // Image ownership is transferred to StillCapture. It closes the Image, resolves the exact
            // request token by sensor timestamp, then acknowledges that token. Never restore a session
            // from this callback alone: a late JPEG from an older request must not disrupt a newer one.
            onJpegReady?.invoke(reader)
        }
    }

    private fun dispatchRawReady(binding: ReaderBinding<ImageReader>, reader: ImageReader) {
        readerCallbackAdmission.dispatch(binding, reader) {
            val owner = onRawReady
            if (owner != null) {
                owner.invoke(reader)
            } else {
                // RAW mode keeps the sensor reader targeted until the outgoing Camera2 session has
                // actually drained. Mode exit retires the project RAW owner first, so a final provider
                // callback can legitimately arrive with no consumer. Leaving that Image in the reader
                // ring back-pressures the HAL and was observed on-device as waitUntilIdle failure,
                // CAMERA_ERROR (4), and a multi-second black/frozen Raw -> Video transition. Acquire the
                // newest orphan and close it immediately; acquireLatestImage also discards older queued
                // images, preserving the hot-session handoff without changing any image-quality setting.
                runCatching { reader.acquireLatestImage()?.close() }
                    .onFailure { failure ->
                        Log.w(TAG, "Unable to drain unowned RAW reader callback", failure)
                    }
            }
        }
    }

    private fun dispatchYuvReady(binding: ReaderBinding<ImageReader>, reader: ImageReader) {
        readerCallbackAdmission.dispatch(binding, reader) {
            previewHealthMonitor.trackYuvFrame()
            onYuvFrame?.invoke(reader)
        }
    }

    private fun dispatchHardwareReady(binding: ReaderBinding<ImageReader>, reader: ImageReader) {
        readerCallbackAdmission.dispatch(binding, reader) {
            previewHealthMonitor.trackYuvFrame()
            onHardwareFrame?.invoke(reader)
        }
    }

    override fun acknowledgeStillJpegBufferReleased(token: StillCaptureRequestToken) {
        runPrioritizedCameraControl(token) {
            if (!ownsStillOperation(token)) return@runPrioritizedCameraControl
            // A combined JPEG+RAW request can deliver JPEG first. Resuming the RAW repeating target
            // here would let acquireLatest/reader pressure overtake the still RAW image.
            if (!token.expectsRaw) {
                resumePreviewRepeatingAfterStillCapture(token)
            }
            if (token.frameIndex == token.frameCount - 1) {
                restoreNormalSessionAfterMaximumResolutionStill("JPEG released", token)
            }
        }
    }

    override fun acknowledgeStillRawBufferReleased(token: StillCaptureRequestToken) {
        runPrioritizedCameraControl(token) {
            if (!ownsStillOperation(token)) return@runPrioritizedCameraControl
            resumePreviewRepeatingAfterStillCapture(token)
            if (token.frameIndex == token.frameCount - 1) {
                restoreNormalSessionAfterMaximumResolutionStill("RAW released", token)
            }
        }
    }

    override fun isStillCaptureSessionRestorationPending(): Boolean =
        maximumResolutionStillCaptureActive ||
            p010CadenceStillCaptureActive ||
            stillCaptureSessionRestorationPending ||
            pausedRepeatingStillCaptureToken != null

    fun recoverStillCaptureSessionAfterFailure(
        captureId: Int,
        phase: StillCaptureRequestPhase? = null
    ) {
        runOnCameraThread {
            val token = activeStillCaptureToken
            if (token?.captureId != captureId || (phase != null && token.phase != phase)) {
                return@runOnCameraThread
            }
            resumePreviewRepeatingAfterStillCapture(token)
            restoreNormalSessionAfterMaximumResolutionStill("capture recovery", token)
            if (ownsStillOperation(token)) activeStillCaptureToken = null
        }
    }

    private fun failStillCaptureRequest(
        token: StillCaptureRequestToken,
        message: String,
        frameNumber: Long? = null
    ) {
        val active = activeStillCaptureToken
        if (!sameStillOperation(active, token)) {
            Log.w(
                TAG,
                "Ignoring stale still failure captureId=${token.captureId} phase=${token.phase} " +
                    "epoch=${token.operationEpoch} active=${active?.captureId}/${active?.phase}/" +
                    "${active?.operationEpoch}: $message"
            )
            return
        }
        resumePreviewRepeatingAfterStillCapture(token)
        restoreNormalSessionAfterMaximumResolutionStill("failure", token)
        activeStillCaptureToken = null
        onStillCaptureFailed?.invoke(
            StillCaptureFailure(token = token, message = message, frameNumber = frameNumber)
        )
    }

    private fun resumePreviewRepeatingAfterStillCapture(token: StillCaptureRequestToken) {
        if (!sameStillOperation(pausedRepeatingStillCaptureToken, token)) return
        pausedRepeatingStillCaptureToken = null
        updatePreview(immediate = true)
    }

    private fun restoreNormalSessionAfterMaximumResolutionStill(
        reason: String,
        token: StillCaptureRequestToken
    ) {
        if ((!maximumResolutionStillCaptureActive && !p010CadenceStillCaptureActive) ||
            !sameStillOperation(maximumResolutionStillCaptureToken, token)
        ) {
            return
        }
        maximumResolutionStillCaptureActive = false
        p010CadenceStillCaptureActive = false
        maximumResolutionStillCaptureToken = null
        stillCaptureSessionRestorationPending = true
        Log.i(TAG, "Restoring cadence-safe video stream graph after dedicated still ($reason)")
        if (cameraDevice != null) {
            refreshOutputsAndSession(
                rebuildReaders = true,
                forceRebuild = true,
                onConfigured = { stillCaptureSessionRestorationPending = false }
            )
        } else {
            stillCaptureSessionRestorationPending = false
        }
    }

    /**
     * A timed-out capture can release its last Image just as a replacement is admitted. Its queued
     * recovery then correctly fails the new epoch guard, so the replacement performs the old owner's
     * idempotent cleanup before submitting any Camera2 request.
     */
    private fun releaseStaleStillResourcesBefore(token: StillCaptureRequestToken): Boolean {
        pausedRepeatingStillCaptureToken?.let { previous ->
            if (!sameStillOperation(previous, token)) {
                resumePreviewRepeatingAfterStillCapture(previous)
            }
        }
        val previousMaximumOwner = maximumResolutionStillCaptureToken
        if ((maximumResolutionStillCaptureActive || p010CadenceStillCaptureActive) &&
            previousMaximumOwner != null &&
            !sameStillOperation(previousMaximumOwner, token)
        ) {
            restoreNormalSessionAfterMaximumResolutionStill("stale owner replacement", previousMaximumOwner)
            return true
        }
        return false
    }

    override fun captureStillBurst(
        tokens: List<StillCaptureRequestToken>,
        captureRaw: Boolean,
        overridesList: List<StillCaptureOverrides>
    ) {
        val plannedOwner = tokens.firstOrNull()
        if (plannedOwner == null || tokens.size != overridesList.size ||
            tokens.any { it.captureId != plannedOwner.captureId || it.frameCount != tokens.size }
        ) {
            plannedOwner?.let { failStillCaptureRequest(it, "Photo bracket token plan is invalid") }
            return
        }
        val operationEpoch = nextStillOperationEpoch()
        val ownedTokens = tokens.map { it.copy(operationEpoch = operationEpoch) }
        val ownerToken = ownedTokens.first()
        activeStillCaptureToken = ownerToken
        runPrioritizedCameraControl(ownerToken) {
            submitStillBurstWhenControllerStable(
                ownerToken,
                ownedTokens,
                captureRaw,
                overridesList
            )
        }
    }

    private fun submitStillBurstWhenControllerStable(
        ownerToken: StillCaptureRequestToken,
        ownedTokens: List<StillCaptureRequestToken>,
        captureRaw: Boolean,
        overridesList: List<StillCaptureOverrides>,
        attempt: Int = 0
    ) {
        if (!ownsStillOperation(ownerToken)) return
        val restoredStaleMaximumSession = releaseStaleStillResourcesBefore(ownerToken)
        if (restoredStaleMaximumSession || sessionRefreshPending.get()) {
            if (attempt >= STILL_CAPTURE_SESSION_WAIT_MAX_ATTEMPTS) {
                failStillCaptureRequest(ownerToken, "Camera session recovery did not settle for photo bracket")
                return
            }
            val handler = backgroundHandler ?: run {
                failStillCaptureRequest(ownerToken, "Camera thread is not available for photo bracket")
                return
            }
            handler.postDelayed(
                {
                    submitStillBurstWhenControllerStable(
                        ownerToken,
                        ownedTokens,
                        captureRaw,
                        overridesList,
                        attempt + 1
                    )
                },
                STILL_CAPTURE_SESSION_WAIT_INTERVAL_MS
            )
            return
        }
        if (shouldUseDedicatedP010CadenceStill(captureRaw, captureJpeg = true)) {
            p010CadenceStillCaptureActive = true
            maximumResolutionStillCaptureToken = ownerToken
            Log.i(TAG, "Preparing dedicated P010-cadence JPEG bracket session")
            refreshOutputsAndSession(
                rebuildReaders = true,
                forceRebuild = true,
                onConfigured = {
                    if (p010CadenceStillCaptureActive &&
                        sameStillOperation(maximumResolutionStillCaptureToken, ownerToken) &&
                        ownsStillOperation(ownerToken)
                    ) {
                        submitStillBurstWhenControllerStable(
                            ownerToken = ownerToken,
                            ownedTokens = ownedTokens,
                            captureRaw = captureRaw,
                            overridesList = overridesList,
                            attempt = attempt
                        )
                    }
                }
            )
            return
        }
        val dev = cameraDevice ?: run {
            failStillCaptureRequest(ownerToken, "Camera is not ready for photo bracket")
            return
        }
        val session = captureSession ?: run {
            failStillCaptureRequest(ownerToken, "Camera session is not ready for photo bracket")
            return
        }
            val streamGraph = currentCinemaStreamGraphPlan()
            val targetPlan = RawStillCaptureTargetPolicy.resolve(
                captureRaw = captureRaw,
                captureJpeg = true,
                rawStreamActive = isSurfaceConfiguredInActiveSession(rawReader?.surface),
                jpegStreamActive = isSurfaceConfiguredInActiveSession(jpegReader?.surface)
            )
            targetPlan.failureReason?.let { reason ->
                failStillCaptureRequest(ownerToken, reason)
                return
            }
            
            val requests = overridesList.mapIndexed { index, overrides ->
                val token = ownedTokens[index]
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
                builder.setTag(token)

                overrides.iso?.let { iso -> builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso) }
                overrides.exposureNs?.let { exp -> builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp) }
                overrides.evCompensation?.let { ev -> builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev) }
                if (overrides.aeLock) builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                applyStillCaptureAutoWhiteBalanceLock(builder, overrides.awbLock)

                builder.build()
            }

            // Do not queue every full-resolution JPEG at once. The vivo/QTI provider can abort in
            // requestBuffer ("maxBufferMap[...] < 0") when a Camera2 burst races the repeating P010
            // stream for several JPEG buffers. Submit the same bracket in order, one completed frame
            // at a time. Preview repeating stays active, JPEG buffers are drained between frames, and
            // the EV bracket/fusion contract is unchanged.
            submitSerializedStillCaptureRequests(session, requests, ownerToken)
    }

    private fun applyStillCaptureAutoWhiteBalanceLock(
        builder: CaptureRequest.Builder,
        requested: Boolean
    ) {
        if (CinemaAutoWhiteBalanceRequestPolicy.shouldApplyStillCaptureAutoLock(
                requested = requested,
                awbLockAvailable = supportsAutoWhiteBalanceLock,
                resolvedAwbMode = builder.get(CaptureRequest.CONTROL_AWB_MODE)
            )
        ) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
    }

    private fun submitSerializedStillCaptureRequests(
        session: CameraCaptureSession,
        requests: List<CaptureRequest>,
        ownerToken: StillCaptureRequestToken
    ) {
        if (requests.isEmpty()) {
            failStillCaptureRequest(ownerToken, "Photo bracket did not contain any frames")
            return
        }
        val handler = backgroundHandler
        if (handler == null) {
            failStillCaptureRequest(ownerToken, "Camera thread is not available for photo capture")
            return
        }

        fun submit(index: Int) {
            if (index >= requests.size) return
            if (!ownsStillOperation(ownerToken)) return
            if (activeP010StopBoundary.get() != null) {
                failStillCaptureRequest(
                    ownerToken,
                    "Photo bracket was gated by the exact P010 stop boundary"
                )
                return
            }
            if (activeRawResumeBoundary.get() != null) {
                handler.postDelayed({ submit(index) }, STILL_CAPTURE_SESSION_WAIT_INTERVAL_MS)
                return
            }
            if (captureSession !== session) {
                failStillCaptureRequest(ownerToken, "Camera session changed during photo bracket")
                return
            }
            val request = requests[index]
            val serializedCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    activeSession: CameraCaptureSession,
                    completedRequest: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    captureCallback.onCaptureCompleted(activeSession, completedRequest, result)
                    if (index + 1 < requests.size) {
                        handler.postDelayed(
                            { submit(index + 1) },
                            STILL_HDR_SERIAL_FRAME_SETTLE_MS
                        )
                    }
                }

                override fun onCaptureFailed(
                    activeSession: CameraCaptureSession,
                    failedRequest: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    captureCallback.onCaptureFailed(activeSession, failedRequest, failure)
                }

                override fun onCaptureBufferLost(
                    activeSession: CameraCaptureSession,
                    lostRequest: CaptureRequest,
                    target: Surface,
                    frameNumber: Long
                ) {
                    captureCallback.onCaptureBufferLost(
                        activeSession,
                        lostRequest,
                        target,
                        frameNumber
                    )
                }

                override fun onCaptureSequenceAborted(
                    activeSession: CameraCaptureSession,
                    sequenceId: Int
                ) {
                    failStillCaptureRequest(ownerToken, "Photo bracket was interrupted by the camera HAL")
                }
            }
            try {
                val sequenceId = submitTrackedCaptureRequest(
                    session = session,
                    request = request,
                    callback = serializedCallback,
                    targetsP010 = false
                ) ?: throw IllegalStateException(
                    "Serialized photo capture crossed an active P010 stop fence"
                )
                Log.i(
                    TAG,
                    "Serialized photo bracket submitted frame=${index + 1}/${requests.size} " +
                        "sequence=$sequenceId"
                )
            } catch (error: IllegalStateException) {
                Log.d(TAG, "Serialized photo bracket: session closed during teardown")
                failStillCaptureRequest(ownerToken, "Camera session closed during photo bracket")
            } catch (error: Exception) {
                Log.w(TAG, "Serialized photo bracket submission failed", error)
                failStillCaptureRequest(ownerToken, "Photo bracket could not continue")
            }
        }

        submit(0)
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
        return synchronized(cinematicFocusPullLock) {
        if (!cinematicFocusPull.isPulling) return@synchronized manFocusDist
        // CP-QV20 endpoint hold: capture the pulling state around the step. When the pull COMPLETES
        // this frame (isPulling flips false and the control returned the endpoint), persist
        // manFocusDist = that endpoint so the lens HOLDS B (AF_MODE_OFF stays forced) instead of
        // reverting to autofocus and re-acquiring (a visible snap-back). Strictly inside the flag-on
        // branch — the flag-off early-return above keeps today's path byte-for-byte.
        val wasPulling = cinematicFocusPull.isPulling
        val stepped = cinematicFocusPull.stepDiopters(
            nowElapsedMs = SystemClock.elapsedRealtime(),
            maxFocusDiopters = minFocusDist
        ) ?: return@synchronized manFocusDist
        manFocusDist = CinematicFocusPullEndpointHoldPolicy.resolveHold(
            wasPulling = wasPulling,
            isPullingNow = cinematicFocusPull.isPulling,
            steppedOrNull = stepped,
            manFocusDist = manFocusDist
        )
        Log.d(
            TAG,
            "CINEFOCUS_REQUEST diopters=$stepped active=${cinematicFocusPull.isPulling} " +
                "generation=${sessionGeneration.get()}"
        )
        stepped
        }
    }

    /**
     * A repeating Camera2 request is immutable. Advance an active pull from real capture-result
     * cadence so each easing step reaches a fresh request without a free-running timer or queue burst.
     */
    private fun maybeAdvanceCinematicFocusPullRequest() {
        if (!CinematicFocusPullFlag.enabled) return
        val active = synchronized(cinematicFocusPullLock) { cinematicFocusPull.isPulling }
        if (active) updatePreviewFromAutonomousControlFeedback()
    }

    /** Cancels only the transient pull; the caller decides whether manual focus is held or cleared. */
    private fun cancelCinematicFocusPull() {
        synchronized(cinematicFocusPullLock) { cinematicFocusPull.cancel() }
    }

    /**
     * Disabling mid-pull holds the last requested distance instead of snapping back to mark A.
     * Enabling does not move the lens until an explicit, valid A/B trigger is issued.
     */
    private fun onCinematicFocusPullFlagChanged() {
        if (!CinematicFocusPullFlag.enabled) {
            val held = synchronized(cinematicFocusPullLock) {
                val status = cinematicFocusPull.status()
                cinematicFocusPull.cancel()
                status.currentDiopters.takeIf { it.isFinite() && it >= 0f }
            }
            if (held != null && minFocusDist > 0f) {
                manFocusDist = held.coerceIn(0f, minFocusDist)
                paramsFlow.value = paramsFlow.value.copy(focusDist = manFocusDist)
            }
        }
        updatePreview(immediate = true)
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
        if (!CinematicFocusPullFlag.enabled || minFocusDist <= 0f) return false
        val started = synchronized(cinematicFocusPullLock) {
            cinematicFocusPull = CinematicFocusPullControl(AICinematicPullStepper(style)).also {
                it.setEasingCurve(CinematicFocusPullControl.EasingCurve.EASE_IN_OUT)
                it.setDurationMs(AICinematicPullStepper.styleDurationMs(style))
            }
            cinematicFocusPull.triggerPull(
                nowElapsedMs = SystemClock.elapsedRealtime(),
                fromDiopters = fromDiopters.coerceIn(0f, minFocusDist),
                toDiopters = toDiopters.coerceIn(0f, minFocusDist)
            ).also { didStart ->
                if (didStart) {
                    manFocusDist = cinematicFocusPull.status().startDiopters
                    aiDrivenFocusDist = -1f
                }
            }
        }
        if (started) {
            paramsFlow.value = paramsFlow.value.copy(focusDist = manFocusDist)
            updatePreview()
        }
        return started
    }

    // CP-QV20: set focus-pull mark A or B (diopters). Gated no-op when the cine flag is OFF, so the
    // default focus path is untouched. Marks are stateful config on the control; no request rebuild.
    internal fun setCinematicFocusPullMark(isA: Boolean, diopters: Float) {
        if (!CinematicFocusPullFlag.enabled) return
        val resolved = if (diopters.isFinite() && diopters >= 0f && minFocusDist > 0f) {
            diopters.coerceIn(0f, minFocusDist)
        } else {
            -1f
        }
        synchronized(cinematicFocusPullLock) {
            if (isA) cinematicFocusPull.setMarkA(resolved) else cinematicFocusPull.setMarkB(resolved)
        }
    }

    // CP-QV20: configure the pull easing curve + duration. Gated no-op when the cine flag is OFF.
    internal fun configureCinematicFocusPull(
        curve: CinematicFocusPullControl.EasingCurve,
        durationMs: Long
    ) {
        if (!CinematicFocusPullFlag.enabled) return
        synchronized(cinematicFocusPullLock) {
            cinematicFocusPull.setEasingCurve(curve)
            cinematicFocusPull.setDurationMs(durationMs)
        }
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
        if (!CinematicFocusPullFlag.enabled || minFocusDist <= 0f) return false
        // The control owns the marks and validates them inside triggerPull() (false when either is
        // unset/invalid — a dormant-safe no-op). Trigger first, which latches pullStartDiopters = mark A.
        val started = synchronized(cinematicFocusPullLock) {
            cinematicFocusPull.triggerPull(SystemClock.elapsedRealtime()).also { didStart ->
                if (didStart) {
                    manFocusDist = cinematicFocusPull.status().startDiopters
                    aiDrivenFocusDist = -1f
                }
            }
        }
        if (!started) return false
        // Endpoint-hold precondition: enter manual focus at A so CameraAutofocusRequestPolicy forces
        // AF_MODE_OFF for the positive manual distance (the lens can then hold B on completion).
        // status().startDiopters is exactly the latched mark A.
        paramsFlow.value = paramsFlow.value.copy(focusDist = manFocusDist)
        // Rebuild the preview request so resolveEffectiveManualFocusDistance() steps the pull per frame.
        updatePreview()
        return true
    }

    override fun setFocusDist(dist: Float) {
        if (!dist.isFinite() || minFocusDist <= 0f) {
            setAutoFocus()
            return
        }
        val resolvedDistance = dist.coerceIn(0f, minFocusDist)
        mutateControlState(immediate = false, stateMutation = {
            cancelCinematicFocusPull()
            manualFocusDist = resolvedDistance
            aiDrivenFocusDist = -1f
        }) { it.copy(focusDist = resolvedDistance) }
    }

    override fun setAutoFocus() {
        mutateControlState(immediate = true, stateMutation = {
            cancelCinematicFocusPull()
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
            updatePreviewFromAutonomousControlFeedback()
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
        if (changed && cameraDevice != null && recordingActive && !rawVideoPrearmEnabled) {
            refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true)
        }
    }

    override fun setSensorLinearNdrCaptureEnabled(enabled: Boolean) {
        if (sensorLinearNdrCaptureEnabled == enabled) return
        sensorLinearNdrCaptureEnabled = enabled
        backgroundHandler?.removeCallbacks(hdrExposureStaleReleaseRunnable)
        lastSensorLinearNdrExposureMetricsElapsedMs = 0L
        lastSensorLinearNdrExposureRequestElapsedMs = 0L
        lastSensorLinearNdrExposureControlElapsedMs = 0L
        sensorLinearNdrExposureStops = 0f
        sensorLinearNdrExposureAppliedStops = 0f
        lastRequestedNdrExposureCompensationIndex = null
        if (enabled) {
            // This is an encoded proprietary NDR path, never NPRAW/RAW-original recording semantics.
            rawVideoCaptureEnabled = false
            rawVideoPrearmEnabled = false
            npv10TenBitCaptureEnabled = false
            hardwareImportP010SourceEnabled = false
            hdrDynamicRangeMode = HdrDynamicRangeMode.SDR
        }
        resetReadoutSidecarFallback()
        // The ViewModel batches the corresponding reader/session refresh with the mode transition.
    }

    override fun setNpv10TenBitCaptureEnabled(enabled: Boolean) {
        val changed = npv10TenBitCaptureEnabled != enabled
        npv10TenBitCaptureEnabled = enabled
        if (!enabled) npv10CaptureTargetSize = null
        if (changed) resetReadoutSidecarFallback()
        if (changed) {
            reconcileNiv99MetadataIngressPriority("npv10-capture-$enabled")
            reconcileNiv99ImageIngressPriority("npv10-capture-$enabled")
        }
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

    override fun setModeSwitchStandbyAnalysisOutput(size: Size?, tenBit: Boolean) {
        val normalizedSize = resolveModeSwitchStandbyP010Target(size, tenBit)
        if (modeSwitchStandbyAnalysisTargetSize == normalizedSize &&
            modeSwitchStandbyAnalysisTenBit == tenBit
        ) return
        modeSwitchStandbyAnalysisTargetSize = normalizedSize
        modeSwitchStandbyAnalysisTenBit = tenBit
        if (cameraDevice != null && captureSession != null && !recordingActive) {
            refreshOutputsAndSession(
                rebuildReaders = true,
                forceRebuild = true,
                reason = "mode-switch-standby-analysis"
            )
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
        val streamGraph = currentCinemaStreamGraphPlan()
        val cadenceSafeSession = p010CadenceSafeSessionActive(streamGraph)
        val jpegSurface = jpegReader?.surface
        return synchronized(sessionCollectionsLock) {
            val configured = configuredSessionSurfaces[session] ?: return@synchronized false
            configured.contains(readerSurface) &&
                (!cadenceSafeSession || jpegSurface == null || !configured.contains(jpegSurface))
        }
    }

    override fun isNpv10TenBitProducerReady(size: Size, fps: Int): Boolean =
        npv10TenBitProducerProof(size, fps) != null

    override fun npv10TenBitProducerProof(size: Size, fps: Int): Npv10TenBitProducerProof? {
        if (fps <= 0 || !recordingActive || targetCameraFps != fps ||
            !isNpv10TenBitSessionConfigured(size)
        ) {
            return null
        }
        val session = captureSession ?: return null
        val generation = sessionGeneration.get()
        val binding = captureResultSessionBinding.get()
        if (binding?.session !== session || binding.generation != generation) return null
        if (lastAppliedFpsRequestPlan?.appliedFps != fps) return null

        val reader = yuvReader ?: return null
        val p010Surface = p010ReaderSurfaceOrNull(reader) ?: return null
        val streamGraph = currentCinemaStreamGraphPlan()
        val requestPlan = resolveCamera2PipelineRequestPlan(streamGraph)
        val recordingReadoutActive = when (requestPlan.pipelineMode) {
            Camera2PipelineMode.ENCODED_RECORDING_WITH_READOUT,
            Camera2PipelineMode.SENSOR_LINEAR_NDR_RECORDING ->
                requestPlan.targets.includeYuvReadout
            else -> false
        }
        if (!recordingReadoutActive) return null

        val repeating = activeRepeatingSequence.get() ?: return null
        if (repeating.identity.session !== session ||
            repeating.identity.sessionGeneration != generation ||
            repeating.p010Surface !== p010Surface ||
            repeating.firstFrameProof.isCompletedExceptionally ||
            repeating.firstFrameProof.isCancelled
        ) return null
        val firstFrame = runCatching { repeating.firstFrameProof.getNow(null) }.getOrNull()
            ?: return null
        // A request/session change between the validation and snapshot must never inherit proof.
        if (activeRepeatingSequence.get() !== repeating ||
            captureSession !== session ||
            sessionGeneration.get() != generation ||
            captureResultSessionBinding.get() !== binding
        ) return null
        return Npv10TenBitProducerProof(
            publicationGeneration = repeating.publicationGeneration,
            sessionGeneration = repeating.identity.sessionGeneration,
            sequenceId = repeating.identity.sequenceId,
            firstFrameNumber = firstFrame.frameNumber,
            firstFrameSensorTimestampNs = firstFrame.sensorTimestampNs
        )
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
        val previousCeiling = thermalResourceCeiling
        val normalizedCeiling = ThermalCaptureRequestPolicy.normalizeCeiling(
            candidate = ceiling,
            current = previousCeiling
        )
        if (normalizedCeiling == previousCeiling) return

        thermalResourceCeiling = normalizedCeiling
        if (ThermalCaptureRequestPolicy.shouldRebuildCaptureRequest(
                previousCeiling = previousCeiling,
                newCeiling = normalizedCeiling,
                strictHighEnd = strictHighEndEnabled()
            )
        ) {
            updatePreview()
        }
    }

    override fun setHdrDynamicRangeMode(
        mode: HdrDynamicRangeMode,
        ndrExposureGovernorEnabled: Boolean
    ) {
        // NDR highlight/exposure integrity needs a small analysis surface. Change its session membership
        // atomically with the dynamic-range mode so entering/leaving NDR causes one rebuild, not two.
        val ndrGovernorRequested =
            ndrExposureGovernorEnabled &&
                (mode != HdrDynamicRangeMode.SDR || sensorLinearNdrCaptureEnabled)
        // RAW sensor-linear NDR meters its exact RAW frame; attaching a vendor YUV analysis stream
        // would violate its input contract and add another camera output for no benefit.
        val ndrExposureAnalysisRequested =
            ndrGovernorRequested && mode != HdrDynamicRangeMode.SDR && !sensorLinearNdrCaptureEnabled
        val effectiveAnalysisRequested =
            ndrExposureAnalysisRequested || clientPreviewAnalysisRequested
        val modeChanged = hdrDynamicRangeMode != mode
        val governorChanged = this.ndrExposureGovernorEnabled != ndrGovernorRequested
        val analysisChanged = previewAnalysisRequested != effectiveAnalysisRequested
        if (!modeChanged && !governorChanged && !analysisChanged) return
        hdrDynamicRangeMode = mode
        this.ndrExposureGovernorEnabled = ndrGovernorRequested
        if (modeChanged || governorChanged) {
            backgroundHandler?.removeCallbacks(hdrExposureStaleReleaseRunnable)
            lastHdrExposureMetricsElapsedMs = 0L
            hdrAdaptiveHighlightExtraStops = 0f
            hdrAdaptiveHighlightAppliedStops = 0f
            sensorLinearNdrExposureStops = 0f
            sensorLinearNdrExposureAppliedStops = 0f
            lastSensorLinearNdrExposureMetricsElapsedMs = 0L
            lastSensorLinearNdrExposureRequestElapsedMs = 0L
            lastSensorLinearNdrExposureControlElapsedMs = 0L
            sdrAdaptiveHighlightExtraStops = 0f
            sdrAdaptiveHighlightAppliedStops = 0f
            lastRequestedNdrExposureCompensationIndex = null
            lastRequestedSdrExposureCompensationIndex = null
            backgroundHandler?.removeCallbacks(sdrExposureStaleReleaseRunnable)
        }
        previewAnalysisRequested = effectiveAnalysisRequested
        characteristics?.let { hdrSupport = HdrDynamicRangePolicy.detectSupport(it) }
        val sessionMutation = HdrDynamicRangeSessionMutationPolicy.resolve(
            modeChanged = modeChanged,
            governorChanged = governorChanged,
            analysisChanged = analysisChanged
        )
        if (cameraDevice != null) {
            if (sessionMutation.rebuildSession) {
                refreshOutputsAndSession(
                    rebuildReaders = analysisChanged,
                    forceRebuild = analysisChanged
                )
            } else if (sessionMutation.retargetRepeatingRequest) {
                // A governor-only transition changes request controls, not Surface ownership. The
                // ViewModel's mode-switch transaction owns any simultaneous RAW/P010 graph swap.
                updatePreview()
            }
        }
    }

    override fun describeHdrDynamicRangeState(): HdrDynamicRangeState {
        val resolvedProfile = HdrDynamicRangePolicy.resolveProfilePure(
            mode = hdrDynamicRangeMode,
            support = hdrSupport,
            role = HdrSurfaceRole.PREVIEW
        )
        val activeSession = captureSession
        val latestSnapshot = appliedDynamicRangeSessionSnapshot
        val activeSnapshot = latestSnapshot?.takeIf { snapshot ->
            activeSession != null && snapshot.session === activeSession
        }
        return HdrDynamicRangeState(
            requestedMode = hdrDynamicRangeMode,
            previewProfile = activeSnapshot?.bindingFor(previewSurface)?.profile,
            encoderProfile = activeSnapshot?.bindingFor(recordSurface)?.profile,
            resolvedProfileForCurrentMode = resolvedProfile,
            support = hdrSupport,
            sessionConfigured = activeSession != null,
            activeSessionGeneration = activeSnapshot?.generation,
            appliedProfileGeneration = latestSnapshot?.generation
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

    override fun setProfessionalVideoControlsActive(enabled: Boolean) {
        if (professionalVideoControlsActive == enabled) return
        professionalVideoControlsActive = enabled
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

    override fun setLensSelectionTaskHint(hint: LensSelectionTaskHint) {
        if (lensSelectionTaskHint == hint) return
        lensSelectionTaskHint = hint

        // Task changes are optical-routing intent, never a reason to disturb an opened device or a
        // running capture session. Before the first open, however, CameraController has already made
        // a conservative default selection in its constructor; invalidate only that unopened choice
        // so the next open uses the task-aware policy and matching characteristics atomically.
        if (!CameraLensSelectionTaskFlag.enabled || cameraDevice != null || stateFlow.value !is CameraState.Closed) {
            return
        }
        currentCameraId = ""
        selectCamera()
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

    override fun isHardwareImportP010SessionConfigured(size: Size): Boolean {
        val normalized = size.normalizedForCameraOutput()
        val session = captureSession
        val reader = hardwareImportReader
        val readerSurfaceRegistered = if (session != null && reader != null) {
            val readerSurface = reader.surface
            synchronized(sessionCollectionsLock) {
                configuredSessionSurfaces[session]?.contains(readerSurface) == true
            }
        } else {
            false
        }
        return HardwareImportP010SessionMembershipPolicy.isExactCurrentSessionMember(
            HardwareImportP010SessionMembershipPolicy.Input(
                platformSupportsP010 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                internalHardwareImportEnabled = internalRecordingHardwareImportEnabled,
                p010SourceEnabled = hardwareImportP010SourceEnabled,
                sessionRefreshPending = sessionRefreshPending.get(),
                expectedWidth = normalized.width,
                expectedHeight = normalized.height,
                readerWidth = reader?.width,
                readerHeight = reader?.height,
                readerIsP010 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    reader?.imageFormat == ImageFormat.YCBCR_P010,
                readerSurfaceRegistered = readerSurfaceRegistered,
                // Revalidate both owners after consulting the registry. A close/rebuild may win on
                // the CameraBackground thread while this read-only proof is being assembled.
                sessionStillCurrent = session != null && captureSession === session,
                readerStillCurrent = reader != null && hardwareImportReader === reader
            )
        )
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
        val previousEffective = previewAnalysisRequested
        clientPreviewAnalysisRequested = enabled
        val effectiveRequested =
            enabled || (ndrExposureGovernorEnabled && !sensorLinearNdrCaptureEnabled)
        NeoLogger.i(
            TAG,
            "NIV99_ANALYSIS_SET client=$enabled effective=$effectiveRequested " +
                "previousEffective=$previousEffective ndrGovernor=$ndrExposureGovernorEnabled " +
                "sensorLinearNdr=$sensorLinearNdrCaptureEnabled recording=$recordingActive " +
                "cameraOpen=${cameraDevice != null} sessionOpen=${captureSession != null}"
        )
        if (previewAnalysisRequested == effectiveRequested) return
        previewAnalysisRequested = effectiveRequested
        if (!effectiveRequested) {
            backgroundHandler?.removeCallbacks(sdrExposureStaleReleaseRunnable)
            sdrAdaptiveHighlightExtraStops = 0f
            sdrAdaptiveHighlightAppliedStops = 0f
            lastRequestedSdrExposureCompensationIndex = null
        }
        // Attaching/detaching the analysis YUV reader changes the session OUTPUT set, so a repeating-
        // request retarget is not enough — a hot session rebuild is required (setupReaders recreates
        // the reader, createSession swaps outputs while the old session keeps streaming). Preview only:
        // during active recording the encoded/uncompressed graph owns the stream set, so we leave it
        // untouched to avoid disturbing the record path.
        if (cameraDevice != null && captureSession != null && !recordingActive) {
            refreshOutputsAndSession(rebuildReaders = true, forceRebuild = true)
        }
    }

    override fun setRecordingThermalAnalysisIsolationEnabled(enabled: Boolean) {
        if (recordingThermalAnalysisIsolationEnabled == enabled) return
        // Resolve both sides against the same configured stream graph. Thermal isolation changes
        // only optional analysis membership; an NPV10 P010 writer or RAW sensor target is mandatory
        // and therefore byte-identical across this transition. Republishing such an unchanged
        // request would be treated as a late control mutation by the strict producer-startup lease,
        // aborting a healthy recording after its first durable frame.
        val activeStreamGraph = if (
            recordingActive && cameraDevice != null && captureSession != null
        ) {
            currentCinemaStreamGraphPlan()
        } else {
            null
        }
        val targetsBefore = activeStreamGraph
            ?.let(::resolveCamera2PipelineRequestPlan)
            ?.targets
        recordingThermalAnalysisIsolationEnabled = enabled
        val targetsAfter = activeStreamGraph
            ?.let(::resolveCamera2PipelineRequestPlan)
            ?.targets
        val targetMembershipChanged =
            targetsBefore != null &&
                targetsAfter != null &&
                CameraRepeatingRequestRetargetPolicy.requiresRepublish(
                    before = targetsBefore,
                    after = targetsAfter
                )
        NeoLogger.i(
            "RecordingThermalIsolation",
            "RECORDING_THERMAL_CAMERA_TARGET isolation=$enabled recording=$recordingActive " +
                "analysisReaderConfigured=${yuvReader != null} sessionRebuild=false " +
                "targetsBefore=$targetsBefore targetsAfter=$targetsAfter " +
                "repeatingRefreshRequired=$targetMembershipChanged " +
                "recordingPixelPathUnchanged=true"
        )
        // Session membership is intentionally stable. Republish only when the effective target set
        // changes (encoded-video optional analysis); mandatory RAW/NPV10 producer targets remain
        // untouched. If recording has not started, its first request observes the new flag directly.
        if (targetMembershipChanged) {
            refreshRepeatingRequest()
        }
    }

    override fun setProcessedRecordingSessionPreparationActive(enabled: Boolean) {
        if (processedRecordingSessionPreparationActive == enabled) return
        processedRecordingSessionPreparationActive = enabled
        NeoLogger.i(
            TAG,
            "PROCESSED_RECORDING_SESSION_PREPARATION active=$enabled " +
                "recording=$recordingActive generation=${sessionGeneration.get()}"
        )
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
        // The ViewModel owns the one batched mode/start/stop refresh. Refreshing here as well used to
        // tear the session down twice during RAW transitions and could leave the preview frozen.
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

    override fun setRawVideoPrearmEnabled(enabled: Boolean) {
        if (rawVideoPrearmEnabled == enabled) return
        rawVideoPrearmEnabled = enabled
        resetReadoutSidecarFallback()
        // The mode-switch owner performs one fingerprinted refresh after readout size, pixel mode, and
        // preview transport have all settled. Recording start/stop only retarget the existing surface.
    }

    override fun setRecordSurface(surface: Surface?) {
        setRecordSurface(surface = surface, refreshSession = true)
    }

    fun setRecordSurface(surface: Surface?, refreshSession: Boolean) {
        replaceRecordSessionSurface(surface)
        if (surface == null) {
            recordingActive = false
            unconfiguredRecordSurfaceRecoveryPending = false
            reconcileNiv99MetadataIngressPriority("record-surface-detached")
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

    /**
     * Registers the two fixed-profile monitor inputs that remain in the Camera2 session. Returns
     * false only after this camera has explicitly rejected that combination, allowing the caller to
     * use the existing single-output fallback without retry churn.
     */
    fun setPersistentPreviewOutputs(sdrSurface: Surface?, hdrSurface: Surface?): Boolean {
        if (sdrSurface == null || hdrSurface == null || sdrSurface === hdrSurface) {
            replacePersistentPreviewSessionSurfaces(null, null)
            previewWarmupSurface = null
            persistentPreviewProfilesCanShareRequest = false
            return false
        }
        val activeCameraId = cameraDevice?.id ?: currentCameraId.takeIf { it.isNotBlank() }
        if (activeCameraId != null && persistentPreviewOutputsRejectedCameraId == activeCameraId) {
            replacePersistentPreviewSessionSurfaces(null, null)
            previewWarmupSurface = null
            persistentPreviewProfilesCanShareRequest = false
            return false
        }
        replacePersistentPreviewSessionSurfaces(sdrSurface, hdrSurface)
        refreshPersistentPreviewProfileCoexistence()
        return true
    }

    fun persistentPreviewOutputsAvailable(): Boolean {
        val activeCameraId = cameraDevice?.id ?: currentCameraId.takeIf { it.isNotBlank() }
        return persistentPreviewOutputsEnabled() &&
            (activeCameraId == null || persistentPreviewOutputsRejectedCameraId != activeCameraId)
    }

    /** Arms an already-streaming input for a router handoff without replacing the Camera2 request. */
    fun beginPersistentPreviewHandoff(incomingSurface: Surface): Boolean {
        if (!persistentPreviewOutputsEnabled() || incomingSurface === previewSurface) return false
        if (incomingSurface !== persistentSdrPreviewSurface && incomingSurface !== persistentHdrPreviewSurface) {
            return false
        }
        val session = captureSession ?: return false
        val configured = synchronized(sessionCollectionsLock) {
            configuredSessionSurfaces[session]?.contains(incomingSurface) == true
        }
        if (!configured) return false
        previewWarmupSurface = incomingSurface
        if (PersistentPreviewRepeatingTargetPolicy.requiresHandoffRetarget(
                profilesCanShareRequest = persistentPreviewProfilesCanShareRequest,
                recordingActive = recordingActive
            )
        ) {
            val generation = sessionGeneration.get()
            runOnCameraThread {
                if (captureSession === session && previewWarmupSurface === incomingSurface) {
                    publishRepeatingRequestIfSessionStable {
                        startPreview(generation, session)
                    }
                }
            }
        }
        return true
    }

    /** Commits only logical ownership; both fixed-profile inputs remain continuously targeted. */
    fun completePersistentPreviewHandoff(incomingSurface: Surface): Boolean {
        if (incomingSurface !== persistentSdrPreviewSurface && incomingSurface !== persistentHdrPreviewSurface) {
            return false
        }
        if (incomingSurface !== previewWarmupSurface) return false
        val appliedBinding = appliedDynamicRangeSessionSnapshot
            ?.takeIf { it.session === captureSession }
            ?.bindingFor(incomingSurface)
            ?: return false
        replacePreviewSessionSurface(incomingSurface)
        previewWarmupSurface = null
        lastAppliedPreviewHdrProfile = appliedBinding.profile
        return true
    }

    fun cancelPersistentPreviewHandoff() {
        val cancelledWarmup = previewWarmupSurface ?: return
        previewWarmupSurface = null
        if (PersistentPreviewRepeatingTargetPolicy.requiresHandoffRetarget(
                profilesCanShareRequest = persistentPreviewProfilesCanShareRequest,
                recordingActive = recordingActive
            )
        ) {
            val session = captureSession ?: return
            val generation = sessionGeneration.get()
            runOnCameraThread {
                if (captureSession === session && previewWarmupSurface == null &&
                    cancelledWarmup !== previewSurface
                ) {
                    publishRepeatingRequestIfSessionStable {
                        startPreview(generation, session)
                    }
                }
            }
        }
    }

    override fun setPreviewSurface(surface: Surface?) {
        replacePreviewSessionSurface(surface)
    }

    override fun getPreviewSurface(): Surface? = previewSurface

    override fun processedVideoQualityEvidence(): Map<String, String> =
        appliedProcessedVideoQualityEvidence

    override fun startVideoCapture() {
        recordFrameDeliveryLedger.reset(targetCameraFps)
        recordingActive = true
        // Keep a prepared processed-recording graph constrained for the complete clip. Clearing this
        // here would re-admit idle analysis immediately after start, invalidate the just-configured
        // surface set, and trigger a second Camera2 rebuild inside the recording interval.
        val metadataPriorityReady =
            reconcileNiv99MetadataIngressPriority("video-capture-start")
        val imagePriorityReady =
            reconcileNiv99ImageIngressPriority("video-capture-start")
        if (npv10TenBitCaptureEnabled && (!metadataPriorityReady || !imagePriorityReady)) {
            recordingActive = false
            reconcileNiv99MetadataIngressPriority("video-capture-start-rollback")
            reconcileNiv99ImageIngressPriority("video-capture-start-rollback")
            error("NPV10 recording requires critical Camera2 metadata and P010 image ingress")
        }
        extremeDetailFallbackExposureRequestApplied = false
        transitionTo(CameraState.Recording)
        restartRepeatingRequestForRecordTargetChange()
    }

    /**
     * Starts the NPV10 P010 producer while holding an exact request-publication lease. Ordinary
     * AE/AWB/focus refreshes are coalesced until the caller atomically commits the returned handle
     * after proving the first durably written frame. Acquisition and every Camera2 submission share
     * [cameraSubmissionLock], closing the proof-to-writer attachment race without freezing frames.
     */
    internal fun startNpv10VideoCaptureWithProducerLease(): Npv10ProducerStartupLeaseHandle {
        val lease = synchronized(cameraSubmissionLock) {
            val generation = sessionGeneration.get()
            check(captureSession != null && generation > 0L) {
                "NPV10 producer startup requires a configured Camera2 session"
            }
            val acquired = npv10ProducerStartupLeaseGate.acquire(generation)
            try {
                npv10AutonomousControlRefreshGate.begin(acquired)
            } catch (failure: Throwable) {
                npv10ProducerStartupLeaseGate.revoke(
                    acquired,
                    "NPV10 autonomous-control startup window acquisition failed"
                )
                throw failure
            }
            acquired
        }
        try {
            check(npv10TenBitCaptureEnabled) {
                "NPV10 producer startup requires native ten-bit capture to be enabled"
            }
            recordFrameDeliveryLedger.reset(targetCameraFps)
            recordingActive = true
            val metadataPriorityReady =
                reconcileNiv99MetadataIngressPriority("npv10-capture-start")
            val imagePriorityReady =
                reconcileNiv99ImageIngressPriority("npv10-capture-start")
            check(metadataPriorityReady && imagePriorityReady) {
                "NPV10 recording requires critical Camera2 metadata and P010 image ingress"
            }
            extremeDetailFallbackExposureRequestApplied = false
            transitionTo(CameraState.Recording)
            restartRepeatingRequestForRecordTargetChange(lease)
        } catch (failure: Throwable) {
            recordingActive = false
            reconcileNiv99MetadataIngressPriority("npv10-capture-start-rollback")
            reconcileNiv99ImageIngressPriority("npv10-capture-start-rollback")
            cancelNpv10ProducerStartupLease(
                lease,
                "Initial NPV10 producer activation failed: ${failure.message ?: failure.javaClass.simpleName}"
            )
            throw failure
        }
        return Npv10ProducerStartupLeaseHandle(
            lease = lease,
            requestCommit = ::commitNpv10ProducerStartupLease,
            requestStableCommit = ::commitStableNpv10ProducerStartupLease,
            replayDeferredRefresh = ::replayDeferredNpv10ProducerStartupRefresh,
            cancel = ::cancelNpv10ProducerStartupLease
        )
    }

    private fun commitNpv10ProducerStartupLease(
        lease: Npv10ProducerStartupLeaseGate.Lease
    ) {
        val (release, autonomous) = synchronized(cameraSubmissionLock) {
            val strictRelease = npv10ProducerStartupLeaseGate.release(lease)
            strictRelease to npv10AutonomousControlRefreshGate.retire(lease)
        }
        if (release.replayDeferredRefresh || autonomous.replayLatestControls) {
            restartRepeatingRequestForRecordTargetChange(lease)
        }
    }

    /**
     * Replays one coalesced AE/AWB/focus update while the exact NPV10 startup lease remains active.
     * The ViewModel re-proves the resulting producer and ten consecutive sensor intervals before it
     * creates the durable master timeline.
     */
    private fun replayDeferredNpv10ProducerStartupRefresh(
        lease: Npv10ProducerStartupLeaseGate.Lease
    ): Boolean {
        val (strictReplay, autonomousReplay) = synchronized(cameraSubmissionLock) {
            npv10ProducerStartupLeaseGate.claimDeferredReplayWhileRetained(lease) to
                npv10AutonomousControlRefreshGate.claimReplayWhileRetained(lease)
        }
        val replay = strictReplay || autonomousReplay.claimed
        if (autonomousReplay.claimed) {
            Log.i(
                TAG,
                "NPV10_AUTONOMOUS_CONTROL_PREWRITER_REPLAY " +
                    "coalesced=${autonomousReplay.coalescedRefreshCount}"
            )
        }
        if (replay) restartRepeatingRequestForRecordTargetChange(lease)
        return replay
    }

    /**
     * Commits the proven producer without mistaking a coalesced engine refinement for a Camera2
     * request mutation. Autonomous AE/AWB/AF feedback only updated controller memory while this
     * window was active; [updatePreviewFromAutonomousControlFeedback] never published it. Retiring
     * that snapshot is therefore safe and preserves the exact request whose cadence and first durable
     * frame were proven. A user/external request still reaches the strict lease and fails closed.
     */
    private fun commitStableNpv10ProducerStartupLease(
        lease: Npv10ProducerStartupLeaseGate.Lease
    ) {
        val (release, autonomous) = synchronized(cameraSubmissionLock) {
            val autonomousRetirement = npv10AutonomousControlRefreshGate.retire(lease)
            val strictRelease = npv10ProducerStartupLeaseGate.releaseAfterCadenceStabilization(lease)
            strictRelease to autonomousRetirement
        }
        if (autonomous.replayLatestControls) {
            Log.i(
                TAG,
                "NPV10_AUTONOMOUS_CONTROL_COMMIT_COALESCED coalesced=${autonomous.coalescedRefreshCount} " +
                    "startupCommitted=${release.released}"
            )
        }
        if (release.released) {
            NeoLogger.i(
                TAG,
                "NPV10_STARTUP_COMMIT stable=true autonomousCoalesced=" +
                    autonomous.coalescedRefreshCount
            )
        }
        if (release.replayDeferredRefresh) {
            NeoLogger.w(TAG, "NPV10_USER_CONTROL_LATE_BOUNDARY startupCommitted=false")
        } else if (!release.released) {
            NeoLogger.w(
                TAG,
                "NPV10_STARTUP_COMMIT stable=false revoked=${lease.revoked.get()} " +
                    "duplicate=${release.duplicate}"
            )
        }
    }

    private fun cancelNpv10ProducerStartupLease(
        lease: Npv10ProducerStartupLeaseGate.Lease,
        reason: String
    ) {
        val replayDeferredRefresh = synchronized(cameraSubmissionLock) {
            val replayStrict = lease.deferredRefresh.get() && !lease.releaseRequested.get()
            npv10ProducerStartupLeaseGate.revoke(lease, reason)
            val autonomous = npv10AutonomousControlRefreshGate.retire(lease)
            replayStrict || autonomous.replayLatestControls
        }
        if (replayDeferredRefresh) restartRepeatingRequestForRecordTargetChange()
    }

    private fun failNpv10AuthorizedPublication(
        lease: Npv10ProducerStartupLeaseGate.Lease,
        reason: String
    ) {
        val (revoked, replayAutonomous) = synchronized(cameraSubmissionLock) {
            val strictRevoked = npv10ProducerStartupLeaseGate.revoke(lease, reason)
            strictRevoked to npv10AutonomousControlRefreshGate.retire(lease).replayLatestControls
        }
        if (revoked) Log.e(TAG, "NPV10 startup request publication failed closed: $reason")
        if (replayAutonomous) restartRepeatingRequestForRecordTargetChange()
    }

    private fun dispatchNpv10AuthorizedPublication(
        lease: Npv10ProducerStartupLeaseGate.Lease,
        action: () -> Unit
    ) {
        Npv10AuthorizedCameraDispatch.dispatch(
            execute = { runnable -> ThreadPoolManager.cameraExecutor.execute(runnable) },
            post = { runnable -> backgroundHandler?.post(runnable) == true },
            action = action,
            onFailure = { reason, failure ->
                val detail = failure?.let { ": ${it.message ?: it.javaClass.simpleName}" }.orEmpty()
                failNpv10AuthorizedPublication(lease, reason + detail)
            }
        )
    }

    override fun stopVideoCapture() {
        stopVideoCapture(retargetRepeating = true)
    }

    fun stopVideoCapture(retargetRepeating: Boolean) {
        recordingActive = false
        processedRecordingSessionPreparationActive = false
        reconcileNiv99MetadataIngressPriority("video-capture-stop")
        reconcileNiv99ImageIngressPriority("video-capture-stop")
        extremeDetailFallbackExposureRequestApplied = false
        if (retargetRepeating) restartRepeatingRequestForRecordTargetChange()
        transitionTo(CameraState.Streaming)
    }

    override fun refreshRepeatingRequest() {
        restartRepeatingRequestForRecordTargetChange()
    }

    private fun restartRepeatingRequestForRecordTargetChange(
        authorizedNpv10StartupLease: Npv10ProducerStartupLeaseGate.Lease? = null
    ) {
        if (authorizedNpv10StartupLease != null) {
            if (captureSession == null || backgroundHandler == null) {
                failNpv10AuthorizedPublication(
                    authorizedNpv10StartupLease,
                    "Camera2 session unavailable for authorized NPV10 startup publication"
                )
                return
            }
            dispatchNpv10AuthorizedPublication(authorizedNpv10StartupLease) authorizedPublication@{
                if (deferRepeatingRefreshBehindActiveBoundary()) {
                    failNpv10AuthorizedPublication(
                        authorizedNpv10StartupLease,
                        "Camera2 boundary became active before authorized NPV10 startup publication"
                    )
                    return@authorizedPublication
                }
                if (sessionRefreshTransitionActive()) {
                    failNpv10AuthorizedPublication(
                        authorizedNpv10StartupLease,
                        "Camera2 session transition began before authorized NPV10 startup publication"
                    )
                    return@authorizedPublication
                }
                val session = captureSession
                if (session == null) {
                    failNpv10AuthorizedPublication(
                        authorizedNpv10StartupLease,
                        "Camera2 session was lost before authorized NPV10 startup publication"
                    )
                    return@authorizedPublication
                }
                val publicationBefore = repeatingPublicationGeneration.get()
                val admitted = publishRepeatingRequestIfSessionStable {
                    if (captureSession === session) {
                        startPreviewInternal(
                            expectedGeneration = authorizedNpv10StartupLease.sessionGeneration,
                            expectedSession = session,
                            authorizedRawResumeBoundary = null,
                            authorizedNpv10StartupLease = authorizedNpv10StartupLease
                        )
                    }
                }
                if (!admitted) {
                    failNpv10AuthorizedPublication(
                        authorizedNpv10StartupLease,
                        "Camera2 session transition won final NPV10 publication admission"
                    )
                    return@authorizedPublication
                }
                val published = activeRepeatingSequence.get()?.let { repeating ->
                    repeating.publicationGeneration > publicationBefore &&
                        repeating.identity.session === session &&
                        repeating.identity.sessionGeneration ==
                            authorizedNpv10StartupLease.sessionGeneration &&
                        repeating.p010Surface != null
                } == true
                if (!published) {
                    failNpv10AuthorizedPublication(
                        authorizedNpv10StartupLease,
                        "Authorized NPV10 Camera2 repeating request was not published"
                    )
                }
            }
            return
        }
        if (deferRepeatingRefreshToSessionTransaction()) return
        if (deferRepeatingRefreshBehindActiveBoundary()) return
        if (captureSession == null || backgroundHandler == null) return
        if (!pendingRepeatingRequestRefresh.compareAndSet(false, true)) return
        runOnCameraThread {
            pendingRepeatingRequestRefresh.set(false)
            if (deferRepeatingRefreshBehindActiveBoundary()) return@runOnCameraThread
            val session = captureSession ?: return@runOnCameraThread
            publishRepeatingRequestIfSessionStable {
                if (captureSession === session) {
                    startPreview(sessionGeneration.get(), session)
                }
            }
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
        supportsManualColorCorrection = caps.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
        )
        ultraHighResRawSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) && supportsRaw
        } else false

        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
        supportsOIS = oisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        val videoStabModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
        supportsPreviewStabilization = videoStabModes.contains(
            CameraVideoStabilizationRequestPolicy.PREVIEW_STABILIZATION_MODE
        )
        supportsVideoStabilization =
            videoStabModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) ||
                supportsPreviewStabilization
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
            cachedAePriorityModes =
                chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES)
                    ?: intArrayOf(AE_PRIORITY_MODE_OFF)
            val cctRange = chars.get(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE)
            manualWhiteBalanceRange = cctRange?.let { Range(it.lower, it.upper) }
                ?: Range(2_000, 12_000)
            supportsCctWhiteBalance =
                cctRange != null &&
                    colorCorrectionModes.contains(CameraMetadata.COLOR_CORRECTION_MODE_CCT)
        } else {
            cachedColorCorrectionModes = IntArray(0)
            cachedAePriorityModes = intArrayOf(AE_PRIORITY_MODE_OFF)
            manualWhiteBalanceRange = Range(2_000, 12_000)
            supportsCctWhiteBalance = false
        }
        cachedAeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
            ?: intArrayOf(CaptureRequest.CONTROL_AE_MODE_ON)
        cachedAwbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?: intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)
        supportsAutoWhiteBalanceLock =
            chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
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
                // FPS is request-only when the sidecar graph is unchanged, but it must still obey the
                // mode/session transaction barrier. Publishing directly to the outgoing session can
                // target surfaces that the replacement session, not the old one, owns.
                restartRepeatingRequestForRecordTargetChange()
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
        val normalizedDegrees = OrientationManager.normalizeDegrees(degrees)
        val publishedGeometry = frameGeometryFlow.value
        if (deviceRotationDegrees == normalizedDegrees &&
            (publishedGeometry == null ||
                publishedGeometry.deviceRotationDegrees == normalizedDegrees)
        ) {
            return
        }
        deviceRotationDegrees = normalizedDegrees
        // The direct SurfaceView can resolve orientation from its own display state, but the RAW
        // debayer and project-owned NDR monitor compositors consume FrameGeometry. Publish a fresh
        // geometry transaction for every physical quarter-turn so those asynchronous domains never
        // retain the upright-portrait transform in reverse portrait or either landscape direction.
        // This is preview/metadata geometry only; recorded sensor pixels are not rotated here.
        refreshFrameGeometry()
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
        check(destroyWithRetirementProof()) {
            "CameraController destroy refused: ImageReader ownership remains quarantined"
        }
    }

    /**
     * Destructive-retirement proof used by the ViewModel's fail-stop native-resource lease.
     * A false result deliberately leaves the background owner alive so quarantined ImageReaders
     * remain strongly reachable and can be retried instead of being closed beneath native users.
     */
    fun destroyWithRetirementProof(): Boolean = destroyWithProof()

    internal fun destroyWithProof(): Boolean {
        // Release the process-global flag callbacks registered in createSession (each captures `this`) so this
        // dead controller is not retained after teardown until the next controller overwrites the slots.
        ColorDriftEnforcementFlag.registerOnChanged(null)
        MeteringDampingFlag.registerOnChanged(null)
        SessionRebuildColorHoldFlag.registerOnChanged(null)
        DeviceTrueWhiteBalanceFlag.registerOnChanged(null)
        RawPreviewViewableTonemapFlag.registerOnChanged(null)
        CinematicFocusPullFlag.registerOnChanged(null)
        CinematicFocusPullDebugCommands.register(null)
        if (!releaseCameraResourcesWithProof(true)) return false
        stopBackground()
        return true
    }

    private fun reconcileMotionTelemetryRegistration() = synchronized(cameraCallbackLock) {
        val required = cameraCallbacksEnabled &&
            CameraMotionTelemetryDemandPolicy.shouldCollect(gyroEisTelemetryConsumerEnabled)
        if (required) startMotionTelemetryLocked() else stopMotionTelemetryLocked()
    }

    private fun startMotionTelemetryLocked() {
        val handler = motionTelemetryHandler ?: return
        if (gyroscopeTelemetryRegistered.compareAndSet(false, true)) {
            val gyroRegistered = gyroscopeSensor?.let { sensor ->
                sensorManager?.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_FASTEST,
                    handler
                ) == true
            } ?: false
            val accelerometerRegistered = accelerometerSensor?.let { sensor ->
                sensorManager?.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_FASTEST,
                    handler
                ) == true
            } ?: false
            if (!gyroRegistered && !accelerometerRegistered) {
                gyroscopeTelemetryRegistered.set(false)
            }
        }
    }

    private fun stopMotionTelemetryLocked() {
        if (gyroscopeTelemetryRegistered.compareAndSet(true, false)) {
            sensorManager?.unregisterListener(this)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        // unregisterListener may race one already-queued callback. Do not let that stale event revive
        // dormant metadata work after demand was withdrawn.
        if (!CameraMotionTelemetryDemandPolicy.shouldCollect(gyroEisTelemetryConsumerEnabled)) return
        if (event.values.size < 3) return
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                motionTelemetry = CameraMotionTelemetry.fromGyroscope(
                    base = motionTelemetry,
                    timestampNs = event.timestamp,
                    gyroXRadPerSec = event.values[0],
                    gyroYRadPerSec = event.values[1],
                    gyroZRadPerSec = event.values[2]
                )
                motionTelemetryRingBuffer.push(motionTelemetry)
            }
            Sensor.TYPE_ACCELEROMETER -> Unit
            else -> return
        }
        try {
            onMotionSensorSample?.invoke(
                event.sensor.type,
                event.timestamp,
                event.values[0],
                event.values[1],
                event.values[2]
            )
        } catch (throwable: Throwable) {
            if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
            Log.e(TAG, "Frame-metadata sensor consumer failed independently", throwable)
        }
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

    /** Null until the existing device-gated calibration explicitly marks its offset usable. */
    fun measuredCameraToImuClockOffsetNsOrNull(): Long? =
        gyroToCameraClockOffsetNs.takeIf { gyroEisTelemetryConsumerEnabled }

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
