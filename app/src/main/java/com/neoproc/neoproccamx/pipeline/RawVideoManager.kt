package com.neoproc.neoproccamx.pipeline

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaScannerConnection
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.neoproc.neoproccamx.capture.RawDngMetadataContract
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.camera.RawSensorCalibrationPolicy
import com.neoproc.neoproccamx.camera.RawSensorReaderDepthPolicy
import com.neoproc.neoproccamx.camera.CameraSensorNoiseModelPolicy
import com.neoproc.neoproccamx.camera.SensorLinearNdrExposurePolicy
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.HotPathScheduling
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.AudioSrc
import com.neoproc.neoproccamx.recording.RawRecordingAudioSession
import com.neoproc.neoproccamx.recording.Npv10AudioSidecarPolicy
import com.neoproc.neoproccamx.recording.RawVideoContractPolicy
import com.neoproc.neoproccamx.recording.RecordingPreallocationMethod
import com.neoproc.neoproccamx.recording.RecordingPreallocationPolicy
import com.neoproc.neoproccamx.recording.StorageReservationHandoffBarrier
import com.neoproc.neoproccamx.recording.StorageReservationRequestPolicy
import com.neoproc.neoproccamx.core.RuntimeStageTelemetry
import com.neoproc.neoproccamx.camera.DynamicAcesIdtCalibration
import com.neoproc.neoproccamx.color.NeoGamutApexPipeline
import com.neoproc.neoproccamx.rendering.BayerPattern
import com.neoproc.neoproccamx.rendering.Camera2RawColorMetadataPolicy
import com.neoproc.neoproccamx.rendering.Camera2RawColorTransformSource
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import com.neoproc.neoproccamx.rendering.ProjectOwnedNdrColorProof
import com.neoproc.neoproccamx.rendering.RawBufferDescriptor
import com.neoproc.neoproccamx.rendering.RawLensShadingGridPolicy
import com.neoproc.neoproccamx.utils.OrientationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import android.media.Image
import kotlin.concurrent.withLock

/**
 * Serializes the final validity decision with publication. Lifecycle owners invalidate their state
 * under the same lock, so a retired RAW/NDR generation cannot pass a check and publish afterward.
 */
internal object SensorLinearExposureLifecyclePublisher {
    fun publishIfCurrent(
        lifecycleLock: ReentrantLock,
        isCurrent: () -> Boolean,
        publish: () -> Unit
    ): Boolean = lifecycleLock.withLock {
        if (!isCurrent()) {
            false
        } else {
            publish()
            true
        }
    }
}

/**
 * Immutable ownership proof for one idle RAW preview submission. Native completion may arrive after
 * capture ownership changes, so presentation must validate this exact join/session identity again.
 */
data class IdleRawPreviewSubmissionIdentity(
    val joinGeneration: Long,
    val presentationEpoch: Long,
    val sessionGeneration: Long,
    val sensorTimestampNs: Long
)

/**
 * RawVideoManager — Parallel RAW_SENSOR frame capture and NPRAW serialisation.
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * DESIGN: ZERO SESSION CONFLICT
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * CameraController already creates a rawReader (ImageReader for RAW_SENSOR)
 * and includes it as a target in the single CameraCaptureSession.
 * CameraController.onRawReady is the callback that fires when each RAW frame
 * arrives. RawVideoManager hooks into that callback — it does NOT create any
 * new ImageReader, CameraCaptureSession, or surface. It never touches the
 * camera device directly.
 *
 * Integration pattern:
 *
 *   val rawManager = RawVideoManager(context, camera)
 *   camera.onRawReady = { reader -> rawManager.onRawFrameAvailable(reader) }
 *   rawManager.startCapture()   // begin saving frames
 *   rawManager.stopCapture()    // stop + finalise sequence
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * PIPELINE
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *   Camera HAL → rawReader.onImageAvailable → onRawFrameAvailable()
 *                   │  [Camera bgHandler thread — minimal work]
 *                   │  acquireNextImage() while recording
 *                   │  offer to writeQueue (bounded, non-blocking)
 *                   ▼
 *              writeQueue (capacity=8)
 *                   │  [writeExecutor — single background thread]
 *                   ▼
 *              packed 12-bit NPRAW stream → disk
 *
 * The Camera HAL thread (bgHandler) only acquires and enqueues. All I/O
 * happens on the dedicated writeExecutor. If the queue is full (disk too slow)
 * frames are dropped with a warning — preview is never impacted.
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * OUTPUT
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *   /storage/emulated/0/Documents/CamX/NEO_RADIANCE_RAW_YYYYMMDD_HHmmss/
 *     stream.npraw
 *
 * NPRAW frames carry timestamp, dimensions, bit depth, black/white levels,
 * dynamic-range estimate, noise estimate, and a CRC for the packed payload.
 */
