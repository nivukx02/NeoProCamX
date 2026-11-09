package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase5PlusComputeStackContractTest {

    private val bridge: String by lazy { File("src/main/cpp/vulkan_bridge.cpp").readText() }

    @Test
    fun noBurstPathSeedsQfcaFromRealCameraFrame() {
        assertTrue(bridge.contains("const bool realMfsrRingReady = state->mfsrRealInputsEnabled && state->mfsrBurstRingReady;"))
        assertTrue(bridge.contains("Pass 1a: REAL MFSR FUSION"))
        assertTrue(bridge.contains("Pass 1b: NO-BURST REAL-FRAME SEED"))
        assertTrue(bridge.contains("vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->computePipeline);"))
        assertTrue(bridge.contains("&target->computeDescriptorSets[imageIndex]"))
        assertTrue(bridge.contains("sizeof(RealtimeComputePushConstants)"))
        assertFalse("dummy fallback must not dispatch mfsr with zero-noise constants", bridge.contains("mfsrParams.noiseS = 0.0f"))
    }

    @Test
    fun realtimeSeedCarriesDimensionsAndEnhancementPolicy() {
        assertTrue(bridge.contains("params.width = target->extent.width;"))
        assertTrue(bridge.contains("params.height = target->extent.height;"))
        assertTrue(bridge.contains("state->enhancementUniforms.values[5]"))
        assertTrue(bridge.contains("state->enhancementUniforms.values[6]"))
        assertTrue(bridge.contains("state->enhancementUniforms.values[8]"))
        assertTrue(bridge.contains("state->enhancementUniforms.values[26]"))
        assertTrue(bridge.contains("state->enhancementUniforms.values[20]"))
        assertTrue(bridge.contains("state->enhancementUniforms.values[21]"))
        assertTrue(bridge.contains("state->enhancementUniforms.values[22]"))
    }

    @Test
    fun opticalSharpenUsesRealBoundedOvershootAndPolicyDrivenPushConstants() {
        val shader = File("src/main/cpp/shaders/vulkan/sharpen.comp").readText()
        assertTrue(shader.contains("float edgeBudget = mix(0.020, 0.006"))
        assertTrue(shader.contains("float maxOvershoot = mix(0.006, edgeBudget, hvsOvershootMask);"))
        assertFalse(shader.contains("mix(0.0001, 0.00002"))
        assertTrue(bridge.contains("const float sharpenAuthority = unitStrength(params.sharpening);"))
        assertTrue(bridge.contains("sharpenParams.amount = std::clamp((0.52f + sharpenAuthority * 0.46f)"))
    }

    @Test
    fun bokehUploadResizesDepthAndScalesCocForFullResolutionTargets() {
        assertTrue(bridge.contains("resampleBokehGrid("))
        assertTrue(bridge.contains("const float cocScale = std::max("))
        assertTrue(bridge.contains("depthResized = resampleBokehGrid"))
        assertTrue(bridge.contains("cocResized = resampleBokehGrid"))
        assertFalse(
            "upload must not require producer dimensions to exactly match target dimensions",
            bridge.contains("target->bokehDepth.width == static_cast<uint32_t>(width) &&")
        )
    }
}
