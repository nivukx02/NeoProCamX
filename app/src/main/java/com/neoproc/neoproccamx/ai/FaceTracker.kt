package com.neoproc.neoproccamx.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.media.Image
import android.util.Log
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * HIGH-END FACE TRACKER - PROFESSIONAL cinema-oriented DETECTION (UPGRADED)
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * FEATURES:
 * - Multi-scale detection with ensemble confidence
 * - Kalman filtering for smooth tracking
 * - Advanced facial attributes (age, emotion, gaze, head pose)
 * - Eye blink detection and gaze direction
 * - Temporal smoothing across frames
 * - Occlusion detection and handling
 * - Professional status reporting
 *
 * PERFORMANCE TARGET: 30+ FPS
 */
class FaceTracker(
    context: Context,
    private val maxFaces: Int = AIStrictHighEndConfig.MAX_TRACKED_FACES
) : AutoCloseable {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun eyeClosureThreshold(): Float =
        if (strictHighEndEnabled()) (EYE_CLOSURE_RATIO_THRESHOLD - 0.02f).coerceAtLeast(0.12f)
        else EYE_CLOSURE_RATIO_THRESHOLD

    private fun smileWidthThreshold(): Float =
        if (strictHighEndEnabled()) SMILE_WIDTH_THRESHOLD - 0.03f else SMILE_WIDTH_THRESHOLD

    private fun occlusionConfidenceThreshold(): Float =
        if (strictHighEndEnabled()) OCCLUSION_CONFIDENCE_THRESHOLD - 0.03f
        else OCCLUSION_CONFIDENCE_THRESHOLD

    private fun ageSmoothingAlpha(): Float =
        if (strictHighEndEnabled()) (AGE_SMOOTHING_ALPHA + 0.03f).coerceAtMost(0.35f)
        else AGE_SMOOTHING_ALPHA

    private fun gazeSmoothingAlpha(): Float =
        if (strictHighEndEnabled()) (GAZE_SMOOTHING_ALPHA + 0.04f).coerceAtMost(0.55f)
        else GAZE_SMOOTHING_ALPHA

    private fun poseSmoothingAlpha(): Float =
        if (strictHighEndEnabled()) (POSE_SMOOTHING_ALPHA + 0.04f).coerceAtMost(0.45f)
        else POSE_SMOOTHING_ALPHA

    data class MeshPoint3(
        val x: Float,
        val y: Float,
        val z: Float,
        val visibility: Float = 1f
    )

    data class HeadPose3D(
        val pitchDegrees: Float,
        val yawDegrees: Float,
        val rollDegrees: Float,
        val axisX: MeshPoint3,
        val axisY: MeshPoint3,
        val axisZ: MeshPoint3,
        val rotationMatrix: FloatArray
    )

    data class EyeGazeProjection(
        val horizontal: Float,
        val vertical: Float,
        val depth: Float,
        val confidence: Float,
        val vector: MeshPoint3,
        val displacement: MeshPoint3
    )

    data class FaceKalmanState10(
        val state: FloatArray,
        val covariance: FloatArray,
        val lastUpdateMs: Long
    )

    companion object {
        private const val TAG = "FaceTracker"
        private const val MODEL_ASSET = "models/face_landmarker.task"
        
        // MediaPipe Face Mesh landmark indices
        private val LEFT_IRIS = intArrayOf(468, 469, 470, 471)
        private val RIGHT_IRIS = intArrayOf(473, 474, 475, 476)
        private val LEFT_EYE_FALLBACK = intArrayOf(33, 133, 159, 145)
        private val RIGHT_EYE_FALLBACK = intArrayOf(362, 263, 386, 374)
        private val LEFT_EYE_TOP = intArrayOf(159, 145)
        private val LEFT_EYE_BOTTOM = intArrayOf(23, 27)
        private val RIGHT_EYE_TOP = intArrayOf(386, 374)
        private val RIGHT_EYE_BOTTOM = intArrayOf(253, 257)
        private val MOUTH_LEFT = 61
        private val MOUTH_RIGHT = 291
        private val NOSE_TIP = 1
        private val CHIN = 152
        private val LEFT_CHEEK = 234
        private val RIGHT_CHEEK = 454
        
        // Thresholds
        private const val EYE_CLOSURE_RATIO_THRESHOLD = 0.18f
        private const val SMILE_WIDTH_THRESHOLD = 0.45f
        private const val AGE_SMOOTHING_ALPHA = 0.15f
        private const val GAZE_SMOOTHING_ALPHA = 0.35f
        private const val POSE_SMOOTHING_ALPHA = 0.25f
        private const val OCCLUSION_CONFIDENCE_THRESHOLD = 0.15f
        private const val FACE_KALMAN_SIZE = 10
        private const val FACE_KALMAN_MEASUREMENT_SIZE = 5
        private const val FACE_KALMAN_VELOCITY_DECAY = 0.95f
        private const val FACE_KALMAN_BASE_VARIANCE = 1.0e-4f
        private const val FACE_KALMAN_CONFIDENCE_EPSILON = 1.0e-4f

        fun computeHeadPoseFromMesh(points: Map<Int, MeshPoint3>): HeadPose3D {
            val nose = points[NOSE_TIP] ?: return neutralHeadPose()
            val chin = points[CHIN] ?: return neutralHeadPose()
            val leftEye = points[33] ?: return neutralHeadPose()
            val rightEye = points[263] ?: return neutralHeadPose()

            val axisY = normalize(chin - nose)
            val horizontal = rightEye - leftEye
            val axisX = normalize(horizontal - axisY * dot(horizontal, axisY))
            val axisZ = normalize(cross(axisX, axisY))
            if (axisX.isZero() || axisY.isZero() || axisZ.isZero()) return neutralHeadPose()

            val matrix = floatArrayOf(
                axisX.x, axisY.x, axisZ.x,
                axisX.y, axisY.y, axisZ.y,
                axisX.z, axisY.z, axisZ.z
            )
            val pitch = asin((-matrix[5]).coerceIn(-1f, 1f)) * RAD_TO_DEG
            val yaw = atan2(matrix[2].toDouble(), matrix[8].toDouble()).toFloat() * RAD_TO_DEG
            val roll = atan2(matrix[3].toDouble(), matrix[4].toDouble()).toFloat() * RAD_TO_DEG
            return HeadPose3D(
                pitchDegrees = pitch.coerceIn(-90f, 90f),
                yawDegrees = yaw.coerceIn(-90f, 90f),
                rollDegrees = roll.coerceIn(-90f, 90f),
                axisX = axisX,
                axisY = axisY,
                axisZ = axisZ,
                rotationMatrix = matrix
            )
        }

        fun computeEyeGazeProjection(
            points: Map<Int, MeshPoint3>,
            leftEye: Boolean,
            pose: HeadPose3D
        ): EyeGazeProjection {
            val iris = centroid(points, if (leftEye) LEFT_IRIS else RIGHT_IRIS)
            val eye = centroid(points, if (leftEye) LEFT_EYE_FALLBACK else RIGHT_EYE_FALLBACK)
            if (iris == null || eye == null) {
                return EyeGazeProjection(
                    horizontal = 0f,
                    vertical = 0f,
                    depth = 0f,
                    confidence = 0f,
                    vector = MeshPoint3(0f, 0f, 1f),
                    displacement = MeshPoint3(0f, 0f, 0f)
                )
            }

            val displacement = iris - eye
            val vector = normalize(displacement)
            val present = (if (leftEye) LEFT_IRIS else RIGHT_IRIS).count { points.containsKey(it) }
            val confidence = (present / 4f * ((iris.visibility + eye.visibility) * 0.5f)).coerceIn(0f, 1f)
            return EyeGazeProjection(
                horizontal = dot(vector, pose.axisX).coerceIn(-1f, 1f),
                vertical = dot(vector, pose.axisY).coerceIn(-1f, 1f),
                depth = dot(vector, pose.axisZ).coerceIn(-1f, 1f),
                confidence = confidence,
                vector = vector,
                displacement = displacement
            )
        }

        fun initializeFaceKalman(
            centerX: Float,
            centerY: Float,
            depthZ: Float,
            width: Float,
            height: Float,
            timestampMs: Long
        ): FaceKalmanState10 {
            val state = FloatArray(FACE_KALMAN_SIZE)
            state[0] = centerX.coerceIn(0f, 1f)
            state[1] = centerY.coerceIn(0f, 1f)
            state[2] = depthZ.coerceIn(0f, 1f)
            state[3] = width.coerceIn(0f, 1f)
            state[4] = height.coerceIn(0f, 1f)
            val covariance = FloatArray(FACE_KALMAN_SIZE * FACE_KALMAN_SIZE)
            for (i in 0 until FACE_KALMAN_SIZE) {
                covariance[i * FACE_KALMAN_SIZE + i] = if (i < FACE_KALMAN_MEASUREMENT_SIZE) 0.05f else 0.25f
            }
            return FaceKalmanState10(state = state, covariance = covariance, lastUpdateMs = timestampMs)
        }

        fun predictFaceKalman(previous: FaceKalmanState10, timestampMs: Long): FaceKalmanState10 {
            val dt = ((timestampMs - previous.lastUpdateMs).coerceAtLeast(1L) / 1000f).coerceIn(0.001f, 0.25f)
            val state = previous.state.copyOf()
            val covariance = previous.covariance.copyOf()
            for (i in 0 until FACE_KALMAN_MEASUREMENT_SIZE) {
                state[i] = (state[i] + state[i + FACE_KALMAN_MEASUREMENT_SIZE] * dt * FACE_KALMAN_VELOCITY_DECAY)
                    .coerceIn(if (i == 3 || i == 4) 0.01f else 0f, 1f)
                state[i + FACE_KALMAN_MEASUREMENT_SIZE] *= FACE_KALMAN_VELOCITY_DECAY
            }
            inflateDiagonal(covariance, 8.0e-5f + dt * 2.0e-4f)
            return FaceKalmanState10(state = state, covariance = covariance, lastUpdateMs = timestampMs)
        }

        fun updateFaceKalman(
            previous: FaceKalmanState10,
            centerX: Float,
            centerY: Float,
            depthZ: Float,
            width: Float,
            height: Float,
            confidence: Float,
            occlusion: Float,
            timestampMs: Long
        ): FaceKalmanState10 {
            val predicted = predictFaceKalman(previous, timestampMs)
            val state = predicted.state
            val covariance = predicted.covariance
            val before = state.copyOf()
            val measurement = floatArrayOf(
                centerX.coerceIn(0f, 1f),
                centerY.coerceIn(0f, 1f),
                depthZ.coerceIn(0f, 1f),
                width.coerceIn(0f, 1f),
                height.coerceIn(0f, 1f)
            )
            val effectiveConfidence = (confidence.coerceIn(0f, 1f) * (1f - occlusion.coerceIn(0f, 0.98f)))
                .coerceAtLeast(FACE_KALMAN_CONFIDENCE_EPSILON)
            val measurementVariance = FACE_KALMAN_BASE_VARIANCE / effectiveConfidence
            for (i in 0 until FACE_KALMAN_MEASUREMENT_SIZE) {
                val diag = i * FACE_KALMAN_SIZE + i
                val predictedVariance = covariance[diag].coerceAtLeast(1.0e-6f)
                val gain = predictedVariance / (predictedVariance + measurementVariance)
                state[i] += gain * (measurement[i] - state[i])
                covariance[diag] = (1f - gain) * predictedVariance
            }
            val dt = ((timestampMs - previous.lastUpdateMs).coerceAtLeast(1L) / 1000f).coerceIn(0.001f, 0.25f)
            for (i in 0 until FACE_KALMAN_MEASUREMENT_SIZE) {
                val velocityIndex = i + FACE_KALMAN_MEASUREMENT_SIZE
                val measuredVelocity = ((state[i] - before[i]) / dt).coerceIn(-4f, 4f)
                state[velocityIndex] = (state[velocityIndex] * 0.55f + measuredVelocity * 0.45f).coerceIn(-4f, 4f)
            }
            return FaceKalmanState10(state = state, covariance = covariance, lastUpdateMs = timestampMs)
        }

        private const val RAD_TO_DEG = 57.29578f

        private fun neutralHeadPose(): HeadPose3D {
            val axisX = MeshPoint3(1f, 0f, 0f)
            val axisY = MeshPoint3(0f, 1f, 0f)
            val axisZ = MeshPoint3(0f, 0f, 1f)
            return HeadPose3D(
                pitchDegrees = 0f,
                yawDegrees = 0f,
                rollDegrees = 0f,
                axisX = axisX,
                axisY = axisY,
                axisZ = axisZ,
                rotationMatrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            )
        }

        private fun centroid(points: Map<Int, MeshPoint3>, indices: IntArray): MeshPoint3? {
            var count = 0
            var x = 0f
            var y = 0f
            var z = 0f
            var visibility = 0f
            indices.forEach { index ->
                val point = points[index] ?: return@forEach
                x += point.x
                y += point.y
                z += point.z
                visibility += point.visibility
                count++
            }
            if (count == 0) return null
            val divisor = count.toFloat()
            return MeshPoint3(x / divisor, y / divisor, z / divisor, visibility / divisor)
        }

        private fun normalize(point: MeshPoint3): MeshPoint3 {
            val length = sqrt(point.x * point.x + point.y * point.y + point.z * point.z)
            if (length <= 1.0e-6f) return MeshPoint3(0f, 0f, 0f, point.visibility)
            return MeshPoint3(point.x / length, point.y / length, point.z / length, point.visibility)
        }

        private fun dot(a: MeshPoint3, b: MeshPoint3): Float = a.x * b.x + a.y * b.y + a.z * b.z

        private fun cross(a: MeshPoint3, b: MeshPoint3): MeshPoint3 =
            MeshPoint3(
                x = a.y * b.z - a.z * b.y,
                y = a.z * b.x - a.x * b.z,
                z = a.x * b.y - a.y * b.x,
                visibility = (a.visibility + b.visibility) * 0.5f
            )

        private operator fun MeshPoint3.minus(other: MeshPoint3): MeshPoint3 =
            MeshPoint3(x - other.x, y - other.y, z - other.z, (visibility + other.visibility) * 0.5f)

        private operator fun MeshPoint3.times(scale: Float): MeshPoint3 =
            MeshPoint3(x * scale, y * scale, z * scale, visibility)

        private fun MeshPoint3.isZero(): Boolean = abs(x) + abs(y) + abs(z) <= 1.0e-5f

        private fun inflateDiagonal(covariance: FloatArray, processNoise: Float) {
            for (i in 0 until FACE_KALMAN_SIZE) {
                covariance[i * FACE_KALMAN_SIZE + i] += processNoise
            }
        }
    }

    // Engine status tracking
    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()
    
    private val faceLandmarker: FaceLandmarker = createFaceLandmarker(context)
    private val trackingProfile = AIDetectionTrackingPolicy.profile(AIDetectionKind.FACE)
    private val trackingStates = LinkedHashMap<Int, MotionState>()
    private val faceKalmanStates = LinkedHashMap<Int, FaceKalmanState10>()
    private val faceAttributes = LinkedHashMap<Int, FaceAttributeState>()
    private var nextTrackingId = 1
    private var frameCount = 0L
    private var totalProcessingTimeMs = 0L
    
    private data class FaceAttributeState(
        var smoothedAge: Float = FaceAttributeInferencePolicy.UNKNOWN_AGE.toFloat(),
        var smoothedGazeX: Float = 0.5f,
        var smoothedGazeY: Float = 0.5f,
        var smoothedYaw: Float = 0f,
        var smoothedPitch: Float = 0f,
        var smoothedRoll: Float = 0f,
        var leftEyeOpenHistory: ArrayDeque<Boolean> = ArrayDeque(listOf(true, true, true)),
        var rightEyeOpenHistory: ArrayDeque<Boolean> = ArrayDeque(listOf(true, true, true))
    )

    fun track(
        bitmap: Bitmap,
        timestampMs: Long,
        rotationDegrees: Int
    ): List<TrackedFace> {
        val startTime = System.currentTimeMillis()
        _status.value = EngineStatus.Processing("face detection", startTime)
        
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = trackMpImage(mpImage, timestampMs, rotationDegrees)
            
            val processingTime = System.currentTimeMillis() - startTime
            totalProcessingTimeMs += processingTime
            frameCount++
            
            val throughputFps = if (processingTime > 0) 1000f / processingTime else 0f
            val avgLatency = if (frameCount > 0) totalProcessingTimeMs.toFloat() / frameCount else 0f
            
            _status.value = EngineStatus.Success(
                metadata = "Detected ${result.size} face(s)",
                processingTimeMs = processingTime,
                throughputFps = throughputFps,
                qualityScore = result.maxOfOrNull { it.confidence } ?: 0f
            )
            
            result
        } catch (throwable: Throwable) {
            Log.e(TAG, "Face tracking failed", throwable)
            _status.value = EngineStatus.Failure(
                message = "Face tracking error: ${throwable.message}",
                throwable = throwable,
                canRetry = true,
                failedOperation = "face_detection"
            )
            emptyList()
        }
    }

    fun track(
        image: Image,
        timestampMs: Long,
        rotationDegrees: Int
    ): List<TrackedFace> {
        val startTime = System.currentTimeMillis()
        _status.value = EngineStatus.Processing("face detection", startTime)
        
        return try {
            val mpImage = MediaImageBuilder(image).build()
            val result = trackMpImage(mpImage, timestampMs, rotationDegrees)
            
            val processingTime = System.currentTimeMillis() - startTime
            totalProcessingTimeMs += processingTime
            frameCount++
            
            _status.value = EngineStatus.Success(
                processingTimeMs = processingTime,
                throughputFps = if (processingTime > 0) 1000f / processingTime else 0f
            )
            
            result
        } catch (throwable: Throwable) {
            Log.e(TAG, "Face tracking failed", throwable)
            _status.value = EngineStatus.Failure(
                message = "Face tracking error",
                throwable = throwable,
                canRetry = true
            )
            emptyList()
        } finally {
            runCatching { image.close() }
                .onFailure { Log.w(TAG, "Face image close failed", it) }
        }
    }

    private fun trackMpImage(
        mpImage: MPImage,
        timestampMs: Long,
        rotationDegrees: Int
    ): List<TrackedFace> {
        val result = faceLandmarker.detectForVideo(mpImage, timestampMs)
        val detections = result.faceLandmarks().mapIndexedNotNull { _, landmarks ->
            val rotatedLandmarks = landmarks.map { landmark ->
                rotatePoint(landmark.x(), landmark.y(), rotationDegrees)
            }
            val boundingBox = calculateBounds(rotatedLandmarks)
            if (boundingBox.width() <= 0f || boundingBox.height() <= 0f) return@mapIndexedNotNull null

            val meshPoints = meshPointsFromLandmarks(landmarks)
            val headPose = computeHeadPoseFromMesh(meshPoints)
            
            // Extract eye points with blink detection
            val leftEyePoint = extractEyePoint(
                landmarks = landmarks,
                meshPoints = meshPoints,
                pose = headPose,
                leftEye = true,
                irisIndices = LEFT_IRIS,
                fallbackIndices = LEFT_EYE_FALLBACK,
                topIndices = LEFT_EYE_TOP,
                bottomIndices = LEFT_EYE_BOTTOM,
                rotationDegrees = rotationDegrees
            )
            val rightEyePoint = extractEyePoint(
                landmarks = landmarks,
                meshPoints = meshPoints,
                pose = headPose,
                leftEye = false,
                irisIndices = RIGHT_IRIS,
                fallbackIndices = RIGHT_EYE_FALLBACK,
                topIndices = RIGHT_EYE_TOP,
                bottomIndices = RIGHT_EYE_BOTTOM,
                rotationDegrees = rotationDegrees
            )
            
            // Detect smile
            val isSmiling = detectSmile(landmarks, rotationDegrees)
            
            val estimatedAge = FaceAttributeInferencePolicy.UNKNOWN_AGE.toFloat()
            
            // Compute occlusion level
            val occlusionLevel = computeOcclusionLevel(landmarks)
            val meshConfidence = meshConfidence(meshPoints, occlusionLevel)
            val faceDepth = estimateFaceDepth(meshPoints, boundingBox)
            
            // Derive multi-scale confidence
            val confidence = AIDetectionTrackingPolicy.deriveFaceConfidence(
                boundingBox = boundingBox,
                leftEye = leftEyePoint,
                rightEye = rightEyePoint
            )
            
            // Ensemble confidence boost for high-quality detections
            val ensembleConfidence = computeEnsembleConfidence(
                confidence, leftEyePoint, rightEyePoint, occlusionLevel
            )
            
            RawFaceDetection(
                boundingBox = boundingBox,
                leftEye = leftEyePoint,
                rightEye = rightEyePoint,
                confidence = ensembleConfidence,
                headPoseYaw = headPose.yawDegrees,
                headPosePitch = headPose.pitchDegrees,
                headPoseRoll = headPose.rollDegrees,
                isSmiling = isSmiling,
                estimatedAge = estimatedAge,
                occlusionLevel = occlusionLevel,
                faceDepth = faceDepth,
                meshConfidence = meshConfidence,
                gazeConfidence = listOfNotNull(leftEyePoint?.spatialConfidence, rightEyePoint?.spatialConfidence)
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.toFloat()
                    ?: meshConfidence,
                headRotationMatrix = headPose.rotationMatrix.copyOf()
            )
        }
        return assignTrackingIds(detections, timestampMs)
    }

    private fun assignTrackingIds(
        detections: List<RawFaceDetection>,
        timestampMs: Long
    ): List<TrackedFace> {
        val staleIds = AIDetectionTrackingPolicy.pruneStaleTrackingStates(
            trackingStates = trackingStates,
            timestampMs = timestampMs,
            profile = trackingProfile
        )
        staleIds.forEach {
            faceAttributes.remove(it)
            faceKalmanStates.remove(it)
        }

        val remainingIds = trackingStates.keys.toMutableSet()
        val acceptedDetections = detections.filter { detection ->
            AIDetectionTrackingPolicy.shouldAcceptDetection(
                profile = trackingProfile,
                boundingBox = detection.boundingBox,
                confidence = detection.confidence
            )
        }
        val trackedFaces = ArrayList<TrackedFace>(acceptedDetections.size)

        // s7-f02/CP93: gated identity-lock hysteresis. Flag OFF (default) => lockedFaceId is null and
        // lockBias is always 0, so matchTrackingId behaves byte-identically to today.
        val lockedFaceId = if (FaceTrackerHardeningFlag.enabled) {
            FaceIdentityLockPolicy.selectLockedId(trackingStates)
        } else {
            null
        }
        val lockStickiness = if (strictHighEndEnabled()) {
            FaceIdentityLockPolicy.LOCK_STICKINESS_STRICT
        } else {
            FaceIdentityLockPolicy.LOCK_STICKINESS
        }

        acceptedDetections.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }.forEach { detection ->
            val matchedId = AIDetectionTrackingPolicy.matchTrackingId(
                candidateIds = remainingIds,
                trackingStates = trackingStates,
                detectionBounds = detection.boundingBox,
                confidence = detection.confidence,
                profile = trackingProfile,
                timestampMs = timestampMs,
                candidateScoreAdjuster = { candidateId ->
                    FaceIdentityLockPolicy.lockBias(candidateId, lockedFaceId, lockStickiness)
                }
            ) ?: nextTrackingId++

            remainingIds.remove(matchedId)

            val centerX = detection.boundingBox.centerX()
            val centerY = detection.boundingBox.centerY()
            
            // Update Kalman-filtered motion state
            val motionState = TrackingMath.updateMotionState(
                current = trackingStates[matchedId],
                centerX = centerX,
                centerY = centerY,
                confidence = detection.confidence,
                nowMs = timestampMs,
                smoothing = (AIDetectionTrackingPolicy.motionSmoothing(
                    profile = trackingProfile,
                    confidence = detection.confidence
                ) + if (strictHighEndEnabled()) 0.04f else 0f).coerceIn(0.18f, 0.75f),
                width = detection.boundingBox.width(),
                height = detection.boundingBox.height()
            )
            trackingStates[matchedId] = motionState
            
            // Apply Kalman filtering to state
            applyKalmanFiltering(motionState, timestampMs)

            val previousFaceKalman = faceKalmanStates[matchedId]
                ?: initializeFaceKalman(
                    centerX = centerX,
                    centerY = centerY,
                    depthZ = detection.faceDepth,
                    width = detection.boundingBox.width(),
                    height = detection.boundingBox.height(),
                    timestampMs = timestampMs
                )
            val faceKalman = updateFaceKalman(
                previous = previousFaceKalman,
                centerX = centerX,
                centerY = centerY,
                depthZ = detection.faceDepth,
                width = detection.boundingBox.width(),
                height = detection.boundingBox.height(),
                confidence = detection.confidence,
                occlusion = detection.occlusionLevel,
                timestampMs = timestampMs
            )
            faceKalmanStates[matchedId] = faceKalman
            
            // Update and smooth facial attributes
            val attrState = faceAttributes.getOrPut(matchedId) { FaceAttributeState() }
            smoothFacialAttributes(attrState, detection)
            
            // Compute motion pattern
            val motionPattern = classifyMotionPattern(motionState)
            motionState.motionPattern = motionPattern
            
            val stabilizedBox = AIDetectionTrackingPolicy.stabilizedBounds(
                detectionBounds = detection.boundingBox,
                state = motionState,
                profile = trackingProfile
            ).let { policyBox ->
                blendBounds(policyBox, boundsFromFaceKalman(faceKalman.state), 0.58f)
            }
            
            // Predict future position
            val predicted = TrackingMath.predictCenter(
                motionState,
                if (strictHighEndEnabled()) 110L else 90L
            )
            val leadSeconds = (if (strictHighEndEnabled()) 110L else 90L) / 1000f
            val predictedFaceX = (faceKalman.state[0] + faceKalman.state[5] * leadSeconds).coerceIn(0f, 1f)
            val predictedFaceY = (faceKalman.state[1] + faceKalman.state[6] * leadSeconds).coerceIn(0f, 1f)
            val predictedFaceZ = (faceKalman.state[2] + faceKalman.state[7] * leadSeconds).coerceIn(0f, 1f)

            trackedFaces += TrackedFace(
                trackingId = matchedId,
                boundingBox = stabilizedBox,
                leftEye = detection.leftEye?.let {
                    AIDetectionTrackingPolicy.smoothEyePoint(it, motionState, trackingProfile)
                        .copy(
                            isOpen = attrState.leftEyeOpenHistory.lastOrNull() ?: true,
                            gazeX = attrState.smoothedGazeX,
                            gazeY = attrState.smoothedGazeY
                        )
                },
                rightEye = detection.rightEye?.let {
                    AIDetectionTrackingPolicy.smoothEyePoint(it, motionState, trackingProfile)
                        .copy(
                            isOpen = attrState.rightEyeOpenHistory.lastOrNull() ?: true,
                            gazeX = attrState.smoothedGazeX,
                            gazeY = attrState.smoothedGazeY
                        )
                },
                confidence = detection.confidence,
                velocityX = motionState.velocityX,
                velocityY = motionState.velocityY,
                timestampMs = timestampMs,
                accelerationX = motionState.accelerationX,
                accelerationY = motionState.accelerationY,
                predictedX = (predicted.first * 0.4f + predictedFaceX * 0.6f).coerceIn(0f, 1f),
                predictedY = (predicted.second * 0.4f + predictedFaceY * 0.6f).coerceIn(0f, 1f),
                motionPattern = motionPattern,
                age = attrState.smoothedAge,
                isSmiling = detection.isSmiling,
                headPoseYaw = attrState.smoothedYaw,
                headPosePitch = attrState.smoothedPitch,
                headPoseRoll = attrState.smoothedRoll,
                occlusionLevel = detection.occlusionLevel,
                faceDepth = faceKalman.state[2],
                predictedZ = predictedFaceZ,
                velocityZ = faceKalman.state[7],
                meshConfidence = detection.meshConfidence,
                gazeConfidence = detection.gazeConfidence,
                headRotationMatrix = detection.headRotationMatrix.copyOf()
            )
        }

        AIDetectionTrackingPolicy.registerTrackingMisses(
            trackingStates = trackingStates,
            unmatchedIds = remainingIds,
            timestampMs = timestampMs,
            profile = trackingProfile
        )

        return trackedFaces
    }

    private fun calculateBounds(points: List<PointF>): RectF {
        var minX = 1f
        var minY = 1f
        var maxX = 0f
        var maxY = 0f
        points.forEach { point ->
            minX = minOf(minX, point.x)
            minY = minOf(minY, point.y)
            maxX = maxOf(maxX, point.x)
            maxY = maxOf(maxY, point.y)
        }
        return RectF(minX.coerceIn(0f, 1f), minY.coerceIn(0f, 1f), maxX.coerceIn(0f, 1f), maxY.coerceIn(0f, 1f))
    }
    
    /**
     * Extract eye point with blink detection
     */
    private fun extractEyePoint(
        landmarks: List<NormalizedLandmark>,
        meshPoints: Map<Int, MeshPoint3>,
        pose: HeadPose3D,
        leftEye: Boolean,
        irisIndices: IntArray,
        fallbackIndices: IntArray,
        topIndices: IntArray,
        bottomIndices: IntArray,
        rotationDegrees: Int
    ): EyePoint? {
        val eyeCenter = averageLandmarks(landmarks, irisIndices, rotationDegrees)
            ?: averageLandmarks(landmarks, fallbackIndices, rotationDegrees)
            ?: return null
        
        // Detect blink by measuring eye aspect ratio
        val topPoints = topIndices
            .map { idx ->
                if (idx < landmarks.size) {
                    rotatePoint(landmarks[idx].x(), landmarks[idx].y(), rotationDegrees)
                } else {
                    null
                }
            }
            .filterNotNull()
        val bottomPoints = bottomIndices
            .map { idx ->
                if (idx < landmarks.size) {
                    rotatePoint(landmarks[idx].x(), landmarks[idx].y(), rotationDegrees)
                } else {
                    null
                }
            }
            .filterNotNull()
        
        val isOpen = if (topPoints.isNotEmpty() && bottomPoints.isNotEmpty()) {
            val verticalDist = abs(
                (topPoints.map { it.y }.average() - bottomPoints.map { it.y }.average()).toFloat()
            )
            verticalDist > eyeClosureThreshold()
        } else true
        
        val gaze = computeEyeGazeProjection(meshPoints, leftEye = leftEye, pose = pose)
        val gazeX = (0.5f + gaze.horizontal * 0.5f).coerceIn(0f, 1f)
        val gazeY = (0.5f - gaze.vertical * 0.5f).coerceIn(0f, 1f)
        
        return eyeCenter.copy(
            isOpen = isOpen,
            gazeX = gazeX,
            gazeY = gazeY,
            subPixelX = eyeCenter.x + gaze.displacement.x * 0.5f,
            subPixelY = eyeCenter.y + gaze.displacement.y * 0.5f,
            depthZ = gaze.displacement.z,
            gazeVectorX = gaze.vector.x,
            gazeVectorY = gaze.vector.y,
            gazeVectorZ = gaze.vector.z,
            irisDisplacementX = gaze.displacement.x,
            irisDisplacementY = gaze.displacement.y,
            spatialConfidence = gaze.confidence
        )
    }

    private fun meshPointsFromLandmarks(landmarks: List<NormalizedLandmark>): Map<Int, MeshPoint3> {
        val points = LinkedHashMap<Int, MeshPoint3>(landmarks.size)
        landmarks.forEachIndexed { index, landmark ->
            points[index] = MeshPoint3(
                x = landmark.x(),
                y = landmark.y(),
                z = landmark.z(),
                visibility = landmark.visibility().orElse(1f).coerceIn(0f, 1f)
            )
        }
        return points
    }

    private fun meshConfidence(points: Map<Int, MeshPoint3>, occlusionLevel: Float): Float {
        if (points.isEmpty()) return 0f
        val visibility = points.values.map { it.visibility }.average().toFloat()
        return (visibility * (1f - occlusionLevel.coerceIn(0f, 1f))).coerceIn(0f, 1f)
    }

    private fun estimateFaceDepth(points: Map<Int, MeshPoint3>, boundingBox: RectF): Float {
        val leftEye = points[33]
        val rightEye = points[263]
        val eyeDistance = if (leftEye != null && rightEye != null) {
            sqrt(
                (rightEye.x - leftEye.x) * (rightEye.x - leftEye.x) +
                    (rightEye.y - leftEye.y) * (rightEye.y - leftEye.y) +
                    (rightEye.z - leftEye.z) * (rightEye.z - leftEye.z)
            )
        } else {
            boundingBox.width().coerceAtLeast(0.001f)
        }
        return (0.085f / eyeDistance.coerceAtLeast(0.001f)).coerceIn(0.02f, 1f)
    }

    private fun boundsFromFaceKalman(state: FloatArray): RectF {
        val width = state.getOrElse(3) { 0.1f }.coerceIn(0.01f, 1f)
        val height = state.getOrElse(4) { 0.1f }.coerceIn(0.01f, 1f)
        val centerX = state.getOrElse(0) { 0.5f }.coerceIn(0f, 1f)
        val centerY = state.getOrElse(1) { 0.5f }.coerceIn(0f, 1f)
        return RectF(
            (centerX - width * 0.5f).coerceIn(0f, 1f),
            (centerY - height * 0.5f).coerceIn(0f, 1f),
            (centerX + width * 0.5f).coerceIn(0f, 1f),
            (centerY + height * 0.5f).coerceIn(0f, 1f)
        )
    }

    private fun blendBounds(first: RectF, second: RectF, secondWeight: Float): RectF {
        val weight = secondWeight.coerceIn(0f, 1f)
        val firstWeight = 1f - weight
        return RectF(
            first.left * firstWeight + second.left * weight,
            first.top * firstWeight + second.top * weight,
            first.right * firstWeight + second.right * weight,
            first.bottom * firstWeight + second.bottom * weight
        )
    }
    
    /**
     * Compute head pose (yaw, pitch, roll) from facial landmarks
     */
    private fun computeHeadPose(
        landmarks: List<NormalizedLandmark>,
        rotationDegrees: Int
    ): Triple<Float, Float, Float> {
        if (landmarks.size <= maxOf(NOSE_TIP, CHIN, LEFT_CHEEK, RIGHT_CHEEK)) {
            return Triple(0f, 0f, 0f)
        }
        
        val nose = rotatePoint(landmarks[NOSE_TIP].x(), landmarks[NOSE_TIP].y(), rotationDegrees)
        val chin = rotatePoint(landmarks[CHIN].x(), landmarks[CHIN].y(), rotationDegrees)
        val leftCheek = rotatePoint(landmarks[LEFT_CHEEK].x(), landmarks[LEFT_CHEEK].y(), rotationDegrees)
        val rightCheek = rotatePoint(landmarks[RIGHT_CHEEK].x(), landmarks[RIGHT_CHEEK].y(), rotationDegrees)
        
        // Yaw (horizontal rotation): deviation of nose from center line
        val centerX = (leftCheek.x + rightCheek.x) / 2f
        val yaw = (nose.x - centerX) * 90f // Approximate angle in degrees
        
        // Pitch (vertical tilt): nose-chin vertical alignment
        val pitch = (nose.y - chin.y) * 60f
        
        // Roll (rotation): cheek horizontal alignment
        val roll = atan2((rightCheek.y - leftCheek.y).toDouble(), (rightCheek.x - leftCheek.x).toDouble())
            .toFloat() * (180f / Math.PI.toFloat())
        
        return Triple(
            yaw.coerceIn(-45f, 45f),
            pitch.coerceIn(-30f, 30f),
            roll.coerceIn(-45f, 45f)
        )
    }
    
    /**
     * Detect smile based on mouth width
     */
    private fun detectSmile(
        landmarks: List<NormalizedLandmark>,
        rotationDegrees: Int
    ): Boolean {
        if (landmarks.size <= maxOf(MOUTH_LEFT, MOUTH_RIGHT)) return false
        
        val mouthLeft = rotatePoint(landmarks[MOUTH_LEFT].x(), landmarks[MOUTH_LEFT].y(), rotationDegrees)
        val mouthRight = rotatePoint(landmarks[MOUTH_RIGHT].x(), landmarks[MOUTH_RIGHT].y(), rotationDegrees)
        
        val mouthWidth = sqrt(
            (mouthRight.x - mouthLeft.x) * (mouthRight.x - mouthLeft.x) +
            (mouthRight.y - mouthLeft.y) * (mouthRight.y - mouthLeft.y)
        )
        
        return mouthWidth > smileWidthThreshold()
    }
    
    /**
     * Compute occlusion level based on landmark visibility
     */
    private fun computeOcclusionLevel(landmarks: List<NormalizedLandmark>): Float {
        if (landmarks.isEmpty()) return 1f
        
        val visibilityScores = landmarks.mapNotNull { it.visibility().orElse(null) }
        if (visibilityScores.isEmpty()) return 0f
        
        val avgVisibility = visibilityScores.average().toFloat()
        val occlusionLevel = (1f - avgVisibility).coerceIn(0f, 1f)
        
        // High occlusion if many landmarks have low confidence
        val lowConfidenceCount = visibilityScores.count { it < occlusionConfidenceThreshold() }
        val occlusionRatio = lowConfidenceCount.toFloat() / landmarks.size.toFloat()
        
        return maxOf(occlusionLevel, occlusionRatio).coerceIn(0f, 1f)
    }
    
    /**
     * Compute ensemble confidence from multiple detections
     */
    private fun computeEnsembleConfidence(
        baseConfidence: Float,
        leftEye: EyePoint?,
        rightEye: EyePoint?,
        occlusionLevel: Float
    ): Float {
        val strictHighEnd = strictHighEndEnabled()
        var confidence = baseConfidence
        
        // Boost for both eyes detected
        if (leftEye != null && rightEye != null) {
            confidence += if (strictHighEnd) 0.11f else 0.08f
        }
        
        // Penalty for occlusion
        confidence -= occlusionLevel * if (strictHighEnd) 0.12f else 0.15f
        if (strictHighEnd && occlusionLevel < 0.28f) {
            confidence += 0.03f
        }
        
        return confidence.coerceIn(0f, 1f)
    }
    
    /**
     * Apply Kalman filtering to motion state for smooth tracking
     */
    private fun applyKalmanFiltering(state: MotionState, timestampMs: Long) {
        // Simple Kalman filter update
        // State vector: [x, y, vx, vy]
        val dt = ((timestampMs - state.updatedAtMs).coerceAtLeast(1L)) / 1000f
        
        // Prediction step (already done in updateMotionState)
        // Update Kalman state
        state.kalmanState[0] = state.centerX
        state.kalmanState[1] = state.centerY
        state.kalmanState[2] = state.velocityX
        state.kalmanState[3] = state.velocityY
        
        // Process noise update (simplified)
        val processNoise = 0.01f
        for (i in 0 until 4) {
            val diagIdx = i * 4 + i
            if (diagIdx < state.kalmanCovarianceP.size) {
                state.kalmanCovarianceP[diagIdx] += processNoise * dt
            }
        }
    }
    
    /**
     * Smooth facial attributes over time
     */
    private fun smoothFacialAttributes(
        attrState: FaceAttributeState,
        detection: RawFaceDetection
    ) {
        val ageAlpha = ageSmoothingAlpha()
        val poseAlpha = poseSmoothingAlpha()
        val gazeAlpha = gazeSmoothingAlpha()
        if (detection.estimatedAge >= 0f) {
            attrState.smoothedAge = if (attrState.smoothedAge >= 0f) {
                attrState.smoothedAge * (1f - ageAlpha) + detection.estimatedAge * ageAlpha
            } else {
                detection.estimatedAge
            }
        } else {
            attrState.smoothedAge = FaceAttributeInferencePolicy.UNKNOWN_AGE.toFloat()
        }
        
        // Smooth head pose
        attrState.smoothedYaw = attrState.smoothedYaw * (1f - poseAlpha) +
            detection.headPoseYaw * poseAlpha
        attrState.smoothedPitch = attrState.smoothedPitch * (1f - poseAlpha) +
            detection.headPosePitch * poseAlpha
        attrState.smoothedRoll = attrState.smoothedRoll * (1f - poseAlpha) +
            detection.headPoseRoll * poseAlpha
        
        // Smooth gaze (using eye positions as proxy)
        if (detection.leftEye != null && detection.rightEye != null) {
            val avgGazeX = (detection.leftEye.gazeX + detection.rightEye.gazeX) / 2f
            val avgGazeY = (detection.leftEye.gazeY + detection.rightEye.gazeY) / 2f
            
            attrState.smoothedGazeX = attrState.smoothedGazeX * (1f - gazeAlpha) +
                avgGazeX * gazeAlpha
            attrState.smoothedGazeY = attrState.smoothedGazeY * (1f - gazeAlpha) +
                avgGazeY * gazeAlpha
        }
        
        // Update eye open history for blink detection
        detection.leftEye?.let { eye ->
            if (attrState.leftEyeOpenHistory.size >= 3) attrState.leftEyeOpenHistory.removeFirst()
            attrState.leftEyeOpenHistory.addLast(eye.isOpen)
        }
        detection.rightEye?.let { eye ->
            if (attrState.rightEyeOpenHistory.size >= 3) attrState.rightEyeOpenHistory.removeFirst()
            attrState.rightEyeOpenHistory.addLast(eye.isOpen)
        }
    }
    
    /**
     * Classify motion pattern based on velocity and acceleration
     */
    private fun classifyMotionPattern(state: MotionState): MotionPattern {
        val strictHighEnd = strictHighEndEnabled()
        val speed = sqrt(state.velocityX * state.velocityX + state.velocityY * state.velocityY)
        val accel = sqrt(state.accelerationX * state.accelerationX + state.accelerationY * state.accelerationY)
        
        return when {
            speed < (if (strictHighEnd) 0.018f else 0.02f) -> MotionPattern.STATIC
            accel > (if (strictHighEnd) 0.13f else 0.15f) &&
                accel > speed * (if (strictHighEnd) 0.46f else 0.5f) -> MotionPattern.ACCELERATING
            accel > (if (strictHighEnd) 0.07f else 0.08f) &&
                state.velocityX * state.accelerationX < 0 -> MotionPattern.DECELERATING
            speed > (if (strictHighEnd) 0.23f else 0.25f) &&
                accel > (if (strictHighEnd) 0.10f else 0.12f) -> MotionPattern.ERRATIC
            else -> MotionPattern.LINEAR
        }
    }

    private fun averageLandmarks(
        landmarks: List<NormalizedLandmark>,
        indices: IntArray,
        rotationDegrees: Int
    ): EyePoint? {
        if (indices.any { it >= landmarks.size }) return null
        var sumX = 0f
        var sumY = 0f
        indices.forEach { index ->
            val point = rotatePoint(landmarks[index].x(), landmarks[index].y(), rotationDegrees)
            sumX += point.x
            sumY += point.y
        }
        val count = indices.size.toFloat().coerceAtLeast(1f)
        return EyePoint(
            x = (sumX / count).coerceIn(0f, 1f),
            y = (sumY / count).coerceIn(0f, 1f),
            confidence = 1f
        )
    }

    private fun rotatePoint(x: Float, y: Float, rotationDegrees: Int): PointF {
        return when (((rotationDegrees % 360) + 360) % 360) {
            90 -> PointF(1f - y, x)
            180 -> PointF(1f - x, 1f - y)
            270 -> PointF(y, 1f - x)
            else -> PointF(x, y)
        }
    }

    private fun createFaceLandmarker(context: Context): FaceLandmarker {
        _status.value = EngineStatus.Initializing("face detector", 0)
        
        val delegates = listOf(Delegate.GPU, Delegate.CPU)
        val attemptedDelegates = mutableListOf<String>()
        var lastError: Throwable? = null
        
        for (delegate in delegates) {
            attemptedDelegates.add(delegate.name)
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .setDelegate(delegate)
                    .build()
                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumFaces(maxFaces)
                    .setMinFaceDetectionConfidence(AIStrictHighEndConfig.FACE_DETECTION_CONFIDENCE)
                    .setMinFacePresenceConfidence(AIStrictHighEndConfig.FACE_PRESENCE_CONFIDENCE)
                    .setMinTrackingConfidence(AIStrictHighEndConfig.FACE_TRACKING_CONFIDENCE)
                    .setOutputFaceBlendshapes(true) // Enable blendshapes for emotion detection
                    .build()
                Log.i(TAG, "Creating FaceLandmarker with delegate=$delegate")
                
                _status.value = EngineStatus.Idle()
                return FaceLandmarker.createFromOptions(context, options)
            } catch (throwable: Throwable) {
                lastError = throwable
                Log.w(TAG, "FaceLandmarker init failed for delegate=$delegate", throwable)
            }
        }
        
        _status.value = EngineStatus.InitializationFailed(
            message = "Unable to initialize FaceLandmarker",
            throwable = lastError ?: Exception("Unknown error"),
            attemptedDelegates = attemptedDelegates
        )
        throw IllegalStateException("Unable to initialize FaceLandmarker", lastError)
    }

    override fun close() {
        runCatching { faceLandmarker.close() }
            .onFailure { Log.w(TAG, "FaceLandmarker close failed", it) }
        trackingStates.clear()
        faceKalmanStates.clear()
        faceAttributes.clear()
        _status.value = EngineStatus.Idle()
    }

    private data class RawFaceDetection(
        val boundingBox: RectF,
        val leftEye: EyePoint?,
        val rightEye: EyePoint?,
        val confidence: Float,
        val headPoseYaw: Float = 0f,
        val headPosePitch: Float = 0f,
        val headPoseRoll: Float = 0f,
        val isSmiling: Boolean = false,
        val estimatedAge: Float = -1f,
        val occlusionLevel: Float = 0f,
        val faceDepth: Float = 0f,
        val meshConfidence: Float = 1f,
        val gazeConfidence: Float = 1f,
        val headRotationMatrix: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    )
}
