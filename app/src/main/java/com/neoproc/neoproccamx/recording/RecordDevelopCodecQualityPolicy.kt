package com.neoproc.neoproccamx.recording

import kotlin.math.roundToLong

internal data class RecordDevelopEncoderElitePlan(
    /** Device-independent ideal bitrate target computed from geometry/rate. */
    val policyTargetBitrate: Int,
    /** High-quality floor that an encoder must meet before it can be selected. */
    val minimumViableBitrate: Int,
    /** Bitrate actually sent to MediaCodec; equals the ideal target or the exact device ceiling. */
    val configuredBitrate: Int,
    /** Advertised upper bound of the selected codec, retained separately for evidence. */
    val advertisedBitrateCeiling: Int,
    val quality: Int,
    val complexity: Int?,
    val operatingRate: Int,
    val maximumQp: Int
) {
    val capabilityBound: Boolean
        get() = configuredBitrate < policyTargetBitrate
}

internal enum class RecordDevelopEncoderRateControl {
    CBR,
    VBR,
    CQ
}

internal enum class RecordDevelopEncoderCapabilityRejection {
    MAIN10_UNSUPPORTED,
    P010_UNSUPPORTED,
    GEOMETRY_OR_RATE_UNSUPPORTED,
    INVALID_BITRATE_RANGE,
    RATE_CONTROL_UNSUPPORTED,
    BITRATE_CEILING_BELOW_MINIMUM_VIABLE
}

/** Side-effect-free facts for one advertised HEVC encoder. */
internal data class RecordDevelopEncoderCapabilityCandidate(
    val name: String,
    val hardwareAccelerated: Boolean,
    val vendor: Boolean,
    val supportsMain10: Boolean,
    val supportsP010: Boolean,
    val supportsRequestedGeometryAndRate: Boolean,
    val bitrateLower: Int,
    val bitrateUpper: Int,
    val rateControlModes: Set<RecordDevelopEncoderRateControl>
) {
    val preferredRateControl: RecordDevelopEncoderRateControl?
        get() = when {
            RecordDevelopEncoderRateControl.CBR in rateControlModes ->
                RecordDevelopEncoderRateControl.CBR
            RecordDevelopEncoderRateControl.VBR in rateControlModes ->
                RecordDevelopEncoderRateControl.VBR
            else -> null
        }
}

internal data class RecordDevelopEncoderCapabilityStatus(
    val candidate: RecordDevelopEncoderCapabilityCandidate,
    val rejections: List<RecordDevelopEncoderCapabilityRejection>,
    val selected: Boolean
) {
    val capable: Boolean
        get() = rejections.isEmpty()

    fun evidenceString(): String = buildString {
        append(candidate.name)
        append("[max=")
        append(candidate.bitrateUpper)
        append(",modes=")
        append(candidate.rateControlModes.sortedBy { it.name }.joinToString("+"))
        append(",status=")
        append(
            when {
                selected -> "SELECTED"
                capable -> "CAPABLE_NOT_SELECTED"
                else -> rejections.joinToString("+") { it.name }
            }
        )
        append(']')
    }
}

/**
 * Read-only strict admission result. It contains only advertised facts and never creates/configures a
 * codec. [bestAdvertisedEligibleBitrateBps] is taken from candidates that already satisfy Main10,
 * P010, exact geometry/rate, a valid bitrate range, and CBR/VBR rate control; it remains visible when
 * every such candidate is below the quality floor.
 */
internal data class RecordDevelopEncoderStrictCapabilityReport(
    val width: Int,
    val height: Int,
    val fps: Int,
    /** Device-independent ideal target; retained even when hardware is capability-bound. */
    val requiredBitrateBps: Int,
    /** Lowest bitrate that preserves the strict high-quality HEVC delivery contract. */
    val minimumViableBitrateBps: Int,
    val bestAdvertisedEligibleBitrateBps: Int,
    val selectedCandidateName: String?,
    val selectedRateControl: RecordDevelopEncoderRateControl?,
    val selectedConfiguredBitrateBps: Int?,
    val candidates: List<RecordDevelopEncoderCapabilityStatus>
) {
    val accepted: Boolean
        get() = selectedCandidateName != null

    val capabilityBound: Boolean
        get() = accepted &&
            requireNotNull(selectedConfiguredBitrateBps) < requiredBitrateBps

    fun requireAccepted(): RecordDevelopEncoderStrictCapabilityReport {
        check(accepted) { rejectionMessage() }
        return this
    }

    fun rejectionMessage(): String =
        "Strict HEVC Main10/P010 admission rejected for ${width}x${height}@${fps}: " +
            "requiredBitrateBps=$requiredBitrateBps, " +
            "minimumViableBitrateBps=$minimumViableBitrateBps, " +
            "bestAdvertisedEligibleBitrateBps=$bestAdvertisedEligibleBitrateBps, " +
            "candidates=${candidates.joinToString(separator = ";") { it.evidenceString() }}. " +
            "No encoder satisfies Main10, P010, exact geometry/rate, the minimum viable bitrate, " +
            "and CBR/VBR rate control. HEVC was refused before MediaCodec configuration; " +
            "the output transport was not substituted."
}

