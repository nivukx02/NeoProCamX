package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Niv99RealtimePixelRendererContractTest {
    private val appRoot = sequenceOf(File("."), File("../../app"))
        .first { File(it, "src/main/cpp/vulkan_bridge.cpp").isFile }
    private val nativeBridge = File(appRoot, "src/main/cpp/vulkan_bridge.cpp").readText()
    private val shader = File(
        appRoot,
        "src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp"
    ).readText()

    @Test
    fun `Vulkan and shader push constants retain the exact NIV99 tail ABI`() {
        val nativeParams = nativeBridge
            .substringAfter("struct RealtimeComputePushConstants {")
            .substringBefore("};")
        val shaderParams = shader
            .substringAfter("layout(push_constant) uniform RealtimeEnhanceParams {")
            .substringBefore("} params;")
        val orderedFields = listOf(
            "niv99Active",
            "niv99RedCorrection",
            "niv99GreenCorrection",
            "niv99BlueCorrection",
            "niv99SpatialDenoise",
            "niv99ChromaDenoise",
            "niv99TemporalDenoise",
            "niv99SharpeningCeiling",
            "niv99Confidence"
        )

        assertTrue(orderedFields.zipWithNext().all { (a, b) ->
            nativeParams.indexOf(a) < nativeParams.indexOf(b)
        })
        assertTrue(orderedFields.zipWithNext().all { (a, b) ->
            shaderParams.indexOf(a) < shaderParams.indexOf(b)
        })
        assertTrue(nativeBridge.contains("sizeof(RealtimeComputePushConstants) == 100"))
    }

    @Test
    fun `Vulkan applies NIV99 to every sample and preserves highlight headroom`() {
        val whiteBalance = shader
            .substringAfter("vec3 realtimeApplyNiv99WhiteBalance(vec3 inputRgb)")
            .substringBefore("vec3 sampleCameraRgb")
        val sample = shader
            .substringAfter("vec3 sampleCameraRgb(vec2 uv)")
            .substringBefore("struct RealtimeLumaNeighborhood")

        assertTrue(whiteBalance.contains("source * mix(vec3(1.0), requestedGain, gate)"))
        assertTrue(whiteBalance.contains("if (sourcePeak <= 1.0001 && correctedPeak > 1.0)"))
        assertTrue(whiteBalance.contains("float noClipScale = 1.0 / correctedPeak"))
        assertTrue(sample.contains("return realtimeApplyNiv99WhiteBalance(sampled)"))
        assertTrue(shader.contains("niv99FuseStrength(base, params.niv99SpatialDenoise"))
        assertTrue(shader.contains("niv99FuseStrength(base, params.niv99ChromaDenoise"))
        assertTrue(shader.contains("realtimeApplyCumulativeDetailBudget"))
    }

    @Test
    fun `native packet state cannot leak to a missing stale or failed frame`() {
        val submit = nativeBridge
            .substringAfter("VulkanBridge_nativeSubmitHardwareBuffer(")
            .substringBefore("// Zero-Copy Tensor Bridge")

        assertTrue(submit.contains("niv99.currentFrameActive = false"))
        assertTrue(submit.contains("const bool exactImageTimestamp"))
        assertTrue(submit.contains("const bool stale = staleSameCamera || staleDifferentCamera"))
        assertTrue(submit.contains("historyContentValid.begin()"))
        assertTrue(submit.contains("return primaryNiv99Applied ? 3 : 1"))
        assertTrue(submit.contains("NIV99_PIXEL_APPLIED_NATIVE"))
        assertFalse(submit.contains("dedicated graph is unattached"))
    }
}
