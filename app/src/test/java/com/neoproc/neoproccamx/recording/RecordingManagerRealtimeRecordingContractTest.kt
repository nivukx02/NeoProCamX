package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingManagerRealtimeRecordingContractTest {
    private val source = File("src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt").readText()

    @Test
    fun tenSecondsAtThirtyFpsDetectsTwoHundredSeventyFramesAsShortfallWithoutMuxDrops() {
        val activeDurationUs = RecordingManager.activeRecordingDurationUsForEvidence(
            recordingStartNs = 1_000_000_000L,
            stoppedAtNs = 11_000_000_000L,
            accumulatedPausedUs = 0L
        )
        val expectedFrameCount = RecordingManager.expectedFrameCountForEvidence(
            activeDurationUs = activeDurationUs,
            fps = 30
        )
        val actualFrameCount = 270L
        val muxDroppedFrames = 0

        assertEquals(10_000_000L, activeDurationUs)
        assertEquals(298L, expectedFrameCount)
        assertTrue(actualFrameCount < expectedFrameCount)
        assertEquals(0, muxDroppedFrames)
    }

    @Test
    fun frameEvidenceDurationSubtractsCompletedPauseWindows() {
        assertEquals(
            8_000_000L,
            RecordingManager.activeRecordingDurationUsForEvidence(
                recordingStartNs = 1_000_000_000L,
                stoppedAtNs = 11_000_000_000L,
                accumulatedPausedUs = 2_000_000L
            )
        )
    }

    @Test
    fun strictClipTimingEvidenceStartsAtValidatedRecordingWindow() {
        val durationUs = RecordingManager.clipTimingDurationUsForEvidence(
            recordingStartNs = 1_000_000_000L,
            clipTimingStartNs = 4_500_000_000L,
            stoppedAtNs = 14_500_000_000L,
            accumulatedPausedUs = 1_500_000L,
            accumulatedPausedAtClipTimingStartUs = 500_000L
        )

        assertEquals(9_000_000L, durationUs)
    }

    @Test
    fun sensorAudioBoundaryDoesNotInflateTheStrictFrameGateWindow() {
        val selection = RecordingManager.resolveClipTimingStartSelection(
            recordingStartNs = 1_000_000_000L,
            observedNowNs = 2_300_000_000L,
            sensorTimestampNs = 2_080_000_000L,
            submittedAtNs = 2_200_000_000L
        )

        assertEquals(2_080_000_000L, selection.audioBoundaryNs)
        assertEquals("sensor", selection.audioBoundarySource)
        assertEquals(2_200_000_000L, selection.frameGateStartNs)
        assertEquals("ingress", selection.frameGateSource)
    }

    @Test
    fun invalidSensorClockFallsBackToTheValidatedIngressBoundary() {
        val selection = RecordingManager.resolveClipTimingStartSelection(
            recordingStartNs = 1_000_000_000L,
            observedNowNs = 2_300_000_000L,
            sensorTimestampNs = 900_000_000L,
            submittedAtNs = 2_200_000_000L
        )

        assertEquals(2_200_000_000L, selection.audioBoundaryNs)
        assertEquals(2_200_000_000L, selection.frameGateStartNs)
        assertEquals("ingress", selection.audioBoundarySource)
        assertEquals("ingress", selection.frameGateSource)
    }

    @Test
    fun encodedTimelineEvidenceDurationIgnoresPreFirstFrameAndFinalizationWallTime() {
        val wallDurationUs = RecordingManager.activeRecordingDurationUsForEvidence(
            recordingStartNs = 0L,
            stoppedAtNs = 11_700_000_000L,
            accumulatedPausedUs = 0L
        )
        val encodedDurationUs = RecordingManager.encodedVideoTimelineDurationUsForEvidence(
            firstVideoPtsUs = 0L,
            lastVideoPtsUs = 9_348_115L,
            encodedVideoSampleCount = 218L,
            fps = 24
        )
        val expectedFrameCount = RecordingManager.expectedFrameCountForEvidence(
            activeDurationUs = encodedDurationUs,
            fps = 24
        )

        assertEquals(11_700_000L, wallDurationUs)
        assertEquals(9_389_781L, encodedDurationUs)
        assertEquals(223L, expectedFrameCount)
    }

    @Test
    fun managerUsesTimelineExpectedFramesInsteadOfEncodedCountSelfCertification() {
        val qualityBlock = source
            .substringAfter("private fun writeQualityCertificateForFinalizedRecording(")
            .substringBefore("private fun resolveQualityEvidenceMode(")

        assertFalse(qualityBlock.contains("expectedFrameCount = encodedVideoSampleCount"))
        assertTrue(qualityBlock.contains("expectedFrameCountForEvidence("))
        assertTrue(qualityBlock.contains("cadenceToleranceFrames = QUALITY_EVIDENCE_CADENCE_TOLERANCE_FRAMES"))
        assertTrue(qualityBlock.contains("actualFrameCount = encodedVideoSampleCount"))
        assertTrue(qualityBlock.contains("maxAllowedDroppedFrames = 0"))
    }

    @Test
    fun managerResolvesNormalProSdrEvidenceModeFromRuntimeEnhancementIntent() {
        val qualityBlock = source
            .substringAfter("private fun writeQualityCertificateForFinalizedRecording(")
            .substringBefore("val plannedSourceBitDepth")
        val resolverBlock = source
            .substringAfter("private fun resolveQualityEvidenceMode(")
            .substringBefore("private fun resolveSourceBitDepthForQualityEvidence(")

        assertTrue(qualityBlock.indexOf("val runtimeMetadata =") <
            qualityBlock.indexOf("val evidenceMode = resolveQualityEvidenceMode(runtimeMetadata)"))
        assertTrue(resolverBlock.contains("runtimeSdrQualityIntent(runtimeMetadata).equals(\"PRO\""))
        assertTrue(resolverBlock.contains("runtimeSdrQualityIntent(runtimeMetadata).equals(\"NORMAL\""))
        assertTrue(resolverBlock.contains("sdrQualityIntent=PRO"))
        assertTrue(resolverBlock.contains("sdrQualityIntent=NORMAL"))
    }

    @Test
    fun actualVulkanProducerPrecisionCapsPlannedMain10Evidence() {
        val plannedSourceBitDepth = 10
        val plannedStorageBitDepth = 10

        val packedTenBit = RecordingManager.resolveEncoderSurfaceBitDepthEvidence(
            plannedSourceBitDepth = plannedSourceBitDepth,
            plannedStorageBitDepth = plannedStorageBitDepth,
            runtimeMetadata = encoderSurfaceMetadata(bitDepth = 10, precision = "PACKED_10_BIT")
        )
        val fp16 = RecordingManager.resolveEncoderSurfaceBitDepthEvidence(
            plannedSourceBitDepth = plannedSourceBitDepth,
            plannedStorageBitDepth = plannedStorageBitDepth,
            runtimeMetadata = encoderSurfaceMetadata(bitDepth = 16, precision = "FP16")
        )
        val eightBit = RecordingManager.resolveEncoderSurfaceBitDepthEvidence(
            plannedSourceBitDepth = plannedSourceBitDepth,
            plannedStorageBitDepth = plannedStorageBitDepth,
            runtimeMetadata = encoderSurfaceMetadata(
                bitDepth = 8,
                precision = "EIGHT_BIT",
                fallback = true
            )
        )
        val unknown = RecordingManager.resolveEncoderSurfaceBitDepthEvidence(
            plannedSourceBitDepth = plannedSourceBitDepth,
            plannedStorageBitDepth = plannedStorageBitDepth,
            runtimeMetadata = mapOf(
                "vulkanEncoderSurfaceEvidenceApplicable" to "true",
                "vulkanEncoderSurfacePrecisionProven" to "false"
            )
        )
        val incompatibleTenBit = RecordingManager.resolveEncoderSurfaceBitDepthEvidence(
            plannedSourceBitDepth = plannedSourceBitDepth,
            plannedStorageBitDepth = plannedStorageBitDepth,
            runtimeMetadata = encoderSurfaceMetadata(
                bitDepth = 10,
                precision = "PACKED_10_BIT",
                proven = false
            )
        )

        assertEquals(10, packedTenBit.effectiveSourceBitDepth)
        assertEquals(10, packedTenBit.effectiveStorageBitDepth)
        assertEquals("PASS", packedTenBit.proofStatus)
        assertEquals(10, fp16.effectiveSourceBitDepth)
        assertEquals(10, fp16.effectiveStorageBitDepth)
        assertEquals("PASS", fp16.proofStatus)
        assertEquals(8, eightBit.effectiveSourceBitDepth)
        assertEquals(8, eightBit.effectiveStorageBitDepth)
        assertEquals("FAIL", eightBit.proofStatus)
        assertEquals(0, unknown.effectiveSourceBitDepth)
        assertEquals(0, unknown.effectiveStorageBitDepth)
        assertEquals("NOT_PROVEN", unknown.proofStatus)
        assertEquals(0, incompatibleTenBit.effectiveSourceBitDepth)
        assertEquals(0, incompatibleTenBit.effectiveStorageBitDepth)
        assertEquals(10, incompatibleTenBit.actualBitDepth)
        assertEquals("PACKED_10_BIT", incompatibleTenBit.precision)
        assertEquals("NOT_PROVEN", incompatibleTenBit.proofStatus)
    }

    @Test
    fun managerUsesRuntimeProducerDepthBeforeBuildingTruthMetadataAndCertificate() {
        val qualityBlock = source
            .substringAfter("private fun writeQualityCertificateForFinalizedRecording(")
            .substringBefore("private fun resolveQualityEvidenceMode(")
        val runtimeMetadataIndex = qualityBlock.indexOf("val runtimeMetadata =")
        val precisionIndex = qualityBlock.indexOf("resolveEncoderSurfaceBitDepthEvidence(")
        val truthIndex = qualityBlock.indexOf("NeoProfileTruthMetadataPolicy.resolve(")
        val certificateIndex = qualityBlock.indexOf("RecordingQualityEvidencePolicy.evaluate(")

        assertTrue(runtimeMetadataIndex >= 0)
        assertTrue(precisionIndex > runtimeMetadataIndex)
        assertTrue(truthIndex > precisionIndex)
        assertTrue(certificateIndex > truthIndex)
        assertTrue(qualityBlock.contains("vulkanEncoderSurfacePrecisionProofStatus"))
        assertTrue(qualityBlock.contains("vulkanEncoderSurfaceActualBitDepth"))
    }

    @Test
    fun managerPrefersFinalRuntimeMetadataAndRuntimeProofForCertificate() {
        val qualityBlock = source
            .substringAfter("private fun writeQualityCertificateForFinalizedRecording(")
            .substringBefore("private fun resolveQualityEvidenceMode(")
        val lookupIndex = qualityBlock.indexOf("val runtimeMetadataLookup")
        val previewSignatureIndex = qualityBlock.indexOf("runtimeMetadataLookup(\"previewQualitySignature\")")
        val recordingSignatureIndex = qualityBlock.indexOf("runtimeMetadataLookup(\"recordingQualitySignature\")")
        val captureSignatureIndex = qualityBlock.indexOf("runtimeMetadataLookup(\"parityCertificateSignature\")")

        assertTrue(lookupIndex >= 0)
        assertTrue(previewSignatureIndex > lookupIndex)
        assertTrue(recordingSignatureIndex > lookupIndex)
        assertTrue(captureSignatureIndex > lookupIndex)
        assertFalse(qualityBlock.contains("sceneMetadataValue(\"previewQualitySignature\") ?: qualitySignature"))
        assertFalse(qualityBlock.contains("sceneMetadataValue(\"recordingQualitySignature\") ?: qualitySignature"))
        assertTrue(qualityBlock.contains("RecordingDeviceRuntimeProofPolicy.evaluate("))
        assertTrue(qualityBlock.contains("deviceLogValidated = deviceRuntimeProof.deviceLogValidated"))
        assertTrue(qualityBlock.contains("adbLogEvidencePath = deviceRuntimeProof.adbLogEvidencePath"))
    }

    @Test
    fun managerContainsExactEncoderPresentationTrackerAndPtsSynchronizer() {
        assertFalse(source.contains("Channel.CONFLATED"))
        assertTrue(source.contains("RecordingFramePresentationTracker"))
        assertTrue(source.contains("encoderPresentationForOutput"))
        assertTrue(source.contains("RecordingFramePresentation"))
        assertTrue(source.contains("RecordingPtsSynchronizer"))
        assertTrue(source.contains("ptsUsForSensorTimestamp"))
    }

    @Test
    fun managerReleasesRecordedFrameIdentityOnlyAfterSuccessfulContainerWrite() {
        val writerBlock = source
            .substringAfter("private fun startMuxerWriter()")
            .substringBefore("private fun muxerPrewarmBufferCount")
        val writeIndex = writerBlock.indexOf("movSessionController?.writeSample(")
        val commitPolicyIndex = writerBlock.indexOf("RecordingFrameCommitPolicy.committedSensorTimestampNs(")
        val callbackIndex = writerBlock.indexOf("onMuxerPacketWriteCommitted(")

        assertTrue(writeIndex >= 0)
        assertTrue(commitPolicyIndex > writeIndex)
        assertTrue(callbackIndex > commitPolicyIndex)
        assertTrue(writerBlock.contains("if (writeCommitted)"))
    }

    @Test
    fun managerAppliesEncoderPresentationTimestampsAndAbrRuntimeParameters() {
        assertTrue(source.contains("EGLExt.eglPresentationTimeANDROID"))
        assertTrue(source.contains("MediaCodec.PARAMETER_KEY_VIDEO_BITRATE"))
        assertTrue(source.contains("RecordingAbrPolicy.resolve"))
        assertTrue(source.contains("maybeApplyAbrBackpressure"))
    }

    @Test
    fun managerRoutesProSdrThroughEliteSdr10PlanAndAllIntraGate() {
        assertTrue(source.contains("activeEliteVideoQualityTierForPlan"))
        assertTrue(source.contains("EliteVideoQualityTier.ELITE_PRO_SDR_10BIT"))
        assertTrue(source.contains("activeHdrEliteTierForPlan"))
        assertTrue(source.contains("activeLogEliteTierForPlan"))
        assertTrue(source.contains("EliteVideoQualityTier.ELITE_HDR_NDR10_PLUS_10BIT"))
        assertTrue(source.contains("EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT"))
        assertTrue(source.contains("EliteVideoQualityTier.ELITE_LOG_N_LOG_BASE_10BIT"))
        assertTrue(source.contains("EliteVideoQualityTier.ELITE_LOG_N_LOG_SHADOW_10BIT"))
        assertTrue(source.contains("EliteVideoQualityTier.ELITE_LOG_N_LOG_HIGHLIGHT_10BIT"))
        assertTrue(source.contains("EliteVideoQualityTier.ELITE_LOG_N_LOG_CINEMA_10BIT"))
        assertTrue(source.contains("EliteVideoQualityTier.ELITE_RAW_12BIT"))
        assertTrue(source.contains("val eliteQualityTierForPlan = activeEliteVideoQualityTierForPlan()"))
        assertTrue(source.contains("eliteQualityTier = eliteQualityTierForPlan"))
        assertTrue(source.contains("videoPlan.allIntraMastering"))
        assertTrue(source.contains("advancedQualityRetryAllowed"))
        assertTrue(source.contains("!(cinemaMasteringEnabled || videoPlan.allIntraMastering || videoPlan.qpTuningEnabled)"))
        assertTrue(source.contains("eliteRuntimeQualityLocked"))
        assertTrue(source.contains("cinemaMasteringEnabled || allIntraActiveInternal ||"))
    }

    @Test
    fun cinemaMasterDoesNotImplicitlyActivateAllIntraAndRejectedRequestKeepsRealtimeGop() {
        val allIntraBlock = source
            .substringAfter("// â”€â”€ ALL-INTRA HEVC WIRING")
            .substringBefore("// s6-f01: capture the requested transport-truth values")

        assertTrue(
            source.contains(
                "if (videoPlan.allIntraMastering && mime.equals(\"video/hevc\", ignoreCase = true))"
            )
        )
        assertFalse(
            source.contains(
                "if ((cinemaMasteringEnabled || videoPlan.allIntraMastering) && " +
                    "mime.equals(\"video/hevc\", ignoreCase = true))"
            )
        )
        assertTrue(source.contains("RecordingEncoderCadencePolicy.MIN_REALTIME_GOP_SECONDS"))
        assertTrue(allIntraBlock.contains("AllIntraRealtimeAdmissionPolicy.resolve("))
        assertTrue(source.contains("ENCODER_CADENCE cinemaMaster=true allIntraRequested=false"))
        assertTrue(source.contains("ENCODER_CADENCE_EVIDENCE"))
    }

    @Test
    fun managerBlocksNormalProSdrEightBitFallbackAtPrepare() {
        assertTrue(source.contains("Strict 10-bit video requires real Main10/P010 transport"))
        assertTrue(source.contains("Strict 10-bit Main10 unavailable; refusing 8-bit fallback"))
        assertTrue(source.contains("videoPlan.simulatedTenBitFallback"))
        assertTrue(source.contains("EliteVideoQualityTier.ELITE_NORMAL_SDR"))
        assertTrue(source.contains("EliteVideoQualityTier.ELITE_PRO_SDR_10BIT"))
    }

    @Test
    fun managerBlocksEveryStrictTenBitTierEightBitFallbackAtPrepare() {
        assertTrue(source.contains("eliteQualityTierForPlan.requiresRealTenBitTransport()"))

        val helperBody = source
            .substringAfter("private fun EliteVideoQualityTier?.requiresRealTenBitTransport()")
            .substringBefore("private fun")

        assertTrue(helperBody.contains("EliteVideoQualityTier.ELITE_NORMAL_SDR"))
        assertTrue(helperBody.contains("EliteVideoQualityTier.ELITE_PRO_SDR_10BIT"))
        assertTrue(helperBody.contains("EliteVideoQualityTier.ELITE_HDR_NDR10_PLUS_10BIT"))
        assertTrue(helperBody.contains("EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT"))
        assertTrue(helperBody.contains("EliteVideoQualityTier.ELITE_LOG_N_LOG_10BIT"))
        assertTrue(helperBody.contains("EliteVideoQualityTier.ELITE_LOG_N_LOG_BASE_10BIT"))
        assertTrue(helperBody.contains("EliteVideoQualityTier.ELITE_LOG_N_LOG_SHADOW_10BIT"))
        assertTrue(helperBody.contains("EliteVideoQualityTier.ELITE_LOG_N_LOG_HIGHLIGHT_10BIT"))
        assertTrue(helperBody.contains("EliteVideoQualityTier.ELITE_LOG_N_LOG_CINEMA_10BIT"))
    }

    @Test
    fun managerMergesNeoProfileTruthMetadataIntoPerRecordingCertificate() {
        assertTrue(source.contains("NeoProfileTruthMetadataPolicy.resolve"))
        val certificateBlock = source
            .substringAfter("RecordingQualityEvidenceRequest(")
            .substringBefore("generatedAtEpochMs")

        assertTrue(certificateBlock.contains("detailMetadata ="))
        assertTrue(certificateBlock.contains("truthMetadata.asMap()"))
        assertTrue(certificateBlock.contains("runtimeMetadata"))
        assertTrue(certificateBlock.contains("encoderSurfacePrecisionMetadata"))
    }

    @Test
    fun managerWritesNeoHdrSidecarBeforeCertificateAndUsesActualWriteTruth() {
        val qualityBlock = source
            .substringAfter("private fun writeQualityCertificateForFinalizedRecording(")
            .substringBefore("private fun resolveQualityEvidenceMode(")
        val sidecarWriteIndex = qualityBlock.indexOf("NeoHdrDynamicMetadataSidecarStore.write")
        val sidecarTruthIndex =
            qualityBlock.indexOf("neoDynamicMetadataSidecarWritten = neoHdrSidecarWritten")
        val certificateIndex = qualityBlock.indexOf("RecordingQualityEvidencePolicy.evaluate(")

        assertTrue(qualityBlock.contains("Bt2100TransportPolicy.isNeoBt2100HdrVersion(normalizedHdr)"))
        assertTrue(sidecarWriteIndex >= 0)
        assertTrue(sidecarTruthIndex > sidecarWriteIndex)
        assertTrue(certificateIndex > sidecarTruthIndex)
        assertTrue(qualityBlock.contains("neoDynamicMetadataSidecarWritten = neoHdrSidecarWritten"))
    }

    @Test
    fun audioLoopUsesCumulativeTimelineWithoutSnappingToVideoPts() {
        assertTrue(source.contains("Process.THREAD_PRIORITY_URGENT_AUDIO"))
        assertTrue(source.contains("RecordingAudioSyncPolicy.Timeline("))
        assertTrue(source.contains("audioTimeline.presentationTimeUs("))
        // Commit only the PCM bytes the codec accepted from the timestamp-preserving staging FIFO.
        assertTrue(source.contains("audioTimeline.commitQueuedBytes(stagedWrite.bytes)"))
        assertTrue(source.contains("staging.commitHead(stagedWrite.bytes)"))
        assertFalse(source.contains("videoPtsUs = lastVideoPtsUs"))
        assertFalse(source.contains("rawPtsUs + audioSyncDecision.correctionUs"))
    }

    @Test
    fun processedGpuAudioWarmsBeforeVideoIngressAndTrimsPcmToTheProvenSensorBoundary() {
        val audioSection = source
            .substringAfter("private fun recordAndEncodeAudio(")
            .substringBefore("private enum class AudioPcmSubmitResult")
        val negotiationIndex =
            audioSection.indexOf("awaitAudioTrackPublicationBeforeCaptureWindow(")
        val gateIndex = audioSection.indexOf("val timelineStartNs = awaitAudioTimelineStartNs(")
        val captureStartIndex = audioSection.indexOf("ar.startRecording()")
        val timelineIndex = audioSection.indexOf("recordingStartNs = timelineStartNs")
        val gateHelper = source
            .substringAfter("private fun awaitAudioTimelineStartNs(")
            .substringBefore("private enum class AudioPcmSubmitResult")

        assertTrue(negotiationIndex >= 0)
        assertTrue(gateIndex > negotiationIndex)
        assertTrue(gateIndex >= 0)
        assertTrue(captureStartIndex > negotiationIndex)
        assertTrue(captureStartIndex < gateIndex)
        assertTrue(timelineIndex > gateIndex)
        assertTrue(gateHelper.contains("clipTimingSynchronizedAudioEnabled"))
        assertTrue(gateHelper.contains("clipAudioBoundaryNs"))
        assertTrue(gateHelper.contains("audioCaptureStopRequested.get()"))
        assertTrue(gateHelper.contains("AudioRecord.READ_BLOCKING"))
        assertTrue(gateHelper.contains("discardBeforeCaptureBoundary("))
        assertTrue(source.contains("awaitAudioCaptureReadinessBeforeVideoIngress()"))
        assertTrue(source.contains("sourceTimestampNs = framePresentation?.sensorTimestampNs"))
        assertTrue(source.contains("submittedAtNs = framePresentation?.submittedAtNs"))
        assertTrue(source.contains("clipTimingStartLatch.countDown()"))
    }

    @Test
    fun audioTrackNegotiationDrainsCodecBeforeWaitingForVideoKeyframe() {
        val audioSection = source
            .substringAfter("private fun recordAndEncodeAudio(")
            .substringBefore("private enum class AudioPcmSubmitResult")
        val negotiationHelper = source
            .substringAfter("private fun awaitAudioTrackPublicationBeforeCaptureWindow(")
            .substringBefore("private enum class AudioPcmSubmitResult")

        assertTrue(audioSection.contains("val info = MediaCodec.BufferInfo()"))
        assertTrue(negotiationHelper.contains("drainAudioEncoder(drainSession, codec, info, eos = false)"))
        assertTrue(negotiationHelper.contains("drainSession.audioTrackIndex() < 0"))
        assertTrue(negotiationHelper.contains("System.nanoTime() < audioTrackDeadlineNs"))
        assertTrue(negotiationHelper.contains("AUDIO_TRACK_NEGOTIATION ready=true"))
    }

    @Test
    fun audioCodecOutputHasOneOwnerFromStartupThroughEos() {
        val audioSection = source
            .substringAfter("private fun recordAndEncodeAudio(")
            .substringBefore("// MUXER START")
        val primeBlock = source
            .substringAfter("private fun primeAudioEncoderTrack(codec: MediaCodec)")
            .substringBefore("private fun tryStartMuxer")
        val stopBlock = source
            .substringAfter("private fun stopAudioCapture(")
            .substringBefore("// Public types")

        assertEquals(1, audioSection.windowed("dequeueOutputBuffer(".length)
            .count { it == "dequeueOutputBuffer(" })
        assertFalse(primeBlock.contains("drainAudioEncoder("))
        assertFalse(stopBlock.contains("dequeueOutputBuffer("))
        assertFalse(source.contains("drainRemainingAudioBuffers"))
    }

    @Test
    fun audioCoroutineQueuesMonotonicEosAndRetriesTransientEmptyOutput() {
        val audioSection = source
            .substringAfter("private fun recordAndEncodeAudio(")
            .substringBefore("// MUXER START")

        assertTrue(audioSection.contains("audioTimeline.endPresentationTimeUs()"))
        assertTrue(audioSection.contains("MediaCodec.BUFFER_FLAG_END_OF_STREAM"))
        assertTrue(audioSection.contains("RecordingAudioSyncPolicy.shouldContinueEosDrain("))
        assertTrue(audioSection.contains("eosDrainStartedNs"))
    }

    @Test
    fun audioCaptureRemainsOwnedUntilTheFinalVideoHorizonIsCovered() {
        val stopBlock = source
            .substringAfter("fun stop(): String?")
            .substringBefore("private fun signalVideoEndOfStream")
        val audioSection = source
            .substringAfter("private fun recordAndEncodeAudio(")
            .substringBefore("private enum class AudioPcmSubmitResult")
        val horizonHelper = source
            .substringAfter("private fun awaitTerminalAudioCoverageBeforeCaptureStop()")
            .substringBefore("// MUXER START")

        val videoEosIndex = stopBlock.indexOf("signalVideoEndOfStream()")
        val terminalCoverageIndex = stopBlock.indexOf("awaitTerminalAudioCoverageBeforeCaptureStop()")
        val audioStopIndex = stopBlock.indexOf("stopAudioCapture(logFailure = true)")

        assertTrue(videoEosIndex >= 0)
        assertTrue(terminalCoverageIndex > videoEosIndex)
        assertTrue(audioStopIndex > terminalCoverageIndex)
        assertTrue(audioSection.contains("!audioCaptureStopRequested.get()"))
        assertFalse(audioSection.contains("while (isRecording.get()"))
        assertTrue(horizonHelper.contains("RecordingAudioSyncPolicy.terminalAudioCoverage("))
        assertTrue(horizonHelper.contains("TERMINAL_AUDIO_COVERAGE_TIMEOUT_US"))
        assertTrue(horizonHelper.contains("TERMINAL_AUDIO_COVERAGE"))
    }

    @Test
    fun liveAudioDrainConsumesAllAvailableAacOutputBeforeReturningToCapture() {
        val drainBlock = source
            .substringAfter("private fun drainAudioEncoder(")
            .substringBefore("// MUXER START")

        assertTrue(drainBlock.contains("MediaCodec.INFO_TRY_AGAIN_LATER"))
        assertFalse(drainBlock.contains("if (!eos) done = true"))
        assertFalse(drainBlock.contains("Only drain one buffer at a time during capture"))
    }

    @Test
    fun managerPassesResolutionIntoHardwareSafeBitrateResolver() {
        val resolver = source.substringAfter("return RecordingUserBitratePolicy.resolveRequestedBitrate(")
            .substringBefore("\n        )")

        assertTrue(resolver.contains("width = size.width"))
        assertTrue(resolver.contains("height = size.height"))
    }

    @Test
    fun managerPassesManualSelectionIntentThroughBitrateAndEncoderPlanning() {
        assertTrue(source.contains("var manualBitrateSelectionEnabled = false"))
        assertTrue(source.contains("manualSelectionEnabled = manualBitrateSelectionEnabled"))
        assertTrue(source.contains("manualBitrateSelectionEnabled = manualBitrateSelectionEnabled"))
        assertTrue(source.contains("allowBitrateReduction = !manualBitrateSelectionEnabled"))
        assertTrue(source.contains("advancedQualityTuningEnabled = true"))
        assertTrue(source.contains("cinemaMasteringEnabled -> EliteVideoQualityTier.ELITE_PRO_SDR_10BIT"))
        assertTrue(source.contains("else -> EliteVideoQualityTier.ELITE_NORMAL_SDR"))
        assertFalse(source.contains("!manualBitrateSelectionEnabled && cinemaMode == CinemaVideoMode.PRO"))
    }

    @Test
    fun directGalleryDeliveryOwnsRateControlWithoutDisablingRealtimeCadenceControls() {
        val requestBlock = source
            .substringAfter("var videoFormatRequest = RecordingEncoderFormatRequest(")
            .substringBefore("var videoFormatResult = RecordingEncoderFormatPolicy.buildVideoFormat")

        assertTrue(requestBlock.contains("strictBitrateTarget = videoPlan.directGallerySdrDelivery"))
        assertTrue(source.contains("ENCODER_RATE_CONTROL strict=true"))
        assertTrue(source.contains("codecQuality=${'$'}{videoFormatResult.spec.quality ?: \"UNSET\"}"))
        assertTrue(source.contains("qpMax=${'$'}{videoFormatResult.spec.qualityTuning?.qpMax ?: \"UNSET\"}"))
        assertTrue(source.contains("allowFrameDrop=${'$'}{videoFormatResult.spec.allowFrameDrop}"))
    }

    @Test
    fun managerCanDisablePersistentInputSurfaceForHardwareImportRecording() {
        assertTrue(source.contains("private var persistentInputSurfaceAllowed = true"))
        assertTrue(source.contains("fun setPersistentInputSurfaceAllowed(allowed: Boolean)"))
        val disableBlock = source
            .substringAfter("fun setPersistentInputSurfaceAllowed(allowed: Boolean)")
            .substringBefore("fun releasePersistentInputSurface()")
        assertTrue(disableBlock.contains("persistentInputSurfaceAllowed = allowed"))
        assertFalse(disableBlock.contains("releasePersistentInputSurface()"))
        assertTrue(source.contains("val persistent = if (persistentInputSurfaceAllowed) persistentEncoderSurface else null"))
        assertTrue(source.contains("Video encoder using codec-owned input surface for hardware-import recording"))
    }

    @Test
    fun inputModePreferenceCannotEraseSurfaceOwnershipEvidence() {
        val modeBlock = source
            .substringAfter("fun setVideoInputMode(mode: RecordingVideoInputMode)")
            .substringBefore("fun setRealtimeSurfaceCadenceEnabled(")
        assertFalse(modeBlock.contains("encoderSurface = null"))
        assertFalse(modeBlock.contains("encoderSurfaceUsesPersistentInput = false"))
    }

    @Test
    fun managerDoesNotApplyDisplayFrameRateHintToCodecInputSurface() {
        val frameRateBlock = source
            .substringAfter("private fun configureEncoderSurfaceFrameRate(surface: Surface)")
            .substringBefore("// PREPARE")

        assertTrue(source.contains("ENCODER_SURFACE_FRAME_RATE_HINT_ENABLED = false"))
        assertTrue(frameRateBlock.contains("frame-rate hint skipped for MediaCodec recording surface"))
        assertTrue(frameRateBlock.indexOf("return") < frameRateBlock.indexOf("surface.setFrameRate("))
    }

    @Test
    fun realtimeInternalSurfaceMuxUsesConstantCadenceWhileP010AndOffscreenPreserveRealTimestamps() {
        val cadenceBlock = source
            .substringAfter("private fun shouldUseConstantMuxCadence(): Boolean")
            .substringBefore("private fun requestVideoSyncFrame")

        assertTrue(cadenceBlock.contains("Realtime internal hardware-import Surface input"))
        assertTrue(cadenceBlock.contains("CFR"))
        assertTrue(cadenceBlock.contains("BYTEBUFFER_P010 and offscreen AHB/EGL keep real"))
        assertTrue(cadenceBlock.contains("eglPresentationTime PTS"))
        assertTrue(cadenceBlock.contains("realtimeSurfaceCadenceEnabled && videoInputMode == RecordingVideoInputMode.SURFACE"))
    }

    @Test
    fun strictStopGateUsesValidatedClipTimingWindowWhenAvailable() {
        val stopBlock = source
            .substringAfter("val stopMethodEnteredNs = System.nanoTime()")
            .substringBefore("val byteBufferP010InputActive")
        val strictGateBlock = source
            .substringAfter("private fun resolveStrictStopFrameGateEvidence()")
            .substringBefore("private fun isStrictOffscreenRecordingPath")

        assertTrue(source.contains("fun markClipTimingStartForEvidence("))
        assertTrue(source.contains("sourceTimestampNs: Long? = null"))
        assertTrue(source.contains("submittedAtNs: Long? = null"))
        assertTrue(source.contains("fun markStopRequestedForEvidence()"))
        assertTrue(stopBlock.contains("val stoppedAtNsForEvidence = synchronized(pauseTimelineLock)"))
        assertTrue(stopBlock.contains("stopRequestedNsForEvidence"))
        assertTrue(stopBlock.contains("?: stopMethodEnteredNs"))
        assertTrue(stopBlock.contains("finishPauseWindowIfNeeded(nowNs = stoppedAtNsForEvidence)"))
        assertTrue(stopBlock.contains("finalStrictFrameGateDurationUsForEvidence = clipTimingDurationUsForEvidence("))
        assertTrue(strictGateBlock.contains("finalStrictFrameGateDurationUsForEvidence"))
        assertFalse(strictGateBlock.contains("maxOf(\n            finalActiveRecordingDurationUsForEvidence"))
    }

    @Test
    fun blockModelCodecCallbacksAndQueuesAreOwnedByOneEncoderSession() {
        val installBlock = source
            .substringAfter("private fun installVideoCodecBlockModelCallback(codec: MediaCodec)")
            .substringBefore("private fun resetVideoCodecBlockModelSession()")
        val resetBlock = source
            .substringAfter("private fun resetVideoCodecBlockModelSession()")
            .substringBefore("private fun configureEncoderSurfaceFrameRate")
        val drainBlock = source
            .substringAfter("private fun drainBlockModelVideoEncoder(")
            .substringBefore("// AUDIO")

        assertTrue(source.contains("private class BlockModelCodecSession("))
        assertTrue(source.contains("@Volatile private var activeBlockModelSession"))
        assertTrue(installBlock.contains("activeBlockModelSession === callbackSession"))
        assertTrue(resetBlock.contains("activeBlockModelSession = null"))
        assertTrue(resetBlock.contains("session?.inputIndices?.clear()"))
        assertTrue(resetBlock.contains("session?.videoEvents?.clear()"))
        assertTrue(drainBlock.contains("activeBlockModelSession === blockModelSession"))
        assertTrue(drainBlock.contains("blockModelSession.videoEvents.poll("))
        assertFalse(source.contains("private val blockModelInputIndices"))
        assertFalse(source.contains("private val blockModelVideoEvents"))
    }

    private fun encoderSurfaceMetadata(
        bitDepth: Int,
        precision: String,
        fallback: Boolean = false,
        proven: Boolean = true
    ): Map<String, String> = mapOf(
        "vulkanEncoderSurfaceEvidenceApplicable" to "true",
        "vulkanEncoderSurfacePrecisionProven" to proven.toString(),
        "vulkanEncoderSurfaceBoundAtValidation" to "true",
        "vulkanEncoderSurfaceFormat" to when (precision) {
            "PACKED_10_BIT" -> "A2B10G10R10_UNORM_PACK32"
            "FP16" -> "R16G16B16A16_SFLOAT"
            else -> "B8G8R8A8_UNORM"
        },
        "vulkanEncoderSurfaceBitDepth" to bitDepth.toString(),
        "vulkanEncoderSurfacePrecision" to precision,
        "vulkanEncoderSurfacePrecisionFallback" to fallback.toString()
    )
}
