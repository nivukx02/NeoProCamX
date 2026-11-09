package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10AudioVideoDurationPolicyTest {
    @Test
    fun `tightly synchronized pause resume duration passes`() {
        val audit = Npv10AudioVideoDurationPolicy.audit(
            videoFrameCount = 65L,
            videoFps = 25,
            audioDurationUs = 2_592_000L
        )
        assertEquals(2_600_000L, audit.videoDurationUs)
        assertEquals(8_000L, audit.absoluteDeltaUs)
        assertTrue(audit.passed)
    }

    @Test
    fun `legacy paused wall time fails closed`() {
        val audit = Npv10AudioVideoDurationPolicy.audit(
            videoFrameCount = 61L,
            videoFps = 25,
            audioDurationUs = 4_428_209L
        )
        assertEquals(1_988_209L, audit.absoluteDeltaUs)
        assertFalse(audit.passed)
    }

    @Test
    fun `threshold is inclusive and one microsecond beyond fails`() {
        val videoDurationUs = 2_000_000L
        val atLimit = Npv10AudioVideoDurationPolicy.audit(
            videoFrameCount = 50L,
            videoFps = 25,
            audioDurationUs = videoDurationUs + Npv10AudioVideoDurationPolicy.MAXIMUM_ALLOWED_DELTA_US
        )
        val beyondLimit = Npv10AudioVideoDurationPolicy.audit(
            videoFrameCount = 50L,
            videoFps = 25,
            audioDurationUs = videoDurationUs + Npv10AudioVideoDurationPolicy.MAXIMUM_ALLOWED_DELTA_US + 1L
        )
        assertTrue(atLimit.passed)
        assertFalse(beyondLimit.passed)
    }
}
