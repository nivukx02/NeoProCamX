package com.neoproc.neoproccamx.color

import android.content.Context
import android.util.Log
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * LUTProcessor — Industry-standard .cube LUT parser and synthetic look generator.
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * .CUBE FILE FORMAT  (standard LUT interchange)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  Keywords (one per line, order-independent):
 *    TITLE "name"           optional display name
 *    LUT_3D_SIZE N          required; N = grid points per axis (2..65)
 *    DOMAIN_MIN r g b       input domain minimum (default 0.0 0.0 0.0)
 *    DOMAIN_MAX r g b       input domain maximum (default 1.0 1.0 1.0)
 *
 *  Data lines: N³ rows of "r g b", one per line, after all keywords.
 *  Axis order: R varies fastest, B varies slowest.
 *  Entry index: idx = r + g×N + b×N²  (same as CubeLUT.get())
 *
 *  Comments: # to end of line.
 *  Blank lines: ignored.
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * DOMAIN REMAPPING
 * ══════════════════════════════════════════════════════════════════════════════
 *  If DOMAIN_MIN/MAX are non-standard (e.g. exposure-normalised LUTs use
 *  DOMAIN_MIN 0 0 0 / DOMAIN_MAX 2 2 2 for log-to-display LUTs), all data
 *  values are remapped to [0, 1] so the renderer receives normalised floats.
 *
 *  remap(v, min, max) = (v - min) / (max - min)
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * BUILT-IN LOOKS  (synthetic, no file I/O, generated at adaptive high-end LUT size)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *  TECHNICAL (use with logEnabled=true in the renderer):
 *    IDENTITY        Pass-through; confirms correct LUT pipeline end-to-end.
 *    N_LOG_TO_REC709 Decode N-Log → Rec.709 display (BT.709 OETF applied).
 *    N_LOG_TO_P3     Decode N-Log → DCI-P3 (gamma 2.6 applied).
 *
 *  CREATIVE (use with logEnabled=false for a fully-baked creative look):
 *    CINEMATIC       Orange-teal complementary grade. Skin → warm orange,
 *                    neutrals and shadows → teal. Hollywood blockbuster look.
 *    BLEACH_BYPASS   Silver-retention analogue: strong contrast, desaturated,
 *                    lifted shadow detail.
 *    WARM_VINTAGE    Lifted blacks (matte/faded), warm (amber) midtones,
 *                    slight overall desaturation.
 *    COOL_TEAL       Shadow-region pushed toward teal/cyan; slight overall
 *                    desaturation. Music-video aesthetic.
 *    FADED_FILM      Lifted blacks (0.08), compressed highlights (0.92),
 *                    reduced contrast. Low-budget film emulation.
 *    NIGHT_VISION    Phosphor-green monochrome. Maps luma → green channel.
 *    CROSS_PROCESS   E-6 slide film processed in C-41 chemistry. Oversaturated,
 *                    colour-shifted: cyan-blue shadows, yellow-green highlights.
 */
object LUTProcessor {

    private const val TAG      = "LUTProcessor"
    @Volatile private var previewFpsHint: Float = HighEndQualityPolicy.targetPreviewFps.toFloat()
    @Volatile private var recordingHint: Boolean = false

    /**
     * Optional runtime hint so built-in LUT precision follows current capture load.
     */
    fun setRuntimeQualityHint(previewFps: Float, recording: Boolean) {
        previewFpsHint = previewFps.coerceAtLeast(1f)
        recordingHint = recording
    }

    fun clearRuntimeQualityHint() {
        previewFpsHint = HighEndQualityPolicy.targetPreviewFps.toFloat()
        recordingHint = false
    }

