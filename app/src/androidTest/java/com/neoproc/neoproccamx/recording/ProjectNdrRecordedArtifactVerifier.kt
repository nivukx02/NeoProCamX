package com.neoproc.neoproccamx.recording

import android.content.Context
import android.content.ContentUris
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * Device-side proof for the finished project-owned NDR family recording, independent of NPV10.
 *
 * Three durable authorities are cross-checked:
 *  1. the authoritative MOV/NIVUKX carrier actually contains HEVC Main10 + BT.2020 + ST.2084
 *     samples at 25 fps;
 *  2. the stop-time certificate proves RAW/cinema-debayer -> project GPU -> encoder ownership; and
 *  3. the published professional sidecar names ACEScg/AP1 and the same PQ carrier.
 *
 * The certificate assertions deliberately reject Camera2 P010/NPV10 ownership. They do not infer
 * pipeline lineage from a filename or from the requested UI mode.
 */
internal object ProjectNdrRecordedArtifactVerifier {
    internal enum class PublishedContainer {
        QUICKTIME_MOV,
        NIVUKX
    }

    private val supportedProfiles = setOf(
        "NDR10+",
        "NIVUKX Vision",
        "NIVUKX APEX SHARP",
        "NIVUKX APEX ZERO",
        "NIVUKX APEX CINEMA"
    )

    internal data class Certificate(
        val file: File,
        val metadata: Map<String, String>
    ) {
        val profile: String get() = metadata.getValue("selectedProfile")
        val recordedFileName: String get() = metadata.getValue("recordedFileName")
        val generatedAtEpochMs: Long
            get() = metadata["generatedAtEpochMs"]?.toLongOrNull() ?: file.lastModified()
    }

    internal data class PublishedClip(
        val uri: Uri,
        val displayName: String,
        val description: String,
        val relativePath: String,
        val mimeType: String,
        val sizeBytes: Long,
        val pending: Int,
        val dateAddedEpochSeconds: Long,
        val container: PublishedContainer
    )

    private data class PublishedSidecar(
        val uri: Uri,
        val displayName: String,
        val relativePath: String,
        val mimeType: String,
        val sizeBytes: Long,
        val pending: Int,
        val dateAddedEpochSeconds: Long
    )

    private data class SidecarDocument(
        val row: PublishedSidecar,
        val json: JSONObject
    )

    internal data class Verification(
        val profile: String,
        val displayName: String,
        val frameCount: Int,
        val measuredFps: Double,
        val maximumFrameIntervalUs: Long
    )

    fun discoverPublishedCertificates(context: Context): List<Pair<Certificate, PublishedClip>> =
        discoverCertificates(context)
            .mapNotNull { certificate ->
                findPublishedClip(context, certificate.recordedFileName)?.let { certificate to it }
            }

    fun awaitCertificate(
        context: Context,
        recordedFileName: String,
        expectedProfile: String,
        timeoutMs: Long = 10_000L
    ): Certificate {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var certificate: Certificate? = null
        do {
            certificate = discoverCertificates(context).firstOrNull { candidate ->
                candidate.recordedFileName == recordedFileName &&
                    candidate.profile == expectedProfile
            }
            if (certificate == null) SystemClock.sleep(POLL_INTERVAL_MS)
        } while (certificate == null && SystemClock.elapsedRealtime() < deadline)
        return requireNotNull(certificate) {
            "No stop-time $expectedProfile quality certificate was written for $recordedFileName"
        }
    }

