package com.neoproc.neoproccamx.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * Read-only HEVC/Main10 capability and configure/start probe for physical-device evidence.
 *
 * The probe never queues a frame and never treats a successful out-of-range configure call as
 * proof that a requested bitrate is supported. Advertised ranges remain the capability authority;
 * configure/start outcomes are recorded separately to expose vendor codec behavior.
 */
@RunWith(AndroidJUnit4::class)
class HevcMain10CodecCapabilityProbeInstrumentedTest {
    @Test
    fun enumerateAndConfigureMain10P010EncodersAtProductionBitrates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val evidenceDir = requireNotNull(context.getExternalFilesDir(EVIDENCE_DIRECTORY)) {
            "External evidence directory is unavailable"
        }.apply { mkdirs() }
        val jsonFile = File(evidenceDir, JSON_FILE_NAME)
        val textFile = File(evidenceDir, TEXT_FILE_NAME)

        val root = JSONObject()
            .put("schemaVersion", 1)
            .put("generatedAtUtc", Instant.now().toString())
            .put("probeContract", JSONObject()
                .put("mime", MIME_HEVC)
                .put("width", WIDTH)
                .put("height", HEIGHT)
                .put("frameRate", FRAME_RATE)
                .put("profile", "HEVCProfileMain10")
                .put("colorFormat", "COLOR_FormatYUVP010")
                .put("framesQueued", 0)
                .put("bitrateTargets", JSONArray(BITRATE_TARGETS.toList()))
                .put("bitrateTargetLabels", JSONArray(BITRATE_TARGET_LABELS.toList())))
            .put("device", deviceJson())

        val codecReports = JSONArray()
        val attempts = JSONArray()
        val main10P010Candidates = mutableListOf<CodecCandidate>()
        val enumerationFailures = JSONArray()

