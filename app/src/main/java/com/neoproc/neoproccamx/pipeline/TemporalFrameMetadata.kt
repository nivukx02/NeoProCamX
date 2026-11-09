package com.neoproc.neoproccamx.pipeline

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.PowerManager
import com.neoproc.neoproccamx.stabilization.ClockOffsetSourcePolicy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt

data class TimedVector3Sample(
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float
)

data class TimedOrientationSample(
    val timestampNs: Long,
    val quaternionX: Float,
    val quaternionY: Float,
    val quaternionZ: Float,
    val quaternionW: Float,
    val displayRotationDegrees: Int
)

enum class PipelineThermalState(val severity: Int) {
    NONE(0),
    LIGHT(1),
    MODERATE(2),
    SEVERE(3),
    CRITICAL(4),
    EMERGENCY(5),
    SHUTDOWN(6),
    UNKNOWN(-1);

    companion object {
        fun fromAndroidStatus(status: Int): PipelineThermalState = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> NONE
            PowerManager.THERMAL_STATUS_LIGHT -> LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> SHUTDOWN
            else -> UNKNOWN
        }
    }
}

data class TimedThermalSample(
    val timestampNs: Long,
    val state: PipelineThermalState,
    val platformStatus: Int,
    /** Real platform headroom, when available. Null means unavailable, never a simulated value. */
    val headroom: Float? = null,
    /** Real measured temperature, when a device sensor exposes it. Null means unavailable. */
    val temperatureCelsius: Float? = null
)

/**
 * Proven mapping from a Camera2 sensor timestamp to the Android sensor-event clock.
 * [cameraToImuOffsetNs] follows `imuTimestamp = cameraTimestamp + offset`. A non-REALTIME
 * camera source is unusable until a measured offset is supplied; zero is never assumed.
 */
data class CameraImuClockAlignment(
    val sensorTimestampSource: Int?,
    val cameraToImuOffsetNs: Long?,
    val offsetMeasured: Boolean
) {
    fun cameraTimestampInImuClock(cameraTimestampNs: Long): Long? {
        if (cameraTimestampNs <= 0L) return null
        val source = ClockOffsetSourcePolicy.resolve(sensorTimestampSource)
        val offsetNs = when {
            source.trusted -> source.offsetNs
            offsetMeasured -> cameraToImuOffsetNs
            else -> null
        } ?: return null
        return saturatingAdd(cameraTimestampNs, offsetNs)
    }

    companion object {
        val UNAVAILABLE = CameraImuClockAlignment(
            sensorTimestampSource = null,
            cameraToImuOffsetNs = null,
            offsetMeasured = false
        )

        fun fromCameraCharacteristics(
            sensorTimestampSource: Int?,
            measuredCameraToImuOffsetNs: Long?
        ) = CameraImuClockAlignment(
            sensorTimestampSource = sensorTimestampSource,
            cameraToImuOffsetNs = measuredCameraToImuOffsetNs,
            offsetMeasured = measuredCameraToImuOffsetNs != null
        )

        private fun saturatingAdd(left: Long, right: Long): Long = when {
            right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
            right < 0L && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
            else -> left + right
        }
    }
}

data class AssociatedVector3(
    val value: TimedVector3Sample,
    val associationDeltaNs: Long,
    val interpolated: Boolean
)

data class AssociatedOrientation(
    val value: TimedOrientationSample,
    val associationDeltaNs: Long,
    val interpolated: Boolean
)

data class FrameTemporalMetadata(
    val associationTimestampNs: Long,
    val gyroscope: AssociatedVector3?,
    val accelerometer: AssociatedVector3?,
    val orientation: AssociatedOrientation?,
    val thermal: TimedThermalSample?,
    val thermalAssociationDeltaNs: Long?,
    val imuAssociationTimestampNs: Long? = null,
    val imuClockAssociationSupported: Boolean = false
)

/**
 * Timestamp association over bounded, non-blocking sample deques. Association is invoked by the
 * metadata-domain worker; Camera2 capture callbacks only enqueue an identity and never scan samples.
 */
