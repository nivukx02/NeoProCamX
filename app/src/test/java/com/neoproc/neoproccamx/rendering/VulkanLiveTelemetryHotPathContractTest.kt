package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VulkanLiveTelemetryHotPathContractTest {
    private val bridgeSource =
        File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt").readText()
    private val runtimeSource =
        File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt").readText()
    private val nativeSource = File("src/main/cpp/vulkan_bridge.cpp").readText()

    @Test
    fun `health cadence uses compact native telemetry instead of capability strings`() {
        val liveRead = runtimeSource
            .substringAfter("fun liveComputeTelemetry(): LiveComputeTelemetry")
            .substringBefore("private val _encoderSurfaceStatus")
        val bridgeRead = bridgeSource
            .substringAfter("internal fun liveComputeTelemetry(): VulkanLiveComputeTelemetry?")
            .substringBefore("fun latestHdrMetadata()")
        val nativeRead = nativeSource
            .substringAfter("VulkanBridge_nativeGetLiveComputeTelemetry(")
            .substringBefore("VulkanBridge_nativeSubmitHardwareBuffer(")

        assertTrue(liveRead.contains("bridge.liveComputeTelemetry()"))
        assertFalse(liveRead.contains("combinedCapabilitySummary()"))
        assertTrue(bridgeRead.contains("nativeGetLiveComputeTelemetry"))
        assertTrue(bridgeRead.contains("liveComputeTelemetryBuffer"))
        assertTrue(nativeRead.contains("std::array<uint64_t, kFieldCount> telemetry"))
        assertTrue(nativeRead.contains("std::lock_guard<std::mutex> lock(state->mutex)"))
        assertFalse(nativeRead.contains("std::ostringstream"))
    }

    @Test
    fun `dynamic enhancement upload never builds diagnostic summaries on packet hot path`() {
        val upload = runtimeSource
            .substringAfter("private fun uploadEnhancementUniformsForBundle(")
            .substringBefore("private fun subjectDetailMaskSignature(")

        assertTrue(upload.contains("bridge.updateEnhancementUniforms(uniforms)"))
        assertTrue(upload.contains("installEnhancementUniforms(uniforms)"))
        assertFalse(upload.contains("refreshCapabilitySummary()"))
        assertTrue(runtimeSource.contains("fun requestCapabilitySummaryRefresh() = refreshCapabilitySummary()"))
    }
}
