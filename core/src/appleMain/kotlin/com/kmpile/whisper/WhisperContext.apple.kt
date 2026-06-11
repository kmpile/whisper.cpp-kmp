package com.kmpile.whisper

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import whisperkmp.whisperkmp_cancel
import whisperkmp.whisperkmp_free
import whisperkmp.whisperkmp_full
import whisperkmp.whisperkmp_init_from_file
import whisperkmp.whisperkmp_is_multilingual
import whisperkmp.whisperkmp_params
import whisperkmp.whisperkmp_system_info

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun platformCreateContext(
    modelPath: String,
    params: ContextParams,
    dispatcher: CoroutineDispatcher,
): WhisperContext = withContext(dispatcher) {
    val ptr = whisperkmp_init_from_file(modelPath, params.useGpu, params.flashAttn)
    check(ptr != 0L) { "Failed to load whisper model: $modelPath" }
    AppleWhisperContext(ptr, whisperkmp_is_multilingual(ptr), dispatcher)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformSystemInfo(): String = whisperkmp_system_info()?.toKString().orEmpty()

@OptIn(ExperimentalForeignApi::class)
internal class AppleWhisperContext(
    private val ctxPtr: Long,
    override val isMultilingual: Boolean,
    private val dispatcher: CoroutineDispatcher,
) : WhisperContext {

    override fun transcribe(samples: FloatArray, params: TranscribeParams): Flow<Segment> =
        segmentFlow(
            dispatcher = dispatcher,
            start = { onSegment -> runTranscription(samples, params, onSegment) },
            cancel = { whisperkmp_cancel(ctxPtr) },
        )

    private fun runTranscription(
        samples: FloatArray,
        params: TranscribeParams,
        onSegment: (Segment) -> Unit,
    ): Int {
        val ref = StableRef.create(onSegment)
        // Called synchronously on this thread for each finalized segment.
        val callback = staticCFunction<CPointer<ByteVar>?, Long, Long, COpaquePointer?, Unit> { textPtr, t0, t1, userData ->
            userData?.asStableRef<(Segment) -> Unit>()?.get()
                ?.invoke(Segment(textPtr?.toKString().orEmpty(), t0, t1))
        }
        try {
            return memScoped {
                val vad = params.vad
                val p = alloc<whisperkmp_params> {
                    language = params.language?.cstr?.ptr
                    translate = params.translate
                    n_threads = params.threads
                    strategy = params.strategy.ordinal
                    beam_size = params.beamSize
                    temperature = params.temperature
                    initial_prompt = params.initialPrompt?.cstr?.ptr
                    offset_ms = params.offsetMs
                    duration_ms = params.durationMs
                    single_segment = params.singleSegment
                    vad_model_path = vad?.modelPath?.cstr?.ptr
                    vad_threshold = vad?.threshold ?: 0.5f
                    vad_min_speech_duration_ms = vad?.minSpeechDurationMs ?: 250
                    vad_min_silence_duration_ms = vad?.minSilenceDurationMs ?: 100
                    vad_max_speech_duration_s = vad?.maxSpeechDurationSec ?: Float.MAX_VALUE
                    vad_speech_pad_ms = vad?.speechPadMs ?: 30
                    vad_samples_overlap = vad?.samplesOverlapSec ?: 0.1f
                }
                samples.usePinned { pinned ->
                    whisperkmp_full(
                        ctxPtr,
                        p.ptr,
                        if (samples.isEmpty()) null else pinned.addressOf(0),
                        samples.size,
                        callback,
                        ref.asCPointer(),
                    )
                }
            }
        } finally {
            ref.dispose()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Abort any in-flight run, then free on the (single-threaded) dispatcher
        // so the native context is never deleted while whisper_full is executing.
        whisperkmp_cancel(ctxPtr)
        runBlocking(dispatcher) { whisperkmp_free(ctxPtr) }
    }

    @Volatile
    private var closed = false
}
