package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import com.neoproc.neoproccamx.recording.StabilizationMode

internal data class CameraVideoStabilizationRequestPlan(
    val oisMode: Int?,
    val videoStabilizationMode: Int?,
    val resolvedMode: StabilizationMode
)

internal object CameraVideoStabilizationRequestPolicy {
    /** Public Camera2 enum value added in API 33; safe only when advertised by the HAL. */
    const val PREVIEW_STABILIZATION_MODE = 2

    fun resolve(
        requestedMode: StabilizationMode,
        supportsOis: Boolean,
        supportsVideoStabilization: Boolean,
        rawVideoCaptureEnabled: Boolean,
        supportsPreviewStabilization: Boolean = false,
        stillCapture: Boolean = false
    ): CameraVideoStabilizationRequestPlan =
        when {
            requestedMode == StabilizationMode.OFF ->
                offPlan(supportsOis, supportsVideoStabilization || supportsPreviewStabilization)
            stillCapture -> stillPlan(
                supportsOis = supportsOis,
                supportsVideoStabilization = supportsVideoStabilization || supportsPreviewStabilization
            )
            rawVideoCaptureEnabled ->
                offPlan(supportsOis, supportsVideoStabilization || supportsPreviewStabilization)
            requestedMode == StabilizationMode.HYBRID ->
                hybridPlan(supportsOis, supportsVideoStabilization, supportsPreviewStabilization)
            requestedMode == StabilizationMode.OIS ->
                oisPreferredPlan(supportsOis, supportsVideoStabilization, supportsPreviewStabilization)
            requestedMode == StabilizationMode.EIS ->
                eisPreferredPlan(supportsOis, supportsVideoStabilization, supportsPreviewStabilization)
            else -> offPlan(supportsOis, supportsVideoStabilization || supportsPreviewStabilization)
        }

    /** A finished still uses optical stabilization without video-EIS crop or warping. */
    private fun stillPlan(
        supportsOis: Boolean,
        supportsVideoStabilization: Boolean
    ): CameraVideoStabilizationRequestPlan =
        if (supportsOis) {
            CameraVideoStabilizationRequestPlan(
                oisMode = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                videoStabilizationMode = if (supportsVideoStabilization) {
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                } else {
                    null
                },
                resolvedMode = StabilizationMode.OIS
            )
        } else {
            offPlan(supportsOis = false, supportsVideoStabilization = supportsVideoStabilization)
        }

    private fun hybridPlan(
        supportsOis: Boolean,
        supportsVideoStabilization: Boolean,
        supportsPreviewStabilization: Boolean
    ): CameraVideoStabilizationRequestPlan =
        when {
            supportsPreviewStabilization -> CameraVideoStabilizationRequestPlan(
                // PREVIEW_STABILIZATION is the public Camera2 mode that keeps preview and record
                // framing on one HAL-managed stabilized stream. Let the HAL coordinate its optical
                // elements; explicitly requesting OIS as well has device-dependent precedence.
                oisMode = if (supportsOis) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF else null,
                videoStabilizationMode = PREVIEW_STABILIZATION_MODE,
                resolvedMode = StabilizationMode.EIS
            )
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
        supportsVideoStabilization: Boolean,
        supportsPreviewStabilization: Boolean
    ): CameraVideoStabilizationRequestPlan =
        when {
            supportsOis -> CameraVideoStabilizationRequestPlan(
                oisMode = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                videoStabilizationMode = if (
                    supportsVideoStabilization || supportsPreviewStabilization
                ) {
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                } else {
                    null
                },
                resolvedMode = StabilizationMode.OIS
            )
            supportsPreviewStabilization || supportsVideoStabilization -> CameraVideoStabilizationRequestPlan(
                oisMode = null,
                videoStabilizationMode = preferredVideoStabilizationMode(supportsPreviewStabilization),
                resolvedMode = StabilizationMode.EIS
            )
            else -> offPlan(supportsOis = false, supportsVideoStabilization = false)
        }

    private fun eisPreferredPlan(
        supportsOis: Boolean,
        supportsVideoStabilization: Boolean,
        supportsPreviewStabilization: Boolean
    ): CameraVideoStabilizationRequestPlan =
        when {
            supportsPreviewStabilization || supportsVideoStabilization -> CameraVideoStabilizationRequestPlan(
                oisMode = if (supportsOis) {
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                } else {
                    null
                },
                videoStabilizationMode = preferredVideoStabilizationMode(supportsPreviewStabilization),
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

    private fun preferredVideoStabilizationMode(supportsPreviewStabilization: Boolean): Int =
        if (supportsPreviewStabilization) {
            PREVIEW_STABILIZATION_MODE
        } else {
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
        }
}
