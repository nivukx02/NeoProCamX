package com.neoproc.neoproccamx.recording

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.roundToLong

/** Result of a read-only, on-disk inspection of a finalized QuickTime/ISO-BMFF artifact. */
internal enum class QuickTimeMovArtifactStatus {
    PASS,
    FAIL
}

/** Optional output requirements applied in addition to the structural MOV checks. */
internal data class QuickTimeMovArtifactRequirements(
    val allowedVideoSampleEntries: Set<String> = SUPPORTED_VIDEO_SAMPLE_ENTRIES,
    val expectedVideoSampleEntry: String? = null,
    val expectedWidth: Int? = null,
    val expectedHeight: Int? = null,
    val expectedRotationDegrees: Int? = null,
    val expectedColor: QuickTimeMovNclxExpectation? = null,
    val minimumSampleCount: Long = 1L,
    val requireNclxColor: Boolean = true,
    val requireChunkOffsetsInsideMdat: Boolean = true
) {
    init {
        require(allowedVideoSampleEntries.isNotEmpty()) { "At least one video sample entry is required" }
        require(allowedVideoSampleEntries.all { it.length == 4 }) {
            "Every allowed video sample entry must be a fourCC"
        }
        require(expectedVideoSampleEntry == null || expectedVideoSampleEntry.length == 4) {
            "Expected video sample entry must be a fourCC"
        }
        require(expectedWidth == null || expectedWidth > 0) { "Expected width must be positive" }
        require(expectedHeight == null || expectedHeight > 0) { "Expected height must be positive" }
        require(expectedRotationDegrees == null || normalizeRotation(expectedRotationDegrees) % 90 == 0) {
            "Expected rotation must be a right angle"
        }
        require(minimumSampleCount >= 0L) { "Minimum sample count cannot be negative" }
    }

    companion object {
        val SUPPORTED_VIDEO_SAMPLE_ENTRIES: Set<String> = setOf("r210", "v210", "hvc1", "hev1")

        internal fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360
    }
}

internal data class QuickTimeMovNclxExpectation(
    val colorPrimaries: Int,
    val transferCharacteristics: Int,
    val matrixCoefficients: Int,
    val fullRange: Boolean
)

internal data class QuickTimeMovTopLevelBoxEvidence(
    val type: String,
    val offset: Long,
    val size: Long,
    val headerSize: Int
)

internal data class QuickTimeMovNclxEvidence(
    val colorPrimaries: Int,
    val transferCharacteristics: Int,
    val matrixCoefficients: Int,
    val fullRange: Boolean
)

internal data class QuickTimeMovChunkOffsetEvidence(
    val tableType: String,
    val entryCount: Long,
    val minimumOffset: Long?,
    val maximumOffset: Long?,
    val allOffsetsInsideFile: Boolean,
    val allOffsetsInsideMdat: Boolean,
    val firstInvalidOffset: Long?,
    val allSampleExtentsInsideFile: Boolean,
    val allSampleExtentsInsideMdat: Boolean,
    val firstInvalidExtentStart: Long?,
    val firstInvalidExtentEndExclusive: Long?
)

internal data class QuickTimeMovSampleToChunkEvidence(
    val entryCount: Long,
    val mappedChunkCount: Long,
    val mappedSampleCount: Long
)

internal data class QuickTimeMovHevcConfigurationEvidence(
    val configurationVersion: Int,
    val profileSpace: Int,
    val profileIdc: Int,
    val profileCompatibilityFlags: Long,
    val levelIdc: Int,
    val chromaFormatIdc: Int,
    val bitDepthLuma: Int,
    val bitDepthChroma: Int,
    val nalLengthFieldBytes: Int,
    val arrayCount: Int,
    val nalUnitCount: Long,
    val hasVps: Boolean,
    val hasSps: Boolean,
    val hasPps: Boolean
)

internal data class QuickTimeMovVideoTrackEvidence(
    val trackId: Long,
    val sampleEntry: String,
    val sampleEntryWidth: Int,
    val sampleEntryHeight: Int,
    val tkhdWidth: Double,
    val tkhdHeight: Double,
    val rotationDegrees: Int?,
    val rotationMatrixRecognized: Boolean,
    val mediaTimescale: Long,
    val mediaDurationUnits: Long,
    val durationSeconds: Double,
    val sampleCount: Long,
    val zeroSizedSampleCount: Long,
    val samplePayloadBytes: Long,
    val sttsEntryCount: Long,
    val sttsCoveredSampleCount: Long,
    val sttsDurationUnits: Long,
    val timingCoversEverySample: Boolean,
    val nclxColor: QuickTimeMovNclxEvidence?,
    val hevcConfiguration: QuickTimeMovHevcConfigurationEvidence?,
    val sampleToChunk: QuickTimeMovSampleToChunkEvidence,
    val chunkOffsets: QuickTimeMovChunkOffsetEvidence,
    val measuredVideoBitrateBps: Long?
)

internal data class QuickTimeMovArtifactReport(
    val status: QuickTimeMovArtifactStatus,
    val filePath: String,
    val fileBytes: Long,
    val majorBrand: String?,
    val compatibleBrands: List<String>,
    val topLevelBoxes: List<QuickTimeMovTopLevelBoxEvidence>,
    val ftypPresent: Boolean,
    val moovPresent: Boolean,
    val mdatPresent: Boolean,
    val videoTrack: QuickTimeMovVideoTrackEvidence?,
    val measuredFileBitrateBps: Long?,
    val failures: List<String>
) {
    val passed: Boolean get() = status == QuickTimeMovArtifactStatus.PASS

    fun requirePass(): QuickTimeMovArtifactReport {
        check(passed) { failures.joinToString(prefix = "MOV artifact inspection failed: ", separator = "; ") }
        return this
    }
}

/**
 * Bounded-memory QuickTime/ISO-BMFF artifact inspector.
 *
 * Only box headers, scalar fields, and one bounded HEVC SPS (at most 65,535 bytes by hvcC syntax) are
 * read into memory. Potentially large `stsz`, `stts`, `stsc`, `stco`, and `co64` tables are validated
 * and accumulated directly from [RandomAccessFile], so inspecting a multi-gigabyte recording never
 * requires loading the recording (or even a whole sample table) into memory. The `stsc` mapping is
 * streamed against sample sizes and chunk offsets so the full byte extent of every chunk—not only its
 * start address—must remain inside one `mdat`. Any malformed/truncated box, non-Main10 HEVC config, or
 * inconsistent required table produces a fail-closed report.
 */
internal object QuickTimeMovArtifactInspector {
    fun inspect(
        file: File,
        requirements: QuickTimeMovArtifactRequirements = QuickTimeMovArtifactRequirements()
    ): QuickTimeMovArtifactReport {
        val canonicalPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        if (!file.isFile) {
            return failureReport(canonicalPath, 0L, "Input is not a readable regular file")
        }
        val fileBytes = file.length()
        if (fileBytes < BASIC_BOX_HEADER_SIZE) {
            return failureReport(canonicalPath, fileBytes, "File is too small to contain an ISO-BMFF box")
        }
        return try {
            RandomAccessFile(file, "r").use { input ->
                InspectorSession(input, canonicalPath, fileBytes, requirements).inspect()
            }
        } catch (failure: Exception) {
            failureReport(
                filePath = canonicalPath,
                fileBytes = fileBytes,
                reason = "${failure.javaClass.simpleName}: ${failure.message ?: "invalid MOV artifact"}"
            )
        }
    }

