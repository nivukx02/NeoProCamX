package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.pipeline.SynchronousImageIngressGate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

/** The four independently-owned ImageReader callback domains in [CameraController]. */
internal enum class ReaderKind {
    JPEG,
    RAW,
    YUV,
    HARDWARE_IMPORT
}

/**
 * Immutable listener identity captured by one ImageReader callback registration.
 *
 * [reader] uses reference identity, not equality. [epoch] distinguishes listener registrations when
 * Camera2 reuses the same ImageReader across a session rebuild. Retirement is monotonic so a queued
 * callback can never become current again.
 */
internal class ReaderBinding<T : Any> internal constructor(
    val kind: ReaderKind,
    val reader: T,
    val epoch: Long
) {
    private val retiredState = AtomicBoolean(false)

    val retired: Boolean
        get() = retiredState.get()

    internal fun retire() {
        retiredState.set(true)
    }
}

/** Lock-free, identity-based authority for the controller's current reader listeners. */
internal class ReaderBindingRegistry<T : Any> {
    private val nextEpoch = AtomicLong(0L)
    private val current = AtomicReferenceArray<ReaderBinding<T>?>(ReaderKind.entries.size)

    /** Installs a new epoch and permanently retires the listener registration it replaces. */
    fun bind(kind: ReaderKind, reader: T): ReaderBinding<T> {
        val epoch = nextEpoch.incrementAndGet()
        check(epoch > 0L) { "ImageReader binding epoch exhausted" }
        val replacement = ReaderBinding(kind = kind, reader = reader, epoch = epoch)
        current.getAndSet(kind.ordinal, replacement)?.retire()
        return replacement
    }

    /**
     * Revalidates all three callback authorities before any health accounting, acquire, or dispatch.
     */
    fun accepts(binding: ReaderBinding<T>, callbackReader: T): Boolean {
        if (binding.retired || binding.reader !== callbackReader) return false
        val authoritative = current.get(binding.kind.ordinal) ?: return false
        return authoritative === binding &&
            authoritative.epoch == binding.epoch &&
            authoritative.reader === callbackReader &&
            !authoritative.retired
    }

    /** Retires only the binding that owns this exact reader identity and kind. */
    fun retire(kind: ReaderKind, reader: T): ReaderBinding<T>? {
        val binding = current.get(kind.ordinal) ?: return null
        if (binding.reader !== reader) return null
        binding.retire()
        current.compareAndSet(kind.ordinal, binding, null)
        return binding
    }

    /** Retires a captured binding without ever disturbing a newer registration. */
    fun retire(binding: ReaderBinding<T>) {
        binding.retire()
        current.compareAndSet(binding.kind.ordinal, binding, null)
    }

    internal fun current(kind: ReaderKind): ReaderBinding<T>? = current.get(kind.ordinal)
}

/**
 * One linearizable callback boundary shared by every CameraController ImageReader listener.
 */
internal class ReaderCallbackAdmission<T : Any>(
    private val bindings: ReaderBindingRegistry<T>,
    private val ingress: SynchronousImageIngressGate
) {
    /** Returns false without invoking [dispatch] when sealing or binding validation rejects. */
    fun dispatch(
        binding: ReaderBinding<T>,
        callbackReader: T,
        dispatch: () -> Unit
    ): Boolean {
        val lease = ingress.tryEnter() ?: return false
        return try {
            if (!bindings.accepts(binding, callbackReader)) {
                false
            } else {
                dispatch()
                true
            }
        } finally {
            lease.close()
        }
    }
}
