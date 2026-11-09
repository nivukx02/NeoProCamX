package com.neoproc.neoproccamx.ui

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** Gives offline conversion exclusive ownership of CPU/GPU/storage by closing Camera2 first. */
internal class CameraConversionIsolationCoordinator(
    private val dispatchMainAndWait: (() -> Unit) -> Unit,
    private val captureActive: () -> Boolean,
    private val cameraClosed: () -> Boolean,
    private val closeCamera: () -> Unit,
    private val reopenCamera: () -> Unit,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val nanoTime: () -> Long = System::nanoTime,
    private val closeTimeoutMs: Long = 8_000L,
    private val trace: (String) -> Unit = {}
) {
    private val ownershipLock = ReentrantLock()
    private val active = AtomicBoolean(false)
    val isActive: Boolean get() = active.get()

    fun <T> run(work: () -> T): T {
        trace("request")
        check(ownershipLock.tryLock()) { "A conversion already owns the camera resources" }
        try {
            check(!captureActive()) { "Stop recording before starting conversion" }
            check(active.compareAndSet(false, true)) { "A conversion already owns the camera resources" }
            try {
                dispatchMainAndWait {
                    trace("close-dispatched")
                    closeCamera()
                }
                val deadline = nanoTime() + closeTimeoutMs * 1_000_000L
                while (!cameraClosed() && nanoTime() < deadline) sleep(10L)
                check(cameraClosed()) { "Camera did not release resources for conversion" }
                trace("camera-release-acknowledged")
                trace("work-start")
                return work()
            } finally {
                trace("work-finished")
                active.set(false)
                dispatchMainAndWait {
                    trace("reopen-dispatched")
                    reopenCamera()
                }
            }
        } finally {
            ownershipLock.unlock()
        }
    }
}
