package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraReadoutTargetSizePolicyTest {

    @Test
    fun `recording readout override wins over smaller analysis target`() {
        val resolved = CameraReadoutTargetSizePolicy.resolveYuvReaderStreamSize(
            analysisTargetSize = CameraStreamSize(1920, 1080),
            recordingReadoutOverrideSize = CameraStreamSize(4096, 3072),
            recordingEncoderTargetSize = CameraStreamSize(3840, 2160),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
            supportedYuvSizes = listOf(CameraStreamSize(4096, 3072), CameraStreamSize(1920, 1080)),
            fallback = CameraStreamSize(1920, 1080)
        )

        assertSizeEquals(4096, 3072, resolved)
    }

    @Test
    fun `unsupported full sensor yuv override falls back to largest exposed yuv`() {
        val resolved = CameraReadoutTargetSizePolicy.resolveYuvReaderStreamSize(
            analysisTargetSize = CameraStreamSize(1920, 1080),
            recordingReadoutOverrideSize = CameraStreamSize(8192, 6144),
            recordingEncoderTargetSize = CameraStreamSize(3840, 2160),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
            supportedYuvSizes = listOf(CameraStreamSize(4096, 3072), CameraStreamSize(3840, 2160)),
            fallback = CameraStreamSize(1920, 1080)
        )

        assertSizeEquals(4096, 3072, resolved)
    }

    @Test
    fun `unsupported full sensor raw override falls back to largest exposed raw`() {
        val resolved = CameraReadoutTargetSizePolicy.resolveRawReaderStreamSize(
            recordingReadoutOverrideSize = CameraStreamSize(8192, 6144),
            supportedRawSizes = listOf(CameraStreamSize(4096, 3072), CameraStreamSize(4080, 3060)),
            fallback = CameraStreamSize(1920, 1080)
        )

        assertSizeEquals(4096, 3072, resolved)
    }

    @Test
    fun `encoder matched sidecar mode requests encoder yuv size instead of max readout`() {
        val resolved = CameraReadoutTargetSizePolicy.resolveYuvReaderStreamSize(
            analysisTargetSize = CameraStreamSize(4096, 3072),
            recordingReadoutOverrideSize = CameraStreamSize(4096, 3072),
            recordingEncoderTargetSize = CameraStreamSize(3840, 2160),
            readoutSidecarMode = CameraReadoutSidecarMode.ENCODER_MATCHED,
            supportedYuvSizes = listOf(CameraStreamSize(4096, 3072), CameraStreamSize(3840, 2160)),
            fallback = CameraStreamSize(1920, 1080)
        )

        assertSizeEquals(3840, 2160, resolved)
    }

    @Test
    fun `encoder matched yuv keeps delivery aspect when exact sidecar size is unavailable`() {
        val resolved = CameraReadoutTargetSizePolicy.resolveYuvReaderStreamSize(
            analysisTargetSize = CameraStreamSize(4096, 3072),
            recordingReadoutOverrideSize = CameraStreamSize(4096, 3072),
            recordingEncoderTargetSize = CameraStreamSize(3840, 2160),
            readoutSidecarMode = CameraReadoutSidecarMode.ENCODER_MATCHED,
            supportedYuvSizes = listOf(
                CameraStreamSize(3264, 2448),
                CameraStreamSize(2560, 1440),
                CameraStreamSize(1920, 1080)
            ),
            fallback = CameraStreamSize(1920, 1080)
        )

        assertSizeEquals(2560, 1440, resolved)
    }

    @Test
    fun `maximum readout accepts near native sensor size instead of dropping to delivery stream`() {
        val resolved = CameraReadoutTargetSizePolicy.resolveRawReaderStreamSize(
            recordingReadoutOverrideSize = CameraStreamSize(4000, 3000),
            supportedRawSizes = listOf(
                CameraStreamSize(4032, 3024),
                CameraStreamSize(3840, 2160)
            ),
            fallback = CameraStreamSize(1920, 1080)
        )

        assertSizeEquals(4032, 3024, resolved)
    }

    private fun assertSizeEquals(expectedWidth: Int, expectedHeight: Int, actual: CameraStreamSize) {
        assertEquals(expectedWidth, actual.width)
        assertEquals(expectedHeight, actual.height)
    }
}
