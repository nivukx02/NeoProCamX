package com.neoproc.neoproccamx.recording

import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two bounded packet slots overlap disk read/stored CRC with original-sample decode/SHA validation.
 * Optional independent decode workers own one decoded buffer per slot. Hashing stays ordered and
 * cannot observe a packet until its complete decode/source-CRC validation has succeeded.
 * A slot remains owned by the consumer until validation returns. No packet is skipped, sampled,
 * re-ordered or considered verified merely because the read completed. Closing also joins the
 * producer before any buffer or reader can be reused, including corrupt-file/cancellation paths.
 */
internal class Npv10AuditReadAhead(
    private val reader: Npv10FileReader,
    packetCapacity: Int,
    slotCount: Int = 2,
    decodedCapacity: Int = 0,
    private val preparePacket: ((Npv10StoredFrameRecord, ByteBuffer) -> Unit)? = null
) : Closeable {
    private class Slot(packetCapacity: Int, decodedCapacity: Int) {
        val storage: ByteBuffer = ByteBuffer.allocateDirect(packetCapacity)
        val decoded: ByteBuffer? = decodedCapacity.takeIf { it > 0 }?.let(ByteBuffer::allocateDirect)
        @Volatile var preparation: Future<*>? = null
    }

    private sealed interface Event {
        data class Packet(val frame: Npv10StoredFrameRecord, val slot: Slot) : Event
        data class Failure(val cause: Throwable) : Event
        data object End : Event
    }

    private val cancelled = AtomicBoolean(false)
    private val consumed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val free = ArrayBlockingQueue<Slot>(slotCount)
    private val ready = ArrayBlockingQueue<Event>(slotCount + 1)
    private val slots: List<Slot>
    private val preparationExecutor = preparePacket?.let {
        Executors.newFixedThreadPool(slotCount) { runnable ->
            Thread(runnable, "npv10-audit-decode").apply { isDaemon = true }
        }
    }
    private val producer: Thread

    internal val isReadWorkerAlive: Boolean get() = producer.isAlive
    internal val areDecodeWorkersTerminated: Boolean
        get() = preparationExecutor?.isTerminated != false

    init {
        require(packetCapacity > 0 && slotCount in 1..2)
        require(decodedCapacity >= 0 && (decodedCapacity > 0) == (preparePacket != null)) {
            "Audit decode work requires an exactly accounted caller-owned decode buffer"
        }
        slots = List(slotCount) { Slot(packetCapacity, decodedCapacity) }
        slots.forEach(free::add)
        producer = Thread({
            try {
                while (!cancelled.get()) {
                    val slot = free.take()
                    val frame = reader.readNextStoredFrameInto(slot.storage)
                    if (frame == null) {
                        ready.put(Event.End)
                        return@Thread
                    }
                    slot.preparation = preparationExecutor?.submit {
                        checkNotNull(preparePacket).invoke(frame, checkNotNull(slot.decoded))
                    }
                    ready.put(Event.Packet(frame, slot))
                }
            } catch (failure: Throwable) {
                if (!cancelled.get()) ready.offer(Event.Failure(failure))
            }
        }, "npv10-audit-read-ahead").apply { isDaemon = true }
    }

    fun forEachFrame(validate: (Npv10StoredFrameRecord) -> Unit) {
        synchronized(lifecycleLock) {
            check(consumed.compareAndSet(false, true) && !cancelled.get()) { "Audit reader is single-use" }
            producer.start()
        }
        while (true) {
            if (cancelled.get()) throw CancellationException("NPV10 audit reader closed")
            val event = ready.take()
            if (cancelled.get()) throw CancellationException("NPV10 audit reader closed")
            when (event) {
                is Event.Packet -> try {
                    try {
                        event.slot.preparation?.get()
                    } catch (failure: ExecutionException) {
                        throw failure.cause ?: failure
                    }
                    if (cancelled.get()) throw CancellationException("NPV10 audit reader closed")
                    validate(event.frame)
                } finally {
                    event.slot.preparation = null
                    if (!cancelled.get()) {
                        check(free.offer(event.slot)) { "Audit packet ownership was returned twice" }
                    }
                }
                is Event.Failure -> throw event.cause
                Event.End -> return
            }
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (!cancelled.compareAndSet(false, true)) return
            producer.interrupt()
        }
        // Closing FileChannel wakes a blocked read; interrupt wakes a blocked bounded queue.
        var closeFailure: Throwable? = null
        try { reader.close() } catch (failure: Throwable) { closeFailure = failure }
        var interrupted = Thread.interrupted()
        while (producer.isAlive) {
            try { producer.join() } catch (_: InterruptedException) { interrupted = true }
        }
        // Joining the producer first closes the submit-vs-cancel race. No slot may be cleared or
        // reused while native decode still owns its buffers, even on corruption or interruption.
        slots.forEach { it.preparation?.cancel(true) }
        preparationExecutor?.let { executor ->
            executor.shutdownNow().forEach { (it as? Future<*>)?.cancel(true) }
            while (!executor.isTerminated) {
                try { executor.awaitTermination(1L, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { interrupted = true }
            }
        }
        free.clear()
        ready.clear()
        // A different thread may be blocked in ready.take() when cancellation closes the reader.
        ready.offer(Event.Failure(CancellationException("NPV10 audit reader closed")))
        if (interrupted) Thread.currentThread().interrupt()
        closeFailure?.let { throw it }
    }
}
