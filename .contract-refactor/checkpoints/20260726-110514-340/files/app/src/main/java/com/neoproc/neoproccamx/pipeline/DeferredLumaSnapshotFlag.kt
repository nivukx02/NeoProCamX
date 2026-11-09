package com.neoproc.neoproccamx.pipeline

/**
 * DeferredLumaSnapshotFlag — reversible gate that DEFERS the per-frame analysis luma downscale
 * ([FramePipelineManager.snapshotLumaPlane]) off the Camera2 image-callback thread and onto the
 * single-thread FramePipeline processing executor.
 *
 * Today the long-edge luma copy/downscale runs on the camera-callback thread, in line, before the frame
 * is handed to processingExecutor. That strided full-luma-plane read into a pooled buffer lengthens the
 * callback and can delay the next acquireLatestImage / hardware drain and AE/AF/AWB metadata delivery.
 * The cheap per-frame GATES that DECIDE whether to snapshot (shouldCaptureLumaSnapshot + the adaptive
 * strideMultiplier) stay on the callback thread; only the heavy copy moves.
 *
 * SAFETY: the source android.media.Image is closed ONLY inside the executor body, and a single in-flight
 * frame is guaranteed by the `processing` AtomicBoolean, so reading the image from the executor is
 * exactly as valid as the existing snapshotAnalysisRawFrame / analyzeMediaImage reads that already run
 * there. No pixels change; only WHICH thread performs the downscale changes.
 *
 * DEFAULT OFF (device A/B required). When false, behaviour is byte-identical to today (snapshot computed
 * on the callback thread exactly where it is now). Read per-frame, so a flip takes effect on the next
 * frame callback:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.deferred_luma_snapshot true
 */
object DeferredLumaSnapshotFlag {

    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
