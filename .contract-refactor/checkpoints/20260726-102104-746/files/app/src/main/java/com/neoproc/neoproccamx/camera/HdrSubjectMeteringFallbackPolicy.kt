package com.neoproc.neoproccamx.camera

import kotlin.math.roundToInt

internal data class HdrSubjectMeteringFallbackPlan(
    val exposureRegion: CameraRect?,
    val awbRegion: CameraRect?
)

internal object HdrSubjectMeteringFallbackPolicy {
    private const val EXPOSURE_WIDTH_RATIO = 0.38f
    private const val EXPOSURE_HEIGHT_RATIO = 0.42f
    private const val AWB_WIDTH_RATIO = 0.46f
    private const val AWB_HEIGHT_RATIO = 0.52f

    val EMPTY = HdrSubjectMeteringFallbackPlan(
        exposureRegion = null,
        awbRegion = null
    )

    fun resolve(
        hdrDynamicRangeMode: HdrDynamicRangeMode,
        activeArray: CameraRect?,
        persistentExposureRegion: CameraRect?,
        persistentAwbRegion: CameraRect?,
        maxAeRegions: Int,
        maxAwbRegions: Int,
        // When true (zoom-stable metering fix), the HDR fallback does NOT impose a narrow centred AE
        // patch — it leaves the exposure region unset so the HAL meters the WHOLE frame. Full-frame
        // metering is inherently zoom-insensitive and includes the highlights, so exposure no longer
        // lifts/blows when zooming out. A user tap-to-meter subject still overrides (persistentExposure).
        zoomStableExposureMetering: Boolean = false
    ): HdrSubjectMeteringFallbackPlan {
        if (hdrDynamicRangeMode == HdrDynamicRangeMode.SDR) return EMPTY
        val sensorBounds = activeArray?.takeIf { it.width > 0 && it.height > 0 } ?: return EMPTY
        val hasExposureSubject = persistentExposureRegion.hasArea()
        val hasAwbSubject = persistentAwbRegion.hasArea()

        return HdrSubjectMeteringFallbackPlan(
            exposureRegion = if (!hasExposureSubject && maxAeRegions > 0 && !zoomStableExposureMetering) {
                centeredRegion(sensorBounds, EXPOSURE_WIDTH_RATIO, EXPOSURE_HEIGHT_RATIO)
            } else {
                null
            },
            awbRegion = if (!hasAwbSubject && maxAwbRegions > 0) {
                centeredRegion(sensorBounds, AWB_WIDTH_RATIO, AWB_HEIGHT_RATIO)
            } else {
                null
            }
        )
    }

    private fun CameraRect?.hasArea(): Boolean =
        this != null && width > 0 && height > 0

    private fun centeredRegion(
        sensorBounds: CameraRect,
        widthRatio: Float,
        heightRatio: Float
    ): CameraRect {
        val targetWidth = (sensorBounds.width * widthRatio)
            .roundToInt()
            .coerceIn(1, sensorBounds.width)
        val targetHeight = (sensorBounds.height * heightRatio)
            .roundToInt()
            .coerceIn(1, sensorBounds.height)
        val centerX = ((sensorBounds.left + sensorBounds.right) * 0.5f).roundToInt()
        val centerY = ((sensorBounds.top + sensorBounds.bottom) * 0.5f).roundToInt()
        return clamp(
            CameraRect(
                left = centerX - targetWidth / 2,
                top = centerY - targetHeight / 2,
                right = centerX - targetWidth / 2 + targetWidth,
                bottom = centerY - targetHeight / 2 + targetHeight
            ),
            sensorBounds
        )
    }

    private fun clamp(region: CameraRect, sensorBounds: CameraRect): CameraRect {
        val left = region.left.coerceIn(sensorBounds.left, sensorBounds.right - 1)
        val top = region.top.coerceIn(sensorBounds.top, sensorBounds.bottom - 1)
        val right = region.right.coerceIn(left + 1, sensorBounds.right)
        val bottom = region.bottom.coerceIn(top + 1, sensorBounds.bottom)
        return CameraRect(left, top, right, bottom)
    }
}
