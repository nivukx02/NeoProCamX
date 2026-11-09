package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

/**
 * Frame rate plan for synchronized preview and recording.
 * 
 * Key invariant: preview and encoder should run at the same FPS when possible
 * to avoid frame pacing issues (stuttering, duplicate frames, or dropped frames).
 */
internal data class RecordingPreviewFrameRatePlan(
    val previewFps: Int,
    val encoderFps: Int,
    /** True if preview and encoder are synchronized (same FPS) */
    val isSynchronized: Boolean = previewFps == encoderFps
)

/**
 * Policy for resolving preview and encoder frame rates.
 * 
 * Goals:
 * 1. Synchronize preview and encoder FPS when possible (eliminates frame pacing issues)
 * 2. Maintain real-time preview (never drop below MIN_REALTIME_PREVIEW_FPS)
 * 3. Cap encoder FPS for ultra-high-res to prevent thermal throttling
 * 
 * The key insight is that preview-encoder mismatch causes:
 * - Stuttering when preview FPS > encoder FPS (frames wait for encoder)
 * - Duplicate frames when encoder FPS > preview FPS (encoder starves)
 * 
 * By synchronizing FPS, both paths use the same frame timing, eliminating jitter.
 */
internal object RecordingPreviewFrameRatePolicy {
    private const val MIN_REALTIME_PREVIEW_FPS = 24
    // 6K/8K removed from the product; "ultra high-res" tier is now 5K.
    private const val ULTRA_HIGH_RES_ENCODER_WIDTH = 5120
    private const val MAX_STANDARD_RECORDING_FPS = 60
    private const val SLOW_MOTION_FPS_THRESHOLD = 61
    private const val MAX_SLOW_MOTION_FPS = 60

    // For cinema quality, prefer native cinema/broadcast cadences and do not snap user-visible settings to 30fps.
    private val CINEMA_FPS_TARGETS = listOf(24, 25, 48, 50, 60)
    private val STANDARD_RECORDING_FPS_TARGETS = listOf(24, 25)

    // Record-then-develop LIGHT lane: full-sensor pixel gate + lifted cadence (mirrors ProcessedGpuRecordFpsPolicy).
    // Pixel-gated (not width) so DCI-4K 4096x2160 (8.85MP) stays 25 — only true full-sensor 4096x3072 (>=12MP) lifts.
    private const val LIGHT_RECORD_FULL_SENSOR_PIXELS = 12_000_000L
    private const val LIGHT_RECORD_FULL_SENSOR_FPS = 30

    fun resolve(
        selection: ResolvedRecordingSelection?,
        requestedFps: Int
    ): RecordingPreviewFrameRatePlan {
        val slowMotionRequest = isSlowMotionRequest(requestedFps)
        val normalizedRequestedFps = requestedFps
            .coerceAtLeast(MIN_REALTIME_PREVIEW_FPS)
            .let { fps ->
                if (slowMotionRequest) {
                    fps.coerceAtMost(MAX_SLOW_MOTION_FPS)
                } else {
                    fps.coerceAtMost(HighEndQualityPolicy.maxPreviewFps(60))
                }
            }
        val standardizedRequestedFps = if (slowMotionRequest) {
            normalizedRequestedFps
        } else {
            resolveStandardRecordingFps(normalizedRequestedFps)
        }
        val encoderFps = resolveEncoderFps(selection, standardizedRequestedFps)
        
        // CRITICAL: Synchronize preview FPS with encoder FPS for smooth recording
        // This eliminates frame pacing issues where preview and encoder fight for frames
        val synchronizedFps = encoderFps.coerceAtLeast(MIN_REALTIME_PREVIEW_FPS)
        
        return RecordingPreviewFrameRatePlan(
            previewFps = synchronizedFps,
            encoderFps = encoderFps,
            isSynchronized = true
        )
    }
    
    /**
     * Snap FPS to nearest cinema-standard value for consistent frame timing.
     * This avoids interpolation artifacts and ensures consistent motion blur.
     */
    fun snapToCinemaFps(requestedFps: Int): Int {
        return CINEMA_FPS_TARGETS.minByOrNull { kotlin.math.abs(it - requestedFps) }
            ?: requestedFps
    }

    fun snapToSupportedCinemaFps(
        requestedFps: Int,
        minFps: Int,
        maxFps: Int
    ): Int {
        val floor = minFps.coerceAtLeast(MIN_REALTIME_PREVIEW_FPS)
        val ceiling = maxFps.coerceAtLeast(floor)
        val clampedRequested = requestedFps.coerceIn(floor, ceiling)
        if (!HighEndQualityPolicy.enabled) return clampedRequested
        val candidates = CINEMA_FPS_TARGETS.filter { it in floor..ceiling }
        return candidates.minByOrNull { kotlin.math.abs(it - clampedRequested) } ?: clampedRequested
    }

