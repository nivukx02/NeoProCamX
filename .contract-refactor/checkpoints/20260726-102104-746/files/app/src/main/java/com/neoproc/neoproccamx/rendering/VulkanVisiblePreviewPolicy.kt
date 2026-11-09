package com.neoproc.neoproccamx.rendering

import android.os.SystemClock
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal data class VulkanVisiblePreviewActivationPlan(
    val shouldAttemptNativePreview: Boolean,
    val reason: String,
    val retryAfterMs: Long = 0L
)

internal data class VulkanVisiblePreviewHealthDecision(
    val shouldFallback: Boolean,
    val shouldConfirmStable: Boolean = false,
    val reason: String
)

internal object VulkanVisiblePreviewPolicy {
    private const val VISIBLE_VULKAN_PREVIEW_ENABLED = false
    const val SURFACE_HANDOFF_SETTLE_MS = 64L
    const val WATCHDOG_WARMUP_MS = 300L
    const val WATCHDOG_INTERVAL_MS = 250L
    private const val FIRST_FRAME_DEADLINE_MS = 900L
    private const val MIN_IMPORTED_FRAMES_TO_CONFIRM = 2L
    private const val MAX_CONSECUTIVE_IMPORT_FAILURES = 4L
    private const val MAX_IDLE_AFTER_BIND_MS = 1_200L
    private const val LOW_THROUGHPUT_GRACE_MS = 2_500L
    private const val LOW_THROUGHPUT_MIN_FRAMES = 12L
    private const val MIN_HEALTHY_IMPORT_FPS = 12f

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun firstFrameDeadlineMs(): Long =
        if (strictHighEndEnabled()) 750L else FIRST_FRAME_DEADLINE_MS

    private fun maxConsecutiveImportFailures(): Long =
        if (strictHighEndEnabled()) 3L else MAX_CONSECUTIVE_IMPORT_FAILURES

    private fun maxIdleAfterBindMs(): Long =
        if (strictHighEndEnabled()) 1_000L else MAX_IDLE_AFTER_BIND_MS

    private fun minHealthyImportFps(): Float =
        if (strictHighEndEnabled()) 15f else MIN_HEALTHY_IMPORT_FPS

    fun resolveActivation(
        runtimeSupported: Boolean,
        hasDisplaySurface: Boolean,
        fallbackCooldownRemainingMs: Long,
        hasPresentationFeedback: Boolean
    ): VulkanVisiblePreviewActivationPlan {
        if (!VISIBLE_VULKAN_PREVIEW_ENABLED) {
            return VulkanVisiblePreviewActivationPlan(
                false,
                "Visible Vulkan preview disabled until presentation pacing is proven"
            )
        }
        if (!runtimeSupported) {
            return VulkanVisiblePreviewActivationPlan(false, "Vulkan runtime unsupported")
        }
        if (!hasDisplaySurface) {
            return VulkanVisiblePreviewActivationPlan(false, "No preview surface")
        }
        if (!hasPresentationFeedback) {
            return VulkanVisiblePreviewActivationPlan(
                shouldAttemptNativePreview = false,
                reason = "Visible Vulkan presentation feedback unavailable"
            )
        }
        if (fallbackCooldownRemainingMs > 0L) {
            return VulkanVisiblePreviewActivationPlan(
                shouldAttemptNativePreview = false,
                reason = "Visible Vulkan retry cooldown",
                retryAfterMs = fallbackCooldownRemainingMs
            )
        }
        return VulkanVisiblePreviewActivationPlan(true, "Visible Vulkan preview eligible")
    }

    fun resolveRetryDelayMs(consecutiveFallbackCount: Int): Long {
        return when {
            strictHighEndEnabled() && consecutiveFallbackCount <= 1 -> 1_500L
            strictHighEndEnabled() && consecutiveFallbackCount == 2 -> 3_500L
            strictHighEndEnabled() -> 7_000L
            consecutiveFallbackCount <= 1 -> 2_000L
            consecutiveFallbackCount == 2 -> 5_000L
            else -> 10_000L
        }
    }

    fun evaluateHealth(
        stats: VulkanImportStats,
        activationElapsedMs: Long,
        currentElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()
    ): VulkanVisiblePreviewHealthDecision {
        if (!stats.frameImportEnabled) {
            return VulkanVisiblePreviewHealthDecision(
                shouldFallback = true,
                reason = "Frame import disabled"
            )
        }
        if (!stats.previewSurfaceBound) {
            return VulkanVisiblePreviewHealthDecision(
                shouldFallback = true,
                reason = "Preview surface not bound in Vulkan runtime"
            )
        }
        if (stats.consecutiveFailedImports >= maxConsecutiveImportFailures()) {
            return VulkanVisiblePreviewHealthDecision(
                shouldFallback = true,
                reason = "Repeated Vulkan import failures"
            )
        }
        if (activationElapsedMs < WATCHDOG_WARMUP_MS) {
            return VulkanVisiblePreviewHealthDecision(
                shouldFallback = false,
                reason = "Warmup"
            )
        }
        if (
            stats.firstSuccessfulImportElapsedMs > 0L &&
            currentElapsedRealtimeMs - stats.lastSuccessfulImportElapsedMs > maxIdleAfterBindMs()
        ) {
            return VulkanVisiblePreviewHealthDecision(
                shouldFallback = true,
                reason = "Vulkan preview import stalled"
            )
        }
        if (
            activationElapsedMs >= LOW_THROUGHPUT_GRACE_MS &&
            stats.sessionImportedFrames >= LOW_THROUGHPUT_MIN_FRAMES
        ) {
            val estimatedImportFps = estimateSessionImportFps(stats)
            if (estimatedImportFps > 0f && estimatedImportFps < minHealthyImportFps()) {
                return VulkanVisiblePreviewHealthDecision(
                    shouldFallback = true,
                    reason = "Low Vulkan preview throughput"
                )
            }
        }
        if (stats.sessionImportedFrames >= MIN_IMPORTED_FRAMES_TO_CONFIRM) {
            return VulkanVisiblePreviewHealthDecision(
                shouldFallback = false,
                shouldConfirmStable = true,
                reason = "Visible Vulkan preview confirmed"
            )
        }
        if (activationElapsedMs >= firstFrameDeadlineMs() && stats.sessionImportedFrames == 0L) {
            return VulkanVisiblePreviewHealthDecision(
                shouldFallback = true,
                reason = "No imported preview frames after activation"
            )
        }
        return VulkanVisiblePreviewHealthDecision(
            shouldFallback = false,
            reason = "Healthy"
        )
    }

    private fun estimateSessionImportFps(stats: VulkanImportStats): Float {
        if (stats.sessionImportedFrames < 2L) return 0f
        val first = stats.firstSuccessfulImportElapsedMs
        val last = stats.lastSuccessfulImportElapsedMs
        if (first <= 0L || last <= first) return 0f
        val elapsedMs = (last - first).coerceAtLeast(1L)
        return stats.sessionImportedFrames * 1_000f / elapsedMs.toFloat()
    }
}