class TemporalMetadataAssociator(
    sampleCapacity: Int = 512,
    private val imuToleranceNs: Long = 20_000_000L,
    private val orientationToleranceNs: Long = 50_000_000L,
    private val thermalToleranceNs: Long = 5_000_000_000L
) {
    private val safeCapacity = sampleCapacity.coerceAtLeast(8)
    private val gyroscope = TemporalSampleBuffer(safeCapacity, TimedVector3Sample::timestampNs)
    private val accelerometer = TemporalSampleBuffer(safeCapacity, TimedVector3Sample::timestampNs)
    private val orientation = TemporalSampleBuffer(safeCapacity, TimedOrientationSample::timestampNs)
    private val thermal = TemporalSampleBuffer(safeCapacity.coerceAtMost(64), TimedThermalSample::timestampNs)

    fun addGyroscope(sample: TimedVector3Sample) {
        if (sample.isUsable()) gyroscope.add(sample)
    }

    fun addAccelerometer(sample: TimedVector3Sample) {
        if (sample.isUsable()) accelerometer.add(sample)
    }

    fun addOrientation(sample: TimedOrientationSample) {
        if (sample.timestampNs > 0L && sample.quaternionIsFinite()) orientation.add(sample.normalized())
    }

    fun addThermal(sample: TimedThermalSample) {
        if (sample.timestampNs > 0L &&
            sample.headroom?.isFinite() != false &&
            sample.temperatureCelsius?.isFinite() != false
        ) {
            thermal.add(sample)
        }
    }

    fun associate(
        identity: FrameIdentity,
        cameraMetadata: FrameCameraMetadata?,
        clockAlignment: CameraImuClockAlignment = CameraImuClockAlignment.UNAVAILABLE,
        frameArrivalMonotonicNs: Long = 0L
    ): FrameTemporalMetadata {
        val exposureStartNs = cameraMetadata?.exposureStartTimestampNs
            ?.takeIf { it > 0L }
            ?: identity.sensorTimestampNs
        val halfExposureNs = cameraMetadata?.exposureDurationNs
            ?.coerceAtLeast(0L)
            ?.div(2L)
            ?: 0L
        val cameraTargetNs = saturatingAdd(exposureStartNs, halfExposureNs)
        val imuTargetNs = clockAlignment.cameraTimestampInImuClock(cameraTargetNs)
        val gyroAssociation = imuTargetNs?.let { targetNs ->
            gyroscope.bracket(targetNs, imuToleranceNs)?.let { bracket ->
                associateVector(bracket, targetNs)
            }
        }
        val accelerometerAssociation = imuTargetNs?.let { targetNs ->
            accelerometer.bracket(targetNs, imuToleranceNs)?.let { bracket ->
                associateVector(bracket, targetNs)
            }
        }
        val orientationAssociation = imuTargetNs?.let { targetNs ->
            orientation.bracket(targetNs, orientationToleranceNs)?.let { bracket ->
                associateOrientation(bracket, targetNs)
            }
        }
        // Thermal callbacks use elapsedRealtimeNanos. The Image/capture-result arrival time is already
        // in that clock, so it remains safely associable even when the camera sensor clock is private.
        val thermalTargetNs = frameArrivalMonotonicNs.takeIf { it > 0L } ?: imuTargetNs
        val thermalSample = thermalTargetNs?.let { thermal.nearest(it, thermalToleranceNs) }
        return FrameTemporalMetadata(
            associationTimestampNs = cameraTargetNs,
            gyroscope = gyroAssociation,
            accelerometer = accelerometerAssociation,
            orientation = orientationAssociation,
            thermal = thermalSample,
            thermalAssociationDeltaNs = thermalSample?.let { sample ->
                safeAbsDifference(sample.timestampNs, thermalTargetNs)
            },
            imuAssociationTimestampNs = imuTargetNs,
            imuClockAssociationSupported = imuTargetNs != null
        )
    }

    fun latestThermal(): TimedThermalSample? = thermal.latest()

    fun clear() {
        gyroscope.clear()
        accelerometer.clear()
        orientation.clear()
        thermal.clear()
    }

    private fun associateVector(
        bracket: TemporalBracket<TimedVector3Sample>,
        targetNs: Long
    ): AssociatedVector3 {
        val before = bracket.before
        val after = bracket.after
        if (before != null && after != null && before.timestampNs != after.timestampNs) {
            val fraction = interpolationFraction(before.timestampNs, after.timestampNs, targetNs)
            return AssociatedVector3(
                value = TimedVector3Sample(
                    timestampNs = targetNs,
                    x = lerp(before.x, after.x, fraction),
                    y = lerp(before.y, after.y, fraction),
                    z = lerp(before.z, after.z, fraction)
                ),
                associationDeltaNs = 0L,
                interpolated = true
            )
        }
        val nearest = bracket.nearest(targetNs)!!
        return AssociatedVector3(
            value = nearest,
            associationDeltaNs = safeAbsDifference(nearest.timestampNs, targetNs),
            interpolated = false
        )
    }

    private fun associateOrientation(
        bracket: TemporalBracket<TimedOrientationSample>,
        targetNs: Long
    ): AssociatedOrientation {
        val before = bracket.before
        val after = bracket.after
        if (before != null && after != null && before.timestampNs != after.timestampNs) {
            val fraction = interpolationFraction(before.timestampNs, after.timestampNs, targetNs)
            val dot = before.quaternionX * after.quaternionX +
                before.quaternionY * after.quaternionY +
                before.quaternionZ * after.quaternionZ +
                before.quaternionW * after.quaternionW
            val sign = if (dot < 0f) -1f else 1f
            val interpolated = TimedOrientationSample(
                timestampNs = targetNs,
                quaternionX = lerp(before.quaternionX, after.quaternionX * sign, fraction),
                quaternionY = lerp(before.quaternionY, after.quaternionY * sign, fraction),
                quaternionZ = lerp(before.quaternionZ, after.quaternionZ * sign, fraction),
                quaternionW = lerp(before.quaternionW, after.quaternionW * sign, fraction),
                displayRotationDegrees = if (fraction < 0.5f) {
                    before.displayRotationDegrees
                } else {
                    after.displayRotationDegrees
                }
            ).normalized()
            return AssociatedOrientation(interpolated, associationDeltaNs = 0L, interpolated = true)
        }
        val nearest = bracket.nearest(targetNs)!!
        return AssociatedOrientation(
            value = nearest,
            associationDeltaNs = safeAbsDifference(nearest.timestampNs, targetNs),
            interpolated = false
        )
    }

    private fun TimedVector3Sample.isUsable(): Boolean =
        timestampNs > 0L && x.isFinite() && y.isFinite() && z.isFinite()

    private fun TimedOrientationSample.quaternionIsFinite(): Boolean =
        quaternionX.isFinite() && quaternionY.isFinite() &&
            quaternionZ.isFinite() && quaternionW.isFinite()

    private fun TimedOrientationSample.normalized(): TimedOrientationSample {
        val magnitude = sqrt(
            quaternionX * quaternionX + quaternionY * quaternionY +
                quaternionZ * quaternionZ + quaternionW * quaternionW
        )
        if (!magnitude.isFinite() || magnitude <= 1e-6f) return copy(
            quaternionX = 0f,
            quaternionY = 0f,
            quaternionZ = 0f,
            quaternionW = 1f
        )
        return copy(
            quaternionX = quaternionX / magnitude,
            quaternionY = quaternionY / magnitude,
            quaternionZ = quaternionZ / magnitude,
            quaternionW = quaternionW / magnitude
        )
    }

    private fun interpolationFraction(startNs: Long, endNs: Long, targetNs: Long): Float =
        ((targetNs - startNs).toDouble() / (endNs - startNs).toDouble()).toFloat().coerceIn(0f, 1f)

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}

