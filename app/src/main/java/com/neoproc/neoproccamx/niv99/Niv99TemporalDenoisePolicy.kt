package com.neoproc.neoproccamx.niv99

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Niv99TemporalFusionPlan(maxHistoryFrames: Int) {
    val identity: Niv99FrameIdentity = Niv99FrameIdentity()
    val historyFrameNumber: LongArray = LongArray(maxHistoryFrames) { -1L }
    val historyTimestampNs: LongArray = LongArray(maxHistoryFrames) { -1L }
    val historyWeight: FloatArray = FloatArray(maxHistoryFrames)
    var historyCount: Int = 0
        internal set
    var totalHistoryWeight: Float = 0f
        internal set
    var currentFrameWeight: Float = 1f
        internal set
    var temporalStrength: Float = 0f
        internal set
    var chromaFlickerSuppression: Float = 0f
        internal set
    var ghostRejection: Float = 1f
        internal set
    var resetReason: Niv99ResetReason = Niv99ResetReason.NONE
        internal set
    var deterministicOffline: Boolean = false
        internal set

    fun clear(identity: Niv99FrameIdentity, reason: Niv99ResetReason) {
        this.identity.copyFrom(identity)
        historyFrameNumber.fill(-1L)
        historyTimestampNs.fill(-1L)
        historyWeight.fill(0f)
        historyCount = 0
        totalHistoryWeight = 0f
        currentFrameWeight = 1f
        temporalStrength = 0f
        chromaFlickerSuppression = 0f
        ghostRejection = 1f
        resetReason = reason
        deterministicOffline = false
    }
}

private class Niv99TemporalHistoryEntry {
    val identity = Niv99FrameIdentity()
    var meanSignal: Float = 0f
    var exposureValue: Double = 0.0
    var redGain: Float = 1f
    var blueGain: Float = 1f
    var meanVariance: Float = 0f
    var motionConfidence: Float = 0f
    var colorProfileId: Long = 0L
    var valid: Boolean = false

    fun set(
        context: Niv99FrameContext,
        whiteBalance: Niv99WhiteBalanceSolution,
        noise: Niv99NoiseModelSnapshot,
        motionConfidence: Float
    ) {
        identity.copyFrom(context.identity)
        meanSignal = context.meanLinearSignal
        exposureValue = context.exposureTimeNs.toDouble() * context.iso
        redGain = whiteBalance.redGain
        blueGain = whiteBalance.blueGain
        meanVariance = noise.meanVariance(meanSignal, meanSignal, meanSignal)
        this.motionConfidence = Niv99Math.saturateFinite(motionConfidence)
        colorProfileId = context.colorProfileId
        valid = true
    }

    fun clear() {
        valid = false
        identity.set("", -1L, -1L, -1L)
        meanSignal = 0f
        exposureValue = 0.0
        redGain = 1f
        blueGain = 1f
        meanVariance = 0f
        motionConfidence = 0f
        colorProfileId = 0L
    }
}

