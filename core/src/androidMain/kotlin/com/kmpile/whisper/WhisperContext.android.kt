package com.kmpile.whisper

import kotlinx.coroutines.CoroutineDispatcher

private object AndroidNativeLoader {
    @Volatile private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        System.loadLibrary("whisperkmp-jni")
        loaded = true
    }
}

internal actual suspend fun platformCreateContext(
    modelPath: String,
    params: ContextParams,
    dispatcher: CoroutineDispatcher,
): WhisperContext {
    AndroidNativeLoader.load()
    return createJniWhisperContext(modelPath, params, dispatcher)
}

internal actual fun platformSystemInfo(): String {
    AndroidNativeLoader.load()
    return nativeSystemInfo()
}
