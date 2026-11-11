#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "whisper.h"

#define TAG "WhisperJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_rs_ocportable_Whisper_nativeInit(JNIEnv *env, jobject, jstring model_path) {
    if (model_path == nullptr) return 0;
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;
    whisper_context *context = whisper_init_from_file_with_params(path, whisper_context_default_params());
    env->ReleaseStringUTFChars(model_path, path);
    if (context == nullptr) {
        LOGI("whisper_init_from_file_with_params failed");
        return 0;
    }
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rs_ocportable_Whisper_nativeFree(JNIEnv *, jobject, jlong context_ptr) {
    if (context_ptr != 0) whisper_free(reinterpret_cast<whisper_context *>(context_ptr));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rs_ocportable_Whisper_nativeTranscribe(
    JNIEnv *env,
    jobject,
    jlong context_ptr,
    jshortArray pcm,
    jint threads
) {
    if (context_ptr == 0 || pcm == nullptr) return env->NewStringUTF("");

    whisper_context *context = reinterpret_cast<whisper_context *>(context_ptr);
    const jsize length = env->GetArrayLength(pcm);
    if (length <= 0) return env->NewStringUTF("");

    jshort *samples = env->GetShortArrayElements(pcm, nullptr);
    if (samples == nullptr) return env->NewStringUTF("");
    std::vector<float> audio(static_cast<size_t>(length));
    for (jsize i = 0; i < length; ++i) {
        audio[static_cast<size_t>(i)] = static_cast<float>(samples[i]) / 32768.0f;
    }
    env->ReleaseShortArrayElements(pcm, samples, JNI_ABORT);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "en";
    params.n_threads = threads > 0 ? threads : 2;
    params.no_context = true;
    params.single_segment = false;

    if (whisper_full(context, params, audio.data(), static_cast<int>(audio.size())) != 0) {
        LOGI("whisper_full failed");
        return env->NewStringUTF("");
    }

    std::string result;
    const int segments = whisper_full_n_segments(context);
    for (int i = 0; i < segments; ++i) {
        const char *text = whisper_full_get_segment_text(context, i);
        if (text != nullptr) result += text;
    }
    return env->NewStringUTF(result.c_str());
}
