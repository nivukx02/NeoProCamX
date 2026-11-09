package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PreviewStabilityArchitectureContractTest {
    private val viewModelSource = File(
        "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
    ).readText()
    private val controllerSource = File(
        "src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt"
    ).readText()
    private val preparationSource = File(
        "src/main/java/com/neoproc/neoproccamx/ui/PersistentPreviewPreparationCoordinator.kt"
    ).readText()
    private val lifecycleSource = File(
        "src/main/java/com/neoproc/neoproccamx/ui/PersistentPreviewLifecycleCoordinator.kt"
    ).readText()
    private val strictNativeProfileSource = File(
        "src/main/java/com/neoproc/neoproccamx/ui/StrictNativePreviewProfileCoordinator.kt"
    ).readText()

    @Test
    fun `persistent mode handoff selects a stable output and never transfers the window producer`() {
        val preparationBlock = preparationSource
            .substringAfter("fun schedule(")
            .substringBefore("private fun activateIfCurrent(")
        val applyBlock = viewModelSource
            .substringAfter("private fun applyPersistentPreviewPipeline(")
            .substringBefore("private fun beginPersistentPreviewHandoff(")
        val completionBlock = lifecycleSource
            .substringAfter("private fun completeHandoff(")
            .substringBefore("private fun validEpoch(")

        assertTrue(preparationBlock.contains("scope.launch(Dispatchers.Default)"))
        assertTrue(preparationBlock.contains("router.startWithResult("))
        assertTrue(preparationBlock.contains("sdr.start(sdrOutput"))
        assertTrue(preparationBlock.contains("hdr.start("))
        assertTrue(preparationBlock.contains("awaitResourceRelease()"))
        assertFalse(applyBlock.contains("persistentPreviewOutputRouter.start("))
        assertTrue(applyBlock.contains("schedulePersistentPreviewPipelinePreparation("))
        assertTrue(applyBlock.contains("persistentPreviewVisibleSurface !== visibleSurface"))
        assertTrue(applyBlock.contains("camera.persistentPreviewOutputsAvailable()"))
        assertTrue(viewModelSource.contains("selectOutput = { owner -> persistentPreviewOutputRouter.selectSource("))
        assertTrue(viewModelSource.contains("completeCameraHandoff = camera::completePersistentPreviewHandoff"))
        assertTrue(completionBlock.contains("selectOutput(completedOwner)"))
        assertTrue(completionBlock.contains("completeCameraHandoff(incoming)"))
        assertFalse(completionBlock.contains("detachOutput()"))
        assertFalse(completionBlock.contains("eglCreateWindowSurface"))
    }

    @Test
    fun `strict preview preparation cannot bind a direct SDR fallback`() {
        val preparationBlock = preparationSource
            .substringAfter("fun schedule(")
            .substringBefore("private fun activateIfCurrent(")
        val applyBlock = viewModelSource
            .substringAfter("private fun applyPersistentPreviewPipeline(")
            .substringBefore("private fun shouldCompositeHdr10PqPreview(")

        assertTrue(preparationBlock.contains("onStrictOutputRejected(startResult)"))
        assertTrue(preparationBlock.contains("camera.setPreviewSurface(null)"))
        assertFalse(preparationBlock.contains("camera.setPreviewSurface(request.visibleSurface)"))
        assertFalse(preparationBlock.contains("onDirectFallback"))
        assertFalse(applyBlock.contains("using single-preview fallback"))
        assertTrue(applyBlock.contains("direct fallback blocked"))
        assertTrue(applyBlock.contains("rejectStrictPreviewOutput("))
        assertTrue(applyBlock.contains("return true"))
        assertTrue(viewModelSource.contains("strictPreviewOutputFailure == null"))
    }

    @Test
    fun `recording waits for an active strict preview proof`() {
        val validationBlock = viewModelSource
            .substringAfter("private fun validateActiveVideoModeAdmissionForRecording()")
            .substringBefore("private fun activeVideoPreviewLookState()")
        val readinessBlock = viewModelSource
            .substringAfter("fun isRecordingAdmissionReadyForStart(): Boolean")
            .substringBefore("fun startRecording()")
        val preparationBlock = viewModelSource
            .substringAfter("private fun schedulePersistentPreviewPipelinePreparation(")
            .substringBefore("private fun applyPersistentPreviewPipeline(")

        assertTrue(validationBlock.contains("StrictPreviewAdmissionPolicy.recordingAllowed("))
        assertTrue(validationBlock.contains("strictPreviewAdmissionState"))
        assertTrue(readinessBlock.contains("StrictPreviewAdmissionPolicy.recordingAllowed("))
        assertTrue(preparationBlock.contains("markStrictPreviewAdmissionPending()"))
        assertTrue(
            preparationBlock.contains(
                "activateStrictPreviewAdmission(StrictPreviewAdmissionState.ACTIVE_PERSISTENT)"
            )
        )
    }

    @Test
    fun `native preview must prove actual precision or retry strict persistent output`() {
        val bindingBlock = viewModelSource
            .substringAfter("private fun applyPreviewPipelineBinding(")
            .substringBefore("private fun bindPreviewSurface(")
        val nativeProof = bindingBlock.indexOf("VulkanPreviewSurfaceStatusParser.parse(")
        val nativeDetach = bindingBlock.indexOf("vulkanRuntime.setPreviewSurface(null)", nativeProof)
        val persistentAttempt = bindingBlock.indexOf(
            "applyPersistentPreviewPipeline(surface, previewSize, nativeAttemptStillEligible)",
            nativeDetach
        )
        val directGuard = bindingBlock.indexOf(
            "StrictPreviewAdmissionPolicy.required(_uiMode.value)",
            persistentAttempt
        )

        assertTrue(nativeProof >= 0)
        assertTrue(bindingBlock.contains("nativePrecision.strictTenBitOrBetterProven"))
        assertTrue(nativeDetach > nativeProof)
        assertTrue(persistentAttempt > nativeDetach)
        assertTrue(directGuard > persistentAttempt)
        assertTrue(
            bindingBlock.contains(
                "activateStrictPreviewAdmission(StrictPreviewAdmissionState.ACTIVE_NATIVE)"
            )
        )
        assertTrue(
            viewModelSource.contains(
                "revalidateStrictNativePreviewForProfile(renderProfilePacket.identity)"
            )
        )
        val revalidationBlock = strictNativeProfileSource
            .substringAfter("fun revalidate(identity: NdrRenderProfileIdentity): Boolean")
            .substringBefore("private fun executeFallback(")
        val fallbackWiring = viewModelSource
            .substringAfter("private val strictNativePreviewProfileCoordinator")
            .substringBefore("@Volatile private var strictPreviewOutputFailure")
        val renderPush = viewModelSource
            .substringAfter("private fun pushVulkanPreviewRenderConfig(")
            .substringBefore("private fun resolveGpuColorProfile")
        val preparationBlock = viewModelSource
            .substringAfter("private fun schedulePersistentPreviewPipelinePreparation(")
            .substringBefore("private fun applyPersistentPreviewPipeline(")
        val stage = renderPush.indexOf("NdrRenderProfileLifecycleCoordinator.commitForFrameIngress(")
        val precisionProof = renderPush.indexOf(
            "revalidateStrictNativePreviewForProfile(renderProfilePacket.identity)"
        )
        val finalize = renderPush.indexOf("NdrRenderProfileLifecycleCoordinator.finalizeFrameIngress(")
        assertTrue(revalidationBlock.contains("runtime.refreshCapabilitySummary"))
        assertTrue(strictNativeProfileSource.contains("StrictPreviewAdmissionPolicy.profileFinalizationGate("))
        val retainedIngressBranch = revalidationBlock
            .substringAfter("StrictPreviewProfileFinalizationGate.RETAIN_FRAME_INGRESS -> {")
            .substringBefore("StrictPreviewProfileFinalizationGate.REQUIRE_NATIVE_PRECISION_PROOF")
        assertTrue(retainedIngressBranch.contains("reportDeferral("))
        assertTrue(retainedIngressBranch.contains("return false"))
        assertTrue(revalidationBlock.contains("scheduledFallback.compareAndSet(null, token)"))
        assertTrue(revalidationBlock.contains("runtime.runOnOwnerThread"))
        assertTrue(strictNativeProfileSource.contains("runtime.detachExactNativeSurface(token.identity)"))
        assertTrue(strictNativeProfileSource.contains("finalizationProof.get() == FinalizationProof("))
        assertTrue(strictNativeProfileSource.contains("runtime.frameIngressEpoch()"))
        assertTrue(fallbackWiring.contains("detachPreviewSurfaceForRenderProfileIdentity"))
        assertTrue(fallbackWiring.contains("preferNativePreviewOverride = false"))
        assertFalse(revalidationBlock.contains("finalizeNdrRenderProfilePacket("))
        assertTrue(stage >= 0 && stage < precisionProof && precisionProof < finalize)
        assertTrue(renderPush.contains("strictPreviewProfileProofStillCurrent(renderProfilePacket.identity)"))
        assertTrue(
            preparationBlock.indexOf(
                "activateStrictPreviewAdmission(StrictPreviewAdmissionState.ACTIVE_PERSISTENT)"
            ) < preparationBlock.indexOf(
                "enqueueVulkanPreviewRenderConfig(camera.frameGeometry.value, force = true)"
            )
        )
    }

    @Test
    fun `camera open waits for off-main persistent preview preparation`() {
        val openBlock = viewModelSource
            .substringAfter("fun openCamera()")
            .substringBefore("fun resumeCameraForForeground()")
        val closeBarrier = openBlock.indexOf("if (openDecision.shouldWaitForClose")
        val preparationGate = openBlock.indexOf("persistentPreviewPreparation.inProgress", closeBarrier)
        val bindSurface = openBlock.indexOf("bindPreviewSurface(surface)", preparationGate)
        val postBindPreparationGate = openBlock.indexOf(
            "persistentPreviewPreparation.inProgress",
            bindSurface
        )
        val cameraOpen = openBlock.indexOf("camera.openCamera(")

        assertTrue(closeBarrier >= 0)
        assertTrue(preparationGate > closeBarrier)
        assertTrue(bindSurface > preparationGate)
        assertTrue(postBindPreparationGate > bindSurface)
        assertTrue(preparationGate >= 0)
        assertTrue(cameraOpen > postBindPreparationGate)
        assertTrue(openBlock.contains("openCameraAfterPersistentPreviewPreparation = true"))
    }

    @Test
    fun `recording surface detach removes persistent outputs before rebuilding and releases after close`() {
        val block = viewModelSource
            .substringAfter("private fun clearPreviewSurfaceForActiveRecording()")
            .substringBefore("private fun preferredPreviewSize()")
        val unregister = block.indexOf("disablePersistentPreviewPipeline(releaseInputs = false)")
        val refresh = block.indexOf("camera.refreshOutputsAndSession(")
        val delayedRelease = block.indexOf("disablePersistentPreviewPipeline(releaseInputs = true)")

        assertTrue(unregister >= 0)
        assertTrue(refresh > unregister)
        assertTrue(delayedRelease > unregister)
        assertTrue(block.contains("onPreviousSessionClosed = completeDetach"))
    }

    @Test
    fun `normal camera close keeps persistent consumers alive until Camera2 is closed`() {
        val prepareBlock = viewModelSource
            .substringAfter("private fun prepareCameraCloseState(")
            .substringBefore("fun suspendCameraForSettings()")
        val closeBlock = viewModelSource
            .substringAfter("private fun closeCameraInternal(")
            .substringBefore("fun handleAppBackgrounded()")
        val cameraClose = closeBlock.indexOf("camera.closeCamera()")
        val presentationRelease = closeBlock.indexOf("releasePreviewPresentationResources()", cameraClose)

        assertTrue(prepareBlock.contains("disablePersistentPreviewPipeline(releaseInputs = false)"))
        assertTrue(cameraClose >= 0)
        assertTrue(presentationRelease > cameraClose)
        assertTrue(closeBlock.contains("withContext(Dispatchers.Main.immediate)"))
    }

    @Test
    fun `persistent child producers retire before their router queues`() {
        val releaseBlock = preparationSource
            .substringAfter("private suspend fun releaseResources()")
            .substringBefore("private companion object")
        val childRelease = releaseBlock.indexOf("sdr.releaseAsync()")
        val childrenFinished = releaseBlock.indexOf("!sdr.isReleasing() && !hdr.isReleasing()")
        val routerRelease = releaseBlock.indexOf("router.release()")

        assertTrue(childRelease >= 0)
        assertTrue(childrenFinished > childRelease)
        assertTrue(routerRelease > childrenFinished)
        assertFalse(releaseBlock.contains("router.releaseAsync()"))
    }

    @Test
    fun `visible router owns one window surface and consumes both child queues`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/PersistentPreviewOutputRouter.kt"
        ).readText()
        val selectBlock = source
            .substringAfter("fun selectSource(source: Int): Boolean")
            .substringBefore("fun isRunning()")

        assertTrue(source.contains("eglCreateWindowSurface("))
        assertTrue(source.contains("private const val SOURCE_COUNT = 2"))
        assertTrue(source.contains("childOutputSurfaces[source] = Surface(surfaceTexture)"))
        assertTrue(source.contains("for (source in 0 until SOURCE_COUNT)"))
        assertTrue(selectBlock.contains("selectionDirty = true"))
        assertFalse(selectBlock.contains("eglDestroySurface"))
        assertFalse(selectBlock.contains("eglCreateWindowSurface"))
    }

    @Test
    fun `visible router publishes no synthetic buffer before a real child frame`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/PersistentPreviewOutputRouter.kt"
        ).readText()
        val sdrSource = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/PreviewGlCompositor.kt"
        ).readText()
        val initBlock = source
            .substringAfter("private fun initGl(output: Surface, width: Int, height: Int)")
            .substringBefore("private fun drawFrame()")
        val drawBlock = source
            .substringAfter("private fun drawFrame()")
            .substringBefore("private fun destroyGl()")
        val sdrDrawBlock = sdrSource
            .substringAfter("private fun drawFrame()")
            .substringBefore("private fun uploadCreativeLutIfDirty()")

        assertFalse(initBlock.contains("eglSwapBuffers"))
        assertTrue(drawBlock.contains("if (!frameReady[selected]"))
        assertTrue(drawBlock.contains("eglSwapBuffers"))
        assertTrue(sdrDrawBlock.contains("if (!validInputFrameLatched) return"))
        assertTrue(preparationSource.contains("publishInitialBlack = false"))
    }

    @Test
    fun `visible router rejects stale layout-sized EGL buffers before first swap`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/PersistentPreviewOutputRouter.kt"
        ).readText()
        val initBlock = source
            .substringAfter("private fun initGl(output: Surface, width: Int, height: Int)")
            .substringBefore("private fun createSizedWindowSurface(")
        val settleBlock = source
            .substringAfter("private fun createSizedWindowSurface(")
            .substringBefore("private fun drawFrame()")
        val sizeVerified = settleBlock.indexOf("lastWidth == expectedWidth")
        val accepted = settleBlock.indexOf("surface = candidate")
        val staleSurfaceDestroyed = settleBlock.indexOf("eglDestroySurface(eglDisplay, candidate)")

        assertTrue(initBlock.contains("createSizedWindowSurface("))
        assertTrue(settleBlock.contains("EGL14.eglQuerySurface("))
        assertTrue(sizeVerified >= 0)
        assertTrue(accepted > sizeVerified)
        assertTrue(staleSurfaceDestroyed > accepted)
        assertFalse(settleBlock.contains("eglSwapBuffers"))
    }

    @Test
    fun `strict window geometry has a thermal-safe bounded settle budget`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/PersistentPreviewOutputRouter.kt"
        ).readText()
        val attempts = Regex("WINDOW_SIZE_SETTLE_ATTEMPTS = (\\d+)")
            .find(source)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val retryMs = Regex("WINDOW_SIZE_SETTLE_RETRY_MS = (\\d+)L")
            .find(source)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val startTimeoutMs = Regex("START_TIMEOUT_MS = (\\d+)_?(\\d*)L")
            .find(source)?.groupValues
            ?.let { values -> (values[1] + values[2]).toLongOrNull() }
            ?: 0L
        val maximumSleepBudgetMs = (attempts - 1L).coerceAtLeast(0L) * retryMs

        assertTrue("BLAST settle budget must cover thermal scheduling delay", maximumSleepBudgetMs >= 1_000L)
        assertTrue(
            "Outer startup timeout must leave headroom beyond geometry polling",
            startTimeoutMs >= maximumSleepBudgetMs + 1_000L
        )
        assertTrue(source.contains("eglDestroySurface(eglDisplay, candidate)"))
        assertFalse(
            source.substringAfter("private fun createSizedWindowSurface(")
                .substringBefore("private fun drawFrame()")
                .contains("eglSwapBuffers")
        )
    }

    @Test
    fun `preview stream geometry is committed before strict EGL ownership is acquired`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()
        val bindingBlock = source
            .substringAfter("private fun bindPreviewSurface(")
            .substringBefore("internal fun refreshPreviewPipelineIfNeeded(")
        val publishGeometry = bindingBlock.indexOf("previewViewRef?.setStreamGeometry(")
        val acquireOutput = bindingBlock.indexOf("val activeNativePreview = applyPreviewPipelineBinding(")

        assertTrue(publishGeometry >= 0)
        assertTrue(acquireOutput > publishGeometry)
        assertEquals(1, Regex("previewViewRef\\?\\.setStreamGeometry\\(").findAll(bindingBlock).count())
    }

    @Test
    fun `persistent SDR router removes only flat shadow chroma and bypasses HDR`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/PersistentPreviewOutputRouter.kt"
        ).readText()

        assertTrue(source.contains("uSdrShadowChromaCleanup"))
        assertTrue(source.contains("if (selected == SOURCE_SDR) 1f else 0f"))
        assertTrue(source.contains("vec3 toYCoCg"))
        assertTrue(source.contains("vec3(centre.x, cleanChroma)"))
        assertTrue(source.contains("flatShadow"))
        assertTrue(source.contains("trueColour"))
    }

    @Test
    fun `persistent owner changes retarget when recording or profiles cannot coexist`() {
        val startPreviewBlock = controllerSource
            .substringAfter("override fun startPreview(expectedGeneration: Long, expectedSession: CameraCaptureSession?)")
            .substringBefore("private fun identitySurfaceSet(")
        val beginBlock = controllerSource
            .substringAfter("fun beginPersistentPreviewHandoff(incomingSurface: Surface): Boolean")
            .substringBefore("fun completePersistentPreviewHandoff(incomingSurface: Surface): Boolean")
        val completeBlock = controllerSource
            .substringAfter("fun completePersistentPreviewHandoff(incomingSurface: Surface): Boolean")
            .substringBefore("fun cancelPersistentPreviewHandoff()")
        val cancelBlock = controllerSource
            .substringAfter("fun cancelPersistentPreviewHandoff()")
            .substringBefore("override fun setPreviewSurface(surface: Surface?)")

        assertTrue(startPreviewBlock.contains("PersistentPreviewRepeatingTargetPolicy.resolve("))
        assertTrue(startPreviewBlock.contains("persistentSdrPreview = persistentSdrPreviewSurface"))
        assertTrue(startPreviewBlock.contains("persistentHdrPreview = persistentHdrPreviewSurface"))
        assertTrue(startPreviewBlock.contains("profilesCanShareRequest = persistentPreviewProfilesCanShareRequest"))
        assertTrue(startPreviewBlock.contains("recordingActive = recordingActive"))
        assertFalse(startPreviewBlock.contains("previewWarmupSurface?.let"))
        listOf(beginBlock, cancelBlock).forEach { constrainedHandoffBlock ->
            assertTrue(
                constrainedHandoffBlock.contains(
                    "PersistentPreviewRepeatingTargetPolicy.requiresHandoffRetarget("
                )
            )
            assertTrue(constrainedHandoffBlock.contains("recordingActive = recordingActive"))
            assertTrue(constrainedHandoffBlock.contains("startPreview("))
        }
        listOf(beginBlock, completeBlock, cancelBlock).forEach { handoffBlock ->
            assertFalse(handoffBlock.contains("setRepeatingRequest("))
        }
        assertFalse(completeBlock.contains("startPreview("))
    }

    @Test
    fun `inactive persistent compositor drains buffers without full presentation work`() {
        val sdrSource = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/PreviewGlCompositor.kt"
        ).readText()
        val hdrSource = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/Hdr10PqPreviewCompositor.kt"
        ).readText()
        val sdrDraw = sdrSource
            .substringAfter("private fun drawFrame()")
            .substringBefore("private fun uploadCreativeLutIfDirty()")
        val hdrDraw = hdrSource
            .substringAfter("private fun drawFrame()")
            .substringBefore("private fun destroyGl()")

        assertTrue(sdrSource.contains("fun setFramePresentationEnabled(enabled: Boolean)"))
        assertTrue(hdrSource.contains("fun setFramePresentationEnabled(enabled: Boolean)"))
        assertTrue(sdrDraw.contains("if (!presentationEnabled)"))
        assertTrue(sdrDraw.indexOf("st.updateTexImage()") < sdrDraw.indexOf("GLES20.glDrawArrays("))
        assertTrue(hdrDraw.contains("if (!presentationEnabled) return"))
        assertTrue(hdrDraw.indexOf("st.updateTexImage()") < hdrDraw.indexOf("if (!presentationEnabled) return"))
        assertTrue(viewModelSource.contains("updatePresentation = persistentPreviewPresentation::update"))
        assertTrue(lifecycleSource.contains("updatePresentation(owner, incomingOwner)"))
        assertTrue(lifecycleSource.contains("updatePresentation(completedOwner, null)"))
    }

    @Test
    fun `preview compositors delete GL resources only while their context is current`() {
        val compositorSources = listOf(
            File(
                "src/main/java/com/neoproc/neoproccamx/rendering/PreviewGlCompositor.kt"
            ).readText(),
            File(
                "src/main/java/com/neoproc/neoproccamx/rendering/Hdr10PqPreviewCompositor.kt"
            ).readText()
        )

        compositorSources.forEach { source ->
            val destroyBlock = source
                .substringAfter("private fun destroyGl()")
                .substringBefore("private fun buildProgram()")
            val bindContext = destroyBlock.indexOf(
                "EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)"
            )
            val deleteProgram = destroyBlock.indexOf("glDeleteProgram(program)")
            val unbindContext = destroyBlock.indexOf(
                "EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT"
            )

            assertTrue(bindContext >= 0)
            assertTrue(deleteProgram > bindContext)
            assertTrue(unbindContext > deleteProgram)
            assertTrue(destroyBlock.contains("if (contextCurrent)"))
        }
    }

    @Test
    fun `view model teardown never waits for EGL destruction`() {
        val synchronousReleasePatterns = listOf(
            Regex("""previewGlCompositor\s*\.release\(\)"""),
            Regex("""hdr10PqPreviewCompositor\s*\.release\(\)"""),
            Regex("""persistentPreviewOutputRouter\s*\.release\(\)""")
        )

        synchronousReleasePatterns.forEach { pattern ->
            assertFalse("Blocking compositor release returned to CameraViewModel", pattern.containsMatchIn(viewModelSource))
        }
        assertTrue(viewModelSource.contains("previewGlCompositor.releaseAsync()"))
        assertTrue(viewModelSource.contains("hdr10PqPreviewCompositor.releaseAsync()"))
        assertTrue(preparationSource.contains("scope.launch(Dispatchers.Default)"))
        assertTrue(preparationSource.contains("router.release()"))
        assertTrue(
            viewModelSource.contains(
                "previewGlCompositor.isReleasing() || hdr10PqPreviewCompositor.isReleasing()"
            )
        )
    }

    @Test
    fun `vulkan swapchain config cannot execute on the main thread`() {
        val block = viewModelSource
            .substringAfter("private fun pushVulkanPreviewRenderConfig(")
            .substringBefore("private fun buildFallbackGpuSceneRenderProfile(")
        val serializedDeferral = block.indexOf("enqueueVulkanPreviewRenderConfig(geometry, force)")
        val nativeBundleCommit = block.indexOf(
            "NdrRenderProfileLifecycleCoordinator.commitForFrameIngress("
        )

        assertTrue(serializedDeferral >= 0)
        assertTrue(nativeBundleCommit > serializedDeferral)
        assertTrue(block.contains("if (!executeOnWorker)"))
        assertTrue(block.contains("nativeRenderConfig = VulkanNativeRenderProfileConfig("))
        assertFalse(block.contains("synchronized(vulkanPreviewConfigPushLock)"))
        assertTrue(viewModelSource.contains("awaitVulkanPreviewRenderConfig(force = true)"))
    }

    @Test
    fun `in progress persistent preparation coalesces rebinds without deactivating its route`() {
        val applyBlock = viewModelSource
            .substringAfter("private fun applyPersistentPreviewPipeline(")
            .substringBefore("private fun shouldCompositeHdr10PqPreview()")
        val failedGate = applyBlock.indexOf("if (persistentPreviewPreparation.failed) return true")
        val inProgressGate = applyBlock.indexOf(
            "if (persistentPreviewPreparation.inProgress) return true",
            failedGate
        )
        val markPending = applyBlock.indexOf("markStrictPreviewAdmissionPending()", inProgressGate)
        val refreshBlock = viewModelSource
            .substringAfter("internal fun refreshPreviewPipelineIfNeeded(")
            .substringBefore("private fun preferredVideoSourceSize()")
        val handoffBlock = refreshBlock
            .substringAfter("val handoffInProgress =")
            .substringBefore("if (!PreviewPipelineRebindPolicy.shouldRebind")

        assertTrue(failedGate >= 0)
        assertTrue(inProgressGate > failedGate)
        assertTrue(markPending > inProgressGate)
        assertTrue(handoffBlock.contains("persistentPreviewPreparation.inProgress"))
    }

    @Test
    fun `stale activation retries while only terminal strict failures become sticky`() {
        val scheduleBlock = preparationSource
            .substringAfter("fun schedule(")
            .substringBefore("private fun activateIfCurrent(")
        val retryBranch = scheduleBlock.indexOf("ActivationDisposition.RETRYABLE_STALE ->")
        val terminalBranch = scheduleBlock.indexOf("ActivationDisposition.TERMINAL_FAILURE ->")
        val retryOnly = scheduleBlock.substring(retryBranch, terminalBranch)
        val failedLatch = scheduleBlock.indexOf("failed = true", terminalBranch)
        val strictRejection = scheduleBlock.indexOf("onStrictOutputRejected(startResult)", terminalBranch)
        val unregisterOutputs = scheduleBlock.indexOf("camera.setPersistentPreviewOutputs(null, null)")
        val releaseResources = scheduleBlock.indexOf("releaseResources()", unregisterOutputs)

        assertTrue(scheduleBlock.contains("ActivationDisposition.ACTIVATED"))
        assertTrue(retryBranch >= 0)
        assertTrue(terminalBranch > retryBranch)
        assertFalse(retryOnly.contains("failed = true"))
        assertFalse(retryOnly.contains("camera.setPreviewSurface(null)"))
        assertTrue(failedLatch > terminalBranch)
        assertTrue(strictRejection > terminalBranch)
        assertTrue(unregisterOutputs >= 0)
        assertTrue(releaseResources > unregisterOutputs)
    }

    @Test
    fun `activation proves freshness then rearms route and stale completion schedules latest authority`() {
        val activationBlock = preparationSource
            .substringAfter("private fun activateIfCurrent(")
            .substringBefore("private suspend fun awaitResourceRelease()")
        val staleSurface = activationBlock.indexOf("if (!isCurrent())")
        val routeRearm = activationBlock.indexOf("runtimeFailure.activatePersistentRoute()", staleSurface)
        val routeProof = activationBlock.indexOf("runtimeFailure.isPersistentRouteHealthy()", routeRearm)
        val completionBlock = viewModelSource
            .substringAfter("onFinished = {")
            .substringBefore("private fun applyPersistentPreviewPipeline(")
        val retryEligibility = completionBlock.indexOf("!persistentPreviewPreparation.failed")
        val retrySchedule = completionBlock.indexOf(
            "schedulePersistentPreviewPipelinePreparation(",
            retryEligibility
        )
        val openGate = completionBlock.indexOf("if (!persistentPreviewPreparation.inProgress)")

        assertTrue(staleSurface >= 0)
        assertTrue(routeRearm > staleSurface)
        assertTrue(routeProof > routeRearm)
        assertTrue(activationBlock.contains("ActivationResult.retryable"))
        assertTrue(activationBlock.contains("ActivationResult.terminal"))
        assertTrue(retryEligibility >= 0)
        assertTrue(retrySchedule > retryEligibility)
        assertTrue(openGate > retrySchedule)
    }
}
