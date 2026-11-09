package com.neoproc.neoproccamx.recording

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoproc.neoproccamx.pipeline.NprawFrameCodec
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Retains current-candidate production RAW-conversion artifacts for the CP-177/178 host oracles.
 *
 * This is deliberately an androidTest-only evidence fixture. It does not alter production cadence,
 * conversion quality, or cleanup. Each invocation uses a unique app-scoped external directory so
 * the exclusive ADB controller can pull the exact source masters, MOVs, and expected timing data.
 */
@RunWith(AndroidJUnit4::class)
class Cp178RetainedMovTimingInstrumentedTest {
    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 48
        const val FPS = 25
        const val SENSOR_TIMESTAMP_ORIGIN_NS = 1_000_000_000L
        const val NOMINAL_FRAME_DURATION_US = 40_000L
        val CFR_PRESENTATION_TIMES_US = longArrayOf(0L, 40_000L, 80_000L, 120_000L, 160_000L)
        val VFR_PRESENTATION_TIMES_US = longArrayOf(0L, 40_000L, 90_000L, 120_000L, 180_000L)
    }

    @Test
    fun retainedCfrAndVfrR210Artifacts_haveExactSourceAndContainerTimingEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evidenceRoot = requireNotNull(context.getExternalFilesDir("neo-qa/cp178")) {
            "App-scoped external evidence directory is unavailable"
        }
        val runDirectory = File(evidenceRoot, "run-${System.currentTimeMillis()}-${System.nanoTime()}")
        assertTrue("Unable to create CP-178 evidence directory", runDirectory.mkdirs())

        val cfr = writeRetainedFixture(runDirectory, "cfr", CFR_PRESENTATION_TIMES_US)
        val vfr = writeRetainedFixture(runDirectory, "vfr", VFR_PRESENTATION_TIMES_US)
        assertTrue("The VFR fixture must contain non-uniform sample intervals", !isConstantRate(vfr.ptsUs))
        assertTrue("The CFR fixture must contain uniform sample intervals", isConstantRate(cfr.ptsUs))

        val manifest = File(runDirectory, "cp178-expected.properties")
        manifest.writeText(
            buildString {
                appendLine("schema=neo-qa-cp177-cp178-production-raw-timing-v2")
                appendLine("conversionPath=Raw12ClipConversionCoordinator")
                appendLine("sampleEntry=r210")
                appendLine("width=$WIDTH")
                appendLine("height=$HEIGHT")
                appendLine("fps=$FPS")
                appendLine("timescale=1000000")
                appendFixture("cfr", cfr)
                appendFixture("vfr", vfr)
            },
            Charsets.UTF_8
        )
        assertTrue("CP-178 expectation manifest was not retained", manifest.length() > 0L)
        File(runDirectory, "cp178-expected.properties.sha256").writeText(
            "${sha256(manifest)}  ${manifest.name}\n",
            Charsets.US_ASCII
        )

        // The stable pointer contains only a relative directory name. Prior runs remain retained.
        File(evidenceRoot, "LATEST").writeText("${runDirectory.name}\n", Charsets.US_ASCII)
    }

    private fun writeRetainedFixture(
        runDirectory: File,
        label: String,
        presentationTimesUs: LongArray
    ): RetainedFixture {
        require(presentationTimesUs.isNotEmpty())
        require(presentationTimesUs.first() == 0L)
        require((1 until presentationTimesUs.size).all { index ->
            presentationTimesUs[index] > presentationTimesUs[index - 1]
        })

        val master = File(runDirectory, "cp178-$label-source.npraw")
        val metadataFile = File(runDirectory, "cp178-$label-develop.meta")
        val output = File(runDirectory, "cp178-$label-production-r210.mov")
        val meta = rawDevelopMetadata()
        metadataFile.writeBytes(RawDevelopMetadataSidecar.encode(meta))
        val expectedSourceTimestampsNs = presentationTimesUs.map { ptsUs ->
            Math.addExact(SENSOR_TIMESTAMP_ORIGIN_NS, Math.multiplyExact(ptsUs, 1_000L))
        }
        master.outputStream().buffered().use { stream ->
            presentationTimesUs.indices.forEach { frameIndex ->
                val rawSamples = syntheticRawSamples(frameIndex)
                stream.write(
                    NprawFrameCodec.encodeFrame(
                        mode = RawCompressionMode.HIGH_COMPRESSION,
                        rawSamples = rawSamples,
                        width = WIDTH,
                        height = HEIGHT,
                        rowStride = WIDTH * Short.SIZE_BYTES,
                        pixelStride = Short.SIZE_BYTES,
                        timestamp = expectedSourceTimestampsNs[frameIndex],
                        originalSize = WIDTH * HEIGHT * Short.SIZE_BYTES,
                        sensorWhiteLevel = 4095
                    ).bytes
                )
            }
        }

        val decodedSourceTimestampsNs = mutableListOf<Long>()
        master.inputStream().buffered().use { stream ->
            while (true) {
                val frame = NprawFrameCodec.readFrame(stream) ?: break
                assertEquals(WIDTH, frame.width)
                assertEquals(HEIGHT, frame.height)
                decodedSourceTimestampsNs += frame.timestamp
            }
        }
        assertEquals(expectedSourceTimestampsNs, decodedSourceTimestampsNs)
        val decodedMeta = RawDevelopMetadataSidecar.decode(metadataFile.readBytes())
        assertEquals(WIDTH, decodedMeta.width)
        assertEquals(HEIGHT, decodedMeta.height)
        assertEquals(FPS, decodedMeta.fps)

        val result = master.inputStream().buffered().use { stream ->
            Raw12ClipConversionCoordinator().convert(
                streamInput = stream,
                meta = decodedMeta,
                options = RawToNeoLogMovConverter.Options(
                    format = RawToNeoLogMovConverter.OutputFormat.MASTER_444,
                    deliveryProfile = RawToNeoLogMovConverter.DeliveryProfile.NEOLOG
                ),
                outputPath = output.absolutePath,
                parallelism = 2
            )
        }
        assertEquals(presentationTimesUs.size, result.framesConverted)
        assertTrue("Production RAW conversion reported no payload", result.bytesWritten > 0L)

        assertTrue("Retained $label NPRAW master is empty", master.length() > 0L)
        assertTrue("Retained $label develop metadata is empty", metadataFile.length() > 0L)
        assertTrue("Retained $label R210 MOV is empty", output.length() > 0L)
        val inspection = QuickTimeMovArtifactInspector.inspect(
            output,
            QuickTimeMovArtifactRequirements(
                allowedVideoSampleEntries = setOf("r210"),
                expectedVideoSampleEntry = "r210",
                expectedWidth = WIDTH,
                expectedHeight = HEIGHT,
                minimumSampleCount = presentationTimesUs.size.toLong()
            )
        ).requirePass()
        val outputFrameCount = requireNotNull(inspection.videoTrack).sampleCount
        assertEquals(presentationTimesUs.size.toLong(), outputFrameCount)
        assertEquals(decodedSourceTimestampsNs.size.toLong(), outputFrameCount)
        val viewingLut = NeoLogRec709LutWriter.sidecarFile(output.absolutePath)
        val qualityCertificate = File("${output.absolutePath}.elite-conversion-certificate.txt")
        assertTrue("Production RAW conversion did not retain its viewing LUT", viewingLut.isFile)
        assertTrue("Production RAW conversion did not retain its quality certificate", qualityCertificate.isFile)

        val durationsUs = sampleDurations(presentationTimesUs)
        val endPresentationTimeUs = Math.addExact(presentationTimesUs.last(), durationsUs.last())

        return RetainedFixture(
            master = master,
            metadata = metadataFile,
            output = output,
            viewingLut = viewingLut,
            qualityCertificate = qualityCertificate,
            ptsUs = presentationTimesUs.copyOf(),
            sourceTimestampsNs = decodedSourceTimestampsNs.toLongArray(),
            durationsUs = durationsUs,
            endPresentationTimeUs = endPresentationTimeUs,
            masterSha256 = sha256(master),
            metadataSha256 = sha256(metadataFile),
            outputSha256 = sha256(output),
            viewingLutSha256 = sha256(viewingLut),
            qualityCertificateSha256 = sha256(qualityCertificate)
        )
    }

    private fun StringBuilder.appendFixture(label: String, fixture: RetainedFixture) {
        appendLine("$label.master=${fixture.master.name}")
        appendLine("$label.masterSha256=${fixture.masterSha256}")
        appendLine("$label.metadata=${fixture.metadata.name}")
        appendLine("$label.metadataSha256=${fixture.metadataSha256}")
        appendLine("$label.output=${fixture.output.name}")
        appendLine("$label.outputSha256=${fixture.outputSha256}")
        appendLine("$label.viewingLut=${fixture.viewingLut.name}")
        appendLine("$label.viewingLutSha256=${fixture.viewingLutSha256}")
        appendLine("$label.qualityCertificate=${fixture.qualityCertificate.name}")
        appendLine("$label.qualityCertificateSha256=${fixture.qualityCertificateSha256}")
        appendLine("$label.sampleCount=${fixture.ptsUs.size}")
        appendLine("$label.sourceTimestampsNs=${fixture.sourceTimestampsNs.joinToString(",")}")
        appendLine("$label.ptsUs=${fixture.ptsUs.joinToString(",")}")
        appendLine("$label.durationsUs=${fixture.durationsUs.joinToString(",")}")
        appendLine("$label.endPresentationTimeUs=${fixture.endPresentationTimeUs}")
    }

    private fun sampleDurations(presentationTimesUs: LongArray): LongArray =
        LongArray(presentationTimesUs.size) { index ->
            if (index < presentationTimesUs.lastIndex) {
                presentationTimesUs[index + 1] - presentationTimesUs[index]
            } else {
                if (presentationTimesUs.size > 1) {
                    presentationTimesUs.last() - presentationTimesUs[presentationTimesUs.lastIndex - 1]
                } else {
                    NOMINAL_FRAME_DURATION_US
                }
            }
        }

    private fun isConstantRate(presentationTimesUs: LongArray): Boolean {
        if (presentationTimesUs.size < 3) return true
        val firstInterval = presentationTimesUs[1] - presentationTimesUs[0]
        return (1 until presentationTimesUs.size).all { index ->
            presentationTimesUs[index] - presentationTimesUs[index - 1] == firstInterval
        }
    }

    private fun rawDevelopMetadata(): RawDevelopMetadata = RawDevelopMetadata(
        width = WIDTH,
        height = HEIGHT,
        storageBitDepth = 12,
        sensorWhiteLevel = 4095,
        blackLevel = 64,
        whiteLevel = 4095,
        bayerPatternValue = 0,
        whiteBalanceR = 1.0f,
        whiteBalanceGr = 1.0f,
        whiteBalanceGb = 1.0f,
        whiteBalanceB = 1.0f,
        exposureTimeNs = 10_000_000L,
        analogGain = 1.0f,
        digitalGain = 1.0f,
        noiseS = 0.001f,
        noiseO = 0.0001f,
        sensorToXyz = DEFAULT_SENSOR_TO_XYZ,
        fps = FPS,
        colorProfileTag = "N-LOG-CINEMA"
    )

    private fun syntheticRawSamples(frameIndex: Int): IntArray =
        IntArray(WIDTH * HEIGHT) { pixel ->
            val x = pixel % WIDTH
            val y = pixel / WIDTH
            (192 + x * 41 + y * 23 + frameIndex * 29).coerceIn(64, 4095)
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(Locale.US, byte) }
    }

    private data class RetainedFixture(
        val master: File,
        val metadata: File,
        val output: File,
        val viewingLut: File,
        val qualityCertificate: File,
        val ptsUs: LongArray,
        val sourceTimestampsNs: LongArray,
        val durationsUs: LongArray,
        val endPresentationTimeUs: Long,
        val masterSha256: String,
        val metadataSha256: String,
        val outputSha256: String,
        val viewingLutSha256: String,
        val qualityCertificateSha256: String
    )
}
