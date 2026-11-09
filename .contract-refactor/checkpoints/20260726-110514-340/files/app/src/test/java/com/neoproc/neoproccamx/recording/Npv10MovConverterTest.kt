package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.CRC32

class Npv10MovConverterTest {

    @Test
    fun converterWrapsSelectedNpv10MasterIntoMovContainer() {
        val input = Files.createTempFile("npv10-convert-source", ".npv10").toFile()
        val output = Files.createTempFile("npv10-convert-output", ".mov").toFile()
        Npv10FileWriter(
            outputFile = input,
            config = Npv10SessionConfig(
                width = 2,
                height = 2,
                fps = 24,
                mode = "PRO",
                colorProfile = "Rec.709",
                orientationHintDegrees = 270,
                sourceFormat = "YCBCR_P010",
                sourceBitDepth = 10,
                payloadFormat = Npv10PayloadFormat.P010_LE,
                startedAtEpochMs = 1L
            )
        ).use { writer ->
            writer.writeFrame(
                RawYuvFrame(
                    width = 2,
                    height = 2,
                    yBytes = tenBitPlane(16, 32, 64, 128),
                    uBytes = tenBitPlane(512),
                    vBytes = tenBitPlane(512),
                    yRowStride = 4,
                    yPixelStride = 2,
                    uRowStride = 2,
                    vRowStride = 2,
                    uPixelStride = 2,
                    vPixelStride = 2,
                    timestamp = 0L,
                    bitDepth = 10
                )
            )
            writer.writeFrame(
                RawYuvFrame(
                    width = 2,
                    height = 2,
                    yBytes = tenBitPlane(129, 128, 128, 127),
                    uBytes = tenBitPlane(895),
                    vBytes = tenBitPlane(105),
                    yRowStride = 4,
                    yPixelStride = 2,
                    uRowStride = 2,
                    vRowStride = 2,
                    uPixelStride = 2,
                    vPixelStride = 2,
                    timestamp = 41_666_667L,
                    bitDepth = 10
                )
            )
            writer.writeFrame(
                RawYuvFrame(
                    width = 2,
                    height = 2,
                    yBytes = tenBitPlane(129, 128, 128, 127),
                    uBytes = tenBitPlane(895),
                    vBytes = tenBitPlane(105),
                    yRowStride = 4,
                    yPixelStride = 2,
                    uRowStride = 2,
                    vRowStride = 2,
                    uPixelStride = 2,
                    vPixelStride = 2,
                    timestamp = 41_666_667L,
                    bitDepth = 10
                )
            )
        }

        val audit = Npv10MovConverter.convertWithAudit(input, output)
        val bytes = Files.readAllBytes(output.toPath())

        assertTrue(output.length() > 0L)
        assertTrue(audit.validQuickTimeAtoms)
        assertTrue(audit.outputBytes > 0L)
        assertTrue(audit.frameCount > 0L)
        assertTrue(audit.sampleEntry == "v210")
        assertTrue(audit.inputPayloadFormat == Npv10PayloadFormat.P010_LE)
        assertTrue(audit.conversionCertificate.contains("inputPayloadFormat=P010_LE"))
        assertTrue(audit.conversionCertificate.contains("sourceFormat=ycbcr_p010"))
        assertTrue(audit.conversionCertificate.contains("sourceBitDepth=10"))
        assertTrue(audit.conversionCertificate.contains("storageBitDepth=10"))
        assertTrue(
            audit.conversionCertificate.contains(
                "inputContainerVersion=${Npv10ContainerPolicy.VERSION}"
            )
        )
        assertTrue(audit.conversionCertificate.contains("inputMasterHashUnchanged=true"))
        assertTrue(audit.conversionCertificate.contains("movExportTranscodePolicy=DERIVED_V210_MOV_EXPORT_PRIVATE_NPV10_MASTER_UNMODIFIED"))
        assertTrue(audit.conversionCertificate.contains("movExportSourceBitDepthEvidence=format=P010_LE;sourceFormat=ycbcr_p010;sourceBitDepth=10;storageBitDepth=10"))
        assertTrue(audit.conversionCertificate.contains("sampleEntry=v210"))
        assertTrue(audit.conversionCertificate.contains("exportChromaNoiseMetric="))
        assertTrue(audit.conversionCertificate.contains("exportLumaNoiseMetric="))
        assertTrue(audit.conversionCertificate.contains("exportTemporalInstabilityMetric="))
        assertTrue(audit.conversionCertificate.contains("exportFrameToFrameRgbVarianceMetric="))
        assertTrue(audit.auditStatus == Npv10AuditStatus.PASS)
        assertTrue(findAscii(bytes, "ftyp") >= 0)
        assertTrue(findAscii(bytes, "mdat") >= 0)
        assertTrue(findAscii(bytes, "moov") >= 0)
        assertTrue(findAscii(bytes, "v210") >= 0)
        val tkhdTypeOffset = findAscii(bytes, "tkhd")
        assertTrue(tkhdTypeOffset >= 4)
        assertEquals(0, readSignedInt(bytes, tkhdTypeOffset + 44))
        assertEquals(-0x00010000, readSignedInt(bytes, tkhdTypeOffset + 48))
        assertEquals(0x00010000, readSignedInt(bytes, tkhdTypeOffset + 56))
    }

    @Test
    fun movAuditDefaultsDoNotClaimAdvancedProcessingWithoutEvidence() {
        val output = Files.createTempFile("npv10-default-audit", ".mov").toFile()
        output.writeText("ftyp mdat moov v210", Charsets.US_ASCII)

        val audit = Npv10IntegrityValidator.auditMovFile(
            output = output,
            frameCount = 1L,
            payloadFormat = Npv10PayloadFormat.P010_LE
        )

        listOf(
            "exportChromaSuppressionApplied",
            "exportEdgeAwareDetailApplied",
            "exportMotionCompensatedDenoiseApplied",
            "exportTemporalChromaSmoothingApplied",
            "exportTexturePreservationGuardApplied",
            "exportAdaptiveDetailReconstructionApplied",
            "exportLocalChromaSpikeSmoothingApplied",
            "exportAntiAliasEdgeProtectionApplied",
            "exportV4CinemaFidelityApplied",
            "exportV7EliteFidelityApplied",
            "exportRgbDancingNoiseLockApplied",
            "exportLowLightChromaFreezeApplied",
            "exportMotionGhostGuardApplied",
            "exportEdgeHaloSuppressionApplied",
            "exportMicroContrastDetailRestoreApplied",
            "exportResolutionHonestDetailBoostApplied",
            "exportV8MeasuredCleanDetailApplied",
            "exportRgbVarianceQuarantineApplied",
            "exportShadowChromaDeadbandApplied",
            "exportMotionSafeTemporalFilterApplied",
            "exportSkinTexturePreservationApplied",
            "exportMoireAliasGuardApplied",
            "exportDetailConfidenceGateApplied",
            "exportV9QualityConflictResolverApplied",
            "exportNoisyEdgeSharpeningSuppressorApplied",
            "exportTemporalMotionSmearGuardApplied",
            "exportCleanDetailEscalatorApplied",
            "exportSceneAdaptiveEncoderGuardApplied",
            "exportV10EvidenceCalibratedGateApplied",
            "exportNoisePressureMeterApplied",
            "exportRgbChromaInstabilityMeterApplied",
            "exportMotionSmearRiskMeterApplied",
            "exportEdgeHaloRiskMeterApplied",
            "exportEncoderStressMeterApplied",
            "exportPreviewRecordingParityEvidenceApplied",
            "exportPerceptualResolutionEvidenceApplied",
            "exportEvidenceCalibratedOutputClampApplied",
            "exportV11ConfidenceMatrixApplied",
            "exportSensorGainNoiseClampApplied",
            "exportTemporalStabilityConfidenceApplied",
            "exportChromaEdgeSeparationMapApplied",
            "exportDetailArtifactRiskGateApplied",
            "exportPerceptualUpscaleTruthGuardApplied",
            "exportCodecDetailPreservationGateApplied",
            "exportV12SensorLimitQualityGateApplied",
            "exportReadNoiseFloorEstimatorApplied",
            "exportShadowChromaNeutralityLockApplied",
            "exportTextureEdgeConfidenceFusionApplied",
            "exportMotionAdaptiveDetailHoldApplied",
            "exportPerceptualDetailScalerApplied",
            "exportFinalTruthfulQualityAuditApplied",
            "exportV13SharpnessDetailApplied",
            "exportEdgeMicroContrastLiftApplied",
            "exportFineTextureDetailBoostApplied",
            "exportHaloSafeSharpeningLiftApplied",
            "exportDetailReconstructionApplied"
        ).forEach { flag ->
            assertTrue("$flag should default false", audit.conversionCertificate.contains("$flag=false"))
        }
    }

