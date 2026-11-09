package com.neoproc.neoproccamx.recording

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Thermal operating bands used by the offline DMC scheduler.
 *
 * This is deliberately independent from Android so the decision system can be exhaustively exercised on
 * the host JVM. Platform thermal status and thermal-headroom samples are translated by [fromSignals].
 */
internal enum class DmcThermalBand(val severity: Int) {
    NOMINAL(0),
    WARM(1),
    ELEVATED(2),
    HOT(3),
    CRITICAL(4),
    EMERGENCY(5);

    companion object {
        fun fromSignals(platformStatus: Int?, thermalHeadroom: Float?): DmcThermalBand {
            val fromStatus = when {
                platformStatus == null || platformStatus <= 0 -> NOMINAL
                platformStatus == 1 -> WARM
                platformStatus == 2 -> ELEVATED
                platformStatus == 3 -> HOT
                platformStatus == 4 -> CRITICAL
                else -> EMERGENCY
            }
            val validHeadroom = thermalHeadroom?.takeIf { it.isFinite() && it >= 0f }
            val fromHeadroom = when {
                validHeadroom == null -> NOMINAL
                validHeadroom >= 1.00f -> HOT
                validHeadroom >= 0.90f -> ELEVATED
                validHeadroom >= 0.70f -> WARM
                else -> NOMINAL
            }
            return if (fromStatus.severity >= fromHeadroom.severity) fromStatus else fromHeadroom
        }

        fun oneStepBelow(value: DmcThermalBand): DmcThermalBand =
            entries[(value.ordinal - 1).coerceAtLeast(0)]
    }
}

/** Immutable description of one conversion workload. Pixel-quality controls intentionally do not exist. */
internal data class DmcConversionWorkload(
    val route: String,
    val width: Int,
    val height: Int,
    val sourceFps: Int,
    val compressedOutput: Boolean
) {
    init {
        require(route.isNotBlank()) { "DMC conversion route must not be blank" }
        require(width >= 0 && height >= 0) { "DMC dimensions must not be negative" }
        require(sourceFps > 0) { "DMC source FPS must be positive" }
    }

    val targetFrameIntervalNanos: Long
        get() = max(1L, 1_000_000_000L / sourceFps.toLong())

    companion object {
        val UNCONFIGURED = DmcConversionWorkload(
            route = "UNCONFIGURED",
            width = 0,
            height = 0,
            sourceFps = 60,
            compressedOutput = false
        )
    }
}

/**
 * Scheduling-only decision. There are intentionally no resolution, cadence, precision, colour, NIV99,
 * codec-profile, or bitrate-scale fields here, making a quality downgrade impossible through this API.
 */
internal data class DmcExecutionTuning(
    val generation: Long,
    val thermalBand: DmcThermalBand,
    val workerLimit: Int,
    val inFlightLimit: Int,
    val allowConcurrentCpuGpuDevelop: Boolean,
    val pauseAtSafeBoundary: Boolean,
    val targetFrameIntervalNanos: Long
) {
    init {
        require(generation >= 0L) { "DMC generation must not be negative" }
        require(workerLimit > 0) { "DMC worker limit must be positive" }
        require(inFlightLimit > 0) { "DMC in-flight limit must be positive" }
        require(targetFrameIntervalNanos > 0L) { "DMC target frame interval must be positive" }
    }

    companion object {
        val UNRESTRICTED = DmcExecutionTuning(
            generation = 0L,
            thermalBand = DmcThermalBand.NOMINAL,
            workerLimit = Int.MAX_VALUE,
            inFlightLimit = Int.MAX_VALUE,
            allowConcurrentCpuGpuDevelop = true,
            pauseAtSafeBoundary = false,
            targetFrameIntervalNanos = 1L
        )
    }
}

/** Final bounded-memory timing/thermal evidence for one conversion transaction. */
internal data class DmcConversionPerformanceEvidence(
    val generation: Long,
    val route: String,
    val width: Int,
    val height: Int,
    val sourceFps: Int,
    val compressedOutput: Boolean,
    val completedFrames: Long,
    val elapsedNanos: Long,
    val averageFramesPerSecond: Double,
    val p50FrameNanos: Long,
    val p95FrameNanos: Long,
    val p99FrameNanos: Long,
    val maximumFrameNanos: Long,
    val maximumThermalBand: DmcThermalBand,
    val thermalTransitions: Long,
    val totalPacingNanos: Long,
    val qualityInvariant: Boolean = true
)

