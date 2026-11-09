package com.neoproc.neoproccamx.pipeline

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.neoproc.neoproccamx.ai.Yuv420ImageTools
import com.neoproc.neoproccamx.camera.FrameGeometry
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.HotPathScheduling
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.pipeline.HighEndRuntimeTier
import com.neoproc.neoproccamx.rendering.CameraVulkanPreviewView
import com.neoproc.neoproccamx.rendering.VulkanRuntimeManager
import com.neoproc.neoproccamx.core.RuntimeStageTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

internal object FramePipelineImageDrainPolicy {
    fun <T> drainAvailable(
        maxItems: Int,
        acquire: () -> T?,
        consume: (T) -> Unit
    ): Int {
        val safeMaxItems = maxItems.coerceAtLeast(1)
        var drainedItems = 0
        while (drainedItems < safeMaxItems) {
            val item = acquire() ?: break
            drainedItems++
            consume(item)
        }
        return drainedItems
    }
}

/**
 * FramePipelineManager — High-performance frame analysis engine.
 *
 * Optimized for 60 FPS flagship performance and zero-lag 8K preview.
 */
class FramePipelineManager {
    companion object {
        private const val DEFAULT_STATS_LONG_EDGE = 320
        private const val DEFAULT_MASK_LONG_EDGE = 320
        private const val SLOW_FRAME_PROCESS_MS = 28L
        private const val MAX_RAW_YUV_IMAGE_SINK_DRAIN_IMAGES = 32
        private const val MAX_HARDWARE_IMPORT_DRAIN_IMAGES = 16
    }

    val latestFrame = AtomicReference<RawYuvFrame?>(null)

    private val _histogram = MutableStateFlow<IntArray?>(null)
    val histogram: StateFlow<IntArray?> = _histogram.asStateFlow()

    private val _waveformColumns = MutableStateFlow<IntArray?>(null)
    val waveformColumns: StateFlow<IntArray?> = _waveformColumns.asStateFlow()

    private val _avgLuma = MutableStateFlow(0f)
    val avgLuma: StateFlow<Float> = _avgLuma.asStateFlow()

    // Fraction of pixels whose reconstructed RED channel is near clipping (H.Sup15 red-aware highlight
    // protection): luma-only stats are blind to red clipping, so this feeds the capture EV governor.
    private val _redHighlightClipFraction = MutableStateFlow(0f)
    val redHighlightClipFraction: StateFlow<Float> = _redHighlightClipFraction.asStateFlow()

    private val _sceneAnalysis = MutableStateFlow<SceneAnalysis?>(null)
    val sceneAnalysis: StateFlow<SceneAnalysis?> = _sceneAnalysis.asStateFlow()

    private val _captureRecommendation = MutableStateFlow<CaptureRecommendation?>(null)
    val captureRecommendation: StateFlow<CaptureRecommendation?> = _captureRecommendation.asStateFlow()
    private val _peakingMask = MutableStateFlow<FocusPeakingMask?>(null)
    val peakingMask: StateFlow<FocusPeakingMask?> = _peakingMask.asStateFlow()
    private val _zebraMask = MutableStateFlow<ZebraMask?>(null)
    val zebraMask: StateFlow<ZebraMask?> = _zebraMask.asStateFlow()

