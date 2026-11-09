package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class RecordingVideoQualityBudgetRequest(
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: GpuVideoCodec,
    val hdrMode: String,
    val logEnabled: Boolean,
    val logProfile: String = "N-Log",
    val tenBit: Boolean,
    val colorSpace: String,
    val videoDetailProfile: VideoDetailProfile? = null,
    val motionRisk: Float = 0f,
    val noiseRisk: Float = 0f,
    val antiMotionBlurEnabled: Boolean = false,
    val sourceQualityBudgetMultiplier: Double = 1.0,
    val sourceQualityTier: String = "STANDARD"
)

internal enum class RecordingVideoQualitySourcePressureTier {
    STANDARD,
    MOTION_STABLE,
    HIGH_NOISE_DETAIL,
    MOTION_STABLE_HIGH_NOISE_DETAIL
}

internal data class RecordingVideoQualityBudgetPlan(
    val targetBitrateBps: Int,
    val qualityValue: Int,
    val codecCeilingBps: Long,
    val baseFloorBps: Long,
    val pixelBudgetBps: Long,
    val modeFloorBps: Long,
    val eliteFloorBps: Long,
    val sourceQualityPressureFloorBps: Long,
    val bitratePressureMultiplier: Double,
    val sourceQualityPressureTier: RecordingVideoQualitySourcePressureTier,
    val motionRisk: Float,
    val noiseRisk: Float,
    val highNoiseBudgetApplied: Boolean,
    val highDetailBudgetApplied: Boolean,
    val motionBudgetApplied: Boolean,
    val antiMotionBlurBudgetApplied: Boolean,
    val metadata: Map<String, String>
)

internal object RecordingVideoQualityBudgetPolicy {
    private const val MAX_VIDEO_QUALITY_VALUE = 100
    private const val LEGACY_HIGH_END_QUALITY_VALUE = 88
    private const val HIGH_MOTION_RISK_THRESHOLD = 0.58f
    private const val HIGH_NOISE_RISK_THRESHOLD = 0.55f

    fun resolveQualityValue(): Int =
        if (strictMaxQualityEnabled()) MAX_VIDEO_QUALITY_VALUE else LEGACY_HIGH_END_QUALITY_VALUE

    fun resolveQualityValue(request: RecordingVideoQualityBudgetRequest): Int {
        if (strictMaxQualityEnabled()) return MAX_VIDEO_QUALITY_VALUE
        val pressure = resolveSourceQualityPressure(request)
        return (LEGACY_HIGH_END_QUALITY_VALUE + pressure.qualityValueLift)
            .coerceIn(LEGACY_HIGH_END_QUALITY_VALUE, 96)
    }

    fun resolveTargetBitrateBps(request: RecordingVideoQualityBudgetRequest): Int =
        resolveBudgetPlan(request).targetBitrateBps

