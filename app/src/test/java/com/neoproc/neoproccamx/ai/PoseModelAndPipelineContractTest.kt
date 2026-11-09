package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PoseModelAndPipelineContractTest {

    @Test
    fun poseModelsCarryKineticAndTorsoTrackingState() {
        val flexion = JointFlexionState(
            jointName = "LEFT_ELBOW",
            jointIndex = 13,
            angleDegrees = 92f,
            angularVelocityDegPerSec = 35f,
            confidence = 0.93f
        )
        val landmark = PoseLandmarkPoint(
            x = 0.25f,
            y = 0.5f,
            z = -0.1f,
            visibility = 0.9f,
            velocityX = 0.01f,
            velocityY = -0.02f,
            velocityZ = 0.03f,
            flexionDegrees = 92f,
            angularVelocityDegPerSec = 35f,
            kineticConfidence = 0.88f
        )
        val pose = TrackedPose(
            trackingId = 4,
            boundingBox = RectF(0.2f, 0.1f, 0.6f, 0.9f),
            landmarks = listOf(landmark),
            confidence = 0.91f,
            velocityX = 0.1f,
            velocityY = 0.2f,
            timestampMs = 42L,
            velocityZ = 0.03f,
            torsoDepth = 0.21f,
            predictedZ = 0.25f,
            jointFlexions = mapOf(flexion.jointName to flexion),
            kineticEnergy = 0.12f,
            torsoStateVector = FloatArray(12) { it.toFloat() },
            skeletonStability = 0.86f,
            fallbackUsed = true
        )

        assertEquals(92f, pose.landmarks.first().flexionDegrees ?: 0f, 0.0001f)
        assertEquals(0.03f, pose.velocityZ, 0.0001f)
        assertEquals(0.21f, pose.torsoDepth, 0.0001f)
        assertEquals(35f, pose.jointFlexions.getValue("LEFT_ELBOW").angularVelocityDegPerSec, 0.0001f)
        assertEquals(12, pose.torsoStateVector?.size)
        assertTrue(pose.fallbackUsed)
    }

    @Test
    fun mediaImagePoseTrackingOwnsImageCloseAndPipelineHasDirectPath() {
        val poseTrackerSource = sourceFile(
            "app/src/main/java/com/neoproc/neoproccamx/ai/PoseTracker.kt",
            "src/main/java/com/neoproc/neoproccamx/ai/PoseTracker.kt"
        ).readText()
        val pipelineSource = sourceFile(
            "app/src/main/java/com/neoproc/neoproccamx/pipeline/AICameraPipeline.kt",
            "src/main/java/com/neoproc/neoproccamx/pipeline/AICameraPipeline.kt"
        ).readText()

        assertTrue(poseTrackerSource.contains("MediaImageBuilder(image).build()"))
        assertTrue(poseTrackerSource.contains("finally"))
        assertTrue(poseTrackerSource.contains("image.close()"))
        assertTrue(pipelineSource.contains("trackPoseMediaImageFrame"))
        assertTrue(pipelineSource.contains("poseTracker.track(image, frame.timestampMs, rotationDegrees)"))
        assertTrue(pipelineSource.contains("faceTrackingEnabled && poseTrackingEnabled"))
        assertNotNull(poseTrackerSource)
    }

    private fun sourceFile(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("Missing source file in candidates: ${candidates.joinToString()}")
}
