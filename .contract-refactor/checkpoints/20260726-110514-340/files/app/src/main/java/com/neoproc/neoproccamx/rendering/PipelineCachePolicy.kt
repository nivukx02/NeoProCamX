package com.neoproc.neoproccamx.rendering

import java.io.File

/**
 * s7-f13 / CP9C — disk pipeline-cache (warm-start) policy.
 *
 * [DEFAULT_ENABLED] is true (user-directed activation 2026-07-03): the native side seeds+persists the
 * device-identity-validated disk VkPipelineCache for warm-start. When false it reproduces the empty-
 * VkPipelineCacheCreateInfo / fresh-compile path bit-for-bit; the native restore/persist branches are
 * then unreachable.
 *
 * This object also mirrors the native header-validity rule (NeoPipelineCacheHeader in
 * pipeline_cache_io.h) so the accept/reject decision is unit-testable on the JVM with no device.
 * The native C++ is the runtime source of truth; this is a characterization mirror kept in lockstep
 * by [com.neoproc.neoproccamx.rendering.PipelineCacheHeaderLayoutTest].
 */
internal object PipelineCachePolicy {

    /**
     * Default ON (user-directed 2026-07-03). Pixel-safe: VkPipelineCache is compile-time only and the
     * native header is device-identity-validated (vendor/device/driver/uuid) before use, else fresh
     * compile. DEVICE-VERIFICATION-PENDING: the warm-start payoff AND the native disk read/write path
     * are unproven without the vivo I2405 — smoke-test a cold start; revert this one line if it misbehaves.
     */
    const val DEFAULT_ENABLED: Boolean = true

    /** Mirrors kPipelineCacheMagic ('NPCK'). */
    const val MAGIC: Int = 0x4E50434B

    /** Mirrors kPipelineCacheHeaderVersion. */
    const val HEADER_VERSION: Int = 1

    /** Mirrors sizeof(NeoPipelineCacheHeader): 6x u32 (24) + u8[16] uuid (40) + u64 payloadSize (48). */
    const val HEADER_SIZE_BYTES: Int = 48

    /** Mirrors VK_UUID_SIZE / kPipelineCacheUuidSize. */
    const val UUID_SIZE: Int = 16

    const val CACHE_FILE_NAME: String = "vk_graphics_pipeline_cache.bin"

    /**
     * App-private cache file path. Caller passes an app cacheDir/filesDir; null -> null (gate
     * effectively off, fresh compile).
     */
    fun cacheFilePath(baseDir: File?): String? {
        if (baseDir == null) return null
        return File(baseDir, CACHE_FILE_NAME).absolutePath.ifBlank { null }
    }

    /**
     * Pure mirror of pipelineCacheHeaderMatchesDevice: accept iff magic+version match and the device
     * identity (vendor/device/driver/uuid) is byte-identical. Used by the JVM characterization test;
     * not on the live path.
     */
    fun headerMatchesDevice(
        headerMagic: Int,
        headerVersion: Int,
        headerVendorId: Int,
        headerDeviceId: Int,
        headerDriverVersion: Int,
        headerUuid: ByteArray,
        deviceVendorId: Int,
        deviceDeviceId: Int,
        deviceDriverVersion: Int,
        deviceUuid: ByteArray
    ): Boolean {
        if (headerMagic != MAGIC) return false
        if (headerVersion != HEADER_VERSION) return false
        if (headerVendorId != deviceVendorId) return false
        if (headerDeviceId != deviceDeviceId) return false
        if (headerDriverVersion != deviceDriverVersion) return false
        if (headerUuid.size != UUID_SIZE || deviceUuid.size != UUID_SIZE) return false
        return headerUuid.contentEquals(deviceUuid)
    }
}
