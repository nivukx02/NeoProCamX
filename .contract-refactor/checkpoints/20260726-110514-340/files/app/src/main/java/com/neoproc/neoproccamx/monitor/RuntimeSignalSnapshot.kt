package com.neoproc.neoproccamx.monitor

/**
 * An immutable snapshot of REAL runtime signals, sampled once per monitor tick and translated into
 * plain primitives at the Android boundary (see `CameraViewModel.buildHealthSnapshot`). Keeping this
 * free of Android/Camera2/Vulkan types is deliberate: every [HealthProbe] is a pure function of this
 * struct, so the entire verification core runs — and is asserted — in ordinary JVM unit tests.
 *
 * Nullability is meaningful: a `null` means "the device / pipeline did not report this signal", which
 * the probes treat as NOT-PROVEN (never as success). Booleans are only `true` when positively observed.
 */
data class RuntimeSignalSnapshot(
    /** Monotonic clock (e.g. SystemClock.elapsedRealtime) for this sample. Never wall-clock. */
    val sampleElapsedMs: Long,

    // ---- Lifecycle ----------------------------------------------------------------------------
    /** Camera session is open/streaming (not Closed/Closing). */
    val cameraOpen: Boolean,
    /** A recording session is active (Recording or Paused). */
    val recording: Boolean,
    val paused: Boolean,

    // ---- Shared fault signals ----------------------------------------------------------------
    /** Non-null => a hardware warning was surfaced (uiState.hardwareWarning). */
    val hardwareWarning: String?,
    /** Non-null => CameraState.Error(message). */
    val cameraErrorMessage: String?,
    /** Non-null => GpuRecordingState.Error(message). */
    val gpuRecordErrorMessage: String?,

    // ---- Frame delivery / render liveness ----------------------------------------------------
    /** elapsedRealtime of the most recently delivered capture result, or null if none yet. */
    val lastFrameDeliveryElapsedMs: Long?,
    /** Latest measured preview FPS (cinemaRuntimeStats.previewFps); 0 if unknown/stalled. */
    val previewFps: Float,
    /** Dropped-frame estimate for the current session (cinemaRuntimeStats.dropEstimate). */
    val dropEstimate: Int,

    // ---- PROV CORE: pipeline liveness --------------------------------------------------------
    val vulkanSupported: Boolean,
    /** VulkanRuntimeManager.isPipelineHealthy() (consecutiveFailedImports < 10). */
    val vulkanPipelineHealthy: Boolean,
    val consecutiveFailedImports: Long,
    val sessionImportedFrames: Long,
    /** An encoder input surface is bound (GPU-import or offscreen EGL). */
    val encoderSurfaceBound: Boolean,
    /** gpuRecordState in {Ready, Recording, Paused} => encoder configured/active. */
    val encoderActive: Boolean,

    // ---- P010: 10-bit transport truth --------------------------------------------------------
    /** activeRecordingTransportHudLabel(): "P010" / "AHB_GPU_COMPATIBILITY" / "ENCODER_SURFACE_DIRECT" / null. */
    val recordingTransportLabel: String?,
    /** RecordingFeedPath.name of the authoritative chosen arm, or null. */
    val activeFeedPath: String?,
    /** A real 10-bit transport could not be honored and the plan fell back to 8-bit. */
    val simulatedTenBitFallbackActive: Boolean,
    /** Camera ImageReader source bit depth for the P010 (BYTEBUFFER) arm: 10, 8, or null. */
    val p010SourceBitDepth: Int?,
    /** VulkanEncoderSurfaceStatus.bitDepth for the GPU arms, or null if unbound/n-a. */
    val encoderSurfaceBitDepth: Int?,
    /** VulkanEncoderSurfaceStatus.surfacePrecisionProven (PACKED_10_BIT & bitDepth==10 proven). */
    val encoderSurfacePrecisionProven: Boolean,
    /** VulkanEncoderSurfaceStatus.precisionFallback (GPU surface fell back below 10-bit). */
    val encoderSurfacePrecisionFallback: Boolean,
    /** encoderOutputTenBitOrNull(): true=proven 10/12-bit output profile, false=proven 8-bit, null=not reported. */
    val encoderOutputTenBit: Boolean?,

    // ---- P010: preview-time 10-bit capability (Issue 2 — READY in preview) --------------------
    /** Device exposes a 10-bit dynamic-range capability (DYNAMIC_RANGE_TEN_BIT / HLG10 / HDR10[+]). */
    val deviceTenBitHdrSupported: Boolean = false,
    /** A hardware HEVC Main10 (P010) encoder exists on this device. */
    val encoderP010Supported: Boolean = false,
    /** An HDR DynamicRangeProfile is APPLIED on the live preview surface (positively proves 10-bit preview). */
    val previewTenBitProfileApplied: Boolean = false,
    /** Preview transport transfer characteristics (SDR / HLG / HDR10 / HDR10+), or null. */
    val previewTransferCharacteristics: String? = null,

    // ---- LAB: GPU compute processing ---------------------------------------------------------
    /** currentMeasuredAverageComputeDurationMs: per-dispatch GPU compute latency, null if none yet. */
    val computeAverageMs: Double?,
    /** Native compute has actually dispatched frames (nativeP010Compute == active-dispatched). */
    val computeDispatched: Boolean,
    /** totalDispatches token: cumulative GPU compute dispatch count, or null if not reported. */
    val computeTotalDispatches: Long? = null,
    /** Peak observed per-dispatch GPU compute latency (Kotlin-tracked max of the live average), or null. */
    val computePeakMs: Double? = null,
    /** True when computeAverageMs is a REAL GPU-timestamp measurement (gpuTimestampSamples>0), not a CPU proxy. */
    val computeGpuTimestamped: Boolean = false,
    /** Raw Vulkan capability summary string (parsed for "init failed" / "not initialized"). */
    val capabilitySummary: String,

    // ---- NATIVE: ISP metadata read-back (CP17) -----------------------------------------------
    /** ispBypassVerdictLabel(): "PROVEN" / "NOT_PROVEN" / "NOT_APPLICABLE". */
    val ispVerdictLabel: String,
    /** Last CP17 HAL-rejection / silent-override warning, or null. */
    val ispReadbackWarning: String?,
    /** Capture results are advancing (HAL responding). */
    val halResultsFlowing: Boolean,
    /** An ISP-off / RAW-sensor capture request is currently engaged (EDGE_OFF & NR_OFF). */
    val ispRawRequestActive: Boolean = false,
    /** A RAW_SENSOR stream is live in the session. */
    val rawStreamActive: Boolean = false,
    /** HAL can turn ISP blocks off (true), genuinely cannot (false), or not yet reported (null). */
    val ispBypassSupportedByHardware: Boolean? = null,
    /** Vendor ISP is mandatory / bypass impossible (true), not required (false), or not reported (null). */
    val vendorIspPresent: Boolean? = null,
    /** Native RAW Bayer (NPRAW) capture is running. */
    val nativeBayerActive: Boolean = false,

    // ---- PROV CORE: real hardware capability report (Issue 3 — exact reasons) ------------------
    /** CameraHalProfile.badgeLabel, e.g. "Pro Cinema 83/100", or null if not yet resolved. */
    val capabilityBadge: String? = null,
    /** CameraHalProfile.score (0..100), or null. */
    val capabilityScore: Int? = null,
    /** Exact per-cause limitation reasons from the real capability interrogation (descriptive, never a fault). */
    val capabilityLimitations: List<String> = emptyList(),

    // ---- Rich diagnostics (real sources; null => genuinely unavailable, rendered "—") -----------
    /** Absolute sensor timestamp (ns) of the most recent delivered capture result. */
    val lastFrameSensorTimestampNs: Long? = null,
    /** elapsedRealtime of the last successful GPU buffer import (VulkanImportStats), or null. */
    val lastSuccessfulImportElapsedMs: Long? = null,
    /** Cumulative failed GPU imports this session (error count), or null. */
    val sessionFailedImports: Long? = null,
    /** Process memory usage (MB) from MemoryProfiler, or null. */
    val memoryUsageMb: Int? = null,
    /** Process memory utilization (%) from MemoryProfiler, or null. */
    val memoryUtilizationPercent: Int? = null,
    /** Muxer queue depth (pending samples) while recording, or null. */
    val pendingMuxerSamples: Int? = null,
    /** Muxer dropped samples while recording, or null. */
    val droppedMuxerSamples: Int? = null
) {
    /** Ms since the last delivered capture result, or null if none has ever been delivered. */
    fun frameStallMs(): Long? =
        lastFrameDeliveryElapsedMs?.let { (sampleElapsedMs - it).coerceAtLeast(0L) }
}
