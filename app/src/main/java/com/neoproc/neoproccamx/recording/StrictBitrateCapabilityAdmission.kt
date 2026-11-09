package com.neoproc.neoproccamx.recording

/**
 * Admission boundary between the quality planner and a real encoder bitrate range.
 *
 * A capability clamp is useful in compatibility mode. In strict-high-end mode it would silently
 * turn a proved quality floor into a lower-quality recording, so the session must be rejected before
 * MediaCodec is configured. The requested and available rates stay separate for truthful telemetry.
 */
internal object StrictBitrateCapabilityAdmission {
    enum class Status {
        ACCEPTED,
        REJECTED_INVALID_CAPABILITY,
        REJECTED_BELOW_REQUIRED_FLOOR
    }

    data class Decision(
        val status: Status,
        val requiredBitrateBps: Int,
        val availableBitrateBps: Int
    ) {
        val accepted: Boolean
            get() = status == Status.ACCEPTED

        fun rejectionMessage(scope: String): String = when (status) {
            Status.ACCEPTED -> ""
            Status.REJECTED_INVALID_CAPABILITY ->
                "$scope reported an invalid bitrate capability ($availableBitrateBps bps)"
            Status.REJECTED_BELOW_REQUIRED_FLOOR ->
                "$scope supports only $availableBitrateBps bps, below the strict quality floor " +
                    "of $requiredBitrateBps bps; recording was refused instead of downgraded"
        }
    }

    fun resolve(
        strictHighEnd: Boolean,
        requiredBitrateBps: Int,
        availableBitrateBps: Int
    ): Decision {
        val status = when {
            requiredBitrateBps <= 0 || availableBitrateBps <= 0 ->
                Status.REJECTED_INVALID_CAPABILITY
            strictHighEnd && availableBitrateBps < requiredBitrateBps ->
                Status.REJECTED_BELOW_REQUIRED_FLOOR
            else -> Status.ACCEPTED
        }
        return Decision(
            status = status,
            requiredBitrateBps = requiredBitrateBps,
            availableBitrateBps = availableBitrateBps
        )
    }
}
