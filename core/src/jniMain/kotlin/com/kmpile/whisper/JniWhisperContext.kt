package com.kmpile.whisper

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Android + desktop-JVM context backed by the `whisperkmp-jni` native library. */
internal class JniWhisperContext(
    private val ctxPtr: Long,
    override val isMultilingual: Boolean,
    private val dispatcher: CoroutineDispatcher,
) : WhisperContext {

    override fun transcribe(samples: FloatArray, params: TranscribeParams): Flow<Segment> =
        segmentFlow(
            dispatcher = dispatcher,
            start = { onSegment ->
                val vad = params.vad
                nativeFull(
                    ctxPtr = ctxPtr,
                    samples = samples,
                    language = params.language,
                    translate = params.translate,
                    threads = params.threads,
                    strategy = params.strategy.ordinal,
                    beamSize = params.beamSize,
                    temperature = params.temperature,
                    initialPrompt = params.initialPrompt,
                    offsetMs = params.offsetMs,
                    durationMs = params.durationMs,
                    singleSegment = params.singleSegment,
                    vadModelPath = vad?.modelPath,
                    vadThreshold = vad?.threshold ?: 0.5f,
                    vadMinSpeechDurationMs = vad?.minSpeechDurationMs ?: 250,
                    vadMinSilenceDurationMs = vad?.minSilenceDurationMs ?: 100,
                    vadMaxSpeechDurationSec = vad?.maxSpeechDurationSec ?: Float.MAX_VALUE,
                    vadSpeechPadMs = vad?.speechPadMs ?: 30,
                    vadSamplesOverlapSec = vad?.samplesOverlapSec ?: 0.1f,
                    callback = object : SegmentCallback {
                        override fun onSegment(textUtf8: ByteArray, startMs: Long, endMs: Long) {
                            onSegment(Segment(String(textUtf8, Charsets.UTF_8), startMs, endMs))
                        }
                    },
                )
            },
            cancel = { nativeCancel(ctxPtr) },
        )

    override fun close() {
        if (closed) return
        closed = true
        // Abort any in-flight run, then free on the (single-threaded) dispatcher
        // so the native context is never deleted while whisper_full is executing.
        nativeCancel(ctxPtr)
        runBlocking(dispatcher) { nativeFree(ctxPtr) }
    }

    @Volatile
    private var closed = false
}

/** Shared construction for both JNI platforms once the native library is loaded. */
internal suspend fun createJniWhisperContext(
    modelPath: String,
    params: ContextParams,
    dispatcher: CoroutineDispatcher,
): WhisperContext = withContext(dispatcher) {
    val ptr = nativeInitFromFile(modelPath, params.useGpu, params.flashAttn)
    check(ptr != 0L) { "Failed to load whisper model: $modelPath" }
    JniWhisperContext(ptr, nativeIsMultilingual(ptr), dispatcher)
}
