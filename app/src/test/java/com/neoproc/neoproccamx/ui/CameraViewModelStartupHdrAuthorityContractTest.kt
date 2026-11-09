package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraViewModelStartupHdrAuthorityContractTest {
    private val source: String
        get() = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
            .readText()
            .replace("\r\n", "\n")

    @Test
    fun `Vulkan initialization and serialized render packets wait for startup authority`() {
        val coordinator = source
            .substringAfter("private val vulkanPreviewConfigCoordinator")
            .substringBefore("private fun currentVulkanRenderProfileGeneration")
        assertTrue(coordinator.contains("startupPreferencesReady.await()"))

        val initialization = source
            .substringAfter("vulkanRuntime.initialize(getApplication<Application>().cacheDir)")
            .substringBefore("viewModelScope.launch {\n            camera.frameGeometry.collect")
        assertTrue(
            initialization.indexOf("startupPreferencesReady.await()") <
                initialization.indexOf("pushVulkanPreviewRenderConfig(force = true)")
        )
        assertFalse(initialization.contains("vulkanRuntime.setHdrDynamicRangeMode("))
        assertFalse(initialization.contains("vulkanRuntime.updateNdrNivukxMeasuredCorrection("))

        val publication = source
            .substringAfter("private fun publishStartupPreferencesApplied()")
            .substringBefore("private fun completeStartupFastPath()")
        assertTrue(publication.contains("startupPreferencesApplied = true"))
        assertTrue(publication.contains("startupPreferencesReady.complete(Unit)"))
    }

    @Test
    fun `render stage has one runtime HDR authority with no preference or contract fallback`() {
        val renderStage = source
            .substringAfter("private fun pushVulkanPreviewRenderConfig(")
            .substringAfter("val videoSurface = renderState.videoSurface")
            .substringBefore("val authoritativeColorProfile = resolveGpuColorProfile")

        assertTrue(renderStage.contains("VideoHdrRuntimeAuthorityPolicy.resolve("))
        assertTrue(renderStage.contains("activeHdrMode = hdrModeAuthority"))
        assertFalse(renderStage.contains("prefs.videoHdrMode"))
        assertFalse(renderStage.contains("renderContract.normalizedHdrMode"))
        assertFalse(
            source.contains(
                "startupFeatureGateActive && _videoHdrMode.value == \"OFF\" -> prefs.videoHdrMode"
            )
        )
    }

    @Test
    fun `constructor never seeds native NDR directly from persistent storage`() {
        val initPrefix = source
            .substringAfter("init {")
            .substringBefore("vulkanRuntime.initialize(getApplication<Application>().cacheDir)")
        assertFalse(
            initPrefix.contains("NdrNivukxMeasuredCorrectionPolicy.resolve(prefs.videoHdrMode)")
        )
    }
}
