package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.pipeline.SceneRenderProfile
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import java.util.Locale
import kotlin.math.max

internal data class V20ZeroGrainClarityRequest(
    val signalFamily: GpuSignalFamily,
    val strictHighEnd: Boolean,
    val normalSdrRealtimeLock: Boolean,
    val profile: SceneRenderProfile,
    val noiseMetric: Float,
    val iso: Float,
    val localChromaSpikeSmoothing: Float,
    val sharpeningFloor: Float,
    val sharpeningCeiling: Float,
    val skinDetailProtection: Float,
    val videoDetailProfile: VideoDetailProfile?,
    val subjectMaskAvailable: Boolean,
    val temporalBlend: Float,
    val spatialDenoise: Float,
    val chromaDenoise: Float,
    val sharpening: Float,
    val subjectDetailGain: Float
)

internal data class V20ZeroGrainClarityPlan(
    val active: Boolean,
    val zeroGrainLock: Float,
    val rgbGrainQuarantine: Float,
    val motionBlurRejection: Float,
    val microDetailHonesty: Float,
    val temporalBlend: Float,
    val spatialDenoise: Float,
    val chromaDenoise: Float,
    val sharpening: Float,
    val sharpeningFloor: Float,
    val sharpeningCeiling: Float,
    val localChromaSpikeSmoothing: Float,
    val subjectDetailGain: Float,
    val signature: String
)

internal object V20ZeroGrainClarityPolicy {
    private const val VERSION = "V20_ZERO_GRAIN_CLARITY"

    fun resolve(request: V20ZeroGrainClarityRequest): V20ZeroGrainClarityPlan {
        val strict = request.strictHighEnd
        val rawMonitor = request.signalFamily == GpuSignalFamily.RAW
        val active =
            strict &&
                request.normalSdrRealtimeLock &&
                request.signalFamily == GpuSignalFamily.SDR &&
                !rawMonitor
        val noise = noisePressure(request)
        val chroma = chromaPressure(request, noise)
        val motion = motionPressure(request)
        val texture = textureConfidence(request)
        val staticness = (1f - motion).coerceIn(0f, 1f)
        val zeroGrainLock = if (active) {
            (0.72f + noise * 0.22f + chroma * 0.10f + if (request.normalSdrRealtimeLock) 0.035f else 0f)
                .coerceIn(0.72f, 0.9999f)
        } else {
            0f
        }
        val rgbGrainQuarantine = if (active) {
            (0.70f + chroma * 0.24f + request.profile.noiseMetrics.frameToFrameRgbVariance.coerceIn(0f, 1f) * 0.10f)
                .coerceIn(0.70f, 0.9999f)
        } else {
            0f
        }
        val motionBlurRejection = if (active) {
            (0.46f + motion * 0.42f + request.profile.noiseMetrics.temporalInstability.coerceIn(0f, 1f) * 0.16f)
                .coerceIn(0.46f, 0.96f)
        } else {
            0f
        }
        val microDetailHonesty = if (active) {
            (
                texture * 0.52f +
                    request.profile.sharpening.coerceIn(0f, 1f) * 0.18f +
                    staticness * 0.18f -
                    noise * 0.24f -
                    chroma * 0.08f -
                    request.skinDetailProtection.coerceIn(0f, 1f) * 0.06f
                ).coerceIn(0f, 1f)
        } else {
            0f
        }

        if (!active) {
            return V20ZeroGrainClarityPlan(
                active = false,
                zeroGrainLock = 0f,
                rgbGrainQuarantine = 0f,
                motionBlurRejection = 0f,
                microDetailHonesty = 0f,
                temporalBlend = request.temporalBlend.coerceIn(0f, 1f),
                spatialDenoise = request.spatialDenoise.coerceIn(0f, 1f),
                chromaDenoise = request.chromaDenoise.coerceIn(0f, 1f),
                sharpening = request.sharpening.coerceIn(0f, 1f),
                sharpeningFloor = request.sharpeningFloor.coerceIn(0f, 1f),
                sharpeningCeiling = request.sharpeningCeiling.coerceIn(0f, 1f),
                localChromaSpikeSmoothing = request.localChromaSpikeSmoothing.coerceIn(0f, 1f),
                subjectDetailGain = request.subjectDetailGain.coerceIn(0f, 1f),
                signature = "$VERSION|active=false|reason=not-normal-pro-sdr-realtime"
            )
        }

        val motionSafeTemporalCeiling = when {
            motion >= 0.62f -> 0.44f
            motion >= 0.42f -> 0.54f
            motion >= 0.24f -> 0.66f
            else -> 0.84f
        }
        val temporalTarget = (
            request.temporalBlend.safeUnit() +
                noise * 0.18f * staticness +
                chroma * 0.10f * staticness -
                motion * 0.10f
            ).coerceIn(0f, motionSafeTemporalCeiling)
        val spatialTarget = max(
            request.spatialDenoise.safeUnit(),
            (
                0.14f +
                    noise * 0.54f +
                    chroma * 0.10f -
                    texture * 0.16f -
                    motion * 0.06f
                ).coerceIn(0.12f, 0.82f)
        ).coerceIn(0f, 0.82f)
        val chromaTarget = max(
            request.chromaDenoise.safeUnit(),
            (
                0.20f +
                    chroma * 0.54f +
                    noise * 0.12f -
                    texture * 0.08f
                ).coerceIn(0.18f, 0.90f)
        ).coerceIn(0f, 0.90f)
        val sharpenCeiling = minOfFinite(
            request.sharpeningCeiling,
            (
                0.90f -
                    noise * 0.22f -
                    chroma * 0.12f -
                    motion * 0.12f +
                    microDetailHonesty * 0.11f
                ).coerceIn(0.34f, 0.94f)
        ).coerceIn(0f, 1f)
        val sharpenFloor = minOf(
            sharpenCeiling,
            max(
                request.sharpeningFloor.coerceIn(0f, 1f),
                (
                    0.42f +
                        microDetailHonesty * 0.32f +
                        texture * 0.10f -
                        noise * 0.10f -
                        motion * 0.08f
                    ).coerceIn(0.24f, 0.82f)
            )
        )
        val sharpenTarget = request.sharpening.safeUnit()
            .coerceIn(sharpenFloor, sharpenCeiling)
        val finalSubjectHeadroom = (
            (sharpenCeiling - sharpenTarget).coerceAtLeast(0f) *
                (1f - request.skinDetailProtection.safeUnit() * 0.5f)
            ).coerceIn(0f, 1f)
        val subjectTarget = minOf(
            request.subjectDetailGain.safeUnit(),
            finalSubjectHeadroom
        )
        val localChromaTarget = max(
            request.localChromaSpikeSmoothing.safeUnit(),
            (rgbGrainQuarantine + chroma * 0.06f).coerceIn(0.72f, 0.9999f)
        )

        return V20ZeroGrainClarityPlan(
            active = true,
            zeroGrainLock = zeroGrainLock,
            rgbGrainQuarantine = rgbGrainQuarantine,
            motionBlurRejection = motionBlurRejection,
            microDetailHonesty = microDetailHonesty,
            temporalBlend = temporalTarget,
            spatialDenoise = spatialTarget,
            chromaDenoise = chromaTarget,
            sharpening = sharpenTarget,
            sharpeningFloor = sharpenFloor,
            sharpeningCeiling = sharpenCeiling,
            localChromaSpikeSmoothing = localChromaTarget,
            subjectDetailGain = subjectTarget,
            signature = listOf(
                VERSION,
                "active=true",
                "signal=${request.signalFamily.name}",
                "zeroGrain=${zeroGrainLock.format4()}",
                "rgbQuarantine=${rgbGrainQuarantine.format4()}",
                "motionBlurReject=${motionBlurRejection.format4()}",
                "microDetailHonesty=${microDetailHonesty.format4()}",
                "temporal=${temporalTarget.format4()}",
                "spatial=${spatialTarget.format4()}",
                "chroma=${chromaTarget.format4()}",
                "sharpen=${sharpenTarget.format4()}",
                "floor=${sharpenFloor.format4()}",
                "ceiling=${sharpenCeiling.format4()}",
                "subject=${subjectTarget.format4()}",
                "profile=${request.videoDetailProfile?.name ?: "LEGACY"}"
            ).joinToString(separator = "|")
        )
    }

