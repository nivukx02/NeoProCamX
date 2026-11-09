package com.neoproc.neoproccamx.recording

import java.util.concurrent.atomic.AtomicReference

/** Atomic ownership state joining producer shutdown, lifecycle teardown, and publication. */
internal class Npv10ProducerBoundaryGate {
    internal enum class State {
        IDLE,
        OPEN,
        ESTABLISHED,
        POISONED,
        SEALED_ESTABLISHED,
        SEALED_POISONED
    }

    private val stateRef = AtomicReference(State.IDLE)
    private val poisonFailureRef = AtomicReference<Throwable?>(null)

    val state: State
        get() = stateRef.get()

    val poisonFailure: Throwable?
        get() = poisonFailureRef.get()

    fun open() {
        stateRef.set(State.OPEN)
    }

    /** Exact cleanup establishes the boundary only if lifecycle poison did not win first. */
    fun establish(): Boolean {
        while (true) {
            when (val current = stateRef.get()) {
                State.OPEN -> if (stateRef.compareAndSet(current, State.ESTABLISHED)) return true
                State.ESTABLISHED, State.SEALED_ESTABLISHED -> return true
                State.IDLE, State.POISONED, State.SEALED_POISONED -> return false
            }
        }
    }

    /** Lifecycle teardown may invalidate only a producer whose exact boundary is still open. */
    fun poisonIfOpen(): Boolean = stateRef.compareAndSet(State.OPEN, State.POISONED)

    /** Explicit boundary/ingress failures remain authoritative until the publication seal. */
    fun poison(): Boolean {
        while (true) {
            when (val current = stateRef.get()) {
                State.OPEN,
                State.ESTABLISHED -> if (stateRef.compareAndSet(current, State.POISONED)) return true
                State.POISONED, State.SEALED_POISONED -> return true
                State.IDLE, State.SEALED_ESTABLISHED -> return false
            }
        }
    }

    fun recordPoisonFailure(failure: Throwable): Boolean =
        poisonFailureRef.compareAndSet(null, failure)

    /**
     * Freezes the boundary decision before writer ownership is released. An unexpectedly open
     * producer seals poisoned, so no later state observation can turn an unknown tail into verified.
     */
    fun sealForPublication(): Boolean {
        while (true) {
            when (val current = stateRef.get()) {
                State.ESTABLISHED ->
                    if (stateRef.compareAndSet(current, State.SEALED_ESTABLISHED)) return true
                State.OPEN ->
                    if (stateRef.compareAndSet(current, State.SEALED_POISONED)) return false
                State.POISONED ->
                    if (stateRef.compareAndSet(current, State.SEALED_POISONED)) return false
                State.SEALED_ESTABLISHED -> return true
                State.IDLE, State.SEALED_POISONED -> return false
            }
        }
    }

    fun close() {
        stateRef.set(State.IDLE)
    }
}
