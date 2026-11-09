package com.neoproc.neoproccamx.pipeline

import android.hardware.camera2.CameraAccessException
import android.os.SystemClock
import android.util.Log
import com.neoproc.neoproccamx.core.RuntimeStageTelemetry
import com.neoproc.neoproccamx.infrastructure.error.ErrorHandler
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Reactive health monitor for the camera pipeline.
 * Implements "Silent Recovery" for catchable errors like CameraAccessException.
 */
object PipelineHealthMonitor {
    private const val TAG = "PipelineHealthMonitor"
    private const val DEFAULT_PROFILE_LOG_INTERVAL_MS = 5_000L
    private const val MIN_CRITICAL_LATENCY_SAMPLES = 2L

    private val lastProfileLogElapsedMs = ConcurrentHashMap<String, AtomicLong>()

    fun reportError(tag: String, message: String, throwable: Throwable? = null, onSilentRecovery: (() -> Unit)? = null) {
        val errorContext = ErrorHandler.ErrorContext(
            severity = ErrorHandler.ErrorSeverity.CRITICAL,
            category = ErrorHandler.ErrorCategory.CAMERA_HARDWARE,
            operation = message,
            component = tag
        )
        
        ErrorHandler.handle(throwable ?: RuntimeException(message), errorContext)

        if (throwable is CameraAccessException) {
            Log.w(TAG, "Silent recovery triggered for CameraAccessException: ${throwable.message}")
            // Trigger an immediate, prioritized rebuild using the cached HardwareOverrideProfile
            onSilentRecovery?.invoke()
        } else {
            if (throwable != null) {
                Log.e(tag, message, throwable)
            } else {
                Log.e(tag, message)
            }
        }
    }

    fun profileRuntimeStages(
        stageNames: List<String>,
        maxStages: Int = 3
    ): PipelineTelemetryProfile? {
        if (stageNames.isEmpty()) return null
        val stageStats = stageNames.mapNotNull { stage ->
            RuntimeStageTelemetry.getStatistics(stage)?.let { stats -> stage to stats }
        }
        return buildTelemetryProfile(stageStats, maxStages)
    }

    fun buildTelemetryProfile(
        stageStats: List<Pair<String, RuntimeStageTelemetry.StageStatistics>>,
        maxStages: Int = 3
    ): PipelineTelemetryProfile? {
        if (stageStats.isEmpty()) return null
        val resolvedMaxStages = maxStages.coerceIn(1, stageStats.size)
        val rankedStages = stageStats
            .filter { (_, stats) -> stats.sampleCount > 0 }
            .map { (stage, stats) -> stageHealthSnapshot(stage, stats) }
            .sortedWith(
                compareByDescending<PipelineStageHealthSnapshot> { snapshot ->
                    snapshot.healthBand.severityRank()
                }.thenByDescending { snapshot ->
                    snapshot.p95Ms.toDouble() / snapshot.slowThresholdMs.coerceAtLeast(1L).toDouble()
                }.thenByDescending { snapshot ->
                    snapshot.p95Ms
                }
            )
        if (rankedStages.isEmpty()) return null
        val overallHealth = when {
            rankedStages.any { it.healthBand == PipelineStageHealthBand.CRITICAL } -> PipelineStageHealthBand.CRITICAL
            rankedStages.any { it.healthBand == PipelineStageHealthBand.DEGRADED } -> PipelineStageHealthBand.DEGRADED
            else -> PipelineStageHealthBand.STABLE
        }
        val bottleneck = rankedStages.first()
        return PipelineTelemetryProfile(
            overallHealth = overallHealth,
            bottleneckStage = bottleneck.stageName,
            bottleneckP95Ms = bottleneck.p95Ms,
            topStages = rankedStages.take(resolvedMaxStages)
        )
    }

