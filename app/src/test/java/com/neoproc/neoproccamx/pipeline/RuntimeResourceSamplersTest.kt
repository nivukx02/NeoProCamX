package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeResourceSamplersTest {
    @Test
    fun cpuSamplerUsesRealDeltasAndNormalizesAcrossCores() {
        val sampler = ProcessCpuUtilizationSampler(availableProcessors = 4)
        assertNull(sampler.sample(elapsedRealtimeMs = 1_000L, processCpuTimeMs = 500L))

        assertEquals(0.5f, sampler.sample(2_000L, 2_500L) ?: -1f, 0.0001f)
        assertEquals(1f, sampler.sample(3_000L, 8_500L) ?: -1f, 0.0001f)
    }

    @Test
    fun storageMeterReportsCommittedBytesAndDecaysDuringAStall() {
        val meter = RollingStorageWriteThroughputMeter(windowDurationNs = 2_000_000_000L)
        val mebibyte = 1024L * 1024L
        meter.recordCommittedBytes(mebibyte, 1_000_000_000L)
        meter.recordCommittedBytes(mebibyte, 2_000_000_000L)

        val flowing = meter.megabytesPerSecond(2_000_000_000L) ?: 0f
        val stalled = meter.megabytesPerSecond(2_900_000_000L) ?: 0f
        assertTrue("flowing=$flowing", flowing > 1.9f)
        assertTrue("flowing=$flowing stalled=$stalled", stalled < flowing)

        assertEquals(0f, meter.megabytesPerSecond(4_100_000_000L) ?: -1f, 0f)
    }
}
