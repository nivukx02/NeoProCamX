package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCodecCatalogTest {

    @Test
    fun descriptor_exposesExpectedMimeLabelsAndBitrateBias() {
        val hevc = RecordingCodecCatalog.descriptor(GpuVideoCodec.HEVC)
        val avc = RecordingCodecCatalog.descriptor(GpuVideoCodec.AVC)
        val av1 = RecordingCodecCatalog.descriptor(GpuVideoCodec.AV1)

        assertEquals("video/hevc", hevc.mime)
        assertEquals("HEVC", hevc.label)
        assertEquals("video/avc", avc.mime)
        assertEquals("AVC", avc.label)
        assertEquals("video/av01", av1.mime)
        assertEquals("AV1", av1.label)
        assertTrue(avc.bitrateMultiplier > hevc.bitrateMultiplier)
        assertTrue(av1.bitrateMultiplier < hevc.bitrateMultiplier)
    }

    @Test
    fun descriptor_exposesUltraHighEndBitrateCeilingsForAllRecordingCodecs() {
        assertEquals(2_000, RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC))
        assertEquals(1_600, RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.AV1))
        assertEquals(1_000, RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.AVC))
    }

    @Test
    fun professionalRuntimeBitrateCeilingDoesNotClampStrictHevcAndAv1BelowHighEndRequests() {
        assertEquals(
            2_000_000_000,
            RecordingCodecCatalog.maxRuntimeBitrateBps(
                codec = GpuVideoCodec.HEVC,
                qualityTier = RecordingCodecCatalog.QualityTier.CINEMA
            )
        )
        assertEquals(
            1_600_000_000,
            RecordingCodecCatalog.maxRuntimeBitrateBps(
                codec = GpuVideoCodec.AV1,
                qualityTier = RecordingCodecCatalog.QualityTier.PROFESSIONAL
            )
        )
        assertEquals(RecordingCodecCatalog.IFRAME_INTERVAL_CINEMA, 0.10f, 0.0001f)
        assertEquals(RecordingCodecCatalog.IFRAME_INTERVAL_PROFESSIONAL, 0.20f, 0.0001f)
    }

    @Test(expected = RecoverableCodecConfigurationException::class)
    fun strictP010RequestFailsRecoverablyWhenHardwareMain10IsUnavailable() {
        RecordingCodecCatalog.requireHardwareP010Encoding(
            codec = GpuVideoCodec.HEVC,
            p010Requested = true,
            hardwareAccelerated = true,
            main10Supported = false,
            nativeP010InputSupported = true
        )
    }

    @Test
    fun resolveRequestedCodec_fallsBackFromAv1ToHevcWhenUnsupported() {
        assertEquals(
            GpuVideoCodec.HEVC,
            RecordingCodecCatalog.resolveRequestedCodec(VideoCodecType.AV1, av1Supported = false)
        )
        assertEquals(
            GpuVideoCodec.AV1,
            RecordingCodecCatalog.resolveRequestedCodec(VideoCodecType.AV1, av1Supported = true)
        )
    }

    @Test
    fun persistedCodecName_usesSharedCodecResolutionRules() {
        assertEquals(
            GpuVideoCodec.AVC,
            RecordingCodecCatalog.resolvePersistedCodecName("AVC", av1Supported = false)
        )
        assertEquals(
            GpuVideoCodec.HEVC,
            RecordingCodecCatalog.resolvePersistedCodecName("AV1", av1Supported = false)
        )
    }
}
