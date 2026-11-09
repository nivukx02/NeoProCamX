package com.neoproc.neoproccamx.camera

import java.util.Arrays
import java.util.Locale
import kotlin.math.sqrt

/**
 * Per-sensor hot-pixel / dark-current defect map (CP15 foundation, s1-f03).
 *
 * Professional bodies subtract a measured dark frame and carry a defect map so a hot pixel never seeds
 * demosaic interpolation as if it were signal. This is the pure-Kotlin builder of that map for NeoProCamX.
 * It is the producer for the demosaic stage's already-declared bindings in
 * `app/src/main/cpp/shaders/vulkan/demosaic_vulkan.comp`:
 *   - `binding = 3, r8ui  hotPixelMask`  ← [SensorDefectMap.toHotPixelMask] (1 = reject + interpolate)
 *   - `binding = 2, r16f  darkCurrentMap` ← per-pixel dark-current rate (DEVICE-GATED: needs exposure-
 *     bracketed dark calibration; here we surface the measured scalar [SensorDefectMap.darkCurrentMeanOffset]
 *     as telemetry only — the per-pixel r16f map is left zero so consumption is a no-op until calibrated).
 *
 * NO-DOWNGRADE: the default everywhere is [SensorDefectMap.IDENTITY] (no defects, zero dark current) which
 * changes nothing when consumed; a real map only engages when a dark frame / hot-pixel list is supplied
 * (device-gated). Building the map is a one-time per-sensor cost (cache the result), off the cold-start path.
 */
class SensorDefectMap internal constructor(
    val width: Int,
    val height: Int,
    /** Ascending, deduped linear indices (`y*width + x`) of hot/defective photosites. */
    val defectIndices: IntArray,
    /** Measured mean dark-current offset above black across non-defect photosites, in raw codes (telemetry). */
    val darkCurrentMeanOffset: Float,
    val source: String
) {
    val defectCount: Int get() = defectIndices.size

    /** True when consuming this map changes nothing (no hot pixels to reject). */
    val isIdentity: Boolean get() = defectIndices.isEmpty()

    val signature: String
        get() = String.format(
            Locale.US,
            "defectMap|source=%s|defects=%d|darkCurrentMeanOffset=%.4f",
            source,
            defectIndices.size,
            darkCurrentMeanOffset
        )

    /** True if photosite (x, y) is flagged defective. */
    fun isDefect(x: Int, y: Int): Boolean {
        if (defectIndices.isEmpty() || width <= 0) return false
        if (x < 0 || y < 0 || x >= width || y >= height) return false
        return Arrays.binarySearch(defectIndices, y * width + x) >= 0
    }

    /**
     * The corrected raw code for photosite (x, y): the original sample when it is not a defect, otherwise
     * the orthogonal ±1 four-neighbour average (edge-clamped, integer round-half-up). This models the
     * neighbour-average STEP of the `demosaic_vulkan.comp.calibratedRaw()` hot-pixel branch in raw-code
     * space; it is NOT a bit-exact replica — the shader averages in float (`0.25*sum`) and additionally
     * subtracts black + dark-current, normalises by (white-black) and applies white balance. For an
     * identity map (no defects) it returns the original sample for any frame dimensions; if the frame
     * dimensions do not match the map's build dimensions it returns the sample unchanged rather than
     * mis-correct a wrong site.
     */
    fun correctedSample(samples: IntArray, x: Int, y: Int, frameWidth: Int, frameHeight: Int): Int {
        if (x < 0 || y < 0 || x >= frameWidth || y >= frameHeight) return 0
        val idx = y * frameWidth + x
        val original = samples.getOrElse(idx) { 0 }
        if (defectIndices.isEmpty()) return original
        // defectIndices are bound to this map's build dimensions; if the frame does not match we cannot
        // trust the linear-index lookup, so leave the sample untouched rather than correct a wrong site.
        if (frameWidth != width || frameHeight != height) return original
        if (Arrays.binarySearch(defectIndices, idx) < 0) return original
        val sum =
            clampedSample(samples, x + 1, y, frameWidth, frameHeight) +
                clampedSample(samples, x - 1, y, frameWidth, frameHeight) +
                clampedSample(samples, x, y + 1, frameWidth, frameHeight) +
                clampedSample(samples, x, y - 1, frameWidth, frameHeight)
        return (sum + 2) / 4
    }

    /**
     * Upload-ready hot-pixel mask matching `demosaic_vulkan.comp` binding 3 (`r8ui`): one byte per
     * photosite, 1 = defect, 0 = good. One-time allocation per sensor (off the cold-start path).
     */
    fun toHotPixelMask(): ByteArray {
        val mask = ByteArray(maxOf(width, 0) * maxOf(height, 0))
        for (index in defectIndices) {
            if (index in mask.indices) mask[index] = 1
        }
        return mask
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SensorDefectMap) return false
        return width == other.width &&
            height == other.height &&
            darkCurrentMeanOffset == other.darkCurrentMeanOffset &&
            source == other.source &&
            defectIndices.contentEquals(other.defectIndices)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + darkCurrentMeanOffset.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + defectIndices.contentHashCode()
        return result
    }

    private fun clampedSample(samples: IntArray, x: Int, y: Int, w: Int, h: Int): Int {
        val cx = x.coerceIn(0, w - 1)
        val cy = y.coerceIn(0, h - 1)
        return samples.getOrElse(cy * w + cx) { 0 }
    }

    companion object {
        /** No-defect, zero-dark-current sentinel — consuming it changes nothing (the no-downgrade default). */
        val IDENTITY = SensorDefectMap(0, 0, IntArray(0), 0f, "identity-no-defect-map")

        /** A dimensioned identity map (no defects) for a known sensor resolution. */
        fun identity(width: Int, height: Int): SensorDefectMap =
            SensorDefectMap(maxOf(width, 0), maxOf(height, 0), IntArray(0), 0f, "identity-no-defect-map")
    }
}

