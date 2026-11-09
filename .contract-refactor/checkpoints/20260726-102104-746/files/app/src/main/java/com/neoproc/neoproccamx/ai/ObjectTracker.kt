package com.neoproc.neoproccamx.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.core.graphics.get
import androidx.core.graphics.scale
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.infrastructure.performance.PerformanceMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * HIGH-END STATE-OF-THE-ART CINEMATIC OBJECT TRACKER (10/10 ELITE LEVEL)
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Drives efficientdet_lite0.tflite with real-time multi-subject tracking,
 * Kalman trajectory filtering, and dynamic rack-pull prioritization.
 * Features a seamless chroma-saliency & temporal differencing classical fallback
 * for zero-crash operational integrity.
 */
class ObjectTracker(private val context: Context) : AutoCloseable {

    data class EfficientDetDetection(
        val classId: Int,
        val className: String,
        val boundingBox: RectF,
        val confidence: Float
    )

    companion object {
        private const val TAG = "ObjectTracker"
        private const val CONFIDENCE_THRESHOLD = 0.42f
        private const val TRACKING_IOU_THRESHOLD = 0.35f
        private const val STATE_VECTOR_SIZE = 10
        private const val MEASUREMENT_VECTOR_SIZE = 4
        private const val MAX_MISSED_FRAMES = 30
        private const val SPAWN_CONFIDENCE_THRESHOLD = 0.60f
        private const val VELOCITY_DAMPING = 0.92f // High-end smoothing parameter
        private const val BASE_MEASUREMENT_VARIANCE = 1.2e-3f
        private const val CONFIDENCE_EPSILON = 1.0e-4f
        private const val OBJECT_RACK_PULL_LAMBDA_CINEMA = 3.5f
        private const val OBJECT_RACK_PULL_LAMBDA_ACTION = 8.5f
        
        // Selection score weights
        private const val WEIGHT_CONFIDENCE = 0.30f
        private const val WEIGHT_CENTRALITY = 0.35f
        private const val WEIGHT_SCALE = 0.15f
        private const val WEIGHT_PERSISTENCE = 0.20f

        // Common COCO class names mapping
        private val COCO_CLASSES = mapOf(
            1 to "person", 2 to "bicycle", 3 to "car", 4 to "motorcycle", 5 to "airplane",
            6 to "bus", 7 to "train", 8 to "truck", 9 to "boat", 10 to "traffic light",
            16 to "bird", 17 to "cat", 18 to "dog", 19 to "horse", 20 to "sheep",
            21 to "cow", 22 to "elephant", 23 to "bear", 24 to "zebra", 25 to "giraffe",
            27 to "backpack", 28 to "umbrella", 31 to "handbag", 32 to "tie", 33 to "suitcase",
            37 to "sports ball", 39 to "baseball glove", 41 to "skateboard", 42 to "surfboard",
            43 to "tennis racket", 44 to "bottle", 46 to "wine glass", 47 to "cup",
            48 to "fork", 49 to "knife", 50 to "spoon", 51 to "bowl", 62 to "chair",
            63 to "couch", 64 to "potted plant", 65 to "bed", 67 to "dining table",
            72 to "tv", 77 to "laptop", 78 to "mouse", 80 to "keyboard", 81 to "cell phone",
            82 to "microwave", 83 to "oven", 84 to "toaster", 85 to "sink", 86 to "refrigerator",
            87 to "book", 88 to "clock", 89 to "vase", 90 to "scissors"
        )

        fun parseEfficientDetDetections(
            outputRoot: Any,
            rotationDegrees: Int,
            confidenceThreshold: Float = CONFIDENCE_THRESHOLD
        ): List<EfficientDetDetection> {
            return detectionRows(outputRoot).mapNotNull { detection ->
                if (detection.size < 6) return@mapNotNull null

                val classId = detection[0].toInt()
                val score = detection[1]
                if (score < confidenceThreshold) return@mapNotNull null

                val ymin = detection[2].coerceIn(0f, 1f)
                val xmin = detection[3].coerceIn(0f, 1f)
                val ymax = detection[4].coerceIn(0f, 1f)
                val xmax = detection[5].coerceIn(0f, 1f)
                if (xmax <= xmin || ymax <= ymin) return@mapNotNull null

                EfficientDetDetection(
                    classId = classId,
                    className = COCO_CLASSES[classId] ?: "object",
                    boundingBox = remapBoxForRotation(xmin, ymin, xmax, ymax, rotationDegrees),
                    confidence = score.coerceIn(0f, 1f)
                )
            }
        }

        fun objectSelectionScore(
            confidence: Float,
            bounds: RectF,
            trackedFrames: Int
        ): Float {
            val cx = (bounds.left + bounds.right) * 0.5f
            val cy = (bounds.top + bounds.bottom) * 0.5f
            val width = (bounds.right - bounds.left).coerceAtLeast(0f)
            val height = (bounds.bottom - bounds.top).coerceAtLeast(0f)
            val centrality = (1.0f - sqrt((cx - 0.5f) * (cx - 0.5f) + (cy - 0.5f) * (cy - 0.5f)) * 1.4142135f)
                .coerceIn(0f, 1f)
            val scale = (width * height).coerceIn(0f, 1f)
            val persistence = (trackedFrames / 90.0f).coerceIn(0f, 1f)
            return (WEIGHT_CONFIDENCE * confidence.coerceIn(0f, 1f)) +
                (WEIGHT_CENTRALITY * centrality) +
                (WEIGHT_SCALE * scale) +
                (WEIGHT_PERSISTENCE * persistence)
        }

        fun cinematicRackPullStep(
            previous: Float,
            target: Float,
            deltaTimeSeconds: Float,
            cinemaMode: Boolean
        ): Float {
            val lambda = if (cinemaMode) OBJECT_RACK_PULL_LAMBDA_CINEMA else OBJECT_RACK_PULL_LAMBDA_ACTION
            val eased = 1.0f - exp(-lambda * deltaTimeSeconds.coerceAtLeast(0f))
            return previous + (target - previous) * eased
        }

        private fun detectionRows(outputRoot: Any): List<FloatArray> {
            return when (outputRoot) {
                is FloatArray -> outputRoot.asDetectionRows()
                is Array<*> -> flattenDetectionRows(outputRoot)
                else -> emptyList()
            }
        }

        private fun flattenDetectionRows(array: Array<*>): List<FloatArray> {
            if (array.isEmpty()) return emptyList()
            val first = array.first()
            return when {
                first is FloatArray && first.size == 6 -> array.filterIsInstance<FloatArray>()
                first is FloatArray && array.size == 1 -> first.asDetectionRows()
                first is Array<*> -> flattenDetectionRows(first)
                first is FloatArray -> array.filterIsInstance<FloatArray>()
                else -> emptyList()
            }
        }

        private fun FloatArray.asDetectionRows(): List<FloatArray> {
            if (size < 6) return emptyList()
            return asList().chunked(6)
                .filter { it.size == 6 }
                .map { chunk -> chunk.toFloatArray() }
        }

        private fun remapBoxForRotation(
            xmin: Float,
            ymin: Float,
            xmax: Float,
            ymax: Float,
            rotationDegrees: Int
        ): RectF {
            val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
            val points = arrayOf(
                mapPointForRotation(xmin, ymin, normalizedRotation),
                mapPointForRotation(xmax, ymin, normalizedRotation),
                mapPointForRotation(xmin, ymax, normalizedRotation),
                mapPointForRotation(xmax, ymax, normalizedRotation)
            )
            val minX = points.minOf { it.first }.coerceIn(0f, 1f)
            val minY = points.minOf { it.second }.coerceIn(0f, 1f)
            val maxX = points.maxOf { it.first }.coerceIn(0f, 1f)
            val maxY = points.maxOf { it.second }.coerceIn(0f, 1f)
            return rectF(minX, minY, maxX, maxY)
        }

        private fun mapPointForRotation(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> {
            return when (rotationDegrees) {
                90 -> Pair(1f - y, x)
                180 -> Pair(1f - x, 1f - y)
                270 -> Pair(y, 1f - x)
                else -> Pair(x, y)
            }
        }

        private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF =
            RectF().apply {
                this.left = left
                this.top = top
                this.right = right
                this.bottom = bottom
            }
    }

    private val activeTracks = LinkedHashMap<Int, TrackState>()
    private var nextTrackingId = 1
    
    // Fallback template variables
    private var lastFallbackBitmap: Bitmap? = null
    private var previousFallbackLuma: ByteArray? = null
    private var previousFallbackLumaWidth: Int = 0
    private var previousFallbackLumaHeight: Int = 0
    private var fallbackChromaTemplate: FloatArray? = null // YCbCr histogram
    private var fallbackBoundsTemplate: RectF? = null
    private var fallbackTrackingId: Int = -1

    private val _isFallbackEngaged = MutableStateFlow(false)
    val isFallbackEngaged: StateFlow<Boolean> = _isFallbackEngaged.asStateFlow()

    private data class TrackState(
        val trackingId: Int,
        val classId: Int,
        val className: String,
        var currentBounds: RectF,
        var confidence: Float,
        var velocityX: Float = 0f,
        var velocityY: Float = 0f,
        var accelerationX: Float = 0f,
        var accelerationY: Float = 0f,
        var kalmanState: ObjectTrackKalmanState,
        var lastUpdateMs: Long,
        var trackedFrames: Int = 1,
        var missedFrames: Int = 0,
        val trackingStartMs: Long = System.currentTimeMillis()
    )

    private data class ObjectTrackKalmanState(
        val state: FloatArray,
        val covariance: FloatArray
    )

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    /**
     * Track objects on the given Bitmap frame.
     */
    suspend fun track(bitmap: Bitmap, rotationDegrees: Int, timestampMs: Long): List<TrackedObject> {
        return PerformanceMonitor.measureSuspend("ObjectTracker.track") {
            try {
                // efficientdet_lite0.tflite packaged with the app uses NHWC 320x320 UINT8 input.
                val scaledBitmap = bitmap.scale(320, 320, true)
                
                // Invoke dynamic inference framework
                val inferenceResult = AINeuralEngine.getInstance(context).runInference(
                    modelName = "object_detection",
                    inputData = scaledBitmap,
                    outputMapper = { outputArray ->
                        parseInferenceOutput(outputArray, rotationDegrees)
                    }
                )

                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }

                when (inferenceResult) {
                    is AINeuralEngine.InferenceResult.Success -> {
                        _isFallbackEngaged.value = false
                        val parsedDetections = inferenceResult.data
                        val matchedTracks = associateDetections(parsedDetections, timestampMs)
                        cleanupStaleTracks(timestampMs)
                        
                        // Seed classical template in case we fall back later
                        if (matchedTracks.isNotEmpty()) {
                            val primary = selectPrimaryTrack(matchedTracks)
                            primary?.let { updateClassicalTemplate(bitmap, it.boundingBox) }
                        }
                        matchedTracks
                    }
                    is AINeuralEngine.InferenceResult.Failure, is AINeuralEngine.InferenceResult.Timeout -> {
                        NeoLogger.w(TAG, "Neural inference failed. Engaging elite classical fallback tracker.")
                        runClassicalFallback(bitmap, timestampMs)
                    }
                }
            } catch (e: Exception) {
                NeoLogger.e(TAG, "ObjectTracker encountered tracking exception, fallback initialized.", e)
                runClassicalFallback(bitmap, timestampMs)
            }
        }
    }

