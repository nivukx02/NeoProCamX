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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Phaser
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.TreeMap

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

private data class Npv10QualityCertificateWrite(
    val file: File?,
    val status: RecordingQualityEvidenceStatus,
    val persistenceFailure: String? = null
)

/** Completion published by either P010 packing worker and consumed strictly in ingress order. */
private class Npv10PreparedP010Commit(
    val sequence: Long,
    val sensorTimestampNs: Long,
    val writer: Npv10FileWriter,
    val frameSlots: Semaphore,
    val packet: Npv10PreparedP010Packet? = null,
    val preparationFailure: Throwable? = null
) {
    private val ownership = AtomicInteger(OWNER_PACKING)

    init {
        require((packet == null) != (preparationFailure == null)) {
            "Prepared P010 completion must contain exactly one packet or failure"
        }
    }

    fun claimForPublication(): Boolean =
        ownership.compareAndSet(OWNER_PACKING, OWNER_PUBLISHER)

    fun handOffToWriter(): Boolean =
        ownership.compareAndSet(OWNER_PUBLISHER, OWNER_WRITER)

    fun retireFromPacking(): Boolean =
        ownership.compareAndSet(OWNER_PACKING, OWNER_RETIRED)

    fun retireFromPublication(): Boolean =
        ownership.compareAndSet(OWNER_PUBLISHER, OWNER_RETIRED)

    fun retireFromWriter(): Boolean =
        ownership.compareAndSet(OWNER_WRITER, OWNER_RETIRED)

    private companion object {
        const val OWNER_PACKING = 0
        const val OWNER_PUBLISHER = 1
        const val OWNER_WRITER = 2
        const val OWNER_RETIRED = 3
    }
}

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

internal data class Npv10P010ImageAdmission(
    val imageOwnershipTransferred: Boolean,
    val writerAdmissionAccepted: Boolean,
    val sensorTimestampNs: Long,
    val ingressSequence: Long
) {
    companion object {
        fun ownedWithoutWriterAdmission(sensorTimestampNs: Long = 0L) = Npv10P010ImageAdmission(
            imageOwnershipTransferred = true,
            writerAdmissionAccepted = false,
            sensorTimestampNs = sensorTimestampNs,
            ingressSequence = -1L
        )

        fun accepted(sensorTimestampNs: Long, ingressSequence: Long) = Npv10P010ImageAdmission(
            imageOwnershipTransferred = true,
            writerAdmissionAccepted = true,
            sensorTimestampNs = sensorTimestampNs,
            ingressSequence = ingressSequence
        )
    }
}

internal data class Npv10ReservationProcessOwner(
    val rootPath: String,
    val ownerId: Long
)

/**
 * Process-wide ownership for private reservation inodes.
 *
 * Every scan, creation, recycle and adoption transition is serialized by one lock. This prevents
 * a newly constructed manager from classifying an overlapping manager's prepared extent as stale.
 * The coordinator intentionally owns no Android state, so its race and failure behavior remains
 * executable in deterministic host tests.
 */
internal class Npv10ReservationLeaseCoordinator {
    private data class RootState(
        val privateRoot: File,
        val owners: MutableSet<Long> = linkedSetOf(),
        val leasedPaths: MutableMap<String, Long> = linkedMapOf()
    )

    private val lock = Any()
    private val nextOwnerId = AtomicLong(0L)
    private val roots = linkedMapOf<String, RootState>()

    fun register(privateRoot: File): Npv10ReservationProcessOwner = synchronized(lock) {
        val rootPath = privateRoot.normalizedPath()
        val state = roots.getOrPut(rootPath) {
            RootState(privateRoot = privateRoot.absoluteFile)
        }
        val owner = Npv10ReservationProcessOwner(
            rootPath = rootPath,
            ownerId = nextOwnerId.incrementAndGet()
        )
        state.owners += owner.ownerId
        owner
    }

    fun cleanup(
        owner: Npv10ReservationProcessOwner,
        store: Npv10MasterStore
    ): Npv10ReservationCleanupResult = synchronized(lock) {
        val state = requireOwner(owner)
        store.deleteStaleReservations(
            excludedFiles = state.leasedPaths.keys.map(::File)
        )
    }

    fun createLeasedReservation(
        owner: Npv10ReservationProcessOwner,
        create: () -> File
    ): File = synchronized(lock) {
        val state = requireOwner(owner)
        create().also { file -> lease(state, owner, file) }
    }

    fun recycleAsLeasedReservation(
        owner: Npv10ReservationProcessOwner,
        recycle: () -> File?
    ): File? = synchronized(lock) {
        val state = requireOwner(owner)
        recycle()?.also { file -> lease(state, owner, file) }
    }

    fun <T> consumeLeasedReservation(
        owner: Npv10ReservationProcessOwner,
        file: File,
        consume: () -> T
    ): T = synchronized(lock) {
        val state = requireOwner(owner)
        val path = file.normalizedPath()
        check(state.leasedPaths[path] == owner.ownerId) {
            "NPV10 reservation is not leased by the consuming manager: $path"
        }
        consume().also { state.leasedPaths.remove(path) }
    }

    /** Release one owner path, then immediately run the same truthful bounded cleanup. */
    fun abandonLeasedReservation(
        owner: Npv10ReservationProcessOwner,
        file: File,
        store: Npv10MasterStore
    ): Npv10ReservationCleanupResult = synchronized(lock) {
        val state = requireOwner(owner)
        val path = file.normalizedPath()
        if (state.leasedPaths[path] == owner.ownerId) state.leasedPaths.remove(path)
        store.deleteStaleReservations(
            excludedFiles = state.leasedPaths.keys.map(::File)
        )
    }

    /** Called only after the owner's reservation executor has terminated. */
    fun unregister(
        owner: Npv10ReservationProcessOwner,
        store: Npv10MasterStore
    ): Npv10ReservationCleanupResult = synchronized(lock) {
        val state = requireOwner(owner)
        val otherOwnerLeases = state.leasedPaths
            .filterValues { leaseOwner -> leaseOwner != owner.ownerId }
            .keys
            .map(::File)
        // Delete this owner's retired paths before publishing owner retirement. If enumeration
        // itself throws, ownership remains intact and release() can safely retry later.
        val cleanup = store.deleteStaleReservations(
            excludedFiles = otherOwnerLeases
        )
        state.owners.remove(owner.ownerId)
        state.leasedPaths.entries.removeAll { (_, leaseOwner) -> leaseOwner == owner.ownerId }
        if (state.owners.isEmpty() && state.leasedPaths.isEmpty()) roots.remove(owner.rootPath)
        cleanup
    }

    internal fun leasedFiles(owner: Npv10ReservationProcessOwner): List<File> = synchronized(lock) {
        val state = requireOwner(owner)
        state.leasedPaths
            .filterValues { leaseOwner -> leaseOwner == owner.ownerId }
            .keys
            .map(::File)
    }

    private fun requireOwner(owner: Npv10ReservationProcessOwner): RootState {
        val state = roots[owner.rootPath]
            ?: error("NPV10 reservation owner root is no longer registered")
        check(owner.ownerId in state.owners) {
            "NPV10 reservation owner is no longer active"
        }
        return state
    }

    private fun lease(
        state: RootState,
        owner: Npv10ReservationProcessOwner,
        file: File
    ) {
        val reservationRoot = File(state.privateRoot, ".npv10_reservations").normalizedPath()
        val path = file.normalizedPath()
        require(file.parentFile?.normalizedPath() == reservationRoot) {
            "Refusing to lease a path outside the private NPV10 reservation directory: $path"
        }
        check(state.leasedPaths.putIfAbsent(path, owner.ownerId) == null) {
            "NPV10 reservation is already leased: $path"
        }
    }

    private fun File.normalizedPath(): String = absoluteFile.toPath().normalize().toString()
}

internal object Npv10ReservationProcessCoordinator {
    private val delegate = Npv10ReservationLeaseCoordinator()

    fun register(privateRoot: File): Npv10ReservationProcessOwner = delegate.register(privateRoot)

    fun cleanup(owner: Npv10ReservationProcessOwner, store: Npv10MasterStore) =
        delegate.cleanup(owner, store)

    fun createLeasedReservation(owner: Npv10ReservationProcessOwner, create: () -> File) =
        delegate.createLeasedReservation(owner, create)

    fun recycleAsLeasedReservation(owner: Npv10ReservationProcessOwner, recycle: () -> File?) =
        delegate.recycleAsLeasedReservation(owner, recycle)

    fun <T> consumeLeasedReservation(
        owner: Npv10ReservationProcessOwner,
        file: File,
        consume: () -> T
    ): T = delegate.consumeLeasedReservation(owner, file, consume)

    fun abandonLeasedReservation(
        owner: Npv10ReservationProcessOwner,
        file: File,
        store: Npv10MasterStore
    ) = delegate.abandonLeasedReservation(owner, file, store)

    fun unregister(owner: Npv10ReservationProcessOwner, store: Npv10MasterStore) =
        delegate.unregister(owner, store)
}

internal object Npv10StartupCleanupAdmission {
    fun await(
        initialCleanup: Future<Npv10ReservationCleanupResult>,
        retryCleanup: () -> Npv10ReservationCleanupResult
    ): Npv10ReservationCleanupResult {
        val initial = awaitUninterruptibly(initialCleanup)
        val admitted = if (initial.successful) initial else retryCleanup()
        check(admitted.successful) {
            "NPV10 stale-reservation cleanup failed: ${admitted.failureSummary()}"
        }
        return admitted
    }

