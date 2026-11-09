package com.neoproc.neoproccamx.recording

/**
 * Selects the highest bitrate a real encoder advertises for direct, playable SDR delivery.
 *
 * A Cinema Cam master and a Gallery-ready inter-frame clip are different delivery formats. The
 * latter must not inherit a multi-gigabit all-intra master floor that no mobile encoder can accept.
 * This policy does not reduce a supported rate: it asks for the codec policy ceiling, records the
 * exact profile-compatible maximum returned by the capability probe, and makes that value the
 * strict session target.
 */
internal object DirectSdrGalleryBitratePolicy {
    data class Decision(
        val codecPolicyCeilingBps: Int,
        val encoderAdmittedMaximumBps: Int,
        val targetBitrateBps: Int
    ) {
        val capabilityBound: Boolean
            get() = targetBitrateBps < codecPolicyCeilingBps

        fun telemetryLine(): String =
            "Direct SDR Gallery bitrate target " +
                "policyCeiling=$codecPolicyCeilingBps " +
                "encoderAdmittedMaximum=$encoderAdmittedMaximumBps " +
                "target=$targetBitrateBps capabilityBound=$capabilityBound " +
                "admission=STRICT_DEVICE_MAX"
    }

    fun resolveMaximumAdmittedTarget(
        codecRequestCeilingBps: Long,
        clampBitrateToEncoderCapabilities: (Int) -> Int
    ): Decision {
        val codecPolicyCeiling = codecRequestCeilingBps
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
        val encoderAdmittedMaximum = clampBitrateToEncoderCapabilities(codecPolicyCeiling)
        require(encoderAdmittedMaximum > 0) {
            "Direct SDR encoder reported an invalid bitrate capability " +
                "($encoderAdmittedMaximum bps)"
        }
        val target = encoderAdmittedMaximum.coerceAtMost(codecPolicyCeiling)
        return Decision(
            codecPolicyCeilingBps = codecPolicyCeiling,
            encoderAdmittedMaximumBps = encoderAdmittedMaximum,
            targetBitrateBps = target
        )
    }
}