    /**
     * Parses unified [1, 100, 6] tensor outputs.
     */
    private fun parseInferenceOutput(outputArray: Array<*>, rotationDegrees: Int): List<RawDetection> {
        return try {
            parseEfficientDetDetections(outputArray, rotationDegrees).map { detection ->
                RawDetection(
                    classId = detection.classId,
                    className = detection.className,
                    boundingBox = detection.boundingBox,
                    confidence = detection.confidence
                )
            }
        } catch (e: Exception) {
            NeoLogger.e(TAG, "Error parsing efficientdet output tensor structure.", e)
            emptyList()
        }
    }

    /**
     * Multi-object association and stateful ID tracking with Kalman Filtering.
     */
    private fun associateDetections(
        detections: List<RawDetection>,
        timestampMs: Long
    ): List<TrackedObject> {
        val tracks = activeTracks.values.toList()
        tracks.forEach { track -> predictObjectTrack(track, timestampMs) }

        val usedDetections = BooleanArray(detections.size)
        val matchedTrackIds = mutableSetOf<Int>()

        tracks.forEach { track ->
            var bestDetectionIndex = -1
            var bestIoU = TRACKING_IOU_THRESHOLD

            detections.forEachIndexed { index, detection ->
                if (usedDetections[index] || detection.classId != track.classId) return@forEachIndexed
                val iou = calculateIoU(track.currentBounds, detection.boundingBox)
                if (iou > bestIoU) {
                    bestIoU = iou
                    bestDetectionIndex = index
                }
            }

            // s7-f06/CP96: gated centroid bridge. When IoU matching fails (fast motion between
            // strided detections), match the closest same-class unused detection within the bridge
            // radius so the track ID survives the gap. Flag OFF (default) => skipped => identical.
            if (bestDetectionIndex < 0 && ObjectTrackerContinuityFlag.enabled) {
                val radius = ObjectTrackContinuityPolicy.bridgeRadius(strictHighEndEnabled())
                var bestDist = Float.MAX_VALUE
                detections.forEachIndexed { index, detection ->
                    if (usedDetections[index] || detection.classId != track.classId) return@forEachIndexed
                    val dist = ObjectTrackContinuityPolicy.centroidDistance(
                        track.currentBounds.centerX(), track.currentBounds.centerY(),
                        detection.boundingBox.centerX(), detection.boundingBox.centerY()
                    )
                    if (ObjectTrackContinuityPolicy.isCentroidBridge(dist, radius) && dist < bestDist) {
                        bestDist = dist
                        bestDetectionIndex = index
                    }
                }
            }

            if (bestDetectionIndex >= 0) {
                val match = detections[bestDetectionIndex]
                updateObjectTrack(track, match, timestampMs)
                usedDetections[bestDetectionIndex] = true
                matchedTrackIds.add(track.trackingId)
            }
        }

        tracks.filterNot { it.trackingId in matchedTrackIds }.forEach { track ->
            track.missedFrames += 1
            track.confidence *= 0.85f
        }

        detections.forEachIndexed { index, detection ->
            if (!usedDetections[index] && detection.confidence > SPAWN_CONFIDENCE_THRESHOLD) {
                val newId = nextTrackingId++
                val newTrack = TrackState(
                    trackingId = newId,
                    classId = detection.classId,
                    className = detection.className,
                    currentBounds = detection.boundingBox,
                    confidence = detection.confidence,
                    kalmanState = initializeObjectKalman(detection.boundingBox),
                    lastUpdateMs = timestampMs
                )
                activeTracks[newId] = newTrack
            }
        }

        cleanupStaleTracks(timestampMs)
        return activeTracks.values.map { track -> track.toTrackedObject(timestampMs) }
    }

