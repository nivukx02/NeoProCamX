package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeCameraBridgeContractTest {
    @Test
    fun nativeBufferManagerOwnsCameraSampledAhardwarebufferAllocationContract() {
        val root = repoRoot()
        val header = File(root, "app/src/main/cpp/native_buffer_manager.h").readText()
        val source = File(root, "app/src/main/cpp/native_buffer_manager.cpp").readText()
        val cmake = File(root, "app/src/main/cpp/CMakeLists.txt").readText()

        assertTrue(header.contains("class NativeBufferManager"))
        assertTrue(header.contains("enum class NativeCameraBufferFormat"))
        assertTrue(source.contains("AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE"))
        assertTrue(source.contains("AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER"))
        assertTrue(source.contains("AHARDWAREBUFFER_USAGE_VIDEO_ENCODE"))
        assertTrue(source.contains("AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420"))
        assertTrue(source.contains("AHARDWAREBUFFER_FORMAT_YCbCr_P010"))
        assertTrue(source.contains("AHardwareBuffer_isSupported"))
        assertTrue(source.contains("AHardwareBuffer_allocate"))
        assertTrue(source.contains("AHardwareBuffer_toHardwareBuffer"))
        assertTrue(source.contains("AHardwareBuffer_fromHardwareBuffer"))
        assertTrue(cmake.contains("native_buffer_manager.cpp"))
    }

    @Test
    fun kotlinNativeCameraBridgeExposesZeroCopyAllocationAndValidationWithoutPixelCopies() {
        val root = repoRoot()
        val kotlin = File(root, "app/src/main/java/com/neoproc/neoproccamx/rendering/NativeCameraBridge.kt").readText()
        val jni = File(root, "app/src/main/cpp/native_camera_bridge.cpp").readText()
        val cmake = File(root, "app/src/main/cpp/CMakeLists.txt").readText()

        assertTrue(kotlin.contains("object NativeCameraBridge"))
        assertTrue(kotlin.contains("NativeCameraBufferFormat.YUV_420_888"))
        assertTrue(kotlin.contains("NativeCameraBufferFormat.P010"))
        assertTrue(kotlin.contains("nativeAllocateHardwareBuffer"))
        assertTrue(kotlin.contains("nativeValidateHardwareBuffer"))
        assertTrue(jni.contains("Java_com_neoproc_neoproccamx_rendering_NativeCameraBridge_nativeAllocateHardwareBuffer"))
        assertTrue(jni.contains("Java_com_neoproc_neoproccamx_rendering_NativeCameraBridge_nativeValidateHardwareBuffer"))
        assertTrue(jni.contains("AHardwareBuffer_toHardwareBuffer"))
        assertTrue(jni.contains("NativeBufferManager::allocationSupported"))
        assertTrue(cmake.contains("native_camera_bridge.cpp"))
    }

    @Test
    fun vulkanImportPathUsesNativeBufferManagerValidationBeforeExternalMemoryBinding() {
        val root = repoRoot()
        val source = File(root, "app/src/main/cpp/vulkan_bridge.cpp").readText()

        assertTrue(source.contains("#include \"native_buffer_manager.h\""))
        assertTrue(source.contains("NativeBufferManager::validateExternalCameraBuffer"))
        assertTrue(source.contains("VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME"))
        assertTrue(source.contains("vkGetAndroidHardwareBufferPropertiesANDROID"))
        assertTrue(source.contains("VkExternalMemoryImageCreateInfo"))
        assertTrue(source.contains("VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID"))
        assertTrue(source.contains("VkImportAndroidHardwareBufferInfoANDROID"))
        assertTrue(source.contains("vkBindImageMemory"))
        assertTrue(source.contains("VkSamplerYcbcrConversionCreateInfo"))
    }

    @Test
    fun realtimeImportUsesBoundedCacheAndCannotBlockEncoderBehindPreview() {
        val root = repoRoot()
        val source = File(root, "app/src/main/cpp/vulkan_bridge.cpp").readText()
        val submit = source.substringAfter("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSubmitHardwareBuffer(")
            .substringBefore("// \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550")
        val renderBlock = submit.substringAfter("bool rendered = false;")

        assertTrue(source.contains("kPreviewAcquireTimeoutNs"))
        assertTrue(source.contains("acquireTimeoutNs"))
        assertTrue(source.contains("kImportedFrameCacheLimit"))
        assertTrue(renderBlock.indexOf("state->encoderTarget.valid") < renderBlock.indexOf("state->previewTarget.valid"))
        assertTrue(!submit.contains("waitForTargetIdle(state"))
        assertTrue(!submit.contains("destroyImportedFrames(state)"))
    }

    @Test
    fun timedSwapchainAcquireCannotLeaveAResetSubmissionFenceUnsignaled() {
        val root = repoRoot()
        val source = File(root, "app/src/main/cpp/vulkan_bridge.cpp").readText()
        val submit = source.substringAfter("bool recordAndSubmit(")
            .substringBefore("bool importHardwareBuffer(")

        val acquireIndex = submit.indexOf("vkAcquireNextImageKHR")
        val timeoutIndex = submit.indexOf("acquireResult == VK_TIMEOUT")
        val resetFenceIndex = submit.indexOf("vkResetFences")
        val queueSubmitIndex = submit.indexOf("vkQueueSubmit")

        assertTrue(acquireIndex >= 0)
        assertTrue(timeoutIndex > acquireIndex)
        assertTrue(resetFenceIndex > timeoutIndex)
        assertTrue(queueSubmitIndex > resetFenceIndex)
    }

    @Test
    fun encoderSwapchainPrefersLowLatencyPresentModesBeforeFifoWithoutChangingPreviewPreference() {
        val root = repoRoot()
        val source = File(root, "app/src/main/cpp/vulkan_bridge.cpp").readText()
        val presentModeResolver = source.substringAfter("VkPresentModeKHR choosePresentMode(")
            .substringBefore("VkExtent2D chooseExtent")
        val swapchainBlock = source.substringAfter("bool createTargetSurfaceResources(")
            .substringBefore("bool bindSurfaceObject(")

        assertTrue(source.contains("bool encoderTarget = false"))
        assertTrue(presentModeResolver.contains("encoderTarget"))
        assertTrue(presentModeResolver.contains("VK_PRESENT_MODE_IMMEDIATE_KHR"))
        assertTrue(presentModeResolver.contains("VK_PRESENT_MODE_MAILBOX_KHR"))
        assertTrue(presentModeResolver.contains("VK_PRESENT_MODE_FIFO_RELAXED_KHR"))
        assertTrue(presentModeResolver.contains("VK_PRESENT_MODE_FIFO_KHR"))
        assertTrue(presentModeResolver.indexOf("VK_PRESENT_MODE_IMMEDIATE_KHR") <
            presentModeResolver.indexOf("VK_PRESENT_MODE_MAILBOX_KHR"))
        assertTrue(presentModeResolver.indexOf("VK_PRESENT_MODE_MAILBOX_KHR") <
            presentModeResolver.indexOf("VK_PRESENT_MODE_FIFO_RELAXED_KHR"))
        assertTrue(presentModeResolver.indexOf("VK_PRESENT_MODE_FIFO_RELAXED_KHR") <
            presentModeResolver.indexOf("VK_PRESENT_MODE_FIFO_KHR"))
        assertTrue(swapchainBlock.contains("choosePresentMode(presentModes, target->encoderTarget)"))
    }

    @Test
    fun encoderHdrSurfacePrefersHighPrecisionHdrFormatsBeforeEightBitFallback() {
        val root = repoRoot()
        val source = File(root, "app/src/main/cpp/vulkan_bridge.cpp").readText()
        val encoderResolver = source.substringAfter("VkSurfaceFormatKHR chooseEncoderSurfaceFormat(")
            .substringBefore("const char* presentModeLabel")
        val swapchainBlock = source.substringAfter("bool createTargetSurfaceResources(")
            .substringBefore("const VkPresentModeKHR presentMode")

        assertTrue(source.contains("chooseEncoderSurfaceFormat"))
        assertTrue(encoderResolver.contains("preserve real HDR/10-bit transport"))
        assertTrue(encoderResolver.contains("VK_FORMAT_A2B10G10R10_UNORM_PACK32"))
        assertTrue(encoderResolver.contains("VK_FORMAT_A2R10G10B10_UNORM_PACK32"))
        assertTrue(encoderResolver.contains("VK_FORMAT_R16G16B16A16_SFLOAT"))
        assertTrue(encoderResolver.contains("VK_FORMAT_R8G8B8A8_UNORM"))
        assertTrue(encoderResolver.contains("VK_FORMAT_B8G8R8A8_UNORM"))
        assertTrue(encoderResolver.contains("if (hdrModeId == 0)"))
        assertTrue(
            encoderResolver.indexOf("VK_FORMAT_A2B10G10R10_UNORM_PACK32") <
                encoderResolver.indexOf("VK_FORMAT_R8G8B8A8_UNORM")
        )
        assertTrue(
            encoderResolver.indexOf("VK_FORMAT_R16G16B16A16_SFLOAT") <
                encoderResolver.indexOf("VK_FORMAT_R8G8B8A8_UNORM")
        )
        assertTrue(
            encoderResolver.indexOf("chooseSurfaceFormat(formats, hdrModeId)") <
                encoderResolver.indexOf("VK_FORMAT_R8G8B8A8_UNORM")
        )
        assertTrue(swapchainBlock.contains("target->encoderTarget"))
        assertTrue(swapchainBlock.contains("chooseEncoderSurfaceFormat(formats, state->hdrModeId)"))
        // X2/R1: the preview (DISPLAY) path threads the 10-bit-SDR display preference into the
        // selector so SDR preview can request A2B10G10R10 + sRGB; encoder path is unchanged.
        assertTrue(
            swapchainBlock.contains(
                "chooseSurfaceFormat(formats, state->hdrModeId, state->preferTenBitSdrDisplay)"
            )
        )
    }

    @Test
    fun encoderSdrSurfacePrefersPackedTenBitThenFp16BeforeEightBitFallback() {
        val root = repoRoot()
        val source = File(root, "app/src/main/cpp/vulkan_bridge.cpp").readText()
        val encoderSdrResolver = source
            .substringAfter("VkSurfaceFormatKHR chooseEncoderSdrSurfaceFormat(")
            .substringBefore("VkSurfaceFormatKHR chooseEncoderSurfaceFormat(")
        val encoderResolver = source
            .substringAfter("VkSurfaceFormatKHR chooseEncoderSurfaceFormat(")
            .substringBefore("VkPresentModeKHR choosePresentMode")

        val packedTenBitIndex = encoderSdrResolver.indexOf("isTenBitPackedRgb(f.format)")
        val fp16Index = encoderSdrResolver.indexOf("VK_FORMAT_R16G16B16A16_SFLOAT")
        val eightBitFallbackIndex = encoderSdrResolver.indexOf("chooseSdrSurfaceFormat(formats)")

        assertTrue(packedTenBitIndex >= 0)
        assertTrue(fp16Index > packedTenBitIndex)
        assertTrue(eightBitFallbackIndex > fp16Index)
        assertTrue(encoderSdrResolver.contains("isEncoderSdrColorSpace(f.colorSpace)"))
        assertTrue(encoderResolver.contains("return chooseEncoderSdrSurfaceFormat(formats);"))
        assertFalse(encoderResolver.contains("return chooseSurfaceFormat(formats, hdrModeId);"))
    }

    @Test
    fun encoderSdrUndefinedSentinelSelectsPackedTenBitWithSdrTransferTag() {
        val root = repoRoot()
        val source = File(root, "app/src/main/cpp/vulkan_bridge.cpp").readText()
        val encoderSdrResolver = source
            .substringAfter("VkSurfaceFormatKHR chooseEncoderSdrSurfaceFormat(")
            .substringBefore("VkSurfaceFormatKHR chooseEncoderSurfaceFormat(")

        assertTrue(encoderSdrResolver.contains(
            "formats.size() == 1 && formats.front().format == VK_FORMAT_UNDEFINED"
        ))
        assertTrue(encoderSdrResolver.contains(
            "fallback.format = VK_FORMAT_A2B10G10R10_UNORM_PACK32"
        ))
        assertTrue(encoderSdrResolver.contains(
            "fallback.colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR"
        ))
    }

    @Test
    fun encoderSdrRejectsPqAndHlgWhilePreviewSdrOrderingRemainsEightBitFirst() {
        val root = repoRoot()
        val source = File(root, "app/src/main/cpp/vulkan_bridge.cpp").readText()
        val colorSpaceHelper = source
            .substringAfter("bool isEncoderSdrColorSpace(")
            .substringBefore("// SDR path.")
        val previewResolver = source
            .substringAfter("VkSurfaceFormatKHR chooseSdrSurfaceFormat(")
            .substringBefore("// HDR path.")
        val encoderSdrResolver = source
            .substringAfter("VkSurfaceFormatKHR chooseEncoderSdrSurfaceFormat(")
            .substringBefore("VkSurfaceFormatKHR chooseEncoderSurfaceFormat(")

        assertTrue(colorSpaceHelper.contains("!isStrictEotfColorSpace(colorSpace)"))
        assertTrue(encoderSdrResolver.contains("isEncoderSdrColorSpace(f.colorSpace)"))
        assertTrue(previewResolver.indexOf("VK_FORMAT_B8G8R8A8_UNORM") <
            previewResolver.indexOf("Any sRGB-colourspace format"))
        assertFalse(previewResolver.contains("chooseEncoderSdrSurfaceFormat"))
    }

    @Test
    fun encoderSdrPrecisionLoggingDoesNotClaimTenBitForEightBitFallback() {
        val root = repoRoot()
        val source = File(root, "app/src/main/cpp/vulkan_bridge.cpp").readText()
        val swapchainBlock = source.substringAfter("bool createTargetSurfaceResources(")
            .substringBefore("const VkExtent2D extent")

        assertTrue(swapchainBlock.contains("Encoder SDR surface precision: high-precision"))
        assertTrue(swapchainBlock.contains("Encoder SDR surface precision fallback: 8-bit"))
        assertTrue(swapchainBlock.contains("isHighPrecisionEncoderSurfaceFormat(selectedFormat.format)"))
        assertTrue(swapchainBlock.contains("transfer=SDR"))
    }

    @Test
    fun encoderSurfaceCapabilitySummaryPublishesActualPrecisionAndResetsOnDestroy() {
        val root = repoRoot()
        val source = File(root, "app/src/main/cpp/vulkan_bridge.cpp").readText()
        val capabilityBlock = source
            .substringAfter("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeGetCapabilitySummary(")
            .substringBefore("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeGetEnhancementCapabilitySummary(")
        val colorSpaceLabelBlock = source
            .substringAfter("const char* encoderSurfaceColorSpaceLabel(")
            .substringBefore("// Find the first format")
        val destroyBlock = source
            .substringAfter("void destroySwapchainResources(")
            .substringBefore("void destroyTargetSurface(")

        assertTrue(source.contains("encoderSurfaceBitDepth("))
        assertTrue(source.contains("encoderSurfacePrecisionLabel("))
        assertTrue(source.contains("encoderSurfaceColorSpaceLabel("))
        assertTrue(capabilityBlock.contains("encoderSurfaceBound="))
        assertTrue(capabilityBlock.contains("encoderSurfaceFormat="))
        assertTrue(capabilityBlock.contains("encoderSurfaceBitDepth="))
        assertTrue(capabilityBlock.contains("encoderSurfacePrecision="))
        assertTrue(capabilityBlock.contains("encoderSurfacePrecisionFallback="))
        assertTrue(capabilityBlock.contains("encoderSurfaceColorSpace="))
        assertTrue(capabilityBlock.contains("encoderSurfaceDeliveryMode="))
        assertTrue(capabilityBlock.contains("encoderSurfaceDeliveryModeId="))
        assertTrue(capabilityBlock.contains("encoderSurfaceDeliveryCompatible="))
        assertTrue(capabilityBlock.contains("state->encoderTarget.colorSpace"))
        assertTrue(capabilityBlock.contains("state->hdrModeId"))
        assertTrue(capabilityBlock.contains("isEncoderSurfaceDeliveryCompatible("))
        assertTrue(colorSpaceLabelBlock.contains("\"SRGB_NONLINEAR\""))
        assertTrue(colorSpaceLabelBlock.contains("\"EXTENDED_SRGB_LINEAR\""))
        assertTrue(colorSpaceLabelBlock.contains("\"HDR10_ST2084\""))
        assertTrue(colorSpaceLabelBlock.contains("\"HDR10_HLG\""))
        assertTrue(colorSpaceLabelBlock.contains("\"OTHER\""))
        assertTrue(destroyBlock.contains("target->swapchainFormat = VK_FORMAT_UNDEFINED"))
        assertTrue(destroyBlock.contains("target->valid = false"))
    }

    @Test
    fun encoderSurfaceDeliveryCompatibilityMatchesHdrSelectorFallbackPolicy() {
        val root = repoRoot()
        val source = File(root, "app/src/main/cpp/vulkan_bridge.cpp").readText()
        val compatibilityBlock = source
            .substringAfter("bool isEncoderSurfaceDeliveryCompatible(")
            .substringBefore("bool isHighPrecisionEncoderSurfaceFormat(")
        val fallbackBlock = source
            .substringAfter("bool isEncoderHdrFallbackColorSpace(")
            .substringBefore("bool isEncoderSurfaceDeliveryCompatible(")

        assertTrue(compatibilityBlock.contains("case 0"))
        assertTrue(compatibilityBlock.contains("isEncoderSdrColorSpace(colorSpace)"))
        assertTrue(compatibilityBlock.contains("case 1"))
        assertTrue(compatibilityBlock.contains("VK_COLOR_SPACE_HDR10_HLG_EXT"))
        assertTrue(compatibilityBlock.contains("case 2"))
        assertTrue(compatibilityBlock.contains("case 3"))
        assertTrue(compatibilityBlock.contains("case 4"))
        assertTrue(compatibilityBlock.contains("VK_COLOR_SPACE_HDR10_ST2084_EXT"))
        assertTrue(compatibilityBlock.contains("isEncoderHdrFallbackColorSpace(colorSpace)"))
        assertTrue(fallbackBlock.contains("VK_COLOR_SPACE_BT2020_LINEAR_EXT"))
        assertTrue(fallbackBlock.contains("VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT"))
        assertTrue(fallbackBlock.contains("VK_COLOR_SPACE_EXTENDED_SRGB_NONLINEAR_EXT"))
        assertTrue(fallbackBlock.contains("VK_COLOR_SPACE_SRGB_NONLINEAR_KHR"))
    }

    @Test
    fun nativeEncoderSurfacePathLogsSlowBindAndPresentStagesForFpsDiagnosis() {
        val root = repoRoot()
        val source = File(root, "app/src/main/cpp/vulkan_bridge.cpp").readText()
        val resourceBlock = source.substringAfter("bool createTargetSurfaceResources(")
            .substringBefore("bool bindSurfaceObject(")
        val submitBlock = source.substringAfter("bool recordAndSubmit(")
            .substringBefore("bool importHardwareBuffer(")

        assertTrue(source.contains("surfaceResourceStageStart"))
        assertTrue(resourceBlock.contains("logSurfaceResourceStage"))
        assertTrue(resourceBlock.contains("\"vkCreateSwapchainKHR\""))
        assertTrue(resourceBlock.contains("\"createComputeIntermediateResources\""))
        assertTrue(submitBlock.contains("logSlowSurfaceOperation"))
        assertTrue(submitBlock.contains("\"vkQueuePresentKHR\""))
    }

    @Test
    fun nativeGraphicsPipelineCreationUsesSharedPipelineCacheForEncoderPrewarm() {
        val root = repoRoot()
        val source = File(root, "app/src/main/cpp/vulkan_bridge.cpp").readText()
        val graphicsPipelineBlock = source.substringAfter("VkPipelineShaderStageCreateInfo vertexStage{}")
            .substringBefore("bool createGraphicsPipeline(")
        val createGraphicsPipelineBlock = source.substringAfter("bool createGraphicsPipeline(")
            .substringBefore("bool prewarmEncoderGraphicsPipeline(")
        val nativeCreateBlock = source.substringAfter("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeCreate")
            .substringBefore("extern \"C\" JNIEXPORT void JNICALL")
        val prewarmBlock = source.substringAfter("bool prewarmEncoderGraphicsPipeline(")
            .substringBefore("bool tryReuseCompatiblePreviewGraphicsPipeline(")

        assertTrue(source.contains("VkPipelineCache graphicsPipelineCache = VK_NULL_HANDLE"))
        assertTrue(source.contains("std::atomic_bool encoderPrewarmStarted{false}"))
        assertTrue(source.contains("std::thread encoderPrewarmThread"))
        assertTrue(source.contains("vkCreatePipelineCache"))
        assertTrue(source.contains("vkDestroyPipelineCache"))
        assertTrue(source.contains("void startEncoderGraphicsPipelinePrewarm(VulkanBridgeState* state)"))
        assertTrue(source.contains("state->encoderPrewarmThread = std::thread"))
        assertTrue(source.contains("prewarmEncoderGraphicsPipeline(state, format, prewarmPipelineCache)"))
        assertTrue(source.contains("VK_FORMAT_R8G8B8A8_UNORM"))
        assertTrue(source.contains("VK_FORMAT_B8G8R8A8_UNORM"))
        assertTrue(source.contains("prewarmed compatible graphics pipeline"))
        assertTrue(graphicsPipelineBlock.contains("pipelineCache"))
        assertTrue(createGraphicsPipelineBlock.contains("state->graphicsPipelineCache"))
        assertTrue(!graphicsPipelineBlock.contains("VK_NULL_HANDLE,\n        1,\n        &pipelineInfo"))
        assertTrue(prewarmBlock.contains("VkPipelineCache prewarmPipelineCache"))
        assertTrue(prewarmBlock.contains("vkCreatePipelineCache("))
        assertTrue(prewarmBlock.contains("\"vkCreatePipelineCache(encoder-prewarm)\""))
        assertTrue(prewarmBlock.contains("TargetSurface prewarmTarget{}"))
        assertTrue(prewarmBlock.contains("prewarmTarget.encoderTarget = true"))
        assertTrue(prewarmBlock.contains("prewarmTarget.colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR"))
        assertTrue(prewarmBlock.contains("createGraphicsPipelineWithCache(state, &prewarmTarget, prewarmPipelineCache)"))
        assertTrue(prewarmBlock.contains("vkDestroyPipeline(state->device, prewarmTarget.pipeline, nullptr)"))
        assertTrue(prewarmBlock.contains("vkDestroyRenderPass(state->device, prewarmTarget.renderPass, nullptr)"))
        assertTrue(source.contains("if (state->encoderPrewarmThread.joinable())"))
        assertTrue(source.contains("state->encoderPrewarmThread.join()"))
        assertTrue(nativeCreateBlock.indexOf("state->supported = true") <
            nativeCreateBlock.indexOf("Vulkan runtime initialized"))
        assertFalse(nativeCreateBlock.contains("startEncoderGraphicsPipelinePrewarm"))
    }

    @Test
    fun encoderGraphicsPipelineCreationFavorsFastRecordingStartupWithoutChangingShaderPath() {
        val root = repoRoot()
        val source = File(root, "app/src/main/cpp/vulkan_bridge.cpp").readText()
        val graphicsPipelineBlock = source.substringAfter("VkPipelineShaderStageCreateInfo vertexStage{}")
            .substringBefore("bool createGraphicsPipeline(")
        val createGraphicsPipelineBlock = source.substringAfter("bool createGraphicsPipeline(")
            .substringBefore("bool prewarmEncoderGraphicsPipeline(")

        assertTrue(graphicsPipelineBlock.contains("vertexStage.module = state->vertexShader"))
        assertTrue(graphicsPipelineBlock.contains("fragmentStage.module = state->fragmentShader"))
        assertTrue(graphicsPipelineBlock.contains("if (target->encoderTarget)"))
        assertTrue(graphicsPipelineBlock.contains("VK_PIPELINE_CREATE_DISABLE_OPTIMIZATION_BIT"))
        assertTrue(graphicsPipelineBlock.contains("pipelineCache"))
        assertTrue(createGraphicsPipelineBlock.contains("state->graphicsPipelineCache"))
    }

    @Test
    fun encoderCanReuseCompatiblePreviewGraphicsPipelineWithoutDoubleDestroyingIt() {
        val root = repoRoot()
        val source = File(root, "app/src/main/cpp/vulkan_bridge.cpp").readText()
        val destroyBlock = source.substringAfter("void destroySwapchainResources(")
            .substringBefore("void destroyTargetSurface(")
        val reuseBlock = source.substringAfter("bool tryReuseCompatiblePreviewGraphicsPipeline(")
            .substringBefore("bool createUniformBuffers(")
        val resourceBlock = source.substringAfter("bool createTargetSurfaceResources(")
            .substringBefore("bool bindSurfaceObject(")

        assertTrue(source.contains("bool ownsPipeline = true"))
        assertTrue(destroyBlock.contains("state->encoderTarget.pipeline == target->pipeline"))
        assertTrue(destroyBlock.contains("state->encoderTarget.ownsPipeline = true"))
        assertTrue(destroyBlock.contains("target->ownsPipeline = false"))
        assertTrue(destroyBlock.contains("target->pipeline != VK_NULL_HANDLE && target->ownsPipeline"))
        assertTrue(reuseBlock.contains("preview.valid"))
        assertTrue(reuseBlock.contains("preview.swapchainFormat != target->swapchainFormat"))
        assertTrue(reuseBlock.contains("preview.builtForHdrModeId != target->builtForHdrModeId"))
        assertTrue(reuseBlock.contains("target->pipeline = preview.pipeline"))
        assertTrue(reuseBlock.contains("target->ownsPipeline = false"))
        assertTrue(resourceBlock.indexOf("tryReuseCompatiblePreviewGraphicsPipeline") <
            resourceBlock.indexOf("createGraphicsPipeline"))
    }

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .filterNotNull()
            .first { File(it, "settings.gradle").isFile }
}
