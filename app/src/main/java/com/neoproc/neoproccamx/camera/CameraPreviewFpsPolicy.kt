package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs
import kotlin.math.roundToInt

// ════════════════════════════════════════════════════════════════════════════════
// HIGH-END CAMERA PREVIEW FPS POLICY
// ════════════════════════════════════════════════════════════════════════════════
// Professional-grade frame rate management with cinema standard support,
// variable frame rate handling, and intelligent exposure budget allocation.
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Cinema-standard frame rates for professional video production.
 */
internal enum class CinemaFrameRate(val fps: Int, val description: String) {
    CINEMA_23_976(24, "23.976 fps (Film)"),
    CINEMA_24(24, "24 fps (True Cinema)"),
    CINEMA_25(25, "25 fps (PAL)"),
    CINEMA_29_97(30, "29.97 fps (NTSC)"),
    CINEMA_30(30, "30 fps (Digital)"),
    CINEMA_48(48, "48 fps (HFR Cinema)"),
    CINEMA_50(50, "50 fps (PAL HFR)"),
    CINEMA_59_94(60, "59.94 fps (NTSC HFR)"),
    CINEMA_60(60, "60 fps (Digital HFR)")
}

/**
 * Frame rate mode for different use cases.
 */
internal enum class FrameRateMode {
    /** Fixed frame rate for cinema production */
    FIXED_CINEMA,
    /** Variable frame rate with bounds */
    VARIABLE_BOUNDED,
    /** Auto-adjust based on scene and lighting */
    ADAPTIVE,
    /** Low-latency mode for real-time applications */
    LOW_LATENCY,
    /** High frame rate for slow motion capture */
    SLOW_MOTION
}

/**
 * Frame rate stability classification.
 */
internal enum class FrameRateStability {
    /** Rock-solid, no drops allowed (cinema) */
    LOCKED,
    /** Minor variance acceptable */
    STABLE,
    /** Some fluctuation acceptable */
    FLEXIBLE,
    /** Best-effort delivery */
    BEST_EFFORT
}

internal data class CameraPreviewFpsPlan(
    val requestedPreviewFps: Int,
    val resolvedRange: CameraIntRange?,
    val appliedPreviewFps: Int,
    val exposureBudgetFps: Int,
    val frameRateMode: FrameRateMode = FrameRateMode.ADAPTIVE,
    val stability: FrameRateStability = FrameRateStability.STABLE,
    val cinemaFrameRate: CinemaFrameRate? = null,
    val maxExposureTimeNs: Long = 0L,
    val allowVariableRange: Boolean = true,
    val dropFramePolicy: DropFramePolicy = DropFramePolicy.SKIP_OLDEST
)

/**
 * Policy for handling frame drops under load.
 */
internal enum class DropFramePolicy {
    /** Skip oldest frames to minimize latency */
    SKIP_OLDEST,
    /** Never skip, queue all frames */
    QUEUE_ALL,
    /** Skip frames evenly for smooth playback */
    TEMPORAL_SPREAD,
    /** Drop only B-frames if encoding */
    SMART_DROP
}

/**
 * Extended frame rate analysis result.
 */
internal data class FrameRateAnalysis(
    val optimalFps: Int,
    val canAchieveTarget: Boolean,
    val limitingFactor: FrameRateLimitingFactor,
    val recommendedMode: FrameRateMode,
    val maxSustainableFps: Int,
    val exposureCeilingNs: Long
)

internal enum class FrameRateLimitingFactor {
    NONE,
    HARDWARE_CAPABILITY,
    EXPOSURE_TIME,
    THERMAL,
    PROCESSING_LOAD,
    MEMORY_BANDWIDTH
}

internal object CameraPreviewFpsPolicy {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CINEMA CONSTANTS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val MIN_REALTIME_PREVIEW_FPS = 24
    private const val MILLI_FPS_SCALE = 1_000
    
    /** Cinema minimum - never drop below this for professional work */
    private const val CINEMA_FLOOR_FPS = 23
    
    /** Frame time tolerance for cinema lock (±2% variance) */
    private const val CINEMA_FRAME_TIME_TOLERANCE = 0.02f
    
    /** Nanoseconds per second for exposure calculations */
    private const val NS_PER_SECOND = 1_000_000_000L
    
    /** Standard 180° shutter angle exposure ceiling factor */
    private const val SHUTTER_ANGLE_180_FACTOR = 0.5f

    // ═══════════════════════════════════════════════════════════════════════════
    // CORE RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════════

