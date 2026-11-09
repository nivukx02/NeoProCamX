package com.neoproc.neoproccamx.quality.measure

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CP01 — objective image-quality measurement primitives (pure JVM, no Android dependencies).
 *
 * These compute REAL numbers from REAL pixels so later checkpoints can gate elite upgrades on
 * measured quality (MTF50 resolution, flat-field SNR, ColorChecker dE2000, banding, temporal
 * flicker) instead of the hardcoded/heuristic values the current quality gates rely on. Inputs
 * are plain float arrays so the harness runs under fast JVM unit tests today and on decoded
 * golden frames later (see the Beyond-IMAX upgrade program, Phase 0 / CP-Q*).
 *
 * Conventions: luma/RGB samples are in [0,1]; MTF50 is in cycles/pixel; SNR in dB; Lab is CIELAB
 * under a D65 white. Nothing here mutates global state; every function is deterministic.
 */
object ImageQualityMetrics {

    // ---- 1. Flat-field SNR ---------------------------------------------------------------

    /** Cap reported SNR when the patch is (near) noiseless to avoid infinities. */
    const val SNR_CAP_DB: Double = 120.0

    /**
     * Luma signal-to-noise ratio in dB for a flat/uniform patch: 20*log10(mean / stddev).
     * Returns [SNR_CAP_DB] when the patch has no measurable noise.
     */
    fun flatFieldSnrDb(luma: FloatArray): Double {
        require(luma.isNotEmpty()) { "luma must be non-empty" }
        var mean = 0.0
        for (v in luma) mean += v
        mean /= luma.size
        var varianceAcc = 0.0
        for (v in luma) {
            val d = v - mean
            varianceAcc += d * d
        }
        val std = sqrt(varianceAcc / luma.size)
        if (std <= 1e-9) return SNR_CAP_DB
        return min(20.0 * log10(abs(mean) / std), SNR_CAP_DB)
    }

    /**
     * Robust per-frame luma SNR (dB) estimated from a SINGLE arbitrary frame when no dedicated
     * flat-field patch is available. The frame is divided into [tile]x[tile] blocks; the noise
     * floor is the mean per-tile standard deviation over the flattest [flatTileFraction] of tiles
     * (the flattest tiles are dominated by sensor noise, not scene structure), and the signal is
     * the global mean. Returns 20*log10(mean/noiseStd), capped at [SNR_CAP_DB]; returns
     * [SNR_CAP_DB] when no measurable noise is present. Row-major luma in [0,1].
     */
    fun frameLumaSnrDb(
        luma: FloatArray,
        width: Int,
        height: Int,
        tile: Int = 16,
        flatTileFraction: Double = 0.10
    ): Double {
        require(luma.size == width * height) { "image size mismatch" }
        return flatTileNoiseSnrDb(width, height, tile, flatTileFraction) { x, y ->
            luma[y * width + x].toDouble()
        }
    }

    /**
     * Same robust flattest-tile SNR as [frameLumaSnrDb] but reads an 8-bit Y plane directly with
     * the given [rowStride]/[pixelStride] (normalizing /255), so a live YUV_420_888 analysis frame
     * can be measured without copying it into a float buffer first. [sampleStep] subsamples the
     * tile walk for hot-path cost control (1 = every pixel).
     */
    fun frameLumaSnrDbFromY(
        yData: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        tile: Int = 16,
        flatTileFraction: Double = 0.10
    ): Double {
        require(width >= tile && height >= tile) { "frame smaller than tile" }
        return flatTileNoiseSnrDb(width, height, tile, flatTileFraction) { x, y ->
            val offset = y * rowStride + x * pixelStride
            if (offset in yData.indices) (yData[offset].toInt() and 0xFF) / 255.0 else 0.0
        }
    }

