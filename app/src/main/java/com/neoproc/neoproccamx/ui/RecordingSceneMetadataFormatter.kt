package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.pipeline.CaptureRecommendation
import com.neoproc.neoproccamx.pipeline.SceneAnalysis
import com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics
import com.neoproc.neoproccamx.recording.HdrImagingMath
import com.neoproc.neoproccamx.recording.ManualVideoColorSelectionPlan
import com.neoproc.neoproccamx.recording.ResolvedRecordingSelection
import java.util.Locale

/** Deterministic formatter for the immutable recording-start evidence snapshot. */
internal object RecordingSceneMetadataFormatter {
    internal data class Input(
        val encoderSize: Size,
        val selection: ResolvedRecordingSelection?,
        val modeLabel: String,
        val cameraLevel: String,
        val highEndHardwareSupported: Boolean,
        val sourceResolutionPlan: RecordingSourceResolutionPlan,
        val rawCompression: String,
        val colorProfile: String,
        val manualColorPlan: ManualVideoColorSelectionPlan,
        val parityPlan: RecordingPipelineParityPlan,
        val processingReport: PixelProcessingRealityReport,
        val runtimeEvidence: Map<String, String>,
        val debugValidationLabel: String?,
        val logEnabled: Boolean,
        val simulatedTenBitFallbackActive: Boolean,
        val faceDetectionEnabled: Boolean,
        val thermalLabel: String,
        val thermalThrottled: Boolean,
        val dropEstimate: Int,
        val motionRisk: Float,
        val noiseRisk: Float,
        val noiseMetrics: VideoNoiseMetrics,
        val noiseReductionSignature: String,
        val detailMetadata: Map<String, String>,
        val scene: SceneAnalysis?,
        val recommendation: CaptureRecommendation?
    )

