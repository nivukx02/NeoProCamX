package com.neoproc.neoproccamx.ui

import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import android.util.Log
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.niv99.Niv99ExecutionBoundaryPolicy
import com.neoproc.neoproccamx.pipeline.CameraCalibrationHandle
import com.neoproc.neoproccamx.pipeline.FramePipelineManager
import com.neoproc.neoproccamx.pipeline.MemoryProfiler
import com.neoproc.neoproccamx.pipeline.PipelineResourceSample
import com.neoproc.neoproccamx.pipeline.PipelineStage
import com.neoproc.neoproccamx.pipeline.ProcessCpuUtilizationSampler
import com.neoproc.neoproccamx.pipeline.RawVideoManager
import com.neoproc.neoproccamx.pipeline.RuntimeMemoryPressureSignalPolicy
import com.neoproc.neoproccamx.recording.RecordingManager
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.IDLE_RESOURCE_TELEMETRY_REFRESH_INTERVAL_MS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.RECORDING_RESOURCE_TELEMETRY_REFRESH_INTERVAL_MS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.THERMAL_ISOLATED_RECORDING_TELEMETRY_REFRESH_INTERVAL_MS
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Keeps sensor-pipeline wiring and evidence out of the UI coordinator. */
internal object SensorPipelineIntegration {
    fun bindRecordingStages(
        recording: RecordingManager,
        pipeline: FramePipelineManager
    ) {
        recording.encoderStageEventListener = { event ->
            pipeline.onRecordingStageEvent(
                sensorTimestampNs = event.sensorTimestampNs,
                stage = PipelineStage.ENCODER,
                queueEnteredAtNs = event.queueEnteredAtNs,
                startedAtNs = event.startedAtNs,
                endedAtNs = event.endedAtNs,
                queueDepthAtStart = event.queueDepthAtStart,
                currentQueueDepth = event.currentQueueDepth,
                success = event.success
            )
        }
        recording.storageStageEventListener = { event ->
            pipeline.onRecordingStageEvent(
                sensorTimestampNs = event.sensorTimestampNs,
                stage = PipelineStage.STORAGE,
                queueEnteredAtNs = event.queueEnteredAtNs,
                startedAtNs = event.startedAtNs,
                endedAtNs = event.endedAtNs,
                queueDepthAtStart = event.queueDepthAtStart,
                currentQueueDepth = event.currentQueueDepth,
                success = event.success
            )
        }
    }

    fun unbindRecordingStages(recording: RecordingManager) {
        recording.encoderStageEventListener = null
        recording.storageStageEventListener = null
    }

    fun beginSession(
        generation: Long,
        camera: CameraController,
        pipeline: FramePipelineManager
    ) {
        val characteristics = camera.getCameraCharacteristics()
        val calibration = characteristics?.let { values ->
            CameraCalibrationHandle(
                cameraId = camera.currentCameraId,
                revision = generation,
                intrinsicCalibration = values
                    .get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                    ?.toList()
                    .orEmpty(),
                lensDistortion = values
                    .get(CameraCharacteristics.LENS_DISTORTION)
                    ?.toList()
                    .orEmpty(),
                poseRotation = values
                    .get(CameraCharacteristics.LENS_POSE_ROTATION)
                    ?.toList()
                    .orEmpty(),
                poseTranslation = values
                    .get(CameraCharacteristics.LENS_POSE_TRANSLATION)
                    ?.toList()
                    .orEmpty()
            )
        }
        pipeline.beginCameraSession(
            sessionGeneration = generation,
            sensorTimestampSource = characteristics?.get(
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE
            ),
            measuredCameraToImuOffsetNs = camera.measuredCameraToImuClockOffsetNsOrNull(),
            calibration = calibration
        )
    }

