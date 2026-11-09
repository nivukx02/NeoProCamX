package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AINeuralEngineNativeTensorBridgeContractTest {

    @Test
    fun kotlinBridgeDeclaresDirectYuvAndHardwareBufferEntrypoints() {
        val source = sourceFile("app/src/main/java/com/neoproc/neoproccamx/ai/AINeuralEngine.kt").readText()

        assertTrue(source.contains("data class DirectYuvTensorInput"))
        assertTrue(source.contains("fun processYuvToTensorDirect"))
        assertTrue(source.contains("private external fun nativeProcessYuvToBuffer"))
        assertTrue(source.contains("fun processHardwareBufferToTensorDirect"))
        assertTrue(source.contains("private external fun nativeProcessHardwareBufferToBuffer"))
        assertTrue(source.contains("System.loadLibrary(\"neoproc_vulkan\")"))
    }

    @Test
    fun nativeBridgeIsCompiledIntoNeoprocVulkanLibrary() {
        val cmake = sourceFile("app/src/main/cpp/CMakeLists.txt").readText()
        assertTrue(cmake.contains("ai_neural_engine_bridge.cpp"))
    }

    @Test
    fun nativeBridgePinsDirectBuffersAndUsesNeonGuard() {
        val source = sourceFile("app/src/main/cpp/ai_neural_engine_bridge.cpp").readText()

        assertTrue(source.contains("Java_com_neoproc_neoproccamx_ai_AINeuralEngine_nativeProcessYuvToBuffer"))
        assertTrue(source.contains("Java_com_neoproc_neoproccamx_ai_AINeuralEngine_nativeProcessHardwareBufferToBuffer"))
        assertTrue(source.contains("GetDirectBufferAddress"))
        assertTrue(source.contains("GetDirectBufferCapacity"))
        assertTrue(source.contains("#if defined(__ARM_NEON)"))
        assertTrue(source.contains("#include <arm_neon.h>"))
        assertTrue(source.contains("AHardwareBuffer_lockPlanes"))
    }

    private fun sourceFile(path: String): File {
        val candidates = listOf(
            File(path),
            File("../$path")
        )
        return candidates.map { it.absoluteFile }.firstOrNull { it.isFile }
            ?: error("Missing source file $path")
    }
}
