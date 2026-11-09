#pragma once

#include <vulkan/vulkan.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace neoproc::niv99 {

constexpr uint32_t kDescriptorBindingCount = 16u;
constexpr uint32_t kMaximumInFlightFrames = 8u;
constexpr uint32_t kShaderStageCount = 6u;
constexpr uint32_t kUniformBytes = 256u;
constexpr uint32_t kTileControlBytes = 64u;
constexpr uint32_t kTileStatisticsBytes = 64u;
constexpr uint32_t kTelemetryRecordBytes = 80u;

enum class Status : uint32_t {
    kOk = 0u,
    kNotInitialized,
    kInvalidArgument,
    kUnsupported,
    kOutOfMemory,
    kVulkanError,
    kBackpressure,
    kStaleGeneration,
    kNotConfigured,
    kSequenceExhausted,
    kClosed,
};

enum class Completion : uint32_t {
    kIdle = 0u,
    kPending,
    kComplete,
    kFailed,
    kStaleGeneration,
};

enum class ShaderStage : uint32_t {
    kStatistics = 0u,
    kRawDenoise,
    kMotionConfidence,
    kTemporalFusion,
    kReconstruction,
    kQualityTelemetry,
};

struct alignas(16) GpuUniformBlock {
    int32_t frameExtent[4];
    int32_t tileAndCfa[4];
    uint32_t identityLow[4];
    uint32_t identityHigh[4];
    uint32_t flags[4];
    float blackLevelRggb[4];
    float whiteAndThresholds[4];
    float shotNoiseRggb[4];
    float readNoiseRggb[4];
    float whiteBalanceRggb[4];
    float colorMatrixRow0[4];
    float colorMatrixRow1[4];
    float colorMatrixRow2[4];
    float motionParameters[4];
    float temporalParameters[4];
    float reconstructionParameters[4];
};

struct alignas(16) TileControlRecord {
    float denoise[4];
    float protection[4];
    float thresholds[4];
    float illuminant[4];
};

struct alignas(16) TileStatisticsRecord {
    float meanRggb[4];
    float varianceRggb[4];
    float confidence[4];
    float chroma[4];
};

struct alignas(16) TelemetryRecord {
    uint32_t identityLow[4];
    uint32_t identityHigh[4];
    float distortion[4];
    float detail[4];
    float boundary[4];
};

static_assert(sizeof(GpuUniformBlock) == kUniformBytes, "NIV99 uniform ABI drift");
static_assert(sizeof(TileControlRecord) == kTileControlBytes, "NIV99 tile-control ABI drift");
static_assert(sizeof(TileStatisticsRecord) == kTileStatisticsBytes, "NIV99 statistics ABI drift");
static_assert(sizeof(TelemetryRecord) == kTelemetryRecordBytes, "NIV99 telemetry ABI drift");
static_assert(offsetof(GpuUniformBlock, identityLow) == 32u, "NIV99 identity-low offset drift");
static_assert(offsetof(GpuUniformBlock, identityHigh) == 48u, "NIV99 identity-high offset drift");
static_assert(offsetof(GpuUniformBlock, flags) == 64u, "NIV99 flags offset drift");
static_assert(offsetof(GpuUniformBlock, reconstructionParameters) == 240u,
              "NIV99 reconstruction offset drift");

struct FrameIdentity {
    uint64_t cameraIdHash = 0u;
    uint64_t sessionGeneration = 0u;
    uint64_t frameNumber = 0u;
    uint64_t sensorTimestampNs = 0u;

    [[nodiscard]] bool complete() const noexcept;
    [[nodiscard]] bool sameFrame(const FrameIdentity& other) const noexcept;
};

struct ImageBinding {
    VkImage image = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkImageLayout layout = VK_IMAGE_LAYOUT_UNDEFINED;
};

/** All images are preallocated by the owning renderer and remain valid until their slot retires. */
struct FrameImages {
    ImageBinding rawInput;
    ImageBinding lensShading;
    ImageBinding persistentDefectMap;
    ImageBinding denoisedRaw;
    ImageBinding currentLinear;
    ImageBinding historyLinear;
    ImageBinding forwardFlow;
    ImageBinding backwardFlow;
    ImageBinding semanticProtection;
    ImageBinding motionConfidence;
    ImageBinding temporalOutput;
    ImageBinding reconstructedOutput;
};

struct DeviceContext {
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex = 0u;
    VkPipelineCache pipelineCache = VK_NULL_HANDLE;
    bool timelineSemaphoreEnabled = false;
    bool storageImageExtendedFormatsEnabled = false;
    /** Required when [queue] is shared with another native owner. Never held during GPU waits. */
    std::mutex* queueMutex = nullptr;
};

struct Configuration {
    uint32_t width = 0u;
    uint32_t height = 0u;
    uint32_t tileColumns = 0u;
    uint32_t tileRows = 0u;
    uint32_t maximumInFlight = 0u;
};

