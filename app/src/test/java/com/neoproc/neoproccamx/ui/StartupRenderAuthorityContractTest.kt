package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StartupRenderAuthorityContractTest {
    private val viewModel by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    }
    private val vulkanRuntime by lazy {
        File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt").readText()
    }

    @Test
    fun `first preview preparation waits for persisted settings`() {
        val bind = viewModel.substring(
            viewModel.indexOf("private fun bindPreviewSurface("),
            viewModel.indexOf("private fun preferredVideoSourceSize", viewModel.indexOf("private fun bindPreviewSurface("))
        )
        val authorityGate = bind.indexOf("if (!startupPreferencesApplied)")
        val sizeSelection = bind.indexOf("val previewSize = preferredPreviewSize()")
        assertTrue(authorityGate >= 0)
        assertTrue(sizeSelection > authorityGate)
    }

    @Test
    fun `camera open waits for persisted settings before Camera2 open`() {
        val open = viewModel.substring(
            viewModel.indexOf("fun openCamera()"),
            viewModel.indexOf("fun closeCamera", viewModel.indexOf("fun openCamera()"))
        )
        val authorityGate = open.indexOf("if (!startupPreferencesApplied)")
        val cameraOpen = open.indexOf("camera.openCamera(")
        assertTrue(authorityGate >= 0)
        assertTrue(cameraOpen > authorityGate)
    }

    @Test
    fun `Vulkan initialization does not create a throwaway MediaCodec`() {
        val initialize = vulkanRuntime.substring(
            vulkanRuntime.indexOf("fun initialize("),
            vulkanRuntime.indexOf("fun setCinemaDebayerActive")
        )
        assertFalse(initialize.contains("MediaCodec.create"))
        assertFalse(initialize.contains("COLOR_TRANSFER_HLG"))
    }
}