    @Test
    fun converterAppliesExportOnlyChromaSuppressionWithoutChangingPrivateMaster() {
        val input = Files.createTempFile("npv10-enhance-source", ".npv10").toFile()
        val output = Files.createTempFile("npv10-enhance-output", ".mov").toFile()
        val originalExtremeChroma = p010ChromaPair(u = 900, v = 100)
        Npv10FileWriter(
            outputFile = input,
            config = Npv10SessionConfig(
                width = 2,
                height = 2,
                fps = 24,
                mode = "PRO",
                colorProfile = "Rec.709",
                sourceFormat = "YCBCR_P010",
                sourceBitDepth = 10,
                payloadFormat = Npv10PayloadFormat.P010_LE,
                startedAtEpochMs = 1L
            )
        ).use { writer ->
            writer.writeFrame(
                RawYuvFrame(
                    width = 2,
                    height = 2,
                    yBytes = tenBitPlane(128, 128, 128, 128),
                    uBytes = tenBitPlane(900),
                    vBytes = tenBitPlane(100),
                    yRowStride = 4,
                    yPixelStride = 2,
                    uRowStride = 2,
                    vRowStride = 2,
                    uPixelStride = 2,
                    vPixelStride = 2,
                    timestamp = 0L,
                    bitDepth = 10
                )
            )
            writer.writeFrame(
                RawYuvFrame(
                    width = 2,
                    height = 2,
                    yBytes = tenBitPlane(129, 128, 128, 127),
                    uBytes = tenBitPlane(895),
                    vBytes = tenBitPlane(105),
                    yRowStride = 4,
                    yPixelStride = 2,
                    uRowStride = 2,
                    vRowStride = 2,
                    uPixelStride = 2,
                    vPixelStride = 2,
                    timestamp = 41_666_667L,
                    bitDepth = 10
                )
            )
        }

        val masterBytesBefore = Files.readAllBytes(input.toPath())
        val audit = Npv10MovConverter.convertWithAudit(input, output)
        val movBytes = Files.readAllBytes(output.toPath())
        val masterBytesAfter = Files.readAllBytes(input.toPath())

        assertTrue(audit.conversionCertificate.contains("enhancementAppliedStage=NPV10_EXPORT_ONLY"))
        assertTrue(audit.conversionCertificate.contains("exportChromaSuppressionApplied=true"))
        assertTrue(audit.conversionCertificate.contains("exportMotionCompensatedDenoiseApplied=true"))
        assertTrue(audit.conversionCertificate.contains("exportTemporalChromaSmoothingApplied=true"))
        assertTrue(audit.conversionCertificate.contains("exportTexturePreservationGuardApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportAdaptiveDetailReconstructionApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportLocalChromaSpikeSmoothingApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportAntiAliasEdgeProtectionApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportV4CinemaFidelityApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportV7EliteFidelityApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportRgbDancingNoiseLockApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportLowLightChromaFreezeApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportMotionGhostGuardApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportEdgeHaloSuppressionApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportMicroContrastDetailRestoreApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportResolutionHonestDetailBoostApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportV8MeasuredCleanDetailApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportRgbVarianceQuarantineApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportShadowChromaDeadbandApplied=true"))
        assertTrue(audit.conversionCertificate.contains("exportMotionSafeTemporalFilterApplied=true"))
        assertTrue(audit.conversionCertificate.contains("exportSkinTexturePreservationApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportMoireAliasGuardApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportDetailConfidenceGateApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportV9QualityConflictResolverApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportNoisyEdgeSharpeningSuppressorApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportTemporalMotionSmearGuardApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportCleanDetailEscalatorApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportSceneAdaptiveEncoderGuardApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportV10EvidenceCalibratedGateApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportNoisePressureMeterApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportRgbChromaInstabilityMeterApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportMotionSmearRiskMeterApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportEdgeHaloRiskMeterApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportEncoderStressMeterApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportPreviewRecordingParityEvidenceApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportPerceptualResolutionEvidenceApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportEvidenceCalibratedOutputClampApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportV11ConfidenceMatrixApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportSensorGainNoiseClampApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportTemporalStabilityConfidenceApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportChromaEdgeSeparationMapApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportDetailArtifactRiskGateApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportPerceptualUpscaleTruthGuardApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportCodecDetailPreservationGateApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportV12SensorLimitQualityGateApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportReadNoiseFloorEstimatorApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportShadowChromaNeutralityLockApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportTextureEdgeConfidenceFusionApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportMotionAdaptiveDetailHoldApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportPerceptualDetailScalerApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportFinalTruthfulQualityAuditApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportV13SharpnessDetailApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportEdgeMicroContrastLiftApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportFineTextureDetailBoostApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportHaloSafeSharpeningLiftApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportDetailReconstructionApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportResolutionTruth=PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED"))
        assertTrue(audit.conversionCertificate.contains("masterEnhancementDestructive=false"))
        assertTrue(masterBytesBefore.contentEquals(masterBytesAfter))
        assertTrue("MOV should not contain unsuppressed extreme chroma", !containsBytes(movBytes, originalExtremeChroma))
    }

    @Test
    fun focusedDetailMasterWithoutStoredSubjectMaskExportsWithoutGlobalDetailClaim() {
        val input = Files.createTempFile("npv10-focused-master", ".npv10").toFile()
        val output = Files.createTempFile("npv10-focused-export", ".mov").toFile()
        Npv10FileWriter(
            outputFile = input,
            config = Npv10SessionConfig(
                width = 3,
                height = 3,
                fps = 24,
                mode = "PRO",
                colorProfile = "Rec.709",
                sourceFormat = "YCBCR_P010",
                sourceBitDepth = 10,
                payloadFormat = Npv10PayloadFormat.P010_LE,
                featureMetadata = mapOf(
                    "selectedDetailProfile" to "FOCUSED_DETAIL",
                    "npv10ExportSubjectMaskStored" to "false",
                    "detailProcessingPath" to "NPV10_PRIVATE_MASTER_UNMODIFIED"
                ),
                startedAtEpochMs = 1L
            )
        ).use { writer ->
            writer.writeFrame(
                RawYuvFrame(
                    width = 3,
                    height = 3,
                    yBytes = tenBitPlane(256, 256, 256, 256, 640, 256, 256, 256, 256),
                    uBytes = tenBitPlane(512, 512, 512, 512),
                    vBytes = tenBitPlane(512, 512, 512, 512),
                    yRowStride = 6,
                    yPixelStride = 2,
                    uRowStride = 4,
                    vRowStride = 4,
                    uPixelStride = 2,
                    vPixelStride = 2,
                    timestamp = 0L,
                    bitDepth = 10
                )
            )
        }

        val masterBefore = input.readBytes()
        val audit = Npv10MovConverter.convertWithAudit(input, output)

        assertTrue(masterBefore.contentEquals(input.readBytes()))
        assertTrue(audit.conversionCertificate.contains("selectedDetailProfile=FOCUSED_DETAIL"))
        assertTrue(audit.conversionCertificate.contains("exportSubjectMaskAvailable=false"))
        assertTrue(audit.conversionCertificate.contains("exportSubjectDetailApplied=false"))
        assertTrue(audit.conversionCertificate.contains("detailProcessingPath=NPV10_EXPORT_NO_SUBJECT_MASK"))
        assertTrue(audit.conversionCertificate.contains("exportEdgeAwareDetailApplied=false"))
    }

    @Test
    fun converterRoutesFocusedAndNaturalDetailProfilesExplicitly() {
        val source = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val routingBody = source.substringAfter("val enhancementProfile = when (requestedDetailProfile) {")
            .substringBefore("val exportUniforms = buildExportUniforms(")

        assertTrue(routingBody.contains("VideoDetailProfile.EXTREME_DETAIL ->"))
        assertTrue(routingBody.contains("VideoDetailProfile.FOCUSED_DETAIL,"))
        assertTrue(routingBody.contains("VideoDetailProfile.NATURAL_DETAIL ->"))
        assertTrue(routingBody.contains("baseEnhancementProfile.withoutAdditiveDetail()"))
        assertTrue(!routingBody.contains("else ->"))
    }

    @Test
    fun naturalDetailMasterWithoutStoredSubjectMaskExportsWithoutGlobalDetailClaim() {
        val input = Files.createTempFile("npv10-natural-master", ".npv10").toFile()
        val output = Files.createTempFile("npv10-natural-export", ".mov").toFile()
        Npv10FileWriter(
            outputFile = input,
            config = Npv10SessionConfig(
                width = 3,
                height = 3,
                fps = 24,
                mode = "PRO",
                colorProfile = "Rec.709",
                sourceFormat = "YCBCR_P010",
                sourceBitDepth = 10,
                payloadFormat = Npv10PayloadFormat.P010_LE,
                featureMetadata = mapOf(
                    "selectedDetailProfile" to "NATURAL_DETAIL",
                    "npv10ExportSubjectMaskStored" to "false",
                    "detailProcessingPath" to "NPV10_PRIVATE_MASTER_UNMODIFIED"
                ),
                startedAtEpochMs = 1L
            )
        ).use { writer ->
            writer.writeFrame(
                RawYuvFrame(
                    width = 3,
                    height = 3,
                    yBytes = tenBitPlane(256, 256, 256, 256, 640, 256, 256, 256, 256),
                    uBytes = tenBitPlane(512, 512, 512, 512),
                    vBytes = tenBitPlane(512, 512, 512, 512),
                    yRowStride = 6,
                    yPixelStride = 2,
                    uRowStride = 4,
                    vRowStride = 4,
                    uPixelStride = 2,
                    vPixelStride = 2,
                    timestamp = 0L,
                    bitDepth = 10
                )
            )
        }

        val masterBefore = input.readBytes()
        val audit = Npv10MovConverter.convertWithAudit(input, output)

        assertTrue(masterBefore.contentEquals(input.readBytes()))
        assertTrue(audit.conversionCertificate.contains("selectedDetailProfile=NATURAL_DETAIL"))
        assertTrue(audit.conversionCertificate.contains("detailProcessingPath=NPV10_EXPORT_NO_SUBJECT_MASK"))
        assertTrue(audit.conversionCertificate.contains("exportEdgeAwareDetailApplied=false"))
        assertTrue(audit.conversionCertificate.contains("exportSubjectMaskAvailable=false"))
        assertTrue(audit.conversionCertificate.contains("exportSubjectDetailApplied=false"))
    }

