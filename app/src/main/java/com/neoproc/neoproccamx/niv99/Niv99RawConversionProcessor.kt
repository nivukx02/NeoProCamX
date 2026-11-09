package com.neoproc.neoproccamx.niv99

import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.rendering.RawLensShadingGridPolicy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Niv99RawConversionOptions(
    val spatialStrength: Float = 0.42f,
    val temporalStrength: Float = 0.30f,
    val defectSigmaThreshold: Float = 6.0f,
    val spatialSigmaThreshold: Float = 3.2f,
    val temporalSigmaThreshold: Float = 3.0f,
    val sceneCutThreshold: Float = 0.075f
) {
    init {
        require(spatialStrength in 0f..0.65f)
        require(temporalStrength in 0f..0.45f)
        require(defectSigmaThreshold in 4f..12f)
        require(spatialSigmaThreshold in 2f..6f)
        require(temporalSigmaThreshold in 2f..6f)
        require(sceneCutThreshold in 0.02f..0.25f)
    }

    companion object {
        val HIGH_QUALITY = Niv99RawConversionOptions()
    }
}

data class Niv99RawFrameEvidence(
    val route: Niv99OfflineConversionRoute,
    val sessionGeneration: Long,
    val frameNumber: Long,
    val sensorTimestampNs: Long,
    val fullFrameSamples: Int,
    val spatiallyFilteredSamples: Int,
    val temporallyFusedSamples: Int,
    val correctedDefects: Int,
    val historyUsed: Boolean,
    val historyReset: Boolean,
    val sourceSamplesPreserved: Boolean,
    val capturedWhiteBalancePreserved: Boolean,
    val processingTimeNs: Long
) {
    fun metadata(): Map<String, String> = linkedMapOf(
        "niv99RawRoute" to route.name,
        "niv99RawSessionGeneration" to sessionGeneration.toString(),
        "niv99RawFrameNumber" to frameNumber.toString(),
        "niv99RawSensorTimestampNs" to sensorTimestampNs.toString(),
        "niv99RawFullFrameSamples" to fullFrameSamples.toString(),
        "niv99RawSpatiallyFilteredSamples" to spatiallyFilteredSamples.toString(),
        "niv99RawTemporallyFusedSamples" to temporallyFusedSamples.toString(),
        "niv99RawCorrectedDefects" to correctedDefects.toString(),
        "niv99RawHistoryUsed" to historyUsed.toString(),
        "niv99RawHistoryReset" to historyReset.toString(),
        "niv99RawSourceSamplesPreserved" to sourceSamplesPreserved.toString(),
        "niv99RawCapturedWhiteBalancePreserved" to capturedWhiteBalancePreserved.toString(),
        "niv99RawProcessingTimeNs" to processingTimeNs.toString()
    )
}

data class Niv99RawProcessedFrame(
    val samples: IntArray,
    val evidence: Niv99RawFrameEvidence
)

data class Niv99RawConversionEvidence(
    val route: Niv99OfflineConversionRoute,
    val processedFrames: Long,
    val fullFrameSamples: Long,
    val spatiallyFilteredSamples: Long,
    val temporallyFusedSamples: Long,
    val correctedDefects: Long,
    val historyUsedFrames: Long,
    val historyResetFrames: Long,
    val sourceSamplesPreserved: Boolean,
    val capturedWhiteBalancePreserved: Boolean,
    val averageProcessingMs: Double,
    val maximumProcessingMs: Double,
    val gpuProcessedFrames: Long = 0L,
    val cpuProcessedFrames: Long = 0L
) {
    fun metadata(): Map<String, String> = linkedMapOf(
        "niv99RawPixelProcessingProven" to (processedFrames > 0L).toString(),
        "niv99RawRoute" to route.name,
        "niv99RawProcessedFrames" to processedFrames.toString(),
        "niv99RawFullFrameSamples" to fullFrameSamples.toString(),
        "niv99RawSpatiallyFilteredSamples" to spatiallyFilteredSamples.toString(),
        "niv99RawTemporallyFusedSamples" to temporallyFusedSamples.toString(),
        "niv99RawCorrectedDefects" to correctedDefects.toString(),
        "niv99RawHistoryUsedFrames" to historyUsedFrames.toString(),
        "niv99RawHistoryResetFrames" to historyResetFrames.toString(),
        "niv99RawSourceSamplesPreserved" to sourceSamplesPreserved.toString(),
        "niv99RawCapturedWbPreserved" to capturedWhiteBalancePreserved.toString(),
        "niv99RawGpuProcessedFrames" to gpuProcessedFrames.toString(),
        "niv99RawCpuProcessedFrames" to cpuProcessedFrames.toString(),
        "niv99RawProcessingAverageMs" to
            String.format(java.util.Locale.US, "%.3f", averageProcessingMs),
        "niv99RawProcessingMaximumMs" to
            String.format(java.util.Locale.US, "%.3f", maximumProcessingMs)
    )
}

