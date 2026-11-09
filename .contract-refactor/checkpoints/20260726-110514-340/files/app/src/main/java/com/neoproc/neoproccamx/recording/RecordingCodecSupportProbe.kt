package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

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
        return runCatching {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val format = MediaFormat.createVideoFormat("video/hevc", width, height).apply {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            }
            val encoderName = codecList.findEncoderForFormat(format) ?: return@runCatching false
            val videoCaps = codecList.codecInfos
                .firstOrNull { it.isEncoder && it.name == encoderName }
                ?.getCapabilitiesForType("video/hevc")
                ?.videoCapabilities ?: return@runCatching false
            videoCaps.areSizeAndRateSupported(width, height, fps.toDouble())
        }.getOrDefault(false)
    }

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
