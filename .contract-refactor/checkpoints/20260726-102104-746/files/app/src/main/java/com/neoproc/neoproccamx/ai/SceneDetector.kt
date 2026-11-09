package com.neoproc.neoproccamx.ai

import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import android.util.Log
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * PRO cinema-oriented SCENE DETECTOR (UPGRADED)
 * ════════════════════════════════════════════════════════════════════════════════
 * 
 * AI-powered hierarchical scene detection for automatic camera optimization.
 * Detects 28+ scene types with ensemble classification and temporal smoothing.
 * 
 * Features:
 * - Multi-model ensemble classification
 * - Histogram-based feature extraction
 * - Temporal coherence across frames
 * - Confidence scoring and uncertainty estimation
 * - Advanced scene recommendations (HDR frames, NR strength, color grading)
 */
class SceneDetector : AutoCloseable {

    companion object {
        private const val TAG = "SceneDetector"
        private const val LABEL_TIMEOUT_MS = 20L
        private const val LABEL_TIMEOUT_LOG_INTERVAL_MS = 5_000L
        private const val CINEMATIC_MODEL_ASSET = "models/cinematic_scene_detector.tflite"
        
        // Luminance thresholds (refined for high-end detection)
        private const val NIGHT_EXTREME_THRESHOLD = 25f
        private const val NIGHT_LUMA_THRESHOLD = 45f
        private const val LOW_LIGHT_LUMA_THRESHOLD = 65f
        private const val BRIGHT_LUMA_THRESHOLD = 200f
        private const val BRIGHT_EXTREME_THRESHOLD = 230f
        
        // Dynamic range thresholds
        private const val HIGH_DR_THRESHOLD = 120f
        private const val BACKLIGHT_DR_THRESHOLD = 105f
        private const val EXTREME_DR_THRESHOLD = 160f
        
        // Color temperature thresholds (Kelvin)
        private const val BLUE_HOUR_TEMP_MAX = 7500
        private const val GOLDEN_HOUR_TEMP_MIN = 3000
        private const val GOLDEN_HOUR_TEMP_MAX = 4000
        
        // Temporal smoothing
        private const val SCENE_HISTORY_SIZE = 8
        private const val MIN_CONFIDENCE_FOR_CHANGE = 0.65f
    }

