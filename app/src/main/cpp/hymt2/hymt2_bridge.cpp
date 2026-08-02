// hymt2_bridge.cpp — Hy-MT2 1.25-bit 本地翻译 JNI 桥
// 引擎: llama.cpp f8b355a9e (build 9521)，链接预编译 libllama.so
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <exception>
#include <mutex>
#include <string>
#include <thread>
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

// logcat 单条日志上限约 1023 字符；超长字符串分块输出，避免被截断（用于完整打印 prompt/结果/token 序列）
static void log_chunked(const char* tag, const char* s, size_t len) {
    constexpr size_t CHUNK = 900;
    size_t off = 0;
    while (off < len) {
        size_t n = std::min(CHUNK, len - off);
        LOGI("%s%.*s", tag, static_cast<int>(n), s + off);
        off += n;
    }
}

// 把 C++ 异常转成 Java 异常抛出，避免 JNI 边界未捕获异常直接 abort 进程
static void throw_java_exception(JNIEnv* env, const char* msg) {
    jclass re = env->FindClass("java/lang/RuntimeException");
    if (re != nullptr) env->ThrowNew(re, msg);
}

// NewStringUTF 在 CheckJNI 下遇到非法 modified UTF-8 会 abort（模型特殊 token 的片段可能是非法字节）。
// 这里构造一份干净的 UTF-8：丢弃非法/过短/过长的字节序列与代理对编码，再转 Java String。
static jstring safe_new_string_utf8(JNIEnv* env, const char* s, size_t len) {
    std::string clean;
    clean.reserve(len);
    for (size_t i = 0; i < len;) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        int seq;
        if (c < 0x80) { clean.push_back(s[i]); ++i; continue; }
        else if ((c & 0xE0) == 0xC0) seq = 2;
        else if ((c & 0xF0) == 0xE0) seq = 3;
        else if ((c & 0xF8) == 0xF0) seq = 4;
        else { ++i; continue; }  // 非法起始字节
        if (i + seq > len) { ++i; continue; }  // 截断的序列
        // 校验后续续接字节
        bool ok = true;
        for (int k = 1; k < seq; ++k) {
            if ((static_cast<unsigned char>(s[i + k]) & 0xC0) != 0x80) { ok = false; break; }
        }
        // 拒绝过短编码（0xC0/0xC1、E0 后跟 <A0、F0 后跟 <90）与代理对（ED A0-BF）
        if (ok && seq == 2 && c < 0xC2) ok = false;
        if (ok && seq == 3 && (c == 0xE0 && (static_cast<unsigned char>(s[i+1]) & 0xE0) == 0x80)) ok = false;
        if (ok && seq == 3 && (c == 0xED && (static_cast<unsigned char>(s[i+1]) & 0xE0) == 0xA0)) ok = false;
        if (ok && seq == 4 && (c == 0xF0 && (static_cast<unsigned char>(s[i+1]) & 0xF0) == 0x80)) ok = false;
        if (ok && seq == 4 && c > 0xF4) ok = false;
        if (ok) { clean.append(s + i, seq); i += seq; }
        else { ++i; }
    }
    return env->NewStringUTF(clean.c_str());
}

struct HyMt2Handle {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    llama_token bos_token = 0;
    llama_token user_token = -1;
    llama_token asst_token = -1;
    llama_token eot_token = -1;
    // 中止标志：nativeAbort 置位，解码循环检查后提前退出（真正终止翻译进程）
    std::atomic<bool> abort{false};
    // 前缀 KV 缓存：固定翻译指令只解码一次进 KV，跨翻译复用，跳过重复 prefill。
    // prefix_key == 当前前缀字符串时 prefix_n > 0 表示 KV 的 [0, prefix_n) 已缓存前缀。
    std::string prefix_key;
    int32_t prefix_n = 0;
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
        // ⚠️ prefill（读原文）与生成（写译文）线程数分开：
        //    手机实测（1.25-bit, 8核限频）prefill 用满 8 核比 6 核快 ~22%，生成 8 核反而略慢。
        //    所以批量解码吃满全部核心提速「读」，生成保持用户设置（默认 6）。
        const unsigned hw_threads = std::thread::hardware_concurrency();
        cp.n_threads_batch = std::max<int>(nThreads, static_cast<int>(hw_threads));
        LOGI("nativeInit: threads=%d batch_threads=%d hw=%u", nThreads, cp.n_threads_batch, hw_threads);
        cp.n_batch = std::min<int>(nCtx, 2048);
        llama_context* ctx = llama_init_from_model(model, cp);
        if (ctx == nullptr) { LOGE("llama_init_from_model failed"); llama_model_free(model); return 0; }

