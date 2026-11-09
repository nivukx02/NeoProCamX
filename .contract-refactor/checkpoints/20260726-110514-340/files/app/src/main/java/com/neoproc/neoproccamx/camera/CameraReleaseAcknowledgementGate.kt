package com.neoproc.neoproccamx.camera

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A generation-safe barrier for the real Camera2 device/session close callbacks. */
data class CameraReleaseTicket(val generation: Long)

internal class CameraReleaseAcknowledgementGate {
    private data class PendingRelease(
        val ticket: CameraReleaseTicket,
        val device: Any?,
        val sessions: MutableSet<Any>,
        val latch: CountDownLatch,
        val deviceLatch: CountDownLatch,
        var deviceReleased: Boolean
    )

    private val lock = Any()
    private var nextGeneration = 0L
    private var pending: PendingRelease? = null

    fun begin(device: Any?, sessions: Collection<Any>): CameraReleaseTicket = synchronized(lock) {
        val uniqueSessions = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        uniqueSessions.addAll(sessions)
        val ticket = CameraReleaseTicket(++nextGeneration)
        pending = PendingRelease(
            ticket = ticket,
            device = device,
            sessions = uniqueSessions,
            latch = CountDownLatch(uniqueSessions.size + if (device == null) 0 else 1),
            deviceLatch = CountDownLatch(if (device == null) 0 else 1),
            deviceReleased = device == null
        )
        ticket
    }

    fun acknowledgeDevice(device: Any) = synchronized(lock) {
        val release = pending ?: return@synchronized
        if (!release.deviceReleased && release.device === device) {
            release.deviceReleased = true
            release.deviceLatch.countDown()
            release.latch.countDown()
        }
    }

    fun acknowledgeSession(session: Any) = synchronized(lock) {
        val release = pending ?: return@synchronized
        if (release.sessions.remove(session)) release.latch.countDown()
    }

    fun await(ticket: CameraReleaseTicket, timeoutMs: Long): Boolean {
        val release = synchronized(lock) { pending?.takeIf { it.ticket == ticket } } ?: return false
        val safeTimeoutMs = timeoutMs.coerceAtLeast(0L)
        if (release.device != null) {
            // CameraDevice.onClosed is the definitive Camera2 ownership boundary: after it fires, all
            // sessions created by that device are unusable and the CameraService client is disconnected.
            // Some vendor HALs (confirmed on vivo I2405) omit a trailing session.onClosed callback when
            // device.close() tears the session down. Give that callback a short bookkeeping grace period,
            // but never crash or retain the camera solely because the redundant vendor callback is absent.
            if (!release.deviceLatch.await(safeTimeoutMs, TimeUnit.MILLISECONDS)) return false
            release.latch.await(minOf(safeTimeoutMs, SESSION_CALLBACK_GRACE_MS), TimeUnit.MILLISECONDS)
            return true
        }
        return release.latch.await(safeTimeoutMs, TimeUnit.MILLISECONDS)
    }

    fun isFullyAcknowledged(ticket: CameraReleaseTicket): Boolean = synchronized(lock) {
        pending?.takeIf { it.ticket == ticket }?.latch?.count == 0L
    }

    fun pendingDescription(ticket: CameraReleaseTicket): String = synchronized(lock) {
        val release = pending?.takeIf { it.ticket == ticket } ?: return@synchronized "stale-generation"
        "device=${if (release.deviceReleased) "released" else "pending"},sessions=${release.sessions.size}"
    }

    private companion object {
        const val SESSION_CALLBACK_GRACE_MS = 250L
    }
}
