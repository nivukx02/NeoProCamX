package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class SecondaryDegradationLevel(val rank: Int) {
    FULL(0),
    REDUCE_AI_FREQUENCY(1),
    REDUCE_ANALYTICS_FREQUENCY(2),
    SIMPLIFY_PREVIEW_EFFECTS(3),
    REDUCE_PREVIEW_RESOLUTION(4),
    SKIP_STALE_PREVIEW(5),
    LIGHTWEIGHT_PREVIEW_AND_DEFER_VFX(6)
}

data class PipelineLoadSignal(
    val diagnostics: SensorPipelineDiagnosticsSnapshot,
    val queueCapacity: Map<FrameDomain, Int> = emptyMap(),
    val encoderLatencyBudgetNs: Long,
    val previewLatencyBudgetNs: Long,
    val minimumStorageWriteMegabytesPerSecond: Float? = null
)

data class PipelineAdaptationDecision(
    val level: SecondaryDegradationLevel,
    val aiCadenceDivisor: Int,
    val analyticsCadenceDivisor: Int,
    val vfxCadenceDivisor: Int,
    val previewQueueLimit: Int,
    val aiQueueLimit: Int,
    val vfxQueueLimit: Int,
    val simplifyPreviewEffects: Boolean,
    val reducePreviewResolution: Boolean,
    val preferLatestPreviewFrame: Boolean,
    val deferBackgroundVfx: Boolean,
    val recordingProtected: Boolean = true,
    val requestedRecordingQualityUnchanged: Boolean = true,
    val reasons: Set<String>
)

