#include "niv99_vulkan_pipeline.h"

#include "generated/niv99_motion_confidence_comp_spv.h"
#include "generated/niv99_quality_telemetry_comp_spv.h"
#include "generated/niv99_raw_denoise_comp_spv.h"
#include "generated/niv99_reconstruction_comp_spv.h"
#include "generated/niv99_statistics_comp_spv.h"
#include "generated/niv99_temporal_fusion_comp_spv.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <memory>
#include <new>

namespace neoproc::niv99 {
namespace {

constexpr uint32_t kStatisticsBufferBinding = 12u;
constexpr uint32_t kTileControlBufferBinding = 13u;
constexpr uint32_t kTelemetryBufferBinding = 14u;
constexpr uint32_t kUniformBufferBinding = 15u;
constexpr uint32_t kImageBindingCount = 12u;
constexpr uint32_t kStorageBufferBindingCount = 3u;
constexpr uint32_t kImageLocalSize = 16u;
constexpr uint32_t kStatisticsSharedMemoryBytes = 5'376u;
constexpr uint64_t kCloseTimelineTimeoutNs = 2'000'000'000ull;

constexpr size_t stageIndex(ShaderStage stage) noexcept {
    return static_cast<size_t>(stage);
}

constexpr uint32_t divideRoundUp(uint32_t value, uint32_t divisor) noexcept {
    return value == 0u || divisor == 0u ? 0u : 1u + (value - 1u) / divisor;
}

uint64_t joinWords(uint32_t low, uint32_t high) noexcept {
    return static_cast<uint64_t>(low) | (static_cast<uint64_t>(high) << 32u);
}

bool validImageBinding(const ImageBinding& binding, VkFormat expectedFormat) noexcept {
    return binding.image != VK_NULL_HANDLE && binding.view != VK_NULL_HANDLE &&
           binding.format == expectedFormat && binding.layout == VK_IMAGE_LAYOUT_GENERAL;
}

void shaderBufferBarrier(
    VkCommandBuffer commandBuffer,
    VkBuffer buffer,
    VkDeviceSize size,
    VkAccessFlags sourceAccess,
    VkAccessFlags destinationAccess
) noexcept {
    VkBufferMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    barrier.srcAccessMask = sourceAccess;
    barrier.dstAccessMask = destinationAccess;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.buffer = buffer;
    barrier.offset = 0u;
    barrier.size = size;
    vkCmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0u,
        0u,
        nullptr,
        1u,
        &barrier,
        0u,
        nullptr
    );
}

void shaderImageBarrier(
    VkCommandBuffer commandBuffer,
    VkImage image,
    VkAccessFlags sourceAccess,
    VkAccessFlags destinationAccess
) noexcept {
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = sourceAccess;
    barrier.dstAccessMask = destinationAccess;
    barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.baseMipLevel = 0u;
    barrier.subresourceRange.levelCount = 1u;
    barrier.subresourceRange.baseArrayLayer = 0u;
    barrier.subresourceRange.layerCount = 1u;
    vkCmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0u,
        0u,
        nullptr,
        0u,
        nullptr,
        1u,
        &barrier
    );
}

}  // namespace

bool FrameIdentity::complete() const noexcept {
    return cameraIdHash != 0u && sensorTimestampNs != 0u;
}

bool FrameIdentity::sameFrame(const FrameIdentity& other) const noexcept {
    return cameraIdHash == other.cameraIdHash &&
           sessionGeneration == other.sessionGeneration &&
           frameNumber == other.frameNumber &&
           sensorTimestampNs == other.sensorTimestampNs;
}

VulkanPipeline::~VulkanPipeline() {
    (void)close();
}

Status VulkanPipeline::initialize(
    const DeviceContext& context,
    const Configuration& configuration
) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (closed_) return Status::kClosed;
    if (initialized_) {
        const bool sameContext = context.physicalDevice == context_.physicalDevice &&
                                 context.device == context_.device &&
                                 context.queue == context_.queue &&
                                 context.queueFamilyIndex == context_.queueFamilyIndex &&
                                 context.pipelineCache == context_.pipelineCache &&
                                 context.timelineSemaphoreEnabled == context_.timelineSemaphoreEnabled &&
                                 context.storageImageExtendedFormatsEnabled ==
                                     context_.storageImageExtendedFormatsEnabled &&
                                 context.queueMutex == context_.queueMutex;
        const bool sameConfiguration = configuration.width == configuration_.width &&
                                       configuration.height == configuration_.height &&
                                       configuration.tileColumns == configuration_.tileColumns &&
                                       configuration.tileRows == configuration_.tileRows &&
                                       configuration.maximumInFlight == configuration_.maximumInFlight;
        if (sameContext && sameConfiguration) return Status::kOk;
        setFailure("NIV99 cannot be reinitialized with a different device or graph configuration");
        return Status::kInvalidArgument;
    }
    if (context.physicalDevice == VK_NULL_HANDLE || context.device == VK_NULL_HANDLE ||
        context.queue == VK_NULL_HANDLE || !context.timelineSemaphoreEnabled ||
        !context.storageImageExtendedFormatsEnabled
    ) {
        setFailure(
            "missing Vulkan device, queue, timeline semaphore, or extended storage-image feature"
        );
        return Status::kUnsupported;
    }
    context_ = context;
    configuration_ = configuration;
    const auto abandonInitialization = [this](Status status) noexcept {
        destroyResourcesLocked(false);
        context_ = DeviceContext{};
        configuration_ = Configuration{};
        return status;
    };
    Status status = validateConfiguration(configuration_);
    if (status != Status::kOk) return abandonInitialization(status);
    status = validateFormats();
    if (status != Status::kOk) return abandonInitialization(status);
    status = createDescriptorInfrastructure();
    if (status != Status::kOk) return abandonInitialization(status);
    status = createPipelines();
    if (status != Status::kOk) return abandonInitialization(status);
    status = createSynchronization();
    if (status != Status::kOk) return abandonInitialization(status);
    status = allocateCommandBuffers();
    if (status != Status::kOk) return abandonInitialization(status);
    status = createSlotBuffers();
    if (status != Status::kOk) return abandonInitialization(status);
    generation_ = 0u;
    lastSubmittedTimelineValue_ = 0u;
    latestSubmittedIdentity_ = FrameIdentity{};
    hasLatestSubmittedIdentity_ = false;
    runtimeFailed_ = false;
    initialized_ = true;
    failureReason_[0] = '\0';
    return Status::kOk;
}

