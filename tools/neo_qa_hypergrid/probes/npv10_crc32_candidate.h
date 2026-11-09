#pragma once

#include <cstdint>
#include <cstring>
#include <zlib.h>

#if defined(__ANDROID__) && defined(__aarch64__)
#include <asm/hwcap.h>
#include <sys/auxv.h>
#include <arm_acle.h>
#endif

namespace neoproc::npv10::checksum {

// QA-only candidate; rejected for production after the physical-device probe was slower than zlib.
// The wire polynomial and zlib seed/final-XOR convention remain
// IEEE CRC-32, not the incompatible CRC-32C instruction. Unsupported CPUs retain the zlib route.
inline bool hardwareAvailable() noexcept {
#if defined(__ANDROID__) && defined(__aarch64__)
    static const bool available = (::getauxval(AT_HWCAP) & HWCAP_CRC32) != 0u;
    return available;
#else
    return false;
#endif
}

#if defined(__ANDROID__) && defined(__aarch64__)
__attribute__((target("crc"))) inline std::uint32_t extendArm64(
    std::uint32_t initial, const std::uint8_t* bytes, std::uint32_t length
) noexcept {
    std::uint32_t value = initial ^ 0xffffffffu;
    while (length >= sizeof(std::uint64_t)) {
        std::uint64_t word;
        // memcpy supports every plane/chunk alignment without undefined typed or unaligned loads.
        std::memcpy(&word, bytes, sizeof(word));
        value = __crc32d(value, word);
        bytes += sizeof(word);
        length -= sizeof(word);
    }
    while (length != 0u) {
        value = __crc32b(value, *bytes++);
        --length;
    }
    return value ^ 0xffffffffu;
}
#endif

inline uLong crc32(uLong initial, const Bytef* bytes, uInt length) noexcept {
    // zlib assigns special initialization semantics to a null input, even with a nonzero seed.
    if (bytes == Z_NULL) return ::crc32(initial, bytes, length);
#if defined(__ANDROID__) && defined(__aarch64__)
    if (hardwareAvailable()) {
        return static_cast<uLong>(extendArm64(static_cast<std::uint32_t>(initial), bytes, length));
    }
#endif
    return ::crc32(initial, bytes, length);
}

}  // namespace neoproc::npv10::checksum
