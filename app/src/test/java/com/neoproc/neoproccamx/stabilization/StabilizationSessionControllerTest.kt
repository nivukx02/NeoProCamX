package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilizationSessionControllerTest {

    private val cutoffHz = 1.2f
    private val dtNs = 33_333_333L // 30 fps
    private val frames = 120

    private fun config() = StabilizationSessionController.Config(
        cutoffHz = cutoffHz,
        focalLengthPx = 1000f,
        cropMarginPixels = 200f,
        rows = 8,
        referenceRow = 4,
        centerX = 960f,
        centerY = 540f,
    )

    // A fixed 2 ms gyro window so the rolling-shutter leg always has coverage; the σ tests read only the
    // smoothed trajectory, so the exact RS values do not matter — they must merely not crash.
    private fun rsSamples(): List<CameraMotionTelemetry> = (0..20).map { i ->
        CameraMotionTelemetry(
            timestampNs = i * 100_000L,
            oisShiftXPixels = 0f,
            oisShiftYPixels = 0f,
            gyroXRadPerSec = 0f,
            gyroYRadPerSec = 0.2f,
            gyroZRadPerSec = 0f,
        )
    }

    /** Raw device yaw for frame [i]: a slow pan drift plus an 8 Hz handshake. */
    private fun rawYaw(i: Int): Float {
        val tSec = i / 30.0
        val pan = 0.15 * tSec
        val shake = 0.012 * sin(2.0 * Math.PI * 8.0 * tSec)
        return (pan + shake).toFloat()
    }

    private fun orientation(i: Int): EisFrameOrientation = EisFrameOrientation(
        frameMidTimestampNs = i * dtNs,
        orientationAtFrameMid = Quaternion.fromAxisAngle(0f, 1f, 0f, rawYaw(i)),
        acrossExposureRotation = Quaternion.IDENTITY,
    )

    /** Std-dev of the geodesic angle between consecutive orientations (the on-path "shake"). */
    private fun jitterSigma(path: List<Quaternion>): Double {
        val deltas = (0 until path.size - 1).map {
            (path[it].conjugate() * path[it + 1]).normalized().angleRad().toDouble()
        }
        val mean = deltas.average()
        return sqrt(deltas.sumOf { (it - mean) * (it - mean) } / deltas.size)
    }

    @Test
    fun defaultModeIsOffAndYieldsIdentityGridUnderStrongShake() {
        val ctrl = StabilizationSessionController(config())
        assertEquals(StabilizationModePolicy.StabilizationMode.OFF, ctrl.mode)
        repeat(frames) { i ->
            val grid = ctrl.onFrame(orientation(i), rsSamples(), frameStartNs = 0L, rowReadoutNs = 200_000L, 0f, 0f)
            assertEquals(config().rows, grid.rows)
            assertTrue("OFF must stay an identity grid (no-downgrade), frame $i", grid.isIdentity)
        }
    }

    @Test
    fun incrementalControllerMatchesGoldenBatchSmootherFrameForFrame() {
        // The controller's per-frame recurrence must be bit-identical to the proven batch smoother, so
        // wiring it live cannot silently change the smoothed path.
        val path = (0 until frames).map {
            OrientationTrajectorySmoother.TimedOrientation(it * dtNs, orientation(it).orientationAtFrameMid)
        }
        val batch = OrientationTrajectorySmoother.smooth(path, cutoffHz)

        val ctrl = StabilizationSessionController(config()).apply {
            mode = StabilizationModePolicy.StabilizationMode.STANDARD
        }
        for (i in 0 until frames) {
            ctrl.onFrame(orientation(i), rsSamples(), frameStartNs = 0L, rowReadoutNs = 200_000L, 0f, 0f)
            val incremental = ctrl.lastSmoothedFrame!!.smoothed
            val golden = batch[i].smoothed
            assertTrue(
                "frame $i smoothed diverged from batch smoother",
                abs(incremental.dot(golden)) > 0.999_99f,
            )
        }
    }

    @Test
    fun standardModeReducesTrajectoryJitterByAtLeastSixtyPercent() {
        // G2: gyro-EIS ON must cut the measured global-motion trajectory jitter σ >= 60% vs the raw path.
        val ctrl = StabilizationSessionController(config()).apply {
            mode = StabilizationModePolicy.StabilizationMode.STANDARD
        }
        val raws = ArrayList<Quaternion>(frames)
        val smootheds = ArrayList<Quaternion>(frames)
        for (i in 0 until frames) {
            ctrl.onFrame(orientation(i), rsSamples(), frameStartNs = 0L, rowReadoutNs = 200_000L, 0f, 0f)
            raws.add(orientation(i).orientationAtFrameMid.normalized())
            smootheds.add(ctrl.lastSmoothedFrame!!.smoothed)
        }
        val sigmaRaw = jitterSigma(raws)
        val sigmaSmoothed = jitterSigma(smootheds)
        val reduction = (sigmaRaw - sigmaSmoothed) / sigmaRaw
        assertTrue(
            "σ reduction $reduction below the 60% G2 target (raw=$sigmaRaw smoothed=$sigmaSmoothed)",
            reduction >= 0.60,
        )
    }

    @Test
    fun standardModeStillTracksTheIntendedPanInsteadOfFreezing() {
        // The stabilizer must FOLLOW the deliberate pan (only cancel the shake), not lock to identity —
        // otherwise it would fight the operator's intended motion.
        val ctrl = StabilizationSessionController(config()).apply {
            mode = StabilizationModePolicy.StabilizationMode.STANDARD
        }
        for (i in 0 until frames) {
            ctrl.onFrame(orientation(i), rsSamples(), frameStartNs = 0L, rowReadoutNs = 200_000L, 0f, 0f)
        }
        val smoothedYaw = ctrl.lastSmoothedFrame!!.smoothed.rotationVector()[1]
        val finalPanYaw = 0.15f * (frames - 1) / 30f
        assertTrue(
            "smoothed path over-damped (yaw=$smoothedYaw, pan=$finalPanYaw) — it froze instead of tracking",
            smoothedYaw > 0.5f * finalPanYaw,
        )
    }

    @Test
    fun standardModeGridStaysWithinCropMarginEveryRow() {
        val margin = 24f
        val ctrl = StabilizationSessionController(config().copy(cropMarginPixels = margin)).apply {
            mode = StabilizationModePolicy.StabilizationMode.STANDARD
        }
        var checkedNonTrivial = false
        for (i in 0 until frames) {
            val grid = ctrl.onFrame(orientation(i), rsSamples(), frameStartNs = 0L, rowReadoutNs = 200_000L, 0f, 0f)
            grid.perRow.forEachIndexed { r, c ->
                assertTrue("row $r dx exceeds crop margin: ${c.dxPixels}", abs(c.dxPixels) <= margin + 1e-3f)
                assertTrue("row $r dy exceeds crop margin: ${c.dyPixels}", abs(c.dyPixels) <= margin + 1e-3f)
            }
            if (!grid.isIdentity) checkedNonTrivial = true
        }
        assertTrue("STANDARD with real motion should drive a non-identity grid", checkedNonTrivial)
    }

    @Test
    fun resetDropsAccumulatedTrajectory() {
        val ctrl = StabilizationSessionController(config()).apply {
            mode = StabilizationModePolicy.StabilizationMode.STANDARD
        }
        repeat(30) { i -> ctrl.onFrame(orientation(i), rsSamples(), 0L, 200_000L, 0f, 0f) }
        ctrl.reset()
        assertTrue(ctrl.lastSmoothedFrame == null)
        // After reset the first frame passes through: smoothed == raw (residual is identity).
        ctrl.onFrame(orientation(50), rsSamples(), 0L, 200_000L, 0f, 0f)
        val f = ctrl.lastSmoothedFrame!!
        assertTrue("first frame after reset must pass through", abs(f.raw.dot(f.smoothed)) > 0.999_99f)
    }
}
