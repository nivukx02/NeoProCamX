package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression lock for [AllIntraHevcPolicy.computeLevel]: the luma rate (width*height*fps) was
 * computed in Int, so 8K@60 (≈2.52e9) overflowed and wrapped negative, collapsing to the LOWEST
 * HEVC level and defeating the level capability gate. The fix uses Long arithmetic.
 */
class AllIntraHevcPolicyLevelTest {

    @Test
    fun `8K at 60fps does not overflow to the lowest level`() {
        val eightKAt60 = AllIntraHevcPolicy.computeLevel(8640, 4860, 60)
        val vgaAt30 = AllIntraHevcPolicy.computeLevel(640, 480, 30)
        // Before the fix both returned HEVCHighTierLevel5 (overflow). They must now differ.
        assertNotEquals(vgaAt30, eightKAt60)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61, eightKAt60)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5, vgaAt30)
    }

    @Test
    fun `level tiers are assigned by luma rate`() {
        // 4K@60 rate > 4K@30 threshold but not > 4K@60 threshold -> 5.1
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51,
            AllIntraHevcPolicy.computeLevel(3840, 2160, 45)
        )
        // 4K@60+ -> 6.1
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61,
            AllIntraHevcPolicy.computeLevel(3840, 2160, 90)
        )
    }
}
