package com.kmpile.whisper

import kotlinx.coroutines.CoroutineDispatcher

private const val MESSAGE =
    "whisper.cpp-kmp: the wasmJs target has no engine yet (needs an emscripten whisper.cpp build)."

internal actual suspend fun platformCreateContext(
    modelPath: String,
    params: ContextParams,
    dispatcher: CoroutineDispatcher,
): WhisperContext = throw UnsupportedOperationException(MESSAGE)

internal actual fun platformSystemInfo(): String = throw UnsupportedOperationException(MESSAGE)
