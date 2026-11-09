package com.neoproc.neoproccamx.apex

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexShaderContractTest {

    @Test
    fun toneMapShaderExposesEliteApexContracts() {
        val shader = File("src/main/cpp/shaders/vulkan/apex/apex_tonemap.frag").readText()

        assertFalse(shader.contains("precision mediump float"))
        assertTrue(shader.contains("apexPiecewisePerceptualTone"))
        assertTrue(shader.contains("tileLocalToneStrength"))
        assertTrue(shader.contains("triangularDither"))
        assertTrue(shader.contains("MULTI-SCALE DETAIL"))
    }

    @Test
    fun ictcpShaderUsesHighPrecisionMath() {
        val shader = File("src/main/cpp/shaders/vulkan/apex/apex_ictcp.frag").readText()

        assertFalse(shader.contains("precision mediump float"))
        assertFalse(shader.contains("mediump vec3"))
        assertTrue(shader.contains("precision highp float"))
    }

    @Test
    fun zeroModeDoesNotUseDetailGainAsLocalTone() {
        val shader = File("src/main/cpp/shaders/vulkan/ycbcr_preview.frag").readText()

        assertTrue(shader.contains("float localApexTone = apexZero ? 0.0 :"))
        assertFalse(shader.contains("float localApexTone = clamp(abs(uGrade2.y), 0.0, 1.0);"))
    }

    @Test
    fun zeroModeDoesNotApplyBaselineLaplacianDetail() {
        val shader = File("src/main/cpp/shaders/vulkan/ycbcr_preview.frag").readText()
        val detailBlock = shader.substringAfter("float detailFinal;")
            .substringBefore("// Apply detail to luma only")
        val zeroDetailBlock = detailBlock.substringAfter("if (apexZero) {")
            .substringBefore("} else")

        assertTrue(detailBlock.contains("if (apexZero)"))
        assertTrue(zeroDetailBlock.contains("detailFinal = detailLuma * detailGain * 0.55;"))
        assertFalse(zeroDetailBlock.contains("0.68"))
        assertFalse(zeroDetailBlock.contains("darkHaloPotential"))
        assertFalse(zeroDetailBlock.contains("shinePotential"))
    }

    @Test
    fun distantDetailLaneIsAppliedUniformlyAndNotSubjectGated() {
        val shader = File("src/main/cpp/shaders/vulkan/ycbcr_preview.frag").readText()

        // X4/R4: the luma detail lane must be uniform across the frame so distant texture is
        // sharpened identically to near subjects. The old subject-mask gate (which zeroed the
        // frame-wide lane and only re-added detail inside the subject) must be gone.
        assertTrue(shader.contains("float profileDetailGate = 1.0;"))
        assertFalse(shader.contains("subjectMaskedDetailActive"))
    }

    @Test
    fun encodedApexBranchDoesNotDoubleSharpenSubject() {
        val shader = File("src/main/cpp/shaders/vulkan/ycbcr_preview.frag").readText()
        val apexBranch = shader
            .substringAfter("NIVUKX APEX DELIVERY PATH")
            .substringBefore("Output: PQ ST2084 encoding")

        // With the global detail lane forced uniform, the additive subject-only detail pass must
        // NOT run inside the encoded apex branch — otherwise the near subject is double-sharpened
        // and the near/far asymmetry returns.
        assertTrue(apexBranch.contains("float profileDetailGate = 1.0;"))
        assertFalse(apexBranch.contains("applySubjectGatedDetail"))
    }

    @Test
    fun apexBlurShaderUsesHighPrecisionForDetailPyramid() {
        val shader = File("src/main/cpp/shaders/vulkan/apex/apex_blur.frag").readText()

        assertTrue(shader.contains("precision highp float"))
        assertTrue(shader.contains("precision highp sampler2D"))
        assertFalse(shader.contains("precision mediump float"))
        assertFalse(shader.contains("mediump vec3"))
        assertFalse(shader.contains("mediump float"))
    }
}
