package com.neoproc.neoproccamx.rendering

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrPqPreviewMonitorTonePolicyTest {
    @Test
    fun diffuseReferenceUsesMeasuredSdrPresentationAnchor() {
        val linear = NdrPqPreviewMonitorTonePolicy.MONITOR_DIFFUSE_LINEAR
        val srgb = if (linear <= 0.0031308f) {
            12.92f * linear
        } else {
            1.055f * linear.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
        }
        assertEquals(0.60382734f, linear, 1e-8f)
        assertEquals(0.80f, srgb, 1e-6f)
    }

    @Test
    fun shadowsAndMidtonesUseCalibratedDiffuseWhiteScale() {
        val diffuse = NdrPqPreviewMonitorTonePolicy.MONITOR_DIFFUSE_LINEAR
        assertEquals(0.50f * diffuse, NdrPqPreviewMonitorTonePolicy.mapLuminance(0.50f, 5000f), 1e-6f)
        assertEquals(0.62f * diffuse, NdrPqPreviewMonitorTonePolicy.mapLuminance(0.62f, 5000f), 1e-6f)
    }

    @Test
    fun pqDiffuseWhiteDoesNotClipOnSdrMonitor() {
        val mapped = NdrPqPreviewMonitorTonePolicy.mapLuminance(1f, 5000f)
        assertEquals(NdrPqPreviewMonitorTonePolicy.MONITOR_DIFFUSE_LINEAR, mapped, 1e-6f)
    }

    @Test
    fun monitorAdapterHighlightRangeRollsMonotonicallyTowardWhite() {
        val diffuse = NdrPqPreviewMonitorTonePolicy.mapLuminance(1f, 5000f)
        val highlight = NdrPqPreviewMonitorTonePolicy.mapLuminance(2f, 5000f)
        val peak = NdrPqPreviewMonitorTonePolicy.mapLuminance(5000f / 203f, 5000f)
        assertTrue(diffuse < highlight)
        assertTrue(highlight < peak)
        // A 5,000-nit master highlight remains bright without consuming the final
        // display codes, so brighter scene values still have a monotone shoulder instead of clipping.
        assertTrue("5,000-nit endpoint must retain calibrated display headroom: $peak", peak < 1f)
        assertTrue("5,000-nit endpoint must remain visibly bright: $peak", peak in 0.985f..0.995f)
    }

    @Test
    fun unboundedToneSignalApproachesButNeverHardClipsDisplayWhite() {
        val bright = NdrPqPreviewMonitorTonePolicy.mapToneSignal(8f, 5000f)
        val extreme = NdrPqPreviewMonitorTonePolicy.mapToneSignal(48f, 5000f)
        assertTrue("bright highlight must retain display headroom: $bright", bright < 1f)
        assertTrue("extreme PQ input must remain bounded below hard white: $extreme", extreme < 1f)
        assertTrue(bright < extreme)
    }

    @Test
    fun sdrMonitorAdaptationDoesNotDependOnEncoderPeakMetadata() {
        val ndr = NdrPqPreviewMonitorTonePolicy.mapToneSignal(2f, 5000f)
        val nivukx = NdrPqPreviewMonitorTonePolicy.mapToneSignal(2f, 10000f)
        assertEquals(ndr, nivukx, 0f)
    }

    @Test
    fun wideGamutOverflowIsCompressedWithoutHardChannelClipping() {
        val mapped = NdrPqPreviewMonitorTonePolicy.compressUnitGamut(
            r = 1.32f,
            g = 0.34f,
            b = 0.18f
        )

        assertTrue(mapped.all { it in 0f..1f })
        assertTrue("red channel must retain headroom instead of clipping", mapped[0] < 0.99f)
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
