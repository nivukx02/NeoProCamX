package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanPreviewSurfacePrecisionTest {
    @Test
    fun `packed ten bit native preview is strict-admissible`() {
        val status = VulkanPreviewSurfaceStatusParser.parse(
            "supported preview=on previewFmt=A2B10G10R10 encoder=off"
        )

        assertTrue(status.bound)
        assertEquals(VulkanPreviewSurfacePrecision.PACKED_10_BIT, status.precision)
        assertTrue(status.strictTenBitOrBetterProven)
    }

    @Test
    fun `fp16 native preview is accepted as higher precision`() {
        val status = VulkanPreviewSurfaceStatusParser.parse(
            "supported preview=on previewFmt=RGBA16F encoder=off"
        )

        assertEquals(VulkanPreviewSurfacePrecision.FP16, status.precision)
        assertTrue(status.strictTenBitOrBetterProven)
    }

    @Test
    fun `requested native route that actually bound rgba8 is rejected`() {
        val status = VulkanPreviewSurfaceStatusParser.parse(
            "supported preview=on previewFmt=RGBA8 encoder=off"
        )

        assertEquals(VulkanPreviewSurfacePrecision.EIGHT_BIT, status.precision)
        assertFalse(status.strictTenBitOrBetterProven)
    }

    @Test
    fun `missing or unknown native proof fails closed`() {
        assertFalse(
            VulkanPreviewSurfaceStatusParser.parse(
                "supported preview=off previewFmt=OTHER"
            ).strictTenBitOrBetterProven
        )
        assertFalse(
            VulkanPreviewSurfaceStatusParser.parse(
                "supported preview=on previewFmt=OTHER"
            ).strictTenBitOrBetterProven
        )
    }
}
