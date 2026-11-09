package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.color.NeoLogCurve
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.ui.VideoFormatPreviewLookState
import com.neoproc.neoproccamx.ui.VideoModePipelineContract

internal enum class VulkanPreviewRenderPath {
    SDR_DIRECT,
    SDR_LUT,
    HDR_HLG_NATIVE,
    HDR_PQ_NATIVE,
    LOG_ORIGINAL,
    RAW_MONITOR,
    CINEMA_DEBAYER_MONITOR
}

internal enum class VulkanPreviewRenderQuality {
    FAST,
    BALANCED,
    MAXIMUM
}

internal data class VulkanPreviewRenderContract(
    val renderPath: VulkanPreviewRenderPath,
    val swapchainDynamicRangeMode: HdrDynamicRangeMode,
    val toneDeliveryMode: VulkanToneDeliveryMode,
    val truthfulColorDelivery: Boolean,
    val previewMonitoringEnabled: Boolean,
    val rawVideoDelivery: Boolean,
    val rawVideoActive: Boolean,
    val logCaptureActive: Boolean,
    val normalizedHdrMode: String,
    val nativePreviewLutEnabled: Boolean,
    val renderQuality: VulkanPreviewRenderQuality,
    val signature: String,
    val preservesPreviewAspect: Boolean = true
)

/**
 * Authoritative render-only contract for the Vulkan live preview.
 *
 * This policy deliberately has no geometry, crop, rotation, viewport, scissor,
 * or aspect-ratio fields. Preview aspect remains owned by the existing preview
 * transform/framing policies; this file only decides tone delivery, swapchain
 * intent, render path, and quality tier.
 */
internal object VulkanPreviewRenderContractPolicy {

    fun resolve(
        pipelineContract: VideoModePipelineContract,
        previewLookState: VideoFormatPreviewLookState,
        nativePreviewLutEnabled: Boolean,
        nativePreviewLutSize: Int,
        logCurve: NeoLogCurve,
        cinemaDebayerActive: Boolean,
        highLoadPreviewActive: Boolean,
        previewFpsHint: Float,
        cameraPreviewHdrTransportMode: HdrDynamicRangeMode? = null
    ): VulkanPreviewRenderContract {
        val rawVideoActive = pipelineContract.rawSensorOriginal || previewLookState.rawEnabled
        val logCaptureActive = !rawVideoActive &&
            (pipelineContract.logToneMapActive || previewLookState.logEnabled)
        val requestedHdrMode = resolveHdrMode(
            pipelineContract = pipelineContract,
            previewLookState = previewLookState,
            logCaptureActive = logCaptureActive,
            rawVideoActive = rawVideoActive
        )
        val normalizedHdrMode = resolveTransportAwareHdrMode(
            requestedHdrMode = requestedHdrMode,
            cameraPreviewHdrTransportMode = cameraPreviewHdrTransportMode
        )
        val rawVideoDelivery = pipelineContract.truthfulColorDelivery && pipelineContract.rawSensorOriginal
        val previewMonitoringEnabled = resolvePreviewMonitoringEnabled(
            previewLookState = previewLookState,
            rawVideoActive = rawVideoActive,
            logCaptureActive = logCaptureActive,
            normalizedHdrMode = normalizedHdrMode
        )
        val lutEnabled = nativePreviewLutEnabled &&
            nativePreviewLutSize > 1 &&
            !pipelineContract.truthfulColorDelivery &&
            !rawVideoActive &&
            !logCaptureActive &&
            normalizedHdrMode == "OFF"
        val renderQuality = resolveQuality(
            highLoadPreviewActive = highLoadPreviewActive,
            previewFpsHint = previewFpsHint
        )
        val toneDeliveryMode = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = lutEnabled,
            truthfulColorDelivery = pipelineContract.truthfulColorDelivery,
            rawVideoDelivery = rawVideoDelivery,
            rawVideoActive = rawVideoActive,
            logCaptureActive = logCaptureActive,
            normalizedHdrMode = normalizedHdrMode,
            logCurve = logCurve,
            cinemaDebayerActive = cinemaDebayerActive && rawVideoActive,
            previewMonitoringEnabled = previewMonitoringEnabled
        )
        val renderPath = resolveRenderPath(
            rawVideoActive = rawVideoActive,
            logCaptureActive = logCaptureActive,
            normalizedHdrMode = normalizedHdrMode,
            lutEnabled = lutEnabled,
            cinemaDebayerActive = cinemaDebayerActive
        )
        val swapchainMode = resolveSwapchainDynamicRangeMode(
            pipelineContract = pipelineContract,
            rawVideoActive = rawVideoActive,
            logCaptureActive = logCaptureActive,
            normalizedHdrMode = normalizedHdrMode,
            cameraPreviewHdrTransportMode = cameraPreviewHdrTransportMode
        )
        val signature = buildSignature(
            pipelineContract = pipelineContract,
            previewLookState = previewLookState,
            renderPath = renderPath,
            swapchainMode = swapchainMode,
            toneDeliveryMode = toneDeliveryMode,
            lutEnabled = lutEnabled,
            logCurve = logCurve,
            cinemaDebayerActive = cinemaDebayerActive && rawVideoActive,
            renderQuality = renderQuality
        )

