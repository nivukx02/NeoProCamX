package com.neoproc.neoproccamx.ui

import android.media.MediaFormat
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDeviceMatrixPolicyTest {

    @Test
    fun `default matrix covers every recording delivery family`() {
        val plan = RecordingDeviceMatrixPolicy.resolveDefaultPlan(durationMs = 1_200L)

        assertTrue(plan.rows.any { it.expectedDeliveryKind == VideoDeliveryKind.SDR })
        assertTrue(plan.rows.any { it.expectedDeliveryKind == VideoDeliveryKind.HDR })
        assertTrue(plan.rows.any { it.expectedDeliveryKind == VideoDeliveryKind.LOG })
        assertTrue(plan.rows.any { it.expectedDeliveryKind == VideoDeliveryKind.RAW })
        assertEquals(
            setOf(
                RecordingDeviceColorMetadataFamily.REC709_SDR,
                RecordingDeviceColorMetadataFamily.BT2020_HLG,
                RecordingDeviceColorMetadataFamily.BT2020_PQ,
                RecordingDeviceColorMetadataFamily.REC2020_LOG,
                RecordingDeviceColorMetadataFamily.RAW_SENSOR
            ),
            plan.coverage.colorFamilies
        )
    }

    @Test
    fun `processed rows are ten bit mov rows and use internal hardware import recording`() {
        val rows = RecordingDeviceMatrixPolicy.resolveDefaultPlan(durationMs = 1_200L)
            .rows
            .filterNot { it.expectedDeliveryKind == VideoDeliveryKind.RAW }

        assertTrue(rows.isNotEmpty())
        rows.forEach { row ->
            assertEquals(10, row.expectedBitDepth)
            assertEquals("mov", row.expectedContainerExtension)
            assertEquals("video/quicktime", row.expectedMediaStoreMime)
            assertTrue(row.previewRecordingParityRequired)
            assertEquals(
                VideoCameraRequestFamily.VIDEO_PREVIEW_RECORDING_PARITY,
                row.graphContract.cameraRequestFamily
            )
            assertEquals(
                RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
                row.parityPlan.feedPath
            )
            assertTrue(row.parityPlan.recordingGradePreview)
        }
    }

    @Test
    fun `log rows disable hdr transport and parse as log debug commands`() {
        val logRows = RecordingDeviceMatrixPolicy.resolveDefaultPlan(durationMs = 1_200L)
            .rows
            .filter { it.expectedDeliveryKind == VideoDeliveryKind.LOG }

        assertTrue(logRows.size >= 2)
        logRows.forEach { row ->
            assertEquals("OFF", row.requestedHdrMode)
            assertEquals("OFF", row.graphContract.effectiveHdrMode)
            assertFalse(row.parityPlan.hdrRequestedForSessionPlan)
            assertTrue(row.parityPlan.logEnabledForSessionPlan)
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, row.graphContract.colorTransfer)

            val parsed = DebugCommandParser.parse(row.debugExtras)
            assertNotNull(parsed)
            requireNotNull(parsed)
            assertEquals(CinemaVideoMode.LOG, parsed.videoMode)
            assertEquals(true, parsed.log)
            assertEquals(false, parsed.raw)
            assertEquals("OFF", parsed.videoHdrMode)
        }
    }

    @Test
    fun `hdr rows carry hdr metadata and never parse as log or raw`() {
        val hdrRows = RecordingDeviceMatrixPolicy.resolveDefaultPlan(durationMs = 1_200L)
            .rows
            .filter { it.expectedDeliveryKind == VideoDeliveryKind.HDR }

        assertTrue(hdrRows.any { it.colorMetadataFamily == RecordingDeviceColorMetadataFamily.BT2020_HLG })
        assertTrue(hdrRows.any { it.colorMetadataFamily == RecordingDeviceColorMetadataFamily.BT2020_PQ })
        hdrRows.forEach { row ->
            assertTrue(row.parityPlan.hdrRequestedForSessionPlan)
            assertFalse(row.parityPlan.logEnabledForSessionPlan)
            assertFalse(row.parityPlan.rawRequestedForSessionPlan)
            assertEquals(MediaFormat.COLOR_STANDARD_BT2020, row.graphContract.colorStandard)
            assertTrue(
                row.graphContract.colorTransfer == MediaFormat.COLOR_TRANSFER_HLG ||
                    row.graphContract.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084
            )

            val parsed = DebugCommandParser.parse(row.debugExtras)
            assertNotNull(parsed)
            requireNotNull(parsed)
            assertEquals(false, parsed.log)
            assertEquals(false, parsed.raw)
            assertTrue(parsed.videoHdrMode != "OFF")
        }
    }

    @Test
    fun `raw row is twelve bit mov and uses raw direct path only`() {
        val rawRows = RecordingDeviceMatrixPolicy.resolveDefaultPlan(durationMs = 1_200L)
            .rows
            .filter { it.expectedDeliveryKind == VideoDeliveryKind.RAW }

        assertTrue(rawRows.isNotEmpty())
        rawRows.forEach { row ->
            assertEquals(12, row.expectedBitDepth)
            assertEquals("mov", row.expectedContainerExtension)
            assertEquals("video/quicktime", row.expectedMediaStoreMime)
            assertFalse(row.previewRecordingParityRequired)
            assertEquals(VideoCameraRequestFamily.RAW_SENSOR_TRUTHFUL, row.graphContract.cameraRequestFamily)
            assertEquals(RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE, row.parityPlan.feedPath)
            assertTrue(row.parityPlan.rawRequestedForSessionPlan)
            assertFalse(row.parityPlan.tenBitRequestedForSessionPlan)

            val parsed = DebugCommandParser.parse(row.debugExtras)
            assertNotNull(parsed)
            requireNotNull(parsed)
            assertEquals(CinemaVideoMode.RAW, parsed.videoMode)
            assertEquals(true, parsed.raw)
            assertEquals(false, parsed.log)
        }
    }

    @Test
    fun `debug extras are unique record clip commands with parseable dimensions`() {
        val plan = RecordingDeviceMatrixPolicy.resolveDefaultPlan(durationMs = 1_200L)
        val labels = plan.rows.map { it.validationLabel }

        assertEquals(labels.size, labels.toSet().size)
        plan.rows.forEach { row ->
            assertEquals("record_clip", row.debugExtras.action)
            assertEquals("1200", row.debugExtras.durationMs)
            assertEquals(row.validationLabel, row.debugExtras.validationLabel)
            assertEquals(row.target.aspectToken, row.debugExtras.aspect)
            assertEquals("${row.target.width}x${row.target.height}", row.debugExtras.videoResolution)
            assertEquals(row.target.fps.toString(), row.debugExtras.videoFps)

            val parsed = DebugCommandParser.parse(row.debugExtras)
            assertNotNull(parsed)
            requireNotNull(parsed)
            assertEquals(row.target.previewAspectRatio, parsed.aspect)
            assertNotNull(parsed.videoResolution)
            assertEquals(row.target.fps, parsed.videoFps)
        }
    }
}
