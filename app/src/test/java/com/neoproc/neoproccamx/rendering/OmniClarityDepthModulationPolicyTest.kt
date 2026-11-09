package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniClarityDepthModulationPolicyTest {

    private fun resolve(
        depth: Boolean = true,
        seg: Boolean = true,
        confidence: Float = 1.0f,
        strict: Boolean = false,
        defocus: Boolean = false
    ) = OmniClarityDepthModulationPolicy.resolve(
        depthAvailable = depth,
        segmentationAvailable = seg,
        depthConfidence = confidence,
        highEndStrict = strict,
        defocusRequested = defocus
    )

    @Test
    fun noDepthOrSegmentationDisablesModulation() {
        val plan = resolve(depth = false, seg = false)
        assertFalse(plan.active)
        // Uniform processing: foreground == background (no halo risk).
        assertEquals(plan.params.foregroundDetailGain, plan.params.backgroundDetailGain, 0.0f)
        assertEquals(0f, plan.params.defocusStrength, 0.0f)
        assertEquals("NO_DEPTH_OR_SEGMENTATION", plan.reason)
    }

    @Test
    fun foregroundGetsMoreDetailThanBackgroundAtHighConfidence() {
        val plan = resolve(confidence = 1.0f)
        assertTrue(plan.active)
        assertTrue(plan.params.foregroundDetailGain > plan.params.backgroundDetailGain)
    }

    @Test
    fun lowConfidenceConvergesForegroundAndBackground() {
        val high = resolve(confidence = 1.0f)
        val low = resolve(confidence = 0.1f)
        // Lower confidence => smaller foreground/background gap (mask trusted less).
        assertTrue(low.params.foregroundBackgroundDelta < high.params.foregroundBackgroundDelta)
    }

    @Test
    fun defocusRequiresGeometricDepthNotJustSegmentation() {
        assertEquals(0f, resolve(depth = false, seg = true, defocus = true).params.defocusStrength, 0.0f)
        assertTrue(resolve(depth = true, defocus = true).params.defocusStrength > 0f)
        // Defocus off when not requested.
        assertEquals(0f, resolve(depth = true, defocus = false).params.defocusStrength, 0.0f)
    }

    @Test
    fun strictRaisesForegroundDetailAndDefocus() {
        assertTrue(resolve(strict = true).params.foregroundDetailGain >= resolve(strict = false).params.foregroundDetailGain)
        assertTrue(
            resolve(strict = true, defocus = true).params.defocusStrength >
                resolve(strict = false, defocus = true).params.defocusStrength
        )
    }

    @Test
    fun segmentationOnlyStillModulatesAndPushConstantsWellFormed() {
        val plan = resolve(depth = false, seg = true)
        assertTrue(plan.active)
        assertEquals("SEGMENTATION_MODULATED", plan.reason)
        assertFalse(plan.params.depthMaskValid)
        assertTrue(plan.params.segmentationMaskValid)
        val packed = plan.params.toPushConstants()
        assertEquals(6, packed.size)
        assertEquals(0f, packed[4], 0.0f) // depthMaskValid
        assertEquals(1f, packed[5], 0.0f) // segmentationMaskValid
    }
}
