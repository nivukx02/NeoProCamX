package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceTrackerGeometryContractTest {

    @Test
    fun headPoseFromMesh_buildsOrthogonalRotationMatrixForNeutralFace() {
        val points = mapOf(
            1 to FaceTracker.MeshPoint3(0f, 0f, 0f),
            152 to FaceTracker.MeshPoint3(0f, 1f, 0f),
            33 to FaceTracker.MeshPoint3(-1f, 0f, 0f),
            263 to FaceTracker.MeshPoint3(1f, 0f, 0f)
        )

        val pose = FaceTracker.computeHeadPoseFromMesh(points)

        assertEquals(0f, pose.pitchDegrees, 0.0001f)
        assertEquals(0f, pose.yawDegrees, 0.0001f)
        assertEquals(0f, pose.rollDegrees, 0.0001f)
        assertEquals(1f, pose.rotationMatrix[0], 0.0001f)
        assertEquals(1f, pose.rotationMatrix[4], 0.0001f)
        assertEquals(1f, pose.rotationMatrix[8], 0.0001f)
    }

    @Test
    fun eyeGazeProjection_usesIrisDisplacementAgainstFaceBasis() {
        val points = mapOf(
            1 to FaceTracker.MeshPoint3(0f, 0f, 0f),
            152 to FaceTracker.MeshPoint3(0f, 1f, 0f),
            33 to FaceTracker.MeshPoint3(-1f, 0f, 0f),
            263 to FaceTracker.MeshPoint3(1f, 0f, 0f),
            133 to FaceTracker.MeshPoint3(-0.2f, 0f, 0f),
            159 to FaceTracker.MeshPoint3(-0.6f, -0.1f, 0f),
            145 to FaceTracker.MeshPoint3(-0.6f, 0.1f, 0f),
            468 to FaceTracker.MeshPoint3(-0.35f, 0f, 0.08f),
            469 to FaceTracker.MeshPoint3(-0.35f, 0f, 0.08f),
            470 to FaceTracker.MeshPoint3(-0.35f, 0f, 0.08f),
            471 to FaceTracker.MeshPoint3(-0.35f, 0f, 0.08f)
        )
        val pose = FaceTracker.computeHeadPoseFromMesh(points)

        val gaze = FaceTracker.computeEyeGazeProjection(points, leftEye = true, pose = pose)

        assertTrue(gaze.horizontal > 0f)
        assertTrue(gaze.depth > 0f)
        assertTrue(gaze.confidence > 0.5f)
    }

    @Test
    fun faceKalman10D_adaptiveConfidenceControlsMeasurementPull() {
        val initial = FaceTracker.initializeFaceKalman(
            centerX = 0.5f,
            centerY = 0.5f,
            depthZ = 0.25f,
            width = 0.2f,
            height = 0.3f,
            timestampMs = 1_000L
        )

        val highConfidence = FaceTracker.updateFaceKalman(
            previous = initial,
            centerX = 0.7f,
            centerY = 0.5f,
            depthZ = 0.25f,
            width = 0.2f,
            height = 0.3f,
            confidence = 0.95f,
            occlusion = 0f,
            timestampMs = 1_033L
        )
        val lowConfidence = FaceTracker.updateFaceKalman(
            previous = initial,
            centerX = 0.7f,
            centerY = 0.5f,
            depthZ = 0.25f,
            width = 0.2f,
            height = 0.3f,
            confidence = 0.15f,
            occlusion = 0.6f,
            timestampMs = 1_033L
        )

        assertEquals(10, highConfidence.state.size)
        assertEquals(100, highConfidence.covariance.size)
        assertTrue(highConfidence.state[0] > lowConfidence.state[0])
    }
}
