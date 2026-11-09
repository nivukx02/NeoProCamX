package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTemporalFusionPolicyTest {
    @Test
    fun `first eligible frame seeds history and next exact frame activates fusion`() {
        val policy = PreviewTemporalFusionPolicy()
        val input = validInput(timestampNs = 1_000_000_000L, historyFrames = 0)
        val decision = PreviewTemporalFusionDecision()

        policy.resolve(input, decision)

        assertFalse(decision.enabled)
        assertTrue(decision.resetHistory)
        assertTrue(decision.acceptCurrentFrameIntoHistory)
        assertEquals(PreviewTemporalFusionReason.FIRST_ELIGIBLE_FRAME, decision.reason)

        input.sensorTimestampNs += 40_000_000L
        input.availableHistoryFrames = 1
        policy.resolve(input, decision)

        assertTrue(decision.enabled)
        assertFalse(decision.resetHistory)
        assertTrue(decision.acceptCurrentFrameIntoHistory)
        assertEquals(1, decision.historyFrameCount)
        assertEquals(PreviewTemporalFusionReason.NONE, decision.reason)
    }

    @Test
    fun `same sensor frame redraw cannot feed back into its own history`() {
        val policy = PreviewTemporalFusionPolicy()
        val input = validInput(timestampNs = 2_000_000_000L, historyFrames = 0)
        val decision = PreviewTemporalFusionDecision()
        policy.resolve(input, decision)

        input.availableHistoryFrames = 1
        policy.resolve(input, decision)

        assertFalse(decision.enabled)
        assertFalse(decision.resetHistory)
        assertFalse(decision.acceptCurrentFrameIntoHistory)
        assertEquals(PreviewTemporalFusionReason.SAME_FRAME_REDRAW, decision.reason)
    }

    @Test
    fun `timestamp reversal and long gap reset history but admit a fresh seed`() {
        val reversalPolicy = PreviewTemporalFusionPolicy()
        val reversalInput = validInput(timestampNs = 3_000_000_000L, historyFrames = 0)
        val decision = PreviewTemporalFusionDecision()
        reversalPolicy.resolve(reversalInput, decision)
        reversalInput.sensorTimestampNs -= 1L
        reversalInput.availableHistoryFrames = 1
        reversalPolicy.resolve(reversalInput, decision)

        assertFalse(decision.enabled)
        assertTrue(decision.resetHistory)
        assertTrue(decision.acceptCurrentFrameIntoHistory)
        assertEquals(PreviewTemporalFusionReason.TIMESTAMP_NON_MONOTONIC, decision.reason)

        val gapPolicy = PreviewTemporalFusionPolicy()
        val gapInput = validInput(timestampNs = 4_000_000_000L, historyFrames = 0)
        gapPolicy.resolve(gapInput, decision)
        gapInput.sensorTimestampNs += 100_000_001L
        gapInput.availableHistoryFrames = 1
        gapPolicy.resolve(gapInput, decision)

        assertFalse(decision.enabled)
        assertTrue(decision.resetHistory)
        assertEquals(PreviewTemporalFusionReason.TIMESTAMP_DISCONTINUITY, decision.reason)
    }

    @Test
    fun `calibration revision regression and material white balance transitions reset history`() {
        val calibrationPolicy = PreviewTemporalFusionPolicy()
        val input = validInput(timestampNs = 5_000_000_000L, historyFrames = 0)
        val decision = PreviewTemporalFusionDecision()
        calibrationPolicy.resolve(input, decision)
        input.sensorTimestampNs += 40_000_000L
        input.availableHistoryFrames = 1
        input.calibrationRevision -= 1L
        calibrationPolicy.resolve(input, decision)
        assertEquals(PreviewTemporalFusionReason.CALIBRATION_CHANGED, decision.reason)
        assertTrue(decision.resetHistory)

        val wbPolicy = PreviewTemporalFusionPolicy()
        val wbInput = validInput(timestampNs = 6_000_000_000L, historyFrames = 0)
        wbPolicy.resolve(wbInput, decision)
        wbInput.sensorTimestampNs += 40_000_000L
        wbInput.availableHistoryFrames = 1
        wbInput.redCorrection = 1.08f
        wbPolicy.resolve(wbInput, decision)
        assertEquals(PreviewTemporalFusionReason.WHITE_BALANCE_TRANSITION, decision.reason)
        assertTrue(decision.resetHistory)
    }

    @Test
    fun `small white balance drift keeps the continuous timeline`() {
        val policy = PreviewTemporalFusionPolicy()
        val input = validInput(timestampNs = 7_000_000_000L, historyFrames = 0)
        val decision = PreviewTemporalFusionDecision()
        policy.resolve(input, decision)
        input.sensorTimestampNs += 40_000_000L
        input.availableHistoryFrames = 1
        input.redCorrection = 1.02f
        input.blueCorrection = 0.985f

        policy.resolve(input, decision)

        assertTrue(decision.enabled)
        assertEquals(PreviewTemporalFusionReason.NONE, decision.reason)
    }

    @Test
    fun `noise evidence increases bounded chroma more than luma fusion`() {
        val low = activeDecision(spatial = 0.02f, chroma = 0.03f, historyFrames = 2)
        val high = activeDecision(spatial = 0.62f, chroma = 0.86f, historyFrames = 2)

        assertTrue(high.lumaStrength > low.lumaStrength)
        assertTrue(high.chromaStrength > low.chromaStrength)
        assertTrue(high.chromaStrength > high.lumaStrength)
        assertTrue(high.lumaStrength <= 0.30f)
        assertTrue(high.chromaStrength <= 0.50f)
        assertTrue(high.noisePressure in 0f..1f)
    }

    @Test
    fun `unsupported precision and non enhance routes never accept history`() {
        val policy = PreviewTemporalFusionPolicy()
        val decision = PreviewTemporalFusionDecision()
        val unsupported = validInput(timestampNs = 8_000_000_000L, historyFrames = 2).apply {
            fp16TextureSupported = false
        }
        policy.resolve(unsupported, decision)
        assertFalse(decision.enabled)
        assertFalse(decision.acceptCurrentFrameIntoHistory)
        assertEquals(PreviewTemporalFusionReason.FP16_UNSUPPORTED, decision.reason)

        val wrongGrade = validInput(timestampNs = 8_040_000_000L, historyFrames = 2).apply {
            enhanceGradeActive = false
        }
        policy.resolve(wrongGrade, decision)
        assertFalse(decision.enabled)
        assertFalse(decision.acceptCurrentFrameIntoHistory)
        assertEquals(PreviewTemporalFusionReason.NON_ENHANCE_GRADE, decision.reason)
    }

    @Test
    fun `non finite tuning fails closed and clears all mix coefficients`() {
        val policy = PreviewTemporalFusionPolicy()
        val decision = PreviewTemporalFusionDecision()
        val input = validInput(timestampNs = 9_000_000_000L, historyFrames = 2).apply {
            tuningConfidence = Float.NaN
        }

        policy.resolve(input, decision)

        assertFalse(decision.enabled)
        assertTrue(decision.resetHistory)
        assertFalse(decision.acceptCurrentFrameIntoHistory)
        assertEquals(0f, decision.lumaStrength, 0f)
        assertEquals(0f, decision.chromaStrength, 0f)
        assertEquals(PreviewTemporalFusionReason.TUNING_UNAVAILABLE, decision.reason)
    }

    private fun activeDecision(
        spatial: Float,
        chroma: Float,
        historyFrames: Int
    ): PreviewTemporalFusionDecision {
        val policy = PreviewTemporalFusionPolicy()
        val input = validInput(timestampNs = 10_000_000_000L, historyFrames = 0).apply {
            spatialDenoise = spatial
            chromaDenoise = chroma
            tuningConfidence = 1f
        }
        val decision = PreviewTemporalFusionDecision()
        policy.resolve(input, decision)
        input.sensorTimestampNs += 40_000_000L
        input.availableHistoryFrames = historyFrames
        policy.resolve(input, decision)
        assertTrue(decision.enabled)
        return decision
    }

    private fun validInput(
        timestampNs: Long,
        historyFrames: Int
    ): PreviewTemporalFusionInput = PreviewTemporalFusionInput().apply {
        sensorTimestampNs = timestampNs
        enhanceGradeActive = true
        fp16TextureSupported = true
        historyResourcesAvailable = true
        outputAttached = true
        presentationEnabled = true
        debayerPixelRoute = false
        tuningActive = true
        tuningConfidence = 0.80f
        spatialDenoise = 0.25f
        chromaDenoise = 0.45f
        calibrationRevision = 17L
        redCorrection = 1f
        greenCorrection = 1f
        blueCorrection = 1f
        availableHistoryFrames = historyFrames
    }
}
