package com.neoproc.neoproccamx.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class StillOutputIntegrityPolicyTest {
    @Test
    fun `complete jpeg signature passes`() {
        val evidence = inspect(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte()), "jpg")
        assertTrue(evidence.passed)
        assertEquals(6L, evidence.byteCount)
    }

    @Test
    fun `truncated jpeg fails`() {
        assertFalse(inspect(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2), "jpg").passed)
    }

    @Test
    fun `little and big endian dng headers pass`() {
        assertTrue(inspect(byteArrayOf(0x49, 0x49, 0x2a, 0x00), "dng").passed)
        assertTrue(inspect(byteArrayOf(0x4d, 0x4d, 0x00, 0x2a), "dng").passed)
    }

    @Test
    fun `zip local header passes`() {
        assertTrue(inspect(byteArrayOf(0x50, 0x4b, 0x03, 0x04), "zip").passed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `publication mutation fails closed`() {
        StillOutputIntegrityPolicy.requireMatching(
            beforePublication = inspect(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 0xff.toByte(), 0xd9.toByte()), "jpg"),
            afterPublication = inspect(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 2, 0xff.toByte(), 0xd9.toByte()), "jpg")
        )
    }

    private fun inspect(bytes: ByteArray, extension: String): StillOutputIntegrityEvidence =
        StillOutputIntegrityPolicy.inspect(ByteArrayInputStream(bytes), extension)
}
