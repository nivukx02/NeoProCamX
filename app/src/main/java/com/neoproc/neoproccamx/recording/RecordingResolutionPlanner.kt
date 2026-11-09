package com.neoproc.neoproccamx.recording

import android.util.Size
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs

internal data class RecordingDimensions(
    val width: Int,
    val height: Int
)

/**
 * RecordingResolutionPlanner — Full-sensor recording resolution planner.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * FLAGSHIP UPGRADE: ZERO DOWNSCALE / FULL SENSOR SUPPORT
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * This planner resolves recording dimensions while respecting the full sensor
 * pixel array. When HighEndQualityPolicy.forceFullSensorReadout is enabled,
 * the planner will:
 *   - Accept any sensor-native resolution without capping
 *   - Preserve native aspect ratio (no AR modification)
 *   - Only align to codec requirements (mod-2/mod-8)
 *   - Never downscale below the requested size
 *
 * The maximum long edge is determined by HighEndQualityPolicy.maximumRecordingLongEdge()
 * which returns Int.MAX_VALUE in full-sensor mode, effectively removing all caps.
 */
internal object RecordingResolutionPlanner {

    // ── Standard Reference Sizes ─────────────────────────────────────────────
    // Used as fallback candidates when no camera sizes are available.
    // Ordered by descending resolution for priority matching.

    private val commonSizes = listOf(
        RecordingDimensions(8192, 6144),  // sensor-native max open gate (4:3)
        RecordingDimensions(8000, 6000),  // sensor-native open gate variant (4:3)
        RecordingDimensions(7680, 4320),  // 8K UHD (16:9)
        RecordingDimensions(6528, 4896),  // 32MP native (4:3)
        RecordingDimensions(5760, 4320),  // 6K (4:3)
        RecordingDimensions(5120, 3840),  // 5K (4:3)
        RecordingDimensions(5120, 2880),  // 5K (16:9)
        RecordingDimensions(4624, 3468),  // 16MP native (4:3)
        RecordingDimensions(4096, 3072),  // 4K Full (4:3)
        RecordingDimensions(4096, 2160),  // DCI 4K
        RecordingDimensions(3840, 2160),  // 4K UHD
        RecordingDimensions(3328, 1872),  // 2.7K wide
        RecordingDimensions(3072, 1728),  // 3K
        RecordingDimensions(2560, 1440),  // QHD
        RecordingDimensions(1920, 1080),  // FHD
        RecordingDimensions(1440, 1080),  // FHD 4:3
        RecordingDimensions(1280, 720)    // HD
    )

    // ── Core API ─────────────────────────────────────────────────────────────

    fun sanitizeRequestedSize(requestedWidth: Int, requestedHeight: Int): Size =
        sanitizeRequestedDimensions(requestedWidth, requestedHeight).toSize()

    /**
     * Sanitizes requested dimensions while respecting full-sensor policy.
     *
     * In full-sensor mode: dimensions are only aligned to codec requirements,
     * never capped or downscaled. The native sensor size passes through.
     *
     * In standard mode: dimensions are clamped to policy limits.
     */
    fun sanitizeRequestedDimensions(
        requestedWidth: Int,
        requestedHeight: Int
    ): RecordingDimensions = run {
        val fullSensor = HighEndQualityPolicy.isFullSensorForced()

        val minWidth = if (HighEndQualityPolicy.enabled) 1280 else 640
        val minHeight = if (HighEndQualityPolicy.enabled) 720 else 480

        val normalized = normalizeSize(
            RecordingDimensions(
                requestedWidth.coerceAtLeast(minWidth),
                requestedHeight.coerceAtLeast(minHeight)
            )
        )

        // Full sensor mode: only align, never cap
        if (fullSensor) {
            alignToCodecRequirements(normalized)
        } else {
            val maxLongEdge = HighEndQualityPolicy.maximumRecordingLongEdge()
            capToMaxLongEdge(normalized, maxLongEdge = maxLongEdge)
        }
    }

    /**
     * Builds a prioritized list of recording size candidates.
     *
     * The list starts with the requested size (exact match preferred),
     * followed by camera-supported sizes sorted by aspect ratio proximity
     * and descending area.
     */
    fun buildSizeCandidates(requested: Size, supportedCameraSizes: List<Size>): List<Size> {
        return buildSizeCandidates(
            requested = requested.toDimensions(),
            supportedCameraSizes = supportedCameraSizes.map { it.toDimensions() }
        ).map { it.toSize() }
    }

