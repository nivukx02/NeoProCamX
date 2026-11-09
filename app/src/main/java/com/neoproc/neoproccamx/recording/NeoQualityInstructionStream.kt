package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics
import com.neoproc.neoproccamx.rendering.GpuSignalFamily
import java.nio.ByteBuffer

/**
 * Neo Quality Instruction Stream (NQIS) — immutable per-frame conversion decisions.
 *
 * The instruction contains measurements and bounded controls, never replacement/generated pixels. It is
 * produced once from the source frame and may be consumed by the CPU enhancer, Vulkan enhancer, and encoder
 * feeder without repeating analysis or allowing the two paths to choose different quality values.
 */
internal data class NeoQualityInstruction(
    val version: Int,
    val frameIndex: Long,
    val width: Int,
    val height: Int,
    val signalFamily: GpuSignalFamily,
    val detailProfile: VideoDetailProfile,
    val noiseMetrics: VideoNoiseMetrics,
    val sceneDetail: P010SceneDetailMetrics?,
    val saturation: Float,
    val warmth: Float,
    val quality: ConversionFrameQualityPolicy.Plan
) {
    /** Compact diagnostic identity; it is not used to alter pixels or skip a frame. */
    val signature: String
        get() = buildString(96) {
            append("NQIS")
            append(version)
            append(':')
            append(frameIndex)
            append(':')
            append(signalFamily.name)
            append(':')
            append(detailProfile.name)
            append(':')
            append(saturation.toRawBits().toString(16))
            append(':')
            append(warmth.toRawBits().toString(16))
            append(':')
            append(quality.sharpen.toRawBits().toString(16))
            append(':')
            append(quality.denoise.toRawBits().toString(16))
            append(':')
            append(quality.severity.toRawBits().toString(16))
        }
}

/** Single source of truth for content-aware conversion analysis. */
internal object NeoQualityInstructionStream {
    const val VERSION: Int = 2

    fun analyzeP010(
        frameIndex: Long,
        payload: ByteArray,
        previousP010Payload: ByteArray?,
        width: Int,
        height: Int,
        signalFamily: GpuSignalFamily,
        detailProfile: VideoDetailProfile,
        sourceSceneAnalysisEnabled: Boolean = true,
        sharpnessScale: Float = 1f,
        denoiseScale: Float = 1f,
        saturation: Float = 1f,
        warmth: Float = 0f
    ): NeoQualityInstruction {
        require(saturation.isFinite() && saturation in 0.90f..1.10f) {
            "saturation must be finite and in [0.90,1.10]"
        }
        require(warmth.isFinite() && warmth in -0.10f..0.10f) {
            "warmth must be finite and in [-0.10,0.10]"
        }
        val noise = Npv10ExportEnhancementPolicy.measureP010NoiseMetrics(
            payload = payload,
            previousP010Payload = previousP010Payload,
            width = width,
            height = height
        )
        val scene = if (sourceSceneAnalysisEnabled) {
            P010SceneDetailAnalyzer.analyze(payload, width, height)
        } else {
            null
        }
        val quality = ConversionFrameQualityPolicy.resolve(
            family = signalFamily,
            detailProfile = detailProfile,
            metrics = noise,
            sceneDetail = scene,
            sharpnessScale = sharpnessScale,
            denoiseScale = denoiseScale
        )
        return NeoQualityInstruction(
            version = VERSION,
            frameIndex = frameIndex,
            width = width,
            height = height,
            signalFamily = signalFamily,
            detailProfile = detailProfile,
            noiseMetrics = noise,
            sceneDetail = scene,
            saturation = saturation,
            warmth = warmth,
            quality = quality
        )
    }

    fun analyzeP010(
        frameIndex: Long,
        payload: ByteBuffer,
        previousP010Payload: ByteBuffer?,
        width: Int,
        height: Int,
        signalFamily: GpuSignalFamily,
        detailProfile: VideoDetailProfile,
        sourceSceneAnalysisEnabled: Boolean = true,
        sharpnessScale: Float = 1f,
        denoiseScale: Float = 1f,
        saturation: Float = 1f,
        warmth: Float = 0f
    ): NeoQualityInstruction {
        require(saturation.isFinite() && saturation in 0.90f..1.10f) {
            "saturation must be finite and in [0.90,1.10]"
        }
        require(warmth.isFinite() && warmth in -0.10f..0.10f) {
            "warmth must be finite and in [-0.10,0.10]"
        }
        val noise = DirectP010QualityAnalyzer.measureNoise(
            payload = payload,
            previousP010Payload = previousP010Payload,
            width = width,
            height = height
        )
        val scene = if (sourceSceneAnalysisEnabled) {
            P010SceneDetailAnalyzer.analyze(payload, width, height)
        } else {
            null
        }
        val quality = ConversionFrameQualityPolicy.resolve(
            family = signalFamily,
            detailProfile = detailProfile,
            metrics = noise,
            sceneDetail = scene,
            sharpnessScale = sharpnessScale,
            denoiseScale = denoiseScale
        )
        return NeoQualityInstruction(
            version = VERSION,
            frameIndex = frameIndex,
            width = width,
            height = height,
            signalFamily = signalFamily,
            detailProfile = detailProfile,
            noiseMetrics = noise,
            sceneDetail = scene,
            saturation = saturation,
            warmth = warmth,
            quality = quality
        )
    }
}
