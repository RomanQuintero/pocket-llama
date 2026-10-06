// JNI bridge: Kotlin (dev.rq.llmceiling.NativeLlama) <-> engine.cpp <-> llama.cpp
// Strings going to Java are passed as UTF-8 byte arrays (NewStringUTF expects *modified*
// UTF-8 and aborts on 4-byte sequences such as emoji).
#include <jni.h>
#include <string>
#include "engine.h"

namespace {
std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}
jbyteArray jbytes(JNIEnv *env, const std::string &s) {
    jbyteArray a = env->NewByteArray((jsize) s.size());
    if (a && !s.empty()) env->SetByteArrayRegion(a, 0, (jsize) s.size(), (const jbyte *) s.data());
    return a;
}
}  // namespace

#define JFN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_dev_rq_llmceiling_NativeLlama_##name

JFN(void, nInit)(JNIEnv *env, jobject, jstring lib_dir) { llmc::backend_init(jstr(env, lib_dir).c_str()); }

JFN(jbyteArray, nSystemInfo)(JNIEnv *env, jobject) { return jbytes(env, llmc::system_info()); }

JFN(jbyteArray, nDevices)(JNIEnv *env, jobject) { return jbytes(env, llmc::backend_devices()); }

JFN(jbyteArray, nLoad)(JNIEnv *env, jobject, jstring path, jint threads, jint threads_batch, jint n_ctx, jint n_batch) {
    llmc::LoadParams p;
    p.path = jstr(env, path);
    p.n_threads = threads;
    p.n_threads_batch = threads_batch;
    p.n_ctx = n_ctx;
    p.n_batch = n_batch;
    return jbytes(env, llmc::to_json(llmc::load(p)));
}

JFN(jbyteArray, nGenerate)(JNIEnv *env, jobject, jstring prompt, jboolean chat_template, jint n_predict,
                           jboolean ignore_eos, jfloat temp, jint seed, jobject sink) {
    llmc::GenParams p;
    p.prompt = jstr(env, prompt);
    p.chat_template = chat_template;
    p.n_predict = n_predict;
    p.ignore_eos = ignore_eos;
    p.temp = temp;
    p.seed = (uint32_t) seed;

    jmethodID on_piece = nullptr;
    if (sink) {
        jclass cls = env->GetObjectClass(sink);
        on_piece = env->GetMethodID(cls, "onPiece", "([B)Z");
        env->DeleteLocalRef(cls);
    }
    auto cb = [&](const std::string &piece) -> bool {
        if (!sink || !on_piece) return true;
        jbyteArray a = jbytes(env, piece);
        const jboolean keep = env->CallBooleanMethod(sink, on_piece, a);
        env->DeleteLocalRef(a);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return keep;
    };
    return jbytes(env, llmc::to_json(llmc::generate(p, cb)));
}

JFN(void, nStop)(JNIEnv *, jobject) { llmc::request_stop(); }

JFN(void, nUnload)(JNIEnv *, jobject) { llmc::unload(); }

JFN(jbyteArray, nProcMem)(JNIEnv *env, jobject) {
    const auto m = llmc::read_proc_mem();
    std::string s = "{\"vm_rss_kb\":" + std::to_string(m.vm_rss) + ",\"vm_hwm_kb\":" + std::to_string(m.vm_hwm) +
                    ",\"rss_anon_kb\":" + std::to_string(m.rss_anon) + ",\"rss_file_kb\":" + std::to_string(m.rss_file) +
                    ",\"vm_swap_kb\":" + std::to_string(m.vm_swap) + ",\"mem_available_kb\":" + std::to_string(m.mem_available) + "}";
    return jbytes(env, s);
}
