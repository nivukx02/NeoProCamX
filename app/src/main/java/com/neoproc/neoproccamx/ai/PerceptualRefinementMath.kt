package com.neoproc.neoproccamx.ai

import com.neoproc.neoproccamx.core.PerceptualSignalMath
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class AdaptiveRefinementProfile(
    val normalizedNoiseFloor: Float,
    val perceptualSharpness: Float,
    val denoiseBoost: Float,
    val sharpenBoost: Float,
    val laplacianRefineStrength: Float
)

internal data class FaceCandidate(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val confidence: Float
)

internal data class OklabPixel(
    val l: Float,
    val a: Float,
    val b: Float
)

internal object PerceptualRefinementMath {

    fun analyzePixels(pixels: IntArray, width: Int, height: Int): AdaptiveRefinementProfile {
        val signal = PerceptualSignalMath.analyzeArgb(pixels, width, height)
        val denoiseBoost =
            (signal.normalizedNoiseFloor * 0.22f + (1f - signal.perceptualSharpness) * 0.04f)
                .coerceIn(0f, 0.28f)
        val sharpenBoost =
            (signal.perceptualSharpness * 0.12f - signal.normalizedNoiseFloor * 0.06f)
                .coerceIn(0f, 0.10f)
        val laplacianRefineStrength =
            (0.16f + signal.perceptualSharpness * 0.34f - signal.normalizedNoiseFloor * 0.14f)
                .coerceIn(0.08f, 0.42f)
        return AdaptiveRefinementProfile(
            normalizedNoiseFloor = signal.normalizedNoiseFloor,
            perceptualSharpness = signal.perceptualSharpness,
            denoiseBoost = denoiseBoost,
            sharpenBoost = sharpenBoost,
            laplacianRefineStrength = laplacianRefineStrength
        )
    }

    fun applyLaplacianHighPassRefinement(
        pixels: IntArray,
        width: Int,
        height: Int,
        profile: AdaptiveRefinementProfile
    ): IntArray {
        if (width < 3 || height < 3 || pixels.size < width * height) {
            return pixels
        }
        val output = pixels.copyOf()
        val refineStrength = profile.laplacianRefineStrength * 0.18f
        val noiseGate = (profile.normalizedNoiseFloor * 22f + 6f).coerceIn(6f, 24f)
        
        // HIGH-END OPTIMIZATION: Row-major access with index caching
        for (y in 1 until height - 1) {
            val rowIdx = y * width
            val prevRowIdx = (y - 1) * width
            val nextRowIdx = (y + 1) * width
            for (x in 1 until width - 1) {
                val idx = rowIdx + x
                val center = pixels[idx]

                // Fast-path luminance check to avoid expensive Math.abs
                val centerLuma = (0.299f * ((center shr 16) and 0xFF) + 0.587f * ((center shr 8) and 0xFF) + 0.114f * (center and 0xFF))
                val laplacian = (
                    4f * centerLuma -
                        luminance(pixels[idx - 1]) -
                        luminance(pixels[idx + 1]) -
                        luminance(pixels[prevRowIdx + x]) -
                        luminance(pixels[nextRowIdx + x])
                    )
                
                if (laplacian > noiseGate || laplacian < -noiseGate) {
                    val delta = laplacian * refineStrength
                    val rOrig = (center shr 16) and 0xFF
                    val gOrig = (center shr 8) and 0xFF
                    val bOrig = center and 0xFF

                    // Use absolute addition to preserve color ratio and prevent saturation explosion
                    // on warm/red-heavy images.
                    val r = (rOrig + delta).roundToInt().coerceIn(0, 255)
                    val g = (gOrig + delta).roundToInt().coerceIn(0, 255)
                    val b = (bOrig + delta).roundToInt().coerceIn(0, 255)

                    output[idx] = (0xFF shl 24) or
                        (r shl 16) or
                        (g shl 8) or
                        b
                }
            }
        }
        return output
    }

