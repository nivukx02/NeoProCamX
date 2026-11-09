package com.neoproc.neoproccamx.rendering

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.neoproc.neoproccamx.ai.SubjectMask
import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.niv99.Niv99LiveFrameTuning
import com.neoproc.neoproccamx.pipeline.SensorPresentationClock
import com.neoproc.neoproccamx.pipeline.SensorLinearNdrRecordingFrameIdentity
import com.neoproc.neoproccamx.tessera.TesseraExecutionBackend
import com.neoproc.neoproccamx.tessera.TesseraRuntimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class VulkanImportStats(
    val importedFrames: Long = 0L,
    val failedImports: Long = 0L,
    val sessionImportedFrames: Long = 0L,
    val sessionFailedImports: Long = 0L,
    val consecutiveFailedImports: Long = 0L,
    val lastImportedWidth: Int = 0,
    val lastImportedHeight: Int = 0,
    val lastImportedFormat: Int = 0,
    val frameImportEnabled: Boolean = false,
    val previewSurfaceBound: Boolean = false,
    val encoderSurfaceBound: Boolean = false,
    val offscreenAhbEglEncoderActive: Boolean = false,
    val offscreenEglEncoderSurfaceBound: Boolean = false,
    val offscreenEglSubmittedFrames: Long = 0L,
    val offscreenEglFailedFrames: Long = 0L,
    val offscreenEglPresentationTimeSet: Boolean = false,
    val lastSuccessfulImportElapsedMs: Long = 0L,
    val firstSuccessfulImportElapsedMs: Long = 0L,
    val lastSessionResetElapsedMs: Long = 0L,
    // s7-f12/CP9B: real native zero-copy signal parsed from the capability summary (default false
    // == today's "unavailable"); never set by the accumulator, only by refreshCapabilitySummary().
    val zeroCopyLiveImport: Boolean = false
)

data class VulkanFrameLineageSubmission(
    val sensorTimestampNs: Long,
    val presentationTimeNs: Long,
    val previewSubmitted: Boolean,
    val encoderSubmitted: Boolean,
    /** Immutable Camera2 source generation; -1 is reserved for non-Camera2/legacy submissions. */
    val cameraSessionGeneration: Long = -1L,
    /** Untranslated vendor timestamp retained for clock-domain diagnostics. */
    val rawSensorTimestampNs: Long = sensorTimestampNs
)

/** Exact result of the most recent camera-image submission on the calling thread. */
internal data class CameraImageSubmissionOutcome(
    val accepted: Boolean,
    val recordingSubmissionExpected: Boolean,
    val reason: String,
    val waitedForProfileIngress: Boolean = false
)

/**
 * Defines the exact clip boundary for Camera2 hardware-buffer submissions.
 *
 * The persistent Camera2 target can deliver frames before the offscreen encoder is armed and after
 * its live gate closes. Those buffers must still be consumed and diagnosed, but they are not clip
 * frames and therefore cannot be counted as recording loss. A recording submission is expected only
 * while the encoder surface is both bound/active and its live submission gate is open.
 */
internal object CameraImageRecordingExpectationPolicy {
    fun isLiveSubmissionExpected(
        recordingPath: Boolean,
        offscreenEncoderActive: Boolean,
        offscreenSubmissionEnabled: Boolean
    ): Boolean = recordingPath && offscreenEncoderActive && offscreenSubmissionEnabled
}

/** Immutable handoff from full-resolution encoder acceptance to the ordered preview worker. */
internal data class SensorLinearNdrRecordingPreviewSubmission(
    val frame: RawDemosaicFrameIdentity,
    val output: RawDemosaicPreviewOutput,
    val uniforms: GpuImageEnhancementUniforms,
    val renderProfileGeneration: Long,
    val renderProfileRevision: Long
) {
    val renderProfileIdentity: NdrRenderProfileIdentity
        get() = NdrRenderProfileIdentity(renderProfileGeneration, renderProfileRevision)
}

data class VulkanRenderConfig(
    val previewTransform: FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    ),
    val encoderTransform: FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    ),
    val colorGain: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
    val previewGrade: FloatArray = floatArrayOf(
        1f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f,
        0f, 0f, 0f, 0f,
        0f, 2f, 0f, 0f
    ),
    val encoderGrade: FloatArray = floatArrayOf(
        1f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f,
        0f, 0f, 0f, 0f,
        0f, 2f, 0f, 0f
    ),
    val previewColorScience: FloatArray = VulkanColorScienceUniforms.disabled().pack(),
    val encoderColorScience: FloatArray = VulkanColorScienceUniforms.disabled().pack()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VulkanRenderConfig) return false
        return previewTransform.contentEquals(other.previewTransform) &&
               encoderTransform.contentEquals(other.encoderTransform) &&
               colorGain.contentEquals(other.colorGain) &&
               previewGrade.contentEquals(other.previewGrade) &&
               encoderGrade.contentEquals(other.encoderGrade) &&
               previewColorScience.contentEquals(other.previewColorScience) &&
               encoderColorScience.contentEquals(other.encoderColorScience)
    }

    override fun hashCode(): Int {
        var result = previewTransform.contentHashCode()
        result = 31 * result + encoderTransform.contentHashCode()
        result = 31 * result + colorGain.contentHashCode()
        result = 31 * result + previewGrade.contentHashCode()
        result = 31 * result + encoderGrade.contentHashCode()
        result = 31 * result + previewColorScience.contentHashCode()
        result = 31 * result + encoderColorScience.contentHashCode()
        return result
    }
}

/**
 * Freezes every mutable array before render intent can be retained or cross JNI. Kotlin data classes
 * do not clone primitive arrays, so a normal copy() would still allow the caller to mutate a deferred
 * generation after admission.
 */
internal fun VulkanRenderConfig.immutableSnapshot(): VulkanRenderConfig = VulkanRenderConfig(
    previewTransform = previewTransform.copyOf(),
    encoderTransform = encoderTransform.copyOf(),
    colorGain = colorGain.copyOf(),
    previewGrade = previewGrade.copyOf(),
    encoderGrade = encoderGrade.copyOf(),
    previewColorScience = previewColorScience.copyOf(),
    encoderColorScience = encoderColorScience.copyOf()
)

/** Point-in-time, mutation-isolated packet used by both immediate apply and cold-start replay. */
internal fun NdrRenderProfilePacket.immutableSnapshot(): NdrRenderProfilePacket = copy(
    nativeRenderConfig = nativeRenderConfig.copy(
        renderConfig = nativeRenderConfig.renderConfig.immutableSnapshot()
    ),
    subjectDetailMask = subjectDetailMask?.let { mask ->
        mask.copy(alphaMask = mask.alphaMask.copyOf())
    }
)

private fun SubjectMask?.contentEquals(other: SubjectMask?): Boolean = when {
    this === other -> true
    this == null || other == null -> false
    else -> maskWidth == other.maskWidth &&
        maskHeight == other.maskHeight &&
        trackingId == other.trackingId &&
        category == other.category &&
        intensity == other.intensity &&
        alphaMask.contentEquals(other.alphaMask)
}

/** One indivisible proof that every native bundle stage accepted the same immutable generation. */
internal data class AppliedNdrRenderBundle(
    val profileGeneration: Long,
    val configRevision: Long,
    val correction: NdrNivukxMeasuredCorrection,
    val enhancementUniforms: GpuImageEnhancementUniforms,
    val nativeRenderConfig: VulkanNativeRenderProfileConfig,
    val subjectDetailMask: SubjectMask?,
    val liveProfile: NdrLiveProfile
) {
    val identity: NdrRenderProfileIdentity
        get() = NdrRenderProfileIdentity(profileGeneration, configRevision)

    fun matches(packet: NdrRenderProfilePacket): Boolean =
        profileGeneration == packet.profileGeneration &&
            configRevision == packet.configRevision &&
            correction == packet.correction &&
            enhancementUniforms == packet.enhancementUniforms &&
            nativeRenderConfig == packet.nativeRenderConfig &&
            subjectDetailMask.contentEquals(packet.subjectDetailMask)
}

/** Atomic holder prevents generation-first or field-by-field publication to frame readers. */
internal class AtomicNdrRenderBundleState {
    private val reference = AtomicReference<AppliedNdrRenderBundle?>(null)

    fun snapshot(): AppliedNdrRenderBundle? = reference.get()

    fun publish(bundle: AppliedNdrRenderBundle) {
        reference.set(bundle)
    }

    fun clear(): AppliedNdrRenderBundle? = reference.getAndSet(null)
}

class VulkanRuntimeManager : Closeable {

    companion object {
        private const val TAG = "VulkanRuntime"
        private const val RECORDING_PROFILE_TRANSITION_WAIT_FRAMES = 3L
        private const val MAX_RECORDING_PROFILE_TRANSITION_WAIT_NS = 150_000_000L
        /** Creative LUTs are applied at full strength on the record surface (SDR only). */
        private const val RECORD_CREATIVE_LUT_STRENGTH = 1.0f
        private val AVG_COMPUTE_MS_PATTERN =
            Regex("""(?:^|\s)avgComputeMs=([^\s,;]+)""")
        private val TOTAL_DISPATCHES_PATTERN =
            Regex("""(?:^|\s)totalDispatches=([^\s,;]+)""")
        private val GPU_TIMESTAMP_SAMPLES_PATTERN =
            Regex("""(?:^|\s)gpuTimestampSamples=([^\s,;]+)""")
        
        /** Batched config updates to reduce JNI overhead */
        private const val CONFIG_UPDATE_BATCH_FRAMES = 15 // Increased from 5 for better performance
        private const val CONFIG_FORCE_REFRESH_ON_FAILURES = 2L
        private const val NDR_PREVIEW_TRANSPORT_RING_SLOTS = 3
        private const val RAW_RECORDING_TRANSPORT_RING_SLOTS = 2
        private const val NDR_PREVIEW_DRAIN_TIMEOUT_MS = 3_000L
        private const val NDR_ENCODER_INPUT_READ_DRAIN_TIMEOUT_NS = 2_000_000_000L

        internal data class AverageComputeTimingEvidence(
            val averageComputeMs: Double,
            val totalDispatches: Long
        )

        internal fun parseAverageComputeTimingEvidence(
            summary: String
        ): AverageComputeTimingEvidence? {
            val averageMatch = AVG_COMPUTE_MS_PATTERN.findAll(summary).lastOrNull()
                ?: return null
            val dispatchMatch = TOTAL_DISPATCHES_PATTERN
                .find(summary, startIndex = averageMatch.range.last + 1)
                ?: return null
            val averageComputeMs = averageMatch.groupValues
                .getOrNull(1)
                ?.trimEnd(']', ')')
                ?.toDoubleOrNull()
                ?: return null
            val totalDispatches = dispatchMatch.groupValues
                .getOrNull(1)
                ?.trimEnd(']', ')')
                ?.toLongOrNull()
                ?: return null
            if (
                !averageComputeMs.isFinite() ||
                averageComputeMs <= 0.0 ||
                totalDispatches <= 0L
            ) {
                return null
            }
            return AverageComputeTimingEvidence(
                averageComputeMs = averageComputeMs,
                totalDispatches = totalDispatches
            )
        }

        internal fun parseAverageComputeMs(summary: String): Double? {
            return parseAverageComputeTimingEvidence(summary)?.averageComputeMs
        }

        internal fun resolveEncoderSurfaceStatus(
            surfaceRequested: Boolean,
            bindSucceeded: Boolean,
            reportedStatus: VulkanEncoderSurfaceStatus
        ): VulkanEncoderSurfaceStatus {
            if (!surfaceRequested || !bindSucceeded) {
                return VulkanEncoderSurfaceStatus.UNBOUND
            }
            return reportedStatus
        }

        internal fun resolveOffscreenEncoderSurfaceStatus(
            surfaceRequested: Boolean,
            bindSucceeded: Boolean,
            reportedStatus: VulkanEncoderSurfaceStatus,
            expectedDeliveryMode: String? = null
        ): VulkanEncoderSurfaceStatus {
            if (!surfaceRequested ||
                !bindSucceeded ||
                !reportedStatus.surfacePrecisionProven ||
                reportedStatus.bitDepth < 10 ||
                // Accept SDR (8/10-bit) AND the 10-bit HDR transports (HLG/PQ) — a 10-bit encoder
                // surface is HDR (BT.2020); requiring SDR-only was what kept the GPU 10-bit path
                // permanently rejected even after a successful bind. The native bridge now reports
                // the CANONICAL transport token (HLG/PQ/SDR), matched by surfacePrecisionProven's
                // isKnownEncoderDeliveryMode; this check stays as a redundant, explicit guard.
                !(reportedStatus.deliveryMode == "SDR" ||
                    reportedStatus.deliveryMode == "HLG" ||
                    reportedStatus.deliveryMode == "PQ") ||
                (expectedDeliveryMode != null &&
                    reportedStatus.deliveryMode != expectedDeliveryMode) ||
                !reportedStatus.deliveryCompatible
            ) {
                return VulkanEncoderSurfaceStatus.UNBOUND
            }
            return reportedStatus
        }

        /** A native offscreen-EGL summary indicates a GENUINE GPU hardware wall (worth persisting
         *  across launches) only when enumeration proves there is no 10-bit recordable EGL config.
         *  Window-surface allocation failures can also mean another API still owns the BufferQueue;
         *  any such non-proven outcome is a transient/token
         *  miss that must stay re-probable — NOT a wall. */
        internal fun offscreenSummaryIndicatesHardwareWall(nativeSummary: String): Boolean =
            nativeSummary.contains("config unavailable")
    }

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun configUpdateBatchFrames(): Int =
        if (strictHighEndEnabled()) 3 else CONFIG_UPDATE_BATCH_FRAMES

    private fun combinedCapabilitySummary(): String =
        "${bridge.capabilitySummary()} ${bridge.enhancementCapabilitySummary()} " +
            "${bridge.parityBridgeSummary()} ${offscreenAhbEglEncoderBridge.summary()}"

    private fun refreshCapabilitySummary() {
        val summary = combinedCapabilitySummary()
        _capabilitySummary.value = summary
        _encoderSurfaceStatus.value = VulkanEncoderSurfaceStatusParser.parse(summary)
        // s7-f12/CP9B: fold the REAL native zero-copy token into import-stats. Guarded by an
        // equality check so it emits only on a genuine transition; the default token "unavailable"
        // => no-op => bit-for-bit today (no extra StateFlow churn, including on the init refresh).
        val zeroCopy = VulkanImportTelemetryResolver.parseZeroCopyLiveImportAvailable(summary)
        if (importStatsAccumulator.current.zeroCopyLiveImport != zeroCopy) {
            _importStats.value = importStatsAccumulator.mutate { it.copy(zeroCopyLiveImport = zeroCopy) }.stats
        }
    }

    /**
     * Runtime-health-monitor recovery hook (non-destructive): re-probe native capabilities and the
     * encoder-surface status so a transiently-degraded GPU pipeline can re-prove itself without any
     * session/encoder teardown. Safe to call at any time.
     */
    fun requestCapabilitySummaryRefresh() = refreshCapabilitySummary()