internal data class TemporalBracket<T>(val before: T?, val after: T?) {
    fun nearest(targetNs: Long, timestamp: (T) -> Long): T? {
        val first = before ?: return after
        val second = after ?: return first
        return if (safeAbsDifference(timestamp(first), targetNs) <=
            safeAbsDifference(timestamp(second), targetNs)
        ) first else second
    }
}

private fun <T> TemporalBracket<T>.nearest(targetNs: Long): T? = when {
    before is TimedVector3Sample -> nearest(targetNs) { (it as TimedVector3Sample).timestampNs }
    before is TimedOrientationSample -> nearest(targetNs) { (it as TimedOrientationSample).timestampNs }
    after is TimedVector3Sample -> nearest(targetNs) { (it as TimedVector3Sample).timestampNs }
    after is TimedOrientationSample -> nearest(targetNs) { (it as TimedOrientationSample).timestampNs }
    else -> before ?: after
}

private class TemporalSampleBuffer<T>(
    private val capacity: Int,
    private val timestamp: (T) -> Long
) {
    private val samples = ConcurrentLinkedDeque<T>()
    private val size = AtomicInteger(0)

    fun add(sample: T) {
        samples.addLast(sample)
        size.incrementAndGet()
        while (size.get() > capacity) {
            if (samples.pollFirst() != null) size.decrementAndGet() else break
        }
    }

    fun bracket(targetNs: Long, toleranceNs: Long): TemporalBracket<T>? {
        var before: T? = null
        var beforeTimestamp = Long.MIN_VALUE
        var after: T? = null
        var afterTimestamp = Long.MAX_VALUE
        samples.forEach { sample ->
            val sampleTimestamp = timestamp(sample)
            if (sampleTimestamp <= targetNs && sampleTimestamp > beforeTimestamp) {
                before = sample
                beforeTimestamp = sampleTimestamp
            }
            if (sampleTimestamp >= targetNs && sampleTimestamp < afterTimestamp) {
                after = sample
                afterTimestamp = sampleTimestamp
            }
        }
        val usableBefore = before?.takeIf { safeAbsDifference(timestamp(it), targetNs) <= toleranceNs }
        val usableAfter = after?.takeIf { safeAbsDifference(timestamp(it), targetNs) <= toleranceNs }
        return if (usableBefore == null && usableAfter == null) null else {
            TemporalBracket(usableBefore, usableAfter)
        }
    }

    fun nearest(targetNs: Long, toleranceNs: Long): T? {
        var nearest: T? = null
        var nearestDelta = Long.MAX_VALUE
        samples.forEach { sample ->
            val delta = safeAbsDifference(timestamp(sample), targetNs)
            if (delta < nearestDelta) {
                nearest = sample
                nearestDelta = delta
            }
        }
        return nearest?.takeIf { nearestDelta <= toleranceNs }
    }

    fun latest(): T? {
        var latest: T? = null
        var latestTimestamp = Long.MIN_VALUE
        samples.forEach { sample ->
            val candidate = timestamp(sample)
            if (candidate > latestTimestamp) {
                latest = sample
                latestTimestamp = candidate
            }
        }
        return latest
    }

    fun clear() {
        samples.clear()
        size.set(0)
    }
}

