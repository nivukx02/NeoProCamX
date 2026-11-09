package com.neoproc.neoproccamx.pipeline

import android.os.Process
import android.util.Log
import java.util.ArrayDeque
import java.util.EnumMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class DomainBackpressureStrategy {
    /** Never evict an accepted frame. A full queue reports backpressure and leaves input ownership upstream. */
    PROTECTED,
    /** Evict queued history and process the newest frame, suitable for monitoring preview. */
    LATEST,
    /** Latest-frame behavior plus externally controlled cadence/active queue limits. */
    ADAPTIVE_LATEST
}

data class FrameDomainQueueConfig(
    val domain: FrameDomain,
    val minimumCapacity: Int,
    val maximumCapacity: Int,
    val initialActiveLimit: Int,
    val maxFrameAgeNs: Long,
    val backpressure: DomainBackpressureStrategy,
    val preferLatestFrame: Boolean
) {
    init {
        require(minimumCapacity >= 1)
        require(maximumCapacity >= minimumCapacity)
        require(initialActiveLimit in minimumCapacity..maximumCapacity)
        require(maxFrameAgeNs >= 0L)
        if (domain == FrameDomain.RECORDING || domain == FrameDomain.ENCODER) {
            require(backpressure == DomainBackpressureStrategy.PROTECTED) {
                "Recording and encoder queues must never conflate accepted frames"
            }
            require(!preferLatestFrame) {
                "Recording and encoder queues must preserve sensor order"
            }
        }
    }
}

data class FrameDomainQueueTelemetry(
    val domain: FrameDomain,
    val activeLimit: Int,
    val maximumCapacity: Int,
    val queueDepth: Int,
    val accepted: Long,
    val backpressured: Long,
    val evicted: Long,
    val staleSkipped: Long
)

data class FrameDomainOfferResult(
    val accepted: Boolean,
    val ownershipTransferred: Boolean,
    val reason: String
)

class SharedFrameResource<T : Any>(
    private val resource: T,
    private val releaseResource: (T) -> Unit
) : AutoCloseable {
    private val referenceCount = AtomicInteger(1)
    private val released = AtomicBoolean(false)
    private val ownerClosed = AtomicBoolean(false)

    fun retain(): FrameResourceLease<T>? {
        while (true) {
            val current = referenceCount.get()
            if (current <= 0) return null
            if (referenceCount.compareAndSet(current, current + 1)) {
                return FrameResourceLease(this)
            }
        }
    }

    internal fun value(): T {
        check(referenceCount.get() > 0 && !released.get()) { "Frame resource accessed after release" }
        return resource
    }

    internal fun releaseReference() {
        val remaining = referenceCount.decrementAndGet()
        check(remaining >= 0) { "Frame resource reference underflow" }
        if (remaining == 0 && released.compareAndSet(false, true)) {
            releaseResource(resource)
        }
    }

    override fun close() {
        if (ownerClosed.compareAndSet(false, true)) releaseReference()
    }
}

class FrameResourceLease<T : Any> internal constructor(
    private val owner: SharedFrameResource<T>
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val value: T
        get() {
            check(!closed.get()) { "Frame lease accessed after close" }
            return owner.value()
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) owner.releaseReference()
    }
}

class FrameDomainWorkItem<T : Any>(
    val record: SensorFrameRecord,
    private val resource: FrameResourceLease<T>,
    val enqueuedAtNs: Long,
    val deadlineNs: Long
) : AutoCloseable {
    /**
     * The payload is deliberately exposed through this owning object rather than exposing the lease.
     * [FrameDomainWorkItem] is a regular final class (not a data class), so Kotlin cannot synthesize a
     * `copy()` that aliases one closeable lease into two independently-owned work items.
     */
    val payload: T
        get() = resource.value

    override fun close() = resource.close()
}

