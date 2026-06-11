#include <jni.h>

#include <cstring>

#include "whisperkmp.h"

#ifdef __ANDROID__
#include <android/log.h>
#define WKMP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "WHISPERKMP", __VA_ARGS__)
#else
#include <cstdio>
#define WKMP_LOGE(...) do { std::fprintf(stderr, "[WHISPERKMP] "); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

namespace {

// Lives on the stack for one nativeFull call; whisper_full + its callbacks run
// synchronously on this same thread, so the captured JNIEnv* stays valid.
struct JniSegmentCtx {
    JNIEnv*   env;
    jobject   callback;
    jmethodID on_segment;
    int64_t   ctx_ptr;
    bool      failed;
};

// Segment text is passed as raw UTF-8 bytes: whisper emits genuine UTF-8
// (4-byte sequences, possibly split mid-character by BPE), which NewStringUTF's
// Modified UTF-8 cannot represent. Kotlin decodes with replacement handling.
void segment_cb(const char* text, int64_t t0_ms, int64_t t1_ms, void* user_data) {
    auto* c = static_cast<JniSegmentCtx*>(user_data);
    if (c->failed) return;  // a callback threw; no JNI calls with an exception pending

    const char* s = text ? text : "";
    const jsize len = static_cast<jsize>(std::strlen(s));
    jbyteArray bytes = c->env->NewByteArray(len);
    if (!bytes) {  // OOM: an exception is pending
        c->failed = true;
        whisperkmp_cancel(c->ctx_ptr);
        return;
    }
    c->env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte*>(s));
    c->env->CallVoidMethod(c->callback, c->on_segment, bytes,
                           static_cast<jlong>(t0_ms), static_cast<jlong>(t1_ms));
    c->env->DeleteLocalRef(bytes);
    if (c->env->ExceptionCheck()) {
        // Leave the exception pending for the JVM; abort the transcription and
        // make sure we never re-enter JNI from this run.
        c->failed = true;
        whisperkmp_cancel(c->ctx_ptr);
    }
}

const char* utf_or_null(JNIEnv* env, jstring s) {
    return s ? env->GetStringUTFChars(s, nullptr) : nullptr;
}

void release_utf(JNIEnv* env, jstring s, const char* chars) {
    if (s && chars) env->ReleaseStringUTFChars(s, chars);
}

} // namespace

extern "C"
JNIEXPORT jlong JNICALL
Java_com_kmpile_whisper_WhisperKmpNativeKt_nativeInitFromFile(
        JNIEnv* env, jclass, jstring model_path, jboolean use_gpu, jboolean flash_attn) {
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    int64_t ptr = whisperkmp_init_from_file(path, use_gpu, flash_attn);
    env->ReleaseStringUTFChars(model_path, path);
    return static_cast<jlong>(ptr);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_kmpile_whisper_WhisperKmpNativeKt_nativeIsMultilingual(
        JNIEnv*, jclass, jlong ctx_ptr) {
    return whisperkmp_is_multilingual(ctx_ptr) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_kmpile_whisper_WhisperKmpNativeKt_nativeFull(
        JNIEnv* env, jclass, jlong ctx_ptr,
        jfloatArray samples,
        jstring language, jboolean translate, jint threads, jint strategy, jint beam_size,
        jfloat temperature, jstring initial_prompt, jint offset_ms, jint duration_ms,
        jboolean single_segment,
        jstring vad_model_path, jfloat vad_threshold, jint vad_min_speech_ms,
        jint vad_min_silence_ms, jfloat vad_max_speech_s, jint vad_speech_pad_ms,
        jfloat vad_samples_overlap,
        jobject callback) {

    jclass cb_class = env->GetObjectClass(callback);
    jmethodID on_segment = env->GetMethodID(cb_class, "onSegment", "([BJJ)V");
    if (!on_segment) {
        WKMP_LOGE("SegmentCallback.onSegment(byte[],long,long) not found");
        return -1;
    }

    const char* lang   = utf_or_null(env, language);
    const char* prompt = utf_or_null(env, initial_prompt);
    const char* vad    = utf_or_null(env, vad_model_path);

    whisperkmp_params p{};
    p.language       = lang;
    p.translate      = translate;
    p.n_threads      = threads;
    p.strategy       = strategy;
    p.beam_size      = beam_size;
    p.temperature    = temperature;
    p.initial_prompt = prompt;
    p.offset_ms      = offset_ms;
    p.duration_ms    = duration_ms;
    p.single_segment = single_segment;
    p.vad_model_path                 = vad;
    p.vad_threshold                  = vad_threshold;
    p.vad_min_speech_duration_ms     = vad_min_speech_ms;
    p.vad_min_silence_duration_ms    = vad_min_silence_ms;
    p.vad_max_speech_duration_s      = vad_max_speech_s;
    p.vad_speech_pad_ms              = vad_speech_pad_ms;
    p.vad_samples_overlap            = vad_samples_overlap;

    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    const jsize n = env->GetArrayLength(samples);

    JniSegmentCtx sctx{ env, callback, on_segment, static_cast<int64_t>(ctx_ptr), false };
    int rc = whisperkmp_full(ctx_ptr, &p,
                             reinterpret_cast<const float*>(data), static_cast<int>(n),
                             segment_cb, &sctx);

    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    release_utf(env, language, lang);
    release_utf(env, initial_prompt, prompt);
    release_utf(env, vad_model_path, vad);
    return rc;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kmpile_whisper_WhisperKmpNativeKt_nativeCancel(
        JNIEnv*, jclass, jlong ctx_ptr) {
    whisperkmp_cancel(ctx_ptr);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kmpile_whisper_WhisperKmpNativeKt_nativeFree(
        JNIEnv*, jclass, jlong ctx_ptr) {
    whisperkmp_free(ctx_ptr);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_kmpile_whisper_WhisperKmpNativeKt_nativeSystemInfo(
        JNIEnv* env, jclass) {
    return env->NewStringUTF(whisperkmp_system_info());
}
