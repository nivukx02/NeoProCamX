package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RecordingHdrVideoLookPolicy

/** One authority for deciding when NDR owns a RAW_SENSOR Camera2 target. */
internal object SensorLinearNdrModePolicy {
    fun isActive(
        uiMode: AppMode,
        cinemaMode: CinemaVideoMode,
        rawEnabled: Boolean,
        logEnabled: Boolean,
        hdrMode: String
    ): Boolean {
        if (uiMode != AppMode.VIDEO || cinemaMode != CinemaVideoMode.PRO || rawEnabled || logEnabled) {
            return false
        }
        return when (RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(hdrMode)) {
            "NDR10+", "NIVUKXVISION", "APEX_SHARP", "APEX_ZERO", "APEX_CINEMA" -> true
            else -> false
        }
    }
}
