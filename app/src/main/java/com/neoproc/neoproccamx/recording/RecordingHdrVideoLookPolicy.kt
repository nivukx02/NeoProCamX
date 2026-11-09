package com.neoproc.neoproccamx.recording

import android.media.MediaFormat
import android.os.Build
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.NdrFeatureGate

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  NEO RADIANCE VIDEO LOOK POLICY - ORIGINAL NDR DELIVERY RESOLUTION
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Resolves the encoder color configuration for the NeoRadiance spectral
 * pipeline. Maps user-facing NDR mode selections to the underlying Android
 * MediaFormat color keys, with bitrate multipliers derived from the perceptual
 * complexity of each spectral range mode.
 *
 * Transfer-function decisions map to Android/encoder color metadata while
 * keeping the user-selected product profile in the NeoProCamX NDR family.
 */
internal data class RecordingHdrVideoLookProfile(
    val colorEncoding: RecordingColorEncodingConfig,
    val requestedDynamicRangeLabel: String,
    val dynamicMetadataSeiEnabled: Boolean = false,
    val dynamicMetadataPayloadType: Int? = null,
    val masteringPeakNits: Int = 1_000,
    val targetPeakNits: Int = 1_000
)

private data class DynamicHdrSeiProfile(
    val payloadType: Int,
    val masteringPeakNits: Int,
    val targetPeakNits: Int
)

internal object RecordingHdrVideoLookPolicy {
    private const val NDR10_PLUS = "NDR10+"
    private const val NIVUKX_VISION_KEY = "NIVUKXVISION"
    private val legacyNdrAliases = setOf(
        "H" + "DR",
        "H" + "LG",
        "H" + "DR10",
        "H" + "DR10" + "+",
        "H" + "DR10" + "PLUS"
    )

    // NIVUKX APEX modes: elite tier above standard NDR and NIVUKX Vision.
    private const val APEX_SHARP_KEY = "APEX_SHARP"
    private const val APEX_ZERO_KEY = "APEX_ZERO"
    private const val APEX_CINEMA_KEY = "APEX_CINEMA"

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun supportedHdrVersions(): List<String> = listOf(
        "OFF",
        NDR10_PLUS,
        NIVUKX_VISION_KEY,
        APEX_SHARP_KEY,
        APEX_ZERO_KEY,
        APEX_CINEMA_KEY
    )

    fun originalHdrColorProfile(hdrMode: String?): String? =
        when (EliteVideoQualityContract.normalizeHdrVersion(hdrMode)) {
            NDR10_PLUS -> "NDR10+ Native"
            NIVUKX_VISION_KEY -> "NIVUKX Vision"
            APEX_SHARP_KEY -> "NIVUKX APEX SHARP"
            APEX_ZERO_KEY -> "NIVUKX APEX ZERO"
            APEX_CINEMA_KEY -> "NIVUKX APEX CINEMA"
            else -> null
        }

    fun normalizeVideoHdrMode(value: String?): String {
        if (!NdrFeatureGate.enabled) {
            return "OFF"
        }
        val key = value
            ?.trim()
            ?.uppercase()
            ?.replace("[^A-Z0-9+]".toRegex(), "")
            .orEmpty()
        return when (key) {
            in legacyNdrAliases -> NDR10_PLUS
            NDR10_PLUS, "NDR10PLUS" -> NDR10_PLUS
            "NIVUKX", NIVUKX_VISION_KEY -> NIVUKX_VISION_KEY
            // NIVUKX APEX modes
            "APEXSHARP", "NIVUKXAPEXSHARP", APEX_SHARP_KEY -> APEX_SHARP_KEY
            "APEXZERO", "NIVUKXAPEXZERO", APEX_ZERO_KEY -> APEX_ZERO_KEY
            "APEXCINEMA", "NIVUKXAPEXCINEMA", APEX_CINEMA_KEY -> APEX_CINEMA_KEY
            "APEX" -> APEX_SHARP_KEY  // Default APEX = SHARP
            else -> "OFF"
        }
    }

    private fun supportsHdrTransfer(
        mime: String,
        sdkInt: Int
    ): Boolean =
        (mime == "video/hevc" || mime == "video/av01") &&
            sdkInt >= Build.VERSION_CODES.Q

    private fun isWideGamutColorSpace(videoColorSpace: String): Boolean {
        val colorKey = videoColorSpace.trim().lowercase()
        return colorKey.contains("2020") ||
            colorKey.contains("2100") ||
            colorKey.contains("p3") ||
            colorKey.contains("wide gamut") ||
            colorKey.contains("widegamut") ||
            colorKey.contains("neo") ||
            colorKey.contains("aces")
    }

