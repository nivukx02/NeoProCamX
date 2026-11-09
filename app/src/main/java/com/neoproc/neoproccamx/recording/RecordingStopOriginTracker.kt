package com.neoproc.neoproccamx.recording

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Identifies the first authority that requested the end of an admitted recording session.
 *
 * Recording finalisation is intentionally idempotent and can be requested by several independent
 * owners at nearly the same time. Keeping the first request is essential: a later shutter/debug
 * request must not overwrite an earlier thermal, lifecycle, writer, or pipeline failure and turn a
 * premature stop into an apparently successful endurance run.
 */
internal enum class RecordingStopOrigin {
    NOT_REQUESTED,
    USER_SHUTTER,
    DEBUG_COMMAND,
    THERMAL_EMERGENCY,
    APP_BACKGROUND,
    FOREGROUND_SERVICE_FAILURE,
    RAW_CONTINUITY_BOUNDARY,
    NPV10_AUDIO_FAILURE,
    RAW_AUDIO_FAILURE,
    NPV10_WRITER_FAILURE,
    SENSOR_LINEAR_PIPELINE_FAILURE,
    PIPELINE_FAILURE,
    VIEW_MODEL_TEARDOWN
}

internal data class RecordingStopOriginEvidence(
    val sessionGeneration: Long,
    val validationLabel: String,
    val origin: RecordingStopOrigin,
    val detail: String,
    val requestedElapsedRealtimeMs: Long
) {
    fun toMetadata(): Map<String, String> = linkedMapOf(
        "recordingStopSessionGeneration" to sessionGeneration.toString(),
        "recordingStopValidationLabel" to validationLabel,
        "recordingStopOrigin" to origin.name,
        "recordingStopDetail" to detail,
        "recordingStopRequestedElapsedRealtimeMs" to requestedElapsedRealtimeMs.toString()
    )
}

internal data class RecordingStopOriginRecordResult(
    val evidence: RecordingStopOriginEvidence,
    val newlyRecorded: Boolean
)

/** Thread-safe, allocation-light stop evidence shared by every recording backend. */
internal class RecordingStopOriginTracker(
    private val elapsedRealtimeMs: () -> Long
) {
    private val generation = AtomicLong(0L)
    private val evidence = AtomicReference(inactiveEvidence())

    fun beginSession(validationLabel: String?): RecordingStopOriginEvidence {
        val nextGeneration = generation.incrementAndGet()
        val next = RecordingStopOriginEvidence(
            sessionGeneration = nextGeneration,
            validationLabel = sanitize(validationLabel),
            origin = RecordingStopOrigin.NOT_REQUESTED,
            detail = "",
            requestedElapsedRealtimeMs = 0L
        )
        evidence.set(next)
        return next
    }

    fun recordFirst(
        origin: RecordingStopOrigin,
        detail: String = ""
    ): RecordingStopOriginRecordResult {
        require(origin != RecordingStopOrigin.NOT_REQUESTED) {
            "NOT_REQUESTED cannot terminate a recording session"
        }
        while (true) {
            val current = evidence.get()
            if (current.origin != RecordingStopOrigin.NOT_REQUESTED) {
                return RecordingStopOriginRecordResult(current, newlyRecorded = false)
            }
            val next = current.copy(
                origin = origin,
                detail = sanitize(detail),
                requestedElapsedRealtimeMs = elapsedRealtimeMs().coerceAtLeast(0L)
            )
            if (evidence.compareAndSet(current, next)) {
                return RecordingStopOriginRecordResult(next, newlyRecorded = true)
            }
        }
    }

    fun snapshot(): RecordingStopOriginEvidence = evidence.get()

    private companion object {
        fun inactiveEvidence() = RecordingStopOriginEvidence(
            sessionGeneration = 0L,
            validationLabel = "",
            origin = RecordingStopOrigin.NOT_REQUESTED,
            detail = "",
            requestedElapsedRealtimeMs = 0L
        )

        fun sanitize(value: String?): String = value
            .orEmpty()
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('=', ':')
            .trim()
            .take(512)
    }
}
