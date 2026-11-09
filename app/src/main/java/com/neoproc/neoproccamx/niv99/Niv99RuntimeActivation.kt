package com.neoproc.neoproccamx.niv99

import java.util.concurrent.atomic.AtomicBoolean

/** A certificate is scoped to one graph class; approval for one route never unlocks another. */
enum class Niv99ActivationTarget {
    VIDEO_PREVIEW,
    VIDEO_RECORDING,
    PRO_PREVIEW,
    PRO_RECORDING,
    RAW_PREVIEW,
    PHOTO,
    CONVERSION
}

enum class Niv99RuntimeActivationStatus {
    BUILD_DISABLED,
    NOT_EXPLICITLY_REQUESTED,
    INVALID_POLICY,
    INVALID_RUNTIME_IDENTITY,
    INVALID_CERTIFICATE,
    CERTIFICATE_NOT_APPROVED,
    CERTIFICATE_EXPIRED,
    IDENTITY_MISMATCH,
    CAPABILITY_UNSUPPORTED,
    EVIDENCE_INCOMPLETE,
    METRICS_FAILED,
    APPROVED
}

/** Evidence is additive. Passing a host gate is intentionally distinct from passing it on Android. */
enum class Niv99ActivationEvidence {
    HOST_UNIT_TESTS,
    ANDROID_ADAPTER_COMPILE,
    NATIVE_WARNING_CLEAN,
    SHADER_REPRODUCIBLE,
    HOST_VULKAN_PARITY,
    ANDROID_VULKAN_PARITY,
    MANUAL_WB_AUTHORITY,
    AWB_LOCK_AUTHORITY,
    METADATA_ORDER_FUZZ,
    DEVICE_INTEGRATION,
    QUALITY_NON_DOWNGRADE,
    PERFORMANCE_CHARACTERIZED,
    REALTIME_DEADLINE,
    MEMORY_BOUNDED,
    THERMAL_ENDURANCE,
    LIFECYCLE_RESOURCE_SAFETY,
    PREVIEW_RECORDING_PARITY,
    RAW_BIT_EXACT,
    CONVERSION_REGRESSION,
    LONG_RECORDING,
    PHOTO_FUSION,
    NO_TILE_SEAMS,
    NO_HISTORY_LEAK;

    val bit: Long
        get() = 1L shl ordinal
}

enum class Niv99ActivationMetric {
    SHADER_PARITY,
    DEVICE_SAMPLE_SIZE,
    FRAME_TIMING,
    MEMORY_BOUND,
    THERMAL_DURATION,
    QUALITY_NON_DOWNGRADE,
    RAW_FIDELITY,
    TIMESTAMP_MONOTONICITY,
    TEMPORAL_HISTORY_ISOLATION,
    TILE_CONTINUITY,
    CONVERSION_INTEGRITY,
    RECORDING_INTEGRITY;

    val bit: Long
        get() = 1L shl ordinal
}

object Niv99ActivationEvidenceMask {
    fun of(vararg evidence: Niv99ActivationEvidence): Long {
        var mask = 0L
        for (item in evidence) mask = mask or item.bit
        return mask
    }

    fun contains(mask: Long, evidence: Niv99ActivationEvidence): Boolean =
        mask and evidence.bit != 0L

    fun missing(required: Long, passed: Long): Long = required and passed.inv()

    fun requiredFor(target: Niv99ActivationTarget): Long {
        var required = COMMON_HOST or COMMON_DEVICE
        when (target) {
            Niv99ActivationTarget.VIDEO_PREVIEW,
            Niv99ActivationTarget.PRO_PREVIEW -> required = required or REALTIME

            Niv99ActivationTarget.VIDEO_RECORDING,
            Niv99ActivationTarget.PRO_RECORDING -> {
                required = required or REALTIME or RECORDING
            }

            Niv99ActivationTarget.RAW_PREVIEW -> {
                required = required or REALTIME or Niv99ActivationEvidence.RAW_BIT_EXACT.bit
            }

            Niv99ActivationTarget.PHOTO -> {
                required = required or Niv99ActivationEvidence.PHOTO_FUSION.bit
            }

            Niv99ActivationTarget.CONVERSION -> {
                required = required or CONVERSION
            }
        }
        return required
    }

