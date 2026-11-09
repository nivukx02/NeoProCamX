package com.neoproc.neoproccamx.pipeline

/**
 * RecordPipelineDepthFlag — reversible depth of the hardware-import frame dispatcher that feeds the
 * live offscreen-EGL encoder submit thread.
 *
 * The dispatcher ([RealtimeFrameDispatcher]) is a conflate-oldest ring: when a slot is occupied by a
 * frame older than the acquire guard, the oldest queued frame is dropped so a newer one can take its
 * place. Neo Thermal Stall Vault V4 admits up to fifty-three recording FIFO frames from the
 * memory-tiered full-resolution P010 ImageReader. Two reader slots remain reserved for Camera2/native
 * ownership. The strict recording queue never conflates accepted frames; overload remains visible
 * instead of being hidden by synthetic cadence.
 *
 * The recording guard is one 25 fps frame interval, so a frame is not treated as stale while it is
 * still inside the selected cadence budget. On a device with at least 10 GiB RAM the byte-bounded
 * reader admits fifty-five 4K P010 images; fifty-three owned 25 fps frames cover 2.120 s, exceeding the
 * measured 1.759 s thermal-4 producer stall. Lower-memory devices retain the V3 twenty-four-image
 * reader and its twenty-two-frame/880 ms bound.
 *
 * NO-DOWNGRADE / NOT-A-BATTERY-OPT: bit-identical per-frame pixels; it can only PRESERVE more frames
 * during a stall, never alter one. It costs a little more AHardwareBuffer memory (free here). Default
 * ON to deliver the smoother cadence live. Reversible for on-device A/B:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.record_pipeline_depth false
 * The capacity is read when the dispatcher is constructed (pipeline init / next recording), so a flip
 * takes effect on the next pipeline build — matching the other request/pipeline flags.
 */
object RecordPipelineDepthFlag {

    /** Bounded ring remains one slot below the large-memory fifty-five-image P010 reader ceiling. */
    const val DEEP_HARDWARE_IMPORT_CAPACITY = 54

    /** Shipped baseline capacity (the pre-change value) used when the flag is OFF. */
    const val BASELINE_HARDWARE_IMPORT_CAPACITY = 2

    /** Recording-only staleness guard for 25 fps 4K paths. Preview/analysis still use the 2 ms guard. */
    const val HARDWARE_IMPORT_RECORD_GUARD_NS = 1_000_000_000L / 25L

    /** Keep reader slots free for the Camera2 producer and the active native submit. */
    const val HARDWARE_IMPORT_READER_RESERVED_IMAGES = 2

    /** Avoid acquiring a whole reader burst into Java while native GL/codec submit is paced. */
    const val HARDWARE_IMPORT_RECORD_DRAIN_PER_CALLBACK = 2

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }

    fun hardwareImportCapacity(): Int =
        if (enabled) DEEP_HARDWARE_IMPORT_CAPACITY else BASELINE_HARDWARE_IMPORT_CAPACITY

    fun hardwareImportAcquireGuardNs(): Long =
        if (enabled) HARDWARE_IMPORT_RECORD_GUARD_NS else PipelineOrchestrator.FRAME_ACQUIRE_GUARD_NS

    fun hardwareImportDrainPerCallback(): Int =
        if (enabled) HARDWARE_IMPORT_RECORD_DRAIN_PER_CALLBACK else 1

    fun hardwareImportReaderHighWatermark(readerMaxImages: Int): Int =
        (readerMaxImages - HARDWARE_IMPORT_READER_RESERVED_IMAGES)
            .coerceAtLeast(1)
            .coerceAtMost(hardwareImportCapacity())
}
