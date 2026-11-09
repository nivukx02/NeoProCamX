package com.neoproc.neoproccamx.stabilization

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Test

class QuaternionTest {

    private val tol = 1e-4f

    @Test
    fun identityHasZeroRotation() {
        assertEquals(0f, Quaternion.IDENTITY.angleRad(), tol)
        val rv = Quaternion.IDENTITY.rotationVector()
        assertEquals(0f, rv[0], tol)
        assertEquals(0f, rv[1], tol)
        assertEquals(0f, rv[2], tol)
    }

    @Test
    fun axisAngleRoundTripsThroughRotationVector() {
        val angle = 0.3f
        val q = Quaternion.fromAxisAngle(0f, 0f, 1f, angle)
        assertEquals(angle, q.angleRad(), tol)
        val rv = q.rotationVector()
        assertEquals(0f, rv[0], tol)
        assertEquals(0f, rv[1], tol)
        assertEquals(angle, rv[2], tol)
    }

    @Test
    fun conjugateCancelsRotation() {
        val q = Quaternion.fromAxisAngle(0.3f, -0.5f, 0.8f, 0.7f)
        val net = (q * q.conjugate()).normalized()
        assertEquals(0f, net.angleRad(), tol)
    }

    @Test
    fun rotationVectorMatchesAxisAngleForSmallRotation() {
        val q1 = Quaternion.fromRotationVector(0.001f, 0f, 0f)
        val q2 = Quaternion.fromAxisAngle(1f, 0f, 0f, 0.001f)
        assertEquals(q2.w, q1.w, tol)
        assertEquals(q2.x, q1.x, tol)
    }

    @Test
    fun sameAxisRotationsComposeByAngleAddition() {
        val a = 0.4f
        val b = 0.5f
        val q = Quaternion.fromAxisAngle(0f, 0f, 1f, a) * Quaternion.fromAxisAngle(0f, 0f, 1f, b)
        assertEquals(a + b, q.angleRad(), tol)
        assertEquals(a + b, q.rotationVector()[2], tol)
    }

    @Test
    fun normalizedProducesUnitQuaternion() {
        val q = Quaternion(2f, 0f, 0f, 0f).normalized()
        assertEquals(1f, q.norm(), tol)
        // A 180-degree rotation about X: rotation magnitude is pi.
        val half = Quaternion.fromAxisAngle(1f, 0f, 0f, PI.toFloat())
        assertEquals(PI.toFloat(), half.angleRad(), 1e-3f)
    }
}
