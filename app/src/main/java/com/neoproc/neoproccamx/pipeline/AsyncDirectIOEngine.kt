package com.neoproc.neoproccamx.pipeline

import android.annotation.SuppressLint
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.neoproc.neoproccamx.recording.RecordingPreallocationMethod
import com.neoproc.neoproccamx.recording.RecordingPreallocationPolicy
import com.neoproc.neoproccamx.recording.RecordingPreallocationResult
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ThreadFactory

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * ASYNCHRONOUS O_DIRECT STORAGE ENGINE
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * High-throughput asynchronous I/O engine for sustained 2-Gbps writes.
 * Bypasses the Linux page cache (O_DIRECT) to prevent buffer bloat during
 * sustained 8K/RAW recording sessions.
 *
 * Architecture:
 *   Camera → WriteQueue (bounded, non-blocking) → WriterThread → O_DIRECT fd
 *
 * Key properties:
 *   • Positioned sequential writes on a dedicated high-priority writer
 *   • Direct buffers with bounded ownership and no camera-thread disk I/O
 *   • Double-buffered: producer and consumer never contend on the same buffer
 *   • Bounded backpressure: producer waits briefly for storage hiccups instead of dropping immediately
 *   • Graceful degradation: falls back to standard I/O on unsupported devices
 *
 * #20 NOTE (honest behaviour on shipped builds): on Android Q+ RAW streams are written through a
 * MediaStore ParcelFileDescriptor (scoped storage) cannot be reopened with O_DIRECT. More importantly,
 * NPRAW packets are variable-sized and therefore cannot legally be sent to an O_DIRECT descriptor without
 * an aligned padding/index format. Older code opened the legacy path with O_DIRECT and then attempted an
 * "ordinary" unaligned pwrite on that same descriptor; Linux correctly rejected it with EINVAL. Until the
 * container owns aligned padding, this engine deliberately uses a normal descriptor. The page cache is
 * bounded by the queue and fsync/ftruncate finalisation, while every write remains off the camera/UI thread.
 *
 * Performance target: 100% write stability for high-bitrate RAW throughput without UI-thread micro-stutters.
 */
