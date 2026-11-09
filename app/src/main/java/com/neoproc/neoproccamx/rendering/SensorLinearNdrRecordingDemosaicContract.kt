package com.neoproc.neoproccamx.rendering

/**
 * Recorder-facing result of attempting to submit one ordered RAW_SENSOR frame.
 *
 * [ACCEPTED] means the current frame was submitted to the project-owned demosaic path. Its GPU
 * completion may still be outstanding. [RETRY_PENDING] means the caller must retain and retry this
 * exact Image: either an older accepted frame still owns a demosaic lane, or the independent preview
 * ring is temporarily full before JNI dispatch. [completionPending] states whether an older native
 * identity remains physically owned. [FAILED] is terminal and must never be treated as a preview skip.
 */
internal enum class SensorLinearNdrDemosaicSubmissionStatus {
    ACCEPTED,
    RETRY_PENDING,
    FAILED
}

/** Exact subsystem that owns a RETRY_PENDING interval; used to keep failure deadlines honest. */
internal enum class SensorLinearNdrDemosaicRetryCause {
    NATIVE_OUTPUT,
    PREVIEW_TRANSPORT,
    RENDER_PROFILE_TRANSITION
}

internal data class SensorLinearNdrDemosaicSubmission(
    val status: SensorLinearNdrDemosaicSubmissionStatus,
    val completionPending: Boolean = false,
    val retryCause: SensorLinearNdrDemosaicRetryCause? = null,
    val reason: String? = null
)

/** Native completion state for the last accepted recorder-owned demosaic dispatch. */
internal enum class SensorLinearNdrDemosaicCompletionStatus {
    IDLE,
    COMPLETED,
    PENDING,
    FAILED
}

internal data class SensorLinearNdrDemosaicCompletion(
    val status: SensorLinearNdrDemosaicCompletionStatus,
    val reason: String? = null
)

/**
 * Transition result used while moving the shared demosaic output from skippable preview ownership to
 * ordered recording ownership. No recording frame is admitted until [READY].
 */
internal enum class SensorLinearNdrDemosaicTransitionStatus {
    READY,
    PENDING,
    FAILED
}
