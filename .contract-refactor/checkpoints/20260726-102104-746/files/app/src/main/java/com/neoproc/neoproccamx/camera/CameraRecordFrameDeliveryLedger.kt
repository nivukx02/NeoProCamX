package com.neoproc.neoproccamx.camera

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  s1-f05 — RECORD-PATH FRAME-DELIVERY LEDGER (honest dropped-frame accounting)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * A cinema body's record path drops ZERO frames in steady state; any frame
 * lost during an explicit transition (a session reconfigure / mode switch) is
 * a bounded, *reported* event — never a silent gap. Camera2 surfaces the two
 * failure modes that constitute a real dropped frame on the record path:
 *
 *  - [android.hardware.camera2.CameraCaptureSession.CaptureCallback.onCaptureFailed]
 *    with [android.hardware.camera2.CaptureFailure.REASON_ERROR] — the HAL could
 *    not produce the requested frame (a genuine drop).
 *  - [android.hardware.camera2.CameraCaptureSession.CaptureCallback.onCaptureBufferLost]
 *    — the buffer for a target was lost before delivery.
 *
 * A [android.hardware.camera2.CaptureFailure.REASON_FLUSHED] failure is NOT a
 * drop: it is the intended consequence of flush()/abortCaptures()/session close
 * during a reconfigure, so it is always classified as a bounded transition event.
 *
 * This ledger is the single honest counter. It is observe-only — it never
 * touches the request stream — so wiring it into the live capture callback is
 * byte-identical to today's output. The controller marks the reconfigure epoch
 * via [markReconfigureStart]/[markReconfigureSettled] so a drop that lands while
 * a session is being rebuilt is attributed to that transition window instead of
 * masquerading as a silent steady-state drop.
 *
 * Thread-safety: capture callbacks fire on the camera-callback executor while
 * reconfigure markers run on the camera thread, so every mutator is
 * [Synchronized] on this instance.
 */
internal class CameraRecordFrameDeliveryLedger {

    private var completedFrames: Long = 0L
    private var steadyStateErrorDrops: Long = 0L
    private var steadyStateBufferLosses: Long = 0L
    private var reconfigureWindowDrops: Long = 0L
    private var reconfigureWindowBufferLosses: Long = 0L
    private var reconfigureWindowsOpened: Long = 0L
    private var reconfiguring: Boolean = false

    /** Open the reconfigure transition window. Idempotent; counts only edges. */
    @Synchronized
    fun markReconfigureStart() {
        if (!reconfiguring) {
            reconfiguring = true
            reconfigureWindowsOpened++
        }
    }

    /** Close the reconfigure transition window. Idempotent. */
    @Synchronized
    fun markReconfigureSettled() {
        reconfiguring = false
    }

    /** A frame completed on the record path. Counted only while recording. */
    @Synchronized
    fun onFrameCompleted(recordingActive: Boolean) {
        if (!recordingActive) return
        completedFrames++
    }

    /**
     * A capture failed. [flushed] is true for [CaptureFailure.REASON_FLUSHED]
     * (an intended transition drop, always bounded); a non-flushed failure that
     * lands outside a reconfigure window is the unintended steady-state drop the
     * elite target forbids.
     */
    @Synchronized
    fun onCaptureFailed(recordingActive: Boolean, flushed: Boolean) {
        if (!recordingActive) return
        if (flushed || reconfiguring) {
            reconfigureWindowDrops++
        } else {
            steadyStateErrorDrops++
        }
    }

    /** A target buffer was lost. Bounded while reconfiguring, unintended otherwise. */
    @Synchronized
    fun onBufferLost(recordingActive: Boolean) {
        if (!recordingActive) return
        if (reconfiguring) {
            reconfigureWindowBufferLosses++
        } else {
            steadyStateBufferLosses++
        }
    }

    @Synchronized
    fun reset() {
        completedFrames = 0L
        steadyStateErrorDrops = 0L
        steadyStateBufferLosses = 0L
        reconfigureWindowDrops = 0L
        reconfigureWindowBufferLosses = 0L
        reconfigureWindowsOpened = 0L
        reconfiguring = false
    }

    @Synchronized
    fun snapshot(): CameraRecordFrameDeliverySnapshot =
        CameraRecordFrameDeliverySnapshot(
            completedFrames = completedFrames,
            steadyStateErrorDrops = steadyStateErrorDrops,
            steadyStateBufferLosses = steadyStateBufferLosses,
            reconfigureWindowDrops = reconfigureWindowDrops,
            reconfigureWindowBufferLosses = reconfigureWindowBufferLosses,
            reconfigureWindowsOpened = reconfigureWindowsOpened,
            currentlyReconfiguring = reconfiguring
        )

    companion object {
        /**
         * Default ceiling on bounded drops attributable to a single reconfigure
         * window. A correct hot-swap (preview kept streaming until the
         * replacement session configures) drops at most a handful of frames per
         * transition; anything beyond this is a glitchy reconfigure worth flagging.
         */
        const val DEFAULT_RECONFIGURE_DROP_BOUND_PER_WINDOW = 4
    }
}

/**
 * Immutable honest snapshot of record-path frame delivery. The derived
 * predicates encode the s1-f05 acceptance criteria so a soak/device check (and
 * the unit suite) can assert them directly.
 */
internal data class CameraRecordFrameDeliverySnapshot(
    val completedFrames: Long,
    val steadyStateErrorDrops: Long,
    val steadyStateBufferLosses: Long,
    val reconfigureWindowDrops: Long,
    val reconfigureWindowBufferLosses: Long,
    val reconfigureWindowsOpened: Long,
    val currentlyReconfiguring: Boolean
) {
    /** Unintended drops outside any transition window — the elite target is 0. */
    val steadyStateUnintendedDrops: Long
        get() = steadyStateErrorDrops + steadyStateBufferLosses

    /** Bounded, reported drops attributable to reconfigure transitions. */
    val reconfigureBoundedDrops: Long
        get() = reconfigureWindowDrops + reconfigureWindowBufferLosses

    /** True when no unintended steady-state frame was lost on the record path. */
    val steadyStateClean: Boolean
        get() = steadyStateUnintendedDrops == 0L

    /** True when transition drops stay within [boundPerWindow] for every window opened. */
    fun reconfigureDropsWithinBound(
        boundPerWindow: Int = CameraRecordFrameDeliveryLedger.DEFAULT_RECONFIGURE_DROP_BOUND_PER_WINDOW
    ): Boolean {
        val allowance = reconfigureWindowsOpened * boundPerWindow.toLong()
        return reconfigureBoundedDrops <= allowance
    }

    fun toCompactMetadataMap(): Map<String, String> = linkedMapOf(
        "recordFrameDeliveryCompletedFrames" to completedFrames.toString(),
        "recordFrameDeliverySteadyStateUnintendedDrops" to steadyStateUnintendedDrops.toString(),
        "recordFrameDeliverySteadyStateErrorDrops" to steadyStateErrorDrops.toString(),
        "recordFrameDeliverySteadyStateBufferLosses" to steadyStateBufferLosses.toString(),
        "recordFrameDeliveryReconfigureBoundedDrops" to reconfigureBoundedDrops.toString(),
        "recordFrameDeliveryReconfigureWindowsOpened" to reconfigureWindowsOpened.toString(),
        "recordFrameDeliverySteadyStateClean" to steadyStateClean.toString(),
        "recordFrameDeliveryReconfigureWithinBound" to reconfigureDropsWithinBound().toString()
    )
}
