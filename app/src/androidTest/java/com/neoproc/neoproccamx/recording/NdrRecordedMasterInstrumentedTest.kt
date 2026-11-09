package com.neoproc.neoproccamx.recording

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Re-validates every available finalized project NDR-family MOV without assuming an NPV10 master.
 * NDR10+, NIVUKX Vision, and any recorded APEX variants are each checked once when present.
 */
@RunWith(AndroidJUnit4::class)
class NdrRecordedMasterInstrumentedTest {

    @Test
    fun finalizedProjectNdrMovs_proveRawAcescgPqMain10LineageWithoutNpv10() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val available = ProjectNdrRecordedArtifactVerifier
            .discoverPublishedCertificates(context)
            .groupBy { (certificate, _) -> certificate.profile }
            .mapValues { (_, candidates) ->
                candidates.maxBy { (certificate, _) -> certificate.generatedAtEpochMs }
            }
        assumeTrue(
            "No finalized project-owned NDR/NIVUKX/APEX PQ Main10 MOV is available",
            available.isNotEmpty()
        )

        val outputDirectory = File(context.cacheDir, OUTPUT_DIRECTORY).apply { mkdirs() }
        val report = buildString {
            available.toSortedMap().forEach { (profile, evidence) ->
                val (certificate, clip) = evidence
                val verification = ProjectNdrRecordedArtifactVerifier.verify(
                    context = context,
                    certificate = certificate,
                    clip = clip,
                    expectedProfile = profile
                )
                appendLine(
                    "profile=${verification.profile};file=${verification.displayName};" +
                        "frames=${verification.frameCount};fps=${"%.4f".format(verification.measuredFps)};" +
                        "maxFrameIntervalUs=${verification.maximumFrameIntervalUs};" +
                        "certificate=${certificate.file.name}"
                )
                Log.i(TAG, "Verified project NDR artifact: $verification")
            }
        }
        assertTrue("No project NDR artifact was verified", report.isNotBlank())
        File(outputDirectory, REPORT_FILE).writeText(report, StandardCharsets.UTF_8)
    }

    private companion object {
        const val TAG = "NdrRecordedMasterTest"
        const val OUTPUT_DIRECTORY = "ndr_record_validation"
        const val REPORT_FILE = "report.txt"
    }
}
