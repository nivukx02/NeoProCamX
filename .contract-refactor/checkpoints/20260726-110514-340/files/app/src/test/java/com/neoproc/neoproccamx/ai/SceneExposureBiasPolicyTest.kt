package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class SceneExposureBiasPolicyTest {

    @Test
    fun backlitHdrScene_balancesMidsWithoutOverBrightening() {
        val bias = SceneExposureBiasPolicy.resolve(
            sceneType = SceneType.BACKLIGHT,
            overallLuminance = 0.28f,
            hdrActive = true,
            logActive = false,
            rawActive = false,
            faceLockConfidence = 0f,
            faceEvBias = 0f
        )

        assertTrue(bias in 0.28f..0.48f)
    }

    @Test
    fun brightExtremeHdrScene_protectsHighlightsInsteadOfOnlyBrightening() {
        val bias = SceneExposureBiasPolicy.resolve(
            sceneType = SceneType.BRIGHT_EXTREME,
            overallLuminance = 0.88f,
            hdrActive = true,
            logActive = false,
            rawActive = false,
            faceLockConfidence = 0f,
            faceEvBias = 0f
        )

        assertTrue(bias in -0.28f..-0.04f)
    }

    @Test
    fun confidentFaceBias_isPreservedWithSmallSceneAssist() {
        val bias = SceneExposureBiasPolicy.resolve(
            sceneType = SceneType.BACKLIGHT,
            overallLuminance = 0.30f,
            hdrActive = false,
            logActive = false,
            rawActive = false,
            faceLockConfidence = 0.82f,
            faceEvBias = 0.40f
        )

        assertTrue(bias > 0.45f)
        assertTrue(bias < 0.55f)
    }
}
