package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.quality.OmegaCapabilityId
import com.neoproc.neoproccamx.quality.OmegaCapabilityStatus
import com.neoproc.neoproccamx.quality.OmegaQualityTruthReport

internal data class SettingsOptionAvailability(val available: Boolean, val reason: String?)

/**
 * s8-f03 (CP-U3) — the honest capability-aware enablement decision layer. Read-only over an
 * [OmegaQualityTruthReport]: it can only RESTRICT a proven-impossible option, never advertise an
 * unproven one. It is doubly guarded so the default reproduces today exactly:
 *  - gate inactive ([SettingsCapabilityGatePolicy] off) OR no report OR no capability id ⇒ available.
 *  - capability proven SUPPORTED ⇒ available.
 *  - capability LIMITED / NOT_AVAILABLE under an active gate ⇒ disabled, with a reason citing the
 *    real OmegaCapabilityStatus (the NOT_PROVEN/NOT_AVAILABLE honesty bar).
 */
internal object SettingsCapabilitySpec {

    fun availabilityFor(
        capabilityId: OmegaCapabilityId?,
        report: OmegaQualityTruthReport?
    ): SettingsOptionAvailability {
        if (!SettingsCapabilityGatePolicy.isCapabilityGatingActive() || report == null || capabilityId == null) {
            return SettingsOptionAvailability(available = true, reason = null)
        }
        val status = report.statusOf(capabilityId)
        return if (status == OmegaCapabilityStatus.SUPPORTED) {
            SettingsOptionAvailability(available = true, reason = null)
        } else {
            SettingsOptionAvailability(
                available = false,
                reason = "Unavailable on this device — ${capabilityId.name} is ${status.name}"
            )
        }
    }
}
