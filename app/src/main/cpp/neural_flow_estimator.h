#pragma once

#include <jni.h>

#include <atomic>
#include <mutex>
#include <string>

namespace neoproc {

class NeuralFlowEstimator {
public:
    std::string configure(
        const std::string& modelPath,
        bool modelAssetPresent,
        bool nnapiDelegatePreferred,
        int inputSize,
        float maxInferenceMs
    );

    std::string estimateAsync(
        long long currentNativeBufferHandle,
        long long previousNativeBufferHandle,
        int width,
        int height,
        long long timestampNs
    );

    std::string status() const;

private:
    mutable std::mutex mutex_;
    std::atomic<bool> configured_{false};
    std::atomic<bool> modelReady_{false};
    std::atomic<bool> nnapiReady_{false};
    std::atomic<float> lastInferenceMs_{0.0f};
    std::atomic<float> lastConfidence_{0.0f};
    std::string modelPath_;
    std::string state_ = "FALLBACK_UNSUPPORTED";
    std::string reason_ = "not configured";
    int inputSize_ = 256;
    float maxInferenceMs_ = 3.0f;
};

}  // namespace neoproc