    fun maybeLogRuntimeTelemetryProfile(
        tag: String,
        stageNames: List<String>,
        minIntervalMs: Long = DEFAULT_PROFILE_LOG_INTERVAL_MS,
        maxStages: Int = 3,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): PipelineTelemetryProfile? {
        val profile = profileRuntimeStages(stageNames, maxStages) ?: return null
        val lastRef = lastProfileLogElapsedMs.getOrPut(tag) { AtomicLong(0L) }
        val lastLoggedAt = lastRef.get()
        if (nowElapsedMs - lastLoggedAt < minIntervalMs) {
            return null
        }
        if (!lastRef.compareAndSet(lastLoggedAt, nowElapsedMs)) {
            return null
        }

        val stageSummary = profile.topStages.joinToString(separator = " | ") { stage ->
            "${stage.stageName}:p95=${stage.p95Ms}ms,p99=${stage.p99Ms}ms," +
                "success=${String.format(Locale.US, "%.1f", stage.successRate * 100.0)}%," +
                "health=${stage.healthBand.name.lowercase()}"
        }
        val message =
            "Pipeline profile health=${profile.overallHealth.name.lowercase()} " +
                "bottleneck=${profile.bottleneckStage}(${profile.bottleneckP95Ms}ms) " +
                "stages=$stageSummary"

        when (profile.overallHealth) {
            PipelineStageHealthBand.CRITICAL -> Log.w(tag, message)
            PipelineStageHealthBand.DEGRADED -> Log.i(tag, message)
            PipelineStageHealthBand.STABLE -> Log.d(tag, message)
        }
        return profile
    }

    private fun stageHealthSnapshot(
        stageName: String,
        stats: RuntimeStageTelemetry.StageStatistics
    ): PipelineStageHealthSnapshot {
        val sampleCount = stats.sampleCount.coerceAtLeast(1)
        val slowRate = stats.slowCount.toDouble() / sampleCount.toDouble()
        val criticalRate = stats.criticalCount.toDouble() / sampleCount.toDouble()
        // A single cold-start/warm-up frame is actionable as degraded telemetry, but it is not
        // sufficient evidence that the whole pipeline is critical. Requiring a repeated latency
        // breach keeps startup outliers from producing sustained critical warnings while p99 and
        // the rate threshold still escalate genuine recurring stalls.
        val hasSustainedCriticalLatency =
            stats.criticalCount >= MIN_CRITICAL_LATENCY_SAMPLES &&
                (criticalRate >= 0.08 || stats.p99Ms >= stats.criticalThresholdMs)
        val health = when {
            stats.successRate < 0.95 ||
                hasSustainedCriticalLatency -> {
                PipelineStageHealthBand.CRITICAL
            }
            stats.successRate < 0.99 ||
                slowRate >= 0.15 ||
                stats.hasMaterialJitter ||
                stats.p95Ms >= stats.slowThresholdMs -> {
                PipelineStageHealthBand.DEGRADED
            }
            else -> PipelineStageHealthBand.STABLE
        }
        return PipelineStageHealthSnapshot(
            stageName = stageName,
            p95Ms = stats.p95Ms,
            p99Ms = stats.p99Ms,
            successRate = stats.successRate,
            slowRate = slowRate,
            criticalRate = criticalRate,
            jitterRatio = stats.jitterRatio,
            healthBand = health,
            slowThresholdMs = stats.slowThresholdMs,
            criticalThresholdMs = stats.criticalThresholdMs
        )
    }

    private fun PipelineStageHealthBand.severityRank(): Int = when (this) {
        PipelineStageHealthBand.STABLE -> 0
        PipelineStageHealthBand.DEGRADED -> 1
        PipelineStageHealthBand.CRITICAL -> 2
    }
}

enum class PipelineStageHealthBand {
    STABLE,
    DEGRADED,
    CRITICAL
}

data class PipelineStageHealthSnapshot(
    val stageName: String,
    val p95Ms: Long,
    val p99Ms: Long,
    val successRate: Double,
    val slowRate: Double,
    val criticalRate: Double,
    val jitterRatio: Double,
    val healthBand: PipelineStageHealthBand,
    val slowThresholdMs: Long,
    val criticalThresholdMs: Long
)

data class PipelineTelemetryProfile(
    val overallHealth: PipelineStageHealthBand,
    val bottleneckStage: String,
    val bottleneckP95Ms: Long,
    val topStages: List<PipelineStageHealthSnapshot>
)
