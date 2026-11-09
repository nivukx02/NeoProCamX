package com.neoproc.neoproccamx.ui

import android.util.Log
import com.neoproc.neoproccamx.pipeline.RawVideoManager
import com.neoproc.neoproccamx.rendering.SensorLinearNdrFailedTailRetirementStatus
import com.neoproc.neoproccamx.rendering.VulkanRuntimeManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs a final native-resource retirement lease outside ViewModelScope and the Android main thread.
 *
 * A camera/GPU worker that is already inside JNI cannot be interrupted safely and its Surface,
 * HardwareBuffer, ImageReader, MediaCodec, and native bridge must not be reclaimed on a timeout. The
 * lease therefore waits as long as the ownership barrier requires. Its daemon worker intentionally
 * quarantines those resources if a vendor call never returns, while lifecycle/UI teardown remains live.
 */
internal class NativeResourceRetirementCoordinator(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Native-Camera-Retirement-${threadIds.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }
) {
    private val scheduled = AtomicBoolean(false)

    fun schedule(label: String, retirement: () -> Unit): Boolean {
        return schedule(label, quarantineOwner = null, retirement = retirement)
    }

    fun schedule(label: String, quarantineOwner: Any?, retirement: () -> Unit): Boolean {
        if (!scheduled.compareAndSet(false, true)) {
            quarantineOwner?.let {
                NativeCameraResourceQuarantine.retain("duplicate schedule rejected: $label", it)
            }
            return false
        }
        return try {
            executor.execute {
                try {
                    retirement()
                } catch (failure: Throwable) {
                    Log.e(TAG, "Native camera retirement failed: $label", failure)
                    quarantineOwner?.let {
                        NativeCameraResourceQuarantine.retain("retirement threw: $label", it)
                    }
                } finally {
                    executor.shutdown()
                }
            }
            true
        } catch (failure: Throwable) {
            scheduled.set(false)
            executor.shutdownNow()
            Log.e(TAG, "Unable to schedule native camera retirement: $label", failure)
            quarantineOwner?.let {
                NativeCameraResourceQuarantine.retain("schedule failed: $label", it)
            }
            false
        }
    }

    private companion object {
        const val TAG = "NativeCameraRetirement"
        val threadIds = AtomicLong(0L)
    }
}

/** Bounded, synchronous retry policy used only on the dedicated retirement daemon. */
internal object NativeResourceRetirementRetryPolicy {
    const val RAW_MANAGER_MAX_ATTEMPTS = 3

    fun retire(
        maxAttempts: Int = RAW_MANAGER_MAX_ATTEMPTS,
        attempt: (attemptNumber: Int) -> Boolean
    ): Boolean {
        require(maxAttempts > 0)
        for (attemptNumber in 1..maxAttempts) {
            if (attempt(attemptNumber)) return true
        }
        return false
    }
}

/** Strong process-lifetime ownership for resources that cannot be reclaimed without risking UAF. */
internal object NativeCameraResourceQuarantine {
    private val retainedOwners = Collections.synchronizedMap(IdentityHashMap<Any, String>())

    fun retain(reason: String, owner: Any) {
        retainedOwners[owner] = reason
        Log.e(TAG, "Native camera ownership quarantined until process exit: $reason")
    }

    private const val TAG = "NativeCameraQuarantine"
}

internal data class CameraDependentRetirementOutcome(
    val cameraRetired: Boolean,
    val dependentsInvoked: Boolean,
    val cameraFailure: Throwable?
)

/**
 * Fail-stop boundary between Camera2/ImageReader retirement and every resource that can still be
 * reached by a camera callback. The exact ownership bundle is retained before this method returns a
 * failed outcome, and [retireDependents] is structurally unreachable until the camera proof succeeds.
 */
