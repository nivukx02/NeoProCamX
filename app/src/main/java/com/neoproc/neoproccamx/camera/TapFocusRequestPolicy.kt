package com.neoproc.neoproccamx.camera

internal data class TapFocusRequest(
    val focusRegion: CameraRect?,
    val exposureRegion: CameraRect?,
    val shouldTriggerAf: Boolean
)

internal object TapFocusRequestPolicy {
    private const val FOCUS_WINDOW_RATIO = 0.08f
    private const val EXPOSURE_WINDOW_RATIO = 0.24f

    fun resolve(
        activeArray: CameraRect?,
        normalizedX: Float,
        normalizedY: Float,
        maxAfRegions: Int,
        maxAeRegions: Int,
        frameGeometry: FrameGeometry? = null,
        // True when the device advertises a real autofocus mode (any non-OFF CONTROL_AF mode).
        // Lets tap-to-focus fire an AF scan even on sensors that expose ZERO AF metering regions
        // (maxAfRegions == 0), where focusRegion is necessarily null. Defaults false so existing
        // callers/goldens keep the region-gated behaviour.
        afSupported: Boolean = false
    ): TapFocusRequest {
        if (activeArray == null || activeArray.width <= 0 || activeArray.height <= 0) {
            return TapFocusRequest(
                focusRegion = null,
                exposureRegion = null,
                shouldTriggerAf = afSupported
            )
        }

        val sensorPoint = frameGeometry?.previewNormalizedPointToSensorNormalized(
            x = normalizedX,
            y = normalizedY
        )
        val focusRegion = if (maxAfRegions > 0) {
            CameraFocusControl.buildTapMeteringRegion(
                sensorRect = activeArray,
                mappedSensorX = sensorPoint?.first ?: normalizedX,
                mappedSensorY = sensorPoint?.second ?: normalizedY,
                focusWindowRatio = FOCUS_WINDOW_RATIO
            )
        } else {
            null
        }
        val exposureRegion = if (maxAeRegions > 0) {
            CameraFocusControl.buildTapMeteringRegion(
                sensorRect = activeArray,
                mappedSensorX = sensorPoint?.first ?: normalizedX,
                mappedSensorY = sensorPoint?.second ?: normalizedY,
                focusWindowRatio = EXPOSURE_WINDOW_RATIO
            )
        } else {
            null
        }

        return TapFocusRequest(
            focusRegion = focusRegion,
            exposureRegion = exposureRegion,
            // Trigger an AF scan when we have an AF metering region OR the device supports AF but
            // exposes no AF regions (region-less full-frame focus). Fixes dead tap-to-focus on
            // sensors reporting maxAfRegions == 0.
            shouldTriggerAf = focusRegion != null || afSupported
        )
    }
}
