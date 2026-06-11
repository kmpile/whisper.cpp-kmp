#ifndef WHISPERKMP_TRANSCRIBER_H
#define WHISPERKMP_TRANSCRIBER_H

#include <atomic>
#include <functional>

#include "whisper.h"
#include "whisperkmp.h"

// Callback the C++ core invokes per finalized segment: (text, t0_ms, t1_ms).
using SegmentSink = std::function<void(const char*, int64_t, int64_t)>;

// Owns a whisper_context and drives whisper_full, translating whisper.cpp's
// new-segment + abort callbacks into a SegmentSink and a cancellation flag.
class Transcriber {
public:
    explicit Transcriber(whisper_context* ctx) : ctx_(ctx) {}
    ~Transcriber();

    Transcriber(const Transcriber&) = delete;
    Transcriber& operator=(const Transcriber&) = delete;

    bool valid() const { return ctx_ != nullptr; }
    bool is_multilingual() const;

    // Blocking. Returns whisper_full's status code (0 = success).
    int full(const whisperkmp_params& params,
             const float* samples,
             int n_samples,
             const SegmentSink& sink);

    void cancel() { cancelled_.store(true); }

private:
    whisper_context* ctx_ = nullptr;
    std::atomic<bool> cancelled_{false};
};

#endif // WHISPERKMP_TRANSCRIBER_H
