package com.neoproc.neoproccamx.pipeline

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorTimestampPipelineIntegrationContractTest {
    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `one canonical timestamp owns registry pacing tuning and Vulkan submission`() {
        val pipeline = source(
            "src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt"
        )
        val enqueue = pipeline
            .substringAfter("private fun enqueueHardwareImage(image: Image)")
            .substringBefore("private fun releaseHardwareQueueMembership")
        val preparation = pipeline
            .substringAfter("private fun prepareNiv99TuningForHardwareFrame(")
            .substringBefore("private fun scheduleHardwareGpuDispatchDrain()")
        val trackedSubmit = pipeline
            .substringAfter("preparedLookup: Niv99MetadataLookupStatus")
            .substringBefore("private fun submitImageToVulkan(")
        val nativeSubmit = pipeline
            .substringAfter("private fun submitImageToVulkan(")
            .substringBefore("private fun strictNoQualityDowngradeActive()")

        assertTrue(enqueue.contains("val rawTimestampNs"))
        assertTrue(enqueue.contains("sensorTimestampTimebase.normalizeToReference("))
        assertTrue(enqueue.contains("sensorTimestampNs = timestampNs"))
        assertTrue(enqueue.contains("hardwareFrameDispatchQueues.offer("))
        assertTrue(enqueue.contains("sensorTimestampNs = timestampNs"))
        assertTrue(preparation.contains("sensorTimestampNs = identity.sensorTimestampNs"))
        assertTrue(trackedSubmit.contains("identity.sensorTimestampNs"))
        assertTrue(nativeSubmit.contains("canonicalSensorTimestampNs = canonicalSensorTimestampNs"))
    }

    @Test
    fun `session reset and capture reference precede image normalization`() {
        val pipeline = source(
            "src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt"
        )
        val begin = pipeline
            .substringAfter("fun beginCameraSession(")
            .substringBefore("fun setCameraCalibration(")
        val capture = pipeline
            .substringAfter("fun onCaptureResult(")
            .substringBefore("fun onNiv99CaptureResult(")

        assertTrue(begin.contains("sensorTimestampTimebase.reset("))
        assertTrue(begin.contains("SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME"))
        assertTrue(capture.contains("sensorTimestampTimebase.observeReference("))
        assertTrue(
            capture.indexOf("sensorTimestampTimebase.observeReference(") <
                capture.indexOf("sensorFramePipeline.onCaptureResult(")
        )
    }
}
