package com.neoproc.neoproccamx.ui

import android.os.Build
import com.neoproc.neoproccamx.recording.NeoNdrTruePqTransportPolicy
import com.neoproc.neoproccamx.rendering.OffscreenEglDeliveryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingHybridRoutePolicyTest {

    @Test
    fun `processed modes default to strict hybrid p010 without touching raw`() {
        val pro = resolve(deliveryKind = VideoDeliveryKind.SDR)
        val log = resolve(deliveryKind = VideoDeliveryKind.LOG)
        val ndr = resolve(deliveryKind = VideoDeliveryKind.HDR, effectiveHdrMode = "NDR10+")
        val raw = resolve(deliveryKind = VideoDeliveryKind.RAW)

        listOf(pro, log, ndr).forEach { plan ->
            assertTrue(plan.offscreenAhbEglEncoderSupported)
            assertTrue(plan.byteBufferP010EncoderSupported)
            assertFalse(plan.preferByteBufferP010Encoder)
            assertTrue(plan.strictHybridP010Default)
        }
        assertFalse(raw.offscreenAhbEglEncoderSupported)
        assertFalse(raw.byteBufferP010EncoderSupported)
        assertFalse(raw.preferByteBufferP010Encoder)
        assertFalse(raw.strictHybridP010Default)
    }

    @Test
    fun `offscreen delivery mode maps sdr and log to sdr and hdr follows ndr true-pq flag`() {
        assertEquals(
            OffscreenEglDeliveryMode.SDR,
            RecordingHybridRoutePolicy.resolveOffscreenDeliveryMode(VideoDeliveryKind.SDR, "OFF")
        )
        assertEquals(
            OffscreenEglDeliveryMode.SDR,
            RecordingHybridRoutePolicy.resolveOffscreenDeliveryMode(VideoDeliveryKind.LOG, "OFF")
        )
        val original = NeoNdrTruePqTransportPolicy.enabled
        val originalDevelop = com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag.enabled
        try {
            com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag.set(false)
            // Fork A (default): the NDR family delivers the BT.2020_PQ surface so the record shader
            // converts HLG->PQ and the pixels genuinely carry the ST.2084 curve the container tags.
            NeoNdrTruePqTransportPolicy.set(true)
            assertEquals(
                OffscreenEglDeliveryMode.PQ,
                RecordingHybridRoutePolicy.resolveOffscreenDeliveryMode(VideoDeliveryKind.HDR, "NDR10+")
            )
            // Fork B fallback: coherent HLG delivery (no shader conversion), matching the HLG tags.
            NeoNdrTruePqTransportPolicy.set(false)
            assertEquals(
                OffscreenEglDeliveryMode.HLG,
                RecordingHybridRoutePolicy.resolveOffscreenDeliveryMode(VideoDeliveryKind.HDR, "NDR10+")
            )
        } finally {
            NeoNdrTruePqTransportPolicy.set(original)
            com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag.set(originalDevelop)
        }
    }

    @Test
    fun `record-develop light lane preserves ndr pq delivery`() {
        val originalPq = NeoNdrTruePqTransportPolicy.enabled
        val originalDevelop = com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag.enabled
        try {
            // Enhancement timing must not silently replace the selected NDR transfer with HLG.
            NeoNdrTruePqTransportPolicy.set(true)
            com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag.set(true)
            assertEquals(
                OffscreenEglDeliveryMode.PQ,
                RecordingHybridRoutePolicy.resolveOffscreenDeliveryMode(VideoDeliveryKind.HDR, "NDR10+")
            )
            // SDR delivery is unaffected by the flag.
            assertEquals(
                OffscreenEglDeliveryMode.SDR,
                RecordingHybridRoutePolicy.resolveOffscreenDeliveryMode(VideoDeliveryKind.SDR, "OFF")
            )
            // Flag OFF keeps the same PQ surface (no transfer/profile change).
            com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag.set(false)
            assertEquals(
                OffscreenEglDeliveryMode.PQ,
                RecordingHybridRoutePolicy.resolveOffscreenDeliveryMode(VideoDeliveryKind.HDR, "NDR10+")
            )
        } finally {
            NeoNdrTruePqTransportPolicy.set(originalPq)
            com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag.set(originalDevelop)
        }
    }

    @Test
    fun `realtime bypass keeps offscreen and disables cpu p010 fallback`() {
        val plan = resolve(
            deliveryKind = VideoDeliveryKind.SDR,
            bypassByteBufferP010ForRealtime = true
        )

        assertTrue(plan.offscreenAhbEglEncoderSupported)
        assertFalse(plan.byteBufferP010EncoderSupported)
        assertFalse(plan.preferByteBufferP010Encoder)
        assertTrue(plan.strictHybridP010Default)
    }

    @Test
    fun `offscreen unavailable keeps strict p010 default`() {
        val plan = resolve(
            deliveryKind = VideoDeliveryKind.LOG,
            offscreenProvenUnavailable = true
        )

        assertFalse(plan.offscreenAhbEglEncoderSupported)
        assertTrue(plan.byteBufferP010EncoderSupported)
        assertTrue(plan.preferByteBufferP010Encoder)
        assertTrue(plan.strictHybridP010Default)
    }

    @Test
    fun `offscreen unavailable plus realtime bypass refuses cpu p010 fallback`() {
        val plan = resolve(
            deliveryKind = VideoDeliveryKind.LOG,
            offscreenProvenUnavailable = true,
            bypassByteBufferP010ForRealtime = true
        )

        assertFalse(plan.offscreenAhbEglEncoderSupported)
        assertFalse(plan.byteBufferP010EncoderSupported)
        assertFalse(plan.preferByteBufferP010Encoder)
        assertTrue(plan.strictHybridP010Default)
    }

    @Test
    fun `offscreen p010 is default even when regular vulkan surface is not blocked`() {
        val plan = resolve(
            deliveryKind = VideoDeliveryKind.SDR,
            vulkanEncoderSurfaceBlocked = false
        )

        assertTrue(plan.offscreenAhbEglEncoderSupported)
        assertTrue(plan.byteBufferP010EncoderSupported)
        assertTrue(plan.strictHybridP010Default)
    }

    private fun resolve(
        deliveryKind: VideoDeliveryKind,
        effectiveHdrMode: String = "OFF",
        bypassByteBufferP010ForRealtime: Boolean = false,
        offscreenProvenUnavailable: Boolean = false,
        vulkanEncoderSurfaceBlocked: Boolean = true
    ): RecordingHybridRoutePlan =
        RecordingHybridRoutePolicy.resolve(
            deliveryKind = deliveryKind,
            effectiveHdrMode = effectiveHdrMode,
            tenBitRequested = true,
            apiLevel = Build.VERSION_CODES.TIRAMISU,
            vulkanRuntimeSupported = true,
            vulkanPipelineHealthy = true,
            vulkanEncoderSurfaceBlocked = vulkanEncoderSurfaceBlocked,
            offscreenProvenUnavailable = offscreenProvenUnavailable,
            byteBufferP010CandidateAvailable = true,
            devicePrefersP010 = true,
            bypassByteBufferP010ForRealtime = bypassByteBufferP010ForRealtime,
            hdrZeroCopyEligible = false
        )
}
