package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RawRecordingStageTelemetryContractTest {
    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `native stage timing is fence correlated and recording only`() {
        val native = source("src/main/cpp/vulkan_bridge.cpp")
        val finalize = native
            .substringAfter("static bool finalizeRawDispatch(")
            .substringBefore("static RawDispatchPollResult pollPendingRawDispatch(")
        val dispatch = native
            .substringAfter("bool dispatchRawDemosaicPreview(")
            .substringBefore("// RECORD-TIME LIVE MONITOR")

        assertTrue(finalize.contains("if (r.pendingWasRecording)"))
        assertTrue(finalize.contains("completionObserved - r.pendingQueueSubmitted"))
        assertTrue(finalize.contains("completionObserved - r.pendingDispatchCycleStarted"))
        assertTrue(finalize.contains("RAW_RECORD_STAGE_TIMING"))
        assertTrue(finalize.contains("collectRawGpuStageTiming(state)"))
        assertTrue(finalize.contains("gpuDemosaicMs="))
        assertTrue(finalize.contains("gpuPreviewMs="))
        assertTrue(dispatch.contains("r.pendingInputPrepareMs = elapsedMsSince(inputPrepareStarted)"))
        assertTrue(dispatch.contains("r.pendingCommandBuildMs = elapsedMsSince(commandBuildStarted)"))
        assertTrue(dispatch.contains("r.pendingWasRecording = state->rawRecordingDemosaicActive"))
        assertTrue(dispatch.contains("vkCmdResetQueryPool"))
        assertTrue(dispatch.contains("RawGpuTimestampPoint::UploadComplete"))
        assertTrue(dispatch.contains("RawGpuTimestampPoint::DemosaicComplete"))
        assertTrue(dispatch.contains("RawGpuTimestampPoint::PreviewComplete"))
        assertTrue(dispatch.contains("RawGpuTimestampPoint::DispatchComplete"))
        assertTrue(native.contains("results are read only after dispatchFence has signaled"))
    }

    @Test
    fun `ingress reports bounded ownership growth without changing admission`() {
        val manager = source(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        )
        val highWater = manager
            .substringAfter("private fun recordSensorLinearNdrOwnedHighWatermark(")
            .substringBefore("private fun releaseSensorLinearNdrOwnershipReservation(")

        assertTrue(highWater.contains("NDR_RECORD_INGRESS_HIGH_WATER"))
        assertTrue(highWater.contains("sensorLinearNdrRecordingOwnedFrameLimit.get()"))
        assertTrue(highWater.contains("sensorLinearNdrRecordingQueue.size"))
        assertTrue(highWater.contains("sensorLinearNdrRecordingNativePending.get()"))
        assertTrue(manager.contains("SensorLinearNdrRecordingIngressPolicy.tryReserveOwnedFrame("))
    }
}
