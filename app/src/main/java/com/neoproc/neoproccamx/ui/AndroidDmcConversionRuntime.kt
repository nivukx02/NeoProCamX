package com.neoproc.neoproccamx.ui

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.recording.ConversionCancellationSignal
import com.neoproc.neoproccamx.recording.DmcConversionPerformanceEvidence
import com.neoproc.neoproccamx.recording.DmcConversionTuningRegistry
import com.neoproc.neoproccamx.recording.DmcConversionWorkload
import com.neoproc.neoproccamx.recording.DmcSustainedConversionGovernor
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Android lifecycle owner for one quality-invariant DMC offline-conversion session.
 *
 * The controller publishes scheduling limits to the conversion workers and applies any thermal pacing
 * only at an ordered frame-commit boundary. It never changes pixel processing, NIV99, dimensions, frame
 * cadence, bit depth, codec profile, colour metadata, or source data.
 */
internal class AndroidDmcConversionRuntime(context: Context) {
    private val applicationContext = context.applicationContext
    private val activeSession = AtomicReference<Session?>(null)

    @Volatile
    private var mostRecentEvidence: DmcConversionPerformanceEvidence? = null

    fun <T> run(cancellation: ConversionCancellationSignal, operation: () -> T): T {
        val session = Session(
            context = applicationContext,
            cancellation = cancellation,
            logicalCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        )
        check(activeSession.compareAndSet(null, session)) {
            "A DMC sustained-conversion session is already active"
        }
        try {
            session.start()
            return operation()
        } finally {
            activeSession.compareAndSet(session, null)
            mostRecentEvidence = session.closeAndCollectEvidence()
        }
    }

    /** Updates the scheduling deadline and evidence identity before a converter allocates its worker pool. */
    fun configure(workload: DmcConversionWorkload) {
        activeSession.get()?.configure(workload)
    }

    /** Called after an ordered output frame is committed; safe to invoke from a converter writer thread. */
    fun onFramesCommitted(exactFramesDone: Long) {
        activeSession.get()?.onFramesCommitted(exactFramesDone)
    }

    fun latestEvidence(): DmcConversionPerformanceEvidence? = mostRecentEvidence

    private class Session(
        context: Context,
        private val cancellation: ConversionCancellationSignal,
        logicalCores: Int
    ) {
        private val powerManager = context.getSystemService(PowerManager::class.java)
        private val governor = DmcSustainedConversionGovernor(
            logicalCores = logicalCores,
            startedAtNanos = System.nanoTime()
        )
        private val closed = AtomicBoolean(false)
        private var registryLease: DmcConversionTuningRegistry.Lease? = null
        private var listener: PowerManager.OnThermalStatusChangedListener? = null
        @Volatile private var lastStatusSampleNanos = Long.MIN_VALUE
        @Volatile private var lastHeadroomSampleNanos = Long.MIN_VALUE
        @Volatile private var lastPlatformStatus: Int? = null
        @Volatile private var lastThermalHeadroom: Float? = null
        @Volatile private var monitoringAvailable = false

        fun start() {
            val now = System.nanoTime()
            sampleThermal(now, forceStatus = true, forceHeadroom = true)
            registryLease = DmcConversionTuningRegistry.acquire(governor.tuning())
            val manager = powerManager ?: run {
                NeoLogger.w(TAG, "DMC thermal monitor unavailable: PowerManager was not provided")
                return
            }
            val callback = PowerManager.OnThermalStatusChangedListener { status ->
                if (closed.get()) return@OnThermalStatusChangedListener
                val sampledAt = System.nanoTime()
                lastPlatformStatus = status
                lastStatusSampleNanos = sampledAt
                publish(governor.observeThermal(status, lastThermalHeadroom, sampledAt))
            }
            try {
                manager.addThermalStatusListener(callback)
                listener = callback
                monitoringAvailable = true
            } catch (failure: RuntimeException) {
                NeoLogger.w(TAG, "DMC thermal callback unavailable; bounded polling remains active", failure)
            }
        }

        fun configure(workload: DmcConversionWorkload) {
            if (closed.get()) return
            publish(governor.configure(workload))
            val now = System.nanoTime()
            sampleThermal(now, forceStatus = true, forceHeadroom = false)
            // Do not process even the first expensive frame while Android reports CRITICAL or
            // EMERGENCY. Waiting here, before converter buffers/workers are started, prevents the
            // previous one-frame thermal overshoot and resumes the exact same quality pipeline after
            // recovery. Cancellation remains responsive through the bounded polling loop.
            waitForSafeBoundaryIfNeeded()
            NeoLogger.i(
                TAG,
                "DMC conversion configured route=${workload.route} " +
                    "geometry=${workload.width}x${workload.height}@${workload.sourceFps} " +
                    "compressed=${workload.compressedOutput} tuning=${governor.tuning()}"
            )
        }

        fun onFramesCommitted(exactFramesDone: Long) {
            if (closed.get() || exactFramesDone <= 0L) return
            cancellation.throwIfCancellationRequested()
            val now = System.nanoTime()
            sampleThermal(now, forceStatus = false, forceHeadroom = false)
            val pacingNanos = governor.onFramesCommitted(exactFramesDone, now)
            waitForSafeBoundaryIfNeeded()
            if (pacingNanos > 0L) applyPacing(pacingNanos)
        }

        fun closeAndCollectEvidence(): DmcConversionPerformanceEvidence {
            if (closed.compareAndSet(false, true)) {
                listener?.let { registered ->
                    powerManager?.let { manager ->
                        runCatching { manager.removeThermalStatusListener(registered) }
                            .onFailure {
                                NeoLogger.w(TAG, "DMC thermal callback removal failed", it)
                            }
                    }
                }
                listener = null
                registryLease?.close()
                registryLease = null
            }
            val evidence = governor.evidence(System.nanoTime())
            NeoLogger.i(
                TAG,
                String.format(
                    Locale.US,
                    "DMC conversion evidence route=%s frames=%d fps=%.3f p50Ms=%.3f " +
                        "p95Ms=%.3f p99Ms=%.3f maxMs=%.3f maxThermal=%s pacedMs=%.3f " +
                        "monitor=%s qualityInvariant=%s",
                    evidence.route,
                    evidence.completedFrames,
                    evidence.averageFramesPerSecond,
                    evidence.p50FrameNanos / 1_000_000.0,
                    evidence.p95FrameNanos / 1_000_000.0,
                    evidence.p99FrameNanos / 1_000_000.0,
                    evidence.maximumFrameNanos / 1_000_000.0,
                    evidence.maximumThermalBand.name,
                    evidence.totalPacingNanos / 1_000_000.0,
                    monitoringAvailable,
                    evidence.qualityInvariant
                )
            )
            return evidence
        }

        private fun waitForSafeBoundaryIfNeeded() {
            while (governor.tuning().pauseAtSafeBoundary) {
                cancellation.throwIfCancellationRequested()
                if (Thread.currentThread().isInterrupted) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("DMC conversion interrupted at a thermal safety boundary")
                }
                LockSupport.parkNanos(CRITICAL_RECHECK_NANOS)
                sampleThermal(System.nanoTime(), forceStatus = true, forceHeadroom = false)
            }
        }