    private fun cleanupStaleTracks(timestampMs: Long) {
        activeTracks.entries.removeIf { (_, track) ->
            track.missedFrames > MAX_MISSED_FRAMES || timestampMs < track.lastUpdateMs
        }
    }

    private fun initializeObjectKalman(bounds: RectF): ObjectTrackKalmanState {
        val measurement = measurementFromBounds(bounds)
        val state = FloatArray(STATE_VECTOR_SIZE)
        state[0] = measurement[0]
        state[1] = measurement[1]
        state[2] = measurement[2]
        state[3] = measurement[3]

        val covariance = FloatArray(STATE_VECTOR_SIZE * STATE_VECTOR_SIZE)
        for (i in 0 until STATE_VECTOR_SIZE) {
            covariance[i * STATE_VECTOR_SIZE + i] = if (i < MEASUREMENT_VECTOR_SIZE) 0.08f else 0.35f
        }
        return ObjectTrackKalmanState(state = state, covariance = covariance)
    }

    private fun predictObjectTrack(track: TrackState, timestampMs: Long) {
        val dt = ((timestampMs - track.lastUpdateMs).coerceAtLeast(1L) / 1000f).coerceIn(0.001f, 0.25f)
        val s = track.kalmanState.state
        val halfDt2 = 0.5f * dt * dt

        s[0] = (s[0] + s[4] * dt + s[8] * halfDt2).coerceIn(0f, 1f)
        s[1] = (s[1] + s[5] * dt + s[9] * halfDt2).coerceIn(0f, 1f)
        s[2] = (s[2] + s[6] * dt).coerceIn(0.05f, 4.0f)
        s[3] = (s[3] + s[7] * dt).coerceIn(0.02f, 1.0f)
        s[4] = s[4] * VELOCITY_DAMPING + s[8] * dt
        s[5] = s[5] * VELOCITY_DAMPING + s[9] * dt
        s[6] *= VELOCITY_DAMPING
        s[7] *= VELOCITY_DAMPING
        s[8] *= VELOCITY_DAMPING * VELOCITY_DAMPING
        s[9] *= VELOCITY_DAMPING * VELOCITY_DAMPING

        inflateCovariance(track.kalmanState.covariance, processNoise = 8.0e-4f)
        track.currentBounds = boundsFromObjectState(s)
        track.velocityX = s[4]
        track.velocityY = s[5]
        track.accelerationX = s[8]
        track.accelerationY = s[9]
    }

