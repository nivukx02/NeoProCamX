package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.stabilization.OrientationTrajectorySmoother.TimedOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationTrajectorySmootherTest {

    private fun yawPath(angles: List<Float>, dtNs: Long = 16_666_667L): List<TimedOrientation> =
        angles.mapIndexed { i, a ->
            TimedOrientation(i.toLong() * dtNs, Quaternion.fromAxisAngle(0f, 1f, 0f, a))
        }

    @Test
    fun emptyPathYieldsEmpty() {
        assertTrue(OrientationTrajectorySmoother.smooth(emptyList(), 2f).isEmpty())
    }

    @Test
    fun constantOrientationNeedsNoCorrection() {
        val path = yawPath(List(20) { 0.1f })
        val smoothed = OrientationTrajectorySmoother.smooth(path, cutoffHz = 2f)
        for (f in smoothed) assertEquals(0f, f.residual.angleRad(), 1e-4f)
    }

    @Test
    fun firstFramePassesThrough() {
        val path = yawPath(listOf(0.05f, 0.2f, -0.1f))
        val smoothed = OrientationTrajectorySmoother.smooth(path, cutoffHz = 2f)
        assertEquals(0f, smoothed[0].residual.angleRad(), 1e-5f)
        assertEquals(path[0].orientation.angleRad(), smoothed[0].smoothed.angleRad(), 1e-5f)
    }

    @Test
    fun smoothingReducesJitterRelativeToRaw() {
        // Alternating +/- jitter around a fixed mean angle.
        val angles = (0 until 40).map { 0.1f + if (it % 2 == 0) 0.06f else -0.06f }
        val path = yawPath(angles)
        val smoothed = OrientationTrajectorySmoother.smooth(path, cutoffHz = 1.5f)
        // Range of the smoothed yaw must be tighter than the raw +/-0.06 swing.
        val smoothedYaw = smoothed.drop(5).map { it.smoothed.rotationVector()[1] }
        val smoothedRange = smoothedYaw.max() - smoothedYaw.min()
        assertTrue("smoothing should shrink the jitter range, was $smoothedRange", smoothedRange < 0.06f)
        // And the residual correction must actually be doing something.
        val meanResidual = smoothed.drop(5).map { it.residual.angleRad() }.average()
        assertTrue("residual correction should be non-trivial: $meanResidual", meanResidual > 1e-3)
    }

    @Test
    fun higherCutoffTracksMoreCloselyThanLowerCutoff() {
        // A steady yaw ramp; a higher cutoff lags less, so its residual correction is smaller.
        val path = yawPath((0 until 30).map { it * 0.01f })
        val tight = OrientationTrajectorySmoother.smooth(path, cutoffHz = 8f)
        val loose = OrientationTrajectorySmoother.smooth(path, cutoffHz = 1f)
        val tightResidual = tight.last().residual.angleRad()
        val looseResidual = loose.last().residual.angleRad()
        assertTrue("tighter cutoff should track closer: tight=$tightResidual loose=$looseResidual", tightResidual < looseResidual)
    }
}
