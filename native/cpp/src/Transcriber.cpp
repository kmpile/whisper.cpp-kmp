#include "Transcriber.h"

Transcriber::~Transcriber() {
    if (ctx_) {
        whisper_free(ctx_);
        ctx_ = nullptr;
    }
}

bool Transcriber::is_multilingual() const {
    return ctx_ && whisper_is_multilingual(ctx_) != 0;
}

namespace {

// Bundled into whisper.cpp's user_data slots for the duration of one full() call.
struct CallbackBridge {
    const SegmentSink* sink;
    std::atomic<bool>* cancelled;
};

void on_new_segment(whisper_context* ctx, whisper_state* /*state*/, int n_new, void* user_data) {
    auto* bridge = static_cast<CallbackBridge*>(user_data);
    const int total = whisper_full_n_segments(ctx);
    for (int i = total - n_new; i < total; ++i) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        // whisper timestamps are in centiseconds (10 ms units) -> milliseconds.
        const int64_t t0_ms = whisper_full_get_segment_t0(ctx, i) * 10;
        const int64_t t1_ms = whisper_full_get_segment_t1(ctx, i) * 10;
        (*bridge->sink)(text ? text : "", t0_ms, t1_ms);
    }
}

// ggml_abort_callback: returning true aborts the computation.
bool on_abort(void* user_data) {
    auto* bridge = static_cast<CallbackBridge*>(user_data);
    return bridge->cancelled->load();
}

} // namespace

int Transcriber::full(const whisperkmp_params& p,
                      const float* samples,
                      int n_samples,
                      const SegmentSink& sink) {
    if (!ctx_) return -1;

    const auto strategy = (p.strategy == WHISPERKMP_BEAM_SEARCH)
        ? WHISPER_SAMPLING_BEAM_SEARCH
        : WHISPER_SAMPLING_GREEDY;
    whisper_full_params wp = whisper_full_default_params(strategy);

    // We stream via the callback; keep whisper.cpp off stdout.
    wp.print_realtime   = false;
    wp.print_progress   = false;
    wp.print_timestamps = false;
    wp.print_special    = false;

    wp.translate       = p.translate;
    wp.language        = p.language;        // null => auto-detect
    // Never set detect_language: in whisper.cpp it means "detect, then STOP
    // without transcribing". A null language alone triggers auto-detection.
    wp.detect_language = false;
    wp.single_segment  = p.single_segment;
    wp.offset_ms       = p.offset_ms;
    wp.duration_ms     = p.duration_ms;
    wp.temperature     = p.temperature;
    wp.initial_prompt  = p.initial_prompt;
    if (p.n_threads > 0) wp.n_threads = p.n_threads;
    if (p.beam_size > 0) wp.beam_search.beam_size = p.beam_size;

    // Voice Activity Detection (whisper.cpp 1.8.x).
    if (p.vad_model_path != nullptr) {
        wp.vad            = true;
        wp.vad_model_path = p.vad_model_path;
        whisper_vad_params vp = whisper_vad_default_params();
        vp.threshold               = p.vad_threshold;
        vp.min_speech_duration_ms  = p.vad_min_speech_duration_ms;
        vp.min_silence_duration_ms = p.vad_min_silence_duration_ms;
        vp.max_speech_duration_s   = p.vad_max_speech_duration_s;
        vp.speech_pad_ms           = p.vad_speech_pad_ms;
        vp.samples_overlap         = p.vad_samples_overlap;
        wp.vad_params = vp;
    }

    CallbackBridge bridge{ &sink, &cancelled_ };
    wp.new_segment_callback           = on_new_segment;
    wp.new_segment_callback_user_data = &bridge;
    wp.abort_callback                 = on_abort;
    wp.abort_callback_user_data       = &bridge;

    const int rc = whisper_full(ctx_, wp, samples, n_samples);
    // Clear the flag AFTER the run, not before it: a cancel() that lands between
    // the Kotlin flow launching this run and whisper_full starting must stay
    // sticky so the run aborts at its first checkpoint. The Kotlin side only
    // calls cancel() for runs that have not completed, so a cleared flag here
    // cannot erase a cancel meant for a later run.
    cancelled_.store(false);
    return rc;
}
