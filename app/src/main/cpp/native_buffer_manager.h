#pragma once

#include <jni.h>

#include <android/hardware_buffer.h>

#include <cstdint>
#include <string>

namespace neoproc {

enum class NativeCameraBufferFormat : uint32_t {
    YUV_420_888 = AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420,
    P010 = AHARDWAREBUFFER_FORMAT_YCbCr_P010
};

struct NativeBufferValidationResult {
    bool valid = false;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t layers = 0;
    uint32_t format = 0;
    uint64_t usage = 0;
    std::string reason;
};

class NativeBufferManager {
public:
    static uint64_t cameraSampledUsage();

    static bool isManagedFormat(uint32_t format);

    static bool allocationSupported(
        int width,
        int height,
        NativeCameraBufferFormat format
    );

    static AHardwareBuffer* allocate(
        int width,
        int height,
        NativeCameraBufferFormat format
    );

    static jobject allocateJavaHardwareBuffer(
        JNIEnv* env,
        int width,
        int height,
        NativeCameraBufferFormat format
    );

    static NativeBufferValidationResult validateExternalCameraBuffer(
        AHardwareBuffer* buffer,
        int expectedWidth,
        int expectedHeight,
        int expectedFormat
    );

    static NativeBufferValidationResult validateJavaHardwareBuffer(
        JNIEnv* env,
        jobject hardwareBuffer,
        int expectedWidth,
        int expectedHeight,
        int expectedFormat
    );

    static std::string describe(AHardwareBuffer* buffer);

    static NativeCameraBufferFormat toNativeFormat(int formatId);
};

}  // namespace neoproc

