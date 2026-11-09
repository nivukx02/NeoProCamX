package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.rendering.ProjectOwnedNdrColorProof
import com.neoproc.neoproccamx.rendering.ProjectOwnedNdrNeutralAxisValidation
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Presentation-only RAW colour metadata after validation and temporal stabilization. */
internal data class RawPreviewColorMetadata(
    val whiteBalanceRggb: FloatArray,
    val sensorToXyz: FloatArray,
    /** Monotonic identity for one validated camera/session/epoch stabilization stream. */
    val stabilizerGeneration: Long
)

internal enum class RawPreviewColorMetadataSource {
    LIVE,
    SMOOTHED,
    HELD_LAST_VALID,
    SKIPPED_INVALID_FIRST_FRAME
}

/** Exact reason a candidate colour packet was rejected before it could affect visible pixels. */
internal enum class RawPreviewColorMetadataRejectionReason {
    MISSING_WHITE_BALANCE,
    INVALID_WHITE_BALANCE_SIZE,
    NON_FINITE_WHITE_BALANCE,
    WHITE_BALANCE_OUT_OF_RANGE,
    GREEN_CHANNEL_RATIO_EXCEEDED,
    MISSING_SENSOR_TO_XYZ,
    INVALID_SENSOR_TO_XYZ_SIZE,
    NON_FINITE_SENSOR_TO_XYZ,
    SENSOR_TO_XYZ_COEFFICIENT_OUT_OF_RANGE,
    SINGULAR_SENSOR_TO_XYZ,
    NEUTRAL_AXIS_REJECTED,
    TEMPORAL_OUTLIER
}

/**
 * Immutable evidence for a rejected packet.
 *
 * Mutable camera arrays are deliberately not retained. [diagnosticSummary] renders the owner's
 * still-current inputs only after the rate limiter permits a log, keeping repeated rejection cheap.
 */
internal data class RawPreviewColorMetadataRejection(
    val reason: RawPreviewColorMetadataRejectionReason,
    val actualSize: Int = -1,
    val expectedSize: Int = -1,
    val minimumObserved: Float = Float.NaN,
    val maximumObserved: Float = Float.NaN,
    val minimumAllowed: Float = Float.NaN,
    val maximumAllowed: Float = Float.NaN,
    val gainRatio: Float = Float.NaN,
    val gainRatioLimit: Float = Float.NaN,
    val matrixDelta: Float = Float.NaN,
    val matrixDeltaLimit: Float = Float.NaN,
    val determinant: Float = Float.NaN,
    val determinantMinimumAbs: Float = Float.NaN,
    val maximumAbsoluteCoefficient: Float = Float.NaN,
    val maximumAbsoluteCoefficientLimit: Float = Float.NaN,
    val neutralAxisValidation: ProjectOwnedNdrNeutralAxisValidation? = null
) {
    fun diagnosticSummary(
        whiteBalanceRggb: FloatArray?,
        sensorToXyz: FloatArray?
    ): String = buildString {
        if (actualSize >= 0) append("size=$actualSize expected=$expectedSize ")
        if (minimumObserved.isFinite()) {
            append("observedRange=[$minimumObserved,$maximumObserved] ")
            append("allowedRange=[$minimumAllowed,$maximumAllowed] ")
        }
        if (gainRatio.isFinite()) append("gainRatio=$gainRatio limit=$gainRatioLimit ")
        if (matrixDelta.isFinite()) append("matrixDelta=$matrixDelta limit=$matrixDeltaLimit ")
        if (determinant.isFinite()) {
            append("determinant=$determinant minimumAbs=$determinantMinimumAbs ")
        }
        if (maximumAbsoluteCoefficient.isFinite()) {
            append("maximumAbsCoefficient=$maximumAbsoluteCoefficient ")
            append("limit=$maximumAbsoluteCoefficientLimit ")
        }
        neutralAxisValidation?.let { neutral ->
            append("neutralStatus=${neutral.status} ")
            append("acesCg=[${neutral.acesCgR},${neutral.acesCgG},${neutral.acesCgB}] ")
            append("spread=${neutral.maximumChannelSpread} ")
        }
        append("gains=${whiteBalanceRggb.compactSummary()} ")
        append("matrix=${sensorToXyz.compactSummary()}")
    }
}