class SensorFrameRecord internal constructor(
    initialIdentity: FrameIdentity,
    initialCameraMetadata: FrameCameraMetadata?,
    val arrivedAtMonotonicNs: Long,
    /** Registry epoch that admitted this record; provisional Image identities remain unassigned. */
    internal val registrySessionGeneration: Long = initialIdentity.sessionGeneration
) {
    val identity = FrameIdentityHandle(initialIdentity)
    val lifecycle = FrameLifecycleTracker()
    private val camera = AtomicReference(initialCameraMetadata)
    private val temporal = AtomicReference<FrameTemporalMetadata?>(null)
    private val captureAccounted = AtomicBoolean(false)
    private val previewAccounted = AtomicBoolean(false)
    private val recordingAccounted = AtomicBoolean(false)
    private val displayAccounted = AtomicBoolean(false)

    fun cameraMetadata(): FrameCameraMetadata? = camera.get()
    fun temporalMetadata(): FrameTemporalMetadata? = temporal.get()

    internal fun mergeCaptureIdentity(authoritative: FrameIdentity): Boolean =
        identity.mergeCamera2(authoritative)

    internal fun mergeCaptureMetadata(metadata: CapturedFrameMetadata): Boolean {
        if (!identity.mergeCamera2(metadata.identity)) return false
        camera.set(metadata.camera)
        return true
    }

    fun updateTemporalMetadata(metadata: FrameTemporalMetadata) {
        temporal.set(metadata)
        lifecycle.mark(FrameLifecycleState.METADATA_ASSOCIATED)
    }

    internal fun tryAccountCapture(): Boolean = captureAccounted.compareAndSet(false, true)
    internal fun tryAccountPreview(): Boolean = previewAccounted.compareAndSet(false, true)
    internal fun tryAccountRecording(): Boolean = recordingAccounted.compareAndSet(false, true)
    internal fun tryAccountDisplay(): Boolean = displayAccounted.compareAndSet(false, true)
}

