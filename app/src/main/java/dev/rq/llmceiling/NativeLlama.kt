package dev.rq.llmceiling

import org.json.JSONObject

/** Kotlin side of the JNI bridge (app/src/main/cpp/llm_jni.cpp). All calls must come from ONE thread. */
object NativeLlama {
    fun interface PieceSink { fun onPiece(utf8: ByteArray): Boolean }

    private var initialised = false

    fun init(nativeLibDir: String) {
        if (initialised) return
        System.loadLibrary("llmceiling")
        nInit(nativeLibDir)
        initialised = true
    }

    fun systemInfo(): String = String(nSystemInfo(), Charsets.UTF_8)
    fun devices(): String = String(nDevices(), Charsets.UTF_8)
    fun procMem(): JSONObject = JSONObject(String(nProcMem(), Charsets.UTF_8))

    fun load(path: String, threads: Int, threadsBatch: Int, nCtx: Int, nBatch: Int): JSONObject =
        JSONObject(String(nLoad(path, threads, threadsBatch, nCtx, nBatch), Charsets.UTF_8))

    fun generate(
        prompt: String, chatTemplate: Boolean, nPredict: Int, ignoreEos: Boolean,
        temp: Float, seed: Int, onText: ((String) -> Boolean)?,
    ): JSONObject {
        val sink = onText?.let { cb -> PieceSink { bytes -> cb(String(bytes, Charsets.UTF_8)) } }
        return JSONObject(String(nGenerate(prompt, chatTemplate, nPredict, ignoreEos, temp, seed, sink), Charsets.UTF_8))
    }

    fun stop() = nStop()       // safe to call from any thread
    fun unload() = nUnload()

    private external fun nInit(libDir: String)
    private external fun nSystemInfo(): ByteArray
    private external fun nDevices(): ByteArray
    private external fun nProcMem(): ByteArray
    private external fun nLoad(path: String, threads: Int, threadsBatch: Int, nCtx: Int, nBatch: Int): ByteArray
    private external fun nGenerate(
        prompt: String, chatTemplate: Boolean, nPredict: Int, ignoreEos: Boolean,
        temp: Float, seed: Int, sink: PieceSink?,
    ): ByteArray
    private external fun nStop()
    private external fun nUnload()
}
