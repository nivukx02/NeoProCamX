package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraDeviceOrchestratorContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraDeviceOrchestrator.kt").readText()
    }
    private val controllerSource: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
    }

    @Test
    fun `open attempt binds callbacks to immutable requested camera id`() {
        assertTrue(source.contains("val requestedCameraId = host.currentCameraId"))
        assertTrue(source.contains("cam.id != requestedCameraId"))
        assertTrue(source.contains("host.currentCameraId != requestedCameraId"))
        assertTrue(source.contains("openAttemptGate.isCurrent(openAttempt)"))
        assertTrue(source.contains("openCamera(requestedCameraId"))
    }

    @Test
    fun `controller retains one orchestrator across open close and delayed retry callbacks`() {
        assertTrue(controllerSource.contains("private val cameraDeviceOrchestrator by lazy"))
        assertTrue(controllerSource.contains("cameraDeviceOrchestrator.openCamera(onReady, onError)"))
        assertTrue(controllerSource.contains("cameraDeviceOrchestrator.closeCamera()"))
        assertTrue(!controllerSource.contains("CameraDeviceOrchestrator(this, openLock).openCamera"))
        assertTrue(!controllerSource.contains("CameraDeviceOrchestrator(this, openLock).closeCamera"))
        assertTrue(source.contains("openAttemptGate.releaseOrphanedPermit()"))
    }

    @Test
    fun `close timeout cannot crash or strand the lifecycle in closing`() {
        assertTrue(source.contains("val releaseAcknowledged = try"))
        assertTrue(source.contains("Camera2 close acknowledgement timed out; release remains best-effort"))
        assertTrue(!source.contains("check(host.awaitCameraRelease"))
        assertTrue(source.contains("host.transitionCameraState(CameraState.Closed, \"close-camera-complete\")"))
    }

    @Test
    fun `non openable selected camera is reselected before retrying open`() {
        assertTrue(source.contains("host.currentCameraId = \"\""))
        assertTrue(source.contains("host.selectCamera()"))
    }

    @Test
    fun `terminal service outage waits for one real camera availability signal`() {
        assertTrue(source.contains("host.armCameraAvailabilityRecovery(message)"))
        assertTrue(source.contains("host.clearCameraAvailabilityRecovery()"))
        assertTrue(controllerSource.contains("CameraManager.AvailabilityCallback()"))
        assertTrue(controllerSource.contains("registerAvailabilityCallback(cameraAvailabilityCallback, handler)"))
        assertTrue(controllerSource.contains("cameraAvailabilityRecoveryArmed.compareAndSet(true, false)"))
        assertTrue(controllerSource.contains("Camera became available after service outage; reopening once"))
    }

    @Test
    fun `device loss invalidates the closed session before reopening the same camera id`() {
        assertTrue(source.contains("host.prepareCameraSessionForDeviceRecovery()"))
        val recoveryBlock = controllerSource.substringAfter("override fun prepareCameraSessionForDeviceRecovery()")
            .substringBefore("override fun beginCameraRelease()")
        assertTrue(recoveryBlock.contains("advanceSessionGeneration(\"camera-device-recovery\")"))
        assertTrue(recoveryBlock.contains("captureSession = null"))
        assertTrue(recoveryBlock.contains("clearSessionConfigurationFingerprints()"))
        assertTrue(recoveryBlock.contains("previewBuilder = null"))
        assertTrue(recoveryBlock.contains("recordingActive"))
    }

    @Test
    fun `intentional close cancels and unregisters availability recovery`() {
        assertTrue(source.contains("host.clearCameraAvailabilityRecovery()"))
        assertTrue(controllerSource.contains("unregisterCameraAvailabilityCallback()"))
        assertTrue(controllerSource.contains("removeCallbacks(cameraAvailabilityRecoveryRunnable)"))
    }
}