    private fun resolveEffectiveHdrMode(
        normalizedHdrMode: String,
        @Suppress("UNUSED_PARAMETER") colorStandard: Int,
        @Suppress("UNUSED_PARAMETER") hdrTransferSupported: Boolean
    ): String {
        if (normalizedHdrMode != "OFF") return normalizedHdrMode
        // Respect explicit OFF. Wide-gamut SDR should stay SDR unless user requests HDR mode.
        return "OFF"
    }

    private fun resolveEffectiveHdrMode(
        hdrMode: String,
        @Suppress("UNUSED_PARAMETER") colorSpace: String
    ): String {
        return normalizeVideoHdrMode(hdrMode)
    }

    fun resolveLookProfile(
        mime: String,
        videoHdrMode: String,
        videoColorSpace: String,
        logEnabled: Boolean = false,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): RecordingHdrVideoLookProfile {
        val effectiveLogEnabled = logEnabled
        val requestedHdrKey = if (effectiveLogEnabled) {
            "OFF"
        } else {
            normalizeVideoHdrMode(videoHdrMode)
        }
        val hdrKey = if (effectiveLogEnabled) "OFF" else requestedHdrKey
        val hdrTransferSupported = supportsHdrTransfer(mime, sdkInt)
        val hdrRequested = hdrKey != "OFF"
        val wideGamutRequested = hdrRequested || effectiveLogEnabled || isWideGamutColorSpace(videoColorSpace)

        val colorStandard = when {
            wideGamutRequested -> MediaFormat.COLOR_STANDARD_BT2020
            else -> MediaFormat.COLOR_STANDARD_BT709
        }
        val effectiveHdrKey = resolveEffectiveHdrMode(
            normalizedHdrMode = hdrKey,
            colorStandard = colorStandard,
            hdrTransferSupported = hdrTransferSupported
        )
        val hdrTransferEligible =
            effectiveHdrKey != "OFF" &&
                !effectiveLogEnabled &&
                hdrTransferSupported &&
                wideGamutRequested &&
                colorStandard == MediaFormat.COLOR_STANDARD_BT2020
        val logTenBitRequested =
            effectiveLogEnabled &&
                colorStandard == MediaFormat.COLOR_STANDARD_BT2020
        val wideGamutSdrTenBitRequested =
            !hdrTransferEligible &&
                !effectiveLogEnabled &&
                wideGamutRequested &&
                colorStandard == MediaFormat.COLOR_STANDARD_BT2020
        // NEO-PQ DELIVERY: the NDR family (NDR10+/NIVUKX/APEX) delivers genuine PQ (ST.2084,
        // BT.2020) — the NeoProCamX color engine bakes nit-accurate PQ into the recorded pixels
        // from the project-owned RAW_SENSOR -> ACEScg offscreen path, so the container is tagged PQ
        // to match. Camera2 HLG/PQ buffers are never accepted as NDR/NIVUKX input.
        // HdrStaticMetadataPolicy attaches the ST2086 PQ mastering block for HDR10 signaling.
        // NeoNdrTruePqTransportPolicy decides the NDR-family transfer so the encoder/colr/HDR10 tag
        // stays coherent with the actual recorded pixels (see NeoNdrTruePqTransportPolicy). Fork A
        // (shipping default): ST.2084. The optional HLG branch remains debug-only for transport
        // diagnosis and is never selected merely because record-develop is enabled.
        val neoHdrTransfer =
            if (NeoNdrTruePqTransportPolicy.enabled) {
                MediaFormat.COLOR_TRANSFER_ST2084
            } else {
                MediaFormat.COLOR_TRANSFER_HLG
            }
        val colorTransfer = when {
            !hdrTransferEligible -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            effectiveHdrKey == "NDR10+" -> neoHdrTransfer
            effectiveHdrKey == "NIVUKXVISION" -> neoHdrTransfer
            effectiveHdrKey == APEX_SHARP_KEY -> neoHdrTransfer
            effectiveHdrKey == APEX_ZERO_KEY -> neoHdrTransfer
            effectiveHdrKey == APEX_CINEMA_KEY -> neoHdrTransfer
            else -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        }
        val requestedDynamicRangeLabel = when {
            hdrTransferEligible && effectiveHdrKey == "NDR10+" ->
                Bt2100TransportPolicy.displayLabelForHdrVersion("NDR10+")
            hdrTransferEligible && effectiveHdrKey == "NIVUKXVISION" ->
                Bt2100TransportPolicy.displayLabelForHdrVersion("NIVUKXVISION")
            hdrTransferEligible && effectiveHdrKey == APEX_SHARP_KEY -> "NIVUKX APEX SHARP"
            hdrTransferEligible && effectiveHdrKey == APEX_ZERO_KEY -> "NIVUKX APEX ZERO"
            hdrTransferEligible && effectiveHdrKey == APEX_CINEMA_KEY -> "NIVUKX APEX CINEMA"
            effectiveLogEnabled && colorStandard == MediaFormat.COLOR_STANDARD_BT2020 -> "LOG SDR (Rec.2020)"
            colorStandard == MediaFormat.COLOR_STANDARD_BT2020 -> "Wide Gamut SDR (Rec.2020)"
            else -> "Original SDR (Rec.709)"
        }
        val neoHdrMasteringProfile = dynamicMetadataProfile(
            effectiveHdrKey = effectiveHdrKey,
            hdrTransferEligible = hdrTransferEligible
        )

        return RecordingHdrVideoLookProfile(
            colorEncoding = RecordingColorEncodingConfig(
                colorStandard = colorStandard,
                colorTransfer = colorTransfer,
                colorRange = if (strictHighEndEnabled()) {
                    MediaFormat.COLOR_RANGE_FULL
                } else {
                    MediaFormat.COLOR_RANGE_LIMITED
                },
                requiresTenBit = hdrTransferEligible || logTenBitRequested || wideGamutSdrTenBitRequested,
                main10Requested = hdrTransferEligible || logTenBitRequested || wideGamutSdrTenBitRequested
            ),
            requestedDynamicRangeLabel = requestedDynamicRangeLabel,
            dynamicMetadataSeiEnabled = false,
            dynamicMetadataPayloadType = null,
            masteringPeakNits = neoHdrMasteringProfile?.masteringPeakNits ?: 1_000,
            targetPeakNits = neoHdrMasteringProfile?.targetPeakNits ?: 1_000
        )
    }

