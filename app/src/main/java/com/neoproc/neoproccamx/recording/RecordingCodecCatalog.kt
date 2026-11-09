package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * VIDEO CODEC DESCRIPTOR - cinema-oriented ENCODING
 * ════════════════════════════════════════════════════════════════════════════════
 */
internal data class RecordingCodecDescriptor(
    val codec: GpuVideoCodec,
    val mime: String,
    val label: String,
    val fullLabel: String,
    val containerExtension: String,
    val mediaStoreMime: String,
    val bitrateMultiplier: Float,
    val supports10Bit: Boolean,
    val supportsHdr: Boolean,
    val maxBitrateMbps: Int,
    val cinemaGrade: Boolean
)

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * PROFESSIONAL VIDEO CODEC CATALOG
 * ════════════════════════════════════════════════════════════════════════════════
 * 
 * cinema-oriented video codec configurations matching professional standards:
 * - HEVC: High efficiency, 10-bit HDR support, up to 8K
 * - AVC: Maximum compatibility, broad hardware support
 * - AV1: Next-gen efficiency, excellent quality-to-size ratio
 */
internal object RecordingCodecCatalog {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BITRATE QUALITY TIERS - UPGRADED TO HIGH-END CINEMA STANDARDS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /** FLAGSHIP: Master-grade bitrate multiplier for sensor-native recording */
    const val CINEMA_BITRATE_MULTIPLIER = 12.0f
    
    /** FLAGSHIP: Professional bitrate multiplier */
    const val PROFESSIONAL_BITRATE_MULTIPLIER = 8.5f
    
    /** FLAGSHIP: Standard bitrate multiplier (still high quality) */
    const val STANDARD_BITRATE_MULTIPLIER = 6.5f
    
    /** 10-bit encoding bitrate boost */
    const val TEN_BIT_BITRATE_BOOST = 3.5f
    
    /** HDR encoding bitrate boost */
    const val HDR_BITRATE_BOOST = 3.5f
    
    /** LOG encoding bitrate boost */
    const val LOG_BITRATE_BOOST = 3.0f

    /** Runtime ABR ceiling for professional HEVC/AV1 sessions. */
    const val PROFESSIONAL_RUNTIME_BITRATE_CEILING_BPS = 400_000_000
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CODEC DESCRIPTORS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val descriptors = listOf(
        RecordingCodecDescriptor(
            codec = GpuVideoCodec.HEVC,
            mime = "video/hevc",
            label = "HEVC",
            fullLabel = "HEVC (H.265) - Cinema Flagship",
            containerExtension = "mp4",
            mediaStoreMime = "video/mp4",
            bitrateMultiplier = 1.0f,
            supports10Bit = true,
            supportsHdr = true,
            maxBitrateMbps = 2000,  // FLAGSHIP: 2.0Gbps request ceiling, still encoder-capability clamped
            cinemaGrade = true
        ),
        RecordingCodecDescriptor(
            codec = GpuVideoCodec.AVC,
            mime = "video/avc",
            label = "AVC",
            fullLabel = "AVC (H.264) - Maximum Compatibility",
            containerExtension = "mp4",
            mediaStoreMime = "video/mp4",
            bitrateMultiplier = 1.30f,  // FLAGSHIP: Higher AVC quality
            supports10Bit = false,
            supportsHdr = false,
            maxBitrateMbps = 1000,  // FLAGSHIP: 1.0Gbps request ceiling for high-res AVC
            cinemaGrade = false
        ),
        RecordingCodecDescriptor(
            codec = GpuVideoCodec.AV1,
            mime = "video/av01",
            label = "AV1",
            fullLabel = "AV1 - Next-Gen Efficiency",
            containerExtension = "mp4",
            mediaStoreMime = "video/mp4",
            bitrateMultiplier = 0.82f,  // AV1 is more efficient
            supports10Bit = true,
            supportsHdr = true,
            maxBitrateMbps = 1600,  // FLAGSHIP: 1.6Gbps request ceiling for AV1 full sensor
            cinemaGrade = true
        )
    ).associateBy { it.codec }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // I-FRAME INTERVAL PRESETS - UPGRADED FOR BETTER QUALITY
    // ═══════════════════════════════════════════════════════════════════════════
    
    /** Cinema quality: one-second realtime GOP for full cadence and efficient Main10 allocation. */
    const val IFRAME_INTERVAL_CINEMA = RecordingEncoderCadencePolicy.MIN_REALTIME_GOP_SECONDS

    /** Professional quality: one-second realtime GOP. */
    const val IFRAME_INTERVAL_PROFESSIONAL = RecordingEncoderCadencePolicy.MIN_REALTIME_GOP_SECONDS

    /** Standard quality: one-second realtime GOP. */
    const val IFRAME_INTERVAL_STANDARD = RecordingEncoderCadencePolicy.MIN_REALTIME_GOP_SECONDS
    
    /** High efficiency - keyframe every 1 second */
    const val IFRAME_INTERVAL_EFFICIENT = 1.0f

    fun descriptor(codec: GpuVideoCodec): RecordingCodecDescriptor =
        descriptors.getValue(codec)

    fun mime(codec: GpuVideoCodec): String =
        descriptor(codec).mime

    fun label(codec: GpuVideoCodec): String =
        descriptor(codec).label
    
    fun fullLabel(codec: GpuVideoCodec): String =
        descriptor(codec).fullLabel

    fun containerExtension(codec: GpuVideoCodec): String =
        descriptor(codec).containerExtension

