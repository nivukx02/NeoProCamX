#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <limits>

#include <zlib.h>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace {

constexpr std::int64_t kScalarTailBytes = 64;
constexpr jsize kStatsCount = 5;
constexpr jsize kBlockRangeStatsCount = 6;
constexpr jint kBlockRangeWidth = 64;
constexpr jint kBlockRangeHeight = 4;
constexpr std::int64_t kBlockRangeHeaderBytes = 12;
constexpr std::uint8_t kBlockRangeMagic[4] = {'N', 'B', 'R', '1'};

bool checkedPayloadBytes(jint width, jint height, std::int64_t* sampleCount,
                         std::int64_t* payloadBytes) {
    if (width <= 0 || height <= 0) return false;
    const std::int64_t samples = static_cast<std::int64_t>(width) * height;
    if (samples <= 0 || samples > std::numeric_limits<jint>::max()) return false;
    if (samples > std::numeric_limits<std::int64_t>::max() / 2) return false;
    *sampleCount = samples;
    *payloadBytes = samples * 2;
    return true;
}

bool checkedPacked12PayloadBytes(jint width, jint height, std::int64_t* sampleCount,
                                 std::int64_t* payloadBytes) {
    if (width <= 0 || height <= 0) return false;
    const std::int64_t samples = static_cast<std::int64_t>(width) * height;
    if (samples <= 0 || samples > std::numeric_limits<jint>::max()) return false;
    if (samples > (std::numeric_limits<std::int64_t>::max() - 1) / 3) return false;
    *sampleCount = samples;
    *payloadBytes = (samples * 3 + 1) / 2;
    return true;
}

bool sourcePlaneCoversActivePixels(jint width, jint height, jint rowStride,
                                   jint pixelStride, std::int64_t sourceLimit) {
    if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride < 2 || sourceLimit <= 0) {
        return false;
    }
    const std::int64_t lastRow = static_cast<std::int64_t>(height - 1) * rowStride;
    const std::int64_t lastPixel = static_cast<std::int64_t>(width - 1) * pixelStride;
    return lastRow >= 0 && lastPixel >= 0 && lastRow <= sourceLimit &&
        lastPixel <= sourceLimit - lastRow && lastRow + lastPixel <= sourceLimit - 2;
}

void copyRawPlane(const std::uint8_t* source, std::int64_t sourceLimit,
                  std::uint8_t* destination, jint width, jint height,
                  jint rowStride, jint pixelStride) {
    const std::int64_t rowBytes = static_cast<std::int64_t>(width) * 2;
    const std::int64_t scalarTailStart = std::max<std::int64_t>(0, sourceLimit - kScalarTailBytes);

    for (jint y = 0; y < height; ++y) {
        std::uint8_t* destinationRow = destination + static_cast<std::int64_t>(y) * rowBytes;
        const std::int64_t rowStart = static_cast<std::int64_t>(y) * rowStride;
        if (rowStart < 0 || rowStart >= sourceLimit) {
            std::memset(destinationRow, 0, static_cast<std::size_t>(rowBytes));
            continue;
        }

        if (pixelStride == 2) {
            const std::int64_t requestedEnd = std::min(sourceLimit, rowStart + rowBytes);
            const std::int64_t bulkEnd = std::min(requestedEnd, scalarTailStart);
            std::int64_t copied = 0;
            if (bulkEnd > rowStart) {
                copied = bulkEnd - rowStart;
                // The final mapped cache line is deliberately excluded. Some vivo RAW gralloc mappings
                // end immediately before an unmapped page and their optimized memcpy faults at that edge.
                std::memcpy(destinationRow, source + rowStart, static_cast<std::size_t>(copied));
            }
            for (std::int64_t sourceIndex = rowStart + copied;
                 sourceIndex < requestedEnd; ++sourceIndex) {
                destinationRow[sourceIndex - rowStart] = source[sourceIndex];
            }
            const std::int64_t validBytes = requestedEnd - rowStart;
            if (validBytes < rowBytes) {
                std::memset(destinationRow + validBytes, 0,
                            static_cast<std::size_t>(rowBytes - validBytes));
            }
        } else {
            for (jint x = 0; x < width; ++x) {
                const std::int64_t sourceIndex = rowStart +
                    static_cast<std::int64_t>(x) * pixelStride;
                std::uint8_t* output = destinationRow + static_cast<std::int64_t>(x) * 2;
                if (sourceIndex >= 0 && sourceIndex + 1 < sourceLimit) {
                    output[0] = source[sourceIndex];
                    output[1] = source[sourceIndex + 1];
                } else {
                    output[0] = 0;
                    output[1] = 0;
                }
            }
        }
    }
}

