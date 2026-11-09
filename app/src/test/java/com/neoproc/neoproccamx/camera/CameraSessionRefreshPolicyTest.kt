package com.neoproc.neoproccamx.camera

import android.util.Size
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionRefreshPolicyTest {

    @Test
    fun `matching reader targets can reuse existing readers`() {
        assertFalse(
            CameraSessionRefreshPolicy.shouldRebuildReaders(
                jpegReaderRequested = false,
                currentJpegSize = null,
                targetJpegSize = null,
                rawReaderRequested = true,
                currentRawSize = Size(8160, 6144),
                targetRawSize = Size(8160, 6144),
                currentYuvSize = Size(1920, 1080),
                targetYuvSize = Size(1920, 1080),
                currentHardwareImportSize = Size(4096, 2304),
                targetHardwareImportSize = Size(4096, 2304)
            )
        )
    }

    @Test
    fun `changed analysis or hardware import targets force reader rebuild`() {
        assertTrue(
            CameraSessionRefreshPolicy.shouldRebuildReaders(
                jpegReaderRequested = false,
                currentJpegSize = null,
                targetJpegSize = null,
                rawReaderRequested = true,
                currentRawSize = Size(8160, 6144),
                targetRawSize = Size(8160, 6144),
                currentYuvSize = null,
                targetYuvSize = Size(1280, 720),
                currentHardwareImportSize = Size(4096, 2304),
                targetHardwareImportSize = null
            )
        )
    }
}
