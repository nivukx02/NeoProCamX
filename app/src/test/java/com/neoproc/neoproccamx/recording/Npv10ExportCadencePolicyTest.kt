package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10ExportCadencePolicyTest {
    @Test
    fun `selected header fps owns mov cadence instead of variable sensor timestamps`() {
        assertEquals(0L, Npv10ExportCadencePolicy.presentationTimeUs(frameIndex = 0L, fps = 25))
        assertEquals(40_000L, Npv10ExportCadencePolicy.presentationTimeUs(frameIndex = 1L, fps = 25))
        assertEquals(3_680_000L, Npv10ExportCadencePolicy.presentationTimeUs(frameIndex = 92L, fps = 25))
    }

    @Test
    fun `rational cadence has exact one-second and one-day boundaries at supported rates`() {
        listOf(24, 25, 30, 60).forEach { fps ->
            assertEquals(
                "one-second boundary at $fps fps",
                1_000_000L,
                Npv10ExportCadencePolicy.presentationTimeUs(fps.toLong(), fps)
            )
            val oneDayFrames = fps.toLong() * 24L * 60L * 60L
            assertEquals(
                "one-day boundary at $fps fps",
                86_400_000_000L,
                Npv10ExportCadencePolicy.endPresentationTimeUs(oneDayFrames, fps)
            )
        }
    }

    @Test
    fun `fractional microsecond periods distribute remainder without cumulative drift`() {
        assertEquals(0L, Npv10ExportCadencePolicy.presentationTimeUs(0L, 24))
        assertEquals(41_666L, Npv10ExportCadencePolicy.presentationTimeUs(1L, 24))
        assertEquals(83_333L, Npv10ExportCadencePolicy.presentationTimeUs(2L, 24))
        assertEquals(125_000L, Npv10ExportCadencePolicy.endPresentationTimeUs(3L, 24))

        val durations = (0L until 24L).map { index ->
            Npv10ExportCadencePolicy.presentationTimeUs(index + 1L, 24) -
                Npv10ExportCadencePolicy.presentationTimeUs(index, 24)
        }
        assertTrue(durations.all { it == 41_666L || it == 41_667L })
        assertEquals(1_000_000L, durations.sum())
    }

    @Test
    fun `cadence rejects invalid inputs and overflowing timelines`() {
        val negativeIndex = runCatching {
            Npv10ExportCadencePolicy.presentationTimeUs(-1L, 24)
        }.exceptionOrNull()
        val zeroFps = runCatching {
            Npv10ExportCadencePolicy.presentationTimeUs(1L, 0)
        }.exceptionOrNull()
        val overflow = runCatching {
            Npv10ExportCadencePolicy.presentationTimeUs(Long.MAX_VALUE, 1)
        }.exceptionOrNull()

        assertTrue(negativeIndex is IllegalArgumentException)
        assertTrue(zeroFps is IllegalArgumentException)
        assertTrue(overflow is ArithmeticException)
    }

    @Test
    fun `frame count authority requires exact source submitted and finalized equality`() {
        Npv10ExportFrameCountPolicy.requireExactMatch(
            expectedFrameCount = 5,
            sourceFrameCount = 5L,
            submittedFrameCount = 5L,
            finalizedFrameCount = 5L
        )

        listOf(
            longArrayOf(4L, 5L, 5L),
            longArrayOf(5L, 4L, 5L),
            longArrayOf(5L, 5L, 0L)
        ).forEach { counts ->
            val failure = runCatching {
                Npv10ExportFrameCountPolicy.requireExactMatch(
                    expectedFrameCount = 5,
                    sourceFrameCount = counts[0],
                    submittedFrameCount = counts[1],
                    finalizedFrameCount = counts[2]
                )
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        }
    }
}
