package com.neoproc.neoproccamx.recording

/**
 * Plans a decoder-safe, zero-origin AAC prefix for NPV10 exports.
 *
 * Camera audio is deliberately opened before the video session, but the durable AAC timeline can
 * only be anchored after Camera2 publishes the first accepted video sensor timestamp. On real
 * devices that hand-off can leave the first retained AAC access unit several frame periods after
 * video PTS zero. Some hardware A/V renderers then advance the video clock to the delayed audio
 * origin and discard otherwise-valid startup pictures.
 *
 * The production repair is intentionally lossless for captured content: complete codec-valid
 * silence access units fill the interval before the first source packet, while every source AAC
 * payload, flag and PTS is copied unchanged. No video sample, audio sample, bitrate, bit depth,
 * colour tag or resolution is rewritten to hide a playback drop.
 */
internal object Npv10AacTimelineOriginPolicy {
    private const val AAC_LC_AUDIO_OBJECT_TYPE = 2
    private const val AAC_48_KHZ_FREQUENCY_INDEX = 3
    private const val AAC_STEREO_CHANNEL_CONFIGURATION = 2
    private const val MAX_REPAIRABLE_CAPTURE_GAP_US = 60_000_000L

    /*
     * MPEG-4 AAC-LC, 48 kHz, two-channel raw_data_block containing digital silence. This exact
     * six-byte access unit was independently decoded by FFmpeg and the iQOO I2405 hardware AAC
     * decoder during LQ-034 diagnosis. It has no ADTS header because `mp4a` samples carry raw AAC
     * access units and derive their AudioSpecificConfig from `esds`.
     */
    private val AAC_LC_48K_STEREO_SILENCE = byteArrayOf(
        0x21,
        0x10,
        0x04,
        0x60,
        0x8C.toByte(),
        0x1C
    )

    data class Plan(
        val sourceFirstPresentationTimeUs: Long,
        val packetPresentationTimesUs: List<Long>,
        val coveredDurationUs: Long,
        val residualGapUs: Long,
        val codecContract: String
    ) {
        val packetCount: Int get() = packetPresentationTimesUs.size
        val applied: Boolean get() = packetPresentationTimesUs.isNotEmpty()
        val outputFirstPresentationTimeUs: Long
            get() = packetPresentationTimesUs.firstOrNull() ?: sourceFirstPresentationTimeUs

        fun newSilenceAccessUnit(): ByteArray = AAC_LC_48K_STEREO_SILENCE.copyOf()
    }

    fun plan(
        firstSourcePresentationTimeUs: Long,
        sampleRate: Int,
        channelCount: Int,
        codecConfig: ByteArray
    ): Plan {
        require(firstSourcePresentationTimeUs >= 0L) {
            "NPV10 AAC source PTS must be non-negative"
        }
        if (firstSourcePresentationTimeUs == 0L) {
            return Plan(
                sourceFirstPresentationTimeUs = 0L,
                packetPresentationTimesUs = emptyList(),
                coveredDurationUs = 0L,
                residualGapUs = 0L,
                codecContract = "SOURCE_ALREADY_ZERO_ORIGIN"
            )
        }
        require(firstSourcePresentationTimeUs <= MAX_REPAIRABLE_CAPTURE_GAP_US) {
            "NPV10 AAC capture-start gap is implausibly large: ${firstSourcePresentationTimeUs}us"
        }
        requireAacLc48kStereo(sampleRate, channelCount, codecConfig)

        val availablePcmFrames = durationUsToCompletePcmFrames(
            durationUs = firstSourcePresentationTimeUs,
            sampleRate = sampleRate
        )
        val silencePacketCount = (availablePcmFrames / Npv10AudioSidecarPolicy.AAC_FRAME_SAMPLES)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val packetPts = ArrayList<Long>(silencePacketCount)
        repeat(silencePacketCount) { index ->
            packetPts += RecordingAudioSyncPolicy.durationUsForFrames(
                frameCount = index.toLong() * Npv10AudioSidecarPolicy.AAC_FRAME_SAMPLES,
                sampleRate = sampleRate
            )
        }
        val coveredDurationUs = RecordingAudioSyncPolicy.durationUsForFrames(
            frameCount = silencePacketCount.toLong() * Npv10AudioSidecarPolicy.AAC_FRAME_SAMPLES,
            sampleRate = sampleRate
        )
        check(coveredDurationUs <= firstSourcePresentationTimeUs) {
            "AAC silence prefix would overlap captured audio"
        }
        return Plan(
            sourceFirstPresentationTimeUs = firstSourcePresentationTimeUs,
            packetPresentationTimesUs = packetPts,
            coveredDurationUs = coveredDurationUs,
            residualGapUs = firstSourcePresentationTimeUs - coveredDurationUs,
            codecContract = "AAC_LC_48000_STEREO_RAW_ACCESS_UNIT"
        )
    }

    private fun requireAacLc48kStereo(
        sampleRate: Int,
        channelCount: Int,
        codecConfig: ByteArray
    ) {
        require(sampleRate == Npv10AudioSidecarPolicy.SAMPLE_RATE) {
            "AAC silence-prefix sample rate is unsupported: $sampleRate"
        }
        require(channelCount == Npv10AudioSidecarPolicy.CHANNEL_COUNT) {
            "AAC silence-prefix channel count is unsupported: $channelCount"
        }
        require(codecConfig.size >= 2) { "AAC AudioSpecificConfig is incomplete" }
        val first = codecConfig[0].toInt() and 0xFF
        val second = codecConfig[1].toInt() and 0xFF
        val audioObjectType = first ushr 3
        val frequencyIndex = ((first and 0x07) shl 1) or (second ushr 7)
        val channelConfiguration = (second ushr 3) and 0x0F
        require(
            audioObjectType == AAC_LC_AUDIO_OBJECT_TYPE &&
                frequencyIndex == AAC_48_KHZ_FREQUENCY_INDEX &&
                channelConfiguration == AAC_STEREO_CHANNEL_CONFIGURATION
        ) {
            "AAC silence-prefix requires AAC-LC 48 kHz stereo AudioSpecificConfig"
        }
    }

    private fun durationUsToCompletePcmFrames(durationUs: Long, sampleRate: Int): Long {
        val wholeSeconds = durationUs / 1_000_000L
        val remainingUs = durationUs % 1_000_000L
        val wholeFrames = Math.multiplyExact(wholeSeconds, sampleRate.toLong())
        val partialFrames = remainingUs * sampleRate.toLong() / 1_000_000L
        return Math.addExact(wholeFrames, partialFrames)
    }
}
