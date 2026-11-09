package com.neoproc.neoproccamx.ui

internal enum class ManualControlPresetApplication {
    RESTORE_PRO_INTENT,
    TEMPORARY_AUTO
}

/**
 * Separates durable Pro manual-control intent from the effective Camera2 state used by modes whose
 * current product contract is automatic. Temporary Auto must never rewrite the durable snapshot.
 */
internal object ManualControlPresetRuntimePolicy {
    fun applicationFor(preset: CameraModePreset): ManualControlPresetApplication =
        if (preset == CameraModePreset.PRO) {
            ManualControlPresetApplication.RESTORE_PRO_INTENT
        } else {
            ManualControlPresetApplication.TEMPORARY_AUTO
        }

    fun effectiveSnapshot(
        preset: CameraModePreset,
        proIntent: ManualControlSnapshot
    ): ManualControlSnapshot = when (applicationFor(preset)) {
        ManualControlPresetApplication.RESTORE_PRO_INTENT -> proIntent
        ManualControlPresetApplication.TEMPORARY_AUTO -> ManualControlSnapshot()
    }
}
