package com.neoproc.neoproccamx.core

import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * RuntimeStageTelemetry — cinema-oriented performance monitoring and profiling system.
 *
 * Provides nanosecond-precision stage timing, statistical aggregation (P50/P95/P99),
 * ring-buffer history, multi-stage correlation, real-time jitter detection, and
 * structured log output suitable for professional camera pipeline diagnostics.
 *
 * Thread-safe. All operations are lock-free for active stages, copy-on-write for history.
 */
object RuntimeStageTelemetry {

    private const val TAG = "RuntimeStageTelemetry"
    private const val HISTORY_CAPACITY = 512
    private const val SLOW_THRESHOLD_DEFAULT_MS = 16L   // 1 frame at 60fps
    private const val CRITICAL_THRESHOLD_DEFAULT_MS = 33L // 1 frame at 30fps
    private const val JITTER_THRESHOLD_RATIO = 0.25f   // 25% deviation = jitter
    private const val MATERIAL_JITTER_BUDGET_RATIO = 0.10
    private const val MAX_ACTIVE_STAGES = 64
    /** Minimum interval between slow/critical log lines per stage (prevents log spam). */
    private const val LOG_THROTTLE_INTERVAL_MS = 1_000L

    // ── Stage Timing Data ─────────────────────────────────────────────────────

    data class StageTiming(
        val stageName: String,
        val startTimeNs: Long,
        val endTimeNs: Long = 0L,
        val success: Boolean = false,
        val metadata: Map<String, String> = emptyMap(),
        val threadName: String = Thread.currentThread().name,
        val cpuTimeNs: Long = 0L
    ) {
        val durationNs: Long
            get() = if (endTimeNs > 0L) endTimeNs - startTimeNs else 0L

        val durationMs: Long
            get() = durationNs / 1_000_000L

        val durationUs: Long
            get() = durationNs / 1_000L

        val isComplete: Boolean
            get() = endTimeNs > 0L

        val elapsedSinceStartMs: Long
            get() = (SystemClock.elapsedRealtimeNanos() - startTimeNs) / 1_000_000L
    }

    data class StageStatistics(
        val stageName: String,
        val sampleCount: Long,
        val totalDurationMs: Long,
        val minDurationMs: Long,
        val maxDurationMs: Long,
        val meanDurationMs: Double,
        val p50Ms: Long,
        val p95Ms: Long,
        val p99Ms: Long,
        val stdDevMs: Double,
        val successRate: Double,
        val jitterRatio: Double,
        val slowCount: Long,
        val criticalCount: Long,
        val slowThresholdMs: Long = SLOW_THRESHOLD_DEFAULT_MS,
        val criticalThresholdMs: Long = CRITICAL_THRESHOLD_DEFAULT_MS
    ) {
        /**
         * Relative jitter is only actionable when its absolute variation consumes a meaningful
         * share of this stage's latency budget. This prevents millisecond timer quantization on
         * very fast stages from reporting a false degradation while retaining deadline-scale
         * jitter detection.
         */
        val hasMaterialJitter: Boolean
            get() = jitterRatio >= JITTER_THRESHOLD_RATIO &&
                stdDevMs >= slowThresholdMs.coerceAtLeast(1L) * MATERIAL_JITTER_BUDGET_RATIO

        val isHealthy: Boolean
            get() = successRate > 0.95 &&
                p99Ms < criticalThresholdMs &&
                !hasMaterialJitter

        fun summarize(): String = buildString {
            append("[$stageName] n=$sampleCount ")
            append("P50=${p50Ms}ms P95=${p95Ms}ms P99=${p99Ms}ms ")
            append("min=${minDurationMs}ms max=${maxDurationMs}ms ")
            append("σ=${String.format(Locale.US, "%.2f", stdDevMs)}ms ")
            append("success=${String.format(Locale.US, "%.1f", successRate * 100)}% ")
            append("jitter=${String.format(Locale.US, "%.1f", jitterRatio * 100)}%")
            if (!isHealthy) append(" [DEGRADED]")
        }
    }

    data class MultiStageCorrelation(
        val stages: List<String>,
        val totalPipelineDurationMs: Long,
        val bottleneckStage: String,
        val bottleneckDurationMs: Long,
        val parallelEfficiencyRatio: Double
    )

    // ── Internal State ────────────────────────────────────────────────────────

