#include <jni.h>

#include <android/bitmap.h>
#include <android/log.h>

#include <vulkan/vulkan.h>

#include <array>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "generated/hdr_merge_comp_spv.h"

#define HDR_LOG_TAG "CamXHdrMerge"
#define HDR_LOGI(...) __android_log_print(ANDROID_LOG_INFO, HDR_LOG_TAG, __VA_ARGS__)
#define HDR_LOGW(...) __android_log_print(ANDROID_LOG_WARN, HDR_LOG_TAG, __VA_ARGS__)
#define HDR_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, HDR_LOG_TAG, __VA_ARGS__)

#ifndef NEOPROCAMX_STRICT_HIGH_END
#define NEOPROCAMX_STRICT_HIGH_END 0
#endif

namespace {

constexpr bool kStrictHighEndBuild = NEOPROCAMX_STRICT_HIGH_END != 0;
constexpr uint32_t kMaxHdrFrames = 8u;
constexpr uint64_t kHdrMergeGpuDeadlineNs = 2000000000ULL;

struct HdrPushConstants {
    uint32_t width;
    uint32_t height;
    uint32_t frameCount;
    uint32_t fastApproximation;
    float exposureScales[kMaxHdrFrames];
    float normalizationFactors[kMaxHdrFrames];
    uint32_t outputAcesCg16f; // s4-f04/CP35: 0 = 8-bit sRGB out (default); !=0 = scene-linear radiance out
};
static_assert(sizeof(HdrPushConstants) == 84, "HdrPushConstants ABI is 84 bytes (s4-f04/CP35)");

struct HdrMergeEngine {
    std::mutex mutex;
    bool initialized = false;
    bool supported = false;
    std::string status = "uninitialized";
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex = 0;
    VkQueue computeQueue = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkShaderModule shader = VK_NULL_HANDLE;
    // Reused submission objects. A deadline overrun retains the per-merge buffers below until this
    // fence actually signals, so CPU fallback never frees memory still referenced by the GPU.
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence submissionFence = VK_NULL_HANDLE;
    bool submissionPending = false;
    VkBuffer pendingInputBuffer = VK_NULL_HANDLE;
    VkDeviceMemory pendingInputMemory = VK_NULL_HANDLE;
    VkBuffer pendingOutputBuffer = VK_NULL_HANDLE;
    VkDeviceMemory pendingOutputMemory = VK_NULL_HANDLE;
    VkDescriptorPool pendingDescriptorPool = VK_NULL_HANDLE;
};

bool check(VkResult result, const char* label) {
    if (result == VK_SUCCESS) return true;
    HDR_LOGE("%s failed with %d", label, static_cast<int>(result));
    return false;
}

HdrMergeEngine& engine() {
    static HdrMergeEngine instance;
    return instance;
}

uint32_t findMemoryType(
    VkPhysicalDevice physicalDevice,
    uint32_t typeBits,
    VkMemoryPropertyFlags properties
) {
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties);
    for (uint32_t index = 0; index < memoryProperties.memoryTypeCount; ++index) {
        const bool typeMatch = (typeBits & (1u << index)) != 0u;
        const bool propertyMatch =
            (memoryProperties.memoryTypes[index].propertyFlags & properties) == properties;
        if (typeMatch && propertyMatch) return index;
    }
    return UINT32_MAX;
}

VkShaderModule createShaderModule(VkDevice device, const uint32_t* code, size_t wordCount) {
    VkShaderModuleCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    createInfo.codeSize = wordCount * sizeof(uint32_t);
    createInfo.pCode = code;
    VkShaderModule shader = VK_NULL_HANDLE;
    if (!check(vkCreateShaderModule(device, &createInfo, nullptr, &shader), "vkCreateShaderModule(hdr)")) {
        return VK_NULL_HANDLE;
    }
    return shader;
}

