package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraP010SdrPrewarmContractTest {
    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `gate closed camera frame runs prewarm before being retired`() {
        val runtime = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val gateClosedBranch = runtime
            .substringAfter("if (!offscreenAhbEglSubmissionEnabled.get()) {")
            .substringBefore("} else {")

        val prewarmIndex = gateClosedBranch.indexOf("prewarmGateClosedCameraP010Sdr(")
        val pauseIndex = gateClosedBranch.indexOf("offscreenSubmissionPaused = true")
        assertTrue(prewarmIndex >= 0)
        assertTrue(pauseIndex > prewarmIndex)
        assertTrue(gateClosedBranch.contains("uniforms = renderBundle.enhancementUniforms"))
    }

    @Test
    fun `native prewarm runs exact full grade without encoder presentation or history publication`() {
        val native = source("src/main/cpp/offscreen_egl_encoder_bridge.cpp")
        val noPresentBlock = native
            .substringAfter("// CAMERA_P010_SDR_PREWARM_NO_PRESENT_BEGIN")
            .substringBefore("// CAMERA_P010_SDR_PREWARM_NO_PRESENT_END")

        assertTrue(noPresentBlock.contains("glFinish()"))
        assertTrue(noPresentBlock.contains("state->historyValid = false"))
        assertTrue(noPresentBlock.contains("state->decodeHistoryValid = false"))
        assertFalse(noPresentBlock.contains("eglPresentationTimeANDROID("))
        assertFalse(noPresentBlock.contains("eglSwapBuffers("))
        assertFalse(noPresentBlock.contains("++state->submittedFrames"))
    }

    @Test
    fun `prewarm privately draws identity and exact live NIV99 shader variants`() {
        val native = source("src/main/cpp/offscreen_egl_encoder_bridge.cpp")
        val selectionBlock = native
            .substringAfter("const bool cameraP010SdrPrewarmIdentityGradeProgramReady")
            .substringBefore("// Compile-time PQ/profile invariants")
        val niv99DrawBlock = native
            .substringAfter("// PRIVATE_NIV99_SHADER_PREWARM_BEGIN")
            .substringBefore("// PRIVATE_NIV99_SHADER_PREWARM_END")
        val completionBlock = native
            .substringAfter("// CAMERA_P010_SDR_PREWARM_NO_PRESENT_BEGIN")
            .substringBefore("// CAMERA_P010_SDR_PREWARM_NO_PRESENT_END")

        assertTrue(selectionBlock.contains("state->sdrCreativeLutNiv99Program2d"))
        assertTrue(selectionBlock.contains("state->sdrNiv99Program2d"))
        assertTrue(selectionBlock.contains("state->mainProgram2d"))
        assertTrue(selectionBlock.contains("cameraP010SdrPrewarmIdentityGradeProgramReady &&"))
        assertTrue(selectionBlock.contains("cameraP010SdrPrewarmNiv99Program2d != 0"))

        assertTrue(niv99DrawBlock.contains("glUseProgram(cameraP010SdrPrewarmNiv99Program2d)"))
        assertTrue(niv99DrawBlock.contains("niv99PrewarmLoc.niv99RgbCorrection, 1.0f, 1.0f, 1.0f"))
        assertTrue(niv99DrawBlock.contains("niv99PrewarmLoc.niv99ActiveConfidence, 1.0f"))
        assertTrue(niv99DrawBlock.contains("glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)"))
        assertFalse(niv99DrawBlock.contains("eglPresentationTimeANDROID("))
        assertFalse(niv99DrawBlock.contains("eglSwapBuffers("))

        assertTrue(completionBlock.contains("cameraP010SdrPrewarmIdentityShaderDraws"))
        assertTrue(completionBlock.contains("cameraP010SdrPrewarmNiv99ShaderDraws"))
        assertTrue(completionBlock.contains("identityShaderDraw=true niv99ShaderDraw=true"))
    }

    @Test
    fun `sdr recording gate waits for completed prewarm and emits checkpoints`() {
        val viewModel = source(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        )
        val startSection = viewModel
            .substringAfter("else if (offscreenDeliveryMode == OffscreenEglDeliveryMode.SDR) {")
            .substringBefore("if (sensorLinearNdrInput && !sensorLinearNdrRecordingLifecycle.startIngress())")

        val awaitIndex = startSection.indexOf("awaitCameraP010SdrPrewarm()")
        val commitIndex = startSection.indexOf("commitSurfaceEncoderReadiness()")
        val gateIndex = startSection.indexOf("setOffscreenAhbEglSubmissionEnabled(true)")
        assertTrue(awaitIndex >= 0)
        assertTrue(commitIndex > awaitIndex)
        assertTrue(gateIndex > commitIndex)
        assertTrue(startSection.contains("camera-p010-sdr-source-proof"))
        assertTrue(startSection.contains("before-gate-opening"))
        assertTrue(viewModel.contains("first-processed-frame"))
        assertTrue(viewModel.contains("pre-stop-detach"))
    }
}