    private val activeStages = ConcurrentHashMap<String, StageTiming>(MAX_ACTIVE_STAGES)
    private val stageHistories = ConcurrentHashMap<String, LongArray>(64) // ring buffers
    private val stageSuccessHistories = ConcurrentHashMap<String, ByteArray>(64)
    private val stageSlowThresholds = ConcurrentHashMap<String, Long>(64)
    private val stageCriticalThresholds = ConcurrentHashMap<String, Long>(64)
    private val stageFrameDropCounts = ConcurrentHashMap<String, AtomicLong>(64)
    private val historyIndices = ConcurrentHashMap<String, Int>(64)
    private val historySizes = ConcurrentHashMap<String, Int>(64)
    private val lastSlowLogElapsedMs = ConcurrentHashMap<String, AtomicLong>(64)
    private val lastCriticalLogElapsedMs = ConcurrentHashMap<String, AtomicLong>(64)
    private val globalStartNs = SystemClock.elapsedRealtimeNanos()
    @Volatile private var telemetryEnabled = true

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Enable or disable telemetry collection globally.
     * Disabling is zero-cost at call sites.
     */
    fun setEnabled(enabled: Boolean) {
        telemetryEnabled = enabled
    }

    /**
     * Begin timing a named pipeline stage.
     * Handles re-entrant calls gracefully — a second call with the same name
     * silently restarts the timer for that stage.
     */
    fun startStage(stageName: String, metadata: Map<String, String> = emptyMap()) {
        if (!telemetryEnabled) return
        val timing = StageTiming(
            stageName = stageName,
            startTimeNs = SystemClock.elapsedRealtimeNanos(),
            metadata = metadata,
            threadName = Thread.currentThread().name
        )
        activeStages[stageName] = timing
    }

    /**
     * End timing a named stage and record its duration.
     * @param success Whether the stage completed successfully.
     * @param slowThresholdMs Overrides the default slow-stage threshold.
     */
    fun endStage(
        stageName: String,
        success: Boolean = true,
        slowThresholdMs: Long = SLOW_THRESHOLD_DEFAULT_MS
    ) {
        if (!telemetryEnabled) return
        val endNs = SystemClock.elapsedRealtimeNanos()
        val timing = activeStages.remove(stageName) ?: run {
            Log.w(TAG, "endStage called for unknown stage: $stageName")
            return
        }
        val completed = timing.copy(endTimeNs = endNs, success = success)
        recordCompletedStage(completed, slowThresholdMs)
    }

    /**
     * Inline convenience: runs [block], timing it automatically.
     */
    inline fun <T> measureStage(stageName: String, block: () -> T): T {
        startStage(stageName)
        var success = false
        try {
            val result = block()
            success = true
            return result
        } finally {
            endStage(stageName, success)
        }
    }

    /**
     * Records a pre-measured duration directly (e.g. from hardware timestamps).
     */
    fun recordDuration(
        tag: String,
        stage: String,
        durationMs: Long,
        slowThresholdMs: Long = SLOW_THRESHOLD_DEFAULT_MS
    ) {
        if (!telemetryEnabled) return
        val resolvedSlowThresholdMs = slowThresholdMs.coerceAtLeast(1L)
        val resolvedCriticalThresholdMs = criticalThresholdFor(resolvedSlowThresholdMs)
        stageSlowThresholds[stage] = resolvedSlowThresholdMs
        stageCriticalThresholds[stage] = resolvedCriticalThresholdMs
        appendToHistory(stage, durationMs, success = true)
        if (durationMs > resolvedSlowThresholdMs) {
            if (shouldLogThrottled(stage, lastSlowLogElapsedMs)) {
                Log.i(tag, "[SLOW] stage=$stage duration=${durationMs}ms threshold=${resolvedSlowThresholdMs}ms")
            }
        }
        if (durationMs > resolvedCriticalThresholdMs) {
            if (shouldLogThrottled(stage, lastCriticalLogElapsedMs)) {
                Log.i(
                    tag,
                    "[CRITICAL] stage=$stage duration=${durationMs}ms " +
                        "threshold=${resolvedCriticalThresholdMs}ms"
                )
            }
        }
    }

    /**
     * Records a frame drop event.
     */
    fun recordFrameDrop(stage: String, reason: String) {
        if (!telemetryEnabled) return
        val count = stageFrameDropCounts.getOrPut("$stage.drop.$reason") { AtomicLong(0) }
        count.incrementAndGet()
        Log.w(TAG, "Frame drop in '$stage': $reason")
    }

