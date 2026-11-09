package com.neoproc.neoproccamx.snce

/**
 * SNCE v2.0 HVC — optional learned-model registry (the "hybrid" half of the build).
 *
 * Holds the four optional model hooks. Every field defaults to null, so the classical
 * pipeline runs unchanged until a trained binary is loaded and its hook is installed.
 * This is the contract that lets the directive's open-source models (C5, FFCC,
 * DeepLabV3+, Image-Adaptive-3DLUT, Zero-DCE++) be dropped in later with zero rework.
 *
 * The registry is a plain instance (no global state) so it can be owned by [SnceV2Engine]
 * and unit-tested. [SnceModelAssets] documents the expected asset filenames the Android
 * loader layer should look for under assets/models/snce/.
 */
class SnceModelRegistry {
    var illuminantModel: SnceIlluminantModel? = null
    var segmentationModel: SnceSegmentationModel? = null
    var adaptiveLutModel: SnceAdaptiveLutModel? = null
    var faceSkinModel: SnceFaceSkinModel? = null

    val hasIlluminantModel: Boolean get() = illuminantModel != null
    val hasSegmentationModel: Boolean get() = segmentationModel != null
    val hasAdaptiveLutModel: Boolean get() = adaptiveLutModel != null
    val hasFaceSkinModel: Boolean get() = faceSkinModel != null

    /** True when every stage is running classically (no learned model installed). */
    val isFullyClassical: Boolean
        get() = !hasIlluminantModel && !hasSegmentationModel && !hasAdaptiveLutModel && !hasFaceSkinModel

    fun summary(): String = buildString {
        append("SNCE models: ")
        append(if (hasIlluminantModel) "illuminant=on " else "illuminant=classical ")
        append(if (hasSegmentationModel) "seg=on " else "seg=mediapipe/classical ")
        append(if (hasAdaptiveLutModel) "lut=on " else "lut=analytic ")
        append(if (hasFaceSkinModel) "face=on" else "face=mediapipe/ITA")
    }
}

/**
 * Specs for the optional trained binaries (directive open-source model PDF). These files
 * are NOT shipped; drop them into assets/models/snce/ and wire the matching hook to
 * activate. Sizes/licenses are from the directive's consolidated model table.
 */
object SnceModelAssets {
    enum class Role { ILLUMINANT, SEGMENTATION, ADAPTIVE_LUT, LOW_LIGHT, FACE_SKIN }

    class ModelSpec(
        val displayName: String,
        val assetPath: String,
        val approxBytes: Long,
        val license: String,
        val source: String,
        val role: Role,
        /** When true the hook works with no shipped file (pure algorithm / bundled SDK). */
        val algorithmic: Boolean = false
    )

    val EXPECTED: List<ModelSpec> = listOf(
        ModelSpec(
            "C5 Cross-Camera Color Constancy", "models/snce/c5_illuminant_int8.tflite",
            1_300_000, "Apache-2.0", "github.com/mahmoudnafifi/C5", Role.ILLUMINANT
        ),
        ModelSpec(
            "FFCC Fast Fourier Color Constancy", "models/snce/ffcc_illuminant_int8.tflite",
            800_000, "Apache-2.0", "github.com/google/ffcc", Role.ILLUMINANT
        ),
        ModelSpec(
            "DeepLabV3+ 15-class", "models/snce/deeplabv3plus_15class_int8.tflite",
            11_500_000, "Apache-2.0", "tensorflow/models/research/deeplab", Role.SEGMENTATION
        ),
        ModelSpec(
            "Image-Adaptive-3DLUT", "models/snce/image_adaptive_3dlut_fp16.tflite",
            6_500_000, "MIT", "github.com/HuiZeng/Image-Adaptive-3DLUT", Role.ADAPTIVE_LUT
        ),
        ModelSpec(
            "Zero-DCE++ low-light", "models/snce/zero_dce_plus_fp16.tflite",
            1_100_000, "MIT", "github.com/Li-Chongyi/Zero-DCE", Role.LOW_LIGHT
        ),
        ModelSpec(
            "MediaPipe BlazeFace + Face Mesh", "(bundled with Play Services)",
            0, "Apache-2.0", "google-ai-edge/mediapipe", Role.FACE_SKIN, algorithmic = true
        )
    )

    /** Total APK asset impact if every shippable binary is added. */
    fun totalShippedBytes(): Long = EXPECTED.filterNot { it.algorithmic }.sumOf { it.approxBytes }
}
