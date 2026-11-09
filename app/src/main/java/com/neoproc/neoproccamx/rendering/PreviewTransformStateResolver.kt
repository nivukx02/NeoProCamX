package com.neoproc.neoproccamx.rendering

import android.hardware.camera2.CameraCharacteristics
import com.neoproc.neoproccamx.camera.FrameGeometry
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.utils.OrientationManager

internal data class PreviewTransformState(
    val rotationDegrees: Int,
    val mirrored: Boolean
)

internal object PreviewTransformStateResolver {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun resolve(
        geometry: FrameGeometry?,
        sensorOrientation: Int,
        deviceRotationDegrees: Int,
        lensFacing: Int
    ): PreviewTransformState {
        val rawRotationDegrees = geometry?.previewRotationDegrees
            ?: OrientationManager.getSensorToOutputRotation(
                sensorOrientation = sensorOrientation,
                deviceRotationDegrees = deviceRotationDegrees,
                lensFacing = lensFacing
            )
        val rotationDegrees = OrientationManager.normalizeDegrees(rawRotationDegrees)
        return PreviewTransformState(
            rotationDegrees = rotationDegrees,
            mirrored = (geometry?.previewMirrored
                ?: (lensFacing == CameraCharacteristics.LENS_FACING_FRONT)
                ) || (strictHighEndEnabled() && geometry?.lensFacing == CameraCharacteristics.LENS_FACING_FRONT)
        )
    }
}