    private val COMMON_HOST = of(
        Niv99ActivationEvidence.HOST_UNIT_TESTS,
        Niv99ActivationEvidence.ANDROID_ADAPTER_COMPILE,
        Niv99ActivationEvidence.NATIVE_WARNING_CLEAN,
        Niv99ActivationEvidence.SHADER_REPRODUCIBLE,
        Niv99ActivationEvidence.HOST_VULKAN_PARITY,
        Niv99ActivationEvidence.MANUAL_WB_AUTHORITY,
        Niv99ActivationEvidence.AWB_LOCK_AUTHORITY,
        Niv99ActivationEvidence.METADATA_ORDER_FUZZ
    )

    private val COMMON_DEVICE = of(
        Niv99ActivationEvidence.ANDROID_VULKAN_PARITY,
        Niv99ActivationEvidence.DEVICE_INTEGRATION,
        Niv99ActivationEvidence.QUALITY_NON_DOWNGRADE,
        Niv99ActivationEvidence.PERFORMANCE_CHARACTERIZED,
        Niv99ActivationEvidence.MEMORY_BOUNDED,
        Niv99ActivationEvidence.LIFECYCLE_RESOURCE_SAFETY,
        Niv99ActivationEvidence.NO_TILE_SEAMS,
        Niv99ActivationEvidence.NO_HISTORY_LEAK
    )

    private val REALTIME = of(
        Niv99ActivationEvidence.REALTIME_DEADLINE,
        Niv99ActivationEvidence.THERMAL_ENDURANCE
    )

    private val RECORDING = of(
        Niv99ActivationEvidence.PREVIEW_RECORDING_PARITY,
        Niv99ActivationEvidence.LONG_RECORDING
    )

    private val CONVERSION = of(
        Niv99ActivationEvidence.RAW_BIT_EXACT,
        Niv99ActivationEvidence.CONVERSION_REGRESSION
    )
}

/** Immutable identity of the runtime graph requesting activation. */
data class Niv99RuntimeGraphIdentity(
    val appBuildFingerprint: String,
    val shaderGraphSha256: String,
    val nativeLibrarySha256: String,
    val deviceBuildFingerprint: String,
    val gpuDriverFingerprint: String,
    val cameraId: String,
    val sessionGeneration: Long,
    val surfaceGeneration: Long,
    val codecGeneration: Long,
    val width: Int,
    val height: Int,
    val targetFps: Int,
    val colorProfileId: Long,
    val operatingMode: Niv99OperatingMode,
    val signalDomain: Niv99SignalDomain
) {
    fun isValid(): Boolean =
        appBuildFingerprint.isNotBlank() &&
            isSha256(shaderGraphSha256) &&
            isSha256(nativeLibrarySha256) &&
            deviceBuildFingerprint.isNotBlank() &&
            gpuDriverFingerprint.isNotBlank() &&
            cameraId.isNotBlank() &&
            sessionGeneration >= 0L &&
            surfaceGeneration >= 0L &&
            codecGeneration >= 0L &&
            width > 0 && height > 0 && targetFps > 0 && colorProfileId >= 0L
}

/**
 * Controlled measurements bound to an immutable build, shader graph, native library, device, camera,
 * mode, format, and evidence manifest. Defaults are deliberately invalid rather than optimistic.
 */
