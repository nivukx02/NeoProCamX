package com.neoproc.neoproccamx.recording

import java.io.ByteArrayOutputStream

internal data class HevcDecoderConfigurationRecordFields(
    val profileSpace: Int,
    val tierFlag: Boolean,
    val profileIdc: Int,
    val profileCompatibilityFlags: Long,
    val constraintIndicatorFlags: Long,
    val levelIdc: Int,
    val chromaFormatIdc: Int,
    val bitDepthLumaMinus8: Int,
    val bitDepthChromaMinus8: Int
) {
    val packedProfileByte: Int
        get() =
            ((profileSpace and 0x03) shl 6) or
                (if (tierFlag) 0x20 else 0) or
                (profileIdc and 0x1F)

    companion object {
        val DEFAULT: HevcDecoderConfigurationRecordFields =
            HevcDecoderConfigurationRecordFields(
                profileSpace = 0,
                tierFlag = false,
                profileIdc = 1,
                profileCompatibilityFlags = 0,
                constraintIndicatorFlags = 0,
                levelIdc = 120,
                chromaFormatIdc = 1,
                bitDepthLumaMinus8 = 0,
                bitDepthChromaMinus8 = 0
            )
    }
}

internal object HevcDecoderConfigurationRecordPolicy {
    private const val NAL_TYPE_SPS = 33

    fun fromAnnexBParameterSets(parameterSets: List<ByteArray>): HevcDecoderConfigurationRecordFields {
        val sps = parameterSets.firstOrNull { hevcNalType(it) == NAL_TYPE_SPS }
            ?: return HevcDecoderConfigurationRecordFields.DEFAULT
        return runCatching { parseSps(sps) }
            .getOrNull()
            ?: HevcDecoderConfigurationRecordFields.DEFAULT
    }

    private fun parseSps(nal: ByteArray): HevcDecoderConfigurationRecordFields? {
        val rbsp = nalToRbsp(nal) ?: return null
        val reader = BitReader(rbsp)
        reader.skipBits(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = reader.readBits(3)
        reader.skipBits(1) // sps_temporal_id_nesting_flag
        val profileTierLevel = readProfileTierLevel(reader, maxSubLayersMinus1)
        reader.readUnsignedExpGolomb() // sps_seq_parameter_set_id
        val chromaFormatIdc = reader.readUnsignedExpGolomb()
        if (chromaFormatIdc == 3) {
            reader.skipBits(1) // separate_colour_plane_flag
        }
        reader.readUnsignedExpGolomb() // pic_width_in_luma_samples
        reader.readUnsignedExpGolomb() // pic_height_in_luma_samples
        if (reader.readBit() == 1) {
            repeat(4) { reader.readUnsignedExpGolomb() }
        }
        val bitDepthLumaMinus8 = reader.readUnsignedExpGolomb()
        val bitDepthChromaMinus8 = reader.readUnsignedExpGolomb()

        return HevcDecoderConfigurationRecordFields(
            profileSpace = profileTierLevel.profileSpace.coerceIn(0, 3),
            tierFlag = profileTierLevel.tierFlag,
            profileIdc = profileTierLevel.profileIdc.coerceIn(0, 31),
            profileCompatibilityFlags = profileTierLevel.profileCompatibilityFlags and 0xFFFF_FFFFL,
            constraintIndicatorFlags = profileTierLevel.constraintIndicatorFlags and 0xFFFF_FFFF_FFFFL,
            levelIdc = profileTierLevel.levelIdc.coerceIn(0, 255),
            chromaFormatIdc = chromaFormatIdc.coerceIn(0, 3),
            bitDepthLumaMinus8 = bitDepthLumaMinus8.coerceIn(0, 7),
            bitDepthChromaMinus8 = bitDepthChromaMinus8.coerceIn(0, 7)
        )
    }

    private fun readProfileTierLevel(
        reader: BitReader,
        maxSubLayersMinus1: Int
    ): ProfileTierLevel {
        val profileSpace = reader.readBits(2)
        val tierFlag = reader.readBit() == 1
        val profileIdc = reader.readBits(5)
        val profileCompatibilityFlags = reader.readBitsLong(32)
        val constraintIndicatorFlags = reader.readBitsLong(48)
        val levelIdc = reader.readBits(8)
        val subLayerProfilePresent = BooleanArray(maxSubLayersMinus1)
        val subLayerLevelPresent = BooleanArray(maxSubLayersMinus1)
        for (index in 0 until maxSubLayersMinus1) {
            subLayerProfilePresent[index] = reader.readBit() == 1
            subLayerLevelPresent[index] = reader.readBit() == 1
        }
        if (maxSubLayersMinus1 > 0) {
            for (index in maxSubLayersMinus1 until 8) {
                reader.skipBits(2)
            }
        }
        for (index in 0 until maxSubLayersMinus1) {
            if (subLayerProfilePresent[index]) {
                reader.skipBits(2 + 1 + 5 + 32 + 48 + 8)
            }
            if (subLayerLevelPresent[index]) {
                reader.skipBits(8)
            }
        }
        return ProfileTierLevel(
            profileSpace = profileSpace,
            tierFlag = tierFlag,
            profileIdc = profileIdc,
            profileCompatibilityFlags = profileCompatibilityFlags,
            constraintIndicatorFlags = constraintIndicatorFlags,
            levelIdc = levelIdc
        )
    }

    private fun nalToRbsp(nal: ByteArray): ByteArray? {
        if (nal.size <= 2) return null
        val payload = nal.copyOfRange(2, nal.size)
        val out = ByteArrayOutputStream(payload.size)
        var index = 0
        while (index < payload.size) {
            if (
                index + 2 < payload.size &&
                payload[index] == 0.toByte() &&
                payload[index + 1] == 0.toByte() &&
                payload[index + 2] == 0x03.toByte()
            ) {
                out.write(0)
                out.write(0)
                index += 3
            } else {
                out.write(payload[index].toInt())
                index += 1
            }
        }
        return out.toByteArray()
    }

    private fun hevcNalType(nal: ByteArray): Int {
        if (nal.size < 2) return -1
        return (nal[0].toInt() ushr 1) and 0x3F
    }

    private data class ProfileTierLevel(
        val profileSpace: Int,
        val tierFlag: Boolean,
        val profileIdc: Int,
        val profileCompatibilityFlags: Long,
        val constraintIndicatorFlags: Long,
        val levelIdc: Int
    )

    private class BitReader(private val bytes: ByteArray) {
        private var position: Int = 0

        fun readBit(): Int = readBits(1)

        fun readBits(count: Int): Int {
            require(count in 0..31) { "Unsupported bit count: $count" }
            return readBitsLong(count).toInt()
        }

        fun readBitsLong(count: Int): Long {
            require(count in 0..63) { "Unsupported bit count: $count" }
            var value = 0L
            repeat(count) {
                check(position < bytes.size * 8) { "HEVC SPS ended before requested field" }
                value = (value shl 1) or
                    (((bytes[position / 8].toInt() ushr (7 - position % 8)) and 1).toLong())
                position += 1
            }
            return value
        }

        fun skipBits(count: Int) {
            check(position + count <= bytes.size * 8) { "HEVC SPS skip exceeded RBSP length" }
            position += count
        }

        fun readUnsignedExpGolomb(): Int {
            var leadingZeroBits = 0
            while (readBit() == 0) {
                leadingZeroBits += 1
                check(leadingZeroBits < 31) { "Exp-Golomb code is too large" }
            }
            val suffix = if (leadingZeroBits == 0) 0 else readBits(leadingZeroBits)
            return (1 shl leadingZeroBits) - 1 + suffix
        }
    }
}
