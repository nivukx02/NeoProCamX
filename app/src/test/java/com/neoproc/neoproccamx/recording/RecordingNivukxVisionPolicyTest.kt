package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NivukxVisionConformance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingNivukxVisionPolicyTest {

    @Test
    fun visionLayerActivatesOnlyForNivukxVisionSelection() {
        val vision = RecordingNivukxVisionPolicy.resolve(
            request = request(storageBitDepth = 12),
            normalizedHdrVersion = "NIVUKXVISION"
        )
        val transportOnly = RecordingNivukxVisionPolicy.resolve(
            request = request(storageBitDepth = 12),
            normalizedHdrVersion = "NDR10+"
        )

        assertTrue(vision.active)
        assertFalse(transportOnly.active)
        assertEquals("NIVUKX_VISION_ELITE", vision.productProfile)
    }

    @Test
    fun tenBitTransportIsHonestLiteFallbackNotEliteClaim() {
        val plan = RecordingNivukxVisionPolicy.resolve(
            request = request(storageBitDepth = 10),
            normalizedHdrVersion = "NIVUKXVISION"
        )

        assertTrue(plan.active)
        assertEquals(NivukxVisionConformance.LITE, plan.renderPlan.conformance)
        assertEquals("MAIN12_OR_AV1_12_UNAVAILABLE", plan.renderPlan.fallbackReason)
        assertEquals("false", plan.asMetadata().getValue("nivukxVisionEliteHonored"))
    }

    private fun request(storageBitDepth: Int): EliteVideoQualityRequest =
        EliteVideoQualityRequest(
            mode = EliteVideoPipelineMode.HDR,
            width = 3840,
            height = 2160,
            fps = 30,
            codec = GpuVideoCodec.HEVC,
            hdrMode = "NIVUKXVISION",
            sourceBitDepth = storageBitDepth,
            storageBitDepth = storageBitDepth,
            tenBitTransport = storageBitDepth >= 10,
            colorSpace = "Rec.2020-NIVUKX",
            colorScience = "NIVUKX Vision"
        )
}