        val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any(::isHevcMime) }
            .sortedBy { it.name }

        codecInfos.forEach { info ->
            val report = JSONObject()
                .put("name", info.name)
                .put("canonicalName", info.canonicalName)
                .put("isAlias", info.isAlias)
                .put("isHardwareAccelerated", info.isHardwareAccelerated)
                .put("isSoftwareOnly", info.isSoftwareOnly)
                .put("isVendor", info.isVendor)
                .put("supportedTypes", JSONArray(info.supportedTypes.toList()))
            try {
                val mime = info.supportedTypes.first(::isHevcMime)
                val capabilities = info.getCapabilitiesForType(mime)
                val video = requireNotNull(capabilities.videoCapabilities) {
                    "HEVC encoder ${info.name} did not expose video capabilities"
                }
                val encoder = requireNotNull(capabilities.encoderCapabilities) {
                    "HEVC encoder ${info.name} did not expose encoder capabilities"
                }
                val profiles = capabilities.profileLevels.map { it.profile }.toSet()
                val hasExactMain10 = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 in profiles
                val hasMain10Family = profiles.any(::isMain10FamilyProfile)
                val hasP010 = capabilities.colorFormats.contains(
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
                )
                val sizeSupported = safelyBoolean {
                    video.isSizeSupported(WIDTH, HEIGHT)
                }
                val sizeAndRateSupported = safelyBoolean {
                    video.areSizeAndRateSupported(WIDTH, HEIGHT, FRAME_RATE.toDouble())
                }
                val supportsCbr = encoder.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
                val supportsVbr = encoder.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
                val supportsCq = encoder.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                )
                val rateControlled = supportsCbr || supportsVbr
                val bitrateUpper = video.bitrateRange.upper

                report
                    .put("maxSupportedInstances", capabilities.maxSupportedInstances)
                    .put("profileLevels", JSONArray().apply {
                        capabilities.profileLevels
                            .sortedWith(compareBy({ it.profile }, { it.level }))
                            .forEach { profileLevel ->
                                put(JSONObject()
                                    .put("profile", profileLevel.profile)
                                    .put("profileHex", hex(profileLevel.profile))
                                    .put("profileName", hevcProfileName(profileLevel.profile))
                                    .put("level", profileLevel.level)
                                    .put("levelHex", hex(profileLevel.level)))
                            }
                    })
                    .put("main10Advertised", hasExactMain10)
                    .put("main10FamilyAdvertised", hasMain10Family)
                    .put("colorFormats", JSONArray().apply {
                        capabilities.colorFormats.sorted().forEach { colorFormat ->
                            put(JSONObject()
                                .put("value", colorFormat)
                                .put("hex", hex(colorFormat))
                                .put("name", colorFormatName(colorFormat)))
                        }
                    })
                    .put("p010Advertised", hasP010)
                    .put("geometryAndRate", JSONObject()
                        .put("supportedWidths", rangeJson(video.supportedWidths.lower, video.supportedWidths.upper))
                        .put("supportedHeights", rangeJson(video.supportedHeights.lower, video.supportedHeights.upper))
                        .put("widthAlignment", video.widthAlignment)
                        .put("heightAlignment", video.heightAlignment)
                        .put("supportedFrameRates", rangeJson(
                            video.supportedFrameRates.lower,
                            video.supportedFrameRates.upper
                        ))
                        .put("size3840x2160Supported", sizeSupported.value)
                        .put("size3840x2160QueryError", sizeSupported.error ?: JSONObject.NULL)
                        .put("sizeAndRate3840x2160At30Supported", sizeAndRateSupported.value)
                        .put("sizeAndRate3840x2160At30QueryError", sizeAndRateSupported.error ?: JSONObject.NULL)
                        .put("frameRatesAt3840x2160", supportedFrameRatesAt(video))
                        .put("performancePoints", performancePointsJson(video)))
                    .put("bitrateRange", rangeJson(video.bitrateRange.lower, bitrateUpper))
                    .put("bitrateModes", JSONObject()
                        .put("CBR", supportsCbr)
                        .put("VBR", supportsVbr)
                        .put("CQ", supportsCq)
                        .put("qualityRange", rangeJson(
                            encoder.qualityRange.lower,
                            encoder.qualityRange.upper
                        ))
                        .put("complexityRange", rangeJson(
                            encoder.complexityRange.lower,
                            encoder.complexityRange.upper
                        )))
                    .put("advertisedAdmission", JSONObject()
                        .put("main10AndP010", hasExactMain10 && hasP010)
                        .put("fourK30", sizeAndRateSupported.value == true)
                        .put("rateControlledMode", rateControlled)
                        .put("at160000000", rateControlled && bitrateUpper >= BITRATE_ADVERTISED_REFERENCE)
                        .put("at200000000", rateControlled && bitrateUpper >= BITRATE_STRICT_FLOOR)
                        .put("at335923200", rateControlled && bitrateUpper >= BITRATE_COMPUTED_REQUIREMENT))

                if (hasExactMain10 && hasP010) {
                    main10P010Candidates += CodecCandidate(
                        info = info,
                        capabilities = capabilities,
                        bitrateUpper = bitrateUpper,
                        supportsFourK30 = sizeAndRateSupported.value == true,
                        supportsCbr = supportsCbr,
                        supportsVbr = supportsVbr,
                        supportsCq = supportsCq
                    )
                }
            } catch (throwable: Throwable) {
                val failure = throwableJson(throwable)
                report.put("capabilityReadFailure", failure)
                enumerationFailures.put(JSONObject()
                    .put("codecName", info.name)
                    .put("failure", failure))
            }
            codecReports.put(report)
        }

        main10P010Candidates.forEach { candidate ->
            val supportedModes = listOf(
                BitrateMode(
                    "CBR",
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                    candidate.supportsCbr,
                    true
                ),
                BitrateMode(
                    "VBR",
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                    candidate.supportsVbr,
                    true
                ),
                BitrateMode(
                    "CQ",
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                    candidate.supportsCq,
                    false
                )
            ).filter { it.supported }

            supportedModes.forEach { mode ->
                BITRATE_TARGETS.forEachIndexed { index, bitrate ->
                    val attempt = configureAndStart(
                        candidate = candidate,
                        mode = mode,
                        bitrate = bitrate,
                        bitrateLabel = BITRATE_TARGET_LABELS[index]
                    )
                    attempts.put(attempt)
                    Log.i(TAG, "ATTEMPT ${attempt.toString()}")
                }
            }
        }

        root
            .put("hevcEncoderCount", codecInfos.size)
            .put("main10P010CandidateCount", main10P010Candidates.size)
            .put("hevcEncoders", codecReports)
            .put("enumerationFailures", enumerationFailures)
            .put("configureStartAttempts", attempts)
            .put("alternateEncoderAssessment", alternateAssessment(main10P010Candidates))

        val prettyJson = root.toString(2) + "\n"
        jsonFile.writeText(prettyJson, Charsets.UTF_8)
        textFile.writeText(humanReadableReport(root), Charsets.UTF_8)

        Log.i(TAG, "EVIDENCE_JSON=${jsonFile.absolutePath}")
        Log.i(TAG, "EVIDENCE_TEXT=${textFile.absolutePath}")
        assertTrue("JSON evidence was not written", jsonFile.isFile && jsonFile.length() > 0L)
        assertTrue("Text evidence was not written", textFile.isFile && textFile.length() > 0L)
    }

    private fun configureAndStart(
        candidate: CodecCandidate,
        mode: BitrateMode,
        bitrate: Int,
        bitrateLabel: String
    ): JSONObject {
        val format = MediaFormat.createVideoFormat(MIME_HEVC, WIDTH, HEIGHT).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
            )
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, mode.value)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val advertisedFormatSupport = safelyBoolean {
            candidate.capabilities.isFormatSupported(format)
        }
        val result = JSONObject()
            .put("codecName", candidate.info.name)
            .put("canonicalName", candidate.info.canonicalName)
            .put("isHardwareAccelerated", candidate.info.isHardwareAccelerated)
            .put("isSoftwareOnly", candidate.info.isSoftwareOnly)
            .put("bitrateMode", mode.name)
            .put("bitrateModeValue", mode.value)
            .put("bitrateContractApplicable", mode.rateControlled)
            .put("requestedBitrate", bitrate)
            .put("bitrateLabel", bitrateLabel)
            .put("advertisedBitrateUpper", candidate.bitrateUpper)
            .put("requestedWithinAdvertisedBitrateRange", bitrate <= candidate.bitrateUpper)
            .put("advertisedFourK30", candidate.supportsFourK30)
            .put("advertisedFormatSupported", advertisedFormatSupport.value)
            .put("advertisedFormatSupportQueryError", advertisedFormatSupport.error ?: JSONObject.NULL)
            .put("framesQueued", 0)
            .put("configureSucceeded", false)
            .put("startSucceeded", false)
            .put("configureAndStartSucceeded", false)
            .put("qualifiesAsBitrateCapabilityEvidence", false)

        var codec: MediaCodec? = null
        var started = false
        try {
            codec = MediaCodec.createByCodecName(candidate.info.name)
            result.put("createSucceeded", true)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                result.put("configureSucceeded", true)
            } catch (throwable: Throwable) {
                result
                    .put("failureStage", "configure")
                    .put("failure", throwableJson(throwable))
                return result
            }
            try {
                codec.start()
                started = true
                result
                    .put("startSucceeded", true)
                    .put("configureAndStartSucceeded", true)
                    .put("configuredInputFormat", safelyFormatString { codec.inputFormat })
                    .put(
                        "qualifiesAsBitrateCapabilityEvidence",
                        mode.rateControlled &&
                            bitrate <= candidate.bitrateUpper &&
                            candidate.supportsFourK30 &&
                            advertisedFormatSupport.value == true
                    )
            } catch (throwable: Throwable) {
                result
                    .put("failureStage", "start")
                    .put("failure", throwableJson(throwable))
            }
        } catch (throwable: Throwable) {
            result
                .put("createSucceeded", false)
                .put("failureStage", "create")
                .put("failure", throwableJson(throwable))
        } finally {
            if (started) {
                try {
                    codec?.stop()
                    result.put("stopSucceeded", true)
                } catch (throwable: Throwable) {
                    result
                        .put("stopSucceeded", false)
                        .put("stopFailure", throwableJson(throwable))
                }
            } else {
                result.put("stopSucceeded", JSONObject.NULL)
            }
            try {
                codec?.release()
                result.put("releaseSucceeded", codec != null)
            } catch (throwable: Throwable) {
                result
                    .put("releaseSucceeded", false)
                    .put("releaseFailure", throwableJson(throwable))
            }
        }
        return result
    }

    private fun alternateAssessment(candidates: List<CodecCandidate>): JSONObject {
        fun admittedAt(bitrate: Int): List<CodecCandidate> = candidates.filter { candidate ->
            candidate.supportsFourK30 &&
                (candidate.supportsCbr || candidate.supportsVbr) &&
                candidate.bitrateUpper >= bitrate
        }
        fun names(candidatesAtRate: List<CodecCandidate>): JSONArray = JSONArray().apply {
            candidatesAtRate.forEach { candidate ->
                put(JSONObject()
                    .put("name", candidate.info.name)
                    .put("isHardwareAccelerated", candidate.info.isHardwareAccelerated)
                    .put("isSoftwareOnly", candidate.info.isSoftwareOnly)
                    .put("advertisedBitrateUpper", candidate.bitrateUpper))
            }
        }

        val at160 = admittedAt(BITRATE_ADVERTISED_REFERENCE)
        val at200 = admittedAt(BITRATE_STRICT_FLOOR)
        val atComputed = admittedAt(BITRATE_COMPUTED_REQUIREMENT)
        return JSONObject()
            .put("admissionRule", "Main10 + P010 + 3840x2160@30 + CBR/VBR + advertised bitrate upper bound")
            .put("cqExcludedFromBitrateAdmission", true)
            .put("eligibleAt160000000", names(at160))
            .put("eligibleAt200000000", names(at200))
            .put("eligibleAt335923200", names(atComputed))
            .put("anyHardwareOrSoftwareEncoderMeetsStrict200MbpsFloor", at200.isNotEmpty())
            .put("anyHardwareOrSoftwareEncoderMeetsComputed335923200Requirement", atComputed.isNotEmpty())
            .put("conclusion", when {
                atComputed.isNotEmpty() -> "At least one advertised encoder meets the computed requirement."
                at200.isNotEmpty() -> "No advertised encoder meets the computed requirement; at least one meets the strict 200 Mbps floor."
                else -> "No advertised hardware or software HEVC encoder meets the strict 200 Mbps floor at 3840x2160@30 Main10/P010."
            })
    }

    private fun humanReadableReport(root: JSONObject): String = buildString {
        appendLine("NeoProCamX HEVC Main10/P010 physical-device capability probe")
        appendLine("Generated UTC: ${root.getString("generatedAtUtc")}")
        val device = root.getJSONObject("device")
        appendLine("Device: ${device.getString("manufacturer")} ${device.getString("model")}")
        appendLine("Build: ${device.getString("fingerprint")}")
        appendLine("Contract: 3840x2160 @ 30 fps, HEVC Main10, P010, zero frames queued")
        appendLine("Targets: 160000000, 200000000, 335923200 bit/s")
        appendLine()

        val encoders = root.getJSONArray("hevcEncoders")
        appendLine("HEVC encoders (${encoders.length()}):")
        for (index in 0 until encoders.length()) {
            val codec = encoders.getJSONObject(index)
            appendLine("- ${codec.getString("name")}")
            if (codec.has("capabilityReadFailure")) {
                appendLine("  capabilityReadFailure=${codec.getJSONObject("capabilityReadFailure")}")
                continue
            }
            val geometry = codec.getJSONObject("geometryAndRate")
            val bitrateModes = codec.getJSONObject("bitrateModes")
            appendLine(
                "  canonical=${codec.getString("canonicalName")} alias=${codec.getBoolean("isAlias")} " +
                    "hardware=${codec.getBoolean("isHardwareAccelerated")} " +
                    "software=${codec.getBoolean("isSoftwareOnly")} vendor=${codec.getBoolean("isVendor")}"
            )
            appendLine(
                    "  Main10=${codec.getBoolean("main10Advertised")} " +
                    "Main10Family=${codec.getBoolean("main10FamilyAdvertised")} " +
                    "P010=${codec.getBoolean("p010Advertised")} " +
                    "4K30=${geometry.opt("sizeAndRate3840x2160At30Supported")}"
            )
            appendLine(
                "  widths=${geometry.getJSONObject("supportedWidths").getString("text")} " +
                    "heights=${geometry.getJSONObject("supportedHeights").getString("text")} " +
                    "fps=${geometry.getJSONObject("supportedFrameRates").getString("text")}"
            )
            appendLine(
                "  bitrate=${codec.getJSONObject("bitrateRange").getString("text")} " +
                    "CBR=${bitrateModes.getBoolean("CBR")} " +
                    "VBR=${bitrateModes.getBoolean("VBR")} CQ=${bitrateModes.getBoolean("CQ")}"
            )
        }
        appendLine()

        val attempts = root.getJSONArray("configureStartAttempts")
        appendLine("Configure/start attempts (${attempts.length()}), no frames queued:")
        for (index in 0 until attempts.length()) {
            val attempt = attempts.getJSONObject(index)
            appendLine(
                "- codec=${attempt.getString("codecName")} mode=${attempt.getString("bitrateMode")} " +
                    "bitrate=${attempt.getInt("requestedBitrate")} " +
                    "advertisedFormatSupported=${attempt.opt("advertisedFormatSupported")} " +
                    "configure=${attempt.getBoolean("configureSucceeded")} " +
                    "start=${attempt.getBoolean("startSucceeded")} " +
                    "qualifyingEvidence=${attempt.getBoolean("qualifiesAsBitrateCapabilityEvidence")}"
            )
            if (attempt.has("failure")) {
                val failure = attempt.getJSONObject("failure")
                appendLine(
                    "  failureStage=${attempt.getString("failureStage")} " +
                        "type=${failure.getString("type")} message=${failure.optString("message")} " +
                        "diagnosticInfo=${failure.optString("diagnosticInfo")}"
                )
            }
        }
        appendLine()
        appendLine("Alternate encoder assessment:")
        appendLine(root.getJSONObject("alternateEncoderAssessment").toString(2))
        appendLine()
        appendLine("Machine-readable report: $JSON_FILE_NAME")
    }

    private fun deviceJson(): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("brand", Build.BRAND)
        .put("model", Build.MODEL)
        .put("device", Build.DEVICE)
        .put("product", Build.PRODUCT)
        .put("hardware", Build.HARDWARE)
        .put("board", Build.BOARD)
        .put("fingerprint", Build.FINGERPRINT)
        .put("sdkInt", Build.VERSION.SDK_INT)
        .put("release", Build.VERSION.RELEASE)
        .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))

    private fun supportedFrameRatesAt(
        video: MediaCodecInfo.VideoCapabilities
    ): Any = try {
        val range = video.getSupportedFrameRatesFor(WIDTH, HEIGHT)
        rangeJson(range.lower, range.upper)
    } catch (throwable: Throwable) {
        JSONObject()
            .put("error", throwableJson(throwable))
    }

    private fun performancePointsJson(
        video: MediaCodecInfo.VideoCapabilities
    ): JSONArray = JSONArray().apply {
        val points = runCatching { video.supportedPerformancePoints }
            .getOrNull()
            .orEmpty()
        points.forEach { point -> put(point.toString()) }
    }

    private fun safelyFormatString(block: () -> MediaFormat): Any = try {
        block().toString()
    } catch (throwable: Throwable) {
        JSONObject().put("readFailure", throwableJson(throwable))
    }

    private fun safelyBoolean(block: () -> Boolean): BooleanQuery = try {
        BooleanQuery(value = block(), error = null)
    } catch (throwable: Throwable) {
        BooleanQuery(
            value = null,
            error = "${throwable.javaClass.name}: ${throwable.message.orEmpty()}"
        )
    }

    private fun throwableJson(throwable: Throwable): JSONObject = JSONObject()
        .put("type", throwable.javaClass.name)
        .put("message", throwable.message ?: JSONObject.NULL)
        .put("stackTrace", Log.getStackTraceString(throwable))
        .apply {
            if (throwable is MediaCodec.CodecException) {
                put("errorCode", throwable.errorCode)
                put("diagnosticInfo", throwable.diagnosticInfo)
                put("isRecoverable", throwable.isRecoverable)
                put("isTransient", throwable.isTransient)
            }
        }

    private fun rangeJson(lower: Number, upper: Number): JSONObject = JSONObject()
        .put("lower", lower)
        .put("upper", upper)
        .put("text", "$lower..$upper")

    private fun isHevcMime(type: String): Boolean = type.equals(MIME_HEVC, ignoreCase = true)

    private fun isMain10FamilyProfile(profile: Int): Boolean = profile ==
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
        profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
        profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus

    private fun hevcProfileName(profile: Int): String = when (profile) {
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "HEVCProfileMain"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "HEVCProfileMain10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill -> "HEVCProfileMainStill"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "HEVCProfileMain10HDR10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "HEVCProfileMain10HDR10Plus"
        else -> "UNKNOWN"
    }

    @Suppress("DEPRECATION")
    private fun colorFormatName(colorFormat: Int): String = when (colorFormat) {
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 -> "COLOR_FormatYUVP010"
        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface -> "COLOR_FormatSurface"
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> "COLOR_FormatYUV420Flexible"
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> "COLOR_FormatYUV420Planar"
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> "COLOR_FormatYUV420SemiPlanar"
        else -> "UNKNOWN"
    }

    private fun hex(value: Int): String = "0x%08X".format(value)

    private data class CodecCandidate(
        val info: MediaCodecInfo,
        val capabilities: MediaCodecInfo.CodecCapabilities,
        val bitrateUpper: Int,
        val supportsFourK30: Boolean,
        val supportsCbr: Boolean,
        val supportsVbr: Boolean,
        val supportsCq: Boolean
    )

    private data class BitrateMode(
        val name: String,
        val value: Int,
        val supported: Boolean,
        val rateControlled: Boolean
    )

    private data class BooleanQuery(
        val value: Boolean?,
        val error: String?
    )

    private companion object {
        const val TAG = "NeoHevcCapabilityProbe"
        const val MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
        const val WIDTH = 3840
        const val HEIGHT = 2160
        const val FRAME_RATE = 30
        const val BITRATE_ADVERTISED_REFERENCE = 160_000_000
        const val BITRATE_STRICT_FLOOR = 200_000_000
        const val BITRATE_COMPUTED_REQUIREMENT = 335_923_200
        const val EVIDENCE_DIRECTORY = "device-production-qa-2026-08-15"
        const val JSON_FILE_NAME = "hevc-main10-p010-codec-capability-probe.json"
        const val TEXT_FILE_NAME = "hevc-main10-p010-codec-capability-probe.txt"
        val BITRATE_TARGETS = intArrayOf(
            BITRATE_ADVERTISED_REFERENCE,
            BITRATE_STRICT_FLOOR,
            BITRATE_COMPUTED_REQUIREMENT
        )
        val BITRATE_TARGET_LABELS = arrayOf(
            "advertisedCeilingReference",
            "strictQualityFloor",
            "computedQualityRequirement"
        )
    }
}
