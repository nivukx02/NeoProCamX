package com.neoproc.neoproccamx.camera

/**
 * Identity token for a live Camera2 output. Surface equality is deliberately reference based:
 * two wrappers may describe the same native dimensions while still owning different buffer queues.
 */
internal class CameraSessionSurfaceIdentity(private val reference: Any) {
    override fun equals(other: Any?): Boolean =
        other is CameraSessionSurfaceIdentity && reference === other.reference

    override fun hashCode(): Int = System.identityHashCode(reference)

    override fun toString(): String = "surface@${hashCode().toString(16)}"
}

internal data class CameraSessionOutputFingerprint(
    val identity: CameraSessionSurfaceIdentity,
    val generationId: Int,
    val kind: CameraSessionSurfaceKind,
    val dynamicRangeProfile: Long?,
    val streamUseCaseRole: StreamUseCasePolicy.StreamRole?,
    val streamUseCase: Long?
)

internal data class CameraSessionReaderFingerprint(
    val kind: CameraSessionSurfaceKind,
    val width: Int,
    val height: Int,
    val imageFormat: Int,
    val maxImages: Int,
    val usage: Long
)

/**
 * Complete identity of the Camera2 session configuration. Capture-request-only controls are not
 * included because they can be applied atomically to the existing repeating request without
 * tearing down a healthy session.
 */
internal class CameraSessionConfigurationFingerprint(
    val cameraId: String,
    val outputs: List<CameraSessionOutputFingerprint>,
    val readers: List<CameraSessionReaderFingerprint>,
    val streamGraph: CinemaStreamGraphPlan,
    val streamBasedFpsPlan: CameraStreamBasedFpsOverridePlan,
    val maximumResolutionPixelMode: Boolean,
    val maximumResolutionSessionParameterApplied: Boolean,
    val regularSession: Boolean = true
) {
    /**
     * [streamGraph] is diagnostic intent, not Camera2 configuration. Two logical modes can resolve
     * to the exact same output/readers/session parameters and must reuse the live session.
     * Output order is likewise non-semantic once the configured Surface set is unchanged.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CameraSessionConfigurationFingerprint) return false
        return cameraId == other.cameraId &&
            outputs.toSet() == other.outputs.toSet() &&
            readers.toSet() == other.readers.toSet() &&
            streamBasedFpsPlan == other.streamBasedFpsPlan &&
            maximumResolutionPixelMode == other.maximumResolutionPixelMode &&
            maximumResolutionSessionParameterApplied == other.maximumResolutionSessionParameterApplied &&
            regularSession == other.regularSession
    }

    override fun hashCode(): Int {
        var result = cameraId.hashCode()
        result = 31 * result + outputs.toSet().hashCode()
        result = 31 * result + readers.toSet().hashCode()
        result = 31 * result + streamBasedFpsPlan.hashCode()
        result = 31 * result + maximumResolutionPixelMode.hashCode()
        result = 31 * result + maximumResolutionSessionParameterApplied.hashCode()
        result = 31 * result + regularSession.hashCode()
        return result
    }

    fun compactDescription(): String =
        "camera=$cameraId mode=${streamGraph.mode} outputs=${outputs.size} " +
            "readers=${readers.size} fps=${streamBasedFpsPlan.appliedFps} " +
            "maxRes=$maximumResolutionPixelMode"

    fun diagnosticDescription(): String =
        "camera=$cameraId outputs=${outputs.joinToString(",") { output ->
            "${output.kind}:${output.identity}:${output.generationId}:" +
                "dr=${output.dynamicRangeProfile}:role=${output.streamUseCaseRole}:use=${output.streamUseCase}"
        }} readers=${readers.joinToString(",")} fps=$streamBasedFpsPlan " +
            "maxRes=$maximumResolutionPixelMode maxResParam=$maximumResolutionSessionParameterApplied " +
            "regular=$regularSession"
}

internal object CameraSessionConfigurationDeduplicationPolicy {
    fun shouldReuseConfiguredSession(
        hasActiveSession: Boolean,
        configured: CameraSessionConfigurationFingerprint?,
        desired: CameraSessionConfigurationFingerprint,
        allowIdenticalRecoveryRebuild: Boolean
    ): Boolean =
        hasActiveSession &&
            !allowIdenticalRecoveryRebuild &&
            configured != null &&
            configured == desired
}
