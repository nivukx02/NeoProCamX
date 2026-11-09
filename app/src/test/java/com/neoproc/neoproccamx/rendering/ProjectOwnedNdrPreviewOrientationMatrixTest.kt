package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ProjectOwnedNdrPreviewOrientationMatrixTest {
    @Test
    fun `portrait back camera uses the working inverse sampling quarter turn`() {
        assertArrayEquals(
            floatArrayOf(0f, -1f, 1f, 0f),
            ProjectOwnedNdrPreviewOrientationMatrix.resolve(
                samplingRotationDegrees = 270,
                mirrored = false
            ),
            0f
        )
    }

    @Test
    fun `front camera mirror follows display space Vulkan operation order`() {
        assertArrayEquals(
            floatArrayOf(0f, -1f, -1f, 0f),
            ProjectOwnedNdrPreviewOrientationMatrix.resolve(
                samplingRotationDegrees = 270,
                mirrored = true
            ),
            0f
        )
    }

    @Test
    fun `all physical quarter turns remain exact`() {
        val expected = mapOf(
            0 to floatArrayOf(1f, 0f, 0f, 1f),
            90 to floatArrayOf(0f, 1f, -1f, 0f),
            180 to floatArrayOf(-1f, 0f, 0f, -1f),
            270 to floatArrayOf(0f, -1f, 1f, 0f)
        )
        expected.forEach { (rotation, matrix) ->
            assertArrayEquals(
                matrix,
                ProjectOwnedNdrPreviewOrientationMatrix.resolve(rotation, mirrored = false),
                0f
            )
        }
    }
}
