package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Niv99ImageIngressHotPathContractTest {
    private val appRoot = listOf(File("."), File("app"))
        .first { File(it, "src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").isFile }

    @Test
    fun `P010 reader callback performs no redundant synchronized NIV99 metadata lookup`() {
        val cameraController = File(
            appRoot,
            "src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt"
        ).readText()
        val yuvIngress = cameraController
            .substringAfter("private fun dispatchYuvReady(")
            .substringBefore("private fun dispatchHardwareReady(")

        assertTrue(yuvIngress.contains("onYuvFrame?.invoke(reader)"))
        assertFalse(yuvIngress.contains("copyTuningForImage("))
    }

    @Test
    fun `metadata lane performs one route-specific join before the independent Vulkan lane`() {
        val pipeline = File(
            appRoot,
            "src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt"
        ).readText()
        val preparation = pipeline
            .substringAfter("private fun prepareNiv99TuningForHardwareFrame(")
            .substringBefore("private fun scheduleHardwareGpuDispatchDrain()")
        val gpuLane = pipeline
            .substringAfter("private fun submitPreparedHardwareFrame(")
            .substringBefore("private fun markHardwareFrameNotSubmitted(")

        assertEquals(1, Regex("awaitTuningForImage\\(").findAll(preparation).count())
        assertEquals(1, Regex("copyTuningForImage\\(").findAll(preparation).count())
        assertTrue(preparation.contains("return if (recordingPath)"))
        assertTrue(preparation.contains("timeoutNs = runtimeNiv99ExactMetadataWaitNs"))
        assertTrue(gpuLane.contains("preparedTuning = slot.tuning"))
        assertTrue(gpuLane.contains("preparedLookup = slot.metadataLookup"))
        assertFalse(gpuLane.contains("awaitTuningForImage("))
        assertFalse(gpuLane.contains("copyTuningForImage("))
        assertTrue(pipeline.contains("Niv99ExactFrameJoinPolicy.isSamePhysicalFrame("))
        assertTrue(pipeline.contains("metadataAlignedSensorTimestampNs"))
    }
}
