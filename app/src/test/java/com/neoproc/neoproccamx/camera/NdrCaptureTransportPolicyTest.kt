package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class NdrCaptureTransportPolicyTest {

    @Test
    fun `I2405 uses standard Camera2 carrier for proprietary raw sensor capture`() {
        assertEquals(
            HdrDynamicRangeMode.SDR,
            NdrCaptureTransportPolicy.resolve(
                manufacturer = "vivo",
                model = "I2405",
                device = "I2405",
                product = "I2405i",
                hdr10Supported = true,
                hdr10PlusSupported = true,
                hlg10Supported = true
            )
        )
    }

    @Test
    fun `I2405 does not fall back to vendor PQ when HLG is unavailable`() {
        assertEquals(
            HdrDynamicRangeMode.SDR,
            NdrCaptureTransportPolicy.resolve(
                manufacturer = "vivo",
                model = "I2405",
                device = "I2405",
                product = "I2405i",
                hdr10Supported = true,
                hdr10PlusSupported = true,
                hlg10Supported = false
            )
        )
    }

    @Test
    fun `unmeasured devices also keep vendor hdr transfer outside proprietary ndr`() {
        assertEquals(
            HdrDynamicRangeMode.SDR,
            NdrCaptureTransportPolicy.resolve(
                manufacturer = "Google",
                model = "Pixel",
                device = "device",
                product = "product",
                hdr10Supported = true,
                hdr10PlusSupported = true,
                hlg10Supported = true
            )
        )
    }

    @Test
    fun `advertised hlg alone never becomes proprietary ndr input`() {
        assertEquals(
            HdrDynamicRangeMode.SDR,
            NdrCaptureTransportPolicy.resolve(
                manufacturer = "example",
                model = "model",
                device = "device",
                product = "product",
                hdr10Supported = false,
                hdr10PlusSupported = false,
                hlg10Supported = true
            )
        )
    }
}
