package com.neoproc.neoproccamx.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.pipeline.HighEndRuntimeTier

internal data class AICameraRuntimeProfile(
    val analysisMaxDimension: Int,
    val objectDetectionStride: Int,
    val poseDetectionStride: Int,
    val segmentationStride: Int,
    val depthEstimationStride: Int,
    val enhancementStride: Int,
    val sceneDetectionStride: Int,
    val analysisTimeoutMs: Long
)

internal object AICameraRuntimePolicy {

    val runtimeTier = MutableStateFlow(HighEndRuntimeTier.BASIC)

    val aiMaxDimension = 1920

    val qualityBoost = 1.0f

    internal data class StrictAiTargets(
        val objectStride: Int,
        val poseStride: Int,
        val segmentationStride: Int,
        val depthStride: Int,
        val enhancementStride: Int,
        val sceneStride: Int,
        val timeoutMs: Long,
        val minDimension: Int
    )

    fun resolve(
        previewFps: Float,
        recording: Boolean,
        baseObjectDetectionStride: Int,
        basePoseDetectionStride: Int,
        baseSegmentationStride: Int,
        baseDepthEstimationStride: Int,
        baseEnhancementStride: Int,
        baseSceneDetectionStride: Int,
        baseAnalysisTimeoutMs: Long,
        baseMaxAnalysisDimension: Int,
        highEndQualityEnabled: Boolean = HighEndQualityPolicy.enabled,
        computationalCinemaPlan: ComputationalCinemaPlan? = null
    ): AICameraRuntimeProfile {
        val fps = previewFps.coerceAtLeast(1f)
        val baseObjectStride = baseObjectDetectionStride.coerceAtLeast(1)
        val basePoseStride = basePoseDetectionStride.coerceAtLeast(1)
        val baseSegmentationStrideSafe = baseSegmentationStride.coerceAtLeast(1)
        val baseDepthStride = baseDepthEstimationStride.coerceAtLeast(1)
        val baseEnhancementStrideSafe = baseEnhancementStride.coerceAtLeast(1)
        val baseSceneStride = baseSceneDetectionStride.coerceAtLeast(1)
        val baseTimeout = baseAnalysisTimeoutMs.coerceAtLeast(10L)
        val baseDimension = baseMaxAnalysisDimension.coerceAtLeast(256)
        val baseProfile = AICameraRuntimeProfile(
            analysisMaxDimension = when {
                recording && fps < 20f -> 384
                recording && fps < 28f -> 416
                recording && fps < 40f -> 448
                recording -> 512
                fps < 24f -> 448
                fps < 28f -> 576
                else -> baseDimension
            }.coerceAtLeast(256),
            objectDetectionStride = when {
                recording && fps < 20f -> 9
                recording && fps < 28f -> 8
                recording && fps < 40f -> 7
                recording -> 6
                fps < 24f -> 6
                fps < 28f -> 5
                else -> baseObjectStride
            },
            poseDetectionStride = when {
                recording && fps < 20f -> 10
                recording && fps < 28f -> 9
                recording && fps < 40f -> 8
                recording -> 7
                fps < 24f -> 7
                fps < 28f -> 6
                else -> basePoseStride
            },
            segmentationStride = when {
                recording && fps < 20f -> 11
                recording && fps < 28f -> 10
                recording && fps < 40f -> 9
                recording -> 8
                fps < 24f -> 8
                fps < 28f -> 7
                else -> baseSegmentationStrideSafe
            },
            depthEstimationStride = when {
                recording && fps < 20f -> 14
                recording && fps < 28f -> 12
                recording && fps < 40f -> 11
                recording -> 10
                fps < 24f -> 10
                fps < 28f -> 9
                else -> baseDepthStride
            },
            enhancementStride = when {
                recording && fps < 20f -> 11
                recording && fps < 28f -> 10
                recording && fps < 40f -> 9
                recording -> 8
                fps < 24f -> 8
                fps < 28f -> 7
                else -> baseEnhancementStrideSafe
            },
            sceneDetectionStride = when {
                recording && fps < 20f -> 14
                recording && fps < 28f -> 12
                recording && fps < 40f -> 11
                recording -> 10
                fps < 24f -> 10
                fps < 28f -> 8
                else -> baseSceneStride
            },
            analysisTimeoutMs = when {
                recording && fps < 20f -> 18L
                recording && fps < 28f -> 20L
                recording && fps < 40f -> 22L
                recording -> 24L
                fps < 24f -> 20L
                fps < 28f -> 24L
                else -> baseTimeout.coerceAtLeast(28L)
            }
        )

        if (!highEndQualityEnabled) {
            return applyComputationalCinemaPlan(baseProfile, computationalCinemaPlan)
        }

        val runtimeTier = HighEndQualityPolicy.runtimeTier(
            previewFps = fps,
            recording = recording,
            enabledOverride = highEndQualityEnabled
        )
        val strictHighEnd = HighEndQualityPolicy.strictHighEndOnly
        if (strictHighEnd) {
            val realtimePressurePenalty = when {
                recording && fps < 20f -> 3
                recording && fps < 28f -> 2
                recording && fps < 40f -> 1
                !recording && fps < 24f -> 2
                !recording && fps < 30f -> 1
                else -> 0
            }
            val strictTargets = when (runtimeTier) {
                HighEndRuntimeTier.FLAGSHIP -> StrictAiTargets(
                    objectStride = if (recording) 2 else 1,
                    poseStride = if (recording) 2 else 1,
                    segmentationStride = 2,
                    depthStride = 2,
                    enhancementStride = if (recording) 2 else 1,
                    sceneStride = 2,
                    timeoutMs = if (recording) 42L else 46L,
                    minDimension = if (recording) 1280 else 1536
                )
                HighEndRuntimeTier.CINEMA -> StrictAiTargets(
                    objectStride = if (recording) 2 else 1,
                    poseStride = if (recording) 2 else 1,
                    segmentationStride = if (recording) 3 else 2,
                    depthStride = if (recording) 3 else 2,
                    enhancementStride = if (recording) 2 else 1,
                    sceneStride = if (recording) 3 else 2,
                    timeoutMs = if (recording) 40L else 44L,
                    minDimension = if (recording) 1152 else 1280
                )
                HighEndRuntimeTier.ULTRA -> StrictAiTargets(
                    objectStride = if (recording) 2 else 1,
                    poseStride = if (recording) 2 else 1,
                    segmentationStride = if (recording) 3 else 2,
                    depthStride = if (recording) 3 else 2,
                    enhancementStride = if (recording) 2 else 1,
                    sceneStride = if (recording) 3 else 2,
                    timeoutMs = if (recording) 38L else 44L,
                    minDimension = if (recording) 1088 else 1280
                )
                HighEndRuntimeTier.PROFESSIONAL -> StrictAiTargets(
                    objectStride = if (recording) 2 else 1,
                    poseStride = if (recording) 2 else 1,
                    segmentationStride = if (recording) 3 else 2,
                    depthStride = if (recording) 3 else 2,
                    enhancementStride = if (recording) 2 else 1,
                    sceneStride = if (recording) 3 else 2,
                    timeoutMs = if (recording) 40L else 42L,
                    minDimension = if (recording) 1152 else 1216
                )
                HighEndRuntimeTier.BALANCED -> StrictAiTargets(
                    objectStride = 3,
                    poseStride = 3,
                    segmentationStride = 4,
                    depthStride = 4,
                    enhancementStride = 3,
                    sceneStride = 4,
                    timeoutMs = if (recording) 30L else 34L,
                    minDimension = if (recording) 832 else 896
                )
                HighEndRuntimeTier.SUSTAINED -> StrictAiTargets(
                    objectStride = 4,
                    poseStride = 4,
                    segmentationStride = 5,
                    depthStride = 5,
                    enhancementStride = 4,
                    sceneStride = 5,
                    timeoutMs = if (recording) 26L else 30L,
                    minDimension = 768
                )
                HighEndRuntimeTier.RECOVERY -> StrictAiTargets(
                    objectStride = 5,
                    poseStride = 5,
                    segmentationStride = 6,
                    depthStride = 6,
                    enhancementStride = 5,
                    sceneStride = 6,
                    timeoutMs = 24L,
                    minDimension = 640
                )
                else -> StrictAiTargets(
                    objectStride = 6,
                    poseStride = 6,
                    segmentationStride = 7,
                    depthStride = 7,
                    enhancementStride = 6,
                    sceneStride = 7,
                    timeoutMs = 20L,
                    minDimension = 512
                )
            }
            val strictBaseDimensionCap = maxOf(
                HighEndQualityPolicy.aiMaxDimension(
                    previewFps = fps,
                    recording = recording,
                    enabledOverride = highEndQualityEnabled
                ) + if (runtimeTier == HighEndRuntimeTier.CINEMA) 256 else 128,
                baseDimension + if (recording) 448 else 512
            )
            val pressureDimensionCap = when {
                recording && fps < 20f -> 720
                recording && fps < 28f -> 896
                recording && fps < 40f -> 1024
                !recording && fps < 24f -> 720
                !recording && fps < 30f -> 896
                else -> Int.MAX_VALUE
            }
            val strictDimensionCap = minOf(strictBaseDimensionCap, pressureDimensionCap)
                .coerceAtLeast(maxOf(baseProfile.analysisMaxDimension, 512))
            val strictBoostedDimension = (
                baseProfile.analysisMaxDimension.toFloat() *
                    HighEndQualityPolicy.qualityBoost(
                        recording = recording,
                        previewFps = fps,
                        enabledOverride = highEndQualityEnabled
                    ) * 1.08f
                ).toInt()
                .coerceAtLeast(maxOf(baseProfile.analysisMaxDimension, strictTargets.minDimension))
                .coerceAtMost(strictDimensionCap)

            val strictLoadPenalty = when {
                recording && fps < 20f -> 2
                recording && fps < 28f -> 1
                !recording && fps < 24f -> 2
                !recording && fps < 30f -> 1
                else -> 0
            } + realtimePressurePenalty
            val strictDimensionFloor = maxOf(
                baseProfile.analysisMaxDimension,
                strictTargets.minDimension - strictLoadPenalty * 160
            )
            val strictAdjustedDimension = (
                strictBoostedDimension - strictLoadPenalty * 256
                ).coerceAtLeast(strictDimensionFloor.coerceAtLeast(512))
                .coerceAtMost(strictDimensionCap)
            val strictTimeoutBase = maxOf(baseProfile.analysisTimeoutMs, strictTargets.timeoutMs)
            val strictAdjustedTimeout =
                if (strictLoadPenalty > 0) {
                    (strictTimeoutBase - strictLoadPenalty * 6L)
                        .coerceIn(18L, if (recording) 32L else 34L)
                } else {
                    strictTimeoutBase.coerceAtLeast(if (recording) 30L else 34L)
                }

            val strictProfile = baseProfile.copy(
                analysisMaxDimension = strictAdjustedDimension,
                objectDetectionStride = (
                    minOf(baseProfile.objectDetectionStride, strictTargets.objectStride) + strictLoadPenalty
                    ).coerceIn(1, if (recording) 4 else 3),
                poseDetectionStride = (
                    minOf(baseProfile.poseDetectionStride, strictTargets.poseStride) + strictLoadPenalty
                    ).coerceIn(1, if (recording) 4 else 3),
                segmentationStride = (
                    minOf(baseProfile.segmentationStride, strictTargets.segmentationStride) + strictLoadPenalty
                    ).coerceIn(2, if (recording) 5 else 4),
                depthEstimationStride = (
                    minOf(baseProfile.depthEstimationStride, strictTargets.depthStride) + strictLoadPenalty
                    ).coerceIn(2, if (recording) 5 else 4),
                enhancementStride = (
                    minOf(baseProfile.enhancementStride, strictTargets.enhancementStride) + strictLoadPenalty
                    ).coerceIn(1, if (recording) 4 else 3),
                sceneDetectionStride = (
                    minOf(baseProfile.sceneDetectionStride, strictTargets.sceneStride) + strictLoadPenalty
                    ).coerceIn(2, if (recording) 5 else 4),
                analysisTimeoutMs = strictAdjustedTimeout
            )
            return applyComputationalCinemaPlan(strictProfile, computationalCinemaPlan)
        }
        val baseStrideReduction = when (runtimeTier) {
            HighEndRuntimeTier.CINEMA -> if (recording) 3 else 4
            HighEndRuntimeTier.ULTRA -> if (recording) 2 else 3
            HighEndRuntimeTier.PROFESSIONAL -> if (recording) 2 else 3
            HighEndRuntimeTier.BALANCED -> if (recording) 1 else 2
            HighEndRuntimeTier.SUSTAINED -> 1
            HighEndRuntimeTier.RECOVERY -> 0
            else -> 0
        }
        val strictStrideBonus = when {
            !strictHighEnd -> 0
            runtimeTier == HighEndRuntimeTier.CINEMA && fps >= if (recording) 52f else 54f -> 1
            runtimeTier == HighEndRuntimeTier.ULTRA && fps >= if (recording) 48f else 50f -> 1
            runtimeTier == HighEndRuntimeTier.PROFESSIONAL && fps >= if (recording) 42f else 46f -> 1
            else -> 0
        }
        val strideReduction = baseStrideReduction + strictStrideBonus
        fun tightenStride(value: Int, minValue: Int): Int =
            (value - strideReduction).coerceAtLeast(minValue)

        val analysisHeadroom = when (runtimeTier) {
            HighEndRuntimeTier.CINEMA -> if (strictHighEnd) 448 else 384
            HighEndRuntimeTier.ULTRA -> if (strictHighEnd) 352 else 320
            HighEndRuntimeTier.PROFESSIONAL -> if (strictHighEnd) 320 else 288
            HighEndRuntimeTier.BALANCED -> 224
            HighEndRuntimeTier.SUSTAINED -> 160
            HighEndRuntimeTier.RECOVERY -> 96
            else -> 64
        }
        val fpsHeadroomBonus = when {
            fps >= 110f -> 128
            fps >= 90f -> 96
            fps >= 75f -> 72
            fps >= 60f -> 48
            else -> 0
        }
        val tierDimensionFloor = when (runtimeTier) {
            HighEndRuntimeTier.CINEMA -> if (recording) 896 else 1024
            HighEndRuntimeTier.ULTRA -> if (recording) 768 else 1024
            HighEndRuntimeTier.PROFESSIONAL -> if (recording) 768 else 896
            HighEndRuntimeTier.BALANCED -> if (recording) 640 else 704
            HighEndRuntimeTier.SUSTAINED -> 576
            HighEndRuntimeTier.RECOVERY -> 512
            else -> 480
        }
        val tierDimensionCap = maxOf(
            baseDimension + analysisHeadroom + if (strictHighEnd) fpsHeadroomBonus else 0,
            HighEndQualityPolicy.aiMaxDimension(
                previewFps = fps,
                recording = recording,
                enabledOverride = highEndQualityEnabled
            )
        )
        val boostedDimension = (
            baseProfile.analysisMaxDimension.toFloat() * HighEndQualityPolicy.qualityBoost(
                recording = recording,
                previewFps = fps,
                enabledOverride = highEndQualityEnabled
            )
            ).toInt()
            .coerceAtLeast(maxOf(baseProfile.analysisMaxDimension, tierDimensionFloor))
            .coerceAtMost(tierDimensionCap)

        val timeoutBonus = when (runtimeTier) {
            HighEndRuntimeTier.CINEMA -> if (recording) 6L else 8L
            HighEndRuntimeTier.ULTRA -> if (recording) 4L else 6L
            HighEndRuntimeTier.PROFESSIONAL -> if (recording) 4L else 6L
            HighEndRuntimeTier.BALANCED -> if (recording) 2L else 4L
            HighEndRuntimeTier.SUSTAINED -> if (recording) 1L else 2L
            HighEndRuntimeTier.RECOVERY -> 0L
            else -> 0L
        }
        val timeoutFloor = when (runtimeTier) {
            HighEndRuntimeTier.CINEMA -> if (recording) 30L else 34L
            HighEndRuntimeTier.ULTRA -> if (recording) 28L else 32L
            HighEndRuntimeTier.PROFESSIONAL -> if (recording) 28L else 32L
            HighEndRuntimeTier.BALANCED -> if (recording) 24L else 28L
            HighEndRuntimeTier.SUSTAINED -> 22L
            HighEndRuntimeTier.RECOVERY -> 18L
            else -> 16L
        }
        val timeoutCap = when (runtimeTier) {
            HighEndRuntimeTier.CINEMA -> if (strictHighEnd) 48L else 45L
            HighEndRuntimeTier.ULTRA -> if (strictHighEnd) 44L else 40L
            HighEndRuntimeTier.PROFESSIONAL -> if (strictHighEnd) 44L else 40L
            else -> 36L
        }
        val boostedTimeout = (baseProfile.analysisTimeoutMs + timeoutBonus + (strictStrideBonus * 2L))
            .coerceAtLeast(timeoutFloor)
            .coerceAtMost(timeoutCap)
        val objectMinStride = when {
            !recording && (runtimeTier == HighEndRuntimeTier.CINEMA || runtimeTier == HighEndRuntimeTier.ULTRA) -> 1
            else -> 2
        }
        val poseMinStride = when {
            !recording && (runtimeTier == HighEndRuntimeTier.CINEMA || runtimeTier == HighEndRuntimeTier.ULTRA) -> 1
            else -> 2
        }
        val segmentationMinStride = when {
            !recording && (runtimeTier == HighEndRuntimeTier.CINEMA || runtimeTier == HighEndRuntimeTier.ULTRA) -> 2
            else -> 3
        }
        val depthMinStride = when {
            runtimeTier == HighEndRuntimeTier.CINEMA -> 2
            else -> 3
        }
        val enhancementMinStride = when {
            runtimeTier == HighEndRuntimeTier.CINEMA || runtimeTier == HighEndRuntimeTier.ULTRA -> 1
            else -> 2
        }
        val sceneMinStride = when {
            !recording && (runtimeTier == HighEndRuntimeTier.CINEMA || runtimeTier == HighEndRuntimeTier.ULTRA) -> 2
            else -> 3
        }

        val runtimeProfile = baseProfile.copy(
            analysisMaxDimension = boostedDimension,
            objectDetectionStride = tightenStride(baseProfile.objectDetectionStride, objectMinStride),
            poseDetectionStride = tightenStride(baseProfile.poseDetectionStride, poseMinStride),
            segmentationStride = tightenStride(baseProfile.segmentationStride, segmentationMinStride),
            depthEstimationStride = tightenStride(baseProfile.depthEstimationStride, depthMinStride),
            enhancementStride = tightenStride(baseProfile.enhancementStride, enhancementMinStride),
            sceneDetectionStride = tightenStride(baseProfile.sceneDetectionStride, sceneMinStride),
            analysisTimeoutMs = boostedTimeout
        )
        return applyComputationalCinemaPlan(runtimeProfile, computationalCinemaPlan)
    }

