package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import android.os.Build
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal data class CameraCineProcessingOverrides(
    val edgeMode: Int,
    val noiseReductionMode: Int,
    val aberrationMode: Int?
)

internal data class CameraBaseProcessingOverrides(
    val edgeMode: Int,
    val noiseReductionMode: Int
)

internal data class Camera2RecordingNoiseCleanupOverrides(
    val edgeMode: Int?,
    val noiseReductionMode: Int?,
    val hotPixelMode: Int?,
    val shadingMode: Int?,
    val tonemapMode: Int?,
    val aberrationMode: Int?,
    val distortionCorrectionMode: Int?
)

internal object Camera2RecordingNoiseCleanupPolicy {
    fun resolve(
        profile: Camera2NoiseCleanupProfile,
        androidApiLevel: Int = Build.VERSION.SDK_INT,
        availability: IspBypassAvailability? = null
    ): Camera2RecordingNoiseCleanupOverrides? = when (profile) {
        Camera2NoiseCleanupProfile.PREVIEW -> null
        Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT -> {
            val modes = availability
            Camera2RecordingNoiseCleanupOverrides(
                edgeMode = bestSupportedMode(
                    preferred = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
                    fallbacks = intArrayOf(),
                    availableModes = modes?.edgeModes
                ),
                noiseReductionMode = bestSupportedMode(
                    preferred = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                    fallbacks = intArrayOf(),
                    availableModes = modes?.noiseReductionModes
                ),
                hotPixelMode = bestSupportedMode(
                    preferred = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
                    fallbacks = intArrayOf(),
                    availableModes = modes?.hotPixelModes
                ),
                shadingMode = bestSupportedMode(
                    preferred = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
                    fallbacks = intArrayOf(),
                    availableModes = modes?.shadingModes
                ),
                tonemapMode = bestSupportedMode(
                    preferred = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
                    fallbacks = intArrayOf(),
                    availableModes = modes?.tonemapModes
                ),
                aberrationMode = if (androidApiLevel >= Build.VERSION_CODES.M) {
                    bestSupportedMode(
                        preferred = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
                        fallbacks = intArrayOf(),
                        availableModes = modes?.colorAberrationModes
                    )
                } else {
                    null
                },
                distortionCorrectionMode = if (androidApiLevel >= Build.VERSION_CODES.P) {
                    bestSupportedMode(
                        preferred = CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
                        fallbacks = intArrayOf(),
                        availableModes = modes?.distortionCorrectionModes
                    )
                } else {
                    null
                }
            )
        }
        Camera2NoiseCleanupProfile.RAW_SENSOR_TRUTHFUL -> Camera2RecordingNoiseCleanupOverrides(
            edgeMode = bestSupportedMode(
                preferred = CaptureRequest.EDGE_MODE_OFF,
                fallbacks = intArrayOf(),
                availableModes = availability?.edgeModes
            ),
            noiseReductionMode = bestSupportedMode(
                preferred = CaptureRequest.NOISE_REDUCTION_MODE_OFF,
                fallbacks = intArrayOf(),
                availableModes = availability?.noiseReductionModes
            ),
            hotPixelMode = bestSupportedMode(
                preferred = CaptureRequest.HOT_PIXEL_MODE_OFF,
                fallbacks = intArrayOf(),
                availableModes = availability?.hotPixelModes
            ),
            shadingMode = bestSupportedMode(
                preferred = CaptureRequest.SHADING_MODE_OFF,
                fallbacks = intArrayOf(),
                availableModes = availability?.shadingModes
            ),
            tonemapMode = null,
            aberrationMode = if (androidApiLevel >= Build.VERSION_CODES.M) {
                bestSupportedMode(
                    preferred = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF,
                    fallbacks = intArrayOf(),
                    availableModes = availability?.colorAberrationModes
                )
            } else {
                null
            },
            distortionCorrectionMode = if (androidApiLevel >= Build.VERSION_CODES.P) {
                bestSupportedMode(
                    preferred = CaptureRequest.DISTORTION_CORRECTION_MODE_OFF,
                    fallbacks = intArrayOf(),
                    availableModes = availability?.distortionCorrectionModes
                )
            } else {
                null
            }
        )
    }

    private fun bestSupportedMode(
        preferred: Int,
        fallbacks: IntArray,
        availableModes: List<Int>?
    ): Int? {
        if (availableModes == null) return preferred
        if (availableModes.isEmpty()) return preferred
        return sequenceOf(preferred)
            .plus(fallbacks.asSequence())
            .firstOrNull { it in availableModes }
    }
}

internal object CameraBaseProcessingPolicy {
    fun resolve(
        rawVideoCaptureEnabled: Boolean,
        noiseReductionEnabled: Boolean
    ): CameraBaseProcessingOverrides {
        return if (rawVideoCaptureEnabled) {
            CameraBaseProcessingOverrides(
                edgeMode = CaptureRequest.EDGE_MODE_OFF,
                noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_OFF
            )
        } else {
            CameraBaseProcessingOverrides(
                edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
                // Strict quality controls the default, but an explicit user OFF must stay authoritative.
                // Silently restoring NR here made the Settings switch a no-op for preview/photo requests.
                noiseReductionMode = if (noiseReductionEnabled) {
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                } else {
                    CaptureRequest.NOISE_REDUCTION_MODE_OFF
                }
            )
        }
    }
}

internal object CameraCineProcessingPolicy {
    fun resolve(
        cinemaFidelityRequest: Boolean,
        rawVideoCaptureEnabled: Boolean,
        noiseReductionEnabled: Boolean,
        isConsumerMode: Boolean = false
    ): CameraCineProcessingOverrides? {
        if (isConsumerMode) {
            return CameraCineProcessingOverrides(
                edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
                noiseReductionMode = if (noiseReductionEnabled) {
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                } else {
                    CaptureRequest.NOISE_REDUCTION_MODE_OFF
                },
                aberrationMode = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
            )
        }
        if (!HighEndQualityPolicy.enabled || !cinemaFidelityRequest) return null

        return if (rawVideoCaptureEnabled) {
            // RAW video should stay truthful, with only the minimal processing needed
            // to keep capture behavior predictable.
            CameraCineProcessingOverrides(
                edgeMode = CaptureRequest.EDGE_MODE_OFF,
                noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_OFF,
                aberrationMode = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
            )
        } else {
            // Cinema/Pro SDR keeps edge and aberration correction at high quality. Noise reduction
            // follows the explicit Settings value so OFF is never converted back into a hidden ON.
            CameraCineProcessingOverrides(
                edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
                noiseReductionMode = if (noiseReductionEnabled) {
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                } else {
                    CaptureRequest.NOISE_REDUCTION_MODE_OFF
                },
                aberrationMode = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
            )
        }
    }
}

internal object CameraRequestIspCorrectionPolicy {
    fun shouldApplyHighQualityCorrections(
        cinemaFidelityRequest: Boolean,
        rawVideoCaptureEnabled: Boolean
    ): Boolean = !rawVideoCaptureEnabled
}
