package com.neoproc.neoproccamx.recording

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.os.Process
import android.os.SystemClock
import android.os.storage.StorageManager
import android.util.Log
import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class Npv10StopStageTimings(
    val queueDrainMs: Double = 0.0,
    val writerSealMs: Double = 0.0,
    val truncateMs: Double = 0.0,
    val durableSyncMs: Double = 0.0,
    val descriptorCloseMs: Double = 0.0,
    val evidenceBuildMs: Double = 0.0,
    val evidenceWriteMs: Double = 0.0,
    val certificateMs: Double = 0.0,
    val totalMs: Double = 0.0
)

internal enum class Npv10FrameBackpressureAction {
    ACCEPT,
    FAIL_STRICT_MASTER
}

internal object Npv10FrameBackpressurePolicy {
    fun resolve(pendingFrames: Int, maxPendingFrames: Int): Npv10FrameBackpressureAction =
        if (pendingFrames > maxPendingFrames) {
            Npv10FrameBackpressureAction.FAIL_STRICT_MASTER
        } else {
            Npv10FrameBackpressureAction.ACCEPT
        }
}

class Npv10RecordingManager(context: Context) {
    private val appContext = context.applicationContext
    private val privateRoot = appContext.filesDir
    private val store = Npv10MasterStore(privateRoot)
    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        object : Thread(runnable, "NPV10-Writer") {
            init {
                priority = Thread.NORM_PRIORITY + 2
                isDaemon = true
            }

            override fun run() {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
                super.run()
            }
        }
    }
    private val reservationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NPV10-Reservation").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }
    private val pendingFrames = AtomicInteger(0)
    @Volatile private var activeMaxPendingFrames = Npv10InFlightBufferPolicy.MIN_PENDING_FRAMES
    @Volatile private var pendingFrameSlots = Semaphore(activeMaxPendingFrames, true)
    private val droppedFrames = AtomicInteger(0)
    private val nativeDirectP010Copies = AtomicLong(0L)
    private val directBufferP010Copies = AtomicLong(0L)
    private val failedP010Copies = AtomicLong(0L)
    private val p010PayloadBufferPool = ConcurrentLinkedQueue<ByteBuffer>()

    private val _state = MutableStateFlow<GpuRecordingState>(GpuRecordingState.Idle)
    val state: StateFlow<GpuRecordingState> = _state.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    var runtimeEvidenceMetadataProvider: (() -> Map<String, String>)? = null

    @Volatile private var writer: Npv10FileWriter? = null
    @Volatile private var outputFile: File? = null
    @Volatile private var activeConfig: Npv10SessionConfig? = null
    @Volatile private var startedElapsedMs: Long = 0L
    @Volatile private var recordingStartProcessCpuMs: Long = 0L
    @Volatile private var finalizedRecordingCpuUsagePercent: Float? = null
    @Volatile private var paused = false
    @Volatile private var finalizationInProgress = false
    @Volatile private var preparedReservation: Npv10PreparedReservation? = null
    @Volatile private var reservationFuture: Future<Npv10PreparedReservation>? = null
    private val reservationGeneration = AtomicLong(0L)
    @Volatile var lastStopStageTimings: Npv10StopStageTimings = Npv10StopStageTimings()
        private set

    fun listMasters(): List<Npv10MasterClip> = store.listMasters(outputFile)

    @Synchronized
    fun writtenFrameCount(): Long = writer?.frameCount ?: 0L

    @Synchronized
    fun hasActiveSession(): Boolean = writer != null

    @Synchronized
    fun start(config: Npv10SessionConfig): File {
        check(writer == null && !finalizationInProgress) {
            "Previous NPV10 lossless master is still finalizing"
        }
        require(config.sourceBitDepth >= Npv10ContainerPolicy.BIT_DEPTH) {
            "Custom NPV10 recording requires a native 10-bit source; no 8-bit fallback is permitted"
        }
        require(config.payloadFormat == Npv10PayloadFormat.P010_LE) {
            "Custom NPV10 recording requires canonical P010 camera data"
        }
        require(config.packetCodec == Npv10PacketCodec.PACKED_P010_10_LE_V1) {
            "Custom NPV10 recording requires the lossless packed-10 packet codec"
        }
        val reservation = takePreparedReservation()
        val file = reservation?.let { store.adoptReservation(it, config) }
            ?: store.createMasterFile(config)
        val availableStorageBytes = reservation?.let(::availableBytesIncludingReservation)
            ?: reservePrivateStorageForMaster()
        val preflight = Npv10QualityGate.preflight(
            config = config,
            privateRoot = privateRoot,
            availableBytes = availableStorageBytes
        )
        try {
            require(preflight.auditStatus == Npv10AuditStatus.PASS) {
                "NPV10 preflight failed: ${preflight.failureReasons.joinToString(separator = "; ")}"
            }
        } catch (failure: Throwable) {
            runCatching { file.delete() }
            throw failure
        }
        val createdWriter = try {
            Npv10FileWriter(
                outputFile = file,
                config = config,
                availableStorageBytes = availableStorageBytes,
                preparedPreallocation = reservation?.preallocationResult
            )
        } catch (failure: Throwable) {
            runCatching { file.delete() }
            throw failure
        }
        try {
            require(
                createdWriter.preallocationResult.plan.enabled &&
                    createdWriter.preallocationResult.method == RecordingPreallocationMethod.POSIX_FALLOCATE
            ) {
                "NPV10 physical preallocation failed: " +
                    createdWriter.preallocationResult.failure.orEmpty()
            }
        } catch (t: Throwable) {
            runCatching { createdWriter.close() }
            runCatching { file.delete() }
            throw t
        }
        writer = createdWriter
        outputFile = file
        activeConfig = config
        pendingFrames.set(0)
        activeMaxPendingFrames = Npv10InFlightBufferPolicy.pendingFrames(config.width, config.height)
        pendingFrameSlots = Semaphore(activeMaxPendingFrames, true)
        trimP010CopyBufferPool()
        droppedFrames.set(0)
        nativeDirectP010Copies.set(0L)
        directBufferP010Copies.set(0L)
        failedP010Copies.set(0L)
        paused = false
        finalizationInProgress = false
        lastStopStageTimings = Npv10StopStageTimings()
        startedElapsedMs = SystemClock.elapsedRealtime()
        recordingStartProcessCpuMs = Process.getElapsedCpuTime()
        finalizedRecordingCpuUsagePercent = null
        _duration.value = 0L
        _state.value = GpuRecordingState.Recording
        Log.i(
            TAG,
            "NPV10 private master started: ${file.name} " +
                "resolution=${config.width}x${config.height} fps=${config.fps} " +
                "sourceFormat=${config.sourceFormat} sourceBitDepth=${config.sourceBitDepth} " +
                "storageBitDepth=${config.storageBitDepth} payloadFormat=${config.payloadFormat} " +
                "qualityTier=${preflight.qualityTier} bytesPerSecond=${preflight.bytesPerSecond} " +
                "minimumFreeBytes=${preflight.minimumFreeBytes} exactSelected=${config.exactSelectedResolution} " +
                "preallocation=${writer?.preallocationResult?.method}:" +
                "${writer?.preallocationResult?.reservedBytes ?: 0L} " +
                "path=${file.absolutePath}"
        )
        return file
    }

    /** Physically reserve the next strict master while the camera is idle, never on shutter UI work. */
    @Synchronized
    fun preparePhysicalReservationAsync() {
        if (writer != null) return
        preparedReservation?.let { ready ->
            if (ready.isUsable()) return
            runCatching { ready.file.delete() }
            preparedReservation = null
        }
        if (reservationFuture != null) return
        store.deleteStaleReservations()
        val reservationFile = store.createReservationFile()
        val generation = reservationGeneration.get()
        reservationFuture = reservationExecutor.submit<Npv10PreparedReservation> {
            val availableStorageBytes = reservePrivateStorageForMaster()
            val reservation = Npv10PreparedReservationFactory.create(
                reservationFile,
                availableStorageBytes
            )
            if (generation != reservationGeneration.get() || Thread.currentThread().isInterrupted) {
                runCatching { reservation.file.delete() }
                throw InterruptedException("NPV10 reservation preparation superseded")
            }
            require(reservation.isUsable()) {
                "NPV10 physical preallocation failed: " +
                    reservation.preallocationResult.failure.orEmpty()
            }
            Log.i(
                TAG,
                "NPV10 idle physical reservation ready method=" +
                    "${reservation.preallocationResult.method} " +
                    "bytes=${reservation.preallocationResult.reservedBytes}"
            )
            reservation
        }
    }

    @Synchronized
    fun discardPreparedPhysicalReservation() {
        reservationGeneration.incrementAndGet()
        reservationFuture?.cancel(true)
        reservationFuture = null
        preparedReservation?.let { runCatching { it.file.delete() } }
        preparedReservation = null
        store.deleteStaleReservations()
    }

    private fun takePreparedReservation(): Npv10PreparedReservation? {
        preparedReservation?.let { ready ->
            preparedReservation = null
            if (ready.isUsable()) return ready
            runCatching { ready.file.delete() }
        }
        val future = reservationFuture ?: return null
        reservationFuture = null
        return runCatching { future.get() }
            .onFailure { failure ->
                Log.w(TAG, "Idle NPV10 reservation was unavailable; reserving synchronously", failure)
            }
            .getOrNull()
            ?.takeIf(Npv10PreparedReservation::isUsable)
    }

    @SuppressLint("UsableSpace")
    private fun availableBytesIncludingReservation(reservation: Npv10PreparedReservation): Long {
        val reservedBytes = reservation.preallocationResult.reservedBytes
        val currentPlusReserved = if (Long.MAX_VALUE - privateRoot.usableSpace < reservedBytes) {
            Long.MAX_VALUE
        } else {
            privateRoot.usableSpace + reservedBytes
        }
        return maxOf(reservation.preallocationResult.plan.availableBytes, currentPlusReserved)
    }

    @SuppressLint("UsableSpace")
    private fun allocatablePrivateStorageBytes(): Long =
        runCatching {
            appContext.getSystemService(StorageManager::class.java)
                .getAllocatableBytes(StorageManager.UUID_DEFAULT)
        }.getOrElse {
            // Retain a conservative fallback when storage allocation querying is unavailable.
            privateRoot.usableSpace
        }

    @SuppressLint("UsableSpace")
    private fun reservePrivateStorageForMaster(): Long {
        val storageManager = appContext.getSystemService(StorageManager::class.java)
        val allocatableBytes = allocatablePrivateStorageBytes()
        val plan = RecordingPreallocationPolicy.processed(allocatableBytes)
        val usableBytes = runCatching { privateRoot.usableSpace }
            .getOrDefault(allocatableBytes)
            .coerceAtLeast(0L)
        if (StorageReservationRequestPolicy.shouldRequestAllocation(usableBytes, plan)) {
            runCatching {
                storageManager.allocateBytes(StorageManager.UUID_DEFAULT, plan.reservationBytes)
            }.onFailure { failure ->
                Log.w(
                    TAG,
                    "Unable to reclaim cache space before NPV10 fallocate; " +
                        "the filesystem reservation will still be attempted: ${failure.message}"
                )
            }
        }
        return runCatching { privateRoot.usableSpace }.getOrDefault(usableBytes).coerceAtLeast(0L)
    }

    fun enqueueFrame(frame: RawYuvFrame) {
        val activeWriter = writer ?: return
        if (_state.value !is GpuRecordingState.Recording || paused) return
        val frameSlots = pendingFrameSlots
        if (!acquirePendingFrameSlot(frameSlots)) {
            droppedFrames.incrementAndGet()
            failStrictMasterBacklog()
            return
        }
        val pending = pendingFrames.incrementAndGet()
        if (Npv10FrameBackpressurePolicy.resolve(pending, activeMaxPendingFrames) ==
            Npv10FrameBackpressureAction.FAIL_STRICT_MASTER
        ) {
            pendingFrames.decrementAndGet()
            frameSlots.release()
            droppedFrames.incrementAndGet()
            failStrictMasterBacklog()
            return
        }
        try {
            writerExecutor.execute {
                try {
                    activeWriter.writeFrame(frame)
                    startedElapsedMs.takeIf { it > 0L }?.let { start ->
                        _duration.value = SystemClock.elapsedRealtime() - start
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "NPV10 frame write failed", t)
                    _state.value = GpuRecordingState.Error(t.message ?: "NPV10 frame write failed")
                } finally {
                    pendingFrames.decrementAndGet()
                    frameSlots.release()
                }
            }
        } catch (t: RejectedExecutionException) {
            pendingFrames.decrementAndGet()
            frameSlots.release()
            droppedFrames.incrementAndGet()
            Log.e(TAG, "NPV10 writer executor rejected frame", t)
            _state.value = GpuRecordingState.Error(t.message ?: "NPV10 writer rejected frame")
        }
    }

    // #7 fix: @Synchronized restored (WIP removed it). Serializes enqueue against the @Synchronized
    // start/stop/pause/resume so a frame can never pass the writer===activeWriter re-check and then submit
    // a write AFTER stop() has submitted+awaited close() — which would throw on the closed writer, flip an
    // already-saved clip to Error, and (on a fast restart) corrupt the next session + underflow pendingFrames.
    // The heavy write still runs async on writerExecutor, so throughput is unaffected (pure revert).
    @Synchronized
    fun enqueueP010Image(image: Image): Boolean {
        val activeWriter = writer
        val sessionConfig = activeConfig
        if (activeWriter == null ||
            sessionConfig == null ||
            _state.value !is GpuRecordingState.Recording ||
            paused
        ) {
            closeImageSafely(image)
            return true
        }
        val frameSlots = pendingFrameSlots
        if (!acquirePendingFrameSlot(frameSlots)) {
            droppedFrames.incrementAndGet()
            closeImageSafely(image)
            failStrictMasterBacklog()
            return true
        }
        val pending = pendingFrames.incrementAndGet()
        if (Npv10FrameBackpressurePolicy.resolve(pending, activeMaxPendingFrames) ==
            Npv10FrameBackpressureAction.FAIL_STRICT_MASTER
        ) {
            pendingFrames.decrementAndGet()
            frameSlots.release()
            droppedFrames.incrementAndGet()
            closeImageSafely(image)
            failStrictMasterBacklog()
            return true
        }
        val copiedFrame = try {
            copyP010ImageForAsyncWrite(image, sessionConfig)
        } catch (t: Throwable) {
            pendingFrames.decrementAndGet()
            frameSlots.release()
            recordP010Copy(Npv10FramePacker.P010CopyPath.FAILED)
            Log.e(TAG, "NPV10 P010 image copy failed before writer enqueue", t)
            _state.value = GpuRecordingState.Error(t.message ?: "NPV10 P010 image copy failed")
            return true
        }
        if (_state.value !is GpuRecordingState.Recording || paused || writer !== activeWriter) {
            releaseP010CopyBuffer(copiedFrame.payload)
            pendingFrames.decrementAndGet()
            frameSlots.release()
            return true
        }
        return try {
            writerExecutor.execute {
                try {
                    val copyPath = activeWriter.writeCopiedP010Frame(copiedFrame)
                    recordP010Copy(copyPath)
                    startedElapsedMs.takeIf { it > 0L }?.let { start ->
                        _duration.value = SystemClock.elapsedRealtime() - start
                    }
                } catch (t: Throwable) {
                    recordP010Copy(Npv10FramePacker.P010CopyPath.FAILED)
                    Log.e(TAG, "NPV10 copied P010 frame write failed", t)
                    _state.value = GpuRecordingState.Error(t.message ?: "NPV10 copied P010 frame write failed")
                } finally {
                    releaseP010CopyBuffer(copiedFrame.payload)
                    pendingFrames.decrementAndGet()
                    frameSlots.release()
                }
            }
            true
        } catch (t: RejectedExecutionException) {
            pendingFrames.decrementAndGet()
            frameSlots.release()
            droppedFrames.incrementAndGet()
            releaseP010CopyBuffer(copiedFrame.payload)
            Log.e(TAG, "NPV10 writer executor rejected copied P010 frame", t)
            _state.value = GpuRecordingState.Error(t.message ?: "NPV10 writer rejected copied P010 frame")
            true
        }
    }

    private fun copyP010ImageForAsyncWrite(
        image: Image,
        config: Npv10SessionConfig
    ): Npv10CopiedP010Frame {
        try {
            validateRuntimeP010Image(image, config)
            val timestampNs = runCatching { image.timestamp }.getOrDefault(0L)
            val payloadBytes = Npv10ContainerPolicy
                .estimatedPayloadBytesPerFrame(image.width, image.height, config.payloadFormat)
                .toInt()
            val payloadBuffer = acquireP010CopyBuffer(payloadBytes)
            try {
                val copyResult = Npv10FramePacker.copyP010ImageIntoDirectBufferWithResult(image, payloadBuffer)
                require(copyResult.path != Npv10FramePacker.P010CopyPath.FAILED) {
                    "P010 Image copy did not produce a valid copy path"
                }
                require(copyResult.bytesCopied == payloadBytes) {
                    "P010 Image copy produced ${copyResult.bytesCopied} bytes, expected $payloadBytes"
                }
                return Npv10CopiedP010Frame(
                    timestampNs = timestampNs,
                    width = image.width,
                    height = image.height,
                    payload = payloadBuffer,
                    payloadBytes = payloadBytes,
                    copyPath = copyResult.path
                )
            } catch (t: Throwable) {
                releaseP010CopyBuffer(payloadBuffer)
                throw t
            }
        } finally {
            closeImageSafely(image)
        }
    }

    @SuppressLint("InlinedApi")
    private fun validateRuntimeP010Image(image: Image, config: Npv10SessionConfig) {
        require(config.payloadFormat == Npv10PayloadFormat.P010_LE) {
            "NPV10 async P010 image copy requires P010_LE payload"
        }
        require(image.width == config.width && image.height == config.height) {
            "NPV10 frame ${image.width}x${image.height} does not match session ${config.width}x${config.height}"
        }
        require(image.format == ImageFormat.YCBCR_P010) {
            "Native P010 NPV10 session received image format ${image.format}"
        }
        require(config.sourceBitDepth >= Npv10ContainerPolicy.BIT_DEPTH) {
            "Native P010 NPV10 session requires 10-bit source metadata"
        }
        require(image.planes.size >= 2) {
            "Native P010 NPV10 session received image without Y and UV planes"
        }
        val yPlane = image.planes[0]
        val uvPlane = image.planes[1]
        require(yPlane.rowStride > 0 && uvPlane.rowStride > 0) {
            "Native P010 NPV10 image row strides must be positive"
        }
        require(yPlane.pixelStride >= Short.SIZE_BYTES && uvPlane.pixelStride >= Short.SIZE_BYTES * 2) {
            "Native P010 NPV10 image pixel strides are invalid"
        }
    }

    private fun acquireP010CopyBuffer(payloadBytes: Int): ByteBuffer {
        while (true) {
            val candidate = p010PayloadBufferPool.poll() ?: break
            if (candidate.capacity() >= payloadBytes) {
                candidate.clear()
                candidate.limit(payloadBytes)
                return candidate
            }
        }
        return ByteBuffer
            .allocateDirect(payloadBytes)
            .order(ByteOrder.nativeOrder())
    }

    private fun releaseP010CopyBuffer(buffer: ByteBuffer) {
        if (p010PayloadBufferPool.size >= activeMaxPendingFrames) return
        buffer.clear()
        p010PayloadBufferPool.offer(buffer)
    }

    private fun trimP010CopyBufferPool() {
        while (p010PayloadBufferPool.size > activeMaxPendingFrames) {
            p010PayloadBufferPool.poll() ?: break
        }
    }

    private fun recordP010Copy(path: Npv10FramePacker.P010CopyPath) {
        when (path) {
            Npv10FramePacker.P010CopyPath.NATIVE_DIRECT -> nativeDirectP010Copies.incrementAndGet()
            Npv10FramePacker.P010CopyPath.DIRECT_BUFFER -> directBufferP010Copies.incrementAndGet()
            Npv10FramePacker.P010CopyPath.FAILED -> failedP010Copies.incrementAndGet()
        }
    }

    private fun closeImageSafely(image: Image) {
        runCatching { image.close() }
    }

    private fun acquirePendingFrameSlot(frameSlots: Semaphore): Boolean = try {
        frameSlots.tryAcquire(
            PENDING_FRAME_BACKPRESSURE_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun failStrictMasterBacklog() {
        val message = "NPV10 strict private master backlog overflow after bounded backpressure; " +
            "refusing dropped-frame output"
        _state.value = GpuRecordingState.Error(message)
        Log.e(TAG, message)
    }

    @Synchronized
    fun pause(): Boolean {
        if (_state.value !is GpuRecordingState.Recording) return false
        paused = true
        _state.value = GpuRecordingState.Paused
        Log.i(TAG, "NPV10 private master paused")
        return true
    }

    @Synchronized
    fun resume(): Boolean {
        if (_state.value !is GpuRecordingState.Paused) return false
        paused = false
        _state.value = GpuRecordingState.Recording
        Log.i(TAG, "NPV10 private master resumed")
        return true
    }

    /**
     * Atomically closes camera ingestion while leaving already accepted packets on the writer lane.
     * The public state becomes idle immediately so the shutter and preview describe capture truth;
     * [hasActiveSession] remains true until [stop] has durably sealed the master, preventing restart.
     */
    @Synchronized
    fun beginFinalization(): Boolean {
        if (writer == null) return false
        paused = true
        if (!finalizationInProgress) {
            // Freeze realtime telemetry at the exact ingestion boundary. Post-record packet hashing,
            // fsync, evidence generation, and certificate writes are background finalization work and
            // must not be misreported as camera-recording CPU load.
            finalizedRecordingCpuUsagePercent = calculateRecordingCpuUsagePercent()
            finalizationInProgress = true
            _duration.value = 0L
            _state.value = GpuRecordingState.Idle
            Log.i(TAG, "NPV10 camera ingestion stopped; lossless master finalization continues in background")
        }
        return true
    }

    @Synchronized
    fun stop(): File? {
        val activeWriter = writer ?: return null
        val stopStartedNs = System.nanoTime()
        val activeOutput = outputFile
        val sessionConfig = activeConfig
        beginFinalization()
        val queueDrainNs = AtomicLong(0L)
        val closeQueuedNs = System.nanoTime()
        val closeTask: Future<Boolean> = writerExecutor.submit<Boolean> {
            queueDrainNs.set((System.nanoTime() - closeQueuedNs).coerceAtLeast(0L))
            activeWriter.closePreservingPhysicalReservationWhenEmpty()
        }
        var physicalReservationPreserved = false
        runCatching {
            // Capture/UI have already returned to idle. Await every accepted packet without a destructive
            // timeout so a slow flash device can never turn a valid tail into silent frame loss.
            closeTask.get()
        }.onSuccess { preserved ->
            physicalReservationPreserved = preserved
        }.onFailure { throwable ->
            Log.e(TAG, "NPV10 writer close failed", throwable)
            _state.value = GpuRecordingState.Error(throwable.message ?: "NPV10 writer close failed")
        }
        val writtenFrames = activeWriter.frameCount
        val dropped = droppedFrames.get()
        val payloadBytes = activeWriter.payloadBytesWritten
        val firstTimestampNs = activeWriter.firstFrameTimestampNs
        val averageWriteMs = activeWriter.averageWriteTimeMs
        val nativeP010Copies = nativeDirectP010Copies.get()
        val directP010Copies = directBufferP010Copies.get()
        val failedCopies = failedP010Copies.get()
        val evidenceBuildStartedNs = System.nanoTime()
        val evidence = activeOutput?.takeIf { writtenFrames > 0L }?.let {
            activeWriter.buildDeviceEvidence(
                privateRoot = privateRoot,
                droppedFrames = dropped,
                generatedAtEpochMs = System.currentTimeMillis()
            )
        }
        val evidenceBuildMs = elapsedMs(evidenceBuildStartedNs)
        writer = null
        outputFile = null
        activeConfig = null
        paused = false
        finalizationInProgress = false
        pendingFrames.set(0)
        nativeDirectP010Copies.set(0L)
        directBufferP010Copies.set(0L)
        failedP010Copies.set(0L)
        _duration.value = 0L
        if (writtenFrames <= 0L) {
            val recycledFile = if (physicalReservationPreserved && activeOutput != null) {
                store.recycleReservation(activeOutput)
            } else {
                null
            }
            if (recycledFile != null) {
                preparedReservation = Npv10PreparedReservation(
                    recycledFile,
                    activeWriter.preallocationResult
                )
                Log.i(TAG, "Recycled unused NPV10 physical reservation for rapid shutter retry")
            } else {
                runCatching { activeOutput?.delete() }
                preparePhysicalReservationAsync()
            }
            if (_state.value !is GpuRecordingState.Error) {
                _state.value = GpuRecordingState.Error("NPV10 stopped without receiving camera frames")
            }
            Log.e(
                TAG,
                "NPV10 private master discarded because no camera frames were written " +
                    "dropped=$dropped path=${activeOutput?.absolutePath.orEmpty()}"
            )
            recordStopStageTimings(
                stopStartedNs = stopStartedNs,
                queueDrainNs = queueDrainNs.get(),
                writerTimings = activeWriter.lastFinalizationTimings,
                evidenceBuildMs = evidenceBuildMs
            )
            return null
        }
        if (_state.value !is GpuRecordingState.Error) {
            _state.value = GpuRecordingState.Idle
        }
        preparePhysicalReservationAsync()
        val evidenceWriteStartedNs = System.nanoTime()
        val evidencePath = evidence?.let { runtimeEvidence ->
            runCatching { Npv10IntegrityValidator.writeDeviceEvidence(runtimeEvidence).absolutePath }
                .getOrElse { throwable ->
                    Log.e(TAG, "NPV10 device evidence write failed", throwable)
                    null
                }
        }
        val evidenceWriteMs = elapsedMs(evidenceWriteStartedNs)
        val certificateStartedNs = System.nanoTime()
        val qualityCertificatePath = evidence?.let { runtimeEvidence ->
            runCatching {
                writeQualityCertificate(
                    evidence = runtimeEvidence,
                    fps = sessionConfig?.fps ?: 1,
                    deviceEvidencePath = evidencePath,
                    sessionConfig = sessionConfig
                ).absolutePath
            }
                .getOrElse { throwable ->
                    Log.e(TAG, "NPV10 quality certificate write failed", throwable)
                    null
                }
        }
        val certificateMs = elapsedMs(certificateStartedNs)
        recordStopStageTimings(
            stopStartedNs = stopStartedNs,
            queueDrainNs = queueDrainNs.get(),
            writerTimings = activeWriter.lastFinalizationTimings,
            evidenceBuildMs = evidenceBuildMs,
            evidenceWriteMs = evidenceWriteMs,
            certificateMs = certificateMs
        )
        Log.i(
            TAG,
            "NPV10 private master stopped: ${activeOutput?.name} " +
                "frames=$writtenFrames dropped=$dropped payloadBytes=$payloadBytes " +
                "fileBytes=${activeOutput?.length() ?: 0L} firstTimestampNs=$firstTimestampNs " +
                "avgWriteMs=$averageWriteMs audit=${evidence?.auditStatus} " +
                "p010Copies=native:$nativeP010Copies direct:$directP010Copies failed:$failedCopies " +
                "evidence=$evidencePath qualityCertificate=$qualityCertificatePath " +
                "stopStages=$lastStopStageTimings " +
                "path=${activeOutput?.absolutePath.orEmpty()}"
        )
        return activeOutput
    }

    @Synchronized
    fun resetCancelledStartError() {
        if (writer == null && _state.value is GpuRecordingState.Error) {
            _state.value = GpuRecordingState.Idle
            _duration.value = 0L
        }
    }

    private fun writeQualityCertificate(
        evidence: Npv10DeviceEvidence,
        fps: Int,
        deviceEvidencePath: String?,
        sessionConfig: Npv10SessionConfig?
    ): File {
        val runtimeMetadata = linkedMapOf<String, String>().apply {
            putAll(sessionConfig?.featureMetadata.orEmpty())
            putAll(runtimeEvidenceMetadataProvider?.invoke().orEmpty())
            put("pipeline", "custom-packed10-npv10")
            put("deviceEvidencePath", deviceEvidencePath.orEmpty())
            put(
                "gpuProcessingPath",
                if (sessionConfig?.gpuDemosaicEnabled == true) "NPV10_RAW_VULKAN_DEMOSAIC" else "NPV10_PRIVATE_MASTER_WRITE"
            )
            put("gpuProcessingValidated", (sessionConfig?.gpuDemosaicEnabled == true).toString())
            put("zeroCopyValidated", (sessionConfig?.zeroCopyNativeAcquisition == true).toString())
        }
        val runtimeEvidence = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = runtimeMetadata::get,
            cpuUsagePercent = finalizedRecordingCpuUsagePercent ?: calculateRecordingCpuUsagePercent(),
            maxFrameTimeMs = evidence.maxWriteTimeMs.toFloat()
        )
        val baseQualityRequest = RecordingQualityEvidenceRequest(
                mode = EliteVideoPipelineMode.NPV10,
                selectedProfile = evidence.selectedProfile,
                activeTransport = evidence.activeTransport,
                transfer = evidence.transfer,
                primaries = evidence.primaries,
                matrix = evidence.matrix,
                codec = "npv10",
                encodedBitDepth = evidence.encodedBitDepth,
                internalMathBitDepth = evidence.internalMathBitDepth,
                sourceBitDepth = evidence.sourceBitDepth,
                storageBitDepth = evidence.storageBitDepth,
                width = evidence.firstFrameWidth,
                height = evidence.firstFrameHeight,
                fps = fps.coerceAtLeast(1),
                bitrateBps = evidence.eliteMinimumBitrateBps,
                selectedResolution = evidence.selectedResolution,
                actualResolution = evidence.captureResolution,
                normalizedHdrVersion = evidence.eliteNormalizedHdrVersion,
                normalizedLogVersion = evidence.eliteNormalizedLogVersion,
                rawSensorOriginal = sessionConfig?.rawSensorCapture == true,
                captureTruthSignature = "NPV10|${evidence.payloadFormat.name}|native10=${evidence.nativeTenBitCapture}",
                previewQualitySignature = "NPV10|${evidence.captureResolution}|${evidence.payloadFormat.name}",
                recordingQualitySignature = "NPV10|${evidence.captureResolution}|${evidence.payloadFormat.name}",
                expectedFrameCount = evidence.frameCount,
                actualFrameCount = evidence.frameCount,
                droppedFrames = evidence.droppedFrames,
                maxAllowedDroppedFrames = 0,
                probe = null,
                ffprobeValidated = false,
                mediaInfoValidated = true,
                deviceLogValidated = false,
                npv10PayloadFormat = evidence.payloadFormat.name,
                npv10NativeTenBitClaimed = evidence.nativeTenBitCapture,
                npv10MasterAvailable = evidence.npv10MasterAvailable,
                recordingId = File(evidence.masterPath).nameWithoutExtension,
                recordedFileName = File(evidence.masterPath).name,
                recordedFileBytes = evidence.fileBytes,
                mediaStoreValidated = false,
                allGpuProcessingClaimed = runtimeEvidence.allGpuProcessingClaimed,
                zeroCopyProcessingClaimed = runtimeEvidence.zeroCopyProcessingClaimed,
                gpuProcessingValidated = runtimeEvidence.gpuProcessingValidated,
                zeroCopyValidated = runtimeEvidence.zeroCopyValidated,
                performanceTelemetryValidated = runtimeEvidence.performanceTelemetryValidated,
                gpuProcessingPath = runtimeEvidence.gpuProcessingPath,
                zeroCopyEvidencePath = runtimeEvidence.zeroCopyEvidencePath,
                cpuUsagePercent = runtimeEvidence.cpuUsagePercent,
                maxFrameTimeMs = runtimeEvidence.maxFrameTimeMs,
                targetCpuUsagePercent = runtimeEvidence.targetCpuUsagePercent,
                generatedAtEpochMs = evidence.generatedAtEpochMs
            )
        val stage2Bundle = SingularityStage2RecordingEvidencePolicy.fromQualityRequest(
            request = baseQualityRequest,
            runtimeMetadata = runtimeMetadata
        )
        val stage2Metadata = stage2Bundle.toMetadata()
        val stage3Bundle = SingularityStage3RecordingEvidencePolicy.fromQualityRequest(
            request = baseQualityRequest,
            runtimeMetadata = runtimeMetadata + stage2Metadata
        )
        val stage3Metadata = stage3Bundle.toMetadata()
        val stage5Bundle = SingularityStage5RecordingEnginePolicy.fromQualityRequest(
            request = baseQualityRequest,
            runtimeMetadata = runtimeMetadata + stage2Metadata + stage3Metadata
        )
        val stage5Metadata = SingularityStage5RecordingEnginePolicy.metadataFor(
            request = baseQualityRequest,
            bundle = stage5Bundle
        )
        val stage4Bundle = SingularityStage4RecordingQualityPolicy.fromQualityRequest(
            request = baseQualityRequest,
            runtimeMetadata = runtimeMetadata + stage2Metadata + stage3Metadata + stage5Metadata
        )
        val stage4Metadata = SingularityStage4RecordingQualityPolicy.metadataFor(
            request = baseQualityRequest,
            bundle = stage4Bundle
        )
        val releaseBundle = SingularityProductionReleasePolicy.evaluate(
            request = baseQualityRequest,
            runtimeMetadata = runtimeMetadata + stage2Metadata + stage3Metadata + stage5Metadata + stage4Metadata,
            stage2Bundle = stage2Bundle,
            stage3Bundle = stage3Bundle,
            stage5Bundle = stage5Bundle,
            stage4Bundle = stage4Bundle
        )
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            baseQualityRequest.copy(
                detailMetadata =
                    baseQualityRequest.detailMetadata +
                        stage2Metadata +
                        stage3Metadata +
                        stage5Metadata +
                        stage4Metadata +
                        releaseBundle.releaseMetadata +
                        EliteVisionProductionMeasurement.measuredTierOmegaMetadata(
                            EliteVisionDecodedFrameProvider.Unavailable
                        )
            )
        )
        val stage5Sidecar = SingularityStage5EngineArtifactStore.write(
            rootDirectory = privateRoot,
            recordingId = baseQualityRequest.recordingId,
            reportJson = stage5Bundle.toJsonText()
        )
        val stage4Sidecar = SingularityQualityGateArtifactStore.write(
            rootDirectory = privateRoot,
            recordingId = baseQualityRequest.recordingId,
            reportJson = stage4Bundle.toJsonText()
        )
        val stage6Sidecar = SingularityStage6DeviceMatrixArtifactStore.write(
            rootDirectory = privateRoot,
            recordingId = baseQualityRequest.recordingId,
            reportJson = releaseBundle.stage6DeviceMatrix.toJsonText()
        )
        val smfBridge = SmfSingularityEvidenceBridge.fromExistingEvidence(
            recordingQualityCertificate = certificate
        )
        val smfMetadata = SmfSingularityMetadata.fromReleaseDecision(
            input = releaseBundle.releaseInput,
            decision = releaseBundle.releaseDecision,
            thresholdSetId = stage4Bundle.thresholdSetId,
            thresholdSetHash = stage4Bundle.thresholdSetHash,
            nestedMetadataReferences = smfBridge.nestedMetadataReferences,
            evidenceReferences = smfBridge.evidenceReferences
        )
        val smfResult = SmfSingularityMetadataStore.write(
            rootDirectory = privateRoot,
            recordingId = baseQualityRequest.recordingId,
            metadata = smfMetadata,
            decision = releaseBundle.releaseDecision
        )
        return RecordingQualityCertificateStore.write(privateRoot, certificate).also { sidecar ->
            Log.i(
                TAG,
                "Quality certificate -> ${sidecar.absolutePath} " +
                    "stage5Engines=${stage5Sidecar.absolutePath} " +
                    "stage4QualityGates=${stage4Sidecar.absolutePath} " +
                    "stage6DeviceMatrix=${stage6Sidecar.absolutePath} " +
                    "smfSingularity=${smfResult.smfFile.absolutePath} " +
                    "releaseReport=${smfResult.releaseReportFile.absolutePath} " +
                    "status=${certificate.status} failures=${certificate.metadata["failureCodes"]} " +
                    "singularityRelease=${certificate.metadata["singularityReleaseLabel"]} " +
                    "stage5StatusSummary=${certificate.metadata["singularityStage5StatusSummary"]} " +
                    "stage4GateSummary=${certificate.metadata["singularityStage4GateSummary"]} " +
                    "stage4ReleaseReadiness=${certificate.metadata["singularityStage4ReleaseReadiness"]} " +
                    "stage6Status=${certificate.metadata["singularityStage6OverallStatus"]} " +
                    "stage6ReleaseCap=${certificate.metadata["singularityStage6ReleaseCap"]} " +
                    "gpuProcessingPath=${certificate.metadata["gpuProcessingPath"]} " +
                    "zeroCopyValidated=${certificate.metadata["zeroCopyValidated"]} " +
                    "performanceTelemetryValidated=${certificate.metadata["performanceTelemetryValidated"]} " +
                    "cpuUsagePercent=${certificate.metadata["cpuUsagePercent"]} " +
                    "maxFrameTimeMs=${certificate.metadata["maxFrameTimeMs"]}"
            )
        }
    }

    private fun calculateRecordingCpuUsagePercent(): Float? {
        val startElapsed = startedElapsedMs
        val startCpuMs = recordingStartProcessCpuMs
        if (startElapsed <= 0L || startCpuMs <= 0L) return null
        val elapsedWallMs = (SystemClock.elapsedRealtime() - startElapsed).coerceAtLeast(1L)
        val elapsedCpuMs = (Process.getElapsedCpuTime() - startCpuMs).coerceAtLeast(0L)
        return (elapsedCpuMs.toFloat() / elapsedWallMs.toFloat() * 100f).coerceAtLeast(0f)
    }

    private fun recordStopStageTimings(
        stopStartedNs: Long,
        queueDrainNs: Long,
        writerTimings: Npv10FileFinalizationTimings,
        evidenceBuildMs: Double,
        evidenceWriteMs: Double = 0.0,
        certificateMs: Double = 0.0
    ) {
        lastStopStageTimings = Npv10StopStageTimings(
            queueDrainMs = queueDrainNs.toDouble() / 1_000_000.0,
            writerSealMs = writerTimings.totalMs,
            truncateMs = writerTimings.truncateMs,
            durableSyncMs = writerTimings.durableSyncMs,
            descriptorCloseMs = writerTimings.descriptorCloseMs,
            evidenceBuildMs = evidenceBuildMs,
            evidenceWriteMs = evidenceWriteMs,
            certificateMs = certificateMs,
            totalMs = elapsedMs(stopStartedNs)
        )
        Log.i(TAG, "NPV10 stop stage timings $lastStopStageTimings")
    }

    private fun elapsedMs(startedNs: Long): Double =
        (System.nanoTime() - startedNs).coerceAtLeast(0L).toDouble() / 1_000_000.0

    fun release() {
        stop()
        p010PayloadBufferPool.clear()
        discardPreparedPhysicalReservation()
        reservationExecutor.shutdownNow()
        writerExecutor.shutdownNow()
    }

    private companion object {
        const val TAG = "Npv10RecordingManager"
        const val PENDING_FRAME_BACKPRESSURE_TIMEOUT_MS = 5_000L
    }
}