internal class AsyncDirectIOEngine(
    private val queueCapacity: Int = 16,
    private val alignmentBytes: Int = 4096,
    private val maxResidentBuffers: Int = DEFAULT_MAX_RESIDENT_BUFFERS,
    private val maxBorrowedPayloadWrites: Int = DEFAULT_MAX_BORROWED_PAYLOAD_WRITES
) : AutoCloseable {

    companion object {
        private const val TAG = "AsyncDirectIO"
        private const val ENQUEUE_BACKPRESSURE_TIMEOUT_MS = 250L
        private const val BUFFER_BACKPRESSURE_TIMEOUT_MS = 250L
        internal const val DEFAULT_MAX_RESIDENT_BUFFERS = 2
        internal const val DEFAULT_MAX_BORROWED_PAYLOAD_WRITES = 4
        
        /** Check if O_DIRECT is available on this device/kernel. */
        fun isDirectIOSupported(): Boolean = false
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // Write request: aligned buffer + metadata
    // ═════════════════════════════════════════════════════════════════════════════

    internal class WriteRequest(
        val buffer: ByteBuffer,    // Engine-owned packet or borrowed camera payload
        val length: Int,           // Bytes to consume from buffer
        val offset: Long,          // File offset for pwrite
        val directEligible: Boolean,
        val header: ByteArray? = null,
        val borrowedPayload: Boolean = false,
        val callback: ((Boolean) -> Unit)? = null
    ) {
        val totalLength: Int = Math.addExact(header?.size ?: 0, length)
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // State
    // ═════════════════════════════════════════════════════════════════════════════

    private val writeQueue = ArrayBlockingQueue<WriteRequest>(queueCapacity)
    private val writerThread: ExecutorService = Executors.newSingleThreadExecutor(
        ThreadFactory { r ->
            Thread(r, "DirectIO-Writer").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = false
            }
        }
    )
    /** Physical extent growth must never consume the ordered frame-writer lane. */
    private val reservationExecutor: ExecutorService = Executors.newSingleThreadExecutor(
        ThreadFactory { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "RawReservation-Writer").apply {
                priority = Thread.NORM_PRIORITY
                isDaemon = true
            }
        }
    )
    private val reservationStateLock = Any()
    private val running = AtomicBoolean(false)
    private val activeWrites = AtomicInteger(0)
    private val totalBytesWritten = AtomicLong(0L)
    private val totalWriteOps = AtomicLong(0L)
    private val droppedWrites = AtomicLong(0L)
    private val committedFileLength = AtomicLong(0L)
    /** Camera-backed buffers retained by queued or active segmented writes. */
    private val borrowedPayloadWrites = AtomicInteger(0)
    private val borrowedPayloadPermits = Semaphore(maxBorrowedPayloadWrites, true)
    private val bufferPool = BoundedDirectBufferPool(maxResidentBuffers)
    private val reservationExtensionAttempts = AtomicLong(0L)
    private val reservationExtensionSuccesses = AtomicLong(0L)
    private val reservationExtensionFailures = AtomicLong(0L)
    private val reservationExtendedBytes = AtomicLong(0L)
    @Volatile private var activeFd: FileDescriptor? = null
    @Volatile private var activePfd: ParcelFileDescriptor? = null
    @Volatile private var useDirectIO: Boolean = false
    @Volatile private var currentFileOffset: Long = 0L
    @Volatile private var writerFuture: Future<*>? = null
    @Volatile private var pendingReservationGrowth: Future<*>? = null
    @Volatile private var reservationGrowthFailure: Throwable? = null
    @Volatile private var reservationCapacityExhausted: Boolean = false
    @Volatile private var physicalReservedBytes: Long = 0L
    @Volatile var preallocationResult: RecordingPreallocationResult =
        RecordingPreallocationResult(
            plan = RecordingPreallocationPolicy.raw(0L),
            method = RecordingPreallocationMethod.NONE,
            reservedBytes = 0L
        )
        private set

    init {
        require(queueCapacity > 0) { "RAW write queue capacity must be positive" }
        require(maxResidentBuffers > 0) { "RAW packet-buffer capacity must be positive" }
        require(maxBorrowedPayloadWrites > 0) { "Borrowed RAW payload capacity must be positive" }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Open a file for high-throughput direct I/O writing.
     *
     * @param path Absolute path to the output file.
     * @param directIO Whether to use O_DIRECT (bypasses page cache).
     */
    @SuppressLint("UsableSpace")
    fun open(
        path: String,
        directIO: Boolean = false,
        availableStorageBytes: Long = File(path).parentFile?.usableSpace ?: 0L
    ) {
        if (running.getAndSet(true)) {
            Log.w(TAG, "Already open — close first")
            return
        }

        resetSessionCounters(directIO = false)
        activePfd = null
        if (directIO) {
            Log.i(TAG, "O_DIRECT request suppressed: NPRAW packets are not alignment-padded")
        }
        try {
            val flags = OsConstants.O_CREAT or OsConstants.O_WRONLY or OsConstants.O_TRUNC
            activeFd = Os.open(path, flags, 0b110_100_100) // 0644
        } catch (e: Exception) {
            Log.e(TAG, "Standard open failed: ${e.message}")
            running.set(false)
            return
        }

        preallocationResult = preallocate(activeFd, availableStorageBytes)
        initializeRollingReservationState()
        startWriterLoop()
    }

    /**
     * Open a caller-owned file descriptor, typically from MediaStore on scoped
     * storage devices. This path uses standard writes because O_DIRECT flags
     * must be selected when the descriptor is opened.
     */
    fun open(pfd: ParcelFileDescriptor, availableStorageBytes: Long = 0L) {
        if (running.getAndSet(true)) {
            Log.w(TAG, "Already open — close first")
            pfd.close()
            return
        }
        resetSessionCounters(directIO = false)
        activePfd = pfd
        activeFd = pfd.fileDescriptor
        preallocationResult = preallocate(activeFd, availableStorageBytes)
        initializeRollingReservationState()
        startWriterLoop()
    }

    /**
     * Enqueue a write request. Uses bounded backpressure so transient storage stalls do not drop
     * strict-master data.
     *
     * @param data Raw bytes to write.
     * @param length Number of bytes from data to write.
     * @param callback Optional completion callback (called from writer thread).
     * @return true if enqueued successfully, false if dropped.
     */
    fun enqueueWrite(data: ByteArray, length: Int, callback: ((Boolean) -> Unit)? = null): Boolean {
        if (!running.get()) return false

        val safeLength = length.coerceIn(0, data.size)
        val directBuffer = acquireDirectBuffer(safeLength) ?: return rejectBufferAcquisition(callback)
        try {
            directBuffer.put(data, 0, safeLength)
        } catch (failure: Throwable) {
            releaseDirectBuffer(directBuffer)
            throw failure
        }
        return enqueuePreparedWrite(directBuffer, safeLength, callback)
    }

    /**
     * Enqueue a write from a ByteBuffer (zero-copy for NIO channels).
     */
    fun enqueueWrite(buffer: ByteBuffer, callback: ((Boolean) -> Unit)? = null): Boolean {
        if (!running.get()) return false

        val length = buffer.remaining()
        val directBuffer = acquireDirectBuffer(length) ?: return rejectBufferAcquisition(callback)
        try {
            directBuffer.put(buffer)
        } catch (failure: Throwable) {
            releaseDirectBuffer(directBuffer)
            throw failure
        }
        return enqueuePreparedWrite(directBuffer, length, callback)
    }

    /**
     * Borrow an engine-owned direct buffer for a producer that can generate the packet in place. The
     * caller must pass it to [enqueuePreparedWrite] or [recyclePreparedWriteBuffer].
     */
    fun acquirePreparedWriteBuffer(minCapacity: Int): ByteBuffer? =
        if (running.get()) acquireDirectBuffer(minCapacity) else null

    /** Warm the complete double-buffered resident set before the first camera packet arrives. */
    fun primePreparedWriteBuffer(minCapacity: Int): Boolean {
        if (!running.get() || minCapacity <= 0) return false
        return try {
            bufferPool.prewarm(minCapacity).also { primed ->
                if (primed) {
                    Log.i(
                        TAG,
                        "Bounded realtime packet buffers ready " +
                            "allocations=${bufferPool.allocationCount} " +
                            "resident=${bufferPool.residentCount} " +
                            "available=${bufferPool.availableCount} limit=$maxResidentBuffers"
                    )
                }
            }
        } catch (allocationFailure: OutOfMemoryError) {
            bufferPool.close()
            Log.e(TAG, "Unable to reserve bounded realtime packet buffers", allocationFailure)
            false
        }
    }

    fun recyclePreparedWriteBuffer(buffer: ByteBuffer) {
        releaseDirectBuffer(buffer)
    }

    /**
     * Starts rolling physical allocation early and proves that [requiredEndBytes] is already backed
     * by an allocated extent. The camera thread never waits for fallocate: while growth is pending it
     * can keep admitting frames inside the current extent, then end the clip cleanly at that exact
     * boundary if storage cannot extend in time.
     */
    @Synchronized
    fun preparePhysicalCapacity(requiredEndBytes: Long): Boolean {
        if (!running.get() || requiredEndBytes <= 0L) return false
        scheduleRawReservationGrowthIfRequired(requiredEndBytes)
        val snapshot = preallocationResult
        return snapshot.method == RecordingPreallocationMethod.POSIX_FALLOCATE &&
            requiredEndBytes <= physicalReservedBytes
    }

    /**
     * Transfer ownership of an already-filled direct buffer to the storage queue without copying it.
     * Ownership is consumed on every return path.
     */
    @Synchronized
    fun enqueuePreparedWrite(
        buffer: ByteBuffer,
        length: Int,
        callback: ((Boolean) -> Unit)? = null
    ): Boolean {
        val safeLength = length.coerceIn(0, buffer.capacity())
        if (!running.get() || !buffer.isDirect || safeLength != length) {
            releaseDirectBuffer(buffer)
            callback?.let { invokeCompletion(it, success = false) }
            return false
        }
        buffer.clear()
        buffer.limit(safeLength)
        val requiredEndBytes = try {
            Math.addExact(currentFileOffset, safeLength.toLong())
        } catch (_: ArithmeticException) {
            releaseDirectBuffer(buffer)
            droppedWrites.incrementAndGet()
            callback?.let { invokeCompletion(it, success = false) }
            return false
        }
        if (preallocationResult.method == RecordingPreallocationMethod.POSIX_FALLOCATE &&
            !preparePhysicalCapacity(requiredEndBytes)
        ) {
            releaseDirectBuffer(buffer)
            droppedWrites.incrementAndGet()
            callback?.let { invokeCompletion(it, success = false) }
            return false
        }
        val directEligible = directWriteEligible(currentFileOffset, safeLength)

        val request = WriteRequest(
            buffer = buffer,
            length = safeLength,
            offset = currentFileOffset,
            directEligible = directEligible,
            callback = callback
        )

        val enqueued = try {
            writeQueue.offer(request, ENQUEUE_BACKPRESSURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!enqueued) {
            droppedWrites.incrementAndGet()
            releaseDirectBuffer(buffer)
            callback?.let { invokeCompletion(it, success = false) }
        } else {
            currentFileOffset = requiredEndBytes
            scheduleRawReservationGrowthIfRequired(currentFileOffset)
        }
        return enqueued
    }

    /**
     * Enqueue one logical packet as a small owned header followed by a borrowed direct payload.
     *
     * This path deliberately does not copy the camera payload. The caller must keep the resource
     * backing [payload] alive until [callback] runs. Completion is delivered exactly once on every
     * accepted, rejected, failed, or successful path. The writer remains strictly ordered, so a
     * partial segmented write cannot advance [committedFileLength] and is truncated at finalization.
     */
    @Synchronized
    fun enqueueBorrowedSegmentedWrite(
        header: ByteArray,
        payload: ByteBuffer,
        payloadLength: Int = payload.remaining(),
        callback: (Boolean) -> Unit
    ): Boolean {
        val validPayloadLength = payloadLength > 0 && payloadLength <= payload.remaining()
        if (!running.get() || header.isEmpty() || !payload.isDirect || !validPayloadLength) {
            droppedWrites.incrementAndGet()
            invokeCompletion(callback, success = false)
            return false
        }

        val payloadSnapshot = payload.duplicate().apply {
            limit(position() + payloadLength)
        }.slice()
        val headerSnapshot = header.copyOf()
        val totalLength = try {
            Math.addExact(headerSnapshot.size, payloadLength)
        } catch (_: ArithmeticException) {
            droppedWrites.incrementAndGet()
            invokeCompletion(callback, success = false)
            return false
        }
        val requiredEndBytes = try {
            Math.addExact(currentFileOffset, totalLength.toLong())
        } catch (_: ArithmeticException) {
            droppedWrites.incrementAndGet()
            invokeCompletion(callback, success = false)
            return false
        }
        if (preallocationResult.method == RecordingPreallocationMethod.POSIX_FALLOCATE &&
            !preparePhysicalCapacity(requiredEndBytes)
        ) {
            droppedWrites.incrementAndGet()
            invokeCompletion(callback, success = false)
            return false
        }
        val request = WriteRequest(
            buffer = payloadSnapshot,
            length = payloadLength,
            offset = currentFileOffset,
            // A two-segment packet cannot satisfy the single-buffer O_DIRECT alignment contract.
            directEligible = false,
            header = headerSnapshot,
            borrowedPayload = true,
            callback = callback
        )
        check(request.totalLength == totalLength)

        val borrowedPermit = try {
            borrowedPayloadPermits.tryAcquire(
                ENQUEUE_BACKPRESSURE_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!borrowedPermit) {
            droppedWrites.incrementAndGet()
            invokeCompletion(callback, success = false)
            return false
        }

        // Publish ownership before the queue offer: the writer is allowed to complete immediately.
        borrowedPayloadWrites.incrementAndGet()
        val enqueued = try {
            writeQueue.offer(request, ENQUEUE_BACKPRESSURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!enqueued) {
            borrowedPayloadWrites.decrementAndGet()
            borrowedPayloadPermits.release()
            droppedWrites.incrementAndGet()
            invokeCompletion(callback, success = false)
        } else {
            currentFileOffset = requiredEndBytes
            scheduleRawReservationGrowthIfRequired(currentFileOffset)
        }
        return enqueued
    }

    /**
     * Flush all pending writes and close the file descriptor.
     */
    @Synchronized
    override fun close() {
        val ownedWriter = running.getAndSet(false)
        if (!ownedWriter && activeFd == null && activePfd == null) return
        check(Thread.currentThread().name != "DirectIO-Writer") {
            "Direct I/O writer cannot close its own descriptor before its loop exits"
        }

        waitForWriterDrain()

        // A fallocate operation owns the same descriptor even though it runs outside the ordered
        // writer lane. Retire that owner before truncation/fsync/close so an extension can never race
        // the committed-length transaction.
        awaitReservationGrowthForClose()
        reservationExecutor.shutdown()
        check(awaitReservationExecutorTermination()) {
            "RAW reservation worker still owns the descriptor; refusing unsafe close"
        }

        // Shut the writer thread down and AWAIT its exit BEFORE releasing the descriptor, so the fd is
        // never closed while the writer may still be inside Os.pwrite(fd, ...). Closing early would risk
        // EBADF (dropping tail frames) or, if the fd number is reused, writing RAW bytes into an unrelated
        // file. The success-path drain above has already made the writer loop exit, so this returns
        // immediately in the normal case and only adds a real wait on the drain-timeout path. Shutting the
        // executor down here also stops the "DirectIO-Writer" thread leaking one thread per start/stop of a
        // RAW recording within a session.
        check(awaitWriterThreadTermination()) {
            "Direct I/O writer still owns the RAW descriptor; refusing unsafe close"
        }

        finalizeCommittedLength()

        // Close fd
        val closeFailure = try {
            val pfd = activePfd
            if (pfd != null) {
                pfd.close()
            } else {
                activeFd?.let { Os.close(it) }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Close error: ${e.message}")
            e
        }
        activeFd = null
        activePfd = null
        if (closeFailure != null) {
            recordDurabilityFailure("RAW descriptor close failed: ${closeFailure.message.orEmpty()}")
        }
        val packetBufferAllocations = bufferPool.allocationCount
        val packetBufferResident = bufferPool.residentCount
        bufferPool.close()

        Log.d(TAG, "Closed: ${totalBytesWritten.get()} bytes, " +
                "${totalWriteOps.get()} ops, ${droppedWrites.get()} dropped, " +
                "committed=${committedFileLength.get()}, " +
                "packetBuffers=$packetBufferAllocations/$packetBufferResident, " +
                "preallocation=${preallocationResult.method}:${preallocationResult.reservedBytes}, " +
                "rolling=${reservationExtensionSuccesses.get()}/${reservationExtensionAttempts.get()} " +
                "extended=${reservationExtendedBytes.get()}")
    }

    /**
     * Signal the single writer thread to stop and wait for it to provably exit BEFORE the caller
     * releases the file descriptor. On the success path the drain has already terminated the writer
     * loop, so this returns almost immediately; on the drain-timeout path it interrupts the writer and
     * waits a hard bound so the writer cannot still be mid-Os.pwrite when the fd is closed.
     */
    private fun awaitWriterThreadTermination(): Boolean {
        try {
            writerThread.shutdown()
            if (!writerThread.awaitTermination(AsyncDirectIODrainPolicy.DRAIN_POLL_MS * 10, TimeUnit.MILLISECONDS)) {
                // Writer still active after the drain window: interrupt it and wait a hard bound so it can
                // finish or unwind out of any in-flight pwrite before we release the fd.
                writerThread.shutdownNow()
                if (!writerThread.awaitTermination(AsyncDirectIODrainPolicy.DEFAULT_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.e(TAG, "Writer thread still alive after hard timeout; retaining RAW descriptor")
                    return false
                }
            }
            return true
        } catch (e: InterruptedException) {
            writerThread.shutdownNow()
            Thread.currentThread().interrupt()
            return false
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // Telemetry
    // ═════════════════════════════════════════════════════════════════════════════

    val bytesWritten: Long get() = totalBytesWritten.get()
    val writeOps: Long get() = totalWriteOps.get()
    val dropped: Long get() = droppedWrites.get()
    val queueDepth: Int get() = writeQueue.size
    val activeWriteCount: Int get() = activeWrites.get()
    val borrowedPayloadWriteCount: Int get() = borrowedPayloadWrites.get()
    val acceptedFileBytes: Long get() = currentFileOffset
    val physicallyReservedBytes: Long get() = physicalReservedBytes
    val reservationGrowthFailureMessage: String?
        get() = reservationGrowthFailure?.message
    val storageCapacityExhausted: Boolean get() = reservationCapacityExhausted
    val isOpen: Boolean get() = running.get()

    // ═════════════════════════════════════════════════════════════════════════════
    // Writer Loop (runs on dedicated high-priority thread)
    // ═════════════════════════════════════════════════════════════════════════════

    private fun writerLoop() {
        try {
            while (running.get() || writeQueue.isNotEmpty()) {
                val request = try {
                    writeQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    continue
                } ?: continue

                activeWrites.incrementAndGet()
                var succeeded = false
                try {
                    val fd = activeFd ?: throw IOException("RAW descriptor unavailable for queued write")
                    val direct = useDirectIO && request.directEligible
                    val written = try {
                        writeRequest(fd, request, direct = direct)
                    } catch (directFailure: Exception) {
                        if (!direct) throw directFailure
                        Log.w(TAG, "O_DIRECT write failed; retrying same request with positioned standard I/O")
                        useDirectIO = false
                        writeRequest(fd, request, direct = false)
                    }
                    check(written == request.totalLength) {
                        "RAW packet short write: $written/${request.totalLength}"
                    }

                    totalBytesWritten.addAndGet(written.toLong())
                    totalWriteOps.incrementAndGet()
                    val committed = committedFileLength.get()
                    if (request.offset == committed) {
                        committedFileLength.set(request.offset + written.toLong())
                    }
                    succeeded = true
                } catch (e: Exception) {
                    droppedWrites.incrementAndGet()
                    Log.e(TAG, "Write error at offset ${request.offset}: ${e.message}")

                    // If O_DIRECT fails, disable it for remaining writes
                    if (useDirectIO) {
                        Log.w(TAG, "Disabling O_DIRECT after write failure")
                        useDirectIO = false
                    }
                } finally {
                    try {
                        request.callback?.let { invokeCompletion(it, succeeded) }
                    } finally {
                        releaseRequestPayload(request)
                        activeWrites.decrementAndGet()
                    }
                }
            }
        } finally {
            // A descriptor/lifecycle failure must return every borrowed camera buffer exactly once.
            while (true) {
                val abandoned = writeQueue.poll() ?: break
                droppedWrites.incrementAndGet()
                try {
                    abandoned.callback?.let { invokeCompletion(it, success = false) }
                } finally {
                    releaseRequestPayload(abandoned)
                }
            }
        }
    }

    private fun releaseRequestPayload(request: WriteRequest) {
        if (request.borrowedPayload) {
            val remaining = borrowedPayloadWrites.decrementAndGet()
            borrowedPayloadPermits.release()
            check(remaining >= 0) { "Borrowed RAW payload ownership underflow" }
        } else {
            releaseDirectBuffer(request.buffer)
        }
    }

    private fun invokeCompletion(callback: (Boolean) -> Unit, success: Boolean) {
        try {
            callback(success)
        } catch (callbackFailure: Throwable) {
            Log.e(TAG, "RAW write completion callback failed", callbackFailure)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // Utilities
    // ═════════════════════════════════════════════════════════════════════════════

    private fun alignUp(value: Int, alignment: Int): Int {
        return (value + alignment - 1) and (alignment - 1).inv()
    }

    // #20: O_DIRECT engages only when the fd was opened O_DIRECT (useDirectIO — false on scoped-storage PFDs,
    // i.e. all Android Q+) AND both length and offset are 4096-aligned. Arbitrary NPRAW frame sizes rarely
    // satisfy this, so the buffered Os.pwrite path is the norm. Correctness is identical either way.
    private fun directWriteEligible(offset: Long, length: Int): Boolean =
        useDirectIO &&
            length > 0 &&
            length == alignUp(length, alignmentBytes) &&
            offset % alignmentBytes.toLong() == 0L

    private fun acquireDirectBuffer(minCapacity: Int): ByteBuffer? =
        bufferPool.acquire(
            minCapacity = minCapacity.coerceAtLeast(1),
            timeout = BUFFER_BACKPRESSURE_TIMEOUT_MS,
            unit = TimeUnit.MILLISECONDS
        )

    private fun releaseDirectBuffer(buffer: ByteBuffer) {
        bufferPool.recycle(buffer)
    }

    private fun rejectBufferAcquisition(callback: ((Boolean) -> Unit)?): Boolean {
        droppedWrites.incrementAndGet()
        callback?.let { invokeCompletion(it, success = false) }
        return false
    }

    private fun resetSessionCounters(directIO: Boolean) {
        useDirectIO = directIO
        currentFileOffset = 0L
        committedFileLength.set(0L)
        activeWrites.set(0)
        totalBytesWritten.set(0L)
        totalWriteOps.set(0L)
        droppedWrites.set(0L)
        physicalReservedBytes = 0L
        reservationGrowthFailure = null
        reservationCapacityExhausted = false
        pendingReservationGrowth = null
        reservationExtensionAttempts.set(0L)
        reservationExtensionSuccesses.set(0L)
        reservationExtensionFailures.set(0L)
        reservationExtendedBytes.set(0L)
        preallocationResult = RecordingPreallocationResult(
            plan = RecordingPreallocationPolicy.raw(0L),
            method = RecordingPreallocationMethod.NONE,
            reservedBytes = 0L
        )
    }

    private fun preallocate(
        fileDescriptor: FileDescriptor?,
        availableStorageBytes: Long
    ): RecordingPreallocationResult {
        val plan = RecordingPreallocationPolicy.raw(availableStorageBytes)
        val fd = fileDescriptor
        if (fd == null || !plan.enabled) {
            return RecordingPreallocationResult(
                plan = plan,
                method = RecordingPreallocationMethod.NONE,
                reservedBytes = 0L
            )
        }
        return try {
            Os.posix_fallocate(fd, 0L, plan.reservationBytes)
            RecordingPreallocationResult(
                plan = plan,
                method = RecordingPreallocationMethod.POSIX_FALLOCATE,
                reservedBytes = plan.reservationBytes
            )
        } catch (fallocateFailure: Throwable) {
            // ftruncate/setLength is sparse on common Android filesystems and does not reserve blocks.
            // Never report that logical length as successful preallocation.
            RecordingPreallocationResult(
                plan = plan,
                method = RecordingPreallocationMethod.NONE,
                reservedBytes = 0L,
                failure = fallocateFailure.message
            )
        }
    }

    private fun initializeRollingReservationState() {
        physicalReservedBytes = preallocationResult.reservedBytes.coerceAtLeast(0L)
    }

    private fun scheduleRawReservationGrowthIfRequired(requiredEndBytes: Long) {
        val snapshot = preallocationResult
        if (!running.get() || snapshot.method != RecordingPreallocationMethod.POSIX_FALLOCATE ||
            snapshot.plan.rollingExtensionBytes <= 0L || reservationCapacityExhausted ||
            reservationGrowthFailure != null ||
            !RecordingPreallocationPolicy.shouldScheduleRawGrowth(
                requiredEndBytes = requiredEndBytes,
                reservedBytes = physicalReservedBytes,
                leadBytes = snapshot.plan.rollingLeadBytes
            )
        ) {
            return
        }
        synchronized(reservationStateLock) {
            val active = pendingReservationGrowth
            if (active != null && !active.isDone) return
            val descriptor = activeFd ?: return
            val reservedAtSubmission = physicalReservedBytes
            reservationExtensionAttempts.incrementAndGet()
            pendingReservationGrowth = reservationExecutor.submit {
                val allocationStartedMs = SystemClock.elapsedRealtime()
                try {
                    val stat = Os.fstatvfs(descriptor)
                    val usableBytes = saturatingMultiply(stat.f_bavail, stat.f_frsize)
                    val plan = RecordingPreallocationPolicy.rollingRawExtension(
                        availableBytes = usableBytes,
                        currentReservedBytes = reservedAtSubmission,
                        requestedGrowthBytes = snapshot.plan.rollingExtensionBytes
                    )
                    if (plan.growthBytes < RecordingPreallocationPolicy.MIN_RESERVATION_BYTES) {
                        reservationCapacityExhausted = true
                        Log.w(
                            TAG,
                            "RAW rolling reservation reached safe storage boundary " +
                                "reserved=$reservedAtSubmission usable=$usableBytes " +
                                "safety=${plan.safetyReserveBytes} reason=${plan.reason}"
                        )
                        return@submit
                    }
                    var allocatedBytes = 0L
                    var allocationSlices = 0
                    while (allocatedBytes < plan.growthBytes) {
                        val sliceBytes = RecordingPreallocationPolicy.nextRawAllocationSliceBytes(
                            plan.growthBytes - allocatedBytes
                        )
                        check(sliceBytes > 0L) { "RAW reservation slice made no progress" }
                        Os.posix_fallocate(
                            descriptor,
                            reservedAtSubmission + allocatedBytes,
                            sliceBytes
                        )
                        allocatedBytes += sliceBytes
                        allocationSlices += 1
                        // Publish only physically completed bytes. The camera admission check may
                        // safely consume these slices while later slices continue in the background.
                        physicalReservedBytes = reservedAtSubmission + allocatedBytes
                        reservationExtendedBytes.addAndGet(sliceBytes)
                        Thread.yield()
                    }
                    val extensionCount = reservationExtensionSuccesses.incrementAndGet()
                    preallocationResult = preallocationResult.copy(
                        plan = preallocationResult.plan.copy(
                            requestedBytes = plan.targetReservedBytes,
                            availableBytes = plan.availableBytesIncludingCurrentReservation,
                            safetyReserveBytes = plan.safetyReserveBytes,
                            reservationBytes = plan.targetReservedBytes,
                            enabled = true,
                            reason = plan.reason
                        ),
                        method = RecordingPreallocationMethod.POSIX_FALLOCATE,
                        reservedBytes = plan.targetReservedBytes,
                        rollingExtensionCount = extensionCount,
                        rollingExtendedBytes = reservationExtendedBytes.get(),
                        failure = null,
                        synced = false
                    )
                    NeoLogger.i(
                        TAG,
                        "RAW rolling reservation extended from $reservedAtSubmission to " +
                            "${plan.targetReservedBytes} bytes; growth=${plan.growthBytes} " +
                            "slices=$allocationSlices elapsedMs=" +
                            (SystemClock.elapsedRealtime() - allocationStartedMs)
                    )
                } catch (failure: Throwable) {
                    reservationExtensionFailures.incrementAndGet()
                    reservationGrowthFailure = failure
                    preallocationResult = preallocationResult.copy(
                        reservedBytes = physicalReservedBytes,
                        failure = "RAW rolling reservation failed: " +
                            (failure.message ?: failure.javaClass.simpleName),
                        rollingExtensionCount = reservationExtensionSuccesses.get(),
                        rollingExtendedBytes = reservationExtendedBytes.get(),
                        synced = false
                    )
                    NeoLogger.e(TAG, "RAW rolling reservation failed", failure)
                }
            }
        }
    }

    private fun awaitReservationGrowthForClose() {
        val growth = synchronized(reservationStateLock) { pendingReservationGrowth } ?: return
        runCatching {
            growth.get(AsyncDirectIODrainPolicy.DEFAULT_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.onFailure { failure ->
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            Log.e(TAG, "RAW reservation worker did not finish cleanly before close", failure)
        }
    }

    private fun awaitReservationExecutorTermination(): Boolean = try {
        if (reservationExecutor.awaitTermination(
                AsyncDirectIODrainPolicy.DEFAULT_DRAIN_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
        ) {
            true
        } else {
            reservationExecutor.shutdownNow()
            reservationExecutor.awaitTermination(
                AsyncDirectIODrainPolicy.DEFAULT_DRAIN_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
        }
    } catch (_: InterruptedException) {
        reservationExecutor.shutdownNow()
        Thread.currentThread().interrupt()
        false
    }

    private fun saturatingMultiply(left: Long, right: Long): Long {
        val safeLeft = left.coerceAtLeast(0L)
        val safeRight = right.coerceAtLeast(0L)
        if (safeLeft == 0L || safeRight == 0L) return 0L
        return if (safeLeft > Long.MAX_VALUE / safeRight) Long.MAX_VALUE else safeLeft * safeRight
    }

    private fun finalizeCommittedLength() {
        val fd = activeFd ?: run {
            recordDurabilityFailure("RAW descriptor unavailable during committed-length finalization")
            return
        }
        try {
            Os.ftruncate(fd, committedFileLength.get())
            Os.fsync(fd)
            preallocationResult = preallocationResult.copy(
                committedBytes = committedFileLength.get(),
                reservationTrimmed = preallocationResult.reservedBytes > committedFileLength.get(),
                synced = true
            )
        } catch (e: Exception) {
            recordDurabilityFailure("Unable to finalize RAW committed length: ${e.message.orEmpty()}")
            Log.e(TAG, "Unable to finalize RAW committed length: ${e.message}")
        }
    }

    private fun recordDurabilityFailure(message: String) {
        val previousFailure = preallocationResult.failure
        preallocationResult = preallocationResult.copy(
            failure = listOfNotNull(previousFailure, message)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("; "),
            committedBytes = committedFileLength.get(),
            reservationTrimmed = false,
            synced = false
        )
    }

    private fun startWriterLoop() {
        writerFuture = writerThread.submit {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            writerLoop()
        }
    }

    private fun waitForWriterDrain() {
        val future = writerFuture
        if (future != null && !future.isDone && Thread.currentThread().name != "DirectIO-Writer") {
            try {
                future.get(AsyncDirectIODrainPolicy.DEFAULT_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                return
            } catch (e: TimeoutException) {
                Log.w(
                    TAG,
                    "Timed out draining direct I/O before close " +
                        "(queue=${writeQueue.size}, active=${activeWrites.get()})"
                )
                return
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "Interrupted while draining direct I/O before close")
                return
            } catch (e: ExecutionException) {
                Log.e(TAG, "Direct I/O writer failed before close", e.cause ?: e)
                return
            }
        }

        val startMs = System.currentTimeMillis()
        while (
            AsyncDirectIODrainPolicy.shouldWaitForDrain(
                queuedWrites = writeQueue.size,
                activeWrites = activeWrites.get(),
                elapsedMs = System.currentTimeMillis() - startMs
            )
        ) {
            Thread.sleep(AsyncDirectIODrainPolicy.DRAIN_POLL_MS)
        }
    }

    private fun writeRequest(
        fd: FileDescriptor,
        request: WriteRequest,
        direct: Boolean
    ): Int {
        var totalWritten = 0
        request.header?.let { header ->
            totalWritten += writeByteArrayFully(
                fd = fd,
                bytes = header,
                offset = request.offset
            )
        }
        val buffer = request.buffer.duplicate()
        buffer.limit(buffer.position() + request.length.coerceAtMost(buffer.remaining()))
        var offset = request.offset + totalWritten.toLong()
        while (buffer.hasRemaining()) {
            val written = if (direct) {
                Os.pwrite(fd, buffer, offset)
            } else {
                Os.pwrite(fd, buffer, offset)
            }
            if (written <= 0) {
                throw IOException("RAW stream write made no progress")
            }
            totalWritten += written
            offset += written.toLong()
        }
        return totalWritten
    }

    private fun writeByteArrayFully(
        fd: FileDescriptor,
        bytes: ByteArray,
        offset: Long
    ): Int {
        var sourceOffset = 0
        var totalWritten = 0
        while (sourceOffset < bytes.size) {
            val written = Os.pwrite(
                fd,
                bytes,
                sourceOffset,
                bytes.size - sourceOffset,
                offset + totalWritten.toLong()
            )
            if (written <= 0) throw IOException("RAW header write made no progress")
            sourceOffset += written
            totalWritten += written
        }
        return totalWritten
    }
}
