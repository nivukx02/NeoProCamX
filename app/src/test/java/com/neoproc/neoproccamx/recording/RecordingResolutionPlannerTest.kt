package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingResolutionPlannerTest {
    @Test
    fun `sanitize requested size normalizes orientation while preserving full sensor request`() {
        val sanitized = RecordingResolutionPlanner.sanitizeRequestedDimensions(
            requestedWidth = 3000,
            requestedHeight = 9000
        )

        assertEquals(RecordingDimensions(9000, 3000), sanitized)
    }

    @Test
    fun `sanitize requested size preserves selected full hd in strict high-end mode`() {
        val sanitized = RecordingResolutionPlanner.sanitizeRequestedDimensions(
            requestedWidth = 1920,
            requestedHeight = 1080
        )

        assertEquals(RecordingDimensions(1920, 1080), sanitized)
    }

    @Test
    fun `build size candidates keeps requested size first and deduplicated`() {
        val candidates = RecordingResolutionPlanner.buildSizeCandidates(
            requested = RecordingDimensions(1080, 1920),
            supportedCameraSizes = listOf(
                RecordingDimensions(1920, 1080),
                RecordingDimensions(2560, 1440),
                RecordingDimensions(1920, 1080)
            )
        )

        assertEquals(RecordingDimensions(1920, 1080), candidates.first())
        assertEquals(1, candidates.count { it == RecordingDimensions(1920, 1080) })
    }

    @Test
    fun `build size candidates prefers closest aspect from supported camera sizes`() {
        val candidates = RecordingResolutionPlanner.buildSizeCandidates(
            requested = RecordingDimensions(1000, 1000),
            supportedCameraSizes = listOf(
                RecordingDimensions(3840, 2160),
                RecordingDimensions(1440, 1080),
                RecordingDimensions(1920, 1080)
            )
        )

        assertTrue(candidates.indexOf(RecordingDimensions(1440, 1080)) <
            candidates.indexOf(RecordingDimensions(1920, 1080)))
    }

    @Test
    fun `build size candidates prefers lower delivery size before larger same-aspect fallback`() {
        val candidates = RecordingResolutionPlanner.buildSizeCandidates(
            requested = RecordingDimensions(2560, 1440),
            supportedCameraSizes = listOf(
                RecordingDimensions(3840, 2160),
                RecordingDimensions(1920, 1080),
                RecordingDimensions(1280, 720)
            )
        )

        assertTrue(candidates.indexOf(RecordingDimensions(1920, 1080)) <
            candidates.indexOf(RecordingDimensions(3840, 2160)))
    }

    @Test
    fun `resolution label maps common tiers and falls back to dimensions`() {
        assertEquals("8K", RecordingResolutionPlanner.resolutionLabel(7680, 4320))
        assertEquals("4K", RecordingResolutionPlanner.resolutionLabel(3840, 2160))
        assertEquals("2K", RecordingResolutionPlanner.resolutionLabel(2048, 1080))
        assertEquals("1000x700", RecordingResolutionPlanner.resolutionLabel(1000, 700))
    }
}
