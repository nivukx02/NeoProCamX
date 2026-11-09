package com.neoproc.neoproccamx.rendering

/** Exclusive owner of the single mutable RAW demosaic master image. */
internal enum class SensorLinearNdrDemosaicOwnerPhase {
    PREVIEW,
    TRANSITION,
    RECORDING,
    RETIRING_FAILED,
    /**
     * Sticky fail-closed state for a native fence/ownership invariant failure. Reusing the shared
     * mutable output after this point could relabel old pixels as a new preview or recording frame.
     * Only explicit Vulkan teardown may reset this state to [PREVIEW].
     */
    TERMINAL_NATIVE_FAILURE
}

/** Result of discarding an invalid recorder tail after the encoder has been detached. */
internal enum class SensorLinearNdrFailedTailRetirementStatus {
    RETIRED,
    PENDING,
    ENCODER_STILL_BOUND,
    TERMINAL_NATIVE_FAILURE
}
