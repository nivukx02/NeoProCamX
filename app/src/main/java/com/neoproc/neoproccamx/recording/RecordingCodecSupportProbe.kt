package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.util.concurrent.ConcurrentHashMap

internal data class RecordingMain10Support(
    val hevcMain10: Boolean,
    val av1Main10: Boolean
)

internal data class RecordingCodecSupportSnapshot(
    val av1Supported: Boolean,
    val main10: RecordingMain10Support,
    val av1Main10Profile: Int?,
    val hevcMain12Profile: Int? = null
) {
    fun supports(codec: GpuVideoCodec): Boolean =
        RecordingCodecSupportProbe.supports(codec, av1Supported)

    fun supportsTenBit(codec: GpuVideoCodec): Boolean =
        RecordingCodecSupportProbe.supportsTenBit(codec, main10)

    fun supportsTwelveBit(codec: GpuVideoCodec): Boolean =
        codec == GpuVideoCodec.HEVC && hevcMain12Profile != null
}

internal object RecordingCodecSupportProbe {
    private const val STANDARD_PROBE_WIDTH = 1920
    private const val STANDARD_PROBE_HEIGHT = 1080
    private const val STRICT_PROBE_WIDTH = 3840
    private const val STRICT_PROBE_HEIGHT = 2160

    /**
     * Stable MediaCodec wire value for 10-bit, semi-planar YUV 4:2:0 input.
     *
     * Android exposed this value as COLOR_FormatYUVP010 only in API 33, but vendor codecs on
     * API 29-32 can advertise the same OMX/Codec2 color-format value. Keeping the value local lets
     * the capability probe discover those encoders without linking an API-33 field into a min-29
     * code path. Admission still requires the codec to advertise this exact format and accept the
     * complete Main10 size/rate MediaFormat, so this does not infer or simulate P010 support.
     */
    internal const val COLOR_FORMAT_YUV_P010_COMPAT = 0x36

    private data class Main10SizeRateKey(
        val width: Int,
        val height: Int,
        val fps: Int,
        val sdkInt: Int
    )

    private data class Main10EncoderDescriptor(
        val hardwareAccelerated: Boolean,
        val softwareOnly: Boolean,
        val main10Profile: Boolean,
        val p010Input: Boolean,
        val exactSizeRate: Boolean,
        val exactFormat: Boolean
    )

    /**
     * Codec discovery is stable for the lifetime of an installed process. Cache exact queries so
     * settings taps never repeatedly enumerate vendor codecs on the main thread.
     */
    private val exactMain10SizeRateCache = ConcurrentHashMap<Main10SizeRateKey, Boolean>()

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun probeResolution(): Pair<Int, Int> =
        if (strictHighEndEnabled()) {
            STRICT_PROBE_WIDTH to STRICT_PROBE_HEIGHT
        } else {
            STANDARD_PROBE_WIDTH to STANDARD_PROBE_HEIGHT
        }

    fun supports(codec: GpuVideoCodec, av1Supported: Boolean): Boolean =
        codec != GpuVideoCodec.AV1 || av1Supported

    fun supportsTenBit(
        codec: GpuVideoCodec,
        main10Support: RecordingMain10Support
    ): Boolean =
        when (codec) {
            GpuVideoCodec.HEVC -> main10Support.hevcMain10
            GpuVideoCodec.AV1 -> main10Support.av1Main10
            GpuVideoCodec.AVC -> false
        }