void releasePendingSubmissionResources(HdrMergeEngine* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return;
    if (state->pendingDescriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(state->device, state->pendingDescriptorPool, nullptr);
        state->pendingDescriptorPool = VK_NULL_HANDLE;
    }
    if (state->pendingInputBuffer != VK_NULL_HANDLE) {
        vkDestroyBuffer(state->device, state->pendingInputBuffer, nullptr);
        state->pendingInputBuffer = VK_NULL_HANDLE;
    }
    if (state->pendingInputMemory != VK_NULL_HANDLE) {
        vkFreeMemory(state->device, state->pendingInputMemory, nullptr);
        state->pendingInputMemory = VK_NULL_HANDLE;
    }
    if (state->pendingOutputBuffer != VK_NULL_HANDLE) {
        vkDestroyBuffer(state->device, state->pendingOutputBuffer, nullptr);
        state->pendingOutputBuffer = VK_NULL_HANDLE;
    }
    if (state->pendingOutputMemory != VK_NULL_HANDLE) {
        vkFreeMemory(state->device, state->pendingOutputMemory, nullptr);
        state->pendingOutputMemory = VK_NULL_HANDLE;
    }
}

bool retirePendingSubmission(HdrMergeEngine* state, uint64_t timeoutNs) {
    if (state == nullptr || !state->submissionPending) return true;
    const VkResult waitResult = vkWaitForFences(
        state->device, 1, &state->submissionFence, VK_TRUE, timeoutNs);
    if (waitResult == VK_TIMEOUT) return false;
    if (waitResult != VK_SUCCESS) {
        HDR_LOGE("pending HDR submission wait failed with %d", static_cast<int>(waitResult));
        state->status = "pending HDR submission fence failed";
        if (waitResult == VK_ERROR_DEVICE_LOST) {
            // Device loss terminates submitted work; its resources may now be released before the
            // logical device is rebuilt. Other wait errors do not prove completion, so retain every
            // referenced object and retry the fence instead of risking a GPU use-after-free.
            state->submissionPending = false;
            state->supported = false;
            releasePendingSubmissionResources(state);
        }
        return false;
    }
    state->submissionPending = false;
    releasePendingSubmissionResources(state);
    return true;
}

void destroyEngine(HdrMergeEngine* state) {
    if (state == nullptr) return;
    if (state->device != VK_NULL_HANDLE) {
        if (state->submissionPending &&
            !retirePendingSubmission(state, kHdrMergeGpuDeadlineNs)) {
            HDR_LOGE("HDR submission still pending during engine teardown");
            state->status = "teardown deferred: HDR GPU work still pending";
            return;
        }
        if (!state->submissionPending) {
            releasePendingSubmissionResources(state);
        }
        if (state->submissionFence != VK_NULL_HANDLE) {
            vkDestroyFence(state->device, state->submissionFence, nullptr);
            state->submissionFence = VK_NULL_HANDLE;
        }
        if (state->commandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(state->device, state->commandPool, nullptr);
            state->commandPool = VK_NULL_HANDLE;
            state->commandBuffer = VK_NULL_HANDLE;
        }
        if (state->pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(state->device, state->pipeline, nullptr);
            state->pipeline = VK_NULL_HANDLE;
        }
        if (state->shader != VK_NULL_HANDLE) {
            vkDestroyShaderModule(state->device, state->shader, nullptr);
            state->shader = VK_NULL_HANDLE;
        }
        if (state->pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(state->device, state->pipelineLayout, nullptr);
            state->pipelineLayout = VK_NULL_HANDLE;
        }
        if (state->descriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(state->device, state->descriptorSetLayout, nullptr);
            state->descriptorSetLayout = VK_NULL_HANDLE;
        }
        vkDestroyDevice(state->device, nullptr);
        state->device = VK_NULL_HANDLE;
    }
    if (state->instance != VK_NULL_HANDLE) {
        vkDestroyInstance(state->instance, nullptr);
        state->instance = VK_NULL_HANDLE;
    }
    state->initialized = false;
    state->supported = false;
}