    private fun builtInLutSize(): Int {
        val target = HighEndQualityPolicy.lutSizeForPreview(
            highFidelity = HighEndQualityPolicy.enabled,
            previewFps = previewFpsHint,
            recording = recordingHint
        )
        return target.coerceIn(17, 65)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FILE LOADING
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Load and parse a .cube LUT from a [File].
     * Runs on [Dispatchers.IO]. Returns null on any parse or I/O error.
     */
    suspend fun loadFromFile(file: File): CubeLUT? = withContext(Dispatchers.IO) {
        runCatching {
            file.inputStream().use { parseCube(it, file.name) }
        }.getOrElse {
            Log.e(TAG, "loadFromFile '${file.name}'", it); null
        }
    }

    /**
     * Load a .cube LUT bundled in the app's assets directory.
     * Example: loadFromAssets(context, "luts/cinematic.cube")
     * Returns null if the asset is missing or malformed.
     */
    suspend fun loadFromAssets(context: Context, assetPath: String): CubeLUT? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(assetPath).use { parseCube(it, assetPath) }
            }.getOrElse {
                Log.e(TAG, "loadFromAssets '$assetPath'", it); null
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // .CUBE PARSER
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse a .cube InputStream into a [CubeLUT].
     *
     * Supports:
     *  - TITLE, LUT_3D_SIZE, DOMAIN_MIN, DOMAIN_MAX
     *  - Comment lines (#)  /  blank lines (ignored)
     *  - Any grid size N ∈ [2, 65]
     *  - Non-standard DOMAIN_MIN/MAX (remapped to [0, 1])
     *  - Data lines starting with digit, '-', or '.'
     */
    fun parseCube(stream: InputStream, sourceName: String = "unnamed"): CubeLUT {
        var title     = sourceName
        var size      = 0
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        val entries   = ArrayList<FloatArray>(32 * 32 * 32)

        BufferedReader(InputStreamReader(stream)).forEachLine { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() || line.startsWith("#") -> Unit  // skip

                line.startsWith("TITLE", ignoreCase = true) ->
                    title = line.substringAfter("TITLE", "").trim().trim('"')

                line.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                    size = line.split(Regex("\\s+")).last().toIntOrNull()
                        ?: error("Bad LUT_3D_SIZE: $line")
                    require(size in 2..65) { "LUT size $size outside [2..65] in '$sourceName'" }
                }

                line.startsWith("DOMAIN_MIN", ignoreCase = true) ->
                    domainMin = parseTriple(line.substringAfter("DOMAIN_MIN").trim())

                line.startsWith("DOMAIN_MAX", ignoreCase = true) ->
                    domainMax = parseTriple(line.substringAfter("DOMAIN_MAX").trim())

                // Data line: starts with digit, minus, or period
                line[0].let { it.isDigit() || it == '-' || it == '.' } -> {
                    check(size > 0) { "Data line before LUT_3D_SIZE in '$sourceName'" }
                    entries.add(parseTriple(line))
                }
            }
        }

        val expected = size * size * size
        require(entries.size == expected) {
            "LUT '$sourceName': expected $expected entries for ${size}^3, got ${entries.size}"
        }

        // Remap entries to [0, 1] if the domain is non-standard
        val needsRemap = domainMin.any { it != 0f } || domainMax.any { it != 1f }
        val data: Array<FloatArray> = Array(entries.size) { i ->
            val e = entries[i]
            if (needsRemap) {
                floatArrayOf(
                    remap(e[0], domainMin[0], domainMax[0]).coerceIn(0f, 1f),
                    remap(e[1], domainMin[1], domainMax[1]).coerceIn(0f, 1f),
                    remap(e[2], domainMin[2], domainMax[2]).coerceIn(0f, 1f)
                )
            } else {
                floatArrayOf(
                    e[0].coerceIn(0f, 1f),
                    e[1].coerceIn(0f, 1f),
                    e[2].coerceIn(0f, 1f)
                )
            }
        }

        Log.i(TAG, "Parsed '$title': ${size}^3 (${entries.size} entries)")
        return CubeLUT(title, size, data, domainMin, domainMax)
    }

