package com.neoproc.neoproccamx.rendering

import android.util.Log

internal enum class SensorLinearNdrPreviewPreparation {
    PREPARED,
    /** Visible compositor or coherent runtime packet has not reached its cold-start readiness edge. */
    DEFERRED_UNREADY,
    /** A ready route rejected native surface/profile preparation. */
    FAILED
}

/**
 * Presents the exact RAW-derived NDR master through the visible HDR child.
 *
 * Vulkan retains scene-linear ACEScg in RGBA16F (or the project-owned 10-bit scene-log fallback).
 * [VulkanRuntimeManager] imports that AHardwareBuffer into the canonical NDR/NIVUKX operator and
 * publishes 10-bit project-owned PQ into the compositor's dedicated queue. PQ is an output transport
 * here, never Camera2/vendor HDR input.
 */
internal class SensorLinearNdrPreviewPresenter(
    private val vulkanRuntime: VulkanRuntimeManager,
    private val compositor: Hdr10PqPreviewCompositor,
    private val isSessionGenerationCurrent: (Long) -> Boolean
) {
    fun prepare(peakPlan: NdrPeakCapabilityPolicy.Plan): SensorLinearNdrPreviewPreparation {
        val surface = compositor.projectOwnedNdrInputSurface()
            ?: return SensorLinearNdrPreviewPreparation.DEFERRED_UNREADY
        val width = compositor.projectOwnedNdrInputWidth()
        val height = compositor.projectOwnedNdrInputHeight()
        if (!compositor.isRunning() || width <= 0 || height <= 0) {
            return SensorLinearNdrPreviewPreparation.DEFERRED_UNREADY
        }
        if (!vulkanRuntime.isCurrentNdrRenderProfilePacketReady()) {
            return SensorLinearNdrPreviewPreparation.DEFERRED_UNREADY
        }
        val prepared = vulkanRuntime.prepareSensorLinearNdrPreviewSurface(
            surface = surface,
            width = width,
            height = height,
            peakPlan = peakPlan
        )
        if (!prepared) {
            Log.e(TAG, "Strict sensor-linear NDR preview preparation failed")
            return SensorLinearNdrPreviewPreparation.FAILED
        }
        return SensorLinearNdrPreviewPreparation.PREPARED
    }

    fun present(
        presentationEpoch: Long,
        width: Int,
        height: Int,
        sensorTimestampNs: Long,
        sessionGeneration: Long,
        colorProof: ProjectOwnedNdrColorProof
    ): Boolean {
        if (!colorProof.isValid) {
            Log.e(TAG, "Rejected NDR preview frame with invalid colour proof")
            return false
        }
        if (!isSessionGenerationCurrent(sessionGeneration)) return false
        if (!compositor.registerProjectOwnedNdrSubmission(
                presentationEpoch,
                sensorTimestampNs,
                colorProof
            )
        ) {
            return false
        }
        if (!isSessionGenerationCurrent(sessionGeneration)) {
            compositor.cancelProjectOwnedNdrSubmission(presentationEpoch, sensorTimestampNs)
            return false
        }
        val submitted = vulkanRuntime.submitLatestSensorLinearNdrPreviewFrame(
            width = width,
            height = height,
            sensorTimestampNs = sensorTimestampNs,
            cameraSessionGeneration = sessionGeneration
        )
        if (!submitted) {
            compositor.cancelProjectOwnedNdrSubmission(presentationEpoch, sensorTimestampNs)
        }
        return submitted
    }

    /** Recording path: presents the worker-owned preview copy, never the full-resolution encoder slot. */
    fun present(submission: SensorLinearNdrRecordingPreviewSubmission): Boolean {
        val completed = submission.frame
        val colorProof = completed.projectOwnedNdrColorProof ?: return false
        if (!colorProof.isValid) {
            Log.e(TAG, "Rejected recorder NDR preview frame with invalid colour proof")
            return false
        }
        if (!isSessionGenerationCurrent(completed.cameraSessionGeneration)) return false
        if (!compositor.registerProjectOwnedNdrSubmission(
                completed.presentationEpoch,
                completed.sensorTimestampNs,
                colorProof
            )
        ) {
            return false
        }
        if (!isSessionGenerationCurrent(completed.cameraSessionGeneration)) {
            compositor.cancelProjectOwnedNdrSubmission(
                completed.presentationEpoch,
                completed.sensorTimestampNs
            )
            return false
        }
        val submitted = try {
            vulkanRuntime.submitSensorLinearNdrRecordingPreviewFrame(submission)
        } catch (failure: Throwable) {
            compositor.cancelProjectOwnedNdrSubmission(
                completed.presentationEpoch,
                completed.sensorTimestampNs
            )
            throw failure
        }
        if (!submitted) {
            compositor.cancelProjectOwnedNdrSubmission(
                completed.presentationEpoch,
                completed.sensorTimestampNs
            )
        }
        return submitted
    }

    fun close(): Boolean {
        compositor.clearProjectOwnedNdrPresent()
        return vulkanRuntime.releaseSensorLinearNdrPreviewSurface()
    }

    private companion object {
        const val TAG = "SensorLinearNdrPreview"
    }
}
