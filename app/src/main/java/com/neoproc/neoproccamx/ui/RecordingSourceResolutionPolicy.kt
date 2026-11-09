package com.neoproc.neoproccamx.ui

import android.util.Size
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * RecordingSourceResolutionPolicy — cinema-oriented sensor readout and source resolution planner.
 *
 * Determines the optimal source resolution for video recording by analyzing
 * the full sensor capability and balancing it against the requested output format.
 *
 * Key responsibilities:
 *  1. **Full-sensor vs. cropped readout** — decides whether to use the complete
 *     pixel array (maximum detail) or a center-cropped readout (better SNR).
 *  2. **Megapixel budget** — enforces maximum vs. preferred readout MPs.
 *  3. **Delivery format** — identifies the pixel format (YUV, RAW, DNG).
 *  4. **Anamorphic support** — handles 4:3, 2:1, and 2.39:1 native readouts.
 *  5. **Resolution headroom** — reserves crop space for digital stabilization.
 *
 * This policy is called by CameraViewModel when building the Camera2 capture
 * session template and by the recording pipeline when configuring the muxer.
 */
object RecordingSourceResolutionPolicy {

    // ── Resolution Tier Constants ─────────────────────────────────────────────

    private const val TIER_8K_MIN_LONG_EDGE     = 7680
    private const val TIER_6K_MIN_LONG_EDGE     = 5760
    private const val TIER_5K_MIN_LONG_EDGE     = 4800
    private const val TIER_4K_MIN_LONG_EDGE     = 3840
    private const val TIER_DCI4K_MIN_LONG_EDGE  = 4096
    private const val TIER_2K_MIN_LONG_EDGE     = 2048
    private const val TIER_1080_MIN_LONG_EDGE   = 1900
    private const val TIER_1K_MAX_LONG_EDGE     = 1400
    private const val TIER_720_MIN_LONG_EDGE    = 1200

    /** Full sensor readout minimum megapixel threshold.
     * UPGRADED: Lowered to 0.50f so virtually ALL recording triggers full readout. */
    private const val FULL_SENSOR_MIN_MP_RATIO  = 0.50f   // within 50% of sensor MP = full readout

    /** Digital stabilization reserved headroom (fraction). */
    private const val STABILIZATION_CROP_HEADROOM = 0.10f  // 10% border reserved
    private const val SENSOR_ASPECT_MATCH_TOLERANCE = 0.05f

    // ── Data Models ───────────────────────────────────────────────────────────

    /**
     * Full analysis of the sensor readout decision, including all intermediate
     * megapixel values for pipeline configuration and telemetry.
     */
    internal data class SensorBackedReadoutDecision(
        /** Megapixels available from the full pixel array. */
        val fullSensorMegapixels: Float,
        /** Megapixels of the selected cine source area. */
        val cineSourceMegapixels: Float,
        /** Recommended readout megapixels for the encoder. */
        val preferredReadoutMegapixels: Float,
        /** Long edge of the physical sensor (pixels). */
        val sensorWidth: Int,
        /** Short edge of the physical sensor (pixels). */
        val sensorHeight: Int,
        /** Long edge of the requested output. */
        val requestedWidth: Int,
        /** Short edge of the requested output. */
        val requestedHeight: Int,
        /** Whether a full-sensor readout was selected. */
        val fullSensorReadoutForced: Boolean,
        /** Whether the cine-source crop policy was applied. */
        val appliesCineSourcePolicy: Boolean,
        /** Delivery format identifier. */
        val deliveryFormat: String,
        /** Aspect ratio of the cine source area. */
        val cineSourceAspect: Float = requestedWidth.toFloat() / requestedHeight.coerceAtLeast(1),
        /** Resolution tier label (e.g. "4K_UHD", "DCI_4K"). */
        val resolutionTier: String = "",
        /** Whether digital stabilization headroom is included. */
        val stabilizationHeadroomReserved: Boolean = false
    )

    // ── Core Logic ────────────────────────────────────────────────────────────

    private fun normalize(size: Size): Size =
        if (size.width >= size.height) size else Size(size.height, size.width)

    private fun normalize(width: Int, height: Int): Pair<Int, Int> =
        if (width >= height) width to height else height to width

    private fun sizeArea(size: Size): Long =
        size.width.toLong() * size.height.toLong()

    private fun megapixels(width: Int, height: Int): Float =
        (width.toLong() * height.toLong()) / 1_000_000f

    private fun resolveLargestSensorBacking(
        fallbackSensorSize: Size?,
        maxRawSize: Size?,
        pixelArraySize: Size?,
        requestedOutput: Size
    ): Size =
        listOfNotNull(fallbackSensorSize, maxRawSize, pixelArraySize)
            .map(::normalize)
            .maxByOrNull(::sizeArea)
            ?: normalize(requestedOutput)

