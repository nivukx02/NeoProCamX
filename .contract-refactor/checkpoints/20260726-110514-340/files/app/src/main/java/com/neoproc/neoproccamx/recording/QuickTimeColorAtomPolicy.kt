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

internal object QuickTimeColorAtomPolicy {
    const val PRIMARIES_BT709 = 1
    const val PRIMARIES_BT2020 = 9
    const val PRIMARIES_DISPLAY_P3 = 12
    const val TRANSFER_BT709 = 1
    const val TRANSFER_SRGB = 13
    const val TRANSFER_PQ_ST2084 = 16
    const val TRANSFER_HLG = 18
    const val MATRIX_BT709 = 1
    const val MATRIX_BT2020_NCL = 9

    fun fromRecordingColorEncoding(colorEncoding: RecordingColorEncodingConfig): QuickTimeColorAtomConfig {
        val bt2020 = colorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020
        val isPq = colorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 && bt2020
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
            masteringDisplay = if (isPq) {
                HdrStaticMetadataPolicy.resolveMasteringDisplay(HdrStaticMetadataPolicy.StaticMetadataMode.HDR10)
            } else {
                null
            }
        )
    }
}
