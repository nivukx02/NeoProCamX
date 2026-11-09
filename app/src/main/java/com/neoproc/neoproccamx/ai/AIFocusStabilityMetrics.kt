package com.neoproc.neoproccamx.ai

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * AI FOCUS STABILITY METRICS - AF HUNTING DETECTION & LOCK RECOMMENDATION
 * ════════════════════════════════════════════════════════════════════════════════
 * 
 * Monitors autofocus stability and detects hunting patterns (repeated focus sweeps).
 * Provides recommendations for focus lock to prevent distracting AF hunting in video.
 * 
 * Features:
 * - AF hunting detection via focus position oscillation analysis
 * - Focus lock confidence scoring
 * - Subject tracking stability assessment
 * - Predictive hunting risk early warning
 * - Adaptive thresholds based on scene complexity
 */
/**
 * CP-F5 (s5-f04): pure scene-cut / risk-gate decision used by the AF coordinator behind the
 * default-OFF SCENE_CUT_RISK_GATING_ENABLED gate. It reuses signals the coordinator already
 * derives — the subject-discontinuity magnitude (`moved`), the policy's `hardReacquire` decision,
 * the real subject `confidence`, and a `sceneContrast` (1.0f = neutral/no-low-contrast today) —
 * and returns whether to (a) perform a SINGLE deliberate re-acquire on a detected hard-cut /
 * whip-pan, or (b) HOLD the last good target on a low-confidence / low-contrast (risk) frame.
 * No state is held here; the coordinator owns the per-discontinuity "already armed" latch.
 */
internal data class SceneCutRiskDecision(
    val reacquireOnce: Boolean,
    val holdLastGood: Boolean
)

internal object AIFocusSceneCutRiskPolicy {
    // A genuine hard-cut / whip-pan is a large subject discontinuity that the stability policy ALSO
    // classified as a hard re-acquire. Both must hold so micro-motion can never read as a cut.
    const val SCENE_CUT_MOVE_THRESHOLD = 0.35f
    // Risk floors: below either, HOLD the last good target instead of chasing transient garbage.
    const val MIN_CONFIDENCE_FOR_RETRIGGER = 0.25f
    const val MIN_CONTRAST_FOR_RETRIGGER = 0.12f

    fun isSceneCut(moved: Float, hardReacquire: Boolean): Boolean =
        hardReacquire && moved >= SCENE_CUT_MOVE_THRESHOLD

    fun isRisk(subjectConfidence: Float, sceneContrast: Float): Boolean =
        subjectConfidence < MIN_CONFIDENCE_FOR_RETRIGGER || sceneContrast < MIN_CONTRAST_FOR_RETRIGGER

    /**
     * Risk gating takes precedence over scene-cut re-acquire: a low-confidence cut is transient
     * garbage and must be HELD, never chased. A real cut re-acquires exactly once — the
     * `sceneCutAlreadyArmed` latch (owned by the caller) prevents a pump train across the frames
     * of one discontinuity.
     */
    fun resolveSceneCutRisk(
        moved: Float,
        hardReacquire: Boolean,
        subjectConfidence: Float,
        sceneContrast: Float,
        sceneCutAlreadyArmed: Boolean
    ): SceneCutRiskDecision {
        if (isRisk(subjectConfidence, sceneContrast)) {
            return SceneCutRiskDecision(reacquireOnce = false, holdLastGood = true)
        }
        val cut = isSceneCut(moved, hardReacquire)
        return SceneCutRiskDecision(
            reacquireOnce = cut && !sceneCutAlreadyArmed,
            holdLastGood = false
        )
    }
}