Status VulkanPipeline::validateConfiguration(const Configuration& configuration) noexcept {
    if (configuration.width == 0u || configuration.height == 0u ||
        configuration.tileColumns == 0u || configuration.tileRows == 0u ||
        configuration.maximumInFlight == 0u ||
        configuration.maximumInFlight > kMaximumInFlightFrames
    ) {
        setFailure("invalid extent, tile grid, or in-flight capacity");
        return Status::kInvalidArgument;
    }
    const uint64_t tileCount = static_cast<uint64_t>(configuration.tileColumns) *
                               static_cast<uint64_t>(configuration.tileRows);
    if (tileCount == 0u || tileCount > std::numeric_limits<uint32_t>::max() ||
        tileCount > std::numeric_limits<VkDeviceSize>::max() / kTelemetryRecordBytes
    ) {
        setFailure("tile grid exceeds bounded NIV99 buffer capacity");
        return Status::kInvalidArgument;
    }
    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(context_.physicalDevice, &properties);
    uint32_t queueFamilyCount = 0u;
    vkGetPhysicalDeviceQueueFamilyProperties(
        context_.physicalDevice,
        &queueFamilyCount,
        nullptr
    );
    if (queueFamilyCount == 0u || context_.queueFamilyIndex >= queueFamilyCount) {
        setFailure("NIV99 queue-family index is unavailable on the selected physical device");
        return Status::kUnsupported;
    }
    const auto queueFamilies = std::unique_ptr<VkQueueFamilyProperties[]>(
        new (std::nothrow) VkQueueFamilyProperties[queueFamilyCount]
    );
    if (queueFamilies == nullptr) {
        setFailure("NIV99 could not allocate queue-family capability storage");
        return Status::kOutOfMemory;
    }
    uint32_t queriedQueueFamilyCount = queueFamilyCount;
    vkGetPhysicalDeviceQueueFamilyProperties(
        context_.physicalDevice,
        &queriedQueueFamilyCount,
        queueFamilies.get()
    );
    if (context_.queueFamilyIndex >= queriedQueueFamilyCount ||
        (queueFamilies[context_.queueFamilyIndex].queueFlags & VK_QUEUE_COMPUTE_BIT) == 0u
    ) {
        setFailure("NIV99 requires a compute-capable queue family");
        return Status::kUnsupported;
    }
    if (configuration.width > properties.limits.maxImageDimension2D ||
        configuration.height > properties.limits.maxImageDimension2D ||
        divideRoundUp(configuration.width, kImageLocalSize) >
            properties.limits.maxComputeWorkGroupCount[0] ||
        divideRoundUp(configuration.height, kImageLocalSize) >
            properties.limits.maxComputeWorkGroupCount[1] ||
        configuration.tileColumns > properties.limits.maxComputeWorkGroupCount[0] ||
        configuration.tileRows > properties.limits.maxComputeWorkGroupCount[1] ||
        properties.limits.maxComputeWorkGroupInvocations < 256u ||
        properties.limits.maxComputeWorkGroupSize[0] < 16u ||
        properties.limits.maxComputeWorkGroupSize[1] < 16u ||
        properties.limits.maxComputeSharedMemorySize < kStatisticsSharedMemoryBytes ||
        properties.limits.maxDescriptorSetStorageImages < kImageBindingCount ||
        properties.limits.maxPerStageDescriptorStorageImages < kImageBindingCount ||
        properties.limits.maxDescriptorSetStorageBuffers < kStorageBufferBindingCount ||
        properties.limits.maxPerStageDescriptorStorageBuffers < kStorageBufferBindingCount ||
        properties.limits.maxDescriptorSetUniformBuffers < 1u ||
        properties.limits.maxPerStageDescriptorUniformBuffers < 1u
    ) {
        setFailure("device compute or descriptor limits cannot execute the NIV99 graph");
        return Status::kUnsupported;
    }
    const uint64_t tileControlBytes = tileCount * kTileControlBytes;
    const uint64_t statisticsBytes = tileCount * kTileStatisticsBytes;
    const uint64_t telemetryBytes = tileCount * kTelemetryRecordBytes;
    if (tileControlBytes > properties.limits.maxStorageBufferRange ||
        statisticsBytes > properties.limits.maxStorageBufferRange ||
        telemetryBytes > properties.limits.maxStorageBufferRange ||
        kUniformBytes > properties.limits.maxUniformBufferRange
    ) {
        setFailure("NIV99 buffers exceed device descriptor ranges");
        return Status::kUnsupported;
    }
    return Status::kOk;
}

