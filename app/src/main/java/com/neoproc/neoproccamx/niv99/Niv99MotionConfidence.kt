package com.neoproc.neoproccamx.niv99

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** Fixed-capacity forward/backward optical-flow field in pixel units. */
class Niv99MotionField(capacity: Int) {
    val forwardX: FloatArray = FloatArray(capacity)
    val forwardY: FloatArray = FloatArray(capacity)
    val backwardX: FloatArray = FloatArray(capacity)
    val backwardY: FloatArray = FloatArray(capacity)
    val estimatorConfidence: FloatArray = FloatArray(capacity)
    val consistency: FloatArray = FloatArray(capacity)
    val occlusion: FloatArray = FloatArray(capacity)
    val motionMagnitude: FloatArray = FloatArray(capacity)
    val temporalAdmission: FloatArray = FloatArray(capacity)
    var columns: Int = 0
        private set
    var rows: Int = 0
        private set
    var count: Int = 0
        private set

    fun configure(columns: Int, rows: Int) {
        require(columns > 0 && rows > 0 && columns.toLong() * rows <= forwardX.size)
        this.columns = columns
        this.rows = rows
        count = columns * rows
        clearDerived()
    }

    fun clearDerived() {
        for (index in 0 until count) {
            consistency[index] = 0f
            occlusion[index] = 1f
            motionMagnitude[index] = 0f
            temporalAdmission[index] = 0f
        }
    }
}

class Niv99GyroMotion {
    var angularVelocityX: Float = 0f
    var angularVelocityY: Float = 0f
    var angularVelocityZ: Float = 0f
    var exposureMidpointOffsetNs: Long = 0L
    var pixelsPerRadianX: Float = 0f
    var pixelsPerRadianY: Float = 0f
    var valid: Boolean = false

    fun magnitude(): Float = sqrt(
        angularVelocityX * angularVelocityX +
            angularVelocityY * angularVelocityY +
            angularVelocityZ * angularVelocityZ
    )
}

class Niv99MotionSummary {
    var meanMagnitude: Float = 0f
    var p95Magnitude: Float = 0f
    var meanConsistency: Float = 0f
    var occludedFraction: Float = 0f
    var admittedFraction: Float = 0f
    var globalConfidence: Float = 0f
}

/** Forward/backward consistency, gyro agreement, rolling-shutter correction, and occlusion rejection. */
class Niv99MotionConfidence(maxTiles: Int) {
    private val magnitudes = FloatArray(maxTiles)

    fun evaluate(
        field: Niv99MotionField,
        gyro: Niv99GyroMotion?,
        rollingShutterSkewNs: Long,
        frameDurationNs: Long,
        output: Niv99MotionSummary
    ): Niv99ValidationResult {
        if (field.count <= 0 || field.count > magnitudes.size) {
            return Niv99ValidationResult.INVALID_DIMENSIONS
        }
        var magnitudeSum = 0.0
        var consistencySum = 0.0
        var occluded = 0
        var admitted = 0
        for (index in 0 until field.count) {
            val row = index / field.columns
            val normalizedY = if (field.rows > 1) row.toFloat() / (field.rows - 1) else 0.5f
            val rollingOffset = normalizedY - 0.5f
            val rollingTimeSeconds = rollingOffset * rollingShutterSkewNs.coerceAtLeast(0L) * 1e-9f
            val gyroX = if (gyro?.valid == true) {
                gyro.angularVelocityY * gyro.pixelsPerRadianX * rollingTimeSeconds
            } else {
                0f
            }
            val gyroY = if (gyro?.valid == true) {
                -gyro.angularVelocityX * gyro.pixelsPerRadianY * rollingTimeSeconds
            } else {
                0f
            }
            val forwardX = field.forwardX[index] - gyroX
            val forwardY = field.forwardY[index] - gyroY
            val backwardX = field.backwardX[index] + gyroX
            val backwardY = field.backwardY[index] + gyroY
            if (!forwardX.isFinite() || !forwardY.isFinite() ||
                !backwardX.isFinite() || !backwardY.isFinite()
            ) {
                field.consistency[index] = 0f
                field.occlusion[index] = 1f
                field.temporalAdmission[index] = 0f
                magnitudes[index] = Float.POSITIVE_INFINITY
                occluded += 1
                continue
            }
            val magnitude = sqrt(forwardX * forwardX + forwardY * forwardY)
            val forwardBackwardError = sqrt(
                (forwardX + backwardX) * (forwardX + backwardX) +
                    (forwardY + backwardY) * (forwardY + backwardY)
            )
            val adaptiveTolerance = FB_ABSOLUTE_TOLERANCE + magnitude * FB_RELATIVE_TOLERANCE
            val consistency = exp(
                -Niv99Math.square(forwardBackwardError / max(adaptiveTolerance, MIN_TOLERANCE))
            )
            val confidence = Niv99Math.saturateFinite(field.estimatorConfidence[index])
            val boundaryRisk = boundaryDisocclusionRisk(field, index, forwardX, forwardY)
            val occlusionConfidence = max(
                1f - consistency,
                boundaryRisk
            ).coerceIn(0f, 1f)
            val motionSafety = 1f - Niv99Math.smoothstep(
                SAFE_MOTION_PIXELS,
                UNSAFE_MOTION_PIXELS,
                magnitude
            )
            val admission = confidence * consistency * (1f - occlusionConfidence) * motionSafety
            field.consistency[index] = consistency
            field.occlusion[index] = occlusionConfidence
            field.motionMagnitude[index] = magnitude
            field.temporalAdmission[index] = admission
            magnitudes[index] = magnitude
            magnitudeSum += magnitude
            consistencySum += consistency
            if (occlusionConfidence >= OCCLUSION_THRESHOLD) occluded += 1
            if (admission >= ADMISSION_THRESHOLD) admitted += 1
        }
        output.meanMagnitude = (magnitudeSum / field.count).toFloat()
        output.p95Magnitude = percentile95(magnitudes, field.count)
        output.meanConsistency = (consistencySum / field.count).toFloat()
        output.occludedFraction = occluded.toFloat() / field.count
        output.admittedFraction = admitted.toFloat() / field.count
        val gyroPenalty = if (gyro?.valid == true) {
            1f - Niv99Math.smoothstep(GYRO_SAFE_RATE, GYRO_UNSAFE_RATE, gyro.magnitude())
        } else {
            MISSING_GYRO_CONFIDENCE
        }
        val durationConfidence = if (frameDurationNs > 0L && rollingShutterSkewNs >= 0L) 1f else 0.5f
        output.globalConfidence = Niv99Math.saturateFinite(
            output.meanConsistency * 0.35f +
                (1f - output.occludedFraction) * 0.25f +
                output.admittedFraction * 0.25f +
                gyroPenalty * durationConfidence * 0.15f
        )
        return Niv99ValidationResult.VALID
    }