    /** Shared core for the single-frame flattest-tile noise SNR; [sample] returns luma in [0,1]. */
    private inline fun flatTileNoiseSnrDb(
        width: Int,
        height: Int,
        tile: Int,
        flatTileFraction: Double,
        sample: (x: Int, y: Int) -> Double
    ): Double {
        require(width >= tile && height >= tile) { "frame smaller than tile" }
        require(flatTileFraction > 0.0 && flatTileFraction <= 1.0) { "fraction out of range" }
        var globalSum = 0.0
        var globalCount = 0L
        val tileStds = ArrayList<Double>()
        var ty = 0
        while (ty + tile <= height) {
            var tx = 0
            while (tx + tile <= width) {
                var s = 0.0
                var ss = 0.0
                var n = 0
                for (y in ty until ty + tile) {
                    for (x in tx until tx + tile) {
                        val v = sample(x, y)
                        s += v
                        ss += v * v
                        n++
                    }
                }
                val mean = s / n
                val variance = (ss / n - mean * mean).coerceAtLeast(0.0)
                tileStds.add(sqrt(variance))
                globalSum += s
                globalCount += n
                tx += tile
            }
            ty += tile
        }
        if (tileStds.isEmpty() || globalCount == 0L) return SNR_CAP_DB
        val globalMean = globalSum / globalCount
        tileStds.sort()
        val take = max(1, (tileStds.size * flatTileFraction).roundToInt())
        var noiseAcc = 0.0
        for (i in 0 until take) noiseAcc += tileStds[i]
        val noiseStd = noiseAcc / take
        if (noiseStd <= 1e-9) return SNR_CAP_DB
        return min(20.0 * log10(abs(globalMean) / noiseStd), SNR_CAP_DB)
    }

    // ---- 2. Slanted-edge MTF50 -----------------------------------------------------------