inline std::uint16_t loadLittleEndian16(const std::uint8_t* bytes) {
    return static_cast<std::uint16_t>(bytes[0]) |
        static_cast<std::uint16_t>(static_cast<std::uint16_t>(bytes[1]) << 8);
}

inline void storeLittleEndian16(std::uint8_t* bytes, std::uint16_t value) {
    bytes[0] = static_cast<std::uint8_t>(value & 0xFFu);
    bytes[1] = static_cast<std::uint8_t>((value >> 8u) & 0xFFu);
}

inline void storeLittleEndian32(std::uint8_t* bytes, std::uint32_t value) {
    bytes[0] = static_cast<std::uint8_t>(value & 0xFFu);
    bytes[1] = static_cast<std::uint8_t>((value >> 8u) & 0xFFu);
    bytes[2] = static_cast<std::uint8_t>((value >> 16u) & 0xFFu);
    bytes[3] = static_cast<std::uint8_t>((value >> 24u) & 0xFFu);
}

void exactSampledNoisePlane(const std::uint8_t* source, jint width, jint rowStride,
                            jint pixelStride, std::int64_t sampleCount,
                            std::int64_t* differenceSum, std::int64_t* differenceCount);

inline std::uint8_t requiredUnsignedBits(std::uint16_t value) {
    if (value == 0u) return 0u;
    return static_cast<std::uint8_t>(32u - static_cast<unsigned>(__builtin_clz(value)));
}

inline void storePackedWord(std::uint8_t*& destination, std::uint64_t value, int byteCount) {
    for (int index = 0; index < byteCount; ++index) {
        *destination++ = static_cast<std::uint8_t>(value & 0xFFu);
        value >>= 8u;
    }
}

/** Fast byte-aligned paths for a complete 64-pixel tile row. */
bool packFullWidthBlockResiduals(
    const std::uint8_t* source,
    std::uint8_t* destination,
    jint tileY,
    jint blockHeight,
    jint tileX,
    jint rowStride,
    jint pixelStride,
    std::uint16_t base,
    std::uint8_t bits,
    std::int64_t expectedBytes) {
    std::uint8_t* output = destination;
    if (bits == 0u) return expectedBytes == 0;
    for (jint localY = 0; localY < blockHeight; ++localY) {
        const std::uint8_t* row = source +
            static_cast<std::int64_t>(tileY + localY) * rowStride +
            static_cast<std::int64_t>(tileX) * pixelStride;
        jint x = 0;
        if (bits == 8u) {
#if defined(__aarch64__)
            if (pixelStride == 2) {
                const uint16x8_t baseVector = vdupq_n_u16(base);
                for (; x + 8 <= kBlockRangeWidth; x += 8) {
                    const uint16x8_t values = vld1q_u16(
                        reinterpret_cast<const std::uint16_t*>(row + x * 2));
                    vst1_u8(output, vmovn_u16(vsubq_u16(values, baseVector)));
                    output += 8;
                }
            }
#endif
            for (; x < kBlockRangeWidth; ++x) {
                *output++ = static_cast<std::uint8_t>(
                    loadLittleEndian16(row + static_cast<std::int64_t>(x) * pixelStride) - base);
            }
            continue;
        }

        const int groupSamples = (bits == 1u || bits == 3u || bits == 5u || bits == 7u)
            ? 8
            : ((bits == 2u || bits == 6u || bits == 10u) ? 4 : (bits == 4u || bits == 12u ? 2 : 0));
        if (groupSamples == 0) return false;
        const int groupBytes = groupSamples * bits / 8;
        for (; x < kBlockRangeWidth; x += groupSamples) {
            std::uint64_t packed = 0;
            for (int lane = 0; lane < groupSamples; ++lane) {
                const std::uint16_t sample = loadLittleEndian16(
                    row + static_cast<std::int64_t>(x + lane) * pixelStride);
                packed |= static_cast<std::uint64_t>(sample - base) << (lane * bits);
            }
            storePackedWord(output, packed, groupBytes);
        }
    }
    return output - destination == expectedBytes;
}

/**
 * Bit-exact, frame-independent RAW packing designed for thermally constrained storage.
 *
 * Each 64x4 tile stores its exact minimum plus the minimum number of bits required for every
 * unsigned (sample - minimum) residual. There is no quantisation, prediction dependency, or
 * cross-frame state: every sensor code reconstructs exactly and one damaged frame cannot poison a
 * later frame. Natural RAW tiles need substantially fewer bits than the sensor-wide container while
 * an adversarial full-range tile remains bounded by the declared sensor code depth.
 */
