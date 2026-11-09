package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
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

    @Test
    fun `recording cadence is frame aligned and resists focus pumping`() {
        val preview = LogFocusResponsivenessPolicy.resolve(
            logEnabled = true,
            hdrEnabled = true,
            strictHighEnd = true,
            recordingActive = false,
            targetFps = 60
        )
        val recording = LogFocusResponsivenessPolicy.resolve(
            logEnabled = true,
            hdrEnabled = true,
            strictHighEnd = true,
            recordingActive = true,
            targetFps = 60
        )

        assertEquals(17L, preview.updateIntervalMs)
        assertEquals(34L, recording.updateIntervalMs)
        assertTrue(recording.moveThreshold >= 0.012f)
        assertTrue(recording.reacquireThreshold >= 0.040f)
        assertEquals(1_200L, recording.retriggerIntervalMs)
        assertEquals(700L, recording.lossTimeoutMs)
    }

    @Test
    fun `recording update interval follows two camera frames at common rates`() {
        mapOf(
            24 to 84L,
            30 to 68L,
            60 to 34L,
            120 to 18L
        ).forEach { (fps, expectedIntervalMs) ->
            val recording = LogFocusResponsivenessPolicy.resolve(
                logEnabled = false,
                hdrEnabled = false,
                strictHighEnd = true,
                recordingActive = true,
                targetFps = fps
            )

            assertEquals("fps=$fps", expectedIntervalMs, recording.updateIntervalMs)
        }
    }
}