    private fun parseTriple(s: String): FloatArray {
        val parts = s.trim().split(Regex("\\s+"))
        require(parts.size >= 3) { "Expected 3 floats in: '$s'" }
        val r = parts[0].toFloat()
        val g = parts[1].toFloat()
        val b = parts[2].toFloat()
        require(r.isFinite() && g.isFinite() && b.isFinite()) { "Non-finite LUT data in: '$s'" }
        return floatArrayOf(r, g, b)
    }

    /** Normalise v from [min, max] to [0, 1]. Returns v unchanged if range is zero. */
    private fun remap(v: Float, min: Float, max: Float): Float =
        if (max > min) (v - min) / (max - min) else v

    // ─────────────────────────────────────────────────────────────────────────
    // BUILT-IN SYNTHETIC LOOKS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Create a named built-in look as a [CubeLUT] at adaptive high-end resolution.
     * The returned LUT is ready to pass directly to [GPUPreviewRenderer.uploadLUT].
     *
     * For N_LOG_TO_REC709 and N_LOG_TO_P3: enable renderer.logEnabled = true
     * so that the renderer's N-Log encode feeds into this decode LUT.
     */
    fun createBuiltIn(look: BuiltInLook): CubeLUT {
        val N = builtInLutSize()
        val data = Array(N * N * N) { idx ->
            // .cube axis order: r fastest (idx % N), b slowest (idx / N²)
            val r = idx % N
            val g = (idx / N) % N
            val b = idx / (N * N)
            val rv = r.toFloat() / (N - 1)
            val gv = g.toFloat() / (N - 1)
            val bv = b.toFloat() / (N - 1)
            look.apply(rv, gv, bv)
        }
        return CubeLUT("BuiltIn/${look.name}", N, data,
            floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// BUILT-IN LOOK ENUM
//
// Each entry defines an apply(r, g, b) function that maps one LUT grid point.
// The function is called N³ times during createBuiltIn().
// Input and output are in [0, 1].
// ═════════════════════════════════════════════════════════════════════════════

enum class BuiltInLook {

    /** 1:1 pass-through. Use to verify the LUT pipeline is wired correctly. */
    IDENTITY {
        override fun apply(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)
    },

    /**
     * N-Log → Rec.709 display transform.
     * Input: N-Log encoded signal (use with renderer.logEnabled = true).
     * Output: Rec.709 gamma-corrected display signal.
     * Implements NeoRadiance spectral EETF decode + ITU-R BT.709 OETF.
     */
    N_LOG_TO_REC709 {
        override fun apply(r: Float, g: Float, b: Float): FloatArray {
            val rl = flexCurveDecode(r); val gl = flexCurveDecode(g); val bl = flexCurveDecode(b)
            return floatArrayOf(
                rec709OETF(rl.coerceIn(0f, 1f)),
                rec709OETF(gl.coerceIn(0f, 1f)),
                rec709OETF(bl.coerceIn(0f, 1f))
            )
        }
    },

    /**
     * N-Log → DCI-P3 display transform.
     * Input: N-Log encoded signal (use with renderer.logEnabled = true).
     * Output: DCI-P3 power gamma (γ = 1/2.6).
     */
    N_LOG_TO_P3 {
        override fun apply(r: Float, g: Float, b: Float): FloatArray {
            val rl = flexCurveDecode(r); val gl = flexCurveDecode(g); val bl = flexCurveDecode(b)
            val g26 = 1f / 2.6f
            return floatArrayOf(
                rl.coerceIn(0f, 1f).pow(g26),
                gl.coerceIn(0f, 1f).pow(g26),
                bl.coerceIn(0f, 1f).pow(g26)
            )
        }
    },

    /**
     * Orange-teal complementary grade (Hollywood look).
     * Pushes warm colours (skin tones, reds, yellows) toward orange.
     * Pushes neutrals and cool colours toward teal/cyan.
     * Slight overall desaturation in midtones.
     */
    CINEMATIC {
        override fun apply(r: Float, g: Float, b: Float): FloatArray {
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            // Colour distance from grey axis — proxy for saturation
            val dist = maxOf(kotlin.math.abs(r - luma),
                             kotlin.math.abs(g - luma),
                             kotlin.math.abs(b - luma))
            // Orange-teal matrix: warm reds, cool blues, slightly greenish neutrals
            var outR = r * 1.05f - g * 0.02f - b * 0.03f
            var outG = g * 0.98f + r * 0.01f
            var outB = b * 1.12f - r * 0.05f - g * 0.03f
            // Reduce saturation in low-chroma areas (cleaner midtones)
            val mix = 0.80f + dist * 0.35f
            outR = luma + (outR - luma) * mix
            outG = luma + (outG - luma) * mix
            outB = luma + (outB - luma) * mix
            return floatArrayOf(outR.coerceIn(0f, 1f), outG.coerceIn(0f, 1f), outB.coerceIn(0f, 1f))
        }
    },

    /**
     * Silver-retention bleach bypass analogue.
     * Simulates skipping the bleach step in E-6 film processing:
     * silver remains alongside colour dyes → high contrast, desaturated, gritty.
     */
    BLEACH_BYPASS {
        override fun apply(r: Float, g: Float, b: Float): FloatArray {
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            // 60% desaturation toward luma
            val dr = r * 0.4f + luma * 0.6f
            val dg = g * 0.4f + luma * 0.6f
            val db = b * 0.4f + luma * 0.6f
            // Contrast boost: 1.5× around 0.5 pivot
            val p = 0.5f
            return floatArrayOf(
                (p + (dr - p) * 1.5f).coerceIn(0f, 1f),
                (p + (dg - p) * 1.5f).coerceIn(0f, 1f),
                (p + (db - p) * 1.5f).coerceIn(0f, 1f)
            )
        }
    },

    /**
     * Warm vintage / faded film look.
     * Lifts blacks (matte box), adds amber warmth, slight desaturation.
     * Evokes 70s Kodachrome / Fuji Velvia cross-processed look.
     */
    WARM_VINTAGE {
        override fun apply(r: Float, g: Float, b: Float): FloatArray {
            // Lift blacks and compress whites
            val lr = (r * 0.85f + 0.06f).coerceIn(0f, 1f)
            val lg = (g * 0.84f + 0.05f).coerceIn(0f, 1f)
            val lb = (b * 0.80f + 0.04f).coerceIn(0f, 1f)
            // Warm tint: slightly boost red, reduce blue
            val wr = (lr * 1.06f).coerceIn(0f, 1f)
            val wg = lg
            val wb = (lb * 0.92f).coerceIn(0f, 1f)
            // 20% desaturation
            val luma = 0.2126f * wr + 0.7152f * wg + 0.0722f * wb
            return floatArrayOf(
                (wr * 0.80f + luma * 0.20f).coerceIn(0f, 1f),
                (wg * 0.80f + luma * 0.20f).coerceIn(0f, 1f),
                (wb * 0.80f + luma * 0.20f).coerceIn(0f, 1f)
            )
        }
    },

    /**
     * Cool teal grade.
     * Shifts shadow regions toward cyan/teal. Slight overall desaturation.
     * Common in crime thrillers and music videos.
     */
    COOL_TEAL {
        override fun apply(r: Float, g: Float, b: Float): FloatArray {
            // Shadow mask: peaks in dark areas
            val shade = (1f - r) * (1f - g) * (1f - b)
            val tr = (r - shade * 0.10f).coerceIn(0f, 1f)
            val tg = (g + shade * 0.04f).coerceIn(0f, 1f)
            val tb = (b + shade * 0.12f).coerceIn(0f, 1f)
            val luma = 0.2126f * tr + 0.7152f * tg + 0.0722f * tb
            return floatArrayOf(
                (tr * 0.85f + luma * 0.15f).coerceIn(0f, 1f),
                (tg * 0.88f + luma * 0.12f).coerceIn(0f, 1f),
                (tb * 0.90f + luma * 0.10f).coerceIn(0f, 1f)
            )
        }
    },

    /**
     * Faded film / matte look.
     * Lifts blacks to 0.08, compresses highlights to 0.92.
     * Reduced contrast. Evokes unsaturated analogue film stock.
     */
    FADED_FILM {
        override fun apply(r: Float, g: Float, b: Float) = floatArrayOf(
            (r * 0.84f + 0.08f).coerceIn(0f, 1f),
            (g * 0.84f + 0.07f).coerceIn(0f, 1f),
            (b * 0.84f + 0.06f).coerceIn(0f, 1f)
        )
    },

    /**
     * Night-vision phosphor green monochrome.
     * Converts luma to a green phosphor glow (ITU-R BT.709 coefficients).
     * The slight gamma lift (^0.85) brightens midtones for a phosphor "bloom" feel.
     */
    NIGHT_VISION {
        override fun apply(r: Float, g: Float, b: Float): FloatArray {
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
            val glow = luma.pow(0.85f)  // slight lift for phosphor bloom
            return floatArrayOf(luma * 0.04f, glow, luma * 0.04f)
        }
    },

    /**
     * Cross-process (E-6 slide film in C-41 negative chemistry).
     * The wrong chemistry produces extreme colour shifts and contrast.
     * Cyan-blue shadows, yellow-green highlights, crushed midtones.
     */
    CROSS_PROCESS {
        override fun apply(r: Float, g: Float, b: Float) = floatArrayOf(
            xpR(r).coerceIn(0f, 1f),
            xpG(g).coerceIn(0f, 1f),
            xpB(b).coerceIn(0f, 1f)
        )

        // Red: shallow toe, steep shoulder (burns out reds in lights)
        private fun xpR(x: Float) = (x * x * 1.4f + x * 0.05f)

        // Green: S-shape with slight push
        private fun xpG(x: Float): Float {
            val p = 0.5f; return p + (x - p) * 1.25f + 0.04f
        }

        // Blue: steep toe (crushed blue shadows), rolls off in lights
        private fun xpB(x: Float) = (x * 1.45f - x * x * 0.55f)
    };

    /** Apply this look to a single LUT grid point (r, g, b) ∈ [0, 1]. */
    abstract fun apply(r: Float, g: Float, b: Float): FloatArray
}

// ─────────────────────────────────────────────────────────────────────────────
// SHARED MATH HELPERS (file-private)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * N-Log monitor decode: encoded signal → linear scene-referred light.
 * Inverse of the N-Log preview encoder in the fragment shader.
 *
 * Two-region inverse:
 *   If x < 171.2102946929/1023 (encoded black clip):
 *     linear = (x × 1023 - 95) × 0.01125 / (171.2102946929 - 95)
 *   Else:
 *     linear = 10^((x × 1023 - 420) / 261.5) × 0.19 - 0.01
 */
private fun flexCurveDecode(x: Float): Float {
    val knee = 171.2102946929f / 1023f
    return if (x < knee) {
        ((x * 1023f - 95f) * 0.01125f / (171.2102946929f - 95f))
    } else {
        (Math.pow(10.0, (x * 1023f - 420f) / 261.5) * 0.19 - 0.01).toFloat()
    }
}

/**
 * Rec.709 OETF (Opto-Electronic Transfer Function): linear → gamma-corrected.
 * ITU-R BT.709 specification:
 *   x < 0.018:  4.500 × x
 *   x ≥ 0.018:  1.099 × x^0.45 - 0.099
 */
private fun rec709OETF(x: Float): Float =
    if (x < 0.018f) 4.5f * x else 1.099f * x.pow(0.45f) - 0.099f

private fun Float.pow(exp: Float): Float =
    Math.pow(this.toDouble(), exp.toDouble()).toFloat()

