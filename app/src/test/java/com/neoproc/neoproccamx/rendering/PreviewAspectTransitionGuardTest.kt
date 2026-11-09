package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewAspectTransitionGuardTest {

    @Test
    fun directPreviewUsesRequestedAspectWhenCameraGeometryStaysFullSensor() {
        val aspect = PreviewAspectTransitionGuard.resolveDisplayContentAspectOverride(
            requestedContentAspect = 16f / 9f,
            geometryOutputAspect = 4f / 3f,
            geometrySensorAspect = 4f / 3f,
            streamWidth = 1920,
            streamHeight = 1440,
            nativeCompositorActive = false
        )

        assertEquals(16f / 9f, aspect ?: 0f, 0.0001f)
    }

    @Test
    fun directPreviewUsesRequestedAspectAfterGeometryMatches() {
        val aspect = PreviewAspectTransitionGuard.resolveDisplayContentAspectOverride(
            requestedContentAspect = 16f / 9f,
            geometryOutputAspect = 16f / 9f,
            geometrySensorAspect = 4f / 3f,
            streamWidth = 1920,
            streamHeight = 1440,
            nativeCompositorActive = false
        )

        assertEquals(16f / 9f, aspect ?: 0f, 0.0001f)
    }

    @Test
    fun directPreviewAppliesRequestedAspectImmediatelyWithoutTransportHandoff() {
        val aspect = PreviewAspectTransitionGuard.resolveDisplayContentAspectOverride(
            requestedContentAspect = 16f / 9f,
            previousPresentedContentAspect = 4f / 3f,
            transitionElapsedMs = PreviewAspectTransitionGuard.ASPECT_TRANSITION_HOLD_MS / 2L,
            geometryOutputAspect = 16f / 9f,
            geometrySensorAspect = 4f / 3f,
            streamWidth = 1920,
            streamHeight = 1440,
            nativeCompositorActive = false
        )

        assertEquals(16f / 9f, aspect ?: 0f, 0.0001f)
    }

    @Test
    fun directPreviewUsesRequestedAspectAfterCameraRequestHandoffWindow() {
        val aspect = PreviewAspectTransitionGuard.resolveDisplayContentAspectOverride(
            requestedContentAspect = 16f / 9f,
            previousPresentedContentAspect = 4f / 3f,
            transitionElapsedMs = PreviewAspectTransitionGuard.ASPECT_TRANSITION_HOLD_MS + 1L,
            geometryOutputAspect = 16f / 9f,
            geometrySensorAspect = 4f / 3f,
            streamWidth = 1920,
            streamHeight = 1440,
            nativeCompositorActive = false
        )

        assertEquals(16f / 9f, aspect ?: 0f, 0.0001f)
    }

    @Test
    fun directPreviewUsesRequestedAspectWhenGeometryIsMissing() {
        val aspect = PreviewAspectTransitionGuard.resolveDisplayContentAspectOverride(
            requestedContentAspect = 16f / 9f,
            geometryOutputAspect = null,
            geometrySensorAspect = null,
            streamWidth = 1920,
            streamHeight = 1440,
            nativeCompositorActive = false
        )

        assertEquals(16f / 9f, aspect ?: 0f, 0.0001f)
    }

    @Test
    fun nativeCompositorUsesRequestedAspectImmediately() {
        val aspect = PreviewAspectTransitionGuard.resolveDisplayContentAspectOverride(
            requestedContentAspect = 16f / 9f,
            geometryOutputAspect = 4f / 3f,
            geometrySensorAspect = 4f / 3f,
            streamWidth = 1920,
            streamHeight = 1440,
            nativeCompositorActive = true
        )

        assertEquals(16f / 9f, aspect ?: 0f, 0.0001f)
    }

    @Test
    fun returnsNullWhenNoRequestedAspectExists() {
        val aspect = PreviewAspectTransitionGuard.resolveDisplayContentAspectOverride(
            requestedContentAspect = null,
            geometryOutputAspect = 4f / 3f,
            geometrySensorAspect = 4f / 3f,
            streamWidth = 1920,
            streamHeight = 1440,
            nativeCompositorActive = false
        )

        assertNull(aspect)
    }
}
