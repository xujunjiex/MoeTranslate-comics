// hymt2_bridge.cpp — Hy-MT2 1.25-bit 本地翻译 JNI 桥
// 引擎: llama.cpp f8b355a9e (build 9521)，链接预编译 libllama.so
#include <jni.h>
#include <android/log.h>
#include <chrono>
#include <exception>
#include <mutex>
#include <string>
#include <vector>
#include <algorithm>
#include <cstring>
#include "llama.h"

#define TAG "HyMT2Bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 串行化所有解码调用，防止并发访问同一 context（漫画模式多气泡并行翻译时排队）
static std::mutex g_mutex;

// 诊断用：相对毫秒时间
static long long now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

// 把 C++ 异常转成 Java 异常抛出，避免 JNI 边界未捕获异常直接 abort 进程
static void throw_java_exception(JNIEnv* env, const char* msg) {
    jclass re = env->FindClass("java/lang/RuntimeException");
    if (re != nullptr) env->ThrowNew(re, msg);
}

struct HyMt2Handle {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
};

extern "C" JNIEXPORT jlong JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeInit(
    JNIEnv* env, jclass, jstring jModelPath, jint nThreads, jint nCtx) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        const char* path = env->GetStringUTFChars(jModelPath, nullptr);
        if (path == nullptr) return 0;

        // 防御性钳位：非法入参不进入引擎
        if (nCtx < 512) nCtx = 512;
        if (nThreads < 1) nThreads = 1;

        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        // ⚠️ 必须 use_mmap=false：mmap 的权重页会被系统在内存压力下回收，导致解码时反复从存储读 440MB
        //    （实测 15-17s/次）。直接读入堆内存，加载慢一次但之后解码稳定快。
        mp.use_mmap = false;
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

        // 预热：解码一个 token 跑一次全前向，把 440MB 权重页调入内存（首次解码会 mmap 缺页 ~15s）
        // 把这段耗时并入"模型加载中…"，避免首次翻译时才卡 15 秒
        const long long t_warm = now_ms();
        llama_token warm_tokens[4];
        const int32_t n_warm = llama_tokenize(h->vocab, "a", 1, warm_tokens, 4, false, false);
        if (n_warm > 0) {
            llama_batch wb = llama_batch_init(n_warm, 0, 1);
            wb.n_tokens = n_warm;
            for (int32_t i = 0; i < n_warm; ++i) {
                wb.token[i]     = warm_tokens[i];
                wb.pos[i]       = i;
                wb.n_seq_id[i]  = 1;
                wb.seq_id[i][0] = 0;
            }
            wb.logits[n_warm - 1] = true;
            if (llama_decode(h->ctx, wb) == 0) {
                LOGI("warmup decode ok (%lld ms)", now_ms() - t_warm);
            } else {
                LOGW("warmup decode failed");
            }
            llama_batch_free(wb);
            llama_memory_clear(llama_get_memory(h->ctx), false);
        }

        LOGI("model loaded: nCtx=%d nThreads=%d", nCtx, nThreads);
        return reinterpret_cast<jlong>(h);
    } catch (const std::exception& e) {
        LOGE("nativeInit exception: %s", e.what());
        throw_java_exception(env, e.what());
        return 0;
    } catch (...) {
        LOGE("nativeInit unknown exception");
        throw_java_exception(env, "unknown native exception");
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeTranslate(
    JNIEnv* env, jclass, jlong jHandle, jstring jPrompt,
    jfloat temperature, jfloat topP, jint topK,
    jfloat repetitionPenalty, jint maxTokens) {
    try {
        const long long t_entry = now_ms();
        LOGI("translate: ENTER handle=%lld temp=%.3f top_p=%.3f top_k=%d rep=%.3f max_tokens=%d",
             jHandle, temperature, topP, topK, repetitionPenalty, maxTokens);

        std::lock_guard<std::mutex> lock(g_mutex);
        LOGI("translate: mutex acquired (+%lld ms)", now_ms() - t_entry);

        auto* h = reinterpret_cast<HyMt2Handle*>(jHandle);
        if (h == nullptr || h->ctx == nullptr) return env->NewStringUTF("");

        // 每次翻译前清空 KV 缓存/内存，防止上下文跨调用累积（prompt 污染 + 超 n_ctx）
        llama_memory_clear(llama_get_memory(h->ctx), false);

        const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
        if (prompt == nullptr) return env->NewStringUTF("");
        const size_t text_len = strlen(prompt);
        LOGI("translate: prompt chars=%zu preview=[%.100s]", text_len, prompt);
        // 把含 {source_text} 的尾部单独打出来，确认识别文本确实进了 prompt
        const size_t tail_len = std::min<size_t>(text_len, 80);
        LOGI("translate: prompt tail=[%s]", prompt + (text_len - tail_len));

        // 分词（缓冲按字符数 + 余量；若缓冲不足返回负的"需要数量"，则按需扩容重试）
        // ⚠️ f8b355a9e 版 llama_vocab::tokenize 内部是 std::string(text, text_len)，不处理 text_len=-1，
        //    传 -1 会抛 std::length_error 导致进程 abort。必须传真实字节长度。
        int32_t n_tokens = 0;
        std::vector<llama_token> tokens(text_len + 8);
        for (int attempt = 0; attempt < 2; ++attempt) {
            n_tokens = llama_tokenize(
                h->vocab, prompt, static_cast<int32_t>(text_len),
                tokens.data(), static_cast<int32_t>(tokens.size()), false, false);
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
        if (n_tokens < 0) {
            LOGE("translate: tokenize failed (needed %d, buffer %zu)", -n_tokens, tokens.size());
            return env->NewStringUTF("");
        }
        tokens.resize(n_tokens);

        // 打印前几个 prompt token id（检查是否有 BOS / 特殊 token）
        {
            std::string ids;
            for (int k = 0; k < std::min<int>(n_tokens, 10); ++k) {
                if (k) ids += ",";
                ids += std::to_string(tokens[k]);
            }
            LOGI("translate: prompt tokens=%d, first=[%s]", n_tokens, ids.c_str());
        }

        const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(h->ctx));
        const int max_tokens = std::max(0, std::min(static_cast<int>(maxTokens),
                                                    static_cast<int>(n_ctx - n_tokens)));
        if (max_tokens == 0) {
            LOGE("translate: prompt longer than context (%d)", n_ctx);
            return env->NewStringUTF("");
        }

        // 每次按当前采样参数重建采样链
        llama_sampler_chain_params sp = llama_sampler_chain_default_params();
        llama_sampler* smpl = llama_sampler_chain_init(sp);
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, repetitionPenalty, 0.0f, 0.0f));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        // prompt 一次性解码（用显式位置数组，与 llama-bench 一致）
        // ⚠️ 不要用 llama_batch_get_one（pos=nullptr 自动跟踪）：f8b355a9e 实测单 token 解码慢 ~175×
        LOGI("translate: decoding prompt (%d tokens)...", n_tokens);
        llama_batch batch = llama_batch_init(n_tokens, 0, 1);
        if (batch.token == nullptr) {
            LOGE("translate: batch alloc failed");
            llama_sampler_free(smpl);
            return env->NewStringUTF("");
        }
        batch.n_tokens = n_tokens;
        for (int32_t i = 0; i < n_tokens; ++i) {
            batch.token[i]    = tokens[i];
            batch.pos[i]      = i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
        }
        // ⚠️ llama_batch_init 的 logits 数组默认全 false：必须为"需要采样"的最后一个 token 置 true，
        //    否则模型不算 logits，llama_sampler_sample 会 ggml_abort 崩溃。
        batch.logits[n_tokens - 1] = true;
        const long long t_decode0 = now_ms();
        if (llama_decode(h->ctx, batch) != 0) {
            LOGE("translate: prompt decode failed");
            llama_batch_free(batch);
            llama_sampler_free(smpl);
            return env->NewStringUTF("");
        }
        LOGI("translate: prompt decoded (+%lld ms), starting generation", now_ms() - t_decode0);

        std::string result;
        char piece_buf[512];
        int32_t n_cur = n_tokens;
        const long long t_gen0 = now_ms();
        bool hit_eog = false;
        int generated = 0;
        for (int i = 0; i < max_tokens; ++i) {
            if ((i % 8) == 0) {
                LOGI("translate: gen progress token %d/%d (+%lld ms)", i, max_tokens, now_ms() - t_gen0);
            }
            const llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
            llama_sampler_accept(smpl, id);
            generated = i + 1;
            if (llama_vocab_is_eog(h->vocab, id)) {
                LOGI("translate: EOG at token %d (+%lld ms), token_id=%d", i, now_ms() - t_gen0, id);
                hit_eog = true;
                break;
            }
            const int32_t n_piece = llama_token_to_piece(
                h->vocab, id, piece_buf, static_cast<int32_t>(sizeof(piece_buf)), 0, false);
            if (n_piece > 0) result.append(piece_buf, n_piece);
            // 解码新 token（显式位置；logits 置 true 供下一轮采样）
            batch.n_tokens    = 1;
            batch.token[0]    = id;
            batch.pos[0]      = n_cur++;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = 0;
            batch.logits[0]   = true;
            if (llama_decode(h->ctx, batch) != 0) { LOGE("translate: decode failed at step %d", i); break; }
        }
        llama_batch_free(batch);
        llama_sampler_free(smpl);
        LOGI("translate: DONE generated=%d/%d eog=%d gen_elapsed=%lld ms total_elapsed=%lld ms result_len=%zu",
             generated, max_tokens, hit_eog, now_ms() - t_gen0, now_ms() - t_entry, result.size());
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception& e) {
        LOGE("translate: C++ exception: %s", e.what());
        throw_java_exception(env, e.what());
        return nullptr;
    } catch (...) {
        LOGE("translate: unknown C++ exception");
        throw_java_exception(env, "unknown native exception");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeRelease(JNIEnv*, jclass, jlong jHandle) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto* h = reinterpret_cast<HyMt2Handle*>(jHandle);
        if (h == nullptr) return;
        if (h->ctx != nullptr) llama_free(h->ctx);
        if (h->model != nullptr) llama_model_free(h->model);
        delete h;
        LOGI("model released");
    } catch (const std::exception& e) {
        LOGE("nativeRelease exception: %s", e.what());
    } catch (...) {
        LOGE("nativeRelease unknown exception");
    }
}
