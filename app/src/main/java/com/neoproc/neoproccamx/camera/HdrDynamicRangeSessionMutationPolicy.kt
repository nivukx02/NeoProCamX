package com.neoproc.neoproccamx.camera

internal data class HdrDynamicRangeSessionMutation(
    val rebuildSession: Boolean,
    val rebuildReaders: Boolean,
    val retargetRepeatingRequest: Boolean
)

/** Separates output-graph mutations from capture-request-only exposure-governor changes. */
internal object HdrDynamicRangeSessionMutationPolicy {
    fun resolve(
        modeChanged: Boolean,
        governorChanged: Boolean,
        analysisChanged: Boolean
    ): HdrDynamicRangeSessionMutation = when {
        modeChanged || analysisChanged -> HdrDynamicRangeSessionMutation(
            rebuildSession = true,
            rebuildReaders = analysisChanged,
            retargetRepeatingRequest = false
        )
        governorChanged -> HdrDynamicRangeSessionMutation(
            rebuildSession = false,
            rebuildReaders = false,
            retargetRepeatingRequest = true
        )
        else -> HdrDynamicRangeSessionMutation(
            rebuildSession = false,
            rebuildReaders = false,
            retargetRepeatingRequest = false
        )
    }
}
