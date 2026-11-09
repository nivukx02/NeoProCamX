package com.neoproc.neoproccamx.snce

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SNCE v2.0 HVC — Stage 10 (OACSS-IMU): orientation-coupled illuminant prior.
 *
 * The existing code already ingests the gyroscope (CameraController motion telemetry)
 * but never couples it to colour. This stage turns 6-DOF IMU into an illuminant prior:
 *  - [SnceMadgwickAhrs] fuses gyro+accel into an orientation quaternion (directive 10.1).
 *  - [SnceOrientationColor] maps camera pitch to a predicted sky fraction and therefore a
 *    ground(3800K) <-> sky(12000K) illuminant prior (directive 10.2), and flags when roll
 *    exceeds the threshold at which the zone grid should be re-derived (directive 10.3).
 *
 * The resulting [SnceIlluminantPrior] is fed into the Stage 01 fusion as Method 9.
 */
class SnceMadgwickAhrs(private val beta: Float = 0.033f) {
    // quaternion (w, x, y, z), initialised to identity
    var q0 = 1f; private set
    var q1 = 0f; private set
    var q2 = 0f; private set
    var q3 = 0f; private set

    /** Update with gyro (rad/s), accel (any scale), and dt (s). Returns this for chaining. */
    fun update(gx: Float, gy: Float, gz: Float, ax0: Float, ay0: Float, az0: Float, dt: Float): SnceMadgwickAhrs {
        var qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
        var qDot1 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
        var qDot2 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
        var qDot3 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)

        val norm = sqrt(ax0 * ax0 + ay0 * ay0 + az0 * az0)
        if (norm > 1e-6f) {
            val ax = ax0 / norm; val ay = ay0 / norm; val az = az0 / norm
            val _2q0 = 2f * q0; val _2q1 = 2f * q1; val _2q2 = 2f * q2; val _2q3 = 2f * q3
            val _4q0 = 4f * q0; val _4q1 = 4f * q1; val _4q2 = 4f * q2
            val _8q1 = 8f * q1; val _8q2 = 8f * q2
            val q0q0 = q0 * q0; val q1q1 = q1 * q1; val q2q2 = q2 * q2; val q3q3 = q3 * q3

            var s0 = _4q0 * q2q2 + _2q2 * ax + _4q0 * q1q1 - _2q1 * ay
            var s1 = _4q1 * q3q3 - _2q3 * ax + 4f * q0q0 * q1 - _2q0 * ay - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * az
            var s2 = 4f * q0q0 * q2 + _2q0 * ax + _4q2 * q3q3 - _2q3 * ay - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * az
            var s3 = 4f * q1q1 * q3 - _2q1 * ax + 4f * q2q2 * q3 - _2q2 * ay
            val sNorm = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
            if (sNorm > 1e-6f) {
                val inv = 1f / sNorm
                s0 *= inv; s1 *= inv; s2 *= inv; s3 *= inv
                qDot0 -= beta * s0; qDot1 -= beta * s1; qDot2 -= beta * s2; qDot3 -= beta * s3
            }
        }

        q0 += qDot0 * dt; q1 += qDot1 * dt; q2 += qDot2 * dt; q3 += qDot3 * dt
        val qn = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        if (qn > 1e-9f) {
            val inv = 1f / qn
            q0 *= inv; q1 *= inv; q2 *= inv; q3 *= inv
        }
        return this
    }

    /** [roll, pitch, yaw] in degrees. */
    fun eulerDegrees(): FloatArray {
        val roll = atan2(2f * (q0 * q1 + q2 * q3), 1f - 2f * (q1 * q1 + q2 * q2))
        val pitch = asin((2f * (q0 * q2 - q3 * q1)).coerceIn(-1f, 1f))
        val yaw = atan2(2f * (q0 * q3 + q1 * q2), 1f - 2f * (q2 * q2 + q3 * q3))
        return floatArrayOf(
            SnceColorPrimitives.toDegrees(roll),
            SnceColorPrimitives.toDegrees(pitch),
            SnceColorPrimitives.toDegrees(yaw)
        )
    }
}