        return VulkanPreviewRenderContract(
            renderPath = renderPath,
            swapchainDynamicRangeMode = swapchainMode,
            toneDeliveryMode = toneDeliveryMode,
            truthfulColorDelivery = pipelineContract.truthfulColorDelivery,
            previewMonitoringEnabled = previewMonitoringEnabled,
            rawVideoDelivery = rawVideoDelivery,
            rawVideoActive = rawVideoActive,
            logCaptureActive = logCaptureActive,
            normalizedHdrMode = normalizedHdrMode,
            nativePreviewLutEnabled = lutEnabled,
            renderQuality = renderQuality,
            signature = signature
        )
    }

    private fun resolveHdrMode(
        pipelineContract: VideoModePipelineContract,
        previewLookState: VideoFormatPreviewLookState,
        logCaptureActive: Boolean,
        rawVideoActive: Boolean
    ): String {
        if (rawVideoActive || logCaptureActive) return "OFF"
        return when {
            previewLookState.hdrEnabled && previewLookState.hdrMode != "OFF" ->
                previewLookState.hdrMode
            pipelineContract.hdrToneMapActive -> pipelineContract.effectiveHdrMode
            else -> "OFF"
        }
    }

    private fun resolveTransportAwareHdrMode(
        requestedHdrMode: String,
        cameraPreviewHdrTransportMode: HdrDynamicRangeMode?
    ): String {
        val requested = requestedHdrMode.trim().uppercase()
        if (requested == "OFF") return "OFF"
        return when (cameraPreviewHdrTransportMode) {
            null -> requestedHdrMode
            HdrDynamicRangeMode.SDR -> if (isProjectOwnedShaderHdrProfile(requested)) {
                requestedHdrMode
            } else {
                "OFF"
            }
            HdrDynamicRangeMode.HLG,
            HdrDynamicRangeMode.HDR10,
            HdrDynamicRangeMode.HDR10_PLUS -> "NDR10+"
            HdrDynamicRangeMode.DOLBY_VISION -> "NIVUKXVISION"
        }
    }

    private fun isProjectOwnedShaderHdrProfile(requested: String): Boolean =
        when (requested.replace("_", "").replace(" ", "")) {
            "NDR10+",
            "NDR10PLUS",
            "NIVUKX",
            "NIVUKXVISION",
            "APEXSHARP",
            "APEXZERO",
            "APEXCINEMA" -> true
            else -> false
        }

    private fun resolvePreviewMonitoringEnabled(
        previewLookState: VideoFormatPreviewLookState,
        rawVideoActive: Boolean,
        logCaptureActive: Boolean,
        normalizedHdrMode: String
    ): Boolean =
        when {
            rawVideoActive -> true
            logCaptureActive -> previewLookState.displayReferredMonitoring
            previewLookState.hdrEnabled && normalizedHdrMode != "OFF" ->
                previewLookState.displayReferredMonitoring
            else -> false
        }

    private fun resolveRenderPath(
        rawVideoActive: Boolean,
        logCaptureActive: Boolean,
        normalizedHdrMode: String,
        lutEnabled: Boolean,
        cinemaDebayerActive: Boolean
    ): VulkanPreviewRenderPath =
        when {
            rawVideoActive && cinemaDebayerActive -> VulkanPreviewRenderPath.CINEMA_DEBAYER_MONITOR
            rawVideoActive -> VulkanPreviewRenderPath.RAW_MONITOR
            logCaptureActive -> VulkanPreviewRenderPath.LOG_ORIGINAL
            normalizedHdrMode == "HLG" -> VulkanPreviewRenderPath.HDR_HLG_NATIVE
            normalizedHdrMode != "OFF" -> VulkanPreviewRenderPath.HDR_PQ_NATIVE
            lutEnabled -> VulkanPreviewRenderPath.SDR_LUT
            else -> VulkanPreviewRenderPath.SDR_DIRECT
        }

    private fun resolveSwapchainDynamicRangeMode(
        pipelineContract: VideoModePipelineContract,
        rawVideoActive: Boolean,
        logCaptureActive: Boolean,
        normalizedHdrMode: String,
        cameraPreviewHdrTransportMode: HdrDynamicRangeMode?
    ): HdrDynamicRangeMode =
        when {
            rawVideoActive -> HdrDynamicRangeMode.SDR
            logCaptureActive -> HdrDynamicRangeMode.SDR
            normalizedHdrMode == "OFF" -> HdrDynamicRangeMode.SDR
            cameraPreviewHdrTransportMode != null -> cameraPreviewHdrTransportMode
            else -> pipelineContract.cameraDynamicRangeMode
        }

    private fun resolveQuality(
        highLoadPreviewActive: Boolean,
        previewFpsHint: Float
    ): VulkanPreviewRenderQuality =
        when {
            highLoadPreviewActive || previewFpsHint < 24f -> VulkanPreviewRenderQuality.FAST
            HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly ->
                VulkanPreviewRenderQuality.MAXIMUM
            previewFpsHint >= 30f -> VulkanPreviewRenderQuality.BALANCED
            else -> VulkanPreviewRenderQuality.FAST
        }

    private fun buildSignature(
        pipelineContract: VideoModePipelineContract,
        previewLookState: VideoFormatPreviewLookState,
        renderPath: VulkanPreviewRenderPath,
        swapchainMode: HdrDynamicRangeMode,
        toneDeliveryMode: VulkanToneDeliveryMode,
        lutEnabled: Boolean,
        logCurve: NeoLogCurve,
        cinemaDebayerActive: Boolean,
        renderQuality: VulkanPreviewRenderQuality
    ): String =
        listOf(
            pipelineContract.sessionSignature,
            previewLookState.previewKind.name,
            previewLookState.toneMap.name,
            previewLookState.displayReferredMonitoring.toString(),
            renderPath.name,
            swapchainMode.name,
            toneDeliveryMode.name,
            lutEnabled.toString(),
            logCurve.name,
            cinemaDebayerActive.toString(),
            renderQuality.name
        ).joinToString(separator = "|")
}
