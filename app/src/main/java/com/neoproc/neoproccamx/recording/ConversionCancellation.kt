package com.neoproc.neoproccamx.recording

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Cooperative cancellation authority for one user-visible offline conversion.
 *
 * Cancellation never publishes a partial result. Conversion loops and transactional publication
 * boundaries call [throwIfCancellationRequested], unwind through their existing `finally` blocks,
 * and delete every uncommitted artifact while preserving the immutable source master.
 */
class ConversionCancellationSignal {
    private val cancelled = AtomicBoolean(false)
    private val reason = AtomicReference(DEFAULT_REASON)

    val isCancellationRequested: Boolean
        get() = cancelled.get()

    /** Returns true only for the request that changed this signal into the cancelled state. */
    fun cancel(message: String = DEFAULT_REASON): Boolean {
        val normalized = message.trim().ifEmpty { DEFAULT_REASON }
        reason.compareAndSet(DEFAULT_REASON, normalized)
        return cancelled.compareAndSet(false, true)
    }

    fun throwIfCancellationRequested() {
        if (cancelled.get()) throw CancellationException(reason.get())
    }

    fun shouldContinue(): Boolean = !cancelled.get()

    private companion object {
        const val DEFAULT_REASON = "Conversion cancelled by user"
    }
}

/**
 * Carries the signal across the conversion coordinator's worker boundary without changing pixel APIs.
 * Per-frame callbacks still capture the signal explicitly so parallel worker lanes observe cancellation.
 */
internal object ConversionCancellationContext {
    private val local = ThreadLocal<ConversionCancellationSignal?>()

    fun <T> runWith(signal: ConversionCancellationSignal, operation: () -> T): T {
        val previous = local.get()
        local.set(signal)
        return try {
            signal.throwIfCancellationRequested()
            operation().also { signal.throwIfCancellationRequested() }
        } finally {
            if (previous == null) local.remove() else local.set(previous)
        }
    }

    fun throwIfCancellationRequested() {
        local.get()?.throwIfCancellationRequested()
    }

    fun shouldContinue(): Boolean = local.get()?.shouldContinue() ?: true

    /** The active signal, or an isolated never-cancelled signal for legacy direct API callers. */
    fun currentSignal(): ConversionCancellationSignal = local.get() ?: ConversionCancellationSignal()
}
