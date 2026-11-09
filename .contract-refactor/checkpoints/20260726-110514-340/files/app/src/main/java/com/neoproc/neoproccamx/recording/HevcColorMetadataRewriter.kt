package com.neoproc.neoproccamx.recording

import java.io.ByteArrayOutputStream

internal data class HevcVuiColorMetadata(
    val fullRange: Boolean,
    val colorPrimaries: Int,
    val transferCharacteristics: Int,
    val matrixCoefficients: Int
)

internal object HevcColorMetadataRewriter {
    private const val NAL_TYPE_SPS = 33
    const val NAL_TYPE_PREFIX_SEI = 39
    private const val SEI_PAYLOAD_TYPE_USER_REGISTERED_ITU_T_T35 = 4

    fun rewriteAnnexBColorMetadata(
        bytes: ByteArray,
        colorConfig: QuickTimeColorAtomConfig
    ): ByteArray {
        val units = splitAnnexB(bytes)
        if (units.isEmpty()) return bytes

        var changed = false
        val out = ByteArrayOutputStream(bytes.size)
        units.forEach { unit ->
            out.write(unit.prefix)
            val rewritten = if (hevcNalType(unit.payload) == NAL_TYPE_SPS) {
                rewriteSpsNal(unit.payload, colorConfig)
            } else {
                unit.payload
            }
            if (rewritten !== unit.payload && !rewritten.contentEquals(unit.payload)) {
                changed = true
            }
            out.write(rewritten)
        }
        return if (changed) out.toByteArray() else bytes
    }

    fun firstAnnexBSpsColorMetadata(bytes: ByteArray): HevcVuiColorMetadata? =
        splitAnnexB(bytes)
            .firstOrNull { hevcNalType(it.payload) == NAL_TYPE_SPS }
            ?.payload
            ?.let(::firstSpsColorMetadata)

    fun buildUserRegisteredItutT35PrefixSeiNal(t35Payload: ByteArray): ByteArray {
        require(t35Payload.isNotEmpty()) { "T.35 payload must not be empty" }
        val rbsp = ByteArrayOutputStream(t35Payload.size + 8)
        writeSeiPayloadField(rbsp, SEI_PAYLOAD_TYPE_USER_REGISTERED_ITU_T_T35)
        writeSeiPayloadField(rbsp, t35Payload.size)
        rbsp.write(t35Payload)
        rbsp.write(0x80)
        return byteArrayOf((NAL_TYPE_PREFIX_SEI shl 1).toByte(), 0x01) + rbspToEbsp(rbsp.toByteArray())
    }

    fun injectUserRegisteredItutT35Sei(
        bytes: ByteArray,
        t35Payload: ByteArray
    ): ByteArray {
        val units = splitAnnexB(bytes)
        if (units.isEmpty()) return bytes
        val seiNal = buildUserRegisteredItutT35PrefixSeiNal(t35Payload)
        val out = ByteArrayOutputStream(bytes.size + units.size * (seiNal.size + 4))
        units.forEach { unit ->
            if (hevcNalType(unit.payload).isVclNalType()) {
                out.write(byteArrayOf(0, 0, 0, 1))
                out.write(seiNal)
            }
            out.write(unit.prefix)
            out.write(unit.payload)
        }
        return out.toByteArray()
    }

    fun countUserRegisteredItutT35Sei(bytes: ByteArray): Int =
        splitAnnexB(bytes).count { unit ->
            hevcNalType(unit.payload) == NAL_TYPE_PREFIX_SEI &&
                containsUserRegisteredItutT35Payload(unit.payload, expectedPayload = null)
        }

    fun containsUserRegisteredItutT35Sei(bytes: ByteArray, t35Payload: ByteArray): Boolean =
        splitAnnexB(bytes).any { unit ->
            hevcNalType(unit.payload) == NAL_TYPE_PREFIX_SEI &&
                containsUserRegisteredItutT35Payload(unit.payload, expectedPayload = t35Payload)
        }

    private fun firstSpsColorMetadata(nal: ByteArray): HevcVuiColorMetadata? {
        val rbsp = nalToRbsp(nal) ?: return null
        val offsets = runCatching { parseSpsVuiColorOffsets(rbsp) }.getOrNull() ?: return null
        return HevcVuiColorMetadata(
            fullRange = readBits(rbsp, offsets.fullRangeFlagBit, 1) == 1,
            colorPrimaries = readBits(rbsp, offsets.colorPrimariesBit, 8),
            transferCharacteristics = readBits(rbsp, offsets.transferCharacteristicsBit, 8),
            matrixCoefficients = readBits(rbsp, offsets.matrixCoefficientsBit, 8)
        )
    }

