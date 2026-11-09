package com.neoproc.neoproccamx.niv99

import kotlin.math.max

/**
 * Resolves strict user/HAL/NIV99 authority and publishes one atomic gain-plus-matrix solution. The
 * local mixed-light field remains separate and can only be consumed by a rendering coordinator.
 */
class Niv99WhiteBalanceController {
    private val temporalState = Niv99WhiteBalanceTemporalState()
    private val interpolator = Niv99DualIlluminantInterpolator()
    private val calibrationSolution = Niv99CalibrationSolution()
    private val cat16 = Niv99Cat16Adaptation()
    private val adaptation = FloatArray(9)
    private val adaptedMatrix = FloatArray(9)
    private val candidate = Niv99WhiteBalanceSolution()

    fun resolve(
        context: Niv99FrameContext,
        calibration: Niv99SensorCalibration,
        estimate: Niv99IlluminantEstimate?,
        mixedLight: Niv99LocalIlluminantField?,
        output: Niv99WhiteBalanceSolution
    ): Niv99ValidationResult {
        val validation = context.validate()
        if (validation != Niv99ValidationResult.VALID) return validation
        candidate.identity.copyFrom(context.identity)
        candidate.sourceIdentity.copyFrom(context.identity)
        candidate.resetReason = Niv99ResetReason.NONE
        val intent = context.whiteBalanceIntent
        val lockRequested = intent.awbLockRequested || intent.awbLockActive
        when {
            intent.hasManualAuthority() -> resolveManual(intent, candidate)
            lockRequested && intent.hasValidHalGains() -> resolveHal(context, intent, candidate)
            lockRequested -> resolveUnsupported(candidate)
            estimate != null && estimate.identity.sameTimeline(context.identity) &&
                estimate.identity.frameNumber == context.identity.frameNumber &&
                estimate.identity.sensorTimestampNs == context.identity.sensorTimestampNs ->
                resolveAutomatic(context, calibration, estimate, mixedLight, candidate)
            intent.hasValidHalGains() -> resolveHal(context, intent, candidate)
            else -> resolveUnsupported(candidate)
        }
        temporalState.stabilize(context, candidate, output)
        output.identity.copyFrom(context.identity)
        if (!output.sourceIdentity.isComplete()) output.sourceIdentity.copyFrom(context.identity)
        output.clippingRisk = clippingRisk(
            context.meanLinearSignal,
            output.redGain,
            output.greenGain,
            output.blueGain,
            context.clippingFraction
        )
        return Niv99ValidationResult.VALID
    }

    fun reset(reason: Niv99ResetReason = Niv99ResetReason.EXPLICIT_RESET) {
        temporalState.reset(reason)
    }

    private fun resolveManual(
        intent: Niv99WhiteBalanceIntent,
        output: Niv99WhiteBalanceSolution
    ) {
        output.authority = Niv99WhiteBalanceAuthority.MANUAL
        output.cctKelvin = intent.manualKelvin.toFloat()
        output.duv = tintToDuv(intent.manualTint)
        output.confidence = 1f
        output.mixedLightConfidence = 0f
        output.localFieldRevision = 0L
        output.illuminantClass = classify(intent.manualKelvin.toFloat(), output.duv)
        if (intent.hasValidManualGains()) {
            output.redGain = intent.manualRedGain
            output.greenGain = intent.manualGreenGain
            output.blueGain = intent.manualBlueGain
        } else {
            // Manual intent remains authoritative even if its renderer payload is incomplete.
            output.redGain = 1f
            output.greenGain = 1f
            output.blueGain = 1f
        }
        output.colorMatrixValid = intent.manualMatrixValid && finiteMatrix(intent.manualColorMatrix)
        output.calibrationMatrixSource = if (output.colorMatrixValid) {
            Niv99CalibrationMatrixSource.MANUAL_USER_MATRIX
        } else {
            Niv99CalibrationMatrixSource.NONE
        }
        output.calibrationTransformIdentityFallback = false
        if (output.colorMatrixValid) {
            intent.manualColorMatrix.copyInto(output.colorMatrix)
        } else {
            Niv99Math.setIdentity3(output.colorMatrix)
        }
    }

    private fun resolveAutomatic(
        context: Niv99FrameContext,
        calibration: Niv99SensorCalibration,
        estimate: Niv99IlluminantEstimate,
        mixedLight: Niv99LocalIlluminantField?,
        output: Niv99WhiteBalanceSolution
    ) {
        output.authority = Niv99WhiteBalanceAuthority.NIV99_AUTO
        output.redGain = estimate.redGain
        output.greenGain = estimate.greenGain
        output.blueGain = estimate.blueGain
        output.cctKelvin = estimate.cctKelvin
        output.duv = estimate.duv
        output.confidence = estimate.confidence
        output.illuminantClass = if (mixedLight?.mixedLightConfidence ?: 0f >= MIXED_LIGHT_THRESHOLD) {
            Niv99IlluminantClass.MIXED
        } else {
            estimate.illuminantClass
        }
        output.mixedLightConfidence = mixedLight?.mixedLightConfidence ?: 0f
        output.localFieldRevision = mixedLight?.revision ?: 0L
        output.clippingRisk = estimate.clippingRisk
        output.colorMatrixValid = buildOutputMatrix(calibration, estimate.cctKelvin, output.colorMatrix)
        output.calibrationMatrixSource = if (output.colorMatrixValid) {
            calibrationSolution.matrixSource
        } else {
            Niv99CalibrationMatrixSource.NONE
        }
        output.calibrationTransformIdentityFallback =
            output.colorMatrixValid && calibrationSolution.calibrationTransformIdentityFallback
        if (!output.colorMatrixValid) Niv99Math.setIdentity3(output.colorMatrix)
        output.identity.copyFrom(context.identity)
        output.sourceIdentity.copyFrom(context.identity)
    }

