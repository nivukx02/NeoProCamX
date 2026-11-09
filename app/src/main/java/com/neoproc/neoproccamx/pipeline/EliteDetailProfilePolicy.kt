package com.neoproc.neoproccamx.pipeline

/**
 * Single source of truth for the three user-visible detail profiles.
 *
 * The values are intentionally bounded. They raise native-texture contrast while the downstream
 * scene/noise, halo, moire and skin guards remain authoritative; none of the profiles enables fake
 * resolution, pixel duplication or unrestricted global sharpening.
 */
internal data class EliteDetailProfileTuning(
    val recordMultiplier: Float,
    val recordAdditiveLift: Float,
    val stillDenoise: Float,
    val stillSharpen: Float,
    val stillMicroContrast: Float,
    val stillResidualChromaSuppression: Float,
    val conversionCleanSharpen: Float,
    val conversionNoisySharpen: Float
)

internal object EliteDetailProfilePolicy {
    fun resolve(profile: VideoDetailProfile): EliteDetailProfileTuning = when (profile) {
        VideoDetailProfile.NATURAL_DETAIL -> EliteDetailProfileTuning(
            recordMultiplier = 1.00f,
            recordAdditiveLift = 0.00f,
            stillDenoise = 0.18f,
            stillSharpen = 0.16f,
            stillMicroContrast = 0.10f,
            stillResidualChromaSuppression = 0.08f,
            conversionCleanSharpen = 0.36f,
            conversionNoisySharpen = 0.22f
        )
        VideoDetailProfile.FOCUSED_DETAIL -> EliteDetailProfileTuning(
            recordMultiplier = 1.08f,
            recordAdditiveLift = 0.02f,
            stillDenoise = 0.20f,
            stillSharpen = 0.24f,
            stillMicroContrast = 0.14f,
            stillResidualChromaSuppression = 0.10f,
            conversionCleanSharpen = 0.52f,
            conversionNoisySharpen = 0.30f
        )
        VideoDetailProfile.EXTREME_DETAIL -> EliteDetailProfileTuning(
            recordMultiplier = 1.16f,
            recordAdditiveLift = 0.05f,
            stillDenoise = 0.22f,
            stillSharpen = 0.32f,
            stillMicroContrast = 0.18f,
            stillResidualChromaSuppression = 0.12f,
            conversionCleanSharpen = 0.66f,
            conversionNoisySharpen = 0.38f
        )
    }
}