// CP-F1: converted object -> class so the AF coordinator owns a per-session instance (the previous
// sole consumer fed it a hardcoded 0f and discarded the report). No other production references exist.
class AIFocusStabilityMetrics {
    companion object {
        const val TAG = "AFStabilityMetrics"
        const val HISTORY_SIZE = 30  // Track last 30 frames
        const val HUNTING_OSCILLATION_THRESHOLD = 0.08f
        const val HUNTING_DETECTION_FRAMES = 5
        const val FOCUS_LOCK_CONFIDENCE_THRESHOLD = 0.75f
        const val SCENE_COMPLEXITY_THRESHOLD = 0.65f

        // CP-F5: a smooth follow keeps the 3rd derivative (jerk) of the lens-distance
        // trajectory below this; a pump/snap-back exceeds it (diopters/s^3, test frame units).
        const val BOUNDED_FOLLOW_JERK_THRESHOLD = 5.0f
    }

    // CP-F5 (s5-f04): observe-only telemetry. These NEVER affect the live decision path — they are
    // recorded for tests/diagnostics so the no-downgrade scene-cut/risk-gate extension can be
    // measured (pump cycles, deliberate re-acquires, follow-jerk) without changing pixels/requests.
    private var pumpCycleCount = 0
    private var reacquireEventCount = 0
    private var maxFollowJerk = 0f
    private var jerkPrevDiopters = Float.NaN
    private var jerkPrevTimestampMs = 0L
    private var jerkPrevVelocity = Float.NaN
    private var jerkPrevAcceleration = Float.NaN

    /** CP-F5: count a non-suppressed single-shot retrigger as a pump candidate (suppressed ones are not). */
    fun notePumpDecision(suppressed: Boolean) {
        if (!suppressed) pumpCycleCount++
    }

    /** CP-F5: count a deliberate scene-cut re-acquire. */
    fun recordReacquireEvent() {
        reacquireEventCount++
    }

    /**
     * CP-F5: feed the applied lens-distance (or region-center) trajectory; tracks the maximum
     * jerk (3rd derivative) seen, so a bounded-follow-jerk test can prove a constant-velocity
     * follow stays smooth. Observe-only.
     */
    fun recordFollowSample(positionDiopters: Float, timestampMs: Long) {
        if (jerkPrevDiopters.isNaN()) {
            jerkPrevDiopters = positionDiopters
            jerkPrevTimestampMs = timestampMs
            return
        }
        val dt = ((timestampMs - jerkPrevTimestampMs).coerceAtLeast(1L)).toFloat() / 1000f
        val velocity = (positionDiopters - jerkPrevDiopters) / dt
        if (!jerkPrevVelocity.isNaN()) {
            val acceleration = (velocity - jerkPrevVelocity) / dt
            if (!jerkPrevAcceleration.isNaN()) {
                val jerk = abs((acceleration - jerkPrevAcceleration) / dt)
                if (jerk > maxFollowJerk) maxFollowJerk = jerk
            }
            jerkPrevAcceleration = acceleration
        }
        jerkPrevVelocity = velocity
        jerkPrevDiopters = positionDiopters
        jerkPrevTimestampMs = timestampMs
    }

    /** CP-F5: immutable snapshot of the observe-only counters for tests/telemetry. */
    fun metricsSnapshot(): AIFocusMetricsSnapshot =
        AIFocusMetricsSnapshot(
            pumpCycleCount = pumpCycleCount,
            reacquireEventCount = reacquireEventCount,
            maxFollowJerk = maxFollowJerk
        )

    private data class FocusHistory(
        val focusDistanceDiopters: Float,
        val timestamp: Long,
        val subjectConfidence: Float,
        val sceneComplexity: Float = 0.5f
    )

    private val focusHistoryBuffer = ArrayDeque<FocusHistory>(HISTORY_SIZE)
    private var lastHuntingDetectionTime = 0L
    private var consecutiveHuntingFrames = 0

