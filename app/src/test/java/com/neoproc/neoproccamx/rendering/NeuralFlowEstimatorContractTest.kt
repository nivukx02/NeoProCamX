package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NeuralFlowEstimatorContractTest {

    @Test
    fun nativeEstimatorExistsAndFailsClosedWithoutRealModel() {
        val kotlin = File("src/main/java/com/neoproc/neoproccamx/rendering/NeuralFlowEstimatorBridge.kt").readText()
        val header = File("src/main/cpp/neural_flow_estimator.h").readText()
        val native = File("src/main/cpp/neural_flow_estimator.cpp").readText()
        val cmake = File("src/main/cpp/CMakeLists.txt").readText()

        assertTrue(kotlin.contains("object NeuralFlowEstimatorBridge"))
        assertTrue(kotlin.contains("estimateAsync"))
        assertTrue(kotlin.contains("modelAssetPresent"))
        assertTrue(kotlin.contains("validateModelAsset"))
        assertTrue(kotlin.contains("OpticalFlowModelAssetPolicy.validateStream"))
        assertTrue(header.contains("class NeuralFlowEstimator"))
        assertTrue(native.contains("NeuralFlowEstimator::configure"))
        assertTrue(native.contains("NNAPI"))
        assertTrue(native.contains("modelPath"))
        assertTrue(native.contains("std::atomic"))
        assertTrue(native.contains("std::mutex"))
        assertTrue(native.contains("state=FALLBACK_UNSUPPORTED"))
        assertTrue(cmake.contains("neural_flow_estimator.cpp"))
    }

    @Test
    fun neuralEstimatorBridgeDoesNotCopyPixelsThroughJvmOrJni() {
        val kotlin = File("src/main/java/com/neoproc/neoproccamx/rendering/NeuralFlowEstimatorBridge.kt").readText()
        val native = File("src/main/cpp/neural_flow_estimator.cpp").readText()

        assertFalse(kotlin.contains("ByteArray"))
        assertFalse(kotlin.contains(".planes"))
        assertFalse(native.contains("GetByteArrayElements"))
        assertFalse(native.contains("GetPrimitiveArrayCritical"))
        assertFalse(native.contains("AHardwareBuffer_lock"))
        assertFalse(native.contains("memcpy("))
    }
}
