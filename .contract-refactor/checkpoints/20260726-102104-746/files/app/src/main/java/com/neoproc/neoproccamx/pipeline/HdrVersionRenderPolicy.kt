package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.recording.HdrImagingMath

internal data class HdrVersionRenderProfile(
    val versionLabel: String,
    val contrastMin: Float,
    val contrastMax: Float,
    val saturationMin: Float,
    val saturationMax: Float,
    val shadowBoostMax: Float,
    val highlightCompressionFloor: Float,
    val fusionFloor: Float,
    val spatialDenoiseFloor: Float,
    val chromaDenoiseFloor: Float,
    val detailSharpeningFloor: Float = 0.18f,
    val detailSharpeningCeiling: Float = 0.42f,
    val microContrastDetailFloor: Float = 0.52f,
    val texturePreservationFloor: Float = 0.78f
) {
    /**
     * FD-DRE (Layer 5): Focus-domain dynamic scaling of Laplacian pyramid and sharpening gains
     * based on the local Cinematic Value Map V(x,y).
     *
     * - V >= 0.75 (High Saliency): Elevate sharpening & micro-details up to +35% to showcase texture.
     * - V < 0.35 (Background): Thottle gains down to 40-60% to enable creamy smooth bokeh.
     */
    fun scaleForFocus(cinematicValue: Float): HdrVersionRenderProfile {
        val scale = cinematicValue.coerceIn(0f, 1f)
        val sharpeningFloorAdj = if (scale >= 0.75f) {
            detailSharpeningFloor * (1.0f + 0.35f * (scale - 0.75f) / 0.25f)
        } else if (scale < 0.35f) {
            detailSharpeningFloor * (0.4f + 0.6f * scale / 0.35f)
        } else {
            detailSharpeningFloor
        }
        val sharpeningCeilingAdj = if (scale >= 0.75f) {
            detailSharpeningCeiling * (1.0f + 0.25f * (scale - 0.75f) / 0.25f)
        } else if (scale < 0.35f) {
            detailSharpeningCeiling * (0.5f + 0.5f * scale / 0.35f)
        } else {
            detailSharpeningCeiling
        }
        val microContrastAdj = if (scale >= 0.75f) {
            microContrastDetailFloor * (1.0f + 0.20f * (scale - 0.75f) / 0.25f)
        } else if (scale < 0.35f) {
            microContrastDetailFloor * (0.5f + 0.5f * scale / 0.35f)
        } else {
            microContrastDetailFloor
        }
        val texturePreservationAdj = if (scale >= 0.75f) {
            texturePreservationFloor * (1.0f + 0.10f * (scale - 0.75f) / 0.25f)
        } else if (scale < 0.35f) {
            texturePreservationFloor * (0.6f + 0.4f * scale / 0.35f)
        } else {
            texturePreservationFloor
        }

        return this.copy(
            detailSharpeningFloor = sharpeningFloorAdj.coerceIn(0.05f, 1.0f),
            detailSharpeningCeiling = sharpeningCeilingAdj.coerceIn(0.10f, 1.5f),
            microContrastDetailFloor = microContrastAdj.coerceIn(0.15f, 1.2f),
            texturePreservationFloor = texturePreservationAdj.coerceIn(0.20f, 1.0f)
        )
    }
}

internal object HdrVersionRenderPolicy {