    /**
     * ISO-12233-style slanted-edge MTF50 in cycles/pixel for a near-vertical edge tilted a few
     * degrees off-axis. Higher = sharper. [image] is row-major luma in [0,1], size width*height.
     *
     * Pipeline: per-row sub-pixel edge centroid -> least-squares edge line -> 4x oversampled ESF
     * binned along the edge normal -> central-difference LSF -> Hamming window -> DFT magnitude ->
     * normalized MTF -> first 0.5 crossing (linearly interpolated).
     */
    fun slantedEdgeMtf50(image: FloatArray, width: Int, height: Int): Double {
        require(image.size == width * height) { "image size mismatch" }
        require(width >= 8 && height >= 8) { "edge target too small" }

        // 2.1 per-row sub-pixel edge location via |gradient| centroid.
        val edgeX = DoubleArray(height)
        val rowValid = BooleanArray(height)
        for (y in 0 until height) {
            var num = 0.0
            var den = 0.0
            for (x in 1 until width) {
                val g = abs(image[y * width + x] - image[y * width + x - 1]).toDouble()
                num += g * (x - 0.5)
                den += g
            }
            if (den > 1e-6) {
                edgeX[y] = num / den
                rowValid[y] = true
            }
        }

        // 2.2 least-squares line fit edgeX = m*y + c over valid rows.
        var n = 0
        var sy = 0.0
        var sx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (y in 0 until height) {
            if (!rowValid[y]) continue
            n++
            sy += y
            sx += edgeX[y]
            syy += y.toDouble() * y
            sxy += y.toDouble() * edgeX[y]
        }
        require(n >= 4) { "not enough valid edge rows" }
        val fitDen = n * syy - sy * sy
        val m = if (abs(fitDen) > 1e-9) (n * sxy - sy * sx) / fitDen else 0.0
        val c = (sx - m * sy) / n
        val cosT = cos(atan(m)) // edge tilt from vertical

        // 2.3 oversampled ESF: bin the signed normal distance (4 sub-samples per pixel).
        val binsPerPx = 4
        val halfWinPx = 16
        val nBins = halfWinPx * 2 * binsPerPx
        val center = nBins / 2
        val esfAcc = DoubleArray(nBins)
        val esfCnt = IntArray(nBins)
        for (y in 0 until height) {
            val ex = m * y + c
            for (x in 0 until width) {
                val d = (x - ex) * cosT
                val bin = (d * binsPerPx).roundToInt() + center
                if (bin in 0 until nBins) {
                    esfAcc[bin] += image[y * width + x]
                    esfCnt[bin]++
                }
            }
        }

        // 2.4 resolve ESF, forward-filling empty bins then back-filling any leading gap.
        val esf = DoubleArray(nBins) { Double.NaN }
        var last = Double.NaN
        for (i in 0 until nBins) {
            if (esfCnt[i] > 0) {
                esf[i] = esfAcc[i] / esfCnt[i]
                last = esf[i]
            } else if (!last.isNaN()) {
                esf[i] = last
            }
        }
        var first = 0.0
        for (i in 0 until nBins) {
            if (esfCnt[i] > 0) {
                first = esfAcc[i] / esfCnt[i]
                break
            }
        }
        for (i in 0 until nBins) if (esf[i].isNaN()) esf[i] = first

        // 2.5 LSF = central difference.
        val lsf = DoubleArray(nBins)
        for (i in 1 until nBins - 1) lsf[i] = (esf[i + 1] - esf[i - 1]) * 0.5
        lsf[0] = lsf[1]
        lsf[nBins - 1] = lsf[nBins - 2]

        // 2.6 Hamming window centered on the LSF centroid (suppresses tail/ringing).
        var wsum = 0.0
        var wpos = 0.0
        for (i in 0 until nBins) {
            val a = abs(lsf[i])
            wsum += a
            wpos += a * i
        }
        val centroid = if (wsum > 1e-9) wpos / wsum else center.toDouble()
        for (i in 0 until nBins) {
            val rel = ((i - centroid) / (nBins * 0.5)).coerceIn(-1.0, 1.0)
            lsf[i] *= 0.54 + 0.46 * cos(PI * rel)
        }

        // 2.7 DFT magnitude -> normalized MTF; freq (cy/px) = k * binsPerPx / nBins.
        val dc = dftMagnitude(lsf, 0)
        if (dc <= 1e-12) return 0.0
        var prevF = 0.0
        var prevM = 1.0
        val maxK = nBins / 2
        for (k in 1..maxK) {
            val mtf = dftMagnitude(lsf, k) / dc
            val f = k.toDouble() * binsPerPx / nBins
            if (mtf <= 0.5) {
                val span = prevM - mtf
                val t = if (span > 1e-9) (prevM - 0.5) / span else 0.0
                return prevF + t * (f - prevF)
            }
            prevF = f
            prevM = mtf
            if (f >= 1.0) break // luma Nyquist
        }
        return prevF
    }

    private fun dftMagnitude(x: DoubleArray, k: Int): Double {
        val n = x.size
        var re = 0.0
        var im = 0.0
        val w = -2.0 * PI * k / n
        for (i in 0 until n) {
            val a = w * i
            re += x[i] * cos(a)
            im += x[i] * sin(a)
        }
        return sqrt(re * re + im * im)
    }

    // ---- 2b. Edge overshoot (ringing / halo) --------------------------------------------

    /**
     * CP24 — ISO-12233-style edge overshoot for a 1-D edge profile (e.g. an ESF row across a
     * slanted edge, or a detail-re-injected edge). Returns the fractional overshoot beyond the
     * plateau the edge settles to, normalized by the edge contrast: 0 = no ringing, >0 = a bright
     * (or dark) fringe. A halo-free detail operator must keep this below a small threshold.
     *
     * The low/high plateaus are the means of the first/last [edgePad] samples; the overshoot is the
     * largest excursion of any interior sample beyond whichever plateau is brighter/darker, divided
     * by |high − low|. Undershoot (dark fringe) is folded in via the symmetric max.
     */
    fun edgeOvershoot1D(profile: DoubleArray, edgePad: Int = 3): Double {
        require(profile.size >= 2 * edgePad + 1) { "profile too short for edgePad=$edgePad" }
        var lo = 0.0
        var hi = 0.0
        for (i in 0 until edgePad) {
            lo += profile[i]
            hi += profile[profile.size - 1 - i]
        }
        lo /= edgePad
        hi /= edgePad
        val contrast = abs(hi - lo)
        if (contrast <= 1e-9) return 0.0
        val top = max(hi, lo)
        val bot = min(hi, lo)
        var overshoot = 0.0
        for (v in profile) {
            val over = max(v - top, bot - v) // excursion beyond either plateau
            if (over > overshoot) overshoot = over
        }
        return overshoot / contrast
    }

