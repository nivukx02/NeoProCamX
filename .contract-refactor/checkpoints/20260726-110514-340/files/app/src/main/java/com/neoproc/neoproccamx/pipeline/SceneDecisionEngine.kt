package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.PerceptualSignalMath
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

enum class SceneStillStrategy {
    SINGLE,
    FRAME_STACK,
    HDR_FUSION,
    RAW_SINGLE
}

class SceneDecisionEngine(
    private val defaultExposureNs: Long = 16_666_667L,
    private val strictHighEnd: Boolean = false
) {

    fun analyze(
        frame: RawYuvFrame?,
        histogram: IntArray,
        averageLuma: Float,
        previousLumaFrame: ByteArray?
    ): SceneAnalysis {
        // ── NEON SIMD: Single-pass combined analysis ─────────────────────
        // When native NEON is available, compute motion + histogram in one
        // pass over the Y plane, halving memory bandwidth vs two passes.
        val neonAvailable = NeonSceneCompute.available
        var motion = 0f
        var effectiveAverageLuma = averageLuma

        if (neonAvailable && frame != null && previousLumaFrame != null &&
            previousLumaFrame.size == frame.yBytes.size) {
            // Combined NEON pass: motion + histogram + averageLuma
            val resultsOut = FloatArray(2)
            NeonSceneCompute.combinedAnalysis(
                previousLumaFrame, frame.yBytes, histogram, resultsOut
            )
            motion = resultsOut[0]
            // Use NEON-computed luma if valid, otherwise keep caller's value
            if (resultsOut[1] > 0f) effectiveAverageLuma = resultsOut[1]
        } else {
            // Fallback: separate Kotlin passes
            motion = if (frame != null && previousLumaFrame != null &&
                previousLumaFrame.size == frame.yBytes.size) {
                computeMotionScore(previousLumaFrame, frame.yBytes)
            } else 0f
        }

        val edgeDensity = if (frame != null) {
            computeEdgeDensity(frame.yBytes, frame.width, frame.height)
        } else {
            0f
        }
        val sensorSignal = if (frame != null) {
            PerceptualSignalMath.analyzeLuma(frame.yBytes, frame.width, frame.height)
        } else {
            com.neoproc.neoproccamx.core.PerceptualSignalSample.ZERO
        }
        var totalHistogramSum = 0
        var shadowSum = 0
        var highlightSum = 0
        for (i in histogram.indices) {
            val v = histogram[i]
            totalHistogramSum += v
            if (i < 48) shadowSum += v
            if (i >= 224) highlightSum += v
        }
        val totalHistogram = totalHistogramSum.coerceAtLeast(1).toFloat()
        val shadowRatio = shadowSum.toFloat() / totalHistogram
        val highlightRatio = highlightSum.toFloat() / totalHistogram
        val p5 = percentile(histogram, 0.05f, totalHistogramSum)
        val p95 = percentile(histogram, 0.95f, totalHistogramSum)
        val dynamicRange = ((p95 - p5).coerceAtLeast(0)) / 255f
        val estimatedLux = estimateLux(effectiveAverageLuma, p95 / 255f, shadowRatio)

        return SceneAnalysis(
            averageLuma = effectiveAverageLuma,
            estimatedLux = estimatedLux,
            motionScore = motion,
            edgeDensity = edgeDensity,
            dynamicRangeScore = dynamicRange,
            shadowRatio = shadowRatio,
            highlightRatio = highlightRatio,
            timestamp = frame?.timestamp ?: 0L,
            normalizedNoiseFloor = sensorSignal.normalizedNoiseFloor,
            flatRegionStdDev = sensorSignal.flatRegionStdDev,
            realIsoNoiseFloor = sensorSignal.realIsoNoiseFloor,
            perceptualSharpness = sensorSignal.perceptualSharpness
        )
    }

    fun buildCaptureRecommendation(scene: SceneAnalysis): CaptureRecommendation {
        val lowLight = scene.isLowLight
        val brightLight = scene.isBrightLight
        val motion = scene.hasMotion
        val veryLowLight = scene.estimatedLux < 12f || scene.averageLuma < 0.12f
        val extremelyLowLight = scene.estimatedLux < 5f || scene.averageLuma < 0.07f
        val richTexture = scene.edgeDensity > 0.24f
        val sensorNoiseFactor = scene.normalizedNoiseFloor.coerceIn(0f, 1f)
        val perceptualSharpness = scene.perceptualSharpness.coerceIn(0f, 1f)
        val isoNoisePressure = ((scene.realIsoNoiseFloor - 100f) / 1500f).coerceIn(0f, 1f)
        val brightDaylight = scene.estimatedLux > 420f && scene.averageLuma > 0.66f
        val stableScene = !motion && scene.motionScore < 0.035f
        val ultraStableScene = !motion && scene.motionScore < 0.018f
        val highlightHeavy = scene.highlightRatio > 0.10f
        val deepShadows = scene.shadowRatio > 0.22f
        val extremeDynamicRange =
            scene.dynamicRangeScore > 0.82f ||
                (scene.highlightRatio > 0.14f && scene.shadowRatio > 0.24f)

        val isoCeiling = when {
            brightDaylight -> 50
            brightLight -> 64
            !lowLight && motion -> 160
            // HDR scenes in normal light: slightly higher ceiling for stacking headroom
            scene.highDynamicRange && stableScene && !lowLight -> 110
            scene.highDynamicRange && !lowLight -> 125
            !lowLight -> 100
            lowLight && motion -> 320
            extremelyLowLight -> 400
            veryLowLight -> 320
            lowLight -> 250
            else -> 200
        } - (sensorNoiseFactor * 38f).toInt().coerceIn(0, 40)

        val targetExposureNs = when {
            brightDaylight && motion -> 1_000_000_000L / 500L
            brightDaylight -> 1_000_000_000L / 320L
            brightLight && motion -> 1_000_000_000L / 320L
            brightLight -> 1_000_000_000L / 250L
            !lowLight && motion -> 1_000_000_000L / 250L
            !lowLight -> 1_000_000_000L / 160L
            motion && lowLight -> 1_000_000_000L / 120L
            motion -> 1_000_000_000L / 180L
            extremelyLowLight -> 1_000_000_000L / 15L
            veryLowLight -> 1_000_000_000L / 20L
            lowLight -> 1_000_000_000L / 28L
            else -> defaultExposureNs
        }

        val stackFrameCount = when {
            brightLight -> 1
            !lowLight && !scene.highDynamicRange -> 1
            scene.highDynamicRange && motion -> 5
            scene.highDynamicRange -> 7
            lowLight && motion -> 6
            extremelyLowLight && !richTexture -> 25
            extremelyLowLight -> 18
            veryLowLight && !richTexture -> 18
            veryLowLight -> 14
            lowLight && richTexture -> 10
            lowLight -> 12
            else -> 1
        }

        val hdrBracketEvs = when {
            !scene.highDynamicRange -> emptyList()
            motion -> listOf(-1, 0, 1)
            extremeDynamicRange && ultraStableScene -> listOf(-3, -2, -1, 0, 1, 2, 3)
            extremeDynamicRange -> listOf(-2, -1, 0, 1, 2, 3)
            else -> listOf(-2, -1, 0, 1, 2)
        }

        val hdrTargetFrameCount = when {
            !scene.highDynamicRange -> 0
            motion -> 3
            extremeDynamicRange && ultraStableScene -> 11
            extremeDynamicRange -> 9
            extremelyLowLight -> 9
            highlightHeavy || deepShadows -> 7
            stableScene -> 5
            else -> 3
        }

        val sharpeningBoost = when {
            motion -> 0.32f
            brightLight -> 0.18f
            lowLight -> 0.14f
            else -> 0.20f
        } + perceptualSharpness * 0.06f - sensorNoiseFactor * 0.02f

        val localToneMapStrength = when {
            extremeDynamicRange && ultraStableScene -> 0.94f
            scene.highDynamicRange && !motion -> 0.88f
            scene.highDynamicRange -> 0.70f
            lowLight -> 0.58f
            else -> 0.36f
        }

        val shadowDenoiseStrength = when {
            extremelyLowLight -> 0.93f
            veryLowLight -> 0.86f
            lowLight -> 0.72f
            else -> 0.34f
        } + sensorNoiseFactor * 0.16f + isoNoisePressure * 0.08f
        val spatialDenoiseFloor = when {
            extremelyLowLight -> 0.84f
            veryLowLight -> 0.78f
            lowLight -> 0.66f
            brightLight -> 0.54f
            else -> 0.42f
        } + sensorNoiseFactor * 0.14f + isoNoisePressure * 0.06f

        val detailPreservationStrength = when {
            extremeDynamicRange && ultraStableScene -> 0.95f
            richTexture && stableScene -> 0.92f
            richTexture -> 0.86f
            motion -> 0.76f
            else -> 0.79f
        } + perceptualSharpness * 0.05f - sensorNoiseFactor * 0.04f

        val skyProtectionStrength = when {
            extremeDynamicRange -> 0.94f
            scene.highDynamicRange && highlightHeavy -> 0.90f
            scene.highDynamicRange -> 0.76f
            brightDaylight -> 0.56f
            else -> 0.24f
        }

        val wbLockSuggested = stableScene && (scene.highDynamicRange || lowLight || brightLight)
        val tunedIsoCeiling = if (strictHighEnd && !motion) {
            // Strict high-end stills: favour lower ISO for cleaner noise floor. Floor the ceiling at 50
            // FIRST (matching the else-branch), because the sensor-noise subtraction on line ~127 can
            // drive isoCeiling below 50 (e.g. 50 - 24 = 26 in clean bright light); a raw
            // coerceIn(50, isoCeiling) then inverts (min 50 > max 26) and threw
            // IllegalArgumentException on the FramePipeline thread, crashing the whole app whenever the
            // analysis stream (monitoring/scopes) was active in bright light.
            val flooredCeiling = isoCeiling.coerceAtLeast(50)
            (flooredCeiling * 0.85f).toInt().coerceIn(50, flooredCeiling)
        } else {
            isoCeiling.coerceAtLeast(50)
        }
        val tunedTargetExposureNs = when {
            strictHighEnd && !motion && (lowLight || scene.highDynamicRange) ->
                (targetExposureNs * 1.18f).toLong()
            strictHighEnd && motion ->
                (targetExposureNs * 0.92f).toLong()
            else -> targetExposureNs
        }
        val tunedStackFrameCount = when {
            !strictHighEnd -> stackFrameCount
            motion -> stackFrameCount
            scene.highDynamicRange || lowLight -> (stackFrameCount + 2).coerceAtMost(15)
            else -> stackFrameCount
        }
        val tunedHdrBracketEvs = when {
            !strictHighEnd || hdrBracketEvs.isEmpty() || motion -> hdrBracketEvs
            else -> {
                val lower = (hdrBracketEvs.first() - 1).coerceAtLeast(-4)
                val upper = (hdrBracketEvs.last() + 1).coerceAtMost(4)
                (lower..upper).toList()
            }
        }
        val tunedHdrTargetFrameCount = when {
            !strictHighEnd -> hdrTargetFrameCount
            !scene.highDynamicRange || motion -> hdrTargetFrameCount
            else -> (hdrTargetFrameCount + 4).coerceAtMost(15)
        }
        val tunedLocalToneMapStrength = if (strictHighEnd && scene.highDynamicRange) {
            (localToneMapStrength + 0.06f).coerceAtMost(0.98f)
        } else {
            localToneMapStrength
        }
        val tunedShadowDenoiseStrength = if (strictHighEnd) {
            val strictDenoiseFloor = when {
                lowLight -> 0.9997f
                brightLight -> 0.9996f
                else -> 0.99965f
            }
            maxOf(
                shadowDenoiseStrength + if (lowLight) 0.08f else 0.10f,
                strictDenoiseFloor
            ).coerceAtMost(0.9998f)
        } else {
            shadowDenoiseStrength
        }
        val tunedSpatialDenoiseFloor = if (strictHighEnd) {
            maxOf(
                spatialDenoiseFloor + if (lowLight) 0.14f else 0.16f,
                if (lowLight) 0.992f else 0.94f
            ).coerceAtMost(0.995f)
        } else {
            spatialDenoiseFloor
        }
        val tunedDetailPreservationStrength = if (strictHighEnd) {
            1.0f
        } else {
            detailPreservationStrength
        }
        val tunedSharpeningBoost = if (strictHighEnd) {
            val strictSharpenFloor = if (lowLight) 0.14f else 0.18f
            maxOf(
                sharpeningBoost + if (motion) 0.04f else 0.03f,
                strictSharpenFloor
            ).coerceAtMost(0.40f)
        } else {
            sharpeningBoost
        }
        val tunedTemporalNoiseReduction = strictHighEnd || !brightLight
        val tunedSkyProtectionStrength = if (strictHighEnd && scene.highDynamicRange) {
            (skyProtectionStrength + 0.04f).coerceAtMost(0.98f)
        } else {
            skyProtectionStrength
        }
        val tunedWbLockSuggested = wbLockSuggested || (strictHighEnd && stableScene && scene.highDynamicRange)

        return CaptureRecommendation(
            scene = scene,
            isoCeiling = tunedIsoCeiling,
            targetExposureNs = tunedTargetExposureNs,
            stackFrameCount = tunedStackFrameCount,
            temporalNoiseReduction = tunedTemporalNoiseReduction,
            hdrBracketEvs = tunedHdrBracketEvs,
            sharpeningBoost = tunedSharpeningBoost,
            hdrTargetFrameCount = tunedHdrTargetFrameCount,
            localToneMapStrength = tunedLocalToneMapStrength,
            shadowDenoiseStrength = tunedShadowDenoiseStrength,
            spatialDenoiseFloor = tunedSpatialDenoiseFloor,
            detailPreservationStrength = tunedDetailPreservationStrength,
            skyProtectionStrength = tunedSkyProtectionStrength,
            wbLockSuggested = tunedWbLockSuggested
        )
    }

    fun chooseStillStrategy(
        rawRequested: Boolean,
        rawSupported: Boolean,
        recommendation: CaptureRecommendation?
    ): SceneStillStrategy {
        if (rawRequested && rawSupported) {
            return SceneStillStrategy.RAW_SINGLE
        }
        val resolved = recommendation ?: return SceneStillStrategy.SINGLE
        return when {
            resolved.scene.hasMotion && resolved.stackFrameCount > 1 -> SceneStillStrategy.FRAME_STACK
            resolved.shouldUseHdrFusion && !resolved.scene.hasMotion -> SceneStillStrategy.HDR_FUSION
            resolved.shouldUseFrameStacking -> SceneStillStrategy.FRAME_STACK
            else -> SceneStillStrategy.SINGLE
        }
    }

    private fun computeMotionScore(previous: ByteArray, current: ByteArray): Float {
        if (previous.isEmpty() || current.isEmpty()) return 0f
        // NEON SIMD fast path: 16 pixels per cycle
        val neonResult = NeonSceneCompute.motionScore(previous, current)
        if (neonResult >= 0f) return neonResult
        // Kotlin fallback for devices without NEON
        val baseStep = max(1, previous.size / 40_000)
        val jitterMask = baseStep - 1
        var diffSum = 0.0
        var samples = 0
        var index = 0
        val seed = (System.nanoTime() and 0xFFFF).toInt()
        while (index < previous.size && index < current.size) {
            diffSum += abs((previous[index].toInt() and 0xFF) - (current[index].toInt() and 0xFF)).toDouble()
            samples++
            val jitter = ((seed * 1103515245 + 12345 + samples * 1013904223) ushr 16) and jitterMask
            index += baseStep.coerceAtLeast(1) + jitter
        }
        if (samples == 0) return 0f
        return ((diffSum / samples.toDouble()) / 255.0).toFloat().coerceIn(0f, 1f)
    }

    private fun computeEdgeDensity(yBytes: ByteArray, width: Int, height: Int): Float {
        if (width < 3 || height < 3 || yBytes.size < width * height) return 0f
        // NEON SIMD fast path: 16 pixels per row iteration
        val neonResult = NeonSceneCompute.edgeDensity(yBytes, width, height)
        if (neonResult >= 0f) return neonResult
        // Kotlin fallback
        val stepX = max(1, width / 96)
        val stepY = max(1, height / 96)
        var strongEdges = 0
        var total = 0
        for (y in 1 until height - 1 step stepY) {
            val row = y * width
            for (x in 1 until width - 1 step stepX) {
                val idx = row + x
                val gx = ((yBytes[idx + 1].toInt() and 0xFF) - (yBytes[idx - 1].toInt() and 0xFF))
                val gy = ((yBytes[idx + width].toInt() and 0xFF) - (yBytes[idx - width].toInt() and 0xFF))
                val magnitude = abs(gx) + abs(gy)
                if (magnitude > 44) strongEdges++
                total++
            }
        }
        if (total == 0) return 0f
        return strongEdges.toFloat() / total.toFloat()
    }

    private fun percentile(histogram: IntArray, fraction: Float, precomputedTotal: Int = -1): Int {
        val total = if (precomputedTotal > 0) precomputedTotal else histogram.sum().coerceAtLeast(1)
        val target = (total * fraction.coerceIn(0f, 1f)).toInt()
        var running = 0
        for (i in histogram.indices) {
            running += histogram[i]
            if (running >= target) return i
        }
        return histogram.lastIndex
    }

    private fun estimateLux(averageLuma: Float, highlightLevel: Float, shadowRatio: Float): Float {
        val perceptualLuma = averageLuma.coerceIn(0f, 1f).pow(2.2f)
        val contrastPenalty = 1f - shadowRatio.coerceIn(0f, 0.6f)
        return ((perceptualLuma * 1400f) + (highlightLevel.coerceIn(0f, 1f) * 450f)) * contrastPenalty
    }
}