class RawVideoManager(
    private val context: Context,
    private val camera:  CameraController
) {
    private data class RawCaptureLocation(
        val directory: File,
        val relativePath: String?,
        val label: String
    )

    /** Exact sensor-timestamp pair handed to the latest-frame-wins RAW monitor worker. */
    private data class IdleRawPreviewFrame(
        val image: Image,
        val result: TotalCaptureResult,
        val joinGeneration: Long,
        val presentationEpoch: Long,
        val sessionGeneration: Long
    )

    /** Stable identity for one writer session; pause/resume never relabels its persisted frames. */
    private data class RawWriterSessionToken(
        val generation: Long,
        val queue: ArrayBlockingQueue<Image>
    )

    /** Exact queue/admission epoch sampled before Camera2 acquires a recording Image. */
    private data class RawCaptureIngressToken(
        val session: RawWriterSessionToken,
        val admissionGeneration: Long,
        val cameraSessionGeneration: Long,
        val retiredSensorTimestampFloorNs: Long
    )

    /** Two-phase ownership for one Camera2 marker-driven RAW resume attempt. */
    private class RawResumeAdmissionLease(
        val pausedResultIdentity: RawCaptureIngressToken,
        val cameraSessionGeneration: Long
    ) {
        val committedAdmission = AtomicReference<RawCaptureIngressToken?>(null)
    }

    /**
     * Immutable publication identity for one open sensor-linear NDR ingress generation.
     *
     * The object identity is intentional: generations describe the recording, while reference
     * identity proves that a Camera2 callback actually entered while that exact admission was
     * published. This closes the start/stop window in which a callback could otherwise observe a
     * newly incremented generation before admission opened and later be relabelled as current.
     */
    private class SensorLinearNdrRecordingIngressToken(
        val generation: Long,
        val sessionGeneration: Long,
        /**
         * Inclusive Camera2 sensor-timestamp boundary established on CameraBackground immediately
         * before this token is published. RAW Images at or below this floor belong to the already
         * completed preview epoch even when ImageReader delivers them after recording admission opens.
         */
        val cutoverTimestampFloorNs: Long
    )

    /** The exact queue drained by one writer Future; never inferred from the mutable current queue. */
    private data class RawWriteLoopHandle(
        val future: Future<*>,
        val session: RawWriterSessionToken
    )

    /**
     * Reference-counted ownership for a Camera2 Image shared by storage and the optional monitor tap.
     * The Image closes exactly once after every asynchronous reader has retired.
     */
    private class RawFrameImageLease(
        val image: Image,
        private val onFullyReleased: () -> Unit
    ) {
        private val references = AtomicInteger(1)

        fun retain(): Boolean {
            while (true) {
                val current = references.get()
                if (current <= 0) return false
                if (references.compareAndSet(current, current + 1)) return true
            }
        }

        fun release() {
            val remaining = references.decrementAndGet()
            check(remaining >= 0) { "RAW Image lease released more than it was retained" }
            if (remaining == 0) {
                try {
                    image.close()
                } finally {
                    onFullyReleased()
                }
            }
        }
    }

    private enum class RawFrameOfferResult {
        ACCEPTED,
        FULL,
        RETIRED
    }

    private data class RawFrameReplacementResult(
        val offerResult: RawFrameOfferResult,
        val evicted: Image?
    )

    /** Image ownership captured in Camera2 delivery order before its exact result callback arrives. */
    private data class SensorLinearNdrPendingImage(
        val sequence: Long,
        val image: Image,
        val ingressGeneration: Long,
        val presentationEpoch: Long
    )

    /** Exact, ordered RAW_SENSOR + metadata pair owned by the NDR recording domain. */
    private data class SensorLinearNdrRecordingFrame(
        val sequence: Long,
        val image: Image,
        val result: TotalCaptureResult,
        val ingressGeneration: Long,
        val presentationEpoch: Long,
        val sessionGeneration: Long,
        var preparedDescriptor: RawBufferDescriptor? = null,
        var exposureAnalyticsQueued: Boolean = false
    )

    private data class SensorLinearExposureAnalyticsWork(
        val snapshot: RawSensorExposureSampleSnapshot,
        val ingressGeneration: Long,
        val sessionGeneration: Long,
        val requiresNdrLifecycle: Boolean,
        val sensorSensitivityIso: Int,
        val exposureTimeNs: Long,
        val noiseS: Float,
        val noiseO: Float,
        val reportedExposureCompensationIndex: Int?,
        val measuredAtElapsedRealtimeMs: Long,
        val sensorTimestampNs: Long,
        val controlRevision: Long
    )

    /**
     * Tiny two-sided join for RAW-original metering. It owns only copied primitive sensor codes and
     * immutable CaptureResult metadata; the full-resolution Image always remains writer-owned.
     */
    private data class RawOriginalExposureMeterJoin(
        val sensorTimestampNs: Long,
        val captureGeneration: Long,
        val sessionGeneration: Long,
        val width: Int,
        val height: Int,
        val rowStrideBytes: Int,
        val bayerPattern: BayerPattern,
        val reservedAtElapsedRealtimeMs: Long,
        var controlRevision: Long = -1L,
        var result: TotalCaptureResult? = null,
        var codeSnapshot: RawSensorExposureCodeSnapshot? = null
    )

    /** Generation-scoped ownership used when draining buffers across stop/restart boundaries. */
    private data class SensorLinearNdrOwnedImage(
        val image: Image,
        val ingressGeneration: Long
    )

    /** Capture metadata identity retained alongside the exact sensor timestamp. */
    private data class TimestampedCaptureResult(
        val result: TotalCaptureResult,
        val presentationEpoch: Long,
        val sessionGeneration: Long,
        /** Non-null only when this result callback was owned by an immutable RAW writer session. */
        val rawWriterSession: RawWriterSessionToken? = null
    )

    private data class PreparedRawCaptureStorage(
        val location: RawCaptureLocation,
        val compressionMode: RawCompressionMode,
        val engine: AsyncDirectIOEngine,
        val streamUri: Uri?
    ) {
        fun isUsable(expectedMode: RawCompressionMode): Boolean =
            RawPreparedStoragePolicy.isReusable(
                expectedMode = expectedMode,
                preparedMode = compressionMode,
                writerOpen = engine.isOpen,
                preallocation = engine.preallocationResult
            )
    }

    companion object {
        private const val TAG              = "RawVideoManager"
        private const val QUEUE_CAPACITY   = 16    // Increased from 8 for better buffering
        private const val MAX_FRAME_RATE   = 60    // Increased from 24 to 60fps for high-end
        private const val CINEMA_FRAME_RATE = 24   // Cinema standard
        private const val HIGH_SPEED_FRAME_RATE = 120  // High-speed option
        private const val SLOW_RAW_WRITE_MS = 45L
        // Record-time live monitor (see tapRecordingPreview): while recording, an ALREADY-PERSISTED RAW
        // frame's bytes are copied out and debayered off-thread so the visible monitor stays LIVE and
        // WYSIWYG. The tap is throttled, decimated to half-res (~1/4 the debayer cost), and skipped whenever
        // the writer is even slightly behind — so the recorded RAW never loses a frame.
        private const val PREVIEW_TAP_MIN_INTERVAL_NS = 300_000_000L // ~3.3fps cap for the record-time monitor
        private const val PREVIEW_TAP_MAX_QUEUE_DEPTH  = 1           // only tap when the writer is caught up (recorder-first)
        private const val PREVIEW_TAP_DECIMATE         = 2           // half-res record-time monitor
        private const val UI_FRAME_COUNTER_STEP = 4
        private const val UI_DROP_COUNTER_STEP = 4
        private const val FRAME_LOG_SAMPLE_STEP = 30
        private const val DROP_LOG_THROTTLE_MS = 1_500L
        private const val RAW_RESUME_BOUNDARY_TIMEOUT_MS = 2_000L
        /** Metadata correctness outranks output: authoritative RAW files never borrow another frame. */
        private const val EXACT_CAPTURE_RESULT_WAIT_TIMEOUT_MS = 250L
        /** Bounds acquired RAW buffers while Image and CaptureResult callbacks arrive out of order. */
        private const val MAX_UNMATCHED_IDLE_RAW_IMAGES = 3
        private const val DEFAULT_RAW_READER_MAX_IMAGES = 16
        /** Storage-stage Camera2 Images retained by the contiguous zero-copy NPRAW path. */
        private const val RAW_BORROWED_STORAGE_CAPACITY = 4
        private const val MAX_SENSOR_LINEAR_NDR_RESULTS = 128
        private const val SENSOR_LINEAR_NDR_JOIN_DIAGNOSTIC_LIMIT = 16
        private const val MAX_RAW_ORIGINAL_EXPOSURE_METER_JOINS = 4
        private const val RAW_ORIGINAL_EXPOSURE_JOIN_MAX_AGE_MS = 1_000L
        private const val DESTROY_EXECUTOR_QUIESCE_TIMEOUT_MS = 500L
        private const val IDLE_STORAGE_HANDOFF_TIMEOUT_MS = 30_000L
        private const val CINEMA_DNG_DESCRIPTION = "NeoProCamX NeoRadiance RAW video frame"
        // Default NeoLog variant hint recorded in the develop-metadata sidecar; the offline
        // converter lets the user pick the final variant, defaulting from this.
        private const val DEFAULT_DEVELOP_LOG_VARIANT = "N-LOG-BASE"
        // MediaStore pending rows survive a SIGKILL/process death. Track live rows process-wide so a
        // replacement process can remove only abandoned 10 GiB reservations without touching a live
        // manager during an Activity/ViewModel recreation in the same process.
        private val pendingRawStreamRegistryLock = Any()
        private val livePendingRawStreamUris = mutableSetOf<String>()
        private var orphanPendingRawStreamsCleaned = false
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private val _state = MutableStateFlow<RawVideoState>(RawVideoState.Idle)
    val state: StateFlow<RawVideoState> = _state.asStateFlow()

    private val _frameCount = MutableStateFlow(0)
    val frameCount: StateFlow<Int> = _frameCount.asStateFlow()
    private val _droppedFrames = MutableStateFlow(0)
    val droppedFrames: StateFlow<Int> = _droppedFrames.asStateFlow()
    var compressionMode: RawCompressionMode = RawCompressionMode.HIGH_COMPRESSION

    // Set by the ViewModel at RAW record start: true when SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION is
    // actually engaged for this clip (un-binned max-res capture), false for the full active-array
    // path. Recorded once per clip in the develop.meta sidecar so the converter/diagnostics can tell
    // how the frames were captured.
    @Volatile
    var sensorPixelModeMaximumResolutionUsed: Boolean = false
    @Volatile
    var orientationHintDegrees: Int = 0
    /** Successful RAW master persistence, emitted only after the storage backend commits the frame. */
    @Volatile
    var persistedFrameCommitListener: ((sensorTimestampNs: Long) -> Unit)? = null
    /** One-shot notification that asks the outer recording owner to finalize a gap-free RAW prefix. */
    @Volatile
    internal var continuityBoundaryStopListener: ((RawRecordingContinuityBoundary) -> Unit)? = null

    // ── Internal ──────────────────────────────────────────────────────────────
    // Frame-accept gate: onRawFrameAvailable only enqueues while this is true. Opened LAST in
    // startCapture() — after writeQueue is recreated and the session is fully set up — and cleared
    // first in stopCapture(), so a frame can never be offered to a stale/undrained queue.
    private val isCapturing  = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    /** Linearizes final Camera2 queue offers with stop/pause/restart admission changes. */
    private val rawWriterAdmissionLock = ReentrantLock()
    private var rawWriterSessionGeneration = 0L
    @Volatile private var rawWriterAdmissionGeneration = 0L
    /** Published last on open/resume and cleared first on pause/stop/failure. */
    private val rawCaptureIngressAdmission =
        AtomicReference<RawCaptureIngressToken?>(null)
    /**
     * Result-entry identity is intentionally independent from frame admission. Stop/pause closes
     * [rawCaptureIngressAdmission] immediately, while this identity remains published until every
     * already-owned Image has either consumed its exact metadata or retired. A callback that entered
     * an old generation can therefore complete only its exact pending timestamp and can never be
     * reinterpreted as a result for a restarted recording or for idle preview.
     */
    private val rawCaptureResultIngressIdentity =
        AtomicReference<RawCaptureIngressToken?>(null)
    /** Non-null only between resume validation and its exact Camera2 marker terminal result. */
    private val rawResumeAdmissionLease = AtomicReference<RawResumeAdmissionLease?>(null)
    /** First accepted sensor timestamp for the current writer clip; pause/resume does not reset it. */
    private val rawCaptureFirstAcceptedSensorTimestampNs = AtomicLong(0L)
    /** Monotonic callback barrier retained across writer restarts within one Camera2 session. */
    private var rawRetiredTimestampSessionGeneration = -1L
    private var rawRetiredSensorTimestampFloorNs = 0L
    /** Serializes RAW start/stop/destroy while NDR exclusion is claimed under its lifecycle lock. */
    private val rawCaptureLifecycleLock = ReentrantLock()
    // Start/stop re-entrancy guard. Held for the ENTIRE capture lifecycle (including the brief setup
    // window before [isCapturing] opens) so a second startCapture()/double stopCapture() is rejected
    // even while the frame-accept gate is still closed.
    private val captureLifecycleActive = AtomicBoolean(false)
    private val captureAudio = RawRecordingAudioSession()
    private var audioPreparedForNextCapture = false
    private var captureAudioPublishedUri: Uri? = null
    @Volatile var recordingAudioFailureListener: ((Throwable) -> Unit)? = null
    private val framesWritten = AtomicInteger(0)
    private val compressedFrameSequence = AtomicInteger(0)
    /** Distinct dequeued Camera2 Images still owned by any writer/preview lease. */
    private val ownedWriterImages = AtomicInteger(0)
    private val activeWrites = AtomicInteger(0)
    private val droppedFrameCount = AtomicInteger(0)
    private val admittedRawFrameCount = AtomicLong(0L)
    private val continuityBoundaryStopRequested = AtomicBoolean(false)
    @Volatile private var rawRecordingStorageBudget: RawRecordingStorageBudget? = null
    private val queueHighWatermark = AtomicInteger(0)
    private val captureStartRequestElapsedNs = AtomicLong(0L)
    private val captureGateOpenElapsedNs = AtomicLong(0L)
    /** Immutable selected cadence for one writer generation; sidecars never read mutable UI state. */
    private val activeCaptureFps = AtomicInteger(0)
    /** During Camera2 RAW request warm-up, drain buffers without invoking the costly idle monitor. */
    private val captureCadenceWarmupActive = AtomicBoolean(false)
    private val firstPersistedFrameReported = AtomicBoolean(false)
    private val firstPersistedSensorTimestampNs = AtomicLong(0L)
    private val lastPersistedSensorTimestampNs = AtomicLong(0L)
    private val persistedFrameCommitCallbackFailures = AtomicLong(0L)
    private val storageWriteThroughputMeter = RollingStorageWriteThroughputMeter()
    // Write the per-clip RAW develop-metadata sidecar exactly once (on the first captured frame).
    private val developSidecarWritten = AtomicBoolean(false)
    @Volatile private var lastDroppedLogElapsedMs = 0L
    private var idleRawFramesDrained = 0L
    private var lastPublishedFrameCount = 0
    private var lastPublishedDroppedCount = 0

    // Bounded queue: Image objects pending write to disk. @Volatile so the writeLoop thread and the
    // camera bgHandler thread always observe the fresh reference recreated in startCapture().
    @Volatile private var writeQueue = ArrayBlockingQueue<Image>(QUEUE_CAPACITY)

    // Single dedicated write thread — all I/O here, never on Camera thread.
    // This is the recorder's heaviest per-frame CPU stage (extract + NPRAW encode + write) and
    // previously ran BELOW normal (NORM_PRIORITY-1), so ordinary background work could preempt it and
    // stall the RAW cadence. Apply a real URGENT_DISPLAY Process nice from inside the run-body (the
    // JVM priority below is largely ignored by CFS). previewTapExecutor is intentionally NOT elevated
    // so the live monitor stays subordinate to the recorder it feeds off. Scheduling only — bit-identical.
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({ HotPathScheduling.applyUrgentDisplay(); r.run() }, "RawVideoWriter")
            .also { it.priority = Thread.NORM_PRIORITY - 1 }
    }
    private val storagePreparationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RawStoragePreparation").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }
    private val storagePreparationLock = Any()
    private val storagePreparationGeneration = AtomicLong(0L)
    /** Closes every new admission while already-owned work is still allowed to retire safely. */
    private val destroyRequested = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    /** Set only after every executor and storage owner has completed destruction, including retries. */
    private val destroyFinalized = AtomicBoolean(false)
    @Volatile private var preparedStorage: PreparedRawCaptureStorage? = null
    @Volatile private var storagePreparationFuture: CompletableFuture<PreparedRawCaptureStorage?>? = null
    private var idleStoragePreparationBlockedByCompetingPipeline = false

    init {
        // Run off-main at app startup. The same single-thread executor serializes cleanup ahead of
        // reservation creation, so stale physical allocations never compete with the next fallocate.
        storagePreparationExecutor.execute(::cleanupOrphanedPendingRawStreams)
    }

    // #9/#10 fix: handle to the in-flight writeLoop task so stop/startCapture can guarantee it has fully
    // EXITED before the engine is closed or writeQueue is recreated. A stop-drain timeout otherwise leaves
    // the loop running against the old queue / a closed engine.
    @Volatile private var writeLoopHandle: RawWriteLoopHandle? = null
    // Bound (ms) for awaiting the writeLoop to exit during quiesce. The main drain wait already gave the loop
    // time to finish naturally, so on the normal path it exits within one poll cycle.
    private val writeLoopExitTimeoutMs = 2_000L

    // Latest metadata is a monitor-only visual fallback; persisted RAW outputs require exact joins.
    @Volatile private var latestResult: TotalCaptureResult? = null
    private val resultLock = ReentrantLock()
    private val exactCaptureResultWaiter = ExactMetadataWaiter(resultLock)
    // Idle RAW preview uses an event-driven exact timestamp join. Images never poll or sleep waiting
    // for metadata: either callback can complete the pair. The three-image bound preserves reader
    // headroom, and every replacement/eviction is closed immediately.
    private val unmatchedIdleRawImages = LinkedHashMap<Long, Image>()
    private var idleRawJoinGeneration = 0L
    private val previewPresentationGate = RawPreviewPresentationEpochGate()
    // ARCHITECTURAL FIX: 128-entry buffer provides ~2.1s metadata safety window at 60fps.
    // Prevents DNG metadata fallback during sustained I/O backpressure on UFS 2.x storage.
    private val recentResultsByTimestamp =
        object : LinkedHashMap<Long, TimestampedCaptureResult>(144, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Long, TimestampedCaptureResult>?
            ): Boolean = size > 128 && eldest != null &&
                pendingRawCaptureResultsByTimestamp[eldest.key] == null
        }
    /** Exact recording Images that may still consume a Camera2 result after frame admission closes. */
    private val pendingRawCaptureResultsByTimestamp =
        LinkedHashMap<Long, RawCaptureIngressToken>(QUEUE_CAPACITY + 2, 0.75f, true)
    private val rawOriginalExposureMeterJoins =
        LinkedHashMap<Long, RawOriginalExposureMeterJoin>(8, 0.75f, true)

    // Output directory for this recording session
    private var sessionDir: File? = null
    private var sessionRelativePath: String? = null
    private var sessionLocationLabel: String? = null
    
    // Asynchronous O_DIRECT engine for sustained RAW stream throughput.
    // #10 fix: @Volatile so the writeLoop thread always observes the fresh reference (assigned before the
    // loop is submitted) and the null on teardown — never a stale/closed engine mid-drain that would misroute
    // tail frames to orphan files.
    @Volatile private var directIOEngine: AsyncDirectIOEngine? = null
    private var compressedStreamUri: Uri? = null

    // Optional preview processor (e.g. Vulkan Cinema-Debayer) for RAW frames
    // when NOT capturing. If set, the processor receives the Image and its
    // descriptor; returning true means the processor took ownership and will
    // close the Image (e.g. via hardwareBuffer.close()).
    @Volatile
    var rawPreviewProcessor:
        ((Image, RawBufferDescriptor, IdleRawPreviewSubmissionIdentity) -> Boolean)? = null
    private val rawPreviewColorMetadataStabilizer = RawPreviewColorMetadataStabilizer()
    private val rawPreviewColorDiagnosticRateLimiter = RawPreviewColorDiagnosticRateLimiter()
    @Volatile private var lastRawPreviewColorMetadataSource: RawPreviewColorMetadataSource? = null
    @Volatile private var lastRawColorTransformSource: Camera2RawColorTransformSource? = null
    @Volatile private var lastRawSensorCalibrationSignature: String? = null

    /**
     * Recorder-owned processor and completion poller. Unlike [rawPreviewProcessor], these callbacks
     * are never fed from a latest-frame slot and their preview callback runs only after encoder submit.
     */
    @Volatile
    internal var sensorLinearNdrRecordingProcessor:
        ((Image, RawBufferDescriptor, Long, SensorLinearNdrRecordingFrameIdentity) ->
            SensorLinearNdrRecordingSubmission)? = null
    @Volatile
    internal var sensorLinearNdrRecordingCompletionPoller:
        (() -> SensorLinearNdrRecordingCompletion)? = null
    private val sensorLinearNdrRecordingColorMetadataStabilizer = RawPreviewColorMetadataStabilizer()
    @Volatile
    private var lastSensorLinearNdrRecordingColorMetadataSource: RawPreviewColorMetadataSource? = null

    private val sensorLinearNdrRecordingLifecycleActive = AtomicBoolean(false)
    private val sensorLinearNdrRecordingLifecycleLock = ReentrantLock()
    private val sensorLinearNdrRecordingAccepting = AtomicBoolean(false)
    /** True only for fail/fast-stop: queued work is discarded but an accepted native tail is polled. */
    private val sensorLinearNdrRecordingDiscardRequested = AtomicBoolean(false)
    private val sensorLinearNdrRecordingFailureReported = AtomicBoolean(false)
    private val sensorLinearNdrRecordingGeneration = AtomicLong(0L)
    private val sensorLinearNdrRecordingSessionGeneration = AtomicLong(-1L)
    /** Exact callback-entry identity; published last and cleared before admission teardown. */
    private val sensorLinearNdrRecordingIngressAdmission =
        AtomicReference<SensorLinearNdrRecordingIngressToken?>(null)
    private val sensorLinearNdrRecordingOwnedFrames = AtomicInteger(0)
    private val sensorLinearNdrRecordingOwnedFrameLimit = AtomicInteger(1)
    private val sensorLinearNdrRecordingOwnedHighWatermark = AtomicInteger(0)
    private val sensorLinearNdrRecordingCapacityWaits = AtomicLong(0L)
    private val sensorLinearNdrRecordingCapacityGate = SensorLinearNdrRecordingCapacityGate()
    private val sensorLinearNdrExactImageOwnership = SensorLinearNdrExactImageOwnership()
    private val sensorLinearNdrRecordingCutoverRetiredFrames = AtomicInteger(0)
    private val sensorLinearNdrRecordingWorkerScheduled = AtomicBoolean(false)
    /** Condition-backed state avoids polling and prevents a barrier overtaking a delayed worker. */
    private val sensorLinearNdrRecordingWorkerStateLock = ReentrantLock()
    private val sensorLinearNdrRecordingWorkerUnscheduled =
        sensorLinearNdrRecordingWorkerStateLock.newCondition()
    private val sensorLinearNdrRecordingNativePending = AtomicBoolean(false)
    private val sensorLinearNdrRecordingRetryWindow =
        AtomicReference<SensorLinearNdrRecordingRetryWindow?>(null)
    private val sensorLinearNdrRecordingRetryFrame =
        AtomicReference<SensorLinearNdrRecordingFrame?>(null)
    /**
     * Explicit split-phase processor lease. The slow GPU/EGL call runs outside the lifecycle lock so
     * Camera2 RAW/result ingress cannot be starved, while stop/failure still sees a concrete owner and
     * cannot retire encoder resources until the recorder worker publishes its terminal outcome.
     */
    private val sensorLinearNdrRecordingProcessorInFlight =
        AtomicReference<SensorLinearNdrRecordingFrame?>(null)
    private val sensorLinearNdrRecordingProcessorInFlightSinceNs = AtomicLong(0L)
    private val sensorLinearNdrRecordingProcessorMaxDurationNs = AtomicLong(0L)
    private val sensorLinearNdrRecordingProcessorSlowSubmissions = AtomicLong(0L)
    @Volatile
    private var sensorLinearNdrRecordingTargetFps = 30
    @Volatile
    private var sensorLinearNdrRecordingFailureListener:
        ((SensorLinearNdrRecordingFailure) -> Unit)? = null
    @Volatile
    private var sensorLinearNdrRecordingStopLatch: CountDownLatch? = null
    @Volatile
    private var sensorLinearNdrRecordingQueue =
        ArrayBlockingQueue<SensorLinearNdrRecordingFrame>(1)
    private val sensorLinearNdrRecordingResults =
        LinkedHashMap<Long, TimestampedCaptureResult>(144, 0.75f, true)
    private val sensorLinearNdrPendingImages = LinkedHashMap<Long, SensorLinearNdrPendingImage>()
    private val sensorLinearNdrReadyBySequence = TreeMap<Long, SensorLinearNdrRecordingFrame>()
    private val sensorLinearNdrImageJoinDiagnosticCount = AtomicInteger(0)
    private val sensorLinearNdrResultJoinDiagnosticCount = AtomicInteger(0)
    private val sensorLinearNdrRecordingArmedElapsedNs = AtomicLong(0L)
    private var sensorLinearNdrNextImageSequence = 1L
    private var sensorLinearNdrNextQueueSequence = 1L
    /** Guarded by [sensorLinearNdrRecordingLifecycleLock]; updated by Camera2 result ingress. */
    private var latestCamera2ResultSessionGeneration = -1L
    /** Guarded by [sensorLinearNdrRecordingLifecycleLock]; SENSOR_TIMESTAMP is monotonic per session. */
    private var latestCamera2ResultTimestampNs = 0L
    private val sensorLinearNdrRecordingExecutor =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(
                { HotPathScheduling.applyUrgentDisplay(); runnable.run() },
                "SensorLinearNdrRecorder"
            ).apply {
                priority = Thread.NORM_PRIORITY
                isDaemon = true
            }
        }
    private val sensorLinearExposureAnalyticsPending =
        AtomicReference<SensorLinearExposureAnalyticsWork?>(null)
    private val sensorLinearExposureAnalyticsScheduled = AtomicBoolean(false)
    private val sensorLinearExposureAnalyticsLastQueuedElapsedMs = AtomicLong(0L)
    /** Invalidates RAW-original meter work across stop/start even when Camera2 keeps one session. */
    private val rawOriginalExposureAnalyticsGeneration = AtomicLong(0L)
    private val rawOriginalExposureMeterLastReservedElapsedMs = AtomicLong(0L)
    private val sensorLinearExposureAnalyticsExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SensorLinearExposureAnalytics").apply {
                priority = (Thread.NORM_PRIORITY - 1).coerceAtLeast(Thread.MIN_PRIORITY)
                isDaemon = true
            }
        }

    // Idle sensor-linear preview must never run its full-resolution demosaic/readback on CameraBackground.
    // The exact timestamp join holds at most three unmatched Images; after pairing, one worker owns the
    // in-flight Image and one atomic slot retains only the newest waiting pair. Replacements close at once.
    private val idleRawPreviewPending = AtomicReference<IdleRawPreviewFrame?>(null)
    private val idleRawPreviewWorkerScheduled = AtomicBoolean(false)
    private val sensorLinearNdrRecordingStartupGate =
        SensorLinearNdrRecordingStartupGate()
    private val idleRawPreviewExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RawPreviewCompute").apply {
            priority = Thread.NORM_PRIORITY
            isDaemon = true
        }
    }

    // Frame rate throttle: timestamp of last accepted frame (nanoseconds)
    @Volatile private var lastFrameNs = 0L
    @Volatile private var minFrameIntervalNs = 1_000_000_000L / MAX_FRAME_RATE
    private var shouldEvictOldestOnOverflow = false
    private var resolveWriterPollTimeoutMs = 100L

    // Record-time live-monitor tap: copies an ALREADY-PERSISTED RAW frame's bytes into [previewTapBuffer]
    // and debayers the COPY on [previewTapExecutor] (single-slot via [previewTapBusy]) so the on-screen
    // monitor stays LIVE while recording WITHOUT ever holding a scarce RAW reader buffer away from the
    // recorder. The processor is installed by the ViewModel (submitRawSensorBytes → present). See
    // tapRecordingPreview.
    @Volatile var recordingPreviewBytesProcessor: ((java.nio.ByteBuffer, RawBufferDescriptor, Int, Int) -> Unit)? = null
    private val previewTapBusy = AtomicBoolean(false)
    @Volatile private var lastPreviewTapNs = 0L
    @Volatile private var previewTapBuffer: java.nio.ByteBuffer? = null
    private val previewTapExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RawPreviewTap").also { it.priority = Thread.NORM_PRIORITY - 1 }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Invalidates every queued/in-flight idle monitor frame before a mode, profile, camera, or
     * Camera2-session transition. The returned epoch remains disarmed until
     * [commitPreviewPresentationEpoch].
     */
    fun beginPreviewPresentationEpoch(identity: String): Long {
        val epoch = previewPresentationGate.begin(identity)
        val imagesToClose = resultLock.withLock {
            idleRawJoinGeneration += 1L
            clearNonAuthoritativeCaptureResultsLocked()
            ArrayList<Image>(unmatchedIdleRawImages.size + 1).apply {
                addAll(unmatchedIdleRawImages.values)
                unmatchedIdleRawImages.clear()
                idleRawPreviewPending.getAndSet(null)?.let { add(it.image) }
            }
        }
        imagesToClose.forEach { image -> runCatching { image.close() } }
        rawPreviewColorMetadataStabilizer.reset()
        rawPreviewColorDiagnosticRateLimiter.reset()
        lastRawPreviewColorMetadataSource = null
        lastRawColorTransformSource = null
        lastRawSensorCalibrationSignature = null
        NeoLogger.i(TAG, "RAW monitor epoch BEGIN epoch=$epoch identity=$identity")
        return epoch
    }

    /** Commits the exact profile/session pair after its Vulkan configuration is fully applied. */
    fun commitPreviewPresentationEpoch(epoch: Long, sessionGeneration: Long): Boolean {
        var imagesToClose: List<Image> = emptyList()
        val outcome = resultLock.withLock {
            val snapshot = previewPresentationGate.snapshot()
            if (epoch <= 0L || sessionGeneration < 0L || snapshot.epoch != epoch) {
                RawPreviewPresentationEpochGate.CommitOutcome.REJECTED
            } else if (snapshot.committedEpoch == epoch &&
                snapshot.sessionGeneration == sessionGeneration
            ) {
                RawPreviewPresentationEpochGate.CommitOutcome.UNCHANGED
            } else {
                // Nothing received while the epoch was disarmed is eligible for its first visible
                // frame. Clear stale joins while admission is still closed, then publish the exact
                // committed pair as the final operation under the same ingress lock.
                idleRawJoinGeneration += 1L
                clearNonAuthoritativeCaptureResultsLocked()
                imagesToClose = ArrayList<Image>(unmatchedIdleRawImages.size + 1).apply {
                    addAll(unmatchedIdleRawImages.values)
                    unmatchedIdleRawImages.clear()
                    idleRawPreviewPending.getAndSet(null)?.let { add(it.image) }
                }
                previewPresentationGate.commitOutcome(epoch, sessionGeneration)
            }
        }
        val accepted = outcome != RawPreviewPresentationEpochGate.CommitOutcome.REJECTED
        imagesToClose.forEach { image -> runCatching { image.close() } }
        if (outcome == RawPreviewPresentationEpochGate.CommitOutcome.COMMITTED) {
            rawPreviewColorMetadataStabilizer.reset()
            rawPreviewColorDiagnosticRateLimiter.reset()
            lastRawPreviewColorMetadataSource = null
            lastRawColorTransformSource = null
            lastRawSensorCalibrationSignature = null
            NeoLogger.i(
                TAG,
                "RAW monitor epoch COMMIT epoch=$epoch sessionGeneration=$sessionGeneration"
            )
        }
        return accepted
    }

    fun currentPreviewPresentationEpoch(): Long = previewPresentationGate.snapshot().epoch

    fun isPreviewPresentationEpochCurrent(epoch: Long): Boolean =
        previewPresentationGate.isCurrent(epoch)

    /**
     * Native RAW completion can outlive the Image submission. Serialize its final presentation with
     * capture ownership so an idle frame is either presented before RAW recording starts or rejected.
     */
    internal fun presentIdleRawPreviewCompletionIfCurrent(
        identity: IdleRawPreviewSubmissionIdentity,
        present: () -> Unit
    ): Boolean = sensorLinearNdrRecordingLifecycleLock.withLock {
        if (!isIdleRawPreviewSubmissionCurrentLocked(identity)) {
            false
        } else {
            present()
            true
        }
    }

    /**
     * Arms the independent RAW_SENSOR -> NDR encoder ingress. This does not open the custom RAW disk
     * writer and does not depend on a visible preview epoch being committed.
     */
    internal fun startSensorLinearNdrRecordingIngress(
        readerMaxImages: Int,
        targetFps: Int,
        onFailure: (SensorLinearNdrRecordingFailure) -> Unit,
        startupReadiness: SensorLinearNdrRecordingStartupGate.ReadinessLease,
        expectedSessionGeneration: Long = camera.sessionGeneration.get()
    ): Boolean = sensorLinearNdrRecordingLifecycleLock.withLock {
        if (destroyRequested.get() || destroyed.get() || captureLifecycleActive.get() ||
            expectedSessionGeneration < 0L ||
            expectedSessionGeneration != camera.sessionGeneration.get() ||
            sensorLinearNdrRecordingLifecycleActive.get() ||
            sensorLinearNdrRecordingWorkerScheduled.get() ||
            sensorLinearNdrRecordingNativePending.get() ||
            sensorLinearNdrRecordingOwnedFrames.get() != 0 ||
            sensorLinearNdrRecordingRetryFrame.get() != null ||
            sensorLinearNdrRecordingProcessorInFlight.get() != null ||
            sensorLinearNdrRecordingQueue.isNotEmpty() ||
            sensorLinearNdrRecordingIngressAdmission.get() != null
        ) return@withLock false
        if (sensorLinearNdrRecordingProcessor == null ||
            sensorLinearNdrRecordingCompletionPoller == null
        ) {
            Log.e(TAG, "Sensor-linear NDR recording ingress has no processor/completion owner")
            return@withLock false
        }
        val boundedStallMinimumDepth =
            RawSensorReaderDepthPolicy.minimumBoundedStallDepth(targetFps)
        if (readerMaxImages < boundedStallMinimumDepth) {
            Log.e(
                TAG,
                "Sensor-linear NDR recording reader cannot preserve ordered ${targetFps}fps " +
                    "through the certified bounded stall: maxImages=$readerMaxImages " +
                    "required=$boundedStallMinimumDepth; refusing a clip that could only survive " +
                    "by dropping or reordering frames"
            )
            return@withLock false
        }
        val cutoverTimestampFloorNs =
            if (latestCamera2ResultSessionGeneration == expectedSessionGeneration) {
                latestCamera2ResultTimestampNs
            } else {
                0L
            }
        if (cutoverTimestampFloorNs <= 0L) {
            Log.e(
                TAG,
                "Sensor-linear NDR recording ingress has no current-session Camera2 cutover result " +
                    "expectedSession=$expectedSessionGeneration " +
                    "latestSession=$latestCamera2ResultSessionGeneration"
            )
            return@withLock false
        }
        if (!sensorLinearNdrRecordingStartupGate.consume(
                lease = startupReadiness,
                currentSessionGeneration = expectedSessionGeneration
            )
        ) {
            Log.e(
                TAG,
                "Sensor-linear NDR recording ingress rejected a stale post-switch RAW/result readiness lease"
            )
            return@withLock false
        }

        // Retire any state left by an interrupted owner before publishing a new generation. Lifecycle
        // active remains false throughout this cleanup, so no Camera2 callback can be relabelled as new.
        takeAllSensorLinearNdrRecordingImages().forEach(::releaseSensorLinearNdrImage)
        val generation = sensorLinearNdrRecordingGeneration.incrementAndGet()
        val ingressToken = SensorLinearNdrRecordingIngressToken(
            generation = generation,
            sessionGeneration = expectedSessionGeneration,
            cutoverTimestampFloorNs = cutoverTimestampFloorNs
        )
        val ownedLimit = SensorLinearNdrRecordingIngressPolicy.ownedFrameLimit(readerMaxImages)
        sensorLinearNdrRecordingTargetFps = targetFps.coerceAtLeast(1)
        sensorLinearNdrRecordingSessionGeneration.set(expectedSessionGeneration)
        sensorLinearNdrRecordingOwnedFrameLimit.set(ownedLimit)
        sensorLinearNdrRecordingOwnedFrames.set(0)
        sensorLinearNdrRecordingOwnedHighWatermark.set(0)
        sensorLinearNdrRecordingCapacityWaits.set(0L)
        sensorLinearNdrRecordingCutoverRetiredFrames.set(0)
        sensorLinearNdrImageJoinDiagnosticCount.set(0)
        sensorLinearNdrResultJoinDiagnosticCount.set(0)
        sensorLinearNdrRecordingArmedElapsedNs.set(SystemClock.elapsedRealtimeNanos())
        sensorLinearNdrRecordingFailureReported.set(false)
        sensorLinearNdrRecordingNativePending.set(false)
        sensorLinearNdrRecordingRetryWindow.set(null)
        sensorLinearNdrRecordingProcessorInFlight.set(null)
        sensorLinearNdrRecordingProcessorInFlightSinceNs.set(0L)
        sensorLinearNdrRecordingProcessorMaxDurationNs.set(0L)
        sensorLinearNdrRecordingProcessorSlowSubmissions.set(0L)
        sensorLinearNdrRecordingFailureListener = onFailure
        sensorLinearNdrRecordingStopLatch = CountDownLatch(1)
        sensorLinearNdrRecordingQueue = ArrayBlockingQueue(ownedLimit)

        resultLock.withLock {
            sensorLinearNdrRecordingResults.clear()
            sensorLinearNdrNextImageSequence = 1L
            sensorLinearNdrNextQueueSequence = 1L
        }
        clearIdleRawPreviewFrames()
        sensorLinearNdrRecordingColorMetadataStabilizer.reset()
        lastSensorLinearNdrRecordingColorMetadataSource = null
        invalidateRawOriginalExposureAnalyticsLocked()
        sensorLinearNdrRecordingLifecycleActive.set(true)
        sensorLinearNdrRecordingDiscardRequested.set(false)
        sensorLinearNdrRecordingAccepting.set(true)
        // Publish the immutable callback-entry proof last. A callback entering during setup observes
        // null and can never be admitted later merely because this generation subsequently opens.
        sensorLinearNdrRecordingIngressAdmission.set(ingressToken)
        sensorLinearNdrRecordingCapacityGate.signalCapacityChanged()
        NeoLogger.i(
            TAG,
            "NDR_RECORD_INGRESS_ARM generation=$generation readerMaxImages=$readerMaxImages " +
                "ownedLimit=$ownedLimit boundedStallMin=$boundedStallMinimumDepth " +
                "boundedStallBudgetNs=${RawSensorReaderDepthPolicy.EVIDENCED_BOUNDED_STALL_NS} " +
                "targetFps=$sensorLinearNdrRecordingTargetFps " +
                "sessionGeneration=$expectedSessionGeneration " +
                "cutoverTimestampFloorNs=$cutoverTimestampFloorNs"
        )
        true
    }

    /**
     * Signal-only lifecycle teardown used by nonblocking UI destruction paths.
     *
     * This closes Camera2 admission and releases the UI-owned failure callback using only atomic/
     * volatile publication. It deliberately never waits for the lifecycle lock because this method is
     * called from Android lifecycle teardown while another thread may be retiring Image ownership.
     * A background owner must subsequently call [stopSensorLinearNdrRecordingIngress] with
     * `drain = false`; that path performs exact-image retirement and the mandatory encoder-safety join.
     *
     */
    internal fun requestSensorLinearNdrRecordingIngressTeardown() {
        sensorLinearNdrRecordingIngressAdmission.set(null)
        sensorLinearNdrRecordingAccepting.set(false)
        sensorLinearNdrRecordingFailureListener = null
        sensorLinearNdrRecordingCapacityGate.signalCapacityChanged()
    }

    /**
     * Closes admission first, then drains all admitted frames and the final native completion while
     * the encoder surface is still alive. The caller may block; Camera2 callbacks never call this.
     */
    internal fun stopSensorLinearNdrRecordingIngress(
        drain: Boolean = true,
        timeoutMs: Long = 2_000L
    ): SensorLinearNdrRecordingStopResult {
        sensorLinearNdrRecordingStartupGate.cancelActive()
        fun result(teardownSafe: Boolean) = SensorLinearNdrRecordingStopResult(
            teardownSafe = teardownSafe,
            ingressFailed = sensorLinearNdrRecordingFailureReported.get()
        )

        // Close entry publication before waiting for the lifecycle lock. Repeat under the lock so a
        // concurrently completing start cannot republish between this signal and stop linearization.
        sensorLinearNdrRecordingIngressAdmission.set(null)
        sensorLinearNdrRecordingCapacityGate.signalCapacityChanged()
        val stopContext = sensorLinearNdrRecordingLifecycleLock.withLock {
            sensorLinearNdrRecordingIngressAdmission.set(null)
            if (!sensorLinearNdrRecordingLifecycleActive.get()) {
                null
            } else {
                val generation = sensorLinearNdrRecordingGeneration.get()
                sensorLinearNdrRecordingAccepting.set(false)
                generation to sensorLinearNdrRecordingStopLatch
            }
        }
        // The recorder worker publishes logical inactivity immediately before leaving its final task.
        // A repeated stop must still join that task; otherwise a caller could detach the encoder during
        // the small inactive-to-worker-unscheduled handoff window.
        if (stopContext == null) {
            val idle = awaitSensorLinearNdrRecordingWorkerIdleForTeardown(timeoutMs)
            return result(teardownSafe = idle)
        }
        val generation = stopContext.first
        if (!drain) {
            requestSensorLinearNdrRecordingDiscard(generation)
        }

        scheduleSensorLinearNdrRecordingWorker()
        val latch = stopContext.second
        if (latch == null) {
            // An active generation must always own a stop latch. Treat corruption as a fatal ingress
            // failure, but never let this improbable branch bypass the encoder-safety worker join.
            failSensorLinearNdrRecording(
                reason = "Ordered NDR recording ingress lost its stop latch",
                expectedGeneration = generation
            )
            // finishSensorLinearNdrRecordingIngress() remains recorder-worker owned; this mandatory
            // join proves that the failure request reached that terminal transition before teardown.
            val teardownSafe = awaitSensorLinearNdrRecordingWorkerIdleForTeardown(timeoutMs)
            return result(teardownSafe = teardownSafe)
        }
        val completed = try {
            latch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            failSensorLinearNdrRecording(
                reason = "Timed out draining ordered NDR recording ingress before encoder stop",
                sensorTimestampNs = sensorLinearNdrRecordingRetryFrame.get()?.image?.timestamp,
                expectedGeneration = generation
            )
            val teardownSafe = awaitSensorLinearNdrRecordingWorkerIdleForTeardown(timeoutMs)
            return result(teardownSafe = teardownSafe)
        }
        val idle = awaitSensorLinearNdrRecordingWorkerIdle(timeoutMs)
        if (!idle) {
            failSensorLinearNdrRecording(
                reason = "Ordered NDR recording worker did not quiesce before encoder stop",
                expectedGeneration = generation
            )
        }
        val teardownSafe = idle || awaitSensorLinearNdrRecordingWorkerIdleForTeardown(timeoutMs)
        return result(teardownSafe = teardownSafe)
    }

    /**
     * Discards only Java-owned, not-yet-submitted frames. An already accepted Vulkan/encoder tail
     * remains published in [sensorLinearNdrRecordingNativePending] and the worker must poll it to a
     * terminal completion before [finishSensorLinearNdrRecordingIngress] can close the generation.
     */
    private fun requestSensorLinearNdrRecordingDiscard(expectedGeneration: Long) {
        val images = sensorLinearNdrRecordingLifecycleLock.withLock {
            if (!sensorLinearNdrRecordingLifecycleActive.get() ||
                expectedGeneration != sensorLinearNdrRecordingGeneration.get()
            ) {
                emptyList()
            } else {
                sensorLinearNdrRecordingIngressAdmission.set(null)
                sensorLinearNdrRecordingAccepting.set(false)
                sensorLinearNdrRecordingDiscardRequested.set(true)
                takeAllSensorLinearNdrRecordingImages()
            }
        }
        images.forEach(::releaseSensorLinearNdrImage)
        scheduleSensorLinearNdrRecordingWorker()
    }

    /** Stop is called from an IO/lifecycle worker, never from this recorder executor. */
    private fun awaitSensorLinearNdrRecordingWorkerIdle(timeoutMs: Long): Boolean {
        val timeoutNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1L))
        val deadlineNs = System.nanoTime() + timeoutNs
        while (true) {
            if (!awaitSensorLinearNdrRecordingWorkerUnscheduledUntil(deadlineNs)) return false
            val barrier = try {
                sensorLinearNdrRecordingExecutor.submit { Unit }
            } catch (failure: Throwable) {
                Log.e(TAG, "Unable to schedule NDR recorder quiescence barrier", failure)
                return false
            }
            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0L) return false
            val completed = try {
                barrier.get(remainingNs, TimeUnit.NANOSECONDS)
                true
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } catch (_: TimeoutException) {
                false
            } catch (failure: Throwable) {
                Log.e(TAG, "NDR recorder quiescence barrier failed", failure)
                false
            }
            if (!completed) return false
            // A running worker can clear the flag and immediately reschedule a delayed retry in its
            // finally block. The barrier may then overtake that delayed task; recheck and repeat.
            if (!sensorLinearNdrRecordingWorkerScheduled.get() &&
                isSensorLinearNdrRecordingWorkerQuiescentForTeardown()
            ) return true
        }
    }

    private fun awaitSensorLinearNdrRecordingWorkerUnscheduledUntil(deadlineNs: Long): Boolean {
        sensorLinearNdrRecordingWorkerStateLock.lock()
        return try {
            while (sensorLinearNdrRecordingWorkerScheduled.get()) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) return false
                try {
                    sensorLinearNdrRecordingWorkerUnscheduled.awaitNanos(remainingNs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            true
        } finally {
            sensorLinearNdrRecordingWorkerStateLock.unlock()
        }
    }

    private fun markSensorLinearNdrRecordingWorkerUnscheduled() {
        sensorLinearNdrRecordingWorkerStateLock.withLock {
            sensorLinearNdrRecordingWorkerScheduled.set(false)
            sensorLinearNdrRecordingWorkerUnscheduled.signalAll()
        }
    }

    /**
     * Encoder teardown safety barrier. The bounded wait reports a stall; after that, keep the encoder
     * resources alive and join the existing single worker without a polling loop. There is no safe
     * timeout-based way to reclaim a Surface/AHardwareBuffer that a native submission may still use.
     */
    private fun awaitSensorLinearNdrRecordingWorkerIdleForTeardown(timeoutMs: Long): Boolean {
        if (awaitSensorLinearNdrRecordingWorkerIdle(timeoutMs)) return true
        Log.e(
            TAG,
            "NDR recorder quiescence deadline missed; holding encoder resources until worker returns"
        )
        var interrupted = false
        try {
            while (true) {
                sensorLinearNdrRecordingWorkerStateLock.lock()
                try {
                    while (sensorLinearNdrRecordingWorkerScheduled.get()) {
                        try {
                            sensorLinearNdrRecordingWorkerUnscheduled.await()
                        } catch (_: InterruptedException) {
                            // Resource ownership cannot be abandoned on cancellation/interruption.
                            // Preserve the interrupt only after the old worker is physically retired.
                            interrupted = true
                        }
                    }
                } finally {
                    sensorLinearNdrRecordingWorkerStateLock.unlock()
                }

                val barrier = try {
                    sensorLinearNdrRecordingExecutor.submit { Unit }
                } catch (failure: Throwable) {
                    Log.e(TAG, "Unable to schedule mandatory NDR teardown barrier", failure)
                    return false
                }
                while (true) {
                    try {
                        barrier.get()
                        break
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                if (!sensorLinearNdrRecordingWorkerScheduled.get() &&
                    isSensorLinearNdrRecordingWorkerQuiescentForTeardown()
                ) return true
            }
        } catch (failure: Throwable) {
            Log.e(TAG, "Mandatory NDR teardown barrier failed", failure)
            return false
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /**
     * Nonblocking proof used by deferred resource retirement after a stop reports failure.
     *
     * A recorded ingress failure can coexist with a successful stop ownership proof. Conversely, a
     * late native submission/retry can leave resources owned after logical lifecycle shutdown. This
     * probe distinguishes those cases without scheduling or waiting: lock contention is reported as
     * not quiescent, never guessed safe.
     */
    internal fun isSensorLinearNdrRecordingWorkerQuiescentForTeardown(): Boolean {
        if (!sensorLinearNdrRecordingLifecycleLock.tryLock()) return false
        return try {
            if (sensorLinearNdrRecordingLifecycleActive.get() ||
                sensorLinearNdrRecordingAccepting.get() ||
                sensorLinearNdrRecordingWorkerScheduled.get() ||
                sensorLinearNdrRecordingNativePending.get() ||
                sensorLinearNdrRecordingOwnedFrames.get() != 0 ||
                sensorLinearNdrRecordingRetryFrame.get() != null ||
                sensorLinearNdrRecordingProcessorInFlight.get() != null ||
                sensorLinearNdrRecordingQueue.isNotEmpty()
            ) return false

            if (!resultLock.tryLock()) return false
            try {
                // Recheck worker/native state after taking the join lock. Failure cleanup and Camera2
                // exact-result joining cannot create a hidden release owner between this check and return.
                !sensorLinearNdrRecordingLifecycleActive.get() &&
                    !sensorLinearNdrRecordingAccepting.get() &&
                    !sensorLinearNdrRecordingWorkerScheduled.get() &&
                    !sensorLinearNdrRecordingNativePending.get() &&
                    sensorLinearNdrRecordingOwnedFrames.get() == 0 &&
                    sensorLinearNdrRecordingRetryFrame.get() == null &&
                    sensorLinearNdrRecordingProcessorInFlight.get() == null &&
                    sensorLinearNdrRecordingQueue.isEmpty() &&
                    sensorLinearNdrPendingImages.isEmpty() &&
                    sensorLinearNdrReadyBySequence.isEmpty()
            } finally {
                resultLock.unlock()
            }
        } finally {
            sensorLinearNdrRecordingLifecycleLock.unlock()
        }
    }

    internal fun isSensorLinearNdrRecordingIngressActive(): Boolean =
        sensorLinearNdrRecordingLifecycleActive.get()

    /**
     * Bounded proof that closing the shared RAW_SENSOR ImageReader cannot invalidate an Image still
     * owned by RAW recording, sensor-linear recording, the idle monitor, or a preview tap. The caller
     * must already have stopped new CameraController callback admission for the exact reader identity.
     * Active recording is never interrupted by a reader refresh: it fails closed and leaves the reader
     * quarantined for a later retry.
     */
    internal fun awaitImageReaderOwnershipIdle(timeoutMs: Long): Boolean {
        if (timeoutMs <= 0L ||
            captureLifecycleActive.get() ||
            isCapturing.get() ||
            rawCaptureIngressAdmission.get() != null ||
            rawCaptureResultIngressIdentity.get() != null ||
            rawResumeAdmissionLease.get() != null ||
            sensorLinearNdrRecordingLifecycleActive.get() ||
            sensorLinearNdrRecordingAccepting.get() ||
            sensorLinearNdrRecordingIngressAdmission.get() != null
        ) return false

        val startedNs = System.nanoTime()
        val timeoutNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs).coerceAtLeast(1L)
        val deadlineNs = if (Long.MAX_VALUE - startedNs < timeoutNs) {
            Long.MAX_VALUE
        } else {
            startedNs + timeoutNs
        }
        clearIdleRawPreviewFrames()
        val previewWaitMs = remainingReaderCloseMs(deadlineNs)
        if (previewWaitMs <= 0L ||
            !awaitIdleRawPreviewWorkerIdleForTeardown(previewWaitMs)
        ) {
            return false
        }
        val previewTapWaitMs = remainingReaderCloseMs(deadlineNs)
        if (previewTapWaitMs <= 0L || !awaitPreviewTapIdle(previewTapWaitMs)) return false

        return sensorLinearNdrRecordingLifecycleLock.withLock {
            rawWriterAdmissionLock.withLock {
                if (captureLifecycleActive.get() ||
                    isCapturing.get() ||
                    rawCaptureIngressAdmission.get() != null ||
                    rawCaptureResultIngressIdentity.get() != null ||
                    rawResumeAdmissionLease.get() != null ||
                    ownedWriterImages.get() != 0 ||
                    activeWrites.get() != 0 ||
                    (directIOEngine?.borrowedPayloadWriteCount ?: 0) != 0 ||
                    writeQueue.isNotEmpty() ||
                    writeLoopHandle?.let { handle ->
                        !handle.future.isDone || handle.session.queue.isNotEmpty()
                    } == true ||
                    previewTapBusy.get() ||
                    idleRawPreviewWorkerScheduled.get() ||
                    idleRawPreviewPending.get() != null ||
                    !isSensorLinearNdrRecordingWorkerQuiescentForTeardown()
                ) return@withLock false

                resultLock.withLock {
                    unmatchedIdleRawImages.isEmpty() &&
                        pendingRawCaptureResultsByTimestamp.isEmpty() &&
                        sensorLinearNdrPendingImages.isEmpty() &&
                        sensorLinearNdrReadyBySequence.isEmpty()
                }
            }
        }
    }

    private fun remainingReaderCloseMs(deadlineNs: Long): Long {
        val remainingNs = deadlineNs - System.nanoTime()
        if (remainingNs <= 0L) return 0L
        return ((remainingNs + 999_999L) / 1_000_000L).coerceAtLeast(1L)
    }

    /** Caller holds [sensorLinearNdrRecordingLifecycleLock]. */
    private fun snapshotSensorLinearNdrRecordingIngressLocked(
        callbackObservedAdmission: SensorLinearNdrRecordingIngressToken?
    ): SensorLinearNdrRecordingIngressToken? {
        val currentSessionGeneration = camera.sessionGeneration.get()
        return if (callbackObservedAdmission == null ||
            sensorLinearNdrRecordingIngressAdmission.get() !== callbackObservedAdmission ||
            callbackObservedAdmission.generation != sensorLinearNdrRecordingGeneration.get() ||
            callbackObservedAdmission.sessionGeneration !=
                sensorLinearNdrRecordingSessionGeneration.get() ||
            callbackObservedAdmission.sessionGeneration != currentSessionGeneration ||
            destroyRequested.get() || destroyed.get() ||
            !sensorLinearNdrRecordingLifecycleActive.get() ||
            !sensorLinearNdrRecordingAccepting.get() ||
            sensorLinearNdrRecordingDiscardRequested.get()
        ) {
            null
        } else {
            callbackObservedAdmission
        }
    }

    private fun nextRawWriterGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L

    /** Opens Camera2 admission only after the exact writer queue and storage session are ready. */
    private fun openRawWriterAdmission(): RawWriterSessionToken = rawWriterAdmissionLock.withLock {
        check(captureLifecycleActive.get() && !destroyRequested.get() && !destroyed.get()) {
            "RAW writer admission cannot open without live capture ownership"
        }
        check(rawCaptureIngressAdmission.get() == null) {
            "RAW writer admission cannot replace a published callback token"
        }
        check(rawCaptureResultIngressIdentity.get() == null) {
            "RAW result identity from a retired capture was not finalized"
        }
        rawWriterSessionGeneration = nextRawWriterGeneration(rawWriterSessionGeneration)
        rawWriterAdmissionGeneration = nextRawWriterGeneration(rawWriterAdmissionGeneration)
        val cameraSessionGeneration = camera.sessionGeneration.get()
        if (rawRetiredTimestampSessionGeneration != cameraSessionGeneration) {
            rawRetiredTimestampSessionGeneration = cameraSessionGeneration
            rawRetiredSensorTimestampFloorNs = 0L
        }
        val session = RawWriterSessionToken(rawWriterSessionGeneration, writeQueue)
        val ingressToken = RawCaptureIngressToken(
            session = session,
            admissionGeneration = rawWriterAdmissionGeneration,
            cameraSessionGeneration = cameraSessionGeneration,
            retiredSensorTimestampFloorNs = rawRetiredSensorTimestampFloorNs
        )
        isPaused.set(false)
        isCapturing.set(true)
        // Result ownership is published before frame admission and survives its close for exact drain.
        rawCaptureResultIngressIdentity.set(ingressToken)
        // Entry proof is published only after the queue, session and boolean gate are fully open.
        rawCaptureIngressAdmission.set(ingressToken)
        session
    }

    /** Closes new Image admission before any writer/storage quiescence wait begins. */
    private fun closeRawWriterAdmission() = rawWriterAdmissionLock.withLock {
        rawResumeAdmissionLease.set(null)
        rawCaptureIngressAdmission.set(null)
        rawWriterAdmissionGeneration = nextRawWriterGeneration(rawWriterAdmissionGeneration)
        isPaused.set(false)
        isCapturing.set(false)
        // Wake an authoritative writer so it can revalidate. Exact pending ownership remains live
        // through the ordinary stop drain, allowing a post-close result callback to complete it.
        exactCaptureResultWaiter.signalChanged()
    }

    /**
     * Retires result ownership only after all accepted Images are quiescent. This is the terminal
     * half of close: callbacks sampled during drain keep an immutable old identity, so once this
     * method clears the pending map they cannot mutate idle preview or a subsequent recording.
     */
    private fun finalizeRawCaptureResultIngress() = rawWriterAdmissionLock.withLock {
        rawCaptureIngressAdmission.set(null)
        val retiringIdentity = rawCaptureResultIngressIdentity.getAndSet(null)
        resultLock.withLock {
            if (retiringIdentity != null) {
                val latestTimestampNs = latestResult?.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                val retiredFloorNs = maxOf(
                    rawRetiredSensorTimestampFloorNs,
                    retiringIdentity.retiredSensorTimestampFloorNs,
                    lastFrameNs,
                    lastPersistedSensorTimestampNs.get(),
                    latestTimestampNs
                )
                if (rawRetiredTimestampSessionGeneration ==
                    retiringIdentity.cameraSessionGeneration
                ) {
                    rawRetiredSensorTimestampFloorNs = retiredFloorNs
                } else {
                    rawRetiredTimestampSessionGeneration =
                        retiringIdentity.cameraSessionGeneration
                    rawRetiredSensorTimestampFloorNs = maxOf(
                        retiringIdentity.retiredSensorTimestampFloorNs,
                        lastFrameNs,
                        lastPersistedSensorTimestampNs.get(),
                        latestTimestampNs
                    )
                }
            }
            pendingRawCaptureResultsByTimestamp.clear()
            recentResultsByTimestamp.clear()
            latestResult = null
            exactCaptureResultWaiter.signalChangedLocked()
        }
    }

    /** Caller holds [rawWriterAdmissionLock]. */
    private fun snapshotRawCaptureIngressLocked(
        callbackObservedAdmission: RawCaptureIngressToken?
    ): RawCaptureIngressToken? =
        if (callbackObservedAdmission == null ||
            rawCaptureIngressAdmission.get() !== callbackObservedAdmission ||
            callbackObservedAdmission.admissionGeneration != rawWriterAdmissionGeneration ||
            callbackObservedAdmission.cameraSessionGeneration != camera.sessionGeneration.get() ||
            !captureLifecycleActive.get() || destroyRequested.get() || destroyed.get() ||
            !isCapturing.get() || isPaused.get()
        ) {
            null
        } else {
            callbackObservedAdmission
        }

    /** Caller holds [rawWriterAdmissionLock]. */
    private fun rawCaptureIngressIsCurrentLocked(token: RawCaptureIngressToken): Boolean =
        !destroyRequested.get() &&
            !destroyed.get() &&
            captureLifecycleActive.get() &&
            isCapturing.get() &&
            !isPaused.get() &&
            rawCaptureIngressAdmission.get() === token &&
            token.session.generation == rawWriterSessionGeneration &&
            token.admissionGeneration == rawWriterAdmissionGeneration &&
            token.cameraSessionGeneration == camera.sessionGeneration.get() &&
            token.session.queue === writeQueue

    /** Caller holds [resultLock]. */
    private fun registerAcceptedRawFrameResultLocked(
        token: RawCaptureIngressToken,
        image: Image
    ) {
        val timestamp = image.timestamp
        pendingRawCaptureResultsByTimestamp[timestamp] = token
        if (rawCaptureFirstAcceptedSensorTimestampNs.compareAndSet(0L, timestamp)) {
            // A result callback invoked only after restart may briefly sample the new token before
            // the first Image arrives. Sensor timestamps are monotonic within a Camera2 session, so
            // purge every older result before the first new frame can resolve descriptor metadata.
            recentResultsByTimestamp.entries.removeAll { it.key < timestamp }
            val latestTimestampNs = latestResult?.get(CaptureResult.SENSOR_TIMESTAMP)
            if (latestTimestampNs != null && latestTimestampNs < timestamp) latestResult = null
        }
    }

    /** Caller holds [rawWriterAdmissionLock]; publish queue ownership and result ownership atomically. */
    private fun offerRawFrameAndRegisterResultLocked(
        token: RawCaptureIngressToken,
        image: Image
    ): Boolean = resultLock.withLock {
        val timestamp = image.timestamp
        val existing = pendingRawCaptureResultsByTimestamp[timestamp]
        if (existing != null && existing !== token) return@withLock false
        val accepted = token.session.queue.offer(image)
        if (accepted) {
            registerAcceptedRawFrameResultLocked(token, image)
            admittedRawFrameCount.incrementAndGet()
        }
        accepted
    }

    /**
     * Ends a strict clip before the next frame would exceed safe storage, physical reservation, or
     * protected ImageReader ownership. The current Image is deliberately outside the clip; no
     * accepted timestamp is skipped and the normal stop transaction can publish the continuous
     * prefix after every already-admitted frame drains.
     */
    private fun stopBeforeRawFrameAtContinuityBoundaryIfRequired(
        token: RawCaptureIngressToken,
        image: Image,
        readerMaxImages: Int
    ): Boolean {
        var boundary: RawRecordingContinuityBoundary? = null
        val rejectCurrentFrame = rawWriterAdmissionLock.withLock {
            if (!rawCaptureIngressIsCurrentLocked(token)) return@withLock true
            if (!strictHighEndRawCaptureActive()) return@withLock false

            val writer = directIOEngine?.takeIf { it.isOpen } ?: return@withLock false
            val packetBytes = runCatching {
                NprawFrameCodec.realtimeLosslessPacketBufferCapacity(
                    width = image.width,
                    height = image.height,
                    sensorWhiteLevel = resolveSensorWhiteLevel()
                ).toLong()
            }.getOrElse { return@withLock false }
            val selectedFps = activeCaptureFps.get().coerceAtLeast(1)
            val cachedBudget = rawRecordingStorageBudget
            val storageBudget = if (cachedBudget != null &&
                cachedBudget.framePacketBytes == packetBytes &&
                cachedBudget.targetFps == selectedFps
            ) {
                cachedBudget
            } else {
                RawRecordingContinuityPolicy.resolveStorageBudget(
                    plan = writer.preallocationResult.plan,
                    framePacketBytes = packetBytes,
                    targetFps = selectedFps
                ).also { rawRecordingStorageBudget = it }
            }
            val acceptedFrames = admittedRawFrameCount.get()
            val queuedImages = token.session.queue.size
            // The engine offset contains every encoded packet already accepted for ordered write.
            // Queue/active Images have not reached that offset yet, so reserve each of them at the
            // complete worst-case packet size. This is fail-closed without pretending all earlier
            // variable-size lossless packets consumed their maximum capacity.
            val acceptedFileBytes = writer.acceptedFileBytes
            val activeImageOwners = ownedWriterImages.get()
            val projectedEndBytes = RawRecordingContinuityPolicy.projectedAdaptiveEndBytes(
                acceptedFileBytes = acceptedFileBytes,
                pendingUnencodedFrames = queuedImages + activeImageOwners,
                framePacketBytes = packetBytes
            )
            // The Direct-I/O borrowed count and active write-loop count can describe the same Image
            // during their ownership hand-off. Count each dequeued Camera2 Image once through its
            // reference-counted lease; double-counting that overlap caused a false continuity stop
            // while three real ImageReader slots were still available on the 4K25 path.
            val conservativeMaximumFrames =
                RawRecordingContinuityPolicy.conservativeMaximumFramesFromCurrentBytes(
                    acceptedFrames = acceptedFrames,
                    acceptedFileBytes = acceptedFileBytes,
                    writableBytes = storageBudget.writableBytes,
                    framePacketBytes = packetBytes
                )
            val reason = when {
                RawRecordingContinuityPolicy.shouldStopBeforeAdaptivePacket(
                    projectedEndBytes = projectedEndBytes,
                    writableBytes = storageBudget.writableBytes
                ) -> RawRecordingContinuityBoundaryReason.STORAGE_LIMIT
                !writer.preparePhysicalCapacity(projectedEndBytes) ->
                    RawRecordingContinuityBoundaryReason.PHYSICAL_RESERVATION_LIMIT
                token.session.queue.remainingCapacity() == 0 ||
                    RawRecordingContinuityPolicy.shouldStopForBackpressure(
                        readerMaxImages = readerMaxImages,
                        queuedImages = queuedImages,
                        activeImageOwners = activeImageOwners
                    ) -> RawRecordingContinuityBoundaryReason.BACKPRESSURE
                else -> null
            }
            if (reason == null) return@withLock false

            if (continuityBoundaryStopRequested.compareAndSet(false, true)) {
                closeRawWriterAdmission()
                boundary = RawRecordingContinuityBoundary(
                    reason = reason,
                    acceptedFrames = acceptedFrames,
                    maximumFrames = conservativeMaximumFrames,
                    framePacketBytes = packetBytes,
                    acceptedFileBytes = acceptedFileBytes,
                    writableBytes = storageBudget.writableBytes,
                    projectedEndBytes = projectedEndBytes,
                    physicallyReservedBytes = writer.physicallyReservedBytes,
                    queuedImages = queuedImages,
                    activeImageOwners = activeImageOwners
                )
            }
            true
        }
        boundary?.let(::dispatchContinuityBoundaryStop)
        return rejectCurrentFrame
    }

    private fun dispatchContinuityBoundaryStop(boundary: RawRecordingContinuityBoundary) {
        NeoLogger.w(
            TAG,
                "RAW_CONTINUITY_BOUNDARY reason=${boundary.reason} " +
                "accepted=${boundary.acceptedFrames}/${boundary.maximumFrames} " +
                "acceptedFileBytes=${boundary.acceptedFileBytes} " +
                "writableBytes=${boundary.writableBytes} " +
                "projectedBytes=${boundary.projectedEndBytes} " +
                "reservedBytes=${boundary.physicallyReservedBytes} " +
                "queued=${boundary.queuedImages} active=${boundary.activeImageOwners}"
        )
        val listener = continuityBoundaryStopListener
        val delivered = listener != null && runCatching { listener(boundary) }
            .onFailure { Log.e(TAG, "RAW continuity stop listener failed", it) }
            .isSuccess
        if (delivered) return

        // Standalone manager users still get exact finalization. This task follows the active write
        // loop on the same serial executor, so it cannot race that loop or close its storage owner.
        runCatching {
            writeExecutor.execute {
                runCatching { stopCapture() }
                    .onFailure { Log.e(TAG, "RAW continuity fallback stop failed", it) }
            }
        }.onFailure { Log.e(TAG, "Unable to schedule RAW continuity fallback stop", it) }
    }

    private fun retireRawCaptureResultJoin(timestampNs: Long) {
        resultLock.withLock {
            pendingRawCaptureResultsByTimestamp.remove(timestampNs)
            recentResultsByTimestamp.remove(timestampNs)
            exactCaptureResultWaiter.signalChangedLocked()
        }
    }

    private fun admitRawFrameTimestamp(
        token: RawCaptureIngressToken,
        timestampNs: Long
    ): Boolean = rawWriterAdmissionLock.withLock {
        if (!rawCaptureIngressIsCurrentLocked(token)) return@withLock false
        if (timestampNs <= token.retiredSensorTimestampFloorNs) return@withLock false
        if (timestampNs - lastFrameNs < minFrameIntervalNs) return@withLock false
        lastFrameNs = timestampNs
        true
    }

    private fun offerRawFrameIfCurrent(
        token: RawCaptureIngressToken,
        image: Image
    ): RawFrameOfferResult = rawWriterAdmissionLock.withLock {
        if (!rawCaptureIngressIsCurrentLocked(token)) {
            RawFrameOfferResult.RETIRED
        } else if (offerRawFrameAndRegisterResultLocked(token, image)) {
            RawFrameOfferResult.ACCEPTED
        } else {
            RawFrameOfferResult.FULL
        }
    }

    private fun replaceOldestRawFrameIfCurrent(
        token: RawCaptureIngressToken,
        image: Image
    ): RawFrameReplacementResult = rawWriterAdmissionLock.withLock {
        if (!rawCaptureIngressIsCurrentLocked(token)) {
            return@withLock RawFrameReplacementResult(RawFrameOfferResult.RETIRED, null)
        }
        val evicted = token.session.queue.poll()
        val accepted = resultLock.withLock {
            evicted?.let {
                pendingRawCaptureResultsByTimestamp.remove(it.timestamp)
                recentResultsByTimestamp.remove(it.timestamp)
            }
            val timestamp = image.timestamp
            val existing = pendingRawCaptureResultsByTimestamp[timestamp]
            if (existing != null && existing !== token) {
                false
            } else {
                token.session.queue.offer(image).also { offered ->
                    if (offered) registerAcceptedRawFrameResultLocked(token, image)
                }
            }
        }
        RawFrameReplacementResult(
            offerResult = if (accepted) RawFrameOfferResult.ACCEPTED else RawFrameOfferResult.FULL,
            evicted = evicted
        )
    }

    /**
     * Linearizes the record-monitor callback with stop/restart. Copying may happen off-lock, but the
     * callback can only begin while this exact writer session still owns presentation admission.
     */
    private fun invokeRecordingPreviewProcessorIfCurrent(
        session: RawWriterSessionToken,
        processor: (java.nio.ByteBuffer, RawBufferDescriptor, Int, Int) -> Unit,
        buffer: java.nio.ByteBuffer,
        descriptor: RawBufferDescriptor,
        rowStride: Int
    ) = rawWriterAdmissionLock.withLock {
        if (!destroyed.get() && captureLifecycleActive.get() && isCapturing.get() &&
            session.generation == rawWriterSessionGeneration && session.queue === writeQueue
        ) {
            processor(buffer, descriptor, rowStride, PREVIEW_TAP_DECIMATE)
        }
    }

    /**
     * Begin capturing RAW frames. Creates the session output directory.
     * Must be called after the camera session is active.
     */
    internal fun prepareCaptureAudio(source: AudioSrc) = rawCaptureLifecycleLock.withLock {
        check(!captureLifecycleActive.get()) { "Cannot change the active RAW audio obligation" }
        captureAudio.prepare(source)
        audioPreparedForNextCapture = true
    }

    internal fun startCaptureAudio() = rawCaptureLifecycleLock.withLock {
        check(captureLifecycleActive.get()) { "RAW audio activation has no recording owner" }
        captureAudio.start(firstPersistedSensorTimestampNs.get(), ::reportCaptureAudioFailure)
    }

    internal fun abortCaptureAudio(cause: Throwable) = rawCaptureLifecycleLock.withLock {
        captureAudio.abort(cause)
        audioPreparedForNextCapture = false
    }

    internal fun firstPersistedTimestampNs(): Long = firstPersistedSensorTimestampNs.get()

    private fun reportCaptureAudioFailure(cause: Throwable) {
        captureAudio.markFailed(cause)
        closeRawWriterAdmission()
        _state.value = RawVideoState.Error("Required RAW audio failed: ${cause.message}")
        val listener = recordingAudioFailureListener
        if (listener != null && runCatching { listener(cause) }.isSuccess) return
        // Notification never joins the reporting AAC thread. The serialized writer owner performs
        // finalization after the already accepted RAW packets have drained.
        runCatching { writeExecutor.execute { runCatching { stopCapture() }.onFailure {
            Log.e(TAG, "RAW audio failure cleanup", it)
        } } }.onFailure { Log.e(TAG, "Unable to schedule RAW audio cleanup", it) }
    }

    fun startCapture(selectedFps: Int) = rawCaptureLifecycleLock.withLock {
        try {
            startCaptureLocked(selectedFps)
        } catch (failure: Throwable) {
            runCatching { captureAudio.abort(failure) }.onFailure(failure::addSuppressed)
            audioPreparedForNextCapture = false
            captureCadenceWarmupActive.set(false)
            // A failed long setup must never reopen the gate after stop/destroy or strand RAW ownership.
            closeRawWriterAdmission()
            sensorLinearNdrRecordingLifecycleLock.withLock {
                invalidateRawOriginalExposureAnalyticsLocked()
            }
            if (quiesceRawCaptureWorkers()) {
                drainAndCloseWriteQueue()
                runCatching { directIOEngine?.close() }
                directIOEngine = null
                sensorLinearNdrRecordingLifecycleLock.withLock {
                    finalizeRawCaptureResultIngress()
                    captureLifecycleActive.set(false)
                }
            } else {
                _state.value = RawVideoState.Error(
                    "RAW capture setup failed while a prior worker still owns resources; retry teardown"
                )
                Log.e(TAG, "RAW setup cleanup retained worker/storage ownership for retry", failure)
            }
            throw failure
        }
    }

    private fun startCaptureLocked(selectedFps: Int) {
        require(selectedFps in 1..240) { "Selected RAW capture FPS is out of range: $selectedFps" }
        val claimed = sensorLinearNdrRecordingLifecycleLock.withLock {
            when {
                destroyRequested.get() || destroyed.get() ||
                    sensorLinearNdrRecordingLifecycleActive.get() -> false
                captureLifecycleActive.get() -> false
                else -> {
                    captureLifecycleActive.set(true)
                    true
                }
            }
        }
        if (!claimed) {
            Log.w(TAG, "startCapture() rejected: RAW/NDR lifecycle is already owned")
            return
        }
        if (!audioPreparedForNextCapture) captureAudio.prepare(AudioSrc.NONE)
        audioPreparedForNextCapture = false
        captureAudioPublishedUri = null
        // Recording owns every subsequent RAW buffer. Retire idle-monitor frames before storage/session
        // setup so no stale preview Image consumes reader capacity during the capture transition.
        clearIdleRawPreviewFrames()
        val startRequestElapsedNs = SystemClock.elapsedRealtimeNanos()
        captureStartRequestElapsedNs.set(startRequestElapsedNs)
        activeCaptureFps.set(selectedFps)
        captureGateOpenElapsedNs.set(0L)
        firstPersistedFrameReported.set(false)
        firstPersistedSensorTimestampNs.set(0L)
        lastPersistedSensorTimestampNs.set(0L)
        rawCaptureFirstAcceptedSensorTimestampNs.set(0L)
        storageWriteThroughputMeter.reset()
        // #9/#10 fix: ensure any writeLoop left running by a previous stop-drain timeout has fully exited and
        // its queue is closed out BEFORE we recreate writeQueue / open the engine below. isCapturing is
        // already false here, so the loop can terminate; on a stall this drains+closes the leftover Images
        // instead of leaking them when the fresh @Volatile writeQueue reference is assigned.
        check(quiesceRawCaptureWorkers()) {
            "Previous RAW capture workers did not prove quiescence; refusing queue/storage replacement"
        }
        framesWritten.set(0)
        compressedFrameSequence.set(0)
        check(ownedWriterImages.get() == 0) {
            "Previous RAW Image ownership did not quiesce before capture restart"
        }
        activeWrites.set(0)
        droppedFrameCount.set(0)
        admittedRawFrameCount.set(0L)
        continuityBoundaryStopRequested.set(false)
        rawRecordingStorageBudget = null
        queueHighWatermark.set(0)
        sensorLinearNdrRecordingLifecycleLock.withLock {
            invalidateRawOriginalExposureAnalyticsLocked()
        }
        developSidecarWritten.set(false)
        _frameCount.value = 0
        _droppedFrames.value = 0
        lastPublishedFrameCount = 0
        lastPublishedDroppedCount = 0
        lastDroppedLogElapsedMs = 0L
        compressionMode = RawVideoContractPolicy.resolveVideoCaptureMode(compressionMode)
        tuneCaptureProfile()
        val rawReaderBudget = camera.rawReader?.maxImages ?: DEFAULT_RAW_READER_MAX_IMAGES
        val sensorWhiteLevel = resolveSensorWhiteLevel()
        // Block-range packets own an engine buffer after encoding, not a Camera2 Image. Reserve the
        // four borrowed storage owners only for the wider linear-16 route that truly holds Images
        // until pwrite completes. On <=12-bit sensors this recovers four already-provisioned reader
        // slots for thermal/storage burst absorption without increasing ImageReader memory.
        val borrowedStorageImageBudget = if (
            compressionMode == RawCompressionMode.LOSSLESS_REALTIME &&
            NprawFrameCodec.realtimeLosslessStorageBitDepth(sensorWhiteLevel) > 12
        ) {
            RAW_BORROWED_STORAGE_CAPACITY
        } else {
            0
        }
        writeQueue = ArrayBlockingQueue(
            RawVideoBackpressurePolicy.resolveSafeQueueCapacity(
                requestedQueueCapacity = resolveQueueCapacity(),
                readerMaxImages = rawReaderBudget,
                reservedActiveWrites = 1 + borrowedStorageImageBudget
            )
        )
        val prepared = if (compressionMode == RawCompressionMode.OFF) {
            null
        } else {
            takePreparedStorage(compressionMode)
        }
        val location = prepared?.location ?: createCaptureLocation(compressionMode)
        sessionDir = location.directory
        sessionRelativePath = location.relativePath
        sessionLocationLabel = location.label
        captureAudio.bind(
            File(context.filesDir, "raw_audio_sessions/${java.util.UUID.randomUUID()}/stream.npraw"),
            selectedFps
        )
        compressedStreamUri = prepared?.streamUri
        directIOEngine = prepared?.engine
        lastFrameNs = 0L
        lastPreviewTapNs = 0L
        check(!previewTapBusy.get()) { "RAW preview tap ownership remained active after quiescence" }
        previewTapBuffer = null

        val sessionLocation = sessionLocationLabel.orEmpty()
        _state.value = RawVideoState.Capturing(sessionLocation)
        val sensorCodeBitDepth = NprawFrameCodec.realtimeSensorCodeBitDepth(sensorWhiteLevel)
        val storageBitDepth = if (sensorCodeBitDepth <= 12) {
            sensorCodeBitDepth
        } else {
            NprawFrameCodec.realtimeLosslessStorageBitDepth(sensorWhiteLevel)
        }
        val storageLayout = if (sensorCodeBitDepth <= 12) {
            "BLOCK_RANGE_LOSSLESS_V1"
        } else {
            "LINEAR_$storageBitDepth"
        }
        NeoLogger.i(
            TAG,
            "RAW_CAPTURE_START path=$sessionLocation preparedStorage=${prepared != null} " +
                "mode=$compressionMode pendingUri=${compressedStreamUri != null} " +
                "sourceTransportBits=${NprawFrameCodec.REALTIME_SOURCE_TRANSPORT_BIT_DEPTH} " +
                "sensorCodeBits=$sensorCodeBitDepth sensorWhiteLevel=$sensorWhiteLevel " +
                "storageBits=$storageBitDepth storageLayout=$storageLayout " +
                "sensorCodesBitExact=true"
        )

        // #10 fix: open the Direct I/O engine BEFORE submitting writeLoop (and before opening the frame gate)
        // so the loop's first iterations observe a ready engine via the @Volatile happens-before, instead of
        // racing to the orphan-file fallback for the earliest frames.
        if (compressionMode != RawCompressionMode.OFF) {
            if (directIOEngine == null) {
                val availableStorageBytes = reserveRawStorageForMaster(sessionDir)
                directIOEngine = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    openScopedRawStreamWriter(availableStorageBytes)
                } else {
                    val streamPath = File(sessionDir, "stream.npraw").absolutePath
                    AsyncDirectIOEngine(
                        maxBorrowedPayloadWrites = RAW_BORROWED_STORAGE_CAPACITY
                    ).apply {
                        open(streamPath, availableStorageBytes = availableStorageBytes)
                    }
                }
            }
            directIOEngine?.preallocationResult?.let { result ->
                Log.i(
                    TAG,
                    "NPRAW preallocation method=${result.method} " +
                        "reserved=${result.reservedBytes} requested=${result.plan.requestedBytes} " +
                        "safetyReserve=${result.plan.safetyReserveBytes} reason=${result.plan.reason}"
                )
                if (strictHighEndRawCaptureActive() &&
                    (!result.plan.enabled || result.method != RecordingPreallocationMethod.POSIX_FALLOCATE)
                ) {
                    directIOEngine?.close()
                    directIOEngine = null
                    compressedStreamUri?.let { uri ->
                        runCatching { deletePendingBinary(uri) }
                    }
                    compressedStreamUri = null
                    val message = "Strict NPRAW physical preallocation failed: ${result.failure.orEmpty()}"
                    failStrictRawMaster(message)
                    throw IllegalStateException(message)
                }
            }
            if (directIOEngine == null && strictHighEndRawCaptureActive()) {
                val message = "Strict NPRAW stream writer could not be opened"
                failStrictRawMaster(message)
                throw IllegalStateException(message)
            }
            if (compressionMode == RawCompressionMode.LOSSLESS_REALTIME) {
                if (!prewarmRealtimeWriter(directIOEngine)) {
                    directIOEngine?.close()
                    directIOEngine = null
                    compressedStreamUri?.let { uri -> runCatching { deletePendingBinary(uri) } }
                    compressedStreamUri = null
                    val message = "Realtime NPRAW native encoder or bounded packet buffers are unavailable"
                    failStrictRawMaster(message)
                    throw IllegalStateException(message)
                }
            }
            val writer = directIOEngine
            val reader = camera.rawReader
            if (writer != null && reader != null) {
                val packetBytes = NprawFrameCodec.realtimeLosslessPacketBufferCapacity(
                    width = reader.width,
                    height = reader.height,
                    sensorWhiteLevel = sensorWhiteLevel
                ).toLong()
                val storageBudget = RawRecordingContinuityPolicy.resolveStorageBudget(
                    plan = writer.preallocationResult.plan,
                    framePacketBytes = packetBytes,
                    targetFps = selectedFps
                )
                check(storageBudget.maximumFrames > 0L) {
                    "Insufficient safe storage for one complete RAW frame"
                }
                rawRecordingStorageBudget = storageBudget
                NeoLogger.i(
                    TAG,
                    "RAW_STORAGE_BUDGET frames=${storageBudget.maximumFrames} " +
                        "durationMs=${storageBudget.maximumDurationMs} " +
                        "packetBytes=${storageBudget.framePacketBytes} " +
                        "writableBytes=${storageBudget.writableBytes} " +
                        "safetyReserve=${storageBudget.safetyReserveBytes} " +
                        "admission=ACTUAL_ACCEPTED_BYTES_PLUS_WORST_CASE_IN_FLIGHT"
                )
            }
        }

        // Open the frame-accept gate LAST: writeQueue is now recreated and the session is fully set
        // up, so onRawFrameAvailable can safely enqueue into the queue that writeLoop will drain. Set
        // BEFORE submitting writeLoop so the loop's (isCapturing || queue non-empty) guard sees true
        // on its first check and does not exit immediately.
        val writerSession = openRawWriterAdmission()
        val gateOpenElapsedNs = SystemClock.elapsedRealtimeNanos()
        captureGateOpenElapsedNs.set(gateOpenElapsedNs)
        val writerThrottleCeilingFps = 1_000_000_000.0 / minFrameIntervalNs.coerceAtLeast(1L).toDouble()
        NeoLogger.i(
            TAG,
            "RAW_CAPTURE_GATE_OPEN setupMs=${(gateOpenElapsedNs - startRequestElapsedNs) / 1_000_000L} " +
                "preparedStorage=${prepared != null} writerReady=${directIOEngine != null} " +
                "queueCapacity=${writeQueue.size + writeQueue.remainingCapacity()} " +
                "borrowedStorageImageBudget=$borrowedStorageImageBudget " +
                "writerThrottleCeilingFps=${String.format(Locale.US, "%.2f", writerThrottleCeilingFps)}"
        )

        // Start the write loop on the dedicated executor. Keep the Future so stop/startCapture can await the
        // loop's exit before closing the engine / recreating the queue (#9/#10).
        val writerTask = FutureTask<Unit> { writeLoop(writerSession) }
        val writerHandle = RawWriteLoopHandle(writerTask, writerSession)
        // Publish ownership before execution. A fast task can no longer enter active work while
        // teardown still observes a null Future.
        writeLoopHandle = writerHandle
        try {
            writeExecutor.execute(writerTask)
        } catch (failure: Throwable) {
            if (writeLoopHandle === writerHandle) writeLoopHandle = null
            writerTask.cancel(false)
            throw failure
        }
    }

    /**
     * Suppresses idle RAW demosaic work while the final recording request proves stable cadence.
     * Camera2 buffers are still acquired and closed immediately, preserving ImageReader liveness.
     */
    fun beginCaptureCadenceWarmup() {
        captureCadenceWarmupActive.set(true)
        clearIdleRawPreviewFrames()
    }

    fun endCaptureCadenceWarmup() {
        captureCadenceWarmupActive.set(false)
    }

    /**
     * Stop capturing. Drains the write queue before returning.
     * @return Path to the session directory, or null if never started.
     */
    fun stopCapture(): String? = rawCaptureLifecycleLock.withLock {
        try {
            stopCaptureLocked()
        } catch (failure: Throwable) {
            closeRawWriterAdmission()
            runCatching { captureAudio.abort(failure) }.onFailure(failure::addSuppressed)
            captureAudioPublishedUri?.let { uri ->
                runCatching { deletePendingBinary(uri) }.onFailure(failure::addSuppressed)
            }
            captureAudioPublishedUri = null
            sensorLinearNdrRecordingLifecycleLock.withLock {
                invalidateRawOriginalExposureAnalyticsLocked()
            }
            val workersRetired = quiesceRawCaptureWorkers()
            _state.value = RawVideoState.Error(
                "RAW capture teardown failed safely: ${failure.message.orEmpty()}; " +
                    if (workersRetired) "retry finalization" else "worker ownership retained for retry"
            )
            throw failure
        }
    }

    private fun stopCaptureLocked(): String? {
        if (!captureLifecycleActive.get()) {
            Log.w(TAG, "stopCapture() called when not capturing"); return null
        }
        val stopRequestElapsedNs = SystemClock.elapsedRealtimeNanos()
        val writerQueue = writeLoopHandle?.session?.queue ?: writeQueue
        val queuedAtStop = writerQueue.size
        val activeAtStop = activeWrites.get()
        // Close the frame-accept gate first; writeLoop drains whatever is already queued.
        closeRawWriterAdmission()
        sensorLinearNdrRecordingLifecycleLock.withLock {
            invalidateRawOriginalExposureAnalyticsLocked()
        }
        // ════════════════════════════════════════════════════════════════════
        // OPTIMIZED: Exponential backoff for queue drain
        // Starts with fast polling (responsive), backs off to reduce CPU wake-ups
        // ════════════════════════════════════════════════════════════════════
        // ARCHITECTURAL FIX: Aggressive sub-second drain with 10ms capped backoff
        val maxWaitMs = stopDrainTimeoutMs()
        var drainedMs = 0L
        var backoff = 2L
        while (writerQueue.isNotEmpty() || activeWrites.get() > 0) {
            if (Thread.currentThread().isInterrupted) break
            try {
                Thread.sleep(backoff.coerceAtMost(10L))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt() // preserve interrupt status, then stop draining
                break
            }
            drainedMs += backoff.coerceAtMost(10L)
            backoff = (backoff * 3 / 2 + 1).coerceAtMost(10L)
            if (drainedMs > maxWaitMs) break
        }
        val path = sessionLocationLabel ?: sessionRelativePath ?: sessionDir?.absolutePath
        // #9/#10 fix: guarantee the writeLoop has EXITED before closing the engine, so no in-flight iteration
        // runs against a closed directIOEngine (which would misroute tail frames to orphan files outside
        // stream.npraw) and no lingering loop survives to race the next startCapture's writeQueue. On a storage
        // stall this also closes out any leftover queued RAW Images rather than leaking their HardwareBuffers.
        if (!quiesceRawCaptureWorkers()) {
            val message = "RAW writer/preview tap did not prove quiescence; storage ownership retained"
            _state.value = RawVideoState.Error(message)
            throw IllegalStateException(message)
        }
        // Close Direct I/O only after every camera-side writer has quiesced. Publication is a separate
        // fail-closed transaction: dummy preallocation never substitutes for exact truncate/fsync,
        // zero-loss accounting, or descriptor retirement.
        val completedRawStream = directIOEngine
        val physicalReservationRequired = strictHighEndRawCaptureActive()
        completedRawStream?.close()
        if (compressionMode != RawCompressionMode.OFF) {
            val durability = RawStreamDurabilityPolicy.evaluate(
                writerPresent = completedRawStream != null,
                physicalReservationRequired = physicalReservationRequired,
                preallocation = completedRawStream?.preallocationResult,
                persistedFrames = framesWritten.get(),
                bytesWritten = completedRawStream?.bytesWritten ?: 0L,
                writeOps = completedRawStream?.writeOps ?: 0L,
                droppedWrites = completedRawStream?.dropped ?: 0L,
                applicationDroppedFrames = droppedFrameCount.get(),
                queuedWrites = completedRawStream?.queueDepth ?: 0,
                activeWrites = completedRawStream?.activeWriteCount ?: 0
            )
            if (!durability.publicationSafe) {
                directIOEngine = null
                compressedStreamUri?.let { uri ->
                    runCatching { deletePendingBinary(uri) }
                        .onFailure { Log.e(TAG, "Unable to delete rejected pending NPRAW stream", it) }
                }
                compressedStreamUri = null
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    runCatching { File(sessionDir, "stream.npraw").delete() }
                }
                previewTapBuffer = null
                _state.value = RawVideoState.Error(durability.reason)
                sensorLinearNdrRecordingLifecycleLock.withLock {
                    finalizeRawCaptureResultIngress()
                    captureLifecycleActive.set(false)
                }
                Log.e(TAG, "NPRAW publication refused: ${durability.reason}")
                throw IllegalStateException(durability.reason)
            }
        }
        try {
            if (compressionMode != RawCompressionMode.OFF) {
                check(developSidecarWritten.get()) { "RAW develop metadata was not persisted" }
            }
            val audioAudit = captureAudio.finish(framesWritten.get(), lastPersistedSensorTimestampNs.get())
            if (audioAudit != null && captureAudioPublishedUri == null) {
                captureAudioPublishedUri = publishCaptureAudioSidecar()
            }
        } catch (failure: Throwable) {
            directIOEngine = null
            compressedStreamUri?.let { uri ->
                runCatching { deletePendingBinary(uri) }.onFailure(failure::addSuppressed)
            }
            compressedStreamUri = null
            captureAudioPublishedUri?.let { uri ->
                runCatching { deletePendingBinary(uri) }.onFailure(failure::addSuppressed)
            }
            captureAudioPublishedUri = null
            runCatching { captureAudio.abort(failure) }.onFailure(failure::addSuppressed)
            _state.value = RawVideoState.Error("RAW publication refused: ${failure.message}")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                sessionDir?.let { directory ->
                    listOf("stream.npraw", "stream.npraw.audio.npv10a", "develop.meta").forEach { name ->
                        val ownedFile = File(directory, name)
                        runCatching {
                            check(!ownedFile.exists() || ownedFile.delete()) { "RAW rollback could not remove $name" }
                        }.onFailure(failure::addSuppressed)
                    }
                }
            }
            sensorLinearNdrRecordingLifecycleLock.withLock {
                finalizeRawCaptureResultIngress()
                captureLifecycleActive.set(false)
            }
            throw failure
        }
        directIOEngine = null
        compressedStreamUri?.let { publishPendingBinary(it) }
        compressedStreamUri = null
        captureAudioPublishedUri = null
        captureAudio.discardStaging()
        publishFrameCount(framesWritten.get(), force = true)
        publishDroppedFrameCount(droppedFrameCount.get(), force = true)
        // Release the ~frame-sized record-monitor copy buffer; an in-flight tap keeps its own reference.
        previewTapBuffer = null

        if (_state.value !is RawVideoState.Error) {
            _state.value = RawVideoState.Done(
                frameCount = framesWritten.get(),
                path       = path ?: ""
            )
        }
        val stopCompleteElapsedNs = SystemClock.elapsedRealtimeNanos()
        val gateOpenElapsedNs = captureGateOpenElapsedNs.get()
        val firstSensorTimestampNs = firstPersistedSensorTimestampNs.get()
        val lastSensorTimestampNs = lastPersistedSensorTimestampNs.get()
        val persistedFrames = framesWritten.get()
        val measuredFps = if (persistedFrames > 1 && lastSensorTimestampNs > firstSensorTimestampNs) {
            (persistedFrames - 1).toDouble() * 1_000_000_000.0 /
                (lastSensorTimestampNs - firstSensorTimestampNs).toDouble()
        } else {
            0.0
        }
        NeoLogger.i(
            TAG,
            "RAW_CAPTURE_STOP frames=$persistedFrames path=$path drops=${droppedFrameCount.get()} " +
                "queueHighWatermark=${queueHighWatermark.get()} queuedAtStop=$queuedAtStop " +
                "activeAtStop=$activeAtStop stopFinalizeMs=${(stopCompleteElapsedNs - stopRequestElapsedNs) / 1_000_000L} " +
                "captureMs=${if (gateOpenElapsedNs > 0L) (stopRequestElapsedNs - gateOpenElapsedNs) / 1_000_000L else 0L} " +
                "measuredSensorFps=${String.format(Locale.US, "%.2f", measuredFps)}"
        )
        sensorLinearNdrRecordingLifecycleLock.withLock {
            finalizeRawCaptureResultIngress()
            captureLifecycleActive.set(false)
        }
        allowIdleCaptureStoragePreparation()
        prepareCaptureStorageAsync(RawCompressionMode.LOSSLESS_REALTIME)
        return path
    }

    fun pauseCapture(): Boolean = rawCaptureLifecycleLock.withLock {
        if (!isCapturing.get() || isPaused.get()) return@withLock false
        try {
            captureAudio.pause()
        } catch (failure: Throwable) {
            reportCaptureAudioFailure(failure)
            return@withLock false
        }
        val paused = rawWriterAdmissionLock.withLock {
            if (!isCapturing.get() || isPaused.get()) {
                false
            } else {
                rawCaptureIngressAdmission.set(null)
                rawWriterAdmissionGeneration = nextRawWriterGeneration(rawWriterAdmissionGeneration)
                isPaused.set(true)
                true
            }
        }
        if (paused) {
            // Camera2 admission is already closed; retire analytics before pause returns.
            sensorLinearNdrRecordingLifecycleLock.withLock {
                invalidateRawOriginalExposureAnalyticsLocked()
            }
        }
        if (paused) Log.i(TAG, "RAW custom packet capture paused")
        paused
    }

    fun resumeCapture(): Boolean {
        val lease = beginRawResumeAdmissionLease() ?: return false
        // No manager lock is held while Camera2 stops repeating and waits for its tagged marker.
        // Stop/destroy can therefore invalidate this lease without deadlocking the callback looper.
        val repeatingRestarted = camera.resumeRawVideoCaptureAtExactBoundary(
            timeoutMs = RAW_RESUME_BOUNDARY_TIMEOUT_MS,
            commitAdmission = { boundary ->
                commitRawResumeAdmissionLease(
                    lease = lease,
                    markerTimestampNs = boundary.sensorTimestampNs,
                    markerSessionGeneration = boundary.sessionGeneration
                )
            },
            rollbackAdmission = { rollbackCommittedRawResumeAdmissionLease(lease) }
        )
        val resumed = rawCaptureLifecycleLock.withLock {
            if (!finishRawResumeAdmissionLease(lease, repeatingRestarted)) return@withLock false
            try {
                captureAudio.resume()
                true
            } catch (failure: Throwable) {
                reportCaptureAudioFailure(failure)
                false
            }
        }
        if (resumed) Log.i(TAG, "RAW custom packet capture resumed at an exact Camera2 boundary")
        return resumed
    }

    private fun beginRawResumeAdmissionLease(): RawResumeAdmissionLease? =
        rawCaptureLifecycleLock.withLock {
            sensorLinearNdrRecordingLifecycleLock.withLock {
                invalidateRawOriginalExposureAnalyticsLocked()
                rawWriterAdmissionLock.withLock {
                    val pausedResultIdentity = rawCaptureResultIngressIdentity.get()
                    if (!isCapturing.get() || !isPaused.get() ||
                        rawCaptureIngressAdmission.get() != null ||
                        pausedResultIdentity == null ||
                        pausedResultIdentity.session.generation != rawWriterSessionGeneration ||
                        pausedResultIdentity.session.queue !== writeQueue ||
                        pausedResultIdentity.cameraSessionGeneration != camera.sessionGeneration.get()
                    ) {
                        return@withLock null
                    }
                    val lease = RawResumeAdmissionLease(
                        pausedResultIdentity = pausedResultIdentity,
                        cameraSessionGeneration = pausedResultIdentity.cameraSessionGeneration
                    )
                    if (!rawResumeAdmissionLease.compareAndSet(null, lease)) null else lease
                }
            }
        }

    private fun commitRawResumeAdmissionLease(
        lease: RawResumeAdmissionLease,
        markerTimestampNs: Long,
        markerSessionGeneration: Long
    ): Boolean = rawCaptureLifecycleLock.withLock {
        sensorLinearNdrRecordingLifecycleLock.withLock {
            rawWriterAdmissionLock.withLock {
                val pausedResultIdentity = lease.pausedResultIdentity
                val minimumMarkerTimestampNs = maxOf(
                    pausedResultIdentity.retiredSensorTimestampFloorNs,
                    lastFrameNs,
                    lastPersistedSensorTimestampNs.get()
                )
                if (rawResumeAdmissionLease.get() !== lease ||
                    lease.committedAdmission.get() != null ||
                    !captureLifecycleActive.get() || !isCapturing.get() || !isPaused.get() ||
                    rawCaptureIngressAdmission.get() != null ||
                    rawCaptureResultIngressIdentity.get() !== pausedResultIdentity ||
                    pausedResultIdentity.session.generation != rawWriterSessionGeneration ||
                    pausedResultIdentity.session.queue !== writeQueue ||
                    markerSessionGeneration != lease.cameraSessionGeneration ||
                    markerSessionGeneration != camera.sessionGeneration.get() ||
                    markerTimestampNs <= minimumMarkerTimestampNs
                ) {
                    return@withLock false
                }

                rawWriterAdmissionGeneration = nextRawWriterGeneration(rawWriterAdmissionGeneration)
                val ingressToken = RawCaptureIngressToken(
                    session = pausedResultIdentity.session,
                    admissionGeneration = rawWriterAdmissionGeneration,
                    cameraSessionGeneration = markerSessionGeneration,
                    retiredSensorTimestampFloorNs = markerTimestampNs
                )
                resultLock.withLock {
                    // Preserve exact metadata owned by already-accepted pre-pause Images, but purge
                    // every unowned fallback at/before the marker so a resumed frame cannot inherit it.
                    recentResultsByTimestamp.entries.removeAll { (timestamp, _) ->
                        timestamp <= markerTimestampNs &&
                            pendingRawCaptureResultsByTimestamp[timestamp] == null
                    }
                    val latestTimestampNs = latestResult?.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                    if (latestTimestampNs <= markerTimestampNs) latestResult = null
                }
                lastFrameNs = 0L
                isPaused.set(false)
                // Publish result identity before Image admission. The marker and every older Image
                // are rejected by [admitRawFrameTimestamp]; only strictly newer frames can persist.
                rawCaptureResultIngressIdentity.set(ingressToken)
                rawCaptureIngressAdmission.set(ingressToken)
                lease.committedAdmission.set(ingressToken)
                true
            }
        }
    }

    private fun finishRawResumeAdmissionLease(
        lease: RawResumeAdmissionLease,
        repeatingRestarted: Boolean
    ): Boolean = rawCaptureLifecycleLock.withLock {
        sensorLinearNdrRecordingLifecycleLock.withLock {
            rawWriterAdmissionLock.withLock {
                val committed = lease.committedAdmission.get()
                val success = repeatingRestarted &&
                    rawResumeAdmissionLease.get() === lease &&
                    committed != null &&
                    rawCaptureIngressAdmission.get() === committed &&
                    rawCaptureResultIngressIdentity.get() === committed &&
                    rawCaptureIngressIsCurrentLocked(committed)
                if (success) {
                    rawResumeAdmissionLease.compareAndSet(lease, null)
                    true
                } else {
                    if (committed != null && rawCaptureIngressAdmission.get() === committed) {
                        rawCaptureIngressAdmission.set(null)
                        rawWriterAdmissionGeneration =
                            nextRawWriterGeneration(rawWriterAdmissionGeneration)
                        if (captureLifecycleActive.get() && isCapturing.get()) isPaused.set(true)
                    }
                    rawResumeAdmissionLease.compareAndSet(lease, null)
                    false
                }
            }
        }
    }

    private fun rollbackCommittedRawResumeAdmissionLease(
        lease: RawResumeAdmissionLease
    ) = rawCaptureLifecycleLock.withLock {
        sensorLinearNdrRecordingLifecycleLock.withLock {
            rawWriterAdmissionLock.withLock {
                val committed = lease.committedAdmission.get() ?: return@withLock
                if (rawResumeAdmissionLease.get() === lease &&
                    rawCaptureIngressAdmission.get() === committed
                ) {
                    rawCaptureIngressAdmission.set(null)
                    rawWriterAdmissionGeneration =
                        nextRawWriterGeneration(rawWriterAdmissionGeneration)
                    if (captureLifecycleActive.get() && isCapturing.get()) isPaused.set(true)
                }
            }
        }
    }

    fun writtenFrameCount(): Int = framesWritten.get()

    fun measuredStorageWriteMegabytesPerSecond(
        nowNs: Long = SystemClock.elapsedRealtimeNanos()
    ): Float? = storageWriteThroughputMeter.megabytesPerSecond(nowNs)

    /**
     * Call from CameraController.onRawReady callback.
     * MUST be called on the Camera bgHandler thread (or any thread — it's fast).
     * Acquires a stable image and enqueues it for writing.
     */
    fun onRawFrameAvailable(reader: ImageReader) {
        // Capture exact published route identities at callback entry, before either non-fair lock can
        // be overtaken by stop/start. Double collection detects a transition straddling the snapshot;
        // reference identity prevents ABA even when a later recording reuses the same Camera2 session.
        val callbackObservedRawAdmissionBefore = rawCaptureIngressAdmission.get()
        val callbackObservedNdrAdmissionBefore =
            sensorLinearNdrRecordingIngressAdmission.get()
        val callbackObservedRawAdmissionAfter = rawCaptureIngressAdmission.get()
        val callbackObservedNdrAdmissionAfter =
            sensorLinearNdrRecordingIngressAdmission.get()
        val callbackRouteSnapshotStable =
            callbackObservedRawAdmissionBefore === callbackObservedRawAdmissionAfter &&
                callbackObservedNdrAdmissionBefore === callbackObservedNdrAdmissionAfter
        var rawCaptureIngress: RawCaptureIngressToken? = null
        var sensorLinearNdrIngress: SensorLinearNdrRecordingIngressToken? = null
        var sensorLinearNdrReservationGeneration: Long? = null
        var sensorLinearNdrReservationFailure: String? = null
        var img: Image? = null
        while (true) {
            var capacityWaitAdmission: SensorLinearNdrRecordingIngressToken? = null
            try {
            // Serialize the mode snapshot through acquireNextImage(). Otherwise an idle callback can
            // choose acquireLatestImage(), a recording can open before acquire, and ImageReader may
            // auto-close the first ordered master frames. acquireNextImage is nonblocking; the existing
            // bounded latest-only software handoff still collapses idle monitor work after ownership is
            // explicit, never inside the shared HAL reader.
            img = sensorLinearNdrRecordingLifecycleLock.withLock {
                rawWriterAdmissionLock.withLock {
                    if (callbackRouteSnapshotStable) {
                        rawCaptureIngress = snapshotRawCaptureIngressLocked(
                            callbackObservedRawAdmissionAfter
                        )
                    }
                    if (rawCaptureIngress == null &&
                        callbackRouteSnapshotStable
                    ) {
                        sensorLinearNdrIngress =
                            snapshotSensorLinearNdrRecordingIngressLocked(
                                callbackObservedNdrAdmissionAfter
                            )
                    }
                    val ndrIngress = sensorLinearNdrIngress
                    if (ndrIngress == null) {
                        reader.acquireNextImage()
                    } else {
                        val reservedOwned =
                            SensorLinearNdrRecordingIngressPolicy.tryReserveOwnedFrame(
                                ownedFrames = sensorLinearNdrRecordingOwnedFrames,
                                readerMaxImages = reader.maxImages
                            )
                        if (reservedOwned == null) {
                            capacityWaitAdmission = ndrIngress
                            null
                        } else {
                            // Charge this exact generation before acquisition. Stop/failure can now
                            // observe every legal ImageReader owner, including the callback currently
                            // between acquireNextImage and the ordered metadata join.
                            sensorLinearNdrReservationGeneration = ndrIngress.generation
                            recordSensorLinearNdrOwnedHighWatermark(reservedOwned)
                            val acquired = reader.acquireNextImage()
                            when {
                                acquired == null -> {
                                    releaseSensorLinearNdrOwnershipReservation(ndrIngress.generation)
                                    sensorLinearNdrReservationGeneration = null
                                    null
                                }
                                !sensorLinearNdrExactImageOwnership.register(
                                    acquired,
                                    ndrIngress.generation
                                ) -> {
                                    runCatching { acquired.close() }
                                    releaseSensorLinearNdrOwnershipReservation(ndrIngress.generation)
                                    sensorLinearNdrReservationGeneration = null
                                    sensorLinearNdrReservationFailure =
                                        "Ordered NDR RAW ingress acquired an already-owned Image identity"
                                    null
                                }
                                else -> acquired
                            }
                        }
                    }
                }
            }
            } catch (e: IllegalStateException) {
            sensorLinearNdrReservationGeneration?.let {
                releaseSensorLinearNdrOwnershipReservation(it)
                sensorLinearNdrReservationGeneration = null
            }
            val ndrIngress = sensorLinearNdrIngress
            if (ndrIngress != null) {
                failSensorLinearNdrRecording(
                    reason = "RAW_SENSOR ImageReader saturated before ordered NDR acquire",
                    cause = e,
                    expectedGeneration = ndrIngress.generation
                )
            } else {
                val captureToken = rawCaptureIngress
                val handled = when {
                    captureToken == null -> false
                    compressionMode != RawCompressionMode.OFF ->
                        failStrictRawMasterIfCurrent(
                            captureToken,
                            "RAW reader saturated before acquire",
                            e
                        )
                    else -> markRawDropIfCurrent(
                        captureToken,
                        "RAW reader saturated; dropping incoming frame",
                        e
                    )
                }
                if (!handled) {
                    Log.w(TAG, "Ignoring RAW acquire failure outside the sampled admission", e)
                }
            }
                return
            }

            val waitAdmission = capacityWaitAdmission
            if (waitAdmission == null) break
            val waitIndex = sensorLinearNdrRecordingCapacityWaits.incrementAndGet()
            if (waitIndex == 1L || waitIndex % 60L == 0L) {
                NeoLogger.i(
                    TAG,
                    "NDR_RECORD_INGRESS_BACKPRESSURE generation=${waitAdmission.generation} " +
                        "waits=$waitIndex owned=${sensorLinearNdrRecordingOwnedFrames.get()} " +
                        "limit=${SensorLinearNdrRecordingIngressPolicy.ownedFrameLimit(reader.maxImages)} " +
                        "fifo=${sensorLinearNdrRecordingQueue.size}"
                )
            }
            // Capacity pressure is not a dropped/admitted frame and is not a native failure. Leave
            // this callback's Image queued inside ImageReader, let the recorder retire an exact owner,
            // then revalidate the immutable admission token before reserving and acquiring it.
            scheduleSensorLinearNdrRecordingWorker()
            when (sensorLinearNdrRecordingCapacityGate.awaitCapacity(
                ownedFrames = sensorLinearNdrRecordingOwnedFrames,
                readerMaxImages = reader.maxImages,
                waitSliceNs = SensorLinearNdrRecordingIngressPolicy.capacityWaitSliceNs(
                    sensorLinearNdrRecordingTargetFps
                ),
                admissionIsCurrent = {
                    sensorLinearNdrRecordingIngressAdmission.get() === waitAdmission &&
                        sensorLinearNdrRecordingLifecycleActive.get() &&
                        sensorLinearNdrRecordingAccepting.get() &&
                        !sensorLinearNdrRecordingDiscardRequested.get() &&
                        sensorLinearNdrRecordingGeneration.get() == waitAdmission.generation
                }
            )) {
                SensorLinearNdrCapacityWaitResult.AVAILABLE,
                SensorLinearNdrCapacityWaitResult.STILL_FULL -> continue
                SensorLinearNdrCapacityWaitResult.ADMISSION_CLOSED -> return
                SensorLinearNdrCapacityWaitResult.INTERRUPTED -> {
                    failSensorLinearNdrRecording(
                        reason = "Ordered NDR RAW ingress capacity wait was interrupted",
                        expectedGeneration = waitAdmission.generation
                    )
                    return
                }
            }
        }

        val reservationFailure = sensorLinearNdrReservationFailure
        if (reservationFailure != null) {
            val ndrIngress = sensorLinearNdrIngress
            if (ndrIngress != null) {
                failSensorLinearNdrRecording(
                    reason = reservationFailure,
                    expectedGeneration = ndrIngress.generation
                )
            }
            return
        }
        if (img == null) return

        val capturing = rawCaptureIngress != null
        val sensorLinearNdrRecording = sensorLinearNdrIngress != null

        if (sensorLinearNdrRecording) {
            val reservedGeneration = sensorLinearNdrReservationGeneration
            if (reservedGeneration == null) {
                runCatching { img.close() }
                failSensorLinearNdrRecording(
                    reason = "Ordered NDR RAW Image was acquired without an ownership reservation",
                    expectedGeneration = sensorLinearNdrIngress.generation
                )
                return
            }
            pairOrHoldSensorLinearNdrRecording(
                image = img,
                readerMaxImages = reader.maxImages,
                ingressToken = sensorLinearNdrIngress,
                ownershipReservationGeneration = reservedGeneration
            )
            return
        }

        if (!capturing) {
            if (captureCadenceWarmupActive.get()) {
                img.close()
                return
            }
            // A RAW writer lifecycle with closed Image admission is paused, starting, stopping, or
            // waiting for its exact resume marker. Such buffers own no idle-preview generation and
            // must be released immediately; retaining them in the idle join can exhaust reader depth
            // and can strand the marker Image when its result is intentionally rejected at the floor.
            // Serialize the final idle admission with NDR start. A callback that sampled idle before
            // recorder admission opened either publishes under this lock (and start subsequently
            // clears it) or observes the active recorder and closes immediately. It can never become
            // an unmetered ImageReader owner beside the protected NDR budget.
            val admittedToIdlePreview = sensorLinearNdrRecordingLifecycleLock.withLock {
                if (captureLifecycleActive.get() ||
                    sensorLinearNdrRecordingLifecycleActive.get() ||
                    sensorLinearNdrRecordingIngressAdmission.get() != null
                ) {
                    false
                } else {
                    pairOrHoldIdleRawPreview(img)
                    true
                }
            }
            if (!admittedToIdlePreview) img.close()
            return
        }

        // Pause/stop may close admission after the initial mode snapshot but before acquire returns.
        // Never enqueue or meter that boundary frame under the retired control generation.
        val captureToken = rawCaptureIngress

        // Frame rate throttle — drop frames above MAX_FRAME_RATE
        val ts = img.timestamp
        if (!admitRawFrameTimestamp(captureToken, ts)) {
            img.close(); return
        }

        if (stopBeforeRawFrameAtContinuityBoundaryIfRequired(
                token = captureToken,
                image = img,
                readerMaxImages = reader.maxImages
            )
        ) {
            img.close()
            return
        }

        // Reserve only a tiny timestamp token here. Sensor-code sampling occurs after master encode/write
        // on the writer thread, so Camera2 ingress and protected writer admission stay first priority.
        val rawExposureMeterJoin = maybeReserveRawOriginalExposureMeter(img)

        if (
            !RawVideoBackpressurePolicy.shouldRetainAcquiredFrame(
                readerMaxImages = reader.maxImages,
                queuedImages = captureToken.session.queue.size,
                activeWrites = ownedWriterImages.get()
            )
        ) {
            if (strictHighEndRawCaptureActive()) {
                // Never wait inside ImageReader/Camera2 delivery. If the protected recording queue cannot
                // accept immediately, fail the strict clip with the exact storage-pressure reason rather
                // than stalling sensor delivery and cascading the loss into every camera output.
                when (offerRawFrameIfCurrent(captureToken, img)) {
                    RawFrameOfferResult.ACCEPTED -> {
                        updateQueueHighWatermark(captureToken.session.queue.size)
                        return
                    }
                    RawFrameOfferResult.RETIRED -> {
                        cancelRawOriginalExposureMeter(rawExposureMeterJoin)
                        img.close()
                        return
                    }
                    RawFrameOfferResult.FULL ->
                        failStrictRawMasterIfCurrent(
                            captureToken,
                            "RAW reader budget exhausted; protected queue rejected ts=$ts"
                        )
                }
            } else {
                markRawDropIfCurrent(
                    captureToken,
                    "RAW reader budget reserved to avoid maxImages saturation; dropping incoming frame at ts=$ts"
                )
            }
            cancelRawOriginalExposureMeter(rawExposureMeterJoin)
            img.close()
            return
        }

        // Non-blocking enqueue — drop frame if writer is behind
        when (offerRawFrameIfCurrent(captureToken, img)) {
            RawFrameOfferResult.ACCEPTED -> {
                updateQueueHighWatermark(captureToken.session.queue.size)
                return
            }
            RawFrameOfferResult.RETIRED -> {
                cancelRawOriginalExposureMeter(rawExposureMeterJoin)
                img.close()
                return
            }
            RawFrameOfferResult.FULL -> Unit
        }

        if (shouldEvictOldestOnOverflow && !strictHighEndRawCaptureActive()) {
            val replacement = replaceOldestRawFrameIfCurrent(captureToken, img)
            val evicted = replacement.evicted
            if (evicted != null) {
                cancelRawOriginalExposureMeter(evicted.timestamp)
                evicted.close()
                markRawDropIfCurrent(
                    captureToken,
                    "RAW write queue overflow — evicting oldest frame"
                )
            }
            when (replacement.offerResult) {
                RawFrameOfferResult.ACCEPTED -> {
                    updateQueueHighWatermark(captureToken.session.queue.size)
                    return
                }
                RawFrameOfferResult.RETIRED -> {
                    cancelRawOriginalExposureMeter(rawExposureMeterJoin)
                    img.close()
                    return
                }
                RawFrameOfferResult.FULL -> Unit
            }
        }

        when (offerRawFrameIfCurrent(captureToken, img)) {
            RawFrameOfferResult.ACCEPTED -> {
                updateQueueHighWatermark(captureToken.session.queue.size)
                return
            }
            RawFrameOfferResult.RETIRED -> {
                cancelRawOriginalExposureMeter(rawExposureMeterJoin)
                img.close()
            }
            RawFrameOfferResult.FULL -> {
            if (strictHighEndRawCaptureActive()) {
                failStrictRawMasterIfCurrent(
                    captureToken,
                    "RAW write queue saturated; protected queue rejected ts=$ts"
                )
            } else {
                markRawDropIfCurrent(
                    captureToken,
                    "Write queue saturated — dropping incoming RAW frame at ts=$ts"
                )
            }
            cancelRawOriginalExposureMeter(rawExposureMeterJoin)
            img.close()
            }
        }
    }

    /** Camera callback ingress for the ordered sensor-linear NDR recording master. */
    private fun pairOrHoldSensorLinearNdrRecording(
        image: Image,
        readerMaxImages: Int,
        ingressToken: SensorLinearNdrRecordingIngressToken,
        ownershipReservationGeneration: Long
    ) {
        val arrivedAtElapsedNs = SystemClock.elapsedRealtimeNanos()
        val generation = ingressToken.generation
        val actualOwnedLimit =
            SensorLinearNdrRecordingIngressPolicy.ownedFrameLimit(readerMaxImages)
        val timestamp = runCatching { image.timestamp }.getOrDefault(0L)
        var owned = 0
        var budgetExceeded = false
        var reservationInvalid = false
        var failure: String? = null
        var queued = false
        var admissionClosed = false
        var cutoverRetired = false
        var activeSessionMismatch = false
        var expectedSessionForFailure = -1L
        var currentSessionForFailure = -1L
        var failedImageClaim: SensorLinearNdrOwnedImage? = null
        var diagnosticSequence = 0L
        var diagnosticDisposition = "UNCLASSIFIED"
        var diagnosticPendingImages = -1
        var diagnosticPendingResults = -1
        var diagnosticReadyFrames = -1
        sensorLinearNdrRecordingLifecycleLock.withLock {
            val expectedSessionGeneration = sensorLinearNdrRecordingSessionGeneration.get()
            val currentSessionGeneration = camera.sessionGeneration.get()
            activeSessionMismatch =
                sensorLinearNdrRecordingLifecycleActive.get() &&
                    sensorLinearNdrRecordingAccepting.get() &&
                    !sensorLinearNdrRecordingDiscardRequested.get() &&
                    generation == sensorLinearNdrRecordingGeneration.get() &&
                    expectedSessionGeneration >= 0L &&
                    expectedSessionGeneration != currentSessionGeneration
            if (activeSessionMismatch) {
                expectedSessionForFailure = expectedSessionGeneration
                currentSessionForFailure = currentSessionGeneration
            }
            if (destroyRequested.get() ||
                !sensorLinearNdrRecordingAccepting.get() ||
                sensorLinearNdrRecordingDiscardRequested.get() ||
                !sensorLinearNdrRecordingLifecycleActive.get() ||
                generation <= 0L || generation != sensorLinearNdrRecordingGeneration.get() ||
                sensorLinearNdrRecordingIngressAdmission.get() !== ingressToken ||
                expectedSessionGeneration < 0L ||
                expectedSessionGeneration != currentSessionGeneration
            ) {
                admissionClosed = true
                diagnosticDisposition = "DISCARDED_ADMISSION_CLOSED"
            } else {
                sensorLinearNdrRecordingOwnedFrameLimit.set(actualOwnedLimit)
                owned = sensorLinearNdrRecordingOwnedFrames.get()
                reservationInvalid = ownershipReservationGeneration != generation || owned <= 0
                budgetExceeded = reservationInvalid || owned > actualOwnedLimit
                if (budgetExceeded) diagnosticDisposition = "DISCARDED_OWNERSHIP_BUDGET"
                if (!budgetExceeded) {
                    if (timestamp > 0L &&
                        timestamp <= ingressToken.cutoverTimestampFloorNs
                    ) {
                        // Camera2 completed this exposure before the serial recording cutover, but
                        // ImageReader delivered its buffer afterward. Return its reservation before
                        // allocating an ordered sequence so frame zero has no permanent prefix hole.
                        cutoverRetired = true
                        diagnosticDisposition = "RETIRED_PRE_RECORD_CUTOVER"
                    } else resultLock.withLock {
                        val sequence = sensorLinearNdrNextImageSequence++
                        diagnosticSequence = sequence
                        val presentationEpoch = previewPresentationGate.snapshot().epoch
                        val exactResult = sensorLinearNdrRecordingResults.remove(timestamp)
                        if (exactResult != null) {
                            if (exactResult.sessionGeneration != expectedSessionGeneration) {
                                diagnosticDisposition = "FAILED_STALE_RESULT_SESSION"
                                failure = "Stale Camera2 session metadata for ordered NDR " +
                                    "timestamp=$timestamp resultSession=${exactResult.sessionGeneration} " +
                                    "expectedSession=$expectedSessionGeneration"
                                failedImageClaim = SensorLinearNdrOwnedImage(image, generation)
                            } else {
                                diagnosticDisposition = "JOINED_RESULT_FIRST"
                                val frame = SensorLinearNdrRecordingFrame(
                                    sequence = sequence,
                                    image = image,
                                    result = exactResult.result,
                                    ingressGeneration = generation,
                                    presentationEpoch = exactResult.presentationEpoch,
                                    sessionGeneration = exactResult.sessionGeneration
                                )
                                sensorLinearNdrReadyBySequence[sequence] = frame
                                failure = flushSensorLinearNdrReadyFramesLocked()
                                queued = failure == null
                                if (failure != null && SensorLinearNdrJoinFailureOwnership.claimForRelease(
                                        transferredToJoin = true,
                                        sequence = sequence,
                                        expectedOwner = image,
                                        readyBySequence = sensorLinearNdrReadyBySequence,
                                        ownerOf = SensorLinearNdrRecordingFrame::image
                                    )
                                ) {
                                    failedImageClaim = SensorLinearNdrOwnedImage(image, generation)
                                }
                            }
                        } else {
                            val previous = sensorLinearNdrPendingImages[timestamp]
                            if (previous == null) {
                                diagnosticDisposition = "STORED_IMAGE_FIRST"
                                sensorLinearNdrPendingImages[timestamp] = SensorLinearNdrPendingImage(
                                    sequence = sequence,
                                    image = image,
                                    ingressGeneration = generation,
                                    presentationEpoch = presentationEpoch
                                )
                            } else {
                                diagnosticDisposition = "FAILED_DUPLICATE_IMAGE_TIMESTAMP"
                                failure = "Duplicate RAW_SENSOR timestamp in ordered NDR ingress: $timestamp"
                                if (SensorLinearNdrJoinFailureOwnership.claimForRelease(
                                        transferredToJoin = false,
                                        sequence = sequence,
                                        expectedOwner = image,
                                        readyBySequence = sensorLinearNdrReadyBySequence,
                                        ownerOf = SensorLinearNdrRecordingFrame::image
                                    )
                                ) {
                                    failedImageClaim = SensorLinearNdrOwnedImage(image, generation)
                                }
                            }
                        }
                        diagnosticPendingImages = sensorLinearNdrPendingImages.size
                        diagnosticPendingResults = sensorLinearNdrRecordingResults.size
                        diagnosticReadyFrames = sensorLinearNdrReadyBySequence.size
                    }
                }
            }
        }

        logSensorLinearNdrImageJoinDiagnostic(
            generation = generation,
            sequence = diagnosticSequence,
            timestamp = timestamp,
            arrivedAtElapsedNs = arrivedAtElapsedNs,
            disposition = diagnosticDisposition,
            owned = owned,
            ownedLimit = actualOwnedLimit,
            pendingImages = diagnosticPendingImages,
            pendingResults = diagnosticPendingResults,
            readyFrames = diagnosticReadyFrames
        )

        if (admissionClosed) {
            // Acquisition was charged before ImageReader ownership transfer. Retire that exact
            // reservation even when stop/failure closed admission before the metadata join.
            releaseSensorLinearNdrImage(image, ownershipReservationGeneration)
            if (sensorLinearNdrRecordingLifecycleActive.get() &&
                !sensorLinearNdrRecordingAccepting.get()
            ) {
                scheduleSensorLinearNdrRecordingWorker()
            }
            if (activeSessionMismatch) {
                failSensorLinearNdrRecording(
                    reason = "Camera2 session advanced during ordered NDR ingress " +
                        "expected=$expectedSessionForFailure current=$currentSessionForFailure",
                    sensorTimestampNs = timestamp.takeIf { it > 0L },
                    expectedGeneration = generation
                )
            }
            return
        }
        if (budgetExceeded) {
            releaseSensorLinearNdrImage(image, ownershipReservationGeneration)
            failSensorLinearNdrRecording(
                reason = if (reservationInvalid) {
                    "Ordered NDR RAW ingress ownership reservation was invalid " +
                        "reservedGeneration=$ownershipReservationGeneration " +
                        "ingressGeneration=$generation owned=$owned"
                } else {
                    "Ordered NDR RAW ingress exhausted ImageReader hard ceiling " +
                        "owned=$owned limit=$actualOwnedLimit maxImages=$readerMaxImages"
                },
                sensorTimestampNs = timestamp.takeIf { it > 0L },
                expectedGeneration = generation
            )
            return
        }
        if (cutoverRetired) {
            sensorLinearNdrRecordingCutoverRetiredFrames.incrementAndGet()
            releaseSensorLinearNdrImage(image, ownershipReservationGeneration)
            return
        }

        val failureReason = failure
        if (failureReason != null) {
            // The exact release permit was claimed while the join lock was still held. Stop therefore
            // cannot remove the same Image between a non-atomic ownership check and this close.
            failedImageClaim?.let(::releaseSensorLinearNdrImage)
            failSensorLinearNdrRecording(
                reason = failureReason,
                sensorTimestampNs = timestamp,
                expectedGeneration = generation
            )
            return
        }
        if (queued) scheduleSensorLinearNdrRecordingWorker()
    }

    /** Moves only a contiguous sensor-order prefix into the bounded FIFO. Caller holds resultLock. */
    private fun flushSensorLinearNdrReadyFramesLocked(): String? {
        while (true) {
            val frame = sensorLinearNdrReadyBySequence[sensorLinearNdrNextQueueSequence] ?: return null
            if (!sensorLinearNdrRecordingQueue.offer(frame)) {
                return "Ordered NDR recording FIFO rejected sequence=${frame.sequence} " +
                    "depth=${sensorLinearNdrRecordingQueue.size} " +
                    "capacity=${sensorLinearNdrRecordingQueue.size + sensorLinearNdrRecordingQueue.remainingCapacity()}"
            }
            sensorLinearNdrReadyBySequence.remove(sensorLinearNdrNextQueueSequence)
            sensorLinearNdrNextQueueSequence += 1L
        }
    }

    private fun scheduleSensorLinearNdrRecordingWorker(delayNs: Long = 0L) {
        // Destruction first closes admission, then uses this same worker to retire an accepted native
        // tail. Do not gate retirement on [destroyRequested]/[destroyed]; the executor is shut down only
        // after the lifecycle is physically quiescent.
        if (!sensorLinearNdrRecordingLifecycleActive.get()) return
        val generation = sensorLinearNdrRecordingGeneration.get()
        if (!sensorLinearNdrRecordingWorkerScheduled.compareAndSet(false, true)) return
        try {
            val task = Runnable(::drainSensorLinearNdrRecordingFrames)
            if (delayNs > 0L) {
                sensorLinearNdrRecordingExecutor.schedule(task, delayNs, TimeUnit.NANOSECONDS)
            } else {
                sensorLinearNdrRecordingExecutor.execute(task)
            }
        } catch (failure: Throwable) {
            markSensorLinearNdrRecordingWorkerUnscheduled()
            failSensorLinearNdrRecording(
                reason = "Unable to schedule sensor-linear NDR recording worker",
                cause = failure,
                expectedGeneration = generation,
                scheduleWorker = false
            )
        }
    }

    private fun drainSensorLinearNdrRecordingFrames() {
        var retryDelayNs: Long? = null
        val workerGeneration = sensorLinearNdrRecordingGeneration.get()
        try {
            while (sensorLinearNdrRecordingLifecycleActive.get() &&
                workerGeneration == sensorLinearNdrRecordingGeneration.get()
            ) {
                // Feed a waiting/retry frame through the ring-aware processor before standalone
                // completion publication. The exact Image remains in the retry slot while either the
                // preceding native dispatch or independent preview transport owns capacity. Once both
                // are ready, that same Image enters Vulkan without relabelling. Poll directly only when
                // no retry/next Image exists (or while draining the final native tail at EOS).
                if (sensorLinearNdrRecordingNativePending.get() &&
                    sensorLinearNdrRecordingRetryFrame.get() == null &&
                    sensorLinearNdrRecordingQueue.isEmpty()
                ) {
                    val completionPoller = sensorLinearNdrRecordingCompletionPoller
                    if (completionPoller == null) {
                        // Losing the poller is not proof that a native fence retired. Fail closed and
                        // retain physical ownership; a teardown retry may restore the immutable owner.
                        failSensorLinearNdrRecording(
                            reason = "NDR native completion owner disappeared",
                            expectedGeneration = workerGeneration
                        )
                        retryDelayNs = SensorLinearNdrRecordingIngressPolicy.retryPollDelayNs(
                            sensorLinearNdrRecordingTargetFps
                        )
                        return
                    }
                    val completion = completionPoller.invoke()
                    when (completion.status) {
                        SensorLinearNdrRecordingCompletionStatus.COMPLETED -> {
                            sensorLinearNdrRecordingNativePending.set(false)
                            clearSensorLinearNdrRetryWindow()
                            continue
                        }
                        SensorLinearNdrRecordingCompletionStatus.IDLE -> {
                            // IDLE/FAILED are terminal native observations. Clear the physical-tail bit
                            // before publishing failure, then let this worker own final lifecycle close.
                            sensorLinearNdrRecordingNativePending.set(false)
                            clearSensorLinearNdrRetryWindow()
                            failSensorLinearNdrRecording(
                                reason = "NDR native dispatch became idle before its recorder completion",
                                expectedGeneration = workerGeneration
                            )
                            continue
                        }
                        SensorLinearNdrRecordingCompletionStatus.PENDING -> {
                            transitionSensorLinearNdrRetryWindow(
                                SensorLinearNdrRecordingRetryCause.NATIVE_OUTPUT
                            )
                            retryDelayNs = sensorLinearNdrRetryDelayOrFailure(
                                frame = sensorLinearNdrRecordingRetryFrame.get(),
                                expectedGeneration = workerGeneration
                            )
                            return
                        }
                        SensorLinearNdrRecordingCompletionStatus.FAILED -> {
                            sensorLinearNdrRecordingNativePending.set(false)
                            clearSensorLinearNdrRetryWindow()
                            failSensorLinearNdrRecording(
                                reason = completion.reason ?: "NDR native completion failed",
                                expectedGeneration = workerGeneration
                            )
                            continue
                        }
                    }
                }

                val frame = sensorLinearNdrRecordingRetryFrame.getAndSet(null)
                    ?: sensorLinearNdrRecordingQueue.poll()
                if (frame != null) {
                    if (sensorLinearNdrRecordingDiscardRequested.get()) {
                        // RETRY_PENDING means this Image was never submitted. Once discard wins the
                        // admission race, close it and preserve any older accepted native tail.
                        releaseSensorLinearNdrRecordingFrame(frame)
                        continue
                    }
                    if (frame.ingressGeneration != workerGeneration) {
                        val sensorTimestampNs = runCatching { frame.image.timestamp }.getOrNull()
                        releaseSensorLinearNdrRecordingFrame(frame)
                        failSensorLinearNdrRecording(
                            reason = "Stale NDR recording ingress generation for sequence=${frame.sequence}",
                            sensorTimestampNs = sensorTimestampNs,
                            expectedGeneration = workerGeneration
                        )
                        continue
                    }
                    val submission = try {
                        processSensorLinearNdrRecordingFrame(frame)
                    } catch (failure: Throwable) {
                        val sensorTimestampNs = runCatching { frame.image.timestamp }.getOrNull()
                        releaseSensorLinearNdrRecordingFrame(frame)
                        failSensorLinearNdrRecording(
                            reason = "NDR frame processor crashed for sequence=${frame.sequence}: " +
                                (failure.message ?: failure.javaClass.simpleName),
                            sensorTimestampNs = sensorTimestampNs,
                            cause = failure,
                            expectedGeneration = workerGeneration
                        )
                        continue
                    }
                    if (submission == null) {
                        // A discard/failure request won while descriptor preparation was running. The
                        // native processor was never called, so this worker still owns the Image.
                        releaseSensorLinearNdrRecordingFrame(frame)
                        continue
                    }
                    when (submission.status) {
                        SensorLinearNdrRecordingSubmissionStatus.ACCEPTED -> {
                            if (!sensorLinearNdrRecordingDiscardRequested.get() &&
                                !frame.exposureAnalyticsQueued
                            ) {
                                frame.exposureAnalyticsQueued = true
                                frame.preparedDescriptor?.let { descriptor ->
                                    try {
                                        queueSensorLinearExposureMetrics(
                                            image = frame.image,
                                            descriptor = descriptor,
                                            result = frame.result,
                                            ingressGeneration = frame.ingressGeneration,
                                            sessionGeneration = frame.sessionGeneration,
                                            requiresNdrLifecycle = true
                                        )
                                    } catch (failure: Throwable) {
                                        // This observer is never allowed to reverse processor admission.
                                        Log.w(TAG, "Skipping sensor-linear exposure analytics", failure)
                                    }
                                }
                            }
                            releaseSensorLinearNdrRecordingFrame(frame)
                        }
                        SensorLinearNdrRecordingSubmissionStatus.RETRY_PENDING -> {
                            // Publication of the retry slot and the native-tail bit occurs atomically
                            // with the processor call. Stop/failure may already have claimed the slot.
                            val retainForRetry = sensorLinearNdrRecordingRetryFrame.get() === frame
                            retryDelayNs = sensorLinearNdrRetryDelayOrFailure(
                                frame = frame.takeIf { retainForRetry },
                                expectedGeneration = workerGeneration
                            )
                            return
                        }
                        SensorLinearNdrRecordingSubmissionStatus.FAILED -> {
                            val sensorTimestampNs = runCatching { frame.image.timestamp }.getOrNull()
                            releaseSensorLinearNdrRecordingFrame(frame)
                            failSensorLinearNdrRecording(
                                reason = submission.reason
                                    ?: "NDR GPU/encoder submission failed for sequence=${frame.sequence}",
                                sensorTimestampNs = sensorTimestampNs,
                                expectedGeneration = workerGeneration
                            )
                            continue
                        }
                    }
                    continue
                }

                if (!sensorLinearNdrRecordingAccepting.get() &&
                    sensorLinearNdrRecordingOwnedFrames.get() == 0 &&
                    !sensorLinearNdrRecordingNativePending.get()
                ) {
                    finishSensorLinearNdrRecordingIngress(
                        expectedGeneration = workerGeneration
                    )
                }
                return
            }
        } catch (failure: Throwable) {
            failSensorLinearNdrRecording(
                reason = "Sensor-linear NDR recording worker crashed: ${failure.message ?: failure.javaClass.simpleName}",
                cause = failure,
                expectedGeneration = workerGeneration
            )
        } finally {
            markSensorLinearNdrRecordingWorkerUnscheduled()
            if (sensorLinearNdrRecordingLifecycleActive.get() &&
                workerGeneration == sensorLinearNdrRecordingGeneration.get()
            ) {
                val delay = retryDelayNs
                when {
                    delay != null -> scheduleSensorLinearNdrRecordingWorker(delay)
                    sensorLinearNdrRecordingRetryFrame.get() != null ||
                        sensorLinearNdrRecordingQueue.isNotEmpty() ->
                        scheduleSensorLinearNdrRecordingWorker()
                    sensorLinearNdrRecordingNativePending.get() ->
                        scheduleSensorLinearNdrRecordingWorker(
                            SensorLinearNdrRecordingIngressPolicy.retryPollDelayNs(
                                sensorLinearNdrRecordingTargetFps
                            )
                        )
                    // Close the scheduling handoff: if stop flips admission after this worker's empty
                    // poll but before the scheduled bit is cleared, the terminal request itself is work.
                    !sensorLinearNdrRecordingAccepting.get() &&
                        sensorLinearNdrRecordingOwnedFrames.get() == 0 ->
                        scheduleSensorLinearNdrRecordingWorker()
                }
            }
        }
    }

    private fun processSensorLinearNdrRecordingFrame(
        frame: SensorLinearNdrRecordingFrame
    ): SensorLinearNdrRecordingSubmission? {
        val processor = sensorLinearNdrRecordingProcessor
            ?: return SensorLinearNdrRecordingSubmission(
                SensorLinearNdrRecordingSubmissionStatus.FAILED,
                reason = "NDR recording processor is unavailable"
            )
        val recordingDescriptor = frame.preparedDescriptor ?: run {
            val exactDescriptor = buildRawBufferDescriptor(frame.image, frame.result)
                ?: return SensorLinearNdrRecordingSubmission(
                    SensorLinearNdrRecordingSubmissionStatus.FAILED,
                    reason = "Exact RAW metadata descriptor is unavailable for sequence=${frame.sequence}"
                )
            stabilizeSensorLinearNdrRecordingDescriptor(
                exact = exactDescriptor,
                result = frame.result,
                presentationEpoch = frame.presentationEpoch,
                sessionGeneration = frame.sessionGeneration
            )?.also { prepared -> frame.preparedDescriptor = prepared }
                ?: return SensorLinearNdrRecordingSubmission(
                    SensorLinearNdrRecordingSubmissionStatus.FAILED,
                    reason = "Stable RAW color metadata is unavailable for sequence=${frame.sequence}"
                )
        }
        val frameIdentity = SensorLinearNdrRecordingFrameIdentity(
            sequence = frame.sequence,
            ingressGeneration = frame.ingressGeneration,
            sessionGeneration = frame.sessionGeneration,
            cameraFrameNumber = frame.result.frameNumber,
            sensorTimestampNs = runCatching { frame.image.timestamp }.getOrDefault(0L)
        )

        val processorStartNs = System.nanoTime()
        sensorLinearNdrRecordingLifecycleLock.withLock {
            val expectedSessionGeneration = sensorLinearNdrRecordingSessionGeneration.get()
            if (!sensorLinearNdrRecordingLifecycleActive.get() ||
                frame.ingressGeneration != sensorLinearNdrRecordingGeneration.get() ||
                sensorLinearNdrRecordingDiscardRequested.get()
            ) {
                return null
            }
            val currentSessionGeneration = camera.sessionGeneration.get()
            if (expectedSessionGeneration < 0L ||
                frame.sessionGeneration != expectedSessionGeneration ||
                expectedSessionGeneration != currentSessionGeneration
            ) {
                return SensorLinearNdrRecordingSubmission(
                    status = SensorLinearNdrRecordingSubmissionStatus.FAILED,
                    reason = "Camera2 session lineage changed before NDR native submission " +
                        "frame=${frame.sessionGeneration} expected=$expectedSessionGeneration " +
                        "current=$currentSessionGeneration sequence=${frame.sequence}"
                )
            }

            if (!sensorLinearNdrRecordingProcessorInFlight.compareAndSet(null, frame)) {
                return SensorLinearNdrRecordingSubmission(
                    status = SensorLinearNdrRecordingSubmissionStatus.FAILED,
                    reason = "NDR processor already owns an in-flight frame before " +
                        "sequence=${frame.sequence}"
                )
            }
            sensorLinearNdrRecordingProcessorInFlightSinceNs.set(processorStartNs)
            // This conservative tail bit covers the JNI call itself. Final status publication below
            // replaces it before the in-flight lease is cleared, so stop sees either a processor owner
            // or the exact native/retry state and can never overtake encoder ownership.
            sensorLinearNdrRecordingNativePending.set(true)
        }

        val submission = try {
            // Deliberately outside sensorLinearNdrRecordingLifecycleLock. A slow GPU/EGL submission
            // must not prevent Camera2 RAW and TotalCaptureResult callbacks from completing their
            // exact timestamp join. The explicit in-flight lease remains the teardown barrier.
            processor(
                frame.image,
                recordingDescriptor,
                frame.presentationEpoch,
                frameIdentity
            )
        } catch (failure: Throwable) {
            val durationNs = (System.nanoTime() - processorStartNs).coerceAtLeast(0L)
            sensorLinearNdrRecordingLifecycleLock.withLock {
                // JNI may have accepted work before throwing. Retain conservative nativePending and
                // its deadline until the completion poller proves a terminal state; clear only the
                // Java call lease so the worker can enter that poll path.
                sensorLinearNdrRecordingProcessorInFlight.compareAndSet(frame, null)
                sensorLinearNdrRecordingProcessorInFlightSinceNs.set(0L)
                // A throw may occur after JNI accepted ownership. Start a conservative physical-tail
                // deadline at this invocation without perturbing healthy same-cause retry windows.
                transitionSensorLinearNdrRetryWindow(
                    cause = SensorLinearNdrRecordingRetryCause.NATIVE_OUTPUT,
                    nowNs = processorStartNs
                )
            }
            recordSensorLinearNdrProcessorDuration(
                frame = frame,
                durationNs = durationNs,
                outcome = "THREW"
            )
            throw failure
        }

        val durationNs = (System.nanoTime() - processorStartNs).coerceAtLeast(0L)
        val published = sensorLinearNdrRecordingLifecycleLock.withLock {
            if (sensorLinearNdrRecordingProcessorInFlight.get() !== frame ||
                !sensorLinearNdrRecordingLifecycleActive.get() ||
                frame.ingressGeneration != sensorLinearNdrRecordingGeneration.get()
            ) {
                // The conservative native-tail bit intentionally remains set. This corruption path
                // fails closed and forces teardown to retain encoder resources for completion proof.
                sensorLinearNdrRecordingProcessorInFlight.compareAndSet(frame, null)
                sensorLinearNdrRecordingProcessorInFlightSinceNs.set(0L)
                return@withLock SensorLinearNdrRecordingSubmission(
                    status = SensorLinearNdrRecordingSubmissionStatus.FAILED,
                    reason = "NDR processor in-flight lease changed before terminal publication " +
                        "sequence=${frame.sequence}"
                )
            }

            when (submission.status) {
                SensorLinearNdrRecordingSubmissionStatus.ACCEPTED -> {
                    sensorLinearNdrRecordingNativePending.set(submission.completionPending)
                    if (submission.completionPending) {
                        // A retry can publish the preceding completion and immediately dispatch this
                        // frame. The new accepted dispatch gets an independent deadline origin.
                        restartSensorLinearNdrRetryWindow(
                            SensorLinearNdrRecordingRetryCause.NATIVE_OUTPUT
                        )
                    } else {
                        clearSensorLinearNdrRetryWindow()
                    }
                }
                SensorLinearNdrRecordingSubmissionStatus.RETRY_PENDING -> {
                    sensorLinearNdrRecordingRetryFrame.set(frame)
                    // RETRY_PENDING can mean either an older accepted native tail or pre-dispatch
                    // preview-ring backpressure. Preserve the processor's exact physical-tail proof;
                    // never fabricate native ownership for a frame that has not entered JNI.
                    sensorLinearNdrRecordingNativePending.set(submission.completionPending)
                    transitionSensorLinearNdrRetryWindow(
                        submission.retryCause ?: if (submission.completionPending) {
                            SensorLinearNdrRecordingRetryCause.NATIVE_OUTPUT
                        } else {
                            SensorLinearNdrRecordingRetryCause.PREVIEW_TRANSPORT
                        }
                    )
                }
                SensorLinearNdrRecordingSubmissionStatus.FAILED -> {
                    // FAILED is the processor contract's terminal response for this attempt.
                    sensorLinearNdrRecordingNativePending.set(false)
                    clearSensorLinearNdrRetryWindow()
                }
            }
            // Clear last, after all exact native/retry state is visible under the same lifecycle lock.
            sensorLinearNdrRecordingProcessorInFlight.set(null)
            sensorLinearNdrRecordingProcessorInFlightSinceNs.set(0L)
            submission
        }
        recordSensorLinearNdrProcessorDuration(
            frame = frame,
            durationNs = durationNs,
            outcome = published.status.name
        )
        return published
    }

    private fun transitionSensorLinearNdrRetryWindow(
        cause: SensorLinearNdrRecordingRetryCause,
        nowNs: Long = System.nanoTime()
    ): SensorLinearNdrRecordingRetryWindow {
        while (true) {
            val current = sensorLinearNdrRecordingRetryWindow.get()
            val next = SensorLinearNdrRecordingIngressPolicy.transitionRetryWindow(
                current = current,
                cause = cause,
                nowNs = nowNs
            )
            if (next === current || sensorLinearNdrRecordingRetryWindow.compareAndSet(current, next)) {
                return next
            }
        }
    }

    /** A newly accepted native dispatch owns a new physical completion deadline. */
    private fun restartSensorLinearNdrRetryWindow(
        cause: SensorLinearNdrRecordingRetryCause,
        nowNs: Long = System.nanoTime()
    ) {
        sensorLinearNdrRecordingRetryWindow.set(
            SensorLinearNdrRecordingIngressPolicy.restartRetryWindow(
                cause = cause,
                nowNs = nowNs
            )
        )
    }

    private fun clearSensorLinearNdrRetryWindow() {
        sensorLinearNdrRecordingRetryWindow.set(null)
    }

    private fun sensorLinearNdrRetryDelayOrFailure(
        frame: SensorLinearNdrRecordingFrame?,
        expectedGeneration: Long
    ): Long? {
        val now = System.nanoTime()
        val window = sensorLinearNdrRecordingRetryWindow.get()
            ?: transitionSensorLinearNdrRetryWindow(
                cause = if (sensorLinearNdrRecordingNativePending.get()) {
                    SensorLinearNdrRecordingRetryCause.NATIVE_OUTPUT
                } else {
                    SensorLinearNdrRecordingRetryCause.PREVIEW_TRANSPORT
                },
                nowNs = now
            )
        val elapsed = (now - window.startedNs).coerceAtLeast(0L)
        val deadline = SensorLinearNdrRecordingIngressPolicy.retryDeadlineNs(
            targetFps = sensorLinearNdrRecordingTargetFps,
            cause = window.cause
        )
        if (elapsed > deadline && !sensorLinearNdrRecordingFailureReported.get()) {
            val claimedFrame = SensorLinearNdrExactOwnership.claim(
                sensorLinearNdrRecordingRetryFrame,
                frame
            )
            val sensorTimestampNs = (claimedFrame ?: frame)?.let {
                runCatching { it.image.timestamp }.getOrNull()
            }
            claimedFrame?.let(::releaseSensorLinearNdrRecordingFrame)
            // A deadline is a failure report, not proof that any older native tail is retired. Failure
            // publication drains the exact unaccepted retry Image; when completionPending was true the
            // worker continues polling the older accepted dispatch until terminal completion.
            val waitingOnNativeTail = sensorLinearNdrRecordingNativePending.get()
            failSensorLinearNdrRecording(
                reason = when (window.cause) {
                    SensorLinearNdrRecordingRetryCause.NATIVE_OUTPUT ->
                        "NDR native completion/backpressure missed recorder deadline " +
                            "elapsedNs=$elapsed deadlineNs=$deadline nativePending=$waitingOnNativeTail"
                    SensorLinearNdrRecordingRetryCause.PREVIEW_TRANSPORT ->
                        "NDR preview transport remained saturated past recorder deadline " +
                            "elapsedNs=$elapsed deadlineNs=$deadline"
                    SensorLinearNdrRecordingRetryCause.RENDER_PROFILE_TRANSITION ->
                        "NDR render-profile replacement missed recorder transition deadline " +
                            "elapsedNs=$elapsed deadlineNs=$deadline"
                },
                sensorTimestampNs = sensorTimestampNs,
                expectedGeneration = expectedGeneration
            )
        }
        return SensorLinearNdrRecordingIngressPolicy.retryPollDelayNs(
            sensorLinearNdrRecordingTargetFps
        )
    }

    private fun failSensorLinearNdrRecording(
        reason: String,
        sensorTimestampNs: Long? = null,
        cause: Throwable? = null,
        expectedGeneration: Long = sensorLinearNdrRecordingGeneration.get(),
        scheduleWorker: Boolean = true
    ) {
        var listener: ((SensorLinearNdrRecordingFailure) -> Unit)? = null
        var firstReport = false
        val imagesToClose = sensorLinearNdrRecordingLifecycleLock.withLock {
            if (!sensorLinearNdrRecordingLifecycleActive.get() ||
                expectedGeneration != sensorLinearNdrRecordingGeneration.get()
            ) {
                return
            }
            // Failure is a termination request, not permission for the caller to retire worker/native
            // ownership. The recorder executor remains the sole lifecycle finisher.
            sensorLinearNdrRecordingIngressAdmission.set(null)
            sensorLinearNdrRecordingAccepting.set(false)
            sensorLinearNdrRecordingDiscardRequested.set(true)
            sensorLinearNdrRecordingCapacityGate.signalCapacityChanged()
            if (sensorLinearNdrRecordingFailureReported.compareAndSet(false, true)) {
                firstReport = true
                listener = sensorLinearNdrRecordingFailureListener
                sensorLinearNdrRecordingFailureListener = null
            }
            takeAllSensorLinearNdrRecordingImages()
        }
        imagesToClose.forEach(::releaseSensorLinearNdrImage)
        if (firstReport) {
            Log.e(TAG, "NDR_RECORD_INGRESS_FAILURE reason=$reason timestamp=$sensorTimestampNs", cause)
        }
        if (scheduleWorker) scheduleSensorLinearNdrRecordingWorker()
        if (firstReport) {
            runCatching {
                listener?.invoke(SensorLinearNdrRecordingFailure(reason, sensorTimestampNs, cause))
            }.onFailure { callbackFailure ->
                Log.e(TAG, "NDR recording failure listener failed", callbackFailure)
            }
        }
    }

    private fun finishSensorLinearNdrRecordingIngress(
        expectedGeneration: Long
    ): Boolean = sensorLinearNdrRecordingLifecycleLock.withLock {
        if (!sensorLinearNdrRecordingLifecycleActive.get() ||
            expectedGeneration != sensorLinearNdrRecordingGeneration.get()
        ) {
            return@withLock false
        }
        // A timeout/failure report is not a native fence. Only the recorder worker may finish, and only
        // after admission is closed and every Java/native owner is observably terminal.
        if (sensorLinearNdrRecordingIngressAdmission.get() != null ||
            sensorLinearNdrRecordingAccepting.get() ||
            sensorLinearNdrRecordingNativePending.get() ||
            sensorLinearNdrRecordingOwnedFrames.get() != 0 ||
            sensorLinearNdrRecordingRetryFrame.get() != null ||
            sensorLinearNdrRecordingProcessorInFlight.get() != null ||
            sensorLinearNdrRecordingQueue.isNotEmpty()
        ) return@withLock false
        val joinsRetired = resultLock.withLock {
            if (sensorLinearNdrPendingImages.isNotEmpty() ||
                sensorLinearNdrReadyBySequence.isNotEmpty()
            ) {
                false
            } else {
                sensorLinearNdrRecordingResults.clear()
                true
            }
        }
        if (!joinsRetired) return@withLock false
        sensorLinearNdrRecordingRetryWindow.set(null)
        sensorLinearNdrRecordingSessionGeneration.set(-1L)
        sensorLinearNdrRecordingColorMetadataStabilizer.reset()
        lastSensorLinearNdrRecordingColorMetadataSource = null
        sensorLinearExposureAnalyticsPending.set(null)
        val stopLatch = sensorLinearNdrRecordingStopLatch
        sensorLinearNdrRecordingStopLatch = null
        sensorLinearNdrRecordingFailureListener = null
        NeoLogger.i(
            TAG,
            "NDR_RECORD_INGRESS_STOP generation=$expectedGeneration " +
                "failure=${sensorLinearNdrRecordingFailureReported.get()} " +
                "owned=${sensorLinearNdrRecordingOwnedFrames.get()} " +
                "ownedHighWatermark=${sensorLinearNdrRecordingOwnedHighWatermark.get()} " +
                "ownedLimit=${sensorLinearNdrRecordingOwnedFrameLimit.get()} " +
                "capacityWaits=${sensorLinearNdrRecordingCapacityWaits.get()} " +
                "processorMaxUs=${sensorLinearNdrRecordingProcessorMaxDurationNs.get() / 1_000L} " +
                "processorSlow=${sensorLinearNdrRecordingProcessorSlowSubmissions.get()} " +
                "cutoverRetired=${sensorLinearNdrRecordingCutoverRetiredFrames.get()}"
        )
        // Lifecycle becomes restartable only after every old-generation field and Image is retired.
        // Count down under the same lock so a waiter cannot tear down the encoder ahead of cleanup.
        sensorLinearNdrRecordingDiscardRequested.set(false)
        sensorLinearNdrRecordingLifecycleActive.set(false)
        stopLatch?.countDown()
        true
    }

    private fun takeAllSensorLinearNdrRecordingImages(): List<SensorLinearNdrOwnedImage> {
        val images = resultLock.withLock { takeSensorLinearNdrRecordingImagesLocked() }
        val retry = sensorLinearNdrRecordingRetryFrame.getAndSet(null)
        val queued = ArrayList<SensorLinearNdrRecordingFrame>()
        sensorLinearNdrRecordingQueue.drainTo(queued)
        return buildList {
            addAll(images)
            retry?.let { add(SensorLinearNdrOwnedImage(it.image, it.ingressGeneration)) }
            queued.forEach { add(SensorLinearNdrOwnedImage(it.image, it.ingressGeneration)) }
        }.distinctBy { it.image }
    }

    /** Caller holds resultLock. */
    private fun takeSensorLinearNdrRecordingImagesLocked(): List<SensorLinearNdrOwnedImage> =
        buildList {
            sensorLinearNdrPendingImages.values.forEach {
                add(SensorLinearNdrOwnedImage(it.image, it.ingressGeneration))
            }
            sensorLinearNdrReadyBySequence.values.forEach {
                add(SensorLinearNdrOwnedImage(it.image, it.ingressGeneration))
            }
            sensorLinearNdrPendingImages.clear()
            sensorLinearNdrReadyBySequence.clear()
        }

    private fun releaseSensorLinearNdrRecordingFrame(frame: SensorLinearNdrRecordingFrame) {
        releaseSensorLinearNdrImage(frame.image, frame.ingressGeneration)
    }

    private fun releaseSensorLinearNdrImage(owned: SensorLinearNdrOwnedImage) {
        releaseSensorLinearNdrImage(owned.image, owned.ingressGeneration)
    }

    private fun releaseSensorLinearNdrImage(
        image: Image,
        ingressGeneration: Long = sensorLinearNdrRecordingGeneration.get()
    ) {
        if (!sensorLinearNdrExactImageOwnership.claimRelease(image, ingressGeneration)) {
            Log.e(
                TAG,
                "Rejected duplicate/unowned NDR Image release generation=$ingressGeneration " +
                    "currentGeneration=${sensorLinearNdrRecordingGeneration.get()} " +
                    "registered=${sensorLinearNdrExactImageOwnership.outstandingCount()}"
            )
            return
        }
        try {
            image.close()
        } catch (failure: Throwable) {
            Log.e(TAG, "NDR Image.close failed after exact release claim", failure)
        } finally {
            releaseSensorLinearNdrOwnershipReservation(ingressGeneration)
        }
    }

    private fun recordSensorLinearNdrOwnedHighWatermark(owned: Int) {
        while (true) {
            val current = sensorLinearNdrRecordingOwnedHighWatermark.get()
            if (owned <= current) return
            if (sensorLinearNdrRecordingOwnedHighWatermark.compareAndSet(current, owned)) {
                val processorStartedNs = sensorLinearNdrRecordingProcessorInFlightSinceNs.get()
                val processorInFlightUs = if (processorStartedNs > 0L) {
                    (System.nanoTime() - processorStartedNs).coerceAtLeast(0L) / 1_000L
                } else {
                    0L
                }
                NeoLogger.i(
                    TAG,
                    "NDR_RECORD_INGRESS_HIGH_WATER generation=" +
                        "${sensorLinearNdrRecordingGeneration.get()} owned=$owned " +
                        "limit=${sensorLinearNdrRecordingOwnedFrameLimit.get()} " +
                        "fifoDepth=${sensorLinearNdrRecordingQueue.size} " +
                        "nativePending=${sensorLinearNdrRecordingNativePending.get()} " +
                        "processorInFlightUs=$processorInFlightUs"
                )
                return
            }
        }
    }

    private fun recordSensorLinearNdrProcessorDuration(
        frame: SensorLinearNdrRecordingFrame,
        durationNs: Long,
        outcome: String
    ) {
        val frameIntervalNs =
            1_000_000_000L / sensorLinearNdrRecordingTargetFps.coerceAtLeast(1)
        if (durationNs > frameIntervalNs) {
            sensorLinearNdrRecordingProcessorSlowSubmissions.incrementAndGet()
        }
        var publishedNewMaximum = false
        while (true) {
            val current = sensorLinearNdrRecordingProcessorMaxDurationNs.get()
            if (durationNs <= current) break
            if (sensorLinearNdrRecordingProcessorMaxDurationNs.compareAndSet(current, durationNs)) {
                publishedNewMaximum = true
                break
            }
        }
        // Avoid per-frame logging when the lane is only slightly above cadence. New maxima above two
        // complete frame periods are actionable bounded-stall evidence and intentionally rare.
        if (publishedNewMaximum && durationNs >= frameIntervalNs * 2L) {
            NeoLogger.w(
                TAG,
                "NDR_RECORD_PROCESSOR_STALL generation=${frame.ingressGeneration} " +
                    "sequence=${frame.sequence} durationUs=${durationNs / 1_000L} " +
                    "frameBudgetUs=${frameIntervalNs / 1_000L} outcome=$outcome " +
                    "owned=${sensorLinearNdrRecordingOwnedFrames.get()} " +
                    "fifo=${sensorLinearNdrRecordingQueue.size} " +
                    "pendingImages=${resultLock.withLock { sensorLinearNdrPendingImages.size }}"
            )
        }
    }

    private fun logSensorLinearNdrImageJoinDiagnostic(
        generation: Long,
        sequence: Long,
        timestamp: Long,
        arrivedAtElapsedNs: Long,
        disposition: String,
        owned: Int,
        ownedLimit: Int,
        pendingImages: Int,
        pendingResults: Int,
        readyFrames: Int
    ) {
        val index = sensorLinearNdrImageJoinDiagnosticCount.getAndIncrement()
        if (index >= SENSOR_LINEAR_NDR_JOIN_DIAGNOSTIC_LIMIT) return
        val armedElapsedNs = sensorLinearNdrRecordingArmedElapsedNs.get()
        val sinceArmUs = if (armedElapsedNs > 0L) {
            (arrivedAtElapsedNs - armedElapsedNs).coerceAtLeast(0L) / 1_000L
        } else {
            -1L
        }
        NeoLogger.i(
            TAG,
            "NDR_JOIN_IMAGE index=$index generation=$generation sequence=$sequence " +
                "timestamp=$timestamp sinceArmUs=$sinceArmUs disposition=$disposition " +
                "owned=$owned/$ownedLimit pendingImages=$pendingImages " +
                "pendingResults=$pendingResults ready=$readyFrames " +
                "fifo=${sensorLinearNdrRecordingQueue.size} " +
                "retry=${sensorLinearNdrRecordingRetryFrame.get() != null} " +
                "nativePending=${sensorLinearNdrRecordingNativePending.get()}"
        )
    }

    private fun sensorLinearNdrAdmissionDiagnosticIdentity(
        admission: SensorLinearNdrRecordingIngressToken?
    ): String = admission?.let {
        "${it.generation}:${it.sessionGeneration}:" +
            Integer.toHexString(System.identityHashCode(it))
    } ?: "null"

    private fun logSensorLinearNdrResultJoinDiagnostic(
        timestamp: Long?,
        callbackSessionGeneration: Long,
        callbackAdmission: SensorLinearNdrRecordingIngressToken?,
        disposition: String,
        pendingImages: Int = -1,
        pendingResults: Int = -1,
        readyFrames: Int = -1
    ) {
        if (!sensorLinearNdrRecordingLifecycleActive.get()) return
        val index = sensorLinearNdrResultJoinDiagnosticCount.getAndIncrement()
        if (index >= SENSOR_LINEAR_NDR_JOIN_DIAGNOSTIC_LIMIT) return
        val publishedAdmission = sensorLinearNdrRecordingIngressAdmission.get()
        NeoLogger.i(
            TAG,
            "NDR_JOIN_RESULT index=$index timestamp=${timestamp ?: -1L} " +
                "callbackSession=$callbackSessionGeneration " +
                "expectedSession=${sensorLinearNdrRecordingSessionGeneration.get()} " +
                "currentSession=${camera.sessionGeneration.get()} " +
                "entryAdmission=${sensorLinearNdrAdmissionDiagnosticIdentity(callbackAdmission)} " +
                "publishedAdmission=${sensorLinearNdrAdmissionDiagnosticIdentity(publishedAdmission)} " +
                "accepting=${sensorLinearNdrRecordingAccepting.get()} " +
                "discard=${sensorLinearNdrRecordingDiscardRequested.get()} " +
                "disposition=$disposition pendingImages=$pendingImages " +
                "pendingResults=$pendingResults ready=$readyFrames " +
                "fifo=${sensorLinearNdrRecordingQueue.size}"
        )
    }

    private fun releaseSensorLinearNdrOwnershipReservation(ingressGeneration: Long) {
        // A delayed old Camera2 callback may close after a new ingress starts. Never let that old close
        // decrement the new generation's reader budget.
        if (ingressGeneration != sensorLinearNdrRecordingGeneration.get()) return
        while (true) {
            val current = sensorLinearNdrRecordingOwnedFrames.get()
            if (current <= 0) return
            if (sensorLinearNdrRecordingOwnedFrames.compareAndSet(current, current - 1)) {
                sensorLinearNdrRecordingCapacityGate.signalCapacityChanged()
                return
            }
        }
    }

    /**
     * Latest-frame-wins handoff from CameraBackground to the dedicated RAW compute worker. This method
     * performs no metadata wait, GPU dispatch, readback, or tone mapping on the camera callback thread.
     */
    private fun pairOrHoldIdleRawPreview(image: Image) {
        if (rawPreviewProcessor == null || destroyRequested.get() || destroyed.get()) {
            runCatching { image.close() }
            return
        }

        var paired: IdleRawPreviewFrame? = null
        var replaced: Image? = null
        var evicted: Image? = null
        var rejectIncoming = false
        resultLock.withLock {
            // startCapture() raises this gate before clearing the join. Re-check it under the same lock
            // used by clear/enqueue so a callback sampled just before the transition cannot repopulate it.
            val presentation = previewPresentationGate.snapshot()
            val currentSessionGeneration = camera.sessionGeneration.get()
            if (captureLifecycleActive.get() || destroyRequested.get() || destroyed.get() ||
                !presentation.committed ||
                presentation.sessionGeneration != currentSessionGeneration
            ) {
                rejectIncoming = true
            } else {
                val timestamp = image.timestamp
                val exactResult = recentResultsByTimestamp.remove(timestamp)
                if (exactResult != null) {
                    if (
                        exactResult.sessionGeneration == currentSessionGeneration &&
                        previewPresentationGate.accepts(
                            exactResult.presentationEpoch,
                            exactResult.sessionGeneration
                        )
                    ) {
                        paired = IdleRawPreviewFrame(
                            image = image,
                            result = exactResult.result,
                            joinGeneration = idleRawJoinGeneration,
                            presentationEpoch = exactResult.presentationEpoch,
                            sessionGeneration = exactResult.sessionGeneration
                        )
                    } else {
                        rejectIncoming = true
                    }
                } else {
                    replaced = unmatchedIdleRawImages.put(timestamp, image)
                    if (unmatchedIdleRawImages.size > MAX_UNMATCHED_IDLE_RAW_IMAGES) {
                        val iterator = unmatchedIdleRawImages.entries.iterator()
                        if (iterator.hasNext()) {
                            val eldest = iterator.next()
                            evicted = eldest.value
                            iterator.remove()
                        }
                    }
                }
            }
        }

        if (rejectIncoming) runCatching { image.close() }
        replaced?.takeIf { it !== image }?.let { runCatching { it.close() } }
        evicted?.takeIf { it !== image && it !== replaced }?.let { runCatching { it.close() } }
        paired?.let(::enqueueIdleRawPreview)
    }

    private fun clearIdleRawPreviewFrames() {
        val imagesToClose = resultLock.withLock {
            idleRawJoinGeneration += 1L
            clearNonAuthoritativeCaptureResultsLocked()
            ArrayList<Image>(unmatchedIdleRawImages.size + 1).apply {
                addAll(unmatchedIdleRawImages.values)
                unmatchedIdleRawImages.clear()
                idleRawPreviewPending.getAndSet(null)?.let { add(it.image) }
            }
        }
        imagesToClose.forEach { image -> runCatching { image.close() } }
    }

    /** Caller holds [resultLock]. Preserve result-first and image-first authoritative writer joins. */
    private fun clearNonAuthoritativeCaptureResultsLocked() {
        recentResultsByTimestamp.entries.removeAll { (timestamp, stamped) ->
            stamped.rawWriterSession == null &&
                pendingRawCaptureResultsByTimestamp[timestamp] == null
        }
        // Presentation/session transitions also wake authoritative waiters so a changed Camera2
        // generation is observed immediately instead of consuming the full timeout budget.
        exactCaptureResultWaiter.signalChangedLocked()
    }

    private fun enqueueIdleRawPreview(frame: IdleRawPreviewFrame) {
        var accepted = false
        var replaced: IdleRawPreviewFrame? = null
        var exactPairTimestampNs = 0L
        resultLock.withLock {
            if (
                !destroyRequested.get() &&
                !destroyed.get() &&
                !captureLifecycleActive.get() &&
                frame.sessionGeneration == camera.sessionGeneration.get() &&
                frame.joinGeneration == idleRawJoinGeneration &&
                previewPresentationGate.accepts(
                    frame.presentationEpoch,
                    frame.sessionGeneration
                )
            ) {
                replaced = idleRawPreviewPending.getAndSet(frame)
                exactPairTimestampNs = runCatching { frame.image.timestamp }.getOrDefault(0L)
                accepted = true
            }
        }
        if (!accepted) {
            runCatching { frame.image.close() }
            return
        }
        sensorLinearNdrRecordingStartupGate.publishExactPair(
            sessionGeneration = frame.sessionGeneration,
            sensorTimestampNs = exactPairTimestampNs
        )
        replaced?.takeIf { it !== frame }?.let { runCatching { it.image.close() } }
        scheduleIdleRawPreviewWorker()
    }

    internal fun beginSensorLinearNdrRecordingStartupReadiness():
        SensorLinearNdrRecordingStartupGate.Transaction? =
        sensorLinearNdrRecordingLifecycleLock.withLock {
            val currentSessionGeneration = camera.sessionGeneration.get()
            val resultTimestampFloorNs =
                if (latestCamera2ResultSessionGeneration == currentSessionGeneration) {
                    latestCamera2ResultTimestampNs
                } else {
                    0L
                }
            sensorLinearNdrRecordingStartupGate.begin(
                baselineSessionGeneration = currentSessionGeneration,
                baselineSensorTimestampNs = resultTimestampFloorNs
            )
        }

    internal fun tryAcquireSensorLinearNdrRecordingStartupReadiness(
        transaction: SensorLinearNdrRecordingStartupGate.Transaction,
        expectedSessionGeneration: Long,
        minimumSensorTimestampNs: Long
    ): SensorLinearNdrRecordingStartupGate.ReadinessLease? =
        sensorLinearNdrRecordingStartupGate.tryAcquire(
            transaction = transaction,
            expectedSessionGeneration = expectedSessionGeneration,
            minimumSensorTimestampNs = minimumSensorTimestampNs
        )

    internal fun cancelSensorLinearNdrRecordingStartupReadiness(
        transaction: SensorLinearNdrRecordingStartupGate.Transaction
    ): Boolean = sensorLinearNdrRecordingStartupGate.cancel(transaction)

    internal fun cancelSensorLinearNdrRecordingStartupReadiness(
        lease: SensorLinearNdrRecordingStartupGate.ReadinessLease
    ): Boolean = sensorLinearNdrRecordingStartupGate.cancel(lease)

    private fun scheduleIdleRawPreviewWorker() {
        if (!idleRawPreviewWorkerScheduled.compareAndSet(false, true)) return
        try {
            idleRawPreviewExecutor.execute(::drainIdleRawPreviewLatestFrames)
        } catch (t: Throwable) {
            idleRawPreviewWorkerScheduled.set(false)
            idleRawPreviewPending.getAndSet(null)?.let { runCatching { it.image.close() } }
            if (!destroyed.get()) Log.w(TAG, "Unable to schedule RAW preview compute", t)
        }
    }

    private fun drainIdleRawPreviewLatestFrames() {
        try {
            while (!destroyed.get()) {
                val frame = idleRawPreviewPending.getAndSet(null) ?: break
                processIdleRawPreview(frame)
            }
        } finally {
            idleRawPreviewWorkerScheduled.set(false)
            // Close the scheduling race where a frame arrives after the loop observes null but before
            // the scheduled flag is cleared. CAS ensures there is still only one worker.
            if (!destroyed.get() && idleRawPreviewPending.get() != null) {
                scheduleIdleRawPreviewWorker()
            }
        }
    }

    /**
     * Bounded physical teardown probe for the latest-only RAW monitor. A miss returns false and leaves
     * every dependent resource quarantined; a later [destroy] retries and finalizes only after the
     * already-running processor has returned.
     */
    private fun awaitIdleRawPreviewWorkerIdleForTeardown(timeoutMs: Long): Boolean {
        if (Thread.currentThread().name.startsWith("RawPreviewCompute")) {
            Log.e(TAG, "RAW preview teardown barrier cannot run on its own worker")
            return false
        }
        val barrier = try {
            idleRawPreviewExecutor.submit { Unit }
        } catch (failure: Throwable) {
            Log.e(TAG, "Unable to schedule RAW preview teardown barrier", failure)
            return !idleRawPreviewWorkerScheduled.get()
        }
        return try {
            barrier.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            true
        } catch (_: TimeoutException) {
            // Close the boundary race without an unbounded wait: get() is immediate after isDone.
            if (barrier.isDone) {
                runCatching { barrier.get() }.isSuccess
            } else {
                Log.e(TAG, "RAW preview teardown deadline missed; retaining native resources for retry")
                false
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (failure: Throwable) {
            Log.e(TAG, "RAW preview teardown barrier failed", failure)
            !idleRawPreviewWorkerScheduled.get()
        }
    }

    private fun processIdleRawPreview(frame: IdleRawPreviewFrame) {
        val image = frame.image
        var consumed = false
        try {
            if (rawPreviewProcessor == null) return
            val isCurrent = sensorLinearNdrRecordingLifecycleLock.withLock {
                isIdleRawPreviewFrameCurrentLocked(frame)
            }
            if (!isCurrent) return
            // The event join guarantees strict sensor-timestamp lineage before compute is scheduled.
            val result = frame.result
            val exactDescriptor = buildRawBufferDescriptor(image, result) ?: return
            publishSensorLinearExposureMetrics(
                image = image,
                descriptor = exactDescriptor,
                result = result,
                sessionGeneration = frame.sessionGeneration,
                frame = frame
            )
            val monitorDescriptor = stabilizeRawPreviewDescriptor(
                exact = exactDescriptor,
                result = result,
                presentationEpoch = frame.presentationEpoch,
                sessionGeneration = frame.sessionGeneration
            ) ?: return
            val submissionIdentity = IdleRawPreviewSubmissionIdentity(
                joinGeneration = frame.joinGeneration,
                presentationEpoch = frame.presentationEpoch,
                sessionGeneration = frame.sessionGeneration,
                sensorTimestampNs = image.timestamp
            )
            // The processor call itself is the Image-ownership transfer. Keep the final validity check
            // and transfer in the same lifecycle critical section as startCapture's ownership claim.
            consumed = sensorLinearNdrRecordingLifecycleLock.withLock {
                if (!isIdleRawPreviewFrameCurrentLocked(frame)) {
                    false
                } else {
                    rawPreviewProcessor?.invoke(image, monitorDescriptor, submissionIdentity) == true
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Idle RAW preview processing failed: ${t.message}", t)
        } finally {
            if (!consumed) runCatching { image.close() }
            idleRawFramesDrained += 1L
            if (idleRawFramesDrained == 1L || idleRawFramesDrained % 125L == 0L) {
                NeoLogger.i(
                    TAG,
                    "Idle RAW frames handled=$idleRawFramesDrained worker=RawPreviewCompute " +
                        "pending=${idleRawPreviewPending.get() != null}"
                )
            }
        }
    }

    /** Caller holds [sensorLinearNdrRecordingLifecycleLock]. */
    private fun isIdleRawPreviewFrameCurrentLocked(frame: IdleRawPreviewFrame): Boolean =
        isIdleRawPreviewSubmissionCurrentLocked(
            IdleRawPreviewSubmissionIdentity(
                joinGeneration = frame.joinGeneration,
                presentationEpoch = frame.presentationEpoch,
                sessionGeneration = frame.sessionGeneration,
                sensorTimestampNs = frame.image.timestamp
            )
        )

    /** Caller holds [sensorLinearNdrRecordingLifecycleLock]. */
    private fun isIdleRawPreviewSubmissionCurrentLocked(
        identity: IdleRawPreviewSubmissionIdentity
    ): Boolean {
        if (destroyRequested.get() || destroyed.get() || captureLifecycleActive.get()) return false
        if (identity.sessionGeneration != camera.sessionGeneration.get()) return false
        return resultLock.withLock {
            identity.joinGeneration == idleRawJoinGeneration &&
                previewPresentationGate.accepts(
                    identity.presentationEpoch,
                    identity.sessionGeneration
                )
        }
    }

    /** Camera ingress reserves only O(1) join metadata; it never reads the RAW plane. */
    private fun maybeReserveRawOriginalExposureMeter(
        image: Image
    ): RawOriginalExposureMeterJoin? {
        if (!captureLifecycleActive.get() || !isCapturing.get() || isPaused.get()) return null
        val meterRequirement = camera.softwareAePriorityMeterRequirement()
        if (!meterRequirement.required) return null

        val nowMs = SystemClock.elapsedRealtime()
        while (true) {
            val previousMs = rawOriginalExposureMeterLastReservedElapsedMs.get()
            if (previousMs > 0L &&
                nowMs - previousMs < SensorLinearNdrExposurePolicy.CONTROL_EVALUATION_INTERVAL_MS
            ) return null
            if (rawOriginalExposureMeterLastReservedElapsedMs.compareAndSet(previousMs, nowMs)) break
        }

        val plane = image.planes.firstOrNull()
        val characteristics = camera.getCameraCharacteristics()
        if (plane == null || characteristics == null) {
            rawOriginalExposureMeterLastReservedElapsedMs.compareAndSet(nowMs, 0L)
            return null
        }
        val bayerPattern = BayerPattern.fromCameraCharacteristic(
            characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        )
        val captureGeneration = rawOriginalExposureAnalyticsGeneration.get()
        val activeSessionGeneration = camera.sessionGeneration.get()
        val join = RawOriginalExposureMeterJoin(
            sensorTimestampNs = image.timestamp,
            captureGeneration = captureGeneration,
            sessionGeneration = activeSessionGeneration,
            width = image.width,
            height = image.height,
            rowStrideBytes = plane.rowStride,
            bayerPattern = bayerPattern,
            reservedAtElapsedRealtimeMs = nowMs
        )
        resultLock.withLock {
            val iterator = rawOriginalExposureMeterJoins.entries.iterator()
            while (iterator.hasNext()) {
                val stale = iterator.next().value
                if (nowMs - stale.reservedAtElapsedRealtimeMs >
                    RAW_ORIGINAL_EXPOSURE_JOIN_MAX_AGE_MS ||
                    stale.captureGeneration != captureGeneration ||
                    stale.sessionGeneration != activeSessionGeneration
                ) iterator.remove()
            }
            recentResultsByTimestamp[join.sensorTimestampNs]
                ?.takeIf { it.sessionGeneration == activeSessionGeneration }
                ?.let {
                    join.result = it.result
                    join.controlRevision = camera.softwareAeControlRevisionFor(it.result)
                }
            rawOriginalExposureMeterJoins[join.sensorTimestampNs] = join
            while (rawOriginalExposureMeterJoins.size > MAX_RAW_ORIGINAL_EXPOSURE_METER_JOINS) {
                val eldest = rawOriginalExposureMeterJoins.entries.iterator()
                if (!eldest.hasNext()) break
                eldest.next()
                eldest.remove()
            }
        }
        if (!sensorLinearExposureWorkIsCurrent(
                requiresNdrLifecycle = false,
                ingressGeneration = captureGeneration,
                sessionGeneration = activeSessionGeneration
            )
        ) {
            cancelRawOriginalExposureMeter(join)
            return null
        }
        return join
    }

    private fun cancelRawOriginalExposureMeter(join: RawOriginalExposureMeterJoin?) {
        if (join == null) return
        resultLock.withLock {
            if (rawOriginalExposureMeterJoins[join.sensorTimestampNs] === join) {
                rawOriginalExposureMeterJoins.remove(join.sensorTimestampNs)
            }
        }
        rawOriginalExposureMeterLastReservedElapsedMs.compareAndSet(
            join.reservedAtElapsedRealtimeMs,
            0L
        )
    }

    private fun cancelRawOriginalExposureMeter(sensorTimestampNs: Long) {
        val removed = resultLock.withLock {
            rawOriginalExposureMeterJoins.remove(sensorTimestampNs)
        }
        if (removed != null) {
            rawOriginalExposureMeterLastReservedElapsedMs.compareAndSet(
                removed.reservedAtElapsedRealtimeMs,
                0L
            )
        }
    }

    private fun clearRawOriginalExposureMeterJoins() {
        resultLock.withLock { rawOriginalExposureMeterJoins.clear() }
        rawOriginalExposureMeterLastReservedElapsedMs.set(0L)
    }

    /** Caller holds [sensorLinearNdrRecordingLifecycleLock]. */
    private fun invalidateRawOriginalExposureAnalyticsLocked() {
        rawOriginalExposureAnalyticsGeneration.incrementAndGet()
        clearRawOriginalExposureMeterJoins()
        sensorLinearExposureAnalyticsPending.set(null)
        sensorLinearExposureAnalyticsLastQueuedElapsedMs.set(0L)
    }

    /** Called only after master encode/write returns and while the writer still owns [image]. */
    private fun completeRawOriginalExposureMeterPixels(image: Image) {
        val timestamp = image.timestamp
        val join = resultLock.withLock {
            rawOriginalExposureMeterJoins[timestamp]?.takeIf {
                it.captureGeneration == rawOriginalExposureAnalyticsGeneration.get() &&
                    it.sessionGeneration == camera.sessionGeneration.get()
            }
        } ?: return
        val codes = SensorLinearNdrSecondarySnapshot.capture(
            onFailure = { failure ->
                Log.w(TAG, "Skipping RAW-original exposure code snapshot", failure)
            }
        ) {
            val plane = image.planes.firstOrNull() ?: return@capture null
            RawSensorExposureAnalyzer.snapshotCodes(
                plane = plane.buffer,
                rowStrideBytes = plane.rowStride,
                pixelStrideBytes = plane.pixelStride,
                width = join.width,
                height = join.height,
                bayerPattern = join.bayerPattern
            )
        }
        if (codes == null) {
            cancelRawOriginalExposureMeter(join)
            return
        }
        val completed = resultLock.withLock {
            val current = rawOriginalExposureMeterJoins[timestamp]
            if (current !== join) {
                null
            } else {
                current.codeSnapshot = codes
                if (current.result != null) {
                    rawOriginalExposureMeterJoins.remove(timestamp)
                    current
                } else {
                    null
                }
            }
        }
        completed?.let(::queueCompletedRawOriginalExposureMeter)
    }

    private fun attachRawOriginalExposureResultLocked(
        sensorTimestampNs: Long,
        result: TotalCaptureResult,
        sessionGeneration: Long
    ): RawOriginalExposureMeterJoin? {
        val join = rawOriginalExposureMeterJoins[sensorTimestampNs] ?: return null
        if (join.captureGeneration != rawOriginalExposureAnalyticsGeneration.get() ||
            join.sessionGeneration != sessionGeneration ||
            !captureLifecycleActive.get() ||
            !isCapturing.get()
        ) {
            rawOriginalExposureMeterJoins.remove(sensorTimestampNs)
            return null
        }
        join.result = result
        join.controlRevision = camera.softwareAeControlRevisionFor(result)
        return if (join.codeSnapshot != null) {
            rawOriginalExposureMeterJoins.remove(sensorTimestampNs)
            join
        } else {
            null
        }
    }

    private fun queueCompletedRawOriginalExposureMeter(join: RawOriginalExposureMeterJoin) {
        val result = join.result ?: return
        val codes = join.codeSnapshot ?: return
        val meterRequirement = camera.softwareAePriorityMeterRequirement()
        if (join.controlRevision < 0L ||
            !meterRequirement.required ||
            meterRequirement.controlRevision != join.controlRevision
        ) return
        if (!sensorLinearExposureWorkIsCurrent(
                requiresNdrLifecycle = false,
                ingressGeneration = join.captureGeneration,
                sessionGeneration = join.sessionGeneration
            )
        ) return
        val descriptor = buildRawBufferDescriptor(
            width = join.width,
            height = join.height,
            stride = join.rowStrideBytes,
            result = result
        ) ?: return
        val snapshot = RawSensorExposureAnalyzer.calibrate(codes, descriptor) ?: return
        sensorLinearExposureAnalyticsLastQueuedElapsedMs.set(
            join.reservedAtElapsedRealtimeMs
        )
        queueSensorLinearExposureMetrics(
            snapshot = snapshot,
            descriptor = descriptor,
            result = result,
            ingressGeneration = join.captureGeneration,
            sessionGeneration = join.sessionGeneration,
            requiresNdrLifecycle = false,
            measuredAtElapsedRealtimeMs = join.reservedAtElapsedRealtimeMs,
            sensorTimestampNs = join.sensorTimestampNs,
            controlRevision = join.controlRevision
        )
    }

    private fun queueSensorLinearExposureMetrics(
        image: Image,
        descriptor: RawBufferDescriptor,
        result: TotalCaptureResult,
        ingressGeneration: Long,
        sessionGeneration: Long,
        requiresNdrLifecycle: Boolean = true
    ) {
        if (!sensorLinearExposureWorkIsCurrent(
                requiresNdrLifecycle = requiresNdrLifecycle,
                ingressGeneration = ingressGeneration,
                sessionGeneration = sessionGeneration
            )
        ) return
        val nowMs = SystemClock.elapsedRealtime()
        while (true) {
            val previousMs = sensorLinearExposureAnalyticsLastQueuedElapsedMs.get()
            if (previousMs > 0L &&
                nowMs - previousMs < SensorLinearNdrExposurePolicy.CONTROL_EVALUATION_INTERVAL_MS
            ) return
            if (sensorLinearExposureAnalyticsLastQueuedElapsedMs.compareAndSet(previousMs, nowMs)) {
                break
            }
        }
        val work = SensorLinearNdrSecondarySnapshot.capture(
            onFailure = { failure ->
                Log.w(TAG, "Skipping sensor-linear exposure snapshot", failure)
            }
        ) {
            // Image/Plane/ByteBuffer access is confined to this synchronous snapshot. Only primitive
            // samples leave the recorder worker; analytics can never retain a scarce reader buffer.
            val plane = image.planes.firstOrNull() ?: return@capture null
            val snapshot = RawSensorExposureAnalyzer.snapshot(
                plane = plane.buffer,
                rowStrideBytes = plane.rowStride,
                pixelStrideBytes = plane.pixelStride,
                descriptor = descriptor
            ) ?: return@capture null
            snapshot
        } ?: return
        queueSensorLinearExposureMetrics(
            snapshot = work,
            descriptor = descriptor,
            result = result,
            ingressGeneration = ingressGeneration,
            sessionGeneration = sessionGeneration,
            requiresNdrLifecycle = requiresNdrLifecycle,
            measuredAtElapsedRealtimeMs = nowMs,
            sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
            controlRevision = camera.softwareAeControlRevisionFor(result)
        )
    }

    private fun queueSensorLinearExposureMetrics(
        snapshot: RawSensorExposureSampleSnapshot,
        descriptor: RawBufferDescriptor,
        result: TotalCaptureResult,
        ingressGeneration: Long,
        sessionGeneration: Long,
        requiresNdrLifecycle: Boolean,
        measuredAtElapsedRealtimeMs: Long,
        sensorTimestampNs: Long,
        controlRevision: Long
    ) {
        if (!sensorLinearExposureWorkIsCurrent(
                requiresNdrLifecycle = requiresNdrLifecycle,
                ingressGeneration = ingressGeneration,
                sessionGeneration = sessionGeneration
            )
        ) return
        val work = SensorLinearExposureAnalyticsWork(
            snapshot = snapshot,
            ingressGeneration = ingressGeneration,
            sessionGeneration = sessionGeneration,
            requiresNdrLifecycle = requiresNdrLifecycle,
            sensorSensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
            exposureTimeNs = descriptor.exposureTimeNs,
            noiseS = descriptor.noiseS,
            noiseO = descriptor.noiseO,
            reportedExposureCompensationIndex =
                result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION),
            measuredAtElapsedRealtimeMs = measuredAtElapsedRealtimeMs,
            sensorTimestampNs = sensorTimestampNs,
            controlRevision = controlRevision
        )
        sensorLinearExposureAnalyticsPending.set(work)
        scheduleSensorLinearExposureAnalytics()
    }

    private fun sensorLinearExposureWorkIsCurrent(
        requiresNdrLifecycle: Boolean,
        ingressGeneration: Long,
        sessionGeneration: Long
    ): Boolean = if (requiresNdrLifecycle) {
        sensorLinearNdrRecordingLifecycleActive.get() &&
            ingressGeneration == sensorLinearNdrRecordingGeneration.get() &&
            sessionGeneration == sensorLinearNdrRecordingSessionGeneration.get()
    } else {
        captureLifecycleActive.get() &&
            isCapturing.get() &&
            !isPaused.get() &&
            ingressGeneration == rawOriginalExposureAnalyticsGeneration.get() &&
            sessionGeneration == camera.sessionGeneration.get()
    }

    private fun scheduleSensorLinearExposureAnalytics() {
        if (destroyed.get() ||
            !sensorLinearExposureAnalyticsScheduled.compareAndSet(false, true)
        ) return
        try {
            sensorLinearExposureAnalyticsExecutor.execute(::drainSensorLinearExposureAnalytics)
        } catch (failure: Throwable) {
            sensorLinearExposureAnalyticsScheduled.set(false)
            sensorLinearExposureAnalyticsPending.set(null)
            Log.e(TAG, "Unable to schedule sensor-linear exposure analytics", failure)
        }
    }

    private fun drainSensorLinearExposureAnalytics() {
        try {
            while (!destroyed.get()) {
                val work = sensorLinearExposureAnalyticsPending.getAndSet(null) ?: return
                if (!sensorLinearExposureWorkIsCurrent(
                        requiresNdrLifecycle = work.requiresNdrLifecycle,
                        ingressGeneration = work.ingressGeneration,
                        sessionGeneration = work.sessionGeneration
                    )
                ) continue
                val metrics = RawSensorExposureAnalyzer.analyze(work.snapshot) ?: continue
                // Lifecycle invalidation and this final check/publication share one lock. Stop, pause,
                // destroy, or NDR teardown therefore happens wholly before or wholly after publication.
                SensorLinearExposureLifecyclePublisher.publishIfCurrent(
                    lifecycleLock = sensorLinearNdrRecordingLifecycleLock,
                    isCurrent = {
                        sensorLinearExposureWorkIsCurrent(
                            requiresNdrLifecycle = work.requiresNdrLifecycle,
                            ingressGeneration = work.ingressGeneration,
                            sessionGeneration = work.sessionGeneration
                        )
                    }
                ) {
                    camera.setSensorLinearNdrExposureSceneMetrics(
                        averageLuma = metrics.averageLuma,
                        medianLuma = metrics.medianLuma,
                        p95Luma = metrics.p95Luma,
                        p99Luma = metrics.p99Luma,
                        p99Red = metrics.p99Red,
                        p99Green = metrics.p99Green,
                        p99Blue = metrics.p99Blue,
                        highlightClipFraction = metrics.highlightClipFraction,
                        redHighlightClipFraction = metrics.redHighlightClipFraction,
                        sampleCount = metrics.sampleCount,
                        sensorSensitivityIso = work.sensorSensitivityIso,
                        exposureTimeNs = work.exposureTimeNs,
                        noiseS = work.noiseS,
                        noiseO = work.noiseO,
                        reportedExposureCompensationIndex = work.reportedExposureCompensationIndex,
                        measuredAtElapsedRealtimeMs = work.measuredAtElapsedRealtimeMs,
                        sourceSessionGeneration = work.sessionGeneration,
                        sourceSensorTimestampNs = work.sensorTimestampNs,
                        sourceControlRevision = work.controlRevision
                    )
                }
            }
        } catch (failure: Throwable) {
            Log.e(TAG, "Sensor-linear exposure analytics failed", failure)
        } finally {
            sensorLinearExposureAnalyticsScheduled.set(false)
            if (!destroyed.get() && sensorLinearExposureAnalyticsPending.get() != null) {
                scheduleSensorLinearExposureAnalytics()
            }
        }
    }

    private fun publishSensorLinearExposureMetrics(
        image: Image,
        descriptor: RawBufferDescriptor,
        result: TotalCaptureResult,
        sessionGeneration: Long,
        frame: IdleRawPreviewFrame
    ) {
        val plane = image.planes.firstOrNull() ?: return
        val metrics = RawSensorExposureAnalyzer.analyze(
            plane = plane.buffer,
            rowStrideBytes = plane.rowStride,
            pixelStrideBytes = plane.pixelStride,
            descriptor = descriptor
        ) ?: return
        sensorLinearNdrRecordingLifecycleLock.withLock {
            if (!isIdleRawPreviewFrameCurrentLocked(frame)) return
            camera.setSensorLinearNdrExposureSceneMetrics(
                averageLuma = metrics.averageLuma,
                medianLuma = metrics.medianLuma,
                p95Luma = metrics.p95Luma,
                p99Luma = metrics.p99Luma,
                p99Red = metrics.p99Red,
                p99Green = metrics.p99Green,
                p99Blue = metrics.p99Blue,
                highlightClipFraction = metrics.highlightClipFraction,
                redHighlightClipFraction = metrics.redHighlightClipFraction,
                sampleCount = metrics.sampleCount,
                sensorSensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                exposureTimeNs = descriptor.exposureTimeNs,
                noiseS = descriptor.noiseS,
                noiseO = descriptor.noiseO,
                reportedExposureCompensationIndex =
                    result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION),
                measuredAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                sourceSessionGeneration = sessionGeneration,
                sourceSensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
                sourceControlRevision = camera.softwareAeControlRevisionFor(result)
            )
        }
    }

    /**
     * Provide capture result metadata for DNG embedding.
     * Call from CameraController.captureCallback.onCaptureCompleted().
     */
    fun onCaptureResult(
        result: TotalCaptureResult,
        sessionGeneration: Long = camera.sessionGeneration.get()
    ) {
        // Sample both mutually-exclusive route publications at callback entry. Double collection
        // rejects a callback that straddles a RAW/NDR ownership transition; reference identity then
        // prevents an invocation from generation G being relabelled as G+1 after either lock wait.
        val callbackObservedRawResultIdentityBefore = rawCaptureResultIngressIdentity.get()
        val callbackObservedNdrAdmissionBefore = sensorLinearNdrRecordingIngressAdmission.get()
        val callbackObservedRawResultIdentityAfter = rawCaptureResultIngressIdentity.get()
        val callbackObservedNdrAdmissionAfter = sensorLinearNdrRecordingIngressAdmission.get()
        if (callbackObservedRawResultIdentityBefore !== callbackObservedRawResultIdentityAfter ||
            callbackObservedNdrAdmissionBefore !== callbackObservedNdrAdmissionAfter
        ) {
            logSensorLinearNdrResultJoinDiagnostic(
                timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP),
                callbackSessionGeneration = sessionGeneration,
                callbackAdmission = callbackObservedNdrAdmissionAfter,
                disposition = "DISCARDED_ROUTE_CHANGED_AT_ENTRY"
            )
            return
        }
        val callbackObservedRawResultIdentity = callbackObservedRawResultIdentityAfter
        val callbackObservedNdrAdmission = callbackObservedNdrAdmissionAfter
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
        if (timestamp == null) {
            logSensorLinearNdrResultJoinDiagnostic(
                timestamp = null,
                callbackSessionGeneration = sessionGeneration,
                callbackAdmission = callbackObservedNdrAdmission,
                disposition = "DISCARDED_MISSING_SENSOR_TIMESTAMP"
            )
            return
        }
        var ndrRoute = false
        var ndrGeneration = 0L
        var ndrFailure: String? = null
        var ndrQueued = false
        var ndrDisposition = "NOT_NDR_ROUTE"
        var diagnosticPendingImages = -1
        var diagnosticPendingResults = -1
        var diagnosticReadyFrames = -1
        var orphanedNdrImage: SensorLinearNdrOwnedImage? = null
        var paired: IdleRawPreviewFrame? = null
        var staleUnmatchedImage: Image? = null
        var completedRawExposureJoin: RawOriginalExposureMeterJoin? = null

        // Route and join under the same lifecycle -> result lock order used by image ingress and
        // start/stop. A callback sampled before a restart can never mutate the new generation's maps.
        sensorLinearNdrRecordingLifecycleLock.withLock {
            if (destroyRequested.get() || destroyed.get()) {
                ndrDisposition = "DISCARDED_MANAGER_DESTROYING"
                return@withLock
            }
            val currentSessionGeneration = camera.sessionGeneration.get()
            if (sessionGeneration == currentSessionGeneration && timestamp > 0L) {
                if (latestCamera2ResultSessionGeneration != sessionGeneration) {
                    latestCamera2ResultSessionGeneration = sessionGeneration
                    latestCamera2ResultTimestampNs = 0L
                }
                if (timestamp > latestCamera2ResultTimestampNs) {
                    latestCamera2ResultTimestampNs = timestamp
                }
            }
            if (sensorLinearNdrRecordingLifecycleActive.get()) {
                ndrRoute = true
                ndrDisposition = "NDR_ROUTE_ENTERED"
                ndrGeneration = sensorLinearNdrRecordingGeneration.get()
                val expectedSessionGeneration = sensorLinearNdrRecordingSessionGeneration.get()
                val publishedAdmission = sensorLinearNdrRecordingIngressAdmission.get()
                val callbackOwnsOpenAdmission =
                    callbackObservedNdrAdmission != null &&
                        publishedAdmission === callbackObservedNdrAdmission &&
                        callbackObservedNdrAdmission.generation == ndrGeneration &&
                        callbackObservedNdrAdmission.sessionGeneration ==
                            expectedSessionGeneration &&
                        sensorLinearNdrRecordingAccepting.get()
                // Once stop closes publication, metadata may still complete an exact Image already
                // owned by this generation. It may never create a new unmatched-result entry. A
                // callback carrying an older non-null token is excluded even from this drain path.
                val callbackMayCompleteUnpublishedExactJoin =
                    publishedAdmission == null &&
                        (callbackObservedNdrAdmission == null ||
                            (callbackObservedNdrAdmission.generation == ndrGeneration &&
                                callbackObservedNdrAdmission.sessionGeneration ==
                                    expectedSessionGeneration))
                if (sensorLinearNdrRecordingDiscardRequested.get()) {
                    ndrDisposition = "DISCARDED_STOP_REQUESTED"
                    return@withLock
                }
                if (expectedSessionGeneration < 0L ||
                    expectedSessionGeneration != currentSessionGeneration
                ) {
                    if (callbackOwnsOpenAdmission) {
                        ndrFailure = "Camera2 session advanced during ordered NDR result ingress " +
                            "callback=$sessionGeneration expected=$expectedSessionGeneration " +
                            "current=$currentSessionGeneration"
                    }
                    ndrDisposition = "DISCARDED_CURRENT_SESSION_MISMATCH"
                    return@withLock
                }
                // A callback from a retired session can arrive after a correctly armed new session.
                // It owns no current-generation state and is ignored rather than aborting the new clip.
                if (sessionGeneration != expectedSessionGeneration) {
                    ndrDisposition = "DISCARDED_CALLBACK_SESSION_MISMATCH"
                    return@withLock
                }
                if (!callbackOwnsOpenAdmission &&
                    !callbackMayCompleteUnpublishedExactJoin
                ) {
                    ndrDisposition = "DISCARDED_ADMISSION_IDENTITY_MISMATCH"
                    return@withLock
                }
                if (callbackOwnsOpenAdmission &&
                    timestamp <= callbackObservedNdrAdmission.cutoverTimestampFloorNs
                ) {
                    // This result cannot normally execute after the CameraBackground cutover action,
                    // but retain the inclusive floor at the metadata boundary as a fail-closed guard
                    // against vendor callback reordering. Its delayed Image is retired on ingress.
                    ndrDisposition = "RETIRED_PRE_RECORD_CUTOVER_RESULT"
                    return@withLock
                }
                resultLock.withLock {
                    val pending = sensorLinearNdrPendingImages.remove(timestamp)
                    if (pending != null) {
                        if (pending.ingressGeneration != ndrGeneration) {
                            ndrDisposition = "FAILED_STALE_IMAGE_GENERATION"
                            ndrFailure =
                                "Stale exact metadata join generation for NDR timestamp=$timestamp"
                            orphanedNdrImage = SensorLinearNdrOwnedImage(
                                pending.image,
                                pending.ingressGeneration
                            )
                        } else {
                            ndrDisposition = "JOINED_IMAGE_FIRST"
                            latestResult = result
                            sensorLinearNdrReadyBySequence[pending.sequence] =
                                SensorLinearNdrRecordingFrame(
                                    sequence = pending.sequence,
                                    image = pending.image,
                                    result = result,
                                    ingressGeneration = ndrGeneration,
                                    presentationEpoch = pending.presentationEpoch,
                                    sessionGeneration = sessionGeneration
                                )
                            ndrFailure = flushSensorLinearNdrReadyFramesLocked()
                            ndrQueued = ndrFailure == null
                        }
                    } else if (!callbackOwnsOpenAdmission) {
                        // Exact results for already-owned Images above are still joined during a drain;
                        // unmatched post-stop metadata owns no Image and is intentionally ignored.
                        ndrDisposition = "DISCARDED_DRAIN_WITHOUT_IMAGE"
                    } else if (sensorLinearNdrRecordingResults.containsKey(timestamp)) {
                        ndrDisposition = "FAILED_DUPLICATE_RESULT_TIMESTAMP"
                        ndrFailure =
                            "Duplicate Camera2 result timestamp in ordered NDR ingress: $timestamp"
                    } else if (sensorLinearNdrRecordingResults.size >=
                        MAX_SENSOR_LINEAR_NDR_RESULTS
                    ) {
                        ndrDisposition = "FAILED_RESULT_JOIN_CAPACITY"
                        ndrFailure =
                            "Ordered NDR metadata join exceeded $MAX_SENSOR_LINEAR_NDR_RESULTS " +
                                "unmatched results at timestamp=$timestamp"
                    } else {
                        ndrDisposition = "STORED_RESULT_FIRST"
                        latestResult = result
                        sensorLinearNdrRecordingResults[timestamp] = TimestampedCaptureResult(
                            result = result,
                            presentationEpoch = previewPresentationGate.snapshot().epoch,
                            sessionGeneration = sessionGeneration
                        )
                    }
                    diagnosticPendingImages = sensorLinearNdrPendingImages.size
                    diagnosticPendingResults = sensorLinearNdrRecordingResults.size
                    diagnosticReadyFrames = sensorLinearNdrReadyBySequence.size
                }
            } else {
                // A callback that entered an older NDR admission is never reinterpreted as idle/RAW
                // metadata after that generation has reached terminal teardown.
                if (callbackObservedNdrAdmission != null ||
                    sensorLinearNdrRecordingIngressAdmission.get() != null
                ) return@withLock
                if (sessionGeneration != currentSessionGeneration) return@withLock
                // RAW result validation shares the lifecycle -> writer -> result lock order used by
                // Image ingress. Open admission may accept result-first metadata. Closed admission may
                // complete only an exact timestamp already registered by an owned recording Image.
                rawWriterAdmissionLock.withLock rawResultRoute@{
                    val publishedRawAdmission = rawCaptureIngressAdmission.get()
                    val publishedRawResultIdentity = rawCaptureResultIngressIdentity.get()
                    val callbackOwnsOpenRawAdmission =
                        callbackObservedRawResultIdentity != null &&
                            publishedRawResultIdentity === callbackObservedRawResultIdentity &&
                            publishedRawAdmission === callbackObservedRawResultIdentity &&
                            callbackObservedRawResultIdentity.cameraSessionGeneration ==
                                sessionGeneration &&
                            timestamp >
                                callbackObservedRawResultIdentity.retiredSensorTimestampFloorNs &&
                            rawCaptureFirstAcceptedSensorTimestampNs.get().let { firstTimestampNs ->
                                firstTimestampNs == 0L || timestamp >= firstTimestampNs
                            } &&
                            rawCaptureIngressIsCurrentLocked(callbackObservedRawResultIdentity)
                    val rawLifecycleActive = captureLifecycleActive.get()

                    if (rawLifecycleActive) {
                        resultLock.withLock rawResultJoin@{
                            val pendingRawIdentity =
                                pendingRawCaptureResultsByTimestamp[timestamp]
                            val callbackCompletesExactOwnedRaw =
                                pendingRawIdentity != null &&
                                    callbackObservedRawResultIdentity != null &&
                                    pendingRawIdentity.session.generation ==
                                        callbackObservedRawResultIdentity.session.generation &&
                                    pendingRawIdentity.session.queue ===
                                        callbackObservedRawResultIdentity.session.queue &&
                                    pendingRawIdentity.cameraSessionGeneration == sessionGeneration &&
                                    callbackObservedRawResultIdentity.cameraSessionGeneration ==
                                        sessionGeneration
                            if (!callbackOwnsOpenRawAdmission &&
                                !callbackCompletesExactOwnedRaw
                            ) return@rawResultJoin

                            // A retired pause epoch can finish its exact Image after resume, but it
                            // must not replace the new epoch's descriptor fallback metadata.
                            if (publishedRawResultIdentity === pendingRawIdentity ||
                                (callbackOwnsOpenRawAdmission && pendingRawIdentity == null)
                            ) {
                                latestResult = result
                            }
                            recentResultsByTimestamp[timestamp] = TimestampedCaptureResult(
                                result = result,
                                presentationEpoch = previewPresentationGate.snapshot().epoch,
                                sessionGeneration = sessionGeneration,
                                rawWriterSession = pendingRawIdentity?.session
                                    ?: callbackObservedRawResultIdentity.session
                            )
                            exactCaptureResultWaiter.signalChangedLocked()
                            completedRawExposureJoin = attachRawOriginalExposureResultLocked(
                                sensorTimestampNs = timestamp,
                                result = result,
                                sessionGeneration = sessionGeneration
                            )
                        }
                        return@rawResultRoute
                    }

                    // An invocation sampled from any RAW generation is never reinterpreted as idle
                    // metadata after terminal stop, nor may an idle invocation cross into RAW start.
                    if (callbackObservedRawResultIdentity != null ||
                        publishedRawResultIdentity != null ||
                        publishedRawAdmission != null
                    ) return@rawResultRoute

                    resultLock.withLock {
                        val presentation = previewPresentationGate.snapshot()
                        val acceptedForPreview =
                            presentation.sessionGeneration == currentSessionGeneration &&
                                previewPresentationGate.accepts(
                                    presentation.epoch,
                                    sessionGeneration
                                )
                        if (acceptedForPreview) {
                            latestResult = result
                            recentResultsByTimestamp[timestamp] = TimestampedCaptureResult(
                                result = result,
                                presentationEpoch = presentation.epoch,
                                sessionGeneration = sessionGeneration
                            )
                            unmatchedIdleRawImages.remove(timestamp)?.let { image ->
                                recentResultsByTimestamp.remove(timestamp)
                                paired = IdleRawPreviewFrame(
                                    image = image,
                                    result = result,
                                    joinGeneration = idleRawJoinGeneration,
                                    presentationEpoch = presentation.epoch,
                                    sessionGeneration = sessionGeneration
                                )
                            }
                        } else {
                            staleUnmatchedImage = unmatchedIdleRawImages.remove(timestamp)
                        }
                    }
                }
            }
        }

        if (ndrRoute || sensorLinearNdrRecordingLifecycleActive.get()) {
            logSensorLinearNdrResultJoinDiagnostic(
                timestamp = timestamp,
                callbackSessionGeneration = sessionGeneration,
                callbackAdmission = callbackObservedNdrAdmission,
                disposition = ndrDisposition,
                pendingImages = diagnosticPendingImages,
                pendingResults = diagnosticPendingResults,
                readyFrames = diagnosticReadyFrames
            )
        }

        if (ndrRoute) {
            val failureReason = ndrFailure
            if (failureReason != null) {
                orphanedNdrImage?.let(::releaseSensorLinearNdrImage)
                failSensorLinearNdrRecording(
                    reason = failureReason,
                    sensorTimestampNs = timestamp,
                    expectedGeneration = ndrGeneration
                )
            } else if (ndrQueued) {
                scheduleSensorLinearNdrRecordingWorker()
            }
            return
        }
        staleUnmatchedImage?.let { image -> runCatching { image.close() } }
        paired?.let(::enqueueIdleRawPreview)
        completedRawExposureJoin?.let(::queueCompletedRawOriginalExposureMeter)
    }

    /**
     * Build a [RawBufferDescriptor] from a RAW_SENSOR [Image] and the latest
     * matching [TotalCaptureResult]. Returns null if metadata is unavailable.
     */
    fun buildRawBufferDescriptor(image: Image): RawBufferDescriptor? {
        val result = resolveExactCaptureResult(image.timestamp) ?: return null
        return buildRawBufferDescriptor(image, result)
    }

    private fun buildRawBufferDescriptor(
        image: Image,
        result: TotalCaptureResult
    ): RawBufferDescriptor? = buildRawBufferDescriptor(
        width = image.width,
        height = image.height,
        stride = image.planes.firstOrNull()?.rowStride ?: image.width,
        result = result
    )

    private fun buildRawBufferDescriptor(
        width: Int,
        height: Int,
        stride: Int,
        result: TotalCaptureResult
    ): RawBufferDescriptor? {
        val chars = camera.getCameraCharacteristics() ?: return null
        // Prefer the exact per-frame saturation code when the HAL publishes it; ISO-dependent sensors
        // can move white/black together, and using a static ceiling would skew both highlight metrics
        // and colour-channel normalization in precisely the high-gain scenes NDR must handle well.
        val whiteLevel = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        val bayerArrangement = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val bayerPattern = BayerPattern.fromCameraCharacteristic(bayerArrangement)
        val blackLevelPattern: android.hardware.camera2.params.BlackLevelPattern? = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val blackLevelOffsets = if (blackLevelPattern != null) {
            intArrayOf(
                blackLevelPattern.getOffsetForIndex(0, 0),
                blackLevelPattern.getOffsetForIndex(1, 0),
                blackLevelPattern.getOffsetForIndex(0, 1),
                blackLevelPattern.getOffsetForIndex(1, 1)
            )
        } else {
            null
        }
        val calibration = RawSensorCalibrationPolicy.resolve(
            whiteLevel = whiteLevel,
            dynamicBlackLevel = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL),
            blackLevelOffsets = blackLevelOffsets,
            strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly,
            bayerPattern = bayerArrangement
        )
        val calibrationSignature = buildString {
            append(calibration.whiteLevel)
            calibration.blackLevelRggb.forEach { level ->
                append('|').append(level.toBits())
            }
        }
        if (calibrationSignature != lastRawSensorCalibrationSignature) {
            lastRawSensorCalibrationSignature = calibrationSignature
            NeoLogger.i(
                TAG,
                "RAW sensor calibration camera=${camera.currentCameraId} bayer=$bayerPattern " +
                    "blackRggb=${calibration.blackLevelRggb.joinToString(
                        prefix = "[",
                        postfix = "]"
                    )} white=${calibration.whiteLevel} source=${calibration.diagnostics.firstOrNull()}"
            )
        }
        val cct = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        // CP12-feed (s1-f03): resolve the heteroscedastic noise model through the single source of
        // truth — dynamic SENSOR_NOISE_PROFILE › SENSOR_INFO_SENSITIVITY_RANGE › conservative fallback —
        // and hand the descriptor the (noiseS, noiseO) the MFSR/denoise consumer reads. Every branch is
        // strictly positive, so a real profile can never reach the consumer stranded at noise=0.
        val captureNoiseProfile = result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.mapNotNull { pair ->
            val shot = pair.first
            val read = pair.second
            if (shot == null || read == null) null else shot to read
        }
        val noiseModel = CameraSensorNoiseModelPolicy.resolveDescriptorModel(
            captureNoiseProfile = captureNoiseProfile,
            sensitivityRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?.let { it.lower to it.upper }
        )
        val noiseInputs = CameraSensorNoiseModelPolicy.descriptorNoiseInputs(noiseModel)
        val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val sensitivityRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val normalizedGain = RawSensorGainNormalizationPolicy.resolve(
            sensorSensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY),
            postRawSensitivityBoostPercent =
                result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST),
            advertisedMinimumSensitivityIso = sensitivityRange?.lower,
            advertisedMaximumAnalogSensitivityIso =
                chars.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
        )

        // CP11 / s1-f09 — hand the demosaic the CORRECT forward sensor→XYZ(D50) matrix from the public
        // DNG SENSOR_FORWARD_MATRIX (mired-interpolated by the per-frame white-balance CCT), never the
        // inverted SENSOR_COLOR_TRANSFORM. Falls back to the identity-safe default when the camera does
        // not advertise public forward matrices, so an unsupported sensor keeps a neutral demosaic. The
        // demosaic stays device-gated (default OFF), so this changes no live pixel until the owner enables it.
        val dngForwardSensorToXyzD50 = runCatching {
            val calibration = camera.physicalImagingTraits.dynamicAcesIdtCalibration
            val cctKelvin = DynamicAcesIdtCalibration.estimateWhiteBalanceKelvin(cct)
                ?: calibration.illuminant2Kelvin
            NeoGamutApexPipeline.selectDemosaicSensorToXyz(
                available = calibration.usesPublicCamera2Matrices,
                forwardMatrix1 = calibration.forward1,
                forwardMatrix2 = calibration.forward2,
                cctKelvin = cctKelvin,
                cct1Kelvin = calibration.illuminant1Kelvin,
                cct2Kelvin = calibration.illuminant2Kelvin,
                fallback = DEFAULT_SENSOR_TO_XYZ
            )
        }.getOrDefault(DEFAULT_SENSOR_TO_XYZ)
        val resultSensorToLinearSrgb = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { transform ->
            FloatArray(9) { index ->
                transform.getElement(index % 3, index / 3).toFloat()
            }
        }
        val colorTransform = Camera2RawColorMetadataPolicy.resolveSensorToXyzD50(
            captureResultSensorToLinearSrgb = resultSensorToLinearSrgb,
            dngForwardFallback = dngForwardSensorToXyzD50
        )
        if (lastRawColorTransformSource != colorTransform.source) {
            lastRawColorTransformSource = colorTransform.source
            NeoLogger.i(
                TAG,
                "RAW colour transform source=${colorTransform.source} " +
                    "camera=${camera.currentCameraId} bayer=$bayerPattern"
            )
        }

        val semanticWhiteBalance = Camera2RawColorMetadataPolicy.semanticWhiteBalanceRggb(
            red = cct?.red ?: 1.0f,
            greenEven = cct?.greenEven ?: 1.0f,
            greenOdd = cct?.greenOdd ?: 1.0f,
            blue = cct?.blue ?: 1.0f,
            bayerPattern = bayerPattern
        )

        // Preview-only optical calibration. RAW_SENSOR bytes are never rewritten; the compact gain map
        // travels only in the debayer monitor descriptor and is applied to the linear debayer monitor.
        val lensShadingGrid = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { map ->
            runCatching {
                RawLensShadingGridPolicy.resample(
                    rows = map.rowCount,
                    columns = map.columnCount
                ) { channel, column, row ->
                    map.getGainFactor(channel, column, row)
                }
            }.getOrNull()
        }

        return RawBufferDescriptor(
            width = width,
            height = height,
            stride = stride,
            blackLevel = calibration.blackLevel,
            blackLevelRggb = calibration.blackLevelRggb,
            whiteLevel = calibration.whiteLevel,
            bayerPattern = bayerPattern,
            whiteBalanceR = semanticWhiteBalance[0],
            whiteBalanceGr = semanticWhiteBalance[1],
            whiteBalanceGb = semanticWhiteBalance[2],
            whiteBalanceB = semanticWhiteBalance[3],
            exposureTimeNs = exposureTime,
            analogGain = normalizedGain.analogGain,
            digitalGain = normalizedGain.digitalGain,
            noiseS = noiseInputs.noiseS,
            noiseO = noiseInputs.noiseO,
            sensorToXyz = colorTransform.sensorToXyzD50,
            lensShadingGridRggb = lensShadingGrid ?: RawLensShadingGridPolicy.identityGrid(),
            lensShadingCorrectionEnabled = lensShadingGrid != null
        )
    }

    /**
     * Applies colour validation only to the visible RAW monitor descriptor. The exact descriptor above
     * remains the source for DNG/NPRAW metadata, and RAW_SENSOR image bytes are never rewritten.
     */
    private fun stabilizeRawPreviewDescriptor(
        exact: RawBufferDescriptor,
        result: TotalCaptureResult,
        presentationEpoch: Long,
        sessionGeneration: Long
    ): RawBufferDescriptor? {
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { value ->
            Camera2RawColorMetadataPolicy.semanticWhiteBalanceRggb(
                red = value.red,
                greenEven = value.greenEven,
                greenOdd = value.greenOdd,
                blue = value.blue,
                bayerPattern = exact.bayerPattern
            )
        }
        val streamIdentity =
            "${camera.currentCameraId}:${exact.width}x${exact.height}:" +
                "${exact.bayerPattern.nativeValue}:session=$sessionGeneration:epoch=$presentationEpoch"
        val resolution = rawPreviewColorMetadataStabilizer.resolve(
            whiteBalanceRggb = gains,
            sensorToXyz = exact.sensorToXyz,
            streamIdentity = streamIdentity
        )
        val sourceChanged = lastRawPreviewColorMetadataSource != resolution.source
        if (sourceChanged) {
            lastRawPreviewColorMetadataSource = resolution.source
        }
        resolution.rejection?.let { rejection ->
            logRawPreviewColorMetadataRejection(
                rejection = rejection,
                resolutionSource = resolution.source,
                streamIdentity = streamIdentity,
                presentationEpoch = presentationEpoch,
                sessionGeneration = sessionGeneration,
                whiteBalanceRggb = gains,
                sensorToXyz = exact.sensorToXyz
            )
        }
        val stable = resolution.metadata ?: return null
        val proof = ProjectOwnedNdrColorProof.create(
            stabilizerGeneration = stable.stabilizerGeneration,
            calibrationGeneration = sessionGeneration,
            whiteBalanceRggb = stable.whiteBalanceRggb,
            sensorToXyz = stable.sensorToXyz
        )
        if (!proof.isValid) {
            NeoLogger.e(TAG, "RAW monitor colour proof rejected ${proof.diagnosticSummary()}")
            return null
        }
        if (sourceChanged) {
            NeoLogger.i(
                TAG,
                "RAW monitor colour metadata=${resolution.source} proof=${proof.diagnosticSummary()}"
            )
        }
        return exact.copy(
            whiteBalanceR = stable.whiteBalanceRggb[0],
            whiteBalanceGr = stable.whiteBalanceRggb[1],
            whiteBalanceGb = stable.whiteBalanceRggb[2],
            whiteBalanceB = stable.whiteBalanceRggb[3],
            sensorToXyz = stable.sensorToXyz,
            projectOwnedNdrColorProof = proof
        )
    }

    private fun logRawPreviewColorMetadataRejection(
        rejection: RawPreviewColorMetadataRejection,
        resolutionSource: RawPreviewColorMetadataSource,
        streamIdentity: String,
        presentationEpoch: Long,
        sessionGeneration: Long,
        whiteBalanceRggb: FloatArray?,
        sensorToXyz: FloatArray?
    ) {
        val decision = rawPreviewColorDiagnosticRateLimiter.evaluate(
            signature = "$streamIdentity|${rejection.reason}",
            elapsedRealtimeMs = SystemClock.elapsedRealtime()
        )
        if (!decision.shouldLog) return
        NeoLogger.w(
            TAG,
            "RAW monitor colour metadata rejected reason=${rejection.reason} " +
                "fallback=$resolutionSource epoch=$presentationEpoch session=$sessionGeneration " +
                "stream=$streamIdentity suppressed=${decision.suppressedSinceLastLog} " +
                rejection.diagnosticSummary(whiteBalanceRggb, sensorToXyz)
        )
    }

    /**
     * Uses the same characterized metadata resolver as the visible monitor, but owns independent
     * temporal state so a preview-surface epoch reset cannot perturb an active recording master.
     */
    private fun stabilizeSensorLinearNdrRecordingDescriptor(
        exact: RawBufferDescriptor,
        result: TotalCaptureResult,
        presentationEpoch: Long,
        sessionGeneration: Long
    ): RawBufferDescriptor? {
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { value ->
            Camera2RawColorMetadataPolicy.semanticWhiteBalanceRggb(
                red = value.red,
                greenEven = value.greenEven,
                greenOdd = value.greenOdd,
                blue = value.blue,
                bayerPattern = exact.bayerPattern
            )
        }
        val resolution = sensorLinearNdrRecordingColorMetadataStabilizer.resolve(
            whiteBalanceRggb = gains,
            sensorToXyz = exact.sensorToXyz,
            streamIdentity =
                "${camera.currentCameraId}:${exact.width}x${exact.height}:" +
                    "${exact.bayerPattern.nativeValue}:session=$sessionGeneration:recording"
        )
        val sourceChanged = lastSensorLinearNdrRecordingColorMetadataSource != resolution.source
        if (sourceChanged) {
            lastSensorLinearNdrRecordingColorMetadataSource = resolution.source
        }
        val stable = resolution.metadata ?: return null
        val proof = ProjectOwnedNdrColorProof.create(
            stabilizerGeneration = stable.stabilizerGeneration,
            calibrationGeneration = sessionGeneration,
            whiteBalanceRggb = stable.whiteBalanceRggb,
            sensorToXyz = stable.sensorToXyz
        )
        if (!proof.isValid) {
            NeoLogger.e(TAG, "NDR recording colour proof rejected ${proof.diagnosticSummary()}")
            return null
        }
        if (sourceChanged) {
            NeoLogger.i(
                TAG,
                "NDR recording colour metadata=${resolution.source} proof=${proof.diagnosticSummary()}"
            )
        }
        return exact.copy(
            whiteBalanceR = stable.whiteBalanceRggb[0],
            whiteBalanceGr = stable.whiteBalanceRggb[1],
            whiteBalanceGb = stable.whiteBalanceRggb[2],
            whiteBalanceB = stable.whiteBalanceRggb[3],
            sensorToXyz = stable.sensorToXyz,
            projectOwnedNdrColorProof = proof
        )
    }

    private fun buildRawMonitorBufferDescriptor(image: Image): RawBufferDescriptor? {
        val result = resolveMonitorCaptureResult(image.timestamp) ?: latestResult ?: return null
        val exact = buildRawBufferDescriptor(image, result) ?: return null
        val presentation = previewPresentationGate.snapshot()
        return stabilizeRawPreviewDescriptor(
            exact = exact,
            result = result,
            presentationEpoch = presentation.epoch,
            sessionGeneration = presentation.sessionGeneration
        )
    }

    fun destroy(): Boolean = rawCaptureLifecycleLock.withLock { destroyLocked() }

    private fun destroyLocked(): Boolean {
        if (destroyFinalized.get()) return true
        // Close all new ownership immediately, but do not publish physical destruction while the NDR
        // executor still needs to poll an accepted Vulkan/encoder tail. A retry observes this request
        // and resumes idempotent retirement without reopening any capture admission.
        destroyRequested.set(true)
        captureCadenceWarmupActive.set(false)
        exactCaptureResultWaiter.signalChanged()
        requestSensorLinearNdrRecordingIngressTeardown()
        if (captureLifecycleActive.get()) {
            runCatching { stopCaptureLocked() }
                .onFailure { Log.e(TAG, "RAW capture stop failed during manager destruction", it) }
        }
        closeRawWriterAdmission()
        runCatching { captureAudio.abort() }.onFailure {
            Log.e(TAG, "RAW audio retirement during destruction failed", it)
        }
        recordingAudioFailureListener = null
        // Invalidate metering before any executor teardown so already-running histogram work cannot
        // publish into a controller whose RAW capture owner is being destroyed.
        sensorLinearNdrRecordingLifecycleLock.withLock {
            invalidateRawOriginalExposureAnalyticsLocked()
        }
        if (!quiesceRawCaptureWorkers()) {
            Log.e(TAG, "RAW capture workers remained active; manager resources quarantined")
            return false
        }
        sensorLinearNdrRecordingLifecycleLock.withLock {
            finalizeRawCaptureResultIngress()
            captureLifecycleActive.set(false)
        }
        stopSensorLinearNdrRecordingIngress(drain = false, timeoutMs = 250L)
        if (!isSensorLinearNdrRecordingWorkerQuiescentForTeardown()) {
            Log.e(TAG, "NDR recorder ownership remained active; manager resources quarantined")
            return false
        }
        // Every NDR Java/native submission owner is now terminal. From this point no executor needs the
        // live-manager state, so the ordinary destroyed gates may safely suppress secondary work.
        destroyed.set(true)
        rawPreviewProcessor = null
        clearIdleRawPreviewFrames()
        if (!awaitIdleRawPreviewWorkerIdleForTeardown(timeoutMs = 250L)) {
            Log.e(TAG, "RAW preview worker remained active; manager resources quarantined")
            return false
        }
        sensorLinearNdrRecordingProcessor = null
        sensorLinearNdrRecordingCompletionPoller = null
        persistedFrameCommitListener = null
        sensorLinearNdrRecordingExecutor.shutdownNow()
        sensorLinearExposureAnalyticsPending.set(null)
        sensorLinearExposureAnalyticsExecutor.shutdownNow()
        rawPreviewColorMetadataStabilizer.reset()
        rawPreviewColorDiagnosticRateLimiter.reset()
        lastRawPreviewColorMetadataSource = null
        lastRawColorTransformSource = null
        lastRawSensorCalibrationSignature = null
        idleRawPreviewExecutor.shutdownNow()
        storagePreparationGeneration.incrementAndGet()
        val idleReservation = synchronized(storagePreparationLock) {
            storagePreparationFuture = null
            preparedStorage.also { preparedStorage = null }
        }
        idleReservation?.let(::discardPreparedStorage)
        storagePreparationExecutor.shutdownNow()
        // Writer and preview-tap ownership were proven above, before any dependent executor shutdown.
        directIOEngine?.close()
        directIOEngine = null
        compressedStreamUri?.let { uri ->
            if (framesWritten.get() > 0 && !captureAudio.required) {
                runCatching { publishPendingBinary(uri) }
                    .onFailure {
                        Log.w(TAG, "Unable to publish interrupted NPRAW capture; deleting pending row", it)
                        runCatching { deletePendingBinary(uri) }
                    }
            } else {
                runCatching { deletePendingBinary(uri) }
            }
        }
        compressedStreamUri = null
        if (framesWritten.get() == 0) runCatching { sessionDir?.delete() }
        writeExecutor.shutdown()
        previewTapExecutor.shutdown()
        previewTapBuffer = null
        val executorsFinalized = listOf(
            "SensorLinearNdrRecorder" to sensorLinearNdrRecordingExecutor,
            "SensorLinearExposureAnalytics" to sensorLinearExposureAnalyticsExecutor,
            "RawPreviewCompute" to idleRawPreviewExecutor,
            "RawStoragePreparation" to storagePreparationExecutor,
            "RawVideoWriter" to writeExecutor,
            "RawPreviewTap" to previewTapExecutor
        ).map { (name, executor) ->
            awaitExecutorTerminationForDestroy(name, executor)
        }.all { it }
        if (!executorsFinalized) {
            Log.e(TAG, "RAW manager executor teardown incomplete; resources retained for destroy retry")
            return false
        }
        destroyFinalized.set(true)
        return true
    }

    private fun awaitExecutorTerminationForDestroy(
        name: String,
        executor: java.util.concurrent.ExecutorService
    ): Boolean {
        if (executor.isTerminated) return true
        return try {
            val terminated = executor.awaitTermination(
                DESTROY_EXECUTOR_QUIESCE_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            if (!terminated) Log.e(TAG, "$name executor remained active during destroy")
            terminated
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // #9/#10 WRITE-LOOP QUIESCE — guarantee the single writeLoop has exited and the queue is emptied before
    // the engine is closed or writeQueue is recreated, so a stop-drain timeout never leaves a lingering loop
    // racing the next session (leaking Images) or writing to a closed Direct-I/O engine (orphan tail files).
    // ─────────────────────────────────────────────────────────────────────────
    private fun quiesceRawCaptureWorkers(): Boolean {
        if (!quiesceWriteLoop()) return false
        return awaitPreviewTapIdle(writeLoopExitTimeoutMs)
    }

    private fun quiesceWriteLoop(): Boolean {
        val handle = writeLoopHandle
        if (handle != null) {
            // isCapturing is already false at every caller, so the loop terminates once its queue empties.
            if (!awaitWriteLoopExit(handle.future, writeLoopExitTimeoutMs)) {
                // Storage stalled: force the loop's guard false by emptying the queue, then await once more.
                Log.w(TAG, "writeLoop did not exit in ${writeLoopExitTimeoutMs}ms; draining queue to force exit")
                drainAndCloseWriteQueue(handle.session.queue)
                if (!awaitWriteLoopExit(handle.future, writeLoopExitTimeoutMs)) {
                    Log.e(TAG, "writeLoop did not exit after forced drain; retaining all session resources")
                    return false
                }
            }
            if (writeLoopHandle === handle) writeLoopHandle = null
            drainAndCloseWriteQueue(handle.session.queue)
            return true
        }
        if (activeWrites.get() != 0) {
            Log.e(TAG, "RAW writer has active work without an owned Future; refusing teardown")
            return false
        }
        drainAndCloseWriteQueue()
        return true
    }

    private fun awaitWriteLoopExit(future: Future<*>, timeoutMs: Long): Boolean =
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
            true
        } catch (e: TimeoutException) {
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: Exception) {
            // ExecutionException etc.: the task has finished (with an error) — treat as exited.
            true
        }

    private fun awaitPreviewTapIdle(timeoutMs: Long): Boolean {
        if (Thread.currentThread().name.startsWith("RawPreviewTap")) return false
        val barrier = try {
            previewTapExecutor.submit { Unit }
        } catch (failure: Throwable) {
            if (previewTapExecutor.isTerminated && !previewTapBusy.get()) return true
            Log.e(TAG, "Unable to schedule RAW preview-tap quiescence barrier", failure)
            return false
        }
        val completed = try {
            barrier.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            true
        } catch (_: TimeoutException) {
            barrier.isDone && runCatching { barrier.get() }.isSuccess
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (failure: Throwable) {
            Log.e(TAG, "RAW preview-tap quiescence barrier failed", failure)
            false
        }
        if (!completed || previewTapBusy.get()) {
            Log.e(TAG, "RAW preview tap remained active; retaining capture resources for retry")
            return false
        }
        return true
    }

    private fun drainAndCloseWriteQueue(
        queue: ArrayBlockingQueue<Image> = writeQueue
    ) {
        while (true) {
            val image = queue.poll() ?: break
            cancelRawOriginalExposureMeter(image.timestamp)
            retireRawCaptureResultJoin(image.timestamp)
            runCatching { image.close() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WRITE LOOP (runs on writeExecutor)
    // ─────────────────────────────────────────────────────────────────────────

    private fun writeLoop(session: RawWriterSessionToken) {
        val queue = session.queue
        Log.d(TAG, "Write loop started")
        while (isCapturing.get() || queue.isNotEmpty()) {
            val waitMs = resolveWriterPollTimeoutMs
            val img = queue.poll(waitMs, TimeUnit.MILLISECONDS) ?: continue
            activeWrites.incrementAndGet()
            ownedWriterImages.incrementAndGet()
            val imageLease = RawFrameImageLease(img) {
                val remainingOwners = ownedWriterImages.decrementAndGet()
                check(remainingOwners >= 0) { "Distinct RAW Image ownership underflow" }
            }
            val writeStartElapsedMs = SystemClock.elapsedRealtime()
            try {
                val previewDeferredUntilStorage = when (compressionMode) {
                    RawCompressionMode.OFF -> {
                        writeDng(img, session)
                        false
                    }
                    else -> writeCompressedFrame(img, session, imageLease)
                }
                com.neoproc.neoproccamx.core.RuntimeStageTelemetry.recordDuration(
                    tag = TAG,
                    stage = "writeFrame",
                    durationMs = SystemClock.elapsedRealtime() - writeStartElapsedMs,
                    slowThresholdMs = SLOW_RAW_WRITE_MS
                )
                completeRawOriginalExposureMeterPixels(img)
                // Transfer at most one already-persisted Image to the low-priority monitor worker. Moving
                // the full-plane copy off this single disk-writer thread prevents the monitor from delaying
                // subsequent RAW persistence. The worker closes transferred ownership after its copy.
                if (!previewDeferredUntilStorage) {
                    tapRecordingPreview(img, session, imageLease)
                }
            } catch (e: Exception) {
                cancelRawOriginalExposureMeter(img.timestamp)
                val message = "RAW frame write failed: ${e.message}"
                if (compressionMode != RawCompressionMode.OFF) {
                    failStrictRawWrite(message, e)
                    drainAndCloseWriteQueue(queue)
                    break
                }
                markDroppedFrame()
                Log.e(TAG, "$message; exact DNG metadata is required", e)
            } finally {
                retireRawCaptureResultJoin(img.timestamp)
                imageLease.release()
                activeWrites.decrementAndGet()
            }
        }
        Log.d(TAG, "Write loop ended")
    }

    /**
     * Record-time live monitor: after a frame is safely on disk, copy its mosaic bytes into the reusable
     * [previewTapBuffer] on [previewTapExecutor] for the (half-res) debayer preview. A true return transfers
     * [image] ownership to that worker, which closes it immediately after copying; false leaves ownership
     * with the write loop. The single-slot gate bounds this to one retained RAW buffer, and the tap is skipped
     * whenever the writer is behind, so disk persistence remains the priority.
     */
    private fun tapRecordingPreview(
        image: Image,
        session: RawWriterSessionToken,
        imageLease: RawFrameImageLease
    ): Boolean {
        val processor = recordingPreviewBytesProcessor ?: return false
        val nowNs = System.nanoTime()
        if (nowNs - lastPreviewTapNs < PREVIEW_TAP_MIN_INTERVAL_NS) return false
        if (session.queue.size > PREVIEW_TAP_MAX_QUEUE_DEPTH) return false
        if (!previewTapBusy.compareAndSet(false, true)) return false

        val descriptor = buildRawMonitorBufferDescriptor(image)
        val plane = image.planes.firstOrNull()
        val rowStride = plane?.rowStride ?: 0
        if (descriptor == null || plane == null || rowStride <= 0) {
            previewTapBusy.set(false)
            return false
        }
        if (!imageLease.retain()) {
            previewTapBusy.set(false)
            return false
        }

        lastPreviewTapNs = nowNs
        try {
            previewTapExecutor.submit {
                try {
                    val src = plane.buffer.duplicate()
                    src.rewind()
                    val copyBytes = src.remaining()
                    val copyBuffer = previewTapBuffer
                        ?.takeIf { buffer -> buffer.capacity() >= copyBytes }
                        ?: java.nio.ByteBuffer.allocateDirect(copyBytes)
                            .order(java.nio.ByteOrder.nativeOrder())
                            .also { buffer -> previewTapBuffer = buffer }
                    copyBuffer.clear()
                    copyBuffer.put(src)
                    copyBuffer.rewind()
                    invokeRecordingPreviewProcessorIfCurrent(
                        session = session,
                        processor = processor,
                        buffer = copyBuffer,
                        descriptor = descriptor,
                        rowStride = rowStride
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "Record-time preview debayer tap failed: ${t.message}")
                } finally {
                    imageLease.release()
                    previewTapBusy.set(false)
                }
            }
            return true
        } catch (t: Throwable) {
            // Executor rejected (shutting down): clear the slot so future taps can proceed.
            imageLease.release()
            previewTapBusy.set(false)
            return false
        }
    }

    private fun writeDng(image: Image, session: RawWriterSessionToken) {
        val chars = camera.getCameraCharacteristics()
            ?: throw IllegalStateException("Camera characteristics unavailable for DNG")
        val result = awaitExactCaptureResult(image.timestamp, session)
            ?: throw IllegalStateException(
                "Exact CaptureResult unavailable for DNG timestamp=${image.timestamp}"
            )
        val sensorTimestampNs = image.timestamp
        val index = framesWritten.incrementAndGet()
        if (index == 1) {
            // Telemeter DNG colour-tag completeness once per session — a missing
            // ForwardMatrix/NoiseProfile means a third party will not render our neutral.
            val completeness = RawDngMetadataContract.fromCameraMetadata(chars, result)
            if (completeness.isComplete) Log.i(TAG, completeness.summary)
            else Log.w(TAG, completeness.summary)
        }
        val displayName = "frame_%06d.dng".format(index)
        lateinit var file: File
        lateinit var savedLabel: String
        var pendingUri: android.net.Uri? = null
        var legacyFile: File? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = insertPendingDng(displayName) ?: throw IllegalStateException("MediaStore insert failed")
                pendingUri = uri
                val resolver = context.contentResolver
                DngCreator(chars, result).use { dng ->
                    configureCinemaDng(dng)
                    resolver.openOutputStream(uri)?.use { out ->
                        dng.writeImage(out, image)
                    } ?: throw IllegalStateException("openOutputStream returned null")
                }
                val publish = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, publish, null, null)
                savedLabel = uri.toString()
            } else {
                val dir = sessionDir
                    ?: throw IllegalStateException("RAW session directory unavailable for DNG")
                file = File(dir, displayName)
                legacyFile = file
                DngCreator(chars, result).use { dng ->
                    configureCinemaDng(dng)
                    FileOutputStream(file).use { out ->
                        dng.writeImage(out, image)
                    }
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf("image/x-adobe-dng"),
                    null
                )
                savedLabel = file.absolutePath
            }
            publishFrameCount(index)
            recordPersistedFrameTelemetry(index, sensorTimestampNs)
            if (index == 1 || index % FRAME_LOG_SAMPLE_STEP == 0) {
                Log.v(TAG, "DNG frame $index -> $savedLabel")
            }
        } catch (e: Exception) {
            framesWritten.decrementAndGet()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                pendingUri?.let { uri ->
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            } else {
                legacyFile?.delete()
            }
            Log.e(TAG, "writeDng frame $index failed: ${e.message}")
            throw e
        }
    }

    /**
     * Apply cinema-grade, self-describing DNG metadata that DngCreator does not infer:
     * orientation, a descriptive label, and GPS when available. Colour tags
     * (Black/White level, Forward/Color matrices, NoiseProfile, illuminants) are emitted
     * automatically by DngCreator from the characteristics/result — see
     * [RawDngMetadataContract] for the completeness contract that guards them.
     */
    private fun configureCinemaDng(dng: DngCreator) {
        runCatching {
            dng.setOrientation(
                OrientationManager.exifOrientationForRotation(camera.videoOrientationHint())
            )
            // CP17: record the distortion-correction geometry alongside the per-frame DNG label so a
            // colourist/review knows whether the frame used sensor-native or corrected lens geometry.
            dng.setDescription("$CINEMA_DNG_DESCRIPTION | Geometry: ${camera.ispBypassDistortionGeometryLabel()}")
            camera.lastKnownLocation?.let { dng.setLocation(it) }
        }
    }

    private data class EncodedPacket(
        val metadata: NprawFrameCodec.EncodeMetadata,
        val packetSize: Int,
        val heapBytes: ByteArray? = null,
        val directBuffer: ByteBuffer? = null,
        val borrowedHeader: ByteArray? = null,
        val borrowedPayload: ByteBuffer? = null
    )

    /** @return true when storage completion owns the Image-backed payload and preview handoff. */
    private fun writeCompressedFrame(
        image: Image,
        session: RawWriterSessionToken,
        imageLease: RawFrameImageLease
    ): Boolean {
        val plane = image.planes.firstOrNull()
            ?: throw IllegalStateException("RAW frame has no sensor plane")
        val sensorTimestampNs = image.timestamp
        // Perf: the NPRAW header only needs the original plane size. Read the plane buffer's capacity
        // directly instead of allocating + copying a full ~25MB ByteArray per RAW frame just to read
        // its .size. The old extractPlaneBytes did duplicate().clear() then remaining(), which equals
        // capacity() — so originalSize is byte-identical while a per-frame alloc + GC churn is removed.
        val originalPlaneSize = plane.buffer.capacity()
        if (originalPlaneSize <= 0) {
            throw IllegalStateException("RAW frame has an empty sensor plane")
        }
        val index = compressedFrameSequence.incrementAndGet()
        val sensorWhiteLevel = resolveSensorWhiteLevel()
        // CP-RAW1: device-gated native-depth preservation — keep a >12-bit sensor word at full depth
        // (16-bit container, no companding) instead of the legacy 12-bit floor, only under strict
        // high-end on capable hardware. ≤12-bit sensors / non-strict devices stay byte-identical, so
        // this can only ADD RAW depth on a capable sensor — never downgrade. Isolated from the
        // HEVC/P010 encoder path (this is the separate DNG/NPRAW pipeline).
        val preserveNativeDepth = RawVideoBitDepthPolicy.shouldPreserveNativeVideoDepth(
            strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly,
            highEndHardwareSupported = camera.highEndHardwareSupported,
            sourceWhiteLevel = sensorWhiteLevel
        )
        // CP16: all NPRAW serialisation (pack/predict/compand/header) lives in the
        // single-source-of-truth NprawFrameCodec, which a decoder can round-trip and
        // the test-suite can golden-lock. The 14→12 bit reduction now uses an invertible
        // companding curve instead of a lossy linear right-shift.
        val direct = directIOEngine
            ?.takeIf { it.isOpen }
            ?: throw IllegalStateException("NPRAW packet stream writer is unavailable")
        val encodedPacket = if (compressionMode == RawCompressionMode.LOSSLESS_REALTIME) {
            val source = plane.buffer.duplicate().apply { clear() }
            if (NprawFrameCodec.realtimeLosslessStorageBitDepth(sensorWhiteLevel) == 12) {
                val packetSize = NprawFrameCodec.realtimeLosslessPacketBufferCapacity(
                    width = image.width,
                    height = image.height,
                    sensorWhiteLevel = sensorWhiteLevel
                )
                val prepared = RealtimeRawPacketAdmission.acquireAndEncode(
                    acquireBuffer = { direct.acquirePreparedWriteBuffer(packetSize) },
                    encodeInto = { destination ->
                        NprawFrameCodec.encodeRealtimeBlockRangeFrameInto(
                            rawPlane = source,
                            destination = destination,
                            width = image.width,
                            height = image.height,
                            rowStride = plane.rowStride,
                            pixelStride = plane.pixelStride,
                            timestamp = image.timestamp,
                            originalSize = originalPlaneSize,
                            sensorWhiteLevel = sensorWhiteLevel
                        )
                    },
                    recycleBuffer = direct::recyclePreparedWriteBuffer
                )
                EncodedPacket(
                    metadata = prepared.result,
                    packetSize = prepared.result.packetSize,
                    directBuffer = prepared.buffer
                )
            } else {
                val borrowed = NprawFrameCodec.encodeRealtimeLinear16BorrowedFrame(
                    rawPlane = source,
                    width = image.width,
                    height = image.height,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                    timestamp = image.timestamp,
                    originalSize = originalPlaneSize
                )
                if (borrowed != null) {
                    source.position(0)
                    source.limit(borrowed.payloadSize)
                    EncodedPacket(
                        metadata = borrowed,
                        packetSize = borrowed.packetSize,
                        borrowedHeader = borrowed.header,
                        borrowedPayload = source.slice()
                    )
                } else {
                    // Non-contiguous wider vendor planes retain the exact native RAW16 packer.
                    val packetSize = NprawFrameCodec.realtimeLinear16PacketSize(image.width, image.height)
                    val prepared = RealtimeRawPacketAdmission.acquireAndEncode(
                        acquireBuffer = { direct.acquirePreparedWriteBuffer(packetSize) },
                        encodeInto = { destination ->
                            NprawFrameCodec.encodeRealtimeLinear16FrameInto(
                                rawPlane = plane.buffer,
                                destination = destination,
                                width = image.width,
                                height = image.height,
                                rowStride = plane.rowStride,
                                pixelStride = plane.pixelStride,
                                timestamp = image.timestamp,
                                originalSize = originalPlaneSize
                            )
                        },
                        recycleBuffer = direct::recyclePreparedWriteBuffer
                    )
                    EncodedPacket(
                        metadata = prepared.result,
                        packetSize = prepared.result.packetSize,
                        directBuffer = prepared.buffer
                    )
                }
            }
        } else {
            val rawSamples = extractRawSamples(image, plane)
            if (rawSamples.isEmpty()) {
                throw IllegalStateException("RAW frame sample extraction returned no sensor codes")
            }
            val fallback = NprawFrameCodec.encodeFrame(
                mode = compressionMode,
                rawSamples = rawSamples,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                timestamp = image.timestamp,
                originalSize = originalPlaneSize,
                sensorWhiteLevel = sensorWhiteLevel,
                preserveNativeDepth = preserveNativeDepth
            )
            EncodedPacket(
                metadata = fallback,
                packetSize = fallback.bytes.size,
                heapBytes = fallback.bytes
            )
        }
        val encoded = encodedPacket.metadata

        // First captured frame: persist the develop-metadata sidecar next to the stream so the
        // offline RAW→NeoLog converter can debayer / white-balance / colour-transform correctly.
        // NprawFrameCodec omits WB gains / Bayer phase / sensor→XYZ; this fills that gap. Guarded so
        // a sidecar failure can never interrupt frame capture (RAW mode locks AWB, so a single
        // per-clip white-balance / matrix snapshot is exact for the whole clip).
        if (!developSidecarWritten.get()) {
            val exactResult = awaitExactCaptureResult(image.timestamp, session)
            if (exactResult == null) {
                Log.w(
                    TAG,
                    "Exact CaptureResult unavailable for RAW develop sidecar " +
                        "timestamp=${image.timestamp}; retrying on the next frame"
                )
            } else {
                runCatching {
                    val descriptor = buildRawBufferDescriptor(image, exactResult)
                        ?: throw IllegalStateException("Exact RAW descriptor unavailable")
                    // The sidecar describes the selected/applied Camera2 cadence, not this writer's
                    // permissive admission ceiling. Deriving it from minFrameIntervalNs mislabeled a
                    // physically measured 25 fps clip as 30 fps on the iQOO I2405.
                    val fpsHint = activeCaptureFps.get().takeIf { it in 1..240 }
                        ?: throw IllegalStateException("RAW capture FPS snapshot is unavailable")
                    val meta = RawDevelopMetadataSidecar.fromDescriptor(
                        descriptor = descriptor,
                        storageBitDepth = encoded.bitDepth,
                        sensorWhiteLevel = sensorWhiteLevel,
                        fps = fpsHint,
                        colorProfileTag = DEFAULT_DEVELOP_LOG_VARIANT,
                        sensorPixelModeMaximumResolutionUsed = sensorPixelModeMaximumResolutionUsed,
                        orientationHintDegrees = orientationHintDegrees,
                        audioExpected = captureAudio.required
                    )
                    writeNprawFrame(RawDevelopMetadataSidecar.FILE_NAME, RawDevelopMetadataSidecar.encode(meta))
                }.onSuccess {
                    // Publish completion only after the sidecar is durably written. A metadata miss
                    // or I/O failure leaves this false so a later exact frame can retry.
                    developSidecarWritten.set(true)
                }.onFailure {
                    Log.w(TAG, "RAW develop-metadata sidecar write failed; retrying: ${it.message}")
                }
            }
        }

        try {
            // The custom recording contract is one concatenated packet stream. There is deliberately
            // no per-frame-file fallback: a stream failure becomes a hard recording error.
            // enqueuePreparedWrite transfers the native direct packet without another 25 MB copy.
            val directPacket = encodedPacket.directBuffer
            val borrowedHeader = encodedPacket.borrowedHeader
            val borrowedPayload = encodedPacket.borrowedPayload
            val callback: (Boolean) -> Unit = { success ->
                if (success) {
                    onCompressedFramePersisted(
                        index = index,
                        sensorTimestampNs = sensorTimestampNs,
                        originalPlaneSize = originalPlaneSize,
                        encoded = encoded,
                        committedPacketBytes = encodedPacket.packetSize
                    )
                } else {
                    failStrictRawWrite("NPRAW stream writer failed to write packet $index")
                }
            }
            val accepted = if (borrowedHeader != null && borrowedPayload != null) {
                check(imageLease.retain()) { "RAW Image ownership retired before storage admission" }
                val completionDelivered = AtomicBoolean(false)
                val borrowedCompletion: (Boolean) -> Unit = { success ->
                    if (completionDelivered.compareAndSet(false, true)) {
                        try {
                            callback(success)
                            if (success) {
                                tapRecordingPreview(image, session, imageLease)
                            }
                        } finally {
                            imageLease.release()
                        }
                    } else {
                        Log.e(TAG, "Duplicate borrowed NPRAW completion ignored for packet $index")
                    }
                }
                val enqueued = try {
                    direct.enqueueBorrowedSegmentedWrite(
                        header = borrowedHeader,
                        payload = borrowedPayload,
                        payloadLength = encoded.payloadSize,
                        callback = borrowedCompletion
                    )
                } catch (failure: Throwable) {
                    if (completionDelivered.compareAndSet(false, true)) imageLease.release()
                    throw failure
                }
                if (!enqueued && completionDelivered.compareAndSet(false, true)) {
                    imageLease.release()
                }
                enqueued
            } else if (directPacket != null) {
                direct.enqueuePreparedWrite(directPacket, encodedPacket.packetSize, callback)
            } else {
                val bytes = encodedPacket.heapBytes
                    ?: throw IllegalStateException("NPRAW packet has no storage payload")
                direct.enqueueWrite(bytes, encodedPacket.packetSize, callback)
            }
            if (!accepted) {
                throw IllegalStateException("NPRAW stream writer rejected packet $index")
            }
            return borrowedPayload != null
        } catch (e: Exception) {
            throw e
        }
    }

    private fun onCompressedFramePersisted(
        index: Int,
        sensorTimestampNs: Long,
        originalPlaneSize: Int,
        encoded: NprawFrameCodec.EncodeMetadata,
        committedPacketBytes: Int
    ) {
        val written = framesWritten.incrementAndGet()
        storageWriteThroughputMeter.recordCommittedBytes(
            byteCount = committedPacketBytes.toLong(),
            committedAtNs = SystemClock.elapsedRealtimeNanos()
        )
        publishFrameCount(written)
        recordPersistedFrameTelemetry(index, sensorTimestampNs)
        if (index == 1 || index % FRAME_LOG_SAMPLE_STEP == 0) {
            Log.i(TAG, "NPRAW frame $index -> Stream mode=$compressionMode " +
                    "raw=$originalPlaneSize compressed=${encoded.payloadSize} " +
                    "bitDepth=${encoded.bitDepth} companding=${encoded.compandingType} " +
                    "v=${encoded.version} ratio=${"%.2f".format(Locale.US, encoded.compressionRatio)} " +
                    "written=$written")
        }
    }

    private fun recordPersistedFrameTelemetry(index: Int, sensorTimestampNs: Long) {
        firstPersistedSensorTimestampNs.compareAndSet(0L, sensorTimestampNs)
        lastPersistedSensorTimestampNs.set(sensorTimestampNs)
        persistedFrameCommitListener?.let { listener ->
            runCatching { listener(sensorTimestampNs) }
                .onFailure { failure ->
                    val failures = persistedFrameCommitCallbackFailures.incrementAndGet()
                    if (failures <= 4L || failures % 64L == 0L) {
                        Log.e(
                            TAG,
                            "RAW persisted-frame commit callback failed " +
                                "failures=$failures sensorTimestampNs=$sensorTimestampNs",
                            failure
                        )
                    }
                }
        }
        if (!firstPersistedFrameReported.compareAndSet(false, true)) return

        val persistedElapsedNs = SystemClock.elapsedRealtimeNanos()
        val startRequestElapsedNs = captureStartRequestElapsedNs.get()
        val gateOpenElapsedNs = captureGateOpenElapsedNs.get()
        NeoLogger.i(
            TAG,
            "RAW_FIRST_FRAME_PERSISTED index=$index mode=$compressionMode " +
                "startLatencyMs=${if (startRequestElapsedNs > 0L) (persistedElapsedNs - startRequestElapsedNs) / 1_000_000L else -1L} " +
                "gateLatencyMs=${if (gateOpenElapsedNs > 0L) (persistedElapsedNs - gateOpenElapsedNs) / 1_000_000L else -1L} " +
                "sensorTimestampNs=$sensorTimestampNs queueDepth=${writeQueue.size}"
        )
    }

    private fun tuneCaptureProfile() {
        val rawSize = camera.fullSensorSize()
        val area = rawSize.width.toLong() * rawSize.height.toLong()
        val strictHighEnd = HighEndQualityPolicy.enabled &&
            HighEndQualityPolicy.strictHighEndOnly &&
            camera.highEndHardwareSupported
        val baseInterval = when {
            area >= 50_000_000L -> 1_000_000_000L / 6L
            area >= 33_000_000L -> 1_000_000_000L / 8L
            area >= 20_000_000L -> 1_000_000_000L / 12L
            area >= 12_000_000L -> if (camera.highEndHardwareSupported) 1_000_000_000L / 6L else 1_000_000_000L / 4L
            camera.highEndHardwareSupported -> 1_000_000_000L / 12L
            else -> 1_000_000_000L / 8L
        }
        val qualityBoostedInterval = if (HighEndQualityPolicy.enabled && camera.highEndHardwareSupported) {
            (baseInterval.toDouble() / HighEndQualityPolicy.qualityBoost(recording = true, previewFps = 30f).toDouble()).toLong()
                .coerceAtLeast(if (strictHighEnd) 1_000_000_000L / 30L else 1_000_000_000L / 18L)
        } else {
            baseInterval
        }
        minFrameIntervalNs = qualityBoostedInterval
    }

    private fun resolveQueueCapacity(): Int {
        val rawSize = camera.fullSensorSize()
        val area = rawSize.width.toLong() * rawSize.height.toLong()
        val strictHighEnd = HighEndQualityPolicy.enabled &&
            HighEndQualityPolicy.strictHighEndOnly &&
            camera.highEndHardwareSupported
        val capacity = when {
            area >= 50_000_000L -> 3
            area >= 33_000_000L -> 4
            area >= 20_000_000L -> if (camera.highEndHardwareSupported) 5 else 4
            area >= 12_000_000L -> 3
            camera.highEndHardwareSupported -> 4
            else -> 3
        }
        val boostedCapacity = if (HighEndQualityPolicy.enabled && camera.highEndHardwareSupported) {
            if (strictHighEnd) (capacity + 6).coerceAtMost(16) else (capacity + 1).coerceAtMost(6)
        } else {
            capacity
        }
        return boostedCapacity
    }

    private fun createCaptureLocation(mode: RawCompressionMode): RawCaptureLocation {
        val primaryDirectory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mode != RawCompressionMode.OFF
        ) {
            Environment.DIRECTORY_DOCUMENTS
        } else {
            Environment.DIRECTORY_DCIM
        }
        val root = Environment.getExternalStoragePublicDirectory(primaryDirectory)
        val parent = File(root, "CamX")
        require(parent.isDirectory || parent.mkdirs()) {
            "Unable to create RAW output directory: ${parent.absolutePath}"
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val baseName = "NEO_RADIANCE_RAW_$timestamp"
        var suffix = 0
        var directory: File
        var folderName: String
        do {
            folderName = if (suffix == 0) baseName else "${baseName}_$suffix"
            directory = File(parent, folderName)
            suffix += 1
        } while (directory.exists())
        require(directory.mkdirs()) {
            "Unable to create RAW session directory: ${directory.absolutePath}"
        }
        val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "$primaryDirectory/CamX/$folderName"
        } else {
            null
        }
        return RawCaptureLocation(
            directory = directory,
            relativePath = relativePath,
            label = relativePath ?: directory.absolutePath
        )
    }

    private fun createPreparedRawCaptureStorage(
        mode: RawCompressionMode
    ): PreparedRawCaptureStorage {
        require(mode != RawCompressionMode.OFF) { "Per-frame DNG mode does not use NPRAW preallocation" }
        val location = createCaptureLocation(mode)
        val availableStorageBytes = reserveRawStorageForMaster(location.directory)
        var engine: AsyncDirectIOEngine? = null
        var uri: Uri? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val scoped = openScopedRawStreamWriter(
                    relativePath = requireNotNull(location.relativePath),
                    availableStorageBytes = availableStorageBytes
                ) ?: error("Unable to open pending NPRAW MediaStore stream")
                engine = scoped.first
                uri = scoped.second
            } else {
                engine = AsyncDirectIOEngine(
                    maxBorrowedPayloadWrites = RAW_BORROWED_STORAGE_CAPACITY
                ).apply {
                    open(
                        File(location.directory, "stream.npraw").absolutePath,
                        availableStorageBytes = availableStorageBytes
                    )
                }
            }
            val prepared = PreparedRawCaptureStorage(location, mode, engine, uri)
            require(prepared.isUsable(mode)) {
                "Strict NPRAW physical preallocation failed: " +
                    engine.preallocationResult.failure.orEmpty()
            }
            if (mode == RawCompressionMode.LOSSLESS_REALTIME) prewarmRealtimeWriter(engine)
            return prepared
        } catch (failure: Throwable) {
            engine?.close()
            uri?.let { runCatching { deletePendingBinary(it) } }
            runCatching { File(location.directory, "stream.npraw").delete() }
            runCatching { location.directory.delete() }
            throw failure
        }
    }

    private fun takePreparedStorage(expectedMode: RawCompressionMode): PreparedRawCaptureStorage? {
        val future = synchronized(storagePreparationLock) { storagePreparationFuture }
        if (future != null) {
            runCatching { future.get() }
                .onFailure { failure ->
                    Log.w(TAG, "Idle NPRAW reservation unavailable; preparing synchronously", failure)
                }
        }
        val ready = synchronized(storagePreparationLock) {
            storagePreparationGeneration.incrementAndGet()
            storagePreparationFuture = null
            preparedStorage.also { preparedStorage = null }
        } ?: return null
        if (ready.isUsable(expectedMode)) return ready
        discardPreparedStorage(ready)
        return null
    }

    private fun discardPreparedStorage(storage: PreparedRawCaptureStorage) {
        runCatching { storage.engine.close() }
        storage.streamUri?.let { uri ->
            runCatching { deletePendingBinary(uri) }
        }
        runCatching { File(storage.location.directory, "stream.npraw").delete() }
        runCatching { storage.location.directory.delete() }
    }

    /**
     * Retires only the unused idle NPRAW extent before another recorder claims physical storage.
     *
     * The generation invalidation makes an in-flight preparation reject its result. The serial
     * executor barrier then proves that native preallocation and its exact cleanup have completed
     * before the competing recorder performs its own storage admission. Active RAW/NDR recording
     * ownership is never modified by this operation.
     */
    fun retireIdleCaptureStorageForCompetingPipeline() {
        check(!destroyRequested.get() && !destroyed.get()) {
            "RAW idle storage cannot be handed off after manager teardown"
        }
        check(!captureLifecycleActive.get() && !sensorLinearNdrRecordingLifecycleActive.get()) {
            "RAW idle storage handoff refused while a RAW/NDR recording lifecycle is active"
        }

        val pendingPreparation: CompletableFuture<PreparedRawCaptureStorage?>?
        val readyReservation: PreparedRawCaptureStorage?
        synchronized(storagePreparationLock) {
            idleStoragePreparationBlockedByCompetingPipeline = true
            storagePreparationGeneration.incrementAndGet()
            pendingPreparation = storagePreparationFuture
            storagePreparationFuture = null
            readyReservation = preparedStorage
            preparedStorage = null
            pendingPreparation?.cancel(false)
        }

        readyReservation?.let(::discardPreparedStorage)
        StorageReservationHandoffBarrier.await(
            executor = storagePreparationExecutor,
            timeoutMs = IDLE_STORAGE_HANDOFF_TIMEOUT_MS,
            ownerLabel = "NPRAW"
        )
        check(synchronized(storagePreparationLock) {
            preparedStorage == null && storagePreparationFuture == null
        }) {
            "A new NPRAW idle reservation raced the competing-pipeline storage handoff"
        }
        NeoLogger.i(TAG, "NPRAW idle physical reservation retired for competing recorder")
    }

    /** Re-opens idle NPRAW preparation only when RAW is again the selected storage owner. */
    fun allowIdleCaptureStoragePreparation() {
        synchronized(storagePreparationLock) {
            check(!destroyRequested.get() && !destroyed.get()) {
                "RAW idle storage preparation cannot resume after manager teardown"
            }
            idleStoragePreparationBlockedByCompetingPipeline = false
        }
    }

    private fun prewarmRealtimeWriter(engine: AsyncDirectIOEngine?): Boolean {
        // Load JNI and reserve both packet buffers before opening the frame gate. First-frame class
        // loading / direct allocation therefore cannot consume the RAW reader's headroom.
        if (!NprawRealtimeNative.prewarm()) {
            Log.e(TAG, "Realtime NPRAW native encoder library is unavailable")
            return false
        }
        val reader = camera.rawReader ?: return false
        val writer = engine ?: return false
        return runCatching {
            val packetBytes = NprawFrameCodec.realtimeLosslessPacketBufferCapacity(
                width = reader.width,
                height = reader.height,
                sensorWhiteLevel = resolveSensorWhiteLevel()
            )
            writer.primePreparedWriteBuffer(packetBytes)
        }.getOrElse {
            Log.w(TAG, "Unable to prewarm realtime NPRAW packet buffer", it)
            false
        }
    }

    private fun insertPendingDng(displayName: String): android.net.Uri? {
        val relativePath = sessionRelativePath ?: return null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun insertPendingBinary(displayName: String): android.net.Uri? {
        val relativePath = sessionRelativePath ?: return null
        return insertPendingBinary(relativePath, displayName)
    }

    private fun insertPendingBinary(relativePath: String, displayName: String): android.net.Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        if (!RawPendingReservationPolicy.isOwnedPendingStream(displayName, relativePath, isPending = true)) {
            return context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
        }
        return synchronized(pendingRawStreamRegistryLock) {
            context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?.also { livePendingRawStreamUris += it.toString() }
        }
    }

    private fun publishPendingBinary(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val publish = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            val updated = context.contentResolver.update(uri, publish, null, null)
            check(updated == 1) {
                "MediaStore refused NPRAW publication update count=$updated uri=$uri"
            }
        } finally {
            unregisterPendingRawStream(uri)
        }
    }

    private fun deletePendingBinary(uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } finally {
            unregisterPendingRawStream(uri)
        }
    }

    private fun unregisterPendingRawStream(uri: Uri) {
        synchronized(pendingRawStreamRegistryLock) {
            livePendingRawStreamUris -= uri.toString()
        }
    }

    @Suppress("DEPRECATION")
    private fun cleanupOrphanedPendingRawStreams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            synchronized(pendingRawStreamRegistryLock) {
                if (orphanPendingRawStreamsCleaned) return
                val collection = MediaStore.Files.getContentUri("external")
                val pendingCollection = MediaStore.setIncludePending(collection)
                val projection = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.IS_PENDING,
                    MediaStore.MediaColumns.SIZE
                )
                val selection = "${MediaStore.MediaColumns.IS_PENDING}=?"
                val cursor = context.contentResolver.query(
                    pendingCollection,
                    projection,
                    selection,
                    arrayOf("1"),
                    null
                ) ?: return
                val orphans = mutableListOf<Triple<Uri, String, Long>>()
                var candidates = 0
                cursor.use {
                    val idColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val pathColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    val pendingColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                    val sizeColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (it.moveToNext()) {
                        candidates += 1
                        val displayName = it.getString(nameColumn)
                        val relativePath = it.getString(pathColumn)
                        val pending = it.getInt(pendingColumn) == 1
                        val uri = ContentUris.withAppendedId(collection, it.getLong(idColumn))
                        if (RawPendingReservationPolicy.isOwnedPendingStream(
                                displayName = displayName,
                                relativePath = relativePath,
                                isPending = pending
                            ) && uri.toString() !in livePendingRawStreamUris
                        ) {
                            orphans += Triple(uri, relativePath, it.getLong(sizeColumn))
                        }
                    }
                }
                NeoLogger.i(
                    TAG,
                    "NPRAW orphan audit pendingCandidates=$candidates orphans=${orphans.size} " +
                        "live=${livePendingRawStreamUris.size}"
                )
                var cleanupFailed = false
                orphans.forEach { (uri, relativePath, size) ->
                    val deleted = runCatching {
                        context.contentResolver.delete(uri, null, null)
                    }.onFailure {
                        cleanupFailed = true
                        NeoLogger.w(TAG, "Unable to remove orphaned NPRAW reservation $uri", it)
                    }.getOrDefault(0)
                    if (deleted > 0) {
                        runCatching {
                            File(Environment.getExternalStorageDirectory(), relativePath).delete()
                        }
                        NeoLogger.i(
                            TAG,
                            "Removed orphaned NPRAW reservation uri=$uri bytes=$size path=$relativePath"
                        )
                    }
                }
                if (!cleanupFailed) orphanPendingRawStreamsCleaned = true
            }
        }.onFailure {
            NeoLogger.w(TAG, "Unable to audit orphaned NPRAW reservations", it)
        }
    }

    private fun openScopedRawStreamWriter(availableStorageBytes: Long): AsyncDirectIOEngine? {
        val relativePath = sessionRelativePath ?: return null
        val scoped = openScopedRawStreamWriter(relativePath, availableStorageBytes) ?: return null
        compressedStreamUri = scoped.second
        return scoped.first
    }

    private fun openScopedRawStreamWriter(
        relativePath: String,
        availableStorageBytes: Long
    ): Pair<AsyncDirectIOEngine, Uri>? {
        val uri = insertPendingBinary(relativePath, "stream.npraw") ?: return null
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "w")
            if (pfd != null) {
                AsyncDirectIOEngine(
                    maxBorrowedPayloadWrites = RAW_BORROWED_STORAGE_CAPACITY
                ).apply { open(pfd, availableStorageBytes) } to uri
            } else {
                deletePendingBinary(uri)
                null
            }
        } catch (e: Exception) {
            deletePendingBinary(uri)
            Log.w(TAG, "Unable to open scoped RAW stream writer: ${e.message}")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun reserveRawStorageForMaster(directory: File? = sessionDir): Long {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val allocatableBytes = runCatching {
            storageManager.getAllocatableBytes(StorageManager.UUID_DEFAULT)
        }.getOrElse { directory?.usableSpace ?: 0L }
        val plan = RecordingPreallocationPolicy.raw(allocatableBytes)
        val usableBytes = (directory?.usableSpace ?: 0L).coerceAtLeast(0L)
        if (StorageReservationRequestPolicy.shouldRequestAllocation(usableBytes, plan)) {
            runCatching {
                storageManager.allocateBytes(StorageManager.UUID_DEFAULT, plan.reservationBytes)
            }.onFailure { failure ->
                Log.w(
                    TAG,
                    "Unable to reclaim cache space before NPRAW fallocate; " +
                        "the filesystem reservation will still be attempted: ${failure.message}"
                )
            }
        }
        return runCatching { directory?.usableSpace ?: usableBytes }
            .getOrDefault(usableBytes)
            .coerceAtLeast(0L)
    }

    private fun publishCaptureAudioSidecar(): Uri? {
        val source = requireNotNull(captureAudio.sidecar)
        check(source.isFile && source.length() > 0L) { "Required RAW audio is missing" }
        val displayName = Npv10AudioSidecarPolicy.sidecarFor(File("stream.npraw")).name
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val target = File(requireNotNull(sessionDir), displayName)
            check(!target.exists()) { "RAW audio target already exists" }
            FileOutputStream(target).use { output ->
                source.inputStream().use { it.copyTo(output) }
                output.fd.sync()
            }
            check(target.inputStream().use(::captureAudioDigest).contentEquals(
                source.inputStream().use(::captureAudioDigest)
            )) { "RAW audio copy differs from the recorded sidecar" }
            return null
        }
        val uri = insertPendingBinary(displayName) ?: error("RAW audio MediaStore insert failed")
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "w")
                ?: error("RAW audio output could not be opened")
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                source.inputStream().use { it.copyTo(output) }
                output.fd.sync()
            }
            val sourceDigest = source.inputStream().use(::captureAudioDigest)
            val storedDigest = context.contentResolver.openInputStream(uri)?.use(::captureAudioDigest)
                ?: error("RAW audio readback could not be opened")
            check(sourceDigest.contentEquals(storedDigest)) {
                "RAW audio MediaStore readback differs from the captured AAC sidecar"
            }
            publishPendingBinary(uri)
            return uri
        } catch (failure: Throwable) {
            runCatching { deletePendingBinary(uri) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun captureAudioDigest(input: java.io.InputStream): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(count > 0) { "RAW audio readback made no progress" }
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }

    private fun writeNprawFrame(displayName: String, payload: ByteArray): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = insertPendingBinary(displayName)
                ?: throw IllegalStateException("MediaStore insert failed")
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(payload)
                    out.flush()
                } ?: throw IllegalStateException("openOutputStream returned null")
                publishPendingBinary(uri)
                uri.toString()
            } catch (e: Exception) {
                deletePendingBinary(uri)
                throw e
            }
        } else {
            val dir = sessionDir ?: throw IllegalStateException("RAW session directory unavailable")
            val file = File(dir, displayName)
            RawFrameFileWriter.write(file, payload)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("application/octet-stream"),
                null
            )
            file.absolutePath
        }
    }

    private fun extractRawSamples(image: Image, plane: Image.Plane): IntArray {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return IntArray(0)

        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride.coerceAtLeast(2)
        val buffer = plane.buffer.duplicate()
        val limit = buffer.limit()
        val samples = IntArray(width * height)

        // NOTE: a bulk `asShortBuffer().get(shorts)` fast path was tried here and REVERTED — on the vivo
        // I2405 the SIMD bulk memcpy (SetPrimitiveArrayRegion -> __memcpy_aarch64_simd) over-reads the
        // RAW gralloc direct buffer's tail into the next (unmapped) page and SIGSEGVs, even though the
        // requested length is within `limit`. The per-pixel bounds-checked loop below reads one short at
        // a time and is the proven-safe path, so it stays. Any future bulk optimisation MUST bound the
        // copy strictly inside the mapped region and be device-verified before shipping.
        for (y in 0 until height) {
            val rowStart = y * rowStride
            val rowBase = y * width
            for (x in 0 until width) {
                val idx = rowStart + x * pixelStride
                if (idx + 1 >= limit) {
                    samples[rowBase + x] = 0
                    continue
                }
                val lo = buffer.get(idx).toInt() and 0xFF
                val hi = buffer.get(idx + 1).toInt() and 0xFF
                samples[rowBase + x] = (hi shl 8) or lo
            }
        }
        return samples
    }

    private fun resolveSensorWhiteLevel(): Int =
        camera.characteristics
            ?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?.coerceIn(1, 0xFFFF)
            ?: 0x0FFF

    private fun markDroppedFrame() {
        val count = droppedFrameCount.incrementAndGet()
        publishDroppedFrameCount(count)
    }

    private fun strictHighEndRawCaptureActive(): Boolean =
        captureLifecycleActive.get() && compressionMode != RawCompressionMode.OFF

    private fun failStrictRawMaster(message: String, throwable: Throwable? = null) {
        closeRawWriterAdmission()
        sensorLinearNdrRecordingLifecycleLock.withLock {
            markDroppedFrame()
            invalidateRawOriginalExposureAnalyticsLocked()
            _state.value = RawVideoState.Error("$message; refusing dropped-frame RAW master")
            logDroppedFrameWarning(message, throwable)
        }
    }

    /**
     * Callback-scoped strict failure. The exact admission token is revalidated while lifecycle and
     * writer ownership are both held, so a delayed callback can never close or mark a restarted clip.
     */
    private fun failStrictRawMasterIfCurrent(
        token: RawCaptureIngressToken,
        message: String,
        throwable: Throwable? = null
    ): Boolean = sensorLinearNdrRecordingLifecycleLock.withLock {
        rawWriterAdmissionLock.withLock {
            if (!rawCaptureIngressIsCurrentLocked(token) ||
                compressionMode == RawCompressionMode.OFF
            ) {
                return@withLock false
            }
            rawCaptureIngressAdmission.set(null)
            rawWriterAdmissionGeneration = nextRawWriterGeneration(rawWriterAdmissionGeneration)
            isPaused.set(false)
            isCapturing.set(false)
            markDroppedFrame()
            invalidateRawOriginalExposureAnalyticsLocked()
            _state.value = RawVideoState.Error("$message; refusing dropped-frame RAW master")
            logDroppedFrameWarning(message, throwable)
            true
        }
    }

    /** Non-strict callback telemetry is also exact-token scoped to protect restarted counters/state. */
    private fun markRawDropIfCurrent(
        token: RawCaptureIngressToken,
        message: String,
        throwable: Throwable? = null
    ): Boolean = sensorLinearNdrRecordingLifecycleLock.withLock {
        rawWriterAdmissionLock.withLock {
            if (!rawCaptureIngressIsCurrentLocked(token)) return@withLock false
            markDroppedFrame()
            logDroppedFrameWarning(message, throwable)
            true
        }
    }

    private fun failStrictRawWrite(message: String, throwable: Throwable? = null) {
        if (strictHighEndRawCaptureActive()) {
            failStrictRawMaster(message, throwable)
        } else {
            markDroppedFrame()
            Log.e(TAG, message, throwable)
        }
    }

    private fun publishFrameCount(currentCount: Int, force: Boolean = false) {
        if (!force && currentCount - lastPublishedFrameCount < UI_FRAME_COUNTER_STEP) return
        lastPublishedFrameCount = currentCount
        _frameCount.value = currentCount
    }

    private fun publishDroppedFrameCount(currentCount: Int, force: Boolean = false) {
        if (!force && currentCount - lastPublishedDroppedCount < UI_DROP_COUNTER_STEP) return
        lastPublishedDroppedCount = currentCount
        _droppedFrames.value = currentCount
    }

    private fun logDroppedFrameWarning(message: String, throwable: Throwable? = null) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDroppedLogElapsedMs < DROP_LOG_THROTTLE_MS) return
        lastDroppedLogElapsedMs = now
        val suffix = " (drops=${droppedFrameCount.get()})"
        if (throwable != null) {
            Log.w(TAG, "$message$suffix", throwable)
        } else {
            Log.w(TAG, "$message$suffix")
        }
    }

    private fun updateQueueHighWatermark(currentDepth: Int) {
        while (true) {
            val previous = queueHighWatermark.get()
            if (currentDepth <= previous) return
            if (queueHighWatermark.compareAndSet(previous, currentDepth)) return
        }
    }

    /**
     * Non-blocking exact lookup for authoritative descriptor callers. An exact sensor timestamp and
     * the Image's immutable writer owner are both required; adjacent and global-latest metadata are
     * never eligible.
     */
    private fun resolveExactCaptureResult(imageTimestamp: Long): TotalCaptureResult? =
        resultLock.withLock {
            val owner = pendingRawCaptureResultsByTimestamp[imageTimestamp]
                ?: return@withLock null
            val exact = recentResultsByTimestamp[imageTimestamp] ?: return@withLock null
            if (exact.sessionGeneration != owner.cameraSessionGeneration ||
                exact.rawWriterSession !== owner.session
            ) {
                recentResultsByTimestamp.remove(imageTimestamp)
                return@withLock null
            }
            recentResultsByTimestamp.remove(imageTimestamp)
            pendingRawCaptureResultsByTimestamp.remove(imageTimestamp)
            exact.result
        }

    /**
     * Writer-thread-only bounded join for DNG and NPRAW develop metadata. The condition releases
     * [resultLock] while waiting, so Camera2/ImageReader callback threads never sleep or wait. Stop
     * keeps an already-owned timestamp eligible through drain; destroy/final teardown wakes and
     * fails closed. Object identity prevents a timestamp from being relabelled across writer epochs.
     */
    private fun awaitExactCaptureResult(
        imageTimestamp: Long,
        expectedSession: RawWriterSessionToken
    ): TotalCaptureResult? {
        var expectedOwner: RawCaptureIngressToken? = null
        return exactCaptureResultWaiter.await(
            timeoutMs = EXACT_CAPTURE_RESULT_WAIT_TIMEOUT_MS
        ) probe@{
            if (destroyRequested.get() || destroyed.get()) {
                return@probe ExactMetadataWaiter.Probe.Terminal
            }
            val currentOwner = pendingRawCaptureResultsByTimestamp[imageTimestamp]
                ?: return@probe ExactMetadataWaiter.Probe.Terminal
            if (currentOwner.session !== expectedSession ||
                currentOwner.session.generation != expectedSession.generation ||
                currentOwner.session.queue !== expectedSession.queue ||
                currentOwner.cameraSessionGeneration != camera.sessionGeneration.get()
            ) {
                return@probe ExactMetadataWaiter.Probe.Terminal
            }
            val owner = expectedOwner ?: currentOwner.also { expectedOwner = it }
            if (currentOwner !== owner) return@probe ExactMetadataWaiter.Probe.Terminal

            val exact = recentResultsByTimestamp[imageTimestamp]
                ?: return@probe ExactMetadataWaiter.Probe.Pending
            if (exact.sessionGeneration != owner.cameraSessionGeneration ||
                exact.rawWriterSession !== expectedSession
            ) {
                // A stale Camera2 generation can never satisfy this Image. Remove only the bad
                // cache entry and continue waiting for the correctly-owned exact callback.
                recentResultsByTimestamp.remove(imageTimestamp)
                return@probe ExactMetadataWaiter.Probe.Pending
            }

            recentResultsByTimestamp.remove(imageTimestamp)
            pendingRawCaptureResultsByTimestamp.remove(imageTimestamp)
            ExactMetadataWaiter.Probe.Ready(exact.result)
        }
    }

    /**
     * Monitor-only lookup. Nearest/latest metadata is acceptable for a non-authoritative live tap,
     * but this function is intentionally never used for persisted DNG or NPRAW sidecar metadata.
     */
    private fun resolveMonitorCaptureResult(imageTimestamp: Long): TotalCaptureResult? {
        resultLock.withLock {
            recentResultsByTimestamp.remove(imageTimestamp)?.let { exact ->
                pendingRawCaptureResultsByTimestamp.remove(imageTimestamp)
                return exact.result
            }
            val nearest = recentResultsByTimestamp.minByOrNull { (timestamp, _) ->
                kotlin.math.abs(timestamp - imageTimestamp)
            }
            if (nearest != null && kotlin.math.abs(nearest.key - imageTimestamp) <= 50_000_000L) {
                return nearest.value.result
            }
        }
        return null
    }

    private fun stopDrainTimeoutMs(): Int {
        val rawSize = camera.fullSensorSize()
        val area = rawSize.width.toLong() * rawSize.height.toLong()
        return when {
            area >= 50_000_000L -> 12_000
            area >= 33_000_000L -> 8_000
            area >= 20_000_000L -> 6_000
            else -> 4_000
        }
    }

