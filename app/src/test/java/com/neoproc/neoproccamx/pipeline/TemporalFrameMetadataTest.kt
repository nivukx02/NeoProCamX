package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalFrameMetadataTest {
    @Test
    fun imuAndOrientationInterpolateAtExposureMidpointAndThermalUsesTimestamp() {
        val associator = TemporalMetadataAssociator(
            imuToleranceNs = 50L,
            orientationToleranceNs = 50L,
            thermalToleranceNs = 100L
        )
        associator.addGyroscope(TimedVector3Sample(100L, 0f, 2f, 4f))
        associator.addGyroscope(TimedVector3Sample(120L, 2f, 4f, 6f))
        associator.addAccelerometer(TimedVector3Sample(100L, 10f, 0f, 0f))
        associator.addAccelerometer(TimedVector3Sample(120L, 20f, 0f, 0f))
        associator.addOrientation(TimedOrientationSample(100L, 0f, 0f, 0f, 1f, 0))
        associator.addOrientation(TimedOrientationSample(120L, 0f, 0f, 1f, 0f, 90))
        associator.addThermal(
            TimedThermalSample(105L, PipelineThermalState.MODERATE, platformStatus = 2)
        )

        val associated = associator.associate(
            identity = FrameIdentity(1L, 1L, 8L, 100L, FrameIdentitySource.CAMERA2_CAPTURE_RESULT),
            cameraMetadata = cameraMetadata(exposureStartNs = 100L, exposureDurationNs = 20L),
            clockAlignment = CameraImuClockAlignment.fromCameraCharacteristics(
                sensorTimestampSource = 1,
                measuredCameraToImuOffsetNs = null
            ),
            frameArrivalMonotonicNs = 110L
        )

        assertEquals(110L, associated.associationTimestampNs)
        assertTrue(associated.gyroscope!!.interpolated)
        assertEquals(1f, associated.gyroscope.value.x, 0.0001f)
        assertEquals(15f, associated.accelerometer!!.value.x, 0.0001f)
        assertTrue(associated.orientation!!.interpolated)
        assertEquals(5L, associated.thermalAssociationDeltaNs)
        assertEquals(PipelineThermalState.MODERATE, associated.thermal!!.state)
    }

    @Test
    fun outOfToleranceImuIsNotAttachedToFrame() {
        val associator = TemporalMetadataAssociator(
            imuToleranceNs = 5L,
            orientationToleranceNs = 5L,
            thermalToleranceNs = 5L
        )
        associator.addGyroscope(TimedVector3Sample(1L, 1f, 1f, 1f))

        val associated = associator.associate(
            FrameIdentity(1L, 1L, null, 100L, FrameIdentitySource.IMAGE_TIMESTAMP),
            cameraMetadata = null,
            clockAlignment = CameraImuClockAlignment.fromCameraCharacteristics(
                sensorTimestampSource = 1,
                measuredCameraToImuOffsetNs = null
            ),
            frameArrivalMonotonicNs = 100L
        )

        assertNull(associated.gyroscope)
        assertNull(associated.accelerometer)
        assertNull(associated.thermal)
        assertNotNull(associated.associationTimestampNs)
    }

    private fun cameraMetadata(exposureStartNs: Long, exposureDurationNs: Long) = FrameCameraMetadata(
        exposureStartTimestampNs = exposureStartNs,
        exposureDurationNs = exposureDurationNs,
        frameDurationNs = 40L,
        rollingShutterSkewNs = 10L,
        iso = 100,
        aeState = null,
        flashState = null,
        whiteBalance = FrameWhiteBalanceMetadata(
            null, null, null, null, null, null, null, null, null
        ),
        focus = FrameFocusMetadata(null, null, null, null),
        lens = FrameLensMetadata(null, null, null, null, null),
        calibration = null
    )
}
