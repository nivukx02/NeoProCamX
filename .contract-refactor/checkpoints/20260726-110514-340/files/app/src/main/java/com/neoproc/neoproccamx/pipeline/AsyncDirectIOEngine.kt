package com.neoproc.neoproccamx.pipeline

import android.annotation.SuppressLint
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.neoproc.neoproccamx.recording.RecordingPreallocationMethod
import com.neoproc.neoproccamx.recording.RecordingPreallocationPolicy
import com.neoproc.neoproccamx.recording.RecordingPreallocationResult
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
    private val alignmentBytes: Int = 4096
) : AutoCloseable {

    companion object {
        private const val TAG = "AsyncDirectIO"
        private const val ENQUEUE_BACKPRESSURE_TIMEOUT_MS = 250L
        
        /** Check if O_DIRECT is available on this device/kernel. */
        fun isDirectIOSupported(): Boolean = false
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // Write request: aligned buffer + metadata
    // ═════════════════════════════════════════════════════════════════════════════

    internal class WriteRequest(
        val buffer: ByteBuffer,    // Must be direct + aligned
        val length: Int,
        val offset: Long,          // File offset for pwrite
        val directEligible: Boolean,
        val callback: ((Boolean) -> Unit)? = null
    )

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
    private val running = AtomicBoolean(false)
    private val activeWrites = AtomicInteger(0)
    private val totalBytesWritten = AtomicLong(0L)
    private val totalWriteOps = AtomicLong(0L)
    private val droppedWrites = AtomicLong(0L)
    private val committedFileLength = AtomicLong(0L)
    private val bufferPool = ArrayBlockingQueue<ByteBuffer>(queueCapacity)
    @Volatile private var activeFd: FileDescriptor? = null
    @Volatile private var activePfd: ParcelFileDescriptor? = null
    @Volatile private var useDirectIO: Boolean = false
    @Volatile private var currentFileOffset: Long = 0L
    @Volatile private var writerFuture: Future<*>? = null
    @Volatile var preallocationResult: RecordingPreallocationResult =
        RecordingPreallocationResult(
            plan = RecordingPreallocationPolicy.raw(0L),
            method = RecordingPreallocationMethod.NONE,
            reservedBytes = 0L
        )
        private set

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
        val directEligible = directWriteEligible(currentFileOffset, safeLength)
        val directBuffer = acquireDirectBuffer(safeLength)
        directBuffer.put(data, 0, safeLength)
        directBuffer.flip()

        val request = WriteRequest(
            buffer = directBuffer,
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
            releaseDirectBuffer(directBuffer)
            callback?.invoke(false)
        } else {
            currentFileOffset += safeLength.toLong()
        }
        return enqueued
    }

    /**
     * Enqueue a write from a ByteBuffer (zero-copy for NIO channels).
     */
    fun enqueueWrite(buffer: ByteBuffer, callback: ((Boolean) -> Unit)? = null): Boolean {
        if (!running.get()) return false

        val length = buffer.remaining()
        val directEligible = directWriteEligible(currentFileOffset, length)
        val directBuffer = acquireDirectBuffer(length)
        directBuffer.put(buffer)
        directBuffer.flip()

        val request = WriteRequest(
            buffer = directBuffer,
            length = length,
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
            releaseDirectBuffer(directBuffer)
            callback?.invoke(false)
        } else {
            currentFileOffset += length.toLong()
        }
        return enqueued
    }

    /**
     * Flush all pending writes and close the file descriptor.
     */
    override fun close() {
        if (!running.getAndSet(false)) return

        waitForWriterDrain()

        // Shut the writer thread down and AWAIT its exit BEFORE releasing the descriptor, so the fd is
        // never closed while the writer may still be inside Os.pwrite(fd, ...). Closing early would risk
        // EBADF (dropping tail frames) or, if the fd number is reused, writing RAW bytes into an unrelated
        // file. The success-path drain above has already made the writer loop exit, so this returns
        // immediately in the normal case and only adds a real wait on the drain-timeout path. Shutting the
        // executor down here also stops the "DirectIO-Writer" thread leaking one thread per start/stop of a
        // RAW recording within a session.
        awaitWriterThreadTermination()

        finalizeCommittedLength()

        // Close fd
        try {
            val pfd = activePfd
            if (pfd != null) {
                pfd.close()
            } else {
                activeFd?.let { Os.close(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Close error: ${e.message}")
        }
        activeFd = null
        activePfd = null

        Log.d(TAG, "Closed: ${totalBytesWritten.get()} bytes, " +
                "${totalWriteOps.get()} ops, ${droppedWrites.get()} dropped, " +
                "committed=${committedFileLength.get()}, " +
                "preallocation=${preallocationResult.method}:${preallocationResult.reservedBytes}")
    }

    /**
     * Signal the single writer thread to stop and wait for it to provably exit BEFORE the caller
     * releases the file descriptor. On the success path the drain has already terminated the writer
     * loop, so this returns almost immediately; on the drain-timeout path it interrupts the writer and
     * waits a hard bound so the writer cannot still be mid-Os.pwrite when the fd is closed.
     */
    private fun awaitWriterThreadTermination() {
        // Cannot join ourselves; if close() is (defensively) invoked on the writer thread the writer is
        // provably not inside Os.pwrite (it is here), so the fd is safe to close once the loop returns.
        if (Thread.currentThread().name == "DirectIO-Writer") {
            writerThread.shutdown()
            return
        }
        try {
            writerThread.shutdown()
            if (!writerThread.awaitTermination(AsyncDirectIODrainPolicy.DRAIN_POLL_MS * 10, TimeUnit.MILLISECONDS)) {
                // Writer still active after the drain window: interrupt it and wait a hard bound so it can
                // finish or unwind out of any in-flight pwrite before we release the fd.
                writerThread.shutdownNow()
                if (!writerThread.awaitTermination(AsyncDirectIODrainPolicy.DEFAULT_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "Writer thread still alive after hard timeout; closing fd anyway")
                }
            }
        } catch (e: InterruptedException) {
            writerThread.shutdownNow()
            Thread.currentThread().interrupt()
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
    val isOpen: Boolean get() = running.get()

    // ═════════════════════════════════════════════════════════════════════════════
    // Writer Loop (runs on dedicated high-priority thread)
    // ═════════════════════════════════════════════════════════════════════════════

    private fun writerLoop() {
        while (running.get() || writeQueue.isNotEmpty()) {
            val request = try {
                writeQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                continue
            } ?: continue

            activeWrites.incrementAndGet()
            try {
                val fd = activeFd ?: break
                val direct = useDirectIO && request.directEligible
                val written = try {
                    writeRequest(fd, request, direct = direct)
                } catch (directFailure: Exception) {
                    if (!direct) throw directFailure
                    Log.w(TAG, "O_DIRECT write failed; retrying same request with positioned standard I/O")
                    useDirectIO = false
                    writeRequest(fd, request, direct = false)
                }

                totalBytesWritten.addAndGet(written.toLong())
                totalWriteOps.incrementAndGet()
                val committed = committedFileLength.get()
                if (request.offset == committed) {
                    committedFileLength.set(request.offset + written.toLong())
                }
                request.callback?.invoke(true)
            } catch (e: Exception) {
                Log.e(TAG, "Write error at offset ${request.offset}: ${e.message}")
                request.callback?.invoke(false)

                // If O_DIRECT fails, disable it for remaining writes
                if (useDirectIO) {
                    Log.w(TAG, "Disabling O_DIRECT after write failure")
                    useDirectIO = false
                }
            } finally {
                releaseDirectBuffer(request.buffer)
                activeWrites.decrementAndGet()
            }
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

    private fun acquireDirectBuffer(minCapacity: Int): ByteBuffer {
        val requested = minCapacity.coerceAtLeast(1)
        while (true) {
            val pooled = bufferPool.poll() ?: break
            if (pooled.capacity() >= requested) {
                pooled.clear()
                return pooled
            }
        }
        return ByteBuffer.allocateDirect(requested)
    }

    private fun releaseDirectBuffer(buffer: ByteBuffer) {
        buffer.clear()
        bufferPool.offer(buffer)
    }

    private fun resetSessionCounters(directIO: Boolean) {
        useDirectIO = directIO
        currentFileOffset = 0L
        committedFileLength.set(0L)
        activeWrites.set(0)
        totalBytesWritten.set(0L)
        totalWriteOps.set(0L)
        droppedWrites.set(0L)
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

    private fun finalizeCommittedLength() {
        val fd = activeFd ?: return
        try {
            Os.ftruncate(fd, committedFileLength.get())
            Os.fsync(fd)
            preallocationResult = preallocationResult.copy(
                committedBytes = committedFileLength.get(),
                reservationTrimmed = preallocationResult.reservedBytes > committedFileLength.get(),
                synced = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to finalize RAW committed length: ${e.message}")
        }
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
        val buffer = request.buffer.duplicate()
        buffer.limit(request.length.coerceAtMost(buffer.limit()))
        var offset = request.offset
        var totalWritten = 0
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
}
