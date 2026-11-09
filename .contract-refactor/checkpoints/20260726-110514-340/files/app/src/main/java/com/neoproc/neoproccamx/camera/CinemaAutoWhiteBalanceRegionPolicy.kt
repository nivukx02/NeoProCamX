package com.neoproc.neoproccamx.camera

import kotlin.math.roundToInt

internal object CinemaAutoWhiteBalanceRegionPolicy {
    private const val SUBJECT_WEIGHT = 900
    private const val SUBJECT_CONTEXT_WEIGHT = 460
    private const val CENTER_WEIGHT = 720
    private const val FULL_FRAME_WEIGHT = 220
    private const val SUBJECT_CONTEXT_SCALE = 1.7333334f
    private const val CENTER_FRAME_RATIO = 0.60f

    fun resolve(
        subjectRegion: CameraRect?,
        activeArray: CameraRect?,
        maxRegions: Int
    ): List<CameraWeightedMeteringRegion> {
        val sensorBounds = activeArray?.takeIf { it.width > 0 && it.height > 0 } ?: return emptyList()
        if (maxRegions <= 0) return emptyList()

        val regions = if (subjectRegion != null && subjectRegion.width > 0 && subjectRegion.height > 0) {
            listOf(
                CameraWeightedMeteringRegion(clamp(subjectRegion, sensorBounds), SUBJECT_WEIGHT),
                CameraWeightedMeteringRegion(
                    expandAndClamp(subjectRegion, sensorBounds, SUBJECT_CONTEXT_SCALE),
                    SUBJECT_CONTEXT_WEIGHT
                ),
                CameraWeightedMeteringRegion(sensorBounds, FULL_FRAME_WEIGHT)
            )
        } else {
            listOf(
                CameraWeightedMeteringRegion(centerFrame(sensorBounds), CENTER_WEIGHT),
                CameraWeightedMeteringRegion(sensorBounds, FULL_FRAME_WEIGHT)
            )
        }

        return regions
            .filter { it.rect.width > 0 && it.rect.height > 0 }
            .distinctBy { it.rect }
            .take(maxRegions)
    }

    private fun centerFrame(sensorBounds: CameraRect): CameraRect {
        val halfWidth = (sensorBounds.width * CENTER_FRAME_RATIO * 0.5f).roundToInt()
        val halfHeight = (sensorBounds.height * CENTER_FRAME_RATIO * 0.5f).roundToInt()
        val centerX = ((sensorBounds.left + sensorBounds.right) * 0.5f).roundToInt()
        val centerY = ((sensorBounds.top + sensorBounds.bottom) * 0.5f).roundToInt()
        return CameraRect(
            left = centerX - halfWidth,
            top = centerY - halfHeight,
            right = centerX + halfWidth,
            bottom = centerY + halfHeight
        )
    }

    private fun expandAndClamp(
        region: CameraRect,
        sensorBounds: CameraRect,
        scale: Float
    ): CameraRect {
        val centerX = ((region.left + region.right) * 0.5f).roundToInt()
        val centerY = ((region.top + region.bottom) * 0.5f).roundToInt()
        val halfWidth = (region.width * scale * 0.5f).roundToInt().coerceAtLeast(1)
        val halfHeight = (region.height * scale * 0.5f).roundToInt().coerceAtLeast(1)
        return clamp(
            CameraRect(
                left = centerX - halfWidth,
                top = centerY - halfHeight,
                right = centerX + halfWidth,
                bottom = centerY + halfHeight
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
