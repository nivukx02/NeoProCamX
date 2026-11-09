package com.neoproc.neoproccamx.ai

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.*

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * PROFESSIONAL FOCUS CONTROLLER - cinema-oriented ALGORITHMS (UPGRADED)
 * ════════════════════════════════════════════════════════════════════════════════
 * 
 * Cinematic focus control features:
 * - Smooth focus transitions with easing curves
 * - Predictive focus based on subject motion
 * - Professional focus pulling simulation
 * - Adaptive distance ramping
 */

internal data class FocusPidState(
    val integral: Float = 0f,
    val lastError: Float = 0f,
    val lastUpdateNs: Long = 0L,
    val lastAppliedDiopters: Float = -1f,
    val transitionStartDiopters: Float = -1f,
    val transitionTargetDiopters: Float = -1f,
    val transitionStartNs: Long = 0L,
    val transitionDurationNs: Long = 0L
)

internal data class FocusPidResult(
    val appliedDiopters: Float,
    val nextState: FocusPidState,
    val isTransitioning: Boolean = false,
    val transitionProgress: Float = 0f
)

internal data class DepthAwareFocusStep(
    val targetDiopters: Float?,
    val appliedDiopters: Float,
    val nextState: FocusPidState,
    val shouldApply: Boolean,
    val confidence: Float
)

/**
 * Easing curves for cinematic focus transitions
 */
enum class FocusEasingCurve {
    LINEAR,
    EASE_IN_QUAD,
    EASE_OUT_QUAD,
    EASE_IN_OUT_QUAD,
    EASE_IN_CUBIC,
    EASE_OUT_CUBIC,
    EASE_IN_OUT_CUBIC,
    EASE_IN_OUT_SINE,
    /** Smooth start, smooth stop (cinema standard) */
    SMOOTHSTEP,
    /** Even smoother (Ken Burns effect) */
    SMOOTHERSTEP
}

internal object AIFocusDistanceController {
    private const val MIN_DEPTH_CONFIDENCE = 0.01f
    private const val DEFAULT_DT_SECONDS = 0.016f
    private const val MIN_DT_SECONDS = 0.001f
    private const val MAX_DT_SECONDS = 0.12f
    private const val PROPORTIONAL_GAIN = 1.25f
    private const val INTEGRAL_GAIN = 0.28f
    private const val DERIVATIVE_GAIN = 0.12f
    private const val APPLY_THRESHOLD_DIOPTERS = 0.05f
    private const val MIN_DEPTH_FOCUS_CONFIDENCE = 0.42f
    private const val MIN_EDGE_FOCUS_EVIDENCE = 0.30f
    
    // Cinematic transition parameters
    private const val DEFAULT_TRANSITION_MS = 320L
    private const val FAST_TRANSITION_MS = 180L
    private const val SLOW_TRANSITION_MS = 550L
    private const val PREDICTIVE_LEAD_MS = 220L

    // ════════════════════════════════════════════════════════════════════════════
    // DEPTH TO DIOPTERS CONVERSION
    // ════════════════════════════════════════════════════════════════════════════

    fun estimateTargetDiopters(
        normalizedDepth: Float,
        maxDiopters: Float
    ): Float? {
        if (maxDiopters <= 0f) return null
        val clampedDepth = normalizedDepth.coerceIn(0f, 1f)
        if (clampedDepth <= MIN_DEPTH_CONFIDENCE) return null
        
        // Smoothstep easing for more natural depth-to-diopters mapping
        val easedDepth = smoothstep(clampedDepth)
        val weightedDepth = (0.12f + easedDepth * 0.88f).coerceIn(0f, 1f)
        return (weightedDepth * maxDiopters).coerceIn(0f, maxDiopters)
    }

