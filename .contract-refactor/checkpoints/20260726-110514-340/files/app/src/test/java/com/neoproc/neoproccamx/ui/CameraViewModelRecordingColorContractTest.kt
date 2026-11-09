package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class CameraViewModelRecordingColorContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    }
    private val gradeSource: String by lazy {
        source.substringAfter("private fun buildVulkanGrade(")
            .substringBefore("private fun shouldSkipSessionRefreshForModeSwitch") +
            File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanGradeUniformPolicy.kt").readText()
    }

    @Test
    fun `recording manager uses camera transport resolved color space instead of stale preferences`() {
        assertTrue(
            "syncEffectiveRecordingColorProfile must resolve the actual Camera2 HDR transport " +
                "before MediaCodec is prepared so HDR10 requests cannot tag HLG or SDR pixels as PQ.",
            source.contains("VideoHdrRecordingTransportPolicy.resolve(")
        )
        assertTrue(
            "Recording color-space metadata must come from the transport policy, not stale prefs " +
                "or the requested HDR mode.",
            source.contains("gpuRecording.videoColorSpace = recordingTransport.colorSpace")
        )
        assertTrue(
            "The old requested-mode handoff must stay removed.",
            !source.contains("gpuRecording.videoHdrMode = deliveryState.effectiveHdrMode")
        )
    }

    @Test
    fun `recording does not force vulkan preview into fast high load render path`() {
        assertTrue(
            "Recording start must not downgrade the live preview render contract by itself; " +
                "that caused preview tone/noise changes while recording.",
            source.contains("highLoadPreviewActive = isHighLoadPreviewMode()")
        )
        assertTrue(
            "The old recording-driven high-load expression must stay removed.",
            !source.contains("highLoadPreviewActive = isHighLoadPreviewMode() || recordingActive")
        )
    }

    @Test
    fun `normal and pro sdr render fallback follows resolved ten bit delivery state`() {
        val fallbackBlock = source.substringAfter("val simulatedTenBitFallback =")
            .substringBefore("apexPipelineState.updateScene")
        val graphRequestBlock = source.substringAfter("VideoPipelineGraphRequest(")
            .substringBefore(")\n        )")

        assertTrue(
            "Normal SDR and Pro SDR are strict 10-bit delivery states. Preview/render fallback " +
                "must follow the resolved pipeline contract rather than an old codec-only toggle.",
            fallbackBlock.contains("deliveryState.tenBitActive") &&
                fallbackBlock.contains("!gpuRecording.supports10Bit()")
        )
        assertTrue(
            "The graph controller must receive real GPU P010 capability so Normal/Pro SDR preview " +
                "and parity metadata cannot overclaim 10-bit when the renderer cannot support it.",
            graphRequestBlock.contains("gpu10BitSupported = gpuRecording.supports10Bit()")
        )
        assertFalse(fallbackBlock.contains("gpuRecording.videoCodec == GpuVideoCodec.HEVC"))
        assertFalse(fallbackBlock.contains("resolveVideoRecordingTenBitRequested()"))
    }

    @Test
    fun `recording does not force preview view grade into high load look`() {
        assertTrue(
            "Recording may disable expensive AI stages, but it must not flip the visible " +
                "preview view into high-load paint mode by itself.",
            source.contains("val visualHighLoad = isHighLoadPreviewMode()")
        )
        assertTrue(
            "The visible preview grade must not include recording state as a high-load input; " +
                "that caused the preview to look different after pressing record.",
            source.contains("val highLoad = highLoadPreviewOverride ?: isHighLoadPreviewMode()")
        )
        assertTrue(
            "The old grade expression must stay removed.",
            !source.contains("isHighLoadPreviewMode() || recordingStateActive")
        )
    }

    @Test
    fun `truthful video delivery keeps tone visibility floors instead of zeroing grade`() {
        val gradeBody = gradeSource

        assertFalse(
            "Truthful HDR/LOG/RAW encoder delivery must not zero shadow lift; that makes recorded " +
                "video visibly darker than preview and removes the shader's tone visibility floor.",
            gradeBody.contains("val resolvedShadowLift = if (neutralizeTruthfulGrade) 0f else shadowLift")
        )
        assertFalse(
            "Truthful HDR/LOG/RAW encoder delivery must not zero highlight compression; the shader " +
                "needs a nonzero rolloff parameter for scene-linear video modes.",
            gradeBody.contains("val resolvedHighlightCompression = if (neutralizeTruthfulGrade) 0f else highlightCompression")
        )
        assertTrue(
            "A named tone visibility floor must protect all video delivery modes while keeping " +
                "contrast/saturation/color scales neutral.",
            gradeBody.contains("truthfulToneVisibilityFloor")
        )
        assertTrue(
            "Display-referred video preview also needs the visibility floor, otherwise preview can " +
                "look darker than the encoded mode while scene analysis is still warming up.",
            gradeBody.contains("displayToneVisibilityFloor")
        )
    }

    @Test
    fun `ndr and nivukx preview grade neutralizes aggressive color paint before shader tone mapping`() {
        val gradeBody = gradeSource

        assertTrue(
            "NDR10+ and NIVUKX Vision must be treated as color-critical HDR modes; carrying " +
                "scene-profile saturation or RGB scales into these modes was the source of " +
                "red/orange skin and cyan highlight casts in preview and recording.",
            gradeBody.contains("val neoHdrColorCritical =")
        )
        assertTrue(gradeBody.contains("normalizedHdrMode.equals(\"NDR10+\", ignoreCase = true)"))
        assertTrue(gradeBody.contains("normalizedHdrMode.equals(\"NIVUKXVISION\", ignoreCase = true)"))
        assertTrue(gradeBody.contains("neoHdrColorCritical || deliveryVideoToneBias"))
        assertTrue(gradeBody.contains("coerceIn(0.94f, 1.06f)"))
        assertTrue(gradeBody.contains("NdrNivukxMeasuredCorrectionPolicy.resolve(normalizedHdrMode)"))
        assertTrue(gradeBody.contains("neoHdrColorCritical -> measuredNdrNivukxCorrection.redScale"))
        assertTrue(gradeBody.contains("neoHdrColorCritical -> measuredNdrNivukxCorrection.greenScale"))
        assertTrue(gradeBody.contains("neoHdrColorCritical -> measuredNdrNivukxCorrection.blueScale"))
        assertTrue(gradeBody.contains("neutralizeTruthfulGrade || neoHdrColorCritical -> 0f"))
    }

    @Test
    fun `truthful hdr preview color gain resets immediately instead of smoothing stale tint`() {
        val colorGainBody = source.substringAfter("private fun buildPreviewColorGain()")
            .substringBefore("private fun resolvedAspectRatio")

        assertTrue(
            "NDR/NIVUKX/HDR/LOG/RAW must not wait for previewColorGainState smoothing; stale " +
                "warm/cast gain is applied before shader HDR math and caused red false-color frames.",
            colorGainBody.contains("if (originalColorCriticalPath) {") &&
                colorGainBody.contains("previewColorGainState = floatArrayOf(1f, 1f, 1f, 0f)") &&
                colorGainBody.contains("return previewColorGainState.copyOf()") &&
                colorGainBody.indexOf("previewColorGainState = floatArrayOf(1f, 1f, 1f, 0f)") <
                    colorGainBody.indexOf("val sceneWarmBias = when (sceneType)")
        )
    }

    @Test
    fun `normal and pro sdr video keep manual color stable instead of applying scene rgb gain`() {
        val colorGainBody = source.substringAfter("private fun buildPreviewColorGain()")
            .substringBefore("private fun resolvedAspectRatio")

        assertTrue(
            "Normal and Pro SDR video are user-selected color science paths, so they must not use the " +
                "pre-shader RGB color-gain lane for scene warm-bias or EV changes. The shader " +
                "multiplies alpha by 18, so a small 0.05 blend became near-full color paint.",
            source.contains("import com.neoproc.neoproccamx.color.NormalSdrColorCalibrationPolicy") &&
            colorGainBody.contains("val stableNormalOrProSdrColor =") &&
                colorGainBody.contains("val normalSdrVideoDelivery =") &&
                colorGainBody.contains("NormalSdrColorCalibrationPolicy.ACTIVE_CORRECTION") &&
                colorGainBody.contains("red = normalSdrCalibration.redScale") &&
                colorGainBody.contains("green = normalSdrCalibration.greenScale") &&
                colorGainBody.contains("blue = normalSdrCalibration.blueScale") &&
                colorGainBody.contains("val targetBlendStrength = when") &&
                colorGainBody.contains("stableNormalOrProSdrColor -> 0f") &&
                colorGainBody.contains("if (!stableNormalOrProSdrColor) {")
        )
        val targetBlendBody = colorGainBody.substringAfter("val targetBlendStrength = when")
            .substringBefore("val target = floatArrayOf(")
        assertTrue(targetBlendBody.contains("stableNormalOrProSdrColor -> 0f"))
        assertTrue(targetBlendBody.contains("else -> maxOf(targetStrength, importCompensation.blendStrength).coerceIn(0f, 1f)"))
        assertTrue(
            "Normal and Pro SDR must exit before import compensation can tint the color-gain blend.",
            targetBlendBody.indexOf("stableNormalOrProSdrColor -> 0f") <
                targetBlendBody.indexOf("else -> maxOf(targetStrength, importCompensation.blendStrength).coerceIn(0f, 1f)")
        )
        assertFalse(colorGainBody.contains("standardNaturalSdr -> 0.05f"))
    }

    @Test
    fun `normal and pro sdr video color calibration is applied immediately without stale smoothing`() {
        val colorGainBody = source.substringAfter("private fun buildPreviewColorGain()")
            .substringBefore("private fun resolvedAspectRatio")

        assertTrue(
            "Normal and Pro SDR recording can start on the same frame the user switches mode. The " +
                "calibrated RGB gain must be assigned immediately, otherwise previous scene/EV " +
                "gain leaks into recording and preview does not match the encoded frame.",
            colorGainBody.contains("if (stableNormalOrProSdrColor) {") &&
                colorGainBody.contains("previewColorGainState = target.copyOf()") &&
                colorGainBody.contains("return previewColorGainState.copyOf()") &&
                colorGainBody.indexOf("previewColorGainState = target.copyOf()") <
                    colorGainBody.indexOf("val smoothing = when")
        )
    }

    @Test
    fun `normal and pro sdr video grade uses explicit adaptive intent without overriding manual color`() {
        val gradeBody = gradeSource

        assertTrue(
            "Normal and Pro SDR must resolve a shared bounded adaptive policy for preview and recording.",
            source.contains("import com.neoproc.neoproccamx.color.SdrQualityIntent") &&
                gradeBody.contains("val videoSurface = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)") &&
                gradeBody.contains("val stableNormalOrProSdrGrade =") &&
                gradeBody.contains("val adaptiveSdrGrade =") &&
                gradeBody.contains("NormalSdrVideoQualityPolicy.resolve(") &&
                gradeBody.contains("sdrQualityIntent = _sdrQualityIntent.value") &&
                gradeBody.contains("intent = sdrQualityIntent") &&
                gradeBody.contains("videoSurface &&") &&
                gradeBody.contains("deliveryHdrMode = deliveryState.effectiveHdrMode") &&
                gradeBody.contains("deliveryHdrMode == \"OFF\"") &&
                gradeBody.contains("!logCaptureActive") &&
                gradeBody.contains("!rawVideoDelivery") &&
                gradeBody.contains("!truthfulColorDelivery") &&
                gradeBody.contains("!neutralVideoPreview")
        )
        assertTrue(gradeBody.contains("stableNormalOrProSdrGrade -> adaptiveSdrGrade.contrast"))
        assertTrue(gradeBody.contains("stableNormalOrProSdrGrade -> adaptiveSdrGrade.saturation"))
        assertTrue(gradeBody.contains("stableNormalOrProSdrGrade -> adaptiveSdrGrade.shadowBoost"))
        assertTrue(gradeBody.contains("stableNormalOrProSdrGrade -> adaptiveSdrGrade.highlightCompression"))
        assertTrue(gradeBody.contains("stableNormalOrProSdrGrade -> 1f"))
        assertTrue(gradeBody.contains("stableNormalOrProSdrGrade -> 0f"))
        assertTrue(
            "The grade lock must not override user-selected color science or color space. Settings " +
                "selection still flows through the existing color profile resolver.",
            source.contains("resolvePreviewRecordingParityColorScience") &&
                source.contains("normalizeColorProfileName(_colorProfile.value)") &&
                source.contains("_videoColorSpace.value")
        )
    }

    @Test
    fun `normal and pro sdr video keeps adaptive highlight compression outside legacy cap`() {
        val gradeBody = gradeSource
        val highlightBody = gradeBody.substringAfter("val resolvedHighlightCompression =")
            .substringBefore("val resolvedRedScale")

        assertTrue(
            "Normal and Pro SDR use their adaptive envelope directly instead of the unrelated legacy cap.",
            highlightBody.contains("if (stableNormalOrProSdrGrade)") &&
                highlightBody.contains("adaptiveSdrGrade.highlightCompression") &&
                highlightBody.indexOf("if (stableNormalOrProSdrGrade)") <
                    highlightBody.indexOf("else")
        )
        assertFalse(
            "The adaptive Normal/Pro SDR branch must not be capped by the legacy 0.36 highlight ceiling.",
            highlightBody.substringBefore("else").contains("0.36f")
        )
    }

    @Test
    fun `pro sdr uses video preview color and performance routing`() {
        val lookStateBody = source.substringAfter("private fun activeVideoPreviewLookState()")
            .substringBefore("private fun activeVulkanPreviewRenderContract")
        val lutBody = source.substringAfter("private fun syncGpuLutState()")
            .substringBefore("private fun applyResolvedPreviewLut")
        val renderConfigBody = source.substringAfter("private fun pushVulkanPreviewRenderConfig(")
            .substringBefore("private fun buildPreviewColorGain")
        val previewSizeBody = source.substringAfter("private fun preferredPreviewSize()")
            .substringBefore("private fun currentPreviewRotationDegrees")

        assertTrue(lookStateBody.contains("VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)"))
        assertTrue(lutBody.contains("val videoSurface = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)"))
        assertTrue(lutBody.contains("if (videoSurface) activeVideoFormatDeliveryState()"))
        assertTrue(renderConfigBody.contains("val videoSurface = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)"))
        assertTrue(renderConfigBody.contains("val neoColorScienceEnabled = videoSurface"))
        assertTrue(renderConfigBody.contains("videoModeActive = videoSurface"))
        assertTrue(previewSizeBody.contains("val videoMode = VideoRenderProfileQualityHintPolicy.isVideoSurface(_uiMode.value)"))
    }

    @Test
    fun `record develop mode keeps live recorder flat and marks offline develop ownership`() {
        val lutBody = source.substringAfter("private fun syncGpuLutState()")
            .substringBefore("private fun applyResolvedPreviewLut")
        val renderConfigBody = source.substringAfter("private fun pushVulkanPreviewRenderConfig(")
            .substringBefore("private fun buildFallbackGpuSceneRenderProfile")
        val metadataBody = source.substringAfter("private fun buildRecordingRuntimeEvidenceMetadata(")
            .substringBefore("private fun resolveRecordingSourcePlan")

        assertTrue(source.contains("import com.neoproc.neoproccamx.rendering.RecordDevelopLivePipelinePolicy"))
        assertTrue(source.contains("import com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag"))
        assertTrue(lutBody.contains("RecordDevelopLivePipelinePolicy.recordCreativeLut(videoSurface, recordLutCandidate)"))
        assertTrue(renderConfigBody.contains("RecordDevelopLivePipelinePolicy.encoderGrade(videoSurface, encoderGradeCandidate)"))
        assertTrue(renderConfigBody.contains("RecordDevelopLivePipelinePolicy.encoderColorScience("))
        assertTrue(renderConfigBody.contains("RecordDevelopLivePipelinePolicy.enhancementUniforms(videoSurface, enhancementUniforms)"))
        assertTrue(renderConfigBody.contains("RecordDevelopLivePipelinePolicy.liveEnhanceUniforms(videoSurface, liveEnhanceArray)"))
        assertTrue(renderConfigBody.contains("!RecordDevelopLivePipelinePolicy.active(videoSurface)"))
        assertTrue(metadataBody.contains("\"recordDevelopLightMaster\" to recordDevelopLightMaster.toString()"))
        assertTrue(metadataBody.contains("\"recordDevelopLiveColorProcessing\" to if (recordDevelopLightMaster)"))
        assertTrue(metadataBody.contains("\"FLAT_10BIT_MASTER_ONLY\""))
        assertTrue(metadataBody.contains("\"recordDevelopOfflineOutputFormat\" to if (recordDevelopLightMaster)"))
        assertTrue(metadataBody.contains("\"R210_444\""))
        assertTrue(metadataBody.contains("\"ELITE_SNCE_TEMPORAL_DENOISE_DETAIL\""))
    }

    @Test
    fun `recording parity uses independent endpoint evidence and truthful average gpu timing`() {
        val lockBody = source.substringAfter("private fun activePreviewRecordingRenderGraphLock(")
            .substringBefore("private fun activePixelProcessingRealityReport(")

        assertTrue(lockBody.contains("previewShaderSha256 = previewShaderSignature"))
        assertTrue(lockBody.contains("recordingShaderSha256 = recordingShaderSignature"))
        assertTrue(lockBody.contains("previewEnhancementIdentity ="))
        assertTrue(lockBody.contains("recordingEnhancementIdentity ="))
        assertTrue(lockBody.contains("previewGradeIdentity = lastPreviewGradeSha256"))
        assertTrue(lockBody.contains("recordingGradeIdentity = lastRecordingGradeSha256"))
        assertTrue(lockBody.contains("previewOutputFormat ="))
        assertTrue(lockBody.contains("recordingOutputFormat ="))
        assertTrue(lockBody.contains("previewOutputTransfer ="))
        assertTrue(lockBody.contains("recordingOutputTransfer ="))
        assertTrue(lockBody.contains("previewOutputGamut ="))
        assertTrue(lockBody.contains("recordingOutputGamut ="))
        assertTrue(lockBody.contains("previewOutputBitDepth ="))
        assertTrue(lockBody.contains("recordingOutputBitDepth ="))
        assertTrue(lockBody.contains("previewOutputQuantizationLevels ="))
        assertTrue(lockBody.contains("recordingOutputQuantizationLevels ="))
        assertTrue(
            lockBody.contains(
                "averageFrameProcessingMs = vulkanRuntime.currentMeasuredAverageComputeDurationMs"
            )
        )
        assertTrue(lockBody.contains("averageRuntimeBudgetMs ="))
        assertFalse(lockBody.contains("maxFrameProcessingMs"))
        assertFalse(lockBody.contains("currentMeasuredComputeDurationMs"))
    }

    @Test
    fun `recording output depth and final evidence use actual encoder surface precision`() {
        val lockBody = source.substringAfter("private fun activePreviewRecordingRenderGraphLock(")
            .substringBefore("private fun activePixelProcessingRealityReport(")
        val metadataBody = source
            .substringAfter("private fun buildRecordingRuntimeEvidenceMetadata(")
            .substringBefore("private fun resolveRecordingSourcePlan(")
        val preservedEvidenceBody = source
            .substringAfter("private fun preserveValidatedGpuRecordingEvidence(stats: VulkanImportStats)")
            .substringBefore("private fun applyCinemaModeSelection(")

        assertTrue(lockBody.contains("vulkanRuntime.currentEncoderSurfaceStatus"))
        assertTrue(lockBody.contains("recordingOutputBitDepth = actualRecordingOutputBitDepth"))
        assertTrue(lockBody.contains("previewOutputBitDepth = previewOutputBitDepth"))
        assertTrue(metadataBody.contains("\"vulkanEncoderSurfaceEvidenceApplicable\""))
        assertTrue(metadataBody.contains("\"vulkanEncoderSurfacePrecisionProven\""))
        assertTrue(metadataBody.contains("\"vulkanEncoderSurfaceBitDepth\""))
        assertTrue(metadataBody.contains("\"vulkanEncoderSurfacePrecisionFallback\""))
        assertTrue(metadataBody.contains("\"vulkanEncoderSurfaceColorSpace\""))
        assertTrue(metadataBody.contains("\"vulkanEncoderSurfaceDeliveryMode\""))
        assertTrue(metadataBody.contains("\"vulkanEncoderSurfaceDeliveryCompatible\""))
        assertTrue(preservedEvidenceBody.contains("vulkanRuntime.currentEncoderSurfaceStatus"))
        assertTrue(preservedEvidenceBody.contains("encoderSurfaceStatus.colorSpace"))
        assertTrue(preservedEvidenceBody.contains("encoderSurfaceStatus.deliveryMode"))
        assertTrue(preservedEvidenceBody.contains("encoderSurfaceStatus.deliveryCompatible"))
        assertTrue(source.contains("lastValidatedGpuRecordingEvidence = ValidatedGpuRecordingEvidence.EMPTY"))
        assertTrue(source.contains("private fun hasCurrentRecordingEncoderSurfaceEvidence()"))
        assertTrue(source.contains("is GpuRecordingState.Stopping -> true"))
        assertTrue(lockBody.contains("hasCurrentRecordingEncoderSurfaceEvidence()"))
    }

    @Test
    fun `normal pro sdr gpu enhancement has deterministic fallback before scene analyzer warms`() {
        val renderConfigBody = source.substringAfter("private fun pushVulkanPreviewRenderConfig(")
            .substringBefore("private fun buildFallbackGpuSceneRenderProfile")
        val connectPreviewBody = source.substringAfter("fun connectPreviewView(previewView: CameraVulkanPreviewView)")
            .substringBefore("/**")

        assertTrue(
            "Normal/Pro SDR cannot wait for the scene analyzer before receiving the elite denoise/detail " +
                "GPU packet; otherwise early preview and recording start can run with stale/default uniforms.",
            source.contains("private fun buildFallbackGpuSceneRenderProfile(") &&
                renderConfigBody.contains("val profile = currentSceneRenderProfile ?: buildFallbackGpuSceneRenderProfile(renderContract)") &&
                renderConfigBody.contains("GpuImageEnhancementUniforms.fromSceneProfile(") &&
                !renderConfigBody.contains("currentSceneRenderProfile?.let")
        )
        assertTrue(
            "The Java/Kotlin preview compositor must also receive the fallback profile instead of its zeroed " +
                "default so startup preview quality matches recording-grade SDR routing.",
            connectPreviewBody.contains(
                "previewView.setSceneRenderProfile(currentSceneRenderProfile ?: buildFallbackGpuSceneRenderProfile(activeVulkanPreviewRenderContract()))"
            )
        )
    }

    @Test
    fun `manual controls do not force normal sdr camera2 recording into low processing`() {
        assertTrue(
            "Camera2 low-processing overrides must be scoped to LOG/RAW sensor-truth paths. " +
                "Normal SDR uses the stable Camera2 encoder route, so it needs high-quality " +
                "HAL NR, edge, shading, tonemap, and lens cleanup even when pro controls are visible.",
            source.contains("private fun camera2LowProcessingOverridesActive()") &&
                source.contains("camera.setCinemaFidelityOverridesEnabled(camera2LowProcessingOverridesActive())") &&
                source.contains("_cinemaVideoMode.value == CinemaVideoMode.LOG") &&
                source.contains("_cinemaVideoMode.value == CinemaVideoMode.RAW") &&
                source.contains("isVideoLogCaptureEnabled()") &&
                source.contains("isRawVideoModeActive()")
        )
        val helperBody = source.substringAfter("private fun camera2LowProcessingOverridesActive()")
            .substringBefore("private fun targetPreviewFpsForCurrentMode()")
        assertFalse(
            "Manual controls alone must not disable Camera2 processing for normal SDR recording.",
            helperBody.contains("_manualControlsVisible.value")
        )
    }

    @Test
    fun `selecting ndr or nivukx applies hdr transition before committing hdr state`() {
        val hdrModeBody = source.substringAfter("fun setVideoHdrMode(mode: String)")
            .substringBefore("// -------------------------------------------------------------------------\r\n    // CAMERA SWITCH")

        assertTrue(
            "NDR/NIVUKX selection must go through the transition policy so RAW and LOG modes " +
                "are converted to PRO before HDR state is committed.",
            hdrModeBody.contains("VideoHdrModeTransitionPolicy.resolve(")
        )
        assertTrue(hdrModeBody.contains("transition.cinemaModeOverride?.let"))
        assertTrue(hdrModeBody.contains("applyCinemaModeSelection("))
        val afterTransitionBlock = hdrModeBody.substringAfter("transition.cinemaModeOverride?.let")
        assertTrue(afterTransitionBlock.indexOf("applyCinemaModeSelection(") <
            afterTransitionBlock.indexOf("_videoHdrMode.value = normalized"))
    }

    @Test
    fun `restoring persisted video state reconciles hdr before applying cinema mode`() {
        val startupBody = source.substringAfter("private fun applyStartupPreferences()")
            .substringBefore("private fun refreshPreferences(")
        val preferencesBody = source.substringAfter("private fun applyPreferences(")
            .substringBefore("private fun observeCaptureResults")
        val restoreBody = source.substringAfter("private fun restoreVideoFeatures(")
            .substringBefore("fun setPreviewAspect")

        assertTrue(
            "Startup restore must reconcile NDR/NIVUKX against persisted RAW/LOG before " +
                "applyCinemaModeSelection can clear HDR back to OFF.",
            startupBody.contains("VideoModeRestorePolicy.resolve(")
        )
        assertTrue(
            "Preference refresh must use the same restore policy so delayed preference loads " +
                "cannot downgrade NDR/NIVUKX to SDR.",
            preferencesBody.contains("VideoModeRestorePolicy.resolve(")
        )
        assertTrue(
            "Feature restore must use the same policy because it runs after startup and was " +
                "the path that rewrote the graph to hdr=OFF.",
            restoreBody.contains("VideoModeRestorePolicy.resolve(")
        )
        assertTrue(startupBody.indexOf("VideoModeRestorePolicy.resolve(") <
            startupBody.indexOf("applyCinemaModeSelection("))
        assertTrue(preferencesBody.indexOf("VideoModeRestorePolicy.resolve(") <
            preferencesBody.indexOf("applyCinemaModeSelection("))
        assertTrue(restoreBody.indexOf("VideoModeRestorePolicy.resolve(") <
            restoreBody.indexOf("applyCinemaModeSelection("))
    }

    @Test
    fun `cinema master bitrate uses bounded resolution tiers`() {
        val body = source.substringAfter("private fun cinemaMasterBitrateForSelection(")
            .substringBefore("private fun isAutomaticBitrateMode")

        assertTrue(body.contains("strictImaxFloorBps"))
        assertTrue(body.contains("HighEndQualityPolicy.minimumBitrateBps"))
        assertTrue(body.contains("pixels * effectiveFps * 5"))
        assertTrue(body.contains(".coerceAtLeast(strictImaxFloorBps)"))
    }

    @Test
    fun `recording bitrate resolution forwards selected video detail profile`() {
        val recommendedBody = source.substringAfter("private fun recommendedBitrateForSelection(")
            .substringBefore("private fun isCinema4k60Selection")
        val configuredBody = source.substringAfter("private fun resolveConfiguredBitrate(")
            .substringBefore("fun reportedVideoBitrateBitsPerSecond")
        val applyConfigBody = source.substringAfter("private fun applyResolvedRecordingConfig(")
            .substringBefore("private fun shouldEnforceNativeRecordingResolution")

        assertTrue(recommendedBody.contains("videoDetailProfile = _videoDetailProfile.value"))
        assertTrue(configuredBody.contains("videoDetailProfile = _videoDetailProfile.value"))
        assertTrue(applyConfigBody.contains("videoDetailProfile = _videoDetailProfile.value"))
        assertTrue(source.contains("gpuRecording.videoDetailProfile = _videoDetailProfile.value"))
    }

    @Test
    fun `recording bitrate resolution forwards effective encoder fps at every call site`() {
        val applyConfigBody = source.substringAfter("private fun applyResolvedRecordingConfig(")
            .substringBefore("private fun shouldEnforceNativeRecordingResolution")
        val configuredBody = source.substringAfter("private fun resolveConfiguredBitrate(")
            .substringBefore("fun reportedVideoBitrateBitsPerSecond")
        val preferenceBody = source.substringAfter("private fun applyVideoBitrateValue(bps: Int)")
            .substringBefore("private fun applyAutomaticVideoBitrateIfNeeded")
        val applyConfigCall = extractBalancedCalls(
            body = applyConfigBody,
            invocation = "gpuRecording.resolveSupportedBitrate"
        ).single()
        val configuredCalls = extractBalancedCalls(
            body = configuredBody,
            invocation = "gpuRecording.resolveSupportedBitrate"
        )
        val preferenceCall = extractBalancedCalls(
            body = preferenceBody,
            invocation = "gpuRecording.resolveSupportedBitrate"
        ).single()

        assertTrue(applyConfigCall.withoutWhitespace().contains("fps=effectiveFps"))
        assertTrue(
            configuredBody.withoutWhitespace().contains(
                "valeffectiveFps=RecordingPreviewFrameRatePolicy.resolveEncoderFps(selection,fps)"
            )
        )
        assertTrue(configuredCalls.size == 2)
        assertTrue(configuredCalls.all { it.withoutWhitespace().contains("fps=effectiveFps") })
        assertTrue(preferenceCall.withoutWhitespace().contains("fps=recording.fps"))
    }

    @Test
    fun `camera view model forwards explicit sdr quality intent only to sdr policy`() {
        val renderConfigBody = source.substringAfter("private fun pushVulkanPreviewRenderConfig(")
            .substringBefore("private fun buildFallbackGpuSceneRenderProfile")
        val normalizedRenderConfig = renderConfigBody.withoutWhitespace()

        assertTrue(source.contains("private val _sdrQualityIntent = MutableStateFlow(SdrQualityIntent.NORMAL)"))
        assertTrue(source.contains("internal fun setSdrQualityIntent(intent: SdrQualityIntent)"))
        assertTrue(
            normalizedRenderConfig.contains(
                "sdrQualityIntent=if(forceNormalSdrRealtimeLock){_sdrQualityIntent.value}else{null}"
            )
        )
    }

    @Test
    fun `thermal bitrate reduction updates active encoder without overwriting idle preference path`() {
        val thermalBody = source.substringAfter("private fun applyThermalBitrateReduction()")
            .substringBefore("private fun appendThermalCooldownEstimate")
        val preferenceBody = source.substringAfter("private fun applyVideoBitrateValue(bps: Int)")
            .substringBefore("private fun applyAutomaticVideoBitrateIfNeeded")

        assertTrue(thermalBody.contains("gpuRecording.updateActiveEncoderBitrate(reduced)"))
        assertTrue(thermalBody.contains("gpuRecording.activeOutputBitrateBitsPerSecond()"))
        assertFalse(thermalBody.contains("applyVideoBitrateValue(reduced)"))
        assertFalse(thermalBody.contains("prefs.videoBitrate"))
        assertTrue(preferenceBody.contains("prefs.videoBitrate = bps"))
    }

    @Test
    fun `strict high end thermal admission refuses instead of downgrading recording`() {
        val startBody = source.substringAfter("private fun startRecordingInternal()")
            .substringBefore("private fun shouldUseUncompressedCinemaPipeline()")
        val admissionIndex = startBody.indexOf("strictHighEndThermalAdmissionFailure()")
        val runtimeHintIndex = startBody.indexOf("applyRecordingCriticalRuntimeHints(")
        val admissionBody = source.substringAfter("private fun strictHighEndThermalAdmissionFailure()")
            .substringBefore("private fun maybeApplyThermalGuard")
        val guardBody = source.substringAfter("private fun maybeApplyThermalGuard(thermalLabel: String)")
            .substringBefore("private fun applyThermalBitrateReduction()")

        assertTrue(admissionIndex >= 0)
        assertTrue(runtimeHintIndex > admissionIndex)
        assertTrue(admissionBody.contains("HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly"))
        assertTrue(admissionBody.contains("status == PowerManager.THERMAL_STATUS_EMERGENCY"))
        assertTrue(admissionBody.contains("cool the device before starting"))
        assertTrue(guardBody.contains("HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly"))
        assertTrue(guardBody.contains("gpuRecording.thermalEncoderComplexity = 10"))
        assertTrue(guardBody.contains("thermalAnalysisFpsCap = null"))
        assertTrue(guardBody.contains("thermalPreviewMaxLongEdge = null"))
        assertTrue(guardBody.contains("thermalPreviewMaxArea = null"))
        assertTrue(guardBody.contains("return"))
    }

    @Test
    fun `preview grade while recording preserves only already running monitor compositor`() {
        val block = source.substringAfter("private fun recordingStartOrActiveBlocksVisiblePreviewRebind()")
            .substringBefore("private fun markGlPreviewOwnerCooldown")
        val startBody = source.substringAfter("private fun startRecordingInternal()")
            .substringBefore("gpuRecording.orientationHintDegrees = camera.videoOrientationHint()")

        assertTrue(block.contains("stableVisibleMonitorAlreadyRunning"))
        assertTrue(block.contains("glPreviewCompositorActive && previewGlCompositor.isRunning()"))
        assertTrue(block.contains("hdr10PqPreviewCompositorActive && hdr10PqPreviewCompositor.isRunning()"))
        assertTrue(block.contains("PreviewGradeWhileRecordingFlag.enabled"))
        assertTrue(block.contains("shouldCompositeVisibleGlPreview()"))
        val preserveCondition = block.substringAfter("if (\n            com.neoproc.neoproccamx.rendering.PreviewGradeWhileRecordingFlag.enabled")
            .substringBefore(") {\n            return false")
        assertTrue(
            "The recording rebind bypass must require an already-running compositor; otherwise " +
                "recording can start/rebind GL mid-record and freeze preview.",
            preserveCondition.contains("shouldCompositeVisibleGlPreview()") &&
                preserveCondition.contains("stableVisibleMonitorAlreadyRunning")
        )
        assertTrue(startBody.contains("if (!com.neoproc.neoproccamx.rendering.PreviewGradeWhileRecordingFlag.enabled)"))
        assertTrue(startBody.contains("releaseVisibleGlPreviewCompositorsForRecordingFeed"))
    }

    @Test
    fun `pro manual signal is included in camera cinema fidelity routing`() {
        val fidelityBody = source.substringAfter("private fun isCinemaFidelityModeActive()")
            .substringBefore("private fun isVideoLogPreviewActive")
        val manualToggleBody = source.substringAfter("fun toggleManualControls()")
            .substringBefore("fun setZebraThresh")

        assertTrue(fidelityBody.contains("_manualControlsVisible.value"))
        assertTrue(manualToggleBody.contains("syncCameraPreviewLookControls()"))
    }

    @Test
    fun `tap focus can release exposure because recording white balance is independently shot locked`() {
        val focusBody = source.substringAfter("fun tapToFocus(nx: Float, ny: Float)")
            .substringBefore("fun lockFocusAndExposure")

        assertTrue(focusBody.contains("camera.setAeAwbLock(false)"))
    }

    @Test
    fun `tap focus does not treat live lens distance as manual focus state`() {
        val focusBody = source.substringAfter("fun tapToFocus(nx: Float, ny: Float)")
            .substringBefore("fun lockFocusAndExposure")

        assertFalse(focusBody.contains("cameraParams.value.focusDist >= 0f"))
        assertFalse(focusBody.contains("setAutoFocus()"))
        assertTrue(focusBody.contains("camera.triggerTapAF"))
    }

    private fun extractBalancedCalls(body: String, invocation: String): List<String> {
        val calls = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val invocationStart = body.indexOf(invocation, startIndex = searchFrom)
            if (invocationStart < 0) return calls
            val openingParenthesis = body.indexOf('(', startIndex = invocationStart + invocation.length)
            require(openingParenthesis >= 0) { "Missing opening parenthesis for $invocation" }
            var depth = 0
            var closingParenthesis = -1
            for (index in openingParenthesis until body.length) {
                when (body[index]) {
                    '(' -> depth += 1
                    ')' -> {
                        depth -= 1
                        if (depth == 0) {
                            closingParenthesis = index
                            break
                        }
                    }
                }
            }
            require(closingParenthesis >= 0) { "Unbalanced call for $invocation" }
            calls += body.substring(invocationStart, closingParenthesis + 1)
            searchFrom = closingParenthesis + 1
        }
    }

    private fun String.withoutWhitespace(): String = replace(Regex("\\s+"), "")
}
