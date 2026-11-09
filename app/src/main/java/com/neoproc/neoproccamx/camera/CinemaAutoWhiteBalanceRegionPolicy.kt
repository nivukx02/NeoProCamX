package com.neoproc.neoproccamx.camera

/**
 * Builds the Camera2 metering contract for automatic white balance.
 *
 * Focus and exposure are allowed to follow a selected subject, but white balance is deliberately
 * scene-wide. Letting a small AF/AE subject rectangle dominate AWB makes the colour of the complete
 * frame jump whenever the camera is reframed. A single maximum-weight active-array region works on
 * devices that expose only one AWB region and gives every sensor pixel equal colour context.
 */
internal object CinemaAutoWhiteBalanceRegionPolicy {
    private const val FULL_FRAME_WEIGHT = 1_000

    fun resolve(
        @Suppress("UNUSED_PARAMETER") subjectRegion: CameraRect?,
        activeArray: CameraRect?,
        maxRegions: Int
    ): List<CameraWeightedMeteringRegion> {
        val sensorBounds = activeArray?.takeIf { it.width > 0 && it.height > 0 }
            ?: return emptyList()
        if (maxRegions <= 0) return emptyList()

        return listOf(
            CameraWeightedMeteringRegion(
                rect = sensorBounds,
                weight = FULL_FRAME_WEIGHT
            )
        )
    }
}
