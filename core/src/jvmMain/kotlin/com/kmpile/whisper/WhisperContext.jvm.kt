package com.kmpile.whisper

import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import java.util.Locale

/**
 * Loads the desktop JNI library bundled in jvmMain resources under
 * `/native/<os>/`. The CMake build (see core/build.gradle.kts) drops the host's
 * libraries there along with a `native-libs.txt` manifest listing load order.
 */
private object JvmNativeLoader {
    @Volatile private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val platform = when {
            os.contains("mac") -> "macos"
            os.contains("nux") -> "linux"
            os.contains("win") -> "windows"
            else -> error("Unsupported OS for whisper.cpp-kmp: $os")
        }
        val resourceRoot = "/native/$platform"

        val names = javaClass.getResourceAsStream("$resourceRoot/native-libs.txt")
            ?.bufferedReader()?.useLines { lines ->
                lines.map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            }
            ?: error("whisper.cpp-kmp native libraries not bundled for platform: $platform")

        val tempDir = File(System.getProperty("java.io.tmpdir"), "whisperkmp-${System.nanoTime()}").apply {
            mkdirs(); deleteOnExit()
        }
        names.forEach { name ->
            val input = javaClass.getResourceAsStream("$resourceRoot/$name")
                ?: error("Missing bundled native library: $resourceRoot/$name")
            val out = File(tempDir, name)
            input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            out.deleteOnExit()
            System.load(out.absolutePath)
        }
        loaded = true
    }
}

internal actual suspend fun platformCreateContext(
    modelPath: String,
    params: ContextParams,
    dispatcher: CoroutineDispatcher,
): WhisperContext {
    JvmNativeLoader.load()
    return createJniWhisperContext(modelPath, params, dispatcher)
}

internal actual fun platformSystemInfo(): String {
    JvmNativeLoader.load()
    return nativeSystemInfo()
}
