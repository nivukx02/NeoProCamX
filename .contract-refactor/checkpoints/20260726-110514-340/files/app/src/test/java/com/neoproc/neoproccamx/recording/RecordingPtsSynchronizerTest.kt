package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPtsSynchronizerTest {

    @Test
    fun sensorTimestampMappingAnchorsFirstFrameAndAppliesLatencyCompensation() {
        val synchronizer = RecordingPtsSynchronizer(initialLatencyCompensationUs = 2_500L)

        val firstPts = synchronizer.ptsUsForSensorTimestamp(1_000_000_000L)
        val secondPts = synchronizer.ptsUsForSensorTimestamp(1_033_333_333L)

        assertEquals(2_500L, firstPts)
        assertEquals(35_833L, secondPts)
    }

    @Test
    fun sensorTimestampMappingIsMonotonicWhenInputClockJittersBackward() {
        val synchronizer = RecordingPtsSynchronizer(initialLatencyCompensationUs = 0L)

        val firstPts = synchronizer.ptsUsForSensorTimestamp(2_000_000_000L)
        val jitteredPts = synchronizer.ptsUsForSensorTimestamp(1_999_990_000L)

        assertEquals(0L, firstPts)
        assertTrue(jitteredPts > firstPts)
    }

    @Test
    fun dispatchLatencyUsesSlidingWindowAndSignatureDocumentsTruth() {
        val synchronizer = RecordingPtsSynchronizer(initialLatencyCompensationUs = 0L)

        synchronizer.recordDispatchLatency(sensorTimestampNs = 10_000_000L, completionNs = 15_000_000L)
        synchronizer.recordDispatchLatency(sensorTimestampNs = 20_000_000L, completionNs = 23_000_000L)

        assertTrue(synchronizer.currentLatencyCompensationUs in 3_000L..5_000L)
        assertTrue(synchronizer.signature().contains("sensor-to-encoder-pts=linear"))
        assertTrue(synchronizer.signature().contains("latency=sliding-window"))
        assertTrue(synchronizer.signature().contains("monotonic=true"))
    }
}
