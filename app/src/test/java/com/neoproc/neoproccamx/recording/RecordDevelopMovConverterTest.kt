package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.recording.RecordDevelopP010ColorIngress.OutputTransfer
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorSpace
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorTransfer
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.VideoDevelopColorMetadata
import com.neoproc.neoproccamx.snce.SnceIlluminantEstimator
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exercises the CPU-parallel offline develop converter through the pure-JVM [InMemoryP010FrameReader] seam:
 * in-order muxing, byte-determinism across worker counts, empty-master fail-safe abort, read-only master
 * tamper-evidence, sidecar → develop-target/colour-tag derivation, and the certificate split.
 */
class RecordDevelopMovConverterTest {

    private val temps = mutableListOf<File>()

    @After
    fun cleanup() {
        temps.forEach { it.delete() }
    }

    private fun tempFile(name: String): File =
        File.createTempFile("recdev_$name", ".bin").also { temps += it }

    // ── canonical P010 frame builder (matches RecordDevelopP010FrameReader layout) ─────────────────────

    private fun uniformP010(width: Int, height: Int, yCode: Int, cbCode: Int = 512, crCode: Int = 512): ByteArray {
        val chromaW = (width + 1) / 2
        val chromaH = (height + 1) / 2
        val lumaBytes = width * height * 2
        val out = ByteArray(lumaBytes + chromaW * chromaH * 2 * 2)
        fun put(offset: Int, code: Int) {
            val v = (code and 0x3FF) shl 6
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        for (p in 0 until width * height) put(p * 2, yCode)
        var o = lumaBytes
        for (p in 0 until chromaW * chromaH) {
            put(o, cbCode); put(o + 2, crCode); o += 4
        }
        return out
    }

    private fun frames(width: Int, height: Int, count: Int, fps: Int = 30): List<RecordDevelopP010Frame> =
        (0 until count).map { i ->
            RecordDevelopP010Frame(
                index = i.toLong(),
                presentationTimeUs = 0L, // ⇒ converter derives an even cadence from fps
                width = width,
                height = height,
                p010 = uniformP010(width, height, yCode = (80 + i * 40).coerceAtMost(1000))
            )
        }

    private fun readerFactoryOf(
        frameList: List<RecordDevelopP010Frame>,
        width: Int,
        height: Int,
        fps: Int = 30,
        sourceDurationUs: Long? = null
    ):
        (File) -> RecordDevelopP010FrameReader = {
        InMemoryP010FrameReader(
            frameList,
            RecordDevelopDecodedFormat(width, height, fps, sourceDurationUs = sourceDurationUs)
        )
    }

    private fun sidecar(space: DevelopColorSpace, transfer: DevelopColorTransfer): VideoDevelopColorMetadata {
        val matrix = if (space == DevelopColorSpace.REC2020) {
            SnceIlluminantEstimator.REC2020_TO_XYZ.copyOf()
        } else {
            SnceIlluminantEstimator.REC709_TO_XYZ.copyOf()
        }
        return VideoDevelopColorMetadata(
            width = 12, height = 4, storageBitDepth = 10, fps = 30,
            whiteBalanceR = 1f, whiteBalanceGr = 1f, whiteBalanceGb = 1f, whiteBalanceB = 1f,
            workingRgbToXyz = matrix, adaptingLuminance = 0f, cctKelvin = 5500f, tint = 0f,
            colorSpace = space, colorTransfer = transfer, colorProfileTag = "test"
        )
    }

    private fun mdatDataStart(bytes: ByteArray): Int {
        val tag = byteArrayOf('m'.code.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte())
        for (i in 0..bytes.size - tag.size) {
            if (bytes[i] == tag[0] && bytes[i + 1] == tag[1] && bytes[i + 2] == tag[2] && bytes[i + 3] == tag[3]) {
                return i + 12 // fourcc(4) is at box_start+4; largesize(8) follows ⇒ data at box_start+16 = i+12
            }
        }
        error("no mdat box in output")
    }

    // ── tests ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun producesInOrderFramesWithExpectedIdentityPayloads() {
        val w = 12; val h = 4; val n = 8
        val frameList = frames(w, h, n)
        val master = tempFile("master")
        val output = tempFile("out")
        val onFrameCounts = mutableListOf<Int>()

        val audit = RecordDevelopMovConverter.convertWithAudit(
            master = master,
            output = output,
            sidecar = null, // ⇒ SDR / Rec.709 defaults
            // Strength-0 develop + enhance OFF ⇒ a pure decode→encode passthrough (the no-downgrade lock).
            options = RecordDevelopConversionOptions(
                snceAppearanceStrength = 0f,
                filmPrintStrength = 0f,
                enhance = RecordDevelopEnhanceOptions.DISABLED
            ),
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = readerFactoryOf(frameList, w, h),
            onFrame = { onFrameCounts += it }
        )
        assertFalse("enhance-disabled ⇒ audit records no enhance", audit.enhanceApplied)

        assertEquals(n.toLong(), audit.frameCount)
        assertEquals((1..n).toList(), onFrameCounts) // monotonic, once per written frame, in order

        // Each muxed frame's payload must equal an independent identity develop (decode→encode) of that
        // frame's distinct luma — proving both ORDER (frame i at position i) and correct passthrough packing.
        val bytes = output.readBytes()
        val start = mdatDataStart(bytes)
        val payloadSize = RawTenBitPacker.payloadSize(RawTenBitPacker.Format.R210_444, w, h)
        for (i in 0 until n) {
            val expected = expectedIdentityR210(w, h, (80 + i * 40).coerceAtMost(1000))
            val actual = bytes.copyOfRange(start + i * payloadSize, start + (i + 1) * payloadSize)
            assertArrayEquals("frame $i payload", expected, actual)
        }
    }

