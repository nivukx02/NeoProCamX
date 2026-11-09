package com.neoproc.neoproccamx.ui

/**
 * Proof that a real camera frame was successfully swapped to the current visible preview surface.
 * Sensor arrival alone is deliberately not considered display presentation.
 */
data class PreviewDisplayPresentEvent(
    val sequence: Long,
    val sessionGeneration: Long,
    val surfaceEpoch: Long,
    val sensorTimestampNs: Long,
    /** Monotonic host time sampled immediately after the visible EGL swap succeeded. */
    val presentationTimestampNs: Long
)

/**
 * Internal acceptance result that keeps the verified visible-route identity attached until
 * telemetry is committed. A compositor callback is not counted as a visible frame until the
 * presentation coordinator has reconciled its Camera2 generation and visible-surface epoch.
 */
internal data class AcceptedPreviewDisplayPresent(
    val event: PreviewDisplayPresentEvent,
    val visibleRouteStage: PreviewPixelRouteStage
)

internal data class PendingPreviewDisplayPresent<S : Any>(
    val outputSurface: S,
    val surfaceEpoch: Long,
    val presentationTimestampNs: Long,
    val visibleRouteStage: PreviewPixelRouteStage
)

/** Coherent validation point used by UI owners before accepting a presentation event. */
data class PreviewDisplayPresentSnapshot(
    val latestSequence: Long,
    val currentSessionGeneration: Long,
    val currentSurfaceEpoch: Long
)

internal fun PreviewDisplayPresentEvent.isCurrent(
    snapshot: PreviewDisplayPresentSnapshot
): Boolean = sessionGeneration == snapshot.currentSessionGeneration &&
    surfaceEpoch == snapshot.currentSurfaceEpoch && sequence <= snapshot.latestSequence
