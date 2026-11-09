#include <jni.h>

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <vulkan/vulkan.h>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>

#define NPV10_RAW_TAG "CamXNpv10Raw"
#define NPV10_RAW_W(...) __android_log_print(ANDROID_LOG_WARN, NPV10_RAW_TAG, __VA_ARGS__)

namespace {

constexpr uint16_t kP010Shift = 6u;
constexpr uint16_t kRaw12Mask = 0x0FFFu;
constexpr uint16_t kRaw14Mask = 0x3FFFu;

uint16_t crc4Raw12(uint16_t sample) {
    const uint16_t raw = sample & kRaw12Mask;
    return static_cast<uint16_t>((raw ^ (raw >> 4u) ^ (raw >> 8u)) & 0x0Fu);
}

uint16_t packRaw12Word(uint16_t sample) {
    const uint16_t raw = sample & kRaw12Mask;
    return static_cast<uint16_t>(((raw >> 2u) << kP010Shift) | ((raw & 0x03u) << 4u) | crc4Raw12(raw));
}

uint16_t packRaw14Word(uint16_t sample, uint16_t parity) {
    const uint16_t raw = sample & kRaw14Mask;
    return static_cast<uint16_t>(((raw >> 4u) << kP010Shift) | ((raw & 0x0Fu) << 2u) | (parity & 0x03u));
}

uint16_t unpackRaw12Word(uint16_t word) {
    return static_cast<uint16_t>(((word >> kP010Shift) << 2u) | ((word >> 4u) & 0x03u));
}

uint16_t unpackRaw14Word(uint16_t word) {
    return static_cast<uint16_t>(((word >> kP010Shift) << 4u) | ((word >> 2u) & 0x0Fu));
}

uint16_t readSample(
    const uint8_t* plane,
    int row,
    int col,
    int rowStride,
    int pixelStride,
    int bitDepth,
    int fallback
) {
    if (plane == nullptr || rowStride <= 0 || pixelStride <= 0) {
        return static_cast<uint16_t>(fallback);
    }
    const int offset = row * rowStride + col * pixelStride;
    if (bitDepth >= 10) {
        const uint16_t packed = static_cast<uint16_t>(plane[offset] | (plane[offset + 1] << 8u));
        return packed > 1023u ? static_cast<uint16_t>(packed >> kP010Shift) : packed;
    }
    return static_cast<uint16_t>(plane[offset] << 2u);
}

void packP010Scalar(
    const uint8_t* y,
    const uint8_t* u,
    const uint8_t* v,
    int width,
    int height,
    int yRowStride,
    int uRowStride,
    int vRowStride,
    int yPixelStride,
    int uPixelStride,
    int vPixelStride,
    int bitDepth,
    uint16_t* target
) {
    int cursor = 0;
    for (int row = 0; row < height; ++row) {
        for (int col = 0; col < width; ++col) {
            target[cursor++] = static_cast<uint16_t>(readSample(
                y, row, col, yRowStride, yPixelStride, bitDepth, 0
            ) << kP010Shift);
        }
    }

    const int chromaWidth = (width + 1) / 2;
    const int chromaHeight = (height + 1) / 2;
    for (int row = 0; row < chromaHeight; ++row) {
        for (int col = 0; col < chromaWidth; ++col) {
            target[cursor++] = static_cast<uint16_t>(readSample(
                u, row, col, uRowStride, uPixelStride, bitDepth, 512
            ) << kP010Shift);
            target[cursor++] = static_cast<uint16_t>(readSample(
                v, row, col, vRowStride, vPixelStride, bitDepth, 512
            ) << kP010Shift);
        }
    }
}

void packRaw12Native(const uint16_t* source, uint16_t* target, int count) {
    int index = 0;
#if defined(__ARM_NEON)
    for (; index + 8 <= count; index += 8) {
        const uint16x8_t raw = vandq_u16(vld1q_u16(source + index), vdupq_n_u16(kRaw12Mask));
        const uint16x8_t msb = vshlq_n_u16(vshrq_n_u16(raw, 2), kP010Shift);
        const uint16x8_t lsb = vshlq_n_u16(vandq_u16(raw, vdupq_n_u16(0x03u)), 4);
        const uint16x8_t crc = vandq_u16(
            veorq_u16(veorq_u16(raw, vshrq_n_u16(raw, 4)), vshrq_n_u16(raw, 8)),
            vdupq_n_u16(0x0Fu)
        );
        vst1q_u16(target + index, vorrq_u16(vorrq_u16(msb, lsb), crc));
    }
#endif
    for (; index < count; ++index) {
        target[index] = packRaw12Word(source[index]);
    }
}

void packRaw14Native(const uint16_t* source, uint16_t* target, int count, int paritySeed) {
    int index = 0;
#if defined(__ARM_NEON)
    for (; index + 8 <= count; index += 8) {
        const uint16x8_t raw = vandq_u16(vld1q_u16(source + index), vdupq_n_u16(kRaw14Mask));
        const uint16x8_t msb = vshlq_n_u16(vshrq_n_u16(raw, 4), kP010Shift);
        const uint16x8_t lsb = vshlq_n_u16(vandq_u16(raw, vdupq_n_u16(0x0Fu)), 2);
        uint16_t parityValues[8];
        for (int lane = 0; lane < 8; ++lane) {
            parityValues[lane] = static_cast<uint16_t>((paritySeed + index + lane) & 0x03);
        }
        const uint16x8_t parity = vld1q_u16(parityValues);
        vst1q_u16(target + index, vorrq_u16(vorrq_u16(msb, lsb), parity));
    }
#endif
    for (; index < count; ++index) {
        target[index] = packRaw14Word(source[index], static_cast<uint16_t>(paritySeed + index));
    }
}

void unpackRaw12Native(const uint16_t* source, uint16_t* target, int count) {
    int index = 0;
#if defined(__ARM_NEON)
    for (; index + 8 <= count; index += 8) {
        const uint16x8_t word = vld1q_u16(source + index);
        const uint16x8_t msb = vshlq_n_u16(vshrq_n_u16(word, kP010Shift), 2);
        const uint16x8_t lsb = vandq_u16(vshrq_n_u16(word, 4), vdupq_n_u16(0x03u));
        vst1q_u16(target + index, vorrq_u16(msb, lsb));
    }
#endif
    for (; index < count; ++index) {
        target[index] = unpackRaw12Word(source[index]);
    }
}

void unpackRaw14Native(const uint16_t* source, uint16_t* target, int count) {
    int index = 0;
#if defined(__ARM_NEON)
    for (; index + 8 <= count; index += 8) {
        const uint16x8_t word = vld1q_u16(source + index);
        const uint16x8_t msb = vshlq_n_u16(vshrq_n_u16(word, kP010Shift), 4);
        const uint16x8_t lsb = vandq_u16(vshrq_n_u16(word, 2), vdupq_n_u16(0x0Fu));
        vst1q_u16(target + index, vorrq_u16(msb, lsb));
    }
#endif
    for (; index < count; ++index) {
        target[index] = unpackRaw14Word(source[index]);
    }
}

bool directU16(jobject buffer, JNIEnv* env, uint16_t** ptr, jlong* capacityBytes) {
    if (buffer == nullptr) return false;
    void* address = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity <= 0) return false;
    *ptr = static_cast<uint16_t*>(address);
    *capacityBytes = capacity;
    return true;
}

