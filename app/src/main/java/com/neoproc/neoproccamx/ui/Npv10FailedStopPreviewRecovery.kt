package com.neoproc.neoproccamx.ui

/**
 * Reopens preview after a failed recording without admitting another recording or publishing it.
 *
 * A failed producer fence still leaves accepted Images that must be retired. Once the callback
 * seal is quiescent and those leases are drained, preview no longer depends on the full-file
 * checksum audit. Keeping that seal until an 80+ GiB audit finishes makes the watchdog repeatedly
 * rebuild a camera whose input the app itself is still rejecting.
 *
 * The caller retains the failed recording state, foreground service, finalization owner, original
 * exception, and all publication/durability gates. This helper owns no Images or file descriptors.
 */
internal object Npv10FailedStopPreviewRecovery {
    suspend fun restoreBeforeAudit(
        alreadyRestored: Boolean,
        ingressQuiescent: Boolean,
        teardownStarted: () -> Boolean,
        drainAcceptedFrames: () -> Boolean,
        restorePreview: suspend () -> Unit,
        releaseIngress: () -> Unit
    ): Boolean {
        if (alreadyRestored) return true
        if (!ingressQuiescent || teardownStarted()) return false
        check(drainAcceptedFrames()) {
            "NPV10 failed-stop preview cannot reopen while accepted Images remain leased"
        }
        // Teardown may begin while the bounded writer/packing queues are being drained.
        if (teardownStarted()) return false
        restorePreview()
        if (teardownStarted()) return false
        releaseIngress()
        return true
    }
}
