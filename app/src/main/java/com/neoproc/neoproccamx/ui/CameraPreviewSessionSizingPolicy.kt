package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * CameraPreviewSessionSizingPolicy — cinema-oriented preview session size planner.
 *
 * Selects the optimal Camera2 preview surface size from the list of hardware-
 * supported sizes for the current recording configuration, quality policy, and
 * thermal constraints.
 *
 * Design principles:
 *  1. **Stable 1080p display** — preview is capped independently from recording output.
 *  2. **Aspect-ratio fidelity** — best-match to selected aspect ratio first.
 *  3. **Performance aware** — degrades gracefully under thermal throttling.
 *  4. **Cinema-first** — in high-end mode, prefers the highest quality match.
 *  5. **Hysteresis** — size changes require a meaningful delta to avoid jitter.
 */
object CameraPreviewSessionSizingPolicy {

    // ── Configuration ─────────────────────────────────────────────────────────

    private const val LIVE_DISPLAY_MAX_LONG_EDGE = 1920
    private const val LIVE_DISPLAY_MAX_AREA = 1920 * 1080
    private const val HEAT_EFFICIENT_MAX_LONG_EDGE = 1280
    private const val HEAT_EFFICIENT_MAX_AREA = 1280 * 960

    /** Target preview size for the primary viewfinder surface. */
    var targetPreviewSize: Size = Size(LIVE_DISPLAY_MAX_LONG_EDGE, 1080)

    /** Maximum live display size. Recording resolution is selected independently. */
    var maxPreviewSize: Size = Size(LIVE_DISPLAY_MAX_LONG_EDGE, 1080)

    /** Minimum allowable preview size — anything smaller is not useful for cinema monitoring. */
    var minPreviewSize: Size = Size(640, 360)

    /** Whether full sensor readout is requested (bypasses size selection). */
    var useFullSensorReadout: Boolean = false

    /** Size-change hysteresis threshold — changes smaller than this are ignored. */
    private const val HYSTERESIS_AREA_RATIO_THRESHOLD = 0.10f  // 10% area change required
    private const val HIGH_RES_VIDEO_PREVIEW_MAX_LONG_EDGE = 3840
    private const val HIGH_RES_VIDEO_PREVIEW_MAX_AREA = 3840 * 2160
    private const val ULTRA_HIGH_RES_VIDEO_PREVIEW_MAX_LONG_EDGE = 3200
    private const val ULTRA_HIGH_RES_VIDEO_PREVIEW_MAX_AREA = 3200 * 1800
    private const val HIGH_RES_ANALYSIS_MAX_LONG_EDGE = 1920
    private const val ULTRA_HIGH_RES_ANALYSIS_MAX_LONG_EDGE = 1920
    private const val SOURCE_MATCH_ASPECT_TOLERANCE = 0.02f

    // ── Optimal Size Calculation ──────────────────────────────────────────────

    /**
     * Calculates the optimal preview size from a list of hardware-supported sizes.
     *
     * Algorithm:
     *  1. Filter to the valid min/max range.
     *  2. Score each candidate by (aspect ratio error, area).
     *  3. In high-quality mode: return the highest-area best-aspect-match.
     *  4. In performance mode: return the candidate closest to [targetPreviewSize].
     *
     * @param supportedSizes  Camera2-reported OutputConfiguration sizes.
     * @param targetFps       Desired frame rate (used to cap max size at high fps).
     * @param highQuality     Whether to prefer maximum resolution.
     * @param targetAspect    Desired aspect ratio (default 16:9).
     */
    fun calculateOptimalPreviewSize(
        supportedSizes: List<Size>,
        targetFps: Int = 25,
        highQuality: Boolean = HighEndQualityPolicy.strictHighEndOnly,
        targetAspect: Float = 16f / 9f
    ): Size {
        if (supportedSizes.isEmpty()) return targetPreviewSize

        // Apply fps-dependent max long edge (high fps → lower resolution limit)
        val fpsMaxLongEdge = when {
            targetFps >= 120 -> 1280
            targetFps >= 60  -> 1920
            else             -> LIVE_DISPLAY_MAX_LONG_EDGE
        }
        val effectiveMax = Size(
            minOf(maxPreviewSize.width, fpsMaxLongEdge),
            minOf(maxPreviewSize.height, fpsMaxLongEdge * 9 / 16)
        )

        val validSizes = supportedSizes.filter { size ->
            size.width >= minPreviewSize.width &&
                size.height >= minPreviewSize.height &&
                size.width <= effectiveMax.width &&
                size.height <= effectiveMax.height
        }
        if (validSizes.isEmpty()) return closestFromAll(supportedSizes, targetAspect)

        // Score each size: primary key = aspect ratio error, secondary = area (desc)
        val ranked = validSizes.sortedWith(
            compareBy<Size> { size ->
                abs(size.width.toFloat() / size.height.coerceAtLeast(1).toFloat() - targetAspect)
            }.thenByDescending { size ->
                size.width.toLong() * size.height.toLong()
            }
        )

        return if (highQuality) {
            // Best aspect match at largest area
            ranked.firstOrNull() ?: targetPreviewSize
        } else {
            // Performance: closest to target size by Euclidean area distance
            val targetArea = targetPreviewSize.width.toLong() * targetPreviewSize.height.toLong()
            ranked.minByOrNull { size ->
                val area = size.width.toLong() * size.height.toLong()
                abs(area - targetArea)
            } ?: targetPreviewSize
        }
    }

