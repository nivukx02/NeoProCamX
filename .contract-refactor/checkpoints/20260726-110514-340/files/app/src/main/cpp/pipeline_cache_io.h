#ifndef NEOPROC_PIPELINE_CACHE_IO_H
#define NEOPROC_PIPELINE_CACHE_IO_H

// s7-f13 / CP9C — disk pipeline-cache persistence helper (warm-start).
// Pure byte-level serialize/validate of a Vulkan pipeline-cache blob with a device-identity
// header. No Vulkan symbols here on purpose: the blob is opaque driver data we never interpret;
// this file only frames + validates it and round-trips it through an app-private file. Gated
// entirely by the caller (vulkan_bridge.cpp); with the gate OFF none of this is reached.
//
// ABI: NeoPipelineCacheHeader is an INTERNAL on-disk struct, NOT a GPU push-constant/UBO. Its
// layout is pinned by the static_asserts below AND by PipelineCacheHeaderLayoutTest (text-parse,
// no device), mirroring the KneeCurve/RawDemosaic pattern.

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace neoproc {

// Bump kPipelineCacheHeaderVersion whenever the header layout changes so an old file is rejected
// (treated as a miss -> fresh compile).
constexpr uint32_t kPipelineCacheMagic = 0x4E50434Bu; // 'NPCK'
constexpr uint32_t kPipelineCacheHeaderVersion = 1u;
constexpr size_t kPipelineCacheUuidSize = 16u; // mirrors VK_UUID_SIZE

// Fixed-layout POD on-disk header. Six 4-byte fields (offsets 0..20), then the 16-byte UUID
// (offset 24..40), then the naturally-8-byte-aligned uint64 payloadSize at offset 40 => sizeof 48.
struct NeoPipelineCacheHeader {
    uint32_t magic;           // kPipelineCacheMagic
    uint32_t headerVersion;   // kPipelineCacheHeaderVersion
    uint32_t vendorId;        // VkPhysicalDeviceProperties.vendorID
    uint32_t deviceId;        // VkPhysicalDeviceProperties.deviceID
    uint32_t driverVersion;   // VkPhysicalDeviceProperties.driverVersion
    uint32_t reserved;        // pad/future use (keeps payloadSize 8-byte aligned)
    uint8_t  pipelineCacheUuid[kPipelineCacheUuidSize]; // VkPhysicalDeviceProperties.pipelineCacheUUID
    uint64_t payloadSize;     // bytes of opaque pipeline-cache data that follow
};

static_assert(sizeof(NeoPipelineCacheHeader) == 48,
              "NeoPipelineCacheHeader on-disk layout is contract-locked");
static_assert(offsetof(NeoPipelineCacheHeader, magic) == 0, "magic offset");
static_assert(offsetof(NeoPipelineCacheHeader, headerVersion) == 4, "headerVersion offset");
static_assert(offsetof(NeoPipelineCacheHeader, vendorId) == 8, "vendorId offset");
static_assert(offsetof(NeoPipelineCacheHeader, deviceId) == 12, "deviceId offset");
static_assert(offsetof(NeoPipelineCacheHeader, driverVersion) == 16, "driverVersion offset");
static_assert(offsetof(NeoPipelineCacheHeader, reserved) == 20, "reserved offset");
static_assert(offsetof(NeoPipelineCacheHeader, pipelineCacheUuid) == 24, "uuid offset");
static_assert(offsetof(NeoPipelineCacheHeader, payloadSize) == 40, "payloadSize offset");

// Identity of the current device, filled from VkPhysicalDeviceProperties.
struct PipelineCacheDeviceIdentity {
    uint32_t vendorId = 0;
    uint32_t deviceId = 0;
    uint32_t driverVersion = 0;
    uint8_t  pipelineCacheUuid[kPipelineCacheUuidSize] = {};
};

// Build a header for the given identity + payload size.
NeoPipelineCacheHeader makePipelineCacheHeader(const PipelineCacheDeviceIdentity& id,
                                               uint64_t payloadSize);

// True iff [header] is well-formed AND matches [id] exactly (magic, version, vendor/device/driver,
// and pipelineCacheUUID byte-for-byte). A mismatch -> reject -> fresh compile.
bool pipelineCacheHeaderMatchesDevice(const NeoPipelineCacheHeader& header,
                                      const PipelineCacheDeviceIdentity& id);

// Serialize header + payload into one contiguous byte vector (header first).
std::vector<uint8_t> serializePipelineCacheFile(const PipelineCacheDeviceIdentity& id,
                                                const uint8_t* payload,
                                                size_t payloadSize);

// Parse [fileBytes]. On success and device match, copies the opaque payload into [outPayload] and
// returns true. On any failure (too short, bad magic, wrong version, truncated payload, device
// mismatch) returns false and leaves [outPayload] empty. NEVER trusts the blob without a full match.
bool deserializePipelineCacheFile(const std::vector<uint8_t>& fileBytes,
                                  const PipelineCacheDeviceIdentity& id,
                                  std::vector<uint8_t>* outPayload);

// Bounded file read (returns empty on any error / over-cap). [maxBytes] caps the read so a
// corrupt/huge file can't blow up boot.
std::vector<uint8_t> readPipelineCacheFile(const std::string& path, size_t maxBytes);

// Atomic-ish write: writes to [path].tmp then renames over [path]. Returns true on success. Caller
// runs this on a background thread post-first-frame.
bool writePipelineCacheFile(const std::string& path, const std::vector<uint8_t>& bytes);

}  // namespace neoproc

#endif  // NEOPROC_PIPELINE_CACHE_IO_H