    @Test
    fun unknownPersistedDetailProfileUsesLegacyNullDetailRouteWithoutChangingPrivateMaster() {
        val input = Files.createTempFile("npv10-unknown-detail-master", ".npv10").toFile()
        val output = Files.createTempFile("npv10-unknown-detail-export", ".mov").toFile()
        Npv10FileWriter(
            outputFile = input,
            config = Npv10SessionConfig(
                width = 3,
                height = 3,
                fps = 24,
                mode = "PRO",
                colorProfile = "Rec.709",
                sourceFormat = "YCBCR_P010",
                sourceBitDepth = 10,
                payloadFormat = Npv10PayloadFormat.P010_LE,
                featureMetadata = mapOf(
                    "selectedDetailProfile" to "FUTURE_DETAIL",
                    "npv10ExportSubjectMaskStored" to "false",
                    "detailProcessingPath" to "NPV10_PRIVATE_MASTER_UNMODIFIED"
                ),
                startedAtEpochMs = 1L
            )
        ).use { writer ->
            writer.writeFrame(
                RawYuvFrame(
                    width = 3,
                    height = 3,
                    yBytes = tenBitPlane(256, 256, 256, 256, 640, 256, 256, 256, 256),
                    uBytes = tenBitPlane(512, 512, 512, 512),
                    vBytes = tenBitPlane(512, 512, 512, 512),
                    yRowStride = 6,
                    yPixelStride = 2,
                    uRowStride = 4,
                    vRowStride = 4,
                    uPixelStride = 2,
                    vPixelStride = 2,
                    timestamp = 0L,
                    bitDepth = 10
                )
            )
        }

        val masterBefore = input.readBytes()
        val audit = Npv10MovConverter.convertWithAudit(input, output)

        assertTrue(masterBefore.contentEquals(input.readBytes()))
        assertTrue(audit.conversionCertificate.contains("selectedDetailProfile=LEGACY_UNSPECIFIED"))
        assertTrue(!audit.conversionCertificate.contains("selectedDetailProfile=FOCUSED_DETAIL"))
        assertTrue(audit.conversionCertificate.contains("detailProcessingPath=LEGACY_GLOBAL_EDGE_DETAIL"))
        assertTrue(audit.conversionCertificate.contains("exportEdgeAwareDetailApplied=true"))
        assertTrue(audit.conversionCertificate.contains("exportSubjectMaskAvailable=false"))
        assertTrue(audit.conversionCertificate.contains("exportSubjectDetailApplied=false"))
        assertTrue(audit.conversionCertificate.contains("masterEnhancementDestructive=false"))
    }

    @Test
    fun extremeDetailMasterExportsDerivedDetailWithoutChangingPrivateMaster() {
        val input = Files.createTempFile("npv10-extreme-master", ".npv10").toFile()
        val output = Files.createTempFile("npv10-extreme-export", ".mov").toFile()
        Npv10FileWriter(
            outputFile = input,
            config = Npv10SessionConfig(
                width = 3,
                height = 3,
                fps = 24,
                mode = "PRO",
                colorProfile = "Rec.709",
                sourceFormat = "YCBCR_P010",
                sourceBitDepth = 10,
                payloadFormat = Npv10PayloadFormat.P010_LE,
                featureMetadata = mapOf(
                    "selectedDetailProfile" to "EXTREME_DETAIL",
                    "npv10ExportSubjectMaskStored" to "false",
                    "detailProcessingPath" to "NPV10_PRIVATE_MASTER_UNMODIFIED"
                ),
                startedAtEpochMs = 1L
            )
        ).use { writer ->
            writer.writeFrame(
                RawYuvFrame(
                    width = 3,
                    height = 3,
                    yBytes = tenBitPlane(256, 256, 256, 256, 640, 256, 256, 256, 256),
                    uBytes = tenBitPlane(512, 512, 512, 512),
                    vBytes = tenBitPlane(512, 512, 512, 512),
                    yRowStride = 6,
                    yPixelStride = 2,
                    uRowStride = 4,
                    vRowStride = 4,
                    uPixelStride = 2,
                    vPixelStride = 2,
                    timestamp = 0L,
                    bitDepth = 10
                )
            )
        }

        val masterBefore = input.readBytes()
        val audit = Npv10MovConverter.convertWithAudit(input, output)

        assertTrue(masterBefore.contentEquals(input.readBytes()))
        assertTrue(audit.conversionCertificate.contains("selectedDetailProfile=EXTREME_DETAIL"))
        assertTrue(audit.conversionCertificate.contains("detailProcessingPath=NPV10_EXPORT_EXTREME_DETAIL_DERIVED_ONLY"))
        assertTrue(audit.conversionCertificate.contains("exportEdgeAwareDetailApplied=true"))
        assertTrue(audit.conversionCertificate.contains("masterEnhancementDestructive=false"))
        assertTrue(audit.conversionCertificate.contains("exportSubjectMaskAvailable=false"))
        assertTrue(audit.conversionCertificate.contains("exportSubjectDetailApplied=false"))
    }

    @Test
    fun convertPersistsMovCertificateSidecarNextToOutput() {
        val input = Files.createTempFile("npv10-sidecar-source", ".npv10").toFile()
        val output = Files.createTempFile("npv10-sidecar-output", ".mov").toFile()
        Npv10FileWriter(
            outputFile = input,
            config = Npv10SessionConfig(
                width = 2,
                height = 2,
                fps = 24,
                mode = "PRO",
                colorProfile = "Rec.709",
                sourceFormat = "YCBCR_P010",
                sourceBitDepth = 10,
                payloadFormat = Npv10PayloadFormat.P010_LE,
                startedAtEpochMs = 1L
            )
        ).use { writer ->
            writer.writeFrame(
                RawYuvFrame(
                    width = 2,
                    height = 2,
                    yBytes = tenBitPlane(128, 128, 128, 128),
                    uBytes = tenBitPlane(512),
                    vBytes = tenBitPlane(512),
                    yRowStride = 4,
                    yPixelStride = 2,
                    uRowStride = 2,
                    vRowStride = 2,
                    uPixelStride = 2,
                    vPixelStride = 2,
                    timestamp = 0L,
                    bitDepth = 10
                )
            )
        }

        val returned = Npv10MovConverter.convert(input, output)
        val sidecar = File(output.parentFile, "${output.name}.certificate.txt")

        assertTrue(returned.auditStatus == Npv10AuditStatus.PASS)
        assertTrue(output.isFile)
        assertTrue(sidecar.isFile)
        val certificate = sidecar.readText(Charsets.UTF_8)
        assertTrue(certificate.contains("auditStatus=PASS"))
        assertTrue(certificate.contains("outputPath=${output.absolutePath}"))
        assertTrue(certificate.contains("exportMotionCompensatedDenoiseApplied=false"))
    }

    @Test
    fun exportEnhancementStronglySuppressesChromaAndRaisesEdgeDetailInP010Payload() {
        val payload = p010Frame(
            width = 3,
            height = 3,
            y = intArrayOf(
                256, 256, 256,
                256, 640, 256,
                256, 256, 256
            ),
            u = intArrayOf(960, 900, 860, 820),
            v = intArrayOf(64, 96, 128, 160)
        )

        val enhanced = Npv10ExportEnhancementPolicy.enhanceP010(payload, width = 3, height = 3)
        val chromaOffset = 3 * 3 * 2
        val originalU = readP010(payload, chromaOffset)
        val enhancedU = readP010(enhanced, chromaOffset)
        val originalCenter = readP010(payload, (1 * 3 + 1) * 2)
        val enhancedCenter = readP010(enhanced, (1 * 3 + 1) * 2)

        assertTrue(
            "chroma delta should be suppressed to 24% or less: originalU=$originalU enhancedU=$enhancedU",
            kotlin.math.abs(enhancedU - 512) <= kotlin.math.abs(originalU - 512) * 0.24
        )
        assertTrue("center edge detail should be lifted", enhancedCenter > originalCenter)
    }

    @Test
    fun exportEnhancementMetricsMeasureTemporalRgbAndChromaNoise() {
        val current = p010Frame(
            width = 3,
            height = 3,
            y = intArrayOf(
                104, 146, 112,
                150, 126, 98,
                116, 142, 106
            ),
            u = intArrayOf(580, 448, 570, 454),
            v = intArrayOf(446, 576, 452, 570)
        )
        val previous = p010Frame(
            width = 3,
            height = 3,
            y = intArrayOf(
                112, 138, 120,
                140, 132, 106,
                108, 148, 114
            ),
            u = intArrayOf(550, 470, 544, 472),
            v = intArrayOf(472, 548, 470, 544)
        )

        val metrics = Npv10ExportEnhancementPolicy.measureP010NoiseMetrics(
            payload = current,
            previousP010Payload = previous,
            width = 3,
            height = 3
        )

        assertTrue("chromaNoise=${metrics.chromaNoise}", metrics.chromaNoise > 0.05f)
        assertTrue("lumaNoise=${metrics.lumaNoise}", metrics.lumaNoise > 0.005f)
        assertTrue("temporal=${metrics.temporalInstability}", metrics.temporalInstability > 0.001f)
        assertTrue("rgbVariance=${metrics.frameToFrameRgbVariance}", metrics.frameToFrameRgbVariance > 0.01f)
    }

