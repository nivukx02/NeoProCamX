package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCodecSupportProbeTest {

    @Test
    fun supports_reportsAv1AvailabilitySeparatelyFromHevcAndAvc() {
        assertTrue(RecordingCodecSupportProbe.supports(GpuVideoCodec.HEVC, av1Supported = false))
        assertTrue(RecordingCodecSupportProbe.supports(GpuVideoCodec.AVC, av1Supported = false))
        assertFalse(RecordingCodecSupportProbe.supports(GpuVideoCodec.AV1, av1Supported = false))
        assertTrue(RecordingCodecSupportProbe.supports(GpuVideoCodec.AV1, av1Supported = true))
    }

    @Test
    fun supportsTenBit_matchesCodecSpecificMain10Rules() {
        val support = RecordingMain10Support(
            hevcMain10 = true,
            av1Main10 = false
        )

        assertTrue(RecordingCodecSupportProbe.supportsTenBit(GpuVideoCodec.HEVC, support))
        assertFalse(RecordingCodecSupportProbe.supportsTenBit(GpuVideoCodec.AV1, support))
        assertFalse(RecordingCodecSupportProbe.supportsTenBit(GpuVideoCodec.AVC, support))
    }

    @Test
    fun supportsTwelveBit_onlyActivatesForReflectedHevcMain12Profile() {
        val unsupported = RecordingCodecSupportSnapshot(
            av1Supported = true,
            main10 = RecordingMain10Support(hevcMain10 = true, av1Main10 = true),
            av1Main10Profile = 1,
            hevcMain12Profile = null
        )
        val supported = unsupported.copy(hevcMain12Profile = 123_456)

        assertFalse(unsupported.supportsTwelveBit(GpuVideoCodec.HEVC))
        assertTrue(supported.supportsTwelveBit(GpuVideoCodec.HEVC))
        assertFalse(supported.supportsTwelveBit(GpuVideoCodec.AV1))
        assertFalse(supported.supportsTwelveBit(GpuVideoCodec.AVC))
    }
}
