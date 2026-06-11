package com.kmpile.whisper

/**
 * Invoked once per finalized segment from native code, on the transcription thread.
 * Text crosses the boundary as raw UTF-8 bytes: whisper.cpp emits genuine UTF-8
 * (including 4-byte sequences), which JNI's Modified-UTF-8 strings cannot carry.
 */
interface SegmentCallback {
    fun onSegment(textUtf8: ByteArray, startMs: Long, endMs: Long)
}

/** Returns an opaque context pointer, or 0 on failure. */
external fun nativeInitFromFile(modelPath: String, useGpu: Boolean, flashAttn: Boolean): Long

external fun nativeIsMultilingual(ctxPtr: Long): Boolean

/**
 * Runs `whisper_full` synchronously, invoking [callback] per segment. Params are
 * passed flat (rather than as a marshalled object) to keep the JNI boundary simple.
 * `vadModelPath == null` disables VAD. Returns whisper.cpp's status (0 = success).
 */
external fun nativeFull(
    ctxPtr: Long,
    samples: FloatArray,
    language: String?,
    translate: Boolean,
    threads: Int,
    strategy: Int,
    beamSize: Int,
    temperature: Float,
    initialPrompt: String?,
    offsetMs: Int,
    durationMs: Int,
    singleSegment: Boolean,
    vadModelPath: String?,
    vadThreshold: Float,
    vadMinSpeechDurationMs: Int,
    vadMinSilenceDurationMs: Int,
    vadMaxSpeechDurationSec: Float,
    vadSpeechPadMs: Int,
    vadSamplesOverlapSec: Float,
    callback: SegmentCallback,
): Int

external fun nativeCancel(ctxPtr: Long)

external fun nativeFree(ctxPtr: Long)

external fun nativeSystemInfo(): String