    fun estimateTargetDioptersDepthAware(
        normalizedDepth: Float,
        maxDiopters: Float,
        subjectConfidence: Float,
        edgeConfidence: Float,
        motionScore: Float
    ): Float? {
        if (maxDiopters <= 0f) return null
        val clampedDepth = normalizedDepth.coerceIn(0f, 1f)
        if (clampedDepth <= MIN_DEPTH_CONFIDENCE) return null
        if (edgeConfidence < MIN_EDGE_FOCUS_EVIDENCE) return null

        val confidence = depthFocusConfidence(
            subjectConfidence = subjectConfidence,
            edgeConfidence = edgeConfidence,
            motionScore = motionScore
        )
        if (confidence < MIN_DEPTH_FOCUS_CONFIDENCE) return null

        val easedDepth = smoothstep(clampedDepth)
        val linearWeight = (0.28f + confidence * 0.18f).coerceIn(0.28f, 0.46f)
        val confidenceWeightedDepth = lerp(easedDepth, clampedDepth, linearWeight)
        val nearSubjectBias = smoothstep((confidenceWeightedDepth - 0.42f).coerceIn(0f, 1f)) * 0.035f
        val weightedDepth = (
            0.08f +
                confidenceWeightedDepth * 0.90f +
                nearSubjectBias * confidence
            ).coerceIn(0f, 1f)
        return (weightedDepth * maxDiopters).coerceIn(0f, maxDiopters)
    }

    /**
     * Estimate target diopters with predictive motion compensation
     */
    fun estimateTargetDioptersPredictive(
        normalizedDepth: Float,
        depthVelocity: Float,
        maxDiopters: Float,
        leadTimeMs: Long = PREDICTIVE_LEAD_MS
    ): Float? {
        if (maxDiopters <= 0f) return null
        
        // Predict future depth based on velocity
        val leadSeconds = leadTimeMs / 1000f
        val predictedDepth = (normalizedDepth + depthVelocity * leadSeconds).coerceIn(0f, 1f)
        
        return estimateTargetDiopters(predictedDepth, maxDiopters)
    }

    // ════════════════════════════════════════════════════════════════════════════
    // CINEMATIC FOCUS TRANSITIONS
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Apply smooth focus transition with easing curve
     */
    fun applyCinematicTransition(
        targetDiopters: Float,
        maxDiopters: Float,
        state: FocusPidState,
        nowNs: Long,
        transitionDurationMs: Long = DEFAULT_TRANSITION_MS,
        easingCurve: FocusEasingCurve = FocusEasingCurve.EASE_IN_OUT_CUBIC
    ): FocusPidResult {
        if (maxDiopters <= 0f) {
            return FocusPidResult(
                appliedDiopters = targetDiopters,
                nextState = state.copy(lastAppliedDiopters = targetDiopters, lastUpdateNs = nowNs),
                isTransitioning = false
            )
        }
        
        val current = state.lastAppliedDiopters.takeIf { it >= 0f } ?: targetDiopters
        
        // Check if we need to start a new transition
        val significantChange = abs(targetDiopters - current) > APPLY_THRESHOLD_DIOPTERS
        val isCurrentlyTransitioning = state.transitionStartNs > 0L && 
                                       nowNs < state.transitionStartNs + state.transitionDurationNs
        
        val newState = when {
            // Start new transition
            significantChange && !isCurrentlyTransitioning -> {
                state.copy(
                    transitionStartDiopters = current,
                    transitionTargetDiopters = targetDiopters,
                    transitionStartNs = nowNs,
                    transitionDurationNs = transitionDurationMs * 1_000_000L
                )
            }
            // Continue existing transition
            isCurrentlyTransitioning -> state
            // No transition needed
            else -> state.copy(lastAppliedDiopters = current, lastUpdateNs = nowNs)
        }
        
        // Calculate transition progress
        if (newState.transitionStartNs > 0L && nowNs >= newState.transitionStartNs) {
            val elapsed = nowNs - newState.transitionStartNs
            val progress = (elapsed.toDouble() / newState.transitionDurationNs.toDouble())
                .coerceIn(0.0, 1.0).toFloat()
            
            if (progress >= 1f) {
                // Transition complete
                return FocusPidResult(
                    appliedDiopters = newState.transitionTargetDiopters,
                    nextState = newState.copy(
                        lastAppliedDiopters = newState.transitionTargetDiopters,
                        lastUpdateNs = nowNs,
                        transitionStartNs = 0L
                    ),
                    isTransitioning = false,
                    transitionProgress = 1f
                )
            } else {
                // Interpolate with easing
                val easedProgress = applyEasing(progress, easingCurve)
                val interpolated = lerp(
                    newState.transitionStartDiopters,
                    newState.transitionTargetDiopters,
                    easedProgress
                ).coerceIn(0f, maxDiopters)
                
                return FocusPidResult(
                    appliedDiopters = interpolated,
                    nextState = newState.copy(
                        lastAppliedDiopters = interpolated,
                        lastUpdateNs = nowNs
                    ),
                    isTransitioning = true,
                    transitionProgress = progress
                )
            }
        }
        
        return FocusPidResult(
            appliedDiopters = current,
            nextState = newState.copy(lastUpdateNs = nowNs),
            isTransitioning = false
        )
    }

