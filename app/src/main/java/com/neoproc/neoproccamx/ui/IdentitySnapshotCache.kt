package com.neoproc.neoproccamx.ui

/**
 * One immutable metadata snapshot, keyed by the actual owner, not its identity-hash integer.
 * Live scene/settings values are not cached here. A replaced CameraCharacteristics object or a
 * changed capability qualifier must be resolved again; an absent owner and failed reads never
 * become sticky defaults. The cache retains at most one owner and one completed result.
 */
internal class IdentitySnapshotCache<Q, V> {
    data class Computed<V>(val value: V, val cacheable: Boolean = true)
    private class Entry<Q, V>(val owner: Any, val qualifier: Q, val value: V)

    @Volatile private var snapshot: Entry<Q, V>? = null
    private val publicationLock = Any()

    fun resolve(owner: Any?, qualifier: Q, compute: () -> Computed<V>): V {
        if (owner == null) return compute().value
        snapshot?.let { if (it.owner === owner && it.qualifier == qualifier) return it.value }
        return synchronized(publicationLock) {
            snapshot?.let { if (it.owner === owner && it.qualifier == qualifier) return it.value }
            val completed = compute()
            if (completed.cacheable) snapshot = Entry(owner, qualifier, completed.value)
            completed.value
        }
    }
}
