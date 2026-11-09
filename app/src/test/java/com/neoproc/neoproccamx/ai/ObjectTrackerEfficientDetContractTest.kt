package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ObjectTrackerEfficientDetContractTest {

    @Test
    fun objectTrackerFeedsPackagedEfficientDetRuntimeInputSize() {
        val source = sourceFile("app/src/main/java/com/neoproc/neoproccamx/ai/ObjectTracker.kt").readText()

        assertTrue(source.contains("bitmap.scale(320, 320, true)"))
    }

    @Test
    fun parseEfficientDetTensor_filtersLowConfidenceAndClampsNormalizedBounds() {
        val output = arrayOf(
            arrayOf(
                floatArrayOf(3f, 0.91f, 0.2f, 0.1f, 0.6f, 0.5f),
                floatArrayOf(1f, 0.2f, 0.0f, 0.0f, 1.0f, 1.0f),
                floatArrayOf(44f, 0.75f, -0.4f, 0.8f, 1.4f, 1.2f)
            )
        )

        val parsed = ObjectTracker.parseEfficientDetDetections(output, rotationDegrees = 0)

        assertEquals(2, parsed.size)
        assertEquals(3, parsed[0].classId)
        assertEquals("car", parsed[0].className)
        assertEquals(0.1f, parsed[0].boundingBox.left, 0.0001f)
        assertEquals(0.2f, parsed[0].boundingBox.top, 0.0001f)
        assertEquals(0.5f, parsed[0].boundingBox.right, 0.0001f)
        assertEquals(0.6f, parsed[0].boundingBox.bottom, 0.0001f)

        assertEquals(0.8f, parsed[1].boundingBox.left, 0.0001f)
        assertEquals(0f, parsed[1].boundingBox.top, 0.0001f)
        assertEquals(1f, parsed[1].boundingBox.right, 0.0001f)
        assertEquals(1f, parsed[1].boundingBox.bottom, 0.0001f)
    }

    @Test
    fun parseEfficientDetTensor_supportsRankTwoAndFlattenedOutputs() {
        val rankTwo = arrayOf(
            floatArrayOf(1f, 0.73f, 0.1f, 0.2f, 0.4f, 0.6f)
        )
        val flattened = floatArrayOf(2f, 0.8f, 0.25f, 0.15f, 0.75f, 0.65f)

        val rankTwoParsed = ObjectTracker.parseEfficientDetDetections(rankTwo, rotationDegrees = 0)
        val flatParsed = ObjectTracker.parseEfficientDetDetections(flattened, rotationDegrees = 0)

        assertEquals(1, rankTwoParsed.size)
        assertEquals(1, flatParsed.size)
        assertEquals(0.2f, rankTwoParsed[0].boundingBox.left, 0.0001f)
        assertEquals(0.15f, flatParsed[0].boundingBox.left, 0.0001f)
    }

    @Test
    fun parseEfficientDetTensor_remapsNormalizedCoordinatesForPreviewRotation() {
        val output = arrayOf(
            arrayOf(floatArrayOf(3f, 0.91f, 0.2f, 0.1f, 0.6f, 0.5f))
        )

        val parsed = ObjectTracker.parseEfficientDetDetections(output, rotationDegrees = 90)

        assertEquals(1, parsed.size)
        assertEquals(0.4f, parsed[0].boundingBox.left, 0.0001f)
        assertEquals(0.1f, parsed[0].boundingBox.top, 0.0001f)
        assertEquals(0.8f, parsed[0].boundingBox.right, 0.0001f)
        assertEquals(0.5f, parsed[0].boundingBox.bottom, 0.0001f)
    }

    @Test
    fun objectSelectionScore_favorsCentralPersistentCinemaSubjects() {
        val centered = rect(0.42f, 0.38f, 0.58f, 0.62f)
        val edge = rect(0.02f, 0.1f, 0.3f, 0.4f)

        val centeredScore = ObjectTracker.objectSelectionScore(
            confidence = 0.72f,
            bounds = centered,
            trackedFrames = 80
        )
        val edgeScore = ObjectTracker.objectSelectionScore(
            confidence = 0.78f,
            bounds = edge,
            trackedFrames = 8
        )

        assertTrue(centeredScore > edgeScore)
    }

    @Test
    fun cinematicRackPullStep_appliesExponentialDampedFocusMovement() {
        val next = ObjectTracker.cinematicRackPullStep(
            previous = 0.25f,
            target = 0.8f,
            deltaTimeSeconds = 1f / 30f,
            cinemaMode = true
        )

        assertTrue(next > 0.25f)
        assertTrue(next < 0.8f)
        assertEquals(0.25f + (0.8f - 0.25f) * (1f - kotlin.math.exp(-3.5f / 30f)), next, 0.0001f)
    }

    private fun rect(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }

    private fun sourceFile(pathFromRepoRoot: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(dir, pathFromRepoRoot)
            if (candidate.exists()) return candidate
            val appRelative = File(dir, pathFromRepoRoot.removePrefix("app/"))
            if (appRelative.exists()) return appRelative
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        return File(pathFromRepoRoot)
    }
}
