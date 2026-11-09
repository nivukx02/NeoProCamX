package com.neoproc.neoproccamx.recording

import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  NEO RADIANCE METADATA ENCODER — Spectral Container Metadata Policy
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Builds the 25-byte spectral static metadata blob attached to the encoder
 * MediaFormat via [MediaFormat.KEY_HDR_STATIC_INFO]. This is the NeoProCamX
 * original metadata path — every byte is encoded with first-principles
 * precision for downstream color-accurate playback.
 *
 * Layout (CTA-861-G compatible, all multi-byte values little-endian):
 * ```
 *   Byte 0     : type (0x00 = Static Metadata Type 1)
 *   Bytes 1-2  : display_primary_red_x   (×50000 chromaticity)
 *   Bytes 3-4  : display_primary_red_y
 *   Bytes 5-6  : display_primary_green_x
 *   Bytes 7-8  : display_primary_green_y
 *   Bytes 9-10 : display_primary_blue_x
 *   Bytes 11-12: display_primary_blue_y
 *   Bytes 13-14: white_point_x
 *   Bytes 15-16: white_point_y
 *   Bytes 17-18: max_display_mastering_luminance (cd/m²)
 *   Bytes 19-20: min_display_mastering_luminance (×10000 cd/m²)
 *   Bytes 21-22: max_content_light_level         (MaxCLL, cd/m²)
 *   Bytes 23-24: max_pic_average_light_level     (MaxFALL, cd/m²)
 * ```
 *
 * Default mastering display: BT.2020 D65 at 1000 nits — the NeoRadiance
 * spectral reference calibrated for cinema-oriented delivery.
 */
object HdrStaticMetadataPolicy {

    /**
     * Encoded mastering display chromaticities + luminance + light-level
     * limits. Defaults reproduce the BT.2020 D65 / 1000-nit reference
     * mastering display widely used in HDR10 grading.
     */
    data class MasteringDisplay(
        // Display primaries — chromaticity coordinates in [0,1], encoded
        // as ×50000 in the 16-bit fields per CTA-861-G.
        val redX: Double = 0.708,
        val redY: Double = 0.292,
        val greenX: Double = 0.170,
        val greenY: Double = 0.797,
        val blueX: Double = 0.131,
        val blueY: Double = 0.046,
        // White point (D65)
        val whiteX: Double = 0.3127,
        val whiteY: Double = 0.3290,
        // Mastering luminance in cd/m² (nits).
        val maxLuminanceNits: Double = 1000.0,
        val minLuminanceNits: Double = 0.0001,
        // Content light levels (per-stream caps).
        val maxContentLightLevelNits: Int = 1000,
        val maxFrameAverageLightLevelNits: Int = 400
    ) {
        companion object {
            /**
             * BT.2020 D65 1000-nit reference mastering display — the
             * industry-standard fallback used by virtually all HDR10
             * cameras and grading suites when an explicit master isn't
             * available.
             */
            val BT2020_D65_1000_NITS: MasteringDisplay = MasteringDisplay()

            /**
             * BT.2020 D65 4000-nit reference — used when targeting
             * theatrical HDR where peak brightness can
             * reach 4000 nits.
             */
            val BT2020_D65_4000_NITS: MasteringDisplay = MasteringDisplay(
                maxLuminanceNits = 4000.0,
                maxContentLightLevelNits = 4000,
                maxFrameAverageLightLevelNits = 1000
            )

            /** Exact project-owned NDR10+ mastering contract. */
            val BT2020_D65_5000_NITS: MasteringDisplay = MasteringDisplay(
                maxLuminanceNits = 5000.0,
                maxContentLightLevelNits = 5000,
                maxFrameAverageLightLevelNits = 1250
            )

            /** Exact project-owned NIVUKX Vision mastering contract and full ST.2084 domain. */
            val BT2020_D65_10000_NITS: MasteringDisplay = MasteringDisplay(
                maxLuminanceNits = 10000.0,
                maxContentLightLevelNits = 10000,
                maxFrameAverageLightLevelNits = 2500
            )
        }
    }

