#include "native_buffer_manager.h"

#include <android/hardware_buffer_jni.h>

#include <atomic>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>

namespace {

struct NativeBufferHandle {
    explicit NativeBufferHandle(
        AHardwareBuffer* hardwareBuffer,
        const neoproc::NativeBufferValidationResult& validation
    ) :
        buffer(hardwareBuffer),
        refCount(1),
        width(validation.width),
        height(validation.height),
        layers(validation.layers),
        format(validation.format),
        usage(validation.usage) {}

    AHardwareBuffer* buffer = nullptr;
    std::atomic<uint32_t> refCount;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t layers = 0;
    uint32_t format = 0;
    uint64_t usage = 0;
};

std::mutex gHandleMutex;
std::unordered_map<jlong, NativeBufferHandle*> gHandles;

jstring toJavaString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string validationToString(
    const neoproc::NativeBufferValidationResult& validation,
    uint32_t refCount
) {
    std::ostringstream out;
    out
        << "valid=" << (validation.valid ? "true" : "false")
        << ";reason=" << validation.reason
        << ";width=" << validation.width
        << ";height=" << validation.height
        << ";layers=" << validation.layers
        << ";format=" << validation.format
        << ";usage=" << validation.usage
        << ";refCount=" << refCount
        << ";pixelCopy=false";
    return out.str();
}

std::string handleToString(const NativeBufferHandle* handle) {
    if (handle == nullptr) {
        return "valid=false;reason=handle=null;refCount=0;pixelCopy=false";
    }
    std::ostringstream out;
    out
        << "valid=true"
        << ";reason=ok"
        << ";width=" << handle->width
        << ";height=" << handle->height
        << ";layers=" << handle->layers
        << ";format=" << handle->format
        << ";usage=" << handle->usage
        << ";refCount=" << handle->refCount.load(std::memory_order_acquire)
        << ";pixelCopy=false";
    return out.str();
}

NativeBufferHandle* findHandleLocked(jlong handle) {
    const auto found = gHandles.find(handle);
    return found == gHandles.end() ? nullptr : found->second;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_NativeBufferBridge_nativeExternalMemoryBridgeSupported(
    JNIEnv*,
    jobject
) {
    return neoproc::NativeBufferManager::cameraSampledUsage() != 0u ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_rendering_NativeBufferBridge_nativeAcquireHardwareBuffer(
    JNIEnv* env,
    jobject,
    jobject hardwareBufferObject,
    jint width,
    jint height,
    jint format
) {
    if (env == nullptr || hardwareBufferObject == nullptr) return 0;
    AHardwareBuffer* hardwareBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    const auto validation = neoproc::NativeBufferManager::validateExternalCameraBuffer(
        hardwareBuffer,
        width,
        height,
        format
    );
    if (!validation.valid) return 0;

    AHardwareBuffer_acquire(hardwareBuffer);
    auto* tracked = new NativeBufferHandle(hardwareBuffer, validation);
    const auto handle = reinterpret_cast<jlong>(tracked);
    {
        std::lock_guard<std::mutex> lock(gHandleMutex);
        gHandles.emplace(handle, tracked);
    }
    return handle;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_NativeBufferBridge_nativeRetainHandle(
    JNIEnv*,
    jobject,
    jlong handle
) {
    if (handle == 0) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(gHandleMutex);
    NativeBufferHandle* tracked = findHandleLocked(handle);
    if (tracked == nullptr) return JNI_FALSE;
    tracked->refCount.fetch_add(1, std::memory_order_acq_rel);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_NativeBufferBridge_nativeReleaseHandle(
    JNIEnv*,
    jobject,
    jlong handle
) {
    if (handle == 0) return JNI_FALSE;
    NativeBufferHandle* releaseTarget = nullptr;
    {
        std::lock_guard<std::mutex> lock(gHandleMutex);
        NativeBufferHandle* tracked = findHandleLocked(handle);
        if (tracked == nullptr) return JNI_FALSE;
        const uint32_t previous = tracked->refCount.fetch_sub(1, std::memory_order_acq_rel);
        if (previous <= 1u) {
            gHandles.erase(handle);
            releaseTarget = tracked;
        }
    }
    if (releaseTarget != nullptr) {
        AHardwareBuffer_release(releaseTarget->buffer);
        delete releaseTarget;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_rendering_NativeBufferBridge_nativeDescribeHandle(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    std::lock_guard<std::mutex> lock(gHandleMutex);
    return toJavaString(env, handleToString(findHandleLocked(handle)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_rendering_NativeBufferBridge_nativeValidateHardwareBuffer(
    JNIEnv* env,
    jobject,
    jobject hardwareBufferObject,
    jint width,
    jint height,
    jint format
) {
    if (env == nullptr || hardwareBufferObject == nullptr) {
        return toJavaString(env, "valid=false;reason=hardwareBuffer=null;refCount=0;pixelCopy=false");
    }
    AHardwareBuffer* hardwareBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    const auto validation = neoproc::NativeBufferManager::validateExternalCameraBuffer(
        hardwareBuffer,
        width,
        height,
        format
    );
    return toJavaString(env, validationToString(validation, 0));
}