Status VulkanPipeline::validateFormats() noexcept {
    constexpr std::array<VkFormat, 5u> requiredFormats{
        VK_FORMAT_R16_UINT,
        VK_FORMAT_R8_UINT,
        VK_FORMAT_R32_SFLOAT,
        VK_FORMAT_R16G16_SFLOAT,
        VK_FORMAT_R16G16B16A16_SFLOAT,
    };
    for (VkFormat format : requiredFormats) {
        VkFormatProperties properties{};
        vkGetPhysicalDeviceFormatProperties(context_.physicalDevice, format, &properties);
        if ((properties.optimalTilingFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0u) {
            setFailure("required NIV99 storage-image format is unavailable");
            return Status::kUnsupported;
        }
    }
    return Status::kOk;
}

Status VulkanPipeline::createDescriptorInfrastructure() noexcept {
    std::array<VkDescriptorSetLayoutBinding, kDescriptorBindingCount> bindings{};
    for (uint32_t binding = 0u; binding < kImageBindingCount; ++binding) {
        bindings[binding].binding = binding;
        bindings[binding].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[binding].descriptorCount = 1u;
        bindings[binding].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    for (uint32_t binding = kStatisticsBufferBinding; binding <= kTelemetryBufferBinding; ++binding) {
        bindings[binding].binding = binding;
        bindings[binding].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[binding].descriptorCount = 1u;
        bindings[binding].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    bindings[kUniformBufferBinding].binding = kUniformBufferBinding;
    bindings[kUniformBufferBinding].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    bindings[kUniformBufferBinding].descriptorCount = 1u;
    bindings[kUniformBufferBinding].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo setLayoutInfo{};
    setLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setLayoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
    setLayoutInfo.pBindings = bindings.data();
    if (vkCreateDescriptorSetLayout(
            context_.device, &setLayoutInfo, nullptr, &descriptorSetLayout_) != VK_SUCCESS
    ) {
        setFailure("vkCreateDescriptorSetLayout failed");
        return Status::kVulkanError;
    }

    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1u;
    pipelineLayoutInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkCreatePipelineLayout(
            context_.device, &pipelineLayoutInfo, nullptr, &pipelineLayout_) != VK_SUCCESS
    ) {
        setFailure("vkCreatePipelineLayout failed");
        return Status::kVulkanError;
    }

    const uint32_t slotCount = configuration_.maximumInFlight;
    const std::array<VkDescriptorPoolSize, 3u> poolSizes{{
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, kImageBindingCount * slotCount},
        {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, kStorageBufferBindingCount * slotCount},
        {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, slotCount},
    }};
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = slotCount;
    poolInfo.poolSizeCount = static_cast<uint32_t>(poolSizes.size());
    poolInfo.pPoolSizes = poolSizes.data();
    if (vkCreateDescriptorPool(context_.device, &poolInfo, nullptr, &descriptorPool_) != VK_SUCCESS) {
        setFailure("vkCreateDescriptorPool failed");
        return Status::kVulkanError;
    }

    std::array<VkDescriptorSetLayout, kMaximumInFlightFrames> layouts{};
    std::fill_n(layouts.begin(), slotCount, descriptorSetLayout_);
    std::array<VkDescriptorSet, kMaximumInFlightFrames> descriptorSets{};
    VkDescriptorSetAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocateInfo.descriptorPool = descriptorPool_;
    allocateInfo.descriptorSetCount = slotCount;
    allocateInfo.pSetLayouts = layouts.data();
    if (vkAllocateDescriptorSets(context_.device, &allocateInfo, descriptorSets.data()) != VK_SUCCESS) {
        setFailure("vkAllocateDescriptorSets failed");
        return Status::kVulkanError;
    }
    for (uint32_t slot = 0u; slot < slotCount; ++slot) {
        slots_[slot].descriptorSet = descriptorSets[slot];
    }
    return Status::kOk;
}

Status VulkanPipeline::createComputePipeline(
    const uint32_t* words,
    size_t wordCount,
    VkPipeline* output
) noexcept {
    if (words == nullptr || wordCount == 0u || output == nullptr) return Status::kInvalidArgument;
    VkShaderModuleCreateInfo moduleInfo{};
    moduleInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    moduleInfo.codeSize = wordCount * sizeof(uint32_t);
    moduleInfo.pCode = words;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(context_.device, &moduleInfo, nullptr, &module) != VK_SUCCESS) {
        setFailure("vkCreateShaderModule failed for an NIV99 stage");
        return Status::kVulkanError;
    }
    VkPipelineShaderStageCreateInfo shaderStage{};
    shaderStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    shaderStage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    shaderStage.module = module;
    shaderStage.pName = "main";
    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = shaderStage;
    pipelineInfo.layout = pipelineLayout_;
    const VkResult result = vkCreateComputePipelines(
        context_.device,
        context_.pipelineCache,
        1u,
        &pipelineInfo,
        nullptr,
        output
    );
    vkDestroyShaderModule(context_.device, module, nullptr);
    if (result != VK_SUCCESS) {
        setFailure("vkCreateComputePipelines failed for an NIV99 stage");
        return Status::kVulkanError;
    }
    return Status::kOk;
}

Status VulkanPipeline::createPipelines() noexcept {
    struct ShaderBinary {
        const uint32_t* words;
        size_t count;
        ShaderStage stage;
    };
    const std::array<ShaderBinary, kShaderStageCount> binaries{{
        {kNiv99StatisticsCompSpv, kNiv99StatisticsCompSpvCount, ShaderStage::kStatistics},
        {kNiv99RawDenoiseCompSpv, kNiv99RawDenoiseCompSpvCount, ShaderStage::kRawDenoise},
        {kNiv99MotionConfidenceCompSpv, kNiv99MotionConfidenceCompSpvCount,
            ShaderStage::kMotionConfidence},
        {kNiv99TemporalFusionCompSpv, kNiv99TemporalFusionCompSpvCount,
            ShaderStage::kTemporalFusion},
        {kNiv99ReconstructionCompSpv, kNiv99ReconstructionCompSpvCount,
            ShaderStage::kReconstruction},
        {kNiv99QualityTelemetryCompSpv, kNiv99QualityTelemetryCompSpvCount,
            ShaderStage::kQualityTelemetry},
    }};
    for (const ShaderBinary& binary : binaries) {
        const Status status = createComputePipeline(
            binary.words,
            binary.count,
            &pipelines_[stageIndex(binary.stage)]
        );
        if (status != Status::kOk) return status;
    }
    return Status::kOk;
}

Status VulkanPipeline::createSynchronization() noexcept {
    getSemaphoreCounterValue_ = reinterpret_cast<PFN_vkGetSemaphoreCounterValueKHR>(
        vkGetDeviceProcAddr(context_.device, "vkGetSemaphoreCounterValueKHR")
    );
    if (getSemaphoreCounterValue_ == nullptr) {
        getSemaphoreCounterValue_ = reinterpret_cast<PFN_vkGetSemaphoreCounterValueKHR>(
            vkGetDeviceProcAddr(context_.device, "vkGetSemaphoreCounterValue")
        );
    }
    waitSemaphores_ = reinterpret_cast<PFN_vkWaitSemaphoresKHR>(
        vkGetDeviceProcAddr(context_.device, "vkWaitSemaphoresKHR")
    );
    if (waitSemaphores_ == nullptr) {
        waitSemaphores_ = reinterpret_cast<PFN_vkWaitSemaphoresKHR>(
            vkGetDeviceProcAddr(context_.device, "vkWaitSemaphores")
        );
    }
    if (getSemaphoreCounterValue_ == nullptr || waitSemaphores_ == nullptr) {
        setFailure("timeline semaphore entry points are unavailable");
        return Status::kUnsupported;
    }

    VkSemaphoreTypeCreateInfo typeInfo{};
    typeInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO;
    typeInfo.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
    typeInfo.initialValue = 0u;
    VkSemaphoreCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    createInfo.pNext = &typeInfo;
    if (vkCreateSemaphore(context_.device, &createInfo, nullptr, &timelineSemaphore_) != VK_SUCCESS) {
        setFailure("vkCreateSemaphore failed for the NIV99 timeline");
        return Status::kVulkanError;
    }
    return Status::kOk;
}

