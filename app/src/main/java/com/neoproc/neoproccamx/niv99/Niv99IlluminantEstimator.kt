package com.neoproc.neoproccamx.niv99

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class Niv99IlluminantMethod {
    GRAY_WORLD,
    SHADES_OF_GRAY,
    GRAY_EDGE,
    ROBUST_NEUTRAL,
    SENSOR_CALIBRATED,
    HAL_SAME_FRAME,
    BAYESIAN_PRIOR,
    LEARNED_COMPACT
}

class Niv99IlluminantCandidate {
    var method: Niv99IlluminantMethod = Niv99IlluminantMethod.GRAY_WORLD
    var redResponseOverGreen: Float = 1f
    var blueResponseOverGreen: Float = 1f
    var confidence: Float = 0f
    var supportFraction: Float = 0f
    var residual: Float = 0f
    var accepted: Boolean = false

    fun set(
        method: Niv99IlluminantMethod,
        redResponseOverGreen: Float,
        blueResponseOverGreen: Float,
        confidence: Float,
        supportFraction: Float
    ) {
        this.method = method
        this.redResponseOverGreen = redResponseOverGreen.coerceIn(MIN_RESPONSE, MAX_RESPONSE)
        this.blueResponseOverGreen = blueResponseOverGreen.coerceIn(MIN_RESPONSE, MAX_RESPONSE)
        this.confidence = Niv99Math.saturateFinite(confidence)
        this.supportFraction = Niv99Math.saturateFinite(supportFraction)
        residual = 0f
        accepted = false
    }

    companion object {
        private const val MIN_RESPONSE = 0.08f
        private const val MAX_RESPONSE = 12f
    }
}

class Niv99IlluminantEstimate {
    val identity: Niv99FrameIdentity = Niv99FrameIdentity()
    var redResponseOverGreen: Float = 1f
    var blueResponseOverGreen: Float = 1f
    var redGain: Float = 1f
    var greenGain: Float = 1f
    var blueGain: Float = 1f
    var chromaticityX: Float = 0.3127f
    var chromaticityY: Float = 0.3290f
    var chromaticityU: Float = 0.19783f
    var chromaticityV: Float = 0.46832f
    var cctKelvin: Float = 6_500f
    var duv: Float = 0f
    var confidence: Float = 0f
    var consensusFraction: Float = 0f
    var neutralTileFraction: Float = 0f
    var clippingRisk: Float = 0f
    var illuminantClass: Niv99IlluminantClass = Niv99IlluminantClass.UNKNOWN
    var candidateCount: Int = 0
    var acceptedCandidateCount: Int = 0

    fun copyFrom(other: Niv99IlluminantEstimate): Niv99IlluminantEstimate {
        identity.copyFrom(other.identity)
        redResponseOverGreen = other.redResponseOverGreen
        blueResponseOverGreen = other.blueResponseOverGreen
        redGain = other.redGain
        greenGain = other.greenGain
        blueGain = other.blueGain
        chromaticityX = other.chromaticityX
        chromaticityY = other.chromaticityY
        chromaticityU = other.chromaticityU
        chromaticityV = other.chromaticityV
        cctKelvin = other.cctKelvin
        duv = other.duv
        confidence = other.confidence
        consensusFraction = other.consensusFraction
        neutralTileFraction = other.neutralTileFraction
        clippingRisk = other.clippingRisk
        illuminantClass = other.illuminantClass
        candidateCount = other.candidateCount
        acceptedCandidateCount = other.acceptedCandidateCount
        return this
    }
}

/** Optional learned estimate. It is accepted only as one bounded ensemble vote. */
class Niv99LearnedIlluminantInput {
    var available: Boolean = false
    var redResponseOverGreen: Float = 1f
    var blueResponseOverGreen: Float = 1f
    var confidence: Float = 0f
    var modelRevision: Long = 0L

    fun isValid(): Boolean =
        available && modelRevision > 0L &&
            redResponseOverGreen.isFinite() && redResponseOverGreen > 0f &&
            blueResponseOverGreen.isFinite() && blueResponseOverGreen > 0f &&
            confidence.isFinite() && confidence > 0f
}

