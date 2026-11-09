package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraViewModelRenderIdentityPolicyTest {
    @Test
    fun `color uniform sha is deterministic for little endian float bytes`() {
        val values = floatArrayOf(0.0f, -0.0f, 1.0f, -2.5f, 1024.125f)

        assertEquals(
            "4b1b14b3d5bd764eebecf87c3361272fd9d8825b5803263da846b4e150db6b4a",
            CameraViewModelRenderIdentityPolicy.colorUniformSha256(values)
        )
    }

    @Test
    fun `quantization levels preserve edge handling`() {
        assertEquals(
            0,
            CameraViewModelRenderIdentityPolicy.quantizationLevelsForBitDepth(Int.MIN_VALUE)
        )
        assertEquals(0, CameraViewModelRenderIdentityPolicy.quantizationLevelsForBitDepth(0))
        assertEquals(1, CameraViewModelRenderIdentityPolicy.quantizationLevelsForBitDepth(1))
        assertEquals(255, CameraViewModelRenderIdentityPolicy.quantizationLevelsForBitDepth(8))
        assertEquals(1023, CameraViewModelRenderIdentityPolicy.quantizationLevelsForBitDepth(10))
        assertEquals(
            Int.MAX_VALUE,
            CameraViewModelRenderIdentityPolicy.quantizationLevelsForBitDepth(31)
        )
        assertEquals(
            Int.MAX_VALUE,
            CameraViewModelRenderIdentityPolicy.quantizationLevelsForBitDepth(Int.MAX_VALUE)
        )
    }
}
