package com.neoproc.neoproccamx.rendering

import android.media.Image
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.neoproc.neoproccamx.ai.SubjectMask
import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.pipeline.SensorPresentationClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    val encoderSubmitted: Boolean
)

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

class VulkanRuntimeManager : Closeable {

    companion object {
        private const val TAG = "VulkanRuntime"
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
         *  across launches) only when the driver has no 10-bit recordable EGL config, or the codec
         *  input surface refused every colorspace. Any other non-proven outcome is a transient/token
         *  miss that must stay re-probable — NOT a wall. */
        internal fun offscreenSummaryIndicatesHardwareWall(nativeSummary: String): Boolean =
            nativeSummary.contains("config unavailable") ||
                nativeSummary.contains("eglCreateWindowSurface failed")
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
    private val frameImportEnabled = AtomicBoolean(false)
    @Volatile private var offscreenEncoderTargetFps = 25
    private val offscreenFramePacingLock = Any()
    private var offscreenFramePacingState = OffscreenEncoderFramePacingPolicy.reset(25)
    
    /** Lazy bridge initialization to defer native library load */
    private val bridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { VulkanBridge() }
    private val offscreenAhbEglEncoderBridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OffscreenAhbEglEncoderBridge()
    }
    
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
        val summary = try {
            combinedCapabilitySummary()
        } catch (_: Throwable) {
            return LiveComputeTelemetry.EMPTY
        }
        val dispatched = summary.contains("active-dispatched")
        val evidence = parseAverageComputeTimingEvidence(summary)
        val avg = evidence?.averageComputeMs
        if (avg != null && avg > peakComputeDurationMs) peakComputeDurationMs = avg
        val gpuSamples = GPU_TIMESTAMP_SAMPLES_PATTERN.find(summary)
            ?.groupValues?.getOrNull(1)?.trimEnd(']', ')')?.toLongOrNull() ?: 0L
        return LiveComputeTelemetry(
            dispatched = dispatched,
            averageMs = avg,
            totalDispatches = evidence?.totalDispatches,
            peakMs = if (peakComputeDurationMs > 0.0) peakComputeDurationMs else null,
            gpuTimestamped = gpuSamples > 0L
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
    private val presentationClock = SensorPresentationClock()
    
    @Volatile private var renderConfig = VulkanRenderConfig()
    private var lastRenderConfig = VulkanRenderConfig()
    @Volatile private var framesSinceConfigUpdate = 0
    @Volatile private var configDirty = true
    @Volatile private var enhancementUniforms: GpuImageEnhancementUniforms? = null
    @Volatile private var enhancementDirty = true
    // Record-grade stage (offscreen-EGL). Identity by default => recorded pixels unchanged.
    @Volatile private var offscreenRecordGradeMatrix: FloatArray = OffscreenAhbEglEncoderBridge.IDENTITY9
    @Volatile private var offscreenRecordGradeContrast: Float = 1f
    @Volatile private var lastEnhancementSignature = ""
    @Volatile private var lastSubjectDetailMaskSignature = ""
    // ARCHITECTURAL FIX: AtomicLong generation token eliminates O(N) hashCode() calls
    private val configGenerationToken = AtomicLong(0L)
    private val lastConfigGeneration = AtomicLong(0L)
    @Volatile private var boundPreviewSurface: Surface? = null
    @Volatile private var boundEncoderSurface: Surface? = null
    @Volatile private var boundOffscreenEncoderSurface: Surface? = null
    @Volatile private var offscreenInputDynamicRangeProfile: Long? = null
    @Volatile private var frameLineageSubmissionListener: ((VulkanFrameLineageSubmission) -> Unit)? = null
    @Volatile private var offscreenAhbEglEncoderActive = false
    @Volatile private var lastOffscreenImportStatsPublishElapsedMs = 0L
    private val offscreenAhbEglSubmissionEnabled = AtomicBoolean(false)

    /**
     * ════════════════════════════════════════════════════════════════════════
     * OPTIMIZED INITIALIZATION - NON-BLOCKING
     * ════════════════════════════════════════════════════════════════════════
     * 
     * Initializes Vulkan runtime without blocking the caller.
     * The bridge is lazy-loaded on first use to defer native library loading.
     */
    fun initialize(cacheDir: java.io.File? = null) {
        if (!initialized.compareAndSet(false, true)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _supported.value = false
            _capabilitySummary.value = "Vulkan AHardwareBuffer import requires API 29+"
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
            // Decisive real-surface probe: bind the GPU to an ACTUAL HEVC Main10 HLG (10-bit HDR)
            // encoder input surface — the step that fails during recording. If bound=true, GPU 10-bit
            // HDR recording is achievable on this device; if false, summary() names the exact reason.
            // Self-contained (throwaway encoder, released immediately) — does not touch recording.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !OffscreenEncoderBootProbeSkipFlag.shouldSkipBootProbe(offscreenEncoderProvenUnavailable)) {
                runCatching {
                    val probeFormat = android.media.MediaFormat.createVideoFormat("video/hevc", 1920, 1080).apply {
                        setInteger(android.media.MediaFormat.KEY_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                        setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(android.media.MediaFormat.KEY_BIT_RATE, 20_000_000)
                        setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 30)
                        setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(android.media.MediaFormat.KEY_COLOR_STANDARD, android.media.MediaFormat.COLOR_STANDARD_BT2020)
                        setInteger(android.media.MediaFormat.KEY_COLOR_TRANSFER, android.media.MediaFormat.COLOR_TRANSFER_HLG)
                        setInteger(android.media.MediaFormat.KEY_COLOR_RANGE, android.media.MediaFormat.COLOR_RANGE_FULL)
                    }
                    val encName = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                        .findEncoderForFormat(probeFormat)
                    if (encName == null) {
                        NeoLogger.i("VulkanEncoder", "GPU_10BIT_REALSURFACE_PROBE no Main10 HLG encoder for video/hevc")
                    } else {
                        val codec = android.media.MediaCodec.createByCodecName(encName)
                        try {
                            codec.configure(probeFormat, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
                            val inputSurface = codec.createInputSurface()
                            val bound = offscreenAhbEglEncoderBridge.setEncoderSurface(
                                inputSurface,
                                1920,
                                1080,
                                OffscreenEglDeliveryMode.HLG
                            )
                            NeoLogger.i(
                                "VulkanEncoder",
                                "GPU_10BIT_REALSURFACE_PROBE encoder=$encName bound=$bound ${offscreenAhbEglEncoderBridge.summary()}"
                            )
                            runCatching { offscreenAhbEglEncoderBridge.setEncoderSurface(null, 0, 0) }
                            runCatching { inputSurface.release() }
                        } finally {
                            runCatching { codec.release() }
                        }
                    }
                }.onFailure {
                    runCatching { NeoLogger.i("VulkanEncoder", "GPU_10BIT_REALSURFACE_PROBE threw ${it.javaClass.simpleName}: ${it.message}") }
                }
            }
        }.onFailure { throwable ->
            _supported.value = false
            _encoderSurfaceStatus.value = VulkanEncoderSurfaceStatus.UNBOUND
            _capabilitySummary.value = "Vulkan init failed: ${throwable.javaClass.simpleName}"
            Log.w(TAG, "Failed to initialize Vulkan runtime", throwable)
        }
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

    fun setOffscreenAhbEglSubmissionEnabled(enabled: Boolean) {
        val active = enabled && offscreenAhbEglEncoderActive && boundOffscreenEncoderSurface != null
        if (offscreenAhbEglSubmissionEnabled.getAndSet(active) != active && active) {
            resetOffscreenFramePacing()
        }
    }

    private fun resetOffscreenFramePacing() {
        synchronized(offscreenFramePacingLock) {
            offscreenFramePacingState =
                OffscreenEncoderFramePacingPolicy.reset(offscreenEncoderTargetFps)
        }
    }

    fun beginPreviewImportSession(preserveActiveOffscreenImport: Boolean = false) {
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

    fun setOffscreenAhbEglEncoderSurface(
        surface: Surface?,
        width: Int,
        height: Int,
        preferredDeliveryMode: OffscreenEglDeliveryMode = OffscreenEglDeliveryMode.SDR,
        outputMirrorPlan: RecordOutputMirrorPlan? = null,
        inputDynamicRangeProfile: Long? = null
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
        if (surface == null) {
            _encoderSurfaceStatus.value = VulkanEncoderSurfaceStatus.UNBOUND
            resetOffscreenFramePacing()
            lastOffscreenImportStatsPublishElapsedMs = 0L
            offscreenAhbEglSubmissionEnabled.set(false)
            runCatching {
                if (offscreenAhbEglEncoderActive || boundOffscreenEncoderSurface != null) {
                    offscreenAhbEglEncoderBridge.setEncoderSurface(null, 0, 0)
                }
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to clear offscreen AHB EGL encoder surface", throwable)
            }
            boundOffscreenEncoderSurface = null
            offscreenInputDynamicRangeProfile = null
            offscreenAhbEglEncoderActive = false
            refreshCapabilitySummary()
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
        if (boundOffscreenEncoderSurface === surface && offscreenAhbEglEncoderActive) {
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
                runCatching {
                    offscreenAhbEglEncoderBridge.setEncoderSurface(null, 0, 0)
                }.onFailure { throwable ->
                    Log.w(TAG, "Failed to clear rejected offscreen encoder surface", throwable)
                }
                boundOffscreenEncoderSurface = null
                offscreenAhbEglEncoderActive = false
                refreshCapabilitySummary()
                _encoderSurfaceStatus.value = VulkanEncoderSurfaceStatus.UNBOUND
            } else {
                offscreenEncoderProvenUnavailable = false
                offscreenEncoderHardwareWallProven = false
                boundOffscreenEncoderSurface = surface
                offscreenAhbEglEncoderActive = true
                offscreenAhbEglSubmissionEnabled.set(false)
                lastOffscreenImportStatsPublishElapsedMs = 0L
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
                .mutate {
                    it.copy(
                        offscreenAhbEglEncoderActive = proven,
                        offscreenEglEncoderSurfaceBound = proven,
                        encoderSurfaceBound = proven
                    )
                }
                .stats
            proven
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to bind offscreen AHB EGL encoder surface", throwable)
            boundOffscreenEncoderSurface = null
            offscreenAhbEglEncoderActive = false
            offscreenAhbEglSubmissionEnabled.set(false)
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
        }.getOrDefault(false)
    }

    fun setPreviewSurface(surface: Surface?): Boolean {
        if (!_supported.value) return false
        if (boundPreviewSurface === surface) {
            _importStats.value = importStatsAccumulator
                .mutate { it.copy(previewSurfaceBound = surface != null) }
                .stats
            return surface != null
        }
        return runCatching {
            com.neoproc.neoproccamx.utils.BootTrace.mark("vk.setPreviewSurface.start")
            val bound = bridge.setPreviewSurface(surface)
            com.neoproc.neoproccamx.utils.BootTrace.mark("vk.setPreviewSurface.end")
            boundPreviewSurface = surface.takeIf { bound }
            if (bound) {
                configDirty = true
            }
            refreshCapabilitySummary()
            _importStats.value = importStatsAccumulator
                .mutate { it.copy(previewSurfaceBound = bound && surface != null) }
                .stats
            bound
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to set preview surface for Vulkan runtime", throwable)
            boundPreviewSurface = null
            _importStats.value = importStatsAccumulator
                .mutate { it.copy(previewSurfaceBound = false) }
                .stats
        }.getOrDefault(false)
    }

    fun setEncoderSurface(surface: Surface?): Boolean {
        if (!_supported.value) {
            if (surface == null) {
                boundEncoderSurface = null
                _encoderSurfaceStatus.value = VulkanEncoderSurfaceStatus.UNBOUND
            }
            return false
        }
        if (surface != null && boundEncoderSurface === surface) {
            refreshCapabilitySummary()
            _encoderSurfaceStatus.value = resolveEncoderSurfaceStatus(
                surfaceRequested = true,
                bindSucceeded = true,
                reportedStatus = _encoderSurfaceStatus.value
            )
            val stillBound = _encoderSurfaceStatus.value.bound
            if (!stillBound) {
                boundEncoderSurface = null
            }
            _importStats.value = importStatsAccumulator
                .mutate { it.copy(encoderSurfaceBound = stillBound) }
                .stats
            return stillBound
        }
        return runCatching {
            val bound = bridge.setEncoderSurface(surface)
            boundEncoderSurface = surface.takeIf { bound }
            if (bound) {
                configDirty = true
            }
            refreshCapabilitySummary()
            _encoderSurfaceStatus.value = resolveEncoderSurfaceStatus(
                surfaceRequested = surface != null,
                bindSucceeded = bound,
                reportedStatus = _encoderSurfaceStatus.value
            )
            _importStats.value = importStatsAccumulator
                .mutate { it.copy(encoderSurfaceBound = bound && surface != null) }
                .stats
            bound
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to set encoder surface for Vulkan runtime", throwable)
            boundEncoderSurface = null
            _encoderSurfaceStatus.value = VulkanEncoderSurfaceStatus.UNBOUND
            _importStats.value = importStatsAccumulator
                .mutate { it.copy(encoderSurfaceBound = false) }
                .stats
        }.getOrDefault(false)
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

    fun setHdrDynamicRangeMode(mode: HdrDynamicRangeMode): Boolean {
        if (!_supported.value) return false
        return runCatching {
            val updated = bridge.setHdrDynamicRangeMode(mode)
            refreshCapabilitySummary()
            if (boundEncoderSurface != null && !_encoderSurfaceStatus.value.bound) {
                boundEncoderSurface = null
            }
            updated
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to set HDR dynamic-range mode", throwable)
            _encoderSurfaceStatus.value = VulkanEncoderSurfaceStatus.UNBOUND
        }.getOrDefault(false)
    }

    /**
     * X2/R1: request (or revoke) a 10-bit packed sRGB DISPLAY (preview) swapchain for SDR. The
     * native side rebuilds only the preview target and falls back byte-identically to 8-bit when the
     * driver advertises no 10-bit SDR display pair, so this is safe to enable and runtime-revocable
     * if on-device frame pacing regresses.
     */
    fun setPreferTenBitSdrDisplay(enabled: Boolean): Boolean {
        if (!_supported.value) return false
        return runCatching {
            val updated = bridge.setPreferTenBitSdrDisplay(enabled)
            refreshCapabilitySummary()
            updated
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to set SDR display 10-bit preference", throwable)
        }.getOrDefault(false)
    }

    fun updateRenderConfig(
        previewTransform: FloatArray,
        encoderTransform: FloatArray,
        colorGain: FloatArray,
        previewGrade: FloatArray,
        encoderGrade: FloatArray,
        previewColorScience: FloatArray = VulkanColorScienceUniforms.disabled().pack(),
        encoderColorScience: FloatArray = VulkanColorScienceUniforms.disabled().pack()
    ) {
        // ULTRA HIGH-END OPTIMIZATION: Skip unnecessary copyOf() calls
        renderConfig = VulkanRenderConfig(
            previewTransform = previewTransform,
            encoderTransform = encoderTransform,
            colorGain = colorGain,
            previewGrade = previewGrade,
            encoderGrade = encoderGrade,
            previewColorScience = previewColorScience,
            encoderColorScience = encoderColorScience
        )
        configDirty = true
        // ARCHITECTURAL FIX: Increment token to signal change without O(N) hashCode
        configGenerationToken.incrementAndGet()
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

    fun updateEnhancementUniforms(uniforms: GpuImageEnhancementUniforms) {
        val previousSignature = enhancementUniforms?.signature
        enhancementUniforms = uniforms
        if (uniforms.signature != previousSignature || uniforms.signature != lastEnhancementSignature) {
            enhancementDirty = true
            configGenerationToken.incrementAndGet()
        }
    }

    fun updateSubjectDetailMask(mask: SubjectMask?) {
        if (!_supported.value) return
        val signature = mask?.let { mask ->
            "${mask.trackingId}:${mask.maskWidth}x${mask.maskHeight}:${mask.alphaMask.contentHashCode()}"
        } ?: "none"
        if (signature == lastSubjectDetailMaskSignature) return
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
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to update Vulkan subject detail mask", throwable)
        }
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


    private fun pushEnhancementUniformsIfNeeded() {
        val uniforms = enhancementUniforms ?: return
        if (!enhancementDirty && uniforms.signature == lastEnhancementSignature) return
        if (bridge.updateEnhancementUniforms(uniforms)) {
            enhancementDirty = false
            lastEnhancementSignature = uniforms.signature
            refreshCapabilitySummary()
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

    fun updatePreviewLut(lutData: FloatArray?, size: Int) {
        if (!_supported.value) return
        runCatching {
            bridge.updatePreviewLut(lutData = lutData, size = size)
            refreshCapabilitySummary()
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to update Vulkan preview LUT", throwable)
        }
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
    fun submitCameraImage(image: Image, presentationTimeNs: Long = 0L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (!isFrameImportEnabled()) return false
        if (!_supported.value) return false
        
        val width: Int
        val height: Int
        val format: Int
        val dataSpace: Int
        val hardwareBuffer: android.hardware.HardwareBuffer
        val sensorTimestampNs = try { image.timestamp } catch (_: Exception) { 0L }
        val effectivePresentationTimeNs = if (presentationTimeNs > 0L) {
            presentationTimeNs
        } else {
            presentationClock.presentationTimeFor(sensorTimestampNs, System.nanoTime())
        }
        var submittedPresentationTimeNs = effectivePresentationTimeNs

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
            hardwareBuffer = image.hardwareBuffer ?: return false
        } catch (_: Exception) {
            return false
        }
        
        return try {
            val configSnapshot = renderConfig
            var offscreenSkippedByPacing = false
            var offscreenSubmissionPaused = false
            val imported = if (offscreenAhbEglEncoderActive) {
                if (!offscreenAhbEglSubmissionEnabled.get()) {
                    offscreenSubmissionPaused = true
                    false
                } else {
                    val pacingDecision = synchronized(offscreenFramePacingLock) {
                        OffscreenEncoderFramePacingPolicy.resolve(
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
                        offscreenAhbEglEncoderBridge.submitHardwareBuffer(
                            hardwareBuffer = hardwareBuffer,
                            width = width,
                            height = height,
                            format = format,
                            dataSpace = dataSpace,
                            appliedProfile = offscreenInputDynamicRangeProfile,
                            presentationTimeNs = submittedPresentationTimeNs,
                            uniforms = enhancementUniforms
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
                    bridge.updateRenderConfig(
                        previewTransform = configSnapshot.previewTransform,
                        encoderTransform = configSnapshot.encoderTransform,
                        colorGain = configSnapshot.colorGain,
                        previewGrade = configSnapshot.previewGrade,
                        encoderGrade = configSnapshot.encoderGrade,
                        previewColorScience = configSnapshot.previewColorScience,
                        encoderColorScience = configSnapshot.encoderColorScience
                    )
                    lastRenderConfig = configSnapshot
                    lastConfigGeneration.set(currentGeneration)
                    framesSinceConfigUpdate = 0
                    configDirty = false
                } else {
                    framesSinceConfigUpdate++
                }

                applyDormantSpatialEnhancementFlags()
                pushEnhancementUniformsIfNeeded()

                bridge.submitHardwareBuffer(
                    hardwareBuffer = hardwareBuffer,
                    width = width,
                    height = height,
                    format = format,
                    presentationTimeNs = effectivePresentationTimeNs
                )
            }
            if (offscreenSkippedByPacing || offscreenSubmissionPaused) {
                return true
            }
            if (imported) {
                presentationClock.recordGpuCompletion(
                    sensorTimestampNs = sensorTimestampNs,
                    completionNs = System.nanoTime()
                )
                emitFrameLineageSubmission(
                    sensorTimestampNs = sensorTimestampNs,
                    presentationTimeNs = submittedPresentationTimeNs
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

            imported
        } catch (throwable: Throwable) {
            configDirty = true
            val statsUpdate = importStatsAccumulator.recordFailedImport()
            _importStats.value = statsUpdate.stats
            if (statsUpdate.shouldForceConfigRefresh) {
                configDirty = true
            }
            Log.w(TAG, "Failed to submit camera image into Vulkan runtime", throwable)
            false
        } finally {
            hardwareBuffer.close()
        }
    }

    private fun emitFrameLineageSubmission(
        sensorTimestampNs: Long,
        presentationTimeNs: Long
    ) {
        if (sensorTimestampNs <= 0L) return
        val listener = frameLineageSubmissionListener ?: return
        val encoderSubmitted = if (offscreenAhbEglEncoderActive) {
            boundOffscreenEncoderSurface != null
        } else {
            boundEncoderSurface != null && _encoderSurfaceStatus.value.bound
        }
        runCatching {
            listener(
                VulkanFrameLineageSubmission(
                    sensorTimestampNs = sensorTimestampNs,
                    presentationTimeNs = presentationTimeNs,
                    previewSubmitted = boundPreviewSurface != null,
                    encoderSubmitted = encoderSubmitted
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

    fun submitRawSensorImage(
        image: android.media.Image,
        descriptor: RawBufferDescriptor
    ): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        val supported = _supported.value
        val active = cinemaDebayer.isActive()
        if (!supported || !active) {
            if (!loggedRawSubmitDiag) {
                loggedRawSubmitDiag = true
                android.util.Log.i("VulkanRuntime", "RAW debayer gated: supported=$supported active=$active")
            }
            return false
        }
        val result = cinemaDebayer.submitRawImage(image, descriptor, bridge)
        if (!loggedRawSubmitDiag) {
            loggedRawSubmitDiag = true
            android.util.Log.i("VulkanRuntime", "RAW debayer first dispatch result=$result")
        }
        return result
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
        return cinemaDebayer.submitRawBytes(rawBytes, rowStrideBytes, decimate, descriptor, bridge)
    }

    /**
     * STAGE 2 present: copy the latest tonemapped RGBA8 debayer monitor into [dst] and return
     * (previewW shl 16) or previewH, or 0 when unavailable. Call right after a successful
     * submitRawSensorImage so the GL compositor can present the genuine sensor debayer.
     */
    fun copyRawDemosaicPreviewInto(dst: java.nio.ByteBuffer): Int {
        if (!_supported.value) return 0
        return bridge.copyRawDemosaicPreviewInto(dst)
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

    override fun close() {
        if (!initialized.get()) return
        // B4-vulkanruntime-close-uaf: flip the import/support gate OFF *first* — before any native
        // teardown below (cinemaDebayer.close()/offscreenAhbEglEncoderBridge.close()/bridge.close())
        // frees a native handle — so an in-flight or newly-arriving submitCameraImage()/
        // submitRawSensorImage()/submitRawSensorBytes()/copyRawDemosaicPreviewInto() on another thread
        // hits its !_supported.value / !isFrameImportEnabled() guard and early-returns instead of
        // submitting into a freed bridge (reinterpret_cast on a freed handle + lock of a freed
        // std::mutex => SIGSEGV on app exit). Teardown-only: MutableStateFlow.value / AtomicBoolean give
        // cross-thread visibility, and the running path is untouched.
        _supported.value = false
        frameImportEnabled.set(false)
        boundPreviewSurface = null
        boundEncoderSurface = null
        boundOffscreenEncoderSurface = null
        offscreenAhbEglEncoderActive = false
        offscreenAhbEglSubmissionEnabled.set(false)
        _encoderSurfaceStatus.value = VulkanEncoderSurfaceStatus.UNBOUND
        configDirty = true
        enhancementUniforms = null
        enhancementDirty = true
        lastEnhancementSignature = ""
        lastRenderConfig = VulkanRenderConfig()
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
        runCatching { cinemaDebayer.close() }
            .onFailure { Log.w(TAG, "Failed to close Cinema-Debayer pipeline", it) }
        runCatching { offscreenAhbEglEncoderBridge.close() }
            .onFailure { Log.w(TAG, "Failed to close offscreen EGL encoder bridge", it) }
        runCatching { bridge.close() }
            .onFailure { Log.w(TAG, "Failed to close Vulkan runtime", it) }
    }
}
