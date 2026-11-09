package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPresentationChangeDetectorTest {

    @Test
    fun hasMeaningfulChange_returnsFalseForTinyDrift() {
        val previous = PreviewSurfacePresentationState(
            scaleX = 1f,
            scaleY = 1f,
            translationX = 10f,
            translationY = 20f,
            rotation = 0f
        )
        val current = previous.copy(translationX = 10.0005f)

        assertFalse(
            PreviewPresentationChangeDetector.hasMeaningfulChange(previous, current)
        )
    }

    @Test
    fun hasMeaningfulChange_returnsTrueForVisibleTransformDelta() {
        val previous = PreviewSurfacePresentationState(
            scaleX = 1f,
            scaleY = 1f,
            translationX = 10f,
            translationY = 20f,
            rotation = 0f
        )
        val current = previous.copy(scaleY = 1.01f)

        assertTrue(
            PreviewPresentationChangeDetector.hasMeaningfulChange(previous, current)
        )
    }

    @Test
    fun hasMeaningfulChange_ignoresSubPixelTranslationNoise() {
        val previous = PreviewSurfacePresentationState(
            scaleX = 1f,
            scaleY = 1f,
            translationX = 10f,
            translationY = 20f,
            rotation = 0f
        )
        val current = previous.copy(translationX = 10.2f)

        assertFalse(
            PreviewPresentationChangeDetector.hasMeaningfulChange(previous, current)
        )
    }

    @Test
    fun hasMeaningfulChange_detectsVisibleTranslationShift() {
        val previous = PreviewSurfacePresentationState(
            scaleX = 1f,
            scaleY = 1f,
            translationX = 10f,
            translationY = 20f,
            rotation = 0f
        )
        val current = previous.copy(translationX = 10.4f)

        assertTrue(
            PreviewPresentationChangeDetector.hasMeaningfulChange(previous, current)
        )
    }

    @Test
    fun strictMode_usesTighterTranslationThreshold() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        val baseline = PreviewSurfacePresentationState(
            scaleX = 1f,
            scaleY = 1f,
            translationX = 0f,
            translationY = 0f,
            rotation = 0f
        )
        val candidate = baseline.copy(translationX = 0.21f)
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = false
            assertFalse(PreviewPresentationChangeDetector.hasMeaningfulChange(baseline, candidate))

            HighEndQualityPolicy.strictHighEndOnly = true
            assertTrue(PreviewPresentationChangeDetector.hasMeaningfulChange(baseline, candidate))
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }
}
