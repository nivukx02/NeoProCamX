package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VulkanVisiblePreviewPolicyTest {

    @Test
    fun `camera view model keeps direct preview until native presentation pacing is proven`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()

        assertTrue(source.contains("hasPresentationFeedback = surface != null"))
        assertTrue(source.contains("startNativePreviewWatchdog(surface)"))
    }

    @Test
    fun `activation policy keeps native visible preview disabled until presentation pacing is proven`() {
        assertFalse(
            VulkanVisiblePreviewPolicy.resolveActivation(
                runtimeSupported = true,
                hasDisplaySurface = true,
                fallbackCooldownRemainingMs = 0L,
                hasPresentationFeedback = true
            ).shouldAttemptNativePreview
        )
        val source = File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanVisiblePreviewPolicy.kt")
            .readText()
        assertTrue(source.contains("VISIBLE_VULKAN_PREVIEW_ENABLED"))
        assertFalse(
            VulkanVisiblePreviewPolicy.resolveActivation(
                runtimeSupported = false,
                hasDisplaySurface = true,
                fallbackCooldownRemainingMs = 0L,
                hasPresentationFeedback = true
            ).shouldAttemptNativePreview
        )
        assertFalse(
            VulkanVisiblePreviewPolicy.resolveActivation(
                runtimeSupported = true,
                hasDisplaySurface = true,
                fallbackCooldownRemainingMs = 1_000L,
                hasPresentationFeedback = true
            ).shouldAttemptNativePreview
        )
    }

    @Test
    fun `activation policy keeps direct preview when native presentation feedback is unavailable`() {
        val decision = VulkanVisiblePreviewPolicy.resolveActivation(
            runtimeSupported = true,
            hasDisplaySurface = true,
            fallbackCooldownRemainingMs = 0L,
            hasPresentationFeedback = false
        )

        assertFalse(decision.shouldAttemptNativePreview)
    }

    @Test
    fun `retry delay escalates after repeated native preview fallbacks`() {
        assertTrue(VulkanVisiblePreviewPolicy.resolveRetryDelayMs(1) < VulkanVisiblePreviewPolicy.resolveRetryDelayMs(2))
        assertTrue(VulkanVisiblePreviewPolicy.resolveRetryDelayMs(2) < VulkanVisiblePreviewPolicy.resolveRetryDelayMs(3))
    }

    @Test
    fun `surface handoff waits for camera producer disconnect before vulkan binding`() {
        assertTrue(VulkanVisiblePreviewPolicy.SURFACE_HANDOFF_SETTLE_MS >= 16L)
    }

    @Test
    fun `health policy falls back when visible Vulkan preview never imports frames`() {
        val decision = VulkanVisiblePreviewPolicy.evaluateHealth(
            stats = VulkanImportStats(
                importedFrames = 10L,
                failedImports = 0L,
                sessionImportedFrames = 0L,
                sessionFailedImports = 0L,
                consecutiveFailedImports = 0L,
                frameImportEnabled = true,
                previewSurfaceBound = true
            ),
            activationElapsedMs = 1_000L,
            currentElapsedRealtimeMs = 10_000L
        )

        assertTrue(decision.shouldFallback)
    }

    @Test
    fun `health policy stays healthy when imported frames continue and surface remains bound`() {
        val decision = VulkanVisiblePreviewPolicy.evaluateHealth(
            stats = VulkanImportStats(
                importedFrames = 18L,
                failedImports = 1L,
                sessionImportedFrames = 3L,
                sessionFailedImports = 1L,
                consecutiveFailedImports = 0L,
                frameImportEnabled = true,
                previewSurfaceBound = true,
                lastSuccessfulImportElapsedMs = 9_800L,
                firstSuccessfulImportElapsedMs = 9_200L
            ),
            activationElapsedMs = VulkanVisiblePreviewPolicy.WATCHDOG_WARMUP_MS + 100L,
            currentElapsedRealtimeMs = 10_000L
        )

        assertFalse(decision.shouldFallback)
        assertTrue(decision.shouldConfirmStable)
    }

    @Test
    fun `health policy falls back after repeated import failures`() {
        val decision = VulkanVisiblePreviewPolicy.evaluateHealth(
            stats = VulkanImportStats(
                importedFrames = 20L,
                failedImports = 9L,
                sessionImportedFrames = 1L,
                sessionFailedImports = 5L,
                consecutiveFailedImports = 6L,
                frameImportEnabled = true,
                previewSurfaceBound = true,
                lastSuccessfulImportElapsedMs = 9_900L,
                firstSuccessfulImportElapsedMs = 9_400L
            ),
            activationElapsedMs = VulkanVisiblePreviewPolicy.WATCHDOG_WARMUP_MS + 100L,
            currentElapsedRealtimeMs = 10_000L
        )

        assertTrue(decision.shouldFallback)
    }

    @Test
    fun `health policy falls back when preview import stalls after first success`() {
        val decision = VulkanVisiblePreviewPolicy.evaluateHealth(
            stats = VulkanImportStats(
                importedFrames = 22L,
                failedImports = 2L,
                sessionImportedFrames = 1L,
                sessionFailedImports = 1L,
                consecutiveFailedImports = 0L,
                frameImportEnabled = true,
                previewSurfaceBound = true,
                lastSuccessfulImportElapsedMs = 8_000L,
                firstSuccessfulImportElapsedMs = 7_900L
            ),
            activationElapsedMs = 1_400L,
            currentElapsedRealtimeMs = 10_000L
        )

        assertTrue(decision.shouldFallback)
    }

    @Test
    fun `health policy falls back when sustained import fps is too low`() {
        val decision = VulkanVisiblePreviewPolicy.evaluateHealth(
            stats = VulkanImportStats(
                importedFrames = 48L,
                failedImports = 2L,
                sessionImportedFrames = 20L,
                sessionFailedImports = 2L,
                consecutiveFailedImports = 0L,
                frameImportEnabled = true,
                previewSurfaceBound = true,
                lastSuccessfulImportElapsedMs = 9_800L,
                firstSuccessfulImportElapsedMs = 7_000L
            ),
            activationElapsedMs = 3_000L,
            currentElapsedRealtimeMs = 10_000L
        )

        assertTrue(decision.shouldFallback)
    }
}
