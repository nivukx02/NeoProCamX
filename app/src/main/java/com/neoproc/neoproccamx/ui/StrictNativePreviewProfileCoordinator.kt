package com.neoproc.neoproccamx.ui

import android.util.Log
import com.neoproc.neoproccamx.rendering.NdrRenderProfileIdentity
import com.neoproc.neoproccamx.rendering.VulkanPreviewSurfaceStatusParser
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the exact render-identity proof for a strict native preview route.
 *
 * Render-profile staging runs on the serialized render worker, while a failed precision proof must
 * detach Android surfaces on their owner thread. The route epoch and immutable fallback token close
 * both TOCTOU windows: an old queued fallback cannot detach a newer revision, and a proof cannot be
 * reused after any native/persistent route transition.
 */
internal class StrictNativePreviewProfileCoordinator(
    private val runtime: Runtime
) {
    private companion object {
        const val TAG = "CameraViewModel"
    }

    internal enum class DeferralReason {
        INVALID_IDENTITY,
        ROUTE_PENDING,
        ROUTE_FAILED,
        PERSISTENT_ROUTE_UNHEALTHY,
        NATIVE_ROUTE_INACTIVE,
        IDENTITY_NOT_CURRENT,
        CAPABILITY_REFRESH_FAILED,
        NATIVE_SURFACE_UNBOUND,
        NATIVE_PRECISION_UNPROVEN,
        ROUTE_CHANGED_DURING_PROOF,
        FRAME_INGRESS_EPOCH_CHANGED,
        FALLBACK_ALREADY_PENDING,
        FALLBACK_TOKEN_SUPERSEDED,
        FALLBACK_ROUTE_CHANGED,
        NATIVE_DETACH_REJECTED
    }

    internal data class DeferralTelemetry(
        val reason: DeferralReason,
        val identity: NdrRenderProfileIdentity,
        val admissionState: StrictPreviewAdmissionState?,
        val persistentRouteHealthy: Boolean,
        val nativeRouteActive: Boolean,
        val frameIngressEpoch: Long,
        val nativeSurfaceBound: Boolean?,
        val nativeFormat: String,
        val nativePrecision: String
    )

    internal data class RouteSnapshot(
        val appMode: AppMode,
        val admissionState: StrictPreviewAdmissionState,
        val persistentRouteHealthy: Boolean,
        val nativeRouteActive: Boolean
    )

    internal data class FallbackRequest(
        val identity: NdrRenderProfileIdentity,
        val nativeFormat: String
    )

    internal data class Runtime(
        val routeSnapshot: () -> RouteSnapshot,
        val isIdentityCurrent: (NdrRenderProfileIdentity) -> Boolean,
        val refreshCapabilitySummary: () -> Unit,
        val capabilitySummary: () -> String,
        val runOnOwnerThread: (() -> Unit) -> Unit,
        val detachExactNativeSurface: (NdrRenderProfileIdentity) -> Boolean,
        val onNativeRouteDetached: (FallbackRequest) -> Unit,
        val isGenerationCurrent: (Long) -> Boolean,
        val replayLatestProfile: () -> Unit,
        /** Lock-free close edge into the same boundary that performs final frame-ingress publication. */
        val invalidateFrameIngress: () -> Long,
        val frameIngressEpoch: () -> Long,
        val onDeferral: (DeferralTelemetry) -> Unit = {}
    )

    private data class FinalizationProof(
        val identity: NdrRenderProfileIdentity,
        val frameIngressEpoch: Long,
        val gate: StrictPreviewProfileFinalizationGate
    )

    private data class FallbackToken(
        val identity: NdrRenderProfileIdentity,
        val frameIngressEpoch: Long
    )

    private val finalizationProof = AtomicReference<FinalizationProof?>(null)
    private val scheduledFallback = AtomicReference<FallbackToken?>(null)
    /**
     * Set only when a newer identity/epoch reaches revalidation while an older owner-thread fallback
     * is still queued. It is not set by the fallback itself, which prevents a failed exact detach from
     * turning into an unbounded worker/Main/worker replay loop.
     */
    private val newerProofRequestPending = AtomicBoolean(false)
    private val lastReportedDeferral = AtomicReference<DeferralTelemetry?>(null)

    /** Invalidates all proofs before publishing the next route epoch. */
    fun onRouteChanged() {
        // This shared-boundary CAS is the linearization point. A finalizer that already passed its
        // point-in-time proof cannot reopen; one that starts later observes an epoch mismatch.
        runtime.invalidateFrameIngress()
        finalizationProof.set(null)
        newerProofRequestPending.set(false)
        lastReportedDeferral.set(null)
    }

    /** Final non-blocking proof check executed immediately before frame ingress is reopened. */
    fun proofStillCurrent(identity: NdrRenderProfileIdentity): Boolean {
        if (!identity.valid) return false
        val route = runtime.routeSnapshot()
        val gate = finalizationGate(route)
        return when (gate) {
            StrictPreviewProfileFinalizationGate.ALLOW_PROVEN_ROUTE ->
                finalizationProof.get() == FinalizationProof(
                    identity,
                    runtime.frameIngressEpoch(),
                    gate
                ) && runtime.isIdentityCurrent(identity)
            StrictPreviewProfileFinalizationGate.RETAIN_FRAME_INGRESS -> false
            StrictPreviewProfileFinalizationGate.REQUIRE_NATIVE_PRECISION_PROOF -> {
                route.nativeRouteActive && finalizationProof.get() == FinalizationProof(
                    identity,
                    runtime.frameIngressEpoch(),
                    gate
                ) && runtime.isIdentityCurrent(identity)
            }
        }
    }

    /**
     * Re-proves swapchain precision for one exact staged identity. Failure retains frame ingress and
     * schedules at most one owner-thread fallback; no camera/session lock is held by this coordinator.
     */
    fun revalidate(identity: NdrRenderProfileIdentity): Boolean {
        if (!identity.valid) {
            reportDeferral(
                reason = DeferralReason.INVALID_IDENTITY,
                identity = identity,
                route = null,
                epoch = runtime.frameIngressEpoch()
            )
            return false
        }
        val initialRoute = runtime.routeSnapshot()
        val gate = finalizationGate(initialRoute)
        when (gate) {
            StrictPreviewProfileFinalizationGate.ALLOW_PROVEN_ROUTE -> Unit
            StrictPreviewProfileFinalizationGate.RETAIN_FRAME_INGRESS -> {
                val reason = when {
                    initialRoute.admissionState == StrictPreviewAdmissionState.PENDING ->
                        DeferralReason.ROUTE_PENDING
                    initialRoute.admissionState == StrictPreviewAdmissionState.FAILED ->
                        DeferralReason.ROUTE_FAILED
                    initialRoute.admissionState == StrictPreviewAdmissionState.ACTIVE_PERSISTENT &&
                        !initialRoute.persistentRouteHealthy ->
                        DeferralReason.PERSISTENT_ROUTE_UNHEALTHY
                    else -> DeferralReason.ROUTE_CHANGED_DURING_PROOF
                }
                reportDeferral(reason, identity, initialRoute, runtime.frameIngressEpoch())
                return false
            }
            StrictPreviewProfileFinalizationGate.REQUIRE_NATIVE_PRECISION_PROOF -> Unit
        }
        if (gate == StrictPreviewProfileFinalizationGate.REQUIRE_NATIVE_PRECISION_PROOF &&
            !initialRoute.nativeRouteActive
        ) {
            reportDeferral(
                DeferralReason.NATIVE_ROUTE_INACTIVE,
                identity,
                initialRoute,
                runtime.frameIngressEpoch()
            )
            return false
        }

        val epoch = runtime.frameIngressEpoch()
        val expectedProof = FinalizationProof(identity, epoch, gate)
        if (finalizationProof.get() == expectedProof && runtime.isIdentityCurrent(identity)) {
            clearDeferral()
            return true
        }
        if (!runtime.isIdentityCurrent(identity)) {
            reportDeferral(DeferralReason.IDENTITY_NOT_CURRENT, identity, initialRoute, epoch)
            return false
        }

        if (gate == StrictPreviewProfileFinalizationGate.ALLOW_PROVEN_ROUTE) {
            val currentRoute = runtime.routeSnapshot()
            if (runtime.frameIngressEpoch() == epoch &&
                finalizationGate(currentRoute) == gate &&
                runtime.isIdentityCurrent(identity)
            ) {
                finalizationProof.set(expectedProof)
                clearDeferral()
                return true
            }
            reportDeferral(
                when {
                    runtime.frameIngressEpoch() != epoch ->
                        DeferralReason.FRAME_INGRESS_EPOCH_CHANGED
                    !runtime.isIdentityCurrent(identity) -> DeferralReason.IDENTITY_NOT_CURRENT
                    else -> DeferralReason.ROUTE_CHANGED_DURING_PROOF
                },
                identity,
                currentRoute,
                epoch
            )
            return false
        }

        val refreshed = runCatching(runtime.refreshCapabilitySummary).isSuccess
        val precision = VulkanPreviewSurfaceStatusParser.parse(runtime.capabilitySummary())
        val refreshedRoute = runtime.routeSnapshot()
        if (refreshed && precision.strictTenBitOrBetterProven &&
            runtime.isIdentityCurrent(identity) &&
            runtime.frameIngressEpoch() == epoch &&
            refreshedRoute.admissionState == StrictPreviewAdmissionState.ACTIVE_NATIVE &&
            refreshedRoute.nativeRouteActive
        ) {
            finalizationProof.set(expectedProof)
            clearDeferral()
            return true
        }

        val deferralReason = when {
            !runtime.isIdentityCurrent(identity) -> DeferralReason.IDENTITY_NOT_CURRENT
            runtime.frameIngressEpoch() != epoch -> DeferralReason.FRAME_INGRESS_EPOCH_CHANGED
            refreshedRoute.admissionState != StrictPreviewAdmissionState.ACTIVE_NATIVE ->
                DeferralReason.ROUTE_CHANGED_DURING_PROOF
            !refreshedRoute.nativeRouteActive -> DeferralReason.NATIVE_ROUTE_INACTIVE
            !refreshed -> DeferralReason.CAPABILITY_REFRESH_FAILED
            !precision.bound -> DeferralReason.NATIVE_SURFACE_UNBOUND
            else -> DeferralReason.NATIVE_PRECISION_UNPROVEN
        }
        reportDeferral(
            reason = deferralReason,
            identity = identity,
            route = refreshedRoute,
            epoch = epoch,
            nativeSurfaceBound = precision.bound,
            nativeFormat = precision.format,
            nativePrecision = precision.precision.name
        )

        // Only a still-current ACTIVE_NATIVE route is eligible for owner-thread fallback. Route and
        // epoch changes have their own readiness publications and must not create a self-replay loop.
        if (!runtime.isIdentityCurrent(identity) ||
            runtime.frameIngressEpoch() != epoch ||
            refreshedRoute.admissionState != StrictPreviewAdmissionState.ACTIVE_NATIVE ||
            !refreshedRoute.nativeRouteActive
        ) return false

        val token = FallbackToken(identity, epoch)
        if (!scheduledFallback.compareAndSet(null, token)) {
            val pendingToken = scheduledFallback.get()
            if (pendingToken != null && pendingToken != token) {
                // This is a newer meaningful proof request, not a retry generated by the fallback.
                newerProofRequestPending.set(true)
                reportDeferral(
                    DeferralReason.FALLBACK_ALREADY_PENDING,
                    identity,
                    refreshedRoute,
                    epoch,
                    precision.bound,
                    precision.format,
                    precision.precision.name
                )
            }
            return false
        }
        runtime.runOnOwnerThread {
            executeFallback(token = token, nativeFormat = precision.format)
        }
        return false
    }

    private fun executeFallback(token: FallbackToken, nativeFormat: String) {
        var supersededRevision = false
        try {
            val route = runtime.routeSnapshot()
            if (!token.identity.valid || scheduledFallback.get() != token) {
                reportDeferral(
                    DeferralReason.FALLBACK_TOKEN_SUPERSEDED,
                    token.identity,
                    route,
                    runtime.frameIngressEpoch(),
                    nativeFormat = nativeFormat
                )
                return
            }
            if (!runtime.isIdentityCurrent(token.identity)) {
                supersededRevision = runtime.isGenerationCurrent(token.identity.profileGeneration)
                reportDeferral(
                    DeferralReason.FALLBACK_TOKEN_SUPERSEDED,
                    token.identity,
                    route,
                    runtime.frameIngressEpoch(),
                    nativeFormat = nativeFormat
                )
                return
            }
            if (runtime.frameIngressEpoch() != token.frameIngressEpoch ||
                route.admissionState != StrictPreviewAdmissionState.ACTIVE_NATIVE ||
                !route.nativeRouteActive
            ) {
                reportDeferral(
                    DeferralReason.FALLBACK_ROUTE_CHANGED,
                    token.identity,
                    route,
                    runtime.frameIngressEpoch(),
                    nativeFormat = nativeFormat
                )
                return
            }
            if (!runtime.detachExactNativeSurface(token.identity)) {
                // A concurrent revision replacement is the sole safe reason to replay immediately.
                // A same-identity detach failure remains fail-closed until an external route/readiness
                // edge requests another proof; retrying here spins indefinitely with no new evidence.
                supersededRevision = !runtime.isIdentityCurrent(token.identity) &&
                    runtime.isGenerationCurrent(token.identity.profileGeneration)
                reportDeferral(
                    if (supersededRevision) {
                        DeferralReason.FALLBACK_TOKEN_SUPERSEDED
                    } else {
                        DeferralReason.NATIVE_DETACH_REJECTED
                    },
                    token.identity,
                    runtime.routeSnapshot(),
                    runtime.frameIngressEpoch(),
                    nativeFormat = nativeFormat
                )
                return
            }
            runtime.onNativeRouteDetached(
                FallbackRequest(identity = token.identity, nativeFormat = nativeFormat)
            )
        } finally {
            val cleared = scheduledFallback.compareAndSet(token, null)
            val newerRequest = newerProofRequestPending.getAndSet(false)
            val latestRoute = runtime.routeSnapshot()
            if (cleared &&
                (supersededRevision || newerRequest) &&
                latestRoute.admissionState == StrictPreviewAdmissionState.ACTIVE_NATIVE &&
                latestRoute.nativeRouteActive &&
                runtime.isGenerationCurrent(token.identity.profileGeneration)
            ) {
                // The queued work was superseded without changing the active native route. Re-prove
                // the latest authority so its staged frame ingress cannot remain closed indefinitely.
                runtime.replayLatestProfile()
            }
        }
    }

    private fun clearDeferral() {
        lastReportedDeferral.set(null)
    }

    private fun reportDeferral(
        reason: DeferralReason,
        identity: NdrRenderProfileIdentity,
        route: RouteSnapshot?,
        epoch: Long,
        nativeSurfaceBound: Boolean? = null,
        nativeFormat: String = "UNKNOWN",
        nativePrecision: String = "UNKNOWN"
    ) {
        val telemetry = DeferralTelemetry(
            reason = reason,
            identity = identity,
            admissionState = route?.admissionState,
            persistentRouteHealthy = route?.persistentRouteHealthy == true,
            nativeRouteActive = route?.nativeRouteActive == true,
            frameIngressEpoch = epoch,
            nativeSurfaceBound = nativeSurfaceBound,
            nativeFormat = nativeFormat,
            nativePrecision = nativePrecision
        )
        if (lastReportedDeferral.getAndSet(telemetry) == telemetry) return
        Log.i(
            TAG,
            "NDR_PREVIEW_PROFILE_DEFERRED reason=${telemetry.reason} " +
                "identity=${telemetry.identity} admission=${telemetry.admissionState} " +
                "persistentHealthy=${telemetry.persistentRouteHealthy} " +
                "nativeActive=${telemetry.nativeRouteActive} epoch=${telemetry.frameIngressEpoch} " +
                "nativeBound=${telemetry.nativeSurfaceBound} format=${telemetry.nativeFormat} " +
                "precision=${telemetry.nativePrecision}"
        )
        runCatching { runtime.onDeferral(telemetry) }
    }

    private fun finalizationGate(
        route: RouteSnapshot
    ): StrictPreviewProfileFinalizationGate = StrictPreviewAdmissionPolicy.profileFinalizationGate(
        appMode = route.appMode,
        state = route.admissionState,
        persistentRouteHealthy = route.persistentRouteHealthy
    )
}
