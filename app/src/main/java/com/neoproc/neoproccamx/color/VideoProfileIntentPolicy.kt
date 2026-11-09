package com.neoproc.neoproccamx.color

/**
 * Maps a selected video profile to runtime intent hints.
 */
object VideoProfileIntentPolicy {
    fun resolve(profileName: String): VideoProfileIntent =
        when (profileName.normalizedProfileKey()) {
            "cinema" -> VideoProfileIntent(
                requiresWideGamut = true,
                impliesLogMode = true,
                recommendedHdrMode = "NDR10+",
                qualityBoost = 2.0f,
                targetPreviewFps = 60,
                lutSizeForPreview = 128
            )
            "log", "nlog", "nlogbase", "nlogshadow", "nloghighlight", "nlogcinema" -> VideoProfileIntent(
                requiresWideGamut = true,
                impliesLogMode = true,
                recommendedHdrMode = null,
                qualityBoost = 1.5f,
                targetPreviewFps = 25,
                lutSizeForPreview = 64
            )
            "hdr", "ndr10", "ndr10plus", "ndr10native" -> VideoProfileIntent(
                requiresWideGamut = true,
                impliesLogMode = false,
                recommendedHdrMode = "NDR10+",
                qualityBoost = 1.8f,
                targetPreviewFps = 25,
                lutSizeForPreview = 64
            )
            "nivukx", "nivukxvision" -> VideoProfileIntent(
                requiresWideGamut = true,
                impliesLogMode = false,
                recommendedHdrMode = "NIVUKXVISION",
                qualityBoost = 2.0f,
                targetPreviewFps = 25,
                lutSizeForPreview = 128
            )
            else -> VideoProfileIntent()
        }

    private fun String.normalizedProfileKey(): String =
        trim().lowercase().replace(Regex("[^a-z0-9]+"), "")
}

data class VideoProfileIntent(
    val requiresWideGamut: Boolean = false,
    val impliesLogMode: Boolean = false,
    val recommendedHdrMode: String? = null,
    val qualityBoost: Float = 1.0f,
    val targetPreviewFps: Int = 25,
    val lutSizeForPreview: Int = 64
)
