package com.neoproc.neoproccamx.ui

internal enum class PixelProcessingStageName {
    TONE_MAP,
    COLOR_PROFILE,
    LUT,
    DENOISE,
    SHARPNESS,
    STABILIZATION,
    AI_ENHANCEMENT,
    MULTI_FRAME_HDR,
    RAW_DEBAYER
}

internal enum class PixelProcessingStageReality {
    REAL_GPU_SHADER,
    REAL_GPU_COMPUTE,
    REAL_CPU_ANALYSIS,
    REAL_CPU_PROCESSING,
    CAMERA2_ISP,
    PLANNED_ONLY,
    UNSUPPORTED
}

internal data class PixelProcessingStageEvidence(
    val stage: PixelProcessingStageName,
    val previewReality: PixelProcessingStageReality,
    val recordingReality: PixelProcessingStageReality,
    val implementation: String,
    val claimSupported: Boolean,
    val reason: String
)

internal data class PixelProcessingRealityRequest(
    val parityPlan: RecordingPipelineParityPlan,
    val nativePreviewActive: Boolean,
    val vulkanRuntimeSupported: Boolean,
    val aiFrameProcessingActive: Boolean,
    val multiFrameVideoProcessingActive: Boolean,
    val recordingGpuFrameImportValidated: Boolean = false,
    val rawGpuDebayerActive: Boolean = false
)

internal data class PixelProcessingRealityReport(
    val feedPath: RecordingFeedPath,
    val previewUsesCustomGpu: Boolean,
    val recordingUsesCustomGpu: Boolean,
    val customGpuActive: Boolean,
    val camera2DirectBypassesCustomProcessing: Boolean,
    val stages: List<PixelProcessingStageEvidence>,
    val realStageNames: List<String>,
    val plannedOnlyStageNames: List<String>,
    val unsupportedStageNames: List<String>,
    val reportSignature: String
)

internal object PixelProcessingRealityPolicy {

