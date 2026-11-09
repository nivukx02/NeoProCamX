#include <jni.h>

#include <android/log.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <limits>
#include <mutex>
#include <string>
#include <vector>

#include "generated/neo_p010_export_enhance_comp_spv.h"
#include "generated/npv10_export_grade_lut_comp_spv.h"
#include "generated/record_develop_rgb10_pack_comp_spv.h"
#include "generated/record_develop_p010_to_rgb10_comp_spv.h"
#include "generated/record_parity_enhance_p010_comp_spv.h"
#include "generated/record_develop_enhance_lane_p010_comp_spv.h"

#define NPV10_LOG_TAG "CamXNpv10Gpu"
#define NPV10_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, NPV10_LOG_TAG, __VA_ARGS__)
#define NPV10_LOGW(...) __android_log_print(ANDROID_LOG_WARN, NPV10_LOG_TAG, __VA_ARGS__)
#define NPV10_LOGI(...) __android_log_print(ANDROID_LOG_INFO, NPV10_LOG_TAG, __VA_ARGS__)

namespace {

// The preview and NPV10 paths share one packet ABI. The compute shader consumes
// the first 40 floats; the subject-mask tail is reserved until export receives
// a real per-frame mask stream.
constexpr uint32_t kUniformFloatCount = 44u;
constexpr uint32_t kMaximumGradeLutEdge = 65u;
// 64 scalar develop params + a baked 33^3 film-print LUT (33*33*33*3 = 107811 floats) appended at index
// 64 so the develop shader can tetrahedral-sample the SAME LUT the CPU golden path uses (record-develop
// SDR film-print parity). Scalars 43..52 hold the canonical calibrated NDR profile. The LUT tail is zero
// and p[42]=0 selects the analytic fallback when the film
// look is off or the target is NDR. This grows binding-3 (a STORAGE buffer) to ~431 KiB — well within
// maxStorageBufferRange; the buffer size, descriptor range, and JNI copy length all derive from here.
constexpr uint32_t kRecordDevelopParamFloatCount = 64u + 33u * 33u * 33u * 3u;
constexpr uint32_t kRecordDevelopPackNone = std::numeric_limits<uint32_t>::max();
constexpr uint32_t kRecordDevelopPackR210 = 0u;
constexpr uint32_t kRecordDevelopPackV210 = 1u;
constexpr uint32_t kRecordDevelopPackP010Bt2020 = 2u;
constexpr uint32_t kRecordDevelopPackP010Bt709 = 3u;
constexpr jint kRecordDevelopOutputR210 = 0;
constexpr jint kRecordDevelopOutputV210 = 1;
// Live-cinema parity luma enhance (GPU port of enhanceLiveCinemaLumaP010): 2 scalar uniforms
// (uSharpness, uDenoise). Its dispatch uses a BOUNDED fence timeout so a hung/TDR'd offline compute
// queue degrades to the CPU parity pass instead of blocking the conversion worker forever.
constexpr uint32_t kParityEnhanceUniformFloatCount = 2u;
constexpr uint64_t kParityEnhanceFenceTimeoutNs = 4000000000ULL; // 4s ceiling (a 4K compute is < 100ms)
// #5/#16: disable the whole GPU engine only after this many CONSECUTIVE bounded-fence-wait timeouts, so a
// single transient GPU stall degrades just the affected frame to the byte-exact CPU path and the engine
// recovers on the next success — instead of collapsing GPU acceleration for the rest of the process.
constexpr int kMaxConsecutiveFenceTimeouts = 3;
// Record-develop enhance lane (chroma-NR + temporal + deadband + spike + dither) GPU port. 7 scalar
// uniforms; 5 sequential passes ping-pong current<->output between per-pass bounded submits.
constexpr uint32_t kEnhanceLaneUniformFloatCount = 7u;
constexpr uint32_t kEnhanceLanePassTemporal = 0u;
constexpr uint32_t kEnhanceLanePassChromaNr = 1u;
constexpr uint32_t kEnhanceLanePassDeadband = 2u;
constexpr uint32_t kEnhanceLanePassSpike = 3u;
constexpr uint32_t kEnhanceLanePassDither = 4u;

// JNI result codes for the live P010 history path. A positive value always means the output buffer
// contains a completed GPU frame; only kLiveEnhanceTemporal proves that a prior source was consumed.
constexpr jint kLiveEnhanceFailed = 0;
constexpr jint kLiveEnhanceSeeded = 1;
constexpr jint kLiveEnhanceTemporal = 2;
constexpr jint kLiveEnhanceResetSession = 3;
constexpr jint kLiveEnhanceResetGeometry = 4;
constexpr jint kLiveEnhanceResetTimestamp = 5;

struct Npv10PushConstants {
    uint32_t width;
    uint32_t height;
    uint32_t hasPreviousFrame;
    // Multi-pass selector for the record-develop enhance lane (record_develop_enhance_lane_p010.comp).
    // Existing 3-field shaders ignore this trailing word; every existing 3-arg brace-init value-
    // initializes it to 0 (aggregate init), so no existing call site changes behaviour.
    uint32_t pass;
};

struct HostVisibleBuffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize capacity = 0;
    void* mapped = nullptr;
};

struct FrameSlot {
    HostVisibleBuffer currentBuffer;
    HostVisibleBuffer outputBuffer;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
};

struct Npv10GpuEngine {
    std::mutex mutex;
    bool initialized = false;
    bool supported = false;
    // Build independent pipeline families only when their consumer asks for them. A record-develop
    // conversion must not synchronously compile the much larger NPV10 export shader (and vice versa).
    // Both families still share one Vulkan device/layout and retain the existing parity/fallback gates.
    bool exportPipelineInitializationAttempted = false;
    bool recordDevelopPipelineInitializationAttempted = false;
    std::string status = "uninitialized";
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex = 0;
    VkQueue computeQueue = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkPipeline gradeLutPipeline = VK_NULL_HANDLE;
    VkPipeline developPipeline = VK_NULL_HANDLE;
    VkPipeline developPackPipeline = VK_NULL_HANDLE;
    VkPipeline parityEnhancePipeline = VK_NULL_HANDLE;
    VkShaderModule shader = VK_NULL_HANDLE;
    VkShaderModule gradeLutShader = VK_NULL_HANDLE;
    VkShaderModule developShader = VK_NULL_HANDLE;
    VkShaderModule developPackShader = VK_NULL_HANDLE;
    VkShaderModule parityEnhanceShader = VK_NULL_HANDLE;
    VkPipeline enhanceLanePipeline = VK_NULL_HANDLE;
    VkShaderModule enhanceLaneShader = VK_NULL_HANDLE;
    // OPTIONAL lane: failure to build it NEVER disables the engine (enhance + develop stay ready).
    bool parityEnhanceSupported = false;
    bool enhanceLaneSupported = false;
    bool developPackSupported = false;
    bool gradeLutSupported = false;
    // #5/#16: consecutive bounded-fence-wait timeout streak (see waitFenceBounded). Reset to 0 on any success.
    int consecutiveFenceTimeouts = 0;
    FrameSlot slots[2];
    HostVisibleBuffer previousBuffer;
    // Dedicated live-recording temporal source. It is intentionally separate from previousBuffer:
    // offline export/develop calls share that scratch buffer and must never contaminate a live session.
    HostVisibleBuffer livePreviousBuffer;
    bool liveHistoryValid = false;
    int64_t liveHistorySessionGeneration = 0;
    int64_t liveHistoryTimestampNs = 0;
    uint32_t liveHistoryWidth = 0;
    uint32_t liveHistoryHeight = 0;
    VkDeviceSize liveHistoryBytes = 0;
    HostVisibleBuffer uniformBuffer;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    uint64_t frameSerial = 0;
};

enum class Npv10PipelineRole {
    EXPORT_ENHANCE,
    RECORD_DEVELOP
};

Npv10GpuEngine& engine() {
    static Npv10GpuEngine instance;
    return instance;
}

uint64_t monotonicMillis() {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()
        ).count()
    );
}

bool validateGradeLut(
    JNIEnv* env,
    jfloatArray gradeLut,
    jint gradeLutSize,
    uint32_t* resolvedSize,
    uint32_t* resolvedFloatCount
) {
    if (resolvedSize == nullptr || resolvedFloatCount == nullptr) return false;
    *resolvedSize = 0u;
    *resolvedFloatCount = 0u;
    if (gradeLut == nullptr) return gradeLutSize == 0;
    if (gradeLutSize < 2 || static_cast<uint32_t>(gradeLutSize) > kMaximumGradeLutEdge) return false;
    const uint64_t edge = static_cast<uint64_t>(gradeLutSize);
    const uint64_t floatCount = edge * edge * edge * 3u;
    if (floatCount > static_cast<uint64_t>(std::numeric_limits<jsize>::max()) ||
        env->GetArrayLength(gradeLut) != static_cast<jsize>(floatCount)) {
        return false;
    }
    *resolvedSize = static_cast<uint32_t>(gradeLutSize);
    *resolvedFloatCount = static_cast<uint32_t>(floatCount);
    return true;
}

bool check(VkResult result, const char* label) {
    if (result == VK_SUCCESS) return true;
    NPV10_LOGE("%s failed with %d", label, static_cast<int>(result));
    return false;
}

// #5/#16: bounded fence wait. Replaces the UINT64_MAX waits that permanently hung the offline convert (while
// holding the engine mutex) on a mid-clip GPU TDR/thermal stall — the export bar froze forever and the user
// had to force-kill. On VK_TIMEOUT the affected frame falls back to the byte-exact CPU path; the WHOLE engine
// is disabled (supported=false) only after kMaxConsecutiveFenceTimeouts consecutive timeouts, so a single
// transient stall recovers on the next success (which resets the streak) instead of poisoning the whole
// process (#16). A hard error (e.g. VK_ERROR_DEVICE_LOST) disables immediately. Returns true only when the
// fence actually signalled.
bool waitFenceBounded(Npv10GpuEngine* state, VkFence fence, uint64_t timeoutNs, const char* label) {
    const VkResult result = vkWaitForFences(state->device, 1, &fence, VK_TRUE, timeoutNs);
    if (result == VK_SUCCESS) {
        state->consecutiveFenceTimeouts = 0;
        return true;
    }
    if (result == VK_TIMEOUT) {
        const int streak = ++state->consecutiveFenceTimeouts;
        if (streak >= kMaxConsecutiveFenceTimeouts) {
            state->status = std::string(label) +
                " fence timeout streak — GPU engine disabled (byte-exact CPU fallback)";
            state->supported = false;
            state->parityEnhanceSupported = false;
        } else {
            state->status = std::string(label) + " fence timeout — frame routed to CPU fallback";
        }
        NPV10_LOGE("%s timed out (streak=%d)", label, streak);
        return false;
    }
    state->status = std::string(label) + " fence wait failed";
    state->supported = false;
    state->parityEnhanceSupported = false;
    NPV10_LOGE("%s failed with %d", label, static_cast<int>(result));
    return false;
}

bool findMemoryType(
    VkPhysicalDevice physicalDevice,
    uint32_t typeBits,
    VkMemoryPropertyFlags properties,
    uint32_t* memoryTypeIndex
) {
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties);
    for (uint32_t index = 0; index < memoryProperties.memoryTypeCount; ++index) {
        const bool typeMatch = (typeBits & (1u << index)) != 0u;
        const bool propertyMatch =
            (memoryProperties.memoryTypes[index].propertyFlags & properties) == properties;
        if (typeMatch && propertyMatch) {
            *memoryTypeIndex = index;
            return true;
        }
    }
    return false;
}

VkShaderModule createShaderModule(VkDevice device, const uint32_t* code, size_t wordCount) {
    VkShaderModuleCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    createInfo.codeSize = wordCount * sizeof(uint32_t);
    createInfo.pCode = code;
    VkShaderModule shader = VK_NULL_HANDLE;
    if (!check(vkCreateShaderModule(device, &createInfo, nullptr, &shader), "vkCreateShaderModule(npv10)")) {
        return VK_NULL_HANDLE;
    }
    return shader;
}

void destroyHostBuffer(VkDevice device, HostVisibleBuffer* buffer) {
    if (buffer == nullptr) return;
    if (buffer->mapped != nullptr && buffer->memory != VK_NULL_HANDLE) {
        vkUnmapMemory(device, buffer->memory);
        buffer->mapped = nullptr;
    }
    if (buffer->buffer != VK_NULL_HANDLE) {
        vkDestroyBuffer(device, buffer->buffer, nullptr);
        buffer->buffer = VK_NULL_HANDLE;
    }
    if (buffer->memory != VK_NULL_HANDLE) {
        vkFreeMemory(device, buffer->memory, nullptr);
        buffer->memory = VK_NULL_HANDLE;
    }
    buffer->capacity = 0;
}

void destroyFrameResources(Npv10GpuEngine* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return;
    for (FrameSlot& slot : state->slots) {
        if (slot.fence != VK_NULL_HANDLE) {
            vkDestroyFence(state->device, slot.fence, nullptr);
            slot.fence = VK_NULL_HANDLE;
        }
        if (slot.commandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(state->device, slot.commandPool, nullptr);
            slot.commandPool = VK_NULL_HANDLE;
            slot.commandBuffer = VK_NULL_HANDLE;
        }
        destroyHostBuffer(state->device, &slot.currentBuffer);
        destroyHostBuffer(state->device, &slot.outputBuffer);
        slot.descriptorSet = VK_NULL_HANDLE;
    }
    if (state->descriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(state->device, state->descriptorPool, nullptr);
        state->descriptorPool = VK_NULL_HANDLE;
    }
    destroyHostBuffer(state->device, &state->previousBuffer);
    destroyHostBuffer(state->device, &state->livePreviousBuffer);
    state->liveHistoryValid = false;
    state->liveHistorySessionGeneration = 0;
    state->liveHistoryTimestampNs = 0;
    state->liveHistoryWidth = 0;
    state->liveHistoryHeight = 0;
    state->liveHistoryBytes = 0;
    destroyHostBuffer(state->device, &state->uniformBuffer);
}

