package com.neoproc.neoproccamx.quality

/**
 * s9-f14 (CP-QV14) — the honest capability badge.
 *
 * Renders a short, human-readable ' · '-joined badge string that advertises ONLY capabilities the
 * [OmegaQualityTruthReport] has certified as [OmegaCapabilityStatus.SUPPORTED]. It follows the
 * only-advertise-SUPPORTED precedent from
 * [com.neoproc.neoproccamx.ui.controls.SettingsCapabilitySpec]: a capability that is LIMITED or
 * NOT_AVAILABLE produces no token, so the badge can never over-claim.
 *
 * Honesty rules:
 *  - `report == null` ⇒ "" (nothing proven, nothing advertised) — reproduces "no badge" today.
 *  - The bit-depth prefix on the HEVC token is read from the SUPPORTED item's own evidence
 *    (`bitDepth=<n>`) or, when that is absent/unparseable, from the passed [encodedBitDepth] arg.
 *    It is NEVER invented: if neither source yields a value the prefix is omitted.
 *  - There is NO audio / 48kHz token: the gate has no audio [OmegaCapabilityId], so inventing one
 *    would fabricate an unproven claim (violating honest-gates).
 */
internal object CapabilityBadgePolicy {

    private val BIT_DEPTH_REGEX = Regex("""bitDepth=(\d+)""")

    /**
     * Builds an ordered ' · '-joined badge from the SUPPORTED capabilities of [report].
     *
     * @param report the truth report to read; a null report yields "".
     * @param encodedBitDepth optional fallback bit depth used only when the SUPPORTED item's
     *   evidence does not carry a `bitDepth=` value; never overrides evidence and is never invented.
     */
    fun badgeFor(report: OmegaQualityTruthReport?, encodedBitDepth: Int? = null): String {
        if (report == null) return ""

        val tokens = LinkedHashSet<String>()

        val neoTransportIds = listOf(
            OmegaCapabilityId.NDR_NEO_TRANSPORT,
            OmegaCapabilityId.NIVUKX_NEO_TRANSPORT,
            OmegaCapabilityId.N_LOG_SDR_TRUTH
        )
        if (neoTransportIds.any { report.statusOf(it) == OmegaCapabilityStatus.SUPPORTED }) {
            val bitDepth = neoTransportIds
                .firstNotNullOfOrNull { id -> supportedBitDepth(report, id) }
                ?: sanitizedBitDepth(encodedBitDepth)
            tokens.add(if (bitDepth != null) "$bitDepth-bit HEVC" else "HEVC")
            tokens.add("HDR10")
        }

        if (report.statusOf(OmegaCapabilityId.VULKAN_RECORDING_PATH) == OmegaCapabilityStatus.SUPPORTED) {
            tokens.add("Vulkan")
        }

        if (report.statusOf(OmegaCapabilityId.RAW_SENSOR_TRUTH) == OmegaCapabilityStatus.SUPPORTED) {
            tokens.add("RAW")
        }

        return tokens.joinToString(separator = " · ")
    }

    /**
     * Returns the certified encoded bit depth for [id] parsed from its item evidence, but only when
     * the item is actually SUPPORTED. Any other status (or a missing/unparseable value) yields null,
     * so an unproven capability can never contribute a bit-depth number to the badge.
     */
    private fun supportedBitDepth(report: OmegaQualityTruthReport, id: OmegaCapabilityId): Int? {
        val item = report.items.firstOrNull { it.id == id } ?: return null
        if (item.status != OmegaCapabilityStatus.SUPPORTED) return null
        val parsed = BIT_DEPTH_REGEX.find(item.evidence)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return sanitizedBitDepth(parsed)
    }

    private fun sanitizedBitDepth(value: Int?): Int? =
        if (value != null && value > 0) value else null
}
