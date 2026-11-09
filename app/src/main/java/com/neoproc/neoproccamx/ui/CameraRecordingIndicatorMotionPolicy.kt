package com.neoproc.neoproccamx.ui

/**
 * A recording-start acknowledgement that settles to a static indicator. Repeating a property
 * animation for an entire clip forces continuous root-view traversal and competes with 4K capture.
 */
internal object CameraRecordingIndicatorMotionPolicy {
    const val START_SCALE = 0.88f
    const val SETTLED_SCALE = 1.0f
    const val START_ALPHA = 0.82f
    const val SETTLED_ALPHA = 0.42f
    const val START_ACKNOWLEDGEMENT_DURATION_MS = 650L
    const val REPEATS_WHILE_RECORDING = false
}