    @Test
    fun exportEnhancementV13LiftsStrongP010EdgesWithBoundedHalo() {
        val payload = p010Frame(
            width = 3,
            height = 3,
            y = intArrayOf(
                256, 256, 256,
                256, 640, 256,
                256, 256, 256
            ),
            u = intArrayOf(512, 512, 512, 512),
            v = intArrayOf(512, 512, 512, 512)
        )

        val enhanced = Npv10ExportEnhancementPolicy.enhanceP010(payload, width = 3, height = 3)
        val centerOffset = (1 * 3 + 1) * 2
        val originalCenter = readP010(payload, centerOffset)
        val enhancedCenter = readP010(enhanced, centerOffset)
        val lift = enhancedCenter - originalCenter

        assertTrue("V13 strong edge lift should be visibly stronger: lift=$lift", lift >= 150)
        assertTrue("V13 strong edge lift should remain halo bounded: lift=$lift", lift <= 220)
    }

    @Test
    fun ndr10ExportEnhancementRaisesMediumTextureDetailMoreThanGenericWithoutHalo() {
        val payload = p010Frame(
            width = 3,
            height = 3,
            y = intArrayOf(
                520, 520, 520,
                520, 536, 520,
                520, 520, 520
            ),
            u = intArrayOf(880, 512, 512, 512),
            v = intArrayOf(144, 512, 512, 512)
        )

        val generic = Npv10ExportEnhancementPolicy.enhanceP010(payload, width = 3, height = 3)
        val ndr = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = payload,
            width = 3,
            height = 3,
            profile = Npv10ExportEnhancementProfile.NDR10_PLUS
        )
        val centerOffset = (1 * 3 + 1) * 2
        val originalCenter = readP010(payload, centerOffset)
        val genericCenter = readP010(generic, centerOffset)
        val ndrCenter = readP010(ndr, centerOffset)
        val chromaOffset = 3 * 3 * 2
        val originalU = readP010(payload, chromaOffset)
        val ndrU = readP010(ndr, chromaOffset)

