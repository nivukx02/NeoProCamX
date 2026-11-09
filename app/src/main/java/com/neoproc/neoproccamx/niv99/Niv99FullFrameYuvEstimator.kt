package com.neoproc.neoproccamx.niv99

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Read-only view of the bounded YUV analysis proxy already produced by the camera pipeline.
 * Implementations retain ownership of their arrays; NIV99 never stores or modifies those arrays.
 */
interface Niv99YuvFrameView {
    val width: Int
    val height: Int
    val yBytes: ByteArray
    val uBytes: ByteArray
    val vBytes: ByteArray
    val yRowStride: Int
    val yPixelStride: Int
    val uRowStride: Int
    val vRowStride: Int
    val uPixelStride: Int
    val vPixelStride: Int
    val timestamp: Long
    val bitDepth: Int
}

enum class Niv99YuvEstimateStatus {
    VALID,
    NOT_CONFIGURED,
    INVALID_IDENTITY,
    WRONG_TIMELINE,
    METADATA_NOT_FOUND,
    INVALID_PLANE,
    INSUFFICIENT_EVIDENCE,
    STALE_ESTIMATE,
    FUTURE_ESTIMATE
}

/**
 * A bounded, post-ISP residual correction. [sourceIdentity] always identifies the analysis image
 * that supplied the evidence; it is never rewritten to impersonate a later rendered frame.
 */
class Niv99YuvColorEstimate {
    val sourceIdentity = Niv99FrameIdentity()
    var redCorrection: Float = 1f
    var greenCorrection: Float = 1f
    var blueCorrection: Float = 1f
    var confidence: Float = 0f
    var consensusFraction: Float = 0f
    var neutralTileFraction: Float = 0f
    var meanLuma: Float = 0f
    var clippingFraction: Float = 0f
    var lumaNoiseSigma: Float = 0f
    var chromaNoiseSigma: Float = 0f
    var sampledPointCount: Int = 0
    var acceptedTileCount: Int = 0
    var totalTileCount: Int = 0

    fun clear(): Niv99YuvColorEstimate {
        sourceIdentity.set("", -1L, -1L, -1L)
        redCorrection = 1f
        greenCorrection = 1f
        blueCorrection = 1f
        confidence = 0f
        consensusFraction = 0f
        neutralTileFraction = 0f
        meanLuma = 0f
        clippingFraction = 0f
        lumaNoiseSigma = 0f
        chromaNoiseSigma = 0f
        sampledPointCount = 0
        acceptedTileCount = 0
        totalTileCount = 0
        return this
    }

    fun copyFrom(other: Niv99YuvColorEstimate): Niv99YuvColorEstimate {
        sourceIdentity.copyFrom(other.sourceIdentity)
        redCorrection = other.redCorrection
        greenCorrection = other.greenCorrection
        blueCorrection = other.blueCorrection
        confidence = other.confidence
        consensusFraction = other.consensusFraction
        neutralTileFraction = other.neutralTileFraction
        meanLuma = other.meanLuma
        clippingFraction = other.clippingFraction
        lumaNoiseSigma = other.lumaNoiseSigma
        chromaNoiseSigma = other.chromaNoiseSigma
        sampledPointCount = other.sampledPointCount
        acceptedTileCount = other.acceptedTileCount
        totalTileCount = other.totalTileCount
        return this
    }

    fun isValid(): Boolean =
        sourceIdentity.isComplete() &&
            redCorrection.isFinite() && redCorrection in MIN_CORRECTION..MAX_CORRECTION &&
            greenCorrection.isFinite() && greenCorrection in MIN_CORRECTION..MAX_CORRECTION &&
            blueCorrection.isFinite() && blueCorrection in MIN_CORRECTION..MAX_CORRECTION &&
            confidence.isFinite() && confidence in 0f..1f && confidence > 0f &&
            consensusFraction.isFinite() && consensusFraction in 0f..1f &&
            neutralTileFraction.isFinite() && neutralTileFraction in 0f..1f &&
            meanLuma.isFinite() && meanLuma in 0f..1f &&
            clippingFraction.isFinite() && clippingFraction in 0f..1f &&
            lumaNoiseSigma.isFinite() && lumaNoiseSigma >= 0f &&
            chromaNoiseSigma.isFinite() && chromaNoiseSigma >= 0f &&
            sampledPointCount > 0 && acceptedTileCount > 0 && totalTileCount > 0

    companion object {
        const val MIN_CORRECTION = 0.92f
        const val MAX_CORRECTION = 1.08f
    }
}

