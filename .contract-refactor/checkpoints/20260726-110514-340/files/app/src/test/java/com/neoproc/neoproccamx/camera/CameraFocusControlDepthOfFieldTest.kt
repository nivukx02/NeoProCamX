package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s5-f07 / CP-F8 — DoF-vs-analytic lock for the read-only [CameraFocusControl.calculateDepthOfField].
 *
 * The reference values are recomputed independently in-test from the textbook hyperfocal form
 * (H = f^2 / (N*c), the same form the production code uses) and the standard near/far limits, so
 * the test proves the shipped math matches the closed-form, not a frozen constant.
 */
class CameraFocusControlDepthOfFieldTest {

    private data class Dof(val hyperfocalM: Float, val nearM: Float, val farM: Float)

    /** Textbook hyperfocal + near/far (metres). Mirrors the production hyperfocal form H = f^2/(N*c). */
    private fun analytic(
        focusM: Float,
        focalMm: Float,
        aperture: Float,
        cocMm: Float = 0.005f
    ): Dof {
        val hyperfocalMm = (focalMm * focalMm) / (aperture * cocMm)
        val hyperfocalM = hyperfocalMm / 1000f
        val focalM = focalMm / 1000f
        val near = (hyperfocalM * focusM) / (hyperfocalM + (focusM - focalM))
        val far = if (focusM >= hyperfocalM) {
            Float.POSITIVE_INFINITY
        } else {
            (hyperfocalM * focusM) / (hyperfocalM - (focusM - focalM))
        }
        return Dof(hyperfocalM, near.coerceAtLeast(0f), far)
    }

    @Test
    fun `depth of field matches the textbook hyperfocal and near far limits over a sweep`() {
        val focals = floatArrayOf(4.5f, 6.0f, 10.0f)
        val apertures = floatArrayOf(1.8f, 2.8f, 4.0f, 8.0f)
        val distances = floatArrayOf(0.5f, 1.0f, 2.0f, 5.0f)
        for (f in focals) {
            for (n in apertures) {
                for (d in distances) {
                    val ref = analytic(d, f, n)
                    val (near, far) = CameraFocusControl.calculateDepthOfField(
                        focusDistanceMeters = d,
                        focalLengthMm = f,
                        aperture = n,
                        circleOfConfusionMm = 0.005f
                    )
                    assertEquals(
                        "near f=$f N=$n d=$d",
                        ref.nearM, near, 1e-3f
                    )
                    if (ref.farM.isInfinite()) {
                        assertTrue("far should be infinite f=$f N=$n d=$d", far.isInfinite())
                    } else {
                        assertEquals("far f=$f N=$n d=$d", ref.farM, far, 1e-3f)
                    }
                    // near <= focus <= far is the physical invariant.
                    assertTrue("near<=d f=$f N=$n d=$d", near <= d + 1e-3f)
                    assertTrue("d<=far f=$f N=$n d=$d", d <= far + 1e-3f)
                }
            }
        }
    }

    @Test
    fun `focus at or beyond hyperfocal yields infinite far limit`() {
        // f=6mm, N=22, c=0.005 -> H ~= 0.327m, so a 0.5m focus is past hyperfocal.
        val (near, far) = CameraFocusControl.calculateDepthOfField(
            focusDistanceMeters = 0.5f,
            focalLengthMm = 6.0f,
            aperture = 22.0f,
            circleOfConfusionMm = 0.005f
        )
        assertTrue("far must be infinite past hyperfocal", far.isInfinite())
        val ref = analytic(0.5f, 6.0f, 22.0f)
        assertEquals(ref.nearM, near, 1e-3f)
    }

    @Test
    fun `companion hyperfocal helper matches the textbook form`() {
        // H = f^2/(N*c) = 36 / (2.8 * 0.005) = 2571.43mm = 2.5714m
        assertEquals(
            2.5714285f,
            CinematicFocusPullControl.hyperfocalMeters(6.0f, 2.8f, 0.005f),
            1e-3f
        )
    }

    @Test
    fun `diopters to meters inverts focus distance and sends zero to infinity`() {
        assertEquals(0.5f, CinematicFocusPullControl.dioptersToMeters(2.0f), 1e-6f)
        assertEquals(2.0f, CinematicFocusPullControl.dioptersToMeters(0.5f), 1e-6f)
        assertTrue(CinematicFocusPullControl.dioptersToMeters(0f).isInfinite())
        assertTrue(CinematicFocusPullControl.dioptersToMeters(-1f).isInfinite())
    }
}