object SnceOrientationColor {
    const val CCT_GROUND = 3800f   // PHYSICAL: warm reflected-ground prior.
    const val CCT_SKY = 12000f     // PHYSICAL: cool open-sky prior.
    const val AMBIENT_CCT = 6500f  // PHYSICAL: neutral daylight prior when level (no orientation cue).
    const val ROLL_RECOMPUTE_DEGREES = 20f

    /** Predicted sky fraction from camera pitch in degrees (directive 10.2). */
    fun skyFraction(pitchDeg: Float): Float {
        val p = pitchDeg
        return when {
            p > 70f -> 0.90f
            p > 30f -> sin(SnceColorPrimitives.toRadians(p)) * 0.85f
            p > -20f -> (0.15f * (p + 20f) / 50f).coerceIn(0f, 0.15f)
            else -> 0.02f
        }.coerceIn(0f, 0.95f)
    }

    /**
     * Orientation-predicted illuminant CCT. Anchored at neutral daylight when the camera is
     * level (horizontal tells you nothing about the light), warming toward the ground when it
     * points down and cooling toward the sky when it points up (directive 10.2).
     */
    fun orientationCct(pitchDeg: Float): Float = when {
        pitchDeg > 30f -> {
            val f = ((pitchDeg - 30f) / 60f).coerceIn(0f, 1f) * 0.85f
            AMBIENT_CCT + f * (CCT_SKY - AMBIENT_CCT)
        }
        pitchDeg < -20f -> {
            val f = ((-pitchDeg - 20f) / 70f).coerceIn(0f, 1f)
            AMBIENT_CCT + f * (CCT_GROUND - AMBIENT_CCT)
        }
        else -> AMBIENT_CCT
    }

    /**
     * How much the orientation cue should be trusted: ~0 near horizontal (no information),
     * rising to 1 once the camera is clearly tilted toward sky or ground.
     */
    fun orientationConfidenceScale(pitchDeg: Float): Float =
        ((kotlin.math.abs(pitchDeg) - 10f) / 50f).coerceIn(0f, 1f)

    /** True when camera roll is large enough that the zone illuminant grid should be re-derived. */
    fun rollCompensationNeeded(rollDeg: Float): Boolean {
        val rollMod = ((rollDeg % 90f) + 90f) % 90f
        val signed = if (rollMod > 45f) rollMod - 90f else rollMod
        return kotlin.math.abs(signed) > ROLL_RECOMPUTE_DEGREES
    }

    /**
     * Build the Stage 01 Method-9 illuminant prior from orientation.
     * @param imuQuality sample quality in [0,1]; @param cloudCover estimate in [0,1].
     */
    fun illuminantPrior(
        pitchDeg: Float,
        imuQuality: Float = 1f,
        cloudCover: Float = 0f,
        rgbToXyz: FloatArray = SnceIlluminantEstimator.REC709_TO_XYZ
    ): SnceIlluminantPrior {
        val cct = orientationCct(pitchDeg)
        val xy = SnceColorPrimitives.kelvinToXy(cct)
        val xyz = SnceColorPrimitives.xyToXyz(xy.first, xy.second)
        val rgbRaw = SnceColorPrimitives.mulV(SnceColorPrimitives.invert3(rgbToXyz), xyz)
        val g = if (kotlin.math.abs(rgbRaw[1]) < SnceColorPrimitives.EPS) SnceColorPrimitives.EPS else rgbRaw[1]
        val rgb = floatArrayOf(rgbRaw[0] / g, 1f, rgbRaw[2] / g)
        val confidence = (
            imuQuality.coerceIn(0f, 1f) * 0.55f * (1f - cloudCover.coerceIn(0f, 1f)) *
                orientationConfidenceScale(pitchDeg)
            ).coerceAtMost(0.65f)
        return SnceIlluminantPrior(rgb, confidence)
    }
}