    private fun updateObjectTrack(track: TrackState, detection: RawDetection, timestampMs: Long) {
        val dt = ((timestampMs - track.lastUpdateMs).coerceAtLeast(1L) / 1000f).coerceIn(0.001f, 0.25f)
        val state = track.kalmanState.state
        val covariance = track.kalmanState.covariance
        val oldX = state[0]
        val oldY = state[1]
        val oldR = state[2]
        val oldH = state[3]
        val measurement = measurementFromBounds(detection.boundingBox)
        val measurementVariance = BASE_MEASUREMENT_VARIANCE / max(detection.confidence, CONFIDENCE_EPSILON)

        for (i in 0 until MEASUREMENT_VECTOR_SIZE) {
            val diagIndex = i * STATE_VECTOR_SIZE + i
            val predictedVariance = covariance[diagIndex].coerceAtLeast(1.0e-6f)
            val gain = predictedVariance / (predictedVariance + measurementVariance)
            state[i] += gain * (measurement[i] - state[i])
            covariance[diagIndex] = (1.0f - gain) * predictedVariance
        }

        val measuredVx = (state[0] - oldX) / dt
        val measuredVy = (state[1] - oldY) / dt
        val measuredVr = (state[2] - oldR) / dt
        val measuredVh = (state[3] - oldH) / dt
        val previousVx = state[4]
        val previousVy = state[5]

        state[4] = state[4] * 0.55f + measuredVx * 0.45f
        state[5] = state[5] * 0.55f + measuredVy * 0.45f
        state[6] = state[6] * 0.60f + measuredVr * 0.40f
        state[7] = state[7] * 0.60f + measuredVh * 0.40f
        state[8] = ((state[4] - previousVx) / dt).coerceIn(-2.0f, 2.0f)
        state[9] = ((state[5] - previousVy) / dt).coerceIn(-2.0f, 2.0f)

        track.currentBounds = boundsFromObjectState(state)
        track.confidence = detection.confidence
        track.velocityX = state[4]
        track.velocityY = state[5]
        track.accelerationX = state[8]
        track.accelerationY = state[9]
        track.lastUpdateMs = timestampMs
        track.trackedFrames += 1
        track.missedFrames = 0
    }