/** Deterministic robust ensemble of classical, calibrated, HAL, prior, and optional learned votes. */
class Niv99IlluminantEstimator(maxTiles: Int) {
    private val candidates = Array(MAX_CANDIDATES) { Niv99IlluminantCandidate() }
    private val candidateLogRed = FloatArray(MAX_CANDIDATES)
    private val candidateLogBlue = FloatArray(MAX_CANDIDATES)
    private val candidateWeights = FloatArray(MAX_CANDIDATES)
    private val tileLogRed = FloatArray(maxTiles)
    private val tileLogBlue = FloatArray(maxTiles)
    private val robustScratch = FloatArray(maxTiles)
    private val robustScratch2 = FloatArray(maxTiles)
    private val vector3 = FloatArray(3)
    private val xy = FloatArray(2)
    private val uv = FloatArray(2)
    private val cctDuv = FloatArray(2)
    private var candidateCount = 0
    private var consensusLogRed = 0f
    private var consensusLogBlue = 0f
    private var consensusResidual = 1f

    fun estimate(
        context: Niv99FrameContext,
        statistics: Niv99TileStatistics,
        calibration: Niv99CalibrationSolution,
        learned: Niv99LearnedIlluminantInput?,
        output: Niv99IlluminantEstimate
    ): Niv99ValidationResult {
        val validation = context.validate()
        if (validation != Niv99ValidationResult.VALID) return validation
        if (!calibration.valid || statistics.count <= 0 || statistics.count > tileLogRed.size) {
            return Niv99ValidationResult.INVALID_CALIBRATION
        }
        candidateCount = 0
        val reliableCount = collectReliableTiles(statistics)
        if (reliableCount > 0) {
            addGrayWorld(statistics)
            addShadesOfGray(statistics)
            addGrayEdge(statistics)
            addRobustNeutral(statistics, reliableCount)
            addSensorCalibrated(statistics)
        }
        addHalSameFrame(context.whiteBalanceIntent)
        addBayesianPrior(context, statistics)
        addLearned(learned)
        if (candidateCount <= 0) return Niv99ValidationResult.INVALID_PLANE
        robustConsensus()
        output.identity.copyFrom(context.identity)
        output.redResponseOverGreen = Niv99Math.safeExp(consensusLogRed)
        output.blueResponseOverGreen = Niv99Math.safeExp(consensusLogBlue)
        output.greenGain = 1f
        output.redGain = (1f / max(output.redResponseOverGreen, MIN_RESPONSE)).coerceIn(MIN_GAIN, MAX_GAIN)
        output.blueGain = (1f / max(output.blueResponseOverGreen, MIN_RESPONSE)).coerceIn(MIN_GAIN, MAX_GAIN)
        estimateChromaticity(output, calibration)
        output.candidateCount = candidateCount
        output.acceptedCandidateCount = acceptedCandidateCount()
        output.consensusFraction = output.acceptedCandidateCount.toFloat() / candidateCount
        output.neutralTileFraction = if (statistics.count > 0) {
            reliableCount.toFloat() / statistics.count
        } else {
            0f
        }
        output.clippingRisk = statistics.frameClippingFraction
        val support = candidateSupport()
        val agreement = 1f - Niv99Math.smoothstep(0f, MAX_CONSENSUS_RESIDUAL, consensusResidual)
        output.confidence = Niv99Math.saturateFinite(
            support * 0.38f + output.consensusFraction * 0.34f + agreement * 0.28f
        ) * (1f - Niv99Math.smoothstep(0.01f, 0.12f, output.clippingRisk))
        output.illuminantClass = classify(output.cctKelvin, output.duv)
        return Niv99ValidationResult.VALID
    }

    private fun collectReliableTiles(statistics: Niv99TileStatistics): Int {
        var count = 0
        for (index in 0 until statistics.count) {
            val weight = statistics.reliability[index] *
                max(statistics.neutralConfidence[index], MIN_NEUTRAL_WEIGHT)
            val redRatio = statistics.redOverGreen[index]
            val blueRatio = statistics.blueOverGreen[index]
            if (weight <= MIN_TILE_WEIGHT || !redRatio.isFinite() || !blueRatio.isFinite() ||
                redRatio <= 0f || blueRatio <= 0f
            ) {
                continue
            }
            tileLogRed[count] = Niv99Math.safeLog(redRatio)
            tileLogBlue[count] = Niv99Math.safeLog(blueRatio)
            count += 1
        }
        return count
    }

