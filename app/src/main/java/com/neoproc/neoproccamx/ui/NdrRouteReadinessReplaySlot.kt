package com.neoproc.neoproccamx.ui

/**
 * One latest-wins slot shared by the compositor-ready and render-profile-ready edges.
 *
 * A readiness edge atomically takes the retained request. If the other dependency is still unready,
 * the caller retains that same current request again. This avoids polling and makes either dependency
 * ordering converge while preserving the coordinator's epoch/session freshness checks.
 */
internal class NdrRouteReadinessReplaySlot<T> {
    private var pending: T? = null
    private var closed = false

    fun retainLatest(intent: T) {
        if (!closed) pending = intent
    }

    fun takeForReadinessEdge(): T? {
        if (closed) return null
        val retained = pending
        pending = null
        return retained
    }

    fun invalidate() {
        pending = null
    }

    fun close() {
        closed = true
        pending = null
    }

    internal fun hasPendingIntent(): Boolean = pending != null
}