    private fun rewriteSpsNal(
        nal: ByteArray,
        colorConfig: QuickTimeColorAtomConfig
    ): ByteArray {
        val rbsp = nalToRbsp(nal) ?: return nal
        val offsets = runCatching { parseSpsVuiColorOffsets(rbsp) }.getOrNull() ?: return nal
        val rewrittenRbsp = rbsp.copyOf()
        writeBits(
            bytes = rewrittenRbsp,
            bitOffset = offsets.fullRangeFlagBit,
            bitCount = 1,
            value = if (colorConfig.fullRange) 1 else 0
        )
        writeBits(
            bytes = rewrittenRbsp,
            bitOffset = offsets.colorPrimariesBit,
            bitCount = 8,
            value = colorConfig.colorPrimaries
        )
        writeBits(
            bytes = rewrittenRbsp,
            bitOffset = offsets.transferCharacteristicsBit,
            bitCount = 8,
            value = colorConfig.transferCharacteristics
        )
        writeBits(
            bytes = rewrittenRbsp,
            bitOffset = offsets.matrixCoefficientsBit,
            bitCount = 8,
            value = colorConfig.matrixCoefficients
        )
        return nal.copyOfRange(0, 2) + rbspToEbsp(rewrittenRbsp)
    }

    /**
     * Rewrites one SPS NAL without forcing the recording hot path to materialize the complete frame.
     * HEVC access units can be several megabytes at the elite bitrate while an SPS is only a small
     * configuration NAL, so keeping this seam per-NAL avoids large temporary frame arrays.
     */
    internal fun rewriteSpsNalForMov(
        nal: ByteArray,
        colorConfig: QuickTimeColorAtomConfig
    ): ByteArray = rewriteSpsNal(nal, colorConfig)

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

