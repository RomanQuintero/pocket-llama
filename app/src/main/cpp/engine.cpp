#include "engine.h"

#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "llmceiling", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "llmceiling", __VA_ARGS__)
#else
#define LOGI(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#define LOGE(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

namespace llmc {

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static LoadParams g_params;
static std::atomic<bool> g_stop{false};

static double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

// ---------------------------------------------------------------- memory
static long kb_field(const std::string &text, const char *key) {
    const size_t pos = text.find(key);
    if (pos == std::string::npos) return -1;
    long v = -1;
    if (sscanf(text.c_str() + pos + strlen(key), " %ld", &v) != 1) return -1;
    return v;
}

static std::string slurp(const char *path) {
    std::ifstream f(path);
    if (!f) return {};
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

ProcMem read_proc_mem() {
    ProcMem m;
    const std::string st = slurp("/proc/self/status");
    m.vm_rss = kb_field(st, "VmRSS:");
    m.vm_hwm = kb_field(st, "VmHWM:");
    m.rss_anon = kb_field(st, "RssAnon:");
    m.rss_file = kb_field(st, "RssFile:");
    m.vm_swap = kb_field(st, "VmSwap:");
    m.mem_available = kb_field(slurp("/proc/meminfo"), "MemAvailable:");
    return m;
}

bool reset_peak_rss() {
    FILE *f = fopen("/proc/self/clear_refs", "w");
    if (!f) return false;
    const bool ok = fputs("5", f) >= 0;
    return (fclose(f) == 0) && ok;
}

// ---------------------------------------------------------------- logging
static void log_cb(ggml_log_level level, const char *text, void *) {
#ifdef __ANDROID__
    const int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
                   : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
                   : ANDROID_LOG_INFO;
    __android_log_write(prio, "llama.cpp", text);
#else
    (void) level;
    fputs(text, stderr);
#endif
}

void backend_init(const char *native_lib_dir) {
    llama_log_set(log_cb, nullptr);
    ggml_time_init();
    if (native_lib_dir && *native_lib_dir) {
        LOGI("Loading ggml backends from %s", native_lib_dir);
        ggml_backend_load_all_from_path(native_lib_dir);
    } else {
        ggml_backend_load_all();
    }
    llama_backend_init();
}

std::string system_info() { return llama_print_system_info(); }

std::string backend_devices() {
    std::string out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        if (!out.empty()) out += "; ";
        out += ggml_backend_dev_name(d);
        out += " (";
        out += ggml_backend_dev_description(d);
        out += ")";
    }
    return out;
}

bool is_loaded() { return g_model != nullptr && g_ctx != nullptr; }

static std::string meta(const llama_model *m, const char *key) {
    char buf[256] = {0};
    const int n = llama_model_meta_val_str(m, key, buf, sizeof(buf));
    return n >= 0 ? std::string(buf) : std::string();
}

void unload() {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

LoadResult load(const LoadParams &p) {
    LoadResult r;
    unload();
    g_params = p;
    r.peak_reset_ok = reset_peak_rss();
    r.mem_before = read_proc_mem();

    llama_model_params mp = llama_model_default_params();
    r.load_mode = llama_load_mode_name(mp.load_mode);

    double t0 = now_ms();
    g_model = llama_model_load_from_file(p.path.c_str(), mp);
    r.load_ms = now_ms() - t0;
    r.mem_after_model = read_proc_mem();
    if (!g_model) {
        r.error = "llama_model_load_from_file returned null (see logcat tag llama.cpp)";
        return r;
    }

    char desc[256] = {0};
    llama_model_desc(g_model, desc, sizeof(desc));
    r.desc = desc;
    r.arch = meta(g_model, "general.architecture");
    r.name = meta(g_model, "general.name");
    r.file_type = meta(g_model, "general.file_type");
    r.n_params = llama_model_n_params(g_model);
    r.size_bytes = llama_model_size(g_model);
    r.n_ctx_train = llama_model_n_ctx_train(g_model);
    r.n_layer = llama_model_n_layer(g_model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) p.n_ctx;
    cp.n_batch = (uint32_t) p.n_batch;
    cp.n_ubatch = (uint32_t) p.n_batch;
    cp.n_threads = p.n_threads;
    cp.n_threads_batch = p.n_threads_batch;
    cp.no_perf = false;
    cp.abort_callback = [](void *) { return g_stop.load(); };
    cp.abort_callback_data = nullptr;

    t0 = now_ms();
    g_ctx = llama_init_from_model(g_model, cp);
    r.ctx_ms = now_ms() - t0;
    r.mem_after_ctx = read_proc_mem();
    if (!g_ctx) {
        r.error = "llama_init_from_model returned null (context allocation failed)";
        unload();
        return r;
    }
    r.n_ctx = (int) llama_n_ctx(g_ctx);
    r.n_batch = (int) llama_n_batch(g_ctx);
    r.n_threads = llama_n_threads(g_ctx);
    r.n_threads_batch = llama_n_threads_batch(g_ctx);
    r.ok = true;
    LOGI("loaded %s in %.0f ms (+ctx %.0f ms)", r.desc.c_str(), r.load_ms, r.ctx_ms);
    return r;
}

void request_stop() { g_stop.store(true); }

size_t utf8_complete_prefix(const std::string &s) {
    // Length of the longest prefix that does not end in the middle of a UTF-8 sequence.
    const size_t n = s.size();
    size_t i = n;
    // walk back over at most 3 continuation bytes to the lead byte
    size_t back = 0;
    while (i > 0 && back < 4 && (((unsigned char) s[i - 1]) & 0xC0) == 0x80) { i--; back++; }
    if (i == 0) return n;  // only continuation bytes: invalid, pass through
    const unsigned char lead = (unsigned char) s[i - 1];
    size_t need;
    if (lead < 0x80) need = 1;
    else if ((lead & 0xE0) == 0xC0) need = 2;
    else if ((lead & 0xF0) == 0xE0) need = 3;
    else if ((lead & 0xF8) == 0xF0) need = 4;
    else return n;  // invalid lead byte: don't hold data back forever
    const size_t have = back + 1;
    return have >= need ? n : i - 1;
}

static std::string piece(const llama_vocab *vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big((size_t) -n, '\0');
        n = llama_token_to_piece(vocab, t, big.data(), (int32_t) big.size(), 0, false);
        return n > 0 ? big.substr(0, (size_t) n) : std::string();
    }
    return std::string(buf, (size_t) n);
}

static bool format_chat(const std::string &user, std::string &out) {
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    if (!tmpl) return false;
    llama_chat_message msg{"user", user.c_str()};
    std::vector<char> buf(user.size() * 2 + 512);
    int n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
    if (n < 0) return false;
    if ((size_t) n > buf.size()) {
        buf.resize((size_t) n);
        n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
    }
    out.assign(buf.data(), (size_t) n);
    return true;
}

GenResult generate(const GenParams &p, const PieceCallback &cb) {
    GenResult r;
    g_stop.store(false);
    if (!is_loaded()) { r.error = "no model loaded"; return r; }

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    std::string prompt = p.prompt;
    if (p.chat_template && !format_chat(p.prompt, prompt)) {
        r.error = "model has no usable chat template";
        return r;
    }

    // tokenize
    int n = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks((size_t) std::max(n, 0));
    if (n <= 0 || llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(),
                                 (int32_t) toks.size(), true, true) < 0) {
        r.error = "tokenization failed";
        return r;
    }
    r.n_prompt = (int) toks.size();
    const int n_ctx = (int) llama_n_ctx(g_ctx);
    if (r.n_prompt + p.n_predict > n_ctx) {
        r.error = "prompt (" + std::to_string(r.n_prompt) + ") + n_predict (" + std::to_string(p.n_predict) +
                  ") exceeds n_ctx (" + std::to_string(n_ctx) + ")";
        return r;
    }

