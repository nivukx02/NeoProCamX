package com.neoproc.neoproccamx.niv99

/**
 * Selects the Camera2 result-ingress scheduling tier without owning Android thread state.
 *
 * NPV10's NIV99 pixel path joins every copied P010 frame to its exact CaptureResult. The native
 * row workers and the NPV10 pack/writer lanes run at the recording-critical priority, so leaving
 * CameraResultIngress at the lower display priority can delay the producer side of that join. The
 * critical tier is deliberately limited to an active NPV10 recording; preview and every other
 * capture mode retain their existing scheduling tier.
 */
internal object Niv99MetadataIngressSchedulingPolicy {
    enum class Tier {
        BASELINE,
        RECORDING_CRITICAL
    }

    fun resolve(
        recordingActive: Boolean,
        npv10TenBitCaptureEnabled: Boolean
    ): Tier = if (recordingActive && npv10TenBitCaptureEnabled) {
        Tier.RECORDING_CRITICAL
    } else {
        Tier.BASELINE
    }

    /** Baseline must be restored exactly; an OEM may legitimately grant an even stronger boost. */
    fun isApplied(tier: Tier, requestedNice: Int, effectiveNice: Int): Boolean = when (tier) {
        Tier.BASELINE -> effectiveNice == requestedNice
        Tier.RECORDING_CRITICAL -> effectiveNice <= requestedNice
    }
}

/**
 * Scheduling contract for the ImageReader callback that owns P010 acquisition and transfers the
 * framework Image to DMC.  It is independent from metadata ingress: both lanes must be promoted for
 * an active 4K NPV10 session, and both must return to their exact baseline priorities afterward.
 */
internal object Niv99ImageIngressSchedulingPolicy {
    enum class Tier {
        BASELINE,
        RECORDING_CRITICAL
    }

    fun resolve(
        recordingActive: Boolean,
        npv10TenBitCaptureEnabled: Boolean
    ): Tier = if (recordingActive && npv10TenBitCaptureEnabled) {
        Tier.RECORDING_CRITICAL
    } else {
        Tier.BASELINE
    }

    fun isApplied(tier: Tier, requestedNice: Int, effectiveNice: Int): Boolean = when (tier) {
        Tier.BASELINE -> effectiveNice == requestedNice
        Tier.RECORDING_CRITICAL -> effectiveNice <= requestedNice
    }
}