internal data class RawPreviewColorMetadataResolution(
    val metadata: RawPreviewColorMetadata?,
    val source: RawPreviewColorMetadataSource,
    val rejection: RawPreviewColorMetadataRejection? = null
) {
    val shouldPresent: Boolean get() = metadata != null
}

internal data class RawPreviewColorDiagnosticLogDecision(
    val shouldLog: Boolean,
    val suppressedSinceLastLog: Long
)

/** Pure, bounded rate limiter used by the production owner and exercised on the host JVM. */
internal class RawPreviewColorDiagnosticRateLimiter(
    private val minimumIntervalMs: Long = 5_000L,
    private val maximumSignatures: Int = 16
) {
    private data class SignatureState(
        val lastLogElapsedMs: Long,
        val suppressedSinceLastLog: Long
    )

    private val states = LinkedHashMap<String, SignatureState>()

    init {
        require(minimumIntervalMs >= 0L)
        require(maximumSignatures > 0)
    }

    @Synchronized
    fun evaluate(signature: String, elapsedRealtimeMs: Long): RawPreviewColorDiagnosticLogDecision {
        require(signature.isNotBlank())
        val now = elapsedRealtimeMs.coerceAtLeast(0L)
        val previous = states[signature]
        if (previous == null) {
            makeRoomForNewSignature()
            states[signature] = SignatureState(now, 0L)
            return RawPreviewColorDiagnosticLogDecision(true, 0L)
        }
        val elapsed = now - previous.lastLogElapsedMs
        if (elapsed < 0L || elapsed >= minimumIntervalMs) {
            states[signature] = SignatureState(now, 0L)
            return RawPreviewColorDiagnosticLogDecision(
                shouldLog = true,
                suppressedSinceLastLog = previous.suppressedSinceLastLog
            )
        }
        states[signature] = previous.copy(
            suppressedSinceLastLog = previous.suppressedSinceLastLog + 1L
        )
        return RawPreviewColorDiagnosticLogDecision(false, 0L)
    }

    @Synchronized
    fun reset() {
        states.clear()
    }

    private fun makeRoomForNewSignature() {
        if (states.size < maximumSignatures) return
        val oldest = states.minByOrNull { it.value.lastLogElapsedMs }?.key ?: return
        states.remove(oldest)
    }
}

/**
 * Rejects missing/corrupt Camera2 colour metadata before it can reach the RAW monitor.
 *
 * This state is deliberately presentation-only: RAW_SENSOR bytes and the exact metadata written to
 * DNG/NPRAW sidecars never pass through it. Valid changes are smoothed in gain/log space and matrix
 * space; missing values or implausible one-frame jumps hold the last proven packet. With no proven
 * packet yet, the frame is skipped instead of briefly presenting a red, green, or monochrome image.
 */
