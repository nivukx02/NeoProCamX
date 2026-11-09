package com.neoproc.neoproccamx.ui

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDeviceMatrixPhase6PolicyTest {

    @Test
    fun `phase6 namespace creates unique row labels for the full matrix`() {
        val plan = RecordingDeviceMatrixPolicy.resolveDefaultPlan(
            durationMs = 900L,
            validationNamespace = "phase6"
        )
        val labels = plan.rows.map { it.validationLabel }

        assertEquals(19, plan.rows.size)
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.all { it.startsWith("phase6_") })
        assertTrue(plan.rows.any { it.validationLabel.contains("hdr_hlg") })
        assertTrue(plan.rows.any { it.validationLabel.contains("ndr10_plus") })
        assertTrue(plan.rows.any { it.validationLabel.contains("log_nlog_base") })
        assertTrue(plan.rows.any { it.validationLabel.contains("raw_12bit") })
    }

    @Test
    fun `adb command rendering includes every debug extra required to reproduce a row`() {
        val row = RecordingDeviceMatrixPolicy.resolveDefaultPlan(
            durationMs = 900L,
            validationNamespace = "phase6"
        ).rows.first { it.id == "hdr_hlg_1920x1080_25fps" }

        val command = RecordingDeviceMatrixCommandPolicy.renderBroadcast(
            row = row,
            packageName = "com.neoproc.neoproccamx"
        )

        assertEquals("com.neoproc.neoproccamx.DEBUG_COMMAND", command.action)
        assertEquals("com.neoproc.neoproccamx/.ui.DebugCommandReceiver", command.component)
        assertEquals(row.validationLabel, command.validationLabel)
        assertContainsArgument(command.arguments, "--es", "debug_preset", "HDR")
        assertContainsArgument(command.arguments, "--es", "debug_action", "record_clip")
        assertContainsArgument(command.arguments, "--es", "debug_duration_ms", "900")
        assertContainsArgument(command.arguments, "--es", "debug_validation_label", row.validationLabel)
        assertContainsArgument(command.arguments, "--es", "debug_aspect", "16:9")
        assertContainsArgument(command.arguments, "--es", "debug_video_hdr_mode", "NDR10+")
        assertContainsArgument(command.arguments, "--es", "debug_video_color_space", "Rec.2020")
        assertContainsArgument(command.arguments, "--es", "debug_video_resolution", "1920x1080")
        assertContainsArgument(command.arguments, "--es", "debug_video_fps", "25")
        assertContainsArgument(command.arguments, "--ez", "debug_raw", "false")
        assertContainsArgument(command.arguments, "--ez", "debug_log", "false")
    }

    @Test
    fun `metadata validation passes exact sdr recording and warns on hardware size fallback`() {
        val row = RecordingDeviceMatrixPolicy.resolveDefaultPlan(
            durationMs = 900L,
            validationNamespace = "phase6"
        ).rows.first { it.id == "video_sdr_1920x1080_25fps" }

        val exact = RecordingDeviceMatrixMetadataPolicy.validate(
            row = row,
            observed = observedMetadata(row),
            logClassification = RecordingDeviceMatrixLogClassifier.classify(
                lines = listOf(
                    "Recording parity[recording-start] delivery=SDR feed=CAMERA2_PREVIEW_PARITY_RECORD_SURFACE hdr=OFF color=Rec.709 log=false raw=false tenBit=true",
                    "Recording saved -> content://media/external/video/media/100"
                )
            )
        )

        assertTrue(exact.passed)
        assertTrue(exact.blockingIssues.isEmpty())
        assertTrue(exact.warnings.isEmpty())

        val fallback = RecordingDeviceMatrixMetadataPolicy.validate(
            row = row,
            observed = observedMetadata(row).copy(width = 1440, height = 1080),
            logClassification = RecordingDeviceMatrixLogClassifier.classify(
                lines = listOf(
                    "applyResolvedRecordingConfig requested=1920x1080 output=1440x1080 requestedFps=25 appliedFps=25",
                    "Recording saved -> content://media/external/video/media/101"
                )
            )
        )

        assertFalse(fallback.passed)
        assertTrue(fallback.blockingIssues.isEmpty())
        assertTrue(fallback.warnings.any { it.code == RecordingDeviceMatrixValidationCode.HARDWARE_METADATA_FALLBACK })
    }

    @Test
    fun `metadata validation allows android scanner mp4 mime for mov container as warning`() {
        val row = RecordingDeviceMatrixPolicy.resolveDefaultPlan(
            durationMs = 900L,
            validationNamespace = "phase6"
        ).rows.first { it.id == "video_sdr_1920x1080_25fps" }

        val result = RecordingDeviceMatrixMetadataPolicy.validate(
            row = row,
            observed = observedMetadata(row).copy(mimeType = "video/mp4"),
            logClassification = RecordingDeviceMatrixLogClassifier.classify(
                lines = listOf(
                    "Recording parity[recording-start] delivery=SDR feed=CAMERA2_PREVIEW_PARITY_RECORD_SURFACE hdr=OFF color=Rec.709 log=false raw=false tenBit=true",
                    "Recording saved -> content://media/external/video/media/103"
                )
            )
        )

        assertFalse(result.passed)
        assertTrue(result.blockingIssues.isEmpty())
        assertTrue(result.warnings.any { it.code == RecordingDeviceMatrixValidationCode.MEDIASTORE_MIME_FALLBACK })
    }

    @Test
    fun `metadata validation warns when applied recording fps falls back`() {
        val row = RecordingDeviceMatrixPolicy.resolveDefaultPlan(
            durationMs = 900L,
            validationNamespace = "phase6"
        ).rows.first { it.id == "video_sdr_1920x1080_60fps" }

        val result = RecordingDeviceMatrixMetadataPolicy.validate(
            row = row,
            observed = observedMetadata(row).copy(captureFrameRate = null),
            logClassification = RecordingDeviceMatrixLogClassifier.classify(
                lines = listOf(
                    "readDebugIntent validationLabel=phase6_video_sdr_1920x1080_60fps resolution=1920x1080 fps=60",
                    "appliedDebugConfig mode=VIDEO colorSpace=Rec.709 hdrMode=OFF videoMode=PRO fps=25 compression=HIGH_COMPRESSION",
                    "Recording parity[recording-start] delivery=SDR feed=CAMERA2_PREVIEW_PARITY_RECORD_SURFACE hdr=OFF color=Rec.709 log=false raw=false tenBit=true",
                    "Recording saved -> content://media/external/video/media/104"
                )
            )
        )

        assertFalse(result.passed)
        assertTrue(result.blockingIssues.isEmpty())
        assertTrue(result.warnings.any { it.code == RecordingDeviceMatrixValidationCode.RECORDING_FPS_FALLBACK })
    }

    @Test
    fun `metadata validation fails when log row is saved as hdr`() {
        val row = RecordingDeviceMatrixPolicy.resolveDefaultPlan(
            durationMs = 900L,
            validationNamespace = "phase6"
        ).rows.first { it.id == "log_nlog_base_1920x1080_25fps" }

        val result = RecordingDeviceMatrixMetadataPolicy.validate(
            row = row,
            observed = observedMetadata(row).copy(
                colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                colorTransfer = MediaFormat.COLOR_TRANSFER_HLG
            ),
            logClassification = RecordingDeviceMatrixLogClassifier.classify(
                lines = listOf(
                    "Recording parity[recording-start] delivery=HDR feed=CAMERA2_PREVIEW_PARITY_RECORD_SURFACE hdr=HLG color=Rec.2020-HLG log=false raw=false tenBit=true",
                    "Recording saved -> content://media/external/video/media/102"
                )
            )
        )

        assertFalse(result.passed)
        assertTrue(result.blockingIssues.any { it.code == RecordingDeviceMatrixValidationCode.DELIVERY_KIND_MISMATCH })
        assertTrue(result.blockingIssues.any { it.code == RecordingDeviceMatrixValidationCode.LOG_SAVED_AS_HDR })
    }

    @Test
    fun `log classifier separates known platform warnings from blocking app failures`() {
        val classification = RecordingDeviceMatrixLogClassifier.classify(
            lines = listOf(
                "05-20 10:00:00.000 111 222 W VideoCapabilities: Unrecognized profile 8 for video/hevc",
                "05-20 10:00:00.010 111 222 W AString : ctor got NULL, using empty string instead",
                "05-20 10:00:00.020 111 222 E RecordingManager: Failed to start encoder",
                "05-20 10:00:00.030 111 222 E AndroidRuntime: FATAL EXCEPTION: main"
            )
        )

        assertEquals(2, classification.platformNoise.size)
        assertEquals(2, classification.blockingIssues.size)
        assertTrue(classification.blockingIssues.any { it.contains("RecordingManager") })
        assertTrue(classification.blockingIssues.any { it.contains("AndroidRuntime") })
    }

    private fun observedMetadata(row: RecordingDeviceMatrixRow): RecordingDeviceMatrixObservedMetadata =
        RecordingDeviceMatrixObservedMetadata(
            validationLabel = row.validationLabel,
            displayName = "CAMX_GPU_${row.validationLabel}_20260520_100000.mov",
            mimeType = row.expectedMediaStoreMime,
            width = row.target.width,
            height = row.target.height,
            captureFrameRate = row.target.fps.toFloat(),
            colorStandard = row.graphContract.colorStandard,
            colorTransfer = row.graphContract.colorTransfer,
            colorRange = row.graphContract.colorRange,
            durationMs = 1_100L,
            bitrateBitsPerSecond = 160_000_000,
            relativePath = "DCIM/CamX/",
            savedUri = "content://media/external/video/media/100"
        )

    private fun assertContainsArgument(
        arguments: List<String>,
        option: String,
        key: String,
        value: String
    ) {
        val index = arguments.windowed(size = 3, step = 1)
            .indexOfFirst { it == listOf(option, key, value) }
        assertTrue("Missing argument triplet: $option $key $value in $arguments", index >= 0)
    }
}
