package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * F07 editor round-trip color-tag contract [CP-QV7].
 *
 * A recording that leaves the encoder and is re-opened by an editor must carry
 * ONE unambiguous color identity across three surfaces: the HEVC SPS VUI, the
 * QuickTime `colr` nclx atom, and (for HDR) the user-registered ITU-T T.35 SEI.
 * If any of these disagree, an editor infers a different transfer/gamut than the
 * app recorded and washes out or over-saturates the delivery.
 *
 * These are honest contract tests against the SAME [HevcColorMetadataRewriter],
 * [QuickTimeMovFileWriter] and [Npv10MovConverter] the live record/export paths
 * use — no downgrade, no fabricated PASS. Each color identity is anchored to a
 * hardcoded ITU-T H.273 spec triplet (primaries, transfer, matrix) so a silent
 * drift in the rewriter/writer mapping fails the build.
 */
class EditorRoundTripColorTagContractTest {

    /**
     * ITU-T H.273 (primaries, transfer, matrix) spec anchors for every color
     * identity the rewriter AND the QuickTime writer actually support. Values
     * are hardcoded, NOT derived from the code under test, so they are a true
     * external oracle. Verified against QuickTimeColorAtomPolicy constants and
     * the rewriter's arbitrary VUI imprint.
     */
    private data class ColorDelivery(
        val name: String,
        val primaries: Int,
        val transfer: Int,
        val matrix: Int,
        val fullRange: Boolean,
        val config: QuickTimeColorAtomConfig
    )

