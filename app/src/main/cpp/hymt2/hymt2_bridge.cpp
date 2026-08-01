// hymt2_bridge.cpp — Hy-MT2 1.25-bit 本地翻译 JNI 桥
// 引擎: llama.cpp f8b355a9e (build 9521)，链接预编译 libllama.so
#include <jni.h>
#include <android/log.h>
#include <mutex>
#include <string>
#include <vector>
#include <algorithm>
#include <cstring>
#include "llama.h"

#define TAG "HyMT2Bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 串行化所有解码调用，防止并发访问同一 context（漫画模式多气泡并行翻译时排队）
static std::mutex g_mutex;

struct HyMt2Handle {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
};

extern "C" JNIEXPORT jlong JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeInit(
    JNIEnv* env, jclass, jstring jModelPath, jint nThreads, jint nCtx) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const char* path = env->GetStringUTFChars(jModelPath, nullptr);
    if (path == nullptr) return 0;

    // 防御性钳位：非法入参不进入引擎
    if (nCtx < 512) nCtx = 512;
    if (nThreads < 1) nThreads = 1;

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model* model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jModelPath, path);
    if (model == nullptr) { LOGE("llama_model_load_from_file failed"); return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nCtx;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    cp.n_batch = std::min<int>(nCtx, 2048);
    llama_context* ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) { LOGE("llama_init_from_model failed"); llama_model_free(model); return 0; }

    auto* h = new HyMt2Handle();
    h->model = model;
    h->ctx = ctx;
    h->vocab = llama_model_get_vocab(model);
    LOGI("model loaded: nCtx=%d nThreads=%d", nCtx, nThreads);
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT jstring JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeTranslate(
    JNIEnv* env, jclass, jlong jHandle, jstring jPrompt,
    jfloat temperature, jfloat topP, jint topK,
    jfloat repetitionPenalty, jint maxTokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto* h = reinterpret_cast<HyMt2Handle*>(jHandle);
    if (h == nullptr || h->ctx == nullptr) return env->NewStringUTF("");

    // 每次翻译前清空 KV 缓存/内存，防止上下文跨调用累积（prompt 污染 + 超 n_ctx）
    llama_memory_clear(llama_get_memory(h->ctx), false);

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    if (prompt == nullptr) return env->NewStringUTF("");
    const size_t text_len = strlen(prompt);

    // 分词（缓冲按字符数 + 余量；若缓冲不足返回负的"需要数量"，则按需扩容重试）
    int32_t n_tokens = 0;
    std::vector<llama_token> tokens(text_len + 8);
    for (int attempt = 0; attempt < 2; ++attempt) {
        n_tokens = llama_tokenize(
            h->vocab, prompt, -1, tokens.data(), static_cast<int32_t>(tokens.size()), false, false);
        if (n_tokens < 0) {
            // 返回值的绝对值是"本应写入的 token 数"，扩容后重试一次
            const int32_t needed = -n_tokens;
            if (needed <= static_cast<int32_t>(tokens.size())) break;
            tokens.resize(static_cast<size_t>(needed) + 8);
            continue;
        }
        break;
    }
    env->ReleaseStringUTFChars(jPrompt, prompt);
    if (n_tokens < 0) { LOGE("tokenize failed (needed %d, buffer %zu)", -n_tokens, tokens.size()); return env->NewStringUTF(""); }
    tokens.resize(n_tokens);

    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(h->ctx));
    const int max_tokens = std::max(0, std::min(static_cast<int>(maxTokens),
                                                static_cast<int>(n_ctx - n_tokens)));
    if (max_tokens == 0) { LOGE("prompt longer than context (%d)", n_ctx); return env->NewStringUTF(""); }

    // 每次按当前采样参数重建采样链
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, repetitionPenalty, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // prompt 一次性解码（f8b355a9e 版 llama_batch_get_one 只取 tokens+n_tokens，位置由 llama_decode 自动跟踪）
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("prompt decode failed"); llama_sampler_free(smpl); return env->NewStringUTF("");
    }

    std::string result;
    char piece_buf[512];
    for (int i = 0; i < max_tokens; ++i) {
        const llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
        llama_sampler_accept(smpl, id);
        if (llama_vocab_is_eog(h->vocab, id)) break;
        const int32_t n_piece = llama_token_to_piece(
            h->vocab, id, piece_buf, static_cast<int32_t>(sizeof(piece_buf)), 0, false);
        if (n_piece > 0) result.append(piece_buf, n_piece);
        // 解码新 token 供下一步采样（位置自动跟踪）
        llama_token next[1] = { id };
        llama_batch nb = llama_batch_get_one(next, 1);
        if (llama_decode(h->ctx, nb) != 0) { LOGE("decode failed at step %d", i); break; }
    }
    llama_sampler_free(smpl);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeRelease(JNIEnv*, jclass, jlong jHandle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto* h = reinterpret_cast<HyMt2Handle*>(jHandle);
    if (h == nullptr) return;
    if (h->ctx != nullptr) llama_free(h->ctx);
    if (h->model != nullptr) llama_model_free(h->model);
    delete h;
}
