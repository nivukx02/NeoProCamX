package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VulkanPreviewShaderContractTest {
    private val shaderSource: String by lazy {
        File("src/main/cpp/shaders/vulkan/ycbcr_preview.frag").readText()
    }
    private val bridgeSource: String by lazy {
        File("src/main/cpp/vulkan_bridge.cpp").readText()
    }
    private val truthfulDeliverySource: String by lazy {
        shaderSource.substring(shaderSource.indexOf("// TRUTHFUL DELIVERY PATH"))
    }

    @Test
    fun shader_boundsAdaptiveQualityOnlyForExplicitNormalProSdrIntent() {
        val mainBody = shaderSource.substringAfter("void main()")
        val deliveryNoiseBody = shaderSource.substringAfter("vec3 suppressRecordingDeliveryNoise(")
            .substringBefore("vec3 finishRealtimeComputedNdrDelivery(")

        assertTrue(
            mainBody.contains(
                "bool normalProSdrQualityIntent = !truthful && !lutEnabled && activeSdrTransport();"
            )
        )
        assertTrue(mainBody.contains("float plannedSpatialDenoise = normalProSdrQualityIntent"))
        assertTrue(mainBody.contains("? max(normalizeEnhancementStrength(uEnhance0.x), 0.28)"))
        assertTrue(mainBody.contains(": clamp(uEnhance0.x, 0.28, 10.0)"))
        assertTrue(mainBody.contains("float plannedSharpening = normalProSdrQualityIntent"))
        assertTrue(mainBody.contains("? normalizeEnhancementStrength(uEnhance0.z)"))
        assertTrue(mainBody.contains(": clamp(uEnhance0.z, 0.0, 10.0)"))
        assertTrue(deliveryNoiseBody.contains("bool boundedNormalProSdrQuality"))
        assertTrue(deliveryNoiseBody.contains("if (!boundedNormalProSdrQuality)"))
        assertTrue(deliveryNoiseBody.contains("strictChromaDenoiseAuthority = plannedChromaDenoise"))
        assertTrue(deliveryNoiseBody.contains("strictDenoiseCeiling = 1.0"))
        assertTrue(truthfulDeliverySource.contains("measuredNoise,\n        false\n    );"))
    }

    @Test
    fun shader_adaptiveSdrUsesExplicitTransportAndComputeMarkerWithoutSentinelDetection() {
        val normalizationBody = shaderSource.substringAfter("float normalizeEnhancementStrength(")
            .substringBefore("float normalProSdrDenoiseAuthority(")
        val standardSdrBody = shaderSource.substringAfter("if (!truthful) {")
            .substringBefore("// NIVUKX APEX DELIVERY PATH")
        val ownershipBody = shaderSource.substringAfter("bool normalProSdrQualityIntent =")
            .substringBefore("float strictDenoiseCeiling")

        assertTrue(shaderSource.contains("float normalizeEnhancementStrength(float rawStrength)"))
        assertTrue(normalizationBody.contains("return clamp("))
        assertTrue(normalizationBody.contains("rawStrength > 1.0 ? rawStrength * 0.1 : rawStrength"))
        assertFalse(normalizationBody.contains("? 10.0 : 1.0"))
        assertFalse(normalizationBody.contains(">= 9.5"))

        assertTrue(shaderSource.contains("const float REALTIME_COMPUTE_ENHANCED_ALPHA = 0.625;"))
        assertTrue(shaderSource.contains("bool activeSdrTransport()"))
        assertTrue(shaderSource.contains("bool realtimeComputeIntermediateAvailable(vec2 uv)"))
        assertTrue(shaderSource.contains("return abs(sourceAlpha - REALTIME_COMPUTE_ENHANCED_ALPHA) <= 0.015625;"))
        assertTrue(
            ownershipBody.contains(
                "!truthful && !lutEnabled && activeSdrTransport();"
            )
        )
        assertTrue(ownershipBody.contains("bool computeEnhancedSdr = normalProSdrDelivery &&"))
        assertTrue(ownershipBody.contains("realtimeComputeIntermediateAvailable(transformed);"))
        assertFalse(shaderSource.contains("normalProSdrUniformLock("))
        assertFalse(ownershipBody.contains("contrast >="))
        assertFalse(ownershipBody.contains("shadowLift >="))
        assertFalse(ownershipBody.contains("highlightCompression >="))
        assertFalse(ownershipBody.contains("plannedSpatialDenoise >="))

        assertTrue(standardSdrBody.contains("if (!computeEnhancedSdr) {"))
        assertTrue(standardSdrBody.contains("color = applyContrastAndSaturation(color, contrast, saturation);"))
        assertTrue(standardSdrBody.contains("float normalProSdrToneMix = normalProSdrDelivery"))
        assertTrue(standardSdrBody.contains("linear = encodeSdrActiveTransport(linear);"))
        assertTrue(shaderSource.contains("const int ACTIVE_TRANSPORT_REC709_SDR = 1;"))
        assertTrue(shaderSource.contains("const int ACTIVE_TRANSPORT_BT2020_SDR = 2;"))
        assertTrue(shaderSource.contains("vec3 encodeSdrActiveTransport(vec3 linearRgb)"))
        assertTrue(shaderSource.contains("sdrUsesBt2020Transport()"))

        assertTrue(
            standardSdrBody.contains(
                "float sdrQuantizationLevels = normalProSdrDelivery ? MASTER_QUANTIZATION_LEVELS : PREVIEW_QUANTIZATION_LEVELS;"
            )
        )
        assertTrue(standardSdrBody.contains("applyJndBlueNoiseDither(outColor.rgb, sdrDitherStrength, sdrQuantizationLevels)"))
        assertTrue(shaderSource.contains("const float DELIVERY_JND_DITHER_STRENGTH = 0.18;"))
        assertFalse(standardSdrBody.contains("normalProSdrDelivery ? 0.0"))
        assertFalse(
            standardSdrBody.contains(
                "applyJndBlueNoiseDither(outColor.rgb, normalProSdrDitherStrength, PREVIEW_QUANTIZATION_LEVELS)"
            )
        )

        assertTrue(bridgeSource.contains("updateDescriptorSet(state, target, imageIndex, useRealtimeCompute);"))
        assertTrue(bridgeSource.contains("imageInfo.imageView = intermediate != nullptr ? intermediate->imageView : frame->imageView;"))
        assertTrue(bridgeSource.contains("if (useRealtimeCompute) {"))
        assertTrue(bridgeSource.contains("recordRealtimeComputePass(state, target, commandBuffer, imageIndex, frame)"))
    }

    @Test
    fun shader_usesBt2100MonitorTonemapInsteadOfLegacyPolynomialHdrLook() {
        assertTrue(shaderSource.contains("bt2100HuePreservingDisplayMap"))
        assertTrue(shaderSource.contains("colorPreservingShoulder"))
        assertFalse(shaderSource.contains("8th-order polynomial"))
    }

    @Test
    fun shader_usesEdgeAwareDenoiseAndSharpeningForPreviewAndDelivery() {
        assertTrue(shaderSource.contains("edgeAwareSpatialDenoise"))
        assertTrue(shaderSource.contains("edgeAwareSharpen"))
        assertTrue(shaderSource.contains("decodeCameraSample"))
    }

    @Test
    fun shader_normalProSdrToneMixUsesExplicitDeliveryIdentity() {
        val nonTruthfulBody = shaderSource.substringAfter("if (!truthful) {")
            .substringBefore("// ═══════════════════════════════════════════════════════════════════════")

        assertTrue(shaderSource.contains("bool normalProSdrQualityIntent = !truthful && !lutEnabled && activeSdrTransport();"))
        assertTrue(shaderSource.contains("bool normalProSdrDelivery = normalProSdrQualityIntent;"))
        assertTrue(
            nonTruthfulBody.contains(
                "float normalProSdrToneMix = normalProSdrDelivery ? highlightCompression : highlightCompression * 0.3"
            )
        )
        assertTrue(
            nonTruthfulBody.contains(
                "linear = mix(linear, mix(cinematicTonemap(linear * normalProSdrHighlightScale), acesDisplayMap, 0.38), normalProSdrToneMix)"
            )
        )
        assertFalse(
            "Strict Normal/Pro SDR must not keep the old direct 0.3 tone blend cap.",
            nonTruthfulBody.contains("), highlightCompression * 0.3);")
        )
    }

    @Test
    fun shader_normalProSdrBypassesFragmentCleanupOnlyForMarkedComputeIntermediate() {
        val suppressBody = shaderSource.substringAfter("vec3 suppressRecordingDeliveryNoise(")
            .substringBefore("vec3 finishRealtimeComputedNdrDelivery(")
        val normalSdrBody = shaderSource.substringAfter("float normalProSdrToneMix =")
            .substringBefore("// TRUTHFUL DELIVERY PATH")
        val truthfulBody = truthfulDeliverySource.replace("\r\n", "\n")

        assertTrue(suppressBody.contains("bool boundedNormalProSdrQuality"))
        assertTrue(shaderSource.contains("float plannedSharpening = normalProSdrQualityIntent"))
        assertTrue(normalSdrBody.contains("if (!computeEnhancedSdr) {"))
        assertTrue(normalSdrBody.contains("linear = suppressRecordingDeliveryNoise("))
        assertTrue(normalSdrBody.contains("linear = encodeSdrActiveTransport(linear);"))
        assertFalse(normalSdrBody.contains("max(plannedSharpening, 10.0)"))
        assertTrue(
            truthfulBody.contains(
                "plannedSharpening,\n        measuredNoise,\n        false\n    );"
            )
        )
    }

    @Test
    fun shader_normalProSdrDenoiseHelpersUseBoundedNormalizedAuthority() {
        val ictcpBody = shaderSource.substringAfter("vec3 ictcpChromaDenoise(")
            .substringBefore("float bayesShrinkThreshold(")
        val chromaBody = shaderSource.substringAfter("vec3 chromaDenoise(")
            .substringBefore("vec3 rgbSpeckleSuppress(")
        val rgbBody = shaderSource.substringAfter("vec3 rgbSpeckleSuppress(")
            .substringBefore("vec3 darkNoiseFloorSuppress(")
        val darkBody = shaderSource.substringAfter("vec3 darkNoiseFloorSuppress(")
            .substringBefore("float skinToneWeight(")

        assertTrue(shaderSource.contains("float normalProSdrDenoiseAuthority(float strength, bool boundedNormalProSdrQuality)"))
        assertTrue(shaderSource.contains("? normalizeEnhancementStrength(strength)"))
        assertTrue(shaderSource.contains(": (strength >= 9.5 ? 10.0 : 1.0)"))
        assertFalse(shaderSource.contains("normalProSdrUniformLock("))
        assertFalse(shaderSource.contains("return strength >= 9.5 ? 10.0 : 1.0;"))
        assertTrue(shaderSource.contains("float strictNeutralNoiseGate("))
        assertTrue(shaderSource.contains("float strictRgbSpeckleGate("))
        assertTrue(shaderSource.contains("float strictTexturelessRgbGrainGate("))
        assertTrue(ictcpBody.contains("normalProSdrDenoiseAuthority(strength, boundedNormalProSdrQuality)"))
        assertTrue(ictcpBody.contains("strictDenoise ? 1.0 : 0.995"))
        assertTrue(chromaBody.contains("normalProSdrDenoiseAuthority(strength, boundedNormalProSdrQuality)"))
        assertTrue(chromaBody.contains("strictNeutralNoiseGate(luma, length(chroma), denoiseAuthority, boundedNormalProSdrQuality)"))
        assertTrue(chromaBody.contains("strictRgbSpeckleGate(luma, length(chroma), denoiseAuthority, boundedNormalProSdrQuality)"))
        assertTrue(chromaBody.contains("texturelessRgbGrain"))
        assertTrue(rgbBody.contains("normalProSdrDenoiseAuthority(strength, boundedNormalProSdrQuality)"))
        assertTrue(rgbBody.contains("strictNeutralNoiseGate(luma, chromaMagnitude, denoiseAuthority, boundedNormalProSdrQuality)"))
        assertTrue(rgbBody.contains("strictRgbSpeckleGate(luma, chromaMagnitude, denoiseAuthority, boundedNormalProSdrQuality)"))
        assertTrue(rgbBody.contains("texturelessRgbGrain"))
        assertTrue(darkBody.contains("normalProSdrDenoiseAuthority(strength, boundedNormalProSdrQuality)"))
        assertTrue(darkBody.contains("strictNeutralNoiseGate(luma, length(color - neutral), denoiseAuthority, boundedNormalProSdrQuality)"))
        assertTrue(darkBody.contains("strictRgbSpeckleGate(luma, length(color - neutral), denoiseAuthority, boundedNormalProSdrQuality)"))
        assertTrue(darkBody.contains("texturelessRgbGrain"))
    }

    @Test
    fun shader_routesLogDeliveryThroughNeoOwnedLogCurvesOnly() {
        assertTrue(shaderSource.contains("encodeLogByMode"))
        assertTrue(shaderSource.contains("encodeNLogFamily"))
        assertTrue(shaderSource.contains("normalizeLogToneDeliveryMode"))
        assertTrue(shaderSource.contains("encodeLogByMode(linear, normalizeLogToneDeliveryMode(toneDeliveryMode))"))
        assertFalse(shaderSource.contains("encodeRedLog3G10"))
        assertFalse(shaderSource.contains("RED_LOG3G10"))
        assertFalse(shaderSource.contains("encodeLogC4"))
        assertFalse(shaderSource.contains("SLog"))
        assertFalse(shaderSource.contains("S-Log"))
        assertFalse(shaderSource.contains("LogC4"))
        assertFalse(shaderSource.contains("Log3G"))
        assertFalse(shaderSource.contains("ARRI"))
        assertFalse(shaderSource.contains("Sony"))
        assertFalse(shaderSource.contains("RED"))
        assertFalse(shaderSource.contains("Dolby"))
        assertTrue(shaderSource.contains("NEO_ACESCG_NON_CERTIFIED"))
        assertTrue(shaderSource.contains("neoRec709ToAcesCg"))
        assertFalse(shaderSource.contains("Display P3"))
        assertFalse(shaderSource.contains("DCI-P3"))
        assertFalse(shaderSource.contains("encodeNeoLogCurveOpen"))
        assertFalse(shaderSource.contains("encodeNeoLogCurveDefault"))
        assertFalse(shaderSource.contains("encodeNeoLogCurveSoft"))
        assertFalse(shaderSource.contains("encodeNeoLogCurveDense"))
        assertFalse(shaderSource.contains("0.2471896"))
        assertFalse(shaderSource.contains("5.367655"))
        assertFalse(shaderSource.contains("0.092864"))
        assertFalse(shaderSource.contains("0.224282"))
        assertFalse(shaderSource.contains("155.975327"))
        assertFalse(shaderSource.contains("15.1927"))
        assertFalse(shaderSource.contains("0.01;\n    const float NEO_LOG_DENSE"))
        assertFalse(shaderSource.contains("if (logDelivery) outColor = vec4(encodeSLog3(linear), 1.0);"))
    }

    @Test
    fun shaderPreviewTextHasNoVendorHdrIdentityClaims() {
        assertFalse(shaderSource.contains("HDR10+"))
        assertFalse(shaderSource.contains("Dolby"))
        assertFalse(shaderSource.contains("RPU"))
        assertFalse(shaderSource.contains("vendorCertified"))
    }

    @Test
    fun shader_hasSingleValidHelperDefinitionsAndCompleteContrastFunction() {
        val contrastStart = shaderSource.indexOf("vec3 applyContrastAndSaturation")
        assertTrue(contrastStart >= 0)

        val nextTopLevelHelper = Regex("\n(?:vec[234]|float|bool|void)\\s+\\w+\\s*\\(")
            .find(shaderSource, contrastStart + 1)
        assertTrue(nextTopLevelHelper != null)

        val contrastBody = shaderSource.substring(contrastStart, nextTopLevelHelper!!.range.first)
        assertTrue(contrastBody.contains("return "))
        assertFalse(contrastBody.contains("vec4 uGrade2;"))
        assertFalse(contrastBody.contains("vec4 uGrade3;"))

        assertTrue(Regex("\\bvec3\\s+srgbToLinear\\s*\\(").findAll(shaderSource).count() == 1)
        assertTrue(Regex("\\bvec3\\s+encodeNLogFamily\\s*\\(").findAll(shaderSource).count() == 1)
    }

    @Test
    fun shaderUsesAuthoritativeNLogFamilyAndReachableModeValues() {
        assertTrue(shaderSource.contains("N_LOG_SCENE_GRAY_18 = 0.18"))
        assertTrue(shaderSource.contains("N_LOG_GRAY_CODE_18 = 0.50"))
        assertTrue(shaderSource.contains("encodeNLogFamily"))
        assertTrue(shaderSource.contains("nLogV5QuinticToe"))
        assertTrue(shaderSource.contains("decodeNLogV5Toe"))
        assertTrue(shaderSource.contains("decodeLogByMode"))
        assertTrue(shaderSource.contains("vec4(0.080000, -0.436788, 0.789056, -0.352269)"))
        assertTrue(shaderSource.contains("14.041075"))
        assertTrue(shaderSource.contains("toneDeliveryMode > 9.5 && toneDeliveryMode < 13.5"))
        assertTrue(shaderSource.contains("toneDeliveryMode > 29.5 && toneDeliveryMode < 33.5"))
    }

    @Test
    fun shaderRoutesNeoColorPacketThroughReferenceOklchProcessing() {
        assertTrue(shaderSource.contains("neoAcesCgToReferenceOklab"))
        assertTrue(shaderSource.contains("neoReferenceOklabToAcesCg"))
        assertTrue(shaderSource.contains("applyNeoGamutOkLchPipeline"))
        assertTrue(shaderSource.contains("applyNeoGamutApexOkLchPipeline"))
        assertTrue(shaderSource.contains("neoGamutApexHueBoundary"))
        assertTrue(shaderSource.contains("neoGamutApexChromaPreservingToneMap"))
        assertTrue(shaderSource.contains("uNeoModeParams.w >= 2.0"))
        assertTrue(shaderSource.contains("uAcesCgToOklabReference"))
        assertTrue(shaderSource.contains("uOklabReferenceToAcesCg"))
        assertTrue(shaderSource.contains("uNeoHdrParams.x > 0.5"))
        assertTrue(shaderSource.contains("uNeoModeParams.x > 0.5"))
        assertTrue(shaderSource.contains("= applyNeoGamutOkLchPipeline("))
    }

    @Test
    fun shader_keepsNdrNivukxCameraInputDecodeSeparateFromPqOutputEncoding() {
        assertTrue(shaderSource.contains("bool neoNdrDelivery = ndr10PlusDelivery || nivukxVisionDelivery"))
        assertTrue(shaderSource.contains("bool neoNdrMonitor = ndr10PlusMonitor || nivukxVisionMonitor"))
        assertTrue(shaderSource.contains("bool encodedPqDeliveryInput = pqDelivery && !neoNdrDelivery"))
        assertTrue(shaderSource.contains("bool encodedPqMonitorInput = pqMonitor && !neoNdrMonitor"))
        assertTrue(shaderSource.contains(": encodedPqMonitorInput ? bt2100PqEotf"))
        assertTrue(shaderSource.contains(": encodedPqDeliveryInput ? bt2100PqEotf"))
        assertTrue(shaderSource.contains("linear = bt2100ColorVolumeGuard(linear, hlgDelivery, encodedPqDeliveryInput)"))
        assertTrue(shaderSource.contains("linear = bt2100ColorVolumeGuard(linear, hlgMonitor, encodedPqMonitorInput)"))
    }

    @Test
    fun shaderUsesDistinctNeoHdrPerceptualBranchesInMasterDelivery() {
        assertTrue(shaderSource.contains("bool ndr10PlusDelivery"))
        assertTrue(shaderSource.contains("bool nivukxVisionDelivery"))
        assertTrue(shaderSource.contains("naturalDynamicRangeToneMap"))
        assertTrue(truthfulDeliverySource.contains("naturalDynamicRangeToneMap"))
    }

    @Test
    fun shaderUsesSelectedNeoHdrPerceptualBranchDuringDisplayMonitoring() {
        assertTrue(shaderSource.contains("linear = naturalDynamicRangeToneMap(transformed, linear, nivukxVisionMonitor"))
        assertTrue(shaderSource.contains("bool nivukxVisionMonitor"))
    }

    @Test
    fun shader_usesCinemaDetailRefinementForNoiseAndEdges() {
        assertTrue(shaderSource.contains("cinemaDetailRefine"))
        assertTrue(shaderSource.contains("detailNoiseGate"))
        assertTrue(shaderSource.contains("accumulateDenoiseSample(sum, weightSum, center, uv, vec2(texel.x * 2.0, 0.0)"))
    }

    @Test
    fun shader_usesDirectionalHaloSafeCinemaSharpening() {
        assertTrue(shaderSource.contains("cinemaEdgeConfidence"))
        assertTrue(shaderSource.contains("directionalEdge"))
        assertTrue(shaderSource.contains("localContrastGuard"))
        assertTrue(shaderSource.contains("overshootLimit"))
        assertTrue(shaderSource.contains("limitedLuma"))
    }

    @Test
    fun shader_usesResolutionNormalizedMicroDetailForAllVideoSizes() {
        assertTrue(shaderSource.contains("resolutionNormalizedDetailScale"))
        assertTrue(shaderSource.contains("microDetailConfidence"))
        assertTrue(shaderSource.contains("longEdge"))
        assertTrue(shaderSource.contains("textureSize(uComputeEnhancedIntermediate, 0)"))
        assertTrue(shaderSource.indexOf("resolutionNormalizedDetailScale") <
            shaderSource.indexOf("float gain = clamp("))
    }

    @Test
    fun shaderSamplesComputeEnhancedIntermediateForRealtimeRenderPath() {
        assertTrue(shaderSource.contains("layout(binding = 0) uniform sampler2D uComputeEnhancedIntermediate"))
        assertTrue(shaderSource.contains("texture(uComputeEnhancedIntermediate"))
        assertFalse(shaderSource.contains("layout(binding = 0) uniform sampler2D uCameraSampler"))
    }

    @Test
    fun shader_suppressesChromaSpecklesBeforeCreativeGrade() {
        assertTrue(shaderSource.contains("preGradeChromaNoiseGuard"))
        assertTrue(shaderSource.indexOf("preGradeChromaNoiseGuard(color, toneDeliveryMode)") <
            shaderSource.indexOf("color = applyContrastAndSaturation(color, contrast, saturation);"))
    }

    @Test
    fun shader_usesBt2100TransferFunctionsForHdrPreview() {
        assertTrue(shaderSource.contains("bt2100PqEotf"))
        assertTrue(shaderSource.contains("bt2100PqInverseEotf"))
        assertTrue(shaderSource.contains("bt2100HlgOetf"))
        assertTrue(shaderSource.contains("bt2100HlgInverseOetf"))
        assertTrue(shaderSource.contains("bt2100Luma"))
        assertFalse(shaderSource.contains("acesLikeBt2100ToneScale"))
        assertFalse(shaderSource.contains("applyHdrBrightness"))
    }

    @Test
    fun shader_appliesNeutralBt2100ColorVolumeGuardAfterTransferDecode() {
        assertTrue(shaderSource.contains("bt2100ColorVolumeGuard"))
        assertFalse(shaderSource.contains("hdrColorPipelineGuard"))
        assertFalse(shaderSource.contains("float redOverdrive"))
        assertFalse(shaderSource.contains("float cyanShadow"))
        assertFalse(shaderSource.contains("naturalSkinHighlightRollOff"))
        assertTrue(
            shaderSource.indexOf("bt2100ColorVolumeGuard(decoded") >
                shaderSource.indexOf("vec3 decoded = hlgLike")
        )
        assertTrue(
            shaderSource.indexOf("linear = bt2100ColorVolumeGuard(linear") <
                shaderSource.indexOf("linear = edgeAwareSpatialDenoise(")
        )
    }

    @Test
    fun shader_usesUnifiedHighEndNoiseSuppressionForAllRecordingDeliveryModes() {
        assertTrue(shaderSource.contains("suppressRecordingDeliveryNoise"))
        assertTrue(shaderSource.contains("bool rawDelivery"))
        assertTrue(shaderSource.contains("float plannedChromaDenoise"))
        assertTrue(shaderSource.contains("float plannedSharpening"))
        assertTrue(shaderSource.contains("float measuredNoise"))
        assertTrue(shaderSource.contains("linear = suppressRecordingDeliveryNoise("))
        assertTrue(
            shaderSource.indexOf("linear = suppressRecordingDeliveryNoise(") >
                shaderSource.indexOf("linear = edgeAwareSpatialDenoise(")
        )
        assertTrue(
            shaderSource.indexOf("linear = suppressRecordingDeliveryNoise(") <
            shaderSource.indexOf("if (logDelivery) outColor")
        )
    }

    @Test
    fun shader_usesLumaPreservingRgbGrainCancellationWithoutSharpenDowngrade() {
        assertTrue(shaderSource.contains("float highFrequencyColorNoise"))
        assertTrue(shaderSource.contains("float edgeStableBlend"))
        assertTrue(shaderSource.contains("float chromaOnlyStrength"))
        assertTrue(shaderSource.contains("return edgeAwareSharpen("))
        assertFalse(shaderSource.contains("sharpenStrength = 0.0"))
    }

    @Test
    fun shader_usesBoundedSceneDrivenNoiseCancellationWithoutDisablingDetailRefine() {
        assertTrue(shaderSource.contains("float plannedSpatialDenoise"))
        assertTrue(shaderSource.contains("float plannedChromaDenoise"))
        assertTrue(shaderSource.contains("float plannedSharpening"))
        assertTrue(shaderSource.contains("float monitorDenoise = clamp("))
        assertTrue(shaderSource.contains("float deliverySpatialDenoise = clamp("))
        // X4/R4: the detail lane must be applied uniformly across the whole frame, never spatially
        // gated to a subject mask (which left only the nearest object sharp). Lock both the new
        // uniform constant and the removal of the old subject-gate symbol.
        assertTrue(shaderSource.contains("float profileDetailGate = 1.0;"))
        assertFalse(shaderSource.contains("subjectMaskedDetailActive"))
        assertFalse(shaderSource.contains("float profileDetailGate = uSubjectDetail.x > 0.5 ? 0.0 : 1.0"))
        assertFalse(shaderSource.contains("logDelivery ? 0.998"))
        assertFalse(shaderSource.contains("rawDelivery ? 0.999"))
        assertTrue(shaderSource.contains("return edgeAwareSharpen("))
    }

    @Test
    fun shader_routesStandardSdrThroughFallbackSuppressorThenTenBitTransportDelivery() {
        val standardSdrBody = shaderSource
            .substringAfter("float normalProSdrToneMix =")
            .substringBefore("// NIVUKX APEX DELIVERY PATH")
        val fallbackSuppressStart = standardSdrBody.indexOf("if (!computeEnhancedSdr) {")
        val fallbackSuppress = standardSdrBody.indexOf(
            "linear = suppressRecordingDeliveryNoise(",
            fallbackSuppressStart
        )
        val fallbackSuppressEnd = standardSdrBody.indexOf("}", fallbackSuppress)
        val transportEncode = standardSdrBody.indexOf("linear = encodeSdrActiveTransport(linear);")
        val linearOutput = standardSdrBody.indexOf("outColor = vec4(linear, 1.0);")
        val deliveryDither = standardSdrBody.indexOf(
            "float sdrDitherStrength = normalProSdrDelivery ? DELIVERY_JND_DITHER_STRENGTH : PREVIEW_JND_DITHER_STRENGTH;"
        )
        val deliveryQuantization = standardSdrBody.indexOf(
            "float sdrQuantizationLevels = normalProSdrDelivery ? MASTER_QUANTIZATION_LEVELS : PREVIEW_QUANTIZATION_LEVELS;"
        )
        val finalDither = standardSdrBody.indexOf(
            "outColor.rgb = applyJndBlueNoiseDither(outColor.rgb, sdrDitherStrength, sdrQuantizationLevels);"
        )

        assertTrue(fallbackSuppressStart >= 0)
        assertTrue(fallbackSuppress > fallbackSuppressStart)
        assertTrue(fallbackSuppressEnd > fallbackSuppress)
        assertTrue(transportEncode > fallbackSuppressEnd)
        assertTrue(linearOutput > transportEncode)
        assertTrue(deliveryDither > linearOutput)
        assertTrue(deliveryQuantization > deliveryDither)
        assertTrue(finalDither > deliveryQuantization)
        assertFalse(
            standardSdrBody.substring(fallbackSuppressEnd + 1)
                .contains("linear = suppressRecordingDeliveryNoise(")
        )
        assertTrue(
            truthfulDeliverySource.contains(
                "linear = suppressRecordingDeliveryNoise("
            )
        )
    }

    @Test
    fun shader_apexDetailKeepsSignedLumaAndCapsShinyHaloGain() {
        assertFalse(shaderSource.contains("float detailLuma = abs(dot(detail, vec3(0.2627, 0.6780, 0.0593)))"))
        assertTrue(shaderSource.contains("float signedDetailLuma = dot(detail, vec3(0.2627, 0.6780, 0.0593))"))
        assertTrue(shaderSource.contains("float detailLuma = signedDetailLuma"))
        assertTrue(shaderSource.contains("float detailLimit = mix(0.020, 0.006, noiseGate)"))
        assertTrue(shaderSource.contains("float detailScale = 1.0 + clamp"))
        assertFalse(shaderSource.contains("clamp(detailScale, 0.85, 1.25)"))
    }

    @Test
    fun `fragment shader declares extreme detail profile id and does not require subject mask`() {
        val source = shaderSource

        assertTrue(source.contains("bool extremeDetailProfileActive()"))
        assertTrue(source.contains("uSubjectDetail.x > 2.5 && uSubjectDetail.x < 3.5"))
        assertTrue(source.contains("extremeDetailProfileActive()"))
        assertTrue(source.contains("profileDetailAuthority"))
        assertTrue(source.contains("extremeDetail ? 12.0 : 8.0"))
    }

    @Test
    fun shader_containsImaxStrictHeteroscedasticDenoiseAndDetailGate() {
        assertTrue(shaderSource.contains("heteroscedasticNoiseVariance"))
        assertTrue(shaderSource.contains("noiseAdaptiveLaplacianGain"))
        assertTrue(shaderSource.contains("float shotNoiseCoefficient = 0.0032"))
        assertTrue(shaderSource.contains("float readNoiseCoefficient = 0.00068"))
        assertTrue(shaderSource.contains("0.00000004"))
        assertTrue(shaderSource.contains("float preserve = mix(0.91, 1.0, motionConfidence)"))
        assertTrue(shaderSource.contains("const float ULTRA_DETAIL_AMPLIFICATION = 20.0"))
        assertTrue(shaderSource.contains("adaptiveBaseGain *"))
        assertTrue(shaderSource.contains("ULTRA_DETAIL_AMPLIFICATION *"))
        assertTrue(shaderSource.contains("profileDetailAuthority"))
        assertTrue(shaderSource.contains("9-tap strict IMAX Laplacian"))
        assertTrue(shaderSource.contains(") / 11.52"))
        assertTrue(shaderSource.contains("float overshootLimit = max("))
        assertTrue(shaderSource.contains("extremeDetail ? 0.0028 : 0.0032"))
        assertTrue(shaderSource.contains("0.995"))
        assertTrue(shaderSource.contains("return color + detail * strength * 20.0"))
    }

    @Test
    fun shader_usesBoundedNoiseCleanupAndHaloGuardedTextureRecovery() {
        assertTrue(shaderSource.contains("float shotNoiseCoefficient = 0.0032"))
        assertTrue(shaderSource.contains("float readNoiseCoefficient = 0.00068"))
        assertTrue(shaderSource.contains("return (noiseVariance * 0.065) / max(signalSigma, 0.0005)"))
        assertTrue(shaderSource.contains("float preserve = mix(0.91, 1.0, motionConfidence)"))
        assertTrue(shaderSource.contains("const float ULTRA_DETAIL_AMPLIFICATION = 20.0"))
        assertTrue(shaderSource.contains("float strictNormalProSdrDetailAuthority = boundedNormalProSdrQuality"))
        assertTrue(shaderSource.contains("? normalizeEnhancementStrength(strength)"))
        assertTrue(shaderSource.contains(": (strength >= 9.5 ? 10.0 : 1.0)"))
        assertTrue(shaderSource.contains("bool strictNormalProSdrDetail = boundedNormalProSdrQuality"))
        assertTrue(shaderSource.contains("? strictNormalProSdrDetailAuthority >= 0.95"))
        assertTrue(shaderSource.contains("float profileDetailAuthority = extremeDetail ? 1.22 : 1.0"))
        assertTrue(shaderSource.contains("mix(0.72, 1.0, strictNormalProSdrDetailAuthority)"))
        assertTrue(shaderSource.contains("profileDetailAuthority"))
        assertTrue(shaderSource.contains("extremeDetail ? 12.0 : 8.0"))
        assertTrue(shaderSource.contains("extremeDetail ? 0.0028 : 0.0032"))
        assertTrue(shaderSource.contains("extremeDetail ? 0.82 : 1.0"))
        assertTrue(shaderSource.contains("return color + detail * strength * 20.0"))
        assertTrue(shaderSource.contains("plannedSharpening * (1.0 - measuredNoise * 0.10)"))
        assertTrue(shaderSource.contains("strictChromaDenoiseAuthority * 0.88 + strictNoiseAuthority * 0.36 + modeChromaLift"))
        assertTrue(shaderSource.contains("strictChromaDenoiseAuthority * 0.78 + strictNoiseAuthority * 0.40 + modeChromaLift"))
        assertTrue(shaderSource.contains("float strictDenoiseCeiling = 1.0"))
        assertFalse(shaderSource.contains("normalProSdrUniformLock("))
    }

    @Test
    fun shader_usesApexAllModeMonitorCleanupWithoutSdrLogRawSoftCaps() {
        assertTrue(shaderSource.contains("plannedChromaDenoise + 0.08"))
        assertTrue(shaderSource.contains("plannedChromaDenoise * 0.88 + measuredNoise * 0.36"))
        assertTrue(shaderSource.contains("plannedChromaDenoise * 0.78 + measuredNoise * 0.40"))
        assertTrue(shaderSource.contains("1.0"))
        assertFalse(shaderSource.contains("plannedChromaDenoise * 0.82 + measuredNoise * 0.30"))
        assertFalse(shaderSource.contains("plannedChromaDenoise * 0.70 + measuredNoise * 0.34"))
        assertFalse(shaderSource.contains("clamp(plannedChromaDenoise, 0.18, 0.992)"))
        assertFalse(shaderSource.contains("clamp(plannedChromaDenoise + 0.04, 0.18, 0.992)"))
    }

    @Test
    fun shader_containsIctcpBayesShrinkAndHvsCsfQualityPath() {
        assertTrue(shaderSource.contains("FP32_BAYESIAN_PIPELINE"))
        assertFalse(shaderSource.contains("FP16_BAYESIAN_PIPELINE"))
        assertTrue(shaderSource.contains("linearBt2020ToICtCp"))
        assertTrue(shaderSource.contains("ictcpToLinearBt2020"))
        assertTrue(shaderSource.contains("ictcpChromaDenoise"))
        assertTrue(shaderSource.contains("bayesShrinkThreshold"))
        assertTrue(shaderSource.contains("laplacianPyramidBayesShrink"))
        assertTrue(shaderSource.contains("anisotropicOpticalFlowConfidence"))
        assertTrue(shaderSource.contains("hvsCsfMask"))
        assertTrue(shaderSource.contains("bayesian = laplacianPyramidBayesShrink"))
        assertTrue(shaderSource.contains("hvsMask = hvsCsfMask"))
    }

    @Test
    fun shader_containsWeberSpectralGrainAndHalationCinemaPath() {
        assertTrue(shaderSource.contains("weberFechnerLocalAdaptation"))
        assertTrue(shaderSource.contains("spectralReflectance31ToAcesAp0"))
        assertTrue(shaderSource.contains("temporalPhaseShiftedBlueNoiseGrain"))
        assertTrue(shaderSource.contains("warmHighlightHalation"))
        assertFalse(truthfulDeliverySource.contains("linear = weberFechnerLocalAdaptation"))
        assertFalse(truthfulDeliverySource.contains("linear = spectralReflectance31ToAcesAp0"))
        assertFalse(truthfulDeliverySource.contains("linear = warmHighlightHalation"))
    }

    @Test
    fun shader_containsPlanckPerceptualColorContrastHalationAndJndGrainPath() {
        assertTrue(shaderSource.contains("linearAcesCgToOklab"))
        assertTrue(shaderSource.contains("oklabToLinearAcesCg"))
        assertTrue(shaderSource.contains("oklabChromaBilateralFilter"))
        assertTrue(shaderSource.contains("bartenHvsContrastEnhancement"))
        assertTrue(shaderSource.contains("fitzHughNagumoChemicalHalation"))
        assertTrue(shaderSource.contains("planckBlueNoiseJndGrain"))
        assertTrue(shaderSource.contains("linear = oklabChromaBilateralFilter"))
        assertFalse(truthfulDeliverySource.contains("linear = bartenHvsContrastEnhancement"))
        assertFalse(truthfulDeliverySource.contains("linear = fitzHughNagumoChemicalHalation"))
        assertFalse(truthfulDeliverySource.contains("linear = planckBlueNoiseJndGrain"))
    }

    @Test
    fun shader_containsAnamorphicLensWarpAndFinalJndDither() {
        assertTrue(shaderSource.contains("applyAnamorphicLensWarp"))
        assertTrue(shaderSource.contains("ANAMORPHIC_DESQUEEZE_DEFAULT"))
        assertTrue(shaderSource.contains("k1 * r2 + k2 * r4 + k3 * r6"))
        assertTrue(shaderSource.contains("bicubicPreviewSample"))
        assertTrue(shaderSource.contains("applyJndBlueNoiseDither"))
        assertTrue(shaderSource.contains("gl_FragCoord.xy"))
        assertTrue(shaderSource.contains("1.0 - exp(-gammaJnd * gradient2)"))
        assertTrue(shaderSource.contains("const float PREVIEW_QUANTIZATION_LEVELS = 255.0"))
        assertTrue(shaderSource.contains("const float MASTER_QUANTIZATION_LEVELS = 1023.0"))
        assertTrue(shaderSource.contains("const float DELIVERY_JND_DITHER_STRENGTH = 0.18"))
        assertTrue(shaderSource.contains("const float PREVIEW_JND_DITHER_STRENGTH = 0.28"))
        assertTrue(shaderSource.contains("const float LOG_PREVIEW_JND_DITHER_STRENGTH = 0.24"))
        assertTrue(shaderSource.contains("float quantizationLevels"))
        assertFalse(shaderSource.contains("floor(255.0 * clamped + ditherOffset) / 255.0"))
        assertFalse(shaderSource.contains(", 0.85, PREVIEW_QUANTIZATION_LEVELS"))
        assertFalse(shaderSource.contains(", 0.70, PREVIEW_QUANTIZATION_LEVELS"))
        assertFalse(shaderSource.contains(", 0.85, MASTER_QUANTIZATION_LEVELS"))
        assertTrue(shaderSource.contains("float sdrDitherStrength = normalProSdrDelivery ? DELIVERY_JND_DITHER_STRENGTH : PREVIEW_JND_DITHER_STRENGTH"))
        assertTrue(shaderSource.contains("float sdrQuantizationLevels = normalProSdrDelivery ? MASTER_QUANTIZATION_LEVELS : PREVIEW_QUANTIZATION_LEVELS"))
        assertTrue(truthfulDeliverySource.contains("MASTER_QUANTIZATION_LEVELS"))
    }

    @Test
    fun shader_containsCosmicGaborLindebergHvsContrastPath() {
        assertTrue(shaderSource.contains("gaborLindebergKernel"))
        assertTrue(shaderSource.contains("lindebergScaleSpaceResponse"))
        assertTrue(shaderSource.contains("gaborLindebergHvsContrastEnhancement"))
        assertFalse(truthfulDeliverySource.contains("linear = gaborLindebergHvsContrastEnhancement"))
    }

    @Test
    fun shader_truthfulMasterPipelineKeepsNeutralOrderedProcessingStages() {
        assertTrue(shaderSource.contains("OMNIPOTENT_CINEMA_ENGINE_PIPELINE"))
        assertTrue(truthfulDeliverySource.indexOf("linear = oklabChromaBilateralFilter") <
            truthfulDeliverySource.indexOf("linear = edgeAwareSpatialDenoise("))
        assertTrue(truthfulDeliverySource.indexOf("linear = edgeAwareSpatialDenoise(") <
            truthfulDeliverySource.indexOf("linear = suppressRecordingDeliveryNoise("))
    }

    @Test
    fun shader_declaresZenithCosmicPlanckPreviewPipelineWithoutVendorClaims() {
        assertTrue(shaderSource.contains("ZENITH_COSMIC_PLANCK_PREVIEW_PIPELINE"))
        assertFalse(truthfulDeliverySource.contains("linear = fitzHughNagumoChemicalHalation"))
        assertFalse(truthfulDeliverySource.contains("linear = gaborLindebergHvsContrastEnhancement"))
        assertFalse(truthfulDeliverySource.contains("linear = temporalPhaseShiftedBlueNoiseGrain"))
        assertFalse(truthfulDeliverySource.contains("linear = planckBlueNoiseJndGrain"))
        assertFalse(shaderSource.contains("Dolby"))
        assertFalse(shaderSource.contains("ARRI"))
        assertFalse(shaderSource.contains("Sony"))
        assertFalse(shaderSource.contains("RED"))
    }

    @Test
    fun shader_truthfulDeliveryNeverAddsMovingTextureAfterNoiseSuppression() {
        assertTrue(truthfulDeliverySource.contains("linear = suppressRecordingDeliveryNoise("))
        assertFalse(truthfulDeliverySource.contains("linear = temporalPhaseShiftedBlueNoiseGrain"))
        assertFalse(truthfulDeliverySource.contains("linear = planckBlueNoiseJndGrain"))
        assertTrue(truthfulDeliverySource.contains("applyJndBlueNoiseDither"))
    }

    @Test
    fun truthfulMasterDeliveryExcludesCreativeSimulationEffects() {
        assertFalse(truthfulDeliverySource.contains("linear = spectralReflectance31ToAcesAp0"))
        assertFalse(truthfulDeliverySource.contains("linear = warmHighlightHalation"))
        assertFalse(truthfulDeliverySource.contains("linear = fitzHughNagumoChemicalHalation"))
        assertFalse(truthfulDeliverySource.contains("linear = bartenHvsContrastEnhancement"))
        assertFalse(truthfulDeliverySource.contains("linear = gaborLindebergHvsContrastEnhancement"))
    }

    @Test
    fun shader_hdrDeliveryUsesSkinToneRedGuardBeforeTransferEncoding() {
        assertTrue(shaderSource.contains("bt2100SkinToneRedGuard"))
        assertTrue(shaderSource.contains("float redExcess = smoothstep(1.24, 1.66, redGreenRatio)"))
        assertTrue(
            shaderSource.indexOf("linear = bt2100SkinToneRedGuard(linear, hlgDelivery, pqDelivery)") >
                shaderSource.indexOf("linear = highlightRolloff(linear, highlightCompression, highlightBiasScale)")
        )
        assertTrue(
            shaderSource.indexOf("linear = bt2100SkinToneRedGuard(linear, hlgDelivery, pqDelivery)") <
                shaderSource.indexOf("if (logDelivery) outColor")
        )
    }

    @Test
    fun shader_exposesEliteOriginalColorPipelineMath() {
        assertTrue(shaderSource.contains("neoWideGamutCompressXyPreserveY"))
        assertTrue(shaderSource.contains("hpeXyzToNivukxOklab"))
        assertTrue(shaderSource.contains("nivukxApexSignalShape"))
        assertTrue(shaderSource.contains("ndr10RationalBezierToneMap"))
        assertTrue(shaderSource.contains("applySubtractiveFilmDensity"))
        assertTrue(shaderSource.contains("highlightSaturationRolloff"))
        assertTrue(shaderSource.contains("encodeNLogV3Family"))
        assertTrue(shaderSource.contains("applyEliteCinemaColorPipeline"))
    }

    @Test
    fun shader_routesNdrAndNivukxThroughNaturalDynamicRangeToneMap() {
        assertTrue(shaderSource.contains("naturalDynamicRangeToneMap"))
        assertTrue(shaderSource.contains("ndrLocalToneRegionWeights"))
        assertTrue(shaderSource.contains("rawDomainHighlightReconstruct"))
        assertTrue(shaderSource.contains("ndrCyanGreenHighlightGuard"))
        assertTrue(shaderSource.contains("reduceArtificialNdrSharpening"))
        assertTrue(shaderSource.contains("linear = naturalDynamicRangeToneMap(transformed, linear, nivukxVisionMonitor"))
        assertTrue(shaderSource.contains("linear = naturalDynamicRangeToneMap(transformed, linear, nivukxVisionDelivery"))
        assertTrue(
            truthfulDeliverySource.indexOf("linear = naturalDynamicRangeToneMap(transformed, linear, nivukxVisionDelivery") <
            truthfulDeliverySource.indexOf("linear = suppressRecordingDeliveryNoise(")
        )
    }

    @Test
    fun shader_appliesUniversalHuePreservingHighEndKneeCurveAcrossMonitorAndDeliveryModes() {
        assertTrue(shaderSource.contains("neoHighEndKneeCurve"))
        assertTrue(shaderSource.contains("applyNeoHighEndKneeCurve"))
        assertTrue(shaderSource.contains("resolveNeoHighEndKneeParams"))
        assertTrue(shaderSource.contains("vec4(0.54, 1.00, 2.25, 1.00)"))
        assertTrue(shaderSource.contains("vec4(0.58, 1.00, 2.05, 1.00)"))
        assertTrue(shaderSource.contains("vec4(0.72, 1.00, 1.36, 0.42)"))
        assertTrue(shaderSource.contains("float mappedY = neoHighEndKneeCurve(sourceY, kneeParams.x, kneeParams.y, kneeParams.z)"))
        assertTrue(shaderSource.contains("vec3 mapped = source * (mappedY / sourceY)"))
        assertTrue(shaderSource.contains("linear = applyNeoHighEndKneeCurve(linear, resolveNeoHighEndKneeParams("))
        val monitorNdrToneMap = shaderSource.indexOf("linear = naturalDynamicRangeToneMap(transformed, linear, nivukxVisionMonitor")
        val monitorKneeAfterNdr = shaderSource.indexOf(
            "linear = applyNeoHighEndKneeCurve(linear, resolveNeoHighEndKneeParams(",
            monitorNdrToneMap
        )
        val deliveryNdrToneMap = truthfulDeliverySource.indexOf("linear = naturalDynamicRangeToneMap(transformed, linear, nivukxVisionDelivery")
        val deliveryKneeAfterNdr = truthfulDeliverySource.indexOf(
            "linear = applyNeoHighEndKneeCurve(linear, resolveNeoHighEndKneeParams(",
            deliveryNdrToneMap
        )
        assertTrue(
            monitorKneeAfterNdr > monitorNdrToneMap
        )
        assertTrue(
            deliveryKneeAfterNdr > deliveryNdrToneMap
        )
        assertTrue(
            deliveryKneeAfterNdr <
                truthfulDeliverySource.indexOf("linear = suppressRecordingDeliveryNoise(")
        )
    }

    @Test
    fun shader_naturalNdrOwnsRawColorStabilizationBeforeDisplayTransform() {
        assertTrue(shaderSource.contains("ndrRawChannelNormalize"))
        assertTrue(shaderSource.contains("ndrSkinToneProtect"))
        assertTrue(shaderSource.contains("ndrWhiteBalanceRecovery"))
        assertTrue(shaderSource.contains("filmicLogLuminanceMap"))
        assertTrue(shaderSource.contains("rawLinearToDisplayNdrTransform"))
        assertTrue(
            shaderSource.indexOf("source = ndrRawChannelNormalize") <
                shaderSource.indexOf("source = rawDomainHighlightReconstruct")
        )
        assertTrue(
            shaderSource.indexOf("mapped = ndrSkinToneProtect") <
                shaderSource.indexOf("return rawLinearToDisplayNdrTransform")
        )
    }

    @Test
    fun shader_usesNormalizedBoundedDenoiseAndDetailControlsForAllModes() {
        assertTrue(shaderSource.contains("float plannedSpatialDenoise = normalProSdrQualityIntent"))
        assertTrue(shaderSource.contains("? max(normalizeEnhancementStrength(uEnhance0.x), 0.28)"))
        assertTrue(shaderSource.contains(": clamp(uEnhance0.x, 0.28, 10.0)"))
        assertTrue(shaderSource.contains("float plannedChromaDenoise = normalProSdrQualityIntent"))
        assertTrue(shaderSource.contains(": clamp(uEnhance0.y, 0.50, 10.0)"))
        assertTrue(shaderSource.contains("float measuredNoise = normalProSdrQualityIntent"))
        assertTrue(shaderSource.contains(": clamp(uEnhance0.w, 0.0, 10.0)"))
        assertTrue(shaderSource.contains("float monitorDenoise = clamp("))
        assertTrue(shaderSource.contains("0.16,"))
        assertTrue(shaderSource.contains("strictDenoiseCeiling"))
        assertTrue(shaderSource.contains("float deliverySpatialDenoise = clamp("))
        assertTrue(shaderSource.contains("0.20,"))
        assertTrue(shaderSource.contains("float strictDenoiseCeiling = 1.0"))
        assertTrue(shaderSource.contains("float strictSpatialDenoiseAuthority = plannedSpatialDenoise"))
        assertTrue(shaderSource.contains("float strictNoiseAuthority = measuredNoise"))
        assertTrue(shaderSource.contains("float highlightProtect = 1.0 - smoothstep(0.74, 0.985, y) * 0.28"))
        assertTrue(shaderSource.contains("return clamp(requestedSharpening * highlightProtect * shadowTextureProtect, 0.0, 0.92)"))
        assertFalse(shaderSource.contains("return clamp(requestedSharpening * highlightProtect * shadowTextureProtect, 0.0, 0.72)"))
        assertTrue(shaderSource.contains("float plannedSharpening = normalProSdrQualityIntent"))
        assertTrue(shaderSource.contains(": clamp(uEnhance0.z, 0.0, 10.0)"))
        assertTrue(shaderSource.contains("(boundedNormalProSdrQuality ? denoiseAuthority : strength)"))
        assertTrue(shaderSource.contains("strictNormalProSdrDenoise ? 1.0 : 0.985"))
        assertTrue(shaderSource.contains("clamp(plannedChromaDenoise + 0.08, 0.18, 1.0)"))
        assertTrue(shaderSource.contains("clamp(plannedChromaDenoise * 0.88 + measuredNoise * 0.36 + 0.08, 0.16, 1.0)"))
        assertTrue(shaderSource.contains("clamp(plannedChromaDenoise * 0.78 + measuredNoise * 0.40 + 0.08, 0.18, 1.0)"))
        assertFalse(shaderSource.contains("strictNormalProSdrDelivery ? 10.0 : 1.0"))
        assertFalse(shaderSource.contains("max(plannedSpatialDenoise, 10.0)"))
        assertFalse(shaderSource.contains("clamp(plannedChromaDenoise * 0.70 + measuredNoise * 0.22 + 0.06, 0.16, 0.92)"))
    }

    @Test
    fun shader_usesNormalSdrDetailAcuityWithNoiseHaloHighlightAndSkinGuards() {
        val detailFunction = shaderSource.substring(
            shaderSource.indexOf("vec3 cinemaDetailRefine("),
            shaderSource.indexOf("vec3 edgeAwareSharpen(")
        )

        assertTrue(detailFunction.contains("const float ULTRA_DETAIL_AMPLIFICATION = 20.0"))
        assertTrue(detailFunction.contains("float strictNormalProSdrDetailAuthority = boundedNormalProSdrQuality"))
        assertTrue(detailFunction.contains("? normalizeEnhancementStrength(strength)"))
        assertTrue(detailFunction.contains(": (strength >= 9.5 ? 10.0 : 1.0)"))
        assertTrue(detailFunction.contains("bool strictNormalProSdrDetail = boundedNormalProSdrQuality"))
        assertTrue(detailFunction.contains("? strictNormalProSdrDetailAuthority >= 0.95"))
        assertTrue(detailFunction.contains(": strictNormalProSdrDetailAuthority > 1.0"))
        assertTrue(detailFunction.contains("float farRingMean = (east2L + west2L + north2L + south2L + ne2L + nw2L + se2L + sw2L) * 0.125"))
        assertTrue(detailFunction.contains("float wideRingMean = (east3L + west3L + north3L + south3L + ne3L + nw3L + se3L + sw3L) * 0.125"))
        assertTrue(detailFunction.contains("float longDistanceStructure = mix(luma - farRingMean, luma - wideRingMean, 0.35)"))
        assertTrue(detailFunction.contains("float longDistanceTextureGate = smoothstep(0.001, 0.014, abs(longDistanceStructure))"))
        assertTrue(detailFunction.contains("float strictDetailNoiseReject = strictNormalProSdrDetail"))
        assertTrue(detailFunction.contains("strictDetailNoiseReject *"))
        assertTrue(detailFunction.contains("(1.0 + longDistanceTextureGate * 2.40)"))
        assertTrue(detailFunction.contains("bool extremeDetail = extremeDetailProfileActive()"))
        assertTrue(detailFunction.contains("float profileDetailAuthority = extremeDetail ? 1.22 : 1.0"))
        assertTrue(detailFunction.contains("? mix(0.72, 1.0, strictNormalProSdrDetailAuthority)"))
        assertTrue(detailFunction.contains(": strictNormalProSdrDetailAuthority) *\n        profileDetailAuthority"))
        assertTrue(detailFunction.contains("float strictDetailLuma = lumaDetail + longDistanceStructure * longDistanceTextureGate * 0.52"))
        assertTrue(detailFunction.contains("extremeDetail ? 12.0 : 8.0"))
        assertTrue(detailFunction.contains("extremeDetail ? 0.0028 : 0.0032"))
        assertTrue(detailFunction.contains("mix(0.16, 0.30, strictNormalProSdrDetailAuthority)"))
        assertTrue(detailFunction.contains("float highlightBlendBack = smoothstep(0.70, 0.94, luma) *"))
        assertTrue(detailFunction.contains("(boundedNormalProSdrQuality ? 0.22"))
        assertTrue(detailFunction.contains("float haloBlendBack = (1.0 - localContrastGuard) *"))
        assertTrue(detailFunction.contains("(boundedNormalProSdrQuality ? 0.30"))
        assertTrue(detailFunction.contains("float skinBlendBack = skinGuard * 0.20"))
        assertTrue(detailFunction.contains("clamp(highlightBlendBack + haloBlendBack + skinBlendBack, 0.0, 0.72)"))
        assertTrue(detailFunction.contains("? (extremeDetail ? 12.0 : 8.0)"))
        assertTrue(detailFunction.contains(": (strictNormalProSdrDetailAuthority > 1.0 ? 100.0"))
    }

    @Test
    fun shader_usesBoundedNormalSdrDenoiseAndVisibleRgbNoiseGate() {
        val denoiseFunction = shaderSource.substring(
            shaderSource.indexOf("vec3 edgeAwareSpatialDenoise("),
            shaderSource.indexOf("float detailNoiseGate(")
        )

        assertTrue(denoiseFunction.contains("normalProSdrDenoiseAuthority(strength, boundedNormalProSdrQuality)"))
        assertTrue(denoiseFunction.contains("strictDenoiseAuthorityActive("))
        assertTrue(denoiseFunction.contains("boundedNormalProSdrQuality ? 0.18 : 0.06"))
        assertTrue(denoiseFunction.contains("boundedNormalProSdrQuality ? 1.08 : 1.40"))
        assertTrue(denoiseFunction.contains("strictNormalProSdrDenoise ? 1.0 : 0.985"))
        assertTrue(denoiseFunction.contains("float lumaResidue = abs(bayesianLuma - luma)"))
        assertTrue(denoiseFunction.contains("float texturelessRgbGrain = strictTexturelessRgbGrainGate("))
        assertTrue(denoiseFunction.contains("chromaResidue,\n            denoiseAuthority,\n            boundedNormalProSdrQuality"))
        assertTrue(denoiseFunction.contains("float strictRgbNoiseBlend = max("))
        assertTrue(denoiseFunction.contains("smoothstep(0.0008, 0.050, chromaResidue)"))
        assertTrue(denoiseFunction.contains("smoothstep(0.0012, 0.018, lumaResidue)"))
        assertTrue(denoiseFunction.contains("bayesian = mix(bayesian, neutral, clamp(strictRgbNoiseBlend, 0.0, 0.985))"))
    }

    @Test
    fun shader_usesDedicatedRealtimeComputedNdrNivukxDeliveryBeforeGenericFragmentDenoise() {
        assertTrue(shaderSource.contains("finishRealtimeComputedNdrDelivery"))
        val dedicatedBranch = truthfulDeliverySource.indexOf("if (ndr10PlusDelivery || nivukxVisionDelivery) {")
        val dedicatedFinish = truthfulDeliverySource.indexOf("linear = finishRealtimeComputedNdrDelivery(", dedicatedBranch)
        val genericDenoise = truthfulDeliverySource.indexOf("linear = edgeAwareSpatialDenoise(")
        val genericSuppress = truthfulDeliverySource.indexOf("linear = suppressRecordingDeliveryNoise(")
        val pqEncode = truthfulDeliverySource.indexOf("bt2100PqInverseEotf(linear)", dedicatedFinish)

        assertTrue(dedicatedBranch >= 0)
        assertTrue(dedicatedFinish > dedicatedBranch)
        assertTrue(dedicatedFinish < genericDenoise)
        assertTrue(dedicatedFinish < genericSuppress)
        assertTrue(pqEncode > dedicatedFinish)
        assertTrue(shaderSource.contains("linear = neoRadianceHeliosCascadeTone(linear)"))
        assertTrue(shaderSource.contains("vec3 exposureMasks = ndrExposureMasks(sourceY, sourceY, peak)"))
        assertTrue(shaderSource.contains("linear = ndrRedChannelOverflowGuard(linear, nivukxVision)"))
        assertTrue(shaderSource.contains("linear = bt2100SkinToneRedGuard(linear, false, true)"))
        assertTrue(shaderSource.contains("bool strictRealtimeClean = plannedChromaDenoise >= 0.98 && measuredNoise >= 0.62"))
        assertTrue(shaderSource.contains("linear = chromaDenoise(linear, 1.0, false)"))
        assertTrue(shaderSource.contains("linear = edgeAwareSharpen(uv, linear, false, true, 1.0, 1.0, finishSharpen, false)"))
        assertTrue(shaderSource.contains("float ndrDeliveryDitherStrength = (plannedChromaDenoise >= 0.98 && measuredNoise >= 0.62)"))
        assertFalse(shaderSource.contains("finishRealtimeComputedNdrDelivery") &&
            shaderSource.substring(
                shaderSource.indexOf("vec3 finishRealtimeComputedNdrDelivery"),
                shaderSource.indexOf("void main()")
            ).contains("edgeAwareSpatialDenoise("))
        assertFalse(shaderSource.substring(
            shaderSource.indexOf("vec3 finishRealtimeComputedNdrDelivery"),
            shaderSource.indexOf("void main()")
        ).contains("applyNeoGamutOkLchPipeline("))
    }
}
