package com.neoproc.neoproccamx.ui

/**
 * Owns the bounded, generation-aware handoff between Camera2 capture timestamps and confirmed
 * visible-window compositor swaps. All mutable presentation identity stays behind one lock so a
 * surface/session transition cannot publish a late frame under the new epoch.
 */
internal class PreviewDisplayPresentationCoordinator<S : Any>(
    capacity: Int,
    private val currentSessionGeneration: () -> Long,
    private val currentPreviewSurface: () -> S?,
    private val isOutputSurfaceValid: (S) -> Boolean,
    private val timestampMatchToleranceNs: Long = DEFAULT_TIMESTAMP_MATCH_TOLERANCE_NS
) {
    private val capacity = capacity.coerceAtLeast(1)
    private val lock = Any()
    private var outputSurface: S? = null
    private var surfaceEpoch = 0L
    private var latestSequence = 0L
    private var lastAcceptedPresentationTimestampNs = 0L
    private val captureSessionByTimestamp = LinkedHashMap<Long, Long>()
    private val pendingPresents = LinkedHashMap<Long, PendingPreviewDisplayPresent<S>>()
    private val emittedTimestamps = LinkedHashSet<Long>()

    fun recordCapture(
        sensorTimestampNs: Long,
        sessionGeneration: Long
    ): AcceptedPreviewDisplayPresent? {
        if (sensorTimestampNs <= 0L) return null
        return synchronized(lock) {
            if (sessionGeneration != currentSessionGeneration()) return@synchronized null
            captureSessionByTimestamp[sensorTimestampNs] = sessionGeneration
            trimOldest(captureSessionByTimestamp)
            val pendingTimestampNs = pendingPresents.closestTimestampTo(sensorTimestampNs)
                ?: return@synchronized null
            val pending = pendingPresents.remove(pendingTimestampNs)
                ?: return@synchronized null
            createEventLocked(
                // Camera2 SENSOR_TIMESTAMP is the canonical frame identity. The compositor's
                // SurfaceTexture timestamp may differ by a few microseconds when the vendor maps
                // MONOTONIC and BOOTTIME clocks. Match only inside the same strict 2 ms tolerance
                // used by the recording-lineage certificate; adjacent 25/60 fps frames remain far
                // outside this window and cannot be merged.
                sensorTimestampNs = sensorTimestampNs,
                frameSessionGeneration = sessionGeneration,
                candidateSurface = pending.outputSurface,
                expectedSurfaceEpoch = pending.surfaceEpoch,
                presentationTimestampNs = pending.presentationTimestampNs,
                visibleRouteStage = pending.visibleRouteStage
            )
        }
    }

    fun onSessionGenerationAdvanced() {
        synchronized(lock) {
            // A late swap from the retired Camera2 graph must never be relabelled as the new session.
            captureSessionByTimestamp.clear()
            pendingPresents.clear()
            emittedTimestamps.clear()
            lastAcceptedPresentationTimestampNs = 0L
        }
    }

    fun updateSurface(surface: S?) {
        synchronized(lock) {
            if (outputSurface === surface) return
            outputSurface = surface
            surfaceEpoch++
            pendingPresents.clear()
            emittedTimestamps.clear()
            lastAcceptedPresentationTimestampNs = 0L
        }
    }

    fun snapshot(): PreviewDisplayPresentSnapshot = synchronized(lock) {
        PreviewDisplayPresentSnapshot(
            latestSequence = latestSequence,
            currentSessionGeneration = currentSessionGeneration(),
            currentSurfaceEpoch = surfaceEpoch
        )
    }

    fun recordCompositedPresentation(
        sensorTimestampNs: Long,
        outputSurface: S,
        presentationTimestampNs: Long,
        visibleRouteStage: PreviewPixelRouteStage
    ): AcceptedPreviewDisplayPresent? {
        if (sensorTimestampNs <= 0L) return null
        return synchronized(lock) {
            if (this.outputSurface !== outputSurface || currentPreviewSurface() !== outputSurface) {
                return@synchronized null
            }
            if (!isOutputSurfaceValid(outputSurface)) {
                return@synchronized null
            }
            val captureTimestampNs = captureSessionByTimestamp.closestTimestampTo(sensorTimestampNs)
            val frameSessionGeneration = captureTimestampNs?.let(captureSessionByTimestamp::get)
            if (frameSessionGeneration == null) {
                pendingPresents[sensorTimestampNs] = PendingPreviewDisplayPresent(
                    outputSurface = outputSurface,
                    surfaceEpoch = surfaceEpoch,
                    presentationTimestampNs = presentationTimestampNs,
                    visibleRouteStage = visibleRouteStage
                )
                trimOldest(pendingPresents)
                null
            } else {
                createEventLocked(
                    sensorTimestampNs = captureTimestampNs,
                    frameSessionGeneration = frameSessionGeneration,
                    candidateSurface = outputSurface,
                    expectedSurfaceEpoch = surfaceEpoch,
                    presentationTimestampNs = presentationTimestampNs,
                    visibleRouteStage = visibleRouteStage
                )
            }
        }
    }

    /** Caller holds [lock]. */
    private fun createEventLocked(
        sensorTimestampNs: Long,
        frameSessionGeneration: Long,
        candidateSurface: S,
        expectedSurfaceEpoch: Long,
        presentationTimestampNs: Long,
        visibleRouteStage: PreviewPixelRouteStage
    ): AcceptedPreviewDisplayPresent? {
        val activeSessionGeneration = currentSessionGeneration()
        if (frameSessionGeneration != activeSessionGeneration ||
            expectedSurfaceEpoch != surfaceEpoch ||
            outputSurface !== candidateSurface ||
            currentPreviewSurface() !== candidateSurface ||
            !isOutputSurfaceValid(candidateSurface) ||
            presentationTimestampNs <= 0L ||
            presentationTimestampNs <= lastAcceptedPresentationTimestampNs ||
            !emittedTimestamps.add(sensorTimestampNs)
        ) return null
        trimOldest(emittedTimestamps)
        lastAcceptedPresentationTimestampNs = presentationTimestampNs
        latestSequence++
        return AcceptedPreviewDisplayPresent(
            event = PreviewDisplayPresentEvent(
                sequence = latestSequence,
                sessionGeneration = activeSessionGeneration,
                surfaceEpoch = surfaceEpoch,
                sensorTimestampNs = sensorTimestampNs,
                presentationTimestampNs = presentationTimestampNs
            ),
            visibleRouteStage = visibleRouteStage
        )
    }

    private fun <V> trimOldest(entries: LinkedHashMap<Long, V>) {
        while (entries.size > capacity) {
            val oldest = entries.entries.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
    }

    private fun trimOldest(entries: LinkedHashSet<Long>) {
        while (entries.size > capacity) {
            val oldest = entries.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
    }

    /** Caller holds [lock]. Returns one unambiguous physical-frame match, if present. */
    private fun <V> LinkedHashMap<Long, V>.closestTimestampTo(timestampNs: Long): Long? {
        var closestTimestampNs: Long? = null
        var closestDeltaNs = Long.MAX_VALUE
        for (candidateTimestampNs in keys) {
            val deltaNs = absoluteDifference(candidateTimestampNs, timestampNs)
            if (deltaNs <= timestampMatchToleranceNs && deltaNs < closestDeltaNs) {
                closestTimestampNs = candidateTimestampNs
                closestDeltaNs = deltaNs
            }
        }
        return closestTimestampNs
    }

    private fun absoluteDifference(first: Long, second: Long): Long {
        val larger = maxOf(first, second)
        val smaller = minOf(first, second)
        return if (larger > Long.MAX_VALUE - smaller) Long.MAX_VALUE else larger - smaller
    }

    private companion object {
        const val DEFAULT_TIMESTAMP_MATCH_TOLERANCE_NS = 2_000_000L
    }
}