    @Test
    fun convertedMovRetainsFrozenCaptureOrientationFromSidecar() {
        val w = 12
        val h = 4
        val master = tempFile("orientation_master")
        val output = tempFile("orientation_out")
        val captureSidecar = sidecar(DevelopColorSpace.REC709, DevelopColorTransfer.SDR)
            .copy(width = w, height = h, orientationHintDegrees = 180)

        RecordDevelopMovConverter.convertWithAudit(
            master = master,
            output = output,
            sidecar = captureSidecar,
            options = RecordDevelopConversionOptions(
                snceAppearanceStrength = 0f,
                filmPrintStrength = 0f,
                enhance = RecordDevelopEnhanceOptions.DISABLED
            ),
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = readerFactoryOf(frames(w, h, 1), w, h)
        )

        val bytes = output.readBytes()
        val tkhdTypeOffset = findFourCc(bytes, "tkhd")
        assertTrue(tkhdTypeOffset >= 4)
        assertEquals(-0x00010000, readSignedInt(bytes, tkhdTypeOffset + 44))
        assertEquals(-0x00010000, readSignedInt(bytes, tkhdTypeOffset + 60))
    }

    private fun expectedIdentityR210(w: Int, h: Int, yCode: Int): ByteArray {
        val linear = RecordDevelopP010ColorIngress.decodeToLinearRgb(
            yCode, 512, 512, DevelopColorSpace.REC709, DevelopColorTransfer.SDR
        )
        val enc = RecordDevelopP010ColorIngress.encodeLinearRgb(
            linear[0], linear[1], linear[2], OutputTransfer.SDR_REC709_OETF, 203f
        )
        val codes = ShortArray(w * h * 3)
        var i = 0
        while (i < codes.size) {
            codes[i] = enc[0].toShort(); codes[i + 1] = enc[1].toShort(); codes[i + 2] = enc[2].toShort()
            i += 3
        }
        return RawTenBitPacker.packR210(codes, w, h)
    }