    fun resolveBudgetPlan(request: RecordingVideoQualityBudgetRequest): RecordingVideoQualityBudgetPlan {
        val width = request.width.coerceAtLeast(1)
        val height = request.height.coerceAtLeast(1)
        val fps = request.fps.coerceAtLeast(24)
        val pixels = width.toLong() * height.toLong()
        val codecCeiling = RecordingCodecCatalog.maxBitrateMbps(request.codec).toLong() * 1_000_000L
        val pressure = resolveSourceQualityPressure(request)
        val qualityValue = if (strictMaxQualityEnabled()) {
            MAX_VIDEO_QUALITY_VALUE
        } else {
            (LEGACY_HIGH_END_QUALITY_VALUE + pressure.qualityValueLift)
                .coerceIn(LEGACY_HIGH_END_QUALITY_VALUE, 96)
        }
        if (strictMaxQualityEnabled()) {
            val targetBitrate = codecCeiling
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            return RecordingVideoQualityBudgetPlan(
                targetBitrateBps = targetBitrate,
                qualityValue = qualityValue,
                codecCeilingBps = codecCeiling,
                baseFloorBps = codecCeiling,
                pixelBudgetBps = codecCeiling,
                modeFloorBps = codecCeiling,
                eliteFloorBps = codecCeiling,
                sourceQualityPressureFloorBps = codecCeiling,
                bitratePressureMultiplier = pressure.multiplier,
                sourceQualityPressureTier = pressure.tier,
                motionRisk = pressure.motionRisk,
                noiseRisk = pressure.noiseRisk,
                highNoiseBudgetApplied = pressure.highNoiseBudgetApplied,
                highDetailBudgetApplied = pressure.highDetailBudgetApplied,
                motionBudgetApplied = pressure.motionBudgetApplied,
                antiMotionBlurBudgetApplied = pressure.antiMotionBlurBudgetApplied,
                metadata = pressure.toMetadata(
                    request = request,
                    targetBitrateBps = targetBitrate,
                    qualityValue = qualityValue,
                    sourceQualityPressureFloorBps = codecCeiling
                )
            )
        }
        val floor = if (HighEndQualityPolicy.enabled) {
            HighEndQualityPolicy.minimumBitrateBps(width, height, fps).toLong()
        } else {
            legacyFloor(width, height, fps).toLong()
        }
        val pixelBudget = (pixels.toDouble() * fps.toDouble() * bitsPerPixelFrame(request))
            .roundToLong()
        val modeFloor = (floor.toDouble() * modeHeadroom(request)).roundToLong()
        val eliteMode = inferEliteMode(request)
        val requiresMain10Sdr =
            eliteMode == EliteVideoPipelineMode.NORMAL || eliteMode == EliteVideoPipelineMode.PRO
        val eliteFloor = EliteVideoQualityContract.resolveMinimumBitrateBps(
            EliteVideoQualityRequest(
                mode = eliteMode,
                width = width,
                height = height,
                fps = fps,
                codec = request.codec,
                hdrMode = request.hdrMode,
                logProfile = request.logProfile,
                sourceBitDepth = if (request.tenBit || requiresMain10Sdr) 10 else 8,
                storageBitDepth = if (request.tenBit || requiresMain10Sdr) 10 else 8,
                tenBitTransport = request.tenBit || requiresMain10Sdr,
                colorSpace = request.colorSpace
            )
        ).toLong()
        val unpressuredFloor = maxOf(floor, pixelBudget, modeFloor, eliteFloor)
        val sourceQualityPressureFloor = (unpressuredFloor.toDouble() * pressure.multiplier)
            .roundToLong()
        val targetBitrate = maxOf(unpressuredFloor, sourceQualityPressureFloor)
            .coerceAtMost(codecCeiling)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        return RecordingVideoQualityBudgetPlan(
            targetBitrateBps = targetBitrate,
            qualityValue = qualityValue,
            codecCeilingBps = codecCeiling,
            baseFloorBps = floor,
            pixelBudgetBps = pixelBudget,
            modeFloorBps = modeFloor,
            eliteFloorBps = eliteFloor,
            sourceQualityPressureFloorBps = sourceQualityPressureFloor.coerceAtMost(codecCeiling),
            bitratePressureMultiplier = pressure.multiplier,
            sourceQualityPressureTier = pressure.tier,
            motionRisk = pressure.motionRisk,
            noiseRisk = pressure.noiseRisk,
            highNoiseBudgetApplied = pressure.highNoiseBudgetApplied,
            highDetailBudgetApplied = pressure.highDetailBudgetApplied,
            motionBudgetApplied = pressure.motionBudgetApplied,
            antiMotionBlurBudgetApplied = pressure.antiMotionBlurBudgetApplied,
            metadata = pressure.toMetadata(
                request = request,
                targetBitrateBps = targetBitrate,
                qualityValue = qualityValue,
                sourceQualityPressureFloorBps = sourceQualityPressureFloor.coerceAtMost(codecCeiling)
            )
        )
    }

    private fun strictMaxQualityEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun bitsPerPixelFrame(request: RecordingVideoQualityBudgetRequest): Double {
        val base = when (request.codec) {
            GpuVideoCodec.AVC -> 2.65
            GpuVideoCodec.HEVC -> 2.05
            GpuVideoCodec.AV1 -> 1.72
        }
        return base * modeHeadroom(request)
    }