Status VulkanPipeline::allocateCommandBuffers() noexcept {
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = context_.queueFamilyIndex;
    if (vkCreateCommandPool(context_.device, &poolInfo, nullptr, &commandPool_) != VK_SUCCESS) {
        setFailure("vkCreateCommandPool failed for NIV99");
        return Status::kVulkanError;
    }
    std::array<VkCommandBuffer, kMaximumInFlightFrames> commandBuffers{};
    VkCommandBufferAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocateInfo.commandPool = commandPool_;
    allocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocateInfo.commandBufferCount = configuration_.maximumInFlight;
    if (vkAllocateCommandBuffers(
            context_.device, &allocateInfo, commandBuffers.data()) != VK_SUCCESS
    ) {
        setFailure("vkAllocateCommandBuffers failed for NIV99");
        return Status::kVulkanError;
    }
    for (uint32_t slot = 0u; slot < configuration_.maximumInFlight; ++slot) {
        slots_[slot].commandBuffer = commandBuffers[slot];
    }
    return Status::kOk;
}

uint32_t VulkanPipeline::findMemoryType(
    uint32_t bits,
    VkMemoryPropertyFlags required
) const noexcept {
    VkPhysicalDeviceMemoryProperties properties{};
    vkGetPhysicalDeviceMemoryProperties(context_.physicalDevice, &properties);
    for (uint32_t index = 0u; index < properties.memoryTypeCount; ++index) {
        if ((bits & (1u << index)) != 0u &&
            (properties.memoryTypes[index].propertyFlags & required) == required
        ) {
            return index;
        }
    }
    return std::numeric_limits<uint32_t>::max();
}

Status VulkanPipeline::createBuffer(
    VkDeviceSize size,
    VkBufferUsageFlags usage,
    VkMemoryPropertyFlags memoryProperties,
    bool map,
    OwnedBuffer* output
) noexcept {
    if (size == 0u || output == nullptr) return Status::kInvalidArgument;
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = size;
    bufferInfo.usage = usage;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(context_.device, &bufferInfo, nullptr, &output->buffer) != VK_SUCCESS) {
        setFailure("vkCreateBuffer failed for NIV99");
        return Status::kOutOfMemory;
    }
    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(context_.device, output->buffer, &requirements);
    const uint32_t memoryType = findMemoryType(requirements.memoryTypeBits, memoryProperties);
    if (memoryType == std::numeric_limits<uint32_t>::max()) {
        setFailure("required NIV99 Vulkan memory type is unavailable");
        return Status::kUnsupported;
    }
    VkMemoryAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocateInfo.allocationSize = requirements.size;
    allocateInfo.memoryTypeIndex = memoryType;
    if (vkAllocateMemory(context_.device, &allocateInfo, nullptr, &output->memory) != VK_SUCCESS) {
        setFailure("vkAllocateMemory failed for NIV99");
        return Status::kOutOfMemory;
    }
    if (vkBindBufferMemory(context_.device, output->buffer, output->memory, 0u) != VK_SUCCESS) {
        setFailure("vkBindBufferMemory failed for NIV99");
        return Status::kVulkanError;
    }
    output->size = size;
    if (map && vkMapMemory(
            context_.device, output->memory, 0u, size, 0u, &output->mapped) != VK_SUCCESS
    ) {
        setFailure("vkMapMemory failed for NIV99 upload storage");
        return Status::kVulkanError;
    }
    return Status::kOk;
}

Status VulkanPipeline::createSlotBuffers() noexcept {
    const uint64_t tileCount = static_cast<uint64_t>(configuration_.tileColumns) *
                               static_cast<uint64_t>(configuration_.tileRows);
    const VkDeviceSize tileControlSize = tileCount * kTileControlBytes;
    const VkDeviceSize statisticsSize = tileCount * kTileStatisticsBytes;
    const VkDeviceSize telemetrySize = tileCount * kTelemetryRecordBytes;
    const VkMemoryPropertyFlags uploadMemory =
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    for (uint32_t slot = 0u; slot < configuration_.maximumInFlight; ++slot) {
        Status status = createBuffer(
            kUniformBytes,
            VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
            uploadMemory,
            true,
            &slots_[slot].uniformBuffer
        );
        if (status != Status::kOk) return status;
        status = createBuffer(
            tileControlSize,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
            uploadMemory,
            true,
            &slots_[slot].tileControlBuffer
        );
        if (status != Status::kOk) return status;
        status = createBuffer(
            statisticsSize,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
            false,
            &slots_[slot].statisticsBuffer
        );
        if (status != Status::kOk) return status;
        status = createBuffer(
            telemetrySize,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
            false,
            &slots_[slot].telemetryBuffer
        );
        if (status != Status::kOk) return status;
    }
    return Status::kOk;
}

bool VulkanPipeline::validateFrameImages(const FrameImages& images, uint32_t slot) const noexcept {
    if (!validImageBinding(images.rawInput, VK_FORMAT_R16_UINT) ||
        !validImageBinding(images.lensShading, VK_FORMAT_R16G16B16A16_SFLOAT) ||
        !validImageBinding(images.persistentDefectMap, VK_FORMAT_R8_UINT) ||
        !validImageBinding(images.denoisedRaw, VK_FORMAT_R32_SFLOAT) ||
        !validImageBinding(images.currentLinear, VK_FORMAT_R16G16B16A16_SFLOAT) ||
        !validImageBinding(images.historyLinear, VK_FORMAT_R16G16B16A16_SFLOAT) ||
        !validImageBinding(images.forwardFlow, VK_FORMAT_R16G16_SFLOAT) ||
        !validImageBinding(images.backwardFlow, VK_FORMAT_R16G16_SFLOAT) ||
        !validImageBinding(images.semanticProtection, VK_FORMAT_R16G16B16A16_SFLOAT) ||
        !validImageBinding(images.motionConfidence, VK_FORMAT_R16G16B16A16_SFLOAT) ||
        !validImageBinding(images.temporalOutput, VK_FORMAT_R16G16B16A16_SFLOAT) ||
        !validImageBinding(images.reconstructedOutput, VK_FORMAT_R16G16B16A16_SFLOAT)
    ) {
        return false;
    }
    const std::array<VkImage, 4u> writableImages{
        images.denoisedRaw.image,
        images.motionConfidence.image,
        images.temporalOutput.image,
        images.reconstructedOutput.image,
    };
    const std::array<VkImage, 8u> readOnlyImages{
        images.rawInput.image,
        images.lensShading.image,
        images.persistentDefectMap.image,
        images.currentLinear.image,
        images.historyLinear.image,
        images.forwardFlow.image,
        images.backwardFlow.image,
        images.semanticProtection.image,
    };
    for (size_t left = 0u; left < writableImages.size(); ++left) {
        for (VkImage readOnlyImage : readOnlyImages) {
            if (writableImages[left] == readOnlyImage) return false;
        }
        for (size_t right = left + 1u; right < writableImages.size(); ++right) {
            if (writableImages[left] == writableImages[right]) return false;
        }
    }
    for (uint32_t other = 0u; other < configuration_.maximumInFlight; ++other) {
        if (other == slot || !slots_[other].configured) continue;
        const FrameImages& configured = slots_[other].images;
        if (images.denoisedRaw.image == configured.denoisedRaw.image ||
            images.motionConfidence.image == configured.motionConfidence.image ||
            images.temporalOutput.image == configured.temporalOutput.image ||
            images.reconstructedOutput.image == configured.reconstructedOutput.image
        ) {
            return false;
        }
    }
    return true;
}

