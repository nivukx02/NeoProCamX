package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileOutputStream
import kotlin.io.path.createTempFile

class RawFrameFileWriterTest {

    @Test
    fun `writes raw payload through write only channel`() {
        val file = createTempFile(prefix = "npraw-write-only", suffix = ".npraw").toFile()
        val payload = ByteArray(8197) { index -> (index % 251).toByte() }

        FileOutputStream(file).channel.use { channel ->
            RawFrameFileWriter.write(channel, payload, forceToStorage = false)
        }

        assertEquals(payload.size.toLong(), file.length())
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun `empty payload does not grow output file`() {
        val file = createTempFile(prefix = "npraw-empty", suffix = ".npraw").toFile()

        RawFrameFileWriter.write(file, ByteArray(0), forceToStorage = false)

        assertEquals(0L, file.length())
    }
}
