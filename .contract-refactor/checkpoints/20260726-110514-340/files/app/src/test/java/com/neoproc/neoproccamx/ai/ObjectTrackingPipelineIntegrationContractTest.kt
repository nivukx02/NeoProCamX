package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ObjectTrackingPipelineIntegrationContractTest {

    @Test
    fun aiPipelineSchedulesObjectTrackingOffMainThreadAndFeedsSubjectAwareness() {
        val source = sourceFile("app/src/main/java/com/neoproc/neoproccamx/pipeline/AICameraPipeline.kt").readText()

        assertTrue(source.contains("objectExecutor"))
        assertTrue(source.contains("objectStageBusy"))
        assertTrue(source.contains("cachedObjectsRef"))
        assertTrue(source.contains("objectTracker.track"))
        assertTrue(source.contains("objects = objects"))
        assertTrue(source.contains("predictiveSubjectTracker.updateTracking"))
    }

    @Test
    fun predictiveSubjectTrackerKeepsObjectsInUnifiedSubjectGraph() {
        val source = sourceFile("app/src/main/java/com/neoproc/neoproccamx/ai/PredictiveSubjectTracker.kt").readText()

        assertTrue(source.contains("object:${'$'}{obj.trackingId}"))
        assertTrue(source.contains("OBJECT_RACK_PULL_LAMBDA_CINEMA"))
        assertTrue(source.contains("cinematicRackPullStep"))
        assertFalse(source.contains("objects: List<TrackedObject> = emptyList()"))
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
