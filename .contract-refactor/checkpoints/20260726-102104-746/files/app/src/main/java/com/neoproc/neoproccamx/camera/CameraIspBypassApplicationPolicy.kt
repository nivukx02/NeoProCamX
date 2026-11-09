package com.neoproc.neoproccamx.camera

internal data class CameraIspBypassApplicationDecision(
    val applyRequestKeys: Boolean,
    val includeLinearTonemap: Boolean,
    val reason: String
)

internal object CameraIspBypassApplicationPolicy {
    fun resolve(
        cinemaFidelityOverridesEnabled: Boolean,
        rawVideoCaptureEnabled: Boolean,
        recordingActive: Boolean,
        readoutSidecarActive: Boolean,
        hdrDynamicRangeMode: HdrDynamicRangeMode,
        appOwnedGpuProcessingEnabled: Boolean = false,
        strictHighEndEnabled: Boolean = false
    ): CameraIspBypassApplicationDecision {
        if (
            strictHighEndEnabled &&
            recordingActive &&
            !rawVideoCaptureEnabled
        ) {
            return CameraIspBypassApplicationDecision(
                applyRequestKeys = false,
                includeLinearTonemap = false,
                reason = "strict high-end processed video keeps public Camera2 ISP cleanup, edge, tone, and geometry elevated"
            )
        }

        if (
            appOwnedGpuProcessingEnabled &&
            recordingActive &&
            !rawVideoCaptureEnabled
        ) {
            val transferLabel = if (hdrDynamicRangeMode == HdrDynamicRangeMode.SDR) "SDR" else "HDR"
            return CameraIspBypassApplicationDecision(
                applyRequestKeys = true,
                includeLinearTonemap = false,
                reason = "Vulkan owns $transferLabel texture cleanup and geometry; request advertised ISP stages OFF"
            )
        }

        if (hdrDynamicRangeMode != HdrDynamicRangeMode.SDR) {
            return CameraIspBypassApplicationDecision(
                applyRequestKeys = false,
                includeLinearTonemap = false,
                reason = "HDR requires ISP AWB, CCM, noise cleanup, and HAL tone mapping unless Vulkan owns the recording path"
            )
        }

        val rawSensorBypassActive = cinemaFidelityOverridesEnabled && rawVideoCaptureEnabled
        if (!rawSensorBypassActive) {
            return CameraIspBypassApplicationDecision(
                applyRequestKeys = false,
                includeLinearTonemap = false,
                reason = when {
                    !cinemaFidelityOverridesEnabled -> "cinema fidelity capture inactive"
                    recordingActive || readoutSidecarActive ->
                        "encoded recording keeps high-quality ISP noise cleanup"
                    else -> "raw sensor bypass inactive"
                }
            )
        }

        return CameraIspBypassApplicationDecision(
            applyRequestKeys = false,
            includeLinearTonemap = false,
            reason = "RAW_SENSOR request path owned by HardwareAbstractionLayer.resolveProfile"
        )
    }
}