/**
 * Pure, allocation-free-on-frame DMC thermal governor.
 *
 * Escalation is immediate. Recovery is hysteretic and drops by at most one band after a stable cool
 * interval, preventing rapid worker/depth oscillation around an Android thermal threshold. Frame timing is
 * retained in a fixed circular reservoir; percentile allocation occurs only once when evidence is requested.
 */
internal class DmcSustainedConversionGovernor(
    private val logicalCores: Int,
    private val startedAtNanos: Long,
    private val recoveryStableNanos: Long = DEFAULT_RECOVERY_STABLE_NANOS,
    timingCapacity: Int = DEFAULT_TIMING_CAPACITY
) {
    private val timings = LongArray(timingCapacity.coerceAtLeast(MIN_TIMING_CAPACITY))
    private var timingWriteIndex = 0
    private var retainedTimingCount = 0
    private var completedFrames = 0L
    private var lastCompletedFrameNumber = 0L
    private var workloadStartedAtNanos = startedAtNanos
    private var lastCompletionNanos = startedAtNanos
    private var lastEvidenceNanos = startedAtNanos
    private var emaFrameNanos = 0.0
    private var workload = DmcConversionWorkload.UNCONFIGURED
    private var generation = 0L
    private var currentBand = DmcThermalBand.NOMINAL
    private var maximumBand = DmcThermalBand.NOMINAL
    private var recoveryCandidate: DmcThermalBand? = null
    private var recoveryCandidateSinceNanos = Long.MIN_VALUE
    private var thermalTransitions = 0L
    private var totalPacingNanos = 0L
    private var currentTuning = resolveTuning()

    init {
        require(logicalCores > 0) { "DMC logical core count must be positive" }
        require(startedAtNanos >= 0L) { "DMC start time must not be negative" }
        require(recoveryStableNanos >= 0L) { "DMC recovery interval must not be negative" }
    }

    @Synchronized
    fun configure(config: DmcConversionWorkload): DmcExecutionTuning {
        workload = config
        generation = Math.addExact(generation, 1L)
        workloadStartedAtNanos = lastEvidenceNanos
        completedFrames = 0L
        lastCompletedFrameNumber = 0L
        lastCompletionNanos = workloadStartedAtNanos
        emaFrameNanos = 0.0
        timingWriteIndex = 0
        retainedTimingCount = 0
        timings.fill(0L)
        totalPacingNanos = 0L
        maximumBand = currentBand
        thermalTransitions = 0L
        currentTuning = resolveTuning()
        return currentTuning
    }

    /** Applies one status/headroom observation and returns the current immutable scheduling decision. */
    @Synchronized
    fun observeThermal(
        platformStatus: Int?,
        thermalHeadroom: Float?,
        nowNanos: Long
    ): DmcExecutionTuning {
        require(nowNanos >= 0L) { "DMC thermal sample time must not be negative" }
        lastEvidenceNanos = max(lastEvidenceNanos, nowNanos)
        val observed = DmcThermalBand.fromSignals(platformStatus, thermalHeadroom)
        when {
            observed.severity > currentBand.severity -> transitionTo(observed)
            observed.severity == currentBand.severity -> clearRecoveryCandidate()
            else -> applyRecoveryHysteresis(observed, nowNanos)
        }
        maximumBand = if (currentBand.severity > maximumBand.severity) currentBand else maximumBand
        currentTuning = resolveTuning()
        return currentTuning
    }

    /**
     * Records an ordered writer/commit boundary and returns an optional pacing delay. The delay is always
     * bounded by the source-cadence slack, so DMC never intentionally paces a healthy conversion below its
     * selected FPS. A delayed callback covering several frames is normalized to a per-frame duration.
     */
    @Synchronized
    fun onFramesCommitted(exactFramesDone: Long, nowNanos: Long): Long {
        if (exactFramesDone <= lastCompletedFrameNumber || nowNanos < lastCompletionNanos) return 0L
        val frameDelta = exactFramesDone - lastCompletedFrameNumber
        val elapsed = max(1L, nowNanos - lastCompletionNanos)
        val perFrame = max(1L, elapsed / frameDelta)
        lastCompletedFrameNumber = exactFramesDone
        completedFrames = exactFramesDone
        lastCompletionNanos = nowNanos
        lastEvidenceNanos = max(lastEvidenceNanos, nowNanos)
        timings[timingWriteIndex] = perFrame
        timingWriteIndex = (timingWriteIndex + 1) % timings.size
        retainedTimingCount = min(timings.size, retainedTimingCount + 1)
        emaFrameNanos = if (emaFrameNanos == 0.0) {
            perFrame.toDouble()
        } else {
            FRAME_EMA_ALPHA * perFrame.toDouble() + (1.0 - FRAME_EMA_ALPHA) * emaFrameNanos
        }
        return recommendedPacingNanos()
    }

    @Synchronized
    fun recordPacingApplied(nanos: Long) {
        if (nanos <= 0L) return
        totalPacingNanos = saturatedAdd(totalPacingNanos, nanos)
        lastCompletionNanos = saturatedAdd(lastCompletionNanos, nanos)
        lastEvidenceNanos = max(lastEvidenceNanos, lastCompletionNanos)
    }

    @Synchronized
    fun tuning(): DmcExecutionTuning = currentTuning

    @Synchronized
    fun evidence(nowNanos: Long): DmcConversionPerformanceEvidence {
        val sample = timings.copyOf(retainedTimingCount).apply { sort() }
        val elapsed = max(0L, max(nowNanos, lastEvidenceNanos) - workloadStartedAtNanos)
        return DmcConversionPerformanceEvidence(
            generation = generation,
            route = workload.route,
            width = workload.width,
            height = workload.height,
            sourceFps = workload.sourceFps,
            compressedOutput = workload.compressedOutput,
            completedFrames = completedFrames,
            elapsedNanos = elapsed,
            averageFramesPerSecond = if (completedFrames > 0L && elapsed > 0L) {
                completedFrames.toDouble() * 1_000_000_000.0 / elapsed.toDouble()
            } else {
                0.0
            },
            p50FrameNanos = percentile(sample, 0.50),
            p95FrameNanos = percentile(sample, 0.95),
            p99FrameNanos = percentile(sample, 0.99),
            maximumFrameNanos = sample.lastOrNull() ?: 0L,
            maximumThermalBand = maximumBand,
            thermalTransitions = thermalTransitions,
            totalPacingNanos = totalPacingNanos
        )
    }

    private fun recommendedPacingNanos(): Long {
        if (currentTuning.pauseAtSafeBoundary || emaFrameNanos <= 0.0) return 0L
        val utilization = when (currentBand) {
            DmcThermalBand.NOMINAL -> return 0L
            DmcThermalBand.WARM -> 0.96
            DmcThermalBand.ELEVATED -> 0.88
            DmcThermalBand.HOT -> 0.76
            DmcThermalBand.CRITICAL,
            DmcThermalBand.EMERGENCY -> return 0L
        }
        val observed = max(1L, emaFrameNanos.roundToLong())
        val cadenceSlack = max(0L, workload.targetFrameIntervalNanos - observed)
        if (cadenceSlack == 0L) return 0L
        val thermalDelay = max(0L, (observed / utilization).roundToLong() - observed)
        return min(min(thermalDelay, cadenceSlack), MAX_PACING_NANOS)
    }

    private fun applyRecoveryHysteresis(observed: DmcThermalBand, nowNanos: Long) {
        if (recoveryCandidate != observed) {
            recoveryCandidate = observed
            recoveryCandidateSinceNanos = nowNanos
            return
        }
        val stableFor = nowNanos - recoveryCandidateSinceNanos
        if (stableFor >= recoveryStableNanos) {
            transitionTo(DmcThermalBand.oneStepBelow(currentBand))
            if (observed.severity < currentBand.severity) {
                recoveryCandidate = observed
                recoveryCandidateSinceNanos = nowNanos
            } else {
                clearRecoveryCandidate()
            }
        }
    }

    private fun transitionTo(next: DmcThermalBand) {
        if (next == currentBand) return
        currentBand = next
        thermalTransitions = Math.addExact(thermalTransitions, 1L)
        clearRecoveryCandidate()
    }

    private fun clearRecoveryCandidate() {
        recoveryCandidate = null
        recoveryCandidateSinceNanos = Long.MIN_VALUE
    }

    private fun resolveTuning(): DmcExecutionTuning {
        val cores = logicalCores.coerceAtLeast(1)
        val workerLimit = when (currentBand) {
            // Preserve the existing all-but-one-core throughput while the platform reports real
            // thermal headroom. DMC becomes restrictive only in response to measured pressure.
            DmcThermalBand.NOMINAL -> (cores - 1).coerceAtLeast(1)
            DmcThermalBand.WARM -> (cores - 2).coerceIn(1, MAX_WARM_WORKERS)
            DmcThermalBand.ELEVATED -> ceil(cores / 2.0).toInt().coerceIn(1, MAX_ELEVATED_WORKERS)
            DmcThermalBand.HOT -> min(2, cores)
            DmcThermalBand.CRITICAL,
            DmcThermalBand.EMERGENCY -> 1
        }
        val inFlightLimit = when (currentBand) {
            DmcThermalBand.NOMINAL -> 4
            DmcThermalBand.WARM -> 3
            DmcThermalBand.ELEVATED -> 2
            DmcThermalBand.HOT,
            DmcThermalBand.CRITICAL,
            DmcThermalBand.EMERGENCY -> 1
        }
        return DmcExecutionTuning(
            generation = generation,
            thermalBand = currentBand,
            workerLimit = workerLimit,
            inFlightLimit = inFlightLimit,
            allowConcurrentCpuGpuDevelop = currentBand == DmcThermalBand.NOMINAL,
            pauseAtSafeBoundary = currentBand.severity >= DmcThermalBand.CRITICAL.severity,
            targetFrameIntervalNanos = workload.targetFrameIntervalNanos
        )
    }

    private fun percentile(sorted: LongArray, fraction: Double): Long {
        if (sorted.isEmpty()) return 0L
        val rank = ceil(sorted.size * fraction).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        const val FRAME_EMA_ALPHA = 0.20
        const val MIN_TIMING_CAPACITY = 32
        const val DEFAULT_TIMING_CAPACITY = 512
        const val MAX_WARM_WORKERS = 4
        const val MAX_ELEVATED_WORKERS = 3
        const val MAX_PACING_NANOS = 8_000_000L
        const val DEFAULT_RECOVERY_STABLE_NANOS = 15_000_000_000L
    }
}

