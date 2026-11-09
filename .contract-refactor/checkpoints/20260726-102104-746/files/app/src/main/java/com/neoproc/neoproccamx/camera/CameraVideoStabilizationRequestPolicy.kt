package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import com.neoproc.neoproccamx.recording.StabilizationMode

internal data class CameraVideoStabilizationRequestPlan(
    val oisMode: Int?,
    val videoStabilizationMode: Int?,
    val resolvedMode: StabilizationMode
)

internal object CameraVideoStabilizationRequestPolicy {

    fun resolve(
        requestedMode: StabilizationMode,
        supportsOis: Boolean,
        supportsVideoStabilization: Boolean,
        rawVideoCaptureEnabled: Boolean
    ): CameraVideoStabilizationRequestPlan =
        when {
            rawVideoCaptureEnabled || requestedMode == StabilizationMode.OFF ->
                offPlan(supportsOis, supportsVideoStabilization)
            requestedMode == StabilizationMode.HYBRID ->
                hybridPlan(supportsOis, supportsVideoStabilization)
            requestedMode == StabilizationMode.OIS ->
                oisPreferredPlan(supportsOis, supportsVideoStabilization)
            requestedMode == StabilizationMode.EIS ->
                eisPreferredPlan(supportsOis, supportsVideoStabilization)
            else -> offPlan(supportsOis, supportsVideoStabilization)
        }

    private fun hybridPlan(
        supportsOis: Boolean,
        supportsVideoStabilization: Boolean
    ): CameraVideoStabilizationRequestPlan =
        when {
            supportsOis && supportsVideoStabilization -> CameraVideoStabilizationRequestPlan(
                oisMode = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                videoStabilizationMode = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                resolvedMode = StabilizationMode.OIS
            )
            supportsOis -> CameraVideoStabilizationRequestPlan(
                oisMode = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                videoStabilizationMode = null,
                resolvedMode = StabilizationMode.OIS
            )
            supportsVideoStabilization -> CameraVideoStabilizationRequestPlan(
                oisMode = null,
                videoStabilizationMode = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                resolvedMode = StabilizationMode.EIS
            )
            else -> offPlan(supportsOis = false, supportsVideoStabilization = false)
        }

    private fun oisPreferredPlan(
        supportsOis: Boolean,
        supportsVideoStabilization: Boolean
    ): CameraVideoStabilizationRequestPlan =
        when {
            supportsOis -> CameraVideoStabilizationRequestPlan(
                oisMode = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                videoStabilizationMode = if (supportsVideoStabilization) {
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                } else {
                    null
                },
                resolvedMode = StabilizationMode.OIS
            )
            supportsVideoStabilization -> CameraVideoStabilizationRequestPlan(
                oisMode = null,
                videoStabilizationMode = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                resolvedMode = StabilizationMode.EIS
            )
            else -> offPlan(supportsOis = false, supportsVideoStabilization = false)
        }

    private fun eisPreferredPlan(
        supportsOis: Boolean,
        supportsVideoStabilization: Boolean
    ): CameraVideoStabilizationRequestPlan =
        when {
            supportsVideoStabilization -> CameraVideoStabilizationRequestPlan(
                oisMode = if (supportsOis) {
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                } else {
                    null
                },
                videoStabilizationMode = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                resolvedMode = StabilizationMode.EIS
            )
            supportsOis -> CameraVideoStabilizationRequestPlan(
                oisMode = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                videoStabilizationMode = null,
                resolvedMode = StabilizationMode.OIS
            )
            else -> offPlan(supportsOis = false, supportsVideoStabilization = false)
        }

    private fun offPlan(
        supportsOis: Boolean,
        supportsVideoStabilization: Boolean
    ): CameraVideoStabilizationRequestPlan =
        CameraVideoStabilizationRequestPlan(
            oisMode = if (supportsOis) {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            } else {
                null
            },
            videoStabilizationMode = if (supportsVideoStabilization) {
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            } else {
                null
            },
            resolvedMode = StabilizationMode.OFF
        )
}
