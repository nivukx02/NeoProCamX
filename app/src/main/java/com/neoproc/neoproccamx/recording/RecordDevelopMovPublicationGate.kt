package com.neoproc.neoproccamx.recording

import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs

/**
 * Expected record-develop facts. These are checks only; they never populate measured evidence.
 *
 * [expectedDurationUs] must come from the normalized source timeline (normally
 * [RecordDevelopPresentationTimeline.endPresentationTimeUs]), not from `frameCount / nominalFps`.
 * [expectedAverageFps] is optional because a valid variable-frame-rate source need not average to the
 * encoder's nominal configuration rate.
 */
internal data class RecordDevelopMovPublicationExpectation(
    val sampleEntry: String,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val color: QuickTimeMovNclxExpectation,
    val frameCount: Long,
    val expectedDurationUs: Long,
    val durationToleranceUs: Long = DEFAULT_DURATION_TOLERANCE_US,
    val expectedAverageFps: Double? = null,
    val fpsTolerance: Double = DEFAULT_FPS_TOLERANCE,
    val minimumMeasuredVideoBitrateBps: Long? = null
) {
    init {
        require(sampleEntry in QuickTimeMovArtifactRequirements.SUPPORTED_VIDEO_SAMPLE_ENTRIES) {
            "Unsupported record-develop MOV sample entry $sampleEntry"
        }
        require(width > 0 && height > 0) { "Record-develop publication geometry must be positive" }
        require(frameCount > 0L) { "Record-develop publication frame count must be positive" }
        require(expectedDurationUs > 0L) {
            "Record-develop source-derived duration must be positive"
        }
        require(durationToleranceUs >= 0L) { "Duration tolerance cannot be negative" }
        require(expectedAverageFps == null || expectedAverageFps > 0.0 && expectedAverageFps.isFinite()) {
            "Expected average fps must be null or finite and positive"
        }
        require(fpsTolerance >= 0.0 && fpsTolerance.isFinite()) {
            "Record-develop publication fps tolerance must be finite and non-negative"
        }
        require(minimumMeasuredVideoBitrateBps == null || minimumMeasuredVideoBitrateBps > 0L) {
            "Minimum measured video bitrate must be positive"
        }
    }

    companion object {
        const val DEFAULT_FPS_TOLERANCE = 0.02
        const val DEFAULT_DURATION_TOLERANCE_US = 1L
    }
}

/** Facts copied exclusively from the finalized MOV inspection report. */
internal data class RecordDevelopMovMeasuredEvidence(
    val fileBytes: Long,
    val majorBrand: String,
    val sampleEntry: String,
    val width: Int,
    val height: Int,
    val tkhdWidth: Double,
    val tkhdHeight: Double,
    val rotationDegrees: Int?,
    val color: QuickTimeMovNclxEvidence,
    val sampleCount: Long,
    val samplePayloadBytes: Long,
    val mediaTimescale: Long,
    val mediaDurationUnits: Long,
    val durationSeconds: Double,
    val measuredAverageFps: Double,
    val measuredVideoBitrateBps: Long,
    val measuredFileBitrateBps: Long,
    val sttsEntryCount: Long,
    val timingCoversEverySample: Boolean,
    val sampleToChunkEntryCount: Long,
    val mappedChunkCount: Long,
    val mappedSampleCount: Long,
    val chunkOffsetTableType: String,
    val chunkOffsetCount: Long,
    val chunkOffsetsInsideFile: Boolean,
    val chunkOffsetsInsideMdat: Boolean,
    val sampleExtentsInsideFile: Boolean,
    val sampleExtentsInsideMdat: Boolean,
    val hevcConfiguration: QuickTimeMovHevcConfigurationEvidence?
)

internal data class RecordDevelopMovPublication(
    val measured: RecordDevelopMovMeasuredEvidence,
    val certificateFile: File,
    val inspectionReport: QuickTimeMovArtifactReport
)

internal class RecordDevelopMovPublicationException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * Final fail-closed publication boundary for record-develop MOV output.
 *
 * Call this only after [QuickTimeMovFileWriter.close]. The certificate's authoritative section is
 * rendered solely from the read-only artifact inspector. Caller-provided audit text is namespaced as
 * non-authoritative context and therefore cannot substitute self-reported frame, color, timing, geometry,
 * offset, or bitrate values for measured values. Any inspection, expectation, sync, or atomic publication
 * failure removes every gate-owned artifact. A borrowed seekable descriptor is never unlinked; its caller
 * must roll back the owning transaction (for example, delete a pending MediaStore row).
 */
