// s7-f13 / CP9C — see pipeline_cache_io.h.
#include "pipeline_cache_io.h"

#include <cstdio>
#include <cstring>
#include <fstream>

namespace neoproc {

NeoPipelineCacheHeader makePipelineCacheHeader(const PipelineCacheDeviceIdentity& id,
                                               uint64_t payloadSize) {
    NeoPipelineCacheHeader header{};
    header.magic = kPipelineCacheMagic;
    header.headerVersion = kPipelineCacheHeaderVersion;
    header.vendorId = id.vendorId;
    header.deviceId = id.deviceId;
    header.driverVersion = id.driverVersion;
    header.reserved = 0u;
    std::memcpy(header.pipelineCacheUuid, id.pipelineCacheUuid, kPipelineCacheUuidSize);
    header.payloadSize = payloadSize;
    return header;
}

bool pipelineCacheHeaderMatchesDevice(const NeoPipelineCacheHeader& header,
                                      const PipelineCacheDeviceIdentity& id) {
    if (header.magic != kPipelineCacheMagic) return false;
    if (header.headerVersion != kPipelineCacheHeaderVersion) return false;
    if (header.vendorId != id.vendorId) return false;
    if (header.deviceId != id.deviceId) return false;
    if (header.driverVersion != id.driverVersion) return false;
    return std::memcmp(header.pipelineCacheUuid, id.pipelineCacheUuid,
                       kPipelineCacheUuidSize) == 0;
}

std::vector<uint8_t> serializePipelineCacheFile(const PipelineCacheDeviceIdentity& id,
                                                const uint8_t* payload,
                                                size_t payloadSize) {
    const NeoPipelineCacheHeader header =
        makePipelineCacheHeader(id, static_cast<uint64_t>(payloadSize));
    std::vector<uint8_t> out;
    out.resize(sizeof(NeoPipelineCacheHeader) + payloadSize);
    std::memcpy(out.data(), &header, sizeof(NeoPipelineCacheHeader));
    if (payloadSize > 0 && payload != nullptr) {
        std::memcpy(out.data() + sizeof(NeoPipelineCacheHeader), payload, payloadSize);
    }
    return out;
}

bool deserializePipelineCacheFile(const std::vector<uint8_t>& fileBytes,
                                  const PipelineCacheDeviceIdentity& id,
                                  std::vector<uint8_t>* outPayload) {
    if (outPayload != nullptr) outPayload->clear();
    if (fileBytes.size() < sizeof(NeoPipelineCacheHeader)) return false;
    NeoPipelineCacheHeader header{};
    std::memcpy(&header, fileBytes.data(), sizeof(NeoPipelineCacheHeader));
    if (!pipelineCacheHeaderMatchesDevice(header, id)) return false;
    const uint64_t available =
        static_cast<uint64_t>(fileBytes.size() - sizeof(NeoPipelineCacheHeader));
    if (header.payloadSize == 0u || header.payloadSize > available) return false;
    if (outPayload != nullptr) {
        const uint8_t* start = fileBytes.data() + sizeof(NeoPipelineCacheHeader);
        outPayload->assign(start, start + header.payloadSize);
    }
    return true;
}

std::vector<uint8_t> readPipelineCacheFile(const std::string& path, size_t maxBytes) {
    std::vector<uint8_t> out;
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in.is_open()) return out;
    const std::streamoff size = in.tellg();
    if (size <= 0 || static_cast<size_t>(size) > maxBytes) return out;
    in.seekg(0, std::ios::beg);
    out.resize(static_cast<size_t>(size));
    if (!in.read(reinterpret_cast<char*>(out.data()), size)) {
        out.clear();
    }
    return out;
}

bool writePipelineCacheFile(const std::string& path, const std::vector<uint8_t>& bytes) {
    if (bytes.empty()) return false;
    const std::string tmp = path + ".tmp";
    {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        if (!out.is_open()) return false;
        out.write(reinterpret_cast<const char*>(bytes.data()),
                  static_cast<std::streamsize>(bytes.size()));
        if (!out.good()) return false;
    }
    std::remove(path.c_str());
    return std::rename(tmp.c_str(), path.c_str()) == 0;
}

}  // namespace neoproc