    // ---- 3. ColorChecker dE2000 ----------------------------------------------------------

    /** Returns (mean, max) CIEDE2000 across paired measured/reference Lab patches. */
    fun colorCheckerDeltaE2000(
        measuredLab: List<FloatArray>,
        referenceLab: List<FloatArray>
    ): Pair<Double, Double> {
        require(measuredLab.size == referenceLab.size && measuredLab.isNotEmpty()) {
            "patch count mismatch"
        }
        var sum = 0.0
        var mx = 0.0
        for (i in measuredLab.indices) {
            val de = deltaE2000(measuredLab[i], referenceLab[i])
            sum += de
            if (de > mx) mx = de
        }
        return (sum / measuredLab.size) to mx
    }

    /** sRGB (0..1, IEC 61966-2-1) -> CIELAB under D65. */
    fun srgbToLab(r: Float, g: Float, b: Float): FloatArray {
        val rl = srgbToLinear(r)
        val gl = srgbToLinear(g)
        val bl = srgbToLinear(b)
        val x = 0.4124564 * rl + 0.3575761 * gl + 0.1804375 * bl
        val y = 0.2126729 * rl + 0.7151522 * gl + 0.0721750 * bl
        val z = 0.0193339 * rl + 0.1191920 * gl + 0.9503041 * bl
        return xyzToLab(x, y, z)
    }