    private val running = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)
    private val decisionEngine = SceneDecisionEngine(
        strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
    )
    private val byteArrayPool = ByteArrayPool()
    // TIER 2 OPTIMIZATION: YUV frame pre-allocation pool + frame skip window
    private val yuvFramePool = YuvFramePool(maxPoolSize = 7)
    private val frameSkipWindow = FrameSkipWindow(targetFps = 25, windowSizeMs = 1000L)
    private val processingExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(namedThreadFactory("FramePipeline"))
    private val hardwareDispatchExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(namedThreadFactory("FramePipeline-HardwareImport"))
    private val hardwareFrameDispatcher = RealtimeFrameDispatcher<Image>(
        // Deepened (2->15, reversible via RecordPipelineDepthFlag) so a transient
        // GPU-submit/encoder-surface stall is absorbed. In strict high-end mode the dispatcher must
        // not conflate/replace queued frames; any stall is exposed as a reject/failure so the recorder
        // refuses certification instead of hiding a temporal downgrade.
        capacity = RecordPipelineDepthFlag.hardwareImportCapacity(),
        acquireGuardNs = RecordPipelineDepthFlag.hardwareImportAcquireGuardNs(),
        closeFrame = { it.close() },
        allowsStaleFrameConflation = !HighEndQualityPolicy.isHighEndQualityActive()
    )
    private val hardwareDispatchScheduled = AtomicBoolean(false)
    private val hardwareSubmitInFlight = AtomicBoolean(false)
    private val pendingHardwareReader = AtomicReference<ImageReader?>(null)
    private val sensorPresentationClock = SensorPresentationClock()
    private val frameSequence = AtomicLong(0L)
    private val busyDropCounter = AtomicLong(0L)
    private val hardwareCallbackCounter = AtomicLong(0L)
    private val hardwareAcquireFailureCounter = AtomicLong(0L)
    private val hardwareAcquireSkippedCounter = AtomicLong(0L)
    private val hardwareSubmittedCounter = AtomicLong(0L)
    private val hardwareClosedCounter = AtomicLong(0L)
    private val hardwareSubmissionFailureCounter = AtomicLong(0L)
    private val lastHardwareImportRejectLogElapsedMs = AtomicLong(0L)
    private val rawYuvImageCallbackCounter = AtomicLong(0L)
    private val rawYuvImageOwnershipTransferCounter = AtomicLong(0L)
    private val rawYuvImageSinkDeclinedCounter = AtomicLong(0L)
    private val rawYuvImageUnsupportedCounter = AtomicLong(0L)
    // Observe-only one-shot marker: proves whether the recording sink ever receives a frame on
    // devices that suppress android.util.Log. Reset whenever a new sink is installed.
    private val p010FirstSinkDeliveryLogged = AtomicBoolean(false)

    @Volatile private var previousLumaFrame: ByteArray? = null
    // Mirrors previousLumaFrame for the analysis frame's pooled U/V chroma: the frame published one refresh
    // ago, whose chroma buffers are returned to byteArrayPool once the next frame is published (a one-refresh
    // safety margin so the async SNCE reader that grabbed it has moved on). Kills the per-frame chroma alloc
    // churn on the analysis hot path (luma was pooled; chroma was not).
    @Volatile private var previousAnalysisChromaFrame: RawYuvFrame? = null
    @Volatile private var peakingEnabled = false
    @Volatile private var peakingIntensity = 0.55f
    @Volatile private var zebraEnabled = false
    @Volatile private var zebraThreshold = 0.9f
    @Volatile private var waveformEnabled = false
    @Volatile private var aiMotionCompensationEnabled = false

    @Volatile private var previewView: CameraVulkanPreviewView? = null
    @Volatile private var aiCameraPipeline: AICameraPipeline? = null
    @Volatile private var frameGeometry: FrameGeometry? = null
    @Volatile private var vulkanRuntime: VulkanRuntimeManager? = null
    @Volatile private var peakingFocusRegion: Triple<Float, Float, Float>? = null
    @Volatile private var runtimePreviewFpsHint: Float = HighEndQualityPolicy.targetPreviewFps.toFloat()
    @Volatile private var runtimeRecordingHint: Boolean = false
    @Volatile private var importRoutingState = FrameImportRoutingState()
    // Guards every read-modify-write of importRoutingState. It is mutated from the dispatch/executor
    // thread (frame-load eval, lastFrameProcessTimeMs) AND the hardware-import callback thread
    // (recordHardwareImport); without a common lock a copy() on one thread clobbered freshly-incremented
    // overload/skip counters on the other (lost update), under-counting overload exactly under load.
    private val routingStateLock = Any()
    // FIX D-1(A): serializes the hardware-import drain across the Camera2 hardware-import listener
    // thread (enqueueHardware) and the hardware-dispatch executor thread
    // (drainPendingHardwareReaderIfCapacity) so reader.acquireNextImage() + RealtimeFrameDispatcher.offer()
    // never run concurrently. Gated by HardwareDrainSerializationFlag (default ON).
    private val hardwareDrainLock = Any()
    private val sensorPacedFrameSelector = SensorPacedFrameSelector()
    @Volatile private var rawYuvFrameSink: RawYuvFrameSink? = null
    @Volatile private var rawYuvImageSink: RawYuvImageSink? = null

    private data class LumaPlaneSnapshot(
        val width: Int,
        val height: Int,
        val luma: ByteArray,
        val timestamp: Long
    )

    fun connectPreviewView(view: CameraVulkanPreviewView) { previewView = view }
    fun connectAICameraPipeline(pipeline: AICameraPipeline?) { aiCameraPipeline = pipeline }
    fun connectVulkanRuntime(runtime: VulkanRuntimeManager?) { vulkanRuntime = runtime }
    fun setFrameGeometry(geometry: FrameGeometry?) { frameGeometry = geometry }
    fun setRawYuvFrameSink(sink: RawYuvFrameSink?) { rawYuvFrameSink = sink }
    fun setRawYuvImageSink(sink: RawYuvImageSink?) {
        if (sink != null) {
            rawYuvImageCallbackCounter.set(0L)
            rawYuvImageOwnershipTransferCounter.set(0L)
            rawYuvImageSinkDeclinedCounter.set(0L)
            rawYuvImageUnsupportedCounter.set(0L)
            p010FirstSinkDeliveryLogged.set(false)
            runCatching {
                NeoLogger.i("FramePipeline", "P010_SINK_SET running=${running.get()}")
            }
        }
        rawYuvImageSink = sink
    }
    fun rawYuvImageSinkTelemetry(): RawYuvImageSinkTelemetry =
        RawYuvImageSinkTelemetry(
            sourceImageCallbacks = rawYuvImageCallbackCounter.get(),
            ownershipTransferred = rawYuvImageOwnershipTransferCounter.get(),
            sinkDeclined = rawYuvImageSinkDeclinedCounter.get(),
            unsupportedImages = rawYuvImageUnsupportedCounter.get()
        )
    fun setRuntimePerformanceHint(previewFps: Float, recording: Boolean) {
        runtimePreviewFpsHint = previewFps.coerceAtLeast(1f)
        runtimeRecordingHint = recording
    }

    fun awaitIdleBeforeReaderClose(timeoutMs: Long): Boolean {
        val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        while (hasInFlightImages()) {
            if (SystemClock.elapsedRealtime() >= deadlineMs) return false
            try {
                Thread.sleep(4L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    /**
     * Bounded poll (elapsed-time loop over the [hardwareSubmitInFlight] AtomicBoolean) that returns
     * true once no native hardware-import submit is in flight, or false when [timeoutMs] elapses.
     * Strictly bounded — never an unbounded native mutex.
     */
    fun awaitHardwareSubmitQuiescent(timeoutMs: Long): Boolean {
        val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        while (hardwareSubmitInFlight.get()) {
            if (SystemClock.elapsedRealtime() >= deadlineMs) return false
            try {
                Thread.sleep(2L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    private fun hasInFlightImages(): Boolean =
        processing.get() ||
            hardwareDispatchScheduled.get() ||
            hardwareFrameDispatcher.hasQueuedFrames()

    fun setDedicatedHardwareImportEnabled(enabled: Boolean) {
        synchronized(routingStateLock) {
            importRoutingState = FrameImportRoutingPolicy.setDedicatedHardwareImportEnabled(
                state = importRoutingState,
                enabled = enabled,
                nowElapsedMs = SystemClock.elapsedRealtime()
            )
        }
    }
    fun setPeakingFocusRegion(normalizedX: Float, normalizedY: Float, radius: Float) {
        peakingFocusRegion = Triple(
            normalizedX.coerceIn(0f, 1f),
            normalizedY.coerceIn(0f, 1f),
            radius.coerceIn(0f, 1f)
        )
    }
    fun clearPeakingFocusRegion() { peakingFocusRegion = null }

    fun setPeakingEnabled(enabled: Boolean) { peakingEnabled = enabled }
    fun setPeakingIntensity(intensity: Float) { peakingIntensity = intensity.coerceIn(0f, 1f) }
    fun setZebraEnabled(enabled: Boolean) { zebraEnabled = enabled }
    fun setZebraThreshold(threshold: Float) { zebraThreshold = threshold.coerceIn(0.55f, 0.99f) }
    fun setWaveformEnabled(enabled: Boolean) { waveformEnabled = enabled }
    fun setAiMotionCompensationEnabled(enabled: Boolean) { aiMotionCompensationEnabled = enabled }

    // ── Frame buffer diagnostics (TIER 2 optimization) ──
    fun getYuvPoolStats(): String {
        val yuvSnapshot = yuvFramePool.snapshot()
        val byteSnapshot = byteArrayPool.snapshot()
        return listOf(
            FramePipelineTelemetryPolicy.formatPoolUtilization("YUV", yuvSnapshot),
            FramePipelineTelemetryPolicy.formatPoolUtilization("Luma", byteSnapshot)
        ).joinToString(" | ")
    }
    fun getFrameSkipDiagnostics(): String =
        FramePipelineTelemetryPolicy.formatRollingDropSummary(
            summary = frameSkipWindow.getDropSummary(SystemClock.elapsedRealtime()),
            busyDrops = busyDropCounter.get()
        )
    fun getFrameDropRate(): Float =
        frameSkipWindow.getDropSummary(SystemClock.elapsedRealtime()).dropRate
    fun getRealtimeDispatchDiagnostics(): String {
        val telemetry = hardwareFrameDispatcher.telemetry()
        val tuning = PipelineOrchestrator.resolveBackpressureTuning(
            droppedFrames = telemetry.droppedFrames,
            rejectedFrames = telemetry.rejectedFrames,
            averageQueueLatencyNs = telemetry.averageQueueLatencyNs,
            strictNoQualityDowngrade = strictNoQualityDowngradeActive()
        )
        return "realtimeDispatch accepted=${telemetry.acceptedFrames} " +
            "dropped=${telemetry.droppedFrames} rejected=${telemetry.rejectedFrames} " +
            "avgQueueNs=${telemetry.averageQueueLatencyNs} ${sensorPresentationClock.telemetrySignature()} " +
            "strictNoConflation=${!hardwareFrameDispatcher.allowsStaleFrameConflation} " +
            "tuning=${tuning.reason}"
    }

    fun realtimeDispatchTelemetry(): RealtimeFrameDispatchTelemetry =
        hardwareFrameDispatcher.telemetry()

    fun realtimeDispatchStrictNoConflation(): Boolean =
        !hardwareFrameDispatcher.allowsStaleFrameConflation
    fun start() { 
        running.set(true)
        frameSkipWindow.reset()
        busyDropCounter.set(0L)
        hardwareCallbackCounter.set(0L)
        hardwareSubmissionFailureCounter.set(0L)
        rawYuvImageCallbackCounter.set(0L)
        rawYuvImageOwnershipTransferCounter.set(0L)
        rawYuvImageSinkDeclinedCounter.set(0L)
        rawYuvImageUnsupportedCounter.set(0L)
    }
    fun stop() {
        running.set(false)
        previousLumaFrame?.let(byteArrayPool::release)
        previousLumaFrame = null
        previousAnalysisChromaFrame?.let { releaseChroma(it) }
        previousAnalysisChromaFrame = null
        byteArrayPool.clear()
        yuvFramePool.clear()
        hardwareFrameDispatcher.clear()
        hardwareDispatchScheduled.set(false)
        pendingHardwareReader.set(null)
        frameSkipWindow.reset()
        _peakingMask.value = null
        _zebraMask.value = null
        synchronized(routingStateLock) { importRoutingState = FrameImportRoutingState() }
        sensorPacedFrameSelector.reset()
        rawYuvFrameSink = null
        rawYuvImageSink = null
    }
    fun destroy() {
        stop()
        processingExecutor.shutdownNow()
        hardwareDispatchExecutor.shutdownNow()
    }

    fun enqueue(reader: ImageReader) {
        val rawYuvImageSinkSnapshot = rawYuvImageSink
        if (rawYuvImageSinkSnapshot != null) {
            val drainedImages = FramePipelineImageDrainPolicy.drainAvailable(
                maxItems = MAX_RAW_YUV_IMAGE_SINK_DRAIN_IMAGES,
                acquire = {
                    try {
                        reader.acquireNextImage()
                    } catch (_: Exception) {
                        null
                    }
                },
                consume = { image ->
                    processAcquiredImage(image, rawYuvImageSinkSnapshot)
                }
            )
            if (drainedImages == MAX_RAW_YUV_IMAGE_SINK_DRAIN_IMAGES) {
                Log.d("FramePipeline", "Raw YUV image sink drain reached per-callback cap=$drainedImages")
            }
            return
        }
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return
        processAcquiredImage(image, rawYuvImageSinkSnapshot)
    }

    private fun processAcquiredImage(
        image: Image,
        rawYuvImageSinkSnapshot: RawYuvImageSink?
    ) {
        if (!running.get()) { image.close(); return }
        val timestampNs = try { image.timestamp } catch (_: Exception) { 0L }
        val timestampMs = TimeUnit.NANOSECONDS.toMillis(timestampNs)
        val currentFrameSeq = frameSequence.incrementAndGet()
        val startProcessTimeMs = SystemClock.elapsedRealtime()
        if (rawYuvImageSinkSnapshot != null) {
            rawYuvImageCallbackCounter.incrementAndGet()
            val rawYuvImageSupported = Yuv420ImageTools.isSupported(image)
            if (p010FirstSinkDeliveryLogged.compareAndSet(false, true)) {
                runCatching {
                    NeoLogger.i(
                        "FramePipeline",
                        "P010_FIRST_SINK_DELIVERY format=${image.format} " +
                            "${image.width}x${image.height} supported=$rawYuvImageSupported"
                    )
                }
            }
            if (rawYuvImageSupported) {
                val ownershipTransferred = runCatching {
                    rawYuvImageSinkSnapshot.onRawYuvImage(image, timestampNs)
                }.onFailure { throwable ->
                    Log.w("FramePipeline", "Raw YUV image sink failed", throwable)
                }.getOrDefault(false)
                if (ownershipTransferred) {
                    rawYuvImageOwnershipTransferCounter.incrementAndGet()
                    return
                }
                rawYuvImageSinkDeclinedCounter.incrementAndGet()
            } else {
                rawYuvImageUnsupportedCounter.incrementAndGet()
            }
        }
        val rawYuvSinkSnapshot = rawYuvFrameSink
        if (rawYuvSinkSnapshot != null) {
            snapshotRawYuvFrame(image, timestampNs)?.let { frame ->
                runCatching { rawYuvSinkSnapshot.onRawYuvFrame(frame) }
                    .onFailure { throwable ->
                        Log.w("FramePipeline", "Raw YUV frame sink failed", throwable)
                    }
            }
        }

        // Adaptive load management - skip frames under heavy load. Read-compute-write under the lock so
        // a concurrent recordHardwareImport()/copy() on the callback thread cannot clobber this update.
        val (_, shouldProcess) = synchronized(routingStateLock) {
            val evaluated = FrameImportRoutingPolicy.evaluateFrameLoad(
                state = importRoutingState,
                frameProcessTimeMs = importRoutingState.lastFrameProcessTimeMs,
                frameSequence = currentFrameSeq
            )
            importRoutingState = evaluated.first
            evaluated
        }

        if (!shouldProcess) {
            // Record frame drop in skip window
            val shouldSuggestStrideAdjust = !frameSkipWindow.shouldProcessFrame(SystemClock.elapsedRealtime())
            if (shouldSuggestStrideAdjust) {
                Log.d("FramePipeline", "Consecutive frame drops detected; stride adjustment recommended")
            }
            // Still submit to Vulkan for preview, just skip CPU analysis
            if (FrameImportRoutingPolicy.shouldImportAnalysisFrame(
                    state = importRoutingState,
                    nowElapsedMs = startProcessTimeMs
                )
            ) {
                submitImageToVulkan(image)
            }
            image.close()
            return
        }

        if (!processing.compareAndSet(false, true)) {
            frameSkipWindow.shouldProcessFrame(SystemClock.elapsedRealtime())
            val busyDrops = busyDropCounter.incrementAndGet()
            if (busyDrops % 30L == 0L) {
                Log.d("FramePipeline", "Busy drops=$busyDrops (processing thread saturated)")
            }
            image.close()
            return
        }

        // #6 fix: guard the synchronous pre-dispatch section AND processingExecutor.execute() itself. If any
        // of it throws BEFORE the executor runnable runs (e.g. snapshotLumaPlane/submitImageToVulkan throw
        // IllegalStateException because a session rebuild closed the analysis reader mid-flight, or execute()
        // throws RejectedExecutionException during teardown), the runnable's finally never fires — leaving
        // `processing` stuck true (analysis frozen for the whole session) and one Image leaked. The try must
        // span the val declarations below because the executor lambda captures them. Exceptions thrown later
        // ON the executor thread are unaffected — the lambda keeps its own try/finally.
        try {
        val geometrySnapshot = frameGeometry
        val aiPipelineSnapshot = aiCameraPipeline
        val shouldImportAnalysisFrame = FrameImportRoutingPolicy.shouldImportAnalysisFrame(
            state = importRoutingState,
            nowElapsedMs = startProcessTimeMs
        )
        val strideMultiplier = FrameImportRoutingPolicy.getAdaptiveStrideMultiplier(importRoutingState).toLong()
        val realtimeAnalysisPlan = FrameRealtimeAnalysisPolicy.resolve(
            previewFps = runtimePreviewFpsHint,
            recording = runtimeRecordingHint,
            adaptiveStrideMultiplier = strideMultiplier
        )
        val shouldRunAiFrame = aiPipelineSnapshot != null && (
            if (SensorPacedFrameSelectorFlag.enabled) {
                sensorPacedFrameSelector.shouldAnalyze(
                    sensorTimestampNs = timestampNs,
                    previewFps = runtimePreviewFpsHint,
                    aiFrameStride = realtimeAnalysisPlan.aiFrameStride
                )
            } else {
                realtimeAnalysisPlan.shouldAnalyze(currentFrameSeq)
            }
        )
        if (shouldImportAnalysisFrame) {
            submitImageToVulkan(image)
        }
        val peakingFocusRegionSnapshot = peakingFocusRegion
        val captureLumaSnapshot = shouldCaptureLumaSnapshot(
            frameSequence = currentFrameSeq,
            strideMultiplier = strideMultiplier
        )
        val deferLumaSnapshot = DeferredLumaSnapshotFlag.enabled
        val lumaSnapshot =
            if (captureLumaSnapshot && !deferLumaSnapshot) {
                snapshotLumaPlane(image, timestampNs)
            } else {
                null
            }

        processingExecutor.execute {
            val processStart = SystemClock.elapsedRealtime()
            try {
                val resolvedLumaSnapshot =
                    if (captureLumaSnapshot && deferLumaSnapshot) {
                        snapshotLumaPlane(image, timestampNs)
                    } else {
                        lumaSnapshot
                    }
                resolvedLumaSnapshot?.let { snapshot ->
                    val sceneStatsStride = resolveSceneStatsStride(strideMultiplier)
                    val shouldRefreshSceneStats =
                        currentFrameSeq == 1L ||
                            currentFrameSeq % sceneStatsStride == 0L ||
                            _sceneAnalysis.value == null ||
                            _captureRecommendation.value == null
                    var retainedAsPreviousFrame = false

                    if (shouldRefreshSceneStats) {
                        val stats = computeSceneStats(snapshot)
                        _histogram.value = stats.histogram
                        _avgLuma.value = stats.avgLuma
                        _waveformColumns.value = stats.waveformColumns

                        val analysisRawFrame =
                            snapshotAnalysisRawFrame(image, snapshot, timestampNs)
                        latestFrame.set(analysisRawFrame)
                        // RED-aware highlight-protect input: luma-only stats above cannot see a
                        // clipping red channel, so compute it from the analysis Y + Cr planes here.
                        _redHighlightClipFraction.value =
                            FrameAnalysisMath.computeRedHighlightClipFraction(
                                analysisRawFrame,
                                resolveStatsLongEdge()
                            )
                        val previous = previousLumaFrame
                        val analysis = decisionEngine.analyze(
                            analysisRawFrame,
                            stats.histogram,
                            stats.avgLuma,
                            previous
                        )
                        _sceneAnalysis.value = analysis
                        _captureRecommendation.value = decisionEngine.buildCaptureRecommendation(analysis)

                        previousLumaFrame = snapshot.luma
                        retainedAsPreviousFrame = true
                        previous?.let(byteArrayPool::release)
                        // Return the prior published frame's pooled chroma one refresh late (mirrors the
                        // luma release above), then track the just-published frame for the next round.
                        previousAnalysisChromaFrame?.let { prior -> releaseChroma(prior) }
                        previousAnalysisChromaFrame = analysisRawFrame
                    }

                    // Adaptive peaking/zebra stride based on load
                    val peakingStride = if (strictHighEndPipelineEnabled()) {
                        val plan = strictHighEndDiagnosticsPlan()
                        max(1L, plan.peakingStride * strideMultiplier)
                    } else {
                        // LAG FIX: recompute peaking every captured frame at nominal load; the
                        // adaptive strideMultiplier still backs it off under sustained overload.
                        1L * strideMultiplier
                    }
                    if (peakingEnabled && currentFrameSeq % peakingStride == 0L) {
                        _peakingMask.value = buildFocusPeakingMask(snapshot, peakingFocusRegionSnapshot)
                    } else if (!peakingEnabled) {
                        _peakingMask.value = null
                    }

                    val zebraStride = if (strictHighEndPipelineEnabled()) {
                        val plan = strictHighEndDiagnosticsPlan()
                        max(1L, plan.zebraStride * strideMultiplier)
                    } else {
                        // LAG FIX: zebra every captured frame at nominal load, backing off with load.
                        when (strideMultiplier) {
                            1L -> 1L
                            2L -> 2L
                            3L -> 3L
                            else -> 4L
                        }
                    }
                    if (zebraEnabled && currentFrameSeq % zebraStride == 0L) {
                        _zebraMask.value = buildZebraMask(snapshot)
                    } else if (!zebraEnabled) {
                        _zebraMask.value = null
                    }

                    if (!retainedAsPreviousFrame) {
                        byteArrayPool.release(snapshot.luma)
                    }
                }

                if (aiPipelineSnapshot != null && shouldRunAiFrame) {
                    aiPipelineSnapshot.analyzeMediaImage(
                        image = image,
                        timestampMs = timestampMs,
                        rotationDegrees = geometrySnapshot?.aiRotationDegrees ?: 0,
                        frameGeometry = geometrySnapshot,
                        previewMaxDimension = realtimeAnalysisPlan.aiProxyLongEdge,
                        releaseImage = { image.close() }
                    )
                } else {
                    image.close()
                }
            } finally {
                // Record frame processing time for adaptive load management
                val processTimeMs = SystemClock.elapsedRealtime() - processStart
                RuntimeStageTelemetry.recordDuration(
                    tag = "FramePipeline",
                    stage = "processFrame",
                    durationMs = processTimeMs,
                    slowThresholdMs = SLOW_FRAME_PROCESS_MS
                )
                synchronized(routingStateLock) {
                    importRoutingState = importRoutingState.copy(lastFrameProcessTimeMs = processTimeMs)
                }
                // Record successful frame processing in skip window
                frameSkipWindow.shouldProcessFrame(SystemClock.elapsedRealtime())
                processing.set(false)
            }
        }
        } catch (t: Throwable) {
            // #6 fix (cont.): reached only when the executor runnable never ran, so its finally never fired.
            // Reset the processing gate and close the Image so the next frame proceeds and no buffer leaks.
            processing.set(false)
            runCatching { image.close() }
            if (t is RejectedExecutionException) {
                Log.d("FramePipeline", "processFrame executor rejected during teardown; frame dropped")
            } else {
                Log.w("FramePipeline", "processFrame synchronous stage threw; pipeline gate reset", t)
            }
        }
    }

    fun enqueueHardware(reader: ImageReader) {
        drainHardwareReader(reader, deferred = false)
    }

    private fun drainHardwareReader(reader: ImageReader, deferred: Boolean): Int {
        // FIX D-1(A): the Camera2 hardware-import listener thread (enqueueHardware) and the
        // hardware-dispatch executor thread (drainPendingHardwareReaderIfCapacity) both reach this
        // drain, so without serialization two threads call reader.acquireNextImage() concurrently.
        // ImageReader is not documented safe for concurrent acquire (it races the max-images accounting
        // and native buffer state), and the shared RealtimeFrameDispatcher.offer() then collides on the
        // same ring slot. Serialize the whole drain body so acquire + offer are atomic across both
        // producers. The critical section is bounded (<= hardwareImportDrainPerCallback acquires +
        // offers, NO GPU submit - that stays in drainHardwareDispatchQueue, outside this lock) and
        // neither caller is the UI thread, so it cannot ANR. Flag OFF restores the exact pre-fix path.
        if (!HardwareDrainSerializationFlag.enabled) {
            return drainHardwareReaderBody(reader, deferred)
        }
        return synchronized(hardwareDrainLock) {
            drainHardwareReaderBody(reader, deferred)
        }
    }

    private fun drainHardwareReaderBody(reader: ImageReader, deferred: Boolean): Int {
        val readerMaxImages = runCatching { reader.maxImages }
            .getOrDefault(MAX_HARDWARE_IMPORT_DRAIN_IMAGES)
            .coerceIn(1, MAX_HARDWARE_IMPORT_DRAIN_IMAGES)
        val queuedBeforeAcquire = hardwareFrameDispatcher.telemetry().queuedFrames
        val activeSubmit = if (hardwareSubmitInFlight.get()) 1 else 0
        val highWatermark = RecordPipelineDepthFlag.hardwareImportReaderHighWatermark(readerMaxImages)
        val acquireBudget = (highWatermark - queuedBeforeAcquire - activeSubmit).coerceAtLeast(0)
        if (acquireBudget <= 0) {
            pendingHardwareReader.set(reader)
            val skipped = hardwareAcquireSkippedCounter.incrementAndGet()
            if (skipped == 1L || skipped % 60L == 0L) {
                Log.w(
                    "FramePipeline",
                    "Hardware import acquire skipped count=$skipped queued=$queuedBeforeAcquire " +
                        "activeSubmit=$activeSubmit highWatermark=$highWatermark " +
                        "readerMaxImages=$readerMaxImages submitted=${hardwareSubmittedCounter.get()} " +
                        "closed=${hardwareClosedCounter.get()} deferredReaderDrain=true"
                )
            }
            scheduleHardwareDispatchDrain()
            return 0
        }
        val maxDrain = minOf(
            readerMaxImages,
            RecordPipelineDepthFlag.hardwareImportDrainPerCallback(),
            acquireBudget
        ).coerceAtLeast(1)
        val drainedImages = FramePipelineImageDrainPolicy.drainAvailable(
            maxItems = maxDrain,
            acquire = {
                try {
                    reader.acquireNextImage()
                } catch (t: Exception) {
                    val failureCount = hardwareAcquireFailureCounter.incrementAndGet()
                    val telemetry = hardwareFrameDispatcher.telemetry()
                    val nowMs = SystemClock.elapsedRealtime()
                    val lastLogMs = lastHardwareImportRejectLogElapsedMs.get()
                    val shouldLog = failureCount == 1L ||
                        failureCount % 30L == 0L ||
                        (nowMs - lastLogMs >= 1_000L &&
                            lastHardwareImportRejectLogElapsedMs.compareAndSet(lastLogMs, nowMs))
                    if (shouldLog) {
                        if (failureCount == 1L) {
                            lastHardwareImportRejectLogElapsedMs.set(nowMs)
                        }
                        Log.w(
                            "FramePipeline",
                            "Hardware import acquire failed count=$failureCount " +
                                "readerMaxImages=$readerMaxImages queued=${telemetry.queuedFrames} " +
                                "activeSubmit=${if (hardwareSubmitInFlight.get()) 1 else 0} " +
                                "highWatermark=$highWatermark " +
                                "accepted=${telemetry.acceptedFrames} dropped=${telemetry.droppedFrames} " +
                                "rejected=${telemetry.rejectedFrames} submitted=${hardwareSubmittedCounter.get()} " +
                                "closed=${hardwareClosedCounter.get()} reason=${t.javaClass.simpleName}: ${t.message}"
                        )
                    }
                    null
                }
            },
            consume = { image -> enqueueHardwareImage(image) }
        )
        if (drainedImages == maxDrain) {
            pendingHardwareReader.set(reader)
            Log.d(
                "FramePipeline",
                "Hardware import drain reached cap=$drainedImages deferred=$deferred queued=$queuedBeforeAcquire " +
                    "activeSubmit=$activeSubmit acquireBudget=$acquireBudget"
            )
        } else {
            pendingHardwareReader.compareAndSet(reader, null)
        }
        return drainedImages
    }

    private fun enqueueHardwareImage(image: Image) {
        if (!running.get()) {
            image.close()
            return
        }
        val callbackCount = hardwareCallbackCounter.incrementAndGet()
        if (callbackCount == 1L) {
            Log.i(
                "FramePipeline",
                "First Camera2 hardware-import frame received " +
                    "${image.width}x${image.height} format=${image.format}"
            )
        }
        val timestampNs = try { image.timestamp } catch (_: Exception) { 0L }
        val offer = hardwareFrameDispatcher.offer(
            frame = image,
            sensorTimestampNs = timestampNs,
            nowNs = System.nanoTime()
        )
        if (!offer.accepted) {
            Log.d("FramePipeline", "Hardware import frame rejected: ${offer.reason}")
            image.close()
            return
        }
        scheduleHardwareDispatchDrain()
    }

    private fun scheduleHardwareDispatchDrain() {
        if (!hardwareDispatchScheduled.compareAndSet(false, true)) return
        hardwareDispatchExecutor.execute {
            try {
                drainPendingHardwareReaderIfCapacity()
                drainHardwareDispatchQueue()
                drainPendingHardwareReaderIfCapacity()
            } finally {
                hardwareDispatchScheduled.set(false)
                if (
                    running.get() &&
                    (hardwareFrameDispatcher.hasQueuedFrames() || pendingHardwareReaderHasAcquireCapacity())
                ) {
                    scheduleHardwareDispatchDrain()
                }
            }
        }
    }

    private fun pendingHardwareReaderHasAcquireCapacity(): Boolean {
        val reader = pendingHardwareReader.get() ?: return false
        val readerMaxImages = runCatching { reader.maxImages }.getOrNull()
            ?: run {
                pendingHardwareReader.compareAndSet(reader, null)
                return false
            }
        val highWatermark = RecordPipelineDepthFlag.hardwareImportReaderHighWatermark(
            readerMaxImages.coerceIn(1, MAX_HARDWARE_IMPORT_DRAIN_IMAGES)
        )
        val activeSubmit = if (hardwareSubmitInFlight.get()) 1 else 0
        return highWatermark > hardwareFrameDispatcher.telemetry().queuedFrames + activeSubmit
    }

    private fun drainPendingHardwareReaderIfCapacity() {
        if (!running.get()) return
        val reader = pendingHardwareReader.get() ?: return
        if (!pendingHardwareReaderHasAcquireCapacity()) return
        drainHardwareReader(reader, deferred = true)
    }

    private fun drainHardwareDispatchQueue() {
        while (true) {
            val queued = hardwareFrameDispatcher.poll() ?: return
            val image = queued.frame
            val presentationTimeNs = sensorPresentationClock.presentationTimeFor(
                sensorTimestampNs = queued.sensorTimestampNs,
                nowNs = System.nanoTime()
            )
            try {
                hardwareSubmitInFlight.set(true)
                val submittedToVulkan = submitImageToVulkan(image, presentationTimeNs)
                if (submittedToVulkan) {
                    val submitted = hardwareSubmittedCounter.incrementAndGet()
                    sensorPresentationClock.recordGpuCompletion(
                        sensorTimestampNs = queued.sensorTimestampNs,
                        completionNs = System.nanoTime()
                    )
                    val telemetry = hardwareFrameDispatcher.telemetry()
                    if (submitted % 60L == 0L) {
                        Log.i(
                            "FramePipeline",
                            "Hardware import submitted=$submitted closed=${hardwareClosedCounter.get()} " +
                                "queued=${telemetry.queuedFrames} accepted=${telemetry.acceptedFrames} " +
                                "dropped=${telemetry.droppedFrames} rejected=${telemetry.rejectedFrames} " +
                                "avgQueueLatencyNs=${telemetry.averageQueueLatencyNs}"
                        )
                    }
                    val tuning = PipelineOrchestrator.resolveBackpressureTuning(
                        droppedFrames = telemetry.droppedFrames,
                        rejectedFrames = telemetry.rejectedFrames,
                        averageQueueLatencyNs = telemetry.averageQueueLatencyNs,
                        strictNoQualityDowngrade = strictNoQualityDowngradeActive()
                    )
                    if (tuning.reason != "stable-full-quality") {
                        Log.d("FramePipeline", "Realtime dispatch tuning: ${tuning.reason}")
                    }
                    synchronized(routingStateLock) {
                        importRoutingState = FrameImportRoutingPolicy.recordHardwareImport(
                            state = importRoutingState,
                            nowElapsedMs = SystemClock.elapsedRealtime()
                        )
                    }
                } else {
                    val failureCount = hardwareSubmissionFailureCounter.incrementAndGet()
                    val nowMs = SystemClock.elapsedRealtime()
                    val lastLogMs = lastHardwareImportRejectLogElapsedMs.get()
                    val shouldLog = failureCount == 1L ||
                        failureCount % 300L == 0L ||
                        (nowMs - lastLogMs >= 5_000L &&
                            lastHardwareImportRejectLogElapsedMs.compareAndSet(lastLogMs, nowMs))
                    if (shouldLog) {
                        if (failureCount == 1L) {
                            lastHardwareImportRejectLogElapsedMs.set(nowMs)
                        }
                        Log.w(
                            "FramePipeline",
                            "Vulkan rejected hardware-import frame count=$failureCount " +
                                "runtime=${vulkanRuntime?.capabilitySummary?.value.orEmpty()}"
                        )
                    }
                }
            } finally {
                image.close()
                hardwareClosedCounter.incrementAndGet()
                hardwareSubmitInFlight.set(false)
                drainPendingHardwareReaderIfCapacity()
            }
        }
    }

    private fun submitImageToVulkan(image: Image, presentationTimeNs: Long = 0L): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vulkanRuntime?.submitCameraImage(image, presentationTimeNs) == true
        } else {
            false
        }
    }

    private fun strictNoQualityDowngradeActive(): Boolean =
        HighEndQualityPolicy.isHighEndQualityActive() && runtimeRecordingHint

    private fun resolveLumaSnapshotDimensions(width: Int, height: Int): LumaSnapshotDimensions =
        FrameLumaSnapshotPolicy.resolveDimensions(
            sourceWidth = width,
            sourceHeight = height,
            targetLongEdge = resolveLumaSnapshotLongEdge()
        )

    private fun resolveLumaSnapshotLongEdge(): Int {
        var target = resolveStatsLongEdge()
        if (peakingEnabled || zebraEnabled) {
            target = max(target, resolveMaskLongEdge())
        }
        return target.coerceAtLeast(1)
    }

    private fun snapshotLumaPlane(image: Image, timestampNs: Long): LumaPlaneSnapshot? {
        val p010 = Yuv420ImageTools.isP010(image)
        if ((!p010 && image.format != ImageFormat.YUV_420_888) || image.planes.isEmpty()) return null
        val plane = image.planes[0]
        val rawBuffer = plane.buffer.duplicate().slice()
        val rawLimit = rawBuffer.limit()
        val target = resolveLumaSnapshotDimensions(image.width, image.height)
        val compactSize = target.width * target.height
        val compact = byteArrayPool.acquire(compactSize)
        val width = target.width
        val height = target.height
        val sourceWidth = image.width
        val sourceHeight = image.height
        val sourcePixelStride = plane.pixelStride.coerceAtLeast(if (p010) 2 else 1)
        var dstIndex = 0
        if (!p010 && width == sourceWidth && height == sourceHeight && plane.pixelStride == 1) {
            for (y in 0 until height) {
                val srcIndex = y * plane.rowStride
                if (srcIndex >= 0 && srcIndex + width <= rawLimit) {
                    rawBuffer.position(srcIndex)
                    rawBuffer.get(compact, dstIndex, width)
                    dstIndex += width
                } else {
                    // Fallback safety fill when row metadata is inconsistent.
                    java.util.Arrays.fill(compact, dstIndex, dstIndex + width, 0)
                    dstIndex += width
                }
            }
        } else if (width == sourceWidth && height == sourceHeight) {
            for (y in 0 until height) {
                var srcIndex = y * plane.rowStride
                for (x in 0 until width) {
                    compact[dstIndex++] =
                        if (srcIndex in 0 until rawLimit) {
                            readAnalysisLuma8(rawBuffer, srcIndex, p010)
                        } else {
                            0
                        }
                    srcIndex += sourcePixelStride
                }
            }
        } else {
            for (targetY in 0 until height) {
                val sourceY = ((targetY.toFloat() / height.toFloat()) * sourceHeight)
                    .toInt()
                    .coerceIn(0, sourceHeight - 1)
                for (targetX in 0 until width) {
                    val sourceX = ((targetX.toFloat() / width.toFloat()) * sourceWidth)
                        .toInt()
                        .coerceIn(0, sourceWidth - 1)
                    val srcIndex = sourceY * plane.rowStride + sourceX * sourcePixelStride
                    compact[dstIndex++] =
                        if (srcIndex in 0 until rawLimit) {
                            readAnalysisLuma8(rawBuffer, srcIndex, p010)
                        } else {
                            0
                        }
                }
            }
        }
        return LumaPlaneSnapshot(
            width = width,
            height = height,
            luma = compact,
            timestamp = timestampNs
        )
    }

    /**
     * Convert one luma sample to the 8-bit analysis scale. P010 remains untouched in the camera,
     * encoder and record paths; only this small metering snapshot is normalized (64->16, 940->235).
     */
    private fun readAnalysisLuma8(
        buffer: java.nio.ByteBuffer,
        index: Int,
        p010: Boolean
    ): Byte {
        if (!p010) return buffer.get(index)
        if (index < 0 || index + 1 >= buffer.limit()) return 0
        val lo = buffer.get(index).toInt() and 0xFF
        val hi = buffer.get(index + 1).toInt() and 0xFF
        val code10 = (((hi shl 8) or lo) ushr 6).coerceIn(0, 1023)
        return ((code10 + 2) ushr 2).coerceIn(0, 255).toByte()
    }

    private fun snapshotLumaPlane(frame: RawYuvFrame): LumaPlaneSnapshot? {
        if (frame.width <= 0 || frame.height <= 0 || frame.yBytes.isEmpty()) return null
        val target = resolveLumaSnapshotDimensions(frame.width, frame.height)
        val compact = byteArrayPool.acquire(target.width * target.height)
        val yPixelStride = frame.yPixelStride.coerceAtLeast(1)
        var dstIndex = 0
        if (
            target.width == frame.width &&
            target.height == frame.height &&
            yPixelStride == 1 &&
            frame.yRowStride >= frame.width
        ) {
            for (y in 0 until target.height) {
                val srcIndex = y * frame.yRowStride
                if (srcIndex >= 0 && srcIndex + frame.width <= frame.yBytes.size) {
                    System.arraycopy(frame.yBytes, srcIndex, compact, dstIndex, target.width)
                } else {
                    java.util.Arrays.fill(compact, dstIndex, dstIndex + target.width, 0)
                }
                dstIndex += target.width
            }
        } else {
            for (targetY in 0 until target.height) {
                val sourceY = ((targetY.toFloat() / target.height.toFloat()) * frame.height)
                    .toInt()
                    .coerceIn(0, frame.height - 1)
                for (targetX in 0 until target.width) {
                    val sourceX = ((targetX.toFloat() / target.width.toFloat()) * frame.width)
                        .toInt()
                        .coerceIn(0, frame.width - 1)
                    val srcIndex = sourceY * frame.yRowStride + sourceX * yPixelStride
                    compact[dstIndex++] =
                        if (srcIndex in frame.yBytes.indices) {
                            frame.yBytes[srcIndex]
                        } else {
                            0
                        }
                }
            }
        }
        return LumaPlaneSnapshot(
            width = target.width,
            height = target.height,
            luma = compact,
            timestamp = frame.timestamp
        )
    }

    private fun snapshotRawYuvFrame(image: Image, timestampNs: Long): RawYuvFrame? {
        if (!Yuv420ImageTools.isSupported(image)) return null
        val snapshot = runCatching { Yuv420ImageTools.snapshot(image) }
            .getOrElse { throwable ->
                Log.w("FramePipeline", "Failed to snapshot YUV frame for AI copy path", throwable)
                return null
            }
        return RawYuvFrame(
            width = snapshot.width,
            height = snapshot.height,
            yBytes = snapshot.yBytes,
            uBytes = snapshot.uBytes,
            vBytes = snapshot.vBytes,
            yRowStride = snapshot.yRowStride,
            yPixelStride = snapshot.yPixelStride,
            uRowStride = snapshot.uRowStride,
            vRowStride = snapshot.vRowStride,
            uPixelStride = snapshot.uPixelStride,
            vPixelStride = snapshot.vPixelStride,
            timestamp = timestampNs,
            bitDepth = snapshot.bitDepth
        )
    }

    /**
     * Scene-stats-only sibling of [snapshotRawYuvFrame]: copies ONLY the chroma planes (via
     * [Yuv420ImageTools.snapshotChromaOnly]) and leaves yBytes empty. [snapshotAnalysisRawFrame] never
     * reads source.yBytes — the analysis frame's luma always comes from the downscaled LumaPlaneSnapshot —
     * so skipping the full-resolution Y copy that Yuv420ImageTools.snapshot() would allocate and discard
     * yields a byte-identical analysis frame with less allocation/GC. Failure/empty-chroma semantics match
     * [snapshotRawYuvFrame] exactly (null -> luma-only fallback in the caller).
     */
    private fun snapshotAnalysisChromaSource(image: Image, timestampNs: Long): RawYuvFrame? {
        if (!Yuv420ImageTools.isSupported(image)) return null
        val snapshot = runCatching { Yuv420ImageTools.snapshotChromaOnly(image) }
            .getOrElse { throwable ->
                Log.w("FramePipeline", "Failed to snapshot YUV chroma for analysis copy path", throwable)
                return null
            }
        return RawYuvFrame(
            width = snapshot.width,
            height = snapshot.height,
            yBytes = snapshot.yBytes,
            uBytes = snapshot.uBytes,
            vBytes = snapshot.vBytes,
            yRowStride = snapshot.yRowStride,
            yPixelStride = snapshot.yPixelStride,
            uRowStride = snapshot.uRowStride,
            vRowStride = snapshot.vRowStride,
            uPixelStride = snapshot.uPixelStride,
            vPixelStride = snapshot.vPixelStride,
            timestamp = timestampNs,
            bitDepth = snapshot.bitDepth
        )
    }

    /** Returns an analysis frame's pooled U/V chroma buffers to the byte pool (no-op for empty chroma). */
    private fun releaseChroma(frame: RawYuvFrame) {
        if (frame.uBytes.isNotEmpty()) byteArrayPool.release(frame.uBytes)
        if (frame.vBytes.isNotEmpty()) byteArrayPool.release(frame.vBytes)
    }

    private fun snapshotAnalysisRawFrame(
        image: Image,
        lumaSnapshot: LumaPlaneSnapshot,
        timestampNs: Long
    ): RawYuvFrame {
        val source = if (AnalysisChromaOnlySnapshotFlag.enabled) {
            snapshotAnalysisChromaSource(image, timestampNs)
        } else {
            snapshotRawYuvFrame(image, timestampNs)
        }
        if (source == null || source.uBytes.isEmpty() || source.vBytes.isEmpty()) {
            return RawYuvFrame(
                width = lumaSnapshot.width,
                height = lumaSnapshot.height,
                yBytes = lumaSnapshot.luma,
                uBytes = ByteArray(0),
                vBytes = ByteArray(0),
                yRowStride = lumaSnapshot.width,
                yPixelStride = 1,
                uRowStride = 0,
                vRowStride = 0,
                uPixelStride = 0,
                vPixelStride = 0,
                timestamp = lumaSnapshot.timestamp
            )
        }

        // Scene analysis intentionally uses an 8-bit compact proxy even when the source is P010.
        // This is analysis-only: the 10-bit camera/encoder buffers are never rewritten or downcast.
        val sourceBytesPerSample = if (source.bitDepth > 8) 2 else 1
        val bytesPerSample = 1
        val chromaWidth = (lumaSnapshot.width + 1) / 2
        val chromaHeight = (lumaSnapshot.height + 1) / 2
        val chromaRowStride = chromaWidth * bytesPerSample
        // Pooled (was per-frame ByteArray): acquire returns an EXACT-size buffer, and the copy loop below
        // overwrites every byte, so reusing a dirty pooled buffer is safe. Returned to the pool one refresh
        // later via previousAnalysisChromaFrame (see the refresh block + stop()).
        val u = byteArrayPool.acquire(chromaRowStride * chromaHeight)
        val v = byteArrayPool.acquire(chromaRowStride * chromaHeight)
        val sourceChromaWidth = (source.width + 1) / 2
        val sourceChromaHeight = (source.height + 1) / 2

        for (targetY in 0 until chromaHeight) {
            val sourceY = ((targetY.toFloat() / chromaHeight.toFloat()) * sourceChromaHeight)
                .toInt()
                .coerceIn(0, sourceChromaHeight - 1)
            for (targetX in 0 until chromaWidth) {
                val sourceX = ((targetX.toFloat() / chromaWidth.toFloat()) * sourceChromaWidth)
                    .toInt()
                    .coerceIn(0, sourceChromaWidth - 1)
                copyAnalysisChromaSample(
                    source = source.uBytes,
                    sourceIndex = sourceY * source.uRowStride + sourceX * source.uPixelStride,
                    destination = u,
                    destinationIndex = targetY * chromaRowStride + targetX * bytesPerSample,
                    sourceBytesPerSample = sourceBytesPerSample
                )
                copyAnalysisChromaSample(
                    source = source.vBytes,
                    sourceIndex = sourceY * source.vRowStride + sourceX * source.vPixelStride,
                    destination = v,
                    destinationIndex = targetY * chromaRowStride + targetX * bytesPerSample,
                    sourceBytesPerSample = sourceBytesPerSample
                )
            }
        }

        return RawYuvFrame(
            width = lumaSnapshot.width,
            height = lumaSnapshot.height,
            yBytes = lumaSnapshot.luma,
            uBytes = u,
            vBytes = v,
            yRowStride = lumaSnapshot.width,
            yPixelStride = 1,
            uRowStride = chromaRowStride,
            vRowStride = chromaRowStride,
            uPixelStride = bytesPerSample,
            vPixelStride = bytesPerSample,
            timestamp = lumaSnapshot.timestamp,
            bitDepth = 8
        )
    }

    private fun copyAnalysisChromaSample(
        source: ByteArray,
        sourceIndex: Int,
        destination: ByteArray,
        destinationIndex: Int,
        sourceBytesPerSample: Int
    ) {
        destination[destinationIndex] = if (sourceBytesPerSample == 1) {
            source.getOrElse(sourceIndex) { 0 }
        } else {
            val lo = source.getOrElse(sourceIndex) { 0 }.toInt() and 0xFF
            val hi = source.getOrElse(sourceIndex + 1) { 0 }.toInt() and 0xFF
            val code10 = (((hi shl 8) or lo) ushr 6).coerceIn(0, 1023)
            ((code10 + 2) ushr 2).coerceIn(0, 255).toByte()
        }
    }

    private fun computeSceneStats(snapshot: LumaPlaneSnapshot): SceneStats {
        return FrameAnalysisMath.computeSceneStats(
            width = snapshot.width,
            height = snapshot.height,
            luma = snapshot.luma,
            maxStatsLongEdge = resolveStatsLongEdge(),
            waveformEnabled = waveformEnabled
        )
    }

    private fun buildFocusPeakingMask(
        snapshot: LumaPlaneSnapshot,
        focusRegion: Triple<Float, Float, Float>? = null
    ): FocusPeakingMask {
        return FrameAnalysisMath.buildFocusPeakingMask(
            width = snapshot.width,
            height = snapshot.height,
            luma = snapshot.luma,
            maxMaskLongEdge = resolveMaskLongEdge(),
            peakingIntensity = peakingIntensity,
            focusRegion = focusRegion
        )
    }

    private fun buildZebraMask(snapshot: LumaPlaneSnapshot): ZebraMask {
        return FrameAnalysisMath.buildZebraMask(
            width = snapshot.width,
            height = snapshot.height,
            luma = snapshot.luma,
            maxMaskLongEdge = resolveMaskLongEdge(),
            zebraThreshold = zebraThreshold
        )
    }

    private fun resolveRuntimeTier(): HighEndRuntimeTier {
        return HighEndQualityPolicy.runtimeTier(
            previewFps = runtimePreviewFpsHint,
            recording = runtimeRecordingHint,
            enabledOverride = HighEndQualityPolicy.enabled
        )
    }

    private fun strictHighEndPipelineEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun strictHighEndDiagnosticsPlan(): StrictHighEndDiagnosticsPlan =
        StrictHighEndDiagnosticsPolicy.resolve(
            previewFps = runtimePreviewFpsHint,
            recording = runtimeRecordingHint
        )

    private fun shouldCaptureLumaSnapshot(
        frameSequence: Long,
        strideMultiplier: Long
    ): Boolean {
        if (strictHighEndPipelineEnabled()) {
            val plan = strictHighEndDiagnosticsPlan()
            // Compute minimum stride inline to avoid List allocation on every frame
            var minStride = resolveSceneStatsStride(strideMultiplier)
            if (peakingEnabled) {
                minStride = minOf(minStride, max(1L, plan.peakingStride * strideMultiplier))
            }
            if (zebraEnabled) {
                minStride = minOf(minStride, max(1L, plan.zebraStride * strideMultiplier))
            }
            if (waveformEnabled) {
                minStride = minOf(minStride, max(1L, plan.sceneStatsStride * strideMultiplier))
            }
            return frameSequence == 1L ||
                frameSequence % minStride == 0L ||
                _sceneAnalysis.value == null ||
                _captureRecommendation.value == null
        }
        if (peakingEnabled || zebraEnabled || waveformEnabled) return true
        return frameSequence == 1L || frameSequence % resolveSceneStatsStride(strideMultiplier) == 0L
    }

    private fun resolveSceneStatsStride(strideMultiplier: Long): Long {
        if (strictHighEndPipelineEnabled()) {
            val plan = strictHighEndDiagnosticsPlan()
            return max(1L, plan.sceneStatsStride * strideMultiplier)
        }
        val baseStride = when (resolveRuntimeTier()) {
            HighEndRuntimeTier.CINEMA -> 2L
            HighEndRuntimeTier.ULTRA -> 2L
            HighEndRuntimeTier.BALANCED -> 2L
            HighEndRuntimeTier.SUSTAINED -> 3L
            HighEndRuntimeTier.RECOVERY -> 4L
            else -> 4L
        }
        return max(1L, baseStride * strideMultiplier)
    }

    private fun resolveStatsLongEdge(): Int {
        if (strictHighEndPipelineEnabled()) {
            return strictHighEndDiagnosticsPlan().statsLongEdge
        }
        return when (resolveRuntimeTier()) {
            HighEndRuntimeTier.CINEMA -> 320
            HighEndRuntimeTier.ULTRA -> 320
            HighEndRuntimeTier.BALANCED -> 288
            HighEndRuntimeTier.SUSTAINED -> 256
            HighEndRuntimeTier.RECOVERY -> 224
            else -> 192
        }
    }

    private fun resolveMaskLongEdge(): Int {
        if (strictHighEndPipelineEnabled()) {
            return strictHighEndDiagnosticsPlan().maskLongEdge
        }
        return when (resolveRuntimeTier()) {
            HighEndRuntimeTier.CINEMA -> 320
            HighEndRuntimeTier.ULTRA -> 320
            HighEndRuntimeTier.BALANCED -> 288
            HighEndRuntimeTier.SUSTAINED -> 256
            HighEndRuntimeTier.RECOVERY -> 224
            else -> 192
        }
    }

    /**
     * ════════════════════════════════════════════════════════════════════════
     * HIGH-PERFORMANCE BYTE ARRAY POOL
     * ════════════════════════════════════════════════════════════════════════
     * 
     * Reduces GC pressure by reusing byte arrays for frame processing.
     * Key optimizations:
     * - Size-bucketed pools for efficient matching
     * - Maximum pool size to prevent memory bloat
     * - Fast path for common sizes
     */
    private class ByteArrayPool {
        companion object {
            /** Maximum buffers per size bucket */
            private const val MAX_POOL_SIZE_PER_BUCKET = 8
            /** Maximum total memory in pool (16MB) */
            private const val MAX_TOTAL_MEMORY_BYTES = 16 * 1024 * 1024
        }
        
        private val buffers = HashMap<Int, java.util.ArrayDeque<ByteArray>>()
        private var totalPooledBytes = 0L
        private var totalPooledBuffers = 0
        private var activeBuffers = 0

        @Synchronized 
        fun acquire(size: Int): ByteArray {
            val queue = buffers[size]
            val buffer = queue?.poll()
            if (buffer != null) {
                totalPooledBytes -= buffer.size
                totalPooledBuffers = (totalPooledBuffers - 1).coerceAtLeast(0)
                activeBuffers++
                return buffer
            }
            activeBuffers++
            return ByteArray(size)
        }
        
        @Synchronized 
        fun release(buffer: ByteArray) {
            activeBuffers = (activeBuffers - 1).coerceAtLeast(0)
            // Don't pool if we're at memory limit
            if (totalPooledBytes + buffer.size > MAX_TOTAL_MEMORY_BYTES) {
                return // Let GC handle it
            }
            
            val queue = buffers.getOrPut(buffer.size) { java.util.ArrayDeque() }
            
            // Don't exceed per-bucket limit
            if (queue.size >= MAX_POOL_SIZE_PER_BUCKET) {
                return // Let GC handle it
            }
            
            queue.add(buffer)
            totalPooledBytes += buffer.size
            totalPooledBuffers++
        }
        
        @Synchronized
        fun clear() {
            buffers.clear()
            totalPooledBytes = 0L
            totalPooledBuffers = 0
            activeBuffers = 0
        }

        @Synchronized
        fun snapshot(): FramePoolSnapshot =
            FramePoolSnapshot(
                activeBuffers = activeBuffers.coerceAtLeast(0),
                pooledBuffers = totalPooledBuffers.coerceAtLeast(0)
            )
    }

    private fun namedThreadFactory(name: String): ThreadFactory = ThreadFactory { runnable ->
        // Two-tier real scheduling (Process nice) applied from INSIDE the thread body: the
        // hardware-import SUBMIT thread feeds the encoder and must sit at URGENT_DISPLAY; the
        // FramePipeline analysis thread (scene-stats/peaking/zebra/AI) sits one notch lower at
        // DISPLAY so overlays never starve the record path. JVM priority alone (below) is largely
        // ignored by CFS — this is the part that actually reprioritises the thread. No pixel change.
        val recordCritical = name.contains("HardwareImport")
        val body = Runnable {
            if (recordCritical) HotPathScheduling.applyUrgentDisplay() else HotPathScheduling.applyDisplay()
            runnable.run()
        }
        Thread(body, name).apply {
            // HIGH-END UPGRADE: Elevated priority for zero-lag 8K analysis
            priority = if (strictHighEndPipelineEnabled()) Thread.MAX_PRIORITY else Thread.NORM_PRIORITY + 2
            isDaemon = true
        }
    }
}

data class SceneStats(val histogram: IntArray, val avgLuma: Float, val waveformColumns: IntArray? = null)
fun interface RawYuvFrameSink {
    fun onRawYuvFrame(frame: RawYuvFrame)
}
data class RawYuvImageSinkTelemetry(
    val sourceImageCallbacks: Long,
    val ownershipTransferred: Long,
    val sinkDeclined: Long,
    val unsupportedImages: Long
)
fun interface RawYuvImageSink {
    /**
     * Return true only when the sink takes ownership of [image] and will close it.
     */
    fun onRawYuvImage(image: Image, timestampNs: Long): Boolean
}
data class RawYuvFrame(
    val width: Int,
    val height: Int,
    val yBytes: ByteArray,
    val uBytes: ByteArray,
    val vBytes: ByteArray,
    val yRowStride: Int,
    val yPixelStride: Int,
    val uRowStride: Int,
    val vRowStride: Int,
    val uPixelStride: Int,
    val vPixelStride: Int,
    val timestamp: Long,
    val bitDepth: Int = 8
)
data class FocusPeakingMask(val maskWidth: Int, val maskHeight: Int, val alphaMask: ByteArray)
data class ZebraMask(val maskWidth: Int, val maskHeight: Int, val alphaMask: ByteArray)
data class SceneAnalysis(
    val averageLuma: Float,
    val estimatedLux: Float,
    val motionScore: Float,
    val edgeDensity: Float,
    val dynamicRangeScore: Float,
    val shadowRatio: Float,
    val highlightRatio: Float,
    val timestamp: Long,
    val normalizedNoiseFloor: Float = 0f,
    val flatRegionStdDev: Float = 0f,
    val realIsoNoiseFloor: Float = 100f,
    val perceptualSharpness: Float = 0f
) {
    val isLowLight: Boolean get() = estimatedLux < 45f || averageLuma < 0.28f
    val isBrightLight: Boolean get() = estimatedLux > 160f || averageLuma > 0.62f
    val hasMotion: Boolean get() = motionScore > 0.10f
    val highDynamicRange: Boolean
        get() = dynamicRangeScore > 0.55f || (highlightRatio > 0.10f && shadowRatio > 0.15f)
}

data class CaptureRecommendation(
    val scene: SceneAnalysis,
    val isoCeiling: Int,
    val targetExposureNs: Long,
    val stackFrameCount: Int,
    val temporalNoiseReduction: Boolean,
    val hdrBracketEvs: List<Int>,
    val sharpeningBoost: Float,
    val hdrTargetFrameCount: Int = 0,
    val localToneMapStrength: Float = 0.36f,
    val shadowDenoiseStrength: Float = 0.24f,
    val spatialDenoiseFloor: Float = 0.24f,
    val detailPreservationStrength: Float = 0.74f,
    val skyProtectionStrength: Float = 0.24f,
    val wbLockSuggested: Boolean = false
) {
    val shouldUseHdrFusion: Boolean
        get() = resolvedHdrFrameCount >= 3
    val shouldUseFrameStacking: Boolean
        get() = stackFrameCount > 1 && !shouldUseHdrFusion
    val resolvedHdrFrameCount: Int
        get() = if (hdrTargetFrameCount > 0) hdrTargetFrameCount else hdrBracketEvs.size
}
