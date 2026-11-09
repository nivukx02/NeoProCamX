package com.neoproc.neoproccamx.recording

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingBitrateEnforcementContractTest {
    private val source = locateRecordingManagerSource().readText()

    @Test
    fun resolveSupportedBitrateAcceptsTrailingFpsAndUsesItForEveryFloor() {
        val body = source
            .substringAfter("fun resolveSupportedBitrate(")
            .substringBefore("\n    fun setCameraSupportedRecordingSizes")

        assertTrue(body.contains("videoDetailProfile: VideoDetailProfile? = this.videoDetailProfile,"))
        assertTrue(body.contains("fps: Int = videoFps"))
        assertTrue(body.contains("RecordingVideoQualityBudgetRequest("))
        assertTrue(body.contains("fps = fps"))
        assertTrue(body.contains("minimumBitrateFloorFor(size.width, size.height, fps = fps)"))
        assertTrue(body.contains("RecordingUserBitratePolicy.resolveRequestedBitrate("))
    }

    @Test
    fun runtimeBitrateUpdateOnlyPublishesAfterMediaCodecAcceptsParameters() {
        val body = source
            .substringAfter("fun updateActiveEncoderBitrate(requestedBitrate: Int): Boolean")
            .substringBefore("\n    fun ")

        assertTrue(source.contains("@Synchronized\n    fun updateActiveEncoderBitrate"))
        assertTrue(body.contains("val codec = videoCodecObj ?: return false"))
        assertTrue(body.contains("requestedOutputBitrateForEvidence = requestedBitrate"))
        assertTrue(body.contains("MediaCodec.PARAMETER_KEY_VIDEO_BITRATE"))
        assertTrue(body.contains("codec.setParameters"))
        assertTrue(body.indexOf("activeOutputBitrate =") > body.indexOf("codec.setParameters"))
        assertTrue(body.contains("return false"))
    }

    @Test
    fun recordingEvidenceKeepsRequestedPolicyAppliedAndMeasuredRatesSeparate() {
        val finalizationBody = source
            .substringAfter("private fun writeQualityCertificateForFinalizedRecording(")
            .substringBefore("private fun resolveQualityEvidenceMode(")
        val requestBody = finalizationBody
            .substringAfter("RecordingQualityEvidenceRequest(")
            .substringBefore("generatedAtEpochMs")
        val normalizedFinalizationBody = finalizationBody.replace(Regex("\\s+"), "")

        assertTrue(
            normalizedFinalizationBody.contains(
                "valmeasuredSessionOutputBitrate=" +
                    "encodedOutputBitrateMeter.measuredSessionBitrateBitsPerSecond()"
            )
        )
        assertTrue(requestBody.contains("requestedBitrateBps = requestedOutputBitrateForEvidence"))
        assertTrue(requestBody.contains("policyTargetBitrateBps = policyTargetBitrateForEvidence"))
        assertTrue(requestBody.contains("appliedBitrateBps = activeOutputBitrate"))
        assertTrue(requestBody.contains("measuredOutputBitrateBps = measuredSessionOutputBitrate"))
        assertTrue(requestBody.contains("bitrateBps = measuredSessionOutputBitrate"))
        assertTrue(
            source.replace(Regex("\\s+"), "").contains(
                "funmeasuredOutputBitrateBitsPerSecond():Int=" +
                    "encodedOutputBitrateMeter.measuredBitrateBitsPerSecond()"
            )
        )
    }

    @Test
    fun allIntraClampUsesTheSelectedEncoderCapabilityRange() {
        val body = source
            .substringAfter("val probedAllIntra = AllIntraHevcPolicy.probe(")
            .substringBefore("val applied = AllIntraHevcPolicy.applyToFormat")

        assertTrue(body.contains("codecInfo = selectedVideoEncoder?.candidate?.codecInfo"))
        assertTrue(body.contains("clampBitrateToSelectedEncoderCapabilities("))
    }

    private fun locateRecordingManagerSource(): File {
        val candidates = listOf(
            "app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt",
            "src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt"
        )
        return candidates
            .map { File(it).absoluteFile }
            .firstOrNull { it.exists() }
            ?: error("Could not locate RecordingManager.kt")
    }
}