    private fun failureReport(filePath: String, fileBytes: Long, reason: String) =
        QuickTimeMovArtifactReport(
            status = QuickTimeMovArtifactStatus.FAIL,
            filePath = filePath,
            fileBytes = fileBytes,
            majorBrand = null,
            compatibleBrands = emptyList(),
            topLevelBoxes = emptyList(),
            ftypPresent = false,
            moovPresent = false,
            mdatPresent = false,
            videoTrack = null,
            measuredFileBitrateBps = null,
            failures = listOf(reason)
        )

    private class InspectorSession(
        private val input: RandomAccessFile,
        private val filePath: String,
        private val fileBytes: Long,
        private val requirements: QuickTimeMovArtifactRequirements
    ) {
        fun inspect(): QuickTimeMovArtifactReport {
            val topLevel = readBoxSequence(0L, fileBytes, "top-level file")
            val ftypBoxes = topLevel.filterType("ftyp")
            val moovBoxes = topLevel.filterType("moov")
            val mdatBoxes = topLevel.filterType("mdat")
            parseRequire(ftypBoxes.size == 1) { "Expected exactly one top-level ftyp box" }
            parseRequire(moovBoxes.size == 1) { "Expected exactly one top-level moov box" }
            parseRequire(mdatBoxes.isNotEmpty()) { "Expected at least one top-level mdat box" }

            val fileType = parseFileType(ftypBoxes.single())
            val movieChildren = readChildren(moovBoxes.single(), "moov")
            val trackBoxes = movieChildren.filterType("trak")
            parseRequire(trackBoxes.isNotEmpty()) { "moov contains no tracks" }
            val videoTracks = trackBoxes.mapNotNull { parseVideoTrack(it, mdatBoxes) }
            parseRequire(videoTracks.size == 1) {
                "Expected exactly one video track, found ${videoTracks.size}"
            }
            val parsedTrack = videoTracks.single()
            val video = buildTrackEvidence(parsedTrack)
            val failures = verify(video)
            val fileBitrate = measuredBitrate(fileBytes, video.sttsDurationUnits, video.mediaTimescale)

            return QuickTimeMovArtifactReport(
                status = if (failures.isEmpty()) {
                    QuickTimeMovArtifactStatus.PASS
                } else {
                    QuickTimeMovArtifactStatus.FAIL
                },
                filePath = filePath,
                fileBytes = fileBytes,
                majorBrand = fileType.majorBrand,
                compatibleBrands = fileType.compatibleBrands,
                topLevelBoxes = topLevel.map {
                    QuickTimeMovTopLevelBoxEvidence(it.type, it.offset, it.size, it.headerSize)
                },
                ftypPresent = true,
                moovPresent = true,
                mdatPresent = true,
                videoTrack = video,
                measuredFileBitrateBps = fileBitrate,
                failures = failures
            )
        }

        private fun parseFileType(box: BoxRef): FileTypeEvidence {
            parseRequire(box.payloadSize >= 8L) { "ftyp payload is truncated" }
            parseRequire((box.payloadSize - 8L) % 4L == 0L) { "ftyp compatible brands are misaligned" }
            val majorBrand = readFourCc(box.payloadOffset, box.end)
            val brandCount = (box.payloadSize - 8L) / 4L
            parseRequire(brandCount <= MAX_BRANDS) { "ftyp contains too many compatible brands" }
            val brands = ArrayList<String>(brandCount.toInt())
            var cursor = box.payloadOffset + 8L
            repeat(brandCount.toInt()) {
                brands += readFourCc(cursor, box.end)
                cursor += 4L
            }
            return FileTypeEvidence(majorBrand, brands)
        }

        private fun parseVideoTrack(trak: BoxRef, mdatBoxes: List<BoxRef>): ParsedTrack? {
            val trakChildren = readChildren(trak, "trak")
            val mdia = trakChildren.singleRequired("mdia", "trak")
            val mdiaChildren = readChildren(mdia, "mdia")
            val handler = mdiaChildren.singleRequired("hdlr", "mdia")
            if (parseHandlerType(handler) != "vide") return null

            val tkhd = parseTrackHeader(trakChildren.singleRequired("tkhd", "trak"))
            val mediaHeader = parseMediaHeader(mdiaChildren.singleRequired("mdhd", "mdia"))
            val minf = mdiaChildren.singleRequired("minf", "video mdia")
            val stbl = readChildren(minf, "video minf").singleRequired("stbl", "video minf")
            val sampleTableChildren = readChildren(stbl, "video stbl")
            val sampleDescription = parseSampleDescription(
                sampleTableChildren.singleRequired("stsd", "video stbl")
            )
            val sampleSizes = parseSampleSizes(
                sampleTableChildren.singleRequired("stsz", "video stbl")
            )
            val timeToSample = parseTimeToSample(
                sampleTableChildren.singleRequired("stts", "video stbl")
            )
            val sampleToChunk = parseSampleToChunk(
                sampleTableChildren.singleRequired("stsc", "video stbl")
            )
            val stco = sampleTableChildren.filterType("stco")
            val co64 = sampleTableChildren.filterType("co64")
            parseRequire(stco.size + co64.size == 1) {
                "Video stbl must contain exactly one stco or co64 chunk-offset table"
            }
            val chunkTable = parseChunkOffsets((stco + co64).single(), mdatBoxes)
            val sampleLayout = validateSampleLayout(
                sampleSizes = sampleSizes,
                sampleToChunk = sampleToChunk,
                chunkTable = chunkTable,
                mdatBoxes = mdatBoxes
            )
            return ParsedTrack(
                trackHeader = tkhd,
                mediaHeader = mediaHeader,
                sampleDescription = sampleDescription,
                sampleSizes = sampleSizes,
                timeToSample = timeToSample,
                sampleToChunk = sampleToChunk,
                chunkTable = chunkTable,
                sampleLayout = sampleLayout
            )
        }

        private fun parseHandlerType(box: BoxRef): String {
            parseRequire(box.payloadSize >= 12L) { "hdlr payload is truncated" }
            requireFullBoxVersion(box, 0)
            return readFourCc(box.payloadOffset + 8L, box.end)
        }

        private fun parseTrackHeader(box: BoxRef): TrackHeaderEvidence {
            val version = readU8(box.payloadOffset, box.end)
            val matrixOffset: Long
            val widthOffset: Long
            val trackIdOffset: Long
            when (version) {
                0 -> {
                    parseRequire(box.payloadSize >= 84L) { "Version-0 tkhd payload is truncated" }
                    trackIdOffset = box.payloadOffset + 12L
                    matrixOffset = box.payloadOffset + 40L
                    widthOffset = box.payloadOffset + 76L
                }
                1 -> {
                    parseRequire(box.payloadSize >= 96L) { "Version-1 tkhd payload is truncated" }
                    trackIdOffset = box.payloadOffset + 20L
                    matrixOffset = box.payloadOffset + 52L
                    widthOffset = box.payloadOffset + 88L
                }
                else -> parseFail("Unsupported tkhd version $version")
            }
            val matrix = IntArray(9) { index -> readI32(matrixOffset + index * 4L, box.end) }
            val rotation = recognizeRotation(matrix)
            return TrackHeaderEvidence(
                trackId = readU32(trackIdOffset, box.end),
                width = readU32(widthOffset, box.end).toDouble() / FIXED_16_16_SCALE,
                height = readU32(widthOffset + 4L, box.end).toDouble() / FIXED_16_16_SCALE,
                rotationDegrees = rotation,
                rotationMatrixRecognized = rotation != null
            )
        }

        private fun parseMediaHeader(box: BoxRef): MediaHeaderEvidence {
            val version = readU8(box.payloadOffset, box.end)
            val timescaleOffset: Long
            val durationOffset: Long
            val duration: Long
            when (version) {
                0 -> {
                    parseRequire(box.payloadSize >= 24L) { "Version-0 mdhd payload is truncated" }
                    timescaleOffset = box.payloadOffset + 12L
                    durationOffset = box.payloadOffset + 16L
                    duration = readU32(durationOffset, box.end)
                }
                1 -> {
                    parseRequire(box.payloadSize >= 36L) { "Version-1 mdhd payload is truncated" }
                    timescaleOffset = box.payloadOffset + 20L
                    durationOffset = box.payloadOffset + 24L
                    duration = readU64(durationOffset, box.end)
                }
                else -> parseFail("Unsupported mdhd version $version")
            }
            val timescale = readU32(timescaleOffset, box.end)
            parseRequire(timescale > 0L) { "mdhd timescale must be positive" }
            return MediaHeaderEvidence(timescale, duration)
        }

        private fun parseSampleDescription(box: BoxRef): SampleDescriptionEvidence {
            parseRequire(box.payloadSize >= 8L) { "stsd payload is truncated" }
            requireFullBoxVersion(box, 0)
            val declaredEntryCount = readU32(box.payloadOffset + 4L, box.end)
            parseRequire(declaredEntryCount == 1L) {
                "Video stsd must declare exactly one sample entry, found $declaredEntryCount"
            }
            val entries = readBoxSequence(box.payloadOffset + 8L, box.end, "video stsd")
            parseRequire(entries.size == 1) {
                "Video stsd sample-entry count does not match its payload"
            }
            val entry = entries.single()
            parseRequire(entry.payloadSize >= VISUAL_SAMPLE_ENTRY_HEADER_SIZE) {
                "${entry.type} visual sample entry is truncated"
            }
            val width = readU16(entry.payloadOffset + 24L, entry.end)
            val height = readU16(entry.payloadOffset + 26L, entry.end)
            parseRequire(width > 0 && height > 0) { "Video sample-entry geometry must be positive" }
            val childStart = entry.payloadOffset + VISUAL_SAMPLE_ENTRY_HEADER_SIZE
            val children = readBoxSequence(childStart, entry.end, "${entry.type} visual sample entry")
            val colorBoxes = children.filterType("colr")
            parseRequire(colorBoxes.size <= 1) { "Video sample entry contains ambiguous colr boxes" }
            val hevcConfiguration = if (entry.type == "hvc1" || entry.type == "hev1") {
                val configurationBoxes = children.filterType("hvcC")
                parseRequire(configurationBoxes.size == 1) {
                    "${entry.type} sample entry must contain exactly one hvcC box"
                }
                parseHevcConfiguration(configurationBoxes.single())
            } else {
                null
            }
            return SampleDescriptionEvidence(
                sampleEntry = entry.type,
                width = width,
                height = height,
                color = colorBoxes.singleOrNull()?.let(::parseColor),
                hevcConfiguration = hevcConfiguration
            )
        }

        private fun parseColor(box: BoxRef): QuickTimeMovNclxEvidence? {
            parseRequire(box.payloadSize >= 4L) { "colr payload is truncated" }
            val colorType = readFourCc(box.payloadOffset, box.end)
            if (colorType != "nclx") return null
            parseRequire(box.payloadSize >= 11L) { "nclx colr payload is truncated" }
            val rangeByte = readU8(box.payloadOffset + 10L, box.end)
            parseRequire(rangeByte and 0x7F == 0) { "nclx full-range reserved bits are non-zero" }
            return QuickTimeMovNclxEvidence(
                colorPrimaries = readU16(box.payloadOffset + 4L, box.end),
                transferCharacteristics = readU16(box.payloadOffset + 6L, box.end),
                matrixCoefficients = readU16(box.payloadOffset + 8L, box.end),
                fullRange = rangeByte and 0x80 != 0
            )
        }

        private fun parseHevcConfiguration(box: BoxRef): QuickTimeMovHevcConfigurationEvidence {
            parseRequire(box.payloadSize >= HEVC_CONFIGURATION_HEADER_SIZE) {
                "hvcC payload is truncated"
            }
            val base = box.payloadOffset
            val configurationVersion = readU8(base, box.end)
            parseRequire(configurationVersion == 1) {
                "Unsupported hvcC configurationVersion $configurationVersion"
            }
            val packedProfile = readU8(base + 1L, box.end)
            val profileSpace = packedProfile ushr 6
            val profileIdc = packedProfile and 0x1F
            val profileCompatibilityFlags = readU32(base + 2L, box.end)
            val levelIdc = readU8(base + 12L, box.end)
            val minimumSpatialSegmentation = readU16(base + 13L, box.end)
            val parallelismType = readU8(base + 15L, box.end)
            val packedChromaFormat = readU8(base + 16L, box.end)
            val packedLumaDepth = readU8(base + 17L, box.end)
            val packedChromaDepth = readU8(base + 18L, box.end)
            val packedTemporal = readU8(base + 21L, box.end)
            val arrayCount = readU8(base + 22L, box.end)

            parseRequire(minimumSpatialSegmentation and 0xF000 == 0xF000) {
                "hvcC min_spatial_segmentation_idc reserved bits are invalid"
            }
            parseRequire(parallelismType and 0xFC == 0xFC) {
                "hvcC parallelismType reserved bits are invalid"
            }
            parseRequire(packedChromaFormat and 0xFC == 0xFC) {
                "hvcC chromaFormat reserved bits are invalid"
            }
            parseRequire(packedLumaDepth and 0xF8 == 0xF8) {
                "hvcC bitDepthLumaMinus8 reserved bits are invalid"
            }
            parseRequire(packedChromaDepth and 0xF8 == 0xF8) {
                "hvcC bitDepthChromaMinus8 reserved bits are invalid"
            }

            val chromaFormatIdc = packedChromaFormat and 0x03
            val bitDepthLuma = 8 + (packedLumaDepth and 0x07)
            val bitDepthChroma = 8 + (packedChromaDepth and 0x07)
            val nalLengthFieldBytes = (packedTemporal and 0x03) + 1
            val temporalLayerCount = (packedTemporal ushr 3) and 0x07
            parseRequire(profileSpace == 0 && profileIdc == HEVC_MAIN10_PROFILE_IDC) {
                "hvcC is not HEVC Main10: profileSpace=$profileSpace profileIdc=$profileIdc"
            }
            parseRequire(levelIdc > 0) { "hvcC general_level_idc must be positive" }
            parseRequire(chromaFormatIdc == HEVC_MAIN10_CHROMA_FORMAT_IDC) {
                "hvcC Main10 output must be 4:2:0: chromaFormatIdc=$chromaFormatIdc"
            }
            parseRequire(bitDepthLuma == HEVC_MAIN10_BIT_DEPTH && bitDepthChroma == HEVC_MAIN10_BIT_DEPTH) {
                "hvcC is not 10-bit: luma=$bitDepthLuma chroma=$bitDepthChroma"
            }
            parseRequire(nalLengthFieldBytes == MOV_NAL_LENGTH_FIELD_BYTES) {
                "hvcC NAL length field is $nalLengthFieldBytes bytes, expected $MOV_NAL_LENGTH_FIELD_BYTES"
            }
            parseRequire(temporalLayerCount > 0) { "hvcC numTemporalLayers must be positive" }

            val seenArrayTypes = BooleanArray(64)
            var cursor = Math.addExact(base, HEVC_CONFIGURATION_HEADER_SIZE)
            var nalUnitCount = 0L
            var primarySps: ByteArray? = null
            repeat(arrayCount) {
                requireRange(cursor, 3L, box.end)
                val arrayHeader = readU8(cursor, box.end)
                parseRequire(arrayHeader and 0x40 == 0) { "hvcC NAL array reserved bit is non-zero" }
                val nalType = arrayHeader and 0x3F
                parseRequire(!seenArrayTypes[nalType]) { "hvcC contains duplicate NAL array type $nalType" }
                seenArrayTypes[nalType] = true
                val unitCount = readU16(cursor + 1L, box.end)
                parseRequire(unitCount > 0) { "hvcC NAL array type $nalType is empty" }
                cursor = Math.addExact(cursor, 3L)
                repeat(unitCount) {
                    requireRange(cursor, 2L, box.end)
                    val unitSize = readU16(cursor, box.end)
                    parseRequire(unitSize >= HEVC_NAL_HEADER_SIZE) {
                        "hvcC NAL type $nalType is shorter than an HEVC NAL header"
                    }
                    cursor = Math.addExact(cursor, 2L)
                    requireRange(cursor, unitSize.toLong(), box.end)
                    val firstHeaderByte = readU8(cursor, box.end)
                    val secondHeaderByte = readU8(cursor + 1L, box.end)
                    parseRequire(firstHeaderByte and 0x80 == 0) {
                        "hvcC NAL type $nalType has forbidden_zero_bit set"
                    }
                    val actualNalType = (firstHeaderByte ushr 1) and 0x3F
                    parseRequire(actualNalType == nalType) {
                        "hvcC array type $nalType contains NAL type $actualNalType"
                    }
                    parseRequire(secondHeaderByte and 0x07 != 0) {
                        "hvcC NAL type $nalType has temporal_id_plus1=0"
                    }
                    if (nalType == HEVC_NAL_TYPE_SPS && primarySps == null) {
                        primarySps = ByteArray(unitSize).also { bytes ->
                            input.seek(cursor)
                            input.readFully(bytes)
                        }
                    }
                    cursor = Math.addExact(cursor, unitSize.toLong())
                    nalUnitCount = Math.addExact(nalUnitCount, 1L)
                    parseRequire(nalUnitCount <= MAX_HEVC_CONFIGURATION_NAL_UNITS) {
                        "hvcC NAL count exceeds the inspection safety limit"
                    }
                }
            }
            parseRequire(cursor == box.end) { "hvcC contains trailing or unparsed bytes" }
            parseRequire(
                seenArrayTypes[HEVC_NAL_TYPE_VPS] &&
                    seenArrayTypes[HEVC_NAL_TYPE_SPS] &&
                    seenArrayTypes[HEVC_NAL_TYPE_PPS]
            ) {
                "hvcC must contain non-empty VPS, SPS, and PPS arrays"
            }
            val spsFields = HevcDecoderConfigurationRecordPolicy.fromAnnexBParameterSets(
                listOf(requireNotNull(primarySps))
            )
            parseRequire(
                spsFields.profileSpace == 0 &&
                    spsFields.profileIdc == HEVC_MAIN10_PROFILE_IDC &&
                    spsFields.chromaFormatIdc == HEVC_MAIN10_CHROMA_FORMAT_IDC &&
                    spsFields.bitDepthLumaMinus8 + 8 == HEVC_MAIN10_BIT_DEPTH &&
                    spsFields.bitDepthChromaMinus8 + 8 == HEVC_MAIN10_BIT_DEPTH
            ) {
                "hvcC SPS does not encode a valid 10-bit Main10 4:2:0 profile"
            }
            parseRequire(
                spsFields.profileIdc == profileIdc &&
                    spsFields.profileCompatibilityFlags == profileCompatibilityFlags &&
                    spsFields.levelIdc == levelIdc &&
                    spsFields.chromaFormatIdc == chromaFormatIdc &&
                    spsFields.bitDepthLumaMinus8 + 8 == bitDepthLuma &&
                    spsFields.bitDepthChromaMinus8 + 8 == bitDepthChroma
            ) {
                "hvcC record fields disagree with the contained SPS"
            }
            return QuickTimeMovHevcConfigurationEvidence(
                configurationVersion = configurationVersion,
                profileSpace = profileSpace,
                profileIdc = profileIdc,
                profileCompatibilityFlags = profileCompatibilityFlags,
                levelIdc = levelIdc,
                chromaFormatIdc = chromaFormatIdc,
                bitDepthLuma = bitDepthLuma,
                bitDepthChroma = bitDepthChroma,
                nalLengthFieldBytes = nalLengthFieldBytes,
                arrayCount = arrayCount,
                nalUnitCount = nalUnitCount,
                hasVps = seenArrayTypes[HEVC_NAL_TYPE_VPS],
                hasSps = seenArrayTypes[HEVC_NAL_TYPE_SPS],
                hasPps = seenArrayTypes[HEVC_NAL_TYPE_PPS]
            )
        }

        private fun parseSampleSizes(box: BoxRef): SampleSizeEvidence {
            parseRequire(box.payloadSize >= 12L) { "stsz payload is truncated" }
            requireFullBoxVersion(box, 0)
            val fixedSampleSize = readU32(box.payloadOffset + 4L, box.end)
            val sampleCount = readU32(box.payloadOffset + 8L, box.end)
            parseRequire(sampleCount <= MAX_TABLE_ENTRIES) {
                "stsz sample count $sampleCount exceeds the inspection safety limit"
            }
            if (fixedSampleSize != 0L) {
                parseRequire(box.payloadSize == 12L) { "Fixed-size stsz contains unexpected trailing data" }
                return SampleSizeEvidence(
                    sampleCount = sampleCount,
                    zeroSizedSampleCount = 0L,
                    totalBytes = Math.multiplyExact(fixedSampleSize, sampleCount),
                    fixedSampleSize = fixedSampleSize,
                    explicitSizesOffset = null,
                    tableEnd = box.end
                )
            }
            val requiredPayload = Math.addExact(12L, Math.multiplyExact(sampleCount, 4L))
            parseRequire(box.payloadSize == requiredPayload) {
                "stsz explicit sample table is truncated or has trailing bytes"
            }
            var totalBytes = 0L
            var zeroSizedSamples = 0L
            input.seek(box.payloadOffset + 12L)
            repeatLong(sampleCount) {
                val size = input.readInt().toLong() and UINT32_MASK
                if (size == 0L) zeroSizedSamples += 1L
                totalBytes = Math.addExact(totalBytes, size)
            }
            return SampleSizeEvidence(
                sampleCount = sampleCount,
                zeroSizedSampleCount = zeroSizedSamples,
                totalBytes = totalBytes,
                fixedSampleSize = 0L,
                explicitSizesOffset = box.payloadOffset + 12L,
                tableEnd = box.end
            )
        }

        private fun parseTimeToSample(box: BoxRef): TimeToSampleEvidence {
            parseRequire(box.payloadSize >= 8L) { "stts payload is truncated" }
            requireFullBoxVersion(box, 0)
            val entryCount = readU32(box.payloadOffset + 4L, box.end)
            parseRequire(entryCount <= MAX_TABLE_ENTRIES) {
                "stts entry count $entryCount exceeds the inspection safety limit"
            }
            val requiredPayload = Math.addExact(8L, Math.multiplyExact(entryCount, 8L))
            parseRequire(box.payloadSize == requiredPayload) {
                "stts table is truncated or has trailing bytes"
            }
            var coveredSamples = 0L
            var durationUnits = 0L
            input.seek(box.payloadOffset + 8L)
            repeatLong(entryCount) {
                val count = input.readInt().toLong() and UINT32_MASK
                val delta = input.readInt().toLong() and UINT32_MASK
                parseRequire(count > 0L) { "stts contains a zero-length sample run" }
                parseRequire(delta > 0L) { "stts contains a zero sample duration" }
                coveredSamples = Math.addExact(coveredSamples, count)
                durationUnits = Math.addExact(durationUnits, Math.multiplyExact(count, delta))
            }
            return TimeToSampleEvidence(entryCount, coveredSamples, durationUnits)
        }

        private fun parseSampleToChunk(box: BoxRef): SampleToChunkTable {
            parseRequire(box.payloadSize >= 8L) { "stsc payload is truncated" }
            requireFullBoxVersion(box, 0)
            val entryCount = readU32(box.payloadOffset + 4L, box.end)
            parseRequire(entryCount <= MAX_TABLE_ENTRIES) {
                "stsc entry count $entryCount exceeds the inspection safety limit"
            }
            val requiredPayload = Math.addExact(8L, Math.multiplyExact(entryCount, STSC_ENTRY_SIZE))
            parseRequire(box.payloadSize == requiredPayload) {
                "stsc table is truncated or has trailing bytes"
            }
            var previousFirstChunk = 0L
            repeatLong(entryCount) { index ->
                val entry = readSampleToChunkEntry(box, index)
                parseRequire(entry.firstChunk > 0L) { "stsc first_chunk must be positive" }
                parseRequire(entry.firstChunk > previousFirstChunk) {
                    "stsc first_chunk values must be strictly increasing"
                }
                parseRequire(entry.samplesPerChunk > 0L) { "stsc samples_per_chunk must be positive" }
                parseRequire(entry.sampleDescriptionIndex == 1L) {
                    "stsc references sample description ${entry.sampleDescriptionIndex}, but stsd contains one entry"
                }
                previousFirstChunk = entry.firstChunk
            }
            return SampleToChunkTable(box, entryCount)
        }

        private fun parseChunkOffsets(box: BoxRef, mdatBoxes: List<BoxRef>): ChunkTableEvidence {
            parseRequire(box.payloadSize >= 8L) { "${box.type} payload is truncated" }
            requireFullBoxVersion(box, 0)
            val entryCount = readU32(box.payloadOffset + 4L, box.end)
            parseRequire(entryCount <= MAX_TABLE_ENTRIES) {
                "${box.type} entry count $entryCount exceeds the inspection safety limit"
            }
            val entryBytes = if (box.type == "co64") 8L else 4L
            val requiredPayload = Math.addExact(8L, Math.multiplyExact(entryCount, entryBytes))
            parseRequire(box.payloadSize == requiredPayload) {
                "${box.type} table is truncated or has trailing bytes"
            }
            var minimumOffset: Long? = null
            var maximumOffset: Long? = null
            var allInsideFile = true
            var allInsideMdat = true
            var firstInvalidOffset: Long? = null
            input.seek(box.payloadOffset + 8L)
            repeatLong(entryCount) {
                val offset = if (box.type == "co64") {
                    val value = input.readLong()
                    parseRequire(value >= 0L) { "co64 offset exceeds the signed 64-bit file range" }
                    value
                } else {
                    input.readInt().toLong() and UINT32_MASK
                }
                minimumOffset = minimumOffset?.coerceAtMost(offset) ?: offset
                maximumOffset = maximumOffset?.coerceAtLeast(offset) ?: offset
                val insideFile = offset in 0 until fileBytes
                val insideMdat = mdatBoxes.any { offset >= it.payloadOffset && offset < it.end }
                if (!insideFile) allInsideFile = false
                if (!insideMdat) allInsideMdat = false
                if ((!insideFile || !insideMdat) && firstInvalidOffset == null) {
                    firstInvalidOffset = offset
                }
            }
            return ChunkTableEvidence(
                type = box.type,
                entryCount = entryCount,
                minimumOffset = minimumOffset,
                maximumOffset = maximumOffset,
                allOffsetsInsideFile = allInsideFile,
                allOffsetsInsideMdat = allInsideMdat,
                firstInvalidOffset = firstInvalidOffset,
                box = box,
                entryBytes = entryBytes
            )
        }

        private fun validateSampleLayout(
            sampleSizes: SampleSizeEvidence,
            sampleToChunk: SampleToChunkTable,
            chunkTable: ChunkTableEvidence,
            mdatBoxes: List<BoxRef>
        ): SampleLayoutEvidence {
            if (chunkTable.entryCount == 0L) {
                parseRequire(sampleSizes.sampleCount == 0L) {
                    "stsz declares ${sampleSizes.sampleCount} samples but the chunk-offset table is empty"
                }
                parseRequire(sampleToChunk.entryCount == 0L) {
                    "stsc must be empty when the chunk-offset table is empty"
                }
                return SampleLayoutEvidence(
                    mappedChunkCount = 0L,
                    mappedSampleCount = 0L,
                    allExtentsInsideFile = true,
                    allExtentsInsideMdat = true,
                    firstInvalidExtentStart = null,
                    firstInvalidExtentEndExclusive = null
                )
            }

            parseRequire(sampleToChunk.entryCount > 0L) {
                "Video chunks exist but stsc has no sample-to-chunk entries"
            }
            var currentEntryIndex = 0L
            var currentEntry = readSampleToChunkEntry(sampleToChunk.box, currentEntryIndex)
            parseRequire(currentEntry.firstChunk == 1L) {
                "The first stsc entry must begin at chunk 1"
            }
            var nextEntry = if (sampleToChunk.entryCount > 1L) {
                readSampleToChunkEntry(sampleToChunk.box, 1L)
            } else {
                null
            }
            parseRequire(
                nextEntry == null || nextEntry.firstChunk <= chunkTable.entryCount
            ) {
                "stsc first_chunk ${nextEntry?.firstChunk} exceeds chunk count ${chunkTable.entryCount}"
            }

            var mappedSamples = 0L
            var allExtentsInsideFile = true
            var allExtentsInsideMdat = true
            var firstInvalidExtentStart: Long? = null
            var firstInvalidExtentEndExclusive: Long? = null
            repeatLong(chunkTable.entryCount) { zeroBasedChunk ->
                val chunkNumber = Math.addExact(zeroBasedChunk, 1L)
                if (nextEntry?.firstChunk == chunkNumber) {
                    currentEntryIndex = Math.addExact(currentEntryIndex, 1L)
                    currentEntry = requireNotNull(nextEntry)
                    nextEntry = if (currentEntryIndex + 1L < sampleToChunk.entryCount) {
                        readSampleToChunkEntry(sampleToChunk.box, currentEntryIndex + 1L)
                    } else {
                        null
                    }
                    parseRequire(
                        nextEntry == null || nextEntry.firstChunk <= chunkTable.entryCount
                    ) {
                        "stsc first_chunk ${nextEntry?.firstChunk} exceeds chunk count ${chunkTable.entryCount}"
                    }
                }

                parseRequire(currentEntry.samplesPerChunk <= sampleSizes.sampleCount - mappedSamples) {
                    "stsc maps more samples than stsz declares at chunk $chunkNumber"
                }
                var chunkPayloadBytes = 0L
                repeatLong(currentEntry.samplesPerChunk) {
                    chunkPayloadBytes = Math.addExact(
                        chunkPayloadBytes,
                        readSampleSize(sampleSizes, mappedSamples)
                    )
                    mappedSamples = Math.addExact(mappedSamples, 1L)
                }
                val chunkOffset = readChunkOffset(chunkTable, zeroBasedChunk)
                val chunkEndExclusive = runCatching {
                    Math.addExact(chunkOffset, chunkPayloadBytes)
                }.getOrNull()
                val insideFile = chunkEndExclusive != null &&
                    chunkOffset >= 0L &&
                    chunkOffset < fileBytes &&
                    chunkEndExclusive <= fileBytes
                val insideMdat = chunkEndExclusive != null && mdatBoxes.any { mdat ->
                    chunkOffset >= mdat.payloadOffset &&
                        chunkOffset < mdat.end &&
                        chunkEndExclusive <= mdat.end
                }
                if (!insideFile) allExtentsInsideFile = false
                if (!insideMdat) allExtentsInsideMdat = false
                if ((!insideFile || !insideMdat) && firstInvalidExtentStart == null) {
                    firstInvalidExtentStart = chunkOffset
                    firstInvalidExtentEndExclusive = chunkEndExclusive
                }
            }
            parseRequire(mappedSamples == sampleSizes.sampleCount) {
                "stsc maps $mappedSamples samples but stsz declares ${sampleSizes.sampleCount}"
            }
            parseRequire(currentEntryIndex + 1L == sampleToChunk.entryCount) {
                "stsc contains entries that do not map any declared chunk"
            }
            return SampleLayoutEvidence(
                mappedChunkCount = chunkTable.entryCount,
                mappedSampleCount = mappedSamples,
                allExtentsInsideFile = allExtentsInsideFile,
                allExtentsInsideMdat = allExtentsInsideMdat,
                firstInvalidExtentStart = firstInvalidExtentStart,
                firstInvalidExtentEndExclusive = firstInvalidExtentEndExclusive
            )
        }

        private fun readSampleToChunkEntry(box: BoxRef, index: Long): SampleToChunkEntry {
            val offset = Math.addExact(
                box.payloadOffset + 8L,
                Math.multiplyExact(index, STSC_ENTRY_SIZE)
            )
            return SampleToChunkEntry(
                firstChunk = readU32(offset, box.end),
                samplesPerChunk = readU32(offset + 4L, box.end),
                sampleDescriptionIndex = readU32(offset + 8L, box.end)
            )
        }

        private fun readSampleSize(table: SampleSizeEvidence, index: Long): Long {
            parseRequire(index in 0 until table.sampleCount) { "stsz sample index $index is out of range" }
            if (table.fixedSampleSize != 0L) return table.fixedSampleSize
            val base = requireNotNull(table.explicitSizesOffset)
            val offset = Math.addExact(base, Math.multiplyExact(index, 4L))
            return readU32(offset, table.tableEnd)
        }

        private fun readChunkOffset(table: ChunkTableEvidence, index: Long): Long {
            parseRequire(index in 0 until table.entryCount) { "${table.type} chunk index $index is out of range" }
            val offset = Math.addExact(
                table.box.payloadOffset + 8L,
                Math.multiplyExact(index, table.entryBytes)
            )
            return if (table.type == "co64") {
                readU64(offset, table.box.end)
            } else {
                readU32(offset, table.box.end)
            }
        }

        private fun buildTrackEvidence(track: ParsedTrack): QuickTimeMovVideoTrackEvidence {
            val durationSeconds = track.timeToSample.durationUnits.toDouble() /
                track.mediaHeader.timescale.toDouble()
            return QuickTimeMovVideoTrackEvidence(
                trackId = track.trackHeader.trackId,
                sampleEntry = track.sampleDescription.sampleEntry,
                sampleEntryWidth = track.sampleDescription.width,
                sampleEntryHeight = track.sampleDescription.height,
                tkhdWidth = track.trackHeader.width,
                tkhdHeight = track.trackHeader.height,
                rotationDegrees = track.trackHeader.rotationDegrees,
                rotationMatrixRecognized = track.trackHeader.rotationMatrixRecognized,
                mediaTimescale = track.mediaHeader.timescale,
                mediaDurationUnits = track.mediaHeader.duration,
                durationSeconds = durationSeconds,
                sampleCount = track.sampleSizes.sampleCount,
                zeroSizedSampleCount = track.sampleSizes.zeroSizedSampleCount,
                samplePayloadBytes = track.sampleSizes.totalBytes,
                sttsEntryCount = track.timeToSample.entryCount,
                sttsCoveredSampleCount = track.timeToSample.coveredSamples,
                sttsDurationUnits = track.timeToSample.durationUnits,
                timingCoversEverySample = track.timeToSample.coveredSamples == track.sampleSizes.sampleCount,
                nclxColor = track.sampleDescription.color,
                hevcConfiguration = track.sampleDescription.hevcConfiguration,
                sampleToChunk = QuickTimeMovSampleToChunkEvidence(
                    entryCount = track.sampleToChunk.entryCount,
                    mappedChunkCount = track.sampleLayout.mappedChunkCount,
                    mappedSampleCount = track.sampleLayout.mappedSampleCount
                ),
                chunkOffsets = QuickTimeMovChunkOffsetEvidence(
                    tableType = track.chunkTable.type,
                    entryCount = track.chunkTable.entryCount,
                    minimumOffset = track.chunkTable.minimumOffset,
                    maximumOffset = track.chunkTable.maximumOffset,
                    allOffsetsInsideFile = track.chunkTable.allOffsetsInsideFile,
                    allOffsetsInsideMdat = track.chunkTable.allOffsetsInsideMdat,
                    firstInvalidOffset = track.chunkTable.firstInvalidOffset,
                    allSampleExtentsInsideFile = track.sampleLayout.allExtentsInsideFile,
                    allSampleExtentsInsideMdat = track.sampleLayout.allExtentsInsideMdat,
                    firstInvalidExtentStart = track.sampleLayout.firstInvalidExtentStart,
                    firstInvalidExtentEndExclusive = track.sampleLayout.firstInvalidExtentEndExclusive
                ),
                measuredVideoBitrateBps = measuredBitrate(
                    track.sampleSizes.totalBytes,
                    track.timeToSample.durationUnits,
                    track.mediaHeader.timescale
                )
            )
        }

        private fun verify(video: QuickTimeMovVideoTrackEvidence): List<String> = buildList {
            if (video.sampleEntry !in requirements.allowedVideoSampleEntries) {
                add(
                    "Unsupported video sample entry ${video.sampleEntry}; allowed=" +
                        requirements.allowedVideoSampleEntries.sorted().joinToString()
                )
            }
            requirements.expectedVideoSampleEntry?.let { expected ->
                if (video.sampleEntry != expected) add("Expected sample entry $expected, found ${video.sampleEntry}")
            }
            if (video.sampleCount < requirements.minimumSampleCount) {
                add("Expected at least ${requirements.minimumSampleCount} video samples, found ${video.sampleCount}")
            }
            if (video.zeroSizedSampleCount != 0L) {
                add("Video stsz contains ${video.zeroSizedSampleCount} zero-sized samples")
            }
            if (!video.timingCoversEverySample) {
                add(
                    "stts covers ${video.sttsCoveredSampleCount} samples but stsz declares " +
                        video.sampleCount
                )
            }
            if (video.sttsDurationUnits != video.mediaDurationUnits) {
                add(
                    "stts duration ${video.sttsDurationUnits} does not match mdhd duration " +
                        video.mediaDurationUnits
                )
            }
            if (video.sampleCount > 0L && video.chunkOffsets.entryCount == 0L) {
                add("Video samples exist but the chunk-offset table is empty")
            }
            if (video.sampleToChunk.mappedChunkCount != video.chunkOffsets.entryCount) {
                add(
                    "stsc maps ${video.sampleToChunk.mappedChunkCount} chunks but " +
                        "${video.chunkOffsets.tableType} declares ${video.chunkOffsets.entryCount}"
                )
            }
            if (video.sampleToChunk.mappedSampleCount != video.sampleCount) {
                add(
                    "stsc maps ${video.sampleToChunk.mappedSampleCount} samples but stsz declares " +
                        video.sampleCount
                )
            }
            if (!video.chunkOffsets.allOffsetsInsideFile) {
                add("A video chunk offset is outside the file: ${video.chunkOffsets.firstInvalidOffset}")
            }
            if (requirements.requireChunkOffsetsInsideMdat && !video.chunkOffsets.allOffsetsInsideMdat) {
                add("A video chunk offset is outside every mdat payload: ${video.chunkOffsets.firstInvalidOffset}")
            }
            if (!video.chunkOffsets.allSampleExtentsInsideFile) {
                add(
                    "A video sample extent is outside the file: " +
                        "[${video.chunkOffsets.firstInvalidExtentStart}, " +
                        "${video.chunkOffsets.firstInvalidExtentEndExclusive ?: "overflow"})"
                )
            }
            if (requirements.requireChunkOffsetsInsideMdat && !video.chunkOffsets.allSampleExtentsInsideMdat) {
                add(
                    "A video sample extent is outside every mdat payload: " +
                        "[${video.chunkOffsets.firstInvalidExtentStart}, " +
                        "${video.chunkOffsets.firstInvalidExtentEndExclusive ?: "overflow"})"
                )
            }
            if (abs(video.tkhdWidth - video.sampleEntryWidth.toDouble()) > FIXED_GEOMETRY_TOLERANCE ||
                abs(video.tkhdHeight - video.sampleEntryHeight.toDouble()) > FIXED_GEOMETRY_TOLERANCE
            ) {
                add(
                    "tkhd geometry ${video.tkhdWidth}x${video.tkhdHeight} disagrees with " +
                        "${video.sampleEntry} geometry ${video.sampleEntryWidth}x${video.sampleEntryHeight}"
                )
            }
            requirements.expectedWidth?.let { expected ->
                if (video.sampleEntryWidth != expected) {
                    add("Expected video width $expected, found ${video.sampleEntryWidth}")
                }
            }
            requirements.expectedHeight?.let { expected ->
                if (video.sampleEntryHeight != expected) {
                    add("Expected video height $expected, found ${video.sampleEntryHeight}")
                }
            }
            requirements.expectedRotationDegrees?.let { expected ->
                val normalized = QuickTimeMovArtifactRequirements.normalizeRotation(expected)
                if (video.rotationDegrees != normalized) {
                    add("Expected rotation $normalized, found ${video.rotationDegrees ?: "unrecognized"}")
                }
            }
            if (requirements.requireNclxColor && video.nclxColor == null) {
                add("Video sample entry has no nclx color description")
            }
            requirements.expectedColor?.let { expected ->
                val actual = video.nclxColor
                if (actual == null) {
                    add("Expected nclx color metadata but none was found")
                } else {
                    if (actual.colorPrimaries != expected.colorPrimaries) {
                        add("Expected nclx primaries ${expected.colorPrimaries}, found ${actual.colorPrimaries}")
                    }
                    if (actual.transferCharacteristics != expected.transferCharacteristics) {
                        add(
                            "Expected nclx transfer ${expected.transferCharacteristics}, found " +
                                actual.transferCharacteristics
                        )
                    }
                    if (actual.matrixCoefficients != expected.matrixCoefficients) {
                        add("Expected nclx matrix ${expected.matrixCoefficients}, found ${actual.matrixCoefficients}")
                    }
                    if (actual.fullRange != expected.fullRange) {
                        add("Expected nclx fullRange=${expected.fullRange}, found ${actual.fullRange}")
                    }
                }
            }
        }

        private fun readChildren(parent: BoxRef, context: String): List<BoxRef> =
            readBoxSequence(parent.payloadOffset, parent.end, context)

        private fun readBoxSequence(start: Long, end: Long, context: String): List<BoxRef> {
            parseRequire(start in 0..end && end <= fileBytes) { "$context has an invalid byte range" }
            val boxes = ArrayList<BoxRef>()
            var cursor = start
            while (cursor < end) {
                parseRequire(boxes.size < MAX_BOXES_PER_CONTAINER) {
                    "$context contains too many boxes"
                }
                parseRequire(end - cursor >= BASIC_BOX_HEADER_SIZE) {
                    "$context ends with a truncated box header at $cursor"
                }
                val size32 = readU32(cursor, end)
                val type = readFourCc(cursor + 4L, end)
                val headerSize: Int
                val size: Long
                when (size32) {
                    0L -> {
                        headerSize = BASIC_BOX_HEADER_SIZE.toInt()
                        size = end - cursor
                    }
                    1L -> {
                        parseRequire(end - cursor >= EXTENDED_BOX_HEADER_SIZE) {
                            "$context has a truncated 64-bit $type header at $cursor"
                        }
                        headerSize = EXTENDED_BOX_HEADER_SIZE.toInt()
                        size = readU64(cursor + 8L, end)
                    }
                    else -> {
                        headerSize = BASIC_BOX_HEADER_SIZE.toInt()
                        size = size32
                    }
                }
                parseRequire(size >= headerSize.toLong()) {
                    "$context box $type at $cursor is smaller than its header"
                }
                val boxEnd = try {
                    Math.addExact(cursor, size)
                } catch (_: ArithmeticException) {
                    parseFail("$context box $type at $cursor overflows its byte range")
                }
                parseRequire(boxEnd <= end) {
                    "$context box $type at $cursor is truncated: end=$boxEnd limit=$end"
                }
                boxes += BoxRef(type, cursor, size, headerSize)
                cursor = boxEnd
            }
            parseRequire(cursor == end) { "$context box coverage is incomplete" }
            return boxes
        }

        private fun requireFullBoxVersion(box: BoxRef, expected: Int) {
            parseRequire(box.payloadSize >= 4L) { "${box.type} full-box header is truncated" }
            val actual = readU8(box.payloadOffset, box.end)
            parseRequire(actual == expected) { "Unsupported ${box.type} version $actual" }
        }

        private fun readU8(offset: Long, limit: Long): Int {
            requireRange(offset, 1L, limit)
            input.seek(offset)
            return input.readUnsignedByte()
        }

        private fun readU16(offset: Long, limit: Long): Int {
            requireRange(offset, 2L, limit)
            input.seek(offset)
            return input.readUnsignedShort()
        }

        private fun readI32(offset: Long, limit: Long): Int {
            requireRange(offset, 4L, limit)
            input.seek(offset)
            return input.readInt()
        }

        private fun readU32(offset: Long, limit: Long): Long = readI32(offset, limit).toLong() and UINT32_MASK

        private fun readU64(offset: Long, limit: Long): Long {
            requireRange(offset, 8L, limit)
            input.seek(offset)
            val value = input.readLong()
            parseRequire(value >= 0L) { "Unsigned 64-bit value at $offset exceeds the supported file range" }
            return value
        }

        private fun readFourCc(offset: Long, limit: Long): String {
            requireRange(offset, 4L, limit)
            input.seek(offset)
            val bytes = ByteArray(4)
            input.readFully(bytes)
            return String(bytes, Charsets.US_ASCII)
        }

        private fun requireRange(offset: Long, byteCount: Long, limit: Long) {
            parseRequire(offset >= 0L && byteCount >= 0L && offset <= limit - byteCount) {
                "Read at $offset ($byteCount bytes) exceeds box/file limit $limit"
            }
        }
    }

