package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

/**
 * VideoProfileIntentPolicy — cinema-oriented video profile intent resolution.
 *
 * Maps user-selected video profile names to runtime intent hints that configure
 * the camera pipeline's color science, LUT, HDR, and quality boost behavior.
 *
 * Supported profile names:
 *  - "cinema"   → Wide gamut, LOG implied, HDR10, maximum quality
 *  - "log"      → Wide gamut, LOG mode, HLG-compatible
 *  - "hdr"      → Wide gamut, HDR10, high quality
 *  - "standard" → Rec.709, no LOG, auto HDR, baseline quality
 *  - "raw"      → No color processing, maximum fidelity
 *  - "night"    → High ISO optimized, noise reduction priority
 *
 * Each resolved [VideoProfileIntent] is consumed by:
 *  - Color pipeline (gamut selection, LUT loading)
 *  - Preview renderer (LUT size, display transfer function)
 *  - Recording pipeline (HDR mode, codec selection)
 */
object VideoProfileIntentPolicy {

    /**
     * Resolves a [VideoProfileIntent] for the given profile name.
     *
     * @param profileName Case-insensitive profile identifier.
     * @return Resolved intent with all pipeline hints.
     */
    fun resolve(profileName: String): VideoProfileIntent {
        val strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        return when (profileName.trim().lowercase()) {
            "cinema" -> VideoProfileIntent(
                requiresWideGamut = true,
                impliesLogMode = true,
                recommendedHdrMode = "HDR10",
                qualityBoost = if (strictHighEnd) 2.8f else 2.2f,
                targetPreviewFps = if (strictHighEnd) 60 else 25,
                lutSizeForPreview = if (strictHighEnd) 128 else 64,
                colorSpace = "Rec.2020",
                bitDepth = 10,
                noiseReductionPriority = false,
                profileLabel = "Cinema"
            )
            "log" -> VideoProfileIntent(
                requiresWideGamut = true,
                impliesLogMode = true,
                recommendedHdrMode = "HLG",
                qualityBoost = if (strictHighEnd) 2.4f else 1.9f,
                targetPreviewFps = 60,
                lutSizeForPreview = if (strictHighEnd) 128 else 64,
                colorSpace = "Rec.2020-LOG",
                bitDepth = 10,
                noiseReductionPriority = false,
                profileLabel = "LOG"
            )
            "hdr" -> VideoProfileIntent(
                requiresWideGamut = true,
                impliesLogMode = false,
                recommendedHdrMode = "HDR10",
                qualityBoost = if (strictHighEnd) 2.6f else 2.0f,
                targetPreviewFps = 60,
                lutSizeForPreview = if (strictHighEnd) 128 else 64,
                colorSpace = "Rec.2020-PQ",
                bitDepth = 10,
                noiseReductionPriority = false,
                profileLabel = "HDR"
            )
            "hlg" -> VideoProfileIntent(
                requiresWideGamut = true,
                impliesLogMode = false,
                recommendedHdrMode = "HLG",
                qualityBoost = 2.0f,
                targetPreviewFps = 60,
                lutSizeForPreview = 64,
                colorSpace = "Rec.2020-HLG",
                bitDepth = 10,
                noiseReductionPriority = false,
                profileLabel = "HLG"
            )
            "raw" -> VideoProfileIntent(
                requiresWideGamut = false,
                impliesLogMode = false,
                recommendedHdrMode = null,
                qualityBoost = 3.5f,
                targetPreviewFps = 60,
                lutSizeForPreview = 0,  // No LUT in RAW mode
                colorSpace = "RAW",
                bitDepth = 12,
                noiseReductionPriority = false,
                profileLabel = "RAW"
            )
            "night" -> VideoProfileIntent(
                requiresWideGamut = false,
                impliesLogMode = false,
                recommendedHdrMode = null,
                qualityBoost = 1.8f,
                targetPreviewFps = 60,
                lutSizeForPreview = 33,
                colorSpace = "Rec.709",
                bitDepth = 10,
                noiseReductionPriority = true,
                profileLabel = "Night"
            )
            "interview" -> VideoProfileIntent(
                requiresWideGamut = false,
                impliesLogMode = false,
                recommendedHdrMode = null,
                qualityBoost = 1.6f,
                targetPreviewFps = 60,
                lutSizeForPreview = 33,
                colorSpace = "Rec.709",
                bitDepth = 10,
                noiseReductionPriority = false,
                profileLabel = "Interview"
            )
            else -> VideoProfileIntent(
                requiresWideGamut = false,
                impliesLogMode = false,
                recommendedHdrMode = if (strictHighEnd) "AUTO" else null,
                qualityBoost = if (strictHighEnd) 1.8f else 1.4f,
                targetPreviewFps = 60,
                lutSizeForPreview = if (strictHighEnd) 64 else 33,
                colorSpace = "Rec.709",
                bitDepth = 10,
                noiseReductionPriority = false,
                profileLabel = "Standard"
            )
        }
    }

    /**
     * Returns the list of all available profile names.
     */
    fun availableProfiles(): List<String> = listOf(
        "cinema", "log", "hdr", "hlg", "raw", "night", "interview", "standard"
    )

    /**
     * Returns the default profile name based on the quality policy.
     */
    fun defaultProfileName(): String =
        if (HighEndQualityPolicy.strictHighEndOnly) "cinema" else "standard"
}

/**
 * Video profile intent data class — runtime hints for the cinema pipeline.
 *
 * Each field represents a pipeline configuration hint derived from the user's
 * selected video profile. The pipeline reads these hints to configure color
 * science, LUT processing, HDR mode, and encoding quality.
 */
data class VideoProfileIntent(
    /** Whether wide gamut color (Rec.2020) is required for this profile. */
    val requiresWideGamut: Boolean = false,

    /** Whether LOG color mode is implied by this profile. */
    val impliesLogMode: Boolean = false,

    /** Recommended HDR mode (e.g. "HDR10", "HLG", null for none). */
    val recommendedHdrMode: String? = null,

    /** Quality boost multiplier applied to bitrate and encoding complexity. */
    val qualityBoost: Float = 1.0f,

    /** Target preview frame rate for this profile. */
    val targetPreviewFps: Int = 60,

    /** LUT cube size for preview rendering (0 = no LUT). */
    val lutSizeForPreview: Int = 64,

    /** Target color space identifier. */
    val colorSpace: String = "Rec.709",

    /** Target bit depth for encoding. */
    val bitDepth: Int = 10,

    /** Whether noise reduction should be prioritized over detail. */
    val noiseReductionPriority: Boolean = false,

    /** Human-readable label for this profile. */
    val profileLabel: String = "Standard"
) {
    /** Whether this profile activates any HDR mode. */
    val isHdrProfile: Boolean get() = recommendedHdrMode != null && recommendedHdrMode != "AUTO"

    /** Whether this profile requires 10-bit or higher output. */
    val requires10Bit: Boolean get() = bitDepth >= 10

    /** Whether this profile uses a preview LUT. */
    val usesLut: Boolean get() = lutSizeForPreview > 0

    /** Whether this is a cinema-oriented (high-quality) profile. */
    val isCinemaGrade: Boolean get() = qualityBoost >= 1.5f
}