bool packRawPlaneBlockRangeExact(
    const std::uint8_t* source,
    std::int64_t sourceLimit,
    std::uint8_t* destination,
    std::int64_t destinationCapacity,
    jint width,
    jint height,
    jint rowStride,
    jint pixelStride,
    jint sensorWhiteLevel,
    std::int64_t sampleCount,
    std::int64_t* payloadBytes,
    std::uint16_t* minimum,
    std::uint16_t* maximum,
    std::int64_t* differenceSum,
    std::int64_t* differenceCount,
    uLong* payloadCrc) {
    if (!sourcePlaneCoversActivePixels(
            width, height, rowStride, pixelStride, sourceLimit) ||
        destination == nullptr || destinationCapacity < kBlockRangeHeaderBytes ||
        sensorWhiteLevel <= 0 || sensorWhiteLevel > 0xFFFF || sampleCount <= 0) {
        return false;
    }

    const std::int64_t tileColumns =
        (static_cast<std::int64_t>(width) + kBlockRangeWidth - 1) / kBlockRangeWidth;
    const std::int64_t tileRows =
        (static_cast<std::int64_t>(height) + kBlockRangeHeight - 1) / kBlockRangeHeight;
    if (tileColumns <= 0 || tileRows <= 0 ||
        tileColumns > std::numeric_limits<std::uint16_t>::max() ||
        tileRows > std::numeric_limits<std::uint16_t>::max() ||
        tileColumns > std::numeric_limits<std::uint32_t>::max() / tileRows) {
        return false;
    }
    const std::uint32_t tileCount = static_cast<std::uint32_t>(tileColumns * tileRows);

    std::memcpy(destination, kBlockRangeMagic, sizeof(kBlockRangeMagic));
    storeLittleEndian16(destination + 4, static_cast<std::uint16_t>(kBlockRangeWidth));
    storeLittleEndian16(destination + 6, static_cast<std::uint16_t>(kBlockRangeHeight));
    storeLittleEndian32(destination + 8, tileCount);
    std::int64_t outputIndex = kBlockRangeHeaderBytes;
    std::uint16_t globalMinimum = std::numeric_limits<std::uint16_t>::max();
    std::uint16_t globalMaximum = 0;

    for (jint tileY = 0; tileY < height; tileY += kBlockRangeHeight) {
        const jint blockHeight = std::min(kBlockRangeHeight, height - tileY);
        for (jint tileX = 0; tileX < width; tileX += kBlockRangeWidth) {
            const jint blockWidth = std::min(kBlockRangeWidth, width - tileX);
            const std::int64_t blockSamples =
                static_cast<std::int64_t>(blockWidth) * blockHeight;
            std::uint16_t blockMinimum = std::numeric_limits<std::uint16_t>::max();
            std::uint16_t blockMaximum = 0;

            for (jint localY = 0; localY < blockHeight; ++localY) {
                const std::uint8_t* row = source +
                    static_cast<std::int64_t>(tileY + localY) * rowStride +
                    static_cast<std::int64_t>(tileX) * pixelStride;
                jint localX = 0;
#if defined(__aarch64__)
                if (pixelStride == 2) {
                    uint16x8_t minimumVector =
                        vdupq_n_u16(std::numeric_limits<std::uint16_t>::max());
                    uint16x8_t maximumVector = vdupq_n_u16(0);
                    for (; localX + 8 <= blockWidth; localX += 8) {
                        const uint16x8_t values = vld1q_u16(
                            reinterpret_cast<const std::uint16_t*>(row + localX * 2));
                        minimumVector = vminq_u16(minimumVector, values);
                        maximumVector = vmaxq_u16(maximumVector, values);
                    }
                    blockMinimum = std::min(blockMinimum, vminvq_u16(minimumVector));
                    blockMaximum = std::max(blockMaximum, vmaxvq_u16(maximumVector));
                }
#endif
                for (; localX < blockWidth; ++localX) {
                    const std::uint16_t sample = loadLittleEndian16(
                        row + static_cast<std::int64_t>(localX) * pixelStride);
                    blockMinimum = std::min(blockMinimum, sample);
                    blockMaximum = std::max(blockMaximum, sample);
                }
            }
            if (blockMaximum > static_cast<std::uint16_t>(sensorWhiteLevel)) return false;
            globalMinimum = std::min(globalMinimum, blockMinimum);
            globalMaximum = std::max(globalMaximum, blockMaximum);

            const std::uint8_t bits = requiredUnsignedBits(
                static_cast<std::uint16_t>(blockMaximum - blockMinimum));
            const std::int64_t packedBytes =
                (blockSamples * static_cast<std::int64_t>(bits) + 7) / 8;
            if (outputIndex > destinationCapacity - 3 ||
                packedBytes > destinationCapacity - outputIndex - 3) {
                return false;
            }
            storeLittleEndian16(destination + outputIndex, blockMinimum);
            destination[outputIndex + 2] = bits;
            outputIndex += 3;

            const std::int64_t packedStart = outputIndex;
            const bool usedFastPath = blockWidth == kBlockRangeWidth && bits != 9u && bits != 11u;
            if (usedFastPath) {
                if (!packFullWidthBlockResiduals(
                        source,
                        destination + outputIndex,
                        tileY,
                        blockHeight,
                        tileX,
                        rowStride,
                        pixelStride,
                        blockMinimum,
                        bits,
                        packedBytes
                    )) {
                    return false;
                }
                outputIndex += packedBytes;
            } else {
                std::uint64_t accumulator = 0;
                unsigned accumulatorBits = 0;
                for (jint localY = 0; localY < blockHeight; ++localY) {
                    const std::uint8_t* row = source +
                        static_cast<std::int64_t>(tileY + localY) * rowStride +
                        static_cast<std::int64_t>(tileX) * pixelStride;
                    for (jint localX = 0; localX < blockWidth; ++localX) {
                        const std::uint16_t sample = loadLittleEndian16(
                            row + static_cast<std::int64_t>(localX) * pixelStride);
                        const std::uint16_t residual =
                            static_cast<std::uint16_t>(sample - blockMinimum);
                        if (bits != 0u) {
                            accumulator |= static_cast<std::uint64_t>(residual) << accumulatorBits;
                            accumulatorBits += bits;
                            while (accumulatorBits >= 8u) {
                                destination[outputIndex++] =
                                    static_cast<std::uint8_t>(accumulator & 0xFFu);
                                accumulator >>= 8u;
                                accumulatorBits -= 8u;
                            }
                        }
                    }
                }
                if (accumulatorBits != 0u) {
                    destination[outputIndex++] = static_cast<std::uint8_t>(accumulator & 0xFFu);
                }
            }
            if (outputIndex - packedStart != packedBytes) return false;
        }
    }

    *payloadBytes = outputIndex;
    *minimum = globalMinimum;
    *maximum = globalMaximum;
    exactSampledNoisePlane(
        source, width, rowStride, pixelStride, sampleCount,
        differenceSum, differenceCount);
    *payloadCrc = ::crc32(
        ::crc32(0L, Z_NULL, 0), destination, static_cast<uInt>(outputIndex));
    return true;
}

