package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanRuntimeLifecycleContractTest {
    private val runtimeSource = File(
        "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
    ).readText()

    @Test
    fun `close generation advance and profile commit share the generation monitor`() {
        val advance = runtimeSource
            .substringAfter("internal fun advanceRenderProfileGeneration(")
            .substringBefore("internal fun isRenderProfileGenerationCurrent(")
        val generationGate = runtimeSource
            .substringAfter("private inline fun <T> withCurrentRenderProfileGeneration(")
            .substringBefore("fun setHdrDynamicRangeMode(")
        val close = runtimeSource.substringAfter("override fun close()")
        val closeTransition = close.substringBefore("if (closeCompleted.get()) return")

        assertTrue(advance.contains("synchronized(renderProfileGenerationLock)"))
        assertTrue(advance.contains("if (closeRequested.get()) return@synchronized"))
        assertTrue(
            advance.indexOf("acceptedRenderProfileRevision = 0L") <
                advance.indexOf("ndrRenderProfileRevisionAdmission.advanceGeneration(generation)")
        )
        assertTrue(advance.contains("ndrRenderProfileMutationDeferral.clear()"))
        assertTrue(advance.contains("_ndrRenderProfileMutationReadyIdentity.value = null"))
        assertTrue(generationGate.contains("synchronized(renderProfileGenerationLock)"))
        assertTrue(generationGate.contains("if (closeRequested.get() ||"))
        assertTrue(closeTransition.contains("synchronized(renderProfileGenerationLock)"))

        val closeRequested = closeTransition.indexOf("closeRequested.set(true)")
        val admissionClosed = closeTransition.indexOf("ndrRenderProfileReadinessAdmission.close()")
        val revisionAdmissionClosed = closeTransition.indexOf("ndrRenderProfileRevisionAdmission.close()")
        val mutationDeferralCleared = closeTransition.indexOf("ndrRenderProfileMutationDeferral.clear()")
        val revisionCleared = closeTransition.indexOf("acceptedRenderProfileRevision = 0L")
        val stagedCleared = closeTransition.indexOf("stagedNdrRenderBundle.clear()")
        val appliedCleared = closeTransition.indexOf("appliedNdrRenderBundle.clear()")
        val stagedEdgeCleared = closeTransition.indexOf("_ndrRenderProfileStagedGeneration.value = 0L")
        val readyEdgeCleared = closeTransition.indexOf("_ndrRenderProfileReadyGeneration.value = 0L")
        assertTrue(closeRequested >= 0)
        assertTrue(admissionClosed > closeRequested)
        assertTrue(revisionAdmissionClosed > admissionClosed)
        assertTrue(mutationDeferralCleared > revisionAdmissionClosed)
        assertTrue(revisionCleared > mutationDeferralCleared)
        assertTrue(stagedCleared > revisionCleared)
        assertTrue(appliedCleared > stagedCleared)
        assertTrue(stagedEdgeCleared > appliedCleared)
        assertTrue(readyEdgeCleared > stagedEdgeCleared)
    }

    @Test
    fun `closed runtime cannot initialize or finalize a packet`() {
        val initialize = runtimeSource
            .substringAfter("fun initialize(cacheDir: java.io.File? = null)")
            .substringBefore("private fun replayDeferredNdrRenderProfileIfReady")
        val finalize = runtimeSource
            .substringAfter("internal fun finalizeNdrRenderProfilePacket(")
            .substringBefore("/** Consumes the latest compatible cold-start packet")

        val closedGuard = initialize.indexOf("if (closeRequested.get())")
        val initializeClaim = initialize.indexOf("initialized.compareAndSet(false, true)")
        assertTrue(closedGuard >= 0)
        assertTrue(initializeClaim > closedGuard)
        assertTrue(finalize.contains("withCurrentRenderProfileGeneration("))
        assertTrue(finalize.contains("_ndrRenderProfileReadyGeneration.value ="))
    }

    @Test
    fun `already applied packet still proves current route before reopening ingress`() {
        val finalize = runtimeSource
            .substringAfter("internal fun finalizeNdrRenderProfilePacket(")
            .substringBefore("/** Consumes the latest compatible cold-start packet")
        val appliedMatch = finalize.indexOf("val appliedBundleAlreadyPublished =")
        val routeProof = finalize.indexOf("beforeIngress()")
        val appliedFastPath = finalize.indexOf(
            "if (appliedBundleAlreadyPublished) return@withCurrentRenderProfileGeneration true"
        )

        assertTrue(appliedMatch >= 0)
        assertTrue(routeProof > appliedMatch)
        assertTrue(appliedFastPath > routeProof)
    }

    @Test
    fun `failed precision fallback can detach exact staged or applied identity`() {
        val setPreview = runtimeSource
            .substringAfter("fun setPreviewSurface(surface: Surface?): Boolean")
            .substringBefore("internal fun detachPreviewSurfaceForRenderProfileIdentity(")
        val detach = runtimeSource
            .substringAfter("internal fun detachPreviewSurfaceForRenderProfileIdentity(")
            .substringBefore("fun setEncoderSurface(")
        val stagedIdentity = detach.indexOf("val stagedIdentity =")
        val appliedIdentity = detach.indexOf("val appliedIdentity =")
        val dualIdentityGate = detach.indexOf(
            "stagedIdentity != identity && appliedIdentity != identity"
        )
        val ingressClose = detach.indexOf("ndrNativeFrameBoundary.closeIngressForMutation()")
        val nativeDetach = detach.indexOf("val detachAcknowledged = setPreviewSurface(null)")

        assertTrue(setPreview.contains("surface != null && boundPreviewSurface === surface"))
        assertTrue(setPreview.contains("val previouslyBoundSurface = boundPreviewSurface"))
        assertTrue(setPreview.contains("if (!nativeAcknowledged)"))
        assertTrue(setPreview.contains("boundPreviewSurface = previouslyBoundSurface"))
        assertTrue(setPreview.contains("boundPreviewSurface = surface"))
        assertTrue(stagedIdentity >= 0)
        assertTrue(appliedIdentity > stagedIdentity)
        assertTrue(dualIdentityGate > appliedIdentity)
        assertTrue(ingressClose > dualIdentityGate)
        assertTrue(nativeDetach > ingressClose)
        assertTrue(detach.contains("detachAcknowledged && boundPreviewSurface == null"))
    }
}
