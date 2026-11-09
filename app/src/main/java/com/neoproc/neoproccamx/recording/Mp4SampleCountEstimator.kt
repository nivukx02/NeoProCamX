package com.neoproc.neoproccamx.recording

import java.io.File
import java.io.RandomAccessFile

internal object Mp4SampleCountEstimator {
    private const val ATOM_HEADER_BYTES = 8L
    private const val EXTENDED_SIZE_BYTES = 16L

    fun estimateVideoSampleCount(file: File): Int = runCatching {
        if (!file.isFile || file.length() < ATOM_HEADER_BYTES) return@runCatching 0
        RandomAccessFile(file, "r").use { raf ->
            findFirstStszSampleCount(raf, 0L, raf.length())
        }
    }.getOrDefault(0)

    private fun findFirstStszSampleCount(raf: RandomAccessFile, start: Long, end: Long): Int {
        var pos = start
        while (pos + ATOM_HEADER_BYTES <= end) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xFFFF_FFFFL
            val type = raf.readAscii4()
            val headerSize: Long
            val atomSize: Long
            when (size32) {
                0L -> {
                    headerSize = ATOM_HEADER_BYTES
                    atomSize = end - pos
                }
                1L -> {
                    if (pos + EXTENDED_SIZE_BYTES > end) return 0
                    headerSize = EXTENDED_SIZE_BYTES
                    atomSize = raf.readLong()
                }
                else -> {
                    headerSize = ATOM_HEADER_BYTES
                    atomSize = size32
                }
            }
            if (atomSize < headerSize || pos + atomSize > end) return 0
            val payloadStart = pos + headerSize
            val payloadEnd = pos + atomSize
            if (type == "stsz") {
                if (payloadStart + 12L > payloadEnd) return 0
                raf.seek(payloadStart + 8L)
                return raf.readInt().coerceAtLeast(0)
            }
            if (type == "trak") {
                if (!isTrackVideo(raf, payloadStart, payloadEnd)) {
                    pos += atomSize
                    continue
                }
            }
            if (type in containerAtoms) {
                val childStart = if (type == "meta") payloadStart + 4L else payloadStart
                val nested = findFirstStszSampleCount(raf, childStart.coerceAtMost(payloadEnd), payloadEnd)
                if (nested > 0) return nested
            }
            pos += atomSize
        }
        return 0
    }

    private fun isTrackVideo(raf: RandomAccessFile, start: Long, end: Long): Boolean {
        var pos = start
        while (pos + ATOM_HEADER_BYTES <= end) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xFFFF_FFFFL
            val type = raf.readAscii4()
            val headerSize: Long
            val atomSize: Long
            when (size32) {
                0L -> {
                    headerSize = ATOM_HEADER_BYTES
                    atomSize = end - pos
                }
                1L -> {
                    if (pos + EXTENDED_SIZE_BYTES > end) return false
                    headerSize = EXTENDED_SIZE_BYTES
                    atomSize = raf.readLong()
                }
                else -> {
                    headerSize = ATOM_HEADER_BYTES
                    atomSize = size32
                }
            }
            if (atomSize < headerSize || pos + atomSize > end) return false
            val payloadStart = pos + headerSize
            val payloadEnd = pos + atomSize

            if (type == "hdlr") {
                if (payloadStart + 12L <= payloadEnd) {
                    raf.seek(payloadStart + 8L)
                    val handlerType = raf.readAscii4()
                    return handlerType == "vide"
                }
                return false
            }
            if (type == "mdia") {
                return isTrackVideo(raf, payloadStart, payloadEnd)
            }
            pos += atomSize
        }
        return false
    }

    private fun RandomAccessFile.readAscii4(): String {
        val bytes = ByteArray(4)
        readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private val containerAtoms = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "udta", "meta")
}
