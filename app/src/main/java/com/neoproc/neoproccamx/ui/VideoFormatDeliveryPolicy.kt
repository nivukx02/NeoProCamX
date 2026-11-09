package com.neoproc.neoproccamx.ui

import android.hardware.camera2.CameraCharacteristics
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.HdrStaticMetadataPolicy

/**
 * VideoFormatDeliveryPolicy — cinema-oriented video format delivery resolution.
 *
 * Determines the active video delivery format (RAW, LOG, NDR, or standard)
 * for the current recording session based on UI mode, cinema mode, NDR
 * configuration, and device capabilities.
 *
 * This policy is the authoritative source for:
 *  1. **RAW video routing** — bypasses all color grading for flat RAW delivery.
 *  2. **LOG encoding** — selects NeoRadiance spectral curve profiles.
 *  3. **NDR mode** — validates and normalizes the requested Neo dynamic range profile.
 *  4. **Truthful color delivery** — determines whether the preview matches recorded color.
 *  5. **Color space metadata** — resolves the container-level color primaries tag.
 *
 * All resolved states follow:
 *  - SMPTE ST 2084 (PQ transfer function for Android-compatible NDR transport)
 *  - ITU-R BT.2100 (PQ container specifications)
 *  - ITU-R BT.709 (Rec.709 standard gamut)
 *  - NeoRadiance spectral curve compatibility
 */
internal object VideoFormatDeliveryPolicy {

    // ── Resolution API ────────────────────────────────────────────────────────

    /**
     * Resolves the complete [VideoFormatDeliveryState] for the current UI configuration.
     *
     * Priority resolution:
     *  1. Non-video modes → return default (no special format).
     *  2. RAW mode → bypass all grading, set format to RAW.
     *  3. LOG mode → enable LOG profile, disable NDR.
     *  4. NDR mode → validate and normalize, enable Neo dynamic range pipeline.
     *  5. Standard → no special format, optional color space override.
     *
     * @param uiMode           Current UI application mode (VIDEO, PHOTO, etc.).
     * @param cinemaMode       Active cinema sub-mode (PRO, LOG, RAW, NDR).
     * @param rawVideoActive   Whether the RAW video output surface is connected.
     * @param logEnabled       Whether LOG encoding is explicitly enabled.
     * @param requestedHdrMode Requested NDR mode string from user/settings.
     * @param preferredColorSpace Preferred delivery color space (e.g. "Rec.2020").
     * @param tenBitActive     Whether 10-bit encoding is active.
     */
    fun resolve(
        uiMode: AppMode,
        cinemaMode: CinemaVideoMode,
        rawVideoActive: Boolean,
        logEnabled: Boolean,
        requestedHdrMode: String,
        preferredColorSpace: String = "Rec.709",
        tenBitActive: Boolean = false,
        characteristics: CameraCharacteristics? = null,
        gpu10BitSupported: Boolean = true,
        hardwareCapabilities: VideoModeHardwareCapabilities? = null,
        width: Int = 3840,
        height: Int = 2160,
        fps: Int = 60
    ): VideoFormatDeliveryState {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = uiMode,
            cinemaMode = cinemaMode,
            rawVideoActive = rawVideoActive,
            logEnabled = logEnabled,
            requestedHdrMode = requestedHdrMode,
            preferredColorSpace = preferredColorSpace,
            tenBitActive = tenBitActive,
            characteristics = characteristics,
            gpu10BitSupported = gpu10BitSupported,
            hardwareCapabilities = hardwareCapabilities,
            width = width,
            height = height,
            fps = fps
        )

        val deliveryHardware = hardwareCapabilities ?: VideoModeHardwareCapabilities.optimistic(gpu10BitSupported)
        return VideoFormatDeliveryState(
            rawVideoActive = contract.rawSensorOriginal,
            logVideoActive = contract.logToneMapActive,
            effectiveHdrMode = contract.effectiveHdrMode,
            effectiveColorSpace = contract.effectiveColorSpace,
            truthfulColorDelivery = contract.truthfulColorDelivery,
            requiresHdrContainerMetadata = contract.requiresHdrContainerMetadata,
            tenBitActive = contract.tenBitTransportRequested,
            cinemaModeLabel = contract.cinemaMode.name,
            pipelineContract = contract,
            neoRadianceDeliveryPlan = NeoRadianceDeliveryPolicy.resolve(
                contract = contract,
                hardwareCapabilities = deliveryHardware
            )
        )
    }
}

