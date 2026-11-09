package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.rendering.VulkanToneDeliveryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFormatPreviewLookPolicyTest {

    @Test
    fun rawPreviewUsesDedicatedMonitoringLook() {
        val state = VideoFormatPreviewLookPolicy.resolve(
            VideoFormatDeliveryState(
                rawVideoActive = true,
                truthfulColorDelivery = true
            )
        )

        assertFalse(state.logEnabled)
        assertFalse(state.hdrEnabled)
        assertTrue(state.rawEnabled)
        assertEquals(VideoPipelinePreviewKind.RAW_SENSOR, state.previewKind)
        assertEquals(VideoPipelineToneMap.RAW_LINEAR_MONITOR, state.toneMap)
        assertTrue(state.displayReferredMonitoring)
    }

    @Test
    fun logPreviewUsesOriginalLogLook() {
        val state = VideoFormatPreviewLookPolicy.resolve(
            VideoFormatDeliveryState(
                logVideoActive = true,
                truthfulColorDelivery = true
            )
        )

        assertTrue(state.logEnabled)
        assertFalse(state.hdrEnabled)
        assertFalse(state.rawEnabled)
        assertEquals(VideoPipelinePreviewKind.LOG_10BIT, state.previewKind)
        assertEquals(VideoPipelineToneMap.LOG_ORIGINAL, state.toneMap)
        assertFalse(state.displayReferredMonitoring)
    }

    @Test
    fun glLogPreviewDoesNotApplySdrMonitorLift() {
        val source = java.io.File(
            "src/main/java/com/neoproc/neoproccamx/rendering/PreviewGlCompositor.kt"
        ).readText()
        val logBranch = source.substringAfter("if (uGrade == 1)")
            .substringBefore("} else if (uGrade == 2)")

        assertFalse(logBranch.contains("pow(outc"))
        assertFalse(logBranch.contains("1.0 / 1.45"))
    }

    @Test
    fun hdrPreviewUsesHdrMonitoringLook() {
        val state = VideoFormatPreviewLookPolicy.resolve(
            VideoFormatDeliveryState(
                effectiveHdrMode = "HDR10",
                truthfulColorDelivery = true
            )
        )

        assertFalse(state.logEnabled)
        assertTrue(state.hdrEnabled)
        assertFalse(state.rawEnabled)
        assertEquals(VideoPipelinePreviewKind.HDR_10BIT, state.previewKind)
        assertEquals(VideoPipelineToneMap.BT2100_PQ, state.toneMap)
        assertTrue(state.displayReferredMonitoring)
    }

    @Test
    fun hdr10PreviewUsesPqMonitorMode() {
        val state = VideoFormatPreviewLookPolicy.resolve(
            VideoFormatDeliveryState(
                effectiveHdrMode = "HDR10",
                truthfulColorDelivery = true
            )
        )

        assertEquals("HDR10", state.hdrMode)
        assertEquals(
            VulkanToneDeliveryMode.NDR10_PLUS_MONITOR,
            VulkanToneDeliveryMode.resolve(
                previewLutEnabled = false,
                truthfulColorDelivery = true,
                rawVideoDelivery = false,
                rawVideoActive = false,
                logCaptureActive = false,
                normalizedHdrMode = state.hdrMode,
                previewMonitoringEnabled = state.displayReferredMonitoring
            )
        )
    }

    @Test
    fun legacyHlgPreviewUsesNeoPqMonitorMode() {
        val state = VideoFormatPreviewLookPolicy.resolve(
            VideoFormatDeliveryState(
                effectiveHdrMode = "HLG",
                truthfulColorDelivery = true
            )
        )

        assertEquals("HLG", state.hdrMode)
        assertEquals(
            VulkanToneDeliveryMode.NDR10_PLUS_MONITOR,
            VulkanToneDeliveryMode.resolve(
                previewLutEnabled = false,
                truthfulColorDelivery = true,
                rawVideoDelivery = false,
                rawVideoActive = false,
                logCaptureActive = false,
                normalizedHdrMode = state.hdrMode,
                previewMonitoringEnabled = state.displayReferredMonitoring
            )
        )
    }

    @Test
    fun neutralPreviewUsesNoMonitoringLook() {
        val state = VideoFormatPreviewLookPolicy.resolve(VideoFormatDeliveryState())

        assertFalse(state.logEnabled)
        assertFalse(state.hdrEnabled)
        assertFalse(state.rawEnabled)
        assertEquals(VideoPipelinePreviewKind.SDR, state.previewKind)
        assertEquals(VideoPipelineToneMap.REC709_NEUTRAL, state.toneMap)
        assertFalse(state.displayReferredMonitoring)
    }

    @Test
    fun wideGamutAndAcesSdrPreviewUseWideGamutMonitoringInsteadOfRec709Neutral() {
        listOf("Neo Wide Gamut", "Neo ACES AP0", "Neo ACES AP1").forEach { colorSpace ->
            val delivery = VideoFormatDeliveryPolicy.resolve(
                uiMode = AppMode.VIDEO,
                cinemaMode = com.neoproc.neoproccamx.recording.CinemaVideoMode.PRO,
                rawVideoActive = false,
                logEnabled = false,
                requestedHdrMode = "OFF",
                preferredColorSpace = colorSpace,
                tenBitActive = true
            )
            val state = VideoFormatPreviewLookPolicy.resolve(delivery)

            assertFalse("$colorSpace should not be neutral preview", delivery.neutralPreview)
            assertFalse(state.logEnabled)
            assertFalse(state.hdrEnabled)
            assertFalse(state.rawEnabled)
            assertEquals(VideoPipelinePreviewKind.SDR, state.previewKind)
            assertEquals(VideoPipelineToneMap.WIDE_GAMUT_SDR, state.toneMap)
            assertTrue(state.displayReferredMonitoring)
        }
    }

    @Test
    fun rawAndLogMonitoringStatesRemainDistinctForRefresh() {
        val raw = VideoFormatPreviewLookPolicy.resolve(
            VideoFormatDeliveryState(rawVideoActive = true, truthfulColorDelivery = true)
        )
        val log = VideoFormatPreviewLookPolicy.resolve(
            VideoFormatDeliveryState(logVideoActive = true, truthfulColorDelivery = true)
        )

        assertNotEquals(raw, log)
    }
}