    data class SceneMetadata(
        val averageLuma: Double,
        val highlightRatio: Double,
        val dynamicRangeScore: Double,
        val maxCllNits: Double? = null,
        val maxFallNits: Double? = null,
        val masteringPeakNits: Double? = null
    ) {
        companion object {
            val DEFAULT = SceneMetadata(
                averageLuma = 0.42,
                highlightRatio = 0.12,
                dynamicRangeScore = 0.72
            )

            fun fromDescription(description: String?): SceneMetadata {
                if (description.isNullOrBlank()) return DEFAULT
                val fields = description
                    .split(';')
                    .mapNotNull { token ->
                        val index = token.indexOf('=')
                        if (index <= 0 || index >= token.lastIndex) {
                            null
                        } else {
                            token.substring(0, index).trim().lowercase() to
                                token.substring(index + 1).trim()
                        }
                    }
                    .toMap()
                fun doubleValue(vararg keys: String): Double? {
                    for (key in keys) {
                        fields[key.lowercase()]?.toDoubleOrNull()?.let { return it }
                    }
                    return null
                }
                return SceneMetadata(
                    averageLuma = doubleValue("avgLuma", "averageLuma", "luma")
                        ?.coerceIn(0.0, 1.0) ?: DEFAULT.averageLuma,
                    highlightRatio = doubleValue("highlight", "highlightRatio")
                        ?.coerceIn(0.0, 1.0) ?: DEFAULT.highlightRatio,
                    dynamicRangeScore = doubleValue("dynamicRange", "dynamicRangeScore")
                        ?.coerceIn(0.0, 1.0) ?: DEFAULT.dynamicRangeScore,
                    maxCllNits = doubleValue("hdrMaxCLL", "maxCLL")
                        ?.coerceIn(1.0, 65_535.0),
                    maxFallNits = doubleValue("hdrMaxFALL", "maxFALL")
                        ?.coerceIn(1.0, 65_535.0),
                    masteringPeakNits = doubleValue("hdrMasterPeak", "masteringPeak")
                        ?.coerceIn(100.0, 65_535.0)
                )
            }
        }
    }

    /**
     * The three meaningful HDR pipeline outputs as far as encoder static
     * metadata is concerned.
     *
     *  - [HDR10]:    PQ (ST.2084) + BT.2020 → attach 25-byte CTA-861-G blob.
     *  - [HDR10_PLUS]: same static info as PQ static metadata. Neo dynamic
     *                metadata is written as a separate sidecar when needed.
     *  - [HLG]:      HLG (ARIB STD-B67) — no static metadata needed.
     *  - [SDR]:      Standard / Rec.709 — no static metadata.
     */
    enum class StaticMetadataMode {
        SDR,
        HLG,
        HDR10,
        HDR10_PLUS,
        CERTIFIED_VENDOR_FALLBACK;

        companion object {
            /**
             * Derive the correct static-metadata mode from the encoder's
             * configured colour transfer + colour standard (the values
             * already chosen by `RecordingHdrVideoLookPolicy` and stored
             * on `videoPlan.colorEncoding`).
             *
             * Robust against API-level reordering: only well-defined
             * MediaFormat constants are used.
             */
            fun fromColorEncoding(
                colorTransfer: Int,
                colorStandard: Int,
                hdr10PlusRequested: Boolean = false,
                certifiedVendorFallbackRequested: Boolean = false
            ): StaticMetadataMode {
                // Pure decoder: no SDK gate. The SDK gate lives in
                // applyToFormat(...) where it actually matters (the
                // KEY_HDR_STATIC_INFO MediaFormat key requires API N+).
                val isBt2020 = colorStandard == MediaFormat.COLOR_STANDARD_BT2020
                return when {
                    colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 && isBt2020 -> {
                        when {
                            certifiedVendorFallbackRequested -> CERTIFIED_VENDOR_FALLBACK
                            hdr10PlusRequested -> HDR10_PLUS
                            else -> HDR10
                        }
                    }
                    colorTransfer == MediaFormat.COLOR_TRANSFER_HLG && isBt2020 -> HLG
                    else -> SDR
                }
            }
        }
    }

