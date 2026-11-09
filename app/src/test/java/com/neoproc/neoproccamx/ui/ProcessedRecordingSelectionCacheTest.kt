package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuVideoCodec
import com.neoproc.neoproccamx.recording.ResolvedRecordingSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessedRecordingSelectionCacheTest {
    private fun fingerprint(
        cameraId: String = "0",
        fps: Int = 25,
        tenBitSupported: Boolean? = true
    ) = ProcessedRecordingSelectionFingerprint(
        cameraId = cameraId,
        requestedWidth = 3840,
        requestedHeight = 2160,
        preferredCodec = GpuVideoCodec.HEVC,
        availableSizeSignature = listOf(
            ProcessedRecordingSelectionFingerprint.sizeSignature(4096, 3072),
            ProcessedRecordingSelectionFingerprint.sizeSignature(3840, 2160)
        ),
        pixelArraySizeSignature =
            ProcessedRecordingSelectionFingerprint.sizeSignature(8192, 6144),
        maximumRawSizeSignature =
            ProcessedRecordingSelectionFingerprint.sizeSignature(4096, 3072),
        fallbackSensorSizeSignature =
            ProcessedRecordingSelectionFingerprint.sizeSignature(4096, 3072),
        uiMode = AppMode.VIDEO,
        cinemaMode = CinemaVideoMode.PRO,
        rawEnabled = false,
        selectedOutputAspectRatioBits = (16f / 9f).toRawBits(),
        explicitResolutionSelection = true,
        rawSupported = true,
        av1Supported = false,
        av1CapabilityResolved = true,
        cachedTenBitSupport = tenBitSupported,
        hdrRequested = false,
        tenBitRequested = true,
        fps = fps,
        highEndEnabled = true,
        strictHighEndOnly = true,
        fullSensorForced = false,
        deviceBuildFingerprint = "test/device/build"
    )

    private fun exactSelection(width: Int = 3840, height: Int = 2160) =
        ResolvedRecordingSelection(
            requestedSize = Size(width, height),
            sourceSize = Size(width, height),
            size = Size(width, height),
            codec = "HEVC",
            cameraExactMatch = true,
            encoderExactMatch = true,
            upscaledToRequested = false
        )

    @Test
    fun `exact processed selection is reused during the same mode-switch fingerprint`() {
        val cache = ProcessedRecordingSelectionCache()
        var resolutions = 0

        val populated = cache.resolve(fingerprint(), processedMode = true, reuseAllowed = false) {
            resolutions += 1
            exactSelection()
        }
        val reused = cache.resolve(fingerprint(), processedMode = true, reuseAllowed = true) {
            resolutions += 1
            exactSelection()
        }

        assertFalse(populated.cacheHit)
        assertTrue(reused.cacheHit)
        assertEquals(1, resolutions)
        assertEquals(populated.selection, reused.selection)
    }

    @Test
    fun `raw resolution does not evict the exact processed entry`() {
        val cache = ProcessedRecordingSelectionCache()
        var processedResolutions = 0
        val populated = cache.resolve(fingerprint(), processedMode = true, reuseAllowed = false) {
            processedResolutions += 1
            exactSelection()
        }

        val raw = cache.resolve(fingerprint(), processedMode = false, reuseAllowed = true) {
            exactSelection(4096, 3072)
        }
        val restored = cache.resolve(fingerprint(), processedMode = true, reuseAllowed = true) {
            processedResolutions += 1
            exactSelection()
        }

        assertFalse(raw.cacheHit)
        assertTrue(restored.cacheHit)
        assertEquals(1, processedResolutions)
        assertSame(populated.selection, restored.selection)
    }

    @Test
    fun `capability fingerprint change forces a fresh exact resolution`() {
        val cache = ProcessedRecordingSelectionCache()
        var resolutions = 0
        cache.resolve(fingerprint(tenBitSupported = null), processedMode = true, reuseAllowed = false) {
            resolutions += 1
            exactSelection()
        }

        val changed = cache.resolve(
            fingerprint(tenBitSupported = true),
            processedMode = true,
            reuseAllowed = true
        ) {
            resolutions += 1
            exactSelection()
        }

        assertFalse(changed.cacheHit)
        assertEquals(2, resolutions)
    }

    @Test
    fun `cinema mode change cannot reuse a processed selection from another contract`() {
        val cache = ProcessedRecordingSelectionCache()
        var resolutions = 0
        cache.resolve(fingerprint(), processedMode = true, reuseAllowed = false) {
            resolutions += 1
            exactSelection()
        }

        val changed = cache.resolve(
            fingerprint().copy(cinemaMode = CinemaVideoMode.LOG),
            processedMode = true,
            reuseAllowed = true
        ) {
            resolutions += 1
            exactSelection()
        }

        assertFalse(changed.cacheHit)
        assertEquals(2, resolutions)
    }

    @Test
    fun `fallback or upscaled selection is never cached`() {
        val cache = ProcessedRecordingSelectionCache()
        var resolutions = 0
        val fallback = exactSelection().copy(
            cameraExactMatch = false,
            encoderExactMatch = false,
            upscaledToRequested = true
        )

        cache.resolve(fingerprint(), processedMode = true, reuseAllowed = false) {
            resolutions += 1
            fallback
        }
        val second = cache.resolve(fingerprint(), processedMode = true, reuseAllowed = true) {
            resolutions += 1
            fallback
        }

        assertFalse(second.cacheHit)
        assertEquals(2, resolutions)
    }
}
