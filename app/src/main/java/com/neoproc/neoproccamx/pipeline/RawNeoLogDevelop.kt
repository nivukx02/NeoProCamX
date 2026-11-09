package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.color.NeoGamutCat16Math
import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.rendering.RawLensShadingGridPolicy
import com.neoproc.neoproccamx.rendering.XYZ_D50_TO_ACESCG
import kotlin.math.abs

/**
 * RawNeoLogDevelop — the single-frame, patent-safe RAW → NeoLog develop core (Tier C: portable CPU).
 *
 * This is the reference develop MATH for the offline RAW→10-bit-LOG converter. It is deliberately
 * SINGLE-FRAME (no multi-frame merge / temporal accumulation / exposure fusion / wavelet denoise —
 * those are the MotionCam-claimable methods, kept out) and reuses the project's own golden-locked
 * [NeoLogCurve] (N-LOG v5 APEX-LOG). It runs in pure Kotlin so it (a) is the UNIVERSAL fallback that
 * develops on ANY device — the "scale to all mobile" tier — and (b) is fully JVM-unit-testable, which
 * locks the correct look. Capable devices accelerate the SAME math on Vulkan/GLES (a device-verified
 * perf tier); because conversion is offline, the CPU path preserves full quality (float develop,
 * quantize once at the end), only trading speed.
 *
 * Pipeline (per frame):
 *   sensor mosaic (0..whiteLevel)
 *     → black-subtract + normalize to scene-linear   [RawExposureNormalizationPolicy]
 *     → per-CFA white-balance (WB-before-demosaic)
 *     → bilinear demosaic → linear sensor RGB
 *     → sensor→XYZ(D50)→ACEScg colour matrix
 *     → exposure compensation (stops)
 *     → NeoLogCurve.encode(variant) + quantize to N-bit (once, at the end)
 *   → interleaved N-bit RGB codes.
 */
object RawNeoLogDevelop {

    /**
     * Reusable full-frame workspace for repeated same-resolution develops (for example private-master
     * playback). A returned [DevelopedFrame.rgb] is owned by this workspace and must be consumed before
     * the next develop call using the same instance. The default null path retains independent arrays.
     */
    class ReusableWorkspace {
        private var mono = FloatArray(0)
        private var acescg = FloatArray(0)
        private var rgb = ShortArray(0)
        internal val detail = RawDevelopEliteDetailPolicy.ReusableWorkspace()

        internal fun mono(size: Int): FloatArray =
            mono.takeIf { it.size == size } ?: FloatArray(size).also { mono = it }

        internal fun acescg(size: Int): FloatArray =
            acescg.takeIf { it.size == size } ?: FloatArray(size).also { acescg = it }

        internal fun rgb(size: Int): ShortArray =
            rgb.takeIf { it.size == size } ?: ShortArray(size).also { rgb = it }
    }

