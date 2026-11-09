package com.neoproc.neoproccamx.rendering

import java.util.concurrent.atomic.AtomicLong

/** Original idle-preview completion route retained across a deferred native fence. */
internal class RawDemosaicCompletionRoute(
    private val callback: (RawDemosaicFrameIdentity) -> Unit
) {
    fun publish(frame: RawDemosaicFrameIdentity) = callback(frame)
}

/** Exact identity of the shared RAW demosaic output while it is owned by an asynchronous dispatch. */
internal data class RawDemosaicFrameIdentity(
    val presentationEpoch: Long,
    val sensorTimestampNs: Long,
    val width: Int,
    val height: Int,
    val recordingSequence: Long = -1L,
    val ingressGeneration: Long = -1L,
    val cameraSessionGeneration: Long = -1L,
    val cameraFrameNumber: Long = -1L,
    val exposureTimeNs: Long = 0L,
    val analogGain: Float = 1.0f,
    val digitalGain: Float = 1.0f,
    val whiteBalanceR: Float = 1.0f,
    val whiteBalanceGr: Float = 1.0f,
    val whiteBalanceGb: Float = 1.0f,
    val whiteBalanceB: Float = 1.0f,
    /** Exact render payload owning the native dispatch; never sampled later at completion time. */
    val renderProfileIdentity: NdrRenderProfileIdentity? = null,
    val renderEnhancementUniforms: GpuImageEnhancementUniforms? = null,
    /** Original idle join/callback closure; a later submission must never replace this route. */
    val idleCompletionRoute: RawDemosaicCompletionRoute? = null,
    /** Immutable stabilized-colour lineage carried across asynchronous native completion. */
    val projectOwnedNdrColorProof: ProjectOwnedNdrColorProof? = null,
    /** Monotonic start used only for completion-time diagnostics; never for frame identity. */
    val demosaicDispatchStartedElapsedRealtimeNs: Long = 0L,
    /** True when VulkanRuntime, rather than submitRawBuffer(), owns exactly-once stats publication. */
    val demosaicCompletionStatsExternallyOwned: Boolean = false
) {
    private val completionObservedElapsedRealtimeNs = AtomicLong(0L)

    /**
     * Monotonic time at which Java first observed native COMPLETE for this dispatch.
     *
     * Kept outside the data-class identity so an encoder retry cannot change frame equality or the
     * exact-completion acknowledgement key. Zero means the owner has not supplied an observation yet.
     */
    val demosaicCompletionObservedElapsedRealtimeNs: Long
        get() = completionObservedElapsedRealtimeNs.get()

    internal fun observeDemosaicCompletion(elapsedRealtimeNs: Long): Boolean {
        if (elapsedRealtimeNs <= 0L) return false
        return completionObservedElapsedRealtimeNs.compareAndSet(0L, elapsedRealtimeNs)
    }
}

internal data class RawDemosaicHandoffDecision(
    val shouldDispatchCurrent: Boolean,
    val completedFrame: RawDemosaicFrameIdentity? = null,
    /** True only when a second native export lane is free before [completedFrame] is acknowledged. */
    val canOverlapDispatchBeforeAcknowledgement: Boolean = false
)

/** Fail-closed reason for retaining native/JVM RAW ownership until an explicit retirement proof. */
internal enum class RawDemosaicOwnershipQuarantineReason {
    NATIVE_COMPLETION_FAILED,
    NATIVE_COMPLETION_LOST_IDENTITY,
    NATIVE_BECAME_IDLE_WITH_PENDING_IDENTITY,
    NATIVE_RETIREMENT_COUNT_MISMATCH
}

/** Immutable lineage snapshot captured when RAW ownership becomes unsafe to publish or reuse. */
internal data class RawDemosaicOwnershipQuarantine(
    val reason: RawDemosaicOwnershipQuarantineReason,
    val pendingIdentity: RawDemosaicFrameIdentity?,
    val completedIdentities: List<RawDemosaicFrameIdentity>
)

/**
 * Couples native fence completion to the frame that originally submitted the work.
 *
 * Native preview owns one output lane; full-sensor recording prewarms two immutable export lanes. When
 * a dispatch misses its short deadline, this tracker binds completion to the original frame identity.
 * Recording may start the next Vulkan dispatch in the free lane before synchronously publishing the
 * older lane, but no lane becomes reusable until the exact completed identity is acknowledged.
 */
internal class RawDemosaicCompletionHandoff {
    private var pendingFrame: RawDemosaicFrameIdentity? = null
    private var completedFrame: RawDemosaicFrameIdentity? = null
    /** Second ring lane that completed while the older lane was still awaiting publication. */
    private var completedFollower: RawDemosaicFrameIdentity? = null
    private var ownershipQuarantine: RawDemosaicOwnershipQuarantine? = null

