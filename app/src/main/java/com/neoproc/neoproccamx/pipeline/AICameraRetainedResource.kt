package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.atomic.AtomicInteger

class AICameraRetainedResource<T>(private val value: T, private val releaseAction: () -> Unit) {
    private val refCount = AtomicInteger(1)

    fun retain(): T? {
        while (true) {
            val current = refCount.get()
            if (current <= 0) return null
            if (refCount.compareAndSet(current, current + 1)) {
                return value
            }
        }
    }

    fun release() {
        while (true) {
            val current = refCount.get()
            if (current <= 0) return
            if (refCount.compareAndSet(current, current - 1)) {
                if (current - 1 == 0) {
                    releaseAction()
                }
                return
            }
        }
    }
}
