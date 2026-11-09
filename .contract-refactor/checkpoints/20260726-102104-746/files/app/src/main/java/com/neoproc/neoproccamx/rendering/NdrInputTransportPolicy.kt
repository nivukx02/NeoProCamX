package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.HdrDynamicRangePolicy

/** Transfer carried by the camera buffer before the project-owned NDR operator. */
internal enum class NdrInputTransfer(val shaderCode: Int) {
    SDR(0),
    PQ(1),
    HLG(2)
}

internal enum class NdrInputPrimaries {
    UNKNOWN,
    BT709,
    BT2020
}

/** Quantization range carried by the camera buffer before YUV -> RGB import. */
internal enum class NdrInputRange(val shaderCode: Int) {
    UNKNOWN(0),
    FULL(1),
    LIMITED(2),
    EXTENDED(3)
}

internal data class NdrInputTransportDecision(
    val transfer: NdrInputTransfer,
    val primaries: NdrInputPrimaries,
    val range: NdrInputRange,
    val dataSpaceKnown: Boolean,
    val source: String
) {
    /** NDR/NDR10+/NIVUKX may enter the delivery operator only as genuine BT.2020 PQ. */
    val isStrictNdrTransport: Boolean
        get() = transfer == NdrInputTransfer.PQ && primaries == NdrInputPrimaries.BT2020
}

/**
 * Resolves the input from the buffer's real Android dataspace first, then from the Camera2 profile.
 *
 * The old path used a process-global "PQ=true" assumption. If Camera2 supplied STANDARD YUV after a
 * session fallback, that decoded Rec.709/SMPTE-170M pixels with ST.2084 and treated them as BT.2020,
 * which produces the reported red cast and broken exposure. Dataspace is per-frame truth and therefore
 * always wins. The profile is used only when an opaque buffer reports DATASPACE_UNKNOWN.
 */
internal object NdrInputTransportPolicy {
    // android.hardware.DataSpace bit layout. Kept as pure constants so host tests do not invoke Android
    // framework stubs. Values are public Android API constants (API 33+).
    private const val STANDARD_MASK = 0x003F0000
    private const val TRANSFER_MASK = 0x07C00000
    private const val RANGE_MASK = 0x18000000
    private const val STANDARD_BT709 = 0x00010000
    private const val STANDARD_BT2020 = 0x00060000
    private const val TRANSFER_SRGB = 0x00800000
    private const val TRANSFER_SMPTE_170M = 0x00C00000
    private const val TRANSFER_ST2084 = 0x01C00000
    private const val TRANSFER_HLG = 0x02000000
    private const val RANGE_FULL = 0x08000000
    private const val RANGE_LIMITED = 0x10000000
    private const val RANGE_EXTENDED = 0x18000000

    const val DATASPACE_UNKNOWN = 0
    const val DATASPACE_BT2020_PQ = 163_971_072
    /** BT.2020 + ST2084 + limited range, observed from the I2405 HDR10+ camera stream. */
    const val DATASPACE_BT2020_PQ_LIMITED = 298_188_800
    const val DATASPACE_BT2020_HLG = 168_165_376
    const val DATASPACE_BT709 = 281_083_904

    /**
     * During the deliberate Camera2→EGL surface handoff the replacement session temporarily has no
     * PREVIEW output, so CameraController's last-applied preview profile is null. Use the profile
     * resolved for the requested NDR mode only for that absence; an explicitly applied STANDARD
     * profile remains STANDARD and must never be disguised as PQ.
     */
    fun effectivePreviewProfile(
        appliedPreviewProfile: Long?,
        resolvedProfileForCurrentMode: Long?
    ): Long? = appliedPreviewProfile ?: resolvedProfileForCurrentMode

    fun resolve(
        dataSpace: Int,
        appliedProfile: Long?,
        preferPqWhenUnknown: Boolean
    ): NdrInputTransportDecision {
        if (dataSpace != DATASPACE_UNKNOWN) {
            val transfer = when (dataSpace and TRANSFER_MASK) {
                TRANSFER_ST2084 -> NdrInputTransfer.PQ
                TRANSFER_HLG -> NdrInputTransfer.HLG
                TRANSFER_SRGB, TRANSFER_SMPTE_170M -> NdrInputTransfer.SDR
                else -> null
            }
            if (transfer != null) {
                val primaries = when (dataSpace and STANDARD_MASK) {
                    STANDARD_BT2020 -> NdrInputPrimaries.BT2020
                    STANDARD_BT709 -> NdrInputPrimaries.BT709
                    else -> NdrInputPrimaries.UNKNOWN
                }
                val range = when (dataSpace and RANGE_MASK) {
                    RANGE_FULL -> NdrInputRange.FULL
                    RANGE_LIMITED -> NdrInputRange.LIMITED
                    RANGE_EXTENDED -> NdrInputRange.EXTENDED
                    else -> NdrInputRange.UNKNOWN
                }
                return NdrInputTransportDecision(
                    transfer = transfer,
                    primaries = primaries,
                    range = range,
                    dataSpaceKnown = true,
                    source = "BUFFER_DATASPACE"
                )
            }
        }

        val profileTransfer = when (appliedProfile) {
            HdrDynamicRangePolicy.PROFILE_HDR10,
            HdrDynamicRangePolicy.PROFILE_HDR10_PLUS -> NdrInputTransfer.PQ
            HdrDynamicRangePolicy.PROFILE_HLG10 -> NdrInputTransfer.HLG
            else -> if (preferPqWhenUnknown) NdrInputTransfer.PQ else NdrInputTransfer.SDR
        }
        return NdrInputTransportDecision(
            transfer = profileTransfer,
            primaries = if (profileTransfer == NdrInputTransfer.SDR) {
                NdrInputPrimaries.BT709
            } else {
                NdrInputPrimaries.BT2020
            },
            // Camera2 dynamic-range profiles declare transfer/primaries, not YUV quantization range.
            // Keep this unknown instead of guessing; explicit YUV import engages only with per-buffer truth.
            range = NdrInputRange.UNKNOWN,
            dataSpaceKnown = false,
            source = if (appliedProfile != null) "CAMERA2_PROFILE" else "UNKNOWN_FALLBACK"
        )
    }

    fun isPqProfile(profile: Long?): Boolean =
        profile == HdrDynamicRangePolicy.PROFILE_HDR10 ||
            profile == HdrDynamicRangePolicy.PROFILE_HDR10_PLUS
}
