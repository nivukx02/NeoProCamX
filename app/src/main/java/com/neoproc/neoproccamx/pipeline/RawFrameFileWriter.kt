package com.neoproc.neoproccamx.pipeline

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal object RawFrameFileWriter {

    fun write(file: File, payload: ByteArray, forceToStorage: Boolean = true) {
        FileOutputStream(file).channel.use { channel ->
            write(channel, payload, forceToStorage)
        }
    }

    fun write(channel: FileChannel, payload: ByteArray, forceToStorage: Boolean = true) {
        val buffer = ByteBuffer.wrap(payload)
        var zeroWriteStreak = 0
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer)
            if (written < 0) {
                throw IOException("RAW frame channel closed before payload write completed")
            }
            if (written == 0) {
                zeroWriteStreak += 1
                if (zeroWriteStreak > 100) {
                    throw IOException("RAW frame channel made no write progress")
                }
                Thread.yield()
            } else {
                zeroWriteStreak = 0
            }
        }
        if (forceToStorage) {
            channel.force(false)
        }
    }
}
