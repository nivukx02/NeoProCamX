package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RawPreviewColorPipelineContractTest {
    private val vulkanShader = File("src/main/cpp/shaders/vulkan/ycbcr_preview.frag").readText()
    private val rawMonitorShader =
        File("src/main/cpp/shaders/vulkan/raw_monitor_tonemap.comp").readText()
    private val glCompositor = File("src/main/java/com/neoproc/neoproccamx/rendering/PreviewGlCompositor.kt").readText()
    private val nativeBridge = File("src/main/cpp/vulkan_bridge.cpp").readText()

    @Test
    fun vulkanRawMonitorExitsBeforeGenericSdrGrading() {
        val rawBranch = vulkanShader.indexOf("if (rawMonitor) {")
        val genericGrade = vulkanShader.indexOf("color = preGradeChromaNoiseGuard(color, toneDeliveryMode)")
        val genericContrast = vulkanShader.indexOf("color = applyContrastAndSaturation(color, contrast, saturation)")

        assertTrue(rawBranch >= 0)
        assertTrue(rawBranch < genericGrade)
        assertTrue(rawBranch < genericContrast)
        assertTrue(vulkanShader.contains("bool earlyRawMonitor"))
        assertTrue(vulkanShader.contains("earlyNdrOrNivukxPath || earlyRawMonitor"))
    }

    @Test
    fun rawFallbackHasNoSdrGammaLiftOrSaturationBoost() {
        val rawBody = glCompositor
            .substringAfter("} else if (uGrade == 2) {")
            .substringBefore("// TPDF dither")
        assertFalse(rawBody.contains("pow(c, vec3(0.72))"))
        assertFalse(rawBody.contains("1.15"))
        assertTrue(rawBody.contains("float mappedY"))
        assertTrue(rawBody.contains("linearToSrgb"))
    }

    @Test
    fun nativeRawMonitorUsesNeutralShoulderNotAcesFilmicCurve() {
        assertTrue(nativeBridge.contains("npRawMonitorNeutralShoulder"))
        assertFalse(nativeBridge.contains("npRawMonitorFilmicShoulder"))
        assertFalse(nativeBridge.contains("2.51f * x + 0.03f"))
    }

    @Test
    fun nativeNdrMonitorUsesMeasuredPresentationAnchorWithoutChangingMaster() {
        val curve = nativeBridge
            .substringAfter("static inline float npRawNdrSdrMonitorLuminance")
            .substringBefore("static inline uint8_t npRawMonitorSrgb8")
        val ndrMonitor = nativeBridge
            .substringAfter("static inline NpRawRgb npRawNdrSdrMonitor")
            .substringBefore("static inline void npRawMonitorPixelToSrgb8")

        assertTrue(curve.contains("kDiffuseWhiteLinear = 0.60382734f"))
        assertTrue(nativeBridge.contains("NDR-NIVUKX-PY-CAL-v4"))
        assertTrue(curve.contains("if (x <= 1.0f) return x * kDiffuseWhiteLinear"))
        assertTrue(curve.contains("kShoulderLength = kHeadroom / kDiffuseWhiteLinear"))
        assertFalse(ndrMonitor.contains("rolloffScale * 0.34f"))
        assertTrue(ndrMonitor.contains("npRawNdrSdrMonitorLuminance(monitorY)"))
        assertTrue(ndrMonitor.contains("npRawCompressRec709GamutPreserveLuma(rec709)"))
    }

    @Test
    fun ndrMonitorPreservesRichMidtonesAndLimitsDesaturationToSuperWhiteHighlights() {
        val nativeNdr = nativeBridge
            .substringAfter("static inline NpRawRgb npRawNdrSdrMonitor")
            .substringBefore("static inline void npRawMonitorPixelToSrgb8")

        assertTrue(rawMonitorShader.contains("params.toneProfile.x"))
        assertTrue(nativeNdr.contains("correction.sensorDiffuseScene"))
        assertTrue(rawMonitorShader.contains("ndrBlackPreservingMidtoneLift"))
        assertTrue(nativeBridge.contains("npRawNdrBlackPreservingMidtoneLift"))
        assertTrue(rawMonitorShader.contains("smoothstep(0.55, 1.0, mappedY)"))
        assertTrue(nativeNdr.contains("npRawSmoothstep(0.55f, 1.0f, mappedY)"))
        assertTrue(rawMonitorShader.contains("acesCgToRec2020"))
        assertTrue(rawMonitorShader.contains("rec2020ToRec709"))
    }

    @Test
    fun nativeEightBitMonitorUsesHighPrecisionImmutableSrgbLookup() {
        val conversion = nativeBridge
            .substringAfter("static inline uint8_t npRawMonitorSrgb8")
            .substringBefore("struct NpRawRgb")

        assertTrue(conversion.contains("std::array<uint8_t, 65536>"))
        assertTrue(conversion.contains("c * 65535.0f + 0.5f"))
        assertTrue(conversion.contains("return kLinearToSrgb8[index]"))
    }

    @Test
    fun gpuRawMonitorTonemapPreservesTheFp16SceneLinearMaster() {
        assertTrue(File("src/main/cpp/generated/raw_monitor_tonemap_comp_spv.h").exists())
        assertTrue(nativeBridge.contains("#include \"generated/raw_monitor_tonemap_comp_spv.h\""))
        assertTrue(rawMonitorShader.contains("layout(binding = 0, rgba16f) uniform readonly image2D uAcesCgInput"))
        assertTrue(rawMonitorShader.contains("layout(binding = 1, rgba8) uniform writeonly image2D uSrgbOutput"))
        assertTrue(rawMonitorShader.contains("ndrSdrMonitor(acesCg, ndrLocalDetailStops(pixel, acesCg))"))
        assertTrue(rawMonitorShader.contains("uniform readonly image2D uAcesCgInput"))
        assertFalse(rawMonitorShader.contains("imageStore(uAcesCgInput"))
        assertTrue(rawMonitorShader.contains("linearToSrgb8Grid"))
        assertTrue(nativeBridge.contains("kRawMonitorTonemapCompSpv"))
        assertTrue(nativeBridge.contains("state->rawMonitorTonemapPipeline"))
        assertTrue(nativeBridge.contains("VK_FORMAT_R8G8B8A8_UNORM"))
        assertTrue(nativeBridge.contains("raw_monitor_tonemap unavailable; retaining exact CPU monitor operator"))
    }

    @Test
    fun ndrRawMonitorModeDoesNotDependOnOptionalCalibrationConfidence() {
        assertTrue(rawMonitorShader.contains("bool ndrActive = params.modeFlags.x >= 1"))
        assertFalse(rawMonitorShader.contains("bool ndrActive = params.modeFlags.y != 0"))
        assertTrue(nativeBridge.contains("if (correction.mode >= 1 && correction.mode <= 2)"))
        assertFalse(nativeBridge.contains("if (correction.active && correction.mode >= 1"))
        assertTrue(rawMonitorShader.contains("if (!correctionEnabled || mode < 1 || mode > 2) return source"))
    }

    @Test
    fun persistentGpuMonitorImageTracksItsRealLayoutAcrossFrames() {
        assertTrue(nativeBridge.contains("bool previewImageInitialized = false;"))
        assertTrue(nativeBridge.contains("r.previewImageInitialized = true;"))
        assertTrue(nativeBridge.contains("? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL"))
        assertTrue(nativeBridge.contains("r.previewImageInitialized ? VK_ACCESS_TRANSFER_READ_BIT : 0"))
        assertTrue(nativeBridge.contains("r.previewImageInitialized = false;"))
    }
}
