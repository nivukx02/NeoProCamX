package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Free sensor axis owned by the app when Camera2 does not advertise Android 16 AE priority. */
internal enum class SoftwareAePriorityAxis {
    ISO,
    SHUTTER
}

internal enum class SoftwareAeSignalDomain {
    PROCESSED_YUV,
    SENSOR_LINEAR_RAW
}

internal data class SoftwareAeSceneSample(
    val domain: SoftwareAeSignalDomain,
    val averageLuma: Float,
    val highlightClipFraction: Float,
    val redHighlightClipFraction: Float,
    val measuredAtElapsedRealtimeMs: Long,
    val meteredIso: Int? = null,
    val meteredExposureTimeNs: Long? = null,
    val sensorTimestampNs: Long = 0L,
    val controlRevision: Long = -1L
)

internal data class SoftwareAePriorityRequest(
    val sessionGeneration: Long,
    val mode: UnifiedVideoControlMode,
    val detailProfile: VideoDetailProfile,
    val axis: SoftwareAePriorityAxis,
    val fixedIso: Int? = null,
    val fixedExposureTimeNs: Long? = null,
    val requestedEvStops: Float = 0f,
    /** True when the platform-AE meter pair already contains [requestedEvStops]. */
    val meteredExposureIncludesRequestedEv: Boolean = false,
    /**
     * Keep the platform-AE-derived exposure product fixed after the first manual-sensor request.
     * This is reserved for a producer whose HAL loses a sensor frame whenever a repeating request
     * changes. The initial pair still preserves measured brightness; only subsequent autonomous
     * request churn is suppressed. User ISO/shutter/EV changes create a new signature and therefore
     * retain precedence.
     */
    val lockComplementaryAxisAfterInitialization: Boolean = false,
    val meteredIso: Int?,
    val meteredExposureTimeNs: Long?,
    val isoRange: CameraIntRange,
    val exposureTimeRangeNs: CameraLongRange,
    val frameExposureBudgetNs: Long
)

internal data class SoftwareAePriorityDecision(
    val ready: Boolean,
    val axis: SoftwareAePriorityAxis,
    val sensorSensitivityIso: Int?,
    val exposureTimeNs: Long?,
    val targetLuma: Float,
    val measuredLuma: Float,
    val correctionStops: Float,
    val changed: Boolean,
    val saturated: Boolean,
    val revision: Long,
    val reason: String
)

/**
 * Bounded app-owned ISO/shutter-priority loop for HALs that expose manual sensor control but only
 * CONTROL_AE_PRIORITY_MODE_OFF. It preserves the platform-AE brightness present when the operator
 * selects one axis, then adjusts only the complementary axis from timestamped frame luminance.
 *
 * This controller never invents a result from a stale image. A missing/stale meter returns
 * [SoftwareAePriorityDecision.ready] = false, which makes the Camera2 integration retain platform AE
 * instead of freezing one old exposure pair during a reframe.
 */
internal class SoftwareAePriorityController {
    companion object {
        const val MAX_SAMPLE_AGE_MS = 750L
        const val MIN_ADJUSTMENT_INTERVAL_MS = 120L
        const val TRANSIENT_ADJUSTMENT_INTERVAL_MS = 80L
        private const val MIN_LUMA = 1e-4f
        private const val ERROR_DEADBAND_STOPS = 0.045f
        private const val TRANSIENT_ERROR_STOPS = 0.75f
        private const val MIN_ISO_CHANGE_FRACTION = 0.02
        private const val MIN_EXPOSURE_CHANGE_FRACTION = 0.012
    }

    private data class Signature(
        val sessionGeneration: Long,
        val mode: UnifiedVideoControlMode,
        val detailProfile: VideoDetailProfile,
        val axis: SoftwareAePriorityAxis,
        val fixedIso: Int?,
        val fixedExposureTimeNs: Long?,
        val requestedEvStops: Float,
        val meteredExposureIncludesRequestedEv: Boolean,
        val lockComplementaryAxisAfterInitialization: Boolean,
        val isoRange: CameraIntRange,
        val exposureRange: CameraLongRange,
        val frameBudgetNs: Long,
        val signalDomain: SoftwareAeSignalDomain
    )

