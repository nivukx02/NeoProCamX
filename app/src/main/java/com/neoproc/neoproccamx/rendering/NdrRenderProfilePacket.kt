package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.ai.SubjectMask
import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode

/**
 * Every native-visible display/render member that must cross a render generation together.
 * Keeping this payload inside the retained packet prevents cold-start replay from publishing an
 * NDR-ready generation after applying only its colour correction.
 */
internal data class VulkanNativeRenderProfileConfig(
    val hdrDynamicRangeMode: HdrDynamicRangeMode,
    val preferTenBitSdrDisplay: Boolean,
    val renderConfig: VulkanRenderConfig
)

/** All native members must accept one generation before its NDR packet and evidence can publish. */
internal data class VulkanNativeRenderCommitAdmission(
    val hdrDynamicRangeAccepted: Boolean,
    val tenBitSdrDisplayPreferenceAccepted: Boolean,
    val renderConfigAccepted: Boolean,
    val subjectDetailMaskAccepted: Boolean,
    val correctionAccepted: Boolean,
    val enhancementUniformsAccepted: Boolean
) {
    val accepted: Boolean
        get() = hdrDynamicRangeAccepted &&
            tenBitSdrDisplayPreferenceAccepted &&
            renderConfigAccepted &&
            subjectDetailMaskAccepted &&
            correctionAccepted &&
            enhancementUniformsAccepted

    fun rejectedStages(): String = buildList {
        if (!hdrDynamicRangeAccepted) add("hdr-dynamic-range")
        if (!tenBitSdrDisplayPreferenceAccepted) add("ten-bit-sdr-display")
        if (!renderConfigAccepted) add("render-config")
        if (!subjectDetailMaskAccepted) add("subject-detail-mask")
        if (!correctionAccepted) add("ndr-correction")
        if (!enhancementUniformsAccepted) add("enhancement-uniforms")
    }.joinToString(",")
}

/**
 * One mode-generation-owned NDR profile commit. Correction and enhancement identity must cross the
 * render boundary together; a frame is never allowed to combine NDR10+ coefficients with NIVUKX
 * colour science (or the inverse).
 */
internal data class NdrRenderProfilePacket(
    val profileGeneration: Long,
    /** Monotonic native-visible replacement within one mode/session generation. */
    val configRevision: Long,
    val correction: NdrNivukxMeasuredCorrection,
    val enhancementUniforms: GpuImageEnhancementUniforms,
    val nativeRenderConfig: VulkanNativeRenderProfileConfig,
    val subjectDetailMask: SubjectMask?
) {
    val identity: NdrRenderProfileIdentity
        get() = NdrRenderProfileIdentity(profileGeneration, configRevision)

    val coherence: NdrRenderProfileCoherencePolicy.Result
        get() = NdrRenderProfileCoherencePolicy.evaluate(
            correctionMode = correction.mode,
            colorScience = enhancementUniforms.colorScience
        )
}

private fun SubjectMask?.sameContentAs(other: SubjectMask?): Boolean = when {
    this === other -> true
    this == null || other == null -> false
    else -> maskWidth == other.maskWidth &&
        maskHeight == other.maskHeight &&
        trackingId == other.trackingId &&
        category == other.category &&
        intensity == other.intensity &&
        alphaMask.contentEquals(other.alphaMask)
}

internal fun NdrRenderProfilePacket.hasSamePayload(other: NdrRenderProfilePacket): Boolean =
    correction == other.correction &&
        enhancementUniforms == other.enhancementUniforms &&
        nativeRenderConfig == other.nativeRenderConfig &&
        subjectDetailMask.sameContentAs(other.subjectDetailMask)

/** Exact outcome of a generation-owned profile commit; incoherence is never mislabeled as stale. */
internal enum class NdrRenderProfileCommitResult {
    COMMITTED,
    /** Compatible latest intent retained until cold-start native consumers acknowledge readiness. */
    DEFERRED_UNREADY,
    /** Current async RAW output must retire before this native-visible replacement can be applied. */
    DEFERRED_IN_FLIGHT,
    STALE_GENERATION,
    STALE_REVISION,
    CONFLICTING_REVISION,
    INCOHERENT_PACKET,
    APPLY_FAILED,
    CANCELLED_LIFECYCLE
}

/** Two-dimensional identity: mode/session ownership first, packet replacement order second. */
internal data class NdrRenderProfileIdentity(
    val profileGeneration: Long,
    val configRevision: Long
) {
    val valid: Boolean
        get() = profileGeneration > 0L && configRevision > 0L
}

internal object NdrRenderProfileCoherencePolicy {
    internal data class Result(
        val coherent: Boolean,
        val correctionMode: NdrNivukxMeasuredMode,
        val expectedColorScience: GpuColorScience?,
        val actualColorScience: GpuColorScience?
    )

    fun evaluate(
        correctionMode: NdrNivukxMeasuredMode,
        colorScience: GpuColorScience?
    ): Result {
        val expected = when (correctionMode) {
            NdrNivukxMeasuredMode.NDR10_PLUS -> GpuColorScience.NDR10_PLUS
            NdrNivukxMeasuredMode.NIVUKX_VISION -> GpuColorScience.NIVUKX_VISION
            NdrNivukxMeasuredMode.OFF -> null
        }
        val actualIsNdr = colorScience == GpuColorScience.NDR10_PLUS ||
            colorScience == GpuColorScience.NIVUKX_VISION
        val coherent = if (expected == null) !actualIsNdr else colorScience == expected
        return Result(
            coherent = coherent,
            correctionMode = correctionMode,
            expectedColorScience = expected,
            actualColorScience = colorScience
        )
    }
}
