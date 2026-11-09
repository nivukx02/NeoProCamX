package com.neoproc.neoproccamx.ui

internal data class PreviewPresentationFpsDecision(
    val accepted: Boolean,
    val resetBeforePresentation: Boolean
)

/**
 * Starts every FPS population at the first accepted frame of its exact Camera2 generation and
 * visible-surface epoch. Mode-switch/rebuild latency remains measured by its dedicated timeline;
 * retired-session frames and rebuild gaps therefore cannot contaminate steady-state display FPS.
 */
internal class PreviewPresentationFpsEpochGate {
    private data class Identity(val sessionGeneration: Long, val surfaceEpoch: Long)

    private var acceptedIdentity: Identity? = null
    private var lastAcceptedSequence = 0L
    private var lastAcceptedPresentationTimestampNs = 0L

    @Synchronized
    fun evaluate(
        event: PreviewDisplayPresentEvent,
        snapshot: PreviewDisplayPresentSnapshot
    ): PreviewPresentationFpsDecision = evaluateLocked(event, snapshot)

    /** Keeps admission and all caller-owned publication side effects in one ordered transaction. */
    @Synchronized
    fun publishIfAccepted(
        event: PreviewDisplayPresentEvent,
        snapshot: () -> PreviewDisplayPresentSnapshot,
        publish: (PreviewPresentationFpsDecision) -> Unit
    ): Boolean {
        val decision = evaluateLocked(event, snapshot())
        if (!decision.accepted) return false
        publish(decision)
        return true
    }

    /** Serializes a generation reset with any accepted publication already in flight. */
    @Synchronized
    fun <T> runExclusive(block: () -> T): T = block()

    private fun evaluateLocked(
        event: PreviewDisplayPresentEvent,
        snapshot: PreviewDisplayPresentSnapshot
    ): PreviewPresentationFpsDecision {
        val identity = Identity(event.sessionGeneration, event.surfaceEpoch)
        val sameIdentity = acceptedIdentity == identity
        if (!event.isCurrent(snapshot) ||
            event.sequence <= lastAcceptedSequence ||
            (sameIdentity && event.presentationTimestampNs <= lastAcceptedPresentationTimestampNs)
        ) {
            return PreviewPresentationFpsDecision(
                accepted = false,
                resetBeforePresentation = false
            )
        }
        val changed = !sameIdentity
        acceptedIdentity = identity
        lastAcceptedSequence = event.sequence
        lastAcceptedPresentationTimestampNs = event.presentationTimestampNs
        return PreviewPresentationFpsDecision(
            accepted = true,
            resetBeforePresentation = changed
        )
    }
}
