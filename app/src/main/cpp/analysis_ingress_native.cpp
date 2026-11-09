#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <limits>

namespace {

inline uint8_t p010_to_analysis_8(const uint8_t* source, int64_t index) {
    const uint16_t word = static_cast<uint16_t>(source[index]) |
        static_cast<uint16_t>(static_cast<uint16_t>(source[index + 1]) << 8U);
    const uint16_t code10 = static_cast<uint16_t>(word >> 6U);
    return static_cast<uint8_t>(std::min(255, static_cast<int>((code10 + 2U) >> 2U)));
}

bool valid_product(jint first, jint second, jsize available) {
    if (first <= 0 || second <= 0 || available < 0) return false;
    const int64_t required = static_cast<int64_t>(first) * static_cast<int64_t>(second);
    return required <= static_cast<int64_t>(available) &&
        required <= static_cast<int64_t>(std::numeric_limits<jint>::max());
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_pipeline_NativeAnalysisIngress_nativeCopyScaledP010Luma(
    JNIEnv* env,
    jclass,
    jobject source_buffer,
    jint source_limit,
    jintArray source_rows_array,
    jintArray source_columns_array,
    jint target_width,
    jint target_height,
    jbyteArray destination_array) {
    if (!source_buffer || !source_rows_array || !source_columns_array || !destination_array ||
        source_limit <= 0 || target_width <= 0 || target_height <= 0) {
        return JNI_FALSE;
    }
    auto* source = static_cast<const uint8_t*>(env->GetDirectBufferAddress(source_buffer));
    const jlong source_capacity = env->GetDirectBufferCapacity(source_buffer);
    const jsize row_count = env->GetArrayLength(source_rows_array);
    const jsize column_count = env->GetArrayLength(source_columns_array);
    const jsize destination_size = env->GetArrayLength(destination_array);
    if (!source || source_capacity < source_limit || row_count < target_height ||
        column_count < target_width ||
        !valid_product(target_width, target_height, destination_size)) {
        return JNI_FALSE;
    }

    jint* source_rows = env->GetIntArrayElements(source_rows_array, nullptr);
    jint* source_columns = env->GetIntArrayElements(source_columns_array, nullptr);
    jbyte* destination_raw = env->GetByteArrayElements(destination_array, nullptr);
    if (!source_rows || !source_columns || !destination_raw) {
        if (source_rows) env->ReleaseIntArrayElements(source_rows_array, source_rows, JNI_ABORT);
        if (source_columns) {
            env->ReleaseIntArrayElements(source_columns_array, source_columns, JNI_ABORT);
        }
        if (destination_raw) {
            env->ReleaseByteArrayElements(destination_array, destination_raw, JNI_ABORT);
        }
        return JNI_FALSE;
    }

    auto* destination = reinterpret_cast<uint8_t*>(destination_raw);
    int destination_index = 0;
    for (int target_y = 0; target_y < target_height; ++target_y) {
        const int64_t source_row = source_rows[target_y];
        for (int target_x = 0; target_x < target_width; ++target_x) {
            const int64_t source_index = source_row + source_columns[target_x];
            destination[destination_index++] =
                source_index >= 0 && source_index + 1 < source_limit
                    ? p010_to_analysis_8(source, source_index)
                    : 0;
        }
    }

    env->ReleaseIntArrayElements(source_rows_array, source_rows, JNI_ABORT);
    env->ReleaseIntArrayElements(source_columns_array, source_columns, JNI_ABORT);
    env->ReleaseByteArrayElements(destination_array, destination_raw, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_pipeline_NativeAnalysisIngress_nativeCopyScaledP010Chroma(
    JNIEnv* env,
    jclass,
    jobject source_buffer,
    jint source_limit,
    jint source_row_stride,
    jint source_pixel_stride,
    jintArray source_rows_array,
    jintArray source_columns_array,
    jint target_width,
    jint target_height,
    jint target_row_stride,
    jbyteArray u_destination_array,
    jbyteArray v_destination_array) {
    if (!source_buffer || !source_rows_array || !source_columns_array ||
        !u_destination_array || !v_destination_array || source_limit <= 0 ||
        source_row_stride <= 0 || source_pixel_stride <= 0 || target_width <= 0 ||
        target_height <= 0 || target_row_stride < target_width) {
        return JNI_FALSE;
    }
    auto* source = static_cast<const uint8_t*>(env->GetDirectBufferAddress(source_buffer));
    const jlong source_capacity = env->GetDirectBufferCapacity(source_buffer);
    const jsize row_count = env->GetArrayLength(source_rows_array);
    const jsize column_count = env->GetArrayLength(source_columns_array);
    const jsize u_size = env->GetArrayLength(u_destination_array);
    const jsize v_size = env->GetArrayLength(v_destination_array);
    if (!source || source_capacity < source_limit || row_count < target_height ||
        column_count < target_width ||
        !valid_product(target_row_stride, target_height, u_size) ||
        !valid_product(target_row_stride, target_height, v_size)) {
        return JNI_FALSE;
    }

    jint* source_rows = env->GetIntArrayElements(source_rows_array, nullptr);
    jint* source_columns = env->GetIntArrayElements(source_columns_array, nullptr);
    jbyte* u_raw = env->GetByteArrayElements(u_destination_array, nullptr);
    jbyte* v_raw = env->GetByteArrayElements(v_destination_array, nullptr);
    if (!source_rows || !source_columns || !u_raw || !v_raw) {
        if (source_rows) env->ReleaseIntArrayElements(source_rows_array, source_rows, JNI_ABORT);
        if (source_columns) {
            env->ReleaseIntArrayElements(source_columns_array, source_columns, JNI_ABORT);
        }
        if (u_raw) env->ReleaseByteArrayElements(u_destination_array, u_raw, JNI_ABORT);
        if (v_raw) env->ReleaseByteArrayElements(v_destination_array, v_raw, JNI_ABORT);
        return JNI_FALSE;
    }

    auto* u_destination = reinterpret_cast<uint8_t*>(u_raw);
    auto* v_destination = reinterpret_cast<uint8_t*>(v_raw);
    for (int target_y = 0; target_y < target_height; ++target_y) {
        const int64_t source_row =
            static_cast<int64_t>(source_rows[target_y]) * source_row_stride;
        const int destination_row = target_y * target_row_stride;
        for (int target_x = 0; target_x < target_width; ++target_x) {
            const int64_t source_index = source_row +
                static_cast<int64_t>(source_columns[target_x]) * source_pixel_stride;
            const int destination_index = destination_row + target_x;
            u_destination[destination_index] =
                source_index >= 0 && source_index + 1 < source_limit
                    ? p010_to_analysis_8(source, source_index)
                    : 0;
            const int64_t v_source_index = source_index + 2;
            v_destination[destination_index] =
                v_source_index >= 0 && v_source_index + 1 < source_limit
                    ? p010_to_analysis_8(source, v_source_index)
                    : 0;
        }
    }

    env->ReleaseIntArrayElements(source_rows_array, source_rows, JNI_ABORT);
    env->ReleaseIntArrayElements(source_columns_array, source_columns, JNI_ABORT);
    env->ReleaseByteArrayElements(u_destination_array, u_raw, 0);
    env->ReleaseByteArrayElements(v_destination_array, v_raw, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_pipeline_NativeAnalysisIngress_nativeComputeSceneStats(
    JNIEnv* env,
    jclass,
    jbyteArray luma_array,
    jint width,
    jint height,
    jint max_stats_long_edge,
    jintArray histogram_array,
    jlongArray totals_array) {
    if (!luma_array || !histogram_array || !totals_array || width < 0 || height < 0 ||
        env->GetArrayLength(histogram_array) < 256 || env->GetArrayLength(totals_array) < 2) {
        return JNI_FALSE;
    }
    const int64_t required_luma = static_cast<int64_t>(width) * height;
    if (required_luma < 0 || required_luma > env->GetArrayLength(luma_array)) {
        return JNI_FALSE;
    }
    jbyte* luma_raw = env->GetByteArrayElements(luma_array, nullptr);
    jint* histogram = env->GetIntArrayElements(histogram_array, nullptr);
    jlong* totals = env->GetLongArrayElements(totals_array, nullptr);
    if (!luma_raw || !histogram || !totals) {
        if (luma_raw) env->ReleaseByteArrayElements(luma_array, luma_raw, JNI_ABORT);
        if (histogram) env->ReleaseIntArrayElements(histogram_array, histogram, JNI_ABORT);
        if (totals) env->ReleaseLongArrayElements(totals_array, totals, JNI_ABORT);
        return JNI_FALSE;
    }

    std::fill(histogram, histogram + 256, 0);
    const auto* luma = reinterpret_cast<const uint8_t*>(luma_raw);
    const int effective_long_edge = std::max(1, max_stats_long_edge);
    const int step = std::max(1, std::max(width, height) / effective_long_edge);
    uint64_t luma_sum = 0;
    uint64_t sample_count = 0;
    for (int y = 0; y < height; y += step) {
        const int64_t row_offset = static_cast<int64_t>(y) * width;
        for (int x = 0; x < width; x += step) {
            const uint8_t value = luma[row_offset + x];
            ++histogram[value];
            luma_sum += value;
            ++sample_count;
        }
    }
    totals[0] = static_cast<jlong>(luma_sum);
    totals[1] = static_cast<jlong>(sample_count);

    env->ReleaseByteArrayElements(luma_array, luma_raw, JNI_ABORT);
    env->ReleaseIntArrayElements(histogram_array, histogram, 0);
    env->ReleaseLongArrayElements(totals_array, totals, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_pipeline_NativeAnalysisIngress_nativeCollectPerceptualLumaAggregates(
    JNIEnv* env,
    jclass,
    jbyteArray luma_array,
    jint width,
    jint height,
    jlongArray totals_array) {
    if (!luma_array || !totals_array || width < 0 || height < 0 ||
        env->GetArrayLength(totals_array) < 6) {
        return JNI_FALSE;
    }
    const int64_t required_luma = static_cast<int64_t>(width) * height;
    if (required_luma < 0 || required_luma > env->GetArrayLength(luma_array)) {
        return JNI_FALSE;
    }
    jlong* totals = env->GetLongArrayElements(totals_array, nullptr);
    if (!totals) return JNI_FALSE;
    std::fill(totals, totals + 6, 0);
    if (width < 3 || height < 3) {
        env->ReleaseLongArrayElements(totals_array, totals, 0);
        return JNI_TRUE;
    }
    jbyte* luma_raw = env->GetByteArrayElements(luma_array, nullptr);
    if (!luma_raw) {
        env->ReleaseLongArrayElements(totals_array, totals, JNI_ABORT);
        return JNI_FALSE;
    }

    const auto* luma = reinterpret_cast<const uint8_t*>(luma_raw);
    const int step_x = std::max(1, width / 128);
    const int step_y = std::max(1, height / 128);
    uint64_t flat_sum = 0;
    uint64_t flat_sum_squares = 0;
    uint64_t flat_count = 0;
    uint64_t laplacian_sum = 0;
    uint64_t textured_count = 0;
    uint64_t total_samples = 0;
    for (int y = 1; y < height - 1; y += step_y) {
        const int64_t row = static_cast<int64_t>(y) * width;
        for (int x = 1; x < width - 1; x += step_x) {
            const int64_t index = row + x;
            const int center = luma[index];
            const int left = luma[index - 1];
            const int right = luma[index + 1];
            const int up = luma[index - width];
            const int down = luma[index + width];
            const int gradient = std::abs(right - left) + std::abs(down - up);
            const int laplacian =
                std::abs((4 * center) - left - right - up - down);
            if (gradient <= 22) {
                flat_sum += static_cast<uint64_t>(center);
                flat_sum_squares += static_cast<uint64_t>(center * center);
                ++flat_count;
            } else if (gradient >= 40) {
                ++textured_count;
            }
            laplacian_sum += static_cast<uint64_t>(laplacian);
            ++total_samples;
        }
    }
    totals[0] = static_cast<jlong>(flat_sum);
    totals[1] = static_cast<jlong>(flat_sum_squares);
    totals[2] = static_cast<jlong>(flat_count);
    totals[3] = static_cast<jlong>(laplacian_sum);
    totals[4] = static_cast<jlong>(textured_count);
    totals[5] = static_cast<jlong>(total_samples);

    env->ReleaseByteArrayElements(luma_array, luma_raw, JNI_ABORT);
    env->ReleaseLongArrayElements(totals_array, totals, 0);
    return JNI_TRUE;
}
