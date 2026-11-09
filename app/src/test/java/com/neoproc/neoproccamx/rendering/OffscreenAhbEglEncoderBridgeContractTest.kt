package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class OffscreenAhbEglEncoderBridgeContractTest {

    @Test
    fun `decode once temporal history rotates through a quality identical three texture ring`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val rotation = nativeSource
            .substringAfter("if (useDecodeTextureHistory && !cameraP010SdrPrewarm) {")
            .substringBefore("++state->temporalHistoryFrames")
        val teardown = nativeSource
            .substringAfter("bool destroyState(OffscreenAhbEglEncoderState* state)")
            .substringBefore("extern \"C\" JNIEXPORT jlong JNICALL")

        assertTrue(nativeSource.contains("GLuint decodeSpareTexture = 0"))
        assertTrue(nativeSource.contains("configureHistoryTexture(\n                state->decodeSpareTexture"))
        assertTrue(rotation.contains("const GLuint completedDecodeTexture = state->decodeTexture"))
        assertTrue(rotation.contains("state->decodeTexture = state->decodeSpareTexture"))
        assertTrue(rotation.contains("state->decodeSpareTexture = state->decodeHistoryTexture"))
        assertTrue(rotation.contains("state->decodeHistoryTexture = completedDecodeTexture"))
        assertTrue(rotation.contains("std::swap(state->decodeTexture, state->decodeHistoryTexture)"))
        assertTrue(rotation.contains("state->decodeHistoryValid = true"))
        assertTrue(teardown.contains("glDeleteTextures(1, &state->decodeSpareTexture)"))
        assertTrue(nativeSource.contains("decodeTripleBufferedFrames="))

        // Model the native rotation: history must remain exactly N-1 while the write target is
        // never either texture read by the immediately preceding grade pass.
        var write = "A"
        var history = "B"
        var spare = "C"
        var previousCompleted: String? = null
        repeat(32) {
            assertTrue(write != history)
            if (previousCompleted != null) assertTrue(history == previousCompleted)
            val completed = write
            write = spare.also { spare = history }
            history = completed
            previousCompleted = completed
        }
    }

    @Test
    fun `zero weight chroma branches retain canonical equations and skip unreachable work`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val calibration = nativeSource
            .substringAfter("float calibrationY = y * calibrationSignalScale;")
            .substringBefore("// General chroma-noise reduction remains")

        assertTrue(calibration.contains("if (calibrationY < 0.090 && edgeProtect < 1.0)"))
        assertTrue(calibration.contains("if (calibrationY > 0.075 && edgeProtect < 1.0)"))
        assertTrue(calibration.contains("if (calibrationNeighbourConsensusMagnitude < 0.090)"))
        assertTrue(calibration.contains("if (calibrationY < 0.065 && edgeProtect < 1.0)"))
        assertTrue(calibration.contains("mix(chroma, consensusChroma, shadowCalibrationBlend)"))
        assertTrue(calibration.contains("mix(\n                calibratedChroma,\n                neighbourConsensusChroma"))
        assertTrue(calibration.contains("calibratedChroma *= 1.0 - neutralize"))
    }

    @Test
    fun `record context requests high GPU priority with ordinary ES3 fallback`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val contextCreation = nativeSource
            .substringAfter("state->contextPriorityExtensionAvailable =")
            .substringBefore("if (eglMakeCurrent(")

        assertTrue(contextCreation.contains("EGL_IMG_context_priority"))
        assertTrue(contextCreation.contains("EGL_CONTEXT_PRIORITY_HIGH_IMG"))
        assertTrue(contextCreation.contains("state->contextPriorityFallbackUsed = true"))
        assertTrue(contextCreation.contains("contextAttribs"))
        assertTrue(contextCreation.contains("eglQueryContext("))
        assertTrue(nativeSource.contains("offscreenEglContextPriorityGranted="))
        assertTrue(nativeSource.contains("offscreenEglContextPriorityFallback="))
    }

    @Test
    fun `project owned pq preview uses untagged intermediate texture transport`() {
        val kotlinSource = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/OffscreenAhbEglEncoderBridge.kt"
        ).readText()
        val runtimeSource = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        ).readText()
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val previewBind = runtimeSource
            .substringAfter("fun prepareSensorLinearNdrPreviewSurface(")
            .substringBefore("fun submitLatestSensorLinearNdrPreviewFrame(")
        val transportBranch = nativeSource
            .substringAfter("requestedDeliveryMode == \"PROJECT_PQ_TEXTURE\"")
            .substringBefore("} else {")

        assertTrue(kotlinSource.contains("PROJECT_PQ_TEXTURE(\"PROJECT_PQ_TEXTURE\")"))
        assertTrue(
            previewBind.contains(
                "preferredDeliveryMode = OffscreenEglDeliveryMode.PROJECT_PQ_TEXTURE"
            )
        )
        assertFalse(previewBind.contains("preferredDeliveryMode = OffscreenEglDeliveryMode.PQ"))
        assertTrue(nativeSource.contains(
            "if (deliveryMode == \"PROJECT_PQ_TEXTURE\") return ADATASPACE_UNKNOWN"
        ))
        assertTrue(transportBranch.contains("EGL_NONE"))
        assertFalse(transportBranch.contains("EGL_GL_COLORSPACE_BT2020_PQ_EXT"))
        assertTrue(nativeSource.contains("verifiedDataSpace == ADATASPACE_UNKNOWN"))
        assertTrue(nativeSource.contains("state->deliveryModeLabel == \"PROJECT_PQ_TEXTURE\""))
    }

    @Test
    fun `scene linear near black calibration normalizes metrics without scaling pixels`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val calibrationBlock = nativeSource
            .substringAfter("// Generic cleanup thresholds are authored")
            .substringBefore("// General chroma-noise reduction remains")

        assertTrue(calibrationBlock.contains("(uInputTransfer == 3 || uInputTransfer == 4)"))
        assertTrue(calibrationBlock.contains("1.0 / clamp(uNdrToneProfile.x, 0.02, 0.30)"))
        assertTrue(calibrationBlock.contains("y * calibrationSignalScale"))
        assertTrue(calibrationBlock.contains("localStructure * calibrationSignalScale"))
        assertTrue(calibrationBlock.contains("chromaStructure * calibrationSignalScale"))
        assertTrue(calibrationBlock.contains("length(consensusChroma) * calibrationSignalScale"))
        assertTrue(calibrationBlock.contains("length(chroma - consensusChroma) * calibrationSignalScale"))
        assertTrue(calibrationBlock.contains("length(calibratedChroma) * calibrationSignalScale"))
        assertTrue(calibrationBlock.contains("bilateralLumaDeltaMetric = dl * calibrationSignalScale"))
        assertTrue(calibrationBlock.contains("detailLumaMetric = lcd * calibrationSignalScale"))
        assertTrue(calibrationBlock.contains("mix(chroma, consensusChroma, shadowCalibrationBlend)"))
        assertFalse(calibrationBlock.contains("chroma *= calibrationSignalScale"))
        assertFalse(calibrationBlock.contains("calibratedChroma *= calibrationSignalScale"))
        assertFalse(calibrationBlock.contains("finalColor *= calibrationSignalScale"))
        assertTrue(nativeSource.contains("smoothstep(0.012, 0.06, sourceMotionMetric)"))
        assertTrue(nativeSource.contains("smoothstep(0.012, 0.06, temporalMotionMetric)"))
        assertTrue(nativeSource.contains("smoothstep(0.02, 0.10, detailLumaMetric)"))
    }

    @Test
    fun `row major record grade is CPU packed for legal GLES matrix upload`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val packCalls = Regex(
            """packGradeMatrixColumnMajor\(state->recordGradeMatrix, gradeMat2?\)"""
        ).findAll(nativeSource).count()
        val legalUploads = Regex(
            """glUniformMatrix3fv\([^;]+, 1, GL_FALSE, gradeMat2?\);"""
        ).findAll(nativeSource).count()

        assertTrue(nativeSource.contains("columnMajor[column * 3 + row] = rowMajor[row * 3 + column]"))
        // External, decoded-2D, and Camera2 P010 prewarm NIV99 draws all upload the same packed
        // row-major matrix. Every path must use GL_FALSE as required by OpenGL ES.
        assertTrue(packCalls == 2)
        assertTrue(legalUploads == 3)
        assertFalse(Regex("""glUniformMatrix[234]fv\([^;]+GL_TRUE""").containsMatchIn(nativeSource))

        val asymmetricRowMajor = floatArrayOf(
            1f, 2f, 3f,
            4f, 5f, 6f,
            7f, 8f, 9f
        )
        val packedColumnMajor = FloatArray(9) { index ->
            val column = index / 3
            val row = index % 3
            asymmetricRowMajor[row * 3 + column]
        }
        assertTrue(
            packedColumnMajor.contentEquals(
                floatArrayOf(1f, 4f, 7f, 2f, 5f, 8f, 3f, 6f, 9f)
            )
        )
    }

    @Test
    fun `offscreen submit always retires its egl current binding`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val submitBlock = nativeSource
            .substringAfter("nativeSubmitHardwareBuffer(")
            .substringBefore("nativeGetSummary(")
        val scopedUnbindBlock = nativeSource
            .substringAfter("bool unbind()")
            .substringBefore("EGLint lastError()")

        assertTrue(nativeSource.contains("class ScopedSubmitEglUnbind final"))
        assertTrue(nativeSource.contains("~ScopedSubmitEglUnbind()"))
        assertTrue(submitBlock.contains("ScopedSubmitEglUnbind submitEglUnbind(state)"))
        assertTrue(submitBlock.contains("if (!submitEglUnbind.unbind())"))
        assertTrue(
            submitBlock.indexOf("if (!submitEglUnbind.unbind())") <
                submitBlock.lastIndexOf("return JNI_TRUE")
        )
        assertTrue(scopedUnbindBlock.contains("eglMakeCurrent("))
        assertTrue(scopedUnbindBlock.contains("EGL_NO_SURFACE"))
        assertTrue(scopedUnbindBlock.contains("EGL_NO_CONTEXT"))
    }

    @Test
    fun `project owned input reads retire before every post draw failure can reuse the slot`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val submitBlock = nativeSource
            .substringAfter("nativeSubmitHardwareBuffer(")
            .substringBefore("nativeGetSummary(")
        val blitFailure = submitBlock
            .substringAfter("if (blitError != GL_NO_ERROR)")
            .substringBefore("std::swap(state->historyTexture")
        val finishBoundary = submitBlock
            .substringAfter("uint64_t finishDurationUs = 0")
            .substringBefore("const auto swapStart")
        val swapBoundary = submitBlock
            .substringAfter("const auto swapStart")
            .substringBefore("const auto swapDurationUs")

        assertTrue(blitFailure.contains("inputIsSensorLinear || inputIsNeoSceneLog10"))
        assertTrue(blitFailure.indexOf("glFinish()") < blitFailure.indexOf("AHardwareBuffer_release"))
        assertTrue(blitFailure.indexOf("glFinish()") < blitFailure.indexOf("return JNI_FALSE"))
        assertTrue(finishBoundary.contains("if (projectOwnedInput)"))
        assertTrue(finishBoundary.contains("inputReadFenceRequested && state->inputReadFenceAvailable"))
        assertTrue(finishBoundary.contains("state->eglCreateSyncKHR("))
        assertTrue(finishBoundary.contains("glFlush()"))
        assertTrue(
            finishBoundary.contains(
                "!inputReadFenceRequested || inputReadSync == EGL_NO_SYNC_KHR"
            )
        )
        assertTrue(
            finishBoundary.indexOf("glFinish()") <
                finishBoundary.indexOf("collectGpuStageTiming(state)")
        )
        assertTrue(swapBoundary.contains("const EGLBoolean swapped = eglSwapBuffers"))
        assertFalse(swapBoundary.contains("glFinish()"))
        assertFalse(swapBoundary.contains("swapped == EGL_TRUE &&"))
    }

    @Test
    fun `input read fence follows the final external read on both render paths`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val submitBlock = nativeSource
            .substringAfter("nativeSubmitHardwareBuffer(")
            .substringBefore("nativeGetSummary(")
        val twoPassBlock = submitBlock
            .substringAfter("if (useDecodeIntermediate) {")
            .substringBefore("single-pass external record path ACTIVE")
        val decodePass = twoPassBlock.substringBefore("// PASS 2")
        val privateGradePass = twoPassBlock.substringAfter("// PASS 2")
        val directAndHistoryBlock = submitBlock
            .substringAfter("single-pass external record path ACTIVE")
            .substringBefore("if (presentationTimeNs > 0L)")
        val finalBoundaryBlock = submitBlock
            .substringAfter("if (projectOwnedInput) {")
            .substringBefore("const auto swapStart")

        assertTrue(decodePass.contains("glBindTexture(GL_TEXTURE_EXTERNAL_OES, state->sourceTexture)"))
        assertTrue(
            decodePass.indexOf("glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)") <
                decodePass.indexOf("establishProjectOwnedInputReadBoundary(\"DECODE_INTERMEDIATE\")")
        )
        assertTrue(privateGradePass.contains("glBindTexture(GL_TEXTURE_2D, state->decodeTexture)"))
        assertFalse(privateGradePass.contains("GL_TEXTURE_EXTERNAL_OES"))
        assertFalse(privateGradePass.contains("state->sourceTexture"))

        assertTrue(directAndHistoryBlock.contains("GL_TEXTURE_EXTERNAL_OES"))
        assertTrue(directAndHistoryBlock.contains("state->sourceTexture"))
        assertTrue(finalBoundaryBlock.contains("establishProjectOwnedInputReadBoundary(\"FINAL_EXTERNAL_READ\")"))
        assertTrue(
            submitBlock.lastIndexOf("glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)") <
                submitBlock.indexOf("establishProjectOwnedInputReadBoundary(\"FINAL_EXTERNAL_READ\")")
        )
    }

    @Test
    fun `offscreen surface retirement is transactional and failure propagates`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val kotlinSource = File("src/main/java/com/neoproc/neoproccamx/rendering/OffscreenAhbEglEncoderBridge.kt")
            .readText()
        val destroyBlock = nativeSource
            .substringAfter("bool destroySurface(OffscreenAhbEglEncoderState* state)")
            .substringBefore("bool destroyState(")
        val nativeSetSurfaceBlock = nativeSource
            .substringAfter("OffscreenAhbEglEncoderBridge_nativeSetEncoderSurface(")
            .substringBefore("OffscreenAhbEglEncoderBridge_nativeSetEglImageCacheEnabled(")
        val kotlinSetSurfaceBlock = kotlinSource
            .substringAfter("fun setEncoderSurface(")
            .substringBefore("fun setEncoderOutputFlip(")

        assertTrue(nativeSource.contains("bool destroySurface(OffscreenAhbEglEncoderState* state)"))
        assertTrue(destroyBlock.contains("if (eglMakeCurrent("))
        assertTrue(destroyBlock.contains("if (hasSurface && eglDestroySurface("))
        assertTrue(
            destroyBlock.indexOf("if (eglMakeCurrent(") <
                destroyBlock.indexOf("clearEglImageCache(state)")
        )
        assertTrue(
            destroyBlock.indexOf("eglDestroySurface(") <
                destroyBlock.indexOf("state->encoderSurface = EGL_NO_SURFACE")
        )
        assertTrue(
            destroyBlock.indexOf("eglDestroySurface(") <
                destroyBlock.indexOf("ANativeWindow_release(state->encoderWindow)")
        )
        assertTrue(
            Regex("""if \(!destroySurface\(state\)\)""")
                .findAll(nativeSetSurfaceBlock)
                .count() >= 2
        )
        assertTrue(nativeSetSurfaceBlock.contains("retains the rejected candidate"))
        assertTrue(kotlinSetSurfaceBlock.contains("val ok = nativeSetEncoderSurface("))
        assertTrue(kotlinSetSurfaceBlock.contains("return ok"))
    }

    @Test
    fun `final close retains native owner until every EGL retirement is acknowledged`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val kotlinSource = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/OffscreenAhbEglEncoderBridge.kt"
        ).readText()
        val destroyState = nativeSource
            .substringAfter("bool destroyState(OffscreenAhbEglEncoderState* state)")
            .substringBefore("} // namespace")
        val normalizedDestroyState = destroyState.replace(Regex("\\s+"), " ")
        val nativeDestroy = nativeSource
            .substringAfter("OffscreenAhbEglEncoderBridge_nativeDestroy(")
            .substringBefore("OffscreenAhbEglEncoderBridge_nativeSetCreativeLut(")
        val kotlinClose = kotlinSource
            .substringAfter("internal fun closeAcknowledged(): Boolean")
            .substringBefore("private external fun nativeCreate()")

        assertTrue(nativeSource.contains("extern \"C\" JNIEXPORT jboolean JNICALL"))
        assertTrue(destroyState.contains("if (!destroySurface(state))"))
        assertTrue(normalizedDestroyState.contains("final close ownership \" \"quarantined"))
        assertTrue(
            destroyState.indexOf("if (!destroySurface(state))") <
                destroyState.indexOf("eglDestroyContext(state->display, state->context)")
        )
        assertTrue(destroyState.contains("if (eglDestroyContext("))
        assertTrue(destroyState.contains("if (eglTerminate("))
        assertTrue(
            destroyState.lastIndexOf("return true;") > destroyState.indexOf("if (eglTerminate(")
        )
        assertTrue(nativeDestroy.contains("destroyed = destroyState(state)"))
        assertTrue(nativeDestroy.contains("if (destroyed)"))
        assertTrue(
            nativeDestroy.indexOf("if (destroyed)") < nativeDestroy.indexOf("delete state")
        )
        assertTrue(nativeDestroy.contains("exact owner retained for retry"))
        assertTrue(kotlinSource.contains("private external fun nativeDestroy(handle: Long): Boolean"))
        assertTrue(kotlinClose.contains("nativeCloseOwnership.beginClose()"))
        assertTrue(kotlinClose.contains("nativeCloseOwnership.completeClose(handle, acknowledged)"))
    }

    @Test
    fun `NDR measured shader avoids Adreno reserved active identifier`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val measuredCorrectionShader = nativeSource
            .substringAfter("vec3 neoApplyNdrMeasuredCorrection(vec3 inputColor)")
            .substringBefore("float neoApplyMeasuredHighlightRolloff")

        assertFalse(measuredCorrectionShader.contains("float active ="))
        assertTrue(measuredCorrectionShader.contains("float correctionActive ="))
        assertTrue(measuredCorrectionShader.contains("if (correctionActive <= 0.0)"))
    }

    @Test
    fun `sensor linear programs specialize proven PQ invariants and retain generic fallback`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val specialization = nativeSource
            .substringAfter("std::string makeSensorLinearFragment(")
            .substringBefore("void buildSensorLinearPrograms(")
        val build = nativeSource
            .substringAfter("void buildSensorLinearPrograms(")
            .substringBefore("// #2 decode-once: build the sampler2D main variant")
        val selection = nativeSource
            .substringAfter("const bool useSensorLinearSpecialized2d =")
            .substringBefore("// Retire any result that was not immediately available")
        val teardown = nativeSource
            .substringAfter("bool destroyState(OffscreenAhbEglEncoderState* state)")
            .substringBefore("extern \"C\" JNIEXPORT jlong JNICALL")

        // The canonical shader remains the single equation authority. Derived copies change only
        // runtime-proven declarations; the sampler2D copy additionally proves pre-grade history.
        assertTrue(nativeSource.contains("uniform int uInputTransfer;"))
        assertTrue(specialization.contains("dynamicDeclaration = \"uniform int uInputTransfer;\""))
        assertTrue(specialization.contains("sensorLinearDeclaration = \"const int uInputTransfer = 3;\""))
        assertTrue(specialization.contains("source.replace(first, dynamicDeclaration.size(), sensorLinearDeclaration)"))
        assertTrue(specialization.contains("expected one input-transfer declaration"))
        assertTrue(specialization.contains("const float uNdrSensorTonePqLutReady = 1.0;"))
        assertTrue(specialization.contains("const float uLutStrength = 0.0;"))
        assertTrue(build.contains("const float uHistoryPreGrade = 1.0;"))

        assertTrue(build.contains("linkVertexFragment(vertexSource, sensorExternal.c_str())"))
        assertTrue(build.contains("samplerExternalOES uTexture\", \"sampler2D uTexture"))
        assertTrue(build.contains("state->sensorLinearLocExternal = fetchMainLoc"))
        assertTrue(build.contains("state->sensorLinearLoc2d = fetchMainLoc"))

        // Only strict native transfer code 3 can select these programs. Code 4 Neo Scene Log10 keeps
        // its exp2 inverse in the generic program, and any compile failure falls back safely.
        assertTrue(selection.contains("inputIsSensorLinear && state->sensorLinearProgram2d != 0"))
        assertTrue(selection.contains("inputIsSensorLinear && state->sensorLinearProgramExternal != 0"))
        assertTrue(nativeSource.contains("sensorNdrTonePqLutReadyForFrame &&"))
        assertTrue(selection.contains("requestedTemporalBlend > 0.0f"))
        assertFalse(selection.contains("inputIsNeoSceneLog10"))
        assertTrue(nativeSource.contains("? state->sensorLinearProgram2d\n            : state->mainProgram2d"))
        assertTrue(nativeSource.contains("? state->sensorLinearProgramExternal\n        : (useExplicitYuv ? state->mainProgramExplicitYuv : state->program)"))

        assertTrue(teardown.contains("glDeleteProgram(state->sensorLinearProgramExternal)"))
        assertTrue(teardown.contains("glDeleteProgram(state->sensorLinearProgram2d)"))
        assertTrue(nativeSource.contains("sensorLinearSpecializedFrames="))
        assertTrue(nativeSource.contains("sensor-linear specialized shader frame committed"))
    }

    @Test
    fun `RAW EGL stage telemetry never adds a second GPU wait and reports real swap time`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val kotlinSource = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/OffscreenAhbEglEncoderBridge.kt"
        ).readText()
        val timingFlagSource = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/EglGpuStageTimingFlag.kt"
        ).readText()
        val submit = nativeSource
            .substringAfter("nativeSubmitHardwareBuffer(")
            .substringBefore("nativeGetSummary(")
        val destroySurface = nativeSource
            .substringAfter("bool destroySurface(OffscreenAhbEglEncoderState* state)")
            .substringBefore("bool destroyState(OffscreenAhbEglEncoderState* state)")
        val finishBoundary = submit
            .substringAfter("uint64_t finishDurationUs = 0")
            .substringBefore("const auto swapStart")
        val swapBoundary = submit
            .substringAfter("const auto swapStart")
            .substringBefore("const auto swapDurationUs")

        assertTrue(nativeSource.contains("GL_EXT_disjoint_timer_query"))
        assertTrue(nativeSource.contains("GL_QUERY_COUNTER_BITS_EXT"))
        assertTrue(nativeSource.contains("glGetQueryivEXT"))
        assertTrue(nativeSource.contains("glGetQueryObjectuivEXT"))
        assertTrue(nativeSource.contains("GL_QUERY_RESULT_AVAILABLE_EXT"))
        assertTrue(nativeSource.contains("GL_GPU_DISJOINT_EXT"))
        assertTrue(submit.contains("beginGpuStageTiming(state, GpuStage::Decode)"))
        assertTrue(submit.contains("beginGpuStageTiming(state, GpuStage::Grade)"))
        assertTrue(submit.contains("beginGpuStageTiming(state, GpuStage::History)"))
        assertTrue(finishBoundary.contains("glFinish()"))
        assertTrue(finishBoundary.contains("gpuStageSample = collectGpuStageTiming(state)"))
        assertFalse(swapBoundary.contains("glFinish()"))
        assertTrue(swapBoundary.contains("eglSwapBuffers"))
        assertTrue(nativeSource.contains("NDR_EGL_STAGE_TIMING"))
        assertTrue(nativeSource.contains("avgFinishUs="))
        assertTrue(nativeSource.contains("avgGpuGradeUs="))
        assertTrue(nativeSource.contains("gpuStageDisjointBatches="))
        assertTrue(nativeSource.contains("faultGpuStageTiming"))
        assertTrue(nativeSource.contains("frame submission unchanged"))

        // Production is never instrumented implicitly. Device QA opts in for one recording and any
        // driver error disables only telemetry, while the render/encode path remains unchanged.
        assertTrue(timingFlagSource.contains("var enabled: Boolean = false"))
        assertTrue(kotlinSource.contains("EglGpuStageTimingFlag.enabled &&"))
        assertTrue(kotlinSource.contains("preferredDeliveryMode != OffscreenEglDeliveryMode.PROJECT_PQ_TEXTURE"))
        assertTrue(submit.contains("state->gpuStageTimingSamplingEnabled"))
        assertTrue(submit.contains("!state->gpuStageTimingFaulted"))
        val timingAdmission = submit
            .substringAfter("const bool sampleGpuStages =")
            .substringBefore("uint64_t decodeHostUs")
        assertFalse(timingAdmission.contains("inputIsSensorLinear"))
        assertFalse(timingAdmission.contains("inputIsNeoSceneLog10"))
        assertTrue(nativeSource.contains("pre-glBeginQueryEXT"))
        assertTrue(nativeSource.contains("Always issue glEndQueryEXT first"))
        assertTrue(nativeSource.contains("delivery=%s surface=%dx%d source=%dx%d"))

        // A late result from the retired surface epoch can never enter the next session's statistics,
        // and its query name is not unsafely reused before the result becomes available.
        val retire = destroySurface.indexOf(
            "if (state->gpuStageQueryPending[index]) state->gpuStageQueryDiscard[index] = true"
        )
        val reset = destroySurface.indexOf(
            "for (DurationStats& stats : state->gpuStageStats) stats.reset()"
        )
        assertTrue(retire >= 0)
        assertTrue(reset > retire)
    }

    @Test
    fun `GatherGrid qualification switch preserves canonical fallback and is frozen per surface`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val kotlinSource = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/OffscreenAhbEglEncoderBridge.kt"
        ).readText()
        val flagSource = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/EglGatherGridFlag.kt"
        ).readText()
        val selection = nativeSource
            .substringAfter("const bool useSdrCreativeLutGatherGrid2d =")
            .substringBefore("const bool cameraP010SdrPrewarmUsesCanonicalDynamicLutProgram")

        assertTrue(flagSource.contains("var enabled: Boolean = false"))
        assertTrue(flagSource.contains("10.957 ms versus 12.030 ms"))
        assertTrue(kotlinSource.contains("nativeSetGatherGridEnabled(handle, EglGatherGridFlag.enabled)"))
        assertTrue(nativeSource.contains("nativeSetGatherGridEnabled("))
        assertTrue(selection.contains("state->neoGatherGridEnabled &&"))
        assertTrue(nativeSource.contains(": state->sdrCreativeLutProgram2d"))
        assertTrue(nativeSource.contains(": state->sdrCreativeLutNiv99Program2d"))
        assertTrue(nativeSource.contains("neoGatherGridEnabled="))
        assertTrue(nativeSource.contains("qualityReduction=false"))
    }

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
        assertTrue(nativeSource.contains("kSensorNdrTonePqLutSize = 2048"))
        assertTrue(nativeSource.contains("kSensorNdrTonePqLutRows"))
        assertTrue(nativeSource.contains("kSensorNdrToneSegmentBounds"))
        assertTrue(nativeSource.contains("kSensorNdrPqSegmentBounds"))
        assertTrue(nativeSource.contains("uniform highp sampler2D uNdrSensorTonePqLut"))
        assertTrue(nativeSource.contains("ensureSensorNdrTonePqLut"))
        assertTrue(nativeSource.contains("sensorTonePqLut=%s"))
        assertTrue(nativeSource.contains("sensorNdrTonePqLutReadyForFrame"))
        assertTrue(nativeSource.contains("NEO_SENSOR_TONE_LUT_MIN_SIGNAL = 0.000001"))
        assertTrue(nativeSource.contains("sourceY >= NEO_SENSOR_TONE_LUT_MIN_SIGNAL"))
        assertTrue(nativeSource.contains("sourceY <= NEO_SENSOR_TONE_DOMAIN"))
        assertTrue(nativeSource.contains("neoSensorSegmentedLutCoord"))
        assertTrue(nativeSource.contains("sensorLutHighResidual"))
        assertTrue(nativeSource.contains("NEO_SENSOR_TONE_PQ_LUT_ROWS = 17.0"))
        assertTrue(nativeSource.contains("(value - segment.y) * segment.z"))
        assertFalse(nativeSource.contains("(value - segment.y) /"))
        assertTrue(nativeSource.contains("mappedSignal[0]"))
        assertTrue(nativeSource.contains("absoluteNits[0]"))
        assertTrue(nativeSource.contains("data[0] = 0.0f"))
        assertTrue(nativeSource.contains("data[pqBlack] = 0.0f"))
        assertTrue(nativeSource.contains("sensorTone.r + sensorTone.g"))
        assertTrue(nativeSource.contains("encoded.r + encoded.g"))
        assertTrue(nativeSource.contains("sensorTone.b + sensorTone.a"))
        assertTrue(nativeSource.contains("bool sensorColorPathClipped = useSensorTonePqLut"))
        assertTrue(nativeSource.contains("bool useCachedSensorNits = useSensorTonePqLut && !sensorColorPathClipped"))
        assertTrue(nativeSource.contains("float nits = useCachedSensorNits"))
        assertFalse(nativeSource.contains("(sensorTone.b + sensorTone.a) * peak"))
        assertTrue(nativeSource.contains("neoApplyMeasuredHighlightRolloff(preRolloffY)"))
        assertTrue(nativeSource.contains("ndrSensorTonePqLutMapping=PIECEWISE_LINEAR_ABSOLUTE_HIGH_RESIDUAL"))
        assertFalse(nativeSource.contains("float shaped = sqrt(sqrt(normalized))"))
        assertTrue(nativeSource.contains("neoSampleSensorPq(peakSafe.r)"))
        assertTrue(nativeSource.contains("neoDelPqFromNits(peakSafe.r)"))
        assertTrue(nativeSource.contains("neoDeliverPqFromInput(finalRgb, uNdrDeliveryMode)"))
        assertTrue(nativeSource.contains("uInputTransfer == 3"))
        assertTrue(nativeSource.contains("neoApplyNdrMeasuredCorrection"))
        assertTrue(nativeSource.contains("uniform vec4 uNdrMeasuredRgbScale"))
        assertTrue(kotlinSource.contains("NdrWarmSkinRedTameFlag.effectiveStrength"))
        assertTrue(nativeSource.contains("uniform float uNdrWarmSkinRedTame"))
        assertTrue(nativeSource.contains("moderateRed"))
        assertTrue(nativeSource.contains("bluePresent"))
        assertTrue(nativeSource.contains("corrected * (oy / correctedY)"))
        assertTrue(nativeSource.contains("uNdrWarmSkinRedTame <= 0.0"))
        assertTrue(nativeSource.contains("angle2 * angle2"))
        assertFalse(nativeSource.contains("float cs = cos(angle)"))
        assertFalse(nativeSource.contains("float sn = sin(angle)"))
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
        assertTrue(nativeSource.contains("kEglImageCacheCapacity = camx::EglImageResidencyPolicy::kCapacity"))
        assertTrue(nativeSource.contains("EglImageResidencyPolicy::needsEviction"))
        assertTrue(nativeSource.contains("EglImageResidencyPolicy::canAdmitWithoutEviction"))
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
        assertTrue(submitBlock.contains("OffscreenEncoderFramePacingPolicy.resolveOneToOne("))
        assertFalse(submitBlock.contains("OffscreenEncoderFramePacingPolicy.resolve("))
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

    @Test
    fun `offscreen NIV99 packet is exact frame validated and resets temporal history`() {
        val kotlinSource = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/OffscreenAhbEglEncoderBridge.kt"
        ).readText()
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val kotlinAbi = kotlinSource
            .substringAfter("private external fun nativeSubmitHardwareBuffer(")
            .substringBefore("): Boolean")
        val nativeAbi = nativeSource
            .substringAfter("OffscreenAhbEglEncoderBridge_nativeSubmitHardwareBuffer(")
            .substringBefore(") {")
        val nativeSubmit = nativeSource
            .substringAfter("OffscreenAhbEglEncoderBridge_nativeSubmitHardwareBuffer(")
            .substringBefore("OffscreenAhbEglEncoderBridge_nativeGetSummary(")

        val kotlinOrder = listOf(
            "presentationTimeNs", "sensorTimestampNs", "niv99PacketValid",
            "niv99PixelApplicationApproved", "niv99CameraIdHash", "niv99SessionGeneration",
            "niv99FrameNumber", "niv99SensorTimestampNs", "niv99Authority",
            "niv99RedCorrection", "niv99GreenCorrection", "niv99BlueCorrection", "niv99Confidence"
        )
        val nativeOrder = listOf(
            "presentationTimeNs", "sensorTimestampNs", "niv99PacketValid",
            "niv99PixelApplicationApproved", "niv99CameraIdHash", "niv99SessionGeneration",
            "niv99FrameNumber", "niv99SensorTimestampNs", "niv99Authority",
            "niv99RedCorrection", "niv99GreenCorrection", "niv99BlueCorrection", "niv99Confidence"
        )
        assertTrue(kotlinOrder.zipWithNext().all { (a, b) -> kotlinAbi.indexOf(a) < kotlinAbi.indexOf(b) })
        assertTrue(nativeOrder.zipWithNext().all { (a, b) -> nativeAbi.indexOf(a) < nativeAbi.indexOf(b) })
        assertTrue(nativeSubmit.contains("const bool exactImageTimestamp"))
        assertTrue(nativeSubmit.contains("const bool niv99TimelineChanged"))
        assertTrue(nativeSubmit.contains("state->historyValid = false"))
        assertTrue(nativeSubmit.contains("state->decodeHistoryValid = false"))
        assertTrue(nativeSubmit.contains("NIV99_HISTORY_RESET"))
        assertTrue(nativeSubmit.contains("NIV99_PIXEL_APPLIED_ENCODER"))
    }

    @Test
    fun `offscreen NIV99 applies whole frame WB with identity for manual authority`() {
        val kotlinSource = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/OffscreenAhbEglEncoderBridge.kt"
        ).readText()
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()

        assertTrue(kotlinSource.contains("Niv99WhiteBalanceAuthority.NIV99_AUTO"))
        assertTrue(kotlinSource.contains("else 1f"))
        assertTrue(nativeSource.contains("vec3 niv99ApplyWhiteBalance(vec3 inputColor)"))
        assertTrue(nativeSource.contains("return niv99ApplyWhiteBalance(neoDecodeIngress"))
        assertTrue(nativeSource.contains("vec3 histSource = niv99ApplyWhiteBalance("))
        assertTrue(nativeSource.contains("const bool userWhiteBalancePreserved"))
        assertTrue(nativeSource.contains("uNiv99ActiveConfidence"))
    }

    @Test
    fun `normal SDR specialization removes only frame proven identity work`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val specialization = nativeSource
            .substringAfter("std::string makeSdrInvariantFragment(")
            .substringBefore("void buildSdrInvariantPrograms(")
        val selection = nativeSource
            .substringAfter("const bool sdrPreGradeHistoryProven =")
            .substringBefore("// Compile-time PQ/profile invariants")
        val submit = nativeSource
            .substringAfter("OffscreenAhbEglEncoderBridge_nativeSubmitHardwareBuffer(")
            .substringBefore("OffscreenAhbEglEncoderBridge_nativeGetSummary(")

        // The specialization is derived from the canonical shader; active quality controls stay
        // uniform-driven and therefore retain the exact denoise, sharpen, grade and temporal
        // equations. Only values proved to be identity for this exact frame become constants. The
        // LUT branch is specialized only behind the exact-zero runtime admission guard below.
        assertTrue(specialization.contains("std::string source(canonicalSource)"))
        assertTrue(specialization.contains("const int uInputTransfer = 0;"))
        assertTrue(specialization.contains("const float uHdrToPqConvert = 0.0;"))
        assertTrue(specialization.contains("const float uNiv99ActiveConfidence = 0.0;"))
        assertTrue(specialization.contains("const float uHistoryPreGrade = 1.0;"))
        assertFalse(specialization.contains("const float uSharpness"))
        assertFalse(specialization.contains("const float uDenoise"))
        assertFalse(specialization.contains("const float uChromaDenoise"))
        assertFalse(specialization.contains("const float uTemporalBlend"))
        assertFalse(specialization.contains("const mat3 uGradeMatrix"))
        assertTrue(specialization.contains("const float uLutStrength = 0.0;"))

        // Integer cardinal fetches are legal only on the equal-size decode-once path. The
        // NIV99-active variant keeps only confidence/RGB correction dynamic. HDR/RAW transfer,
        // failed compile or unmatched extent falls back to the canonical program instead of
        // silently changing pixels or dropping quality.
        assertTrue(specialization.contains("textureOffset(uTexture, uv, ivec2(1, 0)).rgb"))
        assertTrue(selection.contains("state->deliveryModeLabel == \"SDR\""))
        assertTrue(selection.contains("inputTransfer == 0"))
        assertTrue(selection.contains("applyNdrPqDeliveryToneMap == 0.0f"))
        assertTrue(selection.contains("niv99ActiveConfidenceValue == 0.0f"))
        assertTrue(selection.contains("state->lutStrength == 0.0f"))
        assertTrue(selection.contains("sdrCreativeLutBypassProven"))
        assertTrue(
            selection.indexOf("sdrCreativeLutBypassProven &&") !=
                selection.lastIndexOf("sdrCreativeLutBypassProven &&")
        )
        assertTrue(specialization.contains("bool keepNiv99Dynamic = false"))
        assertTrue(specialization.contains("bool keepCreativeLutDynamic = false"))
        assertTrue(specialization.contains("if (!keepNiv99Dynamic"))
        assertTrue(specialization.contains("if (!keepCreativeLutDynamic"))
        assertTrue(selection.contains("sdrNiv99SpecializationEligible"))
        assertTrue(selection.contains("niv99ActiveForFrame"))
        assertTrue(selection.contains("niv99ActiveConfidenceValue > 0.0f"))
        assertTrue(selection.contains("sourceWidth == targetWidth && sourceHeight == targetHeight"))
        assertTrue(submit.contains("? state->sdrInvariantProgram2d"))
        assertTrue(submit.contains("? state->sdrInvariantProgramExternal"))
        assertTrue(submit.contains("sdrInvariantSpecializationFallbackFrames"))
        assertTrue(submit.contains("sdrNiv99SpecializedFrames"))
        assertTrue(submit.contains("sdrNiv99SpecializationFallbackFrames"))
        assertTrue(nativeSource.contains("qualityEquations=CANONICAL"))
        assertTrue(nativeSource.contains("glDeleteProgram(state->sdrInvariantProgramExternal)"))
        assertTrue(nativeSource.contains("glDeleteProgram(state->sdrInvariantProgram2d)"))
        assertTrue(nativeSource.contains("glDeleteProgram(state->sdrNiv99ProgramExternal)"))
        assertTrue(nativeSource.contains("glDeleteProgram(state->sdrNiv99Program2d)"))
        assertTrue(nativeSource.contains("makeSdrInvariantFragment(canonicalExternalFragment, true, false, true)"))
        assertTrue(nativeSource.contains("makeSdrInvariantFragment(canonicalExternalFragment, true, true, true)"))
        assertTrue(selection.contains("sdrCreativeLutSpecializationEligible"))
        assertTrue(selection.contains("sdrCreativeLutNiv99SpecializationEligible"))
        assertTrue(selection.contains("state->lutStrength > 0.0f"))
        assertTrue(submit.contains("? state->sdrCreativeLutGatherProgram2d"))
        assertTrue(submit.contains(": state->sdrCreativeLutProgram2d"))
        assertTrue(submit.contains("? state->sdrCreativeLutNiv99GatherProgram2d"))
        assertTrue(submit.contains(": state->sdrCreativeLutNiv99Program2d"))
        assertTrue(submit.contains("sdrCreativeLutSpecializedFrames"))
        assertTrue(submit.contains("sdrCreativeLutNiv99SpecializedFrames"))
        assertTrue(nativeSource.contains("glDeleteProgram(state->sdrCreativeLutProgramExternal)"))
        assertTrue(nativeSource.contains("glDeleteProgram(state->sdrCreativeLutNiv99Program2d)"))
    }

    @Test
    fun `Camera2 P010 prewarm preserves an active creative LUT on specialized shader with fallback`() {
        val nativeSource = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val submit = nativeSource
            .substringAfter("OffscreenAhbEglEncoderBridge_nativeSubmitHardwareBuffer(")
            .substringBefore("OffscreenAhbEglEncoderBridge_nativeGetSummary(")
        val admission = submit
            .substringAfter("const bool cameraP010SdrPrewarmHasExactGradeProgram =")
            .substringBefore("const bool exactPrewarmContract =")
        val selection = submit
            .substringAfter("const bool cameraP010SdrPrewarmUsesCanonicalDynamicLutProgram =")
            .substringBefore("// Compile-time PQ/profile invariants")
        val gradePass = submit
            .substringAfter("// Lazy 3D-LUT upload (idempotent; same guard as the external path).")
            .substringBefore("}  // end external-path else")

        // Zero LUT uses the identity program. A non-zero/pending LUT prefers the quality-equivalent
        // dynamic-LUT specialization and retains the canonical shader as a compile-failure fallback.
        // Both paths upload, bind, and apply the exact texture, so speed cannot replace the colour look.
        assertTrue(admission.contains("state->mainProgram2d != 0"))
        assertTrue(admission.contains("state->lutStrength != 0.0f || state->sdrInvariantProgram2d != 0"))
        assertTrue(selection.contains("!sdrCreativeLutBypassProven"))
        assertTrue(selection.contains("!useSdrCreativeLutSpecialized2d"))
        assertTrue(selection.contains("state->mainProgram2d != 0"))
        assertTrue(selection.contains("useSdrInvariantSpecialized2d ||"))
        assertTrue(selection.contains("useSdrCreativeLutSpecialized2d ||"))
        assertTrue(selection.contains("cameraP010SdrPrewarmUsesCanonicalDynamicLutProgram"))
        assertTrue(gradePass.contains("if (state->lutDirty && state->lutSize > 1"))
        assertTrue(gradePass.contains("glTexImage3D(GL_TEXTURE_3D, 0, GL_RGB16F"))
        assertTrue(gradePass.contains("glBindTexture(GL_TEXTURE_3D, state->lutTexture != 0"))
        assertTrue(gradePass.contains("std::clamp(state->lutStrength, 0.0f, 1.0f)"))
        assertTrue(submit.contains("sdr-specialized-dynamic-lut"))
        assertTrue(submit.contains("canonical-dynamic-lut-fallback"))
        assertTrue(submit.contains("identityGradeProgram=%s niv99GradeProgram=%s"))
        assertTrue(submit.contains("creativeLutStrength=%.6f"))
    }
}
