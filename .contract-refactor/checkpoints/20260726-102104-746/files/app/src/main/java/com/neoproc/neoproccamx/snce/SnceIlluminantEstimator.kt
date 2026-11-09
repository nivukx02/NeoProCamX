package com.neoproc.neoproccamx.snce

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * SNCE v2.0 HVC — Stage 01 (SIEE-9): nine-method fusion illuminant estimator.
 *
 * The pre-existing code only had a *fusion skeleton* (NeoGamutApexPipeline.fuseWhiteBalance)
 * that consumed pre-supplied white estimates; there was no real pixel-domain estimator.
 * This implements seven classical methods end-to-end plus two injected priors, then
 * fuses by confidence-weighted voting exactly as directive 01.2 specifies:
 *
 *   1 Gray World            2 Max-RGB (white patch)     3 Shades-of-Gray (Minkowski)
 *   4 Specular highlight    5 CNN (C5/FFCC) — hook      6 Bayesian CCT prior
 *   7 Multi-zone k-means    8 Temporal prior            9 IMU orientation prior
 *
 * Operates on a small linear working-RGB [SnceThumbnail]; the working space is assumed
 * Rec.709-linear for the CCT / XYZ conversions ([Config.rgbToXyz] is overridable for
 * other sensors). All estimates are normalised to G = 1 before fusion so the methods
 * are compared purely on chromaticity.
 */
object SnceIlluminantEstimator {

    /** Rec.709 / sRGB linear -> CIE XYZ (D65). PHYSICAL working-space default. */
    val REC709_TO_XYZ = floatArrayOf(
        0.4123908f, 0.3575843f, 0.1804808f,
        0.2126390f, 0.7151687f, 0.0721923f,
        0.0193308f, 0.1191948f, 0.9505322f
    )

    /** Rec.2020 (BT.2020) linear -> CIE XYZ (D65). Working space for the wide-gamut / HDR develop lane. */
    val REC2020_TO_XYZ = floatArrayOf(
        0.6369580f, 0.1446169f, 0.1688810f,
        0.2627002f, 0.6779981f, 0.0593017f,
        0.0000000f, 0.0280727f, 1.0609851f
    )

    class Config(
        val zonesX: Int = 4,
        val zonesY: Int = 6,
        val minkowskiPowers: IntArray = intArrayOf(1, 3, 6, 9),
        val maxRgbPercentile: Float = 0.995f,
        val specularSumThreshold: Float = 0.85f,
        val specularNeutralThreshold: Float = 0.08f,
        val bayesianPriorKelvin: Float = 6000f,
        val bayesianConfidence: Float = 0.15f,
        val mixedLightCctSeparation: Float = 1000f,
        val mixedLightSecondaryCoverage: Float = 0.20f,
        val rgbToXyz: FloatArray = REC709_TO_XYZ
    )

    class Estimate(val rgb: FloatArray, val confidence: Float, val method: String)

    class Result(
        val illuminantRgb: FloatArray,
        val illuminantXyz: FloatArray,
        val cct: Float,
        val duv: Float,
        /** AWB gains [scaleR, 1, scaleB] that neutralise the estimated light. */
        val gains: FloatArray,
        val mixedLight: Boolean,
        val secondaryCct: Float,
        val perMethod: List<Estimate>,
        /**
         * s2-f10 / CP47 fusion confidence in [0,1]: how much the nine methods AGREE on the
         * chromaticity (1 = tight consensus, →0 = scattered estimate). Drops on mixed lighting.
         * Purely additive: the [gains] above are unchanged by this field, so the no-downgrade
         * golden-lock on the gains holds. Optional consumers (s2-f05 nudge gate, s2-f04 drift
         * ceiling) default to ignoring it, so the live path is bit-for-bit identical until they
         * opt in.
         */
        val confidence: Float = 1f
    )

