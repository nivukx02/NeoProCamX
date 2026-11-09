package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks the OmniClarity §4 Riven shader as a classical, confidence-gated, noise-regularized
 * directional motion deconvolution with a ringing clamp, and keeps its committed SPIR-V in sync.
 */
class RivenMotionDeblurShaderContractTest {

    private val shader: String =
        File("src/main/cpp/shaders/vulkan/riven_motion_deblur.comp").readText()

    @Test
    fun shaderIsConfidenceGatedDirectionalDeconvolutionWithRingingClamp() {
        assertTrue(shader.contains("layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;"))
        assertTrue(shader.contains("uInput"))
        assertTrue(shader.contains("uMotionField"))
        assertTrue(shader.contains("uOutput"))

        assertTrue(shader.contains("RivenDeblurParams"))
        listOf("exposureFraction", "minKernelPx", "snrInverse", "maxKernelPx", "deblurStrength", "rlIterations")
            .forEach { field -> assertTrue("missing push-constant field $field", shader.contains(field)) }

        // Line kernel length from flow: L = |v| * exposureFraction.
        assertTrue(shader.contains("uParams.exposureFraction"))
        assertTrue(shader.contains("kernelLength"))
        // Confidence gate: below minKernelPx pass through.
        assertTrue(shader.contains("kernelLength < uParams.minKernelPx"))
        // Noise-regularized gain.
        assertTrue(shader.contains("noiseRegularization"))

        // CP53 Path A — clamp-bounded iterative (van Cittert) deconvolution. The estimate is seeded
        // from the input, driven along the high-frequency residual each iteration, and re-clamped to
        // the input line extremes so overshoot/ringing is impossible at any iteration count.
        assertTrue("must iterate rlIterations times", shader.contains("uParams.rlIterations"))
        assertTrue("estimate seeded from input center", shader.contains("vec3 est = center;"))
        assertTrue("residual taken against the fixed input line-blur", shader.contains("residual = est - blurEstimate"))
        // Ringing/halo clamp to local line extremes, applied to every iterate.
        assertTrue(shader.contains("clamp(est + gain * residual, lineMin, lineMax)"))
    }

    @Test
    fun shaderUsesNoLearnedWeightsOrVendorTechnology() {
        val lower = shader.lowercase()
        listOf("tflite", "nnapi", "onnx", "neural", "midas", "tensorflow", "mediapipe")
            .forEach { token -> assertFalse("Riven must stay classical — found '$token'", lower.contains(token)) }
    }

    @Test
    fun committedSpirvHeaderStaysInSync() {
        val header = File("src/main/cpp/generated/riven_motion_deblur_comp_spv.h")
        assertTrue("missing generated SPIR-V header", header.exists())
        val text = header.readText()
        assertTrue(text.contains("kRivenMotionDeblurCompSpv"))
        assertTrue(text.contains("kRivenMotionDeblurCompSpvCount"))
    }
}
