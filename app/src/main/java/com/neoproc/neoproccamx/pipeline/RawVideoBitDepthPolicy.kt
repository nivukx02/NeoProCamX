package com.neoproc.neoproccamx.pipeline

internal object RawVideoBitDepthPolicy {

    /**
     * Resolve the storage bit depth for a RAW payload.
     *
     * @param requestedBitDepth   bit depth requested by the compression mode.
     * @param sourceWhiteLevel    sensor white level (`SENSOR_INFO_WHITE_LEVEL`).
     * @param preserveNativeDepth  when true the floor is raised to the sensor's native
     *   depth so a >12-bit sensor word is kept at full depth in a 16-bit container
     *   (no companding, no bit loss). DEVICE-GATED — defaults to false so the legacy
     *   12-bit floor (and the companding fallback) is unchanged on every device that
     *   does not opt in. Stills already preserve native depth via DngCreator; this gate
     *   lets a device that can sustain the throughput keep native depth on the video
     *   path too. See [RawCompandingCurve] for the reduced-container fallback.
     */
    fun resolveStorageBitDepth(
        requestedBitDepth: Int,
        sourceWhiteLevel: Int,
        preserveNativeDepth: Boolean = false
    ): Int {
        val requestedBits = requestedBitDepth.coerceIn(1, 16)
        val sourceBits = bitDepthForWhiteLevel(sourceWhiteLevel)
        val nativeFloor = if (preserveNativeDepth) sourceBits else minOf(sourceBits, 12)
        return maxOf(requestedBits, nativeFloor).coerceAtMost(16)
    }

    /**
     * Map sensor samples into the target container.
     *
     * - When the container already holds the sensor word (target >= 16 bit, or the
     *   sensor already fits) this is a clamp pass-through — byte-identical to the
     *   legacy behaviour (no-downgrade).
     * - When a smaller container is forced on a wider sensor, the mapping is the
     *   **monotone, invertible companding curve** in [RawCompandingCurve], *not* the
     *   legacy linear right-shift. The companding is reversible (a decoder reconstructs
     *   the sensor code within a bounded error) where the legacy truncation discarded
     *   the low bits irrecoverably. This is the CP16 fix.
     */
    fun normalizeSamplesForBitDepth(
        samples: IntArray,
        targetBitDepth: Int,
        sourceWhiteLevel: Int
    ): IntArray {
        if (samples.isEmpty()) return IntArray(0)
        return RawCompandingCurve.of(sourceWhiteLevel, targetBitDepth).encodeAll(samples)
    }

    /**
     * The companding type ([RawCompandingCurve.COMPANDING_NONE] /
     * [RawCompandingCurve.COMPANDING_PERCEPTUAL_LOG]) that [normalizeSamplesForBitDepth]
     * will apply for the given target/source. Used by the serializer to stamp the frame
     * header so the decoder knows whether — and how — to invert the mapping.
     */
    fun compandingTypeForBitDepth(targetBitDepth: Int, sourceWhiteLevel: Int): Int =
        RawCompandingCurve.of(sourceWhiteLevel, targetBitDepth).type

    /**
     * Whether the RAW VIDEO path should preserve the sensor's native depth (keeping a >12-bit sensor
     * word at full depth in a 16-bit container) instead of the legacy 12-bit floor + companding.
     *
     * DEVICE-GATED and no-downgrade: only true when strict high-end is active on capable hardware AND
     * the sensor genuinely exceeds 12 bits. A ≤12-bit sensor (the common case) and any non-strict /
     * unsupported device therefore stay byte-identical to the legacy path — this can only ADD depth,
     * never remove it. Stills already preserve native depth via DngCreator; this lets the video path
     * match on a device that can sustain the throughput.
     */
    fun shouldPreserveNativeVideoDepth(
        strictHighEnd: Boolean,
        highEndHardwareSupported: Boolean,
        sourceWhiteLevel: Int
    ): Boolean =
        strictHighEnd &&
            highEndHardwareSupported &&
            bitDepthForWhiteLevel(sourceWhiteLevel) > 12

    private fun bitDepthForWhiteLevel(sourceWhiteLevel: Int): Int {
        val whiteLevel = sourceWhiteLevel.coerceIn(1, 0xFFFF)
        var bits = 1
        var maxValue = 1
        while (bits < 16 && maxValue < whiteLevel) {
            bits++
            maxValue = (1 shl bits) - 1
        }
        return bits
    }
}
