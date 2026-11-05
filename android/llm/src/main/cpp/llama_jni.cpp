// Pocket Nomad's JNI wrapper around llama.cpp: load a GGUF model, answer one chat
// turn with streamed pieces, free. A port of LlamaRuntime in
// App/Sources/Ask/LanguageModels.swift (same context size, sampler chain and
// prompt trimming), on the CPU. Called from org.nomadpocket.llm.Llama, one call at
// a time on one thread.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "nomadllm"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

// Error codes, matched by LlamaException.Kind in Kotlin.
constexpr int kContextFailed = -2;
constexpr int kDecodeFailed = -3;
constexpr int kTemplateFailed = -4;

void log_to_logcat(ggml_log_level level, const char *text, void *) {
    // llama.cpp is chatty at load time; keep warnings and errors only.
    if (level == GGML_LOG_LEVEL_WARN) __android_log_write(ANDROID_LOG_WARN, TAG, text);
    else if (level == GGML_LOG_LEVEL_ERROR) __android_log_write(ANDROID_LOG_ERROR, TAG, text);
}

std::string to_string(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Java's "modified UTF-8" mangles characters outside the BMP, so the prompt strings
// are taken as UTF-16 and converted here.
std::string to_utf8(JNIEnv *env, jstring s) {
    if (!s) return {};
    const jsize n = env->GetStringLength(s);
    const jchar *u = env->GetStringChars(s, nullptr);
    std::string out;
    out.reserve(n);
    for (jsize i = 0; i < n; i++) {
        uint32_t c = u[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (u[i + 1] - 0xDC00);
            i++;
        }
        if (c < 0x80) {
            out += static_cast<char>(c);
        } else if (c < 0x800) {
            out += static_cast<char>(0xC0 | (c >> 6));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            out += static_cast<char>(0xE0 | (c >> 12));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (c >> 18));
            out += static_cast<char>(0x80 | ((c >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        }
    }
    env->ReleaseStringChars(s, u);
    return out;
}

bool chat_prompt(const llama_model *model, const std::string &system, const std::string &user, std::string &out) {
    const char *tmpl = llama_model_chat_template(model, nullptr);
    llama_chat_message messages[] = {{"system", system.c_str()}, {"user", user.c_str()}};
    std::vector<char> buf((system.size() + user.size()) * 2 + 512);
    int n = llama_chat_apply_template(tmpl, messages, 2, true, buf.data(), static_cast<int32_t>(buf.size()));
    if (n > static_cast<int>(buf.size())) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, messages, 2, true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (n <= 0) return false;
    out.assign(buf.data(), n);
    return true;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    std::vector<llama_token> tokens(text.size() + 16);
    int n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), true, true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), true, true);
    }
    tokens.resize(std::max(0, n));
    return tokens;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_org_nomadpocket_llm_Llama_nativeInit(JNIEnv *, jclass) {
    llama_log_set(log_to_logcat, nullptr);
    llama_backend_init();
}

JNIEXPORT jstring JNICALL Java_org_nomadpocket_llm_Llama_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL Java_org_nomadpocket_llm_Llama_nativeLoad(JNIEnv *env, jclass, jstring path) {
    const std::string p = to_string(env, path);
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;  // CPU only: no GPU backend is built for Android yet
    llama_model *model = llama_model_load_from_file(p.c_str(), params);
    if (!model) LOGW("couldn't load %s", p.c_str());
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL Java_org_nomadpocket_llm_Llama_nativeFree(JNIEnv *, jclass, jlong handle) {
    if (handle) llama_model_free(reinterpret_cast<llama_model *>(handle));
}

// Answers one turn, calling sink.onPiece(byte[]) with each token's UTF-8 bytes
// (which may end mid-character). Returns the number of tokens generated, or a
// negative error code. Stops early when onPiece returns false.
JNIEXPORT jint JNICALL Java_org_nomadpocket_llm_Llama_nativeGenerate(
        JNIEnv *env, jclass, jlong handle, jstring jsystem, jstring juser, jint max_tokens, jint n_ctx,
        jint threads, jint seed, jobject sink) {
    auto *model = reinterpret_cast<llama_model *>(handle);
    if (!model) return kContextFailed;
    const llama_vocab *vocab = llama_model_get_vocab(model);

    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_piece = env->GetMethodID(sink_class, "onPiece", "([B)Z");
    env->DeleteLocalRef(sink_class);
    if (!on_piece) return kDecodeFailed;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = 512;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) return kContextFailed;

    std::string prompt;
    if (!chat_prompt(model, to_utf8(env, jsystem), to_utf8(env, juser), prompt)) {
        llama_free(ctx);
        return kTemplateFailed;
    }
    std::vector<llama_token> tokens = tokenize(vocab, prompt);
    // Keep room for the answer; drop the start of an over-long prompt, as on iOS.
    const int room = n_ctx - max_tokens - 8;
    if (room <= 0) {
        llama_free(ctx);
        return kContextFailed;
    }
    if (static_cast<int>(tokens.size()) > room) tokens.erase(tokens.begin(), tokens.end() - room);

    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(0.3f));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(static_cast<uint32_t>(seed)));

    int result = 0;
    // Feed the prompt in batch-sized pieces.
    for (size_t start = 0; start < tokens.size(); start += cparams.n_batch) {
        const size_t count = std::min<size_t>(cparams.n_batch, tokens.size() - start);
        if (llama_decode(ctx, llama_batch_get_one(tokens.data() + start, static_cast<int32_t>(count))) != 0) {
            result = kDecodeFailed;
            break;
        }
    }

    char piece[256];
    for (int i = 0; result >= 0 && i < max_tokens; i++) {
        llama_token token = llama_sampler_sample(chain, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        const int n = llama_token_to_piece(vocab, token, piece, sizeof piece, 0, false);
        result++;
        if (n > 0) {
            jbyteArray bytes = env->NewByteArray(n);
            env->SetByteArrayRegion(bytes, 0, n, reinterpret_cast<const jbyte *>(piece));
            const jboolean more = env->CallBooleanMethod(sink, on_piece, bytes);
            env->DeleteLocalRef(bytes);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
            if (!more) break;
        }
        if (llama_decode(ctx, llama_batch_get_one(&token, 1)) != 0) break;
    }

    llama_sampler_free(chain);
    llama_free(ctx);
    return result;
}

}  // extern "C"
