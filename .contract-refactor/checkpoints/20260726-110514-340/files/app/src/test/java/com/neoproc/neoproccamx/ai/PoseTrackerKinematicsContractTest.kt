package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseTrackerKinematicsContractTest {

    @Test
    fun jointFlexionUsesExactThreeDimensionalVectorAngle() {
        val shoulder = PoseTracker.PosePoint3(0f, 0f, 0f)
        val elbow = PoseTracker.PosePoint3(1f, 0f, 0f)
        val wrist = PoseTracker.PosePoint3(1f, 1f, 0f)
        val extendedWrist = PoseTracker.PosePoint3(2f, 0f, 0f)

        assertEquals(90f, PoseTracker.computeJointFlexionDegrees(shoulder, elbow, wrist), 0.0001f)
        assertEquals(180f, PoseTracker.computeJointFlexionDegrees(shoulder, elbow, extendedWrist), 0.0001f)
    }

    @Test
    fun flexionSetReportsNamedJointAnglesAndAngularVelocity() {
        val points = mutableMapOf<Int, PoseTracker.PosePoint3>()
        points[11] = PoseTracker.PosePoint3(0f, 0f, 0f)
        points[13] = PoseTracker.PosePoint3(1f, 0f, 0f)
        points[15] = PoseTracker.PosePoint3(1f, 1f, 0f)
        points[12] = PoseTracker.PosePoint3(2f, 0f, 0f)
        points[14] = PoseTracker.PosePoint3(3f, 0f, 0f)
        points[16] = PoseTracker.PosePoint3(3f, 1f, 0f)
        points[23] = PoseTracker.PosePoint3(0f, 2f, 0f)
        points[25] = PoseTracker.PosePoint3(0f, 3f, 0f)
        points[27] = PoseTracker.PosePoint3(1f, 3f, 0f)
        points[24] = PoseTracker.PosePoint3(2f, 2f, 0f)
        points[26] = PoseTracker.PosePoint3(2f, 3f, 0f)
        points[28] = PoseTracker.PosePoint3(3f, 3f, 0f)

        val flexions = PoseTracker.computeJointFlexionSet(
            points = points,
            previousAngles = mapOf("LEFT_ELBOW" to 60f),
            deltaTimeSeconds = 0.5f
        )

        assertEquals(6, flexions.size)
        assertEquals(90f, flexions.getValue("LEFT_ELBOW").angleDegrees, 0.0001f)
        assertEquals(60f, flexions.getValue("LEFT_ELBOW").angularVelocityDegPerSec, 0.0001f)
        assertTrue(flexions.getValue("LEFT_KNEE").confidence > 0.9f)
    }

    @Test
    fun torsoKalmanStateUsesTwelveDimensionsAndTrustsHighVisibilityMore() {
        val initial = PoseTracker.initializeTorsoKalman(
            measurement = floatArrayOf(0.5f, 0.5f, 0.1f, 0.2f, 0.4f)
        )

        val highVisibility = PoseTracker.updateTorsoKalman(
            previous = initial,
            measurement = floatArrayOf(0.62f, 0.5f, 0.16f, 0.24f, 0.42f),
            deltaTimeSeconds = 1f / 30f,
            visibility = 0.95f
        )
        val lowVisibility = PoseTracker.updateTorsoKalman(
            previous = initial,
            measurement = floatArrayOf(0.62f, 0.5f, 0.16f, 0.24f, 0.42f),
            deltaTimeSeconds = 1f / 30f,
            visibility = 0.08f
        )

        assertEquals(12, highVisibility.state.size)
        assertEquals(144, highVisibility.covariance.size)
        assertTrue(highVisibility.state[0] > lowVisibility.state[0])
        assertTrue(highVisibility.state[7] > 0f)
    }

    @Test
    fun projectionProfileFallbackExtractsMovingTorsoBounds() {
        val width = 20
        val height = 12
        val previous = FloatArray(width * height) { 0.1f }
        val current = previous.copyOf()
        for (y in 3..8) {
            for (x in 7..12) {
                current[y * width + x] = 0.85f
            }
        }

        val fallback = PoseTracker.extractSilhouetteFallback(
            currentLuma = current,
            width = width,
            height = height,
            previousBackground = previous
        )

        assertTrue(fallback.coverage > 0.05f)
        assertEquals(9.5f / width, fallback.centerX, 0.08f)
        assertEquals(5.5f / height, fallback.centerY, 0.08f)
        assertTrue(fallback.bounds.right - fallback.bounds.left > 0.2f)
        assertTrue(fallback.bounds.bottom - fallback.bounds.top > 0.3f)
    }
}
