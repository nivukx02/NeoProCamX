package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

class Npv10DirectPlaybackRendererTest {

    @Test
    fun directBufferPlaybackMatchesHeapReferenceAndReusesArgbStorage() {
        val payload = ByteArray(12)
        // Four 10-bit luma values followed by one interleaved Cb/Cr pair, all P010 MSB-justified.
        listOf(64, 320, 640, 940, 512, 512).forEachIndexed { index, code ->
            val value = code shl 6
            payload[index * 2] = (value and 0xFF).toByte()
            payload[index * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        val header = Npv10FileHeader(
            version = Npv10ContainerPolicy.VERSION,
            width = 2,
            height = 2,
            fps = 30,
            bitDepth = 10,
            metadata = "",
            metadataValues = emptyMap(),
            payloadFormat = Npv10PayloadFormat.P010_LE,
            sourceFormat = "ycbcr_p010",
            sourceBitDepth = 10,
            storageBitDepth = 10,
            exactSelectedResolution = true
        )
        val heap = Npv10ThumbnailRenderer.renderFrameForPlayback(
            header,
            Npv10FrameRecord(1L, 2, 2, payload),
            maxLongEdge = 2
        )
        val reusable = IntArray(4)
        val direct = Npv10ThumbnailRenderer.renderDirectFrameForPlayback(
            header,
            Npv10DirectFrameRecord(
                timestampNs = 1L,
                width = 2,
                height = 2,
                payload = ByteBuffer.allocateDirect(payload.size)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .apply {
                        put(payload)
                        flip()
                    }
            ),
            maxLongEdge = 2,
            reusableArgb = reusable
        )

        assertEquals(heap, direct)
        assertSame(reusable, direct.argb)
    }

    @Test
    fun playbackColorLookupPreservesTheReferenceBt709Result() {
        val codes = intArrayOf(-100, 0, 63, 64, 128, 511, 512, 700, 940, 1_023, 1_200)
        for (y in codes) {
            for (cb in codes) {
                for (cr in codes) {
                    assertEquals(referenceYuvToArgb(y, cb, cr), Npv10ThumbnailRenderer.yuvToArgb(y, cb, cr))
                }
            }
        }
    }

    @Test
    fun legacyMasterWithoutPixelAuthorityMapsDeclaredPqDiffuseWhiteToCalibratedSdrMonitorWhite() {
        val header = playbackHeader(
            mapOf(
                "transfer" to "st2084/pq",
                "primaries" to "bt.2020",
                "matrix" to "bt.2020-ncl",
                "range" to "limited"
            )
        )
        val plan = Npv10PlaybackColorPolicy.resolve(header)
        val pqCode = pqEncodeFromNits(203.0)
        val yCode = (64.0 + pqCode * 876.0).roundToInt()

        val argb = Npv10PlaybackColorPolicy.yuvToDisplayArgb(plan, yCode, 512, 512)
        val red = argb ushr 16 and 0xFF
        val green = argb ushr 8 and 0xFF
        val blue = argb and 0xFF

        assertEquals(Npv10PlaybackColorPolicy.Transfer.PQ, plan.transfer)
        assertEquals(Npv10PlaybackColorPolicy.Primaries.BT2020, plan.primaries)
        assertTrue(red in 201..207)
        assertTrue(green in 201..207)
        assertTrue(blue in 201..207)
        assertTrue(maxOf(red, green, blue) - minOf(red, green, blue) <= 1)
    }

    @Test
    fun requestedPqIntentWithAuthoritativeBt709CarrierDecodesStoredPixelsAsBt709() {
        val plan = Npv10PlaybackColorPolicy.resolve(
            playbackHeader(
                mapOf(
                    "transfer" to "st2084/pq",
                    "primaries" to "bt.2020",
                    "matrix" to "bt.2020-ncl",
                    "range" to "limited",
                    "pixeltransferencoding" to "bt.709",
                    "pixeltransferapplied" to "true",
                    "pixeltransferauthority" to "CAMERA2_STANDARD_PROFILE"
                )
            )
        )

        assertEquals(Npv10PlaybackColorPolicy.Transfer.SDR, plan.transfer)
        assertEquals(Npv10PlaybackColorPolicy.Primaries.BT709, plan.primaries)
        assertEquals(Npv10PlaybackColorPolicy.Matrix.BT709, plan.matrix)
        assertTrue(plan.directBt709Sdr)
        assertEquals(
            Npv10ThumbnailRenderer.yuvToArgb(731, 409, 618),
            Npv10PlaybackColorPolicy.yuvToDisplayArgb(plan, 731, 409, 618)
        )
    }

    @Test
    fun completeImageDataspaceAuthorityRepresentsBt2020SdrCarrier() {
        val plan = Npv10PlaybackColorPolicy.resolve(
            playbackHeader(
                mapOf(
                    "transfer" to "bt.709",
                    "primaries" to "bt.709",
                    "matrix" to "bt.709",
                    "pixeltransferencoding" to "bt.709",
                    "pixeltransferapplied" to "true",
                    "pixeltransferauthority" to "IMAGE_DATASPACE",
                    "pixelcolorprimaries" to "bt.2020",
                    "pixelcolormatrix" to "bt.2020-ncl",
                    "pixelcolorrange" to "limited",
                    "pixelcolorapplied" to "true",
                    "pixelcolorauthority" to "IMAGE_DATASPACE"
                )
            )
        )

        assertEquals(Npv10PlaybackColorPolicy.Transfer.SDR, plan.transfer)
        assertEquals(Npv10PlaybackColorPolicy.Primaries.BT2020, plan.primaries)
        assertEquals(Npv10PlaybackColorPolicy.Matrix.BT2020, plan.matrix)
        assertFalse(plan.fullRange)
        assertFalse(plan.directBt709Sdr)
        assertTrue(plan.pixelAuthorityValid)
        assertEquals("VERIFIED_COMPLETE_PIXEL_COLOR_AUTHORITY", plan.pixelAuthorityReason)
    }

    @Test
    fun bt2020SdrClaimFromStandardProfileAuthorityFailsClosed() {
        val plan = Npv10PlaybackColorPolicy.resolve(
            playbackHeader(
                mapOf(
                    "pixeltransferencoding" to "bt.709",
                    "pixeltransferapplied" to "true",
                    "pixeltransferauthority" to "CAMERA2_STANDARD_PROFILE",
                    "pixelcolorprimaries" to "bt.2020",
                    "pixelcolormatrix" to "bt.2020-ncl",
                    "pixelcolorrange" to "limited",
                    "pixelcolorapplied" to "true",
                    "pixelcolorauthority" to "CAMERA2_STANDARD_PROFILE"
                )
            )
        )

        assertEquals(Npv10PlaybackColorPolicy.Primaries.BT709, plan.primaries)
        assertEquals(Npv10PlaybackColorPolicy.Matrix.BT709, plan.matrix)
        assertTrue(plan.directBt709Sdr)
        assertFalse(plan.pixelAuthorityValid)
        assertEquals("UNVERIFIED_PIXEL_COLOR_AUTHORITY", plan.pixelAuthorityReason)
    }

    @Test
    fun incompleteImageDataspaceColorPacketFailsClosedWithReason() {
        val plan = Npv10PlaybackColorPolicy.resolve(
            playbackHeader(
                mapOf(
                    "pixeltransferencoding" to "bt.709",
                    "pixeltransferapplied" to "true",
                    "pixeltransferauthority" to "IMAGE_DATASPACE",
                    "pixelcolorprimaries" to "bt.2020",
                    "pixelcolormatrix" to "bt.2020-ncl",
                    "pixelcolorapplied" to "true",
                    "pixelcolorauthority" to "IMAGE_DATASPACE"
                )
            )
        )

        assertTrue(plan.directBt709Sdr)
        assertFalse(plan.pixelAuthorityValid)
        assertEquals("UNKNOWN_PIXEL_RANGE", plan.pixelAuthorityReason)
    }

    @Test
    fun conflictingTransferAliasesCannotOverrideCanonicalPixelAuthority() {
        val plan = Npv10PlaybackColorPolicy.resolve(
            playbackHeader(
                completeBt2020SdrPixelAuthority() + mapOf(
                    "payloadtransferencoding" to "ST.2084"
                )
            )
        )

        assertTrue(plan.directBt709Sdr)
        assertFalse(plan.pixelAuthorityValid)
        assertEquals("CONFLICTING_PIXEL_TRANSFER_ALIASES", plan.pixelAuthorityReason)
    }

    @Test
    fun conflictingGamutAliasesFailClosedInsteadOfTrustingFirstValue() {
        val plan = Npv10PlaybackColorPolicy.resolve(
            playbackHeader(
                completeBt2020SdrPixelAuthority() + mapOf(
                    "payloadcolorprimaries" to "BT.709"
                )
            )
        )

        assertEquals(Npv10PlaybackColorPolicy.Primaries.BT709, plan.primaries)
        assertEquals(Npv10PlaybackColorPolicy.Matrix.BT709, plan.matrix)
        assertFalse(plan.pixelAuthorityValid)
        assertEquals("CONFLICTING_PIXEL_PRIMARIES_ALIASES", plan.pixelAuthorityReason)
    }

    @Test
    fun requestedSdrIntentWithAuthoritativeHlgCarrierDecodesStoredPixelsAsBt2020Hlg() {
        val plan = Npv10PlaybackColorPolicy.resolve(
            playbackHeader(
                mapOf(
                    "transfer" to "bt.709/oetf",
                    "primaries" to "bt.709",
                    "matrix" to "bt.709",
                    "pixeltransferencoding" to Bt2100TransportPolicy.TRANSFER_HLG,
                    "pixeltransferapplied" to "true",
                    "pixeltransferauthority" to "CAMERA2_DYNAMIC_RANGE_PROFILE"
                )
            )
        )

        assertEquals(Npv10PlaybackColorPolicy.Transfer.HLG, plan.transfer)
        assertEquals(Npv10PlaybackColorPolicy.Primaries.BT2020, plan.primaries)
        assertEquals(Npv10PlaybackColorPolicy.Matrix.BT2020, plan.matrix)
        assertFalse(plan.directBt709Sdr)
    }

    @Test
    fun requestedSdrIntentWithAuthoritativePqCarrierDecodesStoredPixelsAsBt2020Pq() {
        val plan = Npv10PlaybackColorPolicy.resolve(
            playbackHeader(
                mapOf(
                    "transfer" to "bt.709/oetf",
                    "primaries" to "bt.709",
                    "matrix" to "bt.709",
                    "pixeltransferencoding" to Bt2100TransportPolicy.TRANSFER_PQ,
                    "pixeltransferapplied" to "true",
                    "pixeltransferauthority" to "CAMERA2_DYNAMIC_RANGE_PROFILE"
                )
            )
        )

        assertEquals(Npv10PlaybackColorPolicy.Transfer.PQ, plan.transfer)
        assertEquals(Npv10PlaybackColorPolicy.Primaries.BT2020, plan.primaries)
        assertEquals(Npv10PlaybackColorPolicy.Matrix.BT2020, plan.matrix)
        assertFalse(plan.directBt709Sdr)
    }

    @Test
    fun contradictoryPixelAuthorityFailsClosedToLimitedBt709() {
        val plan = Npv10PlaybackColorPolicy.resolve(
            playbackHeader(
                mapOf(
                    "transfer" to "st2084/pq",
                    "primaries" to "bt.2020",
                    "matrix" to "bt.2020-ncl",
                    "range" to "full",
                    "pixeltransferencoding" to "ST.2084",
                    "pixeltransferapplied" to "true",
                    "pixeltransferauthority" to "CAMERA2_STANDARD_PROFILE"
                )
            )
        )

        assertEquals(Npv10PlaybackColorPolicy.Transfer.SDR, plan.transfer)
        assertEquals(Npv10PlaybackColorPolicy.Primaries.BT709, plan.primaries)
        assertEquals(Npv10PlaybackColorPolicy.Matrix.BT709, plan.matrix)
        assertFalse(plan.fullRange)
        assertTrue(plan.directBt709Sdr)
    }

    @Test
    fun exactSourceWithUnknownHalDataspaceCannotBeRelabelledByRequestedHdrIntent() {
        val unverifiedSourcePacket = mapOf(
            "pixeltransferencoding" to "",
            "pixeltransferapplied" to "false",
            "pixeltransferauthority" to "UNVERIFIED",
            "pixelcolorprimaries" to "",
            "pixelcolormatrix" to "",
            "pixelcolorrange" to "",
            "pixelcolorapplied" to "false",
            "pixelcolorauthority" to "UNVERIFIED",
            "pixelcolorauthoritystatus" to "SOURCE_VERIFIED_COLOR_UNSPECIFIED",
            "pixelcolorauthorityreason" to "UNKNOWN_IMAGE_DATASPACE",
            "pixelsourceidentityverified" to "true",
            "pixelsourceauthority" to "CAMERA2_EXACT_TIMESTAMP_JOIN"
        )
        val plan = Npv10PlaybackColorPolicy.resolve(
            playbackHeader(
                mapOf(
                    "transfer" to "st2084/pq",
                    "primaries" to "bt.2020",
                    "matrix" to "bt.2020-ncl"
                ) + unverifiedSourcePacket
            )
        )

        assertEquals(Npv10PlaybackColorPolicy.Transfer.SDR, plan.transfer)
        assertEquals(Npv10PlaybackColorPolicy.Primaries.BT709, plan.primaries)
        assertEquals(Npv10PlaybackColorPolicy.Matrix.BT709, plan.matrix)
        assertFalse(plan.fullRange)
        assertTrue(plan.directBt709Sdr)
    }

    @Test
    fun partialPixelAuthorityWithoutSourceAuthorityFailsClosedToLimitedBt709() {
        val plan = Npv10PlaybackColorPolicy.resolve(
            playbackHeader(
                mapOf(
                    "transfer" to "st2084/pq",
                    "primaries" to "bt.2020",
                    "matrix" to "bt.2020-ncl",
                    "pixeltransferencoding" to "ST.2084",
                    "pixeltransferapplied" to "true"
                )
            )
        )

        assertEquals(Npv10PlaybackColorPolicy.Transfer.SDR, plan.transfer)
        assertEquals(Npv10PlaybackColorPolicy.Primaries.BT709, plan.primaries)
        assertEquals(Npv10PlaybackColorPolicy.Matrix.BT709, plan.matrix)
        assertTrue(plan.directBt709Sdr)
    }

    @Test
    fun metadataAwarePlaybackViewsNeoLogInsteadOfTreatingLogCodeAsBt709Luma() {
        val header = playbackHeader(
            mapOf(
                "transfer" to "n-log",
                "primaries" to "bt.2020",
                "matrix" to "bt.2020-ncl",
                "normalizedlogversion" to "n-log cinema"
            )
        )
        val plan = Npv10PlaybackColorPolicy.resolve(header)
        val gray18LogCode = (64.0 + 0.5 * 876.0).roundToInt()

        val viewed = Npv10PlaybackColorPolicy.yuvToDisplayArgb(
            plan,
            gray18LogCode,
            512,
            512
        )
        val viewedGray = viewed ushr 16 and 0xFF
        val incorrectlyUnviewed = Npv10ThumbnailRenderer.yuvToArgb(gray18LogCode, 512, 512)
        val unviewedGray = incorrectlyUnviewed ushr 16 and 0xFF

        assertEquals(Npv10PlaybackColorPolicy.Transfer.NEO_LOG, plan.transfer)
        assertEquals(com.neoproc.neoproccamx.core.NeoLogCurve.Variant.CINEMA, plan.neoLogVariant)
        assertTrue(viewedGray in 105..135)
        assertTrue(viewedGray != unviewedGray)
    }

    @Test
    fun explicitLimitedBt709MetadataRetainsTheExactFastPath() {
        val plan = Npv10PlaybackColorPolicy.resolve(
            playbackHeader(
                mapOf(
                    "transfer" to "bt.709/oetf",
                    "primaries" to "bt.709",
                    "matrix" to "bt.709",
                    "range" to "limited"
                )
            )
        )

        assertTrue(plan.directBt709Sdr)
        assertEquals(
            Npv10ThumbnailRenderer.yuvToArgb(731, 409, 618),
            if (plan.directBt709Sdr) {
                Npv10ThumbnailRenderer.yuvToArgb(731, 409, 618)
            } else {
                Npv10PlaybackColorPolicy.yuvToDisplayArgb(plan, 731, 409, 618)
            }
        )
    }

    @Test
    fun metadataAwarePerPixelColorPathRemainsAllocationFree() {
        val source = java.io.File(
            "src/main/java/com/neoproc/neoproccamx/recording/Npv10PlaybackColorPolicy.kt"
        ).readText()
        val hotPath = source.substringAfter("fun yuvToDisplayArgb(")
            .substringBefore("private fun classifyTransfer")

        assertFalse(hotPath.contains("floatArrayOf("))
        assertFalse(hotPath.contains("FloatArray("))
        assertFalse(hotPath.contains("arrayOf("))
    }

    private fun playbackHeader(metadata: Map<String, String>): Npv10FileHeader = Npv10FileHeader(
        version = Npv10ContainerPolicy.VERSION,
        width = 2,
        height = 2,
        fps = 30,
        bitDepth = 10,
        metadata = metadata.entries.joinToString("\n") { (key, value) -> "$key=$value" },
        metadataValues = metadata,
        payloadFormat = Npv10PayloadFormat.P010_LE,
        sourceFormat = "ycbcr_p010",
        sourceBitDepth = 10,
        storageBitDepth = 10,
        exactSelectedResolution = true
    )

    private fun completeBt2020SdrPixelAuthority(): Map<String, String> = mapOf(
        "pixeltransferencoding" to "BT.709",
        "pixeltransferapplied" to "true",
        "pixeltransferauthority" to "IMAGE_DATASPACE",
        "pixelcolorprimaries" to "BT.2020",
        "pixelcolormatrix" to "BT.2020-NCL",
        "pixelcolorrange" to "limited",
        "pixelcolorapplied" to "true",
        "pixelcolorauthority" to "IMAGE_DATASPACE"
    )

    private fun pqEncodeFromNits(nits: Double): Double {
        val normalized = (nits / 10_000.0).coerceIn(0.0, 1.0)
        val m1 = 2610.0 / 16384.0
        val m2 = 2523.0 / 32.0
        val c1 = 3424.0 / 4096.0
        val c2 = 2413.0 / 128.0
        val c3 = 2392.0 / 128.0
        val powered = normalized.pow(m1)
        return ((c1 + c2 * powered) / (1.0 + c3 * powered)).pow(m2)
    }

    private fun referenceYuvToArgb(y10: Int, cb10: Int, cr10: Int): Int {
        val yl = (y10.coerceIn(0, 1_023) - 64).toFloat() / 876f
        val uf = (cb10.coerceIn(0, 1_023) - 512).toFloat() / 896f
        val vf = (cr10.coerceIn(0, 1_023) - 512).toFloat() / 896f
        val r8 = ((yl + 1.5748f * vf).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val g8 = ((yl - 0.1873f * uf - 0.4681f * vf).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val b8 = ((yl + 1.8556f * uf).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
    }
}