    private data class State(
        val signature: Signature,
        /** Platform-AE exposure product captured before this controller takes AE-OFF ownership. */
        val neutralExposureProduct: Double,
        /** Scene brightness paired with [neutralExposureProduct], before user EV is applied. */
        val neutralTargetLuma: Float,
        val targetLuma: Float,
        val iso: Int,
        val exposureTimeNs: Long,
        val lastSampleTimestampMs: Long,
        val lastAdjustmentElapsedMs: Long,
        val lastMeasuredLuma: Float,
        val lastCorrectionStops: Float,
        val saturated: Boolean,
        val revision: Long
    )

    private var state: State? = null
    private var revision = 0L

    @Synchronized
    fun reset() {
        state = null
    }

    @Synchronized
    fun resolve(
        request: SoftwareAePriorityRequest,
        sample: SoftwareAeSceneSample?,
        nowElapsedRealtimeMs: Long
    ): SoftwareAePriorityDecision {
        val normalizedIso = normalized(request.isoRange)
        val normalizedExposure = normalized(request.exposureTimeRangeNs)
        val frameBudget = request.frameExposureBudgetNs
            .coerceAtLeast(normalizedExposure.lower)
            .coerceAtMost(normalizedExposure.upper)
        val boundedExposure = CameraLongRange(
            normalizedExposure.lower,
            max(normalizedExposure.lower, frameBudget)
        )
        val validSample = sample?.takeIf {
            it.averageLuma.isFinite() &&
                it.averageLuma >= MIN_LUMA &&
                it.measuredAtElapsedRealtimeMs > 0L &&
                nowElapsedRealtimeMs >= it.measuredAtElapsedRealtimeMs &&
                nowElapsedRealtimeMs - it.measuredAtElapsedRealtimeMs <= MAX_SAMPLE_AGE_MS
        }
        if (validSample == null) {
            val hadState = state != null
            state = null
            return unavailable(request.axis, changed = hadState, reason = "METER_UNAVAILABLE_OR_STALE")
        }

        val fixedIso = request.fixedIso?.coerceIn(normalizedIso.lower, normalizedIso.upper)
        val fixedExposure = request.fixedExposureTimeNs?.coerceIn(
            boundedExposure.lower,
            boundedExposure.upper
        )
        val validAxis = when (request.axis) {
            SoftwareAePriorityAxis.ISO -> fixedIso != null && request.fixedExposureTimeNs == null
            SoftwareAePriorityAxis.SHUTTER -> fixedExposure != null && request.fixedIso == null
        }
        if (!validAxis) {
            val hadState = state != null
            state = null
            return unavailable(request.axis, changed = hadState, reason = "INVALID_FIXED_AXIS")
        }

        val evStops = request.requestedEvStops.takeIf(Float::isFinite)?.coerceIn(-6f, 6f) ?: 0f
        val signature = Signature(
            sessionGeneration = request.sessionGeneration.coerceAtLeast(0L),
            mode = request.mode,
            detailProfile = request.detailProfile,
            axis = request.axis,
            fixedIso = fixedIso,
            fixedExposureTimeNs = fixedExposure,
            requestedEvStops = evStops,
            meteredExposureIncludesRequestedEv = request.meteredExposureIncludesRequestedEv,
            lockComplementaryAxisAfterInitialization =
                request.lockComplementaryAxisAfterInitialization,
            isoRange = normalizedIso,
            exposureRange = boundedExposure,
            frameBudgetNs = frameBudget,
            signalDomain = validSample.domain
        )
        val current = state
        val controlContextChanged = current != null && (
            current.signature.sessionGeneration != signature.sessionGeneration ||
                current.signature.mode != signature.mode ||
                current.signature.signalDomain != signature.signalDomain
            )
        if (controlContextChanged &&
            validSample.measuredAtElapsedRealtimeMs <= current.lastSampleTimestampMs
        ) {
            // A session/mode transition must observe at least one frame produced after that
            // transition. Reusing the final frame from the previous request would immediately put
            // AE back into the same frozen-axis failure this controller exists to prevent.
            return unavailable(
                request.axis,
                changed = true,
                reason = "METER_PREDATES_CONTROL_CONTEXT"
            )
        }
        if (current == null || current.signature != signature) {
            val meteredIso = request.meteredIso
                ?.coerceIn(normalizedIso.lower, normalizedIso.upper)
                ?: return unavailable(request.axis, changed = current != null, reason = "METERED_ISO_MISSING")
            val meteredExposure = request.meteredExposureTimeNs
                ?.coerceIn(boundedExposure.lower, boundedExposure.upper)
                ?: return unavailable(
                    request.axis,
                    changed = current != null,
                    reason = "METERED_SHUTTER_MISSING"
                )
            // Keep one neutral platform-AE anchor across EV/profile/fixed-axis edits. CaptureResult
            // feedback while AE is OFF reports our own previous request; treating that value as a
            // fresh baseline makes repeated +EV/-EV edits compound gain and can white out the frame.
            val meteredEvScale = if (request.meteredExposureIncludesRequestedEv) {
                2.0.pow(evStops.toDouble())
            } else {
                1.0
            }
            val neutralExposureProduct = current
                ?.takeUnless { controlContextChanged }
                ?.neutralExposureProduct
                ?: (meteredIso.toDouble() * meteredExposure.toDouble() / meteredEvScale)
            val neutralTargetLuma = current
                ?.takeUnless { controlContextChanged }
                ?.neutralTargetLuma
                ?: targetLuma(
                    validSample,
                    evStops = if (request.meteredExposureIncludesRequestedEv) -evStops else 0f
                )
            val exposureProduct = neutralExposureProduct * 2.0.pow(evStops.toDouble())
            val initialIso: Int
            val initialExposure: Long
            when (request.axis) {
                SoftwareAePriorityAxis.ISO -> {
                    initialIso = requireNotNull(fixedIso)
                    initialExposure = (exposureProduct / initialIso.toDouble())
                        .roundToLong()
                        .coerceIn(boundedExposure.lower, boundedExposure.upper)
                }
                SoftwareAePriorityAxis.SHUTTER -> {
                    initialExposure = requireNotNull(fixedExposure)
                    initialIso = (exposureProduct / initialExposure.toDouble())
                        .roundToInt()
                        .coerceIn(normalizedIso.lower, normalizedIso.upper)
                }
            }
            val targetLuma = targetLuma(
                neutralLuma = neutralTargetLuma,
                domain = validSample.domain,
                evStops = evStops
            )
            revision += 1L
            val initialized = State(
                signature = signature,
                neutralExposureProduct = neutralExposureProduct,
                neutralTargetLuma = neutralTargetLuma,
                targetLuma = targetLuma,
                iso = initialIso,
                exposureTimeNs = initialExposure,
                lastSampleTimestampMs = validSample.measuredAtElapsedRealtimeMs,
                lastAdjustmentElapsedMs = nowElapsedRealtimeMs,
                lastMeasuredLuma = validSample.averageLuma,
                lastCorrectionStops = 0f,
                saturated = false,
                revision = revision
            )
            state = initialized
            return initialized.toDecision(changed = true, reason = "INITIALIZED_FROM_LIVE_AE")
        }

        if (validSample.measuredAtElapsedRealtimeMs <= current.lastSampleTimestampMs) {
            return current.toDecision(changed = false, reason = "WAITING_FOR_NEW_FRAME")
        }
        if (signature.lockComplementaryAxisAfterInitialization) {
            // Some Camera2 HALs create one 2x sensor interval for every manual ISO update. The
            // cadence-specific path enters with a brightness-preserving pair derived from live AE,
            // so keep that proven pair immutable for the clip instead of destabilizing the physical
            // producer. Refresh sample freshness for the stale-authority watchdog without issuing a
            // new Camera2 request.
            val held = current.copy(
                lastSampleTimestampMs = validSample.measuredAtElapsedRealtimeMs,
                lastMeasuredLuma = validSample.averageLuma,
                lastCorrectionStops = 0f
            )
            state = held
            return held.toDecision(changed = false, reason = "FIXED_CADENCE_HOLD")
        }
        val lumaErrorStops = log2(current.targetLuma / validSample.averageLuma.coerceAtLeast(MIN_LUMA))
        val clipProtectionStops = clipProtectionStops(validSample)
        val requestedCorrection = minOf(lumaErrorStops, clipProtectionStops)
        val requiredIntervalMs = if (
            abs(lumaErrorStops) >= TRANSIENT_ERROR_STOPS ||
            clipProtectionStops <= -0.80f
        ) {
            TRANSIENT_ADJUSTMENT_INTERVAL_MS
        } else {
            MIN_ADJUSTMENT_INTERVAL_MS
        }
        if (nowElapsedRealtimeMs - current.lastAdjustmentElapsedMs < requiredIntervalMs) {
            state = current.copy(
                lastSampleTimestampMs = validSample.measuredAtElapsedRealtimeMs,
                lastMeasuredLuma = validSample.averageLuma
            )
            return requireNotNull(state).toDecision(changed = false, reason = "SETTLING")
        }

        val boundedCorrection = boundedCorrection(
            requestedCorrection,
            request.detailProfile,
            validSample
        )
        if (abs(boundedCorrection) < ERROR_DEADBAND_STOPS) {
            val held = current.copy(
                lastSampleTimestampMs = validSample.measuredAtElapsedRealtimeMs,
                lastMeasuredLuma = validSample.averageLuma,
                lastCorrectionStops = 0f
            )
            state = held
            return held.toDecision(changed = false, reason = "WITHIN_DEADBAND")
        }

        val multiplier = 2.0.pow(boundedCorrection.toDouble())
        var nextIso = current.iso
        var nextExposure = current.exposureTimeNs
        when (request.axis) {
            SoftwareAePriorityAxis.ISO -> {
                nextExposure = (current.exposureTimeNs.toDouble() * multiplier)
                    .roundToLong()
                    .coerceIn(boundedExposure.lower, boundedExposure.upper)
            }
            SoftwareAePriorityAxis.SHUTTER -> {
                nextIso = (current.iso.toDouble() * multiplier)
                    .roundToInt()
                    .coerceIn(normalizedIso.lower, normalizedIso.upper)
            }
        }
        val materiallyChanged = when (request.axis) {
            SoftwareAePriorityAxis.ISO ->
                abs(nextExposure - current.exposureTimeNs).toDouble() >=
                    max(50_000.0, current.exposureTimeNs * MIN_EXPOSURE_CHANGE_FRACTION)
            SoftwareAePriorityAxis.SHUTTER ->
                abs(nextIso - current.iso).toDouble() >=
                    max(1.0, current.iso * MIN_ISO_CHANGE_FRACTION)
        }
        if (!materiallyChanged) {
            val held = current.copy(
                lastSampleTimestampMs = validSample.measuredAtElapsedRealtimeMs,
                lastMeasuredLuma = validSample.averageLuma,
                lastCorrectionStops = boundedCorrection
            )
            state = held
            return held.toDecision(changed = false, reason = "BELOW_REQUEST_QUANTUM")
        }

        val saturated = when (request.axis) {
            SoftwareAePriorityAxis.ISO ->
                nextExposure == boundedExposure.lower || nextExposure == boundedExposure.upper
            SoftwareAePriorityAxis.SHUTTER -> nextIso == normalizedIso.lower || nextIso == normalizedIso.upper
        }
        revision += 1L
        val updated = current.copy(
            iso = nextIso,
            exposureTimeNs = nextExposure,
            lastSampleTimestampMs = validSample.measuredAtElapsedRealtimeMs,
            lastAdjustmentElapsedMs = nowElapsedRealtimeMs,
            lastMeasuredLuma = validSample.averageLuma,
            lastCorrectionStops = boundedCorrection,
            saturated = saturated,
            revision = revision
        )
        state = updated
        return updated.toDecision(
            changed = true,
            reason = if (saturated) "UPDATED_AT_AXIS_LIMIT" else "UPDATED_FROM_LIVE_LUMA"
        )
    }

