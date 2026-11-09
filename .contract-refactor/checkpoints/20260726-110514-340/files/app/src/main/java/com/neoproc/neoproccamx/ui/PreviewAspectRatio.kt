package com.neoproc.neoproccamx.ui

import java.util.Locale

enum class PreviewAspectRatio(
    private val ratio: Float?,
    val label: String
) {
    AUTO(null, "AUTO"),
    SQUARE(1f, "1:1"),
    FOUR_THREE(4f / 3f, "4:3"),
    SIXTEEN_NINE(16f / 9f, "16:9");

    fun resolve(viewWidth: Int, viewHeight: Int): Float? {
        return outputRatio()
    }

    fun outputRatio(): Float? =
        ratio ?: SensorCropEngine.fullSensorAspectRatio().takeIf { it > 0f }

    fun layoutRatio(@Suppress("UNUSED_PARAMETER") isLandscape: Boolean): String? {
        val ratio = outputRatio() ?: return null
        val width = ratio
        return if (width >= 1f) {
            val major = (width * 1000).toInt()
            "$major:1000"
        } else {
            val minor = ((1f / width) * 1000).toInt()
            "1000:$minor"
        }
    }

    companion object {
        private fun defaultAspect(): PreviewAspectRatio =
            FOUR_THREE

        private fun fromLegacyName(value: String): PreviewAspectRatio? =
            when (value) {
                "FULL",
                "2.39",
                "CINEMATIC" -> AUTO
                "1:1",
                "ONE_ONE",
                "SQUARE" -> SQUARE
                "4:3",
                "FOUR_THREE" -> FOUR_THREE
                "16:9",
                "SIXTEEN_NINE" -> SIXTEEN_NINE
                else -> null
            }

        fun fromName(value: String?): PreviewAspectRatio {
            val normalized = value?.trim()?.uppercase(Locale.US) ?: return defaultAspect()
            return entries.firstOrNull { it.name == normalized }
                ?: fromLegacyName(normalized)
                ?: defaultAspect()
        }
        
        fun fromLabel(label: String?): PreviewAspectRatio {
            val normalized = label?.trim() ?: return defaultAspect()
            return entries.firstOrNull { it.label.equals(normalized, ignoreCase = true) }
                ?: fromLegacyName(normalized.uppercase(Locale.US))
                ?: defaultAspect()
        }
    }
}
