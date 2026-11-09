package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.RecordingHdrVideoLookPolicy

/**
 * Resolves the one HDR/NDR profile that is allowed to reach the live render pipeline.
 *
 * Preferences are restored into the ViewModel before rendering starts. From that point onward the
 * active StateFlow is authoritative: neither a stale persisted value nor a cached render contract may
 * turn a profile back on after the user-visible state is OFF. Eligibility still fails closed outside
 * the processed Video/Pro graph so RAW and LOG retain their independent colour ownership.
 */
internal object VideoHdrRuntimeAuthorityPolicy {
    fun resolve(runtimeEligible: Boolean, activeHdrMode: String?): String =
        if (runtimeEligible) {
            RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(activeHdrMode)
        } else {
            "OFF"
        }
}