/** Bounded temporal history metadata. GPU images are owned by the rendering backend, not this policy. */
class Niv99TemporalDenoisePolicy(
    historyCapacity: Int = MAX_HISTORY_CAPACITY
) {
    private val capacity = historyCapacity.coerceIn(1, MAX_HISTORY_CAPACITY)
    private val history = Array(capacity) { Niv99TemporalHistoryEntry() }
    private val timeline = Niv99TimelineSignature()
    private var writeIndex = 0
    private var validHistory = 0

    fun resolve(
        context: Niv99FrameContext,
        whiteBalance: Niv99WhiteBalanceSolution,
        noise: Niv99NoiseModelSnapshot,
        motion: Niv99MotionSummary,
        residualConfidence: Float,
        output: Niv99TemporalFusionPlan
    ): Niv99ValidationResult {
        val validation = context.validate()
        if (validation != Niv99ValidationResult.VALID) return validation
        var resetReason = timeline.resetReason(context)
        if (context.sceneCutHint) resetReason = Niv99ResetReason.SCENE_CUT
        if (context.flashFired != timeline.flashFired && timeline.initialized) {
            resetReason = Niv99ResetReason.FLASH_TRANSITION
        }
        if (resetReason != Niv99ResetReason.NONE) clearHistory()
        output.clear(context.identity, resetReason)
        output.deterministicOffline = context.operatingMode.isOfflineConversion()
        val requestedHistory = historyLimit(context)
        val motionConfidence = Niv99Math.saturateFinite(motion.globalConfidence)
        val robustResidual = Niv99Math.saturateFinite(residualConfidence)
        val currentExposure = context.exposureTimeNs.toDouble() * context.iso
        val currentVariance = noise.meanVariance(
            context.meanLinearSignal,
            context.meanLinearSignal,
            context.meanLinearSignal
        )
        var published = 0
        var totalWeight = 0f
        var strongestGhostRisk = 0f
        for (age in 1..min(validHistory, requestedHistory)) {
            val index = floorMod(writeIndex - age, capacity)
            val entry = history[index]
            if (!entry.valid || !entry.identity.sameTimeline(context.identity) ||
                entry.identity.frameNumber >= context.identity.frameNumber ||
                entry.identity.sensorTimestampNs >= context.identity.sensorTimestampNs ||
                entry.colorProfileId != context.colorProfileId
            ) {
                continue
            }
            val exposureRatio = if (entry.exposureValue > 0.0 && currentExposure > 0.0) {
                (currentExposure / entry.exposureValue).coerceIn(0.01, 100.0).toFloat()
            } else {
                1f
            }
            val exposureCompatibility = exp(-abs(Niv99Math.safeLog(exposureRatio)) * EXPOSURE_REJECTION)
            val chromaDistance = Niv99Math.logChromaDistance(
                whiteBalance.redGain,
                whiteBalance.blueGain,
                entry.redGain,
                entry.blueGain
            )
            val chromaCompatibility = exp(-chromaDistance * CHROMA_REJECTION)
            val ageDecay = exp(-age * AGE_DECAY)
            val varianceWeight = 1f / max(currentVariance + entry.meanVariance, MIN_VARIANCE)
            val normalizedVarianceWeight = varianceWeight / (varianceWeight + VARIANCE_NORMALIZER)
            val entryMotion = min(motionConfidence, entry.motionConfidence)
            val ghostRisk = max(
                1f - entryMotion,
                1f - robustResidual
            )
            val ghostGate = 1f - Niv99Math.smoothstep(GHOST_RISK_LOW, GHOST_RISK_HIGH, ghostRisk)
            val weight = ageDecay * exposureCompatibility * chromaCompatibility *
                normalizedVarianceWeight * entryMotion * ghostGate
            if (weight <= MIN_HISTORY_WEIGHT) continue
            output.historyFrameNumber[published] = entry.identity.frameNumber
            output.historyTimestampNs[published] = entry.identity.sensorTimestampNs
            output.historyWeight[published] = weight
            published += 1
            totalWeight += weight
            strongestGhostRisk = max(strongestGhostRisk, ghostRisk)
            if (published >= output.historyWeight.size) break
        }
        output.historyCount = published
        output.totalHistoryWeight = totalWeight
        output.currentFrameWeight = 1f
        val staticScene = 1f - Niv99Math.smoothstep(0.30f, 3.5f, motion.p95Magnitude)
        val noisePressure = Niv99Math.smoothstep(0.002f, 0.045f, sqrt(max(currentVariance, 0f)))
        val profileScale = temporalProfileScale(context.detailProfile)
        val offlineScale = if (output.deterministicOffline) OFFLINE_TEMPORAL_SCALE else 1f
        output.temporalStrength = (
            totalWeight / (1f + totalWeight) * staticScene * noisePressure * profileScale * offlineScale
            ).coerceIn(0f, temporalCeiling(context.detailProfile, output.deterministicOffline))
        output.chromaFlickerSuppression = (
            output.temporalStrength * CHROMA_FLICKER_SCALE * chromaStability(whiteBalance)
            ).coerceIn(0f, MAX_CHROMA_FLICKER_SUPPRESSION)
        output.ghostRejection = max(
            strongestGhostRisk,
            1f - motion.meanConsistency
        ).coerceIn(0f, 1f)
        appendHistory(context, whiteBalance, noise, motionConfidence)
        timeline.update(context)
        return Niv99ValidationResult.VALID
    }

    fun reset() {
        clearHistory()
        timeline.initialized = false
    }

    private fun appendHistory(
        context: Niv99FrameContext,
        whiteBalance: Niv99WhiteBalanceSolution,
        noise: Niv99NoiseModelSnapshot,
        motionConfidence: Float
    ) {
        history[writeIndex].set(context, whiteBalance, noise, motionConfidence)
        writeIndex = (writeIndex + 1) % capacity
        validHistory = min(validHistory + 1, capacity)
    }

    private fun clearHistory() {
        for (entry in history) entry.clear()
        writeIndex = 0
        validHistory = 0
    }

    private fun historyLimit(context: Niv99FrameContext): Int {
        val base = when (context.detailProfile) {
            Niv99DetailProfile.FOCUSED -> 4
            Niv99DetailProfile.NATURAL -> 3
            Niv99DetailProfile.EXTREME -> 2
        }
        val modeLimit = if (context.operatingMode.isOfflineConversion()) capacity else base
        val thermalLimit = when {
            context.thermalPressure >= 0.85f -> 1
            context.thermalPressure >= 0.65f -> max(2, modeLimit - 2)
            else -> modeLimit
        }
        val memoryLimit = when {
            context.memoryPressure >= 0.85f -> 1
            context.memoryPressure >= 0.65f -> max(2, thermalLimit - 1)
            else -> thermalLimit
        }
        return memoryLimit.coerceIn(1, capacity)
    }

    private fun temporalProfileScale(profile: Niv99DetailProfile): Float = when (profile) {
        Niv99DetailProfile.FOCUSED -> 0.88f
        Niv99DetailProfile.NATURAL -> 0.72f
        Niv99DetailProfile.EXTREME -> 0.58f
    }

    private fun temporalCeiling(profile: Niv99DetailProfile, offline: Boolean): Float {
        val realtime = when (profile) {
            Niv99DetailProfile.FOCUSED -> 0.72f
            Niv99DetailProfile.NATURAL -> 0.62f
            Niv99DetailProfile.EXTREME -> 0.50f
        }
        return if (offline) min(realtime + 0.12f, 0.82f) else realtime
    }

    private fun chromaStability(whiteBalance: Niv99WhiteBalanceSolution): Float =
        whiteBalance.confidence * (1f - whiteBalance.mixedLightConfidence * 0.35f)

    private fun floorMod(value: Int, modulus: Int): Int {
        val remainder = value % modulus
        return if (remainder >= 0) remainder else remainder + modulus
    }

    private fun Niv99OperatingMode.isOfflineConversion(): Boolean = when (this) {
        Niv99OperatingMode.CONVERSION_RAW_TO_LOG,
        Niv99OperatingMode.CONVERSION_RAW_TO_RAW_DEVELOPED,
        Niv99OperatingMode.CONVERSION_RAW_TO_XDR10,
        Niv99OperatingMode.CONVERSION_RAW_TO_NIVUKX -> true
        else -> false
    }

    companion object {
        const val MAX_HISTORY_CAPACITY = 8
        private const val MIN_VARIANCE = 1e-12f
        private const val VARIANCE_NORMALIZER = 200f
        private const val EXPOSURE_REJECTION = 1.7f
        private const val CHROMA_REJECTION = 5.5f
        private const val AGE_DECAY = 0.26f
        private const val GHOST_RISK_LOW = 0.18f
        private const val GHOST_RISK_HIGH = 0.62f
        private const val MIN_HISTORY_WEIGHT = 0.005f
        private const val OFFLINE_TEMPORAL_SCALE = 1.18f
        private const val CHROMA_FLICKER_SCALE = 1.25f
        private const val MAX_CHROMA_FLICKER_SUPPRESSION = 0.78f
    }
}

