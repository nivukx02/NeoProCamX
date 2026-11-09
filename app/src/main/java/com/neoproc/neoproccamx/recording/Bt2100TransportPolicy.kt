package com.neoproc.neoproccamx.recording

import java.util.Locale

internal object Bt2100TransportPolicy {
    const val LEGACY_HLG_MODE = "HLG"
    const val PRIMARIES = "BT.2020"
    const val TRANSFER_PQ = "ST2084/PQ"
    const val MATRIX_NCL = "BT.2020-NCL"
    const val WORKING_SPACE_PQ = "BT.2100 PQ"
    const val WORKING_SPACE_HLG = "BT.2100 HLG"
    const val TRANSFER_HLG = "ARIB-STD-B67/HLG"
    const val TRANSPORT_PQ_MAIN10 = "BT.2100_PQ_MAIN10"
    const val TRANSPORT_PQ_MAIN12 = "BT.2100_PQ_MAIN12"
    const val TRANSPORT_HLG_MAIN10 = "BT.2100_HLG_MAIN10"
    const val TRANSPORT_NPV10_PQ_PRIVATE_MASTER = "NPV10_BT.2100_PQ_PRIVATE_MASTER"

    fun displayLabelForHdrVersion(normalizedHdrVersion: String): String =
        when (normalizedHdrVersion.normalizedToken()) {
            "NDR10+" -> "NDR10+ BT.2100 PQ"
            "NIVUKXVISION" -> "NIVUKX Vision BT.2100 PQ"
            "APEX_SHARP" -> "NIVUKX APEX SHARP"
            "APEX_ZERO" -> "NIVUKX APEX ZERO"
            "APEX_CINEMA" -> "NIVUKX APEX CINEMA"
            else -> normalizedHdrVersion
        }

    fun activeLabelForRequested(requestedDynamicRangeLabel: String): String =
        when {
            requestedDynamicRangeLabel.contains("NIVUKX", ignoreCase = true) &&
                !requestedDynamicRangeLabel.contains("APEX", ignoreCase = true) ->
                "NIVUKX Vision BT.2100 PQ (10-bit)"
            requestedDynamicRangeLabel.contains("NDR10+", ignoreCase = true) ->
                "NDR10+ BT.2100 PQ (10-bit)"
            else -> "$requestedDynamicRangeLabel (10-bit)"
        }

    fun isNeoBt2100HdrVersion(normalizedHdrVersion: String): Boolean =
        normalizedHdrVersion.normalizedToken() == "NDR10+" ||
            normalizedHdrVersion.normalizedToken() == "NIVUKXVISION" ||
            normalizedHdrVersion.normalizedToken() == "APEX_SHARP" ||
            normalizedHdrVersion.normalizedToken() == "APEX_ZERO" ||
            normalizedHdrVersion.normalizedToken() == "APEX_CINEMA"

    fun isBt2100PqEvidence(
        activeTransport: String,
        transfer: String,
        primaries: String,
        matrix: String
    ): Boolean =
        activeTransport.isBt2100PqTransport() &&
            transfer.equals(TRANSFER_PQ, ignoreCase = true) &&
            primaries.equals(PRIMARIES, ignoreCase = true) &&
            matrix.equals(MATRIX_NCL, ignoreCase = true)

    fun String.isBt2100PqTransport(): Boolean {
        val token = normalizedToken()
        return token.contains("BT.2100") && token.contains("PQ")
    }

    fun String.isBt2100TransportCarrier(): Boolean {
        val token = normalizedToken()
        return token.contains("BT.2100") && (token.contains("PQ") || token.contains("HLG"))
    }

    private fun String.normalizedToken(): String =
        trim()
            .uppercase(Locale.US)
            .replace(" ", "")
            .replace("-", "")
}
