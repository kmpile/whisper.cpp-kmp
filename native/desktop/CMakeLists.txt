cmake_minimum_required(VERSION 3.19)
project(whisperkmp_jni_desktop C CXX)

# Desktop (JVM) JNI library: whisper.cpp built static and linked into a single
# libwhisperkmp-jni.{dylib|so|dll}, loaded from jvmMain resources at runtime.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# The published JVM artifact bundles ONE library per OS, selected by OS name
# only — build a universal dylib so Intel Macs work too, and keep ggml off
# host-specific instruction sets.
if(APPLE)
    set(CMAKE_OSX_ARCHITECTURES "arm64;x86_64")
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
endif()

get_filename_component(NATIVE_ROOT "${CMAKE_CURRENT_LIST_DIR}/.." ABSOLUTE)
set(CPP_DIR "${NATIVE_ROOT}/cpp")
set(WHISPER_SRC "${CPP_DIR}/whisper.cpp")
set(JNI_SRC "${NATIVE_ROOT}/src/main/cpp/whisperkmp-jni.cpp")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

add_subdirectory("${WHISPER_SRC}" "${CMAKE_BINARY_DIR}/whisper-build")

find_package(JNI REQUIRED)

add_library(whisperkmp-jni SHARED
    "${CPP_DIR}/src/whisperkmp.cpp"
    "${CPP_DIR}/src/Transcriber.cpp"
    "${JNI_SRC}"
)

target_include_directories(whisperkmp-jni PRIVATE
    "${CPP_DIR}/src"
    "${WHISPER_SRC}/include"
    "${WHISPER_SRC}/ggml/include"
    ${JNI_INCLUDE_DIRS}
)

target_compile_features(whisperkmp-jni PRIVATE cxx_std_17)
target_link_libraries(whisperkmp-jni PRIVATE whisper)
set_target_properties(whisperkmp-jni PROPERTIES OUTPUT_NAME "whisperkmp-jni")
