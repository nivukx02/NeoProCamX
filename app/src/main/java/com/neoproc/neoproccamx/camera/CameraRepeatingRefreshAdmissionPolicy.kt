package com.neoproc.neoproccamx.camera

/**
 * Admission policy for a Camera2 repeating-request replacement.
 *
 * A mode switch mutates the desired target graph before the replacement capture session owns those
 * surfaces. Rebuilding a request in that interval can target a RAW/YUV surface which is not part of
 * the outgoing session and provoke a second, unnecessary recovery rebuild. The session transaction
 * is authoritative: its onConfigured callback always publishes a request from the latest state.
 */
internal object CameraRepeatingRefreshAdmissionPolicy {
    fun shouldDeferToSessionTransaction(
        batchDepth: Int,
        batchRefreshHandoffPending: Boolean,
        queuedSessionRefreshDispatches: Int,
        sessionRefreshPending: Boolean
    ): Boolean =
        batchDepth > 0 ||
            batchRefreshHandoffPending ||
            queuedSessionRefreshDispatches > 0 ||
            sessionRefreshPending

    fun shouldReplayAfterBatch(
        repeatingRefreshDeferred: Boolean,
        sessionRefreshQueued: Boolean
    ): Boolean = repeatingRefreshDeferred && !sessionRefreshQueued
}