Status VulkanPipeline::configureSlot(uint32_t slot, const FrameImages& images) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (closed_) return Status::kClosed;
    if (!initialized_) return Status::kNotInitialized;
    if (runtimeFailed_) return Status::kVulkanError;
    if (slot >= configuration_.maximumInFlight) return Status::kInvalidArgument;
    const Status retirementStatus = retireCompletedSlotsLocked();
    if (retirementStatus != Status::kOk) return retirementStatus;
    if (slots_[slot].active) return Status::kBackpressure;
    if (!validateFrameImages(images, slot)) {
        setFailure("NIV99 slot images violate format, layout, ownership, or aliasing contract");
        return Status::kInvalidArgument;
    }
    slots_[slot].images = images;
    const Status status = updateSlotDescriptors(slot);
    if (status != Status::kOk) return status;
    slots_[slot].configured = true;
    return Status::kOk;
}

Status VulkanPipeline::updateSlotDescriptors(uint32_t slotIndex) noexcept {
    Slot& slot = slots_[slotIndex];
    const std::array<ImageBinding, kImageBindingCount> images{{
        slot.images.rawInput,
        slot.images.lensShading,
        slot.images.persistentDefectMap,
        slot.images.denoisedRaw,
        slot.images.currentLinear,
        slot.images.historyLinear,
        slot.images.forwardFlow,
        slot.images.backwardFlow,
        slot.images.semanticProtection,
        slot.images.motionConfidence,
        slot.images.temporalOutput,
        slot.images.reconstructedOutput,
    }};
    std::array<VkDescriptorImageInfo, kImageBindingCount> imageInfos{};
    std::array<VkDescriptorBufferInfo, 4u> bufferInfos{};
    std::array<VkWriteDescriptorSet, kDescriptorBindingCount> writes{};
    for (uint32_t binding = 0u; binding < kImageBindingCount; ++binding) {
        imageInfos[binding].imageView = images[binding].view;
        imageInfos[binding].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        writes[binding].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[binding].dstSet = slot.descriptorSet;
        writes[binding].dstBinding = binding;
        writes[binding].descriptorCount = 1u;
        writes[binding].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[binding].pImageInfo = &imageInfos[binding];
    }
    const std::array<OwnedBuffer*, 4u> buffers{{
        &slot.statisticsBuffer,
        &slot.tileControlBuffer,
        &slot.telemetryBuffer,
        &slot.uniformBuffer,
    }};
    const std::array<uint32_t, 4u> bufferBindings{{
        kStatisticsBufferBinding,
        kTileControlBufferBinding,
        kTelemetryBufferBinding,
        kUniformBufferBinding,
    }};
    for (size_t index = 0u; index < buffers.size(); ++index) {
        bufferInfos[index].buffer = buffers[index]->buffer;
        bufferInfos[index].offset = 0u;
        bufferInfos[index].range = buffers[index]->size;
        const uint32_t binding = bufferBindings[index];
        writes[binding].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[binding].dstSet = slot.descriptorSet;
        writes[binding].dstBinding = binding;
        writes[binding].descriptorCount = 1u;
        writes[binding].descriptorType = binding == kUniformBufferBinding
            ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
            : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[binding].pBufferInfo = &bufferInfos[index];
    }
    vkUpdateDescriptorSets(
        context_.device,
        static_cast<uint32_t>(writes.size()),
        writes.data(),
        0u,
        nullptr
    );
    return Status::kOk;
}

bool VulkanPipeline::validateUniformIdentity(
    const GpuUniformBlock& uniforms,
    const FrameIdentity& identity
) const noexcept {
    const FrameIdentity encoded{
        joinWords(uniforms.identityLow[0], uniforms.identityHigh[0]),
        joinWords(uniforms.identityLow[1], uniforms.identityHigh[1]),
        joinWords(uniforms.identityLow[2], uniforms.identityHigh[2]),
        joinWords(uniforms.identityLow[3], uniforms.identityHigh[3]),
    };
    return encoded.sameFrame(identity) && uniforms.frameExtent[0] > 0 &&
           uniforms.frameExtent[1] > 0 && uniforms.frameExtent[2] > 0 &&
           uniforms.frameExtent[3] > 0 &&
           static_cast<uint32_t>(uniforms.frameExtent[0]) == configuration_.width &&
           static_cast<uint32_t>(uniforms.frameExtent[1]) == configuration_.height &&
           static_cast<uint32_t>(uniforms.frameExtent[2]) == configuration_.tileColumns &&
           static_cast<uint32_t>(uniforms.frameExtent[3]) == configuration_.tileRows &&
           uniforms.tileAndCfa[0] == static_cast<int32_t>(
               divideRoundUp(configuration_.width, configuration_.tileColumns)) &&
           uniforms.tileAndCfa[1] == static_cast<int32_t>(
               divideRoundUp(configuration_.height, configuration_.tileRows)) &&
           uniforms.tileAndCfa[2] >= 0 && uniforms.tileAndCfa[2] <= 3 &&
           uniforms.tileAndCfa[3] >= 10 && uniforms.tileAndCfa[3] <= 32;
}