    llama_memory_clear(llama_get_memory(g_ctx), true);
    llama_perf_context_reset(g_ctx);

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (p.temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(p.temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(p.seed));
    }

    r.peak_reset_ok = reset_peak_rss();
    const double t_start = now_ms();

    // ---- prefill (prompt processing), in chunks of n_batch
    const int n_batch = (int) llama_n_batch(g_ctx);
    for (int i = 0; i < r.n_prompt; i += n_batch) {
        const int cur = std::min(n_batch, r.n_prompt - i);
        const int rc = llama_decode(g_ctx, llama_batch_get_one(toks.data() + i, cur));
        if (rc != 0) {
            r.error = rc == 2 ? "stopped during prefill" : "llama_decode failed during prefill (rc=" + std::to_string(rc) + ")";
            r.stop_reason = rc == 2 ? "user_stop" : "error";
            llama_sampler_free(smpl);
            r.wall_ms = now_ms() - t_start;
            return r;
        }
    }
    r.prefill_ms = now_ms() - t_start;

    // ---- decode (token generation)
    std::string pending;
    r.stop_reason = "n_predict";
    while (r.n_gen < p.n_predict) {
        if (g_stop.load()) { r.stop_reason = "user_stop"; break; }

        const double ts = now_ms();
        llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
        r.sample_ms += now_ms() - ts;
        if (r.n_gen == 0) r.ttft_ms = now_ms() - t_start;

        if (llama_vocab_is_eog(vocab, tok) && !p.ignore_eos) { r.stop_reason = "eog"; break; }
        r.n_gen++;

        pending += piece(vocab, tok);
        const size_t ok_len = utf8_complete_prefix(pending);
        if (ok_len > 0) {
            const std::string out = pending.substr(0, ok_len);
            pending.erase(0, ok_len);
            r.text += out;
            if (cb && !cb(out)) { r.stop_reason = "user_stop"; break; }
        }
        if (r.n_gen >= p.n_predict) break;  // last token needs no forward pass

        const double td = now_ms();
        const int rc = llama_decode(g_ctx, llama_batch_get_one(&tok, 1));
        r.decode_ms += now_ms() - td;
        if (rc != 0) {
            if (rc == 2) { r.stop_reason = "user_stop"; break; }
            r.error = "llama_decode failed during generation (rc=" + std::to_string(rc) + ")";
            r.stop_reason = "error";
            break;
        }
        r.n_decode_calls++;
    }
    if (!pending.empty()) { r.text += pending; if (cb) cb(pending); }
    r.wall_ms = now_ms() - t_start;

