package com.neoproc.neoproccamx.color

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VideoLogPreviewParityPolicyTest {

    @Test
    fun explicitSonySLog3_profileLiftsPreviewParityWithoutBreakingFlatLook() {
        val parity = VideoLogPreviewParityPolicy.resolve("Sony S-Log3", logEnabled = true)

        assertTrue(parity.contrastLift > 0f)
        assertTrue(parity.saturationLift > 0f)
        assertTrue(parity.highLoadPenaltyScale < 1f)
        assertTrue(parity.highlightBiasScale < 1f)
    }

    @Test
    fun nonLogProfile_usesNeutralPreviewParity() {
        val parity = VideoLogPreviewParityPolicy.resolve("Natural", logEnabled = false)

        assertEquals(VideoLogPreviewParity(), parity)
    }

    @Test
    fun genericLogFallback_matchesNLogParityBaseline() {
        val generic = VideoLogPreviewParityPolicy.resolve("Natural", logEnabled = true)
        val explicit = VideoLogPreviewParityPolicy.resolve("N-Log", logEnabled = false)

        assertEquals(explicit, generic)
    }

    @Test
    fun legacyAliasUsesCanonicalNLogParityWithoutLogToggle() {
        val alias = VideoLogPreviewParityPolicy.resolve("Neo Deep Curve", logEnabled = false)
        val canonical = VideoLogPreviewParityPolicy.resolve("N-Log Cinema", logEnabled = false)

        assertEquals(canonical, alias)
        assertTrue(alias.contrastLift > 0f)
        assertTrue(alias.saturationLift > 0f)
        assertTrue(alias.highlightBiasScale < 1f)
    }

    @Test
    fun strictMode_boostsPreviewParityForLogProfiles() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = false
            val standard = VideoLogPreviewParityPolicy.resolve("ARRI LogC4", logEnabled = true)

            HighEndQualityPolicy.strictHighEndOnly = true
            val strict = VideoLogPreviewParityPolicy.resolve("ARRI LogC4", logEnabled = true)

            assertTrue(strict.contrastLift > standard.contrastLift)
            assertTrue(strict.saturationLift > standard.saturationLift)
            assertTrue(strict.highLoadPenaltyScale < standard.highLoadPenaltyScale)
            assertTrue(strict.lutSaturationBoost > standard.lutSaturationBoost)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun rec2020PqPreviewCompressionPreservesHueWhileFittingDisplayRange() {
        val compressed = VideoLogPreviewParityPolicy.compressRec2020PqToDisplayOklab(
            red = 0.92f,
            green = 0.31f,
            blue = 0.12f,
            knee = 0.62f,
            kappa = 5.8f,
            chromaGamma = 0.62f
        )

        assertTrue(compressed.red in 0f..1f)
        assertTrue(compressed.green in 0f..1f)
        assertTrue(compressed.blue in 0f..1f)
        assertTrue(compressed.outputLightness in 0f..1f)
        assertTrue(compressed.outputChroma <= compressed.inputChroma)
        assertTrue(abs(compressed.outputHueRadians - compressed.inputHueRadians) < 0.0001f)
    }
}