bool VulkanPipeline::validateUniformNumerics(const GpuUniformBlock& uniforms) const noexcept {
    const auto finite4 = [](const float (&values)[4]) noexcept {
        return std::isfinite(values[0]) && std::isfinite(values[1]) &&
               std::isfinite(values[2]) && std::isfinite(values[3]);
    };
    if (!finite4(uniforms.blackLevelRggb) || !finite4(uniforms.whiteAndThresholds) ||
        !finite4(uniforms.shotNoiseRggb) || !finite4(uniforms.readNoiseRggb) ||
        !finite4(uniforms.whiteBalanceRggb) || !finite4(uniforms.colorMatrixRow0) ||
        !finite4(uniforms.colorMatrixRow1) || !finite4(uniforms.colorMatrixRow2) ||
        !finite4(uniforms.motionParameters) || !finite4(uniforms.temporalParameters) ||
        !finite4(uniforms.reconstructionParameters)
    ) {
        return false;
    }
    if (!(uniforms.whiteAndThresholds[0] > 0.0f) ||
        !(uniforms.whiteAndThresholds[1] >= 0.0f) ||
        !(uniforms.whiteAndThresholds[2] > uniforms.whiteAndThresholds[1]) ||
        !(uniforms.whiteAndThresholds[2] <= 1.0f) ||
        !(uniforms.whiteAndThresholds[3] >= 0.0f) ||
        !(uniforms.whiteAndThresholds[3] <= 0.25f) ||
        !(uniforms.motionParameters[3] > 0.0f)
    ) {
        return false;
    }
    for (size_t channel = 0u; channel < 4u; ++channel) {
        if (uniforms.blackLevelRggb[channel] < 0.0f ||
            uniforms.blackLevelRggb[channel] >= uniforms.whiteAndThresholds[0] ||
            uniforms.shotNoiseRggb[channel] < 0.0f ||
            uniforms.readNoiseRggb[channel] < 0.0f ||
            !(uniforms.whiteBalanceRggb[channel] > 0.0f)
        ) {
            return false;
        }
    }
    return true;
}

bool VulkanPipeline::validateTileControls(
    const TileControlRecord* controls,
    uint32_t count
) const noexcept {
    if (controls == nullptr || count == 0u) return false;
    for (uint32_t index = 0u; index < count; ++index) {
        const TileControlRecord& control = controls[index];
        for (size_t component = 0u; component < 4u; ++component) {
            if (!std::isfinite(control.denoise[component]) ||
                !std::isfinite(control.protection[component]) ||
                !std::isfinite(control.thresholds[component]) ||
                !std::isfinite(control.illuminant[component]) ||
                control.denoise[component] < 0.0f ||
                control.denoise[component] > 1.0f ||
                control.protection[component] < 0.0f ||
                control.protection[component] > 1.0f ||
                control.thresholds[component] < 0.0f
            ) {
                return false;
            }
        }
        if (!(control.illuminant[0] >= 0.25f) || !(control.illuminant[0] <= 4.0f) ||
            !(control.illuminant[1] >= 0.25f) || !(control.illuminant[1] <= 4.0f) ||
            !(control.illuminant[2] >= 0.25f) || !(control.illuminant[2] <= 4.0f) ||
            control.illuminant[3] < 0.0f || control.illuminant[3] > 1.0f
        ) {
            return false;
        }
    }
    return true;
}

Status VulkanPipeline::recordCommands(uint32_t slotIndex) noexcept {
    Slot& slot = slots_[slotIndex];
    if (vkResetCommandBuffer(slot.commandBuffer, 0u) != VK_SUCCESS) {
        setFailure("vkResetCommandBuffer failed for NIV99");
        return Status::kVulkanError;
    }
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(slot.commandBuffer, &beginInfo) != VK_SUCCESS) {
        setFailure("vkBeginCommandBuffer failed for NIV99");
        return Status::kVulkanError;
    }
    vkCmdBindDescriptorSets(
        slot.commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        pipelineLayout_,
        0u,
        1u,
        &slot.descriptorSet,
        0u,
        nullptr
    );
    const uint32_t imageGroupsX = divideRoundUp(configuration_.width, kImageLocalSize);
    const uint32_t imageGroupsY = divideRoundUp(configuration_.height, kImageLocalSize);
    const auto dispatch = [&](ShaderStage stage, uint32_t x, uint32_t y) {
        vkCmdBindPipeline(
            slot.commandBuffer,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            pipelines_[stageIndex(stage)]
        );
        vkCmdDispatch(slot.commandBuffer, x, y, 1u);
    };

    dispatch(ShaderStage::kStatistics, configuration_.tileColumns, configuration_.tileRows);
    shaderBufferBarrier(
        slot.commandBuffer,
        slot.statisticsBuffer.buffer,
        slot.statisticsBuffer.size,
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT
    );
    dispatch(ShaderStage::kRawDenoise, imageGroupsX, imageGroupsY);
    dispatch(ShaderStage::kMotionConfidence, imageGroupsX, imageGroupsY);
    shaderImageBarrier(
        slot.commandBuffer,
        slot.images.motionConfidence.image,
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT
    );
    dispatch(ShaderStage::kTemporalFusion, imageGroupsX, imageGroupsY);
    shaderImageBarrier(
        slot.commandBuffer,
        slot.images.temporalOutput.image,
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT
    );
    dispatch(ShaderStage::kReconstruction, imageGroupsX, imageGroupsY);
    shaderImageBarrier(
        slot.commandBuffer,
        slot.images.reconstructedOutput.image,
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT
    );
    dispatch(ShaderStage::kQualityTelemetry, configuration_.tileColumns, configuration_.tileRows);
    if (vkEndCommandBuffer(slot.commandBuffer) != VK_SUCCESS) {
        setFailure("vkEndCommandBuffer failed for NIV99");
        return Status::kVulkanError;
    }
    return Status::kOk;
}

