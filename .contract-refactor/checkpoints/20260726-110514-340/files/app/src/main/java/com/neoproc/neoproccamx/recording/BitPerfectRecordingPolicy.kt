package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.util.Log
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

/**
 * Phase 2: high-bitrate quality-preserving RECORDING POLICY
 * 
 * Requests high-bitrate quality-preserving uncompressed/intra-only recording:
 * 1. Bitrate floor: 2.5 Gbps for 8K sensor-native, 1.5 Gbps for 4K
 * 2. All-I (intra-only) encoding: Every frame is a keyframe (I-frame interval = 0)
 * 3. Reduced temporal smoothing request for each frame
 * 4. High bitrate target to reduce visible quantization artifacts when supported
 * 
 * Result: high-bitrate quality-preserving cinema-oriented recording without temporal compression
 */
class BitPerfectRecordingPolicy {
    
    private val TAG = "QualityPreservingRecording"
    
    data class BitPerfectConfig(
        val resolution: Resolution,
        val minimumBitrateBps: Long,
        val isAllIntraEnabled: Boolean,
        val iFrameIntervalFrames: Int,  // 0 = every frame is keyframe
        val description: String
    )

    data class Sdr10AllIntraConfig(
        val minimumBitrateBps: Long,
        val maximumBitrateBps: Long,
        val isAllIntraRequired: Boolean,
        val iFrameIntervalSeconds: Float,
        val bitrateMode: Int,
        val hdrLookBypass: Boolean,
        val tenBitMain10Required: Boolean,
        val colorPrimaries: String,
        val colorTransfer: String
    )
    
    enum class Resolution(val widthPixels: Int, val heightPixels: Int, val megapixels: Int) {
        // Sensor-native full-frame resolutions
        RESOLUTION_8K_NATIVE(8640, 4860, 42),      // 200MP crop to ~40MP
        RESOLUTION_8K_CINEMA(7680, 4320, 33),      // DCI 8K
        RESOLUTION_6K(6144, 3160, 19),             // RED 6K equiv
        RESOLUTION_4K_CINEMA(4096, 2160, 9),       // DCI 4K
        RESOLUTION_4K_DCI_FULL(4096, 2304, 9),     // DCI 4K 17:9
        RESOLUTION_4K_UHD(3840, 2160, 8),          // UHD
        RESOLUTION_2K(2048, 1080, 2);               // DCI 2K
    }
    
    /**
     * Get high-bitrate quality-preserving configuration for a specific resolution
     * Bitrate target preserves as much requested sensor detail as the configured encoder path supports
     */
    fun getBitPerfectConfig(resolution: Resolution): BitPerfectConfig {
        return when (resolution) {
            // 8K: HEVC 4:2:0 10-bit @ 2.5 Gbps (all-I)
            Resolution.RESOLUTION_8K_NATIVE -> BitPerfectConfig(
                resolution = resolution,
                minimumBitrateBps = 2_500_000_000L,  // 2.5 Gbps
                isAllIntraEnabled = true,
                iFrameIntervalFrames = 0,           // Every frame is keyframe
                description = "8K Sensor-Native (42MP), All-I HEVC, 2.5 Gbps"
            )
            
            Resolution.RESOLUTION_8K_CINEMA -> BitPerfectConfig(
                resolution = resolution,
                minimumBitrateBps = 2_000_000_000L,  // 2.0 Gbps
                isAllIntraEnabled = true,
                iFrameIntervalFrames = 0,
                description = "DCI 8K (33MP), All-I HEVC, 2.0 Gbps"
            )
            
            Resolution.RESOLUTION_6K -> BitPerfectConfig(
                resolution = resolution,
                minimumBitrateBps = 1_200_000_000L,  // 1.2 Gbps
                isAllIntraEnabled = true,
                iFrameIntervalFrames = 0,
                description = "6K (19MP), All-I HEVC, 1.2 Gbps"
            )
            
            // 4K: HEVC 4:2:0 10-bit @ 1.5 Gbps (all-I)
            Resolution.RESOLUTION_4K_CINEMA -> BitPerfectConfig(
                resolution = resolution,
                minimumBitrateBps = 1_500_000_000L,  // 1.5 Gbps
                isAllIntraEnabled = true,
                iFrameIntervalFrames = 0,
                description = "DCI 4K (9MP), All-I HEVC, 1.5 Gbps"
            )
            
            Resolution.RESOLUTION_4K_DCI_FULL -> BitPerfectConfig(
                resolution = resolution,
                minimumBitrateBps = 1_500_000_000L,  // 1.5 Gbps
                isAllIntraEnabled = true,
                iFrameIntervalFrames = 0,
                description = "DCI 4K 17:9 (9MP), All-I HEVC, 1.5 Gbps"
            )
            
            Resolution.RESOLUTION_4K_UHD -> BitPerfectConfig(
                resolution = resolution,
                minimumBitrateBps = 1_500_000_000L,  // 1.5 Gbps
                isAllIntraEnabled = true,
                iFrameIntervalFrames = 0,
                description = "UHD 4K (8MP), All-I HEVC, 1.5 Gbps"
            )
            
            Resolution.RESOLUTION_2K -> BitPerfectConfig(
                resolution = resolution,
                minimumBitrateBps = 800_000_000L,    // 800 Mbps
                isAllIntraEnabled = true,
                iFrameIntervalFrames = 0,
                description = "DCI 2K (2MP), All-I HEVC, 800 Mbps"
            )
        }
    }

