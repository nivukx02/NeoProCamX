package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRealtimeRoutePolicyTest {
    @Test
    fun `vivo i2405 high resolution sdr bypasses bytebuffer p010 for realtime recording`() {
        assertTrue(
            RecordingRealtimeRoutePolicy.shouldBypassByteBufferP010ForRealtime(
                manufacturer = "vivo",
                model = "I2405",
                device = "I2405",
                product = "I2405i",
                width = 3264,
                height = 2448,
                requestedFps = 24,
                deliveryKind = VideoDeliveryKind.SDR,
                effectiveHdrMode = "OFF"
            )
        )
    }

    @Test
    fun `vivo i2405 realtime route caps recording analysis sidecar without raising lower caps`() {
        assertEquals(
            800,
            RecordingRealtimeRoutePolicy.resolveRecordingAnalysisMaxLongEdgeForRealtime(
                manufacturer = "vivo",
                model = "I2405",
                device = "I2405",
                product = "I2405i",
                width = 3264,
                height = 2448,
                requestedFps = 30,
                deliveryKind = VideoDeliveryKind.SDR,
                effectiveHdrMode = "OFF",
                defaultMaxLongEdge = 1920
            )
        )
        assertEquals(
            640,
            RecordingRealtimeRoutePolicy.resolveRecordingAnalysisMaxLongEdgeForRealtime(
                manufacturer = "vivo",
                model = "I2405",
                device = "I2405",
                product = "I2405i",
                width = 3264,
                height = 2448,
                requestedFps = 30,
                deliveryKind = VideoDeliveryKind.SDR,
                effectiveHdrMode = "OFF",
                defaultMaxLongEdge = 640
            )
        )
    }

    @Test
    fun `vivo i2405 realtime route caps preview surface without raising lower caps`() {
        assertEquals(
            1920,
            RecordingRealtimeRoutePolicy.resolvePreviewMaxLongEdgeForRealtime(
                manufacturer = "vivo",
                model = "I2405",
                device = "I2405",
                product = "I2405i",
                width = 3264,
                height = 2448,
                requestedFps = 30,
                deliveryKind = VideoDeliveryKind.SDR,
                effectiveHdrMode = "OFF",
                defaultMaxLongEdge = 2800
            )
        )
        assertEquals(
            960,
            RecordingRealtimeRoutePolicy.resolvePreviewMaxLongEdgeForRealtime(
                manufacturer = "vivo",
                model = "I2405",
                device = "I2405",
                product = "I2405i",
                width = 3264,
                height = 2448,
                requestedFps = 30,
                deliveryKind = VideoDeliveryKind.SDR,
                effectiveHdrMode = "OFF",
                defaultMaxLongEdge = 960
            )
        )
    }

    @Test
    fun `hdr raw low resolution and non i2405 keep existing p010 eligibility`() {
        assertFalse(
            RecordingRealtimeRoutePolicy.shouldBypassByteBufferP010ForRealtime(
                manufacturer = "vivo",
                model = "I2405",
                device = "I2405",
                product = "I2405i",
                width = 3264,
                height = 2448,
                requestedFps = 24,
                deliveryKind = VideoDeliveryKind.HDR,
                effectiveHdrMode = "HDR10"
            )
        )
        assertFalse(
            RecordingRealtimeRoutePolicy.shouldBypassByteBufferP010ForRealtime(
                manufacturer = "vivo",
                model = "I2405",
                device = "I2405",
                product = "I2405i",
                width = 3264,
                height = 2448,
                requestedFps = 24,
                deliveryKind = VideoDeliveryKind.RAW,
                effectiveHdrMode = "OFF"
            )
        )
        assertFalse(
            RecordingRealtimeRoutePolicy.shouldBypassByteBufferP010ForRealtime(
                manufacturer = "vivo",
                model = "I2405",
                device = "I2405",
                product = "I2405i",
                width = 1920,
                height = 1080,
                requestedFps = 30,
                deliveryKind = VideoDeliveryKind.SDR,
                effectiveHdrMode = "OFF"
            )
        )
        assertFalse(
            RecordingRealtimeRoutePolicy.shouldBypassByteBufferP010ForRealtime(
                manufacturer = "google",
                model = "Pixel",
                device = "pixel",
                product = "pixel",
                width = 3264,
                height = 2448,
                requestedFps = 30,
                deliveryKind = VideoDeliveryKind.SDR,
                effectiveHdrMode = "OFF"
            )
        )
    }

    @Test
    fun `non matching realtime routes keep default recording analysis cap`() {
        assertEquals(
            1920,
            RecordingRealtimeRoutePolicy.resolveRecordingAnalysisMaxLongEdgeForRealtime(
                manufacturer = "vivo",
                model = "I2405",
                device = "I2405",
                product = "I2405i",
                width = 3264,
                height = 2448,
                requestedFps = 30,
                deliveryKind = VideoDeliveryKind.HDR,
                effectiveHdrMode = "HDR10",
                defaultMaxLongEdge = 1920
            )
        )
        assertEquals(
            1920,
            RecordingRealtimeRoutePolicy.resolveRecordingAnalysisMaxLongEdgeForRealtime(
                manufacturer = "google",
                model = "Pixel",
                device = "pixel",
                product = "pixel",
                width = 3264,
                height = 2448,
                requestedFps = 30,
                deliveryKind = VideoDeliveryKind.SDR,
                effectiveHdrMode = "OFF",
                defaultMaxLongEdge = 1920
            )
        )
    }
}
