package com.neoproc.neoproccamx.recording

import java.util.Locale

/**
 * Resolves the encoder bitrate for the project-owned NDR/NIVUKX/APEX record path.
 *
 * The generic strict-quality multiplier is intentionally not a bitrate model: in strict mode it can
 * exceed 1000x and saturate a valid MediaCodec request to [Int.MAX_VALUE]. Proprietary NDR instead
 * asks the existing capability clamp for the highest bitrate the selected codec set can really
 * admit, then makes that proved value the strict session target. Pixel transport is unaffected.
 */
internal object ProprietaryNdrRecordingBitratePolicy {
    data class Decision(
        val codecPolicyCeilingBps: Int,
        val minimumViableBitrateBps: Int,
        val encoderAdmittedMaximumBps: Int,
        val targetBitrateBps: Int
    ) {
        val capabilityBound: Boolean
            get() = targetBitrateBps < codecPolicyCeilingBps

        fun telemetryLine(): String =
            "Proprietary NDR bitrate target " +
                "policyCeiling=$codecPolicyCeilingBps " +
                "minimumViable=$minimumViableBitrateBps " +
                "encoderAdmittedMaximum=$encoderAdmittedMaximumBps " +
                "target=$targetBitrateBps capabilityBound=$capabilityBound " +
                "admission=STRICT_DEVICE_MAX"
    }

    fun isAutomaticMain10Route(
        requestedDynamicRangeLabel: String?,
        eliteQualityTier: EliteVideoQualityTier?,
        strictHighEnd: Boolean,
        bt2020PqTransport: Boolean,
        realMain10Transport: Boolean,
        manualRateControlledDelivery: Boolean
    ): Boolean {
        if (!strictHighEnd || manualRateControlledDelivery) return false
        if (!bt2020PqTransport || !realMain10Transport) return false
        if (eliteQualityTier != EliteVideoQualityTier.ELITE_HDR_NDR10_PLUS_10BIT &&
            eliteQualityTier != EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT
        ) {
            return false
        }
        val proprietaryVersion = requestedDynamicRangeLabel
            ?.let(::explicitProprietaryVersionFromLabel)
            ?: return false
        return Bt2100TransportPolicy.isNeoBt2100HdrVersion(proprietaryVersion)
    }

    fun resolveMaximumAdmittedTarget(
        codecRequestCeilingBps: Long,
        minimumViableBitrateBps: Int,
        clampBitrateToEncoderCapabilities: (Int) -> Int
    ): Decision {
        val codecPolicyCeiling = codecRequestCeilingBps
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
        require(minimumViableBitrateBps > 0) {
            "Proprietary NDR quality floor must be positive ($minimumViableBitrateBps bps)"
        }
        require(minimumViableBitrateBps <= codecPolicyCeiling) {
            "Proprietary NDR quality floor $minimumViableBitrateBps bps exceeds the codec policy " +
                "ceiling of $codecPolicyCeiling bps; recording was refused instead of downgraded"
        }
        val encoderAdmittedMaximum = clampBitrateToEncoderCapabilities(codecPolicyCeiling)
        require(encoderAdmittedMaximum > 0) {
            "Proprietary NDR encoder reported an invalid bitrate capability " +
                "($encoderAdmittedMaximum bps)"
        }
        val target = encoderAdmittedMaximum.coerceAtMost(codecPolicyCeiling)
        require(target >= minimumViableBitrateBps) {
            "Proprietary NDR Main10 encoder supports only $target bps, below the minimum viable " +
                "quality floor of $minimumViableBitrateBps bps; recording was refused instead " +
                "of downgraded"
        }
        return Decision(
            codecPolicyCeilingBps = codecPolicyCeiling,
            minimumViableBitrateBps = minimumViableBitrateBps,
            encoderAdmittedMaximumBps = encoderAdmittedMaximum,
            targetBitrateBps = target
        )
    }

    private fun explicitProprietaryVersionFromLabel(label: String): String? {
        val token = label
            .trim()
            .uppercase(Locale.US)
            .replace("[^A-Z0-9+]".toRegex(), "")
        return when {
            token.contains("APEXSHARP") -> "APEX_SHARP"
            token.contains("APEXZERO") -> "APEX_ZERO"
            token.contains("APEXCINEMA") -> "APEX_CINEMA"
            token.contains("NIVUKX") -> "NIVUKXVISION"
            token.contains("NDR10+") || token.contains("NDR10PLUS") -> "NDR10+"
            else -> null
        }
    }
}
