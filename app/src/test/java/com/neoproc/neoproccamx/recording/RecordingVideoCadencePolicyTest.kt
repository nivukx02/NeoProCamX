package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingVideoCadencePolicyTest {
    @Test
    fun videoOnlyRecordingPreservesRealForwardTimestampSoDurationMatchesElapsedTime() {
        // Regression for the "10s recording saved as ~1s" bug: a sustained sub-target capture
        // rate (here a 433ms real gap from a slow P010 readout) must KEEP its true elapsed
        // timestamp instead of being compacted to one 1/fps step. Compacting real forward gaps
        // collapsed the clip duration ~8x.
        val previousPtsUs = 1_033_313L
        val cameraPtsAfterStallUs = 1_466_625L

        val resolved = RecordingVideoCadencePolicy.resolvePresentationTimeUs(
            candidateOutputPtsUs = cameraPtsAfterStallUs,
            previousOutputPtsUs = previousPtsUs,
            frameRate = 30,
            constantCadence = false
        )

        assertEquals(cameraPtsAfterStallUs, resolved)
    }

    @Test
    fun realtimeSurfaceRecordingCompactsSourceTimestampJitterToNominalCadence() {
        val previousPtsUs = 1_033_313L
        val jitteredSurfacePtsUs = 1_109_642L

        val resolved = RecordingVideoCadencePolicy.resolvePresentationTimeUs(
            candidateOutputPtsUs = jitteredSurfacePtsUs,
            previousOutputPtsUs = previousPtsUs,
            frameRate = 30,
            constantCadence = true
        )

        assertEquals(previousPtsUs + 33_333L, resolved)
    }

    @Test
    fun realTimestampModeCompactsSubFrameJitterToKeepMovDtsMonotonic() {
        val previousPtsUs = 1_033_313L
        val tinyForwardJitterUs = 1_033_314L

        val resolved = RecordingVideoCadencePolicy.resolvePresentationTimeUs(
            candidateOutputPtsUs = tinyForwardJitterUs,
            previousOutputPtsUs = previousPtsUs,
            frameRate = 30,
            constantCadence = false
        )

        assertEquals(previousPtsUs + 33_333L, resolved)
    }

    @Test
    fun audioEnabledRecordingPreservesForwardCameraTimestampForSync() {
        val previousPtsUs = 1_033_313L
        val cameraPtsAfterStallUs = 1_466_625L

        val resolved = RecordingVideoCadencePolicy.resolvePresentationTimeUs(
            candidateOutputPtsUs = cameraPtsAfterStallUs,
            previousOutputPtsUs = previousPtsUs,
            frameRate = 30,
            constantCadence = false
        )

        assertEquals(cameraPtsAfterStallUs, resolved)
    }

    @Test
    fun nonIncreasingTimestampStillAdvancesOneFrameStep() {
        val previousPtsUs = 1_033_313L

        val resolved = RecordingVideoCadencePolicy.resolvePresentationTimeUs(
            candidateOutputPtsUs = previousPtsUs,
            previousOutputPtsUs = previousPtsUs,
            frameRate = 30,
            constantCadence = false
        )

        assertEquals(previousPtsUs + 33_333L, resolved)
    }
}
