package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStatusHudPolicyTest {

    @Test
    fun resolve_usesSensorStatusWhenIdle() {
        val result = RecordingStatusHudPolicy.resolve(
            recording = false,
            durationMs = 0L,
            cameraStatusText = "8192x6144 | 3840x2160 | 16:9"
        )

        assertFalse(result.timerActive)
        assertEquals("8192x6144 | 3840x2160 | 16:9", result.primaryText)
        assertEquals(0xFFF4F7FA.toInt(), result.primaryTextColor)
    }

    @Test
    fun resolve_replacesSensorStatusWithRedRecordingTimer() {
        val result = RecordingStatusHudPolicy.resolve(
            recording = true,
            durationMs = 65_240L,
            cameraStatusText = "8192x6144 | 3840x2160 | 16:9"
        )

        assertTrue(result.timerActive)
        assertEquals("REC 01:05", result.primaryText)
        assertEquals(0xFFFF3030.toInt(), result.primaryTextColor)
    }

    @Test
    fun resolve_usesPausedTimerWhenRecordingIsPaused() {
        val result = RecordingStatusHudPolicy.resolve(
            recording = true,
            paused = true,
            durationMs = 125_000L,
            cameraStatusText = "8192x6144 | 3840x2160 | 16:9"
        )

        assertTrue(result.timerActive)
        assertEquals("PAUSED 02:05", result.primaryText)
        assertEquals(0xFFB46CFF.toInt(), result.primaryTextColor)
    }
}
