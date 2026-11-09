package com.neoproc.neoproccamx.pipeline

internal object AIPendingFrameBackpressurePolicy {
    fun shouldSkipBitmapPreparation(
        hasPendingFrame: Boolean,
        analysisLoopRunning: Boolean
    ): Boolean = hasPendingFrame && analysisLoopRunning
}