internal enum class NeoRadianceDeliveryTarget {
    HDR10_PQ,
    HLG,
    SDR_BT2020,
    SDR_REC709
}

internal data class NeoRadianceDeliveryPlan(
    val target: NeoRadianceDeliveryTarget,
    val colorStandard: String,
    val transferFunction: String,
    val maxCllNits: Int,
    val maxFallNits: Int,
    val masteringDisplay: HdrStaticMetadataPolicy.MasteringDisplay,
    val requiresStaticMetadata: Boolean,
    val usesBt2390SdrDownmap: Boolean,
    val truthSummary: String
)

internal object NeoRadianceDeliveryPolicy {
    fun resolve(
        contract: VideoModePipelineContract,
        hardwareCapabilities: VideoModeHardwareCapabilities
    ): NeoRadianceDeliveryPlan {
        if (!contract.hdrToneMapActive) {
            return sdrPlan(
                contract = contract,
                summary = "HELIOS SDR path; HDR metadata unavailable or not requested."
            )
        }
        val sensorLinearNeoDelivery = contract.effectiveHdrMode == "NDR10+" ||
            contract.effectiveHdrMode == "NIVUKXVISION" ||
            contract.effectiveHdrMode.startsWith("APEX_")
        val profiles = hardwareCapabilities.supportedDynamicRangeProfiles
        return when {
            // Proprietary Neo modes never consume a Camera2 HLG/PQ stream. Their HDR
            // eligibility was established from RAW_SENSOR + FP16 + Main10 egress, so a
            // vendor-advertised Camera2 HDR10 profile must not gate the final PQ transport.
            sensorLinearNeoDelivery && hardwareCapabilities.supportsSensorLinearNdr ->
                hdr10Plan(contract.effectiveHdrMode)
            profiles.contains(VideoDynamicRangeProfile.HDR10) -> hdr10Plan(contract.effectiveHdrMode)
            else -> sdrPlan(
                contract = contract,
                summary = "HELIOS safe fallback: device reports no static HDR10 PQ transport."
            )
        }
    }

    private fun hdr10Plan(effectiveHdrMode: String): NeoRadianceDeliveryPlan {
        val mastering = when {
            effectiveHdrMode == "NDR10+" ->
                HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_5000_NITS
            effectiveHdrMode == "NIVUKXVISION" || effectiveHdrMode.startsWith("APEX_") ->
                HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_10000_NITS
            else -> HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_1000_NITS
        }
        return NeoRadianceDeliveryPlan(
            target = NeoRadianceDeliveryTarget.HDR10_PQ,
            colorStandard = "BT.2020",
            transferFunction = "ST.2084_PQ",
            maxCllNits = mastering.maxContentLightLevelNits,
            maxFallNits = mastering.maxFrameAverageLightLevelNits,
            masteringDisplay = mastering,
            requiresStaticMetadata = true,
            usesBt2390SdrDownmap = false,
            truthSummary = "HELIOS HDR10 PQ: BT.2020, 203-nit diffuse white, " +
                "${mastering.maxLuminanceNits.toInt()}-nit mastering metadata."
        )
    }

    private fun sdrPlan(
        contract: VideoModePipelineContract,
        summary: String
    ): NeoRadianceDeliveryPlan {
        val mastering = HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_1000_NITS
        val wideGamutSdr = contract.toneMap == VideoPipelineToneMap.WIDE_GAMUT_SDR ||
            contract.effectiveColorSpace.contains("2020", ignoreCase = true) ||
            contract.effectiveColorSpace.contains("neo wide", ignoreCase = true) ||
            contract.effectiveColorSpace.contains("wide gamut", ignoreCase = true)
        return NeoRadianceDeliveryPlan(
            target = if (wideGamutSdr) {
                NeoRadianceDeliveryTarget.SDR_BT2020
            } else {
                NeoRadianceDeliveryTarget.SDR_REC709
            },
            colorStandard = if (wideGamutSdr) "BT.2020" else "Rec.709",
            transferFunction = if (wideGamutSdr) "SDR_VIDEO" else "sRGB",
            maxCllNits = 100,
            maxFallNits = 100,
            masteringDisplay = mastering,
            requiresStaticMetadata = false,
            usesBt2390SdrDownmap = !wideGamutSdr,
            truthSummary = summary
        )
    }
}