    fun publishedClipForOutputPath(
        context: Context,
        outputPath: String,
        timeoutMs: Long = MEDIASTORE_PUBLICATION_TIMEOUT_MS
    ): PublishedClip {
        val outputUri = outputPath
            .takeIf { it.startsWith("content://") }
            ?.let(Uri::parse)
            ?: throw AssertionError(
                "Project NDR recording bypassed finalized MediaStore publication: $outputPath"
            )
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        var lastObserved: PublishedClip? = null
        do {
            queryPublishedClip(context, outputUri)?.let { clip ->
                lastObserved = clip
                if (clip.pending == 0 && clip.sizeBytes > 0L) return clip
            }
            if (SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)

        return lastObserved
            ?: throw AssertionError("Published project NDR MediaStore row is missing: $outputUri")
    }

    fun verify(
        context: Context,
        certificate: Certificate,
        clip: PublishedClip,
        expectedProfile: String = certificate.profile,
        expectedValidationLabel: String? = null,
        expectedWidth: Int? = null,
        expectedHeight: Int? = null,
        minimumFrameCount: Int = MINIMUM_FRAMES
    ): Verification {
        require(minimumFrameCount >= MINIMUM_FRAMES) {
            "minimumFrameCount must be at least $MINIMUM_FRAMES"
        }
        require((expectedWidth == null) == (expectedHeight == null)) {
            "Expected width and height must be supplied together"
        }
        assertTrue("Unsupported project NDR profile: $expectedProfile", expectedProfile in supportedProfiles)
        assertEquals(expectedProfile, certificate.profile)
        assertEquals(certificate.recordedFileName, clip.displayName)
        expectedValidationLabel?.let { label ->
            assertTrue(
                "Validation label $label is missing from ${clip.displayName}",
                clip.displayName.contains(label)
            )
        }
        assertTrue("Project NDR output is empty", clip.sizeBytes > 0L)
        assertEquals("Project NDR output remained pending", 0, clip.pending)
        assertFalse("Project NDR output was misrouted as NPV10", clip.displayName.startsWith("NPV10_", true))
        val expectedContainer = containerForDisplayName(clip.displayName)
        assertEquals("Published container identity disagrees with its extension", expectedContainer, clip.container)
        when (clip.container) {
            PublishedContainer.QUICKTIME_MOV -> {
                assertTrue(
                    "Project NDR MOV escaped DCIM/CamX: ${clip.relativePath}",
                    clip.relativePath.trimEnd('/') == MOV_RELATIVE_PATH
                )
                assertTrue(
                    "Unexpected project NDR MOV MIME: ${clip.mimeType}",
                    clip.mimeType == QUICKTIME_MIME_TYPE || clip.mimeType == MP4_MIME_TYPE
                )
            }
            PublishedContainer.NIVUKX -> {
                assertTrue(
                    "Project NIVUKX output escaped Documents/CamX: ${clip.relativePath}",
                    clip.relativePath.trimEnd('/') == NIVUKX_RELATIVE_PATH
                )
                assertEquals(
                    "Unexpected project NIVUKX storage MIME",
                    NivukxContainerFormat.MEDIASTORE_MIME_TYPE,
                    clip.mimeType
                )
                assertTrue(
                    "Project NIVUKX output is smaller than its mandatory finalized header",
                    clip.sizeBytes >= NivukxContainerFormat.HEADER_SIZE
                )
            }
        }

        expectedWidth?.let { width ->
            val expectedResolution = "${width}x${requireNotNull(expectedHeight)}"
            assertEquals(
                "Project NDR certificate selected a different resolution",
                expectedResolution,
                certificate.metadata["selectedResolution"]
            )
            assertEquals(
                "Project NDR certificate recorded a different resolution",
                expectedResolution,
                certificate.metadata["actualResolution"]
            )
        }
        verifyCertificateLineage(
            metadata = certificate.metadata,
            expectedProfile = expectedProfile,
            minimumFrameCount = minimumFrameCount
        )
        verifyProfessionalSidecar(context, certificate, clip, expectedProfile)
        return when (clip.container) {
            PublishedContainer.QUICKTIME_MOV ->
                inspectMovTrack(
                    context = context,
                    uri = clip.uri,
                    expectedProfile = expectedProfile,
                    displayName = clip.displayName,
                    expectedWidth = expectedWidth,
                    expectedHeight = expectedHeight,
                    minimumFrameCount = minimumFrameCount
                )
            PublishedContainer.NIVUKX ->
                inspectNivukxTrack(
                    context = context,
                    clip = clip,
                    expectedProfile = expectedProfile,
                    expectedWidth = expectedWidth,
                    expectedHeight = expectedHeight,
                    minimumFrameCount = minimumFrameCount
                )
        }
    }

    private fun discoverCertificates(context: Context): List<Certificate> =
        context.filesDir
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".quality-certificate.txt") }
            .mapNotNull { file ->
                val metadata = runCatching { parseKeyValueFile(file) }.getOrNull() ?: return@mapNotNull null
                val profile = metadata["selectedProfile"].orEmpty()
                val recordedFileName = metadata["recordedFileName"].orEmpty()
                if (metadata["selectedMode"] != "HDR" ||
                    profile !in supportedProfiles ||
                    metadata["activeTransport"] != Bt2100TransportPolicy.TRANSPORT_PQ_MAIN10 ||
                    !isSupportedRecordedFileName(recordedFileName)
                ) {
                    return@mapNotNull null
                }
                Certificate(file = file, metadata = metadata)
            }
            .sortedByDescending(Certificate::generatedAtEpochMs)
            .toList()

    private fun parseKeyValueFile(file: File): Map<String, String> =
        file.useLines { lines ->
            lines
                .filter { line -> line.isNotBlank() && !line.startsWith("failure[") }
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null
                    else line.substring(0, separator) to line.substring(separator + 1)
                }
                .toMap()
        }

    private fun verifyCertificateLineage(
        metadata: Map<String, String>,
        expectedProfile: String,
        minimumFrameCount: Int
    ) {
        assertEquals(
            "Project NDR quality evidence did not pass",
            "PASS",
            metadata["qualityEvidenceStatus"]
        )
        assertTrue(
            "Project NDR quality evidence contains blocking failures: ${metadata["failureCodes"]}",
            metadata["failureCodes"].isNullOrBlank()
        )
        listOf(
            "strictVulkanParityCertified",
            "camera2FrameLineageCertified",
            "encoderTransportLevelProven",
            "encoderTransportAvSyncWithinBound"
        ).forEach { proof ->
            assertEquals("Project NDR delivery proof is not certified: $proof", "true", metadata[proof])
        }
        assertEquals(
            "Project NDR strict frame-lineage certificate failed",
            "true",
            metadata["camera2FrameLineageStrictCertificatePassed"]
                ?: metadata["camera2FrameLineage.strictCertificatePassed"]
        )
        assertTrue(
            "Project NDR certificate contains too few encoded frames",
            metadata["actualFrameCount"]?.toLongOrNull()?.let { it >= minimumFrameCount } == true
        )
        assertTrue(
            "Project NDR encoder received fewer than $minimumFrameCount project-owned frames",
            metadata["offscreenEglSubmittedFrames"]?.toLongOrNull()
                ?.let { it >= minimumFrameCount } == true
        )
        assertTrue(
            "Project NDR lineage window contains fewer than $minimumFrameCount Camera2 frames",
            metadata["camera2FrameLineageFrameCount"]?.toLongOrNull()
                ?.let { it >= minimumFrameCount } == true
        )
        assertEquals("HDR", metadata["selectedMode"])
        assertEquals(Bt2100TransportPolicy.TRANSPORT_PQ_MAIN10, metadata["activeTransport"])
        assertEquals(Bt2100TransportPolicy.TRANSFER_PQ, metadata["transfer"])
        assertEquals("BT.2020", metadata["primaries"])
        assertEquals(Bt2100TransportPolicy.MATRIX_NCL, metadata["matrix"])
        assertEquals("10", metadata["encodedBitDepth"])
        assertTrue(
            "Project NDR internal math fell below 12-bit evidence",
            metadata["internalMathBitDepth"]?.toIntOrNull()?.let { it >= 12 } == true
        )
        assertTrue(
            "Project NDR source/storage evidence fell below 10-bit",
            metadata["sourceBitDepth"]?.toIntOrNull()?.let { it >= 10 } == true &&
                metadata["storageBitDepth"]?.toIntOrNull()?.let { it >= 10 } == true
        )

        assertEquals("false", metadata["npv10MasterAvailable"])
        assertEquals("false", metadata["npv10NativeTenBitClaimed"])
        assertTrue("Project NDR certificate contains an NPV10 payload", metadata["npv10PayloadFormat"].isNullOrBlank())
        assertEquals("OFFSCREEN_AHB_EGL_ENCODER", metadata["parityFeedPath"])
        assertEquals("VULKAN_OFFSCREEN_AHB_EGL_ENCODER", metadata["gpuProcessingPath"])
        assertEquals("true", metadata["recordingUsesCustomGpu"])
        assertEquals("true", metadata["gpuProcessingValidated"])
        assertEquals("true", metadata["processedRecordingFramesValidated"])
        assertEquals("true", metadata["vulkanEncoderSourcePrecisionProven"])
        assertTrue(
            "Project NDR encoder ingress is not a project-owned ACEScg buffer: " +
                metadata["vulkanEncoderSourceFormat"],
            metadata["vulkanEncoderSourceFormat"] in setOf(
                "AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT",
                "AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM"
            )
        )
        assertTrue(
            "Project NDR encoder ingress precision fell below 10-bit",
            metadata["vulkanEncoderSourceBitDepth"]?.toIntOrNull()?.let { it >= 10 } == true
        )
        assertEquals("true", metadata["stage3CinemaDebayerPipelineActive"])
        assertTrue(
            "No RAW_SENSOR cinema-debayer frame reached the project NDR encoder",
            metadata["stage3CinemaDebayeredFrames"]?.toLongOrNull()?.let { it > 0L } == true
        )
        assertTrue(
            "No project-owned NDR frame was submitted to the encoder",
            metadata["offscreenEglSubmittedFrames"]?.toLongOrNull()?.let { it > 0L } == true
        )
        assertTrue(
            "Project NDR unexpectedly consumed the vendor Camera2 P010 lane",
            metadata["p010SourceImageCallbacks"]?.toLongOrNull() == 0L
        )
        assertEquals("0", metadata["vulkanSessionFailedImports"])
        assertEquals("0", metadata["offscreenEglFailedFrames"])
        assertEquals("0", metadata["realtimeDispatchDroppedFrames"])
        assertEquals("0", metadata["realtimeDispatchRejectedFrames"])
        assertEquals("0", metadata["droppedFrames"])
        assertEquals("true", metadata["mediaStoreValidated"])
        assertEquals(expectedProfile, metadata["selectedColorScience"])
        assertTrue(
            "Project NDR certificate lost BT.2020 selection: ${metadata["selectedColorSpace"]}",
            metadata["selectedColorSpace"]?.contains("2020", ignoreCase = true) == true
        )
    }

    private fun verifyProfessionalSidecar(
        context: Context,
        certificate: Certificate,
        clip: PublishedClip,
        expectedProfile: String
    ) {
        val document = when (clip.container) {
            PublishedContainer.QUICKTIME_MOV -> {
                val publishedFields = parseDescriptionFields(clip.description)
                assertEquals(expectedProfile, publishedFields["selectedColorScience"])
                assertTrue(
                    "Published NDR MOV description lost its project color space",
                    publishedFields["selectedColorSpace"]?.contains("2020", true) == true
                )
                val sidecarUri = publishedFields["sidecar"]?.let(Uri::parse)
                assertNotNull("Published project NDR MOV has no professional ACES sidecar", sidecarUri)
                readSidecarDocument(context, requireNotNull(sidecarUri))
            }
            PublishedContainer.NIVUKX -> findNivukxSidecarDocument(
                context = context,
                certificate = certificate,
                clip = clip,
                expectedProfile = expectedProfile
            )
        }

        assertEquals("Project sidecar remained pending", 0, document.row.pending)
        assertTrue("Project sidecar is empty", document.row.sizeBytes > 0L)
        assertEquals(SIDECAR_MIME_TYPE, document.row.mimeType)
        assertTrue(
            "Project sidecar escaped Documents/CamX/Metadata: ${document.row.relativePath}",
            document.row.relativePath.trimEnd('/') == SIDECAR_RELATIVE_PATH
        )

        val sidecar = document.json
        assertEquals("NeoProCamX.MediaSidecar.v1", sidecar.getString("schema"))
        assertEquals("BT.2100 PQ", sidecar.getString("workingSpace"))
        assertEquals("BT2020", sidecar.getString("colorStandard"))
        assertEquals("ST2084", sidecar.getString("colorTransfer"))
        val aces = sidecar.getJSONObject("aces")
        assertEquals("ACEScg", aces.getString("gamutWorkingSpace"))
        assertTrue("Project sidecar lost ACES AP1", aces.getBoolean("ap1"))
        assertTrue(
            "Project sidecar identifies a different recorded file: ${sidecar.optString("file")}",
            sidecarFileReferenceMatchesClip(
                recordedFileName = clip.displayName,
                sidecarFileName = sidecar.getString("file")
            )
        )

        val embeddedDescription = parseDescriptionFields(sidecar.getString("description"))
        assertEquals(expectedProfile, embeddedDescription["selectedColorScience"])
        assertTrue(
            "Project sidecar description lost its project color space",
            embeddedDescription["selectedColorSpace"]?.contains("2020", true) == true
        )
    }

    private fun inspectMovTrack(
        context: Context,
        uri: Uri,
        expectedProfile: String,
        displayName: String,
        expectedWidth: Int?,
        expectedHeight: Int?,
        minimumFrameCount: Int
    ): Verification = inspectMovTrack(
        expectedProfile = expectedProfile,
        displayName = displayName,
        expectedWidth = expectedWidth,
        expectedHeight = expectedHeight,
        minimumFrameCount = minimumFrameCount
    ) { extractor ->
        extractor.setDataSource(context, uri, null)
    }

    private fun inspectMovTrack(
        file: File,
        expectedProfile: String,
        displayName: String,
        expectedWidth: Int?,
        expectedHeight: Int?,
        minimumFrameCount: Int
    ): Verification = inspectMovTrack(
        expectedProfile = expectedProfile,
        displayName = displayName,
        expectedWidth = expectedWidth,
        expectedHeight = expectedHeight,
        minimumFrameCount = minimumFrameCount
    ) { extractor ->
        extractor.setDataSource(file.absolutePath)
    }

    private fun inspectMovTrack(
        expectedProfile: String,
        displayName: String,
        expectedWidth: Int?,
        expectedHeight: Int?,
        minimumFrameCount: Int,
        setDataSource: (MediaExtractor) -> Unit
    ): Verification {
        val extractor = MediaExtractor()
        try {
            setDataSource(extractor)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: throw AssertionError("$displayName contains no video track")
            val format = extractor.getTrackFormat(videoTrack)
            assertEquals("video/hevc", format.getString(MediaFormat.KEY_MIME))
            assertTrue("$expectedProfile MOV does not expose an HEVC Main10 profile", format.containsKey(MediaFormat.KEY_PROFILE))
            val profile = format.getInteger(MediaFormat.KEY_PROFILE)
            assertTrue(
                "$expectedProfile encoder profile fell below HEVC Main10: $profile",
                profile in hevcMain10Profiles
            )
            assertEquals(
                "$expectedProfile output lost BT.2020 primaries",
                MediaFormat.COLOR_STANDARD_BT2020,
                format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
            )
            assertEquals(
                "$expectedProfile output lost ST.2084/PQ transfer",
                MediaFormat.COLOR_TRANSFER_ST2084,
                format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
            )
            val encodedWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            val encodedHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            assertTrue(encodedWidth > 0)
            assertTrue(encodedHeight > 0)
            expectedWidth?.let { width ->
                assertEquals("$expectedProfile encoded width changed", width, encodedWidth)
                assertEquals(
                    "$expectedProfile encoded height changed",
                    requireNotNull(expectedHeight),
                    encodedHeight
                )
            }

            extractor.selectTrack(videoTrack)
            var frameCount = 0
            var firstPtsUs = -1L
            var previousPtsUs = -1L
            var lastPtsUs = -1L
            var maximumIntervalUs = 0L
            while (extractor.sampleTime >= 0L) {
                val ptsUs = extractor.sampleTime
                if (firstPtsUs < 0L) firstPtsUs = ptsUs
                if (previousPtsUs >= 0L) {
                    assertTrue("$expectedProfile MOV has non-monotonic PTS", ptsUs > previousPtsUs)
                    maximumIntervalUs = maxOf(maximumIntervalUs, ptsUs - previousPtsUs)
                }
                previousPtsUs = ptsUs
                lastPtsUs = ptsUs
                frameCount++
                if (!extractor.advance()) break
            }
            assertTrue(
                "$expectedProfile MOV contains too few frames: $frameCount/$minimumFrameCount",
                frameCount >= minimumFrameCount
            )
            val durationSeconds = (lastPtsUs - firstPtsUs).toDouble() / 1_000_000.0
            assertTrue("$expectedProfile MOV has no measurable timeline", durationSeconds > 0.0)
            val measuredFps = (frameCount - 1).toDouble() / durationSeconds
            assertTrue(
                "$expectedProfile MOV cadence was not stable 25 fps: frames=$frameCount fps=$measuredFps",
                measuredFps in MINIMUM_FPS..MAXIMUM_FPS
            )
            assertTrue(
                "$expectedProfile MOV contains a dropped-frame-sized PTS gap: ${maximumIntervalUs}us",
                maximumIntervalUs <= MAXIMUM_FRAME_INTERVAL_US
            )
            return Verification(
                profile = expectedProfile,
                displayName = displayName,
                frameCount = frameCount,
                measuredFps = measuredFps,
                maximumFrameIntervalUs = maximumIntervalUs
            )
        } finally {
            extractor.release()
        }
    }

    private fun inspectNivukxTrack(
        context: Context,
        clip: PublishedClip,
        expectedProfile: String,
        expectedWidth: Int?,
        expectedHeight: Int?,
        minimumFrameCount: Int
    ): Verification {
        val descriptor = context.contentResolver.openFileDescriptor(clip.uri, "r")
            ?: throw AssertionError("Published NIVUKX file cannot be opened: ${clip.uri}")
        val remuxedMov = File.createTempFile("project-ndr-verifier-", ".mov", context.cacheDir)
        try {
            return descriptor.use { sourceDescriptor ->
                val statSize = sourceDescriptor.statSize
                if (statSize >= 0L) {
                    assertEquals("NIVUKX MediaStore size disagrees with fstat", clip.sizeBytes, statSize)
                }
                val source = File("/proc/self/fd/${sourceDescriptor.fd}")
                assertTrue("NIVUKX descriptor does not resolve to a regular file", source.isFile)
                assertEquals("NIVUKX MediaStore size disagrees with the opened file", clip.sizeBytes, source.length())

                val packetCopy = NivukxPacketCopyMovRemuxer.remux(
                    input = source,
                    output = remuxedMov
                )
                assertTrue("NIVUKX CRC32C/SHA-256 integrity was not proven", packetCopy.integrityVerified)
                assertFalse("NIVUKX verifier unexpectedly transcoded the recording", packetCopy.transcoded)
                assertTrue("NIVUKX verifier did not preserve coded NAL units", packetCopy.codedNalUnitsPreserved)
                assertTrue("NIVUKX contains no HEVC video track", "video/hevc" in packetCopy.codecMimes)
                assertTrue("NIVUKX contains no encoded video samples", packetCopy.videoSampleCount > 0L)
                assertTrue("NIVUKX has no measurable duration", packetCopy.durationUs > 0L)
                assertTrue("NIVUKX packet-copy MOV is empty", remuxedMov.length() > 0L)

                inspectMovTrack(
                    file = remuxedMov,
                    expectedProfile = expectedProfile,
                    displayName = clip.displayName,
                    expectedWidth = expectedWidth,
                    expectedHeight = expectedHeight,
                    minimumFrameCount = minimumFrameCount
                ).also { verification ->
                    assertEquals(
                        "NIVUKX video sample count changed during packet-copy verification",
                        packetCopy.videoSampleCount,
                        verification.frameCount.toLong()
                    )
                }
            }
        } finally {
            remuxedMov.delete()
        }
    }

    private fun parseDescriptionFields(description: String): Map<String, String> =
        description
            .split(';')
            .mapNotNull { token ->
                val separator = token.indexOf('=')
                if (separator <= 0) null
                else token.substring(0, separator).trim() to token.substring(separator + 1).trim()
            }
            .toMap()

    private fun readSidecarDocument(context: Context, uri: Uri): SidecarDocument {
        val row = queryPublishedSidecar(context, uri)
            ?: throw AssertionError("Published project NDR sidecar row is missing: $uri")
        return readSidecarDocument(context, row)
    }

    private fun readSidecarDocument(context: Context, row: PublishedSidecar): SidecarDocument {
        val bytes = context.contentResolver.openInputStream(row.uri)?.use { input -> input.readBytes() }
            ?: throw AssertionError("Project NDR professional sidecar cannot be read: ${row.uri}")
        assertEquals("Project sidecar MediaStore size disagrees with readable bytes", row.sizeBytes, bytes.size.toLong())
        return SidecarDocument(
            row = row,
            json = JSONObject(bytes.toString(Charsets.UTF_8))
        )
    }

    private fun findNivukxSidecarDocument(
        context: Context,
        certificate: Certificate,
        clip: PublishedClip,
        expectedProfile: String
    ): SidecarDocument {
        val candidates = queryPublishedSidecars(
            context = context,
            displayNames = sidecarDisplayNameCandidates(clip.displayName)
        )
        assertTrue(
            "Published NIVUKX output has no professional ACES sidecar for ${clip.displayName}",
            candidates.isNotEmpty()
        )
        val matching = candidates
            .map { row -> readSidecarDocument(context, row) }
            .filter { document ->
                runCatching {
                    val description = parseDescriptionFields(document.json.getString("description"))
                    sidecarFileReferenceMatchesClip(
                        recordedFileName = clip.displayName,
                        sidecarFileName = document.json.getString("file")
                    ) && description["selectedColorScience"] == expectedProfile
                }.getOrDefault(false)
            }
        assertTrue(
            "No NIVUKX sidecar identifies ${clip.displayName} with profile $expectedProfile",
            matching.isNotEmpty()
        )

        val closestDeltaMs = matching.minOf { document ->
            kotlin.math.abs(
                document.row.dateAddedEpochSeconds * 1_000L - certificate.generatedAtEpochMs
            )
        }
        assertTrue(
            "NIVUKX sidecar is not from the certificate publication transaction: delta=${closestDeltaMs}ms",
            closestDeltaMs <= MAX_SIDECAR_CERTIFICATE_SKEW_MS
        )
        val closest = matching.filter { document ->
            kotlin.math.abs(
                document.row.dateAddedEpochSeconds * 1_000L - certificate.generatedAtEpochMs
            ) == closestDeltaMs
        }
        assertEquals("NIVUKX sidecar publication is ambiguous", 1, closest.size)
        return closest.single()
    }

    internal fun sidecarDisplayNameCandidates(recordedFileName: String): List<String> {
        val exact = "$recordedFileName.metadata.json"
        val extensionStart = recordedFileName.lastIndexOf('.')
        if (extensionStart <= 0) return listOf(exact)
        val stem = recordedFileName.substring(0, extensionStart)
        val extension = recordedFileName.substring(extensionStart)
        val collision = MEDIASTORE_COLLISION_SUFFIX.matchEntire(stem) ?: return listOf(exact)
        val collisionVariant =
            "${collision.groupValues[1]}$extension.metadata (${collision.groupValues[2]}).json"
        return listOf(exact, collisionVariant).distinct()
    }

    internal fun sidecarFileReferenceMatchesClip(
        recordedFileName: String,
        sidecarFileName: String
    ): Boolean {
        if (recordedFileName == sidecarFileName) return true
        val extensionStart = recordedFileName.lastIndexOf('.')
        if (extensionStart <= 0) return false
        val stem = recordedFileName.substring(0, extensionStart)
        val collision = MEDIASTORE_COLLISION_SUFFIX.matchEntire(stem) ?: return false
        val collisionOrigin = collision.groupValues[1] + recordedFileName.substring(extensionStart)
        return collisionOrigin == sidecarFileName
    }

    internal fun isSupportedRecordedFileName(fileName: String): Boolean =
        fileName.endsWith(".mov", ignoreCase = true) ||
            fileName.endsWith(".${NivukxContainerFormat.FILE_EXTENSION}", ignoreCase = true)

    internal fun containerForDisplayName(fileName: String): PublishedContainer = when {
        fileName.endsWith(".mov", ignoreCase = true) -> PublishedContainer.QUICKTIME_MOV
        fileName.endsWith(".${NivukxContainerFormat.FILE_EXTENSION}", ignoreCase = true) ->
            PublishedContainer.NIVUKX
        else -> throw AssertionError("Unsupported project NDR recording container: $fileName")
    }

    private fun findPublishedClip(context: Context, fileName: String): PublishedClip? {
        if (!isSupportedRecordedFileName(fileName)) return null
        val container = containerForDisplayName(fileName)
        val collection = when (container) {
            PublishedContainer.QUICKTIME_MOV -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            PublishedContainer.NIVUKX ->
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val id = context.contentResolver.query(
            collection,
            projection,
            selection,
            arrayOf(fileName),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
        } ?: return null
        return queryPublishedClip(
            context,
            ContentUris.withAppendedId(collection, id)
        )
    }

    private fun queryPublishedClip(context: Context, uri: Uri): PublishedClip? {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.DATE_ADDED
        )
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val displayName = cursor.getString(
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            ).orEmpty()
            val container = containerForDisplayName(displayName)
            PublishedClip(
                uri = uri,
                displayName = displayName,
                description = if (container == PublishedContainer.QUICKTIME_MOV) {
                    queryMovDescription(context, uri)
                } else {
                    ""
                },
                relativePath = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                ).orEmpty(),
                mimeType = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                ).orEmpty(),
                sizeBytes = cursor.getLong(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                ),
                pending = cursor.getInt(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                ),
                dateAddedEpochSeconds = cursor.getLong(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                ),
                container = container
            )
        }
    }

    private fun queryMovDescription(context: Context, uri: Uri): String =
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Video.Media.DESCRIPTION),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) ""
            else cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DESCRIPTION)).orEmpty()
        }.orEmpty()

    private fun queryPublishedSidecar(context: Context, uri: Uri): PublishedSidecar? {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.DATE_ADDED
        )
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else PublishedSidecar(
                uri = uri,
                displayName = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                ).orEmpty(),
                relativePath = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                ).orEmpty(),
                mimeType = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                ).orEmpty(),
                sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
                pending = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)),
                dateAddedEpochSeconds = cursor.getLong(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                )
            )
        }
    }

    private fun queryPublishedSidecars(
        context: Context,
        displayNames: List<String>
    ): List<PublishedSidecar> {
        if (displayNames.isEmpty()) return emptyList()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val names = displayNames.joinToString(separator = " OR ") {
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        }
        val selection =
            "($names) AND ${MediaStore.MediaColumns.MIME_TYPE} = ? AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 0 AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)"
        val arguments = (displayNames + listOf(
            SIDECAR_MIME_TYPE,
            SIDECAR_RELATIVE_PATH,
            "$SIDECAR_RELATIVE_PATH/"
        )).toTypedArray()
        return context.contentResolver.query(
            collection,
            projection,
            selection,
            arguments,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(
                        collection,
                        cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    )
                    add(
                        PublishedSidecar(
                            uri = uri,
                            displayName = cursor.getString(
                                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                            ).orEmpty(),
                            relativePath = cursor.getString(
                                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                            ).orEmpty(),
                            mimeType = cursor.getString(
                                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                            ).orEmpty(),
                            sizeBytes = cursor.getLong(
                                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                            ),
                            pending = cursor.getInt(
                                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                            ),
                            dateAddedEpochSeconds = cursor.getLong(
                                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                            )
                        )
                    )
                }
            }
        }.orEmpty()
    }

    private val hevcMain10Profiles = setOf(
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
    )

    private const val POLL_INTERVAL_MS = 100L
    private const val MEDIASTORE_PUBLICATION_TIMEOUT_MS = 10_000L
    private const val MINIMUM_FRAMES = 40
    private const val MINIMUM_FPS = 24.0
    private const val MAXIMUM_FPS = 26.0
    private const val MAXIMUM_FRAME_INTERVAL_US = 60_000L
    private const val MAX_SIDECAR_CERTIFICATE_SKEW_MS = 120_000L
    private const val MOV_RELATIVE_PATH = "DCIM/CamX"
    private const val NIVUKX_RELATIVE_PATH = "Documents/CamX"
    private const val SIDECAR_RELATIVE_PATH = "Documents/CamX/Metadata"
    private const val QUICKTIME_MIME_TYPE = "video/quicktime"
    private const val MP4_MIME_TYPE = "video/mp4"
    private const val SIDECAR_MIME_TYPE = "application/json"
    private val MEDIASTORE_COLLISION_SUFFIX = Regex("^(.*) \\((\\d+)\\)$")
}