void destroyEngine(Npv10GpuEngine* state) {
    if (state == nullptr) return;
    if (state->device != VK_NULL_HANDLE) {
        destroyFrameResources(state);
        if (state->pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(state->device, state->pipeline, nullptr);
            state->pipeline = VK_NULL_HANDLE;
        }
        if (state->gradeLutPipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(state->device, state->gradeLutPipeline, nullptr);
            state->gradeLutPipeline = VK_NULL_HANDLE;
        }
        state->gradeLutSupported = false;
        if (state->developPipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(state->device, state->developPipeline, nullptr);
            state->developPipeline = VK_NULL_HANDLE;
        }
        if (state->developPackPipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(state->device, state->developPackPipeline, nullptr);
            state->developPackPipeline = VK_NULL_HANDLE;
        }
        if (state->parityEnhancePipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(state->device, state->parityEnhancePipeline, nullptr);
            state->parityEnhancePipeline = VK_NULL_HANDLE;
        }
        if (state->enhanceLanePipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(state->device, state->enhanceLanePipeline, nullptr);
            state->enhanceLanePipeline = VK_NULL_HANDLE;
        }
        if (state->shader != VK_NULL_HANDLE) {
            vkDestroyShaderModule(state->device, state->shader, nullptr);
            state->shader = VK_NULL_HANDLE;
        }
        if (state->gradeLutShader != VK_NULL_HANDLE) {
            vkDestroyShaderModule(state->device, state->gradeLutShader, nullptr);
            state->gradeLutShader = VK_NULL_HANDLE;
        }
        if (state->developShader != VK_NULL_HANDLE) {
            vkDestroyShaderModule(state->device, state->developShader, nullptr);
            state->developShader = VK_NULL_HANDLE;
        }
        if (state->developPackShader != VK_NULL_HANDLE) {
            vkDestroyShaderModule(state->device, state->developPackShader, nullptr);
            state->developPackShader = VK_NULL_HANDLE;
        }
        if (state->parityEnhanceShader != VK_NULL_HANDLE) {
            vkDestroyShaderModule(state->device, state->parityEnhanceShader, nullptr);
            state->parityEnhanceShader = VK_NULL_HANDLE;
        }
        if (state->enhanceLaneShader != VK_NULL_HANDLE) {
            vkDestroyShaderModule(state->device, state->enhanceLaneShader, nullptr);
            state->enhanceLaneShader = VK_NULL_HANDLE;
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
    state->supported = false;
    state->initialized = false;
    state->exportPipelineInitializationAttempted = false;
    state->recordDevelopPipelineInitializationAttempted = false;
    state->parityEnhanceSupported = false;
    state->enhanceLaneSupported = false;
    state->developPackSupported = false;
}

bool initializeBaseEngine(Npv10GpuEngine* state) {
    if (state->initialized) return state->supported;
    state->initialized = true;

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "NeoProCamX NPV10 Export";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "NeoP010ExportCompute";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &appInfo;
    if (!check(vkCreateInstance(&instanceInfo, nullptr, &state->instance), "vkCreateInstance(npv10)")) {
        state->status = "instance creation failed";
        return false;
    }

    uint32_t deviceCount = 0;
    if (!check(vkEnumeratePhysicalDevices(state->instance, &deviceCount, nullptr), "vkEnumeratePhysicalDevices(npv10-count)") ||
        deviceCount == 0) {
        state->status = "no Vulkan physical device";
        destroyEngine(state);
        return false;
    }
    std::vector<VkPhysicalDevice> devices(deviceCount);
    if (!check(vkEnumeratePhysicalDevices(state->instance, &deviceCount, devices.data()), "vkEnumeratePhysicalDevices(npv10-list)")) {
        state->status = "physical device enumeration failed";
        destroyEngine(state);
        return false;
    }
    state->physicalDevice = devices.front();

    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(state->physicalDevice, &queueFamilyCount, nullptr);
    if (queueFamilyCount == 0) {
        state->status = "no Vulkan queue families";
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
        state->status = "no compute-capable queue";
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
    if (!check(vkCreateDevice(state->physicalDevice, &deviceInfo, nullptr, &state->device), "vkCreateDevice(npv10)")) {
        state->status = "device creation failed";
        destroyEngine(state);
        return false;
    }
    vkGetDeviceQueue(state->device, state->queueFamilyIndex, 0, &state->computeQueue);

    std::array<VkDescriptorSetLayoutBinding, 4> bindings{};
    for (uint32_t index = 0; index < bindings.size(); ++index) {
        bindings[index].binding = index;
        bindings[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[index].descriptorCount = 1;
        bindings[index].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo setLayoutInfo{};
    setLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setLayoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
    setLayoutInfo.pBindings = bindings.data();
    if (!check(vkCreateDescriptorSetLayout(state->device, &setLayoutInfo, nullptr, &state->descriptorSetLayout), "vkCreateDescriptorSetLayout(npv10)")) {
        state->status = "descriptor set layout failed";
        destroyEngine(state);
        return false;
    }

    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0;
    pushRange.size = sizeof(Npv10PushConstants);

    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &state->descriptorSetLayout;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushRange;
    if (!check(vkCreatePipelineLayout(state->device, &pipelineLayoutInfo, nullptr, &state->pipelineLayout), "vkCreatePipelineLayout(npv10)")) {
        state->status = "pipeline layout failed";
        destroyEngine(state);
        return false;
    }

    state->supported = true;
    state->status = "base ready";
    return true;
}

bool initializeExportPipeline(Npv10GpuEngine* state) {
    if (state->pipeline != VK_NULL_HANDLE) return true;
    if (state->exportPipelineInitializationAttempted) return false;
    state->exportPipelineInitializationAttempted = true;

    state->shader = createShaderModule(
        state->device,
        kNeoP010ExportEnhanceCompSpv,
        kNeoP010ExportEnhanceCompSpvCount
    );
    if (state->shader == VK_NULL_HANDLE) {
        state->status = "shader module failed";
        return false;
    }

    VkPipelineShaderStageCreateInfo shaderStage{};
    shaderStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    shaderStage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    shaderStage.module = state->shader;
    shaderStage.pName = "main";

    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = shaderStage;
    pipelineInfo.layout = state->pipelineLayout;
    if (!check(vkCreateComputePipelines(state->device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &state->pipeline), "vkCreateComputePipelines(npv10)")) {
        state->status = "compute pipeline failed";
        vkDestroyShaderModule(state->device, state->shader, nullptr);
        state->shader = VK_NULL_HANDLE;
        return false;
    }

    // Optional second pass for SDR creative grading. It consumes the enhanced P010 buffer and writes
    // the fully graded P010 frame back into the source slot. A missing/rejected module is surfaced to
    // the caller only when a LUT is requested, preserving the exact CPU grade as the fail-safe path.
    state->gradeLutShader = createShaderModule(
        state->device,
        kNpv10ExportGradeLutCompSpv,
        kNpv10ExportGradeLutCompSpvCount
    );
    if (state->gradeLutShader != VK_NULL_HANDLE) {
        VkPipelineShaderStageCreateInfo gradeStage{};
        gradeStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        gradeStage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        gradeStage.module = state->gradeLutShader;
        gradeStage.pName = "main";
        VkComputePipelineCreateInfo gradePipelineInfo{};
        gradePipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        gradePipelineInfo.stage = gradeStage;
        gradePipelineInfo.layout = state->pipelineLayout;
        if (vkCreateComputePipelines(
                state->device,
                VK_NULL_HANDLE,
                1,
                &gradePipelineInfo,
                nullptr,
                &state->gradeLutPipeline
            ) == VK_SUCCESS) {
            state->gradeLutSupported = true;
        } else {
            state->gradeLutPipeline = VK_NULL_HANDLE;
            NPV10_LOGW("NPV10 GPU grade-LUT pipeline unavailable; graded exports retain CPU fallback");
        }
    } else {
        NPV10_LOGW("NPV10 GPU grade-LUT shader unavailable; graded exports retain CPU fallback");
    }

    state->status = "ready";
    NPV10_LOGI("NPV10 Vulkan export-enhance compute pipeline initialized");
    return true;
}

bool initializeRecordDevelopPipelines(Npv10GpuEngine* state) {
    if (state->developPipeline != VK_NULL_HANDLE) return true;
    if (state->recordDevelopPipelineInitializationAttempted) return false;
    state->recordDevelopPipelineInitializationAttempted = true;

    state->developShader = createShaderModule(
        state->device,
        kRecordDevelopP010ToRgb10CompSpv,
        kRecordDevelopP010ToRgb10CompSpvCount
    );
    if (state->developShader == VK_NULL_HANDLE) {
        state->status = "record-develop shader module failed";
        return false;
    }
    VkPipelineShaderStageCreateInfo developShaderStage{};
    developShaderStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    developShaderStage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    developShaderStage.module = state->developShader;
    developShaderStage.pName = "main";
    VkComputePipelineCreateInfo developPipelineInfo{};
    developPipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    developPipelineInfo.stage = developShaderStage;
    developPipelineInfo.layout = state->pipelineLayout;
    if (!check(vkCreateComputePipelines(state->device, VK_NULL_HANDLE, 1, &developPipelineInfo, nullptr, &state->developPipeline), "vkCreateComputePipelines(record-develop)")) {
        state->status = "record-develop compute pipeline failed";
        vkDestroyShaderModule(state->device, state->developShader, nullptr);
        state->developShader = VK_NULL_HANDLE;
        return false;
    }

    state->developPackShader = createShaderModule(
        state->device,
        kRecordDevelopRgb10PackCompSpv,
        kRecordDevelopRgb10PackCompSpvCount
    );
    if (state->developPackShader != VK_NULL_HANDLE) {
        VkPipelineShaderStageCreateInfo packShaderStage{};
        packShaderStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        packShaderStage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        packShaderStage.module = state->developPackShader;
        packShaderStage.pName = "main";
        VkComputePipelineCreateInfo packPipelineInfo{};
        packPipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        packPipelineInfo.stage = packShaderStage;
        packPipelineInfo.layout = state->pipelineLayout;
        if (vkCreateComputePipelines(
                state->device, VK_NULL_HANDLE, 1, &packPipelineInfo, nullptr, &state->developPackPipeline
            ) == VK_SUCCESS) {
            state->developPackSupported = true;
        } else {
            NPV10_LOGW("Record-develop GPU pack pipeline unavailable; packed output stays CPU-safe");
            state->developPackPipeline = VK_NULL_HANDLE;
        }
    } else {
        NPV10_LOGW("Record-develop GPU pack shader unavailable; packed output stays CPU-safe");
    }

    // OPTIONAL live-cinema parity luma-enhance pipeline. It reuses the exact same descriptor/push ABI
    // and pipeline layout as the enhance/develop pipelines. Its creation is best-effort: if this device's
    // driver rejects the shader, we log and leave parityEnhanceSupported=false WITHOUT touching
    // state->supported / state->status — the enhance + develop GPU lanes (already committed above) keep
    // working, and the parity-enhance lane simply falls back to the byte-exact CPU pass. This is the
    // no-downgrade guarantee: a new-shader failure can never regress the shipping develop lane.
    state->parityEnhanceShader = createShaderModule(
        state->device,
        kRecordParityEnhanceP010CompSpv,
        kRecordParityEnhanceP010CompSpvCount
    );
    if (state->parityEnhanceShader != VK_NULL_HANDLE) {
        VkPipelineShaderStageCreateInfo parityStage{};
        parityStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        parityStage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        parityStage.module = state->parityEnhanceShader;
        parityStage.pName = "main";
        VkComputePipelineCreateInfo parityPipelineInfo{};
        parityPipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        parityPipelineInfo.stage = parityStage;
        parityPipelineInfo.layout = state->pipelineLayout;
        if (vkCreateComputePipelines(
                state->device, VK_NULL_HANDLE, 1, &parityPipelineInfo, nullptr, &state->parityEnhancePipeline
            ) == VK_SUCCESS) {
            state->parityEnhanceSupported = true;
        } else {
            NPV10_LOGW("NPV10 parity-enhance pipeline unavailable; parity enhance stays on CPU");
            state->parityEnhancePipeline = VK_NULL_HANDLE;
        }
    } else {
        NPV10_LOGW("NPV10 parity-enhance shader module unavailable; parity enhance stays on CPU");
    }

    // OPTIONAL record-develop CHROMA enhance lane (chroma-NR + temporal + deadband + spike + dither).
    // Reuses the exact shared descriptor/push ABI + pipeline layout. Best-effort like the parity lane:
    // a build failure leaves enhanceLaneSupported=false WITHOUT touching state->supported/status, so the
    // develop lane keeps working and the chroma enhance simply stays on the byte-exact CPU path.
    state->enhanceLaneShader = createShaderModule(
        state->device,
        kRecordDevelopEnhanceLaneP010CompSpv,
        kRecordDevelopEnhanceLaneP010CompSpvCount
    );
    if (state->enhanceLaneShader != VK_NULL_HANDLE) {
        VkPipelineShaderStageCreateInfo enhanceLaneStage{};
        enhanceLaneStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        enhanceLaneStage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        enhanceLaneStage.module = state->enhanceLaneShader;
        enhanceLaneStage.pName = "main";
        VkComputePipelineCreateInfo enhanceLanePipelineInfo{};
        enhanceLanePipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        enhanceLanePipelineInfo.stage = enhanceLaneStage;
        enhanceLanePipelineInfo.layout = state->pipelineLayout;
        if (vkCreateComputePipelines(
                state->device, VK_NULL_HANDLE, 1, &enhanceLanePipelineInfo, nullptr, &state->enhanceLanePipeline
            ) == VK_SUCCESS) {
            state->enhanceLaneSupported = true;
        } else {
            NPV10_LOGW("Record-develop enhance-lane pipeline unavailable; chroma enhance stays on CPU");
            state->enhanceLanePipeline = VK_NULL_HANDLE;
        }
    } else {
        NPV10_LOGW("Record-develop enhance-lane shader module unavailable; chroma enhance stays on CPU");
    }

    state->status = "ready";
    NPV10_LOGI(
        "Record-develop Vulkan compute pipelines initialized packGpu=%s parityEnhanceGpu=%s enhanceLaneGpu=%s",
        state->developPackSupported ? "true" : "false",
        state->parityEnhanceSupported ? "true" : "false",
        state->enhanceLaneSupported ? "true" : "false"
    );
    return true;
}

bool initializeEngine(Npv10GpuEngine* state, Npv10PipelineRole role) {
    if (!initializeBaseEngine(state)) return false;
    const bool ready = role == Npv10PipelineRole::EXPORT_ENHANCE
        ? initializeExportPipeline(state)
        : initializeRecordDevelopPipelines(state);
    if (!ready) return false;
    state->status = "ready";
    return true;
}

bool createHostBuffer(
    Npv10GpuEngine* state,
    VkDeviceSize size,
    HostVisibleBuffer* target
) {
    const uint64_t startedMs = monotonicMillis();
    NPV10_LOGI("NPV10_RESOURCE_BEGIN bytes=%llu", static_cast<unsigned long long>(size));
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = size;
    bufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!check(vkCreateBuffer(state->device, &bufferInfo, nullptr, &target->buffer), "vkCreateBuffer(npv10)")) {
        return false;
    }
    NPV10_LOGI(
        "NPV10_RESOURCE_BUFFER_CREATED bytes=%llu elapsedMs=%llu",
        static_cast<unsigned long long>(size),
        static_cast<unsigned long long>(monotonicMillis() - startedMs)
    );
    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(state->device, target->buffer, &requirements);
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = requirements.size;
    if (!findMemoryType(
            state->physicalDevice,
            requirements.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            &allocInfo.memoryTypeIndex
        )) {
        state->status = "host visible coherent Vulkan memory unavailable";
        vkDestroyBuffer(state->device, target->buffer, nullptr);
        target->buffer = VK_NULL_HANDLE;
        return false;
    }
    if (!check(vkAllocateMemory(state->device, &allocInfo, nullptr, &target->memory), "vkAllocateMemory(npv10)")) {
        vkDestroyBuffer(state->device, target->buffer, nullptr);
        target->buffer = VK_NULL_HANDLE;
        return false;
    }
    NPV10_LOGI(
        "NPV10_RESOURCE_MEMORY_ALLOCATED requestedBytes=%llu allocationBytes=%llu elapsedMs=%llu",
        static_cast<unsigned long long>(size),
        static_cast<unsigned long long>(requirements.size),
        static_cast<unsigned long long>(monotonicMillis() - startedMs)
    );
    if (!check(vkBindBufferMemory(state->device, target->buffer, target->memory, 0), "vkBindBufferMemory(npv10)")) {
        destroyHostBuffer(state->device, target);
        return false;
    }
    target->capacity = size;
    if (!check(vkMapMemory(state->device, target->memory, 0, VK_WHOLE_SIZE, 0, &target->mapped), "vkMapMemory(npv10-persistent)")) {
        destroyHostBuffer(state->device, target);
        return false;
    }
    NPV10_LOGI(
        "NPV10_RESOURCE_READY bytes=%llu elapsedMs=%llu",
        static_cast<unsigned long long>(size),
        static_cast<unsigned long long>(monotonicMillis() - startedMs)
    );
    return true;
}

bool copyToMappedMemory(HostVisibleBuffer* target, const void* source, VkDeviceSize size) {
    if (target == nullptr || target->mapped == nullptr || target->capacity < size) return false;
    std::memcpy(target->mapped, source, static_cast<size_t>(size));
    return true;
}

void zeroMappedTail(HostVisibleBuffer* target, VkDeviceSize validBytes, VkDeviceSize paddedBytes) {
    if (target == nullptr || target->mapped == nullptr || paddedBytes <= validBytes) return;
    auto* bytes = static_cast<uint8_t*>(target->mapped);
    std::memset(bytes + validBytes, 0, static_cast<size_t>(paddedBytes - validBytes));
}

bool ensureFrameResources(
    Npv10GpuEngine* state,
    VkDeviceSize currentPayloadBytes,
    VkDeviceSize outputPayloadBytes,
    VkDeviceSize uniformBytes,
    VkDeviceSize previousPayloadBytes
) {
    bool slotsReady = true;
    for (const FrameSlot& slot : state->slots) {
        slotsReady = slotsReady &&
            slot.currentBuffer.capacity >= currentPayloadBytes &&
            slot.currentBuffer.mapped != nullptr &&
            slot.outputBuffer.capacity >= outputPayloadBytes &&
            slot.outputBuffer.mapped != nullptr &&
            slot.descriptorSet != VK_NULL_HANDLE &&
            slot.commandPool != VK_NULL_HANDLE &&
            slot.commandBuffer != VK_NULL_HANDLE &&
            slot.fence != VK_NULL_HANDLE;
    }
    const bool resourcesReady =
        slotsReady &&
        state->previousBuffer.capacity >= previousPayloadBytes &&
        state->previousBuffer.mapped != nullptr &&
        state->uniformBuffer.capacity >= uniformBytes &&
        state->uniformBuffer.mapped != nullptr &&
        state->descriptorPool != VK_NULL_HANDLE;
    if (resourcesReady) return true;

    const uint64_t startedMs = monotonicMillis();
    NPV10_LOGI(
        "NPV10_RESOURCES_REBUILD_BEGIN current=%llu output=%llu previous=%llu uniforms=%llu",
        static_cast<unsigned long long>(currentPayloadBytes),
        static_cast<unsigned long long>(outputPayloadBytes),
        static_cast<unsigned long long>(previousPayloadBytes),
        static_cast<unsigned long long>(uniformBytes)
    );

    destroyFrameResources(state);
    for (FrameSlot& slot : state->slots) {
        if (!createHostBuffer(state, currentPayloadBytes, &slot.currentBuffer) ||
            !createHostBuffer(state, outputPayloadBytes, &slot.outputBuffer)) {
            if (state->status == "ready") {
                state->status = "frame slot buffer allocation failed";
            }
            destroyFrameResources(state);
            return false;
        }
    }
    if (!createHostBuffer(state, previousPayloadBytes, &state->previousBuffer) ||
        !createHostBuffer(state, uniformBytes, &state->uniformBuffer)) {
        if (state->status == "ready") {
            state->status = "frame buffer allocation failed";
        }
        destroyFrameResources(state);
        return false;
    }

    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 8;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    poolInfo.maxSets = 2;
    if (!check(vkCreateDescriptorPool(state->device, &poolInfo, nullptr, &state->descriptorPool), "vkCreateDescriptorPool(npv10)")) {
        state->status = "descriptor pool failed";
        destroyFrameResources(state);
        return false;
    }

    std::array<VkDescriptorSetLayout, 2> layouts{state->descriptorSetLayout, state->descriptorSetLayout};
    std::array<VkDescriptorSet, 2> descriptorSets{};
    VkDescriptorSetAllocateInfo descriptorAlloc{};
    descriptorAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    descriptorAlloc.descriptorPool = state->descriptorPool;
    descriptorAlloc.descriptorSetCount = static_cast<uint32_t>(layouts.size());
    descriptorAlloc.pSetLayouts = layouts.data();
    if (!check(vkAllocateDescriptorSets(state->device, &descriptorAlloc, descriptorSets.data()), "vkAllocateDescriptorSets(npv10)")) {
        state->status = "descriptor allocation failed";
        destroyFrameResources(state);
        return false;
    }
    state->slots[0].descriptorSet = descriptorSets[0];
    state->slots[1].descriptorSet = descriptorSets[1];

    for (FrameSlot& slot : state->slots) {
        VkCommandPoolCreateInfo commandPoolInfo{};
        commandPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        commandPoolInfo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        commandPoolInfo.queueFamilyIndex = state->queueFamilyIndex;
        if (!check(vkCreateCommandPool(state->device, &commandPoolInfo, nullptr, &slot.commandPool), "vkCreateCommandPool(npv10)")) {
            state->status = "command pool failed";
            destroyFrameResources(state);
            return false;
        }

        VkCommandBufferAllocateInfo commandAlloc{};
        commandAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        commandAlloc.commandPool = slot.commandPool;
        commandAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        commandAlloc.commandBufferCount = 1;
        if (!check(vkAllocateCommandBuffers(state->device, &commandAlloc, &slot.commandBuffer), "vkAllocateCommandBuffers(npv10)")) {
            state->status = "command buffer allocation failed";
            destroyFrameResources(state);
            return false;
        }

        VkFenceCreateInfo fenceInfo{};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (!check(vkCreateFence(state->device, &fenceInfo, nullptr, &slot.fence), "vkCreateFence(npv10)")) {
            state->status = "fence creation failed";
            destroyFrameResources(state);
            return false;
        }
    }
    NPV10_LOGI(
        "NPV10_RESOURCES_REBUILD_READY elapsedMs=%llu",
        static_cast<unsigned long long>(monotonicMillis() - startedMs)
    );
    return true;
}

bool ensureLiveHistoryBuffer(Npv10GpuEngine* state, VkDeviceSize payloadBytes) {
    if (state == nullptr || payloadBytes == 0) return false;
    if (state->livePreviousBuffer.mapped != nullptr &&
        state->livePreviousBuffer.capacity >= payloadBytes) {
        return true;
    }
    destroyHostBuffer(state->device, &state->livePreviousBuffer);
    state->liveHistoryValid = false;
    state->liveHistorySessionGeneration = 0;
    state->liveHistoryTimestampNs = 0;
    state->liveHistoryWidth = 0;
    state->liveHistoryHeight = 0;
    state->liveHistoryBytes = 0;
    if (!createHostBuffer(state, payloadBytes, &state->livePreviousBuffer)) {
        if (state->status == "ready") state->status = "live temporal history allocation failed";
        return false;
    }
    return true;
}

bool dispatchEnhanceP010(
    Npv10GpuEngine* state,
    FrameSlot* slot,
    uint32_t safeWidth,
    uint32_t safeHeight,
    VkDeviceSize paddedBytes,
    VkDeviceSize uniformBytes,
    bool hasPrevious,
    HostVisibleBuffer* previousOverride = nullptr,
    uint32_t gradeLutSize = 0u
) {
    if (gradeLutSize > 0u &&
        (!state->gradeLutSupported || state->gradeLutPipeline == VK_NULL_HANDLE)) {
        state->status = "NPV10 GPU grade-LUT pipeline unavailable";
        return false;
    }
    const bool traceColdDispatch = state->frameSerial <= 1u;
    const uint64_t startedMs = traceColdDispatch ? monotonicMillis() : 0u;
    if (traceColdDispatch) NPV10_LOGI("NPV10_DISPATCH_BEGIN width=%u height=%u", safeWidth, safeHeight);
    // B02 — only WAIT here; defer vkResetFences until immediately before the guaranteed submit below.
    // Resetting up-front leaves the fence unsignaled across the descriptor/record steps, so any early
    // return before the submit (command reset/begin/end failure) would strand it unsignaled and the next
    // reuse of this round-robin slot would time out on this line's (now bounded) vkWaitForFences.
    // #5 fix: bounded wait — a hung/TDR'd prior submit routes this frame to CPU instead of deadlocking.
    if (!waitFenceBounded(state, slot->fence, kParityEnhanceFenceTimeoutNs, "npv10-slot-ready")) {
        return false;
    }

    std::array<VkDescriptorBufferInfo, 4> infos{};
    HostVisibleBuffer* temporalInput = previousOverride != nullptr
        ? previousOverride
        : &state->previousBuffer;
    if (temporalInput->buffer == VK_NULL_HANDLE || temporalInput->capacity < paddedBytes) {
        state->status = "temporal input buffer unavailable";
        return false;
    }
    infos[0] = {slot->currentBuffer.buffer, 0, paddedBytes};
    infos[1] = {temporalInput->buffer, 0, paddedBytes};
    infos[2] = {slot->outputBuffer.buffer, 0, paddedBytes};
    infos[3] = {state->uniformBuffer.buffer, 0, uniformBytes};
    std::array<VkWriteDescriptorSet, 4> writes{};
    for (uint32_t index = 0; index < writes.size(); ++index) {
        writes[index].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[index].dstSet = slot->descriptorSet;
        writes[index].dstBinding = index;
        writes[index].descriptorCount = 1;
        writes[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[index].pBufferInfo = &infos[index];
    }
    vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);

    if (!check(vkResetCommandBuffer(slot->commandBuffer, 0), "vkResetCommandBuffer(npv10)")) {
        state->status = "command reset failed";
        return false;
    }

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!check(vkBeginCommandBuffer(slot->commandBuffer, &beginInfo), "vkBeginCommandBuffer(npv10)")) {
        state->status = "command buffer begin failed";
        return false;
    }

    const Npv10PushConstants pushConstants{
        safeWidth,
        safeHeight,
        hasPrevious ? 1u : 0u,
        0u
    };
    vkCmdBindPipeline(slot->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->pipeline);
    vkCmdBindDescriptorSets(
        slot->commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->pipelineLayout,
        0,
        1,
        &slot->descriptorSet,
        0,
        nullptr
    );
    vkCmdPushConstants(
        slot->commandBuffer,
        state->pipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0,
        sizeof(Npv10PushConstants),
        &pushConstants
    );
    vkCmdDispatch(slot->commandBuffer, (safeWidth + 15u) / 16u, (safeHeight + 15u) / 16u, 1u);

    VkMemoryBarrier memoryBarrier{};
    memoryBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    memoryBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    memoryBarrier.dstAccessMask = gradeLutSize > 0u
        ? (VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
        : VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(
        slot->commandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        gradeLutSize > 0u ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_HOST_BIT,
        0,
        1,
        &memoryBarrier,
        0, nullptr,
        0, nullptr
    );

    if (gradeLutSize > 0u) {
        const Npv10PushConstants gradePushConstants{
            safeWidth,
            safeHeight,
            hasPrevious ? 1u : 0u,
            gradeLutSize
        };
        vkCmdBindPipeline(
            slot->commandBuffer,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            state->gradeLutPipeline
        );
        vkCmdPushConstants(
            slot->commandBuffer,
            state->pipelineLayout,
            VK_SHADER_STAGE_COMPUTE_BIT,
            0,
            sizeof(Npv10PushConstants),
            &gradePushConstants
        );
        // One invocation owns one real 2x2 P010 block. This produces all four luma samples and the
        // shared chroma pair without cross-workgroup races or a CPU readback between passes.
        const uint32_t chromaWidth = (safeWidth + 1u) / 2u;
        const uint32_t chromaHeight = (safeHeight + 1u) / 2u;
        vkCmdDispatch(
            slot->commandBuffer,
            (chromaWidth + 7u) / 8u,
            (chromaHeight + 7u) / 8u,
            1u
        );

        VkMemoryBarrier gradeHostBarrier{};
        gradeHostBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        gradeHostBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        gradeHostBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(
            slot->commandBuffer,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_HOST_BIT,
            0,
            1,
            &gradeHostBarrier,
            0, nullptr,
            0, nullptr
        );
    }

    if (!check(vkEndCommandBuffer(slot->commandBuffer), "vkEndCommandBuffer(npv10)")) {
        state->status = "command buffer end failed";
        return false;
    }
    // Reset the fence here — the submit that owns it is guaranteed to be the very next call (B02 defer).
    if (!check(vkResetFences(state->device, 1, &slot->fence), "vkResetFences(npv10)")) {
        state->status = "fence reset failed";
        return false;
    }
    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &slot->commandBuffer;
    if (!check(vkQueueSubmit(state->computeQueue, 1, &submitInfo, slot->fence), "vkQueueSubmit(npv10)")) {
        state->status = "compute dispatch failed";
        // Submit never happened → the fence is left reset/unsignaled. Re-signal it with an empty submit so
        // the next reuse of this slot doesn't block forever on the top-of-function vkWaitForFences (B02).
        VkSubmitInfo fenceSignalSubmit{};
        fenceSignalSubmit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        vkQueueSubmit(state->computeQueue, 1, &fenceSignalSubmit, slot->fence);
        return false;
    }
    if (traceColdDispatch) {
        NPV10_LOGI(
            "NPV10_DISPATCH_SUBMITTED elapsedMs=%llu",
            static_cast<unsigned long long>(monotonicMillis() - startedMs)
        );
    }
    // Submit succeeded → the fence is owned by that submit and will signal (or return an error on a lost
    // device); a wait failure/timeout here must NOT re-signal it (double-use).
    // #5 fix: bounded completion wait — a TDR'd queue routes this frame to CPU rather than hanging forever.
    if (!waitFenceBounded(state, slot->fence, kParityEnhanceFenceTimeoutNs, "npv10-dispatch")) {
        return false;
    }
    if (traceColdDispatch) {
        NPV10_LOGI(
            "NPV10_DISPATCH_COMPLETE elapsedMs=%llu",
            static_cast<unsigned long long>(monotonicMillis() - startedMs)
        );
    }
    return true;
}

uint64_t packInvocationCount(uint32_t width, uint32_t height, uint32_t packMode);

bool dispatchDevelopP010(
    Npv10GpuEngine* state,
    FrameSlot* slot,
    uint32_t safeWidth,
    uint32_t safeHeight,
    VkDeviceSize currentPaddedBytes,
    VkDeviceSize outputBytes,
    VkDeviceSize uniformBytes,
    uint32_t packMode,
    VkDeviceSize packedOutputBytes
) {
    if (state->developPipeline == VK_NULL_HANDLE) {
        state->status = "record-develop pipeline unavailable";
        return false;
    }
    const bool packOnGpu = packMode != kRecordDevelopPackNone;
    if (packOnGpu && (!state->developPackSupported || state->developPackPipeline == VK_NULL_HANDLE)) {
        state->status = "record-develop pack pipeline unavailable";
        return false;
    }
    const uint64_t packInvocations = packOnGpu ? packInvocationCount(safeWidth, safeHeight, packMode) : 0u;
    if (packOnGpu &&
        (packInvocations == 0u ||
            packInvocations > static_cast<uint64_t>(std::numeric_limits<uint32_t>::max()) * 256u)) {
        state->status = "record-develop pack dispatch size invalid";
        return false;
    }
    // #5 fix: bounded slot-ready wait — a hung/TDR'd prior develop submit routes this frame to CPU instead
    // of blocking the conversion worker forever under the engine mutex.
    if (!waitFenceBounded(state, slot->fence, kParityEnhanceFenceTimeoutNs, "record-develop-slot-ready")) {
        return false;
    }

    std::array<VkDescriptorBufferInfo, 4> infos{};
    infos[0] = {slot->currentBuffer.buffer, 0, currentPaddedBytes};
    infos[1] = {state->previousBuffer.buffer, 0, packOnGpu ? packedOutputBytes : currentPaddedBytes};
    infos[2] = {slot->outputBuffer.buffer, 0, outputBytes};
    infos[3] = {state->uniformBuffer.buffer, 0, uniformBytes};
    std::array<VkWriteDescriptorSet, 4> writes{};
    for (uint32_t index = 0; index < writes.size(); ++index) {
        writes[index].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[index].dstSet = slot->descriptorSet;
        writes[index].dstBinding = index;
        writes[index].descriptorCount = 1;
        writes[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[index].pBufferInfo = &infos[index];
    }
    vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);

    if (!check(vkResetCommandBuffer(slot->commandBuffer, 0), "vkResetCommandBuffer(record-develop)")) {
        state->status = "record-develop command reset failed";
        return false;
    }
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!check(vkBeginCommandBuffer(slot->commandBuffer, &beginInfo), "vkBeginCommandBuffer(record-develop)")) {
        state->status = "record-develop command begin failed";
        return false;
    }

    const Npv10PushConstants pushConstants{safeWidth, safeHeight, 0u, 0u};
    vkCmdBindPipeline(slot->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->developPipeline);
    vkCmdBindDescriptorSets(
        slot->commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->pipelineLayout,
        0,
        1,
        &slot->descriptorSet,
        0,
        nullptr
    );
    vkCmdPushConstants(
        slot->commandBuffer,
        state->pipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0,
        sizeof(Npv10PushConstants),
        &pushConstants
    );
    vkCmdDispatch(slot->commandBuffer, (safeWidth + 15u) / 16u, (safeHeight + 15u) / 16u, 1u);

    VkMemoryBarrier memoryBarrier{};
    memoryBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    memoryBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    memoryBarrier.dstAccessMask = packOnGpu
        ? (VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
        : VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(
        slot->commandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        packOnGpu ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_HOST_BIT,
        0,
        1,
        &memoryBarrier,
        0, nullptr,
        0, nullptr
    );

    if (packOnGpu) {
        const Npv10PushConstants packPushConstants{safeWidth, safeHeight, packMode, 0u};
        vkCmdBindPipeline(slot->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->developPackPipeline);
        vkCmdPushConstants(
            slot->commandBuffer,
            state->pipelineLayout,
            VK_SHADER_STAGE_COMPUTE_BIT,
            0,
            sizeof(Npv10PushConstants),
            &packPushConstants
        );
        const uint32_t groupCount = static_cast<uint32_t>((packInvocations + 255u) / 256u);
        vkCmdDispatch(slot->commandBuffer, groupCount, 1u, 1u);

        VkMemoryBarrier packHostBarrier{};
        packHostBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        packHostBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        packHostBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(
            slot->commandBuffer,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_HOST_BIT,
            0,
            1,
            &packHostBarrier,
            0, nullptr,
            0, nullptr
        );
    }

    if (!check(vkEndCommandBuffer(slot->commandBuffer), "vkEndCommandBuffer(record-develop)")) {
        state->status = "record-develop command end failed";
        return false;
    }
    if (!check(vkResetFences(state->device, 1, &slot->fence), "vkResetFences(record-develop)")) {
        state->status = "record-develop fence reset failed";
        return false;
    }
    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &slot->commandBuffer;
    if (!check(vkQueueSubmit(state->computeQueue, 1, &submitInfo, slot->fence), "vkQueueSubmit(record-develop)")) {
        state->status = "record-develop dispatch failed";
        VkSubmitInfo fenceSignalSubmit{};
        fenceSignalSubmit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        vkQueueSubmit(state->computeQueue, 1, &fenceSignalSubmit, slot->fence);
        return false;
    }
    // #5 fix: bounded completion wait — a TDR'd queue routes this frame to CPU rather than hanging forever.
    if (!waitFenceBounded(state, slot->fence, kParityEnhanceFenceTimeoutNs, "record-develop-dispatch")) {
        return false;
    }
    return true;
}

bool dispatchParityEnhanceP010(
    Npv10GpuEngine* state,
    FrameSlot* slot,
    uint32_t safeWidth,
    uint32_t safeHeight,
    VkDeviceSize paddedBytes,
    VkDeviceSize uniformBytes
) {
    if (state->parityEnhancePipeline == VK_NULL_HANDLE) {
        state->status = "parity-enhance pipeline unavailable";
        return false;
    }
    // BOUNDED slot-ready wait: if a PRIOR parity dispatch hung/TDR'd, this bounds the block instead of
    // deadlocking the conversion worker. #16 fix: route through the shared consecutive-timeout counter so a
    // SINGLE transient stall degrades only this frame to CPU (all lanes are bounded now, so a poisoned slot
    // no longer deadlocks the develop/NPV10 lanes); the whole engine disables only after a timeout streak.
    if (!waitFenceBounded(state, slot->fence, kParityEnhanceFenceTimeoutNs, "parity-enhance-slot")) {
        return false;
    }

    std::array<VkDescriptorBufferInfo, 4> infos{};
    infos[0] = {slot->currentBuffer.buffer, 0, paddedBytes};
    infos[1] = {state->previousBuffer.buffer, 0, paddedBytes}; // bound but unread (no temporal lane)
    infos[2] = {slot->outputBuffer.buffer, 0, paddedBytes};
    infos[3] = {state->uniformBuffer.buffer, 0, uniformBytes};
    std::array<VkWriteDescriptorSet, 4> writes{};
    for (uint32_t index = 0; index < writes.size(); ++index) {
        writes[index].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[index].dstSet = slot->descriptorSet;
        writes[index].dstBinding = index;
        writes[index].descriptorCount = 1;
        writes[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[index].pBufferInfo = &infos[index];
    }
    vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);

    if (!check(vkResetCommandBuffer(slot->commandBuffer, 0), "vkResetCommandBuffer(parity-enhance)")) {
        state->status = "parity-enhance command reset failed";
        return false;
    }
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!check(vkBeginCommandBuffer(slot->commandBuffer, &beginInfo), "vkBeginCommandBuffer(parity-enhance)")) {
        state->status = "parity-enhance command begin failed";
        return false;
    }

    const Npv10PushConstants pushConstants{safeWidth, safeHeight, 0u, 0u};
    vkCmdBindPipeline(slot->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->parityEnhancePipeline);
    vkCmdBindDescriptorSets(
        slot->commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->pipelineLayout,
        0,
        1,
        &slot->descriptorSet,
        0,
        nullptr
    );
    vkCmdPushConstants(
        slot->commandBuffer,
        state->pipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0,
        sizeof(Npv10PushConstants),
        &pushConstants
    );
    vkCmdDispatch(slot->commandBuffer, (safeWidth + 15u) / 16u, (safeHeight + 15u) / 16u, 1u);

    VkMemoryBarrier memoryBarrier{};
    memoryBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    memoryBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    memoryBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(
        slot->commandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_HOST_BIT,
        0,
        1,
        &memoryBarrier,
        0, nullptr,
        0, nullptr
    );

    if (!check(vkEndCommandBuffer(slot->commandBuffer), "vkEndCommandBuffer(parity-enhance)")) {
        state->status = "parity-enhance command end failed";
        return false;
    }
    if (!check(vkResetFences(state->device, 1, &slot->fence), "vkResetFences(parity-enhance)")) {
        state->status = "parity-enhance fence reset failed";
        return false;
    }
    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &slot->commandBuffer;
    if (!check(vkQueueSubmit(state->computeQueue, 1, &submitInfo, slot->fence), "vkQueueSubmit(parity-enhance)")) {
        state->status = "parity-enhance dispatch failed";
        // Submit never happened -> fence left reset/unsignaled. Re-signal with an empty submit so the
        // next reuse of this round-robin slot does not block on the top-of-function wait.
        VkSubmitInfo fenceSignalSubmit{};
        fenceSignalSubmit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        vkQueueSubmit(state->computeQueue, 1, &fenceSignalSubmit, slot->fence);
        return false;
    }
    // BOUNDED completion wait: a hung/TDR'd queue returns VK_TIMEOUT instead of blocking forever. Our submit
    // already OWNS this fence (reset + queued above), so we can neither reset nor re-signal it safely while it
    // is pending. #16 fix: the shared consecutive-timeout counter degrades a single transient stall to CPU for
    // this frame and disables the whole engine only after a streak (all lanes bounded ⇒ no cross-lane deadlock).
    if (!waitFenceBounded(state, slot->fence, kParityEnhanceFenceTimeoutNs, "parity-enhance-dispatch")) {
        return false;
    }
    return true;
}

bool prepareFrameResourcesFromRawPointers(
    Npv10GpuEngine* state,
    FrameSlot* slot,
    const void* currentPayload,
    const void* previousPayload,
    const void* uniforms,
    VkDeviceSize currentBytes,
    VkDeviceSize paddedBytes,
    VkDeviceSize uniformBytes,
    bool hasPrevious
) {
    if (!copyToMappedMemory(&slot->currentBuffer, currentPayload, currentBytes)) return false;
    zeroMappedTail(&slot->currentBuffer, currentBytes, paddedBytes);
    if (hasPrevious && previousPayload != nullptr) {
        if (!copyToMappedMemory(&state->previousBuffer, previousPayload, currentBytes)) return false;
    } else {
        if (!copyToMappedMemory(&state->previousBuffer, currentPayload, currentBytes)) return false;
    }
    zeroMappedTail(&state->previousBuffer, currentBytes, paddedBytes);
    if (!copyToMappedMemory(&slot->outputBuffer, currentPayload, currentBytes)) return false;
    zeroMappedTail(&slot->outputBuffer, currentBytes, paddedBytes);
    if (uniforms != state->uniformBuffer.mapped) {
        return copyToMappedMemory(&state->uniformBuffer, uniforms, uniformBytes);
    }
    return state->uniformBuffer.capacity >= uniformBytes;
}

uint32_t clamp10(uint32_t value) {
    return value > 1023u ? 1023u : value;
}

uint32_t clamp10Signed(int32_t value) {
    if (value < 0) return 0u;
    if (value > 1023) return 1023u;
    return static_cast<uint32_t>(value);
}

uint32_t rowBytesR210(uint32_t width) {
    return ((width + 63u) / 64u) * 256u;
}

uint32_t rowBytesV210(uint32_t width) {
    return ((width + 47u) / 48u) * 128u;
}

uint64_t payloadBytesP010420(uint32_t width, uint32_t height) {
    const uint64_t pixels = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    const uint64_t chromaWidth = (static_cast<uint64_t>(width) + 1u) / 2u;
    const uint64_t chromaHeight = (static_cast<uint64_t>(height) + 1u) / 2u;
    return pixels * 2u + chromaWidth * chromaHeight * 4u;
}

uint64_t packedPayloadBytes(uint32_t width, uint32_t height, uint32_t packMode) {
    if (packMode == kRecordDevelopPackR210) {
        return static_cast<uint64_t>(rowBytesR210(width)) * static_cast<uint64_t>(height);
    }
    if (packMode == kRecordDevelopPackV210) {
        return static_cast<uint64_t>(rowBytesV210(width)) * static_cast<uint64_t>(height);
    }
    if (packMode == kRecordDevelopPackP010Bt2020 || packMode == kRecordDevelopPackP010Bt709) {
        return payloadBytesP010420(width, height);
    }
    return 0u;
}

uint64_t packInvocationCount(uint32_t width, uint32_t height, uint32_t packMode) {
    if (packMode == kRecordDevelopPackR210) {
        return (static_cast<uint64_t>(rowBytesR210(width)) / 4u) * static_cast<uint64_t>(height);
    }
    if (packMode == kRecordDevelopPackV210) {
        return (static_cast<uint64_t>(rowBytesV210(width)) / 16u) * static_cast<uint64_t>(height);
    }
    if (packMode == kRecordDevelopPackP010Bt2020 || packMode == kRecordDevelopPackP010Bt709) {
        const uint64_t pixels = static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
        const uint64_t lumaPairs = (pixels + 1u) / 2u;
        const uint64_t chromaPixels =
            ((static_cast<uint64_t>(width) + 1u) / 2u) *
            ((static_cast<uint64_t>(height) + 1u) / 2u);
        return std::max(lumaPairs, chromaPixels);
    }
    return 0u;
}

void writeWordLe(std::vector<jbyte>& row, size_t offset, uint32_t word) {
    row[offset] = static_cast<jbyte>(word & 0xFFu);
    row[offset + 1u] = static_cast<jbyte>((word >> 8u) & 0xFFu);
    row[offset + 2u] = static_cast<jbyte>((word >> 16u) & 0xFFu);
    row[offset + 3u] = static_cast<jbyte>((word >> 24u) & 0xFFu);
}

[[maybe_unused]] void writeP010Word(std::vector<jbyte>& payload, size_t offset, uint32_t code) {
    const uint32_t word = clamp10(code) << 6u;
    payload[offset] = static_cast<jbyte>(word & 0xFFu);
    payload[offset + 1u] = static_cast<jbyte>((word >> 8u) & 0xFFu);
}

void writeP010Word(jbyte* payload, size_t offset, uint32_t code) {
    const uint32_t word = clamp10(code) << 6u;
    payload[offset] = static_cast<jbyte>(word & 0xFFu);
    payload[offset + 1u] = static_cast<jbyte>((word >> 8u) & 0xFFu);
}

uint32_t readDevelopCode(const uint32_t* output, uint64_t pixel, uint32_t channel) {
    const uint32_t word0 = output[pixel * 2u];
    if (channel == 0u) return clamp10(word0 & 0xFFFFu);
    if (channel == 1u) return clamp10((word0 >> 16u) & 0xFFFFu);
    return clamp10(output[pixel * 2u + 1u] & 0xFFFFu);
}

struct YcbcrMatrix {
    double kr;
    double kb;
    double kg;
};

YcbcrMatrix recordDevelopYcbcrMatrix(bool bt709) {
    if (bt709) {
        constexpr double kr = 0.2126;
        constexpr double kb = 0.0722;
        return {kr, kb, 1.0 - kr - kb};
    }
    constexpr double kr = 0.2627;
    constexpr double kb = 0.0593;
    return {kr, kb, 1.0 - kr - kb};
}

uint32_t roundTo10(double value) {
    return clamp10Signed(static_cast<int32_t>(std::lround(value)));
}

void recordDevelopYcbcrForPixel(
    const uint32_t* output,
    uint64_t pixel,
    const YcbcrMatrix& matrix,
    uint32_t* y,
    uint32_t* cb,
    uint32_t* cr
) {
    constexpr double kMax10 = 1023.0;
    constexpr double kLumaMin = 64.0;
    constexpr double kLumaMax = 940.0;
    constexpr double kChromaMin = 64.0;
    constexpr double kChromaMax = 960.0;
    constexpr double kChromaZero = 512.0;
    const double rf = static_cast<double>(readDevelopCode(output, pixel, 0u)) / kMax10;
    const double gf = static_cast<double>(readDevelopCode(output, pixel, 1u)) / kMax10;
    const double bf = static_cast<double>(readDevelopCode(output, pixel, 2u)) / kMax10;
    const double yp = matrix.kr * rf + matrix.kg * gf + matrix.kb * bf;
    const double cbv = (bf - yp) / (2.0 * (1.0 - matrix.kb));
    const double crv = (rf - yp) / (2.0 * (1.0 - matrix.kr));
    *y = roundTo10(yp * (kLumaMax - kLumaMin) + kLumaMin);
    *cb = roundTo10(cbv * (kChromaMax - kChromaMin) + kChromaZero);
    *cr = roundTo10(crv * (kChromaMax - kChromaMin) + kChromaZero);
}

bool prepareAndDispatchRecordDevelop(
    JNIEnv* env,
    Npv10GpuEngine* state,
    jbyteArray currentPayload,
    jint width,
    jint height,
    jfloatArray params,
    FrameSlot** outSlot,
    uint32_t* outWidth,
    uint32_t* outHeight,
    uint64_t* outPixels,
    uint32_t packMode,
    uint64_t* outPackedBytes
) {
    if (currentPayload == nullptr || params == nullptr || width <= 0 || height <= 0) {
        state->status = "invalid record-develop JNI input";
        return false;
    }
    if (env->GetArrayLength(params) != static_cast<jsize>(kRecordDevelopParamFloatCount)) {
        state->status = "invalid record-develop params size";
        return false;
    }

    const uint32_t safeWidth = static_cast<uint32_t>(width);
    const uint32_t safeHeight = static_cast<uint32_t>(height);
    const uint64_t pixels = static_cast<uint64_t>(safeWidth) * static_cast<uint64_t>(safeHeight);
    const uint64_t lumaSamples = pixels;
    const uint64_t chromaSamples =
        static_cast<uint64_t>((safeWidth + 1u) / 2u) * static_cast<uint64_t>((safeHeight + 1u) / 2u);
    const uint64_t totalP010Samples = lumaSamples + chromaSamples * 2u;
    const uint64_t expectedPayloadBytes = totalP010Samples * 2u;
    const uint64_t outputWords = pixels * 2u;
    const uint64_t outputBytes64 = outputWords * sizeof(uint32_t);
    const bool packOnGpu = packMode != kRecordDevelopPackNone;
    const uint64_t packedBytes64 = packOnGpu ? packedPayloadBytes(safeWidth, safeHeight, packMode) : 0u;
    if (expectedPayloadBytes == 0u ||
        expectedPayloadBytes > static_cast<uint64_t>(std::numeric_limits<jsize>::max()) ||
        outputBytes64 > static_cast<uint64_t>(std::numeric_limits<VkDeviceSize>::max()) ||
        packedBytes64 > static_cast<uint64_t>(std::numeric_limits<VkDeviceSize>::max()) ||
        packedBytes64 > static_cast<uint64_t>(std::numeric_limits<jsize>::max())) {
        state->status = "record-develop size overflow";
        return false;
    }
    if (packOnGpu && packedBytes64 == 0u) {
        state->status = "record-develop pack mode invalid";
        return false;
    }
    const jsize currentSize = env->GetArrayLength(currentPayload);
    if (static_cast<uint64_t>(currentSize) != expectedPayloadBytes) {
        state->status = "record-develop payload size mismatch";
        return false;
    }

    const uint64_t paddedWords = (totalP010Samples + 1u) / 2u;
    const VkDeviceSize currentPaddedBytes = static_cast<VkDeviceSize>(paddedWords * sizeof(uint32_t));
    const VkDeviceSize outputBytes = static_cast<VkDeviceSize>(outputBytes64);
    const VkDeviceSize previousBytes = packOnGpu
        ? static_cast<VkDeviceSize>(packedBytes64)
        : currentPaddedBytes;
    const VkDeviceSize uniformBytes = static_cast<VkDeviceSize>(kRecordDevelopParamFloatCount * sizeof(float));
    if (!ensureFrameResources(state, currentPaddedBytes, outputBytes, uniformBytes, previousBytes)) {
        if (state->status == "ready") {
            state->status = "record-develop buffer allocation failed";
        }
        return false;
    }

    FrameSlot& slot = state->slots[state->frameSerial++ & 1u];
    env->GetByteArrayRegion(currentPayload, 0, currentSize, static_cast<jbyte*>(slot.currentBuffer.mapped));
    if (env->ExceptionCheck()) {
        state->status = "record-develop payload read failed";
        return false;
    }
    zeroMappedTail(&slot.currentBuffer, static_cast<VkDeviceSize>(currentSize), currentPaddedBytes);
    if (!packOnGpu) {
        std::memcpy(state->previousBuffer.mapped, slot.currentBuffer.mapped, static_cast<size_t>(currentSize));
        zeroMappedTail(&state->previousBuffer, static_cast<VkDeviceSize>(currentSize), currentPaddedBytes);
    }
    env->GetFloatArrayRegion(params, 0, static_cast<jsize>(kRecordDevelopParamFloatCount), static_cast<jfloat*>(state->uniformBuffer.mapped));
    if (env->ExceptionCheck()) {
        state->status = "record-develop params read failed";
        return false;
    }

    if (!dispatchDevelopP010(
            state,
            &slot,
            safeWidth,
            safeHeight,
            currentPaddedBytes,
            outputBytes,
            uniformBytes,
            packMode,
            static_cast<VkDeviceSize>(packedBytes64)
        )) {
        return false;
    }

    *outSlot = &slot;
    *outWidth = safeWidth;
    *outHeight = safeHeight;
    *outPixels = pixels;
    if (outPackedBytes != nullptr) {
        *outPackedBytes = packedBytes64;
    }
    return true;
}

[[maybe_unused]] jbyteArray packRecordDevelopR210(
    JNIEnv* env,
    Npv10GpuEngine* state,
    const uint32_t* output,
    uint32_t width,
    uint32_t height
) {
    const uint32_t stride = rowBytesR210(width);
    const uint64_t payloadBytes64 = static_cast<uint64_t>(stride) * static_cast<uint64_t>(height);
    if (payloadBytes64 > static_cast<uint64_t>(std::numeric_limits<jsize>::max())) {
        state->status = "record-develop r210 output too large";
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(payloadBytes64));
    if (result == nullptr) {
        state->status = "record-develop r210 allocation failed";
        return nullptr;
    }
    std::vector<jbyte> row(stride);
    for (uint32_t y = 0; y < height; ++y) {
        std::fill(row.begin(), row.end(), static_cast<jbyte>(0));
        for (uint32_t x = 0; x < width; ++x) {
            const uint64_t pixel = static_cast<uint64_t>(y) * width + x;
            const uint32_t r = readDevelopCode(output, pixel, 0u);
            const uint32_t g = readDevelopCode(output, pixel, 1u);
            const uint32_t b = readDevelopCode(output, pixel, 2u);
            const uint32_t word = (r << 20u) | (g << 10u) | b;
            const size_t offset = static_cast<size_t>(x) * 4u;
            row[offset] = static_cast<jbyte>((word >> 24u) & 0xFFu);
            row[offset + 1u] = static_cast<jbyte>((word >> 16u) & 0xFFu);
            row[offset + 2u] = static_cast<jbyte>((word >> 8u) & 0xFFu);
            row[offset + 3u] = static_cast<jbyte>(word & 0xFFu);
        }
        env->SetByteArrayRegion(
            result,
            static_cast<jsize>(static_cast<uint64_t>(y) * stride),
            static_cast<jsize>(stride),
            row.data()
        );
        if (env->ExceptionCheck()) {
            state->status = "record-develop r210 write failed";
            return nullptr;
        }
    }
    return result;
}

[[maybe_unused]] jbyteArray packRecordDevelopV210(
    JNIEnv* env,
    Npv10GpuEngine* state,
    const uint32_t* output,
    uint32_t width,
    uint32_t height
) {
    constexpr double kMax10 = 1023.0;
    constexpr double kKr = 0.2627;
    constexpr double kKb = 0.0593;
    constexpr double kKg = 1.0 - kKr - kKb;
    constexpr double kLumaMin = 64.0;
    constexpr double kLumaMax = 940.0;
    constexpr double kChromaMin = 64.0;
    constexpr double kChromaMax = 960.0;
    constexpr double kChromaZero = 512.0;

    const uint32_t stride = rowBytesV210(width);
    const uint64_t payloadBytes64 = static_cast<uint64_t>(stride) * static_cast<uint64_t>(height);
    if (payloadBytes64 > static_cast<uint64_t>(std::numeric_limits<jsize>::max())) {
        state->status = "record-develop v210 output too large";
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(payloadBytes64));
    if (result == nullptr) {
        state->status = "record-develop v210 allocation failed";
        return nullptr;
    }
    std::vector<jbyte> row(stride);
    std::vector<uint32_t> luma(width);
    std::vector<uint32_t> cb(width);
    std::vector<uint32_t> cr(width);
    for (uint32_t y = 0; y < height; ++y) {
        std::fill(row.begin(), row.end(), static_cast<jbyte>(0));
        for (uint32_t x = 0; x < width; ++x) {
            const uint64_t pixel = static_cast<uint64_t>(y) * width + x;
            const double rf = static_cast<double>(readDevelopCode(output, pixel, 0u)) / kMax10;
            const double gf = static_cast<double>(readDevelopCode(output, pixel, 1u)) / kMax10;
            const double bf = static_cast<double>(readDevelopCode(output, pixel, 2u)) / kMax10;
            const double yp = kKr * rf + kKg * gf + kKb * bf;
            const double cbv = (bf - yp) / (2.0 * (1.0 - kKb));
            const double crv = (rf - yp) / (2.0 * (1.0 - kKr));
            luma[x] = clamp10Signed(static_cast<int32_t>(yp * (kLumaMax - kLumaMin) + kLumaMin));
            cb[x] = clamp10Signed(static_cast<int32_t>(cbv * (kChromaMax - kChromaMin) + kChromaZero));
            cr[x] = clamp10Signed(static_cast<int32_t>(crv * (kChromaMax - kChromaMin) + kChromaZero));
        }

        size_t offset = 0;
        for (uint32_t x = 0; x < width; x += 6u) {
            const uint32_t y0 = luma[x];
            const uint32_t y1 = (x + 1u < width) ? luma[x + 1u] : y0;
            const uint32_t y2 = (x + 2u < width) ? luma[x + 2u] : y1;
            const uint32_t y3 = (x + 3u < width) ? luma[x + 3u] : y2;
            const uint32_t y4 = (x + 4u < width) ? luma[x + 4u] : y3;
            const uint32_t y5 = (x + 5u < width) ? luma[x + 5u] : y4;
            const uint32_t cb0 = cb[x];
            const uint32_t cr0 = cr[x];
            const uint32_t cb2 = (x + 2u < width) ? cb[x + 2u] : cb0;
            const uint32_t cr2 = (x + 2u < width) ? cr[x + 2u] : cr0;
            const uint32_t cb4 = (x + 4u < width) ? cb[x + 4u] : cb2;
            const uint32_t cr4 = (x + 4u < width) ? cr[x + 4u] : cr2;
            writeWordLe(row, offset, (cr0 << 20u) | (y0 << 10u) | cb0); offset += 4u;
            writeWordLe(row, offset, (y2 << 20u) | (cb2 << 10u) | y1); offset += 4u;
            writeWordLe(row, offset, (cb4 << 20u) | (y3 << 10u) | cr2); offset += 4u;
            writeWordLe(row, offset, (y5 << 20u) | (cr4 << 10u) | y4); offset += 4u;
        }
        env->SetByteArrayRegion(
            result,
            static_cast<jsize>(static_cast<uint64_t>(y) * stride),
            static_cast<jsize>(stride),
            row.data()
        );
        if (env->ExceptionCheck()) {
            state->status = "record-develop v210 write failed";
            return nullptr;
        }
    }
    return result;
}

[[maybe_unused]] jbyteArray packRecordDevelopP010420(
    JNIEnv* env,
    Npv10GpuEngine* state,
    const uint32_t* output,
    uint32_t width,
    uint32_t height,
    bool bt709Matrix
) {
    const uint32_t chromaWidth = (width + 1u) / 2u;
    const uint32_t chromaHeight = (height + 1u) / 2u;
    const uint64_t yPlaneBytes = static_cast<uint64_t>(width) * height * 2u;
    const uint64_t uvPlaneBytes = static_cast<uint64_t>(chromaWidth) * chromaHeight * 4u;
    const uint64_t payloadBytes64 = yPlaneBytes + uvPlaneBytes;
    if (payloadBytes64 == 0u || payloadBytes64 > static_cast<uint64_t>(std::numeric_limits<jsize>::max())) {
        state->status = "record-develop p010 output too large";
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(payloadBytes64));
    if (result == nullptr) {
        state->status = "record-develop p010 allocation failed";
        return nullptr;
    }

    const YcbcrMatrix matrix = recordDevelopYcbcrMatrix(bt709Matrix);
    jbyte* payload = env->GetByteArrayElements(result, nullptr);
    if (payload == nullptr) {
        state->status = "record-develop p010 lock failed";
        return nullptr;
    }
    std::memset(payload, 0, static_cast<size_t>(payloadBytes64));

    for (uint32_t y = 0u; y < height; ++y) {
        for (uint32_t x = 0u; x < width; ++x) {
            uint32_t yCode = 0u;
            uint32_t cbCode = 0u;
            uint32_t crCode = 0u;
            recordDevelopYcbcrForPixel(
                output,
                static_cast<uint64_t>(y) * width + x,
                matrix,
                &yCode,
                &cbCode,
                &crCode
            );
            writeP010Word(
                payload,
                static_cast<size_t>((static_cast<uint64_t>(y) * width + x) * 2u),
                yCode
            );
        }
    }

    for (uint32_t cy = 0u; cy < chromaHeight; ++cy) {
        const uint32_t y0 = cy * 2u;
        const uint32_t y1 = std::min(y0 + 1u, height - 1u);
        for (uint32_t cx = 0u; cx < chromaWidth; ++cx) {
            const uint32_t x0 = cx * 2u;
            const uint32_t x1 = std::min(x0 + 1u, width - 1u);
            uint32_t unusedY = 0u;
            uint32_t cb00 = 0u, cr00 = 0u;
            uint32_t cb01 = 0u, cr01 = 0u;
            uint32_t cb10 = 0u, cr10 = 0u;
            uint32_t cb11 = 0u, cr11 = 0u;
            recordDevelopYcbcrForPixel(output, static_cast<uint64_t>(y0) * width + x0, matrix, &unusedY, &cb00, &cr00);
            recordDevelopYcbcrForPixel(output, static_cast<uint64_t>(y0) * width + x1, matrix, &unusedY, &cb01, &cr01);
            recordDevelopYcbcrForPixel(output, static_cast<uint64_t>(y1) * width + x0, matrix, &unusedY, &cb10, &cr10);
            recordDevelopYcbcrForPixel(output, static_cast<uint64_t>(y1) * width + x1, matrix, &unusedY, &cb11, &cr11);
            const uint32_t cb = roundTo10((static_cast<double>(cb00 + cb01 + cb10 + cb11)) / 4.0);
            const uint32_t cr = roundTo10((static_cast<double>(cr00 + cr01 + cr10 + cr11)) / 4.0);
            const size_t uvOffset = static_cast<size_t>(
                yPlaneBytes + (static_cast<uint64_t>(cy) * chromaWidth + cx) * 4u
            );
            writeP010Word(payload, uvOffset, cb);
            writeP010Word(payload, uvOffset + 2u, cr);
        }
    }

    env->ReleaseByteArrayElements(result, payload, 0);
    if (env->ExceptionCheck()) {
        state->status = "record-develop p010 write failed";
        return nullptr;
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10ExportGpuAccelerator_nativeEnhanceP010(
    JNIEnv* env,
    jclass,
    jbyteArray currentPayload,
    jbyteArray previousPayload,
    jint width,
    jint height,
    jfloatArray uniforms,
    jfloatArray gradeLut,
    jint gradeLutSize
) {
    auto& state = engine();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!initializeEngine(&state, Npv10PipelineRole::EXPORT_ENHANCE)) {
        NPV10_LOGW("NPV10 GPU export unavailable: %s", state.status.c_str());
        return nullptr;
    }
    if (currentPayload == nullptr || uniforms == nullptr || width <= 0 || height <= 0) {
        state.status = "invalid JNI input";
        return nullptr;
    }
    if (env->GetArrayLength(uniforms) != static_cast<jsize>(kUniformFloatCount)) {
        state.status = "invalid uniform packet size";
        return nullptr;
    }
    uint32_t safeGradeLutSize = 0u;
    uint32_t gradeLutFloatCount = 0u;
    if (!validateGradeLut(env, gradeLut, gradeLutSize, &safeGradeLutSize, &gradeLutFloatCount)) {
        state.status = "invalid grade LUT packet";
        return nullptr;
    }

    const uint32_t safeWidth = static_cast<uint32_t>(width);
    const uint32_t safeHeight = static_cast<uint32_t>(height);
    const uint64_t lumaSamples = static_cast<uint64_t>(safeWidth) * static_cast<uint64_t>(safeHeight);
    const uint64_t chromaSamples =
        static_cast<uint64_t>((safeWidth + 1u) / 2u) * static_cast<uint64_t>((safeHeight + 1u) / 2u);
    const uint64_t totalSamples = lumaSamples + chromaSamples * 2u;
    const uint64_t expectedPayloadBytes = totalSamples * 2u;
    if (expectedPayloadBytes == 0u || expectedPayloadBytes > static_cast<uint64_t>(std::numeric_limits<jsize>::max())) {
        state.status = "payload size overflow";
        return nullptr;
    }

    const jsize currentSize = env->GetArrayLength(currentPayload);
    if (static_cast<uint64_t>(currentSize) != expectedPayloadBytes) {
        state.status = "current payload size mismatch";
        return nullptr;
    }
    const bool hasPrevious =
        previousPayload != nullptr &&
        env->GetArrayLength(previousPayload) == currentSize;

    const uint64_t paddedWords = (totalSamples + 1u) / 2u;
    const VkDeviceSize paddedBytes = static_cast<VkDeviceSize>(paddedWords * sizeof(uint32_t));
    const uint32_t totalUniformFloats = kUniformFloatCount + gradeLutFloatCount;
    const VkDeviceSize uniformBytes = static_cast<VkDeviceSize>(totalUniformFloats) * sizeof(float);
    // ABR, ISO, shot/read-noise, and detail-gain coefficients ride in this
    // uniform block; the shader clamps entropy/detail if backpressure rises.
    if (!ensureFrameResources(&state, paddedBytes, paddedBytes, uniformBytes, paddedBytes)) {
        if (state.status == "ready") {
            state.status = "buffer allocation failed";
        }
        return nullptr;
    }
    FrameSlot& slot = state.slots[state.frameSerial++ & 1u];
    env->GetByteArrayRegion(currentPayload, 0, currentSize, static_cast<jbyte*>(slot.currentBuffer.mapped));
    if (env->ExceptionCheck()) {
        state.status = "current payload region read failed";
        return nullptr;
    }
    zeroMappedTail(&slot.currentBuffer, static_cast<VkDeviceSize>(currentSize), paddedBytes);
    if (hasPrevious) {
        env->GetByteArrayRegion(previousPayload, 0, currentSize, static_cast<jbyte*>(state.previousBuffer.mapped));
        if (env->ExceptionCheck()) {
            state.status = "previous payload region read failed";
            return nullptr;
        }
    } else {
        std::memcpy(state.previousBuffer.mapped, slot.currentBuffer.mapped, static_cast<size_t>(currentSize));
    }
    zeroMappedTail(&state.previousBuffer, static_cast<VkDeviceSize>(currentSize), paddedBytes);
    std::memcpy(slot.outputBuffer.mapped, slot.currentBuffer.mapped, static_cast<size_t>(currentSize));
    zeroMappedTail(&slot.outputBuffer, static_cast<VkDeviceSize>(currentSize), paddedBytes);
    env->GetFloatArrayRegion(uniforms, 0, static_cast<jsize>(kUniformFloatCount), static_cast<jfloat*>(state.uniformBuffer.mapped));
    if (env->ExceptionCheck()) {
        state.status = "uniform region read failed";
        return nullptr;
    }
    if (gradeLutFloatCount > 0u) {
        auto* uniformFloats = static_cast<jfloat*>(state.uniformBuffer.mapped);
        env->GetFloatArrayRegion(
            gradeLut,
            0,
            static_cast<jsize>(gradeLutFloatCount),
            uniformFloats + kUniformFloatCount
        );
        if (env->ExceptionCheck()) {
            state.status = "grade LUT region read failed";
            return nullptr;
        }
    }

    if (!dispatchEnhanceP010(
            &state,
            &slot,
            safeWidth,
            safeHeight,
            paddedBytes,
            uniformBytes,
            hasPrevious,
            nullptr,
            safeGradeLutSize
        )) {
        return nullptr;
    }
    const void* finalPayload = safeGradeLutSize > 0u
        ? slot.currentBuffer.mapped
        : slot.outputBuffer.mapped;
    std::memcpy(state.previousBuffer.mapped, finalPayload, static_cast<size_t>(currentSize));

    jbyteArray result = env->NewByteArray(currentSize);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, currentSize, static_cast<const jbyte*>(finalPayload));
    }
    state.status = "ready";
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10ExportGpuAccelerator_nativeEnhanceP010Direct(
    JNIEnv* env,
    jclass,
    jobject currentPayload,
    jobject previousPayload,
    jobject outputPayload,
    jint width,
    jint height,
    jfloatArray uniforms,
    jfloatArray gradeLut,
    jint gradeLutSize
) {
    auto& state = engine();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!initializeEngine(&state, Npv10PipelineRole::EXPORT_ENHANCE)) {
        NPV10_LOGW("NPV10 GPU export unavailable: %s", state.status.c_str());
        return JNI_FALSE;
    }
    if (currentPayload == nullptr || outputPayload == nullptr || uniforms == nullptr || width <= 0 || height <= 0) {
        state.status = "invalid direct JNI input";
        return JNI_FALSE;
    }
    if (env->GetArrayLength(uniforms) != static_cast<jsize>(kUniformFloatCount)) {
        state.status = "invalid uniform packet size";
        return JNI_FALSE;
    }
    uint32_t safeGradeLutSize = 0u;
    uint32_t gradeLutFloatCount = 0u;
    if (!validateGradeLut(env, gradeLut, gradeLutSize, &safeGradeLutSize, &gradeLutFloatCount)) {
        state.status = "invalid grade LUT packet";
        return JNI_FALSE;
    }
    auto* currentAddress = static_cast<uint8_t*>(env->GetDirectBufferAddress(currentPayload));
    auto* previousAddress = previousPayload == nullptr ? nullptr : static_cast<uint8_t*>(env->GetDirectBufferAddress(previousPayload));
    auto* outputAddress = static_cast<uint8_t*>(env->GetDirectBufferAddress(outputPayload));
    if (currentAddress == nullptr || outputAddress == nullptr) {
        state.status = "direct buffer address unavailable";
        return JNI_FALSE;
    }

    const uint32_t safeWidth = static_cast<uint32_t>(width);
    const uint32_t safeHeight = static_cast<uint32_t>(height);
    const uint64_t lumaSamples = static_cast<uint64_t>(safeWidth) * static_cast<uint64_t>(safeHeight);
    const uint64_t chromaSamples =
        static_cast<uint64_t>((safeWidth + 1u) / 2u) * static_cast<uint64_t>((safeHeight + 1u) / 2u);
    const uint64_t totalSamples = lumaSamples + chromaSamples * 2u;
    const uint64_t expectedPayloadBytes = totalSamples * 2u;
    if (expectedPayloadBytes == 0u ||
        env->GetDirectBufferCapacity(currentPayload) < static_cast<jlong>(expectedPayloadBytes) ||
        env->GetDirectBufferCapacity(outputPayload) < static_cast<jlong>(expectedPayloadBytes)) {
        state.status = "direct payload size mismatch";
        return JNI_FALSE;
    }
    const bool hasPrevious =
        previousAddress != nullptr &&
        env->GetDirectBufferCapacity(previousPayload) >= static_cast<jlong>(expectedPayloadBytes);
    const uint64_t paddedWords = (totalSamples + 1u) / 2u;
    const VkDeviceSize paddedBytes = static_cast<VkDeviceSize>(paddedWords * sizeof(uint32_t));
    const uint32_t totalUniformFloats = kUniformFloatCount + gradeLutFloatCount;
    const VkDeviceSize uniformBytes = static_cast<VkDeviceSize>(totalUniformFloats) * sizeof(float);
    const bool traceColdDispatch = state.frameSerial == 0u;
    const uint64_t startedMs = traceColdDispatch ? monotonicMillis() : 0u;
    if (traceColdDispatch) NPV10_LOGI("NPV10_DIRECT_BEGIN width=%u height=%u", safeWidth, safeHeight);
    if (!ensureFrameResources(&state, paddedBytes, paddedBytes, uniformBytes, paddedBytes)) {
        if (state.status == "ready") {
            state.status = "buffer allocation failed";
        }
        return JNI_FALSE;
    }
    if (traceColdDispatch) {
        NPV10_LOGI(
            "NPV10_DIRECT_RESOURCES_READY elapsedMs=%llu",
            static_cast<unsigned long long>(monotonicMillis() - startedMs)
        );
    }

    FrameSlot& slot = state.slots[state.frameSerial++ & 1u];
    env->GetFloatArrayRegion(uniforms, 0, static_cast<jsize>(kUniformFloatCount), static_cast<jfloat*>(state.uniformBuffer.mapped));
    if (env->ExceptionCheck()) {
        state.status = "uniform region read failed";
        return JNI_FALSE;
    }
    if (gradeLutFloatCount > 0u) {
        auto* uniformFloats = static_cast<jfloat*>(state.uniformBuffer.mapped);
        env->GetFloatArrayRegion(
            gradeLut,
            0,
            static_cast<jsize>(gradeLutFloatCount),
            uniformFloats + kUniformFloatCount
        );
        if (env->ExceptionCheck()) {
            state.status = "grade LUT region read failed";
            return JNI_FALSE;
        }
    }
    if (
        !prepareFrameResourcesFromRawPointers(
            &state,
            &slot,
            currentAddress,
            hasPrevious ? previousAddress : nullptr,
            state.uniformBuffer.mapped,
            static_cast<VkDeviceSize>(expectedPayloadBytes),
            paddedBytes,
            uniformBytes,
            hasPrevious
        ) ||
        !dispatchEnhanceP010(
            &state,
            &slot,
            safeWidth,
            safeHeight,
            paddedBytes,
            uniformBytes,
            hasPrevious,
            nullptr,
            safeGradeLutSize
        )) {
        if (state.status == "ready") {
            state.status = "direct buffer upload or dispatch failed";
        }
        return JNI_FALSE;
    }
    if (traceColdDispatch) {
        NPV10_LOGI(
            "NPV10_DIRECT_DISPATCH_RETURNED elapsedMs=%llu",
            static_cast<unsigned long long>(monotonicMillis() - startedMs)
        );
    }
    const void* finalPayload = safeGradeLutSize > 0u
        ? slot.currentBuffer.mapped
        : slot.outputBuffer.mapped;
    std::memcpy(outputAddress, finalPayload, static_cast<size_t>(expectedPayloadBytes));
    std::memcpy(state.previousBuffer.mapped, finalPayload, static_cast<size_t>(expectedPayloadBytes));
    // FIX #12 — removed a throwaway env->NewDirectByteBuffer(slot.outputBuffer.mapped, ...) here. Its
    // result was never used: the completed GPU output was already delivered to the caller's direct
    // buffer via the memcpy to outputAddress above. Allocating a Java ByteBuffer per export frame was
    // pure waste, and a transient allocation failure (e.g. OOM) would raise a pending JNI exception
    // that surfaces on a later JNI call and would void this already-completed direct-path result.
    state.status = "ready";
    if (traceColdDispatch) {
        NPV10_LOGI(
            "NPV10_DIRECT_COMPLETE elapsedMs=%llu",
            static_cast<unsigned long long>(monotonicMillis() - startedMs)
        );
    }
    return JNI_TRUE;
}

// Live byte-buffer recording entry with native-owned SOURCE history. Codec input buffers are owned by
// MediaCodec immediately after queueInputBuffer/QueueRequest.queue and therefore cannot be retained as
// temporal inputs. This entry copies the already-uploaded current source into a dedicated persistent
// host-visible buffer only after a successful dispatch; the enhanced output never feeds back as history.
// Session, geometry and timestamp discontinuities all seed instead of blending, preventing cross-clip
// contamination, stale-frame ghosting and mode-switch smears.
extern "C" JNIEXPORT jint JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10ExportGpuAccelerator_nativeEnhanceLiveP010InPlace(
    JNIEnv* env,
    jclass,
    jobject currentAndOutputPayload,
    jint width,
    jint height,
    jfloatArray uniforms,
    jlong sessionGeneration,
    jlong timestampNs,
    jlong maximumHistoryGapNs
) {
    auto& state = engine();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!initializeEngine(&state, Npv10PipelineRole::EXPORT_ENHANCE)) {
        NPV10_LOGW("Live P010 GPU enhancement unavailable: %s", state.status.c_str());
        return kLiveEnhanceFailed;
    }
    if (currentAndOutputPayload == nullptr || uniforms == nullptr || width <= 0 || height <= 0 ||
        env->GetArrayLength(uniforms) != static_cast<jsize>(kUniformFloatCount)) {
        state.status = "invalid live P010 JNI input";
        state.liveHistoryValid = false;
        return kLiveEnhanceFailed;
    }

    auto* currentAddress =
        static_cast<uint8_t*>(env->GetDirectBufferAddress(currentAndOutputPayload));
    if (currentAddress == nullptr) {
        state.status = "live P010 direct buffer address unavailable";
        state.liveHistoryValid = false;
        return kLiveEnhanceFailed;
    }
    const uint32_t safeWidth = static_cast<uint32_t>(width);
    const uint32_t safeHeight = static_cast<uint32_t>(height);
    const uint64_t lumaSamples = static_cast<uint64_t>(safeWidth) * safeHeight;
    const uint64_t chromaSamples =
        static_cast<uint64_t>((safeWidth + 1u) / 2u) * ((safeHeight + 1u) / 2u);
    const uint64_t totalSamples = lumaSamples + chromaSamples * 2u;
    const uint64_t expectedPayloadBytes = totalSamples * 2u;
    if (expectedPayloadBytes == 0u ||
        expectedPayloadBytes > static_cast<uint64_t>(std::numeric_limits<jlong>::max()) ||
        env->GetDirectBufferCapacity(currentAndOutputPayload) <
            static_cast<jlong>(expectedPayloadBytes)) {
        state.status = "live P010 payload size mismatch";
        state.liveHistoryValid = false;
        return kLiveEnhanceFailed;
    }

    const uint64_t paddedWords = (totalSamples + 1u) / 2u;
    const VkDeviceSize paddedBytes = static_cast<VkDeviceSize>(paddedWords * sizeof(uint32_t));
    const VkDeviceSize validBytes = static_cast<VkDeviceSize>(expectedPayloadBytes);
    const VkDeviceSize uniformBytes = static_cast<VkDeviceSize>(kUniformFloatCount * sizeof(float));
    if (!ensureFrameResources(&state, paddedBytes, paddedBytes, uniformBytes, paddedBytes) ||
        !ensureLiveHistoryBuffer(&state, paddedBytes)) {
        state.liveHistoryValid = false;
        return kLiveEnhanceFailed;
    }

    jint successCode = kLiveEnhanceSeeded;
    bool useHistory = state.liveHistoryValid;
    if (useHistory && state.liveHistorySessionGeneration != static_cast<int64_t>(sessionGeneration)) {
        useHistory = false;
        successCode = kLiveEnhanceResetSession;
    } else if (useHistory &&
        (state.liveHistoryWidth != safeWidth || state.liveHistoryHeight != safeHeight ||
            state.liveHistoryBytes != validBytes)) {
        useHistory = false;
        successCode = kLiveEnhanceResetGeometry;
    } else if (useHistory &&
        (timestampNs <= 0 || state.liveHistoryTimestampNs <= 0 ||
            timestampNs <= state.liveHistoryTimestampNs || maximumHistoryGapNs <= 0 ||
            timestampNs - state.liveHistoryTimestampNs > maximumHistoryGapNs)) {
        useHistory = false;
        successCode = kLiveEnhanceResetTimestamp;
    } else if (useHistory) {
        successCode = kLiveEnhanceTemporal;
    }

    FrameSlot& slot = state.slots[state.frameSerial++ & 1u];
    if (!copyToMappedMemory(&slot.currentBuffer, currentAddress, validBytes) ||
        !copyToMappedMemory(&slot.outputBuffer, currentAddress, validBytes)) {
        state.status = "live P010 source upload failed";
        state.liveHistoryValid = false;
        return kLiveEnhanceFailed;
    }
    zeroMappedTail(&slot.currentBuffer, validBytes, paddedBytes);
    zeroMappedTail(&slot.outputBuffer, validBytes, paddedBytes);
    if (!useHistory) {
        // Binding 1 must always reference initialized storage even when hasPreviousFrame == 0.
        if (!copyToMappedMemory(&state.livePreviousBuffer, currentAddress, validBytes)) {
            state.status = "live P010 history seed failed";
            state.liveHistoryValid = false;
            return kLiveEnhanceFailed;
        }
        zeroMappedTail(&state.livePreviousBuffer, validBytes, paddedBytes);
    }
    env->GetFloatArrayRegion(
        uniforms,
        0,
        static_cast<jsize>(kUniformFloatCount),
        static_cast<jfloat*>(state.uniformBuffer.mapped)
    );
    if (env->ExceptionCheck() ||
        !dispatchEnhanceP010(
            &state,
            &slot,
            safeWidth,
            safeHeight,
            paddedBytes,
            uniformBytes,
            useHistory,
            &state.livePreviousBuffer
        )) {
        if (state.status == "ready") state.status = "live P010 upload or dispatch failed";
        state.liveHistoryValid = false;
        return kLiveEnhanceFailed;
    }

    std::memcpy(currentAddress, slot.outputBuffer.mapped, static_cast<size_t>(validBytes));
    // Preserve the unprocessed source, not the enhanced output, as the next frame's temporal prior.
    std::memcpy(
        state.livePreviousBuffer.mapped,
        slot.currentBuffer.mapped,
        static_cast<size_t>(validBytes)
    );
    zeroMappedTail(&state.livePreviousBuffer, validBytes, paddedBytes);
    state.liveHistoryValid = true;
    state.liveHistorySessionGeneration = static_cast<int64_t>(sessionGeneration);
    state.liveHistoryTimestampNs = static_cast<int64_t>(timestampNs);
    state.liveHistoryWidth = safeWidth;
    state.liveHistoryHeight = safeHeight;
    state.liveHistoryBytes = validBytes;
    state.status = useHistory ? "ready; live temporal source consumed" : "ready; live history seeded";
    return successCode;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_neoproc_neoproccamx_recording_NativeRecordDevelopGpuBridge_nativeDevelopP010ToRgb10Codes(
    JNIEnv* env,
    jclass,
    jbyteArray currentPayload,
    jint width,
    jint height,
    jfloatArray params
) {
    auto& state = engine();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!initializeEngine(&state, Npv10PipelineRole::RECORD_DEVELOP)) {
        NPV10_LOGW("Record-develop GPU unavailable: %s", state.status.c_str());
        return nullptr;
    }
    if (currentPayload == nullptr || params == nullptr || width <= 0 || height <= 0) {
        state.status = "invalid record-develop JNI input";
        return nullptr;
    }
    if (env->GetArrayLength(params) != static_cast<jsize>(kRecordDevelopParamFloatCount)) {
        state.status = "invalid record-develop params size";
        return nullptr;
    }

    const uint32_t safeWidth = static_cast<uint32_t>(width);
    const uint32_t safeHeight = static_cast<uint32_t>(height);
    const uint64_t pixels = static_cast<uint64_t>(safeWidth) * static_cast<uint64_t>(safeHeight);
    const uint64_t lumaSamples = pixels;
    const uint64_t chromaSamples =
        static_cast<uint64_t>((safeWidth + 1u) / 2u) * static_cast<uint64_t>((safeHeight + 1u) / 2u);
    const uint64_t totalP010Samples = lumaSamples + chromaSamples * 2u;
    const uint64_t expectedPayloadBytes = totalP010Samples * 2u;
    const uint64_t rgbCodeCount = pixels * 3u;
    const uint64_t outputWords = pixels * 2u;
    const uint64_t outputBytes64 = outputWords * sizeof(uint32_t);
    if (expectedPayloadBytes == 0u ||
        expectedPayloadBytes > static_cast<uint64_t>(std::numeric_limits<jsize>::max()) ||
        rgbCodeCount > static_cast<uint64_t>(std::numeric_limits<jsize>::max()) ||
        outputBytes64 > static_cast<uint64_t>(std::numeric_limits<VkDeviceSize>::max())) {
        state.status = "record-develop size overflow";
        return nullptr;
    }
    const jsize currentSize = env->GetArrayLength(currentPayload);
    if (static_cast<uint64_t>(currentSize) != expectedPayloadBytes) {
        state.status = "record-develop payload size mismatch";
        return nullptr;
    }

    const uint64_t paddedWords = (totalP010Samples + 1u) / 2u;
    const VkDeviceSize currentPaddedBytes = static_cast<VkDeviceSize>(paddedWords * sizeof(uint32_t));
    const VkDeviceSize outputBytes = static_cast<VkDeviceSize>(outputBytes64);
    const VkDeviceSize uniformBytes = static_cast<VkDeviceSize>(kRecordDevelopParamFloatCount * sizeof(float));
    if (!ensureFrameResources(&state, currentPaddedBytes, outputBytes, uniformBytes, currentPaddedBytes)) {
        if (state.status == "ready") {
            state.status = "record-develop buffer allocation failed";
        }
        return nullptr;
    }

    FrameSlot& slot = state.slots[state.frameSerial++ & 1u];
    env->GetByteArrayRegion(currentPayload, 0, currentSize, static_cast<jbyte*>(slot.currentBuffer.mapped));
    if (env->ExceptionCheck()) {
        state.status = "record-develop payload read failed";
        return nullptr;
    }
    zeroMappedTail(&slot.currentBuffer, static_cast<VkDeviceSize>(currentSize), currentPaddedBytes);
    std::memcpy(state.previousBuffer.mapped, slot.currentBuffer.mapped, static_cast<size_t>(currentSize));
    zeroMappedTail(&state.previousBuffer, static_cast<VkDeviceSize>(currentSize), currentPaddedBytes);
    env->GetFloatArrayRegion(params, 0, static_cast<jsize>(kRecordDevelopParamFloatCount), static_cast<jfloat*>(state.uniformBuffer.mapped));
    if (env->ExceptionCheck()) {
        state.status = "record-develop params read failed";
        return nullptr;
    }

    if (!dispatchDevelopP010(
            &state,
            &slot,
            safeWidth,
            safeHeight,
            currentPaddedBytes,
            outputBytes,
            uniformBytes,
            kRecordDevelopPackNone,
            0
        )) {
        return nullptr;
    }

    jshortArray result = env->NewShortArray(static_cast<jsize>(rgbCodeCount));
    if (result == nullptr) {
        state.status = "record-develop output allocation failed";
        return nullptr;
    }

    const auto* output = static_cast<const uint32_t*>(slot.outputBuffer.mapped);
    std::vector<jshort> row(static_cast<size_t>(safeWidth) * 3u);
    for (uint32_t y = 0; y < safeHeight; ++y) {
        for (uint32_t x = 0; x < safeWidth; ++x) {
            const uint64_t pixel = static_cast<uint64_t>(y) * safeWidth + x;
            const uint32_t word0 = output[pixel * 2u];
            const uint32_t word1 = output[pixel * 2u + 1u];
            const uint32_t base = x * 3u;
            row[base] = static_cast<jshort>(word0 & 0xFFFFu);
            row[base + 1u] = static_cast<jshort>((word0 >> 16u) & 0xFFFFu);
            row[base + 2u] = static_cast<jshort>(word1 & 0xFFFFu);
        }
        env->SetShortArrayRegion(
            result,
            static_cast<jsize>(static_cast<uint64_t>(y) * safeWidth * 3u),
            static_cast<jsize>(safeWidth * 3u),
            row.data()
        );
        if (env->ExceptionCheck()) {
            state.status = "record-develop output write failed";
            return nullptr;
        }
    }
    state.status = "ready";
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_neoproc_neoproccamx_recording_NativeRecordDevelopGpuBridge_nativeDevelopP010ToPackedPayload(
    JNIEnv* env,
    jclass,
    jbyteArray currentPayload,
    jint width,
    jint height,
    jfloatArray params,
    jint outputFormat
) {
    auto& state = engine();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!initializeEngine(&state, Npv10PipelineRole::RECORD_DEVELOP)) {
        NPV10_LOGW("Record-develop packed GPU unavailable: %s", state.status.c_str());
        return nullptr;
    }
    if (outputFormat != kRecordDevelopOutputR210 && outputFormat != kRecordDevelopOutputV210) {
        state.status = "record-develop packed output format unsupported";
        return nullptr;
    }

    FrameSlot* slot = nullptr;
    uint32_t safeWidth = 0u;
    uint32_t safeHeight = 0u;
    uint64_t pixels = 0u;
    uint64_t packedBytes = 0u;
    const uint32_t packMode = outputFormat == kRecordDevelopOutputR210
        ? kRecordDevelopPackR210
        : kRecordDevelopPackV210;
    if (!prepareAndDispatchRecordDevelop(
            env,
            &state,
            currentPayload,
            width,
            height,
            params,
            &slot,
            &safeWidth,
            &safeHeight,
            &pixels,
            packMode,
            &packedBytes
        )) {
        return nullptr;
    }
    (void)pixels;

    jbyteArray result = env->NewByteArray(static_cast<jsize>(packedBytes));
    if (result == nullptr) {
        state.status = "record-develop packed allocation failed";
        return nullptr;
    }
    env->SetByteArrayRegion(
        result,
        0,
        static_cast<jsize>(packedBytes),
        static_cast<const jbyte*>(state.previousBuffer.mapped)
    );
    if (env->ExceptionCheck()) {
        state.status = "record-develop packed readback failed";
        return nullptr;
    }
    if (result != nullptr) {
        state.status = "ready";
    }
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_neoproc_neoproccamx_recording_NativeRecordDevelopGpuBridge_nativeDevelopP010ToP010420Payload(
    JNIEnv* env,
    jclass,
    jbyteArray currentPayload,
    jint width,
    jint height,
    jfloatArray params,
    jboolean bt709Matrix
) {
    auto& state = engine();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!initializeEngine(&state, Npv10PipelineRole::RECORD_DEVELOP)) {
        NPV10_LOGW("Record-develop P010 GPU unavailable: %s", state.status.c_str());
        return nullptr;
    }

    FrameSlot* slot = nullptr;
    uint32_t safeWidth = 0u;
    uint32_t safeHeight = 0u;
    uint64_t pixels = 0u;
    uint64_t packedBytes = 0u;
    const uint32_t packMode = bt709Matrix == JNI_TRUE
        ? kRecordDevelopPackP010Bt709
        : kRecordDevelopPackP010Bt2020;
    if (!prepareAndDispatchRecordDevelop(
            env,
            &state,
            currentPayload,
            width,
            height,
            params,
            &slot,
            &safeWidth,
            &safeHeight,
            &pixels,
            packMode,
            &packedBytes
        )) {
        return nullptr;
    }
    (void)pixels;
    (void)safeWidth;
    (void)safeHeight;

    jbyteArray result = env->NewByteArray(static_cast<jsize>(packedBytes));
    if (result == nullptr) {
        state.status = "record-develop p010 allocation failed";
        return nullptr;
    }
    env->SetByteArrayRegion(
        result,
        0,
        static_cast<jsize>(packedBytes),
        static_cast<const jbyte*>(state.previousBuffer.mapped)
    );
    if (env->ExceptionCheck()) {
        state.status = "record-develop p010 readback failed";
        return nullptr;
    }
    if (result != nullptr) {
        state.status = "ready";
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10ExportGpuAccelerator_nativeStatus(
    JNIEnv* env,
    jclass
) {
    auto& state = engine();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!initializeEngine(&state, Npv10PipelineRole::EXPORT_ENHANCE)) {
        return env->NewStringUTF(state.status.c_str());
    }
    return env->NewStringUTF("ready");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_recording_NativeRecordDevelopGpuBridge_nativeStatus(
    JNIEnv* env,
    jclass
) {
    auto& state = engine();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!initializeEngine(&state, Npv10PipelineRole::RECORD_DEVELOP)) {
        return env->NewStringUTF(state.status.c_str());
    }
    return env->NewStringUTF("ready");
}

// ── Live-cinema parity luma enhance (GPU port of enhanceLiveCinemaLumaP010) ──────────────────────
// Runs the edge-aware bilateral + halo-guarded unsharp on the P010 LUMA plane only; chroma passes
// through byte-identical (output is pre-seeded with the input and only luma words are overwritten).
// Returns null on ANY unavailability/failure/timeout so the converter falls back to the byte-exact
// CPU parity pass — the parity gate + this fallback make the worst case identical to today's output.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_neoproc_neoproccamx_recording_RecordDevelopParityEnhanceGpuBridge_nativeEnhanceLumaP010(
    JNIEnv* env,
    jclass,
    jbyteArray currentPayload,
    jint width,
    jint height,
    jfloat uSharpness,
    jfloat uDenoise
) {
    auto& state = engine();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!initializeEngine(&state, Npv10PipelineRole::RECORD_DEVELOP)) {
        NPV10_LOGW("Parity-enhance GPU unavailable: %s", state.status.c_str());
        return nullptr;
    }
    if (!state.parityEnhanceSupported) {
        // Optional pipeline never built on this device, or disabled after a prior dispatch timeout.
        return nullptr;
    }
    if (currentPayload == nullptr || width <= 0 || height <= 0) {
        state.status = "invalid parity-enhance JNI input";
        return nullptr;
    }

    const uint32_t safeWidth = static_cast<uint32_t>(width);
    const uint32_t safeHeight = static_cast<uint32_t>(height);
    const uint64_t lumaSamples = static_cast<uint64_t>(safeWidth) * static_cast<uint64_t>(safeHeight);
    const uint64_t chromaSamples =
        static_cast<uint64_t>((safeWidth + 1u) / 2u) * static_cast<uint64_t>((safeHeight + 1u) / 2u);
    const uint64_t totalSamples = lumaSamples + chromaSamples * 2u;
    const uint64_t expectedPayloadBytes = totalSamples * 2u;
    if (expectedPayloadBytes == 0u ||
        expectedPayloadBytes > static_cast<uint64_t>(std::numeric_limits<jsize>::max())) {
        state.status = "parity-enhance size overflow";
        return nullptr;
    }
    const jsize currentSize = env->GetArrayLength(currentPayload);
    if (static_cast<uint64_t>(currentSize) != expectedPayloadBytes) {
        state.status = "parity-enhance payload size mismatch";
        return nullptr;
    }

    const uint64_t paddedWords = (totalSamples + 1u) / 2u;
    const VkDeviceSize paddedBytes = static_cast<VkDeviceSize>(paddedWords * sizeof(uint32_t));
    const VkDeviceSize uniformBytes =
        static_cast<VkDeviceSize>(kParityEnhanceUniformFloatCount * sizeof(float));
    if (!ensureFrameResources(&state, paddedBytes, paddedBytes, uniformBytes, paddedBytes)) {
        if (state.status == "ready") {
            state.status = "parity-enhance buffer allocation failed";
        }
        return nullptr;
    }

    FrameSlot& slot = state.slots[state.frameSerial++ & 1u];
    env->GetByteArrayRegion(currentPayload, 0, currentSize, static_cast<jbyte*>(slot.currentBuffer.mapped));
    if (env->ExceptionCheck()) {
        state.status = "parity-enhance payload region read failed";
        return nullptr;
    }
    zeroMappedTail(&slot.currentBuffer, static_cast<VkDeviceSize>(currentSize), paddedBytes);
    // Binding 1 (previous) is bound but never read; seed it so validation layers see no stale read.
    std::memcpy(state.previousBuffer.mapped, slot.currentBuffer.mapped, static_cast<size_t>(currentSize));
    zeroMappedTail(&state.previousBuffer, static_cast<VkDeviceSize>(currentSize), paddedBytes);
    // Pre-seed the output with the full input so the CHROMA plane is byte-identical after the
    // luma-only compute (the shader overwrites only luma words).
    std::memcpy(slot.outputBuffer.mapped, slot.currentBuffer.mapped, static_cast<size_t>(currentSize));
    zeroMappedTail(&slot.outputBuffer, static_cast<VkDeviceSize>(currentSize), paddedBytes);
    const float parityUniforms[kParityEnhanceUniformFloatCount] = {
        static_cast<float>(uSharpness),
        static_cast<float>(uDenoise)
    };
    std::memcpy(state.uniformBuffer.mapped, parityUniforms, static_cast<size_t>(uniformBytes));

    if (!dispatchParityEnhanceP010(&state, &slot, safeWidth, safeHeight, paddedBytes, uniformBytes)) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(currentSize);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, currentSize, static_cast<const jbyte*>(slot.outputBuffer.mapped));
    }
    state.status = "ready";
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_recording_RecordDevelopParityEnhanceGpuBridge_nativeStatus(
    JNIEnv* env,
    jclass
) {
    auto& state = engine();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!initializeEngine(&state, Npv10PipelineRole::RECORD_DEVELOP)) {
        return env->NewStringUTF(state.status.c_str());
    }
    if (!state.parityEnhanceSupported) {
        return env->NewStringUTF("parity-enhance pipeline unavailable");
    }
    return env->NewStringUTF("ready");
}

