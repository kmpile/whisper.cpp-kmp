plugins {
    alias(libs.plugins.androidLibrary)
}

// Plain Android library that owns the whisper.cpp / JNI native build.
// AGP 9's KMP library plugin no longer supports externalNativeBuild, so the
// CMake build is isolated here and consumed by :core's androidMain.
// Absolute path to the shared native sources (native/cpp) owned by this module.
val nativeDir = projectDir.resolve("cpp").path.replace("\\", "/")

android {
    namespace = "com.kmpile.whisper.nativelib"
    compileSdk = 36
    // Pin NDK >= 28 so native .so are 16 KB page-size aligned by default
    // (Google Play requirement since Nov 2025).
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 24

        externalNativeBuild {
            cmake {
                arguments(
                    "-DWHISPERKMP_NATIVE_DIR=$nativeDir",
                    // whisper.cpp defaults BUILD_SHARED_LIBS=ON; force static so
                    // whisper/ggml are linked into libwhisperkmp.so and the
                    // version script actually hides their symbols.
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DWHISPER_BUILD_TESTS=OFF",
                    "-DWHISPER_BUILD_EXAMPLES=OFF",
                    "-DWHISPER_BUILD_SERVER=OFF",
                    "-DWHISPER_CURL=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
        }
        ndk {
            abiFilters.add("arm64-v8a")
            // x86_64 so the library runs on standard Android emulators.
            abiFilters.add("x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // AGP's default fork; widely preinstalled and sdkmanager-installable on CI.
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