    private fun deliveries(): List<ColorDelivery> = listOf(
        // Rec.709 SDR: BT.709 primaries=1, transfer=1, matrix=1.
        ColorDelivery(
            name = "REC709_SDR",
            primaries = 1,
            transfer = 1,
            matrix = 1,
            fullRange = false,
            config = QuickTimeColorAtomConfig.rec709SdrLimited()
        ),
        // LOG on a BT.2020 container tagged SDR: primaries=9, transfer=1 (BT.709
        // curve, honest SDR carrier for LOG), matrix=9 (BT.2020 non-constant).
        ColorDelivery(
            name = "LOG_BT2020_SDR",
            primaries = 9,
            transfer = 1,
            matrix = 9,
            fullRange = false,
            config = QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = false
            )
        ),
        // HDR10 PQ: BT.2020 primaries=9, transfer=16 (ST.2084/PQ), matrix=9.
        ColorDelivery(
            name = "HDR10_PQ_BT2020",
            primaries = 9,
            transfer = 16,
            matrix = 9,
            fullRange = false,
            config = QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = false
            )
        ),
        // HLG: BT.2020 primaries=9, transfer=18 (ARIB STD-B67/HLG), matrix=9.
        ColorDelivery(
            name = "HLG_BT2020",
            primaries = 9,
            transfer = 18,
            matrix = 9,
            fullRange = false,
            config = QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_HLG,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = false
            )
        ),
        // Display-P3 SDR: P3-D65 primaries=12, transfer=13 (sRGB/IEC 61966-2-1),
        // matrix=1 (BT.709 non-constant, per QuickTimeColorAtomConfig.displayP3).
        ColorDelivery(
            name = "DISPLAY_P3_SDR",
            primaries = 12,
            transfer = 13,
            matrix = 1,
            fullRange = false,
            config = QuickTimeColorAtomConfig.displayP3SdrLimited()
        )
    )

    // ---------------------------------------------------------------------
    // (a) SPS VUI parse: the imprinted triplet == the hardcoded H.273 table.
    // ---------------------------------------------------------------------

    @Test
    fun spsVuiImprintsSpecTripletForEveryEditorDelivery() {
        deliveries().forEach { delivery ->
            // Sanity: the color config the writer/rewriter consume MUST equal the
            // external spec triplet — this catches a drifted config factory too.
            assertEquals("primaries config for ${delivery.name}", delivery.primaries, delivery.config.colorPrimaries)
            assertEquals("transfer config for ${delivery.name}", delivery.transfer, delivery.config.transferCharacteristics)
            assertEquals("matrix config for ${delivery.name}", delivery.matrix, delivery.config.matrixCoefficients)

            val rewritten = HevcColorMetadataRewriter.rewriteAnnexBColorMetadata(
                bytes = annexB(VPS, SPS_PQ_MAIN10, PPS),
                colorConfig = delivery.config
            )
            val vui = HevcColorMetadataRewriter.firstAnnexBSpsColorMetadata(rewritten)
            assertNotNull("rewritten SPS must parse for ${delivery.name}", vui)
            assertEquals("SPS primaries for ${delivery.name}", delivery.primaries, vui!!.colorPrimaries)
            assertEquals("SPS transfer for ${delivery.name}", delivery.transfer, vui.transferCharacteristics)
            assertEquals("SPS matrix for ${delivery.name}", delivery.matrix, vui.matrixCoefficients)
            assertEquals("SPS fullRange for ${delivery.name}", delivery.fullRange, vui.fullRange)
        }
    }

    // ---------------------------------------------------------------------
    // (b) MOV colr nclx atom == same H.273 table AND == the hvcC SPS VUI:
    //     no container/stream drift when an editor re-opens the file.
    // ---------------------------------------------------------------------

    @Test
    fun movColrAtomAndHvcCSpsAgreeOnSpecTripletForEveryDelivery() {
        deliveries().forEach { delivery ->
            val output = Files.createTempFile("camx-roundtrip-${delivery.name}", ".mov")
            try {
                val writer = QuickTimeMovFileWriter(
                    outputPath = output.toFile().absolutePath,
                    containerLabel = "QuickTime MOV"
                )
                writer.addVideoTrack(
                    QuickTimeVideoTrackConfig(
                        mime = "video/hevc",
                        width = 1920,
                        height = 1080,
                        fps = 30,
                        codecConfig = annexB(VPS, SPS_PQ_MAIN10, PPS),
                        colorConfig = delivery.config
                    )
                ).let { track ->
                    writer.writeSample(
                        trackIndex = track,
                        sample = QuickTimeSampleBuffer(
                            bytes = byteArrayOf(0, 0, 0, 1, 0x40),
                            presentationTimeUs = 0L,
                            flags = 1
                        )
                    )
                }
                writer.close()

                val bytes = Files.readAllBytes(output)

                // colr nclx atom: fourcc "nclx" then primaries/transfer/matrix u16.
                val colrOffset = findBoxOffset(bytes, "colr")
                assertTrue("colr box missing for ${delivery.name}", colrOffset >= 0)
                assertEquals(
                    "colr fourcc for ${delivery.name}",
                    "nclx",
                    String(bytes, colrOffset + 8, 4, Charsets.US_ASCII)
                )
                assertEquals("colr primaries for ${delivery.name}", delivery.primaries, readU16(bytes, colrOffset + 12))
                assertEquals("colr transfer for ${delivery.name}", delivery.transfer, readU16(bytes, colrOffset + 14))
                assertEquals("colr matrix for ${delivery.name}", delivery.matrix, readU16(bytes, colrOffset + 16))
                assertEquals(
                    "colr full-range flag for ${delivery.name}",
                    if (delivery.fullRange) 0x80 else 0x00,
                    bytes[colrOffset + 18].toInt() and 0x80
                )

                // hvcC SPS VUI parsed straight back out of the container.
                val sps = extractHevcParameterSet(bytes, nalType = 33)
                val streamVui = HevcColorMetadataRewriter.firstAnnexBSpsColorMetadata(annexB(sps))
                assertNotNull("hvcC SPS must parse for ${delivery.name}", streamVui)
                assertEquals("stream primaries for ${delivery.name}", delivery.primaries, streamVui!!.colorPrimaries)
                assertEquals("stream transfer for ${delivery.name}", delivery.transfer, streamVui.transferCharacteristics)
                assertEquals("stream matrix for ${delivery.name}", delivery.matrix, streamVui.matrixCoefficients)
                assertEquals("stream fullRange for ${delivery.name}", delivery.fullRange, streamVui.fullRange)

                // Container atom == stream VUI: one identity, no drift.
                assertEquals(
                    "colr primaries must equal SPS VUI primaries for ${delivery.name}",
                    readU16(bytes, colrOffset + 12),
                    streamVui.colorPrimaries
                )
                assertEquals(
                    "colr transfer must equal SPS VUI transfer for ${delivery.name}",
                    readU16(bytes, colrOffset + 14),
                    streamVui.transferCharacteristics
                )
                assertEquals(
                    "colr matrix must equal SPS VUI matrix for ${delivery.name}",
                    readU16(bytes, colrOffset + 16),
                    streamVui.matrixCoefficients
                )
            } finally {
                Files.deleteIfExists(output)
            }
        }
    }

    // ---------------------------------------------------------------------
    // (c) T.35 SEI: prefix SEI type + payloadType 4 + ITU-T country/provider,
    //     plus a lossless EBSP<->RBSP round-trip.
    // ---------------------------------------------------------------------

    @Test
    fun neoHdrT35PrefixSeiCarriesUserRegisteredItutHeaderAndRoundTripsExactly() {
        listOf(
            HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS,
            HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION
        ).forEach { version ->
            val metadata = HdrImagingMath.NeoHdrDynamicMetadata(
                frameIndex = 7L,
                version = version,
                masteringPeakNits = 10_000.0,
                targetPeakNits = 1_680.0,
                p10Nits = 12.0,
                p50Nits = 220.0,
                p90Nits = 1_400.0,
                p99Nits = 4_200.0,
                kneeNits = 2_380.0,
                shoulderStrength = 2.15,
                toeStrength = 0.92,
                colorVolumeCompression = 0.64
            )
            val payload = HdrImagingMath.buildNeoHdrItutT35Payload(metadata)

            // ITU-T T.35 header: country 0xB5 (USA), provider 0x003C, then 0x0001.
            assertTrue("T.35 payload for $version too short", payload.size >= 5)
            assertEquals("T.35 country code for $version", 0xB5, payload[0].toInt() and 0xFF)
            assertEquals("T.35 provider hi for $version", 0x00, payload[1].toInt() and 0xFF)
            assertEquals("T.35 provider lo for $version", 0x3C, payload[2].toInt() and 0xFF)
            assertEquals("T.35 provider oriented code hi for $version", 0x00, payload[3].toInt() and 0xFF)
            assertEquals("T.35 provider oriented code lo for $version", 0x01, payload[4].toInt() and 0xFF)

            val seiNal = HevcColorMetadataRewriter.buildUserRegisteredItutT35PrefixSeiNal(payload)

            // HEVC NAL header: type 6 bits == PREFIX_SEI (39).
            assertEquals(
                "SEI NAL type must be HEVC PREFIX_SEI for $version",
                HevcColorMetadataRewriter.NAL_TYPE_PREFIX_SEI,
                (seiNal[0].toInt() ushr 1) and 0x3F
            )
            // First RBSP byte after the 2-byte NAL header is payloadType == 4
            // (user_data_registered_itu_t_t35).
            assertEquals("SEI payloadType must be 4 for $version", 4, seiNal[2].toInt() and 0xFF)

            // EBSP<->RBSP round-trip reconstructs the EXACT T.35 payload.
            assertTrue(
                "T.35 SEI must round-trip exactly for $version",
                HevcColorMetadataRewriter.containsUserRegisteredItutT35Sei(annexB(seiNal), payload)
            )
            // A different valid-header T.35 body must be rejected (no false match).
            val decoy = payload.copyOf()
            decoy[decoy.lastIndex] = (decoy[decoy.lastIndex] + 1).toByte()
            assertTrue(
                "T.35 SEI must reject a mutated payload for $version",
                !HevcColorMetadataRewriter.containsUserRegisteredItutT35Sei(annexB(seiNal), decoy)
            )
        }
    }

    @Test
    fun t35SeiSurvivesEmulationPreventionRoundTripForHostilePayload() {
        // Body deliberately contains 00 00 03 / 00 00 00 / 00 00 01 / 00 00 02
        // runs so rbspToEbsp insertion AND nalToRbsp stripping are both exercised
        // end-to-end; a lossy escape would corrupt the recovered T.35 payload.
        val payload = hex("b5 00 3c 00 01 00 00 03 00 00 00 00 00 01 00 00 02 aa bb 00 00")
        val seiNal = HevcColorMetadataRewriter.buildUserRegisteredItutT35PrefixSeiNal(payload)

        assertEquals(
            HevcColorMetadataRewriter.NAL_TYPE_PREFIX_SEI,
            (seiNal[0].toInt() ushr 1) and 0x3F
        )
        assertEquals(4, seiNal[2].toInt() and 0xFF)
        assertTrue(
            "EBSP<->RBSP round-trip must reconstruct the exact T.35 payload",
            HevcColorMetadataRewriter.containsUserRegisteredItutT35Sei(annexB(seiNal), payload)
        )
    }

    // ---------------------------------------------------------------------
    // (d) NPV10 honest provenance: the derived-MOV certificate must state the
    //     private master was transcoded UNMODIFIED and NOT destructively
    //     enhanced — no WYSIWYG over-claim on the master.
    // ---------------------------------------------------------------------

    @Test
    fun npv10ConversionCertificateDeclaresUnmodifiedNonDestructiveMasterProvenance() {
        val input = Files.createTempFile("npv10-provenance-source", ".npv10").toFile()
        val output = Files.createTempFile("npv10-provenance-output", ".mov").toFile()
        try {
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
                        timestamp = 1L,
                        bitDepth = 10
                    )
                )
            }

            val masterBefore = input.readBytes()
            val audit = Npv10MovConverter.convertWithAudit(input, output)
            val masterAfter = input.readBytes()

            // The private NPV10 master must be byte-identical after export.
            assertArrayEquals("NPV10 master must not be mutated by export", masterBefore, masterAfter)

            val certificate = audit.conversionCertificate
            // Honest transcode provenance: derived MOV from an UNMODIFIED master.
            assertTrue(
                "certificate must declare the unmodified master-transcode policy",
                certificate.contains("movExportTranscodePolicy=DERIVED_V210_MOV_EXPORT_PRIVATE_NPV10_MASTER_UNMODIFIED")
            )
            // No WYSIWYG over-claim: the master itself was NOT destructively enhanced.
            assertTrue(
                "certificate must state masterEnhancementDestructive=false",
                certificate.contains("masterEnhancementDestructive=false")
            )
            assertTrue(
                "certificate must state npv10PrivateMasterUnmodified=true",
                certificate.contains("npv10PrivateMasterUnmodified=true")
            )
            assertTrue(
                "certificate must state inputMasterHashUnchanged=true",
                certificate.contains("inputMasterHashUnchanged=true")
            )
            assertTrue(
                "enhancement must be export-only, never on the master",
                certificate.contains("enhancementAppliedStage=NPV10_EXPORT_ONLY")
            )
            assertEquals("honest provenance must pass the integrity audit", Npv10AuditStatus.PASS, audit.auditStatus)
        } finally {
            input.delete()
            output.delete()
            Npv10MovConverter.certificateSidecarFile(output).delete()
        }
    }

    // ---------------------------------------------------------------------
    // Helpers (byte-parse + fixtures reused from the existing rewriter/writer
    // contract tests so the round-trip is checked against the real encoders).
    // ---------------------------------------------------------------------

    private fun tenBitPlane(vararg samples: Int): ByteArray {
        val output = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val value = sample.coerceIn(0, 1023)
            output[index * 2] = (value and 0xFF).toByte()
            output[index * 2 + 1] = ((value ushr 8) and 0x03).toByte()
        }
        return output
    }

    private fun annexB(vararg nals: ByteArray): ByteArray =
        nals.fold(ByteArray(0)) { acc, nal ->
            acc + byteArrayOf(0, 0, 0, 1) + nal
        }

    private fun findBoxOffset(bytes: ByteArray, boxType: String): Int {
        val target = boxType.toByteArray(Charsets.US_ASCII)
        for (index in 4..bytes.size - 4) {
            if (
                bytes[index] == target[0] &&
                bytes[index + 1] == target[1] &&
                bytes[index + 2] == target[2] &&
                bytes[index + 3] == target[3]
            ) {
                return index - 4
            }
        }
        return -1
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)

    private fun extractHevcParameterSet(bytes: ByteArray, nalType: Int): ByteArray {
        val hvcCOffset = findBoxOffset(bytes, "hvcC")
        require(hvcCOffset >= 0) { "hvcC box missing" }
        var offset = hvcCOffset + 8 + 23
        val arrayCount = bytes[hvcCOffset + 8 + 22].toInt() and 0xFF
        repeat(arrayCount) {
            val arrayNalType = bytes[offset].toInt() and 0x3F
            offset += 1
            val nalCount = readU16(bytes, offset)
            offset += 2
            repeat(nalCount) {
                val length = readU16(bytes, offset)
                offset += 2
                val nal = bytes.copyOfRange(offset, offset + length)
                offset += length
                if (arrayNalType == nalType) return nal
            }
        }
        error("HEVC parameter set type $nalType missing")
    }

    private companion object {
        fun hex(value: String): ByteArray =
            value
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .map { it.toInt(16).toByte() }
                .toByteArray()

        val VPS: ByteArray = hex(
            "40 01 0c 01 ff ff 22 20 00 00 03 00 b0 00 00 03 00 00 03 00 b4 ac 09"
        )
        val SPS_PQ_MAIN10: ByteArray = hex(
            "42 01 01 22 20 00 00 03 00 b0 00 00 03 00 00 03 00 b4 a0 00 " +
                "80 08 00 90 13 65 ae e4 c9 2e a6 a1 22 01 20 5d a1 42 50"
        )
        val PPS: ByteArray = hex(
            "44 01 c0 e3 0f 08 d0 60 30 18 0c 06 3d 84 20"
        )
    }
}
