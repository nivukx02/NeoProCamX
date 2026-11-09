package com.neoproc.neoproccamx.ui

internal object CameraModeRailAutoSelectionPolicy {
    fun shouldScheduleAutoSelection(
        nowMs: Long,
        suppressedUntilMs: Long
    ): Boolean = nowMs >= suppressedUntilMs
}
