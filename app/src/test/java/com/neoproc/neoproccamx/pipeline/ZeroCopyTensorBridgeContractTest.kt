package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ZeroCopyTensorBridgeContractTest {

    @Test
    fun `native zero copy tensor bridge stays disabled until real import validation exists`() {
        val source = File("src/main/cpp/vulkan_bridge.cpp").readText()
        val supportFunction = source.substringAfter(
            "Java_com_neoproc_neoproccamx_pipeline_ZeroCopyTensorBridge_nativeIsZeroCopySupported"
        ).substringBefore(
            "extern \"C\" JNIEXPORT jlong JNICALL"
        )

        assertTrue(supportFunction.contains("return JNI_FALSE;"))
    }
}
