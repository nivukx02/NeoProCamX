package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEnhancementRecordBoostPolicyTest {

    @Test
    fun `disabled returns base values unchanged (no downgrade)`() {
        val r = AiEnhancementRecordBoostPolicy.resolve(
            enabled = false,
            measuredPerceptualSharpness = 0.1f,
            measuredNoiseLevel = 0.9f,
            baseSharpening = 0.40f,
            baseSpatialDenoise = 0.30f,
            sharpeningCeiling = 0.90f,
            skinDetailProtection = 0f
        )
        assertEquals(0.40f, r.boostedSharpening, 0f)
        assertEquals(0.30f, r.boostedSpatialDenoise, 0f)
        assertEquals(0f, r.sharpenDelta, 0f)
        assertEquals(0f, r.denoiseDelta, 0f)
        assertFalse(r.active)
    }

    @Test
    fun `high noise increases denoise within bound`() {
        val r = AiEnhancementRecordBoostPolicy.resolve(
            enabled = true,
            measuredPerceptualSharpness = 0.8f,
            measuredNoiseLevel = 1.0f,
            baseSharpening = 0.40f,
            baseSpatialDenoise = 0.30f,
            sharpeningCeiling = 0.90f,
            skinDetailProtection = 0f
        )
        assertTrue(r.boostedSpatialDenoise > 0.30f)
        assertTrue("denoiseDelta=${r.denoiseDelta} must be <= 0.12", r.denoiseDelta <= 0.12f + 1e-6f)
        assertTrue(r.boostedSpatialDenoise <= 1f)
    }

    @Test
    fun `low measured sharpness increases sharpening within ceiling`() {
        val r = AiEnhancementRecordBoostPolicy.resolve(
            enabled = true,
            measuredPerceptualSharpness = 0.1f,
            measuredNoiseLevel = 0f,
            baseSharpening = 0.40f,
            baseSpatialDenoise = 0.30f,
            sharpeningCeiling = 0.90f,
            skinDetailProtection = 0f
        )
        assertTrue(r.boostedSharpening > 0.40f)
        assertTrue("sharpenDelta=${r.sharpenDelta} must be <= 0.10", r.sharpenDelta <= 0.10f + 1e-6f)
        assertTrue("boosted=${r.boostedSharpening} must be <= ceiling", r.boostedSharpening <= 0.90f)
        assertTrue(r.active)
    }

    @Test
    fun `skin protection attenuates sharpen boost`() {
        val noSkin = AiEnhancementRecordBoostPolicy.resolve(
            true, 0.1f, 0f, 0.40f, 0.30f, 0.90f, skinDetailProtection = 0f
        )
        val fullSkin = AiEnhancementRecordBoostPolicy.resolve(
            true, 0.1f, 0f, 0.40f, 0.30f, 0.90f, skinDetailProtection = 1f
        )
        assertTrue(fullSkin.sharpenDelta < noSkin.sharpenDelta)
    }

    @Test
    fun `boosted sharpening never exceeds ceiling even near cap`() {
        val r = AiEnhancementRecordBoostPolicy.resolve(
            enabled = true,
            measuredPerceptualSharpness = 0f,
            measuredNoiseLevel = 1f,
            baseSharpening = 0.89f,
            baseSpatialDenoise = 0.95f,
            sharpeningCeiling = 0.90f,
            skinDetailProtection = 0f
        )
        assertTrue(r.boostedSharpening <= 0.90f)
        assertTrue(r.boostedSpatialDenoise <= 1f)
    }

    @Test
    fun `zero ceiling falls back to unit cap`() {
        val r = AiEnhancementRecordBoostPolicy.resolve(
            enabled = true,
            measuredPerceptualSharpness = 0f,
            measuredNoiseLevel = 0f,
            baseSharpening = 0.95f,
            baseSpatialDenoise = 0.30f,
            sharpeningCeiling = 0f,
            skinDetailProtection = 0f
        )
        assertTrue(r.boostedSharpening <= 1f)
        assertTrue(r.boostedSharpening >= 0.95f)
    }
}