    fun buildSizeCandidates(
        requested: RecordingDimensions,
        supportedCameraSizes: List<RecordingDimensions>
    ): List<RecordingDimensions> {
        val normalized = normalizeSize(requested)
        val aspect = aspectRatio(normalized)
        val cameraSizes = if (supportedCameraSizes.isNotEmpty()) {
            sortDeliveryCandidates(
                requested = normalized,
                candidates = supportedCameraSizes
            )
        } else {
            sortDeliveryCandidates(
                requested = normalized,
                candidates = commonSizes + normalized
            )
        }
        return buildList {
            add(normalized)
            addAll(cameraSizes)
        }.distinctBy { it.width to it.height }
    }

    // ── Utility Functions ────────────────────────────────────────────────────

    fun alignDown(value: Int, alignment: Int): Int =
        (value / alignment) * alignment

    fun aspectRatio(size: Size): Float =
        aspectRatio(size.toDimensions())

    fun aspectRatio(size: RecordingDimensions): Float =
        size.width.toFloat() / size.height.toFloat()

    fun normalizeSize(size: Size): Size =
        normalizeSize(size.toDimensions()).toSize()

    fun normalizeSize(size: RecordingDimensions): RecordingDimensions =
        if (size.width >= size.height) size else RecordingDimensions(size.height, size.width)

    fun resolutionLabel(width: Int, height: Int): String = when {
        width >= 7680 -> "8K"
        width >= 5760 -> "6K"
        width >= 5120 -> "5K"
        width >= 4096 -> "4K DCI"
        width >= 3840 -> "4K"
        width >= 2560 -> "2.7K"
        width >= 2048 -> "2K"
        width >= 1920 -> "1080p"
        width >= 1280 -> "1K"
        else          -> "${width}x${height}"
    }

    /**
     * Returns a human-readable megapixel label for the given dimensions.
     */
    fun megapixelLabel(width: Int, height: Int): String {
        val mp = (width.toLong() * height.toLong()) / 1_000_000f
        return when {
            mp >= 50f -> "${mp.toInt()}MP"
            mp >= 10f -> "${mp.toInt()}MP"
            mp >= 1f -> "%.1fMP".format(mp)
            else -> "${(mp * 1000).toInt()}KP"
        }
    }

    // ── Private Helpers ──────────────────────────────────────────────────────

    /**
     * Aligns dimensions to codec requirements without capping.
     * HEVC/AV1 typically require mod-8; AVC requires mod-2.
     * We use mod-2 as the safe default since the encoder will
     * apply its own alignment during configuration.
     */
    private fun alignToCodecRequirements(size: RecordingDimensions): RecordingDimensions {
        val alignment = 2  // Minimum safe alignment
        val alignedWidth = alignDown(size.width, alignment).coerceAtLeast(alignment)
        val alignedHeight = alignDown(size.height, alignment).coerceAtLeast(alignment)
        return RecordingDimensions(alignedWidth, alignedHeight)
    }

    private fun sortDeliveryCandidates(
        requested: RecordingDimensions,
        candidates: List<RecordingDimensions>
    ): List<RecordingDimensions> {
        val normalized = normalizeSize(requested)
        val requestedArea = area(normalized)
        val aspect = aspectRatio(normalized)
        val distinctCandidates = candidates
            .map(::normalizeSize)
            .distinctBy { it.width to it.height }
        val lowerOrEqual = distinctCandidates
            .filter { area(it) <= requestedArea }
            .sortedWith(
                compareBy<RecordingDimensions> { abs(aspectRatio(it) - aspect) }
                    .thenByDescending(::area)
            )
        val larger = distinctCandidates
            .filter { area(it) > requestedArea }
            .sortedWith(
                compareBy<RecordingDimensions> { abs(aspectRatio(it) - aspect) }
                    .thenBy(::area)
            )
        return lowerOrEqual + larger
    }

    private fun capToMaxLongEdge(
        size: RecordingDimensions,
        maxLongEdge: Int
    ): RecordingDimensions {
        val normalized = normalizeSize(size)
        val longEdge = normalized.width
        if (longEdge <= maxLongEdge) return normalized
        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
        val scaledWidth = alignDown((normalized.width * scale).toInt().coerceAtLeast(1), 2)
        val scaledHeight = alignDown((normalized.height * scale).toInt().coerceAtLeast(1), 2)
        return RecordingDimensions(scaledWidth, scaledHeight)
    }

    private fun Size.toDimensions(): RecordingDimensions = RecordingDimensions(width, height)

    private fun RecordingDimensions.toSize(): Size = Size(width, height)

    private fun area(size: RecordingDimensions): Long =
        size.width.toLong() * size.height.toLong()
}
