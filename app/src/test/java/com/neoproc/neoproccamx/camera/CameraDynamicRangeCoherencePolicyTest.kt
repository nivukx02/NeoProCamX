package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s1-f05 — dynamic-range coherence + silent 8-bit fallback detection. Verifies that a requested
 * 10-bit Neo mode is judged COHERENT only when a real 10-bit profile is actually applied, that a
 * device that genuinely cannot do 10-bit is an HONEST downgrade, and that a capable device running
 * STANDARD is flagged as a SILENT fallback (which must cap the Omega-gate bit depth at 8). Pure-JVM.
 */
class CameraDynamicRangeCoherencePolicyTest {

    private fun support(
        standardOnly: Boolean = false,
        hlg10: Boolean = false,
        hdr10: Boolean = false,
        hdr10Plus: Boolean = false
    ) = HdrDynamicRangePolicy.Support(
        standardOnly = standardOnly,
        hlg10Supported = hlg10,
        hdr10Supported = hdr10,
        hdr10PlusSupported = hdr10Plus
    )

    private fun state(
        requested: HdrDynamicRangeMode,
        preview: Long?,
        encoder: Long?,
        resolved: Long?,
        support: HdrDynamicRangePolicy.Support
    ) = HdrDynamicRangeState(
        requestedMode = requested,
        previewProfile = preview,
        encoderProfile = encoder,
        resolvedProfileForCurrentMode = resolved,
        support = support
    )

    @Test
    fun `sdr request makes no ten bit claim and is coherent`() {
        val report = CameraDynamicRangeCoherencePolicy.evaluate(
            state(
                requested = HdrDynamicRangeMode.SDR,
                preview = null,
                encoder = null,
                resolved = null,
                support = support(standardOnly = true)
            )
        )
        assertEquals(CameraDynamicRangeCoherenceVerdict.SDR_NOT_REQUESTED, report.verdict)
        assertFalse(report.requestedTenBit)
        assertFalse(report.silentFallback)
        assertTrue(report.coherent)
        // SDR must not lift or lower a producer's claimed depth.
        assertEquals(10, report.omegaHonestBitDepth(10))
    }

    @Test
    fun `dolby vision is encoder owned so a standard camera2 transport is not a silent fallback`() {
        // DOLBY_VISION resolves to a null Camera2 profile on every device by design; even on capable
        // hardware a STANDARD Camera2 transport is correct and must not be flagged as a fallback.
        val report = CameraDynamicRangeCoherencePolicy.evaluate(
            state = state(
                requested = HdrDynamicRangeMode.DOLBY_VISION,
                preview = null,
                encoder = null,
                resolved = null,
                support = support(hlg10 = true, hdr10 = true, hdr10Plus = true)
            ),
            sessionConfigured = true,
            recordingEncoderActive = true
        )
        assertEquals(CameraDynamicRangeCoherenceVerdict.SDR_NOT_REQUESTED, report.verdict)
        assertFalse(report.silentFallback)
        assertTrue(report.coherent)
    }

    @Test
    fun `coherent ten bit when preview and encoder both run the requested profile`() {
        val report = CameraDynamicRangeCoherencePolicy.evaluate(
            state = state(
                requested = HdrDynamicRangeMode.HDR10_PLUS,
                preview = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
                encoder = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
                resolved = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
                support = support(hdr10Plus = true)
            ),
            sessionConfigured = true,
            recordingEncoderActive = true
        )
        assertEquals(CameraDynamicRangeCoherenceVerdict.COHERENT_TEN_BIT, report.verdict)
        assertEquals(10, report.negotiatedTransportBitDepth)
        assertFalse(report.silentFallback)
        assertTrue(report.coherent)
        assertEquals(10, report.omegaHonestBitDepth(10))
    }

    @Test
    fun `honest hardware downgrade when the device cannot transport ten bit`() {
        val report = CameraDynamicRangeCoherencePolicy.evaluate(
            state(
                requested = HdrDynamicRangeMode.HDR10_PLUS,
                preview = null,
                encoder = null,
                resolved = null,
                support = support(standardOnly = true)
            )
        )
        assertEquals(CameraDynamicRangeCoherenceVerdict.HONEST_HARDWARE_DOWNGRADE, report.verdict)
        assertEquals(8, report.negotiatedTransportBitDepth)
        assertFalse(report.silentFallback)
        assertTrue(report.coherent)
    }

    @Test
    fun `a mode the device only partially supports resolving to standard is honest not silent`() {
        // HLG requested on an HDR10-only device: the policy resolves to STANDARD (no HLG10 profile),
        // so an 8-bit run is honest even though the device is otherwise 10-bit capable.
        val report = CameraDynamicRangeCoherencePolicy.evaluate(
            state = state(
                requested = HdrDynamicRangeMode.HLG,
                preview = null,
                encoder = null,
                resolved = null, // resolveProfilePure(HLG, hdr10-only) == null
                support = support(hdr10 = true)
            ),
            sessionConfigured = true,
            recordingEncoderActive = true
        )
        assertEquals(CameraDynamicRangeCoherenceVerdict.HONEST_HARDWARE_DOWNGRADE, report.verdict)
        assertFalse(report.silentFallback)
        assertTrue(report.coherent)
    }