    private val initialized = AtomicBoolean(false)
    /** Distinguishes an in-progress cold start from a completed unsupported/failed runtime. */
    private val initializationCompleted = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)
    private val closeCompleted = AtomicBoolean(false)
    @Volatile private var runtimeCloseQuarantined = false
    private val frameImportEnabled = AtomicBoolean(false)
    @Volatile private var offscreenEncoderTargetFps = 25
    private val offscreenFramePacingLock = Any()
    private var offscreenFramePacingState = OffscreenEncoderFramePacingPolicy.reset(25)
    private var offscreenEncoderReadinessPresentationTimeNs = 0L
    private val offscreenEncoderLiveSubmissionAttempts = AtomicLong(0L)
    private val offscreenEncoderLiveSubmissionAccepted = AtomicLong(0L)
    private val offscreenEncoderLiveSubmissionRejected = AtomicLong(0L)
    private val cameraP010SdrPrewarmTracker = SdrCameraP010PrewarmTracker()

    /** Lazy bridge initialization to defer native library load */
    private val bridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { VulkanBridge() }
    private val offscreenAhbEglEncoderBridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OffscreenAhbEglEncoderBridge()
    }
    private val sensorLinearNdrPreviewBridgeDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OffscreenAhbEglEncoderBridge()
    }
    private val sensorLinearNdrPreviewBridge by sensorLinearNdrPreviewBridgeDelegate
    private val sensorLinearNdrPreviewLock = Any()
    private val sensorLinearNdrRecordingPreviewWorker = OrderedNdrPreviewTransportWorker(
        maxOutstanding = NDR_PREVIEW_TRANSPORT_RING_SLOTS,
        onTaskRetired = ::onSensorLinearNdrRecordingPreviewTaskRetired
    )
    private val rawPreviewTransportBackpressureRetryCount = AtomicLong(0L)
    private val rawDemosaicCompletionLock = Any()
    private val rawDemosaicCompletionHandoff = RawDemosaicCompletionHandoff()
    private val sensorLinearNdrEncoderInputReads = EncoderInputReadOwnershipRing()
    private enum class EncoderInputReadRetirement {
        NONE,
        RETIRED,
        PENDING,
        FAILED
    }
    private enum class NdrProfileTransitionRetirementStatus {
        DRAINED,
        PENDING,
        FAILED
    }
    private data class NdrProfileTransitionRetirement(
        val status: NdrProfileTransitionRetirementStatus,
        val nativeOutputOwned: Boolean,
        val reason: String
    )
    @Volatile
    private var sensorLinearNdrSessionGenerationValidator: ((Long) -> Boolean)? = null
    @Volatile
    private var sensorLinearNdrDemosaicOwnerPhase =
        SensorLinearNdrDemosaicOwnerPhase.PREVIEW
    @Volatile private var boundSensorLinearNdrPreviewSurface: Surface? = null
    @Volatile private var sensorLinearNdrPreviewActive = false

    /** Cinema-Debayer pipeline for 12-bit RAW processing (bypasses Android ISP) */
    val cinemaDebayer: VulkanCinemaDebayerPipeline by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        VulkanCinemaDebayerPipeline()
    }

    private val _supported = MutableStateFlow(false)
    val supported: StateFlow<Boolean> = _supported.asStateFlow()

    private val _capabilitySummary = MutableStateFlow("Vulkan runtime not initialized")
    val capabilitySummary: StateFlow<String> = _capabilitySummary.asStateFlow()
    val currentMeasuredAverageComputeDurationMs: Double?
        get() = parseAverageComputeMs(_capabilitySummary.value)

    /** Process-lifetime peak of the observed average compute latency (Kotlin-tracked; native has EMA only). */
    @Volatile
    private var peakComputeDurationMs: Double = 0.0

    /** Live GPU-compute telemetry for the runtime health monitor. */
    data class LiveComputeTelemetry(
        val dispatched: Boolean,
        val averageMs: Double?,
        val totalDispatches: Long?,
        val peakMs: Double?,
        val gpuTimestamped: Boolean
    ) {
        companion object {
            val EMPTY = LiveComputeTelemetry(false, null, null, null, false)
        }
    }

    /**
     * A fresh, NON-mutating read of the native GPU-compute telemetry. Unlike
     * [currentMeasuredAverageComputeDurationMs] — which reads the event-driven [capabilitySummary]
     * StateFlow that only refreshes on transitions/failures and therefore goes STALE during steady-state
     * work — this pulls the live native summary directly on each call, so the dispatch count / average /
     * peak advance in real time while compute is actually running (root cause of the "Compute dispatched:
     * NO / Average compute: --" symptom while the GPU was in fact dispatching). It emits on no StateFlow
     * (zero collector churn) and is cheap enough for the ~150 ms health cadence. Honest by construction:
     * returns [LiveComputeTelemetry.EMPTY] when the runtime is not initialized/supported.
     */
    fun liveComputeTelemetry(): LiveComputeTelemetry {
        if (!initialized.get() || !_supported.value) return LiveComputeTelemetry.EMPTY
        val native = try {
            bridge.liveComputeTelemetry()
        } catch (_: Throwable) {
            null
        } ?: return LiveComputeTelemetry.EMPTY
        val validSample = native.totalDispatches > 0L &&
            native.averageComputeMs.isFinite() && native.averageComputeMs > 0.0
        val avg = native.averageComputeMs.takeIf { validSample }
        if (avg != null && avg > peakComputeDurationMs) peakComputeDurationMs = avg
        return LiveComputeTelemetry(
            dispatched = native.computePipelineReady && native.totalDispatches > 0L,
            averageMs = avg,
            totalDispatches = native.totalDispatches.takeIf { validSample },
            peakMs = if (peakComputeDurationMs > 0.0) peakComputeDurationMs else null,
            gpuTimestamped = native.gpuTimestampSamples > 0L
        )
    }

    private val _encoderSurfaceStatus =
        MutableStateFlow(VulkanEncoderSurfaceStatus.UNBOUND)
    val encoderSurfaceStatus: StateFlow<VulkanEncoderSurfaceStatus> =
        _encoderSurfaceStatus.asStateFlow()
    val currentEncoderSurfaceStatus: VulkanEncoderSurfaceStatus
        get() = _encoderSurfaceStatus.value

    /**
     * Sticky (per process) flag: set once the offscreen AHB→EGL→MediaCodec 10-bit encoder surface
     * has PROVEN unbindable at runtime on this device. The recording feed-path selector consults this
     * so that, after the first real failure, recording goes straight to the proven legacy P010 path
     * instead of re-attempting the offscreen bind on every recording start (the cause of the
     * multi-retry "recording start storm" + MediaStore pending-entry churn on devices where the
     * driver cannot back this path). No capability is lost: the offscreen path is still attempted
     * once for discovery, and a process restart re-probes.
     */
    @Volatile
    private var offscreenEncoderProvenUnavailable = false
    val isOffscreenAhbEglEncoderProvenUnavailable: Boolean
        get() = offscreenEncoderProvenUnavailable

    /** Recall a device-confirmed offscreen-encoder failure (persisted across launches) so the
     *  feed-path selector skips the dead path from the first recording instead of re-probing it. */
    fun markOffscreenAhbEglEncoderProvenUnavailable() {
        offscreenEncoderProvenUnavailable = true
    }

    @Volatile
    private var offscreenEncoderHardwareWallProven = false
    /** True when the most recent offscreen bind failure was a GENUINE GPU-driver hardware wall (no
     *  10-bit recordable EGL config, or the codec surface refused every colorspace) rather than a
     *  transient/token miss. Only a hardware wall is worth persisting across launches; everything
     *  else must stay re-probable so a fix (or a warm GPU) can prove the path on a later attempt. */
    val isOffscreenAhbEglEncoderHardwareWallProven: Boolean
        get() = offscreenEncoderHardwareWallProven

    private val _importStats = MutableStateFlow(VulkanImportStats())
    val importStats: StateFlow<VulkanImportStats> = _importStats.asStateFlow()
    private val importStatsAccumulator = VulkanImportStatsAccumulator(
        initialStats = _importStats.value,
        configRefreshFailureThreshold = CONFIG_FORCE_REFRESH_ON_FAILURES
    )
    private val cameraImportSessionGate = VulkanCameraImportSessionGate()
    private val presentationClock = SensorPresentationClock()
    private val cameraImageSubmissionOutcome = ThreadLocal.withInitial {
        CameraImageSubmissionOutcome(
            accepted = false,
            recordingSubmissionExpected = false,
            reason = "not-submitted"
        )
    }

    @Volatile private var renderConfig = VulkanRenderConfig()
    private var lastRenderConfig = VulkanRenderConfig()
    @Volatile private var framesSinceConfigUpdate = 0
    @Volatile private var configDirty = true
    @Volatile private var enhancementUniforms: GpuImageEnhancementUniforms? = null
    @Volatile private var enhancementDirty = true
    // Record-grade stage (offscreen-EGL). Identity by default => recorded pixels unchanged.
    @Volatile private var offscreenRecordGradeMatrix: FloatArray = OffscreenAhbEglEncoderBridge.IDENTITY9
    @Volatile private var offscreenRecordGradeContrast: Float = 1f
    @Volatile private var pendingNdrNivukxMeasuredCorrection =
        NdrNivukxMeasuredCorrectionPolicy.resolve("OFF")
    @Volatile private var appliedNdrNivukxMeasuredCorrection: NdrNivukxMeasuredCorrection? = null
    @Volatile private var appliedNdrNivukxProfileGeneration = 0L
    @Volatile private var appliedNdrLiveProfile: NdrLiveProfile? = null
    private val appliedNdrRenderBundle = AtomicNdrRenderBundleState()
    private val stagedNdrRenderBundle = AtomicNdrRenderBundleState()
    @Volatile private var lastEnhancementSignature = ""
    @Volatile private var lastSubjectDetailMaskSignature = ""
    // ARCHITECTURAL FIX: AtomicLong generation token eliminates O(N) hashCode() calls
    private val configGenerationToken = AtomicLong(0L)
    private val lastConfigGeneration = AtomicLong(0L)
    /**
     * Monotonic owner token for mode/profile state. CameraViewModel advances this token before it
     * mutates VIDEO/PRO/RAW/LOG/NDR authority. Deferred work must present the current token before
     * it can touch native profile, swapchain, render-config, or enhancement state. This prevents an
     * older serialized request from restoring NDR over RAW/LOG/OFF after a fast rail switch.
     */
    // Boundary ownership is always acquired before this generation monitor and before any Vulkan/EGL
    // bridge call. It closes the multi-JNI packet gap without serializing independent frame readers.
    private val ndrNativeFrameBoundary = NdrNativeFrameBoundary()
    private val renderProfileGenerationLock = Any()
    @Volatile private var acceptedRenderProfileGeneration = 0L
    @Volatile private var acceptedRenderProfileRevision = 0L
    private val ndrRenderProfileRevisionAdmission =
        NdrRenderProfileRevisionAdmission<NdrRenderProfilePacket>(
            samePayload = NdrRenderProfilePacket::hasSamePayload
        )
    private val ndrRenderProfileReadinessAdmission =
        NdrRenderProfileReadinessAdmission<NdrRenderProfilePacket>()
    private val ndrRenderProfileMutationDeferral = NdrRenderProfileMutationDeferral()
    private val _ndrRenderProfileMutationReadyIdentity =
        MutableStateFlow<NdrRenderProfileIdentity?>(null)
    /** Exact replacement that may be retried after the prior async RAW output is fully retired. */
    internal val ndrRenderProfileMutationReadyIdentity: StateFlow<NdrRenderProfileIdentity?> =
        _ndrRenderProfileMutationReadyIdentity.asStateFlow()
    private val _ndrRenderProfileStagedGeneration = MutableStateFlow(0L)
    /** Native bundle accepted; the owner must publish its generation-bound state and finalize it. */
    internal val ndrRenderProfileStagedGeneration: StateFlow<Long> =
        _ndrRenderProfileStagedGeneration.asStateFlow()
    private val _ndrRenderProfileReadyGeneration = MutableStateFlow(0L)
    /** Event/state edge for visible-route admission; 0 means the current generation is not ready. */
    internal val ndrRenderProfileReadyGeneration: StateFlow<Long> =
        _ndrRenderProfileReadyGeneration.asStateFlow()
    @Volatile private var boundPreviewSurface: Surface? = null
    @Volatile private var boundEncoderSurface: Surface? = null
    @Volatile private var quarantinedInternalEncoderSurface: Surface? = null
    @Volatile private var internalEncoderDetachQuarantined = false
    @Volatile private var boundOffscreenEncoderSurface: Surface? = null
    private val displayConfigLock = Any()
    @Volatile private var appliedHdrDynamicRangeMode: HdrDynamicRangeMode? = null
    @Volatile private var appliedPreferTenBitSdrDisplay: Boolean? = null
    @Volatile private var offscreenInputDynamicRangeProfile: Long? = null
    @Volatile private var sensorLinearNdrCaptureEnabled = false
    @Volatile private var frameLineageSubmissionListener: ((VulkanFrameLineageSubmission) -> Unit)? = null
    @Volatile
    private var sensorLinearNdrRecordingPreviewListener:
        ((SensorLinearNdrRecordingPreviewSubmission) -> Boolean)? = null
    @Volatile private var offscreenAhbEglEncoderActive = false
    @Volatile private var offscreenEncoderDetachQuarantined = false
    @Volatile private var lastOffscreenImportStatsPublishElapsedMs = 0L
    private val offscreenAhbEglSubmissionEnabled = AtomicBoolean(false)
    private val rawSceneLogTransportStateLock = Any()
    private var appliedRawSceneLogPrepared = false
    private var appliedRawSceneLogSubmission = false
    private var appliedRawDirectNdrPreviewActive = false
    private var appliedRawRecordingDemosaicActive = false

    /**
     * ════════════════════════════════════════════════════════════════════════
     * OPTIMIZED INITIALIZATION - NON-BLOCKING
     * ════════════════════════════════════════════════════════════════════════
     *
     * Initializes Vulkan runtime without blocking the caller.
     * The bridge is lazy-loaded on first use to defer native library loading.
     */
    @Synchronized
    fun initialize(cacheDir: java.io.File? = null) {
        if (closeRequested.get()) {
            initializationCompleted.set(true)
            return
        }
        if (!initialized.compareAndSet(false, true)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _supported.value = false
            _capabilitySummary.value = "Vulkan AHardwareBuffer import requires API 29+"
            initializationCompleted.set(true)
            replayDeferredNdrRenderProfileIfReady("api-level-terminal")
            return
        }
        runCatching {
            com.neoproc.neoproccamx.utils.BootTrace.mark("vk.initialize.start")
            // s7-f13/CP9C: set the process-global pipeline-cache config BEFORE the lazy bridge's
            // first use (its eager nativeCreate runs createGlobalResources). Static call => does not
            // construct the bridge. cacheDir from the caller + DEFAULT_ENABLED=true => header-validated
            // disk pipeline cache (device identity checked; absent/mismatch => fresh compile).
            VulkanBridge.configurePipelineCache(
                PipelineCachePolicy.cacheFilePath(cacheDir),
                PipelineCachePolicy.DEFAULT_ENABLED
            )
            val runtimeSupported = bridge.isSupported()
            com.neoproc.neoproccamx.utils.BootTrace.mark("vk.initialize.bridgeReady")
            _supported.value = runtimeSupported
            TesseraRuntimeProvider.reportBackendAvailability(
                TesseraExecutionBackend.VECTOR_GPU,
                runtimeSupported
            )
            refreshCapabilitySummary()
            // Initialize the Cinema-Debayer compute pipeline so that RAW_SENSOR
            // frames can be submitted for professional debayering preview.
            if (runtimeSupported) {
                cinemaDebayer.initialize(bridge)
                // The offline RAW→NeoLog GPU develop reuses this bridge's demosaic pipeline. Gated OFF by
                // default (RawDevelopGpuAccelerator.developToAcescg no-ops until the develop-GPU flag is
                // enabled AND its first-frame parity check passes), so attaching it is inert until then.
                com.neoproc.neoproccamx.recording.RawDevelopGpuAccelerator.attach(bridge)
            }
            // One-time diagnostic: does this GPU expose a 10-bit RECORDABLE EGL config — the
            // prerequisite for ANY GPU-rendered 10-bit recording? setEncoderSurface(null) runs the
            // EGL config selection (ensureEglReady) with no real surface; summary() carries the raw
            // native status (e.g. "recordable RGB10_A2 config unavailable actual=8/8/8/8") that is
            // otherwise lost to the device's suppressed android.util.Log. This is the definitive
            // proof of whether GPU 10-bit is a hardware wall here.
            runCatching {
                offscreenAhbEglEncoderBridge.setEncoderSurface(null, 0, 0)
                NeoLogger.i(
                    "VulkanEncoder",
                    "GPU_10BIT_EGL_PROBE ${offscreenAhbEglEncoderBridge.summary()}"
                )
            }
            // Do not configure a throwaway MediaCodec during app boot. The real recording-surface bind
            // already produces the decisive capability result when recording is requested. Removing the
            // synthetic HLG setup avoids startup codec/EGL/gralloc contention without changing any frame,
            // encoder setting, output quality, or feature.
        }.onFailure { throwable ->
            _supported.value = false
            TesseraRuntimeProvider.reportBackendAvailability(
                TesseraExecutionBackend.VECTOR_GPU,
                false
            )
            _encoderSurfaceStatus.value = VulkanEncoderSurfaceStatus.UNBOUND
            _capabilitySummary.value = "Vulkan init failed: ${throwable.javaClass.simpleName}"
            Log.w(TAG, "Failed to initialize Vulkan runtime", throwable)
        }
        // Publish completion after every native owner/capability decision above. A packet that
        // arrived during cold start can now make exactly one real apply attempt; unsupported or
        // failed initialization is terminal APPLY_FAILED, not an endless readiness deferral.
        initializationCompleted.set(true)
        replayDeferredNdrRenderProfileIfReady("runtime-initialized")
    }

    fun setCinemaDebayerActive(active: Boolean) {
        if (!_supported.value) return
        cinemaDebayer.setActive(active)
    }

    /**
     * CP-QV21 NRLI: replace the SNCE Stage-11 identity 3D-LUT with a bounded analytic look at the given
     * blend strength. Fail-safe: an invalid array / failed upload leaves the identity LUT + strength 0
     * (exact passthrough) intact. Returns false when unsupported. Only takes effect while the SNCE OCCS
     * stage runs (default on).
     */
    fun updateNrliLut(lut: FloatArray, strength: Float): Boolean {
        if (!_supported.value) return false
        return runCatching { bridge.updateNrliLut(lut, strength); true }.getOrDefault(false)
    }

    /**
     * Live cinematic bokeh: enable/disable the inline Vogel-spiral depth-of-field pass. Default OFF;
     * with it false the pass is skipped and the render path is byte-identical (no-downgrade).
     */
    fun setBokehBlendEnabled(enabled: Boolean) {
        if (!_supported.value) return
        runCatching { bridge.setBokehBlendEnabled(enabled) }
    }

    /**
     * Feed the inline bokeh pass a preview-resolution depth + circle-of-confusion grid (row-major,
     * width*height) plus the focus plane and blend strength. Returns false (pass stays passthrough) if
     * the extent matched no Vulkan target or the runtime is unsupported — never blacks the frame.
     */
    fun uploadBokehInputs(
        depth: FloatArray, coc: FloatArray, width: Int, height: Int,
        focusDepth: Float, blendStrength: Float
    ): Boolean {
        if (!_supported.value) return false
        return runCatching {
            bridge.uploadBokehInputs(depth, coc, width, height, focusDepth, blendStrength)
        }.getOrDefault(false)
    }

    fun setFrameImportEnabled(enabled: Boolean) {
        configDirty = true
        frameImportEnabled.set(enabled && _supported.value)
        if (!enabled) {
            resetOffscreenFramePacing()
            lastOffscreenImportStatsPublishElapsedMs = 0L
        }
        _importStats.value = importStatsAccumulator
            .setFrameImportEnabled(isFrameImportEnabled())
            .stats
    }

    fun isFrameImportEnabled(): Boolean = frameImportEnabled.get() && _supported.value

    fun setOffscreenEncoderTargetFrameRate(fps: Int) {
        val safeFps = fps.coerceAtLeast(1)
        offscreenEncoderTargetFps = safeFps
        synchronized(offscreenFramePacingLock) {
            offscreenFramePacingState = OffscreenEncoderFramePacingPolicy.reset(safeFps)
        }
    }

    @Synchronized
    fun setOffscreenAhbEglSubmissionEnabled(enabled: Boolean) {
        val active = enabled && _supported.value && offscreenAhbEglEncoderActive &&
            !offscreenEncoderDetachQuarantined && boundOffscreenEncoderSurface != null
        val previous = offscreenAhbEglSubmissionEnabled.getAndSet(active)
        // A true -> false transition is the end of one live clip epoch. The persistent codec
        // surface and Camera2 target can stay attached for an instant next start, but the next clip
        // must independently exercise the real rotating camera buffers before its gate opens.
        // Repeated false requests during setup deliberately preserve any warm-up already completed.
        if (previous && !active) {
            cameraP010SdrPrewarmTracker.reset()
        }
        var pacingSnapshot: OffscreenEncoderFramePacingState? = null
        if (previous != active && active) {
            synchronized(offscreenFramePacingLock) {
                offscreenFramePacingState =
                    if (offscreenEncoderReadinessPresentationTimeNs > 0L) {
                        OffscreenEncoderFramePacingPolicy.continueAfterReadiness(
                            readinessPresentationTimeNs =
                                offscreenEncoderReadinessPresentationTimeNs,
                            targetFps = offscreenEncoderTargetFps
                        )
                    } else {
                        OffscreenEncoderFramePacingPolicy.reset(offscreenEncoderTargetFps)
                    }
                pacingSnapshot = offscreenFramePacingState
            }
        }
        if (previous != active) {
            val state = pacingSnapshot ?: synchronized(offscreenFramePacingLock) {
                offscreenFramePacingState
            }
            runCatching {
                NeoLogger.i(
                    "VulkanEncoder",
                    "OFFSCREEN_ENCODER_SUBMISSION_GATE requested=$enabled active=$active " +
                        "previous=$previous supported=${_supported.value} " +
                        "surfaceActive=$offscreenAhbEglEncoderActive " +
                        "surfaceBound=${boundOffscreenEncoderSurface != null} " +
                        "detachQuarantined=$offscreenEncoderDetachQuarantined " +
                        "targetFps=$offscreenEncoderTargetFps " +
                        "readinessPtsNs=$offscreenEncoderReadinessPresentationTimeNs " +
                        "awaitingFirstLive=${state.awaitingFirstLiveFrame} " +
                        "lastSubmittedPtsNs=${state.lastSubmittedPresentationTimeNs}"
                )
            }
        }
        syncRawSceneLogTransportState()
    }

    /**
     * Wakes the MediaCodec input consumer without touching a camera/RAW frame. This is legal only
     * while live offscreen submissions are closed, so the readiness frame cannot race frame zero.
     */
    @Synchronized
    fun submitOffscreenAhbEglEncoderReadinessFrame(): Boolean {
        if (!_supported.value ||
            !offscreenAhbEglEncoderActive ||
            offscreenEncoderDetachQuarantined ||
            boundOffscreenEncoderSurface == null ||
            offscreenAhbEglSubmissionEnabled.get()
        ) return false
        val readinessPresentationTimeNs = System.nanoTime().coerceAtLeast(1L)
        return runCatching {
            offscreenAhbEglEncoderBridge.submitEncoderReadinessFrame(
                readinessPresentationTimeNs
            )
        }.onFailure { throwable ->
            Log.e(TAG, "Offscreen MediaCodec readiness frame submission failed", throwable)
        }.getOrDefault(false).also { submitted ->
            var pacingSnapshot: OffscreenEncoderFramePacingState
            synchronized(offscreenFramePacingLock) {
                if (submitted) {
                    offscreenEncoderReadinessPresentationTimeNs = readinessPresentationTimeNs
                    offscreenFramePacingState =
                        OffscreenEncoderFramePacingPolicy.continueAfterReadiness(
                            readinessPresentationTimeNs = readinessPresentationTimeNs,
                            targetFps = offscreenEncoderTargetFps
                        )
                } else {
                    offscreenEncoderReadinessPresentationTimeNs = 0L
                    offscreenFramePacingState =
                        OffscreenEncoderFramePacingPolicy.reset(offscreenEncoderTargetFps)
                }
                pacingSnapshot = offscreenFramePacingState
            }
            offscreenEncoderLiveSubmissionAttempts.set(0L)
            offscreenEncoderLiveSubmissionAccepted.set(0L)
            offscreenEncoderLiveSubmissionRejected.set(0L)
            runCatching {
                NeoLogger.i(
                    "VulkanEncoder",
                    "OFFSCREEN_ENCODER_READINESS submitted=$submitted " +
                        "readinessPtsNs=$readinessPresentationTimeNs " +
                        "targetFps=$offscreenEncoderTargetFps " +
                        "awaitingFirstLive=${pacingSnapshot.awaitingFirstLiveFrame} " +
                        "native=[${offscreenAhbEglEncoderBridge.summary()}]"
                )
            }
        }
    }

    /** Persists a bounded, exact encoder-transport snapshot for device checkpoint evidence. */
    fun persistOffscreenEncoderTransportEvidence(reason: String) {
        val pacing = synchronized(offscreenFramePacingLock) { offscreenFramePacingState }
        val prewarm = cameraP010SdrPrewarmTracker.snapshot()
        runCatching {
            NeoLogger.i(
                "VulkanEncoder",
                "OFFSCREEN_ENCODER_EVIDENCE reason=$reason " +
                    "attempts=${offscreenEncoderLiveSubmissionAttempts.get()} " +
                    "accepted=${offscreenEncoderLiveSubmissionAccepted.get()} " +
                    "rejected=${offscreenEncoderLiveSubmissionRejected.get()} " +
                    "gate=${offscreenAhbEglSubmissionEnabled.get()} " +
                    "targetFps=$offscreenEncoderTargetFps " +
                    "readinessPtsNs=$offscreenEncoderReadinessPresentationTimeNs " +
                    "awaitingFirstLive=${pacing.awaitingFirstLiveFrame} " +
                    "lastSubmittedPtsNs=${pacing.lastSubmittedPresentationTimeNs} " +
                    "p010PrewarmAttempts=${prewarm.attempts} " +
                    "p010PrewarmSuccesses=${prewarm.successes} " +
                    "p010PrewarmFailures=${prewarm.failures} " +
                    "p010PrewarmDistinctBuffers=${prewarm.distinctBufferIdentities} " +
                    "p010PrewarmComplete=${prewarm.complete} " +
                    "p010PrewarmBypass=${prewarm.bypassReason ?: "none"} " +
                    "p010PrewarmForeignFrames=${prewarm.ignoredForeignSourceFrames} " +
                    "p010PrewarmLastForeign=${prewarm.lastIgnoredForeignSourceReason ?: "none"} " +
                    "native=[${offscreenAhbEglEncoderBridge.summary()}]"
            )
        }
    }

    internal fun cameraP010SdrPrewarmSnapshot(): SdrCameraP010PrewarmSnapshot =
        cameraP010SdrPrewarmTracker.snapshot()

    /**
     * Warm-up is deliberately best-effort. Unsupported formats or driver failures preserve the
     * previous gate-closed consume path; they never trigger a lower-resolution/bit-depth fallback.
     */
    private fun prewarmGateClosedCameraP010Sdr(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        format: Int,
        dataSpace: Int,
        sensorTimestampNs: Long,
        uniforms: GpuImageEnhancementUniforms?
    ) {
        if (!cameraP010SdrPrewarmTracker.shouldAttempt()) return
        val surfaceStatus = _encoderSurfaceStatus.value
        if (surfaceStatus.deliveryMode != OffscreenEglDeliveryMode.SDR.nativeToken) {
            cameraP010SdrPrewarmTracker.markBypassed(
                "delivery-${surfaceStatus.deliveryMode.ifBlank { "unknown" }}"
            )
            return
        }
        if (format != ImageFormat.YCBCR_P010 || hardwareBuffer.format != format) {
            cameraP010SdrPrewarmTracker.recordIgnoredForeignSource(
                "source-format-image-$format-ahb-${hardwareBuffer.format}"
            )
            return
        }
        val observedAtNs = SystemClock.elapsedRealtimeNanos()
        val bufferIdentity = runCatching {
            offscreenAhbEglEncoderBridge.prewarmCameraP010Sdr(
                hardwareBuffer = hardwareBuffer,
                width = width,
                height = height,
                format = format,
                dataSpace = dataSpace,
                appliedProfile = offscreenInputDynamicRangeProfile,
                uniforms = uniforms,
                sensorTimestampNs = sensorTimestampNs
            )
        }.onFailure { throwable ->
            Log.w(TAG, "Gate-closed Camera2 P010 SDR prewarm failed", throwable)
        }.getOrDefault(0L)
        val snapshot = if (bufferIdentity > 0L) {
            cameraP010SdrPrewarmTracker.recordSuccess(
                bufferIdentity = bufferIdentity,
                sensorTimestampNs = sensorTimestampNs,
                observedAtElapsedRealtimeNs = observedAtNs
            )
        } else {
            cameraP010SdrPrewarmTracker.recordFailure(
                sensorTimestampNs = sensorTimestampNs,
                observedAtElapsedRealtimeNs = observedAtNs
            )
        }
        if (snapshot.complete || snapshot.failures == 1L || snapshot.bypassReason != null) {
            runCatching {
                NeoLogger.i(
                    "VulkanEncoder",
                    "CAMERA_P010_SDR_PREWARM_STATE attempts=${snapshot.attempts} " +
                        "successes=${snapshot.successes} failures=${snapshot.failures} " +
                        "distinctBuffers=${snapshot.distinctBufferIdentities} " +
                        "complete=${snapshot.complete} bypass=${snapshot.bypassReason ?: "none"} " +
                        "firstSensorTimestampNs=${snapshot.firstSensorTimestampNs} " +
                        "lastSensorTimestampNs=${snapshot.lastSensorTimestampNs}"
                )
            }
        }
    }

    /**
     * Selects the strict RAW -> linear ACEScg FP16 ingress for NDR/NIVUKX. While active, ordinary
     * Camera2 YUV/P010 HLG/PQ frames cannot be submitted into the NDR encoder path.
     */
    fun setSensorLinearNdrCaptureEnabled(
        enabled: Boolean,
        renderProfileGeneration: Long? = null
    ): Boolean = withCurrentRenderProfileGeneration(renderProfileGeneration, false) {
        sensorLinearNdrCaptureEnabled = enabled
        if (enabled) offscreenInputDynamicRangeProfile = null
        syncRawSceneLogTransportState()
        true
    }

    /**
     * Binds the Camera2 owner used to validate immutable RAW/NDR frame generations at final native
     * completion and encoder-publication boundaries. The runtime fails closed while no owner is bound.
     */
    internal fun setSensorLinearNdrSessionGenerationValidator(
        validator: ((Long) -> Boolean)?
    ) {
        sensorLinearNdrSessionGenerationValidator = validator
    }

    /**
     * Installs the secondary visible-monitor consumer for recorder-owned RAW completions.
     *
     * The listener runs only on the dedicated ordered preview worker after encoder acceptance and safe
     * acquisition of an independent preview-resolution transport. It never runs while
     * [rawDemosaicCompletionLock] is held. Its result is the authoritative preview-lineage acceptance.
     */
    internal fun setSensorLinearNdrRecordingPreviewListener(
        listener: ((SensorLinearNdrRecordingPreviewSubmission) -> Boolean)?
    ) {
        if (listener == null) sensorLinearNdrRecordingPreviewWorker.pause()
        sensorLinearNdrRecordingPreviewListener = listener
        if (listener != null && sensorLinearNdrPreviewActive &&
            sensorLinearNdrRecordingPreviewWorker.snapshot().state !=
                OrderedNdrPreviewTransportWorker.State.RUNNING
        ) {
            sensorLinearNdrRecordingPreviewWorker.resumeAfterDrain(clearFailure = false)
        }
    }

    private fun isSensorLinearNdrSessionGenerationCurrent(
        expectedSessionGeneration: Long
    ): Boolean {
        if (expectedSessionGeneration < 0L) return false
        val validator = sensorLinearNdrSessionGenerationValidator ?: return false
        return try {
            validator(expectedSessionGeneration)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            Log.e(TAG, "Camera2 generation validator failed for NDR frame", failure)
            false
        }
    }

    /**
     * Binds the dedicated 10-bit producer queue owned by [Hdr10PqPreviewCompositor]. The bridge imports
     * the latest RAW-derived FP16/Neo-Scene-Log AHardwareBuffer, executes the canonical project NDR
     * operator, and publishes project-owned PQ code values through an internal, untagged texture
     * queue. The visible compositor is the sole transfer decoder; the platform must not reinterpret
     * this intermediate queue as an HDR display layer. It never accepts Camera2 HLG/PQ as source.
     */
    fun prepareSensorLinearNdrPreviewSurface(
        surface: Surface,
        width: Int,
        height: Int,
        peakPlan: NdrPeakCapabilityPolicy.Plan
    ): Boolean {
        if (!_supported.value || width <= 0 || height <= 0) return false
        val frameLease = ndrNativeFrameBoundary.acquireFrameLease() ?: return false
        return try {
            synchronized(renderProfileGenerationLock) {
                val bundle = currentNdrRenderBundleSnapshot()
                if (bundle == null ||
                    appliedNdrNivukxProfileGeneration != bundle.profileGeneration ||
                    appliedNdrNivukxMeasuredCorrection != bundle.correction
                ) {
                    Log.w(
                        TAG,
                        "Strict RAW-derived NDR preview retained outgoing presentation: " +
                            "no coherent committed packet generation=$acceptedRenderProfileGeneration"
                    )
                    return false
                }
                val profileGeneration = bundle.profileGeneration

                // Lock order is frame boundary -> profile generation -> sensor preview. A packet
                // writer therefore cannot cross surface correction/binding or expose an old profile.
                synchronized(sensorLinearNdrPreviewLock) {
                    sensorLinearNdrPreviewBridge.setNdrPeakPlan(peakPlan)
                    if (!sensorLinearNdrPreviewBridge.setNdrNivukxMeasuredCorrection(
                            bundle.correction,
                            profileGeneration,
                            bundle.liveProfile
                        )
                    ) return false
                    // prepare() is invoked for each committed RAW-preview epoch, including epochs that reuse
                    // the same persistent producer Surface. Clear only temporal history so pixels from an old
                    // sensor/session/WB lineage cannot seed the first project-owned NDR frames of this epoch.
                    // The bridge keeps its EGL surface, textures, measured correction, and proprietary grade.
                    if (!sensorLinearNdrPreviewBridge.resetTemporalHistory()) {
                        Log.e(TAG, "Strict RAW-derived NDR preview temporal-history reset failed")
                        return false
                    }
                    if (boundSensorLinearNdrPreviewSurface === surface && sensorLinearNdrPreviewActive) {
                        if (!sensorLinearNdrRecordingPreviewWorker.resumeAfterDrain(clearFailure = true)) {
                            Log.e(TAG, "Strict NDR preview worker did not begin a clean presentation epoch")
                            return false
                        }
                        syncRawSceneLogTransportState()
                        return true
                    }
                    val bound = sensorLinearNdrPreviewBridge.setEncoderSurface(
                        surface = surface,
                        width = width,
                        height = height,
                        preferredDeliveryMode = OffscreenEglDeliveryMode.PROJECT_PQ_TEXTURE,
                        outputMirrorPlan = RecordOutputMirrorPlan(
                            flipX = false,
                            // SurfaceTexture contributes the display transform after this RGB10_A2 producer.
                            // Flip producer T exactly once so the RAW frame matches the retained Camera2 frame;
                            // rotation/front-camera mirroring remain owned by the visible compositor.
                            flipY = true,
                            source = "raw-preview-surface-texture-y-parity"
                        )
                    )
                    if (bound) {
                        boundSensorLinearNdrPreviewSurface = surface
                        sensorLinearNdrPreviewActive = true
                        if (!sensorLinearNdrRecordingPreviewWorker.resumeAfterDrain(clearFailure = true)) {
                            sensorLinearNdrPreviewBridge.setEncoderSurface(null, 0, 0)
                            boundSensorLinearNdrPreviewSurface = null
                            sensorLinearNdrPreviewActive = false
                            syncRawSceneLogTransportState()
                            Log.e(TAG, "Strict NDR preview worker rejected a fresh surface epoch")
                            return false
                        }
                        Log.i(
                            TAG,
                            "Strict RAW-derived NDR preview bridge prepared ${width}x$height " +
                                sensorLinearNdrPreviewBridge.summary()
                        )
                    } else {
                        boundSensorLinearNdrPreviewSurface = null
                        sensorLinearNdrPreviewActive = false
                        Log.e(TAG, "Strict RAW-derived NDR preview bridge rejected its PQ producer surface")
                    }
                    syncRawSceneLogTransportState()
                    bound
                }
            }
        } finally {
            frameLease.close()
        }
    }

    fun submitLatestSensorLinearNdrPreviewFrame(
        width: Int,
        height: Int,
        sensorTimestampNs: Long,
        cameraSessionGeneration: Long
    ): Boolean {
        val ndrFrameLease = ndrNativeFrameBoundary.acquireFrameLease() ?: return false
        try {
        if (!_supported.value || width <= 0 || height <= 0 || sensorTimestampNs <= 0L) return false
        // Once recorder ownership begins, an idle callback from the outgoing preview epoch must not
        // enter the shared EGL context or contend with the dedicated recording-preview worker.
        if (sensorLinearNdrDemosaicOwnerPhase != SensorLinearNdrDemosaicOwnerPhase.PREVIEW) return false
        if (!isSensorLinearNdrSessionGenerationCurrent(cameraSessionGeneration)) return false
        val renderBundle = currentNdrRenderBundleSnapshot() ?: return false
        synchronized(sensorLinearNdrPreviewLock) {
            if (sensorLinearNdrDemosaicOwnerPhase != SensorLinearNdrDemosaicOwnerPhase.PREVIEW) {
                return false
            }
            if (!sensorLinearNdrPreviewActive || boundSensorLinearNdrPreviewSurface == null) return false
            if (currentNdrRenderBundleSnapshot()?.identity != renderBundle.identity) {
                return false
            }
            val output = bridge.acquireRawDemosaicOutputHardwareBuffer() ?: return false
            return try {
                // Idle direct NDR may use a CFA-preserving bounded demosaic extent. EGL sampling math
                // must receive the exported buffer's real dimensions, never the sensor-frame identity.
                val outputWidth = output.width
                val outputHeight = output.height
                if (outputWidth <= 0 || outputHeight <= 0) return false
                if (!isSensorLinearNdrSessionGenerationCurrent(cameraSessionGeneration)) return false
                val submitted = when (output.format) {
                    HardwareBuffer.RGBA_FP16 ->
                        sensorLinearNdrPreviewBridge.submitSensorLinearAcesCg(
                            hardwareBuffer = output,
                            width = outputWidth,
                            height = outputHeight,
                            presentationTimeNs = sensorTimestampNs,
                            uniforms = renderBundle.enhancementUniforms
                        )
                    HardwareBuffer.RGBA_1010102 ->
                        sensorLinearNdrPreviewBridge.submitSceneLog10AcesCg(
                            hardwareBuffer = output,
                            width = outputWidth,
                            height = outputHeight,
                            presentationTimeNs = sensorTimestampNs,
                            uniforms = renderBundle.enhancementUniforms
                        )
                    else -> false
                }
                submitted && isSensorLinearNdrSessionGenerationCurrent(cameraSessionGeneration)
            } finally {
                output.close()
            }
        }
        } finally {
            ndrFrameLease.close()
        }
    }

    /**
     * Consumes an independently downsampled recording-preview lease. Ownership stays with the ordered
     * worker; this method performs only the synchronous EGL submission/glFinish barrier and never closes
     * or acknowledges [SensorLinearNdrRecordingPreviewSubmission.output].
     */
    internal fun submitSensorLinearNdrRecordingPreviewFrame(
        submission: SensorLinearNdrRecordingPreviewSubmission
    ): Boolean {
        val ndrFrameLease = ndrNativeFrameBoundary.acquireFrameLease() ?: return false
        try {
        val frame = submission.frame
        if (!_supported.value || frame.sensorTimestampNs <= 0L) return false
        if (sensorLinearNdrDemosaicOwnerPhase != SensorLinearNdrDemosaicOwnerPhase.RECORDING) return false
        if (!isSensorLinearNdrSessionGenerationCurrent(frame.cameraSessionGeneration)) return false
        val renderBundle = currentNdrRenderBundleSnapshot() ?: return false
        if (renderBundle.identity != submission.renderProfileIdentity ||
            renderBundle.enhancementUniforms.signature != submission.uniforms.signature
        ) return false

        synchronized(sensorLinearNdrPreviewLock) {
            if (!sensorLinearNdrPreviewActive || boundSensorLinearNdrPreviewSurface == null) return false
            if (sensorLinearNdrDemosaicOwnerPhase != SensorLinearNdrDemosaicOwnerPhase.RECORDING ||
                currentNdrRenderBundleSnapshot()?.identity != renderBundle.identity ||
                !isSensorLinearNdrSessionGenerationCurrent(frame.cameraSessionGeneration)
            ) return false

            val output = submission.output.hardwareBuffer
            val outputWidth = output.width
            val outputHeight = output.height
            if (outputWidth <= 0 || outputHeight <= 0) return false
            val submitted = when (output.format) {
                HardwareBuffer.RGBA_FP16 ->
                    sensorLinearNdrPreviewBridge.submitSensorLinearAcesCg(
                        hardwareBuffer = output,
                        width = outputWidth,
                        height = outputHeight,
                        presentationTimeNs = frame.sensorTimestampNs,
                        uniforms = submission.uniforms
                    )
                HardwareBuffer.RGBA_1010102 ->
                    sensorLinearNdrPreviewBridge.submitSceneLog10AcesCg(
                        hardwareBuffer = output,
                        width = outputWidth,
                        height = outputHeight,
                        presentationTimeNs = frame.sensorTimestampNs,
                        uniforms = submission.uniforms
                    )
                else -> false
            }
            return submitted &&
                isSensorLinearNdrSessionGenerationCurrent(frame.cameraSessionGeneration) &&
                currentNdrRenderBundleSnapshot()?.identity == renderBundle.identity
        }
        } finally {
            ndrFrameLease.close()
        }
    }

    /**
     * Stops preview admission and waits outside the EGL monitor until every accepted token has reached
     * glFinish and exact acknowledgement. Call this before lineage freeze or any preview/native teardown.
     */
    internal fun drainSensorLinearNdrRecordingPreview(
        timeoutMs: Long = NDR_PREVIEW_DRAIN_TIMEOUT_MS,
        requireHealthy: Boolean = true
    ): Boolean {
        if (!sensorLinearNdrRecordingPreviewWorker.pauseAndDrain(timeoutMs)) return false
        val worker = sensorLinearNdrRecordingPreviewWorker.snapshot()
        if (worker.outstandingTasks != 0) return false
        if (requireHealthy && worker.lastFailure != null) return false
        if (!bridge.quiesceRawDemosaicPreviewOutputs()) return false
        val native = bridge.rawDemosaicPreviewTransportStats()
        return native.outstanding == 0L
    }

    /** Reopens admission only after a successful drain and a still-bound project-owned surface. */
    internal fun resumeSensorLinearNdrPreviewAfterDrain(): Boolean {
        if (!sensorLinearNdrPreviewActive || boundSensorLinearNdrPreviewSurface == null) return false
        return sensorLinearNdrRecordingPreviewWorker.resumeAfterDrain(clearFailure = true)
    }

    internal fun sensorLinearNdrRecordingPreviewWorkerSnapshot():
        OrderedNdrPreviewTransportWorker.Snapshot =
        sensorLinearNdrRecordingPreviewWorker.snapshot()

    fun releaseSensorLinearNdrPreviewSurface(): Boolean {
        // Never wait while holding sensorLinearNdrPreviewLock: worker tasks need that same monitor to
        // return from EGL and release their token.
        if (!drainSensorLinearNdrRecordingPreview(
                timeoutMs = NDR_PREVIEW_DRAIN_TIMEOUT_MS,
                requireHealthy = false
            )
        ) {
            Log.e(TAG, "Strict NDR preview worker did not drain; surface retained")
            return false
        }
        // Quiesce already proves zero ownership. A second lifecycle discard must therefore be a no-op;
        // any other result is an ownership-accounting defect and keeps the surface quarantined.
        val discarded = bridge.discardCompletedRawDemosaicPreviewOutputs()
        val previewStats = bridge.rawDemosaicPreviewTransportStats()
        if (discarded != 0 || previewStats.outstanding != 0L) {
            Log.e(
                TAG,
                "Strict NDR preview transport did not retire discarded=$discarded stats=$previewStats"
            )
            return false
        }
        return synchronized(sensorLinearNdrPreviewLock) {
            val nativeDetached = if (sensorLinearNdrPreviewBridgeDelegate.isInitialized()) {
                try {
                    sensorLinearNdrPreviewBridge.setEncoderSurface(null, 0, 0)
                } catch (failure: Throwable) {
                    Log.e(TAG, "Failed to release strict NDR preview bridge", failure)
                    false
                }
            } else {
                true
            }
            if (!nativeDetached) {
                // Native may still own ANativeWindow/EGLSurface. Retain Kotlin state and the strong
                // Surface reference so lifecycle teardown can quarantine the exact ownership bundle.
                Log.e(TAG, "Strict NDR preview detach was not acknowledged; surface quarantined")
                return@synchronized false
            }
            boundSensorLinearNdrPreviewSurface = null
            sensorLinearNdrPreviewActive = false
            syncRawSceneLogTransportState()
            true
        }
    }

    /** Keep idle preview bounded while pre-creating the full-resolution fallback for a proven surface. */
    private fun syncRawSceneLogTransportState() {
        val prepared = sensorLinearNdrCaptureEnabled &&
            ((offscreenAhbEglEncoderActive && boundOffscreenEncoderSurface != null) ||
                sensorLinearNdrPreviewActive)
        val submission = prepared &&
            (offscreenAhbEglSubmissionEnabled.get() || sensorLinearNdrPreviewActive)
        val directPreview = prepared && sensorLinearNdrPreviewActive
        // This ownership phase, not the combined preview/encoder transport-submission gate, is the
        // authority for allocating and processing a full-sensor RAW recording master.
        val recordingActive =
            sensorLinearNdrDemosaicOwnerPhase == SensorLinearNdrDemosaicOwnerPhase.RECORDING
        synchronized(rawSceneLogTransportStateLock) {
            if (prepared == appliedRawSceneLogPrepared &&
                submission == appliedRawSceneLogSubmission &&
                directPreview == appliedRawDirectNdrPreviewActive &&
                recordingActive == appliedRawRecordingDemosaicActive) return
            bridge.setRawSceneLogTransportState(
                prepared,
                submission,
                directPreview,
                recordingActive
            )
            appliedRawSceneLogPrepared = prepared
            appliedRawSceneLogSubmission = submission
            appliedRawDirectNdrPreviewActive = directPreview
            appliedRawRecordingDemosaicActive = recordingActive
        }
    }

    private fun resetOffscreenFramePacing() {
        synchronized(offscreenFramePacingLock) {
            offscreenFramePacingState =
                OffscreenEncoderFramePacingPolicy.reset(offscreenEncoderTargetFps)
        }
    }

    fun beginCameraImportSession(sessionGeneration: Long): Boolean =
        cameraImportSessionGate.beginSession(sessionGeneration)

    internal fun lastCameraImageSubmissionOutcome(): CameraImageSubmissionOutcome =
        checkNotNull(cameraImageSubmissionOutcome.get()) {
            "Camera image submission outcome ThreadLocal failed to initialize"
        }

    fun beginPreviewImportSession(preserveActiveOffscreenImport: Boolean = false) =
        cameraImportSessionGate.withExclusiveMutation {
        val now = SystemClock.elapsedRealtime()
        val offscreenBound = offscreenAhbEglEncoderActive && boundOffscreenEncoderSurface != null
        if (preserveActiveOffscreenImport && offscreenBound) {
            Log.i(
                TAG,
                "beginPreviewImportSession: preserving pre-armed offscreen AHB EGL import state"
            )
        } else {
            // ARCHITECTURAL FIX: Explicit cleanup of VkImage/VkDeviceMemory to prevent VRAM leaks
            bridge.cleanupImportedFrames()
        }
        configDirty = true
        enhancementDirty = enhancementUniforms != null
        lastEnhancementSignature = ""
        lastRenderConfig = VulkanRenderConfig()
        framesSinceConfigUpdate = 0
        lastOffscreenImportStatsPublishElapsedMs = 0L
        val resetStats = importStatsAccumulator.resetSession(now).stats
        _importStats.value = if (offscreenBound) {
            importStatsAccumulator
                .mutate {
                    it.copy(
                        offscreenAhbEglEncoderActive = true,
                        offscreenEglEncoderSurfaceBound = true,
                        encoderSurfaceBound = true
                    )
                }
                .stats
        } else {
            resetStats
        }
        }

    private fun hasOffscreenEncoderSurfaceOwnershipWitness(): Boolean =
        offscreenAhbEglEncoderActive ||
            offscreenEncoderDetachQuarantined ||
            boundOffscreenEncoderSurface != null ||
            importStatsAccumulator.current.offscreenAhbEglEncoderActive ||
            importStatsAccumulator.current.offscreenEglEncoderSurfaceBound

    /** True while Kotlin or native EGL may still own the offscreen codec input surface. */
    @Synchronized
    internal fun hasOffscreenAhbEglEncoderOwnership(): Boolean =
        hasOffscreenEncoderSurfaceOwnershipWitness() ||
            (_encoderSurfaceStatus.value.bound && !hasInternalEncoderSurfaceOwnership())

    /** Atomically observes and retires the offscreen EGL encoder owner under one runtime monitor. */
    @Synchronized
    internal fun detachOffscreenAhbEglEncoderSurfaceWithProof(): Boolean {
        if (!hasOffscreenAhbEglEncoderOwnership()) return true
        return setOffscreenAhbEglEncoderSurface(null, 0, 0) &&
            !hasOffscreenAhbEglEncoderOwnership()
    }

    /**
     * Retires both independent GPU consumers as one transaction. This prevents a route transition
     * from making one bridge look irrelevant while it still owns a Surface from the previous route.
     */
    @Synchronized
    internal fun detachAllEncoderSurfacesWithProof(): Boolean {
        val coreDetached = detachInternalEncoderSurfaceWithProof()
        val offscreenDetached = detachOffscreenAhbEglEncoderSurfaceWithProof()
        return coreDetached && offscreenDetached &&
            !hasInternalEncoderSurfaceOwnership() &&
            !hasOffscreenAhbEglEncoderOwnership()
    }

    @Synchronized
    fun setOffscreenAhbEglEncoderSurface(
        surface: Surface?,
        width: Int,
        height: Int,
        preferredDeliveryMode: OffscreenEglDeliveryMode = OffscreenEglDeliveryMode.SDR,
        outputMirrorPlan: RecordOutputMirrorPlan? = null,
        inputDynamicRangeProfile: Long? = null,
        ndrPeakPlan: NdrPeakCapabilityPolicy.Plan? = null
    ): Boolean {
        if (!_supported.value) {
            runCatching {
                NeoLogger.critical(
                    "VulkanEncoder",
                    "Offscreen AHB EGL 10-bit encoder unavailable: Vulkan runtime not supported (_supported=false) " +
                        "req=${width}x$height"
                )
            }
            return false
        }
        if (surface != null && hasInternalEncoderSurfaceOwnership()) {
            // EGL_BAD_ALLOC is also reported when another API owns the MediaCodec BufferQueue.
            // Reject before JNI so callers can proof-detach and retry without poisoning this route
            // as a permanent GPU hardware wall.
            offscreenEncoderHardwareWallProven = false
            offscreenEncoderProvenUnavailable = false
            Log.w(
                TAG,
                "Offscreen encoder bind rejected: codec Surface still has core Vulkan ownership"
            )
            return false
        }
        if (surface == null) {
            cameraP010SdrPrewarmTracker.reset()
            resetOffscreenFramePacing()
            synchronized(offscreenFramePacingLock) {
                offscreenEncoderReadinessPresentationTimeNs = 0L
            }
            lastOffscreenImportStatsPublishElapsedMs = 0L
            offscreenAhbEglSubmissionEnabled.set(false)
            val encoderInputReadQuiesced = synchronized(rawDemosaicCompletionLock) {
                when (retireSensorLinearNdrEncoderInputReadsLocked(
                    NDR_ENCODER_INPUT_READ_DRAIN_TIMEOUT_NS
                )) {
                    EncoderInputReadRetirement.NONE,
                    EncoderInputReadRetirement.RETIRED -> true
                    EncoderInputReadRetirement.PENDING,
                    EncoderInputReadRetirement.FAILED -> false
                }
            }
            if (!encoderInputReadQuiesced) {
                offscreenEncoderDetachQuarantined = true
                Log.e(
                    TAG,
                    "Offscreen encoder detach blocked by an unretired project-owned input read"
                )
                return false
            }
            val nativeDetached = try {
                // Always ask native to detach, even when Kotlin already appears unbound. A previous
                // partial bind may have reached ANativeWindow/EGL before its JNI call rejected or threw,
                // and only the bridge can prove that it no longer retains that Surface.
                offscreenAhbEglEncoderBridge.setEncoderSurface(null, 0, 0)
            } catch (throwable: Throwable) {
                Log.w(TAG, "Failed to clear offscreen AHB EGL encoder surface", throwable)
                false
            }
            if (!nativeDetached) {
                // Fail closed. Stop submissions, but retain every bound-state signal and the Surface
                // reference: native may still own its ANativeWindow/EGLSurface. The quarantine bit also
                // prevents RAW demosaic ownership from being released or submission from being rearmed.
                offscreenEncoderDetachQuarantined = true
                if (!_encoderSurfaceStatus.value.bound) {
                    _encoderSurfaceStatus.value = VulkanEncoderSurfaceStatus.UNBOUND.copy(bound = true)
                }
                _importStats.value = importStatsAccumulator
                    .mutate {
                        it.copy(
                            offscreenEglEncoderSurfaceBound = true,
                            encoderSurfaceBound = true
                        )
                    }
                    .stats
                Log.e(
                    TAG,
                    "Offscreen AHB EGL encoder detach was not acknowledged; surface quarantined"
                )
                return false
            }
            offscreenEncoderDetachQuarantined = false
            boundOffscreenEncoderSurface = null
            offscreenInputDynamicRangeProfile = null
            offscreenAhbEglEncoderActive = false
            runCatching { refreshCapabilitySummary() }
                .onFailure { Log.w(TAG, "Failed to refresh capabilities after encoder detach", it) }
            _encoderSurfaceStatus.value = VulkanEncoderSurfaceStatus.UNBOUND
            _importStats.value = importStatsAccumulator
                .mutate {
                    it.copy(
                        offscreenAhbEglEncoderActive = false,
                        offscreenEglEncoderSurfaceBound = false,
                        encoderSurfaceBound = false
                    )
                }
                .stats
            return true
        }
        offscreenAhbEglEncoderBridge.setNdrPeakPlan(ndrPeakPlan)
        if (boundOffscreenEncoderSurface === surface && offscreenAhbEglEncoderActive &&
            !offscreenEncoderDetachQuarantined
        ) {
            offscreenInputDynamicRangeProfile = inputDynamicRangeProfile
            outputMirrorPlan?.let { offscreenAhbEglEncoderBridge.setEncoderOutputFlip(it) }
            lastOffscreenImportStatsPublishElapsedMs = 0L
            refreshCapabilitySummary()
            val resolvedStatus = resolveOffscreenEncoderSurfaceStatus(
                surfaceRequested = true,
                bindSucceeded = true,
                reportedStatus = _encoderSurfaceStatus.value,
                expectedDeliveryMode = preferredDeliveryMode.nativeToken
            )
            if (!resolvedStatus.surfacePrecisionProven ||
                resolvedStatus.bitDepth < 10 ||
                !(resolvedStatus.deliveryMode == "SDR" ||
                    resolvedStatus.deliveryMode == "HLG" ||
                    resolvedStatus.deliveryMode == "PQ")
            ) {
                setOffscreenAhbEglEncoderSurface(null, 0, 0)
                return false
            }
            _encoderSurfaceStatus.value = resolvedStatus
            _importStats.value = importStatsAccumulator
                .mutate {
                    it.copy(
                        offscreenAhbEglEncoderActive = true,
                        offscreenEglEncoderSurfaceBound = true,
                        encoderSurfaceBound = true
                    )
                }
                .stats
            runCatching {
                NeoLogger.i(
                    "VulkanEncoder",
                    "Offscreen AHB EGL 10-bit encoder REUSED req=${width}x$height " +
                        "delivery=${resolvedStatus.deliveryMode} bitDepth=${resolvedStatus.bitDepth} " +
                        "mirror=${outputMirrorPlan?.source ?: "default"} " +
                        "flipX=${outputMirrorPlan?.flipX ?: RecordOutputMirrorFlag.flipX} " +
                        "flipY=${outputMirrorPlan?.flipY ?: RecordOutputMirrorFlag.flipY}"
                )
            }
            return true
        }
        return runCatching {
            offscreenInputDynamicRangeProfile = inputDynamicRangeProfile
            val nativeBound = offscreenAhbEglEncoderBridge.setEncoderSurface(
                surface,
                width,
                height,
                preferredDeliveryMode,
                outputMirrorPlan ?: RecordOutputMirrorFlag.currentManualPlan()
            )
            refreshCapabilitySummary()
            val resolvedStatus = resolveOffscreenEncoderSurfaceStatus(
                surfaceRequested = true,
                bindSucceeded = nativeBound,
                reportedStatus = _encoderSurfaceStatus.value,
                expectedDeliveryMode = preferredDeliveryMode.nativeToken
            )
            val proven = resolvedStatus.surfacePrecisionProven &&
                resolvedStatus.bitDepth >= 10 &&
                (resolvedStatus.deliveryMode == "SDR" ||
                    resolvedStatus.deliveryMode == "HLG" ||
                    resolvedStatus.deliveryMode == "PQ")
            if (!proven) {
                // Surface the RAW native EGL reason (status=...) — normally only in the suppressed
                // android.util.Log — so we know EXACTLY why the GPU 10-bit bind failed.
                val nativeSummary = runCatching { offscreenAhbEglEncoderBridge.summary() }
                    .getOrDefault("unavailable")
                // A GENUINE hardware wall = the GPU driver has no 10-bit recordable EGL config, or the
                // codec input surface refused every colorspace. Only these are worth persisting across
                // launches; a transient/token miss must stay re-probable so a later fix (or a warm GPU)
                // can prove the path instead of it being permanently trusted as dead.
                offscreenEncoderHardwareWallProven =
                    offscreenSummaryIndicatesHardwareWall(nativeSummary)
                // Only a real hardware wall should poison the process and steer away from the GPU/offscreen
                // route. Transient start timing or token failures remain re-probable, avoiding a silent
                // drift into the CPU P010 path.
                offscreenEncoderProvenUnavailable = offscreenEncoderHardwareWallProven
                runCatching {
                    NeoLogger.critical(
                        "VulkanEncoder",
                        "Offscreen AHB EGL 10-bit encoder NOT PROVEN req=${width}x$height nativeBound=$nativeBound " +
                            "surfacePrecisionProven=${resolvedStatus.surfacePrecisionProven} " +
                            "bitDepth=${resolvedStatus.bitDepth}(need>=10) " +
                            "deliveryMode=${resolvedStatus.deliveryMode}(need=SDR|HLG|PQ) " +
                            "hardwareWall=$offscreenEncoderHardwareWallProven " +
                            "nativeSummary=[$nativeSummary] " +
                            "fullStatus=$resolvedStatus"
                    )
                }
                if (!setOffscreenAhbEglEncoderSurface(null, 0, 0)) {
                    if (boundOffscreenEncoderSurface == null) {
                        boundOffscreenEncoderSurface = surface
                    }
                    Log.e(
                        TAG,
                        "Rejected offscreen encoder surface could not be detached; retaining quarantine"
                    )
                }
            } else {
                offscreenEncoderProvenUnavailable = false
                offscreenEncoderHardwareWallProven = false
                offscreenEncoderDetachQuarantined = false
                boundOffscreenEncoderSurface = surface
                offscreenAhbEglEncoderActive = true
                // This is a genuinely new native EGL/MediaCodec surface owner. Cached shader and
                // AHardwareBuffer proof from any previous surface cannot authorize this one.
                cameraP010SdrPrewarmTracker.reset()
                offscreenAhbEglSubmissionEnabled.set(false)
                lastOffscreenImportStatsPublishElapsedMs = 0L
                synchronized(offscreenFramePacingLock) {
                    offscreenEncoderReadinessPresentationTimeNs = 0L
                }
                resetOffscreenFramePacing()
                _encoderSurfaceStatus.value = resolvedStatus
                runCatching {
                    NeoLogger.i(
                        "VulkanEncoder",
                        "Offscreen AHB EGL 10-bit encoder PROVEN req=${width}x$height " +
                            "delivery=${resolvedStatus.deliveryMode} bitDepth=${resolvedStatus.bitDepth} " +
                            "mirror=${outputMirrorPlan?.source ?: "default"} " +
                            "flipX=${outputMirrorPlan?.flipX ?: RecordOutputMirrorFlag.flipX} " +
                            "flipY=${outputMirrorPlan?.flipY ?: RecordOutputMirrorFlag.flipY}"
                    )
                }
            }
            _importStats.value = importStatsAccumulator
                .mutate { current ->
                    val boundOrQuarantined = proven || offscreenEncoderDetachQuarantined
                    current.copy(
                        offscreenAhbEglEncoderActive = boundOrQuarantined,
                        offscreenEglEncoderSurfaceBound = boundOrQuarantined,
                        encoderSurfaceBound = boundOrQuarantined
                    )
                }
                .stats
            proven
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to bind offscreen AHB EGL encoder surface", throwable)
            if (!setOffscreenAhbEglEncoderSurface(null, 0, 0)) {
                if (boundOffscreenEncoderSurface == null) {
                    boundOffscreenEncoderSurface = surface
                }
                Log.e(
                    TAG,
                    "Offscreen encoder bind failure cleanup could not detach native surface; " +
                        "retaining quarantine"
                )
                _importStats.value = importStatsAccumulator
                    .mutate {
                        it.copy(
                            offscreenAhbEglEncoderActive = true,
                            offscreenEglEncoderSurfaceBound = true,
                            encoderSurfaceBound = true
                        )
                    }
                    .stats
            }
        }.getOrDefault(false)
    }

    fun setPreviewSurface(surface: Surface?): Boolean {
        if (!_supported.value) return false
        // A null request is an ownership transition and must always cross JNI. Treating two local nulls
        // as "already detached" cannot prove that native swapchain/window ownership was released.
        if (surface != null && boundPreviewSurface === surface) {
            _importStats.value = importStatsAccumulator
                .mutate { it.copy(previewSurfaceBound = true) }
                .stats
            return true
        }
        val previouslyBoundSurface = boundPreviewSurface
        runCatching {
            com.neoproc.neoproccamx.utils.BootTrace.mark("vk.setPreviewSurface.start")
        }
        val nativeAcknowledged = try {
            bridge.setPreviewSurface(surface)
        } catch (throwable: Throwable) {
            if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
            Log.w(TAG, "Failed to set preview surface for Vulkan runtime", throwable)
            false
        } finally {
            runCatching {
                com.neoproc.neoproccamx.utils.BootTrace.mark("vk.setPreviewSurface.end")
            }
        }
        if (!nativeAcknowledged) {
            // Retain the last Java ownership witness. Clearing it on a rejected/throwing JNI call makes
            // later teardown believe the native window was detached and can release a still-live Surface.
            boundPreviewSurface = previouslyBoundSurface
            _importStats.value = importStatsAccumulator
                .mutate { it.copy(previewSurfaceBound = previouslyBoundSurface != null) }
                .stats
            return false
        }

        boundPreviewSurface = surface
        configDirty = true
        try {
            refreshCapabilitySummary()
        } catch (throwable: Throwable) {
            if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
            Log.w(TAG, "Preview surface changed but capability refresh failed", throwable)
        }
        _importStats.value = importStatsAccumulator
            .mutate { it.copy(previewSurfaceBound = surface != null) }
            .stats
        return true
    }

    /**
     * Detaches a failed strict native route only while the exact staged or applied render identity
     * still owns mutation authority. A superseded Main-thread fallback can therefore never tear down
     * a newer same-generation revision that has already proved its surface.
     */
    internal fun detachPreviewSurfaceForRenderProfileIdentity(
        identity: NdrRenderProfileIdentity
    ): Boolean = ndrNativeFrameBoundary.withMutationLease {
        synchronized(renderProfileGenerationLock) {
            val stagedIdentity = stagedNdrRenderBundle.snapshot()?.identity
            val appliedIdentity = appliedNdrRenderBundle.snapshot()?.identity
            if (closeRequested.get() ||
                identity.profileGeneration != acceptedRenderProfileGeneration ||
                identity.configRevision != acceptedRenderProfileRevision ||
                (stagedIdentity != identity && appliedIdentity != identity)
            ) {
                return@synchronized false
            }
            ndrNativeFrameBoundary.closeIngressForMutation()
            val detachAcknowledged = setPreviewSurface(null)
            detachAcknowledged && boundPreviewSurface == null
        }
    }

    /** True while Kotlin or the core Vulkan bridge may still own the MediaCodec input Surface. */
    @Synchronized
    internal fun hasInternalEncoderSurfaceOwnership(): Boolean =
        boundEncoderSurface != null ||
            quarantinedInternalEncoderSurface != null ||
            internalEncoderDetachQuarantined

    /** Atomically observes and retires the core encoder owner under the same runtime monitor. */
    @Synchronized
    internal fun detachInternalEncoderSurfaceWithProof(): Boolean {
        if (!hasInternalEncoderSurfaceOwnership()) return true
        return setEncoderSurface(null) && !hasInternalEncoderSurfaceOwnership()
    }

    /**
     * Applies one core-Vulkan encoder Surface ownership transition.
     *
     * A null request always crosses JNI when the runtime is available. A rejected or throwing JNI
     * transition is not proof of native destruction, so the previous Java ownership witness, status,
     * and telemetry remain intact until a later acknowledged detach. This mirrors preview-surface
     * ownership and prevents callers from releasing a codec Surface still reachable from Vulkan.
     */
    @Synchronized
    fun setEncoderSurface(surface: Surface?): Boolean {
        if (!_supported.value) return false
        if (surface != null && hasOffscreenEncoderSurfaceOwnershipWitness()) {
            Log.w(
                TAG,
                "Core Vulkan encoder bind rejected: codec Surface still has offscreen EGL ownership"
            )
            return false
        }
        if (surface != null && boundEncoderSurface === surface) {
            val previousStatus = _encoderSurfaceStatus.value
            val refreshed = try {
                refreshCapabilitySummary()
                true
            } catch (throwable: Throwable) {
                if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
                Log.w(TAG, "Failed to refresh existing encoder-surface status", throwable)
                false
            }
            val reportedStatus = _encoderSurfaceStatus.value
            val stillBound = refreshed && reportedStatus.bound
            if (!stillBound) _encoderSurfaceStatus.value = previousStatus
            _importStats.value = importStatsAccumulator
                .mutate { it.copy(encoderSurfaceBound = true) }
                .stats
            return stillBound
        }

        val previousSurface = boundEncoderSurface
        val previousStatus = _encoderSurfaceStatus.value
        val previousStatsBound = importStatsAccumulator.current.encoderSurfaceBound
        val nativeAcknowledged = try {
            bridge.setEncoderSurface(surface)
        } catch (throwable: Throwable) {
            if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
            Log.w(TAG, "Failed to set encoder surface for Vulkan runtime", throwable)
            false
        }
        if (!nativeAcknowledged) {
            // The bridge did not acknowledge the requested transition. Keep the exact previous
            // ownership publication so codec destruction cannot race a still-live ANativeWindow.
            boundEncoderSurface = previousSurface
            if (surface != null) quarantinedInternalEncoderSurface = surface
            internalEncoderDetachQuarantined =
                previousSurface != null ||
                    quarantinedInternalEncoderSurface != null ||
                    internalEncoderDetachQuarantined
            _encoderSurfaceStatus.value = previousStatus
            _importStats.value = importStatsAccumulator
                .mutate { it.copy(encoderSurfaceBound = previousStatsBound) }
                .stats
            return false
        }

        boundEncoderSurface = surface
        quarantinedInternalEncoderSurface = null
        internalEncoderDetachQuarantined = false
        configDirty = true
        val statusRefreshed = try {
            refreshCapabilitySummary()
            true
        } catch (throwable: Throwable) {
            if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
            Log.w(TAG, "Encoder surface changed but capability refresh failed", throwable)
            false
        }
        _encoderSurfaceStatus.value = when {
            surface == null -> VulkanEncoderSurfaceStatus.UNBOUND
            statusRefreshed -> resolveEncoderSurfaceStatus(
                surfaceRequested = true,
                bindSucceeded = true,
                reportedStatus = _encoderSurfaceStatus.value
            )
            else -> VulkanEncoderSurfaceStatus.UNBOUND.copy(bound = true)
        }
        _importStats.value = importStatsAccumulator
            .mutate { it.copy(encoderSurfaceBound = surface != null) }
            .stats
        return true
    }

    /**
     * s3-f04 / CP91 — push the adaptive governor's OmniClarity motion-stack tuning into the live native
     * dispatch. Resolves the three stage policies via [OmniClarityMotionTuningResolver] and fans the result
     * across the stage setters. With neutral inputs (the defaults) this pushes the CP-locked native defaults
     * (refine 8, alphas 0.0/0.9, rlIterations 1, snrInverse 0.0), so it is a no-op on a cool/idle device.
     *
     * DEVICE-GATED (CP91): the owner wires the real thermal/load/noise/scene-cut governor signals into this
     * call from the adaptive-load loop after on-device thermal-soak + parity validation. Until then it is a
     * dormant entry point — today's live output is unchanged.
     */
    fun updateOmniClarityMotionTuning(
        thermalThrottling: Boolean = false,
        frameBudgetMs: Double = 16.6,
        recentFrameMs: Double = 0.0,
        sceneCut: Boolean = false,
        noiseSigma: Float = 0f,
        maxRlIterations: Int = 1,
    ) {
        if (!_supported.value) return
        runCatching {
            bridge.applyOmniClarityMotionTuning(
                OmniClarityMotionTuningResolver.resolve(
                    thermalThrottling = thermalThrottling,
                    frameBudgetMs = frameBudgetMs,
                    recentFrameMs = recentFrameMs,
                    sceneCut = sceneCut,
                    noiseSigma = noiseSigma,
                    maxRlIterations = maxRlIterations,
                )
            )
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to push OmniClarity motion tuning", throwable)
        }
    }

    internal fun advanceRenderProfileGeneration(generation: Long): Long =
        ndrNativeFrameBoundary.withMutationLease {
            synchronized(renderProfileGenerationLock) {
                if (closeRequested.get()) return@synchronized acceptedRenderProfileGeneration
                if (generation > acceptedRenderProfileGeneration) {
                    ndrNativeFrameBoundary.closeIngressForMutation()
                    acceptedRenderProfileGeneration = generation
                    acceptedRenderProfileRevision = 0L
                    ndrRenderProfileMutationDeferral.clear()
                    _ndrRenderProfileMutationReadyIdentity.value = null
                    ndrRenderProfileRevisionAdmission.advanceGeneration(generation)
                    ndrRenderProfileReadinessAdmission.advanceGeneration(generation)
                    stagedNdrRenderBundle.clear()
                    appliedNdrRenderBundle.clear()
                    _ndrRenderProfileStagedGeneration.value = 0L
                    _ndrRenderProfileReadyGeneration.value = 0L
                    Log.i(TAG, "NDR_RUNTIME_GENERATION_ADVANCED generation=$generation")
                }
                acceptedRenderProfileGeneration
            }
        }

    internal fun isRenderProfileGenerationCurrent(generation: Long): Boolean =
        generation == acceptedRenderProfileGeneration

    /** Lock-free fail-closed edge from preview-route ownership into native frame admission. */
    internal fun invalidateNdrFrameIngressForPreviewRouteChange(): Long =
        ndrNativeFrameBoundary.invalidateIngressFromExternalRouteChange()

    internal fun currentNdrFrameIngressEpoch(): Long =
        ndrNativeFrameBoundary.currentIngressEpoch()

    internal fun isRenderProfileIdentityCurrent(identity: NdrRenderProfileIdentity): Boolean =
        synchronized(renderProfileGenerationLock) {
            identity.valid &&
                !closeRequested.get() &&
                identity.profileGeneration == acceptedRenderProfileGeneration &&
                identity.configRevision == acceptedRenderProfileRevision
        }

    private inline fun <T> withCurrentRenderProfileGeneration(
        expectedGeneration: Long?,
        staleResult: T,
        block: () -> T
    ): T = synchronized(renderProfileGenerationLock) {
        if (closeRequested.get() ||
            (expectedGeneration != null && expectedGeneration != acceptedRenderProfileGeneration)
        ) {
            staleResult
        } else {
            block()
        }
    }

    fun setHdrDynamicRangeMode(
        mode: HdrDynamicRangeMode,
        renderProfileGeneration: Long? = null
    ): Boolean = withCurrentRenderProfileGeneration(renderProfileGeneration, false) {
        if (!_supported.value) return@withCurrentRenderProfileGeneration false
        synchronized(displayConfigLock) {
            if (appliedHdrDynamicRangeMode == mode) return@synchronized true
            val previousEncoderStatus = _encoderSurfaceStatus.value
            runCatching {
                val updated = bridge.setHdrDynamicRangeMode(mode)
                if (updated) appliedHdrDynamicRangeMode = mode
                // Re-query encoder proof only when an encoder is actually attached. Preview-only mode
                // switches must not synchronously format and parse four diagnostic JNI summaries.
                if (updated && boundEncoderSurface != null) {
                    refreshCapabilitySummary()
                    if (!_encoderSurfaceStatus.value.bound) {
                        // A diagnostic/status miss is not native Surface-destruction proof. Preserve
                        // the exact owner and require an acknowledged detach before codec release.
                        internalEncoderDetachQuarantined = true
                        _encoderSurfaceStatus.value = previousEncoderStatus
                        return@runCatching false
                    }
                }
                updated
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to set HDR dynamic-range mode", throwable)
                if (boundEncoderSurface != null) internalEncoderDetachQuarantined = true
                _encoderSurfaceStatus.value = previousEncoderStatus
            }.getOrDefault(false)
        }
    }

    /**
     * X2/R1: request (or revoke) a 10-bit packed sRGB DISPLAY (preview) swapchain for SDR. The
     * native side rebuilds only the preview target and falls back byte-identically to 8-bit when the
     * driver advertises no 10-bit SDR display pair, so this is safe to enable and runtime-revocable
     * if on-device frame pacing regresses.
     */
    fun setPreferTenBitSdrDisplay(
        enabled: Boolean,
        renderProfileGeneration: Long? = null
    ): Boolean = withCurrentRenderProfileGeneration(renderProfileGeneration, false) {
        if (!_supported.value) return@withCurrentRenderProfileGeneration false
        synchronized(displayConfigLock) {
            if (appliedPreferTenBitSdrDisplay == enabled) return@synchronized true
            runCatching {
                bridge.setPreferTenBitSdrDisplay(enabled).also { updated ->
                    if (updated) appliedPreferTenBitSdrDisplay = enabled
                }
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to set SDR display 10-bit preference", throwable)
            }.getOrDefault(false)
        }
    }

    fun updateRenderConfig(
        previewTransform: FloatArray,
        encoderTransform: FloatArray,
        colorGain: FloatArray,
        previewGrade: FloatArray,
        encoderGrade: FloatArray,
        previewColorScience: FloatArray = VulkanColorScienceUniforms.disabled().pack(),
        encoderColorScience: FloatArray = VulkanColorScienceUniforms.disabled().pack(),
        renderProfileGeneration: Long? = null
    ): Boolean = withCurrentRenderProfileGeneration(renderProfileGeneration, false) {
        if (!_supported.value) return@withCurrentRenderProfileGeneration false
        val candidate = VulkanRenderConfig(
            previewTransform = previewTransform,
            encoderTransform = encoderTransform,
            colorGain = colorGain,
            previewGrade = previewGrade,
            encoderGrade = encoderGrade,
            previewColorScience = previewColorScience,
            encoderColorScience = encoderColorScience
        ).immutableSnapshot()
        val nativeAccepted = runCatching {
            bridge.updateRenderConfig(
                previewTransform = candidate.previewTransform,
                encoderTransform = candidate.encoderTransform,
                colorGain = candidate.colorGain,
                previewGrade = candidate.previewGrade,
                encoderGrade = candidate.encoderGrade,
                previewColorScience = candidate.previewColorScience,
                encoderColorScience = candidate.encoderColorScience
            )
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to commit Vulkan render config", throwable)
        }.getOrDefault(false)
        if (!nativeAccepted) return@withCurrentRenderProfileGeneration false

        renderConfig = candidate
        lastRenderConfig = candidate
        framesSinceConfigUpdate = 0
        configDirty = false
        val committedGeneration = configGenerationToken.incrementAndGet()
        lastConfigGeneration.set(committedGeneration)
        true
    }

    /** Stages intent only. Native consumers change exclusively through a coherent profile packet. */
    internal fun updateNdrNivukxMeasuredCorrection(
        correction: NdrNivukxMeasuredCorrection,
        renderProfileGeneration: Long? = null
    ): Boolean = withCurrentRenderProfileGeneration(renderProfileGeneration, false) {
        pendingNdrNivukxMeasuredCorrection = correction
        true
    }

    private fun applyNdrNivukxMeasuredCorrectionIfNeeded(profile: NdrLiveProfile): Boolean =
        synchronized(renderProfileGenerationLock) {
            if (!initialized.get() || !_supported.value) return@synchronized false
            val correction = pendingNdrNivukxMeasuredCorrection
            if (appliedNdrNivukxMeasuredCorrection == correction &&
                appliedNdrNivukxProfileGeneration == acceptedRenderProfileGeneration &&
                appliedNdrLiveProfile == profile
            ) return@synchronized true
            var committed = false
            runCatching {
                val monitorApplied = bridge.setRawMonitorNdrNivukxCorrection(correction, profile)
                val encoderApplied =
                    offscreenAhbEglEncoderBridge.setNdrNivukxMeasuredCorrection(
                        correction,
                        acceptedRenderProfileGeneration,
                        profile
                    )
                val previewApplied = if (sensorLinearNdrPreviewBridgeDelegate.isInitialized()) {
                    sensorLinearNdrPreviewBridge.setNdrNivukxMeasuredCorrection(
                        correction,
                        acceptedRenderProfileGeneration,
                        profile
                    )
                } else {
                    true
                }
                if (!monitorApplied || !encoderApplied || !previewApplied) {
                    Log.w(
                        TAG,
                        "NDR profile retained for retry: mode=${correction.mode.nativeCode} " +
                            "monitorApplied=$monitorApplied encoderApplied=$encoderApplied " +
                            "previewApplied=$previewApplied"
                    )
                    return@runCatching
                }
                // Cache only after every initialized native consumer accepted the packet. Previously a released/not-yet-
                // created handle silently dropped the setter while this cache still marked the profile applied,
                // leaving the RAW monitor permanently in mode=OFF until a different profile was selected.
                appliedNdrNivukxMeasuredCorrection = correction
                appliedNdrNivukxProfileGeneration = acceptedRenderProfileGeneration
                appliedNdrLiveProfile = profile
                committed = true
                Log.i(
                    TAG,
                    "NDR_RUNTIME_PROFILE_COMMITTED mode=${correction.mode.nativeCode} " +
                        "generation=$acceptedRenderProfileGeneration " +
                        "active=${correction.active} diffuse=${profile.sensorDiffuseScene} " +
                        "gamma=${profile.signalGamma} detail=${profile.localDetailStrength}"
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to apply unified NDR/NIVUKX measured correction", throwable)
            }
            committed
        }

    /**
     * Set the offscreen-EGL record-grade colour matrix (row-major 3x3) + pivoted contrast.
     * Identity matrix + contrast 1.0 (the default) leaves recorded pixels bit-identical.
     */
    fun setOffscreenRecordGrade(matrix: FloatArray, contrast: Float) {
        offscreenRecordGradeMatrix =
            if (matrix.size >= 9) matrix.copyOf(9) else OffscreenAhbEglEncoderBridge.IDENTITY9
        offscreenRecordGradeContrast = contrast.coerceIn(0.1f, 4f)
        offscreenAhbEglEncoderBridge.setRecordGrade(
            offscreenRecordGradeMatrix,
            offscreenRecordGradeContrast
        )
    }

    /**
     * Upload the creative 3D LUT for the record surface (SDR record only — the native layer
     * additionally forces strength 0 on HLG/PQ). null/size<=1 disables it.
     */
    fun updateRecordCreativeLut(lutData: FloatArray?, size: Int) {
        offscreenAhbEglEncoderBridge.setCreativeLut(lutData, size, RECORD_CREATIVE_LUT_STRENGTH)
    }

    fun updateEnhancementUniforms(
        uniforms: GpuImageEnhancementUniforms,
        renderProfileGeneration: Long? = null
    ): Boolean = withCurrentRenderProfileGeneration(renderProfileGeneration, false) {
        installEnhancementUniforms(uniforms)
        true
    }

    private fun installEnhancementUniforms(uniforms: GpuImageEnhancementUniforms) {
        val previousSignature = enhancementUniforms?.signature
        enhancementUniforms = uniforms
        if (uniforms.signature != previousSignature || uniforms.signature != lastEnhancementSignature) {
            enhancementDirty = true
            configGenerationToken.incrementAndGet()
        }
    }

    /** Native acknowledgement is part of packet staging; ready can never precede this upload. */
    private fun uploadEnhancementUniformsForBundle(
        uniforms: GpuImageEnhancementUniforms
    ): Boolean {
        val accepted = runCatching { bridge.updateEnhancementUniforms(uniforms) }
            .onFailure { Log.e(TAG, "Failed to upload generation-owned enhancement uniforms", it) }
            .getOrDefault(false)
        if (!accepted) return false
        installEnhancementUniforms(uniforms)
        enhancementDirty = false
        lastEnhancementSignature = uniforms.signature
        // Native acknowledgement above is the authoritative packet-staging result. Building the
        // multi-bridge diagnostic summary here would contend with frame submission every time the
        // dynamic scene profile changes. ACTIVE_NATIVE precision is still re-proved immediately by
        // the profile coordinator; the persistent route already has independent precision proof.
        return true
    }

    /**
     * Commits correction mode and enhancement identity as one generation-owned packet. Frame ingress is
     * closed from generation advance until this whole packet succeeds, so no consumer can render a
     * new correction with old enhancement identity (or the inverse).
     */
    private fun subjectDetailMaskSignature(mask: SubjectMask?): String = mask?.let {
        "${it.trackingId}:${it.maskWidth}x${it.maskHeight}:${it.alphaMask.contentHashCode()}"
    } ?: "none"

    private fun matchesAppliedNdrRenderPacket(packet: NdrRenderProfilePacket): Boolean =
        appliedNdrRenderBundle.snapshot()?.matches(packet) == true

    private fun matchesStagedNdrRenderPacket(packet: NdrRenderProfilePacket): Boolean =
        stagedNdrRenderBundle.snapshot()?.matches(packet) == true

    private fun invalidateNdrRenderProfileIngressLocked() {
        ndrNativeFrameBoundary.requireMutationLease()
        ndrNativeFrameBoundary.closeIngressForMutation()
        appliedNdrRenderBundle.clear()
        stagedNdrRenderBundle.clear()
        _ndrRenderProfileReadyGeneration.value = 0L
        _ndrRenderProfileStagedGeneration.value = 0L
    }

    /** Caller owns boundary mutation + generation ownership; RAW ownership is sampled atomically. */
    private fun deferNdrRenderProfileWhileRawOutputOwnedLocked(
        identity: NdrRenderProfileIdentity
    ): Boolean = synchronized(rawDemosaicCompletionLock) {
        val outputOwned = rawDemosaicCompletionHandoff.hasOutstandingIdentity() ||
            sensorLinearNdrEncoderInputReads.outstandingCount != 0 ||
            sensorLinearNdrRecordingPreviewWorker.snapshot().outstandingTasks != 0
        if (outputOwned) {
            ndrRenderProfileMutationDeferral.defer(identity)
            true
        } else {
            false
        }
    }

    /**
     * Admits one same-generation native-visible replacement while the caller owns both the native
     * mutation lease and [renderProfileGenerationLock]. A non-null result is terminal for this
     * commit; null means the packet may continue through readiness admission.
     */
    private fun admitNdrRenderProfileRevisionLocked(
        packet: NdrRenderProfilePacket
    ): NdrRenderProfileCommitResult? {
        return when (
            ndrRenderProfileRevisionAdmission.admit(
                identity = packet.identity,
                payload = packet
            )
        ) {
            NdrRenderProfileRevisionAdmission.Result.ACCEPTED_NEW -> {
                acceptedRenderProfileRevision = packet.configRevision
                // A newer native-visible intent invalidates the old bundle immediately. This also
                // keeps a cold-start/deferred replacement fail-closed after this lock exits.
                invalidateNdrRenderProfileIngressLocked()
                null
            }

            NdrRenderProfileRevisionAdmission.Result.IDEMPOTENT -> {
                if (acceptedRenderProfileRevision == packet.configRevision) {
                    null
                } else {
                    Log.e(
                        TAG,
                        "NDR revision ledger/runtime watermark diverged identity=${packet.identity}"
                    )
                    invalidateNdrRenderProfileIngressLocked()
                    NdrRenderProfileCommitResult.APPLY_FAILED
                }
            }

            NdrRenderProfileRevisionAdmission.Result.STALE_GENERATION ->
                NdrRenderProfileCommitResult.STALE_GENERATION

            NdrRenderProfileRevisionAdmission.Result.STALE_REVISION ->
                NdrRenderProfileCommitResult.STALE_REVISION

            NdrRenderProfileRevisionAdmission.Result.CONFLICTING_REVISION -> {
                Log.e(
                    TAG,
                    "NDR_PROFILE_PACKET_CONFLICT identity=${packet.identity}; " +
                        "one immutable payload is permitted per config revision"
                )
                invalidateNdrRenderProfileIngressLocked()
                NdrRenderProfileCommitResult.CONFLICTING_REVISION
            }

            NdrRenderProfileRevisionAdmission.Result.CANCELLED_LIFECYCLE ->
                NdrRenderProfileCommitResult.CANCELLED_LIFECYCLE
        }
    }

    internal fun commitNdrRenderProfilePacket(
        packet: NdrRenderProfilePacket
    ): NdrRenderProfileCommitResult {
        val retainedPacket = packet.immutableSnapshot()
        return ndrNativeFrameBoundary.withMutationLease {
            withCurrentRenderProfileGeneration(
                retainedPacket.profileGeneration,
                NdrRenderProfileCommitResult.STALE_GENERATION
            ) {
                val coherence = retainedPacket.coherence
                if (!coherence.coherent) {
                    Log.e(
                        TAG,
                        "NDR_PROFILE_PACKET_REJECTED generation=${retainedPacket.profileGeneration} " +
                            "correction=${coherence.correctionMode} " +
                            "expected=${coherence.expectedColorScience ?: "NON_NDR"} " +
                            "science=${coherence.actualColorScience}"
                    )
                    return@withCurrentRenderProfileGeneration NdrRenderProfileCommitResult.INCOHERENT_PACKET
                }
                val revisionPreflight = ndrRenderProfileRevisionAdmission.inspect(
                    identity = retainedPacket.identity,
                    payload = retainedPacket
                )
                var revisionAlreadyAdmitted = false
                when (revisionPreflight) {
                    NdrRenderProfileRevisionAdmission.Result.STALE_GENERATION,
                    NdrRenderProfileRevisionAdmission.Result.STALE_REVISION,
                    NdrRenderProfileRevisionAdmission.Result.CONFLICTING_REVISION,
                    NdrRenderProfileRevisionAdmission.Result.CANCELLED_LIFECYCLE -> {
                        // Stale/conflicting work is terminal regardless of RAW output ownership. Run it
                        // through the authoritative admission path for logging and fail-closed cleanup.
                        val terminalResult = admitNdrRenderProfileRevisionLocked(retainedPacket)
                            ?: NdrRenderProfileCommitResult.APPLY_FAILED
                        return@withCurrentRenderProfileGeneration terminalResult
                    }
                    NdrRenderProfileRevisionAdmission.Result.IDEMPOTENT -> {
                        val revisionFailure = admitNdrRenderProfileRevisionLocked(retainedPacket)
                        if (revisionFailure != null) {
                            return@withCurrentRenderProfileGeneration revisionFailure
                        }
                        revisionAlreadyAdmitted = true
                        if (matchesAppliedNdrRenderPacket(retainedPacket) ||
                            matchesStagedNdrRenderPacket(retainedPacket)
                        ) {
                            when (ndrRenderProfileMutationDeferral.admission(retainedPacket.identity)) {
                                NdrRenderProfileMutationDeferral.Admission.STALE_REVISION ->
                                    return@withCurrentRenderProfileGeneration NdrRenderProfileCommitResult.STALE_REVISION
                                NdrRenderProfileMutationDeferral.Admission.PROCEED ->
                                    _ndrRenderProfileMutationReadyIdentity.value = null
                            }
                            // This is an exact payload+identity replay and performs no native mutation;
                            // it must not stall continuous RAW recording behind its own output fence.
                            return@withCurrentRenderProfileGeneration NdrRenderProfileCommitResult.COMMITTED
                        }
                    }
                    NdrRenderProfileRevisionAdmission.Result.ACCEPTED_NEW -> Unit
                }
                if (deferNdrRenderProfileWhileRawOutputOwnedLocked(retainedPacket.identity)) {
                    Log.i(
                        TAG,
                        "NDR_PROFILE_PACKET_DEFERRED_IN_FLIGHT identity=${retainedPacket.identity}; " +
                            "exact async RAW output will retire before native replacement"
                    )
                    return@withCurrentRenderProfileGeneration NdrRenderProfileCommitResult.DEFERRED_IN_FLIGHT
                }
                when (ndrRenderProfileMutationDeferral.admission(retainedPacket.identity)) {
                    NdrRenderProfileMutationDeferral.Admission.STALE_REVISION ->
                        return@withCurrentRenderProfileGeneration NdrRenderProfileCommitResult.STALE_REVISION
                    NdrRenderProfileMutationDeferral.Admission.PROCEED ->
                        _ndrRenderProfileMutationReadyIdentity.value = null
                }
                if (!revisionAlreadyAdmitted) {
                    val revisionFailure = admitNdrRenderProfileRevisionLocked(retainedPacket)
                    if (revisionFailure != null) {
                        return@withCurrentRenderProfileGeneration revisionFailure
                    }
                }
                if (matchesAppliedNdrRenderPacket(retainedPacket) ||
                    matchesStagedNdrRenderPacket(retainedPacket)
                ) {
                    return@withCurrentRenderProfileGeneration NdrRenderProfileCommitResult.COMMITTED
                }
                val admission = ndrRenderProfileReadinessAdmission.submit(
                    generation = retainedPacket.profileGeneration,
                    intent = retainedPacket,
                    // Readiness means cold-start ownership has reached a terminal state. Native
                    // support/handle rejection is then a real apply failure, never a deferred retry.
                    consumersReady = initializationCompleted.get(),
                    apply = { accepted ->
                        applyNdrRenderProfilePacketNow(accepted, publishReplayStagedEdge = false)
                    }
                )
                when (admission) {
                    NdrRenderProfileReadinessAdmission.Result.APPLIED ->
                        NdrRenderProfileCommitResult.COMMITTED
                    NdrRenderProfileReadinessAdmission.Result.DEFERRED_UNREADY ->
                        NdrRenderProfileCommitResult.DEFERRED_UNREADY
                    NdrRenderProfileReadinessAdmission.Result.STALE_GENERATION ->
                        NdrRenderProfileCommitResult.STALE_GENERATION
                    NdrRenderProfileReadinessAdmission.Result.APPLY_FAILED ->
                        NdrRenderProfileCommitResult.APPLY_FAILED
                    NdrRenderProfileReadinessAdmission.Result.CANCELLED_LIFECYCLE ->
                        NdrRenderProfileCommitResult.CANCELLED_LIFECYCLE
                }
            }
        }
    }

    /** Applies one already-coherent, current-generation packet to every initialized native owner. */
    private fun applyNdrRenderProfilePacketNow(
        packet: NdrRenderProfilePacket,
        publishReplayStagedEdge: Boolean
    ): Boolean {
        ndrNativeFrameBoundary.requireMutationLease()
        if (packet.profileGeneration != acceptedRenderProfileGeneration ||
            packet.configRevision != acceptedRenderProfileRevision ||
            !initializationCompleted.get() || !_supported.value
        ) return false
        if (matchesAppliedNdrRenderPacket(packet) || matchesStagedNdrRenderPacket(packet)) return true
        // Once the first setter can run, neither the previous bundle nor a staged identity is safe to
        // admit. A partial JNI/EGL failure deliberately remains fail-closed until a complete packet is
        // staged and its owner finalizes publication.
        invalidateNdrRenderProfileIngressLocked()
        val nativeConfig = packet.nativeRenderConfig
        pendingNdrNivukxMeasuredCorrection = packet.correction
        val liveProfile = NdrLiveProfilePolicy.resolve(
            packet.correction.mode,
            packet.enhancementUniforms.detailProfileId
        )
        val nativeAdmission = VulkanNativeRenderCommitAdmission(
            hdrDynamicRangeAccepted = setHdrDynamicRangeMode(
                nativeConfig.hdrDynamicRangeMode,
                renderProfileGeneration = packet.profileGeneration
            ),
            tenBitSdrDisplayPreferenceAccepted = setPreferTenBitSdrDisplay(
                nativeConfig.preferTenBitSdrDisplay,
                renderProfileGeneration = packet.profileGeneration
            ),
            renderConfigAccepted = updateRenderConfig(
                previewTransform = nativeConfig.renderConfig.previewTransform,
                encoderTransform = nativeConfig.renderConfig.encoderTransform,
                colorGain = nativeConfig.renderConfig.colorGain,
                previewGrade = nativeConfig.renderConfig.previewGrade,
                encoderGrade = nativeConfig.renderConfig.encoderGrade,
                previewColorScience = nativeConfig.renderConfig.previewColorScience,
                encoderColorScience = nativeConfig.renderConfig.encoderColorScience,
                renderProfileGeneration = packet.profileGeneration
            ),
            subjectDetailMaskAccepted = updateSubjectDetailMask(
                mask = packet.subjectDetailMask,
                renderProfileGeneration = packet.profileGeneration
            ),
            correctionAccepted = applyNdrNivukxMeasuredCorrectionIfNeeded(liveProfile),
            enhancementUniformsAccepted = uploadEnhancementUniformsForBundle(
                packet.enhancementUniforms
            )
        )
        if (!nativeAdmission.accepted) {
            Log.e(
                TAG,
                "NDR_RENDER_CONFIG_NATIVE_REJECTED generation=${packet.profileGeneration} " +
                    "stages=${nativeAdmission.rejectedStages()}; frame ingress retained"
            )
            return false
        }
        stagedNdrRenderBundle.publish(
            AppliedNdrRenderBundle(
                profileGeneration = packet.profileGeneration,
                configRevision = packet.configRevision,
                correction = packet.correction,
                enhancementUniforms = packet.enhancementUniforms,
                nativeRenderConfig = nativeConfig,
                subjectDetailMask = packet.subjectDetailMask,
                liveProfile = liveProfile
            )
        )
        if (publishReplayStagedEdge) {
            _ndrRenderProfileStagedGeneration.value = packet.profileGeneration
        }
        Log.i(
            TAG,
            "NDR_PROFILE_PACKET_STAGED identity=${packet.identity} " +
                "mode=${packet.correction.mode.nativeCode} " +
                "science=${packet.enhancementUniforms.colorScience}"
        )
        return true
    }

    /**
     * Opens frame ingress only after the owner publishes every generation-bound downstream state.
     * [beforeIngress] executes while the render-generation monitor is held, after the exact staged
     * packet has been revalidated and before its applied identity or ready edge becomes observable.
     */
    internal fun finalizeNdrRenderProfilePacket(
        packet: NdrRenderProfilePacket,
        beforeIngress: () -> Boolean
    ): Boolean {
        val retainedPacket = packet.immutableSnapshot()
        return ndrNativeFrameBoundary.finalizeIngress {
            withCurrentRenderProfileGeneration(retainedPacket.profileGeneration, false) {
                val appliedBundleAlreadyPublished = matchesAppliedNdrRenderPacket(retainedPacket)
                val stagedBundle = stagedNdrRenderBundle.snapshot()
                if (!appliedBundleAlreadyPublished && stagedBundle?.matches(retainedPacket) != true) {
                    return@withCurrentRenderProfileGeneration false
                }
                val ownerPublished = try {
                    beforeIngress()
                } catch (failure: Throwable) {
                    if (failure is CancellationException ||
                        failure is VirtualMachineError ||
                        failure is ThreadDeath
                    ) throw failure
                    Log.e(
                        TAG,
                        "NDR owner publication failed identity=${retainedPacket.identity}; " +
                            "frame ingress retained",
                        failure
                    )
                    false
                }
                if (!ownerPublished) return@withCurrentRenderProfileGeneration false
                // An exact applied replay still requires the current route proof above. Its native
                // bundle and downstream owner state are already published, so only ingress reopens.
                if (appliedBundleAlreadyPublished) return@withCurrentRenderProfileGeneration true
                val exactStagedBundle = stagedBundle ?: return@withCurrentRenderProfileGeneration false
                appliedNdrRenderBundle.publish(exactStagedBundle)
                stagedNdrRenderBundle.clear()
                _ndrRenderProfileStagedGeneration.value = 0L
                _ndrRenderProfileReadyGeneration.value = retainedPacket.profileGeneration
                Log.i(
                    TAG,
                    "NDR_PROFILE_PACKET_COMMITTED identity=${retainedPacket.identity} " +
                        "mode=${retainedPacket.correction.mode.nativeCode} " +
                        "science=${retainedPacket.enhancementUniforms.colorScience}"
                )
                true
            }
        }
    }

    /** Consumes the latest compatible cold-start packet once after native ownership is decided. */
    private fun replayDeferredNdrRenderProfileIfReady(trigger: String) {
        ndrNativeFrameBoundary.withMutationLease {
            synchronized(renderProfileGenerationLock) {
                val result = ndrRenderProfileReadinessAdmission.replayWhenReady(
                    generation = acceptedRenderProfileGeneration,
                    consumersReady = initializationCompleted.get(),
                    apply = { retained ->
                        applyNdrRenderProfilePacketNow(retained, publishReplayStagedEdge = true)
                    }
                ) ?: return@synchronized
                when (result) {
                    NdrRenderProfileReadinessAdmission.Result.APPLIED -> Log.i(
                        TAG,
                        "NDR_PROFILE_PACKET_REPLAYED generation=$acceptedRenderProfileGeneration " +
                            "trigger=$trigger"
                    )
                    NdrRenderProfileReadinessAdmission.Result.APPLY_FAILED -> Log.e(
                        TAG,
                        "NDR profile replay failed after consumer readiness " +
                            "generation=$acceptedRenderProfileGeneration trigger=$trigger"
                    )
                    NdrRenderProfileReadinessAdmission.Result.STALE_GENERATION -> Log.i(
                        TAG,
                        "NDR profile replay cancelled by generation advance trigger=$trigger"
                    )
                    NdrRenderProfileReadinessAdmission.Result.DEFERRED_UNREADY,
                    NdrRenderProfileReadinessAdmission.Result.CANCELLED_LIFECYCLE -> Unit
                }
            }
        }
    }

    private fun currentNdrRenderBundleSnapshot(): AppliedNdrRenderBundle? {
        val generation = acceptedRenderProfileGeneration
        val revision = acceptedRenderProfileRevision
        val bundle = appliedNdrRenderBundle.snapshot() ?: return null
        if (generation <= 0L || revision <= 0L ||
            bundle.profileGeneration != generation || bundle.configRevision != revision
        ) return null
        return bundle.takeIf {
            acceptedRenderProfileGeneration == generation &&
                acceptedRenderProfileRevision == revision
        }
    }

    private fun hasCurrentNdrRenderProfilePacket(): Boolean =
        currentNdrRenderBundleSnapshot() != null

    /** Read-only readiness proof used before a RAW preview epoch attempts native surface binding. */
    internal fun isCurrentNdrRenderProfilePacketReady(): Boolean =
        synchronized(renderProfileGenerationLock) {
            initializationCompleted.get() && _supported.value &&
                hasCurrentNdrRenderProfilePacket()
        }

    fun updateSubjectDetailMask(
        mask: SubjectMask?,
        renderProfileGeneration: Long? = null
    ): Boolean = withCurrentRenderProfileGeneration(renderProfileGeneration, false) {
        if (!_supported.value) return@withCurrentRenderProfileGeneration false
        val signature = subjectDetailMaskSignature(mask)
        if (signature == lastSubjectDetailMaskSignature) {
            return@withCurrentRenderProfileGeneration true
        }
        runCatching {
            val updated = bridge.updateSubjectDetailMask(
                maskWidth = mask?.maskWidth ?: 0,
                maskHeight = mask?.maskHeight ?: 0,
                alphaMask = mask?.alphaMask,
                trackingId = mask?.trackingId ?: -1
            )
            if (updated) {
                lastSubjectDetailMaskSignature = signature
                refreshCapabilitySummary()
            }
            updated
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to update Vulkan subject detail mask", throwable)
        }.getOrDefault(false)
    }

    fun updateFocusMap(focusMap: FloatArray) {
        if (!_supported.value) return
        require(focusMap.size == 256) { "Focus map must be exactly 256 elements" }
        runCatching {
            bridge.updateFocusMap(focusMap)
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to update focus map", throwable)
        }
    }


    private fun pushEnhancementUniformsIfNeeded(): Boolean {
        val uniforms = currentNdrRenderBundleSnapshot()?.enhancementUniforms ?: return false
        if (!enhancementDirty && uniforms.signature == lastEnhancementSignature) return true
        return if (bridge.updateEnhancementUniforms(uniforms)) {
            enhancementDirty = false
            lastEnhancementSignature = uniforms.signature
            refreshCapabilitySummary()
            true
        } else {
            enhancementDirty = true
            Log.e(TAG, "Required enhancement re-upload rejected; frame ingress retained")
            false
        }
    }

    // CP-QV16/17 — forward the process-global spatial-denoise / optical-sharpen gates to the live bridge
    // on the render tick so a runtime (debug-broadcast) toggle takes effect on the next frame. Change-
    // guarded: the JNI setter fires only on an actual flip. With both flags OFF (default) the native
    // dispatch stays unreachable and the render path is byte-for-byte identical (no-downgrade).
    @Volatile private var lastSpatialDenoiseForwarded: Boolean? = null
    @Volatile private var lastOpticalSharpenForwarded: Boolean? = null
    @Volatile private var lastDistantDetailGatesForwarded: Boolean? = null
    @Volatile private var lastApexPeakNitsForwarded: Boolean? = null
    @Volatile private var lastExactGamutCuspForwarded: Boolean? = null

    private fun applyDormantSpatialEnhancementFlags() {
        val denoise = DenoiseSpatialFlag.enabled
        if (denoise != lastSpatialDenoiseForwarded) {
            runCatching { bridge.setSpatialDenoiseEnabled(denoise) }
                .onSuccess { lastSpatialDenoiseForwarded = denoise }
                .onFailure { Log.w(TAG, "Failed to forward spatial-denoise gate", it) }
        }
        val sharpen = SharpenOpticalFlag.enabled
        if (sharpen != lastOpticalSharpenForwarded) {
            runCatching { bridge.setOpticalSharpenEnabled(sharpen) }
                .onSuccess { lastOpticalSharpenForwarded = sharpen }
                .onFailure { Log.w(TAG, "Failed to forward optical-sharpen gate", it) }
        }
        val distantDetail = DistantDetailGatesFlag.enabled
        if (distantDetail != lastDistantDetailGatesForwarded) {
            runCatching { bridge.setDistantDetailGatesEnabled(distantDetail) }
                .onSuccess { lastDistantDetailGatesForwarded = distantDetail }
                .onFailure { Log.w(TAG, "Failed to forward distant-detail gates", it) }
        }
        val apexPeakNits = ApexPeakNitsFlag.enabled
        if (apexPeakNits != lastApexPeakNitsForwarded) {
            runCatching { bridge.setApexPeakNitsEnabled(apexPeakNits) }
                .onSuccess { lastApexPeakNitsForwarded = apexPeakNits }
                .onFailure { Log.w(TAG, "Failed to forward apex peak-nits gate", it) }
        }
        val exactGamutCusp = ExactGamutCuspFlag.enabled
        if (exactGamutCusp != lastExactGamutCuspForwarded) {
            runCatching { bridge.setExactGamutCuspEnabled(exactGamutCusp) }
                .onSuccess { lastExactGamutCuspForwarded = exactGamutCusp }
                .onFailure { Log.w(TAG, "Failed to forward exact gamut-cusp gate", it) }
        }
    }

    fun updatePreviewLut(
        lutData: FloatArray?,
        size: Int,
        renderProfileGeneration: Long? = null
    ): Boolean = withCurrentRenderProfileGeneration(renderProfileGeneration, false) {
        if (!_supported.value) return@withCurrentRenderProfileGeneration false
        val updated = runCatching {
            bridge.updatePreviewLut(lutData = lutData, size = size)
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to update Vulkan preview LUT", throwable)
        }.getOrDefault(false)
        if (updated) {
            // Diagnostics cannot retroactively turn an acknowledged native upload into failure.
            runCatching { refreshCapabilitySummary() }
                .onFailure { Log.w(TAG, "Preview LUT diagnostic refresh failed", it) }
        }
        updated
    }

    fun setFrameLineageSubmissionListener(
        listener: ((VulkanFrameLineageSubmission) -> Unit)?
    ) {
        frameLineageSubmissionListener = listener
    }

    /**
     * ════════════════════════════════════════════════════════════════════════
     * OPTIMIZED FRAME SUBMISSION - BATCHED CONFIG UPDATES
     * ════════════════════════════════════════════════════════════════════════
     *
     * Key optimizations:
     * - Skip redundant config updates (hash-based detection)
     * - Batch config updates to reduce JNI overhead
     * - Fast path for unchanged configuration
     * - Periodic capability summary updates (not every frame)
     */
    fun submitCameraImage(
        image: Image,
        presentationTimeNs: Long = 0L,
        niv99Tuning: Niv99LiveFrameTuning? = null,
        cameraSessionGeneration: Long,
        canonicalSensorTimestampNs: Long? = null,
        recordingPath: Boolean = false
    ): Boolean {
        val recordingSubmissionExpected = CameraImageRecordingExpectationPolicy.isLiveSubmissionExpected(
            recordingPath = recordingPath,
            offscreenEncoderActive = offscreenAhbEglEncoderActive,
            offscreenSubmissionEnabled = offscreenAhbEglSubmissionEnabled.get()
        )
        cameraImageSubmissionOutcome.set(
            CameraImageSubmissionOutcome(
                accepted = false,
                recordingSubmissionExpected = recordingSubmissionExpected,
                reason = "submission-started"
            )
        )
        val importSessionLease =
            cameraImportSessionGate.acquireSubmission(cameraSessionGeneration)
                ?: return rejectCameraImageSubmission(
                    reason = "camera-session-generation-mismatch:" +
                        "expected=$cameraSessionGeneration:" +
                        "active=${cameraImportSessionGate.activeGeneration()}",
                    recordingSubmissionExpected = recordingSubmissionExpected
                )
        try {
        val ingressWasOpen = ndrNativeFrameBoundary.snapshot().ingressOpen
        val ndrFrameLease = if (recordingSubmissionExpected) {
            ndrNativeFrameBoundary.acquireFrameLeaseAwaitingIngress(
                recordingProfileTransitionWaitNs(offscreenEncoderTargetFps)
            )
        } else {
            ndrNativeFrameBoundary.acquireFrameLease()
        } ?: return rejectCameraImageSubmission(
            reason = "render-profile-ingress-closed:" +
                "epoch=${ndrNativeFrameBoundary.currentIngressEpoch()}",
            recordingSubmissionExpected = recordingSubmissionExpected,
            waitedForProfileIngress = recordingSubmissionExpected && !ingressWasOpen
        )
        try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return rejectCameraImageSubmission("api-below-29", recordingSubmissionExpected)
        }
        if (!isFrameImportEnabled()) {
            return rejectCameraImageSubmission("frame-import-disabled", recordingSubmissionExpected)
        }
        if (!_supported.value) {
            return rejectCameraImageSubmission("vulkan-runtime-unsupported", recordingSubmissionExpected)
        }
        val renderBundle = currentNdrRenderBundleSnapshot()
            ?: return rejectCameraImageSubmission("render-bundle-unavailable", recordingSubmissionExpected)
        if (sensorLinearNdrCaptureEnabled) {
            return rejectCameraImageSubmission("camera-p010-disabled-for-sensor-linear-ndr", recordingSubmissionExpected)
        }

        val width: Int
        val height: Int
        val format: Int
        val dataSpace: Int
        val hardwareBuffer: android.hardware.HardwareBuffer
        val rawSensorTimestampNs = try { image.timestamp } catch (_: Exception) { 0L }
        val sensorTimestampNs = canonicalSensorTimestampNs
            ?.takeIf { it > 0L }
            ?: rawSensorTimestampNs
        val effectivePresentationTimeNs = if (presentationTimeNs > 0L) {
            presentationTimeNs
        } else {
            presentationClock.presentationTimeFor(sensorTimestampNs, System.nanoTime())
        }
        var submittedPresentationTimeNs = effectivePresentationTimeNs
        var offscreenLiveSubmissionAttempt = 0L

        try {
            width = image.width
            height = image.height
            format = image.format
            dataSpace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { image.dataSpace }
                    .getOrDefault(NdrInputTransportPolicy.DATASPACE_UNKNOWN)
            } else {
                NdrInputTransportPolicy.DATASPACE_UNKNOWN
            }
            hardwareBuffer = image.hardwareBuffer
                ?: return rejectCameraImageSubmission("hardware-buffer-unavailable", recordingSubmissionExpected)
        } catch (failure: Exception) {
            return rejectCameraImageSubmission(
                "image-access-failed:${failure.javaClass.simpleName}",
                recordingSubmissionExpected
            )
        }

        return try {
            if (currentNdrRenderBundleSnapshot()?.identity != renderBundle.identity) {
                return rejectCameraImageSubmission(
                    "render-bundle-changed-before-route",
                    recordingSubmissionExpected
                )
            }
            val configSnapshot = renderBundle.nativeRenderConfig.renderConfig
            var offscreenSkippedByPacing = false
            var offscreenSubmissionPaused = false
            val imported = if (offscreenAhbEglEncoderActive) {
                if (!offscreenAhbEglSubmissionEnabled.get()) {
                    // Consume gate-closed Camera2 frames through the exact full-resolution SDR
                    // decode/grade program into private RGB10 scratch. Native performs no timestamp
                    // or swap, so these frames cannot enter MediaCodec or clip accounting.
                    prewarmGateClosedCameraP010Sdr(
                        hardwareBuffer = hardwareBuffer,
                        width = width,
                        height = height,
                        format = format,
                        dataSpace = dataSpace,
                        sensorTimestampNs = sensorTimestampNs,
                        uniforms = renderBundle.enhancementUniforms
                    )
                    offscreenSubmissionPaused = true
                    false
                } else {
                    // The hardware-import reader is already driven by the Camera2 recording
                    // request at the selected sensor cadence. Applying the generic rate-converter
                    // here a second time made small presentation-clock corrections look like early
                    // frames and silently discarded genuine P010 camera masters. That produced the
                    // measured 14-17 fps clips even though Camera2 itself was delivering 25 fps.
                    // Preserve every admitted recording master and only regularize its MediaCodec
                    // timestamp, exactly as the project-owned RAW/NDR recorder path does.
                    val pacingDecision = synchronized(offscreenFramePacingLock) {
                        OffscreenEncoderFramePacingPolicy.resolveOneToOne(
                            presentationTimeNs = effectivePresentationTimeNs,
                            targetFps = offscreenEncoderTargetFps,
                            state = offscreenFramePacingState
                        ).also { offscreenFramePacingState = it.state }
                    }
                    if (!pacingDecision.submit) {
                        offscreenSkippedByPacing = true
                        false
                    } else {
                        submittedPresentationTimeNs =
                            pacingDecision.submissionPresentationTimeNs
                                .takeIf { it > 0L }
                                ?: effectivePresentationTimeNs
                        if (currentNdrRenderBundleSnapshot()?.identity != renderBundle.identity
                        ) return rejectCameraImageSubmission(
                            "render-bundle-changed-before-offscreen-submit",
                            recordingSubmissionExpected
                        )
                        offscreenLiveSubmissionAttempt =
                            offscreenEncoderLiveSubmissionAttempts.incrementAndGet()
                        offscreenAhbEglEncoderBridge.submitHardwareBuffer(
                            hardwareBuffer = hardwareBuffer,
                            width = width,
                            height = height,
                            format = format,
                            dataSpace = dataSpace,
                            appliedProfile = offscreenInputDynamicRangeProfile,
                            presentationTimeNs = submittedPresentationTimeNs,
                            uniforms = renderBundle.enhancementUniforms,
                            sensorTimestampNs = sensorTimestampNs,
                            niv99Tuning = niv99Tuning
                        )
                    }
                }
            } else {
                // ═════════════════════════════════════════════════════════════
                // OPTIMIZED: AtomicLong generation token eliminates expensive
                // hashCode(), reducing per-frame config overhead.
                // ═════════════════════════════════════════════════════════════
                val currentGeneration = configGenerationToken.get()
                val shouldUpdateConfig = configDirty ||
                    framesSinceConfigUpdate >= configUpdateBatchFrames() ||
                    currentGeneration != lastConfigGeneration.get()

                if (shouldUpdateConfig) {
                    val nativeConfigAccepted = bridge.updateRenderConfig(
                        previewTransform = configSnapshot.previewTransform,
                        encoderTransform = configSnapshot.encoderTransform,
                        colorGain = configSnapshot.colorGain,
                        previewGrade = configSnapshot.previewGrade,
                        encoderGrade = configSnapshot.encoderGrade,
                        previewColorScience = configSnapshot.previewColorScience,
                        encoderColorScience = configSnapshot.encoderColorScience
                    )
                    if (!nativeConfigAccepted) {
                        configDirty = true
                        Log.e(TAG, "Vulkan render config rejected during frame submission")
                        return rejectCameraImageSubmission(
                            "native-render-config-rejected",
                            recordingSubmissionExpected
                        )
                    }
                    lastRenderConfig = configSnapshot
                    lastConfigGeneration.set(currentGeneration)
                    framesSinceConfigUpdate = 0
                    configDirty = false
                } else {
                    framesSinceConfigUpdate++
                }

                applyDormantSpatialEnhancementFlags()
                if (!pushEnhancementUniformsIfNeeded()) {
                    return rejectCameraImageSubmission(
                        "enhancement-uniform-upload-rejected",
                        recordingSubmissionExpected
                    )
                }

                if (currentNdrRenderBundleSnapshot()?.identity != renderBundle.identity
                ) return rejectCameraImageSubmission(
                    "render-bundle-changed-before-core-submit",
                    recordingSubmissionExpected
                )
                bridge.submitHardwareBuffer(
                    hardwareBuffer = hardwareBuffer,
                    width = width,
                    height = height,
                    format = format,
                    presentationTimeNs = effectivePresentationTimeNs,
                    sensorTimestampNs = sensorTimestampNs,
                    niv99Tuning = niv99Tuning
                )
            }
            if (offscreenSkippedByPacing || offscreenSubmissionPaused) {
                return acceptCameraImageSubmission(
                    reason = if (offscreenSubmissionPaused) {
                        "offscreen-prearm-consumed"
                    } else {
                        "offscreen-pacing-consumed"
                    },
                    recordingSubmissionExpected = recordingSubmissionExpected,
                    waitedForProfileIngress = recordingSubmissionExpected && !ingressWasOpen
                )
            }
            if (offscreenLiveSubmissionAttempt > 0L) {
                if (imported) {
                    val accepted = offscreenEncoderLiveSubmissionAccepted.incrementAndGet()
                    if (accepted == 1L || accepted % 60L == 0L) {
                        runCatching {
                            NeoLogger.i(
                                "VulkanEncoder",
                                "OFFSCREEN_CAMERA_LIVE_ACCEPTED " +
                                    "attempt=$offscreenLiveSubmissionAttempt accepted=$accepted " +
                                    "sensorTimestampNs=$sensorTimestampNs " +
                                    "presentationTimeNs=$submittedPresentationTimeNs " +
                                    "extent=${width}x$height format=$format"
                            )
                        }
                    }
                } else {
                    val rejected = offscreenEncoderLiveSubmissionRejected.incrementAndGet()
                    runCatching {
                        NeoLogger.e(
                            "VulkanEncoder",
                            "OFFSCREEN_CAMERA_LIVE_REJECTED " +
                                "attempt=$offscreenLiveSubmissionAttempt rejected=$rejected " +
                                "sensorTimestampNs=$sensorTimestampNs " +
                                "presentationTimeNs=$submittedPresentationTimeNs " +
                                "native=[${offscreenAhbEglEncoderBridge.summary()}]"
                        )
                    }
                }
            }
            if (imported) {
                presentationClock.recordGpuCompletion(
                    sensorTimestampNs = sensorTimestampNs,
                    completionNs = System.nanoTime()
                )
                emitFrameLineageSubmission(
                    sensorTimestampNs = sensorTimestampNs,
                    presentationTimeNs = submittedPresentationTimeNs,
                    previewSubmitted = boundPreviewSurface != null,
                    encoderSubmitted = if (offscreenAhbEglEncoderActive) {
                        boundOffscreenEncoderSurface != null
                    } else {
                        boundEncoderSurface != null && _encoderSurfaceStatus.value.bound
                    },
                    cameraSessionGeneration = cameraSessionGeneration,
                    rawSensorTimestampNs = rawSensorTimestampNs
                )
                if (offscreenAhbEglEncoderActive &&
                    !_encoderSurfaceStatus.value.sourcePrecisionProven
                ) {
                    refreshCapabilitySummary()
                }
            }

            val now = SystemClock.elapsedRealtime()
            val baseStatsUpdate = if (imported) {
                importStatsAccumulator.recordSuccessfulImport(
                    width = width,
                    height = height,
                    format = format,
                    elapsedMs = now
                )
            } else {
                importStatsAccumulator.recordFailedImport().also { update ->
                    if (update.shouldForceConfigRefresh) {
                        configDirty = true
                    }
                    if (update.stats.sessionFailedImports == 1L || update.shouldForceConfigRefresh) {
                        refreshCapabilitySummary()
                    }
                }
            }
            val statsUpdate = if (offscreenAhbEglEncoderActive) {
                importStatsAccumulator.mutate { current ->
                    current.copy(
                        offscreenAhbEglEncoderActive = true,
                        offscreenEglEncoderSurfaceBound = boundOffscreenEncoderSurface != null,
                        encoderSurfaceBound = boundOffscreenEncoderSurface != null,
                        offscreenEglSubmittedFrames =
                            if (imported) current.offscreenEglSubmittedFrames + 1 else current.offscreenEglSubmittedFrames,
                        offscreenEglFailedFrames =
                            if (imported) current.offscreenEglFailedFrames else current.offscreenEglFailedFrames + 1,
                        offscreenEglPresentationTimeSet =
                            current.offscreenEglPresentationTimeSet || submittedPresentationTimeNs > 0L
                    )
                }
            } else {
                baseStatsUpdate
            }

            if (OffscreenImportStatsPublishPolicy.shouldPublish(
                    shouldEmitStats = statsUpdate.shouldEmitStats,
                    offscreenRecordingActive = offscreenAhbEglEncoderActive,
                    imported = imported,
                    stats = statsUpdate.stats,
                    nowElapsedMs = now,
                    lastPublishedElapsedMs = lastOffscreenImportStatsPublishElapsedMs
                )
            ) {
                _importStats.value = statsUpdate.stats
                if (offscreenAhbEglEncoderActive) {
                    lastOffscreenImportStatsPublishElapsedMs = now
                }
            }

            if (statsUpdate.shouldRefreshCapabilities) {
                refreshCapabilitySummary()
            }

            if (imported) {
                acceptCameraImageSubmission(
                    reason = "native-import-accepted",
                    recordingSubmissionExpected = recordingSubmissionExpected,
                    waitedForProfileIngress = recordingSubmissionExpected && !ingressWasOpen
                )
            } else {
                rejectCameraImageSubmission(
                    reason = if (offscreenAhbEglEncoderActive) {
                        "offscreen-native-submit-rejected"
                    } else {
                        "vulkan-native-import-rejected"
                    },
                    recordingSubmissionExpected = recordingSubmissionExpected,
                    waitedForProfileIngress = recordingSubmissionExpected && !ingressWasOpen
                )
            }
        } catch (throwable: Throwable) {
            if (offscreenLiveSubmissionAttempt > 0L) {
                offscreenEncoderLiveSubmissionRejected.incrementAndGet()
            }
            configDirty = true
            val statsUpdate = importStatsAccumulator.recordFailedImport()
            _importStats.value = statsUpdate.stats
            if (statsUpdate.shouldForceConfigRefresh) {
                configDirty = true
            }
            Log.w(TAG, "Failed to submit camera image into Vulkan runtime", throwable)
            rejectCameraImageSubmission(
                "submission-exception:${throwable.javaClass.simpleName}",
                recordingSubmissionExpected,
                waitedForProfileIngress = recordingSubmissionExpected && !ingressWasOpen
            )
        } finally {
            hardwareBuffer.close()
        }
        } finally {
            ndrFrameLease.close()
        }
        } finally {
            importSessionLease.close()
        }
    }

    private fun recordingProfileTransitionWaitNs(targetFps: Int): Long {
        val frameIntervalNs = 1_000_000_000L / targetFps.coerceAtLeast(1).toLong()
        return (frameIntervalNs * RECORDING_PROFILE_TRANSITION_WAIT_FRAMES)
            .coerceAtMost(MAX_RECORDING_PROFILE_TRANSITION_WAIT_NS)
    }

    private fun acceptCameraImageSubmission(
        reason: String,
        recordingSubmissionExpected: Boolean,
        waitedForProfileIngress: Boolean = false
    ): Boolean {
        cameraImageSubmissionOutcome.set(
            CameraImageSubmissionOutcome(
                accepted = true,
                recordingSubmissionExpected = recordingSubmissionExpected,
                reason = reason,
                waitedForProfileIngress = waitedForProfileIngress
            )
        )
        return true
    }

    private fun rejectCameraImageSubmission(
        reason: String,
        recordingSubmissionExpected: Boolean,
        waitedForProfileIngress: Boolean = false
    ): Boolean {
        cameraImageSubmissionOutcome.set(
            CameraImageSubmissionOutcome(
                accepted = false,
                recordingSubmissionExpected = recordingSubmissionExpected,
                reason = reason,
                waitedForProfileIngress = waitedForProfileIngress
            )
        )
        return false
    }

    private fun emitFrameLineageSubmission(
        sensorTimestampNs: Long,
        presentationTimeNs: Long,
        previewSubmitted: Boolean,
        encoderSubmitted: Boolean,
        cameraSessionGeneration: Long = -1L,
        rawSensorTimestampNs: Long = sensorTimestampNs
    ) {
        if (sensorTimestampNs <= 0L) return
        if (!previewSubmitted && !encoderSubmitted) return
        if (cameraSessionGeneration >= 0L &&
            !isSensorLinearNdrSessionGenerationCurrent(cameraSessionGeneration)
        ) return
        val listener = frameLineageSubmissionListener ?: return
        runCatching {
            listener(
                VulkanFrameLineageSubmission(
                    sensorTimestampNs = sensorTimestampNs,
                    presentationTimeNs = presentationTimeNs,
                    previewSubmitted = previewSubmitted,
                    encoderSubmitted = encoderSubmitted,
                    cameraSessionGeneration = cameraSessionGeneration,
                    rawSensorTimestampNs = rawSensorTimestampNs
                )
            )
        }.onFailure { throwable ->
            Log.w(TAG, "Frame-lineage submission listener failed", throwable)
        }
    }

    /**
     * ════════════════════════════════════════════════════════════════════════
     * 12-BIT RAW SUBMISSION — CINEMA DEBAYER PIPELINE
     * ════════════════════════════════════════════════════════════════════════
     *
     * Submits a RAW_SENSOR image through the Cinema-Debayer compute shader
     * for professional-grade demosaicing with highlight latitude recovery.
     * This bypasses the default Android ISP tone-mapping pipeline entirely.
     *
     * Requirements:
     * - Cinema debayer pipeline must be active (cinemaDebayer.isActive())
     * - Image must be RAW_SENSOR format (12-bit or 16-bit)
     * - Vulkan runtime must be initialized and supported
     */
    @Volatile private var loggedRawSubmitDiag = false

    internal fun submitRawSensorImage(
        image: android.media.Image,
        descriptor: RawBufferDescriptor,
        presentationEpoch: Long,
        cameraSessionGeneration: Long,
        onCompletedFrame: (RawDemosaicFrameIdentity) -> Unit
    ): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        if (!isSensorLinearNdrSessionGenerationCurrent(cameraSessionGeneration)) return false
        // Ordered NDR recording owns the native demosaic export ring while armed. Idle preview is
        // latest-frame-wins and may be skipped, but it must never race or overwrite a recorder frame.
        if (sensorLinearNdrDemosaicOwnerPhase != SensorLinearNdrDemosaicOwnerPhase.PREVIEW &&
            sensorLinearNdrDemosaicOwnerPhase != SensorLinearNdrDemosaicOwnerPhase.RETIRING_FAILED
        ) return false
        val supported = _supported.value
        val active = cinemaDebayer.isActive()
        if (!supported || !active) {
            if (!loggedRawSubmitDiag) {
                loggedRawSubmitDiag = true
                android.util.Log.i("VulkanRuntime", "RAW debayer gated: supported=$supported active=$active")
            }
            return false
        }
        val frameLease = ndrNativeFrameBoundary.acquireFrameLease() ?: return false
        return try {
            val dispatchRenderBundle = currentNdrRenderBundleSnapshot() ?: return false
            val frame = RawDemosaicFrameIdentity(
                presentationEpoch = presentationEpoch,
                sensorTimestampNs = runCatching { image.timestamp }.getOrDefault(0L),
                width = descriptor.width,
                height = descriptor.height,
                cameraSessionGeneration = cameraSessionGeneration,
                renderProfileIdentity = dispatchRenderBundle.identity,
                renderEnhancementUniforms = dispatchRenderBundle.enhancementUniforms,
                idleCompletionRoute = RawDemosaicCompletionRoute(onCompletedFrame),
                projectOwnedNdrColorProof = descriptor.projectOwnedNdrColorProof
            )
            synchronized(rawDemosaicCompletionLock) {
            if (!isSensorLinearNdrSessionGenerationCurrent(cameraSessionGeneration)) {
                return@synchronized false
            }
            if (!prepareRawPreviewOwnershipLocked()) return@synchronized false
            // Poll before dispatch. COMPLETE is copied to every consumer under its original identity
            // first; each project-owned GL submission returns a completion barrier, then this same worker
            // turn dispatches the current frame so encoder cadence is not reduced.
            val beforePoll = bridge.pollRawDemosaicCompletion()
            val beforeCompletionObservedElapsedRealtimeNs =
                if (beforePoll == RawDemosaicCompletionPoll.COMPLETE) {
                    SystemClock.elapsedRealtimeNanos()
                } else {
                    0L
                }
            val before = rawDemosaicCompletionHandoff.beforeDispatch(
                poll = beforePoll,
                completionObservedElapsedRealtimeNs = beforeCompletionObservedElapsedRealtimeNs
            )
            var completionPublished = false
            var encoderDelivered = false
            before.completedFrame?.let { completed ->
                encoderDelivered = publishCompletedRawDemosaicFrame(completed)
                completionPublished = encoderDelivered &&
                    acknowledgeRawDemosaicCompletion(completed)
                if (!completionPublished) return@synchronized false
            }
            if (ndrRenderProfileMutationDeferral.pendingIdentity() != null) {
                publishDeferredNdrRenderProfileIfRawDrainedLocked()
                // Idle preview is latest-frame-wins. Do not start another old-profile dispatch while
                // the serialized render worker is about to apply the retained replacement.
                return@synchronized false
            }
            if (!before.shouldDispatchCurrent) {
                return@synchronized false
            }

            if (!isSensorLinearNdrSessionGenerationCurrent(cameraSessionGeneration)) {
                return@synchronized false
            }
            // Surface binding/unbinding can occur on a different callback; synchronize the native extent
            // and pack gates immediately before this RAW dispatch without issuing per-frame JNI once stable.
            syncRawSceneLogTransportState()
            val dispatchStartedElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
            val imageConsumed = cinemaDebayer.submitRawImage(
                image = image,
                descriptor = descriptor,
                bridge = bridge,
                completionWaitNs = VulkanBridge.DEFAULT_RAW_DEMOSAIC_COMPLETION_WAIT_NS,
                recordSynchronousCompletion = false
            )
            val synchronousCompletionObservedElapsedRealtimeNs = if (imageConsumed) {
                SystemClock.elapsedRealtimeNanos()
            } else {
                0L
            }
            val submittedFrame = frame.copy(
                demosaicDispatchStartedElapsedRealtimeNs = dispatchStartedElapsedRealtimeNs,
                demosaicCompletionStatsExternallyOwned = true
            )
            val pollAfterDeadline = if (imageConsumed) {
                RawDemosaicCompletionPoll.IDLE
            } else {
                bridge.pollRawDemosaicCompletion()
            }
            val completionObservedElapsedRealtimeNs = when {
                imageConsumed -> synchronousCompletionObservedElapsedRealtimeNs
                pollAfterDeadline == RawDemosaicCompletionPoll.COMPLETE ->
                    SystemClock.elapsedRealtimeNanos()
                else -> 0L
            }
            val completed = rawDemosaicCompletionHandoff.afterDispatch(
                frame = submittedFrame,
                completedSynchronously = imageConsumed,
                pollAfterDeadline = pollAfterDeadline,
                completionObservedElapsedRealtimeNs = completionObservedElapsedRealtimeNs
            )
            completed?.let { completedFrame ->
                encoderDelivered = publishCompletedRawDemosaicFrame(completedFrame)
                completionPublished = encoderDelivered &&
                    acknowledgeRawDemosaicCompletion(completedFrame)
            }
            if (!loggedRawSubmitDiag) {
                loggedRawSubmitDiag = true
                android.util.Log.i(
                    "VulkanRuntime",
                    "RAW debayer first dispatch imageConsumed=$imageConsumed " +
                        "completionPublished=$completionPublished sensorLinearEncoderDelivered=$encoderDelivered"
                )
            }
            // Image ownership remains independent from completion. A deadline-overrun retains its own
            // native AHardwareBuffer reference, so RawVideoManager may close the current Image on false.
                imageConsumed
            }
        } finally {
            frameLease.close()
        }
    }

    /**
     * Disarms skippable RAW preview submissions and retires any preview-owned native dispatch before
     * ordered NDR recording starts. Call repeatedly from a coroutine while [PENDING]; this method never
     * waits on a GPU fence.
     */
    internal fun beginSensorLinearNdrRecordingDemosaicTransition():
        SensorLinearNdrDemosaicTransitionStatus {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            !_supported.value || !cinemaDebayer.isActive()
        ) {
            return SensorLinearNdrDemosaicTransitionStatus.FAILED
        }
        val frameLease = ndrNativeFrameBoundary.acquireFrameLease()
            ?: return SensorLinearNdrDemosaicTransitionStatus.FAILED
        return try {
            synchronized(rawDemosaicCompletionLock) {
            when (sensorLinearNdrDemosaicOwnerPhase) {
                SensorLinearNdrDemosaicOwnerPhase.PREVIEW -> {
                    sensorLinearNdrDemosaicOwnerPhase =
                        SensorLinearNdrDemosaicOwnerPhase.TRANSITION
                }
                SensorLinearNdrDemosaicOwnerPhase.TRANSITION -> Unit
                SensorLinearNdrDemosaicOwnerPhase.RECORDING -> {
                    // A committed owner belongs to an already-running clip. Never treat it as the
                    // transition for a new clip; doing so could publish an old tail into a new encoder.
                    return@synchronized SensorLinearNdrDemosaicTransitionStatus.FAILED
                }
                SensorLinearNdrDemosaicOwnerPhase.RETIRING_FAILED -> {
                    when (retireFailedSensorLinearNdrTailLocked()) {
                        SensorLinearNdrFailedTailRetirementStatus.PENDING,
                        SensorLinearNdrFailedTailRetirementStatus.ENCODER_STILL_BOUND ->
                            return@synchronized SensorLinearNdrDemosaicTransitionStatus.PENDING
                        SensorLinearNdrFailedTailRetirementStatus.TERMINAL_NATIVE_FAILURE ->
                            return@synchronized SensorLinearNdrDemosaicTransitionStatus.FAILED
                        SensorLinearNdrFailedTailRetirementStatus.RETIRED -> {
                            sensorLinearNdrDemosaicOwnerPhase =
                                SensorLinearNdrDemosaicOwnerPhase.TRANSITION
                        }
                    }
                }
                SensorLinearNdrDemosaicOwnerPhase.TERMINAL_NATIVE_FAILURE ->
                    return@synchronized SensorLinearNdrDemosaicTransitionStatus.FAILED
            }
            val poll = bridge.pollRawDemosaicCompletion()
            val completionObservedElapsedRealtimeNs =
                if (poll == RawDemosaicCompletionPoll.COMPLETE) {
                    SystemClock.elapsedRealtimeNanos()
                } else {
                    0L
                }
            when (poll) {
                RawDemosaicCompletionPoll.PENDING ->
                    SensorLinearNdrDemosaicTransitionStatus.PENDING
                RawDemosaicCompletionPoll.COMPLETE,
                RawDemosaicCompletionPoll.IDLE -> {
                    // A completed frame here belongs to the idle monitor epoch that preceded record.
                    // Retire its software identity without feeding it to the newly armed encoder.
                    val decision = rawDemosaicCompletionHandoff.beforeDispatch(
                        poll = poll,
                        completionObservedElapsedRealtimeNs = completionObservedElapsedRealtimeNs
                    )
                    val lostIdentity =
                        (poll == RawDemosaicCompletionPoll.COMPLETE && decision.completedFrame == null) ||
                            (poll == RawDemosaicCompletionPoll.IDLE &&
                                rawDemosaicCompletionHandoff.pendingIdentity() != null)
                    if (lostIdentity) {
                        rawDemosaicCompletionHandoff.quarantine(
                            if (poll == RawDemosaicCompletionPoll.COMPLETE) {
                                RawDemosaicOwnershipQuarantineReason.NATIVE_COMPLETION_LOST_IDENTITY
                            } else {
                                RawDemosaicOwnershipQuarantineReason
                                    .NATIVE_BECAME_IDLE_WITH_PENDING_IDENTITY
                            }
                        )
                        sensorLinearNdrDemosaicOwnerPhase =
                            SensorLinearNdrDemosaicOwnerPhase.TERMINAL_NATIVE_FAILURE
                        return@synchronized SensorLinearNdrDemosaicTransitionStatus.FAILED
                    }
                    if (!discardCompletedRawDemosaicOwnershipLocked()) {
                        sensorLinearNdrDemosaicOwnerPhase =
                            SensorLinearNdrDemosaicOwnerPhase.TERMINAL_NATIVE_FAILURE
                        return@synchronized SensorLinearNdrDemosaicTransitionStatus.FAILED
                    }
                    // The phase changed to TRANSITION before this barrier, so no new idle NDR submit can
                    // enter. Wait for the one outgoing EGL/glFinish call, if any, before recorder preview
                    // begins using the same serial EGL bridge. This is a start-boundary barrier only; it
                    // is never taken on the per-frame recording path.
                    synchronized(sensorLinearNdrPreviewLock) { Unit }
                    sensorLinearNdrDemosaicOwnerPhase =
                        SensorLinearNdrDemosaicOwnerPhase.RECORDING
                    SensorLinearNdrDemosaicTransitionStatus.READY
                }
                RawDemosaicCompletionPoll.FAILED -> {
                    sensorLinearNdrDemosaicOwnerPhase =
                        SensorLinearNdrDemosaicOwnerPhase.TERMINAL_NATIVE_FAILURE
                    SensorLinearNdrDemosaicTransitionStatus.FAILED
                }
            }
        }
        } finally {
            frameLease.close()
        }
    }

    /**
     * Pre-creates the exact full-sensor recorder resources after preview-tail retirement and before
     * RAW ImageReader admission. The caller owns thread placement; this method performs synchronous
     * Vulkan allocation so its completion is an actual readiness proof, not an async intent flag.
     */
    internal fun prepareSensorLinearNdrRecordingDemosaicResources(
        width: Int,
        height: Int
    ): Boolean {
        val frameLease = ndrNativeFrameBoundary.acquireFrameLease() ?: return false
        return try {
            synchronized(rawDemosaicCompletionLock) {
                if (width <= 0 || height <= 0 ||
                    sensorLinearNdrDemosaicOwnerPhase != SensorLinearNdrDemosaicOwnerPhase.RECORDING ||
                    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
                    !_supported.value || !cinemaDebayer.isActive()
                ) return@synchronized false

        // Publish recorder ownership to native before asking it to allocate the final extent.
        syncRawSceneLogTransportState()
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val prepared = bridge.prepareRawRecordingDemosaicResources(width, height)
        var eglPrewarmAttempts = 0
        var eglPrewarmSuccesses = 0
        if (prepared && offscreenAhbEglEncoderActive &&
            !offscreenEncoderDetachQuarantined && boundOffscreenEncoderSurface != null
        ) {
            repeat(RAW_RECORDING_TRANSPORT_RING_SLOTS) { slot ->
                val output = bridge.acquireRawRecordingDemosaicPrewarmHardwareBuffer(slot)
                if (output == null) {
                    Log.w(TAG, "NDR recording EGL prewarm slot=$slot unavailable; retaining live path")
                    return@repeat
                }
                ++eglPrewarmAttempts
                val warmed = try {
                    output.width == width && output.height == height &&
                        (output.format == HardwareBuffer.RGBA_FP16 ||
                            output.format == HardwareBuffer.RGBA_1010102) &&
                        offscreenAhbEglEncoderBridge.prewarmProjectOwnedRawTransport(
                            hardwareBuffer = output,
                            width = width,
                            height = height,
                            slot = slot
                        )
                } catch (throwable: Throwable) {
                    if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
                    Log.w(TAG, "NDR recording EGL prewarm slot=$slot failed", throwable)
                    false
                } finally {
                    output.close()
                }
                if (warmed) ++eglPrewarmSuccesses
            }
        }
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0L) /
            1_000_000L
        Log.i(
            TAG,
            "NDR recording RAW resource prewarm prepared=$prepared extent=${width}x$height " +
                "eglAttempts=$eglPrewarmAttempts eglSuccesses=$eglPrewarmSuccesses " +
                "eglComplete=${eglPrewarmSuccesses == RAW_RECORDING_TRANSPORT_RING_SLOTS} " +
                "elapsedMs=$elapsedMs"
        )
                prepared
            }
        } finally {
            frameLease.close()
        }
    }

    /**
     * Non-blocking readiness probe used by the recording-start coroutine. It must remain outside
     * [rawDemosaicCompletionLock]: the native background builder needs to finish and publish its
     * release-store while the caller asynchronously waits.
     */
    internal fun isRawDemosaicPipelineReady(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            _supported.value &&
            cinemaDebayer.isActive() &&
            bridge.isRawDemosaicPipelineReady()

    /** Cancels only an uncommitted preview-to-recording ownership reservation. */
    internal fun abortSensorLinearNdrRecordingDemosaicTransition(): Boolean =
        synchronized(rawDemosaicCompletionLock) {
            if (sensorLinearNdrDemosaicOwnerPhase !=
                SensorLinearNdrDemosaicOwnerPhase.TRANSITION
            ) {
                return@synchronized false
            }
            // Preserve any preview-owned pending/completed identity. Once its native fence resolves,
            // the ordinary preview worker may publish it under the original epoch and timestamp.
            sensorLinearNdrDemosaicOwnerPhase = SensorLinearNdrDemosaicOwnerPhase.PREVIEW
            true
        }

    /**
     * Releases recorder ownership only after RawVideoManager has drained every accepted frame and its
     * final native completion. A pending fence keeps the gate armed and is reported as a hard failure.
     */
    internal fun endSensorLinearNdrRecordingDemosaic(): Boolean =
        synchronized(rawDemosaicCompletionLock) {
            if (sensorLinearNdrDemosaicOwnerPhase ==
                SensorLinearNdrDemosaicOwnerPhase.PREVIEW
            ) return@synchronized true
            if (sensorLinearNdrDemosaicOwnerPhase ==
                SensorLinearNdrDemosaicOwnerPhase.TERMINAL_NATIVE_FAILURE
            ) return@synchronized false
            if (sensorLinearNdrDemosaicOwnerPhase !=
                SensorLinearNdrDemosaicOwnerPhase.RECORDING
            ) return@synchronized false
            if (!isSensorLinearNdrEncoderDetached()) {
                Log.e(TAG, "Cannot release NDR recording demosaic ownership while encoder is bound")
                return@synchronized false
            }
            val poll = bridge.pollRawDemosaicCompletion()
            val completionObservedElapsedRealtimeNs =
                if (poll == RawDemosaicCompletionPoll.COMPLETE) {
                    SystemClock.elapsedRealtimeNanos()
                } else {
                    0L
                }
            val decision = rawDemosaicCompletionHandoff.beforeDispatch(
                poll = poll,
                completionObservedElapsedRealtimeNs = completionObservedElapsedRealtimeNs
            )
            if (poll == RawDemosaicCompletionPoll.FAILED) {
                sensorLinearNdrDemosaicOwnerPhase =
                    SensorLinearNdrDemosaicOwnerPhase.TERMINAL_NATIVE_FAILURE
                Log.e(TAG, "Native RAW demosaic completion failed during recording ownership release")
                return@synchronized false
            }
            if (poll == RawDemosaicCompletionPoll.PENDING ||
                poll == RawDemosaicCompletionPoll.COMPLETE ||
                decision.completedFrame != null ||
                rawDemosaicCompletionHandoff.hasOutstandingIdentity() ||
                sensorLinearNdrEncoderInputReads.outstandingCount != 0 ||
                sensorLinearNdrEncoderInputReads.isQuarantined
            ) {
                Log.e(TAG, "Cannot release NDR recording demosaic ownership with an unpublished tail")
                return@synchronized false
            }
            if (!rawDemosaicCompletionHandoff.resetIfUnowned()) return@synchronized false
            sensorLinearNdrDemosaicOwnerPhase = SensorLinearNdrDemosaicOwnerPhase.PREVIEW
            true
        }

    /**
     * Quarantines a failed clip tail after its encoder surface is detached. PENDING never destroys or
     * overwrites native resources; later preview callbacks or a later transition poll the same fence.
     * Once COMPLETE, the invalid tail is intentionally discarded and can never enter another clip.
     */
    internal fun retireFailedSensorLinearNdrRecordingTail():
        SensorLinearNdrFailedTailRetirementStatus = synchronized(rawDemosaicCompletionLock) {
            retireFailedSensorLinearNdrTailLocked()
        }

    private fun retireFailedSensorLinearNdrTailLocked():
        SensorLinearNdrFailedTailRetirementStatus {
        if (sensorLinearNdrDemosaicOwnerPhase ==
            SensorLinearNdrDemosaicOwnerPhase.TERMINAL_NATIVE_FAILURE
        ) return SensorLinearNdrFailedTailRetirementStatus.TERMINAL_NATIVE_FAILURE
        if (sensorLinearNdrDemosaicOwnerPhase ==
            SensorLinearNdrDemosaicOwnerPhase.PREVIEW
        ) return SensorLinearNdrFailedTailRetirementStatus.RETIRED
        if (!isSensorLinearNdrEncoderDetached()) {
            return SensorLinearNdrFailedTailRetirementStatus.ENCODER_STILL_BOUND
        }

        sensorLinearNdrDemosaicOwnerPhase =
            SensorLinearNdrDemosaicOwnerPhase.RETIRING_FAILED
        val poll = bridge.pollRawDemosaicCompletion()
        val completionObservedElapsedRealtimeNs =
            if (poll == RawDemosaicCompletionPoll.COMPLETE) {
                SystemClock.elapsedRealtimeNanos()
            } else {
                0L
            }
        val decision = rawDemosaicCompletionHandoff.beforeDispatch(
            poll = poll,
            completionObservedElapsedRealtimeNs = completionObservedElapsedRealtimeNs
        )
        return when (poll) {
            RawDemosaicCompletionPoll.PENDING ->
                SensorLinearNdrFailedTailRetirementStatus.PENDING
            RawDemosaicCompletionPoll.COMPLETE -> {
                if (decision.completedFrame == null) {
                    rawDemosaicCompletionHandoff.quarantine(
                        RawDemosaicOwnershipQuarantineReason.NATIVE_COMPLETION_LOST_IDENTITY
                    )
                    sensorLinearNdrDemosaicOwnerPhase =
                        SensorLinearNdrDemosaicOwnerPhase.TERMINAL_NATIVE_FAILURE
                    return SensorLinearNdrFailedTailRetirementStatus.TERMINAL_NATIVE_FAILURE
                }
                // COMPLETE is a one-shot native result. Bind it to its exact old identity, then discard
                // both together because the clip has already failed and its encoder is gone.
                if (!discardCompletedRawDemosaicOwnershipLocked()) {
                    sensorLinearNdrDemosaicOwnerPhase =
                        SensorLinearNdrDemosaicOwnerPhase.TERMINAL_NATIVE_FAILURE
                    return SensorLinearNdrFailedTailRetirementStatus.TERMINAL_NATIVE_FAILURE
                }
                sensorLinearNdrDemosaicOwnerPhase =
                    SensorLinearNdrDemosaicOwnerPhase.PREVIEW
                SensorLinearNdrFailedTailRetirementStatus.RETIRED
            }
            RawDemosaicCompletionPoll.IDLE -> {
                val orphanedPending = rawDemosaicCompletionHandoff.pendingIdentity() != null
                if (orphanedPending) {
                    rawDemosaicCompletionHandoff.quarantine(
                        RawDemosaicOwnershipQuarantineReason
                            .NATIVE_BECAME_IDLE_WITH_PENDING_IDENTITY
                    )
                    sensorLinearNdrDemosaicOwnerPhase =
                        SensorLinearNdrDemosaicOwnerPhase.TERMINAL_NATIVE_FAILURE
                    Log.e(TAG, "Native RAW dispatch became IDLE while failed-tail identity was pending")
                    SensorLinearNdrFailedTailRetirementStatus.TERMINAL_NATIVE_FAILURE
                } else {
                    // Includes a retained completed identity from an earlier rejected publication.
                    if (!discardCompletedRawDemosaicOwnershipLocked()) {
                        sensorLinearNdrDemosaicOwnerPhase =
                            SensorLinearNdrDemosaicOwnerPhase.TERMINAL_NATIVE_FAILURE
                        return SensorLinearNdrFailedTailRetirementStatus.TERMINAL_NATIVE_FAILURE
                    }
                    sensorLinearNdrDemosaicOwnerPhase =
                        SensorLinearNdrDemosaicOwnerPhase.PREVIEW
                    SensorLinearNdrFailedTailRetirementStatus.RETIRED
                }
            }
            RawDemosaicCompletionPoll.FAILED -> {
                sensorLinearNdrDemosaicOwnerPhase =
                    SensorLinearNdrDemosaicOwnerPhase.TERMINAL_NATIVE_FAILURE
                SensorLinearNdrFailedTailRetirementStatus.TERMINAL_NATIVE_FAILURE
            }
        }
    }

    /** Retires only fence-complete lanes; caller must hold [rawDemosaicCompletionLock]. */
    private fun discardCompletedRawDemosaicOwnershipLocked(): Boolean {
        check(Thread.holdsLock(rawDemosaicCompletionLock))
        if (rawDemosaicCompletionHandoff.pendingIdentity() != null) {
            rawDemosaicCompletionHandoff.quarantine(
                RawDemosaicOwnershipQuarantineReason.NATIVE_BECAME_IDLE_WITH_PENDING_IDENTITY
            )
            return false
        }
        val expected = rawDemosaicCompletionHandoff.completedIdentityCount()
        if (expected == 0) {
            return rawDemosaicCompletionHandoff.resetIfUnowned()
        }
        val discarded = bridge.discardCompletedRawDemosaicOutputs()
        if (discarded != expected) {
            val quarantine = rawDemosaicCompletionHandoff.quarantine(
                RawDemosaicOwnershipQuarantineReason.NATIVE_RETIREMENT_COUNT_MISMATCH
            )
            Log.e(
                TAG,
                "RAW transport retirement mismatch expected=$expected discarded=$discarded " +
                    "reason=${quarantine.reason} identities=${quarantine.completedIdentities.size}"
            )
            return false
        }
        rawDemosaicCompletionHandoff.retireAfterNativeRetirementProof()
        return true
    }

    private fun isSensorLinearNdrEncoderDetached(): Boolean =
        !offscreenAhbEglEncoderActive &&
            !offscreenEncoderDetachQuarantined &&
            !offscreenAhbEglSubmissionEnabled.get() &&
            boundOffscreenEncoderSurface == null

    /** Reaps a quarantined failed tail opportunistically without delaying a camera callback. */
    private fun prepareRawPreviewOwnershipLocked(): Boolean =
        when (sensorLinearNdrDemosaicOwnerPhase) {
            SensorLinearNdrDemosaicOwnerPhase.PREVIEW -> true
            SensorLinearNdrDemosaicOwnerPhase.RETIRING_FAILED ->
                retireFailedSensorLinearNdrTailLocked() ==
                    SensorLinearNdrFailedTailRetirementStatus.RETIRED
            SensorLinearNdrDemosaicOwnerPhase.TRANSITION,
            SensorLinearNdrDemosaicOwnerPhase.RECORDING,
            SensorLinearNdrDemosaicOwnerPhase.TERMINAL_NATIVE_FAILURE -> false
        }

    internal fun isSensorLinearNdrRecordingDemosaicActive(): Boolean =
        sensorLinearNdrDemosaicOwnerPhase != SensorLinearNdrDemosaicOwnerPhase.PREVIEW

    /**
     * Submits one ordered RAW_SENSOR frame without transferring Java Image ownership. Native code takes
     * its own AHardwareBuffer reference only when a dispatch outlives the bounded fence deadline, so the
     * recorder may close the Image immediately after [ACCEPTED].
     */
    internal fun submitSensorLinearNdrRecordingImage(
        image: android.media.Image,
        descriptor: RawBufferDescriptor,
        presentationEpoch: Long,
        recordingIdentity: SensorLinearNdrRecordingFrameIdentity
    ): SensorLinearNdrDemosaicSubmission {
        if (sensorLinearNdrDemosaicOwnerPhase != SensorLinearNdrDemosaicOwnerPhase.RECORDING) {
            return SensorLinearNdrDemosaicSubmission(
                SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                reason = "Recorder does not own the RAW demosaic output"
            )
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            !_supported.value || !cinemaDebayer.isActive()
        ) {
            return SensorLinearNdrDemosaicSubmission(
                SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                reason = "RAW Vulkan demosaic is unavailable"
            )
        }
        val frameLease = ndrNativeFrameBoundary.acquireFrameLease()
            ?: return retireSensorLinearNdrOutputDuringProfileTransition().let { retirement ->
                SensorLinearNdrDemosaicSubmission(
                    status = if (retirement.status == NdrProfileTransitionRetirementStatus.FAILED) {
                        SensorLinearNdrDemosaicSubmissionStatus.FAILED
                    } else {
                        SensorLinearNdrDemosaicSubmissionStatus.RETRY_PENDING
                    },
                    completionPending = retirement.nativeOutputOwned,
                    retryCause = SensorLinearNdrDemosaicRetryCause.RENDER_PROFILE_TRANSITION,
                    reason = retirement.reason
                )
            }
        return try {
            val dispatchRenderBundle = currentNdrRenderBundleSnapshot()
                ?: return SensorLinearNdrDemosaicSubmission(
                    SensorLinearNdrDemosaicSubmissionStatus.RETRY_PENDING,
                    retryCause = SensorLinearNdrDemosaicRetryCause.RENDER_PROFILE_TRANSITION,
                    reason = "NDR render profile is not ready"
                )
            val frame = RawDemosaicFrameIdentity(
                presentationEpoch = presentationEpoch,
                sensorTimestampNs = recordingIdentity.sensorTimestampNs,
                width = descriptor.width,
                height = descriptor.height,
                recordingSequence = recordingIdentity.sequence,
                ingressGeneration = recordingIdentity.ingressGeneration,
                cameraSessionGeneration = recordingIdentity.sessionGeneration,
                cameraFrameNumber = recordingIdentity.cameraFrameNumber,
                exposureTimeNs = descriptor.exposureTimeNs,
                analogGain = descriptor.analogGain,
                digitalGain = descriptor.digitalGain,
                whiteBalanceR = descriptor.whiteBalanceR,
                whiteBalanceGr = descriptor.whiteBalanceGr,
                whiteBalanceGb = descriptor.whiteBalanceGb,
                whiteBalanceB = descriptor.whiteBalanceB,
                renderProfileIdentity = dispatchRenderBundle.identity,
                renderEnhancementUniforms = dispatchRenderBundle.enhancementUniforms,
                projectOwnedNdrColorProof = descriptor.projectOwnedNdrColorProof
            )
            if (frame.sensorTimestampNs <= 0L) {
                return SensorLinearNdrDemosaicSubmission(
                    SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                    reason = "RAW frame has no valid sensor timestamp"
                )
            }

            synchronized(rawDemosaicCompletionLock) {
                if (sensorLinearNdrDemosaicOwnerPhase != SensorLinearNdrDemosaicOwnerPhase.RECORDING) {
                    return@synchronized SensorLinearNdrDemosaicSubmission(
                        SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                        reason = "Recorder demosaic ownership is not committed"
                    )
                }
                submitSensorLinearNdrRecordingImageLocked(
                    image = image,
                    descriptor = descriptor,
                    recordingIdentity = recordingIdentity,
                    frame = frame
                )
            }
        } finally {
            frameLease.close()
        }
    }

    /** Caller owns both the NDR frame lease and [rawDemosaicCompletionLock]. */
    private fun submitSensorLinearNdrRecordingImageLocked(
        image: android.media.Image,
        descriptor: RawBufferDescriptor,
        recordingIdentity: SensorLinearNdrRecordingFrameIdentity,
        frame: RawDemosaicFrameIdentity
    ): SensorLinearNdrDemosaicSubmission {
        check(Thread.holdsLock(rawDemosaicCompletionLock))
        if (frame.sensorTimestampNs <= 0L) {
            return SensorLinearNdrDemosaicSubmission(
                SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                reason = "RAW frame has no valid sensor timestamp"
            )
        }
        return run recordingSubmit@ {
            when (retireSensorLinearNdrEncoderInputReadsLocked(timeoutNs = 0L)) {
                EncoderInputReadRetirement.PENDING -> {
                    if (!sensorLinearNdrEncoderInputReads.hasCapacity()) {
                        return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                            SensorLinearNdrDemosaicSubmissionStatus.RETRY_PENDING,
                            completionPending = true,
                            retryCause = SensorLinearNdrDemosaicRetryCause.NATIVE_OUTPUT,
                            reason = "Both encoder input reads are awaiting their exact EGL fences"
                        )
                    }
                }
                EncoderInputReadRetirement.FAILED ->
                    return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                        SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                        completionPending = true,
                        reason = "Exact encoder input-read fence retirement failed"
                    )
                EncoderInputReadRetirement.NONE,
                EncoderInputReadRetirement.RETIRED -> Unit
            }
            val beforePoll = bridge.pollRawDemosaicCompletion()
            val beforeCompletionObservedElapsedRealtimeNs =
                if (beforePoll == RawDemosaicCompletionPoll.COMPLETE) {
                    SystemClock.elapsedRealtimeNanos()
                } else {
                    0L
                }
            val before = rawDemosaicCompletionHandoff.beforeDispatch(
                poll = beforePoll,
                completionObservedElapsedRealtimeNs = beforeCompletionObservedElapsedRealtimeNs
            )
            val completedBeforeDispatch = before.completedFrame
            when (beforePoll) {
                RawDemosaicCompletionPoll.PENDING -> {
                    // A prior encoder rejection can leave the older completed lane retryable while the
                    // second lane is still in Vulkan. Publish it now, but do not accept another RAW.
                    completedBeforeDispatch?.let { completed ->
                        val resolution = publishOrRetireSensorLinearNdrRecordingFrameLocked(completed)
                        if (!resolution.encoderAccepted) {
                            return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                                SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                                reason = resolution.reason
                            )
                        }
                    }
                    return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                        SensorLinearNdrDemosaicSubmissionStatus.RETRY_PENDING,
                        completionPending = true,
                        retryCause = SensorLinearNdrDemosaicRetryCause.NATIVE_OUTPUT
                    )
                }
                RawDemosaicCompletionPoll.FAILED ->
                    return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                        SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                        reason = "Native RAW completion poll failed before recorder dispatch"
                    )
                RawDemosaicCompletionPoll.COMPLETE,
                RawDemosaicCompletionPoll.IDLE -> {
                    if (beforePoll == RawDemosaicCompletionPoll.COMPLETE &&
                        completedBeforeDispatch == null
                    ) {
                        return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                            SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                            reason = "Native RAW completion lost its frame identity"
                        )
                    }
                    // Two completed lanes leave no write target. Retire only the oldest, then overlap
                    // the current Vulkan dispatch with publication of the promoted completed lane.
                    if (completedBeforeDispatch != null &&
                        (!before.canOverlapDispatchBeforeAcknowledgement ||
                            rawDemosaicCompletionHandoff.completedIdentityCount() +
                                sensorLinearNdrEncoderInputReads.outstandingCount >=
                                EncoderInputReadOwnershipRing.CAPACITY)
                    ) {
                        val resolution = publishOrRetireSensorLinearNdrRecordingFrameLocked(
                            requireNotNull(completedBeforeDispatch)
                        )
                        if (!resolution.encoderAccepted) {
                            return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                                SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                                reason = resolution.reason
                            )
                        }
                        // Both physical Vulkan lanes remain owned (completed and/or sampled by EGL),
                        // so no legal write target exists yet. Retain and retry the exact incoming Image.
                        return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                            SensorLinearNdrDemosaicSubmissionStatus.RETRY_PENDING,
                            completionPending = true,
                            retryCause = SensorLinearNdrDemosaicRetryCause.NATIVE_OUTPUT,
                            reason = "Oldest encoder input read is awaiting its exact EGL fence"
                        )
                    }
                }
            }
            if (ndrRenderProfileMutationDeferral.pendingIdentity() != null) {
                // Drain/publish the exact old-profile output without dispatching the retained Image.
                // RawVideoManager keeps that Image in its retry slot until the replacement commits.
                completedBeforeDispatch?.let { completed ->
                    val resolution = publishOrRetireSensorLinearNdrRecordingFrameLocked(completed)
                    if (!resolution.encoderAccepted) {
                        return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                            SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                            reason = resolution.reason
                        )
                    }
                }
                publishDeferredNdrRenderProfileIfRawDrainedLocked()
                val outputRetirementPending =
                    rawDemosaicCompletionHandoff.hasOutstandingIdentity() ||
                        sensorLinearNdrEncoderInputReads.outstandingCount != 0
                return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                    SensorLinearNdrDemosaicSubmissionStatus.RETRY_PENDING,
                    completionPending = outputRetirementPending,
                    retryCause = if (outputRetirementPending) {
                        SensorLinearNdrDemosaicRetryCause.NATIVE_OUTPUT
                    } else {
                        SensorLinearNdrDemosaicRetryCause.RENDER_PROFILE_TRANSITION
                    },
                    reason = "Waiting for exact RAW output retirement before NDR profile replacement"
                )
            }
            if (!before.shouldDispatchCurrent) {
                return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                    SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                    reason = "RAW completion handoff rejected recorder dispatch"
                )
            }

            if (!isSensorLinearNdrSessionGenerationCurrent(recordingIdentity.sessionGeneration)) {
                return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                    SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                    reason = "Camera2 session advanced before native NDR demosaic dispatch"
                )
            }
            val previewWorker = sensorLinearNdrRecordingPreviewWorker.snapshot()
            if (!_supported.value || !sensorLinearNdrCaptureEnabled ||
                !offscreenAhbEglEncoderActive || !offscreenAhbEglSubmissionEnabled.get() ||
                boundOffscreenEncoderSurface == null || !sensorLinearNdrPreviewActive ||
                boundSensorLinearNdrPreviewSurface == null ||
                sensorLinearNdrRecordingPreviewListener == null ||
                previewWorker.state != OrderedNdrPreviewTransportWorker.State.RUNNING ||
                previewWorker.lastFailure != null
            ) {
                return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                    SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                    reason = "Strict RAW recorder/preview transport is not active " +
                        "worker=${previewWorker.state} failure=${previewWorker.lastFailure}"
                )
            }
            syncRawSceneLogTransportState()
            // Never enter JNI when all three independent preview slots are still owned. Saturation is
            // transient monitor backpressure, not a Vulkan failure: return the exact current Image to
            // RawVideoManager's retry slot without dispatching, closing, dropping, or relabelling it.
            // Any older full-resolution handoff remains explicitly owned through completionPending.
            when (bridge.rawDemosaicPreviewDispatchAdmission()) {
                RawDemosaicPreviewDispatchAdmission.AVAILABLE -> Unit
                RawDemosaicPreviewDispatchAdmission.SATURATED -> {
                    val retryCount = rawPreviewTransportBackpressureRetryCount.incrementAndGet()
                    if (retryCount == 1L || retryCount % 60L == 0L) {
                        val stats = bridge.rawDemosaicPreviewTransportStats()
                        Log.i(
                            TAG,
                            "RAW_PREVIEW_CAPACITY_DEFERRED count=$retryCount " +
                                "generated=${stats.generated} acquired=${stats.acquired} " +
                                "acknowledged=${stats.acknowledged} outstanding=${stats.outstanding}"
                        )
                    }
                    val outputRetirementPending =
                        rawDemosaicCompletionHandoff.hasOutstandingIdentity()
                    return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                        SensorLinearNdrDemosaicSubmissionStatus.RETRY_PENDING,
                        completionPending = outputRetirementPending,
                        retryCause = if (outputRetirementPending) {
                            SensorLinearNdrDemosaicRetryCause.NATIVE_OUTPUT
                        } else {
                            SensorLinearNdrDemosaicRetryCause.PREVIEW_TRANSPORT
                        },
                        reason = "Independent RAW preview transport is temporarily saturated"
                    )
                }
                RawDemosaicPreviewDispatchAdmission.UNAVAILABLE,
                RawDemosaicPreviewDispatchAdmission.NOT_REQUIRED -> {
                    val stats = bridge.rawDemosaicPreviewTransportStats()
                    return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                        SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                        reason = "Strict RAW preview transport is unavailable " +
                            "generated=${stats.generated} acquired=${stats.acquired} " +
                            "acknowledged=${stats.acknowledged} outstanding=${stats.outstanding}"
                    )
                }
            }
            val hardwareBuffer = runCatching { image.hardwareBuffer }.getOrNull()
                ?: return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                    SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                    reason = "RAW Image has no HardwareBuffer"
                )
            val dispatchStartedElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
            var synchronousCompletionObservedElapsedRealtimeNs = 0L
            val completedSynchronously = try {
                val completed = cinemaDebayer.submitRawBuffer(
                    hardwareBuffer = hardwareBuffer,
                    descriptor = descriptor,
                    bridge = bridge,
                    completionWaitNs = 0L,
                    recordSynchronousCompletion = false
                )
                if (completed) {
                    synchronousCompletionObservedElapsedRealtimeNs =
                        SystemClock.elapsedRealtimeNanos()
                }
                completed
            } finally {
                hardwareBuffer.close()
            }
            val submittedFrame = frame.copy(
                demosaicDispatchStartedElapsedRealtimeNs = dispatchStartedElapsedRealtimeNs,
                demosaicCompletionStatsExternallyOwned = true
            )
            val afterPoll = if (completedSynchronously) {
                RawDemosaicCompletionPoll.IDLE
            } else {
                bridge.pollRawDemosaicCompletion()
            }
            val completionObservedElapsedRealtimeNs = when {
                completedSynchronously -> synchronousCompletionObservedElapsedRealtimeNs
                afterPoll == RawDemosaicCompletionPoll.COMPLETE ->
                    SystemClock.elapsedRealtimeNanos()
                else -> 0L
            }
            rawDemosaicCompletionHandoff.afterDispatch(
                frame = submittedFrame,
                completedSynchronously = completedSynchronously,
                pollAfterDeadline = afterPoll,
                completionObservedElapsedRealtimeNs = completionObservedElapsedRealtimeNs
            )
            val dispatchAccepted = completedSynchronously ||
                afterPoll == RawDemosaicCompletionPoll.PENDING ||
                afterPoll == RawDemosaicCompletionPoll.COMPLETE

            // This is the overlap point: current Vulkan work was queued into the free lane above;
            // synchronous EGL now consumes the oldest completed lane while the GPU demosaics current.
            val completedToPublish = rawDemosaicCompletionHandoff.completedIdentity()
            if (completedToPublish != null) {
                val resolution = publishOrRetireSensorLinearNdrRecordingFrameLocked(completedToPublish)
                if (!resolution.encoderAccepted) {
                    return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                        SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                        reason = resolution.reason
                    )
                }
            }
            if (dispatchAccepted) {
                return@recordingSubmit SensorLinearNdrDemosaicSubmission(
                    SensorLinearNdrDemosaicSubmissionStatus.ACCEPTED,
                    completionPending = rawDemosaicCompletionHandoff.ownsIdentity(submittedFrame)
                )
            }
            when (afterPoll) {
                RawDemosaicCompletionPoll.PENDING -> SensorLinearNdrDemosaicSubmission(
                    SensorLinearNdrDemosaicSubmissionStatus.ACCEPTED,
                    completionPending = true
                )
                RawDemosaicCompletionPoll.COMPLETE -> SensorLinearNdrDemosaicSubmission(
                    SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                    reason = "Completed NDR dispatch lost its current frame identity"
                )
                RawDemosaicCompletionPoll.IDLE -> SensorLinearNdrDemosaicSubmission(
                    SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                    reason = "RAW demosaic rejected the recorder dispatch"
                )
                RawDemosaicCompletionPoll.FAILED -> SensorLinearNdrDemosaicSubmission(
                    SensorLinearNdrDemosaicSubmissionStatus.FAILED,
                    reason = "Native RAW demosaic failed during recorder dispatch"
                )
            }
        }
    }

    /** Polls the final recorder-owned dispatch without accepting or dispatching another frame. */
    internal fun pollSensorLinearNdrRecordingCompletion(): SensorLinearNdrDemosaicCompletion {
        if (sensorLinearNdrDemosaicOwnerPhase != SensorLinearNdrDemosaicOwnerPhase.RECORDING) {
            return SensorLinearNdrDemosaicCompletion(
                SensorLinearNdrDemosaicCompletionStatus.FAILED,
                "Recorder no longer owns the RAW demosaic output"
            )
        }
        val frameLease = ndrNativeFrameBoundary.acquireFrameLease()
            ?: return retireSensorLinearNdrOutputDuringProfileTransition().let { retirement ->
                SensorLinearNdrDemosaicCompletion(
                    status = when (retirement.status) {
                        NdrProfileTransitionRetirementStatus.DRAINED ->
                            SensorLinearNdrDemosaicCompletionStatus.COMPLETED
                        NdrProfileTransitionRetirementStatus.PENDING ->
                            SensorLinearNdrDemosaicCompletionStatus.PENDING
                        NdrProfileTransitionRetirementStatus.FAILED ->
                            SensorLinearNdrDemosaicCompletionStatus.FAILED
                    },
                    reason = retirement.reason
                )
            }
        return try {
            synchronized(rawDemosaicCompletionLock) {
            if (sensorLinearNdrDemosaicOwnerPhase != SensorLinearNdrDemosaicOwnerPhase.RECORDING) {
                return@synchronized SensorLinearNdrDemosaicCompletion(
                    SensorLinearNdrDemosaicCompletionStatus.FAILED,
                    "Recorder demosaic ownership is not committed"
                )
            }
            when (retireSensorLinearNdrEncoderInputReadsLocked(timeoutNs = 0L)) {
                EncoderInputReadRetirement.PENDING -> {
                    if (!sensorLinearNdrEncoderInputReads.hasCapacity()) {
                        return@synchronized SensorLinearNdrDemosaicCompletion(
                            SensorLinearNdrDemosaicCompletionStatus.PENDING
                        )
                    }
                }
                EncoderInputReadRetirement.FAILED ->
                    return@synchronized SensorLinearNdrDemosaicCompletion(
                        SensorLinearNdrDemosaicCompletionStatus.FAILED,
                        "Exact encoder input-read fence retirement failed"
                    )
                EncoderInputReadRetirement.RETIRED -> {
                    if (!rawDemosaicCompletionHandoff.hasOutstandingIdentity() &&
                        sensorLinearNdrEncoderInputReads.outstandingCount == 0
                    ) {
                        return@synchronized SensorLinearNdrDemosaicCompletion(
                            SensorLinearNdrDemosaicCompletionStatus.COMPLETED
                        )
                    }
                }
                EncoderInputReadRetirement.NONE -> Unit
            }
            if (!rawDemosaicCompletionHandoff.hasOutstandingIdentity()) {
                return@synchronized SensorLinearNdrDemosaicCompletion(
                    if (sensorLinearNdrEncoderInputReads.outstandingCount == 0) {
                        SensorLinearNdrDemosaicCompletionStatus.COMPLETED
                    } else {
                        SensorLinearNdrDemosaicCompletionStatus.PENDING
                    }
                )
            }
            val poll = bridge.pollRawDemosaicCompletion()
            val completionObservedElapsedRealtimeNs =
                if (poll == RawDemosaicCompletionPoll.COMPLETE) {
                    SystemClock.elapsedRealtimeNanos()
                } else {
                    0L
                }
            val decision = rawDemosaicCompletionHandoff.beforeDispatch(
                poll = poll,
                completionObservedElapsedRealtimeNs = completionObservedElapsedRealtimeNs
            )
            decision.completedFrame?.let { completed ->
                val resolution = publishOrRetireSensorLinearNdrRecordingFrameLocked(completed)
                return@synchronized if (resolution.encoderAccepted) {
                    // Encoder acceptance is irreversible, but the RAW lane remains owned until the
                    // exact EGL input-read fence signals and is acknowledged on a later poll.
                    SensorLinearNdrDemosaicCompletion(
                        SensorLinearNdrDemosaicCompletionStatus.PENDING
                    )
                } else {
                    SensorLinearNdrDemosaicCompletion(
                        SensorLinearNdrDemosaicCompletionStatus.FAILED,
                        resolution.reason
                    )
                }
            }
            when (poll) {
                RawDemosaicCompletionPoll.PENDING -> SensorLinearNdrDemosaicCompletion(
                    SensorLinearNdrDemosaicCompletionStatus.PENDING
                )
                RawDemosaicCompletionPoll.COMPLETE -> {
                    SensorLinearNdrDemosaicCompletion(
                        SensorLinearNdrDemosaicCompletionStatus.FAILED,
                        "Native RAW completion lost its frame identity"
                    )
                }
                RawDemosaicCompletionPoll.IDLE -> SensorLinearNdrDemosaicCompletion(
                    SensorLinearNdrDemosaicCompletionStatus.FAILED,
                    "Native RAW dispatch became idle before recorder completion"
                )
                RawDemosaicCompletionPoll.FAILED -> SensorLinearNdrDemosaicCompletion(
                    SensorLinearNdrDemosaicCompletionStatus.FAILED,
                    "Native RAW completion poll failed"
                )
            }
        }
        } finally {
            frameLease.close()
        }
    }

    private data class SensorLinearNdrCompletedFrameResolution(
        val encoderAccepted: Boolean,
        val reason: String
    )

    private data class SensorLinearNdrRenderPacketSnapshot(
        val uniforms: GpuImageEnhancementUniforms,
        val renderProfileGeneration: Long,
        val renderProfileRevision: Long
    ) {
        val identity: NdrRenderProfileIdentity
            get() = NdrRenderProfileIdentity(renderProfileGeneration, renderProfileRevision)
    }

    private data class SensorLinearNdrEncoderSubmission(
        val submitted: Boolean,
        val inputReadReceipt: Long? = null
    )

    private fun RawDemosaicFrameIdentity.dispatchRenderPacketSnapshot():
        SensorLinearNdrRenderPacketSnapshot? {
        val identity = renderProfileIdentity ?: return null
        val uniforms = renderEnhancementUniforms ?: return null
        return SensorLinearNdrRenderPacketSnapshot(
            uniforms,
            identity.profileGeneration,
            identity.configRevision
        )
    }

    /**
     * Retires every signalled recorder input-read in FIFO order under one shared deadline.
     *
     * Caller holds [rawDemosaicCompletionLock]. Ordering is deliberately strict for each fixed slot:
     * observe EGL fence -> record GPU completion -> acknowledge EGL receipt -> acknowledge the exact
     * Vulkan token -> clear the Kotlin slot. A pending/failed proof can never make a RAW lane writable.
     */
    private fun retireSensorLinearNdrEncoderInputReadsLocked(
        timeoutNs: Long
    ): EncoderInputReadRetirement {
        check(Thread.holdsLock(rawDemosaicCompletionLock))
        if (timeoutNs < 0L) {
            sensorLinearNdrEncoderInputReads.quarantine("Negative encoder input-read drain timeout")
            return EncoderInputReadRetirement.FAILED
        }
        if (sensorLinearNdrEncoderInputReads.isQuarantined) {
            Log.e(
                TAG,
                "Encoder input-read ownership is quarantined: " +
                    sensorLinearNdrEncoderInputReads.quarantineReason()
            )
            return EncoderInputReadRetirement.FAILED
        }
        if (sensorLinearNdrEncoderInputReads.outstandingCount == 0) {
            return EncoderInputReadRetirement.NONE
        }
        val drainStartedNs = System.nanoTime()
        var retiredAny = false

        while (sensorLinearNdrEncoderInputReads.outstandingCount != 0) {
            val pending = sensorLinearNdrEncoderInputReads.oldestCommitted()
            if (pending == null) {
                val reason = "Encoder input-read ring retained an uncommitted reservation"
                sensorLinearNdrEncoderInputReads.quarantine(reason)
                Log.e(TAG, reason)
                return EncoderInputReadRetirement.FAILED
            }
            val frame = pending.frame
            if (frame == null || pending.vulkanToken <= 0L ||
                pending.completionSerial <= 0L || pending.eglReceipt <= 0L
            ) {
                val reason = "Encoder input-read ring lost exact ownership metadata"
                sensorLinearNdrEncoderInputReads.quarantine(reason)
                Log.e(TAG, reason)
                return EncoderInputReadRetirement.FAILED
            }

            if (!pending.fenceSignaled) {
                val remainingNs = if (timeoutNs == 0L) {
                    0L
                } else {
                    val elapsedNs = (System.nanoTime() - drainStartedNs).coerceAtLeast(0L)
                    (timeoutNs - elapsedNs).coerceAtLeast(0L)
                }
                when (offscreenAhbEglEncoderBridge.pollProjectOwnedInputReadFence(
                    receipt = pending.eglReceipt,
                    timeoutNs = remainingNs
                )) {
                    ProjectOwnedInputReadFencePoll.PENDING ->
                        return EncoderInputReadRetirement.PENDING
                    ProjectOwnedInputReadFencePoll.SIGNALED -> {
                        if (!sensorLinearNdrEncoderInputReads.markFenceSignaled(pending)) {
                            sensorLinearNdrEncoderInputReads.quarantine(
                                "Encoder input-read ring rejected fence signal proof"
                            )
                            return EncoderInputReadRetirement.FAILED
                        }
                    }
                    ProjectOwnedInputReadFencePoll.UNKNOWN,
                    ProjectOwnedInputReadFencePoll.FAILED -> {
                        Log.e(
                            TAG,
                            "Encoder input-read fence poll failed receipt=${pending.eglReceipt} " +
                                "token=${pending.vulkanToken} sensor=${frame.sensorTimestampNs}"
                        )
                        sensorLinearNdrEncoderInputReads.quarantine(
                            "Encoder input-read fence poll failed for exact receipt"
                        )
                        return EncoderInputReadRetirement.FAILED
                    }
                }
            }

            if (!pending.gpuCompletionRecorded) {
                presentationClock.recordGpuCompletion(
                    frame.sensorTimestampNs,
                    System.nanoTime()
                )
                if (!sensorLinearNdrEncoderInputReads.markGpuCompletionRecorded(pending)) {
                    sensorLinearNdrEncoderInputReads.quarantine(
                        "Encoder input-read ring rejected GPU completion proof"
                    )
                    return EncoderInputReadRetirement.FAILED
                }
            }
            if (!pending.receiptAcknowledged) {
                if (!offscreenAhbEglEncoderBridge.acknowledgeProjectOwnedInputReadFence(
                        pending.eglReceipt
                    )
                ) {
                    Log.e(
                        TAG,
                        "Encoder input-read receipt acknowledgement failed " +
                            "receipt=${pending.eglReceipt} token=${pending.vulkanToken} " +
                            "sensor=${frame.sensorTimestampNs}"
                    )
                    sensorLinearNdrEncoderInputReads.quarantine(
                        "Encoder input-read receipt acknowledgement failed"
                    )
                    return EncoderInputReadRetirement.FAILED
                }
                if (!sensorLinearNdrEncoderInputReads.markReceiptAcknowledged(pending)) {
                    sensorLinearNdrEncoderInputReads.quarantine(
                        "Encoder input-read ring rejected receipt acknowledgement proof"
                    )
                    return EncoderInputReadRetirement.FAILED
                }
            }
            if (!bridge.acknowledgeRawDemosaicEncoderOutput(pending.vulkanToken)) {
                Log.e(
                    TAG,
                    "Encoder input-read fence retired but exact Vulkan token acknowledgement failed " +
                        "receipt=${pending.eglReceipt} token=${pending.vulkanToken} " +
                        "serial=${pending.completionSerial} sensor=${frame.sensorTimestampNs}"
                )
                sensorLinearNdrEncoderInputReads.quarantine(
                    "EGL-acknowledged input read lost exact Vulkan token acknowledgement"
                )
                return EncoderInputReadRetirement.FAILED
            }
            if (!sensorLinearNdrEncoderInputReads.retire(pending)) {
                val reason = "Encoder input-read ring rejected its proven FIFO retirement"
                sensorLinearNdrEncoderInputReads.quarantine(reason)
                Log.e(TAG, reason)
                return EncoderInputReadRetirement.FAILED
            }
            retiredAny = true
        }

        publishDeferredNdrRenderProfileIfRawDrainedLocked()
        return if (retiredAny) {
            EncoderInputReadRetirement.RETIRED
        } else {
            EncoderInputReadRetirement.NONE
        }
    }

    /**
     * Drains only work admitted before a render-profile transition closed normal frame ingress.
     *
     * The retirement lease is ingress-independent but remains mutually exclusive with native profile
     * mutation. This path may poll/acknowledge an existing EGL receipt or retire a completed stale RAW
     * output; it never accepts the caller's new Image and never dispatches another Vulkan frame.
     */
    private fun retireSensorLinearNdrOutputDuringProfileTransition():
        NdrProfileTransitionRetirement {
        val retirementLease = ndrNativeFrameBoundary.acquireRetirementLease()
        return try {
            synchronized(rawDemosaicCompletionLock) {
                val deferredIdentity = ndrRenderProfileMutationDeferral.pendingIdentity()
                    ?: return@synchronized NdrProfileTransitionRetirement(
                        status = NdrProfileTransitionRetirementStatus.PENDING,
                        nativeOutputOwned = rawDemosaicCompletionHandoff.hasOutstandingIdentity() ||
                            sensorLinearNdrEncoderInputReads.outstandingCount != 0,
                        reason = "NDR render profile mutation is in progress"
                    )

                when (retireSensorLinearNdrEncoderInputReadsLocked(timeoutNs = 0L)) {
                    EncoderInputReadRetirement.PENDING ->
                        return@synchronized NdrProfileTransitionRetirement(
                            status = NdrProfileTransitionRetirementStatus.PENDING,
                            nativeOutputOwned = true,
                            reason = "Old-profile encoder input read is awaiting its exact EGL fence"
                        )
                    EncoderInputReadRetirement.FAILED ->
                        return@synchronized NdrProfileTransitionRetirement(
                            status = NdrProfileTransitionRetirementStatus.FAILED,
                            nativeOutputOwned = true,
                            reason = "Old-profile encoder input-read fence retirement failed"
                        )
                    EncoderInputReadRetirement.NONE,
                    EncoderInputReadRetirement.RETIRED -> Unit
                }

                if (rawDemosaicCompletionHandoff.hasOutstandingIdentity()) {
                    val poll = bridge.pollRawDemosaicCompletion()
                    if (poll == RawDemosaicCompletionPoll.FAILED) {
                        return@synchronized NdrProfileTransitionRetirement(
                            status = NdrProfileTransitionRetirementStatus.FAILED,
                            nativeOutputOwned = true,
                            reason = "Old-profile native RAW completion poll failed"
                        )
                    }
                    val completionObservedElapsedRealtimeNs =
                        if (poll == RawDemosaicCompletionPoll.COMPLETE) {
                            SystemClock.elapsedRealtimeNanos()
                        } else {
                            0L
                        }
                    val decision = rawDemosaicCompletionHandoff.beforeDispatch(
                        poll = poll,
                        completionObservedElapsedRealtimeNs = completionObservedElapsedRealtimeNs
                    )
                    decision.completedFrame?.let { completed ->
                        val staleDecision = NdrRawCompletionIdentityPolicy.resolve(
                            dispatchIdentity = completed.renderProfileIdentity,
                            currentIdentity = currentNdrRenderBundleSnapshot()?.identity,
                            cameraSessionCurrent = isSensorLinearNdrSessionGenerationCurrent(
                                completed.cameraSessionGeneration
                            )
                        )
                        if (staleDecision != NdrRawCompletionIdentityPolicy.Decision.RETIRE_STALE ||
                            !acknowledgeRawDemosaicCompletion(completed)
                        ) {
                            return@synchronized NdrProfileTransitionRetirement(
                                status = NdrProfileTransitionRetirementStatus.FAILED,
                                nativeOutputOwned = true,
                                reason = "Old-profile RAW output could not be retired exactly once"
                            )
                        }
                        Log.i(
                            TAG,
                            "NDR_PROFILE_OLD_OUTPUT_RETIRED identity=${completed.renderProfileIdentity} " +
                                "replacement=$deferredIdentity sensor=${completed.sensorTimestampNs}"
                        )
                    }
                }

                publishDeferredNdrRenderProfileIfRawDrainedLocked()
                val outputOwned = rawDemosaicCompletionHandoff.hasOutstandingIdentity() ||
                    sensorLinearNdrEncoderInputReads.outstandingCount != 0 ||
                    sensorLinearNdrRecordingPreviewWorker.snapshot().outstandingTasks != 0
                NdrProfileTransitionRetirement(
                    status = if (outputOwned) {
                        NdrProfileTransitionRetirementStatus.PENDING
                    } else {
                        NdrProfileTransitionRetirementStatus.DRAINED
                    },
                    nativeOutputOwned = outputOwned,
                    reason = if (outputOwned) {
                        "Waiting for exact old-profile RAW output retirement"
                    } else {
                        "Old-profile RAW output retired; replacement profile is ready"
                    }
                )
            }
        } finally {
            retirementLease.close()
        }
    }

    /**
     * A stale native completion is terminal for its retired Camera2 session. It must not reach the
     * encoder, but its exact handoff identity must still be acknowledged once so teardown cannot wait
     * forever on a completion that can never become current again. Encoder rejection in a current
     * session remains unacknowledged and retryable.
     */
    private fun publishOrRetireSensorLinearNdrRecordingFrameLocked(
        frame: RawDemosaicFrameIdentity
    ): SensorLinearNdrCompletedFrameResolution {
        val published = publishCompletedSensorLinearNdrRecordingFrame(frame)
        if (published) {
            return SensorLinearNdrCompletedFrameResolution(
                encoderAccepted = true,
                reason = "NDR frame accepted; exact RAW ownership retained by its EGL fence"
            )
        }

        val completionIdentityDecision = NdrRawCompletionIdentityPolicy.resolve(
            dispatchIdentity = frame.renderProfileIdentity,
            currentIdentity = currentNdrRenderBundleSnapshot()?.identity,
            cameraSessionCurrent =
                isSensorLinearNdrSessionGenerationCurrent(frame.cameraSessionGeneration)
        )
        if (completionIdentityDecision == NdrRawCompletionIdentityPolicy.Decision.RETIRE_STALE) {
            val acknowledged = acknowledgeRawDemosaicCompletion(frame)
            return SensorLinearNdrCompletedFrameResolution(
                encoderAccepted = false,
                reason = if (acknowledged) {
                    "Camera2/render identity advanced; stale NDR completion retired without encoder publication"
                } else {
                    "Stale NDR completion lost its exact retirement acknowledgement"
                }
            )
        }

        return SensorLinearNdrCompletedFrameResolution(
            encoderAccepted = false,
            reason = "Completed NDR frame was rejected by the encoder"
        )
    }

    /** Encoder-first fan-out: preview is notified only after the exact frame reaches encoder input. */
    private fun publishCompletedSensorLinearNdrRecordingFrame(
        frame: RawDemosaicFrameIdentity
    ): Boolean {
        if (!sensorLinearNdrEncoderInputReads.hasCapacity()) return false
        if (!isSensorLinearNdrSessionGenerationCurrent(frame.cameraSessionGeneration)) return false
        if (!sensorLinearNdrCaptureEnabled || !offscreenAhbEglEncoderActive ||
            !offscreenAhbEglSubmissionEnabled.get() || boundOffscreenEncoderSurface == null
        ) {
            return false
        }
        // An asynchronous preview defect discovered after a prior irreversible encoder acceptance is
        // acted on here, before this frame can reach MediaCodec. The already-encoded prior frame remains
        // acknowledged exactly once and can never be submitted twice.
        sensorLinearNdrRecordingPreviewWorker.snapshot().lastFailure?.let { failure ->
            Log.e(TAG, "Recorder NDR preview domain is failed: $failure")
            return false
        }
        val renderPacket = frame.dispatchRenderPacketSnapshot() ?: return false
        val encoderSubmission = submitLatestSensorLinearNdrFrame(
                width = frame.width,
                height = frame.height,
                sensorTimestampNs = frame.sensorTimestampNs,
                cameraSessionGeneration = frame.cameraSessionGeneration,
                renderPacket = renderPacket,
                inputReadFrame = frame
            )
        val inputReadReceipt = encoderSubmission.inputReadReceipt
        if (!encoderSubmission.submitted || inputReadReceipt == null || inputReadReceipt <= 0L) {
            return false
        }
        if (!sensorLinearNdrEncoderInputReads.ownsCommitted(frame, inputReadReceipt)) return false
        // Encoder acceptance is irreversible. Preview is a separate failure domain: failure is latched
        // for the next pre-encoder boundary. The full-resolution transport remains owned by the exact
        // input-read receipt, so this frame can never be retried into MediaCodec or overwritten early.
        if (!enqueueSensorLinearNdrRecordingPreview(frame, renderPacket)) {
            sensorLinearNdrRecordingPreviewWorker.recordTerminalFailure(
                "Preview acquire/enqueue failed after encoder acceptance sensor=${frame.sensorTimestampNs}"
            )
        }
        return true
    }

    private fun enqueueSensorLinearNdrRecordingPreview(
        frame: RawDemosaicFrameIdentity,
        renderPacket: SensorLinearNdrRenderPacketSnapshot
    ): Boolean {
        if (!sensorLinearNdrPreviewActive || boundSensorLinearNdrPreviewSurface == null) {
            Log.e(TAG, "Recorder NDR preview has no prepared project-owned surface")
            return false
        }
        val listener = sensorLinearNdrRecordingPreviewListener ?: run {
            Log.e(TAG, "Recorder NDR preview has no presentation listener")
            return false
        }
        val output = bridge.acquireRawDemosaicPreviewOutput() ?: run {
            Log.e(TAG, "Recorder NDR preview ring acquisition failed or saturated")
            return false
        }
        val submission = SensorLinearNdrRecordingPreviewSubmission(
            frame = frame,
            output = output,
            uniforms = renderPacket.uniforms,
            renderProfileGeneration = renderPacket.renderProfileGeneration,
            renderProfileRevision = renderPacket.renderProfileRevision
        )
        val accepted = sensorLinearNdrRecordingPreviewWorker.submit(
            description = "sensor=${frame.sensorTimestampNs}/token=${output.token}"
        ) {
            processSensorLinearNdrRecordingPreview(submission, listener)
        }
        if (accepted) return true

        // The worker never owns a rejected task. Retire its exact lease here and fail the recording
        // boundary; silently dropping a project-owned monitor frame would falsify lineage evidence.
        val retired = output.retire(bridge::acknowledgeRawDemosaicPreviewOutput)
        Log.e(
            TAG,
            "Recorder NDR preview enqueue rejected retired=$retired " +
                "worker=${sensorLinearNdrRecordingPreviewWorker.snapshot()}"
        )
        return false
    }

    private fun processSensorLinearNdrRecordingPreview(
        submission: SensorLinearNdrRecordingPreviewSubmission,
        listener: (SensorLinearNdrRecordingPreviewSubmission) -> Boolean
    ) {
        // The token was admitted before this asynchronous worker ran. A retirement lease keeps any
        // render-profile mutation strictly before or after validation, EGL presentation, exact token
        // retirement and lineage publication, while still allowing retirement after ingress closes.
        val retirementLease = ndrNativeFrameBoundary.acquireRetirementLease()
        try {
            val frame = submission.frame
            val presentationAttempted =
                isSensorLinearNdrSessionGenerationCurrent(frame.cameraSessionGeneration) &&
                    currentNdrRenderBundleSnapshot()?.identity == submission.renderProfileIdentity
            var presented = false
            var presentationFailure: Throwable? = null
            if (presentationAttempted) {
                try {
                    presented = listener(submission)
                } catch (throwable: Throwable) {
                    presentationFailure = throwable
                }
            }

            // listener() returns only after native glFinish. Close the Java reference exactly once and
            // release the exact native slot token before resolving failure or lineage.
            var retirementFailure: Throwable? = null
            val retired = try {
                submission.output.retire(bridge::acknowledgeRawDemosaicPreviewOutput)
            } catch (throwable: Throwable) {
                retirementFailure = throwable
                false
            }
            if (!retired && retirementFailure == null) {
                retirementFailure = IllegalStateException(
                    "NDR preview token ${submission.output.token} was not retired exactly once"
                )
            }

            // Sample identity only after exact token retirement. The retirement lease prevents a
            // render mutation from crossing this decision; Camera2 remains independently monotonic.
            val cameraSessionCurrentAfterPresentation =
                isSensorLinearNdrSessionGenerationCurrent(frame.cameraSessionGeneration)
            val renderProfileCurrentAfterPresentation =
                currentNdrRenderBundleSnapshot()?.identity == submission.renderProfileIdentity
            val completionDecision = SensorLinearNdrRecordingPreviewCompletionPolicy.resolve(
                presentationAttempted = presentationAttempted,
                presented = presented,
                cameraSessionCurrentAfterPresentation = cameraSessionCurrentAfterPresentation,
                renderProfileCurrentAfterPresentation = renderProfileCurrentAfterPresentation
            )
            SensorLinearNdrRecordingPreviewCompletionPolicy.selectTerminalFailure(
                decision = completionDecision,
                presentationFailure = presentationFailure,
                retirementFailure = retirementFailure,
                currentRejectionFailure = {
                    IllegalStateException(
                        "Current recorder NDR preview frame was rejected by its EGL/compositor path"
                    )
                }
            )?.let { throw it }

            when (completionDecision) {
                SensorLinearNdrRecordingPreviewCompletionPolicy.Decision.FAIL_CURRENT_PRESENTATION ->
                    error("Current presentation failure was not selected as terminal")
                SensorLinearNdrRecordingPreviewCompletionPolicy.Decision.RETIRE_STALE -> {
                    Log.i(
                        TAG,
                        "Recorder NDR preview token retired stale without clip failure " +
                            "sensor=${frame.sensorTimestampNs} " +
                            "cameraCurrent=$cameraSessionCurrentAfterPresentation " +
                            "profileCurrent=$renderProfileCurrentAfterPresentation"
                    )
                }
                SensorLinearNdrRecordingPreviewCompletionPolicy.Decision.EMIT_CURRENT_LINEAGE -> {
                    emitFrameLineageSubmission(
                        sensorTimestampNs = frame.sensorTimestampNs,
                        presentationTimeNs = frame.sensorTimestampNs,
                        previewSubmitted = true,
                        encoderSubmitted = false,
                        cameraSessionGeneration = frame.cameraSessionGeneration
                    )
                }
            }
        } finally {
            retirementLease.close()
        }
    }

    /** Moves JVM lineage into the fixed encoder ring without releasing its native Vulkan lane. */
    private fun transferRawDemosaicCompletionToEncoderRing(
        frame: RawDemosaicFrameIdentity,
        receipt: Long
    ): Boolean {
        check(Thread.holdsLock(rawDemosaicCompletionLock))
        if (rawDemosaicCompletionHandoff.completedIdentity() != frame ||
            !sensorLinearNdrEncoderInputReads.ownsCommitted(frame, receipt)
        ) return false
        val transferred = rawDemosaicCompletionHandoff.acknowledgeCompleted(frame)
        if (transferred && frame.demosaicCompletionStatsExternallyOwned) {
            cinemaDebayer.recordDeferredCompletion(
                width = frame.width,
                height = frame.height,
                dispatchStartedElapsedRealtimeNs = frame.demosaicDispatchStartedElapsedRealtimeNs,
                completionElapsedRealtimeNs = frame.demosaicCompletionObservedElapsedRealtimeNs
            )
        }
        return transferred
    }

    private fun acknowledgeRawDemosaicCompletion(frame: RawDemosaicFrameIdentity): Boolean {
        if (rawDemosaicCompletionHandoff.completedIdentity() != frame) return false
        // Native owns the physical transport slot; JVM owns the exact frame identity. Advance both
        // under rawDemosaicCompletionLock only after encoder acceptance and safe independent-preview
        // enqueue. Preview EGL completion acknowledges its own token and can no longer delay this ring.
        if (!bridge.acknowledgeRawDemosaicOutput()) return false
        val acknowledged = rawDemosaicCompletionHandoff.acknowledgeCompleted(frame)
        if (acknowledged && frame.demosaicCompletionStatsExternallyOwned) {
            cinemaDebayer.recordDeferredCompletion(
                width = frame.width,
                height = frame.height,
                dispatchStartedElapsedRealtimeNs = frame.demosaicDispatchStartedElapsedRealtimeNs,
                completionElapsedRealtimeNs =
                    frame.demosaicCompletionObservedElapsedRealtimeNs
            )
        }
        if (acknowledged) publishDeferredNdrRenderProfileIfRawDrainedLocked()
        return acknowledged
    }

    /** The worker publishes zero ownership before invoking this edge and holds no worker monitor. */
    private fun onSensorLinearNdrRecordingPreviewTaskRetired() {
        synchronized(rawDemosaicCompletionLock) {
            publishDeferredNdrRenderProfileIfRawDrainedLocked()
        }
    }

    /** Caller owns [rawDemosaicCompletionLock]. Emits a sticky exact-identity retry edge once. */
    private fun publishDeferredNdrRenderProfileIfRawDrainedLocked() {
        check(Thread.holdsLock(rawDemosaicCompletionLock))
        val drained = !rawDemosaicCompletionHandoff.hasOutstandingIdentity() &&
            sensorLinearNdrEncoderInputReads.outstandingCount == 0 &&
            sensorLinearNdrRecordingPreviewWorker.snapshot().outstandingTasks == 0
        val ready = ndrRenderProfileMutationDeferral.readyIdentity(drained) ?: return
        _ndrRenderProfileMutationReadyIdentity.value = ready
    }

    private fun publishCompletedRawDemosaicFrame(
        frame: RawDemosaicFrameIdentity
    ): Boolean {
        val renderPacket = frame.dispatchRenderPacketSnapshot() ?: return false
        if (NdrRawCompletionIdentityPolicy.resolve(
                dispatchIdentity = renderPacket.identity,
                currentIdentity = currentNdrRenderBundleSnapshot()?.identity,
                cameraSessionCurrent =
                    isSensorLinearNdrSessionGenerationCurrent(frame.cameraSessionGeneration)
            ) == NdrRawCompletionIdentityPolicy.Decision.RETIRE_STALE
        ) {
            // A newer packet owns presentation now. The old native output is safely discardable but
            // must never be graded with the replacement transform/enhancement payload.
            return true
        }
        val encoderSubmissionRequired = sensorLinearNdrCaptureEnabled &&
            offscreenAhbEglEncoderActive && offscreenAhbEglSubmissionEnabled.get()
        val encoderDelivered = if (encoderSubmissionRequired) {
            submitLatestSensorLinearNdrFrame(
                    width = frame.width,
                    height = frame.height,
                    sensorTimestampNs = frame.sensorTimestampNs,
                    cameraSessionGeneration = frame.cameraSessionGeneration,
                    renderPacket = renderPacket
                ).submitted
        } else {
            true
        }
        if (encoderSubmissionRequired && !encoderDelivered &&
            !isSensorLinearNdrSessionGenerationCurrent(frame.cameraSessionGeneration)
        ) {
            // The caller acknowledges only a successful publication. Retire a stale immutable identity
            // here so an old idle-preview completion cannot strand the shared native output.
            acknowledgeRawDemosaicCompletion(frame)
        }
        val completionRoute = frame.idleCompletionRoute ?: return false
        runCatching { completionRoute.publish(frame) }
            .onFailure { Log.e(TAG, "RAW demosaic completion consumer failed", it) }
        return encoderDelivered
    }

    private fun submitLatestSensorLinearNdrFrame(
        width: Int,
        height: Int,
        sensorTimestampNs: Long,
        cameraSessionGeneration: Long,
        renderPacket: SensorLinearNdrRenderPacketSnapshot,
        inputReadFrame: RawDemosaicFrameIdentity? = null
    ): SensorLinearNdrEncoderSubmission {
        val ndrFrameLease = ndrNativeFrameBoundary.acquireFrameLease()
            ?: return SensorLinearNdrEncoderSubmission(submitted = false)
        try {
        val inputReadFenceRequired = inputReadFrame != null
        if (inputReadFenceRequired &&
            (!Thread.holdsLock(rawDemosaicCompletionLock) ||
                !sensorLinearNdrEncoderInputReads.hasCapacity())
        ) {
            return SensorLinearNdrEncoderSubmission(submitted = false)
        }
        if (!isSensorLinearNdrSessionGenerationCurrent(cameraSessionGeneration)) {
            return SensorLinearNdrEncoderSubmission(submitted = false)
        }
        val activeBundle = currentNdrRenderBundleSnapshot()
            ?: return SensorLinearNdrEncoderSubmission(submitted = false)
        if (activeBundle.identity != renderPacket.identity ||
            activeBundle.enhancementUniforms.signature != renderPacket.uniforms.signature
        ) return SensorLinearNdrEncoderSubmission(submitted = false)
        // Reserve fixed Kotlin ownership before either acquisition JNI or EGL submission can make a
        // native transition. A rejected pre-accept path cancels both the exact token and this slot.
        val ownershipSlot = if (inputReadFenceRequired) {
            sensorLinearNdrEncoderInputReads.reserve(requireNotNull(inputReadFrame))
                ?: return SensorLinearNdrEncoderSubmission(submitted = false)
        } else {
            null
        }
        var exactOutput: RawDemosaicEncoderOutput? = null
        var irreversibleInputReadAccepted = false
        return try {
            exactOutput = if (inputReadFenceRequired) {
                bridge.acquireRawDemosaicEncoderOutput()
            } else {
                null
            }
            val output = if (inputReadFenceRequired) {
                exactOutput?.hardwareBuffer
            } else {
                bridge.acquireRawDemosaicOutputHardwareBuffer()
            } ?: return SensorLinearNdrEncoderSubmission(submitted = false)
            if (ownershipSlot != null) {
                val acquired = requireNotNull(exactOutput)
                if (!sensorLinearNdrEncoderInputReads.bindTransport(
                        ownershipSlot,
                        acquired.token,
                        acquired.completionSerial
                    )
                ) {
                    return SensorLinearNdrEncoderSubmission(submitted = false)
                }
            }
            try {
            // Preview uses the monitor derived from this same completed FP16 master. Only recording
            // submits its FP16/Neo-Scene-Log transport to MediaCodec; no Camera2 HLG/PQ input is involved.
            if (!offscreenAhbEglEncoderActive || !offscreenAhbEglSubmissionEnabled.get()) {
                return SensorLinearNdrEncoderSubmission(submitted = false)
            }

            // Recording ownership requires an exact full-sensor master. Reject any extent mismatch
            // rather than silently encoding a bounded preview buffer under full-resolution metadata.
            val outputWidth = output.width
            val outputHeight = output.height
            if (outputWidth != width || outputHeight != height) {
                return SensorLinearNdrEncoderSubmission(submitted = false)
            }

            val effectivePresentationTimeNs = presentationClock.presentationTimeFor(
                sensorTimestampNs,
                System.nanoTime()
            )
            val pacingDecision = synchronized(offscreenFramePacingLock) {
                OffscreenEncoderFramePacingPolicy.resolveOneToOne(
                    presentationTimeNs = effectivePresentationTimeNs,
                    targetFps = offscreenEncoderTargetFps,
                    state = offscreenFramePacingState
                ).also { offscreenFramePacingState = it.state }
            }
            val submissionAttempt = offscreenEncoderLiveSubmissionAttempts.incrementAndGet()
            if (pacingDecision.readinessHandoffRebased) {
                val readinessPtsNs = offscreenEncoderReadinessPresentationTimeNs
                val message =
                    "OFFSCREEN_ENCODER_TIMELINE_REBASE attempt=$submissionAttempt " +
                        "readinessPtsNs=$readinessPtsNs liveClockPtsNs=$effectivePresentationTimeNs " +
                        "submittedPtsNs=${pacingDecision.submissionPresentationTimeNs} " +
                        "readinessToLiveGapNs=" +
                        "${(pacingDecision.submissionPresentationTimeNs - readinessPtsNs).coerceAtLeast(0L)}"
                Log.i(TAG, message)
                runCatching { NeoLogger.i("VulkanEncoder", message) }
            }
            // Recorder-owned RAW ingress is one-to-one: normal sensor jitter is mapped onto the CFR
            // timeline without rejecting a master. Keep this guard fail-closed for future policies.
            if (!pacingDecision.submit) return SensorLinearNdrEncoderSubmission(submitted = false)
            val submittedPresentationTimeNs = pacingDecision.submissionPresentationTimeNs
                .takeIf { it > 0L }
                ?: effectivePresentationTimeNs
            if (submittedPresentationTimeNs <= 0L) {
                return SensorLinearNdrEncoderSubmission(submitted = false)
            }
            if (!isSensorLinearNdrSessionGenerationCurrent(cameraSessionGeneration)) {
                return SensorLinearNdrEncoderSubmission(submitted = false)
            }
            if (ownershipSlot != null &&
                !sensorLinearNdrEncoderInputReads.isReadyForAcceptance(ownershipSlot)
            ) {
                return SensorLinearNdrEncoderSubmission(submitted = false)
            }
            val outputFormat = output.format
            val inputReadReceipt = if (inputReadFenceRequired) {
                when (outputFormat) {
                    HardwareBuffer.RGBA_FP16 ->
                        offscreenAhbEglEncoderBridge.submitSensorLinearAcesCgWithInputReadFence(
                            hardwareBuffer = output,
                            width = outputWidth,
                            height = outputHeight,
                            presentationTimeNs = submittedPresentationTimeNs,
                            uniforms = renderPacket.uniforms
                        )
                    HardwareBuffer.RGBA_1010102 ->
                        offscreenAhbEglEncoderBridge.submitSceneLog10AcesCgWithInputReadFence(
                            hardwareBuffer = output,
                            width = outputWidth,
                            height = outputHeight,
                            presentationTimeNs = submittedPresentationTimeNs,
                            uniforms = renderPacket.uniforms
                        )
                    else -> null
                }
            } else {
                null
            }
            val submitted = if (inputReadFenceRequired) {
                inputReadReceipt != null
            } else {
                when (outputFormat) {
                    HardwareBuffer.RGBA_FP16 ->
                        offscreenAhbEglEncoderBridge.submitSensorLinearAcesCg(
                            hardwareBuffer = output,
                            width = outputWidth,
                            height = outputHeight,
                            presentationTimeNs = submittedPresentationTimeNs,
                            uniforms = renderPacket.uniforms
                        )
                    HardwareBuffer.RGBA_1010102 ->
                        offscreenAhbEglEncoderBridge.submitSceneLog10AcesCg(
                            hardwareBuffer = output,
                            width = outputWidth,
                            height = outputHeight,
                            presentationTimeNs = submittedPresentationTimeNs,
                            uniforms = renderPacket.uniforms
                        )
                    else -> false
                }
            }
            // Commit exact ownership immediately after JNI reports the irreversible swap. Nothing
            // that follows (lineage, capability refresh, or telemetry) may throw before Kotlin knows
            // which RAW lane the native receipt protects.
            if (inputReadReceipt != null) {
                irreversibleInputReadAccepted = true
                val slot = requireNotNull(ownershipSlot)
                val committed = sensorLinearNdrEncoderInputReads.commitAccepted(
                    slot = slot,
                    eglReceipt = inputReadReceipt,
                    submittedPresentationTimeNs = submittedPresentationTimeNs
                )
                if (!committed) {
                    // MediaCodec acceptance cannot be rolled back. Preserve both native and Kotlin
                    // ownership fail-closed rather than cancelling or allowing a duplicate submission.
                    sensorLinearNdrEncoderInputReads.quarantine(
                        "Irreversible encoder input read could not commit its reserved slot"
                    )
                    Log.e(TAG, "Irreversible encoder input read could not commit its reserved slot")
                    return SensorLinearNdrEncoderSubmission(
                        submitted = true,
                        inputReadReceipt = inputReadReceipt
                    )
                }
                if (!transferRawDemosaicCompletionToEncoderRing(
                        frame = requireNotNull(inputReadFrame),
                        receipt = inputReadReceipt
                    )
                ) {
                    sensorLinearNdrEncoderInputReads.quarantine(
                        "Irreversible encoder input read lost its RAW JVM ownership transfer"
                    )
                    Log.e(TAG, "Irreversible encoder input read could not transfer RAW JVM ownership")
                    return SensorLinearNdrEncoderSubmission(
                        submitted = true,
                        inputReadReceipt = inputReadReceipt
                    )
                }
            }
            // An EGL swap cannot be retracted. A concurrent Camera2 generation change suppresses
            // lineage below, but an accepted recorder receipt is still returned so its exact GL read
            // and RAW lane can be retired safely instead of leaking or overwriting old-session pixels.
            if (submitted) {
                val acceptedCount = offscreenEncoderLiveSubmissionAccepted.incrementAndGet()
                if (!inputReadFenceRequired) {
                    presentationClock.recordGpuCompletion(sensorTimestampNs, System.nanoTime())
                }
                emitFrameLineageSubmission(
                    sensorTimestampNs = sensorTimestampNs,
                    presentationTimeNs = submittedPresentationTimeNs,
                    previewSubmitted = false,
                    encoderSubmitted = true,
                    cameraSessionGeneration = cameraSessionGeneration
                )
                if (!_encoderSurfaceStatus.value.sourcePrecisionProven) refreshCapabilitySummary()
                if (acceptedCount <= 3L || acceptedCount % 25L == 0L) {
                    runCatching {
                        NeoLogger.i(
                            "VulkanEncoder",
                            "OFFSCREEN_ENCODER_LIVE_ACCEPTED attempt=$submissionAttempt " +
                                "accepted=$acceptedCount sensorPtsNs=$sensorTimestampNs " +
                                "submittedPtsNs=$submittedPresentationTimeNs " +
                                "receipt=${inputReadReceipt ?: 0L} format=$outputFormat " +
                                "native=[${offscreenAhbEglEncoderBridge.summary()}]"
                        )
                    }
                }
            } else {
                val rejectedCount = offscreenEncoderLiveSubmissionRejected.incrementAndGet()
                runCatching {
                    NeoLogger.e(
                        "VulkanEncoder",
                        "OFFSCREEN_ENCODER_LIVE_REJECTED attempt=$submissionAttempt " +
                            "rejected=$rejectedCount sensorPtsNs=$sensorTimestampNs " +
                            "submittedPtsNs=$submittedPresentationTimeNs " +
                            "receipt=${inputReadReceipt ?: 0L} format=$outputFormat " +
                            "native=[${offscreenAhbEglEncoderBridge.summary()}]"
                    )
                }
            }
            val now = SystemClock.elapsedRealtime()
            val importUpdate = if (submitted) {
                importStatsAccumulator.recordSuccessfulImport(
                    width = width,
                    height = height,
                    format = outputFormat,
                    elapsedMs = now
                )
            } else {
                importStatsAccumulator.recordFailedImport()
            }
            val statsUpdate = importStatsAccumulator.mutate { current ->
                current.copy(
                    offscreenAhbEglEncoderActive = true,
                    offscreenEglEncoderSurfaceBound = boundOffscreenEncoderSurface != null,
                    encoderSurfaceBound = boundOffscreenEncoderSurface != null,
                    offscreenEglSubmittedFrames = current.offscreenEglSubmittedFrames +
                        if (submitted) 1 else 0,
                    offscreenEglFailedFrames = current.offscreenEglFailedFrames +
                        if (submitted) 0 else 1,
                    offscreenEglPresentationTimeSet = current.offscreenEglPresentationTimeSet ||
                        submittedPresentationTimeNs > 0L
                )
            }
            if (OffscreenImportStatsPublishPolicy.shouldPublish(
                    shouldEmitStats = importUpdate.shouldEmitStats,
                    offscreenRecordingActive = true,
                    imported = submitted,
                    stats = statsUpdate.stats,
                    nowElapsedMs = now,
                    lastPublishedElapsedMs = lastOffscreenImportStatsPublishElapsedMs
                )
            ) {
                _importStats.value = statsUpdate.stats
                lastOffscreenImportStatsPublishElapsedMs = now
            }
            SensorLinearNdrEncoderSubmission(
                submitted = submitted,
                inputReadReceipt = inputReadReceipt
            )
            } finally {
                if (exactOutput != null) {
                    exactOutput.close()
                } else {
                    output.close()
                }
            }
        } finally {
            if (ownershipSlot != null && !irreversibleInputReadAccepted) {
                exactOutput?.close()
                val tokenCancelled = exactOutput?.let {
                    bridge.cancelRawDemosaicEncoderOutput(it.token)
                } ?: true
                val reservationCancelled = tokenCancelled &&
                    sensorLinearNdrEncoderInputReads.cancelReservation(ownershipSlot)
                if (!tokenCancelled || !reservationCancelled) {
                    sensorLinearNdrEncoderInputReads.quarantine(
                        "Pre-accept RAW encoder acquisition cancellation lost exact ownership"
                    )
                    Log.e(
                        TAG,
                        "Exact RAW encoder acquisition cancellation failed " +
                            "token=${exactOutput?.token ?: 0L} " +
                            "nativeCancelled=$tokenCancelled ringCancelled=$reservationCancelled"
                    )
                }
            }
        }
        } finally {
            ndrFrameLease.close()
        }
    }

    /**
     * Record-time live-monitor submit: demosaic a CPU RAW mosaic COPY (row-strided uint16) held in
     * [rawBytes] — used while recording so the visible monitor stays LIVE without holding a scarce RAW
     * reader buffer away from the recorder. [decimate] (2 = half-res) keeps the cost low. Gated to the
     * active cinema-debayer (RAW+VIDEO); returns false → caller shows no fresh monitor frame.
     */
    fun submitRawSensorBytes(
        rawBytes: java.nio.ByteBuffer,
        rowStrideBytes: Int,
        decimate: Int,
        descriptor: RawBufferDescriptor
    ): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        if (!_supported.value) return false
        if (!cinemaDebayer.isActive()) return false
        val frameLease = ndrNativeFrameBoundary.acquireFrameLease() ?: return false
        return try {
            synchronized(rawDemosaicCompletionLock) {
                if (!prepareRawPreviewOwnershipLocked()) return@synchronized false
                cinemaDebayer.submitRawBytes(rawBytes, rowStrideBytes, decimate, descriptor, bridge)
            }
        } finally {
            frameLease.close()
        }
    }

    /**
     * STAGE 2 present: copy the latest tonemapped RGBA8 debayer monitor into [dst] and return
     * (previewW shl 16) or previewH, or 0 when unavailable. Call right after a successful
     * submitRawSensorImage so the GL compositor can present the genuine sensor debayer.
     */
    fun copyRawDemosaicPreviewInto(dst: java.nio.ByteBuffer): Int {
        if (!_supported.value) return 0
        val frameLease = ndrNativeFrameBoundary.acquireFrameLease() ?: return 0
        return try {
            bridge.copyRawDemosaicPreviewInto(dst)
        } finally {
            frameLease.close()
        }
    }

    /**
     * Check if the Vulkan pipeline is healthy and should receive frames.
     * Returns false if consecutive failures indicate a systemic issue.
     */
    fun isPipelineHealthy(): Boolean {
        val stats = importStatsAccumulator.current
        // If more than 10 consecutive failures, consider pipeline unhealthy
        return stats.consecutiveFailedImports < 10
    }

    /**
     * Runtime-health-monitor recovery hook (non-destructive): reset ONLY the transient consecutive-
     * import-failure counter (the value [isPipelineHealthy] gates on), leaving session totals intact,
     * so a recovered GPU pipeline can re-prove itself. Returns true only if the counter was non-zero
     * and actually reset; if imports are still failing it simply climbs back and the fault re-surfaces.
     */
    fun resetTransientImportHealth(): Boolean {
        if (importStatsAccumulator.current.consecutiveFailedImports == 0L) return false
        _importStats.value =
            importStatsAccumulator.mutate { it.copy(consecutiveFailedImports = 0L) }.stats
        return true
    }

    /**
     * s7-f12/CP9B — truthful gate-facing zero-copy + fallback verdict sourced from real native
     * telemetry (the zeroCopyLiveImport token + live import counters). Read-only; never synthesizes
     * a pass. Internal because [VulkanImportTelemetryResolver.Verdict] is internal (same module).
     */
    internal fun importTelemetryVerdict(): VulkanImportTelemetryResolver.Verdict =
        VulkanImportTelemetryResolver.resolve(
            capabilitySummary = _capabilitySummary.value,
            stats = importStatsAccumulator.current
        )
    
    /**
     * Get current frame import rate (frames per second estimate).
     * Based on session stats and elapsed time.
     */
    fun getEstimatedFps(): Float {
        val stats = importStatsAccumulator.current
        if (stats.sessionImportedFrames < 2) return 0f
        val elapsed = stats.lastSuccessfulImportElapsedMs - stats.firstSuccessfulImportElapsedMs
        if (elapsed <= 0) return 0f
        return (stats.sessionImportedFrames * 1000f) / elapsed
    }

    /**
     * Performs final shutdown and reports whether every native owner was actually retired.
     *
     * [close] deliberately returns without clearing ownership when a fence or native destroy cannot be
     * proven. `Closeable.close()` cannot expose that distinction, so lifecycle retirement must use this
     * method instead of interpreting a non-throwing early return as successful destruction. A false
     * result keeps this runtime as the exact retry owner.
     */
    internal fun closeWithRetirementProof(): Boolean {
        close()
        return closeCompleted.get()
    }

    @Synchronized
    override fun close() {
        // The @Synchronized lifecycle monitor is never acquired from a frame or packet lease. Close
        // therefore has one acyclic order: lifecycle -> boundary -> generation; after this short
        // admission transition it never reacquires the boundary while draining native owners.
        ndrNativeFrameBoundary.withMutationLease {
            synchronized(renderProfileGenerationLock) {
                ndrNativeFrameBoundary.closeIngressForMutation()
                closeRequested.set(true)
                // Close admission, retained identities and observer edges as one generation transition;
                // no finalize/advance crossing this monitor can republish ready after teardown starts.
                ndrRenderProfileReadinessAdmission.close()
                ndrRenderProfileRevisionAdmission.close()
                ndrRenderProfileMutationDeferral.clear()
                _ndrRenderProfileMutationReadyIdentity.value = null
                acceptedRenderProfileRevision = 0L
                stagedNdrRenderBundle.clear()
                appliedNdrRenderBundle.clear()
                _ndrRenderProfileStagedGeneration.value = 0L
                _ndrRenderProfileReadyGeneration.value = 0L
            }
        }
        if (closeCompleted.get()) return
        if (!initialized.get()) {
            initializationCompleted.set(true)
            closeCompleted.set(true)
            return
        }
        // B4-vulkanruntime-close-uaf: flip the import/support gate OFF *first* — before any native
        // teardown below (cinemaDebayer.close()/offscreenAhbEglEncoderBridge.close()/bridge.close())
        // frees a native handle — so an in-flight or newly-arriving submitCameraImage()/
        // submitRawSensorImage()/submitRawSensorBytes()/copyRawDemosaicPreviewInto() on another thread
        // hits its !_supported.value / !isFrameImportEnabled() guard and early-returns instead of
        // submitting into a freed bridge (reinterpret_cast on a freed handle + lock of a freed
        // std::mutex => SIGSEGV on app exit). Teardown-only: MutableStateFlow.value / AtomicBoolean give
        // cross-thread visibility, and the running path is untouched.
        _supported.value = false
        TesseraRuntimeProvider.reportBackendAvailability(
            TesseraExecutionBackend.VECTOR_GPU,
            false
        )
        frameImportEnabled.set(false)
        offscreenAhbEglSubmissionEnabled.set(false)
        val encoderInputReadQuiesced = synchronized(rawDemosaicCompletionLock) {
            when (retireSensorLinearNdrEncoderInputReadsLocked(
                NDR_ENCODER_INPUT_READ_DRAIN_TIMEOUT_NS
            )) {
                EncoderInputReadRetirement.NONE,
                EncoderInputReadRetirement.RETIRED -> true
                EncoderInputReadRetirement.PENDING,
                EncoderInputReadRetirement.FAILED -> false
            }
        }
        // Detach before native teardown. attach(null) waits for any serialized offline RAW develop to
        // leave its workspace, clears the process-static direct buffers, and prevents a closed bridge
        // from being retained or reused by a later conversion.
        com.neoproc.neoproccamx.recording.RawDevelopGpuAccelerator.attach(null)
        val previewWorkerDrained = sensorLinearNdrRecordingPreviewWorker.closeAndDrain(
            NDR_PREVIEW_DRAIN_TIMEOUT_MS
        )
        val previewTransportQuiesced = previewWorkerDrained &&
            bridge.quiesceRawDemosaicPreviewOutputs()
        if (!previewWorkerDrained || !previewTransportQuiesced) {
            runtimeCloseQuarantined = true
            Log.e(
                TAG,
                "NDR preview teardown quarantined workerDrained=$previewWorkerDrained " +
                    "nativeQuiesced=$previewTransportQuiesced " +
                    "worker=${sensorLinearNdrRecordingPreviewWorker.snapshot()}"
            )
            return
        }
        if (!encoderInputReadQuiesced) {
            // Do not clear Surface references, reset the exact RAW identity, or destroy either GPU
            // owner while a project-owned GL read is unproven. A later close() may retry retirement;
            // until then the disabled runtime deliberately quarantines the ownership bundle.
            offscreenEncoderDetachQuarantined = true
            runtimeCloseQuarantined = true
            Log.e(
                TAG,
                "Vulkan runtime close quarantined an unretired encoder input-read receipt"
            )
            return
        }
        // Final close is itself an acknowledged ownership transaction. Both EGL bridges retire
        // before any strong Surface reference or bound-status evidence is cleared. A failed JNI
        // destroy has already disabled ordinary bridge calls while retaining its exact native handle
        // exclusively for a later close retry, so returning here is fail-closed and leak-recoverable.
        val offscreenBridgeClosed = runCatching {
            offscreenAhbEglEncoderBridge.closeAcknowledged()
        }.onFailure {
            Log.e(TAG, "Offscreen EGL encoder bridge final close threw", it)
        }.getOrDefault(false)
        if (!offscreenBridgeClosed) {
            offscreenEncoderDetachQuarantined = true
            runtimeCloseQuarantined = true
            Log.e(
                TAG,
                "Vulkan runtime final close retained offscreen encoder ownership for retry"
            )
            return
        }
        val previewBridgeClosed = if (sensorLinearNdrPreviewBridgeDelegate.isInitialized()) {
            runCatching {
                sensorLinearNdrPreviewBridge.closeAcknowledged()
            }.onFailure {
                Log.e(TAG, "Sensor-linear NDR preview bridge final close threw", it)
            }.getOrDefault(false)
        } else {
            true
        }
        if (!previewBridgeClosed) {
            offscreenEncoderDetachQuarantined = true
            runtimeCloseQuarantined = true
            Log.e(
                TAG,
                "Vulkan runtime final close retained NDR preview ownership for retry"
            )
            return
        }
        // Both EGL readers were transactionally destroyed above before the demosaic/export owners.
        // Keep every Kotlin-side Surface/identity owner intact until the core bridge also acknowledges
        // destruction. If JNI dispatch fails, a later close retry still owns the exact native handle and
        // Java references instead of falsely publishing an empty runtime.
        runCatching { cinemaDebayer.close() }
            .onFailure { Log.w(TAG, "Failed to close Cinema-Debayer pipeline", it) }
        val nativeRuntimeClosed = previewTransportQuiesced && runCatching {
            bridge.closeAcknowledged()
        }.onFailure {
            Log.w(TAG, "Failed to close Vulkan runtime", it)
        }.getOrDefault(false)
        if (!nativeRuntimeClosed) {
            runtimeCloseQuarantined = true
            Log.e(TAG, "Vulkan runtime close retained RAW ownership without native destruction proof")
            return
        }
        runtimeCloseQuarantined = false
        boundPreviewSurface = null
        boundEncoderSurface = null
        quarantinedInternalEncoderSurface = null
        internalEncoderDetachQuarantined = false
        boundOffscreenEncoderSurface = null
        boundSensorLinearNdrPreviewSurface = null
        offscreenAhbEglEncoderActive = false
        offscreenEncoderDetachQuarantined = false
        sensorLinearNdrPreviewActive = false
        sensorLinearNdrCaptureEnabled = false
        sensorLinearNdrRecordingPreviewListener = null
        sensorLinearNdrSessionGenerationValidator = null
        _encoderSurfaceStatus.value = VulkanEncoderSurfaceStatus.UNBOUND
        configDirty = true
        enhancementUniforms = null
        enhancementDirty = true
        lastEnhancementSignature = ""
        lastRenderConfig = VulkanRenderConfig()
        appliedNdrNivukxMeasuredCorrection = null
        appliedNdrNivukxProfileGeneration = 0L
        appliedNdrLiveProfile = null
        ndrRenderProfileMutationDeferral.clear()
        _ndrRenderProfileMutationReadyIdentity.value = null
        acceptedRenderProfileRevision = 0L
        pendingNdrNivukxMeasuredCorrection = NdrNivukxMeasuredCorrectionPolicy.resolve("OFF")
        appliedNdrRenderBundle.clear()
        stagedNdrRenderBundle.clear()
        _ndrRenderProfileStagedGeneration.value = 0L
        _ndrRenderProfileReadyGeneration.value = 0L
        framesSinceConfigUpdate = 0
        _importStats.value = importStatsAccumulator
            .mutate {
                it.copy(
                    frameImportEnabled = false,
                    sessionImportedFrames = 0L,
                    sessionFailedImports = 0L,
                    consecutiveFailedImports = 0L,
                    previewSurfaceBound = false,
                    encoderSurfaceBound = false,
                    offscreenAhbEglEncoderActive = false,
                    offscreenEglEncoderSurfaceBound = false,
                    lastSuccessfulImportElapsedMs = 0L,
                    firstSuccessfulImportElapsedMs = 0L
                )
            }
            .stats
        synchronized(rawDemosaicCompletionLock) {
            rawDemosaicCompletionHandoff.retireAfterNativeRetirementProof()
            sensorLinearNdrDemosaicOwnerPhase =
                SensorLinearNdrDemosaicOwnerPhase.PREVIEW
        }
        closeCompleted.set(true)
    }
}