    private fun State.toDecision(changed: Boolean, reason: String) = SoftwareAePriorityDecision(
        ready = true,
        axis = signature.axis,
        sensorSensitivityIso = iso,
        exposureTimeNs = exposureTimeNs,
        targetLuma = targetLuma,
        measuredLuma = lastMeasuredLuma,
        correctionStops = lastCorrectionStops,
        changed = changed,
        saturated = saturated,
        revision = revision,
        reason = reason
    )

    private fun unavailable(
        axis: SoftwareAePriorityAxis,
        changed: Boolean,
        reason: String
    ) = SoftwareAePriorityDecision(
        ready = false,
        axis = axis,
        sensorSensitivityIso = null,
        exposureTimeNs = null,
        targetLuma = 0f,
        measuredLuma = 0f,
        correctionStops = 0f,
        changed = changed,
        saturated = false,
        revision = revision,
        reason = reason
    )

    private fun targetLuma(sample: SoftwareAeSceneSample, evStops: Float): Float {
        return targetLuma(sample.averageLuma, sample.domain, evStops)
    }

    private fun targetLuma(
        neutralLuma: Float,
        domain: SoftwareAeSignalDomain,
        evStops: Float
    ): Float {
        val scaled = neutralLuma.toDouble() * 2.0.pow(evStops.toDouble())
        val bounds = when (domain) {
            SoftwareAeSignalDomain.PROCESSED_YUV -> 0.02 to 0.90
            SoftwareAeSignalDomain.SENSOR_LINEAR_RAW -> 0.005 to 0.55
        }
        return scaled.coerceIn(bounds.first, bounds.second).toFloat()
    }