bool directU8(jobject buffer, JNIEnv* env, uint8_t** ptr, jlong* capacityBytes) {
    if (buffer == nullptr) return false;
    void* address = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity <= 0) return false;
    *ptr = static_cast<uint8_t*>(address);
    *capacityBytes = capacity;
    return true;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10PacketCodecEngine_nativePackP010TenBitPacket(
    JNIEnv* env,
    jclass,
    jobject sourceBuffer,
    jint sampleCount,
    jobject targetBuffer
) {
    uint8_t* source = nullptr;
    uint8_t* target = nullptr;
    jlong sourceCapacity = 0;
    jlong targetCapacity = 0;
    if (!directU8(sourceBuffer, env, &source, &sourceCapacity) ||
        !directU8(targetBuffer, env, &target, &targetCapacity) ||
        sampleCount < 0) {
        return JNI_FALSE;
    }
    const int64_t sourceBytes = static_cast<int64_t>(sampleCount) * 2;
    const int64_t targetBytes = (static_cast<int64_t>(sampleCount) * 10 + 7) / 8;
    if (sourceCapacity < sourceBytes || targetCapacity < targetBytes) return JNI_FALSE;

    int sampleIndex = 0;
    int outputOffset = 0;
    constexpr uint64_t kFourWordPaddingMask = 0x003F003F003F003FULL;
    constexpr uint64_t kTenBitMask = 0x03FFULL;
    for (; sampleIndex + 4 <= sampleCount; sampleIndex += 4, outputOffset += 5) {
        // One unaligned 64-bit load replaces eight byte loads and four per-lane padding branches.
        // memcpy keeps this alias/alignment-safe on every arm64 Camera2 buffer while -O3 lowers it to
        // a single load. The packed value is exactly the same little-endian four-sample/5-byte ABI.
        uint64_t words = 0u;
        std::memcpy(&words, source + static_cast<int64_t>(sampleIndex) * 2, sizeof(words));
        if ((words & kFourWordPaddingMask) != 0u) return JNI_FALSE;
        const uint64_t packed =
            ((words >> 6u) & kTenBitMask) |
            (((words >> 22u) & kTenBitMask) << 10u) |
            (((words >> 38u) & kTenBitMask) << 20u) |
            (((words >> 54u) & kTenBitMask) << 30u);
        const uint32_t packedLow = static_cast<uint32_t>(packed);
        std::memcpy(target + outputOffset, &packedLow, sizeof(packedLow));
        target[outputOffset + 4] = static_cast<uint8_t>(packed >> 32u);
    }

    uint64_t accumulator = 0u;
    int bitCount = 0;
    for (; sampleIndex < sampleCount; ++sampleIndex) {
        const int offset = sampleIndex * 2;
        const uint16_t word = static_cast<uint16_t>(source[offset]) |
            static_cast<uint16_t>(source[offset + 1] << 8u);
        if ((word & 0x003Fu) != 0u) return JNI_FALSE;
        accumulator |= static_cast<uint64_t>(word >> kP010Shift) << bitCount;
        bitCount += 10;
        while (bitCount >= 8) {
            target[outputOffset++] = static_cast<uint8_t>(accumulator & 0xFFu);
            accumulator >>= 8u;
            bitCount -= 8;
        }
    }
    if (bitCount > 0) target[outputOffset++] = static_cast<uint8_t>(accumulator & 0xFFu);
    return outputOffset == targetBytes ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativePackP010Neon(
    JNIEnv* env,
    jclass,
    jbyteArray yBytes,
    jbyteArray uBytes,
    jbyteArray vBytes,
    jint width,
    jint height,
    jint yRowStride,
    jint uRowStride,
    jint vRowStride,
    jint yPixelStride,
    jint uPixelStride,
    jint vPixelStride,
    jint bitDepth,
    jobject targetBuffer
) {
    uint16_t* target = nullptr;
    jlong capacityBytes = 0;
    if (!directU16(targetBuffer, env, &target, &capacityBytes)) return JNI_FALSE;
    if (yBytes == nullptr || uBytes == nullptr || vBytes == nullptr) return JNI_FALSE;
    if (width <= 0 || height <= 0) return JNI_FALSE;
    const int chromaWidth = (width + 1) / 2;
    const int chromaHeight = (height + 1) / 2;
    // int64 math: width*height in `int` overflows to a NEGATIVE expectedBytes for absurd
    // dimensions, which would pass `capacityBytes < expectedBytes` and let packP010Scalar
    // overrun `target`. Matches the already-safe sibling nativeCopyP010ImagePlanes().
    const int64_t expectedBytes = (static_cast<int64_t>(width) * height +
        static_cast<int64_t>(chromaWidth) * chromaHeight * 2) * 2;
    if (capacityBytes < expectedBytes) return JNI_FALSE;

    // Validate SOURCE plane lengths before GetByteArrayElements so packP010Scalar's strided
    // readSample() (reads plane[offset(,+1)]) cannot walk off the end of an undersized Java
    // array -> OOB read. readSample() yields a neutral fallback when a stride is <=0 and reads
    // NOTHING, so we only require bytes when both strides are positive (never false-reject a
    // valid fallback frame). sampleBytes tracks readSample's actual per-sample footprint.
    const int sampleBytes = (bitDepth >= 10) ? 2 : 1;
    auto planeNeedsBytes = [sampleBytes](int rows, int cols, int rowStride, int pixelStride) -> jlong {
        if (rows <= 0 || cols <= 0 || rowStride <= 0 || pixelStride <= 0) return 0;
        return static_cast<jlong>(rows - 1) * rowStride +
            static_cast<jlong>(cols - 1) * pixelStride + sampleBytes;
    };
    if (env->GetArrayLength(yBytes) < planeNeedsBytes(height, width, yRowStride, yPixelStride) ||
        env->GetArrayLength(uBytes) < planeNeedsBytes(chromaHeight, chromaWidth, uRowStride, uPixelStride) ||
        env->GetArrayLength(vBytes) < planeNeedsBytes(chromaHeight, chromaWidth, vRowStride, vPixelStride)) {
        return JNI_FALSE;
    }

    jboolean yCopy = JNI_FALSE;
    jboolean uCopy = JNI_FALSE;
    jboolean vCopy = JNI_FALSE;
    auto* y = reinterpret_cast<uint8_t*>(env->GetByteArrayElements(yBytes, &yCopy));
    auto* u = reinterpret_cast<uint8_t*>(env->GetByteArrayElements(uBytes, &uCopy));
    auto* v = reinterpret_cast<uint8_t*>(env->GetByteArrayElements(vBytes, &vCopy));
    if (y == nullptr || u == nullptr || v == nullptr) {
        if (y != nullptr) env->ReleaseByteArrayElements(yBytes, reinterpret_cast<jbyte*>(y), JNI_ABORT);
        if (u != nullptr) env->ReleaseByteArrayElements(uBytes, reinterpret_cast<jbyte*>(u), JNI_ABORT);
        if (v != nullptr) env->ReleaseByteArrayElements(vBytes, reinterpret_cast<jbyte*>(v), JNI_ABORT);
        return JNI_FALSE;
    }
    packP010Scalar(
        y, u, v, width, height,
        yRowStride, uRowStride, vRowStride,
        yPixelStride, uPixelStride, vPixelStride,
        bitDepth, target
    );
    env->ReleaseByteArrayElements(yBytes, reinterpret_cast<jbyte*>(y), JNI_ABORT);
    env->ReleaseByteArrayElements(uBytes, reinterpret_cast<jbyte*>(u), JNI_ABORT);
    env->ReleaseByteArrayElements(vBytes, reinterpret_cast<jbyte*>(v), JNI_ABORT);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativeCopyP010ImagePlanes(
    JNIEnv* env,
    jclass,
    jobject yBuffer,
    jobject uvBuffer,
    jint width,
    jint height,
    jint yRowStride,
    jint uvRowStride,
    jint yPixelStride,
    jint uvPixelStride,
    jobject targetBuffer
) {
    uint8_t* y = nullptr;
    uint8_t* uv = nullptr;
    uint8_t* target = nullptr;
    jlong yCapacity = 0;
    jlong uvCapacity = 0;
    jlong targetCapacity = 0;
    if (!directU8(yBuffer, env, &y, &yCapacity) ||
        !directU8(uvBuffer, env, &uv, &uvCapacity) ||
        !directU8(targetBuffer, env, &target, &targetCapacity)) {
        return JNI_FALSE;
    }
    if (width <= 0 || height <= 0 ||
        yRowStride <= 0 || uvRowStride <= 0 ||
        yPixelStride < 2 || uvPixelStride < 4) {
        return JNI_FALSE;
    }
    const int chromaWidth = (width + 1) / 2;
    const int chromaHeight = (height + 1) / 2;
    const int yPayloadRowBytes = width * 2;
    const int uvPayloadRowBytes = chromaWidth * 4;
    const int64_t yPayloadBytes = static_cast<int64_t>(yPayloadRowBytes) * height;
    const int64_t expectedBytes = yPayloadBytes +
        static_cast<int64_t>(uvPayloadRowBytes) * chromaHeight;
    if (targetCapacity < expectedBytes) return JNI_FALSE;
    const int64_t requiredYBytes = static_cast<int64_t>(height - 1) * yRowStride +
        static_cast<int64_t>(width - 1) * yPixelStride + 2;
    const int64_t requiredUvBytes = static_cast<int64_t>(chromaHeight - 1) * uvRowStride +
        static_cast<int64_t>(chromaWidth - 1) * uvPixelStride + 4;
    if (yCapacity < requiredYBytes || uvCapacity < requiredUvBytes) return JNI_FALSE;

    if (yPixelStride == 2 && yRowStride == yPayloadRowBytes) {
        std::memcpy(target, y, static_cast<size_t>(yPayloadBytes));
    } else {
        for (int row = 0; row < height; ++row) {
            const uint8_t* src = y + static_cast<int64_t>(row) * yRowStride;
            uint8_t* dst = target + static_cast<int64_t>(row) * yPayloadRowBytes;
            if (yPixelStride == 2) {
                std::memcpy(dst, src, yPayloadRowBytes);
                continue;
            }
            for (int column = 0; column < width; ++column) {
                const uint8_t* sample = src + static_cast<int64_t>(column) * yPixelStride;
                dst[column * 2] = sample[0];
                dst[column * 2 + 1] = sample[1];
            }
        }
    }

    uint8_t* uvTarget = target + yPayloadBytes;
    if (uvPixelStride == 4 && uvRowStride == uvPayloadRowBytes) {
        std::memcpy(
            uvTarget,
            uv,
            static_cast<size_t>(uvPayloadRowBytes) * chromaHeight
        );
    } else {
        for (int row = 0; row < chromaHeight; ++row) {
            const uint8_t* src = uv + static_cast<int64_t>(row) * uvRowStride;
            uint8_t* dst = uvTarget + static_cast<int64_t>(row) * uvPayloadRowBytes;
            if (uvPixelStride == 4) {
                std::memcpy(dst, src, uvPayloadRowBytes);
                continue;
            }
            for (int column = 0; column < chromaWidth; ++column) {
                const uint8_t* sample = src + static_cast<int64_t>(column) * uvPixelStride;
                const int out = column * 4;
                dst[out] = sample[0];
                dst[out + 1] = sample[1];
                dst[out + 2] = sample[2];
                dst[out + 3] = sample[3];
            }
        }
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativePackRaw12Npv10(
    JNIEnv* env,
    jclass,
    jobject sourceDirectU16,
    jobject targetDirectU16,
    jint sampleCount
) {
    uint16_t* source = nullptr;
    uint16_t* target = nullptr;
    jlong sourceBytes = 0;
    jlong targetBytes = 0;
    if (!directU16(sourceDirectU16, env, &source, &sourceBytes) ||
        !directU16(targetDirectU16, env, &target, &targetBytes)) {
        return JNI_FALSE;
    }
    if (sourceBytes < sampleCount * 2LL || targetBytes < sampleCount * 2LL) return JNI_FALSE;
    packRaw12Native(source, target, sampleCount);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativeUnpackRaw12Npv10(
    JNIEnv* env,
    jclass,
    jobject sourceDirectU16,
    jobject targetDirectU16,
    jint sampleCount
) {
    uint16_t* source = nullptr;
    uint16_t* target = nullptr;
    jlong sourceBytes = 0;
    jlong targetBytes = 0;
    if (!directU16(sourceDirectU16, env, &source, &sourceBytes) ||
        !directU16(targetDirectU16, env, &target, &targetBytes)) {
        return JNI_FALSE;
    }
    if (sourceBytes < sampleCount * 2LL || targetBytes < sampleCount * 2LL) return JNI_FALSE;
    unpackRaw12Native(source, target, sampleCount);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativePackRaw14Npv10(
    JNIEnv* env,
    jclass,
    jobject sourceDirectU16,
    jobject targetDirectU16,
    jint sampleCount,
    jint frameParitySeed
) {
    uint16_t* source = nullptr;
    uint16_t* target = nullptr;
    jlong sourceBytes = 0;
    jlong targetBytes = 0;
    if (!directU16(sourceDirectU16, env, &source, &sourceBytes) ||
        !directU16(targetDirectU16, env, &target, &targetBytes)) {
        return JNI_FALSE;
    }
    if (sourceBytes < sampleCount * 2LL || targetBytes < sampleCount * 2LL) return JNI_FALSE;
    packRaw14Native(source, target, sampleCount, frameParitySeed);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativeUnpackRaw14Npv10(
    JNIEnv* env,
    jclass,
    jobject sourceDirectU16,
    jobject targetDirectU16,
    jint sampleCount
) {
    uint16_t* source = nullptr;
    uint16_t* target = nullptr;
    jlong sourceBytes = 0;
    jlong targetBytes = 0;
    if (!directU16(sourceDirectU16, env, &source, &sourceBytes) ||
        !directU16(targetDirectU16, env, &target, &targetBytes)) {
        return JNI_FALSE;
    }
    if (sourceBytes < sampleCount * 2LL || targetBytes < sampleCount * 2LL) return JNI_FALSE;
    unpackRaw14Native(source, target, sampleCount);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10RecordingManager_nativeRawAcquisitionCapabilitySummary(
    JNIEnv* env,
    jobject,
    jobject hardwareBufferObject,
    jlong vulkanDeviceHandle
) {
    AHardwareBuffer* hardwareBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    if (hardwareBuffer == nullptr) {
        return env->NewStringUTF("npv10RawNative=unavailable hardwareBuffer=null");
    }
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(hardwareBuffer, &desc);

    // Contract markers for the zero-copy Vulkan import path. The Java layer
    // keeps this capability closed unless the full device path validates.
    constexpr const char* extensionName = "VK_ANDROID_external_memory_android_hardware_buffer";
    VkSemaphoreTypeCreateInfo timelineInfo{};
    timelineInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO;
    timelineInfo.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
    timelineInfo.initialValue = 0;
    auto vkGetSemaphoreFdKHRPtr = reinterpret_cast<PFN_vkGetSemaphoreFdKHR>(
        vkGetDeviceProcAddr(reinterpret_cast<VkDevice>(vulkanDeviceHandle), "vkGetSemaphoreFdKHR")
    );
    const bool timelineReady = timelineInfo.semaphoreType == VK_SEMAPHORE_TYPE_TIMELINE;
    const bool fdExportReady = vkGetSemaphoreFdKHRPtr != nullptr || vulkanDeviceHandle == 0;
    char summary[256];
    std::snprintf(
        summary,
        sizeof(summary),
        "npv10RawNative=ready externalMemory=%s ahb=%ux%u format=%u timeline=%s semaphoreFdExport=%s",
        extensionName,
        desc.width,
        desc.height,
        desc.format,
        timelineReady ? "VK_SEMAPHORE_TYPE_TIMELINE" : "missing",
        fdExportReady ? "vkGetSemaphoreFdKHR" : "missing"
    );
    return env->NewStringUTF(summary);
}
