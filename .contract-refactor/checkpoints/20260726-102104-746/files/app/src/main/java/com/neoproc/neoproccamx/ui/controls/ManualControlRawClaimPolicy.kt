package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.quality.OmegaCapabilityStatus

/**
 * s8-f02 (CP-U2) — honest RAW claim for the manual-controls RAW tab. Today the RAW label is set
 * purely from the request flag (`isRawEnabled`), so it can advertise "RAW ON" even when the pipeline
 * did not deliver a true RAW transport. This pure helper maps the proven RAW capability
 * ([OmegaCapabilityStatus] for RAW_SENSOR_TRUTH) to an honest label state.
 *
 * NO-DOWNGRADE: [ENFORCE_PROVEN_RAW_CLAIM_DEFAULT] is false, so [labelFor] ignores the (default-null)
 * proof and returns RAW_OFF / RAW_ON exactly as today's `if (rawEnabled) "RAW ON" else "RAW OFF"`.
 * Only when an owner flips the flag device-side and supplies a real status does an unproven RAW claim
 * fall to RAW_NOT_AVAILABLE — mirroring OmegaCapabilityGate.rawStatus (LIMITED/NOT_AVAILABLE never
 * advertise RAW). Must be `internal`: [OmegaCapabilityStatus] is internal to :app.
 */
internal object ManualControlRawClaimPolicy {

    enum class RawClaimLabel { RAW_OFF, RAW_ON, RAW_NOT_AVAILABLE }

    /** Default OFF reproduces today's RAW ON/OFF label exactly. */
    const val ENFORCE_PROVEN_RAW_CLAIM_DEFAULT = false

    fun labelFor(
        rawToggleOn: Boolean,
        proofStatus: OmegaCapabilityStatus? = null,
        enforceProof: Boolean = ENFORCE_PROVEN_RAW_CLAIM_DEFAULT
    ): RawClaimLabel = when {
        !rawToggleOn -> RawClaimLabel.RAW_OFF
        !enforceProof -> RawClaimLabel.RAW_ON
        proofStatus == OmegaCapabilityStatus.SUPPORTED -> RawClaimLabel.RAW_ON
        else -> RawClaimLabel.RAW_NOT_AVAILABLE
    }

    fun text(label: RawClaimLabel): String = when (label) {
        RawClaimLabel.RAW_OFF -> "RAW OFF"
        RawClaimLabel.RAW_ON -> "RAW ON"
        RawClaimLabel.RAW_NOT_AVAILABLE -> "RAW N/A"
    }
}
