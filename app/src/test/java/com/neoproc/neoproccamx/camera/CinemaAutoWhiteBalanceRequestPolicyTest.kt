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
            manualTransformReady = true,
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
    fun `unavailable awb lock uses the measured fixed white point when awb off is supported`() {
        val plan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = -1,
            manualTint = 0,
            kelvinRange = CameraIntRange(2_000, 12_000),
            availableAwbModes = intArrayOf(
                CaptureRequest.CONTROL_AWB_MODE_AUTO,
                CaptureRequest.CONTROL_AWB_MODE_OFF
            ),
            supportsCctWhiteBalance = true,
            awbLocked = true,
            awbLockAvailable = false,
            requireLockableAutoWhiteBalance = true,
            shotStableFallbackKelvin = 4_300,
            shotStableFallbackTint = 18
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_OFF, plan.awbMode)
        assertFalse(plan.awbLock)
        assertEquals(CameraMetadata.COLOR_CORRECTION_MODE_CCT, plan.colorCorrectionMode)
        assertEquals(4_300, plan.colorTemperatureKelvin)
        assertEquals(6, plan.colorTint)
    }

    @Test
    fun `unavailable awb lock never emits an unsupported lock request`() {
        val plan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = -1,
            manualTint = 0,
            kelvinRange = CameraIntRange(2_000, 12_000),
            availableAwbModes = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO),
            supportsCctWhiteBalance = false,
            awbLocked = true,
            awbLockAvailable = false,
            requireLockableAutoWhiteBalance = true
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, plan.awbMode)
        assertFalse(plan.awbLock)
        assertNull(plan.colorCorrectionMode)
        assertNull(plan.colorTemperatureKelvin)
    }

    @Test
    fun `manual white balance uses cct temperature and tint when supported`() {
        val plan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = 12_500,
            manualTint = 220,
            kelvinRange = CameraIntRange(1_700, 10_000),
            availableAwbModes = intArrayOf(
                CaptureRequest.CONTROL_AWB_MODE_AUTO,
                CaptureRequest.CONTROL_AWB_MODE_OFF
            ),
            supportsCctWhiteBalance = true,
            awbLocked = false
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_OFF, plan.awbMode)
        assertEquals(CameraMetadata.COLOR_CORRECTION_MODE_CCT, plan.colorCorrectionMode)
        assertEquals(10_000, plan.colorTemperatureKelvin)
        assertEquals(50, plan.colorTint)
        assertFalse(plan.useTransformMatrix)
    }

    @Test
    fun `manual white balance falls back to transform matrix when cct is unavailable`() {
        val plan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = 3_200,
            manualTint = -40,
            kelvinRange = CameraIntRange(2_000, 12_000),
            availableAwbModes = intArrayOf(
                CaptureRequest.CONTROL_AWB_MODE_AUTO,
                CaptureRequest.CONTROL_AWB_MODE_OFF
            ),
            supportsCctWhiteBalance = false,
            manualTransformReady = true,
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
            availableAwbModes = intArrayOf(
                CaptureRequest.CONTROL_AWB_MODE_AUTO,
                CaptureRequest.CONTROL_AWB_MODE_OFF
            ),
            supportsCctWhiteBalance = true,
            manualTransformReady = true,
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

    @Test
    fun `manual white balance request never emits unsupported awb off`() {
        val plan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = 4_300,
            manualTint = 12,
            kelvinRange = CameraIntRange(2_000, 12_000),
            availableAwbModes = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO),
            supportsCctWhiteBalance = true,
            manualTransformReady = true,
            awbLocked = false
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, plan.awbMode)
        assertNull(plan.colorCorrectionMode)
        assertNull(plan.colorTemperatureKelvin)
        assertNull(plan.colorTint)
    }

    @Test
    fun `malformed off-only inventory never creates an incomplete off request`() {
        val plan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = 0,
            manualTint = 0,
            kelvinRange = CameraIntRange(2_000, 12_000),
            availableAwbModes = intArrayOf(CaptureRequest.CONTROL_AWB_MODE_OFF),
            supportsCctWhiteBalance = false,
            manualTransformReady = false,
            awbLocked = false
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, plan.awbMode)
        assertNull(plan.colorCorrectionMode)
        assertNull(plan.colorTemperatureKelvin)
    }

    @Test
    fun `still override locks only supported auto white balance`() {
        assertTrue(
            CinemaAutoWhiteBalanceRequestPolicy.shouldApplyStillCaptureAutoLock(
                requested = true,
                awbLockAvailable = true,
                resolvedAwbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
        )
        assertFalse(
            CinemaAutoWhiteBalanceRequestPolicy.shouldApplyStillCaptureAutoLock(
                requested = true,
                awbLockAvailable = false,
                resolvedAwbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
        )
        assertFalse(
            CinemaAutoWhiteBalanceRequestPolicy.shouldApplyStillCaptureAutoLock(
                requested = true,
                awbLockAvailable = true,
                resolvedAwbMode = CaptureRequest.CONTROL_AWB_MODE_OFF
            )
        )
    }

    @Test
    fun `transform white balance requires a complete prevalidated payload`() {
        val plan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = 3_200,
            manualTint = -20,
            kelvinRange = CameraIntRange(2_000, 12_000),
            availableAwbModes = intArrayOf(
                CaptureRequest.CONTROL_AWB_MODE_AUTO,
                CaptureRequest.CONTROL_AWB_MODE_OFF
            ),
            supportsCctWhiteBalance = false,
            manualTransformReady = false,
            awbLocked = false
        )

        assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, plan.awbMode)
        assertNull(plan.colorCorrectionMode)
        assertNull(plan.colorTemperatureKelvin)
        assertFalse(plan.useTransformMatrix)
    }

    @Test
    fun `api 36 cct tint maps the full app range onto platform range`() {
        assertEquals(-50, CinemaAutoWhiteBalanceRequestPolicy.mapToPlatformCctTint(-150))
        assertEquals(-25, CinemaAutoWhiteBalanceRequestPolicy.mapToPlatformCctTint(-75))
        assertEquals(0, CinemaAutoWhiteBalanceRequestPolicy.mapToPlatformCctTint(0))
        assertEquals(25, CinemaAutoWhiteBalanceRequestPolicy.mapToPlatformCctTint(75))
        assertEquals(50, CinemaAutoWhiteBalanceRequestPolicy.mapToPlatformCctTint(150))
        assertEquals(50, CinemaAutoWhiteBalanceRequestPolicy.mapToPlatformCctTint(Int.MAX_VALUE))
    }

    @Test
    fun `missing calibration ccm rejects the entire transform payload`() {
        val incomplete = DngWhiteBalanceMath.DeviceWhiteBalance(
            rGain = 1.8f,
            bGain = 1.4f,
            colorCorrectionTransform = null
        )
        val transform = CinemaManualWhiteBalanceTransformPolicy.fromDeviceCalibration(incomplete)
        val plan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = 4_300,
            manualTint = 10,
            kelvinRange = CameraIntRange(2_000, 12_000),
            availableAwbModes = intArrayOf(
                CaptureRequest.CONTROL_AWB_MODE_AUTO,
                CaptureRequest.CONTROL_AWB_MODE_OFF
            ),
            supportsCctWhiteBalance = false,
            manualTransformReady = transform != null,
            awbLocked = false
        )

        assertNull(transform)
        assertEquals(CaptureRequest.CONTROL_AWB_MODE_AUTO, plan.awbMode)
        assertNull(plan.colorCorrectionMode)
    }

    @Test
    fun `nonfinite or malformed calibration rejects the entire transform payload`() {
        val nonfinite = DngWhiteBalanceMath.DeviceWhiteBalance(
            rGain = Float.NaN,
            bGain = 1.4f,
            colorCorrectionTransform = FloatArray(9) { 1f }
        )
        val malformed = DngWhiteBalanceMath.DeviceWhiteBalance(
            rGain = 1.8f,
            bGain = 1.4f,
            colorCorrectionTransform = FloatArray(8) { 1f }
        )

        assertNull(CinemaManualWhiteBalanceTransformPolicy.fromDeviceCalibration(nonfinite))
        assertNull(CinemaManualWhiteBalanceTransformPolicy.fromDeviceCalibration(malformed))
    }

    @Test
    fun `complete finite calibration is retained as one transform payload`() {
        val matrix = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
        val transform = CinemaManualWhiteBalanceTransformPolicy.fromDeviceCalibration(
            DngWhiteBalanceMath.DeviceWhiteBalance(
                rGain = 1.8f,
                bGain = 1.4f,
                colorCorrectionTransform = matrix
            )
        )
        val resolved = requireNotNull(transform)

        assertEquals(1.8f, resolved.redGain, 0f)
        assertEquals(1.4f, resolved.blueGain, 0f)
        assertTrue(resolved.colorCorrectionTransform.contentEquals(matrix))
        assertTrue(resolved.colorCorrectionTransform !== matrix)
    }

    @Test
    fun `measurement provenance rejects stale session and different camera`() {
        val provenance = WhiteBalanceMeasurementProvenance(
            cameraId = "0",
            sessionGeneration = 42L,
            frameNumber = 100L,
            sensorTimestampNs = 123_000L
        )

        assertTrue(provenance.isCurrent("0", 42L))
        assertFalse(provenance.isCurrent("0", 43L))
        assertFalse(provenance.isCurrent("1", 42L))
    }

    @Test
    fun `measurement provenance rejects regressing frames and sensor time`() {
        val previous = WhiteBalanceMeasurementProvenance(
            cameraId = "0",
            sessionGeneration = 42L,
            frameNumber = 100L,
            sensorTimestampNs = 10_000L
        )

        assertFalse(previous.copy(frameNumber = 99L).isNewerThan(previous))
        assertFalse(previous.copy().isNewerThan(previous))
        assertFalse(
            previous.copy(frameNumber = 101L, sensorTimestampNs = 9_999L).isNewerThan(previous)
        )
        assertTrue(
            previous.copy(frameNumber = 101L, sensorTimestampNs = 10_001L).isNewerThan(previous)
        )
        assertTrue(
            previous.copy(sessionGeneration = 43L, frameNumber = 1L).isNewerThan(previous)
        )
    }
}
