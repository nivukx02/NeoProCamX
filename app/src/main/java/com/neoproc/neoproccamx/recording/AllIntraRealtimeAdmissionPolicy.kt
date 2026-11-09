package com.neoproc.neoproccamx.recording

/**
 * Quality-preserving admission gate for realtime all-intra encoding.
 *
 * A codec advertising a size/rate tuple does not prove it can encode every frame as a high-quality
 * I-frame at that rate. CP-066 device evidence showed exactly that failure: 3264x2448@25 was
 * advertised, but a 160 Mbit/s Main10 session emitted one approximately 5.25 MiB IDR every 120 ms.
 * The camera submitted every 40 ms, so the encoder produced only one third of the requested frames.
 *
 * This gate never reduces resolution, frame rate, bit depth, or quantizer quality. When the
 * configured bitrate cannot carry a conservative high-quality all-intra stream, the caller keeps
 * the same bitrate and quality controls but uses a one-second inter-predicted realtime GOP.
 */
internal object AllIntraRealtimeAdmissionPolicy {
    private const val TEN_BIT_BITS_PER_LUMA_NUMERATOR = 21L
    private const val TEN_BIT_BITS_PER_LUMA_DENOMINATOR = 4L // 5.25 bits/sample
    private const val EIGHT_BIT_BITS_PER_LUMA_NUMERATOR = 4L
    private const val EIGHT_BIT_BITS_PER_LUMA_DENOMINATOR = 1L

    const val REASON_ADMITTED = "ADMITTED"
    const val REASON_NOT_REQUESTED = "NOT_REQUESTED"
    const val REASON_CAPABILITY_UNPROVEN = "CAPABILITY_UNPROVEN"
    const val REASON_INVALID_FORMAT = "INVALID_FORMAT"
    const val REASON_INSUFFICIENT_REALTIME_BITRATE = "INSUFFICIENT_REALTIME_BITRATE"

    data class Decision(
        val admitted: Boolean,
        val reason: String,
        val minimumBitrateBps: Long,
        val configuredBitrateBps: Long,
        val lumaSamplesPerSecond: Long
    )

    fun resolve(
        requested: Boolean,
        hardwareSizeAndRateProven: Boolean,
        width: Int,
        height: Int,
        fps: Int,
        bitDepth: Int,
        configuredBitrateBps: Int
    ): Decision {
        if (!requested) {
            return Decision(
                admitted = false,
                reason = REASON_NOT_REQUESTED,
                minimumBitrateBps = 0L,
                configuredBitrateBps = configuredBitrateBps.toLong().coerceAtLeast(0L),
                lumaSamplesPerSecond = 0L
            )
        }
        if (width <= 0 || height <= 0 || fps <= 0 || configuredBitrateBps <= 0) {
            return Decision(
                admitted = false,
                reason = REASON_INVALID_FORMAT,
                minimumBitrateBps = Long.MAX_VALUE,
                configuredBitrateBps = configuredBitrateBps.toLong().coerceAtLeast(0L),
                lumaSamplesPerSecond = 0L
            )
        }

        val samplesPerSecond = saturatedMultiply(
            saturatedMultiply(width.toLong(), height.toLong()),
            fps.toLong()
        )
        val (numerator, denominator) = if (bitDepth >= 10) {
            TEN_BIT_BITS_PER_LUMA_NUMERATOR to TEN_BIT_BITS_PER_LUMA_DENOMINATOR
        } else {
            EIGHT_BIT_BITS_PER_LUMA_NUMERATOR to EIGHT_BIT_BITS_PER_LUMA_DENOMINATOR
        }
        val minimumBitrate = ceilDivide(
            saturatedMultiply(samplesPerSecond, numerator),
            denominator
        )
        val configured = configuredBitrateBps.toLong()
        val reason = when {
            !hardwareSizeAndRateProven -> REASON_CAPABILITY_UNPROVEN
            configured < minimumBitrate -> REASON_INSUFFICIENT_REALTIME_BITRATE
            else -> REASON_ADMITTED
        }
        return Decision(
            admitted = reason == REASON_ADMITTED,
            reason = reason,
            minimumBitrateBps = minimumBitrate,
            configuredBitrateBps = configured,
            lumaSamplesPerSecond = samplesPerSecond
        )
    }

    private fun saturatedMultiply(left: Long, right: Long): Long {
        if (left <= 0L || right <= 0L) return 0L
        return if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
    }

    private fun ceilDivide(numerator: Long, denominator: Long): Long {
        if (numerator == Long.MAX_VALUE) return Long.MAX_VALUE
        return numerator / denominator + if (numerator % denominator == 0L) 0L else 1L
    }
}
