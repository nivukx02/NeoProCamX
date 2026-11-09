package com.neoproc.neoproccamx.recording

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDevelopGpuNativeContractTest {

    @Test
    fun `offline raw develop uses persistent fenced readback and skips monitor outputs`() {
        val source = File("src/main/cpp/vulkan_bridge.cpp").readText()
        val entry = source.indexOf("nativeDevelopRawToAcescg")
        val end = source.indexOf("nativePollRawDemosaicCompletion", entry)
        assertTrue(entry >= 0 && end > entry)
        val function = source.substring(entry, end)

        assertTrue(function.contains("produceMonitorOutputs=*/false"))
        assertTrue(function.contains("kRawOfflineConversionTimeoutNs"))
        assertTrue(function.contains("copyRawDevelopOutputToHost"))
        assertTrue(function.contains("blackLevelRggb"))
        assertTrue(function.contains("lensShadingGridRggb"))
        assertTrue(function.contains("GetFloatArrayRegion(blackLevelRggb"))
        assertTrue(function.contains("GetFloatArrayRegion(lensShadingGridRggb"))
        assertTrue(function.contains("lensShadingCorrectionEnabled == JNI_TRUE"))
        assertFalse(function.contains("identityLensShadingGrid"))
        assertTrue(source.contains("developReadbackMapped"))
        assertTrue(source.contains("vkWaitForFences(rawDevelopReadback)"))
        assertFalse(function.contains("beginSingleUseCommands"))
        assertFalse(function.contains("vkQueueWaitIdle"))
    }

    @Test
    fun `mapped raw develop expands in native storage and has exact lease cleanup`() {
        val nativeSource = File("src/main/cpp/vulkan_bridge.cpp").readText()
        val bridgeSource = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt"
        ).readText()
        val acceleratorSource = File(
            "src/main/java/com/neoproc/neoproccamx/recording/RawDevelopGpuAccelerator.kt"
        ).readText()

        val begin = nativeSource.indexOf("nativeBeginDevelopRawToAcescgMapped")
        val end = nativeSource.indexOf("nativeEndDevelopRawToAcescgMapped", begin)
        assertTrue(begin >= 0 && end > begin)
        val mappedRoute = nativeSource.substring(begin, end)
        assertTrue(mappedRoute.contains("copyRawDevelopOutputToHost(state, width, height, floatRgbBytes)"))
        assertTrue(mappedRoute.contains("for (size_t p = pixelCount; p-- > 0u;)"))
        assertTrue(mappedRoute.contains("NewDirectByteBuffer"))
        assertTrue(mappedRoute.contains("SetLongArrayRegion"))
        assertTrue(bridgeSource.contains("finally"))
        assertTrue(bridgeSource.contains("nativeEndDevelopRawToAcescgMapped(handle)"))
        assertTrue(acceleratorSource.contains("RawDevelopGpuInputWorkspace"))
        assertTrue(acceleratorSource.contains("withDevelopedRawToAcescgMapped"))
    }

    @Test
    fun `persistent raw host buffers require coherent memory and clean partial initialization`() {
        val source = File("src/main/cpp/vulkan_bridge.cpp").readText()
        val start = source.indexOf("static bool ensureRawDevelopReadbackResources")
        val end = source.indexOf("static bool copyRawDevelopOutputToHost", start)
        assertTrue(start >= 0 && end > start)
        val ensure = source.substring(start, end)

        assertTrue(ensure.contains("requireExactProperties=*/true"))
        assertTrue(ensure.contains("releaseRawDevelopReadbackResources(state)"))
        assertTrue(source.contains("bool findExactMemoryType("))
        val runtime = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        ).readText()
        assertTrue(runtime.contains("RawDevelopGpuAccelerator.attach(null)"))
    }

    @Test
    fun `gpu p010 tail performs real detail colour and pack work with exact lease cleanup`() {
        val nativeSource = File("src/main/cpp/vulkan_bridge.cpp").readText()
        val bridgeSource = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt"
        ).readText()
        val converterSource = File(
            "src/main/java/com/neoproc/neoproccamx/recording/RawToNeoLogMovConverter.kt"
        ).readText()
        val detailShader = File("src/main/cpp/shaders/vulkan/raw_develop_detail.comp").readText()
        val blurShader = File("src/main/cpp/shaders/vulkan/raw_develop_luma_blur.comp").readText()
        val packShader = File("src/main/cpp/shaders/vulkan/raw_develop_p010_pack.comp").readText()

        assertTrue(nativeSource.contains("vkCmdBindPipeline"))
        assertTrue(nativeSource.contains("state->rawDevelopDetailPipeline"))
        assertTrue(nativeSource.contains("state->rawDevelopLumaBlurPipeline"))
        assertTrue(nativeSource.contains("state->rawDevelopP010Pipeline"))
        assertTrue(nativeSource.contains("VK_ACCESS_SHADER_WRITE_BIT"))
        assertTrue(nativeSource.contains("VK_ACCESS_HOST_READ_BIT"))
        assertTrue(nativeSource.contains("nativeBeginDevelopRawToP010Mapped"))
        assertTrue(nativeSource.contains("nativeEndDevelopRawToP010Mapped"))
        assertTrue(bridgeSource.contains("nativeEndDevelopRawToP010Mapped(handle)"))
        assertTrue(converterSource.contains("RawDevelopP010SampledParityPolicy.evaluate"))
        assertTrue(converterSource.contains("withDevelopedP010Direct"))
        assertTrue(detailShader.contains("rgba32f"))
        assertTrue(blurShader.contains("r32f"))
        assertTrue(packShader.contains("P010Output"))
        assertTrue(packShader.contains("deliveryMode"))
        assertFalse(packShader.contains("atomicAdd"))
    }

    @Test
    fun `gpu NIV99 owns accepted transformed frames and CPU NIV99 is only oracle or fallback`() {
        val converterSource = File(
            "src/main/java/com/neoproc/neoproccamx/recording/RawToNeoLogMovConverter.kt"
        ).readText()
        val acceleratorSource = File(
            "src/main/java/com/neoproc/neoproccamx/recording/RawDevelopGpuAccelerator.kt"
        ).readText()
        val route = converterSource
            .substringAfter("internal fun convertStreamDirectP010Timed(")
            .substringBefore("private sealed interface RawStage2Item")
        val readinessAdmission = route.indexOf("awaitRawConversionPipelineReady()")
        val directStart = route.indexOf("RawDevelopGpuAccelerator.withDevelopedP010Direct(")
        val fallbackStart = route.indexOf("if (!deliveredByGpu)")
        val fallbackCpuNiv99 = route.indexOf("niv99?.processOwned(", fallbackStart)

        assertTrue(route.contains("Niv99RawConversionEvidenceAccumulator(it.plan)"))
        assertTrue(readinessAdmission >= 0)
        assertTrue(directStart > readinessAdmission)
        assertTrue(fallbackStart > directStart)
        assertTrue(fallbackCpuNiv99 > fallbackStart)
        assertFalse(route.substring(0, directStart).contains("niv99?.processOwned("))
        assertTrue(route.contains("rawSamples = decoded.samples"))
        assertTrue(route.contains("niv99Plan = niv99?.plan"))
        assertTrue(route.contains("niv99Options = options.niv99RawOptions"))
        assertTrue(route.contains("frameNumber = index.toLong()"))
        assertTrue(route.contains("sensorTimestampNs = decoded.timestamp"))
        assertTrue(route.contains("directInvocation.niv99Evidence"))
        assertTrue(route.contains("niv99Evidence.addGpu("))
        assertTrue(route.contains("fallbackPrepared?.evidence?.let { niv99Evidence?.addCpu(it) }"))
        assertTrue(route.contains("niv99Evidence = niv99Evidence?.evidence()"))
        assertTrue(acceleratorSource.contains("activeBridge.isRawDemosaicPipelineReady()"))
        assertTrue(acceleratorSource.contains("Thread.currentThread().isInterrupted"))
        assertTrue(acceleratorSource.contains("LockSupport.parkNanos"))
        assertTrue(acceleratorSource.contains("RAW_CONVERSION_PIPELINE_READY_TIMEOUT_NANOS"))
    }

    @Test
    fun `raw NIV99 Vulkan prefilter is two pass temporally guarded and evidence bearing`() {
        val nativeSource = File("src/main/cpp/vulkan_bridge.cpp").readText()
        val shader = File("src/main/cpp/shaders/vulkan/raw_niv99_prefilter.comp").readText()
        val record = nativeSource
            .substringAfter("static bool recordRawNiv99Prefilter(")
            .substringBefore("bool dispatchRawDemosaicPreview(")
        val beginP010 = nativeSource
            .substringAfter("nativeBeginDevelopRawToP010Mapped(")
            .substringBefore("nativeEndDevelopRawToP010Mapped(")

        assertTrue(shader.contains("pass (0=spatial, 1=temporal)"))
        assertTrue(shader.contains("sceneCutDetected"))
        assertTrue(shader.contains("imageStore(filteredOutput"))
        assertTrue(record.split("vkCmdDispatch(").size - 1 == 2)
        assertTrue(record.contains("VK_ACCESS_SHADER_WRITE_BIT"))
        assertTrue(record.contains("VK_ACCESS_SHADER_READ_BIT"))
        assertTrue(record.contains("pendingNiv99HistoryCommit = true"))
        assertTrue(nativeSource.contains("commitPendingRawNiv99History(r)"))
        assertTrue(nativeSource.contains("abandonPendingRawNiv99History(r)"))
        assertTrue(beginP010.contains("env->GetArrayLength(timingNanos) < 12"))
        assertTrue(beginP010.contains("niv99Evidence.counters.processedSamples"))
        assertTrue(beginP010.contains("niv99Evidence.historyUsed ? 1 : 0"))
        assertTrue(beginP010.contains("niv99Evidence.historyReset ? 1 : 0"))
    }

    @Test
    fun `raw upload narrowing is one bounded native bulk operation with a correctness fallback`() {
        val nativeSource = File("src/main/cpp/vulkan_bridge.cpp").readText()
        val bridgeSource = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt"
        ).readText()
        val acceleratorSource = File(
            "src/main/java/com/neoproc/neoproccamx/recording/RawDevelopGpuAccelerator.kt"
        ).readText()

        val begin = nativeSource.indexOf("nativePackRawSamplesToU16")
        val end = nativeSource.indexOf("nativePollRawDemosaicCompletion", begin)
        assertTrue(begin >= 0 && end > begin)
        val nativePack = nativeSource.substring(begin, end)
        assertTrue(nativePack.contains("GetPrimitiveArrayCritical"))
        assertTrue(nativePack.contains("ReleasePrimitiveArrayCritical"))
        assertTrue(nativePack.contains("value >= 0xffff ? 0xffff"))
        assertTrue(bridgeSource.contains("nativePackRawSamplesToU16"))
        assertTrue(acceleratorSource.contains("activeBridge.packRawSamplesToU16"))
        assertTrue(acceleratorSource.contains("source[i].coerceIn(0, 0xFFFF).toShort()"))
    }

    @Test
    fun `slow raw teardown retains native ownership until deferred cleanup completes`() {
        val source = File("src/main/cpp/vulkan_bridge.cpp").readText()
        val helperStart = source.indexOf("VkResult waitDeviceIdleSynchronized")
        val helperEnd = source.indexOf("bool createImageComputePipeline", helperStart)
        val destroyStart = source.indexOf(
            "Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeDestroy"
        )
        val destroyEnd = source.indexOf(
            "Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeCleanupImportedFrames",
            destroyStart
        )

        assertTrue(source.contains("bool destroyState(VulkanBridgeState* state)"))
        assertTrue(helperStart >= 0 && helperEnd > helperStart)
        assertTrue(destroyStart >= 0 && destroyEnd > destroyStart)

        val helper = source.substring(helperStart, helperEnd)
        assertTrue(helper.contains("std::scoped_lock queueLocks(state->queueMutex, state->rawQueueMutex)"))
        assertTrue(helper.contains("return vkDeviceWaitIdle(state->device)"))

        val destroy = source.substring(destroyStart, destroyEnd)
        val ownershipTransfer = destroy.indexOf("VulkanBridgeState* deferredState = autoState.release()")
        val deferredOwner = destroy.indexOf(
            "std::unique_ptr<VulkanBridgeState> owner(deferredState)",
            ownershipTransfer
        )
        val synchronizedIdle = destroy.indexOf("waitDeviceIdleSynchronized(owner.get())", deferredOwner)
        val destroyRetry = destroy.indexOf("complete = destroyState(owner.get())", synchronizedIdle)
        assertTrue(ownershipTransfer >= 0)
        assertTrue(deferredOwner > ownershipTransfer)
        assertTrue(synchronizedIdle > deferredOwner)
        assertTrue(destroyRetry > synchronizedIdle)
    }
}