    private fun findFourCc(bytes: ByteArray, fourCc: String): Int {
        val target = fourCc.toByteArray(Charsets.US_ASCII)
        for (index in 0..bytes.size - target.size) {
            if (bytes.copyOfRange(index, index + target.size).contentEquals(target)) return index
        }
        return -1
    }

    private fun readSignedInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    @Test
    fun outputIsByteIdenticalAcrossWorkerCounts() {
        val w = 16; val h = 8; val n = 24
        val master = tempFile("master")
        val out1 = tempFile("out1")
        val out2 = tempFile("out2")
        val opts = RecordDevelopConversionOptions() // default develop (snce=1)

        val single = RecordDevelopMovConverter.convertWithAudit(
            master, out1, sidecar = null,
            options = opts.copy(memoryBudgetBytes = 8L * 1024L), // budget-valid single worker
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = readerFactoryOf(frames(w, h, n), w, h)
        )
        assertEquals("bounded budget must force a single worker", 1, single.workerCount)

        // The multi-worker reorder/thread-safety path only exists with ≥3 cores (cpuCap = cores-1 > 1);
        // be honestly inconclusive rather than vacuously green on a 2-vCPU CI runner.
        org.junit.Assume.assumeTrue(
            "needs >=3 cores to exercise the multi-worker reorder path",
            Runtime.getRuntime().availableProcessors() >= 3
        )
        val multi = RecordDevelopMovConverter.convertWithAudit(
            master, out2, sidecar = null,
            options = opts, // ⇒ multi-core workers
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = readerFactoryOf(frames(w, h, n), w, h)
        )
        assertTrue("default budget must fan out to >1 worker (got ${multi.workerCount})", multi.workerCount > 1)
        assertTrue("single-worker and multi-worker output byte-identical", out1.readBytes().contentEquals(out2.readBytes()))
    }

