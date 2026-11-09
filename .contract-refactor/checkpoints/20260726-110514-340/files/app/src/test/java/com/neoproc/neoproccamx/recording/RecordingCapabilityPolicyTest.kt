package com.neoproc.neoproccamx.recording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCapabilityPolicyTest {

    @Test
    fun persistedCodecName_fallsBackToHevcWhenAv1IsUnavailable() {
        val codec = RecordingCapabilityPolicy.resolvePersistedCodecName(
            value = "AV1",
            av1Supported = false
        )

        assertEquals(GpuVideoCodec.HEVC, codec)
    }

    @Test
    fun persistedCodecName_prefersHevcForCompatibilityWhenAv1IsAvailable() {
        val codec = RecordingCapabilityPolicy.resolvePersistedCodecName(
            value = "AV1",
            av1Supported = true
        )

        assertEquals(GpuVideoCodec.HEVC, codec)
    }

    @Test
    fun requestedCodec_usesAv1WhenSupported() {
        val codec = RecordingCapabilityPolicy.resolveRequestedCodec(
            requested = VideoCodecType.AV1,
            av1Supported = true
        )

        assertEquals(GpuVideoCodec.AV1, codec)
    }

    @Test
    fun deriveCinemaMode_onlyEntersRawWhenHardwareActuallySupportsIt() {
        assertEquals(
            CinemaVideoMode.LOG,
            RecordingCapabilityPolicy.deriveCinemaMode(
                rawRequested = true,
                rawSupported = false,
                logEnabled = true
            )
        )
        assertEquals(
            CinemaVideoMode.RAW,
            RecordingCapabilityPolicy.deriveCinemaMode(
                rawRequested = true,
                rawSupported = true,
                logEnabled = true
            )
        )
    }

    @Test
    fun tenBitPipeline_disablesMain10OnAvcAndKeepsItOnModernCodecs() {
        assertTrue(
            RecordingCapabilityPolicy.resolveTenBitPipelineEnabled(
                requested = true,
                codec = GpuVideoCodec.HEVC
            )
        )
        assertFalse(
            RecordingCapabilityPolicy.resolveTenBitPipelineEnabled(
                requested = true,
                codec = GpuVideoCodec.AVC
            )
        )
        assertTrue(
            RecordingCapabilityPolicy.resolveTenBitPipelineEnabled(
                requested = true,
                codec = GpuVideoCodec.AV1
            )
        )
    }

    @Test
    fun sessionPlan_combinesCodecCinemaModeAndTenBitFallbackDeterministically() {
        val plan = RecordingCapabilityPolicy.resolveSessionPlan(
            requestedCodec = VideoCodecType.AV1,
            av1Supported = false,
            hdrRequested = false,
            rawRequested = true,
            rawSupported = false,
            logEnabled = true,
            tenBitRequested = true
        )

        assertEquals(GpuVideoCodec.HEVC, plan.codec)
        assertEquals(CinemaVideoMode.LOG, plan.cinemaMode)
        assertTrue(plan.tenBitPipelineEnabled)
    }

    @Test
    fun resolveRecordingCodec_preservesRequestedCodecWhenDeclaredSupported() {
        assertEquals(
            GpuVideoCodec.AV1,
            RecordingCapabilityPolicy.resolveRecordingCodec(
                requestedCodec = VideoCodecType.AV1,
                av1Supported = true,
                hdrRequested = false,
                rawVideoRequested = false,
                tenBitRequested = false
            )
        )
        assertEquals(
            GpuVideoCodec.HEVC,
            RecordingCapabilityPolicy.resolveRecordingCodec(
                requestedCodec = VideoCodecType.AV1,
                av1Supported = true,
                hdrRequested = true,
                rawVideoRequested = false,
                tenBitRequested = false
            )
        )
        assertEquals(
            GpuVideoCodec.HEVC,
            RecordingCapabilityPolicy.resolveRecordingCodec(
                requestedCodec = VideoCodecType.AVC,
                av1Supported = true,
                hdrRequested = false,
                rawVideoRequested = true,
                tenBitRequested = false
            )
        )
        assertEquals(
            GpuVideoCodec.HEVC,
            RecordingCapabilityPolicy.resolveRecordingCodec(
                requestedCodec = VideoCodecType.AVC,
                av1Supported = true,
                hdrRequested = true,
                rawVideoRequested = false,
                tenBitRequested = false
            )
        )
        assertEquals(
            GpuVideoCodec.HEVC,
            RecordingCapabilityPolicy.resolveRecordingCodec(
                requestedCodec = VideoCodecType.AVC,
                av1Supported = true,
                hdrRequested = false,
                rawVideoRequested = false,
                tenBitRequested = true
            )
        )
        assertEquals(
            GpuVideoCodec.HEVC,
            RecordingCapabilityPolicy.resolveRecordingCodec(
                requestedCodec = VideoCodecType.AV1,
                av1Supported = true,
                hdrRequested = false,
                rawVideoRequested = false,
                tenBitRequested = true
            )
        )
    }

    @Test
    fun normalizeCinemaMode_fallsBackFromRawWhenNativeRawIsUnavailable() {
        assertEquals(
            CinemaVideoMode.PRO,
            RecordingCapabilityPolicy.normalizeCinemaMode(
                requestedMode = CinemaVideoMode.RAW,
                rawSupported = false
            )
        )
        assertEquals(
            CinemaVideoMode.RAW,
            RecordingCapabilityPolicy.normalizeCinemaMode(
                requestedMode = CinemaVideoMode.RAW,
                rawSupported = true
            )
        )
    }

    @Test
    fun sessionPlan_switchesRawModeAndCodecBasedOnHardwareSupport() {
        val unsupportedRawPlan = RecordingCapabilityPolicy.resolveSessionPlan(
            requestedCodec = VideoCodecType.AVC,
            av1Supported = true,
            hdrRequested = false,
            rawRequested = true,
            rawSupported = false,
            logEnabled = false,
            tenBitRequested = false
        )
        val supportedRawPlan = RecordingCapabilityPolicy.resolveSessionPlan(
            requestedCodec = VideoCodecType.AVC,
            av1Supported = true,
            hdrRequested = false,
            rawRequested = true,
            rawSupported = true,
            logEnabled = false,
            tenBitRequested = false
        )

        assertEquals(CinemaVideoMode.PRO, unsupportedRawPlan.cinemaMode)
        assertEquals(CinemaVideoMode.RAW, supportedRawPlan.cinemaMode)
        assertEquals(GpuVideoCodec.HEVC, supportedRawPlan.codec)
    }
}
