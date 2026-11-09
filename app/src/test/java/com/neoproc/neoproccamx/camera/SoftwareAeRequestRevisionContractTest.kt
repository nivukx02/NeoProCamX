package com.neoproc.neoproccamx.camera

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the Android-dependent wiring around the behavior-tested exact revision ledger. */
class SoftwareAeRequestRevisionContractTest {
    private fun source(path: String): String {
        val candidates = listOf(File(path), File("app/$path"))
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `every repeating request is frozen through exact revision tracking`() {
        val controller = source("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")
        val submissions = Regex("setRepeatingRequest\\(([^,]+),")
            .findAll(controller)
            .map { it.groupValues[1].trim() }
            .toList()

        assertTrue(submissions.isNotEmpty())
        assertTrue(submissions.all { it == "request" || it == "repeatingRequest" })
        assertTrue(controller.contains("buildSoftwareAeTrackedRequest(builder)"))
        assertTrue(controller.contains("softwareAeRequestRevisions.record(request, controlRevision)"))
        assertTrue(controller.contains("softwareAeControlRevisionFor(result.request)"))
        assertFalse(controller.contains("setRepeatingRequest(builder.build()"))
    }

    @Test
    fun `raw and ndr use result request revision instead of image callback state`() {
        val manager = source("src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt")
        val rawCompletion = manager
            .substringAfter("private fun queueCompletedRawOriginalExposureMeter(")
            .substringBefore("private fun queueSensorLinearExposureMetrics(")
        val ndrQueue = manager
            .substringAfter("private fun queueSensorLinearExposureMetrics(\n        image: Image")
            .substringBefore("private fun queueSensorLinearExposureMetrics(\n        snapshot:")
        val idlePublish = manager
            .substringAfter("private fun publishSensorLinearExposureMetrics(")
            .substringBefore("fun onCaptureResult(")

        assertTrue(manager.contains("join.controlRevision = camera.softwareAeControlRevisionFor(result)"))
        assertTrue(rawCompletion.contains("join.controlRevision < 0L"))
        assertTrue(ndrQueue.contains("controlRevision = camera.softwareAeControlRevisionFor(result)"))
        assertTrue(idlePublish.contains("sourceControlRevision = camera.softwareAeControlRevisionFor(result)"))
        assertFalse(ndrQueue.contains("softwareAePriorityMeterRequirement().controlRevision"))
        assertFalse(idlePublish.contains("softwareAePriorityMeterRequirement().controlRevision"))
    }

    @Test
    fun `unknown sensor linear revision cannot gain software exposure authority`() {
        val controller = source("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")
        val acceptance = controller
            .substringAfter("private fun acceptSoftwareAeSceneSample(")
            .substringBefore("private fun scheduleSoftwareAePriorityStaleRelease(")

        assertTrue(acceptance.contains("sample.domain == SoftwareAeSignalDomain.SENSOR_LINEAR_RAW"))
        assertTrue(acceptance.contains("sample.controlRevision < 0L"))
        assertTrue(acceptance.contains("sample.controlRevision != requirement.controlRevision"))
    }

    @Test
    fun `api 36 priority values stay guarded without inlined api references`() {
        val controller = source("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")

        assertTrue(controller.contains("private const val AE_PRIORITY_MODE_OFF = 0"))
        assertTrue(controller.contains("private const val AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY = 1"))
        assertTrue(controller.contains("private const val AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY = 2"))
        assertFalse(controller.contains("CameraMetadata.CONTROL_AE_PRIORITY_MODE_OFF"))
        assertFalse(controller.contains("CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_"))
    }
}
