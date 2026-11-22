cmake_minimum_required(VERSION 3.22.1)
project(mobileplay_chiaki_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build native dependencies as static libraries" FORCE)

get_filename_component(MOBILEPLAY_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(DEPS "${MOBILEPLAY_ROOT}/third_party")
set(CHIAKI_NG_DIR "${DEPS}/chiaki-ng")
set(ANDROID_DEPS "${DEPS}/android-deps/${ANDROID_ABI}")
set(OPENSSL_ROOT_DIR "${ANDROID_DEPS}/openssl-3.5.9")

foreach(required IN ITEMS
    "${CHIAKI_NG_DIR}/lib/CMakeLists.txt"
    "${CHIAKI_NG_DIR}/third-party/curl/CMakeLists.txt"
    "${CHIAKI_NG_DIR}/third-party/nanopb/CMakeLists.txt"
    "${CHIAKI_NG_DIR}/third-party/gf-complete/src/gf.c"
    "${CHIAKI_NG_DIR}/third-party/jerasure/src/jerasure.c"
    "${DEPS}/src/json-c/CMakeLists.txt"
    "${DEPS}/src/miniupnp/miniupnpc/CMakeLists.txt"
    "${OPENSSL_ROOT_DIR}/lib/libssl.a"
    "${OPENSSL_ROOT_DIR}/lib/libcrypto.a"
    "${OPENSSL_ROOT_DIR}/include/openssl/ssl.h"
    "${ANDROID_DEPS}/include/opus/opus.h"
    "${ANDROID_DEPS}/lib/libopus.a")
    if(NOT EXISTS "${required}")
        message(FATAL_ERROR "Missing MobilePlay native dependency: ${required}. See README.md for local setup.")
    endif()
endforeach()

list(PREPEND CMAKE_MODULE_PATH "${CHIAKI_NG_DIR}/cmake")
list(PREPEND CMAKE_PREFIX_PATH "${ANDROID_DEPS}")
list(PREPEND CMAKE_FIND_ROOT_PATH "${ANDROID_DEPS}")
list(PREPEND CMAKE_PROGRAM_PATH "${DEPS}/tools/protoc/bin")

set(OPENSSL_USE_STATIC_LIBS TRUE)
set(OPENSSL_INCLUDE_DIR "${OPENSSL_ROOT_DIR}/include" CACHE PATH "Pinned Android OpenSSL headers" FORCE)
set(OPENSSL_SSL_LIBRARY "${OPENSSL_ROOT_DIR}/lib/libssl.a" CACHE FILEPATH "Pinned Android OpenSSL SSL library" FORCE)
set(OPENSSL_CRYPTO_LIBRARY "${OPENSSL_ROOT_DIR}/lib/libcrypto.a" CACHE FILEPATH "Pinned Android OpenSSL crypto library" FORCE)
find_package(OpenSSL 3.5.9 EXACT REQUIRED)
find_package(PythonInterp 3 REQUIRED)
find_program(PROTOC protoc REQUIRED)
set(nanopb_PROTOC_PATH "${PROTOC}" CACHE FILEPATH "Host Protobuf compiler" FORCE)
set(nanopb_BUILD_GENERATOR OFF CACHE BOOL "" FORCE)

# Build Chiaki's protocol and dependency targets without the upstream Android app or desktop frontends.
set(CHIAKI_ENABLE_ANDROID ON)
set(CHIAKI_ENABLE_TESTS OFF)
set(CHIAKI_ENABLE_STEAM_SHORTCUT OFF)
set(CHIAKI_USE_SYSTEM_CURL OFF)
set(CHIAKI_USE_SYSTEM_NANOPB OFF)
set(CHIAKI_USE_SYSTEM_JERASURE OFF)
set(CHIAKI_LIB_ENABLE_OPUS ON)
set(CHIAKI_LIB_JSONC_EXTERNAL_PROJECT ON)
set(CHIAKI_LIB_MINIUPNPC_EXTERNAL_PROJECT ON)
set(CURL_USE_OPENSSL ON CACHE BOOL "" FORCE)
set(CURL_USE_LIBPSL OFF CACHE BOOL "" FORCE)
set(CURL_USE_LIBSSH2 OFF CACHE BOOL "" FORCE)
set(CURL_USE_LIBUV OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_APPS OFF CACHE BOOL "" FORCE)

set(JSONC_SOURCE "${DEPS}/src/json-c")
set(JSONC_BINARY "${CMAKE_CURRENT_BINARY_DIR}/json-c")
set(JSONC_PREFIX_INCLUDE_DIR "${CMAKE_CURRENT_BINARY_DIR}/dependency-headers")
set(json-c_BINARY_DIR "${JSONC_BINARY}")
set(DISABLE_EXTRA_LIBS ON CACHE BOOL "" FORCE)
add_subdirectory("${JSONC_SOURCE}" "${JSONC_BINARY}" EXCLUDE_FROM_ALL)

set(MINIUPNPC_SOURCE "${DEPS}/src/miniupnp/miniupnpc")
set(MINIUPNPC_PREFIX_INCLUDE_DIR "${JSONC_PREFIX_INCLUDE_DIR}")
set(UPNPC_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(UPNPC_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(UPNPC_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(UPNPC_BUILD_SAMPLE OFF CACHE BOOL "" FORCE)
set(UPNPC_NO_INSTALL ON CACHE BOOL "" FORCE)
add_subdirectory("${MINIUPNPC_SOURCE}" miniupnpc EXCLUDE_FROM_ALL)

# Upstream sources use namespaced includes. Copy headers because a Windows checkout
# may lack permission to create symbolic links.
file(COPY "${JSONC_SOURCE}/" "${JSONC_BINARY}/" DESTINATION "${JSONC_PREFIX_INCLUDE_DIR}/json-c"
    FILES_MATCHING PATTERN "*.h" PATTERN "CMakeFiles" EXCLUDE PATTERN "tests" EXCLUDE)
file(COPY "${MINIUPNPC_SOURCE}/include/" DESTINATION "${MINIUPNPC_PREFIX_INCLUDE_DIR}/miniupnpc"
    FILES_MATCHING PATTERN "*.h")

add_subdirectory("${CHIAKI_NG_DIR}/third-party" chiaki-deps EXCLUDE_FROM_ALL)
add_subdirectory("${CHIAKI_NG_DIR}/lib" chiaki-lib EXCLUDE_FROM_ALL)

# An older local install may have OpenSSL headers under the Opus include root.
# Keep the pinned OpenSSL headers first even when an imported target adds them.
set_property(TARGET chiaki-lib PROPERTY NO_SYSTEM_FROM_IMPORTED TRUE)
target_include_directories(chiaki-lib BEFORE PRIVATE "${OPENSSL_INCLUDE_DIR}")

add_library(chiaki_jni SHARED mobileplay_chiaki_jni.cpp)
set_property(TARGET chiaki_jni PROPERTY NO_SYSTEM_FROM_IMPORTED TRUE)
target_include_directories(chiaki_jni PRIVATE "${OPENSSL_INCLUDE_DIR}")
target_include_directories(chiaki_jni PRIVATE "${ANDROID_DEPS}/include")
target_compile_definitions(chiaki_jni PRIVATE
    MOBILEPLAY_WITH_LIBCHIAKI=1
    MOBILEPLAY_CHIAKI_PINNED_TAG="v1.10.1")
target_link_options(chiaki_jni PRIVATE
    "-Wl,-z,defs"
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
target_link_libraries(chiaki_jni PRIVATE chiaki-lib log)
