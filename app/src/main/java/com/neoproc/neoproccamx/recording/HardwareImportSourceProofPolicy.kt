package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.camera.CameraRepeatingReadinessSnapshot
import com.neoproc.neoproccamx.pipeline.HardwareImportSourceIngressTelemetry
import kotlin.math.abs

/** The ordering evidence that tied an ImageReader frame to the active Camera2 sequence. */
internal enum class HardwareImportSourceProofMethod {
    SENSOR_TIMESTAMP_ORDER,
    POST_CAPTURE_CALLBACK_INGRESS
}

/**
 * Conservative ImageReader counter boundary captured when a repeating sequence first proves a
 * capture result. It is sequence-specific so a replacement request can never inherit the prior
 * request's post-callback ingress evidence.
 */
internal data class HardwareImportRepeatingFirstFrameBoundary(
    val publicationGeneration: Long,
    val sessionGeneration: Long,
    val sequenceId: Int,
    val firstFrameSensorTimestampNs: Long,
    val firstFrameReceivedAtElapsedRealtimeNs: Long,
    val sourceImageCountAtFirstFrameProof: Long
) {
    fun matches(repeating: CameraRepeatingReadinessSnapshot): Boolean =
        publicationGeneration == repeating.publicationGeneration &&
            sessionGeneration == repeating.sessionGeneration &&
            sequenceId == repeating.sequenceId &&
            firstFrameSensorTimestampNs == repeating.firstFrameSensorTimestampNs &&
            firstFrameReceivedAtElapsedRealtimeNs ==
            repeating.firstFrameReceivedAtElapsedRealtimeNs
}

internal data class HardwareImportSourceProofEvaluation(
    val firstFrameBoundary: HardwareImportRepeatingFirstFrameBoundary?,
    val proofMethod: HardwareImportSourceProofMethod?,
    val timestampDomainsCompatible: Boolean?
)

/**
 * Proves that hardware-import pixels belong to the exact active repeating sequence.
 *
 * Android normally exposes the same sensor clock through CaptureResult and Image.timestamp, in
 * which case exact timestamp ordering remains authoritative. Some vendor P010 ImageReaders expose
 * a different timestamp domain. That condition is detected by comparing each sensor timestamp's
 * offset from the monotonic callback clock. Only then may a strictly later ImageReader callback be
 * used, and only after snapshotting the source counter when the exact repeating proof was observed.
 */
internal object HardwareImportSourceProofPolicy {
    // Callback latency may vary under startup pressure. Five seconds is deliberately generous for
    // a shared clock while remaining far below the measured multi-minute Vivo clock-domain split.
    internal const val MAX_TIMESTAMP_CLOCK_OFFSET_DELTA_NS = 5_000_000_000L

    fun evaluate(
        ingress: HardwareImportSourceIngressTelemetry,
        baselineSourceImageCount: Long,
        expectedSessionGeneration: Long,
        repeating: CameraRepeatingReadinessSnapshot?,
        existingFirstFrameBoundary: HardwareImportRepeatingFirstFrameBoundary?
    ): HardwareImportSourceProofEvaluation {
        val currentRepeating = repeating?.takeIf {
            it.sessionGeneration == expectedSessionGeneration
        } ?: return HardwareImportSourceProofEvaluation(null, null, null)
        val firstFrameSensorTimestampNs =
            currentRepeating.firstFrameSensorTimestampNs?.takeIf { it > 0L }
                ?: return HardwareImportSourceProofEvaluation(null, null, null)
        val firstFrameReceivedAtElapsedRealtimeNs =
            currentRepeating.firstFrameReceivedAtElapsedRealtimeNs?.takeIf { it > 0L }
                ?: return HardwareImportSourceProofEvaluation(null, null, null)
        val firstFrameBoundary = existingFirstFrameBoundary
            ?.takeIf { it.matches(currentRepeating) }
            ?: HardwareImportRepeatingFirstFrameBoundary(
                publicationGeneration = currentRepeating.publicationGeneration,
                sessionGeneration = currentRepeating.sessionGeneration,
                sequenceId = currentRepeating.sequenceId,
                firstFrameSensorTimestampNs = firstFrameSensorTimestampNs,
                firstFrameReceivedAtElapsedRealtimeNs =
                    firstFrameReceivedAtElapsedRealtimeNs,
                sourceImageCountAtFirstFrameProof = ingress.sourceImageCount.coerceAtLeast(0L)
            )

        val sourceIsEligible =
            ingress.sourceImageCount > baselineSourceImageCount &&
                ingress.sessionGeneration == expectedSessionGeneration &&
                ingress.containsFrame &&
                ingress.receivedAtElapsedRealtimeNs > 0L
        if (!sourceIsEligible) {
            return HardwareImportSourceProofEvaluation(firstFrameBoundary, null, null)
        }

        val timestampDomainsCompatible = timestampsShareClockDomain(
            ingressSensorTimestampNs = ingress.sensorTimestampNs,
            ingressReceivedAtElapsedRealtimeNs = ingress.receivedAtElapsedRealtimeNs,
            firstFrameSensorTimestampNs = firstFrameSensorTimestampNs,
            firstFrameReceivedAtElapsedRealtimeNs = firstFrameReceivedAtElapsedRealtimeNs
        )
        val proofMethod = when {
            timestampDomainsCompatible &&
                ingress.sensorTimestampNs >= firstFrameSensorTimestampNs ->
                HardwareImportSourceProofMethod.SENSOR_TIMESTAMP_ORDER

            !timestampDomainsCompatible &&
                ingress.sourceImageCount >
                maxOf(
                    baselineSourceImageCount,
                    firstFrameBoundary.sourceImageCountAtFirstFrameProof
                ) &&
                ingress.receivedAtElapsedRealtimeNs >
                firstFrameBoundary.firstFrameReceivedAtElapsedRealtimeNs ->
                HardwareImportSourceProofMethod.POST_CAPTURE_CALLBACK_INGRESS

            else -> null
        }
        return HardwareImportSourceProofEvaluation(
            firstFrameBoundary = firstFrameBoundary,
            proofMethod = proofMethod,
            timestampDomainsCompatible = timestampDomainsCompatible
        )
    }

    private fun timestampsShareClockDomain(
        ingressSensorTimestampNs: Long,
        ingressReceivedAtElapsedRealtimeNs: Long,
        firstFrameSensorTimestampNs: Long,
        firstFrameReceivedAtElapsedRealtimeNs: Long
    ): Boolean {
        val ingressClockOffsetNs = ingressSensorTimestampNs - ingressReceivedAtElapsedRealtimeNs
        val captureClockOffsetNs =
            firstFrameSensorTimestampNs - firstFrameReceivedAtElapsedRealtimeNs
        return safeAbsoluteDifference(ingressClockOffsetNs, captureClockOffsetNs) <=
            MAX_TIMESTAMP_CLOCK_OFFSET_DELTA_NS
    }

    private fun safeAbsoluteDifference(left: Long, right: Long): Long {
        val difference = left - right
        return if (difference == Long.MIN_VALUE) Long.MAX_VALUE else abs(difference)
    }
}
