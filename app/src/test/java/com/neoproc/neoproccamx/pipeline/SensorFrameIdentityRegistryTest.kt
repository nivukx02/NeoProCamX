package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorFrameIdentityRegistryTest {
    @Test
    fun `recording transition retains bounded prior session records for late mux commits`() {
        val registry = SensorFrameRegistry(
            associator = TemporalMetadataAssociator(),
            monotonicClockNs = { 1_000_000_000L }
        )
        registry.beginSession(7L)
        val recordingFrame = registry.recordImage(900_000_000L)

        registry.beginSession(8L, preserveRetiredSessionRecords = true)

        assertSame(recordingFrame, registry.find(900_000_000L))
        assertEquals(8L, registry.activeSessionGeneration())
    }

    @Test
    fun `ordinary preview transition clears prior session records`() {
        val registry = SensorFrameRegistry(
            associator = TemporalMetadataAssociator(),
            monotonicClockNs = { 1_000_000_000L }
        )
        registry.beginSession(7L)
        registry.recordImage(900_000_000L)

        registry.beginSession(8L)

        assertNull(registry.find(900_000_000L))
        assertEquals(8L, registry.activeSessionGeneration())
    }

    @Test
    fun imageFirstThenExactCaptureResultPromotesSamePersistentRecord() {
        var nowNs = 1_000_000L
        val registry = SensorFrameRegistry(
            associator = TemporalMetadataAssociator(),
            monotonicClockNs = { nowNs }
        )
        val generation = registry.beginSession()
        val imageRecord = registry.recordImage(sensorTimestampNs = 10_000_000L)

        assertFalse(imageRecord.identity.snapshot().isHardwareAuthoritative)

        nowNs += 1_000L
        val captureRecord = registry.registerCaptureMetadata(
            capturedMetadata(
                generation = generation,
                frameNumber = 44L,
                sensorTimestampNs = 10_000_000L
            )
        )

        assertSame(imageRecord, captureRecord)
        assertTrue(captureRecord.identity.snapshot().isHardwareAuthoritative)
        assertEquals(44L, captureRecord.identity.snapshot().frameNumber)
        assertEquals(10_000_000L, captureRecord.identity.snapshot().sensorTimestampNs)
        assertEquals(0L, registry.identityConflictCount())
    }

    @Test
    fun differentSensorTimestampNeverUsesNearestCaptureResult() {
        val registry = SensorFrameRegistry(
            associator = TemporalMetadataAssociator(),
            monotonicClockNs = { 1L }
        )
        val generation = registry.beginSession()
        val capture = registry.registerCaptureMetadata(
            capturedMetadata(generation, frameNumber = 1L, sensorTimestampNs = 100_000_000L)
        )
        val image = registry.recordImage(sensorTimestampNs = 100_000_001L)

        assertFalse(capture === image)
        assertFalse(image.identity.snapshot().isHardwareAuthoritative)
        assertEquals(100_000_001L, image.identity.snapshot().sensorTimestampNs)
    }

    @Test
    fun minimalIdentityRegistrationPromotesImageBeforeDeferredMetadataArrives() {
        val registry = SensorFrameRegistry(
            associator = TemporalMetadataAssociator(),
            monotonicClockNs = { 1L }
        )
        val generation = registry.beginSession()
        val image = registry.recordImage(sensorTimestampNs = 300_000_000L)
        val identity = FrameIdentity(
            sessionGeneration = generation,
            ingressSequence = 9L,
            frameNumber = 77L,
            sensorTimestampNs = 300_000_000L,
            source = FrameIdentitySource.CAMERA2_CAPTURE_RESULT
        )

        val promoted = registry.registerCaptureIdentity(identity)

        assertSame(image, promoted)
        assertTrue(promoted.identity.snapshot().isHardwareAuthoritative)
        assertEquals(77L, promoted.identity.snapshot().frameNumber)
        assertEquals(null, promoted.cameraMetadata())

        registry.registerCaptureMetadata(
            capturedMetadata(generation, frameNumber = 77L, sensorTimestampNs = 300_000_000L)
        )
        assertEquals(200, promoted.cameraMetadata()?.iso)
        assertEquals(0L, registry.identityConflictCount())
    }

    private fun capturedMetadata(
        generation: Long,
        frameNumber: Long,
        sensorTimestampNs: Long
    ) = CapturedFrameMetadata(
        identity = FrameIdentity(
            sessionGeneration = generation,
            ingressSequence = 2L,
            frameNumber = frameNumber,
            sensorTimestampNs = sensorTimestampNs,
            source = FrameIdentitySource.CAMERA2_CAPTURE_RESULT
        ),
        camera = FrameCameraMetadata(
            exposureStartTimestampNs = sensorTimestampNs,
            exposureDurationNs = 10_000_000L,
            frameDurationNs = 33_333_333L,
            rollingShutterSkewNs = 8_000_000L,
            iso = 200,
            aeState = 2,
            flashState = 0,
            whiteBalance = FrameWhiteBalanceMetadata(
                awbMode = 1,
                awbState = 2,
                awbLocked = false,
                redGain = 2f,
                greenEvenGain = 1f,
                greenOddGain = 1f,
                blueGain = 1.7f,
                colorTransform = null,
                neutralColorPoint = null
            ),
            focus = FrameFocusMetadata(4, 2, 0.5f, 0),
            lens = FrameLensMetadata("0", 6.7f, 1.8f, 0f, 1),
            calibration = null
        )
    )
}