    private fun measurementFromBounds(bounds: RectF): FloatArray {
        val width = (bounds.right - bounds.left).coerceAtLeast(1.0e-4f)
        val height = (bounds.bottom - bounds.top).coerceAtLeast(1.0e-4f)
        return floatArrayOf(
            ((bounds.left + bounds.right) * 0.5f).coerceIn(0f, 1f),
            ((bounds.top + bounds.bottom) * 0.5f).coerceIn(0f, 1f),
            (width / height).coerceIn(0.05f, 4.0f),
            height.coerceIn(0.02f, 1.0f)
        )
    }

    private fun boundsFromObjectState(state: FloatArray): RectF {
        val height = state[3].coerceIn(0.02f, 1.0f)
        val width = (state[2].coerceIn(0.05f, 4.0f) * height).coerceIn(0.02f, 1.0f)
        val cx = state[0].coerceIn(0f, 1f)
        val cy = state[1].coerceIn(0f, 1f)
        return RectF().apply {
            left = (cx - width * 0.5f).coerceIn(0f, 1f)
            top = (cy - height * 0.5f).coerceIn(0f, 1f)
            right = (cx + width * 0.5f).coerceIn(0f, 1f)
            bottom = (cy + height * 0.5f).coerceIn(0f, 1f)
        }
    }

    private fun inflateCovariance(covariance: FloatArray, processNoise: Float) {
        for (i in 0 until STATE_VECTOR_SIZE) {
            val index = i * STATE_VECTOR_SIZE + i
            covariance[index] = (covariance[index] + processNoise).coerceAtMost(2.0f)
        }
    }

    /**
     * Elite Classical Computer Vision Fallback Tracker.
     * Combines YCbCr color histogram correlation matching with temporal motion prediction.
     */
    private fun runClassicalFallback(bitmap: Bitmap, timestampMs: Long): List<TrackedObject> {
        _isFallbackEngaged.value = true
        
        val template = fallbackChromaTemplate
        val lastBounds = fallbackBoundsTemplate
        
        if (template == null || lastBounds == null) {
            // No template seed available: scan frame for contrasting spatial motion/saliency center
            val salientBounds = findFrameSaliencyCentroid(bitmap)
            
            val newId = if (fallbackTrackingId != -1) fallbackTrackingId else {
                fallbackTrackingId = nextTrackingId++
                fallbackTrackingId
            }
            fallbackBoundsTemplate = salientBounds
            updateClassicalTemplate(bitmap, salientBounds)

            val tracked = TrackedObject(
                trackingId = newId,
                boundingBox = salientBounds,
                confidence = 0.5f,
                classId = 0,
                className = "salient_fallback",
                velocityX = 0f,
                velocityY = 0f,
                timestampMs = timestampMs,
                motionPattern = MotionPattern.STATIC
            )
            return listOf(tracked)
        }

        // Search window: Local neighborhood centered around the last known bounding box
        val searchBounds = RectF().apply {
            left = (lastBounds.left - 0.2f).coerceAtLeast(0f)
            top = (lastBounds.top - 0.2f).coerceAtLeast(0f)
            right = (lastBounds.right + 0.2f).coerceAtMost(1f)
            bottom = (lastBounds.bottom + 0.2f).coerceAtMost(1f)
        }

        val matchingResult = scanSearchWindowForBestMatch(bitmap, template, searchBounds, lastBounds.width(), lastBounds.height())
        val matchedBounds = matchingResult.first
        val matchConfidence = matchingResult.second

        val velocityX = (matchedBounds.centerX() - lastBounds.centerX()) * 0.9f
        val velocityY = (matchedBounds.centerY() - lastBounds.centerY()) * 0.9f

        fallbackBoundsTemplate = matchedBounds
        
        // Slightly adapt the chroma template to accommodate slow color/light mutations
        if (matchConfidence > 0.4f) {
            updateClassicalTemplate(bitmap, matchedBounds, adaptationAlpha = 0.15f)
        }

        val tracked = TrackedObject(
            trackingId = fallbackTrackingId,
            boundingBox = matchedBounds,
            confidence = matchConfidence.coerceIn(0.1f, 1.0f),
            classId = 0,
            className = "chroma_fallback",
            velocityX = velocityX,
            velocityY = velocityY,
            timestampMs = timestampMs,
            motionPattern = if (abs(velocityX) > 0.015f || abs(velocityY) > 0.015f) MotionPattern.LINEAR else MotionPattern.STATIC
        )

        return listOf(tracked)
    }