/** Aggregates whichever full-quality NIV99 backend actually delivered each converted frame. */
class Niv99RawConversionEvidenceAccumulator(
    private val plan: Niv99OfflineConversionPlan
) {
    private var processedFrames = 0L
    private var fullFrameSamples = 0L
    private var spatiallyFilteredSamples = 0L
    private var temporallyFusedSamples = 0L
    private var correctedDefects = 0L
    private var historyUsedFrames = 0L
    private var historyResetFrames = 0L
    private var processingTimeNs = 0L
    private var maximumProcessingTimeNs = 0L
    private var allSourcesPreserved = true
    private var allWhiteBalancePreserved = true
    private var gpuProcessedFrames = 0L
    private var cpuProcessedFrames = 0L

    @Synchronized
    fun addGpu(frame: Niv99RawFrameEvidence) = add(frame, gpu = true)

    @Synchronized
    fun addCpu(frame: Niv99RawFrameEvidence) = add(frame, gpu = false)

    private fun add(frame: Niv99RawFrameEvidence, gpu: Boolean) {
        require(frame.route == plan.route) { "NIV99 evidence route changed inside one conversion" }
        require(frame.frameNumber == processedFrames) {
            "NIV99 evidence is not contiguous: frame=${frame.frameNumber} expected=$processedFrames"
        }
        require(frame.fullFrameSamples > 0) { "NIV99 evidence must cover a complete frame" }
        processedFrames = Math.addExact(processedFrames, 1L)
        fullFrameSamples = Math.addExact(fullFrameSamples, frame.fullFrameSamples.toLong())
        spatiallyFilteredSamples = Math.addExact(
            spatiallyFilteredSamples,
            frame.spatiallyFilteredSamples.toLong()
        )
        temporallyFusedSamples = Math.addExact(
            temporallyFusedSamples,
            frame.temporallyFusedSamples.toLong()
        )
        correctedDefects = Math.addExact(correctedDefects, frame.correctedDefects.toLong())
        if (frame.historyUsed) historyUsedFrames = Math.addExact(historyUsedFrames, 1L)
        if (frame.historyReset) historyResetFrames = Math.addExact(historyResetFrames, 1L)
        processingTimeNs = Math.addExact(processingTimeNs, frame.processingTimeNs.coerceAtLeast(0L))
        maximumProcessingTimeNs = max(maximumProcessingTimeNs, frame.processingTimeNs)
        allSourcesPreserved = allSourcesPreserved && frame.sourceSamplesPreserved
        allWhiteBalancePreserved =
            allWhiteBalancePreserved && frame.capturedWhiteBalancePreserved
        if (gpu) {
            gpuProcessedFrames = Math.addExact(gpuProcessedFrames, 1L)
        } else {
            cpuProcessedFrames = Math.addExact(cpuProcessedFrames, 1L)
        }
    }

    @Synchronized
    fun evidence(): Niv99RawConversionEvidence = Niv99RawConversionEvidence(
        route = plan.route,
        processedFrames = processedFrames,
        fullFrameSamples = fullFrameSamples,
        spatiallyFilteredSamples = spatiallyFilteredSamples,
        temporallyFusedSamples = temporallyFusedSamples,
        correctedDefects = correctedDefects,
        historyUsedFrames = historyUsedFrames,
        historyResetFrames = historyResetFrames,
        sourceSamplesPreserved = allSourcesPreserved,
        capturedWhiteBalancePreserved = allWhiteBalancePreserved &&
            plan.capturedWhiteBalanceAuthoritative,
        averageProcessingMs = if (processedFrames == 0L) {
            0.0
        } else {
            processingTimeNs / processedFrames.toDouble() / 1_000_000.0
        },
        maximumProcessingMs = maximumProcessingTimeNs / 1_000_000.0,
        gpuProcessedFrames = gpuProcessedFrames,
        cpuProcessedFrames = cpuProcessedFrames
    )
}

