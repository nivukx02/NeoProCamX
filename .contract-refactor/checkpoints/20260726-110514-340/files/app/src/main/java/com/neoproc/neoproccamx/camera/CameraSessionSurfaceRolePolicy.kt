package com.neoproc.neoproccamx.camera

internal enum class CameraSessionSurfaceKind {
    PREVIEW,
    RECORD,
    JPEG,
    RAW,
    YUV_ANALYSIS,
    HARDWARE_IMPORT,
    UNKNOWN
}

internal object CameraSessionSurfaceRolePolicy {
    fun resolveStreamUseCaseRole(
        kind: CameraSessionSurfaceKind,
        previewParityRecording: Boolean = false
    ): StreamUseCasePolicy.StreamRole = when (kind) {
        CameraSessionSurfaceKind.PREVIEW -> StreamUseCasePolicy.StreamRole.PREVIEW
        CameraSessionSurfaceKind.RECORD -> StreamUseCasePolicy.StreamRole.VIDEO_RECORD
        CameraSessionSurfaceKind.JPEG -> StreamUseCasePolicy.StreamRole.STILL_JPEG
        CameraSessionSurfaceKind.RAW -> StreamUseCasePolicy.StreamRole.STILL_RAW
        CameraSessionSurfaceKind.YUV_ANALYSIS -> StreamUseCasePolicy.StreamRole.ANALYSIS
        CameraSessionSurfaceKind.HARDWARE_IMPORT -> StreamUseCasePolicy.StreamRole.PREVIEW
        CameraSessionSurfaceKind.UNKNOWN -> StreamUseCasePolicy.StreamRole.ANALYSIS
    }
}
