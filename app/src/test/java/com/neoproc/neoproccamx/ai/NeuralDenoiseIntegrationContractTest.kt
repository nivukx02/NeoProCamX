package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NeuralDenoiseIntegrationContractTest {

    @Test
    fun enhancementEngineRoutesDenoiseThroughNeuralEngineBeforeClassicalFallback() {
        val source = sourceFile("app/src/main/java/com/neoproc/neoproccamx/ai/EnhancementEngine.kt").readText()

        assertTrue(source.contains("NeuralDenoiseEngine"))
        assertTrue(source.contains("denoisePixels"))
        assertTrue(source.indexOf("denoisePixels") < source.indexOf("applyBilateralDenoise"))
    }

    @Test
    fun aiNeuralEngineExposesDenoiseTileHookAndPreloadWarmup() {
        val source = sourceFile("app/src/main/java/com/neoproc/neoproccamx/ai/AINeuralEngine.kt").readText()

        assertTrue(source.contains("runDenoiseTile"))
        assertTrue(source.contains("getNeuralDenoiseEngine"))
        assertTrue(source.contains("MODEL_DENOISING"))
    }

    @Test
    fun nativeBridgeExposesHardwareBufferBindingForNeuralDenoise() {
        val kotlinSource = sourceFile("app/src/main/java/com/neoproc/neoproccamx/ai/NeuralDenoiseEngine.kt").readText()
        val nativeSource = sourceFile("app/src/main/cpp/ai_neural_engine_bridge.cpp").readText()

        assertTrue(kotlinSource.contains("nativeBindHardwareBuffer"))
        assertTrue(nativeSource.contains("Java_com_neoproc_neoproccamx_ai_NeuralDenoiseEngine_nativeBindHardwareBuffer"))
    }

    private fun sourceFile(pathFromRepoRoot: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(dir, pathFromRepoRoot)
            if (candidate.exists()) return candidate
            val appRelative = File(dir, pathFromRepoRoot.removePrefix("app/"))
            if (appRelative.exists()) return appRelative
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        return File(pathFromRepoRoot)
    }
}