void exactSampledNoisePlane(const std::uint8_t* source, jint width, jint rowStride,
                            jint pixelStride, std::int64_t sampleCount,
                            std::int64_t* differenceSum, std::int64_t* differenceCount) {
    const std::int64_t sampleStep = std::max<std::int64_t>(1, sampleCount / 4096);
    const auto sampleAt = [source, width, rowStride, pixelStride](std::int64_t index) {
        const std::int64_t y = index / width;
        const std::int64_t x = index - y * width;
        return loadLittleEndian16(
            source + y * static_cast<std::int64_t>(rowStride) +
            x * static_cast<std::int64_t>(pixelStride));
    };

    std::int64_t sum = 0;
    std::int64_t count = 0;
    std::uint16_t previous = sampleAt(0);
    for (std::int64_t index = sampleStep; index < sampleCount; index += sampleStep) {
        const std::uint16_t current = sampleAt(index);
        const int difference = static_cast<int>(current) - static_cast<int>(previous);
        sum += difference < 0 ? -static_cast<std::int64_t>(difference) : difference;
        ++count;
        previous = current;
    }
    *differenceSum = sum;
    *differenceCount = count;
}

/**
 * Pack the active RAW16 raster into the established NPRAW little-endian packed-12 layout.
 * Every input code is range-checked before it is committed; a value above 4095 rejects the frame
 * rather than masking/clipping sensor information. Statistics are collected from the exact source
 * codes during the same traversal and CRC covers the stored packed payload.
 */