    private data class BoxRef(
        val type: String,
        val offset: Long,
        val size: Long,
        val headerSize: Int
    ) {
        val payloadOffset: Long get() = offset + headerSize
        val payloadSize: Long get() = size - headerSize
        val end: Long get() = offset + size
    }

    private data class FileTypeEvidence(val majorBrand: String, val compatibleBrands: List<String>)
    private data class TrackHeaderEvidence(
        val trackId: Long,
        val width: Double,
        val height: Double,
        val rotationDegrees: Int?,
        val rotationMatrixRecognized: Boolean
    )
    private data class MediaHeaderEvidence(val timescale: Long, val duration: Long)
    private data class SampleDescriptionEvidence(
        val sampleEntry: String,
        val width: Int,
        val height: Int,
        val color: QuickTimeMovNclxEvidence?,
        val hevcConfiguration: QuickTimeMovHevcConfigurationEvidence?
    )
    private data class SampleSizeEvidence(
        val sampleCount: Long,
        val zeroSizedSampleCount: Long,
        val totalBytes: Long,
        val fixedSampleSize: Long,
        val explicitSizesOffset: Long?,
        val tableEnd: Long
    )
    private data class TimeToSampleEvidence(
        val entryCount: Long,
        val coveredSamples: Long,
        val durationUnits: Long
    )
    private data class SampleToChunkEntry(
        val firstChunk: Long,
        val samplesPerChunk: Long,
        val sampleDescriptionIndex: Long
    )
    private data class SampleToChunkTable(
        val box: BoxRef,
        val entryCount: Long
    )
    private data class ChunkTableEvidence(
        val type: String,
        val entryCount: Long,
        val minimumOffset: Long?,
        val maximumOffset: Long?,
        val allOffsetsInsideFile: Boolean,
        val allOffsetsInsideMdat: Boolean,
        val firstInvalidOffset: Long?,
        val box: BoxRef,
        val entryBytes: Long
    )
    private data class SampleLayoutEvidence(
        val mappedChunkCount: Long,
        val mappedSampleCount: Long,
        val allExtentsInsideFile: Boolean,
        val allExtentsInsideMdat: Boolean,
        val firstInvalidExtentStart: Long?,
        val firstInvalidExtentEndExclusive: Long?
    )
    private data class ParsedTrack(
        val trackHeader: TrackHeaderEvidence,
        val mediaHeader: MediaHeaderEvidence,
        val sampleDescription: SampleDescriptionEvidence,
        val sampleSizes: SampleSizeEvidence,
        val timeToSample: TimeToSampleEvidence,
        val sampleToChunk: SampleToChunkTable,
        val chunkTable: ChunkTableEvidence,
        val sampleLayout: SampleLayoutEvidence
    )