    /** Developed frame: interleaved [rgb] codes (3 per pixel), each in 0..(2^bitDepth − 1). */
    data class DevelopedFrame(
        val width: Int,
        val height: Int,
        val bitDepth: Int,
        val rgb: ShortArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DevelopedFrame) return false
            return width == other.width && height == other.height &&
                bitDepth == other.bitDepth && rgb.contentEquals(other.rgb)
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + bitDepth
            result = 31 * result + rgb.contentHashCode()
            return result
        }
    }

    /**
     * Develop one RAW frame using its per-clip [RawDevelopMetadata] sidecar (from the recorder).
     * Composes sensor→ACEScg = [XYZ_D50_TO_ACESCG] · meta.sensorToXyz.
     */
    fun develop(
        rawSamples: IntArray,
        meta: RawDevelopMetadata,
        variant: NeoLogCurve.Variant = NeoLogCurve.Variant.BASE,
        exposureStops: Float = RawExposureNormalizationPolicy.DEFAULT_EXPOSURE_STOPS,
        outputBitDepth: Int = 10,
        workspace: ReusableWorkspace? = null
    ): DevelopedFrame {
        val sensorToAcescg = NeoGamutCat16Math.multiply3x3(XYZ_D50_TO_ACESCG, meta.sensorToXyz)
        return developWithMatrix(
            rawSamples = rawSamples,
            width = meta.width,
            height = meta.height,
            blackLevel = meta.blackLevel,
            whiteLevel = meta.whiteLevel,
            bayerValue = meta.bayerPatternValue,
            wbR = meta.whiteBalanceR,
            wbGr = meta.whiteBalanceGr,
            wbGb = meta.whiteBalanceGb,
            wbB = meta.whiteBalanceB,
            sensorToAcescg = sensorToAcescg,
            variant = variant,
            exposureStops = exposureStops,
            outputBitDepth = outputBitDepth,
            workspace = workspace,
            blackLevelRggb = meta.blackLevelRggb,
            lensShadingGridRggb = meta.lensShadingGridRggb,
            lensShadingCorrectionEnabled = meta.lensShadingCorrectionEnabled
        )
    }

    /**
     * Develop core with an explicit row-major 3×3 sensor→ACEScg matrix. Exposed for testing with a
     * known (e.g. identity) colour transform so the develop math can be golden-locked independently
     * of the per-camera matrix.
     */
    internal fun developWithMatrix(
        rawSamples: IntArray,
        width: Int,
        height: Int,
        blackLevel: Int,
        whiteLevel: Int,
        bayerValue: Int,
        wbR: Float,
        wbGr: Float,
        wbGb: Float,
        wbB: Float,
        sensorToAcescg: FloatArray,
        variant: NeoLogCurve.Variant,
        exposureStops: Float,
        outputBitDepth: Int,
        workspace: ReusableWorkspace? = null,
        blackLevelRggb: FloatArray = FloatArray(4) { blackLevel.toFloat() },
        lensShadingGridRggb: FloatArray = RawLensShadingGridPolicy.identityGrid(),
        lensShadingCorrectionEnabled: Boolean = false
    ): DevelopedFrame {
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        require(rawSamples.size >= width * height) { "rawSamples too small for ${width}x$height" }
        require(sensorToAcescg.size == 9) { "sensorToAcescg must be a row-major 3x3" }
        require(blackLevelRggb.size == 4) { "blackLevelRggb must contain R, Gr, Gb, B" }
        require(lensShadingGridRggb.size == RawLensShadingGridPolicy.FLOAT_COUNT) {
            "lensShadingGridRggb must contain a 3x3 RGGB grid"
        }

        // 1. Black-subtract + normalize + per-CFA white-balance (WB-before-demosaic), into a mono plane.
        val pixelCount = width * height
        val mono = workspace?.mono(pixelCount) ?: FloatArray(pixelCount)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val channel = cfaIndex(x, y, bayerValue)
                val lin = calibratedSceneLinear(rawSamples[row + x], blackLevelRggb[channel], whiteLevel)
                mono[row + x] = lin * wbGainForChannel(channel, wbR, wbGr, wbGb, wbB)
            }
        }

        // 2. bilinear demosaic → linear sensor RGB → sensor→ACEScg matrix, into a full-frame LINEAR ACEScg
        // plane (BEFORE exposure). Handing this plane to finishFromAcescg unifies the CPU develop with the
        // GPU demosaic+finish tier and lets the single shared grade-safe elite-detail + encode tail run on
        // whichever plane was produced. finishFromAcescg computes quantize(acescg * expScale) with a
        // deterministic ~1-LSB TPDF dither (anti-banding); developWithMatrix and the GPU-demosaic tier
        // share it, so both still agree byte-for-byte (the dither is a pure function of sample position).
        val acescg = developAcescgWithPreparedMono(
            mono = mono,
            width = width,
            height = height,
            bayerValue = bayerValue,
            sensorToAcescg = sensorToAcescg,
            output = workspace?.acescg(pixelCount * 3),
            lensShadingGridRggb = lensShadingGridRggb,
            lensShadingCorrectionEnabled = lensShadingCorrectionEnabled
        )
        // 3. shared elite-detail + exposure + NeoLog encode + quantize once.
        return finishFromAcescg(
            acescg = acescg,
            width = width,
            height = height,
            variant = variant,
            exposureStops = exposureStops,
            outputBitDepth = outputBitDepth,
            workspace = workspace
        )
    }

    /**
     * Develop one RAW frame up to the shared LINEAR ACEScg seam without final NeoLog quantization.
     *
     * This is used by the offline MOV converter to pack directly from ACEScg into r210/v210 and avoid
     * materialising a second full-frame 10-bit RGB ShortArray. The math up to this seam is identical to
     * [develop], and the converter then calls the same elite-detail + NeoLog quantizer used by
     * [finishFromAcescg], so output pixels stay unchanged while peak heap is lower.
     */
    fun developToAcescg(
        rawSamples: IntArray,
        meta: RawDevelopMetadata
    ): FloatArray {
        val sensorToAcescg = NeoGamutCat16Math.multiply3x3(XYZ_D50_TO_ACESCG, meta.sensorToXyz)
        return developAcescgWithMatrix(
            rawSamples = rawSamples,
            width = meta.width,
            height = meta.height,
            blackLevel = meta.blackLevel,
            whiteLevel = meta.whiteLevel,
            bayerValue = meta.bayerPatternValue,
            wbR = meta.whiteBalanceR,
            wbGr = meta.whiteBalanceGr,
            wbGb = meta.whiteBalanceGb,
            wbB = meta.whiteBalanceB,
            sensorToAcescg = sensorToAcescg,
            blackLevelRggb = meta.blackLevelRggb,
            lensShadingGridRggb = meta.lensShadingGridRggb,
            lensShadingCorrectionEnabled = meta.lensShadingCorrectionEnabled
        )
    }

    internal fun developAcescgWithMatrix(
        rawSamples: IntArray,
        width: Int,
        height: Int,
        blackLevel: Int,
        whiteLevel: Int,
        bayerValue: Int,
        wbR: Float,
        wbGr: Float,
        wbGb: Float,
        wbB: Float,
        sensorToAcescg: FloatArray,
        blackLevelRggb: FloatArray = FloatArray(4) { blackLevel.toFloat() },
        lensShadingGridRggb: FloatArray = RawLensShadingGridPolicy.identityGrid(),
        lensShadingCorrectionEnabled: Boolean = false
    ): FloatArray {
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        require(rawSamples.size >= width * height) { "rawSamples too small for ${width}x$height" }
        require(sensorToAcescg.size == 9) { "sensorToAcescg must be a row-major 3x3" }
        require(blackLevelRggb.size == 4) { "blackLevelRggb must contain R, Gr, Gb, B" }
        require(lensShadingGridRggb.size == RawLensShadingGridPolicy.FLOAT_COUNT) {
            "lensShadingGridRggb must contain a 3x3 RGGB grid"
        }

        val mono = FloatArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val channel = cfaIndex(x, y, bayerValue)
                val lin = calibratedSceneLinear(rawSamples[row + x], blackLevelRggb[channel], whiteLevel)
                mono[row + x] = lin * wbGainForChannel(channel, wbR, wbGr, wbGb, wbB)
            }
        }
        return developAcescgWithPreparedMono(
            mono = mono,
            width = width,
            height = height,
            bayerValue = bayerValue,
            sensorToAcescg = sensorToAcescg,
            lensShadingGridRggb = lensShadingGridRggb,
            lensShadingCorrectionEnabled = lensShadingCorrectionEnabled
        )
    }

    private fun developAcescgWithPreparedMono(
        mono: FloatArray,
        width: Int,
        height: Int,
        bayerValue: Int,
        sensorToAcescg: FloatArray,
        output: FloatArray? = null,
        lensShadingGridRggb: FloatArray = RawLensShadingGridPolicy.identityGrid(),
        lensShadingCorrectionEnabled: Boolean = false
    ): FloatArray {
        val m0 = sensorToAcescg[0]; val m1 = sensorToAcescg[1]; val m2 = sensorToAcescg[2]
        val m3 = sensorToAcescg[3]; val m4 = sensorToAcescg[4]; val m5 = sensorToAcescg[5]
        val m6 = sensorToAcescg[6]; val m7 = sensorToAcescg[7]; val m8 = sensorToAcescg[8]
        val outputSize = width * height * 3
        val acescg = output?.takeIf { it.size == outputSize } ?: FloatArray(outputSize)
        // Reuse one tiny vector for the whole frame. Allocating an array for every pixel would turn a
        // 12 MP offline develop into millions of short-lived objects and can cause avoidable GC stalls.
        val shading = if (lensShadingCorrectionEnabled) FloatArray(3) else IDENTITY_RGB
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (lensShadingCorrectionEnabled) {
                    lensShadingRgb(x, y, width, height, lensShadingGridRggb, shading)
                }
                val r = demosaicChannel(mono, x, y, width, height, bayerValue, 0) * shading[0]
                val g = demosaicChannel(mono, x, y, width, height, bayerValue, 1) * shading[1]
                val b = demosaicChannel(mono, x, y, width, height, bayerValue, 2) * shading[2]
                val o = (row + x) * 3
                acescg[o] = m0 * r + m1 * g + m2 * b
                acescg[o + 1] = m3 * r + m4 * g + m5 * b
                acescg[o + 2] = m6 * r + m7 * g + m8 * b
            }
        }
        return acescg
    }

    /**
     * Develop TAIL — exposure compensation + NeoLog encode + quantize — applied to an already
     * sensor→ACEScg-demosaiced RGB plane ([acescg], interleaved r,g,b per pixel, LINEAR ACEScg, BEFORE
     * exposure). This is byte-for-byte the same math [developWithMatrix] runs after its demosaic+matrix
     * (`NeoLogCurve.quantize(acescg * expScale, …)`), so a GPU that produces the ACEScg plane can be
     * finished on the CPU and will differ from the full CPU develop ONLY in the demosaic — which is
     * exactly what the offline converter's first-frame parity gate measures. Pure + testable.
     */
    fun finishFromAcescg(
        acescg: FloatArray,
        width: Int,
        height: Int,
        variant: NeoLogCurve.Variant = NeoLogCurve.Variant.BASE,
        exposureStops: Float = RawExposureNormalizationPolicy.DEFAULT_EXPOSURE_STOPS,
        outputBitDepth: Int = 10,
        workspace: ReusableWorkspace? = null
    ): DevelopedFrame {
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        val count = width * height * 3
        require(acescg.size >= count) { "acescg plane too small for ${width}x$height (need $count)" }
        // Grade-safe elite detail (edge-aware denoise + detail sharpen + OmniClarity clarity) on the LINEAR
        // ACEScg plane, BEFORE exposure + NeoLog encode. It runs here — the seam shared by the CPU develop
        // AND the GPU-demosaic+CPU-finish tier — so both tiers get identical detail and the offline
        // converter's CPU/GPU parity gate (demosaic-only difference) still holds. It is DETAIL-ONLY (no
        // creative colour), keeping the LOG flat/gradeable. No-op (no mutation, byte-identical develop) when
        // RawDevelopEliteDetailFlag is OFF.
        RawDevelopEliteDetailPolicy.enhanceInPlace(acescg, width, height, workspace?.detail)
        val expScale = RawExposureNormalizationPolicy.exposureScale(exposureStops)
        val maxCode = (1 shl outputBitDepth) - 1
        val out = workspace?.rgb(count) ?: ShortArray(count)
        for (i in 0 until count) {
            // m5: ~1-LSB triangular (TPDF) dither breaks 10-bit quantiser banding in denoised flats — the
            // grade-safe bilateral above strips the fine grain that would otherwise self-dither the quantiser,
            // so smooth regions (skies; the thin-mid HIGHLIGHT variant) band once the LOG is stretched. Only
            // the INTERIOR is dithered: clipped black (0) and white (maxCode) stay exactly on the rail so true
            // blacks/highlights keep a clean floor/ceiling (no lifted noise). DETERMINISTIC per sample ⇒ the
            // develop stays byte-reproducible AND the CPU-vs-GPU-demosaic parity gate is unaffected (both tiers
            // share this finish and get the identical dither). QUANTIZE_DITHER_LSB = 0f restores exact output.
            val base = NeoLogCurve.quantize(acescg[i] * expScale, variant, outputBitDepth)
            out[i] = if (base in 1 until maxCode) {
                NeoLogCurve.quantize(
                    acescg[i] * expScale, variant, outputBitDepth, QUANTIZE_DITHER_LSB * tpdfUnit(i)
                ).toShort()
            } else {
                base.toShort()
            }
        }
        return DevelopedFrame(width, height, outputBitDepth, out)
    }

    /** ~1-LSB TPDF dither amplitude for the final quantiser (set 0f to disable → exact pre-dither output). */
    private const val QUANTIZE_DITHER_LSB = 0.5f

    /**
     * Deterministic triangular-PDF dither in (-1, 1) for interleaved sample [index]: the difference of two
     * decorrelated hashed uniforms. Pure function of position ⇒ the same input frame always yields the same
     * output bytes (no RNG state; reproducible and CPU/GPU-parity-safe). quantize() clamps the scaled result
     * to ±0.5 code, so this can only nudge a sample by at most one 10-bit step — imperceptible, never noise.
     */
    private fun tpdfUnit(index: Int): Float = hashUnit(index * 2) - hashUnit(index * 2 + 1)

    /** Integer avalanche hash → uniform float in [0, 1) (top 24 bits → float mantissa). */
    private fun hashUnit(n: Int): Float {
        var h = n * -0x61c88647            // 0x9E3779B9
        h = h xor (h ushr 16)
        h *= -0x7ee3623b                   // 0x811C9DC5 (odd avalanche multiplier)
        h = h xor (h ushr 13)
        h *= -0x3361d2af                   // 0xCC9E2D51 (odd avalanche multiplier)
        h = h xor (h ushr 16)
        return (h ushr 8).toFloat() / (1 shl 24).toFloat()
    }

    // ── CFA helpers ──────────────────────────────────────────────────────────────

    /** CFA channel at (x,y) for the Bayer nativeValue: 0=R, 1=G, 2=B. RGGB=0/GRBG=1/GBRG=2/BGGR=3. */
    /**
     * Sparse CPU reference for the offline Vulkan parity gate. Coordinates are x/y pairs and the result
     * is interleaved ACEScg RGB. It evaluates the same calibrated bilinear reference math directly from
     * the RAW mosaic, avoiding full-resolution CPU mono and ACEScg allocations beside GPU output.
     */
    internal fun developAcescgSamples(
        rawSamples: IntArray,
        meta: RawDevelopMetadata,
        coordinates: IntArray
    ): FloatArray {
        require(coordinates.size % 2 == 0) { "sample coordinates must be x/y pairs" }
        require(rawSamples.size >= Math.multiplyExact(meta.width, meta.height)) {
            "rawSamples too small for ${meta.width}x${meta.height}"
        }
        val sensorToAcescg = NeoGamutCat16Math.multiply3x3(XYZ_D50_TO_ACESCG, meta.sensorToXyz)
        val output = FloatArray(coordinates.size / 2 * 3)
        val shading = FloatArray(3)
        var outputOffset = 0
        var coordinateOffset = 0
        while (coordinateOffset < coordinates.size) {
            val x = coordinates[coordinateOffset].coerceIn(0, meta.width - 1)
            val y = coordinates[coordinateOffset + 1].coerceIn(0, meta.height - 1)
            if (meta.lensShadingCorrectionEnabled) {
                lensShadingRgb(x, y, meta.width, meta.height, meta.lensShadingGridRggb, shading)
            } else {
                shading[0] = 1f
                shading[1] = 1f
                shading[2] = 1f
            }
            val r = demosaicRawSample(rawSamples, meta, x, y, 0) * shading[0]
            val g = demosaicRawSample(rawSamples, meta, x, y, 1) * shading[1]
            val b = demosaicRawSample(rawSamples, meta, x, y, 2) * shading[2]
            output[outputOffset] = sensorToAcescg[0] * r + sensorToAcescg[1] * g + sensorToAcescg[2] * b
            output[outputOffset + 1] = sensorToAcescg[3] * r + sensorToAcescg[4] * g + sensorToAcescg[5] * b
            output[outputOffset + 2] = sensorToAcescg[6] * r + sensorToAcescg[7] * g + sensorToAcescg[8] * b
            coordinateOffset += 2
            outputOffset += 3
        }
        return output
    }

    /**
     * Sparse CPU oracle for the Vulkan edge-directed demosaic used by offline conversion.
     *
     * The portable full-frame fallback deliberately remains bilinear, but comparing that different
     * reconstruction kernel against Vulkan made legitimate edge detail look like a colour failure. This
     * oracle mirrors `demosaic_vulkan.comp`: per-CFA calibration/WB, green-site orientation, the 5x5
     * Hamilton-Adams-style green decision at red/blue sites, diagonal opposite-colour reconstruction,
     * post-demosaic lens shading, the precomposed sensor-to-ACEScg matrix, and the shader's non-negative
     * output clamp. It evaluates only the bounded parity grid and never allocates a full frame.
     */
    internal fun developGpuReferenceAcescgSamples(
        rawSamples: IntArray,
        meta: RawDevelopMetadata,
        coordinates: IntArray
    ): FloatArray {
        require(coordinates.size % 2 == 0) { "sample coordinates must be x/y pairs" }
        require(rawSamples.size >= Math.multiplyExact(meta.width, meta.height)) {
            "rawSamples too small for ${meta.width}x${meta.height}"
        }
        val sensorToAcescg = NeoGamutCat16Math.multiply3x3(XYZ_D50_TO_ACESCG, meta.sensorToXyz)
        val output = FloatArray(coordinates.size / 2 * 3)
        val shading = FloatArray(3)
        var outputOffset = 0
        var coordinateOffset = 0
        while (coordinateOffset < coordinates.size) {
            val x = coordinates[coordinateOffset].coerceIn(0, meta.width - 1)
            val y = coordinates[coordinateOffset + 1].coerceIn(0, meta.height - 1)
            val channel = cfaIndex(x, y, meta.bayerPatternValue)
            val center = calibratedGpuReferenceSample(rawSamples, meta, x, y)
            val xM1 = calibratedGpuReferenceSample(rawSamples, meta, x - 1, y)
            val xP1 = calibratedGpuReferenceSample(rawSamples, meta, x + 1, y)
            val yM1 = calibratedGpuReferenceSample(rawSamples, meta, x, y - 1)
            val yP1 = calibratedGpuReferenceSample(rawSamples, meta, x, y + 1)

            val sensorR: Float
            val sensorG: Float
            val sensorB: Float
            if (channel == 1 || channel == 2) {
                val greenOnRedRow = channel == 1
                sensorR = if (greenOnRedRow) 0.5f * (xM1 + xP1) else 0.5f * (yM1 + yP1)
                sensorG = center
                sensorB = if (greenOnRedRow) 0.5f * (yM1 + yP1) else 0.5f * (xM1 + xP1)
            } else {
                val xM2 = calibratedGpuReferenceSample(rawSamples, meta, x - 2, y)
                val xP2 = calibratedGpuReferenceSample(rawSamples, meta, x + 2, y)
                val yM2 = calibratedGpuReferenceSample(rawSamples, meta, x, y - 2)
                val yP2 = calibratedGpuReferenceSample(rawSamples, meta, x, y + 2)
                val horizontalGradient = abs(
                    (xM2 + 2f * center + xP2) - (2f * xM1 + 2f * xP1)
                )
                val verticalGradient = abs(
                    (yM2 + 2f * center + yP2) - (2f * yM1 + 2f * yP1)
                )
                val greenHorizontal =
                    0.5f * (xM1 + xP1) + 0.25f * (2f * center - xM2 - xP2)
                val greenVertical =
                    0.5f * (yM1 + yP1) + 0.25f * (2f * center - yM2 - yP2)
                val greenAverage = 0.25f * (xM1 + xP1 + yM1 + yP1) +
                    0.125f * (4f * center - xM2 - xP2 - yM2 - yP2)
                val green = when {
                    horizontalGradient < verticalGradient -> greenHorizontal
                    verticalGradient < horizontalGradient -> greenVertical
                    else -> greenAverage
                }
                val diagonal = 0.25f * (
                    calibratedGpuReferenceSample(rawSamples, meta, x - 1, y - 1) +
                        calibratedGpuReferenceSample(rawSamples, meta, x + 1, y - 1) +
                        calibratedGpuReferenceSample(rawSamples, meta, x - 1, y + 1) +
                        calibratedGpuReferenceSample(rawSamples, meta, x + 1, y + 1)
                    ) + (center - green) * 0.5f
                sensorR = if (channel == 0) center else diagonal
                sensorG = green
                sensorB = if (channel == 3) center else diagonal
            }

            if (meta.lensShadingCorrectionEnabled) {
                lensShadingRgb(x, y, meta.width, meta.height, meta.lensShadingGridRggb, shading)
            } else {
                shading[0] = 1f
                shading[1] = 1f
                shading[2] = 1f
            }
            val r = sensorR.coerceAtLeast(0f) * shading[0]
            val g = sensorG.coerceAtLeast(0f) * shading[1]
            val b = sensorB.coerceAtLeast(0f) * shading[2]
            output[outputOffset] =
                (sensorToAcescg[0] * r + sensorToAcescg[1] * g + sensorToAcescg[2] * b).coerceAtLeast(0f)
            output[outputOffset + 1] =
                (sensorToAcescg[3] * r + sensorToAcescg[4] * g + sensorToAcescg[5] * b).coerceAtLeast(0f)
            output[outputOffset + 2] =
                (sensorToAcescg[6] * r + sensorToAcescg[7] * g + sensorToAcescg[8] * b).coerceAtLeast(0f)
            coordinateOffset += 2
            outputOffset += 3
        }
        return output
    }

    private fun calibratedGpuReferenceSample(
        rawSamples: IntArray,
        meta: RawDevelopMetadata,
        x: Int,
        y: Int
    ): Float {
        val clampedX = x.coerceIn(0, meta.width - 1)
        val clampedY = y.coerceIn(0, meta.height - 1)
        val channel = cfaIndex(clampedX, clampedY, meta.bayerPatternValue)
        return calibratedSceneLinear(
            rawSamples[clampedY * meta.width + clampedX],
            meta.blackLevelRggb[channel],
            meta.whiteLevel
        ) * wbGainForChannel(
            channel,
            meta.whiteBalanceR,
            meta.whiteBalanceGr,
            meta.whiteBalanceGb,
            meta.whiteBalanceB
        )
    }

    private fun demosaicRawSample(
        rawSamples: IntArray,
        meta: RawDevelopMetadata,
        x: Int,
        y: Int,
        channel: Int
    ): Float {
        var sum = 0f
        var count = 0
        val yLo = (y - 1).coerceAtLeast(0)
        val yHi = (y + 1).coerceAtMost(meta.height - 1)
        val xLo = (x - 1).coerceAtLeast(0)
        val xHi = (x + 1).coerceAtMost(meta.width - 1)
        for (yy in yLo..yHi) {
            val row = yy * meta.width
            for (xx in xLo..xHi) {
                val cfa = cfaIndex(xx, yy, meta.bayerPatternValue)
                if (cfaChannel(xx, yy, meta.bayerPatternValue) == channel) {
                    val linear = calibratedSceneLinear(
                        rawSamples[row + xx],
                        meta.blackLevelRggb[cfa],
                        meta.whiteLevel
                    )
                    sum += linear * wbGainForChannel(
                        cfa,
                        meta.whiteBalanceR,
                        meta.whiteBalanceGr,
                        meta.whiteBalanceGb,
                        meta.whiteBalanceB
                    )
                    count++
                }
            }
        }
        return if (count > 0) sum / count else 0f
    }

    private val IDENTITY_RGB = floatArrayOf(1f, 1f, 1f)

    /** Semantic Camera2 CFA index at (x,y): 0=R, 1=Gr, 2=Gb, 3=B. */
    private fun cfaIndex(x: Int, y: Int, bayer: Int): Int {
        val nativePattern = if (bayer in 0..3) bayer else 3
        return ((((y and 1) shl 1) or (x and 1)) xor nativePattern)
    }

    private fun wbGainForChannel(
        channel: Int,
        wbR: Float,
        wbGr: Float,
        wbGb: Float,
        wbB: Float
    ): Float = when (channel) {
        0 -> wbR
        1 -> wbGr
        2 -> wbGb
        else -> wbB
    }

    /** Black-subtract and normalize in the physical channel's own calibrated range. */
    private fun calibratedSceneLinear(sample: Int, blackLevel: Float, whiteLevel: Int): Float {
        val finiteBlack = if (blackLevel.isFinite()) blackLevel else 0f
        val denominator = (whiteLevel.toFloat() - finiteBlack).coerceAtLeast(1f)
        return ((sample.toFloat() - finiteBlack) / denominator).coerceIn(0f, 1f)
    }

    /**
     * Bilinearly interpolate the compact row-major 3x3 Camera2 lens-shading field. The grid stores
     * semantic R, Gr, Gb and B; the two physical greens are averaged after interpolation just like
     * the live Vulkan RAW demosaic.
     */
    private fun lensShadingRgb(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        gridRggb: FloatArray,
        output: FloatArray
    ) {
        val normalizedX = if (width > 1) x.toFloat() / (width - 1).toFloat() else 0f
        val normalizedY = if (height > 1) y.toFloat() / (height - 1).toFloat() else 0f
        val gridX = normalizedX.coerceIn(0f, 1f) * (RawLensShadingGridPolicy.GRID_SIDE - 1)
        val gridY = normalizedY.coerceIn(0f, 1f) * (RawLensShadingGridPolicy.GRID_SIDE - 1)
        val x0 = gridX.toInt().coerceAtMost(RawLensShadingGridPolicy.GRID_SIDE - 2)
        val y0 = gridY.toInt().coerceAtMost(RawLensShadingGridPolicy.GRID_SIDE - 2)
        val fx = gridX - x0.toFloat()
        val fy = gridY - y0.toFloat()
        val channels = RawLensShadingGridPolicy.CHANNEL_COUNT
        val i00 = (y0 * RawLensShadingGridPolicy.GRID_SIDE + x0) * channels
        val i10 = i00 + channels
        val i01 = i00 + RawLensShadingGridPolicy.GRID_SIDE * channels
        val i11 = i01 + channels

        fun gain(channel: Int): Float {
            val top = gridRggb[i00 + channel] +
                (gridRggb[i10 + channel] - gridRggb[i00 + channel]) * fx
            val bottom = gridRggb[i01 + channel] +
                (gridRggb[i11 + channel] - gridRggb[i01 + channel]) * fx
            return (top + (bottom - top) * fy).coerceIn(0.25f, 8f)
        }

        output[0] = gain(0)
        output[1] = 0.5f * (gain(1) + gain(2))
        output[2] = gain(3)
    }

    private fun cfaChannel(x: Int, y: Int, bayer: Int): Int {
        return when (cfaIndex(x, y, bayer)) {
            0 -> 0
            3 -> 2
            else -> 1
        }
    }

    /**
     * Bilinear demosaic of one channel at (x,y): the mean of all in-CFA samples of that channel within
     * the 3×3 neighbourhood (clamped to bounds). Simple, deterministic and artefact-bounded — the
     * portable reference; the GPU tiers use the edge-directed demosaic for higher quality.
     */
    private fun demosaicChannel(
        mono: FloatArray, x: Int, y: Int, width: Int, height: Int, bayer: Int, channel: Int
    ): Float {
        var sum = 0f
        var count = 0
        val yLo = (y - 1).coerceAtLeast(0)
        val yHi = (y + 1).coerceAtMost(height - 1)
        val xLo = (x - 1).coerceAtLeast(0)
        val xHi = (x + 1).coerceAtMost(width - 1)
        for (yy in yLo..yHi) {
            val row = yy * width
            for (xx in xLo..xHi) {
                if (cfaChannel(xx, yy, bayer) == channel) {
                    sum += mono[row + xx]
                    count++
                }
            }
        }
        return if (count > 0) sum / count else 0f
    }
}
