package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-text contract for the Chronos motion-compensated reconstruction shader.
 *
 * CP52 made the backward warp geometrically honest: the source correspondence is the Argus
 * optical-flow field alone (source = p - flow(p)). The previous fabricated "physics" — a gyro/OIS
 * displacement synthesised from optical-flow magnitude, an Einstein/Riemann radial-curvature metric
 * warp, and a per-pixel field-tensor distortion — added uncalibrated offsets ON TOP of the
 * already-correct flow and is removed. This contract now (a) pins the honest correspondence, (b)
 * pins the legitimate confidence-gated colour-domain fusion that was retained, and (c) LOCKS OUT the
 * fabricated pseudo-physics so it cannot silently regress back in.
 */
class WarpReconstructShaderContractTest {

    private fun source(): String =
        File("src/main/cpp/shaders/vulkan/warp_reconstruct.comp").readText()

    @Test
    fun warpShaderPerformsBackwardWarpWithBoundaryAndConfidenceGuards() {
        val source = source()

        assertTrue(source.contains("layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;"))
        assertTrue(source.contains("backwardWarp"))
        assertTrue(source.contains("bilinearSample"))
        assertTrue(source.contains("boundaryGuard"))
        assertTrue(source.contains("occlusion"))
        assertTrue(source.contains("forwardBackwardConsistency"))
        assertTrue(source.contains("localVarianceConfidence"))
        assertTrue(source.contains("colorSimilarityConfidence"))
        assertTrue(source.contains("confidenceAwareAlpha"))
        assertTrue(source.contains("mix(currentRgb, warpedRgb, alpha)"))
        assertTrue(source.contains("uMotionVectorField"))
        assertTrue(source.contains("uCurrentFrame"))
        assertTrue(source.contains("uPreviousFrame"))
    }

    @Test
    fun warpShaderUsesGeometricallyHonestFlowOnlyBackwardCorrespondence() {
        val source = source()

        // The backward correspondence must be the optical-flow field alone — nothing added on top.
        assertTrue(
            "warp must derive the source coord from the flow field directly",
            source.contains("vec2 sourceCoord = vec2(coord) - flow;")
        )
        // flow is read straight from the Argus motion-vector field at the current pixel.
        assertTrue(source.contains("vec2 flow = imageLoad(uMotionVectorField, coord).rg;"))
    }

    @Test
    fun warpShaderRetainsLegitimateColourDomainFusion() {
        val source = source()

        // These operate on colour/temporal confidence, not geometry, and are retained.
        assertTrue(source.contains("subPixelSuperResolutionGrid"))
        assertTrue(source.contains("continuousKalmanBucyMetricAccumulation"))
        assertTrue(source.contains("warpedRgb = subPixelSuperResolutionGrid"))
        assertTrue(source.contains("warpedRgb = continuousKalmanBucyMetricAccumulation"))
        // The bounded sub-pixel detail blend runs before the Kalman-Bucy temporal accumulator.
        assertTrue(
            source.indexOf("warpedRgb = subPixelSuperResolutionGrid") <
                source.indexOf("warpedRgb = continuousKalmanBucyMetricAccumulation")
        )
    }

    @Test
    fun warpShaderLocksOutFabricatedPseudoPhysics() {
        val source = source()

        // CP52 regression guard: none of the fabricated, uncalibrated geometry distortions may return.
        for (banned in listOf(
            "loadPhysicalGyroscopeAndOisDisplacement",
            "rollingShutterTiltCorrection",
            "einsteinianRiemannianMetricWarp",
            "einsteinFieldTensor",
            "geodesicMetricTensorFrameFusion",
            "OMNIPOTENT_GEODESIC_FRAME_FUSION",
            "ZENITH_COSMIC_PLANCK_WARP_PIPELINE",
        )) {
            assertFalse(
                "fabricated pseudo-physics '$banned' must not reappear in the honest warp",
                source.contains(banned)
            )
        }
    }
}
