package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDemosaicSubmissionContractTest {
    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `ahb raw upload and demosaic share one queue submission`() {
        val native = source("src/main/cpp/vulkan_bridge.cpp")
        val prepare = native
            .substringAfter("bool prepareRawStorageImageUpload(")
            .substringBefore("bool recordPreparedRawStorageImageUpload(")
        val dispatch = native
            .substringAfter("bool dispatchRawDemosaicPreview(")
            .substringBefore("// RECORD-TIME LIVE MONITOR")

        assertFalse(prepare.contains("endSingleUseCommands("))
        assertFalse(prepare.contains("vkQueueWaitIdle("))
        assertTrue(dispatch.contains("recordPreparedRawStorageImageUpload("))
        assertTrue(
            dispatch.indexOf("recordPreparedRawStorageImageUpload(") <
                dispatch.indexOf("vkCmdDispatch(")
        )
        assertFalse(dispatch.contains("endSingleUseCommands("))
        assertFalse(dispatch.contains("vkQueueWaitIdle("))
        assertTrue(dispatch.split("submitRawDispatchCommands(state, completionWaitNs)").size - 1 == 1)
        assertTrue(
            dispatch.indexOf("vkCmdDispatch(") <
                dispatch.indexOf("submitRawDispatchCommands(state, completionWaitNs)")
        )
    }

    @Test
    fun `recording polls immediately while idle preview retains a bounded wait`() {
        val native = source("src/main/cpp/vulkan_bridge.cpp")
        val bridge = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt"
        )
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )

        assertTrue(native.contains("static_cast<uint64_t>(completionWaitNs),\n            kRawPreviewGpuDeadlineNs"))
        assertTrue(bridge.contains("DEFAULT_RAW_DEMOSAIC_COMPLETION_WAIT_NS = 25_000_000L"))
        assertTrue(runtime.contains("completionWaitNs = 0L"))
        assertTrue(runtime.contains("completionWaitNs = VulkanBridge.DEFAULT_RAW_DEMOSAIC_COMPLETION_WAIT_NS"))
    }

    @Test
    fun `monitor readback is skipped only for exact strict ten bit preview ownership`() {
        val native = source("src/main/cpp/vulkan_bridge.cpp")
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val dispatch = native
            .substringAfter("bool dispatchRawDemosaicPreview(")
            .substringBefore("// RECORD-TIME LIVE MONITOR")
        val strictOwnerProof = native
            .substringAfter("static bool strictTenBitPreviewTransportOwnsFrame(")
            .substringBefore("static int reserveFreeRawPreviewTransportSlot(")
        val reserve = native
            .substringAfter("static int reserveFreeRawPreviewTransportSlot(")
            .substringBefore("static void abandonReservedRawPreviewTransportSlot(")
        val previewRecord = native
            .substringAfter("static bool recordRawPreviewTransport(")
            .substringBefore("static bool recordRawMonitorReadback(")
        val strictFallback = dispatch
            .substringAfter(
                "if (previewTransportSlot >= 0 && !strictTenBitPreviewTransportAcceptedForFrame)"
            )
            .substringBefore("const bool doReadback")

        assertTrue(dispatch.contains("state->rawDirectNdrPreviewTransportActive"))
        assertTrue(strictOwnerProof.contains("r.dispatchPreviewTransportSlot != slot"))
        assertTrue(strictOwnerProof.contains("kRawTransportVulkanInFlight"))
        assertTrue(strictOwnerProof.contains("r.previewTransportTokens[index] == 0u"))
        assertTrue(strictOwnerProof.contains("r.previewTransportAhbs[index] == nullptr"))
        assertTrue(strictOwnerProof.contains("AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT"))
        assertTrue(strictOwnerProof.contains("AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM"))
        assertTrue(reserve.contains("nextRawPreviewTransportToken(r)"))
        assertTrue(
            previewRecord.indexOf("r.previewSceneLogDescriptorSet == VK_NULL_HANDLE") <
                previewRecord.indexOf("vkCmdDispatch(cmd, groupsX, groupsY, 1u)")
        )
        assertTrue(dispatch.contains("strictTenBitPreviewTransportAcceptedForFrame"))
        assertTrue(dispatch.contains("strictTenBitPreviewTransportOwnsFrame(r, previewTransportSlot)"))
        assertTrue(strictFallback.contains("abandonReservedRawPreviewTransportSlot"))
        assertFalse(strictFallback.contains("return false"))
        assertTrue(
            dispatch.contains(
                "!strictTenBitPreviewTransportAcceptedForFrame &&\n        recordRawMonitorReadback("
            )
        )
        assertTrue(runtime.contains("val directPreview = prepared && sensorLinearNdrPreviewActive"))
        assertTrue(
            runtime.contains(
                "sensorLinearNdrDemosaicOwnerPhase == SensorLinearNdrDemosaicOwnerPhase.RECORDING"
            )
        )
        assertTrue(runtime.contains("recordingActive == appliedRawRecordingDemosaicActive"))
        assertTrue(native.contains("if (state->rawRecordingDemosaicActive) return;"))
        assertTrue(runtime.contains("val outputWidth = output.width"))
        assertTrue(runtime.contains("val outputHeight = output.height"))
        val extentGuard = runtime
            .substringAfter("if (outputWidth != width || outputHeight != height)")
            .substringBefore("}")
        assertTrue(extentGuard.contains("SensorLinearNdrEncoderSubmission(submitted = false)"))
    }

    @Test
    fun `ordinary idle raw is bounded without weakening full sensor recording ownership`() {
        val native = source("src/main/cpp/vulkan_bridge.cpp")
        val dispatch = native
            .substringAfter("bool dispatchRawDemosaicPreview(")
            .substringBefore("// RECORD-TIME LIVE MONITOR")

        assertTrue(dispatch.contains("if (state->rawRecordingDemosaicActive) return;"))
        assertTrue(dispatch.contains("constexpr int kRawIdlePreviewLongEdge = 1536;"))
        assertTrue(dispatch.contains("state->rawDirectNdrPreviewTransportActive ||"))
        assertTrue(dispatch.contains("!state->rawOutputAhbStorageUnsupported ||"))
        assertTrue(dispatch.contains("previewTargetLongEdge = kRawIdlePreviewLongEdge;"))
        assertTrue(dispatch.contains("constexpr int kRawMonitorLongEdge = 1024;"))
        assertTrue(dispatch.contains("previewTargetLongEdge = kRawMonitorLongEdge;"))
        assertTrue(
            dispatch.indexOf("if (state->rawRecordingDemosaicActive) return;") <
                dispatch.indexOf("previewTargetLongEdge = kRawIdlePreviewLongEdge;")
        )
    }
}