/** Bounded low-contention queue with domain-specific ownership and stale-frame semantics. */
internal class BoundedFrameDomainQueue<T : Any>(
    private val config: FrameDomainQueueConfig,
    private val monotonicClockNs: () -> Long,
    private val onSkipped: (FrameDomainWorkItem<T>, Boolean) -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    private val lock = ReentrantLock()
    private val frames = ArrayDeque<FrameDomainWorkItem<T>>(config.maximumCapacity)
    private val activeLimit = AtomicInteger(config.initialActiveLimit)
    private val queueDepth = AtomicInteger(0)
    private val accepted = AtomicLong(0L)
    private val backpressured = AtomicLong(0L)
    private val evicted = AtomicLong(0L)
    private val staleSkipped = AtomicLong(0L)

    fun offer(item: FrameDomainWorkItem<T>): FrameDomainOfferResult {
        // Producers include Camera2 callbacks. They may report backpressure, but must never wait for a
        // consumer that is briefly polling or resizing this secondary-domain queue.
        if (!lock.tryLock()) {
            backpressured.incrementAndGet()
            return FrameDomainOfferResult(false, false, "queue-contended")
        }
        var skipped: FrameDomainWorkItem<T>? = null
        val result = try {
            val limit = activeLimit.get()
            if (frames.size < limit) {
                frames.addLast(item)
                queueDepth.set(frames.size)
                accepted.incrementAndGet()
                FrameDomainOfferResult(true, true, "accepted")
            } else if (config.backpressure == DomainBackpressureStrategy.PROTECTED) {
                backpressured.incrementAndGet()
                FrameDomainOfferResult(false, false, "protected-queue-full")
            } else {
                skipped = if (frames.isEmpty()) null else frames.removeFirst()
                if (skipped != null) evicted.incrementAndGet()
                frames.addLast(item)
                queueDepth.set(frames.size)
                accepted.incrementAndGet()
                FrameDomainOfferResult(true, true, "accepted-after-latest-eviction")
            }
        } finally {
            lock.unlock()
        }
        skipped?.let { cleanupSkipped(it, stale = false) }
        return result
    }

    fun poll(): FrameDomainWorkItem<T>? {
        val nowNs = monotonicClockNs()
        val skipped = ArrayList<SkippedFrame<T>>()
        val selected = lock.withLock {
            if (config.preferLatestFrame && frames.size > 1) {
                val latest = frames.removeLast()
                while (frames.isNotEmpty()) {
                    skipped += SkippedFrame(frames.removeFirst(), stale = false)
                    evicted.incrementAndGet()
                }
                if (isStale(latest, nowNs)) {
                    staleSkipped.incrementAndGet()
                    skipped += SkippedFrame(latest, stale = true)
                    null
                } else {
                    latest
                }
            } else {
                var nextForConsumer: FrameDomainWorkItem<T>? = null
                while (frames.isNotEmpty() && nextForConsumer == null) {
                    val next = frames.removeFirst()
                    if (!isStale(next, nowNs) ||
                        config.backpressure == DomainBackpressureStrategy.PROTECTED
                    ) {
                        nextForConsumer = next
                    } else {
                        staleSkipped.incrementAndGet()
                        skipped += SkippedFrame(next, stale = true)
                    }
                }
                nextForConsumer
            }
                .also { queueDepth.set(frames.size) }
        }
        skipped.forEach { cleanupSkipped(it.item, it.stale) }
        return selected
    }

    fun setActiveLimit(limit: Int) {
        val resolved = limit.coerceIn(config.minimumCapacity, config.maximumCapacity)
        val skipped = ArrayList<FrameDomainWorkItem<T>>()
        lock.withLock {
            activeLimit.set(resolved)
            if (config.backpressure != DomainBackpressureStrategy.PROTECTED) {
                while (frames.size > resolved) {
                    skipped += frames.removeFirst()
                    evicted.incrementAndGet()
                }
            }
            queueDepth.set(frames.size)
        }
        skipped.forEach { cleanupSkipped(it, stale = false) }
    }

    fun depth(): Int = queueDepth.get()

    fun telemetry(): FrameDomainQueueTelemetry = FrameDomainQueueTelemetry(
        domain = config.domain,
        activeLimit = activeLimit.get(),
        maximumCapacity = config.maximumCapacity,
        queueDepth = depth(),
        accepted = accepted.get(),
        backpressured = backpressured.get(),
        evicted = evicted.get(),
        staleSkipped = staleSkipped.get()
    )

    fun clear() {
        val pending = ArrayList<FrameDomainWorkItem<T>>()
        lock.withLock {
            while (frames.isNotEmpty()) pending += frames.removeFirst()
            queueDepth.set(0)
        }
        pending.forEach(::closeSafely)
    }

    private fun isStale(item: FrameDomainWorkItem<T>, nowNs: Long): Boolean =
        config.maxFrameAgeNs > 0L &&
            nowNs > item.enqueuedAtNs &&
            nowNs - item.enqueuedAtNs > config.maxFrameAgeNs

    private fun cleanupSkipped(item: FrameDomainWorkItem<T>, stale: Boolean) {
        try {
            onSkipped(item, stale)
        } catch (throwable: Throwable) {
            reportFailure(throwable)
        }
        closeSafely(item)
    }

    private fun closeSafely(item: FrameDomainWorkItem<T>) {
        try {
            item.close()
        } catch (throwable: Throwable) {
            reportFailure(throwable)
        }
    }

    private fun reportFailure(throwable: Throwable) {
        onFailure(throwable)
        if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
    }

    private data class SkippedFrame<T : Any>(
        val item: FrameDomainWorkItem<T>,
        val stale: Boolean
    )
}