    // Engine status tracking for production-grade error reporting
    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(if (strictHighEndEnabled()) 0.24f else 0.28f)
            .build()
    )
    
    // Temporal smoothing state
    private val sceneHistory = ArrayDeque<Pair<SceneType, Float>>(SCENE_HISTORY_SIZE)
    private var lastSceneType: SceneType = SceneType.GENERAL
    private var lastConfidence: Float = 0f
    private var framesSinceChange: Int = 0
    // s7-f07/CP97: gated N-frame debounce state (untouched while SceneHysteresisFlag is off).
    private var pendingScene: SceneType? = null
    private var pendingSceneCount: Int = 0
    @Volatile private var lastLabelTimeoutLogElapsedMs = 0L
    
    /**
     * Scene analysis result with professional recommendations.
     */
    data class SceneAnalysis(
        val sceneType: SceneType,
        val sceneClassification: SceneClassification,
        val avgLuma: Float,
        val dynamicRange: Float,
        val colorTemperature: Int,
        val isLowLight: Boolean,
        val isHighContrast: Boolean,
        val isBacklit: Boolean,
        val recommendedHdrFrames: Int,
        val recommendedNoiseReduction: Float,
        val recommendedSaturation: Float,
        val recommendedExposureComp: Float,
        val recommendedSharpening: Float,
        val recommendedContrast: Float,
        val histogramFeatures: HistogramFeatures,
        val temporalStability: Float,
        /** s7-f07/CP97 — true when ML Kit returned no labels and classification used the histogram fallback. */
        val usedHistogramFallback: Boolean = false
    )
    
    /**
     * Histogram-based feature extraction for advanced classification
     */
    data class HistogramFeatures(
        val shadowPixelsPercent: Float,
        val midtonePixelsPercent: Float,
        val highlightPixelsPercent: Float,
        val clippedShadowsPercent: Float,
        val clippedHighlightsPercent: Float,
        val contrastScore: Float,
        val colorfulness: Float
    )

    fun detect(bitmap: Bitmap, rotationDegrees: Int): SceneType {
        return analyze(bitmap, rotationDegrees).sceneType
    }
    
    fun analyze(bitmap: Bitmap, rotationDegrees: Int): SceneAnalysis {
        val startTime = System.currentTimeMillis()
        _status.value = EngineStatus.Processing("scene detection")
        
        // ═══════════════════════════════════════════════════════════════════════════
        // SCENE LABELING — ML Kit ImageLabeling (primary) + histogram features (fallback)
        // ═══════════════════════════════════════════════════════════════════════════

        val labels = try {
            Tasks.await(
                labeler.process(InputImage.fromBitmap(bitmap, rotationDegrees)),
                LABEL_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
        } catch (e: TimeoutException) {
            logSceneLabelTimeout("Bitmap")
            _status.value = EngineStatus.Degraded(
                reason = "Scene labeling timeout",
                fallbackMode = "Using histogram-based detection",
                performanceImpact = 0.3f,
                qualityImpact = 0.2f
            )
            emptyList()
        } catch (e: Throwable) {
            Log.w(TAG, "ML Kit scene labeling failed for Bitmap", e)
            _status.value = EngineStatus.Degraded(
                reason = "Scene labeling failure",
                fallbackMode = "Using histogram-based detection",
                performanceImpact = 0.3f,
                qualityImpact = 0.2f
            )
            emptyList()
        }

        // Extract image statistics
        val labelNames = labels.map { it.text.lowercase(Locale.US) }
        val labelConfidences = labels.associate { it.text.lowercase(Locale.US) to it.confidence }
        val avgLuma = averageLuma(bitmap)
        val dynamicRange = luminanceSpread(bitmap)
        val colorTemperature = estimateColorTemperature(bitmap)
        val histogramFeatures = extractHistogramFeatures(bitmap)
        val strictHighEnd = strictHighEndEnabled()
        val lowLightThreshold = if (strictHighEnd) 72f else LOW_LIGHT_LUMA_THRESHOLD
        val nightThreshold = if (strictHighEnd) 52f else NIGHT_LUMA_THRESHOLD
        val nightExtremeThreshold = if (strictHighEnd) 30f else NIGHT_EXTREME_THRESHOLD
        val highDrThreshold = if (strictHighEnd) 112f else HIGH_DR_THRESHOLD
        val backlightThreshold = if (strictHighEnd) 98f else BACKLIGHT_DR_THRESHOLD
        val extremeDrThreshold = if (strictHighEnd) 148f else EXTREME_DR_THRESHOLD
        val brightThreshold = if (strictHighEnd) 192f else BRIGHT_LUMA_THRESHOLD
        val brightExtremeThreshold = if (strictHighEnd) 224f else BRIGHT_EXTREME_THRESHOLD
        
        // Enhanced scene detection signals
        val portraitSignal = labelNames.any { 
            it.contains("person") || it.contains("face") || it.contains("selfie") || 
            it.contains("portrait") || it.contains("people")
        }
        val groupSignal = labelNames.any {
            it.contains("crowd") || it.contains("group") || it.contains("people")
        } && (labelConfidences["crowd"] ?: 0f) > 0.4f
        
        val landscapeSignal = labelNames.any { 
            it.contains("mountain") || it.contains("nature") || it.contains("scenery") ||
            it.contains("forest") || it.contains("beach") || it.contains("valley") || it.contains("landscape")
        }
        val skySignal = labelNames.any { 
            it.contains("sky") || it.contains("cloud") || it.contains("sunset") || 
            it.contains("sunrise") || it.contains("horizon") || it.contains("dusk") || it.contains("dawn")
        }
        val indoorSignal = labelNames.any { 
            it.contains("room") || it.contains("indoor") || it.contains("furniture") || 
            it.contains("lamp") || it.contains("interior") || it.contains("office")
        }
        val outdoorSignal = labelNames.any { 
            it.contains("outdoor") || it.contains("tree") || it.contains("road") ||
            it.contains("street") || it.contains("building") || it.contains("city")
        }
        val waterSignal = labelNames.any {
            it.contains("water") || it.contains("ocean") || it.contains("river") ||
            it.contains("lake") || it.contains("pool") || it.contains("waterfall")
        }
        val foodSignal = labelNames.any {
            it.contains("food") || it.contains("dish") || it.contains("meal") ||
            it.contains("restaurant") || it.contains("cuisine") || it.contains("plate")
        }
        val documentSignal = labelNames.any {
            it.contains("text") || it.contains("document") || it.contains("paper") ||
            it.contains("book") || it.contains("sign")
        }
        val actionSignal = labelNames.any {
            it.contains("sport") || it.contains("action") || it.contains("running") ||
            it.contains("jumping") || it.contains("vehicle") || it.contains("cycling")
        }
        val macroSignal = labelNames.any {
            it.contains("flower") || it.contains("insect") || it.contains("plant") ||
            it.contains("detail") || it.contains("texture") || it.contains("petal")
        }
        val architectureSignal = labelNames.any {
            it.contains("building") || it.contains("architecture") || it.contains("tower") ||
            it.contains("bridge") || it.contains("monument") || it.contains("castle")
        }
        val concertSignal = labelNames.any {
            it.contains("concert") || it.contains("stage") || it.contains("performance") ||
            it.contains("light") || it.contains("music")
        }
        val fireworksSignal = labelNames.any {
            it.contains("firework") || it.contains("sparkle") || it.contains("explosion")
        }
        val astroSignal = labelNames.any {
            it.contains("star") || it.contains("night sky") || it.contains("galaxy") ||
            it.contains("astronomy")
        }
        
        val isLowLight = avgLuma < lowLightThreshold
        val isNightExtreme = avgLuma < nightExtremeThreshold
        val isHighContrast = dynamicRange > highDrThreshold
        val isExtremeContrast = dynamicRange > extremeDrThreshold
        val isBacklit = dynamicRange > backlightThreshold && avgLuma > if (strictHighEnd) 74f else 78f
        val isGoldenHour = colorTemperature >= GOLDEN_HOUR_TEMP_MIN && colorTemperature <= GOLDEN_HOUR_TEMP_MAX
        val isBlueHour = avgLuma < 80f && colorTemperature > BLUE_HOUR_TEMP_MAX
        
        // Hierarchical scene classification with confidence scoring
        val (sceneType, sceneConfidence) = when {
            // Extreme conditions
            isNightExtreme && astroSignal -> SceneType.ASTRO to 0.9f
            fireworksSignal -> SceneType.FIREWORKS to 0.95f
            isNightExtreme -> SceneType.NIGHT_EXTREME to 0.85f
            avgLuma > brightExtremeThreshold && (waterSignal || skySignal) -> SceneType.BRIGHT_EXTREME to 0.8f
            
            // Special lighting conditions
            isBlueHour && skySignal -> SceneType.BLUE_HOUR to 0.85f
            isGoldenHour && (landscapeSignal || portraitSignal) -> SceneType.GOLDEN_HOUR to 0.85f
            concertSignal && isLowLight -> SceneType.CONCERT to 0.9f
            
            // Night/low light
            avgLuma < nightThreshold -> SceneType.NIGHT to 0.75f
            
            // Backlight/high DR
            isBacklit && isExtremeContrast -> SceneType.BACKLIGHT to 0.9f
            isBacklit -> SceneType.BACKLIGHT to 0.75f
            
            // Time-based scenes
            skySignal && (labelNames.any { it.contains("sunset") || it.contains("sunrise") }) -> {
                SceneType.SUNSET to (labelConfidences["sunset"] ?: labelConfidences["sunrise"] ?: 0.7f)
            }
            
            // Portrait variations
            portraitSignal && groupSignal -> SceneType.PORTRAIT_GROUP to 0.8f
            portraitSignal && avgLuma < LOW_LIGHT_LUMA_THRESHOLD -> SceneType.PORTRAIT_LOW_LIGHT to 0.8f
            portraitSignal -> SceneType.PORTRAIT to 0.75f
            
            // Specialized scenes
            foodSignal -> SceneType.FOOD to 0.85f
            documentSignal -> SceneType.DOCUMENT to 0.9f
            actionSignal -> SceneType.ACTION to 0.75f
            macroSignal -> SceneType.MACRO to 0.8f
            
            // Landscape
            landscapeSignal && waterSignal -> SceneType.WATER_LANDSCAPE to 0.85f
            landscapeSignal -> SceneType.LANDSCAPE to 0.75f
            architectureSignal -> SceneType.ARCHITECTURE to 0.8f
            
            // Indoor/outdoor
            indoorSignal && !outdoorSignal -> SceneType.INDOOR to 0.7f
            outdoorSignal && skySignal -> SceneType.OUTDOOR to 0.65f
            outdoorSignal -> SceneType.STREET to 0.6f
            
            // Brightness-based
            avgLuma > brightThreshold -> SceneType.BRIGHT to 0.7f
            
            else -> SceneType.GENERAL to 0.5f
        }
        
        // Temporal smoothing for scene stability
        val (finalScene, temporalStability) = applyTemporalSmoothing(sceneType, sceneConfidence)
        
        // Build secondary scene hypotheses
        val secondaryScenes = buildSecondaryScenes(
            labelConfidences, avgLuma, dynamicRange, colorTemperature,
            portraitSignal, landscapeSignal, skySignal
        )
        
        val sceneClassification = SceneClassification(
            primaryScene = finalScene,
            confidence = sceneConfidence,
            secondaryScenes = secondaryScenes,
            temporalStability = temporalStability
        )
        
        val processingTime = System.currentTimeMillis() - startTime
        _status.value = EngineStatus.Success(
            metadata = "${finalScene.name} (${(sceneConfidence * 100).toInt()}%)",
            processingTimeMs = processingTime,
            qualityScore = sceneConfidence
        )
        
        return SceneAnalysis(
            sceneType = finalScene,
            sceneClassification = sceneClassification,
            avgLuma = avgLuma,
            dynamicRange = dynamicRange,
            colorTemperature = colorTemperature,
            isLowLight = isLowLight,
            isHighContrast = isHighContrast,
            isBacklit = isBacklit,
            recommendedHdrFrames = recommendHdrFrames(finalScene, dynamicRange, avgLuma, histogramFeatures),
            recommendedNoiseReduction = recommendNoiseReduction(finalScene, avgLuma, histogramFeatures),
            recommendedSaturation = recommendSaturation(finalScene, colorTemperature),
            recommendedExposureComp = recommendExposureComp(finalScene, avgLuma, histogramFeatures),
            recommendedSharpening = recommendSharpening(finalScene, avgLuma),
            recommendedContrast = recommendContrast(finalScene, histogramFeatures),
            histogramFeatures = histogramFeatures,
            temporalStability = temporalStability,
            usedHistogramFallback = labels.isEmpty()
        )
    }

    
    /**
     * Temporal smoothing to prevent scene flickering.
     */
    private fun applyTemporalSmoothing(newScene: SceneType, newConfidence: Float): Pair<SceneType, Float> {
        val minConfidenceForChange = if (strictHighEndEnabled()) {
            (MIN_CONFIDENCE_FOR_CHANGE - 0.07f).coerceAtLeast(0.5f)
        } else {
            MIN_CONFIDENCE_FOR_CHANGE
        }
        sceneHistory.addLast(newScene to newConfidence)
        if (sceneHistory.size > SCENE_HISTORY_SIZE) {
            sceneHistory.removeFirst()
        }
        
        // If new scene is different and confidence is low, stick with previous
        if (newScene != lastSceneType) {
            if (newConfidence < minConfidenceForChange) {
                // Not confident enough to change
                framesSinceChange = 0
                if (SceneHysteresisFlag.enabled) {
                    pendingScene = null
                    pendingSceneCount = 0
                }
                return lastSceneType to lastConfidence
            }
            // s7-f07/CP97: gated N-consecutive-frame debounce. Flag OFF (default) => a confident
            // change commits immediately, exactly as today. Flag ON => the new scene must persist
            // for N frames before committing, preventing flip-flop.
            if (SceneHysteresisFlag.enabled) {
                val debounce = SceneStateHysteresisPolicy.advance(
                    candidateScene = newScene,
                    pendingScene = pendingScene,
                    pendingCount = pendingSceneCount,
                    requiredConsecutive = SceneStateHysteresisPolicy.requiredConsecutive(strictHighEndEnabled())
                )
                pendingScene = debounce.pendingScene
                pendingSceneCount = debounce.pendingCount
                if (!debounce.commit) {
                    framesSinceChange = 0
                    return lastSceneType to lastConfidence
                }
            }
            // Confident change
            framesSinceChange = 0
            lastSceneType = newScene
            lastConfidence = newConfidence
        } else {
            framesSinceChange++
            if (SceneHysteresisFlag.enabled) {
                pendingScene = null
                pendingSceneCount = 0
            }
        }
        
        // Calculate temporal stability based on history consistency
        val stability = if (sceneHistory.size >= 3) {
            val recentScenes = sceneHistory.takeLast(5)
            val dominantScene = recentScenes.groupingBy { it.first }.eachCount().maxByOrNull { it.value }?.key
            if (dominantScene == newScene) {
                (recentScenes.count { it.first == newScene }.toFloat() / recentScenes.size.toFloat())
            } else {
                0.5f
            }
        } else {
            0.5f
        }
        
        return newScene to stability
    }
    
    /**
     * Build secondary scene hypotheses for ensemble classification.
     */
    private fun buildSecondaryScenes(
        labelConfidences: Map<String, Float>,
        avgLuma: Float,
        dynamicRange: Float,
        colorTemp: Int,
        portraitSignal: Boolean,
        landscapeSignal: Boolean,
        skySignal: Boolean
    ): List<Pair<SceneType, Float>> {
        val highDynamicRangeThreshold = if (strictHighEndEnabled()) 112f else HIGH_DR_THRESHOLD
        val secondaries = mutableListOf<Pair<SceneType, Float>>()
        
        // Add alternative interpretations
        if (portraitSignal && avgLuma < NIGHT_LUMA_THRESHOLD) {
            secondaries.add(SceneType.PORTRAIT_LOW_LIGHT to 0.6f)
        }
        if (landscapeSignal && dynamicRange > highDynamicRangeThreshold) {
            secondaries.add(SceneType.BACKLIGHT to 0.5f)
        }
        if (skySignal && colorTemp in 3000..4500) {
            secondaries.add(SceneType.GOLDEN_HOUR to 0.4f)
        }
        
        return secondaries.take(3)
    }
    
    /**
     * Extract histogram-based features for advanced scene analysis.
     */
    private fun extractHistogramFeatures(bitmap: Bitmap): HistogramFeatures {
        val histogram = IntArray(256)
        val sampleStepX = (bitmap.width / 64).coerceAtLeast(1)
        val sampleStepY = (bitmap.height / 64).coerceAtLeast(1)
        var totalSamples = 0
        var colorfulnessSum = 0f
        
        // HIGH-END: Batch row reads instead of per-pixel getPixel() JNI calls
        val rowBuffer = IntArray(bitmap.width)
        for (y in 0 until bitmap.height step sampleStepY) {
            bitmap.getPixels(rowBuffer, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in 0 until bitmap.width step sampleStepX) {
                val pixel = rowBuffer[x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
                histogram[luma]++
                totalSamples++
                
                // Colorfulness: RMS of RGB deviations from mean
                val mean = (r + g + b) / 3f
                colorfulnessSum += kotlin.math.sqrt(
                    ((r - mean) * (r - mean) + (g - mean) * (g - mean) + (b - mean) * (b - mean)) / 3f
                )
            }
        }
        
        if (totalSamples == 0) {
            return HistogramFeatures(0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
        
        // Calculate percentiles
        var cumulative = 0
        var shadow5Idx = 0
        var mid50Idx = 0
        var highlight95Idx = 0
        
        for (i in histogram.indices) {
            cumulative += histogram[i]
            val percentile = (cumulative.toFloat() / totalSamples.toFloat())
            if (percentile >= 0.05f && shadow5Idx == 0) shadow5Idx = i
            if (percentile >= 0.50f && mid50Idx == 0) mid50Idx = i
            if (percentile >= 0.95f && highlight95Idx == 0) highlight95Idx = i
        }
        
        val shadowPixels = histogram.slice(0..64).sum()
        val midtonePixels = histogram.slice(65..190).sum()
        val highlightPixels = histogram.slice(191..255).sum()
        val clippedShadows = histogram.slice(0..5).sum()
        val clippedHighlights = histogram.slice(250..255).sum()
        
        val contrastScore = ((highlight95Idx - shadow5Idx) / 255f).coerceIn(0f, 1f)
        val colorfulness = (colorfulnessSum / totalSamples / 128f).coerceIn(0f, 1f)
        
        return HistogramFeatures(
            shadowPixelsPercent = shadowPixels.toFloat() / totalSamples,
            midtonePixelsPercent = midtonePixels.toFloat() / totalSamples,
            highlightPixelsPercent = highlightPixels.toFloat() / totalSamples,
            clippedShadowsPercent = clippedShadows.toFloat() / totalSamples,
            clippedHighlightsPercent = clippedHighlights.toFloat() / totalSamples,
            contrastScore = contrastScore,
            colorfulness = colorfulness
        )
    }

    fun detect(image: Image, rotationDegrees: Int): SceneType {
        val labels = try {
            Tasks.await(
                labeler.process(InputImage.fromMediaImage(image, rotationDegrees)),
                LABEL_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
        } catch (e: TimeoutException) {
            logSceneLabelTimeout("Image")
            emptyList()
        } catch (e: Throwable) {
            Log.w(TAG, "ML Kit scene labeling failed for Image", e)
            emptyList()
        }

        val labelNames = labels.map { it.text.lowercase(Locale.US) }
        val avgLuma = averageLuma(image)
        val dynamicRange = luminanceSpread(image)
        val portraitSignal = labelNames.any { it.contains("person") || it.contains("face") || it.contains("selfie") }
        val landscapeSignal = labelNames.any { it.contains("mountain") || it.contains("sky") || it.contains("nature") || it.contains("outdoor") }
        val indoorSignal = labelNames.any { it.contains("room") || it.contains("indoor") || it.contains("furniture") || it.contains("lamp") }
        val outdoorSignal = labelNames.any { it.contains("outdoor") || it.contains("sky") || it.contains("tree") || it.contains("road") }
        val strictHighEnd = strictHighEndEnabled()
        val nightThreshold = if (strictHighEnd) 52f else NIGHT_LUMA_THRESHOLD
        val backlightThreshold = if (strictHighEnd) 98f else BACKLIGHT_DR_THRESHOLD

        return when {
            avgLuma < nightThreshold -> SceneType.NIGHT
            dynamicRange > backlightThreshold && avgLuma > if (strictHighEnd) 74f else 78f -> SceneType.BACKLIGHT
            portraitSignal -> SceneType.PORTRAIT
            landscapeSignal -> SceneType.LANDSCAPE
            indoorSignal && !outdoorSignal -> SceneType.INDOOR
            outdoorSignal -> SceneType.OUTDOOR
            else -> SceneType.GENERAL
        }
    }

    private fun logSceneLabelTimeout(source: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLabelTimeoutLogElapsedMs < LABEL_TIMEOUT_LOG_INTERVAL_MS) return
        lastLabelTimeoutLogElapsedMs = now
        Log.w(
            TAG,
            "ML Kit scene labeling timed out (${LABEL_TIMEOUT_MS}ms) for $source; using histogram fallback"
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROFESSIONAL RECOMMENDATION FUNCTIONS (UPGRADED)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun recommendHdrFrames(
        sceneType: SceneType, 
        dynamicRange: Float, 
        avgLuma: Float,
        histogramFeatures: HistogramFeatures
    ): Int {
        val strictHighEnd = strictHighEndEnabled()
        val highDynamicRangeThreshold = if (strictHighEnd) 112f else HIGH_DR_THRESHOLD
        val extremeDynamicRangeThreshold = if (strictHighEnd) 148f else EXTREME_DR_THRESHOLD
        val baseFrames = when {
            sceneType == SceneType.NIGHT_EXTREME -> 7  // Maximum for extreme low light
            sceneType == SceneType.NIGHT -> 5  // More frames for noise reduction
            sceneType == SceneType.ASTRO -> 9  // Astrophotography needs many frames
            sceneType == SceneType.BACKLIGHT -> 7  // Maximum for high DR
            sceneType == SceneType.SUNSET || sceneType == SceneType.GOLDEN_HOUR -> 5
            sceneType == SceneType.BRIGHT_EXTREME -> 5  // Handle extreme highlights
            dynamicRange > extremeDynamicRangeThreshold -> 7
            dynamicRange > highDynamicRangeThreshold -> 5
            dynamicRange > 80f -> 3
            avgLuma < LOW_LIGHT_LUMA_THRESHOLD -> 5
            histogramFeatures.clippedHighlightsPercent > 0.05f -> 5  // Highlight recovery
            else -> 3
        }
        if (!strictHighEnd) return baseFrames
        val strictBonus = when {
            sceneType == SceneType.ASTRO || sceneType == SceneType.NIGHT_EXTREME -> 0
            dynamicRange > highDynamicRangeThreshold || avgLuma < LOW_LIGHT_LUMA_THRESHOLD -> 1
            else -> 0
        }
        return (baseFrames + strictBonus).coerceIn(3, 9)
    }
    
    private fun recommendNoiseReduction(
        sceneType: SceneType, 
        avgLuma: Float,
        histogramFeatures: HistogramFeatures
    ): Float {
        val strictHighEnd = strictHighEndEnabled()
        val baseNR = when {
            sceneType == SceneType.NIGHT_EXTREME -> 0.9f
            sceneType == SceneType.ASTRO -> 0.85f
            sceneType == SceneType.NIGHT -> 0.8f
            sceneType == SceneType.PORTRAIT_LOW_LIGHT -> 0.6f
            sceneType == SceneType.CONCERT -> 0.7f
            avgLuma < NIGHT_EXTREME_THRESHOLD -> 0.85f
            avgLuma < NIGHT_LUMA_THRESHOLD -> 0.7f
            avgLuma < LOW_LIGHT_LUMA_THRESHOLD -> 0.4f
            sceneType == SceneType.DOCUMENT -> 0.1f  // Preserve text detail
            else -> 0.25f
        }
        
        // Adjust based on shadow clipping (indicates noise)
        val shadowAdjustment = histogramFeatures.clippedShadowsPercent *
            if (strictHighEnd) 0.24f else 0.2f
        val strictBoost = if (strictHighEnd && avgLuma < LOW_LIGHT_LUMA_THRESHOLD) 0.05f else 0f
        return (baseNR + shadowAdjustment + strictBoost).coerceIn(0f, 1f)
    }
    
    private fun recommendSaturation(sceneType: SceneType, colorTemperature: Int): Float {
        val strictHighEnd = strictHighEndEnabled()
        val baseSaturation = when (sceneType) {
            SceneType.PORTRAIT, SceneType.PORTRAIT_LOW_LIGHT -> 0.95f  // Subtle desaturation for skin
            SceneType.PORTRAIT_GROUP -> 0.93f
            SceneType.LANDSCAPE, SceneType.WATER_LANDSCAPE -> 1.1f  // Slight boost for nature
            SceneType.SUNSET, SceneType.GOLDEN_HOUR -> 1.15f  // Enhanced colors
            SceneType.BLUE_HOUR -> 1.12f
            SceneType.FOOD -> 1.08f  // Appetizing colors
            SceneType.DOCUMENT -> 0.8f  // Reduce color for readability
            SceneType.NIGHT, SceneType.NIGHT_EXTREME -> 0.9f  // Reduce noise appearance
            SceneType.MACRO -> 1.05f  // Enhance natural colors
            SceneType.ASTRO -> 1.2f  // Boost cosmic colors
            else -> 1.0f
        }
        
        // Adjust based on color temperature
        val tempAdjustment = when {
            colorTemperature < 3500 -> -0.05f  // Reduce saturation in warm light
            colorTemperature > 7000 -> 0.05f  // Boost in cool light
            else -> 0f
        }

        val strictAdjustment = if (strictHighEnd) {
            when (sceneType) {
                SceneType.LANDSCAPE,
                SceneType.WATER_LANDSCAPE,
                SceneType.SUNSET,
                SceneType.GOLDEN_HOUR,
                SceneType.ASTRO -> 0.03f
                else -> 0f
            }
        } else {
            0f
        }
        return (baseSaturation + tempAdjustment + strictAdjustment).coerceIn(0.7f, 1.32f)
    }
    
    private fun recommendExposureComp(
        sceneType: SceneType, 
        avgLuma: Float,
        histogramFeatures: HistogramFeatures
    ): Float {
        val strictHighEnd = strictHighEndEnabled()
        val baseComp = when {
            sceneType == SceneType.BACKLIGHT -> 1.0f  // Lift shadows
            sceneType == SceneType.NIGHT -> 0.5f  // Slight lift
            sceneType == SceneType.NIGHT_EXTREME -> 0.7f
            sceneType == SceneType.SUNSET || sceneType == SceneType.GOLDEN_HOUR -> -0.3f  // Preserve colors
            sceneType == SceneType.BLUE_HOUR -> -0.2f
            sceneType == SceneType.BRIGHT_EXTREME -> -0.7f  // Strong highlight protection
            avgLuma > BRIGHT_LUMA_THRESHOLD -> -0.5f  // Reduce highlights
            avgLuma < LOW_LIGHT_LUMA_THRESHOLD -> 0.3f
            else -> 0.0f
        }
        
        // Adjust based on clipping
        val highlightAdj = if (histogramFeatures.clippedHighlightsPercent > 0.05f) -0.3f else 0f
        val shadowAdj = if (histogramFeatures.clippedShadowsPercent > 0.05f) 0.2f else 0f

        val strictAdj = if (strictHighEnd) {
            when {
                sceneType == SceneType.BACKLIGHT -> 0.1f
                avgLuma < LOW_LIGHT_LUMA_THRESHOLD -> 0.05f
                else -> 0f
            }
        } else {
            0f
        }
        return (baseComp + highlightAdj + shadowAdj + strictAdj).coerceIn(-1.5f, 1.5f)
    }
    
    private fun recommendSharpening(sceneType: SceneType, avgLuma: Float): Float {
        val baseSharpening = when {
            sceneType == SceneType.DOCUMENT -> 1.2f  // High sharpening for text
            sceneType == SceneType.LANDSCAPE || sceneType == SceneType.ARCHITECTURE -> 1.1f
            sceneType == SceneType.PORTRAIT || sceneType == SceneType.PORTRAIT_LOW_LIGHT -> 0.6f  // Gentle for skin
            sceneType == SceneType.NIGHT || sceneType == SceneType.NIGHT_EXTREME -> 0.4f  // Low to avoid noise
            sceneType == SceneType.MACRO -> 1.15f  // High for detail
            avgLuma < NIGHT_LUMA_THRESHOLD -> 0.5f  // Reduce in low light
            else -> 1.0f
        }
        if (!strictHighEndEnabled()) return baseSharpening
        val strictAdjust = when (sceneType) {
            SceneType.DOCUMENT,
            SceneType.LANDSCAPE,
            SceneType.ARCHITECTURE,
            SceneType.MACRO -> 0.08f
            SceneType.PORTRAIT,
            SceneType.PORTRAIT_LOW_LIGHT,
            SceneType.NIGHT,
            SceneType.NIGHT_EXTREME -> 0f
            else -> 0.04f
        }
        return (baseSharpening + strictAdjust).coerceIn(0.35f, 1.3f)
    }
    
    private fun recommendContrast(sceneType: SceneType, histogramFeatures: HistogramFeatures): Float {
        val strictHighEnd = strictHighEndEnabled()
        val baseContrast = when (sceneType) {
            SceneType.PORTRAIT, SceneType.PORTRAIT_LOW_LIGHT -> 0.95f  // Softer
            SceneType.LANDSCAPE -> 1.1f  // Punchy
            SceneType.DOCUMENT -> 1.2f  // High contrast for readability
            SceneType.NIGHT, SceneType.NIGHT_EXTREME -> 1.05f  // Slight boost
            SceneType.SUNSET, SceneType.GOLDEN_HOUR -> 1.08f
            else -> 1.0f
        }
        
        // Adjust based on existing contrast
        val contrastAdj = if (histogramFeatures.contrastScore > 0.7f) -0.1f else 0.1f
        val strictAdj = if (strictHighEnd) {
            if (sceneType == SceneType.PORTRAIT || sceneType == SceneType.PORTRAIT_LOW_LIGHT) 0f else 0.03f
        } else {
            0f
        }
        return (baseContrast + contrastAdj + strictAdj).coerceIn(0.7f, 1.42f)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // IMAGE ANALYSIS FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun averageLuma(bitmap: Bitmap): Float {
        val sampleStepX = (bitmap.width / 32).coerceAtLeast(1)
        val sampleStepY = (bitmap.height / 32).coerceAtLeast(1)
        var sum = 0f
        var count = 0
        // HIGH-END: Batch row reads instead of per-pixel getPixel() JNI calls
        val rowBuffer = IntArray(bitmap.width)
        for (y in 0 until bitmap.height step sampleStepY) {
            bitmap.getPixels(rowBuffer, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in 0 until bitmap.width step sampleStepX) {
                val pixel = rowBuffer[x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                sum += 0.299f * r + 0.587f * g + 0.114f * b
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }

    private fun luminanceSpread(bitmap: Bitmap): Float {
        val sampleStepX = (bitmap.width / 40).coerceAtLeast(1)
        val sampleStepY = (bitmap.height / 40).coerceAtLeast(1)
        var minValue = 255f
        var maxValue = 0f
        // HIGH-END: Batch row reads instead of per-pixel getPixel() JNI calls
        val rowBuffer = IntArray(bitmap.width)
        for (y in 0 until bitmap.height step sampleStepY) {
            bitmap.getPixels(rowBuffer, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in 0 until bitmap.width step sampleStepX) {
                val pixel = rowBuffer[x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                minValue = minOf(minValue, luma)
                maxValue = maxOf(maxValue, luma)
            }
        }
        return maxValue - minValue
    }
    
    /**
     * Estimate color temperature from image (warm/cool).
     * Returns approximate Kelvin value.
     */
    private fun estimateColorTemperature(bitmap: Bitmap): Int {
        val sampleStepX = (bitmap.width / 24).coerceAtLeast(1)
        val sampleStepY = (bitmap.height / 24).coerceAtLeast(1)
        var totalR = 0f
        var totalB = 0f
        var count = 0
        
        // HIGH-END: Batch row reads instead of per-pixel getPixel() JNI calls
        val rowBuffer = IntArray(bitmap.width)
        for (y in 0 until bitmap.height step sampleStepY) {
            bitmap.getPixels(rowBuffer, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in 0 until bitmap.width step sampleStepX) {
                val pixel = rowBuffer[x]
                val r = (pixel shr 16) and 0xFF
                val b = pixel and 0xFF
                totalR += r
                totalB += b
                count++
            }
        }
        
        if (count == 0) return 5500
        val avgR = totalR / count
        val avgB = totalB / count
        
        // Simple R/B ratio to Kelvin approximation
        val ratio = avgR / (avgB + 0.001f)
        return when {
            ratio > 1.5f -> 3200  // Warm/tungsten
            ratio > 1.2f -> 4500  // Warm daylight
            ratio < 0.8f -> 8000  // Cool/shade
            ratio < 0.9f -> 6500  // Overcast
            else -> 5500  // Neutral daylight
        }
    }

    private fun averageLuma(image: Image): Float {
        val plane = image.planes.firstOrNull() ?: return 0f
        val width = image.width
        val height = image.height
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val sampleStepX = (width / 32).coerceAtLeast(1)
        val sampleStepY = (height / 32).coerceAtLeast(1)
        var sum = 0f
        var count = 0
        for (y in 0 until height step sampleStepY) {
            val rowOffset = y * rowStride
            for (x in 0 until width step sampleStepX) {
                val index = rowOffset + x * pixelStride
                if (index < buffer.limit()) {
                    sum += (buffer.get(index).toInt() and 0xFF).toFloat()
                    count++
                }
            }
        }
        return if (count == 0) 0f else sum / count
    }

    private fun luminanceSpread(image: Image): Float {
        val plane = image.planes.firstOrNull() ?: return 0f
        val width = image.width
        val height = image.height
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val sampleStepX = (width / 40).coerceAtLeast(1)
        val sampleStepY = (height / 40).coerceAtLeast(1)
        var minValue = 255f
        var maxValue = 0f
        for (y in 0 until height step sampleStepY) {
            val rowOffset = y * rowStride
            for (x in 0 until width step sampleStepX) {
                val index = rowOffset + x * pixelStride
                if (index < buffer.limit()) {
                    val luma = (buffer.get(index).toInt() and 0xFF).toFloat()
                    minValue = minOf(minValue, luma)
                    maxValue = maxOf(maxValue, luma)
                }
            }
        }
        return maxValue - minValue
    }

    override fun close() {
        labeler.close()
    }
}
