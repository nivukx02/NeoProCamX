package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictPreviewRuntimeFailureArchitectureTest {

    private val sdr = source("rendering/PreviewGlCompositor.kt")
    private val hdr = source("rendering/Hdr10PqPreviewCompositor.kt")
    private val coordinator = source("ui/StrictPreviewRuntimeFailureCoordinator.kt")
    private val preparation = source("ui/PersistentPreviewPreparationCoordinator.kt")
    private val viewModel = source("ui/CameraViewModel.kt")

    @Test
    fun `both child compositors fail closed on runtime EGL loss`() {
        assertChildFailureContract(sdr, "PreviewRuntimeComponent.SDR_CHILD", expectedSwapSites = 2)
        assertChildFailureContract(hdr, "PreviewRuntimeComponent.HDR_CHILD", expectedSwapSites = 1)
    }

    @Test
    fun `three stage failures collapse into one owner thread transaction`() {
        val dispatch = coordinator.substringAfter("private fun dispatch(")
        assertTrue(coordinator.contains("router.setOnRuntimeFailureListener(::dispatch)"))
        assertTrue(coordinator.contains("sdr.setOnRuntimeFailureListener(::dispatch)"))
        assertTrue(coordinator.contains("hdr.setOnRuntimeFailureListener(::dispatch)"))
        assertTrue(dispatch.contains("val failureEpoch = routeGate.claimFailure(onFailureDetected) ?: return"))
        assertTrue(dispatch.indexOf("claimFailure(onFailureDetected)") < dispatch.indexOf("runOnOwnerThread"))
        assertTrue(dispatch.contains("routeGate.isCurrent(failureEpoch)"))
        assertTrue(coordinator.contains("router.setOnRuntimeFailureListener(null)"))
        assertTrue(coordinator.contains("sdr.setOnRuntimeFailureListener(null)"))
        assertTrue(coordinator.contains("hdr.setOnRuntimeFailureListener(null)"))
    }

    @Test
    fun `route gate is inert one shot and invalidates queued stale ownership`() {
        val gate = StrictPreviewRuntimeRouteGate()
        assertNull(gate.claimFailure())

        gate.activate()
        val firstEpoch = requireNotNull(gate.claimFailure())
        assertNull(gate.claimFailure())
        assertTrue(gate.isCurrent(firstEpoch))

        gate.deactivate()
        assertFalse(gate.isCurrent(firstEpoch))
        assertNull(gate.claimFailure())

        gate.activate()
        val secondEpoch = requireNotNull(gate.claimFailure())
        assertTrue(secondEpoch > firstEpoch)
        assertFalse(gate.isCurrent(firstEpoch))
    }

    @Test
    fun `failure callback closes ingress before route health flips`() {
        val gate = StrictPreviewRuntimeRouteGate()
        gate.activate()
        var callbackObservedHealthyRoute = false

        val epoch = gate.claimFailure {
            callbackObservedHealthyRoute = gate.isHealthy()
        }

        assertTrue(callbackObservedHealthyRoute)
        assertTrue(epoch != null)
        assertFalse(gate.isHealthy())
    }

    @Test
    fun `runtime rejection detaches Camera2 and remains failed until lifecycle reset`() {
        val handler = viewModel
            .substringAfter("private fun handleStrictPreviewRuntimeFailure(")
            .substringBefore("private fun markStrictPreviewAdmissionPending()")
        val markPending = viewModel
            .substringAfter("private fun markStrictPreviewAdmissionPending()")
            .substringBefore("private fun activateStrictPreviewAdmission(")
        val activation = viewModel
            .substringAfter("private fun activateStrictPreviewAdmission(")
            .substringBefore("/** A mode-generation change")
        val apply = viewModel
            .substringAfter("private fun applyPersistentPreviewPipeline(")
            .substringBefore("private fun shouldCompositeHdr10PqPreview()")
        val onCleared = viewModel.substringAfter("override fun onCleared()")

        val rejectGeneration = handler.indexOf("persistentPreviewPreparation.rejectRuntimeFailure()")
        val detachPipeline = handler.indexOf("disablePersistentPreviewPipeline(releaseInputs = false)")
        val rejectAdmission = handler.indexOf("rejectStrictPreviewOutput(")
        val retargetCamera = handler.indexOf("camera.refreshRepeatingRequest()")
        assertTrue(rejectGeneration >= 0)
        assertTrue(detachPipeline > rejectGeneration)
        assertTrue(rejectAdmission > detachPipeline)
        assertTrue(retargetCamera > rejectAdmission)
        assertTrue(handler.contains("StrictPreviewAdmissionPolicy.runtimeFailure(failure)"))
        assertTrue(
            source("ui/StrictPreviewAdmissionPolicy.kt")
                .contains("PersistentPreviewOutputRouter.StartFailure.RUNTIME_EGL_FAILURE")
        )
        assertTrue(handler.contains("forceRebuild = true"))
        assertTrue(handler.contains("onPreviousSessionClosed = releaseFailedRoute"))
        assertTrue(handler.contains("disablePersistentPreviewPipeline(releaseInputs = true)"))
        assertTrue(markPending.contains("strictPreviewRuntimeFailureCoordinator.deactivateRoute()"))
        assertTrue(activation.contains("strictPreviewRuntimeFailureCoordinator.activatePersistentRoute()"))
        assertTrue(onCleared.contains("strictPreviewRuntimeFailureCoordinator.close()"))

        val stickyFailureCheck = markPending.indexOf("strictPreviewAdmissionState == StrictPreviewAdmissionState.FAILED")
        val pendingDeactivation = markPending.indexOf("strictPreviewRuntimeFailureCoordinator.deactivateRoute()")
        assertTrue(stickyFailureCheck >= 0)
        assertTrue(pendingDeactivation > stickyFailureCheck)

        val retainedRouteActivation = activation.indexOf("strictPreviewRuntimeFailureCoordinator.activatePersistentRoute()")
        val retainedRouteHealthCheck = activation.indexOf("strictPreviewRuntimeFailureCoordinator.isPersistentRouteHealthy()")
        assertTrue(retainedRouteActivation >= 0)
        assertTrue(retainedRouteHealthCheck > retainedRouteActivation)
        assertTrue(
            markPending.substringAfter("private fun resetStrictPreviewAdmissionForLifecycle()")
                .contains("strictPreviewAdmissionState = StrictPreviewAdmissionState.PENDING")
        )

        val failedCheck = apply.indexOf("if (persistentPreviewPreparation.failed) return true")
        val pendingTransition = apply.indexOf("markStrictPreviewAdmissionPending()", failedCheck)
        assertTrue(failedCheck >= 0)
        assertTrue(pendingTransition > failedCheck)

        val rejection = preparation
            .substringAfter("fun rejectRuntimeFailure()")
            .substringBefore("/** Unregisters Camera2 outputs")
        assertTrue(rejection.indexOf("failed = true") < rejection.indexOf("invalidate(resetFailure = false)"))
    }

    private fun assertChildFailureContract(
        text: String,
        component: String,
        expectedSwapSites: Int
    ) {
        val drawAndReport = text.substringAfter("private fun drawFrame(")
        val report = text
            .substringAfter("private fun reportRuntimeFailure(")
            .substringBefore("private fun destroyGl()")
        assertTrue(drawAndReport.contains("if (!EGL14.eglMakeCurrent("))
        assertTrue(drawAndReport.contains("PreviewRuntimeOperation.EGL_MAKE_CURRENT"))
        assertEquals(
            expectedSwapSites,
            drawAndReport.split("if (!presented)").size - 1
        )
        assertTrue(drawAndReport.contains("PreviewRuntimeOperation.EGL_SWAP_BUFFERS"))
        assertTrue(drawAndReport.contains("val eglError = EGL14.eglGetError()"))
        assertTrue(report.contains(component))
        assertTrue(report.contains("runtimeFailureGate.tryClaimActiveFailure()"))
        assertTrue(report.indexOf("running = false") < report.indexOf("listener(failure)"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/neoproc/neoproccamx/$relative"),
            File("app/src/main/java/com/neoproc/neoproccamx/$relative")
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Missing source $relative"
        }.readText().replace("\r\n", "\n")
    }
}
