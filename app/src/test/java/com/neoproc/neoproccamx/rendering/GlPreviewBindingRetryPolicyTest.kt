package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlPreviewBindingRetryPolicyTest {
    @Test
    fun followUpPrefixesNeverCreateNewRetryReasons() {
        assertEquals(
            "pq-recording-blocked",
            GlPreviewBindingRetryPolicy.canonicalReason(
                "follow-up-follow-up-pq-recording-blocked"
            )
        )
    }

    @Test
    fun retriesStopAtTheConfiguredLimit() {
        assertTrue(GlPreviewBindingRetryPolicy.shouldSchedule(true, attempts = 3, maxRetries = 4))
        assertFalse(GlPreviewBindingRetryPolicy.shouldSchedule(true, attempts = 4, maxRetries = 4))
        assertFalse(GlPreviewBindingRetryPolicy.shouldSchedule(false, attempts = 0, maxRetries = 4))
    }

    @Test
    fun activeCompositorEndsFollowUpRecovery() {
        assertFalse(
            GlPreviewBindingRetryPolicy.shouldContinue(
                shouldComposite = true,
                sdrCompositorActive = true,
                pqCompositorActive = false,
                attempts = 1,
                maxRetries = 4
            )
        )
    }

    @Test
    fun cameraToEglTransitionEndsFollowUpRecoveryUntilItsOwnCallbackCompletes() {
        assertFalse(
            GlPreviewBindingRetryPolicy.shouldContinue(
                shouldComposite = true,
                sdrCompositorActive = false,
                pqCompositorActive = false,
                transitionInProgress = true,
                attempts = 1,
                maxRetries = 4
            )
        )
    }
}
