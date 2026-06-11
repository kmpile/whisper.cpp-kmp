#include "whisperkmp.h"

#include "Transcriber.h"
#include "whisper.h"

extern "C" int64_t whisperkmp_init_from_file(const char* model_path, bool use_gpu, bool flash_attn) {
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu    = use_gpu;
    cparams.flash_attn = flash_attn;

    whisper_context* ctx = whisper_init_from_file_with_params(model_path, cparams);
    if (!ctx) return 0;
    return reinterpret_cast<int64_t>(new Transcriber(ctx));
}

extern "C" bool whisperkmp_is_multilingual(int64_t ctx_ptr) {
    auto* t = reinterpret_cast<Transcriber*>(ctx_ptr);
    return t && t->is_multilingual();
}

extern "C" int whisperkmp_full(int64_t ctx_ptr,
                               const whisperkmp_params* params,
                               const float* samples,
                               int n_samples,
                               whisperkmp_segment_callback callback,
                               void* user_data) {
    auto* t = reinterpret_cast<Transcriber*>(ctx_ptr);
    if (!t || !params) return -1;

    SegmentSink sink = [callback, user_data](const char* text, int64_t t0_ms, int64_t t1_ms) {
        if (callback) callback(text, t0_ms, t1_ms, user_data);
    };
    return t->full(*params, samples, n_samples, sink);
}

extern "C" void whisperkmp_cancel(int64_t ctx_ptr) {
    if (auto* t = reinterpret_cast<Transcriber*>(ctx_ptr)) t->cancel();
}

extern "C" void whisperkmp_free(int64_t ctx_ptr) {
    delete reinterpret_cast<Transcriber*>(ctx_ptr);
}

extern "C" const char* whisperkmp_system_info(void) {
    return whisper_print_system_info();
}