    /**
     * Returns statistics for a named stage.
     * Returns null if no data has been recorded.
     */
    @Synchronized
    fun getStatistics(stageName: String): StageStatistics? {
        val history = getHistorySnapshot(stageName)
        if (history.isEmpty()) return null

        val sorted = history.sorted()
        val n = sorted.size.toLong()
        val totalMs = sorted.sum()
        val mean = totalMs.toDouble() / n
        val variance = sorted.sumOf { v -> (v - mean).let { it * it } } / n
        val stdDev = sqrt(variance)
        val jitterRatio = if (mean > 0.0) stdDev / mean else 0.0

        val successHistory = getSuccessHistorySnapshot(stageName)
        val successCount = if (successHistory.size == history.size) {
            successHistory.count { it }.toLong()
        } else {
            n
        }
        val slowThresholdMs = stageSlowThresholds[stageName] ?: SLOW_THRESHOLD_DEFAULT_MS
        val criticalThresholdMs = stageCriticalThresholds[stageName] ?: CRITICAL_THRESHOLD_DEFAULT_MS
        val slowCount = history.count { it > slowThresholdMs }.toLong()
        val criticalCount = history.count { it > criticalThresholdMs }.toLong()

        return StageStatistics(
            stageName = stageName,
            sampleCount = n,
            totalDurationMs = totalMs,
            minDurationMs = sorted.first(),
            maxDurationMs = sorted.last(),
            meanDurationMs = mean,
            p50Ms = percentile(sorted, 0.50),
            p95Ms = percentile(sorted, 0.95),
            p99Ms = percentile(sorted, 0.99),
            stdDevMs = stdDev,
            successRate = if (n > 0) successCount.toDouble() / n else 1.0,
            jitterRatio = jitterRatio,
            slowCount = slowCount,
            criticalCount = criticalCount,
            slowThresholdMs = slowThresholdMs,
            criticalThresholdMs = criticalThresholdMs
        )
    }

    /**
     * Returns a multi-stage pipeline correlation report.
     */
    fun correlateStages(vararg stageNames: String): MultiStageCorrelation {
        val stats = stageNames.mapNotNull { name -> getStatistics(name)?.let { name to it } }
        val totalMs = stats.sumOf { (_, s) -> s.p95Ms }
        val bottleneck = stats.maxByOrNull { (_, s) -> s.p95Ms }
        val sumP95 = stats.sumOf { (_, s) -> s.p95Ms }.coerceAtLeast(1L)
        val maxP95 = stats.maxOfOrNull { (_, s) -> s.p95Ms }?.coerceAtLeast(1L) ?: 1L
        val parallelEff = maxP95.toDouble() / sumP95.toDouble()

        return MultiStageCorrelation(
            stages = stageNames.toList(),
            totalPipelineDurationMs = totalMs,
            bottleneckStage = bottleneck?.first ?: "",
            bottleneckDurationMs = bottleneck?.second?.p95Ms ?: 0L,
            parallelEfficiencyRatio = parallelEff
        )
    }

    /**
     * Checks if a stage is currently being timed.
     */
    fun isStageActive(stageName: String): Boolean = activeStages.containsKey(stageName)

    /**
     * Returns the elapsed time in milliseconds for an active stage.
     * Returns 0 if stage is not active.
     */
    fun getStageDuration(stageName: String): Long {
        val timing = activeStages[stageName] ?: return 0L
        return (SystemClock.elapsedRealtimeNanos() - timing.startTimeNs) / 1_000_000L
    }

    /**
     * Returns the global pipeline uptime in milliseconds since telemetry init.
     */
    fun getGlobalUptimeMs(): Long =
        (SystemClock.elapsedRealtimeNanos() - globalStartNs) / 1_000_000L

    /**
     * Logs a full pipeline health report at DEBUG level.
     */
    fun logPipelineReport(vararg stageNames: String) {
        if (!telemetryEnabled) return
        Log.d(TAG, "═══ Pipeline Telemetry Report (uptime: ${getGlobalUptimeMs()}ms) ═══")
        stageNames.forEach { name ->
            getStatistics(name)?.let { Log.d(TAG, it.summarize()) }
                ?: Log.d(TAG, "[$name] no data")
        }
        if (stageNames.size > 1) {
            val correlation = correlateStages(*stageNames)
            Log.d(TAG, "Pipeline P95 total: ${correlation.totalPipelineDurationMs}ms | " +
                "Bottleneck: ${correlation.bottleneckStage} (${correlation.bottleneckDurationMs}ms) | " +
                "Parallel efficiency: ${String.format(Locale.US, "%.1f", correlation.parallelEfficiencyRatio * 100)}%")
        }
    }

    /**
     * Resets all collected data for a specific stage.
     */
    fun resetStage(stageName: String) {
        activeStages.remove(stageName)
        stageHistories.remove(stageName)
        stageSuccessHistories.remove(stageName)
        stageSlowThresholds.remove(stageName)
        stageCriticalThresholds.remove(stageName)
        historyIndices.remove(stageName)
        historySizes.remove(stageName)
        lastSlowLogElapsedMs.remove(stageName)
        lastCriticalLogElapsedMs.remove(stageName)
    }

