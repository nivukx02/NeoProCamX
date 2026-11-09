package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class EliteConversionQualityEvidenceFactoryTest {

    @Test
    fun rawNeoLogEvidenceDeclaresAcesAp1AndRequiresTheCompanionLut() {
        val options = RawToNeoLogMovConverter.Options(
            format = RawToNeoLogMovConverter.OutputFormat.MASTER_444,
            deliveryProfile = RawToNeoLogMovConverter.DeliveryProfile.NEOLOG
        )
        val audit = EliteConversionQualityEvidenceFactory.enforceRawDevelop(
            meta = rawMeta(),
            options = options,
            framesConverted = 100,
            containerColor = neoLogR210Carrier(),
            companionLutPresent = true
        )

        assertTrue(audit.passed)
        assertEquals(
            EliteConversionQualityContract.SignalGamut.ACES_AP1,
            audit.evidence.color.semantic.gamut
        )
        assertEquals(
            EliteConversionQualityContract.SignalTransfer.NEOLOG,
            audit.evidence.color.semantic.transfer
        )
    }

    @Test(expected = IllegalStateException::class)
    fun rawNeoLogPublicationFailsClosedWhenTheRequiredLutWasNotWritten() {
        EliteConversionQualityEvidenceFactory.enforceRawDevelop(
            meta = rawMeta(),
            options = RawToNeoLogMovConverter.Options(),
            framesConverted = 1,
            containerColor = neoLogR210Carrier(),
            companionLutPresent = false
        )
    }

    @Test
    fun rawNdrMain10EvidenceLocksPqBt2020TenBitAndBitrate() {
        val options = RawToNeoLogMovConverter.Options(
            format = RawToNeoLogMovConverter.OutputFormat.HEVC_MAIN10_420,
            deliveryProfile = RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS
        )
        val audit = EliteConversionQualityEvidenceFactory.enforceRawDevelop(
            meta = rawMeta(),
            options = options,
            framesConverted = 240,
            containerColor = pqBt2020Carrier(),
            companionLutPresent = false,
            minimumBitrateBps = 80_000_000,
            configuredBitrateBps = 160_000_000,
            policyTargetBitrateBps = 200_000_000,
            advertisedBitrateCeilingBps = 160_000_000,
            encoderCapabilityBound = true,
            advancedEncoderControlsAccepted = true
        )

        assertTrue(audit.passed)
        assertEquals(10, audit.evidence.outputBitDepth)
        assertEquals(
            EliteConversionQualityContract.OutputTransport.HEVC_MAIN10_420,
            audit.evidence.outputTransport
        )
        val bitrate = requireNotNull(audit.evidence.bitrate)
        assertEquals(80_000_000L, bitrate.minimumRequiredBps)
        assertEquals(160_000_000L, bitrate.configuredBps)
        assertEquals(200_000_000L, bitrate.policyTargetBps)
        assertEquals(160_000_000L, bitrate.advertisedCeilingBps)
        assertEquals(true, bitrate.capabilityBound)
        assertEquals("true", audit.toMetadata().getValue("encoderCapabilityBound"))
    }

    @Test
    fun rawPqUncompressedEvidencePreservesExactRgbAndYuvCarrierContracts() {
        for ((format, color, expectedTransport) in listOf(
            Triple(
                RawToNeoLogMovConverter.OutputFormat.MASTER_444,
                QuickTimeColorAtomConfig(
                    colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                    transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                    matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_RGB_IDENTITY,
                    fullRange = true,
                    masteringDisplay = HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_5000_NITS
                ),
                EliteConversionQualityContract.OutputTransport.R210_RGB_444
            ),
            Triple(
                RawToNeoLogMovConverter.OutputFormat.COMPAT_V210,
                pqBt2020Carrier(),
                EliteConversionQualityContract.OutputTransport.V210_YUV_422
            )
        )) {
            val audit = EliteConversionQualityEvidenceFactory.enforceRawDevelop(
                meta = rawMeta(),
                options = RawToNeoLogMovConverter.Options(
                    format = format,
                    deliveryProfile = RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS
                ),
                framesConverted = 24,
                containerColor = color,
                companionLutPresent = false
            )
            assertTrue(audit.passed)
            assertEquals(expectedTransport, audit.evidence.outputTransport)
            assertEquals(10, audit.evidence.outputBitDepth)
            assertTrue(!audit.evidence.qualityReducedForSpeed)
        }
    }

    @Test
    fun recordDevelopEvidenceCoversTheActualSdrCarrierAndExactFrameCount() {
        val audit = EliteConversionQualityEvidenceFactory.enforceRecordDevelop(
            sourceKind = RecordDevelopMasterKind.SDR,
            target = DevelopTarget.SDR_REC709,
            sourceBitDepth = 10,
            width = 3840,
            height = 2160,
            fps = 25,
            frameCount = 125,
            deliveryFormat = RecordDevelopDeliveryFormat.R210_444_UNCOMPRESSED_MOV,
            containerColor = QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT709,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT709,
                fullRange = true
            ),
            sourceSidecar = null
        )

        assertTrue(audit.passed)
        assertEquals(125L, audit.evidence.signal.outputFrameCount)
        assertEquals(
            EliteConversionQualityContract.Matrix.BT709_CARRIER,
            audit.evidence.color.containerCarrier?.matrix
        )
        assertEquals(
            EliteConversionQualityContract.Delivery.VIDEO,
            audit.evidence.delivery
        )
    }

    @Test
    fun recordDevelopR210PqEvidenceKeepsTheActualFullRangeBt2020Carrier() {
        val audit = EliteConversionQualityEvidenceFactory.enforceRecordDevelop(
            sourceKind = RecordDevelopMasterKind.NDR,
            target = DevelopTarget.NDR_PQ_BT2020,
            sourceBitDepth = 10,
            width = 4096,
            height = 2160,
            fps = 25,
            frameCount = 250,
            deliveryFormat = RecordDevelopDeliveryFormat.R210_444_UNCOMPRESSED_MOV,
            containerColor = pqBt2020Carrier().copy(fullRange = true),
            sourceSidecar = null
        )

        assertTrue(audit.passed)
        assertEquals(
            EliteConversionQualityContract.Matrix.BT2020_CARRIER,
            audit.evidence.color.containerCarrier?.matrix
        )
        assertEquals(
            EliteConversionQualityContract.Range.FULL,
            audit.evidence.color.containerCarrier?.range
        )
        assertEquals(250L, audit.evidence.signal.outputFrameCount)
    }

    @Test
    fun certificateContainsDeterministicFailClosedEvidence() {
        val audit = EliteConversionQualityEvidenceFactory.enforceRawDevelop(
            meta = rawMeta(),
            options = RawToNeoLogMovConverter.Options(),
            framesConverted = 2,
            containerColor = neoLogR210Carrier(),
            companionLutPresent = true
        )
        val root = Files.createTempDirectory("elite-conversion-evidence").toFile()
        try {
            val certificate = EliteConversionQualityEvidenceFactory.writeCertificate(
                root.resolve("clip.mov").absolutePath,
                audit
            )
            val text = certificate.readText()
            assertTrue(text.contains("status=PASS"))
            assertTrue(text.contains("outputBitDepth=10"))
            assertTrue(text.contains("semanticSignalGamut=ACES_AP1"))
            assertTrue(text.contains("finalQuantizationApplications=1"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun rawMeta() = RawDevelopMetadata(
        width = 3840,
        height = 2160,
        storageBitDepth = 12,
        sensorWhiteLevel = 4095,
        blackLevel = 64,
        whiteLevel = 4095,
        bayerPatternValue = 0,
        whiteBalanceR = 2f,
        whiteBalanceGr = 1f,
        whiteBalanceGb = 1f,
        whiteBalanceB = 1.7f,
        exposureTimeNs = 20_000_000,
        analogGain = 1f,
        digitalGain = 1f,
        noiseS = 0f,
        noiseO = 0f,
        sensorToXyz = DEFAULT_SENSOR_TO_XYZ.copyOf(),
        fps = 25,
        colorProfileTag = "N-LOG-CINEMA"
    )

    private fun neoLogR210Carrier() = QuickTimeColorAtomConfig(
        colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
        transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
        matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT709,
        fullRange = true
    )

    private fun pqBt2020Carrier() = QuickTimeColorAtomConfig(
        colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
        transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
        matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
        fullRange = false,
        masteringDisplay = HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_1000_NITS
    )
}
