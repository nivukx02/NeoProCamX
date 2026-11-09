package com.neoproc.neoproccamx.rendering

/**
 * Assigns stable packet revisions to serialized immutable render payloads.
 *
 * A forced replay of byte-identical geometry/grade/enhancement state keeps its revision, while any
 * native-visible payload change advances exactly once. A mode generation begins a fresh revision
 * domain, so identity remains the ordered pair (generation, revision).
 */
internal class NdrRenderProfileRevisionSequencer {
    private var generation = 0L
    private var revision = 0L
    private var latestPacket: NdrRenderProfilePacket? = null

    @Synchronized
    fun assign(candidate: NdrRenderProfilePacket): NdrRenderProfilePacket {
        require(candidate.profileGeneration > 0L) { "NDR packet generation must be positive" }
        require(candidate.configRevision == UNASSIGNED_REVISION) {
            "NDR revision sequencer accepts only unassigned packets"
        }
        val retained = candidate.immutableSnapshot()
        require(generation == 0L || candidate.profileGeneration >= generation) {
            "NDR packet generation cannot move backwards"
        }
        if (candidate.profileGeneration != generation) {
            generation = candidate.profileGeneration
            revision = FIRST_REVISION
        } else if (latestPacket?.hasSamePayload(retained) != true) {
            check(revision < Long.MAX_VALUE) { "NDR packet revision exhausted" }
            revision += 1L
        }
        val assigned = retained.copy(configRevision = revision)
        latestPacket = assigned
        return assigned
    }

    internal companion object {
        const val UNASSIGNED_REVISION = 0L
        private const val FIRST_REVISION = 1L
    }
}
