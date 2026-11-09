package com.neoproc.neoproccamx.stabilization

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CP60 foundation — minimal unit (Hamilton) quaternion for gyro orientation integration.
 *
 * Pure math, no Android dependencies, deterministic and JVM-unit-tested. This is the keystone the
 * working-tree audit found missing ("real gyro ring buffer exists but no Madgwick/quaternion
 * integration anywhere"). It is intentionally dormant — not yet wired to the GPU warp — so it cannot
 * change any live pixel. The device-gated step is reconciling gyro timestamps with the camera
 * SENSOR_TIMESTAMP exposure window and pushing the resulting warp grid to the shader.
 *
 * Convention: Hamilton product, scalar-first (w,x,y,z), right-handed rotations.
 */
data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float) {

    operator fun times(o: Quaternion): Quaternion = Quaternion(
        w = w * o.w - x * o.x - y * o.y - z * o.z,
        x = w * o.x + x * o.w + y * o.z - z * o.y,
        y = w * o.y - x * o.z + y * o.w + z * o.x,
        z = w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

    fun norm(): Float = sqrt(w * w + x * x + y * y + z * z)

    fun normalized(): Quaternion {
        val n = norm()
        if (n < 1e-12f) return IDENTITY
        val inv = 1f / n
        return Quaternion(w * inv, x * inv, y * inv, z * inv)
    }

    /** Rotation magnitude in radians, in [0, π] (shortest-arc). */
    fun angleRad(): Float {
        val q = normalized()
        val vnorm = sqrt(q.x * q.x + q.y * q.y + q.z * q.z)
        return 2f * atan2(vnorm, abs(q.w))
    }

    /** Axis-angle as a rotation vector (axis * angle), radians; (0,0,0) for no rotation. */
    fun rotationVector(): FloatArray {
        val q = normalized()
        // Map to the hemisphere with w >= 0 so the returned angle is the shortest arc.
        val s = if (q.w < 0f) -1f else 1f
        val w = q.w * s
        val x = q.x * s
        val y = q.y * s
        val z = q.z * s
        val vnorm = sqrt(x * x + y * y + z * z)
        if (vnorm < 1e-9f) return floatArrayOf(0f, 0f, 0f)
        val angle = 2f * atan2(vnorm, w)
        val k = angle / vnorm
        return floatArrayOf(x * k, y * k, z * k)
    }

    /** Dot product (cosine of half the angle between the two orientations when both are unit). */
    fun dot(o: Quaternion): Float = w * o.w + x * o.x + y * o.y + z * o.z

    companion object {
        val IDENTITY = Quaternion(1f, 0f, 0f, 0f)

        /**
         * Shortest-arc spherical linear interpolation between two unit quaternions, t in [0,1].
         * Falls back to normalized linear interpolation when the orientations are nearly identical.
         */
        fun slerp(a: Quaternion, b: Quaternion, t: Float): Quaternion {
            val an = a.normalized()
            var bn = b.normalized()
            var d = an.dot(bn)
            if (d < 0f) { // take the shorter path
                bn = Quaternion(-bn.w, -bn.x, -bn.y, -bn.z)
                d = -d
            }
            if (d > 0.9995f) {
                return Quaternion(
                    an.w + (bn.w - an.w) * t,
                    an.x + (bn.x - an.x) * t,
                    an.y + (bn.y - an.y) * t,
                    an.z + (bn.z - an.z) * t,
                ).normalized()
            }
            val theta0 = kotlin.math.acos(d.coerceIn(-1f, 1f))
            val theta = theta0 * t
            val sinTheta0 = kotlin.math.sin(theta0)
            val s0 = kotlin.math.sin(theta0 - theta) / sinTheta0
            val s1 = kotlin.math.sin(theta) / sinTheta0
            return Quaternion(
                an.w * s0 + bn.w * s1,
                an.x * s0 + bn.x * s1,
                an.y * s0 + bn.y * s1,
                an.z * s0 + bn.z * s1,
            ).normalized()
        }

        /** Unit quaternion for a rotation of [angleRad] about the (not-necessarily-normalized) axis. */
        fun fromAxisAngle(ax: Float, ay: Float, az: Float, angleRad: Float): Quaternion {
            val axisNorm = sqrt(ax * ax + ay * ay + az * az)
            if (axisNorm < 1e-12f) return IDENTITY
            val half = angleRad * 0.5f
            val s = sin(half) / axisNorm
            return Quaternion(cos(half), ax * s, ay * s, az * s)
        }

        /** Unit quaternion from a rotation vector (axis * angle), radians. Small-angle safe. */
        fun fromRotationVector(rx: Float, ry: Float, rz: Float): Quaternion {
            val angle = sqrt(rx * rx + ry * ry + rz * rz)
            if (angle < 1e-9f) {
                // First-order exponential map; normalize to stay on the unit sphere.
                return Quaternion(1f, rx * 0.5f, ry * 0.5f, rz * 0.5f).normalized()
            }
            return fromAxisAngle(rx, ry, rz, angle)
        }
    }
}
