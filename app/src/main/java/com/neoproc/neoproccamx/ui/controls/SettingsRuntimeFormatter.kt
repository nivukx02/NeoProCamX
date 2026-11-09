package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.recording.VideoBitrateMode
import java.util.Locale

internal object SettingsRuntimeFormatter {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun formatBitrateLabel(
        bitrateMode: VideoBitrateMode,
        bitrateBitsPerSecond: Int
    ): String {
        val bitrateMbps = bitrateBitsPerSecond / 1_000_000f
        val qualitySuffix = if (
            bitrateMbps >= 250f &&
            (bitrateMode == VideoBitrateMode.CINEMA_MASTER || strictHighEndEnabled())
        ) {
            " HQ"
        } else {
            ""
        }
        return when (bitrateMode) {
            VideoBitrateMode.CUSTOM ->
                String.format(Locale.US, "%.0f Mbps%s", bitrateMbps, qualitySuffix)
            VideoBitrateMode.CINEMA_MASTER ->
                String.format(Locale.US, "MASTER %.0f Mbps%s", bitrateMbps, qualitySuffix)
            VideoBitrateMode.AUTO ->
                String.format(Locale.US, "AUTO %.0f Mbps%s", bitrateMbps, qualitySuffix)
        }
    }

    fun formatBitrateStatusLabel(
        bitrateMode: VideoBitrateMode,
        requestedBitrateBitsPerSecond: Int,
        appliedBitrateBitsPerSecond: Int
    ): String {
        val appliedLabel = formatBitrateLabel(bitrateMode, appliedBitrateBitsPerSecond)
        if (bitrateMode != VideoBitrateMode.CUSTOM) return appliedLabel

        val requestedLabel = formatBitrateLabel(bitrateMode, requestedBitrateBitsPerSecond)
        return if (requestedBitrateBitsPerSecond == appliedBitrateBitsPerSecond) {
            requestedLabel
        } else {
            "$requestedLabel target / " +
                "${formatBitrateLabel(VideoBitrateMode.CUSTOM, appliedBitrateBitsPerSecond)} applied (hardware cap)"
        }
    }

    fun isCustomBitrateEnabled(bitrateMode: VideoBitrateMode): Boolean =
        bitrateMode == VideoBitrateMode.CUSTOM
}
