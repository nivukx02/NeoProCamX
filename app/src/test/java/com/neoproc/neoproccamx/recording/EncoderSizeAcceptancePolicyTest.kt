package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the no-downgrade encoder-size accept decision ([EncoderSizeAcceptancePolicy]) that stops the
 * unencodable full-sensor size (e.g. 8192×6144) from reaching MediaCodec — see
 * [EncoderSizeDeviceTruthCapFlag]. resolveVideoConfig itself needs a live Android encoder, so the
 * device A/B (flag OFF repro, flag ON fixed) covers the wiring; this locks the pure predicate.
 */
class EncoderSizeAcceptancePolicyTest {

    @Test
    fun flagOff_isSpatialOnly_andNeverConsultsDeviceTruth() {
        var deviceTruthConsulted = false
        // A size the spatial check accepts but the encoder truly cannot run — legacy behaviour accepts it.
        val accepted = EncoderSizeAcceptancePolicy.accepts(
            spatiallySupported = true,
            deviceTruthCapEnabled = false
        ) {
            deviceTruthConsulted = true
            false
        }
        assertTrue("flag OFF must accept any spatially-supported size (legacy)", accepted)
        assertFalse("flag OFF must not evaluate the device-truth call at all", deviceTruthConsulted)
    }

    @Test
    fun flagOn_rejectsSpatiallyOkButUnencodableSize() {
        // 8192×6144: isSizeSupported=true, areSizeAndRateSupported=false → rejected, falls to fallback.
        val accepted = EncoderSizeAcceptancePolicy.accepts(
            spatiallySupported = true,
            deviceTruthCapEnabled = true
        ) { false }
        assertFalse("flag ON must reject a size the encoder cannot run at rate", accepted)
    }

    @Test
    fun flagOn_acceptsGenuinelyEncodableSize() {
        val accepted = EncoderSizeAcceptancePolicy.accepts(
            spatiallySupported = true,
            deviceTruthCapEnabled = true
        ) { true }
        assertTrue("flag ON must accept a size the encoder can run at rate", accepted)
    }

    @Test
    fun noDowngrade_supportedSizeIdenticalOnVsOff() {
        // A genuinely-encodable size (both checks true) resolves IDENTICALLY with the flag ON or OFF.
        val off = EncoderSizeAcceptancePolicy.accepts(spatiallySupported = true, deviceTruthCapEnabled = false) { true }
        val on = EncoderSizeAcceptancePolicy.accepts(spatiallySupported = true, deviceTruthCapEnabled = true) { true }
        assertEquals("no-downgrade: encodable size must accept identically ON vs OFF", off, on)
        assertTrue(on)
    }

    @Test
    fun notSpatiallySupported_alwaysRejected_andSkipsDeviceTruth() {
        for (flag in listOf(false, true)) {
            var consulted = false
            val accepted = EncoderSizeAcceptancePolicy.accepts(
                spatiallySupported = false,
                deviceTruthCapEnabled = flag
            ) {
                consulted = true
                true
            }
            assertFalse("a non-spatially-supported size is never accepted (flag=$flag)", accepted)
            assertFalse("device-truth must be skipped when not spatially supported (flag=$flag)", consulted)
        }
    }

    @Test
    fun flagDefaultsOnAndToggles() {
        // Default ON — the fix is live without opt-in.
        assertTrue(EncoderSizeDeviceTruthCapFlag.enabled)
        try {
            EncoderSizeDeviceTruthCapFlag.set(false)
            assertFalse(EncoderSizeDeviceTruthCapFlag.enabled)
            EncoderSizeDeviceTruthCapFlag.set(true)
            assertTrue(EncoderSizeDeviceTruthCapFlag.enabled)
        } finally {
            EncoderSizeDeviceTruthCapFlag.set(true) // restore default for other tests in the module.
        }
    }

    @Test
    fun accepts_defaultParameterReadsLiveFlag() {
        // When deviceTruthCapEnabled is omitted, it reads EncoderSizeDeviceTruthCapFlag.enabled.
        try {
            EncoderSizeDeviceTruthCapFlag.set(false)
            assertTrue(EncoderSizeAcceptancePolicy.accepts(spatiallySupported = true) { false })
            EncoderSizeDeviceTruthCapFlag.set(true)
            assertFalse(EncoderSizeAcceptancePolicy.accepts(spatiallySupported = true) { false })
        } finally {
            EncoderSizeDeviceTruthCapFlag.set(true)
        }
    }
}
