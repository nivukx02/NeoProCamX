/**
 * Portable scene-analysis JNI backend for ABIs where ARM Advanced SIMD is unavailable.
 *
 * The public JNI contract intentionally remains identical to scene_compute_neon.cpp. These
 * routines affect analysis cadence only; they never resize, quantize, or modify a captured frame.
 */

#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>

namespace {

class ReadOnlyByteArray final {
public:
    ReadOnlyByteArray(JNIEnv* env, jbyteArray array) : env_(env), array_(array) {
        if (array_ != nullptr) {
            data_ = env_->GetByteArrayElements(array_, nullptr);
        }
    }

    ~ReadOnlyByteArray() {
        if (data_ != nullptr) {
            env_->ReleaseByteArrayElements(array_, data_, JNI_ABORT);
        }
    }

    ReadOnlyByteArray(const ReadOnlyByteArray&) = delete;
    ReadOnlyByteArray& operator=(const ReadOnlyByteArray&) = delete;

    [[nodiscard]] const uint8_t* data() const {
        return reinterpret_cast<const uint8_t*>(data_);
    }

private:
    JNIEnv* env_;
    jbyteArray array_;
    jbyte* data_ = nullptr;
};

[[nodiscard]] int boundedLength(JNIEnv* env, jbyteArray array, jint requested) {
    if (array == nullptr || requested <= 0) return 0;
    return std::min(static_cast<int>(env->GetArrayLength(array)), static_cast<int>(requested));
}

[[nodiscard]] float normalizedMotion(
    const uint8_t* previous,
    const uint8_t* current,
    int length,
    int stride,
    uint32_t* histogram,
    double* averageLuma
) {
    if (previous == nullptr || current == nullptr || length <= 0) return 0.0f;
    const int safeStride = std::max(1, stride);
    uint64_t totalDifference = 0;
    uint64_t lumaSum = 0;
    uint64_t samples = 0;
    for (int index = 0; index < length; index += safeStride) {
        const uint8_t luma = current[index];
        totalDifference += static_cast<uint64_t>(
            std::abs(static_cast<int>(previous[index]) - static_cast<int>(luma))
        );
        lumaSum += luma;
        if (histogram != nullptr) ++histogram[luma];
        ++samples;
    }
    if (samples == 0) return 0.0f;
    if (averageLuma != nullptr) {
        *averageLuma = static_cast<double>(lumaSum) / static_cast<double>(samples) / 255.0;
    }
    const double score = static_cast<double>(totalDifference) /
        static_cast<double>(samples) / 255.0;
    return static_cast<float>(std::clamp(score, 0.0, 1.0));
}

}  // namespace

extern "C" {

JNIEXPORT jfloat JNICALL
Java_com_neoproc_neoproccamx_pipeline_NeonSceneCompute_nativeMotionScore(
    JNIEnv* env,
    jclass,
    jbyteArray previousArray,
    jbyteArray currentArray,
    jint requestedLength
) {
    const int length = std::min(
        boundedLength(env, previousArray, requestedLength),
        boundedLength(env, currentArray, requestedLength)
    );
    if (length <= 0) return 0.0f;
    ReadOnlyByteArray previous(env, previousArray);
    ReadOnlyByteArray current(env, currentArray);
    return normalizedMotion(
        previous.data(),
        current.data(),
        length,
        std::max(1, length / 40000),
        nullptr,
        nullptr
    );
}

JNIEXPORT jfloat JNICALL
Java_com_neoproc_neoproccamx_pipeline_NeonSceneCompute_nativeEdgeDensity(
    JNIEnv* env,
    jclass,
    jbyteArray yBytesArray,
    jint width,
    jint height
) {
    if (yBytesArray == nullptr || width < 3 || height < 3) return 0.0f;
    const int64_t requiredLength = static_cast<int64_t>(width) * static_cast<int64_t>(height);
    if (requiredLength > std::numeric_limits<jsize>::max() ||
        env->GetArrayLength(yBytesArray) < requiredLength) {
        return 0.0f;
    }
    ReadOnlyByteArray bytes(env, yBytesArray);
    const uint8_t* y = bytes.data();
    if (y == nullptr) return 0.0f;

    const int stepX = std::max(1, width / 96);
    const int stepY = std::max(1, height / 96);
    uint64_t strongEdges = 0;
    uint64_t totalSamples = 0;
    for (int row = 1; row < height - 1; row += stepY) {
        const int rowOffset = row * width;
        for (int column = 1; column < width - 1; column += stepX) {
            const int index = rowOffset + column;
            const int horizontal = std::abs(
                static_cast<int>(y[index + 1]) - static_cast<int>(y[index - 1])
            );
            const int vertical = std::abs(
                static_cast<int>(y[index + width]) - static_cast<int>(y[index - width])
            );
            if (horizontal + vertical > 44) ++strongEdges;
            ++totalSamples;
        }
    }
    if (totalSamples == 0) return 0.0f;
    return static_cast<float>(strongEdges) / static_cast<float>(totalSamples);
}

JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_pipeline_NeonSceneCompute_nativeHistogram(
    JNIEnv* env,
    jclass,
    jbyteArray yBytesArray,
    jint requestedLength,
    jintArray histogramOut
) {
    if (histogramOut == nullptr || env->GetArrayLength(histogramOut) < 256) return;
    const int length = boundedLength(env, yBytesArray, requestedLength);
    if (length <= 0) return;
    ReadOnlyByteArray bytes(env, yBytesArray);
    const uint8_t* y = bytes.data();
    if (y == nullptr) return;

    uint32_t histogram[256] = {};
    const int stride = std::max(1, length / 200000);
    for (int index = 0; index < length; index += stride) ++histogram[y[index]];

    static_assert(sizeof(jint) == sizeof(uint32_t));
    env->SetIntArrayRegion(
        histogramOut,
        0,
        256,
        reinterpret_cast<const jint*>(histogram)
    );
}

JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_pipeline_NeonSceneCompute_nativeCombinedAnalysis(
    JNIEnv* env,
    jclass,
    jbyteArray previousArray,
    jbyteArray currentArray,
    jint requestedLength,
    jintArray histogramOut,
    jfloatArray resultsOut
) {
    if (histogramOut == nullptr || resultsOut == nullptr ||
        env->GetArrayLength(histogramOut) < 256 || env->GetArrayLength(resultsOut) < 2) {
        return;
    }
    const int length = std::min(
        boundedLength(env, previousArray, requestedLength),
        boundedLength(env, currentArray, requestedLength)
    );
    if (length <= 0) return;
    ReadOnlyByteArray previous(env, previousArray);
    ReadOnlyByteArray current(env, currentArray);
    if (previous.data() == nullptr || current.data() == nullptr) return;

    uint32_t histogram[256] = {};
    double averageLuma = 0.0;
    const float motionScore = normalizedMotion(
        previous.data(),
        current.data(),
        length,
        std::max(1, length / 40000),
        histogram,
        &averageLuma
    );
    static_assert(sizeof(jint) == sizeof(uint32_t));
    env->SetIntArrayRegion(
        histogramOut,
        0,
        256,
        reinterpret_cast<const jint*>(histogram)
    );
    const jfloat results[] = {
        motionScore,
        static_cast<jfloat>(std::clamp(averageLuma, 0.0, 1.0))
    };
    env->SetFloatArrayRegion(resultsOut, 0, 2, results);
}

}  // extern "C"