    private fun addGrayWorld(statistics: Niv99TileStatistics) {
        var redSum = 0.0
        var greenSum = 0.0
        var blueSum = 0.0
        var weightSum = 0.0
        var used = 0
        for (index in 0 until statistics.count) {
            val weight = statistics.reliability[index]
            if (weight <= MIN_TILE_WEIGHT) continue
            redSum += statistics.meanRed[index] * weight
            greenSum += statistics.meanGreen[index] * weight
            blueSum += statistics.meanBlue[index] * weight
            weightSum += weight
            used += 1
        }
        if (weightSum <= 0.0 || greenSum <= 0.0) return
        addCandidate(
            Niv99IlluminantMethod.GRAY_WORLD,
            (redSum / greenSum).toFloat(),
            (blueSum / greenSum).toFloat(),
            confidence = Niv99Math.smoothstep(2f, 24f, used.toFloat()),
            supportFraction = used.toFloat() / max(statistics.count, 1)
        )
    }

    private fun addShadesOfGray(statistics: Niv99TileStatistics) {
        var redPower = 0.0
        var greenPower = 0.0
        var bluePower = 0.0
        var weightSum = 0.0
        var used = 0
        for (index in 0 until statistics.count) {
            val weight = statistics.reliability[index] *
                (1f - statistics.saturationFraction[index])
            if (weight <= MIN_TILE_WEIGHT) continue
            redPower += statistics.meanRed[index].toDouble().pow(SHADES_POWER) * weight
            greenPower += statistics.meanGreen[index].toDouble().pow(SHADES_POWER) * weight
            bluePower += statistics.meanBlue[index].toDouble().pow(SHADES_POWER) * weight
            weightSum += weight
            used += 1
        }
        if (weightSum <= 0.0 || greenPower <= 0.0) return
        val inversePower = 1.0 / SHADES_POWER
        val red = (redPower / weightSum).pow(inversePower)
        val green = (greenPower / weightSum).pow(inversePower)
        val blue = (bluePower / weightSum).pow(inversePower)
        addCandidate(
            Niv99IlluminantMethod.SHADES_OF_GRAY,
            (red / green).toFloat(),
            (blue / green).toFloat(),
            confidence = Niv99Math.smoothstep(3f, 30f, used.toFloat()) * 0.92f,
            supportFraction = used.toFloat() / max(statistics.count, 1)
        )
    }

    private fun addGrayEdge(statistics: Niv99TileStatistics) {
        var redGradient = 0.0
        var greenGradient = 0.0
        var blueGradient = 0.0
        var weightSum = 0.0
        var used = 0
        for (index in 0 until statistics.count) {
            val reliability = statistics.reliability[index]
            val totalGradient = statistics.gradientRed[index] +
                statistics.gradientGreen[index] + statistics.gradientBlue[index]
            val edgeEvidence = Niv99Math.smoothstep(MIN_EDGE, STRONG_EDGE, totalGradient)
            val weight = reliability * edgeEvidence
            if (weight <= MIN_TILE_WEIGHT) continue
            redGradient += statistics.gradientRed[index] * weight
            greenGradient += statistics.gradientGreen[index] * weight
            blueGradient += statistics.gradientBlue[index] * weight
            weightSum += weight
            used += 1
        }
        if (weightSum <= 0.0 || greenGradient <= 0.0) return
        addCandidate(
            Niv99IlluminantMethod.GRAY_EDGE,
            (redGradient / greenGradient).toFloat(),
            (blueGradient / greenGradient).toFloat(),
            confidence = Niv99Math.smoothstep(3f, 20f, used.toFloat()) * 0.88f,
            supportFraction = used.toFloat() / max(statistics.count, 1)
        )
    }

    private fun addRobustNeutral(statistics: Niv99TileStatistics, reliableCount: Int) {
        if (reliableCount <= 0) return
        for (index in 0 until reliableCount) {
            robustScratch[index] = tileLogRed[index]
            robustScratch2[index] = tileLogBlue[index]
        }
        val medianRed = Niv99Math.medianInPlace(robustScratch, reliableCount)
        val medianBlue = Niv99Math.medianInPlace(robustScratch2, reliableCount)
        addCandidate(
            Niv99IlluminantMethod.ROBUST_NEUTRAL,
            Niv99Math.safeExp(medianRed),
            Niv99Math.safeExp(medianBlue),
            confidence = Niv99Math.smoothstep(2f, 16f, reliableCount.toFloat()),
            supportFraction = reliableCount.toFloat() / max(statistics.count, 1)
        )
    }

