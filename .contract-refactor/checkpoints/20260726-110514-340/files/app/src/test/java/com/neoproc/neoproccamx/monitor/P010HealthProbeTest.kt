package com.neoproc.neoproccamx.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P010HealthProbeTest {

    private fun eval(s: RuntimeSignalSnapshot) = P010HealthProbe.evaluate(s)

    @Test
    fun notRecording_tenBitCapable_isReady() {
        // Issue 2: preview must not read a permanent INACTIVE — a 10-bit-capable device is READY.
        val r = eval(healthySnapshot(recording = false))
        assertEquals(HealthState.READY, r.state)
    }

    @Test
    fun notRecording_tenBitProfileAppliedToPreview_isReadyLive() {
        val r = eval(healthySnapshot(recording = false, previewTenBitProfileApplied = true))
        assertEquals(HealthState.READY, r.state)
        assertTrue(r.reason.contains("preview", ignoreCase = true))
    }

    @Test
    fun notRecording_deviceLacksTenBit_isInactive() {
        val r = eval(
            healthySnapshot(
                recording = false,
                deviceTenBitHdrSupported = false,
                encoderP010Supported = false,
                previewTenBitProfileApplied = false
            )
        )
        assertEquals(HealthState.INACTIVE, r.state)
    }

    @Test
    fun simulatedEightBitFallback_isError() {
        val r = eval(healthySnapshot(simulatedTenBitFallbackActive = true))
        assertEquals(HealthState.ERROR, r.state)
        assertTrue(r.hardFault)
        assertEquals("8-bit encoding", r.diagnostics.currentFallback)
    }

    @Test
    fun byteBufferP010_provenTenBit_isActive() {
        val r = eval(healthySnapshot(recordingTransportLabel = "P010", encoderOutputTenBit = true))
        assertEquals(HealthState.ACTIVE, r.state)
    }

    @Test
    fun byteBufferP010_outputProvenEightBit_isError() {
        val r = eval(healthySnapshot(recordingTransportLabel = "P010", encoderOutputTenBit = false))
        assertEquals(HealthState.ERROR, r.state)
    }

    @Test
    fun byteBufferP010_unreportedOutput_isHealthyButHonestlyNoted() {
        // Device did not report the muxed profile atom: still HEALTHY (source+config prove 10-bit) but
        // the reason must NOT claim the output profile was proven.
        val r = eval(healthySnapshot(recordingTransportLabel = "P010", encoderOutputTenBit = null))
        assertEquals(HealthState.ACTIVE, r.state)
        assertNotEquals(null, r.diagnostics.suggestedSolution)
        assertTrue(r.reason.contains("not reported", ignoreCase = true))
    }

    @Test
    fun byteBufferP010_sourceBelowTenBit_isWarning() {
        val r = eval(healthySnapshot(recordingTransportLabel = "P010", p010SourceBitDepth = 8))
        assertEquals(HealthState.WARNING, r.state)
    }

    @Test
    fun gpuSurface_provenTenBit_isHealthy() {
        val r = eval(
            healthySnapshot(
                recordingTransportLabel = "AHB_GPU_COMPATIBILITY",
                encoderSurfacePrecisionProven = true,
                encoderSurfaceBitDepth = 10
            )
        )
        assertEquals(HealthState.ACTIVE, r.state)
    }

    @Test
    fun gpuSurface_precisionFallback_isError() {
        val r = eval(
            healthySnapshot(
                recordingTransportLabel = "AHB_GPU_COMPATIBILITY",
                encoderSurfacePrecisionFallback = true
            )
        )
        assertEquals(HealthState.ERROR, r.state)
    }

    @Test
    fun gpuSurface_notProven_isWarningNotHealthy() {
        // NOT_PROVEN must never read as HEALTHY.
        val r = eval(
            healthySnapshot(
                recordingTransportLabel = "AHB_GPU_COMPATIBILITY",
                encoderSurfacePrecisionProven = false,
                encoderSurfaceBitDepth = null
            )
        )
        assertEquals(HealthState.WARNING, r.state)
    }

    @Test
    fun directSurface_provenTenBit_isActive() {
        val r = eval(healthySnapshot(recordingTransportLabel = "ENCODER_SURFACE_DIRECT", encoderOutputTenBit = true))
        assertEquals(HealthState.ACTIVE, r.state)
    }

    @Test
    fun directSurface_unprovenOutput_isWarning() {
        val r = eval(healthySnapshot(recordingTransportLabel = "ENCODER_SURFACE_DIRECT", encoderOutputTenBit = null))
        assertEquals(HealthState.WARNING, r.state)
    }

    @Test
    fun directSurface_eightBitOutput_isError() {
        val r = eval(healthySnapshot(recordingTransportLabel = "ENCODER_SURFACE_DIRECT", encoderOutputTenBit = false))
        assertEquals(HealthState.ERROR, r.state)
    }

    @Test
    fun transportUnresolved_isWarning() {
        val r = eval(healthySnapshot(recordingTransportLabel = null))
        assertEquals(HealthState.WARNING, r.state)
    }
}
