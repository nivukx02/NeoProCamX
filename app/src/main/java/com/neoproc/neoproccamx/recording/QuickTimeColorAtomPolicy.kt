package com.neoproc.neoproccamx.recording

import android.media.MediaFormat

/**
 * ISO/IEC 14496-12 / QuickTime `colr` atom values for `nclx` colour metadata.
 *
 * The encoder MediaFormat and MOV container must agree. Otherwise players can
 * infer HDR from a Main10 HEVC stream even when the app intentionally records
 * SDR or LOG SDR.
 */
internal data class QuickTimeColorAtomConfig(
    val colorPrimaries: Int,
    val transferCharacteristics: Int,
    val matrixCoefficients: Int,
    val fullRange: Boolean,
    // HDR10 static metadata (ST.2086 mastering display + content light level). Non-null ONLY for a PQ
    // (ST.2084) BT.2020 stream — the writer then emits the `mdcv` + `clli` boxes so players recognise
    // the file as HDR10 and engage HDR tone-mapping instead of showing the PQ content flat/dim.
    val masteringDisplay: HdrStaticMetadataPolicy.MasteringDisplay? = null
) {
    companion object {
        fun rec709SdrLimited(): QuickTimeColorAtomConfig =
            QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT709,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT709,
                fullRange = false
            )

        fun displayP3SdrLimited(): QuickTimeColorAtomConfig =
            QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_DISPLAY_P3,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_SRGB,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT709,
                fullRange = false
            )
    }
}

/** Exact pixel-domain mapping used by NIV99's direct P010 conversion kernel. */
internal fun QuickTimeColorAtomConfig.toNiv99ColorEncoding(): Npv10Niv99ColorEncoding {
    val matrix = when (matrixCoefficients) {
        QuickTimeColorAtomPolicy.MATRIX_BT709 -> Npv10Niv99ColorMatrix.BT709
        QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL -> Npv10Niv99ColorMatrix.BT2020_NCL
        else -> error("NIV99 does not support P010 matrix coefficient $matrixCoefficients")
    }
    val transfer = when (transferCharacteristics) {
        QuickTimeColorAtomPolicy.TRANSFER_BT709 -> Npv10Niv99Transfer.BT709
        QuickTimeColorAtomPolicy.TRANSFER_SRGB -> Npv10Niv99Transfer.SRGB
        QuickTimeColorAtomPolicy.TRANSFER_HLG -> Npv10Niv99Transfer.HLG
        QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084 -> Npv10Niv99Transfer.PQ
        else -> error("NIV99 does not support P010 transfer characteristic $transferCharacteristics")
    }
    return Npv10Niv99ColorEncoding(
        matrix = matrix,
        transfer = transfer,
        limitedRange = !fullRange
    )
}

internal object QuickTimeColorAtomPolicy {
    const val PRIMARIES_BT709 = 1
    const val PRIMARIES_BT2020 = 9
    const val PRIMARIES_DISPLAY_P3 = 12
    const val TRANSFER_BT709 = 1
    const val TRANSFER_SRGB = 13
    const val TRANSFER_PQ_ST2084 = 16
    const val TRANSFER_HLG = 18
    /** ISO/IEC 23001-8 identity matrix for RGB samples such as r210. */
    const val MATRIX_RGB_IDENTITY = 0
    const val MATRIX_BT709 = 1
    const val MATRIX_BT2020_NCL = 9

    /**
     * General conversion/export mapping. Live recording must use the two-argument overload so the
     * MOV track receives the exact mastering metadata that was attached to its encoder session.
     */
    fun fromRecordingColorEncoding(colorEncoding: RecordingColorEncodingConfig): QuickTimeColorAtomConfig =
        buildConfig(
            colorEncoding = colorEncoding,
            pqMasteringDisplay = HdrStaticMetadataPolicy.resolveMasteringDisplay(
                HdrStaticMetadataPolicy.StaticMetadataMode.HDR10
            ),
            requirePqSessionMetadata = false
        )

    /**
     * Live-recording mapping. A PQ session is not allowed to silently substitute the generic
     * 1,000-nit HDR10 preset: the container metadata must be the exact metadata used to configure
     * MediaCodec, or track publication fails closed.
     */
    fun fromRecordingColorEncoding(
        colorEncoding: RecordingColorEncodingConfig,
        sessionMasteringDisplay: HdrStaticMetadataPolicy.MasteringDisplay?
    ): QuickTimeColorAtomConfig =
        buildConfig(
            colorEncoding = colorEncoding,
            pqMasteringDisplay = sessionMasteringDisplay,
            requirePqSessionMetadata = true
        )

    private fun buildConfig(
        colorEncoding: RecordingColorEncodingConfig,
        pqMasteringDisplay: HdrStaticMetadataPolicy.MasteringDisplay?,
        requirePqSessionMetadata: Boolean
    ): QuickTimeColorAtomConfig {
        val bt2020 = colorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020
        val pqTransfer = colorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084
        if (requirePqSessionMetadata && pqTransfer) {
            require(bt2020) {
                "Live PQ MOV track requires BT.2020 primaries"
            }
        }
        val isPq = pqTransfer && bt2020
        val masteringDisplay = if (isPq) {
            if (requirePqSessionMetadata) {
                requireNotNull(pqMasteringDisplay) {
                    "Live BT.2020 PQ MOV track requires encoder-session mastering metadata"
                }
            } else {
                pqMasteringDisplay
            }
        } else {
            null
        }
        return QuickTimeColorAtomConfig(
            colorPrimaries = if (bt2020) PRIMARIES_BT2020 else PRIMARIES_BT709,
            transferCharacteristics = when (colorEncoding.colorTransfer) {
                MediaFormat.COLOR_TRANSFER_ST2084 -> TRANSFER_PQ_ST2084
                MediaFormat.COLOR_TRANSFER_HLG -> TRANSFER_HLG
                else -> TRANSFER_BT709
            },
            matrixCoefficients = if (bt2020) MATRIX_BT2020_NCL else MATRIX_BT709,
            fullRange = colorEncoding.colorRange == MediaFormat.COLOR_RANGE_FULL,
            // A PQ BT.2020 recording is HDR10 — attach ST.2086 mastering-display + content-light metadata
            // so the file is a complete HDR10 stream (was missing → players showed it flat vs the preview).
            masteringDisplay = masteringDisplay
        )
    }
}
