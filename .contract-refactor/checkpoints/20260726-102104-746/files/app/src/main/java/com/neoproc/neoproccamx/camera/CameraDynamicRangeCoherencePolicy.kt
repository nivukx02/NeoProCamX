package com.neoproc.neoproccamx.camera

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  s1-f05 — DYNAMIC-RANGE COHERENCE (negotiated == requested, or honest reject)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * The WYSIWYG promise of a cinema body is that the transfer/gamut chosen is the
 * one actually negotiated end-to-end — preview and master agree, and a request
 * for 10-bit is either honored or honestly rejected, never silently downgraded to
 * 8-bit. This policy turns the live [HdrDynamicRangeState] (what the user asked
 * for vs. the dynamic-range profiles actually applied to the preview/encoder
 * OutputConfigurations) into a single, honest verdict.
 *
 * The dangerous case is [CameraDynamicRangeCoherenceVerdict.SILENT_EIGHT_BIT_FALLBACK]:
 * the user requested a 10-bit Neo dynamic-range mode, the hardware exposes a
 * 10-bit transport profile, yet the session is running on STANDARD (8-bit). That
 * is a faked 10-bit claim and must be impossible to pass the `OmegaCapabilityGate`
 * — [silentFallback] feeds the gate's strict-10-bit reporting so an 8-bit master
 * can never be certified as a SUPPORTED 10-bit transport.
 *
 * Pure / no-downgrade: this only reads already-computed state; it changes no
 * request and applies no profile.
 */
internal enum class CameraDynamicRangeCoherenceVerdict {
    /** SDR was requested — no 10-bit claim is made. */
    SDR_NOT_REQUESTED,

    /** 10-bit requested and the device cannot do it; honestly running 8-bit (reported). */
    HONEST_HARDWARE_DOWNGRADE,

    /** 10-bit requested on capable hardware, but no session has applied profiles yet. */
    UNVERIFIED_NO_SESSION,

    /** 10-bit requested, hardware capable, and a real 10-bit profile is applied coherently. */
    COHERENT_TEN_BIT,

    /** Preview and encoder negotiated different 10-bit profiles — WYSIWYG mismatch. */
    PREVIEW_ENCODER_SPLIT,

    /** 10-bit requested on capable hardware, but the applied transport is silently 8-bit. */
    SILENT_EIGHT_BIT_FALLBACK
}

internal data class CameraDynamicRangeCoherenceReport(
    val verdict: CameraDynamicRangeCoherenceVerdict,
    val requestedMode: HdrDynamicRangeMode,
    val previewProfile: Long?,
    val encoderProfile: Long?,
    val resolvedProfileForCurrentMode: Long?,
    val requestedTenBit: Boolean,
    val negotiatedTransportBitDepth: Int,
    val silentFallback: Boolean,
    val coherent: Boolean
) {
    /**
     * The honest bit depth to feed an `OmegaCapabilityInput`/recording-evidence
     * producer for the master path. A silent 8-bit fallback can never lift a
     * producer's claimed depth — it caps it at the real 8-bit transport so the
     * gate downgrades the 10-bit claim to LIMITED.
     */
    fun omegaHonestBitDepth(claimedBitDepth: Int): Int =
        if (silentFallback) minOf(claimedBitDepth, 8) else claimedBitDepth

    fun toCompactMetadataMap(): Map<String, String> = linkedMapOf(
        "dynamicRangeCoherenceVerdict" to verdict.name,
        "dynamicRangeRequestedMode" to requestedMode.name,
        "dynamicRangeRequestedTenBit" to requestedTenBit.toString(),
        "dynamicRangeNegotiatedBitDepth" to negotiatedTransportBitDepth.toString(),
        "dynamicRangeSilentFallback" to silentFallback.toString(),
        "dynamicRangeCoherent" to coherent.toString()
    )
}

internal object CameraDynamicRangeCoherencePolicy {

    private const val EIGHT_BIT = 8
    private const val TEN_BIT = 10
    private const val UNKNOWN_BIT_DEPTH = 0

    /** A real 10-bit transport profile (anything the policy applies that is not STANDARD). */
    fun isTenBitProfile(profile: Long?): Boolean =
        profile != null && profile != HdrDynamicRangePolicy.PROFILE_STANDARD