    /**
     * Determines if the current preview size requires adjustment given
     * the active performance mode and target frame rate.
     *
     * Uses hysteresis to prevent constant re-configuration: only returns
     * true if the area difference exceeds [HYSTERESIS_AREA_RATIO_THRESHOLD].
     *
     * @param currentSize    Currently bound preview surface size.
     * @param targetFps      Target frame rate.
     * @param performanceMode Whether the pipeline is in degraded performance mode.
     */
    fun needsSizeAdjustment(
        currentSize: Size,
        targetFps: Int,
        performanceMode: Boolean
    ): Boolean {
        val optimalSize = calculateOptimalPreviewSize(
            supportedSizes = listOf(currentSize, targetPreviewSize),
            targetFps = targetFps,
            highQuality = !performanceMode
        )
        val currentArea = currentSize.width.toLong() * currentSize.height.toLong()
        val optimalArea = optimalSize.width.toLong() * optimalSize.height.toLong()
        if (currentArea == 0L) return true
        val ratio = abs(currentArea - optimalArea).toFloat() / currentArea.toFloat()
        return ratio > HYSTERESIS_AREA_RATIO_THRESHOLD
    }

    // ── Preview Limits ────────────────────────────────────────────────────────

    /**
     * Data class representing resolved preview resolution limits.
     *
     * @param maxLongEdge  Maximum long edge of the preview surface.
     * @param maxArea      Maximum pixel count of the preview surface.
     * @param analysisMaxLongEdge Maximum long edge for AI analysis streams.
     */
    data class PreviewLimits(
        val maxLongEdge: Int,
        val maxArea: Int,
        val analysisMaxLongEdge: Int = maxLongEdge
    ) {
        val maxSize: Size
            get() {
                val shortEdge = (maxLongEdge * 9 / 16).coerceAtLeast(1)
                return Size(maxLongEdge, shortEdge)
            }

        val megapixels: Float
            get() = maxArea / 1_000_000f
    }

    /**
     * A live preview transport cap resolved for the transport's real aspect ratio.
     *
     * Keeping the edge and area in one immutable value prevents callers from publishing a mixed
     * generation (for example, a 4:3 long edge paired with a 16:9 area). Such a mismatch can select
     * a smaller Camera2 stream and unnecessarily retire a healthy preview surface.
     */
    data class RuntimePreviewCap(
        val maxLongEdge: Int,
        val maxArea: Int
    ) {
        fun differsFrom(currentMaxLongEdge: Int?, currentMaxArea: Int?): Boolean =
            currentMaxLongEdge != maxLongEdge || currentMaxArea != maxArea
    }

    /** Resolves an adaptive preview cap without assuming that the transport is 16:9. */
    fun resolveAspectAwareRuntimeCap(
        maxLongEdge: Int,
        targetAspect: Float?
    ): RuntimePreviewCap {
        val resolvedLongEdge = maxLongEdge.coerceAtLeast(2)
        return RuntimePreviewCap(
            maxLongEdge = resolvedLongEdge,
            maxArea = resolveAspectAwarePreviewMaxArea(
                maxLongEdge = resolvedLongEdge,
                targetAspect = targetAspect
            )
        )
    }