    fun beforeDispatch(
        poll: RawDemosaicCompletionPoll,
        completionObservedElapsedRealtimeNs: Long = 0L
    ): RawDemosaicHandoffDecision {
        if (ownershipQuarantine != null) {
            return RawDemosaicHandoffDecision(shouldDispatchCurrent = false)
        }
        return when (poll) {
        RawDemosaicCompletionPoll.COMPLETE -> {
            pendingFrame?.let { finished ->
                finished.observeDemosaicCompletion(completionObservedElapsedRealtimeNs)
                if (completedFrame == null) {
                    completedFrame = finished
                } else if (completedFollower == null) {
                    completedFollower = finished
                }
                pendingFrame = null
            }
            RawDemosaicHandoffDecision(
                // With two completed lanes the owner must publish the oldest first; with one completed
                // lane, the ring permits the next dispatch to start before synchronous EGL publication.
                shouldDispatchCurrent = completedFrame != null,
                completedFrame = completedFrame,
                canOverlapDispatchBeforeAcknowledgement =
                    completedFrame != null && completedFollower == null
            )
        }
        RawDemosaicCompletionPoll.PENDING -> RawDemosaicHandoffDecision(
            shouldDispatchCurrent = false,
            completedFrame = completedFrame
        )
        RawDemosaicCompletionPoll.IDLE -> {
            when {
                completedFrame != null -> RawDemosaicHandoffDecision(
                    shouldDispatchCurrent = true,
                    completedFrame = completedFrame,
                    canOverlapDispatchBeforeAcknowledgement = completedFollower == null
                )
                pendingFrame != null -> {
                    // A native job cannot legally become IDLE without first reporting COMPLETE or
                    // FAILED. Preserve its identity and fail closed rather than relabeling pixels.
                    RawDemosaicHandoffDecision(shouldDispatchCurrent = false)
                }
                else -> RawDemosaicHandoffDecision(shouldDispatchCurrent = true)
            }
        }
        RawDemosaicCompletionPoll.FAILED -> {
            quarantine(RawDemosaicOwnershipQuarantineReason.NATIVE_COMPLETION_FAILED)
            RawDemosaicHandoffDecision(shouldDispatchCurrent = false)
        }
    }
    }

    fun afterDispatch(
        frame: RawDemosaicFrameIdentity,
        completedSynchronously: Boolean,
        pollAfterDeadline: RawDemosaicCompletionPoll,
        completionObservedElapsedRealtimeNs: Long = 0L
    ): RawDemosaicFrameIdentity? {
        if (ownershipQuarantine != null) return null
        if (pendingFrame != null || completedFollower != null) return null
        if (completedSynchronously) {
            frame.observeDemosaicCompletion(completionObservedElapsedRealtimeNs)
            return if (completedFrame == null) {
                completedFrame = frame
                frame
            } else {
                completedFollower = frame
                null
            }
        }
        return when (pollAfterDeadline) {
            RawDemosaicCompletionPoll.COMPLETE -> {
                frame.observeDemosaicCompletion(completionObservedElapsedRealtimeNs)
                if (completedFrame == null) {
                    completedFrame = frame
                    frame
                } else {
                    completedFollower = frame
                    null
                }
            }
            RawDemosaicCompletionPoll.PENDING -> {
                pendingFrame = frame
                null
            }
            RawDemosaicCompletionPoll.IDLE -> {
                pendingFrame = null
                null
            }
            RawDemosaicCompletionPoll.FAILED -> {
                // A failed poll after JNI dispatch cannot prove that the native writer never acquired
                // this frame. Retain its exact lineage and prohibit publication/reuse until teardown.
                pendingFrame = frame
                quarantine(RawDemosaicOwnershipQuarantineReason.NATIVE_COMPLETION_FAILED)
                null
            }
        }
    }

    /** Non-destructive inspection used by stop/transition code before releasing shared output ownership. */
    fun pendingIdentity(): RawDemosaicFrameIdentity? = pendingFrame

    /** A completed output remains owned until the exact publisher acknowledges it. */
    fun completedIdentity(): RawDemosaicFrameIdentity? =
        if (ownershipQuarantine == null) completedFrame else null

    fun hasOutstandingIdentity(): Boolean =
        ownershipQuarantine != null ||
            pendingFrame != null || completedFrame != null || completedFollower != null

    fun completedIdentityCount(): Int =
        (if (completedFrame != null) 1 else 0) + (if (completedFollower != null) 1 else 0)

    fun ownsIdentity(frame: RawDemosaicFrameIdentity): Boolean =
        pendingFrame == frame || completedFrame == frame || completedFollower == frame

    fun acknowledgeCompleted(frame: RawDemosaicFrameIdentity): Boolean {
        if (ownershipQuarantine != null) return false
        if (completedFrame != frame) return false
        completedFrame = completedFollower
        completedFollower = null
        return true
    }

    fun quarantine(
        reason: RawDemosaicOwnershipQuarantineReason
    ): RawDemosaicOwnershipQuarantine {
        ownershipQuarantine?.let { return it }
        return RawDemosaicOwnershipQuarantine(
            reason = reason,
            pendingIdentity = pendingFrame,
            completedIdentities = listOfNotNull(completedFrame, completedFollower)
        ).also { ownershipQuarantine = it }
    }

    fun quarantinedOwnership(): RawDemosaicOwnershipQuarantine? = ownershipQuarantine

    /** Clears an already-empty tracker only; it can never erase retained or quarantined lineage. */
    fun resetIfUnowned(): Boolean {
        if (ownershipQuarantine != null || pendingFrame != null ||
            completedFrame != null || completedFollower != null
        ) return false
        return true
    }

    /** Called only after native discard count parity or synchronous native-runtime destruction. */
    fun retireAfterNativeRetirementProof() {
        pendingFrame = null
        completedFrame = null
        completedFollower = null
        ownershipQuarantine = null
    }
}
