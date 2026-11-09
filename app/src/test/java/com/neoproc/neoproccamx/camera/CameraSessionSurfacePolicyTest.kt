package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionSurfacePolicyTest {

    @Test
    fun `session policy returns null when no valid surfaces exist`() {
        val plan = CameraSessionSurfacePolicy.resolve(
            previewSurface = null,
            hardwareImportSurface = null,
            yuvSurface = null,
            jpegSurface = null,
            rawSurface = null,
            recordSurface = null,
            rawVideoCaptureEnabled = false
        )

        assertNull(plan)
    }

    @Test
    fun `analysis uses hardware import when preview surface absent`() {
        assertTrue(
            CameraSessionSurfacePolicy.prefersDedicatedHardwareImportAnalysis(
                previewSurfacePresent = false,
                hardwareImportSurfacePresent = true
            )
        )
    }

    @Test
    fun `analysis uses yuv when direct preview is present`() {
        assertFalse(
            CameraSessionSurfacePolicy.prefersDedicatedHardwareImportAnalysis(
                previewSurfacePresent = true,
                hardwareImportSurfacePresent = true
            )
        )
    }

    @Test
    fun `analysis uses yuv when no hardware import exists`() {
        assertFalse(
            CameraSessionSurfacePolicy.prefersDedicatedHardwareImportAnalysis(
                previewSurfacePresent = false,
                hardwareImportSurfacePresent = false
            )
        )
    }
}