    /**
     * Resolves preview limits from multiple constraint sources.
     *
     * Priority (most restrictive wins):
     *  1. Live display maximum (1080p)
     *  2. Thermal preview guard
     *  3. Performance stability guard
     *  4. Standard default (1080p)
     *
     * @param videoMode               Whether video mode is active.
     * @param strictHighEnd           Whether high-end pipeline is active.
     * @param videoSourceLongEdge     Long edge of the recording output for high-load analysis caps.
     * @param thermalPreviewMaxLongEdge Optional thermal cap.
     * @param thermalPreviewMaxArea    Optional thermal area cap.
     * @param performancePreviewMaxLongEdge Optional performance stability cap.
     * @param performancePreviewMaxArea     Optional performance area cap.
     */
    fun resolvePreviewLimits(
        videoMode: Boolean,
        strictHighEnd: Boolean,
        videoSourceLongEdge: Int,
        thermalPreviewMaxLongEdge: Int? = null,
        thermalPreviewMaxArea: Int? = null,
        performancePreviewMaxLongEdge: Int? = null,
        performancePreviewMaxArea: Int? = null,
        targetAspect: Float? = null,
        displayMaxLongEdge: Int = LIVE_DISPLAY_MAX_LONG_EDGE,
        displayMaxArea: Int = LIVE_DISPLAY_MAX_AREA,
        heatEfficientPreview: Boolean = false
    ): PreviewLimits {
        val absoluteMaxLongEdge = if (heatEfficientPreview) {
            minOf(displayMaxLongEdge.coerceAtLeast(1), HEAT_EFFICIENT_MAX_LONG_EDGE)
        } else {
            displayMaxLongEdge.coerceAtLeast(1)
        }
        val absoluteMaxArea = if (heatEfficientPreview) {
            minOf(displayMaxArea.coerceAtLeast(1), HEAT_EFFICIENT_MAX_AREA)
        } else {
            displayMaxArea.coerceAtLeast(1)
        }
        val proactiveVideoPreviewMaxLongEdge = when {
            !videoMode -> null
            videoSourceLongEdge >= 5120 -> ULTRA_HIGH_RES_VIDEO_PREVIEW_MAX_LONG_EDGE
            videoSourceLongEdge >= 4096 -> HIGH_RES_VIDEO_PREVIEW_MAX_LONG_EDGE
            else -> null
        }
        val proactiveVideoPreviewMaxArea = when {
            !videoMode -> null
            videoSourceLongEdge >= 5120 ->
                aspectAwareVideoPreviewMaxArea(
                    maxLongEdge = ULTRA_HIGH_RES_VIDEO_PREVIEW_MAX_LONG_EDGE,
                    targetAspect = targetAspect,
                    fallbackArea = ULTRA_HIGH_RES_VIDEO_PREVIEW_MAX_AREA
                )
            videoSourceLongEdge >= 4096 ->
                aspectAwareVideoPreviewMaxArea(
                    maxLongEdge = HIGH_RES_VIDEO_PREVIEW_MAX_LONG_EDGE,
                    targetAspect = targetAspect,
                    fallbackArea = HIGH_RES_VIDEO_PREVIEW_MAX_AREA
                )
            else -> null
        }

        val resolvedMaxLongEdge = listOfNotNull(
            absoluteMaxLongEdge,
            thermalPreviewMaxLongEdge,
            performancePreviewMaxLongEdge,
            proactiveVideoPreviewMaxLongEdge
        ).minOrNull() ?: absoluteMaxLongEdge

        val resolvedMaxArea = listOfNotNull(
            absoluteMaxArea,
            thermalPreviewMaxArea,
            performancePreviewMaxArea,
            proactiveVideoPreviewMaxArea
        ).minOrNull() ?: absoluteMaxArea

        // Resolve analysis cap proportionally
        val analysisMax = when {
            videoMode && videoSourceLongEdge >= 5120 -> ULTRA_HIGH_RES_ANALYSIS_MAX_LONG_EDGE
            videoMode && videoSourceLongEdge >= 4096 -> HIGH_RES_ANALYSIS_MAX_LONG_EDGE
            strictHighEnd && resolvedMaxLongEdge >= 3840 -> 1280
            strictHighEnd -> minOf(resolvedMaxLongEdge, 1280)
            else -> minOf(resolvedMaxLongEdge, 1280)
        }

        return PreviewLimits(
            maxLongEdge = resolvedMaxLongEdge,
            maxArea = resolvedMaxArea,
            analysisMaxLongEdge = analysisMax
        )
    }

    private fun aspectAwareVideoPreviewMaxArea(
        maxLongEdge: Int,
        targetAspect: Float?,
        fallbackArea: Int
    ): Int {
        if (targetAspect == null || !targetAspect.isFinite() || targetAspect <= 0f) {
            return fallbackArea
        }
        return resolveAspectAwarePreviewMaxArea(maxLongEdge, targetAspect)
            .coerceAtLeast(fallbackArea)
    }

