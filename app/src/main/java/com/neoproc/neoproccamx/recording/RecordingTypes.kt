package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

/**
 * Shared recording type definitions used across the recording pipeline
 * and UI layers. Kept in one file to avoid duplicate class errors.
 */

/** Video codec selection for recording. Mirrors VideoCodecType values. */
enum class VideoCodec { HEVC, AVC, AV1 }

/** Audio source for recording. */
enum class AudioSource { CAMCORDER, BUILTIN_MIC, UNPROCESSED, NONE }

/** Camera stabilization mode. */
enum class StabilizationMode { OIS, EIS, HYBRID, OFF }

/**
 * Snapshot of the current recording configuration, used by overlays for display.
 * Updated by CameraViewModel whenever recording settings change.
 */
data class RecordingConfig(
    val widthPx:    Int    = 3840,
    val heightPx:   Int    = 2160,
    val fps:        Int    = 25,
    val bitrateBps: Int    = 220_000_000,  // FLAGSHIP: 220Mbps default for 4K
    val codec:      VideoCodec = VideoCodec.HEVC
) {
    val resolutionLabel: String get() = when {
        widthPx.toLong() * heightPx.toLong() >= 40_000_000L -> "Sensor Native"
        widthPx >= 7680 -> "8K"
        widthPx >= 5120 -> "5K"
        widthPx >= 3840 -> "4K"
        widthPx >= 2560 -> "2.7K"
        widthPx >= 2048 -> "2K"
        widthPx >= 1920 -> "1080p"
        widthPx >= 1280 -> "720p"
        else            -> "${widthPx}×${heightPx}"
    }

    val bitrateLabel: String get() {
        val mbps = bitrateBps / 1_000_000
        return "$mbps Mbps"
    }

    fun normalizedForStrictHighEnd(): RecordingConfig {
        if (!(HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly)) return this
        val minimumBitrate = minimumStrictBitrateBps()
        return copy(
            widthPx = widthPx.coerceAtLeast(3840),
            heightPx = heightPx.coerceAtLeast(2160),
            fps = fps.coerceAtLeast(25),
            bitrateBps = bitrateBps.coerceAtLeast(minimumBitrate),
            codec = if (codec == VideoCodec.AVC) VideoCodec.HEVC else codec
        )
    }

    private fun minimumStrictBitrateBps(): Int {
        val pixelBudget = (widthPx.toLong() * heightPx.toLong()).coerceAtLeast(3840L * 2160L)
        val fpsBudget = fps.coerceAtLeast(25)
        // FLAGSHIP: Higher bits-per-pixel for cinema quality
        val bitsPerPixel = if (HighEndQualityPolicy.strictHighEndOnly) 0.35f else 0.22f
        val estimate = (pixelBudget * fpsBudget * bitsPerPixel).toLong()
        return estimate.coerceIn(80_000_000L, 800_000_000L).toInt()
    }
}
