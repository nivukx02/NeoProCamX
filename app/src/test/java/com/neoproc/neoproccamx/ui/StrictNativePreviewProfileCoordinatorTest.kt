package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.rendering.NdrRenderProfileIdentity
import com.neoproc.neoproccamx.rendering.NdrNativeFrameBoundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class StrictNativePreviewProfileCoordinatorTest {
    @Test
    fun `native precision proof is exact to render identity and route epoch`() {
        val harness = Harness()
        val identity = NdrRenderProfileIdentity(4L, 2L)
        harness.currentIdentity = identity

        assertTrue(harness.coordinator.revalidate(identity))
        assertTrue(harness.coordinator.proofStillCurrent(identity))
        assertEquals(1, harness.refreshes)

        harness.coordinator.onRouteChanged()
        assertFalse(harness.coordinator.proofStillCurrent(identity))
    }

    @Test
    fun `pending failed and unhealthy persistent routes retain ingress without native query`() {
        val harness = Harness()
        val identity = NdrRenderProfileIdentity(7L, 1L)
        harness.currentIdentity = identity

        for (state in listOf(StrictPreviewAdmissionState.PENDING, StrictPreviewAdmissionState.FAILED)) {
            harness.route = harness.route.copy(admissionState = state, nativeRouteActive = false)
            assertFalse(harness.coordinator.revalidate(identity))
        }
        harness.route = harness.route.copy(
            admissionState = StrictPreviewAdmissionState.ACTIVE_PERSISTENT,
            persistentRouteHealthy = false
        )
        assertFalse(harness.coordinator.revalidate(identity))
        assertEquals(0, harness.refreshes)
    }

    @Test
    fun `zero sentinel identity is rejected before any route can grant authority`() {
        val harness = Harness()
        val sentinel = NdrRenderProfileIdentity(0L, 0L)
        harness.currentIdentity = sentinel

        assertFalse(harness.coordinator.revalidate(sentinel))
        assertFalse(harness.coordinator.proofStillCurrent(sentinel))
        assertEquals(0, harness.refreshes)
        assertEquals(0, harness.ownerTasks.size)

        harness.route = harness.route.copy(
            admissionState = StrictPreviewAdmissionState.ACTIVE_PERSISTENT,
            persistentRouteHealthy = true,
            nativeRouteActive = false
        )
        assertFalse(harness.coordinator.revalidate(sentinel))
        assertFalse(harness.coordinator.proofStillCurrent(sentinel))
    }

    @Test
    fun `runtime current identity gate rejects invalid sentinel before comparing counters`() {
        val runtime = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        ).readText()
        val gate = runtime
            .substringAfter("internal fun isRenderProfileIdentityCurrent(")
            .substringBefore("private inline fun <T> withCurrentRenderProfileGeneration(")

        assertTrue(gate.contains("identity.valid"))
        assertTrue(gate.indexOf("identity.valid") < gate.indexOf("acceptedRenderProfileGeneration"))
        assertTrue(gate.indexOf("identity.valid") < gate.indexOf("acceptedRenderProfileRevision"))
    }

    @Test
    fun `superseded queued fallback cannot detach newer revision and replays latest authority`() {
        val harness = Harness(capability = "preview=on previewFmt=RGBA8")
        val old = NdrRenderProfileIdentity(9L, 2L)
        val latest = NdrRenderProfileIdentity(9L, 3L)
        harness.currentIdentity = old

        assertFalse(harness.coordinator.revalidate(old))
        assertEquals(1, harness.ownerTasks.size)
        harness.currentIdentity = latest
        harness.ownerTasks.removeFirst().invoke()

        assertEquals(0, harness.detachAttempts)
        assertNull(harness.fallback)
        assertEquals(1, harness.latestReplays)
    }

    @Test
    fun `failed exact precision detaches once then invalidates route before fallback`() {
        val harness = Harness(capability = "preview=on previewFmt=RGBA8")
        val identity = NdrRenderProfileIdentity(11L, 4L)
        harness.currentIdentity = identity
        lateinit var coordinator: StrictNativePreviewProfileCoordinator
        harness.onFallback = { request ->
            harness.fallback = request
            harness.route = harness.route.copy(
                admissionState = StrictPreviewAdmissionState.PENDING,
                nativeRouteActive = false
            )
            coordinator.onRouteChanged()
        }
        coordinator = harness.coordinator

        assertFalse(coordinator.revalidate(identity))
        harness.ownerTasks.removeFirst().invoke()

        assertEquals(1, harness.detachAttempts)
        assertEquals(identity, harness.fallback?.identity)
        assertEquals("RGBA8", harness.fallback?.nativeFormat)
        assertEquals(0, harness.latestReplays)
        assertFalse(coordinator.proofStillCurrent(identity))
    }

    @Test
    fun `failed exact native detach remains fail closed without immediate self replay`() {
        val harness = Harness(capability = "preview=on previewFmt=RGBA8")
        val identity = NdrRenderProfileIdentity(12L, 5L)
        harness.currentIdentity = identity
        harness.detachAllowed = false

        assertFalse(harness.coordinator.revalidate(identity))
        assertEquals(1, harness.ownerTasks.size)
        harness.ownerTasks.removeFirst().invoke()

        assertEquals(1, harness.detachAttempts)
        assertEquals(0, harness.latestReplays)
        assertEquals(0, harness.ownerTasks.size)
        assertEquals(
            StrictNativePreviewProfileCoordinator.DeferralReason.NATIVE_DETACH_REJECTED,
            harness.deferrals.last().reason
        )
    }

    @Test
    fun `route epoch invalidation does not self replay a stale fallback`() {
        val harness = Harness(capability = "preview=on previewFmt=RGBA8")
        val identity = NdrRenderProfileIdentity(14L, 7L)
        harness.currentIdentity = identity

        assertFalse(harness.coordinator.revalidate(identity))
        assertEquals(1, harness.ownerTasks.size)
        harness.coordinator.onRouteChanged()
        harness.ownerTasks.removeFirst().invoke()

        assertEquals(0, harness.detachAttempts)
        assertEquals(0, harness.latestReplays)
        assertEquals(
            StrictNativePreviewProfileCoordinator.DeferralReason.FALLBACK_ROUTE_CHANGED,
            harness.deferrals.last().reason
        )
    }

    @Test
    fun `identical requests share one fallback and one precision deferral event`() {
        val harness = Harness(capability = "preview=on previewFmt=RGBA8")
        val identity = NdrRenderProfileIdentity(16L, 2L)
        harness.currentIdentity = identity

        assertFalse(harness.coordinator.revalidate(identity))
        assertFalse(harness.coordinator.revalidate(identity))

        assertEquals(1, harness.ownerTasks.size)
        assertEquals(1, harness.deferrals.size)
        assertEquals(
            StrictNativePreviewProfileCoordinator.DeferralReason.NATIVE_PRECISION_UNPROVEN,
            harness.deferrals.single().reason
        )
    }

    @Test
    fun `pending route telemetry identifies admission rather than native precision`() {
        val harness = Harness()
        val identity = NdrRenderProfileIdentity(18L, 1L)
        harness.currentIdentity = identity
        harness.route = harness.route.copy(
            admissionState = StrictPreviewAdmissionState.PENDING,
            nativeRouteActive = false
        )

        assertFalse(harness.coordinator.revalidate(identity))

        assertEquals(0, harness.refreshes)
        assertEquals(0, harness.ownerTasks.size)
        assertEquals(
            StrictNativePreviewProfileCoordinator.DeferralReason.ROUTE_PENDING,
            harness.deferrals.single().reason
        )
    }

    @Test
    fun `route change after proof prevents frame ingress reopen`() {
        val boundary = NdrNativeFrameBoundary(initiallyOpen = false)
        val harness = Harness(frameIngressBoundary = boundary)
        val identity = NdrRenderProfileIdentity(13L, 6L)
        harness.currentIdentity = identity
        assertTrue(harness.coordinator.revalidate(identity))

        val proofObserved = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val finalizationResult = AtomicReference<Boolean?>()
        val workerFailure = AtomicReference<Throwable?>()
        val finalizer = thread(start = true, name = "strict-preview-finalize-race") {
            try {
                finalizationResult.set(
                    boundary.finalizeIngress {
                        check(harness.coordinator.proofStillCurrent(identity))
                        proofObserved.countDown()
                        check(releasePublication.await(3L, TimeUnit.SECONDS))
                        true
                    }
                )
            } catch (failure: Throwable) {
                workerFailure.set(failure)
            }
        }
        assertTrue(proofObserved.await(3L, TimeUnit.SECONDS))

        harness.coordinator.onRouteChanged()
        releasePublication.countDown()
        finalizer.join(TimeUnit.SECONDS.toMillis(3L))
        assertFalse("finalizer did not terminate", finalizer.isAlive)
        workerFailure.get()?.let { throw AssertionError("finalization race failed", it) }

        assertEquals(false, finalizationResult.get())
        assertFalse(boundary.snapshot().ingressOpen)
        assertNull(boundary.acquireFrameLease())
        assertEquals(1, harness.frameIngressInvalidations)
    }

    @Test
    fun `route invalidation before applied packet refinalization cannot reopen ingress`() {
        val boundary = NdrNativeFrameBoundary(initiallyOpen = false)
        val harness = Harness(frameIngressBoundary = boundary)
        val identity = NdrRenderProfileIdentity(17L, 8L)
        harness.currentIdentity = identity

        assertTrue(harness.coordinator.revalidate(identity))
        assertTrue(boundary.finalizeIngress { harness.coordinator.proofStillCurrent(identity) })
        assertTrue(boundary.snapshot().ingressOpen)

        assertTrue(harness.coordinator.revalidate(identity))
        harness.coordinator.onRouteChanged()
        assertFalse(
            boundary.finalizeIngress { harness.coordinator.proofStillCurrent(identity) }
        )
        assertFalse(boundary.snapshot().ingressOpen)
        assertNull(boundary.acquireFrameLease())
    }

    @Test
    fun `applied packet replay with failed precision detaches its exact native route`() {
        val boundary = NdrNativeFrameBoundary(initiallyOpen = false)
        val harness = Harness(frameIngressBoundary = boundary)
        val identity = NdrRenderProfileIdentity(19L, 3L)
        harness.currentIdentity = identity
        lateinit var coordinator: StrictNativePreviewProfileCoordinator
        harness.onFallback = { request ->
            harness.fallback = request
            harness.route = harness.route.copy(
                admissionState = StrictPreviewAdmissionState.PENDING,
                nativeRouteActive = false
            )
            coordinator.onRouteChanged()
        }
        coordinator = harness.coordinator

        assertTrue(coordinator.revalidate(identity))
        assertTrue(boundary.finalizeIngress { coordinator.proofStillCurrent(identity) })
        harness.capability = "preview=on previewFmt=RGBA8"

        assertFalse(coordinator.revalidate(identity))
        assertEquals(1, harness.ownerTasks.size)
        harness.ownerTasks.removeFirst().invoke()

        assertEquals(1, harness.detachAttempts)
        assertEquals(identity, harness.fallback?.identity)
        assertFalse(boundary.snapshot().ingressOpen)
    }

    private class Harness(
        var capability: String = "preview=on previewFmt=A2B10G10R10",
        val frameIngressBoundary: NdrNativeFrameBoundary = NdrNativeFrameBoundary()
    ) {
        var route = StrictNativePreviewProfileCoordinator.RouteSnapshot(
            appMode = AppMode.VIDEO,
            admissionState = StrictPreviewAdmissionState.ACTIVE_NATIVE,
            persistentRouteHealthy = false,
            nativeRouteActive = true
        )
        var currentIdentity: NdrRenderProfileIdentity? = null
        var detachAllowed = true
        var refreshes = 0
        var detachAttempts = 0
        var latestReplays = 0
        var frameIngressInvalidations = 0
        var fallback: StrictNativePreviewProfileCoordinator.FallbackRequest? = null
        val deferrals = mutableListOf<StrictNativePreviewProfileCoordinator.DeferralTelemetry>()
        var onFallback: (StrictNativePreviewProfileCoordinator.FallbackRequest) -> Unit = {
            fallback = it
        }
        val ownerTasks = ArrayDeque<() -> Unit>()
        val coordinator = StrictNativePreviewProfileCoordinator(
            StrictNativePreviewProfileCoordinator.Runtime(
                routeSnapshot = { route },
                isIdentityCurrent = { it == currentIdentity },
                refreshCapabilitySummary = { refreshes++ },
                capabilitySummary = { capability },
                runOnOwnerThread = ownerTasks::addLast,
                detachExactNativeSurface = {
                    detachAttempts++
                    detachAllowed && it == currentIdentity
                },
                onNativeRouteDetached = { onFallback(it) },
                isGenerationCurrent = { it == currentIdentity?.profileGeneration },
                replayLatestProfile = { latestReplays++ },
                invalidateFrameIngress = {
                    frameIngressInvalidations++
                    frameIngressBoundary.invalidateIngressFromExternalRouteChange()
                },
                frameIngressEpoch = frameIngressBoundary::currentIngressEpoch,
                onDeferral = { deferrals += it }
            )
        )
    }
}
