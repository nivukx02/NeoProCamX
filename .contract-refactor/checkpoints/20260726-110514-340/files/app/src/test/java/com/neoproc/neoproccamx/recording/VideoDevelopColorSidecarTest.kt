package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32

/**
 * Golden contract for [VideoDevelopColorSidecar] — the develop-colour metadata carrier. Locks the
 * binary round-trip (incl. the working matrix + SDR/NDR enums), CRC rejection of corruption, the sibling
 * file naming, and the absent/unreadable → null fallback the offline develop relies on for un-tagged clips.
 */
class VideoDevelopColorSidecarTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample() = VideoDevelopColorSidecar.VideoDevelopColorMetadata(
        width = 4096,
        height = 3072,
        storageBitDepth = 10,
        fps = 30,
        whiteBalanceR = 1.9f,
        whiteBalanceGr = 1.0f,
        whiteBalanceGb = 1.0f,
        whiteBalanceB = 1.7f,
        workingRgbToXyz = floatArrayOf(
            0.4124f, 0.3576f, 0.1805f,
            0.2126f, 0.7152f, 0.0722f,
            0.0193f, 0.1192f, 0.9505f
        ),
        adaptingLuminance = 120f,
        cctKelvin = 5200f,
        tint = 3f,
        colorSpace = VideoDevelopColorSidecar.DevelopColorSpace.REC709,
        colorTransfer = VideoDevelopColorSidecar.DevelopColorTransfer.SDR,
        colorProfileTag = "rec709",
        detailProfileTag = "NATURAL_DETAIL",
        orientationHintDegrees = 270
    )

    @Test
    fun encodeDecodeRoundTrip() {
        val meta = sample()
        val decoded = VideoDevelopColorSidecar.decode(VideoDevelopColorSidecar.encode(meta))
        assertEquals(meta, decoded)
        assertArrayEquals(meta.workingRgbToXyz, decoded.workingRgbToXyz, 0f)
        assertEquals("NATURAL_DETAIL", decoded.detailProfileTag)
        assertEquals(270, decoded.orientationHintDegrees)
    }

    @Test
    fun ndrSpaceAndTransferRoundTrip() {
        val meta = sample().copy(
            colorSpace = VideoDevelopColorSidecar.DevelopColorSpace.REC2020,
            colorTransfer = VideoDevelopColorSidecar.DevelopColorTransfer.HLG,
            colorProfileTag = "hlg"
        )
        val decoded = VideoDevelopColorSidecar.decode(VideoDevelopColorSidecar.encode(meta))
        assertEquals(VideoDevelopColorSidecar.DevelopColorSpace.REC2020, decoded.colorSpace)
        assertEquals(VideoDevelopColorSidecar.DevelopColorTransfer.HLG, decoded.colorTransfer)
        assertEquals(meta, decoded)
    }

    @Test
    fun versionOneSidecarDefaultsMissingDetailProfile() {
        val versionThree = VideoDevelopColorSidecar.encode(
            sample().copy(detailProfileTag = "", orientationHintDegrees = 0)
        )
        val versionThreeBodySize = versionThree.size - java.lang.Long.BYTES
        // v3 ends in detailProfile UTF (2 bytes here) + orientation (4 bytes); v1 has neither.
        val versionOneBody = versionThree.copyOf(
            versionThreeBodySize - java.lang.Short.BYTES - java.lang.Integer.BYTES
        )
        versionOneBody[8] = 0
        versionOneBody[9] = 0
        versionOneBody[10] = 0
        versionOneBody[11] = 1
        val crc = CRC32().apply { update(versionOneBody) }.value
        val framed = ByteArrayOutputStream(versionOneBody.size + java.lang.Long.BYTES).also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(versionOneBody)
                out.writeLong(crc)
            }
        }.toByteArray()

        val decoded = VideoDevelopColorSidecar.decode(framed)

        assertEquals("", decoded.detailProfileTag)
        assertEquals(0, decoded.orientationHintDegrees)
        assertEquals(sample().colorProfileTag, decoded.colorProfileTag)
    }

    @Test
    fun crcMismatchIsRejected() {
        val bytes = VideoDevelopColorSidecar.encode(sample())
        bytes[20] = (bytes[20] + 1).toByte() // corrupt a body byte
        try {
            VideoDevelopColorSidecar.decode(bytes)
            fail("corrupt sidecar must be rejected")
        } catch (expected: VideoDevelopColorSidecar.SidecarFormatException) {
            // expected
        }
    }

    @Test
    fun writeThenReadSiblingRoundTrips() {
        val dir = tmp.newFolder("clip")
        val mov = File(dir, "DEVSDR_1000_video_rec709_4096x3072_30fps.mov").apply { writeBytes(ByteArray(8)) }
        val meta = sample()
        val written = VideoDevelopColorSidecar.write(mov, meta)
        assertEquals(mov.name + ".devcolor", written.name)
        assertEquals(meta, VideoDevelopColorSidecar.read(mov))
    }

    @Test
    fun readAbsentSidecarReturnsNull() {
        val dir = tmp.newFolder("clip")
        assertNull(VideoDevelopColorSidecar.read(File(dir, "none.mov")))
    }
}
