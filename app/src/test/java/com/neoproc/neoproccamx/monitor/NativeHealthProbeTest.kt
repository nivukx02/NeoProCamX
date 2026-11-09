package com.neoproc.neoproccamx.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHealthProbeTest {

    private fun eval(s: RuntimeSignalSnapshot) = NativeHealthProbe.evaluate(s)

    @Test
    fun cameraClosed_isInactive() {
        val r = eval(healthySnapshot(cameraOpen = false))
        assertEquals(HealthState.INACTIVE, r.state)
    }

    @Test
    fun noHalMetadataYet_isUnknown() {
        // No capture-result metadata yet — the verification is genuinely indeterminate (UNKNOWN), not a
        // degraded WARNING.
        val r = eval(healthySnapshot(halResultsFlowing = false))
        assertEquals(HealthState.UNKNOWN, r.state)
    }

    @Test
    fun ispProven_isActive() {
        val r = eval(healthySnapshot(ispVerdictLabel = "PROVEN"))
        assertEquals(HealthState.ACTIVE, r.state)
    }

    @Test
    fun ispNotProven_isRecoverableError() {
        val r = eval(healthySnapshot(ispVerdictLabel = "NOT_PROVEN", ispReadbackWarning = "NR NOT_HONORED"))
        assertEquals(HealthState.ERROR, r.state)
        assertTrue(r.recoverable)
        assertEquals("NR NOT_HONORED", r.diagnostics.runtimeException)
    }

    @Test
    fun ispNotApplicable_processedPipeline_isReadyNotInactive() {
        // Issue 4: a processed YUV/JPEG preview where the HAL is demonstrably alive is READY — the old
        // dead INACTIVE conflated this with "unsupported" and "unsettled".
        val r = eval(healthySnapshot(ispVerdictLabel = "NOT_APPLICABLE", ispRawRequestActive = false, rawStreamActive = false))
        assertEquals(HealthState.READY, r.state)
    }

    @Test
    fun ispNotApplicable_rawRequestEngaged_isUnknownPending() {
        // A RAW / ISP-off request IS engaged but read-back has not settled — verification pending.
        val r = eval(healthySnapshot(ispVerdictLabel = "NOT_APPLICABLE", ispRawRequestActive = true, rawStreamActive = true))
        assertEquals(HealthState.UNKNOWN, r.state)
        assertTrue(r.reason.contains("verifying", ignoreCase = true))
    }

    @Test
    fun ispNotApplicable_sensorLinearNdrRawTruth_isActiveWithoutRecoveryLoop() {
        val r = eval(
            healthySnapshot(
                ispVerdictLabel = "NOT_APPLICABLE",
                ispRawRequestActive = false,
                rawStreamActive = true,
                nativeBayerActive = true
            )
        )

        assertEquals(HealthState.ACTIVE, r.state)
        assertEquals(false, r.recoverable)
        assertTrue(r.reason.contains("not applicable", ignoreCase = true))
    }

    @Test
    fun hardwareCannotBypassIsp_isUnsupported() {
        // A POSITIVELY-established capability verdict: the HAL cannot disable the vendor ISP.
        val r = eval(healthySnapshot(ispBypassSupportedByHardware = false, vendorIspPresent = true, ispVerdictLabel = "NOT_APPLICABLE"))
        assertEquals(HealthState.UNSUPPORTED, r.state)
    }

    @Test
    fun bypassCapabilityUnknown_doesNotFabricateUnsupported() {
        // null (not yet reported) must NEVER become UNSUPPORTED — honesty rule.
        val r = eval(healthySnapshot(ispBypassSupportedByHardware = null, ispVerdictLabel = "NOT_APPLICABLE"))
        assertEquals(HealthState.READY, r.state)
    }
}