    private fun noisePressure(request: V20ZeroGrainClarityRequest): Float {
        val isoPressure = ((request.iso.coerceAtLeast(0f) - 400f) / 6_400f).coerceIn(0f, 1f)
        val metrics = request.profile.noiseMetrics.clamped()
        return maxOf(
            request.noiseMetric.safeUnit(),
            metrics.lumaNoise,
            metrics.chromaNoise * 0.90f,
            metrics.frameToFrameRgbVariance * 0.86f,
            request.localChromaSpikeSmoothing.safeUnit() * 0.72f,
            isoPressure
        ).coerceIn(0f, 1f)
    }

    private fun chromaPressure(
        request: V20ZeroGrainClarityRequest,
        noise: Float
    ): Float {
        val metrics = request.profile.noiseMetrics.clamped()
        return maxOf(
            noise * 0.84f,
            metrics.chromaNoise,
            metrics.frameToFrameRgbVariance,
            request.localChromaSpikeSmoothing.safeUnit()
        ).coerceIn(0f, 1f)
    }

    private fun motionPressure(request: V20ZeroGrainClarityRequest): Float {
        val metrics = request.profile.noiseMetrics.clamped()
        val skewPressure = if (request.profile.dualGainTimestampSkewNs == Long.MAX_VALUE) {
            0f
        } else {
            (request.profile.dualGainTimestampSkewNs.toFloat() / 18_000_000f).coerceIn(0f, 1f)
        }
        return maxOf(
            metrics.temporalInstability,
            skewPressure,
            if (request.profile.dualGainMotionMaskEnabled) 0.18f else 0f
        ).coerceIn(0f, 1f)
    }

    private fun textureConfidence(request: V20ZeroGrainClarityRequest): Float {
        val metadata = request.profile.detailSharpeningMetadata
        val edge = metadata.metric("detailEdgeConfidence")
        val texture = metadata.metric("detailTextureConfidence")
        val micro = metadata.metric("detailMicroContrast")
        val reconstruction = metadata.metric("detailReconstruction")
        val profileBoost = when (request.videoDetailProfile) {
            VideoDetailProfile.EXTREME_DETAIL -> 0.10f
            VideoDetailProfile.FOCUSED_DETAIL -> 0.07f
            VideoDetailProfile.NATURAL_DETAIL -> 0.03f
            null -> 0f
        }
        return maxOf(
            edge,
            texture,
            micro * 0.88f,
            reconstruction * 0.82f,
            request.profile.sharpening.coerceIn(0f, 1f) * 0.68f + profileBoost
        ).coerceIn(0f, 1f)
    }

    private fun Map<String, String>.metric(key: String): Float =
        this[key]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f

    private fun minOfFinite(first: Float, second: Float): Float =
        when {
            !first.isFinite() -> second
            !second.isFinite() -> first
            else -> minOf(first, second)
        }

    private fun Float.safeUnit(default: Float = 0f): Float =
        if (isFinite()) coerceIn(0f, 1f) else default

    private fun Float.format4(): String =
        String.format(Locale.US, "%.4f", this.coerceIn(0f, 1f))
}
