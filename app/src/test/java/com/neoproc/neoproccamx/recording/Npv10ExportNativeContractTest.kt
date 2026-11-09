package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Npv10ExportNativeContractTest {

    @Test
    fun nativeP010ExportUsesPersistentMappedPingPongBuffersAndDirectByteBuffers() {
        val source = File("src/main/cpp/npv10_export_native.cpp").readText()
        val accelerator = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10ExportGpuAccelerator.kt").readText()

        assertTrue(source.contains("void* mapped = nullptr"))
        assertTrue(source.contains("vkMapMemory(state->device, target->memory, 0, VK_WHOLE_SIZE, 0, &target->mapped)"))
        assertTrue(source.contains("FrameSlot slots[2]"))
        assertTrue(source.contains("state.frameSerial++ & 1u"))
        assertTrue(source.contains("GetDirectBufferAddress"))
        assertTrue(source.contains("NewDirectByteBuffer"))
        assertTrue(source.contains("VkMemoryBarrier"))
        assertTrue(source.contains("srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT"))
        assertTrue(source.contains("dstAccessMask = VK_ACCESS_HOST_READ_BIT"))
        assertTrue(source.contains("ABR"))
        assertTrue(accelerator.contains("ByteBuffer.allocateDirect"))
        assertTrue(accelerator.contains("nativeEnhanceP010Direct"))
        assertTrue(accelerator.contains("ZERO_COPY_VULKAN_P010_DOUBLE_BUFFER"))
        assertTrue(accelerator.contains("PERSISTENT_MAPPED_HOST_COHERENT"))
        assertTrue(!source.contains("GetPrimitiveArrayCritical"))
        assertTrue(!source.contains("ReleasePrimitiveArrayCritical"))
    }

    @Test
    fun nativeP010ExportAcceptsTheSharedSubjectDetailExtendedUniformPacket() {
        val nativeSource = File("src/main/cpp/npv10_export_native.cpp").readText()
        val bridgeSource = File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt").readText()

        assertTrue(bridgeSource.contains("ENHANCEMENT_UNIFORM_FLOAT_COUNT = 44"))
        assertTrue(nativeSource.contains("kUniformFloatCount = 44u"))
    }

    @Test
    fun exportAndRecordDevelopPipelinesInitializeOnlyForTheirConsumer() {
        val source = File("src/main/cpp/npv10_export_native.cpp").readText()
        val converter = File(
            "src/main/java/com/neoproc/neoproccamx/recording/RecordDevelopMovConverter.kt"
        ).readText()

        assertTrue(source.contains("enum class Npv10PipelineRole"))
        assertTrue(source.contains("Npv10PipelineRole::EXPORT_ENHANCE"))
        assertTrue(source.contains("Npv10PipelineRole::RECORD_DEVELOP"))
        assertTrue(source.contains("initializeExportPipeline(state)"))
        assertTrue(source.contains("initializeRecordDevelopPipelines(state)"))
        assertTrue(source.contains("exportPipelineInitializationAttempted"))
        assertTrue(source.contains("recordDevelopPipelineInitializationAttempted"))
        assertTrue(converter.contains("usesDedicatedLiveParityEnhance"))
        assertTrue(converter.contains("if (!usesDedicatedLiveParityEnhance &&"))
    }

    @Test
    fun liveRecordingUsesDedicatedSourceHistoryWithExplicitResetEvidence() {
        val nativeSource = File("src/main/cpp/npv10_export_native.cpp").readText()
        val accelerator = File(
            "src/main/java/com/neoproc/neoproccamx/recording/Npv10ExportGpuAccelerator.kt"
        ).readText()
        val manager = File(
            "src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt"
        ).readText()

        assertTrue(nativeSource.contains("HostVisibleBuffer livePreviousBuffer"))
        assertTrue(nativeSource.contains("nativeEnhanceLiveP010InPlace"))
        assertTrue(nativeSource.contains("state.liveHistoryTimestampNs"))
        assertTrue(nativeSource.contains("state.liveHistorySessionGeneration"))
        assertTrue(nativeSource.contains("slot.currentBuffer.mapped"))
        assertTrue(nativeSource.contains("&state.livePreviousBuffer"))
        assertTrue(accelerator.contains("LiveP010EnhanceDisposition.TEMPORAL_USED"))
        assertTrue(manager.contains("maximumLiveP010HistoryGapNs"))
        assertTrue(manager.contains("liveP010QueuedTemporalFrames"))
        assertTrue(!manager.contains("previous = null,\n                    width = image.width"))
    }
}