    fun resolve(request: PixelProcessingRealityRequest): PixelProcessingRealityReport {
        val previewGpu = request.nativePreviewActive && request.vulkanRuntimeSupported
        val byteBufferReadout =
            request.parityPlan.feedPath == RecordingFeedPath.BYTEBUFFER_P010_ENCODER
        val recordingGpu = request.vulkanRuntimeSupported &&
            request.parityPlan.feedPath in setOf(
                RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
                RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
            ) &&
            request.recordingGpuFrameImportValidated
        val customGpuActive = recordingGpu
        val camera2Bypass = !recordingGpu &&
            !byteBufferReadout &&
            request.parityPlan.feedPath !in setOf(
                RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
                RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
            )
        val stages = listOf(
            gpuOrIspStage(
                name = PixelProcessingStageName.TONE_MAP,
                previewGpu = previewGpu,
                recordingGpu = recordingGpu,
                fallbackReality = if (byteBufferReadout) {
                    PixelProcessingStageReality.REAL_CPU_PROCESSING
                } else {
                    PixelProcessingStageReality.CAMERA2_ISP
                },
                implementation = "Vulkan ycbcr_preview.frag tone delivery or Camera2 ISP tone map",
                reason = "tone map is real only through Vulkan shader path or vendor Camera2 ISP path"
            ),
            gpuOrIspStage(
                name = PixelProcessingStageName.COLOR_PROFILE,
                previewGpu = previewGpu,
                recordingGpu = recordingGpu,
                fallbackReality = if (byteBufferReadout) {
                    PixelProcessingStageReality.REAL_CPU_PROCESSING
                } else {
                    PixelProcessingStageReality.CAMERA2_ISP
                },
                implementation = "Vulkan color grade uniforms/color gain or Camera2 ISP color transform",
                reason = "color profile is applied by shader uniforms when hardware import is active"
            ),
            gpuStage(
                name = PixelProcessingStageName.LUT,
                previewGpu = previewGpu,
                recordingGpu = recordingGpu,
                implementation = "Vulkan ycbcr_preview.frag LUT sampler",
                unsupportedReason = "custom LUT is unavailable when frames bypass the Vulkan render pass"
            ),
            gpuOrIspStage(
                name = PixelProcessingStageName.DENOISE,
                previewGpu = previewGpu,
                recordingGpu = recordingGpu,
                fallbackReality = if (byteBufferReadout) {
                    PixelProcessingStageReality.REAL_CPU_PROCESSING
                } else {
                    PixelProcessingStageReality.CAMERA2_ISP
                },
                implementation = "Vulkan chroma-noise guard/single-frame cleanup or Camera2 ISP noise reduction",
                reason = "this is single-frame shader/ISP denoise, not AI or multi-frame temporal denoise"
            ),
            gpuStage(
                name = PixelProcessingStageName.SHARPNESS,
                previewGpu = previewGpu,
                recordingGpu = recordingGpu,
                implementation = "Vulkan ycbcr_preview.frag natural detail and edge-preserve sharpening",
                unsupportedReason = "custom sharpening is unavailable when encoder receives Camera2 direct frames"
            ),
            PixelProcessingStageEvidence(
                stage = PixelProcessingStageName.STABILIZATION,
                previewReality = PixelProcessingStageReality.CAMERA2_ISP,
                recordingReality = PixelProcessingStageReality.CAMERA2_ISP,
                implementation = "Camera2 OIS/EIS capture request controls",
                claimSupported = true,
                reason = "stabilization is a camera request feature, not a custom GPU frame processor"
            ),
            conditionalCpuStage(
                name = PixelProcessingStageName.AI_ENHANCEMENT,
                active = request.aiFrameProcessingActive,
                activeReality = PixelProcessingStageReality.REAL_CPU_ANALYSIS,
                implementation = "AICameraPipeline.analyzeMediaImage on YUV frames",
                inactiveReality = PixelProcessingStageReality.UNSUPPORTED,
                inactiveReason = "AI enhancement is not claimed until an AI frame result has actually run"
            ),
            conditionalCpuStage(
                name = PixelProcessingStageName.MULTI_FRAME_HDR,
                active = request.multiFrameVideoProcessingActive,
                activeReality = PixelProcessingStageReality.REAL_CPU_PROCESSING,
                implementation = "multi-frame video processing pass",
                inactiveReality = PixelProcessingStageReality.PLANNED_ONLY,
                inactiveReason = "no active video multi-frame merge stage is attached to the recording path"
            ),
            PixelProcessingStageEvidence(
                stage = PixelProcessingStageName.RAW_DEBAYER,
                previewReality = if (request.rawGpuDebayerActive) {
                    PixelProcessingStageReality.REAL_GPU_COMPUTE
                } else {
                    PixelProcessingStageReality.UNSUPPORTED
                },
                recordingReality = if (
                    request.rawGpuDebayerActive &&
                    request.parityPlan.feedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER
                ) {
                    PixelProcessingStageReality.REAL_GPU_COMPUTE
                } else if (request.parityPlan.deliveryKind == VideoDeliveryKind.RAW) {
                    PixelProcessingStageReality.UNSUPPORTED
                } else {
                    PixelProcessingStageReality.UNSUPPORTED
                },
                implementation = "Vulkan cinema_debayer.comp when RAW GPU debayer is active",
                claimSupported = request.rawGpuDebayerActive,
                reason = if (request.rawGpuDebayerActive) {
                    "RAW debayer compute path is active"
                } else {
                    "RAW recording remains RAW_SENSOR direct data; debayer is not applied to the encoded stream"
                }
            )
        )
        val realStages = stages.filter { it.recordingReality.isReal() }.map { it.stage.name }
        val plannedOnlyStages = stages
            .filter { it.recordingReality == PixelProcessingStageReality.PLANNED_ONLY }
            .map { it.stage.name }
        val unsupportedStages = stages
            .filter { it.recordingReality == PixelProcessingStageReality.UNSUPPORTED }
            .map { it.stage.name }
        val signature = listOf(
            "PIXEL_REALITY",
            "feed=${request.parityPlan.feedPath.name}",
            "previewGpu=$previewGpu",
            "recordingGpu=$recordingGpu",
            "customGpuActive=$customGpuActive",
            "customGpuBypass=$camera2Bypass",
            "real=${realStages.joinToString(separator = ",")}",
            "planned=${plannedOnlyStages.joinToString(separator = ",")}",
            "unsupported=${unsupportedStages.joinToString(separator = ",")}"
        ).joinToString(separator = "|")
        return PixelProcessingRealityReport(
            feedPath = request.parityPlan.feedPath,
            previewUsesCustomGpu = previewGpu,
            recordingUsesCustomGpu = recordingGpu,
            customGpuActive = customGpuActive,
            camera2DirectBypassesCustomProcessing = camera2Bypass,
            stages = stages,
            realStageNames = realStages,
            plannedOnlyStageNames = plannedOnlyStages,
            unsupportedStageNames = unsupportedStages,
            reportSignature = signature
        )
    }

