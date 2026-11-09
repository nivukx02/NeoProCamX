package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class OpticalFlowModelAssetPolicyTest {

    @Test
    fun rejectsMissingModelWithoutActivatingNeuralFlow() {
        val validation = OpticalFlowModelAssetPolicy.validateFile(
            File("src/main/assets/models/optical_flow_int8.tflite")
        )

        if (File("src/main/assets/models/optical_flow_int8.tflite").isFile) {
            assertTrue(validation.valid)
            assertTrue(validation.tfliteFlatbuffer)
        } else {
            assertFalse(validation.valid)
            assertTrue(validation.reason.contains("model missing"))
        }
    }

    @Test
    fun rejectsOnnxEvenWhenItIsInt8OpticalFlow() {
        val payload = ByteArray(300 * 1024) { index -> (index and 0x7F).toByte() }
        payload[0] = 0x08
        payload[1] = 0x01

        val validation = OpticalFlowModelAssetPolicy.validateStream(
            assetPath = "models/optical_flow_int8.onnx",
            fileName = "optical_flow_int8.onnx",
            input = ByteArrayInputStream(payload)
        )

        assertFalse(validation.valid)
        assertTrue(validation.reason.contains("unexpected file name"))
    }

    @Test
    fun rejectsTinyTflitePlaceholder() {
        val payload = ByteArray(64) { 0 }
        payload[4] = 'T'.code.toByte()
        payload[5] = 'F'.code.toByte()
        payload[6] = 'L'.code.toByte()
        payload[7] = '3'.code.toByte()

        val validation = OpticalFlowModelAssetPolicy.validateStream(
            assetPath = OpticalFlowModelAssetPolicy.DEFAULT_ASSET_PATH,
            fileName = "optical_flow_int8.tflite",
            input = ByteArrayInputStream(payload)
        )

        assertFalse(validation.valid)
        assertTrue(validation.reason.contains("model too small"))
    }
}
