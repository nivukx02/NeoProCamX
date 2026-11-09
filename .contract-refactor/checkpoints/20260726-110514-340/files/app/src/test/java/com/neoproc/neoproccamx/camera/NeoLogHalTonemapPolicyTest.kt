package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Contract for the RETIRED NeoLog HAL tonemap gate. LOG is convert-only — recorded RAW is
 * developed to NeoLog offline — so NeoLog is NEVER baked into recorded (or preview) pixels via
 * the ISP tone curve. [NeoLogHalTonemapPolicy.shouldApply] must therefore always return false for
 * every input, and [NeoLogHalTonemapPolicy.set] must be a no-op so the direct-LOG bake can never
 * be re-enabled on any device.
 */
class NeoLogHalTonemapPolicyTest {

    private fun apply(
        logMode: Boolean = true,
        rawCapture: Boolean = false,
        hdrMode: Boolean = false,
        stillCapture: Boolean = false,
        supportsContrastCurve: Boolean = true
    ) = NeoLogHalTonemapPolicy.shouldApply(
        logMode = logMode,
        rawCapture = rawCapture,
        hdrMode = hdrMode,
        stillCapture = stillCapture,
        supportsContrastCurve = supportsContrastCurve
    )

    @Test
    fun retiredGateNeverAppliesForLogVideo() {
        NeoLogHalTonemapPolicy.set(true)
        assertFalse(apply())
    }

    @Test
    fun setIsANoOpSoTheBakeCannotBeReEnabled() {
        NeoLogHalTonemapPolicy.set(true)
        assertFalse(NeoLogHalTonemapPolicy.enabled)
        assertFalse(apply())
    }

    @Test
    fun retiredGateStaysFalseForEveryCaptureKind() {
        NeoLogHalTonemapPolicy.set(true)
        assertFalse(apply(rawCapture = true))
        assertFalse(apply(hdrMode = true))
        assertFalse(apply(stillCapture = true))
        assertFalse(apply(supportsContrastCurve = false))
        assertFalse(apply(logMode = false))
    }
}
