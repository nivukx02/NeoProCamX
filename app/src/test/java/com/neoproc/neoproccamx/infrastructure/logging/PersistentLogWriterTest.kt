package com.neoproc.neoproccamx.infrastructure.logging

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

class PersistentLogWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun multipleUtf8BatchesReuseDescriptorAndPreserveEveryByte() {
        val file = temporaryFolder.newFile("neoproc.log")
        val openCount = AtomicInteger(0)
        val writer = PersistentLogWriter { target ->
            openCount.incrementAndGet()
            FileOutputStream(target, true)
        }

        val firstSize = writer.append(file, "first α batch\n")
        val finalSize = writer.append(file, "second 📷 batch\n")
        writer.close()

        assertEquals(1, openCount.get())
        assertEquals("first α batch\nsecond 📷 batch\n", file.readText())
        assertEquals(file.length(), finalSize)
        assertEquals("first α batch\n".toByteArray(Charsets.UTF_8).size.toLong(), firstSize)
    }

    @Test
    fun fileChangeClosesPreviousDescriptorAndCloseIsIdempotent() {
        val firstFile = temporaryFolder.newFile("first.log")
        val secondFile = temporaryFolder.newFile("second.log")
        val openCount = AtomicInteger(0)
        val closeCount = AtomicInteger(0)
        val writer = PersistentLogWriter { target ->
            openCount.incrementAndGet()
            CloseTrackingOutputStream(
                delegate = FileOutputStream(target, true),
                closeCount = closeCount
            )
        }

        writer.append(firstFile, "first\n")
        writer.append(secondFile, "second\n")

        assertEquals(2, openCount.get())
        assertEquals(1, closeCount.get())
        assertEquals("first\n", firstFile.readText())
        assertEquals("second\n", secondFile.readText())

        writer.close()
        writer.close()
        assertEquals(2, closeCount.get())
    }

    private class CloseTrackingOutputStream(
        private val delegate: OutputStream,
        private val closeCount: AtomicInteger
    ) : FilterOutputStream(delegate) {
        override fun close() {
            try {
                super.close()
            } finally {
                closeCount.incrementAndGet()
            }
        }
    }
}