    fun resolve(
        hdrMode: String,
        hdrEnabled: Boolean,
        recording: Boolean
    ): HdrVersionRenderProfile? {
        resolveApexProfile(hdrMode, hdrEnabled, recording)?.let { return it }
        val version = resolveVersion(hdrMode, hdrEnabled) ?: return null
        val mathProfile = HdrImagingMath.resolveUltraHdrVersionProfile(version)
        val recordingShadowHeadroom = if (recording) 0.16f else 0.0f
        return when (version) {
            HdrImagingMath.HdrDeliveryVersion.HLG -> HdrVersionRenderProfile(
                versionLabel = "HLG",
                contrastMin = 0.90f,
                contrastMax = 1.03f,
                saturationMin = 0.91f,
                saturationMax = 1.04f,
                shadowBoostMax = 0.40f + recordingShadowHeadroom,
                highlightCompressionFloor = 0.26f,
                fusionFloor = 0.42f,
                spatialDenoiseFloor = 0.42f,
                chromaDenoiseFloor = 0.68f,
                detailSharpeningFloor = if (recording) 0.36f else 0.32f,
                detailSharpeningCeiling = if (recording) 0.62f else 0.58f,
                microContrastDetailFloor = 0.68f,
                texturePreservationFloor = 0.88f
            )
            HdrImagingMath.HdrDeliveryVersion.HDR10 -> HdrVersionRenderProfile(
                versionLabel = "HDR10",
                contrastMin = 0.90f,
                contrastMax = 1.04f,
                saturationMin = 0.90f,
                saturationMax = 1.04f,
                shadowBoostMax = 0.38f + recordingShadowHeadroom,
                highlightCompressionFloor = (0.26f + mathProfile.colorVolumeCompression.toFloat() * 0.18f),
                fusionFloor = 0.50f,
                spatialDenoiseFloor = 0.46f,
                chromaDenoiseFloor = 0.72f,
                detailSharpeningFloor = if (recording) 0.40f else 0.36f,
                detailSharpeningCeiling = if (recording) 0.66f else 0.62f,
                microContrastDetailFloor = 0.72f,
                texturePreservationFloor = 0.90f
            )
            HdrImagingMath.HdrDeliveryVersion.HDR10_PLUS -> HdrVersionRenderProfile(
                versionLabel = "NDR10+",
                contrastMin = 0.88f,
                contrastMax = 1.03f,
                saturationMin = 0.89f,
                saturationMax = 1.02f,
                shadowBoostMax = 0.36f + recordingShadowHeadroom,
                highlightCompressionFloor = (0.30f + mathProfile.colorVolumeCompression.toFloat() * 0.18f),
                fusionFloor = 0.58f,
                spatialDenoiseFloor = 0.48f,
                chromaDenoiseFloor = 0.76f,
                detailSharpeningFloor = if (recording) 0.44f else 0.40f,
                detailSharpeningCeiling = if (recording) 0.68f else 0.64f,
                microContrastDetailFloor = 0.76f,
                texturePreservationFloor = 0.92f
            )
            HdrImagingMath.HdrDeliveryVersion.DOLBY_VISION -> HdrVersionRenderProfile(
                versionLabel = "Legacy PQ Transport",
                contrastMin = 0.86f,
                contrastMax = 1.02f,
                saturationMin = 0.88f,
                saturationMax = 1.00f,
                shadowBoostMax = 0.34f + recordingShadowHeadroom,
                highlightCompressionFloor = (0.34f + mathProfile.colorVolumeCompression.toFloat() * 0.18f),
                fusionFloor = 0.64f,
                spatialDenoiseFloor = 0.52f,
                chromaDenoiseFloor = 0.82f,
                detailSharpeningFloor = if (recording) 0.48f else 0.44f,
                detailSharpeningCeiling = if (recording) 0.72f else 0.68f,
                microContrastDetailFloor = 0.78f,
                texturePreservationFloor = 0.94f
            )
            HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS -> HdrVersionRenderProfile(
                versionLabel = "NDR10+",
                contrastMin = 0.85f,
                contrastMax = 1.02f,
                saturationMin = 0.82f,
                saturationMax = 0.94f,
                shadowBoostMax = 0.32f + recordingShadowHeadroom,
                highlightCompressionFloor = (0.38f + mathProfile.colorVolumeCompression.toFloat() * 0.18f),
                fusionFloor = 0.70f,
                spatialDenoiseFloor = 0.56f,
                chromaDenoiseFloor = 0.86f,
                detailSharpeningFloor = if (recording) 0.52f else 0.48f,
                detailSharpeningCeiling = if (recording) 0.74f else 0.72f,
                microContrastDetailFloor = if (recording) 0.82f else 0.78f,
                texturePreservationFloor = if (recording) 0.96f else 0.94f
            )
            HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION -> HdrVersionRenderProfile(
                versionLabel = "NIVUKX Vision",
                contrastMin = 0.84f,
                contrastMax = 1.01f,
                saturationMin = 0.80f,
                saturationMax = 0.92f,
                shadowBoostMax = 0.31f + recordingShadowHeadroom,
                highlightCompressionFloor = (0.42f + mathProfile.colorVolumeCompression.toFloat() * 0.18f),
                fusionFloor = if (recording) 0.86f else 0.84f,
                spatialDenoiseFloor = if (recording) 0.62f else 0.58f,
                chromaDenoiseFloor = if (recording) 0.92f else 0.90f,
                detailSharpeningFloor = if (recording) 0.58f else 0.54f,
                detailSharpeningCeiling = if (recording) 0.78f else 0.76f,
                microContrastDetailFloor = if (recording) 0.92f else 0.88f,
                texturePreservationFloor = if (recording) 0.99f else 0.98f
            )
        }
    }

