package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AIHdrToneMapPolicyTest {

    private fun channelSpread(pixel: Int): Int {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        return maxOf(red, green, blue) - minOf(red, green, blue)
    }

    @Test
    fun toneMapPolicy_usesBt2100LumaMappingInsteadOfCustomHdrHacks() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ai/AIHdrToneMapPolicy.kt").readText()

        assertTrue(source.contains("toneMapBt2100LumaPreservingRgb"))
        assertFalse(source.contains("cascadeFold("))
        assertFalse(source.contains("spectralShoulder("))
        assertFalse(source.contains("depthCurve("))
        assertFalse(source.contains("spectralRecovery("))
        assertFalse(source.contains("quantumCompress("))
        assertFalse(source.contains("redOverdrive"))
        assertFalse(source.contains("cyanShadow"))
    }

    @Test
    fun resolveProfile_prefersAcesWhenHighlightsClip() {
        val profile = AIHdrToneMapPolicy.resolveProfile(
            HdrLinearStats(
                clippedRatio = 0.05f,
                meanLuma = 0.55f,
                shadowRatio = 0.10f,
                peakLuma = 1.30f,
                dynamicRangeSpan = 0.95f,
                midtoneRatio = 0.34f
            )
        )

        // HIGH-END UPDATE: For highlight priority, we use GT/Reinhard blend for better brightness
        // ACES is now reserved for extreme/cinematic scenes only
        assertEquals(HdrSceneIntent.HIGHLIGHT_PRIORITY, profile.sceneIntent)
        // Higher white point = brighter highlights (high-end improvement)
        assertTrue(profile.whitePoint > 2.0f)
        // UPGRADED: Reduced highlight desaturation for better color preservation
        assertTrue(profile.highlightDesaturation >= 0.06f && profile.highlightDesaturation <= 0.12f)
    }

    @Test
    fun resolveProfile_liftsShadowsForDarkHdrScenes() {
        val profile = AIHdrToneMapPolicy.resolveProfile(
            HdrLinearStats(
                clippedRatio = 0.0f,
                meanLuma = 0.16f,
                shadowRatio = 0.46f,
                peakLuma = 0.72f,
                dynamicRangeSpan = 0.70f,
                midtoneRatio = 0.18f
            )
        )

        assertTrue(profile.exposureBoost > 1.0f)
        assertTrue(profile.shadowLift >= 0.02f)
        assertEquals(HdrSceneIntent.SHADOW_PRIORITY, profile.sceneIntent)
    }

    @Test
    fun shouldRetoneMapDisplay_onlyTriggersForProblematicRanges() {
        assertFalse(
            AIHdrToneMapPolicy.shouldRetoneMapDisplay(
                HdrLinearStats(
                    clippedRatio = 0.005f,
                    meanLuma = 0.40f,
                    shadowRatio = 0.12f,
                    peakLuma = 0.70f,
                    dynamicRangeSpan = 0.52f,
                    midtoneRatio = 0.42f
                )
            )
        )
        assertTrue(
            AIHdrToneMapPolicy.shouldRetoneMapDisplay(
                HdrLinearStats(
                    clippedRatio = 0.03f,
                    meanLuma = 0.72f,
                    shadowRatio = 0.08f,
                    peakLuma = 1.24f,
                    dynamicRangeSpan = 0.96f,
                    midtoneRatio = 0.20f
                )
            )
        )
    }

    @Test
    fun toneMapLinearRgb_preserves_channel_order_after_mapping() {
        val profile = HdrToneMapProfile(
            useAcesCurve = false,
            exposureBoost = 1.0f,
            whitePoint = 2.1f,
            shadowLift = 0.01f,
            midtoneContrast = 0.06f,
            highlightDesaturation = 0.14f,
            blackFloor = 0.004f,
            sceneIntent = HdrSceneIntent.BALANCED
        )

        val mapped = AIHdrToneMapPolicy.toneMapLinearRgb(
            redLinear = 1.4f,
            greenLinear = 0.9f,
            blueLinear = 0.4f,
            profile = profile
        )

        val red = (mapped shr 16) and 0xFF
        val green = (mapped shr 8) and 0xFF
        val blue = mapped and 0xFF

        assertTrue(red >= green)
        assertTrue(green >= blue)
    }

    @Test
    fun resolveProfile_usesExtremeDynamicRangeIntentForVeryWideHdrScenes() {
        val profile = AIHdrToneMapPolicy.resolveProfile(
            HdrLinearStats(
                clippedRatio = 0.07f,
                meanLuma = 0.46f,
                shadowRatio = 0.22f,
                peakLuma = 1.58f,
                dynamicRangeSpan = 1.22f,
                midtoneRatio = 0.28f
            )
        )

        assertEquals(HdrSceneIntent.EXTREME_DYNAMIC_RANGE, profile.sceneIntent)
        assertTrue(profile.useAcesCurve)
        // HIGH-END UPDATE: Higher white point for brighter highlights (was <= 1.42f)
        assertTrue(profile.whitePoint >= 2.5f)
    }
    
    @Test
    fun resolveProfile_highEndBrightnessPreservation() {
        // Test that high-end mode produces brighter results than before
        val profile = AIHdrToneMapPolicy.resolveProfile(
            HdrLinearStats(
                clippedRatio = 0.04f,
                meanLuma = 0.50f,
                shadowRatio = 0.15f,
                peakLuma = 1.20f,
                dynamicRangeSpan = 0.90f,
                midtoneRatio = 0.30f
            ),
            enableAdvancedHdr = true
        )

        // High-end should use higher white point for brightness
        assertTrue("White point should be high for brightness: ${profile.whitePoint}", 
            profile.whitePoint >= 3.0f)
        // Exposure boost should not be too low
        assertTrue("Exposure boost should preserve brightness: ${profile.exposureBoost}",
            profile.exposureBoost >= 0.98f)
        // Reduced highlight desaturation preserves colors
        assertTrue("Highlight desaturation should be gentle: ${profile.highlightDesaturation}",
            profile.highlightDesaturation <= 0.15f)
    }

    @Test
    fun resolveProfile_setsHighColorPreservationForAdvancedHdr() {
        val profile = AIHdrToneMapPolicy.resolveProfile(
            HdrLinearStats(
                clippedRatio = 0.06f,
                meanLuma = 0.52f,
                shadowRatio = 0.20f,
                peakLuma = 1.40f,
                dynamicRangeSpan = 1.02f,
                midtoneRatio = 0.33f
            ),
            enableAdvancedHdr = true
        )

        assertTrue(profile.colorPreservation >= 0.93f)
    }

    @Test
    fun toneMapLinearRgb_respectsColorPreservationStrength() {
        val baseProfile = HdrToneMapProfile(
            useAcesCurve = false,
            exposureBoost = 1.0f,
            whitePoint = 3.4f,
            shadowLift = 0.0f,
            midtoneContrast = 0.05f,
            highlightDesaturation = 0.0f,
            blackFloor = 0.0f,
            sceneIntent = HdrSceneIntent.BALANCED,
            useHybridTonemap = true,
            gtWeight = 0.45f,
            agxWeight = 0.18f,
            midtoneBoost = 0.04f,
            highlightRecovery = 0.16f,
            colorPreservation = 0.0f
        )
        val neutralized = AIHdrToneMapPolicy.toneMapLinearRgb(
            redLinear = 1.5f,
            greenLinear = 0.82f,
            blueLinear = 0.30f,
            profile = baseProfile
        )
        val preserved = AIHdrToneMapPolicy.toneMapLinearRgb(
            redLinear = 1.5f,
            greenLinear = 0.82f,
            blueLinear = 0.30f,
            profile = baseProfile.copy(colorPreservation = 1.0f)
        )

        assertTrue(channelSpread(preserved) > channelSpread(neutralized) + 8)
    }

    @Test
    fun toneMapLinearRgb_protectsWarmSkinHighlightsFromOilySaturation() {
        val profile = HdrToneMapProfile(
            useAcesCurve = true,
            exposureBoost = 1.12f,
            whitePoint = 7.0f,
            shadowLift = 0.008f,
            midtoneContrast = 0.08f,
            highlightDesaturation = 0.08f,
            blackFloor = 0.0001f,
            sceneIntent = HdrSceneIntent.CINEMATIC_WIDE_DR,
            highlightRecovery = 0.35f,
            colorPreservation = 0.99f
        )

        val mapped = AIHdrToneMapPolicy.toneMapLinearRgb(
            redLinear = 1.45f,
            greenLinear = 0.82f,
            blueLinear = 0.46f,
            profile = profile
        )

        val red = (mapped shr 16) and 0xFF
        val green = (mapped shr 8) and 0xFF
        val blue = mapped and 0xFF

        assertTrue(red >= green)
        assertTrue(green >= blue)
        assertTrue("Skin highlight should keep chroma controlled, got spread ${red - blue}", red - blue <= 92)
        assertTrue("Skin highlight should not collapse to gray", red - blue >= 18)
    }

    @Test
    fun toneMapLinearRgb_limitsExtremeRedHighlightContamination() {
        val profile = HdrToneMapProfile(
            useAcesCurve = true,
            exposureBoost = 1.18f,
            whitePoint = 8.5f,
            shadowLift = 0.006f,
            midtoneContrast = 0.08f,
            highlightDesaturation = 0.06f,
            blackFloor = 0.0001f,
            sceneIntent = HdrSceneIntent.CINEMATIC_WIDE_DR,
            highlightRecovery = 0.36f,
            colorPreservation = 0.99f
        )

        val mapped = AIHdrToneMapPolicy.toneMapLinearRgb(
            redLinear = 4.0f,
            greenLinear = 0.62f,
            blueLinear = 0.36f,
            profile = profile
        )

        val red = (mapped shr 16) and 0xFF
        val green = (mapped shr 8) and 0xFF
        val blue = mapped and 0xFF

        assertTrue("Red highlight should not clip to neon red", red < 252)
        assertTrue("Red highlight spread should stay bounded, got ${red - green}", red - green <= 82)
        assertTrue("Blue channel should remain plausible", blue >= 92)
    }

    @Test
    fun toneMapLinearRgb_neutralizesCyanShadowCastWithoutCrushingBlack() {
        val profile = HdrToneMapProfile(
            useAcesCurve = true,
            exposureBoost = 1.08f,
            whitePoint = 7.0f,
            shadowLift = 0.018f,
            midtoneContrast = 0.04f,
            highlightDesaturation = 0.08f,
            blackFloor = 0.001f,
            sceneIntent = HdrSceneIntent.SHADOW_PRIORITY,
            highlightRecovery = 0.18f,
            colorPreservation = 0.95f
        )

        val mapped = AIHdrToneMapPolicy.toneMapLinearRgb(
            redLinear = 0.018f,
            greenLinear = 0.090f,
            blueLinear = 0.150f,
            profile = profile
        )

        val red = (mapped shr 16) and 0xFF
        val green = (mapped shr 8) and 0xFF
        val blue = mapped and 0xFF

        assertTrue("Cyan shadow should be neutralized, got B-R=${blue - red}", blue - red <= 42)
        assertTrue("Shadow detail should remain visible", maxOf(red, green, blue) >= 18)
    }

    @Test
    fun skinToneWeightDetectsOnlyPlausibleWarmSkinRange() {
        assertTrue(HdrSkinToneMath.skinToneWeight(0.82f, 0.44f, 0.28f) > 0.25f)
        assertTrue(HdrSkinToneMath.skinToneWeight(0.20f, 0.62f, 1.20f) < 0.05f)
    }
}