/** Exact sensor-timestamp registry; it never associates a capture result by a broad time window. */
internal class SensorFrameRegistry(
    private val associator: TemporalMetadataAssociator,
    private val maxRecords: Int = 512,
    private val monotonicClockNs: () -> Long
) {
    private val sequence = AtomicLong(0L)
    private val sessionGeneration = AtomicLong(FrameIdentity.UNASSIGNED_SESSION_GENERATION)
    private val recordsBySensorTimestamp = ConcurrentHashMap<Long, SensorFrameRecord>()
    private val timestampOrder = ConcurrentLinkedDeque<Long>()
    private val calibration = AtomicReference<CameraCalibrationHandle?>(null)
    private val identityConflicts = AtomicLong(0L)

    fun beginSession(
        generation: Long,
        preserveRetiredSessionRecords: Boolean = false
    ): Long {
        require(generation != FrameIdentity.UNASSIGNED_SESSION_GENERATION)
        sessionGeneration.set(generation)
        if (!preserveRetiredSessionRecords) {
            recordsBySensorTimestamp.clear()
            timestampOrder.clear()
            sequence.set(0L)
            identityConflicts.set(0L)
        }
        associator.clear()
        return generation
    }

    /** Host-test convenience. Production supplies CameraController's actual generation explicitly. */
    internal fun beginSession(): Long {
        val current = sessionGeneration.get()
        val next = if (current == FrameIdentity.UNASSIGNED_SESSION_GENERATION) 1L else current + 1L
        return beginSession(next)
    }

    fun setCalibration(handle: CameraCalibrationHandle?) {
        calibration.set(handle)
    }

    fun registerCaptureResult(
        result: TotalCaptureResult,
        captureSessionGeneration: Long
    ): SensorFrameRecord? {
        val record = registerCaptureIdentity(result, captureSessionGeneration) ?: return null
        attachCaptureMetadata(record, result, captureSessionGeneration)
        return record
    }

    /**
     * Minimal Camera2 callback ingress. This registers only immutable scalar identity; the list/matrix
     * extraction in [attachCaptureMetadata] is deliberately safe to execute on the metadata worker.
     */
    fun registerCaptureIdentity(
        result: TotalCaptureResult,
        captureSessionGeneration: Long
    ): SensorFrameRecord? {
        if (captureSessionGeneration != sessionGeneration.get()) return null
        val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return null
        if (timestampNs <= 0L) return null
        return registerCaptureIdentity(
            FrameIdentity(
                sessionGeneration = captureSessionGeneration,
                ingressSequence = sequence.incrementAndGet(),
                frameNumber = result.frameNumber.takeIf { it >= 0L },
                sensorTimestampNs = timestampNs,
                source = FrameIdentitySource.CAMERA2_CAPTURE_RESULT
            )
        )
    }

    internal fun registerCaptureIdentity(identity: FrameIdentity): SensorFrameRecord {
        require(identity.source == FrameIdentitySource.CAMERA2_CAPTURE_RESULT)
        require(identity.sessionGeneration == sessionGeneration.get()) {
            "Capture identity belongs to stale session ${identity.sessionGeneration}; " +
                "active=${sessionGeneration.get()}"
        }
        val candidate = SensorFrameRecord(
            initialIdentity = identity,
            initialCameraMetadata = null,
            arrivedAtMonotonicNs = monotonicClockNs(),
            registrySessionGeneration = identity.sessionGeneration
        )
        val existing = recordsBySensorTimestamp.putIfAbsent(identity.sensorTimestampNs, candidate)
        val record = existing ?: candidate.also {
            timestampOrder.addLast(identity.sensorTimestampNs)
            prune()
        }
        if (existing != null && !record.mergeCaptureIdentity(identity)) {
            identityConflicts.incrementAndGet()
        }
        record.lifecycle.mark(FrameLifecycleState.CAPTURED)
        return record
    }

    /** Full Camera2 metadata extraction; called from the bounded metadata domain, never pixel ingress. */
    fun attachCaptureMetadata(
        record: SensorFrameRecord,
        result: TotalCaptureResult,
        captureSessionGeneration: Long
    ): Boolean {
        if (captureSessionGeneration != sessionGeneration.get() ||
            record.registrySessionGeneration != captureSessionGeneration
        ) return false
        val identity = record.identity.snapshot()
        val metadata = Camera2FrameMetadataExtractor.extract(
            result = result,
            sessionGeneration = captureSessionGeneration,
            ingressSequence = identity.ingressSequence,
            calibration = calibration.get()
        ) ?: return false
        if (recordsBySensorTimestamp[metadata.identity.sensorTimestampNs] !== record) return false
        if (!record.mergeCaptureMetadata(metadata)) {
            identityConflicts.incrementAndGet()
            return false
        }
        return captureSessionGeneration == sessionGeneration.get()
    }

    internal fun registerCaptureMetadata(metadata: CapturedFrameMetadata): SensorFrameRecord {
        require(metadata.identity.sessionGeneration == sessionGeneration.get()) {
            "Capture metadata belongs to stale session ${metadata.identity.sessionGeneration}; " +
                "active=${sessionGeneration.get()}"
        }
        val timestampNs = metadata.identity.sensorTimestampNs
        val candidate = SensorFrameRecord(
            initialIdentity = metadata.identity,
            initialCameraMetadata = metadata.camera,
            arrivedAtMonotonicNs = monotonicClockNs(),
            registrySessionGeneration = metadata.identity.sessionGeneration
        )
        val existing = recordsBySensorTimestamp.putIfAbsent(timestampNs, candidate)
        val record = existing ?: candidate.also {
            timestampOrder.addLast(timestampNs)
            prune()
        }
        if (existing != null && !record.mergeCaptureMetadata(metadata)) {
            identityConflicts.incrementAndGet()
        }
        record.lifecycle.mark(FrameLifecycleState.CAPTURED)
        return record
    }

    fun recordImage(sensorTimestampNs: Long, arrivedAtNs: Long = monotonicClockNs()): SensorFrameRecord {
        val sequenceId = sequence.incrementAndGet()
        val activeGeneration = sessionGeneration.get()
        val provisionalIdentity = FrameIdentity(
            // ImageReader does not identify the producing CameraCaptureSession. Keep this unassigned
            // until an exact SENSOR_TIMESTAMP capture result promotes it; never guess the active epoch.
            sessionGeneration = FrameIdentity.UNASSIGNED_SESSION_GENERATION,
            ingressSequence = sequenceId,
            frameNumber = null,
            sensorTimestampNs = sensorTimestampNs,
            source = FrameIdentitySource.IMAGE_TIMESTAMP
        )
        val record = if (sensorTimestampNs > 0L) {
            val candidate = SensorFrameRecord(
                provisionalIdentity,
                null,
                arrivedAtNs,
                registrySessionGeneration = activeGeneration
            )
            recordsBySensorTimestamp.putIfAbsent(sensorTimestampNs, candidate)?.also {
                // The capture-result record remains canonical.
            } ?: candidate.also {
                timestampOrder.addLast(sensorTimestampNs)
                prune()
            }
        } else {
            // Timestamp-less images cannot be safely merged with any other exposure.
            SensorFrameRecord(
                provisionalIdentity,
                null,
                arrivedAtNs,
                registrySessionGeneration = activeGeneration
            )
        }
        record.lifecycle.mark(FrameLifecycleState.CAPTURED)
        record.lifecycle.mark(FrameLifecycleState.BUFFERED)
        return record
    }

    fun find(sensorTimestampNs: Long): SensorFrameRecord? =
        sensorTimestampNs.takeIf { it > 0L }?.let(recordsBySensorTimestamp::get)

    fun identityConflictCount(): Long = identityConflicts.get()

    fun activeSessionGeneration(): Long = sessionGeneration.get()

    /** Runs only on the metadata worker; never on a Camera2 capture callback. */
    fun associateTemporalMetadata(
        record: SensorFrameRecord,
        expectedSessionGeneration: Long,
        clockAlignment: CameraImuClockAlignment
    ): Boolean {
        if (expectedSessionGeneration != sessionGeneration.get() ||
            record.registrySessionGeneration != expectedSessionGeneration
        ) return false
        val timestampNs = record.identity.snapshot().sensorTimestampNs
        if (timestampNs > 0L && recordsBySensorTimestamp[timestampNs] !== record) return false
        val metadata = associator.associate(
            identity = record.identity.snapshot(),
            cameraMetadata = record.cameraMetadata(),
            clockAlignment = clockAlignment,
            frameArrivalMonotonicNs = record.arrivedAtMonotonicNs
        )
        if (expectedSessionGeneration != sessionGeneration.get()) return false
        record.updateTemporalMetadata(metadata)
        return true
    }

    fun clear() {
        recordsBySensorTimestamp.clear()
        timestampOrder.clear()
        associator.clear()
        sessionGeneration.set(FrameIdentity.UNASSIGNED_SESSION_GENERATION)
    }

    private fun prune() {
        val safeLimit = maxRecords.coerceAtLeast(32)
        while (recordsBySensorTimestamp.size > safeLimit) {
            val oldestTimestamp = timestampOrder.pollFirst() ?: return
            recordsBySensorTimestamp.remove(oldestTimestamp)
        }
    }
}

internal fun safeAbsDifference(first: Long, second: Long): Long {
    if (first == second) return 0L
    val difference = if (first > second) first - second else second - first
    return if (difference < 0L) Long.MAX_VALUE else abs(difference)
}