data class Niv99RuntimeEvidenceCertificate(
    val schemaVersion: Int = 0,
    val certificateId: String = "",
    val evidenceManifestSha256: String = "",
    val appBuildFingerprint: String = "",
    val shaderGraphSha256: String = "",
    val nativeLibrarySha256: String = "",
    val nativeAbiVersion: Int = 0,
    val deviceBuildFingerprint: String = "",
    val gpuDriverFingerprint: String = "",
    val cameraId: String = "",
    val target: Niv99ActivationTarget = Niv99ActivationTarget.VIDEO_PREVIEW,
    val operatingMode: Niv99OperatingMode = Niv99OperatingMode.VIDEO,
    val signalDomain: Niv99SignalDomain = Niv99SignalDomain.SENSOR_LINEAR_RAW,
    val width: Int = 0,
    val height: Int = 0,
    val targetFps: Int = 0,
    val colorProfileId: Long = -1L,
    val issuedAtEpochMs: Long = 0L,
    val expiresAtEpochMs: Long = 0L,
    val passedEvidenceMask: Long = 0L,
    val shaderComparedValueCount: Long = 0L,
    val shaderMaximumAbsoluteError: Double = Double.NaN,
    val physicalDeviceFrameCount: Long = 0L,
    val controlledSceneCount: Int = 0,
    val medianFrameTimeNs: Long = 0L,
    val p95FrameTimeNs: Long = 0L,
    val p99FrameTimeNs: Long = 0L,
    val maximumFrameTimeNs: Long = 0L,
    val frameDeadlineNs: Long = 0L,
    val deadlineMissCount: Long = -1L,
    val maximumResidentBytes: Long = 0L,
    val certifiedMemoryBudgetBytes: Long = 0L,
    val thermalRunDurationNs: Long = 0L,
    val mtf50Retention: Double = Double.NaN,
    val clippingIncrease: Double = Double.NaN,
    val clippingTolerance: Double = Double.NaN,
    val awbStabilityImprovement: Double = Double.NaN,
    val denoiseQualityImprovement: Double = Double.NaN,
    val unexplainedRecordingFrameLoss: Long = -1L,
    val nonMonotonicTimestampCount: Long = -1L,
    val temporalHistoryLeakCount: Long = -1L,
    val visibleTileSeamCount: Long = -1L,
    val rawByteMismatchCount: Long = -1L,
    val conversionIntegrityFailureCount: Long = -1L,
    val longRecordingDurationNs: Long = 0L
)

/** Compile-time allow-list. Empty hashes plus disabled activation are the production-safe default. */
data class Niv99RuntimeActivationPolicy(
    val liveFrameActivationEnabled: Boolean = false,
    val approvedCertificateId: String = "",
    val approvedEvidenceManifestSha256: String = "",
    val approvedAppBuildFingerprint: String = "",
    val approvedShaderGraphSha256: String = "",
    val approvedNativeLibrarySha256: String = "",
    val expectedNativeAbiVersion: Int = NATIVE_ABI_VERSION,
    val minimumShaderComparedValues: Long = 1L,
    val maximumShaderAbsoluteError: Double = 0.0015,
    val minimumPhysicalDeviceFrames: Long = 1_000L,
    val minimumControlledScenes: Int = 8,
    val minimumThermalRunDurationNs: Long = 30L * 60L * 1_000_000_000L,
    val minimumLongRecordingDurationNs: Long = 30L * 60L * 1_000_000_000L,
    val minimumMtf50Retention: Double = 0.90
) {
    fun isValidForActivation(): Boolean =
        liveFrameActivationEnabled &&
            approvedCertificateId.isNotBlank() &&
            isSha256(approvedEvidenceManifestSha256) &&
            approvedAppBuildFingerprint.isNotBlank() &&
            isSha256(approvedShaderGraphSha256) &&
            isSha256(approvedNativeLibrarySha256) &&
            expectedNativeAbiVersion > 0 &&
            minimumShaderComparedValues > 0L &&
            maximumShaderAbsoluteError.isFinite() && maximumShaderAbsoluteError >= 0.0 &&
            minimumPhysicalDeviceFrames > 0L &&
            minimumControlledScenes > 0 &&
            minimumThermalRunDurationNs > 0L &&
            minimumLongRecordingDurationNs > 0L &&
            minimumMtf50Retention.isFinite() && minimumMtf50Retention in 0.0..1.0

    companion object {
        const val NATIVE_ABI_VERSION = 1
    }
}

object Niv99ProductionActivationPolicy {
    /** Must remain false until an approved physical-device certificate is checked into the build. */
    val DEFAULT = Niv99RuntimeActivationPolicy()
}