    fun resolveSdr10AllIntraConfig(width: Int, height: Int, fps: Int = 25): Sdr10AllIntraConfig {
        val longEdge = maxOf(width, height).coerceAtLeast(1)
        val pixels = width.toLong().coerceAtLeast(1L) * height.toLong().coerceAtLeast(1L)
        val safeFps = fps.coerceAtLeast(1)
        val (baseFloor, baseCeiling) = when {
            longEdge >= 7680 || pixels >= 7680L * 4320L -> 1_500_000_000L to 2_500_000_000L
            longEdge >= 3840 || pixels >= 3840L * 2160L -> 400_000_000L to 800_000_000L
            longEdge >= 1920 || pixels >= 1920L * 1080L -> 200_000_000L to 400_000_000L
            else -> 120_000_000L to 240_000_000L
        }
        val strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        val strictFloor =
            if (strictHighEnd) {
                HighEndQualityPolicy.minimumBitrateBpsLong(width, height, fps = safeFps)
            } else {
                0L
            }
        val strictCeiling =
            if (strictHighEnd) {
                when {
                    longEdge >= 7680 || pixels >= 7680L * 4320L -> 2_500_000_000L
                    longEdge >= 3840 || pixels >= 3840L * 2160L -> 2_000_000_000L
                    longEdge >= 1920 || pixels >= 1920L * 1080L -> 800_000_000L
                    else -> 400_000_000L
                }
            } else {
                baseCeiling
            }
        val floor = maxOf(baseFloor, strictFloor)
        val ceiling = maxOf(strictCeiling, floor)
        return Sdr10AllIntraConfig(
            minimumBitrateBps = floor,
            maximumBitrateBps = ceiling,
            isAllIntraRequired = true,
            iFrameIntervalSeconds = 0f,
            bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            hdrLookBypass = true,
            tenBitMain10Required = true,
            colorPrimaries = "BT.2020",
            colorTransfer = "SDR_VIDEO"
        )
    }

    fun resolveNormalSdrAllIntraConfig(width: Int, height: Int, fps: Int = 25): Sdr10AllIntraConfig {
        val base = resolveSdr10AllIntraConfig(width, height, fps)
        return base.copy(
            hdrLookBypass = true,
            tenBitMain10Required = true,
            colorPrimaries = "BT.709",
            colorTransfer = "SDR_VIDEO"
        )
    }

    fun resolveNLogAllIntraConfig(width: Int, height: Int, fps: Int = 25): Sdr10AllIntraConfig {
        val base = resolveSdr10AllIntraConfig(width, height, fps)
        return base.copy(
            hdrLookBypass = true,
            tenBitMain10Required = true,
            colorPrimaries = "BT.2020",
            colorTransfer = "N-LOG_V3"
        )
    }
    
    /**
     * Calculate required storage for a recording session
     * Storage = (bitrate * duration_seconds) / 8 bytes
     */
    fun calculateStorageRequiredMB(bitrateBps: Long, durationSeconds: Long): Long {
        val bytes = (bitrateBps * durationSeconds) / 8
        return bytes / (1024 * 1024)
    }
    
    /**
     * Get human-readable bitrate string
     */
    fun formatBitrate(bitrateBps: Long): String {
        return when {
            bitrateBps >= 1_000_000_000L -> "${bitrateBps / 1_000_000_000L} Gbps"
            bitrateBps >= 1_000_000L -> "${bitrateBps / 1_000_000L} Mbps"
            bitrateBps >= 1_000L -> "${bitrateBps / 1_000L} Kbps"
            else -> "$bitrateBps bps"
        }
    }
    
    /**
     * Validate that recording device supports all-intra encoding
     */
    fun supportsAllIntraEncoding(): Boolean {
        return supportsAllIntraEncoding(
            width = Resolution.RESOLUTION_4K_UHD.widthPixels,
            height = Resolution.RESOLUTION_4K_UHD.heightPixels,
            frameRate = 25,
            tenBit = false,
            preferredBitrate = AllIntraHevcPolicy.BITRATE_PRO_ALL_INTRA
        )
    }

    fun supportsAllIntraEncoding(probedConfig: AllIntraHevcPolicy.AllIntraConfig?): Boolean {
        return probedConfig != null
    }

    fun supportsAllIntraEncoding(
        width: Int,
        height: Int,
        frameRate: Int,
        tenBit: Boolean,
        preferredBitrate: Int
    ): Boolean {
        return supportsAllIntraEncoding(
            AllIntraHevcPolicy.probe(
                width = width,
                height = height,
                frameRate = frameRate,
                tenBit = tenBit,
                preferredBitrate = preferredBitrate
            )
        )
    }
    
    /**
     * Log high-bitrate quality-preserving recording configuration for diagnostics
     */
    fun logConfiguration(config: BitPerfectConfig) {
        Log.i(TAG, "High-Bitrate Quality-Preserving Recording: ${config.description}")
        Log.i(TAG, "  Resolution: ${config.resolution.widthPixels}x${config.resolution.heightPixels} (${config.resolution.megapixels}MP)")
        Log.i(TAG, "  Bitrate: ${formatBitrate(config.minimumBitrateBps)}")
        Log.i(TAG, "  All-Intra: ${config.isAllIntraEnabled}")
        Log.i(TAG, "  I-Frame Interval: ${if (config.iFrameIntervalFrames == 0) "Every frame (0)" else "${config.iFrameIntervalFrames} frames"}")
    }
}