    fun csfMicroContrastWeight(
        lightness: Float,
        baseGain: Float,
        shadowFalloff: Float = 1.2f,
        highlightFalloff: Float = 0.8f
    ): Float {
        val l = lightness.coerceIn(0f, 1f)
        if (l <= 0f || l >= 1f || baseGain <= 0f) {
            return 0f
        }
        val shadowGate = Math.pow((1f - l).toDouble(), shadowFalloff.toDouble()).toFloat()
        val highlightGate = Math.pow(l.toDouble(), highlightFalloff.toDouble()).toFloat()
        return (baseGain * shadowGate * highlightGate).coerceAtLeast(0f)
    }

    fun sigmoidMicroContrast(highFrequency: Float, gain: Float): Float {
        val x = (-2f * gain.coerceAtLeast(0f) * highFrequency.coerceIn(-1f, 1f)).coerceIn(-24f, 24f)
        return (2f / (1f + exp(x)) - 1f).coerceIn(-1f, 1f)
    }

    fun applyCsfMicroContrast(
        pixels: IntArray,
        width: Int,
        height: Int,
        strength: Float
    ): IntArray {
        if (width < 5 || height < 5 || pixels.size < width * height || strength <= 0.001f) {
            return pixels
        }
        val output = pixels.copyOf()
        val baseGain = strength.coerceIn(0f, 1f) * 10.0f
        for (y in 2 until height - 2) {
            for (x in 2 until width - 2) {
                val index = y * width + x
                val center = pixels[index]
                val centerLuma = luminance(center)
                var mean = 0f
                var count = 0
                for (dy in -2..2) {
                    val row = (y + dy) * width
                    for (dx in -2..2) {
                        mean += luminance(pixels[row + x + dx])
                        count++
                    }
                }
                mean /= count.toFloat()

                var variance = 0f
                for (dy in -2..2) {
                    val row = (y + dy) * width
                    for (dx in -2..2) {
                        val d = luminance(pixels[row + x + dx]) - mean
                        variance += d * d
                    }
                }
                variance /= count.toFloat()
                val localContrast = sqrt(variance)
                val textureGate = smoothStep(1.5f, 16f, localContrast) *
                    (1f - smoothStep(42f, 96f, localContrast))
                if (textureGate <= 0.001f) continue

                val l = centerLuma / 255f
                val gain = csfMicroContrastWeight(l, baseGain = baseGain)
                val high = ((centerLuma - mean) / 255f).coerceIn(-0.22f, 0.22f)
                val normalizedDelta = sigmoidMicroContrast(high, gain) * textureGate
                val delta = (normalizedDelta * 18f).coerceIn(-6f, 6f)
                output[index] = addLumaDelta(center, delta)
            }
        }
        return output
    }

