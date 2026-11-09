package com.neoproc.neoproccamx.recording

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/** A sink for live conversion progress snapshots (delivered on a background thread). */
typealias ConversionProgressListener = (ConversionProgress) -> Unit

/**
 * ConversionProgress — one immutable progress snapshot for an offline RAW / NPV10 conversion.
 *
 * The converters emit one snapshot per completed frame (see [ConversionEtaEstimator]); the Settings
 * UI renders a determinate progress bar + a live ETA line from it. Nothing here mutates state — the
 * snapshot is a pure value so it can cross the converter → main-thread boundary safely.
 *
 * @param framesTotal 0 means the total is not yet known (the bar shows an indeterminate spinner).
 * @param etaMs       negative means the ETA is not yet estimable (fewer than one frame elapsed).
 * @param pathLabel   the compute path actually in use (e.g. "GPU (Vulkan) + CPU", "CPU ×6"), so the
 *                    label never over-claims GPU on a device where the native accelerator is absent.
 */
data class ConversionProgress(
    val framesDone: Long,
    val framesTotal: Long,
    val elapsedMs: Long,
    val etaMs: Long,
    val framesPerSecond: Double,
    val pathLabel: String
) {
    /** 0f..1f, or a negative sentinel when [framesTotal] is unknown (indeterminate). */
    val fraction: Float
        get() = if (framesTotal > 0L) {
            (framesDone.toDouble() / framesTotal.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            -1f
        }

    val isDeterminate: Boolean get() = framesTotal > 0L

    /**
     * Percent as 0..1000 (Android ProgressBar granularity); 0 when indeterminate. Truncates (not
     * rounds) and reserves 1000 for genuine completion, so the bar never reads full before the last
     * frame is written.
     */
    fun perMille(): Int = when {
        !isDeterminate -> 0
        framesDone >= framesTotal -> 1000
        else -> (fraction * 1000f).toInt().coerceIn(0, 999)
    }

    /** e.g. "42%  ·  128 / 305 frames" (or "128 frames" when the total is unknown). */
    fun countLine(): String =
        if (isDeterminate) {
            // Truncate and cap below 100% until the final frame so it never shows "100% · 239 / 240".
            val pct = if (framesDone >= framesTotal) 100 else (fraction * 100f).toInt().coerceIn(0, 99)
            String.format(Locale.US, "%d%%  ·  %d / %d frames", pct, framesDone, framesTotal)
        } else {
            String.format(Locale.US, "%d frames processed", framesDone)
        }

    /** e.g. "~1m 12s remaining  ·  24.6 fps  ·  GPU (Vulkan) + CPU". */
    fun etaLine(): String {
        val eta = if (etaMs >= 0L) "~${formatShortDuration(etaMs)} remaining" else "estimating…"
        return String.format(Locale.US, "%s  ·  %.1f fps  ·  %s", eta, framesPerSecond, pathLabel)
    }

    companion object {
        /** Human-friendly short duration: "45s", "1m 12s", "2h 3m". Negatives render as an em dash. */
        fun formatShortDuration(ms: Long): String {
            if (ms < 0L) return "—"
            val totalSec = (ms + 500L) / 1000L
            val h = totalSec / 3600L
            val m = (totalSec % 3600L) / 60L
            val s = totalSec % 60L
            return when {
                h > 0L -> String.format(Locale.US, "%dh %dm", h, m)
                m > 0L -> String.format(Locale.US, "%dm %ds", m, s)
                else -> String.format(Locale.US, "%ds", s)
            }
        }
    }
}

/**
 * ConversionEtaEstimator — turns "another frame finished at time T" into a smoothed [ConversionProgress].
 *
 * The remaining time blends two signals so the ETA is neither jittery nor laggy:
 *   • an exponential moving average of recent per-frame durations (responsive to speed changes), and
 *   • the overall average since start (stable once many frames are in).
 * The blend leans on the overall average as more of the clip completes, so the last-second estimate
 * converges to the truth.
 *
 * `nanoTime` is injected on every call (never read internally) so the estimator is fully deterministic
 * and unit-testable on the JVM.
 */
class ConversionEtaEstimator(
    private val framesTotal: Long,
    private val pathLabel: String,
    startNanos: Long,
    private val emaAlpha: Double = 0.25
) {
    private val startNanos: Long = startNanos
    private var lastNanos: Long = startNanos
    private var emaFrameNanos: Double = 0.0
    private var framesDone: Long = 0L

    /** Snapshot before any frame completes (0%, ETA unknown) — for the initial "Starting…" state. */
    fun startSnapshot(): ConversionProgress =
        ConversionProgress(
            framesDone = 0L,
            framesTotal = framesTotal.coerceAtLeast(0L),
            elapsedMs = 0L,
            etaMs = -1L,
            framesPerSecond = 0.0,
            pathLabel = pathLabel
        )

    /** Record that one more frame finished at [nowNanos]; returns the updated snapshot. */
    @Synchronized
    fun onFrameCompleted(nowNanos: Long, exactFramesDone: Long = -1L): ConversionProgress {
        val frameNanos = max(0L, nowNanos - lastNanos)
        lastNanos = nowNanos
        if (exactFramesDone >= 0L) {
            framesDone = exactFramesDone
        } else {
            framesDone += 1L
        }
        emaFrameNanos = if (framesDone == 1L) {
            frameNanos.toDouble()
        } else {
            emaAlpha * frameNanos.toDouble() + (1.0 - emaAlpha) * emaFrameNanos
        }

        val elapsedNanos = max(1L, nowNanos - startNanos)
        val overallPerFrameNanos = elapsedNanos.toDouble() / framesDone.toDouble()

        val etaMs: Long
        if (framesTotal > 0L && framesDone < framesTotal) {
            val remaining = framesTotal - framesDone
            // Weight the overall average more heavily as the job nears completion (progress in 0..1).
            val progress = framesDone.toDouble() / framesTotal.toDouble()
            val overallWeight = 0.4 + 0.6 * progress
            val perFrameNanos = overallWeight * overallPerFrameNanos +
                (1.0 - overallWeight) * emaFrameNanos
            etaMs = (perFrameNanos * remaining / 1_000_000.0).roundToLong().coerceAtLeast(0L)
        } else {
            etaMs = if (framesTotal > 0L) 0L else -1L
        }

        val elapsedMs = elapsedNanos / 1_000_000L
        val fps = framesDone.toDouble() / (elapsedNanos.toDouble() / 1_000_000_000.0)
        return ConversionProgress(
            framesDone = framesDone,
            framesTotal = framesTotal.coerceAtLeast(0L),
            elapsedMs = elapsedMs,
            etaMs = etaMs,
            framesPerSecond = fps,
            pathLabel = pathLabel
        )
    }
}

/**
 * ConversionParallelismPolicy — how many worker threads a per-frame-parallel conversion may use.
 *
 * Uses all cores but one, but never so many that the resident conversion buffers blow the memory
 * budget: cinema frames are large (a 4096×3072 P010 frame ≈ 36 MB; an r210 develop ≈ 72 MB) and the
 * app has documented native-memory pressure, so the worker count is capped to keep peak in-flight
 * frame bytes under [DEFAULT_MEMORY_BUDGET_BYTES]. Always returns ≥ 1 so a single-frame clip still
 * converts. Pure + unit-testable.
 */
object ConversionParallelismPolicy {
    /** Peak resident conversion-buffer budget. Conservative on purpose (no-downgrade > raw speed). */
    const val DEFAULT_MEMORY_BUDGET_BYTES: Long = 384L * 1024L * 1024L

    fun resolveMemoryBudget(maxMemoryBytes: Long = Runtime.getRuntime().maxMemory()): Long {
        return when {
            maxMemoryBytes >= 1024L * 1024L * 1024L -> 1024L * 1024L * 1024L // 1 GB budget for large devices (12GB+ RAM / Large Heap)
            maxMemoryBytes >= 512L * 1024L * 1024L -> 512L * 1024L * 1024L   // 512 MB budget
            else -> DEFAULT_MEMORY_BUDGET_BYTES                         // 384 MB fallback
        }
    }

    fun workerCount(
        frameBytes: Long,
        cores: Int = Runtime.getRuntime().availableProcessors(),
        memoryBudgetBytes: Long = resolveMemoryBudget(),
        pipelineDepth: Int = 2,
        residentCopiesPerFrame: Int = 2
    ): Int {
        val runtimeLimit = DmcConversionTuningRegistry.current().workerLimit
        val cpuCap = minOf((cores - 1).coerceAtLeast(1), runtimeLimit).coerceAtLeast(1)
        if (frameBytes <= 0L) return cpuCap
        val perFrameResident = frameBytes.toDouble() * residentCopiesPerFrame.coerceAtLeast(1)
        // (workers + pipelineDepth) frames may be resident at peak; solve for workers under budget.
        val budgetWorkers = (memoryBudgetBytes.toDouble() / perFrameResident) - pipelineDepth
        return budgetWorkers.toInt().coerceIn(1, cpuCap)
    }
}