    @Test
    fun emptyMasterThrowsAndLeavesNoMoov() {
        val master = tempFile("master")
        val output = tempFile("out")
        var threw = false
        try {
            RecordDevelopMovConverter.convertWithAudit(
                master, output, sidecar = null,
                options = RecordDevelopConversionOptions(),
                sourceKind = RecordDevelopMasterKind.SDR,
                readerFactory = readerFactoryOf(emptyList(), 12, 4)
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("empty master must throw", threw)
        assertFalse("failed conversion deletes the partial output", output.exists())
    }

    @Test
    fun variableSourcePtsAreZeroBasedAndPreservedInTheFinalMovTimeline() {
        val width = 12
        val height = 4
        val sourcePts = listOf(7_000_000L, 7_040_000L, 7_083_000L)
        val timedFrames = frames(width, height, sourcePts.size).mapIndexed { index, frame ->
            frame.copy(presentationTimeUs = sourcePts[index])
        }
        val output = tempFile("variable_pts_out")

        val audit = RecordDevelopMovConverter.convertWithAudit(
            master = tempFile("variable_pts_master"),
            output = output,
            sidecar = null,
            options = RecordDevelopConversionOptions(
                snceAppearanceStrength = 0f,
                enhance = RecordDevelopEnhanceOptions.DISABLED
            ),
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = readerFactoryOf(timedFrames, width, height, fps = 25)
        )

        assertEquals(126_000L, audit.presentationDurationUs)
        val report = QuickTimeMovArtifactInspector.inspect(output).requirePass()
        assertEquals(3L, report.videoTrack?.sampleCount)
        assertEquals(126_000L, report.videoTrack?.mediaDurationUnits)
    }

    @Test
    fun sourceTrackDurationPreservesTheFinalVariableCadenceSampleInMov() {
        val width = 12
        val height = 4
        val sourcePts = List(20) { index -> index * 120_000L }
        val timedFrames = frames(width, height, sourcePts.size).mapIndexed { index, frame ->
            frame.copy(presentationTimeUs = sourcePts[index])
        }
        val output = tempFile("short_final_sample_out")

        val audit = RecordDevelopMovConverter.convertWithAudit(
            master = tempFile("short_final_sample_master"),
            output = output,
            sidecar = null,
            options = RecordDevelopConversionOptions(
                snceAppearanceStrength = 0f,
                filmPrintStrength = 0f,
                enhance = RecordDevelopEnhanceOptions.DISABLED
            ),
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = readerFactoryOf(
                frameList = timedFrames,
                width = width,
                height = height,
                fps = 9,
                sourceDurationUs = 2_320_000L
            )
        )

        assertEquals(2_320_000L, audit.presentationDurationUs)
        val report = QuickTimeMovArtifactInspector.inspect(output).requirePass()
        assertEquals(20L, report.videoTrack?.sampleCount)
        assertEquals(2_320_000L, report.videoTrack?.mediaDurationUnits)
        assertEquals(2L, report.videoTrack?.sttsEntryCount)
    }

    @Test
    fun undersizedBudgetFailsBeforeReaderMaterializesAFrame() {
        val master = tempFile("undersized_budget_master")
        val output = tempFile("undersized_budget_out")
        var readCalls = 0
        val reader = object : RecordDevelopP010FrameReader {
            override val format = RecordDevelopDecodedFormat(width = 12, height = 4, fps = 30)

            override fun readNextFrame(): RecordDevelopP010Frame? {
                readCalls += 1
                return frames(width = 12, height = 4, count = 1).single()
            }

            override fun close() = Unit
        }

        val failure = assertThrows(IllegalArgumentException::class.java) {
            RecordDevelopMovConverter.convertWithAudit(
                master = master,
                output = output,
                sidecar = null,
                options = RecordDevelopConversionOptions(memoryBudgetBytes = 256L),
                sourceKind = RecordDevelopMasterKind.SDR,
                readerFactory = { reader }
            )
        }

        assertTrue(failure.message.orEmpty().contains("cannot hold one resident frame"))
        assertEquals("budget validation must happen before decode allocates P010", 0, readCalls)
    }

    private fun containsFourCc(bytes: ByteArray, fourCc: String): Boolean {
        val tag = fourCc.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..bytes.size - tag.size) {
            for (j in tag.indices) if (bytes[i + j] != tag[j]) continue@outer
            return true
        }
        return false
    }

    @Test
    fun masterIsReadOnlyTamperEvidence() {
        val master = tempFile("master").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val before = master.readBytes()
        val output = tempFile("out")
        val audit = RecordDevelopMovConverter.convertWithAudit(
            master, output, sidecar = null,
            options = RecordDevelopConversionOptions(),
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = readerFactoryOf(frames(12, 4, 3), 12, 4)
        )
        assertTrue("master bytes unchanged", before.contentEquals(master.readBytes()))
        assertTrue("audit reports master unchanged", audit.inputMasterHashUnchanged)
        assertTrue("NIV99 is enabled by default", audit.niv99Applied)
        assertEquals("RECORD_DEVELOP_SDR", audit.niv99Route)
        assertEquals("true", audit.niv99PixelEvidence["niv99OfflinePixelProcessingProven"])
        assertEquals("3", audit.niv99PixelEvidence["niv99OfflinePixelProcessedFrames"])
    }

    @Test
    fun ndrSidecarDerivesPqBt2020Delivery() {
        val audit = RecordDevelopMovConverter.convertWithAudit(
            tempFile("master"), tempFile("out"),
            sidecar = sidecar(DevelopColorSpace.REC2020, DevelopColorTransfer.HLG),
            options = RecordDevelopConversionOptions(),
            sourceKind = RecordDevelopMasterKind.NDR,
            readerFactory = readerFactoryOf(frames(12, 4, 2), 12, 4)
        )
        assertEquals(DevelopTarget.NDR_PQ_BT2020, audit.developTarget)
        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT2020, audit.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084, audit.transferCharacteristics)
        assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL, audit.matrixCoefficients)
        assertTrue("NDR attaches HDR10 mastering metadata", audit.hdrMasteringAttached)
        // R6: the genuine-HDR default delivers NDR through the canonical PQ engine, NOT the ACES SDR-fit ODT.
        assertTrue("NDR develops as genuine HDR (canonical PQ delivery)", audit.ndrGenuineHdr)
        assertFalse("genuine-HDR NDR bypasses the ACES SDR-fit ODT", audit.acesToneMap)
        assertEquals("NDR suppresses creative film LUT", 0f, audit.filmPrintStrength)
        assertTrue(audit.niv99Applied)
        assertEquals("RECORD_DEVELOP_NDR", audit.niv99Route)
        assertEquals("true", audit.niv99PixelEvidence["niv99OfflinePixelProcessingProven"])
        assertEquals("2", audit.niv99PixelEvidence["niv99OfflinePixelProcessedFrames"])
    }

    @Test
    fun legacyNdrDeliveryStillUsesAcesSdrFitAnchor() {
        // The reversible A/B fallback: LEGACY_SDR_ANCHORED restores the pre-R6 ACES SDR-fit ODT + 203-anchor.
        val audit = RecordDevelopMovConverter.convertWithAudit(
            tempFile("master"), tempFile("out"),
            sidecar = sidecar(DevelopColorSpace.REC2020, DevelopColorTransfer.HLG),
            options = RecordDevelopConversionOptions(ndrDelivery = RecordDevelopNdrDelivery.LEGACY_SDR_ANCHORED),
            sourceKind = RecordDevelopMasterKind.NDR,
            readerFactory = readerFactoryOf(frames(12, 4, 2), 12, 4)
        )
        assertEquals(DevelopTarget.NDR_PQ_BT2020, audit.developTarget)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084, audit.transferCharacteristics)
        assertFalse("legacy NDR is not genuine-HDR", audit.ndrGenuineHdr)
        assertTrue("legacy NDR keeps the ACES SDR-fit ODT", audit.acesToneMap)
    }

    @Test
    fun genuineHdrAndLegacyNdrProduceDifferentBytesButSdrIsUntouched() {
        // The R6 mode switch must actually change NDR pixels (genuine HDR ≠ 203-anchored) — and must NOT
        // touch the SDR develop path at all (no-downgrade: SDR bytes identical regardless of the NDR knob).
        val ndrGenuine = RecordDevelopMovConverter.convertWithAudit(
            tempFile("m1"), tempFile("o1"),
            sidecar = sidecar(DevelopColorSpace.REC2020, DevelopColorTransfer.HLG),
            options = RecordDevelopConversionOptions(ndrDelivery = RecordDevelopNdrDelivery.GENUINE_HDR),
            sourceKind = RecordDevelopMasterKind.NDR,
            readerFactory = readerFactoryOf(frames(16, 8, 3), 16, 8)
        )
        val ndrLegacy = RecordDevelopMovConverter.convertWithAudit(
            tempFile("m2"), tempFile("o2"),
            sidecar = sidecar(DevelopColorSpace.REC2020, DevelopColorTransfer.HLG),
            options = RecordDevelopConversionOptions(ndrDelivery = RecordDevelopNdrDelivery.LEGACY_SDR_ANCHORED),
            sourceKind = RecordDevelopMasterKind.NDR,
            readerFactory = readerFactoryOf(frames(16, 8, 3), 16, 8)
        )
        assertFalse(
            "genuine-HDR and legacy NDR must differ in pixels",
            File(ndrGenuine.outputPath).readBytes().contentEquals(File(ndrLegacy.outputPath).readBytes())
        )

        // Same two clips developed as SDR (sidecar=null) must be byte-identical whatever the NDR knob says.
        val sdrA = RecordDevelopMovConverter.convertWithAudit(
            tempFile("m3"), tempFile("o3"), sidecar = null,
            options = RecordDevelopConversionOptions(ndrDelivery = RecordDevelopNdrDelivery.GENUINE_HDR),
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = readerFactoryOf(frames(16, 8, 3), 16, 8)
        )
        val sdrB = RecordDevelopMovConverter.convertWithAudit(
            tempFile("m4"), tempFile("o4"), sidecar = null,
            options = RecordDevelopConversionOptions(ndrDelivery = RecordDevelopNdrDelivery.LEGACY_SDR_ANCHORED),
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = readerFactoryOf(frames(16, 8, 3), 16, 8)
        )
        assertFalse("SDR develop must not be genuine-HDR", sdrA.ndrGenuineHdr)
        assertTrue(
            "SDR develop bytes must be independent of the NDR delivery knob",
            File(sdrA.outputPath).readBytes().contentEquals(File(sdrB.outputPath).readBytes())
        )
    }

    @Test
    fun sdrRec2020SidecarDerivesSdrRec2020() {
        val audit = RecordDevelopMovConverter.convertWithAudit(
            tempFile("master"), tempFile("out"),
            sidecar = sidecar(DevelopColorSpace.REC2020, DevelopColorTransfer.SDR),
            options = RecordDevelopConversionOptions(),
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = readerFactoryOf(frames(12, 4, 2), 12, 4)
        )
        assertEquals(DevelopTarget.SDR_REC2020, audit.developTarget)
        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT2020, audit.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_BT709, audit.transferCharacteristics)
        assertFalse("SDR attaches no HDR mastering", audit.hdrMasteringAttached)
    }

    @Test
    fun r210IsFullRangeBt709ButV210IsLimitedWidenedBt2020() {
        val r210 = RecordDevelopMovConverter.convertWithAudit(
            tempFile("master"), tempFile("out_r"),
            sidecar = null, // SDR Rec.709
            options = RecordDevelopConversionOptions(outputFormat = RecordDevelopOutputFormat.R210_444),
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = readerFactoryOf(frames(12, 4, 2), 12, 4)
        )
        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT709, r210.colorPrimaries)
        assertTrue("r210 is full-range RGB", r210.fullRange)

        val v210 = RecordDevelopMovConverter.convertWithAudit(
            tempFile("master"), tempFile("out_v"),
            sidecar = null, // SDR Rec.709 ⇒ widened to BT.2020 for a self-consistent v210
            options = RecordDevelopConversionOptions(outputFormat = RecordDevelopOutputFormat.V210_422),
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = readerFactoryOf(frames(12, 4, 2), 12, 4)
        )
        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT2020, v210.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL, v210.matrixCoefficients)
        assertFalse("v210 is limited-range Y'CbCr", v210.fullRange)
    }

    @Test
    fun convertWritesCertificateButConvertWithAuditDoesNot() {
        val masterA = tempFile("masterA")
        val outputA = tempFile("outA")
        RecordDevelopMovConverter.convertWithAudit(
            masterA, outputA, sidecar = null,
            options = RecordDevelopConversionOptions(),
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = readerFactoryOf(frames(12, 4, 2), 12, 4)
        )
        assertFalse("convertWithAudit writes no certificate",
            RecordDevelopMovConverter.certificateSidecarFile(outputA).exists())

        val masterB = tempFile("masterB")
        val outputB = tempFile("outB")
        RecordDevelopMovConverter.convert(
            masterB, outputB, sidecar = null,
            options = RecordDevelopConversionOptions(),
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = readerFactoryOf(frames(12, 4, 2), 12, 4)
        )
        val cert = RecordDevelopMovConverter.certificateSidecarFile(outputB)
        temps += cert
        assertTrue("convert writes a certificate", cert.exists())
        assertTrue("certificate carries frame count", cert.readText().contains("frameCount=2"))
    }
}
