package com.neoproc.neoproccamx.ui

import kotlin.math.roundToLong

/** Independently identifies progress at each real-pixel preview boundary. */
internal enum class PreviewPixelRouteStage(val metadataKey: String) {
    CAMERA2_CAPTURE("Camera2Capture"),
    SDR_CHILD_SWAP("SdrChildSwap"),
    HDR_CHILD_SWAP("HdrChildSwap"),
    DIRECT_VISIBLE_SWAP("DirectVisibleSwap"),
    PERSISTENT_ROUTER_VISIBLE_SWAP("PersistentRouterVisibleSwap")
}

internal data class PreviewPixelRouteStageSnapshot(
    val count: Long,
    val firstObservedElapsedNs: Long,
    val lastObservedElapsedNs: Long,
    val lastSensorTimestampNs: Long,
    val duplicateSensorTimestamps: Long,
    val regressedSensorTimestamps: Long,
    val maxSensorGapNs: Long,
    val sensorGapsOver60Ms: Long,
    val duplicateObservedTimestamps: Long,
    val regressedObservedTimestamps: Long,
    val maxObservedGapNs: Long,
    val observedGapsOver60Ms: Long,
    val measuredFps: Double
)

internal data class PreviewPixelRouteObservation(
    val stage: PreviewPixelRouteStage,
    val snapshot: PreviewPixelRouteStageSnapshot,
    val logMilestone: Boolean,
    val sensorGapNs: Long,
    val observedGapNs: Long
)

/**
 * Thread-safe counters for Camera2 capture, child-compositor swaps, and the final visible router
 * swap. Keeping the boundaries separate prevents input cadence from being reported as display FPS.
 */
