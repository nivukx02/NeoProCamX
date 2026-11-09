package com.neoproc.neoproccamx.pipeline

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * FRAME IMPORT ROUTING STATE
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Tracks frame processing performance for adaptive load management.
 * Used to maintain smooth preview even under heavy GPU/CPU load.
 */
internal data class FrameImportRoutingState(
    val dedicatedHardwareImportEnabled: Boolean = false,
    val dedicatedHardwareImportEnabledAtElapsedMs: Long = 0L,
    val lastHardwareImportElapsedMs: Long = 0L,
    val consecutiveImportSuccesses: Int = 0,
    val consecutiveImportFailures: Int = 0,
    val adaptiveFrameSkipCount: Int = 0,
    val lastFrameProcessTimeMs: Long = 0L,
    val isHighLoadMode: Boolean = false,
    /** Rolling average of frame process times for smooth adaptation */
    val avgFrameProcessTimeMs: Long = 0L,
    /** Peak process time with decay, used to detect burst jank patterns */
    val peakFrameProcessTimeMs: Long = 0L,
    /** Consecutive frames above critical load threshold */
    val consecutiveCriticalFrames: Int = 0,
    /** Consecutive frames above the high-load threshold */
    val consecutiveHighLoadFrames: Int = 0,
    /** Consecutive frames inside the low-load recovery band */
    val consecutiveLowLoadFrames: Int = 0,
    /** Persistent overload memory to avoid aggressive oscillation */
    val sustainedOverloadScore: Int = 0,
    /** Severe mode enables stronger skip and stride behavior */
    val severeLoadMode: Boolean = false,
    /** Total frames processed in current session */
    val totalFramesProcessed: Long = 0L,
    /** Total frames skipped in current session */
    val totalFramesSkipped: Long = 0L
)

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * FRAME IMPORT ROUTING POLICY - HIGH PERFORMANCE FRAME MANAGEMENT
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Controls when frames are processed vs skipped to maintain smooth preview.
 * 
 * Key optimizations:
 * - Exponential moving average for smooth load detection
 * - Hysteresis to prevent thrashing between load states
 * - Adaptive stride multiplier for AI processing
 * - Graceful degradation under extreme load
 */
internal object FrameImportRoutingPolicy {
    const val DEDICATED_IMPORT_GRACE_MS = 400L
    const val HARDWARE_IMPORT_STALL_FALLBACK_MS = 600L
    
    // ══════════════════════════════════════════════════════════════════════════
    // ADAPTIVE LOAD MANAGEMENT - OPTIMIZED THRESHOLDS FOR SMOOTH PREVIEW
    // ══════════════════════════════════════════════════════════════════════════
    
    /** High load threshold - above this, start skipping frames (UPGRADED: 25ms→24ms) */
    private const val HIGH_LOAD_THRESHOLD_MS = 22L  // ~45 FPS boundary (more lenient)
    
    /** Critical load threshold - aggressive frame skipping (UPGRADED: 26ms→30ms) */
    private const val CRITICAL_LOAD_THRESHOLD_MS = 30L  // ~33 FPS boundary (less aggressive)

    /** Severe load threshold - emergency frame pacing (UPGRADED: 36ms→40ms) */
    private const val SEVERE_LOAD_THRESHOLD_MS = 40L  // Only trigger at very low FPS
    
    /** Low load threshold - excellent performance, can reduce skipping (UPGRADED: 10ms→8ms) */
    private const val LOW_LOAD_THRESHOLD_MS = 8L   // ~125 FPS potential (more aggressive recovery)
    
    /** Maximum frames to skip (UPGRADED: 3→2) - less aggressive skipping */
    private const val MAX_ADAPTIVE_SKIP = 2  // Skip 1/3 max instead of 1/4
    
    /** Consecutive good frames needed before reducing skip count (UPGRADED: 8→6) */
    private const val RECOVERY_THRESHOLD = 6  // Faster recovery from overload
    
    /** Consecutive bad frames before increasing skip count (UPGRADED: 2→3) */
    private const val DEGRADATION_THRESHOLD = 3  // More tolerance before degradation
    
    /** Smoothing factor for exponential moving average (UPGRADED: 0.45→0.35) */
    private const val EMA_ALPHA = 0.35f  // More stable, less responsive to spikes

