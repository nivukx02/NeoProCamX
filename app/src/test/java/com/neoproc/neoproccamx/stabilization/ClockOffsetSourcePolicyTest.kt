package com.neoproc.neoproccamx.stabilization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ELITE Rank-5 — goldens for the timestamp-source → clock-offset strategy policy.
 */
class ClockOffsetSourcePolicyTest {

    @Test
    fun `REALTIME source trusts a zero offset`() {
        val d = ClockOffsetSourcePolicy.resolve(ClockOffsetSourcePolicy.TIMESTAMP_SOURCE_REALTIME)
        assertEquals(0L, d.offsetNs)
        assertTrue("REALTIME offset is trusted", d.trusted)
        assertFalse("REALTIME needs no estimation", d.needsEstimation)
    }

    @Test
    fun `UNKNOWN source requires estimation and is not trusted`() {
        val d = ClockOffsetSourcePolicy.resolve(ClockOffsetSourcePolicy.TIMESTAMP_SOURCE_UNKNOWN)
        assertFalse("UNKNOWN offset must not be trusted", d.trusted)
        assertTrue("UNKNOWN requires estimation", d.needsEstimation)
    }

    @Test
    fun `null source defaults to UNKNOWN (safe)`() {
        val d = ClockOffsetSourcePolicy.resolve(null)
        assertFalse(d.trusted)
        assertTrue(d.needsEstimation)
        assertEquals(
            ClockOffsetSourcePolicy.TimestampSourceKind.UNKNOWN,
            ClockOffsetSourcePolicy.classify(null)
        )
    }

    @Test
    fun `classify maps the raw android constants`() {
        assertEquals(
            ClockOffsetSourcePolicy.TimestampSourceKind.REALTIME,
            ClockOffsetSourcePolicy.classify(ClockOffsetSourcePolicy.TIMESTAMP_SOURCE_REALTIME)
        )
        assertEquals(
            ClockOffsetSourcePolicy.TimestampSourceKind.UNKNOWN,
            ClockOffsetSourcePolicy.classify(99)
        )
    }
}