    fun format(input: Input): String = buildString {
        append("mode=")
        append(input.modeLabel)
        append(";cameraLevel=")
        append(input.cameraLevel)
        append(";highEnd=")
        append(input.highEndHardwareSupported)
        append(";fullSensorMP=")
        append(String.format(Locale.US, "%.1f", input.sourceResolutionPlan.fullSensorMegapixels))
        append(";cineSourceMP=")
        append(String.format(Locale.US, "%.1f", input.sourceResolutionPlan.cineSourceMegapixels))
        append(";readoutMP=")
        append(String.format(Locale.US, "%.1f", input.sourceResolutionPlan.preferredReadoutMegapixels))
        append(";fullReadoutForced=")
        append(input.sourceResolutionPlan.fullSensorReadoutForced)
        append(";rawSource=full_sensor")
        append(";size=")
        append("${input.encoderSize.width}x${input.encoderSize.height}")
        input.selection?.let {
            append(";requestedSize=")
            append("${it.requestedSize.width}x${it.requestedSize.height}")
            append(";sourceSize=")
            append("${it.sourceSize.width}x${it.sourceSize.height}")
            append(";cameraExact=")
            append(it.cameraExactMatch)
            append(";encoderExact=")
            append(it.encoderExactMatch)
            append(";selectionBackend=")
            append(it.backend)
            append(";requiresMediaEncoder=")
            append(it.requiresMediaEncoder)
            append(";upscaledToRequested=")
            append(it.upscaledToRequested)
        }
        append(";rawCompression=")
        append(input.rawCompression)
        append(";profile=")
        append(input.colorProfile)
        append(";selectedColorSpace=")
        append(input.manualColorPlan.selectedColorSpace)
        append(";selectedColorScience=")
        append(input.manualColorPlan.selectedColorScience)
        append(";colorSelectionSource=")
        append(input.manualColorPlan.colorSelectionSource)
        append(";manualColorSelectionReady=")
        append(input.manualColorPlan.readyForRecording)
        append(";previewRecordingParityStatus=")
        append(input.parityPlan.parityCertificate.status.name)
        append(";previewRecordingParityBlocked=")
        append(input.parityPlan.parityCertificate.recordingBlocked)
        append(";strictVulkanParityCertified=")
        append(input.parityPlan.parityCertificate.strictVulkanParityCertified)
        append(";previewPipelineSignature=")
        append(input.parityPlan.sharedDecision.previewSignature)
        append(";recordingPipelineSignature=")
        append(input.parityPlan.sharedDecision.recordingSignature)
        append(";previewQualitySignature=")
        append(input.parityPlan.sharedDecision.previewQualitySignature)
        append(";recordingQualitySignature=")
        append(input.parityPlan.sharedDecision.recordingQualitySignature)
        append(";parityCertificateSignature=")
        append(input.parityPlan.parityCertificate.certificateSignature)
        append(";parityFeedPath=")
        append(input.parityPlan.feedPath.name)
        append(";camera2DirectFallback=")
        append(input.parityPlan.parityCertificate.camera2DirectFallback)
        append(";pixelProcessingSignature=")
        append(input.processingReport.reportSignature)
        append(";recordingUsesCustomGpu=")
        append(input.processingReport.recordingUsesCustomGpu)
        append(";customProcessingBypassed=")
        append(input.processingReport.camera2DirectBypassesCustomProcessing)
        append(";realProcessingStages=")
        append(input.processingReport.realStageNames.joinToString(separator = ","))
        append(";plannedOnlyProcessingStages=")
        append(input.processingReport.plannedOnlyStageNames.joinToString(separator = ","))
        append(";unsupportedProcessingStages=")
        append(input.processingReport.unsupportedStageNames.joinToString(separator = ","))
        appendMetadataEntries(input.runtimeEvidence)
        input.debugValidationLabel?.let {
            append(";validation=")
            append(it)
        }
        append(";log=")
        append(input.logEnabled)
        append(";simulated10bit=")
        append(input.simulatedTenBitFallbackActive)
        append(";faceDetect=")
        append(input.faceDetectionEnabled)
        append(";thermalLabel=")
        append(input.thermalLabel)
        append(";thermalThrottled=")
        append(input.thermalThrottled)
        append(";dropEstimate=")
        append(input.dropEstimate)
        append(";v20CameraSceneRiskVersion=V20")
        append(";v20MotionRisk=")
        append(String.format(Locale.US, "%.3f", input.motionRisk))
        append(";v20NoiseRisk=")
        append(String.format(Locale.US, "%.3f", input.noiseRisk))
        append(";v20AntiMotionBlurEnabled=")
        append(input.motionRisk >= 0.58f)
        appendMetadataEntries(input.noiseMetrics.toMetadata())
        append(";noiseReductionSignature=")
        append(input.noiseReductionSignature)
        appendMetadataEntries(input.detailMetadata)
        input.scene?.let {
            append(";avgLuma=")
            append(String.format(Locale.US, "%.3f", it.averageLuma))
            append(";lux=")
            append(String.format(Locale.US, "%.1f", it.estimatedLux))
            append(";motion=")
            append(String.format(Locale.US, "%.3f", it.motionScore))
            append(";edge=")
            append(String.format(Locale.US, "%.3f", it.edgeDensity))
            append(";highlight=")
            append(String.format(Locale.US, "%.3f", it.highlightRatio))
            append(";dynamicRange=")
            append(String.format(Locale.US, "%.3f", it.dynamicRangeScore))
            val hdr10Meta = HdrImagingMath.estimateHdr10StaticMetadata(
                averageLuma = it.averageLuma.toDouble(),
                highlightRatio = it.highlightRatio.toDouble(),
                dynamicRangeScore = it.dynamicRangeScore.toDouble()
            )
            append(";hdrMaxCLL=")
            append(String.format(Locale.US, "%.0f", hdr10Meta.maxCllNits))
            append(";hdrMaxFALL=")
            append(String.format(Locale.US, "%.0f", hdr10Meta.maxFallNits))
            append(";hdrMasterPeak=")
            append(String.format(Locale.US, "%.0f", hdr10Meta.masteringPeakNits))
        }
        input.recommendation?.let {
            append(";strategy=")
            append(
                when {
                    it.shouldUseHdrFusion -> "HDR_FUSION"
                    it.shouldUseFrameStacking -> "FRAME_STACK"
                    else -> "SINGLE"
                }
            )
            append(";isoCeiling=")
            append(it.isoCeiling)
            append(";exposureNs=")
            append(it.targetExposureNs)
            append(";stack=")
            append(it.stackFrameCount)
            append(";tnr=")
            append(it.temporalNoiseReduction)
        }
    }

    private fun StringBuilder.appendMetadataEntries(entries: Map<String, String>) {
        entries.forEach { (key, value) ->
            append(";")
            append(key)
            append("=")
            append(value)
        }
    }

}