    fun evaluate(
        state: HdrDynamicRangeState,
        sessionConfigured: Boolean = true,
        recordingEncoderActive: Boolean = false
    ): CameraDynamicRangeCoherenceReport {
        val requested = state.requestedMode
        // SDR makes no 10-bit claim. DOLBY_VISION is intentionally NOT a Camera2 transport profile
        // (resolveProfile returns null for it on every device — the encoder owns DV), so a STANDARD
        // Camera2 transport is correct-by-design for it and must never be flagged as a silent fallback.
        if (requested == HdrDynamicRangeMode.SDR || requested == HdrDynamicRangeMode.DOLBY_VISION) {
            return report(
                verdict = CameraDynamicRangeCoherenceVerdict.SDR_NOT_REQUESTED,
                state = state,
                requestedTenBit = false,
                negotiatedBitDepth = EIGHT_BIT,
                silentFallback = false,
                coherent = true
            )
        }

        // The policy's own resolution for this mode on THIS device is the honest "expected" transport.
        // If it resolves to STANDARD (null / not 10-bit) the device cannot honor a 10-bit transport for
        // this mode, so an 8-bit run is an HONEST downgrade (reported via hdrFallbackToSdr), not a fake.
        // Keying off the resolved profile rather than the coarse device-level anyHdrSupported means a
        // mode the device only partially supports (e.g. HLG requested on an HDR10-only device) is also
        // correctly classified as honest, never as a silent fallback.
        val expectedTenBit = isTenBitProfile(state.resolvedProfileForCurrentMode)
        if (!expectedTenBit) {
            return report(
                verdict = CameraDynamicRangeCoherenceVerdict.HONEST_HARDWARE_DOWNGRADE,
                state = state,
                requestedTenBit = true,
                negotiatedBitDepth = EIGHT_BIT,
                silentFallback = false,
                coherent = true
            )
        }

        if (!sessionConfigured) {
            return report(
                verdict = CameraDynamicRangeCoherenceVerdict.UNVERIFIED_NO_SESSION,
                state = state,
                requestedTenBit = true,
                negotiatedBitDepth = UNKNOWN_BIT_DEPTH,
                silentFallback = false,
                coherent = true
            )
        }

        val previewTenBit = isTenBitProfile(state.previewProfile)
        val encoderTenBit = isTenBitProfile(state.encoderProfile)
        val previewSilent = !previewTenBit
        val encoderSilent = recordingEncoderActive && !encoderTenBit

        return when {
            previewSilent || encoderSilent ->
                report(
                    verdict = CameraDynamicRangeCoherenceVerdict.SILENT_EIGHT_BIT_FALLBACK,
                    state = state,
                    requestedTenBit = true,
                    negotiatedBitDepth = EIGHT_BIT,
                    silentFallback = true,
                    coherent = false
                )

            recordingEncoderActive && state.previewProfile != state.encoderProfile ->
                // Both paths are 10-bit but on different profiles — a WYSIWYG break,
                // not an 8-bit fault, so the transport is still 10-bit.
                report(
                    verdict = CameraDynamicRangeCoherenceVerdict.PREVIEW_ENCODER_SPLIT,
                    state = state,
                    requestedTenBit = true,
                    negotiatedBitDepth = TEN_BIT,
                    silentFallback = false,
                    coherent = false
                )

            else ->
                report(
                    verdict = CameraDynamicRangeCoherenceVerdict.COHERENT_TEN_BIT,
                    state = state,
                    requestedTenBit = true,
                    negotiatedBitDepth = TEN_BIT,
                    silentFallback = false,
                    coherent = true
                )
        }
    }

    /** Convenience for gate wiring — true only for the dishonest silent-8-bit case. */
    fun silentFallbackDetected(
        state: HdrDynamicRangeState,
        sessionConfigured: Boolean = true,
        recordingEncoderActive: Boolean = false
    ): Boolean = evaluate(state, sessionConfigured, recordingEncoderActive).silentFallback

    private fun report(
        verdict: CameraDynamicRangeCoherenceVerdict,
        state: HdrDynamicRangeState,
        requestedTenBit: Boolean,
        negotiatedBitDepth: Int,
        silentFallback: Boolean,
        coherent: Boolean
    ): CameraDynamicRangeCoherenceReport =
        CameraDynamicRangeCoherenceReport(
            verdict = verdict,
            requestedMode = state.requestedMode,
            previewProfile = state.previewProfile,
            encoderProfile = state.encoderProfile,
            resolvedProfileForCurrentMode = state.resolvedProfileForCurrentMode,
            requestedTenBit = requestedTenBit,
            negotiatedTransportBitDepth = negotiatedBitDepth,
            silentFallback = silentFallback,
            coherent = coherent
        )
}
