package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HalCalibrationAndTelemetryContractTest {

    @Test
    fun wavefrontCalibrationAlwaysExposesThirtySevenZernikeTermsWithoutVendorClaims() {
        val calibration = CameraWavefrontCalibration.fromPublicOpticsTraits(
            focalLengthMm = 5.2f,
            aperture = 1.8f,
            focusDistanceDiopters = 0.5f,
            rollingShutterSkewNs = 12_000_000L
        )

        assertEquals(37, calibration.zernikeCoefficients.size)
        assertTrue(calibration.source.contains("PUBLIC_CAMERA2_OPTICS"))
        assertTrue(calibration.truthful)
        assertTrue(calibration.signature.contains("zernikeTerms=37"))
    }

    @Test
    fun acesIdtInterpolationUsesPublicCamera2MatrixPairAndClampsWhiteBalance() {
        val tungsten = FloatArray(9) { index -> if (index % 4 == 0) 1f else 0f }
        val daylight = FloatArray(9) { index -> if (index % 4 == 0) 3f else 0f }
        val calibration = DynamicAcesIdtCalibration.fromPublicMatrices(
            transform1 = tungsten,
            transform2 = daylight,
            forward1 = tungsten,
            forward2 = daylight,
            calibration1 = tungsten,
            calibration2 = daylight,
            illuminant1Kelvin = 3_000f,
            illuminant2Kelvin = 7_000f
        )

        assertTrue(calibration.usesPublicCamera2Matrices)
        assertArrayEquals(
            floatArrayOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f),
            calibration.interpolatedSensorToAcesCg(5_000f),
            0.0001f
        )
        assertArrayEquals(tungsten, calibration.interpolatedSensorToAcesCg(2_000f), 0.0001f)
        assertArrayEquals(daylight, calibration.interpolatedSensorToAcesCg(8_000f), 0.0001f)
    }

    @Test
    fun motionTelemetryRingBufferKeepsLatestSamplesWithoutAllocatingOnRead() {
        val buffer = CameraMotionTelemetryRingBuffer(capacity = 3)

        buffer.push(CameraMotionTelemetry(timestampNs = 1L, oisShiftXPixels = 0.1f, oisShiftYPixels = 0.2f, gyroXRadPerSec = 1f, gyroYRadPerSec = 2f, gyroZRadPerSec = 3f))
        buffer.push(CameraMotionTelemetry(timestampNs = 2L, oisShiftXPixels = 0.2f, oisShiftYPixels = 0.3f, gyroXRadPerSec = 2f, gyroYRadPerSec = 3f, gyroZRadPerSec = 4f))
        buffer.push(CameraMotionTelemetry(timestampNs = 3L, oisShiftXPixels = 0.3f, oisShiftYPixels = 0.4f, gyroXRadPerSec = 3f, gyroYRadPerSec = 4f, gyroZRadPerSec = 5f))
        buffer.push(CameraMotionTelemetry(timestampNs = 4L, oisShiftXPixels = 0.4f, oisShiftYPixels = 0.5f, gyroXRadPerSec = 4f, gyroYRadPerSec = 5f, gyroZRadPerSec = 6f))

        assertEquals(4L, buffer.latest()?.timestampNs)
        assertEquals(3, buffer.snapshotNewestFirst(maxSamples = 10).size)
        assertTrue(buffer.signature.contains("lockFreeRingBuffer=true"))
    }

    @Test
    fun fromGyroscopeTagsGyroSourceAndPreservesNumericCapture() {
        // The existing numeric capture must be bit-for-bit unchanged; only the source tag is added.
        val base = CameraMotionTelemetry.DEFAULT.copy(oisShiftXPixels = 1.5f, oisShiftYPixels = -2.5f)
        val s = CameraMotionTelemetry.fromGyroscope(
            base = base,
            timestampNs = 42L,
            gyroXRadPerSec = 0.1f,
            gyroYRadPerSec = 0.2f,
            gyroZRadPerSec = 0.3f,
        )
        assertEquals(42L, s.timestampNs)
        assertEquals(0.1f, s.gyroXRadPerSec, 0f)
        assertEquals(0.2f, s.gyroYRadPerSec, 0f)
        assertEquals(0.3f, s.gyroZRadPerSec, 0f)
        // OIS shift carried over from the prior capture is preserved (unchanged behavior).
        assertEquals(1.5f, s.oisShiftXPixels, 0f)
        assertEquals(-2.5f, s.oisShiftYPixels, 0f)
        assertEquals(CameraMotionTelemetry.Source.GYRO, s.source)
    }

    @Test
    fun defaultTelemetryIsUntaggedSource() {
        assertEquals(CameraMotionTelemetry.Source.UNKNOWN, CameraMotionTelemetry.DEFAULT.source)
    }

    @Test
    fun ringSnapshotGyroOldestFirstFiltersToGyroDomainAndLatestOisFindsOisDomain() {
        val buffer = CameraMotionTelemetryRingBuffer(capacity = 8)
        fun push(ts: Long, source: CameraMotionTelemetry.Source) =
            buffer.push(CameraMotionTelemetry.DEFAULT.copy(timestampNs = ts, source = source))
        // Interleave the two clock domains exactly the way the live ring multiplexes them.
        push(1L, CameraMotionTelemetry.Source.GYRO)
        push(2L, CameraMotionTelemetry.Source.OIS_RESULT)
        push(3L, CameraMotionTelemetry.Source.GYRO)
        push(4L, CameraMotionTelemetry.Source.OIS_RESULT)
        push(5L, CameraMotionTelemetry.Source.GYRO)

        // Gyro-domain view is oldest-first and excludes the OIS-clock samples (single clock domain).
        val gyroOnly = buffer.snapshotGyroOldestFirst(10)
        assertEquals(listOf(1L, 3L, 5L), gyroOnly.map { it.timestampNs })
        gyroOnly.forEach { assertEquals(CameraMotionTelemetry.Source.GYRO, it.source) }

        // Latest OIS-domain sample carries the live STATISTICS_OIS displacement for fusion.
        assertEquals(4L, buffer.latestOisSample()?.timestampNs)
    }
}
