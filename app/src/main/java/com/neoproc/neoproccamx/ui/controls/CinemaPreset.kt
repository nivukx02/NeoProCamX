package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.NdrFeatureGate
import com.neoproc.neoproccamx.recording.CinemaVideoMode

/**
 * CinemaPreset — cinema-oriented video recording preset catalog.
 *
 * Defines the complete set of production-ready cinema presets for NeoProCamX.
 * Each preset encapsulates a validated, interconnected set of parameters:
 *
 *  - Target resolution and frame rate
 *  - Codec preference and bit depth
 *  - Color science profile (LOG curve, gamut, HDR mode)
 *  - Shutter angle (180° rule compliance)
 *  - ISO range guidance
 *  - White balance reference
 *  - Bitrate target
 *  - LUT complexity tier
 *
 * These presets are designed for one-tap configuration of the cinema pipeline,
 * eliminating the need for users to manually configure each parameter.
 *
 * Preset naming follows industry conventions:
 *  - Named after the creative intent, not technical parameters
 *  - Ordered from most common to most specialized
 */
enum class CinemaPreset(
    /** Human-readable display name. */
    val displayName: String,
    /** Short description of the creative intent. */
    val description: String,
    /** Cinema sub-mode activated by this preset. */
    val cinemaMode: CinemaVideoMode,
    /** Target long-edge resolution (pixels). */
    val targetLongEdge: Int,
    /** Target frame rate (fps). */
    val targetFps: Int,
    /** Whether 10-bit output is required. */
    val tenBitRequired: Boolean,
    /** Whether HDR mode should be activated. */
    val hdrEnabled: Boolean,
    /** Default NDR profile when [hdrEnabled] is true. */
    val hdrStandard: String,
    /** Shutter angle in degrees (0 = auto). */
    val shutterAngleDegrees: Int,
    /** Recommended ISO (0 = auto). */
    val recommendedIso: Int,
    /** Recommended white balance in Kelvin (0 = auto). */
    val recommendedWbKelvin: Int,
    /** Target bitrate in Mbps. */
    val targetBitrateMbps: Int,
    /** LUT cube size for preview (0 = no LUT). */
    val lutSize: Int,
    /** Whether RAW video output is preferred. */
    val rawPreferred: Boolean,
    /** Color space identifier. */
    val colorSpace: String,
    /** Whether this preset requires strict high-end mode. */
    val requiresStrictHighEnd: Boolean
) {
    // ── Standard Cinema Presets ────────────────────────────────────────────────

    /**
     * FILM — Classic cinematic look.
     * 24fps, 180° shutter, warm tone, LOG color for post-production grading.
     */
    FILM(
        displayName = "Film",
        description = "Classic cinematic — 24fps, 180° shutter, LOG color",
        cinemaMode = CinemaVideoMode.LOG,
        targetLongEdge = 3840,
        targetFps = 24,
        tenBitRequired = true,
        hdrEnabled = false,
        hdrStandard = "OFF",
        shutterAngleDegrees = 180,
        recommendedIso = 800,
        recommendedWbKelvin = 5600,
        targetBitrateMbps = 100,
        lutSize = 64,
        rawPreferred = false,
        colorSpace = "Rec.2020",
        requiresStrictHighEnd = false
    ),

    /**
     * NARRATIVE — Broadcast and streaming narrative content.
     * 25fps, 180° shutter, balanced exposure, PRO mode.
     */
    NARRATIVE(
        displayName = "Narrative",
        description = "Broadcast narrative - 25fps, balanced exposure",
        cinemaMode = CinemaVideoMode.PRO,
        targetLongEdge = 3840,
        targetFps = 25,
        tenBitRequired = true,
        hdrEnabled = false,
        hdrStandard = "OFF",
        shutterAngleDegrees = 180,
        recommendedIso = 400,
        recommendedWbKelvin = 5500,
        targetBitrateMbps = 80,
        lutSize = 33,
        rawPreferred = false,
        colorSpace = "Rec.709",
        requiresStrictHighEnd = false
    ),

    /**
     * DOCUMENTARY — Run-and-gun documentary style.
     * 25fps (PAL), auto exposure, moderate bitrate for long recordings.
     */
    DOCUMENTARY(
        displayName = "Documentary",
        description = "Run-and-gun — 25fps, auto exposure, sustained recording",
        cinemaMode = CinemaVideoMode.PRO,
        targetLongEdge = 3840,
        targetFps = 25,
        tenBitRequired = false,
        hdrEnabled = false,
        hdrStandard = "OFF",
        shutterAngleDegrees = 180,
        recommendedIso = 0,   // Auto
        recommendedWbKelvin = 0, // Auto
        targetBitrateMbps = 60,
        lutSize = 33,
        rawPreferred = false,
        colorSpace = "Rec.709",
        requiresStrictHighEnd = false
    ),

    /**
     * HDR_CINEMA — Theatrical NDR delivery.
     * NDR10+ with PQ transfer, 10-bit HEVC, Rec.2020 gamut.
     */
    HDR_CINEMA(
        displayName = "XDR Cinema",
        description = "Theatrical XDR10+ - 10-bit PQ, Rec.2020 gamut",
        cinemaMode = CinemaVideoMode.PRO,
        targetLongEdge = 3840,
        targetFps = 24,
        tenBitRequired = true,
        hdrEnabled = true,
        hdrStandard = "NDR10+",
        shutterAngleDegrees = 180,
        recommendedIso = 800,
        recommendedWbKelvin = 5600,
        targetBitrateMbps = 120,
        lutSize = 64,
        rawPreferred = false,
        colorSpace = "Rec.2020-PQ",
        requiresStrictHighEnd = true
    ),

    /**
     * HLG_BROADCAST — NDR broadcast profile.
     * Uses NeoProCamX NDR10+ with Android-compatible 10-bit transport.
     */
    HLG_BROADCAST(
        displayName = "XDR Broadcast",
        description = "XDR10+ broadcast - 10-bit Neo dynamic range",
        cinemaMode = CinemaVideoMode.PRO,
        targetLongEdge = 3840,
        targetFps = 25,
        tenBitRequired = true,
        hdrEnabled = true,
        hdrStandard = "NDR10+",
        shutterAngleDegrees = 180,
        recommendedIso = 400,
        recommendedWbKelvin = 5500,
        targetBitrateMbps = 80,
        lutSize = 33,
        rawPreferred = false,
        colorSpace = "Rec.2020-NDR10+",
        requiresStrictHighEnd = false
    ),

    /**
     * SLOW_MOTION — Highest frame rate allowed by the video contract.
     * 60fps at 1080p, fast shutter, no LOG/HDR for clean motion capture.
     */
    SLOW_MOTION(
        displayName = "Slow Motion",
        description = "60fps high-speed capture — 1080p",
        cinemaMode = CinemaVideoMode.PRO,
        targetLongEdge = 1920,
        targetFps = 60,
        tenBitRequired = false,
        hdrEnabled = false,
        hdrStandard = "OFF",
        shutterAngleDegrees = 90,  // Fast shutter for motion freeze
        recommendedIso = 0,
        recommendedWbKelvin = 0,
        targetBitrateMbps = 100,
        lutSize = 0,
        rawPreferred = false,
        colorSpace = "Rec.709",
        requiresStrictHighEnd = false
    ),

    /**
     * RAW_CINEMA — Maximum quality RAW video for heavy post-production.
     * Full sensor readout, CinemaDNG RAW, maximum bit depth.
     */
    RAW_CINEMA(
        displayName = "RAW Cinema",
        description = "Full-sensor RAW — maximum quality, heavy post",
        cinemaMode = CinemaVideoMode.RAW,
        targetLongEdge = 4096,
        targetFps = 24,
        tenBitRequired = true,
        hdrEnabled = false,
        hdrStandard = "OFF",
        shutterAngleDegrees = 180,
        recommendedIso = 800,
        recommendedWbKelvin = 5600,
        targetBitrateMbps = 200,
        lutSize = 0,   // No LUT in RAW mode
        rawPreferred = true,
        colorSpace = "RAW",
        requiresStrictHighEnd = true
    ),

    /**
     * LOG_GRADING — Professional LOG recording for color grading.
     * N-Log style flat profile, wide dynamic range, Rec.2020 gamut.
     */
    LOG_GRADING(
        displayName = "LOG Grading",
        description = "Professional LOG — flat profile for color grading",
        cinemaMode = CinemaVideoMode.LOG,
        targetLongEdge = 3840,
        targetFps = 25,
        tenBitRequired = true,
        hdrEnabled = false,
        hdrStandard = "OFF",
        shutterAngleDegrees = 180,
        recommendedIso = 800,
        recommendedWbKelvin = 5600,
        targetBitrateMbps = 100,
        lutSize = 64,
        rawPreferred = false,
        colorSpace = "Rec.2020-LOG",
        requiresStrictHighEnd = false
    ),

    /**
     * SOCIAL_VERTICAL — Optimized for vertical social media delivery.
     * 9:16 portrait, 1080p, 60fps, auto exposure for handheld use.
     */
    SOCIAL_VERTICAL(
        displayName = "Social Vertical",
        description = "9:16 portrait — 1080×1920, 60fps for Reels/TikTok",
        cinemaMode = CinemaVideoMode.PRO,
        targetLongEdge = 1920,
        targetFps = 60,
        tenBitRequired = false,
        hdrEnabled = false,
        hdrStandard = "OFF",
        shutterAngleDegrees = 0,  // Auto
        recommendedIso = 0,
        recommendedWbKelvin = 0,
        targetBitrateMbps = 30,
        lutSize = 0,
        rawPreferred = false,
        colorSpace = "Rec.709",
        requiresStrictHighEnd = false
    ),

    /**
     * INTERVIEW — Two-person interview / talking head setup.
     * 25fps, warm tone, face-detection priority, moderate bitrate.
     */
    INTERVIEW(
        displayName = "Interview",
        description = "Talking head - 25fps, warm tone, face priority",
        cinemaMode = CinemaVideoMode.PRO,
        targetLongEdge = 3840,
        targetFps = 25,
        tenBitRequired = false,
        hdrEnabled = false,
        hdrStandard = "OFF",
        shutterAngleDegrees = 180,
        recommendedIso = 400,
        recommendedWbKelvin = 4000,  // Warm studio lighting
        targetBitrateMbps = 50,
        lutSize = 33,
        rawPreferred = false,
        colorSpace = "Rec.709",
        requiresStrictHighEnd = false
    ),

    /**
     * NIGHT — Low-light cinema capture.
     * High ISO, wide shutter, noise-optimized pipeline.
     */
    NIGHT(
        displayName = "Night Cinema",
        description = "Low-light — high ISO, wide shutter, noise-optimized",
        cinemaMode = CinemaVideoMode.PRO,
        targetLongEdge = 3840,
        targetFps = 24,
        tenBitRequired = true,
        hdrEnabled = false,
        hdrStandard = "OFF",
        shutterAngleDegrees = 270,  // Wide shutter for more light
        recommendedIso = 3200,
        recommendedWbKelvin = 4500,  // Sodium/tungsten mix
        targetBitrateMbps = 80,
        lutSize = 33,
        rawPreferred = false,
        colorSpace = "Rec.709",
        requiresStrictHighEnd = false
    );

    // ── Derived Properties ────────────────────────────────────────────────────

    /** Whether this preset uses any LOG color profile. */
    val isLogMode: Boolean get() = cinemaMode == CinemaVideoMode.LOG

    /** Whether this preset uses RAW capture. */
    val isRawMode: Boolean get() = cinemaMode == CinemaVideoMode.RAW

    /** Whether this preset configures manual exposure (shutter + ISO). */
    val usesManualExposure: Boolean get() = shutterAngleDegrees > 0 && recommendedIso > 0

    /** Whether this preset configures manual white balance. */
    val usesManualWhiteBalance: Boolean get() = recommendedWbKelvin > 0

    /** Whether this preset is available on the current device. */
    val isAvailable: Boolean
        get() = !requiresStrictHighEnd || (HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly)

    /** Target short edge based on 16:9 aspect. */
    val targetShortEdge: Int get() = (targetLongEdge * 9 / 16).coerceAtLeast(1)

    /** Whether this preset is an NDR video mode. */
    val isNdr: Boolean
        get() = hdrStandard == "NDR10+" || colorSpace.contains("NDR10+", ignoreCase = true) || displayName.contains("NDR", ignoreCase = true)

    /** Target megapixels. */
    val targetMegapixels: Float
        get() = (targetLongEdge.toLong() * targetShortEdge.toLong()) / 1_000_000f

    companion object {
        /** Returns only presets available on the current device. */
        fun availablePresets(): List<CinemaPreset> =
            entries.filter { it.isAvailable && (NdrFeatureGate.enabled || !it.isNdr) }

        /** Returns presets for a specific cinema mode. */
        fun presetsForMode(mode: CinemaVideoMode): List<CinemaPreset> =
            entries.filter { it.cinemaMode == mode && it.isAvailable && (NdrFeatureGate.enabled || !it.isNdr) }

        /** Returns the default preset for new users. */
        val default: CinemaPreset get() = NARRATIVE

        /** Returns the highest-quality available preset. */
        val highestQuality: CinemaPreset
            get() = entries.filter { it.isAvailable && (NdrFeatureGate.enabled || !it.isNdr) }
                .maxByOrNull { it.targetBitrateMbps }
                ?: NARRATIVE
    }
}
