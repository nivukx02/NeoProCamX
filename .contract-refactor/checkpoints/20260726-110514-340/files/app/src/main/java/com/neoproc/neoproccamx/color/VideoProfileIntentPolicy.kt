package com.neoproc.neoproccamx.color

/**
 * Maps a selected video profile to runtime intent hints.
 */
object VideoProfileIntentPolicy {
    fun resolve(profileName: String): VideoProfileIntent =
        when (profileName.trim().lowercase()) {
            "cinema" -> VideoProfileIntent(
                requiresWideGamut = true,
                impliesLogMode = true,
                recommendedHdrMode = "NDR10+",
                qualityBoost = 2.0f,
                targetPreviewFps = 60,
                lutSizeForPreview = 128
            )
            "log" -> VideoProfileIntent(
                requiresWideGamut = true,
                impliesLogMode = true,
                recommendedHdrMode = "NDR10+",
                qualityBoost = 1.5f,
                targetPreviewFps = 25,
                lutSizeForPreview = 64
            )
            "hdr" -> VideoProfileIntent(
                requiresWideGamut = true,
                impliesLogMode = false,
                recommendedHdrMode = "NDR10+",
                qualityBoost = 1.8f,
                targetPreviewFps = 25,
                lutSizeForPreview = 64
            )
            else -> VideoProfileIntent()
        }
}

data class VideoProfileIntent(
    val requiresWideGamut: Boolean = false,
    val impliesLogMode: Boolean = false,
    val recommendedHdrMode: String? = null,
    val qualityBoost: Float = 1.0f,
    val targetPreviewFps: Int = 25,
    val lutSizeForPreview: Int = 64
)
