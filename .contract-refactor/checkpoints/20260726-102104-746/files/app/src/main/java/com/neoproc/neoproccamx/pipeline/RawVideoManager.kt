package com.neoproc.neoproccamx.pipeline

import android.content.ContentValues
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
import com.neoproc.neoproccamx.camera.CameraSensorNoiseModelPolicy
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.HotPathScheduling
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.RawVideoContractPolicy
import com.neoproc.neoproccamx.recording.RecordingPreallocationMethod
import com.neoproc.neoproccamx.recording.RecordingPreallocationPolicy
import com.neoproc.neoproccamx.recording.StorageReservationRequestPolicy
import com.neoproc.neoproccamx.core.RuntimeStageTelemetry
import com.neoproc.neoproccamx.camera.DynamicAcesIdtCalibration
import com.neoproc.neoproccamx.color.NeoGamutApexPipeline
import com.neoproc.neoproccamx.rendering.BayerPattern
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import com.neoproc.neoproccamx.rendering.RawBufferDescriptor
import com.neoproc.neoproccamx.utils.OrientationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.media.Image

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
        private const val STRICT_MASTER_ENQUEUE_TIMEOUT_MS = 2_500L
        private const val DEFAULT_RAW_READER_MAX_IMAGES = 16
        private const val CINEMA_DNG_DESCRIPTION = "NeoProCamX NeoRadiance RAW video frame"
        // Default NeoLog variant hint recorded in the develop-metadata sidecar; the offline
        // converter lets the user pick the final variant, defaulting from this.
        private const val DEFAULT_DEVELOP_LOG_VARIANT = "N-LOG-BASE"
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

    // ── Internal ──────────────────────────────────────────────────────────────
    // Frame-accept gate: onRawFrameAvailable only enqueues while this is true. Opened LAST in
    // startCapture() — after writeQueue is recreated and the session is fully set up — and cleared
    // first in stopCapture(), so a frame can never be offered to a stale/undrained queue.
    private val isCapturing  = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    // Start/stop re-entrancy guard. Held for the ENTIRE capture lifecycle (including the brief setup
    // window before [isCapturing] opens) so a second startCapture()/double stopCapture() is rejected
    // even while the frame-accept gate is still closed.
    private val captureLifecycleActive = AtomicBoolean(false)
    private val framesWritten = AtomicInteger(0)
    private val compressedFrameSequence = AtomicInteger(0)
    private val activeWrites = AtomicInteger(0)
    private val droppedFrameCount = AtomicInteger(0)
    private val queueHighWatermark = AtomicInteger(0)
    // Write the per-clip RAW develop-metadata sidecar exactly once (on the first captured frame).
    private val developSidecarWritten = AtomicBoolean(false)
    @Volatile private var lastDroppedLogElapsedMs = 0L
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

    // #9/#10 fix: handle to the in-flight writeLoop task so stop/startCapture can guarantee it has fully
    // EXITED before the engine is closed or writeQueue is recreated. A stop-drain timeout otherwise leaves
    // the loop running against the old queue / a closed engine.
    @Volatile private var writeLoopFuture: Future<*>? = null
    // Bound (ms) for awaiting the writeLoop to exit during quiesce. The main drain wait already gave the loop
    // time to finish naturally, so on the normal path it exits within one poll cycle.
    private val writeLoopExitTimeoutMs = 2_000L

    // Latest capture result for DNG metadata (updated on camera bgHandler thread)
    @Volatile private var latestResult: TotalCaptureResult? = null
    private val resultLock = Any()
    // ARCHITECTURAL FIX: 128-entry buffer provides ~2.1s metadata safety window at 60fps.
    // Prevents DNG metadata fallback during sustained I/O backpressure on UFS 2.x storage.
    private val recentResultsByTimestamp =
        object : LinkedHashMap<Long, TotalCaptureResult>(144, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Long, TotalCaptureResult>?
            ): Boolean = size > 128
        }

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
    @Volatile var rawPreviewProcessor: ((Image, RawBufferDescriptor) -> Boolean)? = null

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
     * Begin capturing RAW frames. Creates the session output directory.
     * Must be called after the camera session is active.
     */
    fun startCapture() {
        if (captureLifecycleActive.getAndSet(true)) {
            Log.w(TAG, "startCapture() called while already capturing"); return
        }
        // #9/#10 fix: ensure any writeLoop left running by a previous stop-drain timeout has fully exited and
        // its queue is closed out BEFORE we recreate writeQueue / open the engine below. isCapturing is
        // already false here, so the loop can terminate; on a stall this drains+closes the leftover Images
        // instead of leaking them when the fresh @Volatile writeQueue reference is assigned.
        quiesceWriteLoop()
        framesWritten.set(0)
        compressedFrameSequence.set(0)
        activeWrites.set(0)
        droppedFrameCount.set(0)
        queueHighWatermark.set(0)
        developSidecarWritten.set(false)
        _frameCount.value = 0
        _droppedFrames.value = 0
        lastPublishedFrameCount = 0
        lastPublishedDroppedCount = 0
        lastDroppedLogElapsedMs = 0L
        compressionMode = RawVideoContractPolicy.resolveVideoCaptureMode(compressionMode)
        tuneCaptureProfile()
        val rawReaderBudget = camera.rawReader?.maxImages ?: DEFAULT_RAW_READER_MAX_IMAGES
        writeQueue = ArrayBlockingQueue(
            RawVideoBackpressurePolicy.resolveSafeQueueCapacity(
                requestedQueueCapacity = resolveQueueCapacity(),
                readerMaxImages = rawReaderBudget,
                reservedActiveWrites = 1
            )
        )
        sessionDir = null
        sessionRelativePath = null
        sessionLocationLabel = null
        compressedStreamUri = null
        lastFrameNs = 0L
        lastPreviewTapNs = 0L
        previewTapBusy.set(false)
        isPaused.set(false)

        // Create output directory
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val sessionFolder = "NEO_RADIANCE_RAW_$ts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val primaryDir = if (compressionMode == RawCompressionMode.OFF) {
                Environment.DIRECTORY_DCIM
            } else {
                Environment.DIRECTORY_DOCUMENTS
            }
            sessionRelativePath = "$primaryDir/CamX/$sessionFolder"
            sessionLocationLabel = sessionRelativePath
            val root = Environment.getExternalStoragePublicDirectory(primaryDir)
            sessionDir = File(root, "CamX/$sessionFolder").also { it.mkdirs() }
        } else {
            val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            sessionDir = File(root, "CamX/$sessionFolder").also { it.mkdirs() }
            sessionLocationLabel = sessionDir?.absolutePath
        }

        val sessionLocation = sessionLocationLabel.orEmpty()
        _state.value = RawVideoState.Capturing(sessionLocation)
        Log.i(TAG, "RAW capture started -> $sessionLocation")

        // #10 fix: open the Direct I/O engine BEFORE submitting writeLoop (and before opening the frame gate)
        // so the loop's first iterations observe a ready engine via the @Volatile happens-before, instead of
        // racing to the orphan-file fallback for the earliest frames.
        if (compressionMode != RawCompressionMode.OFF) {
            val availableStorageBytes = reserveRawStorageForMaster()
            directIOEngine = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                openScopedRawStreamWriter(availableStorageBytes)
            } else {
                val streamPath = File(sessionDir, "stream.npraw").absolutePath
                AsyncDirectIOEngine().apply {
                    open(streamPath, availableStorageBytes = availableStorageBytes)
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
                        runCatching { context.contentResolver.delete(uri, null, null) }
                    }
                    compressedStreamUri = null
                    captureLifecycleActive.set(false)
                    val message = "Strict NPRAW physical preallocation failed: ${result.failure.orEmpty()}"
                    failStrictRawMaster(message)
                    throw IllegalStateException(message)
                }
            }
            if (directIOEngine == null && strictHighEndRawCaptureActive()) {
                captureLifecycleActive.set(false)
                val message = "Strict NPRAW stream writer could not be opened"
                failStrictRawMaster(message)
                throw IllegalStateException(message)
            }
        }

        // Open the frame-accept gate LAST: writeQueue is now recreated and the session is fully set
        // up, so onRawFrameAvailable can safely enqueue into the queue that writeLoop will drain. Set
        // BEFORE submitting writeLoop so the loop's (isCapturing || queue non-empty) guard sees true
        // on its first check and does not exit immediately.
        isCapturing.set(true)

        // Start the write loop on the dedicated executor. Keep the Future so stop/startCapture can await the
        // loop's exit before closing the engine / recreating the queue (#9/#10).
        writeLoopFuture = writeExecutor.submit { writeLoop() }
    }

    /**
     * Stop capturing. Drains the write queue before returning.
     * @return Path to the session directory, or null if never started.
     */
    fun stopCapture(): String? {
        if (!captureLifecycleActive.getAndSet(false)) {
            Log.w(TAG, "stopCapture() called when not capturing"); return null
        }
        // Close the frame-accept gate first; writeLoop drains whatever is already queued.
        isPaused.set(false)
        isCapturing.set(false)
        // ════════════════════════════════════════════════════════════════════
        // OPTIMIZED: Exponential backoff for queue drain
        // Starts with fast polling (responsive), backs off to reduce CPU wake-ups
        // ════════════════════════════════════════════════════════════════════
        // ARCHITECTURAL FIX: Aggressive sub-second drain with 10ms capped backoff
        val maxWaitMs = stopDrainTimeoutMs()
        var drainedMs = 0L
        var backoff = 2L
        while (writeQueue.isNotEmpty() || activeWrites.get() > 0) {
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
        quiesceWriteLoop()
        // Close Direct I/O engine
        directIOEngine?.close()
        directIOEngine = null
        compressedStreamUri?.let { publishPendingBinary(it) }
        compressedStreamUri = null
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
        Log.i(
            TAG,
            "RAW capture stopped — ${framesWritten.get()} frames → $path " +
                "(drops=${droppedFrameCount.get()}, queueHighWatermark=${queueHighWatermark.get()})"
        )
        return path
    }

    fun pauseCapture(): Boolean {
        if (!isCapturing.get() || !isPaused.compareAndSet(false, true)) return false
        Log.i(TAG, "RAW custom packet capture paused")
        return true
    }

    fun resumeCapture(): Boolean {
        if (!isCapturing.get() || !isPaused.compareAndSet(true, false)) return false
        lastFrameNs = 0L
        Log.i(TAG, "RAW custom packet capture resumed")
        return true
    }

    fun writtenFrameCount(): Int = framesWritten.get()

    /**
     * Call from CameraController.onRawReady callback.
     * MUST be called on the Camera bgHandler thread (or any thread — it's fast).
     * Acquires a stable image and enqueues it for writing.
     */
    fun onRawFrameAvailable(reader: ImageReader) {
        val capturing = isCapturing.get() && !isPaused.get()
        val img = try {
            if (capturing) {
                // Recording queues Image instances for the writer thread. acquireLatestImage() may
                // auto-close older acquired images that are still queued, corrupting RAW masters and
                // preventing develop.meta from being written. Use acquireNextImage() while recording
                // so each queued buffer remains valid until writeLoop closes it.
                reader.acquireNextImage()
            } else {
                reader.acquireLatestImage()
            }
        } catch (e: IllegalStateException) {
            if (strictHighEndRawCaptureActive()) {
                failStrictRawMaster("RAW reader saturated before acquire", e)
            } else {
                markDroppedFrame()
                logDroppedFrameWarning("RAW reader saturated; dropping incoming frame", e)
            }
            return
        } ?: return

        if (!capturing) {
            // Preview-only path: offer the frame to the optional preview processor (the Vulkan
            // cinema-debayer). If it consumes the frame it owns/closes it; otherwise close here to
            // prevent reader overflow.
            val processor = rawPreviewProcessor
            val descriptor = if (processor != null) buildRawBufferDescriptor(img) else null
            val consumed = processor != null && descriptor != null && processor(img, descriptor) == true
            if (!consumed) {
                img.close()
            }
            return
        }

        // Frame rate throttle — drop frames above MAX_FRAME_RATE
        val ts = img.timestamp
        if (ts - lastFrameNs < minFrameIntervalNs) {
            img.close(); return
        }
        lastFrameNs = ts

        if (
            !RawVideoBackpressurePolicy.shouldRetainAcquiredFrame(
                readerMaxImages = reader.maxImages,
                queuedImages = writeQueue.size,
                activeWrites = activeWrites.get()
            )
        ) {
            if (strictHighEndRawCaptureActive()) {
                // Backpressure the Camera2 callback rather than discarding a master frame. The policy
                // keeps reader headroom while this acquired Image waits; a bounded timeout still turns a
                // genuine storage stall into a hard error instead of hanging camera shutdown forever.
                if (offerStrictMasterWithBackpressure(img, ts)) return
                failStrictRawMaster("RAW reader budget remained exhausted for strict enqueue at ts=$ts")
            } else {
                markDroppedFrame()
                logDroppedFrameWarning(
                    "RAW reader budget reserved to avoid maxImages saturation; dropping incoming frame at ts=$ts"
                )
            }
            img.close()
            return
        }

        // Non-blocking enqueue — drop frame if writer is behind
        if (writeQueue.offer(img)) {
            updateQueueHighWatermark(writeQueue.size)
            return
        }

        if (shouldEvictOldestOnOverflow && !strictHighEndRawCaptureActive()) {
            val evicted = writeQueue.poll()
            if (evicted != null) {
                evicted.close()
                markDroppedFrame()
                logDroppedFrameWarning("RAW write queue overflow — evicting oldest frame")
            }
        }

        if (!writeQueue.offer(img)) {
            if (strictHighEndRawCaptureActive()) {
                if (offerStrictMasterWithBackpressure(img, ts)) return
                failStrictRawMaster("RAW write queue remained saturated for strict enqueue at ts=$ts")
            } else {
                markDroppedFrame()
                logDroppedFrameWarning("Write queue saturated — dropping incoming RAW frame at ts=$ts")
            }
            img.close()
            return
        }
        updateQueueHighWatermark(writeQueue.size)
    }

    private fun offerStrictMasterWithBackpressure(image: Image, timestampNs: Long): Boolean {
        val accepted = try {
            writeQueue.offer(image, STRICT_MASTER_ENQUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (accepted) {
            updateQueueHighWatermark(writeQueue.size)
            Log.d(TAG, "Strict RAW packet enqueue resumed after bounded backpressure at ts=$timestampNs")
        }
        return accepted
    }

    /**
     * Provide capture result metadata for DNG embedding.
     * Call from CameraController.captureCallback.onCaptureCompleted().
     */
    fun onCaptureResult(result: TotalCaptureResult) {
        latestResult = result
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        synchronized(resultLock) {
            recentResultsByTimestamp[timestamp] = result
        }
    }

    /**
     * Build a [RawBufferDescriptor] from a RAW_SENSOR [Image] and the latest
     * matching [TotalCaptureResult]. Returns null if metadata is unavailable.
     */
    fun buildRawBufferDescriptor(image: Image): RawBufferDescriptor? {
        val chars = camera.getCameraCharacteristics() ?: return null
        val result = resolveCaptureResult(image.timestamp) ?: latestResult ?: return null
        val width = image.width
        val height = image.height
        val stride = image.planes.firstOrNull()?.rowStride ?: width
        val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
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
            strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        )
        val bayerArrangement = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val bayerPattern = BayerPattern.fromCameraCharacteristic(bayerArrangement)
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
        val analog = result.get(CaptureResult.SENSOR_SENSITIVITY)?.toFloat() ?: 1.0f
        val digital = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)?.toFloat() ?: 1.0f

        // CP11 / s1-f09 — hand the demosaic the CORRECT forward sensor→XYZ(D50) matrix from the public
        // DNG SENSOR_FORWARD_MATRIX (mired-interpolated by the per-frame white-balance CCT), never the
        // inverted SENSOR_COLOR_TRANSFORM. Falls back to the identity-safe default when the camera does
        // not advertise public forward matrices, so an unsupported sensor keeps a neutral demosaic. The
        // demosaic stays device-gated (default OFF), so this changes no live pixel until the owner enables it.
        val sensorToXyzD50 = runCatching {
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

        return RawBufferDescriptor(
            width = width,
            height = height,
            stride = stride,
            blackLevel = calibration.blackLevel,
            whiteLevel = calibration.whiteLevel,
            bayerPattern = bayerPattern,
            whiteBalanceR = cct?.red ?: 1.0f,
            whiteBalanceGr = cct?.greenEven ?: 1.0f,
            whiteBalanceGb = cct?.greenOdd ?: 1.0f,
            whiteBalanceB = cct?.blue ?: 1.0f,
            exposureTimeNs = exposureTime,
            analogGain = analog,
            digitalGain = digital,
            noiseS = noiseInputs.noiseS,
            noiseO = noiseInputs.noiseO,
            sensorToXyz = sensorToXyzD50
        )
    }

    fun destroy() {
        captureLifecycleActive.set(false)
        isCapturing.set(false)
        // #9/#10 fix: quiesce the writeLoop (await exit, then drain+close any queued Images) before shutting
        // the executor down, so no RAW Image HardwareBuffer leaks and no iteration runs against a closed engine.
        quiesceWriteLoop()
        directIOEngine?.close()
        directIOEngine = null
        writeExecutor.shutdown()
        previewTapExecutor.shutdown()
        previewTapBuffer = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // #9/#10 WRITE-LOOP QUIESCE — guarantee the single writeLoop has exited and the queue is emptied before
    // the engine is closed or writeQueue is recreated, so a stop-drain timeout never leaves a lingering loop
    // racing the next session (leaking Images) or writing to a closed Direct-I/O engine (orphan tail files).
    // ─────────────────────────────────────────────────────────────────────────
    private fun quiesceWriteLoop() {
        val future = writeLoopFuture
        writeLoopFuture = null
        if (future != null) {
            // isCapturing is already false at every caller, so the loop terminates once its queue empties.
            if (!awaitWriteLoopExit(future, writeLoopExitTimeoutMs)) {
                // Storage stalled: force the loop's guard false by emptying the queue, then await once more.
                Log.w(TAG, "writeLoop did not exit in ${writeLoopExitTimeoutMs}ms; draining queue to force exit")
                drainAndCloseWriteQueue()
                if (!awaitWriteLoopExit(future, writeLoopExitTimeoutMs)) {
                    Log.e(TAG, "writeLoop did not exit after forced drain; continuing (engine close is guarded)")
                }
            }
        }
        // Close out anything still queued regardless (idempotent; ArrayBlockingQueue.poll() is atomic so this
        // cannot double-close an Image the loop is concurrently handling).
        drainAndCloseWriteQueue()
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

    private fun drainAndCloseWriteQueue() {
        while (true) {
            val image = writeQueue.poll() ?: break
            runCatching { image.close() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WRITE LOOP (runs on writeExecutor)
    // ─────────────────────────────────────────────────────────────────────────

    private fun writeLoop() {
        Log.d(TAG, "Write loop started")
        while (isCapturing.get() || writeQueue.isNotEmpty()) {
            val waitMs = resolveWriterPollTimeoutMs
            val img = writeQueue.poll(waitMs, TimeUnit.MILLISECONDS) ?: continue
            activeWrites.incrementAndGet()
            val writeStartElapsedMs = SystemClock.elapsedRealtime()
            try {
                when (compressionMode) {
                    RawCompressionMode.OFF -> writeDng(img)
                    else -> writeCompressedFrame(img)
                }
                com.neoproc.neoproccamx.core.RuntimeStageTelemetry.recordDuration(
                    tag = TAG,
                    stage = "writeFrame",
                    durationMs = SystemClock.elapsedRealtime() - writeStartElapsedMs,
                    slowThresholdMs = SLOW_RAW_WRITE_MS
                )
                // Transfer at most one already-persisted Image to the low-priority monitor worker. Moving
                // the full-plane copy off this single disk-writer thread prevents the monitor from delaying
                // subsequent RAW persistence. The worker closes transferred ownership after its copy.
                if (!tapRecordingPreview(img)) img.close()
            } catch (e: Exception) {
                runCatching { img.close() }
                Log.e(TAG, "RAW frame write failed: ${e.message}", e)
            } finally {
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
    private fun tapRecordingPreview(image: Image): Boolean {
        val processor = recordingPreviewBytesProcessor ?: return false
        val nowNs = System.nanoTime()
        if (nowNs - lastPreviewTapNs < PREVIEW_TAP_MIN_INTERVAL_NS) return false
        if (writeQueue.size > PREVIEW_TAP_MAX_QUEUE_DEPTH) return false
        if (!previewTapBusy.compareAndSet(false, true)) return false

        val descriptor = buildRawBufferDescriptor(image)
        val plane = image.planes.firstOrNull()
        val rowStride = plane?.rowStride ?: 0
        if (descriptor == null || plane == null || rowStride <= 0) {
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
                    processor(copyBuffer, descriptor, rowStride, PREVIEW_TAP_DECIMATE)
                } catch (t: Throwable) {
                    Log.w(TAG, "Record-time preview debayer tap failed: ${t.message}")
                } finally {
                    runCatching { image.close() }
                    previewTapBusy.set(false)
                }
            }
            return true
        } catch (t: Throwable) {
            // Executor rejected (shutting down): clear the slot so future taps can proceed.
            previewTapBusy.set(false)
            return false
        }
    }

    private fun writeDng(image: Image) {
        val chars  = camera.getCameraCharacteristics() ?: return
        val result = resolveCaptureResult(image.timestamp) ?: latestResult ?: run {
            Log.w(TAG, "No CaptureResult available — skipping DNG frame"); return
        }
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
                val dir = sessionDir ?: return
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

    private fun writeCompressedFrame(image: Image) {
        val plane = image.planes.firstOrNull() ?: return
        // Perf: the NPRAW header only needs the original plane size. Read the plane buffer's capacity
        // directly instead of allocating + copying a full ~25MB ByteArray per RAW frame just to read
        // its .size. The old extractPlaneBytes did duplicate().clear() then remaining(), which equals
        // capacity() — so originalSize is byte-identical while a per-frame alloc + GC churn is removed.
        val originalPlaneSize = plane.buffer.capacity()
        if (originalPlaneSize <= 0) return
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
        val encoded = if (compressionMode == RawCompressionMode.LOSSLESS_REALTIME) {
            NprawFrameCodec.encodeRealtimeLinear16Frame(
                rawPlane = plane.buffer,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                timestamp = image.timestamp,
                originalSize = originalPlaneSize
            )
        } else {
            val rawSamples = extractRawSamples(image, plane)
            if (rawSamples.isEmpty()) return
            NprawFrameCodec.encodeFrame(
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
        }
        val container = encoded.bytes

        // First captured frame: persist the develop-metadata sidecar next to the stream so the
        // offline RAW→NeoLog converter can debayer / white-balance / colour-transform correctly.
        // NprawFrameCodec omits WB gains / Bayer phase / sensor→XYZ; this fills that gap. Guarded so
        // a sidecar failure can never interrupt frame capture (RAW mode locks AWB, so a single
        // per-clip white-balance / matrix snapshot is exact for the whole clip).
        if (developSidecarWritten.compareAndSet(false, true)) {
            runCatching {
                buildRawBufferDescriptor(image)?.let { descriptor ->
                    val fpsHint = (1_000_000_000L / minFrameIntervalNs.coerceAtLeast(1L))
                        .toInt().coerceIn(1, 240)
                    val meta = RawDevelopMetadataSidecar.fromDescriptor(
                        descriptor = descriptor,
                        storageBitDepth = encoded.bitDepth,
                        sensorWhiteLevel = sensorWhiteLevel,
                        fps = fpsHint,
                        colorProfileTag = DEFAULT_DEVELOP_LOG_VARIANT,
                        sensorPixelModeMaximumResolutionUsed = sensorPixelModeMaximumResolutionUsed,
                        orientationHintDegrees = orientationHintDegrees
                    )
                    writeNprawFrame(RawDevelopMetadataSidecar.FILE_NAME, RawDevelopMetadataSidecar.encode(meta))
                }
            }.onFailure { Log.w(TAG, "RAW develop-metadata sidecar write failed: ${it.message}") }
        }

        try {
            val direct = directIOEngine
                ?.takeIf { it.isOpen }
                ?: throw IllegalStateException("NPRAW packet stream writer is unavailable")
            // The custom recording contract is one concatenated packet stream. There is deliberately
            // no per-frame-file fallback: a stream failure becomes a hard recording error.
            val accepted = direct.enqueueWrite(container, container.size) { success ->
                if (success) {
                    onCompressedFramePersisted(
                        index = index,
                        originalPlaneSize = originalPlaneSize,
                        encoded = encoded
                    )
                } else {
                    failStrictRawWrite("NPRAW stream writer failed to write packet $index")
                }
            }
            if (!accepted) {
                throw IllegalStateException("NPRAW stream writer rejected packet $index")
            }
        } catch (e: Exception) {
            throw e
        }
    }

    private fun onCompressedFramePersisted(
        index: Int,
        originalPlaneSize: Int,
        encoded: NprawFrameCodec.EncodeResult
    ) {
        val written = framesWritten.incrementAndGet()
        publishFrameCount(written)
        if (index == 1 || index % FRAME_LOG_SAMPLE_STEP == 0) {
            Log.i(TAG, "NPRAW frame $index -> Stream mode=$compressionMode " +
                    "raw=$originalPlaneSize compressed=${encoded.payloadSize} " +
                    "bitDepth=${encoded.bitDepth} companding=${encoded.compandingType} " +
                    "v=${encoded.version} ratio=${"%.2f".format(Locale.US, encoded.compressionRatio)} " +
                    "written=$written")
        }
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
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
    }

    private fun publishPendingBinary(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val publish = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        context.contentResolver.update(uri, publish, null, null)
    }

    private fun openScopedRawStreamWriter(availableStorageBytes: Long): AsyncDirectIOEngine? {
        val uri = insertPendingBinary("stream.npraw") ?: return null
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "w")
            if (pfd != null) {
                compressedStreamUri = uri
                AsyncDirectIOEngine().apply { open(pfd, availableStorageBytes) }
            } else {
                context.contentResolver.delete(uri, null, null)
                null
            }
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            Log.w(TAG, "Unable to open scoped RAW stream writer: ${e.message}")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun reserveRawStorageForMaster(): Long {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val allocatableBytes = runCatching {
            storageManager.getAllocatableBytes(StorageManager.UUID_DEFAULT)
        }.getOrElse { sessionDir?.usableSpace ?: 0L }
        val plan = RecordingPreallocationPolicy.raw(allocatableBytes)
        val usableBytes = (sessionDir?.usableSpace ?: 0L).coerceAtLeast(0L)
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
        return runCatching { sessionDir?.usableSpace ?: usableBytes }
            .getOrDefault(usableBytes)
            .coerceAtLeast(0L)
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
                context.contentResolver.delete(uri, null, null)
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
        markDroppedFrame()
        isCapturing.set(false)
        _state.value = RawVideoState.Error("$message; refusing dropped-frame RAW master")
        logDroppedFrameWarning(message, throwable)
    }

    private fun failStrictRawWrite(message: String) {
        if (strictHighEndRawCaptureActive()) {
            failStrictRawMaster(message)
        } else {
            markDroppedFrame()
            Log.e(TAG, message)
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

    private fun resolveCaptureResult(imageTimestamp: Long): TotalCaptureResult? {
        synchronized(resultLock) {
            recentResultsByTimestamp.remove(imageTimestamp)?.let { return it }
            val nearest = recentResultsByTimestamp.minByOrNull { (timestamp, _) ->
                kotlin.math.abs(timestamp - imageTimestamp)
            }
            if (nearest != null && kotlin.math.abs(nearest.key - imageTimestamp) <= 50_000_000L) {
                return nearest.value
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

}

// ── State types ───────────────────────────────────────────────────────────────

sealed class RawVideoState {
    object Idle : RawVideoState()
    data class Capturing(val path: String) : RawVideoState()
    data class Done(val frameCount: Int, val path: String) : RawVideoState()
    data class Error(val msg: String) : RawVideoState()
}
