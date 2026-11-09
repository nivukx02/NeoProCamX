package com.neoproc.neoproccamx.rendering

internal class VulkanImportStatsAccumulator(
    initialStats: VulkanImportStats = VulkanImportStats(),
    private val capabilityRefreshIntervalFrames: Long = 60L,
    private val configRefreshFailureThreshold: Long = 2L
) {
    data class Update(
        val stats: VulkanImportStats,
        val shouldEmitStats: Boolean,
        val shouldRefreshCapabilities: Boolean = false,
        val shouldForceConfigRefresh: Boolean = false
    )

    private val lock = Any()

    var current: VulkanImportStats = initialStats
        get() = synchronized(lock) { field }
        private set

    fun replace(stats: VulkanImportStats): Update = synchronized(lock) {
        current = stats
        Update(stats = current, shouldEmitStats = true)
    }

    fun mutate(transform: (VulkanImportStats) -> VulkanImportStats): Update = synchronized(lock) {
        replace(transform(current))
    }

    fun setFrameImportEnabled(enabled: Boolean): Update = synchronized(lock) {
        replace(
            if (enabled) {
                current.copy(frameImportEnabled = true)
            } else {
                current.copy(
                    frameImportEnabled = false,
                    sessionImportedFrames = 0L,
                    sessionFailedImports = 0L,
                    consecutiveFailedImports = 0L,
                    lastSuccessfulImportElapsedMs = 0L,
                    firstSuccessfulImportElapsedMs = 0L
                )
            }
        )
    }

    fun resetSession(elapsedMs: Long): Update = synchronized(lock) {
        replace(
            current.copy(
                sessionImportedFrames = 0L,
                sessionFailedImports = 0L,
                consecutiveFailedImports = 0L,
                previewSurfaceBound = false,
                encoderSurfaceBound = false,
                offscreenAhbEglEncoderActive = false,
                offscreenEglEncoderSurfaceBound = false,
                offscreenEglSubmittedFrames = 0L,
                offscreenEglFailedFrames = 0L,
                offscreenEglPresentationTimeSet = false,
                lastSuccessfulImportElapsedMs = 0L,
                firstSuccessfulImportElapsedMs = 0L,
                lastSessionResetElapsedMs = elapsedMs
            )
        )
    }

    fun recordSuccessfulImport(
        width: Int,
        height: Int,
        format: Int,
        elapsedMs: Long
    ): Update = synchronized(lock) {
        val updated = current.copy(
            importedFrames = current.importedFrames + 1,
            sessionImportedFrames = current.sessionImportedFrames + 1,
            consecutiveFailedImports = 0L,
            lastImportedWidth = width,
            lastImportedHeight = height,
            lastImportedFormat = format,
            lastSuccessfulImportElapsedMs = elapsedMs,
            firstSuccessfulImportElapsedMs =
                current.firstSuccessfulImportElapsedMs.takeIf { it > 0L } ?: elapsedMs
        )
        current = updated
        Update(
            stats = updated,
            // The preview watchdog uses the latest successful timestamp. Throttling
            // successful emissions makes a live GPU stream look stalled at low FPS.
            shouldEmitStats = true,
            shouldRefreshCapabilities =
                updated.sessionImportedFrames % capabilityRefreshIntervalFrames == 0L
        )
    }

    fun recordFailedImport(): Update = synchronized(lock) {
        val nextConsecutiveFailures = current.consecutiveFailedImports + 1
        val updated = current.copy(
            failedImports = current.failedImports + 1,
            sessionFailedImports = current.sessionFailedImports + 1,
            consecutiveFailedImports = nextConsecutiveFailures
        )
        current = updated
        Update(
            stats = updated,
            shouldEmitStats = true,
            shouldForceConfigRefresh = nextConsecutiveFailures >= configRefreshFailureThreshold
        )
    }
}
