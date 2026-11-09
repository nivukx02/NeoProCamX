package com.neoproc.neoproccamx.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One immutable fatal cause for one prepared output transaction. */
internal data class RecordingFatalFailure(
    val generation: Long,
    val message: String
)

/**
 * Bridges a worker's publication failure to the owner that can safely detach Camera2/GPU inputs.
 *
 * Poisoning a file alone is not cleanup: the audio drain can exit while Camera2, the encoder and
 * the foreground service keep running indefinitely. The replayed signal survives Activity loss;
 * its consumer must use the normal serialized stop path, never release a codec from its worker.
 * Reset, first-cause publication and stop claiming share a lock so a late notification cannot
 * overwrite a new session's cause, nor can a queued old event terminate the next recording.
 */
internal class RecordingFatalFailureSignal {
    private val lock = Any()
    private var generation = 0L
    private var stopClaimed = false
    private val mutableFailures = MutableStateFlow<RecordingFatalFailure?>(null)
    val failures: StateFlow<RecordingFatalFailure?> = mutableFailures.asStateFlow()

    fun reset() = synchronized(lock) {
        generation += 1L
        stopClaimed = false
        mutableFailures.value = null
    }

    fun get(): String? = mutableFailures.value?.message

    fun recordFirst(message: String): RecordingFatalFailure? = synchronized(lock) {
        if (mutableFailures.value != null) return@synchronized null
        val failure = RecordingFatalFailure(
            generation = generation,
            message = message.trim().ifEmpty { "Unspecified fatal recording pipeline failure" }
        )
        mutableFailures.value = failure
        failure
    }

    /** Claim only when the backend is active; a Starting/Stopping observation must not consume it. */
    fun claimStop(failure: RecordingFatalFailure): Boolean = synchronized(lock) {
        if (stopClaimed || mutableFailures.value !== failure) return@synchronized false
        stopClaimed = true
        true
    }
}