    fun mediaStoreMime(codec: GpuVideoCodec): String =
        descriptor(codec).mediaStoreMime

    fun bitrateMultiplier(codec: GpuVideoCodec): Float =
        descriptor(codec).bitrateMultiplier
    
    fun supports10Bit(codec: GpuVideoCodec): Boolean =
        descriptor(codec).supports10Bit
    
    fun supportsHdr(codec: GpuVideoCodec): Boolean =
        descriptor(codec).supportsHdr
    
    fun maxBitrateMbps(codec: GpuVideoCodec): Int =
        descriptor(codec).maxBitrateMbps

    fun maxRuntimeBitrateBps(
        codec: GpuVideoCodec,
        qualityTier: QualityTier
    ): Int {
        val descriptorCeiling = descriptor(codec).maxBitrateMbps * 1_000_000
        if (HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly) {
            return descriptorCeiling
        }
        val professionalCeiling =
            if (
                (qualityTier == QualityTier.CINEMA || qualityTier == QualityTier.PROFESSIONAL) &&
                (codec == GpuVideoCodec.HEVC || codec == GpuVideoCodec.AV1)
            ) {
                PROFESSIONAL_RUNTIME_BITRATE_CEILING_BPS
            } else {
                descriptorCeiling
            }
        return minOf(descriptorCeiling, professionalCeiling)
    }

    fun requireHardwareP010Encoding(
        codec: GpuVideoCodec,
        p010Requested: Boolean,
        hardwareAccelerated: Boolean,
        main10Supported: Boolean,
        nativeP010InputSupported: Boolean
    ) {
        if (!p010Requested) return
        if (
            supports10Bit(codec) &&
            hardwareAccelerated &&
            main10Supported &&
            nativeP010InputSupported
        ) {
            return
        }
        throw RecoverableCodecConfigurationException(
            "Hardware 10-bit P010 encoding requested but unavailable " +
                "(codec=$codec hardware=$hardwareAccelerated main10=$main10Supported p010=$nativeP010InputSupported)"
        )
    }
    
    fun isCinemaGrade(codec: GpuVideoCodec): Boolean =
        descriptor(codec).cinemaGrade
    
    /**
     * Calculate total bitrate multiplier based on encoding options.
     */
    fun totalBitrateMultiplier(
        codec: GpuVideoCodec,
        qualityTier: QualityTier = QualityTier.STANDARD,
        tenBit: Boolean = false,
        hdr: Boolean = false,
        log: Boolean = false
    ): Float {
        var multiplier = bitrateMultiplier(codec)
        
        multiplier *= when (qualityTier) {
            QualityTier.CINEMA -> CINEMA_BITRATE_MULTIPLIER
            QualityTier.PROFESSIONAL -> PROFESSIONAL_BITRATE_MULTIPLIER
            QualityTier.STANDARD -> STANDARD_BITRATE_MULTIPLIER
        }
        
        if (tenBit && supports10Bit(codec)) {
            multiplier *= TEN_BIT_BITRATE_BOOST
        }
        
        if (hdr && supportsHdr(codec)) {
            multiplier *= HDR_BITRATE_BOOST
        }
        
        if (log) {
            multiplier *= LOG_BITRATE_BOOST
        }

        if (HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly) {
            multiplier *= when (qualityTier) {
                QualityTier.CINEMA -> 3.50f        // FLAGSHIP: Maximum cinema
                QualityTier.PROFESSIONAL -> 2.80f   // FLAGSHIP: High professional
                QualityTier.STANDARD -> 2.20f       // FLAGSHIP: Premium standard
            }
        }
        
        return multiplier
    }
    
    /**
     * Get recommended I-frame interval based on quality tier.
     */
    fun iframeInterval(qualityTier: QualityTier): Float = when (qualityTier) {
        QualityTier.CINEMA -> IFRAME_INTERVAL_CINEMA
        QualityTier.PROFESSIONAL -> IFRAME_INTERVAL_PROFESSIONAL
        QualityTier.STANDARD -> IFRAME_INTERVAL_STANDARD
    }

    fun toVideoCodecType(codec: GpuVideoCodec): VideoCodecType =
        when (codec) {
            GpuVideoCodec.AVC -> VideoCodecType.AVC
            GpuVideoCodec.AV1 -> VideoCodecType.AV1
            GpuVideoCodec.HEVC -> VideoCodecType.HEVC
        }

    fun resolveRequestedCodec(
        requested: VideoCodecType,
        av1Supported: Boolean
    ): GpuVideoCodec =
        when (requested) {
            VideoCodecType.AVC -> GpuVideoCodec.AVC
            VideoCodecType.AV1 -> if (av1Supported) GpuVideoCodec.AV1 else GpuVideoCodec.HEVC
            VideoCodecType.HEVC -> GpuVideoCodec.HEVC
        }

    fun resolvePersistedCodecName(
        value: String?,
        av1Supported: Boolean
    ): GpuVideoCodec =
        when (value?.trim()?.uppercase()) {
            "AVC" -> GpuVideoCodec.AVC
            "AV1" -> resolveRequestedCodec(VideoCodecType.AV1, av1Supported)
            else -> GpuVideoCodec.HEVC
        }
    
    /**
     * Quality tier for video encoding.
     */
    enum class QualityTier {
        /** Maximum quality for professional/cinema work */
        CINEMA,
        /** High quality for professional use */
        PROFESSIONAL,
        /** Standard quality for general use */
        STANDARD
    }
}

internal class RecoverableCodecConfigurationException(message: String) : IllegalStateException(message)