    private fun applyComputationalCinemaPlan(
        profile: AICameraRuntimeProfile,
        plan: ComputationalCinemaPlan?
    ): AICameraRuntimeProfile {
        if (plan == null) return profile
        val targetLongEdge = plan.analysisLongEdge.coerceAtLeast(256)
        if (plan.delivery == ComputationalCinemaDelivery.LIVE_WITH_POST_PROCESS_FALLBACK) {
            return profile.copy(
                analysisMaxDimension = minOf(profile.analysisMaxDimension, targetLongEdge),
                objectDetectionStride = maxOf(profile.objectDetectionStride, 3),
                poseDetectionStride = maxOf(profile.poseDetectionStride, 3),
                segmentationStride = maxOf(profile.segmentationStride, 4),
                depthEstimationStride = maxOf(profile.depthEstimationStride, 4),
                enhancementStride = maxOf(profile.enhancementStride, 3),
                sceneDetectionStride = maxOf(profile.sceneDetectionStride, 4),
                analysisTimeoutMs = minOf(profile.analysisTimeoutMs, 34L)
            )
        }

        val denseLiveAi = plan.enableAiSuperResolution ||
            plan.liveStages.contains(ComputationalCinemaStage.TEMPORAL_DETAIL_RECOVERY)
        val liveTimeoutFloor = when {
            plan.enableAiSuperResolution && plan.enableSmartHdr -> 46L
            plan.enableAiSuperResolution || plan.enableSmartHdr -> 42L
            else -> profile.analysisTimeoutMs
        }

        return profile.copy(
            analysisMaxDimension = targetLongEdge,
            objectDetectionStride = if (denseLiveAi) 1 else profile.objectDetectionStride,
            poseDetectionStride = if (denseLiveAi) 1 else profile.poseDetectionStride,
            segmentationStride = if (denseLiveAi) minOf(profile.segmentationStride, 2) else profile.segmentationStride,
            depthEstimationStride = if (denseLiveAi) minOf(profile.depthEstimationStride, 2) else profile.depthEstimationStride,
            enhancementStride = if (denseLiveAi) 1 else profile.enhancementStride,
            sceneDetectionStride = if (denseLiveAi) minOf(profile.sceneDetectionStride, 2) else profile.sceneDetectionStride,
            analysisTimeoutMs = maxOf(profile.analysisTimeoutMs, liveTimeoutFloor)
        )
    }
}