    /**
     * Computes the maximum pixel area for a long edge at the sensor/transport aspect ratio.
     * Invalid metadata falls back deterministically to 16:9. Long arithmetic prevents overflow.
     */
    internal fun resolveAspectAwarePreviewMaxArea(
        maxLongEdge: Int,
        targetAspect: Float?
    ): Int {
        val resolvedLongEdge = maxLongEdge.coerceAtLeast(2)
        val normalizedAspect = targetAspect
            ?.takeIf { it.isFinite() && it > 0f }
            ?.let(SensorCropEngine::normalizeLongShortRatio)
            ?: (16f / 9f)
        val shortEdge = ceil(resolvedLongEdge.toDouble() / normalizedAspect.toDouble())
            .toLong()
            .coerceAtLeast(1L)
        return (resolvedLongEdge.toLong() * shortEdge)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    /**
     * Returns the default photo preview fallback size.
     * Falls back to the live display maximum; recording streams remain independent.
     */
    fun defaultPhotoPreviewFallback(): Size = Size(LIVE_DISPLAY_MAX_LONG_EDGE, 1080)

    /**
     * Returns the default stream preview fallback size.
     */
    fun defaultStreamPreviewFallback(): Size = Size(LIVE_DISPLAY_MAX_LONG_EDGE, 1080)

    /**
     * Resolves the maximum long edge for preview analysis streams.
     *
     * @param strictHighEnd Whether high-end analysis features are active.
     * @param normalizedPreviewLongEdge Long edge of the resolved preview size.
     * @param performanceAnalysisMaxLongEdge External performance cap.
     * @param nativePreview Whether the Vulkan native compositor is active.
     */
    fun resolvePreviewAnalysisMaxLongEdge(
        strictHighEnd: Boolean,
        normalizedPreviewLongEdge: Int,
        performanceAnalysisMaxLongEdge: Int?,
        nativePreview: Boolean = false
    ): Int {
        val limit = performanceAnalysisMaxLongEdge ?: when {
            normalizedPreviewLongEdge >= 3840 -> HIGH_RES_ANALYSIS_MAX_LONG_EDGE
            normalizedPreviewLongEdge >= 2560 -> HIGH_RES_ANALYSIS_MAX_LONG_EDGE
            strictHighEnd -> 1920
            else -> 1280
        }
        return if (strictHighEnd) {
            minOf(normalizedPreviewLongEdge, limit)
        } else {
            limit
        }
    }

    /**
     * Resolves the maximum long edge for recording analysis streams.
     */
    fun resolveRecordingAnalysisMaxLongEdge(
        normalizedRecordingLongEdge: Int,
        performanceAnalysisMaxLongEdge: Int?
    ): Int {
        val limit = performanceAnalysisMaxLongEdge ?: when {
            normalizedRecordingLongEdge >= 5120 -> ULTRA_HIGH_RES_ANALYSIS_MAX_LONG_EDGE
            normalizedRecordingLongEdge >= 4096 -> HIGH_RES_ANALYSIS_MAX_LONG_EDGE
            else -> 1280
        }
        return minOf(normalizedRecordingLongEdge, limit).coerceAtLeast(480)
    }

    fun shouldPreferSourceSizeMatch(
        sourceSize: Size,
        targetAspect: Float?,
        maxLongEdge: Int,
        maxArea: Int
    ): Boolean {
        val normalizedSource = if (sourceSize.width >= sourceSize.height) {
            sourceSize
        } else {
            Size(sourceSize.height, sourceSize.width)
        }
        val withinAppliedLimits =
            maxOf(normalizedSource.width, normalizedSource.height) <= maxLongEdge &&
                (normalizedSource.width.toLong() * normalizedSource.height.toLong()) <= maxArea.toLong()
        if (!withinAppliedLimits) return false
        if (maxOf(normalizedSource.width, normalizedSource.height) < LIVE_DISPLAY_MAX_LONG_EDGE) {
            return false
        }
        val aspect = targetAspect ?: return true
        val sourceAspect =
            normalizedSource.width.toFloat() / normalizedSource.height.coerceAtLeast(1).toFloat()
        return abs(sourceAspect - aspect) <= SOURCE_MATCH_ASPECT_TOLERANCE
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private fun closestFromAll(sizes: List<Size>, targetAspect: Float): Size {
        return sizes.minWithOrNull(
            compareBy<Size> { size ->
                abs(size.width.toFloat() / size.height.coerceAtLeast(1).toFloat() - targetAspect)
            }.thenBy { size ->
                size.width.toLong() * size.height.toLong()
            }
        ) ?: targetPreviewSize
    }

    /**
     * Calculates the Euclidean distance between two sizes in pixel-space.
     */
    private fun sizeDistance(a: Size, b: Size): Float {
        val dx = (a.width - b.width).toFloat()
        val dy = (a.height - b.height).toFloat()
        return sqrt(dx * dx + dy * dy)
    }
}
