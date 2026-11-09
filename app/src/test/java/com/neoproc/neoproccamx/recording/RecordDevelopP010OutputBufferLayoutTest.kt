package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordDevelopP010OutputBufferLayoutTest {

    @Test
    fun qtiP010DecoderStrideIsAcceptedAsRowBytesWhenItAlreadyMatchesWidthTimesTwo() {
        val required = RecordDevelopP010OutputBufferLayout.requiredBytes(
            width = 4096,
            height = 3072,
            formatStride = 8192,
            sliceHeight = 3072
        )

        assertEquals(8192, RecordDevelopP010OutputBufferLayout.rowBytesFromFormatStride(8192, 4096))
        assertEquals(37_748_736L, required)
    }

    @Test
    fun pixelStrideStyleP010DecoderStrideIsConvertedToRowBytes() {
        val required = RecordDevelopP010OutputBufferLayout.requiredBytes(
            width = 4096,
            height = 3072,
            formatStride = 4096,
            sliceHeight = 3072
        )

        assertEquals(8192, RecordDevelopP010OutputBufferLayout.rowBytesFromFormatStride(4096, 4096))
        assertEquals(37_748_736L, required)
    }
}