    /**
     * Calculates the YCbCr chroma profile of the given bounding region.
     */
    private fun updateClassicalTemplate(bitmap: Bitmap, bounds: RectF, adaptationAlpha: Float = 1.0f) {
        val w = bitmap.width
        val h = bitmap.height

        val xStart = (bounds.left * w).toInt().coerceIn(0, w - 1)
        val yStart = (bounds.top * h).toInt().coerceIn(0, h - 1)
        val xEnd = (bounds.right * w).toInt().coerceIn(xStart + 1, w)
        val yEnd = (bounds.bottom * h).toInt().coerceIn(yStart + 1, h)

        val newTemplate = FloatArray(32 * 32) // 2D Chroma grid

        var count = 0
        for (y in yStart until yEnd step max(1, (yEnd - yStart) / 16)) {
            for (x in xStart until xEnd step max(1, (xEnd - xStart) / 16)) {
                if (x >= w || y >= h) continue
                val pixel = bitmap[x, y]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // YCbCr fast approximations
                val cb = (-0.1687f * r - 0.3313f * g + 0.5f * b + 128f)
                val cr = (0.5f * r - 0.4187f * g - 0.0813f * b + 128f)

                val cbBin = (cb / 8f).toInt().coerceIn(0, 31)
                val crBin = (cr / 8f).toInt().coerceIn(0, 31)

                newTemplate[cbBin * 32 + crBin] += 1f
                count++
            }
        }

        if (count > 0) {
            for (i in newTemplate.indices) {
                newTemplate[i] /= count.toFloat()
            }
        }

        val currentTemplate = fallbackChromaTemplate
        if (currentTemplate == null || adaptationAlpha >= 1.0f) {
            fallbackChromaTemplate = newTemplate
        } else {
            for (i in currentTemplate.indices) {
                currentTemplate[i] = currentTemplate[i] * (1.0f - adaptationAlpha) + newTemplate[i] * adaptationAlpha
            }
        }
        fallbackBoundsTemplate = bounds
        if (fallbackTrackingId == -1) {
            fallbackTrackingId = nextTrackingId++
        }
    }

    /**
     * Performs a multi-grid spatial correlation search over the target window bounds.
     */
    private fun scanSearchWindowForBestMatch(
        bitmap: Bitmap,
        targetTemplate: FloatArray,
        searchWindow: RectF,
        targetWidth: Float,
        targetHeight: Float
    ): Pair<RectF, Float> {
        val w = bitmap.width
        val h = bitmap.height

        val stepX = 0.03f
        val stepY = 0.03f

        var bestMatchBounds = searchWindow
        var maxScore = -1f

        var x = searchWindow.left
        while (x + targetWidth <= searchWindow.right) {
            var y = searchWindow.top
            while (y + targetHeight <= searchWindow.bottom) {
                val candidateBounds = RectF().apply {
                    left = x
                    top = y
                    right = x + targetWidth
                    bottom = y + targetHeight
                }
                val score = computeCorrelationScore(bitmap, targetTemplate, candidateBounds)
                
                if (score > maxScore) {
                    maxScore = score
                    bestMatchBounds = candidateBounds
                }
                y += stepY
            }
            x += stepX
        }

        return Pair(bestMatchBounds, if (maxScore < 0f) 0.1f else maxScore)
    }

    private fun computeCorrelationScore(bitmap: Bitmap, targetTemplate: FloatArray, candidate: RectF): Float {
        val w = bitmap.width
        val h = bitmap.height

        val xStart = (candidate.left * w).toInt().coerceIn(0, w - 1)
        val yStart = (candidate.top * h).toInt().coerceIn(0, h - 1)
        val xEnd = (candidate.right * w).toInt().coerceIn(xStart + 1, w)
        val yEnd = (candidate.bottom * h).toInt().coerceIn(yStart + 1, h)

        val candTemplate = FloatArray(32 * 32)
        var count = 0

        for (y in yStart until yEnd step max(1, (yEnd - yStart) / 10)) {
            for (x in xStart until xEnd step max(1, (xEnd - xStart) / 10)) {
                if (x >= w || y >= h) continue
                val pixel = bitmap[x, y]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                val cb = (-0.1687f * r - 0.3313f * g + 0.5f * b + 128f)
                val cr = (0.5f * r - 0.4187f * g - 0.0813f * b + 128f)

                val cbBin = (cb / 8f).toInt().coerceIn(0, 31)
                val crBin = (cr / 8f).toInt().coerceIn(0, 31)

                candTemplate[cbBin * 32 + crBin] += 1f
                count++
            }
        }

        if (count == 0) return 0f

        for (i in candTemplate.indices) {
            candTemplate[i] /= count.toFloat()
        }

        // Histogram intersection
        var intersection = 0f
        for (i in targetTemplate.indices) {
            intersection += min(targetTemplate[i], candTemplate[i])
        }

        return intersection
    }