    /**
     * Adaptive transition duration based on distance and motion pattern
     */
    fun calculateAdaptiveTransitionDuration(
        currentDiopters: Float,
        targetDiopters: Float,
        motionPattern: MotionPattern,
        maxDiopters: Float
    ): Long {
        val diopterDelta = abs(targetDiopters - currentDiopters)
        val normalizedDelta = if (maxDiopters > 0f) {
            (diopterDelta / maxDiopters).coerceIn(0f, 1f)
        } else {
            0f
        }
        
        // Base duration scales with distance
        val baseDuration = when {
            normalizedDelta < 0.1f -> FAST_TRANSITION_MS
            normalizedDelta > 0.5f -> SLOW_TRANSITION_MS
            else -> DEFAULT_TRANSITION_MS
        }
        
        // Adjust for motion pattern
        val motionMultiplier = when (motionPattern) {
            MotionPattern.STATIC -> 1.2f       // Slower for static (cinema look)
            MotionPattern.LINEAR -> 1.0f       // Normal
            MotionPattern.ACCELERATING -> 0.8f // Faster
            MotionPattern.DECELERATING -> 1.1f // Slightly slower
            MotionPattern.CIRCULAR -> 0.9f     // Moderate
            MotionPattern.ERRATIC -> 0.7f      // Fast (keep up with motion)
        }
        
        return (baseDuration * motionMultiplier).toLong()
    }

    // ════════════════════════════════════════════════════════════════════════════
    // PROFESSIONAL FOCUS PULLING
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Simulate professional focus puller behavior
     * Smooth distance ramping with anticipation
     */
    fun applyFocusPull(
        targetDiopters: Float,
        maxDiopters: Float,
        state: FocusPidState,
        nowNs: Long,
        pullStyle: FocusPullStyle = FocusPullStyle.SMOOTH
    ): FocusPidResult {
        val (duration, easing) = when (pullStyle) {
            FocusPullStyle.SMOOTH -> DEFAULT_TRANSITION_MS to FocusEasingCurve.EASE_IN_OUT_CUBIC
            FocusPullStyle.RACK -> FAST_TRANSITION_MS to FocusEasingCurve.EASE_IN_OUT_QUAD
            FocusPullStyle.CREEP -> SLOW_TRANSITION_MS to FocusEasingCurve.SMOOTHERSTEP
            FocusPullStyle.SNAP -> (FAST_TRANSITION_MS / 2) to FocusEasingCurve.EASE_OUT_QUAD
        }
        
        return applyCinematicTransition(
            targetDiopters, maxDiopters, state, nowNs, duration, easing
        )
    }

    enum class FocusPullStyle {
        /** Smooth, professional transition (default) */
        SMOOTH,
        /** Quick rack focus (dramatic effect) */
        RACK,
        /** Slow creep (suspense/reveal) */
        CREEP,
        /** Instant snap (documentary style) */
        SNAP
    }

    // ════════════════════════════════════════════════════════════════════════════
    // LEGACY PID CONTROLLER (MAINTAINED FOR COMPATIBILITY)
    // ════════════════════════════════════════════════════════════════════════════

