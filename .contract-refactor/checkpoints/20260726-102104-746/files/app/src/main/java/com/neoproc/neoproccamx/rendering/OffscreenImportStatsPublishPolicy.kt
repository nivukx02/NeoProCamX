package com.neoproc.neoproccamx.rendering

/**
 * Keeps strict offscreen recording telemetry useful for validation without publishing a StateFlow
 * update for every imported frame. The accumulator still counts every frame; only UI/health
 * publication is paced so recording hot-path CPU is reserved for import, encoder drain, and muxing.
 */
internal object OffscreenImportStatsPublishPolicy {
    const val STARTUP_UNTHROTTLED_FRAMES = 3L
    const val HEARTBEAT_FRAMES = 8L
    const val HEARTBEAT_MS = 250L

    fun shouldPublish(
        shouldEmitStats: Boolean,
        offscreenRecordingActive: Boolean,
        imported: Boolean,
        stats: VulkanImportStats,
        nowElapsedMs: Long,
        lastPublishedElapsedMs: Long
    ): Boolean {
        if (!shouldEmitStats) return false
        if (!offscreenRecordingActive) return true
        if (!imported) return true
        if (stats.sessionImportedFrames <= STARTUP_UNTHROTTLED_FRAMES) return true
        if (stats.sessionImportedFrames % HEARTBEAT_FRAMES == 0L) return true
        if (lastPublishedElapsedMs <= 0L) return true
        return nowElapsedMs - lastPublishedElapsedMs >= HEARTBEAT_MS
    }
}
