package com.neoproc.neoproccamx.ai

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP-QV4 (s9-f04) — CINEMATIC DEPTH-BOKEH FOCUS POLICY contract + no-downgrade lock.
 *
 * Pins:
 *  - the focus plane sits on the subject-region depth (matte-weighted mean);
 *  - a uniform matte reproduces the fixed default focus (0.5) within 1e-4;
 *  - the subject-protecting CoC is ~0 inside the matte and monotonically grows with depth distance
 *    outside the subject;
 *  - the gate is OFF by default => passthrough params (blendStrength 0) and the CoC map (fed the
 *    default plane + a sub-threshold matte) is bit-for-bit identical to the raw depth-only CoC.
 */
class CinematicDepthFocusPolicyTest {

    @After
    fun restoreGate() {
        CinematicDepthFocusPolicy.enabled = CinematicDepthFocusPolicy.ENABLED_DEFAULT
    }

    @Test
    fun gateDefaultsOff() {
        assertFalse(CinematicDepthFocusPolicy.ENABLED_DEFAULT)
        assertFalse(CinematicDepthFocusPolicy.enabled)
    }

    @Test
    fun focusPlaneSitsOnSubjectRegionDepth() {
        // 2x2: subject is the two right pixels (depth 0.8), background is the two left (depth 0.2).
        val depth = floatArrayOf(
            0.2f, 0.8f,
            0.2f, 0.8f
        )
        val matte = floatArrayOf(
            0f, 1f,
            0f, 1f
        )
        val plane = CinematicDepthFocusPolicy.segmentationBiasedFocusDepth(depth, matte, 2, 2)
        // Matte weights only the subject pixels -> focus plane == subject depth.
        assertEquals(0.8f, plane, 1e-4f)
    }

    @Test
    fun softMatteWeightsFocusTowardHigherConfidenceRegion() {
        val depth = floatArrayOf(0.2f, 0.8f)
        val matte = floatArrayOf(0.25f, 0.75f)
        val plane = CinematicDepthFocusPolicy.segmentationBiasedFocusDepth(depth, matte, 2, 1)
        // Weighted mean: (0.25*0.2 + 0.75*0.8) / (0.25 + 0.75) = 0.65.
        assertEquals(0.65f, plane, 1e-4f)
    }

    @Test
    fun uniformMatteReproducesDefaultFocusPlane() {
        val depth = floatArrayOf(0.1f, 0.4f, 0.7f, 0.95f)
        // Uniform (all-equal) matte carries no localized subject signal -> fixed default 0.5.
        val uniformOnes = FloatArray(4) { 1f }
        val uniformHalf = FloatArray(4) { 0.5f }
        assertEquals(
            CinematicDepthFocusPolicy.DEFAULT_FOCUS_DEPTH,
            CinematicDepthFocusPolicy.segmentationBiasedFocusDepth(depth, uniformOnes, 2, 2),
            1e-4f
        )
        assertEquals(
            CinematicDepthFocusPolicy.DEFAULT_FOCUS_DEPTH,
            CinematicDepthFocusPolicy.segmentationBiasedFocusDepth(depth, uniformHalf, 2, 2),
            1e-4f
        )
    }

    @Test
    fun emptyMatteReproducesDefaultFocusPlane() {
        val depth = floatArrayOf(0.1f, 0.4f, 0.7f, 0.95f)
        val emptyMatte = FloatArray(4) { 0f }
        assertEquals(
            CinematicDepthFocusPolicy.DEFAULT_FOCUS_DEPTH,
            CinematicDepthFocusPolicy.segmentationBiasedFocusDepth(depth, emptyMatte, 2, 2),
            1e-4f
        )
        // Also: empty inputs never divide-by-zero.
        assertEquals(
            CinematicDepthFocusPolicy.DEFAULT_FOCUS_DEPTH,
            CinematicDepthFocusPolicy.segmentationBiasedFocusDepth(FloatArray(0), FloatArray(0), 0, 0),
            1e-4f
        )
    }

    @Test
    fun cocIsNearZeroInsideSubjectMatte() {
        // Subject pixels are far off the focus plane in depth, but the matte protects them.
        val focusPlane = 0.5f
        val depth = floatArrayOf(0.5f, 0.95f, 0.05f, 0.5f)
        val matte = floatArrayOf(1f, 1f, 1f, 1f) // whole frame is subject
        val coc = CinematicDepthFocusPolicy.buildFocusMatteCoc(
            depth = depth,
            matte = matte,
            focusDepth = focusPlane,
            viewportWidth = 1920
        )
        // Fully-protected subject: CoC driven to 0 everywhere despite the depth spread.
        for (c in coc) {
            assertTrue("subject CoC should be ~0 but was $c", c < 1e-4f)
        }
    }

