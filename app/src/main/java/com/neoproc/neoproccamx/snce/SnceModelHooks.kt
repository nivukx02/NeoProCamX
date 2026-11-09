package com.neoproc.neoproccamx.snce

/**
 * SNCE v2.0 HVC — shared frame-input types and the optional learned-model hooks
 * (the "hybrid" half of the build).
 *
 * Every learned model is OPTIONAL. When no trained .tflite is loaded the hook is
 * simply null and the classical pipeline runs unchanged — that is the contract that
 * lets a user drop the directive's open-source binaries (C5, FFCC, DeepLabV3+,
 * Image-Adaptive-3DLUT) in later with zero rework. See [SnceModelRegistry].
 *
 * Array-bearing types are deliberately plain classes (not data classes) so equality
 * stays referential and no array-equals/hashCode surprises leak into callers.
 */

/** Small linear working-RGB thumbnail used by the illuminant estimator and LUT/scene descriptor. */
class SnceThumbnail(
    val width: Int,
    val height: Int,
    /** Linear working-RGB, length width*height*3, row-major, channel-interleaved. */
    val rgb: FloatArray
) {
    init {
        require(width > 0 && height > 0) { "thumbnail dimensions must be positive" }
        require(rgb.size == width * height * 3) { "rgb length must equal width*height*3" }
    }

    fun index(x: Int, y: Int): Int = (y * width + x) * 3
    fun pixelCount(): Int = width * height
}

/** An illuminant prior expressed in working RGB plus a confidence in [0,1]. */
class SnceIlluminantPrior(val rgb: FloatArray, val confidence: Float)

/** Output of a learned illuminant model (SIEE Method 5 — C5 / FFCC). */
class SnceModelIlluminant(val rgb: FloatArray, val confidence: Float)

/** Hook: learned illuminant estimator (C5 / FFCC). Return null when no model is loaded. */
fun interface SnceIlluminantModel {
    fun estimateIlluminantRgb(thumb: SnceThumbnail): SnceModelIlluminant?
}

/** Output of a learned semantic segmenter (DeepLabV3+ 15-class). */
class SnceSegmentationResult(
    val width: Int,
    val height: Int,
    /** Per-pixel winning class index, length width*height. */
    val classMask: IntArray,
    /** Per-pixel confidence of the winning class, length width*height. */
    val confidence: FloatArray
)

/** Hook: learned semantic segmentation. Return null to fall back to MediaPipe / classical masks. */
fun interface SnceSegmentationModel {
    fun segment(thumb: SnceThumbnail): SnceSegmentationResult?
}

/** A learned per-scene 3D LUT (Image-Adaptive-3DLUT). rgb length = size^3 * 3, R-fastest. */
class SnceAdaptiveLut(val size: Int, val rgb: FloatArray) {
    init {
        require(size in 2..64) { "LUT size out of range" }
        require(rgb.size == size * size * size * 3) { "LUT rgb length must be size^3*3" }
    }
}

/** Hook: learned per-scene 3D LUT generator. Return null to use the analytic look path. */
fun interface SnceAdaptiveLutModel {
    fun generateLut(thumb: SnceThumbnail): SnceAdaptiveLut?
}

/** A detected face with the skin patch sampled in CIE Lab (for ITA skin classification). */
class SnceFace(
    /** Normalised bounding box [x, y, w, h] in [0,1]. */
    val boundingBox: FloatArray,
    val confidence: Float,
    /** Mean skin-patch colour in CIE Lab. */
    val skinLab: FloatArray
)

/** Hook: learned face / skin detector (MediaPipe BlazeFace + Face Mesh). Empty list when none. */
fun interface SnceFaceSkinModel {
    fun detect(thumb: SnceThumbnail): List<SnceFace>
}
