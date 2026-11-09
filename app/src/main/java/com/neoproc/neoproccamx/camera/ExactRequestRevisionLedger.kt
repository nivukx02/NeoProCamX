package com.neoproc.neoproccamx.camera

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * Thread-safe weak lineage from an immutable request object to the control revision it represents.
 * Callers must keep the request strongly reachable until its completion callback; retired requests
 * then disappear without an explicit unbounded history.
 */
internal class ExactRequestRevisionLedger<Request : Any> {
    private class IdentityWeakReference<Value : Any> : WeakReference<Value> {
        private val identityHash: Int

        constructor(value: Value, queue: ReferenceQueue<Value>) : super(value, queue) {
            identityHash = System.identityHashCode(value)
        }

        constructor(value: Value) : super(value) {
            identityHash = System.identityHashCode(value)
        }

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdentityWeakReference<*>) return false
            val value = get() ?: return false
            return value === other.get()
        }
    }

    private val retiredRequests = ReferenceQueue<Request>()
    private val revisions = HashMap<IdentityWeakReference<Request>, Long>()

    @Synchronized
    fun record(request: Request, revision: Long): Request {
        removeRetiredRequests()
        revisions[IdentityWeakReference(request, retiredRequests)] = revision
        return request
    }

    @Synchronized
    fun revisionFor(request: Request): Long {
        removeRetiredRequests()
        return revisions[IdentityWeakReference(request)] ?: UNKNOWN_REVISION
    }

    @Synchronized
    fun clear() {
        revisions.clear()
        while (retiredRequests.poll() != null) {
            // Drain stale queue nodes so a new Camera2 session starts from a fully empty ledger.
        }
    }

    private fun removeRetiredRequests() {
        while (true) {
            @Suppress("UNCHECKED_CAST")
            val retired = retiredRequests.poll() as? IdentityWeakReference<Request> ?: return
            revisions.remove(retired)
        }
    }

    companion object {
        const val UNKNOWN_REVISION = -1L
    }
}
