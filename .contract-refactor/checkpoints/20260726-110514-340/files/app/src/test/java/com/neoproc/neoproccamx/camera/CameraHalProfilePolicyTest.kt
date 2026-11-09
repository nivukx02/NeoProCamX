package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraHalProfilePolicyTest {

    @Test
    fun `level3 full sensor raw complete bypass resolves ultra raw cinema profile`() {
        val profile = CameraHalProfilePolicy.resolve(
            capability = capability(
                hardwareLevel = CameraHardwareLevelTier.LEVEL_3,
                pixelArraySize = CameraStreamSize(8192, 6144),
                rawOutputSizes = listOf(CameraStreamSize(8192, 6144)),
                yuvOutputSizes = listOf(CameraStreamSize(8192, 6144)),
                encoderOutputSizes = listOf(CameraStreamSize(7680, 4320)),
                supportsTenBitHdr = true,
                supportsOis = true,
                supportsVideoStabilization = true,
                supportsYuvReprocessing = true,
                supportsPrivateReprocessing = true,
                maxPipelineDepth = 8
            ),
            requestPlan = completeRequestPlan()
        )

        assertEquals(CameraHalProfileTier.ULTRA_RAW_CINEMA, profile.tier)
        assertEquals(BypassCapabilityScore.TRUE_ORIGINAL_RAW, profile.rawScore)
        assertEquals(100, profile.score)
        assertTrue(profile.truthfulRawSensor)
        assertTrue(profile.badgeLabel.contains("Ultra RAW Cinema"))
        assertFalse(profile.limitations.any { it.contains("RAW_SENSOR not exposed") })
    }

    @Test
    fun `ten bit yuv encoder path is computational video and never claims raw`() {
        val profile = CameraHalProfilePolicy.resolve(
            capability = capability(
                hardwareLevel = CameraHardwareLevelTier.FULL,
                pixelArraySize = CameraStreamSize(4096, 3072),
                rawOutputSizes = emptyList(),
                yuvOutputSizes = listOf(CameraStreamSize(3840, 2160)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160)),
                supportsRaw = false,
                supportsManualSensor = false,
                supportsTenBitHdr = true,
                supportsOis = true,
                supportsVideoStabilization = true,
                supportsCompleteIspBypassRequest = false
            ),
            requestPlan = partialRequestPlan()
        )

        assertEquals(CameraHalProfileTier.COMPUTATIONAL_VIDEO, profile.tier)
        assertEquals(BypassCapabilityScore.VENDOR_REQUIRED, profile.rawScore)
        assertFalse(profile.truthfulRawSensor)
        assertTrue(profile.supportsTenBitHdr)
        assertTrue(profile.limitations.any { it.contains("RAW_SENSOR not exposed") })
    }

    @Test
    fun `full sensor ten bit processed path resolves elite video hal without claiming raw`() {
        val profile = CameraHalProfilePolicy.resolve(
            capability = capability(
                hardwareLevel = CameraHardwareLevelTier.LEVEL_3,
                pixelArraySize = CameraStreamSize(8192, 6144),
                rawOutputSizes = emptyList(),
                yuvOutputSizes = listOf(CameraStreamSize(8192, 6144)),
                encoderOutputSizes = listOf(CameraStreamSize(7680, 4320)),
                supportsRaw = false,
                supportsManualSensor = true,
                supportsTenBitHdr = true,
                supportsOis = true,
                supportsVideoStabilization = true,
                supportsYuvReprocessing = true,
                supportsPrivateReprocessing = true,
                maxPipelineDepth = 8,
                physicalLensProfiles = listOf(
                    CameraPhysicalLensProfile(
                        cameraId = "0a",
                        label = "24mm Wide",
                        equivalentFocalLengthMm = 24f,
                        focalLengthMm = 4.3f,
                        sensorPhysicalWidthMm = 7.0f,
                        sensorPhysicalHeightMm = 5.2f,
                        lensType = LensType.WIDE
                    ),
                    CameraPhysicalLensProfile(
                        cameraId = "0b",
                        label = "70mm Short Telephoto",
                        equivalentFocalLengthMm = 70f,
                        focalLengthMm = 11.5f,
                        sensorPhysicalWidthMm = 5.6f,
                        sensorPhysicalHeightMm = 4.2f,
                        lensType = LensType.SHORT_TELEPHOTO
                    )
                )
            ),
            requestPlan = partialRequestPlan()
        )

        assertEquals(CameraHalProfileTier.ELITE_VIDEO_HAL, profile.tier)
        assertEquals(BypassCapabilityScore.VENDOR_REQUIRED, profile.rawScore)
        assertEquals(BypassCapabilityScore.PARTIAL_VIDEO_BYPASS, profile.fullSensorScore)
        assertFalse(profile.truthfulRawSensor)
        assertTrue(profile.badgeLabel.contains("Elite Video HAL"))
        assertTrue(profile.limitations.any { it.contains("RAW_SENSOR not exposed") })
        assertTrue(profile.evidence.any { it == "eliteProcessedVideoHal=true" })
    }

    @Test
    fun `vendor gated full sensor candidate is reported as a limitation`() {
        val profile = CameraHalProfilePolicy.resolve(
            capability = capability(
                hardwareLevel = CameraHardwareLevelTier.LEVEL_3,
                pixelArraySize = CameraStreamSize(8192, 6144),
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                supportsTenBitHdr = true,
                supportsOis = true,
                supportsVideoStabilization = true
            ),
            requestPlan = partialRequestPlan(),
            vendorScanReport = CameraVendorReadoutScanReport(
                scannedKeyCount = 1,
                candidates = listOf(
                    CameraVendorReadoutCandidate(
                        keyName = "com.vendor.private.fullSensor",
                        size = CameraStreamSize(8192, 6144),
                        status = CameraVendorReadoutCandidateStatus.GATED_PRIVATE,
                        reason = "public stream map does not expose this output"
                    )
                )
            )
        )

        assertFalse(profile.truthfulRawSensor)
        assertFalse(profile.tier == CameraHalProfileTier.ULTRA_RAW_CINEMA)
        assertTrue(profile.limitations.any { it.contains("Vendor/private full-sensor stream") })
    }

    @Test
    fun `incomplete isp bypass and pixel array fallback cannot score perfect`() {
        val profile = CameraHalProfilePolicy.resolve(
            capability = capability(
                hardwareLevel = CameraHardwareLevelTier.LEVEL_3,
                pixelArraySize = CameraStreamSize(8192, 6144),
                activeArraySize = CameraStreamSize(4096, 3072),
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                supportsTenBitHdr = true,
                supportsOis = true,
                supportsVideoStabilization = true,
                maxPipelineDepth = 8
            ),
            requestPlan = partialRequestPlan()
        )

        assertEquals(BypassCapabilityScore.SENSOR_RAW_LIMITED_ISP, profile.rawScore)
        assertTrue(profile.score < 90)
        assertTrue(profile.limitations.any { it.contains("ISP-off request incomplete") })
        assertTrue(profile.limitations.any { it.contains("native pixel array") })
    }

    @Test
    fun `public logical multi camera lenses improve hal profile evidence without hidden probing`() {
        val profile = CameraHalProfilePolicy.resolve(
            capability = capability(
                hardwareLevel = CameraHardwareLevelTier.LEVEL_3,
                pixelArraySize = CameraStreamSize(8192, 6144),
                rawOutputSizes = listOf(CameraStreamSize(8192, 6144)),
                yuvOutputSizes = listOf(CameraStreamSize(8192, 6144)),
                encoderOutputSizes = listOf(CameraStreamSize(7680, 4320)),
                supportsTenBitHdr = true,
                supportsOis = true,
                supportsVideoStabilization = true,
                physicalLensProfiles = listOf(
                    CameraPhysicalLensProfile(
                        cameraId = "0a",
                        label = "13mm Ultra-Wide",
                        equivalentFocalLengthMm = 13f,
                        focalLengthMm = 2.4f,
                        sensorPhysicalWidthMm = 5.2f,
                        sensorPhysicalHeightMm = 3.9f,
                        lensType = LensType.ULTRA_WIDE
                    ),
                    CameraPhysicalLensProfile(
                        cameraId = "0b",
                        label = "24mm Wide",
                        equivalentFocalLengthMm = 24f,
                        focalLengthMm = 4.3f,
                        sensorPhysicalWidthMm = 7.0f,
                        sensorPhysicalHeightMm = 5.2f,
                        lensType = LensType.WIDE
                    ),
                    CameraPhysicalLensProfile(
                        cameraId = "0c",
                        label = "70mm Short Telephoto",
                        equivalentFocalLengthMm = 70f,
                        focalLengthMm = 11.5f,
                        sensorPhysicalWidthMm = 5.6f,
                        sensorPhysicalHeightMm = 4.2f,
                        lensType = LensType.SHORT_TELEPHOTO
                    )
                ),
                exposedVendorRequestKeys = listOf("com.neo.safe.publicMode")
            ),
            requestPlan = completeRequestPlan()
        )

        assertEquals(3, profile.physicalLensCount)
        assertTrue(profile.auxiliaryLensLabels.contains("13mm Ultra-Wide"))
        assertTrue(profile.auxiliaryLensLabels.contains("70mm Short Telephoto"))
        assertEquals(1, profile.exposedVendorRequestKeyCount)
        assertTrue(profile.evidence.any { it == "safeHalAccess=publicCamera2Only" })
        assertTrue(profile.evidence.any { it == "physicalLenses=3" })
        assertTrue(profile.evidence.any { it == "vendorRequestKeys=1" })
        assertFalse(profile.limitations.any { it.contains("hidden", ignoreCase = true) })
    }

    @Test
    fun `vendor request keys are reported as exposed metadata only`() {
        val profile = CameraHalProfilePolicy.resolve(
            capability = capability(
                hardwareLevel = CameraHardwareLevelTier.FULL,
                pixelArraySize = CameraStreamSize(4096, 3072),
                rawOutputSizes = emptyList(),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160)),
                supportsRaw = false,
                supportsTenBitHdr = true,
                exposedVendorRequestKeys = listOf(
                    "com.vendor.exposed.noiseModel",
                    "com.vendor.exposed.edgeMode"
                ),
                exposedVendorCharacteristicKeys = listOf("com.vendor.exposed.sensorMode")
            ),
            requestPlan = partialRequestPlan()
        )

        assertEquals(2, profile.exposedVendorRequestKeyCount)
        assertTrue(profile.evidence.any { it == "vendorRequestKeys=2" })
        assertTrue(profile.evidence.any { it == "vendorCharacteristicKeys=1" })
        assertTrue(profile.limitations.any { it.contains("Vendor keys are discovery-only") })
    }

    private fun capability(
        hardwareLevel: CameraHardwareLevelTier,
        pixelArraySize: CameraStreamSize,
        activeArraySize: CameraStreamSize = pixelArraySize,
        rawOutputSizes: List<CameraStreamSize>,
        yuvOutputSizes: List<CameraStreamSize>,
        encoderOutputSizes: List<CameraStreamSize>,
        supportsRaw: Boolean = rawOutputSizes.isNotEmpty(),
        supportsManualSensor: Boolean = true,
        supportsIspDisableRequestKeys: Boolean = true,
        supportsCompleteIspBypassRequest: Boolean = true,
        supportsTenBitHdr: Boolean = false,
        supportsOis: Boolean = false,
        supportsVideoStabilization: Boolean = false,
        supportsYuvReprocessing: Boolean = false,
        supportsPrivateReprocessing: Boolean = false,
        maxPipelineDepth: Int = 4,
        physicalLensProfiles: List<CameraPhysicalLensProfile> = emptyList(),
        exposedVendorRequestKeys: List<String> = emptyList(),
        exposedVendorCharacteristicKeys: List<String> = emptyList()
    ): CameraCapabilityInventory =
        CameraCapabilityInventory(
            cameraId = "0",
            pixelArraySize = pixelArraySize,
            activeArraySize = activeArraySize,
            rawOutputSizes = rawOutputSizes,
            yuvOutputSizes = yuvOutputSizes,
            jpegOutputSizes = listOf(pixelArraySize),
            encoderOutputSizes = encoderOutputSizes,
            supportsRaw = supportsRaw,
            supportsTonemapContrastCurve = true,
            supportsManualSensor = supportsManualSensor,
            supportsIspDisableRequestKeys = supportsIspDisableRequestKeys,
            supportsCompleteIspBypassRequest = supportsCompleteIspBypassRequest,
            hardwareLevel = hardwareLevel,
            supportsPrivateReprocessing = supportsPrivateReprocessing,
            supportsYuvReprocessing = supportsYuvReprocessing,
            supportsTenBitHdr = supportsTenBitHdr,
            supportsOis = supportsOis,
            supportsVideoStabilization = supportsVideoStabilization,
            maxPipelineDepth = maxPipelineDepth,
            physicalLensProfiles = physicalLensProfiles,
            exposedVendorRequestKeys = exposedVendorRequestKeys,
            exposedVendorCharacteristicKeys = exposedVendorCharacteristicKeys
        )

    private fun completeRequestPlan(): IspBypassRequestPlan =
        requestPlan(
            supported = setOf(
                IspBypassFeature.TONEMAP_CONTRAST_CURVE,
                IspBypassFeature.EDGE_OFF,
                IspBypassFeature.NOISE_REDUCTION_OFF,
                IspBypassFeature.HOT_PIXEL_OFF,
                IspBypassFeature.SHADING_OFF,
                IspBypassFeature.COLOR_ABERRATION_OFF,
                IspBypassFeature.DISTORTION_CORRECTION_OFF
            ),
            unsupported = emptyList()
        )

    private fun partialRequestPlan(): IspBypassRequestPlan =
        requestPlan(
            supported = setOf(
                IspBypassFeature.EDGE_OFF,
                IspBypassFeature.NOISE_REDUCTION_OFF,
                IspBypassFeature.HOT_PIXEL_OFF
            ),
            unsupported = listOf("TONEMAP_MODE_CONTRAST_CURVE unavailable")
        )

    private fun requestPlan(
        supported: Set<IspBypassFeature>,
        unsupported: List<String>
    ): IspBypassRequestPlan =
        IspBypassRequestPlan(
            supportedFeatures = supported,
            unsupportedReasons = unsupported,
            requiredFeatures = setOf(
                IspBypassFeature.TONEMAP_CONTRAST_CURVE,
                IspBypassFeature.EDGE_OFF,
                IspBypassFeature.NOISE_REDUCTION_OFF,
                IspBypassFeature.HOT_PIXEL_OFF,
                IspBypassFeature.SHADING_OFF,
                IspBypassFeature.COLOR_ABERRATION_OFF,
                IspBypassFeature.DISTORTION_CORRECTION_OFF
            ),
            distortionCorrectionPreference = IspBypassDistortionCorrectionPreference.SENSOR_NATIVE_OFF,
            distortionCorrectionMode = null
        )
}
