package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NdrToneMapComputePipelineContractTest {
    private val bridgeSource by lazy { File("src/main/cpp/vulkan_bridge.cpp").readText() }

    @Test
    fun phaseOneKneeCurveShaderMatchesExactComputeContract() {
        val source = File("src/main/cpp/shaders/knee_curve.comp").readText()

        assertTrue(source.contains("#version 450"))
        assertTrue(source.contains("layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;"))
        assertTrue(source.contains("layout(set = 0, binding = 0, rgba16f) readonly uniform image2D uInputLinearRgba16f;"))
        assertTrue(source.contains("layout(set = 0, binding = 1, rgba16f) writeonly uniform image2D uOutputToneMappedRgba16f;"))
        assertTrue(source.contains("float maxCLL;"))
        assertTrue(source.contains("float kneePoint;"))
        assertTrue(source.contains("float kneeHardness;"))
        assertTrue(source.contains("clamp(params.kneePoint, 0.55, 0.75)"))
        assertTrue(source.contains("clamp(params.kneeHardness, 1.0, 3.0)"))
        assertTrue(source.contains("modifiedReinhardKnee"))
        assertTrue(source.contains("adaptiveSigmoidKneeWeight"))
        assertTrue(source.contains("imageSize(uOutputToneMappedRgba16f)"))
        // Hue-preserving application (audit rank 7 fix): the knee is driven from
        // the max RGB channel and all channels are scaled equally, instead of
        // applying modifiedReinhardKnee per-channel (which twisted highlight hue).
        assertTrue(source.contains("float drive = max(max(rgb.r, rgb.g), rgb.b);"))
        assertTrue(source.contains("modifiedReinhardKnee(drive, kneePoint, kneeHardness, maxCLL)"))
        assertTrue(source.contains("mapped = rgb * scale;"))
        assertFalse(source.contains("modifiedReinhardKnee(source.r,"))
        assertFalse(source.contains("uint width;"))
        assertFalse(source.contains("uint height;"))
        assertFalse(source.contains("dot("))
    }

    @Test
    fun phaseTwoShadowLiftShaderPreservesContrastAndLimitsToShadows() {
        val source = File("src/main/cpp/shaders/shadow_lift.comp").readText()

        assertTrue(source.contains("#version 450"))
        assertTrue(source.contains("layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;"))
        assertTrue(source.contains("layout(set = 0, binding = 0, rgba16f) readonly uniform image2D uInputToneMappedRgba16f;"))
        assertTrue(source.contains("layout(set = 0, binding = 1, rgba16f) writeonly uniform image2D uOutputShadowLiftedRgba16f;"))
        assertTrue(source.contains("float shadowLift;"))
        assertTrue(source.contains("float contrastRecovery;"))
        assertTrue(source.contains("float luminanceThreshold;"))
        assertTrue(source.contains("const float DEFAULT_LUMINANCE_THRESHOLD = 0.4;"))
        assertTrue(source.contains("clamp(params.shadowLift, 0.01, 0.10)"))
        assertTrue(source.contains("clamp(params.contrastRecovery, 1.05, 1.15)"))
        assertTrue(source.contains("clamp(params.luminanceThreshold, 0.05, 0.40)"))
        assertTrue(source.contains("if (luma >= luminanceThreshold)"))
        assertTrue(source.contains("exponentialShadowLift"))
        assertTrue(source.contains("triangularDither"))
        assertTrue(source.contains("imageStore(uOutputShadowLiftedRgba16f, coord, vec4(mapped, source.a))"))
    }

    @Test
    fun shadersHaveGeneratedSpirvArtifacts() {
        val kneeHeader = File("src/main/cpp/generated/knee_curve_comp_spv.h")
        val kneeSpv = File("src/main/cpp/generated/knee_curve.comp.spv")
        val shadowHeader = File("src/main/cpp/generated/shadow_lift_comp_spv.h")
        val shadowSpv = File("src/main/cpp/generated/shadow_lift.comp.spv")

        assertTrue(kneeSpv.isFile)
        assertTrue(kneeSpv.length() > 0L)
        assertTrue(kneeHeader.readText().contains("kKneeCurveCompSpv"))
        assertTrue(kneeHeader.readText().contains("kKneeCurveCompSpvCount"))
        assertTrue(shadowSpv.isFile)
        assertTrue(shadowSpv.length() > 0L)
        assertTrue(shadowHeader.readText().contains("kShadowLiftCompSpv"))
        assertTrue(shadowHeader.readText().contains("kShadowLiftCompSpvCount"))
    }

    @Test
    fun nativeBridgeCreatesAndDispatchesKneeAndShadowComputePipelines() {
        assertTrue(bridgeSource.contains("#include \"generated/knee_curve_comp_spv.h\""))
        assertTrue(bridgeSource.contains("#include \"generated/shadow_lift_comp_spv.h\""))
        assertTrue(bridgeSource.contains("struct KneeCurvePushConstants"))
        assertTrue(bridgeSource.contains("struct ShadowLiftPushConstants"))
        assertTrue(bridgeSource.contains("float luminanceThreshold = 0.40f"))
        assertTrue(bridgeSource.contains("VkPipeline kneeCurvePipeline = VK_NULL_HANDLE"))
        assertTrue(bridgeSource.contains("VkPipeline shadowLiftPipeline = VK_NULL_HANDLE"))
        assertTrue(bridgeSource.contains("createImageComputePipeline("))
        assertTrue(bridgeSource.contains("kKneeCurveCompSpv"))
        assertTrue(bridgeSource.contains("kShadowLiftCompSpv"))
        assertTrue(bridgeSource.contains("vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->kneeCurvePipeline)"))
        assertTrue(bridgeSource.contains("sizeof(KneeCurvePushConstants)"))
        assertTrue(bridgeSource.contains("vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->shadowLiftPipeline)"))
        assertTrue(bridgeSource.contains("sizeof(ShadowLiftPushConstants)"))
        assertTrue(bridgeSource.contains("vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u)"))
        assertTrue(bridgeSource.contains("VkImageMemoryBarrier kneeToShadowBarriers"))
        assertTrue(bridgeSource.contains("VkMemoryBarrier postShadowMemoryBarrier"))
        assertTrue(bridgeSource.contains("VK_ACCESS_SHADER_WRITE_BIT"))
        assertTrue(bridgeSource.contains("VK_ACCESS_SHADER_READ_BIT"))
        assertTrue(bridgeSource.contains("kneeCurveShaderWords="))
        assertTrue(bridgeSource.contains("shadowLiftShaderWords="))
        assertTrue(bridgeSource.contains("toneMapChain=NDR_NIVUKX_UNIVERSAL_KNEE_SHADOW"))
    }
}
