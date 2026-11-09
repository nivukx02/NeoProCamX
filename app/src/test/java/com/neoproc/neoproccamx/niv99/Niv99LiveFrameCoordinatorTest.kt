package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Niv99LiveFrameCoordinatorTest {
    @Test
    fun `exact lookup rejects an unconfigured or different timeline`() {
        val ring = Niv99FrameMetadataRing(capacity = 4)
        val output = Niv99LiveFrameMetadata()

        assertEquals(
            Niv99MetadataLookupStatus.NOT_CONFIGURED,
            ring.copyExact(CAMERA_ID, GENERATION, 40_000_000L, output)
        )
        assertTrue(ring.beginTimeline(CAMERA_ID, GENERATION))
        assertTrue(ring.publish(capture(1L), calibration(), measuredNoise(), configuration()))

        assertEquals(
            Niv99MetadataLookupStatus.WRONG_TIMELINE,
            ring.copyExact("other-camera", GENERATION, 40_000_000L, output)
        )
        assertEquals(
            Niv99MetadataLookupStatus.WRONG_TIMELINE,
            ring.copyExact(CAMERA_ID, GENERATION + 1L, 40_000_000L, output)
        )
        assertEquals(
            Niv99MetadataLookupStatus.NOT_FOUND,
            ring.copyExact(CAMERA_ID, GENERATION, 80_000_000L, output)
        )
    }

    @Test
    fun `published metadata is a deep copy and keeps one atomic frame payload`() {
        val ring = Niv99FrameMetadataRing(capacity = 4, lensShadingCapacity = 16)
        val capture = capture(frameNumber = 7L, lensShadingCapacity = 16).apply {
            halRedGain = 1.75f
            halBlueGain = 1.28f
            sensor.lensShadingAvailable = true
            sensor.lensShadingColumns = 2
            sensor.lensShadingRows = 2
            sensor.lensShadingRggb.fill(1.25f)
        }
        val calibration = calibration(lensShadingCapacity = 16).apply {
            assertTrue(setLensShadingMap(FloatArray(16) { 1.25f }, columns = 2, rows = 2))
        }
        val config = configuration().apply {
            userControls.manualColorMatrix[0] = 0.91f
        }
        val output = Niv99LiveFrameMetadata(lensShadingCapacity = 16)
        assertTrue(ring.beginTimeline(CAMERA_ID, GENERATION))
        assertTrue(ring.publish(capture, calibration, measuredNoise(), config))

        capture.halRedGain = 7f
        capture.halColorMatrix[0] = 7f
        capture.sensor.lensShadingRggb[0] = 7f
        calibration.blackLevel[0] = 7f
        assertTrue(calibration.setLensShadingMap(FloatArray(16) { 7f }, columns = 2, rows = 2))
        config.userControls.manualColorMatrix[0] = 7f

        assertEquals(
            Niv99MetadataLookupStatus.MATCHED,
            ring.copyExact(CAMERA_ID, GENERATION, timestamp(7L), output)
        )
        assertEquals(7L, output.capture.identity.frameNumber)
        assertEquals(1.75f, output.capture.halRedGain, 0f)
        assertEquals(1f, output.capture.halColorMatrix[0], 0f)
        assertEquals(1.25f, output.capture.sensor.lensShadingRggb[0], 0f)
        assertEquals(0f, output.calibration.blackLevel[0], 0f)
        assertEquals(1.25f, output.calibration.lensShadingGain(0, 0f, 0f), 0f)
        assertEquals(0.91f, output.configuration.userControls.manualColorMatrix[0], 0f)
    }

    @Test
    fun `bounded ring retires only overwritten frames`() {
        val ring = Niv99FrameMetadataRing(capacity = 2)
        val output = Niv99LiveFrameMetadata()
        assertTrue(ring.beginTimeline(CAMERA_ID, GENERATION))
        for (frame in 1L..3L) {
            assertTrue(ring.publish(capture(frame), calibration(), measuredNoise(), configuration()))
        }

        assertEquals(
            Niv99MetadataLookupStatus.NOT_FOUND,
            ring.copyExact(CAMERA_ID, GENERATION, timestamp(1L), output)
        )
        assertEquals(
            Niv99MetadataLookupStatus.MATCHED,
            ring.copyExact(CAMERA_ID, GENERATION, timestamp(2L), output)
        )
        assertEquals(2L, output.capture.identity.frameNumber)
        assertEquals(
            Niv99MetadataLookupStatus.MATCHED,
            ring.copyExact(CAMERA_ID, GENERATION, timestamp(3L), output)
        )
        assertEquals(3L, output.capture.identity.frameNumber)
    }

    @Test
    fun `duplicate and reordered metadata are rejected without contaminating retained frames`() {
        val ring = Niv99FrameMetadataRing(capacity = 4)
        val output = Niv99LiveFrameMetadata()
        val snapshot = Niv99MetadataRingSnapshot()
        assertTrue(ring.beginTimeline(CAMERA_ID, GENERATION))
        assertTrue(ring.publish(capture(2L), calibration(), measuredNoise(), configuration()))
        assertFalse(ring.publish(capture(2L), calibration(), measuredNoise(), configuration()))
        assertFalse(ring.publish(capture(1L), calibration(), measuredNoise(), configuration()))

        assertEquals(
            Niv99MetadataLookupStatus.MATCHED,
            ring.copyExact(CAMERA_ID, GENERATION, timestamp(2L), output)
        )
        assertEquals(2L, output.capture.identity.frameNumber)
        assertEquals(
            Niv99MetadataLookupStatus.NOT_FOUND,
            ring.copyExact(CAMERA_ID, GENERATION, timestamp(1L), output)
        )
        ring.snapshot(snapshot)
        assertEquals(1L, snapshot.publishedFrames)
        assertEquals(2L, snapshot.rejectedFrames)
    }

    @Test
    fun `generation reset makes every retired packet unreachable`() {
        val ring = Niv99FrameMetadataRing(capacity = 4)
        val output = Niv99LiveFrameMetadata()
        assertTrue(ring.beginTimeline(CAMERA_ID, GENERATION))
        assertTrue(ring.publish(capture(1L), calibration(), measuredNoise(), configuration()))
        assertTrue(ring.beginTimeline(CAMERA_ID, GENERATION + 1L))

        assertEquals(
            Niv99MetadataLookupStatus.WRONG_TIMELINE,
            ring.copyExact(CAMERA_ID, GENERATION, timestamp(1L), output)
        )
        assertEquals(
            Niv99MetadataLookupStatus.NOT_FOUND,
            ring.copyExact(CAMERA_ID, GENERATION + 1L, timestamp(1L), output)
        )

        val nextCapture = capture(1L, generation = GENERATION + 1L)
        assertTrue(ring.publish(nextCapture, calibration(), measuredNoise(), configuration()))
        assertEquals(
            Niv99MetadataLookupStatus.MATCHED,
            ring.copyExact(CAMERA_ID, GENERATION + 1L, timestamp(1L), output)
        )
        assertEquals(GENERATION + 1L, output.capture.identity.sessionGeneration)
    }

    @Test
    fun `resolved tuning cache gives preview and recording independent copies of one solution`() {
        val ring = Niv99ResolvedTuningRing(capacity = 4)
        val source = resolvedTuning(frameNumber = 3L).apply {
            rgbCorrection[0] = 0.96f
            rgbCorrection[2] = 1.04f
            spatialDenoise = 0.31f
            chromaDenoise = 0.52f
            sharpeningCeiling = 0.43f
            confidence = 0.87f
            colorEstimateApplied = true
            colorEstimateSourceIdentity.set(CAMERA_ID, GENERATION, 2L, timestamp(2L))
        }
        assertTrue(ring.beginTimeline(CAMERA_ID, GENERATION))
        assertTrue(ring.publish(source))

        // Mutating either the producer-owned packet or one consumer copy must not alter the cached
        // exact-frame solution subsequently consumed by the other output path.
        source.rgbCorrection.fill(4f)
        source.clear()
        val preview = Niv99LiveFrameTuning()
        val recording = Niv99LiveFrameTuning()
        assertEquals(
            Niv99MetadataLookupStatus.MATCHED,
            ring.copyExact(CAMERA_ID, GENERATION, timestamp(3L), preview)
        )
        preview.rgbCorrection[0] = 7f
        assertEquals(
            Niv99MetadataLookupStatus.MATCHED,
            ring.copyExact(CAMERA_ID, GENERATION, timestamp(3L), recording)
        )

        assertEquals(0.96f, recording.rgbCorrection[0], 0f)
        assertEquals(1.04f, recording.rgbCorrection[2], 0f)
        assertEquals(0.31f, recording.spatialDenoise, 0f)
        assertEquals(0.52f, recording.chromaDenoise, 0f)
        assertEquals(0.43f, recording.sharpeningCeiling, 0f)
        assertEquals(0.87f, recording.confidence, 0f)
        assertTrue(recording.pixelApplicationApproved)
        assertFalse(recording.pixelApplicationSucceeded)
        assertEquals(2L, recording.colorEstimateSourceIdentity.frameNumber)
    }

    @Test
    fun `resolved tuning cache refuses temporal rewind and old session reuse`() {
        val ring = Niv99ResolvedTuningRing(capacity = 4)
        val output = Niv99LiveFrameTuning()
        assertTrue(ring.beginTimeline(CAMERA_ID, GENERATION))
        assertTrue(ring.publish(resolvedTuning(frameNumber = 4L)))

        assertFalse(ring.canResolveNewFrame(timestamp(3L)))
        assertFalse(ring.publish(resolvedTuning(frameNumber = 3L)))
        assertEquals(
            Niv99MetadataLookupStatus.NOT_FOUND,
            ring.copyExact(CAMERA_ID, GENERATION, timestamp(3L), output)
        )

        assertTrue(ring.beginTimeline(CAMERA_ID, GENERATION + 1L))
        assertEquals(
            Niv99MetadataLookupStatus.WRONG_TIMELINE,
            ring.copyExact(CAMERA_ID, GENERATION, timestamp(4L), output)
        )
        assertEquals(
            Niv99MetadataLookupStatus.NOT_FOUND,
            ring.copyExact(CAMERA_ID, GENERATION + 1L, timestamp(4L), output)
        )
    }

    @Test
    fun `timestamp aligned lookup accepts clock skew but never an adjacent frame`() {
        val ring = Niv99ResolvedTuningRing(capacity = 4)
        val output = Niv99LiveFrameTuning()
        assertTrue(ring.beginTimeline(CAMERA_ID, GENERATION))
        assertTrue(ring.publish(resolvedTuning(frameNumber = 2L)))

        assertEquals(
            Niv99MetadataLookupStatus.MATCHED,
            ring.copyTimestampAligned(
                CAMERA_ID,
                GENERATION,
                timestamp(2L) + 317L,
                Niv99ExactFrameJoinPolicy.MAXIMUM_CLOCK_ALIGNMENT_NS,
                output
            )
        )
        assertEquals(timestamp(2L), output.identity.sensorTimestampNs)
        assertEquals(
            Niv99MetadataLookupStatus.NOT_FOUND,
            ring.copyTimestampAligned(
                CAMERA_ID,
                GENERATION,
                timestamp(2L) + 3_000_000L,
                Niv99ExactFrameJoinPolicy.MAXIMUM_CLOCK_ALIGNMENT_NS,
                output
            )
        )
    }

    @Test
    fun `sequential publisher serves exact packets to out of order preview and recording consumers`() {
        val cache = Niv99SequentialTuningCache(capacity = 4)
        assertTrue(cache.beginTimeline(CAMERA_ID, GENERATION))

        val auto = metadata(frameNumber = 1L)
        val manual = metadata(frameNumber = 2L).apply {
            configuration.detailProfile = Niv99DetailProfile.FOCUSED
            configuration.userControls.manualKelvin = 4_300
            configuration.userControls.manualTint = -17
            configuration.userControls.manualRedGain = 1.92f
            configuration.userControls.manualGreenGain = 1f
            configuration.userControls.manualBlueGain = 1.37f
        }
        val locked = metadata(frameNumber = 3L).apply {
            configuration.detailProfile = Niv99DetailProfile.EXTREME
            configuration.userControls.awbLockRequested = true
        }
        val estimate = Niv99YuvColorEstimate().apply {
            sourceIdentity.set(CAMERA_ID, GENERATION, 1L, timestamp(1L))
            redCorrection = 0.96f
            greenCorrection = 1f
            blueCorrection = 1.04f
            confidence = 0.9f
            consensusFraction = 0.9f
            neutralTileFraction = 0.5f
            meanLuma = 0.4f
            sampledPointCount = 1_000
            acceptedTileCount = 12
            totalTileCount = 96
        }

        assertEquals(Niv99ValidationResult.VALID, cache.resolveAndPublish(auto, estimate))
        assertEquals(Niv99ValidationResult.VALID, cache.resolveAndPublish(manual, estimate))
        assertEquals(Niv99ValidationResult.VALID, cache.resolveAndPublish(locked, estimate))

        // The third frame is consumed first to model a fast NPV10 worker. Preview then consumes the
        // first frame and another worker consumes the second; no consumer advances temporal state.
        val frameThreeRecording = Niv99LiveFrameTuning()
        val frameOnePreview = Niv99LiveFrameTuning()
        val frameTwoRecording = Niv99LiveFrameTuning()
        assertEquals(
            Niv99MetadataLookupStatus.MATCHED,
            cache.copyExact(CAMERA_ID, GENERATION, timestamp(3L), frameThreeRecording)
        )
        assertEquals(
            Niv99MetadataLookupStatus.MATCHED,
            cache.copyExact(CAMERA_ID, GENERATION, timestamp(1L), frameOnePreview)
        )
        assertEquals(
            Niv99MetadataLookupStatus.MATCHED,
            cache.copyExact(CAMERA_ID, GENERATION, timestamp(2L), frameTwoRecording)
        )

        assertEquals(3L, frameThreeRecording.identity.frameNumber)
        assertEquals(Niv99WhiteBalanceAuthority.AWB_LOCK, frameThreeRecording.authority)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), frameThreeRecording.rgbCorrection, 0f)
        assertEquals(1L, frameOnePreview.identity.frameNumber)
        assertEquals(Niv99WhiteBalanceAuthority.NIV99_AUTO, frameOnePreview.authority)
        assertTrue(frameOnePreview.whiteBalanceEffectMagnitude > 0f)
        assertEquals(2L, frameTwoRecording.identity.frameNumber)
        assertEquals(Niv99WhiteBalanceAuthority.MANUAL, frameTwoRecording.authority)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), frameTwoRecording.rgbCorrection, 0f)

        assertTrue(cache.beginTimeline(CAMERA_ID, GENERATION + 1L))
        assertEquals(
            Niv99MetadataLookupStatus.WRONG_TIMELINE,
            cache.copyExact(CAMERA_ID, GENERATION, timestamp(3L), frameThreeRecording)
        )
        assertEquals(
            Niv99MetadataLookupStatus.NOT_FOUND,
            cache.copyExact(CAMERA_ID, GENERATION + 1L, timestamp(1L), frameOnePreview)
        )
        assertEquals(
            Niv99ValidationResult.VALID,
            cache.resolveAndPublish(metadata(1L, GENERATION + 1L), colorEstimate = null)
        )
        assertEquals(
            Niv99MetadataLookupStatus.MATCHED,
            cache.copyExact(CAMERA_ID, GENERATION + 1L, timestamp(1L), frameOnePreview)
        )
        assertEquals(GENERATION + 1L, frameOnePreview.identity.sessionGeneration)
    }

    @Test
    fun `bounded exact lookup wakes when matching capture result publishes`() {
        val cache = Niv99SequentialTuningCache(capacity = 4)
        val workerStarted = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        assertTrue(cache.beginTimeline(CAMERA_ID, GENERATION))
        try {
            val output = Niv99LiveFrameTuning()
            val lookup = executor.submit<Niv99MetadataLookupStatus> {
                workerStarted.countDown()
                cache.awaitExact(
                    expectedCameraId = CAMERA_ID,
                    expectedGeneration = GENERATION,
                    sensorTimestampNs = timestamp(1L),
                    timeoutNs = TimeUnit.MILLISECONDS.toNanos(250L),
                    output = output
                )
            }
            assertTrue(workerStarted.await(1L, TimeUnit.SECONDS))
            assertEquals(
                Niv99ValidationResult.VALID,
                cache.resolveAndPublish(metadata(1L), colorEstimate = null)
            )
            assertEquals(Niv99MetadataLookupStatus.MATCHED, lookup.get(1L, TimeUnit.SECONDS))
            assertEquals(1L, output.identity.frameNumber)
            assertEquals(timestamp(1L), output.identity.sensorTimestampNs)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(1L, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `bounded metadata lookup wakes when an image leads its capture result`() {
        val ring = Niv99FrameMetadataRing(capacity = 4)
        val workerStarted = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        assertTrue(ring.beginTimeline(CAMERA_ID, GENERATION))
        try {
            val output = Niv99LiveFrameMetadata()
            val lookup = executor.submit<Niv99MetadataLookupStatus> {
                workerStarted.countDown()
                ring.awaitExact(
                    expectedCameraId = CAMERA_ID,
                    expectedGeneration = GENERATION,
                    sensorTimestampNs = timestamp(1L),
                    timeoutNs = TimeUnit.MILLISECONDS.toNanos(250L),
                    output = output
                )
            }
            assertTrue(workerStarted.await(1L, TimeUnit.SECONDS))
            assertTrue(ring.publish(capture(1L), calibration(), measuredNoise(), configuration()))

            assertEquals(Niv99MetadataLookupStatus.MATCHED, lookup.get(1L, TimeUnit.SECONDS))
            assertEquals(1L, output.capture.identity.frameNumber)
            assertEquals(timestamp(1L), output.capture.identity.sensorTimestampNs)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(1L, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `metadata waiter exits on generation replacement without accepting retired data`() {
        val ring = Niv99FrameMetadataRing(capacity = 4)
        val workerStarted = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        assertTrue(ring.beginTimeline(CAMERA_ID, GENERATION))
        try {
            val output = Niv99LiveFrameMetadata()
            val lookup = executor.submit<Niv99MetadataLookupStatus> {
                workerStarted.countDown()
                ring.awaitExact(
                    expectedCameraId = CAMERA_ID,
                    expectedGeneration = GENERATION,
                    sensorTimestampNs = timestamp(1L),
                    timeoutNs = TimeUnit.SECONDS.toNanos(1L),
                    output = output
                )
            }
            assertTrue(workerStarted.await(1L, TimeUnit.SECONDS))
            assertTrue(ring.beginTimeline(CAMERA_ID, GENERATION + 1L))

            assertEquals(Niv99MetadataLookupStatus.WRONG_TIMELINE, lookup.get(1L, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(1L, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `manual white balance remains authoritative while live pixel application stays isolated`() {
        val metadata = metadata(frameNumber = 1L).apply {
            configuration.userControls.manualKelvin = 4_300
            configuration.userControls.manualTint = -17
            configuration.userControls.manualRedGain = 1.92f
            configuration.userControls.manualGreenGain = 1f
            configuration.userControls.manualBlueGain = 1.37f
        }
        val output = Niv99LiveFrameTuning()

        assertEquals(Niv99ValidationResult.VALID, Niv99LiveTuningPolicy().resolve(metadata, output))
        assertTrue(output.valid)
        assertEquals(Niv99WhiteBalanceAuthority.MANUAL, output.authority)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), output.rgbCorrection, 0f)
        assertFalse(output.pixelApplicationApproved)
    }

    @Test
    fun `explicit AWB lock cannot receive an automatic correction or live pixel application`() {
        val policy = Niv99LiveTuningPolicy()
        val requested = metadata(frameNumber = 1L).apply {
            configuration.userControls.awbLockRequested = true
        }
        val requestedOutput = Niv99LiveFrameTuning()
        assertEquals(Niv99ValidationResult.VALID, policy.resolve(requested, requestedOutput))
        assertEquals(Niv99WhiteBalanceAuthority.AWB_LOCK, requestedOutput.authority)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), requestedOutput.rgbCorrection, 0f)
        assertFalse(requestedOutput.pixelApplicationApproved)
    }

    @Test
    fun `internal HAL AWB hold resolves correction without enabling live pixel application`() {
        val held = metadata(frameNumber = 2L).apply {
            capture.halAwbLocked = true
        }
        val estimate = Niv99YuvColorEstimate().apply {
            sourceIdentity.set(CAMERA_ID, GENERATION, 1L, timestamp(1L))
            redCorrection = 0.96f
            greenCorrection = 1f
            blueCorrection = 1.04f
            confidence = 0.9f
            consensusFraction = 0.9f
            neutralTileFraction = 0.5f
            meanLuma = 0.4f
            sampledPointCount = 1_000
            acceptedTileCount = 12
            totalTileCount = 96
        }
        val output = Niv99LiveFrameTuning()

        assertEquals(Niv99ValidationResult.VALID, Niv99LiveTuningPolicy().resolve(held, estimate, output))
        assertEquals(Niv99WhiteBalanceAuthority.NIV99_AUTO, output.authority)
        assertTrue(output.colorEstimateApplied)
        assertEquals(1L, output.colorEstimateSourceIdentity.frameNumber)
        assertEquals(0.96f, output.rgbCorrection[0], 0.0001f)
        assertEquals(1.04f, output.rgbCorrection[2], 0.0001f)
        assertFalse(output.pixelApplicationApproved)
    }

    @Test
    fun `live tuning is bounded and temporal fusion stays disabled without flow evidence`() {
        val profiles = Niv99DetailProfile.entries
        for ((index, profile) in profiles.withIndex()) {
            val metadata = metadata(frameNumber = index + 1L).apply {
                configuration.detailProfile = profile
                measuredNoise.valid = true
                measuredNoise.shotCoefficient.fill(0.5f)
                measuredNoise.readCoefficient.fill(0.25f)
            }
            val output = Niv99LiveFrameTuning()
            assertEquals(
                Niv99ValidationResult.VALID,
                Niv99LiveTuningPolicy().resolve(metadata, output)
            )
            assertTrue(output.valid)
            assertFalse(output.pixelApplicationApproved)
            assertTrue(output.spatialDenoise in 0f..0.62f)
            assertTrue(output.chromaDenoise in 0f..0.86f)
            assertEquals(0f, output.temporalDenoise, 0f)
            assertTrue(output.sharpeningCeiling in 0.28f..0.72f)
            assertTrue(output.confidence in 0f..1f)
            assertTrue(output.rgbCorrection.all { it in 0.92f..1.08f })
        }
    }

    @Test
    fun `offline isolation rejects live pixel application while preserving route capability`() {
        val rawDomain = metadata(frameNumber = 1L).apply {
            configuration.domain = Niv99SignalDomain.SENSOR_LINEAR_RAW
        }
        val proMode = metadata(frameNumber = 2L).apply {
            configuration.mode = Niv99OperatingMode.PRO
        }
        val rawOutput = Niv99LiveFrameTuning()
        val proOutput = Niv99LiveFrameTuning()

        assertEquals(Niv99ValidationResult.VALID, Niv99LiveTuningPolicy().resolve(rawDomain, rawOutput))
        assertEquals(Niv99ValidationResult.VALID, Niv99LiveTuningPolicy().resolve(proMode, proOutput))
        assertFalse(rawOutput.pixelApplicationApproved)
        assertFalse(proOutput.pixelApplicationApproved)
        assertFalse(Niv99VideoPixelApplicationPolicy.supportsDomain(rawDomain.configuration.domain))
        assertTrue(Niv99VideoPixelApplicationPolicy.supportsLiveMode(proMode.configuration.mode))
        assertFalse(Niv99VideoPixelApplicationPolicy.isEligible(proMode.configuration))
    }

    private fun metadata(
        frameNumber: Long,
        generation: Long = GENERATION
    ): Niv99LiveFrameMetadata =
        Niv99LiveFrameMetadata().copyFrom(
            capture = capture(frameNumber, generation),
            calibration = calibration(),
            measuredNoise = measuredNoise(),
            configuration = configuration(),
            sequence = frameNumber
        )

    private fun capture(
        frameNumber: Long,
        generation: Long = GENERATION,
        lensShadingCapacity: Int = Niv99DynamicSensorMetadata.DEFAULT_LENS_SHADING_CAPACITY
    ): Niv99CaptureMetadata = Niv99CaptureMetadata(lensShadingCapacity).apply {
        identity.set(CAMERA_ID, generation, frameNumber, timestamp(frameNumber))
        sensor.identity.copyFrom(identity)
        iso = 800
        exposureTimeNs = 8_000_000L
        frameDurationNs = 40_000_000L
        rollingShutterSkewNs = 12_000_000L
        halAwbConverged = true
        halGainsAvailable = true
        halRedGain = 1.72f
        halGreenEvenGain = 1.01f
        halGreenOddGain = 0.99f
        halBlueGain = 1.31f
        halMatrixAvailable = true
        Niv99Math.setIdentity3(halColorMatrix)
    }

    private fun calibration(
        lensShadingCapacity: Int = Niv99DynamicSensorMetadata.DEFAULT_LENS_SHADING_CAPACITY
    ): Niv99SensorCalibration = Niv99SensorCalibration(lensShadingCapacity).apply {
        copyFrom(Niv99TestFixtures.calibration(CAMERA_ID))
    }

    private fun measuredNoise(): Niv99MeasuredNoiseProfile = Niv99MeasuredNoiseProfile()

    private fun resolvedTuning(
        frameNumber: Long,
        generation: Long = GENERATION
    ): Niv99LiveFrameTuning = Niv99LiveFrameTuning().apply {
        identity.set(CAMERA_ID, generation, frameNumber, timestamp(frameNumber))
        valid = true
        authority = Niv99WhiteBalanceAuthority.NIV99_AUTO
        assertTrue(approvePixelApplication())
    }

    private fun configuration(): Niv99LiveFrameConfiguration = Niv99LiveFrameConfiguration().apply {
        mode = Niv99OperatingMode.VIDEO
        domain = Niv99SignalDomain.P010_YUV
        detailProfile = Niv99DetailProfile.NATURAL
        width = 3_840
        height = 2_160
        targetFps = 25
        colorProfileId = 11L
        surfaceGeneration = 7L
        codecGeneration = 5L
    }

    private fun timestamp(frameNumber: Long): Long = frameNumber * 40_000_000L

    companion object {
        private const val CAMERA_ID = Niv99TestFixtures.CAMERA_ID
        private const val GENERATION = 9L
    }
}
