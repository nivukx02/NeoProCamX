package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Milestones are additive rather than a single mutable state. Camera frames branch after buffering, so
 * preview, recording, AI and VFX can finish (or fail) independently without overwriting one another.
 */
enum class FrameLifecycleState {
    CAPTURED,
    BUFFERED,
    METADATA_ASSOCIATED,
    PREVIEW_READY,
    DISPLAYED,
    ENCODING,
    RECORDED,
    STORAGE_PENDING,
    STORED,
    AI_PENDING,
    AI_PROCESSED,
    VFX_PENDING,
    VFX_PROCESSED
}

enum class FrameDomain(val priority: Int) {
    CAPTURE(100),
    RECORDING(90),
    METADATA(80),
    ENCODER(75),
    STORAGE(70),
    RAW_PROCESSING(65),
    PREVIEW(60),
    AI(40),
    IMAGE_PROCESSING(35),
    VFX(30),
    ANALYTICS(20),
    DISPLAY(15),
    UI_MONITORING(10)
}

enum class FrameDomainOutcome {
    NOT_REQUESTED,
    QUEUED,
    RUNNING,
    COMPLETED,
    SKIPPED,
    BACKPRESSURED,
    FAILED
}

data class FrameDomainStatus(
    val outcome: FrameDomainOutcome,
    val reason: String? = null,
    val changedAtNs: Long = 0L
)

data class FrameLifecycleSnapshot(
    val milestones: Set<FrameLifecycleState>,
    val domainStatus: Map<FrameDomain, FrameDomainStatus>,
    val invalidTransitionCount: Long
) {
    fun has(state: FrameLifecycleState): Boolean = state in milestones
}

data class FrameTransitionResult(
    val accepted: Boolean,
    val reason: String
)

/** Thread-safe lifecycle ledger attached to one persistent sensor-frame record. */
class FrameLifecycleTracker {
    private val milestoneBits = AtomicLong(0L)
    private val invalidTransitions = AtomicLong(0L)
    private val domains = AtomicReferenceArray<FrameDomainStatus>(FrameDomain.entries.size).apply {
        FrameDomain.entries.forEach { domain ->
            set(domain.ordinal, FrameDomainStatus(FrameDomainOutcome.NOT_REQUESTED))
        }
    }

    fun mark(state: FrameLifecycleState): FrameTransitionResult {
        val stateBit = bit(state)
        while (true) {
            val current = milestoneBits.get()
            if (current and stateBit != 0L) {
                return FrameTransitionResult(true, "already-${state.name.lowercase()}")
            }
            val missing = prerequisites(state).firstOrNull { current and bit(it) == 0L }
            if (missing != null) {
                invalidTransitions.incrementAndGet()
                return FrameTransitionResult(
                    accepted = false,
                    reason = "${state.name.lowercase()}-requires-${missing.name.lowercase()}"
                )
            }
            if (milestoneBits.compareAndSet(current, current or stateBit)) {
                return FrameTransitionResult(true, state.name.lowercase())
            }
        }
    }

    fun transitionDomain(
        domain: FrameDomain,
        outcome: FrameDomainOutcome,
        changedAtNs: Long,
        reason: String? = null
    ): FrameTransitionResult {
        while (true) {
            val current = domains.get(domain.ordinal)
            if (current.outcome == outcome) {
                return FrameTransitionResult(true, "already-${outcome.name.lowercase()}")
            }
            if (!isValidDomainTransition(current.outcome, outcome)) {
                invalidTransitions.incrementAndGet()
                return FrameTransitionResult(
                    accepted = false,
                    reason = "${domain.name.lowercase()}:${current.outcome.name.lowercase()}-to-" +
                        outcome.name.lowercase()
                )
            }
            val updated = FrameDomainStatus(outcome, reason, changedAtNs)
            if (domains.compareAndSet(domain.ordinal, current, updated)) {
                return FrameTransitionResult(true, "${domain.name.lowercase()}-${outcome.name.lowercase()}")
            }
        }
    }

    fun snapshot(): FrameLifecycleSnapshot {
        val bits = milestoneBits.get()
        val states = FrameLifecycleState.entries.filterTo(linkedSetOf()) { bits and bit(it) != 0L }
        val domainSnapshot = LinkedHashMap<FrameDomain, FrameDomainStatus>(FrameDomain.entries.size)
        FrameDomain.entries.forEach { domain -> domainSnapshot[domain] = domains.get(domain.ordinal) }
        return FrameLifecycleSnapshot(
            milestones = states,
            domainStatus = domainSnapshot,
            invalidTransitionCount = invalidTransitions.get()
        )
    }

    fun domainStatus(domain: FrameDomain): FrameDomainStatus = domains.get(domain.ordinal)

    private fun prerequisites(state: FrameLifecycleState): Set<FrameLifecycleState> = when (state) {
        FrameLifecycleState.CAPTURED -> emptySet()
        FrameLifecycleState.BUFFERED,
        FrameLifecycleState.METADATA_ASSOCIATED -> setOf(FrameLifecycleState.CAPTURED)
        FrameLifecycleState.PREVIEW_READY,
        FrameLifecycleState.ENCODING,
        FrameLifecycleState.AI_PENDING,
        FrameLifecycleState.VFX_PENDING -> setOf(FrameLifecycleState.BUFFERED)
        FrameLifecycleState.DISPLAYED -> setOf(FrameLifecycleState.PREVIEW_READY)
        FrameLifecycleState.RECORDED -> setOf(FrameLifecycleState.ENCODING)
        // Storage also persists still photos and RAW frames, so it branches from a real buffered
        // sensor frame rather than requiring the video encoder branch.
        FrameLifecycleState.STORAGE_PENDING -> setOf(FrameLifecycleState.BUFFERED)
        FrameLifecycleState.STORED -> setOf(FrameLifecycleState.STORAGE_PENDING)
        FrameLifecycleState.AI_PROCESSED -> setOf(FrameLifecycleState.AI_PENDING)
        FrameLifecycleState.VFX_PROCESSED -> setOf(FrameLifecycleState.VFX_PENDING)
    }

    private fun isValidDomainTransition(
        current: FrameDomainOutcome,
        next: FrameDomainOutcome
    ): Boolean = when (current) {
        FrameDomainOutcome.NOT_REQUESTED -> next == FrameDomainOutcome.QUEUED ||
            next == FrameDomainOutcome.RUNNING ||
            next == FrameDomainOutcome.SKIPPED ||
            next == FrameDomainOutcome.BACKPRESSURED ||
            next == FrameDomainOutcome.FAILED
        FrameDomainOutcome.QUEUED -> next == FrameDomainOutcome.RUNNING ||
            next == FrameDomainOutcome.SKIPPED ||
            next == FrameDomainOutcome.BACKPRESSURED ||
            next == FrameDomainOutcome.FAILED
        FrameDomainOutcome.RUNNING -> next == FrameDomainOutcome.COMPLETED ||
            next == FrameDomainOutcome.SKIPPED ||
            next == FrameDomainOutcome.FAILED
        FrameDomainOutcome.BACKPRESSURED -> next == FrameDomainOutcome.QUEUED ||
            next == FrameDomainOutcome.RUNNING ||
            next == FrameDomainOutcome.SKIPPED ||
            next == FrameDomainOutcome.FAILED
        FrameDomainOutcome.COMPLETED,
        FrameDomainOutcome.SKIPPED,
        FrameDomainOutcome.FAILED -> false
    }

    private fun bit(state: FrameLifecycleState): Long = 1L shl state.ordinal
}