Status VulkanPipeline::submit(
    const SubmitInfo& submission,
    SubmissionToken* outputToken
) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (closed_) return Status::kClosed;
    if (!initialized_) return Status::kNotInitialized;
    if (outputToken == nullptr) return Status::kInvalidArgument;
    *outputToken = SubmissionToken{};
    if (runtimeFailed_) return Status::kVulkanError;
    if (submission.slot >= configuration_.maximumInFlight ||
        submission.uniforms == nullptr || submission.tileControls == nullptr ||
        !submission.identity.complete()
    ) {
        return Status::kInvalidArgument;
    }
    if (submission.generation != generation_) {
        return Status::kStaleGeneration;
    }
    const uint64_t requiredTileCount = static_cast<uint64_t>(configuration_.tileColumns) *
                                       static_cast<uint64_t>(configuration_.tileRows);
    if (submission.tileControlCount != requiredTileCount ||
        !validateUniformIdentity(*submission.uniforms, submission.identity) ||
        !validateUniformNumerics(*submission.uniforms) ||
        !validateTileControls(submission.tileControls, submission.tileControlCount) ||
        submission.inputReadySemaphore == timelineSemaphore_ ||
        (submission.inputReadySemaphore == VK_NULL_HANDLE &&
            (submission.inputReadySemaphoreIsTimeline ||
                submission.inputReadyTimelineValue != 0u)) ||
        (submission.inputReadySemaphore != VK_NULL_HANDLE &&
            (!submission.inputReadySemaphoreIsTimeline ||
                submission.inputReadyTimelineValue == 0u))
    ) {
        return Status::kInvalidArgument;
    }
    if (hasLatestSubmittedIdentity_) {
        if (submission.identity.cameraIdHash != latestSubmittedIdentity_.cameraIdHash ||
            submission.identity.sessionGeneration != latestSubmittedIdentity_.sessionGeneration
        ) {
            return Status::kStaleGeneration;
        }
        if (submission.identity.frameNumber <= latestSubmittedIdentity_.frameNumber ||
            submission.identity.sensorTimestampNs <= latestSubmittedIdentity_.sensorTimestampNs
        ) {
            return Status::kStaleGeneration;
        }
    }
    const Status retirementStatus = retireCompletedSlotsLocked();
    if (retirementStatus != Status::kOk) return retirementStatus;
    Slot& slot = slots_[submission.slot];
    if (!slot.configured) return Status::kNotConfigured;
    if (slot.active) return Status::kBackpressure;
    std::memcpy(slot.uniformBuffer.mapped, submission.uniforms, sizeof(GpuUniformBlock));
    std::memcpy(
        slot.tileControlBuffer.mapped,
        submission.tileControls,
        static_cast<size_t>(requiredTileCount) * sizeof(TileControlRecord)
    );
    Status status = recordCommands(submission.slot);
    if (status != Status::kOk) {
        runtimeFailed_ = true;
        return status;
    }

    std::array<VkSemaphore, 2u> waitSemaphores{};
    std::array<uint64_t, 2u> waitValues{};
    std::array<VkPipelineStageFlags, 2u> waitStages{};
    uint32_t waitCount = 0u;
    if (lastSubmittedTimelineValue_ > 0u) {
        waitSemaphores[waitCount] = timelineSemaphore_;
        waitValues[waitCount] = lastSubmittedTimelineValue_;
        waitStages[waitCount] = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        ++waitCount;
    }
    if (submission.inputReadySemaphore != VK_NULL_HANDLE) {
        waitSemaphores[waitCount] = submission.inputReadySemaphore;
        waitValues[waitCount] = submission.inputReadyTimelineValue;
        waitStages[waitCount] = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        ++waitCount;
    }
    if (lastSubmittedTimelineValue_ == std::numeric_limits<uint64_t>::max()) {
        setFailure("NIV99 timeline sequence is exhausted");
        runtimeFailed_ = true;
        return Status::kSequenceExhausted;
    }
    const uint64_t signalValue = lastSubmittedTimelineValue_ + 1u;
    VkTimelineSemaphoreSubmitInfo timelineInfo{};
    timelineInfo.sType = VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO;
    timelineInfo.waitSemaphoreValueCount = waitCount;
    timelineInfo.pWaitSemaphoreValues = waitCount == 0u ? nullptr : waitValues.data();
    timelineInfo.signalSemaphoreValueCount = 1u;
    timelineInfo.pSignalSemaphoreValues = &signalValue;
    VkSubmitInfo queueSubmit{};
    queueSubmit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    queueSubmit.pNext = &timelineInfo;
    queueSubmit.waitSemaphoreCount = waitCount;
    queueSubmit.pWaitSemaphores = waitCount == 0u ? nullptr : waitSemaphores.data();
    queueSubmit.pWaitDstStageMask = waitCount == 0u ? nullptr : waitStages.data();
    queueSubmit.commandBufferCount = 1u;
    queueSubmit.pCommandBuffers = &slot.commandBuffer;
    queueSubmit.signalSemaphoreCount = 1u;
    queueSubmit.pSignalSemaphores = &timelineSemaphore_;
    VkResult submitResult = VK_ERROR_UNKNOWN;
    if (context_.queueMutex == nullptr) {
        submitResult = vkQueueSubmit(context_.queue, 1u, &queueSubmit, VK_NULL_HANDLE);
    } else {
        std::lock_guard<std::mutex> queueLock(*context_.queueMutex);
        submitResult = vkQueueSubmit(context_.queue, 1u, &queueSubmit, VK_NULL_HANDLE);
    }
    if (submitResult != VK_SUCCESS) {
        setFailure("vkQueueSubmit failed for NIV99");
        runtimeFailed_ = true;
        return Status::kVulkanError;
    }
    lastSubmittedTimelineValue_ = signalValue;
    slot.identity = submission.identity;
    slot.generation = submission.generation;
    slot.signalTimelineValue = signalValue;
    slot.active = true;
    slot.retired = false;
    latestSubmittedIdentity_ = submission.identity;
    hasLatestSubmittedIdentity_ = true;
    outputToken->slot = submission.slot;
    outputToken->generation = submission.generation;
    outputToken->signalTimelineValue = signalValue;
    outputToken->identity = submission.identity;
    return Status::kOk;
}

Status VulkanPipeline::retireCompletedSlotsLocked() noexcept {
    if (runtimeFailed_) return Status::kVulkanError;
    if (getSemaphoreCounterValue_ == nullptr || timelineSemaphore_ == VK_NULL_HANDLE) {
        setFailure("NIV99 completion timeline is unavailable");
        runtimeFailed_ = true;
        return Status::kVulkanError;
    }
    uint64_t completedValue = 0u;
    if (getSemaphoreCounterValue_(
            context_.device, timelineSemaphore_, &completedValue) != VK_SUCCESS
    ) {
        setFailure("NIV99 completion timeline query failed");
        runtimeFailed_ = true;
        return Status::kVulkanError;
    }
    for (uint32_t slot = 0u; slot < configuration_.maximumInFlight; ++slot) {
        if (slots_[slot].active && slots_[slot].signalTimelineValue <= completedValue) {
            slots_[slot].active = false;
        }
    }
    return Status::kOk;
}

Completion VulkanPipeline::poll(const SubmissionToken& token) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (closed_ || !initialized_) return Completion::kFailed;
    if (runtimeFailed_) return Completion::kFailed;
    if (token.slot >= configuration_.maximumInFlight || token.signalTimelineValue == 0u) {
        return Completion::kIdle;
    }
    if (retireCompletedSlotsLocked() != Status::kOk) return Completion::kFailed;
    const Slot& slot = slots_[token.slot];
    if (token.generation != generation_ || slot.retired) return Completion::kStaleGeneration;
    if (!slot.identity.sameFrame(token.identity) ||
        slot.signalTimelineValue != token.signalTimelineValue
    ) {
        return Completion::kStaleGeneration;
    }
    return slot.active ? Completion::kPending : Completion::kComplete;
}