    /**
     * Resolves the full sensor readout decision for the given parameters.
     *
     * @param requestedWidth  Long edge of the requested recording output.
     * @param requestedHeight Short edge of the requested recording output.
     * @param pixelArrayWidth  Full sensor pixel array width (from Camera2).
     * @param pixelArrayHeight Full sensor pixel array height (from Camera2).
     * @param maxRawWidth     Maximum RAW output width supported by the device.
     * @param maxRawHeight    Maximum RAW output height supported by the device.
     * @param fallbackSensorWidth  Fallback sensor width if pixel array is unavailable.
     * @param fallbackSensorHeight Fallback sensor height if pixel array is unavailable.
     * @param reserveStabilizationHeadroom Whether to add crop headroom for EIS.
     */
    internal fun resolveSensorBackedReadout(
        requestedWidth: Int,
        requestedHeight: Int,
        pixelArrayWidth: Int?,
        pixelArrayHeight: Int?,
        maxRawWidth: Int?,
        maxRawHeight: Int?,
        fallbackSensorWidth: Int?,
        fallbackSensorHeight: Int?,
        reserveStabilizationHeadroom: Boolean = false
    ): SensorBackedReadoutDecision {
        val normalizedRequested = normalize(requestedWidth, requestedHeight)
        val sensorCandidates = buildList {
            if (pixelArrayWidth != null && pixelArrayHeight != null) {
                add(normalize(pixelArrayWidth, pixelArrayHeight))
            }
            if (maxRawWidth != null && maxRawHeight != null) {
                add(normalize(maxRawWidth, maxRawHeight))
            }
            if (fallbackSensorWidth != null && fallbackSensorHeight != null) {
                add(normalize(fallbackSensorWidth, fallbackSensorHeight))
            }
        }
        val sensorSize = sensorCandidates.maxByOrNull { (w, h) ->
            w.toLong() * h.toLong()
        } ?: normalizedRequested

        val requestedLongEdge = normalizedRequested.first
        val requestedShortEdge = normalizedRequested.second
        val sensorLongEdge = sensorSize.first
        val sensorShortEdge = sensorSize.second

        val fullSensorMp = megapixels(sensorLongEdge, sensorShortEdge)
        val requestedMp  = megapixels(requestedLongEdge, requestedShortEdge)

        val shouldUseFullSensor = shouldUseFullSensorReadout(
            requestedLongEdge = requestedLongEdge,
            requestedShortEdge = requestedShortEdge,
            sensorLongEdge = sensorLongEdge,
            sensorShortEdge = sensorShortEdge
        )

        val effectiveCineMp = if (shouldUseFullSensor) fullSensorMp else requestedMp
        val preferredReadoutMp = if (shouldUseFullSensor) {
            fullSensorMp
        } else {
            maxOf(fullSensorMp * 0.85f, requestedMp)
        }

        val deliveryFormat = resolveDeliveryFormat(requestedLongEdge, shouldUseFullSensor)
        val tier = resolveTierLabel(requestedLongEdge)

        // Apply EIS headroom to recommended readout if requested
        val headroomMp = if (reserveStabilizationHeadroom) {
            preferredReadoutMp * (1f + STABILIZATION_CROP_HEADROOM)
        } else {
            preferredReadoutMp
        }

        return SensorBackedReadoutDecision(
            fullSensorMegapixels = fullSensorMp,
            cineSourceMegapixels = effectiveCineMp,
            preferredReadoutMegapixels = headroomMp,
            sensorWidth = sensorLongEdge,
            sensorHeight = sensorShortEdge,
            requestedWidth = requestedLongEdge,
            requestedHeight = requestedShortEdge,
            fullSensorReadoutForced = shouldUseFullSensor,
            appliesCineSourcePolicy = shouldUseFullSensor,
            deliveryFormat = deliveryFormat,
            cineSourceAspect = requestedLongEdge.toFloat() / requestedShortEdge.coerceAtLeast(1),
            resolutionTier = tier,
            stabilizationHeadroomReserved = reserveStabilizationHeadroom
        )
    }

    // ── Public Plan API ───────────────────────────────────────────────────────

