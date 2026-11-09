package com.neoproc.neoproccamx.ui.controls

/**
 * s8-f03 (CP-U3) — process-global gate for capability-aware settings enablement (disable-with-reason).
 * DEFAULT OFF: while inactive, [SettingsCapabilitySpec] never disables any option, so the settings
 * screen renders exactly as today. An owner flips it on device-side (alongside a real capability
 * report) to surface proven-impossible options as disabled. Mirrors the HighEndQualityPolicy /
 * SceneHysteresisFlag @Volatile-flag idiom.
 */
internal object SettingsCapabilityGatePolicy {
    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }

    fun isCapabilityGatingActive(): Boolean = enabled
}
