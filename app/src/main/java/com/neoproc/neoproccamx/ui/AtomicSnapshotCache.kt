package com.neoproc.neoproccamx.ui

import java.util.concurrent.atomic.AtomicReference

/** A lock-free, one-entry cache that publishes each key/value pair as one immutable snapshot. */
internal class AtomicSnapshotCache<K, V> {
    private data class Snapshot<K, V>(val key: K, val value: V)

    private val snapshot = AtomicReference<Snapshot<K, V>?>(null)

    fun resolve(key: K, resolver: () -> V): V {
        snapshot.get()?.takeIf { it.key == key }?.let { return it.value }

        val resolved = resolver()
        val replacement = Snapshot(key, resolved)
        while (true) {
            val observed = snapshot.get()
            if (observed?.key == key) return observed.value
            if (snapshot.compareAndSet(observed, replacement)) return resolved
        }
    }
}
