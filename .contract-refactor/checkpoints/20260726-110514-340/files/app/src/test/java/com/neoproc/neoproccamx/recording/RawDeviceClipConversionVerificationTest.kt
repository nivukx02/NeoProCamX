package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
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
 * With no system property this test is skipped, so normal unit-test runs stay hermetic.
 */
class RawDeviceClipConversionVerificationTest {

    @Test
    fun convertsPulledDeviceRawClipToNeoLogMovWhenProvided() {
        val clipDirPath = System.getProperty("camx.rawClipDir")
            ?: System.getenv("CAMX_RAW_CLIP_DIR")
        assumeTrue("camx.rawClipDir not provided", clipDirPath != null)
        val clipDir = File(requireNotNull(clipDirPath))
        val output = (System.getProperty("camx.rawOut") ?: System.getenv("CAMX_RAW_OUT"))?.let(::File)
            ?: File(clipDir, "${clipDir.name}_neolog_v210.mov")

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
}
