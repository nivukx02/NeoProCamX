package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSelectionPolicyTest {

    @Test
    fun `resolve honors exact requested size instead of promoting to largest same aspect`() {
        withStrictHighEnd {
            val selection = RecordingSelectionPolicy.resolveDimensions(
                requestedSize = RecordingDimensions(1920, 1080),
                fps = 30,
                cameraSupportedSizes = listOf(
                    RecordingDimensions(3840, 2160),
                    RecordingDimensions(1920, 1080),
                    RecordingDimensions(1280, 720)
                ),
                preferredCodec = "HEVC",
                resolveSupportedSize = { size, _, _ -> size }
            )

            assertEquals(RecordingDimensions(1920, 1080), selection.requestedSize)
            assertEquals(RecordingDimensions(1920, 1080), selection.sourceSize)
            assertEquals(RecordingDimensions(1920, 1080), selection.size)
            assertTrue(selection.cameraExactMatch)
            assertTrue(selection.encoderExactMatch)
            assertFalse(selection.upscaledToRequested)
        }
    }

    @Test
    fun `resolve uses best lower same-aspect camera size when exact request is missing`() {
        withStrictHighEnd {
            val selection = RecordingSelectionPolicy.resolveDimensions(
                requestedSize = RecordingDimensions(2560, 1440),
                fps = 30,
                cameraSupportedSizes = listOf(
                    RecordingDimensions(3840, 2160),
                    RecordingDimensions(1920, 1080),
                    RecordingDimensions(1280, 720)
                ),
                preferredCodec = "HEVC",
                resolveSupportedSize = { size, _, _ -> size }
            )

            assertEquals(RecordingDimensions(1920, 1080), selection.sourceSize)
            assertEquals(RecordingDimensions(1920, 1080), selection.size)
            assertFalse(selection.cameraExactMatch)
            assertFalse(selection.encoderExactMatch)
            assertFalse(selection.upscaledToRequested)
        }
    }

    @Test
    fun `resolve preserves requested fps when checking encoder size support`() {
        withStrictHighEnd {
            var observedFps = 0

            RecordingSelectionPolicy.resolveDimensions(
                requestedSize = RecordingDimensions(1920, 1080),
                fps = 60,
                cameraSupportedSizes = listOf(RecordingDimensions(1920, 1080)),
                preferredCodec = "HEVC",
                resolveSupportedSize = { size, _, fps ->
                    observedFps = fps
                    size
                }
            )

            assertEquals(60, observedFps)
        }
    }

    @Test
    fun `unsupported 16 by 9 8k request falls to best true same-aspect camera stream`() {
        withStrictHighEnd {
            val selection = RecordingSelectionPolicy.resolveDimensions(
                requestedSize = RecordingDimensions(7680, 4320),
                fps = 30,
                cameraSupportedSizes = listOf(
                    RecordingDimensions(4096, 3072),
                    RecordingDimensions(3840, 2160),
                    RecordingDimensions(1920, 1080)
                ),
                preferredCodec = "HEVC",
                resolveSupportedSize = { size, _, _ -> size }
            )

            assertEquals(RecordingDimensions(3840, 2160), selection.sourceSize)
            assertEquals(RecordingDimensions(3840, 2160), selection.size)
            assertFalse(selection.cameraExactMatch)
            assertFalse(selection.encoderExactMatch)
            assertFalse(selection.upscaledToRequested)
        }
    }

    private inline fun withStrictHighEnd(block: () -> Unit) {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true
            block()
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

}