    private fun addSensorCalibrated(statistics: Niv99TileStatistics) {
        var weightedRed = 0.0
        var weightedGreen = 0.0
        var weightedBlue = 0.0
        var weightSum = 0.0
        var used = 0
        for (index in 0 until statistics.count) {
            val weight = statistics.neutralConfidence[index] * statistics.signalToNoise[index]
            if (weight <= MIN_TILE_WEIGHT) continue
            weightedRed += statistics.meanRed[index] * weight
            weightedGreen += statistics.meanGreen[index] * weight
            weightedBlue += statistics.meanBlue[index] * weight
            weightSum += weight
            used += 1
        }
        if (weightSum <= 0.0 || weightedGreen <= 0.0) return
        addCandidate(
            Niv99IlluminantMethod.SENSOR_CALIBRATED,
            (weightedRed / weightedGreen).toFloat(),
            (weightedBlue / weightedGreen).toFloat(),
            confidence = Niv99Math.smoothstep(2f, 12f, used.toFloat()),
            supportFraction = used.toFloat() / max(statistics.count, 1)
        )
    }

    private fun addHalSameFrame(intent: Niv99WhiteBalanceIntent) {
        if (!intent.hasValidHalGains()) return
        val greenGain = (intent.halGreenEvenGain + intent.halGreenOddGain) * 0.5f
        addCandidate(
            Niv99IlluminantMethod.HAL_SAME_FRAME,
            greenGain / intent.halRedGain,
            greenGain / intent.halBlueGain,
            confidence = if (intent.halAwbConverged) 0.82f else 0.42f,
            supportFraction = 1f
        )
    }

    private fun addBayesianPrior(context: Niv99FrameContext, statistics: Niv99TileStatistics) {
        val lowLight = 1f - Niv99Math.smoothstep(0.02f, 0.18f, statistics.frameMeanSignal)
        val longExposure = Niv99Math.smoothstep(5_000_000f, 25_000_000f, context.exposureTimeNs.toFloat())
        val daylightPrior = (1f - lowLight) * (1f - longExposure * 0.4f)
        val tungstenPrior = lowLight * longExposure
        val cct = 3_000f * tungstenPrior + 5_500f * (1f - tungstenPrior) * (1f - daylightPrior) +
            6_500f * daylightPrior
        val normalized = ((cct - 2_850f) / (6_500f - 2_850f)).coerceIn(0f, 1f)
        // Broad prior in response space. Its deliberately low confidence prevents scene semantics
        // from overriding measured neutral evidence.
        val redResponse = Niv99Math.lerp(1.55f, 0.72f, normalized)
        val blueResponse = Niv99Math.lerp(0.55f, 1.35f, normalized)
        addCandidate(
            Niv99IlluminantMethod.BAYESIAN_PRIOR,
            redResponse,
            blueResponse,
            confidence = 0.18f + max(lowLight, daylightPrior) * 0.08f,
            supportFraction = 1f
        )
    }

    private fun addLearned(learned: Niv99LearnedIlluminantInput?) {
        if (learned?.isValid() != true) return
        addCandidate(
            Niv99IlluminantMethod.LEARNED_COMPACT,
            learned.redResponseOverGreen,
            learned.blueResponseOverGreen,
            confidence = min(learned.confidence, MAX_LEARNED_CONFIDENCE),
            supportFraction = 1f
        )
    }

    private fun addCandidate(
        method: Niv99IlluminantMethod,
        redResponse: Float,
        blueResponse: Float,
        confidence: Float,
        supportFraction: Float
    ) {
        if (candidateCount >= candidates.size || !redResponse.isFinite() || !blueResponse.isFinite() ||
            redResponse <= 0f || blueResponse <= 0f
        ) {
            return
        }
        candidates[candidateCount].set(
            method,
            redResponse,
            blueResponse,
            confidence,
            supportFraction
        )
        candidateCount += 1
    }