    fun resolve(
        requestedFps: Int,
        availableRanges: List<CameraIntRange>
    ): CameraPreviewFpsPlan {
        val normalizedTarget = normalizedTargetPreviewFps(requestedFps)
        val unitScale = detectUnitScale(availableRanges)
        val scaledTarget = normalizedTarget * unitScale
        val resolvedRange = resolveRangeForScaledTarget(scaledTarget, availableRanges)
        val scaledAppliedPreviewFps = when {
            resolvedRange == null -> scaledTarget
            resolvedRange.lower > scaledTarget -> resolvedRange.lower
            resolvedRange.upper < scaledTarget -> resolvedRange.upper
            else -> scaledTarget
        }
        val appliedPreviewFps = (
            scaledAppliedPreviewFps.toDouble() / unitScale.toDouble()
            ).roundToInt().coerceAtLeast(MIN_REALTIME_PREVIEW_FPS)
        
        val cinemaFps = findMatchingCinemaFrameRate(appliedPreviewFps)
        val mode = determineFrameRateMode(appliedPreviewFps, resolvedRange, unitScale)
        val maxExposure = calculateMaxExposureForFps(appliedPreviewFps)
        
        return CameraPreviewFpsPlan(
            requestedPreviewFps = normalizedTarget,
            resolvedRange = resolvedRange,
            appliedPreviewFps = appliedPreviewFps,
            exposureBudgetFps = appliedPreviewFps,
            frameRateMode = mode,
            stability = if (cinemaFps != null) FrameRateStability.LOCKED else FrameRateStability.STABLE,
            cinemaFrameRate = cinemaFps,
            maxExposureTimeNs = maxExposure,
            allowVariableRange = resolvedRange?.let { it.lower != it.upper } ?: true,
            dropFramePolicy = if (cinemaFps != null) DropFramePolicy.TEMPORAL_SPREAD else DropFramePolicy.SKIP_OLDEST
        )
    }

    /**
     * Resolve FPS plan with cinema-specific requirements.
     */
    fun resolveForCinema(
        targetFrameRate: CinemaFrameRate,
        availableRanges: List<CameraIntRange>,
        requireLocked: Boolean = true
    ): CameraPreviewFpsPlan {
        val plan = resolve(targetFrameRate.fps, availableRanges)
        
        return plan.copy(
            frameRateMode = FrameRateMode.FIXED_CINEMA,
            stability = if (requireLocked) FrameRateStability.LOCKED else FrameRateStability.STABLE,
            cinemaFrameRate = targetFrameRate,
            dropFramePolicy = DropFramePolicy.TEMPORAL_SPREAD
        )
    }

    /**
     * Resolve FPS plan for slow motion capture.
     */
    fun resolveForSlowMotion(
        targetFps: Int,
        availableRanges: List<CameraIntRange>
    ): CameraPreviewFpsPlan {
        val normalizedTarget = targetFps.coerceIn(60, 60)
        val plan = resolve(normalizedTarget, availableRanges)
        
        return plan.copy(
            frameRateMode = FrameRateMode.SLOW_MOTION,
            stability = FrameRateStability.FLEXIBLE,
            cinemaFrameRate = CinemaFrameRate.CINEMA_60,
            dropFramePolicy = DropFramePolicy.QUEUE_ALL
        )
    }