        private fun applyPacing(requestedNanos: Long) {
            val started = System.nanoTime()
            var remaining = requestedNanos
            while (remaining > 0L) {
                cancellation.throwIfCancellationRequested()
                if (Thread.currentThread().isInterrupted) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("DMC conversion interrupted during thermal pacing")
                }
                LockSupport.parkNanos(minOf(remaining, MAX_PARK_SLICE_NANOS))
                remaining = requestedNanos - (System.nanoTime() - started)
            }
            governor.recordPacingApplied((System.nanoTime() - started).coerceAtLeast(0L))
        }

        private fun sampleThermal(nowNanos: Long, forceStatus: Boolean, forceHeadroom: Boolean) {
            val manager = powerManager ?: return
            var changed = false
            if (forceStatus || elapsedAtLeast(nowNanos, lastStatusSampleNanos, STATUS_POLL_NANOS)) {
                lastPlatformStatus = runCatching { manager.currentThermalStatus }
                    .onFailure { NeoLogger.w(TAG, "DMC thermal-status sample failed", it) }
                    .getOrNull()
                lastStatusSampleNanos = nowNanos
                monitoringAvailable = monitoringAvailable || lastPlatformStatus != null
                changed = true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                (forceHeadroom || elapsedAtLeast(nowNanos, lastHeadroomSampleNanos, HEADROOM_POLL_NANOS))
            ) {
                val sampled = runCatching { manager.getThermalHeadroom(HEADROOM_FORECAST_SECONDS) }
                    .onFailure { NeoLogger.w(TAG, "DMC thermal-headroom sample failed", it) }
                    .getOrNull()
                    ?.takeIf { it.isFinite() && it >= 0f }
                if (sampled != null) lastThermalHeadroom = sampled
                lastHeadroomSampleNanos = nowNanos
                changed = true
            }
            if (changed) {
                publish(
                    governor.observeThermal(
                        platformStatus = lastPlatformStatus,
                        thermalHeadroom = lastThermalHeadroom,
                        nowNanos = nowNanos
                    )
                )
            }
        }

        private fun publish(tuning: com.neoproc.neoproccamx.recording.DmcExecutionTuning) {
            registryLease?.publish(tuning)
        }

        private fun elapsedAtLeast(now: Long, previous: Long, interval: Long): Boolean =
            previous == Long.MIN_VALUE || now - previous >= interval
    }

    private companion object {
        const val TAG = "DmcConversionRuntime"
        const val STATUS_POLL_NANOS = 1_000_000_000L
        const val HEADROOM_POLL_NANOS = 10_000_000_000L
        const val CRITICAL_RECHECK_NANOS = 100_000_000L
        const val MAX_PARK_SLICE_NANOS = 2_000_000L
        const val HEADROOM_FORECAST_SECONDS = 10
    }
}