    fun applyEdgeDirectedSharpening(
        pixels: IntArray,
        width: Int,
        height: Int,
        strength: Float
    ): IntArray {
        if (width < 5 || height < 5 || pixels.size < width * height || strength <= 0.001f) {
            return pixels
        }
        val output = pixels.copyOf()
        val amount = strength.coerceIn(0f, 1f) * 0.46f
        for (y in 2 until height - 2) {
            for (x in 2 until width - 2) {
                val index = y * width + x
                val center = pixels[index]
                val c = luminance(center)
                val nw = luminance(pixels[(y - 1) * width + x - 1])
                val n = luminance(pixels[(y - 1) * width + x])
                val ne = luminance(pixels[(y - 1) * width + x + 1])
                val w = luminance(pixels[y * width + x - 1])
                val e = luminance(pixels[y * width + x + 1])
                val sw = luminance(pixels[(y + 1) * width + x - 1])
                val s = luminance(pixels[(y + 1) * width + x])
                val se = luminance(pixels[(y + 1) * width + x + 1])

                val gx = (-3f * nw + 3f * ne) + (-10f * w + 10f * e) + (-3f * sw + 3f * se)
                val gy = (-3f * nw - 10f * n - 3f * ne) + (3f * sw + 10f * s + 3f * se)
                val gradient = sqrt(gx * gx + gy * gy)

                val localMin = minOf(nw, n, ne, w, c, e, sw, s, se)
                val localMax = maxOf(nw, n, ne, w, c, e, sw, s, se)
                val localRange = localMax - localMin
                val blur = (c * 4f + (n + s + e + w) * 1.20f + (nw + ne + sw + se) * 0.55f) / 10.6f
                val highPass = c - blur
                val detailMagnitude = abs(highPass)
                val textureGate = smoothStep(1.25f, 9.5f, detailMagnitude) *
                    (1f - smoothStep(34f, 78f, detailMagnitude))
                if (textureGate <= 0.001f) continue

                val tangentConfidence = directionalTextureConfidence(
                    pixels = pixels,
                    width = width,
                    height = height,
                    x = x,
                    y = y,
                    centerLuma = c,
                    gx = gx,
                    gy = gy,
                    gradient = gradient
                )
                val hardEdge = smoothStep(42f, 132f, localRange) * smoothStep(220f, 780f, gradient)
                val edgeAttenuation = (1f - hardEdge * (1f - tangentConfidence * 0.70f)).coerceIn(0.04f, 1f)
                val csf = csfMicroContrastWeight(c / 255f, baseGain = 1f)
                val delta = highPass * amount * textureGate * edgeAttenuation * (0.45f + csf * 1.35f)
                val candidateLuma = c + delta
                val overshoot = max(1.25f, localRange * 0.035f)
                val limitedDelta = candidateLuma
                    .coerceIn(localMin - overshoot, localMax + overshoot) - c
                output[index] = addLumaDelta(center, limitedDelta)
            }
        }
        return output
    }

    fun oklabChroma(pixel: Int): Float {
        val lab = argbToOklab(pixel)
        return sqrt(lab.a * lab.a + lab.b * lab.b)
    }

    fun oklabHueRadians(pixel: Int): Float {
        val lab = argbToOklab(pixel)
        return atan2(lab.b, lab.a)
    }

    fun applyOklabChromaSpeckleGuard(
        pixels: IntArray,
        width: Int,
        height: Int,
        strength: Float
    ): IntArray {
        if (width < 3 || height < 3 || pixels.size < width * height || strength <= 0.001f) {
            return pixels
        }
        val output = pixels.copyOf()
        val radius = 2
        val lambdaSuppress = (0.08f + strength.coerceIn(0f, 1f) * 0.16f)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val pixel = pixels[index]
                val centerLab = argbToOklab(pixel)
                val centerChroma = sqrt(centerLab.a * centerLab.a + centerLab.b * centerLab.b)
                if (centerChroma <= 0.006f) continue

                var mean = 0f
                var count = 0
                for (dy in -radius..radius) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    val row = yy * width
                    for (dx in -radius..radius) {
                        val xx = (x + dx).coerceIn(0, width - 1)
                        mean += oklabChroma(pixels[row + xx])
                        count++
                    }
                }
                mean /= count.toFloat()
                if (centerChroma <= mean * 1.10f) continue