fun interface FrameDomainConsumer<T : Any> {
    fun consume(item: FrameDomainWorkItem<T>)
}

/** One bounded queue and one serial worker per processing domain. Failures never escape into peers. */
class BoundedFrameDomainWorker<T : Any>(
    private val config: FrameDomainQueueConfig,
    private val diagnostics: PipelineDeadlineMonitor,
    private val consumer: FrameDomainConsumer<T>,
    private val monotonicClockNs: () -> Long,
    threadFactory: ThreadFactory = FrameDomainThreadFactory(config.domain)
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val drainScheduled = AtomicBoolean(false)
    private val lifecycleLock = ReentrantLock()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(threadFactory)
    private val queue = BoundedFrameDomainQueue<T>(
        config = config,
        monotonicClockNs = monotonicClockNs,
        onSkipped = { item, stale ->
            item.record.lifecycle.transitionDomain(
                config.domain,
                FrameDomainOutcome.SKIPPED,
                monotonicClockNs(),
                if (stale) "stale-frame" else "latest-frame-preference"
            )
            if (config.domain == FrameDomain.PREVIEW) diagnostics.recordPreviewSkipped(stale)
        },
        onFailure = { throwable ->
            diagnostics.recordDomainFailure(config.domain)
            Log.e(
                "FrameDomain-${config.domain.name}",
                "Frame queue cleanup failed independently",
                throwable
            )
        }
    )

    fun offer(item: FrameDomainWorkItem<T>): FrameDomainOfferResult {
        // Capture/renderer producers must not wait behind teardown or queue-limit lifecycle work.
        if (!lifecycleLock.tryLock()) {
            return FrameDomainOfferResult(false, false, "worker-lifecycle-contended")
        }
        return try {
            if (closed.get()) return FrameDomainOfferResult(false, false, "worker-closed")
            val result = queue.offer(item)
            diagnostics.setQueueDepth(config.domain.pipelineStage(), queue.depth())
            if (!result.accepted) {
                item.record.lifecycle.transitionDomain(
                    config.domain,
                    FrameDomainOutcome.BACKPRESSURED,
                    monotonicClockNs(),
                    result.reason
                )
                return result
            }
            item.record.lifecycle.transitionDomain(
                config.domain,
                FrameDomainOutcome.QUEUED,
                item.enqueuedAtNs
            )
            markPendingMilestone(item.record)
            scheduleDrain()
            result
        } finally {
            lifecycleLock.unlock()
        }
    }

    fun setActiveQueueLimit(limit: Int) {
        queue.setActiveLimit(limit)
        diagnostics.setQueueDepth(config.domain.pipelineStage(), queue.depth())
    }

    fun telemetry(): FrameDomainQueueTelemetry = queue.telemetry()

    override fun close() {
        lifecycleLock.withLock {
            if (!closed.compareAndSet(false, true)) return
            executor.shutdownNow()
            queue.clear()
            diagnostics.setQueueDepth(config.domain.pipelineStage(), 0)
        }
    }

    private fun scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    drain()
                } finally {
                    drainScheduled.set(false)
                    if (!closed.get() && queue.depth() > 0) scheduleDrain()
                }
            }
        } catch (_: RejectedExecutionException) {
            drainScheduled.set(false)
            if (!closed.get()) diagnostics.recordDomainFailure(config.domain)
        }
    }

    private fun drain() {
        while (!closed.get()) {
            val item = queue.poll() ?: return
            diagnostics.setQueueDepth(config.domain.pipelineStage(), queue.depth())
            val startedAtNs = monotonicClockNs()
            item.record.lifecycle.transitionDomain(
                config.domain,
                FrameDomainOutcome.RUNNING,
                startedAtNs
            )
            val token = diagnostics.beginStage(
                stage = config.domain.pipelineStage(),
                identity = item.record.identity.snapshot(),
                queueEnteredAtNs = item.enqueuedAtNs,
                deadlineNs = item.deadlineNs,
                queueDepth = queue.depth(),
                startedAtNs = startedAtNs
            )
            var success = false
            try {
                consumer.consume(item)
                success = true
                item.record.lifecycle.transitionDomain(
                    config.domain,
                    FrameDomainOutcome.COMPLETED,
                    monotonicClockNs()
                )
                markCompletedMilestone(item.record)
            } catch (throwable: Throwable) {
                item.record.lifecycle.transitionDomain(
                    config.domain,
                    FrameDomainOutcome.FAILED,
                    monotonicClockNs(),
                    throwable.javaClass.simpleName
                )
                diagnostics.recordDomainFailure(config.domain)
                if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
            } finally {
                diagnostics.finishStage(token, success, monotonicClockNs())
                item.close()
            }
        }
    }

    private fun markPendingMilestone(record: SensorFrameRecord) {
        when (config.domain) {
            FrameDomain.ENCODER,
            FrameDomain.RECORDING -> record.lifecycle.mark(FrameLifecycleState.ENCODING)
            FrameDomain.STORAGE -> record.lifecycle.mark(FrameLifecycleState.STORAGE_PENDING)
            FrameDomain.AI -> record.lifecycle.mark(FrameLifecycleState.AI_PENDING)
            FrameDomain.VFX -> record.lifecycle.mark(FrameLifecycleState.VFX_PENDING)
            else -> Unit
        }
    }

    private fun markCompletedMilestone(record: SensorFrameRecord) {
        when (config.domain) {
            FrameDomain.PREVIEW -> {
                record.lifecycle.mark(FrameLifecycleState.PREVIEW_READY)
                diagnostics.recordPreviewReady()
            }
            FrameDomain.RECORDING -> {
                record.lifecycle.mark(FrameLifecycleState.RECORDED)
                diagnostics.recordRecordingCompleted(record.identity.snapshot())
            }
            FrameDomain.STORAGE -> record.lifecycle.mark(FrameLifecycleState.STORED)
            FrameDomain.AI -> record.lifecycle.mark(FrameLifecycleState.AI_PROCESSED)
            FrameDomain.VFX -> record.lifecycle.mark(FrameLifecycleState.VFX_PROCESSED)
            FrameDomain.DISPLAY -> {
                record.lifecycle.mark(FrameLifecycleState.DISPLAYED)
                diagnostics.recordDisplayed()
            }
            else -> Unit
        }
    }
}

