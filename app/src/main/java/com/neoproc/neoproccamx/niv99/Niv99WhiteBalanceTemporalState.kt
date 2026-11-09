package com.neoproc.neoproccamx.niv99

import kotlin.math.max

/**
 * Confidence-aware temporal stabilization and shot-lock state. All storage is preallocated. A reset
 * boundary is evaluated before history is read, preventing cross-camera/session/mode contamination.
 */
class Niv99WhiteBalanceTemporalState {
    private val timeline = Niv99TimelineSignature()
    private val stable = Niv99WhiteBalanceSolution()
    private val locked = Niv99WhiteBalanceSolution()
    private var stableValid = false
    private var lockValid = false
    private var previousVelocityRed = 0f
    private var previousVelocityBlue = 0f

    fun stabilize(
        context: Niv99FrameContext,
        candidate: Niv99WhiteBalanceSolution,
        output: Niv99WhiteBalanceSolution
    ): Niv99ResetReason {
        var resetReason = timeline.resetReason(context)
        if (context.sceneCutHint) resetReason = Niv99ResetReason.SCENE_CUT
        if (resetReason != Niv99ResetReason.NONE) resetInternal(resetReason)

        val intent = context.whiteBalanceIntent
        if (candidate.authority == Niv99WhiteBalanceAuthority.MANUAL) {
            releaseLock()
            output.copyFrom(candidate)
            output.identity.copyFrom(context.identity)
            output.sourceIdentity.copyFrom(context.identity)
            output.resetReason = resetReason
            stable.copyFrom(output)
            stableValid = true
            timeline.update(context)
            return resetReason
        }

        val lockRequested = intent.awbLockRequested || intent.awbLockActive
        if (lockRequested) {
            if (!lockValid) {
                val sameFrameHalLock = intent.awbLockActive &&
                    candidate.authority == Niv99WhiteBalanceAuthority.HAL_AUTO &&
                    sameFrame(candidate.sourceIdentity, context.identity)
                val source = when {
                    sameFrameHalLock -> candidate
                    stableValid -> stable
                    candidate.authority != Niv99WhiteBalanceAuthority.UNSUPPORTED -> candidate
                    else -> null
                }
                if (source == null) {
                    output.copyFrom(candidate)
                    output.identity.copyFrom(context.identity)
                    output.sourceIdentity.copyFrom(context.identity)
                    output.authority = Niv99WhiteBalanceAuthority.UNSUPPORTED
                    output.resetReason = resetReason
                    timeline.update(context)
                    return resetReason
                }
                locked.copyFrom(source)
                locked.authority = Niv99WhiteBalanceAuthority.AWB_LOCK
                lockValid = true
            }
            output.copyFrom(locked)
            output.identity.copyFrom(context.identity)
            output.authority = Niv99WhiteBalanceAuthority.AWB_LOCK
            output.resetReason = resetReason
            timeline.update(context)
            return resetReason
        }

        if (lockValid) {
            releaseLock()
            stableValid = false
            resetReason = Niv99ResetReason.EXPLICIT_RESET
        }

        if (!stableValid || resetReason != Niv99ResetReason.NONE) {
            output.copyFrom(candidate)
            output.identity.copyFrom(context.identity)
            output.sourceIdentity.copyFrom(candidate.sourceIdentity)
            output.resetReason = resetReason
            stable.copyFrom(output)
            stableValid = true
            previousVelocityRed = 0f
            previousVelocityBlue = 0f
            timeline.update(context)
            return resetReason
        }

        val previousLogRed = Niv99Math.safeLog(stable.redGain)
        val previousLogBlue = Niv99Math.safeLog(stable.blueGain)
        val candidateLogRed = Niv99Math.safeLog(candidate.redGain)
        val candidateLogBlue = Niv99Math.safeLog(candidate.blueGain)
        val deltaRed = candidateLogRed - previousLogRed
        val deltaBlue = candidateLogBlue - previousLogBlue
        val chromaDistance = kotlin.math.sqrt(deltaRed * deltaRed + deltaBlue * deltaBlue)
        val confidence = Niv99Math.saturateFinite(candidate.confidence)
        val sceneMotion = Niv99Math.saturateFinite(context.motionMagnitude)
        val deadband = Niv99Math.lerp(MAX_DEADBAND, MIN_DEADBAND, confidence)
        val directionChange = deltaRed * previousVelocityRed + deltaBlue * previousVelocityBlue < 0f
        val directionPenalty = if (directionChange) DIRECTION_REVERSAL_PENALTY else 1f
        val baseAlpha = Niv99Math.lerp(MIN_ALPHA, MAX_ALPHA, confidence) *
            Niv99Math.lerp(1f, MOTION_ALPHA_SCALE, sceneMotion) * directionPenalty
        val alpha = when {
            chromaDistance <= deadband -> 0f
            chromaDistance >= IMMEDIATE_CHANGE_DISTANCE && confidence >= IMMEDIATE_CHANGE_CONFIDENCE ->
                IMMEDIATE_CHANGE_ALPHA
            else -> baseAlpha
        }.coerceIn(0f, MAX_ALPHA)
        val limitedRedDelta = deltaRed.coerceIn(-MAX_LOG_GAIN_STEP, MAX_LOG_GAIN_STEP)
        val limitedBlueDelta = deltaBlue.coerceIn(-MAX_LOG_GAIN_STEP, MAX_LOG_GAIN_STEP)

        output.copyFrom(candidate)
        output.identity.copyFrom(context.identity)
        output.sourceIdentity.copyFrom(candidate.sourceIdentity)
        output.redGain = Niv99Math.safeExp(previousLogRed + limitedRedDelta * alpha)
        output.greenGain = Niv99Math.lerp(stable.greenGain, candidate.greenGain, alpha)
        output.blueGain = Niv99Math.safeExp(previousLogBlue + limitedBlueDelta * alpha)
        output.cctKelvin = Niv99Math.lerp(stable.cctKelvin, candidate.cctKelvin, alpha)
        output.duv = Niv99Math.lerp(stable.duv, candidate.duv, alpha)
        output.confidence = Niv99Math.lerp(stable.confidence, candidate.confidence, max(alpha, 0.08f))
        output.mixedLightConfidence = Niv99Math.lerp(
            stable.mixedLightConfidence,
            candidate.mixedLightConfidence,
            alpha
        )
        if (stable.colorMatrixValid && candidate.colorMatrixValid) {
            Niv99Math.interpolateMatrix3(stable.colorMatrix, candidate.colorMatrix, alpha, output.colorMatrix)
            output.colorMatrixValid = true
        } else if (!candidate.colorMatrixValid) {
            output.colorMatrixValid = false
            Niv99Math.setIdentity3(output.colorMatrix)
        }
        output.resetReason = resetReason
        previousVelocityRed = limitedRedDelta * alpha
        previousVelocityBlue = limitedBlueDelta * alpha
        stable.copyFrom(output)
        timeline.update(context)
        return resetReason
    }

