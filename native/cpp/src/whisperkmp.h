#ifndef WHISPERKMP_H
#define WHISPERKMP_H

// Minimal C ABI over whisper.cpp for the Kotlin Multiplatform binding.
// Deliberately self-contained (no whisper.h include) so Kotlin/Native cinterop
// only has to parse this one small header.

#if defined(_WIN32)
#  define WHISPERKMP_API
#else
#  define WHISPERKMP_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stdint.h>

enum whisperkmp_strategy {
    WHISPERKMP_GREEDY = 0,
    WHISPERKMP_BEAM_SEARCH = 1,
};

// Flat parameter block for one transcription pass. A null `language` auto-detects.
// A null `vad_model_path` disables Voice Activity Detection (whisper.cpp 1.8.x).
struct whisperkmp_params {
    const char* language;        // ISO-639-1, or null to auto-detect
    bool        translate;
    int         n_threads;       // <= 0 => whisper.cpp default
    int         strategy;        // enum whisperkmp_strategy
    int         beam_size;       // <= 0 keeps whisper.cpp's default
    float       temperature;
    const char* initial_prompt;  // may be null
    int         offset_ms;
    int         duration_ms;
    bool        single_segment;

    // VAD (added in whisper.cpp 1.8.x). vad_model_path != null enables it.
    const char* vad_model_path;
    float       vad_threshold;
    int         vad_min_speech_duration_ms;
    int         vad_min_silence_duration_ms;
    float       vad_max_speech_duration_s;
    int         vad_speech_pad_ms;
    float       vad_samples_overlap;
};

// Invoked once per finalized segment. t0_ms / t1_ms are milliseconds.
typedef void (*whisperkmp_segment_callback)(const char* text, int64_t t0_ms, int64_t t1_ms, void* user_data);

// Loads a GGML model. Returns an opaque context handle, or 0 on failure.
WHISPERKMP_API int64_t whisperkmp_init_from_file(const char* model_path, bool use_gpu, bool flash_attn);

WHISPERKMP_API bool whisperkmp_is_multilingual(int64_t ctx_ptr);

// Runs whisper_full over 16 kHz mono float PCM, streaming segments via `callback`.
// Returns whisper.cpp's status code (0 = success).
WHISPERKMP_API int whisperkmp_full(int64_t ctx_ptr,
                                   const struct whisperkmp_params* params,
                                   const float* samples,
                                   int n_samples,
                                   whisperkmp_segment_callback callback,
                                   void* user_data);

// Signals the in-flight whisperkmp_full to abort at the next checkpoint.
WHISPERKMP_API void whisperkmp_cancel(int64_t ctx_ptr);

WHISPERKMP_API void whisperkmp_free(int64_t ctx_ptr);

WHISPERKMP_API const char* whisperkmp_system_info(void);

#ifdef __cplusplus
}
#endif

#endif // WHISPERKMP_H
