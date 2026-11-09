package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.color.NeoLogCurve
import com.neoproc.neoproccamx.recording.RecordingHdrVideoLookPolicy

internal enum class VulkanToneDeliveryMode(val shaderModeFlag: Float) {
    DIRECT(0f),
    PREVIEW_LUT(1f),
    HLG_HDR(2f),
    PQ_HDR(3f),
    NDR10_PLUS_HDR(21f),
    NIVUKX_VISION_HDR(22f),
    RAW_NEUTRAL(4f),
    CINEMA_DEBAYER(5f),  // 12-bit RAW → Cinema-Debayer compute shader (bypasses ISP tone-mapping)
    HLG_MONITOR(6f),
    PQ_MONITOR(7f),
    NDR10_PLUS_MONITOR(41f),
    NIVUKX_VISION_MONITOR(42f),
    RAW_MONITOR(8f),
    LOG_N_LOG_BASE(10f),
    LOG_N_LOG_SHADOW(11f),
    LOG_N_LOG_HIGHLIGHT(12f),
    LOG_N_LOG_CINEMA(13f),
    LOG_MONITOR_N_LOG_BASE(30f),
    LOG_MONITOR_N_LOG_SHADOW(31f),
    LOG_MONITOR_N_LOG_HIGHLIGHT(32f),
    LOG_MONITOR_N_LOG_CINEMA(33f),
    // ═══════════════════════════════════════════════════════════════════
    // NIVUKX APEX MODES — Elite tier above HDR10+ and NIVUKX Vision
    // Uses ICtCp-inspired signal shaping in the existing Vulkan shader.
    // ═══════════════════════════════════════════════════════════════════
    APEX_SHARP(50f),       // Maximum sharpness, punchy midtones, deep blacks
    APEX_ZERO(51f),        // Optical truth, near-linear, no artificial sharpness
    APEX_CINEMA(52f);      // Cinematic balance, silky shadows, film-like rolloff

    fun usesPreviewLut(): Boolean = this == PREVIEW_LUT

    companion object {
        fun resolve(
            previewLutEnabled: Boolean,
            truthfulColorDelivery: Boolean,
            rawVideoDelivery: Boolean,
            rawVideoActive: Boolean,
            logCaptureActive: Boolean,
            normalizedHdrMode: String,
            logCurve: NeoLogCurve = NeoLogCurve.NONE,
            cinemaDebayerActive: Boolean = false,
            previewMonitoringEnabled: Boolean = false
        ): VulkanToneDeliveryMode {
            if (previewMonitoringEnabled && rawVideoActive) {
                return RAW_MONITOR
            }
            if (previewMonitoringEnabled) {
                return when (normalizeNeoHdrKey(normalizedHdrMode)) {
                    "NDR10+" -> NDR10_PLUS_MONITOR
                    "NIVUKXVISION" -> NIVUKX_VISION_MONITOR
                    "APEX_SHARP", "APEX_ZERO", "APEX_CINEMA" -> PQ_MONITOR
                    else -> if (logCaptureActive || logCurve != NeoLogCurve.NONE) {
                        logDeliveryMode(logCurve, monitor = true)
                    } else {
                        DIRECT
                    }
                }
            }
            if (truthfulColorDelivery) {
                if (rawVideoDelivery) {
                    // Cinema-Debayer bypasses ISP tone-mapping for maximum highlight latitude
                    return if (cinemaDebayerActive) CINEMA_DEBAYER else RAW_NEUTRAL
                }
                return when (normalizeNeoHdrKey(normalizedHdrMode)) {
                    "NDR10+" -> NDR10_PLUS_HDR
                    "NIVUKXVISION" -> NIVUKX_VISION_HDR
                    "APEX_SHARP" -> APEX_SHARP
                    "APEX_ZERO" -> APEX_ZERO
                    "APEX_CINEMA" -> APEX_CINEMA
                    else -> if (logCaptureActive || logCurve != NeoLogCurve.NONE) {
                        logDeliveryMode(logCurve, monitor = false)
                    } else {
                        DIRECT
                    }
                }
            }
            return if (previewLutEnabled) PREVIEW_LUT else DIRECT
        }

        private fun normalizeNeoHdrKey(value: String): String =
            RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(value)

        private fun logDeliveryMode(curve: NeoLogCurve, monitor: Boolean): VulkanToneDeliveryMode =
            when (curve) {
                NeoLogCurve.N_LOG_SHADOW ->
                    if (monitor) LOG_MONITOR_N_LOG_SHADOW else LOG_N_LOG_SHADOW
                NeoLogCurve.N_LOG_HIGHLIGHT ->
                    if (monitor) LOG_MONITOR_N_LOG_HIGHLIGHT else LOG_N_LOG_HIGHLIGHT
                NeoLogCurve.N_LOG_CINEMA ->
                    if (monitor) LOG_MONITOR_N_LOG_CINEMA else LOG_N_LOG_CINEMA
                else ->
                    if (monitor) LOG_MONITOR_N_LOG_BASE else LOG_N_LOG_BASE
            }
    }
}