    fun resolveMasteringDisplay(
        mode: StaticMetadataMode,
        sceneMetadata: SceneMetadata = SceneMetadata.DEFAULT
    ): MasteringDisplay {
        val version = when (mode) {
            StaticMetadataMode.HDR10 -> HdrImagingMath.HdrDeliveryVersion.HDR10
            StaticMetadataMode.HDR10_PLUS -> HdrImagingMath.HdrDeliveryVersion.HDR10_PLUS
            StaticMetadataMode.CERTIFIED_VENDOR_FALLBACK -> HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION
            StaticMetadataMode.HLG -> HdrImagingMath.HdrDeliveryVersion.HLG
            StaticMetadataMode.SDR -> return MasteringDisplay.BT2020_D65_1000_NITS
        }
        val estimated = HdrImagingMath.estimateUltraHdrStaticMetadata(
            version = version,
            averageLuma = sceneMetadata.averageLuma,
            highlightRatio = sceneMetadata.highlightRatio,
            dynamicRangeScore = sceneMetadata.dynamicRangeScore
        )
        val explicitMaster = sceneMetadata.masteringPeakNits?.coerceIn(1_000.0, 10_000.0)
        // An explicit project delivery peak describes the pixels actually generated by NDR. Do not
        // let the scene estimator raise metadata above that value: doing so makes playback apply a
        // shoulder for highlight codes that do not exist in the stream.
        val masteringPeak = (explicitMaster ?: estimated.masteringPeakNits)
            .coerceIn(1_000.0, 10_000.0)
        val maxCll = maxOf(
            estimated.maxCllNits,
            sceneMetadata.maxCllNits?.coerceAtMost(masteringPeak) ?: 0.0
        ).coerceIn(400.0, masteringPeak)
        val maxFall = maxOf(
            estimated.maxFallNits,
            sceneMetadata.maxFallNits?.coerceAtMost(maxCll) ?: 0.0
        ).coerceIn(100.0, maxCll)
        return MasteringDisplay.BT2020_D65_1000_NITS.copy(
            maxLuminanceNits = masteringPeak,
            maxContentLightLevelNits = Math.round(maxCll).coerceIn(1L, 65_535L).toInt(),
            maxFrameAverageLightLevelNits = Math.round(maxFall).coerceIn(1L, 65_535L).toInt()
        )
    }