internal object CameraDependentRetirementGate {
    fun execute(
        quarantineOwner: Any,
        retireCamera: () -> Boolean,
        retireDependents: () -> Unit,
        retainQuarantine: (reason: String, owner: Any) -> Unit =
            NativeCameraResourceQuarantine::retain
    ): CameraDependentRetirementOutcome {
        val cameraRetired = try {
            retireCamera()
        } catch (failure: Throwable) {
            retainQuarantine(
                "camera reader retirement threw ${failure.javaClass.simpleName}; " +
                    "frame pipeline and dependent resources preserved",
                quarantineOwner
            )
            return CameraDependentRetirementOutcome(
                cameraRetired = false,
                dependentsInvoked = false,
                cameraFailure = failure
            )
        }
        if (!cameraRetired) {
            retainQuarantine(
                "camera reader retirement failed; frame pipeline and dependent resources preserved",
                quarantineOwner
            )
            return CameraDependentRetirementOutcome(
                cameraRetired = false,
                dependentsInvoked = false,
                cameraFailure = null
            )
        }

        retireDependents()
        return CameraDependentRetirementOutcome(
            cameraRetired = true,
            dependentsInvoked = true,
            cameraFailure = null
        )
    }
}

/** Exact native/resource ownership bundle transferred out of CameraViewModel at lifecycle teardown. */
internal class CameraNativeResourceRetirementLease(
    private val recordingStopOwnerActive: () -> Boolean,
    private val recordingPrearmTeardownSeal: RecordingSurfacePreparationCoordinator.TeardownSeal,
    private val stopRecordingProtection: () -> Unit,
    private val rawVideoManager: RawVideoManager,
    private val vulkanRuntime: VulkanRuntimeManager,
    private val freezeAndDetachFrameLineage: () -> Unit,
    private val retireNativeRawPreview: () -> Boolean,
    private val releaseNpv10Recorder: () -> Unit,
    private val destroyGpuRecorder: () -> Unit,
    private val destroyStillCapture: () -> Boolean,
    private val destroyCamera: () -> Boolean,
    private val destroyPipeline: () -> Unit,
    private val destroyAiAf: () -> Unit,
    private val closeAiPipeline: () -> Unit,
    private val releaseRecordingIoIsolation: () -> Unit = {}
) {
    fun retire() {
        // CameraViewModel's stop coroutine may still be restoring preview or publishing the result
        // after the recorder itself has sealed. The retirement daemon must not destroy Camera2,
        // ImageReaders, pipeline, or Vulkan until that single stop owner releases its atomic gate.
        // Waiting here never blocks the lifecycle/main thread.
        while (recordingStopOwnerActive()) {
            try {
                Thread.sleep(RECORDING_STOP_OWNER_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                quarantine("recording stop ownership wait was interrupted")
                return
            }
        }
        val prearmQuiescence = recordingPrearmTeardownSeal.awaitQuiescence(
            RECORDING_PREARM_QUIESCENCE_TIMEOUT_MS
        )
        if (prearmQuiescence != RecordingSurfacePreparationCoordinator.TeardownQuiescence.PROVEN) {
            quarantine("recording pre-arm transaction did not quiesce: $prearmQuiescence")
            return
        }
        // Seal and release the strict private master while Camera2/ImageReader ownership is still
        // intact. Foreground protection remains active through this call, then is stopped exactly
        // once by the retirement owner before any potentially long unrelated native teardown.
        val npv10RecorderRetired = retireSafely("NPV10 recorder", releaseNpv10Recorder)
        retireSafely("recording foreground protection", stopRecordingProtection)
        if (!npv10RecorderRetired) {
            quarantine("NPV10 recorder did not retire before dependent camera resources")
            return
        }
        var ingressFailureObserved = false
        val ingressRetired = NativeResourceRetirementRetryPolicy.retire { attemptNumber ->
            val stopResult = runCatching {
                rawVideoManager.stopSensorLinearNdrRecordingIngress(
                    drain = false,
                    timeoutMs = 250L
                )
            }.onFailure {
                Log.e(TAG, "Recorder ingress stop threw attempt=$attemptNumber", it)
            }.getOrNull()
            ingressFailureObserved = ingressFailureObserved || stopResult?.ingressFailed == true
            val quiescent = rawVideoManager.isSensorLinearNdrRecordingWorkerQuiescentForTeardown()
            if (!quiescent &&
                attemptNumber < NativeResourceRetirementRetryPolicy.RAW_MANAGER_MAX_ATTEMPTS
            ) {
                Log.w(TAG, "Recorder worker not quiescent; retrying stop/proof attempt=${attemptNumber + 1}")
            }
            stopResult?.teardownSafe == true && quiescent
        }
        if (!ingressRetired) {
            Log.e(TAG, "Recorder worker not quiescent; native camera resources quarantined")
            quarantine("recorder worker did not prove quiescence after bounded retries")
            return
        }
        if (ingressFailureObserved) {
            Log.w(TAG, "Ingress retired after a reported recording failure; ownership is safe")
        }

        // Recorder ingress can be idle while the independently downsampled preview copy is still
        // crossing EGL/glFinish. Quiesce that ordered domain before freezing its certificate or
        // reclaiming the preview/encoder native objects. A failed drain quarantines the exact bundle.
        val previewWorkerBefore = vulkanRuntime.sensorLinearNdrRecordingPreviewWorkerSnapshot()
        val previewDrainRequired = vulkanRuntime.supported.value ||
            vulkanRuntime.isSensorLinearNdrRecordingDemosaicActive() ||
            previewWorkerBefore.acceptedTasks > 0L ||
            previewWorkerBefore.outstandingTasks > 0
        val previewWorkerQuiesced = !previewDrainRequired || runCatching {
            vulkanRuntime.drainSensorLinearNdrRecordingPreview(requireHealthy = false)
        }.onFailure {
            Log.e(TAG, "Recorder preview worker drain threw during teardown", it)
        }.getOrDefault(false)
        val previewWorkerAfter = vulkanRuntime.sensorLinearNdrRecordingPreviewWorkerSnapshot()
        if (!previewWorkerQuiesced || previewWorkerAfter.outstandingTasks != 0) {
            quarantine(
                "recorder preview worker did not prove quiescence before lineage/native teardown"
            )
            return
        }
        previewWorkerAfter.lastFailure?.let { failure ->
            Log.e(TAG, "Recorder preview domain retired with a clip-quality failure: $failure")
        }
        if (!retireSafely("recording frame-lineage boundary", freezeAndDetachFrameLineage)) {
            quarantine("recording frame-lineage boundary did not freeze after preview drain")
            return
        }

        vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(false)
        val encoderDetached =
            NativeResourceRetirementRetryPolicy.retire { attemptNumber ->
                val detached = runCatching {
                    vulkanRuntime.detachAllEncoderSurfacesWithProof()
                }.onFailure {
                    Log.e(TAG, "Atomic GPU encoder detach threw attempt=$attemptNumber", it)
                }.getOrDefault(false)
                if (!detached &&
                    attemptNumber < NativeResourceRetirementRetryPolicy.RAW_MANAGER_MAX_ATTEMPTS
                ) {
                    Log.w(
                        TAG,
                        "Both GPU encoder detach proofs were not acknowledged; " +
                            "retrying attempt=${attemptNumber + 1}"
                    )
                }
                detached
            }
        if (!encoderDetached) {
            // EGL may still be reading the codec Surface and the RAW demosaic output that feeds it.
            // Retiring the RAW manager, demosaic, codec, Camera2 readers, or Vulkan beneath that
            // unacknowledged reader would turn a recoverable detach timeout into a native UAF. Keep
            // this exact lease strongly reachable so a later/process-lifetime cleanup owns every
            // dependency instead of falsely publishing successful teardown.
            quarantine(
                "atomic core/offscreen encoder detach was not acknowledged; " +
                    "dependent resources preserved"
            )
            return
        }
        val rawManagerRetired = NativeResourceRetirementRetryPolicy.retire { attemptNumber ->
            val retired = runCatching { rawVideoManager.destroy() }
                .onFailure {
                    Log.e(TAG, "Failed to retire RAW manager attempt=$attemptNumber", it)
                }
                .getOrDefault(false)
            if (!retired &&
                attemptNumber < NativeResourceRetirementRetryPolicy.RAW_MANAGER_MAX_ATTEMPTS
            ) {
                Log.w(TAG, "RAW manager retirement incomplete; retrying attempt=${attemptNumber + 1}")
            }
            retired
        }
        if (!rawManagerRetired) {
            quarantine("RAW manager teardown did not finalize after bounded retries")
            return
        }

        val previewSurfaceRetired = retireWithProof(
            "sensor-linear preview surface",
            retireNativeRawPreview
        )
        var cleanupSafe = previewSurfaceRetired
        val demosaicReleased = runCatching {
            when {
                !vulkanRuntime.isSensorLinearNdrRecordingDemosaicActive() -> true
                !encoderDetached -> false
                vulkanRuntime.endSensorLinearNdrRecordingDemosaic() -> true
                else -> vulkanRuntime.retireFailedSensorLinearNdrRecordingTail() ==
                    SensorLinearNdrFailedTailRetirementStatus.RETIRED
            }
        }.getOrElse {
            Log.e(TAG, "RAW demosaic retirement threw; Vulkan quarantined", it)
            false
        }

        val stillCaptureRetired = retireWithProof("still capture", destroyStillCapture)
        cleanupSafe = stillCaptureRetired && cleanupSafe
        if (!stillCaptureRetired) {
            quarantine("still capture retained Image ownership; camera and pipeline preserved")
            return
        }
        val cameraRetirement = CameraDependentRetirementGate.execute(
            quarantineOwner = this,
            retireCamera = destroyCamera,
            retireDependents = {
                // Camera2 can retain the codec input Surface even after Kotlin clears recordSurface.
                // Its session/device retirement proof must complete before MediaCodec releases it.
                cleanupSafe = retireSafely("GPU recorder", destroyGpuRecorder) && cleanupSafe
                cleanupSafe = retireSafely("frame pipeline", destroyPipeline) && cleanupSafe
                cleanupSafe = retireSafely("AI autofocus", destroyAiAf) && cleanupSafe
                cleanupSafe = retireSafely("AI camera pipeline", closeAiPipeline) && cleanupSafe
                if (encoderDetached && demosaicReleased && previewSurfaceRetired) {
                    val vulkanRetired = NativeResourceRetirementRetryPolicy.retire { attemptNumber ->
                        val retired = retireWithProof(
                            "Vulkan runtime attempt=$attemptNumber",
                            vulkanRuntime::closeWithRetirementProof
                        )
                        if (!retired &&
                            attemptNumber < NativeResourceRetirementRetryPolicy.RAW_MANAGER_MAX_ATTEMPTS
                        ) {
                            Log.w(
                                TAG,
                                "Vulkan runtime retirement was not acknowledged; " +
                                    "retrying attempt=${attemptNumber + 1}"
                            )
                        }
                        retired
                    }
                    cleanupSafe = vulkanRetired && cleanupSafe
                } else {
                    Log.e(TAG, "Vulkan retained because RAW/encoder ownership was not safely retired")
                }
                if (!encoderDetached || !demosaicReleased || !previewSurfaceRetired || !cleanupSafe) {
                    quarantine(
                        "encoderDetached=$encoderDetached demosaicReleased=$demosaicReleased " +
                            "previewSurfaceRetired=$previewSurfaceRetired cleanupSafe=$cleanupSafe"
                    )
                }
            }
        )
        if (!cameraRetirement.cameraRetired) {
            cameraRetirement.cameraFailure?.let { failure ->
                Log.e(TAG, "Camera controller retirement proof threw; dependents preserved", failure)
            }
            return
        }
        if (cleanupSafe && demosaicReleased) releaseRecordingIoIsolation()
    }

    private fun quarantine(reason: String) {
        NativeCameraResourceQuarantine.retain(reason, this)
    }

    private inline fun retireSafely(resource: String, retirement: () -> Unit): Boolean {
        return runCatching(retirement)
            .onFailure { Log.e(TAG, "Failed to retire $resource", it) }
            .isSuccess
    }

    private inline fun retireWithProof(resource: String, retirement: () -> Boolean): Boolean {
        return runCatching(retirement)
            .onFailure { Log.e(TAG, "Failed to retire $resource", it) }
            .getOrDefault(false)
    }

    private companion object {
        const val TAG = "NativeCameraRetirement"
        const val RECORDING_STOP_OWNER_POLL_MS = 10L
        const val RECORDING_PREARM_QUIESCENCE_TIMEOUT_MS = 6_000L
    }
}
