package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraDeviceOrchestratorContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraDeviceOrchestrator.kt").readText()
    }

    @Test
    fun `open attempt binds callbacks to immutable requested camera id`() {
        assertTrue(source.contains("val requestedCameraId = host.currentCameraId"))
        assertTrue(source.contains("cam.id != requestedCameraId"))
        assertTrue(source.contains("host.currentCameraId != requestedCameraId"))
        assertTrue(source.contains("openCamera(requestedCameraId"))
    }

    @Test
    fun `non openable selected camera is reselected before retrying open`() {
        assertTrue(source.contains("host.currentCameraId = \"\""))
        assertTrue(source.contains("host.selectCamera()"))
    }
}