// ── State types ───────────────────────────────────────────────────────────────

    /**
     * Opens and physically allocates the final NPRAW stream while RAW mode is idle. The prepared
     * MediaStore row stays pending (invisible to other apps) and is adopted without reopening or
     * reallocating when the shutter is pressed.
     */
    fun prepareCaptureStorageAsync(
        requestedMode: RawCompressionMode = RawCompressionMode.LOSSLESS_REALTIME
    ) {
        val resolvedMode = RawVideoContractPolicy.resolveVideoCaptureMode(requestedMode)
        if (resolvedMode == RawCompressionMode.OFF || destroyRequested.get() || destroyed.get() ||
            captureLifecycleActive.get() || sensorLinearNdrRecordingLifecycleActive.get()
        ) return

        var stale: PreparedRawCaptureStorage? = null
        val generation: Long
        val promise: CompletableFuture<PreparedRawCaptureStorage?>
        synchronized(storagePreparationLock) {
            if (idleStoragePreparationBlockedByCompetingPipeline) return
            preparedStorage?.let { ready ->
                if (ready.isUsable(resolvedMode)) return
                stale = ready
                preparedStorage = null
            }
            if (storagePreparationFuture != null) return
            generation = storagePreparationGeneration.get()
            promise = CompletableFuture()
            storagePreparationFuture = promise
        }

        runCatching {
            storagePreparationExecutor.execute {
                stale?.let(::discardPreparedStorage)
                var created: PreparedRawCaptureStorage? = null
                var accepted = false
                try {
                    cleanupOrphanedPendingRawStreams()
                    created = createPreparedRawCaptureStorage(resolvedMode)
                    synchronized(storagePreparationLock) {
                        accepted = !destroyRequested.get() && !destroyed.get() &&
                            generation == storagePreparationGeneration.get()
                        if (accepted) preparedStorage = created
                        if (storagePreparationFuture === promise) storagePreparationFuture = null
                    }
                    if (accepted) {
                        val result = created.engine.preallocationResult
                        NeoLogger.i(
                            TAG,
                            "NPRAW idle physical reservation ready method=${result.method} " +
                                "bytes=${result.reservedBytes} path=${created.location.label}"
                        )
                        promise.complete(created)
                    } else {
                        created.let(::discardPreparedStorage)
                        promise.complete(null)
                    }
                } catch (failure: Throwable) {
                    created?.let(::discardPreparedStorage)
                    synchronized(storagePreparationLock) {
                        if (storagePreparationFuture === promise) storagePreparationFuture = null
                    }
                    Log.w(TAG, "Unable to prepare idle NPRAW physical reservation", failure)
                    promise.completeExceptionally(failure)
                }
            }
        }.onFailure { failure ->
            synchronized(storagePreparationLock) {
                if (storagePreparationFuture === promise) storagePreparationFuture = null
            }
            promise.completeExceptionally(failure)
        }
    }

}

sealed class RawVideoState {
    object Idle : RawVideoState()
    data class Capturing(val path: String) : RawVideoState()
    data class Done(val frameCount: Int, val path: String) : RawVideoState()
    data class Error(val msg: String) : RawVideoState()
}