class Niv99RuntimeActivationDecision {
    var status: Niv99RuntimeActivationStatus = Niv99RuntimeActivationStatus.BUILD_DISABLED
        private set
    var target: Niv99ActivationTarget = Niv99ActivationTarget.VIDEO_PREVIEW
        private set
    var missingEvidenceMask: Long = 0L
        private set
    var failedMetricMask: Long = 0L
        private set
    var capabilityStatus: Niv99CapabilityStatus = Niv99CapabilityStatus.UNSUPPORTED
        private set
    var capabilityReason: Niv99UnsupportedReason = Niv99UnsupportedReason.NONE
        private set
    var certificateId: String = ""
        private set

    internal fun set(
        status: Niv99RuntimeActivationStatus,
        target: Niv99ActivationTarget,
        missingEvidenceMask: Long = 0L,
        failedMetricMask: Long = 0L,
        capability: Niv99CapabilityDecision? = null,
        certificateId: String = ""
    ) {
        this.status = status
        this.target = target
        this.missingEvidenceMask = missingEvidenceMask
        this.failedMetricMask = failedMetricMask
        capabilityStatus = capability?.status ?: Niv99CapabilityStatus.UNSUPPORTED
        capabilityReason = capability?.reason ?: Niv99UnsupportedReason.NONE
        this.certificateId = certificateId
    }

    fun isApproved(): Boolean = status == Niv99RuntimeActivationStatus.APPROVED
}

/** Opaque, session-bound permit. The GPU dispatcher validates it again for every submitted frame. */
class Niv99RuntimeActivationPermit internal constructor(
    internal val target: Niv99ActivationTarget,
    internal val identity: Niv99RuntimeGraphIdentity,
    internal val certificateId: String
) {
    private val revoked = AtomicBoolean(false)
    private val claimed = AtomicBoolean(false)

    internal fun claim(): Boolean = !revoked.get() && claimed.compareAndSet(false, true)

    internal fun revoke() {
        revoked.set(true)
    }

    internal fun matches(context: Niv99FrameContext): Boolean =
        !revoked.get() &&
            context.identity.cameraId == identity.cameraId &&
            context.identity.sessionGeneration == identity.sessionGeneration &&
            context.operatingMode == identity.operatingMode &&
            context.signalDomain == identity.signalDomain &&
            context.width == identity.width &&
            context.height == identity.height &&
            context.targetFps == identity.targetFps &&
            context.colorProfileId == identity.colorProfileId &&
            context.surfaceGeneration == identity.surfaceGeneration &&
            context.codecGeneration == identity.codecGeneration
}

/**
 * Fail-closed runtime gate. It does not downgrade a requested route and never treats host-only
 * evidence as permission to process Android camera frames.
 */
