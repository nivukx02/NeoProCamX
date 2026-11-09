package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class RecordDevelopMovPublicationGateTest {
    @Test
    fun finalizedMov_isMeasuredBeforeAtomicCertificatePublication() {
        val directory = Files.createTempDirectory("camx-publication-gate").toFile()
        val output = File(directory, "develop.mov")
        val certificate = File(directory, "develop.mov.certificate.txt")
        val staleEliteCertificate = File(directory, "develop.mov.elite-conversion-certificate.txt")
        try {
            writeFinalizedFixture(output, sampleSizes = intArrayOf(100, 120))
            certificate.writeText("STALE")
            staleEliteCertificate.writeText("STALE")

            val publication = RecordDevelopMovPublicationGate.publishFinalized(
                output = output,
                certificateFile = certificate,
                expectation = expectation(frameCount = 2L),
                declaredPipelineAuditText = "frameCount=999\nvideoBitrateBps=1",
                additionalStaleCertificates = listOf(staleEliteCertificate)
            )

            assertTrue(output.isFile)
            assertTrue(certificate.isFile)
            assertFalse(staleEliteCertificate.exists())
            assertEquals(2L, publication.measured.sampleCount)
            assertEquals(220L, publication.measured.samplePayloadBytes)
            assertEquals(0.08, publication.measured.durationSeconds, 0.000_001)
            assertEquals(25.0, publication.measured.measuredAverageFps, 0.000_001)
            assertEquals(22_000L, publication.measured.measuredVideoBitrateBps)
            assertTrue(publication.measured.timingCoversEverySample)
            assertEquals(1L, publication.measured.sampleToChunkEntryCount)
            assertEquals(2L, publication.measured.mappedChunkCount)
            assertEquals(2L, publication.measured.mappedSampleCount)
            assertTrue(publication.measured.chunkOffsetsInsideFile)
            assertTrue(publication.measured.chunkOffsetsInsideMdat)
            assertTrue(publication.measured.sampleExtentsInsideFile)
            assertTrue(publication.measured.sampleExtentsInsideMdat)

            val text = certificate.readText()
            assertTrue(text.contains("evidence.authority=FINALIZED_MOV_READ_ONLY_INSPECTION"))
            assertTrue(text.contains("measured.sampleCount=2"))
            assertTrue(text.contains("measured.videoBitrateBps=22000"))
            assertTrue(text.contains("measured.stscEntryCount=1"))
            assertTrue(text.contains("measured.sampleExtentsInsideMdat=true"))
            assertTrue(text.contains("declared.frameCount=999"))
            assertTrue(text.contains("declared.videoBitrateBps=1"))
            assertFalse(text.lineSequence().any { it == "measured.sampleCount=999" })
            assertFalse(text.lineSequence().any { it == "videoBitrateBps=1" })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedFinalizedMov_deletesOutputAndEveryStaleCertificate() {
        val directory = Files.createTempDirectory("camx-publication-reject").toFile()
        val output = File(directory, "develop.mov")
        val certificate = File(directory, "develop.mov.certificate.txt")
        val staleEliteCertificate = File(directory, "develop.mov.elite-conversion-certificate.txt")
        try {
            writeFinalizedFixture(output, sampleSizes = intArrayOf(64))
            RandomAccessFile(output, "rw").use { it.setLength(it.length() - 1L) }
            certificate.writeText("STALE")
            staleEliteCertificate.writeText("STALE")

            val rejection = expectRejected {
                RecordDevelopMovPublicationGate.publishFinalized(
                    output = output,
                    certificateFile = certificate,
                    expectation = expectation(frameCount = 1L),
                    additionalStaleCertificates = listOf(staleEliteCertificate)
                )
            }

            assertTrue(rejection.message.orEmpty().contains("inspection rejected", ignoreCase = true))
            assertFalse(output.exists())
            assertFalse(certificate.exists())
            assertFalse(staleEliteCertificate.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedBorrowedDescriptor_keepsCallerOwnedOutputButDeletesCertificates() {
        val directory = Files.createTempDirectory("camx-publication-borrowed-reject").toFile()
        val output = File(directory, "borrowed-descriptor.mov")
        val certificate = File(directory, "borrowed-descriptor.certificate.txt")
        val staleEliteCertificate = File(directory, "borrowed-descriptor.elite-certificate.txt")
        try {
            writeFinalizedFixture(output, sampleSizes = intArrayOf(64))
            RandomAccessFile(output, "rw").use { it.setLength(it.length() - 1L) }
            certificate.writeText("STALE")
            staleEliteCertificate.writeText("STALE")

            val rejection = expectRejected {
                RecordDevelopMovPublicationGate.publishFinalized(
                    output = output,
                    certificateFile = certificate,
                    expectation = expectation(frameCount = 1L),
                    additionalStaleCertificates = listOf(staleEliteCertificate),
                    outputOwnership = MovOutputOwnership.BORROWED_SEEKABLE_DESCRIPTOR
                )
            }

            assertTrue(rejection.message.orEmpty().contains("inspection rejected", ignoreCase = true))
            assertTrue("Caller must retain lifecycle ownership of a borrowed descriptor", output.isFile)
            assertFalse(certificate.exists())
            assertFalse(staleEliteCertificate.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun selfReportedFrameCountCannotSubstituteForMeasuredMovEvidence() {
        val directory = Files.createTempDirectory("camx-publication-evidence").toFile()
        val output = File(directory, "develop.mov")
        val certificate = File(directory, "develop.mov.certificate.txt")
        try {
            writeFinalizedFixture(output, sampleSizes = intArrayOf(64, 64))
            certificate.writeText("STALE")

            val rejection = expectRejected {
                RecordDevelopMovPublicationGate.publishFinalized(
                    output = output,
                    certificateFile = certificate,
                    expectation = expectation(frameCount = 3L),
                    declaredPipelineAuditText = "frameCount=3"
                )
            }

            assertTrue(rejection.message.orEmpty().contains("MOV contains 2"))
            assertFalse(output.exists())
            assertFalse(certificate.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sourceDerivedVfrDurationIsAuthoritativeInsteadOfNominalAverageFps() {
        val directory = Files.createTempDirectory("camx-publication-vfr").toFile()
        val output = File(directory, "develop.mov")
        val certificate = File(directory, "develop.mov.certificate.txt")
        try {
            writeFinalizedFixture(
                output = output,
                sampleSizes = intArrayOf(60, 70, 80),
                presentationTimesUs = longArrayOf(100_000L, 120_000L, 200_000L),
                endPresentationTimeUs = 280_000L
            )

            val publication = RecordDevelopMovPublicationGate.publishFinalized(
                output = output,
                certificateFile = certificate,
                expectation = expectation(
                    frameCount = 3L,
                    expectedDurationUs = 180_000L,
                    expectedAverageFps = null
                )
            )

            assertEquals(0.18, publication.measured.durationSeconds, 0.000_001)
            assertEquals(3.0 / 0.18, publication.measured.measuredAverageFps, 0.000_001)
            assertEquals(2L, publication.measured.sttsEntryCount)
            assertTrue(output.isFile)
            assertTrue(certificate.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun mismatchedSourceDerivedVfrDurationRejectsAndDeletesArtifact() {
        val directory = Files.createTempDirectory("camx-publication-vfr-reject").toFile()
        val output = File(directory, "develop.mov")
        val certificate = File(directory, "develop.mov.certificate.txt")
        try {
            writeFinalizedFixture(
                output = output,
                sampleSizes = intArrayOf(60, 70, 80),
                presentationTimesUs = longArrayOf(100_000L, 120_000L, 200_000L),
                endPresentationTimeUs = 280_000L
            )

            val rejection = expectRejected {
                RecordDevelopMovPublicationGate.publishFinalized(
                    output = output,
                    certificateFile = certificate,
                    expectation = expectation(
                        frameCount = 3L,
                        expectedDurationUs = 120_000L,
                        expectedAverageFps = null
                    )
                )
            }

            assertTrue(rejection.message.orEmpty().contains("source-derived duration"))
            assertFalse(output.exists())
            assertFalse(certificate.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun certificateCommitFailure_deletesOtherwiseValidMov() {
        val directory = Files.createTempDirectory("camx-publication-commit").toFile()
        val output = File(directory, "develop.mov")
        val parentBlocker = File(directory, "not-a-directory")
        val certificate = File(parentBlocker, "certificate.txt")
        try {
            writeFinalizedFixture(output, sampleSizes = intArrayOf(64))
            parentBlocker.writeText("BLOCK")

            expectRejected {
                RecordDevelopMovPublicationGate.publishFinalized(
                    output = output,
                    certificateFile = certificate,
                    expectation = expectation(frameCount = 1L)
                )
            }

            assertFalse(output.exists())
            assertFalse(certificate.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeFinalizedFixture(
        output: File,
        sampleSizes: IntArray,
        presentationTimesUs: LongArray? = null,
        endPresentationTimeUs: Long? = null
    ) {
        require(presentationTimesUs == null || presentationTimesUs.size == sampleSizes.size)
        val writer = QuickTimeMovFileWriter(
            outputPath = output.absolutePath,
            containerLabel = "NeoProCamX develop test"
        )
        val track = writer.addVideoTrack(
            QuickTimeVideoTrackConfig(
                mime = "video/r210",
                width = 1280,
                height = 720,
                fps = 25,
                codecConfig = byteArrayOf(),
                colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited()
            )
        )
        sampleSizes.forEachIndexed { index, size ->
            writer.writeSample(
                trackIndex = track,
                sample = QuickTimeSampleBuffer(
                    bytes = ByteArray(size) { ((index + it) and 0x7F).toByte() },
                    presentationTimeUs = presentationTimesUs?.get(index) ?: index * 40_000L,
                    flags = if (index == 0) 1 else 0
                )
            )
        }
        endPresentationTimeUs?.let { writer.setTrackEndPresentationTimeUs(track, it) }
        writer.close()
    }

    private fun expectation(
        frameCount: Long,
        expectedDurationUs: Long = Math.multiplyExact(frameCount, 40_000L),
        expectedAverageFps: Double? = 25.0
    ) = RecordDevelopMovPublicationExpectation(
        sampleEntry = "r210",
        width = 1280,
        height = 720,
        rotationDegrees = 0,
        color = QuickTimeMovNclxExpectation(
            colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT709,
            transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
            matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT709,
            fullRange = false
        ),
        frameCount = frameCount,
        expectedDurationUs = expectedDurationUs,
        expectedAverageFps = expectedAverageFps
    )

    private inline fun expectRejected(block: () -> Unit): RecordDevelopMovPublicationException {
        try {
            block()
            fail("Expected finalized MOV publication rejection")
        } catch (rejection: RecordDevelopMovPublicationException) {
            return rejection
        }
        error("unreachable")
    }
}
