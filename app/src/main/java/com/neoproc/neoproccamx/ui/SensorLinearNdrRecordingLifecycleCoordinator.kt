package com.neoproc.neoproccamx.ui

import android.os.SystemClock
import android.util.Log
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.pipeline.RawVideoManager
import com.neoproc.neoproccamx.pipeline.SensorLinearNdrRecordingFailure
import com.neoproc.neoproccamx.pipeline.SensorLinearNdrRecordingIngressPolicy
import com.neoproc.neoproccamx.pipeline.SensorLinearNdrRecordingStartupGate
import com.neoproc.neoproccamx.pipeline.SensorLinearNdrRecordingStopResult
import com.neoproc.neoproccamx.rendering.SensorLinearNdrDemosaicTransitionStatus
import com.neoproc.neoproccamx.rendering.SensorLinearNdrFailedTailRetirementStatus
import com.neoproc.neoproccamx.rendering.VulkanRuntimeManager
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the RAW -> NDR recording cutover transaction.
 *
 * Keeping the Camera2-thread cutover, Vulkan ownership transition, and failure retirement in one
 * coordinator prevents callers from publishing ImageReader admission before the full-resolution
 * recording resources and shader pipeline are proven ready.
 */
internal class SensorLinearNdrRecordingLifecycleCoordinator(
    private val camera: CameraController,
    private val rawVideoManager: RawVideoManager,
    private val vulkanRuntime: VulkanRuntimeManager,
    private val targetFps: () -> Int,
    private val failure: AtomicReference<SensorLinearNdrRecordingFailure?>,
    private val abortScheduled: AtomicBoolean,
    private val publishWarning: (String) -> Unit,
    private val requestRecordingStop: () -> Unit
) {
    private data class PostSwitchStartupAttempt(
        val transaction: SensorLinearNdrRecordingStartupGate.Transaction,
        val baselineRepeatingPublicationGeneration: Long
    )

    private val startupAttempt = AtomicReference<PostSwitchStartupAttempt?>(null)
    private val startupReadiness =
        AtomicReference<SensorLinearNdrRecordingStartupGate.ReadinessLease?>(null)

    fun beginPostSwitchExactPairReadiness(): Boolean {
        cancelPostSwitchExactPairReadiness()
        val transaction = rawVideoManager.beginSensorLinearNdrRecordingStartupReadiness()
            ?: return false
        startupAttempt.set(
            PostSwitchStartupAttempt(
                transaction = transaction,
                baselineRepeatingPublicationGeneration =
                    camera.currentRepeatingReadinessSnapshot()?.publicationGeneration
                        ?: NO_REPEATING_PUBLICATION
            )
        )
        return true
    }

    suspend fun awaitPostSwitchExactPairReadiness(): Boolean {
        val attempt = startupAttempt.get() ?: return false
        val transaction = attempt.transaction
        val expectedSessionGeneration = camera.sessionGeneration.get()
        val fps = targetFps().coerceAtLeast(1)
        val pollDelayMs = (
            SensorLinearNdrRecordingIngressPolicy.retryPollDelayNs(fps) / NANOS_PER_MILLISECOND
            ).coerceAtLeast(1L)
        val readiness = withTimeoutOrNull(POST_SWITCH_EXACT_PAIR_TIMEOUT_MS) {
            while (currentCoroutineContext().isActive) {
                if (startupAttempt.get() !== attempt ||
                    camera.sessionGeneration.get() != expectedSessionGeneration
                ) return@withTimeoutOrNull null
                val repeating = camera.currentRepeatingReadinessSnapshot()
                val firstRepeatingTimestampNs = repeating?.firstFrameSensorTimestampNs
                if (repeating == null ||
                    repeating.sessionGeneration != expectedSessionGeneration ||
                    repeating.publicationGeneration ==
                        attempt.baselineRepeatingPublicationGeneration ||
                    firstRepeatingTimestampNs == null || firstRepeatingTimestampNs <= 0L
                ) {
                    delay(pollDelayMs)
                    continue
                }
                rawVideoManager.tryAcquireSensorLinearNdrRecordingStartupReadiness(
                    transaction = transaction,
                    expectedSessionGeneration = expectedSessionGeneration,
                    minimumSensorTimestampNs = firstRepeatingTimestampNs
                )?.let { return@withTimeoutOrNull it }
                delay(pollDelayMs)
            }
            null
        }
        if (readiness == null || startupAttempt.get() !== attempt) {
            Log.e(
                TAG,
                "No exact post-switch RAW/result pair became ready for session=" +
                    "$expectedSessionGeneration within ${POST_SWITCH_EXACT_PAIR_TIMEOUT_MS}ms"
            )
            return false
        }
        startupReadiness.set(readiness)
        Log.i(
            TAG,
            "NDR_RECORD_STARTUP_READY session=${readiness.proof.sessionGeneration} " +
                "sensorTimestampNs=${readiness.proof.sensorTimestampNs}"
        )
        return true
    }

    suspend fun awaitDemosaicTransition(): Boolean {
        val fps = targetFps().coerceAtLeast(1)
        val timeoutNs = SensorLinearNdrRecordingIngressPolicy.completionDeadlineNs(fps)
        val timeoutMs = (timeoutNs / NANOS_PER_MILLISECOND).coerceAtLeast(1L)
        val pollDelayMs = (
            SensorLinearNdrRecordingIngressPolicy.retryPollDelayNs(fps) / NANOS_PER_MILLISECOND
            ).coerceAtLeast(1L)
        var ready = false
        try {
            val ownershipReady = withTimeoutOrNull(timeoutMs) {
                while (currentCoroutineContext().isActive) {
                    when (vulkanRuntime.beginSensorLinearNdrRecordingDemosaicTransition()) {
                        SensorLinearNdrDemosaicTransitionStatus.READY -> return@withTimeoutOrNull true
                        SensorLinearNdrDemosaicTransitionStatus.FAILED -> return@withTimeoutOrNull false
                        SensorLinearNdrDemosaicTransitionStatus.PENDING -> delay(pollDelayMs)
                    }
                }
                false
            } ?: false
            if (!ownershipReady) return false

            val pipelineWaitStartedNs = SystemClock.elapsedRealtimeNanos()
            val pipelineReady = withTimeoutOrNull(RAW_PIPELINE_READY_TIMEOUT_MS) {
                while (currentCoroutineContext().isActive) {
                    if (vulkanRuntime.isRawDemosaicPipelineReady()) return@withTimeoutOrNull true
                    delay(pollDelayMs)
                }
                false
            } ?: false
            val pipelineWaitMs =
                (SystemClock.elapsedRealtimeNanos() - pipelineWaitStartedNs).coerceAtLeast(0L) /
                    NANOS_PER_MILLISECOND
            if (!pipelineReady) {
                Log.e(TAG, "RAW demosaic pipeline did not become ready within ${RAW_PIPELINE_READY_TIMEOUT_MS}ms")
                return false
            }
            Log.i(TAG, "RAW demosaic pipeline readiness proven before NDR ingress waitMs=$pipelineWaitMs")

            val rawReader = camera.rawReader ?: return false
            ready = withContext(Dispatchers.Default) {
                vulkanRuntime.prepareSensorLinearNdrRecordingDemosaicResources(
                    width = rawReader.width,
                    height = rawReader.height
                )
            }
            return ready
        } finally {
            if (!ready) vulkanRuntime.abortSensorLinearNdrRecordingDemosaicTransition()
        }
    }

    suspend fun startIngress(): Boolean {
        val readiness = startupReadiness.getAndSet(null) ?: run {
            Log.e(TAG, "NDR recording ingress cannot arm before exact post-switch RAW/result readiness")
            return false
        }
        startupAttempt.set(null)
        val readerMaxImages = camera.rawReader?.maxImages ?: run {
            rawVideoManager.cancelSensorLinearNdrRecordingStartupReadiness(readiness)
            Log.e(TAG, "NDR recording ingress cannot arm without a live RAW reader")
            return false
        }
        failure.set(null)
        abortScheduled.set(false)
        val expectedSessionGeneration = camera.sessionGeneration.get()
        val completion = CompletableDeferred<Boolean>()
        val cutoverAction = Runnable {
            val armed = runCatching {
                rawVideoManager.startSensorLinearNdrRecordingIngress(
                    readerMaxImages = readerMaxImages,
                    targetFps = targetFps().coerceAtLeast(1),
                    onFailure = ::onIngressFailure,
                    startupReadiness = readiness,
                    expectedSessionGeneration = expectedSessionGeneration
                )
            }.onFailure { throwable ->
                Log.e(TAG, "Camera2-serial NDR cutover failed", throwable)
            }.getOrDefault(false)
            if (!completion.complete(armed) && armed) {
                rawVideoManager.stopSensorLinearNdrRecordingIngress(
                    drain = false,
                    timeoutMs = CUTOVER_ARM_TIMEOUT_MS
                )
            }
        }
        val cameraHandler = camera.backgroundHandler ?: run {
            rawVideoManager.cancelSensorLinearNdrRecordingStartupReadiness(readiness)
            Log.e(TAG, "NDR cutover cannot arm without CameraBackground")
            return false
        }
        if (!cameraHandler.post(cutoverAction)) {
            rawVideoManager.cancelSensorLinearNdrRecordingStartupReadiness(readiness)
            Log.e(TAG, "CameraBackground rejected the NDR cutover action")
            return false
        }
        val armed = withTimeoutOrNull(CUTOVER_ARM_TIMEOUT_MS) { completion.await() }
        if (armed != null) return armed

        completion.cancel(CancellationException("Camera2-serial NDR cutover timed out"))
        cameraHandler.removeCallbacks(cutoverAction)
        rawVideoManager.cancelSensorLinearNdrRecordingStartupReadiness(readiness)
        Log.e(TAG, "Camera2-serial NDR cutover timed out after ${CUTOVER_ARM_TIMEOUT_MS}ms")
        return false
    }

    fun stopIngress(
        drain: Boolean,
        timeoutMs: Long = DEFAULT_STOP_TIMEOUT_MS
    ): SensorLinearNdrRecordingStopResult {
        cancelPostSwitchExactPairReadiness()
        return rawVideoManager.stopSensorLinearNdrRecordingIngress(
            drain = drain,
            timeoutMs = timeoutMs
        )
    }

    private fun cancelPostSwitchExactPairReadiness() {
        startupReadiness.getAndSet(null)?.let(
            rawVideoManager::cancelSensorLinearNdrRecordingStartupReadiness
        )
        startupAttempt.getAndSet(null)?.transaction?.let { transaction ->
            rawVideoManager.cancelSensorLinearNdrRecordingStartupReadiness(transaction)
        }
    }

    fun releaseDemosaicOwnership(): Boolean {
        if (!vulkanRuntime.isSensorLinearNdrRecordingDemosaicActive()) return true
        if (vulkanRuntime.endSensorLinearNdrRecordingDemosaic()) return true
        return when (vulkanRuntime.retireFailedSensorLinearNdrRecordingTail()) {
            SensorLinearNdrFailedTailRetirementStatus.RETIRED -> true
            SensorLinearNdrFailedTailRetirementStatus.PENDING,
            SensorLinearNdrFailedTailRetirementStatus.ENCODER_STILL_BOUND,
            SensorLinearNdrFailedTailRetirementStatus.TERMINAL_NATIVE_FAILURE -> false
        }
    }

    private fun onIngressFailure(ingressFailure: SensorLinearNdrRecordingFailure) {
        if (!failure.compareAndSet(null, ingressFailure)) return
        val message = buildString {
            append("Sensor-linear NDR recording aborted: ")
            append(ingressFailure.reason)
            ingressFailure.sensorTimestampNs?.let {
                append(" (sensorTimestampNs=").append(it).append(')')
            }
        }
        Log.e(TAG, message, ingressFailure.cause)
        publishWarning(message)
        if (abortScheduled.compareAndSet(false, true)) requestRecordingStop()
    }

    private companion object {
        const val TAG = "SensorLinearNdrLifecycle"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val RAW_PIPELINE_READY_TIMEOUT_MS = 5_000L
        const val POST_SWITCH_EXACT_PAIR_TIMEOUT_MS = 5_000L
        const val NO_REPEATING_PUBLICATION = -1L
        const val CUTOVER_ARM_TIMEOUT_MS = 2_000L
        const val DEFAULT_STOP_TIMEOUT_MS = 2_000L
    }
}
