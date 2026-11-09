package com.neoproc.neoproccamx.recording

import org.junit.Assert.*
import org.junit.Test

class RawRecordingSelectionPolicyTest {
    private val full = RecordingDimensions(4096, 3072)
    private val wide = RecordingDimensions(4096, 2304)
    private val max = RecordingDimensions(8192, 6144)

    private fun resolve(
        requested: RecordingDimensions = full,
        tier: RecordingDimensions? = full,
        advertised: List<RecordingDimensions> = listOf(full, wide),
        supported: Boolean = true,
        explicit: Boolean = true
    ) = RecordingSelectionPolicy.resolveRawDimensions(requested, tier, advertised, supported, explicit)

    @Test fun `full active array RAW is exact without claiming encoder support`() {
        val selection = resolve()
        assertTrue(selection.exactOutputSupported)
        assertTrue(selection.cameraExactMatch)
        assertFalse(selection.encoderExactMatch)
        assertEquals(RecordingSelectionBackend.SENSOR_RAW, selection.backend)
        assertEquals("NPRAW", selection.codec)
        assertEquals(full, selection.requestedSize)
        assertEquals(full, selection.sourceSize)
        assertEquals(full, selection.size)
        assertFalse(selection.upscaledToRequested)
    }

    @Test fun `unsupported 8MP and UHD requests stay rejected without substitution`() {
        for (requested in listOf(RecordingDimensions(3264, 2448), RecordingDimensions(3840, 2160))) {
            val selection = resolve(requested = requested)
            assertEquals(requested, selection.requestedSize)
            assertFalse(selection.cameraExactMatch)
            assertFalse(selection.exactOutputSupported)
        }
    }

    @Test fun `pixel array metadata cannot advertise a nonexistent maximum stream`() {
        assertFalse(resolve(requested = max, tier = max).exactOutputSupported)
        assertFalse(resolve(requested = max).exactOutputSupported)
    }

    @Test fun `maximum resolution mode must select a real maximum HAL stream`() {
        assertTrue(resolve(requested = max, tier = max, advertised = listOf(full, max)).exactOutputSupported)
        assertFalse(resolve(requested = max, tier = full, advertised = listOf(full, max)).exactOutputSupported)
        assertFalse(resolve(requested = full, tier = max, advertised = listOf(full, max)).exactOutputSupported)
    }

    @Test fun `RAW mode entry uses tier instead of stale processed video intent`() {
        val selection = resolve(requested = RecordingDimensions(3264, 2448), explicit = false)
        assertEquals(full, selection.requestedSize)
        assertTrue(selection.exactOutputSupported)
    }

    @Test fun `absent RAW hardware or tier never falls through to processed output`() {
        assertFalse(resolve(supported = false).exactOutputSupported)
        assertFalse(resolve(tier = null).exactOutputSupported)
        assertFalse(resolve(advertised = emptyList()).exactOutputSupported)
        assertFalse(resolve(tier = RecordingDimensions(0, 0)).exactOutputSupported)
    }

    @Test fun `portrait requested orientation is equivalent to the HAL landscape stream`() {
        val selection = resolve(requested = RecordingDimensions(3072, 4096))
        assertTrue(selection.exactOutputSupported)
        assertEquals(full, selection.size)
    }

    @Test fun `advertised widescreen is not mistaken for the selected full array tier`() {
        assertFalse(resolve(requested = wide).exactOutputSupported)
        assertTrue(resolve(requested = wide, tier = wide).exactOutputSupported)
    }

    @Test fun `processed recording still requires exact encoder admission`() {
        val selection = RecordingSelectionPolicy.resolveDimensions(
            requestedSize = full, fps = 25, cameraSupportedSizes = listOf(full),
            preferredCodec = "HEVC", resolveSupportedSize = { _, _, _ -> RecordingDimensions(3840, 2160) }
        )
        assertEquals(RecordingSelectionBackend.MEDIA_CODEC, selection.backend)
        assertFalse(selection.exactOutputSupported)
    }
}