    /**
     * Standard frame differencing / saliency anchor extraction.
     */
    private fun findFrameSaliencyCentroid(bitmap: Bitmap): RectF {
        findMotionBounds(bitmap)?.let { return it }

        val w = bitmap.width
        val h = bitmap.height
        
        // Grid-based chroma dispersion/intensity differences
        var maxSaliency = 0f
        var bestX = 0.5f
        var bestY = 0.5f

        // Sample grid
        val step = 5
        for (gy in 2 until step - 1) {
            for (gx in 2 until step - 1) {
                val cx = gx.toFloat() / step
                val cy = gy.toFloat() / step
                
                val pixel = bitmap[(cx * w).toInt().coerceIn(0, w - 1), (cy * h).toInt().coerceIn(0, h - 1)]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Contrast against pure neutral gray (128, 128, 128)
                val saliency = sqrt(((r - 128f) * (r - 128f) + (g - 128f) * (g - 128f) + (b - 128f) * (b - 128f)).toDouble()).toFloat()
                
                // Add center bias
                val distFromCenter = sqrt((cx - 0.5f) * (cx - 0.5f) + (cy - 0.5f) * (cy - 0.5f))
                val centerBias = 1.0f - distFromCenter * 1.5f

                val totalScore = saliency * centerBias
                if (totalScore > maxSaliency) {
                    maxSaliency = totalScore
                    bestX = cx
                    bestY = cy
                }
            }
        }

        return RectF().apply {
            left = (bestX - 0.1f).coerceIn(0f, 1f)
            top = (bestY - 0.1f).coerceIn(0f, 1f)
            right = (bestX + 0.1f).coerceIn(0f, 1f)
            bottom = (bestY + 0.1f).coerceIn(0f, 1f)
        }
    }

    private fun findMotionBounds(bitmap: Bitmap): RectF? {
        val downWidth = 48
        val downHeight = 36
        val currentLuma = downsampleLuma(bitmap, downWidth, downHeight)
        val previousLuma = previousFallbackLuma
        previousFallbackLuma = currentLuma
        previousFallbackLumaWidth = downWidth
        previousFallbackLumaHeight = downHeight

        if (previousLuma == null ||
            previousFallbackLumaWidth != downWidth ||
            previousFallbackLumaHeight != downHeight
        ) {
            return null
        }

        val diff = ByteArray(downWidth * downHeight)
        for (i in diff.indices) {
            diff[i] = abs((currentLuma[i].toInt() and 0xFF) - (previousLuma[i].toInt() and 0xFF)).toByte()
        }

        val threshold = otsuThreshold(diff)
        val mask = ByteArray(diff.size)
        for (i in diff.indices) {
            mask[i] = if ((diff[i].toInt() and 0xFF) > threshold) 1 else 0
        }

        val cleaned = morphOpenClose(mask, downWidth, downHeight)
        var minX = downWidth
        var minY = downHeight
        var maxX = -1
        var maxY = -1
        var count = 0
        for (y in 0 until downHeight) {
            for (x in 0 until downWidth) {
                if (cleaned[y * downWidth + x].toInt() != 0) {
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                    count++
                }
            }
        }
        if (count < 8 || maxX < minX || maxY < minY) return null

        val padX = 3
        val padY = 3
        return RectF().apply {
            left = ((minX - padX).coerceAtLeast(0) / downWidth.toFloat()).coerceIn(0f, 1f)
            top = ((minY - padY).coerceAtLeast(0) / downHeight.toFloat()).coerceIn(0f, 1f)
            right = ((maxX + padX).coerceAtMost(downWidth - 1) / downWidth.toFloat()).coerceIn(0f, 1f)
            bottom = ((maxY + padY).coerceAtMost(downHeight - 1) / downHeight.toFloat()).coerceIn(0f, 1f)
        }
    }