    private fun <T> awaitUninterruptibly(future: Future<T>): T {
        var interrupted = false
        try {
            while (true) {
                try {
                    return future.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
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
                // DMC's bounded, pre-faulted ring decouples persistence from Camera2 ingress. Keep
                // the writer at DISPLAY so the URGENT_DISPLAY metadata/packing lanes can preempt a
                // long pwrite tail instead of losing a sensor frame; this never changes frame data.
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
                super.run()
            }
        }
    }
    private val packingWorkHintByThread = ThreadLocal<Npv10PackingWorkHint>()
    private val packingThreadIndex = AtomicInteger(0)
    private val p010PackingExecutor = ThreadPoolExecutor(
        P010_PACKING_DISPATCH_WORKERS,
        Npv10PackingOverlapPolicy.MAX_DISPATCH_WORKERS,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        { runnable ->
            object : Thread(
                runnable,
                "NPV10-Pack-${packingThreadIndex.incrementAndGet()}"
            ) {
                init {
                    priority = Thread.NORM_PRIORITY + 2
                    isDaemon = true
                }

                override fun run() {
                    runCatching {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                    }
                    // These two Java workers are persistent. Give each a stable ADPF identity
                    // for its full packing/lossless stage; the native row-helper session cannot
                    // report this work and deliberately does not include these thread IDs.
                    val workHint = Npv10PackingWorkHintAndroid.create(appContext)
                    packingWorkHintByThread.set(workHint)
                    try {
                        super.run()
                    } finally {
                        packingWorkHintByThread.remove()
                        workHint.close()
                    }
                }
            }
        }
    ).apply {
        // The native row engine already fans one frame across the submitter plus five persistent
        // urgent helpers. It intentionally serializes submissions because those helpers are shared.
        // Plain packed-10 keeps one dispatcher. A chunked-lossless session admits exactly one extra
        // prestarted dispatcher below: LZ4 runs after that shared row lock is released, so next-frame
        // packing can overlap current-frame compression without adding row helpers or frame leases.
        prestartAllCoreThreads()
    }
    private val reservationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NPV10-Reservation").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }
    private val reservationOwner = Npv10ReservationProcessCoordinator.register(privateRoot)
    private val pendingFrames = AtomicInteger(0)
    @Volatile private var activeMaxPendingFrames = Npv10InFlightBufferPolicy.MIN_PENDING_FRAMES
    @Volatile private var pendingFrameSlots = Semaphore(activeMaxPendingFrames, true)
    private val droppedFrames = AtomicInteger(0)
    private val nativeDirectP010Copies = AtomicLong(0L)
    private val earlyReleasedP010Images = AtomicLong(0L)
    private val earlyP010WorkerImageHoldNanos = AtomicLong(0L)
    private val maxEarlyP010WorkerImageHoldNanos = AtomicLong(0L)
    private val directBufferP010Copies = AtomicLong(0L)
    private val failedP010Copies = AtomicLong(0L)
    private val admissionWaitCount = AtomicLong(0L)
    private val admissionWaitNanos = AtomicLong(0L)
    private val admissionTimeouts = AtomicLong(0L)
    private val p010PacketBufferPool = ConcurrentLinkedQueue<Npv10NativeFrameBuffer>()
    private val niv99CanonicalP010BufferPool = ConcurrentLinkedQueue<Npv10NativeFrameBuffer>()
    private val canonicalP010FallbackBufferPool = ConcurrentLinkedQueue<Npv10NativeFrameBuffer>()
    private val canonicalP010FallbackSlots = Semaphore(
        Npv10InFlightBufferPolicy.MAX_CONCURRENT_CANONICAL_FALLBACKS,
        true
    )
    private val p010PackingInFlight = AtomicInteger(0)
    @Volatile private var p010PackingPhaser = Phaser(1)
    private val preparedP010CommitLock = Any()
    private val preparedP010Commits = TreeMap<Long, Npv10PreparedP010Commit>()
    private var nextP010IngressSequence = 0L
    private var nextP010CommitSequence = 0L
    private val ingressPreparationFrames = AtomicLong(0L)
    private val ingressPreparationNanos = AtomicLong(0L)
    private val maxIngressPreparationNanos = AtomicLong(0L)
    private val maxIngressPreparationTimestampNs = AtomicLong(0L)
    private val niv99CanonicalCopies = AtomicLong(0L)
    private val niv99CanonicalCopyNanos = AtomicLong(0L)
    private val maxNiv99CanonicalCopyNanos = AtomicLong(0L)
    private val maxNiv99CanonicalCopyTimestampNs = AtomicLong(0L)
    private val niv99TimingTelemetry = Npv10Niv99TimingTelemetry()
    private val niv99ResultByThread = ThreadLocal.withInitial(::Npv10Niv99FrameProcessingResult)
    private val asynchronousWriterFailures = AtomicLong(0L)
    private val producerBoundaryGateRef = AtomicReference<Npv10ProducerBoundaryGate?>(null)
    private val captureLedger = Npv10CaptureLedger()

    private val _state = MutableStateFlow<GpuRecordingState>(GpuRecordingState.Idle)
    val state: StateFlow<GpuRecordingState> = _state.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    var runtimeEvidenceMetadataProvider: (() -> Map<String, String>)? = null
    @Volatile var persistedFrameCommitListener: ((sensorTimestampNs: Long) -> Unit)? = null
    @Volatile private var configuredNiv99FrameProcessor: Npv10Niv99FrameProcessor? = null
    @Volatile private var activeNiv99FrameProcessor: Npv10Niv99FrameProcessor? = null
    @Volatile private var activeNiv99ColorEncoding: Npv10Niv99ColorEncoding? = null

    @Volatile private var writer: Npv10FileWriter? = null
    @Volatile private var outputFile: File? = null
    @Volatile private var activeConfig: Npv10SessionConfig? = null
    @Volatile private var startedElapsedMs: Long = 0L
    @Volatile private var durationTimeline = Npv10RecordingDurationTimeline()
    @Volatile private var recordingStartProcessCpuMs: Long = 0L
    @Volatile private var finalizedRecordingCpuUsagePercent: Float? = null
    @Volatile private var paused = false
    @Volatile private var finalizationInProgress = false
    @Volatile private var releaseRequested = false
    @Volatile private var releaseComplete = false
    @Volatile private var reservationOwnerReleased = false
    @Volatile private var lastStopDurabilityAuditPassed = false
    @Volatile private var lastStopDurabilityFailureReason: String? = null
    @Volatile private var preparedReservation: Npv10PreparedReservation? = null
    @Volatile private var reservationFuture: Future<Npv10PreparedReservation>? = null
    private var idleReservationPreparationBlockedByCompetingPipeline = false
    private val startupCleanupFuture: Future<Npv10ReservationCleanupResult>
    private val reservationGeneration = AtomicLong(0L)
    @Volatile var lastStopStageTimings: Npv10StopStageTimings = Npv10StopStageTimings()
        private set

    init {
        // A physical reservation can outlive this process because Android does not run release()
        // after SIGKILL, force-stop, or a test-runner process exit. Reclaim those app-private,
        // unpublished reservation inodes on every fresh manager lifetime, even when the user never
        // re-enters an NPV10-capable mode. Keep the scan on the same serial executor used to create
        // reservations: a subsequent prepare request is therefore ordered strictly after cleanup
        // and can never have its newly created extent mistaken for an orphan.
        startupCleanupFuture = reservationExecutor.submit<Npv10ReservationCleanupResult> {
            cleanupStaleReservations("startup")
        }
    }

    /** Serialize recovery scans with start/stop so a newly adopted extent cannot be inspected mid-start. */
    @Synchronized
    fun listMasters(): List<Npv10MasterClip> = store.listMasters(outputFile)

    @Synchronized
    fun writtenFrameCount(): Long = writer?.frameCount ?: 0L

    /** Frame-1 success is published only after the writer's asynchronous fsync checkpoint completes. */
    internal fun firstDurableFrameResult(
        minimumProducerTimestampNs: Long,
        firstSinkTimestampNs: Long
    ): Result<Npv10FirstDurableFrameProof>? {
        if (firstSinkTimestampNs <= 0L) return null
        return writer?.firstDurableFrameResult()?.map { proof ->
            Npv10FirstDurableFramePolicy.validate(
                proof = proof,
                minimumProducerTimestampNs = minimumProducerTimestampNs,
                firstSinkTimestampNs = firstSinkTimestampNs
            )
        }
    }

    /** Exact video timeline origin used by the independently scheduled synchronized audio lane. */
    internal fun firstDurableFrameSensorTimestampNs(): Long? =
        writer?.firstDurableFrameResult()?.getOrNull()?.sensorTimestampNs

    fun hasActiveSession(): Boolean = writer != null

    fun lastStopPassedDurabilityAudit(): Boolean = lastStopDurabilityAuditPassed

    fun lastStopDurabilityFailure(): String? = lastStopDurabilityFailureReason

    fun activeSourceBitDepth(): Int? = activeConfig?.sourceBitDepth

    fun activeFrameRate(): Int? = activeConfig?.fps

    @Synchronized
    internal fun setNiv99FrameProcessor(processor: Npv10Niv99FrameProcessor?) {
        check(writer == null) { "Cannot replace the NIV99 processor during an active NPV10 session" }
        configuredNiv99FrameProcessor = processor
    }

    internal fun niv99TimingSnapshot(): Npv10Niv99TimingSnapshot = niv99TimingTelemetry.snapshot()

    @Synchronized
    fun start(config: Npv10SessionConfig): File {
        check(!releaseRequested) { "Npv10RecordingManager cannot restart after terminal release" }
        check(writer == null && !finalizationInProgress) {
            "Previous NPV10 lossless master is still finalizing"
        }
        check(outputFile == null && producerBoundaryGateRef.get() == null) {
            "Previous NPV10 master lifecycle was not fully retired"
        }
        check(p010PackingInFlight.get() == 0) {
            "Previous NPV10 packing work is still active at session start"
        }
        synchronized(preparedP010CommitLock) {
            check(preparedP010Commits.isEmpty()) {
                "Previous NPV10 prepared commits were not drained"
            }
            nextP010IngressSequence = 0L
            nextP010CommitSequence = 0L
        }
        p010PackingPhaser = Phaser(1)
        // Validate capture truth before consuming a preallocated reservation or creating any file.
        require(config.exactSelectedResolution) {
            "Custom NPV10 recording requires the exact selected P010 resolution; " +
                "refusing a substituted ${config.width}x${config.height} capture stream"
        }
        require(config.sourceBitDepth >= Npv10ContainerPolicy.BIT_DEPTH) {
            "Custom NPV10 recording requires a native 10-bit source; no 8-bit fallback is permitted"
        }
        require(config.payloadFormat == Npv10PayloadFormat.P010_LE) {
            "Custom NPV10 recording requires canonical P010 camera data"
        }
        require(config.packetCodec == Npv10PacketCodec.PACKED_P010_10_LE_V1 ||
            config.packetCodec == Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        ) {
            "Custom NPV10 recording requires a supported bit-exact packed-10 packet codec"
        }
        require(config.packetCodec != Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2 ||
            config.directMappedCommitRequired
        ) {
            "Live chunked-lossless NPV10 recording requires asynchronous direct-mapped commits"
        }
        check(p010PackingExecutor.queue.isEmpty()) {
            "Previous NPV10 packing dispatches remain queued at session start"
        }
        p010PackingExecutor.corePoolSize = Npv10PackingOverlapPolicy.dispatchWorkers(config.packetCodec)
        p010PackingExecutor.prestartAllCoreThreads()
        Log.i(
            TAG,
            "NPV10 packing overlap codec=${config.packetCodec} " +
                "dispatchWorkers=${p010PackingExecutor.corePoolSize} " +
                "nativeRowEngine=single-owner orderedCommit=true"
        )
        check(Npv10FramePacker.prepareP010PackingExecutor()) {
            "Custom NPV10 recording requires the prewarmed native row-parallel P010 packer"
        }
        if (config.width == 4096 && config.height == 3072 && !config.niv99ProcessingRequired &&
            config.packetCodec == Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        ) {
            check(Npv10FramePacker.prepareP010LosslessChunkExecutor()) {
                "Native lossless chunk workspaces could not be prepared before camera admission"
            }
            Log.i(TAG, "NPV10 PARALLEL_FUSED_CHUNKS_READY captureHelpers=existing " +
                "sourceBits=unchanged chunkFormat=NLC2_V2 frameQueue=unchanged gpuRoute=NOT_ADMITTED")
        }
        if (config.directMappedCommitRequired) {
            Npv10DmcCadencePolicy.evaluate(config.width, config.height, config.fps).requireAccepted()
        }
        val startupNiv99Processor = if (config.niv99ProcessingRequired) {
            checkNotNull(configuredNiv99FrameProcessor) {
                "NIV99 is required for production NPV10 recording but no frame processor is connected"
            }.also { processor ->
                check(processor.isAvailable()) {
                    "NIV99 native P010 processing is unavailable; untreated recording is forbidden"
                }
            }
        } else {
            null
        }
        val startupNiv99Encoding = if (config.niv99ProcessingRequired) {
            checkNotNull(Npv10Niv99ColorEncodingPolicy.resolve(config)) {
                "NIV99 could not prove the P010 matrix, transfer, and limited-range encoding"
            }
        } else {
            null
        }
        // A direct shutter start is allowed without idle pre-arm, but it must not inspect free
        // space until process-death reservations have either been reclaimed or truthfully failed.
        awaitStartupReservationCleanup()
        val reservationPreparationAttempted =
            preparedReservation != null || reservationFuture != null
        val reservation = takePreparedReservation()
        if (reservation == null && reservationPreparationAttempted) {
            // A failed/cancelled preparation may have left an extent whose first bounded deletion
            // attempt failed. Retry at the final storage boundary and fail closed if it persists.
            val cleanup = cleanupStaleReservations("direct-start-final-admission")
            check(cleanup.successful) {
                "NPV10 direct start blocked by unreclaimed reservation: " +
                    cleanup.failureSummary()
            }
        }
        val file = reservation?.let { prepared ->
            try {
                Npv10ReservationProcessCoordinator.consumeLeasedReservation(
                    reservationOwner,
                    prepared.file
                ) {
                    store.adoptReservation(prepared, config)
                }
            } catch (failure: Throwable) {
                val cleanup = Npv10ReservationProcessCoordinator.abandonLeasedReservation(
                    reservationOwner,
                    prepared.file,
                    store
                )
                logReservationCleanup("failed-adoption", cleanup)
                throw failure
            }
        } ?: store.createMasterFile(config)
        // Protect the adopted inode before any storage query, preflight, writer initialization, or
        // buffer priming can yield/throw. listMasters() shares this monitor and also receives this
        // volatile exclusion, so it can never recover, truncate, or reclaim an in-flight start.
        outputFile = file
        var startupWriter: Npv10FileWriter? = null
        var startupGate: Npv10ProducerBoundaryGate? = null
        fun rollbackFailedStart() {
            startupGate?.let { gate ->
                producerBoundaryGateRef.compareAndSet(gate, null)
                gate.close()
            }
            if (writer === startupWriter) writer = null
            activeConfig = null
            activeNiv99FrameProcessor = null
            activeNiv99ColorEncoding = null
            releaseIdleP010PacketBuffers()
            releaseIdleNiv99CanonicalP010Buffers()
            runCatching { startupWriter?.close() }
            if (outputFile == file) outputFile = null
            Npv10VerifiedMasterPublication.invalidate(file)
            runCatching { file.delete() }
        }

        try {
            val availableStorageBytes = reservation?.let(::availableBytesIncludingReservation)
                ?: reservePrivateStorageForMaster()
            val preflight = Npv10QualityGate.preflight(
                config = config,
                privateRoot = privateRoot,
                availableBytes = availableStorageBytes
            )
            require(preflight.auditStatus == Npv10AuditStatus.PASS) {
                "NPV10 preflight failed: ${preflight.failureReasons.joinToString(separator = "; ")}"
            }
            val createdWriter = Npv10FileWriter(
                outputFile = file,
                config = config,
                availableStorageBytes = availableStorageBytes,
                preparedPreallocation = reservation?.preallocationResult
            )
            startupWriter = createdWriter
            require(
                createdWriter.preallocationResult.plan.enabled &&
                    createdWriter.preallocationResult.method == RecordingPreallocationMethod.POSIX_FALLOCATE
            ) {
                "NPV10 physical preallocation failed: " +
                    createdWriter.preallocationResult.failure.orEmpty()
            }
            val ordinaryPendingFrames =
                Npv10InFlightBufferPolicy.pendingFrames(
                    config.width,
                    config.height,
                    config.packetCodec
                )
            activeMaxPendingFrames = if (config.niv99ProcessingRequired) {
                Npv10InFlightBufferPolicy.niv99PendingFrames(
                    config.width,
                    config.height,
                    config.packetCodec
                )
            } else {
                ordinaryPendingFrames
            }
            pendingFrameSlots = Semaphore(activeMaxPendingFrames, true)
            if (config.directMappedCommitRequired) {
                releaseIdleP010PacketBuffers()
            } else {
                primeP010PacketBufferPool(config)
            }
            if (config.niv99ProcessingRequired) {
                primeNiv99CanonicalP010BufferPool(config)
            } else {
                releaseIdleNiv99CanonicalP010Buffers()
            }
            // Publish OPEN before the volatile writer reference. Teardown either sees no active writer
            // (and release()->stop() fails closed later) or can atomically poison this exact generation.
            val sessionProducerBoundaryGate = Npv10ProducerBoundaryGate().also { it.open() }
            startupGate = sessionProducerBoundaryGate
            check(producerBoundaryGateRef.compareAndSet(null, sessionProducerBoundaryGate)) {
                "Previous NPV10 producer-boundary generation was not retired"
            }
            writer = createdWriter
            activeConfig = config
            activeNiv99FrameProcessor = startupNiv99Processor
            activeNiv99ColorEncoding = startupNiv99Encoding
            pendingFrames.set(0)
            droppedFrames.set(0)
            nativeDirectP010Copies.set(0L)
            earlyReleasedP010Images.set(0L)
            earlyP010WorkerImageHoldNanos.set(0L)
            maxEarlyP010WorkerImageHoldNanos.set(0L)
            directBufferP010Copies.set(0L)
            failedP010Copies.set(0L)
            admissionWaitCount.set(0L)
            admissionWaitNanos.set(0L)
            admissionTimeouts.set(0L)
            asynchronousWriterFailures.set(0L)
            ingressPreparationFrames.set(0L)
            ingressPreparationNanos.set(0L)
            maxIngressPreparationNanos.set(0L)
            maxIngressPreparationTimestampNs.set(0L)
            niv99CanonicalCopies.set(0L)
            niv99CanonicalCopyNanos.set(0L)
            maxNiv99CanonicalCopyNanos.set(0L)
            maxNiv99CanonicalCopyTimestampNs.set(0L)
            niv99TimingTelemetry.reset()
            captureLedger.reset(config.fps)
            paused = false
            finalizationInProgress = false
            lastStopDurabilityAuditPassed = false
            lastStopDurabilityFailureReason = null
            lastStopStageTimings = Npv10StopStageTimings()
            startedElapsedMs = SystemClock.elapsedRealtime()
            durationTimeline = Npv10RecordingDurationPolicy.start(startedElapsedMs)
            recordingStartProcessCpuMs = Process.getElapsedCpuTime()
            finalizedRecordingCpuUsagePercent = null
            _duration.value = 0L
            Log.i(
                TAG,
                "NPV10 private master started: ${file.name} " +
                    "resolution=${config.width}x${config.height} fps=${config.fps} " +
                    "sourceFormat=${config.sourceFormat} sourceBitDepth=${config.sourceBitDepth} " +
                    "storageBitDepth=${config.storageBitDepth} payloadFormat=${config.payloadFormat} " +
                    "qualityTier=${preflight.qualityTier} bytesPerSecond=${preflight.bytesPerSecond} " +
                    "minimumFreeBytes=${preflight.minimumFreeBytes} exactSelected=${config.exactSelectedResolution} " +
                    "preallocation=${createdWriter.preallocationResult.method}:" +
                    "${createdWriter.preallocationResult.reservedBytes} " +
                    "niv99Required=${config.niv99ProcessingRequired} " +
                    "directMappedCommitRequired=${config.directMappedCommitRequired} " +
                    "niv99Encoding=${startupNiv99Encoding ?: "not-required"} " +
                    "path=${file.absolutePath}"
            )
            _state.value = GpuRecordingState.Recording
            return file
        } catch (failure: Throwable) {
            rollbackFailedStart()
            throw failure
        }
    }

    /**
     * Physically reserve the next strict master while the camera is idle, never on shutter UI work.
     *
     * This operation is deliberately idempotent. A usable reservation, including a preparation
     * still running on [reservationExecutor], survives transient camera-mode detachment and is
     * consumed only by [start]. Recreating the same 8 GiB extent during a live mode transition can
     * contend with Camera2 callback delivery even when fallocate itself runs off the UI thread.
     */
    @Synchronized
    fun preparePhysicalReservationAsync() {
        if (releaseRequested) return
        if (writer != null) return
        if (idleReservationPreparationBlockedByCompetingPipeline) return
        val stalePreparedFile = preparedReservation?.let { ready ->
            if (ready.isUsable()) return
            preparedReservation = null
            ready.file
        }
        if (reservationFuture != null) return
        val generation = reservationGeneration.get()
        reservationFuture = reservationExecutor.submit<Npv10PreparedReservation> {
            // File scans, deletion and temp-file creation can take hundreds of milliseconds on
            // mobile flash. Keep every storage operation on the reservation executor so entering
            // or leaving an NPV10-capable mode never stalls the camera/UI thread.
            stalePreparedFile?.let { stale ->
                logReservationCleanup(
                    "invalid-prepared",
                    Npv10ReservationProcessCoordinator.abandonLeasedReservation(
                        reservationOwner,
                        stale,
                        store
                    )
                )
            }
            val cleanup = cleanupStaleReservations("preparation")
            check(cleanup.successful) {
                "NPV10 reservation preparation cleanup failed: ${cleanup.failureSummary()}"
            }
            if (generation != reservationGeneration.get() || Thread.currentThread().isInterrupted) {
                throw InterruptedException("NPV10 reservation preparation superseded before creation")
            }
            val reservationFile = Npv10ReservationProcessCoordinator.createLeasedReservation(
                reservationOwner,
                store::createReservationFile
            )
            try {
                val availableStorageBytes = reservePrivateStorageForMaster()
                val reservation = Npv10PreparedReservationFactory.create(
                    reservationFile,
                    availableStorageBytes
                )
                if (generation != reservationGeneration.get() ||
                    Thread.currentThread().isInterrupted
                ) {
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
            } catch (failure: Throwable) {
                val failedPreparationCleanup =
                    Npv10ReservationProcessCoordinator.abandonLeasedReservation(
                        reservationOwner,
                        reservationFile,
                        store
                    )
                logReservationCleanup("failed-preparation", failedPreparationCleanup)
                throw failure
            }
        }
    }

    /**
     * Retires only the unused idle NPV10 extent before the RAW recorder claims physical storage.
     * Active writer/finalization ownership is deliberately fail-closed and can never be discarded.
     */
    @Synchronized
    fun retireIdlePhysicalReservationForCompetingPipeline() {
        check(!releaseRequested) { "NPV10 idle storage cannot be handed off after release" }
        check(writer == null && !finalizationInProgress && outputFile == null) {
            "NPV10 idle storage handoff refused while a recording lifecycle is active"
        }

        idleReservationPreparationBlockedByCompetingPipeline = true
        reservationGeneration.incrementAndGet()
        val pendingPreparation = reservationFuture
        reservationFuture = null
        val readyReservation = preparedReservation
        preparedReservation = null
        pendingPreparation?.cancel(true)

        StorageReservationHandoffBarrier.await(
            executor = reservationExecutor,
            timeoutMs = IDLE_STORAGE_HANDOFF_TIMEOUT_MS,
            ownerLabel = "NPV10"
        )

        val completedReservation = pendingPreparation
            ?.takeUnless { it.isCancelled }
            ?.let { completed ->
                runCatching { completed.get() }
                    .onFailure { failure ->
                        Log.w(TAG, "Completed NPV10 idle reservation could not be adopted for retirement", failure)
                    }
                    .getOrNull()
            }
        listOfNotNull(readyReservation, completedReservation)
            .distinctBy { it.file.absolutePath }
            .forEach { reservation ->
                val cleanup = Npv10ReservationProcessCoordinator.abandonLeasedReservation(
                    reservationOwner,
                    reservation.file,
                    store
                )
                logReservationCleanup("competing-pipeline-owned-retirement", cleanup)
                check(cleanup.successful) {
                    "Unable to retire idle NPV10 reservation: ${cleanup.failureSummary()}"
                }
            }
        val staleCleanup = cleanupStaleReservations("competing-pipeline-handoff")
        check(staleCleanup.successful) {
            "Unable to complete NPV10 idle storage handoff: ${staleCleanup.failureSummary()}"
        }
        Log.i(TAG, "NPV10 idle physical reservation retired for competing recorder")
    }

    /** Re-opens idle NPV10 preparation only when a P010-backed mode owns storage again. */
    @Synchronized
    fun allowIdlePhysicalReservationPreparation() {
        check(!releaseRequested) { "NPV10 idle storage preparation cannot resume after release" }
        idleReservationPreparationBlockedByCompetingPipeline = false
    }

    /** Terminal owner cleanup. Transient camera-mode changes must never call this path. */
    @Synchronized
    private fun discardPreparedPhysicalReservation() {
        reservationGeneration.incrementAndGet()
        reservationFuture?.cancel(true)
        reservationFuture = null
        preparedReservation?.let { ready ->
            val cleanup = Npv10ReservationProcessCoordinator.abandonLeasedReservation(
                reservationOwner,
                ready.file,
                store
            )
            logReservationCleanup("terminal-prepared-discard", cleanup)
            check(cleanup.successful) {
                "Unable to retire prepared NPV10 reservation: ${cleanup.failureSummary()}"
            }
        }
        preparedReservation = null
        val cleanup = cleanupStaleReservations("terminal-discard")
        check(cleanup.successful) {
            "Unable to retire stale NPV10 reservations: ${cleanup.failureSummary()}"
        }
    }

    private fun takePreparedReservation(): Npv10PreparedReservation? {
        preparedReservation?.let { ready ->
            preparedReservation = null
            if (ready.isUsable()) return ready
            logReservationCleanup(
                "invalid-recycled-reservation",
                Npv10ReservationProcessCoordinator.abandonLeasedReservation(
                    reservationOwner,
                    ready.file,
                    store
                )
            )
        }
        val future = reservationFuture ?: return null
        reservationFuture = null
        val prepared = runCatching { future.get() }
            .onFailure { failure ->
                Log.w(TAG, "Idle NPV10 reservation was unavailable; reserving synchronously", failure)
            }
            .getOrNull()
            ?: return null
        if (prepared.isUsable()) return prepared
        logReservationCleanup(
            "invalid-future-reservation",
            Npv10ReservationProcessCoordinator.abandonLeasedReservation(
                reservationOwner,
                prepared.file,
                store
            )
        )
        return null
    }

    private fun awaitStartupReservationCleanup() {
        Npv10StartupCleanupAdmission.await(startupCleanupFuture) {
            cleanupStaleReservations("direct-start-retry")
        }
    }

    private fun cleanupStaleReservations(reason: String): Npv10ReservationCleanupResult =
        Npv10ReservationProcessCoordinator.cleanup(reservationOwner, store).also { result ->
            logReservationCleanup(reason, result)
        }

    private fun logReservationCleanup(
        reason: String,
        result: Npv10ReservationCleanupResult
    ) {
        if (result.successful) {
            Log.i(
                TAG,
                "NPV10 $reason stale-reservation cleanup complete " +
                    "attempted=${result.attempted.size} deleted=${result.deleted.size} " +
                    "liveExcluded=${result.excluded.size}"
            )
        } else {
            Log.e(
                TAG,
                "NPV10 $reason stale-reservation cleanup incomplete; retry remains required: " +
                    result.failureSummary()
            )
        }
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
        captureLedger.recordIngress(frame.timestamp)
        if (frame.timestamp <= 0L) {
            failInputContractViolation("NPV10 rejected non-positive camera timestamp=${frame.timestamp}")
            return
        }
        val frameSlots = pendingFrameSlots
        if (!acquirePendingFrameSlot(frameSlots)) {
            droppedFrames.incrementAndGet()
            failStrictMasterBacklog()
            return
        }
        if (_state.value !is GpuRecordingState.Recording || paused || writer !== activeWriter) {
            frameSlots.release()
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
                    val payloadBytes = activeConfig?.let { config ->
                        Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
                            config.width,
                            config.height,
                            config.payloadFormat
                        )
                    } ?: 0L
                    captureLedger.recordCommitted(frame.timestamp, payloadBytes)
                    runCatching { persistedFrameCommitListener?.invoke(frame.timestamp) }
                        .onFailure { Log.w(TAG, "NPV10 frame-commit listener failed", it) }
                    updateActiveRecordingDuration()
                } catch (t: Throwable) {
                    asynchronousWriterFailures.incrementAndGet()
                    droppedFrames.incrementAndGet()
                    Log.e(TAG, "NPV10 frame write failed", t)
                    _state.value = GpuRecordingState.Error(t.message ?: "NPV10 frame write failed")
                } finally {
                    pendingFrames.decrementAndGet()
                    frameSlots.release()
                }
            }
            captureLedger.recordAccepted(frame.timestamp)
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
    // Packing and durable writes run on their own bounded executors; this monitor protects only
    // session ownership and never performs full-frame work on the Camera2 callback thread.
    fun enqueueP010Image(image: Image): Boolean =
        enqueueP010ImageWithAdmission(image).imageOwnershipTransferred

    @Synchronized
    internal fun enqueueP010ImageWithAdmission(image: Image): Npv10P010ImageAdmission {
        val activeWriter = writer
        val sessionConfig = activeConfig
        if (activeWriter == null ||
            sessionConfig == null ||
            _state.value !is GpuRecordingState.Recording ||
            paused
        ) {
            closeImageSafely(image)
            val boundaryState = producerBoundaryGateRef.get()?.state
            if (activeWriter != null &&
                (boundaryState == Npv10ProducerBoundaryGate.State.ESTABLISHED ||
                    boundaryState == Npv10ProducerBoundaryGate.State.SEALED_ESTABLISHED)
            ) {
                poisonStopBoundaryFailure(
                    "NPV10 received a P010 Image after the exact producer boundary was established"
                )
            }
            return Npv10P010ImageAdmission.ownedWithoutWriterAdmission()
        }
        val sensorTimestampNs = runCatching { image.timestamp }.getOrDefault(0L)
        captureLedger.recordIngress(sensorTimestampNs)
        if (sensorTimestampNs <= 0L) {
            closeImageSafely(image)
            failInputContractViolation(
                "NPV10 rejected non-positive P010 camera timestamp=$sensorTimestampNs"
            )
            return Npv10P010ImageAdmission.ownedWithoutWriterAdmission(sensorTimestampNs)
        }
        val frameSlots = pendingFrameSlots
        if (!acquirePendingFrameSlot(frameSlots)) {
            droppedFrames.incrementAndGet()
            closeImageSafely(image)
            failStrictMasterBacklog()
            return Npv10P010ImageAdmission.ownedWithoutWriterAdmission(sensorTimestampNs)
        }
        if (_state.value !is GpuRecordingState.Recording || paused || writer !== activeWriter) {
            frameSlots.release()
            closeImageSafely(image)
            return Npv10P010ImageAdmission.ownedWithoutWriterAdmission(sensorTimestampNs)
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
            return Npv10P010ImageAdmission.ownedWithoutWriterAdmission(sensorTimestampNs)
        }
        // The bounded frame leases absorb Camera2 bursts. The native row engine remains single-owner
        // across six CPU lanes; chunked-lossless sessions overlap its work with one other frame's
        // independent LZ4 stage. Stage 2 publishes completions into an ingress-sequence reorder buffer,
        // then submits only contiguous packets to the durable writer, preserving bit-exact order.
        val sequence = synchronized(preparedP010CommitLock) { nextP010IngressSequence++ }
        val packingPhaser = markP010PackingStarted()
        return try {
            p010PackingExecutor.execute {
                var unownedPacket: Npv10PreparedP010Packet? = null
                var completion: Npv10PreparedP010Commit? = null
                try {
                    completion = try {
                        unownedPacket = prepareP010ImageForAsyncWrite(
                            image = image,
                            config = sessionConfig,
                            sequence = sequence,
                            activeWriter = activeWriter
                        )
                        Npv10PreparedP010Commit(
                            sequence = sequence,
                            sensorTimestampNs = sensorTimestampNs,
                            writer = activeWriter,
                            frameSlots = frameSlots,
                            packet = requireNotNull(unownedPacket)
                        ).also { unownedPacket = null }
                    } catch (failure: Throwable) {
                        unownedPacket?.let { packet ->
                            runCatching { releaseUnpublishedP010Packet(packet, activeWriter) }
                                .onFailure(::poisonP010CommitPipeline)
                        }
                        unownedPacket = null
                        Npv10PreparedP010Commit(
                            sequence = sequence,
                            sensorTimestampNs = sensorTimestampNs,
                            writer = activeWriter,
                            frameSlots = frameSlots,
                            preparationFailure = failure
                        )
                    }
                    publishPreparedP010Commit(requireNotNull(completion))
                } catch (unexpected: Throwable) {
                    // Even allocation/invariant failures must not strand the ImageReader or Phaser.
                    unownedPacket?.let { packet ->
                        runCatching { releaseUnpublishedP010Packet(packet, activeWriter) }
                            .onFailure(::poisonP010CommitPipeline)
                    }
                    val unpublished = completion
                    if (unpublished == null) {
                        retireUnmaterializedP010Admission(frameSlots, unexpected)
                    } else if (unpublished.retireFromPacking()) {
                        releasePreparedP010Resources(unpublished)
                        droppedFrames.incrementAndGet()
                        poisonP010CommitPipeline(unexpected)
                    } else {
                        // Publication already owns the completion; it is the only path allowed to
                        // release the packet/permit. Poisoning prevents any later durable writes.
                        poisonP010CommitPipeline(unexpected)
                    }
                } finally {
                    markP010PackingFinished(packingPhaser)
                }
            }
            captureLedger.recordAccepted(sensorTimestampNs)
            Npv10P010ImageAdmission.accepted(sensorTimestampNs, sequence)
        } catch (t: RejectedExecutionException) {
            closeImageSafely(image)
            try {
                // Preserve this reserved sequence as an ordered failure completion. Retiring it
                // directly would strand every later out-of-order completion behind a permanent gap.
                val tombstone = runCatching {
                    Npv10PreparedP010Commit(
                        sequence = sequence,
                        sensorTimestampNs = sensorTimestampNs,
                        writer = activeWriter,
                        frameSlots = frameSlots,
                        preparationFailure = t
                    )
                }.getOrNull()
                if (tombstone != null) {
                    publishPreparedP010Commit(tombstone)
                } else {
                    retireUnmaterializedP010Admission(frameSlots, t)
                }
            } finally {
                markP010PackingFinished(packingPhaser)
            }
            Log.e(TAG, "NPV10 packing executor rejected Camera2 Image", t)
            _state.value = GpuRecordingState.Error(
                t.message ?: "NPV10 packing executor rejected Camera2 Image"
            )
            Npv10P010ImageAdmission.ownedWithoutWriterAdmission(sensorTimestampNs)
        } catch (failure: Throwable) {
            // Thread creation and executor internals can fail before a Runnable is accepted. Close
            // and retire the admission directly; the poisoned packing drain reconciles its reserved
            // sequence after every already-running worker returns.
            closeImageSafely(image)
            try {
                retireUnmaterializedP010Admission(frameSlots, failure)
            } finally {
                markP010PackingFinished(packingPhaser)
            }
            Npv10P010ImageAdmission.ownedWithoutWriterAdmission(sensorTimestampNs)
        }
    }

    /** Submits every newly contiguous completion while holding the ordering lock. */
    private fun publishPreparedP010Commit(completion: Npv10PreparedP010Commit) {
        if (!completion.claimForPublication()) {
            poisonP010CommitPipeline(
                IllegalStateException(
                    "NPV10 completion sequence=${completion.sequence} was published more than once"
                )
            )
            return
        }
        var readyInTransfer: Npv10PreparedP010Commit? = null
        try {
            synchronized(preparedP010CommitLock) {
                if (completion.sequence < nextP010CommitSequence ||
                    preparedP010Commits.containsKey(completion.sequence)
                ) {
                    retireInvalidPreparedP010Commit(
                        completion,
                        "Duplicate NPV10 prepared completion sequence=${completion.sequence}"
                    )
                    return
                }
                preparedP010Commits[completion.sequence] = completion
                while (true) {
                    val ready = preparedP010Commits.remove(nextP010CommitSequence) ?: break
                    nextP010CommitSequence += 1L
                    readyInTransfer = ready
                    try {
                        writerExecutor.execute {
                            if (ready.handOffToWriter()) {
                                commitPreparedP010InOrder(ready)
                            } else {
                                poisonP010CommitPipeline(
                                    IllegalStateException(
                                        "NPV10 completion sequence=${ready.sequence} " +
                                            "lost publisher ownership before writer start"
                                    )
                                )
                            }
                        }
                    } catch (rejection: RejectedExecutionException) {
                        retireRejectedPreparedP010Commit(ready, rejection)
                    }
                    readyInTransfer = null
                }
            }
        } catch (failure: Throwable) {
            // Fail closed without abandoning any packet that was still owned by the publisher.
            // Tasks already accepted by the single writer keep their ownership and merely observe
            // the poison gate; everything else is retired exactly once here.
            poisonP010CommitPipeline(failure)
            val stranded = synchronized(preparedP010CommitLock) {
                buildList {
                    readyInTransfer?.let(::add)
                    add(completion)
                    addAll(preparedP010Commits.values)
                }.also {
                    preparedP010Commits.clear()
                    nextP010CommitSequence = maxOf(
                        nextP010CommitSequence,
                        nextP010IngressSequence
                    )
                }
            }
            stranded.forEach { candidate ->
                // A task that reached OWNER_WRITER may already be reading its packet. Never steal
                // writer ownership here; the writer task is solely responsible for retiring it.
                if (candidate.retireFromPublication()) {
                    releasePreparedP010Resources(candidate)
                    droppedFrames.incrementAndGet()
                }
            }
        }
    }

    private fun commitPreparedP010InOrder(completion: Npv10PreparedP010Commit) {
        val packet = completion.packet
        var persistenceCommitted = false
        try {
            completion.preparationFailure?.let { failure ->
                droppedFrames.incrementAndGet()
                recordP010Copy(Npv10FramePacker.P010CopyPath.FAILED)
                throw IllegalStateException(
                    "NPV10 asynchronous packed-10 ingress preparation failed",
                    failure
                )
            }
            val preparedPacket = requireNotNull(packet)
            recordP010Copy(preparedPacket.copyPath)
            if (producerBoundaryGateRef.get()?.poisonFailure != null) {
                droppedFrames.incrementAndGet()
                return
            }
            if (activeConfig?.niv99ProcessingRequired == true) {
                require(preparedPacket.niv99Applied) {
                    "Untreated P010 packet reached a NIV99-required writer session"
                }
                require(preparedPacket.niv99CameraId.isNotBlank() &&
                    preparedPacket.niv99SessionGeneration >= 0L &&
                    preparedPacket.niv99FrameNumber >= 0L &&
                    preparedPacket.timestampNs == completion.sensorTimestampNs
                ) { "NIV99 packet provenance is incomplete or mismatched at persistence" }
            }
            completion.writer.writePreparedP010Packet(preparedPacket)
            persistenceCommitted = true
            if (preparedPacket.niv99Applied) niv99TimingTelemetry.recordPersisted()
            captureLedger.recordCommitted(
                completion.sensorTimestampNs,
                preparedPacket.decodedPayloadBytes.toLong()
            )
            runCatching { persistedFrameCommitListener?.invoke(completion.sensorTimestampNs) }
                .onFailure { Log.w(TAG, "NPV10 frame-commit listener failed", it) }
            updateActiveRecordingDuration()
        } catch (failure: Throwable) {
            if (completion.preparationFailure == null) {
                asynchronousWriterFailures.incrementAndGet()
                droppedFrames.incrementAndGet()
            }
            poisonP010CommitPipeline(failure)
            Log.e(TAG, "NPV10 ordered prepared packet commit failed", failure)
        } finally {
            if (completion.retireFromWriter()) {
                releasePreparedP010Resources(
                    completion,
                    abortDirectMappedFrame = !persistenceCommitted
                )
            } else {
                poisonP010CommitPipeline(
                    IllegalStateException(
                        "NPV10 writer completion sequence=${completion.sequence} retired more than once"
                    )
                )
            }
        }
    }

    private fun retireRejectedPreparedP010Commit(
        completion: Npv10PreparedP010Commit,
        rejection: RejectedExecutionException
    ) {
        if (completion.retireFromPublication()) {
            releasePreparedP010Resources(completion)
            droppedFrames.incrementAndGet()
        }
        poisonP010CommitPipeline(rejection)
        Log.e(TAG, "NPV10 writer executor rejected ordered prepared packet", rejection)
    }

    private fun retireInvalidPreparedP010Commit(
        completion: Npv10PreparedP010Commit,
        message: String
    ) {
        if (completion.retireFromPublication()) {
            releasePreparedP010Resources(completion)
            droppedFrames.incrementAndGet()
        }
        poisonP010CommitPipeline(IllegalStateException(message))
    }

    private fun releasePreparedP010Resources(
        completion: Npv10PreparedP010Commit,
        abortDirectMappedFrame: Boolean = true
    ) {
        val packetReleaseFailure = runCatching {
            completion.packet?.let { packet ->
                packet.packetLease?.let(::releaseP010PacketBuffer)
                if (abortDirectMappedFrame && packet.directMapped) {
                    completion.writer.abortDirectMappedFrame(packet.directMappedFrameIndex)
                }
            }
        }.exceptionOrNull()
        val remaining = pendingFrames.decrementAndGet()
        completion.frameSlots.release()
        if (remaining < 0) {
            poisonP010CommitPipeline(
                IllegalStateException("NPV10 pending-frame ownership underflow")
            )
        }
        packetReleaseFailure?.let(::poisonP010CommitPipeline)
    }

    private fun releaseUnpublishedP010Packet(
        packet: Npv10PreparedP010Packet,
        packetWriter: Npv10FileWriter
    ) {
        packet.packetLease?.let(::releaseP010PacketBuffer)
        if (packet.directMapped) {
            packetWriter.abortDirectMappedFrame(packet.directMappedFrameIndex)
        }
    }

    private fun retireUnmaterializedP010Admission(
        frameSlots: Semaphore,
        failure: Throwable
    ) {
        val remaining = pendingFrames.decrementAndGet()
        frameSlots.release()
        droppedFrames.incrementAndGet()
        val terminal = if (remaining < 0) {
            IllegalStateException("NPV10 unmaterialized admission underflow", failure)
        } else {
            failure
        }
        poisonP010CommitPipeline(terminal)
    }

    private fun poisonP010CommitPipeline(failure: Throwable) {
        val sessionGate = producerBoundaryGateRef.get() ?: return
        if (sessionGate.poison() && sessionGate.recordPoisonFailure(failure)) {
            val message = failure.message ?: "NPV10 ordered P010 commit pipeline failed"
            Log.e(TAG, message, failure)
            if (producerBoundaryGateRef.get() === sessionGate) {
                _state.value = GpuRecordingState.Error(message)
            }
        }
    }

    private fun markP010PackingStarted(): Phaser {
        val phaser = p010PackingPhaser
        phaser.register()
        p010PackingInFlight.incrementAndGet()
        return phaser
    }

    private fun markP010PackingFinished(phaser: Phaser) {
        val remaining = p010PackingInFlight.decrementAndGet()
        try {
            check(remaining >= 0) { "NPV10 P010 packing in-flight count underflow" }
        } finally {
            phaser.arriveAndDeregister()
        }
    }

    /** Uninterruptible by design: an accepted Camera2 Image must always close before reader rebuild. */
    private fun awaitP010PackingDrain() {
        p010PackingPhaser.arriveAndAwaitAdvance()
        check(p010PackingInFlight.get() == 0) {
            "NPV10 packing phase advanced with ${p010PackingInFlight.get()} Image(s) still owned"
        }
        if (producerBoundaryGateRef.get()?.poisonFailure != null) {
            // An unmaterialized completion can leave a reserved sequence with no map entry. Once all
            // workers have returned there can be no missing future publisher, so retire every
            // publisher-owned tail and advance the sequence boundary atomically. Writer-owned tasks
            // remain ahead of the subsequent marker and retire themselves through the poison gate.
            val stranded = synchronized(preparedP010CommitLock) {
                preparedP010Commits.values.toList().also {
                    preparedP010Commits.clear()
                    nextP010CommitSequence = nextP010IngressSequence
                }
            }
            stranded.forEach { completion ->
                if (completion.retireFromPublication()) {
                    releasePreparedP010Resources(completion)
                    droppedFrames.incrementAndGet()
                }
            }
        }
        synchronized(preparedP010CommitLock) {
            check(preparedP010Commits.isEmpty()) {
                "NPV10 packing drained with ${preparedP010Commits.size} completion(s) still unordered"
            }
            check(nextP010CommitSequence == nextP010IngressSequence) {
                "NPV10 packing sequence mismatch committed=$nextP010CommitSequence " +
                    "ingress=$nextP010IngressSequence"
            }
        }
    }

    private fun prepareP010ImageForAsyncWrite(
        image: Image,
        config: Npv10SessionConfig,
        sequence: Long,
        activeWriter: Npv10FileWriter
    ): Npv10PreparedP010Packet {
        var imageClosed = false
        try {
            validateRuntimeP010Image(image, config)
            val workHint = packingWorkHintByThread.get()
            workHint?.prepareFrame(config.fps)
            val timestampNs = runCatching { image.timestamp }.getOrDefault(0L)
            val imageWidth = image.width
            val imageHeight = image.height
            val decodedPayloadBytes = Npv10ContainerPolicy
                .estimatedPayloadBytesPerFrame(imageWidth, imageHeight, config.payloadFormat)
                .toInt()
            var encodedBytes = Npv10PacketCodecEngine.storedBytes(
                decodedPayloadBytes.toLong(),
                config.packetCodec
            ).toInt()
            val packetLease = if (config.directMappedCommitRequired) {
                null
            } else {
                acquireP010PacketBuffer(encodedBytes)
            }
            var directMappedFrameBegun = false
            try {
                val preparationStartedNs = System.nanoTime()
                val copyPath: Npv10FramePacker.P010CopyPath
                val crcPath: Npv10PreparedP010CrcPath
                val fallbackCrcTimeNs: Long
                val crc32: Int
                var niv99Applied = false
                var niv99CameraId = ""
                var niv99SessionGeneration = -1L
                var niv99FrameNumber = -1L
                var niv99ProcessingTimeNs = 0L
                if (config.niv99ProcessingRequired) {
                    val processor = checkNotNull(activeNiv99FrameProcessor) {
                        "Active NPV10 session lost its required NIV99 processor"
                    }
                    val encoding = checkNotNull(activeNiv99ColorEncoding) {
                        "Active NPV10 session lost its proven NIV99 color encoding"
                    }
                    val processingResult = checkNotNull(niv99ResultByThread.get()) {
                        "NIV99 processing result ThreadLocal failed to initialize"
                    }.clear()
                    val directMappedCommit = if (config.directMappedCommitRequired) {
                        activeWriter.beginDirectMappedFrame(sequence, encodedBytes).also {
                            directMappedFrameBegun = true
                        }
                    } else {
                        null
                    }

                    // Most frames reach a packing worker after their exact CaptureResult has
                    // already published. Process those Camera2 planes directly into DMC, removing
                    // the 37.7 MiB canonical copy and its second DRAM traversal. If metadata still
                    // leads the Image, do not wait while owning an ImageReader slot: copy once,
                    // close immediately, and retain the existing bounded deferred exact join.
                    var processed = false
                    if (directMappedCommit != null) {
                        processed = try {
                            processor.processImageAndPackDirectMappedIfTuningReady(
                                image = image,
                                sensorTimestampNs = timestampNs,
                                encoding = encoding,
                                directMappedCommit = directMappedCommit,
                                frameIndex = sequence,
                                output = processingResult
                            )
                        } catch (failure: Throwable) {
                            niv99TimingTelemetry.recordFailure()
                            throw failure
                        }
                        if (processed) {
                            closeImageSafely(image)
                            imageClosed = true
                        } else if (processingResult.failure !=
                            Npv10Niv99ProcessingFailure.METADATA_NOT_FOUND
                        ) {
                            niv99TimingTelemetry.recordFailure()
                            error(
                                "Required direct-image NIV99 processing failed: " +
                                    "reason=${processingResult.failure} timestampNs=$timestampNs"
                            )
                        }
                    }

                    if (!processed) {
                        val canonicalLease = acquireNiv99CanonicalP010Buffer(decodedPayloadBytes)
                        try {
                            val canonicalBuffer = canonicalLease.buffer
                            val copyStartedNs = System.nanoTime()
                            var canonicalBytesCopied = 0
                            try {
                                canonicalBytesCopied =
                                    Npv10FramePacker.copyP010ImageIntoCanonicalDirectBuffer(
                                        image = image,
                                        target = canonicalBuffer
                                    )
                            } finally {
                                closeImageSafely(image)
                                imageClosed = true
                                recordNiv99CanonicalCopy(
                                    timestampNs,
                                    (System.nanoTime() - copyStartedNs).coerceAtLeast(0L)
                                )
                            }
                            if (canonicalBytesCopied != decodedPayloadBytes) {
                                niv99TimingTelemetry.recordFailure()
                                error(
                                    "NIV99 canonical P010 copy produced $canonicalBytesCopied bytes, " +
                                        "expected $decodedPayloadBytes timestampNs=$timestampNs"
                                )
                            }
                            processed = try {
                                if (directMappedCommit != null) {
                                    processor.processCanonicalAndPackDirectMapped(
                                        canonicalP010 = canonicalBuffer,
                                        width = imageWidth,
                                        height = imageHeight,
                                        sensorTimestampNs = timestampNs,
                                        encoding = encoding,
                                        directMappedCommit = directMappedCommit,
                                        frameIndex = sequence,
                                        output = processingResult
                                    )
                                } else {
                                    processor.processCanonicalAndPack(
                                        canonicalP010 = canonicalBuffer,
                                        width = imageWidth,
                                        height = imageHeight,
                                        sensorTimestampNs = timestampNs,
                                        encoding = encoding,
                                        target = checkNotNull(packetLease).buffer,
                                        output = processingResult
                                    )
                                }
                            } catch (failure: Throwable) {
                                niv99TimingTelemetry.recordFailure()
                                throw failure
                            }
                        } finally {
                            releaseNiv99CanonicalP010Buffer(canonicalLease)
                        }
                    }
                    if (!processed) {
                        niv99TimingTelemetry.recordFailure()
                        error(
                            "Required NIV99 P010 processing failed: " +
                                "reason=${processingResult.failure} timestampNs=$timestampNs"
                        )
                    }
                    try {
                        if (config.directMappedCommitRequired) {
                            require(Npv10PacketCodecEngine.isValidStoredBytes(
                                actualBytes = processingResult.bytesPacked.toLong(),
                                decodedBytes = decodedPayloadBytes.toLong(),
                                codec = config.packetCodec
                            )) {
                                "NIV99 produced invalid variable packet size " +
                                    "${processingResult.bytesPacked} for ${config.packetCodec}"
                            }
                            encodedBytes = processingResult.bytesPacked
                        } else {
                            require(processingResult.bytesPacked == encodedBytes) {
                                "NIV99 packed ${processingResult.bytesPacked} bytes, expected $encodedBytes"
                            }
                        }
                        require(processingResult.sensorTimestampNs == timestampNs) {
                            "NIV99 provenance timestamp ${processingResult.sensorTimestampNs} " +
                                "does not match P010 timestamp $timestampNs"
                        }
                        require(processingResult.cameraId.isNotBlank() &&
                            processingResult.sessionGeneration >= 0L &&
                            processingResult.frameNumber >= 0L
                        ) { "NIV99 returned incomplete frame provenance" }
                    } catch (failure: Throwable) {
                        niv99TimingTelemetry.recordFailure()
                        throw failure
                    }
                    copyPath = Npv10FramePacker.P010CopyPath.NATIVE_DIRECT
                    crcPath = if (config.directMappedCommitRequired) {
                        Npv10PreparedP010CrcPath.NIV99_DIRECT_MAPPED_FUSED_NATIVE
                    } else {
                        Npv10PreparedP010CrcPath.NIV99_CANONICAL_BUFFER_FUSED_NATIVE
                    }
                    fallbackCrcTimeNs = 0L
                    crc32 = processingResult.crc32
                    niv99Applied = true
                    niv99CameraId = processingResult.cameraId
                    niv99SessionGeneration = processingResult.sessionGeneration
                    niv99FrameNumber = processingResult.frameNumber
                    niv99ProcessingTimeNs = processingResult.processingTimeNs
                    niv99TimingTelemetry.recordProcessed(niv99ProcessingTimeNs)
                } else {
                    if (config.directMappedCommitRequired) {
                        val directMappedCommit = activeWriter.beginDirectMappedFrame(
                            sequence,
                            encodedBytes
                        ).also { directMappedFrameBegun = true }
                        val preparedPayload = Npv10FramePacker
                            .packP010ImageToDirectMappedTenBitWithCrc(
                                image = image,
                                directMappedCommit = directMappedCommit,
                                frameIndex = sequence,
                                onPlanesCopied = Runnable {
                                    // JNI invokes this on the same packing worker, after all plane
                                    // reads and before lossless encoding. Capture metadata above;
                                    // never access Image/Plane/HardwareBuffer after this boundary.
                                    check(!imageClosed) { "P010 source ownership released twice" }
                                    imageClosed = true
                                    image.close()
                                    recordEarlyP010ImageRelease(
                                        (System.nanoTime() - preparationStartedNs).coerceAtLeast(0L)
                                    )
                                }
                            )
                        requireNotNull(preparedPayload) {
                            "Bit-exact direct-mapped P010 packing failed for frame=$sequence " +
                                "timestampNs=$timestampNs"
                        }
                        copyPath = Npv10FramePacker.P010CopyPath.NATIVE_DIRECT
                        crcPath = Npv10PreparedP010CrcPath.CAMERA_PLANE_DIRECT_MAPPED_FUSED_NATIVE
                        fallbackCrcTimeNs = 0L
                        encodedBytes = preparedPayload.encodedBytes
                        crc32 = preparedPayload.crc32
                    } else {
                    val ordinaryPacketLease = checkNotNull(packetLease) {
                        "Non-DMC P010 preparation requires a packet lease"
                    }
                    val packedResult = Npv10FramePacker.packP010ImageToPackedTenBitBufferWithCrcResult(
                        image,
                        ordinaryPacketLease.buffer
                    )
                    if (packedResult.path != Npv10FramePacker.P010CopyPath.FAILED) {
                        require(packedResult.bytesCopied == encodedBytes) {
                            "P010 Image pack produced ${packedResult.bytesCopied} bytes, expected $encodedBytes"
                        }
                        copyPath = packedResult.path
                        if (packedResult.crc32 != null) {
                            crc32 = packedResult.crc32
                            crcPath = Npv10PreparedP010CrcPath.CAMERA_PLANE_FUSED_NATIVE
                            fallbackCrcTimeNs = 0L
                        } else {
                            val crcStartedNs = System.nanoTime()
                            crc32 = crc32(ordinaryPacketLease.buffer, encodedBytes)
                            fallbackCrcTimeNs = (System.nanoTime() - crcStartedNs).coerceAtLeast(0L)
                            crcPath = Npv10PreparedP010CrcPath.JAVA_PACKET_FALLBACK
                        }
                    } else {
                        // Legacy/test sessions may retain the compatibility path. Production Video
                        // requires the direct-mapped bit-exact path and runs NIV99 later while creating
                        // the user-visible derivative, outside the capture deadline.
                        canonicalP010FallbackSlots.acquireUninterruptibly()
                        try {
                            val canonicalLease = acquireCanonicalP010FallbackBuffer(decodedPayloadBytes)
                            try {
                                val canonicalBuffer = canonicalLease.buffer
                                val canonicalCopy = Npv10FramePacker.copyP010ImageIntoDirectBufferWithResult(
                                    image,
                                    canonicalBuffer
                                )
                                require(canonicalCopy.bytesCopied == decodedPayloadBytes) {
                                    "P010 compatibility copy produced ${canonicalCopy.bytesCopied} bytes, " +
                                        "expected $decodedPayloadBytes"
                                }
                                val encodeResult = Npv10PacketCodecEngine.encodeWithCrc32(
                                    decoded = canonicalBuffer,
                                    decodedBytes = decodedPayloadBytes,
                                    codec = config.packetCodec,
                                    target = ordinaryPacketLease.buffer
                                )
                                require(encodeResult.encodedBytes == encodedBytes) {
                                    "P010 compatibility pack produced ${encodeResult.encodedBytes} bytes, " +
                                        "expected $encodedBytes"
                                }
                                copyPath = Npv10FramePacker.P010CopyPath.DIRECT_BUFFER
                                if (encodeResult.crc32 != null) {
                                    crc32 = encodeResult.crc32
                                    crcPath = Npv10PreparedP010CrcPath.CANONICAL_PACKET_NATIVE
                                    fallbackCrcTimeNs = 0L
                                } else {
                                    val crcStartedNs = System.nanoTime()
                                    crc32 = crc32(ordinaryPacketLease.buffer, encodedBytes)
                                    fallbackCrcTimeNs = (System.nanoTime() - crcStartedNs).coerceAtLeast(0L)
                                    crcPath = Npv10PreparedP010CrcPath.JAVA_PACKET_FALLBACK
                                }
                            } finally {
                                releaseCanonicalP010FallbackBuffer(canonicalLease)
                            }
                        } finally {
                            canonicalP010FallbackSlots.release()
                        }
                    }
                    }
                }
                val preparationTimeNs = (System.nanoTime() - preparationStartedNs).coerceAtLeast(0L)
                recordIngressPreparation(timestampNs, preparationTimeNs)
                if (preparationTimeNs > 0L) workHint?.reportActual(preparationTimeNs)
                return Npv10PreparedP010Packet(
                    timestampNs = timestampNs,
                    width = imageWidth,
                    height = imageHeight,
                    packetLease = packetLease,
                    decodedPayloadBytes = decodedPayloadBytes,
                    encodedBytes = encodedBytes,
                    crc32 = crc32,
                    crcPath = crcPath,
                    fallbackCrcTimeNs = fallbackCrcTimeNs,
                    copyPath = copyPath,
                    preparationTimeNs = preparationTimeNs,
                    niv99Applied = niv99Applied,
                    niv99CameraId = niv99CameraId,
                    niv99SessionGeneration = niv99SessionGeneration,
                    niv99FrameNumber = niv99FrameNumber,
                    niv99ProcessingTimeNs = niv99ProcessingTimeNs,
                    directMappedFrameIndex = if (config.directMappedCommitRequired) sequence else -1L
                )
            } catch (t: Throwable) {
                packetLease?.let(::releaseP010PacketBuffer)
                if (directMappedFrameBegun) {
                    runCatching { activeWriter.abortDirectMappedFrame(sequence) }
                        .onFailure(t::addSuppressed)
                }
                throw t
            }
        } finally {
            if (!imageClosed) closeImageSafely(image)
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

    private fun acquireP010PacketBuffer(packetBytes: Int): Npv10NativeFrameBuffer {
        while (true) {
            val candidate = p010PacketBufferPool.poll() ?: break
            if (candidate.buffer.capacity() >= packetBytes) {
                candidate.buffer.clear()
                candidate.buffer.limit(packetBytes)
                return candidate
            }
            candidate.close()
        }
        return Npv10NativeFrameBuffer.allocate(packetBytes).also { lease ->
            prefaultPacketBuffer(lease.buffer, packetBytes)
        }
    }

    private fun releaseP010PacketBuffer(lease: Npv10NativeFrameBuffer) {
        if (p010PacketBufferPool.size >= activeMaxPendingFrames) {
            lease.close()
            return
        }
        lease.buffer.clear()
        p010PacketBufferPool.offer(lease)
    }

    private fun acquireNiv99CanonicalP010Buffer(decodedBytes: Int): Npv10NativeFrameBuffer {
        val lease = checkNotNull(niv99CanonicalP010BufferPool.poll()) {
            "Preallocated NIV99 canonical P010 pool was exhausted"
        }
        if (lease.buffer.capacity() < decodedBytes) {
            lease.close()
            error(
                "Preallocated NIV99 canonical P010 buffer is undersized: " +
                    "capacity=${lease.buffer.capacity()} required=$decodedBytes"
            )
        }
        lease.buffer.clear()
        lease.buffer.limit(decodedBytes)
        return lease
    }

    private fun releaseNiv99CanonicalP010Buffer(lease: Npv10NativeFrameBuffer) {
        if (niv99CanonicalP010BufferPool.size >=
            Npv10InFlightBufferPolicy.NIV99_CANONICAL_BUFFER_COUNT
        ) {
            lease.close()
            return
        }
        lease.buffer.clear()
        niv99CanonicalP010BufferPool.offer(lease)
    }

    private fun acquireCanonicalP010FallbackBuffer(decodedBytes: Int): Npv10NativeFrameBuffer {
        while (true) {
            val candidate = canonicalP010FallbackBufferPool.poll() ?: break
            if (candidate.buffer.capacity() >= decodedBytes) {
                candidate.buffer.clear()
                candidate.buffer.limit(decodedBytes)
                return candidate
            }
            candidate.close()
        }
        return Npv10NativeFrameBuffer.allocate(decodedBytes).also { lease ->
            prefaultPacketBuffer(lease.buffer, decodedBytes)
        }
    }

    private fun releaseCanonicalP010FallbackBuffer(lease: Npv10NativeFrameBuffer) {
        if (canonicalP010FallbackBufferPool.size >=
            Npv10InFlightBufferPolicy.MAX_CONCURRENT_CANONICAL_FALLBACKS
        ) {
            lease.close()
            return
        }
        lease.buffer.clear()
        canonicalP010FallbackBufferPool.offer(lease)
    }

    private fun releaseIdleCanonicalP010FallbackBuffers(): Int {
        var releasedBuffers = 0
        while (true) {
            val lease = canonicalP010FallbackBufferPool.poll() ?: break
            lease.close()
            releasedBuffers += 1
        }
        return releasedBuffers
    }

    private fun primeP010PacketBufferPool(config: Npv10SessionConfig) {
        releaseIdleP010PacketBuffers()
        val decodedPayloadBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            config.width,
            config.height,
            config.payloadFormat
        )
        val packetBytes = Npv10PacketCodecEngine.storedBytes(
            decodedPayloadBytes,
            config.packetCodec
        ).toInt()
        repeat(activeMaxPendingFrames) {
            val lease = Npv10NativeFrameBuffer.allocate(packetBytes)
            prefaultPacketBuffer(lease.buffer, packetBytes)
            p010PacketBufferPool.offer(lease)
        }
        Log.i(
            TAG,
            "NPV10 packed-10 ingress pool ready buffers=$activeMaxPendingFrames " +
                "bytesPerBuffer=$packetBytes totalBytes=${packetBytes.toLong() * activeMaxPendingFrames}"
        )
    }

    private fun primeNiv99CanonicalP010BufferPool(config: Npv10SessionConfig) {
        releaseIdleNiv99CanonicalP010Buffers()
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            config.width,
            config.height,
            config.payloadFormat
        ).toInt()
        try {
            repeat(Npv10InFlightBufferPolicy.NIV99_CANONICAL_BUFFER_COUNT) {
                val lease = Npv10NativeFrameBuffer.allocate(decodedBytes)
                prefaultPacketBuffer(lease.buffer, decodedBytes)
                niv99CanonicalP010BufferPool.offer(lease)
            }
        } catch (failure: Throwable) {
            releaseIdleNiv99CanonicalP010Buffers()
            throw failure
        }
        Log.i(
            TAG,
            "NIV99 canonical P010 pool ready " +
                "buffers=${Npv10InFlightBufferPolicy.NIV99_CANONICAL_BUFFER_COUNT} " +
                "bytesPerBuffer=$decodedBytes " +
                "totalDirectBytes=${Npv10InFlightBufferPolicy.niv99ResidentBufferBytes(
                    config.width,
                    config.height,
                    config.packetCodec
                )} pendingFrames=$activeMaxPendingFrames"
        )
    }

    private fun prefaultPacketBuffer(buffer: ByteBuffer, packetBytes: Int) {
        var offset = 0
        while (offset < packetBytes) {
            buffer.put(offset, 0.toByte())
            offset += MEMORY_PAGE_BYTES
        }
        buffer.put(packetBytes - 1, 0.toByte())
        buffer.clear()
        buffer.limit(packetBytes)
    }

    private fun releaseIdleP010PacketBuffers(): Int {
        var releasedBuffers = 0
        while (true) {
            val lease = p010PacketBufferPool.poll() ?: break
            lease.close()
            releasedBuffers += 1
        }
        return releasedBuffers
    }

    private fun releaseIdleNiv99CanonicalP010Buffers(): Int {
        var releasedBuffers = 0
        while (true) {
            val lease = niv99CanonicalP010BufferPool.poll() ?: break
            lease.close()
            releasedBuffers += 1
        }
        return releasedBuffers
    }

    private fun crc32(packet: ByteBuffer, encodedBytes: Int): Int {
        val duplicate = packet.duplicate().apply {
            position(0)
            limit(encodedBytes)
        }
        return CRC32().apply { update(duplicate) }.value.toInt()
    }

    private fun recordIngressPreparation(timestampNs: Long, elapsedNs: Long) {
        ingressPreparationFrames.incrementAndGet()
        ingressPreparationNanos.addAndGet(elapsedNs)
        while (true) {
            val previous = maxIngressPreparationNanos.get()
            if (elapsedNs <= previous) return
            if (maxIngressPreparationNanos.compareAndSet(previous, elapsedNs)) {
                maxIngressPreparationTimestampNs.set(timestampNs)
                return
            }
        }
    }

    private fun recordEarlyP010ImageRelease(workerHoldNs: Long) {
        earlyReleasedP010Images.incrementAndGet()
        earlyP010WorkerImageHoldNanos.addAndGet(workerHoldNs)
        while (true) {
            val previous = maxEarlyP010WorkerImageHoldNanos.get()
            if (workerHoldNs <= previous ||
                maxEarlyP010WorkerImageHoldNanos.compareAndSet(previous, workerHoldNs)) return
        }
    }

    private fun recordNiv99CanonicalCopy(timestampNs: Long, elapsedNs: Long) {
        niv99CanonicalCopies.incrementAndGet()
        niv99CanonicalCopyNanos.addAndGet(elapsedNs)
        while (true) {
            val previous = maxNiv99CanonicalCopyNanos.get()
            if (elapsedNs <= previous) return
            if (maxNiv99CanonicalCopyNanos.compareAndSet(previous, elapsedNs)) {
                maxNiv99CanonicalCopyTimestampNs.set(timestampNs)
                return
            }
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

    private fun acquirePendingFrameSlot(frameSlots: Semaphore): Boolean {
        if (frameSlots.tryAcquire()) return true
        val fps = activeConfig?.fps ?: return false
        val waitBudgetNanos = Npv10InFlightBufferPolicy.admissionWaitNanos(fps)
        val waitStartedNanos = System.nanoTime()
        val acquired = try {
            frameSlots.tryAcquire(waitBudgetNanos, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        val waitedNanos = (System.nanoTime() - waitStartedNanos).coerceAtLeast(0L)
        admissionWaitCount.incrementAndGet()
        admissionWaitNanos.addAndGet(waitedNanos)
        if (!acquired) admissionTimeouts.incrementAndGet()
        return acquired
    }

    private fun failStrictMasterBacklog() {
        val config = activeConfig
        val activeWriter = writer
        val storedBytesPerFrame = config?.let { active ->
            Npv10ContainerPolicy.estimatedStoredBytesPerFrame(
                active.width,
                active.height,
                active.payloadFormat,
                active.packetCodec
            )
        } ?: 0L
        val requiredBytesPerSecond = storedBytesPerFrame * (config?.fps ?: 0).coerceAtLeast(0).toLong()
        val averageWriteMs = activeWriter?.averageWriteTimeMs ?: 0.0
        val stages = activeWriter?.writerStageTimings
        val nativeWriteServiceRate = Npv10BacklogEvidencePolicy.nativeWriteServiceBytesPerSecond(stages)
        val message = "NPV10 strict private master throughput exhausted its bounded frame leases; " +
            "refusing dropped-frame output requiredBytesPerSecond=$requiredBytesPerSecond " +
            "requiredRateBasis=worst_case_uncompressed_packet " +
            "nativeWriteServiceBytesPerSecond=${nativeWriteServiceRate ?: "unknown"} " +
            "durableStorageBytesPerSecond=unknown enqueueAverageMs=$averageWriteMs " +
            "nativeWriteAverageMs=${stages?.averageAsynchronousRecordWriteMs} " +
            "nativeWriteMaxMs=${stages?.maxAsynchronousRecordWriteMs} " +
            "pendingNativeWrites=${stages?.pendingRecordQueueDepth} " +
            "admissionWaitCount=${admissionWaitCount.get()} " +
            "admissionWaitMs=${admissionWaitNanos.get() / 1_000_000.0} " +
            "admissionTimeouts=${admissionTimeouts.get()}"
        _state.value = GpuRecordingState.Error(message)
        Log.e(TAG, message)
    }

    /** Fails the active strict master when Camera2 violates its admitted P010 stream contract. */
    fun failInputContractViolation(message: String): Boolean {
        val terminal = GpuRecordingState.Error(message)
        while (true) {
            val current = _state.value
            if (current !is GpuRecordingState.Recording || writer == null) return false
            if (_state.compareAndSet(current, terminal)) {
                droppedFrames.incrementAndGet()
                Log.e(TAG, message)
                return true
            }
        }
    }

    /**
     * Irreversibly poisons durability publication for an active writer, including stop/finalizing
     * states where normal realtime input admission is already closed. This is the fail-safe used by
     * the exact Camera2 stop fence: no later structural audit may publish `.npv10.verified` after the
     * producer boundary was not proven.
     */
    // Deliberately lock-free: lifecycle teardown must be able to poison publication while stop() is
    // holding the manager monitor across writer/fsync work. writer is volatile and the poison is an
    // AtomicReference; publication consumes that atomic fact directly.
    fun poisonStopBoundaryFailure(message: String): Boolean {
        val sessionGate = producerBoundaryGateRef.get() ?: return false
        if (writer == null || producerBoundaryGateRef.get() !== sessionGate ||
            !sessionGate.poison()
        ) return false
        val failure = IllegalStateException(message)
        sessionGate.recordPoisonFailure(failure)
        return true
    }

    /**
     * Closes admission only after the exact final already-captured Image is the committed writer
     * tail. Camera2 proves completion of every outstanding sequence without adding a marker exposure.
     * Every interval in persisted media remains cadence-accounted, including the final interval.
     * Any timestamp, lineage, packing, or writer-tail mismatch poisons publication.
     */
    @Synchronized
    internal fun acknowledgeExactProducerBoundary(markerTimestampNs: Long): Boolean {
        val sessionGate = producerBoundaryGateRef.get() ?: return false
        val activeWriter = writer ?: return false
        if (producerBoundaryGateRef.get() !== sessionGate) return false

        // Linearize the terminal FIFO proof against synchronized enqueueP010Image. Already-accepted
        // packets may drain; a later callback cannot enter the ledger while exact ownership is proven.
        paused = true
        fun failClosed(reason: String): Boolean {
            poisonStopBoundaryFailure("NPV10 terminal cadence boundary failed: $reason")
            return false
        }
        if (markerTimestampNs <= 0L) return failClosed("non-positive marker timestamp=$markerTimestampNs")
        if (_state.value !is GpuRecordingState.Recording ||
            sessionGate.state != Npv10ProducerBoundaryGate.State.OPEN
        ) {
            return failClosed(
                "recorder was not open at marker timestamp=$markerTimestampNs " +
                    "state=${_state.value} boundary=${sessionGate.state}"
            )
        }

        val writerExcludedMissingFrames = try {
            // The phaser first proves every accepted Image was packed and its ordered completion was
            // submitted. This writer-lane marker therefore runs after every commit and resource retire.
            awaitP010PackingDrain()
            val terminalCadenceTask = writerExecutor.submit<Long?> {
                if (pendingFrames.get() != 0) {
                    null
                } else {
                    activeWriter.closeTerminalCadenceSegment(markerTimestampNs)
                }
            }
            awaitFutureUninterruptibly(terminalCadenceTask)
        } catch (failure: Throwable) {
            return failClosed(
                "writer drain/classification threw " +
                    (failure.message ?: failure.javaClass.simpleName)
            )
        } ?: return failClosed(
            "writer tail did not match exact marker timestamp=$markerTimestampNs " +
                "pending=${pendingFrames.get()}"
        )

        val ledgerExcludedMissingFrames =
            captureLedger.closeTerminalCadenceSegment(markerTimestampNs)
                ?: return failClosed(
                    "capture lineage tail did not match exact marker timestamp=$markerTimestampNs"
                )
        if (ledgerExcludedMissingFrames != writerExcludedMissingFrames) {
            return failClosed(
                "ledger/writer terminal interval mismatch=" +
                    "$ledgerExcludedMissingFrames/$writerExcludedMissingFrames"
            )
        }
        if (writer !== activeWriter || producerBoundaryGateRef.get() !== sessionGate) {
            return failClosed("recorder generation changed during terminal boundary proof")
        }
        if (!sessionGate.establish()) return false
        Log.i(
            TAG,
            "NPV10 exact terminal cadence boundary established timestampNs=$markerTimestampNs " +
                "excludedMissingFrames=$ledgerExcludedMissingFrames"
        )
        return true
    }

    /**
     * Lock-free lifecycle abort. It cannot downgrade a marker-proven finalization: exact cleanup and
     * teardown race on the same OPEN state, and only one transition can win.
     */
    fun poisonUnprovenProducerBoundaryFailure(message: String): Boolean {
        val sessionGate = producerBoundaryGateRef.get() ?: return false
        if (writer == null || producerBoundaryGateRef.get() !== sessionGate ||
            !sessionGate.poisonIfOpen()
        ) return false
        val failure = IllegalStateException(message)
        sessionGate.recordPoisonFailure(failure)
        return true
    }

    @Synchronized
    fun pause(): Boolean {
        if (_state.value !is GpuRecordingState.Recording) return false
        durationTimeline = Npv10RecordingDurationPolicy.pause(
            durationTimeline,
            SystemClock.elapsedRealtime()
        )
        paused = true
        updateActiveRecordingDuration()
        _state.value = GpuRecordingState.Paused
        Log.i(TAG, "NPV10 private master paused")
        return true
    }

    @Synchronized
    fun resume(): Boolean {
        if (_state.value !is GpuRecordingState.Paused) return false
        val activeWriter = writer ?: return false
        // Pause can return while accepted Images are still owned by the packing workers. Drain those
        // workers first so their ordered writer tasks are necessarily ahead of the cadence marker.
        awaitP010PackingDrain()
        val cadenceMarker = try {
            // Accepted pre-pause frames complete first. This ordered marker starts a fresh cadence
            // segment before synchronized enqueue can admit any resumed frame.
            writerExecutor.submit<Boolean> {
                activeWriter.markTimestampCadenceDiscontinuity()
                true
            }
        } catch (t: RejectedExecutionException) {
            Log.e(TAG, "NPV10 writer rejected resume cadence marker", t)
            _state.value = GpuRecordingState.Error(
                t.message ?: "NPV10 writer rejected resume cadence marker"
            )
            return false
        }
        val markerCompleted = try {
            cadenceMarker.get()
        } catch (failure: Throwable) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            Log.e(TAG, "NPV10 resume cadence marker failed", failure)
            _state.value = GpuRecordingState.Error(
                failure.message ?: "NPV10 resume cadence marker failed"
            )
            false
        }
        if (!markerCompleted || _state.value !is GpuRecordingState.Paused ||
            producerBoundaryGateRef.get()?.poisonFailure != null
        ) {
            return false
        }
        captureLedger.beginCadenceSegment()
        durationTimeline = Npv10RecordingDurationPolicy.resume(
            durationTimeline,
            SystemClock.elapsedRealtime()
        )
        check(_state.compareAndSet(GpuRecordingState.Paused, GpuRecordingState.Recording)) {
            "NPV10 resume state changed after its ordered cadence marker"
        }
        paused = false
        updateActiveRecordingDuration()
        Log.i(TAG, "NPV10 private master resumed")
        return true
    }

    /**
     * Atomically closes camera ingestion while leaving already accepted packets on the writer lane.
     * The public state remains [GpuRecordingState.Finalizing] until the master, evidence, and quality
     * certificate are durable. Capture has stopped, but publishing Idle during that work would make
     * the UI claim that saving had completed and would admit an unsafe second start.
     */
    @Synchronized
    fun beginFinalization(): Boolean {
        if (writer == null) return false
        paused = true
        if (!finalizationInProgress) {
            if (producerBoundaryGateRef.get()?.state == Npv10ProducerBoundaryGate.State.OPEN) {
                poisonStopBoundaryFailure(
                    "NPV10 finalization began without an acknowledged producer boundary"
                )
            }
            // Freeze realtime telemetry at the exact ingestion boundary. Post-record packet hashing,
            // fsync, evidence generation, and certificate writes are background finalization work and
            // must not be misreported as camera-recording CPU load.
            finalizedRecordingCpuUsagePercent = calculateRecordingCpuUsagePercent()
            finalizationInProgress = true
            _duration.value = 0L
            if (_state.value !is GpuRecordingState.Error) {
                _state.value = GpuRecordingState.Finalizing
            }
            Log.i(TAG, "NPV10 camera ingestion stopped; lossless master finalization continues in background")
        }
        return true
    }

    /**
     * Waits until every already-admitted camera Image has been written and closed.
     *
     * CameraViewModel calls this after detaching capture but before it permits a Camera2 session
     * refresh to close/rebuild the P010 ImageReader. The ordered marker shares the single writer
     * executor, therefore its completion proves that every earlier task has reached its `finally`
     * block and released both the Image lease and semaphore permit. Container sealing, hashing, and
     * evidence generation remain in [stop] and may continue after the visible preview is restored.
     */
    @Synchronized
    fun awaitAcceptedFrameDrain(): Boolean {
        if (writer == null) return true
        beginFinalization()
        // The writer marker is meaningful only after every packing worker has published its ordered
        // completion. It then proves both Image closure and packet/permit retirement.
        awaitP010PackingDrain()
        val drainTask = try {
            writerExecutor.submit<Boolean> { pendingFrames.get() == 0 }
        } catch (failure: RejectedExecutionException) {
            Log.e(TAG, "NPV10 writer rejected accepted-frame drain marker", failure)
            _state.value = GpuRecordingState.Error(
                failure.message ?: "NPV10 writer rejected accepted-frame drain marker"
            )
            return false
        }
        return runCatching { awaitFutureUninterruptibly(drainTask) }
            .onFailure { failure ->
                Log.e(TAG, "NPV10 accepted-frame drain failed", failure)
                _state.value = GpuRecordingState.Error(
                    failure.message ?: "NPV10 accepted-frame drain failed"
                )
            }
            .getOrDefault(false)
            .also { drained ->
                if (!drained) {
                    val message = "NPV10 writer drain marker completed with " +
                        "${pendingFrames.get()} accepted frame(s) still leased"
                    Log.e(TAG, message)
                    _state.value = GpuRecordingState.Error(message)
                }
            }
    }

    @Synchronized
    fun stop(): File? {
        val activeWriter = writer ?: return null
        val sessionProducerBoundaryGate = producerBoundaryGateRef.get()
        val stopStartedNs = System.nanoTime()
        val activeOutput = outputFile
        val sessionConfig = activeConfig
        if (!finalizationInProgress) {
            // stop()/release() is also a terminal safety boundary. A legitimate Camera2 stop first
            // calls beginFinalization from its exact marker-fence cleanup; reaching this method with
            // live admission still open means the producer boundary is unknown and must not publish.
            poisonStopBoundaryFailure(
                "NPV10 stop reached an active writer without a proven producer boundary"
            )
        }
        beginFinalization()
        // No new Image can enter while this synchronized finalization boundary is held. Waiting here
        // guarantees every accepted packing completion is queued before the writer close operation.
        awaitP010PackingDrain()
        val queueDrainNs = AtomicLong(0L)
        val closeQueuedNs = System.nanoTime()
        val closeTask: Future<Boolean> = try {
            writerExecutor.submit<Boolean> {
                queueDrainNs.set((System.nanoTime() - closeQueuedNs).coerceAtLeast(0L))
                activeWriter.closePreservingPhysicalReservationWhenEmpty()
            }
        } catch (failure: RejectedExecutionException) {
            Log.e(TAG, "NPV10 writer rejected close operation", failure)
            _state.value = GpuRecordingState.Error(
                failure.message ?: "NPV10 writer rejected close operation"
            )
            throw IllegalStateException("NPV10 writer could not seal the private master", failure)
        }
        var physicalReservationPreserved = false
        runCatching {
            // Capture has stopped and the UI is visibly finalizing. Await every accepted packet without
            // a destructive timeout so a slow flash device can never turn a valid tail into silent loss.
            awaitFutureUninterruptibly(closeTask)
        }.onSuccess { preserved ->
            physicalReservationPreserved = preserved
        }.onFailure { throwable ->
            Log.e(TAG, "NPV10 writer close failed", throwable)
            _state.value = GpuRecordingState.Error(throwable.message ?: "NPV10 writer close failed")
        }
        // Snapshot after close has joined every queued growth/checkpoint operation and therefore is
        // the terminal reservation truth for this master. Persist it below; logcat is diagnostic only
        // and may be evicted on high-throughput vendor builds.
        val physicalReservationTelemetry = activeWriter.physicalReservationTelemetry
        val writerStageTimings = activeWriter.writerStageTimings
        // Everything below this boundary runs only after the ordered writer close has completed,
        // successfully or exceptionally. A post-close evidence/publication failure must fail closed
        // without keeping stale lifecycle ownership that would permanently reject the next shutter.
        var evidenceBuildMs = 0.0
        var evidenceWriteMs = 0.0
        var certificateMs = 0.0
        try {
            // closeTask is ordered behind every accepted frame, so no writer can still own a pooled copy
            // here. Keeping full-resolution P010 buffers until process death retained hundreds of MiB after
            // a clip. Releasing only the idle references preserves every recorded byte and the strict
            // post-record CRC/hash audit while allowing Android to reclaim the native memory.
            val releasedIdleBuffers = releaseIdleP010PacketBuffers()
            val releasedNiv99Buffers = releaseIdleNiv99CanonicalP010Buffers()
            val releasedCompatibilityBuffers = releaseIdleCanonicalP010FallbackBuffers()
        if (releasedIdleBuffers > 0) {
            Log.i(TAG, "Released $releasedIdleBuffers idle packed-10 buffers before post-record audit")
        }
        if (releasedCompatibilityBuffers > 0) {
            Log.i(
                TAG,
                "Released $releasedCompatibilityBuffers canonical P010 compatibility buffers " +
                    "before post-record audit"
            )
        }
        if (releasedNiv99Buffers > 0) {
            Log.i(
                TAG,
                "Released $releasedNiv99Buffers NIV99 canonical P010 buffers before post-record audit"
            )
        }
        val writtenFrames = activeWriter.frameCount
        val queueRejectedFrames = droppedFrames.get()
        val cadenceMissingFrames = activeWriter.inferredDroppedFrameCount
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        // Queue rejection and timestamp gaps describe the same missing output in the common case;
        // use the larger independent observation instead of double-counting it.
        val dropped = maxOf(queueRejectedFrames, cadenceMissingFrames)
        val payloadBytes = activeWriter.payloadBytesWritten
        val firstTimestampNs = activeWriter.firstFrameTimestampNs
        val averageWriteMs = activeWriter.averageWriteTimeMs
        val nativeP010Copies = nativeDirectP010Copies.get()
        val directP010Copies = directBufferP010Copies.get()
        val failedCopies = failedP010Copies.get()
        val boundedAdmissionWaitCount = admissionWaitCount.get()
        val boundedAdmissionWaitNanos = admissionWaitNanos.get()
        val boundedAdmissionTimeouts = admissionTimeouts.get()
        val preparedFrames = ingressPreparationFrames.get()
        val totalPreparationNs = ingressPreparationNanos.get()
        val averagePreparationMs = if (preparedFrames > 0L) {
            totalPreparationNs.toDouble() / preparedFrames.toDouble() / 1_000_000.0
        } else {
            0.0
        }
        val maxPreparationMs = maxIngressPreparationNanos.get().toDouble() / 1_000_000.0
        val maxPreparationTimestampNs = maxIngressPreparationTimestampNs.get()
        val canonicalCopies = niv99CanonicalCopies.get()
        val canonicalCopyTotalNs = niv99CanonicalCopyNanos.get()
        val canonicalCopyAverageMs = if (canonicalCopies > 0L) {
            canonicalCopyTotalNs.toDouble() / canonicalCopies.toDouble() / 1_000_000.0
        } else {
            0.0
        }
        val canonicalCopyMaxMs = maxNiv99CanonicalCopyNanos.get().toDouble() / 1_000_000.0
        val canonicalCopyMaxTimestampNs = maxNiv99CanonicalCopyTimestampNs.get()
        val niv99Timing = niv99TimingTelemetry.snapshot()
        val writerFailureCount = asynchronousWriterFailures.get()
        val captureLedgerSnapshot = captureLedger.snapshot()
        val expectedPayloadBytesPerFrame = sessionConfig?.let { config ->
            Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
                config.width,
                config.height,
                config.payloadFormat
            )
        } ?: 0L
        val captureLedgerFailures = buildList {
            addAll(captureLedgerSnapshot.failureReasons(expectedPayloadBytesPerFrame))
            if (captureLedgerSnapshot.committedFrames != writtenFrames) {
                add("ledgerWriterFrameMismatch=${captureLedgerSnapshot.committedFrames}/$writtenFrames")
            }
            if (captureLedgerSnapshot.committedPayloadBytes != payloadBytes) {
                add("ledgerWriterPayloadMismatch=${captureLedgerSnapshot.committedPayloadBytes}/$payloadBytes")
            }
            if (writerFailureCount > 0L) {
                add("asynchronousWriterFailures=$writerFailureCount")
            }
        }
        // The terminal writer is already joined. Preserve its exact failure/stage measurements
        // BEFORE a potentially minutes-long full master audit. Android process death must not turn
        // a known failed recording into missing diagnostics. This snapshot never permits publication.
        var pendingSnapshotFailure: String? = null
        if (activeOutput != null && writtenFrames > 0L) {
            runCatching {
                Npv10FinalizationSnapshotStore.writePending(
                    privateRoot = privateRoot,
                    master = activeOutput,
                    generatedAtEpochMs = System.currentTimeMillis(),
                    measurements = linkedMapOf(
                        "writtenFrames" to writtenFrames.toString(),
                        "droppedFrames" to dropped.toString(),
                        "payloadBytes" to payloadBytes.toString(),
                        "requestedFps" to (sessionConfig?.fps ?: 0).toString(),
                        "captureWidth" to (sessionConfig?.width ?: 0).toString(),
                        "captureHeight" to (sessionConfig?.height ?: 0).toString(),
                        "terminalError" to ((_state.value as? GpuRecordingState.Error)?.message.orEmpty()),
                        "producerBoundaryFailure" to sessionProducerBoundaryGate?.poisonFailure?.message.orEmpty(),
                        "captureLedgerFailures" to captureLedgerFailures.joinToString(","),
                        "asynchronousWriterFailures" to writerFailureCount.toString(),
                        "admissionTimeouts" to boundedAdmissionTimeouts.toString()
                    ).apply {
                        putAll(writerStageTimings.toEvidenceMetadata())
                        putAll(activeWriter.durabilityStageTimings.toEvidenceMetadata())
                        putAll(physicalReservationTelemetry.toEvidenceMetadata())
                    }
                )
            }.onSuccess { snapshot ->
                Log.i(TAG, "NPV10 pending finalization snapshot persisted path=${snapshot.absolutePath} audit=NOT_PROVEN")
            }.onFailure { failure ->
                pendingSnapshotFailure = failure.message ?: failure.javaClass.simpleName
                Log.e(TAG, "NPV10 pending finalization snapshot could not be persisted", failure)
            }
        }
        val evidenceBuildStartedNs = System.nanoTime()
        Log.i(TAG, "NPV10 full master audit started frames=$writtenFrames " +
            "fileBytes=${activeOutput?.length() ?: 0L} path=${activeOutput?.absolutePath.orEmpty()}")
        val evidence = activeOutput?.takeIf { writtenFrames > 0L }?.let {
            activeWriter.buildDeviceEvidence(
                privateRoot = privateRoot,
                droppedFrames = dropped,
                generatedAtEpochMs = System.currentTimeMillis()
            )
        }
        evidenceBuildMs = elapsedMs(evidenceBuildStartedNs)
        Log.i(TAG, "NPV10 full master audit finished frames=${evidence?.frameCount ?: 0L} " +
            "status=${evidence?.auditStatus} elapsedMs=$evidenceBuildMs")
        // Seal before dropping the volatile writer reference. Lifecycle poison can win only while
        // OPEN; after this point publication consumes one immutable established/poisoned decision.
        val producerBoundaryPublicationSafe =
            sessionProducerBoundaryGate?.sealForPublication() == true
        if (writtenFrames <= 0L) {
            val recycledFile = if (physicalReservationPreserved && activeOutput != null) {
                Npv10ReservationProcessCoordinator.recycleAsLeasedReservation(
                    reservationOwner
                ) {
                    store.recycleReservation(activeOutput)
                }
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
        val evidenceWriteStartedNs = System.nanoTime()
        val evidencePath = evidence?.let { runtimeEvidence ->
            runCatching { Npv10IntegrityValidator.writeDeviceEvidence(runtimeEvidence).absolutePath }
                .getOrElse { throwable ->
                    Log.e(TAG, "NPV10 device evidence write failed", throwable)
                    null
                }
        }
        evidenceWriteMs = elapsedMs(evidenceWriteStartedNs)
        val certificateStartedNs = System.nanoTime()
        val qualityCertificateWrite = evidence?.let { runtimeEvidence ->
            runCatching {
                writeQualityCertificate(
                    evidence = runtimeEvidence,
                    fps = sessionConfig?.fps ?: 1,
                    deviceEvidencePath = evidencePath,
                    sessionConfig = sessionConfig,
                    captureLedger = captureLedgerSnapshot,
                    ingressPackAverageMs = averagePreparationMs,
                    ingressPackMaxMs = maxPreparationMs,
                    ingressPackMaxTimestampNs = maxPreparationTimestampNs,
                    niv99Timing = niv99Timing,
                    writerStageTimings = writerStageTimings,
                    durabilityStageTimings = activeWriter.durabilityStageTimings,
                    physicalReservationTelemetry = physicalReservationTelemetry
                )
            }
                .getOrElse { throwable ->
                    Log.e(TAG, "NPV10 quality certificate write failed", throwable)
                    Npv10QualityCertificateWrite(
                        file = null,
                        status = RecordingQualityEvidenceStatus.NOT_PROVEN,
                        persistenceFailure = throwable.message ?: throwable.javaClass.simpleName
                    )
                }
        }
        val qualityCertificateFile = qualityCertificateWrite?.file
        val qualityCertificatePath = qualityCertificateFile?.absolutePath
        certificateMs = elapsedMs(certificateStartedNs)
        val terminalError = _state.value as? GpuRecordingState.Error
        val commitPoison = sessionProducerBoundaryGate?.poisonFailure
        val prePublicationFailures = buildList {
            pendingSnapshotFailure?.let { add("pendingFinalizationSnapshot=$it") }
            if (terminalError != null) add("terminalError=${terminalError.message}")
            // Publication reads the irreversible poison directly. Recorder state is diagnostic/UI
            // state and may evolve; it is never the sole authority for an unproven producer tail.
            if (commitPoison != null) {
                add("p010CommitPoison=${commitPoison.message ?: commitPoison.javaClass.simpleName}")
            }
            if (!producerBoundaryPublicationSafe) add("producerBoundary=UNPROVEN_OR_POISONED")
            if (evidence?.auditStatus != Npv10AuditStatus.PASS) {
                add("masterAudit=${evidence?.auditStatus ?: "MISSING"}")
            }
            // The structural master audit cannot substitute for the independent quality contract.
            if (evidence?.eliteVideoQualityAuditStatus != EliteQualityAuditStatus.PASS) {
                add(
                    "eliteVideoQualityAudit=" +
                        (evidence?.eliteVideoQualityAuditStatus?.name ?: "MISSING")
                )
            }
            if (dropped != 0) add("droppedFrames=$dropped")
            if (failedCopies != 0L) add("failedP010Copies=$failedCopies")
            if (sessionConfig?.niv99ProcessingRequired == true &&
                !niv99Timing.provesEveryPersistedFrame(writtenFrames)
            ) {
                add(
                    "niv99ProcessedPersistedMismatch=" +
                        "${niv99Timing.processedFrames}/${niv99Timing.persistedFrames}/$writtenFrames" +
                        ",failures=${niv99Timing.failedFrames}"
                )
            }
            addAll(captureLedgerFailures)
            if (evidencePath == null) add("deviceEvidence=MISSING")
        }
        val verifiedMarker = if (prePublicationFailures.isEmpty() &&
            activeOutput != null && evidence != null
        ) {
            runCatching {
                Npv10VerifiedMasterPublication.publish(
                    master = activeOutput,
                    evidence = evidence,
                    captureLedger = captureLedgerSnapshot,
                    qualityCertificate = qualityCertificateFile,
                    qualityEvidenceStatus = qualityCertificateWrite?.status
                        ?: RecordingQualityEvidenceStatus.NOT_PROVEN
                )
            }.onFailure { failure ->
                Log.e(TAG, "NPV10 verified-master publication failed", failure)
                activeOutput.let(Npv10VerifiedMasterPublication::invalidate)
            }.getOrNull()
        } else {
            null
        }
        val durabilityFailures = buildList {
            addAll(prePublicationFailures)
            if (verifiedMarker == null) add("verifiedMasterPublication=MISSING")
        }
        lastStopDurabilityAuditPassed = durabilityFailures.isEmpty()
        lastStopDurabilityFailureReason = durabilityFailures
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "; ")
        if (!lastStopDurabilityAuditPassed && _state.value !is GpuRecordingState.Error) {
            _state.value = GpuRecordingState.Error(
                "NPV10 master preserved privately but failed durability publication gate: " +
                    lastStopDurabilityFailureReason.orEmpty()
            )
        }
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
                "queueRejected=$queueRejectedFrames cadenceMissing=$cadenceMissingFrames " +
                "ledger=${captureLedgerSnapshot.ingressFrames}/" +
                    "${captureLedgerSnapshot.acceptedFrames}/${captureLedgerSnapshot.committedFrames} " +
                "ledgerFailures=${captureLedgerFailures.joinToString(separator = ",")} " +
                "fileBytes=${activeOutput?.length() ?: 0L} firstTimestampNs=$firstTimestampNs " +
                "avgWriteMs=$averageWriteMs audit=${evidence?.auditStatus} " +
                "p010Copies=native:$nativeP010Copies direct:$directP010Copies failed:$failedCopies " +
                "ingressPackFrames=$preparedFrames ingressPackAvgMs=$averagePreparationMs " +
                "ingressPackMaxMs=$maxPreparationMs ingressPackMaxTimestampNs=$maxPreparationTimestampNs " +
                "niv99CanonicalCopies=$canonicalCopies " +
                "niv99CanonicalCopyAvgMs=$canonicalCopyAverageMs " +
                "niv99CanonicalCopyMaxMs=$canonicalCopyMaxMs " +
                "niv99CanonicalCopyMaxTimestampNs=$canonicalCopyMaxTimestampNs " +
                "niv99Required=${sessionConfig?.niv99ProcessingRequired == true} " +
                "niv99Processed=${niv99Timing.processedFrames} " +
                "niv99Persisted=${niv99Timing.persistedFrames} " +
                "niv99Failed=${niv99Timing.failedFrames} " +
                "niv99P50Ms=${niv99Timing.p50Ms} niv99P95Ms=${niv99Timing.p95Ms} " +
                "niv99P99Ms=${niv99Timing.p99Ms} niv99MaxMs=${niv99Timing.maximumMs} " +
                "admissionWaits=$boundedAdmissionWaitCount " +
                "admissionWaitMs=${boundedAdmissionWaitNanos / 1_000_000.0} " +
                "admissionTimeouts=$boundedAdmissionTimeouts " +
                "durabilityPublicationPassed=$lastStopDurabilityAuditPassed " +
                "durabilityFailure=${lastStopDurabilityFailureReason.orEmpty()} " +
                "evidence=$evidencePath qualityCertificate=$qualityCertificatePath " +
                "qualityEvidenceStatus=${qualityCertificateWrite?.status ?: RecordingQualityEvidenceStatus.NOT_PROVEN} " +
                "qualityCertificatePersistenceFailure=${qualityCertificateWrite?.persistenceFailure.orEmpty()} " +
                "verifiedMarker=${verifiedMarker?.absolutePath.orEmpty()} " +
                "stopStages=$lastStopStageTimings " +
                "path=${activeOutput?.absolutePath.orEmpty()}"
        )
        return activeOutput
        } catch (failure: Throwable) {
            val failureReason = failure.message ?: failure.javaClass.simpleName
            sessionProducerBoundaryGate?.let { gate ->
                gate.poison()
                gate.recordPoisonFailure(failure)
                gate.sealForPublication()
            }
            activeOutput?.let(Npv10VerifiedMasterPublication::invalidate)
            lastStopDurabilityAuditPassed = false
            lastStopDurabilityFailureReason = "postCloseFinalizationFailure=$failureReason"
            _state.value = GpuRecordingState.Error(
                "NPV10 private master preserved but post-close durability audit failed: " +
                    failureReason
            )
            runCatching {
                recordStopStageTimings(
                    stopStartedNs = stopStartedNs,
                    queueDrainNs = queueDrainNs.get(),
                    writerTimings = activeWriter.lastFinalizationTimings,
                    evidenceBuildMs = evidenceBuildMs,
                    evidenceWriteMs = evidenceWriteMs,
                    certificateMs = certificateMs
                )
            }.onFailure { timingFailure ->
                Log.e(TAG, "Unable to persist failed NPV10 stop-stage timings", timingFailure)
            }
            Log.e(
                TAG,
                "NPV10 post-close finalization failed; private master retained without publication",
                failure
            )
            throw failure
        } finally {
            retireStoppedSessionOwnership(
                expectedWriter = activeWriter,
                expectedOutput = activeOutput,
                expectedConfig = sessionConfig,
                expectedProducerBoundaryGate = sessionProducerBoundaryGate
            )
        }
    }

    /**
     * Retires one fully drained stop generation without ever clearing a replacement generation.
     *
     * [stop] holds the manager monitor, but identity checks make this invariant explicit and keep a
     * future asynchronous finalization refactor safe. Cleanup is idempotent for normal/empty/error
     * returns and never changes recorded bytes or publication truth.
     */
    @Synchronized
    private fun retireStoppedSessionOwnership(
        expectedWriter: Npv10FileWriter,
        expectedOutput: File?,
        expectedConfig: Npv10SessionConfig?,
        expectedProducerBoundaryGate: Npv10ProducerBoundaryGate?
    ) {
        val currentWriter = writer
        val currentOutput = outputFile
        val currentConfig = activeConfig
        val currentProducerBoundary = producerBoundaryGateRef.get()
        val replacementPresent =
            (currentWriter != null && currentWriter !== expectedWriter) ||
                (currentOutput != null && currentOutput !== expectedOutput) ||
                (currentConfig != null && currentConfig !== expectedConfig) ||
                (currentProducerBoundary != null &&
                    currentProducerBoundary !== expectedProducerBoundaryGate)
        if (replacementPresent) {
            Log.w(TAG, "Skipped stale NPV10 stop retirement because a replacement session owns state")
            return
        }

        val ownsWriter = currentWriter === expectedWriter
        val ownsOutput = expectedOutput != null && currentOutput === expectedOutput
        val ownsConfig = expectedConfig != null && currentConfig === expectedConfig
        val ownsProducerBoundary = expectedProducerBoundaryGate != null &&
            currentProducerBoundary === expectedProducerBoundaryGate
        if (!ownsWriter && !ownsOutput && !ownsConfig && !ownsProducerBoundary) return

        if (ownsWriter) writer = null
        if (ownsOutput) outputFile = null
        if (ownsConfig) {
            activeConfig = null
            activeNiv99FrameProcessor = null
            activeNiv99ColorEncoding = null
        }
        if (ownsProducerBoundary) {
            producerBoundaryGateRef.compareAndSet(expectedProducerBoundaryGate, null)
        }
        paused = false
        durationTimeline = Npv10RecordingDurationTimeline()
        finalizationInProgress = false
        pendingFrames.set(0)
        nativeDirectP010Copies.set(0L)
        earlyReleasedP010Images.set(0L)
        earlyP010WorkerImageHoldNanos.set(0L)
        maxEarlyP010WorkerImageHoldNanos.set(0L)
        directBufferP010Copies.set(0L)
        failedP010Copies.set(0L)
        admissionWaitCount.set(0L)
        admissionWaitNanos.set(0L)
        admissionTimeouts.set(0L)
        asynchronousWriterFailures.set(0L)
        ingressPreparationFrames.set(0L)
        ingressPreparationNanos.set(0L)
        maxIngressPreparationNanos.set(0L)
        maxIngressPreparationTimestampNs.set(0L)
        niv99CanonicalCopies.set(0L)
        niv99CanonicalCopyNanos.set(0L)
        maxNiv99CanonicalCopyNanos.set(0L)
        maxNiv99CanonicalCopyTimestampNs.set(0L)
        niv99TimingTelemetry.reset()
        _duration.value = 0L

        runCatching { releaseIdleP010PacketBuffers() }
            .onFailure { Log.e(TAG, "Unable to retire idle packed-10 buffers", it) }
        runCatching { releaseIdleNiv99CanonicalP010Buffers() }
            .onFailure { Log.e(TAG, "Unable to retire idle NIV99 canonical P010 buffers", it) }
        runCatching { releaseIdleCanonicalP010FallbackBuffers() }
            .onFailure { Log.e(TAG, "Unable to retire idle canonical P010 buffers", it) }
        if (_state.value !is GpuRecordingState.Error) {
            _state.value = GpuRecordingState.Idle
        }
        if (!releaseRequested) {
            runCatching {
                allowIdlePhysicalReservationPreparation()
                preparePhysicalReservationAsync()
            }
                .onFailure { Log.e(TAG, "Unable to re-arm NPV10 physical reservation", it) }
        }
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
        sessionConfig: Npv10SessionConfig?,
        captureLedger: Npv10CaptureLedgerSnapshot,
        ingressPackAverageMs: Double,
        ingressPackMaxMs: Double,
        ingressPackMaxTimestampNs: Long,
        niv99Timing: Npv10Niv99TimingSnapshot,
        writerStageTimings: Npv10WriterStageTimings,
        durabilityStageTimings: Npv10DurabilityStageSnapshot,
        physicalReservationTelemetry: Npv10PhysicalReservationTelemetry
    ): Npv10QualityCertificateWrite {
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
            put("npv10IngressPackAverageMs", ingressPackAverageMs.toString())
            put("npv10IngressPackMaxMs", ingressPackMaxMs.toString())
            put("npv10IngressPackMaxTimestampNs", ingressPackMaxTimestampNs.toString())
            val earlyReleaseCount = earlyReleasedP010Images.get()
            put("npv10EarlyReleasedCameraImages", earlyReleaseCount.toString())
            put("npv10EarlyCameraReleaseBoundary", "AFTER_PLANE_COPY_BEFORE_LOSSLESS")
            put("npv10EarlyReleaseWorkerHoldAverageMs", (
                earlyP010WorkerImageHoldNanos.get().toDouble() /
                    earlyReleaseCount.coerceAtLeast(1L).toDouble() / 1_000_000.0
                ).toString())
            put("npv10EarlyReleaseWorkerHoldMaximumMs", (
                maxEarlyP010WorkerImageHoldNanos.get() / 1_000_000.0
                ).toString())
            put("npv10WriterAverageMs", evidence.averageWriteTimeMs.toString())
            put("npv10WriterMaxMs", evidence.maxWriteTimeMs.toString())
            put("niv99RecordingRequired", (sessionConfig?.niv99ProcessingRequired == true).toString())
            put("niv99RecordingProcessedFrames", niv99Timing.processedFrames.toString())
            put("niv99RecordingPersistedFrames", niv99Timing.persistedFrames.toString())
            put("niv99RecordingFailedFrames", niv99Timing.failedFrames.toString())
            put("niv99RecordingExpectedFrames", evidence.frameCount.toString())
            put("niv99RecordingAverageMs", niv99Timing.averageMs.toString())
            put("niv99RecordingP50Ms", niv99Timing.p50Ms.toString())
            put("niv99RecordingP95Ms", niv99Timing.p95Ms.toString())
            put("niv99RecordingP99Ms", niv99Timing.p99Ms.toString())
            put("niv99RecordingMaxMs", niv99Timing.maximumMs.toString())
            putAll(writerStageTimings.toEvidenceMetadata())
            putAll(durabilityStageTimings.toEvidenceMetadata())
            putAll(physicalReservationTelemetry.toEvidenceMetadata())
            if (sessionConfig?.niv99ProcessingRequired == true) {
                put("gpuProcessingPath", "NIV99_NATIVE_P010_PACKED10")
                put("gpuProcessingValidated", "false")
            }
        }
        val runtimeEvidence = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = runtimeMetadata::get,
            cpuUsagePercent = finalizedRecordingCpuUsagePercent ?: calculateRecordingCpuUsagePercent(),
            maxFrameTimeMs = maxOf(
                evidence.maxWriteTimeMs,
                ingressPackMaxMs,
                niv99Timing.maximumMs
            ).toFloat()
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
                expectedFrameCount = captureLedger.ingressFrames,
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
                detailMetadata = runtimeMetadata,
                allGpuProcessingClaimed = runtimeEvidence.allGpuProcessingClaimed,
                zeroCopyProcessingClaimed = runtimeEvidence.zeroCopyProcessingClaimed,
                gpuProcessingValidated = runtimeEvidence.gpuProcessingValidated,
                processedRecordingFramesValidated = runtimeEvidence.processedRecordingFramesValidated,
                zeroCopyValidated = runtimeEvidence.zeroCopyValidated,
                performanceTelemetryValidated = runtimeEvidence.performanceTelemetryValidated,
                gpuProcessingPath = runtimeEvidence.gpuProcessingPath,
                zeroCopyEvidencePath = runtimeEvidence.zeroCopyEvidencePath,
                enhancementUniformSignature = runtimeEvidence.enhancementUniformSignature,
                previewEnhancementUniformSignature = runtimeEvidence.previewEnhancementUniformSignature,
                recordingEnhancementUniformSignature = runtimeEvidence.recordingEnhancementUniformSignature,
                enhancementTemporalBlend = runtimeEvidence.enhancementTemporalBlend,
                enhancementSpatialDenoise = runtimeEvidence.enhancementSpatialDenoise,
                enhancementChromaDenoise = runtimeEvidence.enhancementChromaDenoise,
                enhancementSharpening = runtimeEvidence.enhancementSharpening,
                enhancementSubjectDetailGain = runtimeEvidence.enhancementSubjectDetailGain,
                enhancementDetailProfile = runtimeEvidence.enhancementDetailProfile,
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
        return runCatching {
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
            val sidecar = RecordingQualityCertificateStore.write(privateRoot, certificate)
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
            Npv10QualityCertificateWrite(sidecar, certificate.status)
        }.getOrElse { throwable ->
            Log.e(
                TAG,
                "NPV10 external quality evidence persistence failed; " +
                    "master-integrity publication remains independently gated",
                throwable
            )
            Npv10QualityCertificateWrite(
                file = null,
                status = RecordingQualityEvidenceStatus.NOT_PROVEN,
                persistenceFailure = throwable.message ?: throwable.javaClass.simpleName
            )
        }
    }

    private fun calculateRecordingCpuUsagePercent(): Float? {
        val startElapsed = startedElapsedMs
        val startCpuMs = recordingStartProcessCpuMs
        if (startElapsed <= 0L || startCpuMs <= 0L) return null
        val elapsedWallMs = Npv10RecordingDurationPolicy.activeDurationMs(
            durationTimeline,
            SystemClock.elapsedRealtime()
        ).coerceAtLeast(1L)
        val elapsedCpuMs = (Process.getElapsedCpuTime() - startCpuMs).coerceAtLeast(0L)
        return (elapsedCpuMs.toFloat() / elapsedWallMs.toFloat() * 100f).coerceAtLeast(0f)
    }

    private fun updateActiveRecordingDuration(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        _duration.value = Npv10RecordingDurationPolicy.activeDurationMs(
            durationTimeline,
            nowElapsedMs
        )
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

    private fun <T> awaitFutureUninterruptibly(future: Future<T>): T {
        var interrupted = false
        try {
            while (true) {
                try {
                    return future.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    @Synchronized
    fun release() {
        if (releaseComplete) return
        releaseRequested = true
        var releaseFailure: Throwable? = null
        runCatching { stop() }
            .onFailure { failure -> releaseFailure = failure }
        runCatching { awaitP010PackingDrain() }
            .onFailure { failure ->
                if (releaseFailure == null) releaseFailure = failure
            }
        p010PackingExecutor.shutdown()
        try {
            if (!p010PackingExecutor.awaitTermination(
                    EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
            ) {
                p010PackingExecutor.shutdownNow()
                p010PackingExecutor.awaitTermination(
                    EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
            }
        } catch (_: InterruptedException) {
            p010PackingExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        releaseIdleP010PacketBuffers()
        releaseIdleNiv99CanonicalP010Buffers()
        releaseIdleCanonicalP010FallbackBuffers()
        runCatching { discardPreparedPhysicalReservation() }
            .onFailure { failure ->
                if (releaseFailure == null) releaseFailure = failure
            }
        reservationExecutor.shutdownNow()
        val reservationExecutorTerminated = try {
            reservationExecutor.awaitTermination(
                EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (reservationExecutorTerminated && !reservationOwnerReleased) {
            runCatching {
                Npv10ReservationProcessCoordinator.unregister(reservationOwner, store)
            }.onSuccess { cleanup ->
                reservationOwnerReleased = true
                logReservationCleanup("terminal-owner-release", cleanup)
                if (!cleanup.successful && releaseFailure == null) {
                    releaseFailure = IllegalStateException(
                        "Unable to retire NPV10 owner reservations: ${cleanup.failureSummary()}"
                    )
                }
            }.onFailure { failure ->
                if (releaseFailure == null) releaseFailure = failure
            }
        } else if (!reservationExecutorTerminated && releaseFailure == null) {
            // Keep the process lease registered when its worker could still own the inode. A later
            // manager must preserve that path rather than deleting an in-flight fallocate.
            releaseFailure = IllegalStateException(
                "NPV10 reservation executor did not terminate; live process lease retained"
            )
        }
        writerExecutor.shutdownNow()
        configuredNiv99FrameProcessor = null
        // If the reservation worker was still alive, keep release retryable. A later release call
        // can unregister after termination instead of leaking a process-wide lease forever.
        releaseComplete = reservationOwnerReleased
        releaseFailure?.let { failure ->
            throw IllegalStateException("NPV10 terminal release completed with failures", failure)
        }
    }

    private companion object {
        const val TAG = "Npv10RecordingManager"
        const val MEMORY_PAGE_BYTES = 4 * 1024
        // Default for plain packing. Chunked-lossless sessions add one overlap lane before admission;
        // the upstream semaphore and native helper count retain their existing hard bounds.
        const val P010_PACKING_DISPATCH_WORKERS = 1
        const val EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5L
        const val IDLE_STORAGE_HANDOFF_TIMEOUT_MS = 30_000L
    }
}
