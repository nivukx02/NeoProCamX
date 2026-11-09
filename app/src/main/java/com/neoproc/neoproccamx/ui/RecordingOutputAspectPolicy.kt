package com.neoproc.neoproccamx.ui

import android.util.Size
import kotlin.math.abs

/**
 * RecordingOutputAspectPolicy — cinema-oriented output aspect ratio enforcement.
 *
 * Handles the mathematical transformation between the camera's native
 * sensor output aspect ratio and the target delivery aspect ratio specified
 * by the user (16:9, 2.00:1, 1.85:1, 4:3, 1:1, 9:16).
 *
 * Responsibilities:
 *  1. **Override detection** — identifies when the encoder output aspect
 *     does not match the selected preview/delivery aspect ratio.
 *  2. **Crop region calculation** — produces the exact sensor crop rectangle
 *     needed to achieve the target aspect without letterboxing.
 *  3. **Pillarbox/letterbox classification** — tells the UI which black-bar
 *     mode to use when the delivery aspect cannot be achieved by cropping alone.
 *  4. **Metadata injection** — provides the SAR/DAR values needed for the
 *     container's video track header (MOV/MP4 `pasp` atom, MKV `AspectRatioX`).
 *
 * All calculations follow SMPTE RP 2052-1 (aspect ratio metadata for MXF)
 * and ISO/IEC 14496-12 (MP4 container aspect ratio signaling).
 */
internal object RecordingOutputAspectPolicy {

    // ── Supported Delivery Aspects ────────────────────────────────────────────

        enum class DeliveryAspect(
        val ratio: Float,
        val label: String,
        val isWidescreen: Boolean = true
    ) {
        SIXTEEN_NINE(16f / 9f, "16:9"),
        CINEMATIC_200(2.00f, "2.00:1"),
        CINEMATIC_185(1.85f, "1.85:1"),
        FOUR_THREE(4f / 3f, "4:3", isWidescreen = false),
        SQUARE(1.00f, "1:1", isWidescreen = false),
        NINE_SIXTEEN(9f / 16f, "9:16", isWidescreen = false),
        NATIVE(0f, "NATIVE");

        companion object {
            fun fromPreviewAspect(aspect: PreviewAspectRatio): DeliveryAspect = when (aspect) {
                PreviewAspectRatio.AUTO -> NATIVE
                PreviewAspectRatio.SQUARE -> SQUARE
                PreviewAspectRatio.FOUR_THREE -> FOUR_THREE
                PreviewAspectRatio.SIXTEEN_NINE -> SIXTEEN_NINE
            }

            fun nearest(ratio: Float): DeliveryAspect =
                entries.filter { it != NATIVE }.minByOrNull { abs(it.ratio - ratio) }
                    ?: SIXTEEN_NINE
        }
    }

    // ── Override Resolution ───────────────────────────────────────────────────

    /**
     * Resolves a crop aspect ratio override for cases where the encoder output
     * does not natively match the selected delivery aspect.
     *
     * Returns null if no override is needed (e.g. the native sensor output
     * already matches the target delivery aspect within tolerance).
     *
     * @param previewAspectRatio The user-selected preview/delivery aspect.
     * @param encoderSize        The Camera2-assigned encoder output size.
     * @param tolerancePct       Aspect ratio match tolerance (default 2%).
     */
    fun resolveOverride(
        previewAspectRatio: PreviewAspectRatio,
        encoderSize: Size?
    ): Float? {
        val targetAspect = DeliveryAspect.fromPreviewAspect(previewAspectRatio)
        return resolveOverrideForDelivery(targetAspect, encoderSize)
    }

    /**
     * Resolves a crop aspect ratio override for a [DeliveryAspect] target.
     *
     * @param targetAspect   Desired delivery aspect ratio.
     * @param encoderSize    Camera2 encoder output size.
     * @param tolerancePct   Match tolerance percentage (default 2%).
     */
    fun resolveOverrideForDelivery(
        targetAspect: DeliveryAspect,
        encoderSize: Size?,
        tolerancePct: Float = 0.02f
    ): Float? {
        if (targetAspect == DeliveryAspect.NATIVE) return null
        val size = encoderSize ?: return null
        val longEdge = maxOf(size.width, size.height).coerceAtLeast(1)
        val shortEdge = minOf(size.width, size.height).coerceAtLeast(1)
        val nativeRatio = longEdge.toFloat() / shortEdge.toFloat()
        val targetRatio = targetAspect.ratio

        // Within tolerance — no override needed
        if (abs(nativeRatio - targetRatio) / targetRatio.coerceAtLeast(1e-6f) <= tolerancePct) {
            return null
        }

        return targetRatio
    }