    private fun downsampleLuma(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): ByteArray {
        val luma = ByteArray(targetWidth * targetHeight)
        val width = bitmap.width
        val height = bitmap.height
        for (y in 0 until targetHeight) {
            val srcY = ((y + 0.5f) * height / targetHeight).toInt().coerceIn(0, height - 1)
            for (x in 0 until targetWidth) {
                val srcX = ((x + 0.5f) * width / targetWidth).toInt().coerceIn(0, width - 1)
                val pixel = bitmap[srcX, srcY]
                val gray = (0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel)).toInt()
                luma[y * targetWidth + x] = gray.coerceIn(0, 255).toByte()
            }
        }
        return luma
    }

    private fun otsuThreshold(values: ByteArray): Int {
        val histogram = IntArray(256)
        values.forEach { value -> histogram[value.toInt() and 0xFF]++ }
        val total = values.size
        var sum = 0L
        for (i in histogram.indices) sum += i.toLong() * histogram[i]

        var sumBackground = 0L
        var weightBackground = 0
        var maxVariance = -1.0
        var threshold = 0

        for (t in histogram.indices) {
            weightBackground += histogram[t]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break

            sumBackground += t.toLong() * histogram[t]
            val meanBackground = sumBackground.toDouble() / weightBackground
            val meanForeground = (sum - sumBackground).toDouble() / weightForeground
            val variance = weightBackground.toDouble() * weightForeground.toDouble() *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (variance > maxVariance) {
                maxVariance = variance
                threshold = t
            }
        }
        return threshold
    }

    private fun morphOpenClose(mask: ByteArray, width: Int, height: Int): ByteArray {
        val opened = dilate(erode(mask, width, height), width, height)
        return erode(dilate(opened, width, height), width, height)
    }

    private fun erode(mask: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(mask.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var keep = 1
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (mask[(y + dy) * width + (x + dx)].toInt() == 0) keep = 0
                    }
                }
                out[y * width + x] = keep.toByte()
            }
        }
        return out
    }

    private fun dilate(mask: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(mask.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var keep = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (mask[(y + dy) * width + (x + dx)].toInt() != 0) keep = 1
                    }
                }
                out[y * width + x] = keep.toByte()
            }
        }
        return out
    }

    /**
     * Pick the primary track using composite scoring.
     */
    fun selectPrimaryTrack(tracks: List<TrackedObject>): TrackedObject? {
        if (tracks.isEmpty()) return null
        return tracks.sortedByDescending { track ->
            activeTracks[track.trackingId]?.let { state ->
                objectSelectionScore(track.confidence, track.boundingBox, state.trackedFrames)
            } ?: track.selectionScore
        }.firstOrNull()
    }

    private fun calculateIoU(rect1: RectF, rect2: RectF): Float {
        val intersectionLeft = max(rect1.left, rect2.left)
        val intersectionTop = max(rect1.top, rect2.top)
        val intersectionRight = min(rect1.right, rect2.right)
        val intersectionBottom = min(rect1.bottom, rect2.bottom)

        if (intersectionLeft < intersectionRight && intersectionTop < intersectionBottom) {
            val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
            val rect1Area = rect1.width() * rect1.height()
            val rect2Area = rect2.width() * rect2.height()
            val unionArea = rect1Area + rect2Area - intersectionArea
            return if (unionArea > 0) intersectionArea / unionArea else 0f
        }
        return 0f
    }

    private fun TrackState.toTrackedObject(timestampMs: Long): TrackedObject {
        return TrackedObject(
            trackingId = trackingId,
            boundingBox = currentBounds,
            confidence = confidence,
            classId = classId,
            className = className,
            velocityX = velocityX,
            velocityY = velocityY,
            timestampMs = timestampMs,
            accelerationX = accelerationX,
            accelerationY = accelerationY,
            predictedX = kalmanState.state[0],
            predictedY = kalmanState.state[1],
            motionPattern = if (abs(velocityX) > 0.015f || abs(velocityY) > 0.015f) MotionPattern.LINEAR else MotionPattern.STATIC,
            subjectType = SubjectType.OBJECT,
            category = objectCategoryForClass(classId),
            predictedBounds = boundsFromObjectState(kalmanState.state),
            trackedFrames = trackedFrames,
            missedFrames = missedFrames,
            selectionScore = objectSelectionScore(confidence, currentBounds, trackedFrames),
            focusDistanceHint = focusDistanceHint(currentBounds)
        )
    }

    private fun objectCategoryForClass(classId: Int): SubjectCategory {
        return when (classId) {
            1 -> SubjectCategory.HUMAN
            17, 18 -> SubjectCategory.PET
            2, 3, 4, 5, 6, 7, 8, 9 -> SubjectCategory.VEHICLE
            16, 19, 20, 21, 22, 23, 24, 25 -> SubjectCategory.ANIMAL
            64 -> SubjectCategory.NATURE
            else -> SubjectCategory.OBJECT
        }
    }

    private fun focusDistanceHint(bounds: RectF): Float {
        val area = ((bounds.right - bounds.left) * (bounds.bottom - bounds.top)).coerceIn(0f, 1f)
        return (1.0f - sqrt(area)).coerceIn(0.05f, 0.95f)
    }

    override fun close() {
        lastFallbackBitmap?.recycle()
        lastFallbackBitmap = null
        previousFallbackLuma = null
        activeTracks.clear()
    }

    private data class RawDetection(
        val classId: Int,
        val className: String,
        val boundingBox: RectF,
        val confidence: Float
    )
}
