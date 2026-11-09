package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLinearNdrEncoderInputReadFenceContractTest {
    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `only full resolution recorder publication requests an asynchronous input read receipt`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val bridge = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/OffscreenAhbEglEncoderBridge.kt"
        )
        val recorderPublish = runtime
            .substringAfter("private fun publishCompletedSensorLinearNdrRecordingFrame(")
            .substringBefore("private fun enqueueSensorLinearNdrRecordingPreview(")
        val idlePreview = runtime
            .substringAfter("fun submitLatestSensorLinearNdrPreviewFrame(")
            .substringBefore("internal fun submitSensorLinearNdrRecordingPreviewFrame(")

        assertTrue(recorderPublish.contains("inputReadFrame = frame"))
        assertTrue(bridge.contains("submitSensorLinearAcesCgWithInputReadFence("))
        assertTrue(bridge.contains("submitSceneLog10AcesCgWithInputReadFence("))
        assertTrue(idlePreview.contains("submitSensorLinearAcesCg("))
        assertTrue(idlePreview.contains("submitSceneLog10AcesCg("))
        assertFalse(idlePreview.contains("WithInputReadFence"))
    }

    @Test
    fun `irreversible swap commits exact frame receipt before telemetry or lineage`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val submit = runtime
            .substringAfter("private fun submitLatestSensorLinearNdrFrame(")
            .substringBefore("fun submitRawSensorBytes(")

        val nativeSubmit = submit.indexOf("submitSensorLinearAcesCgWithInputReadFence(")
        val receiptCommit = submit.indexOf("sensorLinearNdrEncoderInputReads.commitAccepted(", nativeSubmit)
        val ownershipTransfer = submit.indexOf(
            "transferRawDemosaicCompletionToEncoderRing(",
            receiptCommit
        )
        val lineage = submit.indexOf("emitFrameLineageSubmission(", receiptCommit)
        val capabilityRefresh = submit.indexOf("refreshCapabilitySummary()", receiptCommit)
        val telemetry = submit.indexOf("importStatsAccumulator.recordSuccessfulImport(", receiptCommit)

        assertTrue(nativeSubmit >= 0)
        assertTrue(receiptCommit > nativeSubmit)
        assertTrue(ownershipTransfer > receiptCommit)
        assertTrue(lineage > receiptCommit)
        assertTrue(capabilityRefresh > receiptCommit)
        assertTrue(telemetry > receiptCommit)
    }

    @Test
    fun `recorder reserves before JNI and cancels exact token only before acceptance`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val submit = runtime
            .substringAfter("private fun submitLatestSensorLinearNdrFrame(")
            .substringBefore("fun submitRawSensorBytes(")

        val reserve = submit.indexOf("sensorLinearNdrEncoderInputReads.reserve(")
        val acquire = submit.indexOf("bridge.acquireRawDemosaicEncoderOutput()", reserve)
        val readiness = submit.indexOf("isReadyForAcceptance(ownershipSlot)", acquire)
        val eglSubmit = submit.indexOf("submitSensorLinearAcesCgWithInputReadFence(", readiness)
        val irreversible = submit.indexOf("irreversibleInputReadAccepted = true", eglSubmit)
        val cancelGuard = submit.indexOf(
            "if (ownershipSlot != null && !irreversibleInputReadAccepted)",
            irreversible
        )
        val nativeCancel = submit.indexOf("bridge.cancelRawDemosaicEncoderOutput(it.token)", cancelGuard)
        val kotlinCancel = submit.indexOf(
            "sensorLinearNdrEncoderInputReads.cancelReservation(ownershipSlot)",
            nativeCancel
        )

        assertTrue(reserve >= 0)
        assertTrue(acquire > reserve)
        assertTrue(readiness > acquire)
        assertTrue(eglSubmit > readiness)
        assertTrue(irreversible > eglSubmit)
        assertTrue(cancelGuard > irreversible)
        assertTrue(nativeCancel > cancelGuard)
        assertTrue(kotlinCancel > nativeCancel)
        assertTrue(submit.contains("sensorLinearNdrEncoderInputReads.quarantine("))
    }

    @Test
    fun `fence signal retires every FIFO receipt before its exact RAW token`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val retire = runtime
            .substringAfter("private fun retireSensorLinearNdrEncoderInputReadsLocked(")
            .substringBefore("private fun publishOrRetireSensorLinearNdrRecordingFrameLocked(")

        val poll = retire.indexOf("pollProjectOwnedInputReadFence(")
        val pendingReturn = retire.indexOf("ProjectOwnedInputReadFencePoll.PENDING", poll)
        val gpuCompletion = retire.indexOf("presentationClock.recordGpuCompletion(", pendingReturn)
        val receiptAck = retire.indexOf("acknowledgeProjectOwnedInputReadFence(", pendingReturn)
        val rawAck = retire.indexOf("acknowledgeRawDemosaicEncoderOutput(pending.vulkanToken)", receiptAck)
        val clear = retire.indexOf("sensorLinearNdrEncoderInputReads.retire(pending)", rawAck)

        assertTrue(poll >= 0)
        assertTrue(pendingReturn > poll)
        assertTrue(gpuCompletion > pendingReturn)
        assertTrue(receiptAck > gpuCompletion)
        assertTrue(rawAck > receiptAck)
        assertTrue(clear > rawAck)
        assertTrue(retire.contains("while (sensorLinearNdrEncoderInputReads.outstandingCount != 0)"))
        assertTrue(retire.contains("val drainStartedNs = System.nanoTime()"))
        assertTrue(retire.contains("timeoutNs - elapsedNs"))
    }

    @Test
    fun `full resolution encoder transport uses exact monotonic native tokens`() {
        val native = source("src/main/cpp/vulkan_bridge.cpp")
        val bridge = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt"
        )
        val acquire = native
            .substringAfter("nativeAcquireRawDemosaicEncoderOutputHardwareBuffer(")
            .substringBefore("nativeCancelRawDemosaicEncoderOutput(")
        val cancel = native
            .substringAfter("nativeCancelRawDemosaicEncoderOutput(")
            .substringBefore("nativeAcknowledgeRawDemosaicEncoderOutput(")
        val acknowledge = native
            .substringAfter("nativeAcknowledgeRawDemosaicEncoderOutput(")
            .substringBefore("nativeAcknowledgeRawDemosaicOutput(")

        assertTrue(native.contains("std::array<uint64_t, 2> transportTokens"))
        assertTrue(native.contains("uint64_t nextTransportToken = 1u"))
        assertTrue(acquire.contains("oldestCompletedRawTransportSlot(r)"))
        assertTrue(acquire.contains("AHardwareBuffer_toHardwareBuffer(env, transport)"))
        assertTrue(acquire.contains("env->SetLongArrayRegion(ownershipOut, 0, 2, ownership)"))
        assertTrue(
            acquire.indexOf("r.transportOwnership[slot] = kRawTransportExternallyAcquired") >
                acquire.indexOf("AHardwareBuffer_toHardwareBuffer(env, transport)")
        )
        assertTrue(cancel.contains("cancelRawTransportToken("))
        assertTrue(acknowledge.contains("acknowledgeRawTransportToken("))
        val teardown = native
            .substringAfter("bool destroyRawDemosaicResources(VulkanBridgeState* state) {")
            .substringBefore("bool createRawDemosaicOwnedImage(")
        assertTrue(teardown.contains("for (uint8_t ownership : r.transportOwnership)"))
        assertTrue(teardown.contains("ownership == kRawTransportExternallyAcquired"))
        assertTrue(teardown.contains("return false"))
        assertTrue(bridge.contains("acquireRawDemosaicEncoderOutput()"))
        assertTrue(bridge.contains("cancelRawDemosaicEncoderOutput(token: Long)"))
        assertTrue(bridge.contains("acknowledgeRawDemosaicEncoderOutput(token: Long)"))
    }

    @Test
    fun `Kotlin Vulkan and EGL ownership capacities are exactly two`() {
        val ring = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/EncoderInputReadOwnershipRing.kt"
        )
        val vulkan = source("src/main/cpp/vulkan_bridge.cpp")
        val egl = source("src/main/cpp/offscreen_egl_encoder_bridge.cpp")

        assertTrue(ring.contains("const val CAPACITY = 2"))
        assertTrue(vulkan.contains("std::array<uint8_t, 2> transportOwnership"))
        assertTrue(vulkan.contains("std::array<uint64_t, 2> transportTokens"))
        assertTrue(egl.contains("kInputReadFenceReceiptCapacity = 2"))
    }

    @Test
    fun `saturated native receipt ring uses one bounded exact fence wait`() {
        val native = source("src/main/cpp/offscreen_egl_encoder_bridge.cpp")
        val poll = native
            .substringAfter("jint pollInputReadFenceReceipt(")
            .substringBefore("void drainAndClearInputReadFenceReceipts(")

        assertTrue(native.contains("kSaturatedInputReadFencePollWaitNs = 1'000'000u"))
        assertTrue(poll.contains("timeoutNs == 0u"))
        assertTrue(
            poll.contains(
                "inputReadFenceOutstandingCount(state) == kInputReadFenceReceiptCapacity"
            )
        )
        assertTrue(poll.contains("? kSaturatedInputReadFencePollWaitNs"))
        assertTrue(poll.contains("static_cast<EGLTimeKHR>(effectiveTimeoutNs)"))
        assertTrue(poll.contains("++state->inputReadFenceSaturatedWaits"))
        assertTrue(poll.contains("++state->inputReadFenceSaturatedWaitTimeouts"))
        assertTrue(native.contains("inputReadFenceSaturatedWaitNs="))
        assertTrue(native.contains("inputReadFenceSaturatedWaits="))
        assertTrue(native.contains("inputReadFenceSaturatedWaitTimeouts="))
    }

    @Test
    fun `strict recorder hot path keeps exact counters while sampling observer telemetry`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val native = source("src/main/cpp/offscreen_egl_encoder_bridge.cpp")
        val submit = runtime
            .substringAfter("private fun submitLatestSensorLinearNdrFrame(")
            .substringBefore("fun submitRawSensorBytes(")
        val counterUpdate = submit.indexOf("importStatsAccumulator.recordSuccessfulImport(")
        val publishDecision = submit.indexOf(
            "OffscreenImportStatsPublishPolicy.shouldPublish(",
            counterUpdate
        )
        val stateFlowPublish = submit.indexOf("_importStats.value = statsUpdate.stats", publishDecision)

        assertTrue(counterUpdate >= 0)
        assertTrue(publishDecision > counterUpdate)
        assertTrue(stateFlowPublish > publishDecision)
        assertTrue(submit.contains("imported = submitted"))
        assertTrue(native.contains("const uint64_t committedFrame = state->submittedFrames + 1u"))
        assertTrue(native.contains("committedFrame <= 3u || committedFrame % 25u == 0u"))
        assertTrue(native.contains("RAW_INPUT_READ_FENCE_COMMITTED frame=%llu"))
        assertTrue(native.contains("const uint64_t acknowledgedReceipt = static_cast<uint64_t>(receipt)"))
        assertTrue(native.contains("acknowledgedReceipt <= 3u || acknowledgedReceipt % 25u == 0u"))
    }

    @Test
    fun `two receipt capacity retains incoming image only when both lanes are owned`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val ingress = runtime
            .substringAfter("internal fun submitSensorLinearNdrRecordingImage(")
            .substringBefore("internal fun pollSensorLinearNdrRecordingCompletion(")
        val publish = runtime
            .substringAfter("private fun publishCompletedSensorLinearNdrRecordingFrame(")
            .substringBefore("private fun enqueueSensorLinearNdrRecordingPreview(")
        val poll = runtime
            .substringAfter("internal fun pollSensorLinearNdrRecordingCompletion(")
            .substringBefore("private data class SensorLinearNdrCompletedFrameResolution(")

        assertTrue(ingress.contains("retireSensorLinearNdrEncoderInputReadsLocked(timeoutNs = 0L)"))
        assertTrue(ingress.contains("SensorLinearNdrDemosaicSubmissionStatus.RETRY_PENDING"))
        assertTrue(ingress.contains("Oldest encoder input read is awaiting its exact EGL fence"))
        assertTrue(publish.contains("if (!sensorLinearNdrEncoderInputReads.hasCapacity()) return false"))
        assertTrue(
            poll.indexOf("retireSensorLinearNdrEncoderInputReadsLocked(timeoutNs = 0L)") <
                poll.indexOf("bridge.pollRawDemosaicCompletion()")
        )
        assertTrue(poll.contains("SensorLinearNdrDemosaicCompletionStatus.PENDING"))
    }

    @Test
    fun `closed profile ingress can retire old output but cannot dispatch a new RAW frame`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val submit = runtime
            .substringAfter("internal fun submitSensorLinearNdrRecordingImage(")
            .substringBefore("private fun submitSensorLinearNdrRecordingImageLocked(")
        val poll = runtime
            .substringAfter("internal fun pollSensorLinearNdrRecordingCompletion(")
            .substringBefore("private data class SensorLinearNdrCompletedFrameResolution(")
        val retirement = runtime
            .substringAfter("private fun retireSensorLinearNdrOutputDuringProfileTransition()")
            .substringBefore(
                "private fun publishOrRetireSensorLinearNdrRecordingFrameLocked("
            )

        assertTrue(submit.contains("retireSensorLinearNdrOutputDuringProfileTransition()"))
        assertTrue(poll.contains("retireSensorLinearNdrOutputDuringProfileTransition()"))
        assertTrue(retirement.contains("ndrNativeFrameBoundary.acquireRetirementLease()"))
        assertTrue(
            retirement.contains(
                "retireSensorLinearNdrEncoderInputReadsLocked(timeoutNs = 0L)"
            )
        )
        assertTrue(retirement.contains("bridge.pollRawDemosaicCompletion()"))
        assertTrue(retirement.contains("acknowledgeRawDemosaicCompletion(completed)"))
        assertTrue(retirement.contains("publishDeferredNdrRenderProfileIfRawDrainedLocked()"))
        assertFalse(retirement.contains("cinemaDebayer.submitRawBuffer("))
        assertFalse(retirement.contains("submitLatestSensorLinearNdrFrame("))
    }

    @Test
    fun `surface detach and runtime close fail closed until bounded receipt retirement`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val detach = runtime
            .substringAfter("fun setOffscreenAhbEglEncoderSurface(")
            .substringBefore("fun setPreviewSurface(")
        val close = runtime.substringAfter("override fun close()")

        val detachDrain = detach.indexOf("retireSensorLinearNdrEncoderInputReadsLocked(")
        val nativeDetach = detach.indexOf("setEncoderSurface(null, 0, 0)", detachDrain)
        assertTrue(detachDrain >= 0)
        assertTrue(nativeDetach > detachDrain)
        assertTrue(detach.contains("offscreenEncoderDetachQuarantined = true"))

        val closeDrain = close.indexOf("retireSensorLinearNdrEncoderInputReadsLocked(")
        val quarantine = close.indexOf("if (!encoderInputReadQuiesced)", closeDrain)
        val ownershipRetirement = close.indexOf(
            "rawDemosaicCompletionHandoff.retireAfterNativeRetirementProof()",
            quarantine
        )
        val eglDestroy = close.indexOf(
            "offscreenAhbEglEncoderBridge.closeAcknowledged()",
            quarantine
        )
        val eglDestroyGuard = close.indexOf("if (!offscreenBridgeClosed)", eglDestroy)
        val nativeDestroy = close.indexOf("bridge.closeAcknowledged()", eglDestroyGuard)
        assertTrue(closeDrain >= 0)
        assertTrue(quarantine > closeDrain)
        assertTrue(close.substring(quarantine, ownershipRetirement).contains("return"))
        assertTrue(eglDestroy > quarantine)
        assertTrue(eglDestroyGuard > eglDestroy)
        assertTrue(close.substring(eglDestroyGuard, ownershipRetirement).contains("return"))
        assertTrue(nativeDestroy > eglDestroyGuard)
        assertTrue(ownershipRetirement > nativeDestroy)
    }

    @Test
    fun `native receipt commits only after swap and duplicate lifecycle operations fail closed`() {
        val native = source("src/main/cpp/offscreen_egl_encoder_bridge.cpp")
        val submit = native
            .substringAfter("nativeSubmitHardwareBuffer(")
            .substringBefore("nativePollProjectOwnedInputReadFence(")
        val swap = submit.indexOf("const EGLBoolean swapped = eglSwapBuffers")
        val swapFailure = submit.indexOf("if (swapped != EGL_TRUE)", swap)
        val commit = submit.indexOf("inputReadReceiptSlot->id =", swapFailure)
        val unbind = submit.indexOf("if (!submitEglUnbind.unbind())", commit)

        assertTrue(swap >= 0)
        assertTrue(swapFailure > swap)
        assertTrue(commit > swapFailure)
        assertTrue(submit.substring(swapFailure, commit).contains("eglClientWaitSyncKHR("))
        assertTrue(submit.substring(swapFailure, commit).contains("return JNI_FALSE"))
        assertTrue(unbind > commit)
        assertTrue(submit.indexOf("return JNI_TRUE", unbind) > unbind)

        val acknowledge = native
            .substringAfter("nativeAcknowledgeProjectOwnedInputReadFence(")
            .substringBefore("nativeGetSummary(")
        assertTrue(acknowledge.contains("record == nullptr || !record->signaled"))
        assertTrue(acknowledge.contains("record->sync != EGL_NO_SYNC_KHR"))
        assertTrue(acknowledge.contains("*record = InputReadFenceReceipt{}"))
    }

    @Test
    fun `two pass RAW input ownership ends after decode while direct input ends at final boundary`() {
        val native = source("src/main/cpp/offscreen_egl_encoder_bridge.cpp")
        val submit = native
            .substringAfter("nativeSubmitHardwareBuffer(")
            .substringBefore("nativePollProjectOwnedInputReadFence(")

        val passOne = submit.indexOf("// PASS 1")
        val decodeDraw = submit.indexOf("glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)", passOne)
        val decodeBoundary = submit.indexOf(
            "establishProjectOwnedInputReadBoundary(\"DECODE_INTERMEDIATE\")",
            decodeDraw
        )
        val passTwo = submit.indexOf("// PASS 2", decodeBoundary)
        val finalBoundary = submit.indexOf(
            "establishProjectOwnedInputReadBoundary(\"FINAL_EXTERNAL_READ\")",
            passTwo
        )
        val swap = submit.indexOf("const EGLBoolean swapped = eglSwapBuffers", finalBoundary)

        assertTrue(passOne >= 0)
        assertTrue(decodeDraw > passOne)
        assertTrue(decodeBoundary > decodeDraw)
        assertTrue(passTwo > decodeBoundary)
        assertTrue(finalBoundary > passTwo)
        assertTrue(swap > finalBoundary)
        assertTrue(submit.contains("if (!projectOwnedInput || inputReadBoundaryEstablished) return"))
        assertTrue(submit.contains("boundary=%s"))
    }

    @Test
    fun `recorder transport prewarm failure rolls back cache and decode state transactionally`() {
        val native = source("src/main/cpp/offscreen_egl_encoder_bridge.cpp")
        val prewarm = native
            .substringAfter("nativePrewarmProjectOwnedRawTransport(")
            .substringBefore("nativeSubmitHardwareBuffer(")
        val rollback = prewarm
            .substringAfter("const auto rollback = [&]()")
            .substringBefore("const auto fail =")
        val successCommit = prewarm.substringAfter("Cache eviction is committed only after")

        assertTrue(rollback.contains("state->eglDestroyImageKHR"))
        assertTrue(rollback.contains("state->eglImageCache.erase(inserted)"))
        assertTrue(rollback.contains("state->eglImageCacheOrder.erase("))
        assertTrue(rollback.contains("glDeleteFramebuffers"))
        assertTrue(rollback.contains("glDeleteTextures"))
        assertTrue(rollback.contains("state->decodeTexWidth = 0"))
        assertTrue(rollback.contains("state->decodeHistoryValid = false"))
        assertTrue(rollback.contains("while (glGetError() != GL_NO_ERROR)"))
        assertTrue(successCommit.contains(
            "while (camx::EglImageResidencyPolicy::needsEviction(state->eglImageCacheOrder.size()))"
        ))
        assertTrue(successCommit.indexOf("EglImageResidencyPolicy::needsEviction") <
            successCommit.indexOf("insertedCacheEntry = false"))
        assertTrue(successCommit.contains("insertedCacheEntry = false"))
    }
}
