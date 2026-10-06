// Thin, instrumented wrapper around the llama.cpp C API. No JNI here so it can be
// compiled and exercised on a desktop host as well as on Android.
#pragma once
#include <atomic>
#include <cstdint>
#include <functional>
#include <string>

namespace llmc {

struct ProcMem {           // all in kB, -1 if unavailable
    long vm_rss = -1, vm_hwm = -1, rss_anon = -1, rss_file = -1, vm_swap = -1;
    long mem_available = -1;  // system-wide /proc/meminfo
};
ProcMem read_proc_mem();
bool reset_peak_rss();     // echo 5 > /proc/self/clear_refs (resets VmHWM)

struct LoadParams {
    std::string path;
    int n_threads = 6;
    int n_threads_batch = 6;
    int n_ctx = 2048;
    int n_batch = 512;
};

struct LoadResult {
    bool ok = false;
    std::string error;
    double load_ms = 0, ctx_ms = 0;
    std::string desc, arch, name, file_type, load_mode;
    uint64_t n_params = 0, size_bytes = 0;
    int n_ctx_train = 0, n_layer = 0, n_ctx = 0, n_batch = 0, n_threads = 0, n_threads_batch = 0;
    ProcMem mem_before, mem_after_model, mem_after_ctx;
    bool peak_reset_ok = false;
};

struct GenParams {
    std::string prompt;
    bool chat_template = true;
    int n_predict = 128;
    bool ignore_eos = false;   // benchmark mode: always generate n_predict tokens
    float temp = 0.0f;         // <= 0 -> greedy
    uint32_t seed = 42;
};

struct GenResult {
    bool ok = false;
    std::string error, stop_reason, text;
    int n_prompt = 0, n_gen = 0, n_decode_calls = 0;
    double prefill_ms = 0, decode_ms = 0, ttft_ms = 0, wall_ms = 0, sample_ms = 0;
    // llama.cpp internal counters (cross-check)
    double perf_p_eval_ms = 0, perf_eval_ms = 0; int perf_n_p_eval = 0, perf_n_eval = 0;
    ProcMem mem_after;
    bool peak_reset_ok = false;
};

// Returns false to request stop.
using PieceCallback = std::function<bool(const std::string &)>;

void backend_init(const char *native_lib_dir);  // loads CPU backend variants
std::string system_info();
std::string backend_devices();                  // names of registered backends/devices

bool is_loaded();
LoadResult load(const LoadParams &p);
GenResult generate(const GenParams &p, const PieceCallback &cb);
void request_stop();
void unload();

// utilities (exposed for tests)
size_t utf8_complete_prefix(const std::string &s);
std::string json_escape(const std::string &s);
std::string to_json(const LoadResult &r);
std::string to_json(const GenResult &r);

}  // namespace llmc
