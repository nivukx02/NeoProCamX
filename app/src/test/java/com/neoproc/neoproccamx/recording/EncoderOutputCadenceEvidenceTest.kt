package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderOutputCadenceEvidenceTest {
    @Test
    fun `CP-066 all-IDR 120 ms output is detected as cadence mismatch`() {
        val tracker = EncoderOutputCadenceTracker()
        repeat(43) { index ->
            tracker.record(presentationTimeUs = index * 120_000L, keyFrame = true)
        }

        val evidence = tracker.snapshot(
            submittedFrameCount = 128L,
            targetFps = 25,
            allIntraExpected = false
        )

        assertEquals(43L, evidence.encodedSampleCount)
        assertEquals(85L, evidence.submissionToEncodeGap)
        assertEquals(43L, evidence.keyFrameCount)
        assertEquals(8.333333, evidence.observedFps, 0.0001)
        assertTrue(evidence.unexpectedRepeatedKeyFrames)
        assertFalse(evidence.cadenceWithinTolerance)
        assertEquals(EncoderOutputCadenceTracker.STATUS_UNEXPECTED_ALL_INTRA, evidence.status)
    }

    @Test
    fun `one-second IP GOP at 25 fps proves stable output cadence`() {
        val tracker = EncoderOutputCadenceTracker()
        repeat(50) { index ->
            tracker.record(
                presentationTimeUs = index * 40_000L,
                keyFrame = index % 25 == 0
            )
        }

        val evidence = tracker.snapshot(
            submittedFrameCount = 50L,
            targetFps = 25,
            allIntraExpected = false
        )

        assertEquals(2L, evidence.keyFrameCount)
        assertFalse(evidence.unexpectedRepeatedKeyFrames)
        assertTrue(evidence.cadenceWithinTolerance)
        assertEquals(EncoderOutputCadenceTracker.STATUS_PROVEN, evidence.status)
    }

    @Test
    fun `truthful explicitly admitted all-intra is not reported as unexpected`() {
        val tracker = EncoderOutputCadenceTracker()
        repeat(25) { index ->
            tracker.record(presentationTimeUs = index * 40_000L, keyFrame = true)
        }

        val evidence = tracker.snapshot(
            submittedFrameCount = 25L,
            targetFps = 25,
            allIntraExpected = true
        )

        assertFalse(evidence.unexpectedRepeatedKeyFrames)
        assertTrue(evidence.cadenceWithinTolerance)
        assertEquals(EncoderOutputCadenceTracker.STATUS_PROVEN, evidence.status)
    }
}
