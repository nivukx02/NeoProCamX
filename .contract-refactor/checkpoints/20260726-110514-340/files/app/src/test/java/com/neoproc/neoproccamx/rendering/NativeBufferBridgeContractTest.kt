package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeBufferBridgeContractTest {

    private val root = File(".")

    @Test
    fun `native buffer bridge owns ahardwarebuffer handles with atomic ref counting`() {
        val kotlin = File(
            root,
            "src/main/java/com/neoproc/neoproccamx/rendering/NativeBufferBridge.kt"
        ).readText()
        val native = File(root, "src/main/cpp/native_buffer_bridge.cpp").readText()
        val cmake = File(root, "src/main/cpp/CMakeLists.txt").readText()

        assertTrue(kotlin.contains("object NativeBufferBridge"))
        assertTrue(kotlin.contains("acquireHardwareBuffer"))
        assertTrue(kotlin.contains("retainHandle"))
        assertTrue(kotlin.contains("releaseHandle"))
        assertTrue(kotlin.contains("describeHandle"))

        assertTrue(native.contains("#include <android/hardware_buffer_jni.h>"))
        assertTrue(native.contains("AHardwareBuffer_fromHardwareBuffer"))
        assertTrue(native.contains("AHardwareBuffer_acquire"))
        assertTrue(native.contains("AHardwareBuffer_release"))
        assertTrue(native.contains("std::atomic"))
        assertTrue(native.contains("std::mutex"))
        assertTrue(native.contains("std::unordered_map"))
        assertTrue(native.contains("NativeBufferManager::validateExternalCameraBuffer"))
        assertTrue(native.contains("Java_com_neoproc_neoproccamx_rendering_NativeBufferBridge_nativeAcquireHardwareBuffer"))
        assertTrue(native.contains("Java_com_neoproc_neoproccamx_rendering_NativeBufferBridge_nativeRetainHandle"))
        assertTrue(native.contains("Java_com_neoproc_neoproccamx_rendering_NativeBufferBridge_nativeReleaseHandle"))
        assertTrue(native.contains("Java_com_neoproc_neoproccamx_rendering_NativeBufferBridge_nativeDescribeHandle"))
        assertTrue(cmake.contains("native_buffer_bridge.cpp"))
    }

    @Test
    fun `native buffer bridge never copies pixel bytes through jni`() {
        val native = File(root, "src/main/cpp/native_buffer_bridge.cpp").readText()
        val kotlin = File(
            root,
            "src/main/java/com/neoproc/neoproccamx/rendering/NativeBufferBridge.kt"
        ).readText()

        assertFalse(native.contains("GetByteArrayElements"))
        assertFalse(native.contains("GetPrimitiveArrayCritical"))
        assertFalse(native.contains("memcpy("))
        assertFalse(kotlin.contains("ByteArray"))
        assertFalse(kotlin.contains(".planes"))
    }
}
