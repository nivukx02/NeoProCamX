package com.neoproc.neoproccamx.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object EnhancementLumaMath {
    fun luminanceByte(argb: Int): Float {
        val r = ((argb shr 16) and 0xFF).toFloat()
        val g = ((argb shr 8) and 0xFF).toFloat()
        val b = (argb and 0xFF).toFloat()
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
}

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * ENHANCEMENT ENGINE - cinema-oriented NOISE REDUCTION + SHARPNESS PIPELINE
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Section 17 upgrade:
 * - Edge-preserving bilateral-like spatial denoising (replaces mislabeled
 *   Laplacian "denoise" which was actually sharpening)
 * - Luminance-only unsharp mask with halo guard (prevents color fringing)
 * - Micro-contrast enhancement with variance-adaptive strength
 * - ISP BYPASS GUARD: enhancement is SKIPPED when BEYOND_IMAX_RAW /
 *   BEYOND_IMAX_CINEMA is active to keep the sensor output pristine
 * - Scene-aware parameter tuning
 * - High-res downsample guard (process at half-res when > 12MP, scale back)
 * - CNN denoise infrastructure (falls back to bilateral when no TFLite model)
 *
 * Processing order (corrected): DENOISE → SHARPEN → MICRO-CONTRAST
 */
class EnhancementEngine(context: Context) {
    companion object {
        private const val TAG = "EnhancementEngine"
        private const val DENOISE_STRENGTH_BASE = 0.22f
        private const val SHARPEN_STRENGTH_BASE = 0.10f
        private const val MICRO_CONTRAST_STRENGTH_BASE = 0.12f
        private const val MIN_BITMAP_DIMENSION = 320
        /** If either edge exceeds this, process at half resolution then scale. */
        private const val DOWNSAMPLE_THRESHOLD_PX = 4000
        /** Spatial sigma for bilateral-like denoise (pixels). */
        private const val BILATERAL_SPATIAL_SIGMA = 2.0f
        /** Intensity sigma for bilateral-like denoise (0-255 luma range). */
        private const val BILATERAL_INTENSITY_SIGMA = 18.0f
    }

    private val appContext = context.applicationContext
    private val neuralDenoiseEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NeuralDenoiseEngine(appContext)
    }
    private var lastProfileAnalysis: Any? = null

    data class EnhancementConfig(
        val denoiseStrength: Float = DENOISE_STRENGTH_BASE,
        val sharpenStrength: Float = SHARPEN_STRENGTH_BASE,
        val microContrastStrength: Float = MICRO_CONTRAST_STRENGTH_BASE,
        val preserveDetails: Boolean = true,
        val adaptiveByScene: Boolean = true,
        /** When true, enhancement is completely bypassed (ISP bypass mode). */
        val bypassEnhancement: Boolean = false,
        val denoiseSpatialSigma: Float = BILATERAL_SPATIAL_SIGMA,
        val denoiseIntensitySigma: Float = BILATERAL_INTENSITY_SIGMA,
        val residualChromaSuppression: Float = 0f
    )

    data class EnhancementMetrics(
        val estimatedNoiseLevel: Float,
        val perceptualSharpness: Float,
        val enhancedPerceptualSharpness: Float,
        val denoisingApplied: Boolean,
        val sharpeningApplied: Boolean,
        val microContrastApplied: Boolean,
        val processingTimeMs: Long,
        val resolutionScaleApplied: Boolean = false,
        /** s7-f09/CP99 — honest denoise path actually taken (neural hook vs classical fallback). */
        val denoisePath: DenoisePath = DenoisePath.NONE
    )

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    /**
     * Apply full enhancement suite to input bitmap.
     *
     * Section 17 order: DENOISE → SHARPEN → MICRO-CONTRAST.
     * When [config.bypassEnhancement] is true (ISP bypass), returns input untouched.
     */
    fun enhance(
        bitmap: Bitmap,
        sceneType: SceneType = SceneType.GENERAL,
        config: EnhancementConfig = EnhancementConfig()
    ): Pair<Bitmap, EnhancementMetrics> {
        val startTime = System.currentTimeMillis()

        try {
            // Section 17: ISP bypass guard — keep sensor output pristine
            if (config.bypassEnhancement) {
                return bitmap to EnhancementMetrics(
                    estimatedNoiseLevel = 0f,
                    perceptualSharpness = 0.5f,
                    enhancedPerceptualSharpness = 0.5f,
                    denoisingApplied = false,
                    sharpeningApplied = false,
                    microContrastApplied = false,
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }

            // Early exit for small bitmaps
            if (bitmap.width < MIN_BITMAP_DIMENSION || bitmap.height < MIN_BITMAP_DIMENSION) {
                return bitmap to EnhancementMetrics(
                    estimatedNoiseLevel = 0f,
                    perceptualSharpness = 0.5f,
                    enhancedPerceptualSharpness = 0.5f,
                    denoisingApplied = false,
                    sharpeningApplied = false,
                    microContrastApplied = false,
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }

            // Section 17: downsample guard for very high-res images (> 12MP effective)
            val downsample = strictHighEndEnabled() &&
                (bitmap.width > DOWNSAMPLE_THRESHOLD_PX || bitmap.height > DOWNSAMPLE_THRESHOLD_PX)
            val workBitmap = if (downsample) {
                bitmap.scale(bitmap.width / 2, bitmap.height / 2, true)
            } else bitmap

            // Extract pixels
            val w = workBitmap.width
            val h = workBitmap.height
            val pixels = IntArray(w * h)
            workBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            
            // Fix OOM: Recycle the working bitmap immediately after extracting pixels
            // (Only if it's a downsampled copy we created, NOT the original bitmap)
            if (downsample) {
                workBitmap.recycle()
            }

            // Analyze perceptual characteristics
            val profile = PerceptualRefinementMath.analyzePixels(
                pixels = pixels, width = w, height = h
            )
            lastProfileAnalysis = profile

            val adaptiveConfig = if (config.adaptiveByScene) {
                computeAdaptiveConfig(config, sceneType, profile)
            } else config

            var enhancedPixels = pixels
            var denoisePath = DenoisePath.NONE

            // ── Step 1: DENOISE (edge-preserving bilateral-like spatial filter)
            val denoisingApplied = adaptiveConfig.denoiseStrength > 0.01f
            if (denoisingApplied) {
                val neuralDenoiseResult = runCatching {
                    neuralDenoiseEngine.denoisePixels(
                        pixels = enhancedPixels,
                        width = w,
                        height = h,
                        request = NeuralDenoiseEngine.DenoiseRequest(
                            lumaThreshold = adaptiveConfig.denoiseStrength,
                            chromaThreshold = max(
                                adaptiveConfig.residualChromaSuppression,
                                adaptiveConfig.denoiseStrength * 0.72f
                            ),
                            texturePreservation = if (strictHighEndEnabled()) 0.78f else 0.66f,
                            chromaSpeckleGuardStrength = adaptiveConfig.residualChromaSuppression
                        )
                    )
                }.getOrNull()
                enhancedPixels = neuralDenoiseResult?.pixels ?: applyBilateralDenoise(
                    pixels = enhancedPixels,
                    width = w,
                    height = h,
                    spatialSigma = adaptiveConfig.denoiseSpatialSigma,
                    intensitySigma = adaptiveConfig.denoiseIntensitySigma,
                    strength = adaptiveConfig.denoiseStrength
                )
                denoisePath = AiEnhancementSignalPolicy.denoisePath(
                    denoiseApplied = true,
                    neuralResultPresent = neuralDenoiseResult != null,
                    usedNeuralModel = neuralDenoiseResult?.usedNeuralModel == true,
                    usedGuidedFallback = neuralDenoiseResult?.usedGuidedFallback == true
                )
                if (adaptiveConfig.residualChromaSuppression > 0.01f) {
                    enhancedPixels = applyResidualChromaSuppression(
                        pixels = enhancedPixels,
                        strength = adaptiveConfig.residualChromaSuppression
                    )
                }
            }

            // ── Step 2: SHARPEN (luminance-only unsharp mask with halo guard)
            val sharpeningApplied = adaptiveConfig.sharpenStrength > 0.01f
            if (sharpeningApplied) {
                enhancedPixels = applyUnsharpMaskLuma(
                    pixels = enhancedPixels,
                    width = w,
                    height = h,
                    strength = adaptiveConfig.sharpenStrength,
                    radius = if (strictHighEndEnabled()) 1.8f else 1.5f
                )
            }

            // ── Step 3: MICRO-CONTRAST (variance-adaptive local contrast)
            val microContrastApplied = adaptiveConfig.microContrastStrength > 0.01f
            if (microContrastApplied) {
                enhancedPixels = applyMicroContrast(
                    pixels = enhancedPixels,
                    width = w,
                    height = h,
                    strength = adaptiveConfig.microContrastStrength
                )
            }

            // Write back to bitmap
            val processedBitmap = createBitmap(w, h, Config.ARGB_8888)
            processedBitmap.setPixels(enhancedPixels, 0, w, 0, 0, w, h)

            // Scale back up if downsampled
            val outputBitmap = if (downsample) {
                val upscaled = processedBitmap.scale(bitmap.width, bitmap.height, true)
                processedBitmap.recycle() // Fix OOM: Recycle the intermediate smaller bitmap
                upscaled
            } else processedBitmap

            // Final metrics
            val finalProfile = PerceptualRefinementMath.analyzePixels(
                pixels = enhancedPixels, width = w, height = h
            )

            val metrics = EnhancementMetrics(
                estimatedNoiseLevel = profile.normalizedNoiseFloor,
                perceptualSharpness = profile.perceptualSharpness,
                enhancedPerceptualSharpness = finalProfile.perceptualSharpness,
                denoisingApplied = denoisingApplied,
                sharpeningApplied = sharpeningApplied,
                microContrastApplied = microContrastApplied,
                processingTimeMs = System.currentTimeMillis() - startTime,
                resolutionScaleApplied = downsample,
                denoisePath = denoisePath
            )

            return outputBitmap to metrics

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Enhancement OOM — returning original", e)
            return bitmap to EnhancementMetrics(
                estimatedNoiseLevel = 0f, perceptualSharpness = 0.5f,
                enhancedPerceptualSharpness = 0.5f, denoisingApplied = false,
                sharpeningApplied = false, microContrastApplied = false,
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Enhancement failed", e)
            return bitmap to EnhancementMetrics(
                estimatedNoiseLevel = 0f, perceptualSharpness = 0.5f,
                enhancedPerceptualSharpness = 0.5f, denoisingApplied = false,
                sharpeningApplied = false, microContrastApplied = false,
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * Compute adaptive enhancement parameters based on scene characteristics
     */
    private fun computeAdaptiveConfig(
        baseConfig: EnhancementConfig,
        sceneType: SceneType,
        profile: Any  // AdaptiveRefinementProfile from PerceptualRefinementMath
    ): EnhancementConfig {
        val p = profile as? AdaptiveRefinementProfile
            ?: return baseConfig

        val (denoiseAdaptive, sharpenAdaptive, microContrastAdaptive) = when (sceneType) {
            SceneType.NIGHT, SceneType.NIGHT_EXTREME -> {
                // Low light: aggressive denoise, moderate sharpen
                Triple(
                    baseConfig.denoiseStrength * 1.5f,
                    baseConfig.sharpenStrength * 0.7f,
                    baseConfig.microContrastStrength * 1.2f
                )
            }
            SceneType.PORTRAIT, SceneType.PORTRAIT_LOW_LIGHT -> {
                // Portrait: light denoise (preserve skin texture), moderate sharpen
                Triple(
                    baseConfig.denoiseStrength * 0.55f,
                    baseConfig.sharpenStrength * 0.55f,
                    baseConfig.microContrastStrength * 1.4f
                )
            }
            SceneType.CONCERT, SceneType.ACTION -> {
                // High motion: balanced enhancement
                Triple(
                    baseConfig.denoiseStrength * 1.0f,
                    baseConfig.sharpenStrength * 1.05f,
                    baseConfig.microContrastStrength * 0.9f
                )
            }
            SceneType.LANDSCAPE, SceneType.OUTDOOR -> {
                // Detail-rich: light denoise, strong sharpen
                Triple(
                    baseConfig.denoiseStrength * 0.6f,
                    baseConfig.sharpenStrength * 1.35f,
                    baseConfig.microContrastStrength * 1.05f
                )
            }
            else -> {
                Triple(
                    baseConfig.denoiseStrength * 0.9f,
                    baseConfig.sharpenStrength * 1.0f,
                    baseConfig.microContrastStrength * 0.95f
                )
            }
        }

        // Section 17: noise-aware ceiling — if the image is already very clean,
        // cap denoise to avoid over-smoothing.
        val noiseAwareDenoise = if (p.normalizedNoiseFloor < 0.04f) {
            min(denoiseAdaptive, 0.15f)
        } else denoiseAdaptive
        val noiseTuning = AiNoiseReductionPolicy.resolve(
            baseDenoiseStrength = noiseAwareDenoise,
            sceneType = sceneType,
            profile = p,
            strictHighEnd = strictHighEndEnabled()
        )

        val maxSharpen = if (strictHighEndEnabled()) 0.45f else 0.22f
        val maxMicroContrast = if (strictHighEndEnabled()) 0.50f else 0.28f
        // s7-f09/CP99: signal-adaptive sharpen back-off (gated). Flag OFF (default) => scale 1f so
        // sharpen/micro-contrast are unchanged; ON => scale DOWN as the measured noise floor rises so
        // noise is not amplified into fake detail.
        val signalScale = if (EnhancementSignalAdaptiveFlag.enabled) {
            AiEnhancementSignalPolicy.signalAdaptiveSharpenScale(p.normalizedNoiseFloor, strictHighEndEnabled())
        } else {
            1f
        }
        return baseConfig.copy(
            denoiseStrength = noiseTuning.denoiseStrength,
            sharpenStrength = (sharpenAdaptive * signalScale).coerceIn(0f, maxSharpen),
            microContrastStrength = (microContrastAdaptive * signalScale).coerceIn(0f, maxMicroContrast),
            denoiseSpatialSigma = noiseTuning.spatialSigma,
            denoiseIntensitySigma = noiseTuning.intensitySigma,
            residualChromaSuppression = noiseTuning.residualChromaSuppression
        )
    }

    /**
     * Section 17: Luminance-only unsharp mask sharpening.
     *
     * Operates on luma channel only (Rec.709 coefficients) to prevent color
     * fringing. Applies a halo guard: if local contrast already exceeds a
     * threshold, sharpening is attenuated to avoid ringing on hard edges.
     */
    private fun applyUnsharpMaskLuma(
        pixels: IntArray,
        width: Int,
        height: Int,
        strength: Float,
        radius: Float = 1.5f
    ): IntArray {
        if (width < 3 || height < 3) return pixels

        val output = pixels.copyOf()
        val finalStrength = strength.coerceIn(0f, 1f) * if (strictHighEndEnabled()) 0.82f else 0.68f
        val haloGuardThreshold = 48f  // luma difference that triggers attenuation
        // s7-f09/CP99: explicit unsharp overshoot clamp (gated). OFF (default) => no change.
        val signalAdaptiveOvershootOn = EnhancementSignalAdaptiveFlag.enabled
        val unsharpOvershootCeiling = if (strictHighEndEnabled()) {
            AiEnhancementSignalPolicy.MAX_UNSHARP_OVERSHOOT_STRICT
        } else {
            AiEnhancementSignalPolicy.MAX_UNSHARP_OVERSHOOT
        }

        val lumaBuffer = FloatArray(width * height)
        for (i in pixels.indices) {
            lumaBuffer[i] = luminance(pixels[i])
        }

        for (y in 1 until height - 1) {
            val rowIdx = y * width
            val prevRowIdx = (y - 1) * width
            val nextRowIdx = (y + 1) * width

            for (x in 1 until width - 1) {
                val idx = rowIdx + x
                val center = pixels[idx]

                // Local blur (box average of 4-neighborhood + center weighted)
                val centerLuma = lumaBuffer[idx]
                val avgLuma = (
                    lumaBuffer[idx - 1] +
                    lumaBuffer[idx + 1] +
                    lumaBuffer[prevRowIdx + x] +
                    lumaBuffer[nextRowIdx + x] +
                    centerLuma * 4f
                ) / 8f

                val delta = (centerLuma - avgLuma) * finalStrength

                if (abs(delta) > 0.45f) {
                    // Halo guard: attenuate sharpening on already-high-contrast edges
                    val localContrast = abs(centerLuma - avgLuma)
                    val noiseGate = ((localContrast - 1.75f) / 9f).coerceIn(0f, 1f)
                    val attenuation = if (localContrast > haloGuardThreshold) {
                        1f - ((localContrast - haloGuardThreshold) / 80f).coerceIn(0f, 0.75f)
                    } else 1f
                    val attenuatedDelta = if (signalAdaptiveOvershootOn) {
                        AiEnhancementSignalPolicy.clampUnsharpOvershoot(delta * attenuation * noiseGate, unsharpOvershootCeiling)
                    } else {
                        delta * attenuation * noiseGate
                    }

                    val rOrig = (center shr 16) and 0xFF
                    val gOrig = (center shr 8) and 0xFF
                    val bOrig = center and 0xFF
                    val a = (center shr 24) and 0xFF

                    // Preserve chromaticity: scale RGB proportional to luma change
                    val blend = if (centerLuma > 0.5f) (centerLuma + attenuatedDelta) / centerLuma else 1f
                    val r = (rOrig * blend).roundToInt().coerceIn(0, 255)
                    val g = (gOrig * blend).roundToInt().coerceIn(0, 255)
                    val b = (bOrig * blend).roundToInt().coerceIn(0, 255)

                    output[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        return output
    }

    /**
     * Section 17: Edge-preserving bilateral-like spatial denoising.
     *
     * For each pixel, compute a weighted average of its 3x3 neighborhood where
     * weights fall off with both spatial distance and intensity (luma) distance.
     * This preserves hard edges while smoothing flat regions where noise lives.
     *
     * This is a fast approximation of the bilateral filter using a fixed 3x3
     * kernel and pre-computed intensity weights.
     */
    private fun applyBilateralDenoise(
        pixels: IntArray,
        width: Int,
        height: Int,
        spatialSigma: Float,
        intensitySigma: Float,
        strength: Float
    ): IntArray {
        val use5x5 = strictHighEndEnabled() || (width >= 3840 || height >= 2160)
        val radius = if (use5x5) 2 else 1
        if (width < (2 * radius + 1) || height < (2 * radius + 1)) return pixels

        val output = IntArray(pixels.size)
        System.arraycopy(pixels, 0, output, 0, pixels.size)

        val invTwoSigmaSq = 1f / (2f * spatialSigma * spatialSigma)
        val invTwoIntSigmaSq = 1f / (2f * intensitySigma * intensitySigma)

        val size = 2 * radius + 1
        val spatialKernel = FloatArray(size * size)
        var ki = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val distSq = (dx * dx + dy * dy).toFloat()
                spatialKernel[ki] = exp(-distSq * invTwoSigmaSq)
                ki++
            }
        }
        val kernelSum = spatialKernel.sum()

        for (y in radius until height - radius) {
            val row = y * width
            for (x in radius until width - radius) {
                val idx = row + x
                val center = pixels[idx]
                val centerLuma = luminance(center)

                var weightSum = 0f
                var rAcc = 0f
                var gAcc = 0f
                var bAcc = 0f

                var kIdx = 0
                for (dy in -radius..radius) {
                    val ny = y + dy
                    val nRow = ny * width
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        val nIdx = nRow + nx
                        val neighbor = pixels[nIdx]
                        val neighborLuma = luminance(neighbor)

                        val spatialWeight = spatialKernel[kIdx]
                        val intensityDiff = abs(neighborLuma - centerLuma)
                        val intensityWeight = exp(-intensityDiff * intensityDiff * invTwoIntSigmaSq)
                        val weight = spatialWeight * intensityWeight

                        weightSum += weight
                        rAcc += weight * ((neighbor shr 16) and 0xFF)
                        gAcc += weight * ((neighbor shr 8) and 0xFF)
                        bAcc += weight * (neighbor and 0xFF)
                        kIdx++
                    }
                }

                val norm = if (weightSum > 0.001f) weightSum else kernelSum
                val r = (rAcc / norm).toInt().coerceIn(0, 255)
                val g = (gAcc / norm).toInt().coerceIn(0, 255)
                val b = (bAcc / norm).toInt().coerceIn(0, 255)
                val a = (center shr 24) and 0xFF

                val origR = (center shr 16) and 0xFF
                val origG = (center shr 8) and 0xFF
                val origB = center and 0xFF
                val mix = strength.coerceIn(0f, 1f)
                val finalR = (origR + mix * (r - origR)).toInt().coerceIn(0, 255)
                val finalG = (origG + mix * (g - origG)).toInt().coerceIn(0, 255)
                val finalB = (origB + mix * (b - origB)).toInt().coerceIn(0, 255)

                output[idx] = (a shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
            }
        }
        return output
    }

    private fun applyResidualChromaSuppression(
        pixels: IntArray,
        strength: Float
    ): IntArray {
        val mixStrength = strength.coerceIn(0f, 1f)
        if (mixStrength <= 0.01f) return pixels
        val output = pixels.copyOf()
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val y = EnhancementLumaMath.luminanceByte(pixel)
            val maxChannel = max(r, max(g, b)).toFloat()
            val minChannel = min(r, min(g, b)).toFloat()
            val chroma = (maxChannel - minChannel) / 255f
            val darkWeight = (1f - ((y - 42f) / 148f).coerceIn(0f, 1f))
            val speckleWeight = ((chroma - 0.012f) / 0.10f).coerceIn(0f, 1f)
            val flatNoiseWeight = (1f - ((chroma - 0.22f) / 0.18f).coerceIn(0f, 1f))
            val blend = (mixStrength * (0.38f + darkWeight * 0.82f) * speckleWeight * flatNoiseWeight)
                .coerceIn(0f, 0.94f)
            if (blend <= 0.001f) continue
            val a = (pixel shr 24) and 0xFF
            val neutral = y.roundToInt().coerceIn(0, 255)
            val finalR = (r + (neutral - r) * blend).roundToInt().coerceIn(0, 255)
            val finalG = (g + (neutral - g) * blend).roundToInt().coerceIn(0, 255)
            val finalB = (b + (neutral - b) * blend).roundToInt().coerceIn(0, 255)
            output[i] = (a shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }
        return output
    }

    /**
     * Apply micro-contrast enhancement for perceptual quality boost
     */
    private fun applyMicroContrast(
        pixels: IntArray,
        width: Int,
        height: Int,
        strength: Float
    ): IntArray {
        if (width < 5 || height < 5) return pixels
        
        val output = pixels.copyOf()
        val adaptiveStrength = strength.coerceIn(0f, 1f) * if (strictHighEndEnabled()) 0.26f else 0.20f
        
        val lumaBuffer = FloatArray(width * height)
        for (i in pixels.indices) {
            lumaBuffer[i] = luminance(pixels[i])
        }

        for (y in 2 until height - 2) {
            val rowIdx = y * width
            
            for (x in 2 until width - 2) {
                val idx = rowIdx + x
                val center = pixels[idx]
                val centerLuma = lumaBuffer[idx]
                
                // Compute local mean and variance (3x3 window)
                var mean = 0f
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        mean += lumaBuffer[(y + dy) * width + (x + dx)]
                    }
                }
                mean /= 9f

                var variance = 0f
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val neighborLuma = lumaBuffer[(y + dy) * width + (x + dx)]
                        variance += (neighborLuma - mean) * (neighborLuma - mean)
                    }
                }
                variance /= 9f
                
                // Apply micro-contrast based on local variance
                val localContrast = sqrt(variance)
                if (localContrast > 2f) {
                    val noiseGate = ((localContrast - 2f) / 12f).coerceIn(0f, 1f)
                    val haloGuard = 1f - ((localContrast - 52f) / 70f).coerceIn(0f, 0.72f)
                    val detailSign = if (centerLuma >= mean) 1f else -1f
                    val boost = (localContrast.coerceAtMost(38f) * adaptiveStrength * noiseGate * haloGuard)
                        .coerceIn(0f, 4.5f)

                    val boostDelta = boost * detailSign
                    val rOrig = (center shr 16) and 0xFF
                    val gOrig = (center shr 8) and 0xFF
                    val bOrig = center and 0xFF
                    val a = (center shr 24) and 0xFF

                    // Preserve chromaticity: scale RGB proportional to luma change
                    val blend = if (centerLuma > 0.5f) (centerLuma + boostDelta) / centerLuma else 1f
                    val r = (rOrig * blend).roundToInt().coerceIn(0, 255)
                    val g = (gOrig * blend).roundToInt().coerceIn(0, 255)
                    val b = (bOrig * blend).roundToInt().coerceIn(0, 255)
                    
                    output[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        
        return output
    }

    private fun luminance(argb: Int): Float {
        return EnhancementLumaMath.luminanceByte(argb)
    }

    /**
     * Get last analysis profile for telemetry
     */
    fun getLastProfile(): Any? = lastProfileAnalysis

    /**
     * Reset internal state
     */
    fun reset() {
        lastProfileAnalysis = null
    }
}
