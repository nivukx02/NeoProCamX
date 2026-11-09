package com.neoproc.neoproccamx.recording

/**
 * Separates immutable INPUT pixel meaning from requested OUTPUT look metadata.
 *
 * New NPV10 masters carry a complete pixel-colour packet. Conversion must decode those P010 codes
 * from that packet and, because the current NPV10 enhancer does not perform an independently proven
 * gamut/transfer conversion, tag the output with the same colour meaning. Legacy masters retain the
 * pre-schema resolver for compatibility. An invalid new packet is intentionally represented as the
 * playback policy's safe limited-range BT.709 fallback.
 */
internal data class Npv10StoredPixelColorPlan(
    val schemaPresent: Boolean,
    val verified: Boolean,
    val colorConfig: QuickTimeColorAtomConfig,
    val activeTransport: String,
    val reason: String
)

internal object Npv10StoredPixelColorPolicy {
    private val SCHEMA_KEYS = arrayOf(
        "pixelcolorprimaries",
        "pixelcolormatrix",
        "pixelcolorrange",
        "pixelcolorapplied",
        "pixelcolorauthority"
    )

    fun resolve(header: Npv10FileHeader): Npv10StoredPixelColorPlan {
        val schemaPresent = SCHEMA_KEYS.any(header.metadataValues::containsKey)
        val playback = Npv10PlaybackColorPolicy.resolve(header)
        val primaries = when (playback.primaries) {
            Npv10PlaybackColorPolicy.Primaries.BT709 -> QuickTimeColorAtomPolicy.PRIMARIES_BT709
            Npv10PlaybackColorPolicy.Primaries.BT2020 -> QuickTimeColorAtomPolicy.PRIMARIES_BT2020
            Npv10PlaybackColorPolicy.Primaries.ACES_AP1 -> QuickTimeColorAtomPolicy.PRIMARIES_BT2020
        }
        val transfer = when (playback.transfer) {
            Npv10PlaybackColorPolicy.Transfer.PQ -> QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084
            Npv10PlaybackColorPolicy.Transfer.HLG -> QuickTimeColorAtomPolicy.TRANSFER_HLG
            Npv10PlaybackColorPolicy.Transfer.SDR,
            Npv10PlaybackColorPolicy.Transfer.NEO_LOG -> QuickTimeColorAtomPolicy.TRANSFER_BT709
        }
        val matrix = when (playback.matrix) {
            Npv10PlaybackColorPolicy.Matrix.BT709 -> QuickTimeColorAtomPolicy.MATRIX_BT709
            Npv10PlaybackColorPolicy.Matrix.BT2020 -> QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL
        }
        val activeTransport = when (playback.transfer) {
            Npv10PlaybackColorPolicy.Transfer.PQ -> Bt2100TransportPolicy.TRANSPORT_PQ_MAIN10
            Npv10PlaybackColorPolicy.Transfer.HLG -> Bt2100TransportPolicy.TRANSPORT_HLG_MAIN10
            Npv10PlaybackColorPolicy.Transfer.NEO_LOG -> "BT2020_LOG_SDR"
            Npv10PlaybackColorPolicy.Transfer.SDR -> if (
                playback.primaries == Npv10PlaybackColorPolicy.Primaries.BT2020
            ) {
                "BT2020_SDR"
            } else {
                "REC709_SDR"
            }
        }
        return Npv10StoredPixelColorPlan(
            schemaPresent = schemaPresent,
            verified = schemaPresent && playback.pixelAuthorityValid,
            colorConfig = QuickTimeColorAtomConfig(
                colorPrimaries = primaries,
                transferCharacteristics = transfer,
                matrixCoefficients = matrix,
                fullRange = playback.fullRange
            ),
            activeTransport = activeTransport,
            reason = playback.pixelAuthorityReason
        )
    }

    fun sourceColorConfig(
        header: Npv10FileHeader,
        legacyFallback: QuickTimeColorAtomConfig
    ): QuickTimeColorAtomConfig = resolve(header).let { stored ->
        if (stored.schemaPresent) stored.colorConfig else legacyFallback
    }

    fun applyOutputTruth(
        header: Npv10FileHeader,
        requestedPlan: Npv10MovColorTagPlan
    ): Npv10MovColorTagPlan = resolve(header).let { stored ->
        if (!stored.schemaPresent) {
            requestedPlan
        } else {
            requestedPlan.copy(
                colorConfig = stored.colorConfig,
                activeTransport = stored.activeTransport
            )
        }
    }
}