    @Test
    fun `silent eight bit fallback when capable hardware runs standard preview`() {
        val report = CameraDynamicRangeCoherencePolicy.evaluate(
            state = state(
                requested = HdrDynamicRangeMode.HDR10_PLUS,
                preview = null,                 // STANDARD applied despite a 10-bit request
                encoder = null,
                resolved = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
                support = support(hdr10Plus = true)
            ),
            sessionConfigured = true,
            recordingEncoderActive = false
        )
        assertEquals(CameraDynamicRangeCoherenceVerdict.SILENT_EIGHT_BIT_FALLBACK, report.verdict)
        assertEquals(8, report.negotiatedTransportBitDepth)
        assertTrue(report.silentFallback)
        assertFalse(report.coherent)
        // A faked 10-bit claim is impossible: honest depth is capped at the real 8-bit transport.
        assertEquals(8, report.omegaHonestBitDepth(10))
    }

    @Test
    fun `encoder silently eight bit during recording is a silent fallback`() {
        val report = CameraDynamicRangeCoherencePolicy.evaluate(
            state = state(
                requested = HdrDynamicRangeMode.HLG,
                preview = HdrDynamicRangePolicy.PROFILE_HLG10,
                encoder = null,                 // encoder master silently STANDARD
                resolved = HdrDynamicRangePolicy.PROFILE_HLG10,
                support = support(hlg10 = true)
            ),
            sessionConfigured = true,
            recordingEncoderActive = true
        )
        assertEquals(CameraDynamicRangeCoherenceVerdict.SILENT_EIGHT_BIT_FALLBACK, report.verdict)
        assertTrue(report.silentFallback)
    }

    @Test
    fun `preview encoder split on different ten bit profiles breaks wysiwyg without an eight bit fault`() {
        val report = CameraDynamicRangeCoherencePolicy.evaluate(
            state = state(
                requested = HdrDynamicRangeMode.HDR10_PLUS,
                preview = HdrDynamicRangePolicy.PROFILE_HLG10,
                encoder = HdrDynamicRangePolicy.PROFILE_HDR10,
                resolved = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
                support = support(hlg10 = true, hdr10 = true, hdr10Plus = true)
            ),
            sessionConfigured = true,
            recordingEncoderActive = true
        )
        assertEquals(CameraDynamicRangeCoherenceVerdict.PREVIEW_ENCODER_SPLIT, report.verdict)
        assertEquals(10, report.negotiatedTransportBitDepth)
        assertFalse(report.silentFallback)
        assertFalse(report.coherent)
    }

    @Test
    fun `no applied profiles before a session is configured is unverified not a fallback`() {
        val report = CameraDynamicRangeCoherencePolicy.evaluate(
            state = state(
                requested = HdrDynamicRangeMode.HDR10_PLUS,
                preview = null,
                encoder = null,
                resolved = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
                support = support(hdr10Plus = true)
            ),
            sessionConfigured = false
        )
        assertEquals(CameraDynamicRangeCoherenceVerdict.UNVERIFIED_NO_SESSION, report.verdict)
        assertFalse(report.silentFallback)
        assertTrue(report.coherent)
    }

    @Test
    fun `ten bit profile classification treats standard as eight bit`() {
        assertFalse(CameraDynamicRangeCoherencePolicy.isTenBitProfile(null))
        assertFalse(CameraDynamicRangeCoherencePolicy.isTenBitProfile(HdrDynamicRangePolicy.PROFILE_STANDARD))
        assertTrue(CameraDynamicRangeCoherencePolicy.isTenBitProfile(HdrDynamicRangePolicy.PROFILE_HLG10))
        assertTrue(CameraDynamicRangeCoherencePolicy.isTenBitProfile(HdrDynamicRangePolicy.PROFILE_HDR10))
        assertTrue(CameraDynamicRangeCoherencePolicy.isTenBitProfile(HdrDynamicRangePolicy.PROFILE_HDR10_PLUS))
    }

    @Test
    fun `silent fallback convenience matches the full report verdict`() {
        val silentState = state(
            requested = HdrDynamicRangeMode.HDR10_PLUS,
            preview = null,
            encoder = null,
            resolved = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
            support = support(hdr10Plus = true)
        )
        assertTrue(CameraDynamicRangeCoherencePolicy.silentFallbackDetected(silentState))

        val coherentState = state(
            requested = HdrDynamicRangeMode.HDR10_PLUS,
            preview = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
            encoder = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
            resolved = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
            support = support(hdr10Plus = true)
        )
        assertFalse(CameraDynamicRangeCoherencePolicy.silentFallbackDetected(coherentState, recordingEncoderActive = true))
    }

    @Test
    fun `compact metadata exposes the verdict and negotiated depth`() {
        val report = CameraDynamicRangeCoherencePolicy.evaluate(
            state = state(
                requested = HdrDynamicRangeMode.HDR10_PLUS,
                preview = null,
                encoder = null,
                resolved = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
                support = support(hdr10Plus = true)
            )
        )
        val metadata = report.toCompactMetadataMap()
        assertEquals("SILENT_EIGHT_BIT_FALLBACK", metadata["dynamicRangeCoherenceVerdict"])
        assertEquals("8", metadata["dynamicRangeNegotiatedBitDepth"])
        assertEquals("true", metadata["dynamicRangeSilentFallback"])
    }
}
