package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.quality.OmegaCapabilityGate
import com.neoproc.neoproccamx.quality.OmegaCapabilityId
import com.neoproc.neoproccamx.quality.OmegaCapabilityInput
import com.neoproc.neoproccamx.recording.EliteVideoPipelineMode
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f03 (CP-U3) — capability-aware enablement is honest and gated. Default (gate off / no report)
 * leaves every option available (today's behaviour); under an active gate a proven-unavailable
 * capability is disabled with a reason citing the real OmegaCapabilityStatus; a proven SUPPORTED
 * capability stays available. The spec can only restrict, never advertise an unproven capability.
 */
class SettingsCapabilityGatingTest {

    @After
    fun reset() {
        SettingsCapabilityGatePolicy.set(false)
    }

    @Test
    fun gateOffOrNullReportOrNullIdIsAlwaysAvailable() {
        val report = OmegaCapabilityGate.evaluate(input(EliteVideoPipelineMode.NORMAL))
        SettingsCapabilityGatePolicy.set(false)
        assertTrue(SettingsCapabilitySpec.availabilityFor(OmegaCapabilityId.RAW_SENSOR_TRUTH, report).available)

        SettingsCapabilityGatePolicy.set(true)
        // Active gate but no report ⇒ available (today's behaviour preserved).
        assertTrue(SettingsCapabilitySpec.availabilityFor(OmegaCapabilityId.RAW_SENSOR_TRUTH, null).available)
        // Active gate, report present, but the option is not capability-bearing ⇒ available.
        assertTrue(SettingsCapabilitySpec.availabilityFor(null, report).available)
    }

    @Test
    fun gateActiveDisablesUnprovenCapabilityWithHonestReason() {
        SettingsCapabilityGatePolicy.set(true)
        // mode != RAW ⇒ RAW_SENSOR_TRUTH is NOT_AVAILABLE.
        val report = OmegaCapabilityGate.evaluate(input(EliteVideoPipelineMode.NORMAL))
        val availability = SettingsCapabilitySpec.availabilityFor(OmegaCapabilityId.RAW_SENSOR_TRUTH, report)
        assertFalse("unproven RAW must be disabled under an active gate", availability.available)
        assertTrue("reason must cite the real status", availability.reason!!.contains("NOT_AVAILABLE"))
    }

    @Test
    fun gateActiveAllowsProvenCapability() {
        SettingsCapabilityGatePolicy.set(true)
        val report = OmegaCapabilityGate.evaluate(rawSupportedInput())
        assertTrue(SettingsCapabilitySpec.availabilityFor(OmegaCapabilityId.RAW_SENSOR_TRUTH, report).available)
    }

    private fun input(
        mode: EliteVideoPipelineMode,
        rawSensorOriginal: Boolean = false,
        bitDepth: Int = 10
    ) = OmegaCapabilityInput(
        mode = mode,
        selectedProfile = "Original SDR",
        activeTransport = "SDR_REC709_MAIN10",
        transfer = "BT.709/OETF",
        primaries = "BT.709",
        matrix = "BT.709",
        codec = "hevc",
        encodedBitDepth = bitDepth,
        internalMathBitDepth = bitDepth,
        sourceBitDepth = bitDepth,
        storageBitDepth = bitDepth,
        width = 1920,
        height = 1080,
        fps = 30,
        bitrateBps = 67_000_000,
        selectedResolution = "1920x1080",
        actualResolution = "1920x1080",
        rawSensorOriginal = rawSensorOriginal,
        ffprobeValidated = true,
        mediaStoreValidated = true,
        deviceLogValidated = true,
        gpuProcessingValidated = false
    )

    private fun rawSupportedInput() = input(
        mode = EliteVideoPipelineMode.RAW,
        rawSensorOriginal = true,
        bitDepth = 12
    )
}
