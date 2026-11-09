#define _FILE_OFFSET_BITS 64

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#if defined(__aarch64__) && defined(__ARM_FEATURE_CRC32)
#include <arm_acle.h>
#define NPRAW_AUDIT_CRC_ENGINE "armv8-crc32"
#else
#define NPRAW_AUDIT_CRC_ENGINE "portable-table"
#endif

enum {
    NPRAW_PREFIX_BYTES = 42,
    NPRAW_V2_SUFFIX_BYTES = 28,
    NPRAW_V3_SUFFIX_BYTES = 36,
    NPRAW_VERSION_LINEAR = 2,
    NPRAW_VERSION_COMPANDED = 3,
    NPRAW_VERSION_BLOCK_RANGE_LOSSLESS = 4,
    NPRAW_LOSSLESS_REALTIME_MODE = 10,
    AUDIT_BUFFER_BYTES = 4 * 1024 * 1024
};

static uint32_t crc_table[256];

static void initialize_crc_table(void) {
    for (uint32_t index = 0; index < 256; ++index) {
        uint32_t value = index;
        for (int bit = 0; bit < 8; ++bit) {
            value = (value & 1U) ? (0xEDB88320U ^ (value >> 1U)) : (value >> 1U);
        }
        crc_table[index] = value;
    }
}

static uint32_t update_crc32(uint32_t crc, const uint8_t *bytes, size_t count) {
#if defined(__aarch64__) && defined(__ARM_FEATURE_CRC32)
    while (count >= sizeof(uint64_t)) {
        uint64_t word;
        memcpy(&word, bytes, sizeof(word));
        crc = __crc32d(crc, word);
        bytes += sizeof(word);
        count -= sizeof(word);
    }
#endif
    for (size_t index = 0; index < count; ++index) {
#if defined(__aarch64__) && defined(__ARM_FEATURE_CRC32)
        crc = __crc32b(crc, bytes[index]);
#else
        crc = crc_table[(crc ^ bytes[index]) & 0xFFU] ^ (crc >> 8U);
#endif
    }
    return crc;
}

static uint32_t read_u32_be(const uint8_t *bytes) {
    return ((uint32_t) bytes[0] << 24U) |
           ((uint32_t) bytes[1] << 16U) |
           ((uint32_t) bytes[2] << 8U) |
           (uint32_t) bytes[3];
}

static uint64_t read_u64_be(const uint8_t *bytes) {
    return ((uint64_t) read_u32_be(bytes) << 32U) | read_u32_be(bytes + 4);
}

