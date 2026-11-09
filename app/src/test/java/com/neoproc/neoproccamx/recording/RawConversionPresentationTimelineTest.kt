package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RawConversionPresentationTimelineTest {

    @Test
    fun realisticThirtyFpsSensorFixtureRemainsStrictlyMonotonicAfterMicrosecondNormalization() {
        val timeline = RawConversionPresentationTimeline()
        val fps = 30
        val frameDurationNs = 1_000_000_000L / fps
        val sensorTimestampOriginNs = 1_000_000_000L

        val presentationTimesUs = List(3) { frameIndex ->
            timeline.normalize(
                frameIndex,
                sensorTimestampOriginNs + frameIndex * frameDurationNs
            )
        }

        assertEquals(listOf(0L, 33_333L, 66_666L), presentationTimesUs)
    }

    @Test
    fun preservesVariableSourceCadenceOnAZeroBasedMicrosecondTimeline() {
        val timeline = RawConversionPresentationTimeline()

        assertEquals(0L, timeline.normalize(0, 9_000_000_000L))
        assertEquals(40_000L, timeline.normalize(1, 9_040_000_000L))
        assertEquals(85_125L, timeline.normalize(2, 9_085_125_000L))
        assertEquals(3, timeline.frameCount)
        assertEquals(130_250L, timeline.endPresentationTimeUs(40_000L))
    }

    @Test
    fun oneFrameClipUsesNominalDurationOnlyWhenNoMeasuredIntervalExists() {
        val timeline = RawConversionPresentationTimeline()

        assertEquals(0L, timeline.normalize(0, 9_000_000_000L))
        assertEquals(41_666L, timeline.endPresentationTimeUs(41_666L))
    }

    @Test
    fun rejectsReorderDuplicateAndMicrosecondCollapseInsteadOfInventingCadence() {
        val duplicate = RawConversionPresentationTimeline().apply {
            normalize(0, 1_000_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            duplicate.normalize(1, 1_000_000L)
        }

        val collapsed = RawConversionPresentationTimeline().apply {
            normalize(0, 1_000_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            collapsed.normalize(1, 1_000_500L)
        }

        val wrongIndex = RawConversionPresentationTimeline()
        assertThrows(IllegalArgumentException::class.java) {
            wrongIndex.normalize(1, 1_000_000L)
        }
    }
}
