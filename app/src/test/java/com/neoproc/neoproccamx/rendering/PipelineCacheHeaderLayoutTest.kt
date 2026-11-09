package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s7-f13 / CP9C — pins the on-disk NeoPipelineCacheHeader ABI (text-parse, no device, mirrors the
 * KneeCurve/RawDemosaic layout-test pattern), the Kotlin/native constant parity, and the gate-off
 * invariance of the native create site. There is intentionally NO warm-start timing golden — that
 * delta is a device (vivo I2405) BootTrace measurement, not a unit constant.
 */
class PipelineCacheHeaderLayoutTest {

    private fun cppSource(rel: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val c = File(dir, rel)
            if (c.isFile) return c.readText()
            val appRel = File(dir, rel.removePrefix("app/"))
            if (appRel.isFile) return appRel.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Unable to locate $rel from ${File(".").absolutePath}")
    }

    @Test
    fun headerStaticAssertsAndOffsetsAreLocked() {
        val h = cppSource("app/src/main/cpp/pipeline_cache_io.h")
        assertTrue(h.contains("static_assert(sizeof(NeoPipelineCacheHeader) == 48"))
        assertTrue(h.contains("offsetof(NeoPipelineCacheHeader, magic) == 0"))
        assertTrue(h.contains("offsetof(NeoPipelineCacheHeader, headerVersion) == 4"))
        assertTrue(h.contains("offsetof(NeoPipelineCacheHeader, vendorId) == 8"))
        assertTrue(h.contains("offsetof(NeoPipelineCacheHeader, deviceId) == 12"))
        assertTrue(h.contains("offsetof(NeoPipelineCacheHeader, driverVersion) == 16"))
        assertTrue(h.contains("offsetof(NeoPipelineCacheHeader, reserved) == 20"))
        assertTrue(h.contains("offsetof(NeoPipelineCacheHeader, pipelineCacheUuid) == 24"))
        assertTrue(h.contains("offsetof(NeoPipelineCacheHeader, payloadSize) == 40"))
    }

    @Test
    fun kotlinConstantsMirrorNativeHeader() {
        val h = cppSource("app/src/main/cpp/pipeline_cache_io.h")
        assertEquals(0x4E50434B, PipelineCachePolicy.MAGIC)
        assertTrue(h.contains("kPipelineCacheMagic = 0x4E50434Bu"))
        assertEquals(1, PipelineCachePolicy.HEADER_VERSION)
        assertTrue(h.contains("kPipelineCacheHeaderVersion = 1u"))
        assertEquals(48, PipelineCachePolicy.HEADER_SIZE_BYTES)
        assertEquals(16, PipelineCachePolicy.UUID_SIZE)
        assertTrue(h.contains("kPipelineCacheUuidSize = 16u"))
    }

    @Test
    fun defaultGateEnablesDiskCache() {
        assertTrue(PipelineCachePolicy.DEFAULT_ENABLED) // activated 2026-07-03 (user-directed)
        // The native seed/persist branch is now the default; it is still gate-guarded and only runs
        // after the header's device identity validates (else it falls back to a fresh compile).
        val cpp = cppSource("app/src/main/cpp/vulkan_bridge.cpp")
        assertTrue(cpp.contains("if (state->pipelineCacheDiskEnabled"))
    }
}
