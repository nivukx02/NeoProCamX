package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

enum class CinemaVideoMode {
    PRO,
    LOG,
    RAW;

    companion object {
        private fun strictHighEndEnabled(): Boolean =
            HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

        fun fromName(value: String?): CinemaVideoMode {
            val normalized = value?.trim()?.uppercase().orEmpty()
            if (normalized == "NORMAL") return PRO
            return runCatching { valueOf(normalized) }
                .getOrElse { PRO }
        }
    }
}

/**
 * NeoRadiance spectral RAW pipeline modes.
 *
 * Original NPRAW container format with first-principles encoding:
 *  - target bit depth (12 / 14 / 16 bit packed),
 *  - Deflate level (NO_COMPRESSION, BEST_SPEED, DEFAULT, BEST_COMPRESSION),
 *  - whether adaptive quantization is enabled.
 *
 * All modes route through the internal NeoRadiance predictive payload
 * pipeline — no proprietary codecs are implemented or claimed.
 */
enum class RawCompressionMode {
    /** Compatibility path for open raw frame export — packed 16-bit linear Bayer. */
    OFF,
    /** Predictive + Deflate(BEST_SPEED), 16-bit, no quantization. */
    LOSSLESS,
    /** Predictive + Deflate(BEST_COMPRESSION), 16-bit, no quantization. */
    LOSSLESS_ENHANCED,
    /** Predictive + Deflate(BEST_SPEED), 16-bit, adaptive quantization. */
    VISUALLY_LOSSLESS,
    /** Predictive + Deflate(DEFAULT), 14-bit, adaptive quantization. */
    HIGH_QUALITY,
    /** RAW video master path: uncompressed packed 12-bit linear Bayer payload. */
    HIGH_COMPRESSION,
    /** NeoRadiance Spectral RAW — 14-bit predictive + Deflate pipeline. */
    NEO_SPECTRAL_RAW,
    /** NeoRadiance Pure RAW — 14-bit balanced predictive pipeline. */
    NEO_PURE_RAW,
    /** NeoRadiance Fine RAW — 14-bit premium predictive pipeline. */
    NEO_FINE_RAW,
    /** NeoRadiance Dense RAW — 16-bit deep predictive pipeline. */
    NEO_DENSE_RAW,
    /** Realtime RAW-video master: direct packed 16-bit Bayer, bit-exact and uncompressed. */
    LOSSLESS_REALTIME;

    companion object {
        fun fromName(value: String?): RawCompressionMode =
            when (value?.trim()?.uppercase().orEmpty()) {
                // Original NeoRadiance names
                "NEO_SPECTRAL_RAW" -> NEO_SPECTRAL_RAW
                "NEO_PURE_RAW" -> NEO_PURE_RAW
                "NEO_FINE_RAW" -> NEO_FINE_RAW
                "NEO_DENSE_RAW" -> NEO_DENSE_RAW
                // Standard modes
                "OFF" -> OFF
                "LOSSLESS" -> LOSSLESS
                "LOSSLESS_ENHANCED" -> LOSSLESS_ENHANCED
                "VISUALLY_LOSSLESS" -> VISUALLY_LOSSLESS
                "HIGH_QUALITY" -> HIGH_QUALITY
                "HIGH_COMPRESSION" -> HIGH_COMPRESSION
                "LOSSLESS_REALTIME" -> LOSSLESS_REALTIME
                else -> HIGH_COMPRESSION
            }

        // Approximate storage ratios on a Snapdragon 8 Gen 3 reference sensor at
        // 12.5 MP, daylight scene; treated as a UI hint, not a guarantee. The
        // HIGH_COMPRESSION enum name is legacy: its RAW video payload is packed
        // 12-bit with no predictive codec or Deflate stage.
        fun getCompressionRatio(mode: RawCompressionMode): Float = when (mode) {
            OFF -> 1.0f
            LOSSLESS -> 1.5f
            LOSSLESS_ENHANCED -> 2.0f
            VISUALLY_LOSSLESS -> 2.8f
            HIGH_QUALITY -> 4.0f
            HIGH_COMPRESSION -> 1.33f
            NEO_SPECTRAL_RAW -> 2.5f
            NEO_PURE_RAW -> 3.2f
            NEO_FINE_RAW -> 3.5f
            NEO_DENSE_RAW -> 4.5f
            LOSSLESS_REALTIME -> 1.0f
        }

        fun getBitDepth(mode: RawCompressionMode): Int = when (mode) {
            OFF, LOSSLESS, LOSSLESS_ENHANCED, LOSSLESS_REALTIME -> 16
            VISUALLY_LOSSLESS -> 16
            HIGH_QUALITY -> 14
            NEO_SPECTRAL_RAW -> 14
            NEO_PURE_RAW -> 14
            NEO_FINE_RAW -> 14
            NEO_DENSE_RAW -> 16
            HIGH_COMPRESSION -> 12
        }
    }
}

enum class VideoBitrateMode {
    AUTO,
    CINEMA_MASTER,
    CUSTOM;

    companion object {
        private fun strictHighEndEnabled(): Boolean =
            HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

        fun fromName(value: String?): VideoBitrateMode =
            runCatching { valueOf(value?.trim()?.uppercase().orEmpty()) }
                .getOrElse { if (strictHighEndEnabled()) CINEMA_MASTER else AUTO }
    }
}

data class CinemaRuntimeStats(
    val previewFps: Float = 0f,
    val targetFps: Int = 25,
    val bitrateMbps: Float = 0f,
    val dropEstimate: Int = 0,
    val remainingMinutes: Int? = null,
    val thermalLabel: String = "Nominal",
    val resolutionLabel: String = "",
    val storageLabel: String = "",
    val modeLabel: String = "PRO"
)