    private fun modeHeadroom(request: RecordingVideoQualityBudgetRequest): Double {
        var multiplier = 1.0
        if (request.tenBit) multiplier += 0.10
        if (isWideGamut(request.colorSpace)) multiplier += 0.08
        if (hasHdrTransfer(request.hdrMode, request.logEnabled)) multiplier += 0.24
        if (request.logEnabled) multiplier += 0.20
        if (request.videoDetailProfile == VideoDetailProfile.EXTREME_DETAIL) multiplier += 0.18
        return multiplier.coerceIn(1.0, 1.66)
    }

    private fun resolveSourceQualityPressure(
        request: RecordingVideoQualityBudgetRequest
    ): SourceQualityPressure {
        val motionRisk = request.motionRisk.coerceIn(0f, 1f)
        val noiseRisk = request.noiseRisk.coerceIn(0f, 1f)
        val sourceTierKey = request.sourceQualityTier.trim().uppercase(Locale.US)
        val sourceTierRequestsMotion = sourceTierKey.contains("MOTION_STABLE")
        val sourceTierRequestsNoiseDetail =
            sourceTierKey.contains("LOW_NOISE_DETAIL") ||
                sourceTierKey.contains("HIGH_NOISE_DETAIL")
        val highNoiseBudgetApplied = noiseRisk >= HIGH_NOISE_RISK_THRESHOLD ||
            sourceTierRequestsNoiseDetail
        val highDetailBudgetApplied = request.videoDetailProfile == VideoDetailProfile.EXTREME_DETAIL ||
            sourceTierRequestsNoiseDetail
        val motionBudgetApplied = motionRisk >= HIGH_MOTION_RISK_THRESHOLD ||
            sourceTierRequestsMotion
        val antiMotionBlurBudgetApplied =
            request.antiMotionBlurEnabled && (motionRisk >= 0.35f || sourceTierRequestsMotion)

        var multiplier = request.sourceQualityBudgetMultiplier.coerceIn(1.0, 1.35)
        if (highNoiseBudgetApplied) {
            multiplier *= 1.08 + noiseRisk.toDouble() * 0.14
        }
        if (highDetailBudgetApplied) {
            multiplier *= 1.16
        }
        if (antiMotionBlurBudgetApplied) {
            multiplier *= 1.10 + motionRisk.toDouble() * 0.10
        } else if (motionBudgetApplied) {
            multiplier *= 1.04 + motionRisk.toDouble() * 0.08
        }
        val boundedMultiplier = multiplier.coerceIn(1.0, 1.85)
        val tier = when {
            (motionBudgetApplied || antiMotionBlurBudgetApplied) &&
                (highNoiseBudgetApplied || highDetailBudgetApplied) ->
                RecordingVideoQualitySourcePressureTier.MOTION_STABLE_HIGH_NOISE_DETAIL
            highNoiseBudgetApplied || highDetailBudgetApplied ->
                RecordingVideoQualitySourcePressureTier.HIGH_NOISE_DETAIL
            motionBudgetApplied || antiMotionBlurBudgetApplied ->
                RecordingVideoQualitySourcePressureTier.MOTION_STABLE
            else -> RecordingVideoQualitySourcePressureTier.STANDARD
        }
        val qualityValueLift = when (tier) {
            RecordingVideoQualitySourcePressureTier.MOTION_STABLE_HIGH_NOISE_DETAIL -> 8
            RecordingVideoQualitySourcePressureTier.HIGH_NOISE_DETAIL -> 5
            RecordingVideoQualitySourcePressureTier.MOTION_STABLE -> 3
            RecordingVideoQualitySourcePressureTier.STANDARD -> 0
        } + ((boundedMultiplier - 1.0) * 4.0).roundToInt().coerceIn(0, 3)

        return SourceQualityPressure(
            multiplier = boundedMultiplier,
            qualityValueLift = qualityValueLift,
            tier = tier,
            motionRisk = motionRisk,
            noiseRisk = noiseRisk,
            highNoiseBudgetApplied = highNoiseBudgetApplied,
            highDetailBudgetApplied = highDetailBudgetApplied,
            motionBudgetApplied = motionBudgetApplied,
            antiMotionBlurBudgetApplied = antiMotionBlurBudgetApplied
        )
    }

