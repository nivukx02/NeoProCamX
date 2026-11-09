package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaAutoWhiteBalanceRequestPolicyTest {

    @Test
    fun `auto white balance uses hal auto mode without manual color correction`() {
        val plan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = -1,
            manualTint = 0,
            kelvinRange = CameraIntRange(1_700, 10_000),
            availableAwbModes = intArrayOf(
                CaptureRequest.CONTROL_AWB_MODE_AUTO,
                CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            ),
            supportsCctWhiteBalance = true,
            awbLocked = false
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, plan.awbMode)
        assertFalse(plan.awbLock)
        assertNull(plan.colorCorrectionMode)
        assertNull(plan.colorTemperatureKelvin)
        assertNull(plan.colorTint)
        assertFalse(plan.useTransformMatrix)
    }

    @Test
    fun `auto white balance falls back to daylight preset only when auto is unavailable`() {
        val plan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = -1,
            manualTint = 0,
            kelvinRange = CameraIntRange(1_700, 10_000),
            availableAwbModes = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
            supportsCctWhiteBalance = false,
            awbLocked = false
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, plan.awbMode)
        assertFalse(plan.awbLock)
    }

    @Test
    fun `shot stable normal sdr uses fixed transform when lockable auto awb is unavailable`() {
        val plan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = -1,
            manualTint = 0,
            kelvinRange = CameraIntRange(1_700, 10_000),
            availableAwbModes = intArrayOf(
                CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
                CaptureRequest.CONTROL_AWB_MODE_OFF
            ),
            supportsCctWhiteBalance = false,
            awbLocked = true,
            requireLockableAutoWhiteBalance = true,
            shotStableFallbackKelvin = 5_600,
            shotStableFallbackTint = -12
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_OFF, plan.awbMode)
        assertFalse(plan.awbLock)
        assertEquals(CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX, plan.colorCorrectionMode)
        assertEquals(5_600, plan.colorTemperatureKelvin)
        assertEquals(-12, plan.colorTint)
        assertTrue(plan.useTransformMatrix)
    }

    @Test
    fun `shot stable normal sdr keeps preset fallback if awb off is not advertised`() {
        val plan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = -1,
            manualTint = 0,
            kelvinRange = CameraIntRange(1_700, 10_000),
            availableAwbModes = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
            supportsCctWhiteBalance = false,
            awbLocked = true,
            requireLockableAutoWhiteBalance = true
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, plan.awbMode)
        assertFalse(plan.awbLock)
        assertNull(plan.colorCorrectionMode)
        assertNull(plan.colorTemperatureKelvin)
        assertFalse(plan.useTransformMatrix)
    }

    @Test
    fun `manual white balance uses cct temperature and tint when supported`() {
        val plan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = 12_500,
            manualTint = 220,
            kelvinRange = CameraIntRange(1_700, 10_000),
            availableAwbModes = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO),
            supportsCctWhiteBalance = true,
            awbLocked = false
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_OFF, plan.awbMode)
        assertEquals(CameraMetadata.COLOR_CORRECTION_MODE_CCT, plan.colorCorrectionMode)
        assertEquals(10_000, plan.colorTemperatureKelvin)
        assertEquals(150, plan.colorTint)
        assertFalse(plan.useTransformMatrix)
    }

    @Test
    fun `manual white balance falls back to transform matrix when cct is unavailable`() {
        val plan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = 3_200,
            manualTint = -40,
            kelvinRange = CameraIntRange(2_000, 12_000),
            availableAwbModes = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO),
            supportsCctWhiteBalance = false,
            awbLocked = false
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_OFF, plan.awbMode)
        assertEquals(CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX, plan.colorCorrectionMode)
        assertEquals(3_200, plan.colorTemperatureKelvin)
        assertEquals(-40, plan.colorTint)
        assertTrue(plan.useTransformMatrix)
    }

    @Test
    fun `manual white balance falls back to transform matrix below cct api even when capability is true`() {
        val plan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = 4_800,
            manualTint = 20,
            kelvinRange = CameraIntRange(2_000, 12_000),
            availableAwbModes = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO),
            supportsCctWhiteBalance = true,
            awbLocked = false,
            sdkInt = 35
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_OFF, plan.awbMode)
        assertEquals(CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX, plan.colorCorrectionMode)
        assertEquals(4_800, plan.colorTemperatureKelvin)
        assertEquals(20, plan.colorTint)
        assertTrue(plan.useTransformMatrix)
    }

    @Test
    fun `hdr safety mode forces auto awb and disables manual red gain transform`() {
        val plan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = 2_400,
            manualTint = 150,
            kelvinRange = CameraIntRange(1_700, 10_000),
            availableAwbModes = intArrayOf(
                CaptureRequest.CONTROL_AWB_MODE_AUTO,
                CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            ),
            supportsCctWhiteBalance = false,
            awbLocked = true,
            forceAutoForHdr = true
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, plan.awbMode)
        assertFalse(plan.awbLock)
        assertNull(plan.colorCorrectionMode)
        assertNull(plan.colorTemperatureKelvin)
        assertFalse(plan.useTransformMatrix)
    }
}
