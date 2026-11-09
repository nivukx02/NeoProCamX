package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FocusEngineSubjectAwareInstrumentedTest {

    @Test
    fun testMediaPipeIrisFocusTransition() {
        val engine = FocusEngine()
        val previousFace = TrackedFace(
            trackingId = 1,
            boundingBox = RectF(0.1f, 0.1f, 0.3f, 0.3f),
            leftEye = EyePoint(0.15f, 0.15f, 1f),
            rightEye = EyePoint(0.25f, 0.15f, 1f),
            confidence = 0.95f,
            velocityX = 0f,
            velocityY = 0f,
            timestampMs = 100L
        )
        
        // Initial focus on first person
        val result1 = engine.selectSubjectAwareness(
            faces = listOf(previousFace),
            pose = null,
            segmentation = null,
            depthData = null,
            sceneType = SceneType.GENERAL,
            timestampMs = 100L
        )
        
        val newFace = TrackedFace(
            trackingId = 2,
            boundingBox = RectF(0.6f, 0.6f, 0.8f, 0.8f),
            leftEye = EyePoint(0.65f, 0.65f, 1f), // Simulated MediaPipe Iris
            rightEye = EyePoint(0.75f, 0.65f, 1f),
            confidence = 0.98f,
            velocityX = 0f,
            velocityY = 0f,
            timestampMs = 200L
        )
        
        // Transition focus to second person
        val result2 = engine.selectSubjectAwareness(
            faces = listOf(newFace),
            pose = null,
            segmentation = null,
            depthData = null,
            sceneType = SceneType.GENERAL,
            timestampMs = 200L
        )
        
        // Assert cinematic focus transition is working (interpolating)
        val focusPoint = requireNotNull(result2.focusPoint)
        val fx = focusPoint.focusX
        val fy = focusPoint.focusY
        
        // It shouldn't jump immediately to 0.7f, it should blend
        assertTrue("Focus should transition smoothly, X=$fx", fx > 0.2f && fx < 0.7f)
        assertTrue("Focus should transition smoothly, Y=$fy", fy > 0.2f && fy < 0.7f)
    }

    @Test
    fun prefersHumanEyesWhenHumanDetected() {
        val engine = FocusEngine()
        val face = TrackedFace(
            trackingId = 7,
            boundingBox = RectF(0.25f, 0.20f, 0.58f, 0.68f),
            leftEye = EyePoint(0.34f, 0.34f, 1f),
            rightEye = EyePoint(0.47f, 0.34f, 1f),
            confidence = 0.95f,
            velocityX = 0f,
            velocityY = 0f,
            timestampMs = 100L
        )
        val depth = DepthData(
            mapWidth = 4,
            mapHeight = 4,
            normalizedDepth = floatArrayOf(
                0.1f, 0.2f, 0.3f, 0.4f,
                0.2f, 0.9f, 0.8f, 0.4f,
                0.2f, 0.8f, 0.7f, 0.3f,
                0.1f, 0.2f, 0.2f, 0.1f
            )
        )

        val result = engine.selectSubjectAwareness(
            faces = listOf(face),
            pose = null,
            segmentation = null,
            depthData = depth,
            sceneType = SceneType.GENERAL,
            timestampMs = 100L
        )

        assertNotNull(result.focusPoint)
        assertEquals(SubjectType.EYE, result.focusPoint?.subjectType)
        assertEquals(7, result.primarySubject?.trackingId)
        assertEquals(SubjectCategory.HUMAN, result.primarySubject?.category)
    }

    @Test
    fun prefersPoseBodyWhenNoFaceDetected() {
        val engine = FocusEngine()
        val pose = TrackedPose(
            trackingId = 10,
            boundingBox = RectF(0.18f, 0.20f, 0.45f, 0.72f),
            landmarks = listOf(
                PoseLandmarkPoint(0.24f, 0.24f, 0f, 1f),
                PoseLandmarkPoint(0.34f, 0.50f, 0f, 1f)
            ),
            confidence = 0.87f,
            velocityX = 0f,
            velocityY = 0f,
            timestampMs = 200L,
            poseType = PoseType.STANDING,
            skeletonValid = true
        )
        val depth = DepthData(
            mapWidth = 4,
            mapHeight = 4,
            normalizedDepth = floatArrayOf(
                0.2f, 0.8f, 0.2f, 0.1f,
                0.3f, 0.9f, 0.2f, 0.1f,
                0.2f, 0.8f, 0.2f, 0.1f,
                0.1f, 0.2f, 0.1f, 0.1f
            )
        )

        val result = engine.selectSubjectAwareness(
            faces = emptyList(),
            pose = pose,
            segmentation = null,
            depthData = depth,
            sceneType = SceneType.GENERAL,
            timestampMs = 200L
        )

        assertEquals(10, result.primarySubject?.trackingId)
        assertEquals(SubjectType.UPPER_BODY, result.focusPoint?.subjectType)
    }

    @Test
    fun buildsSubjectMaskForPrimarySubject() {
        val engine = FocusEngine()
        val face = TrackedFace(
            trackingId = 1,
            boundingBox = RectF(0.20f, 0.18f, 0.56f, 0.82f),
            leftEye = EyePoint(0.30f, 0.34f, 1f),
            rightEye = EyePoint(0.44f, 0.34f, 1f),
            confidence = 0.92f,
            velocityX = 0f,
            velocityY = 0f,
            timestampMs = 300L
        )
        val segmentation = SegmentationData(
            maskWidth = 4,
            maskHeight = 4,
            foregroundMask = byteArrayOf(
                0, 40, 30, 0,
                10, -1, -1, 20,
                10, -1, -1, 20,
                0, 40, 35, 0
            ),
            approximateDepth = floatArrayOf(
                0.1f, 0.3f, 0.3f, 0.1f,
                0.2f, 0.9f, 0.9f, 0.2f,
                0.2f, 0.9f, 0.9f, 0.2f,
                0.1f, 0.3f, 0.3f, 0.1f
            ),
            foregroundCoverage = 0.55f,
            subjectDistanceScore = 0.85f
        )
        val depth = DepthData(
            mapWidth = 4,
            mapHeight = 4,
            normalizedDepth = floatArrayOf(
                0.1f, 0.3f, 0.3f, 0.1f,
                0.2f, 0.9f, 0.9f, 0.2f,
                0.2f, 0.9f, 0.9f, 0.2f,
                0.1f, 0.3f, 0.3f, 0.1f
            )
        )

        val result = engine.selectSubjectAwareness(
            faces = listOf(face),
            pose = null,
            segmentation = segmentation,
            depthData = depth,
            sceneType = SceneType.BACKLIGHT,
            timestampMs = 300L
        )

        assertNotNull(result.subjectMask)
        assertTrue(result.hdrSuggested)
        assertTrue(result.subjectMask!!.alphaMask.any { (it.toInt() and 0xFF) > 0 })
    }
}
