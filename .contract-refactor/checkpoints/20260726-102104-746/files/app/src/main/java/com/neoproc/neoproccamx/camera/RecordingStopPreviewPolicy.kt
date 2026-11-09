package com.neoproc.neoproccamx.camera

internal object RecordingStopPreviewPolicy {
    fun shouldRestartPreviewImmediately(recordSurfaceAttached: Boolean): Boolean =
        !recordSurfaceAttached

    fun shouldRebindVisiblePreviewTarget(
        keepPrearmedRecordSurface: Boolean,
        previewSurfaceAvailable: Boolean,
        nativeVisiblePreviewActive: Boolean
    ): Boolean =
        keepPrearmedRecordSurface &&
            previewSurfaceAvailable &&
            !nativeVisiblePreviewActive
}
