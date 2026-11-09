package com.neoproc.neoproccamx.niv99

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Fixed-capacity, structure-of-arrays tile statistics shared with native parity tests. */
class Niv99TileStatistics(val capacity: Int) {
    val centerX: FloatArray = FloatArray(capacity)
    val centerY: FloatArray = FloatArray(capacity)
    val meanRed: FloatArray = FloatArray(capacity)
    val meanGreen: FloatArray = FloatArray(capacity)
    val meanBlue: FloatArray = FloatArray(capacity)
    val varianceRed: FloatArray = FloatArray(capacity)
    val varianceGreen: FloatArray = FloatArray(capacity)
    val varianceBlue: FloatArray = FloatArray(capacity)
    val gradientRed: FloatArray = FloatArray(capacity)
    val gradientGreen: FloatArray = FloatArray(capacity)
    val gradientBlue: FloatArray = FloatArray(capacity)
    val saturationFraction: FloatArray = FloatArray(capacity)
    val shadowFraction: FloatArray = FloatArray(capacity)
    val clippingRisk: FloatArray = FloatArray(capacity)
    val signalToNoise: FloatArray = FloatArray(capacity)
    val reliability: FloatArray = FloatArray(capacity)
    val neutralConfidence: FloatArray = FloatArray(capacity)
    val redOverGreen: FloatArray = FloatArray(capacity)
    val blueOverGreen: FloatArray = FloatArray(capacity)
    val sampleCount: IntArray = IntArray(capacity)
    var columns: Int = 0
        internal set
    var rows: Int = 0
        internal set
    var count: Int = 0
        internal set
    var validCount: Int = 0
        internal set
    var frameMeanSignal: Float = 0f
        internal set
    var frameClippingFraction: Float = 0f
        internal set

    fun clear(columns: Int, rows: Int) {
        require(columns >= 0 && rows >= 0 && columns.toLong() * rows <= capacity)
        this.columns = columns
        this.rows = rows
        count = columns * rows
        validCount = 0
        frameMeanSignal = 0f
        frameClippingFraction = 0f
        for (index in 0 until count) {
            meanRed[index] = 0f
            meanGreen[index] = 0f
            meanBlue[index] = 0f
            varianceRed[index] = 0f
            varianceGreen[index] = 0f
            varianceBlue[index] = 0f
            gradientRed[index] = 0f
            gradientGreen[index] = 0f
            gradientBlue[index] = 0f
            saturationFraction[index] = 0f
            shadowFraction[index] = 0f
            clippingRisk[index] = 0f
            signalToNoise[index] = 0f
            reliability[index] = 0f
            neutralConfidence[index] = 0f
            redOverGreen[index] = 1f
            blueOverGreen[index] = 1f
            sampleCount[index] = 0
        }
    }
}

/**
 * Sensor-linear RAW statistics. Saturated, deep-shadow, high-gradient, and noise-dominated samples
 * are downweighted rather than allowed to steer a whole-frame illuminant estimate.
 */
