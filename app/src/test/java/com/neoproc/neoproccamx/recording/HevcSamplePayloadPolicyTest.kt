package com.neoproc.neoproccamx.recording

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcSamplePayloadPolicyTest {
    @Test
    fun twoHundredFiftySixByteLengthPrefixedNalIsNeverMisclassifiedAsAnnexB() {
        val nal = ByteArray(256) { index -> (index * 17).toByte() }.apply {
            this[0] = (19 shl 1).toByte()
            this[1] = 0x01
        }
        val source = lengthPrefixed(nal)

        val payload = HevcSamplePayloadPolicy.convertForMovSample(
            sampleBytes = source,
            colorConfig = QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = false
            )
        )

        assertTrue("Valid MOV framing must remain byte-exact", source.contentEquals(payload))
    }

    @Test
    fun lengthPrefixedHdrSampleDropsHardwarePrefixSeiBeforeVcl() {
        val hardwareSei = byteArrayOf(
            (HevcColorMetadataRewriter.NAL_TYPE_PREFIX_SEI shl 1).toByte(),
            0x01,
            0x04,
            0x04,
            0x11,
            0x22,
            0x33,
            0x44,
            0x80.toByte()
        )
        val idrSlice = byteArrayOf((19 shl 1).toByte(), 0x01, 0x12, 0x34, 0x56)
        val source = lengthPrefixed(hardwareSei, idrSlice)

        val payload = HevcSamplePayloadPolicy.convertForMovSample(
            sampleBytes = source,
            colorConfig = QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = false
            )
        )

        assertEquals(19, firstLengthPrefixedNalType(payload))
        assertFalse(payload.containsLengthPrefixedNalType(HevcColorMetadataRewriter.NAL_TYPE_PREFIX_SEI))
        assertTrue(payload.containsLengthPrefixedNalType(19))
    }

    @Test
    fun annexBHotPathStreamsVclAndDropsHardwarePrefixSei() {
        val hardwareSei = byteArrayOf(
            (HevcColorMetadataRewriter.NAL_TYPE_PREFIX_SEI shl 1).toByte(),
            0x01,
            0x04,
            0x01,
            0x7F
        )
        val idrSlice = byteArrayOf((19 shl 1).toByte(), 0x01, 0x12, 0x34, 0x56)
        val annexB =
            byteArrayOf(0, 0, 0, 1) + hardwareSei +
                byteArrayOf(0, 0, 1) + idrSlice
        val output = ByteArrayOutputStream()

        val written = HevcSamplePayloadPolicy.writeForMovSample(
            sampleBuffer = ByteBuffer.allocateDirect(annexB.size).apply {
                put(annexB)
                flip()
            },
            colorConfig = QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = false
            )
        ) { segment ->
            val bytes = ByteArray(segment.remaining())
            segment.get(bytes)
            output.write(bytes)
        }

        val payload = output.toByteArray()
        assertEquals(payload.size, written)
        assertEquals(19, firstLengthPrefixedNalType(payload))
        assertFalse(payload.containsLengthPrefixedNalType(HevcColorMetadataRewriter.NAL_TYPE_PREFIX_SEI))
        assertTrue(payload.containsLengthPrefixedNalType(19))
    }

    @Test
    fun directBufferScannerPreservesNalBoundariesAcrossEightByteWords() {
        val first = ByteArray(29) { index -> (0x30 + index).toByte() }.apply {
            this[0] = (1 shl 1).toByte()
            this[1] = 0x01
            // Legal emulation-prevention sequence; it must not become a false start code.
            this[12] = 0
            this[13] = 0
            this[14] = 3
            this[15] = 1
        }
        val second = ByteArray(37) { index -> (0x60 + index).toByte() }.apply {
            this[0] = (19 shl 1).toByte()
            this[1] = 0x01
        }
        // The second prefix begins at byte 33, so both prefix forms and word-boundary state are used.
        val annexB = byteArrayOf(0, 0, 0, 1) + first + byteArrayOf(0, 0, 1) + second
        val output = ByteArrayOutputStream()

        val written = HevcSamplePayloadPolicy.writeForMovSample(
            sampleBuffer = ByteBuffer.allocateDirect(annexB.size).apply {
                put(annexB)
                flip()
            },
            colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited()
        ) { segment ->
            val bytes = ByteArray(segment.remaining())
            segment.get(bytes)
            output.write(bytes)
        }

        val expected = lengthPrefixed(first, second)
        assertEquals(expected.size, written)
        assertTrue(expected.contentEquals(output.toByteArray()))
    }

    private fun lengthPrefixed(vararg nals: ByteArray): ByteArray =
        nals.fold(ByteArray(0)) { acc, nal ->
            acc + byteArrayOf(
                (nal.size ushr 24).toByte(),
                (nal.size ushr 16).toByte(),
                (nal.size ushr 8).toByte(),
                nal.size.toByte()
            ) + nal
        }

    private fun firstLengthPrefixedNalType(bytes: ByteArray): Int {
        require(bytes.size >= 6)
        val length =
            ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
        require(length >= 2 && bytes.size >= 4 + length)
        return (bytes[4].toInt() ushr 1) and 0x3F
    }

    private fun ByteArray.containsLengthPrefixedNalType(nalType: Int): Boolean {
        var offset = 0
        while (offset + 6 <= size) {
            val length =
                ((this[offset].toInt() and 0xFF) shl 24) or
                    ((this[offset + 1].toInt() and 0xFF) shl 16) or
                    ((this[offset + 2].toInt() and 0xFF) shl 8) or
                    (this[offset + 3].toInt() and 0xFF)
            if (length < 2 || offset + 4 + length > size) return false
            val type = (this[offset + 4].toInt() ushr 1) and 0x3F
            if (type == nalType) return true
            offset += 4 + length
        }
        return false
    }
}
