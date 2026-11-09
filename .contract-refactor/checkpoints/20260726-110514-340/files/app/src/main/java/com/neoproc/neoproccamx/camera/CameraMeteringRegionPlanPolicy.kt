package com.neoproc.neoproccamx.camera

import kotlin.math.roundToInt

internal data class CameraWeightedMeteringRegion(
    val rect: CameraRect,
    val weight: Int
)

internal object CameraMeteringRegionPlanPolicy {
    private const val MAX_METERING_WEIGHT = 1000
    private val focusProfile = listOf(
        RegionProfile(scale = 1.0f, weight = 1000),
        RegionProfile(scale = 2.5f, weight = 560),
        RegionProfile(scale = 4.0f, weight = 260)
    )
    private val exposureProfile = listOf(
        RegionProfile(scale = 1.0f, weight = 880),
        RegionProfile(scale = 1.5f, weight = 520),
        RegionProfile(scale = 2.0f, weight = 260)
    )

    fun resolveFocusRegions(
        primaryRegion: CameraRect?,
        activeArray: CameraRect?,
        maxRegions: Int
    ): List<CameraWeightedMeteringRegion> =
        resolve(primaryRegion, activeArray, maxRegions, focusProfile)

    fun resolveExposureRegions(
        primaryRegion: CameraRect?,
        activeArray: CameraRect?,
        maxRegions: Int
    ): List<CameraWeightedMeteringRegion> =
        resolve(primaryRegion, activeArray, maxRegions, exposureProfile)

    private fun resolve(
        primaryRegion: CameraRect?,
        activeArray: CameraRect?,
        maxRegions: Int,
        profile: List<RegionProfile>
    ): List<CameraWeightedMeteringRegion> {
        if (primaryRegion == null || maxRegions <= 0 || primaryRegion.width <= 0 || primaryRegion.height <= 0) {
            return emptyList()
        }

        val sensorBounds = activeArray?.takeIf { it.width > 0 && it.height > 0 }
        return profile
            .mapNotNull { regionProfile ->
                val rect = if (regionProfile.scale == 1.0f || sensorBounds == null) {
                    sensorBounds?.let { clamp(primaryRegion, it) } ?: primaryRegion
                } else {
                    expandAndClamp(primaryRegion, sensorBounds, regionProfile.scale)
                }
                rect
                    .takeIf { it.width > 0 && it.height > 0 }
                    ?.let {
                        CameraWeightedMeteringRegion(
                            rect = it,
                            weight = regionProfile.weight.coerceIn(1, MAX_METERING_WEIGHT)
                        )
                    }
            }
            .distinctBy { it.rect }
            .take(maxRegions)
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

    private data class RegionProfile(
        val scale: Float,
        val weight: Int
    )
}
