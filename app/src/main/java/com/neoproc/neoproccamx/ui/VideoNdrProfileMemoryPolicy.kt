package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.RecordingHdrVideoLookPolicy

/** Keeps the selected Neo NDR look independent from the temporarily active camera mode. */
internal object VideoNdrProfileMemoryPolicy {
    private const val DEFAULT_PROFILE = "NDR10+"

    fun resolvePreferred(
        storedPreferred: String?,
        activeHdrMode: String? = null
    ): String {
        val stored = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(storedPreferred)
        if (stored != "OFF") return stored

        val active = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(activeHdrMode)
        return active.takeUnless { it == "OFF" } ?: DEFAULT_PROFILE
    }

    fun remember(storedPreferred: String?, selectedHdrMode: String?): String {
        val selected = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(selectedHdrMode)
        return if (selected != "OFF") selected else resolvePreferred(storedPreferred)
    }
}