    private fun srgbToLinear(c: Float): Double {
        val v = c.toDouble().coerceIn(0.0, 1.0)
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private val D65 = doubleArrayOf(0.95047, 1.0, 1.08883)

    private fun xyzToLab(x: Double, y: Double, z: Double): FloatArray {
        val fx = labPivot(x / D65[0])
        val fy = labPivot(y / D65[1])
        val fz = labPivot(z / D65[2])
        return floatArrayOf(
            (116.0 * fy - 16.0).toFloat(),
            (500.0 * (fx - fy)).toFloat(),
            (200.0 * (fy - fz)).toFloat()
        )
    }

    private fun labPivot(v: Double): Double =
        if (v > 216.0 / 24389.0) v.pow(1.0 / 3.0) else (24389.0 / 27.0 * v + 16.0) / 116.0

    /** CIEDE2000 color difference between two CIELAB triples (same formulation as ColorValidationHarness). */
    fun deltaE2000(lab1: FloatArray, lab2: FloatArray): Double {
        val l1 = lab1[0].toDouble()
        val a1 = lab1[1].toDouble()
        val b1 = lab1[2].toDouble()
        val l2 = lab2[0].toDouble()
        val a2 = lab2[1].toDouble()
        val b2 = lab2[2].toDouble()
        val c1 = sqrt(a1 * a1 + b1 * b1)
        val c2 = sqrt(a2 * a2 + b2 * b2)
        val cBar = (c1 + c2) * 0.5
        val cBar7 = cBar.pow(7.0)
        val g = 0.5 * (1.0 - sqrt(cBar7 / (cBar7 + 25.0.pow(7.0))))
        val ap1 = (1.0 + g) * a1
        val ap2 = (1.0 + g) * a2
        val cp1 = sqrt(ap1 * ap1 + b1 * b1)
        val cp2 = sqrt(ap2 * ap2 + b2 * b2)
        val hp1 = hueRadians(b1, ap1)
        val hp2 = hueRadians(b2, ap2)
        val deltaLp = l2 - l1
        val deltaCp = cp2 - cp1
        val dhp = when {
            cp1 * cp2 == 0.0 -> 0.0
            abs(hp2 - hp1) <= PI -> hp2 - hp1
            hp2 <= hp1 -> hp2 - hp1 + 2.0 * PI
            else -> hp2 - hp1 - 2.0 * PI
        }
        val deltaHp = 2.0 * sqrt(cp1 * cp2) * sin(dhp * 0.5)
        val lpBar = (l1 + l2) * 0.5
        val cpBar = (cp1 + cp2) * 0.5
        val hpBar = when {
            cp1 * cp2 == 0.0 -> hp1 + hp2
            abs(hp1 - hp2) <= PI -> (hp1 + hp2) * 0.5
            hp1 + hp2 < 2.0 * PI -> (hp1 + hp2 + 2.0 * PI) * 0.5
            else -> (hp1 + hp2 - 2.0 * PI) * 0.5
        }
        val t = 1.0 -
            0.17 * cos(hpBar - Math.toRadians(30.0)) +
            0.24 * cos(2.0 * hpBar) +
            0.32 * cos(3.0 * hpBar + Math.toRadians(6.0)) -
            0.20 * cos(4.0 * hpBar - Math.toRadians(63.0))
        val deltaTheta = Math.toRadians(30.0) *
            exp(-((Math.toDegrees(hpBar) - 275.0) / 25.0).pow(2.0))
        val rc = 2.0 * sqrt(cpBar.pow(7.0) / (cpBar.pow(7.0) + 25.0.pow(7.0)))
        val sl = 1.0 + (0.015 * (lpBar - 50.0).pow(2.0)) /
            sqrt(20.0 + (lpBar - 50.0).pow(2.0))
        val sc = 1.0 + 0.045 * cpBar
        val sh = 1.0 + 0.015 * cpBar * t
        val rt = -sin(2.0 * deltaTheta) * rc
        val lTerm = deltaLp / sl
        val cTerm = deltaCp / sc
        val hTerm = deltaHp / sh
        return sqrt(lTerm * lTerm + cTerm * cTerm + hTerm * hTerm + rt * cTerm * hTerm)
    }

    private fun hueRadians(b: Double, a: Double): Double {
        val hue = atan2(b, a)
        return if (hue >= 0.0) hue else hue + 2.0 * PI
    }

    // ---- 4. Banding / posterization ------------------------------------------------------

    data class BandingResult(
        val distinctLevels: Int,
        val longestFlatRun: Int,
        /** longestFlatRun / sampleCount — higher means coarser quantization (worse banding). */
        val bandingScore: Double
    )

    /**
     * Measures quantization banding on a (monotonic) ramp. A smooth high-bit-depth ramp yields
     * many distinct levels and short flat runs; a posterized ramp yields few levels and long runs.
     */
    fun rampBanding(ramp: FloatArray, quantizeDigits: Int = 4): BandingResult {
        require(ramp.size >= 2) { "ramp too short" }
        val scale = 10.0.pow(quantizeDigits)
        val q = LongArray(ramp.size) { (ramp[it] * scale).roundToInt().toLong() }
        val distinct = q.toHashSet().size
        var longest = 1
        var run = 1
        for (i in 1 until q.size) {
            if (q[i] == q[i - 1]) {
                run++
                if (run > longest) longest = run
            } else {
                run = 1
            }
        }
        return BandingResult(distinct, longest, longest.toDouble() / ramp.size)
    }

    // ---- 5. Temporal flicker -------------------------------------------------------------

    /**
     * Mean per-pixel temporal standard deviation across frames of a (nominally static) scene,
     * on the [0,1] sample scale. Near 0 = temporally stable; higher = flicker/chroma-crawl.
     */
    fun temporalFlickerStd(frames: List<FloatArray>): Double {
        require(frames.size >= 2) { "need >=2 frames" }
        val len = frames[0].size
        require(len > 0 && frames.all { it.size == len }) { "frames must share a non-zero length" }
        var acc = 0.0
        for (p in 0 until len) {
            var mean = 0.0
            for (f in frames) mean += f[p]
            mean /= frames.size
            var v = 0.0
            for (f in frames) {
                val d = f[p] - mean
                v += d * d
            }
            acc += sqrt(v / frames.size)
        }
        return acc / len
    }

    /**
     * Population standard deviation of the first [count] samples produced by [sample]. This is the
     * shared inline core behind the windowed temporal-flicker-sigma metric, mirroring how
     * [flatTileNoiseSnrDb] backs both the live and bench SNR entrypoints — a live caller (a small
     * ring of per-frame means) and a bench caller (a fixed window) share this exact math so their
     * results agree. [count] must be >= 2.
     */
    private inline fun temporalSigmaCore(count: Int, sample: (Int) -> Double): Double {
        require(count >= 2) { "need >=2 samples" }
        var mean = 0.0
        for (i in 0 until count) mean += sample(i)
        mean /= count
        var acc = 0.0
        for (i in 0 until count) {
            val d = sample(i) - mean
            acc += d * d
        }
        return sqrt(acc / count)
    }

    /**
     * CP-Q7 — windowed temporal flicker sigma: the population standard deviation of a window of
     * per-frame luma SUMMARY values (e.g. frame means), on the [0,1] sample scale. 0 = temporally
     * flat; higher = brightness flicker. This is the cheap, live-promotable companion to
     * [temporalFlickerStd] (which needs full per-frame buffers): it consumes one scalar per frame, so
     * it can run on the analysis stride. Shares [temporalSigmaCore] with the live caller.
     */
    fun temporalFlickerSigmaFromMeans(frameMeans: DoubleArray): Double =
        temporalSigmaCore(frameMeans.size) { frameMeans[it] }

    /**
     * Overload reading only the first [count] entries of a reusable ring [buffer], so a live caller
     * can avoid copying its ring into a fresh array each frame. Same math as the [DoubleArray] form.
     */
    fun temporalFlickerSigmaFromMeans(buffer: DoubleArray, count: Int): Double =
        temporalSigmaCore(count) { buffer[it] }

    /**
     * CP-Q6 — temporal chroma-crawl: mean per-pixel temporal standard deviation of the *chroma*
     * (color, not luma) of a nominally-static scene, on the [0,1] sample scale. Each frame is
     * RGB-interleaved (length = 3*pixels). Chroma is taken in a simple opponent space
     * (r-g, 0.5*(r+g)-b) so that luma-only flicker does NOT register — only color instability
     * (the chroma-crawl that betrays an unstable AWB / denoiser) does. Near 0 = chroma-stable.
     */
    fun temporalChromaCrawl(framesRgb: List<FloatArray>): Double {
        require(framesRgb.size >= 2) { "need >=2 frames" }
        val len = framesRgb[0].size
        require(len > 0 && len % 3 == 0 && framesRgb.all { it.size == len }) {
            "frames must share a non-zero RGB-interleaved length"
        }
        val pixels = len / 3
        var acc = 0.0
        for (px in 0 until pixels) {
            val i = px * 3
            var meanO1 = 0.0
            var meanO2 = 0.0
            for (f in framesRgb) {
                val r = f[i]
                val g = f[i + 1]
                val b = f[i + 2]
                meanO1 += (r - g)
                meanO2 += 0.5 * (r + g) - b
            }
            meanO1 /= framesRgb.size
            meanO2 /= framesRgb.size
            var v = 0.0
            for (f in framesRgb) {
                val r = f[i]
                val g = f[i + 1]
                val b = f[i + 2]
                val d1 = (r - g) - meanO1
                val d2 = (0.5 * (r + g) - b) - meanO2
                v += d1 * d1 + d2 * d2
            }
            acc += sqrt(v / framesRgb.size)
        }
        return acc / pixels
    }
}