/**
 * Deterministic sensor-CFA conversion processor. It works on a copy, samples only the same CFA
 * colour at two-pixel offsets, and keeps one bounded prior frame. The recorded NPRAW master and its
 * captured WB/calibration metadata are never modified.
 */
class Niv99RawConversionProcessor(
    private val metadata: RawDevelopMetadata,
    val plan: Niv99OfflineConversionPlan,
    private val sessionGeneration: Long = 0L,
    private val options: Niv99RawConversionOptions = Niv99RawConversionOptions.HIGH_QUALITY
) {
    private val width = metadata.width
    private val height = metadata.height
    private val sampleCount = checkedSampleCount(width, height)
    private val whiteLevel = metadata.whiteLevel.coerceAtLeast(1)
    private val nominalBlack = metadata.blackLevel.coerceIn(0, whiteLevel - 1)
    private val dynamicRange = (whiteLevel - nominalBlack).coerceAtLeast(1)
    private val frameDurationNs = 1_000_000_000L / metadata.fps.coerceAtLeast(1)
    private var previous: IntArray? = null
    private var previousTimestampNs = -1L
    private var previousFrameNumber = -1L
    private var processedFrames = 0L
    private var totalFullFrameSamples = 0L
    private var totalSpatiallyFilteredSamples = 0L
    private var totalTemporallyFusedSamples = 0L
    private var totalCorrectedDefects = 0L
    private var historyUsedFrames = 0L
    private var historyResetFrames = 0L
    private var totalProcessingTimeNs = 0L
    private var maximumProcessingTimeNs = 0L

    init {
        require(plan.enabled && plan.pixelDomain == Niv99OfflinePixelDomain.SENSOR_CFA) {
            "NIV99 RAW conversion requires an enabled sensor-CFA plan"
        }
        require(metadata.storageBitDepth >= 10) {
            "NIV99 RAW conversion requires at least ten-bit source samples"
        }
        require(metadata.blackLevelRggb.size == 4) {
            "NIV99 RAW conversion requires four-channel black-level calibration"
        }
    }

    @Synchronized
    fun process(
        source: IntArray,
        frameNumber: Long,
        sensorTimestampNs: Long
    ): Niv99RawProcessedFrame = processInternal(
        source = source,
        output = source.copyOf(),
        frameNumber = frameNumber,
        sensorTimestampNs = sensorTimestampNs,
        sourceOwnedByProcessor = false
    )

    /**
     * Processes a decoded frame whose array ownership has been transferred to this processor.
     *
     * The NPRAW decoder creates a new array for every frame, so offline conversion can safely use this
     * entry point without changing the immutable file-backed master. Spatial filtering delays row writes
     * by the full two-row CFA radius, preserving the exact read-before-write result while avoiding a second
     * full-resolution [IntArray]. The returned array must not be reused by the caller.
     */
    @Synchronized
    fun processOwned(
        source: IntArray,
        frameNumber: Long,
        sensorTimestampNs: Long
    ): Niv99RawProcessedFrame = processInternal(
        source = source,
        output = source,
        frameNumber = frameNumber,
        sensorTimestampNs = sensorTimestampNs,
        sourceOwnedByProcessor = true
    )

    private fun processInternal(
        source: IntArray,
        output: IntArray,
        frameNumber: Long,
        sensorTimestampNs: Long,
        sourceOwnedByProcessor: Boolean
    ): Niv99RawProcessedFrame {
        require(source.size >= sampleCount) {
            "NIV99 RAW source has ${source.size} samples; expected at least $sampleCount"
        }
        require(frameNumber >= 0L) { "NIV99 RAW frame number must be non-negative" }
        val startedNs = System.nanoTime()
        val spatial = if (sourceOwnedByProcessor) {
            applySpatialOwned(output)
        } else {
            applySpatial(source, output)
        }
        val prior = previous
        val timelineContinuous = prior != null && isTimelineContinuous(frameNumber, sensorTimestampNs)
        val sceneCut = timelineContinuous && isSceneCut(output, checkNotNull(prior))
        val temporalCount = if (timelineContinuous && !sceneCut && options.temporalStrength > 0f) {
            applyTemporal(output, checkNotNull(prior))
        } else {
            0
        }
        val reset = prior == null || !timelineContinuous || sceneCut
        previous = output
        previousFrameNumber = frameNumber
        previousTimestampNs = sensorTimestampNs
        val evidence = Niv99RawFrameEvidence(
                route = plan.route,
                sessionGeneration = sessionGeneration,
                frameNumber = frameNumber,
                sensorTimestampNs = sensorTimestampNs,
                fullFrameSamples = sampleCount,
                spatiallyFilteredSamples = spatial.filtered,
                temporallyFusedSamples = temporalCount,
                correctedDefects = spatial.defects,
                historyUsed = temporalCount > 0,
                historyReset = reset,
                sourceSamplesPreserved = true,
                capturedWhiteBalancePreserved = plan.capturedWhiteBalanceAuthoritative,
                processingTimeNs = (System.nanoTime() - startedNs).coerceAtLeast(0L)
        )
        processedFrames++
        totalFullFrameSamples = Math.addExact(totalFullFrameSamples, sampleCount.toLong())
        totalSpatiallyFilteredSamples = Math.addExact(
            totalSpatiallyFilteredSamples,
            spatial.filtered.toLong()
        )
        totalTemporallyFusedSamples = Math.addExact(
            totalTemporallyFusedSamples,
            temporalCount.toLong()
        )
        totalCorrectedDefects = Math.addExact(totalCorrectedDefects, spatial.defects.toLong())
        if (evidence.historyUsed) historyUsedFrames++
        if (evidence.historyReset) historyResetFrames++
        totalProcessingTimeNs = Math.addExact(totalProcessingTimeNs, evidence.processingTimeNs)
        maximumProcessingTimeNs = max(maximumProcessingTimeNs, evidence.processingTimeNs)
        return Niv99RawProcessedFrame(samples = output, evidence = evidence)
    }

    @Synchronized
    fun evidence(): Niv99RawConversionEvidence = Niv99RawConversionEvidence(
        route = plan.route,
        processedFrames = processedFrames,
        fullFrameSamples = totalFullFrameSamples,
        spatiallyFilteredSamples = totalSpatiallyFilteredSamples,
        temporallyFusedSamples = totalTemporallyFusedSamples,
        correctedDefects = totalCorrectedDefects,
        historyUsedFrames = historyUsedFrames,
        historyResetFrames = historyResetFrames,
        sourceSamplesPreserved = true,
        capturedWhiteBalancePreserved = plan.capturedWhiteBalanceAuthoritative,
        averageProcessingMs = if (processedFrames == 0L) {
            0.0
        } else {
            totalProcessingTimeNs / processedFrames.toDouble() / 1_000_000.0
        },
        maximumProcessingMs = maximumProcessingTimeNs / 1_000_000.0,
        gpuProcessedFrames = 0L,
        cpuProcessedFrames = processedFrames
    )

    @Synchronized
    fun reset() {
        previous = null
        previousTimestampNs = -1L
        previousFrameNumber = -1L
        processedFrames = 0L
        totalFullFrameSamples = 0L
        totalSpatiallyFilteredSamples = 0L
        totalTemporallyFusedSamples = 0L
        totalCorrectedDefects = 0L
        historyUsedFrames = 0L
        historyResetFrames = 0L
        totalProcessingTimeNs = 0L
        maximumProcessingTimeNs = 0L
    }

    private data class SpatialCounts(val filtered: Int, val defects: Int)

    /**
     * In-place equivalent of [applySpatial]. A pixel reads same-CFA neighbours up to two rows away, so
     * each completed row is retained until row y+2 has been evaluated. This makes every read observe the
     * original frame while keeping scratch bounded to three rows instead of another full 12 MP array.
     */
    private fun applySpatialOwned(samples: IntArray): SpatialCounts {
        if (width < 5 || height < 5 || options.spatialStrength <= 0f) {
            return SpatialCounts(filtered = 0, defects = 0)
        }
        val pending = Array(3) { IntArray(width) }
        var filtered = 0
        var defects = 0
        for (y in 2 until height - 2) {
            val row = y * width
            val target = pending[y % pending.size]
            System.arraycopy(samples, row, target, 0, width)
            for (x in 2 until width - 2) {
                val index = row + x
                val center = samples[index]
                val black = blackLevelFor(x, y)
                if (center <= black || center >= highlightGuardLevel(black)) continue

                val left = samples[index - 2]
                val right = samples[index + 2]
                val up = samples[index - width * 2]
                val down = samples[index + width * 2]
                val upLeft = samples[index - width * 2 - 2]
                val upRight = samples[index - width * 2 + 2]
                val downLeft = samples[index + width * 2 - 2]
                val downRight = samples[index + width * 2 + 2]
                val trimmedMean = trimmedMean8(
                    left,
                    right,
                    up,
                    down,
                    upLeft,
                    upRight,
                    downLeft,
                    downRight
                )
                val sigma = noiseSigmaCodes(center, black, x, y)
                val defectThreshold = max(MIN_DEFECT_CODE_THRESHOLD, sigma * options.defectSigmaThreshold)
                val residualToConsensus = abs(center - trimmedMean.toFloat())
                if (residualToConsensus > defectThreshold &&
                    oppositePairSupport(left, right, up, down, sigma)
                ) {
                    target[x] = trimmedMean.coerceIn(black, whiteLevel)
                    defects++
                    continue
                }

                val horizontal = (left + right + 1) / 2
                val vertical = (up + down + 1) / 2
                val diagonalDown = (upLeft + downRight + 1) / 2
                val diagonalUp = (upRight + downLeft + 1) / 2
                val horizontalGradient = abs(left - right)
                val verticalGradient = abs(up - down)
                val diagonalDownGradient = abs(upLeft - downRight)
                val diagonalUpGradient = abs(upRight - downLeft)
                val directional = when (minOf(
                    horizontalGradient,
                    verticalGradient,
                    diagonalDownGradient,
                    diagonalUpGradient
                )) {
                    horizontalGradient -> horizontal
                    verticalGradient -> vertical
                    diagonalDownGradient -> diagonalDown
                    else -> diagonalUp
                }
                val residual = abs(center - directional.toFloat())
                val cutoff = max(1f, sigma * options.spatialSigmaThreshold)
                if (residual >= cutoff) continue
                val normalizedResidual = residual / cutoff
                val robustWeight = square(1f - normalizedResidual * normalizedResidual)
                val localGradient = minOf(
                    horizontalGradient,
                    verticalGradient,
                    diagonalDownGradient,
                    diagonalUpGradient
                ).toFloat()
                val edgeGuard = smoothstep(sigma * 1.5f, sigma * 5.5f, localGradient)
                val shadowPressure = 1f - smoothstep(
                    black + dynamicRange * 0.03f,
                    black + dynamicRange * 0.24f,
                    center.toFloat()
                )
                val gainPressure = ((whiteBalanceGainFor(x, y) - 1f) / 2f).coerceIn(0f, 1f)
                val strength = options.spatialStrength * robustWeight * (1f - edgeGuard) *
                    (0.72f + shadowPressure * 0.18f + gainPressure * 0.10f)
                if (strength <= MIN_EFFECTIVE_WEIGHT) continue
                val value = (center + (directional - center) * strength)
                    .roundToInt()
                    .coerceIn(black, whiteLevel)
                target[x] = value
                if (value != center) filtered++
            }
            // After row y is complete, no later output row can read source row y-2.
            val safeRow = y - 2
            if (safeRow >= 2) {
                System.arraycopy(pending[safeRow % pending.size], 0, samples, safeRow * width, width)
            }
        }
        // The final two computed interior rows were deliberately held back for source-neighbour safety.
        val firstPending = (height - 4).coerceAtLeast(2)
        val lastPending = height - 3
        for (rowIndex in firstPending..lastPending) {
            System.arraycopy(pending[rowIndex % pending.size], 0, samples, rowIndex * width, width)
        }
        return SpatialCounts(filtered = filtered, defects = defects)
    }

    private fun applySpatial(source: IntArray, output: IntArray): SpatialCounts {
        if (width < 5 || height < 5 || options.spatialStrength <= 0f) {
            return SpatialCounts(filtered = 0, defects = 0)
        }
        var filtered = 0
        var defects = 0
        for (y in 2 until height - 2) {
            val row = y * width
            for (x in 2 until width - 2) {
                val index = row + x
                val center = source[index]
                val black = blackLevelFor(x, y)
                if (center <= black || center >= highlightGuardLevel(black)) continue

                val left = source[index - 2]
                val right = source[index + 2]
                val up = source[index - width * 2]
                val down = source[index + width * 2]
                val upLeft = source[index - width * 2 - 2]
                val upRight = source[index - width * 2 + 2]
                val downLeft = source[index + width * 2 - 2]
                val downRight = source[index + width * 2 + 2]
                val trimmedMean = trimmedMean8(
                    left,
                    right,
                    up,
                    down,
                    upLeft,
                    upRight,
                    downLeft,
                    downRight
                )
                val sigma = noiseSigmaCodes(center, black, x, y)
                val defectThreshold = max(MIN_DEFECT_CODE_THRESHOLD, sigma * options.defectSigmaThreshold)
                val residualToConsensus = abs(center - trimmedMean.toFloat())
                if (residualToConsensus > defectThreshold &&
                    oppositePairSupport(left, right, up, down, sigma)
                ) {
                    output[index] = trimmedMean.coerceIn(black, whiteLevel)
                    defects++
                    continue
                }

                val horizontal = (left + right + 1) / 2
                val vertical = (up + down + 1) / 2
                val diagonalDown = (upLeft + downRight + 1) / 2
                val diagonalUp = (upRight + downLeft + 1) / 2
                val horizontalGradient = abs(left - right)
                val verticalGradient = abs(up - down)
                val diagonalDownGradient = abs(upLeft - downRight)
                val diagonalUpGradient = abs(upRight - downLeft)
                val directional = when (minOf(
                    horizontalGradient,
                    verticalGradient,
                    diagonalDownGradient,
                    diagonalUpGradient
                )) {
                    horizontalGradient -> horizontal
                    verticalGradient -> vertical
                    diagonalDownGradient -> diagonalDown
                    else -> diagonalUp
                }
                val residual = abs(center - directional.toFloat())
                val cutoff = max(1f, sigma * options.spatialSigmaThreshold)
                if (residual >= cutoff) continue
                val normalizedResidual = residual / cutoff
                val robustWeight = square(1f - normalizedResidual * normalizedResidual)
                val localGradient = minOf(
                    horizontalGradient,
                    verticalGradient,
                    diagonalDownGradient,
                    diagonalUpGradient
                ).toFloat()
                val edgeGuard = smoothstep(sigma * 1.5f, sigma * 5.5f, localGradient)
                val shadowPressure = 1f - smoothstep(
                    black + dynamicRange * 0.03f,
                    black + dynamicRange * 0.24f,
                    center.toFloat()
                )
                val gainPressure = ((whiteBalanceGainFor(x, y) - 1f) / 2f).coerceIn(0f, 1f)
                val strength = options.spatialStrength * robustWeight * (1f - edgeGuard) *
                    (0.72f + shadowPressure * 0.18f + gainPressure * 0.10f)
                if (strength <= MIN_EFFECTIVE_WEIGHT) continue
                val value = (center + (directional - center) * strength)
                    .roundToInt()
                    .coerceIn(black, whiteLevel)
                output[index] = value
                if (value != center) filtered++
            }
        }
        return SpatialCounts(filtered = filtered, defects = defects)
    }

    private fun applyTemporal(current: IntArray, prior: IntArray): Int {
        var fused = 0
        for (y in 2 until height - 2) {
            val row = y * width
            for (x in 2 until width - 2) {
                val index = row + x
                val value = current[index]
                val black = blackLevelFor(x, y)
                if (value <= black || value >= highlightGuardLevel(black)) continue
                val old = prior[index]
                val sigma = noiseSigmaCodes(value, black, x, y)
                val residual = abs(value - old).toFloat()
                val cutoff = max(1f, sigma * options.temporalSigmaThreshold)
                if (residual >= cutoff) continue
                val gradient = minOf(
                    abs(current[index - 2] - current[index + 2]),
                    abs(current[index - width * 2] - current[index + width * 2])
                ).toFloat()
                val structureGuard = smoothstep(sigma * 1.4f, sigma * 4.5f, gradient)
                val normalizedResidual = residual / cutoff
                val robustWeight = square(1f - normalizedResidual * normalizedResidual)
                val weight = options.temporalStrength * robustWeight * (1f - structureGuard)
                if (weight <= MIN_EFFECTIVE_WEIGHT) continue
                val blended = (value + (old - value) * weight)
                    .roundToInt()
                    .coerceIn(black, whiteLevel)
                current[index] = blended
                if (blended != value) fused++
            }
        }
        return fused
    }

    private fun isTimelineContinuous(frameNumber: Long, timestampNs: Long): Boolean {
        if (frameNumber != previousFrameNumber + 1L) return false
        if (timestampNs <= 0L || previousTimestampNs <= 0L) return true
        val gap = timestampNs - previousTimestampNs
        return gap > 0L && gap <= frameDurationNs * MAX_HISTORY_GAP_FRAMES
    }

    private fun isSceneCut(current: IntArray, prior: IntArray): Boolean {
        val stride = max(1, sampleCount / SCENE_SAMPLE_COUNT)
        var totalDifference = 0.0
        var samples = 0
        var index = 0
        while (index < sampleCount) {
            totalDifference += abs(current[index] - prior[index]).toDouble() / dynamicRange.toDouble()
            samples++
            index += stride
        }
        return samples > 0 && totalDifference / samples.toDouble() >= options.sceneCutThreshold
    }

    private fun noiseSigmaCodes(sample: Int, black: Int, x: Int, y: Int): Float {
        val signal = ((sample - black).coerceAtLeast(0)).toFloat() / dynamicRange.toFloat()
        val totalGain = (metadata.analogGain * metadata.digitalGain)
            .takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(1f, 64f)
            ?: 1f
        val calibratedShot = metadata.noiseS.takeIf { it.isFinite() && it >= 0f } ?: 0f
        val calibratedRead = metadata.noiseO.takeIf { it.isFinite() && it >= 0f } ?: 0f
        val shot = max(calibratedShot, BASE_SHOT_VARIANCE * totalGain)
        val read = max(calibratedRead, BASE_READ_VARIANCE * totalGain * totalGain)
        val rawSigma = sqrt(max(MIN_NORMALIZED_VARIANCE, shot * signal + read)) * dynamicRange
        val shading = lensShadingGainFor(x, y)
        return (rawSigma * sqrt(shading.coerceAtLeast(1f))).coerceAtLeast(MIN_SIGMA_CODES)
    }

    private fun lensShadingGainFor(x: Int, y: Int): Float {
        if (!metadata.lensShadingCorrectionEnabled ||
            metadata.lensShadingGridRggb.size != RawLensShadingGridPolicy.FLOAT_COUNT
        ) {
            return 1f
        }
        val gridSide = RawLensShadingGridPolicy.GRID_SIDE
        val channelCount = RawLensShadingGridPolicy.CHANNEL_COUNT
        val gx = ((x.toLong() * (gridSide - 1)) / max(width - 1, 1)).toInt()
        val gy = ((y.toLong() * (gridSide - 1)) / max(height - 1, 1)).toInt()
        val channel = semanticCfaChannel(x, y)
        return metadata.lensShadingGridRggb[(gy * gridSide + gx) * channelCount + channel]
            .takeIf { it.isFinite() }
            ?.coerceIn(0.25f, 8f)
            ?: 1f
    }

    private fun whiteBalanceGainFor(x: Int, y: Int): Float = when (semanticCfaChannel(x, y)) {
        0 -> metadata.whiteBalanceR
        1 -> metadata.whiteBalanceGr
        2 -> metadata.whiteBalanceGb
        else -> metadata.whiteBalanceB
    }.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.25f, 8f) ?: 1f

    private fun blackLevelFor(x: Int, y: Int): Int {
        val level = metadata.blackLevelRggb[semanticCfaChannel(x, y)]
        return level.takeIf { it.isFinite() }
            ?.roundToInt()
            ?.coerceIn(0, whiteLevel - 1)
            ?: nominalBlack
    }

    private fun semanticCfaChannel(x: Int, y: Int): Int {
        val physical = ((y and 1) shl 1) or (x and 1)
        return physical xor normalizedBayerPattern()
    }

    private fun normalizedBayerPattern(): Int = metadata.bayerPatternValue.coerceIn(0, 3)

    private fun highlightGuardLevel(black: Int): Int =
        (whiteLevel - max(2, (whiteLevel - black) / HIGHLIGHT_GUARD_DIVISOR)).coerceAtLeast(black + 1)

    private fun oppositePairSupport(left: Int, right: Int, up: Int, down: Int, sigma: Float): Boolean {
        val supportThreshold = max(MIN_DEFECT_CODE_THRESHOLD, sigma * DEFECT_NEIGHBOR_SUPPORT_SIGMA)
        return abs(left - right) <= supportThreshold && abs(up - down) <= supportThreshold
    }

    private fun trimmedMean8(
        a: Int,
        b: Int,
        c: Int,
        d: Int,
        e: Int,
        f: Int,
        g: Int,
        h: Int
    ): Int {
        val sum = a.toLong() + b + c + d + e + f + g + h
        val low = minOf(a, b, c, d, e, f, g, h)
        val high = maxOf(a, b, c, d, e, f, g, h)
        return ((sum - low - high + 3L) / 6L).toInt()
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge1 <= edge0) return if (value >= edge1) 1f else 0f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun square(value: Float): Float = value * value

    companion object {
        private const val MAX_HISTORY_GAP_FRAMES = 4L
        private const val SCENE_SAMPLE_COUNT = 4096
        private const val HIGHLIGHT_GUARD_DIVISOR = 64
        private const val MIN_EFFECTIVE_WEIGHT = 0.002f
        private const val MIN_SIGMA_CODES = 0.75f
        private const val MIN_DEFECT_CODE_THRESHOLD = 6f
        private const val DEFECT_NEIGHBOR_SUPPORT_SIGMA = 2.5f
        private const val MIN_NORMALIZED_VARIANCE = 1e-10f
        private const val BASE_SHOT_VARIANCE = 0.000018f
        private const val BASE_READ_VARIANCE = 0.0000008f

        fun processSingleFrame(
            source: IntArray,
            metadata: RawDevelopMetadata,
            plan: Niv99OfflineConversionPlan,
            options: Niv99RawConversionOptions = Niv99RawConversionOptions.HIGH_QUALITY
        ): Niv99RawProcessedFrame = Niv99RawConversionProcessor(
            metadata = metadata,
            plan = plan,
            options = options
        ).process(source, frameNumber = 0L, sensorTimestampNs = 0L)

        private fun checkedSampleCount(width: Int, height: Int): Int {
            require(width > 0 && height > 0) { "NIV99 RAW dimensions must be positive" }
            val count = width.toLong() * height.toLong()
            require(count in 1L..Int.MAX_VALUE.toLong()) {
                "NIV99 RAW dimensions exceed addressable sample storage: ${width}x$height"
            }
            return count.toInt()
        }
    }
}