struct SubmitInfo {
    uint32_t slot = 0u;
    uint64_t generation = 0u;
    FrameIdentity identity;
    const GpuUniformBlock* uniforms = nullptr;
    const TileControlRecord* tileControls = nullptr;
    uint32_t tileControlCount = 0u;
    VkSemaphore inputReadySemaphore = VK_NULL_HANDLE;
    bool inputReadySemaphoreIsTimeline = false;
    uint64_t inputReadyTimelineValue = 0u;
};

struct SubmissionToken {
    uint32_t slot = 0u;
    uint64_t generation = 0u;
    uint64_t signalTimelineValue = 0u;
    FrameIdentity identity;
};

/**
 * Dedicated NIV99 Vulkan compute dispatcher.
 *
 * Initialization/configuration may allocate Vulkan objects. submit() performs only bounded memcpy,
 * command recording, descriptor reuse, and queue submission; it never allocates a descriptor,
 * creates an image/buffer, waits for device idle, or performs a readback.
 */
class VulkanPipeline final {
public:
    VulkanPipeline() = default;
    ~VulkanPipeline();

    VulkanPipeline(const VulkanPipeline&) = delete;
    VulkanPipeline& operator=(const VulkanPipeline&) = delete;
    VulkanPipeline(VulkanPipeline&&) = delete;
    VulkanPipeline& operator=(VulkanPipeline&&) = delete;

    Status initialize(const DeviceContext& context, const Configuration& configuration) noexcept;
    Status configureSlot(uint32_t slot, const FrameImages& images) noexcept;
    Status submit(const SubmitInfo& submission, SubmissionToken* outputToken) noexcept;
    Completion poll(const SubmissionToken& token) noexcept;
    Status cancelGeneration(uint64_t generation) noexcept;
    Status close() noexcept;

    [[nodiscard]] uint64_t currentGeneration() const noexcept;
    [[nodiscard]] const char* failureReason() const noexcept;
    /** The owner may wait on this semaphore/value before exposing an output to another queue/API. */
    [[nodiscard]] VkSemaphore completionSemaphore() const noexcept;
    [[nodiscard]] VkBuffer statisticsBuffer(uint32_t slot) const noexcept;
    [[nodiscard]] VkBuffer telemetryBuffer(uint32_t slot) const noexcept;

private:
    struct OwnedBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        void* mapped = nullptr;
        VkDeviceSize size = 0u;
    };

    struct Slot {
        FrameImages images{};
        OwnedBuffer uniformBuffer{};
        OwnedBuffer tileControlBuffer{};
        OwnedBuffer statisticsBuffer{};
        OwnedBuffer telemetryBuffer{};
        VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
        VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
        FrameIdentity identity{};
        uint64_t generation = 0u;
        uint64_t signalTimelineValue = 0u;
        bool configured = false;
        bool active = false;
        bool retired = false;
    };

    Status validateConfiguration(const Configuration& configuration) noexcept;
    Status validateFormats() noexcept;
    Status createDescriptorInfrastructure() noexcept;
    Status createPipelines() noexcept;
    Status createSynchronization() noexcept;
    Status createSlotBuffers() noexcept;
    Status allocateCommandBuffers() noexcept;
    Status updateSlotDescriptors(uint32_t slot) noexcept;
    Status recordCommands(uint32_t slot) noexcept;
    Status createBuffer(
        VkDeviceSize size,
        VkBufferUsageFlags usage,
        VkMemoryPropertyFlags memoryProperties,
        bool map,
        OwnedBuffer* output
    ) noexcept;
    Status createComputePipeline(
        const uint32_t* words,
        size_t wordCount,
        VkPipeline* output
    ) noexcept;
    uint32_t findMemoryType(uint32_t bits, VkMemoryPropertyFlags required) const noexcept;
    void destroyBuffer(OwnedBuffer* buffer) noexcept;
    void destroyResourcesLocked(bool waitForTimeline) noexcept;
    Status retireCompletedSlotsLocked() noexcept;
    bool validateFrameImages(const FrameImages& images, uint32_t slot) const noexcept;
    bool validateUniformIdentity(const GpuUniformBlock& uniforms, const FrameIdentity& identity) const noexcept;
    bool validateUniformNumerics(const GpuUniformBlock& uniforms) const noexcept;
    bool validateTileControls(const TileControlRecord* controls, uint32_t count) const noexcept;
    void setFailure(const char* reason) noexcept;

    mutable std::mutex mutex_;
    DeviceContext context_{};
    Configuration configuration_{};
    std::array<Slot, kMaximumInFlightFrames> slots_{};
    std::array<VkPipeline, kShaderStageCount> pipelines_{};
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkSemaphore timelineSemaphore_ = VK_NULL_HANDLE;
    PFN_vkGetSemaphoreCounterValueKHR getSemaphoreCounterValue_ = nullptr;
    PFN_vkWaitSemaphoresKHR waitSemaphores_ = nullptr;
    uint64_t generation_ = 0u;
    uint64_t lastSubmittedTimelineValue_ = 0u;
    FrameIdentity latestSubmittedIdentity_{};
    bool hasLatestSubmittedIdentity_ = false;
    bool initialized_ = false;
    bool runtimeFailed_ = false;
    bool closed_ = false;
    std::array<char, 192u> failureReason_{};
};

}  // namespace neoproc::niv99