    fun setDedicatedHardwareImportEnabled(
        state: FrameImportRoutingState,
        enabled: Boolean,
        nowElapsedMs: Long
    ): FrameImportRoutingState {
        return if (enabled) {
            FrameImportRoutingState(
                dedicatedHardwareImportEnabled = true,
                dedicatedHardwareImportEnabledAtElapsedMs = nowElapsedMs,
                lastHardwareImportElapsedMs = 0L
            )
        } else {
            FrameImportRoutingState()
        }
    }

    fun recordHardwareImport(
        state: FrameImportRoutingState,
        nowElapsedMs: Long
    ): FrameImportRoutingState {
        if (!state.dedicatedHardwareImportEnabled) return state
        return state.copy(lastHardwareImportElapsedMs = nowElapsedMs)
    }

    fun shouldImportAnalysisFrame(
        state: FrameImportRoutingState,
        nowElapsedMs: Long,
        observedHardwareImportElapsedMs: Long = state.lastHardwareImportElapsedMs
    ): Boolean {
        if (!state.dedicatedHardwareImportEnabled) return true
        val lastHardwareImport = observedHardwareImportElapsedMs
        if (lastHardwareImport > 0L) {
            return nowElapsedMs - lastHardwareImport > HARDWARE_IMPORT_STALL_FALLBACK_MS
        }
        return nowElapsedMs - state.dedicatedHardwareImportEnabledAtElapsedMs <= DEDICATED_IMPORT_GRACE_MS
    }
    
    /**
     * Adaptive load management using exponential moving average.
     * 
     * This approach provides:
     * - Smooth transitions between load states (no thrashing)
     * - Fast response to sudden load spikes
     * - Gradual recovery when load decreases
     * 
     * @return Updated state and whether to process this frame
     */
    fun evaluateFrameLoad(
        state: FrameImportRoutingState,
        frameProcessTimeMs: Long,
        frameSequence: Long
    ): Pair<FrameImportRoutingState, Boolean> {
        val clampedProcessTimeMs = frameProcessTimeMs.coerceAtLeast(0L)

        // Calculate exponential moving average for smooth load detection
        val newAvg = if (state.totalFramesProcessed == 0L) {
            clampedProcessTimeMs
        } else {
            ((1 - EMA_ALPHA) * state.avgFrameProcessTimeMs + EMA_ALPHA * clampedProcessTimeMs).toLong()
        }

        val decayedPeak = (state.peakFrameProcessTimeMs * 9L) / 10L
        val newPeak = maxOf(decayedPeak, clampedProcessTimeMs)

        // Determine load level using both averaged and instantaneous timing signals.
        val isCriticalLoad =
            newAvg >= CRITICAL_LOAD_THRESHOLD_MS ||
                clampedProcessTimeMs >= CRITICAL_LOAD_THRESHOLD_MS ||
                (clampedProcessTimeMs > 13L && newAvg > 26L)
        val isSevereLoad =
            newAvg >= SEVERE_LOAD_THRESHOLD_MS ||
                clampedProcessTimeMs >= SEVERE_LOAD_THRESHOLD_MS ||
                newPeak >= (SEVERE_LOAD_THRESHOLD_MS + 6L)
        val isHighLoad =
            isCriticalLoad ||
                newAvg >= HIGH_LOAD_THRESHOLD_MS ||
                clampedProcessTimeMs >= HIGH_LOAD_THRESHOLD_MS ||
                state.sustainedOverloadScore >= 12
        val isLowLoad =
            newAvg <= LOW_LOAD_THRESHOLD_MS &&
                clampedProcessTimeMs <= HIGH_LOAD_THRESHOLD_MS / 2L &&
                state.sustainedOverloadScore <= 3

        val newCriticalFrames = if (isCriticalLoad) {
            state.consecutiveCriticalFrames + 1
        } else {
            0
        }
        val newHighLoadFrames = if (isHighLoad) {
            state.consecutiveHighLoadFrames + 1
        } else {
            0
        }
        val newLowLoadFrames = if (isLowLoad) {
            state.consecutiveLowLoadFrames + 1
        } else {
            0
        }
        val overloadDelta = when {
            isSevereLoad -> 4
            isCriticalLoad -> 3
            isHighLoad -> 2
            isLowLoad -> -2
            else -> -1
        }
        val newOverloadScore = (state.sustainedOverloadScore + overloadDelta).coerceIn(0, 24)

        // Update consecutive counters
        val newSuccesses = if (!isHighLoad) {
            state.consecutiveImportSuccesses + 1
        } else 0

        val newFailures = if (isHighLoad) {
            state.consecutiveImportFailures + 1
        } else 0

        val severeMode = isSevereLoad || newCriticalFrames >= 2 || newOverloadScore >= 16

        // Determine adaptive skip count with hysteresis
        val newSkipCount = when {
            // Severe load: emergency pacing
            severeMode ->
                MAX_ADAPTIVE_SKIP
            // Critical load: aggressive skipping
            isCriticalLoad &&
                newHighLoadFrames >= DEGRADATION_THRESHOLD &&
                state.adaptiveFrameSkipCount < MAX_ADAPTIVE_SKIP ->
                (state.adaptiveFrameSkipCount + 1).coerceAtMost(MAX_ADAPTIVE_SKIP)
            // High load with sustained failures: increase skip
            isHighLoad &&
                newHighLoadFrames >= DEGRADATION_THRESHOLD &&
                (newFailures >= DEGRADATION_THRESHOLD || newOverloadScore >= 10) &&
                state.adaptiveFrameSkipCount < MAX_ADAPTIVE_SKIP ->
                state.adaptiveFrameSkipCount + 1
            // Low load with sustained good performance: decrease skip
            isLowLoad &&
                newLowLoadFrames >= RECOVERY_THRESHOLD &&
                newSuccesses >= RECOVERY_THRESHOLD &&
                state.adaptiveFrameSkipCount > 0 ->
                if (
                    newSuccesses >= RECOVERY_THRESHOLD * 2 &&
                    state.adaptiveFrameSkipCount > 1
                ) {
                    (state.adaptiveFrameSkipCount - 2).coerceAtLeast(0)
                } else {
                    state.adaptiveFrameSkipCount - 1
                }
            else -> state.adaptiveFrameSkipCount
        }

        // These are CPU/GPU analysis frames, not camera or encoder frames. Even strict
        // high-end mode must shed analysis work under overload to keep preview cadence.
        val shouldSkip = newSkipCount > 0 && (frameSequence % (newSkipCount + 1)) != 0L

        val newState = state.copy(
            adaptiveFrameSkipCount = newSkipCount,
            lastFrameProcessTimeMs = clampedProcessTimeMs,
            avgFrameProcessTimeMs = newAvg,
            peakFrameProcessTimeMs = newPeak,
            consecutiveCriticalFrames = newCriticalFrames,
            consecutiveHighLoadFrames = newHighLoadFrames,
            consecutiveLowLoadFrames = newLowLoadFrames,
            sustainedOverloadScore = newOverloadScore,
            severeLoadMode = severeMode,
            isHighLoadMode = isHighLoad || severeMode || newOverloadScore >= 8 || newFailures >= DEGRADATION_THRESHOLD,
            consecutiveImportSuccesses = newSuccesses,
            consecutiveImportFailures = newFailures,
            totalFramesProcessed = state.totalFramesProcessed + 1,
            totalFramesSkipped = if (shouldSkip) state.totalFramesSkipped + 1 else state.totalFramesSkipped
        )

        return newState to !shouldSkip
    }
    