enum class FramePayloadAccessMode {
    SINGLE_CONSUMER,
    CONCURRENT_READ_ONLY
}

/** Shares one safely reference-counted payload without copying full-resolution frame data. */
class AsynchronousFrameDistributor<T : Any>(
    workers: Collection<BoundedFrameDomainWorker<T>>
) : AutoCloseable {
    private val workersByDomain = EnumMap<FrameDomain, BoundedFrameDomainWorker<T>>(FrameDomain::class.java)

    init {
        workers.forEach { worker ->
            val domain = worker.telemetry().domain
            require(workersByDomain.put(domain, worker) == null) { "Duplicate frame domain $domain" }
        }
    }

    fun distribute(
        record: SensorFrameRecord,
        payload: T,
        releasePayload: (T) -> Unit,
        domains: Set<FrameDomain>,
        accessMode: FramePayloadAccessMode,
        enqueuedAtNs: Long,
        deadlineForDomain: (FrameDomain) -> Long
    ): Map<FrameDomain, FrameDomainOfferResult> {
        if (accessMode == FramePayloadAccessMode.SINGLE_CONSUMER) {
            require(domains.size <= 1) { "Single-consumer payload cannot be shared across domains" }
        }
        val shared = SharedFrameResource(payload, releasePayload)
        val results = LinkedHashMap<FrameDomain, FrameDomainOfferResult>(domains.size)
        try {
            domains.sortedByDescending(FrameDomain::priority).forEach { domain ->
                val worker = workersByDomain[domain]
                if (worker == null) {
                    results[domain] = FrameDomainOfferResult(false, false, "domain-not-registered")
                    return@forEach
                }
                val lease = shared.retain()
                if (lease == null) {
                    results[domain] = FrameDomainOfferResult(false, false, "payload-released")
                    return@forEach
                }
                val item = FrameDomainWorkItem(
                    record = record,
                    resource = lease,
                    enqueuedAtNs = enqueuedAtNs,
                    deadlineNs = deadlineForDomain(domain)
                )
                val result = worker.offer(item)
                if (!result.ownershipTransferred) item.close()
                results[domain] = result
            }
        } finally {
            shared.close()
        }
        return results
    }

    override fun close() {
        workersByDomain.values.forEach(BoundedFrameDomainWorker<T>::close)
        workersByDomain.clear()
    }
}

