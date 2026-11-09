package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.ai.FocusTarget
import com.neoproc.neoproccamx.ai.FocusTargetType

enum class FocusAssistVisual {
    TAP,
    FACE,
    EYE
}

object FocusAssistVisualPolicy {
    fun resolve(
        manualFocus: FocusTarget?,
        primaryFocus: FocusTarget?
    ): FocusAssistVisual {
        if (manualFocus != null) return FocusAssistVisual.TAP
        return when (primaryFocus?.type) {
            FocusTargetType.EYE -> FocusAssistVisual.EYE
            FocusTargetType.FACE -> FocusAssistVisual.FACE
            else -> FocusAssistVisual.TAP
        }
    }
}
