package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionRecoveryPolicyTest {

    @Test
    fun `resets conservative fallback when preview surface is gone but analysis surfaces still exist`() {
        assertTrue(
            CameraSessionRecoveryPolicy.shouldResetConservativeFallback(
                conservativeSessionFallback = true,
                previewSurfaceValid = false,
                hardwareImportSurfaceAvailable = true,
                yuvSurfaceAvailable = false
            )
        )
    }

    @Test
    fun `does not reset conservative fallback when preview surface is still valid`() {
        assertFalse(
            CameraSessionRecoveryPolicy.shouldResetConservativeFallback(
                conservativeSessionFallback = true,
                previewSurfaceValid = true,
                hardwareImportSurfaceAvailable = true,
                yuvSurfaceAvailable = true
            )
        )
    }

    @Test
    fun `does not reset conservative fallback when no analysis surfaces are available`() {
        assertFalse(
            CameraSessionRecoveryPolicy.shouldResetConservativeFallback(
                conservativeSessionFallback = true,
                previewSurfaceValid = false,
                hardwareImportSurfaceAvailable = false,
                yuvSurfaceAvailable = false
            )
        )
    }
}
