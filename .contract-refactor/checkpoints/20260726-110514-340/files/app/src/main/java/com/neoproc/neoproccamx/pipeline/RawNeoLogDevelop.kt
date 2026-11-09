package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.color.NeoGamutCat16Math
import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.rendering.XYZ_D50_TO_ACESCG

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
        outputBitDepth: Int = 10
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
            outputBitDepth = outputBitDepth
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
        outputBitDepth: Int
    ): DevelopedFrame {
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        require(rawSamples.size >= width * height) { "rawSamples too small for ${width}x$height" }
        require(sensorToAcescg.size == 9) { "sensorToAcescg must be a row-major 3x3" }

        // 1. Black-subtract + normalize + per-CFA white-balance (WB-before-demosaic), into a mono plane.
        val mono = FloatArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val lin = RawExposureNormalizationPolicy.sceneLinear(rawSamples[row + x], blackLevel, whiteLevel)
                mono[row + x] = lin * wbGainAt(x, y, bayerValue, wbR, wbGr, wbGb, wbB)
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
            sensorToAcescg = sensorToAcescg
        )
        // 3. shared elite-detail + exposure + NeoLog encode + quantize once.
        return finishFromAcescg(
            acescg = acescg,
            width = width,
            height = height,
            variant = variant,
            exposureStops = exposureStops,
            outputBitDepth = outputBitDepth
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
            sensorToAcescg = sensorToAcescg
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
        sensorToAcescg: FloatArray
    ): FloatArray {
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        require(rawSamples.size >= width * height) { "rawSamples too small for ${width}x$height" }
        require(sensorToAcescg.size == 9) { "sensorToAcescg must be a row-major 3x3" }

        val mono = FloatArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val lin = RawExposureNormalizationPolicy.sceneLinear(rawSamples[row + x], blackLevel, whiteLevel)
                mono[row + x] = lin * wbGainAt(x, y, bayerValue, wbR, wbGr, wbGb, wbB)
            }
        }
        return developAcescgWithPreparedMono(mono, width, height, bayerValue, sensorToAcescg)
    }

    private fun developAcescgWithPreparedMono(
        mono: FloatArray,
        width: Int,
        height: Int,
        bayerValue: Int,
        sensorToAcescg: FloatArray
    ): FloatArray {
        val m0 = sensorToAcescg[0]; val m1 = sensorToAcescg[1]; val m2 = sensorToAcescg[2]
        val m3 = sensorToAcescg[3]; val m4 = sensorToAcescg[4]; val m5 = sensorToAcescg[5]
        val m6 = sensorToAcescg[6]; val m7 = sensorToAcescg[7]; val m8 = sensorToAcescg[8]
        val acescg = FloatArray(width * height * 3)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val r = demosaicChannel(mono, x, y, width, height, bayerValue, 0)
                val g = demosaicChannel(mono, x, y, width, height, bayerValue, 1)
                val b = demosaicChannel(mono, x, y, width, height, bayerValue, 2)
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
        outputBitDepth: Int = 10
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
        RawDevelopEliteDetailPolicy.enhanceInPlace(acescg, width, height)
        val expScale = RawExposureNormalizationPolicy.exposureScale(exposureStops)
        val maxCode = (1 shl outputBitDepth) - 1
        val out = ShortArray(count)
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
    private fun cfaChannel(x: Int, y: Int, bayer: Int): Int {
        val col = x and 1
        val rowOdd = (y and 1) == 1
        return when (bayer) {
            0 -> if (!rowOdd) { if (col == 0) 0 else 1 } else { if (col == 0) 1 else 2 }   // RGGB
            1 -> if (!rowOdd) { if (col == 0) 1 else 0 } else { if (col == 0) 2 else 1 }   // GRBG
            2 -> if (!rowOdd) { if (col == 0) 1 else 2 } else { if (col == 0) 0 else 1 }   // GBRG
            else -> if (!rowOdd) { if (col == 0) 2 else 1 } else { if (col == 0) 1 else 0 } // BGGR
        }
    }

    /** White-balance gain for the CFA channel at (x,y); greens split Gr (red row) / Gb (blue row). */
    private fun wbGainAt(x: Int, y: Int, bayer: Int, wbR: Float, wbGr: Float, wbGb: Float, wbB: Float): Float =
        when (cfaChannel(x, y, bayer)) {
            0 -> wbR
            2 -> wbB
            else -> {
                // The green's row also holds either R (→ Gr) or B (→ Gb): inspect the horizontal partner.
                val partnerX = if ((x and 1) == 0) x + 1 else x - 1
                if (cfaChannel(partnerX, y, bayer) == 0) wbGr else wbGb
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
