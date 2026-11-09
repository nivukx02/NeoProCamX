package com.neoproc.neoproccamx.recording

/** Runs one synchronized initialization while allowing hot paths to read only completed results. */
internal class NonBlockingSynchronizedLazy<T>(initializer: () -> T) {
    private val delegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED, initializer)

    fun get(): T = delegate.value

    fun getOrNull(): T? = if (delegate.isInitialized()) delegate.value else null
}
