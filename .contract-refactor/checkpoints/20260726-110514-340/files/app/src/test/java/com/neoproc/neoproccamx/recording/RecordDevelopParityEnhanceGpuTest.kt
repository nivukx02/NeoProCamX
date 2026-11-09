package com.neoproc.neoproccamx.recording

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the GPU parity-enhance lane's contract:
 *  1. [RecordDevelopParityEnhanceGpuFlag] defaults ON and is reversible (house A/B pattern).
 *  2. On the JVM the native Vulkan library is absent, so [RecordDevelopParityEnhanceGpuBridge] reports
 *     NOT-available and every enhance call returns null — which is exactly the signal the converter uses
 *     to fall back to the byte-exact CPU parity pass. This is the NO-DOWNGRADE guarantee: with no GPU,
 *     the SDR/NDR develop output is identical to the pre-existing CPU path.
 */
class RecordDevelopParityEnhanceGpuTest {

    @After
    fun cleanup() {
        RecordDevelopParityEnhanceGpuFlag.set(true)
    }

    @Test
    fun flagDefaultsOnAndIsReversible() {
        assertTrue("parity-enhance GPU lever must default ON", RecordDevelopParityEnhanceGpuFlag.enabled)
        RecordDevelopParityEnhanceGpuFlag.set(false)
        assertFalse(RecordDevelopParityEnhanceGpuFlag.enabled)
        RecordDevelopParityEnhanceGpuFlag.set(true)
        assertTrue(RecordDevelopParityEnhanceGpuFlag.enabled)
    }

    @Test
    fun bridgeUnavailableOnJvmForcesCpuFallback() {
        // No native neoproc_vulkan on the JVM test runtime.
        assertFalse("no native GPU on JVM", RecordDevelopParityEnhanceGpuBridge.isAvailable())
        assertEquals(
            "NATIVE_LIBRARY_UNAVAILABLE",
            RecordDevelopParityEnhanceGpuBridge.unavailableReason()
        )
    }

    @Test
    fun enhanceReturnsNullWhenGpuUnavailable() {
        val w = 16
        val h = 16
        val payload = ByteArray(w * h * 2 + (w / 2) * (h / 2) * 2 * 2) // P010 4:2:0
        // Null (not a crash, not a mutated buffer) is the contract the converter relies on to run the CPU pass.
        assertNull(
            RecordDevelopParityEnhanceGpuBridge.enhanceLumaP010(
                payload = payload, width = w, height = h, uSharpness = 0.60, uDenoise = 0.50
            )
        )
    }

    @Test
    fun enhanceRejectsDegenerateInput() {
        assertNull(
            RecordDevelopParityEnhanceGpuBridge.enhanceLumaP010(
                payload = ByteArray(0), width = 16, height = 16, uSharpness = 0.60, uDenoise = 0.50
            )
        )
        assertNull(
            RecordDevelopParityEnhanceGpuBridge.enhanceLumaP010(
                payload = ByteArray(8), width = 0, height = 16, uSharpness = 0.60, uDenoise = 0.50
            )
        )
    }
}
