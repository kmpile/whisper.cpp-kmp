cmake_minimum_required(VERSION 3.22.1)

project(whisperkmp-jni)

# Shared native sources live at <module>/native/cpp; WHISPERKMP_NATIVE_DIR is
# injected by build.gradle.kts as an absolute path. Build them into this module's
# build dir, producing the SHARED libwhisperkmp.so this JNI lib links against.
add_subdirectory(${WHISPERKMP_NATIVE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/native-build)

add_library(
        whisperkmp-jni
        SHARED
        whisperkmp-jni.cpp
)
target_link_libraries(whisperkmp-jni whisperkmp log android)
