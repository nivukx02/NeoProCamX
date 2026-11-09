package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RawAudioTimelinePolicyTest {
    @Test fun sourceAssociationAcceptsOnlyItsOwnCompletedTimeline() {
        RawAudioTimelinePolicy.requireAssociation(1_000_000L, 81_000_000L, 25, 1_000_000L, 120_000L)
        assertThrows(IllegalStateException::class.java) {
            RawAudioTimelinePolicy.requireAssociation(1_000_000L, 81_000_000L, 25, 2_000_000L, 120_000L)
        }
        assertThrows(IllegalStateException::class.java) {
            RawAudioTimelinePolicy.requireAssociation(1_000_000L, 81_000_000L, 25, 1_000_000L, 80_000L)
        }
        assertThrows(IllegalStateException::class.java) {
            RawAudioTimelinePolicy.requireAssociation(1_000_000L, 81_000_000L, 25, 1_000_000L, -1L)
        }
    }

    @Test fun sourceAssociationRetainsStrictTwentyMillisecondBoundary() {
        RawAudioTimelinePolicy.requireAssociation(1_000L, 1_000L, 25, 1_000L, 60_000L)
        assertThrows(IllegalStateException::class.java) {
            RawAudioTimelinePolicy.requireAssociation(1_000L, 1_000L, 25, 1_000L, 60_001L)
        }
    }

    @Test fun singleFrameKeepsItsSelectedFrameDuration() {
        assertEquals(40_000L, RawAudioTimelinePolicy.durationUs(1_000L, 1_000L, 25))
    }

    @Test fun sourceClockIsNotReplacedByTheRequestedFps() {
        assertEquals(299_853_000L,
            RawAudioTimelinePolicy.durationUs(1_000_000_000L, 300_813_000_000L, 25))
    }

    @Test fun sourcePauseAndDropGapsRemainVisible() {
        assertEquals(5_120_000L,
            RawAudioTimelinePolicy.durationUs(1_000_000L, 5_081_000_000L, 25))
    }

    @Test fun pausedPcmUsesItsMeasuredSampleCountNotSchedulerTime() {
        assertEquals(2_000_000L, RawAudioTimelinePolicy.discardedPcmDurationUs(384_000, 48_000, 2))
        assertEquals(21_333L, RawAudioTimelinePolicy.discardedPcmDurationUs(4_096, 48_000, 2))
        assertEquals(0L, RawAudioTimelinePolicy.discardedPcmDurationUs(0, 48_000, 2))
    }

    @Test fun invalidTimelinesAndPartialPcmFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { RawAudioTimelinePolicy.durationUs(0, 1, 25) }
        assertThrows(IllegalArgumentException::class.java) { RawAudioTimelinePolicy.durationUs(2, 1, 25) }
        assertThrows(IllegalArgumentException::class.java) { RawAudioTimelinePolicy.durationUs(1, 2, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            RawAudioTimelinePolicy.discardedPcmDurationUs(3, 48_000, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawAudioTimelinePolicy.discardedPcmDurationUs(-4, 48_000, 2)
        }
    }
}