    private fun List<BoxRef>.filterType(type: String): List<BoxRef> = filter { it.type == type }

    private fun List<BoxRef>.singleRequired(type: String, context: String): BoxRef {
        val matches = filterType(type)
        parseRequire(matches.size == 1) { "$context must contain exactly one $type box" }
        return matches.single()
    }

    private fun recognizeRotation(matrix: IntArray): Int? {
        val a = matrix[0]
        val b = matrix[1]
        val c = matrix[3]
        val d = matrix[4]
        return when {
            near(a, FIXED_ONE) && near(b, 0) && near(c, 0) && near(d, FIXED_ONE) -> 0
            near(a, 0) && near(b, FIXED_ONE) && near(c, -FIXED_ONE) && near(d, 0) -> 90
            near(a, -FIXED_ONE) && near(b, 0) && near(c, 0) && near(d, -FIXED_ONE) -> 180
            near(a, 0) && near(b, -FIXED_ONE) && near(c, FIXED_ONE) && near(d, 0) -> 270
            else -> null
        }
    }

    private fun near(actual: Int, expected: Int): Boolean = abs(actual.toLong() - expected.toLong()) <= 1L

    private fun measuredBitrate(bytes: Long, durationUnits: Long, timescale: Long): Long? {
        if (bytes < 0L || durationUnits <= 0L || timescale <= 0L) return null
        val value = bytes.toDouble() * 8.0 * timescale.toDouble() / durationUnits.toDouble()
        return if (value.isFinite() && value >= 0.0 && value <= Long.MAX_VALUE.toDouble()) {
            value.roundToLong()
        } else {
            null
        }
    }