    fun resolveEncoderFps(
        selection: ResolvedRecordingSelection?,
        requestedFps: Int
    ): Int {
        val normalizedSizes = listOfNotNull(
            selection?.requestedSize,
            selection?.sourceSize,
            selection?.size
        ).map(RecordingSelectionPolicy::normalizeForOrientation)
        // Record-then-develop LIGHT lane (reversible, default-OFF): lift the full-sensor encoder target to
        // 30 fps. The live elite grade that forced the cinema cap runs OFFLINE, so full-sensor can attempt a
        // higher cadence. Device-gated: the sensor AE ceiling still governs the ACHIEVED rate (vivo may
        // hard-wall ~25); AlwaysSaveAtAchievedFpsFlag saves at whatever is achieved. resolve()'s
        // synchronizedFps carries this to the preview cadence too, so preview + encoder stay in lockstep.
        // Flag OFF => unchanged width-based cap below (the pure resolveEncoderFpsForNormalizedWidth is
        // untouched, so its golden tests stay green).
        if (com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag.enabled) {
            val maxPixels = normalizedSizes.maxOfOrNull { it.width.toLong() * it.height.toLong() } ?: 0L
            if (maxPixels >= LIGHT_RECORD_FULL_SENSOR_PIXELS) {
                // Full-sensor constant-fps (reversible, default ON): pin full-sensor to a constant 25 instead
                // of the LIGHT-lane 30 lift so preview + encoder hold a steady cadence. Flag OFF ⇒ 30 lift
                // (byte-identical). Keeps preview/encoder in lockstep via resolve()'s synchronizedFps.
                return FullSensorConstantFpsPolicy.lightLaneFullSensorFps(LIGHT_RECORD_FULL_SENSOR_FPS)
            }
        }
        val normalizedMaxWidth = normalizedSizes.maxOfOrNull { it.width }
        return resolveEncoderFpsForNormalizedWidth(normalizedMaxWidth, requestedFps)
    }

    fun resolveEncoderFpsForNormalizedWidth(
        normalizedMaxWidth: Int?,
        requestedFps: Int
    ): Int {
        val slowMotionRequest = isSlowMotionRequest(requestedFps)
        val normalizedRequested = requestedFps
            .coerceAtLeast(MIN_REALTIME_PREVIEW_FPS)
            .let { fps ->
                if (slowMotionRequest) {
                    fps.coerceAtMost(MAX_SLOW_MOTION_FPS)
                } else {
                    fps.coerceAtMost(HighEndQualityPolicy.maxPreviewFps(60))
                }
            }
        val normalizedRequestedFps = if (slowMotionRequest) {
            normalizedRequested
        } else {
            resolveStandardRecordingFps(normalizedRequested)
        }
        val capped = when {
            normalizedMaxWidth != null && normalizedMaxWidth >= ULTRA_HIGH_RES_ENCODER_WIDTH ->
                normalizedRequestedFps.coerceAtMost(25)
            normalizedMaxWidth != null && normalizedMaxWidth >= 3840 ->
                normalizedRequestedFps.coerceAtMost(25)
            else -> normalizedRequestedFps
        }.coerceAtLeast(MIN_REALTIME_PREVIEW_FPS)
        val clamped = if (slowMotionRequest) {
            capped
        } else {
            capped.coerceAtMost(HighEndQualityPolicy.maxPreviewFps(60))
        }
        return if (slowMotionRequest) {
            clamped
        } else {
            clamped.coerceAtMost(MAX_STANDARD_RECORDING_FPS)
        }
    }
    
    /**
     * Calculate ideal frame interval for synchronized preview/encoder pipeline.
     * Returns frame interval in nanoseconds.
     */
    fun frameIntervalNs(fps: Int): Long {
        return 1_000_000_000L / fps.coerceAtLeast(1)
    }

    private fun isSlowMotionRequest(requestedFps: Int): Boolean =
        requestedFps >= SLOW_MOTION_FPS_THRESHOLD

    private fun resolveStandardRecordingFps(requestedFps: Int): Int {
        val fpsCeiling = if (HighEndQualityPolicy.enabled) {
            MAX_STANDARD_RECORDING_FPS
        } else {
            30
        }
        val clamped = requestedFps.coerceIn(MIN_REALTIME_PREVIEW_FPS, fpsCeiling)
        val targets = if (HighEndQualityPolicy.enabled) {
            CINEMA_FPS_TARGETS
        } else {
            STANDARD_RECORDING_FPS_TARGETS
        }
        return targets.minByOrNull { kotlin.math.abs(it - clamped) } ?: clamped
    }
}
