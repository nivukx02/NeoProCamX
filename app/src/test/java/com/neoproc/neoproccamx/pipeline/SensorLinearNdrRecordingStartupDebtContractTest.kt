package com.neoproc.neoproccamx.pipeline

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLinearNdrRecordingStartupDebtContractTest {
    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText().replace("\r\n", "\n")
    }

    @Test
    fun `sensor-linear codec is proven before exact pair and clip commits before ingress`() {
        val viewModel = source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        val offscreen = viewModel
            .substringAfter("suspend fun startOffscreenAhbEglEncoderRecording(): Boolean")
            .substringBefore("suspend fun startByteBufferP010Recording(): Boolean")
        val begin = offscreen.indexOf(
            "sensorLinearNdrRecordingLifecycle.beginPostSwitchExactPairReadiness()"
        )
        val cameraStart = offscreen.indexOf("camera.startVideoCapture()", begin)
        val preRollStart = offscreen.indexOf(
            "gpuRecording.beginSurfaceEncoderReadinessPreRoll()",
            cameraStart
        )
        val preRollSubmit = offscreen.indexOf(
            "vulkanRuntime.submitOffscreenAhbEglEncoderReadinessFrame()",
            preRollStart
        )
        val preRollReady = offscreen.indexOf(
            "gpuRecording.awaitSurfaceEncoderReadiness()",
            preRollSubmit
        )
        val sessionRefresh = offscreen.indexOf(
            "awaitFreshPreviewSessionBeforeRecording(",
            preRollReady
        )
        val exactPairReady = offscreen.indexOf(
            "sensorLinearNdrRecordingLifecycle.awaitPostSwitchExactPairReadiness()",
            sessionRefresh
        )
        val clipCommit = offscreen.indexOf(
            "gpuRecording.commitSurfaceEncoderReadiness()",
            exactPairReady
        )
        val ingress = offscreen.indexOf(
            "sensorLinearNdrRecordingLifecycle.startIngress()",
            clipCommit
        )

        assertTrue(begin >= 0)
        assertTrue(cameraStart > begin)
        assertTrue(preRollStart > cameraStart)
        assertTrue(preRollSubmit > preRollStart)
        assertTrue(preRollReady > preRollSubmit)
        assertTrue(sessionRefresh > preRollReady)
        assertTrue(exactPairReady > sessionRefresh)
        assertTrue(clipCommit > exactPairReady)
        assertTrue(ingress > clipCommit)
    }

    @Test
    fun `exact idle join publishes readiness and ingress consumes a one-shot lease`() {
        val manager = source("src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt")
        val lifecycle = source(
            "src/main/java/com/neoproc/neoproccamx/ui/SensorLinearNdrRecordingLifecycleCoordinator.kt"
        )
        val camera = source("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")
        val enqueue = manager
            .substringAfter("private fun enqueueIdleRawPreview(frame: IdleRawPreviewFrame)")
            .substringBefore("private fun scheduleIdleRawPreviewWorker()")
        val ingress = manager
            .substringAfter("internal fun startSensorLinearNdrRecordingIngress(")
            .substringBefore("internal fun requestSensorLinearNdrRecordingIngressTeardown()")

        assertTrue(enqueue.contains("sensorLinearNdrRecordingStartupGate.publishExactPair("))
        assertTrue(enqueue.indexOf("if (!accepted)") < enqueue.indexOf("publishExactPair("))
        assertTrue(ingress.contains("SensorLinearNdrRecordingStartupGate.ReadinessLease"))
        assertTrue(ingress.contains("sensorLinearNdrRecordingStartupGate.consume("))
        assertTrue(
            ingress.indexOf("sensorLinearNdrRecordingStartupGate.consume(") <
                ingress.indexOf("sensorLinearNdrRecordingIngressAdmission.set(ingressToken)")
        )
        assertTrue(lifecycle.contains("camera.currentRepeatingReadinessSnapshot()"))
        assertTrue(lifecycle.contains("minimumSensorTimestampNs = firstRepeatingTimestampNs"))
        assertTrue(camera.contains("repeatingPublicationGeneration.updateAndGet"))
        assertTrue(camera.contains("firstFrameProof.getNow(null)"))
    }

    @Test
    fun `startup gate preserves full raster precision cadence and bitrate authorities`() {
        val viewModel = source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        val offscreen = viewModel
            .substringAfter("suspend fun startOffscreenAhbEglEncoderRecording(): Boolean")
            .substringBefore("suspend fun startByteBufferP010Recording(): Boolean")
        val gate = source(
            "src/main/java/com/neoproc/neoproccamx/pipeline/SensorLinearNdrRecordingStartupGate.kt"
        )
        val manager = source(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        )

        assertTrue(offscreen.contains("resolveSensorLinearNdrRawSize(preparedEncoderSize)"))
        assertTrue(offscreen.contains("width = preparedEncoderSize.width"))
        assertTrue(offscreen.contains("height = preparedEncoderSize.height"))
        assertTrue(manager.contains("sensorLinearNdrRecordingTargetFps = targetFps.coerceAtLeast(1)"))
        assertTrue(gate.contains("ExactPairProof"))
        assertTrue(!gate.contains("bitrate"))
        assertTrue(!gate.contains("resize"))
        assertTrue(!gate.contains("downsample"))
    }
}
