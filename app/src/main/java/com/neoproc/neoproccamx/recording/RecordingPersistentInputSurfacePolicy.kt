package com.neoproc.neoproccamx.recording

import android.os.Build

internal object RecordingPersistentInputSurfacePolicy {
    fun shouldPreparePersistentInputSurface(
        androidApiLevel: Int = Build.VERSION.SDK_INT
    ): Boolean = androidApiLevel >= Build.VERSION_CODES.M

    fun shouldUsePersistentInputSurface(
        androidApiLevel: Int = Build.VERSION.SDK_INT,
        persistentSurfaceAvailable: Boolean,
        persistentSurfaceRejected: Boolean
    ): Boolean =
        shouldPreparePersistentInputSurface(androidApiLevel) &&
            persistentSurfaceAvailable &&
            !persistentSurfaceRejected

    fun shouldReleaseSessionSurface(
        sessionUsesPersistentSurface: Boolean
    ): Boolean = !sessionUsesPersistentSurface

    @Suppress("UNUSED_PARAMETER")
    fun shouldRetirePersistentInputSurfaceAfterCodecSession(
        sessionUsesPersistentSurface: Boolean
    ): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun shouldAttachPrearmedSurfaceToPreviewSession(
        codecConfigured: Boolean,
        previewSessionValidated: Boolean
    ): Boolean = codecConfigured

    fun isCameraSessionAttachmentConfirmed(
        sessionRefreshRequested: Boolean,
        sessionRefreshCompleted: Boolean
    ): Boolean = sessionRefreshRequested && sessionRefreshCompleted

    fun shouldSkipSessionRebuildForPrearmedSurface(
        surfaceMatches: Boolean,
        surfaceAttachedToCameraSession: Boolean
    ): Boolean = surfaceMatches && surfaceAttachedToCameraSession
}