bool packRawPlane12Exact(const std::uint8_t* source, std::int64_t sourceLimit,
                         std::uint8_t* destination, jint width, jint height,
                         jint rowStride, jint pixelStride, std::int64_t sampleCount,
                         std::int64_t payloadBytes, std::uint16_t* minimum,
                         std::uint16_t* maximum, std::int64_t* differenceSum,
                         std::int64_t* differenceCount, uLong* payloadCrc) {
    if (!sourcePlaneCoversActivePixels(
            width, height, rowStride, pixelStride, sourceLimit) ||
        destination == nullptr || sampleCount <= 0 || payloadBytes <= 0) {
        return false;
    }

#if defined(__aarch64__)
    // The production RAW geometry is 4096x3072 with a two-byte Camera2 transport word. Pack
    // sixteen codes into twenty-four bytes per iteration: vuzp separates even/odd pixels and a
    // single vst3 emits the established [Alo, Ahi|Blo, Bhi] byte order for eight pairs. The
    // validation vector accumulates every forbidden high bit and is checked before the caller can
    // commit the packet, preserving the existing fail-closed >12-bit contract.
    if (pixelStride == 2 && (width & 15) == 0 &&
        static_cast<std::int64_t>(rowStride) >= static_cast<std::int64_t>(width) * 2) {
        uint16x8_t minimumVector = vdupq_n_u16(std::numeric_limits<std::uint16_t>::max());
        uint16x8_t maximumVector = vdupq_n_u16(0);
        uint16x8_t invalidHighBits = vdupq_n_u16(0);
        const uint16x8_t lowNibbleMask = vdupq_n_u16(0x000Fu);
        const uint16x8_t forbiddenBitsMask = vdupq_n_u16(0xF000u);
        std::int64_t outputIndex = 0;

        for (jint y = 0; y < height; ++y) {
            const std::uint8_t* sourceRow = source + static_cast<std::int64_t>(y) * rowStride;
            for (jint x = 0; x < width; x += 16) {
                const auto* words = reinterpret_cast<const std::uint16_t*>(
                    sourceRow + static_cast<std::int64_t>(x) * 2);
                const uint16x8_t first = vld1q_u16(words);
                const uint16x8_t second = vld1q_u16(words + 8);

                minimumVector = vminq_u16(minimumVector, vminq_u16(first, second));
                maximumVector = vmaxq_u16(maximumVector, vmaxq_u16(first, second));
                invalidHighBits = vorrq_u16(
                    invalidHighBits,
                    vorrq_u16(
                        vandq_u16(first, forbiddenBitsMask),
                        vandq_u16(second, forbiddenBitsMask)));

                const uint16x8_t even = vuzp1q_u16(first, second);
                const uint16x8_t odd = vuzp2q_u16(first, second);
                const uint8x8_t firstByte = vmovn_u16(even);
                const uint8x8_t secondByte = vmovn_u16(vorrq_u16(
                    vshrq_n_u16(even, 8),
                    vshlq_n_u16(vandq_u16(odd, lowNibbleMask), 4)));
                const uint8x8_t thirdByte = vmovn_u16(vshrq_n_u16(odd, 4));
                uint8x8x3_t packed{};
                packed.val[0] = firstByte;
                packed.val[1] = secondByte;
                packed.val[2] = thirdByte;
                vst3_u8(destination + outputIndex, packed);
                outputIndex += 24;
            }
        }

        if (outputIndex != payloadBytes || vmaxvq_u16(invalidHighBits) != 0) return false;
        *minimum = vminvq_u16(minimumVector);
        *maximum = vmaxvq_u16(maximumVector);
        exactSampledNoisePlane(
            source, width, rowStride, pixelStride, sampleCount,
            differenceSum, differenceCount);
        *payloadCrc = ::crc32(
            ::crc32(0L, Z_NULL, 0), destination, static_cast<uInt>(payloadBytes));
        return true;
    }
#endif

    std::int64_t sourceSampleIndex = 0;
    std::int64_t outputIndex = 0;
    std::uint16_t minValue = std::numeric_limits<std::uint16_t>::max();
    std::uint16_t maxValue = 0;
    std::uint16_t pendingSample = 0;
    bool hasPendingSample = false;

    for (jint y = 0; y < height; ++y) {
        const std::int64_t rowStart = static_cast<std::int64_t>(y) * rowStride;
        for (jint x = 0; x < width; ++x) {
            const std::int64_t sourceIndex = rowStart + static_cast<std::int64_t>(x) * pixelStride;
            const std::uint16_t sample = loadLittleEndian16(source + sourceIndex);
            // This route is selected only for <=12-bit sensors. Fail closed if a broken HAL or
            // incorrect characteristic exposes a wider code; never truncate a RAW sample.
            if (sample > 0x0FFFu) return false;

            minValue = std::min(minValue, sample);
            maxValue = std::max(maxValue, sample);

            if (!hasPendingSample) {
                pendingSample = sample;
                hasPendingSample = true;
            } else {
                destination[outputIndex] = static_cast<std::uint8_t>(pendingSample & 0xFFu);
                destination[outputIndex + 1] = static_cast<std::uint8_t>(
                    ((pendingSample >> 8u) & 0x0Fu) | ((sample & 0x0Fu) << 4u));
                destination[outputIndex + 2] = static_cast<std::uint8_t>((sample >> 4u) & 0xFFu);
                outputIndex += 3;
                hasPendingSample = false;
            }
            ++sourceSampleIndex;
        }
    }

    if (hasPendingSample) {
        destination[outputIndex] = static_cast<std::uint8_t>(pendingSample & 0xFFu);
        destination[outputIndex + 1] = static_cast<std::uint8_t>((pendingSample >> 8u) & 0x0Fu);
        outputIndex += 2;
    }
    if (sourceSampleIndex != sampleCount || outputIndex != payloadBytes) return false;

    *minimum = minValue;
    *maximum = maxValue;
    exactSampledNoisePlane(
        source, width, rowStride, pixelStride, sampleCount,
        differenceSum, differenceCount);
    *payloadCrc = ::crc32(
        ::crc32(0L, Z_NULL, 0),
        destination,
        static_cast<uInt>(payloadBytes)
    );
    return true;
}