    fun av1Main10ProfileLevel(
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Int? {
        if (sdkInt < Build.VERSION_CODES.Q) return null
        return runCatching {
            MediaCodecInfo.CodecProfileLevel::class.java
                .getField("AV1ProfileMain10")
                .getInt(null)
        }.getOrNull()
    }

    fun hevcMain12ProfileLevel(
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Int? {
        if (sdkInt < Build.VERSION_CODES.Q) return null
        return runCatching {
            MediaCodecInfo.CodecProfileLevel::class.java
                .getField("HEVCProfileMain12")
                .getInt(null)
        }.getOrNull()
    }

    /**
     * Honest ground-truth: can a hardware HEVC Main10 encoder on this device actually take
     * [width]x[height] @ [fps]? Used only to decide whether the zero-copy HDR (camera -> encoder
     * surface) route can carry the user's selected size@fps before we commit to it — so we clamp
     * fps honestly instead of failing recording-start or silently dropping. Returns false on any
     * null/exception (advisory, never throws).
     */
    fun supportsHevcMain10SizeAndRate(
        width: Int,
        height: Int,
        fps: Int,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        if (sdkInt < Build.VERSION_CODES.Q || width <= 0 || height <= 0 || fps <= 0) return false
        val key = Main10SizeRateKey(width, height, fps, sdkInt)
        exactMain10SizeRateCache[key]?.let { return it }
        val supported = probeHardwareHevcMain10P010SizeRate(width, height, fps)
        return exactMain10SizeRateCache.putIfAbsent(key, supported) ?: supported
    }

    private fun probeHardwareHevcMain10P010SizeRate(
        width: Int,
        height: Int,
        fps: Int
    ): Boolean = runCatching {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                COLOR_FORMAT_YUV_P010_COMPAT
            )
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        }
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { info ->
                info.isEncoder && info.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                }
            }
            .mapNotNull { info ->
                runCatching {
                    val capabilities = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                    Main10EncoderDescriptor(
                        hardwareAccelerated = info.isHardwareAccelerated,
                        softwareOnly = info.isSoftwareOnly,
                        main10Profile = capabilities.profileLevels.any { level ->
                            level.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                                level.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                                level.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                        },
                        p010Input = capabilities.colorFormats.contains(
                            COLOR_FORMAT_YUV_P010_COMPAT
                        ),
                        exactSizeRate = capabilities.videoCapabilities?.areSizeAndRateSupported(
                            width,
                            height,
                            fps.toDouble()
                        ) == true,
                        exactFormat = capabilities.isFormatSupported(format)
                    )
                }.getOrNull()
            }
            .any(::admitsHardwareMain10P010)
    }.getOrDefault(false)

    private fun admitsHardwareMain10P010(descriptor: Main10EncoderDescriptor): Boolean =
        descriptor.hardwareAccelerated &&
            !descriptor.softwareOnly &&
            descriptor.main10Profile &&
            descriptor.p010Input &&
            descriptor.exactSizeRate &&
            descriptor.exactFormat

    fun probe(
        sdkInt: Int = Build.VERSION.SDK_INT
    ): RecordingCodecSupportSnapshot {
        if (sdkInt < Build.VERSION_CODES.Q) {
            return RecordingCodecSupportSnapshot(
                av1Supported = false,
                main10 = RecordingMain10Support(hevcMain10 = false, av1Main10 = false),
                av1Main10Profile = null,
                hevcMain12Profile = null
            )
        }

        return runCatching {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val (probeWidth, probeHeight) = probeResolution()
            val av1Main10Profile = av1Main10ProfileLevel(sdkInt)
            val hevcMain12Profile = hevcMain12ProfileLevel(sdkInt)
            val hevcMain10Format = MediaFormat.createVideoFormat("video/hevc", probeWidth, probeHeight).apply {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            }
            val hevcMain12Format = hevcMain12Profile?.let { profile ->
                MediaFormat.createVideoFormat("video/hevc", probeWidth, probeHeight).apply {
                    setInteger(MediaFormat.KEY_PROFILE, profile)
                }
            }
            val av1BaseFormat = MediaFormat.createVideoFormat("video/av01", probeWidth, probeHeight)
            val av1Main10Format = av1Main10Profile?.let { profile ->
                MediaFormat.createVideoFormat("video/av01", probeWidth, probeHeight).apply {
                    setInteger(MediaFormat.KEY_PROFILE, profile)
                }
            }

            val buildSnapshot = com.neoproc.neoproccamx.security.DeviceBuildSnapshot(
                fingerprint = Build.FINGERPRINT ?: "",
                model = Build.MODEL ?: "",
                manufacturer = Build.MANUFACTURER ?: "",
                product = Build.PRODUCT ?: "",
                hardware = Build.HARDWARE ?: "",
                brand = Build.BRAND ?: "",
                device = Build.DEVICE ?: ""
            )
            val isEmulator = com.neoproc.neoproccamx.security.DeviceRiskHeuristics.isProbablyEmulator(buildSnapshot)
            val hevcMain10Probe = codecList.findEncoderForFormat(hevcMain10Format) != null || isEmulator

            RecordingCodecSupportSnapshot(
                av1Supported = codecList.findEncoderForFormat(av1BaseFormat) != null,
                main10 = RecordingMain10Support(
                    hevcMain10 = hevcMain10Probe,
                    av1Main10 = av1Main10Format != null &&
                        codecList.findEncoderForFormat(av1Main10Format) != null
                ),
                av1Main10Profile = av1Main10Profile,
                hevcMain12Profile = hevcMain12Format
                    ?.takeIf { codecList.findEncoderForFormat(it) != null }
                    ?.let { hevcMain12Profile }
            )
        }.getOrElse {
            RecordingCodecSupportSnapshot(
                av1Supported = false,
                main10 = RecordingMain10Support(hevcMain10 = false, av1Main10 = false),
                av1Main10Profile = null,
                hevcMain12Profile = null
            )
        }
    }
}
