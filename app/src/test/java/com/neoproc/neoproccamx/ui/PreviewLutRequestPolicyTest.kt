package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewLutRequestPolicyTest {

    @Test
    fun hdrRequestDiffersFromProRequestEvenWithSameColorProfile() {
        val pro = buildRequest(
            deliveryState = VideoFormatDeliveryState(),
            previewLookState = VideoFormatPreviewLookState()
        )
        val hdr = buildRequest(
            deliveryState = VideoFormatDeliveryState(
                effectiveHdrMode = "HDR10",
                effectiveColorSpace = "Rec.2020-PQ",
                truthfulColorDelivery = true
            ),
            previewLookState = VideoFormatPreviewLookState(hdrEnabled = true)
        )

        assertNotEquals(pro, hdr)
        assertEquals("HDR10", hdr.effectiveHdrMode)
        assertTrue(hdr.hdrEnabled)
    }

    @Test
    fun hdrRequestDisablesPreviewLutUntilColorPipelineIsStable() {
        val hdr = PreviewLutRequestPolicy.build(
            uiMode = AppMode.VIDEO,
            deliveryState = VideoFormatDeliveryState(
                effectiveHdrMode = "HDR10",
                effectiveColorSpace = "Rec.2020-PQ",
                truthfulColorDelivery = true
            ),
            previewLookState = VideoFormatPreviewLookState(
                hdrEnabled = true,
                hdrMode = "HDR10",
                displayReferredMonitoring = true
            ),
            recordingActive = true,
            previewFpsHint = 60f,
            colorProfile = "Natural",
            lutEnabled = true,
            lutProfile = "Warm Cinema",
            customLutPath = "/tmp/warm.cube",
            saturation = 1.4f,
            contrast = 1.2f,
            brightness = 0f,
            hueShift = 0f,
            highlights = 0.1f,
            shadows = 0.1f
        )

        assertTrue(hdr.hdrEnabled)
        assertFalse(hdr.lutEnabled)
        assertEquals(null, hdr.customLutPath)
    }

    @Test
    fun rawRequestDiffersFromProRequestEvenWhenLogToggleIsOff() {
        val pro = buildRequest(
            deliveryState = VideoFormatDeliveryState(),
            previewLookState = VideoFormatPreviewLookState()
        )
        val raw = buildRequest(
            deliveryState = VideoFormatDeliveryState(
                rawVideoActive = true,
                effectiveColorSpace = "RAW",
                truthfulColorDelivery = true,
                cinemaModeLabel = "RAW"
            ),
            previewLookState = VideoFormatPreviewLookState(
                rawEnabled = true,
                displayReferredMonitoring = true
            )
        )

        assertNotEquals(pro, raw)
        assertTrue(raw.rawVideoActive)
        assertEquals("RAW", raw.cinemaModeLabel)
    }

    @Test
    fun logRequestDiffersFromProRequestOnResolvedPreviewLook() {
        val pro = buildRequest(
            deliveryState = VideoFormatDeliveryState(),
            previewLookState = VideoFormatPreviewLookState()
        )
        val log = buildRequest(
            deliveryState = VideoFormatDeliveryState(
                logVideoActive = true,
                effectiveColorSpace = "Rec.2020-LOG",
                truthfulColorDelivery = true,
                cinemaModeLabel = "LOG"
            ),
            previewLookState = VideoFormatPreviewLookState(
                logEnabled = true,
                displayReferredMonitoring = true
            )
        )

        assertNotEquals(pro, log)
        assertEquals("LOG", log.cinemaModeLabel)
        assertTrue(log.logEnabled)
    }

    @Test
    fun proSdrNeutralPreviewUsesSameNoLutPathAsNormalVideoWhenLutIsOff() {
        val request = PreviewLutRequestPolicy.build(
            uiMode = AppMode.PRO,
            deliveryState = VideoFormatDeliveryState(),
            previewLookState = VideoFormatPreviewLookState(),
            recordingActive = false,
            previewFpsHint = 60f,
            colorProfile = "Natural",
            lutEnabled = false,
            lutProfile = "Warm Cinema",
            customLutPath = "/tmp/warm.cube",
            saturation = 1f,
            contrast = 1f,
            brightness = 0f,
            hueShift = 0f,
            highlights = 0f,
            shadows = 0f
        )

        assertTrue(request.neutralVideoPreview)
        assertFalse(request.lutEnabled)
        assertEquals(null, request.customLutPath)
    }

    private fun buildRequest(
        deliveryState: VideoFormatDeliveryState,
        previewLookState: VideoFormatPreviewLookState
    ): PreviewLutRequest =
        PreviewLutRequestPolicy.build(
            uiMode = AppMode.VIDEO,
            deliveryState = deliveryState,
            previewLookState = previewLookState,
            recordingActive = false,
            previewFpsHint = 30f,
            colorProfile = "Natural",
            lutEnabled = false,
            lutProfile = "Neutral Film",
            customLutPath = null,
            saturation = 1f,
            contrast = 1f,
            brightness = 0f,
            hueShift = 0f,
            highlights = 0f,
            shadows = 0f
        )
}