        auto* h = new HyMt2Handle();
        h->model = model;
        h->ctx = ctx;
        h->vocab = llama_model_get_vocab(model);

        // 诊断 + 格式：读取模型要求的输入格式（chat template）与聊天特殊 token
        {
            char tbuf[2048];
            const int tn = llama_model_meta_val_str(model, "tokenizer.ggml.chat_template", tbuf, sizeof(tbuf));
            LOGI("chat_template[%d]=%s", tn, tn >= 0 ? tbuf : "(none)");
            h->bos_token = llama_vocab_bos(h->vocab);
            LOGI("bos=%d eos=%d vocab_n=%d", h->bos_token, llama_vocab_eos(h->vocab), llama_vocab_n_tokens(h->vocab));
            for (llama_token t = 0; t < llama_vocab_n_tokens(h->vocab); ++t) {
                const char* text = llama_vocab_get_text(h->vocab, t);
                if (text == nullptr) continue;
                if (strstr(text, "hy_User")) { if (h->user_token < 0) h->user_token = t; }
                else if (strstr(text, "hy_Assistant")) { if (h->asst_token < 0) h->asst_token = t; }
                else if (strstr(text, "hy_EOT")) { if (h->eot_token < 0) h->eot_token = t; }
            }
            LOGI("chat special tokens: user=%d asst=%d eot=%d",
                 h->user_token, h->asst_token, h->eot_token);
        }

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

// 核心翻译实现。jCallback != nullptr 时，每生成一段译文就回调 onToken(累积的完整译文) 实现流式显示。
// jPrefix 传「固定指令前缀」字符串（不含待翻译文本）；同一前缀跨翻译复用 KV 缓存，跳过重复 prefill。
static jstring translate_impl(JNIEnv* env, jlong jHandle, jstring jPrompt, jstring jPrefix,
    jfloat temperature, jfloat topP, jint topK,
    jfloat repetitionPenalty, jint maxTokens, jobject jCallback) {
    try {
        const long long t_entry = now_ms();
        LOGI("translate: ENTER handle=%lld temp=%.3f top_p=%.3f top_k=%d rep=%.3f max_tokens=%d",
             jHandle, temperature, topP, topK, repetitionPenalty, maxTokens);

        std::lock_guard<std::mutex> lock(g_mutex);
        LOGI("translate: mutex acquired (+%lld ms)", now_ms() - t_entry);

        auto* h = reinterpret_cast<HyMt2Handle*>(jHandle);
        if (h == nullptr || h->ctx == nullptr) return env->NewStringUTF("");
        h->abort.store(false);  // 清除上次可能残留的中止标志

        llama_memory_t mem = llama_get_memory(h->ctx);

        const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
        if (prompt == nullptr) return env->NewStringUTF("");
        const size_t text_len = strlen(prompt);
        const char* prefix_cstr = (jPrefix != nullptr) ? env->GetStringUTFChars(jPrefix, nullptr) : nullptr;
        std::string prefix = (prefix_cstr != nullptr) ? std::string(prefix_cstr) : std::string();
        if (prefix_cstr != nullptr) env->ReleaseStringUTFChars(jPrefix, prefix_cstr);
        LOGI("translate: prompt chars=%zu preview=[%.100s]", text_len, prompt);
        // 把含 {source_text} 的尾部单独打出来，确认识别文本确实进了 prompt
        const size_t tail_len = std::min<size_t>(text_len, 80);
        LOGI("translate: prompt tail=[%s]", prompt + (text_len - tail_len));
        LOGI("translate: prefix chars=%zu prefix=[%.60s]", prefix.size(), prefix.c_str());
        log_chunked("translate: FULL PROMPT:\n", prompt, text_len);

        // 前缀缓存是否有效：前缀非空、已建缓存、且与本前缀一致
        const bool want_cache = !prefix.empty();
        const bool cache_valid = want_cache && h->prefix_n > 0 && h->prefix_key == prefix;

        // 分词辅助（缓冲按字符数 + 余量；若缓冲不足返回负的"需要数量"，则按需扩容重试）
        // ⚠️ f8b355a9e 版 llama_vocab::tokenize 内部是 std::string(text, text_len)，不处理 text_len=-1，
        //    传 -1 会抛 std::length_error 导致进程 abort。必须传真实字节长度。
        auto tokenize_str = [&](const char* s, int32_t len, bool add_special, std::vector<llama_token>& out) -> int32_t {
            std::vector<llama_token> buf((len < 0 ? 0 : len) + 16);
            for (int attempt = 0; attempt < 2; ++attempt) {
                int32_t n = llama_tokenize(h->vocab, s, len, buf.data(), static_cast<int32_t>(buf.size()), add_special, false);
                if (n < 0) {
                    const int32_t needed = -n;
                    if (needed <= static_cast<int32_t>(buf.size())) return n;
                    buf.resize(static_cast<size_t>(needed) + 16);
                    continue;
                }
                out.assign(buf.begin(), buf.begin() + n);
                return n;
            }
            return -1;
        };

        // 本次要解码的 token（不含已缓存的前缀；缓存未命中时 = 全量 prompt）
        std::vector<llama_token> prompt_tok;
        int32_t start_pos = 0;  // 本次解码的起始 KV 位置（缓存命中时 = 前缀长度）

        if (cache_valid) {
            // 缓存命中：只解码变化部分（prompt 去掉前缀），前缀 KV 已在 [0, prefix_n)
            const int32_t rest_len = static_cast<int32_t>(text_len - prefix.size());
            const int32_t nr = tokenize_str(prompt + prefix.size(), rest_len, false, prompt_tok);
            if (nr < 0) {
                LOGE("translate: rest tokenize failed (%d)", nr);
                env->ReleaseStringUTFChars(jPrompt, prompt);
                return env->NewStringUTF("");
            }
            start_pos = h->prefix_n;
            LOGI("translate: prefix cache HIT prefix_n=%d rest_tokens=%d", h->prefix_n, nr);
        } else {
            // 缓存未命中/禁用：全量 prefill（保持原有行为），清空 KV 后重建
            llama_memory_clear(mem, false);
            h->prefix_n = 0;  // KV 已清空，任何已缓存前缀失效
            const int32_t n = tokenize_str(prompt, static_cast<int32_t>(text_len), true, prompt_tok);
            if (n < 0) {
                LOGE("translate: prompt tokenize failed (%d)", n);
                env->ReleaseStringUTFChars(jPrompt, prompt);
                return env->NewStringUTF("");
            }
            start_pos = 0;
            // 尝试建立前缀缓存：校验「前缀 token」==「全量 prompt 前段」，一致才说明切分干净
            // （add_special 未额外加 BOS、且前缀/变化部分边界无 token 合并），缓存才与全量 prefill 等价
            if (want_cache && h->prefix_key != prefix) {
                std::vector<llama_token> prefix_tok;
                const int32_t np = tokenize_str(prefix.c_str(), static_cast<int32_t>(prefix.size()), false, prefix_tok);
                bool clean = (np >= 0) && (static_cast<int32_t>(prefix_tok.size()) <= n);
                if (clean) {
                    for (int32_t k = 0; k < static_cast<int32_t>(prefix_tok.size()); ++k) {
                        if (prefix_tok[k] != prompt_tok[k]) { clean = false; break; }
                    }
                }
                if (clean) {
                    const int32_t chat_prefix = (h->bos_token > 0 ? 1 : 0) + (h->user_token >= 0 ? 1 : 0);
                    h->prefix_n = chat_prefix + static_cast<int32_t>(prefix_tok.size());
                    h->prefix_key = prefix;
                    LOGI("translate: prefix cache built prefix_n=%d prefix_tokens=%d", h->prefix_n, np);
                } else {
                    // 切分不干净 → 该前缀不可缓存，记住 key 避免每次重试
                    h->prefix_key = prefix;
                    LOGW("translate: prefix split not clean, caching disabled for this prefix");
                }
            } else if (!want_cache) {
                h->prefix_key.clear();
            }
        }
        env->ReleaseStringUTFChars(jPrompt, prompt);

        // ⚠️ 按模型聊天格式构造输入: [BOS]<User>{prompt}<Assistant>
        //    模型有 <｜hy_User｜>/<｜hy_Assistant｜> 角色 token，不包角色标记直接喂裸文本会退化输出垃圾
        if (h->bos_token > 0 || h->user_token >= 0 || h->asst_token >= 0) {
            std::vector<llama_token> seq;
            seq.reserve(prompt_tok.size() + 4);
            if (h->bos_token > 0) seq.push_back(h->bos_token);
            if (h->user_token >= 0) seq.push_back(h->user_token);
            seq.insert(seq.end(), prompt_tok.begin(), prompt_tok.end());
            if (h->asst_token >= 0) seq.push_back(h->asst_token);
            prompt_tok.swap(seq);
        }
        const int32_t n_tokens = static_cast<int32_t>(prompt_tok.size());
        // 本次输入实际占用上下文 [start_pos, start_pos + n_tokens)
        const int32_t total_prompt = start_pos + n_tokens;

        // 打印前几个 prompt token id（检查是否有 BOS / 特殊 token）
        {
            std::string ids;
            for (int k = 0; k < std::min<int>(n_tokens, 10); ++k) {
                if (k) ids += ",";
                ids += std::to_string(prompt_tok[k]);
            }
            LOGI("translate: input tokens=%d start_pos=%d total_ctx=%d first=[%s]",
                 n_tokens, start_pos, total_prompt, ids.c_str());
        }
        // 完整输入 token 序列（模型实际消费的 ID 列表），便于核对聊天格式
        {
            std::string ids;
            for (int32_t k = 0; k < n_tokens; ++k) {
                if (k) ids += ",";
                ids += std::to_string(prompt_tok[k]);
            }
            log_chunked("translate: FULL INPUT TOKENS: ", ids.c_str(), ids.size());
        }

        // 防御：空输入（无 token）时直接返回，避免下方 batch.logits[n_tokens-1] 越界写
        if (n_tokens <= 0) {
            LOGE("translate: empty prompt tokens");
            return env->NewStringUTF("");
        }

        const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(h->ctx));
        // 输出长度上限：翻译通常 ≈ 源长度，防止模型过度生成（实测短句翻出 300+ token）
        // effective = min(用户max_tokens, 源tokens*2+64, 上下文剩余)
        const int gen_cap = n_tokens * 2 + 64;
        const int max_tokens = std::max(0, std::min(static_cast<int>(maxTokens),
                                                    std::min(gen_cap, static_cast<int>(n_ctx - total_prompt))));
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
        LOGI("translate: decoding input (%d tokens, start_pos=%d)...", n_tokens, start_pos);
        llama_batch batch = llama_batch_init(n_tokens, 0, 1);
        if (batch.token == nullptr) {
            LOGE("translate: batch alloc failed");
            llama_sampler_free(smpl);
            return env->NewStringUTF("");
        }
        batch.n_tokens = n_tokens;
        for (int32_t i = 0; i < n_tokens; ++i) {
            batch.token[i]    = prompt_tok[i];
            batch.pos[i]      = start_pos + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
        }
        // ⚠️ llama_batch_init 的 logits 数组默认全 false：必须为"需要采样"的最后一个 token 置 true，
        //    否则模型不算 logits，llama_sampler_sample 会 ggml_abort 崩溃。
        batch.logits[n_tokens - 1] = true;
        // 流式回调：解析 onPhase/onToken 方法；通知进入 prompt 解码阶段
        jmethodID onTokenId = nullptr;
        jmethodID onPhaseId = nullptr;
        if (jCallback != nullptr) {
            jclass cbClass = env->GetObjectClass(jCallback);
            onTokenId = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
            onPhaseId = env->GetMethodID(cbClass, "onPhase", "(Ljava/lang/String;)V");
            env->DeleteLocalRef(cbClass);
        }
        if (onPhaseId != nullptr) {
            jstring js = env->NewStringUTF("prefill");
            env->CallVoidMethod(jCallback, onPhaseId, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
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
        int32_t n_cur = total_prompt;
        const long long t_gen0 = now_ms();
        bool hit_eog = false;
        int generated = 0;
        // 通知阶段：开始生成译文
        if (onPhaseId != nullptr) {
            jstring js = env->NewStringUTF("generate");
            env->CallVoidMethod(jCallback, onPhaseId, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        for (int i = 0; i < max_tokens; ++i) {
            if ((i % 8) == 0) {
                LOGI("translate: gen progress token %d/%d (+%lld ms)", i, max_tokens, now_ms() - t_gen0);
            }
            // 中止检查：nativeAbort 置位后提前退出，真正终止翻译
            if (h->abort.load()) {
                LOGI("translate: ABORTED at token %d", i);
                break;
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
            if (n_piece > 0) {
                result.append(piece_buf, n_piece);
                // 流式显示：回调「当前完整译文」（累积），UI 逐段增长显示，而不是只发单个片段
                if (onTokenId != nullptr) {
                    jstring js = safe_new_string_utf8(env, result.c_str(), result.size());
                    if (js != nullptr) {
                        env->CallVoidMethod(jCallback, onTokenId, js);
                        env->DeleteLocalRef(js);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                    }
                }
            }
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

        // 保留前缀 KV，只清掉本次生成的内容，供下次翻译复用；若裁剪失败则禁用缓存（下次全量 prefill）
        if (h->prefix_n > 0 && h->prefix_key == prefix) {
            if (llama_memory_seq_rm(mem, 0, h->prefix_n, -1)) {
                LOGI("translate: KV trimmed to prefix_n=%d", h->prefix_n);
            } else {
                h->prefix_n = 0;
                h->prefix_key.clear();
                LOGW("translate: KV trim failed, prefix cache disabled");
            }
        }

        log_chunked("translate: FULL RESULT:\n", result.c_str(), result.size());
        LOGI("translate: DONE generated=%d/%d eog=%d gen_elapsed=%lld ms total_elapsed=%lld ms result_len=%zu preview=[%.120s]",
             generated, max_tokens, hit_eog, now_ms() - t_gen0, now_ms() - t_entry, result.size(), result.c_str());
        return safe_new_string_utf8(env, result.c_str(), result.size());
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

extern "C" JNIEXPORT jstring JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeTranslate(
    JNIEnv* env, jclass, jlong jHandle, jstring jPrompt, jstring jPrefix,
    jfloat temperature, jfloat topP, jint topK,
    jfloat repetitionPenalty, jint maxTokens) {
    return translate_impl(env, jHandle, jPrompt, jPrefix, temperature, topP, topK, repetitionPenalty, maxTokens, nullptr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeTranslateStreaming(
    JNIEnv* env, jclass, jlong jHandle, jstring jPrompt, jstring jPrefix,
    jfloat temperature, jfloat topP, jint topK,
    jfloat repetitionPenalty, jint maxTokens, jobject jCallback) {
    return translate_impl(env, jHandle, jPrompt, jPrefix, temperature, topP, topK, repetitionPenalty, maxTokens, jCallback);
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

// 中止翻译：不抢互斥锁（translate_impl 持锁时无法获取），仅置位原子标志让解码循环提前退出
extern "C" JNIEXPORT void JNICALL
Java_translationapi_hymt2translation_HyMt2Native_nativeAbort(JNIEnv*, jclass, jlong jHandle) {
    auto* h = reinterpret_cast<HyMt2Handle*>(jHandle);
    if (h != nullptr) {
        h->abort.store(true);
        LOGI("abort requested");
    }
}
