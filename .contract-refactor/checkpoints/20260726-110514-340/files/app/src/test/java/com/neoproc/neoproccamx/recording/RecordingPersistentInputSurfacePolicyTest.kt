package com.neoproc.neoproccamx.recording

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPersistentInputSurfacePolicyTest {

    @Test
    fun `camera2 video preview parity prepares reusable persistent input surface on supported api`() {
        assertTrue(
            RecordingPersistentInputSurfacePolicy.shouldPreparePersistentInputSurface(
                androidApiLevel = Build.VERSION_CODES.M
            )
        )

        assertTrue(
            RecordingPersistentInputSurfacePolicy.shouldUsePersistentInputSurface(
                androidApiLevel = Build.VERSION_CODES.M,
                persistentSurfaceAvailable = true,
                persistentSurfaceRejected = false
            )
        )

        assertFalse(
            RecordingPersistentInputSurfacePolicy.shouldUsePersistentInputSurface(
                androidApiLevel = Build.VERSION_CODES.LOLLIPOP,
                persistentSurfaceAvailable = true,
                persistentSurfaceRejected = false
            )
        )

        assertFalse(
            RecordingPersistentInputSurfacePolicy.shouldUsePersistentInputSurface(
                androidApiLevel = Build.VERSION_CODES.M,
                persistentSurfaceAvailable = false,
                persistentSurfaceRejected = false
            )
        )

        assertFalse(
            RecordingPersistentInputSurfacePolicy.shouldUsePersistentInputSurface(
                androidApiLevel = Build.VERSION_CODES.M,
                persistentSurfaceAvailable = true,
                persistentSurfaceRejected = true
            )
        )
    }

    @Test
    fun `persistent session surface is kept across recording session cleanup`() {
        assertFalse(
            RecordingPersistentInputSurfacePolicy.shouldReleaseSessionSurface(
                sessionUsesPersistentSurface = true
            )
        )
        assertTrue(
            RecordingPersistentInputSurfacePolicy.shouldReleaseSessionSurface(
                sessionUsesPersistentSurface = false
            )
        )
    }

    @Test
    fun `persistent input surface is retained after an active codec session for stable preview graph`() {
        assertFalse(
            RecordingPersistentInputSurfacePolicy.shouldRetirePersistentInputSurfaceAfterCodecSession(
                sessionUsesPersistentSurface = true
            )
        )

        assertFalse(
            RecordingPersistentInputSurfacePolicy.shouldRetirePersistentInputSurfaceAfterCodecSession(
                sessionUsesPersistentSurface = false
            )
        )
    }

    @Test
    fun `persistent input surface is attached to camera preview session after codec configuration`() {
        assertFalse(
            RecordingPersistentInputSurfacePolicy.shouldAttachPrearmedSurfaceToPreviewSession(
                codecConfigured = false,
                previewSessionValidated = false
            )
        )

        assertTrue(
            RecordingPersistentInputSurfacePolicy.shouldAttachPrearmedSurfaceToPreviewSession(
                codecConfigured = true,
                previewSessionValidated = false
            )
        )

        assertTrue(
            RecordingPersistentInputSurfacePolicy.shouldAttachPrearmedSurfaceToPreviewSession(
                codecConfigured = true,
                previewSessionValidated = true
            )
        )
    }

    @Test
    fun `prearmed surface attachment is confirmed only after a real session refresh completes`() {
        assertFalse(
            RecordingPersistentInputSurfacePolicy.isCameraSessionAttachmentConfirmed(
                sessionRefreshRequested = false,
                sessionRefreshCompleted = true
            )
        )

        assertFalse(
            RecordingPersistentInputSurfacePolicy.isCameraSessionAttachmentConfirmed(
                sessionRefreshRequested = true,
                sessionRefreshCompleted = false
            )
        )

        assertTrue(
            RecordingPersistentInputSurfacePolicy.isCameraSessionAttachmentConfirmed(
                sessionRefreshRequested = true,
                sessionRefreshCompleted = true
            )
        )
    }

    @Test
    fun `prearmed surface only skips session rebuild after it is already attached`() {
        assertFalse(
            RecordingPersistentInputSurfacePolicy.shouldSkipSessionRebuildForPrearmedSurface(
                surfaceMatches = true,
                surfaceAttachedToCameraSession = false
            )
        )

        assertFalse(
            RecordingPersistentInputSurfacePolicy.shouldSkipSessionRebuildForPrearmedSurface(
                surfaceMatches = false,
                surfaceAttachedToCameraSession = true
            )
        )

        assertTrue(
            RecordingPersistentInputSurfacePolicy.shouldSkipSessionRebuildForPrearmedSurface(
                surfaceMatches = true,
                surfaceAttachedToCameraSession = true
            )
        )
    }
}
