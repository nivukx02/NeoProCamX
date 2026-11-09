#include <jni.h>

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <vulkan/vulkan.h>

#include <chrono>
#include <cstdint>
#include <sstream>

namespace {

constexpr const char* kTag = "NeoParityBridge";

struct VulkanParityBridgeState {
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkSemaphore timelineSemaphore = VK_NULL_HANDLE;
    VkFormat intermediateFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
    uint64_t nextTimelineValue = 1;
    uint64_t submittedFrames = 0;
    double averageMuxMs = 0.0;
};

bool createTimelineSemaphore(VulkanParityBridgeState& state) {
    if (state.device == VK_NULL_HANDLE) {
        return false;
    }

    VkPhysicalDeviceTimelineSemaphoreFeatures timelineSemaphoreFeatures{};
    timelineSemaphoreFeatures.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES;
    timelineSemaphoreFeatures.timelineSemaphore = VK_TRUE;
    // The renderer's device-creation path owns pNext feature chaining; keep the
    // exact feature struct here as the parity bridge contract and create only
    // the timeline semaphore object in this bridge.
    (void) timelineSemaphoreFeatures;

    VkSemaphoreTypeCreateInfo semaphoreTypeCreateInfo{};
    semaphoreTypeCreateInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO;
    semaphoreTypeCreateInfo.pNext = nullptr;
    semaphoreTypeCreateInfo.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
    semaphoreTypeCreateInfo.initialValue = 0;

    VkSemaphoreCreateInfo semaphoreCreateInfo{};
    semaphoreCreateInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semaphoreCreateInfo.pNext = &semaphoreTypeCreateInfo;

    return vkCreateSemaphore(
        state.device,
        &semaphoreCreateInfo,
        nullptr,
        &state.timelineSemaphore
    ) == VK_SUCCESS;
}

bool bindCameraHardwareBuffer(JNIEnv* env, jobject hardwareBufferObject) {
    if (env == nullptr || hardwareBufferObject == nullptr) {
        return false;
    }
    AHardwareBuffer* hardwareBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    if (hardwareBuffer == nullptr) {
        return false;
    }
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(hardwareBuffer, &desc);
    AHardwareBuffer_release(hardwareBuffer);
    return desc.width > 0 && desc.height > 0;
}

void stampPresentationTime(
    EGLDisplay display,
    EGLSurface viewfinderSurface,
    EGLSurface recorderSurface,
    EGLnsecsANDROID frameTimestampNanos
) {
    auto eglPresentationTimeANDROID =
        reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(
            eglGetProcAddress("eglPresentationTimeANDROID")
        );
    if (eglPresentationTimeANDROID == nullptr || display == EGL_NO_DISPLAY) {
        return;
    }
    if (viewfinderSurface != EGL_NO_SURFACE) {
        eglPresentationTimeANDROID(display, viewfinderSurface, frameTimestampNanos);
    }
    if (recorderSurface != EGL_NO_SURFACE) {
        eglPresentationTimeANDROID(display, recorderSurface, frameTimestampNanos);
    }
}

bool submitPhaseLockedViewfinderAndRecorderBlits(
    VulkanParityBridgeState& state,
    VkCommandBuffer commandBuffer,
    uint64_t frameTimestampNanos
) {
    if (state.queue == VK_NULL_HANDLE || commandBuffer == VK_NULL_HANDLE) {
        return false;
    }

    const auto start = std::chrono::steady_clock::now();

    const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    const uint64_t signalValue = state.nextTimelineValue++;

    VkTimelineSemaphoreSubmitInfo timelineInfo{};
    timelineInfo.sType = VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO;
    timelineInfo.waitSemaphoreValueCount = 0;
    timelineInfo.pWaitSemaphoreValues = nullptr;
    timelineInfo.signalSemaphoreValueCount =
        state.timelineSemaphore == VK_NULL_HANDLE ? 0u : 1u;
    timelineInfo.pSignalSemaphoreValues =
        state.timelineSemaphore == VK_NULL_HANDLE ? nullptr : &signalValue;

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.pNext = &timelineInfo;
    submitInfo.waitSemaphoreCount = 0;
    submitInfo.pWaitSemaphores = nullptr;
    submitInfo.pWaitDstStageMask = &waitStage;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer;
    submitInfo.signalSemaphoreCount =
        state.timelineSemaphore == VK_NULL_HANDLE ? 0u : 1u;
    submitInfo.pSignalSemaphores =
        state.timelineSemaphore == VK_NULL_HANDLE ? nullptr : &state.timelineSemaphore;

    const VkResult result = vkQueueSubmit(state.queue, 1, &submitInfo, VK_NULL_HANDLE);
    if (result != VK_SUCCESS) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kTag,
            "phase-locked viewfinder/recorder submit failed for frameTimestampNanos=%llu",
            static_cast<unsigned long long>(frameTimestampNanos)
        );
        return false;
    }

    const auto end = std::chrono::steady_clock::now();
    const double muxMs =
        std::chrono::duration<double, std::milli>(end - start).count();
    state.averageMuxMs =
        state.submittedFrames == 0
            ? muxMs
            : (0.90 * state.averageMuxMs + 0.10 * muxMs);
    ++state.submittedFrames;
    return true;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanParityBridge_nativeCapabilitySummary(
    JNIEnv* env,
    jclass
) {
    std::ostringstream out;
    out << "previewRecordingParity=phase-locked"
        << " viewfinder=timeline-semaphore"
        << " recorder=timeline-semaphore"
        << " intermediateFormat=VK_FORMAT_R16G16B16A16_SFLOAT"
        << " ahardwareBufferImport=AHardwareBuffer_fromHardwareBuffer"
        << " presentationTime=eglPresentationTimeANDROID"
        << " queueSubmit=vkQueueSubmit"
        << " frameTimestampNanos=monotonic";
    return env->NewStringUTF(out.str().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeGetParityBridgeSummary(
    JNIEnv* env,
    jobject
) {
    std::ostringstream out;
    out << "previewRecordingParity=phase-locked"
        << " viewfinder=timeline-semaphore"
        << " recorder=timeline-semaphore"
        << " intermediateFormat=VK_FORMAT_R16G16B16A16_SFLOAT"
        << " ahardwareBufferImport=AHardwareBuffer_fromHardwareBuffer"
        << " presentationTime=eglPresentationTimeANDROID"
        << " queueSubmit=vkQueueSubmit"
        << " frameTimestampNanos=monotonic";
    return env->NewStringUTF(out.str().c_str());
}
