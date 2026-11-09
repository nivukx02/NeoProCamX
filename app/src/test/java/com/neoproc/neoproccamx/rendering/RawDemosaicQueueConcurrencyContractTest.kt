package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDemosaicQueueConcurrencyContractTest {
    private fun nativeSource(): String {
        val candidates = listOf(
            File("src/main/cpp/vulkan_bridge.cpp"),
            File("app/src/main/cpp/vulkan_bridge.cpp")
        )
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `optional raw queue is same family and has strict queue zero fallback`() {
        val native = nativeSource()
        val createDevice = native
            .substringAfter("bool createDevice(VulkanBridgeState* state)")
            .substringBefore("bool createGlobalResources(")

        assertTrue(createDevice.contains("requestedGraphicsQueueCount"))
        assertTrue(
            createDevice.contains(
                "queueFamilies[state->queueFamilyIndex].queueCount >= 2u ? 2u : 1u"
            )
        )
        assertTrue(createDevice.contains("queueInfo.queueFamilyIndex = state->queueFamilyIndex"))
        assertTrue(createDevice.contains("queueInfo.queueCount = requestedGraphicsQueueCount"))
        assertTrue(createDevice.contains("queueInfo.queueCount = 1u"))
        assertTrue(
            createDevice.contains(
                "vkGetDeviceQueue(state->device, state->queueFamilyIndex, 1, &state->rawQueue)"
            )
        )
        assertTrue(createDevice.contains("state->rawQueue = state->graphicsQueue"))
        assertFalse(createDevice.contains("dedicatedTransferQueueFamilyIndex, 0, &state->rawQueue"))
    }

    @Test
    fun `raw and graphics queue handles never share different host locks`() {
        val native = nativeSource()
        val submitRaw = native
            .substringAfter("VkResult submitRawQueueSynchronized(")
            .substringBefore("VkResult waitDeviceIdleSynchronized(")
        val waitIdle = native
            .substringAfter("VkResult waitDeviceIdleSynchronized(")
            .substringBefore("bool createImageComputePipeline(")

        assertTrue(submitRaw.contains("if (state->independentRawQueueEnabled)"))
        assertTrue(submitRaw.contains("state->rawQueueMutex"))
        assertTrue(submitRaw.contains("vkQueueSubmit(state->rawQueue"))
        assertTrue(submitRaw.contains("state->queueMutex"))
        assertTrue(submitRaw.contains("vkQueueSubmit(state->graphicsQueue"))
        assertTrue(
            waitIdle.contains("std::scoped_lock queueLocks(state->queueMutex, state->rawQueueMutex)")
        )
    }

    @Test
    fun `raw dispatch remains fence owned and uses only the raw queue helper`() {
        val native = nativeSource()
        val submit = native
            .substringAfter("static RawDispatchSubmitResult submitRawDispatchCommands(")
            .substringBefore("bool prepareRawStorageImageUpload(")
        val poll = native
            .substringAfter("static RawDispatchPollResult pollPendingRawDispatch(")
            .substringBefore("static bool reapPendingRawDispatch(")
        val dispatch = native
            .substringAfter("bool dispatchRawDemosaicPreview(")
            .substringBefore("// RECORD-TIME LIVE MONITOR")

        assertTrue(submit.contains("submitRawQueueSynchronized("))
        assertFalse(submit.contains("vkQueueSubmit(state->graphicsQueue"))
        assertTrue(submit.contains("r.dispatchFence"))
        assertTrue(submit.contains("r.dispatchInFlight = true"))
        assertTrue(poll.contains("vkWaitForFences("))
        assertTrue(poll.contains("finalizeRawDispatch("))
        assertTrue(dispatch.contains("AHardwareBuffer_acquire(hardwareBuffer)"))
        assertTrue(dispatch.contains("r.pendingRawHardwareBuffer = hardwareBuffer"))
    }

    @Test
    fun `recording completion poll coalesces briefly while idle preview stays non blocking`() {
        val native = nativeSource()
        val poll = native
            .substringAfter("static RawDispatchPollResult pollPendingRawDispatch(")
            .substringBefore("static bool reapPendingRawDispatch(")

        assertTrue(native.contains("kRawRecordingCompletionCoalesceWaitNs = 1000000ULL"))
        assertTrue(poll.contains("r.pendingWasRecording"))
        assertTrue(poll.contains("? kRawRecordingCompletionCoalesceWaitNs"))
        assertTrue(poll.contains(": 0u"))
        assertTrue(
            poll.contains(
                "state->device, 1, &r.dispatchFence, VK_TRUE, completionPollWaitNs"
            )
        )
    }
}