    private fun resolveHal(
        context: Niv99FrameContext,
        intent: Niv99WhiteBalanceIntent,
        output: Niv99WhiteBalanceSolution
    ) {
        output.authority = Niv99WhiteBalanceAuthority.HAL_AUTO
        output.redGain = intent.halRedGain
        output.greenGain = (intent.halGreenEvenGain + intent.halGreenOddGain) * 0.5f
        output.blueGain = intent.halBlueGain
        output.confidence = if (intent.halAwbConverged) 0.82f else 0.35f
        output.cctKelvin = 0f
        output.duv = 0f
        output.illuminantClass = Niv99IlluminantClass.UNKNOWN
        output.mixedLightConfidence = 0f
        output.localFieldRevision = 0L
        output.colorMatrixValid = intent.halMatrixValid && finiteMatrix(intent.halColorMatrix)
        output.calibrationMatrixSource = if (output.colorMatrixValid) {
            Niv99CalibrationMatrixSource.SAME_FRAME_HAL_MATRIX
        } else {
            Niv99CalibrationMatrixSource.NONE
        }
        output.calibrationTransformIdentityFallback = false
        if (output.colorMatrixValid) {
            intent.halColorMatrix.copyInto(output.colorMatrix)
        } else {
            Niv99Math.setIdentity3(output.colorMatrix)
        }
        output.identity.copyFrom(context.identity)
        output.sourceIdentity.copyFrom(context.identity)
    }

    private fun resolveUnsupported(output: Niv99WhiteBalanceSolution) {
        output.authority = Niv99WhiteBalanceAuthority.UNSUPPORTED
        output.redGain = 1f
        output.greenGain = 1f
        output.blueGain = 1f
        output.cctKelvin = 0f
        output.duv = 0f
        output.confidence = 0f
        output.illuminantClass = Niv99IlluminantClass.UNKNOWN
        output.mixedLightConfidence = 0f
        output.localFieldRevision = 0L
        output.colorMatrixValid = false
        output.calibrationMatrixSource = Niv99CalibrationMatrixSource.NONE
        output.calibrationTransformIdentityFallback = false
        Niv99Math.setIdentity3(output.colorMatrix)
    }

    private fun buildOutputMatrix(
        calibration: Niv99SensorCalibration,
        cctKelvin: Float,
        output: FloatArray
    ): Boolean {
        if (!interpolator.resolve(calibration, cctKelvin, calibrationSolution)) return false
        // DNG forward matrices target PCS D50. Adapt once to the app's D65 linear working white.
        if (!cat16.compute(
                sourceX = D50_X,
                sourceY = 1f,
                sourceZ = D50_Z,
                targetX = D65_X,
                targetY = 1f,
                targetZ = D65_Z,
                degreeOfAdaptation = 1f,
                output = adaptation
            )
        ) {
            return false
        }
        Niv99Math.multiply3(adaptation, calibrationSolution.sensorToOutput, adaptedMatrix)
        Niv99Math.copy3(adaptedMatrix, output)
        return finiteMatrix(output)
    }

    private fun finiteMatrix(matrix: FloatArray): Boolean {
        if (matrix.size < 9) return false
        for (index in 0 until 9) if (!matrix[index].isFinite()) return false
        return true
    }

    private fun clippingRisk(
        meanSignal: Float,
        redGain: Float,
        greenGain: Float,
        blueGain: Float,
        existingClipping: Float
    ): Float {
        val peakGain = max(redGain, max(greenGain, blueGain))
        val predicted = Niv99Math.smoothstep(0.72f, 1f, meanSignal * peakGain)
        return max(Niv99Math.saturateFinite(existingClipping), predicted)
    }

    private fun tintToDuv(tint: Int): Float = (tint.coerceIn(-150, 150) / 150f) * MAX_MANUAL_DUV

    private fun classify(cct: Float, duv: Float): Niv99IlluminantClass = when {
        kotlin.math.abs(duv) > 0.025f -> Niv99IlluminantClass.FLUORESCENT
        cct < 3_100f -> Niv99IlluminantClass.TUNGSTEN
        cct < 4_000f -> Niv99IlluminantClass.WARM_LED
        cct < 6_800f -> Niv99IlluminantClass.DAYLIGHT
        cct < 8_500f -> Niv99IlluminantClass.OVERCAST
        else -> Niv99IlluminantClass.SHADE
    }

    companion object {
        private const val MIXED_LIGHT_THRESHOLD = 0.45f
        private const val MAX_MANUAL_DUV = 0.035f
        private const val D50_X = 0.96422f
        private const val D50_Z = 0.82521f
        private const val D65_X = 0.95047f
        private const val D65_Z = 1.08883f
    }
}
