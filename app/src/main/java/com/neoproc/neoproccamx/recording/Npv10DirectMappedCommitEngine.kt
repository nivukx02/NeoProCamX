package com.neoproc.neoproccamx.recording

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

internal data class Npv10DirectMappedPreparedPayload(
    val encodedBytes: Int,
    val crc32: Int
)

/**
 * Bounded direct-mapped commit (DMC) owner for one NPV10 master.
 *
 * Each slot owns one fixed, guarded anonymous staging range that is allocated and pre-faulted once
 * during session setup. The NIV99 packer writes its packed ten-bit result into that warm range and
 * native code persists it to the exact physically preallocated frame offset with a complete
 * positional-write loop. There is no per-frame allocation, remap, packet object, or FileChannel
 * payload traversal on this route.
 *
 * Frame offsets are derived exclusively from [dataStartOffset], [frameHeaderBytes], [payloadBytes]
 * and the monotonic ingress index. The native state machine rejects slot reuse, stale frame indices,
 * writes beyond [reservedBytes], and commit-before-processing. A production caller must treat every
 * false native result as fatal. Large lossless packets use a capability-proven aligned direct
 * writer when supported; the unchanged buffered backend is selected only before capture when that
 * capability is unsupported. Runtime write errors never switch backends or weaken the master.
 */
