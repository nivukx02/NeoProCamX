package com.neoproc.neoproccamx.ui.controls

import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

private fun strictHighEndEnabled(): Boolean =
    HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

internal data class SettingsRuntimeViews(
    val bitrateValue: TextView,
    val bitrateSeekBar: SeekBar,
    val strictHighEnd: Boolean = strictHighEndEnabled()
)

internal data class SettingsScreenContent(
    val rootView: View,
    val runtimeViews: SettingsRuntimeViews,
    val strictHighEnd: Boolean = strictHighEndEnabled()
)

internal data class SettingsSectionBuildResult(
    val view: View,
    val runtimeViews: SettingsRuntimeViews? = null,
    val strictHighEnd: Boolean = strictHighEndEnabled()
)
