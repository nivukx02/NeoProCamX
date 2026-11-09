#include <jni.h>

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

namespace {

constexpr const char* kTag = "AINeuralBridge";

struct TensorPlane {
    const uint8_t* data = nullptr;
    int64_t capacity = 0;
    int32_t rowStride = 0;
    int32_t pixelStride = 1;
};

uint8_t clampToByte(float value) {
    return static_cast<uint8_t>(std::clamp(value, 0.0f, 255.0f));
}

#if defined(__ARM_NEON)
void writeRgbFloat(float* out, uint8_t r, uint8_t g, uint8_t b, float scale, float bias) {
    const uint32_t rgb[4] = {
        static_cast<uint32_t>(r),
        static_cast<uint32_t>(g),
        static_cast<uint32_t>(b),
        0u
    };
    const uint32x4_t rgbU32 = vld1q_u32(rgb);
    const float32x4_t rgbF32 = vcvtq_f32_u32(rgbU32);
    const float32x4_t normalized = vmlaq_n_f32(vdupq_n_f32(bias), rgbF32, scale);
    float tmp[4];
    vst1q_f32(tmp, normalized);
    out[0] = tmp[0];
    out[1] = tmp[1];
    out[2] = tmp[2];
}
#else
void writeRgbFloat(float* out, uint8_t r, uint8_t g, uint8_t b, float scale, float bias) {
    out[0] = static_cast<float>(r) * scale + bias;
    out[1] = static_cast<float>(g) * scale + bias;
    out[2] = static_cast<float>(b) * scale + bias;
}
#endif

bool validPlane(const TensorPlane& plane, int32_t width, int32_t height, bool chroma) {
    if (plane.data == nullptr || plane.capacity <= 0 || plane.rowStride <= 0 || plane.pixelStride <= 0) {
        return false;
    }
    const int32_t sampleWidth = chroma ? (width + 1) / 2 : width;
    const int32_t sampleHeight = chroma ? (height + 1) / 2 : height;
    const int64_t lastIndex =
        static_cast<int64_t>(sampleHeight - 1) * plane.rowStride +
        static_cast<int64_t>(sampleWidth - 1) * plane.pixelStride;
    return lastIndex >= 0 && lastIndex < plane.capacity;
}

bool processYuv420ToRgbTensor(
    const TensorPlane& yPlane,
    const TensorPlane& uPlane,
    const TensorPlane& vPlane,
    int32_t width,
    int32_t height,
    float* output,
    int64_t outputFloatCount,
    float normScale,
    float normBias
) {
    if (width <= 0 || height <= 0 || output == nullptr) return false;
    if (!validPlane(yPlane, width, height, false) ||
        !validPlane(uPlane, width, height, true) ||
        !validPlane(vPlane, width, height, true)) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "Invalid YUV plane layout");
        return false;
    }
    const int64_t requiredFloats = static_cast<int64_t>(width) * height * 3;
    if (outputFloatCount < requiredFloats) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "Output tensor buffer too small");
        return false;
    }

    int64_t outIndex = 0;
    for (int32_t y = 0; y < height; ++y) {
        const int64_t yRowOffset = static_cast<int64_t>(y) * yPlane.rowStride;
        const int32_t chromaY = y / 2;
        const int64_t uRowOffset = static_cast<int64_t>(chromaY) * uPlane.rowStride;
        const int64_t vRowOffset = static_cast<int64_t>(chromaY) * vPlane.rowStride;
        for (int32_t x = 0; x < width; ++x) {
            const int32_t chromaX = x / 2;
            const int64_t yIndex = yRowOffset + static_cast<int64_t>(x) * yPlane.pixelStride;
            const int64_t uIndex = uRowOffset + static_cast<int64_t>(chromaX) * uPlane.pixelStride;
            const int64_t vIndex = vRowOffset + static_cast<int64_t>(chromaX) * vPlane.pixelStride;

            const int32_t luma = static_cast<int32_t>(yPlane.data[yIndex]);
            const int32_t cb = static_cast<int32_t>(uPlane.data[uIndex]) - 128;
            const int32_t cr = static_cast<int32_t>(vPlane.data[vIndex]) - 128;

            const auto r = clampToByte(static_cast<float>(luma) + 1.402f * static_cast<float>(cr));
            const auto g = clampToByte(
                static_cast<float>(luma) -
                0.344136f * static_cast<float>(cb) -
                0.714136f * static_cast<float>(cr)
            );
            const auto b = clampToByte(static_cast<float>(luma) + 1.772f * static_cast<float>(cb));

            writeRgbFloat(output + outIndex, r, g, b, normScale, normBias);
            outIndex += 3;
        }
    }
    return true;
}

