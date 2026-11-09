package com.neoproc.neoproccamx.ui

import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import com.neoproc.neoproccamx.ai.SceneType
import com.neoproc.neoproccamx.ai.SubjectMask
import com.neoproc.neoproccamx.apex.ApexPipelineSnapshot
import com.neoproc.neoproccamx.camera.FrameGeometry
import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.camera.SensorNoiseModel
import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.pipeline.SceneRenderProfile
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.recording.VideoCodecType
import com.neoproc.neoproccamx.snce.SnceLiveColorSnapshot

/** One atomic LUT identity. Data upload and the render contract use the same generation-bound value. */
internal data class NativePreviewLutSnapshot(
    val enabled: Boolean = false,
    val size: Int = 0,
    val contentHash: Int = 0,
    val renderProfileGeneration: Long = 1L
) {
    init {
        require(size >= 0) { "LUT size cannot be negative" }
        require(enabled == (size > 1)) { "Enabled LUTs require a valid cube size" }
        require(enabled || contentHash == 0) { "Disabled LUTs cannot retain content identity" }
    }
}

/**
 * Immutable owner of every mutable value consumed while one Vulkan preview/encoder packet is built.
 * Deferred work may resolve policies from this object, but must never re-read live ViewModel state.
 */
internal data class VulkanRenderRequestSnapshot(
    val authority: VulkanRenderProfileAuthorityCoordinator.Snapshot,
    /** Capture-order identity used only to coalesce requests; packet revisions remain payload-stable. */
    val requestSequence: Long,
    val geometry: FrameGeometry?,
    val videoSurface: Boolean,
    val deliveryKey: ActiveDeliveryContractCacheKey,
    val cameraCharacteristics: CameraCharacteristics?,
    val nativePreviewLut: NativePreviewLutSnapshot,
    val colorProfile: String,
    val lutEnabled: Boolean,
    val lutProfile: String,
    val sdrQualityIntent: SdrQualityIntent,
    val highLoadPreviewActive: Boolean,
    val previewFpsHint: Float,
    val cameraPreviewHdrTransportMode: HdrDynamicRangeMode,
    val cinemaDebayerActive: Boolean,
    val sceneRenderProfile: SceneRenderProfile?,
    val sceneType: SceneType,
    val aiEnhancementEnabled: Boolean,
    val aiMeasuredPerceptualSharpness: Float,
    val aiMeasuredNoiseLevel: Float,
    val aiMotionCompensationEnabled: Boolean,
    val videoDetailProfile: VideoDetailProfile,
    val recordingState: GpuRecordingState,
    val recordingActive: Boolean,
    val simulatedTenBitFallback: Boolean,
    val requestedCodec: VideoCodecType,
    val av1Supported: Boolean,
    val outputRenderSize: Size,
    val sourceRenderSize: Size,
    val sensorNoiseModel: SensorNoiseModel,
    val sensorIso: Int,
    val exposureCompensation: Int,
    val subjectDetailMask: SubjectMask?,
    val snce: SnceLiveColorSnapshot,
    val apex: ApexPipelineSnapshot,
    val previousColorGain: FloatArray,
    val previewTransform: FloatArray,
    val encoderTransform: FloatArray
) {
    init {
        require(previousColorGain.size == 4) { "Color gain state must contain four floats" }
        require(previewTransform.size == 16) { "Preview transform must contain sixteen floats" }
        require(encoderTransform.size == 16) { "Encoder transform must contain sixteen floats" }
    }
}

internal data class PreviewColorGainCandidate(
    val uniforms: FloatArray,
    val committedState: FloatArray
)