    /**
     * Resets all telemetry data.
     */
    fun resetAll() {
        activeStages.clear()
        stageHistories.clear()
        stageSuccessHistories.clear()
        stageSlowThresholds.clear()
        stageCriticalThresholds.clear()
        stageFrameDropCounts.clear()
        historyIndices.clear()
        historySizes.clear()
        lastSlowLogElapsedMs.clear()
        lastCriticalLogElapsedMs.clear()
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private fun recordCompletedStage(timing: StageTiming, slowThresholdMs: Long) {
        val durationMs = timing.durationMs
        val resolvedSlowThresholdMs = slowThresholdMs.coerceAtLeast(1L)
        val resolvedCriticalThresholdMs = criticalThresholdFor(resolvedSlowThresholdMs)
        stageSlowThresholds[timing.stageName] = resolvedSlowThresholdMs
        stageCriticalThresholds[timing.stageName] = resolvedCriticalThresholdMs
        appendToHistory(timing.stageName, durationMs, success = timing.success)

        if (durationMs > resolvedSlowThresholdMs) {
            if (shouldLogThrottled(timing.stageName, lastSlowLogElapsedMs)) {
                Log.i(TAG, "[SLOW] stage=${timing.stageName} duration=${durationMs}ms thread=${timing.threadName}")
            }
        }
        if (durationMs > resolvedCriticalThresholdMs) {
            if (shouldLogThrottled(timing.stageName, lastCriticalLogElapsedMs)) {
                Log.i(
                    TAG,
                    "[CRITICAL] stage=${timing.stageName} duration=${durationMs}ms " +
                        "threshold=${resolvedCriticalThresholdMs}ms thread=${timing.threadName}"
                )
            }
        }
        if (!timing.success) {
            Log.w(TAG, "[FAIL] stage=${timing.stageName} duration=${durationMs}ms")
        }
    }

    /**
     * Per-stage log throttle — returns true only if enough time has elapsed
     * since the last log for this stage/category. Prevents per-frame spam.
     */
    private fun shouldLogThrottled(
        stageName: String,
        lastLogMap: ConcurrentHashMap<String, AtomicLong>
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        val lastRef = lastLogMap.getOrPut(stageName) { AtomicLong(0L) }
        val last = lastRef.get()
        if (now - last < LOG_THROTTLE_INTERVAL_MS) return false
        return lastRef.compareAndSet(last, now)
    }

    @Synchronized
    private fun appendToHistory(stageName: String, durationMs: Long, success: Boolean) {
        val buf = stageHistories.getOrPut(stageName) { LongArray(HISTORY_CAPACITY) }
        val successBuf = stageSuccessHistories.getOrPut(stageName) { ByteArray(HISTORY_CAPACITY) }
        val idx = historyIndices.getOrDefault(stageName, 0)
        val size = historySizes.getOrDefault(stageName, 0)
        buf[idx % HISTORY_CAPACITY] = durationMs
        successBuf[idx % HISTORY_CAPACITY] = if (success) 1 else 0
        historyIndices[stageName] = (idx + 1) % HISTORY_CAPACITY
        if (size < HISTORY_CAPACITY) historySizes[stageName] = size + 1
    }

    @Synchronized
    private fun getHistorySnapshot(stageName: String): List<Long> {
        val buf = stageHistories[stageName] ?: return emptyList()
        val size = historySizes.getOrDefault(stageName, 0)
        val idx = historyIndices.getOrDefault(stageName, 0)
        return if (size < HISTORY_CAPACITY) {
            buf.take(size)
        } else {
            val result = mutableListOf<Long>()
            for (i in 0 until HISTORY_CAPACITY) {
                result.add(buf[(idx + i) % HISTORY_CAPACITY])
            }
            result
        }
    }

    @Synchronized
    private fun getSuccessHistorySnapshot(stageName: String): List<Boolean> {
        val buf = stageSuccessHistories[stageName] ?: return emptyList()
        val size = historySizes.getOrDefault(stageName, 0)
        val idx = historyIndices.getOrDefault(stageName, 0)
        return if (size < HISTORY_CAPACITY) {
            List(size) { index -> buf[index].toInt() != 0 }
        } else {
            List(HISTORY_CAPACITY) { offset -> buf[(idx + offset) % HISTORY_CAPACITY].toInt() != 0 }
        }
    }

    internal fun criticalThresholdFor(slowThresholdMs: Long): Long {
        val normalizedSlowThresholdMs = slowThresholdMs.coerceAtLeast(1L)
        val doubled = if (normalizedSlowThresholdMs > Long.MAX_VALUE / 2L) {
            Long.MAX_VALUE
        } else {
            normalizedSlowThresholdMs * 2L
        }
        return maxOf(CRITICAL_THRESHOLD_DEFAULT_MS, doubled)
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Long {
        if (sorted.isEmpty()) return 0L
        val idx = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
