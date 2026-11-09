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
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * HIGH-END POSE TRACKER - PROFESSIONAL cinema-oriented DETECTION (UPGRADED)
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * FEATURES:
 * - Skeleton validity checking
 * - Pose type classification (STANDING, RUNNING, etc.)
 * - Motion pattern recognition
 * - Gesture detection hooks
 * - 3D pose estimation improvements
 * - Temporal pose smoothing
 * - Professional status reporting
 *
 * PERFORMANCE TARGET: 30+ FPS
 */
class PoseTracker(context: Context) : AutoCloseable {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    data class PosePoint3(
        val x: Float,
        val y: Float,
        val z: Float,
        val visibility: Float = 1f
    )

    data class TorsoKalmanState12(
        val state: FloatArray,
        val covariance: FloatArray
    )

    data class SilhouetteFallbackResult(
        val centerX: Float,
        val centerY: Float,
        val bounds: RectF,
        val coverage: Float,
        val foregroundMask: ByteArray
    )

    companion object {
        private const val TAG = "PoseTracker"
        private const val MODEL_ASSET = "models/pose_landmarker_full.task"
        private const val TRACK_ID = 1
        
        // MediaPipe Pose landmark indices (33 landmarks)
        private const val NOSE = 0
        private const val LEFT_SHOULDER = 11
        private const val RIGHT_SHOULDER = 12
        private const val LEFT_ELBOW = 13
        private const val RIGHT_ELBOW = 14
        private const val LEFT_WRIST = 15
        private const val RIGHT_WRIST = 16
        private const val LEFT_HIP = 23
        private const val RIGHT_HIP = 24
        private const val LEFT_KNEE = 25
        private const val RIGHT_KNEE = 26
        private const val LEFT_ANKLE = 27
        private const val RIGHT_ANKLE = 28
        
        // Thresholds
        private const val MIN_LANDMARK_VISIBILITY = 0.2f
        private const val SKELETON_VALIDITY_THRESHOLD = 0.6f
        private const val POSE_SMOOTHING_ALPHA = 0.3f
        private const val TORSO_STATE_SIZE = 12
        private const val TORSO_MEASUREMENT_SIZE = 5
        private const val TORSO_VELOCITY_DAMPING = 0.94f
        private const val TORSO_BASE_MEASUREMENT_VARIANCE = 1.0e-4f

        fun computeJointFlexionDegrees(
            a: PosePoint3,
            b: PosePoint3,
            c: PosePoint3
        ): Float {
            val ux = a.x - b.x
            val uy = a.y - b.y
            val uz = a.z - b.z
            val vx = c.x - b.x
            val vy = c.y - b.y
            val vz = c.z - b.z
            val uLen = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(1.0e-6f)
            val vLen = sqrt(vx * vx + vy * vy + vz * vz).coerceAtLeast(1.0e-6f)
            val dot = ((ux * vx + uy * vy + uz * vz) / (uLen * vLen)).coerceIn(-1f, 1f)
            return (acos(dot) * 57.29578f).coerceIn(0f, 180f)
        }

        fun computeJointFlexionSet(
            points: Map<Int, PosePoint3>,
            previousAngles: Map<String, Float> = emptyMap(),
            deltaTimeSeconds: Float = 1f / 30f
        ): Map<String, JointFlexionState> {
            val definitions = listOf(
                JointDefinition("LEFT_ELBOW", LEFT_SHOULDER, LEFT_ELBOW, LEFT_WRIST),
                JointDefinition("RIGHT_ELBOW", RIGHT_SHOULDER, RIGHT_ELBOW, RIGHT_WRIST),
                JointDefinition("LEFT_KNEE", LEFT_HIP, LEFT_KNEE, LEFT_ANKLE),
                JointDefinition("RIGHT_KNEE", RIGHT_HIP, RIGHT_KNEE, RIGHT_ANKLE),
                JointDefinition("LEFT_HIP", LEFT_SHOULDER, LEFT_HIP, LEFT_KNEE),
                JointDefinition("RIGHT_HIP", RIGHT_SHOULDER, RIGHT_HIP, RIGHT_KNEE)
            )
            val dt = deltaTimeSeconds.coerceAtLeast(1.0e-4f)
            return definitions.mapNotNull { definition ->
                val a = points[definition.start] ?: return@mapNotNull null
                val b = points[definition.joint] ?: return@mapNotNull null
                val c = points[definition.end] ?: return@mapNotNull null
                val angle = computeJointFlexionDegrees(a, b, c)
                val previous = previousAngles[definition.name] ?: angle
                JointFlexionState(
                    jointName = definition.name,
                    jointIndex = definition.joint,
                    angleDegrees = angle,
                    angularVelocityDegPerSec = (angle - previous) / dt,
                    confidence = ((a.visibility + b.visibility + c.visibility) / 3f).coerceIn(0f, 1f)
                )
            }.associateBy { it.jointName }
        }

        fun initializeTorsoKalman(measurement: FloatArray): TorsoKalmanState12 {
            require(measurement.size >= TORSO_MEASUREMENT_SIZE) {
                "Torso measurement must contain xc, yc, zc, width, and height"
            }
            val state = FloatArray(TORSO_STATE_SIZE)
            for (i in 0 until TORSO_MEASUREMENT_SIZE) {
                state[i] = measurement[i]
            }
            val covariance = FloatArray(TORSO_STATE_SIZE * TORSO_STATE_SIZE)
            for (i in 0 until TORSO_STATE_SIZE) {
                covariance[i * TORSO_STATE_SIZE + i] = if (i < TORSO_MEASUREMENT_SIZE) 0.05f else 0.25f
            }
            return TorsoKalmanState12(state, covariance)
        }

        fun updateTorsoKalman(
            previous: TorsoKalmanState12,
            measurement: FloatArray,
            deltaTimeSeconds: Float,
            visibility: Float
        ): TorsoKalmanState12 {
            require(previous.state.size == TORSO_STATE_SIZE) { "Torso state must be 12D" }
            require(previous.covariance.size == TORSO_STATE_SIZE * TORSO_STATE_SIZE) {
                "Torso covariance must be 12x12"
            }
            require(measurement.size >= TORSO_MEASUREMENT_SIZE) {
                "Torso measurement must contain xc, yc, zc, width, and height"
            }

            val dt = deltaTimeSeconds.coerceIn(1.0e-4f, 0.25f)
            val dt2 = dt * dt
            val predicted = previous.state.copyOf()
            predicted[0] += predicted[5] * dt + 0.5f * predicted[10] * dt2
            predicted[1] += predicted[6] * dt + 0.5f * predicted[11] * dt2
            predicted[2] += predicted[7] * dt
            predicted[3] += predicted[8] * dt
            predicted[4] += predicted[9] * dt
            predicted[5] = predicted[5] * TORSO_VELOCITY_DAMPING + predicted[10] * dt
            predicted[6] = predicted[6] * TORSO_VELOCITY_DAMPING + predicted[11] * dt
            predicted[7] *= TORSO_VELOCITY_DAMPING
            predicted[8] *= TORSO_VELOCITY_DAMPING
            predicted[9] *= TORSO_VELOCITY_DAMPING
            predicted[10] *= TORSO_VELOCITY_DAMPING * TORSO_VELOCITY_DAMPING
            predicted[11] *= TORSO_VELOCITY_DAMPING * TORSO_VELOCITY_DAMPING

            val covariance = previous.covariance.copyOf()
            for (i in 0 until TORSO_STATE_SIZE) {
                val diagonal = i * TORSO_STATE_SIZE + i
                covariance[diagonal] = (covariance[diagonal] + if (i < TORSO_MEASUREMENT_SIZE) 0.002f else 0.01f)
                    .coerceAtMost(10f)
            }

            val measurementVariance = TORSO_BASE_MEASUREMENT_VARIANCE /
                visibility.coerceAtLeast(1.0e-4f)
            val velocityIndices = intArrayOf(5, 6, 7, 8, 9)
            val updated = predicted.copyOf()
            for (i in 0 until TORSO_MEASUREMENT_SIZE) {
                val diagonal = i * TORSO_STATE_SIZE + i
                val p = covariance[diagonal].coerceAtLeast(1.0e-6f)
                val gain = p / (p + measurementVariance)
                val before = updated[i]
                updated[i] += (measurement[i] - updated[i]) * gain
                covariance[diagonal] = (1f - gain) * p
                val measuredVelocity = (updated[i] - previous.state[i]) / dt
                val velocityIndex = velocityIndices[i]
                updated[velocityIndex] = updated[velocityIndex] * (1f - gain) + measuredVelocity * gain
                if (i == 0 || i == 1) {
                    val accelerationIndex = if (i == 0) 10 else 11
                    updated[accelerationIndex] = (updated[velocityIndex] - previous.state[velocityIndex]) / dt
                }
                if (updated[i].isNaN()) updated[i] = before
            }
            return TorsoKalmanState12(updated, covariance)
        }

        fun extractSilhouetteFallback(
            currentLuma: FloatArray,
            width: Int,
            height: Int,
            previousBackground: FloatArray? = null,
            threshold: Float = 18f / 255f
        ): SilhouetteFallbackResult {
            if (width <= 0 || height <= 0 || currentLuma.size < width * height) {
                return SilhouetteFallbackResult(0.5f, 0.5f, rectF(0f, 0f, 0f, 0f), 0f, ByteArray(0))
            }
            val pixelCount = width * height
            var sumLuma = 0f
            for (i in 0 until pixelCount) {
                sumLuma += currentLuma[i]
            }
            val fallbackMean = sumLuma / pixelCount
            val mask = ByteArray(pixelCount)
            val verticalProjection = IntArray(width)
            val horizontalProjection = IntArray(height)
            var foregroundCount = 0
            var sumX = 0f
            var sumY = 0f
            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    val index = rowOffset + x
                    val background = previousBackground?.getOrNull(index) ?: fallbackMean
                    val isForeground = abs(currentLuma[index] - background) > threshold
                    if (isForeground) {
                        mask[index] = 0xFF.toByte()
                        verticalProjection[x]++
                        horizontalProjection[y]++
                        foregroundCount++
                        sumX += x
                        sumY += y
                    }
                }
            }
            if (foregroundCount == 0) {
                return SilhouetteFallbackResult(0.5f, 0.5f, rectF(0f, 0f, 0f, 0f), 0f, mask)
            }