    private fun inferEliteMode(request: RecordingVideoQualityBudgetRequest): EliteVideoPipelineMode {
        val hdrMode = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(request.hdrMode)
        return when {
            hdrMode != "OFF" -> EliteVideoPipelineMode.HDR
            request.logEnabled -> EliteVideoPipelineMode.LOG
            request.tenBit -> EliteVideoPipelineMode.PRO
            else -> EliteVideoPipelineMode.NORMAL
        }
    }

    private fun hasHdrTransfer(hdrMode: String, logEnabled: Boolean): Boolean {
        if (logEnabled) return false
        val normalized = hdrMode.trim().uppercase(Locale.US)
        return normalized.isNotEmpty() &&
            normalized != "OFF" &&
            normalized != "SDR"
    }

    private fun isWideGamut(colorSpace: String): Boolean =
        colorSpace.contains("2020", ignoreCase = true) ||
            colorSpace.contains("P3", ignoreCase = true) ||
            colorSpace.contains("ACES", ignoreCase = true) ||
            colorSpace.contains("Wide", ignoreCase = true) ||
            colorSpace.contains("Neo", ignoreCase = true)

    private fun legacyFloor(width: Int, height: Int, fps: Int): Int {
        val pixels = width.toLong() * height.toLong()
        val base = when {
            pixels >= 7680L * 4320 -> 380_000_000
            pixels >= 5120L * 2880 -> 280_000_000
            pixels >= 3840L * 2160 -> 220_000_000
            pixels >= 1920L * 1080 -> 80_000_000
            else -> 20_000_000
        }
        val fpsScale = (fps / 25f).coerceAtLeast(1f)
        return (base * fpsScale)
            .roundToLong()
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private data class SourceQualityPressure(
        val multiplier: Double,
        val qualityValueLift: Int,
        val tier: RecordingVideoQualitySourcePressureTier,
        val motionRisk: Float,
        val noiseRisk: Float,
        val highNoiseBudgetApplied: Boolean,
        val highDetailBudgetApplied: Boolean,
        val motionBudgetApplied: Boolean,
        val antiMotionBlurBudgetApplied: Boolean
    ) {
        fun toMetadata(
            request: RecordingVideoQualityBudgetRequest,
            targetBitrateBps: Int,
            qualityValue: Int,
            sourceQualityPressureFloorBps: Long
        ): Map<String, String> =
            mapOf(
                "v20RecordingQualityBudgetPolicy" to "true",
                "v20SourceQualityRequestTier" to request.sourceQualityTier,
                "v20SourceQualityPressureTier" to tier.name,
                "v20MotionRisk" to motionRisk.evidenceDecimal(),
                "v20NoiseRisk" to noiseRisk.evidenceDecimal(),
                "v20AntiMotionBlurBudgetApplied" to antiMotionBlurBudgetApplied.toString(),
                "v20HighNoiseBudgetApplied" to highNoiseBudgetApplied.toString(),
                "v20HighDetailBudgetApplied" to highDetailBudgetApplied.toString(),
                "v20MotionBudgetApplied" to motionBudgetApplied.toString(),
                "v20BitratePressureMultiplier" to multiplier.evidenceDecimal(),
                "v20SourceQualityPressureFloorBps" to sourceQualityPressureFloorBps.toString(),
                "v20TargetBitrateBps" to targetBitrateBps.toString(),
                "v20QualityValue" to qualityValue.toString()
            )
    }
}

private fun Float.evidenceDecimal(): String =
    String.format(Locale.US, "%.3f", this)

private fun Double.evidenceDecimal(): String =
    String.format(Locale.US, "%.3f", this)
