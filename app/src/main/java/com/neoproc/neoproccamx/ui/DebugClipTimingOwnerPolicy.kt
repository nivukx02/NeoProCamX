package com.neoproc.neoproccamx.ui

/**
 * Selects the recorder that owns clip-timing evidence for debug endurance commands.
 *
 * NPRAW and NPV10 maintain their own first-durable-frame timestamps. Forwarding those sessions to
 * [com.neoproc.neoproccamx.recording.RecordingManager] is invalid because its encoder clock was
 * never started. This policy keeps the automation observer from touching an inactive backend.
 */
internal enum class DebugClipTimingOwner {
    GPU_ENCODER,
    NPV10,
    NPRAW
}

internal object DebugClipTimingOwnerPolicy {
    fun resolve(rawActive: Boolean, npv10Active: Boolean): DebugClipTimingOwner =
        when {
            rawActive -> DebugClipTimingOwner.NPRAW
            npv10Active -> DebugClipTimingOwner.NPV10
            else -> DebugClipTimingOwner.GPU_ENCODER
        }
}