void exactMinMax(const std::uint8_t* payload, std::int64_t sampleCount,
                 std::uint16_t* minimum, std::uint16_t* maximum) {
    std::int64_t index = 0;
    std::uint16_t minValue = std::numeric_limits<std::uint16_t>::max();
    std::uint16_t maxValue = 0;

#if defined(__aarch64__)
    uint16x8_t minVector = vdupq_n_u16(std::numeric_limits<std::uint16_t>::max());
    uint16x8_t maxVector = vdupq_n_u16(0);
    for (; index + 8 <= sampleCount; index += 8) {
        const uint16x8_t values = vld1q_u16(
            reinterpret_cast<const std::uint16_t*>(payload + index * 2));
        minVector = vminq_u16(minVector, values);
        maxVector = vmaxq_u16(maxVector, values);
    }
    minValue = vminvq_u16(minVector);
    maxValue = vmaxvq_u16(maxVector);
#endif

    for (; index < sampleCount; ++index) {
        const std::uint16_t value = loadLittleEndian16(payload + index * 2);
        minValue = std::min(minValue, value);
        maxValue = std::max(maxValue, value);
    }
    *minimum = minValue;
    *maximum = maxValue;
}

void exactSampledNoise(const std::uint8_t* payload, std::int64_t sampleCount,
                       std::int64_t* differenceSum, std::int64_t* differenceCount) {
    const std::int64_t sampleStep = std::max<std::int64_t>(1, sampleCount / 4096);
    std::int64_t sum = 0;
    std::int64_t count = 0;
    int previous = loadLittleEndian16(payload);
    for (std::int64_t index = sampleStep; index < sampleCount; index += sampleStep) {
        const int current = loadLittleEndian16(payload + index * 2);
        const int difference = current - previous;
        sum += difference < 0 ? -static_cast<std::int64_t>(difference) : difference;
        ++count;
        previous = current;
    }
    *differenceSum = sum;
    *differenceCount = count;
}

uLong mappedBufferSafeCrc32(const std::uint8_t* payload, std::int64_t payloadBytes) {
    constexpr std::int64_t kProtectedTailBytes = 64;
    const std::int64_t bulkBytes = std::max<std::int64_t>(
        0, payloadBytes - kProtectedTailBytes);
    uLong crc = ::crc32(0L, Z_NULL, 0);
    if (bulkBytes > 0) {
        crc = ::crc32(crc, payload, static_cast<uInt>(bulkBytes));
    }
    // Some camera gralloc mappings terminate exactly at an unmapped page. Keep the final cache line
    // scalar so a vectorized zlib implementation cannot speculatively read beyond that mapping.
    for (std::int64_t index = bulkBytes; index < payloadBytes; ++index) {
        crc = ::crc32(crc, payload + index, 1);
    }
    return crc;
}