TensorPlane directPlane(JNIEnv* env, jobject buffer, jint rowStride, jint pixelStride) {
    return TensorPlane{
        static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer)),
        env->GetDirectBufferCapacity(buffer),
        static_cast<int32_t>(rowStride),
        static_cast<int32_t>(pixelStride)
    };
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_ai_AINeuralEngine_nativeProcessYuvToBuffer(
    JNIEnv* env,
    jobject,
    jobject yPlaneObject,
    jobject uPlaneObject,
    jobject vPlaneObject,
    jint yStride,
    jint uStride,
    jint vStride,
    jint pixelStride,
    jint width,
    jint height,
    jobject directOutputBuffer,
    jfloat normScale,
    jfloat normBias
) {
    if (env == nullptr || yPlaneObject == nullptr || uPlaneObject == nullptr ||
        vPlaneObject == nullptr || directOutputBuffer == nullptr) {
        return JNI_FALSE;
    }

    float* output = static_cast<float*>(env->GetDirectBufferAddress(directOutputBuffer));
    const int64_t outputFloats = env->GetDirectBufferCapacity(directOutputBuffer) / sizeof(float);
    const TensorPlane yPlane = directPlane(env, yPlaneObject, yStride, 1);
    const TensorPlane uPlane = directPlane(env, uPlaneObject, uStride, pixelStride);
    const TensorPlane vPlane = directPlane(env, vPlaneObject, vStride, pixelStride);

    const bool ok = processYuv420ToRgbTensor(
        yPlane,
        uPlane,
        vPlane,
        static_cast<int32_t>(width),
        static_cast<int32_t>(height),
        output,
        outputFloats,
        static_cast<float>(normScale),
        static_cast<float>(normBias)
    );
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_ai_AINeuralEngine_nativeProcessHardwareBufferToBuffer(
    JNIEnv* env,
    jobject,
    jobject hardwareBufferObject,
    jint width,
    jint height,
    jobject directOutputBuffer,
    jfloat normScale,
    jfloat normBias
) {
    if (env == nullptr || hardwareBufferObject == nullptr || directOutputBuffer == nullptr) {
        return JNI_FALSE;
    }

#if __ANDROID_API__ >= 29
    AHardwareBuffer* hardwareBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    if (hardwareBuffer == nullptr) return JNI_FALSE;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(hardwareBuffer, &desc);
    if (static_cast<uint32_t>(width) > desc.width || static_cast<uint32_t>(height) > desc.height) {
        return JNI_FALSE;
    }

    AHardwareBuffer_Planes planes{};
    const int lockResult = AHardwareBuffer_lockPlanes(
        hardwareBuffer,
        AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
        -1,
        nullptr,
        &planes
    );
    if (lockResult != 0) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "AHardwareBuffer_lockPlanes failed: %d", lockResult);
        return JNI_FALSE;
    }

    bool ok = false;
    if (planes.planeCount >= 3) {
        float* output = static_cast<float*>(env->GetDirectBufferAddress(directOutputBuffer));
        const int64_t outputFloats = env->GetDirectBufferCapacity(directOutputBuffer) / sizeof(float);
        const TensorPlane yPlane{
            static_cast<const uint8_t*>(planes.planes[0].data),
            static_cast<int64_t>(planes.planes[0].rowStride) * height,
            static_cast<int32_t>(planes.planes[0].rowStride),
            static_cast<int32_t>(planes.planes[0].pixelStride)
        };
        const TensorPlane uPlane{
            static_cast<const uint8_t*>(planes.planes[1].data),
            static_cast<int64_t>(planes.planes[1].rowStride) * ((height + 1) / 2),
            static_cast<int32_t>(planes.planes[1].rowStride),
            static_cast<int32_t>(planes.planes[1].pixelStride)
        };
        const TensorPlane vPlane{
            static_cast<const uint8_t*>(planes.planes[2].data),
            static_cast<int64_t>(planes.planes[2].rowStride) * ((height + 1) / 2),
            static_cast<int32_t>(planes.planes[2].rowStride),
            static_cast<int32_t>(planes.planes[2].pixelStride)
        };
        ok = processYuv420ToRgbTensor(
            yPlane,
            uPlane,
            vPlane,
            static_cast<int32_t>(width),
            static_cast<int32_t>(height),
            output,
            outputFloats,
            static_cast<float>(normScale),
            static_cast<float>(normBias)
        );
    }

    AHardwareBuffer_unlock(hardwareBuffer, nullptr);
    return ok ? JNI_TRUE : JNI_FALSE;
