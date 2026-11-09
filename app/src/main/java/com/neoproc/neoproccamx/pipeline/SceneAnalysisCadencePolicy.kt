package com.neoproc.neoproccamx.pipeline

internal data class SceneAnalysisCadencePlan(
    val sceneStatsStride: Long,
    val statsLongEdge: Int
)

/** Cadence for the internal scene/quality analysis; it has no user-facing monitor output. */
internal object SceneAnalysisCadencePolicy {
    fun resolve(previewFps: Float, recording: Boolean): SceneAnalysisCadencePlan {
        val fps = previewFps.coerceAtLeast(1f)
        return when {
            recording && fps < 24f -> SceneAnalysisCadencePlan(12L, 160)
            recording && fps < 25f -> SceneAnalysisCadencePlan(10L, 160)
            recording -> SceneAnalysisCadencePlan(8L, 160)
            fps < 24f -> SceneAnalysisCadencePlan(4L, 256)
            fps <= 60f -> SceneAnalysisCadencePlan(2L, if (fps <= 30f) 320 else 288)
            else -> SceneAnalysisCadencePlan(3L, 256)
        }
    }
}
