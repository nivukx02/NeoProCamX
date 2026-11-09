#include "native_buffer_manager.h"

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>

#include <cmath>
#include <cstdlib>
#include <string>

namespace {

jstring toJavaString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_NativeCameraBridge_nativeAllocationSupported(
    JNIEnv*,
    jobject,
    jint width,
    jint height,
    jint formatId
) {
    const auto format = neoproc::NativeBufferManager::toNativeFormat(formatId);
    return neoproc::NativeBufferManager::allocationSupported(width, height, format)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_neoproc_neoproccamx_rendering_NativeCameraBridge_nativeAllocateHardwareBuffer(
    JNIEnv* env,
    jobject,
    jint width,
    jint height,
    jint formatId
) {
    const auto format = neoproc::NativeBufferManager::toNativeFormat(formatId);
    // NativeBufferManager performs AHardwareBuffer_toHardwareBuffer and then
    // releases the native allocation reference so Java owns the lifetime.
    return neoproc::NativeBufferManager::allocateJavaHardwareBuffer(env, width, height, format);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_rendering_NativeCameraBridge_nativeValidateHardwareBuffer(
    JNIEnv* env,
    jobject,
    jobject hardwareBufferObject,
    jint expectedWidth,
    jint expectedHeight,
    jint expectedFormat
) {
    const auto result = neoproc::NativeBufferManager::validateJavaHardwareBuffer(
        env,
        hardwareBufferObject,
        expectedWidth,
        expectedHeight,
        expectedFormat
    );
    std::string text =
        "valid=" + std::string(result.valid ? "true" : "false") +
        ";reason=" + result.reason +
        ";width=" + std::to_string(result.width) +
        ";height=" + std::to_string(result.height) +
        ";layers=" + std::to_string(result.layers) +
        ";format=" + std::to_string(result.format) +
        ";usage=" + std::to_string(result.usage) +
        ";pixelCopy=false";
    return toJavaString(env, text);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_rendering_NativeCameraBridge_nativeDescribeHardwareBuffer(
    JNIEnv* env,
    jobject,
    jobject hardwareBufferObject
) {
    if (hardwareBufferObject == nullptr) {
        return toJavaString(env, "valid=false;reason=hardwareBuffer=null;pixelCopy=false");
    }
    AHardwareBuffer* buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    return toJavaString(env, neoproc::NativeBufferManager::describe(buffer));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_rendering_NativeCameraBridge_nativeCameraSampledUsageFlags(
    JNIEnv*,
    jobject
) {
    return static_cast<jlong>(neoproc::NativeBufferManager::cameraSampledUsage());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_rendering_NativeCameraBridge_nativeValidateDualGainHardwareBuffers(
    JNIEnv* env,
    jobject,
    jobject lcgHardwareBuffer,
    jobject hcgHardwareBuffer,
    jint expectedWidth,
    jint expectedHeight,
    jint expectedFormat,
    jlong lcgTimestampNs,
    jlong hcgTimestampNs,
    jfloat kGain,
    jboolean privateExtensionRequested
) {
    const long long skew = std::llabs(
        static_cast<long long>(lcgTimestampNs) - static_cast<long long>(hcgTimestampNs)
    );
    auto makeResult = [&](bool valid, const std::string& reason, const std::string& path) {
        std::string text =
            "valid=" + std::string(valid ? "true" : "false") +
            ";reason=" + reason +
            ";path=" + path +
            ";timestampSkewNs=" + std::to_string(skew) +
            ";kGain=" + std::to_string(static_cast<double>(kGain)) +
            ";motionMask=" + std::string(valid ? "true" : "false") +
            ";aces=ACESCG_LINEAR" +
            ";pixelCopy=false";
        return toJavaString(env, text);
    };

    if (privateExtensionRequested == JNI_TRUE) {
        return makeResult(
            false,
            "PRIVATE_EXTENSION_REJECTED_PUBLIC_CAMERA2_ONLY",
            "EXISTING_HDR_MERGE_FALLBACK"
        );
    }
    if (!std::isfinite(static_cast<double>(kGain)) || kGain < 1.0f || kGain > 64.0f) {
        return makeResult(false, "INVALID_SENSOR_GAIN_COEFFICIENT", "EXISTING_HDR_MERGE_FALLBACK");
    }
    if (skew > 500000LL) {
        return makeResult(false, "DUAL_GAIN_TIMESTAMP_SKEW_TOO_HIGH", "EXISTING_HDR_MERGE_FALLBACK");
    }

    const auto lcg = neoproc::NativeBufferManager::validateJavaHardwareBuffer(
        env,
        lcgHardwareBuffer,
        expectedWidth,
        expectedHeight,
        expectedFormat
    );
    if (!lcg.valid) {
        return makeResult(false, "LCG_" + lcg.reason, "EXISTING_HDR_MERGE_FALLBACK");
    }
    const auto hcg = neoproc::NativeBufferManager::validateJavaHardwareBuffer(
        env,
        hcgHardwareBuffer,
        expectedWidth,
        expectedHeight,
        expectedFormat
    );
    if (!hcg.valid) {
        return makeResult(false, "HCG_" + hcg.reason, "EXISTING_HDR_MERGE_FALLBACK");
    }
    if (lcg.width != hcg.width || lcg.height != hcg.height || lcg.format != hcg.format) {
        return makeResult(false, "DUAL_GAIN_BUFFER_PAIR_MISMATCH", "EXISTING_HDR_MERGE_FALLBACK");
    }
    return makeResult(true, "OK", "PUBLIC_CAMERA2_DUAL_GAIN");
}
