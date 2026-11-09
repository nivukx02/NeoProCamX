package com.neoproc.neoproccamx.ui

/**
 * Serializes replacement SurfaceView binding behind Camera2 ownership retirement. Camera2 may keep
 * an output BufferQueue referenced until its session/device close callback, so the EGL producers that
 * own that queue cannot be released or reused by a replacement surface before that boundary.
 */
internal data class PreviewSurfaceRetirementTicket(val generation: Long)

internal data class PreviewSurfaceRetirementCompletion<S : Any>(
    val accepted: Boolean,
    val replacement: S?
)

internal class PreviewSurfaceRetirementCoordinator<S : Any> {
    private val lock = Any()
    private var generation = 0L
    private var activeTicket: PreviewSurfaceRetirementTicket? = null
    private var pendingReplacement: S? = null

    fun begin(): PreviewSurfaceRetirementTicket = synchronized(lock) {
        PreviewSurfaceRetirementTicket(++generation).also { ticket ->
            activeTicket = ticket
            pendingReplacement = null
        }
    }

    /** Returns true when ownership of [surface] was deferred behind an active retirement. */
    fun deferReplacement(surface: S): Boolean = synchronized(lock) {
        if (activeTicket == null) return@synchronized false
        pendingReplacement = surface
        true
    }

    fun currentTicket(): PreviewSurfaceRetirementTicket? = synchronized(lock) { activeTicket }

    fun isCurrent(ticket: PreviewSurfaceRetirementTicket): Boolean = synchronized(lock) {
        activeTicket == ticket
    }

    fun complete(
        ticket: PreviewSurfaceRetirementTicket
    ): PreviewSurfaceRetirementCompletion<S> = synchronized(lock) {
        if (activeTicket != ticket) {
            return@synchronized PreviewSurfaceRetirementCompletion(
                accepted = false,
                replacement = null
            )
        }
        val replacement = pendingReplacement
        activeTicket = null
        pendingReplacement = null
        PreviewSurfaceRetirementCompletion(
            accepted = true,
            replacement = replacement
        )
    }
}
