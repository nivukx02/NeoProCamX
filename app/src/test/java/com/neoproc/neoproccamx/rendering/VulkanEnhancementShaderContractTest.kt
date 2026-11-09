package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class VulkanEnhancementShaderContractTest {
    private val bridgeSource: String by lazy {
        File("src/main/cpp/vulkan_bridge.cpp").readText()
    }
    private val kotlinBridgeSource: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt").readText()
    }
    private val runtimeSource: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt").readText()
    }
    private val viewModelSource: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    }
    private val fragmentShaderSource: String by lazy {
        File("src/main/cpp/shaders/vulkan/ycbcr_preview.frag").readText()
    }

    @Test
    fun realtimeBoundedQualityIsSdrGatedAndNonSdrKeepsLegacyOrderAndStrengths() {
        val source = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp").readText()
        val gateBody = source.substringAfter("bool realtimeBoundedNormalProSdrQuality()")
            .substringBefore("bool realtimeStrictNormalProSdr()")
        val mainBody = source.substringAfter("void main()")
        val legacyBody = mainBody.substringAfter("if (!boundedNormalProSdrQuality) {")
            .substringBefore("return;")

        assertTrue(gateBody.contains("realtimeSignalFamilyId() == 1"))
        assertTrue(gateBody.contains("realtimeNormalProSdrTransport()"))
        assertTrue(source.contains("float base = clamp(max(params.spatialDenoise, realtimeLegacyQualityStrength())"))
        assertTrue(source.contains("float fused = niv99FuseStrength("))
        assertTrue(source.contains("return fused * ceiling;"))
        assertTrue(source.contains(": clamp(params.noiseMetric, 0.0, 10.0)"))
        assertTrue(source.contains("realtimeAuthority > 1.0 ? 100.0 : 10.0"))
        assertTrue(legacyBody.contains("RealtimeLumaNeighborhood lumaN = realtimeSample3x3Luma(uv, texel);"))
        assertTrue(legacyBody.contains("realtimeApexMicroTextureRecoverLegacy(lumaN, uv, detailed, texel"))
        assertTrue(legacyBody.contains("vec3 shadowClean = realtimeApexShadowNoiseFloor(lumaN, contrast"))
        assertTrue(legacyBody.indexOf("vec3 detailed =") < legacyBody.indexOf("vec3 processed ="))
        assertTrue(legacyBody.indexOf("vec3 processed =") < legacyBody.indexOf("vec3 contrast ="))
        assertTrue(legacyBody.indexOf("vec3 contrast =") < legacyBody.indexOf("vec3 shadowClean ="))
        assertTrue(legacyBody.contains("vec4(clampWidePreviewRgb(longDistanceDetail), 1.0)"))
        assertFalse(legacyBody.contains("realtimeBuildCleanLumaNeighborhood"))
        assertFalse(legacyBody.contains("realtimeApplyCumulativeDetailBudget"))
    }

    @Test
    fun p010BoundedQualityIsSdrGatedWhileNonSdrRetainsLegacyExportSemantics() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()
        val gateBody = source.substringAfter("bool p010BoundedNormalProSdrExport()")
            .substringBefore("float p010StrictNormalProSdrExportAuthority()")
        val temporalBody = source.substringAfter("uint motionCompensatedBilateralTemporalBlend(")
            .substringBefore("vec3 bt2020ToAcesCg(")
        val standardBody = source.substringAfter("uint processStandardCinemaPipeline(")
            .substringBefore("uint processNivukxApexPipeline(")

        assertTrue(gateBody.contains("signalFamily == SIGNAL_FAMILY_SDR"))
        assertTrue(gateBody.contains("activeTransport == ACTIVE_TRANSPORT_REC709_SDR"))
        assertTrue(gateBody.contains("activeTransport == ACTIVE_TRANSPORT_BT2020_SDR"))
        assertTrue(source.contains("return p010BoundedNormalProSdrExport()"))
        assertTrue(source.contains(": (strictVisibleCleanProfile ? 10.0 : 1.0)"))
        assertTrue(source.contains(": clamp(uniforms.aliasSafeMaxDetailDelta, 0.0, 1.0) * 1023.0"))
        assertTrue(temporalBody.contains("if (!p010BoundedNormalProSdrExport())"))
        assertTrue(temporalBody.contains("clamp(legacyWeight * bilateralWeight * boundaryGuard, 0.0, 1.0)"))
        assertTrue(standardBody.contains("if (p010BoundedNormalProSdrExport())"))
        assertTrue(standardBody.contains("applyCumulativeDetailBudget(lumaIndex, detailBase, processed)"))
    }

    @Test
    fun realtimeSdrEnhancementUsesNormalizedStrengthCleanDetailAndCumulativeGuards() {
        val source = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp").readText()
        val normalizationBody = source.substringAfter("float realtimeNormalizeStrength(")
            .substringBefore("bool realtimeNormalProSdrTransport()")
        val microBody = source.substringAfter("vec3 realtimeApexMicroTextureRecover(")
            .substringBefore("vec3 realtimeApexMicroTextureRecoverLegacy(")
        val longDistanceBody = source.substringAfter("vec3 realtimeApexLongDistanceDetail(")
            .substringBefore("vec3 realtimeApexLongDistanceDetailLegacy(")
        val mainBody = source.substringAfter("void main()")
            .substringAfter("RealtimeLumaNeighborhood rawLumaN = realtimeSample3x3Luma(uv, texel);")
        val boundedMainBody = mainBody.substringAfter(
            "RealtimeLumaNeighborhood rawLumaN = realtimeSample3x3Luma(uv, texel);"
        )

        assertTrue(source.contains("float realtimeNormalizeStrength(float rawStrength)"))
        assertTrue(normalizationBody.contains("rawStrength > 1.0 ? rawStrength * 0.1 : rawStrength"))
        assertFalse(normalizationBody.contains("? 10.0 : 1.0"))
        assertTrue(source.contains("RealtimeLumaNeighborhood realtimeBuildCleanLumaNeighborhood("))
        assertTrue(boundedMainBody.contains("RealtimeLumaNeighborhood cleanLumaN = realtimeBuildCleanLumaNeighborhood("))
        assertTrue(boundedMainBody.contains("vec3 detailBase = chromaClean;"))
        assertFalse(microBody.contains("sampleCameraRgb("))
        assertFalse(longDistanceBody.contains("sampleCameraRgb("))

        assertTrue(source.contains("float realtimeDetailNoiseConfidence("))
        assertTrue(source.contains("float realtimeSkinDetailGuard("))
        assertTrue(source.contains("vec3 realtimeApplyCumulativeDetailBudget("))
        assertTrue(source.contains("const float REALTIME_MAX_CUMULATIVE_DETAIL_DELTA = 0.036;"))
        assertTrue(boundedMainBody.contains("realtimeApplyCumulativeDetailBudget(detailBase, longDistanceDetail, cleanLumaN)"))
        assertTrue(source.contains("const float REALTIME_COMPUTE_ENHANCED_ALPHA = 0.625;"))
        assertTrue(
            boundedMainBody.contains(
                "vec4(clampWidePreviewRgb(guardedDetail), REALTIME_COMPUTE_ENHANCED_ALPHA)"
            )
        )
    }

    @Test
    fun p010EnhancementUsesMotionLimitedTemporalBlendAndCumulativeDetailBudget() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()
        val gateBody = source.substringAfter("bool p010BoundedNormalProSdrExport() {")
            .substringBefore("float p010StrictNormalProSdrExportAuthority()")
        val authorityBody = source.substringAfter("float p010StrictNormalProSdrExportAuthority() {")
            .substringBefore("bool p010ZeroVisibleGrainExport()")
        val temporalBody = source.substringAfter("uint motionCompensatedBilateralTemporalBlend(")
            .substringBefore("vec3 bt2020ToAcesCg(")
        val standardBody = source.substringAfter("uint processStandardCinemaPipeline(")
            .substringBefore("uint processNivukxApexPipeline(")

        assertTrue(source.contains("float p010NormalizeStrength(float rawStrength)"))
        assertTrue(gateBody.contains("signalFamily == SIGNAL_FAMILY_SDR"))
        assertTrue(gateBody.contains("activeTransport == ACTIVE_TRANSPORT_REC709_SDR"))
        assertTrue(gateBody.contains("activeTransport == ACTIVE_TRANSPORT_BT2020_SDR"))
        assertTrue(authorityBody.contains("return p010BoundedNormalProSdrExport() ? 1.0 : 0.0;"))
        assertFalse(authorityBody.contains("fineTextureDetailGain"))
        assertFalse(authorityBody.contains("mediumDetailGain"))
        assertFalse(authorityBody.contains("strongDetailGain"))
        assertFalse(authorityBody.contains("aliasSafeMaxDetailDelta"))
        assertFalse(authorityBody.contains(">= 9.5"))
        assertFalse(authorityBody.contains(">= 0.95"))
        assertTrue(source.contains("const float P010_MAX_TEMPORAL_PREVIOUS_WEIGHT = 0.72;"))
        assertTrue(source.contains("float p010TemporalMotionConfidence("))
        assertTrue(source.contains("float photometricConfidence = 1.0 - smoothstep("))
        assertTrue(source.contains("float flowConfidence = 1.0 - smoothstep(0.35, 2.25, flowMagnitude)"))
        assertTrue(source.contains("float timestampConfidence = 1.0 - smoothstep(0.001, 0.012, abs(uniforms.dualGainTimestampSkew))"))
        assertTrue(source.contains("return clamp(photometricConfidence * flowConfidence * timestampConfidence, 0.0, 1.0)"))
        assertTrue(temporalBody.contains("motionConfidence"))
        // Single ceiling (rank-19 fix): the caps are min'd together, not multiplied.
        assertTrue(temporalBody.contains("float cappedAlpha = min(min(baseAlpha, temporalCap), P010_MAX_TEMPORAL_PREVIOUS_WEIGHT)"))
        assertFalse(temporalBody.contains("min(baseAlpha, P010_MAX_TEMPORAL_PREVIOUS_WEIGHT) *"))
        assertTrue(temporalBody.contains("cappedAlpha * bilateralWeight"))
        assertTrue(temporalBody.contains("bilateralWeight *"))
        assertTrue(temporalBody.contains("boundaryGuard *"))
        assertTrue(temporalBody.contains("motionConfidence,"))
        assertTrue(temporalBody.contains("return blendSample(currentValue, previousValue, alphaTemp)"))
        assertFalse(temporalBody.contains("blendSample(currentValue, previousValue, 1.0)"))
        val boundedTemporalBody = temporalBody.substringAfter("float flowMagnitude =")
        assertFalse(boundedTemporalBody.contains("blendSample(currentValue, previousValue, 1.0)"))

        assertTrue(source.contains("const float P010_MAX_CUMULATIVE_DETAIL_CODES = 40.0;"))
        assertTrue(source.contains("uint applyCumulativeDetailBudget("))
        assertTrue(source.contains("float p010DetailNoiseConfidence("))
        assertTrue(source.contains("float p010SkinDetailGuard("))
        assertTrue(standardBody.contains("uint detailBase = processed;"))
        assertTrue(standardBody.contains("processed = applyCumulativeDetailBudget(lumaIndex, detailBase, processed);"))
    }

    @Test
    fun nativeBridgeStoresEnhancementUniformsAndReportsTruthfulCapabilitySummary() {
        assertTrue(bridgeSource.contains("constexpr size_t kEnhancementUniformFloatCount = 44"))
        assertTrue(bridgeSource.contains("struct EnhancementUniforms"))
        assertTrue(bridgeSource.contains("std::array<float, kEnhancementUniformFloatCount> values"))
        assertTrue(bridgeSource.contains("std::string signature"))
        assertTrue(bridgeSource.contains("bool ready = false"))
        assertTrue(bridgeSource.contains("EnhancementUniforms enhancementUniforms"))
        // Part A made this atomic (written by the background enhancement-build thread); still starts false.
        assertTrue(bridgeSource.contains("std::atomic_bool enhancementComputePipelineReady{false}"))
        assertTrue(bridgeSource.contains("std::string enhancementFallbackReason = \"compute pipeline not initialized\""))
        assertTrue(bridgeSource.contains("VkDescriptorSetLayout computeDescriptorSetLayout = VK_NULL_HANDLE"))
        assertTrue(bridgeSource.contains("VkPipelineLayout computePipelineLayout = VK_NULL_HANDLE"))
        assertTrue(bridgeSource.contains("VkPipeline computePipeline = VK_NULL_HANDLE"))
        assertTrue(bridgeSource.contains("VkShaderModule computeShaderModule = VK_NULL_HANDLE"))
        assertTrue(bridgeSource.contains("VK_FORMAT_R16G16B16A16_SFLOAT"))
        assertTrue(bridgeSource.contains("VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT"))
        assertTrue(bridgeSource.contains("VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT"))
        assertTrue(bridgeSource.contains("vkCreateComputePipelines"))
        assertTrue(bridgeSource.contains("vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->computePipeline)"))
        assertTrue(bridgeSource.contains("vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u)"))
        assertTrue(bridgeSource.contains("VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT"))
        assertTrue(bridgeSource.contains("VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT"))
        assertTrue(bridgeSource.contains("computeDispatchCount"))
        assertTrue(bridgeSource.contains("averageComputeDurationMs"))
        assertTrue(bridgeSource.contains("nativeUpdateEnhancementUniforms"))
        assertTrue(bridgeSource.contains("nativeGetEnhancementCapabilitySummary"))
        assertTrue(bridgeSource.contains("GetFloatArrayRegion"))
        assertTrue(bridgeSource.contains("std::isfinite"))
        assertTrue(bridgeSource.contains("isValidIntegralEnumId"))
        assertTrue(bridgeSource.contains("const double enumValue = static_cast<double>(value)"))
        assertTrue(bridgeSource.contains("enumValue < static_cast<double>(std::numeric_limits<int>::min())"))
        assertTrue(bridgeSource.contains("enumValue > static_cast<double>(std::numeric_limits<int>::max())"))
        assertTrue(bridgeSource.contains("std::trunc(enumValue) != enumValue"))
        assertTrue(bridgeSource.contains("kValidSignalFamilyIds{1, 2, 3, 4}"))
        assertTrue(bridgeSource.contains("kValidColorSpaceIds{1, 2, 3, 4, 5}"))
        assertTrue(bridgeSource.contains("kValidColorScienceIds{1, 10, 11, 12, 13, 20, 21, 30, 31}"))
        assertTrue(bridgeSource.contains("kValidActiveTransportIds{1, 2, 3, 4, 5}"))
        assertTrue(bridgeSource.contains("invalid enhancement enum id: signalFamily"))
        assertTrue(bridgeSource.contains("invalid enhancement enum id: colorSpace"))
        assertTrue(bridgeSource.contains("invalid enhancement enum id: colorScience"))
        assertTrue(bridgeSource.contains("invalid enhancement enum id: activeTransport"))
        assertTrue(bridgeSource.contains("enhancement uniforms rejected: Vulkan unsupported"))
        assertTrue(bridgeSource.contains("if (!state->supported)"))
        assertTrue(bridgeSource.contains("gpuEnhancement="))
        assertTrue(bridgeSource.contains("zeroCopyLiveImport="))
        assertTrue(bridgeSource.contains("npv10ExportPath="))
        assertTrue(bridgeSource.contains("enhancementUniforms="))
        assertTrue(bridgeSource.contains("enhancementSignature="))
        assertTrue(bridgeSource.contains("enhancementFallbackReason="))
        assertTrue(bridgeSource.contains("gpuEnhancement=contract-ready-fallback"))
        assertTrue(bridgeSource.contains("CPU_P010_FALLBACK_GPU_CONTRACT_READY"))
        assertTrue(bridgeSource.contains("nativeP010Compute=active-dispatched"))
        assertTrue(bridgeSource.contains("avgComputeMs="))
        assertTrue(bridgeSource.contains("totalDispatches="))
        assertTrue(bridgeSource.contains("intermediateFormat=RGBA16F"))
        assertTrue(bridgeSource.contains("barrierSync=PIPELINE_BARRIER_COMPUTE_TO_FRAGMENT"))
        assertTrue(bridgeSource.contains("VkSemaphoreTypeCreateInfo"))
        assertTrue(bridgeSource.contains("VK_SEMAPHORE_TYPE_TIMELINE"))
        assertTrue(bridgeSource.contains("VkTimelineSemaphoreSubmitInfo"))
        assertTrue(bridgeSource.contains("syncPolicy=VulkanTimelineSemaphores"))
        assertTrue(bridgeSource.contains("vkCmdWriteTimestamp"))
        assertTrue(bridgeSource.contains("timestampQueries=VulkanTimestampQuery"))
        assertTrue(bridgeSource.contains("lensWarping=ActiveAnamorphic"))
        assertTrue(bridgeSource.contains("cinematicHalation=IMAXEmissionPyramid"))
        assertFalse(bridgeSource.contains("ready-no-dispatch"))
        assertFalse(bridgeSource.contains("GPU_P010_COMPUTE"))
    }

    @Test
    fun nativeBridgeHasGeneratedRealtimeComputeShaderArtifact() {
        val shader = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp")
        val header = File("src/main/cpp/generated/realtime_preview_enhance_comp_spv.h")
        val spv = File("src/main/cpp/generated/realtime_preview_enhance.comp.spv")
        val shaderHash = MessageDigest.getInstance("SHA-256")
            .digest(shader.readBytes())
            .joinToString(separator = "") { "%02x".format(it) }

        assertTrue(shader.isFile)
        assertTrue(header.isFile)
        assertTrue(spv.isFile)
        assertTrue(spv.length() > 0L)
        val headerSource = header.readText()
        assertTrue(headerSource.contains("kRealtimePreviewEnhanceCompSpv"))
        assertTrue(headerSource.contains("// Source SHA-256: $shaderHash"))
    }

    @Test
    fun realtimeComputePathDoesNotDuplicateNdrToneWorkBeforeFragmentDelivery() {
        val realtimeGate = bridgeSource
            .substringAfter("bool shouldRunFullToneMetadataComputePass(")
            .substringBefore("void updateDescriptorSet(")

        assertTrue(realtimeGate.contains("return false;"))
        assertTrue(bridgeSource.contains("state->computeDispatchCount += 1u;"))
        assertTrue(bridgeSource.contains("state->computeDispatchCount += 5u;"))
        assertTrue(bridgeSource.contains("realtimeToFragmentBarrier"))
        assertTrue(bridgeSource.contains("The realtime fragment shader now owns NDR/NIVUKX highlight recovery"))
    }

    @Test
    fun ycbcrPreviewGeneratedSpirvContainsNdrColorStabilityFixes() {
        val shader = File("src/main/cpp/shaders/vulkan/ycbcr_preview.frag")
        val header = File("src/main/cpp/generated/ycbcr_preview_frag_spv.h")
        val spv = File("src/main/cpp/generated/ycbcr_preview.frag.spv")
        val headerSource = header.readText()
        val shaderHash = MessageDigest.getInstance("SHA-256")
            .digest(shader.readBytes())
            .joinToString(separator = "") { "%02x".format(it) }

        assertTrue(spv.isFile)
        assertTrue(spv.length() > 0L)
        assertTrue(headerSource.contains("kYcbcrPreviewFragSpv"))
        assertTrue(headerSource.contains("// Source SHA-256: $shaderHash"))
    }

    @Test
    fun generatedPreviewShaderHeadersMatchCheckedInSpirvByteForByte() {
        assertGeneratedSpirvHeaderMatchesBinary(
            header = File("src/main/cpp/generated/ycbcr_preview_frag_spv.h"),
            spv = File("src/main/cpp/generated/ycbcr_preview.frag.spv")
        )
        assertGeneratedSpirvHeaderMatchesBinary(
            header = File("src/main/cpp/generated/realtime_preview_enhance_comp_spv.h"),
            spv = File("src/main/cpp/generated/realtime_preview_enhance.comp.spv")
        )
    }

    @Test
    fun realtimePreviewComputeShaderPerformsQualityProcessingBeforeIntermediateStore() {
        val source = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp").readText()

        assertTrue(source.contains("heteroscedasticRealtimeNoiseVariance"))
        assertTrue(source.contains("realtimeBilateralDenoise"))
        assertTrue(source.contains("realtimeChromaSpeckleSuppress"))
        assertTrue(source.contains("realtimeEdgeAwareDetail"))
        assertTrue(source.contains("vec3 detailed = realtimeEdgeAwareDetail"))
        assertTrue(source.contains("vec3 processed = realtimeApexMicroTextureRecover"))
        assertFalse(source.contains("imageStore(uRealtimeIntermediate, coord, vec4(clampWidePreviewRgb(cameraRgb), 1.0));"))
        assertTrue(bridgeSource.contains("params.reserved = std::clamp("))
    }

    private fun assertGeneratedSpirvHeaderMatchesBinary(header: File, spv: File) {
        val words = Regex("0x([0-9a-fA-F]{8})u")
            .findAll(header.readText())
            .map { match -> match.groupValues[1].toLong(16).toInt() }
            .toList()
        val headerBytes = ByteBuffer
            .allocate(words.size * Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { words.forEach { putInt(it) } }
            .array()

        assertTrue(headerBytes.size >= Int.SIZE_BYTES)
        assertEqualsSpirvMagic(headerBytes)
        assertArrayEquals(spv.readBytes(), headerBytes)
    }

    private fun assertEqualsSpirvMagic(bytes: ByteArray) {
        val magic = ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        assertTrue(magic == 0x07230203)
    }

    @Test
    fun realtimePreviewComputeReceivesSeparatedNoiseAndDetailUniformLanes() {
        val source = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp").readText()

        assertTrue(bridgeSource.contains("float spatialDenoise = 1.0f"))
        assertTrue(bridgeSource.contains("float chromaDenoise = 1.0f"))
        assertTrue(bridgeSource.contains("float sharpening = 1.0f"))
        assertTrue(bridgeSource.contains("float noiseMetric = 1.0f"))
        assertFalse(bridgeSource.contains("float noiseMetric = 0.36f"))
        assertTrue(bridgeSource.contains("state->enhancementUniforms.values[5]"))
        assertTrue(bridgeSource.contains("state->enhancementUniforms.values[6]"))
        assertTrue(bridgeSource.contains("state->enhancementUniforms.values[8]"))
        assertTrue(bridgeSource.contains("state->enhancementUniforms.values[26]"))
        assertTrue(source.contains("float spatialDenoise;"))
        assertTrue(source.contains("float chromaDenoise;"))
        assertTrue(source.contains("float sharpening;"))
        assertTrue(source.contains("float noiseMetric;"))
        assertTrue(source.contains("float realtimeNormalizeStrength(float rawStrength)"))
        assertTrue(source.contains("return clamp(rawStrength > 1.0 ? rawStrength * 0.1 : rawStrength, 0.0, 1.0)"))
        assertTrue(source.contains("realtimeSpatialStrength()"))
        assertTrue(source.contains("realtimeChromaStrength()"))
        assertTrue(source.contains("realtimeDetailStrength()"))
        assertTrue(source.contains("requested * mix(0.28, 0.94, noiseShape)"))
        assertTrue(source.contains("requested * mix(0.42, 0.98, noiseShape)"))
        assertTrue(source.contains("float base = clamp(realtimeNormalizeStrength(params.sharpening), 0.0, 1.0)"))
        assertTrue(source.contains("return max(base, reconstruction);"))
        assertFalse(source.contains("max(realtimeNormalizeStrength(params.reserved), 0.88)"))
        assertTrue(source.contains("? realtimeNormalizeStrength(params.noiseMetric)"))
        assertTrue(source.contains(": clamp(params.noiseMetric, 0.0, 10.0)"))
        assertTrue(source.contains("for (int y = -2; y <= 2; ++y)"))
    }

    @Test
    fun realtimePreviewComputeUsesSelectedColorSpaceAndSdrExportDetailProfile() {
        val source = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp").readText()

        assertTrue(bridgeSource.contains("float colorGainR = 1.0f"))
        assertTrue(bridgeSource.contains("float colorGainG = 1.0f"))
        assertTrue(bridgeSource.contains("float colorGainB = 1.0f"))
        assertTrue(bridgeSource.contains("params.colorGainR = state->renderConfig.colorGain[0]"))
        assertTrue(bridgeSource.contains("params.colorGainG = state->renderConfig.colorGain[1]"))
        assertTrue(bridgeSource.contains("params.colorGainB = state->renderConfig.colorGain[2]"))
        assertTrue(bridgeSource.contains("float colorSpaceId = 1.0f"))
        assertTrue(bridgeSource.contains("float activeTransportId = 1.0f"))
        assertTrue(bridgeSource.contains("params.colorSpaceId = state->enhancementUniforms.values[1]"))
        assertTrue(bridgeSource.contains("params.activeTransportId = state->enhancementUniforms.values[3]"))
        assertTrue(bridgeSource.contains("state->enhancementUniforms.values[20]"))
        assertTrue(bridgeSource.contains("state->enhancementUniforms.values[21]"))
        assertTrue(bridgeSource.contains("state->enhancementUniforms.values[22]"))
        assertTrue(bridgeSource.contains("state->enhancementUniforms.values[27]"))
        assertTrue(source.contains("vec3 realtimeColorGain()"))
        assertTrue(source.contains("vec3 realtimeApplyNormalSdrCalibration"))
        assertTrue(source.contains("const vec3 kRealtimeRec709LumaWeights = vec3(0.2126, 0.7152, 0.0722)"))
        assertTrue(source.contains("const vec3 kRealtimeRec2020LumaWeights = vec3(0.2627, 0.6780, 0.0593)"))
        assertTrue(source.contains("bool realtimeWideGamutSignal()"))
        assertTrue(source.contains("return realtimeWideGamutSignal() ? kRealtimeRec2020LumaWeights : kRealtimeRec709LumaWeights"))
        assertTrue(source.contains("float realtimeProfileDetailGain()"))
        assertTrue(source.contains("? realtimeNormalizeStrength(params.profileDetailGain)"))
        assertTrue(source.contains(": clamp(params.profileDetailGain, 1.00, 10.0)"))
        assertTrue(source.contains("float realtimeChromaStability()"))
        assertTrue(source.contains("float realtimeChromaStabilityAuthority()"))
        assertTrue(source.contains("float realtimeNeutralChromaNoiseGate("))
        assertTrue(source.contains("? realtimeNormalizeStrength(params.chromaStability)"))
        assertTrue(source.contains(": clamp(params.chromaStability, 0.0, 10.0)"))
        assertFalse(source.contains("const vec3 kRealtimeLumaWeights = vec3(0.2126, 0.7152, 0.0722)"))
    }

    @Test
    fun realtimePreviewComputeReceivesSignalFamilyAndColorScienceIdentity() {
        val source = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp").readText()

        assertTrue(bridgeSource.contains("float signalFamilyId = 1.0f"))
        assertTrue(bridgeSource.contains("float colorScienceId = 1.0f"))
        assertTrue(bridgeSource.contains("params.signalFamilyId = state->enhancementUniforms.values[0]"))
        assertTrue(bridgeSource.contains("params.colorScienceId = state->enhancementUniforms.values[2]"))
        assertTrue(source.contains("float signalFamilyId;"))
        assertTrue(source.contains("float colorScienceId;"))
        assertTrue(source.contains("int realtimeSignalFamilyId()"))
        assertTrue(source.contains("int realtimeColorScienceId()"))
        assertTrue(source.contains("bool realtimeNeoHdrScience()"))
        assertTrue(source.contains("colorScience == 20 || colorScience == 21"))
        assertTrue(source.contains("bool realtimeLogSignal()"))
        assertTrue(source.contains("return realtimeSignalFamilyId() == 3 || colorScience == 10 || colorScience == 11 || colorScience == 12 || colorScience == 13"))
    }

    @Test
    fun realtimePreviewComputeUsesExplicitSdrTransportAndBoundedCleanupDetailControls() {
        val source = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp").readText()
        val normalSdrAuthority = source.substringAfter("bool realtimeStrictNormalProSdr()")
            .substringBefore("bool realtimeLegacyStrictNormalProSdr()")
        val legacyAuthority = source.substringAfter("bool realtimeLegacyStrictNormalProSdr()")
            .substringBefore("bool realtimeStrictEncodedTransport()")

        assertTrue(source.contains("float shotNoiseCoefficient = 0.00032"))
        assertTrue(source.contains("float readNoiseCoefficient = 0.000032"))
        assertTrue(source.contains("bool realtimeNormalProSdrTransport()"))
        assertTrue(source.contains("return activeTransport == 1 || activeTransport == 2"))
        assertTrue(source.contains("bool realtimeStrictNormalProSdr()"))
        assertTrue(normalSdrAuthority.contains("return realtimeBoundedNormalProSdrQuality();"))
        assertFalse(normalSdrAuthority.contains("params.spatialDenoise"))
        assertFalse(normalSdrAuthority.contains("params.chromaDenoise"))
        assertFalse(normalSdrAuthority.contains("params.sharpening"))
        assertFalse(normalSdrAuthority.contains("params.noiseMetric"))
        assertTrue(legacyAuthority.contains("params.spatialDenoise >= 9.5"))
        assertTrue(legacyAuthority.contains("params.chromaDenoise >= 9.5"))
        assertTrue(legacyAuthority.contains("params.sharpening >= 9.5"))
        assertTrue(legacyAuthority.contains("params.noiseMetric >= 9.5"))
        assertTrue(source.contains("bool realtimeStrictEncodedTransport()"))
        assertTrue(source.contains("return activeTransport == 3 || activeTransport == 4 || activeTransport == 5"))
        assertTrue(source.contains("bool realtimeStrictHighQualityEncodedSignal()"))
        assertTrue(source.contains("params.spatialDenoise >= 0.98"))
        assertTrue(source.contains("params.chromaDenoise >= 0.99"))
        assertTrue(source.contains("params.sharpening >= 0.90"))
        assertTrue(source.contains("params.noiseMetric >= 0.70"))
        assertTrue(source.contains("float realtimeStrictAuthority()"))
        assertTrue(source.contains("if (realtimeBoundedNormalProSdrQuality()) return 1.0;"))
        assertTrue(source.contains("return (realtimeLegacyStrictNormalProSdr() || realtimeStrictHighQualityEncodedSignal()) ? 10.0 : 1.0"))
        assertTrue(source.contains("float realtimeStrictCeiling()"))
        assertTrue(source.contains("return realtimeBoundedNormalProSdrQuality()"))
        assertTrue(source.contains(": (realtimeStrictAuthority() > 1.0 ? 10.0 : 1.0)"))
        assertTrue(source.contains("float noiseShape = realtimeNormalizeStrength(params.noiseMetric)"))
        assertTrue(source.contains("return clamp(0.14 + noiseShape * 0.66, 0.14, 0.80)"))
        assertFalse(source.contains("max(realtimeNormalizeStrength(params.reserved), 0.88)"))
        assertTrue(source.contains("float realtimeStrengthShape(float strictStrength)"))
        assertTrue(source.contains("float realtimeNoiseShape(float noisePressure)"))
        assertTrue(source.contains("? realtimeNormalizeStrength(strictStrength)"))
        assertTrue(source.contains("? realtimeNormalizeStrength(noisePressure)"))
        assertTrue(source.contains("float spatialShape = realtimeStrengthShape(spatialStrength)"))
        assertTrue(source.contains("float chromaShape = realtimeStrengthShape(chromaStrength)"))
        assertTrue(source.contains("float noiseShape = realtimeNoiseShape(noisePressure)"))
        assertTrue(source.contains("float realtimeDenoiseCenterAnchor(float noiseShape)"))
        assertTrue(source.contains("float realtimeStrictDetailNoiseReject(float noiseShape, float residualNoise, float localRange)"))
        assertTrue(source.contains("float impulseRatio = residualNoise / max(localRange, 0.004)"))
        assertTrue(source.contains("float impulseReject = 1.0 - smoothstep(0.16, 0.62, impulseRatio)"))
        assertTrue(source.contains("float extremeNoiseReject = 1.0 - smoothstep(0.44, 1.0, noiseShape) * 0.88"))
        assertTrue(source.contains("float flatNoiseReject = 1.0 - smoothstep(0.010, 0.030, residualNoise)"))
        assertTrue(source.contains("noiseVariance * mix(8.0, 44.0, spatialShape)"))
        assertFalse(source.contains("noiseVariance * mix(22.0, 64.0, spatialStrength)"))
        assertTrue(source.contains("float spatialWeight = exp(-dot(offset, offset) * mix(0.78, 0.34, spatialShape))"))
        assertFalse(source.contains("mix(0.78, 0.34, spatialStrength)"))
        assertTrue(source.contains("float centerAnchor = realtimeDenoiseCenterAnchor(noiseShape)"))
        assertTrue(source.contains("vec3 sumRgb = centerRgb * centerAnchor"))
        assertFalse(source.contains("vec3 sumRgb = centerRgb * 3.80"))
        assertTrue(source.contains("spatialShape * mix(0.18, 0.82, noiseShape)"))
        assertTrue(source.contains("chromaShape * mix(0.34, 0.92, noiseShape)"))
        assertFalse(source.contains("strictQuality ? 0.92 : 0.70"))
        assertTrue(source.contains("bool strictQuality = realtimeBoundedNormalProSdrQuality() || realtimeStrictAuthority() > 1.0"))
        assertTrue(source.contains("float edgePreserveScale = strictQuality ? 0.92 : 0.64"))
        assertTrue(source.contains("float strictNeutralSuppression = realtimeNeutralChromaNoiseGate(originalY, chromaEnergy, stabilityAuthority)"))
        assertTrue(source.contains("float chromaPreserve = strictQuality ? saturatedColorPreserve * 0.86 : saturatedColorPreserve"))
        assertTrue(source.contains("float realtimeStrictTexturelessGrainGate(float luma, float localRange, float residualNoise)"))
        assertTrue(source.contains("float profileLift = boundedNormalProSdrQuality"))
        assertTrue(source.contains("float realtimeAuthority = realtimeStrictAuthority()"))
        assertTrue(source.contains("float detailNoiseShape = realtimeNoiseShape(noisePressure)"))
        assertTrue(source.contains("float residualNoise = abs(n.y11 - cleanY)"))
        assertTrue(source.contains("float strictDetailReject = realtimeStrictDetailNoiseReject(detailNoiseShape, residualNoise, localRange)"))
        assertTrue(source.contains("float noiseGuard = (1.0 - smoothstep(0.52, 1.0, detailNoiseShape) * 0.18) * strictDetailReject"))
        assertFalse(source.contains("smoothstep(0.52, 1.0, noisePressure)"))
        assertTrue(source.contains("? clamp(detailStrength * mix(1.10, 2.40, profileLift), 0.0, 2.40)"))
        assertTrue(source.contains(": clamp(detailStrength * mix(1.92, 10.0, profileLift), 1.0"))
        assertTrue(source.contains("localRange * mix(0.140, 0.280, detailStrength * (0.82 + profileLift * 0.18))"))
        assertTrue(source.contains("float microNoiseShape = realtimeNoiseShape(noisePressure)"))
        assertTrue(source.contains("float motionlessRealtimeGate = (1.0 - smoothstep(0.72, 1.0, microNoiseShape) * 0.28) *"))
        assertTrue(source.contains("realtimeStrictDetailNoiseReject(microNoiseShape, abs(microTexture), localRange)"))
        assertFalse(source.contains("smoothstep(0.72, 1.0, noisePressure)"))
        assertTrue(source.contains("? mix(1.20, 2.60, profileLift) * mix(0.40, 1.0, detailStrength)"))
        assertTrue(source.contains("? max(0.0030, localRange * mix(0.16, 0.28, profileLift))"))
        assertTrue(source.contains("const float REALTIME_MAX_CUMULATIVE_DETAIL_DELTA = 0.036"))
        assertTrue(source.contains("vec3 realtimeApplyCumulativeDetailBudget("))
        assertTrue(source.contains("realtimeAuthority > 1.0 ? 100.0 : 10.0"))
    }

    @Test
    fun realtimePreviewComputeUsesSecondStageLumaOnlyMicroTextureRecovery() {
        val source = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp").readText()
        val microBody = source.substringAfter("vec3 realtimeApexMicroTextureRecover(")
            .substringBefore("void main()")
        val mainBody = source.substringAfter("void main()")
            .substringAfter("RealtimeLumaNeighborhood rawLumaN = realtimeSample3x3Luma(uv, texel);")

        assertTrue(source.contains("vec3 realtimeApexMicroTextureRecover("))
        assertTrue(microBody.contains("float axial1 ="))
        assertTrue(microBody.contains("float diag1 ="))
        assertTrue(microBody.contains("float axial2 ="))
        assertTrue(microBody.contains("float microLowPass ="))
        assertTrue(microBody.contains("float microTexture = centerY - microLowPass;"))
        assertTrue(microBody.contains("? mix(1.20, 2.60, profileLift) * mix(0.40, 1.0, detailStrength)"))
        assertTrue(microBody.contains("? max(0.0030, localRange * mix(0.16, 0.28, profileLift))"))
        assertTrue(microBody.contains("float limitedMicro = clamp("))
        assertTrue(microBody.contains("return max(detailedRgb + vec3(limitedMicro), vec3(0.0));"))
        assertFalse(microBody.contains("detailedRgb *"))
        assertTrue(mainBody.contains("vec3 detailed = realtimeEdgeAwareDetail("))
        assertTrue(mainBody.contains("vec3 processed = realtimeApexMicroTextureRecover("))
        assertTrue(
            mainBody.indexOf("vec3 detailed = realtimeEdgeAwareDetail(") <
                mainBody.indexOf("vec3 processed = realtimeApexMicroTextureRecover(")
        )
    }

    @Test
    fun realtimePreviewComputeUsesShadowNoiseFloorBeforeDetailRecovery() {
        val source = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp").readText()
        val mainBody = source.substringAfter("void main()")
            .substringAfter("RealtimeLumaNeighborhood rawLumaN = realtimeSample3x3Luma(uv, texel);")

        assertTrue(source.contains("vec3 realtimeApexShadowNoiseFloor("))
        assertTrue(source.contains("float legacyShadowNoiseGate = (1.0 - smoothstep(0.096, 0.360, centerY))"))
        assertTrue(source.contains("float strictShadowNoiseGate = max("))
        assertTrue(source.contains("realtimeStrictTexturelessGrainGate(centerY, localRange, abs(centerY - localMean))"))
        assertTrue(source.contains("bool strictQuality = realtimeBoundedNormalProSdrQuality() || realtimeAuthority > 1.0;"))
        assertTrue(source.contains("float shadowNoiseGate = strictQuality ? strictShadowNoiseGate : legacyShadowNoiseGate;"))
        assertTrue(source.contains("smoothstep(0.18, 0.88, noiseShape) * 0.74"))
        assertFalse(source.contains("float noiseFloorWeight = strictQuality ? 1.0 :"))
        assertTrue(source.contains("return max(mix(contrastRgb, vec3(localMean), noiseFloorWeight), vec3(0.0));"))
        assertTrue(mainBody.contains("vec3 shadowClean = realtimeApexShadowNoiseFloor("))
        assertTrue(mainBody.contains("vec3 detailed = realtimeEdgeAwareDetail("))
        assertTrue(mainBody.contains("vec3 processed = realtimeApexMicroTextureRecover("))
        assertTrue(mainBody.contains("vec3 contrast = realtimeApexLocalContrast("))
        assertTrue(mainBody.contains("vec3 highlightTexture = realtimeApexHighlightTexture("))
        assertTrue(mainBody.contains("vec3 edgeCoherent = realtimeApexEdgeCoherence("))
        assertTrue(mainBody.contains("vec3 tonalDensity = realtimeApexTonalDensity("))
        assertTrue(mainBody.indexOf("vec3 shadowClean = realtimeApexShadowNoiseFloor(") <
            mainBody.indexOf("vec3 detailed = realtimeEdgeAwareDetail("))
        assertTrue(mainBody.indexOf("vec3 detailed = realtimeEdgeAwareDetail(") <
            mainBody.indexOf("vec3 processed = realtimeApexMicroTextureRecover("))
        assertTrue(mainBody.indexOf("vec3 processed = realtimeApexMicroTextureRecover(") <
            mainBody.indexOf("vec3 contrast = realtimeApexLocalContrast("))
        assertTrue(mainBody.indexOf("vec3 contrast = realtimeApexLocalContrast(") <
            mainBody.indexOf("vec3 highlightTexture = realtimeApexHighlightTexture("))
        assertTrue(mainBody.indexOf("vec3 highlightTexture = realtimeApexHighlightTexture(") <
            mainBody.indexOf("vec3 edgeCoherent = realtimeApexEdgeCoherence("))
        assertTrue(mainBody.indexOf("vec3 edgeCoherent = realtimeApexEdgeCoherence(") <
            mainBody.indexOf("vec3 tonalDensity = realtimeApexTonalDensity("))
        assertTrue(mainBody.contains("vec4(clampWidePreviewRgb(guardedDetail), REALTIME_COMPUTE_ENHANCED_ALPHA)"))
    }

    @Test
    fun p010EnhancementShaderUsesV6ShadowNoiseFloorAfterLocalContrast() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()
        val shadowBody = source.substringAfter("uint applyApexShadowNoiseFloorLuma(")
            .substringBefore("uint applyApexHighlightTextureLuma(")
        val standardBody = source.substringAfter("uint processStandardCinemaPipeline(")
            .substringBefore("uint processNivukxApexPipeline(")

        assertTrue(source.contains("uint applyApexShadowNoiseFloorLuma(uint sampleIndex, uint contrastValue)"))
        assertTrue(shadowBody.contains("float legacyShadowNoiseGate = (1.0 - smoothstep(96.0, 360.0, c))"))
        assertTrue(shadowBody.contains("float strictShadowNoiseGate = max("))
        assertTrue(shadowBody.contains("p010StrictTexturelessGrainGate(c, localRange, abs(c - localMean))"))
        assertTrue(shadowBody.contains("bool strictVisibleCleanExport = p010BoundedNormalProSdrExport() ||"))
        assertTrue(shadowBody.contains("p010StrictVisibleCleanExportAuthority() > 1.0;"))
        assertTrue(shadowBody.contains("float shadowNoiseGate = strictVisibleCleanExport ? strictShadowNoiseGate : legacyShadowNoiseGate;"))
        assertTrue(shadowBody.contains("float noiseFloorWeight = strictVisibleCleanExport ? 1.0 :"))
        assertTrue(shadowBody.contains("float candidate = mix(float(contrastValue), localMean, noiseFloorWeight);"))
        assertTrue(standardBody.contains("processed = applyApexLocalContrastLuma(lumaIndex, processed);"))
        assertTrue(standardBody.contains("processed = applyApexShadowNoiseFloorLuma(lumaIndex, processed);"))
        assertTrue(standardBody.indexOf("processed = applyApexLocalContrastLuma(lumaIndex, processed);") <
            standardBody.indexOf("processed = applyApexShadowNoiseFloorLuma(lumaIndex, processed);"))
    }

    @Test
    fun realtimePreviewComputeUsesV7HighlightTextureAfterShadowNoiseFloor() {
        val source = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp").readText()
        val mainBody = source.substringAfter("void main()")
            .substringAfter("RealtimeLumaNeighborhood rawLumaN = realtimeSample3x3Luma(uv, texel);")

        assertTrue(source.contains("vec3 realtimeApexHighlightTexture("))
        assertTrue(source.contains("float highlightTextureGate = smoothstep(0.600, 0.880, centerY)"))
        assertTrue(source.contains("? 1.0 + detailStrength * 0.55"))
        assertTrue(source.contains("? max(0.004, localRange * mix(0.14, 0.24, realtimeAuthority))"))
        assertTrue(source.contains(": (realtimeAuthority > 1.0 ? 10.0 : 1.0 + detailStrength * 0.24)"))
        assertTrue(source.contains("float headroomGuard = highlightDetail > 0.0 ?"))
        assertTrue(source.contains("return max(shadowCleanRgb + vec3(limitedHighlight), vec3(0.0));"))
        assertTrue(mainBody.contains("vec3 shadowClean = realtimeApexShadowNoiseFloor("))
        assertTrue(mainBody.contains("vec3 highlightTexture = realtimeApexHighlightTexture("))
        assertTrue(mainBody.indexOf("vec3 shadowClean = realtimeApexShadowNoiseFloor(") <
            mainBody.indexOf("vec3 highlightTexture = realtimeApexHighlightTexture("))
        assertTrue(mainBody.contains("vec3 edgeCoherent = realtimeApexEdgeCoherence("))
        assertTrue(mainBody.indexOf("vec3 highlightTexture = realtimeApexHighlightTexture(") <
            mainBody.indexOf("vec3 edgeCoherent = realtimeApexEdgeCoherence("))
        assertTrue(mainBody.contains("vec3 tonalDensity = realtimeApexTonalDensity("))
        assertTrue(mainBody.indexOf("vec3 edgeCoherent = realtimeApexEdgeCoherence(") <
            mainBody.indexOf("vec3 tonalDensity = realtimeApexTonalDensity("))
        assertTrue(mainBody.contains("vec4(clampWidePreviewRgb(guardedDetail), REALTIME_COMPUTE_ENHANCED_ALPHA)"))
    }

    @Test
    fun p010EnhancementShaderUsesV7HighlightTextureAfterShadowNoiseFloor() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()
        val highlightBody = source.substringAfter("uint applyApexHighlightTextureLuma(")
            .substringBefore("uint applyApexEdgeCoherenceLuma(")
        val standardBody = source.substringAfter("uint processStandardCinemaPipeline(")
            .substringBefore("uint processNivukxApexPipeline(")

        assertTrue(source.contains("uint applyApexHighlightTextureLuma(uint sampleIndex, uint shadowCleanValue)"))
        assertTrue(highlightBody.contains("float highlightTextureGate = smoothstep(600.0, 880.0, c)"))
        assertTrue(highlightBody.contains("? 1.0 + 0.55 * profileLift"))
        assertTrue(highlightBody.contains(": 1.0 + 4.0 * profileLift"))
        assertTrue(highlightBody.contains("? max(4.0, localRange * 0.24)"))
        assertTrue(highlightBody.contains(": max(4.0, localRange * 0.18)"))
        assertTrue(highlightBody.contains("float headroomGuard = highlightDetail > 0.0 ?"))
        assertTrue(highlightBody.contains("float candidate = clamp(float(shadowCleanValue) + limitedHighlight, 0.0, 1023.0);"))
        assertTrue(standardBody.contains("processed = applyApexShadowNoiseFloorLuma(lumaIndex, processed);"))
        assertTrue(standardBody.contains("processed = applyApexHighlightTextureLuma(lumaIndex, processed);"))
        assertTrue(standardBody.indexOf("processed = applyApexShadowNoiseFloorLuma(lumaIndex, processed);") <
            standardBody.indexOf("processed = applyApexHighlightTextureLuma(lumaIndex, processed);"))
    }

    @Test
    fun realtimePreviewComputeUsesV8EdgeCoherenceAfterHighlightTexture() {
        val source = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp").readText()
        val mainBody = source.substringAfter("void main()")
            .substringAfter("RealtimeLumaNeighborhood rawLumaN = realtimeSample3x3Luma(uv, texel);")

        assertTrue(source.contains("vec3 realtimeApexEdgeCoherence("))
        assertTrue(source.contains("float edgeCoherenceGate = smoothstep(0.018, 0.096, abs(gx) + abs(gy))"))
        assertTrue(source.contains("? 1.0 + detailStrength * 0.60"))
        assertTrue(source.contains("? max(0.004, localRange * mix(0.16, 0.26, realtimeAuthority))"))
        assertTrue(source.contains(": (realtimeAuthority > 1.0 ? 10.0 : 1.0 + detailStrength * 0.24)"))
        assertTrue(source.contains("float haloSafe = clamp(candidate, max(localMin - edgeLimit, 0.0), min(localMax + edgeLimit, 1.25));"))
        assertTrue(source.contains("return max(edgeRgb + vec3(haloSafe - currentY), vec3(0.0));"))
        assertTrue(mainBody.contains("vec3 highlightTexture = realtimeApexHighlightTexture("))
        assertTrue(mainBody.contains("vec3 edgeCoherent = realtimeApexEdgeCoherence("))
        assertTrue(mainBody.indexOf("vec3 highlightTexture = realtimeApexHighlightTexture(") <
            mainBody.indexOf("vec3 edgeCoherent = realtimeApexEdgeCoherence("))
        assertTrue(mainBody.contains("vec3 tonalDensity = realtimeApexTonalDensity("))
        assertTrue(mainBody.indexOf("vec3 edgeCoherent = realtimeApexEdgeCoherence(") <
            mainBody.indexOf("vec3 tonalDensity = realtimeApexTonalDensity("))
        assertTrue(mainBody.contains("vec4(clampWidePreviewRgb(guardedDetail), REALTIME_COMPUTE_ENHANCED_ALPHA)"))
    }

    @Test
    fun p010EnhancementShaderUsesV8EdgeCoherenceAfterHighlightTexture() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()
        val coherenceBody = source.substringAfter("uint applyApexEdgeCoherenceLuma(")
            .substringBefore("const int BLUE_NOISE_8X8")
        val standardBody = source.substringAfter("uint processStandardCinemaPipeline(")
            .substringBefore("uint processNivukxApexPipeline(")

        assertTrue(source.contains("uint applyApexEdgeCoherenceLuma(uint sampleIndex, uint highlightedValue)"))
        assertTrue(coherenceBody.contains("float edgeCoherenceGate = smoothstep(18.0, 96.0, abs(gx) + abs(gy))"))
        assertTrue(coherenceBody.contains("? 1.0 + 0.60 * profileLift"))
        assertTrue(coherenceBody.contains(": 1.0 + 4.0 * profileLift"))
        assertTrue(coherenceBody.contains("? max(4.0, localRange * 0.26)"))
        assertTrue(coherenceBody.contains(": max(4.0, localRange * 0.20)"))
        assertTrue(coherenceBody.contains("float haloSafe = clamp(candidate, max(localMin - edgeLimit, 0.0), min(localMax + edgeLimit, 1023.0));"))
        assertTrue(coherenceBody.contains("return uint(clamp(haloSafe, 0.0, 1023.0));"))
        assertTrue(standardBody.contains("processed = applyApexHighlightTextureLuma(lumaIndex, processed);"))
        assertTrue(standardBody.contains("processed = applyApexEdgeCoherenceLuma(lumaIndex, processed);"))
        assertTrue(standardBody.indexOf("processed = applyApexHighlightTextureLuma(lumaIndex, processed);") <
            standardBody.indexOf("processed = applyApexEdgeCoherenceLuma(lumaIndex, processed);"))
    }

    @Test
    fun realtimePreviewComputeUsesV9TonalDensityAfterEdgeCoherence() {
        val source = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp").readText()
        val mainBody = source.substringAfter("void main()")
            .substringAfter("RealtimeLumaNeighborhood rawLumaN = realtimeSample3x3Luma(uv, texel);")

        assertTrue(source.contains("vec3 realtimeApexTonalDensity("))
        assertTrue(source.contains("float flatMidtoneGate = (1.0 - smoothstep(0.096, 0.260, localRange))"))
        assertTrue(source.contains("? 1.0 + detailStrength * 0.35"))
        assertTrue(source.contains("? max(0.002, localRange * mix(0.06, 0.10, realtimeAuthority))"))
        assertTrue(source.contains(": (realtimeAuthority > 1.0 ? 10.0 : 1.0 + detailStrength * 0.18)"))
        assertTrue(source.contains("float densityDelta = normalized * (1.0 - abs(normalized)) * densityGain"))
        assertTrue(source.contains("float isolatedDetailGuard = 1.0 - smoothstep(0.006, 0.018, isolatedBrightDetail);"))
        assertTrue(source.contains("float positiveDensityGuard = densityDelta > 0.0 ? isolatedDetailGuard : 1.0;"))
        assertTrue(source.contains("return max(densityRgb + vec3(densitySafe - currentY), vec3(0.0));"))
        assertTrue(mainBody.contains("vec3 edgeCoherent = realtimeApexEdgeCoherence("))
        assertTrue(mainBody.contains("vec3 tonalDensity = realtimeApexTonalDensity("))
        assertTrue(mainBody.indexOf("vec3 edgeCoherent = realtimeApexEdgeCoherence(") <
            mainBody.indexOf("vec3 tonalDensity = realtimeApexTonalDensity("))
        assertTrue(mainBody.contains("vec4(clampWidePreviewRgb(guardedDetail), REALTIME_COMPUTE_ENHANCED_ALPHA)"))
    }

    @Test
    fun realtimePreviewComputeUsesV10LongDistanceDetailAfterTonalDensity() {
        val source = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp").readText()
        val mainBody = source.substringAfter("void main()")
            .substringAfter("RealtimeLumaNeighborhood rawLumaN = realtimeSample3x3Luma(uv, texel);")

        assertTrue(source.contains("vec3 realtimeApexLongDistanceDetail("))
        assertTrue(source.contains("float nearMean = (n.y11 * 4.0 + axial1 * 1.0 + diagonal1 * 0.75) / 11.0;"))
        assertTrue(source.contains("float farMean = (n.y11 * 4.0 + axial1 * 1.0 + diagonal1 * 0.66 + axial2 * 0.50) / 12.64;"))
        assertTrue(source.contains("float wideMean = (n.y11 * 4.0 + axial1 * 0.82 + diagonal1 * 0.56 + axial2 * 0.44 + axial3 * 0.30) / 13.92;"))
        assertTrue(source.contains("float longDistanceStructure = mix("))
        assertTrue(source.contains("? 1.0 + detailStrength * 1.40"))
        assertTrue(source.contains("? max(0.002, localRange * 0.18)"))
        assertTrue(source.contains(": (realtimeAuthority > 1.0 ? 14.0 : 1.0 + detailStrength * 4.0)"))
        assertTrue(source.contains("float noiseReject = clamp("))
        assertTrue(source.contains("float longDistanceNoiseShape = realtimeNoiseShape(noisePressure);"))
        assertTrue(source.contains("float strictStructureGuard = boundedNormalProSdrQuality"))
        assertTrue(source.contains("min(noiseGuard, 0.72 + wideAgreement * 0.18)"))
        assertTrue(source.contains("realtimeStrictDetailNoiseReject(longDistanceNoiseShape, singlePixelNoise, localRange)"))
        assertTrue(mainBody.contains("vec3 tonalDensity = realtimeApexTonalDensity("))
        assertTrue(mainBody.contains("vec3 longDistanceDetail = realtimeApexLongDistanceDetail("))
        assertTrue(mainBody.indexOf("vec3 tonalDensity = realtimeApexTonalDensity(") <
            mainBody.indexOf("vec3 longDistanceDetail = realtimeApexLongDistanceDetail("))
        assertTrue(mainBody.contains("vec4(clampWidePreviewRgb(guardedDetail), REALTIME_COMPUTE_ENHANCED_ALPHA)"))
    }

    @Test
    fun p010EnhancementShaderUsesV9TonalDensityAfterEdgeCoherence() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()
        val densityBody = source.substringAfter("uint applyApexTonalDensityLuma(")
            .substringBefore("const int BLUE_NOISE_8X8")
        val standardBody = source.substringAfter("uint processStandardCinemaPipeline(")
            .substringBefore("uint processNivukxApexPipeline(")

        assertTrue(source.contains("uint applyApexTonalDensityLuma(uint sampleIndex, uint edgeValue)"))
        assertTrue(densityBody.contains("float flatMidtoneGate = (1.0 - smoothstep(96.0, 260.0, localRange))"))
        assertTrue(densityBody.contains("? 1.0 + 0.35 * profileLift"))
        assertTrue(densityBody.contains(": 1.0 + 3.0 * profileLift"))
        assertTrue(densityBody.contains("? max(2.0, localRange * 0.10)"))
        assertTrue(densityBody.contains(": max(2.0, localRange * 0.06)"))
        assertTrue(densityBody.contains("float densityDelta = normalized * (1.0 - abs(normalized)) * densityGain"))
        assertTrue(densityBody.contains("float isolatedDetailGuard = 1.0 - smoothstep(6.0, 18.0, isolatedBrightDetail);"))
        assertTrue(densityBody.contains("float positiveDensityGuard = densityDelta > 0.0 ? isolatedDetailGuard : 1.0;"))
        assertTrue(densityBody.contains("return uint(clamp(densitySafe, 0.0, 1023.0));"))
        assertTrue(standardBody.contains("processed = applyApexEdgeCoherenceLuma(lumaIndex, processed);"))
        assertTrue(standardBody.contains("processed = applyApexTonalDensityLuma(lumaIndex, processed);"))
        assertTrue(standardBody.indexOf("processed = applyApexEdgeCoherenceLuma(lumaIndex, processed);") <
            standardBody.indexOf("processed = applyApexTonalDensityLuma(lumaIndex, processed);"))
    }

    @Test
    fun p010EnhancementShaderUsesV10LongDistanceDetailAfterTonalDensity() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()
        val longDistanceBody = source.substringAfter("uint applyApexLongDistanceDetailLuma(")
            .substringBefore("const int BLUE_NOISE_8X8")
        val standardBody = source.substringAfter("uint processStandardCinemaPipeline(")
            .substringBefore("uint processNivukxApexPipeline(")

        assertTrue(source.contains("uint applyApexLongDistanceDetailLuma(uint sampleIndex, uint tonalValue)"))
        assertTrue(longDistanceBody.contains("float nearMean = (c * 4.0 + axial1 * 1.0 + diagonal1 * 0.75) / 11.0;"))
        assertTrue(longDistanceBody.contains("float farMean = (c * 4.0 + axial1 * 1.0 + diagonal1 * 0.66 + axial2 * 0.50) / 12.64;"))
        assertTrue(longDistanceBody.contains("bool hasWideRing = row >= 3u"))
        assertTrue(longDistanceBody.contains("float wideMean = hasWideRing ?"))
        assertTrue(longDistanceBody.contains("float longDistanceStructure = mix("))
        assertTrue(longDistanceBody.contains("? 1.0 + 1.40 * profileLift"))
        assertTrue(longDistanceBody.contains(": 1.0 + 4.0 * profileLift"))
        assertTrue(longDistanceBody.contains("? max(2.0, localRange * 0.18)"))
        assertTrue(longDistanceBody.contains(": max(2.0, localRange * 0.10)"))
        assertTrue(longDistanceBody.contains("float noiseReject = clamp("))
        assertTrue(longDistanceBody.contains("return uint(clamp(safeDetail, 0.0, 1023.0));"))
        assertTrue(standardBody.contains("processed = applyApexTonalDensityLuma(lumaIndex, processed);"))
        assertTrue(standardBody.contains("processed = applyApexLongDistanceDetailLuma(lumaIndex, processed);"))
        assertTrue(standardBody.indexOf("processed = applyApexTonalDensityLuma(lumaIndex, processed);") <
            standardBody.indexOf("processed = applyApexLongDistanceDetailLuma(lumaIndex, processed);"))
    }

    @Test
    fun realtimePreviewComputeSharesLumaNeighborhoodAcrossDetailStagesForFpsSafety() {
        val source = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp").readText()
        val cumulativeBody = source.substringAfter("vec3 realtimeApplyCumulativeDetailBudget(")
            .substringBefore("vec3 realtimeBilateralDenoise(")
        val mainBody = source.substringAfter("void main()")
        val detailStagesBody = mainBody
            .substringAfter(
                "RealtimeLumaNeighborhood cleanLumaN = realtimeBuildCleanLumaNeighborhood(rawLumaN, chromaClean, noisePressure);"
            )
            .substringBefore("imageStore(")

        assertTrue(source.contains("struct RealtimeLumaNeighborhood"))
        assertTrue(source.contains("RealtimeLumaNeighborhood realtimeSample3x3Luma("))
        assertTrue(source.contains("RealtimeLumaNeighborhood realtimeBuildCleanLumaNeighborhood("))
        assertTrue(
            mainBody.split(
                "RealtimeLumaNeighborhood rawLumaN = realtimeSample3x3Luma(uv, texel);"
            ).size - 1 == 1
        )
        assertTrue(
            mainBody.split(
                "RealtimeLumaNeighborhood cleanLumaN = realtimeBuildCleanLumaNeighborhood(rawLumaN, chromaClean, noisePressure);"
            ).size - 1 == 1
        )

        assertTrue(detailStagesBody.contains("vec3 shadowClean = realtimeApexShadowNoiseFloor(cleanLumaN, detailBase, noisePressure);"))
        assertTrue(detailStagesBody.contains("vec3 detailed = realtimeEdgeAwareDetail(cleanLumaN, shadowClean, detailStrength, noisePressure);"))
        assertTrue(detailStagesBody.contains("vec3 processed = realtimeApexMicroTextureRecover(cleanLumaN, detailed, detailStrength, noisePressure);"))
        assertTrue(detailStagesBody.contains("vec3 contrast = realtimeApexLocalContrast(cleanLumaN, processed, detailStrength, noisePressure);"))
        assertTrue(detailStagesBody.contains("vec3 highlightTexture = realtimeApexHighlightTexture(cleanLumaN, contrast, detailStrength, noisePressure);"))
        assertTrue(detailStagesBody.contains("vec3 edgeCoherent = realtimeApexEdgeCoherence(cleanLumaN, highlightTexture, detailStrength, noisePressure);"))
        assertTrue(detailStagesBody.contains("vec3 tonalDensity = realtimeApexTonalDensity(cleanLumaN, edgeCoherent, detailStrength, noisePressure);"))
        assertTrue(detailStagesBody.contains("vec3 longDistanceDetail = realtimeApexLongDistanceDetail(cleanLumaN, tonalDensity, detailStrength, noisePressure);"))
        assertTrue(detailStagesBody.contains("vec3 guardedDetail = realtimeApplyCumulativeDetailBudget(detailBase, longDistanceDetail, cleanLumaN);"))

        assertFalse(detailStagesBody.contains("sampleCameraRgb("))
        assertFalse(detailStagesBody.contains("uv,"))
        assertFalse(detailStagesBody.contains("texel"))
        assertFalse(cumulativeBody.contains("sampleCameraRgb("))
        assertFalse(cumulativeBody.contains("vec2 uv"))
        assertFalse(cumulativeBody.contains("vec2 texel"))
    }

    @Test
    fun kotlinBridgeExposesParsedEnhancementDispatchTelemetry() {
        assertTrue(kotlinBridgeSource.contains("data class VulkanEnhancementTelemetry"))
        assertTrue(kotlinBridgeSource.contains("val nativeP010Compute: String"))
        assertTrue(kotlinBridgeSource.contains("val averageComputeMs: Double"))
        assertTrue(kotlinBridgeSource.contains("val totalDispatches: Long"))
        assertTrue(kotlinBridgeSource.contains("val intermediateFormat: String"))
        assertTrue(kotlinBridgeSource.contains("val barrierSync: String"))
        assertTrue(kotlinBridgeSource.contains("val lensWarping: String"))
        assertTrue(kotlinBridgeSource.contains("val cinematicHalation: String"))
        assertTrue(kotlinBridgeSource.contains("val syncPolicy: String"))
        assertTrue(kotlinBridgeSource.contains("val timestampQueries: String"))
        assertTrue(kotlinBridgeSource.contains("val computeDispatched: Boolean"))
        assertTrue(kotlinBridgeSource.contains("fun parseEnhancementTelemetry(summary: String): VulkanEnhancementTelemetry"))
        assertTrue(kotlinBridgeSource.contains("fun enhancementTelemetry(): VulkanEnhancementTelemetry"))
    }

    @Test
    fun fragmentDeliveryConsumesSceneQualityUniformsInsteadOfFixedNearTotalSmoothing() {
        assertTrue(bridgeSource.contains("float enhancement[4]"))
        assertTrue(bridgeSource.contains("float subjectDetail[4]"))
        assertTrue(bridgeSource.contains("state->enhancementUniforms.values[5]"))
        assertTrue(bridgeSource.contains("state->enhancementUniforms.values[6]"))
        assertTrue(bridgeSource.contains("state->enhancementUniforms.values[8]"))
        assertTrue(bridgeSource.contains("state->enhancementUniforms.values[40]"))
        assertTrue(bridgeSource.contains("state->enhancementUniforms.values[43]"))
        assertTrue(bridgeSource.contains("const std::array<float, 4> fallbackEnhancement{1.0f, 1.0f, 1.0f, 1.0f}"))
        assertTrue(fragmentShaderSource.contains("vec4 uEnhance0"))
        assertTrue(fragmentShaderSource.contains("vec4 uSubjectDetail"))
        assertTrue(fragmentShaderSource.contains("float normalizeEnhancementStrength(float rawStrength)"))
        assertTrue(fragmentShaderSource.contains("float plannedSpatialDenoise"))
        assertTrue(fragmentShaderSource.contains("float plannedChromaDenoise"))
        assertTrue(fragmentShaderSource.contains("float plannedSpatialDenoise = normalProSdrQualityIntent"))
        assertTrue(fragmentShaderSource.contains("? max(normalizeEnhancementStrength(uEnhance0.x), 0.28)"))
        assertTrue(fragmentShaderSource.contains(": clamp(uEnhance0.x, 0.28, 10.0)"))
        assertTrue(fragmentShaderSource.contains("float plannedChromaDenoise = normalProSdrQualityIntent"))
        assertTrue(fragmentShaderSource.contains("? max(normalizeEnhancementStrength(uEnhance0.y), 0.50)"))
        assertTrue(fragmentShaderSource.contains(": clamp(uEnhance0.y, 0.50, 10.0)"))
        assertTrue(fragmentShaderSource.contains("float measuredNoise = normalProSdrQualityIntent"))
        assertTrue(fragmentShaderSource.contains("? normalizeEnhancementStrength(uEnhance0.w)"))
        assertTrue(fragmentShaderSource.contains(": clamp(uEnhance0.w, 0.0, 10.0)"))
        assertTrue(fragmentShaderSource.contains("float plannedSharpening = normalProSdrQualityIntent"))
        assertTrue(fragmentShaderSource.contains("? normalizeEnhancementStrength(uEnhance0.z)"))
        assertTrue(fragmentShaderSource.contains(": clamp(uEnhance0.z, 0.0, 10.0)"))
        assertTrue(fragmentShaderSource.contains("float strictDenoiseCeiling = 1.0"))
        assertTrue(fragmentShaderSource.contains("float subjectAlpha = texture(uSubjectDetailMask"))
        assertTrue(fragmentShaderSource.contains("float gatedDetailGain"))
        assertFalse(fragmentShaderSource.contains("logDelivery ? 0.998"))
        assertFalse(fragmentShaderSource.contains("rawDelivery ? 0.999"))
    }

    @Test
    fun ndrAndNivukxTonePathUsesBt2020LumaAndExplicitSkySkinGuards() {
        val ndrBody = fragmentShaderSource
            .substringAfter("vec3 naturalDynamicRangeToneMap(")
            .substringBefore("const mat3 NEO_WIDE_GAMUT_TO_XYZ")

        assertTrue(
            "NDR/NIVUKX scene-linear HDR decisions must use BT.2020 luminance. Rec.709 luma " +
                "underweights red and overweights green, which caused the red/orange skin and " +
                "cyan sky failure shown in the screenshots.",
            fragmentShaderSource.contains("float ndrSceneLuma(vec3 color)")
        )
        assertTrue(ndrBody.contains("float sourceY = max(ndrSceneLuma(source), 1e-5)"))
        assertTrue(ndrBody.contains("float mappedLuma = max(ndrSceneLuma(mapped), 1e-5)"))
        assertTrue(ndrBody.contains("mapped = ndrSkyHighlightChromaGuard(mapped, nivukxVision);"))
        assertTrue(ndrBody.contains("mapped = ndrSkinToneProtect(mapped);"))
        assertFalse(ndrBody.contains("luminance(source)"))
        assertFalse(ndrBody.contains("luminance(mapped)"))
    }

    @Test
    fun ndrAndNivukxTonePathAppliesPixelLocalHdrRecoveryBeforeDisplayClamp() {
        val ndrBody = fragmentShaderSource
            .substringAfter("vec3 naturalDynamicRangeToneMap(")
            .substringBefore("const mat3 NEO_WIDE_GAMUT_TO_XYZ")

        assertTrue(fragmentShaderSource.contains("vec3 ndrExposureMasks("))
        assertTrue(fragmentShaderSource.contains("float ndrLocalSceneLuma(vec2 uv, float fallbackY)"))
        assertTrue(fragmentShaderSource.contains("vec3 ndrTexturePreservingNoiseClean("))
        assertTrue(fragmentShaderSource.contains("vec3 ndrPixelAdaptiveHdrBalance("))
        assertTrue(fragmentShaderSource.contains("vec3 naturalDynamicRangeToneMap(vec2 uv, vec3 rgb"))
        assertTrue(ndrBody.contains("mapped = ndrPixelAdaptiveHdrBalance("))
        assertTrue(
            "Local HDR recovery must happen before the final display clamp, otherwise highlight " +
                "detail and shadow texture are already lost.",
            ndrBody.indexOf("mapped = ndrPixelAdaptiveHdrBalance(") <
                ndrBody.indexOf("return rawLinearToDisplayNdrTransform")
        )
        assertTrue(fragmentShaderSource.contains("naturalDynamicRangeToneMap(transformed, linear"))
        assertFalse(fragmentShaderSource.contains("naturalDynamicRangeToneMap(linear,"))
    }

    @Test
    fun ndrAndNivukxTonePathBypassesPreviewColorGainBeforeHdrMath() {
        val mainSetup = fragmentShaderSource
            .substringAfter("void main()")
            .substringBefore("float contrast = uGrade0.x;")
        val gradedSampleBody = fragmentShaderSource
            .substringAfter("vec3 gradedCameraSample(")
            .substringBefore("vec3 decodeCameraSample(")

        assertTrue(mainSetup.contains("float earlyToneDeliveryMode = uGrade3.x"))
        assertTrue(mainSetup.contains("bool earlyNdrOrNivukxPath"))
        assertTrue(mainSetup.contains("bool earlyRawMonitor"))
        assertTrue(mainSetup.contains("float earlyColorGainBlend = (earlyNdrOrNivukxPath || earlyRawMonitor)"))
        assertTrue(mainSetup.contains("clamp(uColorGain.a * 18.0, 0.0, 1.0)"))
        assertTrue(mainSetup.contains("earlyColorGainBlend"))
        assertTrue(gradedSampleBody.contains("bool sampleNdrOrNivukxPath"))
        assertTrue(gradedSampleBody.contains("float sampleColorGainBlend = sampleNdrOrNivukxPath ? 0.0"))
    }

    @Test
    fun ndrAndNivukxTonePathUsesLumaPreservingRedOverflowGuard() {
        val ndrBody = fragmentShaderSource
            .substringAfter("vec3 naturalDynamicRangeToneMap(")
            .substringBefore("const mat3 NEO_WIDE_GAMUT_TO_XYZ")
        val redGuardBody = fragmentShaderSource
            .substringAfter("vec3 ndrRedChannelOverflowGuard(")
            .substringBefore("vec3 ndrSkinToneProtect(")

        assertTrue(fragmentShaderSource.contains("vec3 ndrRedChannelOverflowGuard(vec3 rgb, bool nivukxVision)"))
        assertTrue(redGuardBody.contains("float redGreenRatio = source.r / max(source.g, 1e-5)"))
        assertTrue(redGuardBody.contains("float redLumaRatio = source.r / max(y, 1e-5)"))
        assertTrue(redGuardBody.contains("smoothstep(1.10, 1.42, redGreenRatio)"))
        assertTrue(redGuardBody.contains("smoothstep(1.16, 1.55, redLumaRatio)"))
        assertTrue(redGuardBody.contains("min(target.r, max(target.g * 1.14, y * 1.06))"))
        assertTrue(redGuardBody.contains("target * (y / targetY)"))
        assertTrue(ndrBody.contains("source = ndrRedChannelOverflowGuard(source, nivukxVision);"))
        assertTrue(ndrBody.contains("mapped = ndrRedChannelOverflowGuard(mapped, nivukxVision);"))
        assertTrue(
            ndrBody.indexOf("source = ndrRedChannelOverflowGuard(source, nivukxVision);") <
                ndrBody.indexOf("float sourceY = max(ndrSceneLuma(source), 1e-5)")
        )
    }

    @Test
    fun bt2100SkinGuardCapsRedDominanceBeforeDisplayEncode() {
        val body = fragmentShaderSource
            .substringAfter("vec3 bt2100SkinToneRedGuard(")
            .substringBefore("// HDR Delivery Logic")

        assertTrue(body.contains("smoothstep(1.24, 1.66, redGreenRatio)"))
        assertTrue(body.contains("adjusted.g * 1.30"))
        assertTrue(body.contains("adjusted.g * 0.78"))
        assertTrue(body.contains("0.38"))
        assertFalse(body.contains("adjusted.g * 1.42"))
    }

    @Test
    fun subjectDetailMaskIsUploadedClearedAndBoundOnlyThroughExplicitBridgeRouting() {
        assertTrue(kotlinBridgeSource.contains("fun updateSubjectDetailMask("))
        assertTrue(kotlinBridgeSource.contains("nativeUpdateSubjectDetailMask"))
        assertTrue(runtimeSource.contains("fun updateSubjectDetailMask("))
        assertTrue(runtimeSource.contains("subjectDetailMaskAccepted = updateSubjectDetailMask("))
        assertTrue(viewModelSource.contains("subjectDetailMask = subjectDetailMask"))
        assertFalse(viewModelSource.contains("vulkanRuntime.updateSubjectDetailMask(subjectDetailMask)"))
        assertTrue(bridgeSource.contains("struct SubjectDetailMaskTexture"))
        assertTrue(bridgeSource.contains("VK_FORMAT_R8_UNORM"))
        assertTrue(bridgeSource.contains("createOrUpdateSubjectDetailMask"))
        assertTrue(bridgeSource.contains("nativeUpdateSubjectDetailMask"))
        assertTrue(fragmentShaderSource.contains("layout(binding = 3) uniform sampler2D uSubjectDetailMask"))
    }

    @Test
    fun unchangedSubjectMaskDoesNotForceRepeatedNativeTextureUploads() {
        val updateBody = runtimeSource
            .substringAfter("fun updateSubjectDetailMask(")
            .substringBefore("fun updateFocusMap(")
        val unchangedGuard = updateBody.indexOf(
            "if (signature == lastSubjectDetailMaskSignature) {"
        )
        val acceptedEarlyReturn = updateBody.indexOf(
            "return@withCurrentRenderProfileGeneration true",
            startIndex = unchangedGuard.coerceAtLeast(0)
        )
        val nativeUpload = updateBody.indexOf(
            "bridge.updateSubjectDetailMask(",
            startIndex = acceptedEarlyReturn.coerceAtLeast(0)
        )

        assertTrue(unchangedGuard >= 0)
        assertTrue(acceptedEarlyReturn > unchangedGuard)
        assertTrue(nativeUpload > acceptedEarlyReturn)
        assertTrue(runtimeSource.contains(".alphaMask.contentHashCode()"))
    }

    @Test
    fun replacingASubjectMaskWaitsUntilExistingFragmentReadsAreFinished() {
        val deviceIdleBody = bridgeSource
            .substringAfter("VkResult waitDeviceIdleSynchronized(")
            .substringBefore("bool createImageComputePipeline(")
        val maskUploadBody = bridgeSource
            .substringAfter("bool createOrUpdateSubjectDetailMask(")
            .substringBefore("ImportedFrame* currentImportedFrame")

        assertTrue(deviceIdleBody.contains("std::lock_guard<std::mutex> queueLock(state->queueMutex)"))
        assertTrue(deviceIdleBody.contains("return vkDeviceWaitIdle(state->device)"))
        assertTrue(maskUploadBody.contains("waitDeviceIdleSynchronized(state)"))
        assertTrue(
            maskUploadBody.indexOf("waitDeviceIdleSynchronized(state)") <
                maskUploadBody.indexOf("destroySubjectDetailMask(state)")
        )
    }

    @Test
    fun p010EnhancementShaderContainsRequiredProcessingOrderAndUniforms() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()

        assertTrue(source.contains("layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;"))
        assertTrue(source.contains("layout(std430, set = 0, binding = 0) readonly buffer CurrentP010"))
        assertTrue(source.contains("layout(std430, set = 0, binding = 1) readonly buffer PreviousP010"))
        assertTrue(source.contains("layout(std430, set = 0, binding = 2) buffer OutputP010"))
        assertTrue(source.contains("layout(std430, set = 0, binding = 3) readonly buffer UniformBlock"))
        assertTrue(source.contains("struct EnhancementUniforms"))
        assertTrue(source.indexOf("applyTemporalDenoise") < source.indexOf("applyChromaDeadband"))
        assertTrue(source.indexOf("applyChromaDeadband") < source.indexOf("applyLocalChromaSpikeSmoothing"))
        assertTrue(source.indexOf("applyLocalChromaSpikeSmoothing") < source.indexOf("applyEdgeAwareDetailLift"))
        assertTrue(source.contains("aliasSafeMaxDetailDelta"))
        assertTrue(source.contains("shadowChromaDeadband"))
        assertTrue(source.contains("temporalLumaBlend"))
        assertTrue(source.contains("dualGainFusionEnabled"))
        assertTrue(source.contains("dualGainMotionMaskEnabled"))
        assertTrue(source.contains("dualGainKGain"))
        assertTrue(source.contains("dualGainNoiseFloor"))
        assertTrue(source.contains("dualGainSaturationThreshold"))
        assertTrue(source.contains("dualGainTimestampSkew"))
        assertTrue(source.contains("shotNoiseCoefficient"))
        assertTrue(source.contains("readNoiseCoefficient"))
        assertTrue(source.contains("heteroscedasticNoiseVariance"))
        assertTrue(source.contains("bayesShrinkThreshold"))
        assertTrue(source.contains("bayesianWaveletShrink"))
        assertTrue(source.contains("applyBlueNoiseDither"))
        assertTrue(source.contains("PHASE_SHIFTED_FILM_GRAIN"))
        assertTrue(source.contains("applyPhaseShiftedFilmGrain"))
        assertTrue(source.contains("applyWarmHighlightHalation"))
        assertTrue(source.contains("applyPlanckBlueNoiseJndDither"))
        assertTrue(source.contains("planckBlueNoiseJndThreshold"))
        assertTrue(source.contains("applyFitzHughNagumoChemicalHalation"))
        assertTrue(source.contains("applyPlanckGrain"))
        assertTrue(source.contains("highEntropyPlanckBlueNoiseTexture"))
        assertTrue(source.contains("jndShapedQuantizationError"))
        assertTrue(source.contains("bakeCosmicSingularityExportLook"))
        assertTrue(source.contains("processNivukxApexPipeline"))
        assertTrue(source.contains("processNeoAcesHighEndPipeline"))
        assertTrue(source.contains("processCosmicSingularityPipeline"))
        assertTrue(source.contains("processStandardCinemaPipeline"))
        assertTrue(source.contains("COLOR_SCIENCE_NEO_ACES_HIGH_END"))
        assertTrue(source.contains("COLOR_SPACE_NEO_ACES_AP1"))
        assertTrue(source.contains("COLOR_SPACE_NEO_ACES_AP0"))
        assertTrue(source.contains("bool neoAcesAp0 = int(uniforms.colorSpace + 0.5) == COLOR_SPACE_NEO_ACES_AP0"))
        assertTrue(source.contains("if (int(uniforms.colorScience + 0.5) == COLOR_SCIENCE_NIVUKX_VISION)"))
        assertTrue(source.contains("else if (neoAcesColorSpace || int(uniforms.colorScience + 0.5) == COLOR_SCIENCE_NEO_ACES_HIGH_END)"))
        assertTrue(source.contains("else if (int(uniforms.colorScience + 0.5) == COLOR_SCIENCE_COSMIC_SINGULARITY"))
        assertTrue(source.contains("motionCompensatedBilateralTemporalBlend"))
        assertTrue(source.contains("bt2020ToAcesCg"))
        assertTrue(source.contains("acesHighlightRollOff"))
        assertTrue(source.contains("finalJndBlueNoiseQuantize"))
        assertTrue(source.contains("abrBackpressureScale"))
        assertTrue(source.contains("abrDetailGainScale"))
        assertTrue(source.contains("abrDitherAmplitudeScale"))
        assertTrue(source.contains("const float JND_EXPORT_DITHER_VISIBILITY_SCALE = 0.28"))
        assertTrue(source.contains("return highEntropyBlue * jnd * abrDitherAmplitudeScale() * JND_EXPORT_DITHER_VISIBILITY_SCALE;"))
        assertFalse(source.contains("return highEntropyBlue * jnd * abrDitherAmplitudeScale();"))
        assertTrue(source.contains("p010NormalizeStrength(uniforms.strongDetailGain) * abrDetailGainScale()"))
        assertTrue(source.contains("p010NormalizeStrength(uniforms.mediumDetailGain) * abrDetailGainScale()"))
        assertTrue(source.contains("p010NormalizeStrength(uniforms.fineTextureDetailGain) * abrDetailGainScale()"))
        assertTrue(source.contains("computedNeighborPeak"))
        val mainBody = source.substringAfter("void main()")
        assertFalse(mainBody.contains("yValue = applyBlueNoiseDither"))
        assertFalse(mainBody.contains("yValue = applyPhaseShiftedFilmGrain"))
        assertFalse(mainBody.contains("yValue = applyPlanckBlueNoiseJndDither"))
        assertFalse(mainBody.contains("yValue = applyPlanckGrain"))
        assertFalse(mainBody.contains("yValue = bakeCosmicSingularityExportLook"))
        assertTrue(source.contains("OMNIPOTENT_NPV10_EXPORT_PIPELINE"))
        assertTrue(source.contains("ZENITH_COSMIC_PLANCK_NPV10_EXPORT_PIPELINE"))
        assertFalse(source.contains("applyTpdfDither"))
    }

    @Test
    fun p010EnhancementShaderUsesPacked16BitSampleLaneHelpers() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()

        assertTrue(source.contains("const uint kP010LaneMask = 0xFFFFu;"))
        assertTrue(source.contains("uint readCurrentP010Sample(uint sampleIndex)"))
        assertTrue(source.contains("uint readPreviousP010Sample(uint sampleIndex)"))
        assertTrue(source.contains("uint unpackPackedP010Lane(uint packedWord, uint sampleIndex)"))
        assertTrue(source.contains("void writePackedP010Sample(uint sampleIndex, uint sampleValue)"))
        assertTrue(source.contains("uint wordIndex = sampleIndex >> 1u;"))
        assertTrue(source.contains("return (sampleIndex & 1u) * 16u;"))
        assertTrue(source.contains("return (packedWord >> packedLaneShift(sampleIndex)) & kP010LaneMask;"))
        assertTrue(source.contains("uint laneMask = kP010LaneMask << shift;"))
        assertTrue(source.contains("uint preservedWord = oldWord & ~laneMask;"))
        assertTrue(source.contains("uint newWord = preservedWord | ((packP010(sampleValue) & kP010LaneMask) << shift);"))
        assertTrue(source.contains("uint observedWord = atomicCompSwap(outputData[wordIndex], oldWord, newWord);"))
        assertTrue(source.contains("readCurrentP010Sample(lumaIndex)"))
        assertTrue(source.contains("readPreviousP010Sample(sampleIndex)"))
        assertTrue(source.contains("writePackedP010Sample(lumaIndex, yValue)"))
        assertTrue(source.contains("writePackedP010Sample(chromaPairIndex, uValue)"))
        assertTrue(source.contains("writePackedP010Sample(chromaPairIndex + 1u, vValue)"))
        assertFalse(source.contains("uint readPackedP010Sample(uint packedData[]"))
        assertFalse(source.contains("unpackP010(currentData[sampleIndex"))
        assertFalse(source.contains("unpackP010(previousData[sampleIndex"))
        assertFalse(source.contains("outputData[lumaIndex] = packP010"))
    }

    @Test
    fun p010EnhancementShaderPreservesCoherentWideGamutChromaBeforeSpikeCleanup() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()

        assertTrue(source.contains("COHERENT_CHROMA_RETAIN_FLOOR"))
        assertTrue(source.contains("uint chromaNeighborAverage("))
        assertTrue(source.contains("bool coherentChromaColor("))
        assertTrue(source.contains("applyChromaDeadband(uValue, neighborU)"))
        assertTrue(source.contains("applyChromaDeadband(vValue, neighborV)"))
        assertTrue(source.contains("applyLocalChromaSpikeSmoothing(uValue, neighborU)"))
        assertTrue(source.contains("applyLocalChromaSpikeSmoothing(vValue, neighborV)"))
        assertFalse(source.contains("uint applyLocalChromaSpikeSmoothing(uint chromaValue)"))
    }

    @Test
    fun realtimePreviewEnhancementUsesGatedNormalSdrRedSkyCalibration() {
        val source = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp").readText()
        val body = source.substringAfter("vec3 realtimeApplyNormalSdrCalibration(vec3 rgb)")
            .substringBefore("vec3 clampWidePreviewRgb")

        assertTrue(body.contains("float redDominance = safeRgb.r - max(safeRgb.g, safeRgb.b);"))
        assertTrue(body.contains("float skyBlueDominance = min(safeRgb.b - safeRgb.r, safeRgb.g - safeRgb.r);"))
        assertTrue(body.contains("float redGreenRatio = safeRgb.r / max(safeRgb.g, 0.000001);"))
        assertTrue(body.contains("float blueGreenRatio = safeRgb.b / max(safeRgb.g, 0.000001);"))
        assertTrue(body.contains("float redOverflowGate = smoothstep(1.42, 1.72, redGreenRatio)"))
        assertTrue(body.contains("float redGuardGate = smoothstep(0.040, 0.240, redDominance)"))
        assertTrue(body.contains("redOverflowGate;"))
        assertTrue(body.contains("float skyHighlightProtect = 1.0 - smoothstep(0.88, 0.98, safeRgb.b);"))
        assertTrue(body.contains("float skyRecoveryGate = smoothstep(0.040, 0.220, skyBlueDominance)"))
        assertTrue(body.contains("skyHighlightProtect;"))
        assertTrue(body.contains("float redCorrectionGate = max(redGuardGate, skyRecoveryGate);"))
        assertTrue(body.contains("mix(1.0, gain.r, redCorrectionGate)"))
        assertTrue(body.contains("mix(1.0, gain.b, skyRecoveryGate)"))
        assertTrue(body.contains("vec3 corrected = safeRgb * correctionGain;"))
        assertTrue(body.contains("float colorLockGate = realtimeLocalColorLockGate(safeRgb);"))
        assertTrue(body.contains("vec3 colorLocked = mix(corrected, vec3(realtimeLuma(corrected)), colorLockGate);"))
        assertTrue(body.contains("return max(colorLocked * (sourceY / lockedY), vec3(0.0));"))
        assertFalse(body.contains("chromaGate"))
    }

    @Test
    fun realtimePreviewComputeUsesV5LumaOnlyLocalContrastAfterMicroTexture() {
        val source = File("src/main/cpp/shaders/vulkan/realtime_preview_enhance.comp").readText()
        val mainBody = source.substringAfter("void main()")
            .substringAfter("RealtimeLumaNeighborhood rawLumaN = realtimeSample3x3Luma(uv, texel);")

        assertTrue(source.contains("vec3 realtimeApexLocalContrast("))
        assertTrue(source.contains("float localContrast = centerY - localMean;"))
        assertTrue(source.contains("? 1.0 + detailStrength * 0.45"))
        assertTrue(source.contains("? max(0.003, localRange * mix(0.12, 0.24, realtimeAuthority))"))
        assertTrue(source.contains(": (realtimeAuthority > 1.0 ? 10.0 : 1.0 + detailStrength * 0.18)"))
        assertTrue(source.contains("return max(contrastRgb + vec3(limitedContrast), vec3(0.0));"))
        assertTrue(mainBody.contains("vec3 shadowClean = realtimeApexShadowNoiseFloor("))
        assertTrue(mainBody.contains("vec3 detailed = realtimeEdgeAwareDetail("))
        assertTrue(mainBody.contains("vec3 processed = realtimeApexMicroTextureRecover("))
        assertTrue(mainBody.contains("vec3 contrast = realtimeApexLocalContrast("))
        assertTrue(mainBody.contains("vec3 highlightTexture = realtimeApexHighlightTexture("))
        assertTrue(mainBody.contains("vec3 edgeCoherent = realtimeApexEdgeCoherence("))
        assertTrue(mainBody.contains("vec3 tonalDensity = realtimeApexTonalDensity("))
        assertTrue(mainBody.indexOf("vec3 shadowClean = realtimeApexShadowNoiseFloor(") <
            mainBody.indexOf("vec3 detailed = realtimeEdgeAwareDetail("))
        assertTrue(mainBody.indexOf("vec3 detailed = realtimeEdgeAwareDetail(") <
            mainBody.indexOf("vec3 processed = realtimeApexMicroTextureRecover("))
        assertTrue(mainBody.indexOf("vec3 processed = realtimeApexMicroTextureRecover(") <
            mainBody.indexOf("vec3 contrast = realtimeApexLocalContrast("))
        assertTrue(mainBody.indexOf("vec3 contrast = realtimeApexLocalContrast(") <
            mainBody.indexOf("vec3 highlightTexture = realtimeApexHighlightTexture("))
        assertTrue(mainBody.indexOf("vec3 highlightTexture = realtimeApexHighlightTexture(") <
            mainBody.indexOf("vec3 edgeCoherent = realtimeApexEdgeCoherence("))
        assertTrue(mainBody.indexOf("vec3 edgeCoherent = realtimeApexEdgeCoherence(") <
            mainBody.indexOf("vec3 tonalDensity = realtimeApexTonalDensity("))
        assertTrue(mainBody.contains("vec4(clampWidePreviewRgb(guardedDetail), REALTIME_COMPUTE_ENHANCED_ALPHA)"))
    }

    @Test
    fun p010EnhancementShaderUsesStrictNoiseCleanupAndHaloBoundedDetailLift() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()

        assertTrue(source.contains("const float COHERENT_CHROMA_RETAIN_FLOOR = 0.44"))
        assertTrue(source.contains("noiseVariance * 0.064"))
        assertTrue(source.contains("float smoothingPressure = max(p010ExportNoiseShape(), p010ExportLocalChromaSpikeShape())"))
        assertFalse(source.contains("float smoothingPressure = max(uniforms.noiseMetric, uniforms.localChromaSpikeSmoothing)"))
        assertTrue(source.contains("int spikeThreshold = strictVisibleCleanExport ? 44 : 80"))
        assertTrue(source.contains("float retained = strictVisibleCleanExport ?"))
        assertTrue(source.contains("mix(0.018, 0.004"))
        assertTrue(source.contains("mix(0.060, 0.024"))
        assertTrue(source.contains("float csfGain = 0.82 + csfMicroContrastWeight(float(centerValue) / 1023.0, 1.0) * 2.36"))
        assertTrue(source.contains("float overshoot = max(6.20, (localMax - localMin) * 0.22)"))
    }

    @Test
    fun p010EnhancementShaderUsesNormalizedNoiseCleanupAndBoundedDetailLift() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()

        assertTrue(source.contains("const float COHERENT_CHROMA_RETAIN_FLOOR = 0.44"))
        assertTrue(source.contains("const float JND_EXPORT_DITHER_VISIBILITY_SCALE = 0.28"))
        assertTrue(source.contains("float p010StrictVisibleCleanExportAuthority()"))
        assertTrue(source.contains("float p010ExportStrictCeiling()"))
        assertTrue(source.contains("return p010BoundedNormalProSdrExport()"))
        assertTrue(source.contains(": (p010StrictVisibleCleanExportAuthority() > 1.0 ? 10.0 : 1.0)"))
        assertTrue(source.contains("float p010ExportNoiseShape()"))
        assertTrue(source.contains("? p010NormalizeStrength(uniforms.noiseMetric)"))
        assertTrue(source.contains(": clamp(uniforms.noiseMetric / p010ExportStrictCeiling(), 0.0, 1.0)"))
        assertTrue(source.contains("float p010ExportLocalChromaSpikeShape()"))
        assertTrue(source.contains("? p010NormalizeStrength(uniforms.localChromaSpikeSmoothing)"))
        assertTrue(source.contains(": clamp(uniforms.localChromaSpikeSmoothing / p010ExportStrictCeiling(), 0.0, 1.0)"))
        assertTrue(source.contains("float pressure = smoothstep(0.72, 1.0, p010ExportNoiseShape())"))
        assertFalse(source.contains("float pressure = smoothstep(0.72, 1.0, uniforms.noiseMetric)"))
        assertTrue(source.contains("float smoothingPressure = max(p010ExportNoiseShape(), p010ExportLocalChromaSpikeShape())"))
        assertFalse(source.contains("float smoothingPressure = max(uniforms.noiseMetric, uniforms.localChromaSpikeSmoothing)"))
        assertTrue(source.contains("float exportNoiseShape = p010ExportNoiseShape()"))
        assertTrue(source.contains("float amplitude = clamp(0.35 + exportNoiseShape * 0.18, 0.0, 1.0) * abrDitherAmplitudeScale()"))
        assertTrue(source.contains("uint phase = uint(clamp(uniforms.temporalLumaBlend * 11.0 + p010ExportNoiseShape() * 17.0, 0.0, 63.0))"))
        assertTrue(source.contains("float amplitude = clamp(p010ExportNoiseShape() * 0.42 + 0.10, 0.0, 0.55) * abrDitherAmplitudeScale()"))
        assertTrue(source.contains("uint phase = uint(clamp(uniforms.temporalBlend * 23.0 + p010ExportNoiseShape() * 29.0, 0.0, 63.0))"))
        assertTrue(source.contains("float colorSigma = mix(0.018, 0.075, p010ExportNoiseShape())"))
        assertFalse(source.contains("clamp(uniforms.noiseMetric, 0.0, 1.0)"))
        assertTrue(source.contains("return (noiseVariance * 0.064) / max(signalSigma, 0.0005)"))
        assertTrue(source.contains("sqrt(variance) * 1023.0 * 1.90"))
        assertTrue(source.contains("uint threshold = (chromaSample ? 48u : 32u) + adaptiveBoost"))
        assertTrue(source.contains("float retained = strictVisibleCleanExport ?"))
        assertTrue(source.contains("mix(0.018, 0.004"))
        assertTrue(source.contains("mix(0.060, 0.024"))
        assertTrue(source.contains("heteroscedasticNoiseVariance(float(centerValue) / 1023.0) * 0.070"))
        assertTrue(source.contains("float csfGain = 0.82 + csfMicroContrastWeight(float(centerValue) / 1023.0, 1.0) * 2.36"))
        assertTrue(source.contains("float overshoot = max(6.20, (localMax - localMin) * 0.22)"))
        assertTrue(source.contains("const float P010_MAX_CUMULATIVE_DETAIL_CODES = 40.0"))
        assertTrue(source.contains("processed = applyCumulativeDetailBudget(lumaIndex, detailBase, processed)"))
        assertFalse(source.contains("aliasSafeMaxDetailDelta >= 0.99"))
    }

    @Test
    fun p010EnhancementShaderUsesBoundedSubJndDitherWithoutSdrAuthoritySentinel() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()
        val jndBody = source.substringAfter("float jndShapedQuantizationError(")
            .substringBefore("vec2 neuralOpticalFlowVector(")
        val quantizeBody = source.substringAfter("uint finalJndBlueNoiseQuantize(")
            .substringBefore("uint bakeCosmicSingularityExportLook(")

        assertTrue(source.contains("uint applyBlueNoiseDither(uint sampleIndex, uint sampleValue)"))
        assertTrue(source.contains("uint applyPhaseShiftedFilmGrain(uint sampleIndex, uint sampleValue)"))
        assertTrue(source.contains("uint applyPlanckGrain(uint sampleIndex, uint sampleValue)"))
        assertTrue(source.contains("float jndShapedQuantizationError(uint sampleIndex, uint sampleValue)"))
        assertTrue(source.contains("uint finalJndBlueNoiseQuantize(uint sampleIndex, uint sampleValue)"))
        assertTrue(source.contains("const float JND_EXPORT_DITHER_VISIBILITY_SCALE = 0.28"))
        assertTrue(jndBody.contains("highEntropyBlue * jnd * abrDitherAmplitudeScale() * JND_EXPORT_DITHER_VISIBILITY_SCALE"))
        assertTrue(quantizeBody.contains("quantizationError >= 0.50 ? 1.0 : (quantizationError <= -0.50 ? -1.0 : 0.0)"))
        assertTrue(quantizeBody.contains("return uint(clamp(quantized, 0.0, 1023.0))"))
        assertFalse(jndBody.contains("p010StrictNormalProSdrExportAuthority"))
        assertFalse(quantizeBody.contains("p010StrictNormalProSdrExportAuthority"))
    }

    @Test
    fun p010EnhancementShaderUsesSecondStageLumaOnlyMicroTextureRecovery() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()
        val microBody = source.substringAfter("uint applyApexMicroTextureRecover(")
            .substringBefore("const int BLUE_NOISE_8X8")
        val standardBody = source.substringAfter("uint processStandardCinemaPipeline(")
            .substringBefore("uint processNivukxApexPipeline(")

        assertTrue(source.contains("uint applyApexMicroTextureRecover(uint sampleIndex, uint detailedValue)"))
        assertTrue(microBody.contains("float axial1 ="))
        assertTrue(microBody.contains("float diag1 ="))
        assertTrue(microBody.contains("float axial2 ="))
        assertTrue(microBody.contains("float microLowPass ="))
        assertTrue(microBody.contains("float microTexture = c - microLowPass;"))
        assertTrue(microBody.contains("bool strictNormalProSdrExport = p010BoundedNormalProSdrExport();"))
        assertTrue(microBody.contains("? p010NormalizeStrength(uniforms.fineTextureDetailGain) * abrDetailGainScale()"))
        assertTrue(microBody.contains(": clamp(uniforms.fineTextureDetailGain * abrDetailGainScale() / 10.0, 0.0, 1.0)"))
        assertTrue(microBody.contains("? mix(1.20, 2.60, profileLift)"))
        assertTrue(microBody.contains(": mix(2.0, 10.0, profileLift)"))
        assertTrue(microBody.contains("? max(3.0, localRange * mix(0.16, 0.28, profileLift))"))
        assertTrue(microBody.contains("float limitedMicro = clamp("))
        assertTrue(microBody.contains("float candidate = clamp(float(detailedValue) + limitedMicro"))
        assertTrue(standardBody.contains("processed = applyEdgeAwareDetailLift(lumaIndex, processed);"))
        assertTrue(standardBody.contains("processed = applyApexMicroTextureRecover(lumaIndex, processed);"))
        assertTrue(standardBody.indexOf("processed = applyEdgeAwareDetailLift(lumaIndex, processed);") <
            standardBody.indexOf("processed = applyApexMicroTextureRecover(lumaIndex, processed);"))
    }

    @Test
    fun p010EnhancementShaderUsesV5LocalContrastAfterMicroTexture() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()
        val contrastBody = source.substringAfter("uint applyApexLocalContrastLuma(")
            .substringBefore("const int BLUE_NOISE_8X8")
        val standardBody = source.substringAfter("uint processStandardCinemaPipeline(")
            .substringBefore("uint processNivukxApexPipeline(")

        assertTrue(source.contains("uint applyApexLocalContrastLuma(uint sampleIndex, uint microValue)"))
        assertTrue(contrastBody.contains("float localContrast = c - localMean;"))
        assertTrue(contrastBody.contains("? 1.0 + 0.45 * profileLift"))
        assertTrue(contrastBody.contains(": 1.0 + 3.0 * profileLift"))
        assertTrue(contrastBody.contains("? max(3.0, localRange * 0.24)"))
        assertTrue(contrastBody.contains(": max(3.0, localRange * 0.16)"))
        assertTrue(contrastBody.contains("float candidate = clamp(float(microValue) + limitedContrast"))
        assertTrue(standardBody.contains("processed = applyApexMicroTextureRecover(lumaIndex, processed);"))
        assertTrue(standardBody.contains("processed = applyApexLocalContrastLuma(lumaIndex, processed);"))
        assertTrue(standardBody.indexOf("processed = applyApexMicroTextureRecover(lumaIndex, processed);") <
            standardBody.indexOf("processed = applyApexLocalContrastLuma(lumaIndex, processed);"))
    }

    @Test
    fun p010EnhancementShaderUsesExplicitNormalSdrExportAuthorityForGpuMicroTexture() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()
        val gateBody = source.substringAfter("bool p010BoundedNormalProSdrExport() {")
            .substringBefore("float p010StrictNormalProSdrExportAuthority()")
        val authorityBody = source.substringAfter("float p010StrictNormalProSdrExportAuthority() {")
            .substringBefore("bool p010ZeroVisibleGrainExport()")

        assertTrue(source.contains("float p010StrictNormalProSdrExportAuthority()"))
        assertTrue(source.contains("float p010StrictVisibleCleanExportAuthority()"))
        assertTrue(source.contains("const int SIGNAL_FAMILY_SDR = 1"))
        assertTrue(source.contains("const int SIGNAL_FAMILY_HDR = 2"))
        assertTrue(source.contains("const int SIGNAL_FAMILY_LOG = 3"))
        assertTrue(source.contains("const int SIGNAL_FAMILY_RAW = 4"))
        assertTrue(gateBody.contains("signalFamily == SIGNAL_FAMILY_SDR"))
        assertTrue(gateBody.contains("activeTransport == ACTIVE_TRANSPORT_REC709_SDR"))
        assertTrue(gateBody.contains("activeTransport == ACTIVE_TRANSPORT_BT2020_SDR"))
        assertTrue(authorityBody.contains("return p010BoundedNormalProSdrExport() ? 1.0 : 0.0;"))
        assertFalse(authorityBody.contains("fineTextureDetailGain"))
        assertFalse(authorityBody.contains("mediumDetailGain"))
        assertFalse(authorityBody.contains("strongDetailGain"))
        assertFalse(authorityBody.contains("aliasSafeMaxDetailDelta"))
        assertFalse(authorityBody.contains(">= 9.5"))
        assertFalse(authorityBody.contains(">= 0.95"))
        assertTrue(source.contains("bool strictEncodedSignal ="))
        assertTrue(source.contains("uniforms.spatialDenoise >= 0.98"))
        assertTrue(source.contains("uniforms.chromaDenoise >= 0.99"))
        assertTrue(source.contains("uniforms.sharpening >= 0.90"))
        assertTrue(source.contains("uniforms.noiseMetric >= 0.70"))
        assertTrue(source.contains("return p010BoundedNormalProSdrExport()"))
        assertTrue(source.contains(": (strictVisibleCleanProfile ? 10.0 : 1.0)"))
        assertTrue(source.contains("bool strictNormalProSdrExport = p010BoundedNormalProSdrExport();"))
        assertTrue(source.contains("? mix(1.20, 2.60, profileLift)"))
        assertTrue(source.contains("? max(3.0, localRange * mix(0.16, 0.28, profileLift))"))
    }

    @Test
    fun p010EnhancementShaderHasGeneratedSpirvArtifactForNativePipeline() {
        val shader = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp")
        val header = File("src/main/cpp/generated/neo_p010_export_enhance_comp_spv.h")
        val spv = File("src/main/cpp/generated/neo_p010_export_enhance.comp.spv")
        val headerSource = header.readText()
        val shaderHash = MessageDigest.getInstance("SHA-256")
            .digest(shader.readBytes())
            .joinToString(separator = "") { "%02x".format(it) }

        assertTrue(shader.isFile)
        assertTrue(spv.isFile)
        assertTrue(spv.length() > 0L)
        assertTrue(headerSource.contains("kNeoP010ExportEnhanceCompSpv"))
        assertTrue(headerSource.contains("kNeoP010ExportEnhanceCompSpvCount"))
        assertTrue(headerSource.contains("kNeoP010ExportEnhanceCompSpvSize"))
        assertTrue(headerSource.contains("// Source SHA-256: $shaderHash"))
    }

    @Test
    fun nativeTensorReadbackFailsClosedWhileZeroCopyIsUnsupported() {
        val functionBody = bridgeSource.substringAfter("nativeGetTensorData(")
        assertTrue(functionBody.contains("Zero-copy tensor readback is disabled until native import validates allocation size and stride."))
        assertTrue(functionBody.contains("return JNI_FALSE;"))
        assertFalse(functionBody.contains("AHardwareBuffer_lock"))
        assertFalse(functionBody.contains("std::memcpy(outPtr"))
    }

    @Test
    fun p010ExportShaderExposesEliteOriginalColorPipelineMath() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()

        assertTrue(source.contains("neoWideGamutCompressXyPreserveY"))
        assertTrue(source.contains("hpeXyzToNivukxOklab"))
        assertTrue(source.contains("nivukxApexSignalShape"))
        assertTrue(source.contains("ndr10RationalBezierToneMap"))
        assertTrue(source.contains("applySubtractiveFilmDensity"))
        assertTrue(source.contains("highlightSaturationRolloff"))
        assertTrue(source.contains("encodeNLogV3Family"))
        assertTrue(source.contains("applyEliteCinemaColorPipeline"))
    }

    @Test
    fun p010ExportShaderAppliesUniversalHighEndKneeCurveBeforeQuantization() {
        val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()

        assertTrue(source.contains("neoHighEndKneeCurve"))
        assertTrue(source.contains("applyNeoHighEndKneeCurveToLuma"))
        assertTrue(source.contains("resolveNeoHighEndKneeParams"))
        assertTrue(source.contains("vec4(0.54, 1.00, 2.25, 1.00)"))
        assertTrue(source.contains("vec4(0.58, 1.00, 2.05, 1.00)"))
        assertTrue(source.contains("vec4(0.72, 1.00, 1.36, 0.42)"))
        assertTrue(source.contains("processed = applyNeoHighEndKneeCurveToLuma(processed"))
        assertTrue(source.indexOf("processed = applyNeoHighEndKneeCurveToLuma(processed") <
            source.indexOf("return finalJndBlueNoiseQuantize(lumaIndex, processed)"))
    }
}