    fun estimate(
        thumb: SnceThumbnail,
        temporalPrior: FloatArray? = null,
        imuPrior: SnceIlluminantPrior? = null,
        cnnModel: SnceIlluminantModel? = null,
        config: Config = Config()
    ): Result {
        val estimates = ArrayList<Estimate>(9)

        estimates += grayWorld(thumb)
        estimates += maxRgb(thumb, config)
        estimates += shadesOfGray(thumb, config)
        specularHighlight(thumb, config)?.let { estimates += it }
        // s2-f09 honest-fallback: a throwing or degenerate learned model is rejected here and the
        // classical methods carry the fusion unchanged (a valid model passes through identically).
        SnceModelGuards.safeIlluminant(cnnModel, thumb)?.let {
            estimates += Estimate(normalizeToG1(it.rgb), it.confidence.coerceIn(0f, 1f), "CNN")
        }
        estimates += bayesianPrior(config)
        val zoneResult = multiZone(thumb, config)
        estimates += zoneResult.estimate
        temporalPrior?.let {
            estimates += Estimate(normalizeToG1(it), TEMPORAL_CONFIDENCE, "Temporal")
        }
        imuPrior?.let {
            estimates += Estimate(normalizeToG1(it.rgb), it.confidence.coerceIn(0f, 1f), "IMU")
        }

        val fused = fuse(estimates)
        val xyz = SnceColorPrimitives.mulV(config.rgbToXyz, fused)
        val (cctRaw, duv) = SnceColorPrimitives.cctAndDuv(xyz)

        // directive 01.2 sanity gate: keep CCT in band, clamp implausible tint.
        val cct = cctRaw.coerceIn(2000f, 20000f)
        val gains = floatArrayOf(
            1f / fused[0].coerceAtLeast(SnceColorPrimitives.EPS),
            1f,
            1f / fused[2].coerceAtLeast(SnceColorPrimitives.EPS)
        )

        return Result(
            illuminantRgb = fused,
            illuminantXyz = xyz,
            cct = cct,
            duv = duv.coerceIn(-0.025f, 0.025f),
            gains = gains,
            mixedLight = zoneResult.mixedLight,
            secondaryCct = zoneResult.secondaryCct,
            perMethod = estimates,
            confidence = fusedConfidence(estimates, fused, zoneResult.mixedLight)
        )
    }

