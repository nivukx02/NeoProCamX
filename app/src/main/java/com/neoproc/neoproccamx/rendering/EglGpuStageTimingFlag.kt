package com.neoproc.neoproccamx.rendering

/**
 * Opt-in diagnostic timing for the offscreen EGL record stages.
 *
 * Timer queries are intentionally disabled for normal production recording. Device qualification can
 * enable them for one recording session to separate decode, grade, and history GPU cost; the native
 * bridge disables them fail-open on the first unsupported or erroneous query operation.
 */
object EglGpuStageTimingFlag {

    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
