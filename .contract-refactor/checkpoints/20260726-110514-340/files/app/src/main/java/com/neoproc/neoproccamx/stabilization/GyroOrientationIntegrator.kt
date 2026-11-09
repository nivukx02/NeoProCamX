package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry

/**
 * CP60 foundation — integrates body-frame gyroscope angular velocity into device orientation.
 *
 * Consumes the EXISTING [CameraMotionTelemetry] samples (rad/s + nanosecond timestamps) already
 * captured into [com.neoproc.neoproccamx.camera.CameraMotionTelemetryRingBuffer] but, per the audit,
 * currently dead-ended on the CPU as diagnostic strings. This turns that stream into a usable
 * orientation signal via trapezoidal quaternion integration. It is pure, deterministic and
 * JVM-unit-tested; it is NOT wired to the GPU warp, so it changes no live pixel.
 *
 * Gyro axis convention is the device sensor frame (X right, Y up, Z out of the screen). The integrated
 * quaternion is the orientation of the current sample relative to the first sample in the list.
 */
object GyroOrientationIntegrator {

    private const val NANOS_PER_SECOND = 1_000_000_000.0

    /** ω of a sample as a rad/s vector. */
    private fun omega(s: CameraMotionTelemetry) =
        floatArrayOf(s.gyroXRadPerSec, s.gyroYRadPerSec, s.gyroZRadPerSec)

    /**
     * Integrate orientation across [samples] (oldest first), returning the rotation of the last
     * sample's frame relative to the first. Uses the midpoint (trapezoidal) angular velocity over each
     * interval. Returns identity for fewer than two samples.
     */
    fun integrate(samples: List<CameraMotionTelemetry>): Quaternion {
        if (samples.size < 2) return Quaternion.IDENTITY
        var q = Quaternion.IDENTITY
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val dt = (b.timestampNs - a.timestampNs) / NANOS_PER_SECOND
            if (dt <= 0.0) continue
            q = (q * deltaRotation(omega(a), omega(b), dt)).normalized()
        }
        return q
    }

    /**
     * Integrate only the portion of the (oldest-first) [samples] stream that overlaps [t0Ns, t1Ns],
     * including partial endpoint intervals with linearly-interpolated angular velocity. This is the
     * primitive a gyro-EIS warp needs: the camera-to-reference rotation over a frame's exposure window.
     * Returns identity if the interval is empty or non-positive.
     */
    fun rotationBetween(samples: List<CameraMotionTelemetry>, t0Ns: Long, t1Ns: Long): Quaternion {
        if (samples.size < 2 || t1Ns <= t0Ns) return Quaternion.IDENTITY
        var q = Quaternion.IDENTITY
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val segStart = a.timestampNs
            val segEnd = b.timestampNs
            if (segEnd <= segStart) continue
            val lo = maxOf(segStart, t0Ns)
            val hi = minOf(segEnd, t1Ns)
            if (hi <= lo) continue
            val oa = omega(a)
            val ob = omega(b)
            // Linearly-interpolated ω at the clipped sub-interval endpoints.
            val fLo = (lo - segStart).toDouble() / (segEnd - segStart).toDouble()
            val fHi = (hi - segStart).toDouble() / (segEnd - segStart).toDouble()
            val wLo = lerp(oa, ob, fLo)
            val wHi = lerp(oa, ob, fHi)
            val dt = (hi - lo) / NANOS_PER_SECOND
            q = (q * deltaRotation(wLo, wHi, dt)).normalized()
        }
        return q
    }

    /** Delta-rotation quaternion for the trapezoidal mean of two ω samples over dt seconds. */
    private fun deltaRotation(wA: FloatArray, wB: FloatArray, dt: Double): Quaternion {
        val mx = ((wA[0] + wB[0]) * 0.5 * dt).toFloat()
        val my = ((wA[1] + wB[1]) * 0.5 * dt).toFloat()
        val mz = ((wA[2] + wB[2]) * 0.5 * dt).toFloat()
        return Quaternion.fromRotationVector(mx, my, mz)
    }

    private fun lerp(a: FloatArray, b: FloatArray, t: Double): FloatArray = floatArrayOf(
        (a[0] + (b[0] - a[0]) * t).toFloat(),
        (a[1] + (b[1] - a[1]) * t).toFloat(),
        (a[2] + (b[2] - a[2]) * t).toFloat(),
    )
}