/** Pure policy: only secondary domains degrade; requested recording FPS/quality are not mutated. */
object AdaptivePipelinePolicy {
    fun resolve(signal: PipelineLoadSignal): PipelineAdaptationDecision {
        val diagnostics = signal.diagnostics
        val resources = diagnostics.resources
        var level = SecondaryDegradationLevel.FULL
        val reasons = linkedSetOf<String>()

        fun escalate(candidate: SecondaryDegradationLevel, reason: String) {
            if (candidate.rank > level.rank) level = candidate
            reasons += reason
        }

        val thermal = resources?.thermal?.state
        when {
            thermal == PipelineThermalState.SHUTDOWN || thermal == PipelineThermalState.EMERGENCY ->
                escalate(SecondaryDegradationLevel.LIGHTWEIGHT_PREVIEW_AND_DEFER_VFX, "thermal-${thermal.name.lowercase()}")
            thermal == PipelineThermalState.CRITICAL ->
                escalate(SecondaryDegradationLevel.SKIP_STALE_PREVIEW, "thermal-critical")
            thermal == PipelineThermalState.SEVERE ->
                escalate(SecondaryDegradationLevel.REDUCE_PREVIEW_RESOLUTION, "thermal-severe")
            thermal == PipelineThermalState.MODERATE ->
                escalate(SecondaryDegradationLevel.REDUCE_ANALYTICS_FREQUENCY, "thermal-moderate")
            thermal == PipelineThermalState.LIGHT ->
                escalate(SecondaryDegradationLevel.REDUCE_AI_FREQUENCY, "thermal-light")
        }

        resources?.cpuUtilization?.let { utilization ->
            when {
                utilization >= 0.95f -> escalate(
                    SecondaryDegradationLevel.SKIP_STALE_PREVIEW,
                    "cpu-critical"
                )
                utilization >= 0.85f -> escalate(
                    SecondaryDegradationLevel.SIMPLIFY_PREVIEW_EFFECTS,
                    "cpu-high"
                )
                utilization >= 0.72f -> escalate(
                    SecondaryDegradationLevel.REDUCE_AI_FREQUENCY,
                    "cpu-elevated"
                )
            }
        }
        resources?.gpuUtilization?.let { utilization ->
            when {
                utilization >= 0.95f -> escalate(
                    SecondaryDegradationLevel.LIGHTWEIGHT_PREVIEW_AND_DEFER_VFX,
                    "gpu-critical"
                )
                utilization >= 0.86f -> escalate(
                    SecondaryDegradationLevel.SIMPLIFY_PREVIEW_EFFECTS,
                    "gpu-high"
                )
            }
        }
        resources?.memoryPressure?.let { pressure ->
            when {
                pressure >= 0.92f -> escalate(
                    SecondaryDegradationLevel.LIGHTWEIGHT_PREVIEW_AND_DEFER_VFX,
                    "memory-critical"
                )
                pressure >= 0.80f -> escalate(
                    SecondaryDegradationLevel.REDUCE_PREVIEW_RESOLUTION,
                    "memory-high"
                )
            }
        }
        resources?.storageQueuePressure?.let { pressure ->
            when {
                pressure >= 0.75f -> escalate(
                    SecondaryDegradationLevel.LIGHTWEIGHT_PREVIEW_AND_DEFER_VFX,
                    "storage-queue-critical"
                )
                pressure >= 0.50f -> escalate(
                    SecondaryDegradationLevel.REDUCE_PREVIEW_RESOLUTION,
                    "storage-queue-high"
                )
                pressure >= 0.25f -> escalate(
                    SecondaryDegradationLevel.REDUCE_ANALYTICS_FREQUENCY,
                    "storage-queue-elevated"
                )
            }
        }

        val encoder = diagnostics.stageTiming[PipelineStage.ENCODER]
        if (diagnostics.droppedRecordingFrames > 0L || (encoder?.missedDeadlines ?: 0L) > 0L) {
            escalate(
                SecondaryDegradationLevel.LIGHTWEIGHT_PREVIEW_AND_DEFER_VFX,
                "protect-recording-integrity"
            )
        } else if ((encoder?.maximumProcessingNs ?: 0L) > signal.encoderLatencyBudgetNs) {
            escalate(SecondaryDegradationLevel.SKIP_STALE_PREVIEW, "encoder-latency")
        }
        val preview = diagnostics.stageTiming[PipelineStage.PREVIEW]
        if ((preview?.maximumProcessingNs ?: 0L) > signal.previewLatencyBudgetNs) {
            escalate(SecondaryDegradationLevel.SKIP_STALE_PREVIEW, "preview-latency")
        }

        signal.minimumStorageWriteMegabytesPerSecond?.let { minimum ->
            val actual = resources?.storageWriteMegabytesPerSecond
            if (actual != null && actual < minimum) {
                escalate(
                    SecondaryDegradationLevel.LIGHTWEIGHT_PREVIEW_AND_DEFER_VFX,
                    "storage-throughput"
                )
            }
        }

        signal.queueCapacity.forEach { (domain, capacity) ->
            if (capacity <= 0) return@forEach
            val stage = domain.toPipelineStage() ?: return@forEach
            val depth = diagnostics.stageTiming[stage]?.currentQueueDepth ?: 0
            val pressure = depth.toFloat() / capacity.toFloat()
            when {
                domain == FrameDomain.ENCODER && pressure >= 0.75f -> escalate(
                    SecondaryDegradationLevel.LIGHTWEIGHT_PREVIEW_AND_DEFER_VFX,
                    "encoder-queue-pressure"
                )
                domain == FrameDomain.PREVIEW && pressure >= 0.75f -> escalate(
                    SecondaryDegradationLevel.SKIP_STALE_PREVIEW,
                    "preview-queue-pressure"
                )
                domain == FrameDomain.AI && pressure >= 0.75f -> escalate(
                    SecondaryDegradationLevel.REDUCE_AI_FREQUENCY,
                    "ai-queue-pressure"
                )
                domain == FrameDomain.VFX && pressure >= 0.75f -> escalate(
                    SecondaryDegradationLevel.REDUCE_ANALYTICS_FREQUENCY,
                    "vfx-queue-pressure"
                )
            }
        }

        val rank = level.rank
        return PipelineAdaptationDecision(
            level = level,
            aiCadenceDivisor = when {
                rank >= 6 -> 8
                rank >= 4 -> 4
                rank >= 2 -> 2
                rank >= 1 -> 2
                else -> 1
            },
            analyticsCadenceDivisor = when {
                rank >= 6 -> 8
                rank >= 4 -> 4
                rank >= 2 -> 2
                else -> 1
            },
            vfxCadenceDivisor = when {
                rank >= 6 -> Int.MAX_VALUE
                rank >= 5 -> 8
                rank >= 3 -> 4
                rank >= 2 -> 2
                else -> 1
            },
            previewQueueLimit = when {
                rank >= 5 -> 1
                rank >= 3 -> 2
                else -> 4
            },
            aiQueueLimit = if (rank >= 1) 1 else 3,
            vfxQueueLimit = if (rank >= 2) 1 else 2,
            simplifyPreviewEffects = rank >= 3,
            reducePreviewResolution = rank >= 4,
            preferLatestPreviewFrame = rank >= 5,
            deferBackgroundVfx = rank >= 6,
            reasons = reasons.ifEmpty { setOf("stable-full-processing") }
        )
    }

