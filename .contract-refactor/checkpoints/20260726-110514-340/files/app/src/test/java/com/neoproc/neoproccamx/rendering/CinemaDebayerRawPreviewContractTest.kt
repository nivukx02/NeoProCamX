package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s1-f07 / CP10c — locks the RAW-preview cinema-debayer activation: demosaic_vulkan.comp (the shader
 * CP10b compiled) is now actually DISPATCHED on a real R16_UINT import, behind a no-downgrade gate that
 * defaults to the YUV fallback. Mirrors [MfsrFusionShaderContractTest]; text-token asserts only (no Vulkan).
 *
 * The single-shader decision (s1-f08): demosaic_vulkan — already compiled, WB-before-demosaic +
 * hard-gradient green (CP14) + ACEScg forward matrices (CP11) — is the live RAW-preview demosaic;
 * cinema_debayer.comp stays a non-compiled reference and must NOT be dual-wired into the native build.
 */
class CinemaDebayerRawPreviewContractTest {

    private val bridge: String = File("src/main/cpp/vulkan_bridge.cpp").readText()
    private val bridgeKt: String =
        File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt").readText()
    private val pipelineKt: String =
        File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanCinemaDebayerPipeline.kt").readText()

    @Test
    fun nativeBridgeIncludesAndDispatchesTheDemosaicShader() {
        assertTrue("demosaic SPV header must be included",
            bridge.contains("#include \"generated/demosaic_vulkan_comp_spv.h\""))
        assertTrue(bridge.contains("kDemosaicVulkanCompSpv"))
        // The dispatch function exists and actually issues a compute dispatch on the demosaic pipeline.
        assertTrue(bridge.contains("dispatchRawDemosaicPreview"))
        assertTrue(bridge.contains("vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, state->demosaicPipeline)"))
        assertTrue(bridge.contains("vkCmdDispatch(cmd, gx, gy, 1u)"))
        // All five bindings (4 storage images + 1 UBO) are written — none left UNDEFINED.
        assertTrue(bridge.contains("vkUpdateDescriptorSets(state->device, 5, writes"))
        assertTrue(bridge.contains("VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER"))
        // Owned defect maps + output are deterministically cleared so the dispatch reads no UNDEFINED memory.
        assertTrue(bridge.contains("vkCmdClearColorImage"))
        // Real R16_UINT import (format 0x20 path), not a generic AHB placeholder.
        assertTrue(bridge.contains("importRawStorageImage"))
        assertTrue(bridge.contains("VK_FORMAT_R16_UINT"))
    }

    @Test
    fun nativeDispatchIsGatedDefaultOffNoDowngrade() {
        // Gate field defaults OFF; the dispatch bails immediately when it is off (YUV fallback).
        assertTrue(bridge.contains("bool rawPreviewDemosaicEnabled = false;"))
        assertTrue(bridge.contains("if (!state->rawPreviewDemosaicEnabled) return false;"))
        // JNI entries exist (setter + dispatch).
        assertTrue(bridge.contains("nativeSetRawPreviewDemosaicEnabled"))
        assertTrue(bridge.contains("nativeDispatchRawDemosaicPreview"))
        // Owned resources are freed at device teardown.
        assertTrue(bridge.contains("destroyRawDemosaicResources(state)"))
        // NO-DOWNGRADE: the standard YUV submit path must NOT branch on the RAW-demosaic gate — the
        // demosaic is reached only through its own dispatch entry, so shipping pixels stay byte-identical.
        val submitStart = bridge.indexOf("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSubmitHardwareBuffer")
        assertTrue("nativeSubmitHardwareBuffer must exist", submitStart >= 0)
        val submitEnd = bridge.indexOf("\n}", submitStart)
        val submitBody = bridge.substring(submitStart, if (submitEnd > submitStart) submitEnd else bridge.length)
        assertFalse("YUV submit path must not reference the RAW-demosaic gate",
            submitBody.contains("rawPreviewDemosaicEnabled"))
        assertFalse("YUV submit path must not dispatch the demosaic",
            submitBody.contains("dispatchRawDemosaicPreview"))
    }