// ── Delivery State ────────────────────────────────────────────────────────────

/**
 * The resolved video format delivery state for the current recording session.
 * All rendering, encoding, and preview decisions are driven by this model.
 */
internal data class VideoFormatDeliveryState(
    /** Whether RAW video capture is active (bypasses all ISP/LUT processing). */
    val rawVideoActive: Boolean = false,
    /** Whether LOG video encoding is active. */
    val logVideoActive: Boolean = false,
    /** Normalized NDR mode for the current session (e.g. "NDR10+", "NIVUKXVISION", "OFF"). */
    val effectiveHdrMode: String = "OFF",
    /** Resolved container-level color space (e.g. "Rec.2020-PQ", "Rec.709"). */
    val effectiveColorSpace: String = "Rec.709",
    /** Whether the preview faithfully represents the recorded color pipeline. */
    val truthfulColorDelivery: Boolean = false,
    /** Whether PQ/NDR transport metadata must be injected into the container. */
    val requiresHdrContainerMetadata: Boolean = false,
    /** Whether 10-bit output is active. */
    val tenBitActive: Boolean = false,
    /** Human-readable label for the active cinema mode. */
    val cinemaModeLabel: String = "PRO",
    /** Authoritative mode contract used by Camera2, preview, and encoder adapters. */
    val pipelineContract: VideoModePipelineContract = VideoModePipelineContractPolicy.resolve(
        uiMode = AppMode.VIDEO,
        cinemaMode = CinemaVideoMode.PRO,
        rawVideoActive = false,
        logEnabled = false,
        requestedHdrMode = "OFF"
    ),
    /** NeoRadiance v2 HELIOS delivery target selected from device HDR capabilities. */
    val neoRadianceDeliveryPlan: NeoRadianceDeliveryPlan = NeoRadianceDeliveryPolicy.resolve(
        contract = pipelineContract,
        hardwareCapabilities = VideoModeHardwareCapabilities.premium()
    )
) {
    val neutralPreview: Boolean
        get() = !rawVideoActive &&
            !logVideoActive &&
            effectiveHdrMode == "OFF" &&
            pipelineContract.toneMap == VideoPipelineToneMap.REC709_NEUTRAL

    /** Whether any non-standard delivery mode is active. */
    val isCinemaDeliveryMode: Boolean
        get() = rawVideoActive || logVideoActive || effectiveHdrMode != "OFF"

    /** Whether the pipeline requires static PQ transport metadata. */
    val requiresPqStaticMetadata: Boolean
        get() = effectiveHdrMode == "NDR10+" ||
            effectiveHdrMode == "NIVUKXVISION" ||
            effectiveHdrMode.startsWith("APEX_")

    /** Whether the pipeline requires a Neo dynamic metadata sidecar. */
    val requiresNeoDynamicMetadataSidecar: Boolean
        get() = effectiveHdrMode == "NDR10+" ||
            effectiveHdrMode == "NIVUKXVISION" ||
            effectiveHdrMode.startsWith("APEX_")

    /** Certified vendor enhancement-layer encoding is not claimed by NeoProCamX profiles. */
    val requiresCertifiedVendorEnhancementEncoding: Boolean
        get() = false

    /** Neo profiles do not expose legacy broadcast transfer curves as product modes. */
    val isLegacyBroadcastTransferActive: Boolean
        get() = false

    /** Short summary string for logging and UI overlay. */
    val shortSummary: String
        get() = buildString {
            when {
                rawVideoActive -> append("RAW")
                logVideoActive -> append("LOG")
                effectiveHdrMode != "OFF" -> append(effectiveHdrMode)
                else -> append("PRO")
            }
            if (tenBitActive) append(" 10-bit")
            append(" / $effectiveColorSpace")
        }
}