/** Strict, deterministic quality contract for offline SDR/NDR/RAW-to-LOG HEVC delivery. */
internal object RecordDevelopEncoderQualityPolicy {
    private const val BITS_PER_PIXEL_PER_FRAME = 1.35
    private const val MINIMUM_VIABLE_BITS_PER_PIXEL_PER_FRAME = 0.45
    const val MINIMUM_BITRATE = 200_000_000
    const val MINIMUM_VIABLE_BITRATE = 80_000_000
    const val ELITE_QUALITY = 100
    const val ELITE_MAXIMUM_QP = 18

    fun requiredBitrate(width: Int, height: Int, fps: Int): Int {
        require(width > 0 && height > 0 && fps > 0) {
            "Invalid HEVC quality geometry ${width}x${height}@${fps}"
        }
        val required = (width.toDouble() * height.toDouble() * fps.toDouble() *
            BITS_PER_PIXEL_PER_FRAME)
            .roundToLong()
            .coerceAtLeast(MINIMUM_BITRATE.toLong())
        check(required <= Int.MAX_VALUE.toLong()) {
            "HEVC quality floor $required exceeds MediaCodec's integer bitrate limit for " +
                "${width}x${height}@${fps}; bitrate was not clamped"
        }
        return required.toInt()
    }

    /**
     * High-quality admission floor for a real hardware Main10 encoder. The larger policy target is
     * never erased: it remains in reports/certificates, while a capability-bound device must run at
     * its exact advertised maximum and still clear this geometry-scaled floor. This matches the live
     * recorder's STRICT_DEVICE_MAX contract and prevents the former all-HEVC-conversions-fail state
     * on otherwise valid 160 Mbps flagship encoders.
     */
    fun minimumViableBitrate(width: Int, height: Int, fps: Int): Int {
        require(width > 0 && height > 0 && fps > 0) {
            "Invalid HEVC quality geometry ${width}x${height}@${fps}"
        }
        val minimum = (width.toDouble() * height.toDouble() * fps.toDouble() *
            MINIMUM_VIABLE_BITS_PER_PIXEL_PER_FRAME)
            .roundToLong()
            .coerceAtLeast(MINIMUM_VIABLE_BITRATE.toLong())
        check(minimum <= Int.MAX_VALUE.toLong()) {
            "HEVC minimum viable bitrate $minimum exceeds MediaCodec's integer bitrate limit for " +
                "${width}x${height}@${fps}"
        }
        return minimum.toInt()
    }

    fun strictCapabilityReport(
        width: Int,
        height: Int,
        fps: Int,
        candidates: List<RecordDevelopEncoderCapabilityCandidate>
    ): RecordDevelopEncoderStrictCapabilityReport {
        val required = requiredBitrate(width, height, fps)
        val minimumViable = minimumViableBitrate(width, height, fps)
        val provisional = candidates.map { candidate ->
            val rejections = buildList {
                if (!candidate.supportsMain10) {
                    add(RecordDevelopEncoderCapabilityRejection.MAIN10_UNSUPPORTED)
                }
                if (!candidate.supportsP010) {
                    add(RecordDevelopEncoderCapabilityRejection.P010_UNSUPPORTED)
                }
                if (!candidate.supportsRequestedGeometryAndRate) {
                    add(RecordDevelopEncoderCapabilityRejection.GEOMETRY_OR_RATE_UNSUPPORTED)
                }
                if (candidate.bitrateLower <= 0 || candidate.bitrateUpper < candidate.bitrateLower) {
                    add(RecordDevelopEncoderCapabilityRejection.INVALID_BITRATE_RANGE)
                }
                if (candidate.preferredRateControl == null) {
                    add(RecordDevelopEncoderCapabilityRejection.RATE_CONTROL_UNSUPPORTED)
                }
                if (candidate.bitrateUpper < minimumViable) {
                    add(RecordDevelopEncoderCapabilityRejection.BITRATE_CEILING_BELOW_MINIMUM_VIABLE)
                }
            }
            RecordDevelopEncoderCapabilityStatus(
                candidate = candidate,
                rejections = rejections,
                selected = false
            )
        }
        val selected = provisional
            .asSequence()
            .filter { it.capable }
            .sortedWith(
                compareByDescending<RecordDevelopEncoderCapabilityStatus> {
                    it.candidate.preferredRateControl == RecordDevelopEncoderRateControl.CBR
                }
                    .thenByDescending { it.candidate.hardwareAccelerated }
                    .thenByDescending { it.candidate.vendor }
                    .thenByDescending { it.candidate.bitrateUpper }
                    .thenBy { it.candidate.name }
            )
            .firstOrNull()
        val selectedName = selected?.candidate?.name
        val statuses = provisional.map { status ->
            if (status.candidate.name == selectedName) status.copy(selected = true) else status
        }
        val bestAdvertisedEligible = provisional
            .asSequence()
            .filter { status ->
                status.rejections.none {
                    it != RecordDevelopEncoderCapabilityRejection.BITRATE_CEILING_BELOW_MINIMUM_VIABLE
                }
            }
            .maxOfOrNull { it.candidate.bitrateUpper }
            ?: 0
        return RecordDevelopEncoderStrictCapabilityReport(
            width = width,
            height = height,
            fps = fps,
            requiredBitrateBps = required,
            minimumViableBitrateBps = minimumViable,
            bestAdvertisedEligibleBitrateBps = bestAdvertisedEligible,
            selectedCandidateName = selectedName,
            selectedRateControl = selected?.candidate?.preferredRateControl,
            selectedConfiguredBitrateBps = selected?.candidate?.let { candidate ->
                minOf(required, candidate.bitrateUpper).coerceAtLeast(candidate.bitrateLower)
            },
            candidates = statuses
        )
    }

