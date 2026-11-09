package com.neoproc.neoproccamx.pipeline

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.neoproc.neoproccamx.ai.Yuv420ImageTools
import com.neoproc.neoproccamx.camera.CameraControllerConfig
import com.neoproc.neoproccamx.camera.FrameGeometry
import com.neoproc.neoproccamx.camera.NdrAdaptiveHighlightExposurePolicy
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.HotPathScheduling
import com.neoproc.neoproccamx.core.PerceptualSignalMath
import com.neoproc.neoproccamx.core.FrameCadenceEvidenceTracker
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.niv99.Niv99DetailProfile
import com.neoproc.neoproccamx.niv99.Niv99DeferredMetadataJoinPolicy
import com.neoproc.neoproccamx.niv99.Niv99ExecutionBoundaryPolicy
import com.neoproc.neoproccamx.niv99.Niv99ExactFrameJoinPolicy
import com.neoproc.neoproccamx.niv99.Niv99LiveFrameTuning
import com.neoproc.neoproccamx.niv99.Niv99MetadataLookupStatus
import com.neoproc.neoproccamx.niv99.Niv99MetadataStatus
import com.neoproc.neoproccamx.niv99.Niv99OperatingMode
import com.neoproc.neoproccamx.niv99.Niv99PreviewTuningContinuity
import com.neoproc.neoproccamx.niv99.Niv99PreviewTuningContinuityPolicy
import com.neoproc.neoproccamx.niv99.Niv99PreviewTuningFallbackStatus
import com.neoproc.neoproccamx.niv99.Niv99PreviewTuningSource
import com.neoproc.neoproccamx.niv99.Niv99SignalDomain
import com.neoproc.neoproccamx.niv99.Niv99YuvFrameView
import com.neoproc.neoproccamx.niv99.android.Niv99LiveCaptureCoordinator
import com.neoproc.neoproccamx.niv99.android.Niv99LiveCaptureSnapshot
import com.neoproc.neoproccamx.recording.Npv10FramePacker
import com.neoproc.neoproccamx.recording.Npv10DirectMappedCommitEngine
import com.neoproc.neoproccamx.recording.Npv10Niv99ColorEncoding
import com.neoproc.neoproccamx.recording.Npv10Niv99FrameProcessingResult
import com.neoproc.neoproccamx.recording.Npv10Niv99FrameProcessor
import com.neoproc.neoproccamx.recording.Npv10Niv99ProcessingFailure
import com.neoproc.neoproccamx.pipeline.HighEndRuntimeTier
import com.neoproc.neoproccamx.rendering.CameraVulkanPreviewView
import com.neoproc.neoproccamx.rendering.VulkanRuntimeManager
import com.neoproc.neoproccamx.core.RuntimeStageTelemetry
import com.neoproc.neoproccamx.tessera.TesseraExecutionBackend
import com.neoproc.neoproccamx.tessera.TesseraPrecisionContract
import com.neoproc.neoproccamx.tessera.TesseraRuntimeProvider
import com.neoproc.neoproccamx.tessera.TesseraSubmission
import com.neoproc.neoproccamx.tessera.TesseraTaskSpec
import com.neoproc.neoproccamx.tessera.TesseraWorkClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max
import kotlin.math.roundToInt

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
 * Immutable routing captured before a hardware-import frame is admitted to the dispatch queue.
 * A later recording start/stop may change the route of future frames, never one already accepted.
 */
internal enum class HardwareImportRoute {
    PREVIEW,
    RECORDING;

    val recordingPath: Boolean
        get() = this == RECORDING

    val domain: FrameDomain
        get() = if (recordingPath) FrameDomain.ENCODER else FrameDomain.PREVIEW

    companion object {
        fun capture(recordingActive: Boolean): HardwareImportRoute =
            if (recordingActive) RECORDING else PREVIEW
    }
}

/**
 * Immutable clip-admission identity captured with a hardware-import Image.
 *
 * Recording preparation deliberately uses the recording pixel route while the encoder gate is
 * still closed.  A Boolean route alone therefore cannot distinguish a pre-roll Image from a frame
 * admitted after the durable clip boundary.  The epoch makes that distinction stable even when a
 * Camera2 generation changes while metadata preparation is in flight.
 */
internal data class HardwareRecordingAdmissionSnapshot(
    val recordingActive: Boolean,
    val lossAccountingEpoch: Long
)

/** Linearizable owner for recording-route and clip-loss admission. */
internal class HardwareRecordingAdmissionFence {
    private val epochSequence = AtomicLong(0L)
    private val state = AtomicReference(
        HardwareRecordingAdmissionSnapshot(
            recordingActive = false,
            lossAccountingEpoch = 0L
        )
    )

    fun beginRuntimeRecording(): HardwareRecordingAdmissionSnapshot {
        while (true) {
            val current = state.get()
            if (current.recordingActive) return current
            val updated = HardwareRecordingAdmissionSnapshot(
                recordingActive = true,
                lossAccountingEpoch = nextEpoch()
            )
            if (state.compareAndSet(current, updated)) return updated
        }
    }

    /** Keeps the recording/prewarm route active while closing clip-loss admission. */
    fun beginClipPreparation(): HardwareRecordingAdmissionSnapshot {
        while (true) {
            val current = state.get()
            if (!current.recordingActive || current.lossAccountingEpoch == 0L) return current
            val updated = current.copy(lossAccountingEpoch = 0L)
            if (state.compareAndSet(current, updated)) return updated
        }
    }

    /** Publishes a new epoch so every pre-roll lease is retired before frame-zero admission. */
    fun commitClipIngress(): HardwareRecordingAdmissionSnapshot? {
        while (true) {
            val current = state.get()
            if (!current.recordingActive) return null
            val updated = current.copy(lossAccountingEpoch = nextEpoch())
            if (state.compareAndSet(current, updated)) return updated
        }
    }

    fun endRuntimeRecording(): HardwareRecordingAdmissionSnapshot {
        while (true) {
            val current = state.get()
            if (!current.recordingActive && current.lossAccountingEpoch == 0L) return current
            val updated = HardwareRecordingAdmissionSnapshot(
                recordingActive = false,
                lossAccountingEpoch = 0L
            )
            if (state.compareAndSet(current, updated)) return updated
        }
    }

    fun capture(): HardwareRecordingAdmissionSnapshot = state.get()

    fun isCurrent(snapshot: HardwareRecordingAdmissionSnapshot): Boolean {
        val current = state.get()
        return snapshot.recordingActive &&
            current.recordingActive &&
            snapshot.lossAccountingEpoch == current.lossAccountingEpoch
    }

    fun isLossEligible(snapshot: HardwareRecordingAdmissionSnapshot): Boolean =
        snapshot.lossAccountingEpoch > 0L && isCurrent(snapshot)

    private fun nextEpoch(): Long = epochSequence.incrementAndGet().also { epoch ->
        check(epoch > 0L) { "Hardware recording admission epoch overflow" }
    }
}

/**
 * Owns one accepted hardware resource. Queue removal and resource release are separate atomic events:
 * the dispatcher may remove/close during clear or conflation while the consumer concurrently polls.
 */
internal class RoutedHardwareFrame<T>(
    val resource: T,
    val route: HardwareImportRoute,
    val recordingAdmission: HardwareRecordingAdmissionSnapshot =
        HardwareRecordingAdmissionSnapshot(
            recordingActive = route.recordingPath,
            lossAccountingEpoch = 0L
        ),
    /** Exact sensor record created once at hardware admission and retained as scalar metadata only. */
    val sensorRecord: SensorFrameRecord? = null,
    private val closeResource: (T) -> Unit,
    private val reportCloseFailure: (Throwable) -> Unit = {}
) {
    private val queued = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)

    /** Returns true exactly once, to the path that removed this frame from the queue. */
    fun leaveQueue(): Boolean = queued.compareAndSet(true, false)

    /** Returns true exactly once, after this instance has claimed and attempted resource release. */
    fun closeOnce(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        try {
            closeResource(resource)
        } catch (throwable: Throwable) {
            reportCloseFailure(throwable)
            if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
        }
        return true
    }
}

/**
 * One preallocated hand-off slot between exact Camera2/NIV99 metadata resolution and native GPU
 * submission. The slot owns its tuning payload for the complete hand-off, so the GPU lane can never
 * observe a ThreadLocal payload being reused by the metadata lane for a newer frame.
 */
internal class PreparedHardwareFrameSlot<T> {
    val tuning = Niv99LiveFrameTuning()

    private val leased = AtomicBoolean(false)
    var frame: RealtimeQueuedFrame<RoutedHardwareFrame<T>>? = null
        private set
    var presentationTimeNs: Long = 0L
        private set
    var metadataLookup: Niv99MetadataLookupStatus = Niv99MetadataLookupStatus.NOT_FOUND
        private set
    var metadataWaitNs: Long = 0L
        private set

    internal fun claim(): Boolean = leased.compareAndSet(false, true)

    internal fun bind(
        queuedFrame: RealtimeQueuedFrame<RoutedHardwareFrame<T>>,
        resolvedPresentationTimeNs: Long,
        lookup: Niv99MetadataLookupStatus,
        waitNs: Long
    ) {
        check(leased.get()) { "Prepared hardware slot must be leased before bind" }
        check(frame == null) { "Prepared hardware slot already contains a frame" }
        frame = queuedFrame
        presentationTimeNs = resolvedPresentationTimeNs
        metadataLookup = lookup
        metadataWaitNs = waitNs.coerceAtLeast(0L)
    }

    internal fun recycle(): Boolean {
        if (!leased.compareAndSet(true, false)) return false
        frame = null
        presentationTimeNs = 0L
        metadataLookup = Niv99MetadataLookupStatus.NOT_FOUND
        metadataWaitNs = 0L
        tuning.clear()
        return true
    }
}

/**
 * Fixed two-slot preparation window. At most one frame can be in native GPU submission while the
 * next frame resolves exact metadata. A third frame remains in the existing bounded dispatcher,
 * preserving ImageReader backpressure instead of growing an allocation queue.
 */
internal class PreparedHardwareFramePool<T>(
    val capacity: Int = 2
) {
    init {
        require(capacity >= 2) { "Two-stage overlap requires at least two slots" }
    }

    private val available = ArrayBlockingQueue<PreparedHardwareFrameSlot<T>>(capacity, true).apply {
        repeat(capacity) { add(PreparedHardwareFrameSlot()) }
    }
    private val leasedCount = AtomicInteger(0)
    private val maximumLeasedCount = AtomicInteger(0)

    fun acquire(timeoutMs: Long): PreparedHardwareFrameSlot<T>? {
        val slot = if (timeoutMs <= 0L) {
            available.poll()
        } else {
            available.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } ?: return null
        check(slot.claim()) { "Prepared hardware slot escaped the pool while leased" }
        val active = leasedCount.incrementAndGet()
        while (true) {
            val maximum = maximumLeasedCount.get()
            if (active <= maximum || maximumLeasedCount.compareAndSet(maximum, active)) break
        }
        return slot
    }

    fun recycle(slot: PreparedHardwareFrameSlot<T>): Boolean {
        if (!slot.recycle()) return false
        val remaining = leasedCount.decrementAndGet()
        check(remaining >= 0) { "Prepared hardware slot count underflow" }
        check(available.offer(slot)) { "Prepared hardware slot pool overflow" }
        return true
    }

    fun inFlightCount(): Int = leasedCount.get().coerceAtLeast(0)
    fun maximumInFlightCount(): Int = maximumLeasedCount.get().coerceAtLeast(0)

    fun resetMaximumInFlightCount() {
        maximumLeasedCount.set(inFlightCount())
    }
}

/**
 * Independent hardware-import queues for the master recording path and the monitoring path.
 *
 * Both queues are bounded. [FramePipelineManager] additionally applies one combined ImageReader
 * in-flight ceiling before acquisition, so isolation cannot increase the number of camera buffers
 * retained. A full preview queue can therefore never reject or replace a recording frame, while a
 * full recording queue still reports real encoder backpressure without temporal conflation.
 */
internal class HardwareFrameDispatchQueues<T>(
    capacityPerDomain: Int,
    acquireGuardNs: Long,
    closeFrame: (RoutedHardwareFrame<T>) -> Unit,
    allowsPreviewStaleFrameConflation: Boolean
) {
    private val recording = RealtimeFrameDispatcher<RoutedHardwareFrame<T>>(
        capacity = capacityPerDomain,
        acquireGuardNs = acquireGuardNs,
        closeFrame = closeFrame,
        allowsStaleFrameConflation = false
    )
    private val preview = RealtimeFrameDispatcher<RoutedHardwareFrame<T>>(
        capacity = capacityPerDomain,
        acquireGuardNs = acquireGuardNs,
        closeFrame = closeFrame,
        allowsStaleFrameConflation = allowsPreviewStaleFrameConflation
    )

    val allowsPreviewStaleFrameConflation: Boolean
        get() = preview.allowsStaleFrameConflation

    fun offer(
        frame: RoutedHardwareFrame<T>,
        sensorTimestampNs: Long,
        nowNs: Long
    ): RealtimeFrameOfferResult = if (frame.route.recordingPath) {
        recording.offer(
            frame = frame,
            sensorTimestampNs = sensorTimestampNs,
            nowNs = nowNs,
            allowStaleFrameConflation = false
        )
    } else {
        preview.offer(
            frame = frame,
            sensorTimestampNs = sensorTimestampNs,
            nowNs = nowNs
        )
    }

    fun pollRecording(nowNs: Long): RealtimeQueuedFrame<RoutedHardwareFrame<T>>? =
        recording.poll(nowNs)

    fun pollPreview(nowNs: Long): RealtimeQueuedFrame<RoutedHardwareFrame<T>>? =
        preview.poll(nowNs)

    fun hasQueuedFrames(): Boolean = recording.hasQueuedFrames() || preview.hasQueuedFrames()

    fun hasQueuedRecordingFrames(): Boolean = recording.hasQueuedFrames()

    fun hasQueuedPreviewFrames(): Boolean = preview.hasQueuedFrames()

    fun queuedFrameCount(): Int = recording.queuedFrameCount() + preview.queuedFrameCount()

    fun telemetry(): RealtimeFrameDispatchTelemetry {
        val recordingTelemetry = recording.telemetry()
        val previewTelemetry = preview.telemetry()
        val recordingLatencySamples = recordingTelemetry.queueLatencySamples.coerceAtLeast(0L)
        val previewLatencySamples = previewTelemetry.queueLatencySamples.coerceAtLeast(0L)
        val totalLatencySamples = recordingLatencySamples + previewLatencySamples
        val averageQueueLatencyNs = if (totalLatencySamples == 0L) {
            0L
        } else {
            (
                (recordingTelemetry.averageQueueLatencyNs.toDouble() * recordingLatencySamples) +
                    (previewTelemetry.averageQueueLatencyNs.toDouble() * previewLatencySamples)
                ).div(totalLatencySamples.toDouble()).toLong().coerceAtLeast(0L)
        }
        return RealtimeFrameDispatchTelemetry(
            acceptedFrames = recordingTelemetry.acceptedFrames + previewTelemetry.acceptedFrames,
            droppedFrames = recordingTelemetry.droppedFrames + previewTelemetry.droppedFrames,
            rejectedFrames = recordingTelemetry.rejectedFrames + previewTelemetry.rejectedFrames,
            averageQueueLatencyNs = averageQueueLatencyNs,
            queuedFrames = recordingTelemetry.queuedFrames + previewTelemetry.queuedFrames,
            skippedPreviewFrames = previewTelemetry.skippedPreviewFrames,
            stalePreviewFrames = previewTelemetry.stalePreviewFrames,
            queueLatencySamples = totalLatencySamples
        )
    }

    fun clear() {
        var firstFailure: Throwable? = null
        try {
            recording.clear()
        } catch (failure: Throwable) {
            firstFailure = failure
        }
        try {
            preview.clear()
        } catch (failure: Throwable) {
            val first = firstFailure
            if (first == null) firstFailure = failure else if (first !== failure) first.addSuppressed(failure)
        }
        firstFailure?.let { throw it }
    }
}