    /**
     * Determines optimal AI stride multiplier based on current load.
     * Higher multiplier = less frequent AI processing = lower load.
     */
    fun getAdaptiveStrideMultiplier(state: FrameImportRoutingState): Int {
        return when {
            // Severe load: use strongest stride expansion
            state.severeLoadMode -> 5
            // Critical load: maximize stride
            state.avgFrameProcessTimeMs > CRITICAL_LOAD_THRESHOLD_MS -> 3
            state.sustainedOverloadScore >= 14 -> 3
            // High load: moderate stride increase
            state.isHighLoadMode -> 2
            state.adaptiveFrameSkipCount > 1 -> 2
            state.adaptiveFrameSkipCount > 0 -> 1
            else -> 1
        }
    }
    
    /**
     * Get current performance stats for diagnostics.
     */
    fun getPerformanceStats(state: FrameImportRoutingState): String {
        val skipRate = if (state.totalFramesProcessed > 0) {
            (state.totalFramesSkipped * 100.0 / state.totalFramesProcessed).toInt()
        } else 0
        return "avg=${state.avgFrameProcessTimeMs}ms peak=${state.peakFrameProcessTimeMs}ms skip=$skipRate% overload=${state.sustainedOverloadScore} mode=${if (state.severeLoadMode) "SEVERE" else if (state.isHighLoadMode) "HIGH" else "NORMAL"}"
    }
}
