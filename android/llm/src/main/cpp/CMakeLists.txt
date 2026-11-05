# libnomadllm.so: llama.cpp (MIT) and its ggml CPU backend, linked statically,
# plus Pocket Nomad's small JNI wrapper (llama_jni.cpp).
#
# llama.cpp is pinned to one release. Gradle's fetchLlamaCpp task downloads that
# release's source once, checks its SHA-256 and passes the folder in as
# FETCHCONTENT_SOURCE_DIR_LLAMA_CPP (with FETCHCONTENT_FULLY_DISCONNECTED), so
# CMake fetches nothing itself. Configured by hand without it, FetchContent
# downloads the same pinned archive and refuses it unless the hash matches.
# Builds that must not touch the network (F-Droid) pass
# -Pnomad.llamaSourceDir=<path to an unpacked b11976 source tree> to Gradle.
cmake_minimum_required(VERSION 3.22.1)
project(nomadllm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11976.tar.gz
    URL_HASH SHA256=248756d1e3eac86a12b37a658ca545cf98bc0d8203977cd3450ee1499f60023f
)

# Only the library: no tools, server, tests, HTTPS or downloads at build time.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
# A build for every phone, not just the build machine's CPU.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# ggml's own thread pool instead of OpenMP, and no KleidiAI kernels: KleidiAI would
# be a second download at build time. Both could make answers faster later.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(llama_cpp)

add_library(nomadllm SHARED llama_jni.cpp)
target_link_libraries(nomadllm PRIVATE llama android log)
# Keep only the JNI entry points visible; 16 KB pages for Android 15+ devices.
set_target_properties(nomadllm PROPERTIES CXX_VISIBILITY_PRESET hidden)
target_link_options(nomadllm PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections")