internal object RecordDevelopMovPublicationGate {
    fun publishFinalized(
        output: File,
        certificateFile: File,
        expectation: RecordDevelopMovPublicationExpectation,
        declaredPipelineAuditText: String = "",
        additionalStaleCertificates: List<File> = emptyList(),
        outputOwnership: MovOutputOwnership = MovOutputOwnership.OWNED_REGULAR_FILE
    ): RecordDevelopMovPublication {
        val normalizedOutputPath = output.absoluteFile.toPath().normalize()
        require(normalizedOutputPath != certificateFile.absoluteFile.toPath().normalize()) {
            "MOV output and publication certificate must be different files"
        }
        val cleanupCertificates = (listOf(certificateFile) + additionalStaleCertificates)
            .distinctBy { it.absoluteFile.toPath().normalize().toString() }
        require(cleanupCertificates.none { it.absoluteFile.toPath().normalize() == normalizedOutputPath }) {
            "MOV output cannot be registered as a stale certificate"
        }

        var temporaryCertificate: File? = null
        try {
            cleanupCertificates.forEach(::deleteIfPresentStrict)
            val report = QuickTimeMovArtifactInspector.inspect(
                output,
                QuickTimeMovArtifactRequirements(
                    expectedVideoSampleEntry = expectation.sampleEntry,
                    expectedWidth = expectation.width,
                    expectedHeight = expectation.height,
                    expectedRotationDegrees = expectation.rotationDegrees,
                    expectedColor = expectation.color,
                    minimumSampleCount = 1L,
                    requireNclxColor = true,
                    requireChunkOffsetsInsideMdat = true
                )
            )
            if (!report.passed) {
                throw RecordDevelopMovPublicationException(
                    report.failures.joinToString(
                        prefix = "Finalized MOV inspection rejected publication: ",
                        separator = "; "
                    )
                )
            }
            val measured = measuredEvidence(report)
            verifyExpectation(measured, expectation)

            val parent = certificateFile.absoluteFile.parentFile
                ?: throw RecordDevelopMovPublicationException("Certificate has no parent directory")
            check(parent.isDirectory || parent.mkdirs()) {
                "Unable to create publication certificate directory ${parent.absolutePath}"
            }
            temporaryCertificate = File.createTempFile(".${certificateFile.name}.", ".tmp", parent)
            writeAndSync(
                temporaryCertificate,
                renderCertificate(measured, declaredPipelineAuditText)
            )
            Files.move(
                temporaryCertificate.toPath(),
                certificateFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            temporaryCertificate = null
            check(certificateFile.isFile && certificateFile.length() > 0L) {
                "Publication certificate was not committed"
            }
            return RecordDevelopMovPublication(measured, certificateFile, report)
        } catch (failure: Throwable) {
            val cleanupFailures = ArrayList<Throwable>()
            val ownedOutput = if (outputOwnership.converterMayDeleteOutput) listOf(output) else emptyList()
            (listOfNotNull(temporaryCertificate) + cleanupCertificates + ownedOutput)
                .distinctBy { it.absoluteFile.toPath().normalize().toString() }
                .forEach { target ->
                    runCatching { deleteIfPresentStrict(target) }
                        .exceptionOrNull()
                        ?.let(cleanupFailures::add)
                }
            val rejection = if (failure is RecordDevelopMovPublicationException) {
                failure
            } else {
                RecordDevelopMovPublicationException(
                    "Finalized MOV publication failed: ${failure.message ?: failure.javaClass.simpleName}",
                    failure
                )
            }
            cleanupFailures.forEach(rejection::addSuppressed)
            throw rejection
        }
    }

    private fun measuredEvidence(report: QuickTimeMovArtifactReport): RecordDevelopMovMeasuredEvidence {
        val video = report.videoTrack
            ?: throw RecordDevelopMovPublicationException("Passing inspection did not provide video evidence")
        val color = video.nclxColor
            ?: throw RecordDevelopMovPublicationException("Passing inspection did not provide nclx evidence")
        val videoBitrate = video.measuredVideoBitrateBps
            ?: throw RecordDevelopMovPublicationException("Finalized MOV has no measurable video bitrate")
        val fileBitrate = report.measuredFileBitrateBps
            ?: throw RecordDevelopMovPublicationException("Finalized MOV has no measurable file bitrate")
        if (video.durationSeconds <= 0.0 || !video.durationSeconds.isFinite()) {
            throw RecordDevelopMovPublicationException("Finalized MOV has no measurable duration")
        }
        return RecordDevelopMovMeasuredEvidence(
            fileBytes = report.fileBytes,
            majorBrand = report.majorBrand
                ?: throw RecordDevelopMovPublicationException("Passing inspection did not provide ftyp evidence"),
            sampleEntry = video.sampleEntry,
            width = video.sampleEntryWidth,
            height = video.sampleEntryHeight,
            tkhdWidth = video.tkhdWidth,
            tkhdHeight = video.tkhdHeight,
            rotationDegrees = video.rotationDegrees,
            color = color,
            sampleCount = video.sampleCount,
            samplePayloadBytes = video.samplePayloadBytes,
            mediaTimescale = video.mediaTimescale,
            mediaDurationUnits = video.mediaDurationUnits,
            durationSeconds = video.durationSeconds,
            measuredAverageFps = video.sampleCount.toDouble() / video.durationSeconds,
            measuredVideoBitrateBps = videoBitrate,
            measuredFileBitrateBps = fileBitrate,
            sttsEntryCount = video.sttsEntryCount,
            timingCoversEverySample = video.timingCoversEverySample,
            sampleToChunkEntryCount = video.sampleToChunk.entryCount,
            mappedChunkCount = video.sampleToChunk.mappedChunkCount,
            mappedSampleCount = video.sampleToChunk.mappedSampleCount,
            chunkOffsetTableType = video.chunkOffsets.tableType,
            chunkOffsetCount = video.chunkOffsets.entryCount,
            chunkOffsetsInsideFile = video.chunkOffsets.allOffsetsInsideFile,
            chunkOffsetsInsideMdat = video.chunkOffsets.allOffsetsInsideMdat,
            sampleExtentsInsideFile = video.chunkOffsets.allSampleExtentsInsideFile,
            sampleExtentsInsideMdat = video.chunkOffsets.allSampleExtentsInsideMdat,
            hevcConfiguration = video.hevcConfiguration
        )
    }

    private fun verifyExpectation(
        measured: RecordDevelopMovMeasuredEvidence,
        expected: RecordDevelopMovPublicationExpectation
    ) {
        val failures = buildList {
            if (measured.sampleCount != expected.frameCount) {
                add("Expected ${expected.frameCount} frames, MOV contains ${measured.sampleCount}")
            }
            if (!durationMatchesSource(measured, expected)) {
                add(
                    "Expected source-derived duration ${expected.expectedDurationUs}us " +
                        "+/-${expected.durationToleranceUs}us, measured " +
                        "${measured.mediaDurationUnits}/${measured.mediaTimescale}s"
                )
            }
            expected.expectedAverageFps?.let { averageFps ->
                if (abs(measured.measuredAverageFps - averageFps) > expected.fpsTolerance) {
                    add(
                        "Expected ${averageFps}fps +/-${expected.fpsTolerance}, measured " +
                            measured.measuredAverageFps
                    )
                }
            }
            expected.minimumMeasuredVideoBitrateBps?.let { minimum ->
                if (measured.measuredVideoBitrateBps < minimum) {
                    add(
                        "Measured video bitrate ${measured.measuredVideoBitrateBps} is below required $minimum"
                    )
                }
            }
        }
        if (failures.isNotEmpty()) {
            throw RecordDevelopMovPublicationException(
                failures.joinToString(prefix = "Finalized MOV evidence mismatch: ", separator = "; ")
            )
        }
    }

    private fun durationMatchesSource(
        measured: RecordDevelopMovMeasuredEvidence,
        expected: RecordDevelopMovPublicationExpectation
    ): Boolean {
        val timescale = BigInteger.valueOf(measured.mediaTimescale)
        val measuredMicrosecondProducts = BigInteger.valueOf(measured.mediaDurationUnits)
            .multiply(MICROSECONDS_PER_SECOND)
        val expectedProducts = BigInteger.valueOf(expected.expectedDurationUs).multiply(timescale)
        val toleranceProducts = BigInteger.valueOf(expected.durationToleranceUs).multiply(timescale)
        return measuredMicrosecondProducts.subtract(expectedProducts).abs() <= toleranceProducts
    }

    private fun renderCertificate(
        measured: RecordDevelopMovMeasuredEvidence,
        declaredPipelineAuditText: String
    ): String = buildString {
        appendLine("NeoProCamX Finalized MOV Publication Certificate")
        appendLine("inspection.status=PASS")
        appendLine("evidence.authority=FINALIZED_MOV_READ_ONLY_INSPECTION")
        appendLine("measured.fileBytes=${measured.fileBytes}")
        appendLine("measured.majorBrand=${measured.majorBrand}")
        appendLine("measured.sampleEntry=${measured.sampleEntry}")
        appendLine("measured.geometry=${measured.width}x${measured.height}")
        appendLine("measured.tkhdGeometry=${measured.tkhdWidth}x${measured.tkhdHeight}")
        appendLine("measured.rotationDegrees=${measured.rotationDegrees}")
        appendLine("measured.nclx.primaries=${measured.color.colorPrimaries}")
        appendLine("measured.nclx.transfer=${measured.color.transferCharacteristics}")
        appendLine("measured.nclx.matrix=${measured.color.matrixCoefficients}")
        appendLine("measured.nclx.fullRange=${measured.color.fullRange}")
        appendLine("measured.sampleCount=${measured.sampleCount}")
        appendLine("measured.samplePayloadBytes=${measured.samplePayloadBytes}")
        appendLine("measured.mediaTimescale=${measured.mediaTimescale}")
        appendLine("measured.mediaDurationUnits=${measured.mediaDurationUnits}")
        appendLine("measured.durationSeconds=${measured.durationSeconds}")
        appendLine("measured.averageFps=${measured.measuredAverageFps}")
        appendLine("measured.videoBitrateBps=${measured.measuredVideoBitrateBps}")
        appendLine("measured.fileBitrateBps=${measured.measuredFileBitrateBps}")
        appendLine("measured.sttsEntryCount=${measured.sttsEntryCount}")
        appendLine("measured.timingCoversEverySample=${measured.timingCoversEverySample}")
        appendLine("measured.stscEntryCount=${measured.sampleToChunkEntryCount}")
        appendLine("measured.mappedChunkCount=${measured.mappedChunkCount}")
        appendLine("measured.mappedSampleCount=${measured.mappedSampleCount}")
        appendLine("measured.chunkOffsetTable=${measured.chunkOffsetTableType}")
        appendLine("measured.chunkOffsetCount=${measured.chunkOffsetCount}")
        appendLine("measured.chunkOffsetsInsideFile=${measured.chunkOffsetsInsideFile}")
        appendLine("measured.chunkOffsetsInsideMdat=${measured.chunkOffsetsInsideMdat}")
        appendLine("measured.sampleExtentsInsideFile=${measured.sampleExtentsInsideFile}")
        appendLine("measured.sampleExtentsInsideMdat=${measured.sampleExtentsInsideMdat}")
        measured.hevcConfiguration?.let { hevc ->
            appendLine("measured.hvcC.profileSpace=${hevc.profileSpace}")
            appendLine("measured.hvcC.profileIdc=${hevc.profileIdc}")
            appendLine("measured.hvcC.bitDepthLuma=${hevc.bitDepthLuma}")
            appendLine("measured.hvcC.bitDepthChroma=${hevc.bitDepthChroma}")
            appendLine("measured.hvcC.nalLengthFieldBytes=${hevc.nalLengthFieldBytes}")
            appendLine("measured.hvcC.hasVps=${hevc.hasVps}")
            appendLine("measured.hvcC.hasSps=${hevc.hasSps}")
            appendLine("measured.hvcC.hasPps=${hevc.hasPps}")
        }
        if (declaredPipelineAuditText.isNotBlank()) {
            appendLine("[DECLARED_PIPELINE_AUDIT_NON_AUTHORITATIVE]")
            declaredPipelineAuditText.lineSequence().forEach { line -> appendLine("declared.$line") }
        }
    }

    private fun writeAndSync(file: File, text: String) {
        FileOutputStream(file, false).use { output ->
            output.write(text.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun deleteIfPresentStrict(file: File) {
        Files.deleteIfExists(file.toPath())
        check(!file.exists()) { "Failed to delete stale publication artifact ${file.absolutePath}" }
    }

    private val MICROSECONDS_PER_SECOND: BigInteger = BigInteger.valueOf(1_000_000L)
}
