package com.neoproc.neoproccamx.pipeline

/**
 * HardwareDrainSerializationFlag — reversible serialization of the live hardware-import frame drain.
 *
 * WHY: [FramePipelineManager.drainHardwareReader] is reached from TWO threads — the Camera2
 * hardware-import ImageReader listener thread (via enqueueHardware) and the single hardware-dispatch
 * executor thread (via drainPendingHardwareReaderIfCapacity). Both call `reader.acquireNextImage()` and
 * then `RealtimeFrameDispatcher.offer()`. ImageReader is not documented safe for concurrent acquire, so
 * the race corrupts the max-images accounting / native buffer state (surfacing as swallowed acquire
 * failures + budget mis-accounting), and the shared dispatcher's `get()`-then-increment writeIndex can
 * collide two producers on one ring slot and spuriously drop a recorded frame.
 *
 * ImageReader acquisition/admission is always protected by `HardwareImageReaderAcquisitionGate`;
 * disabling that protection would reintroduce an unsafe hardware race. When this flag is ON,
 * `offer()` is additionally serialized under `producerLock` as defense-in-depth, so two producers
 * cannot select the same ring slot.
 * The critical section is bounded (at most hardwareImportDrainPerCallback acquires + offers, no GPU
 * submit) and runs on background threads, so it cannot ANR and never changes a pixel or sheds a
 * deliverable frame.
 *
 * NO-DOWNGRADE: pure mutual-exclusion; it can only PREVENT the queue-producer race and PRESERVE frames,
 * never alter or shed one. Default ON. Flag OFF changes only the dispatcher's producer serialization;
 * mandatory ImageReader acquisition safety remains active. Reversible for on-device A/B (read per
 * offer, so it takes effect immediately):
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.hardware_drain_serialize false
 */
object HardwareDrainSerializationFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