    private fun gpuOrIspStage(
        name: PixelProcessingStageName,
        previewGpu: Boolean,
        recordingGpu: Boolean,
        fallbackReality: PixelProcessingStageReality,
        implementation: String,
        reason: String
    ): PixelProcessingStageEvidence =
        PixelProcessingStageEvidence(
            stage = name,
            previewReality = if (previewGpu) PixelProcessingStageReality.REAL_GPU_SHADER else fallbackReality,
            recordingReality = if (recordingGpu) PixelProcessingStageReality.REAL_GPU_SHADER else fallbackReality,
            implementation = implementation,
            claimSupported = true,
            reason = reason
        )

    private fun gpuStage(
        name: PixelProcessingStageName,
        previewGpu: Boolean,
        recordingGpu: Boolean,
        implementation: String,
        unsupportedReason: String
    ): PixelProcessingStageEvidence =
        PixelProcessingStageEvidence(
            stage = name,
            previewReality = if (previewGpu) {
                PixelProcessingStageReality.REAL_GPU_SHADER
            } else {
                PixelProcessingStageReality.UNSUPPORTED
            },
            recordingReality = if (recordingGpu) {
                PixelProcessingStageReality.REAL_GPU_SHADER
            } else {
                PixelProcessingStageReality.UNSUPPORTED
            },
            implementation = implementation,
            claimSupported = previewGpu || recordingGpu,
            reason = if (previewGpu || recordingGpu) {
                "stage is backed by the Vulkan render pass"
            } else {
                unsupportedReason
            }
        )

    private fun conditionalCpuStage(
        name: PixelProcessingStageName,
        active: Boolean,
        activeReality: PixelProcessingStageReality,
        implementation: String,
        inactiveReality: PixelProcessingStageReality,
        inactiveReason: String
    ): PixelProcessingStageEvidence =
        PixelProcessingStageEvidence(
            stage = name,
            previewReality = if (active) activeReality else inactiveReality,
            recordingReality = if (active) activeReality else inactiveReality,
            implementation = implementation,
            claimSupported = active,
            reason = if (active) {
                "stage has observed active frame processing"
            } else {
                inactiveReason
            }
        )

    private fun PixelProcessingStageReality.isReal(): Boolean =
        this == PixelProcessingStageReality.REAL_GPU_SHADER ||
            this == PixelProcessingStageReality.REAL_GPU_COMPUTE ||
            this == PixelProcessingStageReality.REAL_CPU_ANALYSIS ||
            this == PixelProcessingStageReality.REAL_CPU_PROCESSING ||
            this == PixelProcessingStageReality.CAMERA2_ISP
}
