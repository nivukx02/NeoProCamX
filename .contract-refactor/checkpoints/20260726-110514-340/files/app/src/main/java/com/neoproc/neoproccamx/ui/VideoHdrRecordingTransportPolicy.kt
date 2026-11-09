package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.camera.HdrDynamicRangeState
import com.neoproc.neoproccamx.recording.RecordingHdrVideoLookPolicy

internal data class VideoHdrRecordingTransport(
    val hdrMode: String,
    val colorSpace: String,
    val colorProfile: String?,
    val proprietaryProfilePreserved: Boolean = true,
    val androidBt2100CarrierOnly: Boolean = false,
    val vendorDynamicRangeSubstitution: Boolean = false,
    val vendorDynamicMetadataAllowed: Boolean = false
)

internal object VideoHdrRecordingTransportPolicy {
    fun resolve(
        requestedHdrMode: String,
        requestedColorSpace: String,
        hdrState: HdrDynamicRangeState
    ): VideoHdrRecordingTransport {
        val requested = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(requestedHdrMode)
        if (requested == "OFF") {
            return VideoHdrRecordingTransport(
                hdrMode = "OFF",
                colorSpace = requestedColorSpace,
                colorProfile = null,
                proprietaryProfilePreserved = true,
                androidBt2100CarrierOnly = false,
                vendorDynamicRangeSubstitution = false,
                vendorDynamicMetadataAllowed = false
            )
        }

        val transportMode = hdrState.recordingTransportMode
        val actualHdrMode = when (transportMode) {
            HdrDynamicRangeMode.HDR10,
            HdrDynamicRangeMode.HDR10_PLUS -> "NDR10+"
            HdrDynamicRangeMode.HLG,
            HdrDynamicRangeMode.DOLBY_VISION -> "OFF"
            HdrDynamicRangeMode.SDR -> "OFF"
        }
        val customPqRequested = requested == "NDR10+" ||
            requested == "NIVUKXVISION" ||
            requested.startsWith("APEX_")
        val pqTransportActive = transportMode == HdrDynamicRangeMode.HDR10 ||
            transportMode == HdrDynamicRangeMode.HDR10_PLUS
        val deliveredHdrMode = if (customPqRequested && pqTransportActive) {
            requested
        } else {
            actualHdrMode
        }
        return VideoHdrRecordingTransport(
            hdrMode = deliveredHdrMode,
            colorSpace = colorSpaceFor(deliveredHdrMode),
            colorProfile = RecordingHdrVideoLookPolicy.originalHdrColorProfile(deliveredHdrMode),
            proprietaryProfilePreserved = customPqRequested && deliveredHdrMode == requested ||
                deliveredHdrMode == "NDR10+",
            androidBt2100CarrierOnly = deliveredHdrMode != "OFF" && pqTransportActive,
            vendorDynamicRangeSubstitution = false,
            vendorDynamicMetadataAllowed = false
        )
    }

    private fun colorSpaceFor(hdrMode: String): String =
        when (hdrMode) {
            "NDR10+" -> "Rec.2020-NDR10+"
            "NIVUKXVISION" -> "Rec.2020-NIVUKX"
            "APEX_SHARP",
            "APEX_ZERO",
            "APEX_CINEMA" -> "Rec.2020-NIVUKX-APEX"
            else -> "Rec.709"
        }
}