    private fun FrameDomain.toPipelineStage(): PipelineStage? = when (this) {
        FrameDomain.CAPTURE -> PipelineStage.SENSOR_CAPTURE
        FrameDomain.RECORDING -> PipelineStage.RECORDING
        FrameDomain.METADATA -> PipelineStage.METADATA
        FrameDomain.ENCODER -> PipelineStage.ENCODER
        FrameDomain.STORAGE -> PipelineStage.STORAGE
        FrameDomain.RAW_PROCESSING -> PipelineStage.RAW_PROCESSING
        FrameDomain.PREVIEW -> PipelineStage.PREVIEW
        FrameDomain.AI -> PipelineStage.AI
        FrameDomain.IMAGE_PROCESSING,
        FrameDomain.ANALYTICS -> PipelineStage.IMAGE_PROCESSING
        FrameDomain.VFX -> PipelineStage.VFX
        FrameDomain.DISPLAY -> PipelineStage.DISPLAY
        FrameDomain.UI_MONITORING -> null
    }
}

/** Immediate escalation and hysteretic one-level recovery prevent quality/cadence oscillation. */
class AdaptivePipelineController(
    initial: PipelineAdaptationDecision
) {
    private val current = AtomicReference(initial)
    private val stableSamples = AtomicInteger(0)

    fun update(resolved: PipelineAdaptationDecision): PipelineAdaptationDecision {
        while (true) {
            val previous = current.get()
            val next = when {
                resolved.level.rank >= previous.level.rank -> {
                    stableSamples.set(0)
                    resolved
                }
                stableSamples.incrementAndGet() < RECOVERY_STABLE_SAMPLES -> previous
                else -> {
                    stableSamples.set(0)
                    val oneLevelLower = SecondaryDegradationLevel.entries
                        .first { it.rank == previous.level.rank - 1 }
                    resolved.copy(level = oneLevelLower).normalizedForLevel(oneLevelLower)
                }
            }
            if (current.compareAndSet(previous, next)) return next
        }
    }

    fun snapshot(): PipelineAdaptationDecision = current.get()

    private fun PipelineAdaptationDecision.normalizedForLevel(
        recoveredLevel: SecondaryDegradationLevel
    ): PipelineAdaptationDecision {
        val rank = recoveredLevel.rank
        return copy(
            aiCadenceDivisor = if (rank >= 4) 4 else if (rank >= 1) 2 else 1,
            analyticsCadenceDivisor = if (rank >= 4) 4 else if (rank >= 2) 2 else 1,
            vfxCadenceDivisor = if (rank >= 5) 8 else if (rank >= 3) 4 else if (rank >= 2) 2 else 1,
            previewQueueLimit = if (rank >= 5) 1 else if (rank >= 3) 2 else 4,
            aiQueueLimit = if (rank >= 1) 1 else 3,
            vfxQueueLimit = if (rank >= 2) 1 else 2,
            simplifyPreviewEffects = rank >= 3,
            reducePreviewResolution = rank >= 4,
            preferLatestPreviewFrame = rank >= 5,
            deferBackgroundVfx = rank >= 6
        )
    }

    private companion object {
        const val RECOVERY_STABLE_SAMPLES = 8
    }
}
