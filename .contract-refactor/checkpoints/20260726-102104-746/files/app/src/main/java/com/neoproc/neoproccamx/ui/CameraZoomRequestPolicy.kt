package com.neoproc.neoproccamx.ui

internal object CameraZoomRequestPolicy {
    fun resolve(
        requestedZoom: Float,
        minZoom: Float,
        maxZoom: Float,
        beyondImaxActive: Boolean
    ): Float {
        if (minZoom > maxZoom) return requestedZoom
        val effectiveRequest = if (beyondImaxActive) 1f else requestedZoom
        return effectiveRequest.coerceIn(minZoom, maxZoom)
    }
}
