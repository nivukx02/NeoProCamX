package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AINeuralEngineAssetDiscoveryContractTest {

    @Test
    fun modelAliasRegistry_pointsAbstractRequestsAtPackagedAssets() {
        val source = aiSource("AINeuralEngine.kt").readText()

        assertTrue(source.contains("MODEL_ALIAS_REGISTRY"))
        assertTrue(source.contains("\"depth_estimation\" to listOf(\"models/midas_v2_1_small.tflite\""))
        assertTrue(source.contains("\"segmentation\" to listOf(\"models/selfie_multiclass_256x256.tflite\""))
        assertTrue(source.contains("\"denoising\" to listOf(\"models/enhancement_denoise.tflite\""))
        assertTrue(source.contains("\"object_detection\" to listOf(\"models/efficientdet_lite0.tflite\""))
        assertTrue(source.contains("\"face_detection\" to listOf(\"models/face_landmarker.task\""))
        assertTrue(source.contains("\"pose_estimation\" to listOf(\"models/pose_landmarker_full.task\""))
    }

    @Test
    fun initializationScansAssetsAndMarksMissingModelsUnavailable() {
        val source = aiSource("AINeuralEngine.kt").readText()

        assertTrue(source.contains("enum class ModelStatus"))
        assertTrue(source.contains("UNVERIFIED"))
        assertTrue(source.contains("ACTIVE"))
        assertTrue(source.contains("UNAVAILABLE"))
        assertTrue(source.contains("context.assets.list(AIConstants.MODEL_ASSET_DIRECTORY)"))
        assertTrue(source.contains("resolveModelAssetPath"))
        assertTrue(source.contains("modelStatusMap"))
        assertTrue(source.contains("EngineState.Degraded"))
    }

    @Test
    fun preloadOnlyTouchesResolvedActiveAssets() {
        val source = aiSource("AINeuralEngine.kt").readText()

        assertTrue(source.contains("if (modelStatusMap[modelName] != ModelStatus.ACTIVE)"))
        assertTrue(source.contains("Skipping unavailable model"))
        assertFalse(
            "loadModel must not directly open stale hardcoded configuration.modelFile paths",
            source.contains("context.assets.openFd(configuration.modelFile)")
        )
    }

    @Test
    fun bitmapInputPreparationAvoidsHeapIntArrayGetPixelsPath() {
        val source = aiSource("AINeuralEngine.kt").readText()

        assertTrue(source.contains("copyPixelsToBuffer"))
        assertTrue(source.contains("DirectTensorInput"))
        assertFalse(source.contains("IntArray(inputData.width * inputData.height)"))
        assertFalse(source.contains("inputData.getPixels(pixels"))
    }

    private fun aiSource(name: String): File {
        val candidates = listOf(
            File("app/src/main/java/com/neoproc/neoproccamx/ai/$name"),
            File("src/main/java/com/neoproc/neoproccamx/ai/$name")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $name from ${File(".").absolutePath}")
    }
}
