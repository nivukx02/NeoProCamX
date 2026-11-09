package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VulkanPreviewRecordingParityPolicyTest {

    @Test
    fun strictVideoModeUsesPreviewGradeForEncoderToPreventMismatch() {
        val previewGrade = FloatArray(16) { index -> index / 16f }
        val encoderGrade = FloatArray(16) { index -> 1f - index / 16f }

        val resolved = VulkanPreviewRecordingParityPolicy.resolveEncoderGrade(
            previewGrade = previewGrade,
            encoderGrade = encoderGrade,
            videoModeActive = true,
            strictHighEnd = true
        )

        assertArrayEquals(previewGrade, resolved, 0.0001f)
        assertNotSame(previewGrade, resolved)
    }

    @Test
    fun videoModeAlwaysUsesPreviewGradeForEncoderToKeepProcessingParity() {
        val previewGrade = FloatArray(16) { 0.25f }
        val encoderGrade = FloatArray(16) { 0.75f }

        val resolved = VulkanPreviewRecordingParityPolicy.resolveEncoderGrade(
            previewGrade = previewGrade,
            encoderGrade = encoderGrade,
            videoModeActive = true,
            strictHighEnd = false
        )

        assertArrayEquals(previewGrade, resolved, 0.0001f)
        assertNotSame(previewGrade, resolved)
    }

    @Test
    fun monitorOnlyToneDeliveryKeepsDedicatedEncoderGrade() {
        val previewGrade = FloatArray(16) { 0.25f }
        val encoderGrade = FloatArray(16) { 0.75f }

        val resolved = VulkanPreviewRecordingParityPolicy.resolveEncoderGrade(
            previewGrade = previewGrade,
            encoderGrade = encoderGrade,
            videoModeActive = true,
            strictHighEnd = true,
            monitorOnlyToneDeliveryActive = true
        )

        assertArrayEquals(encoderGrade, resolved, 0.0001f)
    }

    @Test
    fun videoSdrModeUsesPreviewColorScienceForEncoderToPreventTiltColorMismatch() {
        val previewColorScience = FloatArray(VulkanColorScienceUniforms.FLOAT_COUNT) { index ->
            index / VulkanColorScienceUniforms.FLOAT_COUNT.toFloat()
        }
        val encoderColorScience = FloatArray(VulkanColorScienceUniforms.FLOAT_COUNT) { index ->
            1f - index / VulkanColorScienceUniforms.FLOAT_COUNT.toFloat()
        }

        val resolved = VulkanPreviewRecordingParityPolicy.resolveEncoderColorScience(
            previewColorScience = previewColorScience,
            encoderColorScience = encoderColorScience,
            videoModeActive = true,
            logDeliveryActive = false,
            strictHighEnd = true
        )

        assertArrayEquals(previewColorScience, resolved, 0.0001f)
        assertNotSame(previewColorScience, resolved)
    }

    @Test
    fun logVideoModeKeepsDedicatedEncoderColorScienceForLogDelivery() {
        val previewColorScience = FloatArray(VulkanColorScienceUniforms.FLOAT_COUNT) { 0.10f }
        val encoderColorScience = FloatArray(VulkanColorScienceUniforms.FLOAT_COUNT) { 0.90f }

        val resolved = VulkanPreviewRecordingParityPolicy.resolveEncoderColorScience(
            previewColorScience = previewColorScience,
            encoderColorScience = encoderColorScience,
            videoModeActive = true,
            logDeliveryActive = true,
            strictHighEnd = true
        )

        assertArrayEquals(encoderColorScience, resolved, 0.0001f)
    }

    @Test
    fun nonVideoModeKeepsDedicatedEncoderColorScience() {
        val previewColorScience = FloatArray(VulkanColorScienceUniforms.FLOAT_COUNT) { 0.10f }
        val encoderColorScience = FloatArray(VulkanColorScienceUniforms.FLOAT_COUNT) { 0.90f }

        val resolved = VulkanPreviewRecordingParityPolicy.resolveEncoderColorScience(
            previewColorScience = previewColorScience,
            encoderColorScience = encoderColorScience,
            videoModeActive = false,
            logDeliveryActive = false,
            strictHighEnd = true
        )

        assertArrayEquals(encoderColorScience, resolved, 0.0001f)
    }

    @Test
    fun nonVideoModeKeepsDedicatedEncoderGrade() {
        val previewGrade = FloatArray(16) { 0.25f }
        val encoderGrade = FloatArray(16) { 0.75f }

        val resolved = VulkanPreviewRecordingParityPolicy.resolveEncoderGrade(
            previewGrade = previewGrade,
            encoderGrade = encoderGrade,
            videoModeActive = false,
            strictHighEnd = true
        )

        assertArrayEquals(encoderGrade, resolved, 0.0001f)
    }

    @Test
    fun renderGraphLockHashesShaderLutStagesAndKeepsFullViewportWhenBudgetIsExceeded() {
        val preview = VulkanParityEndpoint(
            shaderSpirv = byteArrayOf(1, 3, 3, 7),
            lutCoefficients = floatArrayOf(0.02f, 0.25f, 0.91f),
            stages = listOf("AHARDWAREBUFFER_INGEST", "RGBA16F_CORE", "NIVUKX_3D_LUT", "DUAL_SWAPCHAIN_BLIT"),
            colorSpace = "Rec.2020-PQ",
            colorScience = "NIVUKX_VISION",
            colorUniforms = VulkanColorScienceUniformPolicy.resolve(
                enabled = true,
                hdrMappingEnabled = true,
                logDeliveryEnabled = false,
                nivukxVision = true,
                hdrKnee = 0.55f,
                hdrShoulder = 4.12f,
                volumeCompression = 0.64f,
                colorSpaceName = "Rec.2020-NIVUKX",
                colorScienceName = "NIVUKX Vision"
            ).pack(),
            denoiseProfile = "HDR_CLEAN",
            sharpnessProfile = "HDR_HIGHLIGHT_SAFE"
        )
        val recording = preview.copy(
            shaderSpirv = byteArrayOf(1, 3, 3, 8),
            stages = listOf("CAMERA2_DIRECT_BYPASS"),
            colorSpace = "Rec.709",
            colorScience = "BYPASS"
        )

        val lock = VulkanPreviewRecordingParityPolicy.resolveRenderGraphLock(
            preview = preview,
            recording = recording,
            pixelRealityBypassDetected = true,
            maxFrameProcessingMs = 7.1,
            runtimeBudgetMs = 6.0
        )

        assertFalse(lock.parityPassed)
        assertEquals(64, lock.previewShaderSha256.length)
        assertEquals(64, lock.previewLutSha256.length)
        assertEquals(1.0f, lock.viewportResolutionScale, 0.0001f)
        assertTrue(lock.timelineSemaphoreRequired)
        assertTrue(lock.failureCodes.contains("PREVIEW_RECORDING_SHADER_HASH_MISMATCH"))
        assertTrue(lock.failureCodes.contains("PREVIEW_RECORDING_STAGE_MISMATCH"))
        assertTrue(lock.failureCodes.contains("PIXEL_REALITY_BYPASS_DETECTED"))
        assertTrue(lock.failureCodes.contains("PERFORMANCE_BUDGET_EXCEEDED"))
    }

    @Test
    fun renderGraphLockFailsWhenColorUniformPacketDiffersDespiteMatchingLabels() {
        val preview = VulkanParityEndpoint(
            shaderSpirv = byteArrayOf(4, 4, 4, 4),
            lutCoefficients = floatArrayOf(0.0f, 0.5f, 1.0f),
            stages = listOf("AHARDWAREBUFFER_INGEST", "RGBA16F_CORE", "DUAL_SWAPCHAIN_BLIT"),
            colorSpace = "Neo ACES AP1",
            colorScience = "Neo ACES High End",
            colorUniforms = VulkanColorScienceUniformPolicy.resolve(
                enabled = true,
                hdrMappingEnabled = false,
                logDeliveryEnabled = false,
                nivukxVision = false,
                hdrKnee = 0.59f,
                hdrShoulder = 3.72f,
                volumeCompression = 0.54f,
                colorSpaceName = "Neo ACES AP1",
                colorScienceName = "Neo ACES High End"
            ).pack(),
            denoiseProfile = "ENCODED_VIDEO_STRICT",
            sharpnessProfile = "NATURAL_DETAIL"
        )
        val recording = preview.copy(
            colorUniforms = VulkanColorScienceUniformPolicy.resolve(
                enabled = true,
                hdrMappingEnabled = false,
                logDeliveryEnabled = false,
                nivukxVision = false,
                hdrKnee = 0.59f,
                hdrShoulder = 3.72f,
                volumeCompression = 0.54f,
                colorSpaceName = "Neo ACES AP0",
                colorScienceName = "Neo ACES High End"
            ).pack()
        )

        val lock = VulkanPreviewRecordingParityPolicy.resolveRenderGraphLock(
            preview = preview,
            recording = recording
        )

        assertFalse(lock.parityPassed)
        assertEquals(64, lock.previewColorUniformsSha256.length)
        assertEquals(64, lock.recordingColorUniformsSha256.length)
        assertTrue(lock.failureCodes.contains("PREVIEW_RECORDING_COLOR_UNIFORM_MISMATCH"))
        assertFalse(lock.failureCodes.contains("PREVIEW_RECORDING_COLOR_SPACE_MISMATCH"))
        assertFalse(lock.failureCodes.contains("PREVIEW_RECORDING_COLOR_SCIENCE_MISMATCH"))
    }

    @Test
    fun renderGraphLockFailsWhenGradeAndOutputIdentityDiffer() {
        val preview = VulkanParityEndpoint(
            shaderSpirv = byteArrayOf(8, 6, 7, 5),
            lutCoefficients = floatArrayOf(0f, 0.5f, 1f),
            stages = listOf("RGBA16F_CORE", "SDR_OUTPUT"),
            colorSpace = "Rec.709",
            colorScience = "Rec.709 Standard",
            denoiseProfile = "STRICT_SDR",
            sharpnessProfile = "NATURAL_DETAIL",
            gradePacket = FloatArray(16) { 1f },
            pipelineIdentity = "SDR_PIPELINE_V1",
            outputFormat = "VK_FORMAT_B8G8R8A8_UNORM",
            outputTransfer = "SDR_VIDEO",
            outputGamut = "BT.709",
            outputBitDepth = 8,
            outputQuantizationLevels = 255
        )
        val recording = preview.copy(
            gradePacket = FloatArray(16) { index -> if (index == 3) 0.75f else 1f },
            pipelineIdentity = "SDR_PIPELINE_V2",
            outputFormat = "VK_FORMAT_A2B10G10R10_UNORM_PACK32",
            outputTransfer = "BT.1886",
            outputGamut = "BT.2020",
            outputBitDepth = 10,
            outputQuantizationLevels = 1023
        )

        val lock = VulkanPreviewRecordingParityPolicy.resolveRenderGraphLock(
            preview = preview,
            recording = recording
        )

        assertFalse(lock.parityPassed)
        assertEquals(64, lock.previewGradeSha256.length)
        assertEquals(64, lock.recordingGradeSha256.length)
        assertNotEquals(lock.previewGradeSha256, lock.recordingGradeSha256)
        assertNotEquals(lock.previewParityIdentitySha256, lock.recordingParityIdentitySha256)
        assertTrue(lock.failureCodes.contains("PREVIEW_RECORDING_GRADE_PACKET_MISMATCH"))
        assertTrue(lock.failureCodes.contains("PREVIEW_RECORDING_PIPELINE_IDENTITY_MISMATCH"))
        assertTrue(lock.failureCodes.contains("PREVIEW_RECORDING_OUTPUT_FORMAT_MISMATCH"))
        assertTrue(lock.failureCodes.contains("PREVIEW_RECORDING_OUTPUT_TRANSFER_MISMATCH"))
        assertTrue(lock.failureCodes.contains("PREVIEW_RECORDING_OUTPUT_GAMUT_MISMATCH"))
        assertTrue(lock.failureCodes.contains("PREVIEW_RECORDING_OUTPUT_BIT_DEPTH_MISMATCH"))
        assertTrue(lock.failureCodes.contains("PREVIEW_RECORDING_OUTPUT_QUANTIZATION_MISMATCH"))
    }

    @Test
    fun renderGraphLockAcceptsIndependentlySuppliedMatchingSdrOutputIdentities() {
        fun endpoint() = VulkanParityEndpoint(
            shaderSpirv = byteArrayOf(1, 0, 2, 3),
            lutCoefficients = floatArrayOf(0f, 1f),
            stages = listOf("RGBA16F_CORE", "SDR_MAIN10_OUTPUT"),
            colorSpace = "Rec.2020",
            colorScience = "Natural",
            denoiseProfile = "STRICT_SDR",
            sharpnessProfile = "NATURAL_DETAIL",
            gradePacket = FloatArray(16) { index -> index / 16f },
            pipelineIdentity = "PRO_SDR_REC2020_MAIN10",
            outputFormat = "VK_FORMAT_A2B10G10R10_UNORM_PACK32",
            outputTransfer = "SDR_VIDEO",
            outputGamut = "BT.2020",
            outputBitDepth = 10,
            outputQuantizationLevels = 1023
        )

        val lock = VulkanPreviewRecordingParityPolicy.resolveRenderGraphLock(
            preview = endpoint(),
            recording = endpoint()
        )

        assertTrue(lock.parityPassed)
        assertEquals(lock.previewGradeSha256, lock.recordingGradeSha256)
        assertEquals(lock.previewParityIdentitySha256, lock.recordingParityIdentitySha256)
        assertTrue(lock.failureCodes.isEmpty())
    }

    @Test
    fun logMonitorToDeliveryDifferenceAllowsOnlyTheExplicitLogFlagSlot() {
        val preview = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = false,
            logDeliveryEnabled = false,
            nivukxVision = false,
            hdrKnee = 0.59f,
            hdrShoulder = 3.72f,
            volumeCompression = 0.54f,
            colorSpaceName = "Rec.2020",
            colorScienceName = "N-Log Cinema"
        ).pack()
        val recording = preview.copyOf().also { it[37] = 1f }

        assertTrue(
            VulkanPreviewRecordingParityPolicy.isAllowedMonitorDeliveryColorUniformDelta(
                previewColorScience = preview,
                recordingColorScience = recording
            )
        )
        recording[44] = GpuColorSpace.NEO_ACES_AP0.id.toFloat()
        assertFalse(
            VulkanPreviewRecordingParityPolicy.isAllowedMonitorDeliveryColorUniformDelta(
                previewColorScience = preview,
                recordingColorScience = recording
            )
        )
    }

    @Test
    fun renderGraphLockFailsWhenRecordingUsesWeakerDenoiseOrSharpnessThanPreview() {
        val preview = VulkanParityEndpoint(
            shaderSpirv = byteArrayOf(9, 7, 5, 3),
            lutCoefficients = floatArrayOf(1.0f, 0.5f, 0.25f),
            stages = listOf("AHARDWAREBUFFER_INGEST", "RGBA16F_CORE", "NIVUKX_3D_LUT", "ENCODER_SURFACE"),
            colorSpace = "Rec.709",
            colorScience = "NATURAL",
            denoiseProfile = "BEYOND_IMAX_VISIBLE_CLEAN",
            sharpnessProfile = "BEYOND_IMAX_EDGE_AWARE_DETAIL"
        )
        val recording = preview.copy(
            denoiseProfile = "CAMERA2_LIGHT_DENOISE",
            sharpnessProfile = "CAMERA2_SOFT_DETAIL"
        )

        val lock = VulkanPreviewRecordingParityPolicy.resolveRenderGraphLock(
            preview = preview,
            recording = recording
        )

        assertFalse(lock.parityPassed)
        assertTrue(lock.failureCodes.contains("PREVIEW_RECORDING_DENOISE_PROFILE_MISMATCH"))
        assertTrue(lock.failureCodes.contains("PREVIEW_RECORDING_SHARPNESS_PROFILE_MISMATCH"))
        assertFalse(lock.failureCodes.contains("PREVIEW_RECORDING_SHADER_HASH_MISMATCH"))
        assertFalse(lock.failureCodes.contains("PREVIEW_RECORDING_STAGE_MISMATCH"))
    }

    @Test
    fun nativeParityBridgeDeclaresTimelineSemaphorePresentationTimeAndRgba16fMultiplexer() {
        val source = repoFile("app/src/main/cpp/vulkan_parity_bridge.cpp").readText()
        val cmake = repoFile("app/src/main/cpp/CMakeLists.txt").readText()
        val bridge = repoFile("app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt").readText()
        val runtime = repoFile("app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt").readText()

        assertTrue(source.contains("VkPhysicalDeviceTimelineSemaphoreFeatures"))
        assertTrue(source.contains("VK_SEMAPHORE_TYPE_TIMELINE"))
        assertTrue(source.contains("eglPresentationTimeANDROID"))
        assertTrue(source.contains("AHardwareBuffer_fromHardwareBuffer"))
        assertTrue(source.contains("VK_FORMAT_R16G16B16A16_SFLOAT"))
        assertTrue(source.contains("vkQueueSubmit"))
        assertTrue(source.contains("viewfinder"))
        assertTrue(source.contains("recorder"))
        assertTrue(source.contains("frameTimestampNanos"))
        assertTrue(cmake.contains("vulkan_parity_bridge.cpp"))
        assertTrue(bridge.contains("nativeGetParityBridgeSummary"))
        assertTrue(runtime.contains("parityBridgeSummary()"))
    }

    @Test
    fun renderGraphLockFailsWhenReferenceRenderDiffersAndPassesWhenFixed() {
        fun endpoint(referenceRender: String) = VulkanParityEndpoint(
            shaderSpirv = byteArrayOf(2, 4, 6, 8),
            lutCoefficients = floatArrayOf(0f, 0.5f, 1f),
            stages = listOf("RGBA16F_CORE", "SDR_OUTPUT"),
            colorSpace = "Rec.709",
            colorScience = "Neo ACES High End",
            denoiseProfile = "STRICT_SDR",
            sharpnessProfile = "NATURAL_DETAIL",
            referenceRenderSignature = referenceRender
        )

        // OmniClarity §5: preview and recording must share the one FIXED Helios reference render.
        val fixed = OmniClarityCinemaRenderingChain.REFERENCE_RENDER.signature
        val matched = VulkanPreviewRecordingParityPolicy.resolveRenderGraphLock(
            preview = endpoint(fixed),
            recording = endpoint(fixed)
        )
        assertTrue(matched.parityPassed)
        assertEquals(fixed, matched.previewReferenceRenderSignature)
        assertEquals(fixed, matched.recordingReferenceRenderSignature)
        assertFalse(matched.failureCodes.contains("PREVIEW_RECORDING_REFERENCE_RENDER_MISMATCH"))

        val drifted = VulkanPreviewRecordingParityPolicy.resolveRenderGraphLock(
            preview = endpoint(fixed),
            recording = endpoint("ALT_REFERENCE_RENDER_V0")
        )
        assertFalse(drifted.parityPassed)
        assertTrue(drifted.failureCodes.contains("PREVIEW_RECORDING_REFERENCE_RENDER_MISMATCH"))
    }

    private fun repoFile(path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        return File("..", path).takeIf { it.exists() } ?: direct
    }
}
