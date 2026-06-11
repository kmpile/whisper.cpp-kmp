import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.publish)
}

kotlin {
    // AGP 9 KMP Android library target (replaces androidTarget {} + android {}).
    android {
        namespace = "com.kmpile.whisper"
        compileSdk = 36
        minSdk = 24
        withHostTestBuilder {}.configure {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm()

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
        macosArm64(),
    ).forEach {
        it.binaries.framework {
            baseName = "WhisperKmp"
            isStatic = true
        }
        it.compilations.getByName("main") {
            cinterops {
                val whisperkmp by creating {
                    // Our glue header + whisper.cpp public headers (from the submodule).
                    val nativeRoot = "${rootProject.projectDir}/native/cpp"
                    includeDirs(
                        "$nativeRoot/src",
                        "$nativeRoot/whisper.cpp/include",
                        "$nativeRoot/whisper.cpp/ggml/include",
                    )
                }
            }
        }
    }

    // The custom jniMain dependsOn edges below opt out of the auto-applied default
    // hierarchy, so apply it explicitly to keep iOS/apple/native source sets wired.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.coroutines.core)
            }
        }
        // Shared JNI source set for Android + desktop JVM (externals + JniWhisperContext).
        val jniMain by creating { dependsOn(commonMain) }
        val androidMain by getting { dependsOn(jniMain) }
        val jvmMain by getting {
            dependsOn(jniMain)
            // Desktop native libs built by the desktop-jni CMake below.
            resources.srcDir(layout.buildDirectory.dir("generated/jvmNativeResources"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// ---- Desktop (JVM) JNI native build ----
// Builds libwhisperkmp-jni for the host OS (whisper.cpp linked static) and bundles
// it into jvmMain resources; WhisperContext.jvm.kt extracts + System.load()s it.
private val hostPlatform: String = System.getProperty("os.name").lowercase().let { os ->
    when {
        os.contains("mac") -> "macos"
        os.contains("nux") -> "linux"
        os.contains("win") -> "windows"
        else -> "unknown"
    }
}
private val desktopCmake: String =
    providers.gradleProperty("cmakePath").orNull
        ?: System.getenv("CMAKE_PATH")
        ?: listOf("/opt/homebrew/bin/cmake", "/usr/local/bin/cmake", "/usr/bin/cmake").firstOrNull { File(it).canExecute() }
        ?: "cmake"
private val desktopJniBuildDir = layout.buildDirectory.dir("desktop-jni").get().asFile
private val jvmNativeOutDir = layout.buildDirectory.dir("generated/jvmNativeResources/native/$hostPlatform").get().asFile
// CI builds each OS's lib on its own runner, drops them under generated resources,
// and bundles them without rebuilding: -PwhisperkmpPrebuiltNatives=true (or env
// WHISPERKMP_PREBUILT_NATIVES=1).
private val nativesPrebuilt: Boolean =
    providers.gradleProperty("whisperkmpPrebuiltNatives").orNull == "true" ||
        System.getenv("WHISPERKMP_PREBUILT_NATIVES") == "1"

val configureDesktopJni by tasks.registering(Exec::class) {
    group = "whisper-native"
    enabled = !nativesPrebuilt
    doFirst { desktopJniBuildDir.mkdirs() }
    commandLine(
        desktopCmake, "-S", rootProject.file("native/desktop").absolutePath,
        "-B", desktopJniBuildDir.absolutePath, "-DCMAKE_BUILD_TYPE=Release", "-Wno-dev",
    )
}
val buildDesktopJni by tasks.registering(Exec::class) {
    group = "whisper-native"
    enabled = !nativesPrebuilt
    dependsOn(configureDesktopJni)
    commandLine(desktopCmake, "--build", desktopJniBuildDir.absolutePath, "--config", "Release")
}
val copyDesktopJni by tasks.registering(Copy::class) {
    group = "whisper-native"
    onlyIf { !nativesPrebuilt }
    dependsOn(buildDesktopJni)
    val patterns = when (hostPlatform) {
        "macos" -> listOf("**/*.dylib")
        "linux" -> listOf("**/*.so", "**/*.so.*")
        "windows" -> listOf("**/*.dll")
        else -> emptyList()
    }
    from(fileTree(desktopJniBuildDir) { include(patterns) })
    eachFile { path = name }
    includeEmptyDirs = false
    into(jvmNativeOutDir)
    doFirst { jvmNativeOutDir.deleteRecursively(); jvmNativeOutDir.mkdirs() }
    doLast {
        val libs = jvmNativeOutDir.listFiles()
            ?.filter { it.isFile && it.name != "native-libs.txt" }?.map { it.name }?.sorted().orEmpty()
        jvmNativeOutDir.resolve("native-libs.txt").writeText(libs.joinToString("\n"))
    }
}
// In the prebuilt path, just (re)generate native-libs.txt for each OS folder CI placed.
val writeNativeLibsManifests by tasks.registering {
    group = "whisper-native"
    onlyIf { nativesPrebuilt }
    doLast {
        val root = layout.buildDirectory.dir("generated/jvmNativeResources/native").get().asFile
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val libs = dir.listFiles()
                ?.filter { it.isFile && it.name != "native-libs.txt" }?.map { it.name }?.sorted().orEmpty()
            dir.resolve("native-libs.txt").writeText(libs.joinToString("\n"))
        }
    }
}
tasks.matching { it.name == "jvmProcessResources" }.configureEach {
    dependsOn(if (nativesPrebuilt) writeNativeLibsManifests else copyDesktopJni)
}

// The JNI .so is built by :native (AGP 9's KMP plugin can't run CMake). Embed
// those libs into THIS module's AAR jniLibs so the published artifact is
// self-contained, instead of declaring a dependency on the unpublished module.
val embedNativeLibs by tasks.registering(Copy::class) {
    dependsOn(":native:assembleRelease")
    val jniLibsDir = layout.projectDirectory.dir("src/androidMain/jniLibs")
    doFirst { delete(jniLibsDir) }
    val nativeAar = rootProject.file("native/build/outputs/aar/native-release.aar")
    from(zipTree(nativeAar)) {
        include("jni/**")
        eachFile { path = path.removePrefix("jni/") }
    }
    includeEmptyDirs = false
    into(layout.projectDirectory.dir("src/androidMain/jniLibs"))
}
tasks.matching { it.name == "mergeAndroidMainJniLibFolders" }.configureEach {
    dependsOn(embedNativeLibs)
}

// Optional file-based Maven repository (e.g. a local artifact-repo checkout).
// Pass -PartifactRepoUrl=file:///path/to/maven to publish there via
// publishAllPublicationsToArtifactRepoRepository.
publishing {
    repositories {
        providers.gradleProperty("artifactRepoUrl").orNull?.let { repoUrl ->
            maven {
                name = "artifactRepo"
                url = uri(repoUrl)
            }
        }
    }
}
