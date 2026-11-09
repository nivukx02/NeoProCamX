package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.NprawFrameCodec
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Opt-in local verification for a RAW master pulled from a phone.
 *
 * Run with:
 *   -Dcamx.rawClipDir=<directory containing stream.npraw + develop.meta>
 *   -Dcamx.rawOut=<output mov path>
 *
 * Environment-variable equivalents are CAMX_RAW_CLIP_DIR and CAMX_RAW_OUT.
 *
 * Without an external clip, the test creates a deterministic local RAW fixture so normal test runs
 * remain hermetic and still exercise the complete on-disk conversion path.
 */
class RawDeviceClipConversionVerificationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun convertsPulledDeviceRawClipToNeoLogMovWhenProvided() {
        val configuredClipDir = System.getProperty("camx.rawClipDir")
            ?: System.getenv("CAMX_RAW_CLIP_DIR")
        val clipDir = configuredClipDir?.let(::File) ?: createSyntheticClip()
        val output = (System.getProperty("camx.rawOut") ?: System.getenv("CAMX_RAW_OUT"))?.let(::File)
            ?: if (configuredClipDir != null) {
                File(clipDir, "${clipDir.name}_neolog_v210.mov")
            } else {
                temporaryFolder.newFile("synthetic_neolog_v210.mov")
            }

        val clipRoot = requireNotNull(clipDir.parentFile) {
            "RAW clip directory must have a parent: ${clipDir.absolutePath}"
        }
        val clip = Raw12MasterStore(clipRoot).resolveClip(clipDir)
            ?: error("RAW clip must contain stream.npraw and develop.meta: ${clipDir.absolutePath}")
        val result = Raw12ClipConversionCoordinator().convert(
            clip = clip,
            options = RawToNeoLogMovConverter.Options(
                format = RawToNeoLogMovConverter.OutputFormat.COMPAT_V210
            ),
            outputPath = output.absolutePath,
            parallelism = 1
        )

        assertTrue("device RAW clip must convert at least one frame", result.framesConverted > 0)
        assertTrue("converted MOV must be non-empty", output.isFile && output.length() > 0L)
    }

    private fun createSyntheticClip(): File {
        val width = 6
        val height = 2
        val clipDir = temporaryFolder.newFolder("synthetic_raw_clip")
        val encodedFrame = NprawFrameCodec.encodeFrame(
            mode = RawCompressionMode.HIGH_COMPRESSION,
            rawSamples = IntArray(width * height) { sampleIndex -> 160 + sampleIndex },
            width = width,
            height = height,
            rowStride = width * 2,
            pixelStride = 2,
            timestamp = 1L,
            originalSize = width * height * 2,
            sensorWhiteLevel = 1_000
        ).bytes
        File(clipDir, Raw12MasterStore.STREAM_NAME).writeBytes(encodedFrame)
        File(clipDir, Raw12MasterStore.META_NAME).writeBytes(
            RawDevelopMetadataSidecar.encode(
                RawDevelopMetadata(
                    width = width,
                    height = height,
                    storageBitDepth = 12,
                    sensorWhiteLevel = 1_000,
                    blackLevel = 0,
                    whiteLevel = 1_000,
                    bayerPatternValue = 0,
                    whiteBalanceR = 1f,
                    whiteBalanceGr = 1f,
                    whiteBalanceGb = 1f,
                    whiteBalanceB = 1f,
                    exposureTimeNs = 0L,
                    analogGain = 1f,
                    digitalGain = 1f,
                    noiseS = 0f,
                    noiseO = 0f,
                    sensorToXyz = DEFAULT_SENSOR_TO_XYZ,
                    fps = 30,
                    colorProfileTag = "N-LOG-BASE"
                )
            )
        )
        return clipDir
    }
}