    fun resolveRange(
        requestedFps: Int,
        availableRanges: List<CameraIntRange>
    ): CameraIntRange? {
        val normalizedTarget = normalizedTargetPreviewFps(requestedFps)
        val scaledTarget = normalizedTarget * detectUnitScale(availableRanges)
        return resolveRangeForScaledTarget(scaledTarget, availableRanges)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FRAME RATE ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Analyze achievable frame rates with limiting factors.
     */
    fun analyzeFrameRateCapability(
        targetFps: Int,
        availableRanges: List<CameraIntRange>,
        currentExposureNs: Long,
        thermalFactor: Float = 1.0f
    ): FrameRateAnalysis {
        val unitScale = detectUnitScale(availableRanges)
        val maxHardwareFps = availableRanges.maxOfOrNull { it.upper / unitScale } ?: 25
        
        // Calculate exposure-limited FPS
        val frameTimeNs = NS_PER_SECOND / targetFps
        val exposureLimitedFps = if (currentExposureNs > 0) {
            (NS_PER_SECOND / currentExposureNs).toInt().coerceAtMost(targetFps)
        } else {
            targetFps
        }
        
        // Apply thermal throttling
        val thermalLimitedFps = (maxHardwareFps * thermalFactor).toInt()
        
        // Find the actual limiting factor
        val (limitingFactor, achievableFps) = when {
            exposureLimitedFps < targetFps -> 
                FrameRateLimitingFactor.EXPOSURE_TIME to exposureLimitedFps
            thermalLimitedFps < targetFps -> 
                FrameRateLimitingFactor.THERMAL to thermalLimitedFps
            maxHardwareFps < targetFps -> 
                FrameRateLimitingFactor.HARDWARE_CAPABILITY to maxHardwareFps
            else -> 
                FrameRateLimitingFactor.NONE to targetFps
        }
        
        val canAchieve = achievableFps >= targetFps
        val mode = when {
            canAchieve && findMatchingCinemaFrameRate(targetFps) != null -> FrameRateMode.FIXED_CINEMA
            targetFps > 60 -> FrameRateMode.SLOW_MOTION
            canAchieve -> FrameRateMode.ADAPTIVE
            else -> FrameRateMode.VARIABLE_BOUNDED
        }
        
        return FrameRateAnalysis(
            optimalFps = achievableFps,
            canAchieveTarget = canAchieve,
            limitingFactor = limitingFactor,
            recommendedMode = mode,
            maxSustainableFps = minOf(maxHardwareFps, thermalLimitedFps),
            exposureCeilingNs = frameTimeNs
        )
    }

    /**
     * Calculate safe exposure range for given FPS target.
     */
    fun calculateExposureBudget(
        targetFps: Int,
        shutterAngleDegrees: Float = 180f
    ): Pair<Long, Long> {
        val frameTimeNs = NS_PER_SECOND / targetFps
        val shutterFactor = (shutterAngleDegrees / 360f).coerceIn(0.01f, 1f)
        val maxExposure = (frameTimeNs * shutterFactor).toLong()
        val minExposure = (maxExposure * 0.01).toLong().coerceAtLeast(1000L) // Min ~1µs
        return minExposure to maxExposure
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VARIABLE FRAME RATE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find optimal variable range for adaptive recording.
     */
    fun resolveVariableRange(
        minFps: Int,
        maxFps: Int,
        availableRanges: List<CameraIntRange>
    ): CameraIntRange? {
        val unitScale = detectUnitScale(availableRanges)
        val scaledMin = minFps * unitScale
        val scaledMax = maxFps * unitScale
        
        return availableRanges
            .filter { it.lower <= scaledMin && it.upper >= scaledMax }
            .minByOrNull { span(it) }
            ?: availableRanges
                .filter { it.lower <= scaledMax && it.upper >= scaledMin }
                .maxByOrNull { minOf(it.upper, scaledMax) - maxOf(it.lower, scaledMin) }
    }

    /**
     * Determine if frame rate change should be smooth or immediate.
     */
    fun shouldSmoothTransition(
        currentFps: Int,
        targetFps: Int,
        isRecording: Boolean
    ): Boolean {
        val delta = abs(currentFps - targetFps)
        return when {
            !isRecording -> false // Immediate transitions when not recording
            delta <= 5 -> true // Smooth for small changes
            delta <= 15 -> isRecording // Smooth only during recording for medium changes
            else -> false // Large jumps should be immediate
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun resolveRangeForScaledTarget(
        scaledTarget: Int,
        availableRanges: List<CameraIntRange>
    ): CameraIntRange? {
        return availableRanges.minWithOrNull(
            compareBy<CameraIntRange> { rangePriority(it, scaledTarget) }
                .thenBy { abs(it.lower - scaledTarget) }
                .thenBy { span(it) }
                .thenBy { abs(it.upper - scaledTarget) }
                .thenByDescending { it.lower }
        )
    }

    private fun normalizedTargetPreviewFps(requestedFps: Int): Int {
        val targetFloor = HighEndQualityPolicy.previewFpsFloor(
            requestedFps = requestedFps,
            recording = false
        )
        // Record-then-develop LIGHT lane: let a 26..30 capture request survive so the camera AE cadence can
        // match the lifted 30 fps full-sensor encoder target instead of snapping back to 25 (which would let
        // RecordingCameraFpsAlignmentPolicy pull the encoder back down). The device AE range still caps to
        // sensor truth. Reversible / default-OFF => the standard maxPreviewFps snap is unchanged.
        val ceiling = if (com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag.enabled && requestedFps in 26..30) {
            requestedFps
        } else {
            HighEndQualityPolicy.maxPreviewFps(requestedFps)
        }
        return requestedFps
            .coerceAtLeast(targetFloor)
            .coerceAtMost(ceiling)
    }

    private fun detectUnitScale(availableRanges: List<CameraIntRange>): Int {
        val maxUpper = availableRanges.maxOfOrNull { it.upper } ?: return 1
        return if (maxUpper >= MILLI_FPS_SCALE) MILLI_FPS_SCALE else 1
    }

    private fun rangePriority(
        range: CameraIntRange,
        targetFps: Int
    ): Int = when {
        range.lower == targetFps && range.upper == targetFps -> 0
        range.lower == targetFps && range.upper > targetFps -> 1
        range.lower <= targetFps && range.upper >= targetFps -> 2
        range.upper > targetFps -> 3
        else -> 4
    }

    private fun span(range: CameraIntRange): Int = (range.upper - range.lower).coerceAtLeast(0)
    
    private fun findMatchingCinemaFrameRate(fps: Int): CinemaFrameRate? {
        return CinemaFrameRate.entries.find { abs(it.fps - fps) <= 1 }
    }
    
    private fun determineFrameRateMode(
        fps: Int,
        range: CameraIntRange?,
        unitScale: Int
    ): FrameRateMode {
        return when {
            fps > 60 -> FrameRateMode.SLOW_MOTION
            range != null && range.lower == range.upper -> FrameRateMode.FIXED_CINEMA
            findMatchingCinemaFrameRate(fps) != null -> FrameRateMode.FIXED_CINEMA
            fps >= 60 -> FrameRateMode.LOW_LATENCY
            else -> FrameRateMode.ADAPTIVE
        }
    }
    
    private fun calculateMaxExposureForFps(fps: Int): Long {
        val frameTimeNs = NS_PER_SECOND / fps
        return (frameTimeNs * SHUTTER_ANGLE_180_FACTOR).toLong()
    }
}
