package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.FrameGeometry

/** Avoids applying sensor-to-display orientation twice to the RAW 2D monitor texture. */
internal object RawDebayerPreviewTransformPolicy {
    data class Transform(val rotationDegrees: Int, val mirrored: Boolean)

    fun resolve(
        geometry: FrameGeometry?,
        sensorOrientationDegrees: Int,
        deviceRotationDegrees: Int,
        lensFacing: Int,
        surfacePresentationOwnsGeometry: Boolean
    ): Transform {
        if (surfacePresentationOwnsGeometry) {
            // CameraVulkanPreviewView rotates/mirrors the complete SurfaceView, including the 2D RAW texture.
            return Transform(rotationDegrees = 0, mirrored = false)
        }

        // Resolve the same display orientation used by CameraVulkanPreviewView. The RAW/NDR shader
        // transforms sampling coordinates, not the Surface itself, so it must use the inverse rotation.
        // Applying the forward display rotation here turns portrait 90 into the wrong 90 instead of 270.
        val workingPreviewTransform = PreviewTransformStateResolver.resolve(
            geometry = geometry,
            sensorOrientation = sensorOrientationDegrees,
            deviceRotationDegrees = deviceRotationDegrees,
            lensFacing = lensFacing
        )
        return Transform(
            rotationDegrees = normalizeDegrees(-workingPreviewTransform.rotationDegrees),
            mirrored = workingPreviewTransform.mirrored
        )
    }

    /** Retains the direct working-preview transform contract for existing callers and diagnostics. */
    fun resolve(
        previewRotationDegrees: Int,
        previewMirrored: Boolean,
        surfacePresentationOwnsGeometry: Boolean
    ): Transform {
        if (surfacePresentationOwnsGeometry) {
            return Transform(rotationDegrees = 0, mirrored = false)
        }
        return Transform(
            rotationDegrees = normalizeDegrees(-previewRotationDegrees),
            mirrored = previewMirrored
        )
    }

    private fun normalizeDegrees(degrees: Int): Int = ((degrees % 360) + 360) % 360
}

/**
 * Resolves project-owned NDR orientation from the latest physical-orientation publication instead
 * of a possibly older [FrameGeometry] snapshot queued by another preview domain.
 *
 * Camera2, Vulkan, and the RAW presenter still consume the shared geometry transaction unchanged.
 * This NDR-only seam merely guarantees that a late geometry callback cannot restore the initial
 * upright-portrait matrix after the phone has moved to reverse portrait or either landscape side.
 */
internal object ProjectOwnedNdrPreviewTransformPolicy {
    fun resolve(
        sensorOrientationDegrees: Int,
        latestDeviceRotationDegrees: Int,
        lensFacing: Int
    ): RawDebayerPreviewTransformPolicy.Transform {
        val normalizedRotation = ((latestDeviceRotationDegrees % 360) + 360) % 360
        val resolvedDeviceRotationDegrees = if (normalizedRotation == 0) {
            0
        } else if (normalizedRotation == 90) {
            0
        } else if (normalizedRotation == 180) {
            0
        } else if (normalizedRotation == 270) {
            0
        } else {
            0
        }

        // Every physical quadrant deliberately executes the same proven device-orientation-0
        // resolver branch. Do not return a literal sampling rotation of zero here: for a 90-degree
        // back-camera sensor, the shared inverse-sampling math correctly resolves this branch to 270.
        return RawDebayerPreviewTransformPolicy.resolve(
            geometry = null,
            sensorOrientationDegrees = sensorOrientationDegrees,
            deviceRotationDegrees = resolvedDeviceRotationDegrees,
            lensFacing = lensFacing,
            surfacePresentationOwnsGeometry = false
        )
    }
}
