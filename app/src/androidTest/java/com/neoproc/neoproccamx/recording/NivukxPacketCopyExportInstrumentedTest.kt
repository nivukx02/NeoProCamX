package com.neoproc.neoproccamx.recording

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class NivukxPacketCopyExportInstrumentedTest {
    @Test
    fun directMediaStorePacketCopyPublishesMovWithoutTranscodeOrTemporaryFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        var sourceUri: android.net.Uri? = null
        var outputUri: android.net.Uri? = null

        try {
            val fixtureUri = resolver.insert(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "codex_nivukx_packet_copy_fixture.nivukx")
                    put(MediaStore.MediaColumns.MIME_TYPE, NivukxContainerFormat.MEDIASTORE_MIME_TYPE)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/CamX")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            ) ?: error("Unable to create NIVUKX test fixture")
            sourceUri = fixtureUri

            resolver.openFileDescriptor(fixtureUri, "rw")!!.use { descriptor ->
                NivukxStreamingWriter(
                    outputPath = "/proc/self/fd/${descriptor.fd}",
                    containerLabel = NivukxContainerFormat.USER_VISIBLE_LABEL,
                    createdAtEpochMs = 1_780_000_000_000L
                ).use { writer ->
                    writer.setOrientationHint(90)
                    val videoTrack = writer.addVideoTrack(
                        mime = "video/hevc",
                        width = 1_920,
                        height = 1_080,
                        fps = 30,
                        codecConfig = ByteArray(0),
                        colorConfig = QuickTimeColorAtomConfig(
                            colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                            transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                            matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                            fullRange = false,
                            masteringDisplay = HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_4000_NITS
                        )
                    )
                    val audioTrack = writer.addAudioTrack(
                        mime = "audio/mp4a-latm",
                        sampleRate = 48_000,
                        channelCount = 2,
                        codecConfig = byteArrayOf(0x12, 0x10)
                    )
                    val hevcPrefixSeiAndVcl = byteArrayOf(
                        0, 0, 0, 1, 0x4E, 0x01, 0x11, 0x22,
                        0, 0, 0, 1, 0x26, 0x01, 0x33, 0x44, 0x55
                    )
                    writer.writeSample(videoTrack, ByteBuffer.wrap(hevcPrefixSeiAndVcl), 0L, 1)
                    writer.writeSample(
                        audioTrack,
                        ByteBuffer.wrap(byteArrayOf(0x21, 0x10, 0x56, 0x78)),
                        500L,
                        0
                    )
                }
            }
            resolver.update(
                fixtureUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )

            val published = NivukxMediaStorePacketCopyExporter(context).export(fixtureUri)
            outputUri = published.uri

            assertTrue(published.remux.integrityVerified)
            assertTrue(published.remux.codedNalUnitsPreserved)
            assertFalse(published.remux.transcoded)
            assertEquals(2L, published.remux.sampleCount)
            assertEquals(1L, published.remux.videoSampleCount)
            assertEquals(1L, published.remux.audioSampleCount)
            assertTrue(published.displayName.endsWith("_PACKET_COPY.mov"))
            // Android 16's MediaProvider normalizes some .mov rows to video/mp4 even when the insert
            // requests video/quicktime. That database label does not alter the packet-copy MOV bytes.
            assertTrue(resolver.getType(outputUri) in setOf("video/quicktime", "video/mp4"))

            val bytes = resolver.openInputStream(outputUri)!!.use { input -> input.readBytes() }
            assertTrue(bytes.size > 128)
            assertTrue(bytes.containsAscii("ftyp"))
            assertTrue(bytes.containsAscii("mdat"))
            assertTrue(bytes.containsAscii("moov"))
            assertTrue(bytes.containsAscii("mdcv"))
            assertTrue(bytes.containsAscii("clli"))
            assertTrue(bytes.containsSequence(byteArrayOf(0x4E, 0x01, 0x11, 0x22)))
            assertTrue(bytes.containsSequence(byteArrayOf(0x26, 0x01, 0x33, 0x44, 0x55)))
        } finally {
            outputUri?.let { resolver.delete(it, null, null) }
            sourceUri?.let { resolver.delete(it, null, null) }
        }
    }

    private fun ByteArray.containsAscii(value: String): Boolean =
        containsSequence(value.toByteArray(Charsets.US_ASCII))

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        for (start in 0..size - needle.size) {
            var matched = true
            for (index in needle.indices) {
                if (this[start + index] != needle[index]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }
}