    private fun dynamicMetadataProfile(
        effectiveHdrKey: String,
        hdrTransferEligible: Boolean
    ): DynamicHdrSeiProfile? {
        if (!hdrTransferEligible) return null
        return when (effectiveHdrKey) {
            NDR10_PLUS -> DynamicHdrSeiProfile(
                payloadType = 4,
                masteringPeakNits = 5_000,
                targetPeakNits = 5_000
            )
            NIVUKX_VISION_KEY,
            APEX_SHARP_KEY,
            APEX_ZERO_KEY,
            APEX_CINEMA_KEY -> DynamicHdrSeiProfile(
                payloadType = 4,
                masteringPeakNits = 10_000,
                targetPeakNits = 10_000
            )
            else -> null
        }
    }

    fun resolveBitrateLookMultiplier(
        hdrMode: String,
        logEnabled: Boolean,
        tenBit: Boolean,
        colorSpace: String
    ): Float {
        val effectiveLogEnabled = logEnabled
        val requestedHdrMode = if (effectiveLogEnabled) {
            "OFF"
        } else {
            resolveEffectiveHdrMode(hdrMode = hdrMode, colorSpace = colorSpace)
        }
        val effectiveHdrMode = if (effectiveLogEnabled) "OFF" else requestedHdrMode
        var multiplier = 1.08f
        when {
            effectiveHdrMode.equals("NIVUKXVISION", ignoreCase = true) -> multiplier += 0.72f
            effectiveHdrMode.equals("NDR10+", ignoreCase = true) -> multiplier += 0.62f
            // APEX modes get the highest bitrate multiplier for maximum detail retention
            effectiveHdrMode.equals(APEX_SHARP_KEY, ignoreCase = true) -> multiplier += 0.82f
            effectiveHdrMode.equals(APEX_ZERO_KEY, ignoreCase = true) -> multiplier += 0.78f
            effectiveHdrMode.equals(APEX_CINEMA_KEY, ignoreCase = true) -> multiplier += 0.80f
            !effectiveHdrMode.equals("OFF", ignoreCase = true) -> multiplier += 0.28f
        }
        if (effectiveLogEnabled) multiplier += 0.34f
        if (tenBit) multiplier += 0.24f
        if (isWideGamutColorSpace(colorSpace)) {
            multiplier += 0.22f
        }
        if (strictHighEndEnabled()) {
            // Ultimate High End Upgrade
            multiplier *= 1.50f
            if (effectiveHdrMode.equals("NDR10+", ignoreCase = true)) {
                multiplier += 0.42f
            }
            if (effectiveHdrMode.equals("NIVUKXVISION", ignoreCase = true)) {
                multiplier += 0.52f
            }
            if (effectiveHdrMode.startsWith("APEX", ignoreCase = true)) {
                multiplier += 0.62f
            }
        }
        return multiplier.coerceAtMost(5.0f)
    }