    /**
     * Analyze focus stability and detect hunting patterns
     */
    fun analyzeFocusStability(
        currentFocusDiopters: Float,
        subjectConfidence: Float,
        sceneComplexity: Float,
        timestampMs: Long
    ): FocusStabilityReport {
        // Record history
        focusHistoryBuffer.addLast(FocusHistory(
            focusDistanceDiopters = currentFocusDiopters,
            timestamp = timestampMs,
            subjectConfidence = subjectConfidence,
            sceneComplexity = sceneComplexity
        ))

        if (focusHistoryBuffer.size > HISTORY_SIZE) {
            focusHistoryBuffer.removeFirst()
        }

        val isHunting = detectHunting()
        val focusLockConfidence = calculateFocusLockConfidence(subjectConfidence)
        val stabilityScore = calculateStabilityScore()
        val huntingRisk = predictHuntingRisk(sceneComplexity)

        if (isHunting) {
            consecutiveHuntingFrames++
            lastHuntingDetectionTime = timestampMs
        } else {
            consecutiveHuntingFrames = maxOf(0, consecutiveHuntingFrames - 1)
        }

        val shouldLockFocus = focusLockConfidence > FOCUS_LOCK_CONFIDENCE_THRESHOLD &&
                stabilityScore > 0.6f &&
                !isHunting

        return FocusStabilityReport(
            isHunting = isHunting,
            huntingConfidence = calculateHuntingConfidence(),
            stabilityScore = stabilityScore,
            focusLockConfidence = focusLockConfidence,
            shouldLockFocus = shouldLockFocus,
            huntingRiskPrediction = huntingRisk,
            recommendedLockDuration = if (shouldLockFocus) calculateOptimalLockDuration() else 0L,
            focusVariance = calculateFocusVariance(),
            subjectTrackingStability = subjectConfidence
        )
    }

    /**
     * Detect hunting pattern via oscillation analysis
     */
    private fun detectHunting(): Boolean {
        if (focusHistoryBuffer.size < HUNTING_DETECTION_FRAMES + 2) return false

        val recent = focusHistoryBuffer.takeLast(HUNTING_DETECTION_FRAMES + 2)
        val diopters = recent.map { it.focusDistanceDiopters }

        // Count direction changes (hunting signature: up-down-up or down-up-down pattern)
        var directionChanges = 0
        var lastDirection = 0  // 0: none, 1: increasing, -1: decreasing
        var oscillationAmplitude = 0f

        for (i in 1 until diopters.size) {
            val diff = diopters[i] - diopters[i - 1]
            val direction = when {
                diff > 0.001f -> 1
                diff < -0.001f -> -1
                else -> 0
            }

            if (direction != 0 && lastDirection != 0 && direction != lastDirection) {
                directionChanges++
            }

            if (direction != 0) {
                lastDirection = direction
                oscillationAmplitude = maxOf(oscillationAmplitude, abs(diff))
            }
        }

        // Hunting detected if: 2+ direction changes AND oscillation is noticeable
        return directionChanges >= 2 && oscillationAmplitude > HUNTING_OSCILLATION_THRESHOLD
    }

