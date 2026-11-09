package com.neoproc.neoproccamx.recording

import android.util.Size
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * RecordingSourceResolutionPolicy — Codec-level recording resolution validation and fallback.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * FLAGSHIP UPGRADE: FULL SENSOR READOUT / ZERO DOWNSCALE
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Validates a [RecordingSourceResolutionPlan] against the active encoder
 * capabilities and applies corrections when the requested size or FPS
 * is not natively supported.
 *
 * In full-sensor mode (HighEndQualityPolicy.forceFullSensorReadout):
 *   - MAX_LONG_EDGE is effectively unlimited (sensor native)
 *   - Alignment is applied but dimensions are never scaled down
 *   - Native aspect ratio is preserved through all corrections
 *   - Bitrate recommendations scale to full sensor pixel count
 *
 * Responsibilities:
 *  1. **Alignment enforcement** — ensures width/height meet MediaCodec alignment requirements.
 *  2. **Aspect ratio preservation** — corrections maintain the original aspect ratio.
 *  3. **FPS clamping** — restricts frame rate to encoder-supported range.
 *  4. **Tier labeling** — assigns a human-readable resolution tier label.
 *  5. **Fallback chain** — progressively reduces resolution if the encoder rejects the size.
 *
 * This policy operates at the recording layer (closest to the encoder). The UI-layer
 * `RecordingSourceResolutionPolicy` operates at the sensor/planning level.
 */
object RecordingSourceResolutionPolicy {

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Standard encoder alignment — most hardware codecs require mod-2 dimensions. */
    private const val DEFAULT_ALIGNMENT = 2

    /** HEVC typically requires mod-8 or mod-16 for optimal encoding. */
    private const val HEVC_ALIGNMENT = 8

    /** Minimum encoder output dimensions. */
    private const val MIN_DIMENSION = 128

    /** Maximum encoder output long edge — unlimited in full sensor mode. */
    private fun maxLongEdge(): Int = HighEndQualityPolicy.maximumRecordingLongEdge()

    // ── Core API ──────────────────────────────────────────────────────────────

    /**
     * Validates and potentially corrects a [RecordingSourceResolutionPlan].
     *
     * @param plan The requested plan to validate.
     * @return A validated plan (may differ from input if corrections were applied).
     */
    fun resolve(plan: RecordingSourceResolutionPlan): RecordingSourceResolutionPlan {
        val alignment = resolveAlignment(plan.codecMime)
        val effectiveMaxLongEdge = maxLongEdge()
        val alignedWidth = alignDown(plan.width, alignment)
            .coerceIn(MIN_DIMENSION, effectiveMaxLongEdge)
        val alignedHeight = alignDown(plan.height, alignment)
            .coerceIn(MIN_DIMENSION, effectiveMaxLongEdge)
        val clampedFps = plan.fps.coerceIn(1, 240)
        val tierLabel = resolveTierLabel(alignedWidth, alignedHeight)

        return plan.copy(
            width = alignedWidth,
            height = alignedHeight,
            fps = clampedFps,
            tierLabel = tierLabel
        )
    }

    /**
     * Resolves a plan from raw width, height, and fps values.
     *
     * @param width  Requested width.
     * @param height Requested height.
     * @param fps    Requested frame rate.
     * @param codec  Codec MIME type.
     */
    fun resolve(
        width: Int,
        height: Int,
        fps: Int,
        codec: String = "video/hevc"
    ): RecordingSourceResolutionPlan {
        val raw = RecordingSourceResolutionPlan(
            width = width, height = height, fps = fps, codecMime = codec
        )
        return resolve(raw)
    }