static int read_exact(int descriptor, uint8_t *destination, size_t count, int allow_clean_eof) {
    size_t offset = 0;
    while (offset < count) {
        ssize_t received = read(descriptor, destination + offset, count - offset);
        if (received == 0) {
            return allow_clean_eof && offset == 0 ? 0 : -2;
        }
        if (received < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        offset += (size_t) received;
    }
    return 1;
}

static void print_failure(const char *reason, uint64_t frame, uint64_t offset) {
    fprintf(stderr, "FAIL frame=%" PRIu64 " offset=%" PRIu64 " reason=%s errno=%d\n",
            frame, offset, reason, errno);
}

int main(int argc, char **argv) {
    if (argc != 4) {
        fprintf(stderr, "usage: %s <stream.npraw> <expected-width> <expected-height>\n", argv[0]);
        return 64;
    }

    const char *path = argv[1];
    const uint32_t expected_width = (uint32_t) strtoul(argv[2], NULL, 10);
    const uint32_t expected_height = (uint32_t) strtoul(argv[3], NULL, 10);
    if (expected_width == 0 || expected_height == 0) {
        fprintf(stderr, "expected dimensions must be positive\n");
        return 64;
    }

    int descriptor = open(path, O_RDONLY | O_CLOEXEC);
    if (descriptor < 0) {
        perror("open");
        return 66;
    }

    struct stat status;
    if (fstat(descriptor, &status) != 0) {
        perror("fstat");
        close(descriptor);
        return 74;
    }

    uint8_t *buffer = NULL;
    if (posix_memalign((void **) &buffer, 4096, AUDIT_BUFFER_BYTES) != 0 || buffer == NULL) {
        fprintf(stderr, "unable to allocate audit buffer\n");
        close(descriptor);
        return 71;
    }

    initialize_crc_table();
    uint64_t frame_count = 0;
    uint64_t bytes_walked = 0;
    uint64_t first_timestamp = 0;
    uint64_t previous_timestamp = 0;
    uint64_t last_timestamp = 0;
    uint64_t minimum_gap = UINT64_MAX;
    uint64_t maximum_gap = 0;
    uint64_t non_monotonic_gaps = 0;
    uint64_t gaps_over_60ms = 0;
    uint64_t crc_failures = 0;
    uint64_t field_failures = 0;
    uint32_t stream_bit_depth = 0;
    uint8_t prefix[NPRAW_PREFIX_BYTES];
    uint8_t suffix[NPRAW_V3_SUFFIX_BYTES];

    for (;;) {
        const int prefix_result = read_exact(descriptor, prefix, sizeof(prefix), 1);
        if (prefix_result == 0) break;
        if (prefix_result < 0) {
            print_failure("truncated-header-prefix", frame_count, bytes_walked);
            field_failures++;
            break;
        }
        const uint64_t frame_offset = bytes_walked;
        bytes_walked += sizeof(prefix);

        if (memcmp(prefix, "NPRAW", 5) != 0) {
            print_failure("bad-magic", frame_count, frame_offset);
            field_failures++;
            break;
        }
        const uint8_t version = prefix[5];
        const uint32_t mode = read_u32_be(prefix + 6);
        const uint32_t width = read_u32_be(prefix + 10);
        const uint32_t height = read_u32_be(prefix + 14);
        const uint32_t row_stride = read_u32_be(prefix + 18);
        const uint32_t pixel_stride = read_u32_be(prefix + 22);
        const uint64_t timestamp = read_u64_be(prefix + 26);
        const uint32_t original_size = read_u32_be(prefix + 34);
        const uint32_t payload_size = read_u32_be(prefix + 38);
        const size_t suffix_size = version == NPRAW_VERSION_LINEAR
                ? NPRAW_V2_SUFFIX_BYTES
                : ((version == NPRAW_VERSION_COMPANDED ||
                    version == NPRAW_VERSION_BLOCK_RANGE_LOSSLESS)
                    ? NPRAW_V3_SUFFIX_BYTES : 0);
        if (suffix_size == 0 || read_exact(descriptor, suffix, suffix_size, 0) != 1) {
            print_failure("bad-version-or-truncated-suffix", frame_count, frame_offset);
            field_failures++;
            break;
        }
        bytes_walked += suffix_size;

        const uint32_t bit_depth = read_u32_be(suffix);
        const uint32_t black_level = read_u32_be(suffix + 4);
        const uint32_t white_level = read_u32_be(suffix + 8);
        const uint64_t expected_crc = read_u64_be(suffix + 20);
        const uint32_t companding_type = suffix_size == NPRAW_V3_SUFFIX_BYTES
                ? read_u32_be(suffix + 28) : 0U;
        const uint32_t sensor_white_level = suffix_size == NPRAW_V3_SUFFIX_BYTES
                ? read_u32_be(suffix + 32) : white_level;
        const uint64_t sample_count = (uint64_t) expected_width * expected_height;
        const uint64_t expected_raw16_bytes = sample_count * 2U;
        const uint64_t expected_payload = bit_depth == 12U
                ? (sample_count * 3U + 1U) / 2U
                : (bit_depth == 16U ? expected_raw16_bytes : 0U);
        const int linear_contract = version == NPRAW_VERSION_LINEAR &&
                payload_size == expected_payload &&
                (bit_depth == 12U || bit_depth == 16U);
        const int block_range_contract = version == NPRAW_VERSION_BLOCK_RANGE_LOSSLESS &&
                bit_depth >= 1U && bit_depth <= 12U &&
                payload_size >= 12U && payload_size <= (sample_count * 3U + 1U) / 2U &&
                companding_type == 0U && sensor_white_level >= 1U &&
                sensor_white_level <= 4095U && white_level <= sensor_white_level;
        if (stream_bit_depth == 0U) stream_bit_depth = bit_depth;
        if ((!linear_contract && !block_range_contract) ||
            mode != NPRAW_LOSSLESS_REALTIME_MODE ||
            width != expected_width || height != expected_height ||
            row_stride != expected_width * 2U || pixel_stride != 2U ||
            original_size != expected_raw16_bytes ||
            bit_depth != stream_bit_depth ||
            (bit_depth == 12U && (black_level > 4095U || white_level > 4095U)) ||
            timestamp == 0U || expected_crc > UINT32_MAX) {
            print_failure("header-contract", frame_count, frame_offset);
            field_failures++;
            break;
        }

        uint32_t crc = UINT32_MAX;
        uint64_t remaining = payload_size;
        while (remaining > 0) {
            const size_t requested = remaining < AUDIT_BUFFER_BYTES
                    ? (size_t) remaining
                    : AUDIT_BUFFER_BYTES;
            if (read_exact(descriptor, buffer, requested, 0) != 1) {
                print_failure("truncated-payload", frame_count, frame_offset);
                field_failures++;
                remaining = 0;
                goto audit_complete;
            }
            crc = update_crc32(crc, buffer, requested);
            remaining -= requested;
            bytes_walked += requested;
        }
        crc ^= UINT32_MAX;
        if ((uint64_t) crc != expected_crc) {
            fprintf(stderr,
                    "FAIL frame=%" PRIu64 " offset=%" PRIu64
                    " reason=crc-mismatch expected=%" PRIu64 " actual=%" PRIu32 "\n",
                    frame_count, frame_offset, expected_crc, crc);
            crc_failures++;
        }

        if (frame_count == 0) {
            first_timestamp = timestamp;
        } else {
            if (timestamp <= previous_timestamp) {
                non_monotonic_gaps++;
            } else {
                const uint64_t gap = timestamp - previous_timestamp;
                if (gap < minimum_gap) minimum_gap = gap;
                if (gap > maximum_gap) maximum_gap = gap;
                if (gap > UINT64_C(60000000)) gaps_over_60ms++;
            }
        }
        previous_timestamp = timestamp;
        last_timestamp = timestamp;
        frame_count++;
        if ((frame_count & 127U) == 0U) {
            fprintf(stderr, "progress frames=%" PRIu64 " bytes=%" PRIu64 "\n",
                    frame_count, bytes_walked);
        }
    }

audit_complete:
    free(buffer);
    close(descriptor);
    const uint64_t duration_ns = frame_count > 1 ? last_timestamp - first_timestamp : 0;
    const double measured_fps = duration_ns > 0
            ? (double) (frame_count - 1) * 1000000000.0 / (double) duration_ns
            : 0.0;
    const int passed = field_failures == 0 && crc_failures == 0 &&
            non_monotonic_gaps == 0 && gaps_over_60ms == 0 &&
            bytes_walked == (uint64_t) status.st_size && frame_count > 1;
    printf("{\n");
    printf("  \"schema\": \"lq001-npraw-payload-audit-v1\",\n");
    printf("  \"status\": \"%s\",\n", passed ? "PASS" : "FAIL");
    printf("  \"crcEngine\": \"%s\",\n", NPRAW_AUDIT_CRC_ENGINE);
    printf("  \"fileBytes\": %" PRIu64 ",\n", (uint64_t) status.st_size);
    printf("  \"bytesWalked\": %" PRIu64 ",\n", bytes_walked);
    printf("  \"frameCount\": %" PRIu64 ",\n", frame_count);
    printf("  \"storageBitDepth\": %" PRIu32 ",\n", stream_bit_depth);
    printf("  \"crcFailures\": %" PRIu64 ",\n", crc_failures);
    printf("  \"fieldFailures\": %" PRIu64 ",\n", field_failures);
    printf("  \"nonMonotonicGaps\": %" PRIu64 ",\n", non_monotonic_gaps);
    printf("  \"gapsOver60ms\": %" PRIu64 ",\n", gaps_over_60ms);
    printf("  \"minimumGapNs\": %" PRIu64 ",\n", minimum_gap == UINT64_MAX ? 0 : minimum_gap);
    printf("  \"maximumGapNs\": %" PRIu64 ",\n", maximum_gap);
    printf("  \"firstTimestampNs\": %" PRIu64 ",\n", first_timestamp);
    printf("  \"lastTimestampNs\": %" PRIu64 ",\n", last_timestamp);
    printf("  \"measuredFps\": %.9f\n", measured_fps);
    printf("}\n");
    return passed ? 0 : 1;
}
