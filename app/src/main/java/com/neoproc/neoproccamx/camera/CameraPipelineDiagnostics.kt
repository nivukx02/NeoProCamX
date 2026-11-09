package com.neoproc.neoproccamx.camera

internal object CameraPipelineDiagnostics {

    fun readoutFallbackWarning(
        requested: CameraStreamSize?,
        actual: CameraStreamSize?,
        requestedLabel: String,
        actualLabel: String
    ): String? {
        if (requested == null || actual == null) return null
        if (actual.area >= requested.area) return null
        return "$requestedLabel requested, but camera/encoder exposed ${actual.width}x${actual.height}. " +
            "Using $actualLabel path."
    }

    fun ispBypassWarning(unsupportedReasons: List<String>): String? {
        if (unsupportedReasons.isEmpty()) return null
        return "Requested ISP processing disablement unavailable: ${unsupportedReasons.joinToString(", ")}."
    }

    fun experimentalFallbackWarning(reason: String): String =
        "Pro capture path adapted to this device — continued on the stable pipeline: $reason."

    fun readoutSidecarSessionFallbackWarning(
        rejectedPath: String,
        fallbackPath: String
    ): String =
        "Camera HAL rejected $rejectedPath. Recording continued using $fallbackPath path."
}