/**
 * Process-wide scheduling publication for the one exclusive Settings conversion. A generation-owned lease
 * prevents a stale session from clearing a newer one. The unrestricted state preserves legacy/test callers.
 */
internal object DmcConversionTuningRegistry {
    private data class Published(val owner: Long, val tuning: DmcExecutionTuning)

    private val nextOwner = AtomicLong(1L)
    private val published = AtomicReference<Published?>(null)

    fun acquire(initial: DmcExecutionTuning): Lease {
        val owner = nextOwner.getAndIncrement()
        check(published.compareAndSet(null, Published(owner, initial))) {
            "A DMC conversion tuning session is already active"
        }
        return Lease(owner)
    }

    fun current(): DmcExecutionTuning = published.get()?.tuning ?: DmcExecutionTuning.UNRESTRICTED

    internal class Lease(private val owner: Long) : AutoCloseable {
        fun publish(tuning: DmcExecutionTuning): Boolean {
            while (true) {
                val current = published.get() ?: return false
                if (current.owner != owner) return false
                if (published.compareAndSet(current, Published(owner, tuning))) return true
            }
        }

        override fun close() {
            while (true) {
                val current = published.get() ?: return
                if (current.owner != owner) return
                if (published.compareAndSet(current, null)) return
            }
        }
    }
}
