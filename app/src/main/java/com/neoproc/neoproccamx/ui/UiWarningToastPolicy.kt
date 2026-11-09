package com.neoproc.neoproccamx.ui

internal data class UiWarningToastDecision(
    val showToast: Boolean,
    val lastShownWarning: String?
)

internal object UiWarningToastPolicy {
    fun resolve(
        nextWarning: String?,
        lastShownWarning: String?
    ): UiWarningToastDecision {
        val normalizedWarning = nextWarning?.takeIf { it.isNotBlank() }
        if (normalizedWarning == null) {
            return UiWarningToastDecision(showToast = false, lastShownWarning = null)
        }
        return UiWarningToastDecision(
            showToast = normalizedWarning != lastShownWarning,
            lastShownWarning = normalizedWarning
        )
    }
}
