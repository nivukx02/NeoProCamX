package com.neoproc.neoproccamx.recording

import android.media.MediaFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class HevcColorMetadataRewriterTest {
    @Test
    fun rewritesQualcommMain10SpsFromPqHdrToLogSdr() {
        val config = annexB(VPS, SPS_PQ_MAIN10, PPS)

        val before = HevcColorMetadataRewriter.firstAnnexBSpsColorMetadata(config)
        assertNotNull(before)
        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT2020, before!!.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084, before.transferCharacteristics)
        assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL, before.matrixCoefficients)

        val rewritten = HevcColorMetadataRewriter.rewriteAnnexBColorMetadata(
            bytes = config,
            colorConfig = QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = false
            )
        )

        val after = HevcColorMetadataRewriter.firstAnnexBSpsColorMetadata(rewritten)
        assertNotNull(after)
        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT2020, after!!.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_BT709, after.transferCharacteristics)
        assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL, after.matrixCoefficients)
        assertEquals(false, after.fullRange)
    }

    @Test
    fun rewritesMain10SpsToRec709SdrFullRangeForProVideo() {
        val rewritten = HevcColorMetadataRewriter.rewriteAnnexBColorMetadata(
            bytes = annexB(VPS, SPS_PQ_MAIN10, PPS),
            colorConfig = QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT709,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT709,
                fullRange = true
            )
        )

        val after = HevcColorMetadataRewriter.firstAnnexBSpsColorMetadata(rewritten)
        assertNotNull(after)
        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT709, after!!.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_BT709, after.transferCharacteristics)
        assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT709, after.matrixCoefficients)
        assertEquals(true, after.fullRange)
    }

    @Test
    fun leavesNonHevcConfigUnchanged() {
        val bytes = byteArrayOf(1, 2, 3, 4)

        val rewritten = HevcColorMetadataRewriter.rewriteAnnexBColorMetadata(
            bytes = bytes,
            colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited()
        )

        assertArrayEquals(bytes, rewritten)
    }

    @Test
    fun injectsUserRegisteredItutT35SeiBeforeEveryVclNalUnit() {
        val metadata = HdrImagingMath.deriveNeoHdrDynamicMetadata(
            histogram = IntArray(256) { index -> if (index == 220) 4 else 1 },
            frameIndex = 3L,
            version = HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS
        )
        val payload = HdrImagingMath.buildNeoHdrItutT35Payload(metadata)
        val idrSlice = byteArrayOf((19 shl 1).toByte(), 0x01, 0x12, 0x34, 0x56)
        val trailingSlice = byteArrayOf((1 shl 1).toByte(), 0x01, 0x22, 0x33)
        val source = annexB(VPS, SPS_PQ_MAIN10, PPS, idrSlice, trailingSlice)

        val injected = HevcColorMetadataRewriter.injectUserRegisteredItutT35Sei(
            bytes = source,
            t35Payload = payload
        )

        assertEquals(2, HevcColorMetadataRewriter.countUserRegisteredItutT35Sei(injected))
        assertTrue(HevcColorMetadataRewriter.containsUserRegisteredItutT35Sei(injected, payload))
        assertTrue(
            injected.indexOfAnnexBNalType(HevcColorMetadataRewriter.NAL_TYPE_PREFIX_SEI) <
                injected.indexOfAnnexBNalType(19)
        )
    }

    @Test
    fun builtNeoHdrSeiNalUsesHevcPrefixSeiTypeAndPayloadTypeFour() {
        val metadata = HdrImagingMath.NeoHdrDynamicMetadata(
            frameIndex = 1L,
            version = HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION,
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
        val seiNal = HevcColorMetadataRewriter.buildUserRegisteredItutT35PrefixSeiNal(payload)

        assertEquals(HevcColorMetadataRewriter.NAL_TYPE_PREFIX_SEI, (seiNal[0].toInt() ushr 1) and 0x3F)
        assertEquals(4, seiNal[2].toInt() and 0xFF)
        assertTrue(HevcColorMetadataRewriter.containsUserRegisteredItutT35Sei(annexB(seiNal), payload))
    }

    @Test
    fun rewriterImprintsActiveOdtVuiTripletForEachColorMode() {
        odtTable().forEach { (name, cfg) ->
            val rewritten = HevcColorMetadataRewriter.rewriteAnnexBColorMetadata(
                bytes = annexB(VPS, SPS_PQ_MAIN10, PPS),
                colorConfig = cfg
            )
            val vui = HevcColorMetadataRewriter.firstAnnexBSpsColorMetadata(rewritten)
            assertNotNull("rewritten SPS must parse for $name", vui)
            assertEquals("primaries for $name", cfg.colorPrimaries, vui!!.colorPrimaries)
            assertEquals("transfer for $name", cfg.transferCharacteristics, vui.transferCharacteristics)
            assertEquals("matrix for $name", cfg.matrixCoefficients, vui.matrixCoefficients)
            assertEquals("fullRange for $name", cfg.fullRange, vui.fullRange)
        }
        // Spec anchor (prompt §4.1): PQ/HDR10 BT.2020 => transfer 16, primaries 9, matrix 9.
        val pq = HevcColorMetadataRewriter.firstAnnexBSpsColorMetadata(
            HevcColorMetadataRewriter.rewriteAnnexBColorMetadata(
                bytes = annexB(VPS, SPS_PQ_MAIN10, PPS),
                colorConfig = QuickTimeColorAtomConfig(
                    colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                    transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                    matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                    fullRange = false
                )
            )
        )!!
        assertEquals(16, pq.transferCharacteristics)
        assertEquals(9, pq.colorPrimaries)
        assertEquals(9, pq.matrixCoefficients)
    }

    @Test
    fun rewriteIsIdempotentForEachOdt() {
        odtTable().forEach { (name, cfg) ->
            val r1 = HevcColorMetadataRewriter.rewriteAnnexBColorMetadata(
                bytes = annexB(VPS, SPS_PQ_MAIN10, PPS),
                colorConfig = cfg
            )
            val r2 = HevcColorMetadataRewriter.rewriteAnnexBColorMetadata(bytes = r1, colorConfig = cfg)
            assertArrayEquals("double-rewrite must equal single for $name", r1, r2)
        }
    }

    @Test
    fun rewriteThenParseRoundTripsEachOdtVuiTriplet() {
        odtTable().forEach { (name, cfg) ->
            val rewritten = HevcColorMetadataRewriter.rewriteAnnexBColorMetadata(
                bytes = annexB(VPS, SPS_PQ_MAIN10, PPS),
                colorConfig = cfg
            )
            val intended = HevcVuiColorMetadata(
                fullRange = cfg.fullRange,
                colorPrimaries = cfg.colorPrimaries,
                transferCharacteristics = cfg.transferCharacteristics,
                matrixCoefficients = cfg.matrixCoefficients
            )
            assertEquals(
                "round-trip VUI for $name",
                intended,
                HevcColorMetadataRewriter.firstAnnexBSpsColorMetadata(rewritten)
            )
        }
    }

    @Test
    fun t35SeiBuildSurvivesEmulationPreventionRoundTrip() {
        // T.35 header (B5 00 3C 00 01) + body deliberately containing 00 00 03 / 00 00 00 /
        // 00 00 01 / 00 00 02 / trailing-00 runs so rbspToEbsp insertion AND nalToRbsp stripping
        // are exercised end-to-end. A reversible round-trip reconstructs the exact payload.
        val payload = hex("b5 00 3c 00 01 00 00 03 00 00 00 00 00 01 00 00 02 aa bb 00 00")
        val seiNal = HevcColorMetadataRewriter.buildUserRegisteredItutT35PrefixSeiNal(payload)
        assertTrue(
            "EBSP<->RBSP round-trip must reconstruct the exact T.35 payload",
            HevcColorMetadataRewriter.containsUserRegisteredItutT35Sei(annexB(seiNal), payload)
        )
    }

    @Test
    fun builtT35SeiParsesAsWellFormedUserRegisteredPrefixSei() {
        val payload = hex("b5 00 3c 00 01 11 22 33 00 00 03 44")
        val seiNal = HevcColorMetadataRewriter.buildUserRegisteredItutT35PrefixSeiNal(payload)
        assertEquals(
            HevcColorMetadataRewriter.NAL_TYPE_PREFIX_SEI,
            (seiNal[0].toInt() ushr 1) and 0x3F
        )
        assertEquals(4, seiNal[2].toInt() and 0xFF)
        assertEquals(1, HevcColorMetadataRewriter.countUserRegisteredItutT35Sei(annexB(seiNal)))
        // A different payload (valid T.35 header but different body) must be rejected.
        val differentPayload = hex("b5 00 3c 00 01 99 88 77")
        assertFalse(
            HevcColorMetadataRewriter.containsUserRegisteredItutT35Sei(annexB(seiNal), differentPayload)
        )
    }

    @Test
    fun nonSpsAndSeiNalsAreBytePreservedAfterSpsRewrite() {
        val idrSlice = byteArrayOf((19 shl 1).toByte(), 0x01, 0x12, 0x34, 0x56)
        val trailingSlice = byteArrayOf((1 shl 1).toByte(), 0x01, 0x22, 0x33)
        val source = annexB(VPS, SPS_PQ_MAIN10, PPS, idrSlice, trailingSlice)
        val rewritten = HevcColorMetadataRewriter.rewriteAnnexBColorMetadata(
            bytes = source,
            colorConfig = QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = false
            )
        )
        // VPS / PPS / VCL slice NAL byte runs survive verbatim; only the SPS VUI bits changed.
        assertTrue("VPS preserved", rewritten.containsSubarray(VPS))
        assertTrue("PPS preserved", rewritten.containsSubarray(PPS))
        assertTrue("IDR slice preserved", rewritten.containsSubarray(idrSlice))
        assertTrue("trailing slice preserved", rewritten.containsSubarray(trailingSlice))
        assertEquals(
            QuickTimeColorAtomPolicy.TRANSFER_BT709,
            HevcColorMetadataRewriter.firstAnnexBSpsColorMetadata(rewritten)!!.transferCharacteristics
        )
    }

    @Test
    fun rewriteReturnsIdenticalReferenceWhenVuiAlreadyMatchesOdt() {
        val source = annexB(VPS, SPS_PQ_MAIN10, PPS)
        val current = HevcColorMetadataRewriter.firstAnnexBSpsColorMetadata(source)!!
        val matchingCfg = QuickTimeColorAtomConfig(
            colorPrimaries = current.colorPrimaries,
            transferCharacteristics = current.transferCharacteristics,
            matrixCoefficients = current.matrixCoefficients,
            fullRange = current.fullRange
        )
        val rewritten = HevcColorMetadataRewriter.rewriteAnnexBColorMetadata(bytes = source, colorConfig = matchingCfg)
        // changed == false early-return (rewriter :38): byte-identical, the no-downgrade identity.
        assertArrayEquals(source, rewritten)
    }

    @Test
    fun sdrRec709OdtVuiMatchesQuickTimeColorAtomPolicyMapping() {
        val sdrEncoding = RecordingColorEncodingConfig(
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            requiresTenBit = false,
            main10Requested = false
        )
        val cfg = QuickTimeColorAtomPolicy.fromRecordingColorEncoding(sdrEncoding)
        // Rewriter consumes EXACTLY the config the live mapping selects (no drift).
        assertEquals(QuickTimeColorAtomConfig.rec709SdrLimited(), cfg)
        val vui = HevcColorMetadataRewriter.firstAnnexBSpsColorMetadata(
            HevcColorMetadataRewriter.rewriteAnnexBColorMetadata(
                bytes = annexB(VPS, SPS_PQ_MAIN10, PPS),
                colorConfig = cfg
            )
        )!!
        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT709, vui.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_BT709, vui.transferCharacteristics)
        assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT709, vui.matrixCoefficients)
        assertFalse(vui.fullRange)
    }

    @Test
    fun exportT35InjectionMethodNameResolvesForReflectiveExportCaller() {
        // Npv10MovConverter (s6-f05 export) invokes this method via reflection with a string-split
        // name; a rename would silently break export at runtime with no compile error. Lock it.
        val method = HevcColorMetadataRewriter::class.java.getMethod(
            "injectUserRegisteredItutT35Sei",
            ByteArray::class.java,
            ByteArray::class.java
        )
        assertNotNull(method)
    }

    private fun odtTable(): List<Pair<String, QuickTimeColorAtomConfig>> = listOf(
        "SDR_REC709" to QuickTimeColorAtomConfig(
            colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT709,
            transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
            matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT709,
            fullRange = false
        ),
        "LOG_SDR_BT2020" to QuickTimeColorAtomConfig(
            colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
            transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
            matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
            fullRange = false
        ),
        "PQ_HDR10_BT2020" to QuickTimeColorAtomConfig(
            colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
            transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
            matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
            fullRange = false
        ),
        // Forward-looking lock: the live look policy maps every HDR key to ST2084 today, but the
        // rewriter must imprint HLG correctly if a path ever selects COLOR_TRANSFER_HLG.
        "HLG_BT2020" to QuickTimeColorAtomConfig(
            colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
            transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_HLG,
            matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
            fullRange = false
        ),
        "DISPLAY_P3_SDR" to QuickTimeColorAtomConfig.displayP3SdrLimited()
    )

    private fun ByteArray.containsSubarray(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..(size - needle.size)) {
            for (i in needle.indices) {
                if (this[start + i] != needle[i]) continue@outer
            }
            return true
        }
        return false
    }

    private fun annexB(vararg nals: ByteArray): ByteArray =
        nals.fold(ByteArray(0)) { acc, nal ->
            acc + byteArrayOf(0, 0, 0, 1) + nal
        }

    private fun ByteArray.indexOfAnnexBNalType(nalType: Int): Int {
        var index = 0
        while (index <= size - 6) {
            val startCodeLength = when {
                index <= size - 4 &&
                    this[index] == 0.toByte() &&
                    this[index + 1] == 0.toByte() &&
                    this[index + 2] == 0.toByte() &&
                    this[index + 3] == 1.toByte() -> 4
                this[index] == 0.toByte() &&
                    this[index + 1] == 0.toByte() &&
                    this[index + 2] == 1.toByte() -> 3
                else -> 0
            }
            if (startCodeLength > 0) {
                val candidate = (this[index + startCodeLength].toInt() ushr 1) and 0x3F
                if (candidate == nalType) return index
            }
            index++
        }
        return -1
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