    fun resolve(
        width: Int,
        height: Int,
        fps: Int,
        bitrateLower: Int,
        bitrateUpper: Int,
        complexityLower: Int? = null,
        complexityUpper: Int? = null
    ): RecordDevelopEncoderElitePlan {
        val policyTarget = requiredBitrate(width, height, fps)
        val minimumViable = minimumViableBitrate(width, height, fps)
        require(bitrateLower > 0 && bitrateUpper >= bitrateLower) {
            "Invalid HEVC bitrate range $bitrateLower..$bitrateUpper"
        }
        check(bitrateUpper >= minimumViable) {
            "HEVC bitrate ceiling $bitrateUpper is below minimum viable $minimumViable for " +
                "${width}x${height}@${fps}; conversion was refused"
        }
        val configured = minOf(policyTarget, bitrateUpper).coerceAtLeast(bitrateLower)
        val complexity = if (complexityLower != null && complexityUpper != null) {
            complexityUpper.takeIf { it >= complexityLower }
        } else {
            null
        }
        return RecordDevelopEncoderElitePlan(
            policyTargetBitrate = policyTarget,
            minimumViableBitrate = minimumViable,
            configuredBitrate = configured,
            advertisedBitrateCeiling = bitrateUpper,
            quality = ELITE_QUALITY,
            complexity = complexity,
            operatingRate = fps,
            maximumQp = ELITE_MAXIMUM_QP
        )
    }
}

internal data class RecordDevelopDecoderCandidate(
    val name: String,
    val hardwareAccelerated: Boolean,
    val vendor: Boolean,
    val supportsMain10: Boolean,
    val supportsP010: Boolean,
    val supportsRequestedGeometry: Boolean
)

/** No silent 8-bit decoder fallback: only a genuine Main10 + P010 path is eligible. */
internal object RecordDevelopDecoderQualityPolicy {
    fun select(candidates: List<RecordDevelopDecoderCandidate>): RecordDevelopDecoderCandidate? =
        candidates
            .asSequence()
            .filter { it.supportsMain10 && it.supportsP010 && it.supportsRequestedGeometry }
            .sortedWith(
                compareByDescending<RecordDevelopDecoderCandidate> { it.hardwareAccelerated }
                    .thenByDescending { it.vendor }
                    .thenBy { it.name }
            )
            .firstOrNull()

    fun requireOutput(
        expectedWidth: Int,
        expectedHeight: Int,
        actualWidth: Int,
        actualHeight: Int,
        p010: Boolean,
        strideBytes: Int,
        sliceHeight: Int
    ) {
        require(actualWidth == expectedWidth && actualHeight == expectedHeight) {
            "Decoder geometry changed ${actualWidth}x${actualHeight}; expected ${expectedWidth}x${expectedHeight}"
        }
        require(p010) { "Decoder output is not 10-bit P010" }
        require(strideBytes >= expectedWidth * Short.SIZE_BYTES) {
            "Decoder P010 stride $strideBytes is smaller than ${expectedWidth * Short.SIZE_BYTES}"
        }
        require(sliceHeight >= expectedHeight) {
            "Decoder slice height $sliceHeight is smaller than $expectedHeight"
        }
    }

    fun requireMonotonicPts(previousPtsUs: Long?, currentPtsUs: Long) {
        require(previousPtsUs == null || currentPtsUs > previousPtsUs) {
            "Decoder PTS is not strictly monotonic previous=$previousPtsUs current=$currentPtsUs"
        }
    }
}