                var variance = 0f
                for (dy in -radius..radius) {
                    val yy = (y + dy).coerceIn(0, height - 1)
                    val row = yy * width
                    for (dx in -radius..radius) {
                        val xx = (x + dx).coerceIn(0, width - 1)
                        val d = oklabChroma(pixels[row + xx]) - mean
                        variance += d * d
                    }
                }
                variance = (variance / count.toFloat()).coerceAtLeast(1.0e-5f)
                val deviation = centerChroma - mean
                val normalizedOutlier = (deviation * deviation / variance).coerceIn(0f, 32f)
                val darkWeight = 1f - smoothStep(0.18f, 0.74f, centerLab.l)
                val outlierWeight = smoothStep(1.4f, 6.0f, normalizedOutlier)
                val scale = exp(-normalizedOutlier * lambdaSuppress * (0.42f + darkWeight * 0.58f) * outlierWeight)
                    .coerceIn(0.35f, 1f)
                if (scale >= 0.995f) continue
                val filteredLab = centerLab.copy(a = centerLab.a * scale, b = centerLab.b * scale)
                output[index] = oklabToArgb((pixel ushr 24) and 0xFF, filteredLab)
            }
        }
        return output
    }

    fun detectFaceCandidates(
        pixels: IntArray,
        width: Int,
        height: Int,
        confidenceThreshold: Float,
        maxFaces: Int = 4
    ): List<FaceCandidate> {
        if (width < 32 || height < 32 || pixels.size < width * height) {
            return emptyList()
        }
        val profile = analyzePixels(pixels, width, height)
        val candidates = mutableListOf<FaceCandidate>()
        val shortEdge = minOf(width, height)
        val windowWidths = intArrayOf(
            (shortEdge * 0.35f).toInt(), // UPGRADED: Sparse sampling for speed
            (shortEdge * 0.45f).toInt()
        )
        // ARCHITECTURAL FIX: Sub-pixel jitter seed prevents aliasing on fine textures
        val jitterSeed = (System.nanoTime() and 0x7).toInt()
        for (windowWidth in windowWidths) {
            val faceWidth = windowWidth.coerceAtLeast(40) // Increased min size
            val faceHeight = (faceWidth * 1.25f).toInt().coerceAtMost(height - 2)
            if (faceHeight <= 0 || faceWidth >= width) continue
            val stepX = max(16, faceWidth / 2) // Larger steps for < 1s detection
            val stepY = max(16, faceHeight / 2)
            // Sub-pixel offset ensures small subjects are not missed during high-speed 8K tracking
            // Coerced to 0 to prevent negative indices causing ArrayIndexOutOfBoundsException
            val subPixelOffsetX = ((jitterSeed % 3) - 1).coerceIn(0, maxOf(0, width - faceWidth))
            val subPixelOffsetY = (((jitterSeed shr 2) % 3) - 1).coerceIn(0, maxOf(0, height - faceHeight))
            for (top in subPixelOffsetY until max(1, height - faceHeight) step stepY) {
                for (left in subPixelOffsetX until max(1, width - faceWidth) step stepX) {
                    val score = scoreFaceWindow(
                        pixels = pixels,
                        frameWidth = width,
                        frameHeight = height,
                        left = left,
                        top = top,
                        windowWidth = faceWidth,
                        windowHeight = faceHeight,
                        refinement = profile
                    )
                    if (score >= confidenceThreshold * 0.78f) {
                        candidates += FaceCandidate(
                            x = left.toFloat() / width.toFloat(),
                            y = top.toFloat() / height.toFloat(),
                            width = faceWidth.toFloat() / width.toFloat(),
                            height = faceHeight.toFloat() / height.toFloat(),
                            confidence = score.coerceIn(0f, 1f)
                        )
                    }
                }
            }
        }
        return suppressOverlaps(candidates)
            .sortedByDescending { it.confidence }
            .take(maxFaces)
            .filter { it.confidence >= confidenceThreshold }
    }

    private fun scoreFaceWindow(
        pixels: IntArray,
        frameWidth: Int,
        frameHeight: Int,
        left: Int,
        top: Int,
        windowWidth: Int,
        windowHeight: Int,
        refinement: AdaptiveRefinementProfile
    ): Float {
        val clampedRight = (left + windowWidth).coerceAtMost(frameWidth)
        val clampedBottom = (top + windowHeight).coerceAtMost(frameHeight)
        val halfWidth = max(1, windowWidth / 2)
        var skinSamples = 0
        var totalSamples = 0
        var symmetrySum = 0f
        var featureContrast = 0f
        var laplacianEnergy = 0f
        val sampleStepX = max(2, windowWidth / 18)
        val sampleStepY = max(2, windowHeight / 18)

        for (y in top until clampedBottom step sampleStepY) {
            for (x in left until clampedRight step sampleStepX) {
                val idx = y * frameWidth + x
                val pixel = pixels[idx]
                if (isSkinLike(pixel)) {
                    skinSamples++
                }
                val mirrorX = (left + clampedRight - x - 1).coerceIn(left, clampedRight - 1)
                val mirror = pixels[y * frameWidth + mirrorX]
                symmetrySum += 1f - (abs(luminance(pixel) - luminance(mirror)) / 255f)
                if (x > left && x < clampedRight - 1 && y > top && y < clampedBottom - 1) {
                    val laplacian = abs(
                        4f * luminance(pixel) -
                            luminance(pixels[idx - 1]) -
                            luminance(pixels[idx + 1]) -
                            luminance(pixels[idx - frameWidth]) -
                            luminance(pixels[idx + frameWidth])
                    )
                    laplacianEnergy += laplacian / 255f
                }
                totalSamples++
            }
        }
        if (totalSamples == 0) {
            return 0f
        }

        val eyeBandTop = top + (windowHeight * 0.28f).toInt()
        val eyeBandBottom = top + (windowHeight * 0.48f).toInt()
        val cheekBandTop = top + (windowHeight * 0.48f).toInt()
        val cheekBandBottom = top + (windowHeight * 0.66f).toInt()
        val mouthBandTop = top + (windowHeight * 0.66f).toInt()
        val mouthBandBottom = top + (windowHeight * 0.86f).toInt()
        val eyeAverage = bandAverageLuma(pixels, frameWidth, left, eyeBandTop, clampedRight, eyeBandBottom)
        val cheekAverage = bandAverageLuma(pixels, frameWidth, left, cheekBandTop, clampedRight, cheekBandBottom)
        val mouthAverage = bandAverageLuma(pixels, frameWidth, left, mouthBandTop, clampedRight, mouthBandBottom)
        featureContrast = (
            ((cheekAverage - eyeAverage) / 255f) * 0.6f +
                ((cheekAverage - mouthAverage) / 255f) * 0.4f
            ).coerceIn(0f, 1f)

        val skinRatio = skinSamples.toFloat() / totalSamples.toFloat()
        val symmetry = (symmetrySum / totalSamples.toFloat()).coerceIn(0f, 1f)
        val localSharpness = (laplacianEnergy / totalSamples.toFloat()).coerceIn(0f, 1f)
        val centerBias = 1f - (
            abs((left + halfWidth).toFloat() - frameWidth * 0.5f) /
                (frameWidth * 0.5f)
            ).coerceIn(0f, 1f) * 0.22f
        return (
            skinRatio * 0.42f +
                symmetry * 0.20f +
                featureContrast * 0.22f +
                localSharpness * 0.16f +
                refinement.perceptualSharpness * 0.06f -
                refinement.normalizedNoiseFloor * 0.10f
            ).coerceIn(0f, 1f) * centerBias
    }

    private fun suppressOverlaps(candidates: List<FaceCandidate>): List<FaceCandidate> {
        val result = mutableListOf<FaceCandidate>()
        for (candidate in candidates.sortedByDescending { it.confidence }) {
            if (result.none { overlapRatio(it, candidate) > 0.35f }) {
                result += candidate
            }
        }
        return result
    }

    private fun overlapRatio(a: FaceCandidate, b: FaceCandidate): Float {
        val left = maxOf(a.x, b.x)
        val top = maxOf(a.y, b.y)
        val right = minOf(a.x + a.width, b.x + b.width)
        val bottom = minOf(a.y + a.height, b.y + b.height)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val union = (a.width * a.height) + (b.width * b.height) - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun bandAverageLuma(
        pixels: IntArray,
        frameWidth: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Float {
        var sum = 0f
        var count = 0
        val clampedTop = top.coerceAtLeast(0)
        val clampedBottom = bottom.coerceAtMost(pixels.size / frameWidth)
        for (y in clampedTop until clampedBottom step max(1, (bottom - top) / 6)) {
            for (x in left until right step max(1, (right - left) / 8)) {
                sum += luminance(pixels[y * frameWidth + x])
                count++
            }
        }
        return if (count == 0) 0f else sum / count.toFloat()
    }

    private fun isSkinLike(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val maxChannel = maxOf(r, g, b)
        val minChannel = minOf(r, g, b)
        return r > 45 &&
            g > 32 &&
            b > 16 &&
            (maxChannel - minChannel) > 14 &&
            abs(r - g) > 8 &&
            r > g &&
            r > b
    }

    private fun luminance(pixel: Int): Float {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun directionalTextureConfidence(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        centerLuma: Float,
        gx: Float,
        gy: Float,
        gradient: Float
    ): Float {
        if (gradient < 1.0e-3f) return 1f
        val nx = gx / gradient
        val ny = gy / gradient
        val tx = -ny
        val ty = nx
        val tangentA = sampleLumaNearest(pixels, width, height, x + tx, y + ty)
        val tangentB = sampleLumaNearest(pixels, width, height, x - tx, y - ty)
        val normalA = sampleLumaNearest(pixels, width, height, x + nx, y + ny)
        val normalB = sampleLumaNearest(pixels, width, height, x - nx, y - ny)
        val tangentDetail = abs(2f * centerLuma - tangentA - tangentB)
        val normalDetail = abs(2f * centerLuma - normalA - normalB)
        return (tangentDetail / (tangentDetail + normalDetail + 1.0e-3f)).coerceIn(0f, 1f)
    }

    private fun sampleLumaNearest(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float
    ): Float {
        val xx = x.roundToInt().coerceIn(0, width - 1)
        val yy = y.roundToInt().coerceIn(0, height - 1)
        return luminance(pixels[yy * width + xx])
    }

    private fun addLumaDelta(pixel: Int, delta: Float): Int {
        if (abs(delta) <= 0.001f) return pixel
        val a = (pixel ushr 24) and 0xFF
        val rOrig = (pixel shr 16) and 0xFF
        val gOrig = (pixel shr 8) and 0xFF
        val bOrig = pixel and 0xFF

        // Use absolute addition to preserve hue ratio and prevent red-channel blowouts
        val r = (rOrig + delta).roundToInt().coerceIn(0, 255)
        val g = (gOrig + delta).roundToInt().coerceIn(0, 255)
        val b = (bOrig + delta).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun argbToOklab(pixel: Int): OklabPixel {
        val r = srgbToLinear((pixel shr 16) and 0xFF)
        val g = srgbToLinear((pixel shr 8) and 0xFF)
        val b = srgbToLinear(pixel and 0xFF)
        val l = cubeRoot(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cubeRoot(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cubeRoot(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        return OklabPixel(
            l = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            b = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
        )
    }

    private fun oklabToArgb(alpha: Int, lab: OklabPixel): Int {
        val lPrime = lab.l + 0.3963377774f * lab.a + 0.2158037573f * lab.b
        val mPrime = lab.l - 0.1055613458f * lab.a - 0.0638541728f * lab.b
        val sPrime = lab.l - 0.0894841775f * lab.a - 1.2914855480f * lab.b
        val l = lPrime * lPrime * lPrime
        val m = mPrime * mPrime * mPrime
        val s = sPrime * sPrime * sPrime
        val r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        val g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        val b = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
        return (alpha.coerceIn(0, 255) shl 24) or
            (linearToSrgb(r).coerceIn(0, 255) shl 16) or
            (linearToSrgb(g).coerceIn(0, 255) shl 8) or
            linearToSrgb(b).coerceIn(0, 255)
    }

    private fun srgbToLinear(channel: Int): Float {
        val c = channel.coerceIn(0, 255) / 255f
        return if (c <= 0.04045f) {
            c / 12.92f
        } else {
            Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
    }

    private fun linearToSrgb(channel: Float): Int {
        val c = channel.coerceIn(0f, 1f)
        val encoded = if (c <= 0.0031308f) {
            c * 12.92f
        } else {
            1.055f * Math.pow(c.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
        }
        return (encoded * 255f).roundToInt()
    }

    private fun cubeRoot(value: Float): Float {
        return Math.cbrt(value.coerceAtLeast(0f).toDouble()).toFloat()
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0).coerceAtLeast(1.0e-6f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

}