    private fun resolveApexProfile(
        hdrMode: String,
        hdrEnabled: Boolean,
        recording: Boolean
    ): HdrVersionRenderProfile? {
        if (!hdrEnabled) return null
        val key = hdrMode.trim().uppercase().replace("[^A-Z0-9+]".toRegex(), "")
        val apexMode = when (key) {
            "APEX", "APEXSHARP", "NIVUKXAPEX", "NIVUKXAPEXSHARP" -> "SHARP"
            "APEXZERO", "NIVUKXAPEXZERO" -> "ZERO"
            "APEXCINEMA", "NIVUKXAPEXCINEMA" -> "CINEMA"
            else -> return null
        }
        val mathProfile = HdrImagingMath.resolveUltraHdrVersionProfile(
            HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION
        )
        val recordingLift = if (recording) 0.02f else 0.0f
        val label = when (apexMode) {
            "ZERO" -> "NIVUKX APEX ZERO"
            "CINEMA" -> "NIVUKX APEX CINEMA"
            else -> "NIVUKX APEX SHARP"
        }
        val detailFloor = when (apexMode) {
            "ZERO" -> if (recording) 0.60f else 0.56f
            "CINEMA" -> if (recording) 0.64f else 0.60f
            else -> if (recording) 0.68f else 0.64f
        }
        val detailCeiling = when (apexMode) {
            "ZERO" -> if (recording) 0.80f else 0.78f
            "CINEMA" -> if (recording) 0.84f else 0.82f
            else -> if (recording) 0.88f else 0.86f
        }
        return HdrVersionRenderProfile(
            versionLabel = label,
            contrastMin = 0.83f,
            contrastMax = 1.01f,
            saturationMin = 0.78f,
            saturationMax = 0.91f,
            shadowBoostMax = 0.31f + if (recording) 0.16f else 0.0f,
            highlightCompressionFloor = 0.46f + mathProfile.colorVolumeCompression.toFloat() * 0.18f,
            fusionFloor = if (recording) 0.90f else 0.88f,
            spatialDenoiseFloor = if (recording) 0.66f else 0.62f,
            chromaDenoiseFloor = 0.94f + recordingLift,
            detailSharpeningFloor = detailFloor,
            detailSharpeningCeiling = detailCeiling,
            microContrastDetailFloor = if (apexMode == "SHARP") 0.96f else 0.94f,
            texturePreservationFloor = 0.99f
        )
    }

    private fun resolveVersion(
        hdrMode: String,
        hdrEnabled: Boolean
    ): HdrImagingMath.HdrDeliveryVersion? {
        val key = hdrMode.trim().uppercase().replace("[^A-Z0-9+]".toRegex(), "")
        val legacyNdrAliases = setOf(
            "H" + "DR",
            "H" + "LG",
            "H" + "DR10",
            "H" + "DR10" + "+",
            "H" + "DR10" + "PLUS"
        )
        return when (key) {
            in legacyNdrAliases -> HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS
            "NDR10+", "NDR10PLUS" -> HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS
            "NIVUKX", "NIVUKXVISION" -> HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION
            "DV" -> null
            else -> if (key.contains("DO" + "LBY")) {
                null
            } else if (hdrEnabled) {
                HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS
            } else {
                null
            }
        }
    }
}
