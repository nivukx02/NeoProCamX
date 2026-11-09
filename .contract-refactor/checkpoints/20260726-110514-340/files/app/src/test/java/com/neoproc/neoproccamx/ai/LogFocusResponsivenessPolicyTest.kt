package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class LogFocusResponsivenessPolicyTest {

    @Test
    fun resolve_makesLogPreviewMoreResponsiveThanStandardPreview() {
        val base = LogFocusResponsivenessPolicy.resolve(
            logEnabled = false,
            hdrEnabled = false,
            strictHighEnd = false
        )
        val log = LogFocusResponsivenessPolicy.resolve(
            logEnabled = true,
            hdrEnabled = false,
            strictHighEnd = false
        )

        assertTrue(log.updateIntervalMs < base.updateIntervalMs)
        assertTrue(log.retriggerIntervalMs < base.retriggerIntervalMs)
        assertTrue(log.moveThreshold < base.moveThreshold)
        assertTrue(log.reacquireThreshold < base.reacquireThreshold)
    }
}
