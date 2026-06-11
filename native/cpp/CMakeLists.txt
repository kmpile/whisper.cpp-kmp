cmake_minimum_required(VERSION 3.22.1)

project(whisperkmp C CXX)

option(WHISPERKMP_BUILD_APPLE "Build for Apple targets (static lib)" OFF)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src)
set(WHISPER_SRC ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# ARM CPU acceleration (KleidiAI i8mm/dotprod kernels) on arm64 Android only.
if (ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "Enable KleidiAI ARM kernels" FORCE)
endif ()

# Build whisper.cpp (+ ggml) straight from the submodule instead of vendoring
# copied sources. Bumping whisper.cpp = moving the submodule.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL           OFF CACHE BOOL "" FORCE)
add_subdirectory(${WHISPER_SRC} build-whisper)

if (WHISPERKMP_BUILD_APPLE)
    add_library(whisperkmp STATIC ${SOURCE_DIR}/whisperkmp.cpp ${SOURCE_DIR}/Transcriber.cpp)
else ()
    add_library(whisperkmp SHARED ${SOURCE_DIR}/whisperkmp.cpp ${SOURCE_DIR}/Transcriber.cpp)
endif ()

# Export only the WHISPERKMP_API symbols; keep our internals + whisper/ggml hidden.
set_target_properties(whisperkmp PROPERTIES
        C_VISIBILITY_PRESET hidden
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON
)

target_include_directories(whisperkmp PUBLIC
        ${SOURCE_DIR}
        ${WHISPER_SRC}/include
        ${WHISPER_SRC}/ggml/include
)

target_compile_features(whisperkmp PRIVATE cxx_std_17)
target_compile_options(whisperkmp PRIVATE -O3 -DNDEBUG)

# On the shared (Android) lib, restrict the exported ABI to the public C API via a
# linker version script (hides leaked whisper/ggml + our internal symbols). The
# iOS static lib's symbols don't leak to the framework consumer.
if (NOT WHISPERKMP_BUILD_APPLE)
    target_link_options(whisperkmp PRIVATE
            "-Wl,--version-script=${SOURCE_DIR}/whisperkmp.map")
endif ()

target_link_libraries(whisperkmp PUBLIC whisper)
