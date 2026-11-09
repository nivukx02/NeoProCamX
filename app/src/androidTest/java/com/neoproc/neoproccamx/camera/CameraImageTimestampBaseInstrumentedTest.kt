package com.neoproc.neoproccamx.camera

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Framework configuration tests, not a substitute for physical camera timestamp/FPS evidence. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class CameraImageTimestampBaseInstrumentedTest {
    @Test
    fun videoEncodeP010ReaderRetainsItsPixelsUsageAndSensorTimebase() {
        val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE
        ImageReader.newInstance(64, 64, ImageFormat.YCBCR_P010, 3, usage).use { reader ->
            val output = OutputConfiguration(reader.surface)
            assertTrue(CameraImageTimestampBasePolicy.apply(output, appOwnedImageReader = true))
            assertEquals(OutputConfiguration.TIMESTAMP_BASE_SENSOR, output.timestampBase)
            assertEquals(ImageFormat.YCBCR_P010, reader.imageFormat)
            assertEquals(64, reader.width)
            assertEquals(64, reader.height)
            assertEquals(3, reader.maxImages)
            assertEquals(usage, reader.usage)
            assertSame(reader.surface, output.surface)
        }
    }

    @Test
    fun cpuReadableAndRawReadersShareTheSameExplicitSensorClock() {
        for (format in intArrayOf(ImageFormat.YUV_420_888, ImageFormat.RAW_SENSOR)) {
            ImageReader.newInstance(64, 64, format, 2).use { reader ->
                val output = OutputConfiguration(reader.surface)
                assertTrue(CameraImageTimestampBasePolicy.apply(output, appOwnedImageReader = true))
                assertEquals(OutputConfiguration.TIMESTAMP_BASE_SENSOR, output.timestampBase)
                assertEquals(format, reader.imageFormat)
            }
        }
    }

    @Test
    fun unownedSurfaceConfigurationIsUnchanged() {
        ImageReader.newInstance(64, 64, ImageFormat.YUV_420_888, 2).use { reader ->
            val output = OutputConfiguration(reader.surface)
            val originalBase = output.timestampBase
            assertFalse(CameraImageTimestampBasePolicy.apply(output, appOwnedImageReader = false))
            assertEquals(originalBase, output.timestampBase)
        }
    }
}
