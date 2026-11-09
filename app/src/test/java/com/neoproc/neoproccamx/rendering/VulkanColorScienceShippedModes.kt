package com.neoproc.neoproccamx.rendering

/**
 * s2-f03 / CP-C3 — the representative set of shipped color-science configurations whose packed
 * 52-float vector is golden-locked. One entry per distinct shader lane the policy can select
 * (disabled, Rec.709 SDR, Neo Wide Gamut V2-APEX, Neo ACES AP1/AP0 high-end, NDR10+, NIVUKX Vision,
 * N-Log Cinema, HDR-mapping on). Each [resolve] call must remain byte-identical across edits.
 */
internal data class VulkanColorScienceShippedMode(
    val id: String,
    val resolve: () -> VulkanColorScienceUniforms
)

internal object VulkanColorScienceShippedModes {
    val MODES: List<VulkanColorScienceShippedMode> = listOf(
        VulkanColorScienceShippedMode("DISABLED") { VulkanColorScienceUniforms.disabled() },
        VulkanColorScienceShippedMode("REC709_SDR") {
            VulkanColorScienceUniformPolicy.resolve(
                enabled = true,
                hdrMappingEnabled = false,
                logDeliveryEnabled = false,
                nivukxVision = false,
                hdrKnee = 0.59f,
                hdrShoulder = 3.72f,
                volumeCompression = 0.54f,
                colorSpaceName = "Rec.709",
                colorScienceName = "Natural"
            )
        },
        VulkanColorScienceShippedMode("NEO_WIDE_APEX") {
            VulkanColorScienceUniformPolicy.resolve(
                enabled = true,
                hdrMappingEnabled = false,
                logDeliveryEnabled = false,
                nivukxVision = false,
                hdrKnee = 0.59f,
                hdrShoulder = 3.72f,
                volumeCompression = 0.54f,
                colorSpaceName = "Neo Wide Gamut",
                colorScienceName = "NeoGamut APEX"
            )
        },
        VulkanColorScienceShippedMode("NEO_ACES_AP1_HIGH_END") {
            VulkanColorScienceUniformPolicy.resolve(
                enabled = true,
                hdrMappingEnabled = false,
                logDeliveryEnabled = false,
                nivukxVision = false,
                hdrKnee = 0.59f,
                hdrShoulder = 3.72f,
                volumeCompression = 0.54f,
                colorSpaceName = "Neo ACES AP1",
                colorScienceName = "Neo ACES High End"
            )
        },
        VulkanColorScienceShippedMode("NEO_ACES_AP0_HIGH_END") {
            VulkanColorScienceUniformPolicy.resolve(
                enabled = true,
                hdrMappingEnabled = false,
                logDeliveryEnabled = false,
                nivukxVision = false,
                hdrKnee = 0.59f,
                hdrShoulder = 3.72f,
                volumeCompression = 0.54f,
                colorSpaceName = "Neo ACES AP0",
                colorScienceName = "Neo ACES High End"
            )
        },
        VulkanColorScienceShippedMode("NDR10_PLUS") {
            VulkanColorScienceUniformPolicy.resolve(
                enabled = true,
                hdrMappingEnabled = true,
                logDeliveryEnabled = false,
                nivukxVision = false,
                hdrKnee = 0.59f,
                hdrShoulder = 3.72f,
                volumeCompression = 0.54f,
                colorSpaceName = "Rec.2020-NDR10+",
                colorScienceName = "NDR10+",
                measuredCorrection = NdrNivukxMeasuredCorrectionPolicy.resolve("NDR10+")
            )
        },
        VulkanColorScienceShippedMode("NIVUKX_VISION") {
            VulkanColorScienceUniformPolicy.resolve(
                enabled = true,
                hdrMappingEnabled = true,
                logDeliveryEnabled = false,
                nivukxVision = true,
                hdrKnee = 0.55f,
                hdrShoulder = 4.12f,
                volumeCompression = 0.64f,
                colorSpaceName = "Rec.2020-NIVUKX",
                colorScienceName = "NIVUKX Vision",
                measuredCorrection = NdrNivukxMeasuredCorrectionPolicy.resolve("NIVUKXVISION")
            )
        },
        VulkanColorScienceShippedMode("N_LOG_CINEMA") {
            VulkanColorScienceUniformPolicy.resolve(
                enabled = true,
                hdrMappingEnabled = false,
                logDeliveryEnabled = true,
                nivukxVision = false,
                hdrKnee = 0.59f,
                hdrShoulder = 3.72f,
                volumeCompression = 0.54f,
                colorSpaceName = "Rec.2020",
                colorScienceName = "N-Log Cinema"
            )
        }
    )
}
