package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.NdrFeatureGate
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RecordingDimensions
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.StabilizationMode
import com.neoproc.neoproccamx.recording.VideoCodecType
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsOptionCatalogTest {

    @Test
    fun `ndr product modes and settings are enabled by default`() {
        assertTrue(NdrFeatureGate.enabled)
        assertEquals(listOf("OFF", "NDR10+", "NIVUKXVISION"), SettingsOptionCatalog.videoHdrChoices.map { it.value })
        assertEquals(listOf("OFF", "XDR10+", "NIVUKX VISION"), SettingsOptionCatalog.videoHdrChoices.map { it.label })
        assertTrue(SettingsOptionCatalog.videoColorSpaceChoices.any { it.value == "Rec.2020-NDR10+" })
        assertTrue(SettingsOptionCatalog.videoColorSpaceChoices.any {
            it.label == "REC.2020 XDR10+" && it.value == "Rec.2020-NDR10+"
        })
        assertTrue(SettingsOptionCatalog.videoColorSpaceChoices.any { it.value == "Rec.2020-NIVUKX" })
        assertTrue(SettingsOptionCatalog.videoColorProfileChoices.any { it.value == "NDR10+" })
        assertTrue(SettingsOptionCatalog.videoColorProfileChoices.any {
            it.label == "XDR10+" && it.value == "NDR10+"
        })
        assertTrue(SettingsOptionCatalog.videoColorProfileChoices.any { it.value == "NIVUKX Vision" })
    }

    @Test
    fun `pro video resolution choices expose only the two 4k launch geometries`() {
        val labels = SettingsOptionCatalog.resolutionChoicesForMode(CinemaVideoMode.PRO).map { it.label }

        assertFalse(labels.any { it.startsWith("8K") })
        assertFalse(labels.any { it.startsWith("6K") })
        assertEquals(listOf("4K"), labels)
        assertFalse(labels.any { it.startsWith("2K") })
        assertFalse(labels.any { it.startsWith("1K") })
    }

    @Test
    fun `raw compression labels stay on NeoRadiance raw modes`() {
        val labels = SettingsOptionCatalog.rawCompressionChoices.map { it.label }
        assertEquals(
            "Every RawCompressionMode entry must have exactly one label",
            RawCompressionMode.entries.size, labels.size
        )
        for (label in labels) {
            val upper = label.uppercase()
            assertFalse("Label '$label' must not claim an external codec", upper.contains("CODEC"))
            assertFalse("Label '$label' must not claim a third-party raw format", upper.contains("RAW FORMAT"))
        }
    }

    @Test
    fun `every raw compression mode round-trips through fromName`() {
        for (mode in RawCompressionMode.entries) {
            val restored = RawCompressionMode.fromName(mode.name)
            assertEquals(
                "Mode '${mode.name}' did not round-trip; got '${restored.name}'",
                mode, restored
            )
        }
    }

    /**
     * Bit-depth getter must remain consistent with the documented per-enum
     * comment in [RawCompressionMode].
     */
    @Test
    fun `bit depth matches documented per-mode contract`() {
        assertEquals(16, RawCompressionMode.getBitDepth(RawCompressionMode.OFF))
        assertEquals(16, RawCompressionMode.getBitDepth(RawCompressionMode.LOSSLESS))
        assertEquals(16, RawCompressionMode.getBitDepth(RawCompressionMode.LOSSLESS_ENHANCED))
        assertEquals(16, RawCompressionMode.getBitDepth(RawCompressionMode.VISUALLY_LOSSLESS))
        assertEquals(14, RawCompressionMode.getBitDepth(RawCompressionMode.HIGH_QUALITY))
        assertEquals(12, RawCompressionMode.getBitDepth(RawCompressionMode.HIGH_COMPRESSION))
        assertEquals(14, RawCompressionMode.getBitDepth(RawCompressionMode.NEO_SPECTRAL_RAW))
        assertEquals(14, RawCompressionMode.getBitDepth(RawCompressionMode.NEO_PURE_RAW))
        assertEquals(14, RawCompressionMode.getBitDepth(RawCompressionMode.NEO_FINE_RAW))
        assertEquals(16, RawCompressionMode.getBitDepth(RawCompressionMode.NEO_DENSE_RAW))
    }

    @Test
    fun `every raw compression mode has a non-blank label`() {
        for (mode in RawCompressionMode.entries) {
            val choice = SettingsOptionCatalog.rawCompressionChoices.firstOrNull { it.value == mode }
            assertNotNull("No SettingsChoice for mode '${mode.name}'", choice)
            assertTrue(
                "Label for mode '${mode.name}' was blank",
                choice!!.label.isNotBlank()
            )
        }
    }

    @Test
    fun `raw video resolution choices expose only the two 4k launch geometries`() {
        val labels = SettingsOptionCatalog.resolutionChoicesForMode(CinemaVideoMode.RAW).map { it.label }

        assertFalse(labels.any { it.startsWith("8K") })
        assertFalse(labels.any { it.startsWith("6K") })
        assertEquals(listOf("4K"), labels)
        assertFalse(labels.any { it.startsWith("2K") })
        assertFalse(labels.any { it.startsWith("1K") })
    }

    @Test
    fun `active camera resolution choices expose named supported tiers only`() {
        val eightKChoices = SettingsOptionCatalog.resolutionLabelsForMode(
            mode = CinemaVideoMode.PRO,
            supportedOutputDimensions = listOf(
                RecordingDimensions(7680, 4320),
                RecordingDimensions(6144, 3456),
                RecordingDimensions(4096, 3072),
                RecordingDimensions(3840, 2160),
                RecordingDimensions(1920, 1080),
                RecordingDimensions(1280, 720)
            )
        )
        val limitedChoices = SettingsOptionCatalog.resolutionLabelsForMode(
            mode = CinemaVideoMode.PRO,
            supportedOutputDimensions = listOf(
                RecordingDimensions(4096, 3072),
                RecordingDimensions(3840, 2160),
                RecordingDimensions(1920, 1080)
            )
        )

        assertEquals(listOf("4K", "4K 4:3"), eightKChoices)
        assertFalse(eightKChoices.any { "x" in it || it.startsWith("6K") })
        assertFalse(limitedChoices.any { it.startsWith("8K") })
        assertEquals(listOf("4K", "4K 4:3"), limitedChoices)
    }

    @Test
    fun `supported full sensor four three output is distinct from canonical uhd`() {
        val labels = SettingsOptionCatalog.resolutionLabelsForMode(
            mode = CinemaVideoMode.PRO,
            supportedOutputDimensions = listOf(
                RecordingDimensions(4096, 3072),
                RecordingDimensions(3840, 2160)
            )
        )
        val selectedFullSensor = SettingsOptionCatalog.selectedResolutionDimensionsOrDefault(
            currentSize = RecordingDimensions(4096, 3072),
            mode = CinemaVideoMode.PRO,
            supportedOutputDimensions = listOf(
                RecordingDimensions(4096, 3072),
                RecordingDimensions(3840, 2160)
            )
        )
        val selectedUhd = SettingsOptionCatalog.selectedResolutionDimensionsOrDefault(
            currentSize = RecordingDimensions(3840, 2160),
            mode = CinemaVideoMode.PRO,
            supportedOutputDimensions = listOf(
                RecordingDimensions(4096, 3072),
                RecordingDimensions(3840, 2160)
            )
        )

        assertEquals(listOf("4K", "4K 4:3"), labels)
        assertEquals(RecordingDimensions(3840, 2160), selectedUhd)
        assertEquals(RecordingDimensions(4096, 3072), selectedFullSensor)
    }

    @Test
    fun `hidden lower resolution selection normalizes to visible 4k when supported`() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true

            val selected = SettingsOptionCatalog.selectedResolutionDimensionsOrDefault(
                currentSize = RecordingDimensions(1920, 1080),
                mode = CinemaVideoMode.PRO,
                supportedOutputDimensions = listOf(
                    RecordingDimensions(3840, 2160),
                    RecordingDimensions(1920, 1080),
                    RecordingDimensions(1280, 720)
                )
            )

            assertEquals(RecordingDimensions(3840, 2160), selected)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun `selected supported frame rate is not silently promoted when strict quality is enabled`() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true

            assertEquals(25, SettingsOptionCatalog.selectedFpsOrDefault(24))
            assertEquals(25, SettingsOptionCatalog.selectedFpsOrDefault(25))
            assertEquals(30, SettingsOptionCatalog.selectedFpsOrDefault(30))
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun `fps choices expose only launch-approved 25 and 30`() {
        val values = SettingsOptionCatalog.fpsChoices.map { it.value }

        assertEquals(listOf(25, 30), values)
        assertTrue(values.all { it <= 30 })
        assertFalse(values.contains(24))
        assertFalse(values.contains(48))
        assertFalse(values.contains(50))
        assertFalse(values.contains(60))
        assertFalse(values.contains(120))
        assertFalse(values.contains(240))
    }

    @Test
    fun `video codec choices stay on the honest hevc path`() {
        val values = SettingsOptionCatalog.videoCodecChoices.map { it.value }

        assertEquals(listOf(VideoCodecType.HEVC), values)
    }

    @Test
    fun `video detail choices offer focused enhancement and natural unsharpened output`() {
        val choices = SettingsOptionCatalog.videoDetailProfileChoices

        assertEquals(
            listOf(
                VideoDetailProfile.FOCUSED_DETAIL,
                VideoDetailProfile.NATURAL_DETAIL,
                VideoDetailProfile.EXTREME_DETAIL
            ),
            choices.map { it.value }
        )
        assertEquals(
            listOf("FOCUSED DETAIL", "NATURAL DETAIL", "EXTREME DETAIL"),
            choices.map { it.label }
        )
    }

    @Test
    fun `video color profile choices expose standard and NeoProCamX color science only`() {
        val labels = SettingsOptionCatalog.videoColorProfileChoices.map { it.label }
        val values = SettingsOptionCatalog.videoColorProfileChoices.map { it.value }

        assertEquals(
            listOf(
                "Natural",
                "Rec.709",
                "Rec.2020",
                "Neo ACES High End",
                "Neo Wide Gamut",
                "NeoGamut APEX",
                "NDR10+",
                "NIVUKX Vision",
                "N-Log Base",
                "N-Log Shadow",
                "N-Log Highlight",
                "N-Log Cinema"
            ),
            values
        )
        assertTrue(labels.contains("REC.709"))
        assertTrue(labels.contains("REC.2020"))
        listOf("Sony", "ARRI", "Canon", "Panasonic", "RED", "Blackmagic", "DaVinci", "Dolby", "HLG").forEach { vendor ->
            assertFalse(values.joinToString(separator = " ").contains(vendor, ignoreCase = true))
        }
    }

    @Test
    fun `video hdr choices expose only NeoProCamX hdr modes plus off`() {
        val values = SettingsOptionCatalog.videoHdrChoices.map { it.value }

        assertEquals(listOf("OFF", "NDR10+", "NIVUKXVISION"), values)
        assertFalse(values.contains("HLG"))
        assertFalse(values.contains("HDR10"))
        assertFalse(values.contains("HDR10+"))
        assertFalse(values.contains("DOLBYVISION"))
    }

    @Test
    fun `video color space choices expose only standards and NeoProCamX spaces`() {
        val values = SettingsOptionCatalog.videoColorSpaceChoices.map { it.value }

        assertEquals(
            listOf(
                "Rec.709",
                "Rec.2020",
                "Rec.2020-N-Log",
                "Rec.2020-NDR10+",
                "Rec.2020-NIVUKX",
                "Neo Wide Gamut",
                "NeoGamut APEX",
                "Neo ACES AP1",
                "Neo ACES AP0"
            ),
            values
        )
        listOf("Display P3", "Sony", "ARRI", "Canon", "Panasonic", "RED", "Blackmagic", "DaVinci").forEach { token ->
            assertFalse(values.joinToString(separator = " ").contains(token, ignoreCase = true))
        }
    }

    @Test
    fun `neo aces profile selections stay user selectable instead of mapping to log`() {
        assertEquals("Neo ACES High End", SettingsOptionCatalog.selectedVideoColorProfile("Neo ACES High End"))
        assertEquals("Neo ACES High End", SettingsOptionCatalog.selectedVideoColorProfile("ACEScg"))
        assertEquals("Neo Wide Gamut", SettingsOptionCatalog.selectedVideoColorProfile("Neo Wide Gamut"))
    }

    @Test
    fun `creative lut choices do not expose vendor branded transform names`() {
        val values = SettingsOptionCatalog.lutChoices(customLutAvailable = false).map { it.value }
        val joined = values.joinToString(separator = " ")

        assertTrue(values.contains("Neutral Film"))
        assertTrue(values.contains("Warm Cinema"))
        assertTrue(values.contains("Teal & Orange"))
        listOf("ARRI", "Sony", "Canon", "Panasonic", "RED", "Blackmagic", "DaVinci", "Kodak", "Fuji").forEach { token ->
            assertFalse(joined.contains(token, ignoreCase = true))
        }
    }

    @Test
    fun `legacy Neo and external color profile selections map to NeoProCamX cinema`() {
        assertEquals("N-Log Cinema", SettingsOptionCatalog.selectedVideoColorProfile("Neo Flex Curve"))
        assertEquals("N-Log Cinema", SettingsOptionCatalog.selectedVideoColorProfile("Neo Legacy Curve"))
        assertEquals("N-Log Cinema", SettingsOptionCatalog.selectedVideoColorProfile("Neo Film Curve"))
        assertEquals("N-Log Cinema", SettingsOptionCatalog.selectedVideoColorProfile("Neo Deep Curve"))
        assertEquals("N-Log Cinema", SettingsOptionCatalog.selectedVideoColorProfile("External Log Alias"))
        assertEquals("N-Log Cinema", SettingsOptionCatalog.selectedVideoColorProfile("External Cinema Gamut"))
    }

    @Test
    fun `n log color profile selection stays on original n log profile`() {
        assertEquals("N-Log Base", SettingsOptionCatalog.selectedVideoColorProfile("N Log"))
        assertEquals("N-Log Base", SettingsOptionCatalog.selectedVideoColorProfile("NeoPro N-Log"))
        assertEquals("N-Log Shadow", SettingsOptionCatalog.selectedVideoColorProfile("N-Log Shadow"))
        assertEquals("N-Log Highlight", SettingsOptionCatalog.selectedVideoColorProfile("N-Log Highlight"))
        assertEquals("N-Log Cinema", SettingsOptionCatalog.selectedVideoColorProfile("N-Log Cinema"))
    }

    @Test
    fun `raw ui exposes only the uncompressed 12 bit raw video master path`() {
        val choices = SettingsOptionCatalog.rawCompressionChoicesForMode(CinemaVideoMode.RAW)
        val values = choices.map { it.value }

        assertEquals(listOf(RawCompressionMode.HIGH_COMPRESSION), values)
        assertTrue(choices.single().label.contains("UNCOMPRESSED 12-BIT"))
    }

    @Test
    fun `stabilization choices expose only modes supported by the active camera`() {
        assertEquals(
            listOf(StabilizationMode.OFF),
            SettingsOptionCatalog.stabilizationChoicesForCapabilities(
                supportsOis = false,
                supportsVideoStabilization = false
            ).map { it.value }
        )
        assertEquals(
            listOf(StabilizationMode.OFF, StabilizationMode.OIS),
            SettingsOptionCatalog.stabilizationChoicesForCapabilities(
                supportsOis = true,
                supportsVideoStabilization = false
            ).map { it.value }
        )
        assertEquals(
            listOf(StabilizationMode.OFF, StabilizationMode.EIS),
            SettingsOptionCatalog.stabilizationChoicesForCapabilities(
                supportsOis = false,
                supportsVideoStabilization = true
            ).map { it.value }
        )
        assertEquals(
            listOf(
                StabilizationMode.OFF,
                StabilizationMode.OIS,
                StabilizationMode.EIS,
                StabilizationMode.HYBRID
            ),
            SettingsOptionCatalog.stabilizationChoicesForCapabilities(
                supportsOis = true,
                supportsVideoStabilization = true
            ).map { it.value }
        )
        assertEquals(
            StabilizationMode.EIS,
            SettingsOptionCatalog.selectedStabilizationForCapabilities(
                requestedMode = StabilizationMode.OIS,
                supportsOis = false,
                supportsVideoStabilization = true
            )
        )
        assertEquals(
            StabilizationMode.OIS,
            SettingsOptionCatalog.selectedStabilizationForCapabilities(
                requestedMode = StabilizationMode.EIS,
                supportsOis = true,
                supportsVideoStabilization = false
            )
        )
        assertEquals(
            listOf(StabilizationMode.OFF),
            SettingsOptionCatalog.stabilizationChoicesForCapabilities(
                supportsOis = true,
                supportsVideoStabilization = true,
                rawVideoCaptureEnabled = true
            ).map { it.value }
        )
        assertEquals(
            StabilizationMode.OFF,
            SettingsOptionCatalog.selectedStabilizationForCapabilities(
                requestedMode = StabilizationMode.HYBRID,
                supportsOis = true,
                supportsVideoStabilization = true,
                rawVideoCaptureEnabled = true
            )
        )
        assertEquals(
            StabilizationMode.OIS,
            SettingsOptionCatalog.selectedStabilizationForCapabilities(
                requestedMode = StabilizationMode.HYBRID,
                supportsOis = true,
                supportsVideoStabilization = true
            )
        )
        assertEquals(
            StabilizationMode.EIS,
            SettingsOptionCatalog.selectedStabilizationForCapabilities(
                requestedMode = StabilizationMode.HYBRID,
                supportsOis = true,
                supportsVideoStabilization = true,
                supportsPreviewStabilization = true
            )
        )
    }
}