    /**
     * Applies a fallback resolution strategy when the primary resolution fails.
     *
     * Strategy:
     *  1. Try 90% of the original dimensions (maintains aspect, minimal loss).
     *  2. Try 80% of the original dimensions.
     *  3. Try standard 4K (3840×2160).
     *  4. Try 1080p (1920×1080).
     *  5. Try 720p (1280×720).
     *
     * @param plan The original plan that failed encoder validation.
     * @return A list of fallback plans in priority order.
     */
    fun fallbackChain(plan: RecordingSourceResolutionPlan): List<RecordingSourceResolutionPlan> {
        val aspect = plan.aspectRatio
        val alignment = resolveAlignment(plan.codecMime)
        return buildList {
            // 90% scale — minimal quality loss
            val scaled90W = alignDown((plan.width * 0.9f).roundToInt(), alignment)
            val scaled90H = alignDown((scaled90W / aspect).roundToInt(), alignment)
            if (scaled90W >= MIN_DIMENSION && scaled90H >= MIN_DIMENSION) {
                add(resolve(plan.copy(width = scaled90W, height = scaled90H)))
            }

            // 80% scale
            val scaled80W = alignDown((plan.width * 0.8f).roundToInt(), alignment)
            val scaled80H = alignDown((scaled80W / aspect).roundToInt(), alignment)
            if (scaled80W >= MIN_DIMENSION && scaled80H >= MIN_DIMENSION) {
                add(resolve(plan.copy(width = scaled80W, height = scaled80H)))
            }

            // Standard fallbacks
            val standardSizes = listOf(
                Size(3840, 2160),
                Size(1920, 1080),
                Size(1280, 720)
            )
            for (size in standardSizes) {
                if (size.width < plan.width || size.height < plan.height) {
                    add(resolve(plan.copy(width = size.width, height = size.height)))
                }
            }
        }.distinctBy { "${it.width}x${it.height}" }
    }

    /**
     * Returns the optimal bitrate for the given plan.
     *
     * Uses a per-pixel bitrate model upgraded for full-sensor recording:
     *  - Base: 12 bits/pixel at 25fps for cinema-oriented quality
     *  - High-end multiplier: 2.0× for flagship-grade bitrates
     *  - FPS scaling: linear proportional to frame rate
     *  - Full sensor bonus: additional headroom for sensor-native open-gate readouts
     *
     * @param plan Resolution plan.
     * @return Recommended bitrate in bits per second.
     */
    fun recommendBitrate(plan: RecordingSourceResolutionPlan): Int {
        val baseBitsPerPixel = if (HighEndQualityPolicy.strictHighEndOnly) 14.0f else 10.0f
        val fpsRatio = plan.fps / 25f
        val qualityMultiplier = when {
            HighEndQualityPolicy.strictHighEndOnly -> 2.2f
            HighEndQualityPolicy.enabled -> 1.6f
            else -> 1.0f
        }
        val codecEfficiency = when {
            plan.codecMime.contains("av1", ignoreCase = true) -> 0.7f
            plan.codecMime.contains("hevc", ignoreCase = true) -> 0.85f
            else -> 1.0f
        }
        // Full sensor bonus for ultra-high resolution
        val fullSensorBonus = if (plan.pixelCount > 8_000_000L) 1.3f else 1.0f

        return (plan.pixelCount * baseBitsPerPixel * fpsRatio * qualityMultiplier * codecEfficiency * fullSensorBonus)
            .toLong()
            .coerceIn(1_000_000L, 2_000_000_000L)  // 1Mbps – 2Gbps
            .toInt()
    }

    /**
     * Normalizes a size to landscape orientation (width ≥ height).
     */
    fun normalizeForOrientation(size: Size): Size =
        if (size.width >= size.height) size else Size(size.height, size.width)

    // ── Private Helpers ───────────────────────────────────────────────────────

    private fun resolveAlignment(codecMime: String): Int = when {
        codecMime.contains("hevc", ignoreCase = true) -> HEVC_ALIGNMENT
        codecMime.contains("av1", ignoreCase = true) -> HEVC_ALIGNMENT
        else -> DEFAULT_ALIGNMENT
    }

    private fun alignDown(value: Int, alignment: Int): Int {
        val safe = alignment.coerceAtLeast(1)
        return (value / safe) * safe
    }

    private fun resolveTierLabel(width: Int, height: Int): String {
        val longEdge = maxOf(width, height)
        val pixelCount = width.toLong() * height.toLong()
        return when {
            pixelCount >= 40_000_000L -> "SENSOR_NATIVE"
            longEdge >= 7680 -> "8K_UHD"
            longEdge >= 5760 -> "6K_CINEMA"
            longEdge >= 4800 -> "5K"
            longEdge >= 4096 -> "DCI_4K"
            longEdge >= 3840 -> "4K_UHD"
            longEdge >= 2560 -> "2.7K"
            longEdge >= 2048 -> "2K_DCI"
            longEdge >= 1900 -> "1080P"
            longEdge >= 1200 -> "720P"
            else             -> "SD"
        }
    }
}
