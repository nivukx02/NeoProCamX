package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VulkanPreviewLutUploadContractTest {
    private val bridgeSource =
        File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt").readText()
    private val runtimeSource =
        File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt").readText()
    private val nativeSource = File("src/main/cpp/vulkan_bridge.cpp").readText()

    @Test
    fun `preview LUT upload result propagates from Vulkan through the runtime`() {
        val bridgeUpdate = bridgeSource
            .substringAfter("fun updatePreviewLut(")
            .substringBefore("fun updateSubjectDetailMask(")
        val nativeDeclaration = bridgeSource
            .substringAfter("private external fun nativeUpdatePreviewLut(")
            .substringBefore("private external fun nativeUpdateSubjectDetailMask(")
        val runtimeUpdate = runtimeSource
            .substringAfter("fun updatePreviewLut(")
            .substringBefore("fun setFrameLineageSubmissionListener(")
        val nativeUpdate = nativeSource
            .substringAfter("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeUpdatePreviewLut(")
            .substringBefore("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeUpdateSubjectDetailMask(")

        assertTrue(bridgeUpdate.contains("): Boolean"))
        assertTrue(bridgeUpdate.contains("if (handle == 0L) return false"))
        assertTrue(bridgeUpdate.contains("return nativeUpdatePreviewLut(handle, lutData, size)"))
        assertTrue(nativeDeclaration.contains("): Boolean"))
        assertTrue(nativeSource.contains("extern \"C\" JNIEXPORT jboolean JNICALL\nJava_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeUpdatePreviewLut("))
        assertTrue(nativeUpdate.contains("if (state == nullptr) return JNI_FALSE"))
        assertTrue(nativeUpdate.contains("if (!state->supported) return JNI_FALSE"))
        assertTrue(nativeUpdate.contains("preview LUT upload failed"))
        assertTrue(nativeUpdate.contains("return JNI_TRUE"))
        assertTrue(runtimeUpdate.contains("val updated = runCatching"))
        assertTrue(runtimeUpdate.contains("bridge.updatePreviewLut(lutData = lutData, size = size)"))
        assertTrue(runtimeUpdate.contains("updated"))
        assertTrue(runtimeUpdate.contains("getOrDefault(false)"))
    }

    @Test
    fun `canonical 65 cube is accepted while larger LUTs remain rejected`() {
        val nativeUpdate = nativeSource
            .substringAfter("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeUpdatePreviewLut(")
            .substringBefore("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeUpdateSubjectDetailMask(")

        assertTrue(nativeUpdate.contains("constexpr int kMaximumPreviewLutSize = 65"))
        assertTrue(nativeUpdate.contains("if (size > kMaximumPreviewLutSize)"))
        assertTrue(nativeUpdate.contains("length != static_cast<jsize>(expectedLength)"))
        assertTrue(nativeUpdate.contains("preview LUT rejected: non-finite value"))
    }

    @Test
    fun `diagnostic refresh cannot turn an accepted native LUT upload into failure`() {
        val runtimeUpdate = runtimeSource
            .substringAfter("fun updatePreviewLut(")
            .substringBefore("fun setFrameLineageSubmissionListener(")
        val acceptedResult = runtimeUpdate.indexOf("val updated = runCatching")
        val diagnosticGuard = runtimeUpdate.indexOf("if (updated)")
        val diagnosticRefresh = runtimeUpdate.indexOf("runCatching { refreshCapabilitySummary() }")
        val returnedResult = runtimeUpdate.lastIndexOf("updated")

        assertTrue(acceptedResult >= 0)
        assertTrue(diagnosticGuard > acceptedResult)
        assertTrue(diagnosticRefresh > diagnosticGuard)
        assertTrue(returnedResult > diagnosticRefresh)
        assertTrue(runtimeUpdate.contains("getOrDefault(false)"))
        assertTrue(runtimeUpdate.contains("Preview LUT diagnostic refresh failed"))
    }
}
