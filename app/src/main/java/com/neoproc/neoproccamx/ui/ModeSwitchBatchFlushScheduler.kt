package com.neoproc.neoproccamx.ui

/**
 * Lets the selected mode render once before the coalesced Camera2/preview transaction is flushed.
 * [flushNow] is intentionally lossless: lifecycle changes and a following switch close the pending
 * transaction synchronously instead of cancelling it and leaving either batch depth open.
 */
internal class ModeSwitchBatchFlushScheduler(
    private val framePoster: (Runnable) -> Unit
) {
    private var generation = 0L
    private var pending: Runnable? = null

    fun schedule(work: Runnable) {
        flushNow()
        pending = work
        val expectedGeneration = ++generation
        framePoster(
            Runnable {
                if (expectedGeneration != generation) return@Runnable
                val scheduled = pending ?: return@Runnable
                pending = null
                scheduled.run()
            }
        )
    }

    fun flushNow() {
        generation += 1L
        val scheduled = pending ?: return
        pending = null
        scheduled.run()
    }
}
