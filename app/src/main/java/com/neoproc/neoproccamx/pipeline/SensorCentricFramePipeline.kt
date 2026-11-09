package com.neoproc.neoproccamx.pipeline

import android.hardware.camera2.TotalCaptureResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Authoritative, metadata-only control plane for the camera's existing zero/low-copy pixel paths.
 * It never retains an Image or hardware buffer and therefore cannot block or extend buffer lifetime.
 */
class SensorCentricFramePipeline(
    targetFps: Double,
    private val monotonicClockNs: () -> Long,
    initialQueueCapacity: Map<FrameDomain, Int> = emptyMap()
) {
    private val associator = TemporalMetadataAssociator()
    private val registry = SensorFrameRegistry(
        associator = associator,
        monotonicClockNs = monotonicClockNs
    )
    private val deadlines = PipelineDeadlineMonitor(targetFps, monotonicClockNs)
    private val targetFpsRef = AtomicReference(targetFps)
    // Recording evidence and loss admission intentionally have different lifetimes. Camera2 may
    // advance to the restored preview generation before MediaCodec commits its final samples, so
    // the registry/deadline evidence must remain readable. Once the accepted encoder tail has
    // drained, however, any stale recording-routed Image belongs outside the completed clip and
    // must not be charged as clip loss.
    private val recordingLossAccountingOpen = AtomicBoolean(false)
    private val latestResources = AtomicReference<PipelineResourceSample?>(null)
    private val clockAlignment = AtomicReference(CameraImuClockAlignment.UNAVAILABLE)
    private val adaptation = AdaptivePipelineController(stableDecision())
    private val queueCapacity = ConcurrentHashMap<FrameDomain, Int>().apply {
        initialQueueCapacity.forEach { (domain, capacity) ->
            if (capacity > 0) put(domain, capacity)
        }
    }
    private val metadataWorker = BoundedFrameDomainWorker(
        config = FrameDomainQueueConfig(
            domain = FrameDomain.METADATA,
            minimumCapacity = 8,
            maximumCapacity = 128,
            initialActiveLimit = 64,
            maxFrameAgeNs = 500_000_000L,
            backpressure = DomainBackpressureStrategy.ADAPTIVE_LATEST,
            preferLatestFrame = false
        ),
        diagnostics = deadlines,
        consumer = FrameDomainConsumer<TemporalAssociationWork> { item ->
            val work = item.payload
            work.captureResult?.let { result ->
                registry.attachCaptureMetadata(
                    record = item.record,
                    result = result,
                    captureSessionGeneration = work.sessionGeneration
                )
            }
            registry.associateTemporalMetadata(
                record = item.record,
                expectedSessionGeneration = work.sessionGeneration,
                clockAlignment = work.clockAlignment
            )
        },
        monotonicClockNs = monotonicClockNs
    )
    private val metadataDistributor = AsynchronousFrameDistributor(listOf(metadataWorker))

    fun beginSession(
        sessionGeneration: Long,
        alignment: CameraImuClockAlignment,
        calibration: CameraCalibrationHandle?,
        preserveRecordingEvidence: Boolean = false
    ): Long {
        if (!preserveRecordingEvidence) {
            deadlines.reset()
            latestResources.set(null)
        }
        clockAlignment.set(alignment)
        registry.setCalibration(calibration)
        return registry.beginSession(
            generation = sessionGeneration,
            preserveRetiredSessionRecords = preserveRecordingEvidence
        )
    }

    /** Opens one recording evidence window independently of Camera2 session rebuilds. */
    fun beginRecordingEvidenceWindow() {
        deadlines.reset()
        latestResources.set(null)
        recordingLossAccountingOpen.set(true)
    }

    /**
     * Closes only explicit loss admission. Retained frame identities and accumulated diagnostics
     * remain available for late encoder/muxer completion callbacks during preview restoration.
     */
    fun sealRecordingLossAccounting() {
        recordingLossAccountingOpen.set(false)
    }

    /**
     * Non-blocking Camera2 ingress API. It snapshots small scalar metadata and performs exact timestamp
     * registration only; no pixel access, disk I/O, inference, rendering, logging or executor wait occurs.
     */
    fun onCaptureResult(
        result: TotalCaptureResult,
        captureSessionGeneration: Long
    ): FrameIdentity? {
        val conflictsBefore = registry.identityConflictCount()
        val record = registry.registerCaptureIdentity(result, captureSessionGeneration) ?: return null
        if (registry.identityConflictCount() > conflictsBefore) deadlines.recordIdentityConflict()
        accountCapture(record)
        enqueueTemporalAssociation(record, result)
        return record.identity.snapshot()
    }

    fun onImageAvailable(
        sensorTimestampNs: Long,
        arrivedAtNs: Long = monotonicClockNs()
    ): SensorFrameRecord {
        val record = registry.recordImage(sensorTimestampNs, arrivedAtNs)
        accountCapture(record)
        enqueueTemporalAssociation(record, captureResult = null)
        return record
    }

    fun setCameraCalibration(handle: CameraCalibrationHandle?) {
        registry.setCalibration(handle)
    }

    fun addGyroscope(sample: TimedVector3Sample) = associator.addGyroscope(sample)
    fun addAccelerometer(sample: TimedVector3Sample) = associator.addAccelerometer(sample)
    fun addOrientation(sample: TimedOrientationSample) = associator.addOrientation(sample)

    fun addThermal(sample: TimedThermalSample) {
        associator.addThermal(sample)
        updateResources(
            PipelineResourceSample(
                measuredAtNs = sample.timestampNs,
                thermal = sample
            )
        )
    }

    fun updateTargetFps(targetFps: Double) {
        require(targetFps.isFinite() && targetFps > 0.0)
        targetFpsRef.set(targetFps)
        deadlines.updateTargetFps(targetFps)
    }

    fun updateResources(sample: PipelineResourceSample): PipelineAdaptationDecision {
        val merged = mergeResourceSample(sample)
        deadlines.updateResources(merged)
        return updateAdaptation()
    }

    fun updateQueueCapacity(domain: FrameDomain, capacity: Int): PipelineAdaptationDecision {
        if (capacity > 0) queueCapacity[domain] = capacity else queueCapacity.remove(domain)
        return updateAdaptation()
    }

    fun shouldAdmitSecondary(domain: FrameDomain, record: SensorFrameRecord): Boolean {
        val decision = adaptation.snapshot()
        val divisor = when (domain) {
            FrameDomain.AI -> decision.aiCadenceDivisor
            FrameDomain.VFX -> decision.vfxCadenceDivisor
            FrameDomain.ANALYTICS,
            FrameDomain.IMAGE_PROCESSING -> decision.analyticsCadenceDivisor
            else -> 1
        }
        if (divisor == Int.MAX_VALUE) return false
        return divisor <= 1 || record.identity.snapshot().ingressSequence % divisor.toLong() == 0L
    }

    fun adaptationDecision(): PipelineAdaptationDecision = adaptation.snapshot()

    fun beginStage(
        record: SensorFrameRecord,
        stage: PipelineStage,
        queueEnteredAtNs: Long = record.arrivedAtMonotonicNs,
        queueDepth: Int = 0,
        deadlineNs: Long = defaultDeadlineNs(record, stage),
        startedAtNs: Long = monotonicClockNs()
    ): StageTimingToken = deadlines.beginStage(
        stage = stage,
        identity = record.identity.snapshot(),
        queueEnteredAtNs = queueEnteredAtNs,
        deadlineNs = deadlineNs,
        queueDepth = queueDepth,
        startedAtNs = startedAtNs
    )

    fun finishStage(
        token: StageTimingToken,
        success: Boolean,
        endedAtNs: Long = monotonicClockNs()
    ) = deadlines.finishStage(token, success, endedAtNs)

    fun markDomainQueued(record: SensorFrameRecord, domain: FrameDomain, reason: String? = null) {
        val current = record.lifecycle.domainStatus(domain).outcome
        if (current == FrameDomainOutcome.NOT_REQUESTED ||
            current == FrameDomainOutcome.BACKPRESSURED
        ) {
            record.lifecycle.transitionDomain(
                domain,
                FrameDomainOutcome.QUEUED,
                monotonicClockNs(),
                reason
            )
        }
        when (domain) {
            FrameDomain.AI -> record.lifecycle.mark(FrameLifecycleState.AI_PENDING)
            FrameDomain.VFX -> record.lifecycle.mark(FrameLifecycleState.VFX_PENDING)
            FrameDomain.ENCODER,
            FrameDomain.RECORDING -> record.lifecycle.mark(FrameLifecycleState.ENCODING)
            FrameDomain.STORAGE -> record.lifecycle.mark(FrameLifecycleState.STORAGE_PENDING)
            else -> Unit
        }
    }

    fun markDomainRunning(record: SensorFrameRecord, domain: FrameDomain) {
        val current = record.lifecycle.domainStatus(domain).outcome
        if (current == FrameDomainOutcome.NOT_REQUESTED ||
            current == FrameDomainOutcome.QUEUED ||
            current == FrameDomainOutcome.BACKPRESSURED
        ) {
            record.lifecycle.transitionDomain(domain, FrameDomainOutcome.RUNNING, monotonicClockNs())
        }
    }

    fun markDomainCompleted(record: SensorFrameRecord, domain: FrameDomain) {
        val current = record.lifecycle.domainStatus(domain).outcome
        if (current == FrameDomainOutcome.RUNNING) {
            record.lifecycle.transitionDomain(domain, FrameDomainOutcome.COMPLETED, monotonicClockNs())
        }
        when (domain) {
            FrameDomain.PREVIEW -> {
                record.lifecycle.mark(FrameLifecycleState.PREVIEW_READY)
                if (record.tryAccountPreview()) deadlines.recordPreviewReady()
            }
            FrameDomain.AI -> record.lifecycle.mark(FrameLifecycleState.AI_PROCESSED)
            FrameDomain.VFX -> record.lifecycle.mark(FrameLifecycleState.VFX_PROCESSED)
            FrameDomain.RECORDING -> markRecorded(record)
            FrameDomain.STORAGE -> record.lifecycle.mark(FrameLifecycleState.STORED)
            FrameDomain.DISPLAY -> markDisplayed(record)
            else -> Unit
        }
    }

    fun markDomainSkipped(
        record: SensorFrameRecord,
        domain: FrameDomain,
        reason: String,
        stalePreview: Boolean = false
    ) {
        val current = record.lifecycle.domainStatus(domain).outcome
        if (current != FrameDomainOutcome.COMPLETED &&
            current != FrameDomainOutcome.SKIPPED &&
            current != FrameDomainOutcome.FAILED
        ) {
            record.lifecycle.transitionDomain(
                domain,
                FrameDomainOutcome.SKIPPED,
                monotonicClockNs(),
                reason
            )
            if (domain == FrameDomain.PREVIEW) deadlines.recordPreviewSkipped(stalePreview)
        }
    }

    fun markDomainBackpressured(record: SensorFrameRecord, domain: FrameDomain, reason: String) {
        val current = record.lifecycle.domainStatus(domain).outcome
        if (current == FrameDomainOutcome.NOT_REQUESTED || current == FrameDomainOutcome.QUEUED) {
            record.lifecycle.transitionDomain(
                domain,
                FrameDomainOutcome.BACKPRESSURED,
                monotonicClockNs(),
                reason
            )
        }
    }

    fun markDomainFailed(record: SensorFrameRecord, domain: FrameDomain, throwable: Throwable?) {
        val current = record.lifecycle.domainStatus(domain).outcome
        if (current != FrameDomainOutcome.COMPLETED && current != FrameDomainOutcome.FAILED) {
            record.lifecycle.transitionDomain(
                domain,
                FrameDomainOutcome.FAILED,
                monotonicClockNs(),
                throwable?.javaClass?.simpleName ?: "failure"
            )
            deadlines.recordDomainFailure(domain)
        }
    }

    fun markRecordingDrop(record: SensorFrameRecord?, reason: String): Boolean {
        if (!recordingLossAccountingOpen.get()) {
            if (record != null) {
                markDomainSkipped(
                    record = record,
                    domain = FrameDomain.RECORDING,
                    reason = "outside-sealed-recording-window:$reason"
                )
            }
            return false
        }
        if (record != null) markDomainBackpressured(record, FrameDomain.RECORDING, reason)
        deadlines.recordRecordingDrop(reason = reason)
        updateAdaptation()
        return true
    }

    /** Called only from a confirmed muxer/recording completion callback, never from preview submission. */
    fun onRecordedFrame(sensorTimestampNs: Long): Boolean {
        val record = registry.find(sensorTimestampNs) ?: return false
        markRecorded(record)
        return true
    }

    /** Called only when the compositor/display confirms presentation. */
    fun onDisplayedFrame(sensorTimestampNs: Long): Boolean {
        val record = registry.find(sensorTimestampNs) ?: return false
        markDisplayed(record)
        return true
    }

    fun setQueueDepth(domain: FrameDomain, depth: Int) {
        deadlines.setQueueDepth(domain.pipelineStage(), depth)
    }

    /**
     * Joins independently measured encoder/storage timings back to the authoritative sensor frame.
     * The recording worker owns these clocks; this method only performs bounded identity lookup and
     * atomic diagnostic/lifecycle updates, and is never called from the Camera2 capture callback.
     */
    fun onRecordingStageEvent(
        sensorTimestampNs: Long,
        stage: PipelineStage,
        queueEnteredAtNs: Long,
        startedAtNs: Long,
        endedAtNs: Long,
        queueDepthAtStart: Int,
        currentQueueDepth: Int,
        success: Boolean
    ): Boolean {
        val domain = when (stage) {
            PipelineStage.ENCODER -> FrameDomain.ENCODER
            PipelineStage.STORAGE -> FrameDomain.STORAGE
            else -> return false
        }
        val record = registry.find(sensorTimestampNs) ?: return false
        markDomainQueued(record, domain)
        markDomainRunning(record, domain)
        val safeStartedAtNs = startedAtNs.coerceAtLeast(queueEnteredAtNs)
        val token = beginStage(
            record = record,
            stage = stage,
            queueEnteredAtNs = queueEnteredAtNs,
            queueDepth = queueDepthAtStart,
            startedAtNs = safeStartedAtNs
        )
        finishStage(
            token = token,
            success = success,
            endedAtNs = endedAtNs.coerceAtLeast(safeStartedAtNs)
        )
        deadlines.setQueueDepth(stage, currentQueueDepth)
        if (success) {
            markDomainCompleted(record, domain)
        } else {
            markDomainFailed(record, domain, null)
        }
        return true
    }

    fun diagnostics(): SensorPipelineDiagnosticsSnapshot {
        val snapshot = deadlines.snapshot()
        return if (snapshot.identityConflicts == registry.identityConflictCount()) snapshot else {
            snapshot.copy(identityConflicts = registry.identityConflictCount())
        }
    }

    fun endSession() {
        sealRecordingLossAccounting()
        clockAlignment.set(CameraImuClockAlignment.UNAVAILABLE)
        registry.clear()
    }

    fun close() {
        endSession()
        metadataDistributor.close()
    }

    private fun enqueueTemporalAssociation(
        record: SensorFrameRecord,
        captureResult: TotalCaptureResult?
    ) {
        val sessionGeneration = record.registrySessionGeneration
        if (sessionGeneration == FrameIdentity.UNASSIGNED_SESSION_GENERATION ||
            sessionGeneration != registry.activeSessionGeneration()
        ) return
        val nowNs = monotonicClockNs()
        metadataDistributor.distribute(
            record = record,
            payload = TemporalAssociationWork(
                sessionGeneration = sessionGeneration,
                clockAlignment = clockAlignment.get(),
                captureResult = captureResult
            ),
            releasePayload = { _ -> },
            domains = setOf(FrameDomain.METADATA),
            accessMode = FramePayloadAccessMode.SINGLE_CONSUMER,
            enqueuedAtNs = nowNs,
            deadlineForDomain = { defaultDeadlineNs(record, PipelineStage.METADATA) }
        )
    }

    private fun accountCapture(record: SensorFrameRecord) {
        if (!record.tryAccountCapture()) return
        record.lifecycle.transitionDomain(
            FrameDomain.CAPTURE,
            FrameDomainOutcome.RUNNING,
            record.arrivedAtMonotonicNs
        )
        record.lifecycle.transitionDomain(
            FrameDomain.CAPTURE,
            FrameDomainOutcome.COMPLETED,
            monotonicClockNs()
        )
        deadlines.recordCapture(record.identity.snapshot())
    }

    private fun markRecorded(record: SensorFrameRecord) {
        record.lifecycle.mark(FrameLifecycleState.ENCODING)
        record.lifecycle.mark(FrameLifecycleState.RECORDED)
        if (record.tryAccountRecording()) {
            deadlines.recordRecordingCompleted(record.identity.snapshot())
        }
    }

    private fun markDisplayed(record: SensorFrameRecord) {
        record.lifecycle.mark(FrameLifecycleState.DISPLAYED)
        if (record.tryAccountDisplay()) deadlines.recordDisplayed()
    }

    private fun updateAdaptation(): PipelineAdaptationDecision {
        val fps = targetFpsRef.get()
        val frameIntervalNs = (1_000_000_000.0 / fps).toLong().coerceAtLeast(1L)
        val resolved = AdaptivePipelinePolicy.resolve(
            PipelineLoadSignal(
                diagnostics = deadlines.snapshot(),
                queueCapacity = queueCapacity.toMap(),
                encoderLatencyBudgetNs = frameIntervalNs,
                previewLatencyBudgetNs = frameIntervalNs * 2L
            )
        )
        return adaptation.update(resolved)
    }

    private fun mergeResourceSample(sample: PipelineResourceSample): PipelineResourceSample {
        while (true) {
            val previous = latestResources.get()
            if (previous != null && sample.measuredAtNs < previous.measuredAtNs) return previous
            val merged = PipelineResourceSample(
                measuredAtNs = sample.measuredAtNs,
                cpuUtilization = sample.cpuUtilization ?: previous?.cpuUtilization,
                gpuUtilization = sample.gpuUtilization ?: previous?.gpuUtilization,
                npuDspUtilization = sample.npuDspUtilization ?: previous?.npuDspUtilization,
                memoryUsedBytes = sample.memoryUsedBytes ?: previous?.memoryUsedBytes,
                memoryPressure = sample.memoryPressure ?: previous?.memoryPressure,
                storageWriteMegabytesPerSecond = sample.storageWriteMegabytesPerSecond
                    ?: previous?.storageWriteMegabytesPerSecond,
                storageQueuePressure = sample.storageQueuePressure
                    ?: previous?.storageQueuePressure,
                thermal = sample.thermal ?: previous?.thermal
            )
            if (latestResources.compareAndSet(previous, merged)) return merged
        }
    }

    private fun defaultDeadlineNs(record: SensorFrameRecord, stage: PipelineStage): Long {
        val frameIntervalNs = (1_000_000_000.0 / targetFpsRef.get()).toLong().coerceAtLeast(1L)
        val intervals = when (stage) {
            PipelineStage.SENSOR_CAPTURE,
            PipelineStage.FRAME_DISTRIBUTION,
            PipelineStage.ENCODER -> 1L
            PipelineStage.RECORDING,
            PipelineStage.STORAGE -> 2L
            PipelineStage.PREVIEW,
            PipelineStage.DISPLAY -> 3L
            PipelineStage.RAW_PROCESSING,
            PipelineStage.IMAGE_PROCESSING -> 4L
            PipelineStage.AI,
            PipelineStage.VFX -> 8L
            PipelineStage.METADATA -> 2L
        }
        return saturatingAdd(record.arrivedAtMonotonicNs, frameIntervalNs * intervals)
    }

    private fun stableDecision() = PipelineAdaptationDecision(
        level = SecondaryDegradationLevel.FULL,
        aiCadenceDivisor = 1,
        analyticsCadenceDivisor = 1,
        vfxCadenceDivisor = 1,
        previewQueueLimit = 4,
        aiQueueLimit = 3,
        vfxQueueLimit = 2,
        simplifyPreviewEffects = false,
        reducePreviewResolution = false,
        preferLatestPreviewFrame = false,
        deferBackgroundVfx = false,
        reasons = setOf("stable-full-processing")
    )

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private data class TemporalAssociationWork(
        val sessionGeneration: Long,
        val clockAlignment: CameraImuClockAlignment,
        val captureResult: TotalCaptureResult?
    )
}