internal class RawPreviewColorMetadataStabilizer(
    private val gainAlpha: Float = 0.28f,
    private val matrixAlpha: Float = 0.20f,
    private val maximumOneFrameGainRatio: Float = 1.65f,
    private val maximumOneFrameMatrixDelta: Float = 0.55f,
    private val requiredOutlierConfirmationFrames: Int = 2,
    private val maximumConfirmationGainRatio: Float = 1.18f,
    private val maximumConfirmationMatrixDelta: Float = 0.10f
) {
    private var lastValid: RawPreviewColorMetadata? = null
    private var pendingOutlier: RawPreviewColorMetadata? = null
    private var pendingOutlierCount = 0
    private var confirmedAdaptationTarget: RawPreviewColorMetadata? = null
    private var lastStreamIdentity: String? = null
    private var generationCounter = 0L
    private var activeGeneration = 0L

    @Synchronized
    fun resolve(
        whiteBalanceRggb: FloatArray?,
        sensorToXyz: FloatArray?,
        streamIdentity: String = "default"
    ): RawPreviewColorMetadataResolution {
        if (lastStreamIdentity != streamIdentity) {
            lastValid = null
            resetOutlierAdaptation()
            lastStreamIdentity = streamIdentity
            activeGeneration = nextGeneration()
        }
        val gainRejection = validateWhiteBalance(whiteBalanceRggb)
        if (gainRejection != null) {
            // Confirmation is deliberately consecutive. A missing/corrupt metadata packet breaks
            // the evidence chain and may not help a later colour jump pass temporal validation.
            resetOutlierAdaptation()
            return holdOrSkip(gainRejection)
        }
        val matrixRejection = validateMatrix(sensorToXyz)
        if (matrixRejection != null) {
            resetOutlierAdaptation()
            return holdOrSkip(matrixRejection)
        }

        val candidate = RawPreviewColorMetadata(
            whiteBalanceRggb = whiteBalanceRggb!!.copyOf(),
            sensorToXyz = sensorToXyz!!.copyOf(),
            stabilizerGeneration = activeGeneration
        )
        val previous = lastValid
        if (previous == null) {
            lastValid = candidate
            return RawPreviewColorMetadataResolution(candidate.copyDeep(), RawPreviewColorMetadataSource.LIVE)
        }

        val temporalRejection = temporalOutlier(previous, candidate)
        if (temporalRejection != null && !acceptConfirmedOutlier(candidate)) {
            return holdOrSkip(temporalRejection)
        }
        if (temporalRejection == null) {
            resetOutlierAdaptation()
        }

        val smoothed = RawPreviewColorMetadata(
            whiteBalanceRggb = FloatArray(4) { index ->
                logLerp(previous.whiteBalanceRggb[index], candidate.whiteBalanceRggb[index], gainAlpha)
            },
            sensorToXyz = FloatArray(9) { index ->
                lerp(previous.sensorToXyz[index], candidate.sensorToXyz[index], matrixAlpha)
            },
            stabilizerGeneration = previous.stabilizerGeneration
        )
        lastValid = smoothed
        return RawPreviewColorMetadataResolution(smoothed.copyDeep(), RawPreviewColorMetadataSource.SMOOTHED)
    }

    @Synchronized
    fun reset() {
        lastValid = null
        resetOutlierAdaptation()
        lastStreamIdentity = null
        activeGeneration = 0L
    }

    private fun holdOrSkip(
        rejection: RawPreviewColorMetadataRejection
    ): RawPreviewColorMetadataResolution {
        val held = lastValid
        return if (held != null) {
            RawPreviewColorMetadataResolution(
                held.copyDeep(),
                RawPreviewColorMetadataSource.HELD_LAST_VALID,
                rejection
            )
        } else {
            RawPreviewColorMetadataResolution(
                metadata = null,
                source = RawPreviewColorMetadataSource.SKIPPED_INVALID_FIRST_FRAME,
                rejection = rejection
            )
        }
    }

    private fun temporalOutlier(
        previous: RawPreviewColorMetadata,
        candidate: RawPreviewColorMetadata
    ): RawPreviewColorMetadataRejection? {
        val gainJump = previous.whiteBalanceRggb.indices.maxOf { index ->
            val a = previous.whiteBalanceRggb[index]
            val b = candidate.whiteBalanceRggb[index]
            max(a / b, b / a)
        }
        val matrixJump = previous.sensorToXyz.indices.maxOf { index ->
            abs(previous.sensorToXyz[index] - candidate.sensorToXyz[index])
        }
        if (gainJump <= maximumOneFrameGainRatio && matrixJump <= maximumOneFrameMatrixDelta) {
            return null
        }
        return RawPreviewColorMetadataRejection(
            reason = RawPreviewColorMetadataRejectionReason.TEMPORAL_OUTLIER,
            gainRatio = gainJump,
            gainRatioLimit = maximumOneFrameGainRatio,
            matrixDelta = matrixJump,
            matrixDeltaLimit = maximumOneFrameMatrixDelta
        )
    }

    /**
     * Requires two mutually consistent packets before adapting to a large WB/CCM transition. Once
     * confirmed, every matching packet advances the bounded smoother until the normal temporal gate
     * is reached. This rejects a one-frame colour flash without permanently freezing a real illuminant
     * change (the previous implementation could reject a sustained large change forever).
     */
    private fun acceptConfirmedOutlier(candidate: RawPreviewColorMetadata): Boolean {
        val confirmed = confirmedAdaptationTarget
        if (confirmed != null && confirmationPairMatches(confirmed, candidate)) {
            confirmedAdaptationTarget = candidate.copyDeep()
            return true
        }

        val pending = pendingOutlier
        if (pending == null || !confirmationPairMatches(pending, candidate)) {
            pendingOutlier = candidate.copyDeep()
            pendingOutlierCount = 1
            confirmedAdaptationTarget = null
            return false
        }

        pendingOutlierCount += 1
        pendingOutlier = candidate.copyDeep()
        if (pendingOutlierCount < requiredOutlierConfirmationFrames.coerceAtLeast(2)) return false
        confirmedAdaptationTarget = candidate.copyDeep()
        pendingOutlier = null
        pendingOutlierCount = 0
        return true
    }

    private fun confirmationPairMatches(
        first: RawPreviewColorMetadata,
        second: RawPreviewColorMetadata
    ): Boolean {
        val gainRatio = first.whiteBalanceRggb.indices.maxOf { index ->
            val a = first.whiteBalanceRggb[index]
            val b = second.whiteBalanceRggb[index]
            max(a / b, b / a)
        }
        val matrixDelta = first.sensorToXyz.indices.maxOf { index ->
            abs(first.sensorToXyz[index] - second.sensorToXyz[index])
        }
        return gainRatio <= maximumConfirmationGainRatio &&
            matrixDelta <= maximumConfirmationMatrixDelta
    }

    private fun resetOutlierAdaptation() {
        pendingOutlier = null
        pendingOutlierCount = 0
        confirmedAdaptationTarget = null
    }

    private fun validateWhiteBalance(
        gains: FloatArray?
    ): RawPreviewColorMetadataRejection? {
        if (gains == null) {
            return RawPreviewColorMetadataRejection(
                RawPreviewColorMetadataRejectionReason.MISSING_WHITE_BALANCE
            )
        }
        if (gains.size != 4) {
            return RawPreviewColorMetadataRejection(
                reason = RawPreviewColorMetadataRejectionReason.INVALID_WHITE_BALANCE_SIZE,
                actualSize = gains.size,
                expectedSize = 4
            )
        }
        if (gains.any { !it.isFinite() }) {
            return RawPreviewColorMetadataRejection(
                RawPreviewColorMetadataRejectionReason.NON_FINITE_WHITE_BALANCE
            )
        }
        if (gains.any { it < MIN_GAIN || it > MAX_GAIN }) {
            return RawPreviewColorMetadataRejection(
                reason = RawPreviewColorMetadataRejectionReason.WHITE_BALANCE_OUT_OF_RANGE,
                minimumObserved = gains.minOrNull() ?: Float.NaN,
                maximumObserved = gains.maxOrNull() ?: Float.NaN,
                minimumAllowed = MIN_GAIN,
                maximumAllowed = MAX_GAIN
            )
        }
        val greenRatio = max(gains[1], gains[2]) / min(gains[1], gains[2])
        if (greenRatio > MAX_GREEN_CHANNEL_RATIO) {
            return RawPreviewColorMetadataRejection(
                reason = RawPreviewColorMetadataRejectionReason.GREEN_CHANNEL_RATIO_EXCEEDED,
                gainRatio = greenRatio,
                gainRatioLimit = MAX_GREEN_CHANNEL_RATIO
            )
        }
        return null
    }

    private fun validateMatrix(
        matrix: FloatArray?
    ): RawPreviewColorMetadataRejection? {
        if (matrix == null) {
            return RawPreviewColorMetadataRejection(
                RawPreviewColorMetadataRejectionReason.MISSING_SENSOR_TO_XYZ
            )
        }
        if (matrix.size != 9) {
            return RawPreviewColorMetadataRejection(
                reason = RawPreviewColorMetadataRejectionReason.INVALID_SENSOR_TO_XYZ_SIZE,
                actualSize = matrix.size,
                expectedSize = 9
            )
        }
        if (matrix.any { !it.isFinite() }) {
            return RawPreviewColorMetadataRejection(
                RawPreviewColorMetadataRejectionReason.NON_FINITE_SENSOR_TO_XYZ
            )
        }
        val maximumAbsCoefficient = matrix.maxOf(::abs)
        if (maximumAbsCoefficient > MAX_MATRIX_COEFFICIENT) {
            return RawPreviewColorMetadataRejection(
                reason = RawPreviewColorMetadataRejectionReason.SENSOR_TO_XYZ_COEFFICIENT_OUT_OF_RANGE,
                maximumAbsoluteCoefficient = maximumAbsCoefficient,
                maximumAbsoluteCoefficientLimit = MAX_MATRIX_COEFFICIENT
            )
        }
        val determinant =
            matrix[0] * (matrix[4] * matrix[8] - matrix[5] * matrix[7]) -
                matrix[1] * (matrix[3] * matrix[8] - matrix[5] * matrix[6]) +
                matrix[2] * (matrix[3] * matrix[7] - matrix[4] * matrix[6])
        if (!determinant.isFinite() || abs(determinant) < MIN_ABS_DETERMINANT) {
            return RawPreviewColorMetadataRejection(
                reason = RawPreviewColorMetadataRejectionReason.SINGULAR_SENSOR_TO_XYZ,
                determinant = determinant,
                determinantMinimumAbs = MIN_ABS_DETERMINANT
            )
        }
        val neutral = ProjectOwnedNdrColorProof.validateNeutralAxis(matrix)
        if (!neutral.passed) {
            return RawPreviewColorMetadataRejection(
                reason = RawPreviewColorMetadataRejectionReason.NEUTRAL_AXIS_REJECTED,
                determinant = determinant,
                determinantMinimumAbs = MIN_ABS_DETERMINANT,
                neutralAxisValidation = neutral
            )
        }
        return null
    }

    private fun logLerp(from: Float, to: Float, alpha: Float): Float =
        exp(lerp(ln(from), ln(to), alpha))

    private fun lerp(from: Float, to: Float, alpha: Float): Float =
        from + (to - from) * alpha.coerceIn(0f, 1f)

    private fun RawPreviewColorMetadata.copyDeep(): RawPreviewColorMetadata =
        RawPreviewColorMetadata(
            whiteBalanceRggb.copyOf(),
            sensorToXyz.copyOf(),
            stabilizerGeneration
        )

    private fun nextGeneration(): Long {
        generationCounter = if (generationCounter == Long.MAX_VALUE) 1L else generationCounter + 1L
        return generationCounter
    }

    private companion object {
        const val MIN_GAIN = 0.20f
        const val MAX_GAIN = 12.0f
        const val MAX_GREEN_CHANNEL_RATIO = 1.75f
        const val MAX_MATRIX_COEFFICIENT = 8.0f
        const val MIN_ABS_DETERMINANT = 1.0e-5f
    }
}

private fun FloatArray?.compactSummary(): String = this?.joinToString(
    prefix = "[",
    postfix = "]",
    separator = ","
) ?: "null"