class Niv99RawStatistics(
    private val tileWidth: Int = DEFAULT_TILE_WIDTH,
    private val tileHeight: Int = DEFAULT_TILE_HEIGHT
) {
    private val sums = DoubleArray(CHANNEL_ACCUMULATOR_COUNT)
    private val squareSums = DoubleArray(CHANNEL_ACCUMULATOR_COUNT)
    private val gradientSums = DoubleArray(CHANNEL_ACCUMULATOR_COUNT)
    private val counts = IntArray(CHANNEL_ACCUMULATOR_COUNT)

    fun compute(
        plane: Niv99LinearPlane,
        calibration: Niv99SensorCalibration,
        noiseModel: Niv99NoiseModelSnapshot?,
        output: Niv99TileStatistics
    ): Niv99ValidationResult {
        if (!plane.isValid()) return Niv99ValidationResult.INVALID_PLANE
        if (calibration.validate() != Niv99ValidationResult.VALID) {
            return Niv99ValidationResult.INVALID_CALIBRATION
        }
        if (plane.cfaPattern != calibration.cfaPattern) {
            return Niv99ValidationResult.INVALID_PLANE
        }
        val columns = (plane.width + tileWidth - 1) / tileWidth
        val rows = (plane.height + tileHeight - 1) / tileHeight
        if (columns.toLong() * rows > output.capacity) return Niv99ValidationResult.INVALID_DIMENSIONS
        output.clear(columns, rows)
        var frameSignalSum = 0.0
        var frameSampleCount = 0L
        var frameClippedCount = 0L
        for (tileY in 0 until rows) {
            val startY = tileY * tileHeight
            val endY = min(startY + tileHeight, plane.height)
            for (tileX in 0 until columns) {
                val startX = tileX * tileWidth
                val endX = min(startX + tileWidth, plane.width)
                val tileIndex = tileY * columns + tileX
                clearAccumulators()
                var tileSamples = 0
                var tileClipped = 0
                var tileShadows = 0
                for (y in startY until endY) {
                    for (x in startX until endX) {
                        val cfaChannel = cfaChannelAt(plane.cfaPattern, x, y)
                        if (cfaChannel < 0) continue
                        val black = calibration.blackLevelForCfaChannel(cfaChannel)
                        val range = max(calibration.whiteLevel - black, MIN_SIGNAL_RANGE)
                        val normalizedX = if (plane.width > 1) x.toFloat() / (plane.width - 1) else 0.5f
                        val normalizedY = if (plane.height > 1) y.toFloat() / (plane.height - 1) else 0.5f
                        val lensGain = calibration.lensShadingGain(cfaChannel, normalizedX, normalizedY)
                        val raw = plane.sample(x, y)
                        if (!raw.isFinite()) continue
                        val signal = ((raw - black) / range * lensGain).coerceIn(0f, MAX_LINEAR_SIGNAL)
                        val accumulator = accumulatorChannel(cfaChannel)
                        sums[accumulator] += signal
                        squareSums[accumulator] += signal.toDouble() * signal
                        counts[accumulator] += 1
                        if (x >= startX + 2 && y >= startY + 2) {
                            val leftRaw = plane.sample(x - 2, y)
                            val upRaw = plane.sample(x, y - 2)
                            if (leftRaw.isFinite() && upRaw.isFinite()) {
                                val left = ((leftRaw - black) / range * lensGain)
                                    .coerceIn(0f, MAX_LINEAR_SIGNAL)
                                val up = ((upRaw - black) / range * lensGain)
                                    .coerceIn(0f, MAX_LINEAR_SIGNAL)
                                gradientSums[accumulator] +=
                                    sqrt(Niv99Math.square(signal - left) + Niv99Math.square(signal - up))
                            }
                        }
                        if (signal >= SATURATION_THRESHOLD) tileClipped += 1
                        if (signal <= SHADOW_THRESHOLD) tileShadows += 1
                        tileSamples += 1
                        frameSignalSum += signal
                        frameSampleCount += 1L
                    }
                }
                frameClippedCount += tileClipped
                finishTile(
                    tileIndex = tileIndex,
                    tileX = tileX,
                    tileY = tileY,
                    columns = columns,
                    rows = rows,
                    tileSamples = tileSamples,
                    tileClipped = tileClipped,
                    tileShadows = tileShadows,
                    noiseModel = noiseModel,
                    output = output
                )
            }
        }
        output.frameMeanSignal = if (frameSampleCount > 0L) {
            (frameSignalSum / frameSampleCount).toFloat()
        } else {
            0f
        }
        output.frameClippingFraction = if (frameSampleCount > 0L) {
            frameClippedCount.toFloat() / frameSampleCount
        } else {
            0f
        }
        return Niv99ValidationResult.VALID
    }

    private fun finishTile(
        tileIndex: Int,
        tileX: Int,
        tileY: Int,
        columns: Int,
        rows: Int,
        tileSamples: Int,
        tileClipped: Int,
        tileShadows: Int,
        noiseModel: Niv99NoiseModelSnapshot?,
        output: Niv99TileStatistics
    ) {
        output.centerX[tileIndex] = (tileX + 0.5f) / columns
        output.centerY[tileIndex] = (tileY + 0.5f) / rows
        output.sampleCount[tileIndex] = tileSamples
        if (tileSamples <= 0 || counts[RED_ACCUMULATOR] <= 0 ||
            counts[GREEN_ACCUMULATOR] <= 0 || counts[BLUE_ACCUMULATOR] <= 0
        ) {
            return
        }
        val red = channelMean(RED_ACCUMULATOR)
        val green = channelMean(GREEN_ACCUMULATOR)
        val blue = channelMean(BLUE_ACCUMULATOR)
        val redVariance = channelVariance(RED_ACCUMULATOR, red)
        val greenVariance = channelVariance(GREEN_ACCUMULATOR, green)
        val blueVariance = channelVariance(BLUE_ACCUMULATOR, blue)
        val redGradient = channelGradient(RED_ACCUMULATOR)
        val greenGradient = channelGradient(GREEN_ACCUMULATOR)
        val blueGradient = channelGradient(BLUE_ACCUMULATOR)
        val saturation = tileClipped.toFloat() / tileSamples
        val shadow = tileShadows.toFloat() / tileSamples
        val modeledVariance = noiseModel?.meanVariance(red, green, blue)
            ?: max((redVariance + greenVariance + blueVariance) / 3f, MIN_VARIANCE)
        val meanSignal = (red + 2f * green + blue) * 0.25f
        val snr = meanSignal / sqrt(max(modeledVariance, MIN_VARIANCE))
        val chromaSpread = max(abs(red - green), abs(blue - green)) / max(meanSignal, MIN_SIGNAL)
        val gradient = (redGradient + 2f * greenGradient + blueGradient) * 0.25f
        val clippingPenalty = 1f - Niv99Math.smoothstep(0.005f, 0.10f, saturation)
        val shadowPenalty = 1f - Niv99Math.smoothstep(0.30f, 0.92f, shadow)
        val noiseConfidence = Niv99Math.smoothstep(1.5f, 12f, snr)
        val structurePenalty = 1f - Niv99Math.smoothstep(0.02f, 0.20f, gradient)
        val reliability = clippingPenalty * shadowPenalty * noiseConfidence
        // A neutral reflector under tungsten can be strongly red/blue-imbalanced in raw sensor space;
        // rejecting it merely because R/G or B/G differs from one would erase the illuminant evidence.
        // Only implausibly extreme single-color tiles are rejected here. Ensemble consensus performs
        // the calibrated neutral/outlier decision across the complete frame.
        val plausibleIlluminantChroma = 1f - Niv99Math.smoothstep(0.75f, 2.8f, chromaSpread)
        val neutralConfidence = reliability * structurePenalty * plausibleIlluminantChroma
        output.meanRed[tileIndex] = red
        output.meanGreen[tileIndex] = green
        output.meanBlue[tileIndex] = blue
        output.varianceRed[tileIndex] = redVariance
        output.varianceGreen[tileIndex] = greenVariance
        output.varianceBlue[tileIndex] = blueVariance
        output.gradientRed[tileIndex] = redGradient
        output.gradientGreen[tileIndex] = greenGradient
        output.gradientBlue[tileIndex] = blueGradient
        output.saturationFraction[tileIndex] = saturation
        output.shadowFraction[tileIndex] = shadow
        output.clippingRisk[tileIndex] = 1f - clippingPenalty
        output.signalToNoise[tileIndex] = snr
        output.reliability[tileIndex] = Niv99Math.saturateFinite(reliability)
        output.neutralConfidence[tileIndex] = Niv99Math.saturateFinite(neutralConfidence)
        output.redOverGreen[tileIndex] = (red / max(green, MIN_SIGNAL)).coerceIn(MIN_CHROMA_RATIO, MAX_CHROMA_RATIO)
        output.blueOverGreen[tileIndex] = (blue / max(green, MIN_SIGNAL)).coerceIn(MIN_CHROMA_RATIO, MAX_CHROMA_RATIO)
        if (reliability > MIN_VALID_RELIABILITY) output.validCount += 1
    }

    private fun clearAccumulators() {
        sums.fill(0.0)
        squareSums.fill(0.0)
        gradientSums.fill(0.0)
        counts.fill(0)
    }

    private fun channelMean(channel: Int): Float =
        if (counts[channel] > 0) (sums[channel] / counts[channel]).toFloat() else 0f

    private fun channelVariance(channel: Int, mean: Float): Float {
        val count = counts[channel]
        if (count <= 1) return 0f
        val population = squareSums[channel] / count - mean.toDouble() * mean
        return max(population.toFloat(), 0f)
    }

    private fun channelGradient(channel: Int): Float =
        if (counts[channel] > 0) (gradientSums[channel] / counts[channel]).toFloat() else 0f

    private fun accumulatorChannel(cfaChannel: Int): Int = when (cfaChannel) {
        Niv99SensorCalibration.CHANNEL_RED -> RED_ACCUMULATOR
        Niv99SensorCalibration.CHANNEL_BLUE -> BLUE_ACCUMULATOR
        else -> GREEN_ACCUMULATOR
    }

    private fun cfaChannelAt(pattern: Niv99CfaPattern, x: Int, y: Int): Int {
        val evenX = x and 1 == 0
        val evenY = y and 1 == 0
        return when (pattern) {
            Niv99CfaPattern.RGGB -> when {
                evenY && evenX -> Niv99SensorCalibration.CHANNEL_RED
                evenY -> Niv99SensorCalibration.CHANNEL_GREEN_EVEN
                evenX -> Niv99SensorCalibration.CHANNEL_GREEN_ODD
                else -> Niv99SensorCalibration.CHANNEL_BLUE
            }
            Niv99CfaPattern.GRBG -> when {
                evenY && evenX -> Niv99SensorCalibration.CHANNEL_GREEN_EVEN
                evenY -> Niv99SensorCalibration.CHANNEL_RED
                evenX -> Niv99SensorCalibration.CHANNEL_BLUE
                else -> Niv99SensorCalibration.CHANNEL_GREEN_ODD
            }
            Niv99CfaPattern.GBRG -> when {
                evenY && evenX -> Niv99SensorCalibration.CHANNEL_GREEN_EVEN
                evenY -> Niv99SensorCalibration.CHANNEL_BLUE
                evenX -> Niv99SensorCalibration.CHANNEL_RED
                else -> Niv99SensorCalibration.CHANNEL_GREEN_ODD
            }
            Niv99CfaPattern.BGGR -> when {
                evenY && evenX -> Niv99SensorCalibration.CHANNEL_BLUE
                evenY -> Niv99SensorCalibration.CHANNEL_GREEN_EVEN
                evenX -> Niv99SensorCalibration.CHANNEL_GREEN_ODD
                else -> Niv99SensorCalibration.CHANNEL_RED
            }
            Niv99CfaPattern.MONO -> Niv99SensorCalibration.CHANNEL_GREEN_EVEN
            Niv99CfaPattern.UNKNOWN -> -1
        }
    }

    companion object {
        private const val DEFAULT_TILE_WIDTH = 32
        private const val DEFAULT_TILE_HEIGHT = 32
        private const val CHANNEL_ACCUMULATOR_COUNT = 3
        private const val RED_ACCUMULATOR = 0
        private const val GREEN_ACCUMULATOR = 1
        private const val BLUE_ACCUMULATOR = 2
        private const val MIN_SIGNAL_RANGE = 1e-6f
        private const val MIN_SIGNAL = 1e-5f
        private const val MIN_VARIANCE = 1e-9f
        private const val MAX_LINEAR_SIGNAL = 1.25f
        private const val SATURATION_THRESHOLD = 0.985f
        private const val SHADOW_THRESHOLD = 0.012f
        private const val MIN_CHROMA_RATIO = 0.08f
        private const val MAX_CHROMA_RATIO = 12f
        private const val MIN_VALID_RELIABILITY = 0.02f
    }
}
