package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffscreenImportStatsPublishPolicyTest {

    @Test
    fun `non offscreen imports publish every requested stats update`() {
        val stats = stats(importedFrames = 40L)

        assertTrue(
            OffscreenImportStatsPublishPolicy.shouldPublish(
                shouldEmitStats = true,
                offscreenRecordingActive = false,
                imported = true,
                stats = stats,
                nowElapsedMs = 1_000L,
                lastPublishedElapsedMs = 900L
            )
        )
    }

    @Test
    fun `offscreen startup proof publishes first frames immediately`() {
        for (frame in 1L..OffscreenImportStatsPublishPolicy.STARTUP_UNTHROTTLED_FRAMES) {
            assertTrue(
                OffscreenImportStatsPublishPolicy.shouldPublish(
                    shouldEmitStats = true,
                    offscreenRecordingActive = true,
                    imported = true,
                    stats = stats(importedFrames = frame),
                    nowElapsedMs = 1_000L + frame,
                    lastPublishedElapsedMs = 1_000L
                )
            )
        }
    }

    @Test
    fun `offscreen steady state publishes only heartbeat frames or elapsed heartbeat`() {
        assertFalse(
            OffscreenImportStatsPublishPolicy.shouldPublish(
                shouldEmitStats = true,
                offscreenRecordingActive = true,
                imported = true,
                stats = stats(importedFrames = 4L),
                nowElapsedMs = 1_040L,
                lastPublishedElapsedMs = 1_000L
            )
        )

        assertTrue(
            OffscreenImportStatsPublishPolicy.shouldPublish(
                shouldEmitStats = true,
                offscreenRecordingActive = true,
                imported = true,
                stats = stats(importedFrames = OffscreenImportStatsPublishPolicy.HEARTBEAT_FRAMES),
                nowElapsedMs = 1_320L,
                lastPublishedElapsedMs = 1_040L
            )
        )

        assertTrue(
            OffscreenImportStatsPublishPolicy.shouldPublish(
                shouldEmitStats = true,
                offscreenRecordingActive = true,
                imported = true,
                stats = stats(importedFrames = 11L),
                nowElapsedMs = 1_300L,
                lastPublishedElapsedMs = 1_000L
            )
        )
    }

    @Test
    fun `offscreen failures always publish for validation and recovery`() {
        assertTrue(
            OffscreenImportStatsPublishPolicy.shouldPublish(
                shouldEmitStats = true,
                offscreenRecordingActive = true,
                imported = false,
                stats = stats(importedFrames = 40L),
                nowElapsedMs = 1_010L,
                lastPublishedElapsedMs = 1_000L
            )
        )
    }

    @Test
    fun `stats updates that did not request emit stay silent`() {
        assertFalse(
            OffscreenImportStatsPublishPolicy.shouldPublish(
                shouldEmitStats = false,
                offscreenRecordingActive = true,
                imported = false,
                stats = stats(importedFrames = 40L),
                nowElapsedMs = 2_000L,
                lastPublishedElapsedMs = 1_000L
            )
        )
    }

    private fun stats(importedFrames: Long): VulkanImportStats =
        VulkanImportStats(
            frameImportEnabled = true,
            encoderSurfaceBound = true,
            offscreenAhbEglEncoderActive = true,
            offscreenEglEncoderSurfaceBound = true,
            importedFrames = importedFrames,
            sessionImportedFrames = importedFrames,
            offscreenEglSubmittedFrames = importedFrames,
            firstSuccessfulImportElapsedMs = 1_000L,
            lastSuccessfulImportElapsedMs = 1_000L + importedFrames * 40L
        )
}
