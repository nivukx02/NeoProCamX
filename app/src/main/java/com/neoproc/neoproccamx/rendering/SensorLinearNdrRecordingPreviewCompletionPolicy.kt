package com.neoproc.neoproccamx.rendering

/**
 * Resolves the asynchronous recorder-monitor result against the identities that still own display.
 *
 * Encoder acceptance precedes monitor presentation. A Camera2 session or render-profile transition
 * may therefore make a queued monitor task stale while it is waiting for EGL. That transition is not
 * a compositor defect: the task must retire its exact native token without emitting current-frame
 * lineage or poisoning the encoded clip. A rejection remains terminal when both identities are still
 * current, so genuine compositor failures cannot be hidden by this policy.
 */
internal object SensorLinearNdrRecordingPreviewCompletionPolicy {
    internal enum class Decision {
        EMIT_CURRENT_LINEAGE,
        RETIRE_STALE,
        FAIL_CURRENT_PRESENTATION
    }

    fun resolve(
        presentationAttempted: Boolean,
        presented: Boolean,
        cameraSessionCurrentAfterPresentation: Boolean,
        renderProfileCurrentAfterPresentation: Boolean
    ): Decision {
        if (!presentationAttempted ||
            !cameraSessionCurrentAfterPresentation ||
            !renderProfileCurrentAfterPresentation
        ) {
            return Decision.RETIRE_STALE
        }
        return if (presented) {
            Decision.EMIT_CURRENT_LINEAGE
        } else {
            Decision.FAIL_CURRENT_PRESENTATION
        }
    }

    /**
     * Ownership failure is terminal for every identity. Ordinary presentation failure is terminal
     * only for the still-current identity, while VM-fatal failures are never converted into stale
     * retirement. Suppressed failures preserve the complete diagnostic chain.
     */
    fun selectTerminalFailure(
        decision: Decision,
        presentationFailure: Throwable?,
        retirementFailure: Throwable?,
        currentRejectionFailure: () -> Throwable
    ): Throwable? {
        val failures = listOfNotNull(presentationFailure, retirementFailure)
        val fatal = failures.firstOrNull { it is VirtualMachineError || it is ThreadDeath }
        if (fatal != null) {
            failures.filter { it !== fatal }.forEach(fatal::addSuppressed)
            return fatal
        }
        if (retirementFailure != null) {
            presentationFailure
                ?.takeIf { it !== retirementFailure }
                ?.let(retirementFailure::addSuppressed)
            return retirementFailure
        }
        return if (decision == Decision.FAIL_CURRENT_PRESENTATION) {
            presentationFailure ?: currentRejectionFailure()
        } else {
            null
        }
    }
}