/** One frame's complete, immutable input to the proprietary NDR exposure governor. */
data class NdrExposureMetrics(
    val averageLuma: Float,
    val highlightClipFraction: Float,
    val redHighlightClipFraction: Float,
    val pqHighlightPressureFraction: Float,
    val pqRedHighlightPressureFraction: Float,
    val sourcePqCandidate: Boolean,
    val valid: Boolean,
    val measuredAtElapsedRealtimeMs: Long,
    /** Camera2 session that produced the timestamp-joined analysis frame. */
    val sessionGeneration: Long
) {
    companion object {
        val INVALID = NdrExposureMetrics(
            averageLuma = 0f,
            highlightClipFraction = 0f,
            redHighlightClipFraction = 0f,
            pqHighlightPressureFraction = 0f,
            pqRedHighlightPressureFraction = 0f,
            sourcePqCandidate = false,
            valid = false,
            measuredAtElapsedRealtimeMs = 0L,
            sessionGeneration = FrameIdentity.UNASSIGNED_SESSION_GENERATION
        )
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
        private const val SLOW_FRAME_PROCESS_MS = 28L
        private const val SLOW_AI_INGRESS_MS = 250L
        private const val MAX_RAW_YUV_IMAGE_SINK_DRAIN_IMAGES = 32
        private const val RAW_YUV_IMAGE_SINK_HANDOFF_TIMEOUT_MS = 5_000L
        private const val MAX_HARDWARE_IMPORT_DRAIN_IMAGES =
            CameraControllerConfig.ReaderDepth.STRICT_MAX_HARDWARE_IMPORT_P010
        private const val HARDWARE_IMPORT_DRAIN_CAP_LOG_INTERVAL_MS = 1_000L
        private const val HARDWARE_PREPARATION_SLOT_WAIT_MS = 2L
        private const val HARDWARE_PREPARATION_WINDOW = 2
        private const val HARDWARE_IMPORT_DIAGNOSTIC_INTERVAL_FRAMES = 60L
        private const val MAX_PENDING_RAW_YUV_PIXEL_EVIDENCE = 64
    }

    val latestFrame = AtomicReference<RawYuvFrame?>(null)

    private val _histogram = MutableStateFlow<IntArray?>(null)
    val histogram: StateFlow<IntArray?> = _histogram.asStateFlow()

    private val _avgLuma = MutableStateFlow(0f)
    val avgLuma: StateFlow<Float> = _avgLuma.asStateFlow()

    // Fraction of pixels whose reconstructed RED channel is near clipping (H.Sup15 red-aware highlight
    // protection): luma-only stats are blind to red clipping, so this feeds the capture EV governor.
    private val _redHighlightClipFraction = MutableStateFlow(0f)
    val redHighlightClipFraction: StateFlow<Float> = _redHighlightClipFraction.asStateFlow()

    // The exposure loop consumes this single publication rather than combining the three diagnostic
    // flows above. That guarantees luma, luma clipping, and red clipping always belong to one frame.
    private val _ndrExposureMetrics = MutableStateFlow(NdrExposureMetrics.INVALID)
    val ndrExposureMetrics: StateFlow<NdrExposureMetrics> = _ndrExposureMetrics.asStateFlow()

    private val _sceneAnalysis = MutableStateFlow<SceneAnalysis?>(null)
    val sceneAnalysis: StateFlow<SceneAnalysis?> = _sceneAnalysis.asStateFlow()

    private val _captureRecommendation = MutableStateFlow<CaptureRecommendation?>(null)
    val captureRecommendation: StateFlow<CaptureRecommendation?> = _captureRecommendation.asStateFlow()
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
    // MediaImage -> analysis bitmap conversion is intentionally isolated from scene/exposure work.
    // It is bounded to one Image so reader pressure cannot grow, while the scene executor can accept
    // the next sensor frame as soon as its immutable luma/chroma proxy has been consumed.
    private val aiIngressExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(namedThreadFactory("FramePipeline-AIIngress"))
    private val aiIngressInFlight = AtomicBoolean(false)
    private val aiIngressBusyDropCounter = AtomicLong(0L)
    private val tesseraWarmups = ArrayList<TesseraSubmission<Unit>>(2)
    private val tesseraCameraSessionHeld = AtomicBoolean(false)
    private val hardwareDispatchExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(namedThreadFactory("FramePipeline-HardwareImport"))
    // Exact metadata for frame N+1 is prepared here while frame N is submitted on the independent
    // native lane below. Both lanes remain single-threaded, retaining FIFO ordering in each stage.
    private val hardwareGpuSubmitExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(namedThreadFactory("FramePipeline-HardwareImport-GpuSubmit"))
    private val preparedHardwareFramePool =
        PreparedHardwareFramePool<Image>(HARDWARE_PREPARATION_WINDOW)
    private val preparedHardwareFrames =
        ConcurrentLinkedQueue<PreparedHardwareFrameSlot<Image>>()
    private val hardwareGpuDispatchScheduled = AtomicBoolean(false)
    private val hardwareFrameDispatchQueues = HardwareFrameDispatchQueues<Image>(
        // Each domain is bounded; acquisition below applies the original combined high-watermark.
        // Recording is always FIFO/no-conflation and can never be rejected by preview occupancy.
        capacityPerDomain = RecordPipelineDepthFlag.hardwareImportCapacity(),
        acquireGuardNs = RecordPipelineDepthFlag.hardwareImportAcquireGuardNs(),
        closeFrame = ::closeHardwareFrame,
        allowsPreviewStaleFrameConflation = !HighEndQualityPolicy.isHighEndQualityActive()
    )
    private val hardwareDispatchScheduled = AtomicBoolean(false)
    private val hardwareSubmitInFlight = AtomicBoolean(false)
    private val quiescenceGate = PipelineQuiescenceGate()
    private val synchronousImageIngress =
        SynchronousImageIngressGate(quiescenceGate::signalStateChanged)
    // Normal Camera2 callbacks and the exact stop-tail drain can execute on different threads.
    // Keep FIFO acquisition and recorder ownership transfer atomic across both paths so the final
    // buffered P010 frames cannot be admitted with reversed sensor timestamps.
    private val rawYuvImageDrainGate = OrderedImageReaderAcquisitionGate()
    private val pendingHardwareReader = AtomicReference<ImageReader?>(null)
    private val sensorPresentationClock = SensorPresentationClock()
    private val sensorTimestampTimebase = SensorTimestampTimebaseReconciler()
    private val sensorFramePipeline = SensorCentricFramePipeline(
        targetFps = HighEndQualityPolicy.targetPreviewFps.toDouble().coerceAtLeast(1.0),
        monotonicClockNs = SystemClock::elapsedRealtimeNanos,
        initialQueueCapacity = mapOf(
            FrameDomain.ENCODER to RecordPipelineDepthFlag.hardwareImportCapacity(),
            FrameDomain.PREVIEW to RecordPipelineDepthFlag.hardwareImportCapacity(),
            // Both ingress paths use an AtomicBoolean single-flight gate; report the real bound.
            FrameDomain.AI to 1
        )
    )
    private val niv99LiveCapture = Niv99LiveCaptureCoordinator()
    private val niv99CameraId = AtomicReference("")
    @Volatile
    private var runtimeNiv99ExactMetadataWaitNs =
        Niv99ExactFrameJoinPolicy.timeoutNs(HighEndQualityPolicy.targetPreviewFps.toFloat())
    @Volatile
    private var runtimeNiv99DeferredMetadataWaitNs =
        Niv99DeferredMetadataJoinPolicy.timeoutNs(HighEndQualityPolicy.targetPreviewFps.toFloat())
    // The bounded NPV10 packing lanes each allocate their tuning payload once, then reuse it for
    // every copied-buffer exact-timestamp join; there is no steady-state tuning allocation.
    private val niv99TuningByThread = ThreadLocal.withInitial(::Niv99LiveFrameTuning)
    private val niv99P010RecordingProcessor = object : Npv10Niv99FrameProcessor {
        override fun isAvailable(): Boolean = Npv10FramePacker.isNiv99P010NativeProcessingAvailable()

        override fun processImageAndPackDirectMappedIfTuningReady(
            image: Image,
            sensorTimestampNs: Long,
            encoding: Npv10Niv99ColorEncoding,
            directMappedCommit: Npv10DirectMappedCommitEngine,
            frameIndex: Long,
            output: Npv10Niv99FrameProcessingResult
        ): Boolean {
            val startedNs = SystemClock.elapsedRealtimeNanos()
            output.clear()
            try {
                if (!isAvailable()) {
                    output.failure = Npv10Niv99ProcessingFailure.PROCESSOR_UNAVAILABLE
                    return false
                }
                val tuning = checkNotNull(niv99TuningByThread.get()) {
                    "NIV99 recording tuning ThreadLocal failed to initialize"
                }.clear()
                val cameraId = niv99CameraId.get()
                val sessionGeneration = activeCameraSessionGeneration.get()
                val lookup = niv99LiveCapture.probeTuningForImage(
                    cameraId = cameraId,
                    sessionGeneration = sessionGeneration,
                    sensorTimestampNs = sensorTimestampNs,
                    output = tuning
                )
                if (lookup != Niv99MetadataLookupStatus.MATCHED) {
                    // NOT_FOUND is deliberately not finalized: the caller will copy and close the
                    // Image, then use the bounded deferred join for this exact timestamp.
                    if (lookup != Niv99MetadataLookupStatus.NOT_FOUND) {
                        niv99LiveCapture.recordRecordingImageLookup(
                            cameraId,
                            sessionGeneration,
                            sensorTimestampNs,
                            lookup,
                            tuning
                        )
                    }
                    output.failure = Npv10Niv99ProcessingFailure.METADATA_NOT_FOUND
                    return false
                }
                if (!niv99LiveCapture.recordRecordingImageLookup(
                        cameraId,
                        sessionGeneration,
                        sensorTimestampNs,
                        lookup,
                        tuning
                    )) {
                    output.failure = Npv10Niv99ProcessingFailure.STALE_FRAME_IDENTITY
                    return false
                }
                val identity = tuning.identity
                output.cameraId = identity.cameraId
                output.sessionGeneration = identity.sessionGeneration
                output.frameNumber = identity.frameNumber
                output.sensorTimestampNs = identity.sensorTimestampNs
                output.whiteBalanceEffectMagnitude = tuning.whiteBalanceEffectMagnitude
                output.denoiseEffectMagnitude = tuning.denoiseEffectMagnitude
                output.reconstructionEffectMagnitude = tuning.reconstructionEffectMagnitude
                if (!identity.isComplete() || identity.cameraId != cameraId ||
                    identity.sessionGeneration != sessionGeneration ||
                    identity.sensorTimestampNs != sensorTimestampNs
                ) {
                    output.failure = Npv10Niv99ProcessingFailure.STALE_FRAME_IDENTITY
                    return false
                }
                if (!tuning.valid || !tuning.pixelApplicationApproved) {
                    output.failure = Npv10Niv99ProcessingFailure.PIXEL_APPLICATION_NOT_APPROVED
                    return false
                }
                if (tuning.temporalDenoise != 0f) {
                    output.failure = Npv10Niv99ProcessingFailure.TEMPORAL_ALIGNMENT_UNPROVEN
                    niv99LiveCapture.onPixelRenderRejected(tuning, recordingPath = true)
                    return false
                }
                val prepared = Npv10FramePacker.processNiv99P010ImageToDirectMappedTenBitWithCrc(
                    image = image,
                    directMappedCommit = directMappedCommit,
                    frameIndex = frameIndex,
                    encoding = encoding,
                    redCorrection = tuning.rgbCorrection[0],
                    greenCorrection = tuning.rgbCorrection[1],
                    blueCorrection = tuning.rgbCorrection[2],
                    spatialDenoise = tuning.spatialDenoise,
                    chromaDenoise = tuning.chromaDenoise,
                    sharpeningCeiling = tuning.sharpeningCeiling,
                    confidence = tuning.confidence
                )
                if (prepared == null) {
                    output.failure = Npv10Niv99ProcessingFailure.NATIVE_PROCESSING_FAILED
                    niv99LiveCapture.onPixelRenderRejected(tuning, recordingPath = true)
                    return false
                }
                if (!tuning.markPixelApplicationSucceeded()) {
                    output.failure = Npv10Niv99ProcessingFailure.COMPLETION_REJECTED
                    niv99LiveCapture.onPixelRenderRejected(tuning, recordingPath = true)
                    return false
                }
                if (!niv99LiveCapture.onPixelRenderCompleted(tuning, recordingPath = true)) {
                    output.failure = Npv10Niv99ProcessingFailure.COMPLETION_REJECTED
                    return false
                }
                output.bytesPacked = prepared.encodedBytes
                output.crc32 = prepared.crc32
                return true
            } finally {
                output.processingTimeNs =
                    (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0L)
            }
        }

        override fun processCanonicalAndPack(
            canonicalP010: ByteBuffer,
            width: Int,
            height: Int,
            sensorTimestampNs: Long,
            encoding: Npv10Niv99ColorEncoding,
            target: ByteBuffer,
            output: Npv10Niv99FrameProcessingResult
        ): Boolean = processCanonical(
            canonicalP010 = canonicalP010,
            width = width,
            height = height,
            sensorTimestampNs = sensorTimestampNs,
            encoding = encoding,
            target = target,
            directMappedCommit = null,
            frameIndex = -1L,
            output = output
        )

        override fun processCanonicalAndPackDirectMapped(
            canonicalP010: ByteBuffer,
            width: Int,
            height: Int,
            sensorTimestampNs: Long,
            encoding: Npv10Niv99ColorEncoding,
            directMappedCommit: Npv10DirectMappedCommitEngine,
            frameIndex: Long,
            output: Npv10Niv99FrameProcessingResult
        ): Boolean = processCanonical(
            canonicalP010 = canonicalP010,
            width = width,
            height = height,
            sensorTimestampNs = sensorTimestampNs,
            encoding = encoding,
            target = null,
            directMappedCommit = directMappedCommit,
            frameIndex = frameIndex,
            output = output
        )

        private fun processCanonical(
            canonicalP010: ByteBuffer,
            width: Int,
            height: Int,
            sensorTimestampNs: Long,
            encoding: Npv10Niv99ColorEncoding,
            target: ByteBuffer?,
            directMappedCommit: Npv10DirectMappedCommitEngine?,
            frameIndex: Long,
            output: Npv10Niv99FrameProcessingResult
        ): Boolean {
            val startedNs = SystemClock.elapsedRealtimeNanos()
            output.clear()
            try {
                if (!isAvailable()) {
                    output.failure = Npv10Niv99ProcessingFailure.PROCESSOR_UNAVAILABLE
                    return false
                }
                val tuning = checkNotNull(niv99TuningByThread.get()) {
                    "NIV99 recording tuning ThreadLocal failed to initialize"
                }.clear()
                val cameraId = niv99CameraId.get()
                val sessionGeneration = activeCameraSessionGeneration.get()
                val lookup = niv99LiveCapture.awaitTuningForCopiedFrame(
                    cameraId = cameraId,
                    sessionGeneration = sessionGeneration,
                    sensorTimestampNs = sensorTimestampNs,
                    timeoutNs = runtimeNiv99DeferredMetadataWaitNs,
                    output = tuning
                )
                if (lookup != Niv99MetadataLookupStatus.MATCHED) {
                    output.failure = Npv10Niv99ProcessingFailure.METADATA_NOT_FOUND
                    return false
                }
                val identity = tuning.identity
                output.cameraId = identity.cameraId
                output.sessionGeneration = identity.sessionGeneration
                output.frameNumber = identity.frameNumber
                output.sensorTimestampNs = identity.sensorTimestampNs
                output.whiteBalanceEffectMagnitude = tuning.whiteBalanceEffectMagnitude
                output.denoiseEffectMagnitude = tuning.denoiseEffectMagnitude
                output.reconstructionEffectMagnitude = tuning.reconstructionEffectMagnitude
                if (!identity.isComplete() || identity.cameraId != cameraId ||
                    identity.sessionGeneration != sessionGeneration ||
                    identity.sensorTimestampNs != sensorTimestampNs
                ) {
                    output.failure = Npv10Niv99ProcessingFailure.STALE_FRAME_IDENTITY
                    return false
                }
                if (!tuning.valid || !tuning.pixelApplicationApproved) {
                    output.failure = Npv10Niv99ProcessingFailure.PIXEL_APPLICATION_NOT_APPROVED
                    return false
                }
                if (tuning.temporalDenoise != 0f) {
                    output.failure = Npv10Niv99ProcessingFailure.TEMPORAL_ALIGNMENT_UNPROVEN
                    niv99LiveCapture.onPixelRenderRejected(tuning, recordingPath = true)
                    return false
                }
                val encodedBytes: Int
                val crc: Long
                if (directMappedCommit != null) {
                    check(target == null && frameIndex >= 0L) {
                        "NIV99 DMC processing requires one mapped target and a valid frame index"
                    }
                    val prepared = Npv10FramePacker.processNiv99CanonicalP010ToDirectMappedTenBitWithCrc(
                        canonicalP010 = canonicalP010,
                        width = width,
                        height = height,
                        directMappedCommit = directMappedCommit,
                        frameIndex = frameIndex,
                        encoding = encoding,
                        redCorrection = tuning.rgbCorrection[0],
                        greenCorrection = tuning.rgbCorrection[1],
                        blueCorrection = tuning.rgbCorrection[2],
                        spatialDenoise = tuning.spatialDenoise,
                        chromaDenoise = tuning.chromaDenoise,
                        sharpeningCeiling = tuning.sharpeningCeiling,
                        confidence = tuning.confidence
                    )
                    encodedBytes = prepared?.encodedBytes ?: 0
                    crc = prepared?.crc32?.toLong()?.and(0xFFFF_FFFFL) ?: -1L
                } else {
                    val packedCrc = Npv10FramePacker.processNiv99CanonicalP010ToPackedTenBitBufferWithCrc(
                        canonicalP010 = canonicalP010,
                        width = width,
                        height = height,
                        target = checkNotNull(target),
                        encoding = encoding,
                        redCorrection = tuning.rgbCorrection[0],
                        greenCorrection = tuning.rgbCorrection[1],
                        blueCorrection = tuning.rgbCorrection[2],
                        spatialDenoise = tuning.spatialDenoise,
                        chromaDenoise = tuning.chromaDenoise,
                        sharpeningCeiling = tuning.sharpeningCeiling,
                        confidence = tuning.confidence
                    )
                    encodedBytes = checkNotNull(target).remaining()
                    crc = packedCrc
                }
                if (crc < 0L) {
                    output.failure = Npv10Niv99ProcessingFailure.NATIVE_PROCESSING_FAILED
                    niv99LiveCapture.onPixelRenderRejected(tuning, recordingPath = true)
                    return false
                }
                if (!tuning.markPixelApplicationSucceeded()) {
                    output.failure = Npv10Niv99ProcessingFailure.COMPLETION_REJECTED
                    niv99LiveCapture.onPixelRenderRejected(tuning, recordingPath = true)
                    return false
                }
                if (!niv99LiveCapture.onPixelRenderCompleted(tuning, recordingPath = true)) {
                    output.failure = Npv10Niv99ProcessingFailure.COMPLETION_REJECTED
                    return false
                }
                output.bytesPacked = encodedBytes
                output.crc32 = crc.toInt()
                return true
            } finally {
                output.processingTimeNs =
                    (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0L)
            }
        }
    }
    private val frameSequence = AtomicLong(0L)
    private val busyDropCounter = AtomicLong(0L)
    private val hardwareListenerCallbackCounter = AtomicLong(0L)
    private val hardwareCallbackCounter = AtomicLong(0L)
    private val latestHardwareImportSourceIngress =
        AtomicReference(HardwareImportSourceIngressTelemetry.NONE)
    private val hardwareAcquireFailureCounter = AtomicLong(0L)
    private val hardwareAcquireSkippedCounter = AtomicLong(0L)
    private val hardwareSubmittedCounter = AtomicLong(0L)
    private val hardwareClosedCounter = AtomicLong(0L)
    private val hardwareQueuedRecordingCounter = AtomicLong(0L)
    private val hardwareRecordingAcquisitionCounter = AtomicLong(0L)
    private val hardwareVulkanAttemptCounter = AtomicLong(0L)
    private val hardwareFirstSensorTimestampNs = AtomicLong(0L)
    private val hardwareLastSensorTimestampNs = AtomicLong(0L)
    private val hardwareFirstRawSensorTimestampNs = AtomicLong(0L)
    private val hardwareLastRawSensorTimestampNs = AtomicLong(0L)
    private val hardwareFirstReceivedElapsedRealtimeNs = AtomicLong(0L)
    private val hardwareLastReceivedElapsedRealtimeNs = AtomicLong(0L)
    private val hardwareMaximumQueueDepth = AtomicInteger(0)
    private val hardwarePreparedFrameCounter = AtomicLong(0L)
    // Live capture currently preserves the camera master and defers NIV99 to conversion. Keep that
    // no-metadata path on the established hardware-import worker: transferring each EGL submission
    // to a second thread serializes driver ownership on affected devices and can exceed a 40 ms
    // frame budget at full sensor resolution. The separate GPU lane remains available for the only
    // case that benefits from it: overlapping an enabled exact-metadata join with native submission.
    private val hardwareImmediateSubmitCounter = AtomicLong(0L)
    private val hardwareOverlappedSubmitCounter = AtomicLong(0L)
    private val hardwareMetadataWaitCounter = AtomicLong(0L)
    private val hardwareMetadataWaitTotalNs = AtomicLong(0L)
    private val hardwareMetadataWaitMaximumNs = AtomicLong(0L)
    private val hardwarePreparedQueueMaximumDepth = AtomicInteger(0)
    private val hardwarePreviewSkippedCounter = AtomicLong(0L)
    private val hardwareStalePreviewCounter = AtomicLong(0L)
    private val hardwareSubmissionFailureCounter = AtomicLong(0L)
    // Successful hardware import is a single scalar signal. Keep it independent from the larger
    // adaptive-analysis state so the native GPU worker never allocates/copies that state or waits
    // for the analysis worker's routing lock after submitting a full-resolution frame.
    private val lastHardwareImportElapsedMs = AtomicLong(0L)
    private val hardwareImportDiagnosticsScheduled = AtomicBoolean(false)
    private val lastHardwareImportRejectLogElapsedMs = AtomicLong(0L)
    private val lastHardwareImportDrainCapLogElapsedMs = AtomicLong(0L)
    private val rawYuvImageCallbackCounter = AtomicLong(0L)
    private val rawYuvImageOwnershipTransferCounter = AtomicLong(0L)
    private val rawYuvImageSinkDeclinedCounter = AtomicLong(0L)
    private val rawYuvImageUnsupportedCounter = AtomicLong(0L)
    private val rawYuvImageAcquisitionExceptionCounter = AtomicLong(0L)
    private val rawYuvImageDrainCapHitCounter = AtomicLong(0L)
    private val rawYuvImageFirstAcquisitionException = AtomicReference("")
    private val rawYuvImageCadence = FrameCadenceEvidenceTracker()
    private data class PendingRawYuvPixelEvidence(
        val sensorTimestampNs: Long,
        val registrySessionGeneration: Long,
        val width: Int,
        val height: Int,
        val imageFormat: Int,
        val dataSpace: Int,
        val dataSpaceApiAvailable: Boolean
    )
    private val activeCameraSessionGeneration =
        AtomicLong(FrameIdentity.UNASSIGNED_SESSION_GENERATION)
    private val pendingRawYuvPixelEvidence = ConcurrentHashMap<Long, PendingRawYuvPixelEvidence>()
    private val latestRawYuvPixelAuthority = AtomicReference(RawYuvPixelAuthorityTelemetry.NONE)
    // Observe-only one-shot marker: proves whether the recording sink ever receives a frame on
    // devices that suppress android.util.Log. Reset whenever a new sink is installed.
    private val p010FirstSinkDeliveryLogged = AtomicBoolean(false)

    @Volatile private var previousLumaFrame: ByteArray? = null
    // Mirrors previousLumaFrame for the analysis frame's pooled U/V chroma: the frame published one refresh
    // ago, whose chroma buffers are returned to byteArrayPool once the next frame is published (a one-refresh
    // safety margin so the async SNCE reader that grabbed it has moved on). Kills the per-frame chroma alloc
    // churn on the analysis hot path (luma was pooled; chroma was not).
    @Volatile private var previousAnalysisChromaFrame: RawYuvFrame? = null
    private val _aiMotionCompensationEnabled = MutableStateFlow(false)
    val aiMotionCompensationEnabled: StateFlow<Boolean> = _aiMotionCompensationEnabled.asStateFlow()

    @Volatile private var previewView: CameraVulkanPreviewView? = null
    @Volatile private var aiCameraPipeline: AICameraPipeline? = null
    @Volatile private var frameGeometry: FrameGeometry? = null
    @Volatile private var vulkanRuntime: VulkanRuntimeManager? = null
    // Secondary analysis cadence may be thermally capped independently of the actual sensor rate.
    // Never feed this value into capture/deadline accounting: doing so would make a reduced AI hint
    // conceal real sensor or encoder deadline misses.
    @Volatile private var runtimeAnalysisFpsHint: Float = HighEndQualityPolicy.targetPreviewFps.toFloat()
    @Volatile private var runtimeSensorTargetFps: Float = HighEndQualityPolicy.targetPreviewFps.toFloat()
    @Volatile private var runtimeRecordingHint: Boolean = false
    private val recordingEvidenceWindowActive = AtomicBoolean(false)
    private val hardwareRecordingAdmissionFence = HardwareRecordingAdmissionFence()
    // The native submission worker holds this only across one recording Image submission. A clip
    // boundary takes the same lock once, making gate publication atomic with the epoch transition.
    private val hardwareRecordingAdmissionTransitionLock = ReentrantLock()
    // Thermal isolation affects only optional CPU/AI analysis. Recording and preview pixel delivery
    // remain on their existing full-quality routes and retain their configured cadence.
    @Volatile private var runtimeNonessentialAnalysisSuspended: Boolean = false
    @Volatile private var sourcePqAnalysisEnabled: Boolean = false
    @Volatile private var importRoutingState = FrameImportRoutingState()
    // Guards every read-modify-write of importRoutingState. It is mutated from the dispatch/executor
    // thread (frame-load eval, lastFrameProcessTimeMs) AND the hardware-import callback thread
    // (recordHardwareImport); without a common lock a copy() on one thread clobbered freshly-incremented
    // overload/skip counters on the other (lost update), under-counting overload exactly under load.
    private val routingStateLock = Any()
    // Serializes only ImageReader acquisition/admission across the Camera2 listener and deferred
    // reader worker. Dispatch dequeue has an independent queue-owned lock and must never contend with
    // this timing-critical producer gate at full-frame 25 FPS.
    private val hardwareReaderAcquisitionGate = HardwareImageReaderAcquisitionGate()
    private val sensorPacedFrameSelector = SensorPacedFrameSelector()
    @Volatile private var rawYuvFrameSink: RawYuvFrameSink? = null
    private data class RawYuvImageSinkBinding(
        val sink: RawYuvImageSink,
        val acceptanceEpoch: Long
    )
    private val rawYuvImageAcceptance = ExactTimestampAcceptanceTracker()
    @Volatile private var rawYuvImageSinkBinding: RawYuvImageSinkBinding? = null

    private data class LumaPlaneSnapshot(
        val width: Int,
        val height: Int,
        val luma: ByteArray,
        val timestamp: Long
    )

    private data class PlaneScaleMapKey(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val targetWidth: Int,
        val targetHeight: Int,
        val sourceRowStride: Int,
        val sourcePixelStride: Int
    )

    private data class PlaneScaleMap(
        val sourceRows: IntArray,
        val sourceColumns: IntArray
    )

    private val planeScaleMapLock = Any()
    private val planeScaleMaps = LinkedHashMap<PlaneScaleMapKey, PlaneScaleMap>(8, 0.75f, true)
    @Volatile private var pipelineWarmupChecksum = 0

    init {
        // Publish the exact finite-domain signal tables while the ViewModel is being created, before
        // camera frames arrive. Expensive ST.2084 channel values are demand-computed and retained only
        // at threshold-boundary indices, so this warm-up cannot contend with cold camera initialization.
        // Until publication completes the exact legacy equations remain active; no approximate mode exists.
        tesseraWarmups += TesseraRuntimeProvider.runtime().submit(
            TesseraTaskSpec(
                name = "frame-pipeline-exact-pq-table-warmup",
                workClass = TesseraWorkClass.BACKGROUND,
                preferredBackends = listOf(TesseraExecutionBackend.CONTROL_CPU),
                precision = TesseraPrecisionContract.FP32_STRICT,
                estimatedWorkingSetBytes = 2L * 1024L * 1024L
            )
        ) { _ ->
            val startNs = SystemClock.elapsedRealtimeNanos()
            FrameAnalysisMath.warmExactPqTables()
            NeoLogger.i(
                "FramePipeline",
                "Exact PQ interval warmup ready durationUs=" +
                    (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000L
            )
            Unit
        }
        // These kernels share no mutable state with PQ table construction. Warming them on the second
        // background worker avoids serial startup work crossing into the first ImageReader frame.
        tesseraWarmups += TesseraRuntimeProvider.runtime().submit(
            TesseraTaskSpec(
                name = "frame-pipeline-realtime-kernel-warmup",
                workClass = TesseraWorkClass.BACKGROUND,
                preferredBackends = listOf(TesseraExecutionBackend.CONTROL_CPU),
                precision = TesseraPrecisionContract.FP32_STRICT,
                estimatedWorkingSetBytes = 4L * 1024L * 1024L
            )
        ) { _ ->
            val startNs = SystemClock.elapsedRealtimeNanos()
            prewarmRealtimeAnalysisKernels()
            NeoLogger.i(
                "FramePipeline",
                "Realtime analysis kernel warmup ready durationUs=" +
                    (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000L
            )
            Unit
        }
    }

    fun connectPreviewView(view: CameraVulkanPreviewView) { previewView = view }
    fun connectAICameraPipeline(pipeline: AICameraPipeline?) { aiCameraPipeline = pipeline }
    fun connectVulkanRuntime(runtime: VulkanRuntimeManager?) { vulkanRuntime = runtime }
    fun setFrameGeometry(geometry: FrameGeometry?) { frameGeometry = geometry }
    fun setRawYuvFrameSink(sink: RawYuvFrameSink?) { rawYuvFrameSink = sink }
    fun setRawYuvImageSink(sink: RawYuvImageSink?) {
        publishRawYuvImageSink(sink)
    }

    /**
     * Changes P010 ownership only after every callback admitted under the previous routing snapshot
     * has returned. Frames arriving while sealed remain in ImageReader and the first post-handoff
     * callback drains them FIFO through [enqueue], preserving the master sensor-timestamp lattice.
     */
    fun setRawYuvImageSinkAtExactIngressBoundary(
        timeoutMs: Long = RAW_YUV_IMAGE_SINK_HANDOFF_TIMEOUT_MS,
        sink: RawYuvImageSink
    ) {
        check(synchronousImageIngress.runAtSealedIdleBoundary(timeoutMs) {
            publishRawYuvImageSink(sink)
        }) {
            "Timed out waiting for the exact P010 recording-sink ingress boundary"
        }
        NeoLogger.i("FramePipeline", "P010_SINK_HANDOFF_READY timeoutMs=$timeoutMs")
    }

    private fun publishRawYuvImageSink(sink: RawYuvImageSink?) {
        if (sink != null) {
            rawYuvImageCallbackCounter.set(0L)
            rawYuvImageOwnershipTransferCounter.set(0L)
            rawYuvImageSinkDeclinedCounter.set(0L)
            rawYuvImageUnsupportedCounter.set(0L)
            rawYuvImageAcquisitionExceptionCounter.set(0L)
            rawYuvImageDrainCapHitCounter.set(0L)
            rawYuvImageFirstAcquisitionException.set("")
            rawYuvImageCadence.reset(runtimeSensorTargetFps.roundToInt().coerceAtLeast(1))
            p010FirstSinkDeliveryLogged.set(false)
            runCatching {
                NeoLogger.i("FramePipeline", "P010_SINK_SET running=${running.get()}")
            }
            // Publish one immutable sink+epoch identity. A late callback holding an older binding may
            // still finish, but its timestamp can never satisfy this installation's stop marker.
            rawYuvImageSinkBinding = RawYuvImageSinkBinding(
                sink = sink,
                acceptanceEpoch = rawYuvImageAcceptance.reset()
            )
        } else {
            rawYuvImageSinkBinding = null
        }
    }
    fun rawYuvImageSinkTelemetry(): RawYuvImageSinkTelemetry {
        val cadence = rawYuvImageCadence.snapshot()
        return RawYuvImageSinkTelemetry(
            sourceImageCallbacks = rawYuvImageCallbackCounter.get(),
            ownershipTransferred = rawYuvImageOwnershipTransferCounter.get(),
            sinkDeclined = rawYuvImageSinkDeclinedCounter.get(),
            unsupportedImages = rawYuvImageUnsupportedCounter.get(),
            acquisitionExceptions = rawYuvImageAcquisitionExceptionCounter.get(),
            drainCapHits = rawYuvImageDrainCapHitCounter.get(),
            firstAcquisitionException = rawYuvImageFirstAcquisitionException.get(),
            cadenceTargetFps = cadence.targetFps,
            acquiredImages = cadence.observedFrames,
            invalidTimestamps = cadence.invalidTimestamps,
            nonMonotonicTimestamps = cadence.nonMonotonicTimestamps,
            missingCadenceFrames = cadence.missingCadenceFrames,
            maximumTimestampGapNs = cadence.maximumGapNs,
            firstTimestampNs = cadence.firstTimestampNs,
            lastTimestampNs = cadence.lastTimestampNs,
            firstGapPreviousTimestampNs = cadence.firstGapPreviousTimestampNs,
            firstGapTimestampNs = cadence.firstGapTimestampNs,
            firstGapMissingFrames = cadence.firstGapMissingFrames,
            terminalBoundaryTimestampNs = cadence.terminalBoundaryTimestampNs,
            terminalExcludedMissingFrames = cadence.terminalExcludedMissingFrames,
            timestampsAfterTerminal = cadence.timestampsAfterTerminal
        )
    }
    /**
     * Camera-source evidence published immediately after an ImageReader image is acquired.
     *
     * This counter is deliberately upstream of Vulkan and encoder admission. Recording startup can
     * therefore prove that the replacement Camera2 session is producing pixels while the encoder is
     * quarantined, without depending on the very encoder submissions that the quarantine blocks.
     */
    fun hardwareImportSourceIngressTelemetry(): HardwareImportSourceIngressTelemetry =
        latestHardwareImportSourceIngress.get()
    fun hardwareImportLifecycleTelemetry(): HardwareImportLifecycleTelemetry {
        val queue = realtimeDispatchTelemetry()
        return HardwareImportLifecycleTelemetry(
            submissionTechnology = NeoZeroCopyFrameRailPolicy.TECHNOLOGY_ID,
            listenerCallbacks = hardwareListenerCallbackCounter.get(),
            acquiredFrames = hardwareCallbackCounter.get(),
            recordingRoutedAcquisitions = hardwareRecordingAcquisitionCounter.get(),
            queueAcceptedFrames = queue.acceptedFrames,
            queueDroppedFrames = queue.droppedFrames,
            queueRejectedFrames = queue.rejectedFrames,
            vulkanSubmissionAttempts = hardwareVulkanAttemptCounter.get(),
            vulkanSubmissionSuccesses = hardwareSubmittedCounter.get(),
            vulkanSubmissionFailures = hardwareSubmissionFailureCounter.get(),
            closedFrames = hardwareClosedCounter.get(),
            firstSensorTimestampNs = hardwareFirstSensorTimestampNs.get(),
            lastSensorTimestampNs = hardwareLastSensorTimestampNs.get(),
            firstRawSensorTimestampNs = hardwareFirstRawSensorTimestampNs.get(),
            lastRawSensorTimestampNs = hardwareLastRawSensorTimestampNs.get(),
            firstReceivedElapsedRealtimeNs = hardwareFirstReceivedElapsedRealtimeNs.get(),
            lastReceivedElapsedRealtimeNs = hardwareLastReceivedElapsedRealtimeNs.get(),
            currentQueueDepth = queue.queuedFrames,
            maximumQueueDepth = hardwareMaximumQueueDepth.get(),
            acquireFailures = hardwareAcquireFailureCounter.get(),
            acquireBackpressureSkips = hardwareAcquireSkippedCounter.get(),
            preparedFrames = hardwarePreparedFrameCounter.get(),
            immediateSubmitFrames = hardwareImmediateSubmitCounter.get(),
            overlappedSubmitFrames = hardwareOverlappedSubmitCounter.get(),
            currentPreparationWindow = preparedHardwareFramePool.inFlightCount(),
            maximumPreparationWindow = preparedHardwareFramePool.maximumInFlightCount(),
            maximumPreparedQueueDepth = hardwarePreparedQueueMaximumDepth.get(),
            metadataWaits = hardwareMetadataWaitCounter.get(),
            metadataWaitTotalNs = hardwareMetadataWaitTotalNs.get(),
            metadataWaitMaximumNs = hardwareMetadataWaitMaximumNs.get()
        )
    }
    fun persistHardwareImportLifecycleEvidence(reason: String) {
        NeoLogger.i(
            "FramePipeline",
            "HARDWARE_IMPORT_LEDGER reason=$reason ${hardwareImportLifecycleTelemetry().signature()}"
        )
    }
    fun rawYuvPixelAuthorityTelemetry(): RawYuvPixelAuthorityTelemetry =
        latestRawYuvPixelAuthority.get()
    fun sensorTimestampTimebaseMetadata(): Map<String, String> =
        sensorTimestampTimebase.snapshot().toMetadata("sensorPipelineTimestamp")
    fun setRuntimePerformanceHint(previewFps: Float, recording: Boolean) {
        runtimeAnalysisFpsHint = previewFps.coerceAtLeast(1f)
        if (recording && recordingEvidenceWindowActive.compareAndSet(false, true)) {
            // Exactly one reset per recording, before any accepted recording frame. Camera2 may
            // rebuild more than once during start/stop; those generation changes must not erase the
            // source-to-container evidence for this clip.
            sensorFramePipeline.beginRecordingEvidenceWindow()
            hardwareRecordingAdmissionFence.beginRuntimeRecording()
        } else if (!recording) {
            hardwareRecordingAdmissionFence.endRuntimeRecording()
            sensorFramePipeline.sealRecordingLossAccounting()
            recordingEvidenceWindowActive.set(false)
        }
        runtimeRecordingHint = recording
    }

    /**
     * Closes clip-loss admission while retaining the full-quality recording route for encoder and
     * Camera2 prewarm. Frames already admitted under the prior epoch are permanently outside the
     * next clip even if their asynchronous preparation finishes after the new session is published.
     */
    fun beginHardwareRecordingClipPreparation(): Boolean {
        hardwareRecordingAdmissionTransitionLock.lock()
        return try {
            val active = hardwareRecordingAdmissionFence.beginClipPreparation().recordingActive
            sensorFramePipeline.sealRecordingLossAccounting()
            active
        } finally {
            hardwareRecordingAdmissionTransitionLock.unlock()
        }
    }

    /**
     * Atomically opens loss accounting and the supplied encoder gate at the proven frame-zero
     * boundary. The submission worker cannot carry a pre-roll Image across this transaction.
     */
    fun commitHardwareRecordingClipIngress(enableEncoderIngress: () -> Unit): Boolean {
        hardwareRecordingAdmissionTransitionLock.lock()
        return try {
            if (!runtimeRecordingHint || !recordingEvidenceWindowActive.get()) return false
            sensorFramePipeline.beginRecordingEvidenceWindow()
            val committed = hardwareRecordingAdmissionFence.commitClipIngress() ?: run {
                sensorFramePipeline.sealRecordingLossAccounting()
                return false
            }
            try {
                enableEncoderIngress()
                committed.lossAccountingEpoch > 0L
            } catch (throwable: Throwable) {
                hardwareRecordingAdmissionFence.beginClipPreparation()
                sensorFramePipeline.sealRecordingLossAccounting()
                throw throwable
            }
        } finally {
            hardwareRecordingAdmissionTransitionLock.unlock()
        }
    }

    /**
     * Seals clip-loss admission after all pre-seal hardware frames have drained. Recording evidence
     * remains retained so late MediaCodec/muxer callbacks can still resolve exact sensor identity.
     */
    fun sealRecordingLossAccounting() {
        sensorFramePipeline.sealRecordingLossAccounting()
    }

    fun setNonessentialAnalysisSuspendedForRecording(suspended: Boolean) {
        val changed = runtimeNonessentialAnalysisSuspended != suspended
        runtimeNonessentialAnalysisSuspended = suspended
        if (changed) {
            Log.i(
                "RecordingThermalIsolation",
                "RECORDING_THERMAL_ISOLATION secondaryAnalysisSuspended=$suspended " +
                    "recordingPixelPathUnchanged=true"
            )
        }
    }

    /** Updates capture/deadline accounting from the configured sensor cadence, never an AI cap. */
    fun setSensorTargetFps(sensorFps: Float) {
        require(sensorFps.isFinite() && sensorFps > 0f) { "sensorFps must be finite and positive" }
        runtimeSensorTargetFps = sensorFps
        runtimeNiv99ExactMetadataWaitNs = Niv99ExactFrameJoinPolicy.timeoutNs(sensorFps)
        runtimeNiv99DeferredMetadataWaitNs = Niv99DeferredMetadataJoinPolicy.timeoutNs(sensorFps)
        sensorFramePipeline.updateTargetFps(sensorFps.toDouble())
    }
    fun setSourcePqAnalysisEnabled(enabled: Boolean) { sourcePqAnalysisEnabled = enabled }

    /**
     * Camera2 result ingress. This is intentionally non-blocking: only scalar metadata is snapshotted
     * and indexed by the exact SENSOR_TIMESTAMP; no rendering, inference, disk I/O or executor wait.
     */
    fun onCaptureResult(
        result: TotalCaptureResult,
        captureSessionGeneration: Long
    ): FrameIdentity? {
        if (captureSessionGeneration == activeCameraSessionGeneration.get()) {
            result.get(CaptureResult.SENSOR_TIMESTAMP)?.takeIf { it > 0L }?.let { timestampNs ->
                val monotonicBeforeNs = System.nanoTime()
                val boottimeNowNs = SystemClock.elapsedRealtimeNanos()
                val monotonicAfterNs = System.nanoTime()
                sensorTimestampTimebase.observeReference(
                    timestampNs = timestampNs,
                    monotonicNowNs = midpoint(monotonicBeforeNs, monotonicAfterNs),
                    boottimeNowNs = boottimeNowNs
                )
            }
        }
        val identity = sensorFramePipeline.onCaptureResult(result, captureSessionGeneration)
        identity?.let(::promoteRawYuvPixelAuthority)
        return identity
    }

    /**
     * Publishes a deep, fixed-capacity NIV99 metadata snapshot for exact image-timestamp joining.
     * This observer cannot enable pixel processing; it exists to prove live metadata correctness
     * before a certificate-bound Vulkan graph is allowed to own pixels.
     */
    fun onNiv99CaptureResult(
        result: TotalCaptureResult,
        cameraId: String,
        characteristics: CameraCharacteristics?,
        captureSessionGeneration: Long,
        mode: Niv99OperatingMode,
        signalDomain: Niv99SignalDomain,
        detailProfile: Niv99DetailProfile,
        width: Int,
        height: Int,
        targetFps: Int,
        colorProfileId: Long,
        surfaceGeneration: Long,
        codecGeneration: Long,
        manualKelvin: Int,
        manualTint: Int,
        awbLockRequested: Boolean
    ): Niv99MetadataStatus {
        if (cameraId.isNotBlank() &&
            captureSessionGeneration == activeCameraSessionGeneration.get()
        ) {
            niv99CameraId.set(cameraId)
        }
        return niv99LiveCapture.onCaptureResult(
            result = result,
            cameraId = cameraId,
            characteristics = characteristics,
            sessionGeneration = captureSessionGeneration,
            mode = mode,
            signalDomain = signalDomain,
            detailProfile = detailProfile,
            width = width,
            height = height,
            targetFps = targetFps,
            colorProfileId = colorProfileId,
            surfaceGeneration = surfaceGeneration,
            codecGeneration = codecGeneration,
            manualKelvin = manualKelvin,
            manualTint = manualTint,
            awbLockRequested = awbLockRequested
        )
    }

    fun niv99LiveCaptureSnapshot(): Niv99LiveCaptureSnapshot =
        Niv99LiveCaptureSnapshot().also(niv99LiveCapture::snapshot)

    internal fun niv99P010RecordingFrameProcessor(): Npv10Niv99FrameProcessor =
        niv99P010RecordingProcessor

    /**
     * Exact-timestamp NIV99 join for the persistent visible-preview SurfaceTexture.
     * The caller owns and reuses [output]; this method performs no Image acquisition or allocation.
     */
    fun copyNiv99TuningForPreviewFrame(
        sensorTimestampNs: Long,
        output: Niv99LiveFrameTuning
    ): Niv99MetadataLookupStatus {
        if (!Niv99ExecutionBoundaryPolicy.LIVE_PREVIEW_PIXEL_PROCESSING_ENABLED) {
            output.clear()
            return Niv99MetadataLookupStatus.NOT_CONFIGURED
        }
        return niv99LiveCapture.probeTuningForImage(
            cameraId = niv99CameraId.get(),
            sessionGeneration = activeCameraSessionGeneration.get(),
            sensorTimestampNs = sensorTimestampNs,
            output = output
        )
    }

    /** Preview-only cadence fallback; recording paths retain exact-frame joins. */
    fun copyRecentNiv99TuningForPreviewFrame(
        targetSensorTimestampNs: Long,
        expectedContinuity: Niv99PreviewTuningContinuity,
        output: Niv99LiveFrameTuning
    ): Niv99PreviewTuningFallbackStatus {
        if (!Niv99ExecutionBoundaryPolicy.LIVE_PREVIEW_PIXEL_PROCESSING_ENABLED) {
            output.clear()
            return Niv99PreviewTuningFallbackStatus.NOT_CONFIGURED
        }
        return niv99LiveCapture.probeRecentTuningForPreview(
            cameraId = niv99CameraId.get(),
            sessionGeneration = activeCameraSessionGeneration.get(),
            targetSensorTimestampNs = targetSensorTimestampNs,
            maximumAgeNs = Niv99PreviewTuningContinuityPolicy.maximumReuseAgeNs(
                expectedContinuity.targetFps.toFloat()
            ),
            expectedContinuity = expectedContinuity,
            output = output
        )
    }

    /** Accounts exactly one terminal preview join outcome; retry probes are deliberately excluded. */
    fun onNiv99PreviewTuningLookupFinalized(
        sensorTimestampNs: Long,
        lookup: Niv99MetadataLookupStatus,
        tuning: Niv99LiveFrameTuning
    ): Boolean {
        if (!Niv99ExecutionBoundaryPolicy.LIVE_PREVIEW_PIXEL_PROCESSING_ENABLED) return false
        return niv99LiveCapture.recordPreviewImageLookup(
            cameraId = niv99CameraId.get(),
            sessionGeneration = activeCameraSessionGeneration.get(),
            sensorTimestampNs = sensorTimestampNs,
            lookup = lookup,
            output = tuning
        )
    }

    /** Records success only after the compositor has swapped the exact tuned frame. */
    fun onNiv99PreviewFramePresented(
        tuning: Niv99LiveFrameTuning,
        presentedSensorTimestampNs: Long,
        source: Niv99PreviewTuningSource,
        expectedContinuity: Niv99PreviewTuningContinuity
    ): Boolean {
        if (!Niv99ExecutionBoundaryPolicy.LIVE_PREVIEW_PIXEL_PROCESSING_ENABLED) return false
        return niv99LiveCapture.onPreviewPixelRenderCompleted(
            tuning = tuning,
            presentedSensorTimestampNs = presentedSensorTimestampNs,
            source = source,
            expectedContinuity = expectedContinuity
        )
    }

    fun beginCameraSession(
        sessionGeneration: Long,
        sensorTimestampSource: Int?,
        measuredCameraToImuOffsetNs: Long?,
        calibration: CameraCalibrationHandle?
    ) {
        if (vulkanRuntime?.beginCameraImportSession(sessionGeneration) == false) {
            Log.e(
                "FramePipeline",
                "Rejected stale Camera2 import generation=$sessionGeneration before pipeline reset"
            )
            return
        }
        activeCameraSessionGeneration.set(sessionGeneration)
        lastHardwareImportElapsedMs.set(0L)
        sensorTimestampTimebase.reset(
            referenceHint = if (
                sensorTimestampSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            ) {
                SensorTimestampClockDomain.BOOTTIME
            } else {
                SensorTimestampClockDomain.UNKNOWN
            }
        )
        latestHardwareImportSourceIngress.set(
            HardwareImportSourceIngressTelemetry.sessionBoundary(
                sourceImageCount = hardwareCallbackCounter.get(),
                sessionGeneration = sessionGeneration
            )
        )
        calibration?.cameraId?.takeIf(String::isNotBlank)?.let(niv99CameraId::set)
        niv99LiveCapture.onSessionGenerationAdvanced(sessionGeneration)
        pendingRawYuvPixelEvidence.clear()
        latestRawYuvPixelAuthority.set(RawYuvPixelAuthorityTelemetry.NONE)
        sensorFramePipeline.beginSession(
            sessionGeneration = sessionGeneration,
            alignment = CameraImuClockAlignment.fromCameraCharacteristics(
                sensorTimestampSource = sensorTimestampSource,
                measuredCameraToImuOffsetNs = measuredCameraToImuOffsetNs
            ),
            calibration = calibration,
            // Stop-time preview restoration advances Camera2's generation before MediaCodec writes
            // its final samples. Keep the bounded prior-frame registry and cumulative recording
            // diagnostics alive until the encoder has committed that tail.
            preserveRecordingEvidence = recordingEvidenceWindowActive.get()
        )
    }

    fun setCameraCalibration(handle: CameraCalibrationHandle?) {
        sensorFramePipeline.setCameraCalibration(handle)
    }

    fun onGyroscopeSample(timestampNs: Long, x: Float, y: Float, z: Float) {
        sensorFramePipeline.addGyroscope(TimedVector3Sample(timestampNs, x, y, z))
    }

    fun onAccelerometerSample(timestampNs: Long, x: Float, y: Float, z: Float) {
        sensorFramePipeline.addAccelerometer(TimedVector3Sample(timestampNs, x, y, z))
    }

    fun onOrientationSample(sample: TimedOrientationSample) {
        sensorFramePipeline.addOrientation(sample)
    }

    fun onThermalSample(sample: TimedThermalSample) {
        sensorFramePipeline.addThermal(sample)
    }

    fun updatePipelineResources(sample: PipelineResourceSample): PipelineAdaptationDecision =
        sensorFramePipeline.updateResources(sample)

    fun sensorPipelineDiagnostics(): SensorPipelineDiagnosticsSnapshot =
        sensorFramePipeline.diagnostics()

    fun sensorPipelineAdaptation(): PipelineAdaptationDecision =
        sensorFramePipeline.adaptationDecision()

    /** Must be called by a confirmed recording/muxer completion callback, not by preview submit. */
    fun onRecordedFrame(sensorTimestampNs: Long): Boolean =
        sensorFramePipeline.onRecordedFrame(sensorTimestampNs)

    fun onRecordingStageEvent(
        sensorTimestampNs: Long,
        stage: PipelineStage,
        queueEnteredAtNs: Long,
        startedAtNs: Long,
        endedAtNs: Long,
        queueDepthAtStart: Int,
        currentQueueDepth: Int,
        success: Boolean
    ): Boolean = sensorFramePipeline.onRecordingStageEvent(
        sensorTimestampNs = sensorTimestampNs,
        stage = stage,
        queueEnteredAtNs = queueEnteredAtNs,
        startedAtNs = startedAtNs,
        endedAtNs = endedAtNs,
        queueDepthAtStart = queueDepthAtStart,
        currentQueueDepth = currentQueueDepth,
        success = success
    )

    /** Must be called by an actual compositor/display presentation callback. */
    fun onDisplayedFrame(sensorTimestampNs: Long): Boolean =
        sensorFramePipeline.onDisplayedFrame(sensorTimestampNs)

    /**
     * The caller must hold [sealImageIngressForReaderClose] across this wait and every reader
     * close/restoration operation. The seal makes this bounded observation stable by rejecting new
     * synchronous callbacks before they can acquire an Image.
     */
    fun awaitIdleBeforeReaderClose(timeoutMs: Long): Boolean =
        quiescenceGate.awaitNotBusy(timeoutMs, ::hasInFlightImages)

    /**
     * Atomically blocks future synchronous ImageReader callbacks. Close the returned token only after
     * reader close/restoration is complete; nested owners remain sealed until their final token closes.
     */
    fun sealImageIngressForReaderClose(): FramePipelineImageIngressSeal {
        val seal = synchronousImageIngress.seal()
        // A thermally/backpressure-deferred reader may have no runnable drain. Once admission is
        // sealed it cannot be acquired, so clear that exact pending ownership before the idle proof.
        if (pendingHardwareReader.getAndSet(null) != null) {
            quiescenceGate.signalStateChanged()
        }
        return seal
    }

    /**
     * Bounded poll (elapsed-time loop over the [hardwareSubmitInFlight] AtomicBoolean) that returns
     * true once no native hardware-import submit is in flight, or false when [timeoutMs] elapses.
     * Strictly bounded — never an unbounded native mutex.
     */
    fun awaitHardwareSubmitQuiescent(timeoutMs: Long): Boolean =
        quiescenceGate.awaitNotBusy(timeoutMs) {
            hardwareSubmitInFlight.get() ||
                hardwareGpuDispatchScheduled.get() ||
                preparedHardwareFramePool.inFlightCount() > 0 ||
                preparedHardwareFrames.isNotEmpty()
        }

    private fun hasInFlightImages(): Boolean =
        synchronousImageIngress.hasInFlight() ||
            pendingHardwareReader.get() != null ||
            processing.get() ||
            aiIngressInFlight.get() ||
            hardwareSubmitInFlight.get() ||
            hardwareDispatchScheduled.get() ||
            hardwareGpuDispatchScheduled.get() ||
            preparedHardwareFramePool.inFlightCount() > 0 ||
            preparedHardwareFrames.isNotEmpty() ||
            hardwareFrameDispatchQueues.hasQueuedFrames()

    fun setDedicatedHardwareImportEnabled(enabled: Boolean) {
        synchronized(routingStateLock) {
            importRoutingState = FrameImportRoutingPolicy.setDedicatedHardwareImportEnabled(
                state = importRoutingState,
                enabled = enabled,
                nowElapsedMs = SystemClock.elapsedRealtime()
            )
            lastHardwareImportElapsedMs.set(0L)
        }
    }
    fun setAiMotionCompensationEnabled(enabled: Boolean) {
        _aiMotionCompensationEnabled.value = enabled
    }

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
        val telemetry = realtimeDispatchTelemetry()
        val tuning = PipelineOrchestrator.resolveBackpressureTuning(
            droppedFrames = telemetry.droppedFrames,
            rejectedFrames = telemetry.rejectedFrames,
            averageQueueLatencyNs = telemetry.averageQueueLatencyNs,
            strictNoQualityDowngrade = strictNoQualityDowngradeActive()
        )
        return "realtimeDispatch accepted=${telemetry.acceptedFrames} " +
            "dropped=${telemetry.droppedFrames} rejected=${telemetry.rejectedFrames} " +
            "previewSkipped=${telemetry.skippedPreviewFrames} stalePreview=${telemetry.stalePreviewFrames} " +
            "avgQueueNs=${telemetry.averageQueueLatencyNs} ${sensorPresentationClock.telemetrySignature()} " +
            "strictNoConflation=${!hardwareFrameDispatchQueues.allowsPreviewStaleFrameConflation} " +
            "tuning=${tuning.reason}"
    }

    fun realtimeDispatchTelemetry(): RealtimeFrameDispatchTelemetry =
        hardwareFrameDispatchQueues.telemetry().let { telemetry ->
            telemetry.copy(
                skippedPreviewFrames = telemetry.skippedPreviewFrames +
                    hardwarePreviewSkippedCounter.get(),
                stalePreviewFrames = telemetry.stalePreviewFrames +
                    hardwareStalePreviewCounter.get()
            )
        }

    fun realtimeDispatchStrictNoConflation(): Boolean =
        !hardwareFrameDispatchQueues.allowsPreviewStaleFrameConflation
    fun start() { 
        running.set(true)
        if (tesseraCameraSessionHeld.compareAndSet(false, true)) {
            TesseraRuntimeProvider.runtime().enterCameraSession()
        }
        frameSkipWindow.reset()
        busyDropCounter.set(0L)
        hardwareListenerCallbackCounter.set(0L)
        hardwareCallbackCounter.set(0L)
        latestHardwareImportSourceIngress.set(HardwareImportSourceIngressTelemetry.NONE)
        hardwareAcquireFailureCounter.set(0L)
        hardwareAcquireSkippedCounter.set(0L)
        hardwareSubmittedCounter.set(0L)
        hardwareClosedCounter.set(0L)
        hardwareQueuedRecordingCounter.set(0L)
        hardwareRecordingAcquisitionCounter.set(0L)
        hardwareVulkanAttemptCounter.set(0L)
        hardwareFirstSensorTimestampNs.set(0L)
        hardwareLastSensorTimestampNs.set(0L)
        hardwareFirstRawSensorTimestampNs.set(0L)
        hardwareLastRawSensorTimestampNs.set(0L)
        hardwareFirstReceivedElapsedRealtimeNs.set(0L)
        hardwareLastReceivedElapsedRealtimeNs.set(0L)
        hardwareMaximumQueueDepth.set(0)
        hardwarePreparedFrameCounter.set(0L)
        hardwareImmediateSubmitCounter.set(0L)
        hardwareOverlappedSubmitCounter.set(0L)
        hardwareMetadataWaitCounter.set(0L)
        hardwareMetadataWaitTotalNs.set(0L)
        hardwareMetadataWaitMaximumNs.set(0L)
        hardwarePreparedQueueMaximumDepth.set(0)
        preparedHardwareFramePool.resetMaximumInFlightCount()
        hardwareSubmissionFailureCounter.set(0L)
        lastHardwareImportElapsedMs.set(0L)
        hardwarePreviewSkippedCounter.set(0L)
        hardwareStalePreviewCounter.set(0L)
        rawYuvImageCallbackCounter.set(0L)
        rawYuvImageOwnershipTransferCounter.set(0L)
        rawYuvImageSinkDeclinedCounter.set(0L)
        rawYuvImageUnsupportedCounter.set(0L)
        rawYuvImageAcquisitionExceptionCounter.set(0L)
        rawYuvImageDrainCapHitCounter.set(0L)
        rawYuvImageFirstAcquisitionException.set("")
        rawYuvImageCadence.reset(runtimeSensorTargetFps.roundToInt().coerceAtLeast(1))
        pendingRawYuvPixelEvidence.clear()
        latestRawYuvPixelAuthority.set(RawYuvPixelAuthorityTelemetry.NONE)
    }
    fun stop() {
        running.set(false)
        hardwareRecordingAdmissionFence.endRuntimeRecording()
        sensorFramePipeline.sealRecordingLossAccounting()
        recordingEvidenceWindowActive.set(false)
        pendingHardwareReader.set(null)
        if (tesseraCameraSessionHeld.compareAndSet(true, false)) {
            TesseraRuntimeProvider.runtime().exitCameraSession()
        }
        previousLumaFrame?.let(byteArrayPool::release)
        previousLumaFrame = null
        previousAnalysisChromaFrame?.let { releaseChroma(it) }
        previousAnalysisChromaFrame = null
        byteArrayPool.clear()
        yuvFramePool.clear()
        // The capture callback never waits for this lock: it uses tryLock and defers its reader.
        // Stop may wait for the already-running, bounded acquire/offer producer section, ensuring
        // clear cannot miss an accepted frame inserted immediately after its slot scan.
        hardwareReaderAcquisitionGate.withLifecycleBarrier {
            pendingHardwareReader.set(null)
            hardwareFrameDispatchQueues.clear()
            discardQueuedPreparedHardwareFrames("pipeline-stopped-with-prepared-frame")
            hardwareQueuedRecordingCounter.set(0L)
            hardwarePreviewSkippedCounter.set(0L)
            hardwareStalePreviewCounter.set(0L)
        }
        hardwareDispatchScheduled.set(false)
        quiescenceGate.signalStateChanged()
        frameSkipWindow.reset()
        synchronized(routingStateLock) { importRoutingState = FrameImportRoutingState() }
        lastHardwareImportElapsedMs.set(0L)
        sensorPacedFrameSelector.reset()
        rawYuvFrameSink = null
        rawYuvImageSinkBinding = null
        activeCameraSessionGeneration.set(FrameIdentity.UNASSIGNED_SESSION_GENERATION)
        latestHardwareImportSourceIngress.set(
            HardwareImportSourceIngressTelemetry.sessionBoundary(
                sourceImageCount = hardwareCallbackCounter.get(),
                sessionGeneration = FrameIdentity.UNASSIGNED_SESSION_GENERATION
            )
        )
        niv99CameraId.set("")
        niv99LiveCapture.close()
        pendingRawYuvPixelEvidence.clear()
        latestRawYuvPixelAuthority.set(RawYuvPixelAuthorityTelemetry.NONE)
        sensorFramePipeline.endSession()
    }
    fun destroy() {
        stop()
        niv99LiveCapture.destroy()
        sensorFramePipeline.close()
        tesseraWarmups.forEach { it.cancel() }
        tesseraWarmups.clear()
        processingExecutor.shutdownNow()
        aiIngressExecutor.shutdownNow()
        hardwareDispatchExecutor.shutdownNow()
        hardwareGpuSubmitExecutor.shutdownNow()
    }

    fun enqueue(reader: ImageReader) {
        // This must remain the first action: a sealed callback returns before reading routing state or
        // calling any ImageReader acquisition method.
        val imageIngressLease = synchronousImageIngress.tryEnter() ?: return
        try {
            rawYuvImageDrainGate.runExclusive {
                val rawYuvImageSinkSnapshot = rawYuvImageSinkBinding
                if (rawYuvImageSinkSnapshot != null) {
                    val drainedImages = FramePipelineImageDrainPolicy.drainAvailable(
                        maxItems = MAX_RAW_YUV_IMAGE_SINK_DRAIN_IMAGES,
                        acquire = {
                            try {
                                reader.acquireNextImage()
                            } catch (failure: Exception) {
                                recordRawYuvImageAcquisitionException("callback-drain", failure)
                                null
                            }
                        },
                        consume = { image ->
                            processAcquiredImage(image, rawYuvImageSinkSnapshot)
                        }
                    )
                    if (drainedImages == MAX_RAW_YUV_IMAGE_SINK_DRAIN_IMAGES) {
                        rawYuvImageDrainCapHitCounter.incrementAndGet()
                        Log.d(
                            "FramePipeline",
                            "Raw YUV image sink drain reached per-callback cap=$drainedImages"
                        )
                    }
                } else {
                    val image = try {
                        reader.acquireLatestImage()
                    } catch (_: Exception) {
                        null
                    }
                    if (image != null) processAcquiredImage(image, null)
                }
            }
        } finally {
            // Includes ownership-transfer returns, declined sinks, unsupported images, and exceptions.
            // This only releases callback accounting; Image ownership remains with the existing path.
            imageIngressLease.close()
        }
    }

    /**
     * Synchronously acquires every currently buffered P010 Image through the still-attached sink.
     * A negative result is fail-closed: ingress was sealed, the sink changed, or ImageReader
     * acquisition failed. Zero is a genuine empty observation.
     */
    fun drainRawYuvImageTail(reader: ImageReader): Int {
        val imageIngressLease = synchronousImageIngress.tryEnter() ?: return -1
        try {
            return rawYuvImageDrainGate.runExclusive {
                val sinkBinding = rawYuvImageSinkBinding ?: return@runExclusive -1
                var acquisitionFailed = false
                val drained = FramePipelineImageDrainPolicy.drainAvailable(
                    maxItems = MAX_RAW_YUV_IMAGE_SINK_DRAIN_IMAGES,
                    acquire = {
                        try {
                            reader.acquireNextImage()
                        } catch (failure: Exception) {
                            acquisitionFailed = true
                            recordRawYuvImageAcquisitionException("stop-tail", failure)
                            null
                        }
                    },
                    consume = { image -> processAcquiredImage(image, sinkBinding) }
                )
                if (drained == MAX_RAW_YUV_IMAGE_SINK_DRAIN_IMAGES) {
                    rawYuvImageDrainCapHitCounter.incrementAndGet()
                }
                if (acquisitionFailed) -1 else drained
            }
        } finally {
            imageIngressLease.close()
        }
    }

    /** Waits for exact ownership transfer; later timestamps never substitute for the marker. */
    fun awaitRawYuvImageSinkAcceptance(timestampNs: Long, timeoutMs: Long): Boolean {
        val sinkBinding = rawYuvImageSinkBinding ?: return false
        return rawYuvImageAcceptance.await(
            epoch = sinkBinding.acceptanceEpoch,
            timestampNs = timestampNs,
            timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        )
    }

    /**
     * Proves exact marker ownership after one FIFO acquireNext drain through the still-attached sink.
     * Camera2 sequence termination establishes the producer boundary; no quiet-period guess is needed.
     */
    fun proveRawYuvImageStopBoundary(
        reader: ImageReader,
        markerTimestampNs: Long,
        timeoutMs: Long
    ): Boolean {
        if (markerTimestampNs <= 0L || timeoutMs <= 0L) return false
        val sinkBinding = rawYuvImageSinkBinding ?: return false
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        if (drainRawYuvImageTail(reader) < 0) return false
        if (!rawYuvImageAcceptance.contains(sinkBinding.acceptanceEpoch, markerTimestampNs)) {
            val remainingNs = deadlineNs - System.nanoTime()
            if (!rawYuvImageAcceptance.await(
                    epoch = sinkBinding.acceptanceEpoch,
                    timestampNs = markerTimestampNs,
                    timeoutNanos = remainingNs
                )
            ) {
                return false
            }
        }
        val boundaryOwned = rawYuvImageSinkBinding === sinkBinding &&
            rawYuvImageAcceptance.contains(sinkBinding.acceptanceEpoch, markerTimestampNs)
        if (boundaryOwned) {
            rawYuvImageCadence.closeTerminalInterval(markerTimestampNs)
        }
        return boundaryOwned
    }

    private fun processAcquiredImage(
        image: Image,
        rawYuvImageSinkSnapshot: RawYuvImageSinkBinding?
    ) {
        if (!running.get()) { image.close(); return }
        val rawTimestampNs = try { image.timestamp } catch (_: Exception) { 0L }
        val timestampNs = normalizeImageSensorTimestamp(rawTimestampNs)
        val sensorRecord = sensorFramePipeline.onImageAvailable(
            sensorTimestampNs = timestampNs,
            arrivedAtNs = SystemClock.elapsedRealtimeNanos()
        )
        recordRawYuvPixelEvidence(image, timestampNs, sensorRecord)
        val distributionToken = sensorFramePipeline.beginStage(
            record = sensorRecord,
            stage = PipelineStage.FRAME_DISTRIBUTION,
            queueDepth = 0
        )
        sensorFramePipeline.finishStage(distributionToken, success = true)
        val timestampMs = TimeUnit.NANOSECONDS.toMillis(timestampNs)
        val currentFrameSeq = frameSequence.incrementAndGet()
        val startProcessTimeMs = SystemClock.elapsedRealtime()
        // Meter before writer-sink attachment as well as after it. NPV10 intentionally proves and
        // stabilizes the exact Camera2 producer before opening the durable timeline; hiding this
        // meter behind a non-null sink delayed the cadence exposure decision until frame one and
        // caused live repeating-request churn during recording.
        maybePublishNpv10CadenceExposureMetrics(
            image = image,
            frameSequence = currentFrameSeq,
            sessionGeneration = sensorRecord.registrySessionGeneration
        )
        if (rawYuvImageSinkSnapshot != null) {
            rawYuvImageCadence.record(timestampNs)
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
                if (runtimeRecordingHint) {
                    sensorFramePipeline.markDomainQueued(
                        sensorRecord,
                        FrameDomain.ENCODER,
                        "raw-yuv-image-sink"
                    )
                }
                val ownershipTransferred = runCatching {
                    rawYuvImageSinkSnapshot.sink.onRawYuvImage(image, timestampNs)
                }.onFailure { throwable ->
                    sensorFramePipeline.markDomainFailed(sensorRecord, FrameDomain.ENCODER, throwable)
                    Log.w("FramePipeline", "Raw YUV image sink failed", throwable)
                }.getOrDefault(false)
                if (ownershipTransferred) {
                    rawYuvImageOwnershipTransferCounter.incrementAndGet()
                    rawYuvImageAcceptance.record(
                        epoch = rawYuvImageSinkSnapshot.acceptanceEpoch,
                        timestampNs = timestampNs
                    )
                    return
                }
                rawYuvImageSinkDeclinedCounter.incrementAndGet()
            } else {
                rawYuvImageUnsupportedCounter.incrementAndGet()
            }
        }
        val rawYuvSinkSnapshot = rawYuvFrameSink
        if (rawYuvSinkSnapshot != null) {
            sensorFramePipeline.markDomainQueued(
                sensorRecord,
                FrameDomain.RAW_PROCESSING,
                "immutable-yuv-snapshot"
            )
            snapshotRawYuvFrame(image, timestampNs)?.let { frame ->
                runCatching {
                    sensorFramePipeline.markDomainRunning(sensorRecord, FrameDomain.RAW_PROCESSING)
                    rawYuvSinkSnapshot.onRawYuvFrame(frame)
                }.onSuccess {
                    sensorFramePipeline.markDomainCompleted(sensorRecord, FrameDomain.RAW_PROCESSING)
                }
                    .onFailure { throwable ->
                        sensorFramePipeline.markDomainFailed(
                            sensorRecord,
                            FrameDomain.RAW_PROCESSING,
                            throwable
                        )
                        Log.w("FramePipeline", "Raw YUV frame sink failed", throwable)
                    }
            }
        }

        if (runtimeRecordingHint && runtimeNonessentialAnalysisSuspended) {
            // Keep monitor delivery alive, but do not allocate luma/chroma snapshots or dispatch
            // scene/AI workers while the full-quality recording path is thermally constrained.
            if (FrameImportRoutingPolicy.shouldImportAnalysisFrame(
                    state = importRoutingState,
                    nowElapsedMs = startProcessTimeMs,
                    observedHardwareImportElapsedMs = lastHardwareImportElapsedMs.get()
                )
            ) {
                submitTrackedImageToVulkan(image, sensorRecord, recordingPath = false)
            }
            sensorFramePipeline.markDomainSkipped(
                sensorRecord,
                FrameDomain.IMAGE_PROCESSING,
                "recording-thermal-isolation"
            )
            image.close()
            return
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
                    nowElapsedMs = startProcessTimeMs,
                    observedHardwareImportElapsedMs = lastHardwareImportElapsedMs.get()
                )
            ) {
                submitTrackedImageToVulkan(image, sensorRecord, recordingPath = false)
            }
            sensorFramePipeline.markDomainSkipped(
                sensorRecord,
                FrameDomain.IMAGE_PROCESSING,
                "adaptive-analysis-cadence"
            )
            image.close()
            return
        }

        if (!processing.compareAndSet(false, true)) {
            frameSkipWindow.shouldProcessFrame(SystemClock.elapsedRealtime())
            val busyDrops = busyDropCounter.incrementAndGet()
            if (busyDrops % 30L == 0L) {
                Log.d("FramePipeline", "Busy drops=$busyDrops (processing thread saturated)")
            }
            sensorFramePipeline.markDomainSkipped(
                sensorRecord,
                FrameDomain.IMAGE_PROCESSING,
                "scene-worker-busy"
            )
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
        // P010 describes precision, not transfer. SDR/PRO P010 must not run ST.2084 metering;
        // CameraViewModel enables it only for an actual PQ Camera2 dynamic-range transport.
        val sourcePqCandidate = sourcePqAnalysisEnabled && Yuv420ImageTools.isP010(image)
        val aiPipelineSnapshot = aiCameraPipeline
        val shouldImportAnalysisFrame = FrameImportRoutingPolicy.shouldImportAnalysisFrame(
            state = importRoutingState,
            nowElapsedMs = startProcessTimeMs,
            observedHardwareImportElapsedMs = lastHardwareImportElapsedMs.get()
        )
        val routingStrideMultiplier =
            FrameImportRoutingPolicy.getAdaptiveStrideMultiplier(importRoutingState).toLong()
        val analyticsStrideMultiplier = SecondaryWorkloadConsumerPolicy.analyticsStrideMultiplier(
            existingMultiplier = routingStrideMultiplier,
            decision = sensorFramePipeline.adaptationDecision()
        )
        val realtimeAnalysisPlan = FrameRealtimeAnalysisPolicy.resolve(
            previewFps = runtimeAnalysisFpsHint,
            recording = runtimeRecordingHint,
            adaptiveStrideMultiplier = routingStrideMultiplier
        )
        val shouldRunAiFrame = aiPipelineSnapshot != null &&
            sensorFramePipeline.shouldAdmitSecondary(FrameDomain.AI, sensorRecord) && (
            if (SensorPacedFrameSelectorFlag.enabled) {
                sensorPacedFrameSelector.shouldAnalyze(
                    sensorTimestampNs = timestampNs,
                    previewFps = runtimeAnalysisFpsHint,
                    aiFrameStride = realtimeAnalysisPlan.aiFrameStride
                )
            } else {
                realtimeAnalysisPlan.shouldAnalyze(currentFrameSeq)
            }
        )
        if (shouldImportAnalysisFrame) {
            submitTrackedImageToVulkan(image, sensorRecord, recordingPath = false)
        }
        val captureLumaSnapshot = shouldCaptureLumaSnapshot(
            frameSequence = currentFrameSeq,
            strideMultiplier = analyticsStrideMultiplier
        )
        val deferLumaSnapshot = DeferredLumaSnapshotFlag.enabled
        val lumaSnapshot =
            if (captureLumaSnapshot && !deferLumaSnapshot) {
                snapshotLumaPlane(image, timestampNs)
            } else {
                null
            }

        val imageClosed = AtomicBoolean(false)
        val closeImageOnce = {
            if (imageClosed.compareAndSet(false, true)) {
                runCatching { image.close() }
            }
            Unit
        }

        sensorFramePipeline.markDomainQueued(
            sensorRecord,
            FrameDomain.IMAGE_PROCESSING,
            "scene-analysis"
        )
        processingExecutor.execute {
            val processStart = SystemClock.elapsedRealtime()
            val processStageToken = sensorFramePipeline.beginStage(
                record = sensorRecord,
                stage = PipelineStage.IMAGE_PROCESSING,
                queueEnteredAtNs = sensorRecord.arrivedAtMonotonicNs,
                queueDepth = if (processing.get()) 1 else 0
            )
            sensorFramePipeline.markDomainRunning(sensorRecord, FrameDomain.IMAGE_PROCESSING)
            var processSucceeded = false
            var aiIngressScheduled = false
            var lumaSnapshotNs = 0L
            var sceneStatsNs = 0L
            var chromaSnapshotNs = 0L
            var redMetricNs = 0L
            var pqMetricNs = 0L
            var decisionNs = 0L
            var imageBackedIngressFinished = false

            fun finishImageBackedIngress() {
                if (imageBackedIngressFinished) return
                imageBackedIngressFinished = true
                if (aiPipelineSnapshot != null && shouldRunAiFrame &&
                    aiIngressInFlight.compareAndSet(false, true)
                ) {
                    sensorFramePipeline.markDomainQueued(
                        sensorRecord,
                        FrameDomain.AI,
                        "bounded-ai-ingress"
                    )
                    try {
                        aiIngressExecutor.execute {
                            val aiStart = SystemClock.elapsedRealtime()
                            val aiToken = sensorFramePipeline.beginStage(
                                record = sensorRecord,
                                stage = PipelineStage.AI,
                                queueEnteredAtNs = sensorRecord.arrivedAtMonotonicNs,
                                queueDepth = 1
                            )
                            var aiSucceeded = false
                            try {
                                if (running.get()) {
                                    sensorFramePipeline.markDomainRunning(sensorRecord, FrameDomain.AI)
                                    aiPipelineSnapshot.analyzeMediaImage(
                                        image = image,
                                        timestampMs = timestampMs,
                                        rotationDegrees = geometrySnapshot?.aiRotationDegrees ?: 0,
                                        frameGeometry = geometrySnapshot,
                                        previewMaxDimension = realtimeAnalysisPlan.aiProxyLongEdge,
                                        releaseImage = closeImageOnce
                                    )
                                    aiSucceeded = true
                                    sensorFramePipeline.markDomainCompleted(sensorRecord, FrameDomain.AI)
                                } else {
                                    sensorFramePipeline.markDomainSkipped(
                                        sensorRecord,
                                        FrameDomain.AI,
                                        "session-stopped"
                                    )
                                }
                            } catch (throwable: Throwable) {
                                sensorFramePipeline.markDomainFailed(
                                    sensorRecord,
                                    FrameDomain.AI,
                                    throwable
                                )
                                Log.w("FramePipeline", "AI ingress failed independently", throwable)
                                if (throwable is VirtualMachineError || throwable is ThreadDeath) {
                                    throw throwable
                                }
                            } finally {
                                sensorFramePipeline.finishStage(aiToken, aiSucceeded)
                                closeImageOnce()
                                aiIngressInFlight.set(false)
                                quiescenceGate.signalStateChanged()
                                RuntimeStageTelemetry.recordDuration(
                                    tag = "AiIngress",
                                    stage = "mediaImageToAnalysis",
                                    durationMs = SystemClock.elapsedRealtime() - aiStart,
                                    slowThresholdMs = SLOW_AI_INGRESS_MS
                                )
                            }
                        }
                        aiIngressScheduled = true
                    } catch (t: Throwable) {
                        aiIngressInFlight.set(false)
                        quiescenceGate.signalStateChanged()
                        closeImageOnce()
                        sensorFramePipeline.markDomainFailed(sensorRecord, FrameDomain.AI, t)
                        if (t !is RejectedExecutionException) {
                            Log.w("FramePipeline", "AI ingress dispatch failed", t)
                        }
                    }
                } else {
                    if (aiPipelineSnapshot != null && shouldRunAiFrame) {
                        sensorFramePipeline.markDomainSkipped(
                            sensorRecord,
                            FrameDomain.AI,
                            "ai-worker-busy"
                        )
                        val busyDrops = aiIngressBusyDropCounter.incrementAndGet()
                        if (busyDrops == 1L || busyDrops % 30L == 0L) {
                            Log.d(
                                "FramePipeline",
                                "AI ingress busy; retaining latest sensor/scene cadence busyDrops=$busyDrops"
                            )
                        }
                    }
                    // Every later scene/PQ/decision stage reads only the immutable heap snapshot.
                    closeImageOnce()
                }
            }

            try {
                val lumaStartNs = SystemClock.elapsedRealtimeNanos()
                val resolvedLumaSnapshot =
                    if (captureLumaSnapshot && deferLumaSnapshot) {
                        snapshotLumaPlane(image, timestampNs)
                    } else {
                        lumaSnapshot
                    }
                lumaSnapshotNs = SystemClock.elapsedRealtimeNanos() - lumaStartNs
                resolvedLumaSnapshot?.let { snapshot ->
                    val sceneStatsStride = resolveSceneStatsStride(analyticsStrideMultiplier)
                    val shouldRefreshSceneStats =
                        currentFrameSeq == 1L ||
                            currentFrameSeq % sceneStatsStride == 0L ||
                            _sceneAnalysis.value == null ||
                            _captureRecommendation.value == null
                    var retainedAsPreviousFrame = false

                    if (shouldRefreshSceneStats) {
                        val chromaStartNs = SystemClock.elapsedRealtimeNanos()
                        val analysisRawFrame = snapshotAnalysisRawFrame(image, snapshot, timestampNs)
                        chromaSnapshotNs = SystemClock.elapsedRealtimeNanos() - chromaStartNs
                        // This is the final scene-pipeline read of the Image. Hand ownership to the
                        // bounded AI ingress (or close immediately) before any CPU metric work.
                        finishImageBackedIngress()
                        val statsStartNs = SystemClock.elapsedRealtimeNanos()
                        val stats = computeSceneStats(snapshot)
                        sceneStatsNs = SystemClock.elapsedRealtimeNanos() - statsStartNs
                        latestFrame.set(analysisRawFrame)
                        // The coordinator performs an exact Camera2 timestamp join. Keeping this call
                        // beside publication of the existing bounded analysis frame guarantees that
                        // color evidence covers the whole image rather than a focus/metering crop.
                        if (Niv99ExecutionBoundaryPolicy.LIVE_ANALYSIS_ENABLED) {
                            niv99LiveCapture.onAnalysisFrame(analysisRawFrame)
                        }
                        // RED-aware highlight-protect input: luma-only stats above cannot see a
                        // clipping red channel, so compute it from the analysis Y + Cr planes here.
                        val highlightStartNs = SystemClock.elapsedRealtimeNanos()
                        val highlightMetrics = FrameAnalysisMath.computeHighlightMetrics(
                            frame = analysisRawFrame,
                            maxStatsLongEdge = resolveStatsLongEdge(),
                            sourcePqCandidate = sourcePqCandidate
                        )
                        val highlightMetricNs = SystemClock.elapsedRealtimeNanos() - highlightStartNs
                        val redHighlightClipFraction = highlightMetrics.redClipFraction
                        val highlightClipFraction =
                            NdrAdaptiveHighlightExposurePolicy.highlightClipFraction(stats.histogram)
                        val pqHighlightPressure = highlightMetrics.pqPressure
                        if (sourcePqCandidate) {
                            pqMetricNs = highlightMetricNs
                        } else {
                            redMetricNs = highlightMetricNs
                        }
                        val metricsValid =
                            stats.avgLuma.isFinite() && stats.histogram.any { it > 0 }

                        // Keep the legacy diagnostics available, then atomically publish the complete
                        // exposure tuple last. CameraViewModel consumes only this one-frame snapshot.
                        _histogram.value = stats.histogram
                        _avgLuma.value = stats.avgLuma
                        _redHighlightClipFraction.value = redHighlightClipFraction
                        _ndrExposureMetrics.value = NdrExposureMetrics(
                            averageLuma = stats.avgLuma,
                            highlightClipFraction = highlightClipFraction,
                            redHighlightClipFraction = redHighlightClipFraction,
                            pqHighlightPressureFraction = pqHighlightPressure.luminanceFraction,
                            pqRedHighlightPressureFraction = pqHighlightPressure.redChannelFraction,
                            sourcePqCandidate = sourcePqCandidate,
                            valid = metricsValid,
                            measuredAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                            sessionGeneration = sensorRecord.registrySessionGeneration
                        )
                        val previous = previousLumaFrame
                        val decisionStartNs = SystemClock.elapsedRealtimeNanos()
                        val analysis = decisionEngine.analyze(
                            analysisRawFrame,
                            stats.histogram,
                            stats.avgLuma,
                            previous
                        )
                        decisionNs = SystemClock.elapsedRealtimeNanos() - decisionStartNs
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

                    if (!retainedAsPreviousFrame) {
                        byteArrayPool.release(snapshot.luma)
                    }
                }
                // Non-refresh frames only needed the luma snapshot; finish their Image ownership here.
                finishImageBackedIngress()
                processSucceeded = true
            } finally {
                if (!aiIngressScheduled) closeImageOnce()
                if (processSucceeded) {
                    sensorFramePipeline.markDomainCompleted(
                        sensorRecord,
                        FrameDomain.IMAGE_PROCESSING
                    )
                } else {
                    sensorFramePipeline.markDomainFailed(
                        sensorRecord,
                        FrameDomain.IMAGE_PROCESSING,
                        null
                    )
                }
                sensorFramePipeline.finishStage(processStageToken, processSucceeded)
                // Record frame processing time for adaptive load management
                val processTimeMs = SystemClock.elapsedRealtime() - processStart
                val breakdownMessage =
                    "processFrame breakdown total=${processTimeMs}ms " +
                        "lumaUs=${lumaSnapshotNs / 1_000L} statsUs=${sceneStatsNs / 1_000L} " +
                        "chromaUs=${chromaSnapshotNs / 1_000L} redUs=${redMetricNs / 1_000L} " +
                        "pqUs=${pqMetricNs / 1_000L} decisionUs=${decisionNs / 1_000L}"
                if (processTimeMs >= SLOW_FRAME_PROCESS_MS) {
                    Log.i("FramePipeline", breakdownMessage)
                }
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
                quiescenceGate.signalStateChanged()
            }
        }
        } catch (t: Throwable) {
            // #6 fix (cont.): reached only when the executor runnable never ran, so its finally never fired.
            // Reset the processing gate and close the Image so the next frame proceeds and no buffer leaks.
            processing.set(false)
            quiescenceGate.signalStateChanged()
            runCatching { image.close() }
            sensorFramePipeline.markDomainFailed(
                sensorRecord,
                FrameDomain.IMAGE_PROCESSING,
                t
            )
            if (t is RejectedExecutionException) {
                Log.d("FramePipeline", "processFrame executor rejected during teardown; frame dropped")
            } else {
                Log.w("FramePipeline", "processFrame synchronous stage threw; pipeline gate reset", t)
            }
        }
    }

    private fun maybePublishNpv10CadenceExposureMetrics(
        image: Image,
        frameSequence: Long,
        sessionGeneration: Long
    ) {
        if (!runtimeRecordingHint || sourcePqAnalysisEnabled ||
            !Yuv420ImageTools.isP010(image) ||
            !P010CadenceExposureMeter.shouldMeasure(image.width, image.height, frameSequence)
        ) return
        val plane = image.planes.firstOrNull() ?: return
        val metrics = runCatching {
            P010CadenceExposureMeter.measure(
                buffer = plane.buffer,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride
            )
        }.onFailure { failure ->
            Log.w("FramePipeline", "NPV10 1K/30 cadence meter failed independently", failure)
        }.getOrNull() ?: return
        _ndrExposureMetrics.value = NdrExposureMetrics(
            averageLuma = metrics.averageLuma,
            highlightClipFraction = metrics.highlightClipFraction,
            redHighlightClipFraction = 0f,
            pqHighlightPressureFraction = 0f,
            pqRedHighlightPressureFraction = 0f,
            sourcePqCandidate = false,
            valid = metrics.sampleCount > 0,
            measuredAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            sessionGeneration = sessionGeneration
        )
    }

    /**
     * Pixel colour evidence is published only after the Image timestamp joins an exact Camera2
     * capture identity for the currently active session. One immutable packet carries generation,
     * source geometry, format, dataspace and timestamp so readers can never mix fields from two
     * frames or two session generations.
     */
    private fun recordRawYuvPixelEvidence(
        image: Image,
        timestampNs: Long,
        sensorRecord: SensorFrameRecord
    ) {
        if (timestampNs <= 0L) return
        val dataSpaceApiAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val dataSpace = if (dataSpaceApiAvailable) {
            runCatching { image.dataSpace }.getOrDefault(0)
        } else {
            0
        }
        val candidate = PendingRawYuvPixelEvidence(
            sensorTimestampNs = timestampNs,
            registrySessionGeneration = sensorRecord.registrySessionGeneration,
            width = image.width,
            height = image.height,
            imageFormat = image.format,
            dataSpace = dataSpace,
            dataSpaceApiAvailable = dataSpaceApiAvailable
        )
        pendingRawYuvPixelEvidence[timestampNs] = candidate
        if (pendingRawYuvPixelEvidence.size > MAX_PENDING_RAW_YUV_PIXEL_EVIDENCE) {
            pendingRawYuvPixelEvidence.keys.minOrNull()?.let(pendingRawYuvPixelEvidence::remove)
        }
        promoteRawYuvPixelAuthority(sensorRecord.identity.snapshot())
    }

    private fun promoteRawYuvPixelAuthority(identity: FrameIdentity) {
        if (!identity.isHardwareAuthoritative || identity.sensorTimestampNs <= 0L) return
        val activeGeneration = activeCameraSessionGeneration.get()
        if (identity.sessionGeneration != activeGeneration) return
        val candidate = pendingRawYuvPixelEvidence.remove(identity.sensorTimestampNs) ?: return
        if (candidate.registrySessionGeneration != identity.sessionGeneration) return
        latestRawYuvPixelAuthority.set(
            RawYuvPixelAuthorityTelemetry(
                exactCaptureIdentity = true,
                sessionGeneration = identity.sessionGeneration,
                sensorTimestampNs = identity.sensorTimestampNs,
                width = candidate.width,
                height = candidate.height,
                imageFormat = candidate.imageFormat,
                dataSpace = candidate.dataSpace,
                dataSpaceApiAvailable = candidate.dataSpaceApiAvailable
            )
        )
    }

    fun enqueueHardware(reader: ImageReader) {
        hardwareListenerCallbackCounter.incrementAndGet()
        drainHardwareReader(reader, deferred = false)
    }

    private fun drainHardwareReader(reader: ImageReader, deferred: Boolean): Int {
        // Deferred drains acquire from ImageReader outside CameraController's listener callback. They
        // therefore require the same close seal as synchronous YUV ingress; otherwise a queued worker
        // can call acquireNextImage() after CameraController has observed the pipeline as idle.
        val imageIngressLease = synchronousImageIngress.tryEnter() ?: run {
            if (pendingHardwareReader.compareAndSet(reader, null)) {
                quiescenceGate.signalStateChanged()
            }
            return 0
        }
        return try {
            if (!running.get()) {
                if (pendingHardwareReader.compareAndSet(reader, null)) {
                    quiescenceGate.signalStateChanged()
                }
                0
            } else {
                // Camera2's listener is a timing-critical producer and must never wait behind lifecycle clear
                // or the deferred reader drain. On contention it publishes the reader and returns immediately;
                // the hardware worker may wait because it is already outside the capture callback.
                val acquisitionLease = if (deferred) {
                    hardwareReaderAcquisitionGate.enterDeferred()
                } else {
                    hardwareReaderAcquisitionGate.tryEnterListener()
                }
                if (acquisitionLease == null) {
                    deferHardwareReader(reader)
                    0
                } else try {
                    if (!running.get()) {
                        if (pendingHardwareReader.compareAndSet(reader, null)) {
                            quiescenceGate.signalStateChanged()
                        }
                        0
                    } else {
                        drainHardwareReaderBody(reader, deferred)
                    }
                } finally {
                    acquisitionLease.close()
                }
            }
        } finally {
            imageIngressLease.close()
        }
    }

    private fun deferHardwareReader(reader: ImageReader) {
        if (!running.get()) return
        pendingHardwareReader.set(reader)
        if (running.get()) {
            scheduleHardwareDispatchDrain()
        } else {
            if (pendingHardwareReader.compareAndSet(reader, null)) {
                quiescenceGate.signalStateChanged()
            }
        }
    }

    private fun drainHardwareReaderBody(reader: ImageReader, deferred: Boolean): Int {
        val readerMaxImages = runCatching { reader.maxImages }
            .getOrDefault(MAX_HARDWARE_IMPORT_DRAIN_IMAGES)
            .coerceIn(1, MAX_HARDWARE_IMPORT_DRAIN_IMAGES)
        val queuedBeforeAcquire = hardwareFrameDispatchQueues.queuedFrameCount()
        // Count both stages: one Image may be resolving exact metadata while the previous Image is
        // submitted to the GPU. The combined reader ceiling therefore remains unchanged.
        val activeSubmit = preparedHardwareFramePool.inFlightCount()
        val highWatermark = RecordPipelineDepthFlag.hardwareImportReaderHighWatermark(readerMaxImages)
        val inFlightLimit = SecondaryWorkloadConsumerPolicy.hardwareImportInFlightLimit(
            hardwareHighWatermark = highWatermark,
            activeSubmitCount = activeSubmit,
            recordingActive = runtimeRecordingHint,
            decision = sensorFramePipeline.adaptationDecision()
        )
        val acquireBudget = (inFlightLimit - queuedBeforeAcquire - activeSubmit).coerceAtLeast(0)
        if (acquireBudget <= 0) {
            pendingHardwareReader.set(reader)
            val skipped = hardwareAcquireSkippedCounter.incrementAndGet()
            if (skipped == 1L || skipped % 60L == 0L) {
                Log.w(
                    "FramePipeline",
                    "Hardware import acquire skipped count=$skipped queued=$queuedBeforeAcquire " +
                        "activeSubmit=$activeSubmit inFlightLimit=$inFlightLimit " +
                        "highWatermark=$highWatermark " +
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
                    val telemetry = hardwareFrameDispatchQueues.telemetry()
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
                                "activeSubmit=${preparedHardwareFramePool.inFlightCount()} " +
                                "inFlightLimit=$inFlightLimit highWatermark=$highWatermark " +
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
            val nowMs = SystemClock.elapsedRealtime()
            val previousLogMs = lastHardwareImportDrainCapLogElapsedMs.get()
            if (nowMs - previousLogMs >= HARDWARE_IMPORT_DRAIN_CAP_LOG_INTERVAL_MS &&
                lastHardwareImportDrainCapLogElapsedMs.compareAndSet(previousLogMs, nowMs)
            ) {
                Log.d(
                    "FramePipeline",
                    "Hardware import drain reached cap=$drainedImages deferred=$deferred " +
                        "queued=$queuedBeforeAcquire activeSubmit=$activeSubmit " +
                        "acquireBudget=$acquireBudget"
                )
            }
        } else {
            if (pendingHardwareReader.compareAndSet(reader, null)) {
                quiescenceGate.signalStateChanged()
            }
        }
        return drainedImages
    }

    private fun enqueueHardwareImage(image: Image) {
        if (!running.get()) {
            runCatching { image.close() }
                .onFailure { throwable ->
                    Log.w("FramePipeline", "Stopped hardware import Image.close failed", throwable)
                }
            return
        }
        // Capture once at acceptance. Reading runtimeRecordingHint again in the worker used to let a
        // recording start/stop reclassify already-queued frames and could pollLatest-drop a recording.
        val recordingAdmission = hardwareRecordingAdmissionFence.capture()
        val route = HardwareImportRoute.capture(recordingAdmission.recordingActive)
        if (route.recordingPath) {
            hardwareQueuedRecordingCounter.incrementAndGet()
            hardwareRecordingAcquisitionCounter.incrementAndGet()
        }
        val callbackCount = hardwareCallbackCounter.incrementAndGet()
        val rawTimestampNs = try { image.timestamp } catch (_: Exception) { 0L }
        val monotonicBeforeNs = System.nanoTime()
        val receivedAtElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
        val monotonicAfterNs = System.nanoTime()
        val timestampNs = sensorTimestampTimebase.normalizeToReference(
            timestampNs = rawTimestampNs,
            monotonicNowNs = midpoint(monotonicBeforeNs, monotonicAfterNs),
            boottimeNowNs = receivedAtElapsedRealtimeNs
        )
        if (timestampNs > 0L) {
            hardwareFirstSensorTimestampNs.compareAndSet(0L, timestampNs)
            hardwareLastSensorTimestampNs.set(timestampNs)
        }
        if (rawTimestampNs > 0L) {
            hardwareFirstRawSensorTimestampNs.compareAndSet(0L, rawTimestampNs)
            hardwareLastRawSensorTimestampNs.set(rawTimestampNs)
        }
        hardwareFirstReceivedElapsedRealtimeNs.compareAndSet(0L, receivedAtElapsedRealtimeNs)
        hardwareLastReceivedElapsedRealtimeNs.set(receivedAtElapsedRealtimeNs)
        latestHardwareImportSourceIngress.set(
            HardwareImportSourceIngressTelemetry(
                sourceImageCount = callbackCount,
                sessionGeneration = activeCameraSessionGeneration.get(),
                sensorTimestampNs = timestampNs,
                rawSensorTimestampNs = rawTimestampNs,
                receivedAtElapsedRealtimeNs = receivedAtElapsedRealtimeNs,
                width = image.width,
                height = image.height,
                imageFormat = image.format
            )
        )
        if (callbackCount == 1L) {
            runCatching {
                Log.i(
                    "FramePipeline",
                    "First Camera2 hardware-import frame received " +
                        "${image.width}x${image.height} format=${image.format}"
                )
            }.onFailure { throwable ->
                Log.w("FramePipeline", "Unable to inspect first hardware import frame", throwable)
            }
        }
        val hardwareDomain = route.domain
        val sensorRecord = try {
            sensorFramePipeline.onImageAvailable(
                sensorTimestampNs = timestampNs,
                arrivedAtNs = SystemClock.elapsedRealtimeNanos()
            ).also { record ->
                sensorFramePipeline.markDomainQueued(
                    record,
                    hardwareDomain,
                    "hardware-buffer-import"
                )
            }
        } catch (throwable: Throwable) {
            runCatching { image.close() }
                .onFailure { closeFailure ->
                    Log.w("FramePipeline", "Hardware import Image.close failed", closeFailure)
                }
            Log.e("FramePipeline", "Hardware import admission metadata failed", throwable)
            if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
            return
        }
        val routedFrame = RoutedHardwareFrame(
            resource = image,
            route = route,
            recordingAdmission = recordingAdmission,
            sensorRecord = sensorRecord,
            closeResource = Image::close,
            reportCloseFailure = { throwable ->
                Log.w("FramePipeline", "Hardware import Image.close failed", throwable)
            }
        )
        val offer = hardwareFrameDispatchQueues.offer(
            frame = routedFrame,
            sensorTimestampNs = timestampNs,
            nowNs = SystemClock.elapsedRealtimeNanos()
        )
        if (!offer.accepted) {
            markHardwareFrameNotSubmitted(routedFrame, sensorRecord, offer.reason)
            Log.d("FramePipeline", "Hardware import frame rejected: ${offer.reason}")
            closeHardwareFrame(routedFrame)
            return
        }
        updateHardwareMaximumQueueDepth(hardwareFrameDispatchQueues.queuedFrameCount())
        runCatching {
            sensorFramePipeline.setQueueDepth(
                hardwareDomain,
                hardwareFrameDispatchQueues.queuedFrameCount()
            )
        }.onFailure { throwable ->
            Log.w("FramePipeline", "Hardware import queue-depth telemetry failed", throwable)
        }
        scheduleHardwareDispatchDrain()
    }

    private fun releaseHardwareQueueMembership(frame: RoutedHardwareFrame<Image>) {
        if (!frame.leaveQueue() || !frame.route.recordingPath) return
        while (true) {
            val current = hardwareQueuedRecordingCounter.get()
            if (current <= 0L) return
            if (hardwareQueuedRecordingCounter.compareAndSet(current, current - 1L)) return
        }
    }

    private fun closeHardwareFrame(frame: RoutedHardwareFrame<Image>) {
        releaseHardwareQueueMembership(frame)
        if (frame.closeOnce()) {
            hardwareClosedCounter.incrementAndGet()
        }
    }

    private fun updateHardwareMaximumQueueDepth(observedDepth: Int) {
        val safeDepth = observedDepth.coerceAtLeast(0)
        while (true) {
            val current = hardwareMaximumQueueDepth.get()
            if (safeDepth <= current || hardwareMaximumQueueDepth.compareAndSet(current, safeDepth)) {
                return
            }
        }
    }

    private fun scheduleHardwareDispatchDrain() {
        if (!hardwareDispatchScheduled.compareAndSet(false, true)) return
        try {
            hardwareDispatchExecutor.execute {
                try {
                    // At a preview->recording transition only, free monitoring capacity before
                    // acquisition. Otherwise retain the established acquire-first ordering so the
                    // ImageReader cannot fill while already-routed recording work is submitted.
                    if (
                        runtimeRecordingHint &&
                        !hardwareFrameDispatchQueues.hasQueuedRecordingFrames() &&
                        hardwareFrameDispatchQueues.hasQueuedPreviewFrames()
                    ) {
                        drainHardwareDispatchQueue()
                    }
                    drainPendingHardwareReaderIfCapacity()
                    drainHardwareDispatchQueue()
                    drainPendingHardwareReaderIfCapacity()
                } finally {
                    hardwareDispatchScheduled.set(false)
                    quiescenceGate.signalStateChanged()
                    if (
                        running.get() &&
                        (hardwareFrameDispatchQueues.hasQueuedFrames() || pendingHardwareReaderHasAcquireCapacity())
                    ) {
                        scheduleHardwareDispatchDrain()
                    }
                }
            }
        } catch (exception: RejectedExecutionException) {
            hardwareDispatchScheduled.set(false)
            pendingHardwareReader.getAndSet(null)
            quiescenceGate.signalStateChanged()
            if (running.get()) {
                Log.e("FramePipeline", "Hardware dispatch worker rejected a live drain", exception)
            }
        }
    }

    private fun pendingHardwareReaderHasAcquireCapacity(): Boolean {
        val reader = pendingHardwareReader.get() ?: return false
        if (synchronousImageIngress.isSealed()) {
            if (pendingHardwareReader.compareAndSet(reader, null)) {
                quiescenceGate.signalStateChanged()
            }
            return false
        }
        val readerMaxImages = runCatching { reader.maxImages }.getOrNull()
            ?: run {
                if (pendingHardwareReader.compareAndSet(reader, null)) {
                    quiescenceGate.signalStateChanged()
                }
                return false
            }
        val highWatermark = RecordPipelineDepthFlag.hardwareImportReaderHighWatermark(
            readerMaxImages.coerceIn(1, MAX_HARDWARE_IMPORT_DRAIN_IMAGES)
        )
        val activeSubmit = preparedHardwareFramePool.inFlightCount()
        val inFlightLimit = SecondaryWorkloadConsumerPolicy.hardwareImportInFlightLimit(
            hardwareHighWatermark = highWatermark,
            activeSubmitCount = activeSubmit,
            recordingActive = runtimeRecordingHint,
            decision = sensorFramePipeline.adaptationDecision()
        )
        return inFlightLimit > hardwareFrameDispatchQueues.queuedFrameCount() + activeSubmit
    }

    private fun drainPendingHardwareReaderIfCapacity() {
        if (!running.get()) return
        val reader = pendingHardwareReader.get() ?: return
        if (!pendingHardwareReaderHasAcquireCapacity()) return
        drainHardwareReader(reader, deferred = true)
    }

    private fun drainHardwareDispatchQueue() {
        while (true) {
            var latestPreview: RealtimeQueuedFrame<RoutedHardwareFrame<Image>>? = null
            while (true) {
                val queued = pollHardwareDispatchFrame() ?: break
                releaseHardwareQueueMembership(queued.frame)
                if (queued.frame.route.recordingPath) {
                    // Recording is never conflated. A pending monitoring frame can be shed before it
                    // so preview work cannot delay an accepted encoder frame.
                    latestPreview?.let { preview ->
                        skipHardwarePreviewFrame(
                            queued = preview,
                            reason = "recording-priority-preview-skip",
                            stale = false
                        )
                    }
                    latestPreview = null
                    submitHardwareDispatchFrame(queued)
                } else {
                    latestPreview?.let { preview ->
                        skipHardwarePreviewFrame(
                            queued = preview,
                            reason = "latest-preview-preference",
                            stale = false
                        )
                    }
                    latestPreview = queued
                }
            }

            val preview = latestPreview ?: return
            if (runtimeRecordingHint) {
                // Do not spend the shared Vulkan submit slot on a pre-transition preview while the
                // ImageReader may already contain sensor frames belonging to the recording session.
                skipHardwarePreviewFrame(preview, "recording-active-preview-shed", stale = false)
                continue
            }

            // Close the small race between the final preview poll and GPU submit: if a recording
            // frame arrived, it wins and this monitoring frame is released without touching Vulkan.
            val lateRecording = pollHardwareRecordingFrame()
            if (lateRecording != null) {
                releaseHardwareQueueMembership(lateRecording.frame)
                skipHardwarePreviewFrame(preview, "recording-priority-preview-skip", stale = false)
                submitHardwareDispatchFrame(lateRecording)
                continue
            }

            val nowNs = SystemClock.elapsedRealtimeNanos()
            val maxPreviewAgeNs = SecondaryWorkloadConsumerPolicy.previewMaxFrameAgeNs(
                targetFps = runtimeSensorTargetFps,
                decision = sensorFramePipeline.adaptationDecision()
            )
            val stale = (nowNs - preview.enqueuedAtNs).coerceAtLeast(0L) > maxPreviewAgeNs
            if (stale) {
                skipHardwarePreviewFrame(preview, "stale-preview-frame", stale = true)
            } else {
                submitHardwareDispatchFrame(preview)
            }
            return
        }
    }

    private fun pollHardwareDispatchFrame(): RealtimeQueuedFrame<RoutedHardwareFrame<Image>>? {
        // RealtimeFrameDispatcher owns queue synchronization. Keeping dequeue independent from the
        // ImageReader gate prevents consumer polling from deferring a timing-critical Camera2 callback.
        val nowNs = SystemClock.elapsedRealtimeNanos()
        return hardwareFrameDispatchQueues.pollRecording(nowNs)
            ?: hardwareFrameDispatchQueues.pollPreview(nowNs)
    }

    private fun pollHardwareRecordingFrame(): RealtimeQueuedFrame<RoutedHardwareFrame<Image>>? {
        return hardwareFrameDispatchQueues.pollRecording(SystemClock.elapsedRealtimeNanos())
    }

    private fun skipHardwarePreviewFrame(
        queued: RealtimeQueuedFrame<RoutedHardwareFrame<Image>>,
        reason: String,
        stale: Boolean
    ) {
        if (queued.frame.route.recordingPath) {
            Log.e("FramePipeline", "Refusing to skip a recording-routed hardware frame")
            submitHardwareDispatchFrame(queued)
            return
        }
        try {
            val sensorRecord = requireNotNull(queued.frame.sensorRecord) {
                "Hardware dispatch frame lost its admission sensor identity"
            }
            sensorFramePipeline.markDomainSkipped(
                sensorRecord,
                FrameDomain.PREVIEW,
                reason,
                stalePreview = stale
            )
        } finally {
            hardwarePreviewSkippedCounter.incrementAndGet()
            if (stale) hardwareStalePreviewCounter.incrementAndGet()
            closeHardwareFrame(queued.frame)
        }
    }

    private fun submitHardwareDispatchFrame(
        queued: RealtimeQueuedFrame<RoutedHardwareFrame<Image>>
    ) {
        val routedFrame = queued.frame
        val route = routedFrame.route
        val activeDomain = route.domain
        val sensorRecord = try {
            requireNotNull(routedFrame.sensorRecord) {
                "Hardware dispatch frame lost its admission sensor identity"
            }
        } catch (throwable: Throwable) {
            closeHardwareFrame(routedFrame)
            Log.e("FramePipeline", "Hardware frame identity association failed", throwable)
            if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
            return
        }
        var preparationSlot: PreparedHardwareFrameSlot<Image>? = null
        var handedToSubmitPath = false
        try {
            if (!running.get()) {
                markHardwareFrameNotSubmitted(routedFrame, sensorRecord, "pipeline-stopped-before-prepare")
                return
            }
            sensorFramePipeline.setQueueDepth(activeDomain, hardwareFrameDispatchQueues.queuedFrameCount())
            val presentationTimeNs = sensorPresentationClock.presentationTimeFor(
                sensorTimestampNs = queued.sensorTimestampNs,
                nowNs = SystemClock.elapsedRealtimeNanos()
            )
            while (running.get() && preparationSlot == null) {
                preparationSlot = preparedHardwareFramePool.acquire(HARDWARE_PREPARATION_SLOT_WAIT_MS)
            }
            val slot = preparationSlot ?: run {
                markHardwareFrameNotSubmitted(routedFrame, sensorRecord, "pipeline-stopped-before-prepare-slot")
                return
            }
            val liveNiv99Enabled =
                Niv99ExecutionBoundaryPolicy.allowsLivePixelProcessing(route.recordingPath)
            val frameRailRoute = NeoZeroCopyFrameRailPolicy.route(liveNiv99Enabled)
            val metadataStartedNs = SystemClock.elapsedRealtimeNanos()
            val lookup = if (liveNiv99Enabled) {
                prepareNiv99TuningForHardwareFrame(
                    record = sensorRecord,
                    recordingPath = route.recordingPath,
                    output = slot.tuning
                )
            } else {
                slot.tuning.clear()
                Niv99MetadataLookupStatus.NOT_CONFIGURED
            }
            val metadataWaitNs = if (liveNiv99Enabled) {
                (SystemClock.elapsedRealtimeNanos() - metadataStartedNs).coerceAtLeast(0L)
            } else {
                0L
            }
            if (route.recordingPath && liveNiv99Enabled) {
                hardwareMetadataWaitCounter.incrementAndGet()
                hardwareMetadataWaitTotalNs.addAndGet(metadataWaitNs)
                hardwareMetadataWaitMaximumNs.accumulateAndGet(metadataWaitNs, ::maxOf)
            }
            if (!running.get()) {
                markHardwareFrameNotSubmitted(routedFrame, sensorRecord, "pipeline-stopped-after-prepare")
                return
            }
            if (route.recordingPath &&
                !hardwareRecordingAdmissionFence.isCurrent(routedFrame.recordingAdmission)
            ) {
                markHardwareFrameNotSubmitted(
                    routedFrame,
                    sensorRecord,
                    "recording-admission-retired-after-prepare"
                )
                return
            }
            if (sensorRecord.registrySessionGeneration != activeCameraSessionGeneration.get()) {
                markHardwareFrameNotSubmitted(
                    routedFrame,
                    sensorRecord,
                    "camera-session-changed-after-prepare"
                )
                return
            }
            slot.bind(
                queuedFrame = queued,
                resolvedPresentationTimeNs = presentationTimeNs,
                lookup = lookup,
                waitNs = metadataWaitNs
            )
            hardwarePreparedFrameCounter.incrementAndGet()
            handedToSubmitPath = true
            if (frameRailRoute == NeoFrameRailSubmissionRoute.BOUNDED_METADATA_OVERLAP) {
                // Only exact live metadata work benefits from two-stage overlap. The prepared slot
                // owns its tuning payload across the hand-off, so the GPU lane remains allocation-
                // free, bounded, and FIFO when this optional live path is explicitly enabled.
                preparedHardwareFrames.offer(slot)
                hardwareOverlappedSubmitCounter.incrementAndGet()
                updateHardwarePreparedMaximumQueueDepth(preparedHardwareFrames.size)
                scheduleHardwareGpuDispatchDrain()
            } else {
                // Fast path for the production capture boundary: submit on the same single worker
                // that drained the frame. This preserves EGL/driver thread affinity and restores the
                // known-good full-resolution cadence without changing a pixel or quality setting.
                hardwareImmediateSubmitCounter.incrementAndGet()
                submitPreparedHardwareFrame(slot)
            }
        } catch (throwable: Throwable) {
            sensorFramePipeline.markDomainFailed(sensorRecord, activeDomain, throwable)
            if (route.recordingPath) {
                markHardwareRecordingFailure(
                    routedFrame,
                    sensorRecord,
                    "hardware-metadata-prepare-exception"
                )
            }
            Log.e("FramePipeline", "Hardware import metadata preparation failed", throwable)
            if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
        } finally {
            if (!handedToSubmitPath) {
                closeHardwareFrame(routedFrame)
                preparationSlot?.let(::recyclePreparedHardwareSlot)
                quiescenceGate.signalStateChanged()
            }
        }
    }

    private fun prepareNiv99TuningForHardwareFrame(
        record: SensorFrameRecord,
        recordingPath: Boolean,
        output: Niv99LiveFrameTuning
    ): Niv99MetadataLookupStatus {
        if (!Niv99ExecutionBoundaryPolicy.allowsLivePixelProcessing(recordingPath)) {
            output.clear()
            return Niv99MetadataLookupStatus.NOT_CONFIGURED
        }
        val identity = record.identity.snapshot()
        output.clear()
        // Camera2 may deliver a P010 Image before its result/tuning packet. The recording lane keeps
        // the exact bounded join, but performs it independently of native submission. Preview remains
        // a non-blocking cache lookup. No adjacent-frame or stale-metadata substitution is permitted.
        return if (recordingPath) {
            niv99LiveCapture.awaitTuningForImage(
                cameraId = niv99CameraId.get(),
                sessionGeneration = record.registrySessionGeneration,
                sensorTimestampNs = identity.sensorTimestampNs,
                timeoutNs = runtimeNiv99ExactMetadataWaitNs,
                output = output
            )
        } else {
            niv99LiveCapture.copyTuningForImage(
                cameraId = niv99CameraId.get(),
                sessionGeneration = record.registrySessionGeneration,
                sensorTimestampNs = identity.sensorTimestampNs,
                output = output
            )
        }
    }

    private fun scheduleHardwareGpuDispatchDrain() {
        if (!hardwareGpuDispatchScheduled.compareAndSet(false, true)) return
        try {
            hardwareGpuSubmitExecutor.execute {
                try {
                    while (true) {
                        val prepared = preparedHardwareFrames.poll() ?: break
                        submitPreparedHardwareFrame(prepared)
                    }
                } finally {
                    hardwareGpuDispatchScheduled.set(false)
                    quiescenceGate.signalStateChanged()
                    if (preparedHardwareFrames.isNotEmpty()) {
                        scheduleHardwareGpuDispatchDrain()
                    }
                }
            }
        } catch (exception: RejectedExecutionException) {
            hardwareGpuDispatchScheduled.set(false)
            discardQueuedPreparedHardwareFrames("gpu-submit-worker-rejected")
            quiescenceGate.signalStateChanged()
            if (running.get()) {
                Log.e("FramePipeline", "Hardware GPU submit worker rejected a live drain", exception)
            }
        }
    }

    private fun submitPreparedHardwareFrame(slot: PreparedHardwareFrameSlot<Image>) {
        val queued = slot.frame ?: run {
            recyclePreparedHardwareSlot(slot)
            return
        }
        val routedFrame = queued.frame
        val route = routedFrame.route
        val activeDomain = route.domain
        val sensorRecord = routedFrame.sensorRecord ?: run {
            Log.e("FramePipeline", "Prepared hardware frame lost its admission sensor identity")
            closeHardwareFrame(routedFrame)
            recyclePreparedHardwareSlot(slot)
            return
        }
        val recordingAdmissionLocked = route.recordingPath
        if (recordingAdmissionLocked) hardwareRecordingAdmissionTransitionLock.lock()
        try {
            if (!running.get()) {
                markHardwareFrameNotSubmitted(
                    routedFrame,
                    sensorRecord,
                    "pipeline-stopped-before-gpu-submit"
                )
                return
            }
            if (route.recordingPath &&
                !hardwareRecordingAdmissionFence.isCurrent(routedFrame.recordingAdmission)
            ) {
                markHardwareFrameNotSubmitted(
                    routedFrame,
                    sensorRecord,
                    "recording-admission-retired-before-gpu-submit"
                )
                return
            }
            if (sensorRecord.registrySessionGeneration != activeCameraSessionGeneration.get()) {
                markHardwareFrameNotSubmitted(
                    routedFrame,
                    sensorRecord,
                    "camera-session-changed-before-gpu-submit"
                )
                return
            }
            hardwareSubmitInFlight.set(true)
            hardwareVulkanAttemptCounter.incrementAndGet()
            val submittedToVulkan = submitTrackedImageToVulkan(
                image = routedFrame.resource,
                record = sensorRecord,
                presentationTimeNs = slot.presentationTimeNs,
                recordingPath = route.recordingPath,
                preparedTuning = slot.tuning,
                preparedLookup = slot.metadataLookup
            )
            if (submittedToVulkan) {
                val submitted = hardwareSubmittedCounter.incrementAndGet()
                sensorPresentationClock.recordGpuCompletion(
                    sensorTimestampNs = queued.sensorTimestampNs,
                    completionNs = SystemClock.elapsedRealtimeNanos()
                )
                lastHardwareImportElapsedMs.set(SystemClock.elapsedRealtime())
                if (submitted % HARDWARE_IMPORT_DIAGNOSTIC_INTERVAL_FRAMES == 0L) {
                    scheduleHardwareImportDiagnostics(
                        submittedAtSchedule = submitted,
                        sessionGeneration = sensorRecord.registrySessionGeneration
                    )
                }
            } else {
                val submissionOutcome = vulkanRuntime?.lastCameraImageSubmissionOutcome()
                val rejectionReason = submissionOutcome?.reason
                    ?.takeIf(String::isNotBlank)
                    ?: "unknown"
                if (route.recordingPath && submissionOutcome?.recordingSubmissionExpected != false) {
                    markHardwareRecordingFailure(
                        routedFrame,
                        sensorRecord,
                        "vulkan-hardware-submit-rejected:$rejectionReason"
                    )
                } else {
                    // Camera2 starts feeding the persistent pre-armed surface before the live encoder
                    // gate opens and can leave one tail image after it closes. Those frames are outside
                    // the clip and remain visible as hardware-import failures, but are not recording
                    // drops. This keeps zero-drop evidence scoped to frames the encoder was expected
                    // to consume rather than hiding a genuine live rejection.
                    sensorFramePipeline.markDomainSkipped(
                        sensorRecord,
                        activeDomain,
                        "non-clip-vulkan-submit:$rejectionReason",
                        stalePreview = false
                    )
                }
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
                            "recordingExpected=${submissionOutcome?.recordingSubmissionExpected} " +
                            "reason=$rejectionReason " +
                            "runtime=${vulkanRuntime?.capabilitySummary?.value.orEmpty()}"
                    )
                }
            }
        } catch (throwable: Throwable) {
            sensorFramePipeline.markDomainFailed(sensorRecord, activeDomain, throwable)
            if (route.recordingPath) {
                markHardwareRecordingFailure(
                    routedFrame,
                    sensorRecord,
                    "vulkan-hardware-submit-exception"
                )
            }
            Log.e("FramePipeline", "Hardware import submit failed independently", throwable)
            if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
        } finally {
            try {
                closeHardwareFrame(routedFrame)
                hardwareSubmitInFlight.set(false)
                recyclePreparedHardwareSlot(slot)
                quiescenceGate.signalStateChanged()
                scheduleHardwareDispatchDrain()
            } finally {
                if (recordingAdmissionLocked) hardwareRecordingAdmissionTransitionLock.unlock()
            }
        }
    }

    /**
     * Samples observability on the existing analysis/control lane. None of this diagnostic work is
     * allowed to extend native GPU Image ownership: the submit worker now performs only submission,
     * exact completion accounting, one atomic liveness publication, and resource release.
     */
    private fun scheduleHardwareImportDiagnostics(
        submittedAtSchedule: Long,
        sessionGeneration: Long
    ) {
        if (!hardwareImportDiagnosticsScheduled.compareAndSet(false, true)) return
        try {
            processingExecutor.execute {
                try {
                    if (!running.get() || activeCameraSessionGeneration.get() != sessionGeneration) {
                        return@execute
                    }
                    val telemetry = realtimeDispatchTelemetry()
                    val submitted = hardwareSubmittedCounter.get().coerceAtLeast(submittedAtSchedule)
                    val waitCount = hardwareMetadataWaitCounter.get().coerceAtLeast(1L)
                    Log.i(
                        "FramePipeline",
                        "Hardware import submitted=$submitted closed=${hardwareClosedCounter.get()} " +
                            "queued=${telemetry.queuedFrames} accepted=${telemetry.acceptedFrames} " +
                            "dropped=${telemetry.droppedFrames} rejected=${telemetry.rejectedFrames} " +
                            "avgQueueLatencyNs=${telemetry.averageQueueLatencyNs} " +
                            "preparationInFlight=${preparedHardwareFramePool.inFlightCount()} " +
                            "metadataWaitAvgNs=${hardwareMetadataWaitTotalNs.get() / waitCount} " +
                            "metadataWaitMaxNs=${hardwareMetadataWaitMaximumNs.get()}"
                    )
                    val tuning = PipelineOrchestrator.resolveBackpressureTuning(
                        droppedFrames = telemetry.droppedFrames,
                        rejectedFrames = telemetry.rejectedFrames,
                        averageQueueLatencyNs = telemetry.averageQueueLatencyNs,
                        strictNoQualityDowngrade = strictNoQualityDowngradeActive()
                    )
                    if (tuning.reason != "stable-full-quality") {
                        Log.d("FramePipeline", "Realtime dispatch tuning: ${tuning.reason}")
                    }
                } catch (throwable: Throwable) {
                    Log.w("FramePipeline", "Hardware import diagnostic sample failed", throwable)
                    if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
                } finally {
                    hardwareImportDiagnosticsScheduled.set(false)
                }
            }
        } catch (exception: RejectedExecutionException) {
            hardwareImportDiagnosticsScheduled.set(false)
            if (running.get()) {
                Log.w("FramePipeline", "Hardware import diagnostic sample was rejected", exception)
            }
        }
    }

    private fun markHardwareFrameNotSubmitted(
        routedFrame: RoutedHardwareFrame<Image>,
        sensorRecord: SensorFrameRecord,
        reason: String
    ) {
        val route = routedFrame.route
        if (route.recordingPath) {
            markHardwareRecordingFailure(routedFrame, sensorRecord, reason)
        } else {
            sensorFramePipeline.markDomainSkipped(
                record = sensorRecord,
                domain = FrameDomain.PREVIEW,
                reason = reason,
                stalePreview = false
            )
        }
    }

    private fun markHardwareRecordingFailure(
        routedFrame: RoutedHardwareFrame<Image>,
        sensorRecord: SensorFrameRecord,
        reason: String
    ): Boolean {
        if (hardwareRecordingAdmissionFence.isLossEligible(routedFrame.recordingAdmission)) {
            return sensorFramePipeline.markRecordingDrop(sensorRecord, reason)
        }
        sensorFramePipeline.markDomainSkipped(
            record = sensorRecord,
            domain = FrameDomain.ENCODER,
            reason = "outside-clip-admission:$reason",
            stalePreview = false
        )
        return false
    }

    private fun recyclePreparedHardwareSlot(slot: PreparedHardwareFrameSlot<Image>) {
        if (!preparedHardwareFramePool.recycle(slot)) {
            Log.e("FramePipeline", "Prepared hardware slot was recycled more than once")
        }
        quiescenceGate.signalStateChanged()
    }

    private fun discardQueuedPreparedHardwareFrames(reason: String) {
        while (true) {
            val slot = preparedHardwareFrames.poll() ?: return
            val routedFrame = slot.frame?.frame
            val sensorRecord = routedFrame?.sensorRecord
            if (routedFrame != null && sensorRecord != null) {
                markHardwareFrameNotSubmitted(routedFrame, sensorRecord, reason)
                closeHardwareFrame(routedFrame)
            }
            recyclePreparedHardwareSlot(slot)
        }
    }

    private fun updateHardwarePreparedMaximumQueueDepth(observedDepth: Int) {
        val safeDepth = observedDepth.coerceAtLeast(0)
        while (true) {
            val current = hardwarePreparedQueueMaximumDepth.get()
            if (safeDepth <= current ||
                hardwarePreparedQueueMaximumDepth.compareAndSet(current, safeDepth)
            ) return
        }
    }

    /** Non-hardware analysis ingress retains its immediate, allocation-free preview lookup. */
    private fun submitTrackedImageToVulkan(
        image: Image,
        record: SensorFrameRecord,
        presentationTimeNs: Long = 0L,
        recordingPath: Boolean
    ): Boolean {
        val tuning = checkNotNull(niv99TuningByThread.get()) {
            "NIV99 tuning ThreadLocal failed to initialize"
        }
        val lookup = if (Niv99ExecutionBoundaryPolicy.allowsLivePixelProcessing(recordingPath)) {
            prepareNiv99TuningForHardwareFrame(record, recordingPath, tuning)
        } else {
            tuning.clear()
            Niv99MetadataLookupStatus.NOT_CONFIGURED
        }
        return submitTrackedImageToVulkan(
            image = image,
            record = record,
            presentationTimeNs = presentationTimeNs,
            recordingPath = recordingPath,
            preparedTuning = tuning,
            preparedLookup = lookup
        )
    }

    private fun submitTrackedImageToVulkan(
        image: Image,
        record: SensorFrameRecord,
        presentationTimeNs: Long = 0L,
        recordingPath: Boolean,
        preparedTuning: Niv99LiveFrameTuning,
        preparedLookup: Niv99MetadataLookupStatus
    ): Boolean {
        val domain = if (recordingPath) FrameDomain.ENCODER else FrameDomain.PREVIEW
        val stage = if (recordingPath) PipelineStage.ENCODER else PipelineStage.PREVIEW
        sensorFramePipeline.markDomainQueued(record, domain, "vulkan-hardware-submit")
        sensorFramePipeline.markDomainRunning(record, domain)
        val token = sensorFramePipeline.beginStage(
            record = record,
            stage = stage,
            queueEnteredAtNs = record.arrivedAtMonotonicNs,
            queueDepth = hardwareFrameDispatchQueues.queuedFrameCount()
        )
        var accepted = false
        try {
            val identity = record.identity.snapshot()
            val exactTuning = preparedTuning.takeIf {
                preparedLookup == Niv99MetadataLookupStatus.MATCHED &&
                    it.valid &&
                    Niv99ExactFrameJoinPolicy.isSamePhysicalFrame(
                        imageTimestampNs = identity.sensorTimestampNs,
                        metadataTimestampNs = it.identity.sensorTimestampNs,
                        targetFps = runtimeSensorTargetFps
                    ) &&
                    it.identity.sessionGeneration == record.registrySessionGeneration &&
                    (identity.frameNumber == null || it.identity.frameNumber == identity.frameNumber)
            }
            // Once the bounded alignment proves one physical frame, carry the authoritative
            // Camera2 result timestamp into the native provenance check. Presentation cadence stays
            // on the independently resolved PTS and is not changed by this metadata correction.
            val metadataAlignedSensorTimestampNs = exactTuning
                ?.identity
                ?.sensorTimestampNs
                ?.takeIf { it > 0L }
                ?: identity.sensorTimestampNs
            accepted = submitImageToVulkan(image,
                presentationTimeNs,
                exactTuning,
                record.registrySessionGeneration,
                metadataAlignedSensorTimestampNs,
                recordingPath
            )
            if (exactTuning?.pixelApplicationApproved == true) {
                if (exactTuning.pixelApplicationSucceeded) {
                    niv99LiveCapture.onPixelRenderCompleted(exactTuning, recordingPath)
                } else {
                    niv99LiveCapture.onPixelRenderRejected(exactTuning, recordingPath)
                }
            }
            if (accepted) {
                sensorFramePipeline.markDomainCompleted(record, domain)
            } else {
                sensorFramePipeline.markDomainFailed(record, domain, null)
            }
            return accepted
        } catch (throwable: Throwable) {
            sensorFramePipeline.markDomainFailed(record, domain, throwable)
            throw throwable
        } finally {
            sensorFramePipeline.finishStage(token, accepted)
        }
    }

    private fun submitImageToVulkan(
        image: Image,
        presentationTimeNs: Long = 0L,
        niv99Tuning: Niv99LiveFrameTuning? = null,
        cameraSessionGeneration: Long,
        canonicalSensorTimestampNs: Long,
        recordingPath: Boolean
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vulkanRuntime?.submitCameraImage(
                image = image,
                presentationTimeNs = presentationTimeNs,
                niv99Tuning = niv99Tuning,
                cameraSessionGeneration = cameraSessionGeneration,
                canonicalSensorTimestampNs = canonicalSensorTimestampNs,
                recordingPath = recordingPath
            ) == true
        } else {
            false
        }
    }

    private fun strictNoQualityDowngradeActive(): Boolean =
        HighEndQualityPolicy.isHighEndQualityActive() && runtimeRecordingHint

    private fun normalizeImageSensorTimestamp(rawTimestampNs: Long): Long {
        val monotonicBeforeNs = System.nanoTime()
        val boottimeNowNs = SystemClock.elapsedRealtimeNanos()
        val monotonicAfterNs = System.nanoTime()
        return sensorTimestampTimebase.normalizeToReference(
            timestampNs = rawTimestampNs,
            monotonicNowNs = midpoint(monotonicBeforeNs, monotonicAfterNs),
            boottimeNowNs = boottimeNowNs
        )
    }

    private fun midpoint(first: Long, second: Long): Long =
        first + (second - first) / 2L

    private fun resolveLumaSnapshotDimensions(width: Int, height: Int): LumaSnapshotDimensions =
        FrameLumaSnapshotPolicy.resolveDimensions(
            sourceWidth = width,
            sourceHeight = height,
            targetLongEdge = resolveLumaSnapshotLongEdge()
        )

    private fun resolveLumaSnapshotLongEdge(): Int = resolveStatsLongEdge().coerceAtLeast(1)

    private fun snapshotLumaPlane(image: Image, timestampNs: Long): LumaPlaneSnapshot? {
        val p010 = Yuv420ImageTools.isP010(image)
        if ((!p010 && image.format != ImageFormat.YUV_420_888) || image.planes.isEmpty()) return null
        val plane = image.planes[0]
        val rawBuffer = plane.buffer.duplicate().slice().order(ByteOrder.LITTLE_ENDIAN)
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
            val scaleMap = planeScaleMap(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                targetWidth = width,
                targetHeight = height,
                sourceRowStride = plane.rowStride,
                sourcePixelStride = sourcePixelStride
            )
            val sourceRows = scaleMap.sourceRows
            val sourceColumns = scaleMap.sourceColumns
            copyScaledAnalysisLuma(
                source = rawBuffer,
                sourceLimit = rawLimit,
                destination = compact,
                targetWidth = width,
                targetHeight = height,
                sourceRows = sourceRows,
                sourceColumns = sourceColumns,
                p010 = p010
            )
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
        val word = buffer.getShort(index).toInt() and 0xFFFF
        val code10 = (word ushr 6).coerceIn(0, 1023)
        return ((code10 + 2) ushr 2).coerceIn(0, 255).toByte()
    }

    private fun copyScaledAnalysisLuma(
        source: ByteBuffer,
        sourceLimit: Int,
        destination: ByteArray,
        targetWidth: Int,
        targetHeight: Int,
        sourceRows: IntArray,
        sourceColumns: IntArray,
        p010: Boolean,
        allowNative: Boolean = true
    ) {
        if (
            p010 && allowNative &&
            NativeAnalysisIngress.copyScaledP010Luma(
                source = source,
                sourceLimit = sourceLimit,
                sourceRows = sourceRows,
                sourceColumns = sourceColumns,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                destination = destination
            )
        ) {
            return
        }
        var destinationIndex = 0
        if (p010) {
            for (targetY in 0 until targetHeight) {
                val sourceRow = sourceRows[targetY]
                for (targetX in 0 until targetWidth) {
                    val sourceIndex = sourceRow + sourceColumns[targetX]
                    destination[destinationIndex++] =
                        if (sourceIndex >= 0 && sourceIndex + 1 < sourceLimit) {
                            val word = source.getShort(sourceIndex).toInt() and 0xFFFF
                            val code10 = (word ushr 6).coerceIn(0, 1023)
                            ((code10 + 2) ushr 2).coerceIn(0, 255).toByte()
                        } else {
                            0
                        }
                }
            }
        } else {
            for (targetY in 0 until targetHeight) {
                val sourceRow = sourceRows[targetY]
                for (targetX in 0 until targetWidth) {
                    val sourceIndex = sourceRow + sourceColumns[targetX]
                    destination[destinationIndex++] = if (sourceIndex in 0 until sourceLimit) {
                        source.get(sourceIndex)
                    } else {
                        0
                    }
                }
            }
        }
    }

    private fun prewarmRealtimeAnalysisKernels() {
        val sourceWidth = 1280
        val sourceHeight = 960
        val targetWidth = 320
        val targetHeight = 240
        val sourcePixelStride = 2
        val sourceRowStride = sourceWidth * sourcePixelStride
        val source = ByteBuffer.allocateDirect(sourceRowStride * sourceHeight)
            .order(ByteOrder.LITTLE_ENDIAN)
        val destination = ByteArray(targetWidth * targetHeight)
        val map = planeScaleMap(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            sourceRowStride = sourceRowStride,
            sourcePixelStride = sourcePixelStride
        )
        var sampleOrdinal = 0
        for (targetY in 0 until targetHeight) {
            val sourceRow = map.sourceRows[targetY]
            for (targetX in 0 until targetWidth) {
                val sourceIndex = sourceRow + map.sourceColumns[targetX]
                val code10 = (sampleOrdinal * 67 + targetY * 23 + targetX * 11) and 0x3FF
                source.putShort(sourceIndex, (code10 shl 6).toShort())
                sampleOrdinal++
            }
        }
        copyScaledAnalysisLuma(
            source = source,
            sourceLimit = source.limit(),
            destination = destination,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            sourceRows = map.sourceRows,
            sourceColumns = map.sourceColumns,
            p010 = true
        )
        val referenceLuma = ByteArray(destination.size)
        copyScaledAnalysisLuma(
            source = source,
            sourceLimit = source.limit(),
            destination = referenceLuma,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            sourceRows = map.sourceRows,
            sourceColumns = map.sourceColumns,
            p010 = true,
            allowNative = false
        )
        if (!destination.contentEquals(referenceLuma)) {
            NativeAnalysisIngress.disable()
            System.arraycopy(referenceLuma, 0, destination, 0, destination.size)
        }
        val chromaChecksum = Yuv420ImageTools.prewarmScaledP010ChromaKernel(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
        var stats = FrameAnalysisMath.computeSceneStats(
            targetWidth,
            targetHeight,
            destination,
            targetWidth
        )
        val referenceStats = FrameAnalysisMath.computeSceneStats(
            targetWidth,
            targetHeight,
            destination,
            targetWidth,
            allowNative = false
        )
        if (
            !stats.histogram.contentEquals(referenceStats.histogram) ||
            stats.avgLuma.toRawBits() != referenceStats.avgLuma.toRawBits()
        ) {
            NativeAnalysisIngress.disable()
            stats = referenceStats
        }
        val chromaSize = (targetWidth / 2) * (targetHeight / 2)
        val frame = RawYuvFrame(
            width = targetWidth,
            height = targetHeight,
            yBytes = destination,
            uBytes = ByteArray(chromaSize) { 128.toByte() },
            vBytes = ByteArray(chromaSize) { 128.toByte() },
            yRowStride = targetWidth,
            yPixelStride = 1,
            uRowStride = targetWidth / 2,
            vRowStride = targetWidth / 2,
            uPixelStride = 1,
            vPixelStride = 1,
            timestamp = 0L,
            bitDepth = 8
        )
        val warmDecisionEngine = SceneDecisionEngine(
            strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        )
        val nativePerceptual = NativeAnalysisIngress.analyzePerceptualLuma(
            destination,
            targetWidth,
            targetHeight
        )
        val referencePerceptual = PerceptualSignalMath.analyzeLuma(
            destination,
            targetWidth,
            targetHeight
        )
        if (nativePerceptual != null && nativePerceptual != referencePerceptual) {
            NativeAnalysisIngress.disable()
        }
        var checksum = chromaChecksum xor stats.avgLuma.toRawBits()
        repeat(2) { pass ->
            val analysis = warmDecisionEngine.analyze(
                frame,
                stats.histogram,
                stats.avgLuma,
                if (pass == 0) null else destination
            )
            checksum = checksum xor analysis.hashCode()
            checksum = checksum xor warmDecisionEngine.buildCaptureRecommendation(analysis).hashCode()
        }
        pipelineWarmupChecksum = checksum xor destination[0].toInt()
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
            val scaleMap = planeScaleMap(
                sourceWidth = frame.width,
                sourceHeight = frame.height,
                targetWidth = target.width,
                targetHeight = target.height,
                sourceRowStride = frame.yRowStride,
                sourcePixelStride = yPixelStride
            )
            for (targetY in 0 until target.height) {
                val sourceRow = scaleMap.sourceRows[targetY]
                for (targetX in 0 until target.width) {
                    val srcIndex = sourceRow + scaleMap.sourceColumns[targetX]
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
     * Scene-stats-only sibling of [snapshotRawYuvFrame]: samples only the bounded chroma proxy via
     * [Yuv420ImageTools.snapshotScaledChromaOnly] and leaves yBytes empty. [snapshotAnalysisRawFrame] never
     * reads source.yBytes — the analysis frame's luma always comes from the downscaled LumaPlaneSnapshot —
     * so skipping all full-resolution plane copies yields a byte-identical analysis frame with much
     * lower memory bandwidth and allocation pressure. Failure/empty-chroma semantics match
     * [snapshotRawYuvFrame] exactly (null -> luma-only fallback in the caller).
     */
    private fun snapshotAnalysisChromaSource(
        image: Image,
        timestampNs: Long,
        targetWidth: Int,
        targetHeight: Int
    ): RawYuvFrame? {
        if (!Yuv420ImageTools.isSupported(image)) return null
        val chromaBytes = ((targetWidth + 1) / 2) * ((targetHeight + 1) / 2)
        val pooledU = byteArrayPool.acquire(chromaBytes)
        val pooledV = byteArrayPool.acquire(chromaBytes)
        val snapshot = runCatching {
            Yuv420ImageTools.snapshotScaledChromaOnly(
                image = image,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                normalizeP010ToEightBit = true,
                uDestination = pooledU,
                vDestination = pooledV
            )
        }
            .getOrElse { throwable ->
                byteArrayPool.release(pooledU)
                byteArrayPool.release(pooledV)
                Log.w("FramePipeline", "Failed to snapshot YUV chroma for analysis copy path", throwable)
                return null
            }
        if (snapshot.uBytes !== pooledU) byteArrayPool.release(pooledU)
        if (snapshot.vBytes !== pooledV) byteArrayPool.release(pooledV)
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

    private fun planeScaleMap(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        sourceRowStride: Int,
        sourcePixelStride: Int
    ): PlaneScaleMap {
        val key = PlaneScaleMapKey(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            sourceRowStride = sourceRowStride,
            sourcePixelStride = sourcePixelStride
        )
        synchronized(planeScaleMapLock) {
            planeScaleMaps[key]?.let { return it }
        }
        val created = PlaneScaleMap(
            sourceRows = IntArray(targetHeight) { targetY ->
                val sourceY = ((targetY.toFloat() / targetHeight.toFloat()) * sourceHeight)
                    .toInt()
                    .coerceIn(0, sourceHeight - 1)
                sourceY * sourceRowStride
            },
            sourceColumns = IntArray(targetWidth) { targetX ->
                val sourceX = ((targetX.toFloat() / targetWidth.toFloat()) * sourceWidth)
                    .toInt()
                    .coerceIn(0, sourceWidth - 1)
                sourceX * sourcePixelStride
            }
        )
        return synchronized(planeScaleMapLock) {
            planeScaleMaps[key]?.let { return it }
            if (planeScaleMaps.size >= 8) {
                val eldest = planeScaleMaps.entries.iterator()
                if (eldest.hasNext()) {
                    eldest.next()
                    eldest.remove()
                }
            }
            planeScaleMaps[key] = created
            created
        }
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
        val source = snapshotAnalysisChromaSource(
            image = image,
            timestampNs = timestampNs,
            targetWidth = lumaSnapshot.width,
            targetHeight = lumaSnapshot.height
        )
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
        // snapshotScaledChromaOnly already produced this exact compact planar layout. Reuse those
        // immutable arrays directly instead of re-scaling every sample through a second nested loop.
        // P010 was normalized with the identical ((code10 + 2) >> 2) expression at source read time.
        if (source.bitDepth == 8 &&
            source.width == lumaSnapshot.width && source.height == lumaSnapshot.height &&
            source.uPixelStride == 1 && source.vPixelStride == 1 &&
            source.uRowStride == chromaRowStride && source.vRowStride == chromaRowStride
        ) {
            val u = source.uBytes
            val v = source.vBytes
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
            maxStatsLongEdge = resolveStatsLongEdge()
        )
    }

    private fun resolveRuntimeTier(): HighEndRuntimeTier {
        return HighEndQualityPolicy.runtimeTier(
            previewFps = runtimeAnalysisFpsHint,
            recording = runtimeRecordingHint,
            enabledOverride = HighEndQualityPolicy.enabled
        )
    }

    private fun strictHighEndPipelineEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun sceneAnalysisCadencePlan(): SceneAnalysisCadencePlan =
        SceneAnalysisCadencePolicy.resolve(
            previewFps = runtimeAnalysisFpsHint,
            recording = runtimeRecordingHint
        )

    private fun shouldCaptureLumaSnapshot(
        frameSequence: Long,
        strideMultiplier: Long
    ): Boolean {
        if (strictHighEndPipelineEnabled()) {
            val minStride = resolveSceneStatsStride(strideMultiplier)
            return frameSequence == 1L ||
                frameSequence % minStride == 0L ||
                _sceneAnalysis.value == null ||
                _captureRecommendation.value == null
        }
        return frameSequence == 1L || frameSequence % resolveSceneStatsStride(strideMultiplier) == 0L
    }

    private fun resolveSceneStatsStride(strideMultiplier: Long): Long {
        if (strictHighEndPipelineEnabled()) {
            val plan = sceneAnalysisCadencePlan()
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
            return sceneAnalysisCadencePlan().statsLongEdge
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
        // FramePipeline scene/AI analysis thread sits one notch lower at DISPLAY so analysis never
        // starves the record path. JVM priority alone (below) is largely
        // ignored by CFS — this is the part that actually reprioritises the thread. No pixel change.
        val recordCritical = name.contains("HardwareImport")
        val backgroundAiIngress = name.contains("AIIngress") || name.contains("Warmup")
        val body = Runnable {
            when {
                recordCritical -> HotPathScheduling.applyUrgentDisplay()
                backgroundAiIngress -> runCatching {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                }
                else -> HotPathScheduling.applyDisplay()
            }
            runnable.run()
        }
        Thread(body, name).apply {
            // HIGH-END UPGRADE: Elevated priority for zero-lag 8K analysis
            priority = when {
                backgroundAiIngress -> Thread.NORM_PRIORITY
                strictHighEndPipelineEnabled() -> Thread.MAX_PRIORITY
                else -> Thread.NORM_PRIORITY + 2
            }
            isDaemon = true
        }
    }

    private fun recordRawYuvImageAcquisitionException(stage: String, failure: Exception) {
        rawYuvImageAcquisitionExceptionCounter.incrementAndGet()
        rawYuvImageFirstAcquisitionException.compareAndSet(
            "",
            "$stage:${failure.javaClass.name}:${failure.message.orEmpty()}"
        )
        Log.e("FramePipeline", "P010 ImageReader acquisition failed stage=$stage", failure)
    }
}

data class SceneStats(val histogram: IntArray, val avgLuma: Float)
fun interface RawYuvFrameSink {
    fun onRawYuvFrame(frame: RawYuvFrame)
}
data class RawYuvImageSinkTelemetry(
    val sourceImageCallbacks: Long,
    val ownershipTransferred: Long,
    val sinkDeclined: Long,
    val unsupportedImages: Long,
    val acquisitionExceptions: Long,
    val drainCapHits: Long,
    val firstAcquisitionException: String,
    val cadenceTargetFps: Int,
    val acquiredImages: Long,
    val invalidTimestamps: Long,
    val nonMonotonicTimestamps: Long,
    val missingCadenceFrames: Long,
    val maximumTimestampGapNs: Long,
    val firstTimestampNs: Long,
    val lastTimestampNs: Long,
    val firstGapPreviousTimestampNs: Long,
    val firstGapTimestampNs: Long,
    val firstGapMissingFrames: Long,
    val terminalBoundaryTimestampNs: Long,
    val terminalExcludedMissingFrames: Long,
    val timestampsAfterTerminal: Long
)
data class HardwareImportSourceIngressTelemetry(
    val sourceImageCount: Long,
    val sessionGeneration: Long,
    val sensorTimestampNs: Long,
    val receivedAtElapsedRealtimeNs: Long,
    val width: Int,
    val height: Int,
    val imageFormat: Int,
    /** Original vendor Image timestamp retained for clock-domain diagnostics. */
    val rawSensorTimestampNs: Long = sensorTimestampNs
) {
    val containsFrame: Boolean
        get() = sensorTimestampNs > 0L && width > 0 && height > 0 && imageFormat != ImageFormat.UNKNOWN

    companion object {
        val NONE = sessionBoundary(
            sourceImageCount = 0L,
            sessionGeneration = FrameIdentity.UNASSIGNED_SESSION_GENERATION
        )

        fun sessionBoundary(
            sourceImageCount: Long,
            sessionGeneration: Long
        ) = HardwareImportSourceIngressTelemetry(
            sourceImageCount = sourceImageCount.coerceAtLeast(0L),
            sessionGeneration = sessionGeneration,
            sensorTimestampNs = 0L,
            receivedAtElapsedRealtimeNs = 0L,
            width = 0,
            height = 0,
            imageFormat = ImageFormat.UNKNOWN
        )
    }
}
data class HardwareImportLifecycleTelemetry(
    val submissionTechnology: String,
    val listenerCallbacks: Long,
    val acquiredFrames: Long,
    val recordingRoutedAcquisitions: Long,
    val queueAcceptedFrames: Long,
    val queueDroppedFrames: Long,
    val queueRejectedFrames: Long,
    val vulkanSubmissionAttempts: Long,
    val vulkanSubmissionSuccesses: Long,
    val vulkanSubmissionFailures: Long,
    val closedFrames: Long,
    val firstSensorTimestampNs: Long,
    val lastSensorTimestampNs: Long,
    val firstRawSensorTimestampNs: Long,
    val lastRawSensorTimestampNs: Long,
    val firstReceivedElapsedRealtimeNs: Long,
    val lastReceivedElapsedRealtimeNs: Long,
    val currentQueueDepth: Int,
    val maximumQueueDepth: Int,
    val acquireFailures: Long,
    val acquireBackpressureSkips: Long,
    val preparedFrames: Long,
    val immediateSubmitFrames: Long,
    val overlappedSubmitFrames: Long,
    val currentPreparationWindow: Int,
    val maximumPreparationWindow: Int,
    val maximumPreparedQueueDepth: Int,
    val metadataWaits: Long,
    val metadataWaitTotalNs: Long,
    val metadataWaitMaximumNs: Long
) {
    fun signature(): String =
        "submissionTechnology=$submissionTechnology " +
            "listenerCallbacks=$listenerCallbacks acquired=$acquiredFrames " +
            "recordingRouted=$recordingRoutedAcquisitions " +
            "queueAccepted=$queueAcceptedFrames queueDropped=$queueDroppedFrames " +
            "queueRejected=$queueRejectedFrames queueDepth=$currentQueueDepth " +
            "queueMaxDepth=$maximumQueueDepth vulkanAttempts=$vulkanSubmissionAttempts " +
            "vulkanSuccesses=$vulkanSubmissionSuccesses vulkanFailures=$vulkanSubmissionFailures " +
            "closed=$closedFrames acquireFailures=$acquireFailures " +
            "acquireBackpressureSkips=$acquireBackpressureSkips " +
            "preparedFrames=$preparedFrames preparationInFlight=$currentPreparationWindow " +
            "immediateSubmitFrames=$immediateSubmitFrames " +
            "overlappedSubmitFrames=$overlappedSubmitFrames " +
            "preparationMaxInFlight=$maximumPreparationWindow " +
            "preparedQueueMaxDepth=$maximumPreparedQueueDepth " +
            "metadataWaits=$metadataWaits metadataWaitTotalNs=$metadataWaitTotalNs " +
            "metadataWaitMaximumNs=$metadataWaitMaximumNs " +
            "firstSensorTimestampNs=$firstSensorTimestampNs " +
            "lastSensorTimestampNs=$lastSensorTimestampNs " +
            "firstRawSensorTimestampNs=$firstRawSensorTimestampNs " +
            "lastRawSensorTimestampNs=$lastRawSensorTimestampNs " +
            "firstReceivedElapsedRealtimeNs=$firstReceivedElapsedRealtimeNs " +
            "lastReceivedElapsedRealtimeNs=$lastReceivedElapsedRealtimeNs"
}
data class RawYuvPixelAuthorityTelemetry(
    val exactCaptureIdentity: Boolean,
    val sessionGeneration: Long,
    val sensorTimestampNs: Long,
    val width: Int,
    val height: Int,
    val imageFormat: Int,
    val dataSpace: Int,
    val dataSpaceApiAvailable: Boolean
) {
    companion object {
        val NONE = RawYuvPixelAuthorityTelemetry(
            exactCaptureIdentity = false,
            sessionGeneration = FrameIdentity.UNASSIGNED_SESSION_GENERATION,
            sensorTimestampNs = 0L,
            width = 0,
            height = 0,
            imageFormat = ImageFormat.UNKNOWN,
            dataSpace = 0,
            dataSpaceApiAvailable = false
        )
    }
}
fun interface RawYuvImageSink {
    /**
     * Return true only when the sink takes ownership of [image] and will close it.
     */
    fun onRawYuvImage(image: Image, timestampNs: Long): Boolean
}
data class RawYuvFrame(
    override val width: Int,
    override val height: Int,
    override val yBytes: ByteArray,
    override val uBytes: ByteArray,
    override val vBytes: ByteArray,
    override val yRowStride: Int,
    override val yPixelStride: Int,
    override val uRowStride: Int,
    override val vRowStride: Int,
    override val uPixelStride: Int,
    override val vPixelStride: Int,
    override val timestamp: Long,
    override val bitDepth: Int = 8
) : Niv99YuvFrameView
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
