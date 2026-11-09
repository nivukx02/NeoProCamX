package com.neoproc.neoproccamx.ai

internal object SceneExposureBiasPolicy {

    fun resolve(
        sceneType: SceneType,
        overallLuminance: Float,
        hdrActive: Boolean,
        logActive: Boolean,
        rawActive: Boolean,
        faceLockConfidence: Float,
        faceEvBias: Float
    ): Float {
        val normalizedLuma = overallLuminance.coerceIn(0f, 1f)
        val cinemaMonitoringActive = hdrActive || logActive || rawActive
        val shadowNeed = ((0.42f - normalizedLuma) / 0.42f).coerceIn(0f, 1f)
        val formatLift = when {
            rawActive -> 0.24f
            logActive -> 0.18f
            hdrActive -> 0.06f
            else -> 0f
        }

        val sceneLift = when (sceneType) {
            SceneType.BACKLIGHT ->
                if (hdrActive) 0.22f + shadowNeed * 0.22f else 0.30f + shadowNeed * 0.32f
            SceneType.NIGHT_EXTREME,
            SceneType.ASTRO -> 0.34f + shadowNeed * 0.26f
            SceneType.NIGHT,
            SceneType.PORTRAIT_LOW_LIGHT,
            SceneType.CONCERT,
            SceneType.BLUE_HOUR -> 0.20f + shadowNeed * 0.18f
            SceneType.INDOOR -> if (normalizedLuma < 0.20f) 0.12f + shadowNeed * 0.10f else 0f
            SceneType.BRIGHT_EXTREME ->
                if (hdrActive) {
                    -0.06f + shadowNeed * 0.06f
                } else if (cinemaMonitoringActive) {
                    0.04f + shadowNeed * 0.08f
                } else {
                    -0.18f + shadowNeed * 0.08f
                }
            SceneType.BRIGHT -> when {
                hdrActive && normalizedLuma > 0.72f -> -0.04f
                cinemaMonitoringActive && normalizedLuma < 0.45f -> 0.05f
                else -> 0f
            }
            else -> 0f
        }

        val highlightGuard = when {
            hdrActive && normalizedLuma > 0.82f -> -0.16f
            hdrActive && normalizedLuma > 0.72f -> -0.10f
            hdrActive &&
                normalizedLuma > 0.62f &&
                (sceneType == SceneType.BRIGHT || sceneType == SceneType.BRIGHT_EXTREME) -> -0.06f
            cinemaMonitoringActive -> 0f
            normalizedLuma > 0.82f -> -0.10f
            normalizedLuma > 0.72f -> -0.05f
            else -> 0f
        }

        val sceneBias = (sceneLift + formatLift + highlightGuard).coerceIn(
            when {
                hdrActive -> -0.28f
                cinemaMonitoringActive -> 0f
                else -> -0.35f
            },
            when {
                rawActive -> 0.90f
                logActive -> 0.82f
                hdrActive -> 0.55f
                else -> 0.60f
            }
        )

        if (faceLockConfidence <= 0.55f) {
            return sceneBias
        }

        val sceneAssist = sceneBias.coerceAtLeast(0f) * 0.22f
        return (faceEvBias + sceneAssist).coerceIn(-2f, 2f)
    }
}
