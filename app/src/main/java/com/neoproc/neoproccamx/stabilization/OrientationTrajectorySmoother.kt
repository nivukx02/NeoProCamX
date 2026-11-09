package com.neoproc.neoproccamx.stabilization

import kotlin.math.PI
import kotlin.math.exp

/**
 * CP61 foundation — smooths the integrated camera-orientation path and derives the per-frame residual
 * rotation an EIS warp must cancel.
 *
 * Electronic stabilization works by synthesising a SMOOTH virtual camera path and warping each real
 * frame to it. Given the raw per-frame orientations (from [GyroOrientationIntegrator]) this produces a
 * causal low-passed path (one-pole filter applied on the rotation manifold via SLERP) and the residual
 * rotation `raw⁻¹ · smoothed` for each frame — feed that residual to [GyroEisWarp] to get the pixel
 * correction. Pure, deterministic, JVM-unit-tested; dormant (not GPU-wired), so no live pixel changes.
 *
 * Causal by construction (only past/current frames), so it is real-time-applicable; the device-gated
 * concern is choosing the cutoff vs. the available crop margin, not the math here.
 */
object OrientationTrajectorySmoother {

    data class TimedOrientation(val timestampNs: Long, val orientation: Quaternion)

    /** A frame's smoothed reference orientation and the residual rotation to apply to stabilize it. */
    data class SmoothedFrame(
        val timestampNs: Long,
        val raw: Quaternion,
        val smoothed: Quaternion,
        /** Rotation from the real frame to the smoothed virtual camera (raw⁻¹ · smoothed). */
        val residual: Quaternion,
    )

    private const val NANOS_PER_SECOND = 1_000_000_000.0

    /**
     * One-pole causal low-pass on the orientation path. [cutoffHz] is the smoothing corner frequency:
     * lower = steadier (more lag, needs more crop). The first frame passes through unchanged.
     */
    fun smooth(path: List<TimedOrientation>, cutoffHz: Float): List<SmoothedFrame> {
        if (path.isEmpty()) return emptyList()
        val out = ArrayList<SmoothedFrame>(path.size)
        var smoothed = path[0].orientation.normalized()
        out.add(frame(path[0].timestampNs, path[0].orientation, smoothed))
        for (i in 1 until path.size) {
            val dt = (path[i].timestampNs - path[i - 1].timestampNs) / NANOS_PER_SECOND
            val raw = path[i].orientation.normalized()
            if (dt <= 0.0) {
                out.add(frame(path[i].timestampNs, raw, smoothed))
                continue
            }
            // One-pole coefficient: alpha = 1 - exp(-2π fc dt). alpha→1 (follow) as cutoff↑.
            val alpha = (1.0 - exp(-2.0 * PI * cutoffHz.coerceAtLeast(0f) * dt)).coerceIn(0.0, 1.0).toFloat()
            smoothed = Quaternion.slerp(smoothed, raw, alpha)
            out.add(frame(path[i].timestampNs, raw, smoothed))
        }
        return out
    }

    private fun frame(tNs: Long, raw: Quaternion, smoothed: Quaternion): SmoothedFrame {
        val residual = (raw.conjugate() * smoothed).normalized()
        return SmoothedFrame(tNs, raw, smoothed, residual)
    }
}
