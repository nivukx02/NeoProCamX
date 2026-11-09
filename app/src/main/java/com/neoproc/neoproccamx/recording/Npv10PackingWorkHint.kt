package com.neoproc.neoproccamx.recording

/**
 * One advisory Android performance session per persistent packing worker. The row helpers have
 * their own session; they do not account for the submitter's subsequent lossless/CRC work.
 * This reports real work without changing a frame deadline, codec, sample or thermal restriction.
 */
internal class Npv10PackingWorkHint(
    private var backend: Backend?,
    private val reportFailure: (String, RuntimeException) -> Unit = { _, _ -> }
) : AutoCloseable {
    interface Backend : AutoCloseable {
        fun updateTarget(durationNs: Long)
        fun reportActual(durationNs: Long)
    }

    private val owner = Thread.currentThread()
    private var selectedFps = DEFAULT_FPS
    private var closed = false
    var reportsAccepted = 0L
        private set
    var backendFailed = false
        private set

    val active: Boolean get() = !closed && backend != null

    fun prepareFrame(fps: Int) {
        checkOwner()
        val target = targetDurationNs(fps)
        check(!closed) { "Packing work hint already closed" }
        if (selectedFps == fps) return
        selectedFps = fps
        invokeBackend("update-target") { it.updateTarget(target) }
    }

    fun reportActual(durationNs: Long) {
        checkOwner()
        require(durationNs > 0L) { "Actual packing duration must be positive" }
        check(!closed) { "Packing work hint already closed" }
        invokeBackend("report-work") {
            it.reportActual(durationNs)
            reportsAccepted++
        }
    }

    override fun close() {
        checkOwner()
        if (closed) return
        closed = true
        val previous = backend
        backend = null
        try {
            previous?.close()
        } catch (failure: RuntimeException) {
            backendFailed = true
            reportFailure("close", failure)
        }
    }

    private inline fun invokeBackend(operation: String, action: (Backend) -> Unit) {
        val current = backend ?: return
        try {
            action(current)
        } catch (failure: RuntimeException) {
            // ADPF is advisory. A vendor rejection must not lose a captured image or corrupt a
            // frame. Retire the exact rejected session once, preserve diagnostics, and continue
            // the unchanged recording algorithm under the Android scheduler's authority.
            backend = null
            backendFailed = true
            try {
                current.close()
            } catch (closeFailure: RuntimeException) {
                // A vendor may cache and rethrow the same exception from report/update and close.
                // Throwable rejects self-suppression; optional hint retirement must not introduce
                // a second exception that aborts the captured frame's cleanup path.
                if (closeFailure !== failure) {
                    failure.addSuppressed(closeFailure)
                }
            }
            reportFailure(operation, failure)
        }
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "Packing work hint used by another worker" }
    }

    companion object {
        const val DEFAULT_FPS = 25

        fun targetDurationNs(fps: Int): Long {
            require(fps in 1..1_000) { "Invalid packing FPS: $fps" }
            // Reserve half of the selected frame period for packing/lossless work, leaving
            // scheduling headroom for capture, preview, ordered publication and storage. This
            // is a requested CPU-work budget, not the unchanged end-to-end 40 ms test threshold.
            return 1_000_000_000L / fps / 2L
        }
    }
}
