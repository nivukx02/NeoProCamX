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
    private val runtimeKt: String =
        File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt").readText()

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
        assertTrue("RAW demosaic must have readiness independent of secondary enhancement groups",
            bridge.contains("std::atomic_bool rawDemosaicPipelineReady{false};"))
        assertTrue("dispatch must acquire-load rawDemosaicPipelineReady before reading handles",
            bridge.contains("rawDemosaicPipelineReady.load(std::memory_order_acquire)"))
        assertTrue("build must release-publish the completed demosaic handles",
            bridge.contains("state->rawDemosaicPipelineReady.store(") &&
                bridge.contains("std::memory_order_release"))
        val rawDispatch = bridge
            .substringAfter("bool dispatchRawDemosaicPreview(")
            .substringBefore("// RECORD-TIME LIVE MONITOR")
        assertFalse(
            "RAW demosaic dispatch must not depend on the QFCA/Omni aggregate gate",
            rawDispatch.contains("qfcaPipelinesReady.load")
        )
        // Storage-feasibility gates (fail-closed -> YUV fallback): imported AHB feature, owned-image feature,
        // the enabled device feature, and the feature-guarded demosaic pipeline build.
        assertTrue(bridge.contains("VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT"))
        assertTrue(bridge.contains("shaderStorageImageExtendedFormats"))
        assertTrue(bridge.contains("storageImageExtendedFormatsEnabled"))
        assertTrue("demosaic pipeline build must be guarded by the storage-extended-formats feature",
            bridge.contains("if (state->storageImageExtendedFormatsEnabled) {"))
    }

    @Test
    fun stagingUploadResourcesArePersistentAndNeverDestroyedByThePerFrameGuard() {
        // Unsupported camera RAW AHB storage import is a stream capability result. Cache it so the
        // fallback does not repeat expensive failed Vulkan imports for every sensor frame.
        assertTrue(bridge.contains("!r.rawAhbStorageImportUnsupported"))
        assertTrue(bridge.contains("r.rawAhbStorageImportUnsupported = true;"))

        // The staging image/view/memory belong to RawDemosaicResources and survive frame dispatches.
        // Only a successful transient AHB import is owned by the per-frame RAII guard.
        assertTrue(bridge.contains("rawGuard{ state, rawImage, rawMemory, rawView, rawImported }"))
        assertTrue(bridge.contains("if (!owns) return;"))
        assertTrue(bridge.contains("destroyImg(r.uploadImage, r.uploadMemory, r.uploadView)"))
    }

    @Test
    fun idleRawAndNdrUseBoundedCfaExtentWhileActiveRecordingKeepsTheFullResolutionMaster() {
        // A prepared surface is not an active full-resolution consumer. Both idle RAW preview paths
        // are bounded; active recording returns before either preview-only extent can be selected.
        assertTrue(bridge.contains("bool rawOutputAhbStorageUnsupported = false;"))
        assertTrue(bridge.contains("bool rawSceneLogTransportUnsupported = false;"))
        assertTrue(bridge.contains("bool rawRecordingDemosaicActive = false;"))
        assertTrue(bridge.contains("if (state->rawRecordingDemosaicActive) return;"))
        assertTrue(bridge.contains("constexpr int kRawIdlePreviewLongEdge = 1536;"))
        assertTrue(bridge.contains("state->rawDirectNdrPreviewTransportActive ||"))
        assertTrue(bridge.contains("!state->rawOutputAhbStorageUnsupported ||"))
        assertTrue(bridge.contains("state->rawSceneLogTransportPrepared &&"))
        assertTrue(bridge.contains("!state->rawSceneLogTransportUnsupported))"))
        assertTrue(bridge.contains("previewTargetLongEdge = kRawIdlePreviewLongEdge;"))
        assertTrue(bridge.contains("constexpr int kRawMonitorLongEdge = 1024;"))
        assertTrue(bridge.contains("previewTargetLongEdge = kRawMonitorLongEdge;"))
        assertTrue(bridge.contains("sourceLongEdge + previewTargetLongEdge - 1"))
        assertTrue(bridge.contains("width / (2 * monitorDecimate)"))
        assertTrue(bridge.contains("height / (2 * monitorDecimate)"))

        // 4096x3072 with a 1536 target selects integer decimate=3 and an even 1364x1024 CFA extent.
        val decimate = (4096 + 1536 - 1) / 1536
        val processingWidth = (4096 / (2 * decimate)) * 2
        val processingHeight = (3072 / (2 * decimate)) * 2
        assertTrue(decimate == 3)
        assertTrue(processingWidth == 1364 && processingHeight == 1024)
        assertTrue(processingWidth % 2 == 0 && processingHeight % 2 == 0)
        assertTrue(processingWidth <= 1536)

        // FP16 remains the working master; the recording fallback is a separate full-resolution
        // RGB10_A2 scene-log image and is emitted only while submission is enabled.
        assertTrue(bridge.contains("createRawDemosaicSceneLogTransport"))
        assertTrue(bridge.contains("recordRawSceneLogTransport"))
        assertTrue(bridge.contains("AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM"))
        assertTrue(bridge.contains("if (!state->rawSceneLogTransportSubmissionEnabled ||"))
        assertTrue(runtimeKt.contains("sensorLinearNdrDemosaicOwnerPhase == SensorLinearNdrDemosaicOwnerPhase.RECORDING"))
        assertTrue(runtimeKt.contains("recordingActive == appliedRawRecordingDemosaicActive"))

        // The last-resort monitor gather reads identical Bayer phases with center-weighted integer
        // coefficients; it is neither a crop nor point-decimation.
        assertTrue(bridge.contains("sourceBlockX + phaseX + 2 * bx"))
        assertTrue(bridge.contains("sourceBlockY + phaseY + 2 * by"))
        assertTrue(bridge.contains("weightedSum +="))
        assertTrue(bridge.contains("tentWeights"))
        assertTrue(bridge.contains("monitorDecimate == 1 && !r.rawAhbStorageImportUnsupported"))

        val dispatch = bridge
            .substringAfter("bool dispatchRawDemosaicPreview(")
            .substringBefore("// RECORD-TIME LIVE MONITOR")
        assertTrue(
            "processing extent must be resolved before the resource cache is ensured",
            dispatch.indexOf("resolveProcessingExtent();") <
                dispatch.indexOf("ensureRawDemosaicResources(state, processingWidth, processingHeight)")
        )
        assertFalse(
            "a known monitor-only device must not recreate full sensor resources every frame",
            dispatch.contains("ensureRawDemosaicResources(state, width, height)")
        )
    }

    @Test
    fun vulkanOwnedExportPrecedesAndroidAllocatorImportWithoutCpuReadback() {
        assertTrue(bridge.contains("PFN_vkGetMemoryAndroidHardwareBufferANDROID getMemoryAndroidHardwareBuffer"))
        assertTrue(bridge.contains("VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT"))
        assertTrue(bridge.contains("VkExportMemoryAllocateInfo exportInfo"))
        assertTrue(bridge.contains("VkMemoryDedicatedAllocateInfo dedicated"))
        assertTrue(bridge.contains("vkGetMemoryAndroidHardwareBufferANDROID(rawVulkanExport)"))

        val outputFactory = bridge
            .substringAfterLast("bool createRawDemosaicExportableOutput(")
            .substringBefore("bool createRawDemosaicSceneLogTransport(")
        assertTrue(
            "Vulkan-owned FP16 export must be attempted before allocator-import fallback",
            outputFactory.indexOf("createVulkanExportedStorageImage(") in
                0 until outputFactory.indexOf("AHardwareBuffer_allocate")
        )
        assertTrue(outputFactory.contains("VK_FORMAT_R16G16B16A16_SFLOAT"))

        val exportFactory = bridge
            .substringAfter("bool createVulkanExportedStorageImage(")
            .substringBefore("// STAGE 2: allocate an rgba16f")
        assertTrue(exportFactory.contains("VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT"))
        assertFalse("strict RAW export must remain zero-copy", exportFactory.contains("AHardwareBuffer_lock"))
    }

    @Test
    fun demosaicShaderRemainsTheEliteCp14Cp11Math() {
        val shader = File("src/main/cpp/shaders/vulkan/demosaic_vulkan.comp").readText()
        // WB-before-demosaic (CP14): the host precomposes WB/(white-black) once per frame, and the
        // shader applies that exact scale while filling the calibrated CFA tile before reconstruction.
        assertTrue(
            bridge.contains(
                "ubo.calibrationScaleRggb[i] = whiteBalanceRggb[i] / denominator;"
            )
        )
        val shaderMain = shader.substringAfter("void main()")
        assertTrue(shader.contains("uniforms.calibrationScaleRggb[channel]"))
        assertTrue(
            shaderMain.indexOf("calibratedTile[tileY][tileX] =") in
                0 until shaderMain.indexOf("demosaicRgbFromTile(")
        )
        // Hard-gradient edge-directed green (CP14): directional selection, not a soft blend. The
        // optimized shader expresses the same two comparisons as one compact conditional value.
        assertTrue(shader.contains("float green = h < v ? gH : (v < h ? gV : gA);"))
        // ACEScg forward matrices (CP11 / s1-f09) are composed once per frame in the exact
        // XYZ->ACEScg * sensor->XYZ order, then applied per pixel without changing the transform.
        assertTrue(shader.contains("M_SENSOR_TO_XYZ"))
        assertTrue(shader.contains("M_XYZ_TO_ACESCG"))
        assertTrue(
            bridge.contains(
                "multiplyRawDemosaicMatrices(mXyzToAcescg, mSensorToXyz, sensorToAcescgFast)"
            )
        )
        assertTrue(shader.contains("uniforms.M_SENSOR_TO_ACESCG_FAST * sensorRgb"))
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
        assertTrue(pipelineKt.contains("val sensorToXyz = shaderSensorToXyz(descriptor)"))
        assertTrue(pipelineKt.contains("sensorToXyz = sensorToXyz"))
    }

    @Test
    fun cpuReadbackPreviewIsNotSuppressedByOptionalEncoderAhbExport() {
        val submit = runtimeKt.replace("\r\n", "\n")
            .substringAfter("fun submitRawSensorImage(")
            .substringBefore("private fun publishCompletedRawDemosaicFrame(")

        assertTrue(submit.contains("var encoderDelivered ="))
        assertTrue(submit.contains("val imageConsumed ="))
        assertTrue(submit.contains("publishCompletedRawDemosaicFrame("))
        assertTrue(Regex("""imageConsumed\n\s+}""").containsMatchIn(submit))
        assertFalse(submit.contains("return encoderDelivered"))
    }
}
