package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.RuntimeStageTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineHealthMonitorTest {

    @Test
    fun buildTelemetryProfileReturnsNullWhenNoStageStats() {
        val profile = PipelineHealthMonitor.buildTelemetryProfile(emptyList())
        assertNull(profile)
    }

    @Test
    fun buildTelemetryProfileRanksStagesAndEscalatesCriticalHealth() {
        val decode = stats(
            stageName = "decode",
            sampleCount = 120,
            p95Ms = 18,
            p99Ms = 28,
            successRate = 0.998,
            slowCount = 2,
            criticalCount = 0,
            jitterRatio = 0.08
        )
        val write = stats(
            stageName = "write",
            sampleCount = 120,
            p95Ms = 64,
            p99Ms = 135,
            successRate = 0.90,
            slowCount = 32,
            criticalCount = 12,
            jitterRatio = 0.34
        )

        val profile = PipelineHealthMonitor.buildTelemetryProfile(
            stageStats = listOf("decode" to decode, "write" to write),
            maxStages = 2
        )

        assertNotNull(profile)
        assertEquals(PipelineStageHealthBand.CRITICAL, profile!!.overallHealth)
        assertEquals("write", profile.bottleneckStage)
        assertEquals(64L, profile.bottleneckP95Ms)
        assertEquals(2, profile.topStages.size)
        assertEquals("write", profile.topStages[0].stageName)
        assertEquals(PipelineStageHealthBand.CRITICAL, profile.topStages[0].healthBand)
    }

    @Test
    fun buildTelemetryProfileCapsTopStagesByMaxStages() {
        val stageA = stats("A", sampleCount = 80, p95Ms = 50, p99Ms = 70)
        val stageB = stats("B", sampleCount = 80, p95Ms = 30, p99Ms = 45)
        val stageC = stats("C", sampleCount = 80, p95Ms = 20, p99Ms = 30)

        val profile = PipelineHealthMonitor.buildTelemetryProfile(
            stageStats = listOf("A" to stageA, "B" to stageB, "C" to stageC),
            maxStages = 2
        )

        assertNotNull(profile)
        assertEquals(PipelineStageHealthBand.DEGRADED, profile!!.overallHealth)
        assertEquals(2, profile.topStages.size)
        assertEquals(listOf("A", "B"), profile.topStages.map { it.stageName })
        assertTrue(profile.topStages[0].p95Ms >= profile.topStages[1].p95Ms)
    }

    @Test
    fun buildTelemetryProfileHonorsStageSpecificLatencyBudgets() {
        val recordingStart = stats(
            stageName = "startRecording.total",
            sampleCount = 8,
            p95Ms = 824,
            p99Ms = 824,
            slowThresholdMs = 1_300,
            criticalThresholdMs = 2_600
        )
        val frameProcessing = stats(
            stageName = "processFrame",
            sampleCount = 8,
            p95Ms = 36,
            p99Ms = 36,
            slowCount = 2,
            slowThresholdMs = 28,
            criticalThresholdMs = 56
        )

        val profile = PipelineHealthMonitor.buildTelemetryProfile(
            stageStats = listOf(
                "startRecording.total" to recordingStart,
                "processFrame" to frameProcessing
            ),
            maxStages = 2
        )

        assertNotNull(profile)
        assertEquals(PipelineStageHealthBand.DEGRADED, profile!!.overallHealth)
        assertEquals("processFrame", profile.bottleneckStage)
        assertEquals(PipelineStageHealthBand.DEGRADED, profile.topStages[0].healthBand)
        assertEquals(PipelineStageHealthBand.STABLE, profile.topStages[1].healthBand)
    }

    @Test
    fun buildTelemetryProfileDoesNotCallOneWarmupOutlierCritical() {
        val frameProcessing = stats(
            stageName = "processFrame",
            sampleCount = 8,
            p95Ms = 27,
            p99Ms = 27,
            slowCount = 1,
            criticalCount = 1,
            jitterRatio = 0.31,
            stdDevMs = 4.0,
            slowThresholdMs = 28,
            criticalThresholdMs = 56
        )

        val profile = PipelineHealthMonitor.buildTelemetryProfile(
            stageStats = listOf("processFrame" to frameProcessing)
        )

        assertNotNull(profile)
        assertEquals(PipelineStageHealthBand.DEGRADED, profile!!.overallHealth)
        assertEquals(PipelineStageHealthBand.DEGRADED, profile.topStages.single().healthBand)
    }

    @Test
    fun buildTelemetryProfileEscalatesRepeatedCriticalLatencySamples() {
        val frameProcessing = stats(
            stageName = "processFrame",
            sampleCount = 8,
            p95Ms = 27,
            p99Ms = 60,
            slowCount = 2,
            criticalCount = 2,
            jitterRatio = 0.31,
            slowThresholdMs = 28,
            criticalThresholdMs = 56
        )

        val profile = PipelineHealthMonitor.buildTelemetryProfile(
            stageStats = listOf("processFrame" to frameProcessing)
        )

        assertNotNull(profile)
        assertEquals(PipelineStageHealthBand.CRITICAL, profile!!.overallHealth)
        assertEquals(PipelineStageHealthBand.CRITICAL, profile.topStages.single().healthBand)
    }

    @Test
    fun fastMillisecondQuantizedStageRemainsStableWhenJitterIsSmallAgainstBudget() {
        val stage = "test.stage.fast.quantized.${System.nanoTime()}"
        try {
            repeat(20) { index ->
                RuntimeStageTelemetry.recordDuration(
                    tag = "PipelineHealthMonitorTest",
                    stage = stage,
                    durationMs = (index % 5 + 1).toLong(),
                    slowThresholdMs = 28L
                )
            }

            val statistics = requireNotNull(RuntimeStageTelemetry.getStatistics(stage))
            val profile = requireNotNull(
                PipelineHealthMonitor.buildTelemetryProfile(listOf(stage to statistics))
            )

            assertTrue(statistics.jitterRatio >= 0.25)
            assertEquals(5L, statistics.p95Ms)
            assertEquals(5L, statistics.p99Ms)
            assertEquals(1.0, statistics.successRate, 0.0)
            assertEquals(false, statistics.hasMaterialJitter)
            assertEquals(true, statistics.isHealthy)
            assertEquals(PipelineStageHealthBand.STABLE, profile.overallHealth)
        } finally {
            RuntimeStageTelemetry.resetStage(stage)
        }
    }

    @Test
    fun deadlineScaleJitterStillDegradesStageBelowSlowThreshold() {
        val stage = "test.stage.material.jitter.${System.nanoTime()}"
        try {
            repeat(20) { index ->
                RuntimeStageTelemetry.recordDuration(
                    tag = "PipelineHealthMonitorTest",
                    stage = stage,
                    durationMs = if (index % 2 == 0) 4L else 16L,
                    slowThresholdMs = 28L
                )
            }

            val statistics = requireNotNull(RuntimeStageTelemetry.getStatistics(stage))
            val profile = requireNotNull(
                PipelineHealthMonitor.buildTelemetryProfile(listOf(stage to statistics))
            )

            assertEquals(0L, statistics.slowCount)
            assertTrue(statistics.p95Ms < statistics.slowThresholdMs)
            assertTrue(statistics.jitterRatio >= 0.25)
            assertEquals(true, statistics.hasMaterialJitter)
            assertEquals(false, statistics.isHealthy)
            assertEquals(PipelineStageHealthBand.DEGRADED, profile.overallHealth)
        } finally {
            RuntimeStageTelemetry.resetStage(stage)
        }
    }

    @Test
    fun runtimeTelemetryKeepsCountersInsideBoundedHistoryAndDerivesCriticalBudget() {
        val stage = "test.stage.bounded.${System.nanoTime()}"
        try {
            repeat(520) { index ->
                RuntimeStageTelemetry.recordDuration(
                    tag = "PipelineHealthMonitorTest",
                    stage = stage,
                    durationMs = if (index < 8) 3_000L else 824L,
                    slowThresholdMs = 1_300L
                )
            }

            val statistics = requireNotNull(RuntimeStageTelemetry.getStatistics(stage))
            assertEquals(512L, statistics.sampleCount)
            assertEquals(1_300L, statistics.slowThresholdMs)
            assertEquals(2_600L, statistics.criticalThresholdMs)
            assertEquals(0L, statistics.slowCount)
            assertEquals(0L, statistics.criticalCount)
            assertEquals(1.0, statistics.successRate, 0.0)
        } finally {
            RuntimeStageTelemetry.resetStage(stage)
        }
    }

    private fun stats(
        stageName: String,
        sampleCount: Long,
        p95Ms: Long,
        p99Ms: Long,
        successRate: Double = 1.0,
        slowCount: Long = 0,
        criticalCount: Long = 0,
        jitterRatio: Double = 0.05,
        stdDevMs: Double = 2.0,
        slowThresholdMs: Long = 40L,
        criticalThresholdMs: Long = 100L
    ): RuntimeStageTelemetry.StageStatistics {
        val totalDuration = sampleCount * (p95Ms.coerceAtLeast(1))
        return RuntimeStageTelemetry.StageStatistics(
            stageName = stageName,
            sampleCount = sampleCount,
            totalDurationMs = totalDuration,
            minDurationMs = 1L,
            maxDurationMs = p99Ms,
            meanDurationMs = if (sampleCount > 0) totalDuration.toDouble() / sampleCount else 0.0,
            p50Ms = (p95Ms / 2).coerceAtLeast(1L),
            p95Ms = p95Ms,
            p99Ms = p99Ms,
            stdDevMs = stdDevMs,
            successRate = successRate,
            jitterRatio = jitterRatio,
            slowCount = slowCount,
            criticalCount = criticalCount,
            slowThresholdMs = slowThresholdMs,
            criticalThresholdMs = criticalThresholdMs
        )
    }
}
