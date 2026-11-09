package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.BitrateEvidence
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.CarrierColorTags
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.CarrierPrimaries
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.CarrierTransfer
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.ColorEvidence
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.CompanionColorTransform
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.Delivery
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.Evidence
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.Matrix
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.MetadataEvidence
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.OutputTransport
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.Preservation
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.Range
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.SampleType
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.SemanticColor
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.SignalEvidence
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.SignalGamut
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.SignalTransfer
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.SourceDomain
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.Status
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.TransformEvidence
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.WorkingPipelineEvidence
import com.neoproc.neoproccamx.recording.EliteConversionQualityContract.WorkingSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EliteConversionQualityContractTest {

    @Test
    fun everySupportedDeliveryPassesWithCompleteCanonicalEvidence() {
        Delivery.values().forEach { delivery ->
            val audit = EliteConversionQualityContract.audit(validEvidence(delivery))
            assertTrue("${delivery.name}: ${audit.failureReasons}", audit.passed)
            assertEquals(Status.PASS, audit.status)
            assertTrue(audit.failureReasons.isEmpty())
        }
    }

    @Test
    fun rejectsEightBitSourceOutputAndFallbackClaims() {
        val sourceFailure = EliteConversionQualityContract.audit(
            validEvidence(Delivery.VIDEO).copy(sourceBitDepth = 8)
        )
        assertFalse(sourceFailure.passed)
        assertContains(sourceFailure, "source must be at least 10-bit")

        val outputFailure = EliteConversionQualityContract.audit(
            validEvidence(Delivery.PRO).copy(outputBitDepth = 8)
        )
        assertFalse(outputFailure.passed)
        assertContains(outputFailure, "requires an actual 10-bit output")

        val fallbackFailure = EliteConversionQualityContract.audit(
            validEvidence(Delivery.NEOLOG).copy(usedEightBitFallback = true)
        )
        assertFalse(fallbackFailure.passed)
        assertContains(fallbackFailure, "8-bit fallback is forbidden")
    }

    @Test
    fun rawRepackMustRetainSensorBitDepthAndSensorNativeDomain() {
        val evidence = validEvidence(Delivery.RAW)
        val reducedDepth = EliteConversionQualityContract.audit(evidence.copy(outputBitDepth = 10))
        assertFalse(reducedDepth.passed)
        assertContains(reducedDepth, "RAW delivery must preserve source bit depth")

        val wrongWorkingDomain = EliteConversionQualityContract.audit(
            evidence.copy(
                workingPipeline = WorkingPipelineEvidence(
                    space = WorkingSpace.LINEAR_ACESCG,
                    sampleType = SampleType.FLOAT,
                    precisionBits = 32,
                    signalIsLinear = true
                )
            )
        )
        assertFalse(wrongWorkingDomain.passed)
        assertContains(wrongWorkingDomain, "sensor-native linear space")
    }

    @Test
    fun rawSensorDevelopRequiresLinearFloat32Acescg() {
        val evidence = validEvidence(Delivery.NEOLOG)
        val lowPrecision = EliteConversionQualityContract.audit(
            evidence.copy(
                workingPipeline = evidence.workingPipeline.copy(precisionBits = 16)
            )
        )
        assertFalse(lowPrecision.passed)
        assertContains(lowPrecision, "at least Float32 working precision")

        val wrongSpace = EliteConversionQualityContract.audit(
            evidence.copy(
                workingPipeline = evidence.workingPipeline.copy(space = WorkingSpace.LINEAR_RGB)
            )
        )
        assertFalse(wrongSpace.passed)
        assertContains(wrongSpace, "linear ACEScg Float32 working plane")

        val nonlinear = EliteConversionQualityContract.audit(
            evidence.copy(
                workingPipeline = evidence.workingPipeline.copy(signalIsLinear = false)
            )
        )
        assertFalse(nonlinear.passed)
        assertContains(nonlinear, "working signal must remain linear")
    }

    @Test
    fun p010VideoDevelopAllowsLinearFloat32RgbWithoutMandatingAcescg() {
        val evidence = validEvidence(Delivery.VIDEO)
        assertEquals(SourceDomain.P010_VIDEO, evidence.sourceDomain)
        assertEquals(WorkingSpace.LINEAR_RGB, evidence.workingPipeline.space)
        assertTrue(EliteConversionQualityContract.audit(evidence).passed)
    }

    @Test
    fun rejectsDuplicateToneMapTransferOrQuantization() {
        val ndr = validEvidence(Delivery.NDR10_PLUS)
        val duplicateToneMap = EliteConversionQualityContract.audit(
            ndr.copy(transforms = ndr.transforms.copy(toneMapApplications = 2))
        )
        assertFalse(duplicateToneMap.passed)
        assertContains(duplicateToneMap, "tone mapping must be applied at most once")

        val duplicateTransfer = EliteConversionQualityContract.audit(
            ndr.copy(transforms = ndr.transforms.copy(outputTransferApplications = 2))
        )
        assertFalse(duplicateTransfer.passed)
        assertContains(duplicateTransfer, "exactly 1 output-transfer")

        val duplicateQuantization = EliteConversionQualityContract.audit(
            ndr.copy(transforms = ndr.transforms.copy(finalQuantizationApplications = 2))
        )
        assertFalse(duplicateQuantization.passed)
        assertContains(duplicateQuantization, "exactly one final quantization")
    }

    @Test
    fun enforcesModeSpecificColorDelivery() {
        val ndr = validEvidence(Delivery.NDR10_PLUS)
        val sdrTags = CarrierColorTags(
            primaries = CarrierPrimaries.BT709,
            transfer = CarrierTransfer.BT709,
            matrix = Matrix.BT709_NCL,
            range = Range.LIMITED
        )
        val audit = EliteConversionQualityContract.audit(
            ndr.copy(
                color = ndr.color.copy(
                    semantic = SemanticColor(SignalGamut.BT709, SignalTransfer.SDR),
                    expectedCarrier = sdrTags,
                    containerCarrier = sdrTags,
                    sidecarCarrier = sdrTags
                )
            )
        )
        assertFalse(audit.passed)
        assertContains(audit, "NDR/NIVUKX delivery requires ST2084 PQ with BT.2020 semantic gamut")

        val neoLog = validEvidence(Delivery.NEOLOG)
        val logAudit = EliteConversionQualityContract.audit(
            neoLog.copy(
                color = neoLog.color.copy(
                    semantic = SemanticColor(SignalGamut.BT2020, SignalTransfer.ST2084_PQ)
                )
            )
        )
        assertFalse(logAudit.passed)
        assertContains(logAudit, "NeoLog delivery requires ACES AP1 gamut")
    }

    @Test
    fun rejectsContainerAndSidecarColorTagMismatch() {
        val evidence = validEvidence(Delivery.PRO)
        val wrongTags = requireNotNull(evidence.color.expectedCarrier).copy(
            matrix = Matrix.RGB_IDENTITY,
            range = Range.FULL
        )

        val containerMismatch = EliteConversionQualityContract.audit(
            evidence.copy(color = evidence.color.copy(containerCarrier = wrongTags))
        )
        assertFalse(containerMismatch.passed)
        assertContains(containerMismatch, "container carrier color tags do not match")

        val sidecarMismatch = EliteConversionQualityContract.audit(
            evidence.copy(color = evidence.color.copy(sidecarCarrier = wrongTags))
        )
        assertFalse(sidecarMismatch.passed)
        assertContains(sidecarMismatch, "sidecar carrier color tags do not match")
    }

    @Test
    fun neoLogSeparatesAcesAp1SignalFromBt2020Bt709CarrierAndRequiresViewingLut() {
        val evidence = validEvidence(Delivery.NEOLOG)
        assertEquals(SignalGamut.ACES_AP1, evidence.color.semantic.gamut)
        assertEquals(SignalTransfer.NEOLOG, evidence.color.semantic.transfer)
        assertEquals(CarrierPrimaries.BT2020, evidence.color.expectedCarrier?.primaries)
        assertEquals(CarrierTransfer.BT709, evidence.color.expectedCarrier?.transfer)
        assertTrue(EliteConversionQualityContract.audit(evidence).passed)

        val missingLut = EliteConversionQualityContract.audit(
            evidence.copy(
                color = evidence.color.copy(
                    companionDeclaration = Preservation.NOT_PRESENT,
                    companionColorTransform = null
                )
            )
        )
        assertFalse(missingLut.passed)
        assertContains(missingLut, "requires a preserved ACES AP1 NeoLog-to-Rec.709 companion LUT")
    }

    @Test
    fun rejectsStaleDeliveryProfileAndLostMetadata() {
        val evidence = validEvidence(Delivery.NIVUKX_VISION)
        val audit = EliteConversionQualityContract.audit(
            evidence.copy(
                metadata = evidence.metadata.copy(
                    declaredDelivery = Delivery.NDR10_PLUS,
                    whiteBalance = Preservation.LOST,
                    detailProfile = Preservation.LOST
                )
            )
        )
        assertFalse(audit.passed)
        assertContains(audit, "metadata delivery profile does not match")
        assertContains(audit, "white balance metadata was lost")
        assertContains(audit, "detail profile metadata was lost")
    }

    @Test
    fun ndrAndNivukxRequireHdrMasteringEvidence() {
        listOf(Delivery.NDR10_PLUS, Delivery.NIVUKX_VISION).forEach { delivery ->
            val evidence = validEvidence(delivery)
            val audit = EliteConversionQualityContract.audit(
                evidence.copy(metadata = evidence.metadata.copy(hdrMastering = Preservation.NOT_PRESENT))
            )
            assertFalse(audit.passed)
            assertContains(audit, "requires preserved HDR mastering metadata")
        }
    }

    @Test
    fun rejectsResolutionFrameRateAndFrameCountReduction() {
        val evidence = validEvidence(Delivery.VIDEO)
        val audit = EliteConversionQualityContract.audit(
            evidence.copy(
                signal = evidence.signal.copy(
                    outputWidth = 1920,
                    outputHeight = 1080,
                    outputFps = 24,
                    outputFrameCount = 99L
                )
            )
        )
        assertFalse(audit.passed)
        assertContains(audit, "preserve the exact source resolution")
        assertContains(audit, "preserve the exact source frame rate")
        assertContains(audit, "preserve every source frame")
    }

    @Test
    fun compressedOutputsFailClosedWithoutSatisfiedBitrateEvidence() {
        val evidence = validEvidence(Delivery.NDR10_PLUS)
        val missing = EliteConversionQualityContract.audit(evidence.copy(bitrate = null))
        assertFalse(missing.passed)
        assertContains(missing, "requires bitrate-floor evidence")

        val tooLow = EliteConversionQualityContract.audit(
            evidence.copy(
                bitrate = BitrateEvidence(
                    minimumRequiredBps = 80_000_000L,
                    configuredBps = 60_000_000L,
                    measuredBps = 55_000_000L
                )
            )
        )
        assertFalse(tooLow.passed)
        assertContains(tooLow, "configured bitrate is below")
        assertContains(tooLow, "measured output bitrate is below")
    }

    @Test
    fun capabilityBoundBitrateMustBeTruthfulAndSaturateAdvertisedCeiling() {
        val evidence = validEvidence(Delivery.NDR10_PLUS)
        val truthful = EliteConversionQualityContract.audit(
            evidence.copy(
                bitrate = BitrateEvidence(
                    minimumRequiredBps = 80_000_000L,
                    configuredBps = 160_000_000L,
                    policyTargetBps = 200_000_000L,
                    advertisedCeilingBps = 160_000_000L,
                    capabilityBound = true
                )
            )
        )
        assertTrue(truthful.passed)

        val hiddenHeadroom = EliteConversionQualityContract.audit(
            evidence.copy(
                bitrate = requireNotNull(truthful.evidence.bitrate).copy(
                    configuredBps = 140_000_000L
                )
            )
        )
        assertFalse(hiddenHeadroom.passed)
        assertContains(hiddenHeadroom, "must saturate the exact advertised codec ceiling")
    }

    @Test
    fun compressedOutputsFailClosedWhenTheCodecRejectsEliteQualityControls() {
        val evidence = validEvidence(Delivery.NDR10_PLUS)

        val rejected = EliteConversionQualityContract.audit(
            evidence.copy(advancedEncoderControlsAccepted = false)
        )
        val unproven = EliteConversionQualityContract.audit(
            evidence.copy(advancedEncoderControlsAccepted = null)
        )

        assertFalse(rejected.passed)
        assertFalse(unproven.passed)
        assertContains(rejected, "requires accepted elite encoder quality controls")
        assertContains(unproven, "requires accepted elite encoder quality controls")
    }

    @Test
    fun uncompressedOutputsDoNotInventEncodedBitrateEvidence() {
        val evidence = validEvidence(Delivery.NEOLOG)
        assertTrue(EliteConversionQualityContract.audit(evidence).passed)

        val falseClaim = EliteConversionQualityContract.audit(
            evidence.copy(
                bitrate = BitrateEvidence(
                    minimumRequiredBps = 1L,
                    configuredBps = 1L
                )
            )
        )
        assertFalse(falseClaim.passed)
        assertContains(falseClaim, "must not claim an encoded bitrate contract")
    }

    @Test
    fun rejectsAnyQualityReductionForSpeedOrDestructiveFallback() {
        val evidence = validEvidence(Delivery.PRO)
        val audit = EliteConversionQualityContract.audit(
            evidence.copy(qualityReducedForSpeed = true, destructiveFallbackUsed = true)
        )
        assertFalse(audit.passed)
        assertContains(audit, "quality reduction for conversion speed is forbidden")
        assertContains(audit, "destructive quality fallback is forbidden")
    }

    @Test
    fun enforceThrowsBeforePublicationAndIncludesActionableReasons() {
        val evidence = validEvidence(Delivery.NEOLOG).copy(outputBitDepth = 8)
        try {
            EliteConversionQualityContract.enforce(evidence)
            fail("Expected fail-closed conversion quality rejection")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains(EliteConversionQualityContract.VERSION))
            assertTrue(expected.message.orEmpty().contains("actual 10-bit output"))
        }
    }

    @Test
    fun certificateMetadataIsExplicitAndDoesNotMandateGpu() {
        val metadata = EliteConversionQualityContract.enforce(
            validEvidence(Delivery.NDR10_PLUS)
        ).toMetadata()

        assertEquals(EliteConversionQualityContract.VERSION, metadata.getValue("contractVersion"))
        assertEquals("PASS", metadata.getValue("status"))
        assertEquals("LINEAR_ACESCG", metadata.getValue("workingSpace"))
        assertEquals("32", metadata.getValue("workingPrecisionBits"))
        assertEquals("1", metadata.getValue("finalQuantizationApplications"))
        assertEquals("true", metadata.getValue("resolutionPreserved"))
        assertEquals("true", metadata.getValue("frameRatePreserved"))
        assertEquals("true", metadata.getValue("frameCountPreserved"))
        assertEquals("true", metadata.getValue("bitrateFloorSatisfied"))
        assertEquals("false", metadata.getValue("gpuRequired"))
    }

    private fun validEvidence(delivery: Delivery): Evidence {
        val rawSource = delivery != Delivery.VIDEO && delivery != Delivery.PRO
        val sourceDomain = if (rawSource) SourceDomain.RAW_SENSOR else SourceDomain.P010_VIDEO
        val outputTransport = when (delivery) {
            Delivery.VIDEO -> OutputTransport.R210_RGB_444
            Delivery.PRO -> OutputTransport.V210_YUV_422
            Delivery.RAW -> OutputTransport.RAW_PACKED
            Delivery.NEOLOG -> OutputTransport.R210_RGB_444
            Delivery.NDR10_PLUS,
            Delivery.NIVUKX_VISION -> OutputTransport.HEVC_MAIN10_420
        }
        val carrier = carrierTags(delivery, outputTransport)
        val colorSidecarPresent = delivery != Delivery.RAW && delivery != Delivery.NEOLOG
        val compressedBitrate = if (outputTransport.compressed) {
            BitrateEvidence(
                minimumRequiredBps = 80_000_000L,
                configuredBps = 120_000_000L,
                measuredBps = 100_000_000L
            )
        } else {
            null
        }
        val sourceBitDepth = if (sourceDomain == SourceDomain.RAW_SENSOR) 12 else 10
        return Evidence(
            delivery = delivery,
            sourceDomain = sourceDomain,
            sourceBitDepth = sourceBitDepth,
            outputBitDepth = if (delivery == Delivery.RAW) sourceBitDepth else 10,
            outputTransport = outputTransport,
            workingPipeline = when (delivery) {
                Delivery.RAW -> WorkingPipelineEvidence(
                    space = WorkingSpace.SENSOR_NATIVE_LINEAR,
                    sampleType = SampleType.UNSIGNED_INTEGER,
                    precisionBits = 16,
                    signalIsLinear = true
                )
                Delivery.VIDEO,
                Delivery.PRO -> WorkingPipelineEvidence(
                    space = WorkingSpace.LINEAR_RGB,
                    sampleType = SampleType.FLOAT,
                    precisionBits = 32,
                    signalIsLinear = true
                )
                Delivery.NEOLOG,
                Delivery.NDR10_PLUS,
                Delivery.NIVUKX_VISION -> WorkingPipelineEvidence(
                    space = WorkingSpace.LINEAR_ACESCG,
                    sampleType = SampleType.FLOAT,
                    precisionBits = 32,
                    signalIsLinear = true
                )
            },
            transforms = TransformEvidence(
                toneMapApplications = when (delivery) {
                    Delivery.NDR10_PLUS,
                    Delivery.NIVUKX_VISION -> 1
                    else -> 0
                },
                outputTransferApplications = if (delivery == Delivery.RAW) 0 else 1,
                finalQuantizationApplications = 1
            ),
            color = ColorEvidence(
                semantic = semanticColor(delivery),
                expectedCarrier = carrier,
                containerCarrier = carrier,
                sidecarCarrier = if (colorSidecarPresent) carrier else null,
                companionDeclaration = if (delivery == Delivery.NEOLOG) {
                    Preservation.PRESERVED
                } else {
                    Preservation.NOT_PRESENT
                },
                companionColorTransform = if (delivery == Delivery.NEOLOG) {
                    CompanionColorTransform.NEOLOG_ACES_AP1_TO_REC709_LUT
                } else {
                    null
                }
            ),
            metadata = MetadataEvidence(
                declaredDelivery = delivery,
                captureMetadata = Preservation.PRESERVED,
                whiteBalance = Preservation.PRESERVED,
                exposure = Preservation.PRESERVED,
                orientation = Preservation.PRESERVED,
                detailProfile = Preservation.PRESERVED,
                frameTiming = Preservation.PRESERVED,
                colorSidecar = if (colorSidecarPresent) {
                    Preservation.PRESERVED
                } else {
                    Preservation.NOT_PRESENT
                },
                hdrMastering = if (delivery == Delivery.NDR10_PLUS || delivery == Delivery.NIVUKX_VISION) {
                    Preservation.PRESERVED
                } else {
                    Preservation.NOT_PRESENT
                }
            ),
            signal = SignalEvidence(
                sourceWidth = 3840,
                sourceHeight = 2160,
                outputWidth = 3840,
                outputHeight = 2160,
                sourceFps = 25,
                outputFps = 25,
                sourceFrameCount = 100L,
                outputFrameCount = 100L
            ),
            bitrate = compressedBitrate,
            advancedEncoderControlsAccepted = if (outputTransport.compressed) true else null,
            usedEightBitFallback = false,
            qualityReducedForSpeed = false,
            destructiveFallbackUsed = false
        )
    }

    private fun semanticColor(delivery: Delivery): SemanticColor = when (delivery) {
        Delivery.VIDEO -> SemanticColor(SignalGamut.BT709, SignalTransfer.SDR)
        Delivery.PRO -> SemanticColor(SignalGamut.BT2020, SignalTransfer.SDR)
        Delivery.RAW -> SemanticColor(SignalGamut.SENSOR_NATIVE, SignalTransfer.SENSOR_LINEAR)
        Delivery.NEOLOG -> SemanticColor(SignalGamut.ACES_AP1, SignalTransfer.NEOLOG)
        Delivery.NDR10_PLUS,
        Delivery.NIVUKX_VISION -> SemanticColor(SignalGamut.BT2020, SignalTransfer.ST2084_PQ)
    }

    private fun carrierTags(delivery: Delivery, transport: OutputTransport): CarrierColorTags? {
        val rgb = transport == OutputTransport.R210_RGB_444
        return when (delivery) {
            Delivery.VIDEO -> CarrierColorTags(
                primaries = CarrierPrimaries.BT709,
                transfer = CarrierTransfer.BT709,
                matrix = Matrix.BT709_CARRIER,
                range = Range.FULL
            )
            Delivery.PRO -> CarrierColorTags(
                primaries = CarrierPrimaries.BT2020,
                transfer = CarrierTransfer.BT709,
                matrix = Matrix.BT2020_NCL,
                range = Range.LIMITED
            )
            Delivery.RAW -> null
            Delivery.NEOLOG -> CarrierColorTags(
                primaries = CarrierPrimaries.BT2020,
                transfer = CarrierTransfer.BT709,
                matrix = if (rgb) Matrix.BT709_CARRIER else Matrix.BT2020_NCL,
                range = if (rgb) Range.FULL else Range.LIMITED
            )
            Delivery.NDR10_PLUS,
            Delivery.NIVUKX_VISION -> CarrierColorTags(
                primaries = CarrierPrimaries.BT2020,
                transfer = CarrierTransfer.ST2084_PQ,
                matrix = if (rgb) Matrix.RGB_IDENTITY else Matrix.BT2020_NCL,
                range = if (rgb) Range.FULL else Range.LIMITED
            )
        }
    }

    private fun assertContains(
        audit: EliteConversionQualityContract.Audit,
        expectedFragment: String
    ) {
        assertTrue(
            "Expected '$expectedFragment' in ${audit.failureReasons}",
            audit.failureReasons.any { it.contains(expectedFragment) }
        )
    }
}
