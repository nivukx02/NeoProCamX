package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics
import com.neoproc.neoproccamx.pipeline.VideoDetailSharpeningPolicy
import com.neoproc.neoproccamx.quality.OmegaCapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RecordingQualityEvidencePolicyTest {

    @Test
    fun `strict pass requires matching app probe log and parity evidence`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                recordingId = "clip-ndr10-pass",
                recordedFileName = "clip-ndr10-pass.mov",
                recordedFileBytes = 42_000_000L
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.PASS, certificate.status)
        assertTrue(certificate.failures.isEmpty())
        assertEquals("V15_STRICT_QUALITY_EVIDENCE_GATE", certificate.metadata.getValue("qualityEvidenceGeneration"))
        assertEquals("true", certificate.metadata.getValue("v10EvidenceCalibratedGateApplied"))
        assertEquals("true", certificate.metadata.getValue("v15QualityEvidenceGateApplied"))
        assertEquals("true", certificate.metadata.getValue("strict10QualityProven"))
        assertEquals("true", certificate.metadata.getValue("strict10DeviceVerified"))
        assertEquals("PASS", certificate.metadata.getValue("qualityEvidenceStatus"))
        assertEquals("false", certificate.metadata.getValue("officialHdr10PlusMetadataWritten"))
        assertEquals("false", certificate.metadata.getValue("vendorHdrCertification"))
        assertEquals("false", certificate.metadata.getValue("vendorCertifiedHdr10Plus"))
        assertEquals("false", certificate.metadata.getValue("vendorCertifiedDolbyVision"))
        assertEquals("false", certificate.metadata.getValue("dolbyRpuWritten"))
        assertEquals("false", certificate.metadata.getValue("externalHdrStandardSubstitution"))
        assertEquals("true", certificate.metadata.getValue("proprietaryProfilePreserved"))
        assertEquals("true", certificate.metadata.getValue("androidHdrTransportOnly"))
        assertEquals("true", certificate.metadata.getValue("neoHdrNoVendorDependency"))
        assertEquals("false", certificate.metadata.getValue("nLogVendorDependency"))
        assertTrue(certificate.toCertificateText().contains("qualityEvidenceStatus=PASS"))
        assertTrue(certificate.toCertificateText().contains("recordedFileBytes=42000000"))
    }

    @Test
    fun `tier omega metrics are required before strict quality can pass`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(detailMetadata = mapOf("tierOmegaLumaSnrBaseDb" to "51.000000"))
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any {
            it.code == RecordingQualityGateCode.TIER_OMEGA_METRICS_NOT_PROVEN
        })
        assertEquals("false", certificate.metadata.getValue("tierOmegaQualityProven"))
        assertEquals("false", certificate.metadata.getValue("strict10QualityProven"))
    }

    @Test
    fun `tier omega metric threshold failures block elite certificate`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                detailMetadata = tierOmegaPassingMetadata() + mapOf(
                    "tierOmegaLumaSnrBaseDb" to "49.000000",
                    "tierOmegaChromaNoiseBaseStd" to "0.410000",
                    "tierOmegaTemporalFlickerPercent" to "0.900000",
                    "tierOmegaMtf50CyPerPx" to "0.330000",
                    "tierOmegaDetailRetentionRaw" to "0.920000",
                    "tierOmegaDeltaE2000Mean" to "1.400000"
                )
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any {
            it.code == RecordingQualityGateCode.TIER_OMEGA_METRIC_THRESHOLD_FAILED
        })
        assertTrue(certificate.toCertificateText().contains("tierOmegaLumaSnrBaseDb=49.000000"))
        assertEquals("false", certificate.metadata.getValue("tierOmegaQualityProven"))
    }

    @Test
    fun `imax passing metrics alone do not prove beyond imax certificate`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(detailMetadata = tierOmegaImaxOnlyPassingMetadata())
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any {
            it.code == RecordingQualityGateCode.TIER_OMEGA_METRIC_THRESHOLD_FAILED
        })
        assertEquals("BEYOND_IMAX_ELITE_10_10", certificate.metadata.getValue("beyondImaxQualityProfile"))
        assertEquals("false", certificate.metadata.getValue("beyondImaxQualityProven"))
        assertTrue(certificate.toCertificateText().contains("base luma SNR 51.000000 is below 54.000000"))
    }

    @Test
    fun `tier omega bitrate floors are blocking evidence gates`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                bitrateBps = 330_000_000,
                probe = hdrProbe().copy(bitrateBps = 330_000_000)
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.BITRATE_FLOOR_NOT_MET })
        assertEquals("420000000", certificate.metadata.getValue("tierOmegaMinimumBitrateBps"))
        assertEquals("420000000", certificate.metadata.getValue("beyondImaxMinimumBitrateBps"))
    }

    @Test
    fun `strict fourk sixty sdr evidence floor is capped to hevc request ceiling`() {
        val hevcCeiling = RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validSdrRequest().copy(
                width = 3840,
                height = 2160,
                fps = 60,
                selectedResolution = "3840x2160",
                actualResolution = "3840x2160",
                expectedFrameCount = 120,
                actualFrameCount = 120,
                bitrateBps = hevcCeiling,
                appliedBitrateBps = hevcCeiling,
                performanceTelemetryValidated = true,
                cpuUsagePercent = 6.8f,
                maxFrameTimeMs = 15.2f,
                probe = RecordingProbeMetadata(
                    codec = "hevc",
                    pixelFormat = "yuv420p10le",
                    encodedBitDepth = 10,
                    primaries = "BT.709",
                    transfer = "BT.709/OETF",
                    matrix = "BT.709",
                    colorRange = "LIMITED",
                    width = 3840,
                    height = 2160,
                    fps = 60,
                    bitrateBps = hevcCeiling
                )
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.PASS, certificate.status)
        assertTrue(certificate.failures.none { it.code == RecordingQualityGateCode.BITRATE_FLOOR_NOT_MET })
        assertEquals(hevcCeiling.toString(), certificate.metadata.getValue("tierOmegaMinimumBitrateBps"))
        assertEquals("HEVC_MAIN10_SDR", certificate.metadata.getValue("activeTransport"))
        assertEquals("BT.709/OETF", certificate.metadata.getValue("transfer"))
        assertEquals("10", certificate.metadata.getValue("encodedBitDepth"))
    }

    @Test
    fun `normal pro sdr fails when recording enhancement does not prove strict realtime lock`() {
        val weakSdr = validSdrRequest().copy(
            enhancementUniformSignature = "gpu-v1|signal=SDR|space=BT.709|science=Normal SDR|packet=weak",
            previewEnhancementUniformSignature = "gpu-v1|signal=SDR|space=BT.709|science=Normal SDR|packet=weak",
            recordingEnhancementUniformSignature = "gpu-v1|signal=SDR|space=BT.709|science=Normal SDR|packet=weak",
            enhancementTemporalBlend = 0.42f,
            enhancementSpatialDenoise = 0.73f,
            enhancementChromaDenoise = 0.96f,
            enhancementSharpening = 0.31f
        )

        val certificate = RecordingQualityEvidencePolicy.evaluate(weakSdr)

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any {
            it.code == RecordingQualityGateCode.NORMAL_PRO_SDR_ENHANCEMENT_LOCK_NOT_PROVEN
        })
        assertEquals("true", certificate.metadata.getValue("normalProSdrEnhancementLockRequired"))
        assertEquals("false", certificate.metadata.getValue("normalProSdrEnhancementLockProven"))
    }

    @Test
    fun `normal and pro sdr accept matching finite adaptive packet evidence`() {
        listOf(
            EliteVideoPipelineMode.NORMAL to "Normal SDR",
            EliteVideoPipelineMode.PRO to "Pro SDR"
        ).forEach { (mode, profile) ->
            val certificate = RecordingQualityEvidencePolicy.evaluate(
                validSdrRequest().copy(
                    mode = mode,
                    selectedProfile = profile
                ).withAdaptiveNormalProSdrEnhancement()
            )

            assertEquals("$mode adaptive evidence", RecordingQualityEvidenceStatus.PASS, certificate.status)
            assertEquals("true", certificate.metadata.getValue("normalProSdrEnhancementLockProven"))
        }
    }

    @Test
    fun `normal and pro sdr reject wrong intent and invalid adaptive packet evidence`() {
        val wrongIntent = RecordingQualityEvidencePolicy.evaluate(
            validSdrRequest().copy(mode = EliteVideoPipelineMode.PRO, selectedProfile = "Pro SDR")
                .withAdaptiveNormalProSdrEnhancement(intent = "NORMAL")
        )
        val nonFinitePacket = RecordingQualityEvidencePolicy.evaluate(
            validSdrRequest().withAdaptiveNormalProSdrEnhancement(sharpeningPacketValue = "NaN")
        )
        val legacySentinelPacket = RecordingQualityEvidencePolicy.evaluate(
            validSdrRequest().withAdaptiveNormalProSdrEnhancement(sharpeningPacketValue = "10.0000")
        )

        listOf(wrongIntent, nonFinitePacket, legacySentinelPacket).forEach { certificate ->
            assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
            assertTrue(certificate.failures.any {
                it.code == RecordingQualityGateCode.NORMAL_PRO_SDR_ENHANCEMENT_LOCK_NOT_PROVEN
            })
        }
    }

    @Test
    fun `manual bitrate cap below quality floor is explicit evidence`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                width = 3840,
                height = 2160,
                selectedResolution = "3840x2160",
                actualResolution = "3840x2160",
                bitrateBps = 160_000_000,
                requestedBitrateBps = 1_200_000_000,
                appliedBitrateBps = 160_000_000,
                encoderCapabilityUpperBoundBps = 160_000_000,
                manualBitrateSelectionEnabled = true,
                probe = hdrProbe().copy(
                    width = 3840,
                    height = 2160,
                    bitrateBps = 160_000_000
                )
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.BITRATE_FLOOR_NOT_MET })
        assertTrue(certificate.failures.any {
            it.code == RecordingQualityGateCode.MANUAL_BITRATE_CAPPED_BELOW_QUALITY_FLOOR
        })
        assertEquals("1200000000", certificate.metadata.getValue("requestedBitrateBps"))
        assertEquals("160000000", certificate.metadata.getValue("appliedBitrateBps"))
        assertEquals("160000000", certificate.metadata.getValue("encoderCapabilityUpperBoundBps"))
        assertEquals("true", certificate.metadata.getValue("manualBitrateSelectionEnabled"))
        assertEquals("true", certificate.metadata.getValue("manualBitrateCapped"))
        assertEquals("true", certificate.metadata.getValue("manualBitrateCappedBelowQualityFloor"))
    }

    @Test
    fun `configured target without measured or validated probe bitrate is not proven`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                bitrateBps = 460_000_000,
                requestedBitrateBps = 600_000_000,
                policyTargetBitrateBps = 500_000_000,
                appliedBitrateBps = 460_000_000,
                measuredOutputBitrateBps = 0,
                probe = null,
                ffprobeValidated = false,
                mediaInfoValidated = false
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.NOT_PROVEN, certificate.status)
        assertTrue(certificate.failures.any {
            it.code == RecordingQualityGateCode.BITRATE_MEASUREMENT_NOT_PROVEN
        })
        assertEquals("600000000", certificate.metadata.getValue("requestedBitrateBps"))
        assertEquals("500000000", certificate.metadata.getValue("policyTargetBitrateBps"))
        assertEquals("460000000", certificate.metadata.getValue("appliedBitrateBps"))
        assertEquals("0", certificate.metadata.getValue("measuredOutputBitrateBps"))
        assertEquals("false", certificate.metadata.getValue("bitrateMeasurementProven"))
    }

    @Test
    fun `measured output below floor fails even when configured target meets floor`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                requestedBitrateBps = 600_000_000,
                policyTargetBitrateBps = 500_000_000,
                appliedBitrateBps = 460_000_000,
                measuredOutputBitrateBps = 330_000_000,
                probe = null,
                ffprobeValidated = false,
                mediaInfoValidated = false
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any {
            it.code == RecordingQualityGateCode.BITRATE_FLOOR_NOT_MET
        })
        assertEquals("330000000", certificate.metadata.getValue("measuredOutputBitrateBps"))
        assertEquals("ENCODER_OUTPUT_METER", certificate.metadata.getValue("bitrateMeasurementSource"))
    }

    @Test
    fun `normal and log fail when probe metadata is hdr tagged`() {
        val normal = RecordingQualityEvidencePolicy.evaluate(validSdrRequest().copy(probe = hdrProbe()))
        val log = RecordingQualityEvidencePolicy.evaluate(validLogRequest().copy(probe = hdrProbe()))

        assertEquals(RecordingQualityEvidenceStatus.FAIL, normal.status)
        assertEquals(RecordingQualityEvidenceStatus.FAIL, log.status)
        assertTrue(normal.failures.any { it.code == RecordingQualityGateCode.NORMAL_OR_PRO_SAVED_AS_HDR })
        assertTrue(log.failures.any { it.code == RecordingQualityGateCode.LOG_SAVED_AS_HDR })
    }

    @Test
    fun `manual rec2020 sdr rejects bt709 physical metadata`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validSdrRequest().copy(
                primaries = "BT.2020",
                matrix = "BT.2020-NCL",
                probe = validSdrRequest().probe?.copy(
                    primaries = "BT.709",
                    transfer = "BT.709/OETF",
                    matrix = "BT.709"
                )
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any {
            it.code == RecordingQualityGateCode.COLOR_PRIMARIES_MISMATCH
        })
        assertTrue(certificate.failures.any {
            it.code == RecordingQualityGateCode.MATRIX_MISMATCH
        })
    }

    @Test
    fun `normal and pro evidence must prove ten bit sdr transport`() {
        listOf(
            EliteVideoPipelineMode.NORMAL to "Normal SDR",
            EliteVideoPipelineMode.PRO to "Pro SDR"
        ).forEach { (mode, profile) ->
            val certificate = RecordingQualityEvidencePolicy.evaluate(
                validSdrRequest().copy(
                    mode = mode,
                    selectedProfile = profile,
                    encodedBitDepth = 8,
                    sourceBitDepth = 8,
                    storageBitDepth = 8
                )
            )

            assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
            assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.BIT_DEPTH_MISMATCH })
        }
    }

    @Test
    fun `hdr transport accepts main twelve when all bit depth evidence is twelve bit`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                activeTransport = "BT.2100_PQ_MAIN12",
                encodedBitDepth = 12,
                internalMathBitDepth = 12,
                sourceBitDepth = 12,
                storageBitDepth = 12,
                probe = hdrProbe().copy(encodedBitDepth = 12)
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.PASS, certificate.status)
    }

    @Test
    fun `ndr and nivukx reject generic pq or hlg unless bt2100 pq is proven`() {
        val genericPq = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(activeTransport = "ANDROID_PQ_BT2020_MAIN10")
        )
        val hlg = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                activeTransport = "BT.2100_HLG_MAIN10",
                transfer = "HLG",
                probe = hdrProbe().copy(transfer = "HLG")
            )
        )
        val nivukxBt2100 = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                selectedProfile = "NIVUKX Vision",
                activeTransport = "BT.2100_PQ_MAIN10",
                normalizedHdrVersion = "NIVUKXVISION",
                captureTruthSignature = "HDR_PQ|HDR_10BIT|BT.2100_PQ|hdr=NIVUKX|tenBit=true",
                previewQualitySignature = "HDR|VULKAN_PREVIEW_RECORD_EXPORT|BT.2100_PQ|NIVUKX",
                recordingQualitySignature = "HDR|VULKAN_PREVIEW_RECORD_EXPORT|BT.2100_PQ|NIVUKX"
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, genericPq.status)
        assertTrue(genericPq.failures.any { it.code == RecordingQualityGateCode.HDR_TRANSPORT_NOT_10BIT_BT2020 })
        assertEquals(RecordingQualityEvidenceStatus.FAIL, hlg.status)
        assertTrue(hlg.failures.any { it.code == RecordingQualityGateCode.HDR_TRANSPORT_NOT_10BIT_BT2020 })
        assertEquals(RecordingQualityEvidenceStatus.PASS, nivukxBt2100.status)
    }

    @Test
    fun `metadata match without ffprobe and device logs remains not proven`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(probe = null, ffprobeValidated = false, mediaInfoValidated = false, deviceLogValidated = false)
        )

        assertEquals(RecordingQualityEvidenceStatus.NOT_PROVEN, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.MISSING_RECORDED_FILE_PROOF })
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.MISSING_DEVICE_LOG_PROOF })
        assertEquals("false", certificate.metadata.getValue("strict10DeviceVerified"))
    }

    @Test
    fun `finalized mediastore output proves recorded file exists without ffprobe`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                probe = null,
                ffprobeValidated = false,
                mediaInfoValidated = false,
                deviceLogValidated = false,
                recordedFileName = "clip-mediastore.mov",
                recordedFileBytes = 81_000_000L,
                mediaStoreValidated = true
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.NOT_PROVEN, certificate.status)
        assertTrue(certificate.failures.none { it.code == RecordingQualityGateCode.MISSING_RECORDED_FILE_PROOF })
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.MISSING_DEVICE_LOG_PROOF })
        assertEquals("true", certificate.metadata.getValue("mediaStoreValidated"))
        assertEquals("81000000", certificate.metadata.getValue("recordedFileBytes"))
    }

    @Test
    fun `recorded file must be non empty before strict quality can pass`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                recordingId = "clip-empty",
                recordedFileName = "clip-empty.mov",
                recordedFileBytes = 0L
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.RECORDED_FILE_EMPTY })
        assertEquals("false", certificate.metadata.getValue("strict10QualityProven"))
    }

    @Test
    fun `adb fatal or codec errors fail the strict quality gate`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                recordingId = "clip-adb-error",
                recordedFileName = "clip-adb-error.mov",
                recordedFileBytes = 38_000_000L,
                adbFatalErrorCount = 1,
                adbCodecErrorCount = 1
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.ADB_RUNTIME_ERROR })
        assertEquals("false", certificate.metadata.getValue("strict10QualityProven"))
    }

    @Test
    fun `npv10 fallback cannot be labeled native ten bit`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNpv10Request().copy(
                npv10PayloadFormat = "YUV420_8BIT_EXPANDED",
                npv10NativeTenBitClaimed = true,
                sourceBitDepth = 8,
                recordedFileBytes = 24_000_000L
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.NPV10_FALLBACK_MISLABELED_NATIVE_10BIT })
        assertEquals("false", certificate.metadata.getValue("strict10DeviceVerified"))
    }

    @Test
    fun `npv10 ndr and nivukx private masters require bt2100 pq evidence`() {
        val missingBt2100 = RecordingQualityEvidencePolicy.evaluate(
            validNpv10Request().copy(
                selectedProfile = "NDR10+",
                normalizedHdrVersion = "NDR10+",
                activeTransport = "NPV10_PRIVATE_MASTER",
                transfer = "N-Log",
                matrix = "YUV420",
                measuredOutputBitrateBps = 460_000_000
            )
        )
        val provenBt2100 = RecordingQualityEvidencePolicy.evaluate(
            validNpv10Request().copy(
                selectedProfile = "NDR10+",
                normalizedHdrVersion = "NDR10+",
                activeTransport = "NPV10_BT.2100_PQ_PRIVATE_MASTER",
                transfer = "ST2084/PQ",
                primaries = "BT.2020",
                matrix = "BT.2020-NCL",
                measuredOutputBitrateBps = 460_000_000
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, missingBt2100.status)
        assertTrue(missingBt2100.failures.any {
            it.code == RecordingQualityGateCode.HDR_TRANSPORT_NOT_10BIT_BT2020
        })
        assertEquals(RecordingQualityEvidenceStatus.PASS, provenBt2100.status)
    }

    @Test
    fun `preview and recording quality signatures must match`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(recordingQualitySignature = "HDR|DIFFERENT|LOW_QUALITY_RECORDING")
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.PREVIEW_RECORDING_PARITY_MISMATCH })
    }

    @Test
    fun `passing parity certificate proves endpoint equivalence when signatures differ by monitor metadata`() {
        val request = validNdrRequest()
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            request.copy(
                recordingQualitySignature = request.recordingQualitySignature + "|recording-output",
                detailMetadata = request.detailMetadata + mapOf(
                    "previewRecordingParityStatus" to "PASS",
                    "previewRecordingParityFailureCodes" to ""
                )
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.PASS, certificate.status)
        assertTrue(certificate.failures.none { it.code == RecordingQualityGateCode.PREVIEW_RECORDING_PARITY_MISMATCH })
    }

    @Test
    fun `non blocking parity certificate keeps monitor grade drift from failing recording quality`() {
        val request = validNdrRequest()
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            request.copy(
                recordingQualitySignature = request.recordingQualitySignature + "|recording-grade",
                detailMetadata = request.detailMetadata + mapOf(
                    "previewRecordingParityStatus" to "FAIL",
                    "previewRecordingParityFailureCodes" to
                        "PREVIEW_RECORDING_GRADE_IDENTITY_MISMATCH,CAMERA2_FRAME_LINEAGE_ENDPOINT_OMISSION",
                    "previewRecordingParityBlocked" to "false",
                    "parityCertificateSignature" to "PARITY_CERT|FAIL|blocked=false"
                )
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.PASS, certificate.status)
        assertTrue(certificate.failures.none { it.code == RecordingQualityGateCode.PREVIEW_RECORDING_PARITY_MISMATCH })
        assertEquals("false", certificate.metadata.getValue("previewRecordingParityBlocked"))
    }

    @Test
    fun `recording evidence fails wrong rotation mirrored output and missing chroma planes`() {
        val wrongRotation = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                expectedRotationDegrees = 0,
                probe = hdrProbe().copy(rotationDegrees = 90)
            )
        )
        val mirrored = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                expectedMirrored = false,
                probe = hdrProbe().copy(mirrored = true)
            )
        )
        val gray = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                probe = hdrProbe().copy(pixelFormat = "gray10le")
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, wrongRotation.status)
        assertTrue(wrongRotation.failures.any { it.code == RecordingQualityGateCode.ORIENTATION_MISMATCH })
        assertEquals("0", wrongRotation.metadata.getValue("expectedRotationDegrees"))
        assertEquals("90", wrongRotation.metadata.getValue("probeRotationDegrees"))

        assertEquals(RecordingQualityEvidenceStatus.FAIL, mirrored.status)
        assertTrue(mirrored.failures.any { it.code == RecordingQualityGateCode.MIRRORED_OUTPUT_DETECTED })
        assertEquals("true", mirrored.metadata.getValue("probeMirrored"))

        assertEquals(RecordingQualityEvidenceStatus.FAIL, gray.status)
        assertTrue(gray.failures.any { it.code == RecordingQualityGateCode.CHROMA_CHANNELS_MISSING })
        assertEquals("gray10le", gray.metadata.getValue("probePixelFormat"))
    }

    @Test
    fun `matching signatures cannot prove processed quality when frames use isp fallback`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                gpuProcessingPath = "CAMERA2_ISP_ENCODED_FALLBACK",
                processedRecordingFramesValidated = false
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any {
            it.code == RecordingQualityGateCode.PROCESSED_RECORDING_NOT_PROVEN
        })
    }

    @Test
    fun `hdr preview parity path can prove realtime hdr delivery without claiming gpu`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                gpuProcessingValidated = false,
                gpuProcessingPath = "CAMERA2_PREVIEW_PARITY_RECORD_SURFACE",
                processedRecordingFramesValidated = true
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.PASS, certificate.status)
        assertEquals("CAMERA2_PREVIEW_PARITY_RECORD_SURFACE", certificate.metadata.getValue("gpuProcessingPath"))
        assertEquals("false", certificate.metadata.getValue("gpuProcessingValidated"))
        assertEquals("true", certificate.metadata.getValue("hdrPreviewParityDeliveryPath"))
    }

    @Test
    fun `sdr preview parity path can prove realtime delivery without claiming gpu`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validSdrRequest().copy(
                gpuProcessingValidated = false,
                gpuProcessingPath = "CAMERA2_PREVIEW_PARITY_RECORD_SURFACE",
                processedRecordingFramesValidated = false
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.PASS, certificate.status)
        assertEquals("CAMERA2_PREVIEW_PARITY_RECORD_SURFACE", certificate.metadata.getValue("gpuProcessingPath"))
        assertEquals("true", certificate.metadata.getValue("stablePreviewParityDeliveryPath"))
    }

    @Test
    fun `log preview parity path can prove realtime delivery without claiming gpu`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validLogRequest().copy(
                gpuProcessingValidated = false,
                gpuProcessingPath = "CAMERA2_PREVIEW_PARITY_RECORD_SURFACE",
                processedRecordingFramesValidated = false
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.PASS, certificate.status)
        assertEquals("CAMERA2_PREVIEW_PARITY_RECORD_SURFACE", certificate.metadata.getValue("gpuProcessingPath"))
        assertEquals("true", certificate.metadata.getValue("stablePreviewParityDeliveryPath"))
    }

    @Test
    fun `hdr preview parity path still fails if all gpu processing is claimed without proof`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                allGpuProcessingClaimed = true,
                gpuProcessingValidated = false,
                gpuProcessingPath = "CAMERA2_PREVIEW_PARITY_RECORD_SURFACE",
                processedRecordingFramesValidated = true
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any {
            it.code == RecordingQualityGateCode.GPU_PROCESSING_NOT_PROVEN
        })
        assertEquals("true", certificate.metadata.getValue("hdrPreviewParityDeliveryPath"))
    }

    @Test
    fun `processed recording must prove enhancement uniform contract`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                enhancementUniformSignature = "",
                previewEnhancementUniformSignature = "",
                recordingEnhancementUniformSignature = "",
                enhancementTemporalBlend = 0f,
                enhancementSpatialDenoise = 0f,
                enhancementChromaDenoise = 0f,
                enhancementSharpening = 0f,
                enhancementSubjectDetailGain = 0f
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any {
            it.code == RecordingQualityGateCode.RECORDING_ENHANCEMENT_NOT_PROVEN
        })
        assertEquals("false", certificate.metadata.getValue("recordingEnhancementUniformValidated"))
    }

    @Test
    fun `identity enhancement uniform blocks processed quality certificate`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                enhancementUniformSignature = "gpu-v1|signal=HDR|space=REC2020|packet=identity",
                enhancementTemporalBlend = 0f,
                enhancementSpatialDenoise = 0f,
                enhancementChromaDenoise = 0f,
                enhancementSharpening = 0f,
                enhancementSubjectDetailGain = 0f
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any {
            it.code == RecordingQualityGateCode.RECORDING_ENHANCEMENT_IDENTITY
        })
        assertEquals("true", certificate.metadata.getValue("recordingEnhancementIdentity"))
    }

    @Test
    fun `record develop light master allows flat live uniforms because offline develop owns quality`() {
        val flatSignature = "gpu-v1|recordDevelopLightMaster=true|packet=flat-offline-develop"
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validSdrRequest().copy(
                enhancementUniformSignature = flatSignature,
                previewEnhancementUniformSignature = flatSignature,
                recordingEnhancementUniformSignature = flatSignature,
                enhancementTemporalBlend = 0f,
                enhancementSpatialDenoise = 0f,
                enhancementChromaDenoise = 0f,
                enhancementSharpening = 0f,
                enhancementSubjectDetailGain = 0f,
                detailMetadata = validSdrRequest().detailMetadata + mapOf(
                    "recordDevelopLightMaster" to "true",
                    "recordDevelopOfflineDevelopRequired" to "true",
                    "recordDevelopLiveColorProcessing" to "FLAT_10BIT_MASTER_ONLY",
                    "recordDevelopOfflineOutputFormat" to "R210_444",
                    "recordDevelopOfflineEnhanceProfile" to "ELITE_SNCE_TEMPORAL_DENOISE_DETAIL"
                )
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.PASS, certificate.status)
        assertTrue(certificate.failures.none {
            it.code == RecordingQualityGateCode.RECORDING_ENHANCEMENT_IDENTITY
        })
        assertTrue(certificate.failures.none {
            it.code == RecordingQualityGateCode.NORMAL_PRO_SDR_ENHANCEMENT_LOCK_NOT_PROVEN
        })
        assertEquals("true", certificate.metadata.getValue("recordingEnhancementIdentity"))
        assertEquals("false", certificate.metadata.getValue("normalProSdrEnhancementLockRequired"))
        assertEquals("R210_444", certificate.metadata.getValue("recordDevelopOfflineOutputFormat"))
    }

    @Test
    fun `preview and recording enhancement signatures must match`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                previewEnhancementUniformSignature = "gpu-v1|signal=HDR|packet=f5=0.7300",
                recordingEnhancementUniformSignature = "gpu-v1|signal=HDR|packet=f5=0.0000",
                enhancementUniformSignature = "gpu-v1|signal=HDR|packet=f5=0.0000"
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any {
            it.code == RecordingQualityGateCode.PREVIEW_RECORDING_ENHANCEMENT_MISMATCH
        })
        assertEquals("false", certificate.metadata.getValue("previewRecordingEnhancementMatch"))
        assertEquals(
            "gpu-v1|signal=HDR|packet=f5=0.7300",
            certificate.metadata.getValue("previewEnhancementUniformSignature")
        )
        assertEquals(
            "gpu-v1|signal=HDR|packet=f5=0.0000",
            certificate.metadata.getValue("recordingEnhancementUniformSignature")
        )
    }

    @Test
    fun `certificate distinguishes masked preview detail from camera2 fallback recording`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                processedRecordingFramesValidated = false,
                detailMetadata = VideoDetailSharpeningPolicy.recordingEvidenceMetadata(
                    previewMetadata = mapOf(
                        "selectedDetailProfile" to "FOCUSED_DETAIL",
                        "subjectDetailApplied" to "true",
                        "subjectDetailGain" to "0.410000"
                    ),
                    recordingGpuFrameImportValidated = false
                )
            )
        )

        assertEquals("FOCUSED_DETAIL", certificate.metadata.getValue("selectedDetailProfile"))
        assertEquals("true", certificate.metadata.getValue("previewSubjectDetailApplied"))
        assertEquals("false", certificate.metadata.getValue("recordingSubjectDetailApplied"))
        assertEquals("CAMERA2_FALLBACK", certificate.metadata.getValue("detailProcessingPath"))
    }

    @Test
    fun `certificate exposes high end noise detail sensor encoder evidence ids`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(validLogRequest())
        val metadata = certificate.metadata

        assertEquals("MOTION_AWARE_TEMPORAL_LUMA_CHROMA_V6", metadata.getValue("noiseModelV6"))
        assertEquals("SHADOW_CHROMA_LOCK_SKIN_TEXTURE_GUARD_V6", metadata.getValue("chromaNoiseModelV6"))
        assertEquals("EDGE_CONFIDENCE_ANTI_HALO_DETAIL_V6", metadata.getValue("detailModelV6"))
        assertEquals("GENERIC_SENSOR_PROFILE_WITH_DEVICE_OVERRIDE_V6", metadata.getValue("sensorCalibrationModelV6"))
        assertEquals("SCENE_COMPLEXITY_SUSTAINED_BITRATE_GUARD_V6", metadata.getValue("encoderQualityModelV6"))
        assertEquals("false", metadata.getValue("nativeOutputResolutionFaked"))
    }

    @Test
    fun `certificate exposes measured luma chroma temporal and rgb variance noise metrics`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                noiseMetrics = VideoNoiseMetrics(
                    chromaNoise = 0.61f,
                    lumaNoise = 0.44f,
                    temporalInstability = 0.35f,
                    frameToFrameRgbVariance = 0.72f
                )
            )
        )
        val metadata = certificate.metadata

        assertEquals("0.610000", metadata.getValue("chromaNoise"))
        assertEquals("0.440000", metadata.getValue("lumaNoise"))
        assertEquals("0.350000", metadata.getValue("temporalInstability"))
        assertEquals("0.720000", metadata.getValue("frameToFrameRgbVariance"))
        assertTrue(certificate.toCertificateText().contains("frameToFrameRgbVariance=0.720000"))
    }

    @Test
    fun `certificate exposes encoder capability scene bitrate and thermal evidence`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                encoderCapabilityCheck = "HEVC_MAIN10_SUPPORTED",
                encoderSceneComplexity = 0.73f,
                encoderEstimatedLux = 4.2f,
                encoderBitrateMultiplier = 1.82f,
                encoderSceneComplexityBoostApplied = true,
                encoderLowLightBoostApplied = true,
                encoderHdrOrLogBoostApplied = true,
                encoderThermalDowngradeActive = true
            )
        )
        val metadata = certificate.metadata

        assertEquals("HEVC_MAIN10_SUPPORTED", metadata.getValue("encoderCapabilityCheck"))
        assertEquals("0.730000", metadata.getValue("encoderSceneComplexity"))
        assertEquals("4.200000", metadata.getValue("encoderEstimatedLux"))
        assertEquals("1.820000", metadata.getValue("encoderBitrateMultiplier"))
        assertEquals("true", metadata.getValue("encoderSceneComplexityBoostApplied"))
        assertEquals("true", metadata.getValue("encoderLowLightBoostApplied"))
        assertEquals("true", metadata.getValue("encoderHdrOrLogBoostApplied"))
        assertEquals("true", metadata.getValue("encoderThermalDowngradeActive"))
    }

    @Test
    fun `all gpu and zero copy claims require runtime proof before strict pass`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                allGpuProcessingClaimed = true,
                zeroCopyProcessingClaimed = true,
                gpuProcessingValidated = false,
                zeroCopyValidated = false,
                gpuProcessingPath = "VULKAN_PREVIEW_RECORD_EXPORT",
                zeroCopyEvidencePath = ""
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.GPU_PROCESSING_NOT_PROVEN })
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.ZERO_COPY_CLAIM_NOT_PROVEN })
        assertEquals("false", certificate.metadata.getValue("strict10QualityProven"))
        assertEquals("VULKAN_PREVIEW_RECORD_EXPORT", certificate.metadata.getValue("gpuProcessingPath"))
    }

    @Test
    fun `omega certificate fails unsupported sovereign feature claims instead of faking support`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                omegaClaimedFeatures = setOf(
                    OmegaCapabilityId.EXTERNAL_CERTIFIED_DYNAMIC_METADATA,
                    OmegaCapabilityId.EXTERNAL_LICENSED_RAW_CODEC,
                    OmegaCapabilityId.UNPROVEN_EXTREME_BITRATE
                )
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.OMEGA_UNSUPPORTED_FEATURE_CLAIMED })
        assertEquals("false", certificate.metadata.getValue("omegaTruthful"))
        assertTrue(certificate.metadata.getValue("omegaBlockingFailures").contains("EXTERNAL_CERTIFIED_DYNAMIC_METADATA"))
        assertTrue(certificate.metadata.getValue("omegaUnavailableFeatures").contains("EXTERNAL_LICENSED_RAW_CODEC"))
        assertTrue(certificate.toCertificateText().contains("omegaSovereignClaimed=true"))
    }

    @Test
    fun `four k sixty strict claim requires real cpu and frame timing proof`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                width = 3840,
                height = 2160,
                fps = 60,
                selectedResolution = "3840x2160",
                actualResolution = "3840x2160",
                expectedFrameCount = 120,
                actualFrameCount = 120,
                probe = fourKSixtyHdrProbe(),
                performanceTelemetryValidated = true,
                cpuUsagePercent = 16.2f,
                maxFrameTimeMs = 22.5f,
                targetCpuUsagePercent = 10f
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.CPU_BUDGET_EXCEEDED })
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.FRAME_TIME_BUDGET_EXCEEDED })
        assertEquals("16.200001", certificate.metadata.getValue("cpuUsagePercent"))
        assertEquals("22.500000", certificate.metadata.getValue("maxFrameTimeMs"))
        assertEquals("10.000000", certificate.metadata.getValue("targetCpuUsagePercent"))
        assertEquals("16.666666", certificate.metadata.getValue("targetFrameTimeMs"))
    }

    @Test
    fun `strict high end four k twenty five also requires realtime proof`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                width = 3840,
                height = 2160,
                fps = 25,
                selectedResolution = "3840x2160",
                actualResolution = "3840x2160",
                expectedFrameCount = 50,
                actualFrameCount = 50,
                probe = hdrProbe().copy(width = 3840, height = 2160, fps = 25),
                performanceTelemetryValidated = false,
                cpuUsagePercent = null,
                maxFrameTimeMs = null
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any {
            it.code == RecordingQualityGateCode.REALTIME_PERFORMANCE_NOT_PROVEN
        })
        assertEquals("40.000000", certificate.metadata.getValue("targetFrameTimeMs"))
        assertTrue(certificate.toCertificateText().contains("strict high-end 4K claims require real-device"))
    }

    @Test
    fun `strict high end four k twenty five accepts proven zero drop device cpu budget`() {
        val qualityFloorBitrate = RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                width = 3840,
                height = 2160,
                fps = 25,
                bitrateBps = qualityFloorBitrate,
                requestedBitrateBps = qualityFloorBitrate,
                policyTargetBitrateBps = qualityFloorBitrate,
                appliedBitrateBps = qualityFloorBitrate,
                selectedResolution = "3840x2160",
                actualResolution = "3840x2160",
                expectedFrameCount = 528,
                actualFrameCount = 528,
                probe = hdrProbe().copy(
                    width = 3840,
                    height = 2160,
                    fps = 25,
                    bitrateBps = qualityFloorBitrate
                ),
                performanceTelemetryValidated = true,
                cpuUsagePercent = 114.306557f,
                maxFrameTimeMs = 40.0f
            )
        )

        assertEquals(
            certificate.toCertificateText(),
            RecordingQualityEvidenceStatus.PASS,
            certificate.status
        )
        assertTrue(certificate.failures.none { it.code == RecordingQualityGateCode.CPU_BUDGET_EXCEEDED })
        assertTrue(certificate.failures.none { it.code == RecordingQualityGateCode.FRAME_TIME_BUDGET_EXCEEDED })
        assertEquals("114.306557", certificate.metadata.getValue("cpuUsagePercent"))
        assertEquals("200.000000", certificate.metadata.getValue("targetCpuUsagePercent"))
        assertEquals("40.000000", certificate.metadata.getValue("targetFrameTimeMs"))
    }

    @Test
    fun `strict high end four k twenty five still rejects overloaded cpu evidence`() {
        val qualityFloorBitrate = RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(
                width = 3840,
                height = 2160,
                fps = 25,
                bitrateBps = qualityFloorBitrate,
                requestedBitrateBps = qualityFloorBitrate,
                policyTargetBitrateBps = qualityFloorBitrate,
                appliedBitrateBps = qualityFloorBitrate,
                selectedResolution = "3840x2160",
                actualResolution = "3840x2160",
                expectedFrameCount = 528,
                actualFrameCount = 528,
                probe = hdrProbe().copy(
                    width = 3840,
                    height = 2160,
                    fps = 25,
                    bitrateBps = qualityFloorBitrate
                ),
                performanceTelemetryValidated = true,
                cpuUsagePercent = 225.0f,
                maxFrameTimeMs = 39.8f
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.CPU_BUDGET_EXCEEDED })
        assertTrue(certificate.failures.none { it.code == RecordingQualityGateCode.FRAME_TIME_BUDGET_EXCEEDED })
        assertEquals("225.000000", certificate.metadata.getValue("cpuUsagePercent"))
        assertEquals("200.000000", certificate.metadata.getValue("targetCpuUsagePercent"))
    }

    @Test
    fun `per recording certificate sidecar contains pass fail gates and truth metadata`() {
        val root = Files.createTempDirectory("neoproc-quality-cert").toFile()
        try {
            val certificate = RecordingQualityEvidencePolicy.evaluate(
                validLogRequest().copy(
                    recordingId = "clip-log-sidecar",
                    recordedFileName = "clip-log-sidecar.mov",
                    recordedFileBytes = 29_000_000L
                )
            )

            val sidecar = RecordingQualityCertificateStore.write(root, certificate)
            val text = sidecar.readText()

            assertTrue(sidecar.name.contains("clip-log-sidecar"))
            assertTrue(text.contains("qualityEvidenceGeneration=V15_STRICT_QUALITY_EVIDENCE_GATE"))
            assertTrue(text.contains("mode=LOG"))
            assertTrue(text.contains("codec=hevc"))
            assertTrue(text.contains("transfer=N-Log"))
            assertTrue(text.contains("primaries=BT.2020"))
            assertTrue(text.contains("matrix=BT.2020-NCL"))
            assertTrue(text.contains("qualityEvidenceStatus=PASS"))
            assertTrue(text.contains("failureCodes="))
            assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validSdrRequest(): RecordingQualityEvidenceRequest =
        baseRequest(
            mode = EliteVideoPipelineMode.NORMAL,
            selectedProfile = "Normal SDR",
            activeTransport = "HEVC_MAIN10_SDR",
            transfer = "BT.709/OETF",
            primaries = "BT.709",
            matrix = "BT.709",
            encodedBitDepth = 10,
            internalMathBitDepth = 10,
            sourceBitDepth = 10,
            storageBitDepth = 10,
            captureTruthSignature = "PRO_SDR|SDR|Rec.709|hdr=OFF|tenBit=true|log=false|raw=false|hdrMetadata=false",
            previewQualitySignature = "SDR|CAMERA2_PREVIEW_PARITY_RECORD_SURFACE|Rec.709|ENCODED_VIDEO_STRICT|NATURAL_DETAIL|USER_MANUAL_COLOR",
            recordingQualitySignature = "SDR|CAMERA2_PREVIEW_PARITY_RECORD_SURFACE|Rec.709|ENCODED_VIDEO_STRICT|NATURAL_DETAIL|USER_MANUAL_COLOR",
            probe = RecordingProbeMetadata(
                codec = "hevc",
                pixelFormat = "yuv420p10le",
                encodedBitDepth = 10,
                primaries = "BT.709",
                transfer = "BT.709/OETF",
                matrix = "BT.709",
                colorRange = "LIMITED",
                width = 1920,
                height = 1080,
                fps = 30,
                bitrateBps = 460_000_000
            )
        ).withAdaptiveNormalProSdrEnhancement()

    private fun RecordingQualityEvidenceRequest.withAdaptiveNormalProSdrEnhancement(
        intent: String = if (mode == EliteVideoPipelineMode.PRO) "PRO" else "NORMAL",
        sharpeningPacketValue: String = if (intent == "PRO") "0.5600" else "0.4400"
    ): RecordingQualityEvidenceRequest {
        val signature = listOf(
            "gpu-v1",
            "signal=SDR",
            "space=$primaries",
            "science=$selectedProfile",
            "transport=$activeTransport",
            "normalSdrRealtimeLock=true",
            "sdrQualityIntent=$intent",
            "sdrQualityPolicy=ADAPTIVE_SDR_V1",
            "packet=f4=0.4200,f5=0.6600,f6=0.7000,f7=0.2400,f8=$sharpeningPacketValue," +
                "f9=0.0200,f10=1.0300,f11=1.0100,f12=0.3200,f26=0.3600,f27=0.4400," +
                "f28=0.2800,f29=0.7400,f30=0.8200"
        ).joinToString(separator = "|")
        return copy(
            enhancementUniformSignature = signature,
            previewEnhancementUniformSignature = signature,
            recordingEnhancementUniformSignature = signature,
            enhancementTemporalBlend = 0.42f,
            enhancementSpatialDenoise = 0.66f,
            enhancementChromaDenoise = 0.70f,
            enhancementSharpening = sharpeningPacketValue.toFloatOrNull() ?: Float.NaN,
            enhancementSubjectDetailGain = 0f,
            enhancementDetailProfile = "NATURAL_DETAIL"
        )
    }

    private fun validNdrRequest(): RecordingQualityEvidenceRequest =
        baseRequest(
            mode = EliteVideoPipelineMode.HDR,
            selectedProfile = "NDR10+",
            activeTransport = "BT.2100_PQ_MAIN10",
            transfer = "ST2084/PQ",
            primaries = "BT.2020",
            matrix = "BT.2020-NCL",
            encodedBitDepth = 10,
            internalMathBitDepth = 12,
            sourceBitDepth = 10,
            storageBitDepth = 10,
            normalizedHdrVersion = "NDR10+",
            captureTruthSignature = "HDR_PQ|HDR_10BIT|Rec.2020-NDR10+|hdr=NDR10+|tenBit=true|log=false|raw=false|hdrMetadata=true",
            previewQualitySignature = "HDR|CAMERA2_PREVIEW_PARITY_RECORD_SURFACE|Rec.2020-NDR10+|HDR_CLEAN|HDR_HIGHLIGHT_SAFE|USER_MANUAL_COLOR",
            recordingQualitySignature = "HDR|CAMERA2_PREVIEW_PARITY_RECORD_SURFACE|Rec.2020-NDR10+|HDR_CLEAN|HDR_HIGHLIGHT_SAFE|USER_MANUAL_COLOR",
            probe = hdrProbe()
        )

    private fun validLogRequest(): RecordingQualityEvidenceRequest =
        baseRequest(
            mode = EliteVideoPipelineMode.LOG,
            selectedProfile = "N-Log Cinema",
            activeTransport = "HEVC_MAIN10_LOG",
            transfer = "N-Log",
            primaries = "BT.2020",
            matrix = "BT.2020-NCL",
            encodedBitDepth = 10,
            internalMathBitDepth = 12,
            sourceBitDepth = 10,
            storageBitDepth = 10,
            normalizedLogVersion = "N-Log Cinema",
            captureTruthSignature = "LOG_10BIT|LOG_10BIT|Rec.2020-LOG|hdr=OFF|tenBit=true|log=true|raw=false|hdrMetadata=false",
            previewQualitySignature = "LOG|CAMERA2_PREVIEW_PARITY_RECORD_SURFACE|Rec.2020-LOG|LOG_DETAIL|LOG_EDGE_PRESERVE|USER_MANUAL_COLOR",
            recordingQualitySignature = "LOG|CAMERA2_PREVIEW_PARITY_RECORD_SURFACE|Rec.2020-LOG|LOG_DETAIL|LOG_EDGE_PRESERVE|USER_MANUAL_COLOR",
            probe = RecordingProbeMetadata(
                codec = "hevc",
                pixelFormat = "yuv420p10le",
                encodedBitDepth = 10,
                primaries = "BT.2020",
                transfer = "BT.709/OETF",
                matrix = "BT.2020-NCL",
                colorRange = "LIMITED",
                width = 1920,
                height = 1080,
                fps = 30,
                bitrateBps = 460_000_000
            )
        )

    private fun validNpv10Request(): RecordingQualityEvidenceRequest =
        baseRequest(
            mode = EliteVideoPipelineMode.NPV10,
            selectedProfile = "N-Log Cinema",
            activeTransport = "NPV10_PRIVATE_MASTER",
            transfer = "N-Log",
            primaries = "BT.2020",
            matrix = "YUV420",
            encodedBitDepth = 10,
            internalMathBitDepth = 10,
            sourceBitDepth = 10,
            storageBitDepth = 10,
            npv10PayloadFormat = "P010_LE",
            npv10NativeTenBitClaimed = true,
            npv10MasterAvailable = true,
            captureTruthSignature = "NPV10|PRIVATE_MASTER|Rec.2020-N-Log|hdr=OFF|tenBit=true|log=true|raw=false|hdrMetadata=false",
            previewQualitySignature = "NPV10|PRIVATE_MASTER|Rec.2020-N-Log|LOG_DETAIL|LOG_EDGE_PRESERVE|USER_MANUAL_COLOR",
            recordingQualitySignature = "NPV10|PRIVATE_MASTER|Rec.2020-N-Log|LOG_DETAIL|LOG_EDGE_PRESERVE|USER_MANUAL_COLOR",
            probe = null,
            ffprobeValidated = false,
            mediaInfoValidated = true
        )

    private fun hdrProbe(): RecordingProbeMetadata =
        RecordingProbeMetadata(
            codec = "hevc",
            pixelFormat = "yuv420p10le",
            encodedBitDepth = 10,
            primaries = "BT.2020",
            transfer = "ST2084/PQ",
            matrix = "BT.2020-NCL",
            colorRange = "LIMITED",
            width = 1920,
            height = 1080,
            fps = 30,
            bitrateBps = 460_000_000
        )

    private fun fourKSixtyHdrProbe(): RecordingProbeMetadata =
        RecordingProbeMetadata(
            codec = "hevc",
            pixelFormat = "yuv420p10le",
            encodedBitDepth = 10,
            primaries = "BT.2020",
            transfer = "ST2084/PQ",
            matrix = "BT.2020-NCL",
            colorRange = "LIMITED",
            width = 3840,
            height = 2160,
            fps = 60,
            bitrateBps = 320_000_000
        )

    @Test
    fun `s6-f09 tier omega metrics with real pixel provenance stamp are accepted`() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(validNdrRequest())
        assertEquals(RecordingQualityEvidenceStatus.PASS, certificate.status)
        assertTrue(
            certificate.failures.none {
                it.code == RecordingQualityGateCode.TIER_OMEGA_METRICS_NOT_PROVEN
            }
        )
    }

    @Test
    fun `s6-f09 tier omega metrics without provenance stamp land not proven before threshold checks`() {
        val unstamped = tierOmegaPassingMetadata() -
            EliteVisionMeasurementHarness.TIER_OMEGA_METRICS_SOURCE_KEY
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(detailMetadata = unstamped)
        )
        assertTrue(certificate.status != RecordingQualityEvidenceStatus.PASS)
        assertTrue(
            certificate.failures.any {
                it.code == RecordingQualityGateCode.TIER_OMEGA_METRICS_NOT_PROVEN
            }
        )
        // Provenance failure RETURNs before threshold-checking the (unproven) values.
        assertTrue(
            certificate.failures.none {
                it.code == RecordingQualityGateCode.TIER_OMEGA_METRIC_THRESHOLD_FAILED
            }
        )
    }

    @Test
    fun `s6-f09 tier omega metrics with foreign provenance token land not proven`() {
        val foreign = tierOmegaPassingMetadata() +
            (EliteVisionMeasurementHarness.TIER_OMEGA_METRICS_SOURCE_KEY to "SYNTHETIC_FIXTURE")
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            validNdrRequest().copy(detailMetadata = foreign)
        )
        assertTrue(certificate.status != RecordingQualityEvidenceStatus.PASS)
        assertTrue(
            certificate.failures.any {
                it.code == RecordingQualityGateCode.TIER_OMEGA_METRICS_NOT_PROVEN
            }
        )
    }

    @Test
    fun `s6-f09 tier omega certificate is deterministic for fixed evidence`() {
        val request = validNdrRequest()
        assertEquals(
            RecordingQualityEvidencePolicy.evaluate(request).toCertificateText(),
            RecordingQualityEvidencePolicy.evaluate(request).toCertificateText()
        )
    }

    private fun baseRequest(
        mode: EliteVideoPipelineMode,
        selectedProfile: String,
        activeTransport: String,
        transfer: String,
        primaries: String,
        matrix: String,
        encodedBitDepth: Int,
        internalMathBitDepth: Int,
        sourceBitDepth: Int = encodedBitDepth,
        storageBitDepth: Int = encodedBitDepth,
        normalizedHdrVersion: String = "OFF",
        normalizedLogVersion: String = "N-Log Base",
        captureTruthSignature: String,
        previewQualitySignature: String,
        recordingQualitySignature: String,
        probe: RecordingProbeMetadata?,
        ffprobeValidated: Boolean = true,
        mediaInfoValidated: Boolean = true,
        npv10PayloadFormat: String = "",
        npv10NativeTenBitClaimed: Boolean = false,
        npv10MasterAvailable: Boolean = false
    ): RecordingQualityEvidenceRequest =
        RecordingQualityEvidenceRequest(
            mode = mode,
            selectedProfile = selectedProfile,
            activeTransport = activeTransport,
            transfer = transfer,
            primaries = primaries,
            matrix = matrix,
            codec = "hevc",
            encodedBitDepth = encodedBitDepth,
            internalMathBitDepth = internalMathBitDepth,
            sourceBitDepth = sourceBitDepth,
            storageBitDepth = storageBitDepth,
            width = 1920,
            height = 1080,
            fps = 30,
            bitrateBps = 460_000_000,
            selectedResolution = "1920x1080",
            actualResolution = "1920x1080",
            normalizedHdrVersion = normalizedHdrVersion,
            normalizedLogVersion = normalizedLogVersion,
            rawSensorOriginal = mode == EliteVideoPipelineMode.RAW,
            captureTruthSignature = captureTruthSignature,
            previewQualitySignature = previewQualitySignature,
            recordingQualitySignature = recordingQualitySignature,
            expectedFrameCount = 30,
            actualFrameCount = 30,
            droppedFrames = 0,
            maxAllowedDroppedFrames = 0,
            probe = probe,
            ffprobeValidated = ffprobeValidated,
            mediaInfoValidated = mediaInfoValidated,
            deviceLogValidated = true,
            npv10PayloadFormat = npv10PayloadFormat,
            npv10NativeTenBitClaimed = npv10NativeTenBitClaimed,
            npv10MasterAvailable = npv10MasterAvailable,
            detailMetadata = tierOmegaPassingMetadata(),
            enhancementUniformSignature = "gpu-v1|signal=${mode.name}|space=$primaries|science=$selectedProfile|packet=active",
            previewEnhancementUniformSignature = "gpu-v1|signal=${mode.name}|space=$primaries|science=$selectedProfile|packet=active",
            recordingEnhancementUniformSignature = "gpu-v1|signal=${mode.name}|space=$primaries|science=$selectedProfile|packet=active",
            enhancementTemporalBlend = 0.42f,
            enhancementSpatialDenoise = 0.73f,
            enhancementChromaDenoise = 0.96f,
            enhancementSharpening = 0.31f,
            enhancementSubjectDetailGain = 0.18f,
            enhancementDetailProfile = "FOCUSED_DETAIL",
            gpuProcessingValidated = mode != EliteVideoPipelineMode.NPV10 &&
                mode != EliteVideoPipelineMode.RAW,
            gpuProcessingPath = if (mode == EliteVideoPipelineMode.NPV10 || mode == EliteVideoPipelineMode.RAW) {
                "PRIVATE_MASTER"
            } else {
                "VULKAN_PREVIEW_RECORD_EXPORT"
            },
            processedRecordingFramesValidated = mode != EliteVideoPipelineMode.NPV10 &&
                mode != EliteVideoPipelineMode.RAW
        )

    private fun tierOmegaPassingMetadata(): Map<String, String> =
        mapOf(
            "tierOmegaLumaSnrBaseDb" to "55.000000",
            "tierOmegaLumaSnrHighIsoDb" to "44.000000",
            "tierOmegaChromaNoiseBaseStd" to "0.200000",
            "tierOmegaChromaNoiseHighIsoStd" to "0.500000",
            "tierOmegaTemporalFlickerPercent" to "0.400000",
            "tierOmegaMtf50CyPerPx" to "0.390000",
            "tierOmegaOvershootPercent" to "1.800000",
            "tierOmegaDetailRetentionRaw" to "0.970000",
            "tierOmegaDetailRetentionHighIsoRaw" to "0.940000",
            "tierOmegaDeltaE2000Mean" to "0.800000",
            "tierOmegaDeltaE2000Max" to "1.800000",
            "tierOmegaGrayDeltaAAbs" to "0.500000",
            "tierOmegaGrayDeltaBAbs" to "0.550000",
            "tierOmegaBandingScore" to "0.000000",
            "tierOmegaDeadHotPixels" to "0",
            "tierOmegaWbApplied" to "true",
            "tierOmegaCcmNonIdentity" to "true",
            "tierOmegaPreviewRecordExportParity" to "true",
            EliteVisionMeasurementHarness.TIER_OMEGA_METRICS_SOURCE_KEY to
                EliteVisionMeasurementHarness.SOURCE_REAL_PIXEL
        )

    private fun tierOmegaImaxOnlyPassingMetadata(): Map<String, String> =
        mapOf(
            "tierOmegaLumaSnrBaseDb" to "51.000000",
            "tierOmegaLumaSnrHighIsoDb" to "41.000000",
            "tierOmegaChromaNoiseBaseStd" to "0.340000",
            "tierOmegaChromaNoiseHighIsoStd" to "0.700000",
            "tierOmegaTemporalFlickerPercent" to "0.700000",
            "tierOmegaMtf50CyPerPx" to "0.350000",
            "tierOmegaOvershootPercent" to "2.400000",
            "tierOmegaDetailRetentionRaw" to "0.940000",
            "tierOmegaDetailRetentionHighIsoRaw" to "0.910000",
            "tierOmegaDeltaE2000Mean" to "1.100000",
            "tierOmegaDeltaE2000Max" to "2.400000",
            "tierOmegaGrayDeltaAAbs" to "0.700000",
            "tierOmegaGrayDeltaBAbs" to "0.800000",
            "tierOmegaBandingScore" to "0.000000",
            "tierOmegaDeadHotPixels" to "0",
            "tierOmegaWbApplied" to "true",
            "tierOmegaCcmNonIdentity" to "true",
            "tierOmegaPreviewRecordExportParity" to "true",
            EliteVisionMeasurementHarness.TIER_OMEGA_METRICS_SOURCE_KEY to
                EliteVisionMeasurementHarness.SOURCE_REAL_PIXEL
        )
}
