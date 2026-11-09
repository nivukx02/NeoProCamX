package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrPqPreviewMonitorTonePolicyTest {
    @Test
    fun shadowsAndMidtonesRemainIdentityBelowKnee() {
        assertEquals(0.50f, NdrPqPreviewMonitorTonePolicy.mapLuminance(0.50f, 1420f), 1e-6f)
        assertEquals(0.75f, NdrPqPreviewMonitorTonePolicy.mapLuminance(0.75f, 1420f), 1e-6f)
    }

    @Test
    fun pqDiffuseWhiteDoesNotClipOnSdrMonitor() {
        val mapped = NdrPqPreviewMonitorTonePolicy.mapLuminance(1f, 1420f)
        assertTrue("diffuse white must remain below display clipping: $mapped", mapped in 0.77f..0.82f)
    }

    @Test
    fun fullNdrHighlightRangeRollsMonotonicallyTowardWhite() {
        val diffuse = NdrPqPreviewMonitorTonePolicy.mapLuminance(1f, 1420f)
        val highlight = NdrPqPreviewMonitorTonePolicy.mapLuminance(2f, 1420f)
        val peak = NdrPqPreviewMonitorTonePolicy.mapLuminance(1420f / 203f, 1420f)
        assertTrue(diffuse < highlight)
        assertTrue(highlight < peak)
        assertTrue(peak in 0.98f..1f)
    }

    @Test
    fun wideGamutOverflowIsCompressedWithoutHardChannelClipping() {
        val mapped = NdrPqPreviewMonitorTonePolicy.compressUnitGamut(
            r = 1.32f,
            g = 0.34f,
            b = 0.18f
        )

        assertTrue(mapped.all { it in 0f..1f })
        assertEquals(1f, mapped[0], 1e-6f)
        assertTrue("green detail must survive instead of a red hard clip", mapped[1] > 0.34f)
        assertTrue("blue detail must survive instead of a red hard clip", mapped[2] > 0.18f)
    }

    @Test
    fun inGamutNeutralIsByteEquivalent() {
        val mapped = NdrPqPreviewMonitorTonePolicy.compressUnitGamut(0.42f, 0.42f, 0.42f)
        assertEquals(0.42f, mapped[0], 1e-6f)
        assertEquals(0.42f, mapped[1], 1e-6f)
        assertEquals(0.42f, mapped[2], 1e-6f)
    }
}
