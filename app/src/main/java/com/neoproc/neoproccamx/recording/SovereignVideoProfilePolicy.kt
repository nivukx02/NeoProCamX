package com.neoproc.neoproccamx.recording

import java.util.Locale

internal object SovereignVideoProfilePolicy {
    const val AUTHORITY_STANDARD = "STANDARD_COLOR_PROFILE"
    const val AUTHORITY_NDR = "NEO_NDR_PROPRIETARY"
    const val AUTHORITY_N_LOG = "NEO_N_LOG_PROPRIETARY"
    const val AUTHORITY_RAW = "NEO_RAW_PRIVATE_SENSOR_MASTER"
    const val AUTHORITY_NPV10 = "NEO_NPV10_PRIVATE_MASTER"

    const val CARRIER_NONE = "NONE"
    const val CARRIER_BT2100_PQ = "BT.2100_PQ_ANDROID_CARRIER_ONLY"
    const val CARRIER_BT2100_HLG = "BT.2100_HLG_ANDROID_CARRIER_ONLY"

    const val PARITY_LOCK = "SELECTED_PROFILE_TRANSFER_MATRIX_BIT_DEPTH_LOCKED"
    const val RAW_MONITOR_PREVIEW_ONLY = "RAW_MONITOR_CINEMA_DEBAYER_PREVIEW_ONLY"
    const val NOT_APPLICABLE = "NOT_APPLICABLE"

    fun authorityForRequest(
        request: EliteVideoQualityRequest,
        normalizedHdrVersion: String,
        normalizedLogVersion: String
    ): String =
        when {
            request.mode == EliteVideoPipelineMode.RAW -> AUTHORITY_RAW
            request.mode == EliteVideoPipelineMode.NPV10 -> when {
                request.payloadFormat?.isRawPacked == true -> AUTHORITY_RAW
                isNLogProfile(normalizedLogVersion) && requestRequestsLog(request) -> AUTHORITY_N_LOG
                isNeoHdrProfile(normalizedHdrVersion) && requestRequestsNeoHdr(request) -> AUTHORITY_NDR
                else -> AUTHORITY_NPV10
            }
            request.mode == EliteVideoPipelineMode.HDR && isNeoHdrProfile(normalizedHdrVersion) -> AUTHORITY_NDR
            request.mode == EliteVideoPipelineMode.LOG && isNLogProfile(normalizedLogVersion) -> AUTHORITY_N_LOG
            else -> AUTHORITY_STANDARD
        }

    fun authorityForMovPlan(
        selectedProfile: String,
        activeTransport: String
    ): String {
        val key = normalized("$selectedProfile|$activeTransport")
        return when {
            isNeoHdrToken(key) -> AUTHORITY_NDR
            isNLogToken(key) -> AUTHORITY_N_LOG
            key.contains("raw") -> AUTHORITY_RAW
            key.contains("npv10") -> AUTHORITY_NPV10
            else -> AUTHORITY_STANDARD
        }
    }

    fun carrierFor(activeTransport: String): String {
        val key = normalized(activeTransport)
        return when {
            key.contains("bt.2100") && key.contains("pq") -> CARRIER_BT2100_PQ
            key.contains("bt2100") && key.contains("pq") -> CARRIER_BT2100_PQ
            key.contains("pq") && key.contains("main10") -> CARRIER_BT2100_PQ
            key.contains("bt.2100") && key.contains("hlg") -> CARRIER_BT2100_HLG
            key.contains("bt2100") && key.contains("hlg") -> CARRIER_BT2100_HLG
            key.contains("hlg") && key.contains("main10") -> CARRIER_BT2100_HLG
            else -> CARRIER_NONE
        }
    }

    fun isBt2100CarrierOnly(
        selectedProfile: String,
        activeTransport: String
    ): Boolean =
        authorityForMovPlan(selectedProfile, activeTransport) == AUTHORITY_NDR &&
            carrierFor(activeTransport) != CARRIER_NONE

    fun isNeoHdrProfile(normalizedHdrVersion: String): Boolean =
        normalizedHdrVersion == "NDR10+" ||
            normalizedHdrVersion == "NIVUKXVISION" ||
            normalizedHdrVersion.startsWith("APEX_")

    fun isNLogProfile(normalizedLogVersion: String): Boolean =
        normalizedLogVersion.startsWith("N-Log")

    fun hasForbiddenVendorHdrIdentity(value: String): Boolean {
        val key = normalized(value)
        return key.contains("dolby") ||
            key.contains("rpu") ||
            key.contains("hdr10plus") ||
            key.contains("hdr10+")
    }

    private fun isNeoHdrToken(key: String): Boolean =
        key.contains("ndr10") ||
            key.contains("nivukx") ||
            key.contains("apex")

    private fun isNLogToken(key: String): Boolean =
        key.contains("nlog") ||
            key.contains("n-log") ||
            key.contains("n log") ||
            key.contains("bt2020logsdr") ||
            key.contains("log")

    private fun requestRequestsLog(request: EliteVideoQualityRequest): Boolean {
        val key = normalized("${request.logProfile}|${request.colorSpace}|${request.colorScience}")
        return key.contains("log")
    }

    private fun requestRequestsNeoHdr(request: EliteVideoQualityRequest): Boolean {
        val hdrMode = normalized(request.hdrMode)
        val colorKey = normalized("${request.colorSpace}|${request.colorScience}")
        return hdrMode.isNotEmpty() && hdrMode != "off" ||
            isNeoHdrToken(colorKey)
    }

    private fun normalized(value: String): String =
        value.trim().lowercase(Locale.US).replace(" ", "")
}
