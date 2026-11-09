// Executes the production native commit engine on Android, not a simulated DMC implementation.
#include "../app/src/main/cpp/npv10_dmc_native.cpp"
#include <cstdio>
#include <stdexcept>
#include <zlib.h>

namespace {
void requireRace(bool value, const char* reason) {
    if (!value) throw std::runtime_error(std::string(reason) + ": " + gLastDmcError);
}

struct OwnedRaceFile {
    char path[80] = "/data/local/tmp/lq001-dmc-commit-race-XXXXXX";
    int fd = ::mkstemp(path);
    ~OwnedRaceFile() {
        if (fd >= 0) { (void)::close(fd); (void)::unlink(path); }
    }
};

void runRace(bool compressed) {
    constexpr std::int32_t samples = 64 * 32 * 3 / 2;
    constexpr std::int32_t packed = samples * 10 / 8;
    const auto capacity = compressed
        ? static_cast<std::int32_t>(neoproc::npv10::lossless::maximumStoredBytes(packed, samples))
        : packed;
    constexpr std::int64_t start = 4096;
    const std::int64_t original = start + 4LL * (28 + capacity);
    const std::int64_t grown = original + 4LL * (28 + capacity);
    OwnedRaceFile file;
    requireRace(file.fd >= 0, "mkstemp failed");
    requireRace(::posix_fallocate64(file.fd, 0, original) == 0, "initial allocation failed");
    auto engine = DirectMappedCommitEngine::create(
        file.path, start, 28, packed, capacity, samples, compressed, 4, original);
    requireRace(engine != nullptr, "engine create failed");
    requireRace(engine->beginFrame(0, original), "original admission failed");
    neoproc::npv10::DirectMappedPayloadView view{};
    requireRace(engine->acquirePayload(0, packed, &view), "payload acquisition failed");
    for (int i = 0; i < packed; ++i) view.data[i] = static_cast<std::uint8_t>(i * 37 + 11);
    const auto crc = static_cast<std::uint32_t>(::crc32(0, view.data, packed));
    neoproc::npv10::DirectMappedPreparedPayload prepared{};
    requireRace(engine->finishProcessing(0, true, crc, &prepared), "preparation failed");
    std::vector<std::uint8_t> expected(view.data, view.data + prepared.storedBytes);
    requireRace(::posix_fallocate64(file.fd, original, grown - original) == 0,
        "physical growth failed");
    requireRace(engine->beginFrame(1, grown), "newer admission failed");
    const auto payloadEnd = start + 28 + prepared.storedBytes;
    requireRace(!engine->commitFrame(0, prepared.storedBytes, start + 28, payloadEnd - 1,
        prepared.storedCrc32, 1000000, 64, 32), "undersized own snapshot was accepted");
    const bool committed = engine->commitFrame(0, prepared.storedBytes, start + 28, original,
        prepared.storedCrc32, 1000000, 64, 32);
    const auto failure = gLastDmcError;
    if (!committed) {
        (void)engine->abortFrame(0);
        (void)engine->abortFrame(1);
        (void)engine->shutdown();
        throw std::runtime_error("valid older commit rejected after proven growth: " + failure);
    }
    requireRace(engine->awaitCommittedFrames(1), "commit did not persist");
    std::vector<std::uint8_t> actual(expected.size());
    requireRace(::pread64(file.fd, actual.data(), actual.size(), start + 28) ==
        static_cast<ssize_t>(actual.size()), "persisted packet unreadable");
    requireRace(actual == expected, "stored packet bytes changed");
    requireRace(engine->abortFrame(1), "newer unused admission could not retire");
    requireRace(engine->shutdown(), "engine cleanup failed");
    std::printf("COMMIT_RESERVATION_RACE_PASS compressed=%d own_extent_checked=true "
        "older_valid_snapshot_committed=true stored_packet_bit_exact=true\n", compressed);
}
}  // namespace

int main() {
    try { runRace(false); runRace(true); return 0; }
    catch (const std::exception& failure) {
        std::fprintf(stderr, "COMMIT_RESERVATION_RACE_FAIL %s\n", failure.what());
        return 1;
    }
}