Status VulkanPipeline::cancelGeneration(uint64_t generation) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (closed_) return Status::kClosed;
    if (!initialized_) return Status::kNotInitialized;
    if (generation != generation_) return Status::kStaleGeneration;
    if (generation_ == std::numeric_limits<uint64_t>::max()) {
        setFailure("NIV99 generation sequence is exhausted");
        return Status::kSequenceExhausted;
    }
    for (uint32_t slot = 0u; slot < configuration_.maximumInFlight; ++slot) {
        if (slots_[slot].active && slots_[slot].generation == generation) {
            slots_[slot].retired = true;
        }
    }
    ++generation_;
    latestSubmittedIdentity_ = FrameIdentity{};
    hasLatestSubmittedIdentity_ = false;
    return Status::kOk;
}

void VulkanPipeline::destroyBuffer(OwnedBuffer* buffer) noexcept {
    if (buffer == nullptr || context_.device == VK_NULL_HANDLE) return;
    if (buffer->mapped != nullptr && buffer->memory != VK_NULL_HANDLE) {
        vkUnmapMemory(context_.device, buffer->memory);
    }
    if (buffer->buffer != VK_NULL_HANDLE) {
        vkDestroyBuffer(context_.device, buffer->buffer, nullptr);
    }
    if (buffer->memory != VK_NULL_HANDLE) {
        vkFreeMemory(context_.device, buffer->memory, nullptr);
    }
    *buffer = OwnedBuffer{};
}

Status VulkanPipeline::close() noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (closed_) return Status::kOk;
    Status result = Status::kOk;
    if (context_.device != VK_NULL_HANDLE && timelineSemaphore_ != VK_NULL_HANDLE &&
        lastSubmittedTimelineValue_ > 0u && waitSemaphores_ != nullptr
    ) {
        const VkSemaphore semaphore = timelineSemaphore_;
        const uint64_t value = lastSubmittedTimelineValue_;
        VkSemaphoreWaitInfo waitInfo{};
        waitInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO;
        waitInfo.semaphoreCount = 1u;
        waitInfo.pSemaphores = &semaphore;
        waitInfo.pValues = &value;
        const VkResult waitResult = waitSemaphores_(
            context_.device,
            &waitInfo,
            kCloseTimelineTimeoutNs
        );
        if (waitResult != VK_SUCCESS) {
            setFailure("NIV99 timeline did not drain during bounded teardown");
            result = Status::kVulkanError;
            // Teardown is outside the frame path. Queue idle prevents destroying in-use resources
            // after a timeout while avoiding a device-wide idle operation.
            VkResult idleResult = VK_ERROR_UNKNOWN;
            if (context_.queueMutex == nullptr) {
                idleResult = vkQueueWaitIdle(context_.queue);
            } else {
                std::lock_guard<std::mutex> queueLock(*context_.queueMutex);
                idleResult = vkQueueWaitIdle(context_.queue);
            }
            if (idleResult != VK_SUCCESS && idleResult != VK_ERROR_DEVICE_LOST) {
                setFailure("NIV99 queue could not be retired safely during teardown");
                return Status::kVulkanError;
            }
        }
    }
    destroyResourcesLocked(false);
    initialized_ = false;
    closed_ = true;
    return result;
}

void VulkanPipeline::destroyResourcesLocked(bool waitForTimeline) noexcept {
    if (waitForTimeline && context_.queue != VK_NULL_HANDLE) {
        if (context_.queueMutex == nullptr) {
            (void)vkQueueWaitIdle(context_.queue);
        } else {
            std::lock_guard<std::mutex> queueLock(*context_.queueMutex);
            (void)vkQueueWaitIdle(context_.queue);
        }
    }
    if (context_.device != VK_NULL_HANDLE) {
        for (uint32_t slot = 0u; slot < kMaximumInFlightFrames; ++slot) {
            destroyBuffer(&slots_[slot].uniformBuffer);
            destroyBuffer(&slots_[slot].tileControlBuffer);
            destroyBuffer(&slots_[slot].statisticsBuffer);
            destroyBuffer(&slots_[slot].telemetryBuffer);
            slots_[slot] = Slot{};
        }
        for (VkPipeline& pipeline : pipelines_) {
            if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(context_.device, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        if (timelineSemaphore_ != VK_NULL_HANDLE) {
            vkDestroySemaphore(context_.device, timelineSemaphore_, nullptr);
        }
        if (commandPool_ != VK_NULL_HANDLE) {
            vkDestroyCommandPool(context_.device, commandPool_, nullptr);
        }
        if (descriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(context_.device, descriptorPool_, nullptr);
        }
        if (pipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(context_.device, pipelineLayout_, nullptr);
        }
        if (descriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(context_.device, descriptorSetLayout_, nullptr);
        }
    }
    timelineSemaphore_ = VK_NULL_HANDLE;
    commandPool_ = VK_NULL_HANDLE;
    descriptorPool_ = VK_NULL_HANDLE;
    pipelineLayout_ = VK_NULL_HANDLE;
    descriptorSetLayout_ = VK_NULL_HANDLE;
    getSemaphoreCounterValue_ = nullptr;
    waitSemaphores_ = nullptr;
    lastSubmittedTimelineValue_ = 0u;
    latestSubmittedIdentity_ = FrameIdentity{};
    hasLatestSubmittedIdentity_ = false;
}

uint64_t VulkanPipeline::currentGeneration() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return generation_;
}

const char* VulkanPipeline::failureReason() const noexcept {
    return failureReason_.data();
}

VkSemaphore VulkanPipeline::completionSemaphore() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return initialized_ && !closed_ ? timelineSemaphore_ : VK_NULL_HANDLE;
}

VkBuffer VulkanPipeline::statisticsBuffer(uint32_t slot) const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return slot < configuration_.maximumInFlight
        ? slots_[slot].statisticsBuffer.buffer
        : VK_NULL_HANDLE;
}

VkBuffer VulkanPipeline::telemetryBuffer(uint32_t slot) const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return slot < configuration_.maximumInFlight
        ? slots_[slot].telemetryBuffer.buffer
        : VK_NULL_HANDLE;
}

void VulkanPipeline::setFailure(const char* reason) noexcept {
    const char* safeReason = reason == nullptr ? "unknown NIV99 Vulkan error" : reason;
    (void)std::snprintf(failureReason_.data(), failureReason_.size(), "%s", safeReason);
}

}  // namespace neoproc::niv99
