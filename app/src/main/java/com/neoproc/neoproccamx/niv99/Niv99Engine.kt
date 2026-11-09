package com.neoproc.neoproccamx.niv99

import java.util.concurrent.atomic.AtomicLong

class Niv99FrameInput(maxTiles: Int) {
    val context = Niv99FrameContext()
    val plane = Niv99LinearPlane()
    var calibration: Niv99SensorCalibration? = null
    var measuredNoiseProfile: Niv99MeasuredNoiseProfile? = null
    var semantics: Niv99SemanticTileMap? = null
    var motionField: Niv99MotionField? = null
    var gyroMotion: Niv99GyroMotion? = null
    var learnedIlluminant: Niv99LearnedIlluminantInput? = null
    var residualConfidence: Float = 1f
    val maximumTiles: Int = maxTiles
}

/**
 * CPU reference orchestrator. It reads sensor data, publishes policy state, and never writes RAW
 * samples. All frame workspaces are allocated at construction and copied into caller-owned output.
 */
class Niv99Engine(
    maximumTiles: Int = DEFAULT_MAXIMUM_TILES,
    historyCapacity: Int = Niv99TemporalDenoisePolicy.MAX_HISTORY_CAPACITY
) {
    private val tileStatistics = Niv99TileStatistics(maximumTiles)
    private val rawStatistics = Niv99RawStatistics()
    private val noiseModel = Niv99NoiseModel()
    private val noiseSnapshot = Niv99NoiseModelSnapshot()
    private val provisionalCalibration = Niv99CalibrationSolution()
    private val calibrationInterpolator = Niv99DualIlluminantInterpolator()
    private val illuminantEstimator = Niv99IlluminantEstimator(maximumTiles)
    private val illuminantEstimate = Niv99IlluminantEstimate()
    private val mixedLightEstimator = Niv99MixedLightEstimator(maximumTiles)
    val localIlluminantField = Niv99LocalIlluminantField(maximumTiles)
    private val whiteBalanceController = Niv99WhiteBalanceController()
    private val whiteBalance = Niv99WhiteBalanceSolution()
    private val detailProtection = Niv99DetailProtection()
    private val detailMap = Niv99DetailMap(maximumTiles)
    private val skinProtection = Niv99SkinProtection()
    private val skinMap = Niv99SkinMap(maximumTiles)
    private val spatialPolicy = Niv99SpatialDenoisePolicy()
    val spatialPlan = Niv99SpatialPlan(maximumTiles)
    private val motionConfidence = Niv99MotionConfidence(maximumTiles)
    private val motionSummary = Niv99MotionSummary()
    private val temporalPolicy = Niv99TemporalDenoisePolicy(historyCapacity)
    val temporalPlan = Niv99TemporalFusionPlan(historyCapacity)
    private val workingPacket = Niv99StatePacket()
    private val publisher = Niv99AtomicStatePublisher()
    private val provenanceGate = Niv99FrameProvenanceGate()
    private val processSequence = AtomicLong(0L)

    @Synchronized
    fun process(
        input: Niv99FrameInput,
        capability: Niv99CapabilityDecision,
        output: Niv99StatePacket
    ): Niv99ValidationResult {
        val startNs = System.nanoTime()
        val context = input.context
        workingPacket.identity.copyFrom(context.identity)
        workingPacket.whiteBalance.identity.copyFrom(context.identity)
        workingPacket.whiteBalance.sourceIdentity.copyFrom(context.identity)
        workingPacket.denoise.identity.copyFrom(context.identity)
        workingPacket.accepted = false
        workingPacket.capabilityStatus = capability.status
        val contextValidation = context.validate()
        if (contextValidation != Niv99ValidationResult.VALID) {
            return reject(contextValidation, startNs, output)
        }
        val provenanceValidation = provenanceGate.validate(context.identity)
        if (provenanceValidation != Niv99ValidationResult.VALID) {
            return reject(provenanceValidation, startNs, output)
        }
        if (!input.plane.isValid()) return reject(Niv99ValidationResult.INVALID_PLANE, startNs, output)
        val calibration = input.calibration
            ?: return reject(Niv99ValidationResult.INVALID_CALIBRATION, startNs, output)
        if (calibration.cameraId != context.identity.cameraId ||
            calibration.validate() != Niv99ValidationResult.VALID
        ) {
            return reject(Niv99ValidationResult.INVALID_CALIBRATION, startNs, output)
        }
        val provisionalCct = provisionalCct(context)
        if (!calibrationInterpolator.resolve(calibration, provisionalCct, provisionalCalibration)) {
            return reject(Niv99ValidationResult.INVALID_CALIBRATION, startNs, output)
        }
        var status = noiseModel.resolve(context, calibration, input.measuredNoiseProfile, noiseSnapshot)
        if (status != Niv99ValidationResult.VALID) return reject(status, startNs, output)
        status = rawStatistics.compute(input.plane, calibration, noiseSnapshot, tileStatistics)
        if (status != Niv99ValidationResult.VALID) return reject(status, startNs, output)
        context.meanLinearSignal = tileStatistics.frameMeanSignal
        context.clippingFraction = tileStatistics.frameClippingFraction
        status = illuminantEstimator.estimate(
            context,
            tileStatistics,
            provisionalCalibration,
            input.learnedIlluminant,
            illuminantEstimate
        )
        val intent = context.whiteBalanceIntent
        val userAuthorityActive = intent.hasManualAuthority() ||
            intent.awbLockRequested || intent.awbLockActive
        if (status != Niv99ValidationResult.VALID && !userAuthorityActive) {
            return reject(status, startNs, output)
        }
        if (status != Niv99ValidationResult.VALID) initializeUserAuthorityEstimate(context)
        status = mixedLightEstimator.estimate(tileStatistics, illuminantEstimate, localIlluminantField)
        if (status != Niv99ValidationResult.VALID) return reject(status, startNs, output)
        status = whiteBalanceController.resolve(
            context,
            calibration,
            illuminantEstimate,
            localIlluminantField,
            whiteBalance
        )
        if (status != Niv99ValidationResult.VALID) return reject(status, startNs, output)
        status = skinProtection.resolve(tileStatistics, input.semantics, whiteBalance, skinMap)
        if (status != Niv99ValidationResult.VALID) return reject(status, startNs, output)
        status = detailProtection.classify(
            tileStatistics,
            input.semantics,
            context.detailProfile,
            detailMap
        )
        if (status != Niv99ValidationResult.VALID) return reject(status, startNs, output)
        resolveMotion(input)
        status = spatialPolicy.resolve(
            context,
            tileStatistics,
            noiseSnapshot,
            detailMap,
            skinMap,
            spatialPlan
        )
        if (status != Niv99ValidationResult.VALID) return reject(status, startNs, output)
        status = temporalPolicy.resolve(
            context,
            whiteBalance,
            noiseSnapshot,
            motionSummary,
            input.residualConfidence,
            temporalPlan
        )
        if (status != Niv99ValidationResult.VALID) return reject(status, startNs, output)
        publishDenoisePlan(context)
        workingPacket.whiteBalance.copyFrom(whiteBalance)
        workingPacket.accepted = true
        workingPacket.rejectionReason = Niv99ValidationResult.VALID
        workingPacket.processingTimeNs = System.nanoTime() - startNs
        check(workingPacket.hasCoherentIdentity()) { "NIV99 attempted incoherent state publication" }
        check(provenanceGate.commit(context.identity)) { "NIV99 provenance changed during serialized processing" }
        publisher.publish(workingPacket)
        output.copyFrom(workingPacket)
        processSequence.incrementAndGet()
        return Niv99ValidationResult.VALID
    }

    fun copyLatest(output: Niv99StatePacket): Boolean = publisher.copyLatest(output)

    fun reset(reason: Niv99ResetReason = Niv99ResetReason.EXPLICIT_RESET) {
        synchronized(this) {
            whiteBalanceController.reset(reason)
            temporalPolicy.reset()
            provenanceGate.reset()
        }
    }

    fun processedFrameCount(): Long = processSequence.get()

    private fun resolveMotion(input: Niv99FrameInput) {
        val field = input.motionField
        if (field != null && field.count > 0) {
            motionConfidence.evaluate(
                field,
                input.gyroMotion,
                input.context.rollingShutterSkewNs,
                input.context.frameDurationNs,
                motionSummary
            )
        } else {
            val motion = Niv99Math.saturateFinite(input.context.motionMagnitude)
            motionSummary.meanMagnitude = motion * 8f
            motionSummary.p95Magnitude = motion * 12f
            motionSummary.meanConsistency = 1f - motion
            motionSummary.occludedFraction = motion * 0.45f
            motionSummary.admittedFraction = 1f - motion
            motionSummary.globalConfidence = (1f - motion * 0.75f) *
                Niv99Math.saturateFinite(input.context.opticalFlowConfidence)
        }
    }

    private fun publishDenoisePlan(context: Niv99FrameContext) {
        val output = workingPacket.denoise
        output.identity.copyFrom(context.identity)
        output.rawSpatialStrength = spatialPlan.meanRawStrength
        output.lumaSpatialStrength = spatialPlan.meanLumaStrength
        output.chromaSpatialStrength = spatialPlan.meanChromaStrength
        output.temporalStrength = temporalPlan.temporalStrength
        output.temporalHistoryFrames = temporalPlan.historyCount
        output.motionConfidence = motionSummary.globalConfidence
        output.occlusionRejection = temporalPlan.ghostRejection
        output.textureProtection = mean(spatialPlan.textureProtection, spatialPlan.count)
        output.edgeProtection = mean(spatialPlan.edgeProtection, spatialPlan.count)
        output.skinProtection = mean(spatialPlan.skinProtection, spatialPlan.count)
        output.reconstructionSharpening = spatialPlan.meanSharpening
        output.sharpeningCeiling = maximum(spatialPlan.sharpening, spatialPlan.count)
        output.resetReason = temporalPlan.resetReason
        output.realtimeDeadlineNs = 1_000_000_000L / context.targetFps.coerceAtLeast(1)
    }

    private fun initializeUserAuthorityEstimate(context: Niv99FrameContext) {
        val intent = context.whiteBalanceIntent
        val manualGainsValid = intent.hasManualAuthority() && intent.hasValidManualGains()
        val lockedHalGainsValid = !intent.hasManualAuthority() &&
            (intent.awbLockRequested || intent.awbLockActive) && intent.hasValidHalGains()
        val redGain = when {
            manualGainsValid -> intent.manualRedGain
            lockedHalGainsValid -> intent.halRedGain
            else -> 1f
        }
        val greenGain = when {
            manualGainsValid -> intent.manualGreenGain
            lockedHalGainsValid -> (intent.halGreenEvenGain + intent.halGreenOddGain) * 0.5f
            else -> 1f
        }
        val blueGain = when {
            manualGainsValid -> intent.manualBlueGain
            lockedHalGainsValid -> intent.halBlueGain
            else -> 1f
        }
        illuminantEstimate.identity.copyFrom(context.identity)
        illuminantEstimate.redGain = redGain
        illuminantEstimate.greenGain = greenGain
        illuminantEstimate.blueGain = blueGain
        illuminantEstimate.redResponseOverGreen = greenGain / redGain
        illuminantEstimate.blueResponseOverGreen = greenGain / blueGain
        illuminantEstimate.chromaticityX = 0.3127f
        illuminantEstimate.chromaticityY = 0.3290f
        illuminantEstimate.chromaticityU = 0.19783f
        illuminantEstimate.chromaticityV = 0.46832f
        illuminantEstimate.cctKelvin = provisionalCct(context)
        illuminantEstimate.duv = if (intent.hasManualAuthority()) {
            intent.manualTint / 150f * 0.035f
        } else {
            0f
        }
        illuminantEstimate.confidence = when {
            manualGainsValid -> 1f
            lockedHalGainsValid && intent.halAwbConverged -> 0.82f
            lockedHalGainsValid -> 0.35f
            else -> 0f
        }
        illuminantEstimate.consensusFraction = 0f
        illuminantEstimate.neutralTileFraction = 0f
        illuminantEstimate.clippingRisk = context.clippingFraction
        illuminantEstimate.illuminantClass = Niv99IlluminantClass.UNKNOWN
        illuminantEstimate.candidateCount = 0
        illuminantEstimate.acceptedCandidateCount = 0
    }

    private fun provisionalCct(context: Niv99FrameContext): Float = when {
        context.whiteBalanceIntent.manualKelvin > 0 -> context.whiteBalanceIntent.manualKelvin.toFloat()
        context.whiteBalanceIntent.hasValidHalGains() -> {
            val red = context.whiteBalanceIntent.halGreenEvenGain /
                context.whiteBalanceIntent.halRedGain
            val blue = context.whiteBalanceIntent.halGreenEvenGain /
                context.whiteBalanceIntent.halBlueGain
            (5_500f + (blue - red) * 1_500f).coerceIn(2_000f, 12_000f)
        }
        else -> 5_000f
    }

    private fun reject(
        reason: Niv99ValidationResult,
        startNs: Long,
        output: Niv99StatePacket
    ): Niv99ValidationResult {
        workingPacket.accepted = false
        workingPacket.rejectionReason = reason
        workingPacket.processingTimeNs = System.nanoTime() - startNs
        output.copyFrom(workingPacket)
        return reason
    }

    private fun mean(values: FloatArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (index in 0 until count) sum += values[index]
        return (sum / count).toFloat()
    }

    private fun maximum(values: FloatArray, count: Int): Float {
        var maximum = 0f
        for (index in 0 until count) maximum = kotlin.math.max(maximum, values[index])
        return maximum
    }

    companion object {
        const val DEFAULT_MAXIMUM_TILES = 4_096
    }
}

/** Triple-buffer seqlock publication with caller-provided read destination. */
class Niv99AtomicStatePublisher {
    private val slots = Array(SLOT_COUNT) { Niv99StatePacket() }
    private val publishedSequence = AtomicLong(0L)

    fun publish(packet: Niv99StatePacket) {
        val nextSequence = publishedSequence.get() + 1L
        val slot = slots[(nextSequence % SLOT_COUNT).toInt()]
        slot.copyFrom(packet)
        publishedSequence.set(nextSequence)
    }

    fun copyLatest(output: Niv99StatePacket): Boolean {
        repeat(MAX_READ_RETRIES) {
            val before = publishedSequence.get()
            if (before <= 0L) return false
            output.copyFrom(slots[(before % SLOT_COUNT).toInt()])
            val after = publishedSequence.get()
            if (before == after && output.hasCoherentIdentity()) return true
        }
        return false
    }

    companion object {
        private const val SLOT_COUNT = 3
        private const val MAX_READ_RETRIES = 4
    }
}