            val xGate = (verticalProjection.maxOrNull() ?: 0) * 0.10f
            val yGate = (horizontalProjection.maxOrNull() ?: 0) * 0.10f
            val left = verticalProjection.indexOfFirst { it >= xGate }.takeIf { it >= 0 } ?: 0
            val right = verticalProjection.indexOfLast { it >= xGate }.takeIf { it >= 0 } ?: (width - 1)
            val top = horizontalProjection.indexOfFirst { it >= yGate }.takeIf { it >= 0 } ?: 0
            val bottom = horizontalProjection.indexOfLast { it >= yGate }.takeIf { it >= 0 } ?: (height - 1)
            val bounds = rectF(
                left.toFloat() / width,
                top.toFloat() / height,
                (right + 1f) / width,
                (bottom + 1f) / height
            )
            return SilhouetteFallbackResult(
                centerX = (sumX / foregroundCount) / width,
                centerY = (sumY / foregroundCount) / height,
                bounds = bounds,
                coverage = foregroundCount.toFloat() / pixelCount,
                foregroundMask = mask
            )
        }

        private data class JointDefinition(
            val name: String,
            val start: Int,
            val joint: Int,
            val end: Int
        )

        private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF =
            RectF().apply {
                this.left = left
                this.top = top
                this.right = right
                this.bottom = bottom
            }
    }

    // Engine status tracking
    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()
    
    private val poseLandmarker = createPoseLandmarker(context)
    private var motionState: MotionState? = null
    private var previousLandmarks: List<PoseLandmarkPoint>? = null
    private var previousTimestampMs: Long? = null
    private var previousJointAngles: Map<String, Float> = emptyMap()
    private var torsoKalmanState: TorsoKalmanState12? = null
    private var frameCount = 0L
    private var totalProcessingTimeMs = 0L

    private fun minLandmarkVisibility(): Float =
        if (strictHighEndEnabled()) MIN_LANDMARK_VISIBILITY - 0.03f else MIN_LANDMARK_VISIBILITY

    private fun skeletonValidityThreshold(): Float =
        if (strictHighEndEnabled()) SKELETON_VALIDITY_THRESHOLD - 0.05f else SKELETON_VALIDITY_THRESHOLD

    private fun poseSmoothingAlpha(): Float =
        if (strictHighEndEnabled()) (POSE_SMOOTHING_ALPHA + 0.05f).coerceAtMost(0.5f) else POSE_SMOOTHING_ALPHA

    fun track(
        bitmap: Bitmap,
        timestampMs: Long,
        rotationDegrees: Int
    ): TrackedPose? {
        val startTime = System.currentTimeMillis()
        _status.value = EngineStatus.Processing("pose detection", startTime)
        
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = trackMpImage(mpImage, timestampMs, rotationDegrees)
            
            val processingTime = System.currentTimeMillis() - startTime
            totalProcessingTimeMs += processingTime
            frameCount++
            
            _status.value = EngineStatus.Success(
                metadata = if (result != null) "Pose detected" else "No pose",
                processingTimeMs = processingTime,
                throughputFps = if (processingTime > 0) 1000f / processingTime else 0f,
                qualityScore = result?.confidence ?: 0f
            )
            
            result
        } catch (throwable: Throwable) {
            Log.e(TAG, "Pose tracking failed", throwable)
            _status.value = EngineStatus.Failure(
                message = "Pose tracking error: ${throwable.message}",
                throwable = throwable,
                canRetry = true,
                failedOperation = "pose_detection"
            )
            null
        }
    }

    fun track(
        image: Image,
        timestampMs: Long,
        rotationDegrees: Int
    ): TrackedPose? {
        val startTime = System.currentTimeMillis()
        _status.value = EngineStatus.Processing("pose detection", startTime)
        
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
            Log.e(TAG, "Pose tracking failed", throwable)
            _status.value = EngineStatus.Failure(
                message = "Pose tracking error",
                throwable = throwable,
                canRetry = true
            )
            null
        } finally {
            runCatching { image.close() }
                .onFailure { Log.w(TAG, "Pose image close failed", it) }
        }
    }

    private fun trackMpImage(
        mpImage: MPImage,
        timestampMs: Long,
        rotationDegrees: Int
    ): TrackedPose? {
        val result = poseLandmarker.detectForVideo(mpImage, timestampMs)
        val landmarks = result.landmarks().firstOrNull() ?: return null
        if (landmarks.isEmpty()) return null

        val rotated = landmarks.map { landmark ->
            val point = rotatePoint(landmark.x(), landmark.y(), rotationDegrees)
            PoseLandmarkPoint(
                x = point.x,
                y = point.y,
                z = landmark.z(),
                visibility = landmark.visibility().orElse(0f)
            )
        }
        
        val visiblePoints = rotated.filter { it.visibility >= minLandmarkVisibility() }
        if (visiblePoints.isEmpty()) return null

        val boundingBox = rectF(
            visiblePoints.minOf { it.x }.coerceIn(0f, 1f),
            visiblePoints.minOf { it.y }.coerceIn(0f, 1f),
            visiblePoints.maxOf { it.x }.coerceIn(0f, 1f),
            visiblePoints.maxOf { it.y }.coerceIn(0f, 1f)
        )
        
        val averageConfidence = visiblePoints.map { it.visibility }.average().toFloat().coerceIn(0f, 1f)
        
        val deltaTimeSeconds = previousTimestampMs
            ?.let { ((timestampMs - it).coerceAtLeast(1L) / 1000f).coerceIn(1f / 120f, 0.25f) }
            ?: (1f / 30f)
        val previousForVelocity = previousLandmarks

        // Apply temporal smoothing
        val smoothedLandmarks = applyTemporalSmoothing(rotated, previousForVelocity)
        val pointMap = smoothedLandmarks.mapIndexed { index, landmark ->
            index to PosePoint3(landmark.x, landmark.y, landmark.z, landmark.visibility)
        }.toMap()
        val jointFlexions = computeJointFlexionSet(pointMap, previousJointAngles, deltaTimeSeconds)
        val kineticLandmarks = applyKineticAnnotations(
            current = smoothedLandmarks,
            previous = previousForVelocity,
            jointFlexions = jointFlexions,
            deltaTimeSeconds = deltaTimeSeconds
        )
        previousLandmarks = kineticLandmarks
        previousTimestampMs = timestampMs
        previousJointAngles = jointFlexions.mapValues { it.value.angleDegrees }
        
        // Check skeleton validity
        val skeletonValid = checkSkeletonValidity(kineticLandmarks)
        
        // Classify pose type
        val poseType = classifyPoseType(kineticLandmarks)
        
        // Update motion state
        motionState = TrackingMath.updateMotionState(
            current = motionState,
            centerX = boundingBox.centerX(),
            centerY = boundingBox.centerY(),
            confidence = averageConfidence,
            nowMs = timestampMs,
            smoothing = if (strictHighEndEnabled()) 0.40f else 0.36f
        )
        
        val predicted = TrackingMath.predictCenter(
            motionState ?: return null,
            if (strictHighEndEnabled()) 110L else 90L
        )
        val halfWidth = boundingBox.width() / 2f
        val halfHeight = boundingBox.height() / 2f
        
        // Classify motion pattern
        val motionPattern = classifyMotionPattern(motionState ?: return null)
        motionState?.motionPattern = motionPattern
        val torsoMeasurement = calculateTorsoMeasurement(kineticLandmarks, boundingBox)
        val updatedTorso = updateTorsoKalman(
            previous = torsoKalmanState ?: initializeTorsoKalman(torsoMeasurement),
            measurement = torsoMeasurement,
            deltaTimeSeconds = deltaTimeSeconds,
            visibility = averageConfidence
        )
        torsoKalmanState = updatedTorso
        val kineticEnergy = calculateKineticEnergy(jointFlexions, motionState)

        return TrackedPose(
            trackingId = TRACK_ID,
            boundingBox = rectF(
                (predicted.first - halfWidth).coerceIn(0f, 1f),
                (predicted.second - halfHeight).coerceIn(0f, 1f),
                (predicted.first + halfWidth).coerceIn(0f, 1f),
                (predicted.second + halfHeight).coerceIn(0f, 1f)
            ),
            landmarks = kineticLandmarks,
            confidence = averageConfidence,
            velocityX = motionState?.velocityX ?: 0f,
            velocityY = motionState?.velocityY ?: 0f,
            timestampMs = timestampMs,
            accelerationX = motionState?.accelerationX ?: 0f,
            accelerationY = motionState?.accelerationY ?: 0f,
            motionPattern = motionPattern,
            poseType = poseType,
            skeletonValid = skeletonValid,
            velocityZ = updatedTorso.state[7],
            torsoDepth = updatedTorso.state[2],
            predictedZ = updatedTorso.state[2] + updatedTorso.state[7] * 0.10f,
            jointFlexions = jointFlexions,
            kineticEnergy = kineticEnergy,
            torsoStateVector = updatedTorso.state.copyOf(),
            skeletonStability = averageConfidence * if (skeletonValid) 1f else 0.65f
        )
    }

    private fun applyKineticAnnotations(
        current: List<PoseLandmarkPoint>,
        previous: List<PoseLandmarkPoint>?,
        jointFlexions: Map<String, JointFlexionState>,
        deltaTimeSeconds: Float
    ): List<PoseLandmarkPoint> {
        val dt = deltaTimeSeconds.coerceAtLeast(1.0e-4f)
        val flexionByJoint = jointFlexions.values.associateBy { it.jointIndex }
        return current.mapIndexed { index, landmark ->
            val previousLandmark = previous?.getOrNull(index)
            val flexion = flexionByJoint[index]
            landmark.copy(
                velocityX = previousLandmark?.let { (landmark.x - it.x) / dt } ?: 0f,
                velocityY = previousLandmark?.let { (landmark.y - it.y) / dt } ?: 0f,
                velocityZ = previousLandmark?.let { (landmark.z - it.z) / dt } ?: 0f,
                flexionDegrees = flexion?.angleDegrees,
                angularVelocityDegPerSec = flexion?.angularVelocityDegPerSec ?: 0f,
                kineticConfidence = flexion?.confidence ?: landmark.confidence
            )
        }
    }

    private fun calculateTorsoMeasurement(
        landmarks: List<PoseLandmarkPoint>,
        boundingBox: RectF
    ): FloatArray {
        val leftShoulder = landmarks.getOrNull(LEFT_SHOULDER)
        val rightShoulder = landmarks.getOrNull(RIGHT_SHOULDER)
        val leftHip = landmarks.getOrNull(LEFT_HIP)
        val rightHip = landmarks.getOrNull(RIGHT_HIP)
        val torsoPoints = listOfNotNull(leftShoulder, rightShoulder, leftHip, rightHip)
        if (torsoPoints.isEmpty()) {
            return floatArrayOf(
                boundingBox.centerX(),
                boundingBox.centerY(),
                0f,
                boundingBox.width(),
                boundingBox.height()
            )
        }
        val centerX = torsoPoints.map { it.x }.average().toFloat()
        val centerY = torsoPoints.map { it.y }.average().toFloat()
        val centerZ = torsoPoints.map { it.z }.average().toFloat()
        val shoulderWidth = if (leftShoulder != null && rightShoulder != null) {
            distance2d(leftShoulder, rightShoulder)
        } else {
            boundingBox.width()
        }
        val hipWidth = if (leftHip != null && rightHip != null) {
            distance2d(leftHip, rightHip)
        } else {
            boundingBox.width()
        }
        val torsoHeight = if (leftShoulder != null && leftHip != null && rightShoulder != null && rightHip != null) {
            (distance2d(leftShoulder, leftHip) + distance2d(rightShoulder, rightHip)) * 0.5f
        } else {
            boundingBox.height()
        }
        return floatArrayOf(
            centerX.coerceIn(0f, 1f),
            centerY.coerceIn(0f, 1f),
            centerZ,
            shoulderWidth.coerceAtLeast(hipWidth).coerceIn(0f, 1f),
            torsoHeight.coerceIn(0f, 1f)
        )
    }

    private fun distance2d(a: PoseLandmarkPoint, b: PoseLandmarkPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun calculateKineticEnergy(
        flexions: Map<String, JointFlexionState>,
        state: MotionState?
    ): Float {
        val angularEnergy = flexions.values
            .map { abs(it.angularVelocityDegPerSec) / 720f }
            .sum()
        val translationEnergy = state?.let {
            sqrt(it.velocityX * it.velocityX + it.velocityY * it.velocityY)
        } ?: 0f
        return (angularEnergy + translationEnergy).coerceIn(0f, 1f)
    }
    
    /**
     * Apply temporal smoothing to landmarks for stable pose
     */
    private fun applyTemporalSmoothing(
        current: List<PoseLandmarkPoint>,
        previous: List<PoseLandmarkPoint>?
    ): List<PoseLandmarkPoint> {
        if (previous == null || previous.size != current.size) return current
        
        return current.mapIndexed { index, landmark ->
            val prev = previous[index]
            val smoothingAlpha = poseSmoothingAlpha()
            landmark.copy(
                x = landmark.x * smoothingAlpha + prev.x * (1f - smoothingAlpha),
                y = landmark.y * smoothingAlpha + prev.y * (1f - smoothingAlpha),
                z = landmark.z * smoothingAlpha + prev.z * (1f - smoothingAlpha)
            )
        }
    }
    
    /**
     * Check if skeleton connections are valid
     */
    private fun checkSkeletonValidity(landmarks: List<PoseLandmarkPoint>): Boolean {
        if (landmarks.size < 33) return false
        
        // Check key landmark pairs are visible and connected
        val keyPairs = listOf(
            LEFT_SHOULDER to RIGHT_SHOULDER,
            LEFT_SHOULDER to LEFT_ELBOW,
            RIGHT_SHOULDER to RIGHT_ELBOW,
            LEFT_HIP to RIGHT_HIP,
            LEFT_HIP to LEFT_KNEE,
            RIGHT_HIP to RIGHT_KNEE,
            LEFT_KNEE to LEFT_ANKLE,
            RIGHT_KNEE to RIGHT_ANKLE
        )
        
        var validConnections = 0
        keyPairs.forEach { (idx1, idx2) ->
            if (idx1 < landmarks.size && idx2 < landmarks.size) {
                val p1 = landmarks[idx1]
                val p2 = landmarks[idx2]
                if (p1.visibility >= minLandmarkVisibility() &&
                    p2.visibility >= minLandmarkVisibility()) {
                    validConnections++
                }
            }
        }
        
        return (validConnections.toFloat() / keyPairs.size) >= skeletonValidityThreshold()
    }
    
    /**
     * Classify pose type based on landmark positions
     */
    private fun classifyPoseType(landmarks: List<PoseLandmarkPoint>): PoseType {
        if (landmarks.size < 33) return PoseType.UNKNOWN
        
        val nose = landmarks.getOrNull(NOSE) ?: return PoseType.UNKNOWN
        val leftHip = landmarks.getOrNull(LEFT_HIP) ?: return PoseType.UNKNOWN
        val rightHip = landmarks.getOrNull(RIGHT_HIP) ?: return PoseType.UNKNOWN
        val leftKnee = landmarks.getOrNull(LEFT_KNEE) ?: return PoseType.UNKNOWN
        val rightKnee = landmarks.getOrNull(RIGHT_KNEE) ?: return PoseType.UNKNOWN
        val leftAnkle = landmarks.getOrNull(LEFT_ANKLE) ?: return PoseType.UNKNOWN
        val rightAnkle = landmarks.getOrNull(RIGHT_ANKLE) ?: return PoseType.UNKNOWN
        
        val hipMidY = (leftHip.y + rightHip.y) / 2f
        val kneeMidY = (leftKnee.y + rightKnee.y) / 2f
        val ankleMidY = (leftAnkle.y + rightAnkle.y) / 2f
        
        // Vertical alignment check
        val torsoLength = abs(nose.y - hipMidY)
        val legLength = abs(hipMidY - ankleMidY)
        
        // Knee bend check
        val leftKneeBend = abs(leftHip.y - leftKnee.y) / abs(leftKnee.y - leftAnkle.y).coerceAtLeast(0.001f)
        val rightKneeBend = abs(rightHip.y - rightKnee.y) / abs(rightKnee.y - rightAnkle.y).coerceAtLeast(0.001f)
        val avgKneeBend = (leftKneeBend + rightKneeBend) / 2f
        
        // Classify based on posture
        return when {
            // Lying down: nose and hip at similar height
            abs(nose.y - hipMidY) < torsoLength * 0.4f -> PoseType.LYING
            
            // Sitting: knees significantly bent, hip higher than average of knees
            avgKneeBend < 0.6f && hipMidY < kneeMidY -> PoseType.SITTING
            
            // Running: significant knee bend variation, likely in motion
            abs(leftKneeBend - rightKneeBend) > 0.5f && motionState?.let { 
                sqrt(it.velocityX * it.velocityX + it.velocityY * it.velocityY) > 0.2f 
            } == true -> PoseType.RUNNING
            
            // Walking: moderate movement, legs alternating
            motionState?.let { 
                val speed = sqrt(it.velocityX * it.velocityX + it.velocityY * it.velocityY)
                speed > 0.08f && speed < 0.2f 
            } == true -> PoseType.WALKING
            
            // Jumping: legs extended, likely airborne
            legLength > torsoLength * 1.3f && avgKneeBend > 0.9f -> PoseType.JUMPING
            
            // Standing: upright posture, minimal knee bend
            hipMidY < ankleMidY && avgKneeBend > 0.7f -> PoseType.STANDING
            
            else -> PoseType.UNKNOWN
        }
    }
    
    /**
     * Classify motion pattern
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

    private fun rotatePoint(x: Float, y: Float, rotationDegrees: Int): PointF {
        return when (((rotationDegrees % 360) + 360) % 360) {
            90 -> PointF(1f - y, x)
            180 -> PointF(1f - x, 1f - y)
            270 -> PointF(y, 1f - x)
            else -> PointF(x, y)
        }
    }

    private fun createPoseLandmarker(context: Context): PoseLandmarker {
        _status.value = EngineStatus.Initializing("pose detector", 0)
        
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
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumPoses(AIStrictHighEndConfig.MAX_TRACKED_POSES)
                    .setMinPoseDetectionConfidence(AIStrictHighEndConfig.POSE_DETECTION_CONFIDENCE)
                    .setMinPosePresenceConfidence(AIStrictHighEndConfig.POSE_PRESENCE_CONFIDENCE)
                    .setMinTrackingConfidence(AIStrictHighEndConfig.POSE_TRACKING_CONFIDENCE)
                    .build()
                Log.i(TAG, "Creating PoseLandmarker with delegate=$delegate")
                
                _status.value = EngineStatus.Idle()
                return PoseLandmarker.createFromOptions(context, options)
            } catch (throwable: Throwable) {
                lastError = throwable
                Log.w(TAG, "PoseLandmarker init failed for delegate=$delegate", throwable)
            }
        }
        
        _status.value = EngineStatus.InitializationFailed(
            message = "Unable to initialize PoseLandmarker",
            throwable = lastError ?: Exception("Unknown error"),
            attemptedDelegates = attemptedDelegates
        )
        throw IllegalStateException("Unable to initialize PoseLandmarker", lastError)
    }

    override fun close() {
        runCatching { poseLandmarker.close() }
            .onFailure { Log.w(TAG, "PoseLandmarker close failed", it) }
        motionState = null
        previousLandmarks = null
        previousTimestampMs = null
        previousJointAngles = emptyMap()
        torsoKalmanState = null
        _status.value = EngineStatus.Idle()
    }
}
