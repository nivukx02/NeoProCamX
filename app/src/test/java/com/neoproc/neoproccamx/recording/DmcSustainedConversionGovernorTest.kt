package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DmcSustainedConversionGovernorTest {

    @Test
    fun `platform status and predictive headroom select the safer band`() {
        assertEquals(DmcThermalBand.NOMINAL, DmcThermalBand.fromSignals(0, 0.2f))
        assertEquals(DmcThermalBand.WARM, DmcThermalBand.fromSignals(0, 0.72f))
        assertEquals(DmcThermalBand.ELEVATED, DmcThermalBand.fromSignals(1, 0.92f))
        assertEquals(DmcThermalBand.HOT, DmcThermalBand.fromSignals(3, 0.1f))
        assertEquals(DmcThermalBand.CRITICAL, DmcThermalBand.fromSignals(4, Float.NaN))
        assertEquals(DmcThermalBand.EMERGENCY, DmcThermalBand.fromSignals(6, null))
    }

    @Test
    fun `escalation is immediate and recovery is stable and one band at a time`() {
        val governor = governor(recoveryNanos = 10L)
        assertEquals(
            DmcThermalBand.HOT,
            governor.observeThermal(platformStatus = 3, thermalHeadroom = null, nowNanos = 1L).thermalBand
        )
        assertEquals(
            DmcThermalBand.HOT,
            governor.observeThermal(platformStatus = 0, thermalHeadroom = null, nowNanos = 2L).thermalBand
        )
        assertEquals(
            DmcThermalBand.ELEVATED,
            governor.observeThermal(platformStatus = 0, thermalHeadroom = null, nowNanos = 12L).thermalBand
        )
        assertEquals(
            DmcThermalBand.WARM,
            governor.observeThermal(platformStatus = 0, thermalHeadroom = null, nowNanos = 22L).thermalBand
        )
        assertEquals(
            DmcThermalBand.NOMINAL,
            governor.observeThermal(platformStatus = 0, thermalHeadroom = null, nowNanos = 32L).thermalBand
        )
    }

    @Test
    fun `thermal decisions change scheduling but expose no quality mutation`() {
        val governor = governor()
        val nominal = governor.tuning()
        assertEquals(7, nominal.workerLimit)
        assertEquals(4, nominal.inFlightLimit)
        assertTrue(nominal.allowConcurrentCpuGpuDevelop)

        val hot = governor.observeThermal(3, 1.05f, 1L)
        assertEquals(2, hot.workerLimit)
        assertEquals(1, hot.inFlightLimit)
        assertFalse(hot.allowConcurrentCpuGpuDevelop)
        assertFalse(hot.pauseAtSafeBoundary)

        val critical = governor.observeThermal(4, 1.05f, 2L)
        assertEquals(1, critical.workerLimit)
        assertEquals(1, critical.inFlightLimit)
        assertTrue(critical.pauseAtSafeBoundary)

        val evidence = governor.evidence(3L)
        assertTrue(evidence.qualityInvariant)
        assertEquals(DmcThermalBand.CRITICAL, evidence.maximumThermalBand)
    }

    @Test
    fun `emergency status pauses at a frame boundary without changing quality`() {
        val governor = governor()

        val emergency = governor.observeThermal(
            platformStatus = 5,
            thermalHeadroom = null,
            nowNanos = 1L
        )

        assertEquals(DmcThermalBand.EMERGENCY, emergency.thermalBand)
        assertEquals(1, emergency.workerLimit)
        assertEquals(1, emergency.inFlightLimit)
        assertFalse(emergency.allowConcurrentCpuGpuDevelop)
        assertTrue(emergency.pauseAtSafeBoundary)
        assertTrue(governor.evidence(2L).qualityInvariant)
    }

    @Test
    fun `pacing consumes only available cadence slack`() {
        val governor = governor()
        governor.observeThermal(platformStatus = 3, thermalHeadroom = null, nowNanos = 1L)

        val fastDelay = governor.onFramesCommitted(exactFramesDone = 1L, nowNanos = 10_000_000L)
        assertTrue(fastDelay > 0L)
        assertTrue(fastDelay <= 8_000_000L)
        assertTrue(10_000_000L + fastDelay <= 40_000_000L)

        val slowGovernor = governor()
        slowGovernor.observeThermal(platformStatus = 3, thermalHeadroom = null, nowNanos = 1L)
        assertEquals(
            0L,
            slowGovernor.onFramesCommitted(exactFramesDone = 1L, nowNanos = 50_000_000L)
        )
    }

    @Test
    fun `timing evidence uses a bounded sample window and exact completed count`() {
        val governor = DmcSustainedConversionGovernor(
            logicalCores = 8,
            startedAtNanos = 0L,
            timingCapacity = 32
        )
        governor.configure(workload())
        var now = 0L
        for (frame in 1L..100L) {
            now += frame * 1_000L
            governor.onFramesCommitted(frame, now)
        }
        val evidence = governor.evidence(now)
        assertEquals(100L, evidence.completedFrames)
        assertTrue(evidence.p50FrameNanos > 0L)
        assertTrue(evidence.p95FrameNanos >= evidence.p50FrameNanos)
        assertTrue(evidence.p99FrameNanos >= evidence.p95FrameNanos)
        assertTrue(evidence.maximumFrameNanos >= evidence.p99FrameNanos)
    }

    @Test
    fun `registry clamps workers overlap and hybrid without leaking after close`() {
        val tuning = DmcExecutionTuning(
            generation = 7L,
            thermalBand = DmcThermalBand.HOT,
            workerLimit = 2,
            inFlightLimit = 1,
            allowConcurrentCpuGpuDevelop = false,
            pauseAtSafeBoundary = false,
            targetFrameIntervalNanos = 40_000_000L
        )
        DmcConversionTuningRegistry.acquire(tuning).use {
            assertEquals(
                2,
                ConversionParallelismPolicy.workerCount(frameBytes = 0L, cores = 12)
            )
            assertFalse(
                RawDevelopPipelineOverlapPolicy.shouldOverlap(
                    width = 1920,
                    height = 1080,
                    maxHeapBytes = 2L * 1024L * 1024L * 1024L
                )
            )
            assertEquals(
                1,
                RecordDevelopPipelineOverlapPolicy.maxResidentFrames(
                    residentBytesPerFrame = 1_000L,
                    conservativeMaxFrames = 1,
                    maxMemoryBytes = 1_000_000L,
                    memoryBudgetBytes = 1_000_000L
                )
            )
            assertFalse(RecordDevelopHybridCpuLanePolicy.routeToCpuLane(index = 9L, shareBps = 10_000))
        }
        assertEquals(
            11,
            ConversionParallelismPolicy.workerCount(frameBytes = 0L, cores = 12)
        )
    }

    private fun governor(recoveryNanos: Long = 15_000_000_000L): DmcSustainedConversionGovernor =
        DmcSustainedConversionGovernor(
            logicalCores = 8,
            startedAtNanos = 0L,
            recoveryStableNanos = recoveryNanos
        ).also { it.configure(workload()) }

    private fun workload() = DmcConversionWorkload(
        route = "NPV10_SDR",
        width = 3840,
        height = 2160,
        sourceFps = 25,
        compressedOutput = true
    )
}
