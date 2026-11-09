package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.recording.CinemaVideoMode

internal data class ModeSwitchSessionState(
    val cinemaMode: CinemaVideoMode,
    val pipelineSignature: String,
    val rawReaderGraphActive: Boolean,
    val hdrMode: String,
    val logEnabled: Boolean
)

internal data class ModeSwitchSessionRequest(
    val previous: ModeSwitchSessionState,
    val current: ModeSwitchSessionState,
    val hdrToHdrSameTransport: Boolean
)

/** Coalesces all intermediate preset mutations into one settled Camera2 session decision. */
internal class ModeSwitchSessionTransaction {
    private var baseline: ModeSwitchSessionState? = null
    private var refreshRequested = false

    fun begin(state: ModeSwitchSessionState) {
        baseline = state
        refreshRequested = false
    }

    fun requestRefresh() {
        refreshRequested = true
    }

    fun finish(state: ModeSwitchSessionState): ModeSwitchSessionRequest? {
        val previous = baseline
        baseline = null
        val requested = refreshRequested
        refreshRequested = false
        if (!requested || previous == null) return null
        val previousTransport = HdrDynamicRangeMode.fromUiMode(previous.hdrMode, previous.logEnabled)
        val currentTransport = HdrDynamicRangeMode.fromUiMode(state.hdrMode, state.logEnabled)
        return ModeSwitchSessionRequest(
            previous = previous,
            current = state,
            hdrToHdrSameTransport =
                previousTransport == currentTransport && previousTransport != HdrDynamicRangeMode.SDR
        )
    }

    /** Discards a failed transition's baseline and refresh request. Safe to call more than once. */
    fun abort() {
        baseline = null
        refreshRequested = false
    }
}
