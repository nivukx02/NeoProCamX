package com.neoproc.neoproccamx.ui

/**
 * Publishes the Vulkan render generation, HDR mode, and caller-owned render fields as one authority.
 *
 * Native generation retirement happens before the matching StateFlow mutation is made visible.
 * The immutable snapshot is published last, so a render request can observe either the complete old
 * authority or the complete new authority, never a new generation paired with old render state.
 * Accepted commits share the same monitor with authority transitions, closing the final
 * check-to-native-write window during mode changes.
 */
internal class VulkanRenderProfileAuthorityCoordinator(
    initialGeneration: Long,
    initialHdrMode: String
) {
    data class Snapshot(
        val generation: Long,
        val hdrMode: String
    )

    private val monitor = Any()

    @Volatile
    private var committed = Snapshot(
        generation = initialGeneration,
        hdrMode = initialHdrMode
    )

    fun snapshot(): Snapshot = committed

    /**
     * Advances native retirement first, commits the caller's bounded non-throwing StateFlow mutations,
     * and only then publishes the new immutable authority. Callers must keep both commit callbacks to
     * direct in-memory assignments; fallible I/O and device work belongs outside this transaction.
     */
    fun advance(
        hdrMode: String? = null,
        retireNativeGeneration: (Long) -> Unit,
        commitAuthorityState: () -> Unit = {},
        commitHdrMode: (String) -> Unit = {}
    ): Snapshot = synchronized(monitor) {
        val previous = committed
        val next = Snapshot(
            generation = previous.generation + 1L,
            hdrMode = hdrMode ?: previous.hdrMode
        )
        retireNativeGeneration(next.generation)
        commitAuthorityState()
        if (hdrMode != null) commitHdrMode(next.hdrMode)
        committed = next
        next
    }

    fun isCurrent(generation: Long, hdrMode: String? = null): Boolean {
        val current = committed
        return current.generation == generation && (hdrMode == null || current.hdrMode == hdrMode)
    }

    /**
     * Captures every caller-owned input for one deferred render request while authority transitions
     * are excluded. The returned value therefore belongs to exactly one generation/HDR authority;
     * callers must not re-read mutable mode/profile state while resolving that request later.
     */
    fun <T> captureIfCurrent(
        generation: Long? = null,
        hdrMode: String? = null,
        externalAuthorityCurrent: (Snapshot) -> Boolean = { true },
        block: (Snapshot) -> T
    ): T? = synchronized(monitor) {
        val current = committed
        if (
            (generation != null && current.generation != generation) ||
            (hdrMode != null && current.hdrMode != hdrMode) ||
            !externalAuthorityCurrent(current)
        ) {
            null
        } else {
            block(current)
        }
    }

    fun commitIfCurrent(
        generation: Long,
        hdrMode: String? = null,
        externalAuthorityCurrent: () -> Boolean,
        block: () -> Unit
    ): Boolean = synchronized(monitor) {
        if (!isCurrent(generation, hdrMode) || !externalAuthorityCurrent()) {
            false
        } else {
            block()
            true
        }
    }
}
