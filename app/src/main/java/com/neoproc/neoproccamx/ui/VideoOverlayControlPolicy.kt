package com.neoproc.neoproccamx.ui

internal object VideoOverlayControlPolicy {

    data class State(
        val showFocusAssist: Boolean,
        val showExposureLockControl: Boolean,
        val showRightStatusRail: Boolean
    )

    fun resolve(appMode: AppMode): State {
        return when (appMode) {
            AppMode.VIDEO,
            AppMode.PRO -> State(
                showFocusAssist = true,
                showExposureLockControl = true,
                showRightStatusRail = true
            )
        }
    }
}
