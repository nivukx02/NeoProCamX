package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AINeuralEngineDynamicOutputContractTest {

    @Test
    fun executeInferenceAllocatesOutputsFromRuntimeTensorShapeAndMapsRankOneSafely() {
        val source = sourceFile("app/src/main/java/com/neoproc/neoproccamx/ai/AINeuralEngine.kt").readText()

        assertTrue(source.contains("resolveOutputShape"))
        assertTrue(source.contains("allocateFloatOutputTensor"))
        assertTrue(source.contains("outputMapperPayload"))
        assertFalse(source.contains("outputMapper(outputArray as Array<*>)"))
    }

    @Test
    fun executeInferenceUsesRuntimeInputTensorShapeAndTypeForQuantizedModels() {
        val source = sourceFile("app/src/main/java/com/neoproc/neoproccamx/ai/AINeuralEngine.kt").readText()

        assertTrue(source.contains("resolveInputTensorSpec"))
        assertTrue(source.contains("interpreter.getInputTensor(0)"))
        assertTrue(source.contains("inputTensor?.numBytes()"))
        assertTrue(source.contains("DataType.UINT8"))
        assertTrue(source.contains("inputSpec.isQuantizedBytes"))
        assertFalse(source.contains("configuration.inputShape.reduce { acc, i -> acc * i } * 4"))
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