internal class Npv10DirectMappedCommitEngine private constructor(
    private val nativeHandle: AtomicLong,
    val dataStartOffset: Long,
    val frameHeaderBytes: Int,
    /** Canonical packed-10 bytes written by the fused Camera2/native packer. */
    val payloadBytes: Int,
    /** Guarded slot capacity and maximum possible on-disk packet bytes. */
    val payloadCapacityBytes: Int,
    val sampleCount: Int,
    val chunkedLosslessCompression: Boolean,
    val slotCount: Int
) : Closeable {
    private val framesMapped = AtomicLong(0L)
    private val framesCommitted = AtomicLong(0L)
    private val framesAborted = AtomicLong(0L)
    private val mappingFailures = AtomicLong(0L)
    private val commitFailures = AtomicLong(0L)
    private val directMappedPayloadBytes = AtomicLong(0L)
    private val totalMapTimeNs = AtomicLong(0L)
    private val maxMapTimeNs = AtomicLong(0L)
    private val totalCommitTimeNs = AtomicLong(0L)
    private val maxCommitTimeNs = AtomicLong(0L)
    private val latestReservedBytes = AtomicLong(0L)

    val frameRecordBytes: Long =
        Math.addExact(frameHeaderBytes.toLong(), payloadCapacityBytes.toLong())

    fun beginFrame(frameIndex: Long, reservedBytes: Long) {
        require(frameIndex >= 0L) { "DMC frame index must be non-negative: $frameIndex" }
        require(reservedBytes > 0L) { "DMC physical reservation must be positive" }
        val handle = requireOpenHandle()
        val startedNs = System.nanoTime()
        if (!Npv10DirectMappedCommitNative.nativeBeginFrame(handle, frameIndex, reservedBytes)) {
            mappingFailures.incrementAndGet()
            throw nativeFailure("claim frame $frameIndex")
        }
        latestReservedBytes.accumulateAndGet(reservedBytes, ::maxOf)
        val elapsedNs = elapsedNs(startedNs)
        framesMapped.incrementAndGet()
        totalMapTimeNs.addAndGet(elapsedNs)
        updateMaximum(maxMapTimeNs, elapsedNs)
    }

    /** Opaque handle used only by the native fused NIV99 packer while this owner remains open. */
    fun processingHandle(): Long = requireOpenHandle()

    fun commitFrame(
        frameIndex: Long,
        encodedBytes: Int,
        payloadFileOffset: Long,
        reservedBytes: Long,
        crc32: Int,
        timestampNs: Long = frameIndex + 1L,
        width: Int = 1,
        height: Int = 1
    ) {
        require(frameIndex >= 0L) { "DMC frame index must be non-negative: $frameIndex" }
        require(encodedBytes in 1..payloadCapacityBytes) {
            "DMC encoded payload is outside slot capacity: $encodedBytes/$payloadCapacityBytes"
        }
        val handle = requireOpenHandle()
        val startedNs = System.nanoTime()
        if (!Npv10DirectMappedCommitNative.nativeCommitFrame(
                handle,
                frameIndex,
                encodedBytes,
                payloadFileOffset,
                reservedBytes,
                crc32.toLong() and UINT_MASK,
                timestampNs,
                width,
                height
            )
        ) {
            commitFailures.incrementAndGet()
            throw nativeFailure("commit frame $frameIndex")
        }
        val elapsedNs = elapsedNs(startedNs)
        framesCommitted.incrementAndGet()
        directMappedPayloadBytes.addAndGet(encodedBytes.toLong())
        totalCommitTimeNs.addAndGet(elapsedNs)
        updateMaximum(maxCommitTimeNs, elapsedNs)
    }

    /** Compatibility helper for fixed-size packet tests and the legacy packed-P010 codec. */
    fun commitFrame(frameIndex: Long, crc32: Int) {
        check(!chunkedLosslessCompression) {
            "Variable-size chunked packets require an explicit encoded size and file offset"
        }
        val payloadFileOffset = Math.addExact(
            dataStartOffset,
            Math.addExact(
                Math.multiplyExact(frameIndex, frameRecordBytes),
                frameHeaderBytes.toLong()
            )
        )
        commitFrame(
            frameIndex = frameIndex,
            encodedBytes = payloadBytes,
            payloadFileOffset = payloadFileOffset,
            reservedBytes = latestReservedBytes.get(),
            crc32 = crc32
        )
    }

    /** Idempotently retires an uncommitted mapping. A stale/live-slot mismatch remains a hard error. */
    fun abortFrame(frameIndex: Long) {
        if (frameIndex < 0L) return
        val handle = nativeHandle.get()
        if (handle == 0L) return
        if (!Npv10DirectMappedCommitNative.nativeAbortFrame(handle, frameIndex)) {
            throw nativeFailure("abort frame $frameIndex")
        }
        framesAborted.incrementAndGet()
    }

    /**
     * Waits until every asynchronously submitted record through [requiredFrames] is physically
     * written through the selected buffered or aligned-direct backend. This is not a durability
     * acknowledgement. Durability callers must cross this boundary before
     * force(), truncate(), hashing, publication, or descriptor close can observe the master.
     */
    fun awaitCommittedFrames(requiredFrames: Long) {
        require(requiredFrames >= 0L) { "DMC drain frame count must be non-negative" }
        val submittedFrames = framesCommitted.get()
        require(requiredFrames <= submittedFrames) {
            "DMC cannot drain $requiredFrames frames after only $submittedFrames submissions"
        }
        val handle = requireOpenHandle()
        if (!Npv10DirectMappedCommitNative.nativeAwaitCommittedFrames(handle, requiredFrames)) {
            commitFailures.incrementAndGet()
            throw nativeFailure("drain through frame $requiredFrames")
        }
    }

    /**
     * Retains the original fdatasync guarantee, but excludes simultaneous writes on this master.
     * Native descriptor ownership also makes a concurrent close wait for an admitted force.
     * This never acknowledges an unsubmitted frame or changes the record-write timing boundary.
     */
    fun forceCommittedFrames(requiredFrames: Long) {
        require(requiredFrames >= 0L && requiredFrames <= framesCommitted.get()) {
            "DMC durability boundary must refer to submitted frames"
        }
        val handle = requireOpenHandle()
        if (!Npv10DirectMappedCommitNative.nativeForceCommittedFrames(handle, requiredFrames)) {
            throw nativeFailure("force through frame $requiredFrames")
        }
    }

    /**
     * Physically reserves one contiguous live slice under the same native I/O boundary as
     * complete record writes and fdatasync. Success proves allocation, NOT frame durability.
     * The caller retains its low-space safety reserve and publishes the returned prefix only.
     */
    fun allocatePhysicalRange(offsetBytes: Long, lengthBytes: Long) {
        require(offsetBytes >= 0L && lengthBytes in 1L..Npv10RollingExtentAllocator.LIVE_SLICE_BYTES) {
            "DMC physical allocation must be one bounded live slice"
        }
        Math.addExact(offsetBytes, lengthBytes)
        val handle = requireOpenHandle()
        if (!Npv10DirectMappedCommitNative.nativeAllocatePhysicalRange(handle, offsetBytes, lengthBytes)) {
            throw nativeFailure("allocate physical slice at $offsetBytes of $lengthBytes bytes")
        }
    }

    fun snapshot(): Npv10DirectMappedCommitTelemetry {
        val mapped = framesMapped.get()
        val committed = framesCommitted.get()
        val handle = nativeHandle.get()
        val writeback = if (handle != 0L) {
            runCatching {
                Npv10DirectMappedCommitNative.nativeIncrementalWritebackTelemetry(handle)
            }.getOrNull()?.takeIf { it.size >= WRITEBACK_TELEMETRY_VALUE_COUNT }
        } else {
            null
        }
        val writebackAttempts = writeback?.get(1) ?: 0L
        return Npv10DirectMappedCommitTelemetry(
            enabled = handle != 0L,
            slotCount = slotCount,
            payloadBytesPerFrame = payloadBytes,
            framesMapped = mapped,
            framesCommitted = committed,
            framesAborted = framesAborted.get(),
            mappingFailures = mappingFailures.get(),
            commitFailures = commitFailures.get(),
            directMappedPayloadBytes = directMappedPayloadBytes.get(),
            averageMapMs = averageMs(totalMapTimeNs.get(), mapped),
            maxMapMs = nanosToMs(maxMapTimeNs.get()),
            averageCommitMs = averageMs(totalCommitTimeNs.get(), committed),
            maxCommitMs = nanosToMs(maxCommitTimeNs.get()),
            incrementalWritebackEnabled = writeback?.get(0) == 1L,
            incrementalWritebackAttempts = writebackAttempts,
            incrementalWritebackSuccesses = writeback?.get(2) ?: 0L,
            incrementalWritebackFailures = writeback?.get(3) ?: 0L,
            incrementalWritebackBytes = writeback?.get(4) ?: 0L,
            averageIncrementalWritebackMs = averageMs(
                writeback?.get(5) ?: 0L,
                writebackAttempts
            ),
            maxIncrementalWritebackMs = nanosToMs(writeback?.get(6) ?: 0L),
            incrementalWritebackCoveredThroughOffset = writeback?.get(7) ?: dataStartOffset,
            incrementalWritebackLastErrno = writeback?.get(8)?.toInt() ?: 0,
            incrementalWritebackSuppressedForRealtime = writeback?.get(9) == 1L,
            asynchronousRecordWriterEnabled = writeback?.get(10) == 1L,
            asynchronouslyPersistedFrames = writeback?.get(11) ?: 0L,
            asynchronousRecordWriteFailures = writeback?.get(12) ?: 0L,
            averageAsynchronousRecordWriteMs = averageMs(
                writeback?.get(13) ?: 0L,
                writeback?.get(11) ?: 0L
            ),
            maxAsynchronousRecordWriteMs = nanosToMs(writeback?.get(14) ?: 0L),
            maximumRecordQueueDepth = writeback?.get(15) ?: 0L,
            pendingRecordQueueDepth = writeback?.get(16) ?: 0L,
            asynchronouslySubmittedFrames = writeback?.get(17) ?: 0L,
            maximumIncrementalWritebackRequestBytes = writeback?.getOrNull(18) ?: 0L,
            maximumIncrementalWritebackPendingBytes = writeback?.getOrNull(19) ?: 0L,
            asynchronouslyPersistedRecordBytes = writeback?.getOrNull(20) ?: 0L,
            incrementalWritebackTargetBytes = writeback?.getOrNull(21) ?: 0L,
            alignedRecordWriterAttempted = writeback?.getOrNull(22) == 1L,
            alignedRecordWriterEnabled = writeback?.getOrNull(23) == 1L,
            alignedRecordWriterAdmissionErrno = writeback?.getOrNull(24)?.toInt() ?: 0,
            alignedRecordWriterAlignmentBytes = writeback?.getOrNull(25) ?: 0L,
            alignedRecordWriterCalls = writeback?.getOrNull(26) ?: 0L,
            alignedRecordWriterPhysicalBytes = writeback?.getOrNull(27) ?: 0L,
            alignedRecordWriterPaddingBytes = writeback?.getOrNull(28) ?: 0L,
            alignedRecordWriterFailures = writeback?.getOrNull(29) ?: 0L,
            alignedRecordWriterStagingBytes = writeback?.getOrNull(30) ?: 0L,
            alignedRecordWriterProbeVerified = writeback?.getOrNull(31) == 1L,
            losslessStoragePressure = Npv10LosslessStoragePressureTelemetry.fromNative(writeback),
            kernelAio = Npv10KernelAioTelemetry.fromNative(writeback)
        )
    }

    override fun close() {
        val handle = nativeHandle.getAndSet(0L)
        if (handle == 0L) return
        if (!Npv10DirectMappedCommitNative.nativeClose(handle)) {
            throw nativeFailure("close")
        }
    }

    private fun requireOpenHandle(): Long = nativeHandle.get().also { handle ->
        check(handle != 0L) { "NPV10 direct-mapped commit engine is closed" }
    }

    private fun nativeFailure(operation: String): IOException {
        val detail = Npv10DirectMappedCommitNative.nativeLastError().ifBlank { "unknown native failure" }
        return IOException("NPV10 direct-mapped commit could not $operation: $detail")
    }

    private fun elapsedNs(startedNs: Long): Long =
        (System.nanoTime() - startedNs).coerceAtLeast(0L)

    private fun averageMs(totalNs: Long, samples: Long): Double =
        if (samples <= 0L) 0.0 else totalNs.toDouble() / samples.toDouble() / 1_000_000.0

    private fun nanosToMs(nanos: Long): Double = nanos.toDouble() / 1_000_000.0

    private fun updateMaximum(target: AtomicLong, candidate: Long) {
        var observed = target.get()
        while (candidate > observed && !target.compareAndSet(observed, candidate)) {
            observed = target.get()
        }
    }

    companion object {
        private const val UINT_MASK = 0xFFFF_FFFFL
        private const val WRITEBACK_TELEMETRY_VALUE_COUNT = 18

        fun open(
            file: File,
            dataStartOffset: Long,
            frameHeaderBytes: Int,
            payloadBytes: Int,
            payloadCapacityBytes: Int = payloadBytes,
            sampleCount: Int = ((payloadBytes.toLong() * 8L) / 10L).toInt().coerceAtLeast(1),
            chunkedLosslessCompression: Boolean = false,
            slotCount: Int,
            reservedBytes: Long
        ): Npv10DirectMappedCommitEngine {
            require(file.isFile) { "DMC master does not exist: ${file.absolutePath}" }
            require(dataStartOffset >= 0L) { "DMC data start must be non-negative" }
            require(frameHeaderBytes > 0) { "DMC frame header size must be positive" }
            require(payloadBytes > 0) { "DMC payload size must be positive" }
            require(payloadCapacityBytes >= payloadBytes) {
                "DMC payload capacity must cover its packed input"
            }
            require(sampleCount > 0) { "DMC sample count must be positive" }
            require(slotCount > 0) { "DMC slot count must be positive" }
            require(reservedBytes >= dataStartOffset) {
                "DMC reservation ends before its data start"
            }
            check(Npv10DirectMappedCommitNative.libraryAvailable) {
                "NPV10 direct-mapped commit native library is unavailable"
            }
            val handle = Npv10DirectMappedCommitNative.nativeCreate(
                path = file.absolutePath,
                dataStartOffset = dataStartOffset,
                frameHeaderBytes = frameHeaderBytes,
                inputPayloadBytes = payloadBytes,
                payloadCapacityBytes = payloadCapacityBytes,
                sampleCount = sampleCount,
                chunkedLosslessCompression = chunkedLosslessCompression,
                slotCount = slotCount,
                reservedBytes = reservedBytes
            )
            if (handle == 0L) {
                val detail = Npv10DirectMappedCommitNative.nativeLastError()
                    .ifBlank { "unknown native initialization failure" }
                throw IOException("NPV10 direct-mapped commit initialization failed: $detail")
            }
            return Npv10DirectMappedCommitEngine(
                nativeHandle = AtomicLong(handle),
                dataStartOffset = dataStartOffset,
                frameHeaderBytes = frameHeaderBytes,
                payloadBytes = payloadBytes,
                payloadCapacityBytes = payloadCapacityBytes,
                sampleCount = sampleCount,
                chunkedLosslessCompression = chunkedLosslessCompression,
                slotCount = slotCount
            )
        }
    }
}