    private inline fun repeatLong(count: Long, block: (Long) -> Unit) {
        var index = 0L
        while (index < count) {
            block(index)
            index += 1L
        }
    }

    private inline fun parseRequire(condition: Boolean, lazyMessage: () -> String) {
        if (!condition) throw MovArtifactParseException(lazyMessage())
    }

    private fun parseFail(message: String): Nothing = throw MovArtifactParseException(message)

    private class MovArtifactParseException(message: String) : IllegalArgumentException(message)

    private const val BASIC_BOX_HEADER_SIZE = 8L
    private const val EXTENDED_BOX_HEADER_SIZE = 16L
    private const val VISUAL_SAMPLE_ENTRY_HEADER_SIZE = 78L
    private const val HEVC_CONFIGURATION_HEADER_SIZE = 23L
    private const val HEVC_NAL_HEADER_SIZE = 2
    private const val HEVC_MAIN10_PROFILE_IDC = 2
    private const val HEVC_MAIN10_CHROMA_FORMAT_IDC = 1
    private const val HEVC_MAIN10_BIT_DEPTH = 10
    private const val MOV_NAL_LENGTH_FIELD_BYTES = 4
    private const val HEVC_NAL_TYPE_VPS = 32
    private const val HEVC_NAL_TYPE_SPS = 33
    private const val HEVC_NAL_TYPE_PPS = 34
    private const val MAX_HEVC_CONFIGURATION_NAL_UNITS = 100_000L
    private const val STSC_ENTRY_SIZE = 12L
    private const val UINT32_MASK = 0xFFFF_FFFFL
    private const val FIXED_ONE = 0x0001_0000
    private const val FIXED_16_16_SCALE = 65_536.0
    private const val FIXED_GEOMETRY_TOLERANCE = 1.0 / FIXED_16_16_SCALE
    private const val MAX_BOXES_PER_CONTAINER = 100_000
    private const val MAX_BRANDS = 1_024L
    private const val MAX_TABLE_ENTRIES = 50_000_000L
}
