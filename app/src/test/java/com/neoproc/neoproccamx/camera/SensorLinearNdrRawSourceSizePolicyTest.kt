package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorLinearNdrRawSourceSizePolicyTest {
    @Test
    fun `normal pixel mode active array wins over a larger max resolution raw size`() {
        val resolved = SensorLinearNdrRawSourceSizePolicy.resolve(
            activeArrayRawSize = CameraStreamSize(4096, 3072),
            supportedRawSizes = listOf(
                CameraStreamSize(8192, 6144),
                CameraStreamSize(4096, 3072),
                CameraStreamSize(4096, 2304)
            ),
            fallback = CameraStreamSize(4096, 3072)
        )

        assertEquals(CameraStreamSize(4096, 3072), resolved)
    }

    @Test
    fun `orientation equivalent active array matches the advertised raw stream`() {
        val resolved = SensorLinearNdrRawSourceSizePolicy.resolve(
            activeArrayRawSize = CameraStreamSize(3072, 4096),
            supportedRawSizes = listOf(
                CameraStreamSize(4096, 2304),
                CameraStreamSize(4096, 3072)
            ),
            fallback = CameraStreamSize(4096, 2304)
        )

        assertEquals(CameraStreamSize(4096, 3072), resolved)
    }
}
