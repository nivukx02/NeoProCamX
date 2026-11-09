package com.neoproc.neoproccamx.ui

import android.os.Build
import com.neoproc.neoproccamx.recording.NeoNdrTruePqTransportPolicy
import com.neoproc.neoproccamx.rendering.OffscreenEglDeliveryMode

internal data class RecordingHybridRoutePlan(
    val offscreenAhbEglEncoderSupported: Boolean,
    val byteBufferP010EncoderSupported: Boolean,
    val preferByteBufferP010Encoder: Boolean,
    val strictHybridP010Default: Boolean,
    val offscreenDeliveryMode: OffscreenEglDeliveryMode,
    val reason: String
)

internal object RecordingHybridRoutePolicy {
    fun resolve(
        deliveryKind: VideoDeliveryKind,
        effectiveHdrMode: String,
        tenBitRequested: Boolean,
        apiLevel: Int,
        vulkanRuntimeSupported: Boolean,
        vulkanPipelineHealthy: Boolean,
        vulkanEncoderSurfaceBlocked: Boolean,
        offscreenProvenUnavailable: Boolean,
        byteBufferP010CandidateAvailable: Boolean,
        devicePrefersP010: Boolean,
        bypassByteBufferP010ForRealtime: Boolean,
        hdrZeroCopyEligible: Boolean
    ): RecordingHybridRoutePlan {
        val processed = deliveryKind != VideoDeliveryKind.RAW
        val byteBufferP010RealtimeSafe =
            processed &&
                tenBitRequested &&
                byteBufferP010CandidateAvailable &&
                !bypassByteBufferP010ForRealtime
        val strictHybridP010Default = processed && byteBufferP010CandidateAvailable
        val canAttemptOffscreen =
            processed &&
                tenBitRequested &&
                apiLevel >= Build.VERSION_CODES.Q &&
                vulkanRuntimeSupported &&
                vulkanPipelineHealthy &&
                !offscreenProvenUnavailable &&
                byteBufferP010CandidateAvailable
        val preferP010 =
            byteBufferP010RealtimeSafe && !canAttemptOffscreen

        return RecordingHybridRoutePlan(
            offscreenAhbEglEncoderSupported = canAttemptOffscreen,
            byteBufferP010EncoderSupported = byteBufferP010RealtimeSafe,
            preferByteBufferP010Encoder = preferP010,
            strictHybridP010Default = strictHybridP010Default,
            offscreenDeliveryMode = resolveOffscreenDeliveryMode(deliveryKind, effectiveHdrMode),
            reason = buildReason(
                deliveryKind = deliveryKind,
                offscreen = canAttemptOffscreen,
                p010 = preferP010,
                blocked = vulkanEncoderSurfaceBlocked,
                unavailable = offscreenProvenUnavailable,
                realtimeBypass = bypassByteBufferP010ForRealtime,
                strictHybridP010Default = strictHybridP010Default,
                devicePrefersP010 = devicePrefersP010,
                hdrZeroCopyEligible = hdrZeroCopyEligible
            )
        )
    }

    fun resolveOffscreenDeliveryMode(
        deliveryKind: VideoDeliveryKind,
        @Suppress("UNUSED_PARAMETER") effectiveHdrMode: String
    ): OffscreenEglDeliveryMode =
        if (deliveryKind == VideoDeliveryKind.HDR) {
            // NDR10+/NIVUKX/APEX transport is decided by NeoNdrTruePqTransportPolicy so pixels and
            // tags stay coherent (see that policy). Fork A (default): bind the EGL surface BT.2020_PQ
            // and let the record shader convert HLG->PQ, so the delivered pixels genuinely carry the
            // ST.2084 curve the container is tagged with. Fork B fallback: bind BT.2020_HLG (no shader
            // conversion) to match the HLG-coherent tags. Never HLG pixels on a PQ-tagged surface.
            // Record-develop changes enhancement timing only; it must never change the selected NDR
            // transfer. The encoder, container, sidecar, preview, and converter therefore remain on the
            // same project-owned PQ NDR transport even while the live master is otherwise kept flat.
            if (NeoNdrTruePqTransportPolicy.enabled) {
                OffscreenEglDeliveryMode.PQ
            } else {
                OffscreenEglDeliveryMode.HLG
            }
        } else {
            OffscreenEglDeliveryMode.SDR
        }

    private fun buildReason(
        deliveryKind: VideoDeliveryKind,
        offscreen: Boolean,
        p010: Boolean,
        blocked: Boolean,
        unavailable: Boolean,
        realtimeBypass: Boolean,
        strictHybridP010Default: Boolean,
        devicePrefersP010: Boolean,
        hdrZeroCopyEligible: Boolean
    ): String = listOf(
        "delivery=${deliveryKind.name}",
        "offscreen=$offscreen",
        "p010=$p010",
        "strictHybridP010Default=$strictHybridP010Default",
        "devicePrefersP010=$devicePrefersP010",
        "vulkanSurfaceBlocked=$blocked",
        "offscreenUnavailable=$unavailable",
        "realtimeP010Bypass=$realtimeBypass",
        "hdrZeroCopyEligible=$hdrZeroCopyEligible"
    ).joinToString(separator = "|")
}