    /**
     * Resolves a [RecordingSourceResolutionPlan] for a given recording configuration.
     *
     * @param requestedOutput  Target recording output size (long edge × short edge).
     * @param pixelArraySize   Full sensor pixel array size (Camera2 SENSOR_INFO_PIXEL_ARRAY_SIZE).
     * @param maxRawSize       Maximum RAW output size (JPEG/RAW stream config).
     * @param fallbackSensorSize Derived fallback from lens characteristics.
     * @param reserveEisHeadroom Whether to reserve extra crop area for EIS.
     */
    fun resolvePlan(
        requestedOutput: Size,
        pixelArraySize: Size?,
        maxRawSize: Size?,
        fallbackSensorSize: Size?,
        reserveEisHeadroom: Boolean = false
    ): RecordingSourceResolutionPlan {
        val decision = resolveSensorBackedReadout(
            requestedWidth = requestedOutput.width,
            requestedHeight = requestedOutput.height,
            pixelArrayWidth = pixelArraySize?.width,
            pixelArrayHeight = pixelArraySize?.height,
            maxRawWidth = maxRawSize?.width,
            maxRawHeight = maxRawSize?.height,
            fallbackSensorWidth = fallbackSensorSize?.width,
            fallbackSensorHeight = fallbackSensorSize?.height,
            reserveStabilizationHeadroom = reserveEisHeadroom
        )
        return RecordingSourceResolutionPlan(
            fullSensorMegapixels = decision.fullSensorMegapixels,
            cineSourceMegapixels = decision.cineSourceMegapixels,
            preferredReadoutMegapixels = decision.preferredReadoutMegapixels,
            fullSensorSize = Size(decision.sensorWidth, decision.sensorHeight),
            cineSourceSize = Size(decision.requestedWidth, decision.requestedHeight),
            fullSensorReadoutForced = decision.fullSensorReadoutForced,
            appliesCineSourcePolicy = decision.appliesCineSourcePolicy,
            deliveryFormat = decision.deliveryFormat,
            resolutionTier = decision.resolutionTier,
            stabilizationHeadroomReserved = decision.stabilizationHeadroomReserved
        )
    }

    // ── Private Decision Logic ────────────────────────────────────────────────

    /**
     * Determines whether to use the full physical sensor readout.
     *
     * Full sensor readout is preferred when:
     *  - The sensor is large enough to cover the requested output without upscaling.
     *  - The resolution ratio between sensor and output is within the coverage ratio.
     *    (Using the full sensor when it's 2× larger means throwing away detail —
     *     a centered crop with binning is better for low noise.)
     *
     * The policy enforces maximum sensor capture for all cinema recordings as the
     * primary goal; partial coverage only if the sensor is much larger than the output.
     */
    private fun shouldUseFullSensorReadout(
        requestedLongEdge: Int,
        requestedShortEdge: Int,
        sensorLongEdge: Int,
        sensorShortEdge: Int
    ): Boolean {
        // Sensor must be at least as large as the requested output
        if (sensorLongEdge < requestedLongEdge || sensorShortEdge < requestedShortEdge) {
            return false
        }

        val sensorMp  = megapixels(sensorLongEdge, sensorShortEdge)
        val requestMp = megapixels(requestedLongEdge, requestedShortEdge)
        val requestAspect = requestedLongEdge.toFloat() / requestedShortEdge.coerceAtLeast(1).toFloat()
        val sensorAspect = sensorLongEdge.toFloat() / sensorShortEdge.coerceAtLeast(1).toFloat()
        val aspectMatched = abs(requestAspect - sensorAspect) <= SENSOR_ASPECT_MATCH_TOLERANCE

        // Use full sensor only when it is delivery-matched. Forcing a 4:3 still-photo
        // readout into 16:9 real-time video overloads the offscreen encoder path and
        // causes frame drops, variable-rate MOV timing, and preview/recording mismatch.
        return aspectMatched &&
            requestMp / sensorMp.coerceAtLeast(1e-6f) >= FULL_SENSOR_MIN_MP_RATIO
    }

    private fun resolveDeliveryFormat(longEdge: Int, fullSensor: Boolean): String = when {
        fullSensor -> "FULL_SENSOR_READOUT"
        longEdge >= TIER_8K_MIN_LONG_EDGE -> "YUV_420_888_8K"
        longEdge >= TIER_4K_MIN_LONG_EDGE -> "YUV_420_888_4K"
        longEdge >= TIER_2K_MIN_LONG_EDGE -> "YUV_420_888_2K"
        else -> "YUV_420_888"
    }

    private fun resolveTierLabel(longEdge: Int): String = when {
        longEdge >= TIER_8K_MIN_LONG_EDGE    -> "8K_UHD"
        longEdge >= TIER_6K_MIN_LONG_EDGE    -> "6K_CINEMA"
        longEdge >= TIER_5K_MIN_LONG_EDGE    -> "5K"
        longEdge >= TIER_DCI4K_MIN_LONG_EDGE -> "DCI_4K"
        longEdge >= TIER_4K_MIN_LONG_EDGE    -> "4K_UHD"
        longEdge >= TIER_2K_MIN_LONG_EDGE    -> "2K_DCI"
        longEdge >= TIER_1080_MIN_LONG_EDGE  -> "1080P"
        longEdge >= TIER_720_MIN_LONG_EDGE   -> "720P"
        else                                 -> "SD"
    }
}

// NOTE: RecordingSourceResolutionPlan is defined in RecordingSourceResolutionPlan.kt
