package com.neoproc.neoproccamx.ui

import java.util.concurrent.atomic.AtomicBoolean

/** Lock-free ownership latch for the opaque Settings screen and its next warm camera reopen. */
internal class CameraSettingsSuspensionState {
    private val suspended = AtomicBoolean(false)
    private val fastResumePending = AtomicBoolean(false)

    fun suspend(): Boolean {
        fastResumePending.set(false)
        return suspended.compareAndSet(false, true)
    }

    fun resume(): Boolean {
        if (!suspended.compareAndSet(true, false)) return false
        fastResumePending.set(true)
        return true
    }

    fun consumeFastResume(): Boolean = fastResumePending.getAndSet(false)

    fun isSuspended(): Boolean = suspended.get()
}