        assertTrue(
            "generic should enhance medium detail without exceeding the default halo bound",
            genericCenter in (originalCenter + 1)..(originalCenter + Npv10ExportEnhancementProfile.DEFAULT.aliasSafeMaxDetailDelta)
        )
        assertTrue("NDR10+ should restore at least as much visible medium detail as generic", ndrCenter >= genericCenter)
        assertTrue("NDR10+ should restore visible medium detail", ndrCenter > originalCenter)
        assertTrue("NDR10+ medium detail should remain halo bounded", ndrCenter <= originalCenter + 40)
        assertTrue(
            "NDR10+ chroma noise should be more tightly suppressed",
            kotlin.math.abs(ndrU - 512) <= kotlin.math.abs(originalU - 512) * 0.20
        )
    }

    @Test
    fun nivukxExportEnhancementUsesStrongerFineTextureProfileThanNdr10PlusWithoutHalo() {
        val payload = p010Frame(
            width = 3,
            height = 3,
            y = intArrayOf(
                520, 520, 520,
                520, 528, 520,
                520, 520, 520
            ),
            u = intArrayOf(920, 512, 512, 512),
            v = intArrayOf(104, 512, 512, 512)
        )
        val nivukxProfile = Npv10ExportEnhancementProfile.fromColorTagPlan(
            Npv10MovColorTagPlan(
                colorConfig = QuickTimeColorAtomConfig(
                    colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                    transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                    matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                    fullRange = false
                ),
                selectedProfile = "NIVUKX Vision",
                selectedColorSpace = "Rec.2020-NIVUKX",
                activeTransport = "BT.2100_PQ_MAIN10"
            )
        )

        val ndr = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = payload,
            width = 3,
            height = 3,
            profile = Npv10ExportEnhancementProfile.NDR10_PLUS
        )
        val nivukx = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = payload,
            width = 3,
            height = 3,
            profile = nivukxProfile
        )
        val centerOffset = (1 * 3 + 1) * 2
        val originalCenter = readP010(payload, centerOffset)
        val ndrCenter = readP010(ndr, centerOffset)
        val nivukxCenter = readP010(nivukx, centerOffset)
        val chromaOffset = 3 * 3 * 2
        val originalU = readP010(payload, chromaOffset)
        val nivukxU = readP010(nivukx, chromaOffset)

        assertTrue("NDR10+ should leave sub-threshold fine texture stable", ndrCenter <= originalCenter + 1)
        assertTrue("NIVUKX should recover finer texture than NDR10+", nivukxCenter > ndrCenter + 3)
        assertTrue("NIVUKX fine texture should remain halo bounded", nivukxCenter <= originalCenter + 10)
        assertTrue(
            "NIVUKX chroma noise should be tighter than NDR10+ export",
            kotlin.math.abs(nivukxU - 512) <= kotlin.math.abs(originalU - 512) * 0.16
        )
        assertTrue(
            "NIVUKX profile should be sharper than NDR10+ for fine texture",
            nivukxProfile.fineTextureThreshold < Npv10ExportEnhancementProfile.NDR10_PLUS.fineTextureThreshold
        )
    }

    @Test
    fun exportEnhancementAppliesShadowChromaDeadbandAroundNeutralP010Chroma() {
        val payload = p010Frame(
            width = 3,
            height = 3,
            y = intArrayOf(
                128, 128, 128,
                128, 128, 128,
                128, 128, 128
            ),
            u = intArrayOf(528, 508, 520, 496),
            v = intArrayOf(496, 524, 506, 530)
        )

        val enhanced = Npv10ExportEnhancementPolicy.enhanceP010(payload, width = 3, height = 3)
        val chromaOffset = 3 * 3 * 2

        for (offset in chromaOffset until enhanced.size step 2) {
            assertTrue(
                "near-neutral shadow chroma should be deadbanded to stable neutral at offset=$offset value=${readP010(enhanced, offset)}",
                kotlin.math.abs(readP010(enhanced, offset) - 512) <= 1
            )
        }
    }

    @Test
    fun exportEnhancementPreservesLowContrastTextureInsteadOfSharpeningItAsAnEdge() {
        val payload = p010Frame(
            width = 3,
            height = 3,
            y = intArrayOf(
                508, 508, 508,
                508, 520, 508,
                508, 508, 508
            ),
            u = intArrayOf(512, 512, 512, 512),
            v = intArrayOf(512, 512, 512, 512)
        )

        val enhanced = Npv10ExportEnhancementPolicy.enhanceP010(payload, width = 3, height = 3)
        val centerOffset = (1 * 3 + 1) * 2
        val originalCenter = readP010(payload, centerOffset)
        val enhancedCenter = readP010(enhanced, centerOffset)

        assertTrue(
            "low contrast texture should not be sharpened into a halo: original=$originalCenter enhanced=$enhancedCenter",
            kotlin.math.abs(enhancedCenter - originalCenter) <= 1
        )
    }

    @Test
    fun exportEnhancementSmoothsIsolatedChromaSpikesWithoutPollutingNeutralNeighbors() {
        val payload = p010Frame(
            width = 3,
            height = 3,
            y = intArrayOf(
                384, 384, 384,
                384, 384, 384,
                384, 384, 384
            ),
            u = intArrayOf(1023, 512, 512, 512),
            v = intArrayOf(1, 512, 512, 512)
        )

        val enhanced = Npv10ExportEnhancementPolicy.enhanceP010(payload, width = 3, height = 3)
        val chromaOffset = 3 * 3 * 2
        val originalSpikeU = readP010(payload, chromaOffset)
        val enhancedSpikeU = readP010(enhanced, chromaOffset)
        val enhancedNeighborU = readP010(enhanced, chromaOffset + 4)
        val originalSpikeV = readP010(payload, chromaOffset + 2)
        val enhancedSpikeV = readP010(enhanced, chromaOffset + 2)

        assertTrue(
            "isolated U spike should be strongly pulled toward neutral",
            kotlin.math.abs(enhancedSpikeU - 512) <= kotlin.math.abs(originalSpikeU - 512) * 0.38
        )
        assertTrue(
            "isolated V spike should be strongly pulled toward neutral",
            kotlin.math.abs(enhancedSpikeV - 512) <= kotlin.math.abs(originalSpikeV - 512) * 0.38
        )
        assertTrue(
            "neutral chroma neighbors should not be polluted by the spike: neighbor=$enhancedNeighborU",
            kotlin.math.abs(enhancedNeighborU - 512) <= 8
        )
    }

    @Test
    fun exportEnhancementPreservesCoherentWideGamutChromaInsteadOfNeutralizingRealColor() {
        val payload = p010Frame(
            width = 4,
            height = 4,
            y = IntArray(16) { 520 },
            u = intArrayOf(840, 840, 840, 840),
            v = intArrayOf(240, 240, 240, 240)
        )

        val enhanced = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = payload,
            width = 4,
            height = 4,
            profile = Npv10ExportEnhancementProfile.NIVUKX_VISION
        )
        val chromaOffset = 4 * 4 * 2
        val originalU = readP010(payload, chromaOffset)
        val enhancedU = readP010(enhanced, chromaOffset)
        val originalV = readP010(payload, chromaOffset + 2)
        val enhancedV = readP010(enhanced, chromaOffset + 2)

        assertTrue(
            "coherent U color should keep at least 45% of its chroma delta",
            kotlin.math.abs(enhancedU - 512) >= kotlin.math.abs(originalU - 512) * 0.45
        )
        assertTrue(
            "coherent V color should keep at least 45% of its chroma delta",
            kotlin.math.abs(enhancedV - 512) >= kotlin.math.abs(originalV - 512) * 0.45
        )
        assertTrue(
            "coherent U color should remain bounded by the source chroma",
            kotlin.math.abs(enhancedU - 512) <= kotlin.math.abs(originalU - 512)
        )
        assertTrue(
            "coherent V color should remain bounded by the source chroma",
            kotlin.math.abs(enhancedV - 512) <= kotlin.math.abs(originalV - 512)
        )
    }

    @Test
    fun exportEnhancementPreservesSubtleSupportedChromaInsteadOfDeadbandingToGray() {
        val payload = p010Frame(
            width = 4,
            height = 4,
            y = IntArray(16) { 520 },
            u = intArrayOf(540, 540, 540, 540),
            v = intArrayOf(488, 488, 488, 488)
        )

        val enhanced = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = payload,
            width = 4,
            height = 4,
            profile = Npv10ExportEnhancementProfile.SDR_VIDEO
        )
        val chromaOffset = 4 * 4 * 2
        val enhancedU = readP010(enhanced, chromaOffset)
        val enhancedV = readP010(enhanced, chromaOffset + 2)

        assertTrue(
            "subtle coherent U color was deadbanded toward gray: $enhancedU",
            kotlin.math.abs(enhancedU - 512) >= 16
        )
        assertTrue(
            "subtle coherent V color was deadbanded toward gray: $enhancedV",
            kotlin.math.abs(enhancedV - 512) >= 14
        )
    }

    @Test
    fun converterTagsNpv10SdrMovAsRec709Only() {
        val audit = convertTinyNpv10Master(
            mode = "PRO",
            colorProfile = "Rec.709",
            selectedColorSpace = "Rec.709",
            selectedColorScience = "Rec.709 Standard"
        )

        assertTrue(audit.conversionCertificate.contains("movColorPrimaries=1"))
        assertTrue(audit.conversionCertificate.contains("movTransferCharacteristics=1"))
        assertTrue(audit.conversionCertificate.contains("movMatrixCoefficients=1"))
        assertTrue(audit.conversionCertificate.contains("movSelectedProfile=Rec.709 Standard"))
        assertTrue(audit.conversionCertificate.contains("movActiveTransport=REC709_SDR"))
    }

    @Test
    fun converterLabelsPrivateMasterExportAsCpuFallbackWhenGpuExportIsUnavailable() {
        val audit = convertTinyNpv10Master(
            mode = "PRO",
            colorProfile = "Rec.709",
            selectedColorSpace = "Rec.709",
            selectedColorScience = "Rec.709 Standard"
        )

        assertTrue(audit.conversionCertificate.contains("exportProcessingPath=CPU_P010_FALLBACK"))
        assertTrue(audit.conversionCertificate.contains("exportZeroCopy=false"))
        assertTrue(audit.conversionCertificate.contains("exportGpuFallbackReason="))
        assertTrue(audit.conversionCertificate.contains("exportGpuUniformSignature="))
        assertFalse(audit.conversionCertificate.contains("exportProcessingPath=ZERO_COPY"))
    }

    @Test
    fun converterSourceRoutesP010ExportThroughNativeVulkanAcceleratorBeforeCpuFallback() {
        val converterSource = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val acceleratorSource = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10ExportGpuAccelerator.kt").readText()
        val nativeSource = File("src/main/cpp/npv10_export_native.cpp").readText()

        assertTrue(converterSource.contains("Npv10ExportGpuAccelerator.enhanceP010"))
        assertTrue(converterSource.contains("Channel<Npv10RawExportFrame>(capacity = PIPELINE_BUFFER_CAPACITY)"))
        assertTrue(converterSource.contains("newFixedThreadPoolContext(1, \"npv10-reader\")"))
        assertTrue(converterSource.contains("newFixedThreadPoolContext(1, \"npv10-gpu\")"))
        assertTrue(converterSource.contains("newFixedThreadPoolContext(1, \"npv10-writer\")"))
        assertFalse(converterSource.contains("DirectBufferPool"))
        assertFalse(converterSource.contains("releaseToPool"))
        assertTrue(converterSource.contains("Npv10ExportProcessingEvidence.fromFrameCounts"))
        assertTrue(converterSource.contains("isValidEnhancedP010Payload"))
        assertTrue(acceleratorSource.contains("VULKAN_P010_COMPUTE_STAGING"))
        assertTrue(acceleratorSource.contains("MIXED_VULKAN_P010_COMPUTE_STAGING_CPU_FALLBACK"))
        assertTrue(acceleratorSource.contains("ZERO_COPY_VULKAN_P010_DOUBLE_BUFFER"))
        assertTrue(acceleratorSource.contains("ThreadLocal<DirectP010Scratch>"))
        assertTrue(acceleratorSource.contains("fun ByteBuffer?.reusableDirect"))
        assertTrue(acceleratorSource.contains("nativeEnhanceP010"))
        assertTrue(acceleratorSource.contains("nativeEnhanceP010Direct"))
        assertTrue(nativeSource.contains("Java_com_neoproc_neoproccamx_recording_Npv10ExportGpuAccelerator_nativeEnhanceP010"))
        assertTrue(nativeSource.contains("Java_com_neoproc_neoproccamx_recording_Npv10ExportGpuAccelerator_nativeEnhanceP010Direct"))
        assertTrue(nativeSource.contains("vkCreateComputePipelines"))
        assertTrue(nativeSource.contains("ensureFrameResources"))
        assertTrue(nativeSource.contains("vkResetCommandBuffer"))
        assertTrue(nativeSource.contains("vkCmdDispatch"))
        assertTrue(nativeSource.contains("kNeoP010ExportEnhanceCompSpv"))
    }

    @Test
    fun exportTemporalPriorUsesPreviousSourceForGpuAndCpuFallback() {
        val source = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val exportLoop = source.substringAfter("for (rawFrame in rawFrames) {")
            .substringBefore("val playableMovPayload")

        assertTrue(exportLoop.contains("val sourceTemporalPrior = previousSourcePayload"))
        assertTrue(exportLoop.contains("previousP010Payload = sourceTemporalPrior"))
        assertTrue(exportLoop.contains("previousSourcePayload = sourcePayload.copyOf()"))
        assertFalse(exportLoop.contains("previousP010Payload = previousEnhancedPayload"))
        assertFalse(exportLoop.contains("previousEnhancedPayload"))
    }

    @Test
    fun converterTagsNdrAndNivukxNpv10MovAsBt2020Pq() {
        val ndr = convertTinyNpv10Master(
            mode = "HDR",
            colorProfile = "NDR10+",
            selectedColorSpace = "Rec.2020-NDR10+",
            selectedColorScience = "NDR10+"
        )
        val nivukx = convertTinyNpv10Master(
            mode = "HDR",
            colorProfile = "NIVUKX Vision",
            selectedColorSpace = "Rec.2020-NIVUKX",
            selectedColorScience = "NIVUKX Vision"
        )

        listOf(ndr, nivukx).forEach { audit ->
            assertTrue(audit.conversionCertificate.contains("movColorPrimaries=9"))
            assertTrue(audit.conversionCertificate.contains("movTransferCharacteristics=16"))
            assertTrue(audit.conversionCertificate.contains("movMatrixCoefficients=9"))
            assertTrue(audit.conversionCertificate.contains("movActiveTransport=BT.2100_PQ_MAIN10"))
        }
        assertTrue(ndr.conversionCertificate.contains("movSelectedProfile=NDR10+"))
        assertTrue(nivukx.conversionCertificate.contains("movSelectedProfile=NIVUKX Vision"))
    }

    @Test
    fun converterClassifiesApexAndNeoProfilesByExplicitSignalEvidence() {
        data class Case(
            val name: String,
            val audit: Npv10MovConversionAudit,
            val expectedTransfer: Int,
            val expectedTransport: String,
            val expectedSignal: String
        )

        val cases = listOf(
            Case(
                name = "APEX-LOG",
                audit = convertTinyNpv10Master(
                    mode = "LOG",
                    colorProfile = "N-LOG v5 APEX-LOG",
                    selectedColorSpace = "Rec.2020-N-Log",
                    selectedColorScience = "N-Log Cinema"
                ),
                expectedTransfer = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                expectedTransport = "BT2020_LOG_SDR",
                expectedSignal = "LOG"
            ),
            Case(
                name = "NeoGamut APEX SDR",
                audit = convertTinyNpv10Master(
                    mode = "PRO",
                    colorProfile = "SDR NeoGamut APEX",
                    selectedColorSpace = "NeoGamut APEX",
                    selectedColorScience = "NeoGamut APEX"
                ),
                expectedTransfer = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                expectedTransport = "BT2020_SDR",
                expectedSignal = "SDR"
            ),
            Case(
                name = "HDR APEX PQ",
                audit = convertTinyNpv10Master(
                    mode = "PRO",
                    colorProfile = "HDR APEX",
                    selectedColorSpace = "NeoGamut APEX",
                    selectedColorScience = "NeoGamut APEX"
                ),
                expectedTransfer = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                expectedTransport = "BT.2100_PQ_MAIN10",
                expectedSignal = "HDR"
            ),
            Case(
                name = "HDR APEX HLG",
                audit = convertTinyNpv10Master(
                    mode = "PRO",
                    colorProfile = "HDR APEX HLG",
                    selectedColorSpace = "NeoGamut APEX",
                    selectedColorScience = "NeoGamut APEX",
                    featureMetadata = mapOf("transport" to "BT.2100_HLG_MAIN10")
                ),
                expectedTransfer = QuickTimeColorAtomPolicy.TRANSFER_HLG,
                expectedTransport = "BT.2100_HLG_MAIN10",
                expectedSignal = "HDR"
            ),
            Case(
                name = "ordinary N-Log",
                audit = convertTinyNpv10Master(
                    mode = "LOG",
                    colorProfile = "N-Log Cinema",
                    selectedColorSpace = "Rec.2020-N-Log",
                    selectedColorScience = "N-Log Cinema"
                ),
                expectedTransfer = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                expectedTransport = "BT2020_LOG_SDR",
                expectedSignal = "LOG"
            ),
            Case(
                name = "NDR10+",
                audit = convertTinyNpv10Master(
                    mode = "PRO",
                    colorProfile = "PRO-HDR-Rec.2020-NDR10+-NDR10+",
                    selectedColorSpace = "Rec.2020-NDR10+",
                    selectedColorScience = "NDR10+"
                ),
                expectedTransfer = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                expectedTransport = "BT.2100_PQ_MAIN10",
                expectedSignal = "HDR"
            ),
            Case(
                name = "NIVUKX Vision",
                audit = convertTinyNpv10Master(
                    mode = "PRO",
                    colorProfile = "PRO-HDR-Rec.2020-NIVUKX-NIVUKX Vision",
                    selectedColorSpace = "Rec.2020-NIVUKX",
                    selectedColorScience = "NIVUKX Vision"
                ),
                expectedTransfer = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                expectedTransport = "BT.2100_PQ_MAIN10",
                expectedSignal = "HDR"
            )
        )

        cases.forEach { case ->
            val message = "${case.name}\n${case.audit.conversionCertificate}"
            assertTrue(message, case.audit.conversionCertificate.contains("movColorPrimaries=9"))
            assertTrue(
                message,
                case.audit.conversionCertificate.contains(
                    "movTransferCharacteristics=${case.expectedTransfer}"
                )
            )
            assertTrue(message, case.audit.conversionCertificate.contains("movMatrixCoefficients=9"))
            assertTrue(
                message,
                case.audit.conversionCertificate.contains("movActiveTransport=${case.expectedTransport}")
            )
            assertTrue(
                message,
                case.audit.conversionCertificate.contains(
                    "exportGpuUniformSignature=gpu-v1|signal=${case.expectedSignal}"
                )
            )
        }
    }

    @Test
    fun converterTagsNLogNpv10MovAsBt2020LogAndNotHdr() {
        val audit = convertTinyNpv10Master(
            mode = "LOG",
            colorProfile = "N-Log Cinema",
            selectedColorSpace = "Rec.2020-N-Log",
            selectedColorScience = "N-Log Cinema"
        )

        assertTrue(audit.conversionCertificate.contains("movColorPrimaries=9"))
        assertTrue(audit.conversionCertificate.contains("movTransferCharacteristics=1"))
        assertTrue(audit.conversionCertificate.contains("movMatrixCoefficients=9"))
        assertTrue(audit.conversionCertificate.contains("movSelectedProfile=N-Log Cinema"))
        assertTrue(audit.conversionCertificate.contains("movActiveTransport=BT2020_LOG_SDR"))
        assertTrue(!audit.conversionCertificate.contains("movActiveTransport=BT.2100_PQ_MAIN10"))
    }

    @Test
    fun converterKeepsProSdrManualLogNamedColorScienceOnSdrTransport() {
        val audit = convertTinyNpv10Master(
            mode = "PRO",
            colorProfile = "Neo Wide Gamut / N-Log Cinema",
            selectedColorSpace = "Neo Wide Gamut",
            selectedColorScience = "N-Log Cinema"
        )

        assertTrue(audit.conversionCertificate.contains("movColorPrimaries=9"))
        assertTrue(audit.conversionCertificate.contains("movTransferCharacteristics=1"))
        assertTrue(audit.conversionCertificate.contains("movMatrixCoefficients=9"))
        assertTrue(audit.conversionCertificate.contains("movSelectedProfile=N-Log Cinema"))
        assertTrue(audit.conversionCertificate.contains("movSelectedColorSpace=Neo Wide Gamut"))
        assertTrue(audit.conversionCertificate, audit.conversionCertificate.contains("movActiveTransport=BT2020_SDR"))
        assertTrue(audit.conversionCertificate.contains("normalSdrRealtimeLock=true"))
        assertTrue(audit.conversionCertificate.contains("sdrQualityIntent=PRO"))
        assertTrue(!audit.conversionCertificate.contains("movActiveTransport=BT2020_LOG_SDR"))
        assertTrue(!audit.conversionCertificate.contains("movActiveTransport=BT.2100_PQ_MAIN10"))
    }

    @Test
    fun converterTagsNeoWideGamutNpv10MovAsBt2020SdrAndNotHdr() {
        val audit = convertTinyNpv10Master(
            mode = "PRO",
            colorProfile = "Neo Wide Gamut",
            selectedColorSpace = "Neo Wide Gamut",
            selectedColorScience = "Natural"
        )

        assertTrue(audit.conversionCertificate.contains("movColorPrimaries=9"))
        assertTrue(audit.conversionCertificate.contains("movTransferCharacteristics=1"))
        assertTrue(audit.conversionCertificate.contains("movMatrixCoefficients=9"))
        assertTrue(audit.conversionCertificate.contains("movSelectedProfile=Natural"))
        assertTrue(audit.conversionCertificate.contains("movSelectedColorSpace=Neo Wide Gamut"))
        assertTrue(audit.conversionCertificate.contains("movActiveTransport=BT2020_SDR"))
        assertTrue(!audit.conversionCertificate.contains("movActiveTransport=BT.2100_PQ_MAIN10"))
    }

    @Test
    fun converterTagsAcesAp0AndAp1Npv10MovAsBt2020SdrWhilePreservingManualSelection() {
        val ap0 = convertTinyNpv10Master(
            mode = "PRO",
            colorProfile = "Neo ACES High End",
            selectedColorSpace = "ACES AP0",
            selectedColorScience = "Neo ACES High End"
        )
        val ap1 = convertTinyNpv10Master(
            mode = "PRO",
            colorProfile = "Neo ACES High End",
            selectedColorSpace = "ACEScg",
            selectedColorScience = "Neo ACES High End"
        )

        assertTrue(ap0.conversionCertificate.contains("movSelectedColorSpace=Neo ACES AP0"))
        assertTrue(ap1.conversionCertificate.contains("movSelectedColorSpace=Neo ACES AP1"))
        listOf(ap0, ap1).forEach { audit ->
            assertTrue(audit.conversionCertificate.contains("movColorPrimaries=9"))
            assertTrue(audit.conversionCertificate.contains("movTransferCharacteristics=1"))
            assertTrue(audit.conversionCertificate.contains("movMatrixCoefficients=9"))
            assertTrue(audit.conversionCertificate.contains("movActiveTransport=BT2020_SDR"))
            assertTrue(!audit.conversionCertificate.contains("movActiveTransport=BT.2100_PQ_MAIN10"))
        }
    }

    @Test
    fun normalSdrExportEnhancementUsesDedicatedFineTextureProfileWithoutHdrOrLogTagging() {
        val payload = p010Frame(
            width = 3,
            height = 3,
            y = intArrayOf(
                520, 520, 520,
                520, 536, 520,
                520, 520, 520
            ),
            u = intArrayOf(880, 512, 512, 512),
            v = intArrayOf(144, 512, 512, 512)
        )
        val normalProfile = Npv10ExportEnhancementProfile.fromColorTagPlan(
            Npv10MovColorTagPlan(
                colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited(),
                selectedProfile = "Rec.709 Standard",
                selectedColorSpace = "Rec.709",
                activeTransport = "REC709_SDR"
            )
        )

        val generic = Npv10ExportEnhancementPolicy.enhanceP010(payload, width = 3, height = 3)
        val normal = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = payload,
            width = 3,
            height = 3,
            profile = normalProfile
        )
        val centerOffset = (1 * 3 + 1) * 2
        val originalCenter = readP010(payload, centerOffset)
        val genericCenter = readP010(generic, centerOffset)
        val normalCenter = readP010(normal, centerOffset)
        val chromaOffset = 3 * 3 * 2
        val originalU = readP010(payload, chromaOffset)
        val normalU = readP010(normal, chromaOffset)

        assertTrue(
            "generic should enhance fine normal-video texture without exceeding the default halo bound",
            genericCenter in (originalCenter + 1)..(originalCenter + Npv10ExportEnhancementProfile.DEFAULT.aliasSafeMaxDetailDelta)
        )
        assertTrue(
            "Normal SDR export should recover fine texture: " +
                "originalCenter=$originalCenter, genericCenter=$genericCenter, normalCenter=$normalCenter, " +
                "fineGain=${normalProfile.fineTextureDetailGain}, " +
                "mediumGain=${normalProfile.mediumDetailGain}, " +
                "strongGain=${normalProfile.strongDetailGain}, " +
                "budget=${normalProfile.cumulativeDetailBudget}",
            normalCenter > genericCenter &&
                kotlin.math.abs(normalCenter - originalCenter) <= normalProfile.cumulativeDetailBudget
        )
        assertTrue(
            "Normal SDR fine texture should remain within the selected alias-safe ceiling",
            normalCenter <= originalCenter + normalProfile.aliasSafeMaxDetailDelta
        )
        assertTrue(
            "Normal SDR export chroma noise should be tightly suppressed",
            kotlin.math.abs(normalU - 512) <= kotlin.math.abs(originalU - 512) * 0.20
        )
        assertTrue(
            "Normal SDR profile should be more detail-sensitive than generic export",
            normalProfile.fineTextureThreshold < Npv10ExportEnhancementProfile.DEFAULT.fineTextureThreshold
        )
    }

    @Test
    fun normalAndProSdrExportEnforceCumulativeFortyCodeDetailBudget() {
        val payload = p010Frame(
            width = 5,
            height = 5,
            y = intArrayOf(
                520, 520, 520, 520, 520,
                520, 524, 524, 524, 520,
                520, 524, 528, 524, 520,
                520, 524, 524, 524, 520,
                520, 520, 520, 520, 520
            ),
            u = IntArray(9) { 512 },
            v = IntArray(9) { 512 }
        )
        val normalProfile = Npv10ExportEnhancementProfile.fromColorTagPlan(
            Npv10MovColorTagPlan(
                colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited(),
                selectedProfile = "NORMAL",
                selectedColorSpace = "Rec.709",
                activeTransport = "REC709_SDR"
            )
        )
        val proProfile = Npv10ExportEnhancementProfile.fromColorTagPlan(
            Npv10MovColorTagPlan(
                colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited(),
                selectedProfile = "PRO",
                selectedColorSpace = "Rec.709",
                activeTransport = "REC709_SDR"
            )
        )

        val normal = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = payload,
            width = 5,
            height = 5,
            profile = normalProfile
        )
        val pro = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = payload,
            width = 5,
            height = 5,
            profile = proProfile
        )

        for (sampleIndex in 0 until 25) {
            val offset = sampleIndex * 2
            val source = readP010(payload, offset)
            assertTrue(
                "Normal SDR sample $sampleIndex exceeded cumulative detail budget",
                kotlin.math.abs(readP010(normal, offset) - source) <= 40
            )
            assertTrue(
                "Pro SDR sample $sampleIndex exceeded cumulative detail budget",
                kotlin.math.abs(readP010(pro, offset) - source) <= 40
            )
        }
        assertTrue(proProfile.strongDetailGain > normalProfile.strongDetailGain)
    }

    @Test
    fun normalSdrTemporalBlendRejectsMotionAndNeverFullyReplacesCurrentFrame() {
        val current = p010Frame(
            width = 3,
            height = 3,
            y = IntArray(9) { 512 },
            u = IntArray(4) { 512 },
            v = IntArray(4) { 512 }
        )
        val stablePrior = p010Frame(
            width = 3,
            height = 3,
            y = IntArray(9) { 500 },
            u = IntArray(4) { 500 },
            v = IntArray(4) { 500 }
        )
        val movingPrior = p010Frame(
            width = 3,
            height = 3,
            y = IntArray(9) { 400 },
            u = IntArray(4) { 400 },
            v = IntArray(4) { 400 }
        )
        val temporalOnly = Npv10ExportEnhancementProfile.SDR_VIDEO.copy(
            chromaSuppression = 1.0,
            shadowChromaDeadband = 0,
            fineTextureDetailGain = 0.0,
            mediumDetailGain = 0.0,
            strongDetailGain = 0.0,
            ditherAmplitude = 0.0,
            normalSdrCalibrationStrength = 0.0
        )

        val stable = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = current,
            width = 3,
            height = 3,
            previousP010Payload = stablePrior,
            profile = temporalOnly
        )
        val moving = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = current,
            width = 3,
            height = 3,
            previousP010Payload = movingPrior,
            profile = temporalOnly
        )

        assertTrue(readP010(stable, 0) in 503..508)
        assertTrue(readP010(stable, 0) > readP010(stablePrior, 0))
        assertEquals(512, readP010(moving, 0))
    }

    @Test
    fun normalAndProSdrDoNotAmplifyIsolatedLumaNoise() {
        val payload = p010Frame(
            width = 5,
            height = 5,
            y = IntArray(25) { index -> if (index == 12) 620 else 512 },
            u = IntArray(9) { 512 },
            v = IntArray(9) { 512 }
        )

        listOf(
            Npv10ExportEnhancementProfile.SDR_VIDEO,
            Npv10ExportEnhancementProfile.fromColorTagPlan(
                Npv10MovColorTagPlan(
                    colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited(),
                    selectedProfile = "PRO",
                    selectedColorSpace = "Rec.709",
                    activeTransport = "REC709_SDR"
                )
            )
        ).forEach { profile ->
            val enhanced = Npv10ExportEnhancementPolicy.enhanceP010(
                payload = payload,
                width = 5,
                height = 5,
                profile = profile
            )
            assertTrue(
                "isolated luma impulse was amplified for $profile",
                readP010(enhanced, 12 * 2) - 512 <= 620 - 512
            )
        }
    }

    @Test
    fun normalSdrExportEnhancementUsesLongDistanceDetailPassOnLowContrastRemoteTexture() {
        val payload = p010Frame(
            width = 5,
            height = 5,
            y = intArrayOf(
                520, 521, 520, 521, 520,
                521, 524, 525, 524, 521,
                520, 525, 527, 525, 520,
                521, 524, 525, 524, 521,
                520, 521, 520, 521, 520
            ),
            u = IntArray(9) { 512 },
            v = IntArray(9) { 512 }
        )
        val normalProfile = Npv10ExportEnhancementProfile.fromColorTagPlan(
            Npv10MovColorTagPlan(
                colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited(),
                selectedProfile = "Rec.709 Standard",
                selectedColorSpace = "Rec.709",
                activeTransport = "REC709_SDR"
            )
        ).copy(ditherAmplitude = 0.0)
        val genericProfile = Npv10ExportEnhancementProfile.DEFAULT.copy(ditherAmplitude = 0.0)

        val generic = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = payload,
            width = 5,
            height = 5,
            profile = genericProfile
        )
        val normal = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = payload,
            width = 5,
            height = 5,
            profile = normalProfile
        )
        val centerOffset = (2 * 5 + 2) * 2
        val nearTextureOffset = (1 * 5 + 2) * 2
        val originalCenter = readP010(payload, centerOffset)
        val originalNearTexture = readP010(payload, nearTextureOffset)
        val genericCenter = readP010(generic, centerOffset)
        val normalCenter = readP010(normal, centerOffset)
        val normalNearTexture = readP010(normal, nearTextureOffset)

        assertTrue("generic export should keep remote low-contrast texture mostly unchanged", genericCenter <= originalCenter + 3)
        assertTrue("Normal SDR long-distance pass should preserve or improve remote texture", normalCenter >= genericCenter)
        assertTrue("Normal SDR remote texture should not erase real structure", normalNearTexture >= originalNearTexture)
        assertTrue(
            "Normal SDR remote texture lift must remain inside the cumulative budget",
            kotlin.math.abs(normalCenter - originalCenter) <= 40
        )
    }

    @Test
    fun normalRec2020SdrExportEnhancementUsesDedicatedSdrProfile() {
        val normalProfile = Npv10ExportEnhancementProfile.fromColorTagPlan(
            Npv10MovColorTagPlan(
                colorConfig = QuickTimeColorAtomConfig(
                    colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                    transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                    matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                    fullRange = false
                ),
                selectedProfile = "Rec.2020 Standard",
                selectedColorSpace = "Rec.2020",
                activeTransport = "BT2020_SDR"
            )
        )

        assertEquals(Npv10ExportEnhancementProfile.SDR_VIDEO, normalProfile)
    }

    @Test
    fun logExportEnhancementUsesDedicatedFineTextureProfileWithoutHdrTagging() {
        val payload = p010Frame(
            width = 3,
            height = 3,
            y = intArrayOf(
                520, 520, 520,
                520, 534, 520,
                520, 520, 520
            ),
            u = intArrayOf(900, 512, 512, 512),
            v = intArrayOf(124, 512, 512, 512)
        )
        val logProfile = Npv10ExportEnhancementProfile.fromColorTagPlan(
            Npv10MovColorTagPlan(
                colorConfig = QuickTimeColorAtomConfig(
                    colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                    transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                    matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                    fullRange = false
                ),
                selectedProfile = "N-Log Cinema",
                selectedColorSpace = "Rec.2020-N-Log",
                activeTransport = "BT2020_LOG_SDR"
            )
        )

        val generic = Npv10ExportEnhancementPolicy.enhanceP010(payload, width = 3, height = 3)
        val log = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = payload,
            width = 3,
            height = 3,
            profile = logProfile
        )
        val centerOffset = (1 * 3 + 1) * 2
        val originalCenter = readP010(payload, centerOffset)
        val genericCenter = readP010(generic, centerOffset)
        val logCenter = readP010(log, centerOffset)
        val chromaOffset = 3 * 3 * 2
        val originalU = readP010(payload, chromaOffset)
        val logU = readP010(log, chromaOffset)

        assertTrue("generic should leave fine log texture stable", genericCenter <= originalCenter + 1)
        assertTrue("LOG export should recover fine texture", logCenter > genericCenter + 3)
        assertTrue("LOG export fine texture should remain strict halo bounded", logCenter <= originalCenter + 40)
        assertTrue(
            "LOG export chroma noise should be tightly suppressed",
            kotlin.math.abs(logU - 512) <= kotlin.math.abs(originalU - 512) * 0.18
        )
        assertTrue(
            "LOG profile should be more detail-sensitive than generic export",
            logProfile.fineTextureThreshold < Npv10ExportEnhancementProfile.DEFAULT.fineTextureThreshold
        )
    }

    @Test
    fun converterRejectsUnsupportedPayloadFormatMetadata() {
        val input = Files.createTempFile("npv10-bad-format", ".npv10").toFile()
        val output = Files.createTempFile("npv10-bad-format-output", ".mov").toFile()
        DataOutputStream(FileOutputStream(input)).use { stream ->
            stream.write(Npv10ContainerPolicy.MAGIC_BYTES)
            stream.writeInt(Npv10ContainerPolicy.VERSION)
            stream.writeInt(2)
            stream.writeInt(2)
            stream.writeInt(24)
            stream.writeInt(10)
            val metadata = """
                container=npv10
                payloadformat=unsupported_private_layout
                sourceformat=yuv_420_888
                sourcebitdepth=8
                storagebitdepth=10
                exactselectedresolution=true
            """.trimIndent().toByteArray(Charsets.UTF_8)
            stream.writeInt(metadata.size)
            stream.write(metadata)
        }

        var rejected = false
        try {
            Npv10MovConverter.convert(input, output)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun npv10LowLevelIoUsesStreamingFileChannelsAndDirectPayloadBuffers() {
        val readerSource = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10FileReader.kt").readText()
        val writerSource = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10FileWriter.kt").readText()
        val packerSource = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10FramePacker.kt").readText()
        val validatorSource = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidator.kt").readText()

        assertTrue(readerSource.contains("FileChannel.open"))
        assertFalse(readerSource.contains("FileChannel.MapMode.READ_ONLY"))
        assertTrue(readerSource.contains("readBytesOrNull"))
        assertTrue(readerSource.contains("readNextFrameInto(target: ByteBuffer)"))
        // Preallocation needs a real FileDescriptor, so the writer may obtain the same streaming
        // FileChannel from RandomAccessFile instead of FileChannel.open.
        assertTrue(
            writerSource.contains("FileChannel.open") ||
                (writerSource.contains("RandomAccessFile") &&
                    writerSource.contains("randomAccessFile.channel"))
        )
        assertTrue(writerSource.contains("writeFramePayload"))
        assertTrue(writerSource.contains("MAX_ZERO_WRITE_RETRIES"))
        assertTrue(packerSource.contains("packIntoDirectP010"))
        assertTrue(packerSource.contains("nativePackP010Neon"))
        assertTrue(validatorSource.contains("validateP010PayloadAlignment"))
    }

    @Test
    fun converterRejectsEightBitExpandedPayloadMislabeledAsNativeTenBit() {
        val input = Files.createTempFile("npv10-mislabeled-8bit", ".npv10").toFile()
        val output = Files.createTempFile("npv10-mislabeled-8bit-output", ".mov").toFile()
        writeManualNpv10(
            output = input,
            metadata = """
                container=npv10
                payloadformat=YUV420_8BIT_EXPANDED
                sourceformat=YUV_420_888
                sourcebitdepth=10
                storagebitdepth=10
                exactselectedresolution=true
                selectedcolorspace=Rec.709
                selectedcolorscience=Rec.709 Standard
            """.trimIndent(),
            payload = planarTenBitPayload()
        )

        var message = ""
        try {
            Npv10MovConverter.convert(input, output)
        } catch (e: IllegalArgumentException) {
            message = e.message.orEmpty()
        }

        assertTrue(message.contains("YUV420_8BIT_EXPANDED"))
        assertTrue(message.contains("native 10-bit"))
    }

    @Test
    fun converterRejectsPlanarTenBitPayloadMislabeledAsP010() {
        val input = Files.createTempFile("npv10-mislabeled-p010", ".npv10").toFile()
        val output = Files.createTempFile("npv10-mislabeled-p010-output", ".mov").toFile()
        writeManualNpv10(
            output = input,
            metadata = """
                container=npv10
                payloadformat=P010_LE
                sourceformat=YCBCR_P010
                sourcebitdepth=10
                storagebitdepth=10
                exactselectedresolution=true
                selectedcolorspace=Rec.709
                selectedcolorscience=Rec.709 Standard
            """.trimIndent(),
            payload = planarTenBitPayload()
        )

        var message = ""
        try {
            Npv10MovConverter.convert(input, output)
        } catch (e: IllegalArgumentException) {
            message = e.message.orEmpty()
        }

        assertTrue(message.contains("P010"))
        assertTrue(message.contains("payload layout"))
    }

    private fun findAscii(bytes: ByteArray, text: String): Int {
        val needle = text.toByteArray(Charsets.US_ASCII)
        for (index in 0..bytes.size - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (bytes[index + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
        }
        return -1
    }

    private fun containsBytes(bytes: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || bytes.size < needle.size) return false
        for (index in 0..bytes.size - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (bytes[index + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    private fun tenBitPlane(vararg samples: Int): ByteArray {
        val output = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val value = sample.coerceIn(0, 1023)
            output[index * 2] = (value and 0xFF).toByte()
            output[index * 2 + 1] = ((value ushr 8) and 0x03).toByte()
        }
        return output
    }

    private fun p010ChromaPair(u: Int, v: Int): ByteArray {
        val output = ByteArray(4)
        writeP010(output, 0, u)
        writeP010(output, 2, v)
        return output
    }

    private fun writeP010(output: ByteArray, offset: Int, sample: Int) {
        val value = sample.coerceIn(0, 1023) shl 6
        output[offset] = (value and 0xFF).toByte()
        output[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun p010Frame(width: Int, height: Int, y: IntArray, u: IntArray, v: IntArray): ByteArray {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val output = ByteArray((width * height + chromaWidth * chromaHeight * 2) * 2)
        y.forEachIndexed { index, sample -> writeP010(output, index * 2, sample) }
        var offset = width * height * 2
        for (index in 0 until chromaWidth * chromaHeight) {
            writeP010(output, offset, u[index])
            writeP010(output, offset + 2, v[index])
            offset += 4
        }
        return output
    }

    private fun readP010(bytes: ByteArray, offset: Int): Int {
        val value = (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        return value ushr 6
    }

    private fun convertTinyNpv10Master(
        mode: String,
        colorProfile: String,
        selectedColorSpace: String,
        selectedColorScience: String,
        featureMetadata: Map<String, String> = emptyMap()
    ): Npv10MovConversionAudit {
        val input = Files.createTempFile("npv10-color-source", ".npv10").toFile()
        val output = Files.createTempFile("npv10-color-output", ".mov").toFile()
        Npv10FileWriter(
            outputFile = input,
            config = Npv10SessionConfig(
                width = 2,
                height = 2,
                fps = 24,
                mode = mode,
                colorProfile = colorProfile,
                selectedColorSpace = selectedColorSpace,
                selectedColorScience = selectedColorScience,
                colorSelectionSource = ManualVideoColorSelectionPolicy.USER_MANUAL,
                featureMetadata = featureMetadata,
                sourceFormat = "YCBCR_P010",
                sourceBitDepth = 10,
                payloadFormat = Npv10PayloadFormat.P010_LE,
                startedAtEpochMs = 1L
            )
        ).use { writer ->
            writer.writeFrame(
                RawYuvFrame(
                    width = 2,
                    height = 2,
                    yBytes = tenBitPlane(128, 128, 128, 128),
                    uBytes = tenBitPlane(512),
                    vBytes = tenBitPlane(512),
                    yRowStride = 4,
                    yPixelStride = 2,
                    uRowStride = 2,
                    vRowStride = 2,
                    uPixelStride = 2,
                    vPixelStride = 2,
                    timestamp = 0L,
                    bitDepth = 10
                )
            )
        }
        return Npv10MovConverter.convertWithAudit(input, output)
    }

    private fun writeManualNpv10(
        output: java.io.File,
        metadata: String,
        payload: ByteArray,
        timestampNs: Long = 1_000L
    ) {
        DataOutputStream(FileOutputStream(output)).use { stream ->
            stream.write(Npv10ContainerPolicy.MAGIC_BYTES)
            stream.writeInt(Npv10ContainerPolicy.VERSION)
            stream.writeInt(2)
            stream.writeInt(2)
            stream.writeInt(24)
            stream.writeInt(10)
            val metadataBytes = metadata.toByteArray(Charsets.UTF_8)
            stream.writeInt(metadataBytes.size)
            stream.write(metadataBytes)
            stream.write(Npv10ContainerPolicy.FRAME_MAGIC_BYTES)
            stream.writeLong(timestampNs)
            stream.writeInt(2)
            stream.writeInt(2)
            stream.writeInt(payload.size)
            stream.writeInt(crc32(payload))
            stream.write(payload)
        }
    }

    private fun planarTenBitPayload(): ByteArray {
        val payload = ByteArray(Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(2, 2).toInt())
        listOf(16, 32, 64, 128, 512, 512).forEachIndexed { index, sample ->
            val offset = index * 2
            payload[offset] = (sample and 0xFF).toByte()
            payload[offset + 1] = ((sample ushr 8) and 0x03).toByte()
        }
        return payload
    }

    private fun crc32(payload: ByteArray): Int {
        val crc = CRC32()
        crc.update(payload)
        return crc.value.toInt()
    }

    private fun readSignedInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
