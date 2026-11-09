package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP-QV18 — integration golden-lock for the dormant custom gyro-EIS CPU pipeline.
 *
 * TESTS ONLY. No production code is touched. These lock end-to-end INTEGRATION invariants of the
 * gyro-EIS stack ([StabilizationSessionController] -> [OrientationTrajectorySmoother] fold ->
 * [EisWarpGridProducer] -> [RollingShutterGrid] -> [OisEisFusion] -> [GyroEisWarp.clampToCropMargin])
 * that the existing per-unit / per-controller tests do NOT already assert, so the flagship
 * stabilization stack is provably correct before any device-side activation.
 *
 * Deliberately non-overlapping with:
 *   - StabilizationSessionControllerTest (locks isIdentity-only for OFF, dot>0.99999 fold, σ reduction,
 *     per-row margin under mixed shake) — here we lock the PACKED bit-identity, an EXACT (0f-eps)
 *     component fold over a NON-UNIFORM-dt path, and correction MONOTONICITY.
 *   - OisEisFusionTest (locks fuse() at the unit level) — here we lock the OIS sign THROUGH the full
 *     producer chain (grid output), i.e. no double-correction of OIS-handled motion.
 */
class EisPipelineFoundationGoldenTest {

    private val cutoffHz = 1.2f
    private val focalLengthPx = 1000f
    private val rows = 12
    private val referenceRow = rows / 2 // centre row => frame pivots about its middle
    private val centerX = 960f
    private val centerY = 540f
    private val rowReadoutNs = 200_000L // 200 µs / row => ~2.2 ms readout for 12 rows
    private val fps30Ns = 33_333_333L

    private fun config(cropMarginPixels: Float = 200f) = StabilizationSessionController.Config(
        cutoffHz = cutoffHz,
        focalLengthPx = focalLengthPx,
        cropMarginPixels = cropMarginPixels,
        rows = rows,
        referenceRow = referenceRow,
        centerX = centerX,
        centerY = centerY,
    )

    /** A steady yaw-rate gyro window (oldest first) spanning past the frame's row-readout window. */
    private fun yawStream(rateRadPerSec: Float): List<CameraMotionTelemetry> =
        (0..40).map { i ->
            CameraMotionTelemetry(
                timestampNs = i * 100_000L, // every 100 µs, spans [0, 4 ms]
                oisShiftXPixels = 0f,
                oisShiftYPixels = 0f,
                gyroXRadPerSec = 0f,
                gyroYRadPerSec = rateRadPerSec, // yaw about +Y
                gyroZRadPerSec = 0f,
                source = CameraMotionTelemetry.Source.GYRO,
            )
        }

    // ---------------------------------------------------------------------------------------------
    // GOLDEN 1 — OFF-mode identity (no-downgrade) AND state still advances for a seamless switch.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun offModeYieldsPackedIdentityGridWhileStillAdvancingTrajectory() {
        // Non-trivial synthetic motion: a growing yaw with per-frame handshake wobble.
        fun rawYaw(i: Int): Float {
            val t = i / 30.0
            return (0.2 * t + 0.02 * kotlin.math.sin(2.0 * Math.PI * 7.0 * t)).toFloat()
        }
        fun orientation(i: Int) = EisFrameOrientation(
            frameMidTimestampNs = i * fps30Ns,
            orientationAtFrameMid = Quaternion.fromAxisAngle(0f, 1f, 0f, rawYaw(i)),
            acrossExposureRotation = Quaternion.IDENTITY,
        )

        val ctrl = StabilizationSessionController(config())
        assertEquals(
            "no-downgrade default must be OFF",
            StabilizationModePolicy.StabilizationMode.OFF,
            ctrl.mode,
        )

