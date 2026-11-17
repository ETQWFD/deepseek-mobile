#include <jni.h>
#include <android/log.h>
#include <mutex>
#include <string>
#include <vector>
#include <cstring>
#include <cstdio>
#include <atomic>
#include "llama.h"

#define TAG "agentllama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_ctx = 0;
    std::mutex mu;
};

static std::string g_last_error;
static std::atomic<bool> g_stop{false};

static std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return "";
    const char * p = env->GetStringUTFChars(s, nullptr);
    std::string out = p ? p : "";
    if (p) env->ReleaseStringUTFChars(s, p);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_etwqfd_agentetc_LlamaBridge_nativeInit(JNIEnv *, jclass) {
    llama_backend_init();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_etwqfd_agentetc_LlamaBridge_nativeLastError(JNIEnv * env, jclass) {
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_etwqfd_agentetc_LlamaBridge_nativeLoad(JNIEnv * env, jclass, jstring path_, jint n_ctx, jint n_threads, jint n_gpu) {
    std::string path = jstr(env, path_);
    g_last_error.clear();
    g_stop = false;
    Session * s = new Session();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu;

    s->model = llama_model_load_from_file(path.c_str(), mp);
    if (!s->model) {
        g_last_error = "模型加载失败，文件可能不是有效 GGUF";
        delete s;
        return 0;
    }
    s->vocab = llama_model_get_vocab(s->model);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx > 0 ? n_ctx : 2048;
    cp.n_batch = 256;
    cp.n_ubatch = 256;
    cp.n_threads = n_threads > 0 ? n_threads : 4;
    cp.n_threads_batch = cp.n_threads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    s->ctx = llama_init_from_model(s->model, cp);
    if (!s->ctx) {
        g_last_error = "上下文创建失败，内存不足或上下文过大";
        llama_model_free(s->model);
        delete s;
        return 0;
    }
    s->n_ctx = llama_n_ctx(s->ctx);
    LOGI("model loaded ctx=%d", s->n_ctx);
    return (jlong)(intptr_t)s;
}

extern "C" JNIEXPORT void JNICALL
Java_com_etwqfd_agentetc_LlamaBridge_nativeFree(JNIEnv *, jclass, jlong h) {
    Session * s = (Session *)(intptr_t)h;
    if (!s) return;
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

extern "C" JNIEXPORT void JNICALL
Java_com_etwqfd_agentetc_LlamaBridge_nativeRequestStop(JNIEnv *, jclass) {
    g_stop = true;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_etwqfd_agentetc_LlamaBridge_nativeBuildPrompt(JNIEnv * env, jclass, jlong h, jobjectArray roles_, jobjectArray contents_) {
    Session * s = (Session *)(intptr_t)h;
    if (!s) return env->NewStringUTF("");
    int n = env->GetArrayLength(roles_);
    std::vector<llama_chat_message> msgs(n);
    std::vector<std::string> keep(n);
    for (int i = 0; i < n; i++) {
        jstring r = (jstring)env->GetObjectArrayElement(roles_, i);
        jstring c = (jstring)env->GetObjectArrayElement(contents_, i);
        keep[i] = jstr(env, c);
        msgs[i].role = jstr(env, r).c_str();
        msgs[i].content = keep[i].c_str();
        env->DeleteLocalRef(r); env->DeleteLocalRef(c);
    }
    // role strings must outlive the call: rebuild with stable storage
    std::vector<std::string> roles(n);
    for (int i = 0; i < n; i++) {
        jstring r = (jstring)env->GetObjectArrayElement(roles_, i);
        roles[i] = jstr(env, r);
        msgs[i].role = roles[i].c_str();
        env->DeleteLocalRef(r);
    }
    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    if (!tmpl) {
        std::string out;
        for (int i = 0; i < n; i++) out += "<|im_start|>" + roles[i] + "\n" + keep[i] + "<|im_end|>\n";
        out += "<|im_start|>assistant\n";
        return env->NewStringUTF(out.c_str());
    }
    int need = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, nullptr, 0);
    if (need < 0) return env->NewStringUTF("");
    std::vector<char> buf(need + 1);
    int got = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int)buf.size());
    if (got < 0) {
        // fallback ChatML
        std::string out;
        for (int i = 0; i < n; i++) {
            out += "<|im_start|>" + roles[i] + "\n" + keep[i] + "<|im_end|>\n";
        }
        out += "<|im_start|>assistant\n";
        return env->NewStringUTF(out.c_str());
    }
    return env->NewStringUTF(buf.data());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_etwqfd_agentetc_LlamaBridge_nativeGenerate(JNIEnv * env, jclass, jlong h, jstring prompt_, jint max_tokens, jfloat temp, jfloat top_p, jint top_k) {
    Session * s = (Session *)(intptr_t)h;
    if (!s || !s->ctx) return env->NewStringUTF("");
    std::lock_guard<std::mutex> lock(s->mu);
    std::string prompt = jstr(env, prompt_);
    const llama_vocab * vocab = s->vocab;
    int n_prompt = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), nullptr, 0, true, true);
    if (n_prompt < 0) n_prompt = -n_prompt;
    std::vector<llama_token> tokens(n_prompt);
    int got = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), tokens.data(), (int)tokens.size(), true, true);
    if (got < 0) { g_last_error = "分词失败"; return env->NewStringUTF(""); }
    tokens.resize(got);
    if ((int)tokens.size() >= s->n_ctx) {
        g_last_error = "上下文不足：请缩短对话或调大上下文";
        return env->NewStringUTF("");
    }
    if (max_tokens < 1) max_tokens = 1;
    if (max_tokens > s->n_ctx - (int)tokens.size()) max_tokens = s->n_ctx - (int)tokens.size();
    llama_memory_clear(llama_get_memory(s->ctx), true);
    // n_batch is 256; decode the prompt in chunks instead of passing a >256 token batch
    int pos = 0;
    while (pos < (int)tokens.size()) {
        int take = std::min(256, (int)tokens.size() - pos);
        llama_batch batch = llama_batch_get_one(tokens.data() + pos, take);
        if (llama_decode(s->ctx, batch) != 0) {
            g_last_error = "提示词解码失败，内存不足";
            return env->NewStringUTF("");
        }
        pos += take;
    }
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k > 0 ? top_k : 40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p > 0 ? top_p : 0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp > 0 ? temp : 0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    std::string out;
    for (int i = 0; i < max_tokens; i++) {
        if (g_stop.load()) { g_last_error = "已停止"; break; }
        llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        char piece[512];
        int np = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, true);
        if (np > 0) out.append(piece, np);
        if (out.find("<|im_end|>") != std::string::npos) break;
        if (out.find("<|eot_id|>") != std::string::npos) break;
        llama_batch next = llama_batch_get_one(&id, 1);
        if (llama_decode(s->ctx, next) != 0) break;
    }
    llama_sampler_free(smpl);
    // strip chat control tokens
    size_t p;
    while ((p = out.find("<|im_end|>")) != std::string::npos) out.erase(p, 10);
    while ((p = out.find("<|eot_id|>")) != std::string::npos) out.erase(p, 10);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_etwqfd_agentetc_LlamaBridge_nativeInfo(JNIEnv * env, jclass, jlong h) {
    Session * s = (Session *)(intptr_t)h;
    if (!s) return env->NewStringUTF("{}");
    char desc[256] = {0};
    llama_model_desc(s->model, desc, sizeof(desc));
    char buf[512];
    snprintf(buf, sizeof(buf), "{\"n_ctx\":%d,\"n_params\":%lld,\"desc\":\"%s\"}", s->n_ctx,
             (long long)llama_model_n_params(s->model), desc);
    return env->NewStringUTF(buf);
}
