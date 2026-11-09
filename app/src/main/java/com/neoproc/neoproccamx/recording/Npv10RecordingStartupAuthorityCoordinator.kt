package com.neoproc.neoproccamx.recording

import android.os.SystemClock
import com.neoproc.neoproccamx.camera.Npv10TenBitProducerProof
import com.neoproc.neoproccamx.pipeline.RawYuvPixelAuthorityTelemetry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Immutable proof that stored P010 colour evidence belongs to the active recording request. */
internal data class Npv10RecordingStartupAuthority(
    val producerProof: Npv10TenBitProducerProof,
    val pixelEvidence: RawYuvPixelAuthorityTelemetry,
    val pixelColorMetadata: Npv10PixelColorMetadataPlan
) {
    fun toMetadata(): Map<String, String> = linkedMapOf(
        "npv10ProducerPublicationGeneration" to producerProof.publicationGeneration.toString(),
        "npv10ProducerSessionGeneration" to producerProof.sessionGeneration.toString(),
        "npv10ProducerSequenceId" to producerProof.sequenceId.toString(),
        "npv10ProducerFirstFrameNumber" to producerProof.firstFrameNumber.toString(),
        "npv10ProducerFirstSensorTimestampNs" to
            producerProof.firstFrameSensorTimestampNs.toString(),
        "pixelEvidenceAtOrAfterRecordingProducer" to
            (pixelEvidence.sensorTimestampNs >= producerProof.firstFrameSensorTimestampNs).toString()
    ).apply { putAll(pixelColorMetadata.toMetadata()) }
}

internal data class Npv10RecordingStartupAuthorityResult(
    val authority: Npv10RecordingStartupAuthority?,
    val failureReason: String,
    val timedOut: Boolean
) {
    val accepted: Boolean get() = authority != null
}

/**
 * Locks one exact Camera2 repeating identity, then waits for a timestamp-joined P010 image from that
 * request boundary. A control-only repeating-request replacement in the same Camera2 session rebases
 * the boundary to the newer first frame; a session change, identity regression, or unproven producer
 * remains fail-closed. Idle/pre-arm frames can be observed while waiting but can never be accepted.
 */
internal object Npv10RecordingStartupAuthorityCoordinator {
    private const val DEFAULT_POLL_INTERVAL_MS = 25L

    suspend fun await(
        expectedWidth: Int,
        expectedHeight: Int,
        expectedImageFormat: Int,
        timeoutMs: Long,
        producerProof: () -> Npv10TenBitProducerProof?,
        pixelEvidence: () -> RawYuvPixelAuthorityTelemetry,
        startupLeaseValid: () -> Boolean = { true },
        elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
        pause: suspend (Long) -> Unit = { delay(it) },
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
    ): Npv10RecordingStartupAuthorityResult {
        require(expectedWidth > 0 && expectedHeight > 0) { "Expected P010 size must be positive" }
        require(timeoutMs > 0L && pollIntervalMs > 0L) { "Wait intervals must be positive" }
        val startedMs = elapsedRealtimeMs()
        var lockedProof: Npv10TenBitProducerProof? = null
        var lastPixelFailure = "PRODUCER_PROOF_PENDING"
        var producerStabilityPending = false
        while ((elapsedRealtimeMs() - startedMs).coerceAtLeast(0L) < timeoutMs) {
            currentCoroutineContext().ensureActive()
            if (!startupLeaseValid()) return failed("RECORDING_PRODUCER_STARTUP_LEASE_REVOKED")
            val currentProof = producerProof()
            if (currentProof == null) {
                lastPixelFailure = if (lockedProof == null) {
                    "PRODUCER_PROOF_PENDING"
                } else {
                    // setRepeatingRequest replaces the old sequence before the first result from the
                    // new request can prove it. This bounded gap is not producer loss by itself.
                    "SAME_SESSION_PRODUCER_REPLACEMENT_PROOF_PENDING"
                }
                producerStabilityPending = lockedProof != null
                pause(pollIntervalMs)
                continue
            }
            producerStabilityPending = false
            val previousProof = lockedProof
            if (previousProof == null) {
                lockedProof = currentProof
            } else if (currentProof != previousProof) {
                val transitionFailure = unsafeTransitionFailure(previousProof, currentProof)
                if (transitionFailure != null) return failed(transitionFailure)
                // Camera controls (AE/AWB/focus) legitimately install a newer repeating request while
                // recording startup is proving P010. Rebase and require evidence from its first frame;
                // never accept pixels merely because they were valid for the superseded request.
                lockedProof = currentProof
                lastPixelFailure = "REBASED_PRODUCER_AWAITING_PIXEL_AUTHORITY"
            }
            val proof = requireNotNull(lockedProof)
            val evidence = pixelEvidence()
            val plan = Npv10PixelColorMetadataPolicy.resolve(
                evidence = evidence,
                expectedSessionGeneration = proof.sessionGeneration,
                expectedWidth = expectedWidth,
                expectedHeight = expectedHeight,
                expectedImageFormat = expectedImageFormat,
                minimumSensorTimestampNs = proof.firstFrameSensorTimestampNs
            )
            if (plan.recordingEligible) {
                val finalProof = producerProof()
                if (finalProof == null) {
                    lastPixelFailure = "PRODUCER_REPLACEMENT_DURING_PIXEL_PROOF"
                    producerStabilityPending = true
                } else if (finalProof != proof) {
                    val transitionFailure = unsafeTransitionFailure(proof, finalProof)
                    if (transitionFailure != null) return failed(transitionFailure)
                    lockedProof = finalProof
                    lastPixelFailure = "REBASED_PRODUCER_AWAITING_PIXEL_AUTHORITY"
                    producerStabilityPending = true
                } else {
                    if (!startupLeaseValid()) {
                        return failed("RECORDING_PRODUCER_STARTUP_LEASE_REVOKED")
                    }
                    return Npv10RecordingStartupAuthorityResult(
                        authority = Npv10RecordingStartupAuthority(proof, evidence, plan),
                        failureReason = "",
                        timedOut = false
                    )
                }
            } else {
                lastPixelFailure = plan.reason
            }
            pause(pollIntervalMs)
        }
        val reason = if (lockedProof == null) {
            "RECORDING_PRODUCER_PROOF_TIMEOUT"
        } else if (producerStabilityPending) {
            "RECORDING_PRODUCER_STABILITY_TIMEOUT:$lastPixelFailure"
        } else {
            "RECORDING_PIXEL_AUTHORITY_TIMEOUT:$lastPixelFailure"
        }
        return failed(reason, timedOut = true)
    }

    private fun failed(
        reason: String,
        timedOut: Boolean = false
    ) = Npv10RecordingStartupAuthorityResult(
        authority = null,
        failureReason = reason,
        timedOut = timedOut
    )

    /**
     * A newer publication in the same Camera2 session is one continuous P010 producer lineage. All
     * other identity changes are unsafe: accepting them could join pixels from another session or
     * resurrect an older repeating request after a race.
     */
    private fun unsafeTransitionFailure(
        previous: Npv10TenBitProducerProof,
        current: Npv10TenBitProducerProof
    ): String? = when {
        current.sessionGeneration != previous.sessionGeneration ->
            "RECORDING_PRODUCER_SESSION_CHANGED"
        current.publicationGeneration < previous.publicationGeneration ->
            "RECORDING_PRODUCER_IDENTITY_REGRESSED"
        current.publicationGeneration == previous.publicationGeneration ->
            "RECORDING_PRODUCER_IDENTITY_CONFLICT"
        else -> null
    }
}