internal data class Npv10DirectMappedCommitTelemetry(
    val enabled: Boolean = false,
    val slotCount: Int = 0,
    val payloadBytesPerFrame: Int = 0,
    val framesMapped: Long = 0L,
    val framesCommitted: Long = 0L,
    val framesAborted: Long = 0L,
    val mappingFailures: Long = 0L,
    val commitFailures: Long = 0L,
    val directMappedPayloadBytes: Long = 0L,
    val averageMapMs: Double = 0.0,
    val maxMapMs: Double = 0.0,
    val averageCommitMs: Double = 0.0,
    val maxCommitMs: Double = 0.0,
    val incrementalWritebackEnabled: Boolean = false,
    val incrementalWritebackAttempts: Long = 0L,
    val incrementalWritebackSuccesses: Long = 0L,
    val incrementalWritebackFailures: Long = 0L,
    val incrementalWritebackBytes: Long = 0L,
    val averageIncrementalWritebackMs: Double = 0.0,
    val maxIncrementalWritebackMs: Double = 0.0,
    val incrementalWritebackCoveredThroughOffset: Long = 0L,
    val incrementalWritebackLastErrno: Int = 0,
    val incrementalWritebackSuppressedForRealtime: Boolean = false,
    val asynchronousRecordWriterEnabled: Boolean = false,
    val asynchronouslyPersistedFrames: Long = 0L,
    val asynchronousRecordWriteFailures: Long = 0L,
    val averageAsynchronousRecordWriteMs: Double = 0.0,
    val maxAsynchronousRecordWriteMs: Double = 0.0,
    val maximumRecordQueueDepth: Long = 0L,
    val pendingRecordQueueDepth: Long = 0L,
    val asynchronouslySubmittedFrames: Long = 0L,
    val maximumIncrementalWritebackRequestBytes: Long = 0L,
    val maximumIncrementalWritebackPendingBytes: Long = 0L,
    val asynchronouslyPersistedRecordBytes: Long = 0L,
    val incrementalWritebackTargetBytes: Long = 0L,
    val alignedRecordWriterAttempted: Boolean = false,
    val alignedRecordWriterEnabled: Boolean = false,
    val alignedRecordWriterAdmissionErrno: Int = 0,
    val alignedRecordWriterAlignmentBytes: Long = 0L,
    val alignedRecordWriterCalls: Long = 0L,
    val alignedRecordWriterPhysicalBytes: Long = 0L,
    val alignedRecordWriterPaddingBytes: Long = 0L,
    val alignedRecordWriterFailures: Long = 0L,
    val alignedRecordWriterStagingBytes: Long = 0L,
    val alignedRecordWriterProbeVerified: Boolean = false,
    val kernelAio: Npv10KernelAioTelemetry = Npv10KernelAioTelemetry(),
    val losslessStoragePressure: Npv10LosslessStoragePressureTelemetry =
        Npv10LosslessStoragePressureTelemetry()
)

