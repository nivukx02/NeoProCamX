package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordDevelopPresentationTimelineTest {

    @Test
    fun zeroBasesFirstPtsAndPreservesEveryVariableSourceInterval() {
        val timeline = RecordDevelopPresentationTimeline(nominalFrameDurationUs = 40_000L)

        assertEquals(0L, timeline.normalize(0L, 8_500_000L))
        assertEquals(40_000L, timeline.normalize(1L, 8_540_000L))
        assertEquals(83_125L, timeline.normalize(2L, 8_583_125L))
        assertEquals(123_375L, timeline.normalize(3L, 8_623_375L))
        assertEquals(4L, timeline.frameCount)
        assertEquals(163_625L, timeline.endPresentationTimeUs())
    }

    @Test
    fun oneFrameClipUsesNominalDurationOnlyAsFinalDurationFallback() {
        val timeline = RecordDevelopPresentationTimeline(nominalFrameDurationUs = 41_666L)

        assertEquals(0L, timeline.normalize(0L, 0L))
        assertEquals(41_666L, timeline.endPresentationTimeUs())
    }

    @Test
    fun containerDurationPreservesAShortFinalSampleThatPtsDeltasCannotReveal() {
        val timeline = RecordDevelopPresentationTimeline(nominalFrameDurationUs = 111_111L)

        repeat(20) { index ->
            assertEquals(index * 120_000L, timeline.normalize(index.toLong(), index * 120_000L))
        }

        // The first 19 samples are 120 ms; the source stts makes only the final sample 40 ms.
        assertEquals(2_400_000L, timeline.endPresentationTimeUs())
        assertEquals(2_320_000L, timeline.endPresentationTimeUs(sourceDurationUs = 2_320_000L))
    }

    @Test
    fun rejectsContainerDurationThatDoesNotExtendPastTheFinalSample() {
        val timeline = RecordDevelopPresentationTimeline(nominalFrameDurationUs = 40_000L).apply {
            normalize(0L, 0L)
            normalize(1L, 40_000L)
        }

        assertThrows(IllegalArgumentException::class.java) {
            timeline.endPresentationTimeUs(sourceDurationUs = 40_000L)
        }
    }

    @Test
    fun explicitDurationIsZeroBasedAndWorksForNonzeroPtsOriginsAndOneFrameClips() {
        val offsetTimeline = RecordDevelopPresentationTimeline(nominalFrameDurationUs = 40_000L).apply {
            normalize(0L, 7_000_000L)
            normalize(1L, 7_040_000L)
            normalize(2L, 7_083_000L)
        }
        assertEquals(126_000L, offsetTimeline.endPresentationTimeUs(sourceDurationUs = 126_000L))

        val singleFrame = RecordDevelopPresentationTimeline(nominalFrameDurationUs = 40_000L).apply {
            normalize(0L, 9_000_000L)
        }
        assertEquals(17_000L, singleFrame.endPresentationTimeUs(sourceDurationUs = 17_000L))
    }

    @Test
    fun allZeroUnknownTimingSentinelUsesDeterministicNominalCadence() {
        val timeline = RecordDevelopPresentationTimeline(nominalFrameDurationUs = 40_000L)

        assertEquals(0L, timeline.normalize(0L, 0L))
        assertEquals(40_000L, timeline.normalize(1L, 0L))
        assertEquals(80_000L, timeline.normalize(2L, 0L))
        assertEquals(120_000L, timeline.endPresentationTimeUs())
    }

    @Test
    fun rejectsDuplicateRegressingNegativeAndOutOfOrderTimestamps() {
        val duplicate = RecordDevelopPresentationTimeline(40_000L).apply {
            normalize(0L, 100_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            duplicate.normalize(1L, 100_000L)
        }

        val regressing = RecordDevelopPresentationTimeline(40_000L).apply {
            normalize(0L, 100_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            regressing.normalize(1L, 99_999L)
        }

        assertThrows(IllegalArgumentException::class.java) {
            RecordDevelopPresentationTimeline(40_000L).normalize(0L, -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecordDevelopPresentationTimeline(40_000L).normalize(1L, 100_000L)
        }

        val mixedSentinel = RecordDevelopPresentationTimeline(40_000L).apply {
            normalize(0L, 0L)
            normalize(1L, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            mixedSentinel.normalize(2L, 80_000L)
        }
    }

    @Test
    fun failsClosedOnEmptyTimelineAndArithmeticOverflow() {
        assertThrows(IllegalArgumentException::class.java) {
            RecordDevelopPresentationTimeline(0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecordDevelopPresentationTimeline(40_000L).endPresentationTimeUs()
        }

        val overflow = RecordDevelopPresentationTimeline(2L)
        overflow.normalize(0L, 0L)
        overflow.normalize(1L, Long.MAX_VALUE)
        assertThrows(ArithmeticException::class.java) {
            overflow.endPresentationTimeUs()
        }
    }
}
