package com.neoproc.neoproccamx.camera

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevents the live NDR exposure loop from silently losing either highlight input again. */
class NdrExposureDataFlowContractTest {

    @Test
    fun `view model forwards one atomic exposure metric frame to camera`() {
        val viewModelSource = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val pipelineSource = File("src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt").readText()
        val call = viewModelSource
            .substringAfter("camera.setHdrExposureSceneMetrics(")
            .substringBefore(")\n")
        val exposureFlow = viewModelSource
            .substringBefore("camera.setHdrExposureSceneMetrics(")
            .substringAfterLast("viewModelScope.launch")

        assertTrue(call.contains("highlightClipFraction"))
        assertTrue(call.contains("redHighlightClipFraction"))
        assertTrue(call.contains("analysisValid"))
        assertTrue(call.contains("measuredAtElapsedRealtimeMs"))
        assertTrue(exposureFlow.contains("pipeline.ndrExposureMetrics.collectLatest"))
        assertTrue(pipelineSource.contains("_ndrExposureMetrics.value = NdrExposureMetrics("))
    }

    @Test
    fun `sensor linear ndr does not request vendor yuv scene analysis`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val request = source
            .substringAfter("private fun analysisFeedRequested(): Boolean")
            .substringBefore("private fun syncPreviewAnalysisFeed")

        assertTrue(request.contains("!isSensorLinearNdrMode()"))
    }

    @Test
    fun `camera exposure governor is explicitly scoped to neo hdr intent`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val sync = source
            .substringAfter("private fun syncCameraHdrDynamicRangeMode()")
            .substringBefore("fun setColorProfile")

        assertTrue(sync.contains("ndrExposureGovernorEnabled = sensorLinearNdr || analysisFeedRequested()"))
        assertTrue(sync.contains("camera.setSensorLinearNdrCaptureEnabled(sensorLinearNdr)"))
    }

    @Test
    fun `sensor linear ndr event joins exact raw metadata on a dedicated worker`() {
        val rawManager = File("src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt").readText()
        val controller = File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
        val idleIngress = rawManager
            .substringAfter("if (!capturing) {")
            .substringBefore("// Frame rate throttle")
        val idleWorker = rawManager
            .substringAfter("private fun processIdleRawPreview(frame: IdleRawPreviewFrame)")
            .substringBefore("private fun publishSensorLinearExposureMetrics")

        assertTrue(rawManager.contains("Thread(runnable, \"RawPreviewCompute\")"))
        assertTrue(rawManager.contains("MAX_UNMATCHED_IDLE_RAW_IMAGES = 3"))
        assertTrue(rawManager.contains("unmatchedIdleRawImages.remove(timestamp)"))
        assertTrue(rawManager.contains("recentResultsByTimestamp.remove(timestamp)"))
        assertTrue(rawManager.contains("paired?.let(::enqueueIdleRawPreview)"))
        assertTrue(rawManager.contains("clearIdleRawPreviewFrames()"))
        assertTrue(!idleIngress.contains("awaitExactCaptureResult("))
        assertTrue(!idleWorker.contains("awaitExactCaptureResult("))
        assertTrue(rawManager.contains("RawSensorExposureAnalyzer.analyze("))
        assertTrue(rawManager.contains("camera.setSensorLinearNdrExposureSceneMetrics("))
        assertTrue(rawManager.contains("reportedExposureCompensationIndex ="))
        assertTrue(controller.contains("reportedExposureCompensationIndex != expectedIndex"))
        val rawMetricMethod = controller
            .substringAfter("override fun setSensorLinearNdrExposureSceneMetrics(")
            .substringBefore("private fun mutateControlState(")
        assertTrue(
            rawMetricMethod.indexOf("lastSensorLinearNdrExposureMetricsElapsedMs =") <
                rawMetricMethod.indexOf("reportedExposureCompensationIndex != expectedIndex")
        )
        assertTrue(rawMetricMethod.contains("scheduleHdrExposureStaleRelease(sensorLinearNdrExposureStops)"))
        assertTrue(controller.contains("MAX_HIGHLIGHT_PROTECT_INDEX_STEP"))
        assertTrue(controller.contains("MIN_REQUEST_INTERVAL_MS"))
        assertTrue(controller.contains("CONTROL_EVALUATION_INTERVAL_MS"))
        assertTrue(controller.contains("NDREXPOSURE source=RAW_SENSOR"))
        assertTrue(controller.contains("SensorLinearNdrExposurePolicy.resolveNextStops("))
        assertTrue(controller.contains("sensorSensitivityUpperIso = isoRange.upper"))
        assertTrue(!controller.contains("sensorSensitivityUpperIso = effectiveIsoRange.upper"))
        assertTrue(rawManager.contains("p99Red = metrics.p99Red"))
        assertTrue(rawManager.contains("p99Green = metrics.p99Green"))
        assertTrue(rawManager.contains("p99Blue = metrics.p99Blue"))
        assertTrue(rawManager.contains("sensorSensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY)"))
        assertTrue(rawManager.contains("noiseS = descriptor.noiseS"))
        assertTrue(rawManager.contains("noiseO = descriptor.noiseO"))
    }
}