object SensorDefectMapPolicy {

    /**
     * Tunables for dark-frame hot-pixel detection.
     *
     * A photosite is flagged hot when its excess over black exceeds BOTH an absolute floor and a robust
     * statistical bound (`mean + sigma·std` of the dark-frame excess). [maxDefectFraction] is a mis-capture
     * guard: if more than this fraction of the frame would be flagged, the frame was not actually dark, so
     * the build falls back to IDENTITY rather than reject real signal as defects (NO-DOWNGRADE).
     */
    data class Config(
        val hotPixelFloorCodes: Int = 64,
        val hotPixelSigma: Float = 8f,
        val maxDefectFraction: Float = 0.02f
    )

    /**
     * Build a hot-pixel map from a captured dark frame (sensor covered). Hot pixels are isolated bright
     * outliers; uniform dark current is reported via [SensorDefectMap.darkCurrentMeanOffset], not flagged.
     * Returns an identity map when the frame is genuinely dark (no outliers) or fails the mis-capture guard.
     */
    fun fromDarkFrame(
        samples: IntArray,
        width: Int,
        height: Int,
        blackLevel: Int,
        whiteLevel: Int,
        config: Config = Config()
    ): SensorDefectMap {
        val total = width * height
        if (width <= 0 || height <= 0 || total <= 0 || samples.size < total) {
            return SensorDefectMap.identity(width, height)
        }
        val black = blackLevel.coerceAtLeast(0)
        // whiteLevel is reserved for the (device-gated) per-pixel dark-current rate map; hot-pixel
        // detection deliberately does NOT gate on it — a sample at/above white in a dark frame is the
        // strongest hot-pixel evidence and must be flagged, not excluded.

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until total) {
            val excess = (samples[i] - black).toDouble()
            sum += excess
            sumSq += excess * excess
        }
        val mean = sum / total
        val variance = (sumSq / total - mean * mean).coerceAtLeast(0.0)
        val std = sqrt(variance)
        val threshold = maxOf(config.hotPixelFloorCodes.toDouble(), mean + config.hotPixelSigma * std)

        val defects = ArrayList<Int>()
        for (i in 0 until total) {
            val sample = samples[i]
            if (sample > black && (sample - black) > threshold) {
                defects.add(i)
            }
        }

        // Mis-capture guard: too many "defects" means the frame was not dark — never reject real signal.
        if (defects.size > total.toDouble() * config.maxDefectFraction) {
            return SensorDefectMap.identity(width, height)
        }

        val defectArray = defects.toIntArray() // iterated in ascending i, already sorted + unique
        val darkMean = darkCurrentMean(samples, total, black, defectArray)
        val source = if (defectArray.isEmpty()) {
            "dark-frame-no-defects"
        } else {
            "dark-frame:${defectArray.size}-hot-pixels"
        }
        return SensorDefectMap(width, height, defectArray, darkMean, source)
    }

    /**
     * Build a hot-pixel map from explicit defect coordinates (e.g. a vendor `STATISTICS_HOT_PIXEL_MAP`).
     * Out-of-range coordinates are ignored; duplicates collapse; an empty list yields an identity map.
     */
    fun fromHotPixelCoordinates(
        coordinates: List<Pair<Int, Int>>,
        width: Int,
        height: Int
    ): SensorDefectMap {
        if (width <= 0 || height <= 0) return SensorDefectMap.identity(width, height)
        val indices = sortedSetOf<Int>()
        for ((x, y) in coordinates) {
            if (x in 0 until width && y in 0 until height) {
                indices.add(y * width + x)
            }
        }
        if (indices.isEmpty()) return SensorDefectMap.identity(width, height)
        return SensorDefectMap(
            width,
            height,
            indices.toIntArray(),
            darkCurrentMeanOffset = 0f,
            source = "hot-pixel-coordinates:${indices.size}"
        )
    }

    private fun darkCurrentMean(samples: IntArray, total: Int, black: Int, defectIndices: IntArray): Float {
        if (total <= 0) return 0f
        var sum = 0.0
        for (i in 0 until total) {
            sum += maxOf(0, samples[i] - black)
        }
        // Subtract the defect contribution so hot pixels do not inflate the dark-current estimate.
        for (index in defectIndices) {
            sum -= maxOf(0, samples[index] - black)
        }
        val nonDefect = total - defectIndices.size
        return if (nonDefect > 0) (sum / nonDefect).toFloat() else 0f
    }
}
