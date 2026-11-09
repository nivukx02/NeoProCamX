package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s5-f06 / CP-F7 — host-verifiable bokeh/depth contracts:
 *  - thin-lens CoC: blur radius grows with |depth - focusPlane| and is ~0 at the focus plane;
 *  - temporal stability: a deadband+EMA deflicker collapses the flicker sigma of a noisy-static depth
 *    sequence (no blur boiling), while the identity defaults reproduce the input exactly (no-downgrade);
 *  - depth EPE: a mean end-point-error helper within a MiDaS-class sanity band.
 */
class DepthEstimatorBokehTemporalContractTest {

    // -- Thin-lens CoC (prompt section 4.2) --------------------------------------------------------

    @Test
    fun circleOfConfusionFollowsThinLensCurveVsDefocus() {
        val vw = 1920
        // Focus the lens at the normalized-inverse-depth plane 0.5.
        val focusMeters = DepthEstimator.normalizedInverseDepthToMeters(0.5f)

        val atFocus = DepthEstimator.circleOfConfusionRadiusPixels(0.5f, focusMeters, vw)
        val near1 = DepthEstimator.circleOfConfusionRadiusPixels(0.55f, focusMeters, vw)
        val near2 = DepthEstimator.circleOfConfusionRadiusPixels(0.60f, focusMeters, vw)
        val near3 = DepthEstimator.circleOfConfusionRadiusPixels(0.70f, focusMeters, vw)

        // At the focus plane the CoC is exactly zero (perfect focus -> bokeh passthrough region).
        assertEquals(0f, atFocus, 1.0e-4f)
        // Radius grows monotonically as |depth - focusPlane| grows (thin-lens, not a flat blur).
        assertTrue("defocus must blur", near1 > atFocus)
        assertTrue(near2 > near1)
        assertTrue(near3 > near2)
        // Defocusing on the far side of the focus plane also blurs (symmetry of |d - focus|).
        val far1 = DepthEstimator.circleOfConfusionRadiusPixels(0.45f, focusMeters, vw)
        assertTrue("both sides of focus blur", far1 > atFocus)
    }

    // -- Temporal stability: no blur boiling (prompt section 4.4) ----------------------------------

    @Test
    fun temporalDeflickerSuppressesStaticSceneFlicker() {
        // A static scene whose depth jitters by sensor/inference noise (~+-0.02 around 0.50).
        val noisyStatic = floatArrayOf(0.50f, 0.52f, 0.49f, 0.51f, 0.50f, 0.48f, 0.52f, 0.50f, 0.49f, 0.51f)
        val rawSigma = DepthEstimator.temporalFlickerSigma(noisyStatic)
        val deflickered = DepthEstimator.temporalDeflickerSequence(noisyStatic, deadband = 0.03f, alpha = 0.25f)
        val stableSigma = DepthEstimator.temporalFlickerSigma(deflickered)

        assertTrue("raw noisy-static sequence must actually flicker", rawSigma > 0.005f)
        // Deflicker must drive the flicker sigma well below the boiling threshold.
        assertTrue("deflickered flicker sigma must be bounded (no blur boiling)", stableSigma < 0.005f)
        assertTrue("deflicker must reduce flicker", stableSigma < rawSigma)
    }

    @Test
    fun deflickerIdentityDefaultsReproduceInputExactly() {
        // No-downgrade: deadband 0 + alpha 1 is a bit-for-bit passthrough of the input sequence.
        val seq = floatArrayOf(0.10f, 0.40f, 0.25f, 0.90f, 0.05f)
        val identity = DepthEstimator.temporalDeflickerSequence(seq, deadband = 0f, alpha = 1f)
        assertEquals(seq.size, identity.size)
        for (i in seq.indices) assertEquals(seq[i], identity[i], 0f)
    }

    @Test
    fun motionSmoothingDeadbandDefaultIsIdentity() {
        // The live path passes no deadband -> default 0 -> identical to the pre-hardening output.
        val previous = floatArrayOf(0.2f)
        val current = floatArrayOf(0.8f)
        val baseline = DepthEstimator.motionAdaptiveTemporalDepthSmoothing(
            currentDepth = current, previousDepth = previous,
            currentLuma = floatArrayOf(0.5f), previousLuma = floatArrayOf(0.5f)
        )
        val explicitZero = DepthEstimator.motionAdaptiveTemporalDepthSmoothing(
            currentDepth = current, previousDepth = previous,
            currentLuma = floatArrayOf(0.5f), previousLuma = floatArrayOf(0.5f),
            deadband = 0f
        )
        assertEquals(baseline.depth[0], explicitZero.depth[0], 0f)
        assertEquals(0.59f, baseline.depth[0], 1.0e-4f)
    }

    // -- Depth EPE sanity bound (prompt section 4.5) ----------------------------------------------

    @Test
    fun meanEndPointErrorIsWithinMidasClassSanityBand() {
        val truth = floatArrayOf(0.10f, 0.20f, 0.30f, 0.40f, 0.50f)
        val estimate = floatArrayOf(0.12f, 0.18f, 0.33f, 0.39f, 0.52f)
        val epe = DepthEstimator.meanEndPointError(estimate, truth)
        // Mean |estimate - truth| == (0.02+0.02+0.03+0.01+0.02)/5 = 0.02 normalized depth units.
        assertEquals(0.02f, epe, 1.0e-4f)
        // Sanity bound: a MiDaS-class normalized-depth EPE on a near-correct map stays small (< 0.10).
        assertTrue("EPE must be within the MiDaS-class sanity band", epe < 0.10f)
        // A perfect estimate has zero error.
        assertEquals(0f, DepthEstimator.meanEndPointError(truth, truth), 0f)
    }
}