    @Test
    fun cocGrowsMonotonicallyWithDepthDistanceOutsideSubject() {
        // Focus at the nearest plane (1.0). Depth values move away toward far (1.0 -> 0.78) but stay
        // above the CoC pixel-clamp knee, so growth is strictly monotonic. Matte is all-background so
        // the raw depth-driven CoC is used everywhere.
        val focusPlane = 1.0f
        val depth = floatArrayOf(1.0f, 0.9f, 0.85f, 0.8f, 0.78f)
        val matte = FloatArray(depth.size) { 0f }
        val coc = CinematicDepthFocusPolicy.buildFocusMatteCoc(
            depth = depth,
            matte = matte,
            focusDepth = focusPlane,
            viewportWidth = 1920
        )
        // On-plane pixel is sharp.
        assertTrue("on-plane CoC should be ~0 but was ${coc[0]}", coc[0] < 1e-3f)
        // CoC strictly grows as depth distance from the focus plane grows.
        for (i in 1 until coc.size) {
            assertTrue(
                "CoC not monotonic: coc[$i]=${coc[i]} !> coc[${i - 1}]=${coc[i - 1]}",
                coc[i] > coc[i - 1]
            )
        }
    }

    @Test
    fun subjectMatteSuppressesCocRelativeToBareDepth() {
        // Same defocused depth, once as background (raw CoC) and once as subject (suppressed).
        val focusPlane = 0.5f
        val depth = floatArrayOf(0.05f)
        val background = CinematicDepthFocusPolicy.buildFocusMatteCoc(depth, floatArrayOf(0f), focusPlane, 1920)
        val subject = CinematicDepthFocusPolicy.buildFocusMatteCoc(depth, floatArrayOf(1f), focusPlane, 1920)
        assertTrue("background CoC should be > 0 but was ${background[0]}", background[0] > 0f)
        assertTrue("subject CoC should be suppressed below background", subject[0] < background[0])
        assertTrue("subject CoC should be ~0 but was ${subject[0]}", subject[0] < 1e-4f)
    }

    @Test
    fun gateOffReturnsPassthroughParamsWithBlendStrengthZero() {
        CinematicDepthFocusPolicy.enabled = false
        val params = CinematicDepthFocusPolicy.resolveBokehParams(width = 640, height = 480, focusDepth = 0.8f)
        assertEquals(0f, params.blendStrength, 0f)
        assertEquals(640, params.width)
        assertEquals(480, params.height)
        // Gate off pins the fixed default plane regardless of the requested subject plane.
        assertEquals(CinematicDepthFocusPolicy.DEFAULT_FOCUS_DEPTH, params.focusDepth, 0f)
    }

    @Test
    fun gateOffCocMapEqualsRawDepthOnlyCoc() {
        // The live path builds its CoC around the fixed 0.5 plane. With the gate off contract inputs
        // (default plane + a sub-threshold matte), buildFocusMatteCoc must equal that raw map exactly.
        val depth = floatArrayOf(0.1f, 0.35f, 0.5f, 0.65f, 0.9f, 1f)
        val viewportWidth = 1280
        val subThresholdMatte = FloatArray(depth.size) { 0f }
        val fused = CinematicDepthFocusPolicy.buildFocusMatteCoc(
            depth = depth,
            matte = subThresholdMatte,
            focusDepth = CinematicDepthFocusPolicy.DEFAULT_FOCUS_DEPTH,
            viewportWidth = viewportWidth
        )
        val focusMeters = DepthEstimator.normalizedInverseDepthToMeters(CinematicDepthFocusPolicy.DEFAULT_FOCUS_DEPTH)
        val raw = FloatArray(depth.size) { index ->
            DepthEstimator.circleOfConfusionRadiusPixels(
                normalizedInverseDepth = depth[index],
                focusDistanceMeters = focusMeters,
                viewportWidthPixels = viewportWidth
            )
        }
        assertArrayEquals(raw, fused, 0f)
    }

    @Test
    fun gateOnCarriesSubjectPlaneButStillPassthroughBlend() {
        CinematicDepthFocusPolicy.enabled = true
        val params = CinematicDepthFocusPolicy.resolveBokehParams(width = 100, height = 100, focusDepth = 0.8f)
        // The subject-biased plane is carried, but blendStrength stays 0 (device-gated lever elsewhere).
        assertEquals(0.8f, params.focusDepth, 0f)
        assertEquals(0f, params.blendStrength, 0f)
    }
}
