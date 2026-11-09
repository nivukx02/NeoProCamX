package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s5-f09 / CP-F10 — routing of the s5-f07 focus-quality primitive
 * (CameraFocusControl.calculateFocusPeakingIntensity) into the bounded live
 * peaking strength. Locks the color/intensity/threshold mapping and the
 * no-downgrade default.
 */
class CameraFocusPeakingIntensityPolicyTest {

    private inline fun <T> withStrictHighEndOnly(
        enabled: Boolean,
        block: () -> T
    ): T {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = enabled
        try {
            return block()
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun `null measurement reproduces today's default strength`() {
        assertEquals(
            0.55f,
            CameraFocusPeakingIntensityPolicy.resolveOrDefault(laplacianVariance = null),
            0.0f
        )
        assertEquals(
            CameraFocusPeakingIntensityPolicy.DEFAULT_PEAKING_STRENGTH,
            CameraFocusPeakingIntensityPolicy.resolveOrDefault(laplacianVariance = null),
            0.0f
        )
    }

    @Test
    fun `non-finite measurement falls back to default strength`() {
        assertEquals(
            0.55f,
            CameraFocusPeakingIntensityPolicy.resolveOrDefault(laplacianVariance = Float.NaN),
            0.0f
        )
        assertEquals(
            0.55f,
            CameraFocusPeakingIntensityPolicy.resolveOrDefault(
                laplacianVariance = Float.POSITIVE_INFINITY
            ),
            0.0f
        )
    }

    @Test
    fun `mid quality maps to the matching strength`() = withStrictHighEndOnly(enabled = false) {
        // calculateFocusPeakingIntensity(55, 100) = 0.55 -> within bounds -> 0.55
        assertEquals(
            0.55f,
            CameraFocusPeakingIntensityPolicy.resolvePeakingStrength(
                laplacianVariance = 55f,
                focusThreshold = 100f
            ),
            0.0001f
        )
    }

    @Test
    fun `high quality saturates at the ceiling`() = withStrictHighEndOnly(enabled = false) {
        // calculateFocusPeakingIntensity(200, 100) = 1.0 (coerced) -> 1.0
        assertEquals(
            1.0f,
            CameraFocusPeakingIntensityPolicy.resolvePeakingStrength(
                laplacianVariance = 200f,
                focusThreshold = 100f
            ),
            0.0001f
        )
    }

    @Test
    fun `low quality is clamped up to the visible floor`() = withStrictHighEndOnly(enabled = false) {
        // calculateFocusPeakingIntensity(10, 100) = 0.10 -> clamped to MIN 0.20
        assertEquals(
            CameraFocusPeakingIntensityPolicy.MIN_PEAKING_STRENGTH,
            CameraFocusPeakingIntensityPolicy.resolvePeakingStrength(
                laplacianVariance = 10f,
                focusThreshold = 100f
            ),
            0.0001f
        )
    }

    @Test
    fun `resolved strength is always within the live setPeakingIntensity range`() {
        val samples = floatArrayOf(0f, 5f, 25f, 60f, 99f, 100f, 140f, 300f, 5000f)
        for (v in samples) {
            val strength = CameraFocusPeakingIntensityPolicy.resolvePeakingStrength(
                laplacianVariance = v
            )
            assertTrue("strength $strength below floor", strength >= 0.20f)
            assertTrue("strength $strength above ceiling", strength <= 1.0f)
        }
    }

    @Test
    fun `strict high-end mapping is not lower than standard for the same edge`() {
        val standard = withStrictHighEndOnly(enabled = false) {
            CameraFocusPeakingIntensityPolicy.resolvePeakingStrength(
                laplacianVariance = 85f,
                focusThreshold = 100f
            )
        }
        val strict = withStrictHighEndOnly(enabled = true) {
            CameraFocusPeakingIntensityPolicy.resolvePeakingStrength(
                laplacianVariance = 85f,
                focusThreshold = 100f
            )
        }
        // standard: 85/100 = 0.85 ; strict: 85/85 = 1.0 (coerced)
        assertEquals(0.85f, standard, 0.0001f)
        assertEquals(1.0f, strict, 0.0001f)
        assertTrue(strict >= standard)
    }
}
