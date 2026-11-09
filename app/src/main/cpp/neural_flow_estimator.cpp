#include "neural_flow_estimator.h"

#include <algorithm>
#include <atomic>
#include <mutex>
#include <sstream>

namespace {

// NeuralFlowEstimator owns std::atomic state and std::mutex synchronization in
// the header; state=FALLBACK_UNSUPPORTED is the truthful default until a real
// NNAPI model and LiteRT optical-flow executor are linked.
neoproc::NeuralFlowEstimator gEstimator;

jstring toJavaString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string formatStatus(
    const std::string& state,
    const std::string& reason,
    float inferenceMs,
    float confidence,
    bool zeroCopy
) {
    std::ostringstream out;
    out
        << "state=" << state
        << ";reason=" << reason
        << ";inferenceMs=" << inferenceMs
        << ";confidence=" << confidence
        << ";zeroCopy=" << (zeroCopy ? "true" : "false");
    return out.str();
}

}  // namespace

namespace neoproc {

std::string NeuralFlowEstimator::configure(
    const std::string& modelPath,
    bool modelAssetPresent,
    bool nnapiDelegatePreferred,
    int inputSize,
    float maxInferenceMs
) {
    std::lock_guard<std::mutex> lock(mutex_);
    modelPath_ = modelPath;
    inputSize_ = std::clamp(inputSize, 64, 512);
    maxInferenceMs_ = std::clamp(maxInferenceMs, 0.5f, 16.0f);
    configured_.store(true, std::memory_order_release);
    modelReady_.store(modelAssetPresent, std::memory_order_release);
    nnapiReady_.store(nnapiDelegatePreferred, std::memory_order_release);

    // NNAPI/LiteRT activation is intentionally fail-closed unless a real
    // quantized optical-flow model is present and the runtime bridge is wired.
    if (!modelAssetPresent) {
        state_ = "FALLBACK_UNSUPPORTED";
        reason_ = "model missing";
    } else if (!nnapiDelegatePreferred) {
        state_ = "FALLBACK_UNSUPPORTED";
        reason_ = "NNAPI delegate unavailable";
    } else {
        state_ = "FALLBACK_UNSUPPORTED";
        reason_ = "LiteRT NNAPI optical-flow runtime not linked";
    }
    lastInferenceMs_.store(0.0f, std::memory_order_release);
    lastConfidence_.store(0.0f, std::memory_order_release);
    return formatStatus(state_, reason_, 0.0f, 0.0f, modelAssetPresent && nnapiDelegatePreferred);
}

std::string NeuralFlowEstimator::estimateAsync(
    long long currentNativeBufferHandle,
    long long previousNativeBufferHandle,
    int width,
    int height,
    long long timestampNs
) {
    (void)timestampNs;
    std::lock_guard<std::mutex> lock(mutex_);
    if (!configured_.load(std::memory_order_acquire)) {
        state_ = "FALLBACK_UNSUPPORTED";
        reason_ = "not configured";
    } else if (currentNativeBufferHandle == 0 || previousNativeBufferHandle == 0) {
        state_ = "FALLBACK_UNSUPPORTED";
        reason_ = "zero-copy hardware buffer handle missing";
    } else if (width <= 0 || height <= 0) {
        state_ = "FALLBACK_UNSUPPORTED";
        reason_ = "invalid frame size";
    } else {
        state_ = "FALLBACK_UNSUPPORTED";
        reason_ = "NNAPI inference not active";
    }
    lastInferenceMs_.store(0.0f, std::memory_order_release);
    lastConfidence_.store(0.0f, std::memory_order_release);
    return formatStatus(state_, reason_, 0.0f, 0.0f, false);
}

std::string NeuralFlowEstimator::status() const {
    std::lock_guard<std::mutex> lock(mutex_);
    const bool zeroCopyReady =
        configured_.load(std::memory_order_acquire) &&
        modelReady_.load(std::memory_order_acquire) &&
        nnapiReady_.load(std::memory_order_acquire);
    return formatStatus(
        state_,
        reason_,
        lastInferenceMs_.load(std::memory_order_acquire),
        lastConfidence_.load(std::memory_order_acquire),
        zeroCopyReady
    );
}

}  // namespace neoproc

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_rendering_NeuralFlowEstimatorBridge_nativeConfigure(
    JNIEnv* env,
    jobject,
    jstring modelPath,
    jboolean modelAssetPresent,
    jboolean nnapiDelegatePreferred,
    jint inputSize,
    jfloat maxInferenceMs
) {
    const char* pathChars = modelPath != nullptr ? env->GetStringUTFChars(modelPath, nullptr) : nullptr;
    const std::string path = pathChars != nullptr ? std::string(pathChars) : std::string();
    if (pathChars != nullptr) {
        env->ReleaseStringUTFChars(modelPath, pathChars);
    }
    return toJavaString(
        env,
        gEstimator.configure(
            path,
            modelAssetPresent == JNI_TRUE,
            nnapiDelegatePreferred == JNI_TRUE,
            inputSize,
            maxInferenceMs
        )
    );
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_rendering_NeuralFlowEstimatorBridge_nativeEstimateAsync(
    JNIEnv* env,
    jobject,
    jlong currentNativeBufferHandle,
    jlong previousNativeBufferHandle,
    jint width,
    jint height,
    jlong timestampNs
) {
    return toJavaString(
        env,
        gEstimator.estimateAsync(
            currentNativeBufferHandle,
            previousNativeBufferHandle,
            width,
            height,
            timestampNs
        )
    );
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_rendering_NeuralFlowEstimatorBridge_nativeStatus(
    JNIEnv* env,
    jobject
) {
    return toJavaString(env, gEstimator.status());
}