/**
 * Allocation-free, full-frame residual illuminant estimator for processed Video YUV frames.
 *
 * The estimator samples a uniformly distributed grid over the complete image, rejects shadows,
 * highlights, saturated colours and high-variance tiles, then fuses neutral-tile, Gray-World,
 * Shades-of-Gray and Gray-Edge votes with a median/MAD consensus. The result is intentionally a
 * small correction around the Camera2/HAL result rather than a replacement for sensor calibration.
 */
class Niv99FullFrameYuvEstimator(
    private val tileColumns: Int = DEFAULT_TILE_COLUMNS,
    private val tileRows: Int = DEFAULT_TILE_ROWS,
    private val maximumSamplePoints: Int = DEFAULT_MAXIMUM_SAMPLE_POINTS
) {
    private val tileCapacity = tileColumns * tileRows
    private val tileSumY = DoubleArray(tileCapacity)
    private val tileSumR = DoubleArray(tileCapacity)
    private val tileSumG = DoubleArray(tileCapacity)
    private val tileSumB = DoubleArray(tileCapacity)
    private val tileSquareY = DoubleArray(tileCapacity)
    private val tileDiffY2 = DoubleArray(tileCapacity)
    private val tileDiffCb2 = DoubleArray(tileCapacity)
    private val tileDiffCr2 = DoubleArray(tileCapacity)
    private val tileCounts = IntArray(tileCapacity)
    private val tileDiffCounts = IntArray(tileCapacity)
    private val candidateLogRed = FloatArray(tileCapacity + ENSEMBLE_CANDIDATES)
    private val candidateLogBlue = FloatArray(tileCapacity + ENSEMBLE_CANDIDATES)
    private val candidateWeight = FloatArray(tileCapacity + ENSEMBLE_CANDIDATES)
    private val medianScratchRed = FloatArray(tileCapacity + ENSEMBLE_CANDIDATES)
    private val medianScratchBlue = FloatArray(tileCapacity + ENSEMBLE_CANDIDATES)
    private val residualScratch = FloatArray(tileCapacity + ENSEMBLE_CANDIDATES)
    private val lumaNoiseScratch = FloatArray(tileCapacity)
    private val chromaNoiseScratch = FloatArray(tileCapacity)
    private var candidateCount = 0

    init {
        require(tileColumns in 2..MAX_TILE_AXIS)
        require(tileRows in 2..MAX_TILE_AXIS)
        require(maximumSamplePoints >= MINIMUM_SAMPLE_POINTS)
    }

    fun estimate(
        identity: Niv99FrameIdentity,
        frame: Niv99YuvFrameView,
        output: Niv99YuvColorEstimate
    ): Niv99YuvEstimateStatus {
        output.clear()
        if (!identity.isComplete() || identity.sensorTimestampNs != frame.timestamp) {
            return Niv99YuvEstimateStatus.INVALID_IDENTITY
        }
        if (!validFrame(frame)) return Niv99YuvEstimateStatus.INVALID_PLANE

        clearAccumulators()
        val chromaWidth = (frame.width + 1) / 2
        val chromaHeight = (frame.height + 1) / 2
        val samplingStep = samplingStep(chromaWidth, chromaHeight)
        val maximumCode = (1 shl frame.bitDepth) - 1
        val chromaCenter = (1 shl (frame.bitDepth - 1)).toFloat()
        val chromaSpan = maximumCode * VIDEO_CHROMA_RANGE_FRACTION
        var sampled = 0
        var usable = 0
        var clipped = 0
        var globalSumY = 0.0
        var globalSumR = 0.0
        var globalSumG = 0.0
        var globalSumB = 0.0
        var globalPowerR = 0.0
        var globalPowerG = 0.0
        var globalPowerB = 0.0
        var globalDiffR2 = 0.0
        var globalDiffG2 = 0.0
        var globalDiffB2 = 0.0
        var globalDiffCount = 0

        var chromaY = 0
        while (chromaY < chromaHeight) {
            var previousValid = false
            var previousR = 0f
            var previousG = 0f
            var previousB = 0f
            var previousY = 0f
            var previousCb = 0f
            var previousCr = 0f
            var chromaX = 0
            while (chromaX < chromaWidth) {
                val sourceX = min(chromaX * 2 + 1, frame.width - 1)
                val sourceY = min(chromaY * 2 + 1, frame.height - 1)
                val yCode = readCode(
                    frame.yBytes,
                    sourceY * frame.yRowStride + sourceX * frame.yPixelStride,
                    frame.bitDepth
                )
                val uCode = readCode(
                    frame.uBytes,
                    chromaY * frame.uRowStride + chromaX * frame.uPixelStride,
                    frame.bitDepth
                )
                val vCode = readCode(
                    frame.vBytes,
                    chromaY * frame.vRowStride + chromaX * frame.vPixelStride,
                    frame.bitDepth
                )
                sampled += 1
                val y = normalizeLuma(yCode, maximumCode)
                if (y <= SHADOW_REJECTION || y >= HIGHLIGHT_REJECTION) {
                    if (y >= HIGHLIGHT_REJECTION) clipped += 1
                    previousValid = false
                    chromaX += samplingStep
                    continue
                }
                val cb = ((uCode - chromaCenter) / chromaSpan).coerceIn(-0.5f, 0.5f)
                val cr = ((vCode - chromaCenter) / chromaSpan).coerceIn(-0.5f, 0.5f)
                val red = y + BT709_RED_CR * cr
                val green = y - BT709_GREEN_CB * cb - BT709_GREEN_CR * cr
                val blue = y + BT709_BLUE_CB * cb
                val minimum = min(red, min(green, blue))
                val maximum = max(red, max(green, blue))
                if (minimum <= CHANNEL_FLOOR || maximum >= CHANNEL_CEILING ||
                    !red.isFinite() || !green.isFinite() || !blue.isFinite()
                ) {
                    previousValid = false
                    chromaX += samplingStep
                    continue
                }

                val tileX = min(sourceX * tileColumns / frame.width, tileColumns - 1)
                val tileY = min(sourceY * tileRows / frame.height, tileRows - 1)
                val tile = tileY * tileColumns + tileX
                tileSumY[tile] += y
                tileSumR[tile] += red
                tileSumG[tile] += green
                tileSumB[tile] += blue
                tileSquareY[tile] += y.toDouble() * y
                tileCounts[tile] += 1
                globalSumY += y
                globalSumR += red
                globalSumG += green
                globalSumB += blue
                val red2 = red.toDouble() * red
                val green2 = green.toDouble() * green
                val blue2 = blue.toDouble() * blue
                globalPowerR += red2 * red2
                globalPowerG += green2 * green2
                globalPowerB += blue2 * blue2
                usable += 1

                if (previousValid) {
                    val deltaY = y - previousY
                    val deltaCb = cb - previousCb
                    val deltaCr = cr - previousCr
                    val deltaR = red - previousR
                    val deltaG = green - previousG
                    val deltaB = blue - previousB
                    tileDiffY2[tile] += deltaY.toDouble() * deltaY
                    tileDiffCb2[tile] += deltaCb.toDouble() * deltaCb
                    tileDiffCr2[tile] += deltaCr.toDouble() * deltaCr
                    tileDiffCounts[tile] += 1
                    globalDiffR2 += deltaR.toDouble() * deltaR
                    globalDiffG2 += deltaG.toDouble() * deltaG
                    globalDiffB2 += deltaB.toDouble() * deltaB
                    globalDiffCount += 1
                }
                previousR = red
                previousG = green
                previousB = blue
                previousY = y
                previousCb = cb
                previousCr = cr
                previousValid = true
                chromaX += samplingStep
            }
            chromaY += samplingStep
        }

        if (usable < MINIMUM_USABLE_SAMPLES) {
            return Niv99YuvEstimateStatus.INSUFFICIENT_EVIDENCE
        }

        var acceptedTiles = 0
        var noiseTiles = 0
        for (tile in 0 until tileCapacity) {
            val count = tileCounts[tile]
            if (count < MINIMUM_SAMPLES_PER_TILE) continue
            val inverseCount = 1.0 / count
            val meanY = (tileSumY[tile] * inverseCount).toFloat()
            val red = (tileSumR[tile] * inverseCount).toFloat()
            val green = (tileSumG[tile] * inverseCount).toFloat()
            val blue = (tileSumB[tile] * inverseCount).toFloat()
            val meanSignal = (red + 2f * green + blue) * 0.25f
            val chromaSpread = max(abs(red - green), abs(blue - green)) /
                max(meanSignal, MIN_SIGNAL)
            val signalWeight = Niv99Math.smoothstep(0.07f, 0.24f, meanY) *
                (1f - Niv99Math.smoothstep(0.76f, 0.94f, meanY))
            val neutralWeight = 1f - Niv99Math.smoothstep(
                NEUTRAL_CHROMA_START,
                NEUTRAL_CHROMA_END,
                chromaSpread
            )
            val varianceY = max(
                (tileSquareY[tile] * inverseCount - meanY.toDouble() * meanY).toFloat(),
                0f
            )
            val structureWeight = 1f - Niv99Math.smoothstep(
                FLAT_VARIANCE_START,
                FLAT_VARIANCE_END,
                varianceY
            )
            val reliability = signalWeight * neutralWeight * (0.55f + 0.45f * structureWeight)
            if (reliability >= MINIMUM_TILE_RELIABILITY) {
                addCandidate(red, green, blue, reliability * sqrt(count.toFloat()))
                acceptedTiles += 1
            }

            val differenceCount = tileDiffCounts[tile]
            if (differenceCount >= MINIMUM_NOISE_DIFFERENCES && structureWeight > 0.2f) {
                val lumaSigma = sqrt(
                    max(tileDiffY2[tile] / (2.0 * differenceCount), 0.0)
                ).toFloat()
                val chromaSigma = sqrt(
                    max(
                        (tileDiffCb2[tile] + tileDiffCr2[tile]) /
                            (4.0 * differenceCount),
                        0.0
                    )
                ).toFloat()
                if (lumaSigma.isFinite() && chromaSigma.isFinite()) {
                    lumaNoiseScratch[noiseTiles] = lumaSigma
                    chromaNoiseScratch[noiseTiles] = chromaSigma
                    noiseTiles += 1
                }
            }
        }

        addGlobalEnsembleCandidates(
            usable = usable,
            redSum = globalSumR,
            greenSum = globalSumG,
            blueSum = globalSumB,
            redPower = globalPowerR,
            greenPower = globalPowerG,
            bluePower = globalPowerB,
            redDifference = globalDiffR2,
            greenDifference = globalDiffG2,
            blueDifference = globalDiffB2,
            differenceCount = globalDiffCount
        )
        if (candidateCount < MINIMUM_CANDIDATES || acceptedTiles <= 0) {
            return Niv99YuvEstimateStatus.INSUFFICIENT_EVIDENCE
        }

        for (index in 0 until candidateCount) {
            medianScratchRed[index] = candidateLogRed[index]
            medianScratchBlue[index] = candidateLogBlue[index]
        }
        val medianRed = Niv99Math.medianInPlace(medianScratchRed, candidateCount)
        val medianBlue = Niv99Math.medianInPlace(medianScratchBlue, candidateCount)
        for (index in 0 until candidateCount) {
            val redDelta = candidateLogRed[index] - medianRed
            val blueDelta = candidateLogBlue[index] - medianBlue
            residualScratch[index] = sqrt(redDelta * redDelta + blueDelta * blueDelta)
        }
        val medianResidual = Niv99Math.medianInPlace(residualScratch, candidateCount)
        val consensusRadius = (medianResidual * MAD_CONSENSUS_SCALE + MIN_CONSENSUS_RADIUS)
            .coerceIn(MIN_CONSENSUS_RADIUS, MAX_CONSENSUS_RADIUS)
        var weightedRed = 0.0
        var weightedBlue = 0.0
        var acceptedWeight = 0.0
        var totalWeight = 0.0
        var consensusCandidates = 0
        for (index in 0 until candidateCount) {
            val baseWeight = max(candidateWeight[index], 0f)
            totalWeight += baseWeight
            val redDelta = candidateLogRed[index] - medianRed
            val blueDelta = candidateLogBlue[index] - medianBlue
            val residual = sqrt(redDelta * redDelta + blueDelta * blueDelta)
            if (residual > consensusRadius) continue
            val normalized = residual / consensusRadius
            val robust = 1f - normalized * normalized
            val weight = baseWeight * robust * robust
            if (weight <= 0f) continue
            weightedRed += candidateLogRed[index] * weight
            weightedBlue += candidateLogBlue[index] * weight
            acceptedWeight += weight
            consensusCandidates += 1
        }
        if (acceptedWeight <= 0.0 || consensusCandidates <= 0) {
            return Niv99YuvEstimateStatus.INSUFFICIENT_EVIDENCE
        }

        val fusedRed = (weightedRed / acceptedWeight).toFloat()
        val fusedBlue = (weightedBlue / acceptedWeight).toFloat()
        val consensusFraction = if (totalWeight > 0.0) {
            (acceptedWeight / totalWeight).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        val tileSupport = Niv99Math.smoothstep(2f, 18f, acceptedTiles.toFloat())
        val sampleSupport = Niv99Math.smoothstep(
            MINIMUM_USABLE_SAMPLES.toFloat(),
            STRONG_SAMPLE_SUPPORT.toFloat(),
            usable.toFloat()
        )
        val clippingFraction = if (sampled > 0) clipped.toFloat() / sampled else 0f
        val clippingConfidence = 1f - Niv99Math.smoothstep(0.02f, 0.20f, clippingFraction)
        val confidence = (
            0.38f * consensusFraction +
                0.30f * tileSupport +
                0.20f * sampleSupport +
                0.12f * clippingConfidence
            ).coerceIn(MINIMUM_PUBLISHED_CONFIDENCE, 1f)

        output.sourceIdentity.copyFrom(identity)
        output.redCorrection = exp(fusedRed.toDouble()).toFloat()
            .coerceIn(Niv99YuvColorEstimate.MIN_CORRECTION, Niv99YuvColorEstimate.MAX_CORRECTION)
        output.greenCorrection = 1f
        output.blueCorrection = exp(fusedBlue.toDouble()).toFloat()
            .coerceIn(Niv99YuvColorEstimate.MIN_CORRECTION, Niv99YuvColorEstimate.MAX_CORRECTION)
        output.confidence = confidence
        output.consensusFraction = consensusFraction
        output.neutralTileFraction = (acceptedTiles.toFloat() / tileCapacity).coerceIn(0f, 1f)
        output.meanLuma = (globalSumY / usable).toFloat().coerceIn(0f, 1f)
        output.clippingFraction = clippingFraction.coerceIn(0f, 1f)
        if (noiseTiles > 0) {
            sortPrefix(lumaNoiseScratch, noiseTiles)
            sortPrefix(chromaNoiseScratch, noiseTiles)
            val noiseIndex = ((noiseTiles - 1) * NOISE_QUANTILE).toInt()
            output.lumaNoiseSigma = lumaNoiseScratch[noiseIndex].coerceAtLeast(0f)
            output.chromaNoiseSigma = chromaNoiseScratch[noiseIndex].coerceAtLeast(0f)
        }
        output.sampledPointCount = usable
        output.acceptedTileCount = acceptedTiles
        output.totalTileCount = tileCapacity
        return if (output.isValid()) {
            Niv99YuvEstimateStatus.VALID
        } else {
            output.clear()
            Niv99YuvEstimateStatus.INSUFFICIENT_EVIDENCE
        }
    }

    private fun addGlobalEnsembleCandidates(
        usable: Int,
        redSum: Double,
        greenSum: Double,
        blueSum: Double,
        redPower: Double,
        greenPower: Double,
        bluePower: Double,
        redDifference: Double,
        greenDifference: Double,
        blueDifference: Double,
        differenceCount: Int
    ) {
        if (usable <= 0 || greenSum <= 0.0) return
        val red = (redSum / usable).toFloat()
        val green = (greenSum / usable).toFloat()
        val blue = (blueSum / usable).toFloat()
        val mean = (red + 2f * green + blue) * 0.25f
        val globalChroma = max(abs(red - green), abs(blue - green)) / max(mean, MIN_SIGNAL)
        val sceneNeutrality = 1f - Niv99Math.smoothstep(
            GLOBAL_CHROMA_START,
            GLOBAL_CHROMA_END,
            globalChroma
        )
        if (sceneNeutrality > 0f) {
            addCandidate(red, green, blue, GLOBAL_GRAY_WEIGHT * sceneNeutrality)
            val shadesRed = sqrt(sqrt(max(redPower / usable, 0.0))).toFloat()
            val shadesGreen = sqrt(sqrt(max(greenPower / usable, 0.0))).toFloat()
            val shadesBlue = sqrt(sqrt(max(bluePower / usable, 0.0))).toFloat()
            addCandidate(
                shadesRed,
                shadesGreen,
                shadesBlue,
                GLOBAL_SHADES_WEIGHT * sceneNeutrality
            )
        }
        if (differenceCount <= 0) return
        val edgeRed = sqrt(max(redDifference / differenceCount, 0.0)).toFloat()
        val edgeGreen = sqrt(max(greenDifference / differenceCount, 0.0)).toFloat()
        val edgeBlue = sqrt(max(blueDifference / differenceCount, 0.0)).toFloat()
        val edgeMean = (edgeRed + 2f * edgeGreen + edgeBlue) * 0.25f
        val edgeChroma = max(abs(edgeRed - edgeGreen), abs(edgeBlue - edgeGreen)) /
            max(edgeMean, MIN_SIGNAL)
        val edgeReliability = 1f - Niv99Math.smoothstep(
            EDGE_CHROMA_START,
            EDGE_CHROMA_END,
            edgeChroma
        )
        if (edgeMean >= MINIMUM_EDGE_SIGNAL && edgeReliability > 0f) {
            addCandidate(edgeRed, edgeGreen, edgeBlue, GLOBAL_EDGE_WEIGHT * edgeReliability)
        }
    }

    private fun addCandidate(red: Float, green: Float, blue: Float, weight: Float) {
        if (candidateCount >= candidateLogRed.size || weight <= 0f ||
            !red.isFinite() || !green.isFinite() || !blue.isFinite() ||
            red <= MIN_SIGNAL || green <= MIN_SIGNAL || blue <= MIN_SIGNAL
        ) return
        val redCorrection = (green / red).coerceIn(RAW_MIN_CORRECTION, RAW_MAX_CORRECTION)
        val blueCorrection = (green / blue).coerceIn(RAW_MIN_CORRECTION, RAW_MAX_CORRECTION)
        candidateLogRed[candidateCount] = ln(redCorrection.toDouble()).toFloat()
        candidateLogBlue[candidateCount] = ln(blueCorrection.toDouble()).toFloat()
        candidateWeight[candidateCount] = weight
        candidateCount += 1
    }

    private fun clearAccumulators() {
        tileSumY.fill(0.0)
        tileSumR.fill(0.0)
        tileSumG.fill(0.0)
        tileSumB.fill(0.0)
        tileSquareY.fill(0.0)
        tileDiffY2.fill(0.0)
        tileDiffCb2.fill(0.0)
        tileDiffCr2.fill(0.0)
        tileCounts.fill(0)
        tileDiffCounts.fill(0)
        candidateCount = 0
    }

    private fun samplingStep(chromaWidth: Int, chromaHeight: Int): Int {
        var step = 1
        while (((chromaWidth + step - 1) / step).toLong() *
            ((chromaHeight + step - 1) / step) > maximumSamplePoints
        ) {
            step += 1
        }
        return step
    }

    private fun validFrame(frame: Niv99YuvFrameView): Boolean {
        if (frame.width < 4 || frame.height < 4 || frame.timestamp <= 0L) return false
        if (frame.bitDepth != 8 && frame.bitDepth != 10) return false
        val bytesPerSample = if (frame.bitDepth > 8) 2 else 1
        if (frame.yPixelStride < bytesPerSample || frame.uPixelStride < bytesPerSample ||
            frame.vPixelStride < bytesPerSample
        ) return false
        val chromaWidth = (frame.width + 1) / 2
        val chromaHeight = (frame.height + 1) / 2
        return planeFits(
            frame.yBytes.size,
            frame.width,
            frame.height,
            frame.yRowStride,
            frame.yPixelStride,
            bytesPerSample
        ) && planeFits(
            frame.uBytes.size,
            chromaWidth,
            chromaHeight,
            frame.uRowStride,
            frame.uPixelStride,
            bytesPerSample
        ) && planeFits(
            frame.vBytes.size,
            chromaWidth,
            chromaHeight,
            frame.vRowStride,
            frame.vPixelStride,
            bytesPerSample
        )
    }

    private fun planeFits(
        size: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        bytesPerSample: Int
    ): Boolean {
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return false
        val minimumRowBytes = (width - 1L) * pixelStride + bytesPerSample
        if (rowStride.toLong() < minimumRowBytes) return false
        val required = (height - 1L) * rowStride + minimumRowBytes
        return required in 1..size.toLong()
    }

    private fun readCode(bytes: ByteArray, index: Int, bitDepth: Int): Int {
        if (bitDepth <= 8) return bytes[index].toInt() and 0xff
        val packed = (bytes[index].toInt() and 0xff) or
            ((bytes[index + 1].toInt() and 0xff) shl 8)
        val maximumCode = (1 shl bitDepth) - 1
        return if (packed > maximumCode) packed ushr (16 - bitDepth) else packed
    }

    private fun normalizeLuma(code: Int, maximumCode: Int): Float {
        val black = maximumCode * VIDEO_LUMA_BLACK_FRACTION
        val span = maximumCode * VIDEO_LUMA_RANGE_FRACTION
        return ((code - black) / span).coerceIn(0f, 1f)
    }

    private fun sortPrefix(values: FloatArray, count: Int) {
        for (index in 1 until count) {
            val value = values[index]
            var insertion = index - 1
            while (insertion >= 0 && values[insertion] > value) {
                values[insertion + 1] = values[insertion]
                insertion -= 1
            }
            values[insertion + 1] = value
        }
    }

    companion object {
        private const val DEFAULT_TILE_COLUMNS = 12
        private const val DEFAULT_TILE_ROWS = 8
        private const val MAX_TILE_AXIS = 32
        private const val DEFAULT_MAXIMUM_SAMPLE_POINTS = 32_768
        private const val MINIMUM_SAMPLE_POINTS = 256
        private const val MINIMUM_USABLE_SAMPLES = 96
        private const val STRONG_SAMPLE_SUPPORT = 2_048
        private const val MINIMUM_SAMPLES_PER_TILE = 3
        private const val MINIMUM_NOISE_DIFFERENCES = 2
        private const val ENSEMBLE_CANDIDATES = 3
        private const val MINIMUM_CANDIDATES = 2
        private const val MINIMUM_TILE_RELIABILITY = 0.045f
        private const val MINIMUM_PUBLISHED_CONFIDENCE = 0.18f
        private const val MIN_SIGNAL = 1.0e-5f
        private const val CHANNEL_FLOOR = 0.01f
        private const val CHANNEL_CEILING = 0.99f
        private const val SHADOW_REJECTION = 0.035f
        private const val HIGHLIGHT_REJECTION = 0.97f
        private const val NEUTRAL_CHROMA_START = 0.025f
        private const val NEUTRAL_CHROMA_END = 0.32f
        private const val GLOBAL_CHROMA_START = 0.035f
        private const val GLOBAL_CHROMA_END = 0.22f
        private const val EDGE_CHROMA_START = 0.08f
        private const val EDGE_CHROMA_END = 0.55f
        private const val FLAT_VARIANCE_START = 0.00008f
        private const val FLAT_VARIANCE_END = 0.018f
        private const val MINIMUM_EDGE_SIGNAL = 0.001f
        private const val GLOBAL_GRAY_WEIGHT = 0.32f
        private const val GLOBAL_SHADES_WEIGHT = 0.22f
        private const val GLOBAL_EDGE_WEIGHT = 0.12f
        private const val RAW_MIN_CORRECTION = 0.82f
        private const val RAW_MAX_CORRECTION = 1.18f
        private const val MAD_CONSENSUS_SCALE = 2.8f
        private const val MIN_CONSENSUS_RADIUS = 0.018f
        private const val MAX_CONSENSUS_RADIUS = 0.12f
        private const val NOISE_QUANTILE = 0.25f
        private const val VIDEO_LUMA_BLACK_FRACTION = 16f / 255f
        private const val VIDEO_LUMA_RANGE_FRACTION = 219f / 255f
        private const val VIDEO_CHROMA_RANGE_FRACTION = 224f / 255f
        private const val BT709_RED_CR = 1.5748f
        private const val BT709_GREEN_CB = 0.1873f
        private const val BT709_GREEN_CR = 0.4681f
        private const val BT709_BLUE_CB = 1.8556f
    }
}

/**
 * Single-slot, bounded temporal store. A renderer can consume only a same-camera, same-generation,
 * non-future estimate within both a time and frame-distance budget.
 */
class Niv99YuvEstimateStore {
    private val latest = Niv99YuvColorEstimate()
    private var cameraId = ""
    private var sessionGeneration = -1L
    private var valid = false

    @Synchronized
    fun beginTimeline(cameraId: String, sessionGeneration: Long): Boolean {
        if (cameraId.isBlank() || sessionGeneration < 0L) return false
        this.cameraId = cameraId
        this.sessionGeneration = sessionGeneration
        latest.clear()
        valid = false
        return true
    }

    @Synchronized
    fun publish(candidate: Niv99YuvColorEstimate): Niv99YuvEstimateStatus {
        if (!candidate.isValid()) return Niv99YuvEstimateStatus.INSUFFICIENT_EVIDENCE
        val identity = candidate.sourceIdentity
        if (cameraId.isBlank() || sessionGeneration < 0L) {
            return Niv99YuvEstimateStatus.NOT_CONFIGURED
        }
        if (identity.cameraId != cameraId || identity.sessionGeneration != sessionGeneration) {
            return Niv99YuvEstimateStatus.WRONG_TIMELINE
        }
        if (valid && (identity.frameNumber <= latest.sourceIdentity.frameNumber ||
                identity.sensorTimestampNs <= latest.sourceIdentity.sensorTimestampNs)
        ) {
            return Niv99YuvEstimateStatus.STALE_ESTIMATE
        }

        if (!valid || identity.sensorTimestampNs - latest.sourceIdentity.sensorTimestampNs >
            MAXIMUM_CONTINUOUS_GAP_NS
        ) {
            latest.copyFrom(candidate)
            valid = true
            return Niv99YuvEstimateStatus.VALID
        }

        val previousRed = latest.redCorrection
        val previousBlue = latest.blueCorrection
        val previousConfidence = latest.confidence
        val alpha = (MINIMUM_TEMPORAL_ALPHA +
            (MAXIMUM_TEMPORAL_ALPHA - MINIMUM_TEMPORAL_ALPHA) * candidate.confidence)
            .coerceIn(MINIMUM_TEMPORAL_ALPHA, MAXIMUM_TEMPORAL_ALPHA)
        val nextRedLog = limitedLogUpdate(previousRed, candidate.redCorrection, alpha)
        val nextBlueLog = limitedLogUpdate(previousBlue, candidate.blueCorrection, alpha)
        latest.copyFrom(candidate)
        latest.redCorrection = exp(nextRedLog.toDouble()).toFloat()
            .coerceIn(Niv99YuvColorEstimate.MIN_CORRECTION, Niv99YuvColorEstimate.MAX_CORRECTION)
        latest.greenCorrection = 1f
        latest.blueCorrection = exp(nextBlueLog.toDouble()).toFloat()
            .coerceIn(Niv99YuvColorEstimate.MIN_CORRECTION, Niv99YuvColorEstimate.MAX_CORRECTION)
        latest.confidence = Niv99Math.lerp(previousConfidence, candidate.confidence, alpha)
            .coerceIn(0f, 1f)
        valid = true
        return Niv99YuvEstimateStatus.VALID
    }

    @Synchronized
    fun copyLatestPreceding(
        target: Niv99FrameIdentity,
        output: Niv99YuvColorEstimate
    ): Niv99YuvEstimateStatus {
        output.clear()
        if (!target.isComplete()) return Niv99YuvEstimateStatus.INVALID_IDENTITY
        if (cameraId.isBlank() || sessionGeneration < 0L) {
            return Niv99YuvEstimateStatus.NOT_CONFIGURED
        }
        if (target.cameraId != cameraId || target.sessionGeneration != sessionGeneration) {
            return Niv99YuvEstimateStatus.WRONG_TIMELINE
        }
        if (!valid) return Niv99YuvEstimateStatus.INSUFFICIENT_EVIDENCE
        val source = latest.sourceIdentity
        if (source.frameNumber > target.frameNumber || source.sensorTimestampNs > target.sensorTimestampNs) {
            return Niv99YuvEstimateStatus.FUTURE_ESTIMATE
        }
        val frameLag = target.frameNumber - source.frameNumber
        val ageNs = target.sensorTimestampNs - source.sensorTimestampNs
        if (frameLag > MAXIMUM_FRAME_LAG || ageNs >= MAXIMUM_ESTIMATE_AGE_NS) {
            return Niv99YuvEstimateStatus.STALE_ESTIMATE
        }
        output.copyFrom(latest)
        // Valid neutral evidence can be sparse in saturated, dark, or highly textured scenes. Do not
        // toggle a residual correction abruptly between NIV99 and identity merely because one cadence
        // window produced no candidate. Hold recent evidence, then continuously decay it toward the
        // HAL solution. Timeline checks above still prevent any camera/session reuse, and the hard age
        // limit bounds scene-change exposure even when no new valid neutral region appears.
        if (ageNs > FULL_STRENGTH_ESTIMATE_AGE_NS) {
            val fadeProgress = (
                (ageNs - FULL_STRENGTH_ESTIMATE_AGE_NS).toDouble() /
                    (MAXIMUM_ESTIMATE_AGE_NS - FULL_STRENGTH_ESTIMATE_AGE_NS).toDouble()
                ).toFloat().coerceIn(0f, 1f)
            val freshness = 1f - Niv99Math.smoothstep(0f, 1f, fadeProgress)
            output.redCorrection = Niv99Math.lerp(1f, output.redCorrection, freshness)
            output.greenCorrection = Niv99Math.lerp(1f, output.greenCorrection, freshness)
            output.blueCorrection = Niv99Math.lerp(1f, output.blueCorrection, freshness)
            output.confidence *= freshness
            output.consensusFraction *= freshness
            output.lumaNoiseSigma *= freshness
            output.chromaNoiseSigma *= freshness
        }
        return Niv99YuvEstimateStatus.VALID
    }

    @Synchronized
    fun clear() {
        cameraId = ""
        sessionGeneration = -1L
        latest.clear()
        valid = false
    }

    private fun limitedLogUpdate(previous: Float, candidate: Float, alpha: Float): Float {
        val previousLog = ln(max(previous, MINIMUM_POSITIVE).toDouble()).toFloat()
        val candidateLog = ln(max(candidate, MINIMUM_POSITIVE).toDouble()).toFloat()
        val step = (candidateLog - previousLog).coerceIn(-MAXIMUM_LOG_STEP, MAXIMUM_LOG_STEP)
        return previousLog + step * alpha
    }

    companion object {
        private const val FULL_STRENGTH_ESTIMATE_AGE_NS = 2_000_000_000L
        private const val MAXIMUM_ESTIMATE_AGE_NS = 8_000_000_000L
        private const val MAXIMUM_CONTINUOUS_GAP_NS = 500_000_000L
        private const val MAXIMUM_FRAME_LAG = 1_024L
        private const val MINIMUM_TEMPORAL_ALPHA = 0.22f
        private const val MAXIMUM_TEMPORAL_ALPHA = 0.62f
        private const val MAXIMUM_LOG_STEP = 0.045f
        private const val MINIMUM_POSITIVE = 1.0e-6f
    }
}