    /** Publishes fused log-red, log-blue, and weighted residual into reusable fields. */
    private fun robustConsensus() {
        for (index in 0 until candidateCount) {
            candidateLogRed[index] = Niv99Math.safeLog(candidates[index].redResponseOverGreen)
            candidateLogBlue[index] = Niv99Math.safeLog(candidates[index].blueResponseOverGreen)
        }
        var bestIndex = 0
        var bestSupport = -1f
        for (hypothesis in 0 until candidateCount) {
            val centerRed = candidateLogRed[hypothesis]
            val centerBlue = candidateLogBlue[hypothesis]
            var support = 0f
            for (index in 0 until candidateCount) {
                val red = candidateLogRed[index]
                val blue = candidateLogBlue[index]
                val distance = sqrt(Niv99Math.square(red - centerRed) + Niv99Math.square(blue - centerBlue))
                if (distance <= RANSAC_RADIUS) {
                    support += candidates[index].confidence * candidates[index].supportFraction
                }
            }
            if (support > bestSupport) {
                bestSupport = support
                bestIndex = hypothesis
            }
        }
        var centerRed = candidateLogRed[bestIndex]
        var centerBlue = candidateLogBlue[bestIndex]
        repeat(ROBUST_ITERATIONS) {
            var weightedRed = 0.0
            var weightedBlue = 0.0
            var weightSum = 0.0
            for (index in 0 until candidateCount) {
                val candidate = candidates[index]
                val red = candidateLogRed[index]
                val blue = candidateLogBlue[index]
                val distance = sqrt(Niv99Math.square(red - centerRed) + Niv99Math.square(blue - centerBlue))
                val consensusWeight = Niv99Math.tukeyWeight(distance, TUKEY_CUTOFF)
                val weight = candidate.confidence * candidate.supportFraction * consensusWeight
                candidate.accepted = distance <= RANSAC_RADIUS && weight > 0f
                candidate.residual = distance
                candidateWeights[index] = weight
                weightedRed += red * weight
                weightedBlue += blue * weight
                weightSum += weight
            }
            if (weightSum > 1e-9) {
                centerRed = (weightedRed / weightSum).toFloat()
                centerBlue = (weightedBlue / weightSum).toFloat()
            }
        }
        var residualSum = 0.0
        var residualWeight = 0.0
        for (index in 0 until candidateCount) {
            val weight = candidateWeights[index]
            residualSum += candidates[index].residual * weight
            residualWeight += weight
        }
        consensusLogRed = centerRed
        consensusLogBlue = centerBlue
        consensusResidual = if (residualWeight > 1e-9) {
            (residualSum / residualWeight).toFloat()
        } else {
            1f
        }
    }

    private fun estimateChromaticity(
        output: Niv99IlluminantEstimate,
        calibration: Niv99CalibrationSolution
    ) {
        Niv99Math.multiply3Vector(
            calibration.sensorToXyz,
            output.redResponseOverGreen,
            1f,
            output.blueResponseOverGreen,
            vector3
        )
        Niv99Math.xyzToXy(vector3[0], vector3[1], vector3[2], xy)
        Niv99Math.xyToUvPrime(xy[0], xy[1], uv)
        Niv99Math.estimateCctAndDuv(uv[0], uv[1], cctDuv)
        output.chromaticityX = xy[0]
        output.chromaticityY = xy[1]
        output.chromaticityU = uv[0]
        output.chromaticityV = uv[1]
        output.cctKelvin = cctDuv[0]
        output.duv = cctDuv[1]
    }

    private fun acceptedCandidateCount(): Int {
        var count = 0
        for (index in 0 until candidateCount) if (candidates[index].accepted) count += 1
        return count
    }

    private fun candidateSupport(): Float {
        var weighted = 0f
        var normalizer = 0f
        for (index in 0 until candidateCount) {
            val candidate = candidates[index]
            normalizer += candidate.confidence
            if (candidate.accepted) weighted += candidate.confidence * candidate.supportFraction
        }
        return if (normalizer > 0f) (weighted / normalizer).coerceIn(0f, 1f) else 0f
    }

    private fun classify(cct: Float, duv: Float): Niv99IlluminantClass = when {
        !cct.isFinite() || !duv.isFinite() -> Niv99IlluminantClass.UNKNOWN
        abs(duv) > 0.025f -> Niv99IlluminantClass.FLUORESCENT
        cct < 3_100f -> Niv99IlluminantClass.TUNGSTEN
        cct < 4_000f -> Niv99IlluminantClass.WARM_LED
        cct < 6_800f -> Niv99IlluminantClass.DAYLIGHT
        cct < 8_500f -> Niv99IlluminantClass.OVERCAST
        else -> Niv99IlluminantClass.SHADE
    }

    companion object {
        private const val MAX_CANDIDATES = 8
        private const val SHADES_POWER = 6.0
        private const val MIN_RESPONSE = 1e-5f
        private const val MIN_GAIN = 0.125f
        private const val MAX_GAIN = 8f
        private const val MIN_TILE_WEIGHT = 1e-5f
        private const val MIN_NEUTRAL_WEIGHT = 0.08f
        private const val MIN_EDGE = 0.004f
        private const val STRONG_EDGE = 0.08f
        private const val MAX_LEARNED_CONFIDENCE = 0.70f
        private const val RANSAC_RADIUS = 0.28f
        private const val TUKEY_CUTOFF = 0.42f
        private const val ROBUST_ITERATIONS = 4
        private const val MAX_CONSENSUS_RESIDUAL = 0.30f
    }
}