class Niv99RuntimeActivationGate(
    private val policy: Niv99RuntimeActivationPolicy = Niv99ProductionActivationPolicy.DEFAULT
) {
    private val capabilityDecision = Niv99CapabilityDecision()

    @Synchronized
    fun evaluate(
        explicitlyRequested: Boolean,
        target: Niv99ActivationTarget,
        identity: Niv99RuntimeGraphIdentity,
        certificate: Niv99RuntimeEvidenceCertificate,
        capabilities: Niv99HardwareCapabilities,
        nowEpochMs: Long,
        output: Niv99RuntimeActivationDecision
    ): Niv99RuntimeActivationPermit? {
        if (!policy.liveFrameActivationEnabled) {
            output.set(Niv99RuntimeActivationStatus.BUILD_DISABLED, target)
            return null
        }
        if (!explicitlyRequested) {
            output.set(Niv99RuntimeActivationStatus.NOT_EXPLICITLY_REQUESTED, target)
            return null
        }
        if (!policy.isValidForActivation()) {
            output.set(Niv99RuntimeActivationStatus.INVALID_POLICY, target)
            return null
        }
        if (!identity.isValid() || !targetMatchesMode(target, identity.operatingMode)) {
            output.set(Niv99RuntimeActivationStatus.INVALID_RUNTIME_IDENTITY, target)
            return null
        }
        if (!certificateIsStructurallyValid(certificate)) {
            output.set(Niv99RuntimeActivationStatus.INVALID_CERTIFICATE, target)
            return null
        }
        if (!certificateIsApproved(certificate)) {
            output.set(
                Niv99RuntimeActivationStatus.CERTIFICATE_NOT_APPROVED,
                target,
                certificateId = certificate.certificateId
            )
            return null
        }
        if (nowEpochMs < certificate.issuedAtEpochMs || nowEpochMs > certificate.expiresAtEpochMs) {
            output.set(
                Niv99RuntimeActivationStatus.CERTIFICATE_EXPIRED,
                target,
                certificateId = certificate.certificateId
            )
            return null
        }
        if (!certificateMatchesRuntime(certificate, target, identity)) {
            output.set(
                Niv99RuntimeActivationStatus.IDENTITY_MISMATCH,
                target,
                certificateId = certificate.certificateId
            )
            return null
        }
        Niv99CapabilityResolver.resolve(
            capabilities = capabilities,
            width = identity.width,
            height = identity.height,
            signalDomain = identity.signalDomain,
            output = capabilityDecision
        )
        if (capabilityDecision.status != Niv99CapabilityStatus.SUPPORTED_ZERO_COPY &&
            capabilityDecision.status != Niv99CapabilityStatus.SUPPORTED_BOUNDED_COPY
        ) {
            output.set(
                Niv99RuntimeActivationStatus.CAPABILITY_UNSUPPORTED,
                target,
                capability = capabilityDecision,
                certificateId = certificate.certificateId
            )
            return null
        }
        var requiredEvidence = Niv99ActivationEvidenceMask.requiredFor(target)
        if (identity.signalDomain == Niv99SignalDomain.SENSOR_LINEAR_RAW) {
            requiredEvidence = requiredEvidence or Niv99ActivationEvidence.RAW_BIT_EXACT.bit
        }
        val missingEvidence = Niv99ActivationEvidenceMask.missing(
            requiredEvidence,
            certificate.passedEvidenceMask
        )
        if (missingEvidence != 0L) {
            output.set(
                Niv99RuntimeActivationStatus.EVIDENCE_INCOMPLETE,
                target,
                missingEvidenceMask = missingEvidence,
                capability = capabilityDecision,
                certificateId = certificate.certificateId
            )
            return null
        }
        val failedMetrics = failedMetricMask(target, certificate)
        if (failedMetrics != 0L) {
            output.set(
                Niv99RuntimeActivationStatus.METRICS_FAILED,
                target,
                failedMetricMask = failedMetrics,
                capability = capabilityDecision,
                certificateId = certificate.certificateId
            )
            return null
        }
        output.set(
            Niv99RuntimeActivationStatus.APPROVED,
            target,
            capability = capabilityDecision,
            certificateId = certificate.certificateId
        )
        return Niv99RuntimeActivationPermit(target, identity, certificate.certificateId)
    }

    private fun certificateIsStructurallyValid(certificate: Niv99RuntimeEvidenceCertificate): Boolean =
        certificate.schemaVersion == CERTIFICATE_SCHEMA_VERSION &&
            certificate.certificateId.isNotBlank() &&
            isSha256(certificate.evidenceManifestSha256) &&
            certificate.appBuildFingerprint.isNotBlank() &&
            isSha256(certificate.shaderGraphSha256) &&
            isSha256(certificate.nativeLibrarySha256) &&
            certificate.nativeAbiVersion > 0 &&
            certificate.deviceBuildFingerprint.isNotBlank() &&
            certificate.gpuDriverFingerprint.isNotBlank() &&
            certificate.cameraId.isNotBlank() &&
            certificate.width > 0 && certificate.height > 0 && certificate.targetFps > 0 &&
            certificate.colorProfileId >= 0L &&
            certificate.issuedAtEpochMs > 0L &&
            certificate.expiresAtEpochMs >= certificate.issuedAtEpochMs

    private fun certificateIsApproved(certificate: Niv99RuntimeEvidenceCertificate): Boolean =
        certificate.certificateId == policy.approvedCertificateId &&
            certificate.evidenceManifestSha256.equals(
                policy.approvedEvidenceManifestSha256,
                ignoreCase = true
            ) &&
            certificate.appBuildFingerprint == policy.approvedAppBuildFingerprint &&
            certificate.shaderGraphSha256.equals(policy.approvedShaderGraphSha256, ignoreCase = true) &&
            certificate.nativeLibrarySha256.equals(
                policy.approvedNativeLibrarySha256,
                ignoreCase = true
            ) &&
            certificate.nativeAbiVersion == policy.expectedNativeAbiVersion

    private fun certificateMatchesRuntime(
        certificate: Niv99RuntimeEvidenceCertificate,
        target: Niv99ActivationTarget,
        identity: Niv99RuntimeGraphIdentity
    ): Boolean =
        certificate.target == target &&
            certificate.appBuildFingerprint == identity.appBuildFingerprint &&
            certificate.shaderGraphSha256.equals(identity.shaderGraphSha256, ignoreCase = true) &&
            certificate.nativeLibrarySha256.equals(identity.nativeLibrarySha256, ignoreCase = true) &&
            certificate.deviceBuildFingerprint == identity.deviceBuildFingerprint &&
            certificate.gpuDriverFingerprint == identity.gpuDriverFingerprint &&
            certificate.cameraId == identity.cameraId &&
            certificate.operatingMode == identity.operatingMode &&
            certificate.signalDomain == identity.signalDomain &&
            certificate.width == identity.width &&
            certificate.height == identity.height &&
            certificate.targetFps == identity.targetFps &&
            certificate.colorProfileId == identity.colorProfileId

    private fun failedMetricMask(
        target: Niv99ActivationTarget,
        certificate: Niv99RuntimeEvidenceCertificate
    ): Long {
        var failed = 0L
        if (certificate.shaderComparedValueCount < policy.minimumShaderComparedValues ||
            !certificate.shaderMaximumAbsoluteError.isFinite() ||
            certificate.shaderMaximumAbsoluteError < 0.0 ||
            certificate.shaderMaximumAbsoluteError > policy.maximumShaderAbsoluteError
        ) {
            failed = failed or Niv99ActivationMetric.SHADER_PARITY.bit
        }
        if (certificate.physicalDeviceFrameCount < policy.minimumPhysicalDeviceFrames ||
            certificate.controlledSceneCount < policy.minimumControlledScenes
        ) {
            failed = failed or Niv99ActivationMetric.DEVICE_SAMPLE_SIZE.bit
        }
        if (certificate.maximumResidentBytes <= 0L ||
            certificate.certifiedMemoryBudgetBytes <= 0L ||
            certificate.maximumResidentBytes > certificate.certifiedMemoryBudgetBytes
        ) {
            failed = failed or Niv99ActivationMetric.MEMORY_BOUND.bit
        }
        if (!certificate.mtf50Retention.isFinite() ||
            certificate.mtf50Retention < policy.minimumMtf50Retention ||
            !certificate.clippingIncrease.isFinite() || certificate.clippingIncrease < 0.0 ||
            !certificate.clippingTolerance.isFinite() || certificate.clippingTolerance < 0.0 ||
            certificate.clippingIncrease > certificate.clippingTolerance ||
            !certificate.awbStabilityImprovement.isFinite() ||
            certificate.awbStabilityImprovement <= 0.0 ||
            !certificate.denoiseQualityImprovement.isFinite() ||
            certificate.denoiseQualityImprovement <= 0.0
        ) {
            failed = failed or Niv99ActivationMetric.QUALITY_NON_DOWNGRADE.bit
        }
        if (certificate.nonMonotonicTimestampCount != 0L) {
            failed = failed or Niv99ActivationMetric.TIMESTAMP_MONOTONICITY.bit
        }
        if (certificate.temporalHistoryLeakCount != 0L) {
            failed = failed or Niv99ActivationMetric.TEMPORAL_HISTORY_ISOLATION.bit
        }
        if (certificate.visibleTileSeamCount != 0L) {
            failed = failed or Niv99ActivationMetric.TILE_CONTINUITY.bit
        }
        if (requiresRealtime(target)) {
            if (!validFrameTiming(certificate)) {
                failed = failed or Niv99ActivationMetric.FRAME_TIMING.bit
            }
            if (certificate.thermalRunDurationNs < policy.minimumThermalRunDurationNs) {
                failed = failed or Niv99ActivationMetric.THERMAL_DURATION.bit
            }
        }
        if ((requiresRawFidelity(target) ||
                certificate.signalDomain == Niv99SignalDomain.SENSOR_LINEAR_RAW) &&
            certificate.rawByteMismatchCount != 0L
        ) {
            failed = failed or Niv99ActivationMetric.RAW_FIDELITY.bit
        }
        if (target == Niv99ActivationTarget.CONVERSION &&
            certificate.conversionIntegrityFailureCount != 0L
        ) {
            failed = failed or Niv99ActivationMetric.CONVERSION_INTEGRITY.bit
        }
        if (requiresLongRecording(target) &&
            (certificate.unexplainedRecordingFrameLoss != 0L ||
                certificate.longRecordingDurationNs < policy.minimumLongRecordingDurationNs)
        ) {
            failed = failed or Niv99ActivationMetric.RECORDING_INTEGRITY.bit
        }
        return failed
    }

    private fun validFrameTiming(certificate: Niv99RuntimeEvidenceCertificate): Boolean =
        certificate.frameDeadlineNs > 0L &&
            certificate.medianFrameTimeNs > 0L &&
            certificate.medianFrameTimeNs <= certificate.p95FrameTimeNs &&
            certificate.p95FrameTimeNs <= certificate.p99FrameTimeNs &&
            certificate.p99FrameTimeNs <= certificate.maximumFrameTimeNs &&
            certificate.maximumFrameTimeNs <= certificate.frameDeadlineNs &&
            certificate.deadlineMissCount == 0L

    companion object {
        const val CERTIFICATE_SCHEMA_VERSION = 1

        private fun requiresRealtime(target: Niv99ActivationTarget): Boolean = when (target) {
            Niv99ActivationTarget.VIDEO_PREVIEW,
            Niv99ActivationTarget.VIDEO_RECORDING,
            Niv99ActivationTarget.PRO_PREVIEW,
            Niv99ActivationTarget.PRO_RECORDING,
            Niv99ActivationTarget.RAW_PREVIEW -> true

            Niv99ActivationTarget.PHOTO,
            Niv99ActivationTarget.CONVERSION -> false
        }

        private fun requiresRawFidelity(target: Niv99ActivationTarget): Boolean =
            target == Niv99ActivationTarget.RAW_PREVIEW ||
                target == Niv99ActivationTarget.CONVERSION

        private fun requiresLongRecording(target: Niv99ActivationTarget): Boolean =
            target == Niv99ActivationTarget.VIDEO_RECORDING ||
                target == Niv99ActivationTarget.PRO_RECORDING

        private fun targetMatchesMode(
            target: Niv99ActivationTarget,
            mode: Niv99OperatingMode
        ): Boolean = when (target) {
            Niv99ActivationTarget.VIDEO_PREVIEW,
            Niv99ActivationTarget.VIDEO_RECORDING -> mode == Niv99OperatingMode.VIDEO

            Niv99ActivationTarget.PRO_PREVIEW,
            Niv99ActivationTarget.PRO_RECORDING -> mode == Niv99OperatingMode.PRO

            Niv99ActivationTarget.RAW_PREVIEW -> mode == Niv99OperatingMode.RAW_PREVIEW
            Niv99ActivationTarget.PHOTO -> mode == Niv99OperatingMode.PHOTO
            Niv99ActivationTarget.CONVERSION -> mode == Niv99OperatingMode.CONVERSION_RAW_TO_LOG ||
                mode == Niv99OperatingMode.CONVERSION_RAW_TO_RAW_DEVELOPED ||
                mode == Niv99OperatingMode.CONVERSION_RAW_TO_XDR10 ||
                mode == Niv99OperatingMode.CONVERSION_RAW_TO_NIVUKX
        }
    }
}

private fun isSha256(value: String): Boolean {
    if (value.length != 64) return false
    for (character in value) {
        if (character !in '0'..'9' && character !in 'a'..'f' && character !in 'A'..'F') return false
    }
    return true
}