    // ------------------------------------------------------------------
    // s2-f10 / CP47 — fusion confidence (consensus tightness of the nine methods).
    //
    // Confidence-weighted chromatic dispersion of the per-method estimates around the
    // fused point: tight agreement -> ~1, scattered -> ->0, monotonically decreasing in
    // dispersion. Mixed lighting (already detected by the multi-zone k-means) tightens the
    // gate further. This never touches [estimate]'s gains; it is a read-only summary for the
    // optional s2-f05 / s2-f04 consumers.
    // ------------------------------------------------------------------
    private fun fusedConfidence(estimates: List<Estimate>, fused: FloatArray, mixedLight: Boolean): Float {
        var totalW = 0f
        var weightedDispersion = 0f
        estimates.forEach { e ->
            val w = e.confidence
            if (w <= 0f) return@forEach
            weightedDispersion += w * chromaDist(e.rgb, fused)
            totalW += w
        }
        if (totalW < SnceColorPrimitives.EPS) return 0f
        val meanDispersion = weightedDispersion / totalW
        var c = 1f / (1f + CONFIDENCE_DISPERSION_K * meanDispersion)
        if (mixedLight) c *= MIXED_LIGHT_CONFIDENCE_SCALE
        return c.coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------
    // Method 1 — Gray World.
    // ------------------------------------------------------------------
    private fun grayWorld(thumb: SnceThumbnail): Estimate {
        val mean = channelMeans(thumb)
        val chromaVar = chromaVariance(thumb, mean)
        // High confidence when the scene is close to gray (low chroma variance).
        val confidence = (1f / (1f + 6f * chromaVar)).coerceIn(0.1f, 1f)
        return Estimate(normalizeToG1(mean), confidence, "GrayWorld")
    }

    // ------------------------------------------------------------------
    // Method 2 — Max-RGB / white patch (per-channel high percentile).
    // ------------------------------------------------------------------
    private fun maxRgb(thumb: SnceThumbnail, config: Config): Estimate {
        val r = percentile(thumb, 0, config.maxRgbPercentile)
        val g = percentile(thumb, 1, config.maxRgbPercentile)
        val b = percentile(thumb, 2, config.maxRgbPercentile)
        val rgb = floatArrayOf(r, g, b)
        // Confidence falls if the brightest patch is itself strongly chromatic (coloured specular).
        val neutrality = 1f - chromaSpread(rgb)
        return Estimate(normalizeToG1(rgb), neutrality.coerceIn(0.1f, 0.9f), "MaxRGB")
    }

    // ------------------------------------------------------------------
    // Method 3 — Shades-of-Gray (Minkowski p-norm ensemble).
    // ------------------------------------------------------------------
    private fun shadesOfGray(thumb: SnceThumbnail, config: Config): Estimate {
        val acc = FloatArray(3)
        config.minkowskiPowers.forEach { p ->
            val norm = minkowski(thumb, p)
            val g = normalizeToG1(norm)
            acc[0] += g[0]; acc[1] += g[1]; acc[2] += g[2]
        }
        val inv = 1f / config.minkowskiPowers.size
        val rgb = floatArrayOf(acc[0] * inv, acc[1] * inv, acc[2] * inv)
        return Estimate(normalizeToG1(rgb), 0.6f, "ShadesOfGray")
    }

    // ------------------------------------------------------------------
    // Method 4 — Specular highlight detection (dielectric highlights ~ illuminant colour).
    // ------------------------------------------------------------------
    private fun specularHighlight(thumb: SnceThumbnail, config: Config): Estimate? {
        var count = 0
        val sum = FloatArray(3)
        val n = thumb.pixelCount()
        for (i in 0 until n) {
            val r = thumb.rgb[i * 3]
            val g = thumb.rgb[i * 3 + 1]
            val b = thumb.rgb[i * 3 + 2]
            val total = r + g + b
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            if (total > config.specularSumThreshold * 3f && (mx - mn) < config.specularNeutralThreshold) {
                sum[0] += r; sum[1] += g; sum[2] += b
                count++
            }
        }
        if (count < 8) return null
        val rgb = floatArrayOf(sum[0] / count, sum[1] / count, sum[2] / count)
        val confidence = (count.toFloat() / 500f).coerceIn(0f, 1f)
        return Estimate(normalizeToG1(rgb), confidence, "Specular")
    }

    // ------------------------------------------------------------------
    // Method 6 — Physics-based Bayesian prior (gentle regulariser toward ~6000K daylight).
    // ------------------------------------------------------------------
    private fun bayesianPrior(config: Config): Estimate {
        val xy = SnceColorPrimitives.kelvinToXy(config.bayesianPriorKelvin)
        val xyz = SnceColorPrimitives.xyToXyz(xy.first, xy.second)
        val rgb = SnceColorPrimitives.mulV(SnceColorPrimitives.invert3(config.rgbToXyz), xyz)
        return Estimate(normalizeToG1(rgb), config.bayesianConfidence, "Bayesian")
    }

    // ------------------------------------------------------------------
    // Method 7 — Multi-zone spatial decomposition (k=2 k-means on zone chromaticities).
    // ------------------------------------------------------------------
    private class ZoneResult(val estimate: Estimate, val mixedLight: Boolean, val secondaryCct: Float)

    private fun multiZone(thumb: SnceThumbnail, config: Config): ZoneResult {
        val zoneChroma = ArrayList<FloatArray>()    // each: normalised G=1 rgb
        val zoneWeight = ArrayList<Float>()
        for (zy in 0 until config.zonesY) {
            for (zx in 0 until config.zonesX) {
                val mean = zoneMean(thumb, zx, zy, config) ?: continue
                zoneChroma += normalizeToG1(mean)
                zoneWeight += (mean[0] + mean[1] + mean[2])
            }
        }
        if (zoneChroma.size < 2) {
            val mean = channelMeans(thumb)
            return ZoneResult(Estimate(normalizeToG1(mean), 0.3f, "MultiZone"), false, 0f)
        }

        val clusters = kMeans2(zoneChroma, zoneWeight)
        val dominant = if (clusters.weightA >= clusters.weightB) clusters.centroidA else clusters.centroidB
        val secondary = if (clusters.weightA >= clusters.weightB) clusters.centroidB else clusters.centroidA
        val secondaryCoverage = minOf(clusters.weightA, clusters.weightB) /
            (clusters.weightA + clusters.weightB).coerceAtLeast(SnceColorPrimitives.EPS)

        val domXyz = SnceColorPrimitives.mulV(config.rgbToXyz, dominant)
        val secXyz = SnceColorPrimitives.mulV(config.rgbToXyz, secondary)
        val domCct = SnceColorPrimitives.correlatedColorTemperature(domXyz)
        val secCct = SnceColorPrimitives.correlatedColorTemperature(secXyz)
        val mixed = abs(domCct - secCct) > config.mixedLightCctSeparation &&
            secondaryCoverage > config.mixedLightSecondaryCoverage

        return ZoneResult(
            Estimate(normalizeToG1(dominant), 0.55f, "MultiZone"),
            mixed,
            if (mixed) secCct else 0f
        )
    }

    private class KMeans2(
        val centroidA: FloatArray,
        val centroidB: FloatArray,
        val weightA: Float,
        val weightB: Float
    )

    private fun kMeans2(points: List<FloatArray>, weights: List<Float>): KMeans2 {
        // Initialise with the two most chromatically separated zones.
        var a = points.first()
        var b = points.last()
        var maxD = -1f
        for (i in points.indices) for (j in i + 1 until points.size) {
            val d = chromaDist(points[i], points[j])
            if (d > maxD) { maxD = d; a = points[i]; b = points[j] }
        }
        var ca = a.copyOf(); var cb = b.copyOf()
        repeat(20) {
            val sa = FloatArray(3); val sb = FloatArray(3)
            var wa = 0f; var wb = 0f
            for (i in points.indices) {
                val w = weights[i]
                if (chromaDist(points[i], ca) <= chromaDist(points[i], cb)) {
                    sa[0] += points[i][0] * w; sa[1] += points[i][1] * w; sa[2] += points[i][2] * w; wa += w
                } else {
                    sb[0] += points[i][0] * w; sb[1] += points[i][1] * w; sb[2] += points[i][2] * w; wb += w
                }
            }
            if (wa > 0f) ca = floatArrayOf(sa[0] / wa, sa[1] / wa, sa[2] / wa)
            if (wb > 0f) cb = floatArrayOf(sb[0] / wb, sb[1] / wb, sb[2] / wb)
        }
        // Recompute final cluster weights.
        var wa = 0f; var wb = 0f
        for (i in points.indices) {
            if (chromaDist(points[i], ca) <= chromaDist(points[i], cb)) wa += weights[i] else wb += weights[i]
        }
        return KMeans2(normalizeToG1(ca), normalizeToG1(cb), wa, wb)
    }

    // ------------------------------------------------------------------
    // Confidence-weighted fusion (directive 01.2).
    // ------------------------------------------------------------------
    private fun fuse(estimates: List<Estimate>): FloatArray {
        var total = 0f
        estimates.forEach { total += it.confidence }
        if (total < SnceColorPrimitives.EPS) return floatArrayOf(1f, 1f, 1f)
        val acc = FloatArray(3)
        estimates.forEach { e ->
            val w = e.confidence / total
            acc[0] += e.rgb[0] * w; acc[1] += e.rgb[1] * w; acc[2] += e.rgb[2] * w
        }
        return normalizeToG1(acc)
    }

    // ------------------------------------------------------------------
    // Pixel statistics helpers.
    // ------------------------------------------------------------------
    private fun channelMeans(thumb: SnceThumbnail): FloatArray {
        val sum = FloatArray(3)
        val n = thumb.pixelCount()
        for (i in 0 until n) {
            sum[0] += thumb.rgb[i * 3]; sum[1] += thumb.rgb[i * 3 + 1]; sum[2] += thumb.rgb[i * 3 + 2]
        }
        val inv = 1f / n
        return floatArrayOf(sum[0] * inv, sum[1] * inv, sum[2] * inv)
    }

    private fun minkowski(thumb: SnceThumbnail, p: Int): FloatArray {
        val sum = DoubleArray(3)
        val n = thumb.pixelCount()
        for (i in 0 until n) {
            sum[0] += thumb.rgb[i * 3].toDouble().pow(p)
            sum[1] += thumb.rgb[i * 3 + 1].toDouble().pow(p)
            sum[2] += thumb.rgb[i * 3 + 2].toDouble().pow(p)
        }
        val invN = 1.0 / n
        val e = 1.0 / p
        return floatArrayOf(
            (sum[0] * invN).pow(e).toFloat(),
            (sum[1] * invN).pow(e).toFloat(),
            (sum[2] * invN).pow(e).toFloat()
        )
    }

    private fun percentile(thumb: SnceThumbnail, channel: Int, q: Float): Float {
        val n = thumb.pixelCount()
        val values = FloatArray(n) { thumb.rgb[it * 3 + channel] }
        values.sort()
        val idx = ((n - 1) * q).toInt().coerceIn(0, n - 1)
        return values[idx]
    }

    private fun zoneMean(thumb: SnceThumbnail, zx: Int, zy: Int, config: Config): FloatArray? {
        val x0 = thumb.width * zx / config.zonesX
        val x1 = thumb.width * (zx + 1) / config.zonesX
        val y0 = thumb.height * zy / config.zonesY
        val y1 = thumb.height * (zy + 1) / config.zonesY
        if (x1 <= x0 || y1 <= y0) return null
        val sum = FloatArray(3)
        var count = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = thumb.index(x, y)
            sum[0] += thumb.rgb[i]; sum[1] += thumb.rgb[i + 1]; sum[2] += thumb.rgb[i + 2]
            count++
        }
        if (count == 0) return null
        val inv = 1f / count
        return floatArrayOf(sum[0] * inv, sum[1] * inv, sum[2] * inv)
    }