    @Test
    fun nativeDispatchIsRaceSafeAndStorageFeatureGated() {
        // HIGH-finding fix: the dispatch acquire-loads the readiness flag the background build thread
        // store-releases AFTER writing the non-atomic demosaic pipeline handles — establishing happens-before.
        assertTrue("dispatch must acquire-load qfcaPipelinesReady before reading pipeline handles",
            bridge.contains("qfcaPipelinesReady.load(std::memory_order_acquire)"))
        // Storage-feasibility gates (fail-closed -> YUV fallback): imported AHB feature, owned-image feature,
        // the enabled device feature, and the feature-guarded demosaic pipeline build.
        assertTrue(bridge.contains("VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT"))
        assertTrue(bridge.contains("shaderStorageImageExtendedFormats"))
        assertTrue(bridge.contains("storageImageExtendedFormatsEnabled"))
        assertTrue("demosaic pipeline build must be guarded by the storage-extended-formats feature",
            bridge.contains("if (state->storageImageExtendedFormatsEnabled) {"))
    }

    @Test
    fun demosaicShaderRemainsTheEliteCp14Cp11Math() {
        val shader = File("src/main/cpp/shaders/vulkan/demosaic_vulkan.comp").readText()
        // WB-before-demosaic (CP14): white balance is applied inside calibratedRaw, before reconstruction.
        assertTrue(shader.contains("* wbForChannel(channel)"))
        // Hard-gradient edge-directed green (CP14): directional selection, not a soft blend.
        assertTrue(shader.contains("if (h < v) return gH;"))
        assertTrue(shader.contains("if (v < h) return gV;"))
        // ACEScg forward matrices (CP11 / s1-f09).
        assertTrue(shader.contains("M_SENSOR_TO_XYZ"))
        assertTrue(shader.contains("M_XYZ_TO_ACESCG"))
    }

    @Test
    fun cinemaDebayerShaderIsNotDualWiredIntoTheNativeBuild() {
        // Single-shader decision: cinema_debayer must have NO generated SPV header and must NOT be
        // included/referenced as a native symbol (only demosaic_vulkan is the live RAW-preview demosaic).
        assertFalse("cinema_debayer must not be compiled into the native build",
            File("src/main/cpp/generated/cinema_debayer_comp_spv.h").exists())
        assertFalse("cinema_debayer must not be #included in the bridge",
            bridge.contains("cinema_debayer_comp_spv.h"))
        assertFalse("no cinema_debayer SPV symbol may be referenced",
            bridge.contains("kCinemaDebayerCompSpv"))
    }

    @Test
    fun kotlinBridgeExposesTheRawDemosaicControlPath() {
        listOf(
            "fun setRawPreviewDemosaicEnabled(",
            "fun dispatchRawDemosaicPreview(",
            "nativeSetRawPreviewDemosaicEnabled",
            "nativeDispatchRawDemosaicPreview"
        ).forEach { token ->
            assertTrue("VulkanBridge must expose $token", bridgeKt.contains(token))
        }
    }

    @Test
    fun cinemaDebayerPipelineRoutesRawThroughTheDemosaicDispatch() {
        // The RAW path now dispatches demosaic_vulkan; it must no longer fall through to the generic
        // submitHardwareBuffer(format = 0x20) placeholder that never dispatched anything.
        assertTrue(pipelineKt.contains("bridge.dispatchRawDemosaicPreview("))
        assertFalse("RAW path must not use the generic AHB submit placeholder",
            pipelineKt.contains("format = 0x20"))
        // Activation toggles the native gate (gated to RAW+VIDEO via setActive).
        assertTrue(pipelineKt.contains("setRawPreviewDemosaicEnabled(effective)"))
        // CP11 / s1-f09 forward matrices are supplied: the project XYZ(D50)→ACEScg constant second leg
        // (NOT the published D60 ACES matrix) + the per-sensor forward sensor→XYZ(D50) leg.
        assertTrue(pipelineKt.contains("xyzToAcescg = XYZ_D50_TO_ACESCG"))
        assertTrue(pipelineKt.contains("sensorToXyz = descriptor.sensorToXyz"))
    }
}
