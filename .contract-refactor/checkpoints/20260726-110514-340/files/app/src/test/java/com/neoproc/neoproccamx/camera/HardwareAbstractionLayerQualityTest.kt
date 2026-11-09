package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareAbstractionLayerQualityTest {

    @Test
    fun `cinematic encoded recording requests high quality processed sdr without raw labeling`() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true
            HardwareAbstractionLayer.resetCounters()

            val profile = HardwareAbstractionLayer.resolveProfile(
                isRecording = true,
                isRawCapture = false,
                isLogMode = false,
                isCinemaFidelity = true,
                noiseReductionEnabled = true
            )

            assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, profile.edgeMode)
            assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, profile.noiseReductionMode)
            assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, profile.hotPixelMode)
            assertEquals(CaptureRequest.SHADING_MODE_HIGH_QUALITY, profile.shadingMode)
            assertEquals(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY, profile.tonemapMode)
            assertEquals(QualityTier.OMNIPOTENT_CINEMA_ENGINE, profile.qualityTier)
            assertTrue(profile.enableOisTelemetry)
            assertTrue(profile.asyncVulkanComputeRequired)
            assertTrue(profile.pipelineCoverage.contains(HalPipelineCoverage.PREVIEW))
            assertTrue(profile.pipelineCoverage.contains(HalPipelineCoverage.RECORDING))
            assertTrue(profile.pipelineCoverage.contains(HalPipelineCoverage.NPV10_EXPORT))
            assertFalse(profile.pipelineCoverage.contains(HalPipelineCoverage.RAW_MONITOR))
            assertFalse(profile.enforceOpenGate)
            assertFalse(profile.applyLinearTonemapCurve)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
            HardwareAbstractionLayer.resetCounters()
        }
    }

    @Test
    fun `consumer auto video explicitly requests device ISP cleanup`() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = false
            HighEndQualityPolicy.strictHighEndOnly = false
            HardwareAbstractionLayer.resetCounters()

            val profile = HardwareAbstractionLayer.resolveProfile(
                isRecording = true,
                isRawCapture = false,
                isLogMode = false,
                isCinemaFidelity = false,
                noiseReductionEnabled = true,
                isConsumerMode = true
            )

            assertEquals("CONSUMER_AUTO_VIDEO", profile.name)
            assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, profile.edgeMode)
            assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, profile.noiseReductionMode)
            assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, profile.hotPixelMode)
            assertEquals(QualityTier.OMNIPOTENT_CINEMA_ENGINE, profile.qualityTier)
            assertTrue(profile.enableOisTelemetry)
            assertTrue(profile.asyncVulkanComputeRequired)
            assertTrue(profile.pipelineCoverage.contains(HalPipelineCoverage.PREVIEW))
            assertTrue(profile.pipelineCoverage.contains(HalPipelineCoverage.RECORDING))
            assertTrue(profile.pipelineCoverage.contains(HalPipelineCoverage.NPV10_EXPORT))
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
            HardwareAbstractionLayer.resetCounters()
        }
    }

    @Test
    fun `unclassified SDR recording fails safe to consumer cleanup rather than cinema processing`() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true
            HardwareAbstractionLayer.resetCounters()

            val profile = HardwareAbstractionLayer.resolveProfile(
                isRecording = true,
                isRawCapture = false,
                isLogMode = false,
                isCinemaFidelity = false,
                noiseReductionEnabled = true,
                isHdrMode = false,
                isConsumerMode = false
            )

            assertEquals("CONSUMER_AUTO_VIDEO", profile.name)
            assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, profile.edgeMode)
            assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, profile.noiseReductionMode)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
            HardwareAbstractionLayer.resetCounters()
        }
    }

    @Test
    fun `thermal throttling preserves cinema edge and noise cleanup request`() {
        val thermal = HardwareAbstractionLayer.thermalAwareProfile(
            HardwareAbstractionLayer.BEYOND_IMAX_CINEMA,
            thermalResourceCeiling = 0.42f
        )

        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, thermal.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, thermal.noiseReductionMode)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, thermal.hotPixelMode)
        assertEquals(QualityTier.OMNIPOTENT_CINEMA_ENGINE, thermal.qualityTier)
    }

    @Test
    fun `log sdr profile carries elite processed video hal contract`() {
        val profile = HardwareAbstractionLayer.BEYOND_IMAX_LOG

        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, profile.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, profile.noiseReductionMode)
        assertEquals(QualityTier.OMNIPOTENT_CINEMA_ENGINE, profile.qualityTier)
        assertTrue(profile.enableOisTelemetry)
        assertTrue(profile.asyncVulkanComputeRequired)
        assertTrue(profile.pipelineCoverage.contains(HalPipelineCoverage.PREVIEW))
        assertTrue(profile.pipelineCoverage.contains(HalPipelineCoverage.RECORDING))
        assertTrue(profile.pipelineCoverage.contains(HalPipelineCoverage.NPV10_EXPORT))
        assertFalse(profile.pipelineCoverage.contains(HalPipelineCoverage.RAW_MONITOR))
        assertFalse(profile.enforceOpenGate)
        assertFalse(profile.applyLinearTonemapCurve)
    }

    @Test
    fun `strict processed sdr honors explicit noise toggle off in hal`() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true
            HardwareAbstractionLayer.resetCounters()

            val profile = HardwareAbstractionLayer.resolveProfile(
                isRecording = true,
                isRawCapture = false,
                isLogMode = false,
                isCinemaFidelity = true,
                noiseReductionEnabled = false
            )

            // F-NR: an EXPLICIT user Noise Reduction OFF is now honored even under strict.
            assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_OFF, profile.noiseReductionMode)
            assertEquals(CaptureRequest.HOT_PIXEL_MODE_OFF, profile.hotPixelMode)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
            HardwareAbstractionLayer.resetCounters()
        }
    }

    @Test
    fun `strict processed sdr keeps high quality noise reduction when toggle on`() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true
            HardwareAbstractionLayer.resetCounters()

            val profile = HardwareAbstractionLayer.resolveProfile(
                isRecording = true,
                isRawCapture = false,
                isLogMode = false,
                isCinemaFidelity = true,
                noiseReductionEnabled = true
            )

            // No-downgrade guard: the ON (default) path stays HIGH_QUALITY exactly as before.
            assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, profile.noiseReductionMode)
            assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, profile.hotPixelMode)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
            HardwareAbstractionLayer.resetCounters()
        }
    }

    @Test
    fun `hal request mode policy keeps strict high quality off fast only devices`() {
        assertEquals(
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            CameraHalRequestModePolicy.resolveSupportedMode(
                requestedMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                availableModes = null
            )
        )
        assertEquals(
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            CameraHalRequestModePolicy.resolveSupportedMode(
                requestedMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                availableModes = emptyList()
            )
        )
        assertEquals(
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            CameraHalRequestModePolicy.resolveSupportedMode(
                requestedMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                availableModes = listOf(
                    CaptureRequest.NOISE_REDUCTION_MODE_FAST,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                )
            )
        )
        assertNull(
            CameraHalRequestModePolicy.resolveSupportedMode(
                requestedMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                availableModes = listOf(CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            )
        )
    }

    @Test
    fun `raw capture keeps truthful low processing request`() {
        val profile = HardwareAbstractionLayer.resolveProfile(
            isRecording = true,
            isRawCapture = true,
            isLogMode = false,
            isCinemaFidelity = true,
            noiseReductionEnabled = true
        )

        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_OFF, profile.noiseReductionMode)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_OFF, profile.hotPixelMode)
        assertEquals(CaptureRequest.SHADING_MODE_OFF, profile.shadingMode)
        assertEquals("SUPREME_FIDELITY_BYPASS", profile.name)
        assertTrue(profile.disableOis)
        assertTrue(profile.disableEis)
        assertTrue(profile.applyLinearTonemapCurve)
    }

    @Test
    fun `imax cinema profile exposes full frame capture mode and preserves visual processing under heat`() {
        assertEquals(QualityTier.IMAX_CINEMA, HardwareAbstractionLayer.IMAX_CINEMA.qualityTier)
        assertEquals(HalCaptureMode.IMAX_FULL_FRAME, HardwareAbstractionLayer.IMAX_FULL_FRAME)
        assertNotNull(HardwareAbstractionLayer.IMAX_CINEMA.physicalOpticsModel)

        val thermal = HardwareAbstractionLayer.thermalAwareProfile(
            HardwareAbstractionLayer.IMAX_CINEMA,
            thermalResourceCeiling = 0.42f
        )

        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, thermal.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, thermal.noiseReductionMode)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, thermal.hotPixelMode)
        assertEquals(QualityTier.IMAX_CINEMA, thermal.qualityTier)
    }

    @Test
    fun `planck zenith profile exposes full sensor mode ois telemetry and thermal preservation`() {
        assertEquals(QualityTier.PLANCK_ZENITH, HardwareAbstractionLayer.PLANCK_ZENITH.qualityTier)
        assertEquals(HalCaptureMode.PLANCK_ZENITH_FULL_SENSOR, HardwareAbstractionLayer.PLANCK_ZENITH_FULL_SENSOR)
        assertNotNull(HardwareAbstractionLayer.PLANCK_ZENITH.physicalOpticsModel)
        assertEquals(true, HardwareAbstractionLayer.PLANCK_ZENITH.enableOisTelemetry)

        val thermal = HardwareAbstractionLayer.thermalAwareProfile(
            HardwareAbstractionLayer.PLANCK_ZENITH,
            thermalResourceCeiling = 0.36f
        )

        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, thermal.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, thermal.noiseReductionMode)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, thermal.hotPixelMode)
        assertEquals(QualityTier.PLANCK_ZENITH, thermal.qualityTier)
        assertEquals(HardwareAbstractionLayer.PLANCK_ZENITH.distortionCorrectionMode, thermal.distortionCorrectionMode)
    }

    @Test
    fun `cosmic singularity profile exposes full sensor mode ois telemetry and thermal preservation`() {
        assertEquals(QualityTier.COSMIC_SINGULARITY, HardwareAbstractionLayer.COSMIC_SINGULARITY.qualityTier)
        assertEquals(HalCaptureMode.COSMIC_SINGULARITY_FULL_SENSOR, HardwareAbstractionLayer.COSMIC_SINGULARITY_FULL_SENSOR)
        assertNotNull(HardwareAbstractionLayer.COSMIC_SINGULARITY.physicalOpticsModel)
        assertEquals(true, HardwareAbstractionLayer.COSMIC_SINGULARITY.enableOisTelemetry)

        val thermal = HardwareAbstractionLayer.thermalAwareProfile(
            HardwareAbstractionLayer.COSMIC_SINGULARITY,
            thermalResourceCeiling = 0.34f
        )

        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, thermal.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, thermal.noiseReductionMode)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, thermal.hotPixelMode)
        assertEquals(QualityTier.COSMIC_SINGULARITY, thermal.qualityTier)
        assertEquals(HardwareAbstractionLayer.COSMIC_SINGULARITY.distortionCorrectionMode, thermal.distortionCorrectionMode)
    }

    @Test
    fun `omnipotent cinema engine profile exposes full sensor mode ois telemetry and thermal preservation`() {
        assertEquals(QualityTier.OMNIPOTENT_CINEMA_ENGINE, HardwareAbstractionLayer.OMNIPOTENT_CINEMA_ENGINE.qualityTier)
        assertEquals(HalCaptureMode.OMNIPOTENT_FULL_SENSOR, HardwareAbstractionLayer.OMNIPOTENT_FULL_SENSOR)
        assertNotNull(HardwareAbstractionLayer.OMNIPOTENT_CINEMA_ENGINE.physicalOpticsModel)
        assertEquals(true, HardwareAbstractionLayer.OMNIPOTENT_CINEMA_ENGINE.enableOisTelemetry)

        val thermal = HardwareAbstractionLayer.thermalAwareProfile(
            HardwareAbstractionLayer.OMNIPOTENT_CINEMA_ENGINE,
            thermalResourceCeiling = 0.34f
        )

        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, thermal.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, thermal.noiseReductionMode)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, thermal.hotPixelMode)
        assertEquals(QualityTier.OMNIPOTENT_CINEMA_ENGINE, thermal.qualityTier)
        assertEquals(HardwareAbstractionLayer.OMNIPOTENT_CINEMA_ENGINE.distortionCorrectionMode, thermal.distortionCorrectionMode)
    }

    @Test
    fun `elite quantum profiles expose truthful calibration telemetry and async compute contracts`() {
        val profiles = listOf(
            HardwareAbstractionLayer.PLANCK_ZENITH,
            HardwareAbstractionLayer.COSMIC_SINGULARITY,
            HardwareAbstractionLayer.OMNIPOTENT_CINEMA_ENGINE
        )

        profiles.forEach { profile ->
            assertEquals(37, profile.wavefrontCalibration.zernikeCoefficients.size)
            assertTrue(profile.dynamicAcesIdtCalibration.usesPublicCamera2Matrices)
            assertEquals(1_000L, profile.dualGainSyncGuardNs)
            assertTrue(profile.motionTelemetryRingBufferCapacity >= 512)
            assertTrue(profile.asyncVulkanComputeRequired)
            assertTrue(profile.pipelineCoverage.contains(HalPipelineCoverage.PREVIEW))
            assertTrue(profile.pipelineCoverage.contains(HalPipelineCoverage.RECORDING))
            assertTrue(profile.pipelineCoverage.contains(HalPipelineCoverage.NPV10_EXPORT))
        }
    }

    @Test
    fun `zenith cosmic planck signature is truthful public camera2 and full pipeline`() {
        val signature = HardwareAbstractionLayer.ZENITH_COSMIC_PLANCK_SIGNATURE

        assertTrue(signature.contains("standard=ZENITH_COSMIC_PLANCK"))
        assertTrue(signature.contains("publicCamera2=true"))
        assertTrue(signature.contains("physicalLimitClaim=false"))
        assertTrue(signature.contains("preview=ZENITH_COSMIC_PLANCK_PREVIEW_PIPELINE"))
        assertTrue(signature.contains("recording=ZENITH_COSMIC_PLANCK_DENOISE_PIPELINE"))
        assertTrue(signature.contains("wavefront=ZENITH_COSMIC_PLANCK_WAVEFRONT_PIPELINE"))
        assertTrue(signature.contains("warp=ZENITH_COSMIC_PLANCK_WARP_PIPELINE"))
        assertTrue(signature.contains("dualGain=ZENITH_COSMIC_PLANCK_DUAL_GAIN_PIPELINE"))
        assertTrue(signature.contains("npv10=ZENITH_COSMIC_PLANCK_NPV10_EXPORT_PIPELINE"))
        assertTrue(signature.contains("processedSdrHal=OMNIPOTENT_PUBLIC_CAMERA2_ISP_PIPELINE"))
        assertTrue(signature.contains("dualGainGuardNs=1000"))
        assertTrue(signature.contains("openGate=true"))
        assertTrue(signature.contains("rawBypass=SUPREME_FIDELITY_BYPASS"))
        assertTrue(signature.contains("vendor=false"))
        assertFalse(signature.contains("Dolby"))
        assertFalse(signature.contains("HDR10+"))
        assertFalse(signature.contains("ARRI"))
        assertFalse(signature.contains("Sony"))
        assertFalse(signature.contains("RED"))
    }
}