    private fun chromaVariance(thumb: SnceThumbnail, mean: FloatArray): Float {
        val meanChroma = chromaOf(mean)
        var acc = 0f
        val n = thumb.pixelCount()
        for (i in 0 until n) {
            val c = chromaOf(floatArrayOf(thumb.rgb[i * 3], thumb.rgb[i * 3 + 1], thumb.rgb[i * 3 + 2]))
            val du = c.first - meanChroma.first
            val dv = c.second - meanChroma.second
            acc += du * du + dv * dv
        }
        return acc / n
    }

    private fun chromaOf(rgb: FloatArray): Pair<Float, Float> {
        val sum = (rgb[0] + rgb[1] + rgb[2]).coerceAtLeast(SnceColorPrimitives.EPS)
        return (rgb[0] / sum) to (rgb[1] / sum)
    }

    private fun chromaDist(a: FloatArray, b: FloatArray): Float {
        val ca = chromaOf(a); val cb = chromaOf(b)
        val du = ca.first - cb.first; val dv = ca.second - cb.second
        return sqrt(du * du + dv * dv)
    }

    private fun chromaSpread(rgb: FloatArray): Float {
        val g = normalizeToG1(rgb)
        return (abs(g[0] - 1f) + abs(g[2] - 1f)) * 0.5f
    }

    private fun normalizeToG1(rgb: FloatArray): FloatArray {
        val g = rgb[1].let { if (abs(it) < SnceColorPrimitives.EPS) SnceColorPrimitives.EPS else it }
        return floatArrayOf(rgb[0] / g, 1f, rgb[2] / g)
    }

    private const val TEMPORAL_CONFIDENCE = 0.35f

    /** Dispersion sensitivity for [fusedConfidence]; tuned so a clean single-illuminant scene
     *  reports high confidence and a scattered/mixed scene reports low confidence. */
    private const val CONFIDENCE_DISPERSION_K = 60f

    /** Extra confidence haircut when the multi-zone stage flags mixed lighting. */
    private const val MIXED_LIGHT_CONFIDENCE_SCALE = 0.6f
}
