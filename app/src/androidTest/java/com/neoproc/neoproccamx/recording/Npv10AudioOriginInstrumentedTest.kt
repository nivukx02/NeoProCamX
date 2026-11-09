package com.neoproc.neoproccamx.recording

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real-device LQ-034 proof for the NPV10 -> Main10 A/V timeline origin contract. */
@RunWith(AndroidJUnit4::class)
class Npv10AudioOriginInstrumentedTest {
    @Test
    fun latestAudioMasterExportsMain10WithZeroOriginAndExactSourceAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val requestedMasterName = InstrumentationRegistry.getArguments()
            .getString(ARG_MASTER_NAME)
            .orEmpty()
            .trim()
        val candidates = Npv10MasterStore(context.filesDir)
            .listMasters()
            .asSequence()
            .map { it.file }
            .filter { Npv10AudioSidecarPolicy.sidecarFor(it).isFile }
            .toList()
        val master = if (requestedMasterName.isEmpty()) {
            candidates.maxByOrNull(File::lastModified)
        } else {
            candidates.singleOrNull { it.name == requestedMasterName }
        }
            ?: throw AssertionError("No finalized NPV10 master with AAC sidecar is available")

        var sourcePackets = 0L
        val sourceAudioAudit = Npv10AudioSidecarReader(master).use { reader ->
            while (reader.readNextPacket() != null) sourcePackets++
            reader.requireAudit()
        }
        val sourceHeader = Npv10FileReader(master).use { it.header }
        val capability = RecordDevelopHevcMovEncoder.strictCapabilityReport(
            width = sourceHeader.width,
            height = sourceHeader.height,
            fps = sourceHeader.fps
        )
        assumeTrue(
            "Hardware cannot produce strict HEVC Main10 for this retained source: " +
                capability.rejectionMessage(),
            capability.accepted
        )

        val evidenceDir = requireNotNull(context.getExternalFilesDir("lq034"))
            .apply { check(isDirectory || mkdirs()) }
        val output = File(evidenceDir, "lq034-main10-zero-origin.mov")
        val report = File(evidenceDir, "lq034-main10-zero-origin-audit.txt")
        check(!output.exists() || output.delete()) { "Unable to replace prior LQ-034 MOV" }
        check(!report.exists() || report.delete()) { "Unable to replace prior LQ-034 audit" }

        val audit = Npv10MovConverter.convertWithAudit(
            input = master,
            output = output,
            outputFormat = Npv10MovConverter.OutputFormat.HEVC_MAIN10_420
        )

        assertEquals(Npv10AuditStatus.PASS, audit.auditStatus)
        assertTrue("Converted Main10 MOV was not created", output.isFile && output.length() > 0L)
        assertTrue("Required captured audio was not retained", audit.audioExpected && audit.audioPresent)
        assertEquals("Source AAC packet count changed", sourcePackets, audit.audioPacketCount)
        assertEquals(
            "Source AAC first PTS changed",
            sourceAudioAudit.firstPresentationTimeUs,
            audit.audioSourceFirstPresentationTimeUs
        )
        assertEquals(
            "Source AAC final PTS changed",
            sourceAudioAudit.finalPresentationTimeUs,
            audit.audioFinalPresentationTimeUs
        )
        assertEquals(
            "Source AAC payload byte count changed",
            sourceAudioAudit.payloadBytes,
            audit.audioPayloadBytes
        )
        assertTrue("Source AAC payloads were not packet-copied", audit.audioOriginalPacketsPreserved)
        assertTrue("AAC packet PTS validation failed", audit.audioMonotonicPts)
        assertTrue("AAC packet CRC validation failed", audit.audioCrcValidated)
        assertEquals("Exported AAC track did not start at zero", 0L, audit.audioFirstPresentationTimeUs)
        assertTrue("Exported AAC origin was not certified", audit.audioZeroOrigin)
        assertEquals(
            sourcePackets + audit.audioLeadingSilencePacketCount.toLong(),
            audit.audioMuxedPacketCount
        )
        if (sourceAudioAudit.firstPresentationTimeUs >=
            Npv10AudioSidecarPolicy.nominalPacketDurationUs(Npv10AudioSidecarPolicy.SAMPLE_RATE)
        ) {
            assertTrue(
                "Delayed source audio did not receive a codec-valid silence prefix",
                audit.audioLeadingSilencePacketCount > 0
            )
        }

        report.writeText(
            buildString {
                appendLine("checkpoint=LQ-034")
                appendLine("sourceMaster=${master.absolutePath}")
                appendLine("sourceWidth=${sourceHeader.width}")
                appendLine("sourceHeight=${sourceHeader.height}")
                appendLine("sourceFps=${sourceHeader.fps}")
                appendLine("sourceAudioPacketCount=$sourcePackets")
                appendLine("sourceAudioFirstPresentationTimeUs=${sourceAudioAudit.firstPresentationTimeUs}")
                appendLine("sourceAudioFinalPresentationTimeUs=${sourceAudioAudit.finalPresentationTimeUs}")
                appendLine("output=${output.absolutePath}")
                appendLine("outputBytes=${output.length()}")
                appendLine("outputAudioFirstPresentationTimeUs=${audit.audioFirstPresentationTimeUs}")
                appendLine("outputAudioMuxedPacketCount=${audit.audioMuxedPacketCount}")
                appendLine("leadingSilencePacketCount=${audit.audioLeadingSilencePacketCount}")
                appendLine("leadingSilenceCoveredDurationUs=${audit.audioLeadingSilenceCoveredDurationUs}")
                appendLine("leadingSilenceResidualGapUs=${audit.audioLeadingSilenceResidualGapUs}")
                appendLine("originalPacketsPreserved=${audit.audioOriginalPacketsPreserved}")
                appendLine("zeroOrigin=${audit.audioZeroOrigin}")
                append(audit.toCertificateText())
            }
        )
    }

    private companion object {
        const val ARG_MASTER_NAME = "neoNpv10MasterName"
    }
}
