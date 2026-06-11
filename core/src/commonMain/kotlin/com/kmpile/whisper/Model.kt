package com.kmpile.whisper

/** Decoding strategy, mirroring whisper.cpp's `whisper_sampling_strategy`. */
public enum class SamplingStrategy { GREEDY, BEAM_SEARCH }

/** Parameters for loading a model into a [WhisperContext]. */
public data class ContextParams(
    /** Use a GPU backend (Metal/CUDA/Vulkan) when the native build provides one. */
    val useGpu: Boolean = true,
    /** Flash attention — faster and lower-memory; default-on in whisper.cpp 1.8.x. */
    val flashAttn: Boolean = true,
)

/**
 * Voice Activity Detection — added in whisper.cpp 1.8.x. When supplied, whisper
 * runs a VAD pass first and only transcribes detected speech, which speeds up
 * long recordings and drops hallucinated text in silence.
 *
 * Requires a separate VAD GGML model (e.g. `ggml-silero-v5.1.2.bin`) at [modelPath].
 * Defaults mirror `whisper_vad_default_params()`.
 */
public data class VadParams(
    /** Path to a VAD GGML model. Presence of this object is what enables VAD. */
    val modelPath: String,
    /** Speech probability threshold. */
    val threshold: Float = 0.5f,
    /** Minimum duration for a valid speech segment, in milliseconds. */
    val minSpeechDurationMs: Int = 250,
    /** Minimum silence to consider speech ended, in milliseconds. */
    val minSilenceDurationMs: Int = 100,
    /** Max speech segment duration before a forced split, in seconds. */
    val maxSpeechDurationSec: Float = Float.MAX_VALUE,
    /** Padding added before and after each speech segment, in milliseconds. */
    val speechPadMs: Int = 30,
    /** Overlap when copying audio from a speech segment, in seconds. */
    val samplesOverlapSec: Float = 0.1f,
)

/**
 * Per-transcription parameters: a Kotlin-idiomatic subset of `whisper_full_params`.
 * Defaults match a typical English dictation pass.
 */
public data class TranscribeParams(
    /** Spoken language as an ISO-639-1 code (e.g. "en"), or null to auto-detect. */
    val language: String? = "en",
    /** Translate to English instead of transcribing verbatim. */
    val translate: Boolean = false,
    /** Worker threads; <= 0 lets whisper.cpp pick a sensible default. */
    val threads: Int = 0,
    /** Decoding strategy. */
    val strategy: SamplingStrategy = SamplingStrategy.GREEDY,
    /** Beam width when [strategy] is [SamplingStrategy.BEAM_SEARCH]; <= 0 keeps the default. */
    val beamSize: Int = 5,
    /** Initial decoding temperature. */
    val temperature: Float = 0.0f,
    /** Optional prompt to bias the first window's vocabulary. */
    val initialPrompt: String? = null,
    /** Offset into the audio to start at, in milliseconds. */
    val offsetMs: Int = 0,
    /** Max audio duration to process, in milliseconds; 0 = to the end. */
    val durationMs: Int = 0,
    /** Emit one segment for the whole input instead of splitting on timestamps. */
    val singleSegment: Boolean = false,
    /** Enable Voice Activity Detection (whisper.cpp 1.8.x); null disables it. */
    val vad: VadParams? = null,
)

/**
 * A transcribed chunk of audio.
 *
 * @property text segment text as produced by whisper.cpp (may carry a leading space).
 * @property startMs segment start in milliseconds, relative to the start of the samples.
 * @property endMs segment end in milliseconds.
 */
public data class Segment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)
