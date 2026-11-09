package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingProbeValidationPolicyTest {

    @Test
    fun `parses ffprobe main10 pq bt2020 fields`() {
        val probe = RecordingProbeValidationPolicy.fromFields(
            mapOf(
                "codec_name" to "hevc",
                "pix_fmt" to "yuv420p10le",
                "bits_per_raw_sample" to "10",
                "color_space" to "bt2020nc",
                "color_transfer" to "smpte2084",
                "color_primaries" to "bt2020",
                "color_range" to "tv",
                "width" to "3840",
                "height" to "2160",
                "avg_frame_rate" to "60000/1000",
                "bit_rate" to "220000000",
                "rotation" to "90"
            )
        )

        assertEquals("hevc", probe.codec)
        assertEquals("yuv420p10le", probe.pixelFormat)
        assertEquals(10, probe.encodedBitDepth)
        assertEquals("BT.2020", probe.primaries)
        assertEquals("ST2084/PQ", probe.transfer)
        assertEquals("BT.2020-NCL", probe.matrix)
        assertEquals("LIMITED", probe.colorRange)
        assertEquals(3840, probe.width)
        assertEquals(2160, probe.height)
        assertEquals(60, probe.fps)
        assertEquals(220_000_000, probe.bitrateBps)
        assertEquals(90, probe.rotationDegrees)
        assertEquals(false, probe.mirrored)
    }

    @Test
    fun `parses ffprobe key value text`() {
        val probe = RecordingProbeValidationPolicy.fromKeyValueText(
            """
            codec_name=hevc
            pix_fmt=yuv420p
            color_space=bt709
            color_transfer=bt709
            color_primaries=bt709
            width=1920
            height=1080
            r_frame_rate=30000/1000
            bit_rate=80000000
            """.trimIndent()
        )

        assertEquals("hevc", probe.codec)
        assertEquals(8, probe.encodedBitDepth)
        assertEquals("BT.709", probe.primaries)
        assertEquals("BT.709/OETF", probe.transfer)
        assertEquals("BT.709", probe.matrix)
        assertEquals(1920, probe.width)
        assertEquals(1080, probe.height)
        assertEquals(30, probe.fps)
    }

    @Test
    fun `parses mirrored display matrix evidence`() {
        val probe = RecordingProbeValidationPolicy.fromKeyValueText(
            """
            codec_name=hevc
            pix_fmt=yuv420p10le
            color_space=bt2020nc
            color_transfer=smpte2084
            color_primaries=bt2020
            width=1920
            height=1080
            avg_frame_rate=30/1
            bit_rate=120000000
            displaymatrix=mirror_horizontal
            """.trimIndent()
        )

        assertEquals(10, probe.encodedBitDepth)
        assertEquals(0, probe.rotationDegrees)
        assertEquals(true, probe.mirrored)
    }
}