    private fun boundaryDisocclusionRisk(
        field: Niv99MotionField,
        index: Int,
        flowX: Float,
        flowY: Float
    ): Float {
        val x = index % field.columns
        val y = index / field.columns
        val targetX = x + flowX
        val targetY = y + flowY
        val horizontalOutside = when {
            targetX < 0f -> -targetX
            targetX > field.columns - 1 -> targetX - (field.columns - 1)
            else -> 0f
        }
        val verticalOutside = when {
            targetY < 0f -> -targetY
            targetY > field.rows - 1 -> targetY - (field.rows - 1)
            else -> 0f
        }
        return Niv99Math.smoothstep(0f, 1.5f, max(horizontalOutside, verticalOutside))
    }

    private fun percentile95(values: FloatArray, count: Int): Float {
        // Fixed-bin histogram avoids sorting and remains deterministic on the hot path.
        val bins = percentileBins
        bins.fill(0)
        var finiteCount = 0
        for (index in 0 until count) {
            val value = values[index]
            if (!value.isFinite()) continue
            val bin = ((value.coerceIn(0f, MAX_HISTOGRAM_MOTION) / MAX_HISTOGRAM_MOTION) *
                (bins.size - 1)).toInt()
            bins[bin] += 1
            finiteCount += 1
        }
        if (finiteCount <= 0) return Float.POSITIVE_INFINITY
        val target = (finiteCount * 95 + 99) / 100
        var cumulative = 0
        for (bin in bins.indices) {
            cumulative += bins[bin]
            if (cumulative >= target) {
                return bin.toFloat() / (bins.size - 1) * MAX_HISTOGRAM_MOTION
            }
        }
        return MAX_HISTOGRAM_MOTION
    }

    private val percentileBins = IntArray(HISTOGRAM_BIN_COUNT)

    companion object {
        private const val FB_ABSOLUTE_TOLERANCE = 0.35f
        private const val FB_RELATIVE_TOLERANCE = 0.12f
        private const val MIN_TOLERANCE = 1e-4f
        private const val SAFE_MOTION_PIXELS = 0.4f
        private const val UNSAFE_MOTION_PIXELS = 6f
        private const val OCCLUSION_THRESHOLD = 0.55f
        private const val ADMISSION_THRESHOLD = 0.18f
        private const val GYRO_SAFE_RATE = 0.08f
        private const val GYRO_UNSAFE_RATE = 2.5f
        private const val MISSING_GYRO_CONFIDENCE = 0.65f
        private const val HISTOGRAM_BIN_COUNT = 256
        private const val MAX_HISTOGRAM_MOTION = 32f
    }
}

object Niv99SceneDiscontinuityPolicy {
    fun isSceneCut(
        lumaHistogramDistance: Float,
        chromaDistance: Float,
        motionInlierFraction: Float,
        exposureRatio: Float,
        flashTransition: Boolean
    ): Boolean {
        if (flashTransition) return true
        if (!lumaHistogramDistance.isFinite() || !chromaDistance.isFinite() ||
            !motionInlierFraction.isFinite() || !exposureRatio.isFinite()
        ) {
            return true
        }
        val lumaCut = lumaHistogramDistance > 0.42f
        val chromaCut = chromaDistance > 0.22f
        val alignmentFailed = motionInlierFraction < 0.12f
        val exposureJump = exposureRatio < 0.30f || exposureRatio > 3.3f
        return (lumaCut && chromaCut) || (lumaCut && alignmentFailed) ||
            (chromaCut && alignmentFailed && exposureJump)
    }
}