    private fun rbspToEbsp(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rbsp.size)
        var zeroCount = 0
        rbsp.forEach { byte ->
            val value = byte.toInt() and 0xFF
            if (zeroCount >= 2 && value <= 0x03) {
                out.write(0x03)
                zeroCount = 0
            }
            out.write(value)
            zeroCount = if (value == 0) zeroCount + 1 else 0
        }
        return out.toByteArray()
    }

    private fun writeSeiPayloadField(out: ByteArrayOutputStream, value: Int) {
        var remaining = value
        while (remaining >= 0xFF) {
            out.write(0xFF)
            remaining -= 0xFF
        }
        out.write(remaining)
    }

    private fun containsUserRegisteredItutT35Payload(
        nal: ByteArray,
        expectedPayload: ByteArray?
    ): Boolean {
        val rbsp = nalToRbsp(nal) ?: return false
        var index = 0
        while (index + 1 < rbsp.size) {
            val payloadType = readSeiPayloadField(rbsp, index) ?: return false
            index = payloadType.nextIndex
            val payloadSize = readSeiPayloadField(rbsp, index) ?: return false
            index = payloadSize.nextIndex
            if (payloadSize.value < 0 || index + payloadSize.value > rbsp.size) return false
            val payload = rbsp.copyOfRange(index, index + payloadSize.value)
            if (
                payloadType.value == SEI_PAYLOAD_TYPE_USER_REGISTERED_ITU_T_T35 &&
                payload.size >= 5 &&
                (payload[0].toInt() and 0xFF) == 0xB5 &&
                (payload[1].toInt() and 0xFF) == 0x00 &&
                (payload[2].toInt() and 0xFF) == 0x3C &&
                (payload[3].toInt() and 0xFF) == 0x00 &&
                (payload[4].toInt() and 0xFF) == 0x01 &&
                (expectedPayload == null || payload.contentEquals(expectedPayload))
            ) {
                return true
            }
            index += payloadSize.value
            if (index < rbsp.size && (rbsp[index].toInt() and 0x80) != 0) return false
        }
        return false
    }

    private fun readSeiPayloadField(bytes: ByteArray, startIndex: Int): SeiPayloadField? {
        var index = startIndex
        var value = 0
        while (index < bytes.size) {
            val next = bytes[index].toInt() and 0xFF
            value += next
            index += 1
            if (next != 0xFF) return SeiPayloadField(value = value, nextIndex = index)
        }
        return null
    }

    private fun parseSpsVuiColorOffsets(rbsp: ByteArray): SpsVuiColorOffsets? {
        val reader = BitReader(rbsp)
        reader.skipBits(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = reader.readBits(3)
        reader.skipBits(1) // sps_temporal_id_nesting_flag
        skipProfileTierLevel(reader, maxSubLayersMinus1)
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
        reader.readUnsignedExpGolomb() // bit_depth_luma_minus8
        reader.readUnsignedExpGolomb() // bit_depth_chroma_minus8
        val log2MaxPicOrderCntLsbMinus4 = reader.readUnsignedExpGolomb()
        val subLayerOrderingInfoPresentFlag = reader.readBit()
        val orderingStart = if (subLayerOrderingInfoPresentFlag == 1) 0 else maxSubLayersMinus1
        for (index in orderingStart..maxSubLayersMinus1) {
            reader.readUnsignedExpGolomb()
            reader.readUnsignedExpGolomb()
            reader.readUnsignedExpGolomb()
        }
        repeat(6) { reader.readUnsignedExpGolomb() }
        if (reader.readBit() == 1) {
            if (reader.readBit() == 1) {
                skipScalingListData(reader)
            }
        }
        reader.skipBits(1) // amp_enabled_flag
        reader.skipBits(1) // sample_adaptive_offset_enabled_flag
        if (reader.readBit() == 1) {
            reader.skipBits(4)
            reader.skipBits(4)
            reader.readUnsignedExpGolomb()
            reader.readUnsignedExpGolomb()
            reader.skipBits(1)
        }
        val shortTermRefPicSetCount = reader.readUnsignedExpGolomb()
        val deltaPocCounts = ArrayList<Int>(shortTermRefPicSetCount)
        for (index in 0 until shortTermRefPicSetCount) {
            skipShortTermRefPicSet(
                reader = reader,
                stRpsIndex = index,
                stRpsCount = shortTermRefPicSetCount,
                deltaPocCounts = deltaPocCounts
            )
        }
        if (reader.readBit() == 1) {
            val longTermRefPicsCount = reader.readUnsignedExpGolomb()
            repeat(longTermRefPicsCount) {
                reader.skipBits(log2MaxPicOrderCntLsbMinus4 + 4)
                reader.skipBits(1)
            }
        }
        reader.skipBits(1) // sps_temporal_mvp_enabled_flag
        reader.skipBits(1) // strong_intra_smoothing_enabled_flag
        if (reader.readBit() != 1) return null

        if (reader.readBit() == 1) {
            val aspectRatioIdc = reader.readBits(8)
            if (aspectRatioIdc == 255) {
                reader.skipBits(32)
            }
        }
        if (reader.readBit() == 1) {
            reader.skipBits(1)
        }
        if (reader.readBit() != 1) return null
        reader.skipBits(3) // video_format
        val fullRangeFlagBit = reader.position
        reader.skipBits(1)
        if (reader.readBit() != 1) return null
        val colorPrimariesBit = reader.position
        reader.skipBits(8)
        val transferCharacteristicsBit = reader.position
        reader.skipBits(8)
        val matrixCoefficientsBit = reader.position
        reader.skipBits(8)
        return SpsVuiColorOffsets(
            fullRangeFlagBit = fullRangeFlagBit,
            colorPrimariesBit = colorPrimariesBit,
            transferCharacteristicsBit = transferCharacteristicsBit,
            matrixCoefficientsBit = matrixCoefficientsBit
        )
    }

    private fun skipProfileTierLevel(reader: BitReader, maxSubLayersMinus1: Int) {
        reader.skipBits(2 + 1 + 5 + 32 + 48 + 8)
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
    }

    private fun skipScalingListData(reader: BitReader) {
        for (sizeId in 0..3) {
            var matrixId = 0
            while (matrixId < 6) {
                if (reader.readBit() == 0) {
                    reader.readUnsignedExpGolomb()
                } else {
                    val coefficientCount = minOf(64, 1 shl (4 + (sizeId shl 1)))
                    if (sizeId > 1) {
                        reader.readSignedExpGolomb()
                    }
                    repeat(coefficientCount) {
                        reader.readSignedExpGolomb()
                    }
                }
                matrixId += if (sizeId == 3) 3 else 1
            }
        }
    }

    private fun skipShortTermRefPicSet(
        reader: BitReader,
        stRpsIndex: Int,
        stRpsCount: Int,
        deltaPocCounts: MutableList<Int>
    ) {
        val interPrediction = stRpsIndex != 0 && reader.readBit() == 1
        if (interPrediction) {
            if (stRpsIndex == stRpsCount) {
                reader.readUnsignedExpGolomb()
            }
            reader.skipBits(1)
            reader.readUnsignedExpGolomb()
            val previousDeltaPocCount = deltaPocCounts.getOrNull(stRpsIndex - 1) ?: 0
            var deltaPocCount = 0
            repeat(previousDeltaPocCount + 1) {
                val usedByCurrentPic = reader.readBit() == 1
                val useDeltaFlag = if (usedByCurrentPic) true else reader.readBit() == 1
                if (usedByCurrentPic || useDeltaFlag) {
                    deltaPocCount += 1
                }
            }
            deltaPocCounts += deltaPocCount
            return
        }

        val negativePics = reader.readUnsignedExpGolomb()
        val positivePics = reader.readUnsignedExpGolomb()
        repeat(negativePics) {
            reader.readUnsignedExpGolomb()
            reader.skipBits(1)
        }
        repeat(positivePics) {
            reader.readUnsignedExpGolomb()
            reader.skipBits(1)
        }
        deltaPocCounts += negativePics + positivePics
    }

    private fun readBits(bytes: ByteArray, bitOffset: Int, bitCount: Int): Int {
        var value = 0
        for (index in 0 until bitCount) {
            val bit = bitOffset + index
            value = (value shl 1) or ((bytes[bit / 8].toInt() ushr (7 - bit % 8)) and 1)
        }
        return value
    }

    private fun writeBits(
        bytes: ByteArray,
        bitOffset: Int,
        bitCount: Int,
        value: Int
    ) {
        for (index in 0 until bitCount) {
            val bit = bitOffset + index
            val mask = 1 shl (7 - bit % 8)
            val bitValue = (value ushr (bitCount - index - 1)) and 1
            val byteIndex = bit / 8
            bytes[byteIndex] = if (bitValue == 1) {
                (bytes[byteIndex].toInt() or mask).toByte()
            } else {
                (bytes[byteIndex].toInt() and mask.inv()).toByte()
            }
        }
    }

    private fun splitAnnexB(bytes: ByteArray): List<AnnexBNalUnit> {
        val startCodes = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index <= bytes.size - 3) {
            val fourByte =
                index <= bytes.size - 4 &&
                    bytes[index] == 0.toByte() &&
                    bytes[index + 1] == 0.toByte() &&
                    bytes[index + 2] == 0.toByte() &&
                    bytes[index + 3] == 1.toByte()
            val threeByte =
                bytes[index] == 0.toByte() &&
                    bytes[index + 1] == 0.toByte() &&
                    bytes[index + 2] == 1.toByte()
            when {
                fourByte -> {
                    startCodes += index to 4
                    index += 4
                }
                threeByte -> {
                    startCodes += index to 3
                    index += 3
                }
                else -> index += 1
            }
        }
        if (startCodes.isEmpty()) return emptyList()
        return startCodes.mapIndexedNotNull { listIndex, (start, prefixLength) ->
            val payloadStart = start + prefixLength
            val payloadEnd = if (listIndex + 1 < startCodes.size) {
                startCodes[listIndex + 1].first
            } else {
                bytes.size
            }
            if (payloadEnd <= payloadStart) {
                null
            } else {
                AnnexBNalUnit(
                    prefix = bytes.copyOfRange(start, payloadStart),
                    payload = bytes.copyOfRange(payloadStart, payloadEnd)
                )
            }
        }
    }

    private fun hevcNalType(nal: ByteArray): Int {
        if (nal.size < 2) return -1
        return (nal[0].toInt() ushr 1) and 0x3F
    }

    private fun Int.isVclNalType(): Boolean = this in 0..31

    private data class AnnexBNalUnit(
        val prefix: ByteArray,
        val payload: ByteArray
    )

    private data class SeiPayloadField(
        val value: Int,
        val nextIndex: Int
    )

    private data class SpsVuiColorOffsets(
        val fullRangeFlagBit: Int,
        val colorPrimariesBit: Int,
        val transferCharacteristicsBit: Int,
        val matrixCoefficientsBit: Int
    )

    private class BitReader(private val bytes: ByteArray) {
        var position: Int = 0
            private set

        fun readBit(): Int = readBits(1)

        fun readBits(count: Int): Int {
            require(count in 0..31) { "Unsupported bit count: $count" }
            var value = 0
            repeat(count) {
                check(position < bytes.size * 8) { "HEVC SPS ended before VUI color metadata" }
                value = (value shl 1) or
                    ((bytes[position / 8].toInt() ushr (7 - position % 8)) and 1)
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

        fun readSignedExpGolomb(): Int {
            val codeNum = readUnsignedExpGolomb()
            val magnitude = (codeNum + 1) / 2
            return if (codeNum and 1 == 0) -magnitude else magnitude
        }
    }
}
