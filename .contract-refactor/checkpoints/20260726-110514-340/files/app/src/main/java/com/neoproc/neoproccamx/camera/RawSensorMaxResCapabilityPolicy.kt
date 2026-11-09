package com.neoproc.neoproccamx.camera

/**
 * Result of probing whether the device exposes a genuine maximum-resolution (un-binned) RAW mode.
 *
 * @property maxResAvailable    true when a materially larger un-binned RAW size is exposed.
 * @property maxResRawSize      the largest RAW_SENSOR size from the MAXIMUM_RESOLUTION config map.
 * @property activeArrayRawSize the largest RAW_SENSOR size from the default (streaming) config map.
 * @property areaGainRatio      maxRes.area / activeArray.area (1.0 when there is no meaningful gain).
 */
internal data class RawSensorMaxResCapability(
    val maxResAvailable: Boolean,
    val maxResRawSize: CameraStreamSize?,
    val activeArrayRawSize: CameraStreamSize?,
    val areaGainRatio: Double,
    val diagnostics: List<String>
)

/**
 * Decides whether a device offers a meaningful maximum-resolution RAW mode.
 *
 * A Quad-/Nona-Bayer sensor reports its default (streaming) RAW at a binned quarter/ninth of the
 * native pixel count; the un-binned native size is only reachable through the
 * SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION config map. This policy compares the two so we only claim
 * a max-res tier when the un-binned size is materially larger — some HALs echo the same size in
 * both maps, in which case engaging the (slower) max-res path buys nothing.
 *
 * Pure and Context-free so it is fully unit-testable.
 */
internal object RawSensorMaxResCapabilityPolicy {

    /**
     * Minimum area ratio for the max-res size to count as "meaningful". Binned Bayer layouts are
     * typically 4x (Quad) or 9x (Nona); 1.5x is a conservative floor that still rejects echoes.
     */
    const val MEANINGFUL_AREA_GAIN: Double = 1.5

    fun resolve(
        activeArrayRawSize: CameraStreamSize?,
        maxResMapRawSizes: List<CameraStreamSize>
    ): RawSensorMaxResCapability {
        val activeArray = activeArrayRawSize?.takeIf { it.width > 0 && it.height > 0 }
        val largestMaxRes = maxResMapRawSizes
            .filter { it.width > 0 && it.height > 0 }
            .maxByOrNull { it.area }

        val gain = if (activeArray != null && activeArray.area > 0L && largestMaxRes != null) {
            largestMaxRes.area.toDouble() / activeArray.area.toDouble()
        } else {
            1.0
        }

        val available = largestMaxRes != null &&
            (activeArray == null || gain >= MEANINGFUL_AREA_GAIN)

        val diagnostics = buildList {
            if (largestMaxRes == null) {
                add("No MAXIMUM_RESOLUTION RAW_SENSOR size exposed to Camera2")
            } else if (!available) {
                add(
                    "Max-res RAW ${largestMaxRes.width}x${largestMaxRes.height} only " +
                        "${"%.2f".format(gain)}x the active array — no meaningful gain"
                )
            } else {
                add(
                    "Max-res RAW ${largestMaxRes.width}x${largestMaxRes.height} " +
                        "(${"%.1f".format(gain)}x active array) available"
                )
            }
            if (activeArray != null) add("Active-array RAW ${activeArray.width}x${activeArray.height}")
        }

        return RawSensorMaxResCapability(
            maxResAvailable = available,
            maxResRawSize = if (available) largestMaxRes else null,
            activeArrayRawSize = activeArray,
            areaGainRatio = gain,
            diagnostics = diagnostics
        )
    }
}
