package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.HdrDynamicRangePolicy

/** Transfer carried by the camera buffer before the project-owned NDR operator. */
internal enum class NdrInputTransfer(val shaderCode: Int) {
    SDR(0),
    PQ(1),
    HLG(2),
    /** Scene-linear light exported by the project-owned RAW Vulkan demosaic. */
    NEO_SENSOR_LINEAR(3),
    /** Project-owned packed 10-bit scene-log fallback; decoded to ACEScg before NDR processing. */
    NEO_SCENE_LOG10(4)
}

internal enum class NdrInputPrimaries {
    UNKNOWN,
    BT709,
    BT2020,
    /** ACES AP1 primaries in the project-owned linear working space. */
    ACESCG
}

/** Quantization range carried by the camera buffer before YUV -> RGB import. */
internal enum class NdrInputRange(val shaderCode: Int) {
    UNKNOWN(0),
    FULL(1),
    LIMITED(2),
    EXTENDED(3)
}

/** Per-frame admission decision for the project-owned NDR delivery grade. */
internal enum class NdrInputRenderPath(val shaderCode: Int) {
    TRANSPORT_BYPASS(0),
    NDR_GRADE(1)
}

/**
 * Visible-preview-only admission for the project-owned NDR look.
 *
 * Recording remains restricted to [NdrInputTransportDecision.isStrictNdrTransport]. A standard
 * BT.709 Camera2 frame may drive only the live monitor approximation after its real transfer and
 * primaries have been decoded. HLG/PQ never enter the NDR grade, so vendor HDR tone processing
 * cannot become part of NDR10+ or NIVUKX Vision.
 */
internal object NdrPreviewInputAdmissionPolicy {
    fun isSupported(decision: NdrInputTransportDecision): Boolean =
        decision.isStrictNdrTransport ||
            (decision.transfer == NdrInputTransfer.SDR &&
                decision.primaries == NdrInputPrimaries.BT709)

    fun renderPath(decision: NdrInputTransportDecision): NdrInputRenderPath =
        if (isSupported(decision)) {
            NdrInputRenderPath.NDR_GRADE
        } else {
            NdrInputRenderPath.TRANSPORT_BYPASS
        }

    /** Safe pre-frame gate for constructing the compositor before dataspace is available. */
    fun canPrepareFromCameraProfile(profile: Long?): Boolean =
        profile == null || profile == HdrDynamicRangePolicy.PROFILE_STANDARD
}

internal data class NdrInputTransportDecision(
    val transfer: NdrInputTransfer,
    val primaries: NdrInputPrimaries,
    val range: NdrInputRange,
    val dataSpaceKnown: Boolean,
    val source: String
) {
    /** Exact project-owned sensor-linear ingress; this does not describe the final PQ output. */
    val isStrictNdrTransport: Boolean
        get() = (transfer == NdrInputTransfer.NEO_SENSOR_LINEAR ||
            transfer == NdrInputTransfer.NEO_SCENE_LOG10) &&
            primaries == NdrInputPrimaries.ACESCG

    /**
     * Valid high-precision input to the project-owned NDR/NIVUKX operator. Camera2 HLG/PQ buffers
     * have already passed through vendor ISP transfer/tone processing and are therefore deliberately
     * ineligible. Only the RAW-derived, project-owned linear ACEScg frame can enter the NDR grade.
     */
    val isSupportedNdrInput: Boolean
        get() = isStrictNdrTransport

    val renderPath: NdrInputRenderPath
        get() = if (isSupportedNdrInput) {
            NdrInputRenderPath.NDR_GRADE
        } else {
            NdrInputRenderPath.TRANSPORT_BYPASS
        }
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
     * Before Camera2 opens, the resolved profile is a preparation hint only. Once a session exists,
     * only a profile binding committed for that exact session generation is transport truth. This
     * prevents a null/failed STANDARD binding from being silently promoted to requested PQ.
     */
    fun effectivePreviewProfile(
        appliedPreviewProfile: Long?,
        resolvedProfileForCurrentMode: Long?,
        sessionConfigured: Boolean,
        appliedProfileTruthCurrent: Boolean
    ): Long? = when {
        !sessionConfigured -> resolvedProfileForCurrentMode
        appliedProfileTruthCurrent -> appliedPreviewProfile
        else -> null
    }

    fun resolve(
        dataSpace: Int,
        appliedProfile: Long?,
        @Suppress("UNUSED_PARAMETER") preferPqWhenUnknown: Boolean,
        sensorLinearAcesCg: Boolean = false,
        sceneLog10AcesCg: Boolean = false
    ): NdrInputTransportDecision {
        require(!(sensorLinearAcesCg && sceneLog10AcesCg)) {
            "A frame cannot be both FP16 linear and packed scene-log"
        }
        if (sensorLinearAcesCg) return sensorLinearAcesCg()
        if (sceneLog10AcesCg) return sceneLog10AcesCg()

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
            // Unknown input is never promoted to PQ. Doing so would decode ordinary SDR as ST.2084.
            else -> NdrInputTransfer.SDR
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

    /** Admission token for the FP16 AHB exported directly by the RAW Vulkan demosaic. */
    fun sensorLinearAcesCg(): NdrInputTransportDecision =
        NdrInputTransportDecision(
            transfer = NdrInputTransfer.NEO_SENSOR_LINEAR,
            primaries = NdrInputPrimaries.ACESCG,
            range = NdrInputRange.EXTENDED,
            dataSpaceKnown = true,
            source = "VULKAN_RAW_DEMOSAIC_FP16"
        )

    /** Admission token for the full-resolution RGB10_A2 fallback exported by the RAW Vulkan path. */
    fun sceneLog10AcesCg(): NdrInputTransportDecision =
        NdrInputTransportDecision(
            transfer = NdrInputTransfer.NEO_SCENE_LOG10,
            primaries = NdrInputPrimaries.ACESCG,
            range = NdrInputRange.FULL,
            dataSpaceKnown = true,
            source = "VULKAN_RAW_DEMOSAIC_NEO_SCENE_LOG10"
        )

    fun isPqProfile(profile: Long?): Boolean =
        profile == HdrDynamicRangePolicy.PROFILE_HDR10 ||
            profile == HdrDynamicRangePolicy.PROFILE_HDR10_PLUS

    /** Camera2 dynamic-range profiles are never valid proprietary NDR ingress. */
    @Suppress("UNUSED_PARAMETER")
    fun isSupportedNdrInputProfile(profile: Long?): Boolean = false
}