    /**
     * Calculate confidence that hunting is actually occurring (0-1)
     */
    private fun calculateHuntingConfidence(): Float {
        if (focusHistoryBuffer.size < HUNTING_DETECTION_FRAMES) return 0f

        var hunts = 0
        val window = focusHistoryBuffer.takeLast(HUNTING_DETECTION_FRAMES)

        for (i in 1 until window.size) {
            val diff = abs(window[i].focusDistanceDiopters - window[i - 1].focusDistanceDiopters)
            if (diff > HUNTING_OSCILLATION_THRESHOLD * 0.6f) hunts++
        }

        return (hunts / HUNTING_DETECTION_FRAMES.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Calculate overall focus stability (0-1, where 1 = stable)
     */
    private fun calculateStabilityScore(): Float {
        if (focusHistoryBuffer.size < 3) return 0.5f

        val variance = calculateFocusVariance()
        val maxAcceptableVariance = 0.15f

        return (1f - (variance / maxAcceptableVariance).coerceAtMost(1f)) * 0.8f +
                (1f - calculateHuntingConfidence()) * 0.2f
    }

    /**
     * Calculate focus distance variance across recent frames
     */
    private fun calculateFocusVariance(): Float {
        if (focusHistoryBuffer.size < 2) return 0f

        val diopters = focusHistoryBuffer.map { it.focusDistanceDiopters }
        val mean = diopters.average().toFloat()

        val variance = diopters.map { (it - mean) * (it - mean) }.average().toFloat()
        return sqrt(variance)
    }

    /**
     * Calculate confidence that focus lock will succeed (0-1)
     */
    private fun calculateFocusLockConfidence(subjectConfidence: Float): Float {
        val stabilityBonus = calculateStabilityScore() * 0.4f
        val subjectBonus = subjectConfidence * 0.5f
        val huntingPenalty = calculateHuntingConfidence() * 0.3f

        return (stabilityBonus + subjectBonus - huntingPenalty)
            .coerceIn(0f, 1f)
    }

    /**
     * Predict hunting risk for next N frames (0-1, where 1 = high risk)
     */
    private fun predictHuntingRisk(sceneComplexity: Float): Float {
        val currentHuntingConfidence = calculateHuntingConfidence()
        val sceneRisk = if (sceneComplexity > SCENE_COMPLEXITY_THRESHOLD) 0.3f else 0f
        val momentum = if (consecutiveHuntingFrames > HUNTING_DETECTION_FRAMES) 0.4f else 0f

        return (currentHuntingConfidence * 0.5f + sceneRisk + momentum)
            .coerceIn(0f, 1f)
    }

    /**
     * Calculate optimal focus lock duration to avoid hunting
     */
    private fun calculateOptimalLockDuration(): Long {
        val complexity = focusHistoryBuffer.lastOrNull()?.sceneComplexity ?: 0.5f
        return when {
            complexity > 0.8f -> 800L   // Complex scenes: lock 800ms
            complexity > 0.6f -> 1200L  // Moderate: lock 1.2s
            else -> 1600L               // Simple scenes: lock 1.6s
        }
    }

    /**
     * Get detailed status report for logging/telemetry
     */
    data class FocusStabilityReport(
        val isHunting: Boolean,
        val huntingConfidence: Float,
        val stabilityScore: Float,
        val focusLockConfidence: Float,
        val shouldLockFocus: Boolean,
        val huntingRiskPrediction: Float,
        val recommendedLockDuration: Long,
        val focusVariance: Float,
        val subjectTrackingStability: Float
    ) {
        override fun toString(): String = buildString {
            append("FocusStability(")
            append("hunting=$isHunting, ")
            append("stability=%.2f, ".format(stabilityScore))
            append("lockConf=%.2f, ".format(focusLockConfidence))
            append("shouldLock=$shouldLockFocus, ")
            append("riskPred=%.2f)".format(huntingRiskPrediction))
        }
    }

    /**
     * Reset metrics (e.g., when camera switches or video restarts)
     */
    fun reset() {
        focusHistoryBuffer.clear()
        lastHuntingDetectionTime = 0L
        consecutiveHuntingFrames = 0
        // CP-F5: clear observe-only telemetry too.
        pumpCycleCount = 0
        reacquireEventCount = 0
        maxFollowJerk = 0f
        jerkPrevDiopters = Float.NaN
        jerkPrevTimestampMs = 0L
        jerkPrevVelocity = Float.NaN
        jerkPrevAcceleration = Float.NaN
    }

    /**
     * Get tracking stability indicator for UI/telemetry
     */
    fun getTrackingStabilityIndicator(): String = when {
        calculateHuntingConfidence() > 0.7f -> "HUNTING"
        calculateStabilityScore() > 0.8f -> "LOCKED"
        calculateStabilityScore() > 0.6f -> "STABLE"
        else -> "UNSTABLE"
    }
}

/** CP-F5 (s5-f04): immutable observe-only telemetry snapshot from AIFocusStabilityMetrics. */
data class AIFocusMetricsSnapshot(
    val pumpCycleCount: Int,
    val reacquireEventCount: Int,
    val maxFollowJerk: Float
)
