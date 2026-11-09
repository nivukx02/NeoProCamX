package com.neoproc.neoproccamx.pipeline

/**
 * Fail-closed Camera2 lineage check for asynchronous sensor-linear NDR work.
 *
 * RAW images, capture metadata, GPU completions, and encoder submissions can all outlive the
 * CameraCaptureSession that created them. A non-negative immutable generation on the work item is
 * therefore mandatory; sampling only the mutable current generation would relabel an old frame.
 */
internal object SensorLinearNdrSessionGenerationPolicy {
    fun isCurrent(frameSessionGeneration: Long, currentSessionGeneration: Long): Boolean =
        frameSessionGeneration >= 0L &&
            currentSessionGeneration >= 0L &&
            frameSessionGeneration == currentSessionGeneration
}
