package com.neoproc.neoproccamx.ui

/**
 * Preserves the user's explicit recording cadence while a mode preset changes colour/session state.
 * Capability admission remains authoritative: this policy never substitutes 25 fps (or any other
 * fallback) when the target mode cannot support the selected cadence.
 */
internal object CameraModePresetFpsPreservationPolicy {

    data class Decision(
        val preset: CameraModePreset,
        val explicitFps: Int,
        val observedFpsAfterPreset: Int,
        val restoreRequired: Boolean
    )

    fun resolve(
        preset: CameraModePreset,
        explicitFps: Int,
        observedFpsAfterPreset: Int
    ): Decision {
        require(explicitFps > 0) { "Explicit recording FPS must be positive" }
        require(observedFpsAfterPreset > 0) { "Observed recording FPS must be positive" }
        return Decision(
            preset = preset,
            explicitFps = explicitFps,
            observedFpsAfterPreset = observedFpsAfterPreset,
            restoreRequired = observedFpsAfterPreset != explicitFps
        )
    }
}
