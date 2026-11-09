package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraControllerRawSessionLineageContractTest {
    private val source by lazy { File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText() }

    @Test
    fun `outgoing Camera2 session results are rejected before metadata publication`() {
        val callback = source.substring(
            source.indexOf("private val captureCallback"),
            source.indexOf("private fun maybeLogPhysicalTelemetryForShaderProfiles")
        )
        val staleReject = callback.indexOf(
            "if (binding?.session !== s || binding.generation != sessionGeneration.get()) {"
        )
        val staleBoundaryFailure = callback.indexOf(
            "rawResumeBoundary?.let { failRawResumeBoundary(it, restartRepeating = false) }",
            staleReject
        )
        val staleReturn = callback.indexOf("return", staleBoundaryFailure)
        val publish = callback.indexOf("processResult(result, captureSessionGeneration)")

        assertTrue(staleReject >= 0)
        assertTrue(staleBoundaryFailure > staleReject)
        assertTrue(staleReturn > staleBoundaryFailure)
        assertTrue(publish > staleReturn)
    }

    @Test
    fun `session generation brackets raw presentation configuration`() {
        assertTrue(source.contains("onSessionGenerationAdvanced?.invoke(generation)"))
        assertTrue(source.contains("onSessionGenerationConfigured?.invoke(generation)"))
        assertTrue(source.contains("onCaptureResultWithSessionGeneration?.invoke(tr, captureSessionGeneration)"))
    }

    @Test
    fun `capture callback reads an atomic session generation binding without lifecycle map locking`() {
        val callback = source.substring(
            source.indexOf("private val captureCallback"),
            source.indexOf("private fun maybeLogPhysicalTelemetryForShaderProfiles")
        )

        assertTrue(source.contains("AtomicReference<CaptureResultSessionBinding?>(null)"))
        assertTrue(callback.contains("val binding = captureResultSessionBinding.get()"))
        assertTrue(callback.contains(
            "if (binding?.session !== s || binding.generation != sessionGeneration.get()) {"
        ))
        assertTrue(callback.contains(
            "rawResumeBoundary?.let { failRawResumeBoundary(it, restartRepeating = false) }"
        ))
        assertTrue(callback.contains("val captureSessionGeneration = binding.generation"))
        assertTrue(!callback.contains("configuredSessionGenerations[s]"))
        assertTrue(!callback.contains("synchronized(sessionCollectionsLock)"))
    }

    @Test
    fun `configured session publishes exact generation before repeating capture starts`() {
        val configured = source
            .substringAfter("configuredSessionGenerations[s] = generation")
            .substringBefore("notifySessionGenerationConfigured(generation)")
        val bindingIndex = configured.indexOf(
            "CaptureResultSessionBinding(session = s, generation = generation)"
        )
        val repeatingStartIndex = configured.indexOf("startPreviewInternal(")

        assertTrue(bindingIndex >= 0)
        assertTrue(repeatingStartIndex > bindingIndex)
        assertTrue(configured.contains("expectedGeneration = generation"))
        assertTrue(configured.contains("expectedSession = s"))
    }
}
