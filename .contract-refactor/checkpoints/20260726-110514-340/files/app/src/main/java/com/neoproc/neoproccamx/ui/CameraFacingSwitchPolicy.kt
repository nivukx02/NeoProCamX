package com.neoproc.neoproccamx.ui

import android.hardware.camera2.CameraCharacteristics
import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal data class CameraFacingSwitchPlan(
    val targetFacing: Int,
    val shouldProceed: Boolean,
    val shouldRestartCamera: Boolean,
    val blockedReason: String? = null
)

internal object CameraFacingSwitchPolicy {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun buildPlan(
        currentFacing: Int,
        hasTargetFacing: Boolean,
        isRecordingOrStopping: Boolean,
        cameraState: CameraState
    ): CameraFacingSwitchPlan {
        val targetFacing = if (currentFacing == CameraCharacteristics.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        if (isRecordingOrStopping) {
            return CameraFacingSwitchPlan(
                targetFacing = targetFacing,
                shouldProceed = false,
                shouldRestartCamera = false,
                blockedReason = "recording"
            )
        }
        if (!hasTargetFacing) {
            return CameraFacingSwitchPlan(
                targetFacing = targetFacing,
                shouldProceed = false,
                shouldRestartCamera = false,
                blockedReason = "missing_target_facing"
            )
        }
        return CameraFacingSwitchPlan(
            targetFacing = targetFacing,
            shouldProceed = true,
            shouldRestartCamera =
                strictHighEndEnabled() || CameraModeSwitchPolicy.shouldRestartForFacingSwitch(cameraState)
        )
    }
}
