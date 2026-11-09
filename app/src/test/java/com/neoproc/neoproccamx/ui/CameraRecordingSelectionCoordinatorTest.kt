package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuVideoCodec
import com.neoproc.neoproccamx.recording.RecordingCapabilityPolicy
import com.neoproc.neoproccamx.recording.VideoCodecType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRecordingSelectionCoordinatorTest {

    @Test
    fun `raw-only runtime dimensions are retired when returning to a processed mode`() {
        assertTrue(
            CameraRecordingSelectionCoordinator.shouldRestoreProcessedSelectionAfterModeChange(
                previousMode = CinemaVideoMode.RAW,
                currentMode = CinemaVideoMode.PRO
            )
        )
        assertTrue(
            CameraRecordingSelectionCoordinator.shouldRestoreProcessedSelectionAfterModeChange(
                previousMode = CinemaVideoMode.RAW,
                currentMode = CinemaVideoMode.LOG
            )
        )
        assertFalse(
            CameraRecordingSelectionCoordinator.shouldRestoreProcessedSelectionAfterModeChange(
                previousMode = CinemaVideoMode.PRO,
                currentMode = CinemaVideoMode.LOG
            )
        )
        assertFalse(
            CameraRecordingSelectionCoordinator.shouldRestoreProcessedSelectionAfterModeChange(
                previousMode = CinemaVideoMode.RAW,
                currentMode = CinemaVideoMode.RAW
            )
        )
    }

    @Test
    fun `strict high end does not fabricate unsupported record size from pixel array`() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true

            val selection = CameraRecordingSelectionCoordinator.resolve(
                CameraRecordingSelectionRequest(
                    preferred = Size(3840, 2160),
                    preferredCodec = GpuVideoCodec.HEVC,
                    availableSizes = listOf(
                        Size(4096, 3072),
                        Size(3840, 2160),
                        Size(1920, 1080)
                    ),
                    pixelArraySize = Size(8192, 6144),
                    maxRawSize = Size(4096, 3072),
                    fallbackSensorSize = Size(4096, 3072),
                    fps = 30,
                    normalizeRecordingRequest = { it }
                )
            )

            assertEquals("HEVC", selection.codec)
            assertTrue(selection.cameraExactMatch)
            assertTrue(selection.encoderExactMatch)
            assertFalse(selection.upscaledToRequested)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun `strict live video keeps full four three 4k request for the 25fps processed tier`() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true

            val selection = CameraRecordingSelectionCoordinator.capPreferredForSustainableLiveVideoDimensions(
                preferred = CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(4096, 3072),
                availableSizes = listOf(
                    CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(4096, 3072),
                    CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(3840, 2160),
                    CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(1920, 1080)
                )
            )

            assertEquals(4096, selection.width)
            assertEquals(3072, selection.height)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun `explicit full four three 4k hdr dimensions ignore stale aspect and retain Main10 plan`() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true

            val dimensions = CameraRecordingSelectionCoordinator.resolveSelectedAspectPreferredDimensions(
                preferredWidth = 4096,
                preferredHeight = 3072,
                availableSizes = listOf(
                    CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(4096, 3072),
                    CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(3840, 2160),
                    CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(1920, 1080),
                    CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(1632, 1224)
                ),
                selectedOutputAspectRatio = 16f / 9f,
                uiMode = AppMode.VIDEO,
                rawModeActive = false,
                explicitResolutionSelection = true
            )
            val sessionPlan = RecordingCapabilityPolicy.resolveSessionPlan(
                requestedCodec = VideoCodecType.HEVC,
                av1Supported = false,
                hdrRequested = true,
                rawRequested = false,
                rawSupported = true,
                logEnabled = false,
                tenBitRequested = true
            )

            assertEquals(4096, dimensions.width)
            assertEquals(3072, dimensions.height)
            assertEquals(GpuVideoCodec.HEVC, sessionPlan.codec)
            assertEquals(CinemaVideoMode.PRO, sessionPlan.cinemaMode)
            assertTrue(sessionPlan.tenBitPipelineEnabled)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun `log mode prefers device maximum matching aspect for current mobile`() {
        val dimensions = CameraRecordingSelectionCoordinator.resolveMaximumSensorPreferredDimensions(
            requestedWidth = 3840,
            requestedHeight = 2160,
            availableSizes = listOf(
                CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(3840, 2160),
                CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(7680, 4320),
                CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(8000, 6000)
            ),
            pixelArrayWidth = 8000,
            pixelArrayHeight = 6000,
            maxRawWidth = 8000,
            maxRawHeight = 6000,
            fallbackSensorWidth = 8000,
            fallbackSensorHeight = 6000,
            favorFullSensor = false
        )

        assertEquals(7680, dimensions.width)
        assertEquals(4320, dimensions.height)
    }

    @Test
    fun `raw mode prefers full sensor maximum size for current mobile`() {
        val dimensions = CameraRecordingSelectionCoordinator.resolveMaximumSensorPreferredDimensions(
            requestedWidth = 3840,
            requestedHeight = 2160,
            availableSizes = listOf(
                CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(4096, 3072),
                CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(7680, 4320),
                CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(8000, 6000)
            ),
            pixelArrayWidth = 8000,
            pixelArrayHeight = 6000,
            maxRawWidth = 8000,
            maxRawHeight = 6000,
            fallbackSensorWidth = 8000,
            fallbackSensorHeight = 6000,
            favorFullSensor = true
        )

        assertEquals(8000, dimensions.width)
        assertEquals(6000, dimensions.height)
    }

    @Test
    fun `raw mode keeps full sensor backing when encoder list only exposes four k`() {
        val dimensions = CameraRecordingSelectionCoordinator.resolveMaximumSensorPreferredDimensions(
            requestedWidth = 4096,
            requestedHeight = 3072,
            availableSizes = listOf(
                CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(4096, 3072),
                CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(3840, 2160)
            ),
            pixelArrayWidth = 8192,
            pixelArrayHeight = 6144,
            maxRawWidth = 8192,
            maxRawHeight = 6144,
            fallbackSensorWidth = 4096,
            fallbackSensorHeight = 3072,
            favorFullSensor = true
        )

        assertEquals(8192, dimensions.width)
        assertEquals(6144, dimensions.height)
    }

    @Test
    fun `coordinator uses device supported size resolver callback`() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true
            var resolverInvoked = false

            CameraRecordingSelectionCoordinator.resolve(
                CameraRecordingSelectionRequest(
                    preferred = Size(3840, 2160),
                    preferredCodec = GpuVideoCodec.HEVC,
                    availableSizes = listOf(Size(7680, 4320)),
                    pixelArraySize = Size(8000, 6000),
                    maxRawSize = Size(8000, 6000),
                    fallbackSensorSize = Size(8000, 6000),
                    cinemaMode = CinemaVideoMode.LOG,
                    fps = 30,
                    normalizeRecordingRequest = { it },
                    resolveSupportedSize = { size, _, _ ->
                        resolverInvoked = true
                        size
                    }
                )
            )

            assertTrue(resolverInvoked)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun `four three video aspect uses real four three stream instead of cropped widescreen`() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true

            val dimensions = CameraRecordingSelectionCoordinator.resolveSelectedAspectPreferredDimensions(
                preferredWidth = 3840,
                preferredHeight = 2160,
                availableSizes = listOf(
                    CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(4096, 3072),
                    CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(3840, 2160),
                    CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(1920, 1080)
                ),
                selectedOutputAspectRatio = 4f / 3f,
                uiMode = AppMode.VIDEO,
                rawModeActive = false
            )

            assertEquals(4096, dimensions.width)
            assertEquals(3072, dimensions.height)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun `auto video aspect keeps manual widescreen resolution independent`() {
        val dimensions = CameraRecordingSelectionCoordinator.resolveSelectedAspectPreferredDimensions(
            preferredWidth = 3840,
            preferredHeight = 2160,
            availableSizes = listOf(
                CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(4096, 3072),
                CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(3840, 2160),
                CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(1920, 1080)
            ),
            selectedOutputAspectRatio = null,
            uiMode = AppMode.VIDEO,
            rawModeActive = false
        )

        assertEquals(3840, dimensions.width)
        assertEquals(2160, dimensions.height)
    }

    @Test
    fun `explicit widescreen resolution is not replaced by stale four three preview aspect`() {
        val dimensions = CameraRecordingSelectionCoordinator.resolveSelectedAspectPreferredDimensions(
            preferredWidth = 1920,
            preferredHeight = 1080,
            availableSizes = listOf(
                CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(1920, 1080),
                CameraRecordingSelectionCoordinator.MaximumSensorPreferredDimensions(1632, 1224)
            ),
            selectedOutputAspectRatio = 4f / 3f,
            uiMode = AppMode.VIDEO,
            rawModeActive = false,
            explicitResolutionSelection = true
        )

        assertEquals(1920, dimensions.width)
        assertEquals(1080, dimensions.height)
    }

    @Test
    fun `strict encoded video does not route pro hdr or log through maximum sensor output selection`() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true

            assertFalse(
                CameraRecordingSelectionCoordinator.shouldUseMaximumSensorPreferredSize(
                    strictHighEnd = true,
                    uiMode = AppMode.VIDEO,
                    rawModeActive = false
                )
            )
            assertTrue(
                CameraRecordingSelectionCoordinator.shouldUseMaximumSensorPreferredSize(
                    strictHighEnd = true,
                    uiMode = AppMode.VIDEO,
                    rawModeActive = true
                )
            )
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

}