// ── Record-develop CHROMA enhance lane (GPU port of the CPU-only enhanceP010 chroma passes) ──────
// Runs 5 sequential passes (temporal, chroma-NR, deadband, spike, dither) on the P010 buffer, ping-
// ponging currentBuffer<->outputBuffer between per-pass bounded submits. Host-visible mapped buffers
// let us pre-seed each pass's output with its input by memcpy so unmodified samples pass through
// (mirrors the CPU output=copyOf + per-pass snapshot). Returns the buffer holding the final result.
bool dispatchDevelopEnhanceLaneP010(
    Npv10GpuEngine* state,
    FrameSlot* slot,
    uint32_t safeWidth,
    uint32_t safeHeight,
    VkDeviceSize currentBytes,
    VkDeviceSize paddedBytes,
    VkDeviceSize uniformBytes,
    bool hasPrevious,
    HostVisibleBuffer** outResult
) {
    if (state->enhanceLanePipeline == VK_NULL_HANDLE) {
        state->status = "enhance-lane pipeline unavailable";
        return false;
    }
    const uint64_t lumaSamples = static_cast<uint64_t>(safeWidth) * static_cast<uint64_t>(safeHeight);
    const uint64_t chromaSamples =
        static_cast<uint64_t>((safeWidth + 1u) / 2u) * static_cast<uint64_t>((safeHeight + 1u) / 2u);
    const uint64_t totalShorts = lumaSamples + chromaSamples * 2u;

    struct PassSpec { uint32_t pass; uint64_t count; };
    const PassSpec passes[5] = {
        {kEnhanceLanePassTemporal, totalShorts},
        {kEnhanceLanePassChromaNr, chromaSamples},
        {kEnhanceLanePassDeadband, chromaSamples},
        {kEnhanceLanePassSpike, chromaSamples},
        {kEnhanceLanePassDither, lumaSamples},
    };

    HostVisibleBuffer* inBuf = &slot->currentBuffer;
    HostVisibleBuffer* outBuf = &slot->outputBuffer;

    for (const PassSpec& spec : passes) {
        // BOUNDED slot-ready wait: a prior hung/TDR'd dispatch bounds the block instead of deadlocking.
        // #16 fix: shared consecutive-timeout counter — a single transient stall routes this frame to CPU;
        // the engine disables only after a streak.
        if (!waitFenceBounded(state, slot->fence, kParityEnhanceFenceTimeoutNs, "enhance-lane-slot")) {
            return false;
        }
        // Pre-seed this pass's output with its input so unmodified samples carry through; then the swap
        // below makes the next pass read this pass's result (the CPU per-pass `original = snapshot`).
        std::memcpy(outBuf->mapped, inBuf->mapped, static_cast<size_t>(currentBytes));
        zeroMappedTail(outBuf, currentBytes, paddedBytes);

        std::array<VkDescriptorBufferInfo, 4> infos{};
        infos[0] = {inBuf->buffer, 0, paddedBytes};
        infos[1] = {state->previousBuffer.buffer, 0, paddedBytes};
        infos[2] = {outBuf->buffer, 0, paddedBytes};
        infos[3] = {state->uniformBuffer.buffer, 0, uniformBytes};
        std::array<VkWriteDescriptorSet, 4> writes{};
        for (uint32_t index = 0; index < writes.size(); ++index) {
            writes[index].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[index].dstSet = slot->descriptorSet;
            writes[index].dstBinding = index;
            writes[index].descriptorCount = 1;
            writes[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            writes[index].pBufferInfo = &infos[index];
        }
        vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);

        if (!check(vkResetCommandBuffer(slot->commandBuffer, 0), "vkResetCommandBuffer(enhance-lane)")) {
            state->status = "enhance-lane command reset failed";
            return false;
        }
        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (!check(vkBeginCommandBuffer(slot->commandBuffer, &beginInfo), "vkBeginCommandBuffer(enhance-lane)")) {
            state->status = "enhance-lane command begin failed";
            return false;
        }
        const Npv10PushConstants pushConstants{safeWidth, safeHeight, hasPrevious ? 1u : 0u, spec.pass};
        vkCmdBindPipeline(slot->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->enhanceLanePipeline);
        vkCmdBindDescriptorSets(
            slot->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->pipelineLayout,
            0, 1, &slot->descriptorSet, 0, nullptr
        );
        vkCmdPushConstants(
            slot->commandBuffer, state->pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
            0, sizeof(Npv10PushConstants), &pushConstants
        );
        const uint32_t groups = static_cast<uint32_t>((spec.count + 255u) / 256u);
        vkCmdDispatch(slot->commandBuffer, groups == 0u ? 1u : groups, 1u, 1u);

        VkMemoryBarrier memoryBarrier{};
        memoryBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        memoryBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        memoryBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT;
        vkCmdPipelineBarrier(
            slot->commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
            0, 1, &memoryBarrier, 0, nullptr, 0, nullptr
        );

        if (!check(vkEndCommandBuffer(slot->commandBuffer), "vkEndCommandBuffer(enhance-lane)")) {
            state->status = "enhance-lane command end failed";
            return false;
        }
        if (!check(vkResetFences(state->device, 1, &slot->fence), "vkResetFences(enhance-lane)")) {
            state->status = "enhance-lane fence reset failed";
            return false;
        }
        VkSubmitInfo submitInfo{};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &slot->commandBuffer;
        if (!check(vkQueueSubmit(state->computeQueue, 1, &submitInfo, slot->fence), "vkQueueSubmit(enhance-lane)")) {
            state->status = "enhance-lane dispatch failed";
            VkSubmitInfo fenceSignalSubmit{};
            fenceSignalSubmit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            vkQueueSubmit(state->computeQueue, 1, &fenceSignalSubmit, slot->fence);
            return false;
        }
        // #16 fix: bounded completion wait via the shared consecutive-timeout counter (see waitFenceBounded).
        if (!waitFenceBounded(state, slot->fence, kParityEnhanceFenceTimeoutNs, "enhance-lane-dispatch")) {
            return false;
        }
        std::swap(inBuf, outBuf);
    }
    // After the final swap, inBuf points to the buffer written by the last pass (dither).
    *outResult = inBuf;
    return true;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_neoproc_neoproccamx_recording_RecordDevelopEnhanceLaneGpuBridge_nativeEnhanceLaneP010(
    JNIEnv* env,
    jclass,
    jbyteArray currentPayload,
    jbyteArray previousPayload,
    jint width,
    jint height,
    jfloatArray uniforms
) {
    auto& state = engine();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!initializeEngine(&state, Npv10PipelineRole::RECORD_DEVELOP)) {
        NPV10_LOGW("Enhance-lane GPU unavailable: %s", state.status.c_str());
        return nullptr;
    }
    if (!state.enhanceLaneSupported) {
        return nullptr;
    }
    if (currentPayload == nullptr || uniforms == nullptr || width <= 0 || height <= 0) {
        state.status = "invalid enhance-lane JNI input";
        return nullptr;
    }
    if (env->GetArrayLength(uniforms) != static_cast<jsize>(kEnhanceLaneUniformFloatCount)) {
        state.status = "invalid enhance-lane uniforms size";
        return nullptr;
    }

    const uint32_t safeWidth = static_cast<uint32_t>(width);
    const uint32_t safeHeight = static_cast<uint32_t>(height);
    const uint64_t lumaSamples = static_cast<uint64_t>(safeWidth) * static_cast<uint64_t>(safeHeight);
    const uint64_t chromaSamples =
        static_cast<uint64_t>((safeWidth + 1u) / 2u) * static_cast<uint64_t>((safeHeight + 1u) / 2u);
    const uint64_t totalSamples = lumaSamples + chromaSamples * 2u;
    const uint64_t expectedPayloadBytes = totalSamples * 2u;
    if (expectedPayloadBytes == 0u ||
        expectedPayloadBytes > static_cast<uint64_t>(std::numeric_limits<jsize>::max())) {
        state.status = "enhance-lane size overflow";
        return nullptr;
    }
    const jsize currentSize = env->GetArrayLength(currentPayload);
    if (static_cast<uint64_t>(currentSize) != expectedPayloadBytes) {
        state.status = "enhance-lane payload size mismatch";
        return nullptr;
    }
    const bool hasPrevious =
        previousPayload != nullptr && env->GetArrayLength(previousPayload) == currentSize;

    const uint64_t paddedWords = (totalSamples + 1u) / 2u;
    const VkDeviceSize paddedBytes = static_cast<VkDeviceSize>(paddedWords * sizeof(uint32_t));
    const VkDeviceSize uniformBytes =
        static_cast<VkDeviceSize>(kEnhanceLaneUniformFloatCount * sizeof(float));
    if (!ensureFrameResources(&state, paddedBytes, paddedBytes, uniformBytes, paddedBytes)) {
        if (state.status == "ready") {
            state.status = "enhance-lane buffer allocation failed";
        }
        return nullptr;
    }

    FrameSlot& slot = state.slots[state.frameSerial++ & 1u];
    env->GetByteArrayRegion(currentPayload, 0, currentSize, static_cast<jbyte*>(slot.currentBuffer.mapped));
    if (env->ExceptionCheck()) {
        state.status = "enhance-lane payload region read failed";
        return nullptr;
    }
    zeroMappedTail(&slot.currentBuffer, static_cast<VkDeviceSize>(currentSize), paddedBytes);
    if (hasPrevious) {
        env->GetByteArrayRegion(previousPayload, 0, currentSize, static_cast<jbyte*>(state.previousBuffer.mapped));
        if (env->ExceptionCheck()) {
            state.status = "enhance-lane previous region read failed";
            return nullptr;
        }
    } else {
        std::memcpy(state.previousBuffer.mapped, slot.currentBuffer.mapped, static_cast<size_t>(currentSize));
    }
    zeroMappedTail(&state.previousBuffer, static_cast<VkDeviceSize>(currentSize), paddedBytes);
    env->GetFloatArrayRegion(uniforms, 0, static_cast<jsize>(kEnhanceLaneUniformFloatCount), static_cast<jfloat*>(state.uniformBuffer.mapped));
    if (env->ExceptionCheck()) {
        state.status = "enhance-lane uniforms read failed";
        return nullptr;
    }

    HostVisibleBuffer* resultBuf = nullptr;
    if (!dispatchDevelopEnhanceLaneP010(
            &state, &slot, safeWidth, safeHeight,
            static_cast<VkDeviceSize>(currentSize), paddedBytes, uniformBytes, hasPrevious, &resultBuf
        ) || resultBuf == nullptr) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(currentSize);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, currentSize, static_cast<const jbyte*>(resultBuf->mapped));
    }
    state.status = "ready";
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_recording_RecordDevelopEnhanceLaneGpuBridge_nativeStatus(
    JNIEnv* env,
    jclass
) {
    auto& state = engine();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!initializeEngine(&state, Npv10PipelineRole::RECORD_DEVELOP)) {
        return env->NewStringUTF(state.status.c_str());
    }
    if (!state.enhanceLaneSupported) {
        return env->NewStringUTF("enhance-lane pipeline unavailable");
    }
    return env->NewStringUTF("ready");
}