    private fun clipProtectionStops(sample: SoftwareAeSceneSample): Float {
        val clip = maxOf(
            sample.highlightClipFraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
            sample.redHighlightClipFraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        )
        return when {
            sample.averageLuma >= 0.98f || clip >= 0.10f -> -2.0f
            clip >= 0.04f -> -1.25f
            clip >= 0.015f -> -0.80f
            clip >= 0.004f -> -0.45f
            else -> Float.POSITIVE_INFINITY
        }
    }

    private fun boundedCorrection(
        requestedStops: Float,
        profile: VideoDetailProfile,
        sample: SoftwareAeSceneSample
    ): Float {
        if (!requestedStops.isFinite()) return 0f
        val (attackLimit, steadyReleaseLimit) = when (profile) {
            VideoDetailProfile.NATURAL_DETAIL -> 0.45f to 0.16f
            VideoDetailProfile.FOCUSED_DETAIL -> 0.65f to 0.22f
            VideoDetailProfile.EXTREME_DETAIL -> 0.85f to 0.28f
        }
        val brightEmergency = sample.averageLuma >= 0.98f ||
            sample.highlightClipFraction >= 0.10f ||
            sample.redHighlightClipFraction >= 0.10f
        val attack = if (brightEmergency) 1.5f else attackLimit
        // A large dark-scene transition gets a bounded two-times release window. Fine exposure
        // corrections keep the original slower response, preserving stability and avoiding pump.
        val largeDarkTransition = requestedStops >= TRANSIENT_ERROR_STOPS
        val releaseLimit = if (largeDarkTransition) steadyReleaseLimit * 2f else steadyReleaseLimit
        val response = when {
            requestedStops < 0f -> 0.72f
            largeDarkTransition -> 0.52f
            else -> 0.38f
        }
        return (requestedStops * response).coerceIn(-attack, releaseLimit)
    }

    private fun normalized(range: CameraIntRange): CameraIntRange {
        val lower = minOf(range.lower, range.upper).coerceAtLeast(1)
        val upper = maxOf(range.lower, range.upper).coerceAtLeast(lower)
        return CameraIntRange(lower, upper)
    }

    private fun normalized(range: CameraLongRange): CameraLongRange {
        val lower = minOf(range.lower, range.upper).coerceAtLeast(1L)
        val upper = maxOf(range.lower, range.upper).coerceAtLeast(lower)
        return CameraLongRange(lower, upper)
    }

    private fun log2(value: Float): Float =
        (ln(value.coerceAtLeast(MIN_LUMA).toDouble()) / ln(2.0)).toFloat()
}