internal class PreviewPixelRouteTelemetry(
    logEveryFrames: Long = 120L
) {
    private data class MutableStage(
        var count: Long = 0L,
        var firstObservedElapsedNs: Long = 0L,
        var lastObservedElapsedNs: Long = 0L,
        var lastSensorTimestampNs: Long = 0L,
        var duplicateSensorTimestamps: Long = 0L,
        var regressedSensorTimestamps: Long = 0L,
        var maxSensorGapNs: Long = 0L,
        var sensorGapsOver60Ms: Long = 0L,
        var duplicateObservedTimestamps: Long = 0L,
        var regressedObservedTimestamps: Long = 0L,
        var maxObservedGapNs: Long = 0L,
        var observedGapsOver60Ms: Long = 0L
    )

    private val logEveryFrames = logEveryFrames.coerceAtLeast(1L)
    private val lock = Any()
    private var epoch = 0L
    private val stages = PreviewPixelRouteStage.entries.associateWith { MutableStage() }.toMutableMap()

    fun reset(newEpoch: Long) = synchronized(lock) {
        epoch = newEpoch
        stages.values.forEach { stage ->
            stage.count = 0L
            stage.firstObservedElapsedNs = 0L
            stage.lastObservedElapsedNs = 0L
            stage.lastSensorTimestampNs = 0L
            stage.duplicateSensorTimestamps = 0L
            stage.regressedSensorTimestamps = 0L
            stage.maxSensorGapNs = 0L
            stage.sensorGapsOver60Ms = 0L
            stage.duplicateObservedTimestamps = 0L
            stage.regressedObservedTimestamps = 0L
            stage.maxObservedGapNs = 0L
            stage.observedGapsOver60Ms = 0L
        }
    }

    fun record(
        stage: PreviewPixelRouteStage,
        sensorTimestampNs: Long,
        observedElapsedNs: Long
    ): PreviewPixelRouteObservation? {
        if (sensorTimestampNs <= 0L || observedElapsedNs <= 0L) return null
        return synchronized(lock) {
            val mutable = stages.getValue(stage)
            val sensorGapNs = if (mutable.lastSensorTimestampNs > 0L &&
                sensorTimestampNs > mutable.lastSensorTimestampNs
            ) {
                sensorTimestampNs - mutable.lastSensorTimestampNs
            } else {
                0L
            }
            val observedGapNs = if (mutable.lastObservedElapsedNs > 0L &&
                observedElapsedNs > mutable.lastObservedElapsedNs
            ) {
                observedElapsedNs - mutable.lastObservedElapsedNs
            } else {
                0L
            }
            when {
                mutable.lastSensorTimestampNs == sensorTimestampNs ->
                    mutable.duplicateSensorTimestamps += 1L
                mutable.lastSensorTimestampNs > sensorTimestampNs ->
                    mutable.regressedSensorTimestamps += 1L
                mutable.lastSensorTimestampNs > 0L -> {
                    mutable.maxSensorGapNs = maxOf(mutable.maxSensorGapNs, sensorGapNs)
                    if (sensorGapNs > MAX_ACCEPTABLE_PRESENTATION_GAP_NS) {
                        mutable.sensorGapsOver60Ms += 1L
                    }
                }
            }
            when {
                mutable.lastObservedElapsedNs == observedElapsedNs ->
                    mutable.duplicateObservedTimestamps += 1L
                mutable.lastObservedElapsedNs > observedElapsedNs ->
                    mutable.regressedObservedTimestamps += 1L
                mutable.lastObservedElapsedNs > 0L -> {
                    mutable.maxObservedGapNs = maxOf(mutable.maxObservedGapNs, observedGapNs)
                    if (observedGapNs > MAX_ACCEPTABLE_PRESENTATION_GAP_NS) {
                        mutable.observedGapsOver60Ms += 1L
                    }
                }
            }
            mutable.count += 1L
            if (mutable.firstObservedElapsedNs == 0L) {
                mutable.firstObservedElapsedNs = observedElapsedNs
            }
            mutable.lastObservedElapsedNs = maxOf(mutable.lastObservedElapsedNs, observedElapsedNs)
            mutable.lastSensorTimestampNs = sensorTimestampNs
            val snapshot = mutable.snapshot()
            PreviewPixelRouteObservation(
                stage = stage,
                snapshot = snapshot,
                logMilestone = snapshot.count % logEveryFrames == 0L,
                sensorGapNs = sensorGapNs,
                observedGapNs = observedGapNs
            )
        }
    }

    fun snapshot(stage: PreviewPixelRouteStage): PreviewPixelRouteStageSnapshot = synchronized(lock) {
        stages.getValue(stage).snapshot()
    }

    fun runtimeMetadata(): Map<String, String> = synchronized(lock) {
        linkedMapOf("previewPixelRouteEpoch" to epoch.toString()).apply {
            PreviewPixelRouteStage.entries.forEach { stage ->
                val snapshot = stages.getValue(stage).snapshot()
                val prefix = "previewPixelRoute${stage.metadataKey}"
                put("${prefix}Count", snapshot.count.toString())
                put("${prefix}MeasuredFps", snapshot.measuredFps.toEvidenceDecimal())
                put("${prefix}DuplicateSensorTimestamps", snapshot.duplicateSensorTimestamps.toString())
                put("${prefix}RegressedSensorTimestamps", snapshot.regressedSensorTimestamps.toString())
                put("${prefix}MaxSensorGapMs", snapshot.maxSensorGapNs.toMillisDecimal())
                put("${prefix}SensorGapsOver60Ms", snapshot.sensorGapsOver60Ms.toString())
                put("${prefix}DuplicateObservedTimestamps", snapshot.duplicateObservedTimestamps.toString())
                put("${prefix}RegressedObservedTimestamps", snapshot.regressedObservedTimestamps.toString())
                put("${prefix}MaxObservedGapMs", snapshot.maxObservedGapNs.toMillisDecimal())
                put("${prefix}ObservedGapsOver60Ms", snapshot.observedGapsOver60Ms.toString())
            }
        }
    }

    private fun MutableStage.snapshot(): PreviewPixelRouteStageSnapshot {
        val elapsedNs = lastObservedElapsedNs - firstObservedElapsedNs
        val measuredFps = if (count > 1L && elapsedNs > 0L) {
            (count - 1L).toDouble() * NANOS_PER_SECOND.toDouble() / elapsedNs.toDouble()
        } else {
            0.0
        }
        return PreviewPixelRouteStageSnapshot(
            count = count,
            firstObservedElapsedNs = firstObservedElapsedNs,
            lastObservedElapsedNs = lastObservedElapsedNs,
            lastSensorTimestampNs = lastSensorTimestampNs,
            duplicateSensorTimestamps = duplicateSensorTimestamps,
            regressedSensorTimestamps = regressedSensorTimestamps,
            maxSensorGapNs = maxSensorGapNs,
            sensorGapsOver60Ms = sensorGapsOver60Ms,
            duplicateObservedTimestamps = duplicateObservedTimestamps,
            regressedObservedTimestamps = regressedObservedTimestamps,
            maxObservedGapNs = maxObservedGapNs,
            observedGapsOver60Ms = observedGapsOver60Ms,
            measuredFps = measuredFps
        )
    }

    private fun Double.toEvidenceDecimal(): String =
        ((this * 100.0).roundToLong() / 100.0).toString()

    private fun Long.toMillisDecimal(): String =
        (this.toDouble() / NANOS_PER_MILLISECOND.toDouble()).toEvidenceDecimal()

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_ACCEPTABLE_PRESENTATION_GAP_NS = 60L * NANOS_PER_MILLISECOND
    }
}
