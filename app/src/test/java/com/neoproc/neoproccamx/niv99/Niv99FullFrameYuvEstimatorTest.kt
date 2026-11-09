package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99FullFrameYuvEstimatorTest {
    @Test
    fun `peripheral neutral evidence drives bounded correction instead of a focus crop`() {
        val frame = yuvFrame(width = 96, height = 64, yCode = 128, uCode = 112, vCode = 142)
        // A highly saturated centre is deliberately not neutral evidence. The estimator must still
        // consume the distributed peripheral tiles rather than behaving like centre-weighted AF/AWB.
        paintChromaRectangle(frame, left = 32, top = 20, right = 64, bottom = 44, u = 210, v = 210)
        val output = Niv99YuvColorEstimate()

        assertEquals(
            Niv99YuvEstimateStatus.VALID,
            Niv99FullFrameYuvEstimator().estimate(identity(4L), frame, output)
        )
        assertTrue(output.isValid())
        assertEquals(4L, output.sourceIdentity.frameNumber)
        assertTrue(output.acceptedTileCount >= 8)
        assertTrue(output.sampledPointCount > 500)
        assertTrue(output.redCorrection < 0.99f)
        assertTrue(output.blueCorrection > 1.01f)
        assertTrue(output.redCorrection >= Niv99YuvColorEstimate.MIN_CORRECTION)
        assertTrue(output.blueCorrection <= Niv99YuvColorEstimate.MAX_CORRECTION)
    }

    @Test
    fun `neutral full frame remains identity within quantization tolerance`() {
        val frame = yuvFrame(width = 64, height = 48, yCode = 128, uCode = 128, vCode = 128)
        val output = Niv99YuvColorEstimate()

        assertEquals(
            Niv99YuvEstimateStatus.VALID,
            Niv99FullFrameYuvEstimator().estimate(identity(4L), frame, output)
        )
        assertEquals(1f, output.redCorrection, 0.005f)
        assertEquals(1f, output.greenCorrection, 0f)
        assertEquals(1f, output.blueCorrection, 0.005f)
        assertTrue(output.confidence > 0.5f)
    }

    @Test
    fun `ten bit P010-like planes are decoded without an eight bit intermediate`() {
        val frame = p010Frame(width = 64, height = 48, yCode = 512, uCode = 452, vCode = 568)
        val output = Niv99YuvColorEstimate()

        assertEquals(
            Niv99YuvEstimateStatus.VALID,
            Niv99FullFrameYuvEstimator().estimate(identity(3L), frame, output)
        )
        assertTrue(output.redCorrection < 1f)
        assertTrue(output.blueCorrection > 1f)
        assertEquals(10, frame.bitDepth)
    }

    @Test
    fun `invalid or timestamp-mismatched planes fail closed`() {
        val validFrame = yuvFrame(width = 32, height = 24, yCode = 128, uCode = 128, vCode = 128)
        val wrongTimestamp = identity(2L)
        val invalidPlane = TestYuvFrame(
            width = 32,
            height = 24,
            yBytes = ByteArray(4),
            uBytes = ByteArray(4),
            vBytes = ByteArray(4),
            yRowStride = 32,
            yPixelStride = 1,
            uRowStride = 16,
            vRowStride = 16,
            uPixelStride = 1,
            vPixelStride = 1,
            timestamp = timestamp(1L),
            bitDepth = 8
        )

        assertEquals(
            Niv99YuvEstimateStatus.INVALID_IDENTITY,
            Niv99FullFrameYuvEstimator().estimate(wrongTimestamp, validFrame, Niv99YuvColorEstimate())
        )
        assertEquals(
            Niv99YuvEstimateStatus.INVALID_PLANE,
            Niv99FullFrameYuvEstimator().estimate(identity(1L), invalidPlane, Niv99YuvColorEstimate())
        )
    }

    @Test
    fun `estimate store rejects future stale and cross-session evidence`() {
        val store = Niv99YuvEstimateStore()
        val estimate = estimate(frameNumber = 5L, red = 0.96f, blue = 1.04f)
        val output = Niv99YuvColorEstimate()
        assertTrue(store.beginTimeline(CAMERA_ID, GENERATION))
        assertEquals(Niv99YuvEstimateStatus.VALID, store.publish(estimate))

        assertEquals(
            Niv99YuvEstimateStatus.FUTURE_ESTIMATE,
            store.copyLatestPreceding(identity(4L), output)
        )
        assertEquals(
            Niv99YuvEstimateStatus.WRONG_TIMELINE,
            store.copyLatestPreceding(identity(6L, generation = GENERATION + 1L), output)
        )
        assertEquals(
            Niv99YuvEstimateStatus.VALID,
            store.copyLatestPreceding(
                Niv99FrameIdentity().set(CAMERA_ID, GENERATION, 80L, timestamp(80L)),
                output
            )
        )
        assertTrue(output.redCorrection > estimate.redCorrection)
        assertTrue(output.redCorrection < 1f)
        assertTrue(output.blueCorrection < estimate.blueCorrection)
        assertTrue(output.blueCorrection > 1f)
        assertTrue(output.confidence < estimate.confidence)
        assertEquals(
            Niv99YuvEstimateStatus.STALE_ESTIMATE,
            store.copyLatestPreceding(
                Niv99FrameIdentity().set(CAMERA_ID, GENERATION, 250L, timestamp(250L)),
                output
            )
        )
        assertEquals(
            Niv99YuvEstimateStatus.VALID,
            store.copyLatestPreceding(identity(6L), output)
        )
        assertEquals(5L, output.sourceIdentity.frameNumber)
        assertEquals(estimate.redCorrection, output.redCorrection, 0f)
        assertEquals(estimate.blueCorrection, output.blueCorrection, 0f)

        assertTrue(store.beginTimeline(CAMERA_ID, GENERATION + 1L))
        assertEquals(
            Niv99YuvEstimateStatus.INSUFFICIENT_EVIDENCE,
            store.copyLatestPreceding(identity(1L, generation = GENERATION + 1L), output)
        )
    }

    @Test
    fun `temporal store limits direction changes without losing source provenance`() {
        val store = Niv99YuvEstimateStore()
        val output = Niv99YuvColorEstimate()
        assertTrue(store.beginTimeline(CAMERA_ID, GENERATION))
        assertEquals(
            Niv99YuvEstimateStatus.VALID,
            store.publish(estimate(frameNumber = 1L, red = 0.92f, blue = 1.08f))
        )
        assertEquals(
            Niv99YuvEstimateStatus.VALID,
            store.publish(estimate(frameNumber = 2L, red = 1.08f, blue = 0.92f))
        )
        assertEquals(Niv99YuvEstimateStatus.VALID, store.copyLatestPreceding(identity(3L), output))
        assertEquals(2L, output.sourceIdentity.frameNumber)
        assertTrue(output.redCorrection > 0.92f)
        assertTrue(output.redCorrection < 1.0f)
        assertTrue(output.blueCorrection > 1.0f)
        assertTrue(output.blueCorrection < 1.08f)
    }

    @Test
    fun `explicit user lock keeps identity even with valid preceding full-frame estimate`() {
        val metadata = Niv99LiveFrameMetadata().copyFrom(
            capture = capture(frameNumber = 2L, halLocked = true),
            calibration = Niv99TestFixtures.calibration(CAMERA_ID),
            measuredNoise = Niv99MeasuredNoiseProfile(),
            configuration = configuration(awbLockRequested = true),
            sequence = 2L
        )
        val output = Niv99LiveFrameTuning()

        assertEquals(
            Niv99ValidationResult.VALID,
            Niv99LiveTuningPolicy().resolve(
                metadata,
                estimate(frameNumber = 1L, red = 0.94f, blue = 1.06f),
                output
            )
        )
        assertEquals(Niv99WhiteBalanceAuthority.AWB_LOCK, output.authority)
        assertFalse(output.colorEstimateApplied)
        assertEquals(1f, output.rgbCorrection[0], 0f)
        assertEquals(1f, output.rgbCorrection[2], 0f)
    }

    private fun estimate(frameNumber: Long, red: Float, blue: Float) =
        Niv99YuvColorEstimate().apply {
            sourceIdentity.copyFrom(identity(frameNumber))
            redCorrection = red
            greenCorrection = 1f
            blueCorrection = blue
            confidence = 0.9f
            consensusFraction = 0.9f
            neutralTileFraction = 0.5f
            meanLuma = 0.5f
            lumaNoiseSigma = 0.01f
            chromaNoiseSigma = 0.012f
            sampledPointCount = 1_000
            acceptedTileCount = 16
            totalTileCount = 96
        }

    private fun capture(frameNumber: Long, halLocked: Boolean): Niv99CaptureMetadata =
        Niv99CaptureMetadata().apply {
            identity.copyFrom(identity(frameNumber))
            sensor.identity.copyFrom(identity)
            iso = 400
            exposureTimeNs = 8_000_000L
            frameDurationNs = FRAME_DURATION_NS
            halAwbConverged = true
            halAwbLocked = halLocked
            halGainsAvailable = true
            halRedGain = 1.7f
            halGreenEvenGain = 1f
            halGreenOddGain = 1f
            halBlueGain = 1.3f
            halMatrixAvailable = true
            Niv99Math.setIdentity3(halColorMatrix)
        }

    private fun configuration(awbLockRequested: Boolean) = Niv99LiveFrameConfiguration().apply {
        mode = Niv99OperatingMode.VIDEO
        domain = Niv99SignalDomain.DISPLAY_RGB
        detailProfile = Niv99DetailProfile.NATURAL
        width = 1_920
        height = 1_080
        targetFps = 25
        colorProfileId = 10L
        surfaceGeneration = 4L
        codecGeneration = 9L
        userControls.awbLockRequested = awbLockRequested
    }

    private fun identity(frameNumber: Long, generation: Long = GENERATION) =
        Niv99FrameIdentity().set(CAMERA_ID, generation, frameNumber, timestamp(frameNumber))

    private fun timestamp(frameNumber: Long): Long = frameNumber * FRAME_DURATION_NS

    private fun yuvFrame(
        width: Int,
        height: Int,
        yCode: Int,
        uCode: Int,
        vCode: Int
    ): TestYuvFrame {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        return TestYuvFrame(
            width = width,
            height = height,
            yBytes = ByteArray(width * height) { yCode.toByte() },
            uBytes = ByteArray(chromaWidth * chromaHeight) { uCode.toByte() },
            vBytes = ByteArray(chromaWidth * chromaHeight) { vCode.toByte() },
            yRowStride = width,
            yPixelStride = 1,
            uRowStride = chromaWidth,
            vRowStride = chromaWidth,
            uPixelStride = 1,
            vPixelStride = 1,
            timestamp = timestamp(4L),
            bitDepth = 8
        )
    }

    private fun p010Frame(
        width: Int,
        height: Int,
        yCode: Int,
        uCode: Int,
        vCode: Int
    ): TestYuvFrame {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val yBytes = ByteArray(width * height * 2)
        val uBytes = ByteArray(chromaWidth * chromaHeight * 2)
        val vBytes = ByteArray(chromaWidth * chromaHeight * 2)
        fillP010(yBytes, yCode)
        fillP010(uBytes, uCode)
        fillP010(vBytes, vCode)
        return TestYuvFrame(
            width = width,
            height = height,
            yBytes = yBytes,
            uBytes = uBytes,
            vBytes = vBytes,
            yRowStride = width * 2,
            yPixelStride = 2,
            uRowStride = chromaWidth * 2,
            vRowStride = chromaWidth * 2,
            uPixelStride = 2,
            vPixelStride = 2,
            timestamp = timestamp(3L),
            bitDepth = 10
        )
    }

    private fun fillP010(bytes: ByteArray, code: Int) {
        val packed = code.coerceIn(0, 1_023) shl 6
        var index = 0
        while (index < bytes.size) {
            bytes[index] = (packed and 0xff).toByte()
            bytes[index + 1] = ((packed ushr 8) and 0xff).toByte()
            index += 2
        }
    }

    private fun paintChromaRectangle(
        frame: TestYuvFrame,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        u: Int,
        v: Int
    ) {
        val chromaLeft = left / 2
        val chromaTop = top / 2
        val chromaRight = (right + 1) / 2
        val chromaBottom = (bottom + 1) / 2
        for (y in chromaTop until chromaBottom) {
            for (x in chromaLeft until chromaRight) {
                frame.uBytes[y * frame.uRowStride + x] = u.toByte()
                frame.vBytes[y * frame.vRowStride + x] = v.toByte()
            }
        }
    }

    private data class TestYuvFrame(
        override val width: Int,
        override val height: Int,
        override val yBytes: ByteArray,
        override val uBytes: ByteArray,
        override val vBytes: ByteArray,
        override val yRowStride: Int,
        override val yPixelStride: Int,
        override val uRowStride: Int,
        override val vRowStride: Int,
        override val uPixelStride: Int,
        override val vPixelStride: Int,
        override val timestamp: Long,
        override val bitDepth: Int
    ) : Niv99YuvFrameView

    private companion object {
        const val CAMERA_ID = "0"
        const val GENERATION = 12L
        const val FRAME_DURATION_NS = 40_000_000L
    }
}
