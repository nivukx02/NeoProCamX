package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FaceModelAndFallbackContractTest {

    @Test
    fun faceModelCarriesSubpixelGazeDepthAndMeshConfidence() {
        val eye = EyePoint(
            x = 0.4f,
            y = 0.45f,
            confidence = 0.9f,
            subPixelX = 0.401f,
            subPixelY = 0.449f,
            depthZ = 0.12f,
            gazeVectorX = 0.2f,
            gazeVectorY = -0.1f,
            gazeVectorZ = 0.97f,
            spatialConfidence = 0.88f
        )
        val face = TrackedFace(
            trackingId = 7,
            boundingBox = RectF(0.3f, 0.2f, 0.6f, 0.7f),
            leftEye = eye,
            rightEye = null,
            confidence = 0.91f,
            velocityX = 0f,
            velocityY = 0f,
            timestampMs = 10L,
            faceDepth = 0.42f,
            predictedZ = 0.44f,
            meshConfidence = 0.93f,
            gazeConfidence = 0.88f,
            headRotationMatrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        )

        assertEquals(0.401f, face.leftEye?.subPixelX ?: 0f, 0.0001f)
        assertEquals(0.42f, face.faceDepth, 0.0001f)
        assertEquals(0.44f, face.predictedZ, 0.0001f)
        assertEquals(9, face.headRotationMatrix?.size)
    }

    @Test
    fun skinChromaFallbackExtractsCentroidAndEllipseFromSkinColoredRegion() {
        val width = 16
        val height = 12
        val argb = IntArray(width * height) { 0xff202020.toInt() }
        for (y in 4..7) {
            for (x in 6..10) {
                argb[y * width + x] = 0xffe6b4a0.toInt()
            }
        }

        val result = AIFaceDetectionEngine.extractSkinChromaFallback(argb, width, height)

        assertTrue(result.coverage > 0f)
        assertEquals(8f / width, result.centerX, 0.12f)
        assertEquals(5.5f / height, result.centerY, 0.12f)
        assertTrue(result.ellipse.majorAxis >= result.ellipse.minorAxis)
    }

    @Test
    fun mediaImageFaceTrackingOwnsImageCloseInFinallyAndPipelineUsesDirectFacePath() {
        val faceTrackerSource = sourceFile(
            "app/src/main/java/com/neoproc/neoproccamx/ai/FaceTracker.kt",
            "src/main/java/com/neoproc/neoproccamx/ai/FaceTracker.kt"
        ).readText()
        val pipelineSource = sourceFile(
            "app/src/main/java/com/neoproc/neoproccamx/pipeline/AICameraPipeline.kt",
            "src/main/java/com/neoproc/neoproccamx/pipeline/AICameraPipeline.kt"
        ).readText()

        assertTrue(faceTrackerSource.contains("MediaImageBuilder(image).build()"))
        assertTrue(faceTrackerSource.contains("finally"))
        assertTrue(faceTrackerSource.contains("image.close()"))
        assertTrue(pipelineSource.contains("trackFaceMediaImageFrame"))
        assertTrue(pipelineSource.contains("faceTracker.track(image, frame.timestampMs, rotationDegrees)"))
        assertNotNull(faceTrackerSource)
    }

    private fun sourceFile(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("Missing source file in candidates: ${candidates.joinToString()}")
}