#else
    (void)hardwareBufferObject;
    (void)width;
    (void)height;
    (void)directOutputBuffer;
    (void)normScale;
    (void)normBias;
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_ai_NeuralDenoiseEngine_nativeBindHardwareBuffer(
    JNIEnv* env,
    jobject,
    jobject hardwareBufferObject,
    jobject directBufferObject,
    jint width,
    jint height
) {
    if (env == nullptr || hardwareBufferObject == nullptr || directBufferObject == nullptr) {
        return JNI_FALSE;
    }

#if __ANDROID_API__ >= 26
    AHardwareBuffer* hardwareBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    if (hardwareBuffer == nullptr) return JNI_FALSE;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(hardwareBuffer, &desc);
    if (width <= 0 || height <= 0 ||
        static_cast<uint32_t>(width) > desc.width ||
        static_cast<uint32_t>(height) > desc.height) {
        return JNI_FALSE;
    }

    float* output = static_cast<float*>(env->GetDirectBufferAddress(directBufferObject));
    const int64_t outputFloats = env->GetDirectBufferCapacity(directBufferObject) / sizeof(float);
    const int64_t requiredFloats = static_cast<int64_t>(width) * height * 3;
    if (output == nullptr || outputFloats < requiredFloats) return JNI_FALSE;

    void* address = nullptr;
    const int lockResult = AHardwareBuffer_lock(
        hardwareBuffer,
        AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
        -1,
        nullptr,
        &address
    );
    if (lockResult != 0 || address == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "AHardwareBuffer_lock failed: %d", lockResult);
        return JNI_FALSE;
    }

    const uint32_t* src = static_cast<const uint32_t*>(address);
    const uint32_t stride = desc.stride == 0 ? desc.width : desc.stride;
    int64_t outIndex = 0;
    for (int32_t y = 0; y < height; ++y) {
        const uint32_t* row = src + static_cast<int64_t>(y) * stride;
        for (int32_t x = 0; x < width; ++x) {
            const uint32_t pixel = row[x];
            const uint8_t r = static_cast<uint8_t>((pixel >> 16) & 0xFF);
            const uint8_t g = static_cast<uint8_t>((pixel >> 8) & 0xFF);
            const uint8_t b = static_cast<uint8_t>(pixel & 0xFF);
            writeRgbFloat(output + outIndex, r, g, b, 1.0f / 255.0f, 0.0f);
            outIndex += 3;
        }
    }

    AHardwareBuffer_unlock(hardwareBuffer, nullptr);
    return JNI_TRUE;
#else
    (void)hardwareBufferObject;
    (void)directBufferObject;
    (void)width;
    (void)height;
    return JNI_FALSE;
#endif
}
