package com.neoproc.neoproccamx.recording

import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * runInOrderTwoStage — a strict-in-order, memory-bounded, exception-safe 2-stage pipeline.
 *
 * [stage1] is called repeatedly on ONE dedicated background thread to produce items until it returns null
 * (end of stream); each produced item is handed to [stage2] on the CALLING thread, in FIFO (production)
 * order. The hand-off uses a [SynchronousQueue] — a rendezvous with NO buffering — so at most the two items
 * the overlap needs are ever live at once (one being produced by [stage1], one being consumed by [stage2]).
 * That is the minimum memory footprint for a producer/consumer overlap: stage1 can build item N+1 while
 * stage2 consumes item N, but it cannot run further ahead and accumulate a backlog of large buffers.
 *
 * This is the scheduler behind the offline RAW→NeoLog single-worker CPU+GPU overlap
 * ([RawToNeoLogMovConverter]): stage1 = GPU demosaic (or per-frame CPU fallback), stage2 = CPU NeoLog
 * finish + 10-bit pack + sink. Keeping it a small pure generic helper makes the concurrency (ordering,
 * backpressure, cancellation, error propagation) unit-testable on the JVM without a GPU.
 *
 * End-of-stream / cancellation is signalled with flags (NOT a blocking sentinel put), and both stages use a
 * timed [SynchronousQueue.offer]/[SynchronousQueue.poll] that re-checks those flags, so neither thread can
 * park forever: a consumer failure unblocks a producer waiting to hand off, and a finished producer lets the
 * consumer notice completion. The background thread is ALWAYS joined before this function returns.
 *
 * Guarantees:
 *  - IN ORDER: single producer + single consumer + rendezvous hand-off ⇒ [stage2] sees items in exactly
 *    the order [stage1] produced them.
 *  - NO DEADLOCK: if [stage2] throws, the producer is cancelled + joined, then the [stage2] exception is
 *    rethrown. If [stage1] throws, the consumer drains cleanly and the [stage1] exception is rethrown.
 *  - BOUNDED: never more than two in-flight items (SynchronousQueue capacity 0 + one in each stage).
 */
internal fun <T : Any> runInOrderTwoStage(stage1: () -> T?, stage2: (T) -> Unit) {
    val handoff = SynchronousQueue<T>()
    val producerError = AtomicReference<Throwable?>(null)
    val finished = AtomicBoolean(false)   // producer will produce nothing more (normal end OR failure)
    val cancelled = AtomicBoolean(false)  // consumer aborted; producer should stop and discard

    val producer = Thread({
        try {
            while (!cancelled.get()) {
                val item = stage1() ?: break
                // Hand off; re-check cancellation on each timed attempt so a consumer failure unblocks us
                // (rather than parking forever on the rendezvous). Loops only while a consumer is busy.
                var delivered = false
                while (!cancelled.get()) {
                    if (handoff.offer(item, POLL_MS, TimeUnit.MILLISECONDS)) {
                        delivered = true
                        break
                    }
                }
                if (!delivered) break // cancelled while trying to hand off
            }
        } catch (_: InterruptedException) {
            // Treated as cancellation — the consumer owns the failure that triggered the interrupt.
        } catch (t: Throwable) {
            producerError.set(t)
        } finally {
            finished.set(true)
        }
    }, "raw-develop-gpu").apply { isDaemon = true }

    producer.start()
    var consumerError: Throwable? = null
    try {
        while (true) {
            val item = handoff.poll(POLL_MS, TimeUnit.MILLISECONDS)
            if (item != null) {
                stage2(item)
            } else if (finished.get()) {
                // Producer is done; drain any final item that raced with the flag, then stop.
                var last = handoff.poll()
                while (last != null) {
                    stage2(last)
                    last = handoff.poll()
                }
                break
            }
        }
    } catch (t: Throwable) {
        consumerError = t
    } finally {
        // Unblock a producer parked mid hand-off, then join so no thread outlives this call.
        cancelled.set(true)
        producer.interrupt()
        producer.join()
    }

    // Consumer failure takes precedence (it aborted the pipeline); otherwise surface a producer failure.
    consumerError?.let { throw it }
    producerError.get()?.let { throw it }
}

/** Flag re-check cadence for the timed hand-off; small so shutdown is snappy, but not a busy-spin. */
private const val POLL_MS = 25L
