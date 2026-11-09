package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class OffscreenAhbEglEncoderBridgeContractTest {

    @Test
    fun `offscreen bridge exposes real egl codec surface handoff`() {
        val kotlinSource = File("src/main/java/com/neoproc/neoproccamx/rendering/OffscreenAhbEglEncoderBridge.kt")
            .readText()
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val cmake = File("src/main/cpp/CMakeLists.txt").readText()

        assertTrue(kotlinSource.contains("nativeSetEncoderSurface"))
        assertTrue(kotlinSource.contains("nativeSubmitHardwareBuffer"))
        assertTrue(kotlinSource.contains("OffscreenEglDeliveryMode"))
        assertTrue(kotlinSource.contains("preferredDeliveryMode"))
        assertTrue(nativeSource.contains("eglCreateWindowSurface"))
        assertTrue(nativeSource.contains("eglCreateWindowSurface failed colorspace=%s delivery=%s error=0x%04x"))
        assertTrue(nativeSource.contains("reacquiring native window before retry"))
        assertTrue(nativeSource.contains("EGL_RECORDABLE_ANDROID"))
        assertTrue(nativeSource.contains("ANativeWindow_setBuffersDataSpace"))
        assertTrue(nativeSource.contains("ANativeWindow_getBuffersDataSpace"))
        assertTrue(nativeSource.contains("dlsym"))
        assertFalse(nativeSource.contains("ANativeWindow_setBuffersGeometry"))
        assertTrue(nativeSource.contains("eglPresentationTimeANDROID"))
        assertFalse(nativeSource.contains("eglSwapInterval(state->display, 0)"))
        assertTrue(nativeSource.contains("codecBackpressurePacing"))
        assertTrue(nativeSource.contains("eglSwapBuffers"))
        assertTrue(nativeSource.contains("avgSwapUs"))
        assertTrue(nativeSource.contains("maxSwapUs"))
        assertTrue(nativeSource.contains("slowSwapFrames"))
        assertTrue(nativeSource.contains("historyWriteTexture"))
        assertTrue(nativeSource.contains("Reattach every frame after the texture-name swap below"))
        assertTrue(nativeSource.contains("decodeHistoryTexture"))
        assertTrue(nativeSource.contains("uHistoryPreGrade"))
        assertTrue(nativeSource.contains("RGB10_A2 decode-history temporal blend ACTIVE"))
        assertTrue(nativeSource.contains("RGB10_A2_history_pingpong"))
        assertTrue(nativeSource.contains("kNdrPqLutSize = 65"))
        assertTrue(nativeSource.contains("NDR PQ delivery LUTs %s size=%d rgb16f"))
        assertTrue(nativeSource.contains("uniform highp sampler3D uNdrPqLut"))
        assertTrue(nativeSource.contains("uNdrPqLutReady > 0.5"))
        assertTrue(nativeSource.contains("neoDeliverPqFromHlg(finalRgb, uNdrDeliveryMode)"))
        assertTrue(kotlinSource.contains("NdrWarmSkinRedTameFlag.effectiveStrength"))
        assertTrue(nativeSource.contains("uniform float uNdrWarmSkinRedTame"))
        assertTrue(nativeSource.contains("moderateRed"))
        assertTrue(nativeSource.contains("bluePresent"))
        assertTrue(nativeSource.contains("corrected * (oy / correctedY)"))
        assertTrue(nativeSource.contains("uNdrWarmSkinRedTame <= 0.0"))
        assertTrue(nativeSource.contains("glActiveTexture(GL_TEXTURE3)"))
        assertTrue(nativeSource.contains("ndrPqLutReady="))
        assertTrue(nativeSource.contains("avgHistoryPresentUs"))
        assertTrue(nativeSource.contains("historyPresentErrorChecks="))
        assertTrue(nativeSource.contains("glGetError() after a full-resolution FBO->encoder-surface blit can serialize tile GPUs"))
        assertTrue(nativeSource.contains("decodeOnceEnabled="))
        assertTrue(nativeSource.contains("decodeOnceFrames="))
        assertTrue(nativeSource.contains("externalPathFrames="))
        assertTrue(nativeSource.contains("sourceHistoryFrames="))
        assertTrue(nativeSource.contains("single-pass external record path ACTIVE"))
        assertTrue(nativeSource.contains("sourceHistory=%s"))
        assertTrue(nativeSource.contains("single_pass_external_source_history"))
        assertTrue(nativeSource.contains("Update a 10-bit source-history texture"))
        assertTrue(nativeSource.contains("single_pass_external"))
        assertTrue(nativeSource.contains("rgb10a2_decode_history"))
        assertTrue(nativeSource.contains("constexpr size_t kEglImageCacheCapacity = 32"))
        assertTrue(nativeSource.contains("keep 2x that pool"))
        assertTrue(nativeSource.contains("AHardwareBuffer_getId"))
        assertTrue(nativeSource.contains("eglImageCacheKey="))
        assertTrue(nativeSource.contains("AHARDWAREBUFFER_ID"))
        assertTrue(nativeSource.contains("stable AHardwareBuffer_getId unavailable"))
        assertTrue(nativeSource.contains("requestedEnable && !state->eglImageCacheStableIds"))
        assertTrue(nativeSource.contains("eglImageCacheIdFailures="))
        assertFalse(nativeSource.contains("Hold a SEPARATE AHB ref"))
        assertFalse(nativeSource.contains("EglImageCacheEntry{eglImage, hardwareBuffer}"))
        assertTrue(nativeSource.contains("the cache must not hold an extra AHB ref"))
        assertFalse(nativeSource.contains("glCopyTexImage2D"))
        assertFalse(nativeSource.contains("glCopyTexSubImage2D"))
        assertTrue(nativeSource.contains("AHardwareBuffer_fromHardwareBuffer"))
        assertTrue(nativeSource.contains("glEGLImageTargetTexture2DOES"))
        assertTrue(nativeSource.contains("samplerExternalOES"))
        assertTrue(nativeSource.contains("GL_EXT_YUV_target"))
        assertTrue(nativeSource.contains("explicitYuvDecodeFrames"))
        assertTrue(nativeSource.contains("GLuint sourceTexture = 0"))
        assertTrue(nativeSource.contains("glDeleteTextures(1, &state->sourceTexture)"))
        assertFalse(nativeSource.contains("glDeleteTextures(1, &texture)"))
        val decodeFlagSource = File("src/main/java/com/neoproc/neoproccamx/rendering/EglDecodeOnceFlag.kt")
            .readText()
        assertTrue(decodeFlagSource.contains("var enabled: Boolean = true"))
        val cacheFlagSource = File("src/main/java/com/neoproc/neoproccamx/rendering/EglImageCacheFlag.kt")
            .readText()
        assertTrue(cacheFlagSource.contains("var enabled: Boolean = true"))
        assertTrue(cmake.contains("offscreen_egl_encoder_bridge.cpp"))
        assertTrue(cmake.contains("GLESv3"))
        assertTrue(cmake.contains("find_library(dl-lib dl)"))
    }

    @Test
    fun `EGL image cache entry never holds an extra hardware buffer reference`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val cacheEntry = nativeSource
            .substringAfter("struct EglImageCacheEntry")
            .substringBefore("};")

        assertTrue(cacheEntry.contains("EGLImageKHR image"))
        assertFalse(cacheEntry.contains("AHardwareBuffer"))
    }

    @Test
    fun `offscreen bridge requests and proves ten bit sdr delivery`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val runtimeSource = File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt")
            .readText()
        val bindBlock = runtimeSource
            .substringAfter("fun setOffscreenAhbEglEncoderSurface(")
            .substringBefore("fun setPreviewSurface(")

        assertTrue(nativeSource.contains("EGL_RED_SIZE, 10"))
        assertTrue(nativeSource.contains("EGL_GREEN_SIZE, 10"))
        assertTrue(nativeSource.contains("EGL_BLUE_SIZE, 10"))
        assertTrue(nativeSource.contains("EGL_ALPHA_SIZE, 2"))
        assertTrue(nativeSource.contains("EGL_GL_COLORSPACE_KHR"))
        assertTrue(nativeSource.contains("EGL_GL_COLORSPACE_SRGB_KHR"))
        assertTrue(nativeSource.contains("ADATASPACE_SRGB"))
        assertTrue(nativeSource.contains("ADATASPACE_BT2020_HLG"))
        assertTrue(nativeSource.contains("ADATASPACE_BT2020_PQ"))
        assertTrue(nativeSource.contains("dataSpaceMatchesDelivery"))
        assertTrue(nativeSource.contains("offscreenEglDataSpace="))
        assertTrue(nativeSource.contains("offscreenEglDataSpaceApplied="))
        assertTrue(nativeSource.contains("requestedDeliveryMode == \"HLG\""))
        assertTrue(nativeSource.contains("requestedDeliveryMode == \"PQ\""))
        assertTrue(nativeSource.contains("\"SRGB\", \"SDR\", \"SRGB_NONLINEAR\""))
        assertTrue(nativeSource.contains("eglGetConfigAttrib"))
        assertTrue(nativeSource.contains("eglQuerySurface"))
        assertTrue(nativeSource.contains("offscreenEglRedBits="))
        assertTrue(nativeSource.contains("offscreenEglDeliveryCompatible="))
        assertTrue(nativeSource.contains("state->alphaBits == 2"))
        assertTrue(bindBlock.contains("resolveOffscreenEncoderSurfaceStatus("))
        assertTrue(bindBlock.contains("expectedDeliveryMode = preferredDeliveryMode.nativeToken"))
        assertTrue(bindBlock.contains("surfacePrecisionProven"))
        assertTrue(bindBlock.contains("deliveryMode == \"SDR\""))
        assertFalse(bindBlock.contains("refusing reduced offscreen AHB EGL encoder"))
    }

    @Test
    fun `offscreen prearm binds codec surface but pauses submissions until recording starts`() {
        val runtimeSource = File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt")
            .readText()
        val cameraSource = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
            .readText()
        val submitBlock = runtimeSource
            .substringAfter("fun submitCameraImage(")
            .substringBefore("private fun emitFrameLineageSubmission(")
        val pacingLockBlock = submitBlock
            .substringAfter("val pacingDecision = synchronized(offscreenFramePacingLock) {")
            .substringBefore("if (!pacingDecision.submit)")
        val offscreenStartBlock = cameraSource
            .substringAfter("suspend fun startOffscreenAhbEglEncoderRecording(): Boolean {")
            .substringBefore("suspend fun startByteBufferP010Recording(): Boolean {")
        val stopBlock = cameraSource
            .substringAfter("private fun stopRecordingInternal()")
            .substringBefore("private fun stopRawCaptureIfNeeded()")

        assertTrue(runtimeSource.contains("offscreenAhbEglSubmissionEnabled"))
        assertTrue(runtimeSource.contains("fun setOffscreenAhbEglSubmissionEnabled(enabled: Boolean)"))
        assertTrue(submitBlock.contains("offscreenSubmissionPaused"))
        assertTrue(submitBlock.contains("!offscreenAhbEglSubmissionEnabled.get()"))
        assertTrue(submitBlock.contains("offscreenSkippedByPacing || offscreenSubmissionPaused"))
        assertTrue(submitBlock.contains("val pacingDecision = synchronized(offscreenFramePacingLock)"))
        assertTrue(submitBlock.contains("offscreenAhbEglEncoderBridge.submitHardwareBuffer("))
        assertFalse(pacingLockBlock.contains("offscreenAhbEglEncoderBridge.submitHardwareBuffer("))
        assertTrue(
            submitBlock.indexOf("val pacingDecision = synchronized(offscreenFramePacingLock)") <
                submitBlock.indexOf("offscreenAhbEglEncoderBridge.submitHardwareBuffer(")
        )
        assertTrue(offscreenStartBlock.contains("vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(true)"))
        assertTrue(stopBlock.contains("vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(false)"))
    }

    @Test
    fun `prearmed offscreen start preserves active import state during validation reset`() {
        val runtimeSource = File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt")
            .readText()
        val cameraSource = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
            .readText()
        val beginBlock = runtimeSource
            .substringAfter("fun beginPreviewImportSession(")
            .substringBefore("fun setOffscreenAhbEglEncoderSurface(")
        val offscreenStartBlock = cameraSource
            .substringAfter("suspend fun startOffscreenAhbEglEncoderRecording(): Boolean {")
            .substringBefore("suspend fun startByteBufferP010Recording(): Boolean {")

        assertTrue(beginBlock.contains("preserveActiveOffscreenImport"))
        assertTrue(beginBlock.contains("preserveActiveOffscreenImport && offscreenBound"))
        assertTrue(beginBlock.contains("bridge.cleanupImportedFrames()"))
        assertTrue(
            beginBlock.indexOf("preserveActiveOffscreenImport && offscreenBound") <
                beginBlock.indexOf("bridge.cleanupImportedFrames()")
        )
        assertTrue(
            offscreenStartBlock.contains(
                "preserveActiveOffscreenImport = offscreenSourceAlreadyInSession"
            )
        )
    }

    @Test
    fun `offscreen bridge rejects unproven hardware buffer source precision`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val submitBlock = nativeSource
            .substringAfter("nativeSubmitHardwareBuffer(")
            .substringBefore("nativeGetSummary(")

        assertTrue(submitBlock.contains("AHardwareBuffer_describe"))
        assertTrue(nativeSource.contains("AHARDWAREBUFFER_FORMAT_YCbCr_P010"))
        assertTrue(nativeSource.contains("AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM"))
        assertTrue(submitBlock.contains("sourcePrecisionProven"))
        assertTrue(submitBlock.contains("if (!sourceProof.proven)"))
        assertTrue(submitBlock.contains("opaqueVendorSource"))
        assertTrue(submitBlock.contains("if (destProven10Bit && opaqueVendorSource)"))
        assertTrue(submitBlock.contains("\"TRUSTED_EXTERNAL_10_BIT\""))
        assertFalse(submitBlock.contains("destProven10Bit && opaqueVendorSource && hdrDelivery"))
        assertTrue(submitBlock.contains("First offscreen source accepted format=%s bitDepth=%d precision=%s"))
        assertTrue(
            submitBlock.indexOf("if (!sourceProof.proven)") <
                submitBlock.indexOf("eglGetNativeClientBufferANDROID")
        )
        assertTrue(nativeSource.contains("encoderSourceFormat="))
        assertTrue(nativeSource.contains("encoderSourceBitDepth="))
        assertTrue(nativeSource.contains("encoderSourcePrecision="))
        assertTrue(nativeSource.contains("encoderSourcePrecisionProven="))
    }
}
