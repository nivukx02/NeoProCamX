cmake_minimum_required(VERSION 3.22.1)
project(neoproc_vulkan LANGUAGES C CXX)

set(NEOPROCAMX_SCENE_COMPUTE_SOURCE scene_compute_portable.cpp)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # AArch64 guarantees Advanced SIMD. Other supported ABIs use the mathematically equivalent
    # portable implementation so no optional instruction set can make the JNI library unloadable.
    set(NEOPROCAMX_SCENE_COMPUTE_SOURCE scene_compute_neon.cpp)
endif()

add_library(
    neoproc_vulkan
    SHARED
    vulkan_bridge.cpp
    pipeline_cache_io.cpp
    native_buffer_manager.cpp
    native_camera_bridge.cpp
    native_buffer_bridge.cpp
    ai_neural_engine_bridge.cpp
    neural_flow_estimator.cpp
    hdr_merge_native.cpp
    npv10_export_native.cpp
    npv10_raw_native.cpp
    npv10_dmc_native.cpp
    npv10_lossless_chunk_codec.cpp
    npv10_gpu_range_encoder.cpp
    third_party/lz4/lz4.c
    npraw_realtime_native.cpp
    vulkan_parity_bridge.cpp
    offscreen_egl_encoder_bridge.cpp
    ${NEOPROCAMX_SCENE_COMPUTE_SOURCE}
    analysis_ingress_native.cpp
    niv99_vulkan_pipeline.cpp
)

target_compile_definitions(
    neoproc_vulkan
    PRIVATE
    VK_USE_PLATFORM_ANDROID_KHR
    NEOPROCAMX_STRICT_HIGH_END=1
)
target_compile_features(neoproc_vulkan PRIVATE cxx_std_20)
# Optimise the selected ABI-safe scene backend; AArch64 uses NEON and other ABIs use scalar code.
target_compile_options(neoproc_vulkan PRIVATE
    -O3
    -ftree-vectorize
    -ffast-math
    -fstack-protector-strong
    -D_FORTIFY_SOURCE=2
    -Wall
    -Wextra
    -Wformat=2
    -Werror=format-security
    -Werror
)
# NIV99 validates NaN/Inf metadata and performs calibration/covariance math; keep IEEE semantics even
# though legacy rendering translation units retain the target-wide fast-math setting.
set_source_files_properties(niv99_vulkan_pipeline.cpp PROPERTIES COMPILE_OPTIONS "-fno-fast-math")
# DMC masters routinely exceed 2 GiB. Keep existing POSIX calls explicitly large-file-safe on
# armeabi-v7a as well as the two 64-bit ABIs; this does not alter the container or record offsets.
set_source_files_properties(npv10_dmc_native.cpp PROPERTIES COMPILE_DEFINITIONS "_FILE_OFFSET_BITS=64")
# Keep our pinned block codec private to this JNI library. A vendor or another bundled library
# may also contain LZ4; symbol interposition must not silently select a different codec version.
set_source_files_properties(third_party/lz4/lz4.c PROPERTIES
    COMPILE_OPTIONS "-fvisibility=hidden"
    COMPILE_DEFINITIONS "LZ4LIB_VISIBILITY="
)
target_link_options(
    neoproc_vulkan
    PRIVATE
    "-Wl,-z,relro"
    "-Wl,-z,now"
    "-Wl,-z,noexecstack"
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
find_library(vulkan-lib vulkan)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)
find_library(dl-lib dl)
find_library(z-lib z)

target_link_libraries(
    neoproc_vulkan
    PRIVATE
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    ${vulkan-lib}
    ${egl-lib}
    ${gles-lib}
    ${dl-lib}
    ${z-lib}
)

# Private, debug-only physical-device probe. It is not linked into the recording library and is
# never included in release builds. Shell syscall support must not be mistaken for app support.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_library(neoproc_storage_qa SHARED ../../androidTest/cpp/Npv10KernelAioProbe.cpp
        ../../androidTest/cpp/Npv10RecordedWriterReplay.cpp)
    target_compile_features(neoproc_storage_qa PRIVATE cxx_std_20)
    target_compile_options(neoproc_storage_qa PRIVATE -O3 -Wall -Wextra -Werror
        -fstack-protector-strong -D_FORTIFY_SOURCE=2)
    target_link_options(neoproc_storage_qa PRIVATE "-Wl,-z,relro" "-Wl,-z,now"
        "-Wl,-z,noexecstack" "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
    target_link_libraries(neoproc_storage_qa PRIVATE ${log-lib})
endif()
