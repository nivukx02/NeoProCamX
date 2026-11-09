package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLifecyclePolicyTest {

    @Test
    fun `open failure message reports lock timeout first`() {
        assertEquals(
            "Timeout acquiring camera lock",
            CameraLifecyclePolicy.openFailureMessage(
                lockAcquired = false,
                cameraId = "0"
            )
        )
    }

    @Test
    fun `open failure message reports missing camera id`() {
        assertEquals(
            "No camera available",
            CameraLifecyclePolicy.openFailureMessage(
                lockAcquired = true,
                cameraId = ""
            )
        )
    }

    @Test
    fun `open failure message is null when request is valid`() {
        assertNull(
            CameraLifecyclePolicy.openFailureMessage(
                lockAcquired = true,
                cameraId = "0"
            )
        )
    }

    @Test
    fun `retryable camera errors match recoverable camera service failures`() {
        assertTrue(CameraLifecyclePolicy.shouldRetryOnError(CameraDevice.StateCallback.ERROR_CAMERA_IN_USE))
        assertTrue(CameraLifecyclePolicy.shouldRetryOnError(CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE))
        assertTrue(CameraLifecyclePolicy.shouldRetryOnError(CameraDevice.StateCallback.ERROR_CAMERA_DEVICE))
        assertTrue(CameraLifecyclePolicy.shouldRetryOnError(CameraDevice.StateCallback.ERROR_CAMERA_SERVICE))
        assertFalse(CameraLifecyclePolicy.shouldRetryOnError(CameraDevice.StateCallback.ERROR_CAMERA_DISABLED))
    }

    @Test
    fun `reopen plan increments only when prerequisites are satisfied`() {
        val scheduled = CameraLifecyclePolicy.resolveReopenPlan(
            currentAttempts = 0,
            maxAttempts = 1,
            hasPreviewSurface = true,
            hasReadyCallback = true,
            hasErrorCallback = true,
            isClosing = false,
            hasOpenDevice = false
        )
        val blocked = CameraLifecyclePolicy.resolveReopenPlan(
            currentAttempts = 1,
            maxAttempts = 1,
            hasPreviewSurface = true,
            hasReadyCallback = true,
            hasErrorCallback = true,
            isClosing = false,
            hasOpenDevice = false
        )

        assertTrue(scheduled.shouldSchedule)
        assertEquals(1, scheduled.nextAttemptCount)
        assertFalse(blocked.shouldSchedule)
        assertEquals(1, blocked.nextAttemptCount)
    }

    @Test
    fun `next facing toggles between back and front`() {
        assertEquals(
            CameraCharacteristics.LENS_FACING_FRONT,
            CameraLifecyclePolicy.nextFacing(CameraCharacteristics.LENS_FACING_BACK)
        )
        assertEquals(
            CameraCharacteristics.LENS_FACING_BACK,
            CameraLifecyclePolicy.nextFacing(CameraCharacteristics.LENS_FACING_FRONT)
        )
    }

    @Test
    fun `executor based open is used when platform supports it`() {
        assertFalse(CameraLifecyclePolicy.shouldUseExecutorCameraOpen(Build.VERSION_CODES.O_MR1))
        assertTrue(CameraLifecyclePolicy.shouldUseExecutorCameraOpen(Build.VERSION_CODES.P))
        assertTrue(CameraLifecyclePolicy.shouldUseExecutorCameraOpen(Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
    }

    @Test
    fun `camera callbacks are posted only while background delivery is live`() {
        assertTrue(
            CameraLifecyclePolicy.shouldPostCameraCallback(
                callbacksEnabled = true,
                handlerPresent = true,
                threadAlive = true
            )
        )
        assertFalse(
            CameraLifecyclePolicy.shouldPostCameraCallback(
                callbacksEnabled = false,
                handlerPresent = true,
                threadAlive = true
            )
        )
        assertFalse(
            CameraLifecyclePolicy.shouldPostCameraCallback(
                callbacksEnabled = true,
                handlerPresent = false,
                threadAlive = true
            )
        )
        assertFalse(
            CameraLifecyclePolicy.shouldPostCameraCallback(
                callbacksEnabled = true,
                handlerPresent = true,
                threadAlive = false
            )
        )
    }
}
