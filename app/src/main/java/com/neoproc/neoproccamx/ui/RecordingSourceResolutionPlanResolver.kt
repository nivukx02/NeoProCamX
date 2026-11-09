package com.neoproc.neoproccamx.ui

import android.util.Size

/**
 * RecordingSourceResolutionPlanResolver — cinema-oriented resolution plan factory.
 *
 * Produces [RecordingSourceResolutionPlan] instances by combining sensor metadata,
 * user-selected recording resolution, and pipeline policy constraints.
 *
 * This resolver bridges the gap between raw Camera2 characteristics (pixel arrays,
 * RAW stream configurations) and the structured resolution plans consumed by
 * the recording pipeline.
 *
 * Key responsibilities:
 *  1. **Sensor analysis** — extracts full-sensor and cine-source megapixels.
 *  2. **Full-sensor readout detection** — determines when the requested output
 *     covers enough of the sensor to warrant full readout vs. crop.
 *  3. **Delivery format selection** — maps resolution to delivery format labels.
 *  4. **Resolution tier classification** — assigns tier labels for telemetry.
 *  5. **EIS headroom reservation** — adds crop margin for digital stabilization.
 *  6. **Plan caching** — avoids recomputation on identical inputs.
 */
class RecordingSourceResolutionPlanResolver {

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile
    private var cachedPlan: RecordingSourceResolutionPlan? = null

    @Volatile
    private var cachedInputHash: Int = 0

    // ── Primary API ───────────────────────────────────────────────────────────

    /**
     * Resolves a [RecordingSourceResolutionPlan] using the full policy engine.
     *
     * @param requestedOutput    Target recording output size.
     * @param pixelArraySize     Full sensor pixel array (SENSOR_INFO_PIXEL_ARRAY_SIZE).
     * @param maxRawSize         Maximum RAW output size from stream config.
     * @param fallbackSensorSize Derived fallback from lens characteristics.
     * @param reserveEisHeadroom Whether to add extra crop area for EIS.
     */
    fun resolvePlan(
        requestedOutput: Size = Size(3840, 2160),
        pixelArraySize: Size? = null,
        maxRawSize: Size? = null,
        fallbackSensorSize: Size? = null,
        reserveEisHeadroom: Boolean = false
    ): RecordingSourceResolutionPlan {
        // Cache check — skip recomputation on identical inputs
        val inputHash = computeInputHash(
            requestedOutput, pixelArraySize, maxRawSize, fallbackSensorSize, reserveEisHeadroom
        )
        cachedPlan?.let { cached ->
            if (inputHash == cachedInputHash) return cached
        }

        // Delegate to the full policy engine
        val plan = RecordingSourceResolutionPolicy.resolvePlan(
            requestedOutput = requestedOutput,
            pixelArraySize = pixelArraySize,
            maxRawSize = maxRawSize,
            fallbackSensorSize = fallbackSensorSize,
            reserveEisHeadroom = reserveEisHeadroom
        )

        cachedPlan = plan
        cachedInputHash = inputHash
        return plan
    }

    /**
     * Returns the most recently resolved plan without recomputation.
     * Returns a default plan if no prior resolution has been performed.
     */
    fun currentPlan(): RecordingSourceResolutionPlan =
        cachedPlan ?: RecordingSourceResolutionPlan()

    /**
     * Resolves a plan for a specific resolution without sensor metadata.
     * Useful for quick UI updates where sensor data is not yet available.
     *
     * @param width  Recording output width.
     * @param height Recording output height.
     */
    fun resolvePlanForSize(width: Int, height: Int): RecordingSourceResolutionPlan =
        resolvePlan(requestedOutput = Size(width, height))

    /**
     * Resolves a plan and returns the effective megapixels.
     * Shortcut for telemetry and UI display.
     */
    fun resolveEffectiveMegapixels(
        requestedOutput: Size,
        pixelArraySize: Size? = null,
        maxRawSize: Size? = null,
        fallbackSensorSize: Size? = null
    ): Float =
        resolvePlan(requestedOutput, pixelArraySize, maxRawSize, fallbackSensorSize)
            .effectiveMegapixels

    /**
     * Returns whether the given output size would trigger a full-sensor readout.
     */
    fun wouldUseFullSensorReadout(
        requestedOutput: Size,
        pixelArraySize: Size? = null,
        maxRawSize: Size? = null,
        fallbackSensorSize: Size? = null
    ): Boolean =
        resolvePlan(requestedOutput, pixelArraySize, maxRawSize, fallbackSensorSize)
            .fullSensorReadoutForced

    /**
     * Invalidates the cached plan, forcing recomputation on next call.
     */
    fun invalidateCache() {
        cachedPlan = null
        cachedInputHash = 0
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private fun computeInputHash(
        requestedOutput: Size,
        pixelArraySize: Size?,
        maxRawSize: Size?,
        fallbackSensorSize: Size?,
        reserveEisHeadroom: Boolean
    ): Int {
        var hash = 17
        hash = hash * 31 + requestedOutput.hashCode()
        hash = hash * 31 + (pixelArraySize?.hashCode() ?: 0)
        hash = hash * 31 + (maxRawSize?.hashCode() ?: 0)
        hash = hash * 31 + (fallbackSensorSize?.hashCode() ?: 0)
        hash = hash * 31 + if (reserveEisHeadroom) 1 else 0
        return hash
    }
}