void publishStats(JNIEnv* env, jlongArray statsOut, std::uint16_t minimum,
                  std::uint16_t maximum, std::int64_t differenceSum,
                  std::int64_t differenceCount, uLong crc) {
    const jlong stats[kStatsCount] = {
        static_cast<jlong>(minimum),
        static_cast<jlong>(maximum),
        static_cast<jlong>(differenceSum),
        static_cast<jlong>(differenceCount),
        static_cast<jlong>(crc)
    };
    env->SetLongArrayRegion(statsOut, 0, kStatsCount, stats);
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_pipeline_NprawRealtimeNative_nativePackLinear16Payload(
    JNIEnv* env, jclass, jobject sourceBuffer, jint sourceLimit, jobject destinationBuffer,
    jint destinationOffset, jint width, jint height, jint rowStride, jint pixelStride,
    jlongArray statsOut) {
    if (sourceBuffer == nullptr || destinationBuffer == nullptr || statsOut == nullptr ||
        sourceLimit <= 0 || destinationOffset < 0 || rowStride <= 0 || pixelStride < 2 ||
        env->GetArrayLength(statsOut) < kStatsCount) {
        return JNI_FALSE;
    }

    auto* source = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(sourceBuffer));
    auto* destinationBase = static_cast<std::uint8_t*>(
        env->GetDirectBufferAddress(destinationBuffer));
    const jlong sourceCapacity = env->GetDirectBufferCapacity(sourceBuffer);
    const jlong destinationCapacity = env->GetDirectBufferCapacity(destinationBuffer);
    if (source == nullptr || destinationBase == nullptr || sourceCapacity < 0 ||
        destinationCapacity < 0 || sourceLimit > sourceCapacity) {
        return JNI_FALSE;
    }

    std::int64_t sampleCount = 0;
    std::int64_t payloadBytes = 0;
    if (!checkedPayloadBytes(width, height, &sampleCount, &payloadBytes) ||
        static_cast<std::int64_t>(destinationOffset) + payloadBytes > destinationCapacity) {
        return JNI_FALSE;
    }

    std::uint8_t* payload = destinationBase + destinationOffset;
    copyRawPlane(source, sourceLimit, payload, width, height, rowStride, pixelStride);

    std::uint16_t minimum = 0;
    std::uint16_t maximum = 0;
    exactMinMax(payload, sampleCount, &minimum, &maximum);
    std::int64_t differenceSum = 0;
    std::int64_t differenceCount = 0;
    exactSampledNoise(payload, sampleCount, &differenceSum, &differenceCount);
    const uLong crc = ::crc32(::crc32(0L, Z_NULL, 0), payload,
                              static_cast<uInt>(payloadBytes));

    publishStats(env, statsOut, minimum, maximum, differenceSum, differenceCount, crc);
    return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_pipeline_NprawRealtimeNative_nativePackLinear12Payload(
    JNIEnv* env, jclass, jobject sourceBuffer, jint sourceLimit, jobject destinationBuffer,
    jint destinationOffset, jint width, jint height, jint rowStride, jint pixelStride,
    jlongArray statsOut) {
    if (sourceBuffer == nullptr || destinationBuffer == nullptr || statsOut == nullptr ||
        sourceLimit <= 0 || destinationOffset < 0 || rowStride <= 0 || pixelStride < 2 ||
        env->GetArrayLength(statsOut) < kStatsCount) {
        return JNI_FALSE;
    }

    auto* source = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(sourceBuffer));
    auto* destinationBase = static_cast<std::uint8_t*>(
        env->GetDirectBufferAddress(destinationBuffer));
    const jlong sourceCapacity = env->GetDirectBufferCapacity(sourceBuffer);
    const jlong destinationCapacity = env->GetDirectBufferCapacity(destinationBuffer);
    if (source == nullptr || destinationBase == nullptr || sourceCapacity < 0 ||
        destinationCapacity < 0 || sourceLimit > sourceCapacity) {
        return JNI_FALSE;
    }

    std::int64_t sampleCount = 0;
    std::int64_t payloadBytes = 0;
    if (!checkedPacked12PayloadBytes(width, height, &sampleCount, &payloadBytes) ||
        static_cast<std::int64_t>(destinationOffset) + payloadBytes > destinationCapacity) {
        return JNI_FALSE;
    }

    std::uint16_t minimum = 0;
    std::uint16_t maximum = 0;
    std::int64_t differenceSum = 0;
    std::int64_t differenceCount = 0;
    uLong crc = 0;
    if (!packRawPlane12Exact(
            source,
            sourceLimit,
            destinationBase + destinationOffset,
            width,
            height,
            rowStride,
            pixelStride,
            sampleCount,
            payloadBytes,
            &minimum,
            &maximum,
            &differenceSum,
            &differenceCount,
            &crc
        )) {
        return JNI_FALSE;
    }
    publishStats(
        env,
        statsOut,
        minimum,
        maximum,
        differenceSum,
        differenceCount,
        crc
    );
    return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_pipeline_NprawRealtimeNative_nativePackBlockRangePayload(
    JNIEnv* env, jclass, jobject sourceBuffer, jint sourceLimit, jobject destinationBuffer,
    jint destinationOffset, jint width, jint height, jint rowStride, jint pixelStride,
    jint sensorWhiteLevel, jlongArray statsOut) {
    if (sourceBuffer == nullptr || destinationBuffer == nullptr || statsOut == nullptr ||
        sourceLimit <= 0 || destinationOffset < 0 || rowStride <= 0 || pixelStride < 2 ||
        sensorWhiteLevel <= 0 || sensorWhiteLevel > 0xFFFF ||
        env->GetArrayLength(statsOut) < kBlockRangeStatsCount) {
        return JNI_FALSE;
    }

    auto* source = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(sourceBuffer));
    auto* destinationBase = static_cast<std::uint8_t*>(
        env->GetDirectBufferAddress(destinationBuffer));
    const jlong sourceCapacity = env->GetDirectBufferCapacity(sourceBuffer);
    const jlong destinationCapacity = env->GetDirectBufferCapacity(destinationBuffer);
    if (source == nullptr || destinationBase == nullptr || sourceCapacity < 0 ||
        destinationCapacity < 0 || sourceLimit > sourceCapacity ||
        destinationOffset > destinationCapacity) {
        return JNI_FALSE;
    }

    std::int64_t sampleCount = 0;
    std::int64_t rawPayloadBytes = 0;
    if (!checkedPayloadBytes(width, height, &sampleCount, &rawPayloadBytes)) return JNI_FALSE;

    std::int64_t payloadBytes = 0;
    std::uint16_t minimum = 0;
    std::uint16_t maximum = 0;
    std::int64_t differenceSum = 0;
    std::int64_t differenceCount = 0;
    uLong crc = 0;
    if (!packRawPlaneBlockRangeExact(
            source,
            sourceLimit,
            destinationBase + destinationOffset,
            destinationCapacity - destinationOffset,
            width,
            height,
            rowStride,
            pixelStride,
            sensorWhiteLevel,
            sampleCount,
            &payloadBytes,
            &minimum,
            &maximum,
            &differenceSum,
            &differenceCount,
            &crc
        )) {
        return JNI_FALSE;
    }

    const jlong stats[kBlockRangeStatsCount] = {
        static_cast<jlong>(minimum),
        static_cast<jlong>(maximum),
        static_cast<jlong>(differenceSum),
        static_cast<jlong>(differenceCount),
        static_cast<jlong>(crc),
        static_cast<jlong>(payloadBytes)
    };
    env->SetLongArrayRegion(statsOut, 0, kBlockRangeStatsCount, stats);
    return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_pipeline_NprawRealtimeNative_nativeAnalyzeContiguousLinear16Payload(
    JNIEnv* env, jclass, jobject sourceBuffer, jint sourceLimit, jint width, jint height,
    jint rowStride, jint pixelStride, jlongArray statsOut) {
    if (sourceBuffer == nullptr || statsOut == nullptr || sourceLimit <= 0 ||
        rowStride <= 0 || pixelStride != 2 || env->GetArrayLength(statsOut) < kStatsCount) {
        return JNI_FALSE;
    }

    auto* source = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(sourceBuffer));
    const jlong sourceCapacity = env->GetDirectBufferCapacity(sourceBuffer);
    std::int64_t sampleCount = 0;
    std::int64_t payloadBytes = 0;
    if (source == nullptr || sourceCapacity < 0 || sourceLimit > sourceCapacity ||
        !checkedPayloadBytes(width, height, &sampleCount, &payloadBytes) ||
        static_cast<std::int64_t>(rowStride) != static_cast<std::int64_t>(width) * 2 ||
        payloadBytes > sourceLimit) {
        return JNI_FALSE;
    }

    std::uint16_t minimum = 0;
    std::uint16_t maximum = 0;
    exactMinMax(source, sampleCount, &minimum, &maximum);
    std::int64_t differenceSum = 0;
    std::int64_t differenceCount = 0;
    exactSampledNoise(source, sampleCount, &differenceSum, &differenceCount);
    const uLong crc = mappedBufferSafeCrc32(source, payloadBytes);
    publishStats(env, statsOut, minimum, maximum, differenceSum, differenceCount, crc);
    return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}
