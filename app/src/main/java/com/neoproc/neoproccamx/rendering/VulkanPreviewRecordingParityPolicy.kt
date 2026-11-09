package com.neoproc.neoproccamx.rendering

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal data class VulkanParityEndpoint(
    val shaderSpirv: ByteArray,
    val lutCoefficients: FloatArray,
    val stages: List<String>,
    val colorSpace: String,
    val colorScience: String,
    val denoiseProfile: String,
    val sharpnessProfile: String,
    val colorUniforms: FloatArray = FloatArray(0),
    val gradePacket: FloatArray = FloatArray(0),
    val pipelineIdentity: String = "",
    val outputFormat: String = "",
    val outputTransfer: String = "",
    val outputGamut: String = "",
    val outputBitDepth: Int = 0,
    val outputQuantizationLevels: Int = 0,
    val referenceRenderSignature: String = ""
)

internal data class VulkanRenderGraphLock(
    val previewShaderSha256: String,
    val recordingShaderSha256: String,
    val previewLutSha256: String,
    val recordingLutSha256: String,
    val previewStagesSignature: String,
    val recordingStagesSignature: String,
    val previewColorSpace: String,
    val recordingColorSpace: String,
    val previewColorScience: String,
    val recordingColorScience: String,
    val previewColorUniformsSha256: String,
    val recordingColorUniformsSha256: String,
    val previewGradeSha256: String,
    val recordingGradeSha256: String,
    val previewParityIdentitySha256: String,
    val recordingParityIdentitySha256: String,
    val previewPipelineIdentity: String,
    val recordingPipelineIdentity: String,
    val previewOutputFormat: String,
    val recordingOutputFormat: String,
    val previewOutputTransfer: String,
    val recordingOutputTransfer: String,
    val previewOutputGamut: String,
    val recordingOutputGamut: String,
    val previewOutputBitDepth: Int,
    val recordingOutputBitDepth: Int,
    val previewOutputQuantizationLevels: Int,
    val recordingOutputQuantizationLevels: Int,
    val previewReferenceRenderSignature: String,
    val recordingReferenceRenderSignature: String,
    val previewDenoiseProfile: String,
    val recordingDenoiseProfile: String,
    val previewSharpnessProfile: String,
    val recordingSharpnessProfile: String,
    val monitorDeliveryColorUniformDifferenceAllowed: Boolean,
    val pixelRealityBypassDetected: Boolean,
    val timelineSemaphoreRequired: Boolean,
    val maxFrameProcessingMs: Double,
    val runtimeBudgetMs: Double,
    val viewportResolutionScale: Float,
    val parityPassed: Boolean,
    val failureCodes: List<String>
)

internal object VulkanPreviewRecordingParityPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun resolveEncoderGrade(
        previewGrade: FloatArray,
        encoderGrade: FloatArray,
        videoModeActive: Boolean,
        strictHighEnd: Boolean,
        monitorOnlyToneDeliveryActive: Boolean = false
    ): FloatArray =
        if (videoModeActive && !monitorOnlyToneDeliveryActive) {
            previewGrade.copyOf()
        } else {
            encoderGrade
        }

    @Suppress("UNUSED_PARAMETER")
    fun resolveEncoderColorScience(
        previewColorScience: FloatArray,
        encoderColorScience: FloatArray,
        videoModeActive: Boolean,
        logDeliveryActive: Boolean,
        strictHighEnd: Boolean
    ): FloatArray =
        if (videoModeActive && !logDeliveryActive) {
            previewColorScience.copyOf()
        } else {
            encoderColorScience
        }

    fun resolveRenderGraphLock(
        preview: VulkanParityEndpoint,
        recording: VulkanParityEndpoint,
        pixelRealityBypassDetected: Boolean = false,
        maxFrameProcessingMs: Double = 0.0,
        runtimeBudgetMs: Double = 14.0,
        monitorDeliveryColorUniformDifferenceAllowed: Boolean = false
    ): VulkanRenderGraphLock {
        val previewShaderHash = sha256(preview.shaderSpirv)
        val recordingShaderHash = sha256(recording.shaderSpirv)
        val previewLutHash = sha256(preview.lutCoefficients)
        val recordingLutHash = sha256(recording.lutCoefficients)
        val previewColorUniformHash = sha256OrBlank(preview.colorUniforms)
        val recordingColorUniformHash = sha256OrBlank(recording.colorUniforms)
        val previewGradeHash = sha256OrBlank(preview.gradePacket)
        val recordingGradeHash = sha256OrBlank(recording.gradePacket)
        val previewParityIdentityHash = parityIdentitySha256(preview, previewGradeHash)
        val recordingParityIdentityHash = parityIdentitySha256(recording, recordingGradeHash)
        val previewStagesSignature = preview.stages.joinToString(separator = ">")
        val recordingStagesSignature = recording.stages.joinToString(separator = ">")
        val budgetExceeded =
            maxFrameProcessingMs > 0.0 &&
                runtimeBudgetMs > 0.0 &&
                maxFrameProcessingMs > runtimeBudgetMs
        val failures = buildList {
            if (previewShaderHash != recordingShaderHash) add("PREVIEW_RECORDING_SHADER_HASH_MISMATCH")
            if (previewLutHash != recordingLutHash) add("PREVIEW_RECORDING_LUT_HASH_MISMATCH")
            if (previewStagesSignature != recordingStagesSignature) add("PREVIEW_RECORDING_STAGE_MISMATCH")
            if (preview.colorSpace != recording.colorSpace) add("PREVIEW_RECORDING_COLOR_SPACE_MISMATCH")
            if (preview.colorScience != recording.colorScience) add("PREVIEW_RECORDING_COLOR_SCIENCE_MISMATCH")
            if (previewGradeHash != recordingGradeHash) add("PREVIEW_RECORDING_GRADE_PACKET_MISMATCH")
            if (preview.pipelineIdentity != recording.pipelineIdentity) {
                add("PREVIEW_RECORDING_PIPELINE_IDENTITY_MISMATCH")
            }
            if (preview.outputFormat != recording.outputFormat) {
                add("PREVIEW_RECORDING_OUTPUT_FORMAT_MISMATCH")
            }
            if (preview.outputTransfer != recording.outputTransfer) {
                add("PREVIEW_RECORDING_OUTPUT_TRANSFER_MISMATCH")
            }
            if (preview.outputGamut != recording.outputGamut) {
                add("PREVIEW_RECORDING_OUTPUT_GAMUT_MISMATCH")
            }
            if (preview.outputBitDepth != recording.outputBitDepth) {
                add("PREVIEW_RECORDING_OUTPUT_BIT_DEPTH_MISMATCH")
            }
            if (preview.outputQuantizationLevels != recording.outputQuantizationLevels) {
                add("PREVIEW_RECORDING_OUTPUT_QUANTIZATION_MISMATCH")
            }
            if (preview.referenceRenderSignature != recording.referenceRenderSignature) {
                // OmniClarity §5: SDR/HDR/LOG must branch from one FIXED Helios reference render.
                add("PREVIEW_RECORDING_REFERENCE_RENDER_MISMATCH")
            }
            if (
                previewColorUniformHash != recordingColorUniformHash &&
                !monitorDeliveryColorUniformDifferenceAllowed
            ) {
                add("PREVIEW_RECORDING_COLOR_UNIFORM_MISMATCH")
            }
            if (preview.denoiseProfile != recording.denoiseProfile) add("PREVIEW_RECORDING_DENOISE_PROFILE_MISMATCH")
            if (preview.sharpnessProfile != recording.sharpnessProfile) add("PREVIEW_RECORDING_SHARPNESS_PROFILE_MISMATCH")
            if (pixelRealityBypassDetected) add("PIXEL_REALITY_BYPASS_DETECTED")
            if (budgetExceeded) add("PERFORMANCE_BUDGET_EXCEEDED")
        }
        return VulkanRenderGraphLock(
            previewShaderSha256 = previewShaderHash,
            recordingShaderSha256 = recordingShaderHash,
            previewLutSha256 = previewLutHash,
            recordingLutSha256 = recordingLutHash,
            previewStagesSignature = previewStagesSignature,
            recordingStagesSignature = recordingStagesSignature,
            previewColorSpace = preview.colorSpace,
            recordingColorSpace = recording.colorSpace,
            previewColorScience = preview.colorScience,
            recordingColorScience = recording.colorScience,
            previewColorUniformsSha256 = previewColorUniformHash,
            recordingColorUniformsSha256 = recordingColorUniformHash,
            previewGradeSha256 = previewGradeHash,
            recordingGradeSha256 = recordingGradeHash,
            previewParityIdentitySha256 = previewParityIdentityHash,
            recordingParityIdentitySha256 = recordingParityIdentityHash,
            previewPipelineIdentity = preview.pipelineIdentity,
            recordingPipelineIdentity = recording.pipelineIdentity,
            previewOutputFormat = preview.outputFormat,
            recordingOutputFormat = recording.outputFormat,
            previewOutputTransfer = preview.outputTransfer,
            recordingOutputTransfer = recording.outputTransfer,
            previewOutputGamut = preview.outputGamut,
            recordingOutputGamut = recording.outputGamut,
            previewOutputBitDepth = preview.outputBitDepth,
            recordingOutputBitDepth = recording.outputBitDepth,
            previewOutputQuantizationLevels = preview.outputQuantizationLevels,
            recordingOutputQuantizationLevels = recording.outputQuantizationLevels,
            previewReferenceRenderSignature = preview.referenceRenderSignature,
            recordingReferenceRenderSignature = recording.referenceRenderSignature,
            previewDenoiseProfile = preview.denoiseProfile,
            recordingDenoiseProfile = recording.denoiseProfile,
            previewSharpnessProfile = preview.sharpnessProfile,
            recordingSharpnessProfile = recording.sharpnessProfile,
            monitorDeliveryColorUniformDifferenceAllowed =
                monitorDeliveryColorUniformDifferenceAllowed,
            pixelRealityBypassDetected = pixelRealityBypassDetected,
            timelineSemaphoreRequired = true,
            maxFrameProcessingMs = maxFrameProcessingMs,
            runtimeBudgetMs = runtimeBudgetMs,
            viewportResolutionScale = 1.0f,
            parityPassed = failures.isEmpty(),
            failureCodes = failures
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }

    private fun sha256(values: FloatArray): String {
        val buffer = ByteBuffer
            .allocate(values.size * java.lang.Float.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(buffer::putFloat)
        return sha256(buffer.array())
    }

    private fun sha256OrBlank(values: FloatArray): String =
        if (values.isEmpty()) "" else sha256(values)

    private fun parityIdentitySha256(endpoint: VulkanParityEndpoint, gradeSha256: String): String =
        sha256(
            listOf(
                endpoint.pipelineIdentity,
                gradeSha256,
                endpoint.outputFormat,
                endpoint.outputTransfer,
                endpoint.outputGamut,
                endpoint.outputBitDepth.toString(),
                endpoint.outputQuantizationLevels.toString()
            ).joinToString(separator = "\u001f").toByteArray(Charsets.UTF_8)
        )

    fun isAllowedMonitorDeliveryColorUniformDelta(
        previewColorScience: FloatArray,
        recordingColorScience: FloatArray
    ): Boolean {
        if (
            previewColorScience.size != VulkanColorScienceUniforms.FLOAT_COUNT ||
            recordingColorScience.size != VulkanColorScienceUniforms.FLOAT_COUNT
        ) {
            return false
        }
        var differenceCount = 0
        for (index in previewColorScience.indices) {
            if (previewColorScience[index].toBits() == recordingColorScience[index].toBits()) {
                continue
            }
            if (index != LOG_DELIVERY_ENABLED_INDEX) return false
            differenceCount += 1
        }
        return differenceCount == 1 &&
            previewColorScience[LOG_DELIVERY_ENABLED_INDEX] <= 0.5f &&
            recordingColorScience[LOG_DELIVERY_ENABLED_INDEX] > 0.5f
    }

    private const val LOG_DELIVERY_ENABLED_INDEX = 37
}
