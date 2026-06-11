package com.kmpile.whisper

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

/**
 * An on-device whisper.cpp transcription context wrapping a loaded GGML model.
 *
 * Audio must be **16 kHz mono PCM** supplied as normalized float samples in `[-1, 1]`.
 * A context is not thread-safe; the [fromFile] factory pins a single-threaded
 * dispatcher by default so calls are serialized. Always [close] when done — or use
 * it as an [AutoCloseable] (`use { ... }`).
 */
public interface WhisperContext : AutoCloseable {

    /** True if the loaded model supports languages other than English. */
    public val isMultilingual: Boolean

    /**
     * Transcribe [samples] (16 kHz mono float PCM), emitting each [Segment] as
     * whisper.cpp finalizes it. The returned [Flow] is cold: collecting it starts
     * a transcription on the context's dispatcher and completes when the audio is
     * fully processed. Cancelling collection aborts the in-flight run.
     */
    public fun transcribe(
        samples: FloatArray,
        params: TranscribeParams = TranscribeParams(),
    ): Flow<Segment>

    override fun close()

    public companion object {
        /**
         * Load a GGML whisper model from [modelPath] and return a ready context.
         *
         * @throws IllegalStateException if the model fails to load.
         */
        public suspend fun fromFile(
            modelPath: String,
            params: ContextParams = ContextParams(),
            dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
        ): WhisperContext = platformCreateContext(modelPath, params, dispatcher)

        /** whisper.cpp + ggml build/system info (backends, SIMD, threads). */
        public fun systemInfo(): String = platformSystemInfo()
    }
}

/** Transcribe to a complete list, suspending until the whole input is processed. */
public suspend fun WhisperContext.transcribeAll(
    samples: FloatArray,
    params: TranscribeParams = TranscribeParams(),
): List<Segment> = transcribe(samples, params).toList()

internal expect suspend fun platformCreateContext(
    modelPath: String,
    params: ContextParams,
    dispatcher: CoroutineDispatcher,
): WhisperContext

internal expect fun platformSystemInfo(): String

/**
 * Bridges the native, blocking "run whisper_full, call me once per finalized
 * segment" call into a cold [Flow]. [start] runs on [dispatcher] and returns
 * whisper.cpp's status code; a non-zero status fails the flow. [cancel] signals
 * the native abort callback so collection can be cut short.
 */
internal fun segmentFlow(
    dispatcher: CoroutineDispatcher,
    start: ((Segment) -> Unit) -> Int,
    cancel: () -> Unit,
): Flow<Segment> = callbackFlow {
    val state = RunState()
    val job = launch(dispatcher) {
        var status = 0
        try {
            ensureActive()
            state.started = true
            status = start { segment -> trySend(segment) }
        } finally {
            state.completed = true
            // A cancelled run aborts mid-flight and reports non-zero; that is
            // not an error from the collector's point of view.
            val failure = if (status != 0 && !state.cancelRequested) {
                IllegalStateException("whisper_full failed with status $status")
            } else {
                null
            }
            channel.close(failure)
        }
    }
    awaitClose {
        state.cancelRequested = true
        // Only signal the native abort flag while the native call is actually
        // in flight: awaitClose also fires after normal completion (and for
        // jobs that never started), and a cancel no run consumes stays sticky
        // on the context and would abort its next run.
        if (state.started && !state.completed) cancel()
        job.cancel()
    }
}.buffer(Channel.UNLIMITED)

private class RunState {
    @Volatile var started: Boolean = false
    @Volatile var completed: Boolean = false
    @Volatile var cancelRequested: Boolean = false
}
