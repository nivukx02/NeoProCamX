cmake_minimum_required(VERSION 3.22.1)
project(niv99_native_compile LANGUAGES CXX)

# Isolated production-source compile gate. This deliberately does not configure or build the app.
add_library(
    niv99_native_compile
    STATIC
    ../../app/src/main/cpp/niv99_vulkan_pipeline.cpp
)

target_include_directories(
    niv99_native_compile
    PRIVATE
    ../../app/src/main/cpp
)

target_compile_features(niv99_native_compile PRIVATE cxx_std_20)
target_compile_definitions(
    niv99_native_compile
    PRIVATE
    VK_USE_PLATFORM_ANDROID_KHR
    NEOPROCAMX_STRICT_HIGH_END=1
)
target_compile_options(
    niv99_native_compile
    PRIVATE
    -O3
    -fno-fast-math
    -fstack-protector-strong
    -D_FORTIFY_SOURCE=2
    -Wall
    -Wextra
    -Wformat=2
    -Werror=format-security
    -Werror
)
