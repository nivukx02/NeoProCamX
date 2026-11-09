package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PerceptualRefinementMathTest {

    @Test
    fun noisyPixels_raiseDenoiseBiasAndReduceSharpenBias() {
        val clean = IntArray(64 * 64) { argb(128, 128, 128) }
        val noisy = IntArray(64 * 64) { index ->
            val perturbation = ((index * 11) % 17) - 8
            val value = (128 + perturbation).coerceIn(0, 255)
            argb(value, value, value)
        }

        val cleanProfile = PerceptualRefinementMath.analyzePixels(clean, width = 64, height = 64)
        val noisyProfile = PerceptualRefinementMath.analyzePixels(noisy, width = 64, height = 64)

        assertTrue(noisyProfile.denoiseBoost > cleanProfile.denoiseBoost)
        assertTrue(noisyProfile.normalizedNoiseFloor > cleanProfile.normalizedNoiseFloor)
    }

    @Test
    fun laplacianHighPassRefinement_enhancesStrongEdges() {
        val pixels = IntArray(5 * 5) { argb(100, 100, 100) }
        pixels[2 * 5 + 2] = argb(200, 200, 200)
        val profile = AdaptiveRefinementProfile(
            normalizedNoiseFloor = 0.05f,
            perceptualSharpness = 0.82f,
            denoiseBoost = 0f,
            sharpenBoost = 0.08f,
            laplacianRefineStrength = 0.42f
        )

        val refined = PerceptualRefinementMath.applyLaplacianHighPassRefinement(
            pixels = pixels,
            width = 5,
            height = 5,
            profile = profile
        )

        assertTrue(channel(refined[2 * 5 + 2]) > channel(pixels[2 * 5 + 2]))
    }

    @Test
    fun detectFaceCandidates_findsCenteredFaceLikePattern() {
        val width = 128
        val height = 128
        val pixels = IntArray(width * height) { argb(28, 28, 28) }

        for (y in 18 until 112) {
            for (x in 28 until 100) {
                pixels[y * width + x] = argb(216, 168, 124)
            }
        }
        for (y in 44 until 54) {
            for (x in 40 until 58) {
                pixels[y * width + x] = argb(44, 34, 28)
            }
            for (x in 70 until 88) {
                pixels[y * width + x] = argb(44, 34, 28)
            }
        }
        for (y in 78 until 88) {
            for (x in 48 until 80) {
                pixels[y * width + x] = argb(124, 48, 44)
            }
        }

        val candidates = PerceptualRefinementMath.detectFaceCandidates(
            pixels = pixels,
            width = width,
            height = height,
            confidenceThreshold = 0.35f,
            maxFaces = 2
        )

        assertTrue(candidates.isNotEmpty())
        val primary = candidates.first()
        val centerX = primary.x + primary.width / 2f
        val centerY = primary.y + primary.height / 2f
        assertTrue(primary.confidence >= 0.35f)
        assertTrue(centerX in 0.35f..0.65f)
        assertTrue(centerY in 0.30f..0.70f)
    }

    @Test
    fun csfMicroContrastWeight_concentratesGainInMidtones() {
        val shadows = PerceptualRefinementMath.csfMicroContrastWeight(0.03f, baseGain = 1f)
        val midtones = PerceptualRefinementMath.csfMicroContrastWeight(0.50f, baseGain = 1f)
        val highlights = PerceptualRefinementMath.csfMicroContrastWeight(0.97f, baseGain = 1f)

        assertTrue(midtones > shadows * 4f)
        assertTrue(midtones > highlights * 4f)
        assertTrue(midtones in 0.20f..0.55f)
    }

    @Test
    fun edgeDirectedSharpening_enhancesFineTextureWithoutStepEdgeHalos() {
        val width = 9
        val height = 9
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val base = if (x < 4) 32 else 216
            val texture = if (x >= 5 && y == 4) 10 else 0
            argb(base + texture, base + texture, base + texture)
        }

        val refined = PerceptualRefinementMath.applyEdgeDirectedSharpening(
            pixels = pixels,
            width = width,
            height = height,
            strength = 1f
        )

        val hardEdgeDarkSide = channel(refined[4 * width + 3])
        val hardEdgeBrightSide = channel(refined[4 * width + 4])
        val texturedDetail = channel(refined[4 * width + 5])

        assertTrue(hardEdgeDarkSide <= 34)
        assertTrue(hardEdgeBrightSide >= 214)
        assertTrue(hardEdgeBrightSide <= 218)
        assertTrue(texturedDetail >= channel(pixels[4 * width + 5]))
    }

    @Test
    fun oklabChromaSpeckleGuard_suppressesOutlierWithoutRotatingHue() {
        val width = 7
        val height = 7
        val pixels = IntArray(width * height) { argb(96, 104, 100) }
        val centerIndex = 3 * width + 3
        pixels[centerIndex] = argb(34, 198, 180)

        val beforeChroma = PerceptualRefinementMath.oklabChroma(pixels[centerIndex])
        val beforeHue = PerceptualRefinementMath.oklabHueRadians(pixels[centerIndex])
        val refined = PerceptualRefinementMath.applyOklabChromaSpeckleGuard(
            pixels = pixels,
            width = width,
            height = height,
            strength = 0.95f
        )
        val afterChroma = PerceptualRefinementMath.oklabChroma(refined[centerIndex])
        val afterHue = PerceptualRefinementMath.oklabHueRadians(refined[centerIndex])

        assertTrue(afterChroma < beforeChroma * 0.78f)
        assertTrue(abs(angleDistance(beforeHue, afterHue)) < 0.10f)
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun channel(pixel: Int): Int = (pixel shr 16) and 0xFF

    private fun angleDistance(a: Float, b: Float): Float {
        var delta = a - b
        while (delta > Math.PI.toFloat()) delta -= (2f * Math.PI.toFloat())
        while (delta < -Math.PI.toFloat()) delta += (2f * Math.PI.toFloat())
        return delta
    }
}