internal object Npv10DirectMappedCommitNative {
    val libraryAvailable: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            System.loadLibrary("neoproc_vulkan")
            true
        }.getOrDefault(false)
    }

    @JvmStatic
    external fun nativeCreate(
        path: String,
        dataStartOffset: Long,
        frameHeaderBytes: Int,
        inputPayloadBytes: Int,
        payloadCapacityBytes: Int,
        sampleCount: Int,
        chunkedLosslessCompression: Boolean,
        slotCount: Int,
        reservedBytes: Long
    ): Long

    @JvmStatic
    external fun nativeBeginFrame(handle: Long, frameIndex: Long, reservedBytes: Long): Boolean

    @JvmStatic
    external fun nativeCommitFrame(
        handle: Long,
        frameIndex: Long,
        encodedBytes: Int,
        payloadFileOffset: Long,
        reservedBytes: Long,
        crc32: Long,
        timestampNs: Long,
        width: Int,
        height: Int
    ): Boolean

    @JvmStatic
    external fun nativeAbortFrame(handle: Long, frameIndex: Long): Boolean

    @JvmStatic
    external fun nativeAwaitCommittedFrames(handle: Long, requiredFrames: Long): Boolean

    @JvmStatic
    external fun nativeForceCommittedFrames(handle: Long, requiredFrames: Long): Boolean

    @JvmStatic
    external fun nativeAllocatePhysicalRange(handle: Long, offsetBytes: Long, lengthBytes: Long): Boolean

    @JvmStatic
    external fun nativeIncrementalWritebackTelemetry(handle: Long): LongArray?

    @JvmStatic
    external fun nativeClose(handle: Long): Boolean

    @JvmStatic
    external fun nativeLastError(): String
}
