#include "native_buffer_manager.h"

#include <android/hardware_buffer_jni.h>

#include <algorithm>
#include <sstream>

namespace neoproc {
namespace {

constexpr uint32_t kYuv420Format = AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420;
constexpr uint32_t kP010Format = AHARDWAREBUFFER_FORMAT_YCbCr_P010;

AHardwareBuffer_Desc makeDesc(
    int width,
    int height,
    NativeCameraBufferFormat format
) {
    AHardwareBuffer_Desc desc{};
    desc.width = static_cast<uint32_t>(std::max(1, width));
    desc.height = static_cast<uint32_t>(std::max(1, height));
    desc.layers = 1;
    desc.format = static_cast<uint32_t>(format);
    desc.usage = NativeBufferManager::cameraSampledUsage();
    desc.stride = 0;
    desc.rfu0 = 0;
    desc.rfu1 = 0;
    return desc;
}

bool dimensionsMatch(
    const AHardwareBuffer_Desc& desc,
    int expectedWidth,
    int expectedHeight
) {
    return expectedWidth <= 0 ||
        expectedHeight <= 0 ||
        (desc.width == static_cast<uint32_t>(expectedWidth) &&
            desc.height == static_cast<uint32_t>(expectedHeight));
}

bool formatMatches(
    const AHardwareBuffer_Desc& desc,
    int expectedFormat
) {
    if (expectedFormat <= 0) return true;
    const auto expected = static_cast<uint32_t>(expectedFormat);
    if (desc.format == expected) return true;

    // The managed zero-copy formats are strict. Other camera formats, such as
    // RAW_SENSOR, are allowed through because Vulkan may expose them as an
    // external format even when they are not allocatable by this manager.
    if (NativeBufferManager::isManagedFormat(expected)) return false;
    return true;
}

}  // namespace

uint64_t NativeBufferManager::cameraSampledUsage() {
    // Public NDK has no AHARDWAREBUFFER_USAGE_CAMERA_WRITE flag. Camera HAL
    // writes through the ImageReader/Surface producer; native-owned buffers are
    // allocated with public GPU sampling, framebuffer, and encoder usage bits.
    return AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
        AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
        AHARDWAREBUFFER_USAGE_VIDEO_ENCODE;
}

bool NativeBufferManager::isManagedFormat(uint32_t format) {
    return format == kYuv420Format || format == kP010Format;
}

bool NativeBufferManager::allocationSupported(
    int width,
    int height,
    NativeCameraBufferFormat format
) {
    if (width <= 0 || height <= 0) return false;
    const AHardwareBuffer_Desc desc = makeDesc(width, height, format);
    return AHardwareBuffer_isSupported(&desc) != 0;
}

AHardwareBuffer* NativeBufferManager::allocate(
    int width,
    int height,
    NativeCameraBufferFormat format
) {
    if (!allocationSupported(width, height, format)) return nullptr;
    const AHardwareBuffer_Desc desc = makeDesc(width, height, format);
    AHardwareBuffer* buffer = nullptr;
    if (AHardwareBuffer_allocate(&desc, &buffer) != 0) {
        return nullptr;
    }
    return buffer;
}

jobject NativeBufferManager::allocateJavaHardwareBuffer(
    JNIEnv* env,
    int width,
    int height,
    NativeCameraBufferFormat format
) {
    if (env == nullptr) return nullptr;
    AHardwareBuffer* buffer = allocate(width, height, format);
    if (buffer == nullptr) return nullptr;
    jobject javaBuffer = AHardwareBuffer_toHardwareBuffer(env, buffer);
    AHardwareBuffer_release(buffer);
    return javaBuffer;
}

NativeBufferValidationResult NativeBufferManager::validateExternalCameraBuffer(
    AHardwareBuffer* buffer,
    int expectedWidth,
    int expectedHeight,
    int expectedFormat
) {
    NativeBufferValidationResult result{};
    if (buffer == nullptr) {
        result.reason = "hardwareBuffer=null";
        return result;
    }

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    result.width = desc.width;
    result.height = desc.height;
    result.layers = desc.layers;
    result.format = desc.format;
    result.usage = desc.usage;

    if (!dimensionsMatch(desc, expectedWidth, expectedHeight)) {
        result.reason = "dimension mismatch";
        return result;
    }
    if (!formatMatches(desc, expectedFormat)) {
        result.reason = "managed format mismatch";
        return result;
    }

    result.valid = true;
    result.reason = "ok";
    return result;
}

NativeBufferValidationResult NativeBufferManager::validateJavaHardwareBuffer(
    JNIEnv* env,
    jobject hardwareBuffer,
    int expectedWidth,
    int expectedHeight,
    int expectedFormat
) {
    if (env == nullptr || hardwareBuffer == nullptr) {
        return NativeBufferValidationResult{false, 0, 0, 0, 0, 0, "hardwareBuffer=null"};
    }
    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    return validateExternalCameraBuffer(buffer, expectedWidth, expectedHeight, expectedFormat);
}

std::string NativeBufferManager::describe(AHardwareBuffer* buffer) {
    const auto validation = validateExternalCameraBuffer(buffer, 0, 0, 0);
    std::ostringstream out;
    out
        << "valid=" << (validation.valid ? "true" : "false")
        << ";reason=" << validation.reason
        << ";width=" << validation.width
        << ";height=" << validation.height
        << ";layers=" << validation.layers
        << ";format=" << validation.format
        << ";usage=" << validation.usage
        << ";pixelCopy=false";
    return out.str();
}

NativeCameraBufferFormat NativeBufferManager::toNativeFormat(int formatId) {
    if (static_cast<uint32_t>(formatId) == kP010Format || formatId == 1) {
        return NativeCameraBufferFormat::P010;
    }
    return NativeCameraBufferFormat::YUV_420_888;
}

}  // namespace neoproc