bool initializeEngine(HdrMergeEngine* state) {
    if (state->initialized) return state->supported;
    state->initialized = true;

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "CamX HDR Merge";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "CamXHdrMerge";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &appInfo;
    if (!check(vkCreateInstance(&instanceInfo, nullptr, &state->instance), "vkCreateInstance(hdr)")) {
        state->status = "instance creation failed";
        state->initialized = false;
        return false;
    }

    uint32_t deviceCount = 0;
    if (!check(vkEnumeratePhysicalDevices(state->instance, &deviceCount, nullptr), "vkEnumeratePhysicalDevices(count)") ||
        deviceCount == 0) {
        state->status = "no physical device";
        destroyEngine(state);
        return false;
    }
    std::vector<VkPhysicalDevice> devices(deviceCount);
    if (!check(vkEnumeratePhysicalDevices(state->instance, &deviceCount, devices.data()), "vkEnumeratePhysicalDevices(list)")) {
        state->status = "enumerate devices failed";
        destroyEngine(state);
        return false;
    }
    state->physicalDevice = devices.front();

    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(state->physicalDevice, &queueFamilyCount, nullptr);
    if (queueFamilyCount == 0) {
        state->status = "no queue family";
        destroyEngine(state);
        return false;
    }
    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(state->physicalDevice, &queueFamilyCount, queueFamilies.data());
    bool foundQueue = false;
    for (uint32_t index = 0; index < queueFamilyCount; ++index) {
        if ((queueFamilies[index].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0u) {
            state->queueFamilyIndex = index;
            foundQueue = true;
            break;
        }
    }
    if (!foundQueue) {
        for (uint32_t index = 0; index < queueFamilyCount; ++index) {
            if ((queueFamilies[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0u) {
                state->queueFamilyIndex = index;
                foundQueue = true;
                break;
            }
        }
    }
    if (!foundQueue) {
        state->status = "no compute queue";
        destroyEngine(state);
        return false;
    }

    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = state->queueFamilyIndex;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &queuePriority;

    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    if (!check(vkCreateDevice(state->physicalDevice, &deviceInfo, nullptr, &state->device), "vkCreateDevice(hdr)")) {
        state->status = "device creation failed";
        destroyEngine(state);
        return false;
    }
    vkGetDeviceQueue(state->device, state->queueFamilyIndex, 0, &state->computeQueue);

    VkCommandPoolCreateInfo commandPoolInfo{};
    commandPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    commandPoolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    commandPoolInfo.queueFamilyIndex = state->queueFamilyIndex;
    if (!check(vkCreateCommandPool(
            state->device, &commandPoolInfo, nullptr, &state->commandPool),
            "vkCreateCommandPool(hdr persistent)")) {
        state->status = "command pool creation failed";
        destroyEngine(state);
        return false;
    }
    VkCommandBufferAllocateInfo commandBufferInfo{};
    commandBufferInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandBufferInfo.commandPool = state->commandPool;
    commandBufferInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandBufferInfo.commandBufferCount = 1;
    if (!check(vkAllocateCommandBuffers(
            state->device, &commandBufferInfo, &state->commandBuffer),
            "vkAllocateCommandBuffers(hdr persistent)")) {
        state->status = "command buffer allocation failed";
        destroyEngine(state);
        return false;
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    if (!check(vkCreateFence(
            state->device, &fenceInfo, nullptr, &state->submissionFence),
            "vkCreateFence(hdr persistent)")) {
        state->status = "submission fence creation failed";
        destroyEngine(state);
        return false;
    }

    VkDescriptorSetLayoutBinding inputBinding{};
    inputBinding.binding = 0;
    inputBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    inputBinding.descriptorCount = 1;
    inputBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutBinding outputBinding{};
    outputBinding.binding = 1;
    outputBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    outputBinding.descriptorCount = 1;
    outputBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    std::array<VkDescriptorSetLayoutBinding, 2> bindings{inputBinding, outputBinding};
    VkDescriptorSetLayoutCreateInfo setLayoutInfo{};
    setLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setLayoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
    setLayoutInfo.pBindings = bindings.data();
    if (!check(vkCreateDescriptorSetLayout(state->device, &setLayoutInfo, nullptr, &state->descriptorSetLayout), "vkCreateDescriptorSetLayout(hdr)")) {
        state->status = "descriptor set layout failed";
        destroyEngine(state);
        return false;
    }

    VkPushConstantRange pushConstantRange{};
    pushConstantRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushConstantRange.offset = 0;
    pushConstantRange.size = sizeof(HdrPushConstants);

    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &state->descriptorSetLayout;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushConstantRange;
    if (!check(vkCreatePipelineLayout(state->device, &pipelineLayoutInfo, nullptr, &state->pipelineLayout), "vkCreatePipelineLayout(hdr)")) {
        state->status = "pipeline layout failed";
        destroyEngine(state);
        return false;
    }

    state->shader = createShaderModule(state->device, kHdrMergeCompSpv, kHdrMergeCompSpvCount);
    if (state->shader == VK_NULL_HANDLE) {
        state->status = "shader module failed";
        destroyEngine(state);
        return false;
    }

    VkPipelineShaderStageCreateInfo shaderStageInfo{};
    shaderStageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    shaderStageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    shaderStageInfo.module = state->shader;
    shaderStageInfo.pName = "main";

    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = shaderStageInfo;
    pipelineInfo.layout = state->pipelineLayout;
    if (!check(vkCreateComputePipelines(state->device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &state->pipeline), "vkCreateComputePipelines(hdr)")) {
        state->status = "compute pipeline failed";
        destroyEngine(state);
        return false;
    }

    state->supported = true;
    state->status = "ready";
    HDR_LOGI("HDR Vulkan compute pipeline initialized");
    return true;
}

bool createBuffer(
    HdrMergeEngine* state,
    VkDeviceSize size,
    VkBufferUsageFlags usage,
    VkBuffer* buffer,
    VkDeviceMemory* memory
) {
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = size;
    bufferInfo.usage = usage;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!check(vkCreateBuffer(state->device, &bufferInfo, nullptr, buffer), "vkCreateBuffer(hdr)")) {
        return false;
    }
    VkMemoryRequirements memoryRequirements{};
    vkGetBufferMemoryRequirements(state->device, *buffer, &memoryRequirements);
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memoryRequirements.size;
    const uint32_t memoryTypeIndex = findMemoryType(
        state->physicalDevice,
        memoryRequirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    );
    if (memoryTypeIndex == UINT32_MAX) {
        HDR_LOGW("HDR merge requires host-visible coherent Vulkan buffer memory");
        vkDestroyBuffer(state->device, *buffer, nullptr);
        *buffer = VK_NULL_HANDLE;
        return false;
    }
    allocInfo.memoryTypeIndex = memoryTypeIndex;
    if (!check(vkAllocateMemory(state->device, &allocInfo, nullptr, memory), "vkAllocateMemory(hdr)")) {
        vkDestroyBuffer(state->device, *buffer, nullptr);
        *buffer = VK_NULL_HANDLE;
        return false;
    }
    if (!check(vkBindBufferMemory(state->device, *buffer, *memory, 0), "vkBindBufferMemory(hdr)")) {
        vkFreeMemory(state->device, *memory, nullptr);
        vkDestroyBuffer(state->device, *buffer, nullptr);
        *memory = VK_NULL_HANDLE;
        *buffer = VK_NULL_HANDLE;
        return false;
    }
    return true;
}

bool extractBitmapPixels(
    JNIEnv* env,
    jobject bitmap,
    uint32_t width,
    uint32_t height,
    std::vector<uint32_t>* destination,
    size_t frameOffsetPixels
) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        HDR_LOGW("AndroidBitmap_getInfo failed");
        return false;
    }
    if (info.width != width || info.height != height || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        HDR_LOGW("Bitmap format/size mismatch width=%u height=%u format=%d", info.width, info.height, info.format);
        return false;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
        HDR_LOGW("AndroidBitmap_lockPixels failed");
        return false;
    }
    auto* src = static_cast<const uint8_t*>(pixels);
    for (uint32_t y = 0; y < height; ++y) {
        const auto* row = src + static_cast<size_t>(y) * info.stride;
        for (uint32_t x = 0; x < width; ++x) {
            const size_t base = static_cast<size_t>(x) * 4u;
            const uint32_t r = row[base + 0u];
            const uint32_t g = row[base + 1u];
            const uint32_t b = row[base + 2u];
            const uint32_t a = row[base + 3u];
            (*destination)[frameOffsetPixels + static_cast<size_t>(y) * width + x] =
                (a << 24) | (r << 16) | (g << 8) | b;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_neoproc_neoproccamx_capture_HdrMergeNative_nativeMergeHdrBitmaps(
    JNIEnv* env,
    jobject,
    jobjectArray bitmaps,
    jfloatArray exposureScales,
    jfloatArray normalizationFactors,
    jboolean fastApproximation
) {
    if (env == nullptr || bitmaps == nullptr || exposureScales == nullptr ||
        normalizationFactors == nullptr) {
        HDR_LOGW("HDR merge received null input");
        return nullptr;
    }
    auto& state = engine();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!initializeEngine(&state)) {
        HDR_LOGW("HDR Vulkan engine unavailable: %s", state.status.c_str());
        return nullptr;
    }
    if (!retirePendingSubmission(&state, 0u)) {
        if (!state.supported) {
            destroyEngine(&state);
            HDR_LOGW("HDR Vulkan engine reset after device loss");
            return nullptr;
        }
        state.status = "previous HDR GPU merge still pending; using CPU fallback";
        HDR_LOGW("%s", state.status.c_str());
        return nullptr;
    }

    const jsize frameCount = env->GetArrayLength(bitmaps);
    if (frameCount <= 0 || frameCount > static_cast<jsize>(kMaxHdrFrames)) {
        HDR_LOGW("Unsupported HDR frame count %d", frameCount);
        return nullptr;
    }
    if (env->GetArrayLength(exposureScales) < frameCount || env->GetArrayLength(normalizationFactors) < frameCount) {
        HDR_LOGW("Exposure metadata mismatch");
        return nullptr;
    }

    jobject firstBitmap = env->GetObjectArrayElement(bitmaps, 0);
    if (firstBitmap == nullptr) return nullptr;
    AndroidBitmapInfo firstInfo{};
    if (AndroidBitmap_getInfo(env, firstBitmap, &firstInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        firstInfo.width == 0 || firstInfo.height == 0 ||
        firstInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        env->DeleteLocalRef(firstBitmap);
        HDR_LOGW("First HDR bitmap invalid");
        return nullptr;
    }
    const uint32_t width = firstInfo.width;
    const uint32_t height = firstInfo.height;
    env->DeleteLocalRef(firstBitmap);

    const size_t pixelCount = static_cast<size_t>(width) * static_cast<size_t>(height);
    std::vector<uint32_t> inputPixels(pixelCount * static_cast<size_t>(frameCount));
    for (jsize index = 0; index < frameCount; ++index) {
        jobject bitmap = env->GetObjectArrayElement(bitmaps, index);
        const bool ok = bitmap != nullptr &&
            extractBitmapPixels(
                env,
                bitmap,
                width,
                height,
                &inputPixels,
                static_cast<size_t>(index) * pixelCount
            );
        if (bitmap != nullptr) {
            env->DeleteLocalRef(bitmap);
        }
        if (!ok) {
            return nullptr;
        }
    }

    std::array<float, kMaxHdrFrames> exposureScaleValues{};
    std::array<float, kMaxHdrFrames> normalizationValues{};
    env->GetFloatArrayRegion(exposureScales, 0, frameCount, exposureScaleValues.data());
    env->GetFloatArrayRegion(normalizationFactors, 0, frameCount, normalizationValues.data());

    VkBuffer inputBuffer = VK_NULL_HANDLE;
    VkDeviceMemory inputMemory = VK_NULL_HANDLE;
    VkBuffer outputBuffer = VK_NULL_HANDLE;
    VkDeviceMemory outputMemory = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = state.commandBuffer;
    jintArray outputArray = nullptr;

    auto cleanup = [&]() {
        if (descriptorPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(state.device, descriptorPool, nullptr);
            descriptorPool = VK_NULL_HANDLE;
        }
        if (inputBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(state.device, inputBuffer, nullptr);
            inputBuffer = VK_NULL_HANDLE;
        }
        if (inputMemory != VK_NULL_HANDLE) {
            vkFreeMemory(state.device, inputMemory, nullptr);
            inputMemory = VK_NULL_HANDLE;
        }
        if (outputBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(state.device, outputBuffer, nullptr);
            outputBuffer = VK_NULL_HANDLE;
        }
        if (outputMemory != VK_NULL_HANDLE) {
            vkFreeMemory(state.device, outputMemory, nullptr);
            outputMemory = VK_NULL_HANDLE;
        }
    };

    const VkDeviceSize inputSize = static_cast<VkDeviceSize>(inputPixels.size() * sizeof(uint32_t));
    const VkDeviceSize outputSize = static_cast<VkDeviceSize>(pixelCount * sizeof(uint32_t));
    if (!createBuffer(&state, inputSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, &inputBuffer, &inputMemory) ||
        !createBuffer(&state, outputSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, &outputBuffer, &outputMemory)) {
        cleanup();
        return nullptr;
    }

    void* mapped = nullptr;
    if (!check(vkMapMemory(state.device, inputMemory, 0, inputSize, 0, &mapped), "vkMapMemory(inputHdr)")) {
        cleanup();
        return nullptr;
    }
    std::memcpy(mapped, inputPixels.data(), static_cast<size_t>(inputSize));
    vkUnmapMemory(state.device, inputMemory);

    if (!check(vkMapMemory(state.device, outputMemory, 0, outputSize, 0, &mapped), "vkMapMemory(outputHdrInit)")) {
        cleanup();
        return nullptr;
    }
    std::memset(mapped, 0, static_cast<size_t>(outputSize));
    vkUnmapMemory(state.device, outputMemory);

    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 2;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    poolInfo.maxSets = 1;
    if (!check(vkCreateDescriptorPool(state.device, &poolInfo, nullptr, &descriptorPool), "vkCreateDescriptorPool(hdr)")) {
        cleanup();
        return nullptr;
    }

    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = descriptorPool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &state.descriptorSetLayout;
    if (!check(vkAllocateDescriptorSets(state.device, &allocInfo, &descriptorSet), "vkAllocateDescriptorSets(hdr)")) {
        cleanup();
        return nullptr;
    }

    VkDescriptorBufferInfo inputInfo{};
    inputInfo.buffer = inputBuffer;
    inputInfo.offset = 0;
    inputInfo.range = inputSize;
    VkDescriptorBufferInfo outputInfo{};
    outputInfo.buffer = outputBuffer;
    outputInfo.offset = 0;
    outputInfo.range = outputSize;
    std::array<VkWriteDescriptorSet, 2> writes{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = descriptorSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inputInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = descriptorSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &outputInfo;
    vkUpdateDescriptorSets(state.device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);

    if (!check(vkResetCommandPool(state.device, state.commandPool, 0),
               "vkResetCommandPool(hdr)")) {
        cleanup();
        return nullptr;
    }

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!check(vkBeginCommandBuffer(commandBuffer, &beginInfo), "vkBeginCommandBuffer(hdr)")) {
        cleanup();
        return nullptr;
    }

    HdrPushConstants pushConstants{};
    pushConstants.width = width;
    pushConstants.height = height;
    pushConstants.frameCount = static_cast<uint32_t>(frameCount);
    pushConstants.fastApproximation = kStrictHighEndBuild ? 0u : (fastApproximation == JNI_TRUE ? 1u : 0u);
    std::memcpy(pushConstants.exposureScales, exposureScaleValues.data(), sizeof(pushConstants.exposureScales));
    std::memcpy(pushConstants.normalizationFactors, normalizationValues.data(), sizeof(pushConstants.normalizationFactors));
    // s4-f04/CP35: default OFF — the still-capture JPEG path is unchanged until the owner enables the
    // scene-linear 16f hand-off device-side (the 16f float output buffer is the device-gated step).
    pushConstants.outputAcesCg16f = 0u;

    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state.pipeline);
    vkCmdBindDescriptorSets(
        commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state.pipelineLayout,
        0,
        1,
        &descriptorSet,
        0,
        nullptr
    );
    vkCmdPushConstants(
        commandBuffer,
        state.pipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0,
        sizeof(HdrPushConstants),
        &pushConstants
    );
    vkCmdDispatch(
        commandBuffer,
        (width + 15u) / 16u,
        (height + 15u) / 16u,
        1u
    );

    VkMemoryBarrier memoryBarrier{};
    memoryBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    memoryBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    memoryBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_HOST_BIT,
        0,
        1, &memoryBarrier,
        0, nullptr,
        0, nullptr
    );

    if (!check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer(hdr)")) {
        cleanup();
        return nullptr;
    }

    if (!check(vkResetFences(state.device, 1, &state.submissionFence),
               "vkResetFences(hdr)")) {
        cleanup();
        return nullptr;
    }

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer;
    const VkResult submitResult = vkQueueSubmit(
        state.computeQueue, 1, &submitInfo, state.submissionFence);
    if (!check(submitResult, "vkQueueSubmit(hdr)")) {
        cleanup();
        state.status = "HDR GPU submission failed";
        if (submitResult == VK_ERROR_DEVICE_LOST) {
            state.supported = false;
            destroyEngine(&state);
        }
        return nullptr;
    }
    const VkResult waitResult = vkWaitForFences(
        state.device, 1, &state.submissionFence, VK_TRUE, kHdrMergeGpuDeadlineNs);
    if (waitResult != VK_SUCCESS && waitResult != VK_ERROR_DEVICE_LOST) {
        // Preserve every object referenced by the queued command until a later call observes the
        // fence. Timeout is expected under load; an OOM/other wait error likewise does not prove that
        // execution ended. The caller can use its existing CPU fallback without an ANR or unsafe free.
        state.submissionPending = true;
        state.pendingInputBuffer = inputBuffer;
        state.pendingInputMemory = inputMemory;
        state.pendingOutputBuffer = outputBuffer;
        state.pendingOutputMemory = outputMemory;
        state.pendingDescriptorPool = descriptorPool;
        inputBuffer = VK_NULL_HANDLE;
        inputMemory = VK_NULL_HANDLE;
        outputBuffer = VK_NULL_HANDLE;
        outputMemory = VK_NULL_HANDLE;
        descriptorPool = VK_NULL_HANDLE;
        descriptorSet = VK_NULL_HANDLE;
        state.status = waitResult == VK_TIMEOUT
            ? "HDR GPU merge deadline exceeded; CPU fallback active"
            : "HDR GPU completion unavailable; resources retained for safe retry";
        HDR_LOGW("%s", state.status.c_str());
        return nullptr;
    }
    if (waitResult == VK_ERROR_DEVICE_LOST) {
        check(waitResult, "vkWaitForFences(hdr)");
        cleanup();
        state.status = "HDR GPU completion fence failed";
        state.supported = false;
        destroyEngine(&state);
        return nullptr;
    }
    state.status = "ready";

    std::vector<uint32_t> outputPixels(pixelCount);
    if (!check(vkMapMemory(state.device, outputMemory, 0, outputSize, 0, &mapped), "vkMapMemory(outputHdr)")) {
        cleanup();
        return nullptr;
    }
    std::memcpy(outputPixels.data(), mapped, static_cast<size_t>(outputSize));
    vkUnmapMemory(state.device, outputMemory);

    outputArray = env->NewIntArray(static_cast<jsize>(outputPixels.size()));
    if (outputArray != nullptr) {
        env->SetIntArrayRegion(
            outputArray,
            0,
            static_cast<jsize>(outputPixels.size()),
            reinterpret_cast<const jint*>(outputPixels.data())
        );
    }

    cleanup();
    return outputArray;
}

// #13 fix: the Kotlin layer (HdrMergeNative.mergeWithQuality) ONLY ever calls this Advanced entry point.
// It previously had NO native definition, so the first HDR still threw UnsatisfiedLinkError, which runCatching
// swallowed → EVERY HDR capture silently used the CPU software stack and the GPU merge never ran. This defines
// the export and delegates to the proven GPU merge above (same initializeEngine/createBuffer/dispatch, with its
// full init guards → null → clean CPU fallback on unsupported devices), mapping the quality tier onto the
// parameter the current merge shader honors: FAST → fast approximation; BALANCED/CINEMA/ULTRA → the
// full-quality GPU merge (a strict-high-end build already forces full quality internally).
// enableDenoising / enableAdvancedAlignment are accepted for forward compatibility; their dedicated shader
// passes are a future addition that must be device-verified before they alter output, so until then the
// full-quality GPU merge runs — which is the intended quality win over the CPU fallback, with no new
// unverified GPU code introduced here.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_neoproc_neoproccamx_capture_HdrMergeNative_nativeMergeHdrBitmapsAdvanced(
    JNIEnv* env,
    jobject thiz,
    jobjectArray bitmaps,
    jfloatArray exposureScales,
    jfloatArray normalizationFactors,
    jboolean fastApproximation,
    jboolean enableDenoising,
    jboolean enableAdvancedAlignment,
    jint qualityLevel
) {
    (void) enableDenoising;
    (void) enableAdvancedAlignment;
    // qualityLevel ordinal: FAST=0, BALANCED=1, CINEMA_QUALITY=2, ULTRA_QUALITY=3. Only FAST uses the
    // approximation path; every higher tier runs the full-quality GPU merge.
    const jboolean effectiveFastApproximation =
        (fastApproximation == JNI_TRUE && qualityLevel <= 0) ? JNI_TRUE : JNI_FALSE;
    return Java_com_neoproc_neoproccamx_capture_HdrMergeNative_nativeMergeHdrBitmaps(
        env,
        thiz,
        bitmaps,
        exposureScales,
        normalizationFactors,
        effectiveFastApproximation
    );
}