    /**
     * Build the 25-byte CTA-861-G static metadata blob for a PQ transport
     * stream. The buffer is little-endian, position rewound to 0,
     * ready to attach via `MediaFormat.setByteBuffer(KEY_HDR_STATIC_INFO,
     * buffer)`.
     */
    fun buildHdr10StaticInfo(
        masteringDisplay: MasteringDisplay = MasteringDisplay.BT2020_D65_1000_NITS
    ): ByteBuffer {
        val buffer = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x00)                                                                // type
        buffer.putShort(encodeChromaticity(masteringDisplay.redX))                       // R.x
        buffer.putShort(encodeChromaticity(masteringDisplay.redY))                       // R.y
        buffer.putShort(encodeChromaticity(masteringDisplay.greenX))                     // G.x
        buffer.putShort(encodeChromaticity(masteringDisplay.greenY))                     // G.y
        buffer.putShort(encodeChromaticity(masteringDisplay.blueX))                      // B.x
        buffer.putShort(encodeChromaticity(masteringDisplay.blueY))                      // B.y
        buffer.putShort(encodeChromaticity(masteringDisplay.whiteX))                     // W.x
        buffer.putShort(encodeChromaticity(masteringDisplay.whiteY))                     // W.y
        buffer.putShort(encodeMaxLuminance(masteringDisplay.maxLuminanceNits))           // max
        buffer.putShort(encodeMinLuminance(masteringDisplay.minLuminanceNits))           // min
        buffer.putShort(encodeLightLevel(masteringDisplay.maxContentLightLevelNits))     // MaxCLL
        buffer.putShort(encodeLightLevel(masteringDisplay.maxFrameAverageLightLevelNits))// MaxFALL
        buffer.rewind()
        return buffer
    }

    /**
     * Decode the exact 25-byte Android/CTA-861-G static HDR block stored in a NIVUKX track.
     * This is the inverse of [buildHdr10StaticInfo] and lets a packet-copy MOV export reproduce
     * `mdcv`/`clli` metadata without substituting a generic mastering-display preset.
     */
    internal fun parseHdr10StaticInfo(bytes: ByteArray): MasteringDisplay? {
        if (bytes.size != 25 || bytes[0] != 0.toByte()) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.get() // Static Metadata Descriptor ID 0.
        fun unsignedShort(): Int = buffer.short.toInt() and 0xFFFF
        return MasteringDisplay(
            redX = unsignedShort() / 50_000.0,
            redY = unsignedShort() / 50_000.0,
            greenX = unsignedShort() / 50_000.0,
            greenY = unsignedShort() / 50_000.0,
            blueX = unsignedShort() / 50_000.0,
            blueY = unsignedShort() / 50_000.0,
            whiteX = unsignedShort() / 50_000.0,
            whiteY = unsignedShort() / 50_000.0,
            maxLuminanceNits = unsignedShort().toDouble(),
            minLuminanceNits = unsignedShort() / 10_000.0,
            maxContentLightLevelNits = unsignedShort(),
            maxFrameAverageLightLevelNits = unsignedShort()
        )
    }

    /**
     * Apply the appropriate static metadata for [mode] onto the given
     * [MediaFormat]. No-op when [mode] is [StaticMetadataMode.SDR] or
     * [StaticMetadataMode.HLG], and on API < N where KEY_HDR_STATIC_INFO
     * isn't supported.
     *
     * Returns true when a metadata blob was actually attached.
     */
    fun applyToFormat(
        format: MediaFormat,
        mode: StaticMetadataMode,
        masteringDisplay: MasteringDisplay = MasteringDisplay.BT2020_D65_1000_NITS
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return when (mode) {
            StaticMetadataMode.HDR10,
            StaticMetadataMode.HDR10_PLUS,
            StaticMetadataMode.CERTIFIED_VENDOR_FALLBACK -> {
                val buffer = buildHdr10StaticInfo(masteringDisplay)
                format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, buffer)
                // Neo dynamic metadata is written through the app sidecar;
                // this method only attaches the Android static PQ blob. The
                // static metadata blob attached above is still required as
                // the baseline for downstream tone-mapping fallback.
                true
            }
            StaticMetadataMode.HLG, StaticMetadataMode.SDR -> false
        }
    }

    /**
     * Encode a chromaticity coordinate (0..1) to its CTA-861-G fixed-point
     * representation: ×50000, clamped to unsigned 16-bit. Defensive clamp
     * keeps malformed user inputs from corrupting the blob.
     */
    internal fun encodeChromaticity(value: Double): Short {
        // Round-half-up — toLong() alone truncates, which silently shifts
        // the encoded primary by 1 unit when the input contains the kind
        // of binary-float artefact you get from 0.292 × 50000.
        val scaled = Math.round(value * 50000.0)
        return scaled.coerceIn(0L, 65535L).toShort()
    }

    /**
     * Encode a max-luminance value (cd/m²) to a 16-bit unsigned integer.
     * Per CTA-861-G the max field is in cd/m² directly; no scaling.
     */
    internal fun encodeMaxLuminance(nits: Double): Short {
        // Round half-up to avoid silent off-by-one on inputs like 999.999.
        val v = Math.round(nits)
        return v.coerceIn(0L, 65535L).toShort()
    }

    /**
     * Encode a min-luminance value (cd/m²) to a 16-bit unsigned integer.
     * Per CTA-861-G the min field is in 0.0001 cd/m² units.
     */
    internal fun encodeMinLuminance(nits: Double): Short {
        val v = Math.round(nits * 10000.0)
        return v.coerceIn(0L, 65535L).toShort()
    }

    /** MaxCLL/MaxFALL are 16-bit unsigned integers in cd/m² units. */
    internal fun encodeLightLevel(nits: Int): Short {
        return nits.coerceIn(0, 65535).toShort()
    }
}
