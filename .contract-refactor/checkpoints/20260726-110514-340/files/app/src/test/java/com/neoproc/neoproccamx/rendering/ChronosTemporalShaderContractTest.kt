package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chronos reuses warp_reconstruct.comp as its motion-compensated, occlusion-gated temporal fuser.
 * This locks the Argus->Chronos link (the warp consumes the Argus motion field + the prior frame)
 * and that the shader's committed SPIR-V header exists so it can be dispatched.
 * (The warp algorithm itself is locked by WarpReconstructShaderContractTest.)
 */
class ChronosTemporalShaderContractTest {

    private val shader: String =
        File("src/main/cpp/shaders/vulkan/warp_reconstruct.comp").readText()

    @Test
    fun temporalWarpConsumesArgusFlowAndPriorFrameWithBoundedAlpha() {
        assertTrue(shader.contains("uMotionVectorField")) // Argus flow field
        assertTrue(shader.contains("uPreviousFrame"))      // temporal history
        assertTrue(shader.contains("uCurrentFrame"))
        assertTrue(shader.contains("WarpParams"))
        listOf("baseTemporalAlpha", "maxTemporalAlpha", "colorSigma", "varianceSigma", "flowConsistencySigma", "boundaryFeatherPixels")
            .forEach { field -> assertTrue("missing WarpParams field $field", shader.contains(field)) }
        assertTrue(shader.contains("occlusion"))
        assertTrue(shader.contains("forwardBackwardConsistency"))
    }

    @Test
    fun committedSpirvHeaderStaysInSync() {
        val header = File("src/main/cpp/generated/warp_reconstruct_comp_spv.h")
        assertTrue("missing generated SPIR-V header — warp_reconstruct must be compiled to be dispatched", header.exists())
        val text = header.readText()
        assertTrue(text.contains("kWarpReconstructCompSpv"))
        assertTrue(text.contains("kWarpReconstructCompSpvCount"))
    }
}