    // ── Crop Rectangle Calculation ────────────────────────────────────────────

    /**
     * Calculates the sensor crop region needed to achieve the target delivery aspect.
     *
     * Returns a [CropRegion] specifying the crop rectangle in sensor coordinates.
     * The rectangle is always centered on the sensor active area.
     *
     * @param sensorSize   Full sensor active pixel area.
     * @param targetAspect Target delivery aspect ratio.
     */
    fun calculateCropRegion(
        sensorSize: Size,
        targetAspect: DeliveryAspect
    ): CropRegion {
        if (targetAspect == DeliveryAspect.NATIVE) {
            return CropRegion(0, 0, sensorSize.width, sensorSize.height, isFullSensor = true)
        }

        val sensorLong = maxOf(sensorSize.width, sensorSize.height)
        val sensorShort = minOf(sensorSize.width, sensorSize.height)
        val sensorAspect = sensorLong.toFloat() / sensorShort.coerceAtLeast(1)
        val targetRatio = targetAspect.ratio

        return if (targetRatio > sensorAspect) {
            // Target is wider than sensor — crop top and bottom
            val croppedHeight = (sensorLong / targetRatio).toInt().coerceAtLeast(1)
            val topMargin = (sensorShort - croppedHeight) / 2
            CropRegion(0, topMargin, sensorLong, croppedHeight, isFullSensor = false)
        } else {
            // Target is narrower than sensor — crop left and right
            val croppedWidth = (sensorShort * targetRatio).toInt().coerceAtLeast(1)
            val leftMargin = (sensorLong - croppedWidth) / 2
            CropRegion(leftMargin, 0, croppedWidth, sensorShort, isFullSensor = false)
        }
    }

    // ── Metadata Helpers ──────────────────────────────────────────────────────

    /**
     * Returns the Sample Aspect Ratio (SAR) numerator and denominator for MP4/MOV
     * `pasp` atom injection when the encoder encodes at a pixel aspect ratio != 1:1.
     *
     * Most Cinema2 devices encode at square pixels (SAR = 1:1), but anamorphic
     * workflows (2× squeeze) require a non-square SAR in the container.
     *
     * @param encodedSize   The encoder output size in pixels.
     * @param deliveryAspect The target display aspect ratio.
     * @return Pair(sar_width, sar_height) — both 1 for square-pixel output.
     */
    fun resolveSampleAspectRatio(
        encodedSize: Size,
        deliveryAspect: DeliveryAspect
    ): Pair<Int, Int> {
        if (deliveryAspect == DeliveryAspect.NATIVE) return Pair(1, 1)
        val pixelAspect = encodedSize.width.toFloat() / encodedSize.height.coerceAtLeast(1)
        val deliveryRatio = deliveryAspect.ratio
        if (abs(pixelAspect - deliveryRatio) < 0.02f) return Pair(1, 1)

        // Compute SAR as a simplified integer fraction
        val sarW = (deliveryRatio / pixelAspect * 1000).toInt()
        val sarH = 1000
        val gcd = gcd(sarW, sarH)
        return Pair(sarW / gcd, sarH / gcd)
    }

    /**
     * Classifies the black bar mode required for a given encoder→display transformation.
     */
    fun classifyBarMode(
        encoderAspect: Float,
        displayAspect: Float,
        tolerancePct: Float = 0.02f
    ): BarMode {
        val ratio = abs(encoderAspect - displayAspect) / displayAspect.coerceAtLeast(1e-6f)
        if (ratio <= tolerancePct) return BarMode.NONE
        return if (encoderAspect > displayAspect) BarMode.PILLARBOX else BarMode.LETTERBOX
    }

    enum class BarMode { NONE, LETTERBOX, PILLARBOX }

    // ── Data Types ────────────────────────────────────────────────────────────

    data class CropRegion(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val isFullSensor: Boolean
    ) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height
        val area: Long get() = width.toLong() * height.toLong()
        val aspectRatio: Float get() = width.toFloat() / height.coerceAtLeast(1)
    }

    // ── Private Utilities ─────────────────────────────────────────────────────

    private fun gcd(a: Int, b: Int): Int {
        var x = a.coerceAtLeast(0)
        var y = b.coerceAtLeast(1)
        while (y != 0) {
            val t = y
            y = x % y
            x = t
        }
        return x.coerceAtLeast(1)
    }
}