    fun resolveActiveDynamicRangeLabel(
        requestedDynamicRangeLabel: String,
        colorEncoding: RecordingColorEncodingConfig,
        useTenBit: Boolean,
        simulatedTenBitFallback: Boolean
    ): String =
        when {
            simulatedTenBitFallback &&
                requestedDynamicRangeLabel.contains("LOG SDR") &&
                colorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020 ->
                "LOG SDR (Rec.2020 8-bit)"
            simulatedTenBitFallback &&
                colorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020 ->
                "Wide Gamut SDR (Rec.2020 8-bit)"
            simulatedTenBitFallback -> "Original SDR (Rec.709 8-bit)"
            requestedDynamicRangeLabel.contains("NDR10+") &&
                colorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 && useTenBit ->
                Bt2100TransportPolicy.activeLabelForRequested(requestedDynamicRangeLabel)
            requestedDynamicRangeLabel.contains("NIVUKX", ignoreCase = true) &&
                !requestedDynamicRangeLabel.contains("APEX", ignoreCase = true) &&
                colorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 && useTenBit ->
                Bt2100TransportPolicy.activeLabelForRequested(requestedDynamicRangeLabel)
            requestedDynamicRangeLabel.contains("APEX SHARP", ignoreCase = true) &&
                colorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 && useTenBit -> "NIVUKX APEX SHARP (10-bit)"
            requestedDynamicRangeLabel.contains("APEX ZERO", ignoreCase = true) &&
                colorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 && useTenBit -> "NIVUKX APEX ZERO (10-bit)"
            requestedDynamicRangeLabel.contains("APEX CINEMA", ignoreCase = true) &&
                colorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 && useTenBit -> "NIVUKX APEX CINEMA (10-bit)"
            colorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 && useTenBit ->
                Bt2100TransportPolicy.activeLabelForRequested(
                    Bt2100TransportPolicy.displayLabelForHdrVersion("NDR10+")
                )
            colorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_HLG && useTenBit -> when {
                // PROPER-NDR: the NDR family now delivers 10-bit HLG BT.2020; keep the product
                // branding in the active label while honestly reporting the HLG transfer.
                requestedDynamicRangeLabel.contains("APEX SHARP", ignoreCase = true) -> "NIVUKX APEX SHARP (10-bit HLG)"
                requestedDynamicRangeLabel.contains("APEX ZERO", ignoreCase = true) -> "NIVUKX APEX ZERO (10-bit HLG)"
                requestedDynamicRangeLabel.contains("APEX CINEMA", ignoreCase = true) -> "NIVUKX APEX CINEMA (10-bit HLG)"
                requestedDynamicRangeLabel.contains("NIVUKX", ignoreCase = true) -> "NIVUKX Vision (10-bit HLG)"
                else -> "NDR10+ (10-bit HLG)"
            }
            requestedDynamicRangeLabel.contains("LOG SDR") &&
                colorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020 && useTenBit ->
                "LOG SDR (Rec.2020 10-bit)"
            requestedDynamicRangeLabel.contains("LOG SDR") &&
                colorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020 ->
                "LOG SDR (Rec.2020 8-bit)"
            requestedDynamicRangeLabel.contains("Wide Gamut") &&
                colorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020 && useTenBit ->
                "Wide Gamut SDR (Rec.2020 10-bit)"
            requestedDynamicRangeLabel.contains("Wide Gamut") &&
                colorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020 ->
                "Wide Gamut SDR (Rec.2020 8-bit)"
            colorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_SDR_VIDEO && useTenBit ->
                "Original SDR (Rec.709 10-bit)"
            else -> "Original SDR (Rec.709 8-bit)"
        }

    fun inferRequestedDynamicRangeLabel(colorEncoding: RecordingColorEncodingConfig): String =
        when {
            colorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ->
                Bt2100TransportPolicy.displayLabelForHdrVersion("NDR10+")
            colorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_HLG -> "NDR10+"
            colorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020 -> "Wide Gamut SDR"
            else -> "SDR"
        }

    fun defaultDynamicRangeLabel(videoHdrMode: String, videoColorSpace: String): String =
        resolveLookProfile(
            mime = "video/hevc",
            videoHdrMode = videoHdrMode,
            videoColorSpace = videoColorSpace
        ).requestedDynamicRangeLabel
}
