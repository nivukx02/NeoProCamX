package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.RggbChannelVector

/** Maps the frame-owned HAL AWB state to the confidence consumed by video control. */
internal object CameraAwbConfidencePolicy {
    fun resolve(awbState: Int?): Float = when (awbState) {
        CaptureResult.CONTROL_AWB_STATE_CONVERGED,
        CaptureResult.CONTROL_AWB_STATE_LOCKED -> 1f
        CaptureResult.CONTROL_AWB_STATE_SEARCHING -> 0.35f
        else -> 0.7f
    }

    /** Same-frame overload: malformed color evidence can never become lock authority. */
    fun resolve(awbState: Int?, gains: RggbChannelVector?): Float {
        val base = resolve(awbState)
        val measured = gains ?: return 0f
        return UniversalCameraControlCalibration.whiteBalanceEvidenceConfidence(
            baseConfidence = base,
            red = measured.red,
            greenEven = measured.greenEven,
            greenOdd = measured.greenOdd,
            blue = measured.blue
        )
    }
}
