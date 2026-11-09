package com.neoproc.neoproccamx.recording

import android.media.MediaCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAbrPolicyTest {

    @Test
    fun evaluatesEncoderBackpressureEveryThreeFrames() {
        assertTrue(RecordingAbrPolicy.shouldEvaluate(frameIndex = 0L))
        assertFalse(RecordingAbrPolicy.shouldEvaluate(frameIndex = 1L))
        assertFalse(RecordingAbrPolicy.shouldEvaluate(frameIndex = 2L))
        assertTrue(RecordingAbrPolicy.shouldEvaluate(frameIndex = 3L))
    }

    @Test
    fun highEncoderLatencyReducesEntropyAndPushesRuntimeBitrateParameter() {
        val decision = RecordingAbrPolicy.resolve(
            frameIndex = 6L,
            encoderQueueLatencyMs = 24L,
            targetBitrateBps = 400_000_000,
            minimumBitrateBps = 220_000_000,
            queueOccupancy = 0.82f,
            thermalPressure = 0.20f
        )

        assertTrue(decision.backpressureActive)
        assertTrue(decision.shouldSetCodecParameters)
        assertTrue(decision.targetBitrateBps in 220_000_000 until 400_000_000)
        assertTrue(decision.shaderEntropyScale < 1f)
        assertTrue(decision.detailGainScale < 1f)
        assertTrue(decision.ditherAmplitudeScale < 1f)
        assertEquals(
            decision.targetBitrateBps,
            RecordingAbrPolicy.toCodecParameterMap(decision)
                .getValue(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE)
        )
    }

    @Test
    fun stableEncoderQueuePreservesRequestedQuality() {
        val decision = RecordingAbrPolicy.resolve(
            frameIndex = 9L,
            encoderQueueLatencyMs = 4L,
            targetBitrateBps = 320_000_000,
            minimumBitrateBps = 120_000_000,
            queueOccupancy = 0.20f,
            thermalPressure = 0.0f
        )

        assertFalse(decision.backpressureActive)
        assertFalse(decision.shouldSetCodecParameters)
        assertEquals(320_000_000, decision.targetBitrateBps)
        assertEquals(1f, decision.shaderEntropyScale, 0.0001f)
        assertEquals(1f, decision.detailGainScale, 0.0001f)
        assertEquals(1f, decision.ditherAmplitudeScale, 0.0001f)
    }

    @Test
    fun manualBitrateRetainsUserTargetWhileReportingBackpressure() {
        val decision = RecordingAbrPolicy.resolve(
            frameIndex = 6L,
            encoderQueueLatencyMs = 450L,
            targetBitrateBps = 180_000_000,
            minimumBitrateBps = 180_000_000,
            queueOccupancy = 0.95f,
            thermalPressure = 0.8f,
            allowBitrateReduction = false
        )

        assertTrue(decision.backpressureActive)
        assertFalse(decision.shouldSetCodecParameters)
        assertEquals(180_000_000, decision.targetBitrateBps)
        assertEquals(1f, decision.shaderEntropyScale, 0.0001f)
        assertEquals(1f, decision.detailGainScale, 0.0001f)
        assertEquals(1f, decision.ditherAmplitudeScale, 0.0001f)
    }

    @Test
    fun strictHighEndBackpressurePreservesDetailAndDitherScales() {
        val decision = RecordingAbrPolicy.resolve(
            frameIndex = 6L,
            encoderQueueLatencyMs = 450L,
            targetBitrateBps = 820_000_000,
            minimumBitrateBps = 820_000_000,
            queueOccupancy = 1.0f,
            thermalPressure = 1.0f
        )

        assertTrue(decision.backpressureActive)
        assertFalse(decision.shouldSetCodecParameters)
        assertEquals(820_000_000, decision.targetBitrateBps)
        assertTrue(decision.shaderEntropyScale >= 0.82f)
        assertTrue(decision.detailGainScale >= 0.88f)
        assertTrue(decision.ditherAmplitudeScale >= 0.84f)
    }
}
