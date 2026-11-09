package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** s7-f12 / CP9B — pure truthful zero-copy/fallback resolver. Hand-verifiable; never fakes a pass. */
class VulkanImportTelemetryResolverTest {

    @Test
    fun tokenAbsentOrUnavailableYieldsNoZeroCopyClaim() {
        val absent = VulkanImportTelemetryResolver.resolve("gpuEnhancement=ready foo=bar", VulkanImportStats())
        assertFalse(absent.zeroCopyAvailable)
        assertFalse(absent.zeroCopyProven)
        assertFalse(absent.fallbackAdvised)

        val unavailable = VulkanImportTelemetryResolver.resolve(
            "gpu=ok zeroCopyLiveImport=unavailable enc=bound", VulkanImportStats()
        )
        assertFalse(unavailable.zeroCopyAvailable)
        assertFalse(unavailable.zeroCopyProven)
    }

    @Test
    fun availableTokenIsParsedOnlyAtTokenBoundary() {
        assertTrue(VulkanImportTelemetryResolver.parseZeroCopyLiveImportAvailable("a zeroCopyLiveImport=available b"))
        assertTrue(VulkanImportTelemetryResolver.parseZeroCopyLiveImportAvailable("zeroCopyLiveImport=available"))
        // No leading boundary => not spoofable by a substring.
        assertFalse(VulkanImportTelemetryResolver.parseZeroCopyLiveImportAvailable("xzeroCopyLiveImport=available"))
    }

    @Test
    fun zeroCopyProvenRequiresAvailableTokenPlusRealImportedFrame() {
        val summary = "zeroCopyLiveImport=available"
        assertFalse(
            VulkanImportTelemetryResolver.resolve(
                summary, VulkanImportStats(frameImportEnabled = true, sessionImportedFrames = 0L)
            ).zeroCopyProven
        )
        assertTrue(
            VulkanImportTelemetryResolver.resolve(
                summary, VulkanImportStats(frameImportEnabled = true, sessionImportedFrames = 1L)
            ).zeroCopyProven
        )
        assertFalse(
            VulkanImportTelemetryResolver.resolve(
                summary, VulkanImportStats(frameImportEnabled = false, sessionImportedFrames = 5L)
            ).zeroCopyProven
        )
    }

    @Test
    fun fallbackAdvisedOnlyAtConsecutiveFailureThresholdWhenEnabled() {
        assertFalse(
            VulkanImportTelemetryResolver.resolve(
                "", VulkanImportStats(frameImportEnabled = true, consecutiveFailedImports = 9L)
            ).fallbackAdvised
        )
        assertTrue(
            VulkanImportTelemetryResolver.resolve(
                "", VulkanImportStats(frameImportEnabled = true, consecutiveFailedImports = 10L)
            ).fallbackAdvised
        )
        // Disabled import path never advises a fallback regardless of failures.
        assertFalse(
            VulkanImportTelemetryResolver.resolve(
                "", VulkanImportStats(frameImportEnabled = false, consecutiveFailedImports = 50L)
            ).fallbackAdvised
        )
    }
}
