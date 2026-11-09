package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** s7-f13 / CP9C — pure mirror of the native header-validity rule + path resolver. */
class PipelineCachePolicyTest {

    private fun uuid(seed: Int) = ByteArray(PipelineCachePolicy.UUID_SIZE) { (it + seed).toByte() }

    @Test
    fun headerMatchesDeviceAcceptsExactIdentity() {
        val u = uuid(3)
        assertTrue(
            PipelineCachePolicy.headerMatchesDevice(
                headerMagic = PipelineCachePolicy.MAGIC,
                headerVersion = PipelineCachePolicy.HEADER_VERSION,
                headerVendorId = 0x5143, headerDeviceId = 0x4060, headerDriverVersion = 99,
                headerUuid = u,
                deviceVendorId = 0x5143, deviceDeviceId = 0x4060, deviceDriverVersion = 99,
                deviceUuid = uuid(3)
            )
        )
    }

    @Test
    fun headerMatchesDeviceRejectsMismatchAndTruncation() {
        fun call(
            magic: Int = PipelineCachePolicy.MAGIC,
            version: Int = PipelineCachePolicy.HEADER_VERSION,
            vendor: Int = 1, device: Int = 2, driver: Int = 3,
            hUuid: ByteArray = uuid(0), dUuid: ByteArray = uuid(0)
        ) = PipelineCachePolicy.headerMatchesDevice(
            magic, version, vendor, device, driver, hUuid, 1, 2, 3, dUuid
        )

        assertTrue(call())
        assertFalse("wrong magic", call(magic = 0xDEAD))
        assertFalse("wrong version", call(version = 2))
        assertFalse("wrong vendor", call(vendor = 9))
        assertFalse("wrong device", call(device = 9))
        assertFalse("wrong driver", call(driver = 9))
        val mutated = uuid(0).also { it[7] = (it[7] + 1).toByte() }
        assertFalse("one uuid byte differs", call(hUuid = mutated))
        assertFalse("truncated header uuid", call(hUuid = ByteArray(8)))
        assertFalse("truncated device uuid", call(dUuid = ByteArray(8)))
    }

    @Test
    fun cacheFilePathResolvesUnderBaseDirOrNull() {
        assertNull(PipelineCachePolicy.cacheFilePath(null))
        val tmp = File(System.getProperty("java.io.tmpdir") ?: ".")
        val path = PipelineCachePolicy.cacheFilePath(tmp)!!
        assertTrue(path.endsWith(PipelineCachePolicy.CACHE_FILE_NAME))
        assertTrue(File(path).isAbsolute)
    }
}