    fun applyPid(
        targetDiopters: Float,
        maxDiopters: Float,
        state: FocusPidState,
        nowNs: Long
    ): FocusPidResult {
        if (maxDiopters <= 0f) {
            return FocusPidResult(
                appliedDiopters = targetDiopters,
                nextState = state.copy(lastAppliedDiopters = targetDiopters, lastUpdateNs = nowNs)
            )
        }

        val current = state.lastAppliedDiopters.takeIf { it >= 0f } ?: targetDiopters
        val dtSeconds = if (state.lastUpdateNs > 0L) {
            ((nowNs - state.lastUpdateNs).toDouble() / 1_000_000_000.0)
                .toFloat()
                .coerceIn(MIN_DT_SECONDS, MAX_DT_SECONDS)
        } else {
            DEFAULT_DT_SECONDS
        }

        val error = targetDiopters - current
        val integral = (state.integral + error * dtSeconds).coerceIn(-maxDiopters, maxDiopters)
        val derivative = (error - state.lastError) / dtSeconds
        val strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        
        val proportionalGain = if (strictHighEnd) {
            PROPORTIONAL_GAIN * 1.20f
        } else if (HighEndQualityPolicy.enabled) {
            PROPORTIONAL_GAIN * 1.10f
        } else {
            PROPORTIONAL_GAIN
        }
        val integralGain = if (strictHighEnd) {
            INTEGRAL_GAIN * 1.10f
        } else if (HighEndQualityPolicy.enabled) {
            INTEGRAL_GAIN * 1.05f
        } else {
            INTEGRAL_GAIN
        }
        val derivativeGain = if (strictHighEnd) {
            DERIVATIVE_GAIN * 1.35f
        } else if (HighEndQualityPolicy.enabled) {
            DERIVATIVE_GAIN * 1.20f
        } else {
            DERIVATIVE_GAIN
        }
        
        val output = current +
            error * proportionalGain +
            integral * integralGain +
            derivative * derivativeGain
        val boundedOutput = output.coerceIn(0f, maxDiopters)
        val smoothingFactor = if (strictHighEnd) 0.18f else if (HighEndQualityPolicy.enabled) 0.22f else 0.35f
        val applied = (current + (boundedOutput - current) * (1f - smoothingFactor)).coerceIn(0f, maxDiopters)

        return FocusPidResult(
            appliedDiopters = applied,
            nextState = FocusPidState(
                integral = integral,
                lastError = error,
                lastUpdateNs = nowNs,
                lastAppliedDiopters = applied
            )
        )
    }

    fun resolveDepthAwareFocusStep(
        normalizedDepth: Float,
        maxDiopters: Float,
        subjectConfidence: Float,
        edgeConfidence: Float,
        motionScore: Float,
        state: FocusPidState,
        nowNs: Long
    ): DepthAwareFocusStep {
        val current = state.lastAppliedDiopters.takeIf { it >= 0f } ?: 0f
        val confidence = depthFocusConfidence(
            subjectConfidence = subjectConfidence,
            edgeConfidence = edgeConfidence,
            motionScore = motionScore
        )
        val targetDiopters = estimateTargetDioptersDepthAware(
            normalizedDepth = normalizedDepth,
            maxDiopters = maxDiopters,
            subjectConfidence = subjectConfidence,
            edgeConfidence = edgeConfidence,
            motionScore = motionScore
        ) ?: return DepthAwareFocusStep(
            targetDiopters = null,
            appliedDiopters = current,
            nextState = state.copy(lastAppliedDiopters = current, lastUpdateNs = nowNs),
            shouldApply = false,
            confidence = confidence
        )

        val pidResult = applyPid(
            targetDiopters = targetDiopters,
            maxDiopters = maxDiopters,
            state = state,
            nowNs = nowNs
        )
        val guardedTargetApproach = current + (targetDiopters - current) * 0.88f
        val boundedApplied = when {
            pidResult.appliedDiopters > current && pidResult.appliedDiopters > targetDiopters -> guardedTargetApproach
            pidResult.appliedDiopters < current && pidResult.appliedDiopters < targetDiopters -> guardedTargetApproach
            else -> pidResult.appliedDiopters
        }.coerceIn(0f, maxDiopters)
        val boundedState = pidResult.nextState.copy(lastAppliedDiopters = boundedApplied)
        val adaptiveThreshold = adaptiveApplyThreshold(
            confidence = confidence,
            motionScore = motionScore
        )
        val shouldApply =
            current < 0f || abs(boundedApplied - current) >= adaptiveThreshold

        return DepthAwareFocusStep(
            targetDiopters = targetDiopters,
            appliedDiopters = boundedApplied,
            nextState = boundedState,
            shouldApply = shouldApply,
            confidence = confidence
        )
    }

