package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSurfaceStartupPolicyTest {

    @Test
    fun cachedHolderSurfaceCannotOpenBeforeSurfaceCallback() {
        assertFalse(
            PreviewSurfaceStartupPolicy.canUseCachedSurfaceForOpen(
                surfaceCallbackDelivered = false,
                surfaceValid = true
            )
        )
    }

    @Test
    fun cachedSurfaceCanOpenAfterCallbackWhenStillValid() {
        assertTrue(
            PreviewSurfaceStartupPolicy.canUseCachedSurfaceForOpen(
                surfaceCallbackDelivered = true,
                surfaceValid = true
            )
        )
    }

    @Test
    fun cachedHolderSurfaceCanRecoverAfterLayoutEvenWhenCallbackWasMissed() {
        assertTrue(
            PreviewSurfaceStartupPolicy.canUseCachedSurfaceForOpen(
                surfaceCallbackDelivered = false,
                surfaceValid = true,
                viewAttached = true,
                viewHasSize = true
            )
        )
        assertFalse(
            PreviewSurfaceStartupPolicy.canUseCachedSurfaceForOpen(
                surfaceCallbackDelivered = false,
                surfaceValid = true,
                viewAttached = true,
                viewHasSize = false
            )
        )
    }

    @Test
    fun surfaceCallbackSchedulesOpenOnlyWhenValidAndPermitted() {
        assertTrue(
            PreviewSurfaceStartupPolicy.shouldScheduleOpenFromSurfaceCallback(
                surfaceValid = true,
                hasCameraPermission = true
            )
        )
        assertFalse(
            PreviewSurfaceStartupPolicy.shouldScheduleOpenFromSurfaceCallback(
                surfaceValid = false,
                hasCameraPermission = true
            )
        )
        assertFalse(
            PreviewSurfaceStartupPolicy.shouldScheduleOpenFromSurfaceCallback(
                surfaceValid = true,
                hasCameraPermission = false
            )
        )
    }

    @Test
    fun cachedSurfaceProbeRetriesUntilBoundedBudgetIsExhausted() {
        assertTrue(
            PreviewSurfaceStartupPolicy.shouldScheduleCachedSurfaceProbe(
                attemptsCompleted = 0,
                probeAlreadyScheduled = false
            )
        )
        assertFalse(
            PreviewSurfaceStartupPolicy.shouldScheduleCachedSurfaceProbe(
                attemptsCompleted = 0,
                probeAlreadyScheduled = true
            )
        )
        assertFalse(
            PreviewSurfaceStartupPolicy.shouldScheduleCachedSurfaceProbe(
                attemptsCompleted = PreviewSurfaceStartupPolicy.CACHED_SURFACE_PROBE_MAX_ATTEMPTS,
                probeAlreadyScheduled = false
            )
        )
    }

    @Test
    fun cachedHolderSurfaceProbeRequiresLayoutAndNoCallbackYet() {
        assertTrue(
            PreviewSurfaceStartupPolicy.canProbeCachedSurfaceAfterLayout(
                surfaceCallbackDelivered = false,
                surfaceValid = true,
                viewAttached = true,
                viewHasSize = true
            )
        )
        assertFalse(
            PreviewSurfaceStartupPolicy.canProbeCachedSurfaceAfterLayout(
                surfaceCallbackDelivered = true,
                surfaceValid = true,
                viewAttached = true,
                viewHasSize = true
            )
        )
        assertFalse(
            PreviewSurfaceStartupPolicy.canProbeCachedSurfaceAfterLayout(
                surfaceCallbackDelivered = false,
                surfaceValid = true,
                viewAttached = true,
                viewHasSize = false
            )
        )
    }

    @Test
    fun callbackSurfaceCanOpenWhenHolderCacheIsTemporarilyMissing() {
        assertTrue(
            PreviewSurfaceStartupPolicy.canUseCallbackSurfaceWhenHolderCacheMissing(
                surfaceCallbackDelivered = true,
                callbackSurfaceValid = true
            )
        )
        assertFalse(
            PreviewSurfaceStartupPolicy.canUseCallbackSurfaceWhenHolderCacheMissing(
                surfaceCallbackDelivered = false,
                callbackSurfaceValid = true
            )
        )
        assertFalse(
            PreviewSurfaceStartupPolicy.canUseCallbackSurfaceWhenHolderCacheMissing(
                surfaceCallbackDelivered = true,
                callbackSurfaceValid = false
            )
        )
    }
}
