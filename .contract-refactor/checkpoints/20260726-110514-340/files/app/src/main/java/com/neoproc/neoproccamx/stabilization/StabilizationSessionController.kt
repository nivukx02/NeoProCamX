package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry
import kotlin.math.PI
import kotlin.math.exp

/**
 * s9-f02 / CP-QV2 — the per-frame orchestration seam that turns the (CP60-64) dormant gyro-EIS stack
 * into a live warp-grid stream.
 *
 * Every piece already existed and was JVM-tested but had ZERO production callers: [GyroEisOrientationFeed]
 * emits a per-frame [EisFrameOrientation], [OrientationTrajectorySmoother] low-passes the path (as a
 * BATCH call over a full list), and [EisWarpGridProducer] turns one smoothed frame into an [EisWarpGrid].
 * Nothing drove them incrementally across a real frame stream. This controller is that missing driver: it
 * carries the smoother's one-pole state frame-to-frame — mirroring [OrientationTrajectorySmoother.smooth]'s
 * exact recurrence so a folded stream is bit-identical to the golden batch smoother — and produces the
 * per-scanline grid the (still-dormant) `eis_warp.comp` pipeline consumes.
 *
 * NO-DOWNGRADE: [mode] defaults to OFF, so [onFrame] returns an all-zero identity grid regardless of the
 * incoming motion — a bit-for-bit passthrough. Nothing here reaches a pixel: this is a dormant foundation
 * with no production caller. Wiring it to `CameraController.latestGyroEisFrameOrientation()` and uploading
 * the grid to the GPU is the DEVICE-GATED step, blocked on the gyro↔SENSOR_TIMESTAMP clock-offset and the
 * OIS sign/scale calibration (see [GyroEisOrientationFeed], [OisEisFusion]).
 *
 * Intended for single-threaded per-frame use on the camera callback thread; [mode]/[lockedReference] are
 * set from the same thread that calls [onFrame].
 */
class StabilizationSessionController(
    private val config: Config,
) {
    data class Config(
        /** Trajectory smoothing corner frequency (Hz): lower = steadier, more lag, needs more crop. */
        val cutoffHz: Float,
        val focalLengthPx: Float,
        val cropMarginPixels: Float,
        val rows: Int,
        val referenceRow: Int,
        val centerX: Float,
        val centerY: Float,
    )

    /** No-downgrade default: OFF => identity grid. */
    var mode: StabilizationModePolicy.StabilizationMode = StabilizationModePolicy.StabilizationMode.OFF

    /** Fixed reference orientation used only by [StabilizationModePolicy.StabilizationMode.LOCKED]. */
    var lockedReference: Quaternion = Quaternion.IDENTITY

    private var smoothed: Quaternion? = null
    private var lastTimestampNs: Long = 0L

    /** The latest smoothed-trajectory frame (raw + smoothed + residual), for diagnostics/telemetry. */
    var lastSmoothedFrame: OrientationTrajectorySmoother.SmoothedFrame? = null
        private set

    /** Drop the accumulated trajectory (e.g. at record start / camera reconfigure). */
    fun reset() {
        smoothed = null
        lastTimestampNs = 0L
        lastSmoothedFrame = null
    }

    /** Capture the current smoothed orientation as the LOCKED reference (tripod-like hold). */
    fun lockReferenceToCurrent() {
        lockedReference = (smoothed ?: Quaternion.IDENTITY)
    }

    /**
     * Advance the trajectory by one camera frame and return its per-scanline warp grid.
     *
     * @param orientation the frame's [GyroEisOrientationFeed] output (exposure-mid orientation + timestamp).
     * @param samples the GYRO-domain ring view (oldest first) for the rolling-shutter per-row refinement.
     */
    fun onFrame(
        orientation: EisFrameOrientation,
        samples: List<CameraMotionTelemetry>,
        frameStartNs: Long,
        rowReadoutNs: Long,
        oisShiftXPixels: Float,
        oisShiftYPixels: Float,
    ): EisWarpGrid {
        val frame = advanceTrajectory(orientation)
        return EisWarpGridProducer.produce(
            mode = mode,
            smoothedFrame = frame,
            lockedReference = lockedReference,
            focalLengthPx = config.focalLengthPx,
            cropMarginPixels = config.cropMarginPixels,
            samples = samples,
            frameStartNs = frameStartNs,
            rowReadoutNs = rowReadoutNs,
            rows = config.rows,
            referenceRow = config.referenceRow,
            oisShiftXPixels = oisShiftXPixels,
            oisShiftYPixels = oisShiftYPixels,
            centerX = config.centerX,
            centerY = config.centerY,
        )
    }

    /**
     * Incremental form of [OrientationTrajectorySmoother.smooth]'s one-pole recurrence: the first frame
     * passes through; each later frame slerps the smoothed state toward the raw orientation by
     * `alpha = 1 - exp(-2π·cutoff·dt)`. State is updated even when [mode] is OFF so a later mode switch is
     * seamless (the grid stays identity while OFF regardless).
     */
    private fun advanceTrajectory(orientation: EisFrameOrientation): OrientationTrajectorySmoother.SmoothedFrame {
        val raw = orientation.orientationAtFrameMid.normalized()
        val ts = orientation.frameMidTimestampNs
        val prior = smoothed
        val newSmoothed = when {
            prior == null -> raw
            else -> {
                val dt = (ts - lastTimestampNs) / NANOS_PER_SECOND
                if (dt <= 0.0) {
                    prior
                } else {
                    val alpha = (1.0 - exp(-2.0 * PI * config.cutoffHz.coerceAtLeast(0f) * dt))
                        .coerceIn(0.0, 1.0)
                        .toFloat()
                    Quaternion.slerp(prior, raw, alpha)
                }
            }
        }
        smoothed = newSmoothed
        lastTimestampNs = ts
        val residual = (raw.conjugate() * newSmoothed).normalized()
        val frame = OrientationTrajectorySmoother.SmoothedFrame(ts, raw, newSmoothed, residual)
        lastSmoothedFrame = frame
        return frame
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