        val frames = 90
        for (i in 0 until frames) {
            val grid = ctrl.onFrame(
                orientation(i), yawStream(0.3f),
                frameStartNs = 0L, rowReadoutNs = rowReadoutNs,
                oisShiftXPixels = 0f, oisShiftYPixels = 0f,
            )
            assertEquals("grid must have config.rows rows", rows, grid.rows)
            assertTrue("OFF must stay an identity grid (frame $i)", grid.isIdentity)
            // Genuinely-new lock: the PACKED buffer the shader samples is bit-for-bit identity, not just
            // the isIdentity flag — every (dx,dy,roll) lane exactly 0f, alpha lane exactly 1f.
            val packed = grid.packGridRgba()
            assertEquals(rows * 4, packed.size)
            for (r in 0 until rows) {
                assertEquals("packed dx row $r frame $i", 0f, packed[r * 4], 0f)
                assertEquals("packed dy row $r frame $i", 0f, packed[r * 4 + 1], 0f)
                assertEquals("packed roll row $r frame $i", 0f, packed[r * 4 + 2], 0f)
                assertEquals("packed alpha row $r frame $i", 1f, packed[r * 4 + 3], 0f)
            }
        }

        // Genuinely-new lock: even though OFF emits an identity grid, the smoother state HAS advanced
        // (advanceTrajectory runs regardless of mode), so a later mode switch is seamless — the smoothed
        // path is NOT frozen at identity, it is tracking the driven motion.
        val last = ctrl.lastSmoothedFrame
        assertNotNull("OFF must still advance/expose the smoothed trajectory", last)
        assertTrue(
            "OFF smoothed path must track the driven yaw (not frozen at identity)",
            last!!.smoothed.angleRad() > 0.05f,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // GOLDEN 2 — streaming fold == batch smoother, EXACT (0f eps), over a NON-UNIFORM-dt path.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun streamingFoldIsBitIdenticalToBatchSmootherOverNonUniformTimestamps() {
        // Irregular frame timestamps (variable dt, incl. a duplicate-timestamp dt<=0 frame) so this
        // exercises the recurrence branches the uniform-30fps existing test never does.
        val timestamps = longArrayOf(
            0L,
            30_000_000L,
            30_000_000L,          // dt == 0 -> smoother must HOLD (both batch & fold)
            62_000_000L,          // 32 ms
            95_000_000L,          // 33 ms
            111_000_000L,         // 16 ms
            160_000_000L,         // 49 ms (dropped frame)
            193_000_000L,
            226_000_000L,
            259_000_000L,
        )
        val yaws = floatArrayOf(0.00f, 0.05f, 0.06f, -0.03f, 0.12f, 0.11f, 0.20f, 0.19f, 0.25f, 0.22f)

        fun orientation(i: Int) = EisFrameOrientation(
            frameMidTimestampNs = timestamps[i],
            orientationAtFrameMid = Quaternion.fromAxisAngle(0f, 1f, 0f, yaws[i]),
            acrossExposureRotation = Quaternion.IDENTITY,
        )

        // Golden: the batch smoother over the identical (timestamp, orientation) path.
        val path = timestamps.indices.map {
            OrientationTrajectorySmoother.TimedOrientation(
                timestamps[it], orientation(it).orientationAtFrameMid,
            )
        }
        val batch = OrientationTrajectorySmoother.smooth(path, cutoffHz)

        val ctrl = StabilizationSessionController(config()).apply {
            mode = StabilizationModePolicy.StabilizationMode.STANDARD
        }
        for (i in timestamps.indices) {
            ctrl.onFrame(
                orientation(i), yawStream(0.3f),
                frameStartNs = 0L, rowReadoutNs = rowReadoutNs,
                oisShiftXPixels = 0f, oisShiftYPixels = 0f,
            )
            val fold = ctrl.lastSmoothedFrame!!
            val gold = batch[i]
            // Bit-exact: the fold reproduces the batch smoother's one-pole SLERP recurrence exactly.
            // Both compute alpha=1-exp(-2π·fc·dt) from the SAME dt and slerp from the SAME prior, so the
            // FloatArray/quaternion components must be equal to 0f (identical operations, identical order).
            assertQuatExact("smoothed[$i]", gold.smoothed, fold.smoothed)
            assertQuatExact("raw[$i]", gold.raw, fold.raw)
            // residual = raw⁻¹·smoothed, recomputed identically in both -> also bit-exact.
            assertQuatExact("residual[$i]", gold.residual, fold.residual)
            assertEquals("timestampNs[$i]", gold.timestampNs, fold.timestampNs)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GOLDEN 3 — known constant-velocity pan -> monotonic (no sign-flip) correction + border-safe.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun constantVelocityPanGivesMonotonicCounterRotationAndNeverRevealsBorder() {
        val margin = 40f
        val ctrl = StabilizationSessionController(config(cropMarginPixels = margin)).apply {
            mode = StabilizationModePolicy.StabilizationMode.STANDARD
        }
        // A perfectly steady yaw pan: yaw(i) = rate * t, no shake at all -> the smoother lags the pan by
        // a monotonically-growing amount, so the STANDARD residual correction must counter-rotate in ONE
        // direction only (no oscillation / sign-flip) and settle, never crossing zero after it starts.
        val panRateRadPerSec = 0.35f
        val frames = 80
        fun orientation(i: Int): EisFrameOrientation {
            val t = i / 30.0
            return EisFrameOrientation(
                frameMidTimestampNs = i * fps30Ns,
                orientationAtFrameMid = Quaternion.fromAxisAngle(0f, 1f, 0f, (panRateRadPerSec * t).toFloat()),
                acrossExposureRotation = Quaternion.IDENTITY,
            )
        }

        // Global-frame correction dx (row-independent part) derived from the mode residual; we read it via
        // the produced grid's clamped rows are noisy from RS, so track the mode-policy correction directly
        // from the exposed smoothed frame — this is the trajectory the grid is built from.
        val correctionDx = ArrayList<Float>(frames)
        var sawNonIdentity = false
        for (i in 0 until frames) {
            val grid = ctrl.onFrame(
                orientation(i), yawStream(panRateRadPerSec),
                frameStartNs = 0L, rowReadoutNs = rowReadoutNs,
                oisShiftXPixels = 0f, oisShiftYPixels = 0f,
            )
            // (b) NEVER-REVEAL-BORDER: every packed row's translation stays inside the crop margin.
            val packed = grid.packGridRgba()
            for (r in 0 until rows) {
                assertTrue(
                    "frame $i row $r dx=${packed[r * 4]} outside crop margin ±$margin",
                    abs(packed[r * 4]) <= margin + 1e-3f,
                )
                assertTrue(
                    "frame $i row $r dy=${packed[r * 4 + 1]} outside crop margin ±$margin",
                    abs(packed[r * 4 + 1]) <= margin + 1e-3f,
                )
            }
            if (!grid.isIdentity) sawNonIdentity = true

            // (a) MONOTONICITY: read the mode-selected GLOBAL correction (before RS/clamp) from the
            // exposed smoothed frame; a constant pan must give a single-signed, non-oscillating dx.
            val frame = ctrl.lastSmoothedFrame!!
            val global = StabilizationModePolicy.correction(
                StabilizationModePolicy.StabilizationMode.STANDARD,
                frame, ctrl.lockedReference, focalLengthPx, /* huge margin so we see the true (unclamped) sign */ 1e9f,
            )
            correctionDx.add(global.dxPixels)
        }

        assertTrue("a steady pan must drive a non-identity grid at STANDARD", sawNonIdentity)

        // Frame 0 is a pass-through (residual identity) -> dx == 0. From frame 1 the correction must be
        // strictly one-signed. A +yaw pan with the smoother LAGGING means smoothed<raw, so the residual
        // rotation raw⁻¹·smoothed is a NEGATIVE yaw, giving dx = f·tan(negative yaw) < 0 (and roll unchanged).
        assertEquals("first frame is a pass-through (identity residual)", 0f, correctionDx[0], 1e-4f)
        for (i in 1 until frames) {
            assertTrue(
                "frame $i correction dx=${correctionDx[i]} flipped sign — counter-rotation must be one-signed",
                correctionDx[i] <= 1e-4f,
            )
        }
        // Monotone in magnitude over the transient (correction grows more negative, then plateaus): assert
        // NON-OSCILLATION — dx is monotonically non-increasing (never rebounds toward zero) as lag builds.
        for (i in 2 until frames) {
            assertTrue(
                "frame $i correction dx=${correctionDx[i]} rebounded above previous ${correctionDx[i - 1]} " +
                    "(oscillation) — a steady pan must give monotone counter-rotation",
                correctionDx[i] <= correctionDx[i - 1] + 1e-3f,
            )
        }
        // And it must actually be doing something (not a degenerate all-zero trajectory).
        assertTrue(
            "steady pan must produce a real counter-rotation, dx_final=${correctionDx.last()}",
            correctionDx.last() < -1f,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // GOLDEN 4 — OIS sign THROUGH the full producer: an OIS shift reduces the residual EIS correction
    // in that axis (no double-correction of the motion OIS already handled).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun oisShiftReducesResidualEisCorrectionThroughFullProducerChain() {
        // A STANDARD frame with a known positive residual yaw so the global correction dx is well-defined
        // and non-zero, plus a real rolling-shutter yaw stream. Use a large crop margin so nothing clamps
        // and the OIS subtraction is visible in the output grid.
        val margin = 1e9f
        fun grid(oisX: Float, oisY: Float): EisWarpGrid = EisWarpGridProducer.produce(
            mode = StabilizationModePolicy.StabilizationMode.STANDARD,
            smoothedFrame = OrientationTrajectorySmoother.SmoothedFrame(
                timestampNs = 0L,
                raw = Quaternion.IDENTITY,
                smoothed = Quaternion.IDENTITY,
                // residual yaw about +Y => positive global dx = f·tan(+yaw) > 0.
                residual = Quaternion.fromAxisAngle(0f, 1f, 0f, 0.05f),
            ),
            lockedReference = Quaternion.IDENTITY,
            focalLengthPx = focalLengthPx,
            cropMarginPixels = margin,
            samples = yawStream(0.4f),
            frameStartNs = 0L,
            rowReadoutNs = rowReadoutNs,
            rows = rows,
            referenceRow = referenceRow,
            oisShiftXPixels = oisX,
            oisShiftYPixels = oisY,
            centerX = centerX,
            centerY = centerY,
        )

        val noOis = grid(0f, 0f)
        // OIS absorbed a POSITIVE-x shift (same sign as the residual dx it built up). Correct fusion must
        // SUBTRACT it (dx -= oisShiftX), so every row's dx must DROP by exactly the OIS shift, per row.
        val oisPositiveX = 15f
        val oisPositiveY = 9f
        val withOis = grid(oisPositiveX, oisPositiveY)

        assertFalse("baseline STANDARD frame must be non-identity", noOis.isIdentity)
        assertEquals(noOis.rows, withOis.rows)
        for (r in 0 until noOis.rows) {
            val base = noOis.perRow[r]
            val fused = withOis.perRow[r]
            // Sign lock: OIS shift is SUBTRACTED, per axis, with NO effect on roll.
            assertEquals(
                "row $r: OIS-x must be subtracted (dx -= oisX)",
                base.dxPixels - oisPositiveX, fused.dxPixels, 1e-3f,
            )
            assertEquals(
                "row $r: OIS-y must be subtracted (dy -= oisY)",
                base.dyPixels - oisPositiveY, fused.dyPixels, 1e-3f,
            )
            assertEquals(
                "row $r: OIS (translational) must not touch roll",
                base.rollRadians, fused.rollRadians, 0f,
            )
            // And the RESIDUAL magnitude in the OIS-handled +x axis must SHRINK (no double-correction):
            // since base.dx > 0 and we subtract a positive shift smaller than base.dx.
            assertTrue(
                "row $r: fused dx=${fused.dxPixels} must be a smaller residual than base dx=${base.dxPixels}",
                abs(fused.dxPixels) < abs(base.dxPixels),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** Component-wise bit-exact (0f eps) quaternion equality. */
    private fun assertQuatExact(msg: String, expected: Quaternion, actual: Quaternion) {
        assertEquals("$msg .w", expected.w, actual.w, 0f)
        assertEquals("$msg .x", expected.x, actual.x, 0f)
        assertEquals("$msg .y", expected.y, actual.y, 0f)
        assertEquals("$msg .z", expected.z, actual.z, 0f)
    }
}