    const llama_perf_context_data pd = llama_perf_context(g_ctx);
    r.perf_p_eval_ms = pd.t_p_eval_ms;
    r.perf_eval_ms = pd.t_eval_ms;
    r.perf_n_p_eval = pd.n_p_eval;
    r.perf_n_eval = pd.n_eval;
    r.mem_after = read_proc_mem();
    llama_sampler_free(smpl);
    r.ok = r.error.empty();
    return r;
}

// ---------------------------------------------------------------- json
std::string json_escape(const std::string &s) {
    std::string o;
    o.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (c < 0x20) { char b[8]; snprintf(b, sizeof(b), "\\u%04x", c); o += b; }
                else o += (char) c;
        }
    }
    return o;
}

namespace {
struct J {
    std::ostringstream ss;
    bool first = true;
    J() { ss.precision(6); ss << std::fixed; ss << "{"; }
    void key(const char *k) { if (!first) ss << ","; first = false; ss << "\"" << k << "\":"; }
    J &s(const char *k, const std::string &v) { key(k); ss << "\"" << json_escape(v) << "\""; return *this; }
    J &b(const char *k, bool v) { key(k); ss << (v ? "true" : "false"); return *this; }
    J &i(const char *k, long long v) { key(k); ss << v; return *this; }
    J &u(const char *k, unsigned long long v) { key(k); ss << v; return *this; }
    J &d(const char *k, double v) { key(k); ss << v; return *this; }
    J &raw(const char *k, const std::string &v) { key(k); ss << v; return *this; }
    std::string str() { ss << "}"; return ss.str(); }
};
std::string mem_json(const ProcMem &m) {
    J j;
    j.i("vm_rss_kb", m.vm_rss).i("vm_hwm_kb", m.vm_hwm).i("rss_anon_kb", m.rss_anon)
     .i("rss_file_kb", m.rss_file).i("vm_swap_kb", m.vm_swap).i("mem_available_kb", m.mem_available);
    return j.str();
}
}  // namespace

std::string to_json(const LoadResult &r) {
    J j;
    j.b("ok", r.ok).s("error", r.error).d("load_ms", r.load_ms).d("ctx_ms", r.ctx_ms)
     .s("desc", r.desc).s("arch", r.arch).s("name", r.name).s("file_type", r.file_type)
     .s("load_mode", r.load_mode).u("n_params", r.n_params).u("size_bytes", r.size_bytes)
     .i("n_ctx_train", r.n_ctx_train).i("n_layer", r.n_layer).i("n_ctx", r.n_ctx).i("n_batch", r.n_batch)
     .i("n_threads", r.n_threads).i("n_threads_batch", r.n_threads_batch).b("peak_reset_ok", r.peak_reset_ok)
     .raw("mem_before", mem_json(r.mem_before)).raw("mem_after_model", mem_json(r.mem_after_model))
     .raw("mem_after_ctx", mem_json(r.mem_after_ctx));
    return j.str();
}

std::string to_json(const GenResult &r) {
    J j;
    j.b("ok", r.ok).s("error", r.error).s("stop_reason", r.stop_reason)
     .i("n_prompt", r.n_prompt).i("n_gen", r.n_gen).i("n_decode_calls", r.n_decode_calls)
     .d("prefill_ms", r.prefill_ms).d("decode_ms", r.decode_ms).d("ttft_ms", r.ttft_ms)
     .d("wall_ms", r.wall_ms).d("sample_ms", r.sample_ms)
     .d("perf_p_eval_ms", r.perf_p_eval_ms).d("perf_eval_ms", r.perf_eval_ms)
     .i("perf_n_p_eval", r.perf_n_p_eval).i("perf_n_eval", r.perf_n_eval)
     .b("peak_reset_ok", r.peak_reset_ok).raw("mem_after", mem_json(r.mem_after))
     .s("text", r.text);
    return j.str();
}

}  // namespace llmc
