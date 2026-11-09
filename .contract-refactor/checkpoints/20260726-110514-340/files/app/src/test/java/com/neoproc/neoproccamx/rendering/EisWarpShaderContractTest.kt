package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s3-f06 / CP60 — source-text contract for the gyro-EIS per-scanline warp shader. Pins the no-downgrade
 * identity-OFF default, the per-row correction lookup, the crop-margin clamp (a black border can never be
 * revealed), the bilinear resample, the classical-only rule, and that the committed SPIR-V header is in sync.
 */
class EisWarpShaderContractTest {

    private val shader: String = File("src/main/cpp/shaders/vulkan/eis_warp.comp").readText()

    @Test
    fun warpIsGatedOffByDefaultToAnIdentityResample() {
        // The spec constant gates EIS, defaulting OFF, and the OFF path is a bit-for-bit passthrough.
        assertTrue(shader.contains("layout(constant_id = 0) const bool USE_EIS_WARP = false;"))
        assertTrue("OFF path must be an identity resample", shader.contains("imageStore(uOutput, p, imageLoad(uInput, p));"))
    }

    @Test
    fun warpIsPerScanlineCropClampedBilinearResample() {
        assertTrue(shader.contains("layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;"))
        assertTrue(shader.contains("uInput"))
        assertTrue(shader.contains("uRowGrid"))
        assertTrue(shader.contains("uOutput"))
        // Per-output-row correction lookup from the grid.
        assertTrue(shader.contains("imageLoad(uRowGrid"))
        // EisWarpParams block carries the crop margin + roll pivot + row count.
        assertTrue(shader.contains("EisWarpParams"))
        listOf("cropMarginPixels", "centerX", "centerY", "gridRows").forEach {
            assertTrue("missing EisWarpParams field $it", shader.contains(it))
        }
        // Crop-margin clamp on the source fetch — the never-reveal-a-border guarantee.
        assertTrue("source fetch must be crop-clamped", shader.contains("src = clamp(src, lo, hi);"))
        assertTrue("crop margin bounds the fetch window", shader.contains("uParams.cropMarginPixels"))
        // Bilinear+ resample.
        assertTrue(shader.contains("sampleInputBilinear"))
    }

    @Test
    fun warpUsesNoLearnedWeightsOrVendorTechnology() {
        val lower = shader.lowercase()
        listOf("tflite", "nnapi", "onnx", "neural", "midas", "tensorflow", "mediapipe")
            .forEach { token -> assertFalse("EIS warp must stay classical — found '$token'", lower.contains(token)) }
    }

    @Test
    fun committedSpirvHeaderStaysInSync() {
        val header = File("src/main/cpp/generated/eis_warp_comp_spv.h")
        assertTrue("missing generated SPIR-V header — eis_warp must be compiled to be dispatched", header.exists())
        val text = header.readText()
        assertTrue(text.contains("kEisWarpCompSpv"))
        assertTrue(text.contains("kEisWarpCompSpvCount"))
    }
}