    fun reset(reason: Niv99ResetReason = Niv99ResetReason.EXPLICIT_RESET) {
        resetInternal(reason)
    }

    private fun resetInternal(@Suppress("UNUSED_PARAMETER") reason: Niv99ResetReason) {
        stableValid = false
        lockValid = false
        previousVelocityRed = 0f
        previousVelocityBlue = 0f
        timeline.initialized = false
    }

    private fun releaseLock() {
        lockValid = false
    }

    private fun sameFrame(first: Niv99FrameIdentity, second: Niv99FrameIdentity): Boolean =
        first.sameTimeline(second) &&
            first.frameNumber == second.frameNumber &&
            first.sensorTimestampNs == second.sensorTimestampNs

    companion object {
        private const val MIN_ALPHA = 0.035f
        private const val MAX_ALPHA = 0.62f
        private const val MOTION_ALPHA_SCALE = 0.55f
        private const val DIRECTION_REVERSAL_PENALTY = 0.42f
        private const val MIN_DEADBAND = 0.0015f
        private const val MAX_DEADBAND = 0.012f
        private const val MAX_LOG_GAIN_STEP = 0.055f
        private const val IMMEDIATE_CHANGE_DISTANCE = 0.24f
        private const val IMMEDIATE_CHANGE_CONFIDENCE = 0.82f
        private const val IMMEDIATE_CHANGE_ALPHA = 0.82f
    }
}
