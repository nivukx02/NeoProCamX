package com.neoproc.neoproccamx.ui

internal object ThermalCooldownEstimatePolicy {
    fun append(warningMessage: String, cooldownEstimateSec: Int?): String {
        val estimate = cooldownEstimateSec?.coerceAtLeast(0) ?: return warningMessage
        val formatted = when {
            estimate >= 60 -> {
                val minutes = estimate / 60
                val seconds = estimate % 60
                if (seconds == 0) "${minutes}m" else "${minutes}m ${seconds}s"
            }
            else -> "${estimate}s"
        }
        return "$warningMessage \u2022 Cooldown ~$formatted"
    }
}
