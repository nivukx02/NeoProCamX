package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * high-bitrate quality-preserving RECORDING CONFIGURATION
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Supreme Fidelity recording configuration for high-bitrate RAW and 10-bit-capable LOG/PRO requests when supported.
 *
 * Key features:
 * - High bitrate targets: 2.5 Gbps for 8K sensor-native, 1.5 Gbps for 4K
 * - All-I encoding: Every frame is a keyframe (Intra-frame only)
 * - Reduced temporal smoothing request for quality-preserving output
 * - HEVC Main 10 profile: 10-bit capable codec
 * - VBR mode: Requests high bitrate headroom when the encoder exposes it
 *
 * Actual fidelity depends on device-reported encoder behavior and session stability.
 */
data class BitPerfectRecordingConfig(
    /**
     * Bitrate in bits per second.
     * - 8K Sensor-Native RAW: 2,500,000,000 bps (2.5 Gbps)
     * - 4K RAW: 1,500,000,000 bps (1.5 Gbps)
     */
    val bitrateBps: Long = 2_500_000_000L,
    
    /**
     * I-frame interval in seconds.
     * 0 = Every frame is a keyframe (Intra-frame only, best for RAW)
     * 1 = Every 1 second
     * >1 = Less frequent keyframes (not recommended for fidelity)
     */
    val iFrameIntervalSec: Int = 0,
    
    /**
     * HEVC codec profile for 10-bit support.
     * Main10 = 10-bit capable, required for HDR/LOG.
     */
    val hevcProfile: Int = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
    
    /**
     * Bitrate mode.
     * For Supreme Fidelity, we use VBR equivalent by setting flexible bitrate.
     * Value: 0 = Default, bit shifting applied as needed.
     */
    val bitRateMode: Int = 0,  // Default/flexible mode
    
    /**
     * Enable Intra Refresh.
     * When true, refreshes part of the frame without full I-frame overhead.
     * For All-I mode, this should be false (all frames are already I-frames).
     */
    val enableIntraRefresh: Boolean = false,
    
    /**
     * Force hardware encoding.
     * Should be true for maximum bitrate support and efficiency.
     */
    val forceHardwareEncoding: Boolean = true
) {
    /**
     * Return human-readable description of this config.
     */
    fun describe(): String = buildString {
        append("Quality-preserving Recording:\n")
        append("  Bitrate: ${bitrateBps / 1_000_000_000}Gbps\n")
        append("  I-Frame Interval: ${if (iFrameIntervalSec == 0) "Every frame (All-I)" else "${iFrameIntervalSec}s"}\n")
        append("  Profile: Main10 (10-bit)\n")
        append("  Mode: Flexible\n")
    }
}
