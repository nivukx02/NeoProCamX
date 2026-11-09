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

/**
 * Admission gate for a PQ signal produced by NeoProCamX rather than Camera2.
 *
 * Camera2 legitimately reports SDR when the application owns the sensor-linear -> PQ render
 * graph and feeds MediaCodec itself.  That SDR state must not erase the encoder identity, but it
 * also must never become a blanket way to relabel an SDR or vendor-managed stream as PQ.  Keep all
 * proof inputs explicit and require the encoder plan to match the active delivery contract.
 */
internal object ProjectOwnedPqRecordingTransportPolicy {
    private val projectOwnedEncoderFeeds = setOf(
        RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER,
        RecordingFeedPath.BYTEBUFFER_P010_ENCODER,
        RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER
    )

    fun isActive(
        sensorLinearNdrActive: Boolean,
        deliveryKind: VideoDeliveryKind,
        exactTenBitTransportRequested: Boolean,
        encoderHdrMode: String,
        encoderColorSpace: String,
        activeHdrMode: String,
        activeColorSpace: String,
        feedPath: RecordingFeedPath
    ): Boolean {
        val normalizedEncoderMode =
            RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(encoderHdrMode)
        val normalizedActiveMode =
            RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(activeHdrMode)
        return sensorLinearNdrActive &&
            deliveryKind == VideoDeliveryKind.HDR &&
            exactTenBitTransportRequested &&
            normalizedEncoderMode != "OFF" &&
            normalizedEncoderMode == normalizedActiveMode &&
            encoderColorSpace.trim().equals(activeColorSpace.trim(), ignoreCase = true) &&
            feedPath in projectOwnedEncoderFeeds
    }
}

internal object VideoHdrRecordingTransportPolicy {
    fun resolve(
        requestedHdrMode: String,
        requestedColorSpace: String,
        hdrState: HdrDynamicRangeState,
        projectOwnedPqTransportActive: Boolean = false
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
            HdrDynamicRangeMode.HDR10 -> "NDR10+"
            HdrDynamicRangeMode.HDR10_PLUS,
            HdrDynamicRangeMode.HLG,
            HdrDynamicRangeMode.DOLBY_VISION -> "OFF"
            HdrDynamicRangeMode.SDR -> "OFF"
        }
        val customPqRequested = requested == "NDR10+" ||
            requested == "NIVUKXVISION" ||
            requested.startsWith("APEX_")
        // A Neo profile is accepted only over the static HDR10/BT.2100 PQ carrier. This
        // defense-in-depth check prevents a future mode resolver from silently substituting
        // Android's dynamic-metadata profile or another externally managed range profile.
        val pqTransportActive = transportMode == HdrDynamicRangeMode.HDR10 ||
            projectOwnedPqTransportActive
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
