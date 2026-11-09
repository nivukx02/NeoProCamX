package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HdrMetadataComputePipelineContractTest {
    private val bridgeSource by lazy { File("src/main/cpp/vulkan_bridge.cpp").readText() }
    private val kotlinBridgeSource by lazy { File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt").readText() }

    @Test
    fun histogramShaderUses256BinsAndAtomicGpuAccumulation() {
        val source = File("src/main/cpp/shaders/hdr_metadata_histogram.comp").readText()

        assertTrue(source.contains("#version 450"))
        assertTrue(source.contains("layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;"))
        assertTrue(source.contains("const uint HISTOGRAM_BINS = 256u;"))
        assertTrue(source.contains("const float MAX_METADATA_NITS = 4000.0;"))
        assertTrue(source.contains("layout(set = 0, binding = 0, rgba16f) readonly uniform image2D uLinearLightImage;"))
        assertTrue(source.contains("layout(std430, set = 0, binding = 1) buffer HdrHistogramBuffer"))
        assertTrue(source.contains("uint bins[256];"))
        assertTrue(source.contains("uint sumBins;"))
        assertTrue(source.contains("uint pixelCount;"))
        assertTrue(source.contains("atomicAdd(histogram.bins[bin], 1u)"))
        assertTrue(source.contains("atomicAdd(histogram.sumBins, bin)"))
        assertTrue(source.contains("atomicAdd(histogram.pixelCount, 1u)"))
    }

    @Test
    fun reductionShaderComputesProjectOwnedMaxCllMaxFallAndClampsRanges() {
        val source = File("src/main/cpp/shaders/hdr_metadata_reduce.comp").readText()

        assertTrue(source.contains("#version 450"))
        assertTrue(source.contains("layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;"))
        assertTrue(source.contains("shared uint sharedCounts[256];"))
        assertTrue(source.contains("barrier()"))
        assertTrue(source.contains("const float MAX_METADATA_NITS = 4000.0;"))
        assertTrue(source.contains("const uint MIN_MAXCLL_NITS = 100u;"))
        assertTrue(source.contains("uint percentileTarget = max(1u, (totalPixels * 99u + 99u) / 100u);"))
        assertTrue(source.contains("metadata.maxCLL = clamp(nitsFromBin(percentileBin), MIN_MAXCLL_NITS, MAX_MAXCLL_NITS);"))
        assertTrue(source.contains("metadata.maxFALL = min(meanNits, metadata.maxCLL);"))
        assertTrue(source.contains("metadata.minLuminance = 0u;"))
        assertFalse(source.contains("Dolby"))
        assertFalse(source.contains("HDR10+"))
    }

    @Test
    fun metadataShadersHaveGeneratedSpirvArtifacts() {
        val histogramHeader = File("src/main/cpp/generated/hdr_metadata_histogram_comp_spv.h")
        val histogramSpv = File("src/main/cpp/generated/hdr_metadata_histogram.comp.spv")
        val reduceHeader = File("src/main/cpp/generated/hdr_metadata_reduce_comp_spv.h")
        val reduceSpv = File("src/main/cpp/generated/hdr_metadata_reduce.comp.spv")

        assertTrue(histogramSpv.isFile)
        assertTrue(histogramSpv.length() > 0L)
        assertTrue(histogramHeader.readText().contains("kHdrMetadataHistogramCompSpv"))
        assertTrue(reduceSpv.isFile)
        assertTrue(reduceSpv.length() > 0L)
        assertTrue(reduceHeader.readText().contains("kHdrMetadataReduceCompSpv"))
    }

    @Test
    fun nativeBridgeDispatchesGpuHistogramReduceAndAsyncReadbackRing() {
        assertTrue(bridgeSource.contains("#include \"generated/hdr_metadata_histogram_comp_spv.h\""))
        assertTrue(bridgeSource.contains("#include \"generated/hdr_metadata_reduce_comp_spv.h\""))
        assertTrue(bridgeSource.contains("constexpr uint32_t kHdrMetadataHistogramBins = 256u"))
        assertTrue(bridgeSource.contains("struct NeoHdr10Metadata"))
        assertTrue(bridgeSource.contains("std::array<NeoHdr10Metadata, kHdrMetadataRingSize> hdrMetadataRingBuffer"))
        assertTrue(bridgeSource.contains("VkPipeline hdrMetadataHistogramPipeline = VK_NULL_HANDLE"))
        assertTrue(bridgeSource.contains("VkPipeline hdrMetadataReducePipeline = VK_NULL_HANDLE"))
        assertTrue(bridgeSource.contains("vkCmdFillBuffer(commandBuffer, target->hdrHistogramBuffers[imageIndex]"))
        assertTrue(bridgeSource.contains("vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->hdrMetadataHistogramPipeline)"))
        assertTrue(bridgeSource.contains("vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->hdrMetadataReducePipeline)"))
        assertTrue(bridgeSource.contains("vkCmdCopyBuffer(commandBuffer, target->hdrMetadataBuffers[imageIndex], target->hdrMetadataStagingBuffers[imageIndex]"))
        assertTrue(bridgeSource.contains("updateHdrMetadataRingBufferFromStaging("))
        assertTrue(bridgeSource.contains("frame N+1"))
        assertTrue(bridgeSource.contains("hdrMetadataHistogramShaderWords="))
        assertTrue(bridgeSource.contains("hdrMetadataReduceShaderWords="))
        assertTrue(bridgeSource.contains("vendorHdr10PlusCertified=false"))
        assertTrue(bridgeSource.contains("vendorDolbyVisionCertified=false"))
    }

    @Test
    fun kotlinBridgeExposesProjectOwnedHdrMetadataWithoutVendorCertification() {
        assertTrue(kotlinBridgeSource.contains("data class HDR10Metadata"))
        assertTrue(kotlinBridgeSource.contains("val maxCLL: Int"))
        assertTrue(kotlinBridgeSource.contains("val maxFALL: Int"))
        assertTrue(kotlinBridgeSource.contains("val minLuminance: Int"))
        assertTrue(kotlinBridgeSource.contains("class HDRMetadataGenerator"))
        assertTrue(kotlinBridgeSource.contains("fun calculateMetadata"))
        assertTrue(kotlinBridgeSource.contains("fun latestHdrMetadata(): HDR10Metadata"))
        assertTrue(kotlinBridgeSource.contains("vendorHdr10PlusCertified") && kotlinBridgeSource.contains("false"))
        assertTrue(kotlinBridgeSource.contains("vendorDolbyVisionCertified") && kotlinBridgeSource.contains("false"))

        val metadata = HDRMetadataGenerator().calculateMetadata(IntArray(256) { bin ->
            when {
                bin < 32 -> 8
                bin < 240 -> 16
                else -> 2
            }
        })
        assertTrue(metadata.maxCLL in 100..4000)
        assertTrue(metadata.maxFALL <= metadata.maxCLL)
        assertEquals(0, metadata.minLuminance)
    }
}
