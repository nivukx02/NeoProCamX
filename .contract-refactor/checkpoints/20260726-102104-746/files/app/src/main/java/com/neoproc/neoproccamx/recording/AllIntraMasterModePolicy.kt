package com.neoproc.neoproccamx.recording

/**
 * AllIntraMasterModePolicy — pure Master-mode (CINEMA_MASTER) tier selection (CP-QV12).
 *
 * Master mode is a user-facing "cinema master" all-intra request. This policy names the four
 * discrete request tiers and funnels every one of them through
 * [AllIntraHevcPolicy.resolveEffectiveBitrate] so the effective bitrate can only ever be the
 * requested tier CLAMPED to the live device encoder bitrate range — never an over-claim.
 *
 * NO-DOWNGRADE / honesty by construction:
 *  - The per-tier bit rates are NOT redefined here; each [MasterTier.bps] REFERENCES the single
 *    source-of-truth `AllIntraHevcPolicy.BITRATE_*` constant (80 / 100 / 120 / 180 Mbps).
 *  - A tier that exceeds the advertised device cap is clamped DOWN (proving no over-claim), and a
 *    tier below the policy/device floor is floored UP, both by the same shared resolver the live
 *    [AllIntraHevcPolicy.probe] path already uses.
 *  - [isMasterModeRequested] is a pure predicate; it changes no defaults. Whether Master mode is
 *    actually offered/honored on a given encoder is the caller's DEVICE-GATED step.
 */
object AllIntraMasterModePolicy {

    /**
     * The four Master-mode request tiers. Each tier's [bps] references the corresponding
     * single-source `AllIntraHevcPolicy.BITRATE_*` constant so the numbers cannot drift.
     */
    enum class MasterTier(val bps: Int) {
        /** 80 Mbps — 1080p/2K high-quality proxy. */
        ECONOMY_80(AllIntraHevcPolicy.BITRATE_ECONOMY),

        /** 100 Mbps — 4K 10-bit high-detail acquisition. */
        STANDARD_100(AllIntraHevcPolicy.BITRATE_STANDARD),

        /** 120 Mbps — high-end mobile all-intra request. */
        PRO_120(AllIntraHevcPolicy.BITRATE_PRO_ALL_INTRA),

        /** 180 Mbps — legacy cinema-master target (not a hardware ceiling). */
        CINEMA_180(AllIntraHevcPolicy.BITRATE_CINEMA_MASTER)
    }

    /**
     * Resolves the effective encoder bitrate for a requested [tier], clamped to the live device
     * encoder bitrate range. Delegates to [AllIntraHevcPolicy.resolveEffectiveBitrate] — the same
     * resolver the live probe path uses — so the result is guaranteed to be inside
     * `[deviceCapLowerBps, deviceCapUpperBps]` (after the resolver's own validation/floor logic).
     *
     * @param tier the requested Master tier.
     * @param deviceCapUpperBps the encoder-advertised upper bitrate bound (bits per second).
     * @param deviceCapLowerBps the encoder-advertised lower bitrate bound (bits per second).
     * @return the requested tier bitrate clamped into the validated device range.
     */
    fun resolveRequestedTierBitrate(
        tier: MasterTier,
        deviceCapUpperBps: Int,
        deviceCapLowerBps: Int
    ): Int = AllIntraHevcPolicy.resolveEffectiveBitrate(
        preferredBitrate = tier.bps,
        capabilityLowerBound = deviceCapLowerBps,
        capabilityUpperBound = deviceCapUpperBps
    )

    /** True when the selected bitrate mode is the user's Master (cinema-master) request. */
    fun isMasterModeRequested(mode: VideoBitrateMode): Boolean =
        mode == VideoBitrateMode.CINEMA_MASTER
}