    fun runtimeEvidence(pipeline: FramePipelineManager): Map<String, String> {
        val diagnostics = pipeline.sensorPipelineDiagnostics()
        val niv99 = pipeline.niv99LiveCaptureSnapshot()
        val niv99Timeline = niv99.timeline
        val encoder = diagnostics.stageTiming[PipelineStage.ENCODER]
        val storage = diagnostics.stageTiming[PipelineStage.STORAGE]
        return linkedMapOf(
            "sensorPipelineTargetFps" to diagnostics.targetFps.toString(),
            "sensorPipelineCaptureFps" to diagnostics.captureFps.toString(),
            "sensorPipelineRecordingFps" to diagnostics.recordingFps.toString(),
            "sensorPipelinePreviewFps" to diagnostics.previewFps.toString(),
            "sensorPipelineDisplayFps" to diagnostics.displayFps.toString(),
            "sensorPipelineCapturedFrames" to diagnostics.capturedFrames.toString(),
            "sensorPipelineRecordedFrames" to diagnostics.recordedFrames.toString(),
            "sensorPipelineDroppedCaptureFrames" to diagnostics.droppedCaptureFrames.toString(),
            "sensorPipelineDroppedRecordingFrames" to diagnostics.droppedRecordingFrames.toString(),
            "sensorPipelineExplicitDroppedRecordingFrames" to
                diagnostics.explicitDroppedRecordingFrames.toString(),
            "sensorPipelineInferredRecordingTimestampGapFrames" to
                diagnostics.inferredRecordingTimestampGapFrames.toString(),
            "sensorPipelineUnrecoveredRecordingTimestampGapFrames" to
                diagnostics.unrecoveredRecordingTimestampGapFrames.toString(),
            "sensorPipelineRecordingTimestampGapEvents" to
                diagnostics.recordingTimestampGapEvents.toString(),
            "sensorPipelineRecordingMaximumTimestampGapNs" to
                diagnostics.recordingMaximumTimestampGapNs.toString(),
            "sensorPipelineRecordingFirstSensorTimestampNs" to
                diagnostics.recordingFirstSensorTimestampNs.toString(),
            "sensorPipelineRecordingLastSensorTimestampNs" to
                diagnostics.recordingLastSensorTimestampNs.toString(),
            "sensorPipelineRecordingSourceFps" to diagnostics.recordingSourceFps.toString(),
            "sensorPipelineFirstExplicitRecordingDropReason" to
                diagnostics.firstExplicitRecordingDropReason,
            "sensorPipelineFirstRecordingGapPreviousTimestampNs" to
                diagnostics.firstRecordingGapPreviousTimestampNs.toString(),
            "sensorPipelineFirstRecordingGapTimestampNs" to
                diagnostics.firstRecordingGapTimestampNs.toString(),
            "sensorPipelineSkippedPreviewFrames" to diagnostics.skippedPreviewFrames.toString(),
            "sensorPipelineStalePreviewFrames" to diagnostics.stalePreviewFrames.toString(),
            "sensorPipelineIdentityConflicts" to diagnostics.identityConflicts.toString(),
            "niv99ConfiguredCameraId" to niv99.configuredCameraId,
            "niv99ConfiguredGeneration" to niv99.configuredGeneration.toString(),
            "niv99StaticMetadataStatus" to niv99.staticMetadataStatus.name,
            "niv99CaptureResultsSeen" to niv99.captureResultsSeen.toString(),
            "niv99CaptureResultsPublished" to niv99.captureResultsPublished.toString(),
            "niv99CaptureResultsRejected" to niv99.captureResultsRejected.toString(),
            "niv99ImageMatches" to niv99.imageMatches.toString(),
            "niv99ImageMisses" to niv99.imageMisses.toString(),
            "niv99TuningAccepted" to niv99.tuningAccepted.toString(),
            "niv99TuningRejected" to niv99.tuningRejected.toString(),
            "niv99TuningWaits" to niv99.tuningWaits.toString(),
            "niv99TuningWaitTimeouts" to niv99.tuningWaitTimeouts.toString(),
            "niv99TuningWaitTotalNs" to niv99.tuningWaitTotalNs.toString(),
            "niv99TuningWaitMaxNs" to niv99.tuningWaitMaxNs.toString(),
            "niv99TimestampAlignedImageMatches" to niv99.timestampAlignedImageMatches.toString(),
            "niv99TimestampAlignmentMaxNs" to niv99.timestampAlignmentMaxNs.toString(),
            "niv99TuningWorkQueued" to niv99.tuningWorkQueued.toString(),
            "niv99TuningWorkCompleted" to niv99.tuningWorkCompleted.toString(),
            "niv99TuningWorkRejected" to niv99.tuningWorkRejected.toString(),
            "niv99TuningWorkStale" to niv99.tuningWorkStale.toString(),
            "niv99TuningWorkFailed" to niv99.tuningWorkFailed.toString(),
            "niv99TuningWorkRetired" to niv99.tuningWorkRetired.toString(),
            "niv99TuningWorkMaximumQueueDepth" to
                niv99.tuningWorkMaximumQueueDepth.toString(),
            "niv99TuningWorkTotalNs" to niv99.tuningWorkTotalNs.toString(),
            "niv99TuningWorkMaximumNs" to niv99.tuningWorkMaximumNs.toString(),
            "niv99PreviewFallbackAttempts" to niv99.previewFallbackAttempts.toString(),
            "niv99PreviewFallbackSelections" to niv99.previewFallbackSelections.toString(),
            "niv99PreviewFallbackPresentations" to
                niv99.previewFallbackPresentations.toString(),
            "niv99PreviewFallbackRejections" to niv99.previewFallbackRejections.toString(),
            "niv99PreviewFallbackMaximumAgeNs" to
                niv99.previewFallbackMaximumAgeNs.toString(),
            "niv99PixelAppliedFrames" to niv99.pixelAppliedFrames.toString(),
            "niv99PreviewPixelAppliedFrames" to niv99.previewPixelAppliedFrames.toString(),
            "niv99RecordingPixelAppliedFrames" to niv99.recordingPixelAppliedFrames.toString(),
            "niv99PixelRenderRejectedFrames" to niv99.pixelRenderRejectedFrames.toString(),
            "niv99PreviewPixelRenderRejectedFrames" to
                niv99.previewPixelRenderRejectedFrames.toString(),
            "niv99RecordingPixelRenderRejectedFrames" to
                niv99.recordingPixelRenderRejectedFrames.toString(),
            "niv99AnalysisFramesSeen" to niv99.analysisFramesSeen.toString(),
            "niv99AnalysisEstimatesPublished" to niv99.analysisEstimatesPublished.toString(),
            "niv99AnalysisEstimatesRejected" to niv99.analysisEstimatesRejected.toString(),
            "niv99ColorEstimateTunings" to niv99.colorEstimateTunings.toString(),
            "niv99NonIdentityWhiteBalanceFrames" to
                niv99.nonIdentityWhiteBalanceFrames.toString(),
            "niv99DenoiseEffectFrames" to niv99.denoiseEffectFrames.toString(),
            "niv99ReconstructionEffectFrames" to niv99.reconstructionEffectFrames.toString(),
            "niv99LastWhiteBalanceEffectMagnitude" to
                niv99.lastWhiteBalanceEffectMagnitude.toString(),
            "niv99LastDenoiseEffectMagnitude" to niv99.lastDenoiseEffectMagnitude.toString(),
            "niv99LastReconstructionEffectMagnitude" to
                niv99.lastReconstructionEffectMagnitude.toString(),
            "niv99LastColorEstimateFrameNumber" to
                niv99.lastColorEstimateFrameNumber.toString(),
            "niv99LastColorEstimateTimestampNs" to
                niv99.lastColorEstimateTimestampNs.toString(),
            "niv99ProcessingStatus" to niv99.processingStatus,
            "niv99MetadataOnly" to niv99.metadataOnly.toString(),
            "niv99TimelineCameraId" to niv99Timeline.cameraId,
            "niv99TimelineGeneration" to niv99Timeline.sessionGeneration.toString(),
            "niv99TimelineCaptureResultsSeen" to niv99Timeline.captureResultsSeen.toString(),
            "niv99TimelineCaptureResultsPublished" to
                niv99Timeline.captureResultsPublished.toString(),
            "niv99TimelineCaptureResultsRejected" to
                niv99Timeline.captureResultsRejected.toString(),
            "niv99TimelineImageMatches" to niv99Timeline.imageMatches.toString(),
            "niv99TimelineImageMisses" to niv99Timeline.imageMisses.toString(),
            "niv99TimelineTuningAccepted" to niv99Timeline.tuningAccepted.toString(),
            "niv99TimelineTuningRejected" to niv99Timeline.tuningRejected.toString(),
            "niv99TimelinePixelEligibleTunings" to
                niv99Timeline.pixelEligibleTunings.toString(),
            "niv99TimelinePixelAppliedFrames" to niv99Timeline.pixelAppliedFrames.toString(),
            "niv99TimelinePreviewPixelAppliedFrames" to
                niv99Timeline.previewPixelAppliedFrames.toString(),
            "niv99TimelineRecordingPixelAppliedFrames" to
                niv99Timeline.recordingPixelAppliedFrames.toString(),
            "niv99TimelinePixelRenderRejectedFrames" to
                niv99Timeline.pixelRenderRejectedFrames.toString(),
            "niv99TimelinePreviewPixelRenderRejectedFrames" to
                niv99Timeline.previewPixelRenderRejectedFrames.toString(),
            "niv99TimelineRecordingPixelRenderRejectedFrames" to
                niv99Timeline.recordingPixelRenderRejectedFrames.toString(),
            "niv99TimelineLatestPublishedFrameNumber" to
                niv99Timeline.latestPublishedFrameNumber.toString(),
            "niv99TimelineLatestPublishedSensorTimestampNs" to
                niv99Timeline.latestPublishedSensorTimestampNs.toString(),
            "niv99TimelineResetCount" to niv99Timeline.resetCount.toString(),
            "sensorPipelineEncoderSamples" to (encoder?.samples ?: 0L).toString(),
            "sensorPipelineEncoderAverageProcessingNs" to
                (encoder?.averageProcessingNs ?: 0L).toString(),
            "sensorPipelineEncoderMaximumProcessingNs" to
                (encoder?.maximumProcessingNs ?: 0L).toString(),
            "sensorPipelineEncoderCurrentQueueDepth" to
                (encoder?.currentQueueDepth ?: 0).toString(),
            "sensorPipelineEncoderMaximumQueueDepth" to
                (encoder?.maximumQueueDepth ?: 0).toString(),
            "sensorPipelineEncoderMissedDeadlines" to
                (encoder?.missedDeadlines ?: 0L).toString(),
            "sensorPipelineStorageSamples" to (storage?.samples ?: 0L).toString(),
            "sensorPipelineStorageAverageProcessingNs" to
                (storage?.averageProcessingNs ?: 0L).toString(),
            "sensorPipelineStorageMaximumProcessingNs" to
                (storage?.maximumProcessingNs ?: 0L).toString(),
            "sensorPipelineStorageAverageQueueWaitNs" to
                (storage?.averageQueueWaitNs ?: 0L).toString(),
            "sensorPipelineStorageMaximumQueueWaitNs" to
                (storage?.maximumQueueWaitNs ?: 0L).toString(),
            "sensorPipelineStorageCurrentQueueDepth" to
                (storage?.currentQueueDepth ?: 0).toString(),
            "sensorPipelineStorageMaximumQueueDepth" to
                (storage?.maximumQueueDepth ?: 0).toString(),
            "sensorPipelineStorageMissedDeadlines" to
                (storage?.missedDeadlines ?: 0L).toString(),
            "sensorPipelineStorageQueuePressure" to
                diagnostics.resources?.storageQueuePressure?.toString().orEmpty(),
            "sensorPipelineStorageWriteMegabytesPerSecond" to
                diagnostics.resources?.storageWriteMegabytesPerSecond?.toString().orEmpty()
        ) + pipeline.sensorTimestampTimebaseMetadata() + Niv99ExecutionBoundaryPolicy.evidence()
    }
}

