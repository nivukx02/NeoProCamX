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

    private fun stats(
        stageName: String,
        sampleCount: Long,
        p95Ms: Long,
        p99Ms: Long,
        successRate: Double = 1.0,
        slowCount: Long = 0,
        criticalCount: Long = 0,
        jitterRatio: Double = 0.05
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
            stdDevMs = 2.0,
            successRate = successRate,
            jitterRatio = jitterRatio,
            slowCount = slowCount,
            criticalCount = criticalCount
        )
    }
}
