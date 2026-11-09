package com.neoproc.neoproccamx.pipeline

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards RAW-original priority metering and RAW/NDR ownership without a camera device. */
class RawOriginalSoftwareAeMeterContractTest {
    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText()
    }

    private fun managerSource(): String = source(
        "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
    )

    @Test
    fun `camera ingress reserves metadata only before writer admission`() {
        val manager = managerSource()
        val callback = manager
            .substringAfter("fun onRawFrameAvailable(reader: ImageReader)")
            .substringBefore("private fun pairOrHoldSensorLinearNdrRecording")
        val reserve = manager
            .substringAfter("private fun maybeReserveRawOriginalExposureMeter(")
            .substringBefore("private fun cancelRawOriginalExposureMeter(")

        val throttle = callback.indexOf("admitRawFrameTimestamp(captureToken, ts)")
        val reservation = callback.indexOf("maybeReserveRawOriginalExposureMeter(img)")
        val writerBudget = callback.indexOf("RawVideoBackpressurePolicy.shouldRetainAcquiredFrame(")
        assertTrue(throttle >= 0)
        assertTrue(reservation > throttle)
        assertTrue(writerBudget > reservation)
        assertTrue(reserve.contains("softwareAePriorityMeterRequirement()"))
        assertTrue(reserve.contains("MAX_RAW_ORIGINAL_EXPOSURE_METER_JOINS"))
        assertTrue(reserve.contains("recentResultsByTimestamp[join.sensorTimestampNs]"))
        assertFalse(reserve.contains("plane.buffer"))
        assertFalse(reserve.contains("buildRawBufferDescriptor("))
        assertFalse(reserve.contains("image.close()"))
    }

    @Test
    fun `writer samples only after master encode or write and before image release`() {
        val manager = managerSource()
        val writer = manager
            .substringAfter("private fun writeLoop(session: RawWriterSessionToken)")
            .substringBefore("private fun tapRecordingPreview(")
        val complete = manager
            .substringAfter("private fun completeRawOriginalExposureMeterPixels(image: Image)")
            .substringBefore("private fun attachRawOriginalExposureResultLocked(")

        val master = writer.indexOf("when (compressionMode)")
        val sample = writer.indexOf("completeRawOriginalExposureMeterPixels(img)")
        val monitorOrClose = writer.indexOf("tapRecordingPreview(img, session, imageLease)")
        assertTrue(master >= 0)
        assertTrue(sample > master)
        assertTrue(monitorOrClose > sample)
        assertTrue(complete.contains("RawSensorExposureAnalyzer.snapshotCodes("))
        assertTrue(complete.contains("rawOriginalExposureMeterJoins[timestamp]"))
        assertFalse(complete.contains("buildRawBufferDescriptor("))
    }

    @Test
    fun `image first and result first both complete an exact non consuming join`() {
        val manager = managerSource()
        val reserve = manager
            .substringAfter("private fun maybeReserveRawOriginalExposureMeter(")
            .substringBefore("private fun cancelRawOriginalExposureMeter(")
        val attach = manager
            .substringAfter("private fun attachRawOriginalExposureResultLocked(")
            .substringBefore("private fun queueCompletedRawOriginalExposureMeter(")
        val resultCallback = manager
            .substringAfter("fun onCaptureResult(")
            .substringBefore("fun buildRawBufferDescriptor(image: Image)")
        val completed = manager
            .substringAfter("private fun queueCompletedRawOriginalExposureMeter(")
            .substringBefore("private fun queueSensorLinearExposureMetrics(")

        assertTrue(reserve.contains("join.result = it.result"))
        assertFalse(reserve.contains("recentResultsByTimestamp.remove(join.sensorTimestampNs)"))
        assertTrue(attach.contains("join.result = result"))
        assertTrue(attach.contains("join.codeSnapshot != null"))
        assertTrue(resultCallback.contains("attachRawOriginalExposureResultLocked("))
        assertTrue(resultCallback.contains("completedRawExposureJoin?.let("))
        assertTrue(completed.contains("RawSensorExposureAnalyzer.calibrate(codes, descriptor)"))
        assertTrue(completed.contains("sensorTimestampNs = join.sensorTimestampNs"))
        assertTrue(completed.contains("controlRevision = join.controlRevision"))
    }

    @Test
    fun `retired RAW and NDR lifecycles cannot publish analytics`() {
        val manager = managerSource()
        val lifecycle = manager
            .substringAfter("private fun sensorLinearExposureWorkIsCurrent(")
            .substringBefore("private fun scheduleSensorLinearExposureAnalytics()")
        val drain = manager
            .substringAfter("private fun drainSensorLinearExposureAnalytics()")
            .substringBefore("private fun publishSensorLinearExposureMetrics(")

        assertTrue(lifecycle.contains("if (requiresNdrLifecycle)"))
        assertTrue(lifecycle.contains("sensorLinearNdrRecordingLifecycleActive.get()"))
        assertTrue(lifecycle.contains("sensorLinearNdrRecordingGeneration.get()"))
        assertTrue(lifecycle.contains("sensorLinearNdrRecordingSessionGeneration.get()"))
        assertTrue(lifecycle.contains("captureLifecycleActive.get()"))
        assertTrue(lifecycle.contains("isCapturing.get()"))
        assertTrue(lifecycle.contains("rawOriginalExposureAnalyticsGeneration.get()"))
        assertTrue(lifecycle.contains("camera.sessionGeneration.get()"))
        assertTrue(drain.split("sensorLinearExposureWorkIsCurrent(").size >= 3)
    }

    @Test
    fun `RAW start stop destroy serialize and exclude NDR ownership`() {
        val manager = managerSource()
        val start = manager
            .substringAfter("private fun startCaptureLocked()")
            .substringBefore("fun stopCapture()")
        val stop = manager
            .substringAfter("private fun stopCaptureLocked()")
            .substringBefore("fun pauseCapture()")
        val ndrStart = manager
            .substringAfter("internal fun startSensorLinearNdrRecordingIngress(")
            .substringBefore("internal fun requestSensorLinearNdrRecordingIngressTeardown()")

        assertTrue(manager.contains("rawCaptureLifecycleLock.withLock"))
        assertTrue(start.contains("sensorLinearNdrRecordingLifecycleLock.withLock"))
        assertTrue(start.contains("sensorLinearNdrRecordingLifecycleActive.get()"))
        assertTrue(start.contains("captureLifecycleActive.set(true)"))
        assertTrue(ndrStart.contains("captureLifecycleActive.get()"))
        assertTrue(stop.indexOf("quiesceRawCaptureWorkers()") < stop.indexOf("captureLifecycleActive.set(false)"))
        assertTrue(manager.contains("private fun destroyLocked(): Boolean"))
    }

    @Test
    fun `RAW software priority uses exact frame exposure tuple and revision`() {
        val controller = source(
            "src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt"
        )
        val metric = controller
            .substringAfter("override fun setSensorLinearNdrExposureSceneMetrics(")
            .substringBefore("private fun mutateControlState(")
        val priority = controller
            .substringAfter("private fun resolveSoftwareAePriorityDecision(")
            .substringBefore("private fun maybeRefreshSoftwareAePriority()")

        assertTrue(metric.contains("meteredIso = sensorSensitivityIso"))
        assertTrue(metric.contains("meteredExposureTimeNs = exposureTimeNs"))
        assertTrue(metric.contains("sensorTimestampNs = sourceSensorTimestampNs"))
        assertTrue(metric.contains("controlRevision = sourceControlRevision"))
        assertTrue(priority.contains("SENSOR_LINEAR_RAW -> sample?.meteredIso"))
        assertTrue(priority.contains("SENSOR_LINEAR_RAW -> sample?.meteredExposureTimeNs"))
        assertFalse(priority.contains("SENSOR_LINEAR_RAW -> latest?.iso"))
        assertTrue(controller.contains("@Volatile private var manIso"))
        assertTrue(controller.contains("@Volatile private var manExpNs"))
    }
}
