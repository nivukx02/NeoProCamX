package com.neoproc.neoproccamx.recording

import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log

/**
 * Phase 2: MANDATORY 10-BIT SDR/HDR RECORDING POLICY
 * 
 * Enforces mandatory 10-bit transport for high-end video recording:
 * - NORMAL SDR: 10-bit Rec.709 Main10/P010
 * - PRO SDR: 10-bit Rec.2020 Main10/P010
 * - LOG: 10-bit Rec.2020 + log curve (cinema-oriented encoding)
 * - HDR: 10-bit Rec.2020 + HDR metadata (10-bit container)
 * 
 * NO fallback to 8-bit: Device must support HEVC 10-bit or fail hard.
 */
class Mandatory10BitRecordingPolicy {
    
    private val TAG = "Mandatory10BitRecording"
    
    /**
     * Check if device supports mandatory 10-bit HEVC video encoding
     */
    fun supportsHevc10Bit(): Boolean {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue
            if (!codecInfo.supportedTypes.contains("video/hevc")) continue
            
            try {
                val capabilities = codecInfo.getCapabilitiesForType("video/hevc") ?: continue
                val hasMain10Profile = capabilities.profileLevels.any { profileLevel ->
                    profileLevel.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                }
                if (hasMain10Profile) {
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking codec capabilities", e)
            }
        }
        
        return false
    }
    
    /**
     * Get best available 10-bit HEVC encoder
     */
    fun getBestHevc10BitEncoder(): String? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue
            if (!codecInfo.supportedTypes.contains("video/hevc")) continue
            
            try {
                val capabilities = codecInfo.getCapabilitiesForType("video/hevc") ?: continue
                val hasMain10Profile = capabilities.profileLevels.any { profileLevel ->
                    profileLevel.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                }
                if (!hasMain10Profile) continue

                // Prefer hardware encoders
                if (!codecInfo.isHardwareAccelerated) continue
                
                return codecInfo.name
            } catch (e: Exception) {
                Log.w(TAG, "Error querying codec ${codecInfo.name}", e)
            }
        }
        
        return null
    }
    
    /**
     * Validate device supports mandatory 10-bit SDR/HDR recording
     * Throws CriticalHardwareException if not supported
     */
    fun validateMandatory10Bit(): Boolean {
        val supportsHevc10 = supportsHevc10Bit()
        val encoderName = getBestHevc10BitEncoder()
        
        Log.i(TAG, "10-bit HEVC Support: $supportsHevc10, Encoder: $encoderName")
        
        if (!supportsHevc10 || encoderName == null) {
            throw com.neoproc.neoproccamx.camera.CriticalHardwareException(
                "Device does not support mandatory 10-bit HEVC video encoding. " +
                "High-end Normal SDR/Pro SDR/LOG/HDR recording requires 10-bit Main10 codec support. " +
                "This device is incompatible with strict high-end recording mode."
            )
        }
        
        return true
    }
    
    /**
     * Get color space descriptor for 10-bit SDR/HDR recording
     */
    fun getColorSpaceDescriptor(): String {
        return buildString {
            append("Color Space: SDR/HDR Main10 (10-bit)\n")
            append("  Normal SDR Gamut: ITU-R BT.709\n")
            append("  Pro SDR/LOG/HDR Gamut: ITU-R BT.2020\n")
            append("  Transfer: Linear / Log (depending on mode)\n")
            append("  Matrix: BT.709 or BT.2020 non-constant luminance\n")
            append("  Bit Depth: 10-bit YCbCr 4:2:0\n")
            append("  Primaries: D65\n")
        }
    }
}
