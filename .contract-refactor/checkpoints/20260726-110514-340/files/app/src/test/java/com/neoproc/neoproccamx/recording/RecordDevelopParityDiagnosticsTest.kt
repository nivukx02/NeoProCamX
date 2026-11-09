package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Self-validation of the GPU-vs-CPU parity diff instrument (the Step-1 harness). Proves the diff /
 * percentile / bucket / worst-pixel extraction are correct on KNOWN inputs before the numbers are
 * trusted on real GPU output — and demonstrates that the p99.9 guard catches a STRUCTURED regional
 * error that a global-mean / lone-max gate would pass silently.
 */
class RecordDevelopParityDiagnosticsTest {

    @Test
    fun identicalStreamsReportZeroResidual() {
        // CPU-vs-CPU: every code equal → the harness must report an exact zero everywhere.
        val a = ShortArray(30_000) { (it % 1024).toShort() }
        val stats = RecordDevelopParityDiagnostics.compareCodes(a, a.copyOf(), "SDR_COLOR", width = 100)!!
        assertEquals(0.0, stats.meanAbs, 0.0)
        assertEquals(0, stats.maxAbs)
        assertEquals(0, stats.p50)
        assertEquals(0, stats.p999)
        assertEquals(0, stats.p9999)
        assertEquals(0, stats.over1)
        assertTrue(stats.worst.isEmpty())
    }

    @Test
    fun sizeMismatchOrEmptyReturnsNull() {
        assertNull(RecordDevelopParityDiagnostics.compareCodes(ShortArray(10), ShortArray(9), "SDR_COLOR", 0))
        assertNull(RecordDevelopParityDiagnostics.compareCodes(ShortArray(0), ShortArray(0), "SDR_COLOR", 0))
    }

    @Test
    fun structuredRegionalErrorIsCaughtByP999ButNotByMeanOrMax() {
        // 40 of 30_000 codes (0.133% > 0.1%) off by 40; the rest identical. meanAbs≈0.053 (<4) and
        // maxAbs=40 (<64) would BOTH pass the old gate, but p99.9 must surface it at 40.
        val gpu = ShortArray(30_000)
        val cpu = ShortArray(30_000)
        for (i in gpu.indices) {
            val v = if (i < 40) 40 else 0
            gpu[i] = v.toShort()
        }
        val stats = RecordDevelopParityDiagnostics.compareCodes(gpu, cpu, "SDR_COLOR", width = 100)!!
        assertTrue("mean should look benign", stats.meanAbs < 1.0)
        assertEquals(40, stats.maxAbs)
        assertEquals(40, stats.p999)
        assertTrue(stats.p999 > RecordDevelopParityGatePolicy.DEFAULT_P999_TOLERANCE)
        assertEquals(40, stats.over16)
    }

    @Test
    fun loneFpOutlierDoesNotInflateP999() {
        // A single isolated code off by 35 (float-vs-double style outlier) must NOT trip p99.9 — it is far
        // below the 0.1% tail — while maxAbs still records it. This is why maxTol stays and p99.9 is added.
        val gpu = ShortArray(30_000)
        val cpu = ShortArray(30_000)
        gpu[12_345] = 35
        val stats = RecordDevelopParityDiagnostics.compareCodes(gpu, cpu, "SDR_COLOR", width = 100)!!
        assertEquals(35, stats.maxAbs)
        assertEquals(0, stats.p999)
        assertEquals(1, stats.over16)
        assertEquals(1, stats.worst.size)
        val w = stats.worst[0]
        assertEquals(35, w.delta)
        // index 12_345 → pixel 4115, channel 0 → (x=15, y=41) at width 100.
        assertEquals(4115 % 100, w.x)
        assertEquals(4115 / 100, w.y)
        assertEquals(0, w.channel)
    }

    @Test
    fun packedBytesIdenticalReportsZero() {
        val a = ByteArray(4096) { (it and 0xFF).toByte() }
        val stats = RecordDevelopParityDiagnostics.comparePackedBytes(a, a.copyOf(), "PACK_R210_444")!!
        assertEquals(0, stats.differingBytes)
        assertEquals(0, stats.maxByteDelta)
        assertEquals(0.0, stats.meanByteDelta, 0.0)
    }

    @Test
    fun packedBytesReportsDivergence() {
        val gpu = ByteArray(1000)
        val cpu = ByteArray(1000)
        gpu[100] = 7; gpu[200] = 40
        val stats = RecordDevelopParityDiagnostics.comparePackedBytes(gpu, cpu, "PACK_V210_422")!!
        assertEquals(2, stats.differingBytes)
        assertEquals(40, stats.maxByteDelta)
        assertNull(RecordDevelopParityDiagnostics.comparePackedBytes(ByteArray(4), ByteArray(5), "PACK"))
    }

    @Test
    fun worstListIsDescendingByDelta() {
        val gpu = ShortArray(3_000)
        val cpu = ShortArray(3_000)
        gpu[10] = 5; gpu[20] = 60; gpu[30] = 12; gpu[40] = 33
        val stats = RecordDevelopParityDiagnostics.compareCodes(gpu, cpu, "NDR_PQ", width = 50)!!
        val deltas = stats.worst.map { it.delta }
        assertEquals(listOf(60, 33, 12, 5), deltas)
        assertEquals(60, stats.maxAbs)
    }
}
