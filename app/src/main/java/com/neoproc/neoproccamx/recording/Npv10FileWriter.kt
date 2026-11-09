package com.neoproc.neoproccamx.recording

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.media.Image
import android.os.Trace
import android.system.Os
import android.util.Log
import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import kotlin.math.abs

data class Npv10CopiedP010Frame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val payload: ByteBuffer,
    val payloadBytes: Int,
    val copyPath: Npv10FramePacker.P010CopyPath
)

/** Monotonic stop-stage timings for the durable NPV10 master seal. */
data class Npv10FileFinalizationTimings(
    val truncateMs: Double = 0.0,
    val durableSyncMs: Double = 0.0,
    val descriptorCloseMs: Double = 0.0,
    val totalMs: Double = 0.0,
    val committedBytes: Long = 0L,
    val reservedBytes: Long = 0L
)

/** Cache/CPU/storage stage evidence for the exact packet writer lane. */
data class Npv10WriterStageTimings(
    val frames: Long = 0L,
    val nativeFusedCrcFrames: Long = 0L,
    val preparedPacketFrames: Long = 0L,
    val averageEncodeAndCrcMs: Double = 0.0,
    val maxEncodeAndCrcMs: Double = 0.0,
    val averageFallbackCrcMs: Double = 0.0,
    val maxFallbackCrcMs: Double = 0.0,
    val averageFileWriteMs: Double = 0.0,
    val maxFileWriteMs: Double = 0.0,
    val averagePreparedProcessingMs: Double = 0.0,
    val maxPreparedProcessingMs: Double = 0.0,
    val directMappedCommitFrames: Long = 0L,
    val directMappedPayloadBytes: Long = 0L,
    val averageDirectMapMs: Double = 0.0,
    val maxDirectMapMs: Double = 0.0,
    val averageDirectCommitMs: Double = 0.0,
    val maxDirectCommitMs: Double = 0.0,
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
    val asynchronouslySubmittedFrames: Long = 0L,
    val asynchronouslyPersistedFrames: Long = 0L,
    val asynchronousRecordWriteFailures: Long = 0L,
    val averageAsynchronousRecordWriteMs: Double = 0.0,
    val maxAsynchronousRecordWriteMs: Double = 0.0,
    val maximumRecordQueueDepth: Long = 0L,
    val pendingRecordQueueDepth: Long = 0L,
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
) {
    /**
     * Stable recording-certificate keys for the exact hot-path stages. Keeping this mapping next
     * to the telemetry model prevents log-only diagnostics from being mistaken for durable proof.
     */
    fun toEvidenceMetadata(): Map<String, String> = linkedMapOf(
        "npv10WriterStageFrames" to frames.toString(),
        "npv10WriterStageNativeFusedCrcFrames" to nativeFusedCrcFrames.toString(),
        "npv10WriterStagePreparedPacketFrames" to preparedPacketFrames.toString(),
        "npv10WriterStageAverageEncodeAndCrcMs" to averageEncodeAndCrcMs.toString(),
        "npv10WriterStageMaxEncodeAndCrcMs" to maxEncodeAndCrcMs.toString(),
        "npv10WriterStageAverageFallbackCrcMs" to averageFallbackCrcMs.toString(),
        "npv10WriterStageMaxFallbackCrcMs" to maxFallbackCrcMs.toString(),
        "npv10WriterStageAverageFileWriteMs" to averageFileWriteMs.toString(),
        "npv10WriterStageMaxFileWriteMs" to maxFileWriteMs.toString(),
        "npv10WriterStageAveragePreparedProcessingMs" to averagePreparedProcessingMs.toString(),
        "npv10WriterStageMaxPreparedProcessingMs" to maxPreparedProcessingMs.toString(),
        "npv10DmcCommitFrames" to directMappedCommitFrames.toString(),
        "npv10DmcPayloadBytes" to directMappedPayloadBytes.toString(),
        "npv10DmcAverageMapMs" to averageDirectMapMs.toString(),
        "npv10DmcMaxMapMs" to maxDirectMapMs.toString(),
        "npv10DmcAverageCommitMs" to averageDirectCommitMs.toString(),
        "npv10DmcMaxCommitMs" to maxDirectCommitMs.toString(),
        "npv10DmcIncrementalWritebackEnabled" to incrementalWritebackEnabled.toString(),
        "npv10DmcIncrementalWritebackAttempts" to incrementalWritebackAttempts.toString(),
        "npv10DmcIncrementalWritebackSuccesses" to incrementalWritebackSuccesses.toString(),
        "npv10DmcIncrementalWritebackFailures" to incrementalWritebackFailures.toString(),
        "npv10DmcIncrementalWritebackBytes" to incrementalWritebackBytes.toString(),
        "npv10DmcAverageIncrementalWritebackMs" to averageIncrementalWritebackMs.toString(),
        "npv10DmcMaxIncrementalWritebackMs" to maxIncrementalWritebackMs.toString(),
        "npv10DmcIncrementalWritebackCoveredThroughOffset" to
            incrementalWritebackCoveredThroughOffset.toString(),
        "npv10DmcIncrementalWritebackLastErrno" to incrementalWritebackLastErrno.toString(),
        "npv10DmcIncrementalWritebackSuppressedForRealtime" to
            incrementalWritebackSuppressedForRealtime.toString(),
        "npv10DmcAsyncRecordWriterEnabled" to asynchronousRecordWriterEnabled.toString(),
        "npv10DmcAsyncSubmittedFrames" to asynchronouslySubmittedFrames.toString(),
        "npv10DmcAsyncPersistedFrames" to asynchronouslyPersistedFrames.toString(),
        "npv10DmcAsyncRecordWriteFailures" to asynchronousRecordWriteFailures.toString(),
        "npv10DmcAverageAsyncRecordWriteMs" to averageAsynchronousRecordWriteMs.toString(),
        "npv10DmcMaxAsyncRecordWriteMs" to maxAsynchronousRecordWriteMs.toString(),
        "npv10DmcMaximumRecordQueueDepth" to maximumRecordQueueDepth.toString(),
        "npv10DmcPendingRecordQueueDepth" to pendingRecordQueueDepth.toString(),
        "npv10DmcMaxIncrementalWritebackRequestBytes" to maximumIncrementalWritebackRequestBytes.toString(),
        "npv10DmcMaxIncrementalWritebackPendingBytes" to maximumIncrementalWritebackPendingBytes.toString(),
        "npv10DmcAsyncPersistedRecordBytes" to asynchronouslyPersistedRecordBytes.toString(),
        "npv10DmcIncrementalWritebackTargetBytes" to incrementalWritebackTargetBytes.toString(),
        "npv10DmcAlignedWriterAttempted" to alignedRecordWriterAttempted.toString(),
        "npv10DmcAlignedWriterEnabled" to alignedRecordWriterEnabled.toString(),
        "npv10DmcAlignedWriterAdmissionErrno" to alignedRecordWriterAdmissionErrno.toString(),
        "npv10DmcAlignedWriterAlignmentBytes" to alignedRecordWriterAlignmentBytes.toString(),
        "npv10DmcAlignedWriterCalls" to alignedRecordWriterCalls.toString(),
        "npv10DmcAlignedWriterPhysicalBytes" to alignedRecordWriterPhysicalBytes.toString(),
        "npv10DmcAlignedWriterPaddingBytes" to alignedRecordWriterPaddingBytes.toString(),
        "npv10DmcAlignedWriterFailures" to alignedRecordWriterFailures.toString(),
        "npv10DmcAlignedWriterStagingBytes" to alignedRecordWriterStagingBytes.toString(),
        "npv10DmcAlignedWriterProbeVerified" to alignedRecordWriterProbeVerified.toString()
    ).apply {
        putAll(losslessStoragePressure.toEvidenceMetadata())
        putAll(kernelAio.toMetadata())
    }
}

/** Periodic fsync evidence; reservation growth shares this serialized maintenance lane. */
data class Npv10DurabilityCheckpointTimings(
    val checkpoints: Long = 0L,
    val averageCheckpointMs: Double = 0.0,
    val maxCheckpointMs: Double = 0.0,
    val queuedBehindReservationGrowth: Long = 0L,
    val coalescedRequests: Long = 0L,
    val completedThroughFrame: Long = 0L,
    val dedicatedDescriptor: Boolean = false
)

/** Exact frame-1 boundary acknowledged only after FileChannel.force(false) succeeds. */
internal data class Npv10FirstDurableFrameProof(
    val sensorTimestampNs: Long,
    val frameCount: Long,
    val committedBytes: Long
)

