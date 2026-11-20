cmake_minimum_required(VERSION 3.22.1)
project(mobileplay_chiaki_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# Pinned upstream chiaki-ng checkout (v1.10.1 / commit 7ea0b0c) populated by GitHub Actions or local setup.
set(CHIAKI_NG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/chiaki-ng")
set(PREBUILT_DEPS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/android-deps/${ANDROID_ABI}")

find_library(ANDROID_LOG_LIB log)
find_library(ANDROID_MEDIANDK_LIB mediandk)
find_library(ANDROID_NATIVE_LIB android)

if(EXISTS "${CHIAKI_NG_DIR}/lib/CMakeLists.txt")
    message(STATUS "MobilePlay: Building chiaki_jni against chiaki-ng at ${CHIAKI_NG_DIR} (ABI=${ANDROID_ABI})")

    # Point CMake pkg/find paths at cross-compiled Android arm64-v8a OpenSSL/mbedTLS & Opus libraries
    # built by .github/workflows/android-ndk-build.yml (or local NDK prep script).
    if(EXISTS "${PREBUILT_DEPS_DIR}")
        list(PREPEND CMAKE_PREFIX_PATH "${PREBUILT_DEPS_DIR}")
        list(PREPEND CMAKE_FIND_ROOT_PATH "${PREBUILT_DEPS_DIR}")
        set(OPENSSL_ROOT_DIR "${PREBUILT_DEPS_DIR}" CACHE PATH "" FORCE)
        set(OPUS_ROOT_DIR "${PREBUILT_DEPS_DIR}" CACHE PATH "" FORCE)
    endif()

    set(CHIAKI_ENABLE_TESTS OFF CACHE BOOL "" FORCE)
    set(CHIAKI_ENABLE_CLI OFF CACHE BOOL "" FORCE)
    set(CHIAKI_ENABLE_GUI OFF CACHE BOOL "" FORCE)
    set(CHIAKI_ENABLE_ANDROID ON CACHE BOOL "" FORCE)
    set(CHIAKI_LIB_ENABLE_OPUS ON CACHE BOOL "" FORCE)
    set(CHIAKI_USE_SYSTEM_JERASURE OFF CACHE STRING "" FORCE)
    set(CHIAKI_USE_SYSTEM_NANOPB OFF CACHE STRING "" FORCE)

    add_subdirectory("${CHIAKI_NG_DIR}/lib" "${CMAKE_CURRENT_BINARY_DIR}/chiaki-lib")

    add_library(chiaki_jni SHARED
        mobileplay_chiaki_jni.cpp
    )

    target_compile_definitions(chiaki_jni PRIVATE
        MOBILEPLAY_WITH_LIBCHIAKI=1
        MOBILEPLAY_CHIAKI_PINNED_TAG="v1.10.1"
    )

    target_include_directories(chiaki_jni PRIVATE
        "${CHIAKI_NG_DIR}/lib/include"
        "${CMAKE_CURRENT_BINARY_DIR}/chiaki-lib/include"
    )

    # Enforce Android 15+ 16 KB ELF LOAD segment page alignment.
    target_link_options(chiaki_jni PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
    )

    target_link_libraries(chiaki_jni
        chiaki-lib
        "${ANDROID_LOG_LIB}"
        "${ANDROID_MEDIANDK_LIB}"
        "${ANDROID_NATIVE_LIB}"
    )
else()
    message(FATAL_ERROR
        "MobilePlay native build requested (-PmobileplayEnableNativeChiaki=true), "
        "but ${CHIAKI_NG_DIR}/lib/CMakeLists.txt was not found. "
        "Clone chiaki-ng v1.10.1 (commit 7ea0b0c) with --recurse-submodules into third_party/chiaki-ng."
    )
endif()
