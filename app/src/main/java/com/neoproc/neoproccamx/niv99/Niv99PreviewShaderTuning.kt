package com.neoproc.neoproccamx.niv99

/**
 * Reusable, allocation-free uniform packet for the visible NIV99 preview shader.
 *
 * The packet is activated only for an approved tuning that belongs to the exact SurfaceTexture
 * timestamp being rendered. Manual WB and AWB-lock authority deliberately publish identity RGB
 * correction while retaining their noise/detail policy, so rendering can never override user intent.
 */
class Niv99PreviewShaderTuning {
    var active: Float = 0f
        private set
    var redCorrection: Float = 1f
        private set
    var greenCorrection: Float = 1f
        private set
    var blueCorrection: Float = 1f
        private set
    var spatialDenoise: Float = 0f
        private set
    var chromaDenoise: Float = 0f
        private set
    var sharpeningCeiling: Float = 0f
        private set
    var confidence: Float = 0f
        private set

    fun update(tuning: Niv99LiveFrameTuning?, sensorTimestampNs: Long): Boolean {
        clear()
        if (tuning == null || sensorTimestampNs <= 0L || !tuning.valid ||
            !tuning.pixelApplicationApproved || !tuning.identity.isComplete() ||
            tuning.identity.sensorTimestampNs != sensorTimestampNs
        ) {
            return false
        }
        return applyApproved(tuning)
    }

    /**
     * Activates an older packet only after the collision-free continuity policy proves it belongs to
     * the same preview contract and is at most two sensor intervals old. This path is deliberately
     * separate from [update] so telemetry can never mislabel reuse as an exact timestamp match.
     */
    fun updateRecent(
        tuning: Niv99LiveFrameTuning?,
        targetSensorTimestampNs: Long,
        maximumAgeNs: Long,
        expectedContinuity: Niv99PreviewTuningContinuity
    ): Boolean {
        clear()
        if (tuning == null || !Niv99PreviewTuningContinuityPolicy.canReuse(
                expected = expectedContinuity,
                candidate = tuning,
                targetSensorTimestampNs = targetSensorTimestampNs,
                maximumAgeNs = maximumAgeNs
            )
        ) {
            return false
        }
        return applyApproved(tuning)
    }

    private fun applyApproved(tuning: Niv99LiveFrameTuning): Boolean {
        val boundedConfidence = tuning.confidence.coerceIn(0f, 1f)
        if (!boundedConfidence.isFinite() || boundedConfidence <= 0f) return false

        val niv99OwnsWhiteBalance = tuning.authority == Niv99WhiteBalanceAuthority.NIV99_AUTO
        redCorrection = if (niv99OwnsWhiteBalance) {
            tuning.rgbCorrection[0].finiteOrIdentity().coerceIn(MIN_WB_CORRECTION, MAX_WB_CORRECTION)
        } else {
            1f
        }
        greenCorrection = if (niv99OwnsWhiteBalance) {
            tuning.rgbCorrection[1].finiteOrIdentity().coerceIn(MIN_WB_CORRECTION, MAX_WB_CORRECTION)
        } else {
            1f
        }
        blueCorrection = if (niv99OwnsWhiteBalance) {
            tuning.rgbCorrection[2].finiteOrIdentity().coerceIn(MIN_WB_CORRECTION, MAX_WB_CORRECTION)
        } else {
            1f
        }
        spatialDenoise = tuning.spatialDenoise.finiteOrZero().coerceIn(0f, 1f)
        chromaDenoise = tuning.chromaDenoise.finiteOrZero().coerceIn(0f, 1f)
        sharpeningCeiling = tuning.sharpeningCeiling.finiteOrZero().coerceIn(0f, 1f)
        confidence = boundedConfidence
        active = 1f
        return true
    }

    fun clear() {
        active = 0f
        redCorrection = 1f
        greenCorrection = 1f
        blueCorrection = 1f
        spatialDenoise = 0f
        chromaDenoise = 0f
        sharpeningCeiling = 0f
        confidence = 0f
    }

    private fun Float.finiteOrIdentity(): Float = if (isFinite()) this else 1f

    private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f

    private companion object {
        const val MIN_WB_CORRECTION = 0.92f
        const val MAX_WB_CORRECTION = 1.08f
    }
}

/** Deterministic CPU reference for the GLES peak-safe white-balance transform. */
object Niv99PreviewColorReference {
    fun applyPeakSafeWhiteBalance(
        red: Float,
        green: Float,
        blue: Float,
        redCorrection: Float,
        greenCorrection: Float,
        blueCorrection: Float,
        amount: Float,
        output: FloatArray
    ): Boolean {
        if (output.size < 3 || !red.isFinite() || !green.isFinite() || !blue.isFinite() ||
            !redCorrection.isFinite() || !greenCorrection.isFinite() ||
            !blueCorrection.isFinite() || !amount.isFinite()
        ) return false

        val sourceRed = red.coerceIn(0f, 1f)
        val sourceGreen = green.coerceIn(0f, 1f)
        val sourceBlue = blue.coerceIn(0f, 1f)
        val boundedAmount = amount.coerceIn(0f, 1f)
        val gainRed = 1f +
            (redCorrection.coerceIn(MIN_WB_CORRECTION, MAX_WB_CORRECTION) - 1f) * boundedAmount
        val gainGreen = 1f +
            (greenCorrection.coerceIn(MIN_WB_CORRECTION, MAX_WB_CORRECTION) - 1f) * boundedAmount
        val gainBlue = 1f +
            (blueCorrection.coerceIn(MIN_WB_CORRECTION, MAX_WB_CORRECTION) - 1f) * boundedAmount
        val correctedRed = sourceRed * gainRed
        val correctedGreen = sourceGreen * gainGreen
        val correctedBlue = sourceBlue * gainBlue
        val sourcePeak = maxOf(sourceRed, sourceGreen, sourceBlue)
        val correctedPeak = maxOf(correctedRed, correctedGreen, correctedBlue)
        val scale = if (correctedPeak > sourcePeak && correctedPeak > MIN_NONZERO) {
            sourcePeak / correctedPeak
        } else {
            1f
        }
        output[0] = (correctedRed * scale).coerceIn(0f, 1f)
        output[1] = (correctedGreen * scale).coerceIn(0f, 1f)
        output[2] = (correctedBlue * scale).coerceIn(0f, 1f)
        return true
    }

    private const val MIN_WB_CORRECTION = 0.92f
    private const val MAX_WB_CORRECTION = 1.08f
    private const val MIN_NONZERO = 1.0e-6f
}