internal class FrameDomainThreadFactory(
    private val domain: FrameDomain
) : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread = Thread({
        runCatching {
            Process.setThreadPriority(
                when (domain) {
                    FrameDomain.CAPTURE,
                    FrameDomain.RECORDING,
                    FrameDomain.ENCODER -> Process.THREAD_PRIORITY_URGENT_DISPLAY
                    FrameDomain.METADATA,
                    FrameDomain.STORAGE,
                    FrameDomain.RAW_PROCESSING,
                    FrameDomain.PREVIEW -> Process.THREAD_PRIORITY_DISPLAY
                    else -> Process.THREAD_PRIORITY_BACKGROUND
                }
            )
        }
        runnable.run()
    }, "FrameDomain-${domain.name}").apply { isDaemon = true }
}

internal fun FrameDomain.pipelineStage(): PipelineStage = when (this) {
    FrameDomain.CAPTURE -> PipelineStage.SENSOR_CAPTURE
    FrameDomain.RECORDING -> PipelineStage.RECORDING
    FrameDomain.METADATA -> PipelineStage.METADATA
    FrameDomain.ENCODER -> PipelineStage.ENCODER
    FrameDomain.STORAGE -> PipelineStage.STORAGE
    FrameDomain.RAW_PROCESSING -> PipelineStage.RAW_PROCESSING
    FrameDomain.PREVIEW -> PipelineStage.PREVIEW
    FrameDomain.AI -> PipelineStage.AI
    FrameDomain.IMAGE_PROCESSING,
    FrameDomain.ANALYTICS,
    FrameDomain.UI_MONITORING -> PipelineStage.IMAGE_PROCESSING
    FrameDomain.VFX -> PipelineStage.VFX
    FrameDomain.DISPLAY -> PipelineStage.DISPLAY
}