internal data class SensorPipelineResourceRefresh(
    val memory: MemoryProfiler.MemoryStats?,
    val availableStorageBytes: Long,
    val thermalLabel: String,
    val capturedAtElapsedMs: Long,
    val simplifyPreviewEffects: Boolean
)

/** Samples blocking process/memory/storage signals exclusively on the IO dispatcher. */
internal class SensorPipelineRuntimeResourceCoordinator(
    private val scope: CoroutineScope,
    private val memoryProfiler: MemoryProfiler,
    private val pipeline: FramePipelineManager,
    private val recording: RecordingManager,
    private val rawVideo: RawVideoManager,
    private val recordingActive: () -> Boolean,
    private val suppressNonessentialTelemetry: () -> Boolean = { false },
    private val availableStorageBytes: () -> Long,
    private val thermalLabel: () -> String,
    private val onRefresh: suspend (SensorPipelineResourceRefresh) -> Unit
) {
    private val cpuSampler = ProcessCpuUtilizationSampler()
    private val refreshInFlight = AtomicBoolean(false)
    @Volatile private var latestRefreshElapsedMs = 0L

    fun request(nowMs: Long = SystemClock.elapsedRealtime(), force: Boolean = false) {
        val thermalIsolationActive = recordingActive() && suppressNonessentialTelemetry()
        val intervalMs = when {
            thermalIsolationActive -> THERMAL_ISOLATED_RECORDING_TELEMETRY_REFRESH_INTERVAL_MS
            recordingActive() -> RECORDING_RESOURCE_TELEMETRY_REFRESH_INTERVAL_MS
            else -> IDLE_RESOURCE_TELEMETRY_REFRESH_INTERVAL_MS
        }
        if (!force && nowMs - latestRefreshElapsedMs < intervalMs) return
        if (!refreshInFlight.compareAndSet(false, true)) return

        scope.launch(Dispatchers.IO) {
            try {
                val memory = if (thermalIsolationActive) {
                    null
                } else {
                    runCatching { memoryProfiler.captureSnapshot() }
                        .onFailure { Log.w(TAG, "Background memory telemetry refresh failed", it) }
                        .getOrNull()
                }
                val storageBytes = availableStorageBytes()
                val thermal = thermalLabel()
                val capturedAtMs = SystemClock.elapsedRealtime()
                val capturedAtNs = SystemClock.elapsedRealtimeNanos()
                val storageMegabytesPerSecond = listOfNotNull(
                    recording.measuredStorageWriteMegabytesPerSecond(capturedAtNs),
                    rawVideo.measuredStorageWriteMegabytesPerSecond(capturedAtNs)
                ).maxOrNull()
                val adaptation = pipeline.updatePipelineResources(
                    PipelineResourceSample(
                        measuredAtNs = capturedAtNs,
                        cpuUtilization = if (thermalIsolationActive) null else cpuSampler.sample(
                            elapsedRealtimeMs = capturedAtMs,
                            processCpuTimeMs = android.os.Process.getElapsedCpuTime()
                        ),
                        memoryUsedBytes = memory?.let(RuntimeMemoryPressureSignalPolicy::usedBytes),
                        memoryPressure = memory?.let(RuntimeMemoryPressureSignalPolicy::pressure),
                        storageWriteMegabytesPerSecond = storageMegabytesPerSecond,
                        storageQueuePressure = recording.muxerQueuePressure()
                    )
                )
                latestRefreshElapsedMs = capturedAtMs
                onRefresh(
                    SensorPipelineResourceRefresh(
                        memory = memory,
                        availableStorageBytes = storageBytes,
                        thermalLabel = thermal,
                        capturedAtElapsedMs = capturedAtMs,
                        simplifyPreviewEffects = adaptation.simplifyPreviewEffects
                    )
                )
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    private companion object {
        const val TAG = "SensorPipelineResources"
    }
}
