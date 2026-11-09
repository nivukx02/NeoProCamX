package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanVisibleSurfaceOwnershipPolicyTest {
    @Test
    fun `visible Vulkan binding is denied until Camera2 release is confirmed`() {
        assertFalse(
            VulkanVisibleSurfaceOwnershipPolicy.mayBindVulkanVisibleSurface(
                requestNativePreview = true,
                cameraOwnsSurface = true,
                cameraReleaseConfirmed = false,
                replacementSurfaceReady = false
            )
        )
        assertFalse(
            VulkanVisibleSurfaceOwnershipPolicy.mayBindVulkanVisibleSurface(
                requestNativePreview = true,
                cameraOwnsSurface = false,
                cameraReleaseConfirmed = false,
                replacementSurfaceReady = true
            )
        )
        assertTrue(
            VulkanVisibleSurfaceOwnershipPolicy.mayBindVulkanVisibleSurface(
                requestNativePreview = true,
                cameraOwnsSurface = false,
                cameraReleaseConfirmed = true,
                replacementSurfaceReady = true
            )
        )
    }

    @Test
    fun `direct preview remains eligible while a visible handoff is incomplete`() {
        assertTrue(
            VulkanVisibleSurfaceOwnershipPolicy.mustKeepCamera2VisiblePreview(
                requestNativePreview = true,
                cameraReleaseConfirmed = false
            )
        )
        assertFalse(
            VulkanVisibleSurfaceOwnershipPolicy.mustKeepCamera2VisiblePreview(
                requestNativePreview = true,
                cameraReleaseConfirmed = true
            )
        )
    }
}
