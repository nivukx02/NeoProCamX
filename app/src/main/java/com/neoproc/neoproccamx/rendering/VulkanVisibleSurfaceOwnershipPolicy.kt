package com.neoproc.neoproccamx.rendering

internal object VulkanVisibleSurfaceOwnershipPolicy {
    fun mayBindVulkanVisibleSurface(
        requestNativePreview: Boolean,
        cameraOwnsSurface: Boolean,
        cameraReleaseConfirmed: Boolean,
        replacementSurfaceReady: Boolean
    ): Boolean =
        requestNativePreview &&
            !cameraOwnsSurface &&
            cameraReleaseConfirmed &&
            replacementSurfaceReady

    fun mustKeepCamera2VisiblePreview(
        requestNativePreview: Boolean,
        cameraReleaseConfirmed: Boolean
    ): Boolean =
        requestNativePreview && !cameraReleaseConfirmed
}