    fun shouldApply(lastAppliedDiopters: Float, candidateDiopters: Float): Boolean {
        if (lastAppliedDiopters < 0f) return true
        val threshold = if (HighEndQualityPolicy.enabled) {
            APPLY_THRESHOLD_DIOPTERS * 0.5f
        } else {
            APPLY_THRESHOLD_DIOPTERS
        }
        return abs(lastAppliedDiopters - candidateDiopters) >= threshold
    }

    private fun depthFocusConfidence(
        subjectConfidence: Float,
        edgeConfidence: Float,
        motionScore: Float
    ): Float {
        val subject = subjectConfidence.coerceIn(0f, 1f)
        val edge = edgeConfidence.coerceIn(0f, 1f)
        val motionPenalty = motionScore.coerceIn(0f, 1f) * 0.18f
        val strictLift = if (HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly) 0.04f else 0f
        return (subject * 0.58f + edge * 0.42f + strictLift - motionPenalty).coerceIn(0f, 1f)
    }

    private fun adaptiveApplyThreshold(
        confidence: Float,
        motionScore: Float
    ): Float {
        val base = if (HighEndQualityPolicy.enabled) {
            APPLY_THRESHOLD_DIOPTERS * 0.42f
        } else {
            APPLY_THRESHOLD_DIOPTERS
        }
        val confidenceScale = (1.0f - confidence.coerceIn(0f, 1f) * 0.34f).coerceIn(0.60f, 1.0f)
        val motionScale = (1.0f + motionScore.coerceIn(0f, 1f) * 0.65f).coerceIn(1.0f, 1.65f)
        return (base * confidenceScale * motionScale).coerceIn(0.010f, APPLY_THRESHOLD_DIOPTERS)
    }

    // ════════════════════════════════════════════════════════════════════════════
    // EASING FUNCTIONS
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Apply easing curve to linear progress [0, 1]
     */
    private fun applyEasing(t: Float, curve: FocusEasingCurve): Float {
        return when (curve) {
            FocusEasingCurve.LINEAR -> t
            
            FocusEasingCurve.EASE_IN_QUAD -> t * t
            FocusEasingCurve.EASE_OUT_QUAD -> t * (2f - t)
            FocusEasingCurve.EASE_IN_OUT_QUAD -> {
                if (t < 0.5f) 2f * t * t
                else -1f + (4f - 2f * t) * t
            }
            
            FocusEasingCurve.EASE_IN_CUBIC -> t * t * t
            FocusEasingCurve.EASE_OUT_CUBIC -> {
                val t1 = t - 1f
                t1 * t1 * t1 + 1f
            }
            FocusEasingCurve.EASE_IN_OUT_CUBIC -> {
                if (t < 0.5f) 4f * t * t * t
                else {
                    val t1 = 2f * t - 2f
                    1f + t1 * t1 * t1 / 2f
                }
            }
            
            FocusEasingCurve.EASE_IN_OUT_SINE -> {
                (1f - cos(t * PI.toFloat())) / 2f
            }
            
            FocusEasingCurve.SMOOTHSTEP -> smoothstep(t)
            FocusEasingCurve.SMOOTHERSTEP -> smootherstep(t)
        }
    }

    /**
     * Smoothstep interpolation (3rd order Hermite)
     */
    private fun smoothstep(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }

    /**
     * Smootherstep interpolation (5th order Hermite)
     * Even smoother than smoothstep
     */
    private fun smootherstep(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return clamped * clamped * clamped * (clamped * (clamped * 6f - 15f) + 10f)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