class Npv10FileWriter(
    private val outputFile: File,
    private val config: Npv10SessionConfig,
    availableStorageBytes: Long = 0L,
    preparedPreallocation: RecordingPreallocationResult? = null
) : Closeable {
    private val randomAccessFile: RandomAccessFile
    private val channel: FileChannel
    private val durabilityRandomAccessFile: RandomAccessFile
    private val durabilityChannel: FileChannel
    private val directMappedAdmissionFence = Npv10WriterAdmissionFence()
    @Volatile
    private var directMappedCommitEngine: Npv10DirectMappedCommitEngine? = null
    @Volatile
    private var finalDirectMappedCommitTelemetry = Npv10DirectMappedCommitTelemetry()
    private val reservationProfile = Npv10PhysicalReservationPolicy.profile(config)
    private val durabilityExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "npv10-durability-${outputFile.name.hashCode().toUInt()}").apply {
            isDaemon = true
        }
    }
    private val reservationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "npv10-reservation-${outputFile.name.hashCode().toUInt()}").apply {
            isDaemon = true
        }
    }
    @Volatile
    var preallocationResult: RecordingPreallocationResult
        private set
    private var closed = false
    @Volatile
    var frameCount: Long = 0L
        private set
    var firstFrameTimestampNs: Long = -1L
        private set
    var firstFrameWidth: Int = 0
        private set
    var firstFrameHeight: Int = 0
        private set
    var firstFrameBitDepth: Int = 0
        private set
    var payloadBytesWritten: Long = 0L
        private set
    var encodedPayloadBytesWritten: Long = 0L
        private set
    private val firstDurableFrameProof = CompletableFuture<Npv10FirstDurableFrameProof>()
    @Volatile
    var committedBytes: Long = 0L
        private set
    private var totalWriteTimeNs: Long = 0L
    private var maxWriteTimeNs: Long = 0L
    private var stageFrameCount: Long = 0L
    private var nativeFusedCrcFrameCount: Long = 0L
    private var totalEncodeAndCrcTimeNs: Long = 0L
    private var maxEncodeAndCrcTimeNs: Long = 0L
    private var totalFallbackCrcTimeNs: Long = 0L
    private var maxFallbackCrcTimeNs: Long = 0L
    private var totalFileWriteTimeNs: Long = 0L
    private var maxFileWriteTimeNs: Long = 0L
    private var preparedPacketFrameCount: Long = 0L
    private var totalPreparedProcessingTimeNs: Long = 0L
    private var maxPreparedProcessingTimeNs: Long = 0L
    private var lastFrameTimestampNs: Long = -1L
    /** Last durable frame across every cadence segment; intentional pauses never reset lineage. */
    private var lastCommittedFrameTimestampNs: Long = -1L
    private var totalTimestampJitterNs: Long = 0L
    private var timestampJitterSamples: Long = 0L
    private var lastCadenceIntervalEndTimestampNs: Long = -1L
    private var lastCadenceIntervalMissingFrames: Long = 0L
    private var lastCadenceIntervalJitterNs: Long = 0L
    private var lastCadenceIntervalHasJitterSample: Boolean = false
    private var maxTimestampJitterBeforeLastIntervalNs: Long = 0L
    private var terminalCadenceBoundaryTimestampNs: Long = -1L
    var inferredDroppedFrameCount: Long = 0L
        private set
    var maxTimestampJitterNs: Long = 0L
        private set
    private val frameHeaderBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(Npv10ContainerPolicy.FRAME_MAGIC_BYTES.size + Long.SIZE_BYTES + Int.SIZE_BYTES * 4)
        .order(ByteOrder.BIG_ENDIAN)
    private var reusableDirectP010Payload: ByteBuffer? = null
    private var reusableEncodedPacket: ByteBuffer? = null
    @Volatile
    private var pendingDurabilityCheckpoint: Future<*>? = null
    @Volatile
    private var durabilityCheckpointFailure: Throwable? = null
    private val durabilityStateLock = Any()
    private var durabilityWorkerRunning = false
    private var durabilityRequestedThroughFrame = 0L
    private var durabilityCompletedThroughFrame = 0L
    private var firstFrameDurabilityCandidate: Npv10FirstDurableFrameProof? = null
    private var lastDurabilityCheckpointFrame: Long = 0L
    @Volatile
    private var pendingReservationGrowth: Future<*>? = null
    @Volatile
    private var reservationGrowthFailure: Throwable? = null
    @Volatile
    private var physicalReservedBytes: Long = 0L
    @Volatile
    private var initialPhysicalReservedBytes: Long = 0L
    @Volatile
    private var reservationEnforced: Boolean = false
    private val reservationExtensionAttempts = AtomicLong(0L)
    private val reservationExtensionSuccesses = AtomicLong(0L)
    private val reservationExtensionFailures = AtomicLong(0L)
    private val reservationAllocationSlices = AtomicLong(0L)
    private val reservationAllocationMaxSliceNs = AtomicLong(0L)
    private val reservationExtendedBytes = AtomicLong(0L)
    private val minimumReservationHeadroomBytes = AtomicLong(Long.MAX_VALUE)
    private val unreservedWriteRefusals = AtomicLong(0L)
    private val durabilityCheckpointCount = AtomicLong(0L)
    private val durabilityCheckpointTotalNs = AtomicLong(0L)
    private val durabilityCheckpointMaxNs = AtomicLong(0L)
    private val durabilityStageTelemetry = Npv10DurabilityStageTelemetry()
    private val checkpointsQueuedBehindReservationGrowth = AtomicLong(0L)
    private val coalescedDurabilityCheckpointRequests = AtomicLong(0L)
    @Volatile
    var lastFinalizationTimings: Npv10FileFinalizationTimings = Npv10FileFinalizationTimings()
        private set

    val averageWriteTimeMs: Double
        get() = if (frameCount <= 0L) 0.0 else totalWriteTimeNs.toDouble() / frameCount.toDouble() / 1_000_000.0
    val maxWriteTimeMs: Double
        get() = maxWriteTimeNs.toDouble() / 1_000_000.0
    val averageTimestampJitterNs: Long
        get() = if (timestampJitterSamples <= 0L) 0L else totalTimestampJitterNs / timestampJitterSamples
    val writerStageTimings: Npv10WriterStageTimings
        get() {
            val dmc = directMappedCommitEngine?.snapshot() ?: finalDirectMappedCommitTelemetry
            return Npv10WriterStageTimings(
            frames = stageFrameCount,
            nativeFusedCrcFrames = nativeFusedCrcFrameCount,
            preparedPacketFrames = preparedPacketFrameCount,
            averageEncodeAndCrcMs = averageMs(totalEncodeAndCrcTimeNs, stageFrameCount),
            maxEncodeAndCrcMs = nanosToMs(maxEncodeAndCrcTimeNs),
            averageFallbackCrcMs = averageMs(totalFallbackCrcTimeNs, stageFrameCount),
            maxFallbackCrcMs = nanosToMs(maxFallbackCrcTimeNs),
            averageFileWriteMs = averageMs(totalFileWriteTimeNs, stageFrameCount),
            maxFileWriteMs = nanosToMs(maxFileWriteTimeNs),
            averagePreparedProcessingMs = averageMs(
                totalPreparedProcessingTimeNs,
                preparedPacketFrameCount
            ),
            maxPreparedProcessingMs = nanosToMs(maxPreparedProcessingTimeNs),
            directMappedCommitFrames = dmc.framesCommitted,
            directMappedPayloadBytes = dmc.directMappedPayloadBytes,
            averageDirectMapMs = dmc.averageMapMs,
            maxDirectMapMs = dmc.maxMapMs,
            averageDirectCommitMs = dmc.averageCommitMs,
            maxDirectCommitMs = dmc.maxCommitMs,
            incrementalWritebackEnabled = dmc.incrementalWritebackEnabled,
            incrementalWritebackAttempts = dmc.incrementalWritebackAttempts,
            incrementalWritebackSuccesses = dmc.incrementalWritebackSuccesses,
            incrementalWritebackFailures = dmc.incrementalWritebackFailures,
            incrementalWritebackBytes = dmc.incrementalWritebackBytes,
            averageIncrementalWritebackMs = dmc.averageIncrementalWritebackMs,
            maxIncrementalWritebackMs = dmc.maxIncrementalWritebackMs,
            incrementalWritebackCoveredThroughOffset =
                dmc.incrementalWritebackCoveredThroughOffset,
            incrementalWritebackLastErrno = dmc.incrementalWritebackLastErrno,
            incrementalWritebackSuppressedForRealtime =
                dmc.incrementalWritebackSuppressedForRealtime,
            asynchronousRecordWriterEnabled = dmc.asynchronousRecordWriterEnabled,
            asynchronouslySubmittedFrames = dmc.asynchronouslySubmittedFrames,
            asynchronouslyPersistedFrames = dmc.asynchronouslyPersistedFrames,
            asynchronousRecordWriteFailures = dmc.asynchronousRecordWriteFailures,
            averageAsynchronousRecordWriteMs = dmc.averageAsynchronousRecordWriteMs,
            maxAsynchronousRecordWriteMs = dmc.maxAsynchronousRecordWriteMs,
            maximumRecordQueueDepth = dmc.maximumRecordQueueDepth,
            pendingRecordQueueDepth = dmc.pendingRecordQueueDepth,
            maximumIncrementalWritebackRequestBytes = dmc.maximumIncrementalWritebackRequestBytes,
            maximumIncrementalWritebackPendingBytes = dmc.maximumIncrementalWritebackPendingBytes,
            asynchronouslyPersistedRecordBytes = dmc.asynchronouslyPersistedRecordBytes,
            incrementalWritebackTargetBytes = dmc.incrementalWritebackTargetBytes,
            alignedRecordWriterAttempted = dmc.alignedRecordWriterAttempted,
            alignedRecordWriterEnabled = dmc.alignedRecordWriterEnabled,
            alignedRecordWriterAdmissionErrno = dmc.alignedRecordWriterAdmissionErrno,
            alignedRecordWriterAlignmentBytes = dmc.alignedRecordWriterAlignmentBytes,
            alignedRecordWriterCalls = dmc.alignedRecordWriterCalls,
            alignedRecordWriterPhysicalBytes = dmc.alignedRecordWriterPhysicalBytes,
            alignedRecordWriterPaddingBytes = dmc.alignedRecordWriterPaddingBytes,
            alignedRecordWriterFailures = dmc.alignedRecordWriterFailures,
            alignedRecordWriterStagingBytes = dmc.alignedRecordWriterStagingBytes,
            alignedRecordWriterProbeVerified = dmc.alignedRecordWriterProbeVerified,
            kernelAio = dmc.kernelAio,
            losslessStoragePressure = dmc.losslessStoragePressure
            )
        }
    val physicalReservationTelemetry: Npv10PhysicalReservationTelemetry
        get() {
            val currentReserved = physicalReservedBytes
            val observedHeadroom = minimumReservationHeadroomBytes.get().let { minimum ->
                if (minimum == Long.MAX_VALUE) {
                    (currentReserved - committedBytes).coerceAtLeast(0L)
                } else {
                    minimum.coerceAtLeast(0L)
                }
            }
            return Npv10PhysicalReservationTelemetry(
                enabled = reservationEnforced,
                frameRecordBytes = reservationProfile.frameRecordBytes,
                bytesPerSecond = reservationProfile.bytesPerSecond,
                initialCoverageRequiredBytes = reservationProfile.initialCoverageBytes,
                initialReservedBytes = initialPhysicalReservedBytes,
                currentReservedBytes = currentReserved,
                rollingLeadBytes = reservationProfile.rollingLeadBytes,
                rollingExtensionBytes = reservationProfile.rollingExtensionBytes,
                extensionAttempts = reservationExtensionAttempts.get(),
                extensionSuccesses = reservationExtensionSuccesses.get(),
                extensionFailures = reservationExtensionFailures.get(),
                totalExtendedBytes = reservationExtendedBytes.get(),
                minimumHeadroomBytes = observedHeadroom,
                unreservedWriteRefusals = unreservedWriteRefusals.get(),
                lastFailure = reservationGrowthFailure?.message.orEmpty(),
                allocationSliceLimitBytes = Npv10RollingExtentAllocator.LIVE_SLICE_BYTES,
                allocationSlices = reservationAllocationSlices.get(),
                maximumAllocationSliceNs = reservationAllocationMaxSliceNs.get()
            )
        }
    internal val durabilityStageTimings: Npv10DurabilityStageSnapshot
        get() = durabilityStageTelemetry.snapshot()

    val durabilityCheckpointTimings: Npv10DurabilityCheckpointTimings
        get() {
            val checkpoints = durabilityCheckpointCount.get()
            return Npv10DurabilityCheckpointTimings(
                checkpoints = checkpoints,
                averageCheckpointMs = averageMs(durabilityCheckpointTotalNs.get(), checkpoints),
                maxCheckpointMs = nanosToMs(durabilityCheckpointMaxNs.get()),
                queuedBehindReservationGrowth = checkpointsQueuedBehindReservationGrowth.get(),
                coalescedRequests = coalescedDurabilityCheckpointRequests.get(),
                completedThroughFrame = synchronized(durabilityStateLock) {
                    durabilityCompletedThroughFrame
                },
                dedicatedDescriptor = true
            )
        }

    /**
     * Starts a new sensor-cadence segment after an intentional capture pause.
     *
     * The next frame keeps its original hardware timestamp in the master, while elapsed time during
     * the deliberate pause is not misclassified as frame loss. Calls are serialized with frame
     * writes by [Npv10RecordingManager]'s writer executor.
     */
    @Synchronized
    fun markTimestampCadenceDiscontinuity() {
        check(terminalCadenceBoundaryTimestampNs <= 0L) {
            "NPV10 cadence cannot resume after terminal boundary=" +
                terminalCadenceBoundaryTimestampNs
        }
        lastFrameTimestampNs = -1L
        lastCadenceIntervalEndTimestampNs = -1L
        lastCadenceIntervalMissingFrames = 0L
        lastCadenceIntervalJitterNs = 0L
        lastCadenceIntervalHasJitterSample = false
        maxTimestampJitterBeforeLastIntervalNs = maxTimestampJitterNs
    }

    /**
     * Seals the exact final captured interval without excusing gaps in saved media.
     * Calls must be ordered after every accepted write. A timestamp mismatch or any later write fails
     * closed instead of weakening cadence checks for content frames.
     */
    @Synchronized
    internal fun closeTerminalCadenceSegment(markerTimestampNs: Long): Long? {
        if (closed || markerTimestampNs <= 0L || terminalCadenceBoundaryTimestampNs > 0L ||
            frameCount <= 0L || lastFrameTimestampNs != markerTimestampNs ||
            lastCadenceIntervalEndTimestampNs != markerTimestampNs ||
            lastCadenceIntervalMissingFrames > inferredDroppedFrameCount
        ) {
            return null
        }
        if (lastCadenceIntervalHasJitterSample) {
            if (lastCadenceIntervalJitterNs > totalTimestampJitterNs || timestampJitterSamples <= 0L ||
                maxTimestampJitterNs < maxTimestampJitterBeforeLastIntervalNs) {
                return null
            }
        }
        // Even a terminal packet is visible in playback. A marker label must never hide its gap.
        // The Camera2 stop now proves the already-captured tail without submitting an extra image.
        val excludedMissingFrames = 0L
        terminalCadenceBoundaryTimestampNs = markerTimestampNs
        return excludedMissingFrames
    }

    init {
        Npv10IntegrityValidator.validateSessionConfig(config)
        outputFile.parentFile?.mkdirs()
        randomAccessFile = RandomAccessFile(outputFile, "rw")
        channel = randomAccessFile.channel
        val openedDurabilityFile = try {
            RandomAccessFile(outputFile, "rw")
        } catch (failure: Throwable) {
            runCatching { randomAccessFile.close() }
            durabilityExecutor.shutdownNow()
            reservationExecutor.shutdownNow()
            throw failure
        }
        durabilityRandomAccessFile = openedDurabilityFile
        durabilityChannel = openedDurabilityFile.channel
        try {
            // A dedicated descriptor prevents a multi-second storage durability barrier from taking
            // the Java FileChannel state used by ordered frame-header writes. POSIX durability is an
            // inode property, so forcing this descriptor also covers payload pwrite() calls made by
            // the native DMC descriptor, while capture can continue on its independent descriptors.
            preallocationResult = initializePhysicalReservation(
                availableStorageBytes = availableStorageBytes,
                preparedPreallocation = preparedPreallocation
            )
            physicalReservedBytes = preallocationResult.reservedBytes
            initialPhysicalReservedBytes = preallocationResult.reservedBytes
            reservationEnforced =
                preallocationResult.method == RecordingPreallocationMethod.POSIX_FALLOCATE
            writeHeader()
            if (config.directMappedCommitRequired) {
                val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
                    config.width,
                    config.height,
                    config.payloadFormat
                )
                val encodedCapacityBytes = Npv10PacketCodecEngine.storedBytes(
                    decodedBytes,
                    config.packetCodec
                )
                val packedPayloadBytes = Npv10PacketCodecEngine.packedP010Bytes(decodedBytes)
                val sampleCount = decodedBytes / Short.SIZE_BYTES
                require(encodedCapacityBytes in 1L..Int.MAX_VALUE.toLong() &&
                    packedPayloadBytes in 1L..Int.MAX_VALUE.toLong() &&
                    sampleCount in 1L..Int.MAX_VALUE.toLong()
                ) {
                    "NPV10 DMC frame geometry exceeds native limits: " +
                        "packed=$packedPayloadBytes capacity=$encodedCapacityBytes samples=$sampleCount"
                }
                val slotCount = if (config.niv99ProcessingRequired) {
                    Npv10InFlightBufferPolicy.niv99PendingFrames(
                        config.width,
                        config.height,
                        config.packetCodec
                    )
                } else {
                    Npv10InFlightBufferPolicy.pendingFrames(
                        config.width,
                        config.height,
                        config.packetCodec
                    )
                }
                directMappedCommitEngine = Npv10DirectMappedCommitEngine.open(
                    file = outputFile,
                    dataStartOffset = committedBytes,
                    frameHeaderBytes = Npv10PhysicalReservationPolicy.FRAME_HEADER_BYTES.toInt(),
                    payloadBytes = packedPayloadBytes.toInt(),
                    payloadCapacityBytes = encodedCapacityBytes.toInt(),
                    sampleCount = sampleCount.toInt(),
                    chunkedLosslessCompression =
                        config.packetCodec == Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2,
                    slotCount = slotCount,
                    reservedBytes = physicalReservedBytes
                )
            }
            updateMinimumReservationHeadroom(
                (physicalReservedBytes - committedBytes).coerceAtLeast(0L)
            )
        } catch (t: Throwable) {
            runCatching { directMappedCommitEngine?.close() }
            directMappedCommitEngine = null
            runCatching { channel.truncate(0L) }
            runCatching { durabilityRandomAccessFile.close() }
            runCatching { randomAccessFile.close() }
            durabilityExecutor.shutdownNow()
            reservationExecutor.shutdownNow()
            throw t
        }
    }

    @Synchronized
    fun writeFrame(frame: RawYuvFrame) {
        check(!closed) { "NPV10 writer is closed" }
        throwIfDurabilityCheckpointFailed()
        throwIfReservationGrowthFailed()
        val writeStartedNs = System.nanoTime()
        Npv10IntegrityValidator.validateRuntimeFrame(config, frame)
        validateNextFrameTimestamp(frame.timestamp)
        val encodeStartedNs = System.nanoTime()
        val payload = Npv10FramePacker.pack(frame, config.payloadFormat)
        val encodedPacket = Npv10PacketCodecEngine.encode(payload, config.packetCodec)
        val encodeElapsedNs = elapsedNs(encodeStartedNs)
        val crcStartedNs = System.nanoTime()
        val crc = CRC32()
        crc.update(encodedPacket)
        val crcValue = crc.value.toInt()
        val crcElapsedNs = elapsedNs(crcStartedNs)
        ensurePhysicalReservationForFrame(encodedPacket.size)
        val fileWriteStartedNs = System.nanoTime()
        writeFrameHeader(
            timestampNs = frame.timestamp,
            width = frame.width,
            height = frame.height,
            payloadSize = encodedPacket.size,
            crcValue = crcValue
        )
        writeFramePayload(encodedPacket)
        markCommittedBoundary()
        recordCommittedFrameTimestamp(frame.timestamp)
        val fileWriteElapsedNs = elapsedNs(fileWriteStartedNs)
        if (frameCount == 0L) {
            firstFrameTimestampNs = frame.timestamp
            firstFrameWidth = frame.width
            firstFrameHeight = frame.height
            firstFrameBitDepth = frame.bitDepth
        }
        frameCount += 1L
        payloadBytesWritten += payload.size.toLong()
        encodedPayloadBytesWritten += encodedPacket.size.toLong()
        val writeElapsedNs = System.nanoTime() - writeStartedNs
        totalWriteTimeNs += writeElapsedNs
        maxWriteTimeNs = maxOf(maxWriteTimeNs, writeElapsedNs)
        recordWriterStages(
            encodeAndCrcNs = encodeElapsedNs,
            fallbackCrcNs = crcElapsedNs,
            fileWriteNs = fileWriteElapsedNs,
            nativeFusedCrc = false
        )
        scheduleRollingReservationIfRequired()
        scheduleDurabilityCheckpointIfRequired()
    }

    @Synchronized
    fun writeP010Image(image: Image): Npv10FramePacker.P010CopyPath {
        check(!closed) { "NPV10 writer is closed" }
        throwIfDurabilityCheckpointFailed()
        throwIfReservationGrowthFailed()
        require(config.payloadFormat == Npv10PayloadFormat.P010_LE) {
            "Direct Image write is only valid for P010_LE NPV10 sessions"
        }
        val timestampNs = image.timestamp
        validateNextFrameTimestamp(timestampNs)
        validateRuntimeP010Image(image)

        val payloadBytes = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(image.width, image.height, config.payloadFormat)
            .toInt()
        val encodedPacket = encodedPacketBuffer(payloadBytes)
        val writeStartedNs = System.nanoTime()
        val fusedPack = if (config.packetCodec == Npv10PacketCodec.PACKED_P010_10_LE_V1) {
            Npv10FramePacker.packP010ImageToPackedTenBitBufferWithResult(
                image = image,
                target = encodedPacket
            )
        } else {
            Npv10FramePacker.P010CopyResult(0, Npv10FramePacker.P010CopyPath.FAILED)
        }
        if (fusedPack.path == Npv10FramePacker.P010CopyPath.NATIVE_DIRECT) {
            val expectedEncodedBytes = Npv10PacketCodecEngine.storedBytes(
                payloadBytes.toLong(),
                config.packetCodec
            ).toInt()
            require(fusedPack.bytesCopied == expectedEncodedBytes) {
                "Fused P010 packet produced ${fusedPack.bytesCopied} bytes, " +
                    "expected $expectedEncodedBytes"
            }
            val encodeElapsedNs = elapsedNs(writeStartedNs)
            return commitEncodedP010Frame(
                timestampNs = timestampNs,
                width = image.width,
                height = image.height,
                decodedPayloadBytes = payloadBytes,
                encodedPacket = encodedPacket,
                encodedBytes = fusedPack.bytesCopied,
                encodeAndCrcNs = encodeElapsedNs,
                copyPath = fusedPack.path,
                writeStartedNs = writeStartedNs
            )
        }

        // Compatibility path for unusual non-direct/vendor plane layouts. It remains byte-identical
        // and fail-closed; supported Camera2 P010 planes take the fused native route above.
        val payloadBuffer = directP010PayloadBuffer(payloadBytes)
        val copyResult = Npv10FramePacker.copyP010ImageIntoDirectBufferWithResult(image, payloadBuffer)
        require(copyResult.bytesCopied == payloadBytes) {
            "P010 Image copy produced ${copyResult.bytesCopied} bytes, expected $payloadBytes"
        }
        return writeCopiedP010Frame(
            Npv10CopiedP010Frame(
                timestampNs = timestampNs,
                width = image.width,
                height = image.height,
                payload = payloadBuffer,
                payloadBytes = payloadBytes,
                copyPath = copyResult.path
            )
        )
    }

    @Synchronized
    fun writeCopiedP010Frame(frame: Npv10CopiedP010Frame): Npv10FramePacker.P010CopyPath {
        check(!closed) { "NPV10 writer is closed" }
        throwIfDurabilityCheckpointFailed()
        throwIfReservationGrowthFailed()
        require(config.payloadFormat == Npv10PayloadFormat.P010_LE) {
            "Copied P010 write is only valid for P010_LE NPV10 sessions"
        }
        validateNextFrameTimestamp(frame.timestampNs)
        validateRuntimeP010Frame(
            width = frame.width,
            height = frame.height,
            payloadBytes = frame.payloadBytes
        )

        val writeStartedNs = System.nanoTime()
        val encodedPacket = encodedPacketBuffer(frame.payloadBytes)
        val encodeStartedNs = System.nanoTime()
        val encodeResult = Npv10PacketCodecEngine.encodeWithCrc32(
            decoded = frame.payload,
            decodedBytes = frame.payloadBytes,
            codec = config.packetCodec,
            target = encodedPacket
        )
        val encodeElapsedNs = elapsedNs(encodeStartedNs)
        return commitEncodedP010Frame(
            timestampNs = frame.timestampNs,
            width = frame.width,
            height = frame.height,
            decodedPayloadBytes = frame.payloadBytes,
            encodedPacket = encodedPacket,
            encodedBytes = encodeResult.encodedBytes,
            precomputedCrc32 = encodeResult.crc32,
            encodeAndCrcNs = encodeElapsedNs,
            copyPath = frame.copyPath,
            writeStartedNs = writeStartedNs
        )
    }

    /**
     * Commits an already packed and checksummed Camera2 packet without copying or traversing its
     * multi-megabyte payload again. Ownership of [packet] remains with the caller for the complete
     * call. A direct-mapped packet publishes its engine-owned slot to the bounded native writer;
     * that immutable slot is retired only after the positional write completes.
     */
    internal fun beginDirectMappedFrame(
        frameIndex: Long,
        encodedBytes: Int
    ): Npv10DirectMappedCommitEngine = directMappedAdmissionFence.withAdmission {
        val (engine, reservedForAdmission) = synchronized(this) {
            check(!closed) { "NPV10 writer is closed" }
            check(config.directMappedCommitRequired) {
                "Direct-mapped frame requested for a non-DMC NPV10 session"
            }
            val activeEngine = checkNotNull(directMappedCommitEngine) {
                "Required NPV10 direct-mapped commit engine is unavailable"
            }
            require(encodedBytes == activeEngine.payloadCapacityBytes) {
                "DMC maximum payload size changed from ${activeEngine.payloadCapacityBytes} to $encodedBytes"
            }
            require(frameIndex >= frameCount && frameIndex - frameCount < activeEngine.slotCount.toLong()) {
                "DMC frame index=$frameIndex is outside the bounded in-flight window from $frameCount"
            }
            val requiredEndBytes = directMappedFrameEnd(activeEngine, frameIndex)
            ensurePhysicalReservationThrough(requiredEndBytes)
            activeEngine to physicalReservedBytes
        }
        // Native admission may wait for a previous slot's write or for fstat. Keeping the Kotlin
        // writer monitor here would stop prepared frames from committing despite the native lock
        // being released. The fence retains lifecycle ownership while that monitor stays available.
        // Native admission re-proves this snapshot against the file and never regresses its own
        // reservation high-water mark if a concurrent commit has observed a newer 10-GiB extent.
        engine.beginFrame(frameIndex, reservedForAdmission)
        engine
    }

    internal fun abortDirectMappedFrame(frameIndex: Long) {
        directMappedCommitEngine?.abortFrame(frameIndex)
    }

    @Synchronized
    internal fun writePreparedP010Packet(
        packet: Npv10PreparedP010Packet
    ): Npv10FramePacker.P010CopyPath {
        check(!closed) { "NPV10 writer is closed" }
        throwIfDurabilityCheckpointFailed()
        throwIfReservationGrowthFailed()
        require(config.payloadFormat == Npv10PayloadFormat.P010_LE) {
            "Prepared P010 write is only valid for P010_LE NPV10 sessions"
        }
        require(config.packetCodec == Npv10PacketCodec.PACKED_P010_10_LE_V1 ||
            config.packetCodec == Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        ) {
            "Prepared P010 write requires a lossless packed-10 packet codec"
        }
        validateNextFrameTimestamp(packet.timestampNs)
        validateRuntimeP010Frame(
            width = packet.width,
            height = packet.height,
            payloadBytes = packet.decodedPayloadBytes
        )
        require(Npv10PacketCodecEngine.isValidStoredBytes(
            actualBytes = packet.encodedBytes.toLong(),
            decodedBytes = packet.decodedPayloadBytes.toLong(),
            codec = config.packetCodec
        )) {
            "Prepared P010 packet has invalid size ${packet.encodedBytes} for ${config.packetCodec}"
        }
        require(packet.directMapped == config.directMappedCommitRequired) {
            "Prepared P010 packet DMC route does not match its session contract"
        }
        if (!packet.directMapped) {
            require(packet.packet.isDirect && packet.packet.capacity() >= packet.encodedBytes) {
                "Prepared P010 packet must use a direct buffer with complete encoded capacity"
            }
        }
        require(packet.copyPath != Npv10FramePacker.P010CopyPath.FAILED) {
            "Prepared P010 packet has no valid native copy path"
        }

        val writerServiceStartedNs = System.nanoTime()
        val copyPath = if (packet.directMapped) {
            commitDirectMappedP010Frame(packet, writerServiceStartedNs)
        } else {
            commitEncodedP010Frame(
                timestampNs = packet.timestampNs,
                width = packet.width,
                height = packet.height,
                decodedPayloadBytes = packet.decodedPayloadBytes,
                encodedPacket = packet.packet,
                encodedBytes = packet.encodedBytes,
                precomputedCrc32 = packet.crc32,
                precomputedCrcNativeFused = packet.crcPath.nativeFused,
                precomputedFallbackCrcNs = packet.fallbackCrcTimeNs,
                // Packing and CRC happened once, while the Image was valid. Preserve that stage cost in
                // telemetry while averageWriteTimeMs remains the isolated writer-service measurement.
                encodeAndCrcNs = packet.preparationTimeNs.coerceAtLeast(0L),
                copyPath = packet.copyPath,
                writeStartedNs = writerServiceStartedNs
            )
        }
        val writerServiceNs = elapsedNs(writerServiceStartedNs)
        val preparedProcessingNs = saturatingDurationAdd(
            packet.preparationTimeNs.coerceAtLeast(0L),
            writerServiceNs
        )
        preparedPacketFrameCount += 1L
        totalPreparedProcessingTimeNs = saturatingDurationAdd(
            totalPreparedProcessingTimeNs,
            preparedProcessingNs
        )
        maxPreparedProcessingTimeNs = maxOf(maxPreparedProcessingTimeNs, preparedProcessingNs)
        return copyPath
    }

    private fun commitDirectMappedP010Frame(
        packet: Npv10PreparedP010Packet,
        writeStartedNs: Long
    ): Npv10FramePacker.P010CopyPath {
        val engine = checkNotNull(directMappedCommitEngine) {
            "Required NPV10 direct-mapped commit engine disappeared"
        }
        require(packet.directMappedFrameIndex == frameCount) {
            "DMC frame order mismatch: index=${packet.directMappedFrameIndex} committed=$frameCount"
        }
        val recordStart = committedBytes
        check(channel.position() == recordStart) {
            "DMC file boundary mismatch: channel=${channel.position()} committed=$recordStart"
        }
        ensurePhysicalReservationForFrame(packet.encodedBytes)
        val fileWriteStartedNs = System.nanoTime()
        // Publish the frame marker, metadata and packed payload to one bounded native ordered-write
        // transaction. Splitting the 28-byte header onto FileChannel previously introduced a
        // second page-cache scheduling point before every DMC payload; under thermal pressure its
        // tail combined with payload persistence and exceeded one 25-fps frame period. The native
        // worker writes the byte-identical header and payload together while this channel remains
        // the authoritative logical boundary/durability owner.
        val payloadFileOffset = checkedAdd(
            recordStart,
            Npv10PhysicalReservationPolicy.FRAME_HEADER_BYTES
        )
        engine.commitFrame(
            frameIndex = packet.directMappedFrameIndex,
            encodedBytes = packet.encodedBytes,
            payloadFileOffset = payloadFileOffset,
            reservedBytes = physicalReservedBytes,
            crc32 = packet.crc32,
            timestampNs = packet.timestampNs,
            width = packet.width,
            height = packet.height
        )
        val recordEnd = checkedAdd(payloadFileOffset, packet.encodedBytes.toLong())
        channel.position(recordEnd)
        markCommittedBoundary()
        recordCommittedFrameTimestamp(packet.timestampNs)
        val fileWriteElapsedNs = elapsedNs(fileWriteStartedNs)
        if (frameCount == 0L) {
            firstFrameTimestampNs = packet.timestampNs
            firstFrameWidth = packet.width
            firstFrameHeight = packet.height
            firstFrameBitDepth = config.sourceBitDepth
        }
        frameCount += 1L
        payloadBytesWritten += packet.decodedPayloadBytes.toLong()
        encodedPayloadBytesWritten += packet.encodedBytes.toLong()
        val writeElapsedNs = elapsedNs(writeStartedNs)
        totalWriteTimeNs += writeElapsedNs
        maxWriteTimeNs = maxOf(maxWriteTimeNs, writeElapsedNs)
        recordWriterStages(
            encodeAndCrcNs = packet.preparationTimeNs.coerceAtLeast(0L),
            fallbackCrcNs = packet.fallbackCrcTimeNs.coerceAtLeast(0L),
            fileWriteNs = fileWriteElapsedNs,
            nativeFusedCrc = packet.crcPath.nativeFused
        )
        scheduleRollingReservationIfRequired()
        scheduleDurabilityCheckpointIfRequired()
        return packet.copyPath
    }

    private fun commitEncodedP010Frame(
        timestampNs: Long,
        width: Int,
        height: Int,
        decodedPayloadBytes: Int,
        encodedPacket: ByteBuffer,
        encodedBytes: Int,
        precomputedCrc32: Int? = null,
        precomputedCrcNativeFused: Boolean = precomputedCrc32 != null,
        precomputedFallbackCrcNs: Long = 0L,
        encodeAndCrcNs: Long,
        copyPath: Npv10FramePacker.P010CopyPath,
        writeStartedNs: Long
    ): Npv10FramePacker.P010CopyPath {
        validateNextFrameTimestamp(timestampNs)
        val crcStartedNs = System.nanoTime()
        val crcValue = precomputedCrc32 ?: crc32(encodedPacket, encodedBytes)
        val fallbackCrcElapsedNs = if (precomputedCrc32 == null) {
            elapsedNs(crcStartedNs)
        } else {
            precomputedFallbackCrcNs.coerceAtLeast(0L)
        }
        ensurePhysicalReservationForFrame(encodedBytes)
        val fileWriteStartedNs = System.nanoTime()
        writeFrameHeader(
            timestampNs = timestampNs,
            width = width,
            height = height,
            payloadSize = encodedBytes,
            crcValue = crcValue
        )
        writeFramePayload(encodedPacket, encodedBytes)
        markCommittedBoundary()
        recordCommittedFrameTimestamp(timestampNs)
        val fileWriteElapsedNs = elapsedNs(fileWriteStartedNs)
        if (frameCount == 0L) {
            firstFrameTimestampNs = timestampNs
            firstFrameWidth = width
            firstFrameHeight = height
            firstFrameBitDepth = config.sourceBitDepth
        }
        frameCount += 1L
        payloadBytesWritten += decodedPayloadBytes.toLong()
        encodedPayloadBytesWritten += encodedBytes.toLong()
        val writeElapsedNs = System.nanoTime() - writeStartedNs
        totalWriteTimeNs += writeElapsedNs
        maxWriteTimeNs = maxOf(maxWriteTimeNs, writeElapsedNs)
        recordWriterStages(
            encodeAndCrcNs = encodeAndCrcNs,
            fallbackCrcNs = fallbackCrcElapsedNs,
            fileWriteNs = fileWriteElapsedNs,
            nativeFusedCrc = precomputedCrc32 != null && precomputedCrcNativeFused
        )
        scheduleRollingReservationIfRequired()
        scheduleDurabilityCheckpointIfRequired()
        return copyPath
    }

    override fun close() {
        directMappedAdmissionFence.withClose {
            synchronized(this) {
                closeInternal(preservePhysicalReservationWhenEmpty = false)
            }
        }
    }

    /** Close without trimming only when no camera packet reached this writer, allowing rapid retry. */
    fun closePreservingPhysicalReservationWhenEmpty(): Boolean =
        directMappedAdmissionFence.withClose {
            synchronized(this) {
                closeInternal(preservePhysicalReservationWhenEmpty = true)
            }
        }

    private fun closeInternal(preservePhysicalReservationWhenEmpty: Boolean): Boolean {
        if (closed) return false
        val finalizationStartedNs = System.nanoTime()
        val preserveReservation =
            preservePhysicalReservationWhenEmpty &&
                frameCount == 0L &&
                preallocationResult.method == RecordingPreallocationMethod.POSIX_FALLOCATE
        closed = true
        var failure: Throwable? = null
        var truncateMs = 0.0
        var durableSyncMs = 0.0
        var descriptorCloseMs = 0.0
        try {
            try {
                awaitReservationGrowth()
            } catch (t: Throwable) {
                failure = mergeFailure(failure, t)
            }
            try {
                awaitDurabilityCheckpoint()
            } catch (t: Throwable) {
                failure = mergeFailure(failure, t)
            }
            val engine = directMappedCommitEngine
            if (engine != null) {
                try {
                    engine.awaitCommittedFrames(frameCount)
                    finalDirectMappedCommitTelemetry = engine.snapshot()
                } catch (t: Throwable) {
                    failure = mergeFailure(failure, t)
                } finally {
                    try {
                        engine.close()
                    } catch (closeFailure: Throwable) {
                        failure = mergeFailure(failure, closeFailure)
                    }
                    directMappedCommitEngine = null
                }
            }
            try {
                if (!preserveReservation) {
                    val truncateStartedNs = System.nanoTime()
                    channel.truncate(committedBytes)
                    truncateMs = elapsedMs(truncateStartedNs)
                }
                val durableSyncStartedNs = System.nanoTime()
                channel.force(true)
                durableSyncMs = elapsedMs(durableSyncStartedNs)
                preallocationResult = preallocationResult.copy(
                    committedBytes = committedBytes,
                    reservationTrimmed = !preserveReservation &&
                        preallocationResult.reservedBytes > committedBytes,
                    synced = true
                )
            } catch (t: Throwable) {
                failure = mergeFailure(failure, t)
            }
        } finally {
            val descriptorCloseStartedNs = System.nanoTime()
            try {
                durabilityRandomAccessFile.close()
            } catch (closeFailure: Throwable) {
                failure = mergeFailure(failure, closeFailure)
            }
            try {
                randomAccessFile.close()
            } catch (closeFailure: Throwable) {
                failure = mergeFailure(failure, closeFailure)
            } finally {
                durabilityExecutor.shutdown()
                reservationExecutor.shutdown()
                if (!firstDurableFrameProof.isDone) {
                    firstDurableFrameProof.completeExceptionally(
                        failure ?: IOException("NPV10 writer closed before frame-1 durability proof")
                    )
                }
                descriptorCloseMs = elapsedMs(descriptorCloseStartedNs)
                lastFinalizationTimings = Npv10FileFinalizationTimings(
                    truncateMs = truncateMs,
                    durableSyncMs = durableSyncMs,
                    descriptorCloseMs = descriptorCloseMs,
                    totalMs = elapsedMs(finalizationStartedNs),
                    committedBytes = committedBytes,
                    reservedBytes = preallocationResult.reservedBytes
                )
                Log.i(
                    TAG,
                    "NPV10 writer stage evidence totalFrames=$frameCount " +
                        "totalAvgMs=$averageWriteTimeMs totalMaxMs=$maxWriteTimeMs " +
                        "stages=$writerStageTimings checkpoints=$durabilityCheckpointTimings " +
                        "checkpointStages=$durabilityStageTimings " +
                        "reservation=$physicalReservationTelemetry"
                )
            }
        }
        failure?.let { throw it }
        return preserveReservation
    }

    private fun writeHeader() {
        writeBytes(Npv10ContainerPolicy.MAGIC_BYTES)
        writeInt(Npv10ContainerPolicy.VERSION)
        writeInt(config.width)
        writeInt(config.height)
        writeInt(config.fps)
        writeInt(config.storageBitDepth)
        val metadata = buildMetadataLine(config).toByteArray(StandardCharsets.UTF_8)
        writeInt(metadata.size)
        writeBytes(metadata)
        markCommittedBoundary()
        channel.force(false)
    }

    private fun initializePhysicalReservation(
        availableStorageBytes: Long,
        preparedPreallocation: RecordingPreallocationResult?
    ): RecordingPreallocationResult {
        val preparedReservedBytes = preparedPreallocation?.reservedBytes?.coerceAtLeast(0L) ?: 0L
        if (preparedPreallocation == null) {
            randomAccessFile.setLength(0L)
        } else {
            require(
                preparedPreallocation.plan.enabled &&
                    preparedPreallocation.method == RecordingPreallocationMethod.POSIX_FALLOCATE &&
                    outputFile.length() >= preparedReservedBytes
            ) { "Invalid prepared NPV10 physical reservation" }
        }
        val reservationPlan = Npv10PhysicalReservationPolicy.initialPlan(
            profile = reservationProfile,
            availableBytesIncludingCurrentReservation = availableStorageBytes,
            currentReservedBytes = preparedReservedBytes
        )
        val sharedPlan = reservationPlan.toRecordingPlan()
        if (!reservationPlan.enabled || !reservationPlan.fullyCoversRequest) {
            channel.position(0L)
            return RecordingPreallocationResult(
                plan = sharedPlan,
                method = RecordingPreallocationMethod.NONE,
                reservedBytes = 0L,
                failure = if (reservationPlan.fullyCoversRequest) {
                    reservationPlan.reason
                } else {
                    "initial-30s-not-fully-reserved: ${reservationPlan.reason}"
                }
            )
        }
        val result = try {
            if (reservationPlan.targetReservedBytes > preparedReservedBytes) {
                Os.posix_fallocate(
                    randomAccessFile.fd,
                    preparedReservedBytes,
                    reservationPlan.growthBytes
                )
            }
            RecordingPreallocationResult(
                plan = sharedPlan,
                method = RecordingPreallocationMethod.POSIX_FALLOCATE,
                reservedBytes = reservationPlan.targetReservedBytes,
                committedBytes = 0L,
                reservationTrimmed = false,
                synced = false
            )
        } catch (fallocateFailure: Throwable) {
            if (preparedPreallocation != null) {
                throw IOException(
                    "Unable to extend prepared NPV10 reservation from $preparedReservedBytes to " +
                        "${reservationPlan.targetReservedBytes} bytes",
                    fallocateFailure
                )
            }
            // A sparse setLength() does not reserve physical blocks. Keep the result truthful so the
            // strict runtime admission gate can reject an unreserved master instead of pretending.
            RecordingPreallocationResult(
                plan = sharedPlan,
                method = RecordingPreallocationMethod.NONE,
                reservedBytes = 0L,
                failure = fallocateFailure.message
            )
        }
        channel.position(0L)
        return result
    }

    private fun markCommittedBoundary() {
        committedBytes = channel.position()
    }

    private fun writeFrameHeader(
        timestampNs: Long,
        width: Int,
        height: Int,
        payloadSize: Int,
        crcValue: Int
    ) {
        frameHeaderBuffer.clear()
        frameHeaderBuffer.put(Npv10ContainerPolicy.FRAME_MAGIC_BYTES)
        frameHeaderBuffer.putLong(timestampNs)
        frameHeaderBuffer.putInt(width)
        frameHeaderBuffer.putInt(height)
        frameHeaderBuffer.putInt(payloadSize)
        frameHeaderBuffer.putInt(crcValue)
        frameHeaderBuffer.flip()
        writeBuffer(frameHeaderBuffer)
    }

    private fun writeFramePayload(payload: ByteArray) {
        writeBuffer(ByteBuffer.wrap(payload))
    }

    private fun writeFramePayload(payload: ByteBuffer, payloadSize: Int) {
        val duplicate = payload.duplicate()
        duplicate.position(0)
        duplicate.limit(payloadSize)
        writeBuffer(duplicate)
    }

    private fun writeInt(value: Int) {
        val buffer = ByteBuffer.allocateDirect(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(value)
        buffer.flip()
        writeBuffer(buffer)
    }

    private fun writeBytes(bytes: ByteArray) {
        writeBuffer(ByteBuffer.wrap(bytes))
    }

    private fun writeBuffer(buffer: ByteBuffer) {
        var zeroWriteRetries = 0
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer)
            if (written == 0) {
                zeroWriteRetries += 1
                if (zeroWriteRetries > MAX_ZERO_WRITE_RETRIES) {
                    throw IOException("NPV10 writer stalled while writing ${buffer.remaining()} bytes")
                }
                Thread.yield()
            } else {
                zeroWriteRetries = 0
            }
        }
    }

    private fun directP010PayloadBuffer(payloadBytes: Int): ByteBuffer {
        val current = reusableDirectP010Payload
        if (current != null && current.capacity() >= payloadBytes) {
            current.clear()
            current.limit(payloadBytes)
            return current
        }
        return ByteBuffer
            .allocateDirect(payloadBytes)
            .order(ByteOrder.nativeOrder())
            .also { reusableDirectP010Payload = it }
    }

    private fun encodedPacketBuffer(decodedPayloadBytes: Int): ByteBuffer {
        val encodedBytes = Npv10PacketCodecEngine
            .storedBytes(decodedPayloadBytes.toLong(), config.packetCodec)
            .toInt()
        val current = reusableEncodedPacket
        if (current != null && current.capacity() >= encodedBytes) {
            current.clear()
            current.limit(encodedBytes)
            return current
        }
        return ByteBuffer
            .allocateDirect(encodedBytes)
            .order(ByteOrder.nativeOrder())
            .also { reusableEncodedPacket = it }
    }

    @SuppressLint("InlinedApi")
    private fun validateRuntimeP010Image(image: Image) {
        validateRuntimeP010Frame(
            width = image.width,
            height = image.height,
            payloadBytes = Npv10ContainerPolicy
                .estimatedPayloadBytesPerFrame(image.width, image.height, config.payloadFormat)
                .toInt()
        )
        require(image.format == ImageFormat.YCBCR_P010) {
            "Native P010 NPV10 session received image format ${image.format}"
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

    private fun validateRuntimeP010Frame(width: Int, height: Int, payloadBytes: Int) {
        require(width == config.width && height == config.height) {
            "NPV10 frame ${width}x$height does not match session ${config.width}x${config.height}"
        }
        require(config.sourceBitDepth >= Npv10ContainerPolicy.BIT_DEPTH) {
            "Native P010 NPV10 session requires 10-bit source metadata"
        }
        val expectedPayloadBytes = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(width, height, config.payloadFormat)
            .toInt()
        require(payloadBytes == expectedPayloadBytes) {
            "P010 payload has $payloadBytes bytes, expected $expectedPayloadBytes"
        }
    }

    private fun buildMetadataLine(config: Npv10SessionConfig): String {
        val lines = listOf(
            "container=NPV10",
            "format=${config.payloadFormat.name}",
            "payloadFormat=${config.payloadFormat.name}",
            "packetCodec=${config.packetCodec.name}",
            "packetCodecLossless=true",
            "packetCodecMediaCodecUsed=false",
            "storedPacketsOnly=true",
            "individualFrameFiles=false",
            "mode=${config.mode}",
            "recordingMode=${config.mode}",
            "colorProfile=${config.colorProfile}",
            "selectedColorSpace=${config.resolvedSelectedColorSpace()}",
            "selectedColorScience=${config.resolvedSelectedColorScience()}",
            "colorSelectionSource=${config.colorSelectionSource}",
            "manualColorSelectionRequired=${config.manualColorSelectionRequired}",
            "sourceFormat=${config.sourceFormat}",
            "sourceBitDepth=${config.sourceBitDepth}",
            "storageBitDepth=${config.storageBitDepth}",
            "rawSensorCapture=${config.rawSensorCapture}",
            "sensorPixelModeMaximumResolution=${config.sensorPixelModeMaximumResolution}",
            "gpuDemosaicEnabled=${config.gpuDemosaicEnabled}",
            "darkFrameCalibrationEnabled=${config.darkFrameCalibrationEnabled}",
            "zeroCopyNativeAcquisition=${config.zeroCopyNativeAcquisition}",
            "niv99ProcessingRequired=${config.niv99ProcessingRequired}",
            "directMappedCommitRequired=${config.directMappedCommitRequired}",
            "bayerPattern=${config.bayerPattern}",
            "exactSelectedResolution=${config.exactSelectedResolution}",
            "orientationHintDegrees=${normalizeRightAngle(config.orientationHintDegrees)}",
            "startedAtEpochMs=${config.startedAtEpochMs}",
            "preallocationRequestedBytes=${preallocationResult.plan.requestedBytes}",
            "preallocationReservedBytes=${preallocationResult.reservedBytes}",
            "preallocationSafetyReserveBytes=${preallocationResult.plan.safetyReserveBytes}",
            "preallocationMethod=${preallocationResult.method.name}",
            "committedLengthTruncatedOnClose=true"
        ) + config.rawSidecarMetadata.entries
            .sortedBy { it.key }
            .map { entry -> "raw.${entry.key}=${entry.value}" } +
            config.featureMetadata.entries
            .sortedBy { it.key }
            .map { entry -> "${entry.key}=${entry.value}" } +
            automaticEliteMetadata(config).entries
                .sortedBy { it.key }
                .map { entry -> "${entry.key}=${entry.value}" }
        return lines.joinToString(separator = "\n", postfix = "\n")
            .replace("\r", "")
            .replace("\u0000", "")
            .lowercase(Locale.US)
    }

    private fun normalizeRightAngle(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        require(normalized % 90 == 0) { "NPV10 orientation must be a right angle: $degrees" }
        return normalized
    }

    private fun automaticEliteMetadata(config: Npv10SessionConfig): Map<String, String> =
        EliteVideoQualityContract.resolve(
            EliteVideoQualityRequest(
                mode = EliteVideoPipelineMode.NPV10,
                width = config.width,
                height = config.height,
                fps = config.fps,
                codec = GpuVideoCodec.HEVC,
                logProfile = config.colorProfile,
                sourceBitDepth = config.sourceBitDepth,
                storageBitDepth = config.storageBitDepth,
                sourceFormat = config.sourceFormat,
                payloadFormat = config.payloadFormat,
                hdrMode = config.featureMetadataValue(
                    "hdrMode",
                    "videoHdrMode",
                    "requestedHdrMode",
                    "effectiveHdrMode"
                ),
                tenBitTransport = config.sourceBitDepth >= Npv10ContainerPolicy.BIT_DEPTH,
                colorSpace = config.resolvedSelectedColorSpace(),
                colorScience = config.resolvedSelectedColorScience(),
                colorSpaceSelectedByUser = config.colorSelectionSource == ManualVideoColorSelectionPolicy.USER_MANUAL,
                colorScienceSelectedByUser = config.colorSelectionSource == ManualVideoColorSelectionPolicy.USER_MANUAL,
                savedColorSpacePreference = ManualVideoColorSelectionPolicy.isApprovedSelectionSource(
                    config.colorSelectionSource
                ),
                savedColorSciencePreference = ManualVideoColorSelectionPolicy.isApprovedSelectionSource(
                    config.colorSelectionSource
                ),
                exactSelectedResolution = config.exactSelectedResolution
            )
        ).toMetadata()

    private fun Npv10SessionConfig.featureMetadataValue(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            featureMetadata.entries.firstOrNull { entry ->
                entry.key.equals(key, ignoreCase = true)
            }?.value
        }?.takeIf { it.isNotBlank() } ?: "OFF"

    fun buildDeviceEvidence(
        privateRoot: File,
        droppedFrames: Int,
        generatedAtEpochMs: Long = System.currentTimeMillis()
    ): Npv10DeviceEvidence =
        Npv10IntegrityValidator.buildDeviceEvidence(
            config = config,
            outputFile = outputFile,
            privateRoot = privateRoot,
            firstFrameWidth = firstFrameWidth,
            firstFrameHeight = firstFrameHeight,
            firstFrameBitDepth = firstFrameBitDepth,
            firstFrameTimestampNs = firstFrameTimestampNs,
            frameCount = frameCount,
            droppedFrames = droppedFrames,
            payloadBytes = payloadBytesWritten,
            averageWriteTimeMs = averageWriteTimeMs,
            maxWriteTimeMs = maxWriteTimeMs,
            maxTimestampJitterNs = maxTimestampJitterNs,
            averageTimestampJitterNs = averageTimestampJitterNs,
            sessionHash = "",
            generatedAtEpochMs = generatedAtEpochMs
        )

    private fun validateNextFrameTimestamp(timestampNs: Long) {
        require(timestampNs > 0L) { "NPV10 frame timestamp must be positive: $timestampNs" }
        check(terminalCadenceBoundaryTimestampNs <= 0L) {
            "NPV10 frame timestamp=$timestampNs arrived after terminal cadence boundary=" +
                terminalCadenceBoundaryTimestampNs
        }
        val previousTimestampNs = lastCommittedFrameTimestampNs
        require(previousTimestampNs <= 0L || timestampNs > previousTimestampNs) {
            "NPV10 frame timestamps must be strictly increasing: " +
                "previous=$previousTimestampNs current=$timestampNs"
        }
    }

    private fun recordCommittedFrameTimestamp(timestampNs: Long) {
        check(timestampNs > lastCommittedFrameTimestampNs) {
            "NPV10 committed timestamp lineage regressed: " +
                "previous=$lastCommittedFrameTimestampNs current=$timestampNs"
        }
        updateRuntimeTiming(timestampNs)
        lastCommittedFrameTimestampNs = timestampNs
    }

    private fun updateRuntimeTiming(timestampNs: Long) {
        val previousTimestampNs = lastFrameTimestampNs
        lastCadenceIntervalEndTimestampNs = timestampNs
        lastCadenceIntervalMissingFrames = 0L
        lastCadenceIntervalJitterNs = 0L
        lastCadenceIntervalHasJitterSample = false
        maxTimestampJitterBeforeLastIntervalNs = maxTimestampJitterNs
        if (previousTimestampNs > 0L && timestampNs > previousTimestampNs) {
            val expectedIntervalNs = 1_000_000_000L / config.fps.coerceAtLeast(1).toLong()
            val timestampDeltaNs = timestampNs - previousTimestampNs
            val representedFrameIntervals =
                ((timestampDeltaNs + expectedIntervalNs / 2L) / expectedIntervalNs)
                    .coerceAtLeast(1L)
            val missingFrames = (representedFrameIntervals - 1L).coerceAtLeast(0L)
            lastCadenceIntervalMissingFrames = missingFrames
            inferredDroppedFrameCount =
                (inferredDroppedFrameCount + missingFrames).coerceAtLeast(inferredDroppedFrameCount)
            val jitterNs = abs(timestampDeltaNs - expectedIntervalNs)
            lastCadenceIntervalJitterNs = jitterNs
            lastCadenceIntervalHasJitterSample = true
            maxTimestampJitterNs = maxOf(maxTimestampJitterNs, jitterNs)
            totalTimestampJitterNs += jitterNs
            timestampJitterSamples += 1L
        }
        if (timestampNs > 0L) {
            lastFrameTimestampNs = timestampNs
        }
    }

    private fun crc32(payload: ByteBuffer, payloadSize: Int): Int {
        val duplicate = payload.duplicate()
        duplicate.position(0)
        duplicate.limit(payloadSize)
        val crc = CRC32()
        crc.update(duplicate)
        return crc.value.toInt()
    }

    /** Refuses the frame before its header or payload can enter an unallocated filesystem extent. */
    private fun ensurePhysicalReservationForFrame(encodedPayloadBytes: Int) {
        require(encodedPayloadBytes > 0) { "NPV10 encoded payload must be positive" }
        val frameRecordBytes = checkedAdd(
            Npv10PhysicalReservationPolicy.FRAME_HEADER_BYTES,
            encodedPayloadBytes.toLong()
        )
        val requiredEndBytes = checkedAdd(committedBytes, frameRecordBytes)
        ensurePhysicalReservationThrough(requiredEndBytes)
    }

    private fun ensurePhysicalReservationThrough(requiredEndBytes: Long) {
        if (!reservationEnforced) return
        require(requiredEndBytes >= committedBytes) {
            "NPV10 reservation target regressed below the committed boundary"
        }
        throwIfReservationGrowthFailed()
        var growthAttempts = 0
        while (!Npv10PhysicalReservationPolicy.isWritePhysicallyCovered(
                requiredEndBytes = requiredEndBytes,
                reservedBytes = physicalReservedBytes
            )
        ) {
            // The normal path starts this work with ten seconds of headroom. This synchronous edge
            // path is only a final guard against an unexpectedly slow maintenance executor. Looping
            // makes the proof independent of the configured extension quantum.
            val beforeGrowth = physicalReservedBytes
            scheduleReservationGrowth(force = true)
            awaitReservationGrowth()
            growthAttempts += 1
            if (physicalReservedBytes <= beforeGrowth || growthAttempts > MAX_SYNCHRONOUS_GROWTH_ATTEMPTS) {
                unreservedWriteRefusals.incrementAndGet()
                throw IOException(
                    "NPV10 could not extend physical reservation through $requiredEndBytes bytes"
                )
            }
        }
        val currentReserved = physicalReservedBytes
        if (!Npv10PhysicalReservationPolicy.isWritePhysicallyCovered(requiredEndBytes, currentReserved)) {
            unreservedWriteRefusals.incrementAndGet()
            throw IOException(
                "NPV10 refused an unreserved frame write requiredEndBytes=$requiredEndBytes " +
                    "physicalReservedBytes=$currentReserved"
            )
        }
        updateMinimumReservationHeadroom((currentReserved - requiredEndBytes).coerceAtLeast(0L))
    }

    private fun directMappedFrameEnd(
        engine: Npv10DirectMappedCommitEngine,
        frameIndex: Long
    ): Long = Npv10DmcReservationWindowPolicy.requiredEndBytes(
        committedBytes = committedBytes,
        committedFrames = frameCount,
        frameIndex = frameIndex,
        maximumFrameRecordBytes = engine.frameRecordBytes,
        slotCount = engine.slotCount
    )

    private fun scheduleRollingReservationIfRequired() {
        if (!reservationEnforced) return
        if (!Npv10PhysicalReservationPolicy.shouldScheduleGrowth(
                committedBytes = committedBytes,
                reservedBytes = physicalReservedBytes,
                rollingLeadBytes = reservationProfile.rollingLeadBytes
            )
        ) {
            return
        }
        scheduleReservationGrowth(force = false)
    }

    /**
     * Runs fallocate on a dedicated maintenance lane, independent of both ordered packet commits and
     * the durability lane. A slow periodic sync therefore cannot consume the rolling-reservation
     * lead and force the capture writer into synchronous extent growth.
     *
     * The manager already runs StorageManager.getAllocatableBytes()/allocateBytes() before initial
     * reservation. This rolling step intentionally reads the post-fallocate filesystem remainder and
     * adds the extent already owned by this file; using allocatable bytes here would double-count the
     * held reservation. Lint cannot see the StorageManager calls in the coordinating compilation unit.
     */
    @SuppressLint("UsableSpace")
    private fun scheduleReservationGrowth(force: Boolean) {
        throwIfReservationGrowthFailed()
        if (!reservationEnforced) return
        if (!force && !Npv10PhysicalReservationPolicy.shouldScheduleGrowth(
                committedBytes = committedBytes,
                reservedBytes = physicalReservedBytes,
                rollingLeadBytes = reservationProfile.rollingLeadBytes
            )
        ) {
            return
        }
        val active = pendingReservationGrowth
        if (active != null && !active.isDone) return
        val reservedAtSubmission = physicalReservedBytes
        reservationExtensionAttempts.incrementAndGet()
        pendingReservationGrowth = reservationExecutor.submit {
            try {
                val availableIncludingReservation =
                    Npv10PhysicalReservationPolicy.availableIncludingReservation(
                        usableBytes = runCatching { outputFile.usableSpace }.getOrDefault(0L),
                        reservedBytes = reservedAtSubmission
                    )
                val plan = Npv10PhysicalReservationPolicy.rollingPlan(
                    profile = reservationProfile,
                    availableBytesIncludingCurrentReservation = availableIncludingReservation,
                    currentReservedBytes = reservedAtSubmission
                )
                require(plan.growthBytes >= reservationProfile.frameRecordBytes) {
                    "NPV10 cannot extend its physical reservation while preserving " +
                        "${plan.safetyReserveBytes} safety bytes: ${plan.reason}"
                }
                val allocationStartedNs = System.nanoTime()
                val allocationProof = Npv10RollingExtentAllocator.allocate(
                    initialReservedBytes = reservedAtSubmission,
                    growthBytes = plan.growthBytes,
                    allocateRange = { offsetBytes, lengthBytes ->
                        val sliceStartedNs = System.nanoTime()
                        Trace.beginSection("NPV10 rolling allocation slice")
                        try {
                            val engine = directMappedCommitEngine
                            if (engine != null) {
                                // A separate executor/descriptor still competes on the same inode.
                                // Coordinate all live file mutations without weakening durability
                                // or publishing capacity before the allocation syscall completes.
                                engine.allocatePhysicalRange(offsetBytes, lengthBytes)
                            } else {
                                Os.posix_fallocate(randomAccessFile.fd, offsetBytes, lengthBytes)
                            }
                        } finally {
                            Trace.endSection()
                            updateAtomicMax(reservationAllocationMaxSliceNs, elapsedNs(sliceStartedNs))
                        }
                    },
                    onSliceConfirmed = { confirmedEndBytes, lengthBytes ->
                        // Only a completed physical prefix may be consumed by the native writer.
                        // Later slice failure still poisons this recording through the existing gate.
                        physicalReservedBytes = confirmedEndBytes
                        reservationExtendedBytes.addAndGet(lengthBytes)
                        reservationAllocationSlices.incrementAndGet()
                    },
                    yieldBetweenSlices = { Npv10RollingExtentAllocator.paceLiveAllocation() },
                    sliceLimitBytes = Npv10RollingExtentAllocator.LIVE_SLICE_BYTES
                )
                check(allocationProof.confirmedEndBytes == plan.targetReservedBytes) {
                    "NPV10 rolling extent proof does not cover its planned target"
                }
                preallocationResult = preallocationResult.copy(
                    plan = plan.toRecordingPlan(),
                    method = RecordingPreallocationMethod.POSIX_FALLOCATE,
                    reservedBytes = plan.targetReservedBytes,
                    committedBytes = committedBytes,
                    reservationTrimmed = false,
                    synced = false,
                    failure = null
                )
                physicalReservedBytes = plan.targetReservedBytes
                reservationExtensionSuccesses.incrementAndGet()
                Log.i(
                    TAG,
                    "NPV10 rolling reservation extended from $reservedAtSubmission to " +
                        "${plan.targetReservedBytes} bytes; growth=${allocationProof.allocatedBytes} " +
                        "slices=${allocationProof.completedSlices} " +
                        "sliceLimitBytes=${Npv10RollingExtentAllocator.LIVE_SLICE_BYTES} " +
                        "serviceWindowNs=${Npv10RollingExtentAllocator.LIVE_SERVICE_WINDOW_NS} " +
                        "elapsedMs=${elapsedNs(allocationStartedNs) / 1_000_000.0} " +
                        "totalSlices=${reservationAllocationSlices.get()} " +
                        "maxSliceMs=${reservationAllocationMaxSliceNs.get() / 1_000_000.0}"
                )
            } catch (t: Throwable) {
                reservationExtensionFailures.incrementAndGet()
                reservationGrowthFailure = t
                preallocationResult = preallocationResult.copy(
                    reservedBytes = physicalReservedBytes,
                    committedBytes = committedBytes,
                    failure = t.message ?: t.javaClass.simpleName
                )
                throw t
            }
        }
    }

    private fun awaitReservationGrowth() {
        val growth = pendingReservationGrowth
        if (growth != null) {
            try {
                growth.get()
            } catch (failure: ExecutionException) {
                throw failure.cause ?: failure
            }
        }
        throwIfReservationGrowthFailed()
    }

    private fun throwIfReservationGrowthFailed() {
        reservationGrowthFailure?.let { failure ->
            throw IOException("NPV10 rolling physical reservation failed", failure)
        }
    }

    private fun updateMinimumReservationHeadroom(headroomBytes: Long) {
        var observed = minimumReservationHeadroomBytes.get()
        val candidate = headroomBytes.coerceAtLeast(0L)
        while (candidate < observed &&
            !minimumReservationHeadroomBytes.compareAndSet(observed, candidate)
        ) {
            observed = minimumReservationHeadroomBytes.get()
        }
    }

    /**
     * Keeps the existing frame-1/120-frame recovery checkpoints without blocking the ordered packet
     * writer lane on storage fsync latency. Requests that arrive while a force is active are
     * coalesced into a monotonic follow-up boundary instead of spawning unbounded work. The force
     * uses a dedicated descriptor and executor; reservation growth and capture writes retain their
     * own descriptors and lanes. Close joins every requested boundary before truncate and the final
     * metadata fsync, so publication durability and asynchronous error propagation remain fail-closed.
     */
    private fun scheduleDurabilityCheckpointIfRequired() {
        throwIfDurabilityCheckpointFailed()
        if (frameCount != 1L &&
            frameCount - lastDurabilityCheckpointFrame < FLUSH_FRAME_INTERVAL
        ) {
            return
        }
        if (pendingReservationGrowth?.isDone == false) {
            checkpointsQueuedBehindReservationGrowth.incrementAndGet()
        }
        lastDurabilityCheckpointFrame = frameCount
        val requestedFrame = frameCount
        val firstFrameCandidate = if (requestedFrame == 1L) {
            Npv10FirstDurableFrameProof(
                sensorTimestampNs = firstFrameTimestampNs,
                frameCount = requestedFrame,
                committedBytes = committedBytes
            )
        } else {
            null
        }
        synchronized(durabilityStateLock) {
            durabilityRequestedThroughFrame = maxOf(
                durabilityRequestedThroughFrame,
                requestedFrame
            )
            if (firstFrameCandidate != null && firstFrameDurabilityCandidate == null) {
                firstFrameDurabilityCandidate = firstFrameCandidate
            }
            if (durabilityWorkerRunning) {
                coalescedDurabilityCheckpointRequests.incrementAndGet()
                return
            }
            durabilityWorkerRunning = true
            try {
                pendingDurabilityCheckpoint = durabilityExecutor.submit {
                    drainDurabilityCheckpointRequests()
                }
            } catch (failure: Throwable) {
                durabilityWorkerRunning = false
                durabilityCheckpointFailure = failure
                firstFrameDurabilityCandidate?.let {
                    firstDurableFrameProof.completeExceptionally(failure)
                }
                throw failure
            }
        }
    }

    private fun drainDurabilityCheckpointRequests() {
        while (true) {
            val requestedFrame = synchronized(durabilityStateLock) {
                durabilityRequestedThroughFrame
            }
            val checkpointStartedNs = System.nanoTime()
            try {
                Trace.beginSection("NPV10 checkpoint await committed")
                try {
                    durabilityStageTelemetry.awaitCommit {
                        directMappedCommitEngine?.awaitCommittedFrames(requestedFrame)
                    }
                } finally { Trace.endSection() }
                Trace.beginSection("NPV10 checkpoint filesystem force")
                try {
                    durabilityStageTelemetry.syncFile {
                        val engine = directMappedCommitEngine
                        if (engine != null) engine.forceCommittedFrames(requestedFrame)
                        else durabilityChannel.force(false)
                    }
                } finally { Trace.endSection() }
                val firstFrameProof = synchronized(durabilityStateLock) {
                    durabilityCompletedThroughFrame = maxOf(
                        durabilityCompletedThroughFrame,
                        requestedFrame
                    )
                    firstFrameDurabilityCandidate?.takeIf { candidate ->
                        requestedFrame >= candidate.frameCount
                    }
                }
                firstFrameProof?.let(firstDurableFrameProof::complete)
            } catch (failure: Throwable) {
                durabilityCheckpointFailure = failure
                synchronized(durabilityStateLock) {
                    durabilityWorkerRunning = false
                    firstFrameDurabilityCandidate?.let {
                        firstDurableFrameProof.completeExceptionally(failure)
                    }
                }
                throw failure
            } finally {
                val elapsedNs = elapsedNs(checkpointStartedNs)
                durabilityCheckpointCount.incrementAndGet()
                durabilityCheckpointTotalNs.addAndGet(elapsedNs)
                updateAtomicMax(durabilityCheckpointMaxNs, elapsedNs)
            }

            val complete = synchronized(durabilityStateLock) {
                if (durabilityRequestedThroughFrame <= requestedFrame) {
                    durabilityWorkerRunning = false
                    true
                } else {
                    false
                }
            }
            if (complete) return
        }
    }

    /** Null while frame-1 fsync is pending; completed success/failure has JVM-safe visibility. */
    internal fun firstDurableFrameResult(): Result<Npv10FirstDurableFrameProof>? =
        if (firstDurableFrameProof.isDone) {
            runCatching { firstDurableFrameProof.join() }
        } else {
            null
        }

    private fun awaitDurabilityCheckpoint() {
        while (true) {
            val checkpoint = synchronized(durabilityStateLock) {
                pendingDurabilityCheckpoint
            }
            if (checkpoint == null) break
            try {
                checkpoint.get()
            } catch (failure: ExecutionException) {
                throw failure.cause ?: failure
            }
            val complete = synchronized(durabilityStateLock) {
                !durabilityWorkerRunning &&
                    durabilityCompletedThroughFrame >= durabilityRequestedThroughFrame
            }
            if (complete) break
        }
        throwIfDurabilityCheckpointFailed()
    }

    private fun throwIfDurabilityCheckpointFailed() {
        durabilityCheckpointFailure?.let { failure ->
            throw IOException("NPV10 periodic durability checkpoint failed", failure)
        }
    }

    private fun checkedAdd(left: Long, right: Long): Long =
        try {
            Math.addExact(left, right)
        } catch (overflow: ArithmeticException) {
            throw IOException("NPV10 byte offset overflow", overflow)
        }

    private fun updateAtomicMax(target: AtomicLong, candidate: Long) {
        var observed = target.get()
        while (candidate > observed && !target.compareAndSet(observed, candidate)) {
            observed = target.get()
        }
    }

    private fun mergeFailure(existing: Throwable?, additional: Throwable): Throwable =
        existing?.also { failure ->
            if (failure !== additional) failure.addSuppressed(additional)
        } ?: additional

    private fun recordWriterStages(
        encodeAndCrcNs: Long,
        fallbackCrcNs: Long,
        fileWriteNs: Long,
        nativeFusedCrc: Boolean
    ) {
        stageFrameCount += 1L
        if (nativeFusedCrc) nativeFusedCrcFrameCount += 1L
        totalEncodeAndCrcTimeNs += encodeAndCrcNs
        maxEncodeAndCrcTimeNs = maxOf(maxEncodeAndCrcTimeNs, encodeAndCrcNs)
        totalFallbackCrcTimeNs += fallbackCrcNs
        maxFallbackCrcTimeNs = maxOf(maxFallbackCrcTimeNs, fallbackCrcNs)
        totalFileWriteTimeNs += fileWriteNs
        maxFileWriteTimeNs = maxOf(maxFileWriteTimeNs, fileWriteNs)
    }

    private fun averageMs(totalNs: Long, samples: Long): Double =
        if (samples <= 0L) 0.0 else totalNs.toDouble() / samples.toDouble() / 1_000_000.0

    private fun nanosToMs(nanos: Long): Double = nanos.toDouble() / 1_000_000.0

    private fun elapsedNs(startedNs: Long): Long =
        (System.nanoTime() - startedNs).coerceAtLeast(0L)

    private fun saturatingDurationAdd(left: Long, right: Long): Long {
        val safeLeft = left.coerceAtLeast(0L)
        val safeRight = right.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - safeLeft < safeRight) Long.MAX_VALUE else safeLeft + safeRight
    }

    private fun elapsedMs(startedNs: Long): Double =
        (System.nanoTime() - startedNs).coerceAtLeast(0L).toDouble() / 1_000_000.0

    private companion object {
        const val TAG = "Npv10FileWriter"
        const val FLUSH_FRAME_INTERVAL = 120L
        const val MAX_ZERO_WRITE_RETRIES = 8
        const val MAX_SYNCHRONOUS_GROWTH_ATTEMPTS = 4
    }
}
