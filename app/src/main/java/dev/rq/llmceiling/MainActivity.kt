package dev.rq.llmceiling

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Deliberately plain UI (no AndroidX). All native calls run on a single worker thread.
 *
 * adb automation (all inference still happens on the phone):
 *   am start -n dev.rq.llmceiling/.MainActivity --ez bench true            -> runs files/bench-config.json
 *   am start -n dev.rq.llmceiling/.MainActivity --es model X.gguf --es prompt "..." [--ei n_predict 128]
 *                                                                           -> one generation, saved to session "adhoc"
 */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var probe: DeviceProbe
    private lateinit var store: ResultStore
    private lateinit var modelsDir: File

    private lateinit var header: TextView
    private lateinit var modelSpinner: Spinner
    private lateinit var threadsEdit: EditText
    private lateinit var ctxEdit: EditText
    private lateinit var promptEdit: EditText
    private lateinit var output: TextView
    private lateinit var stats: TextView
    private lateinit var logView: TextView
    private lateinit var outScroll: ScrollView

    @Volatile private var loadedModel: String? = null
    @Volatile private var busy = false
    private var runner: BenchRunner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        probe = DeviceProbe(this)
        modelsDir = getExternalFilesDir("models")!!.also { it.mkdirs() }
        store = ResultStore(getExternalFilesDir("results")!!)
        buildUi()

        store.recoverInflight()?.let { log("previous run died during: ${it.optJSONObject("during")}") }

        worker.execute {
            try {
                NativeLlama.init(applicationInfo.nativeLibraryDir)
                log("llama.cpp ready. backends: ${NativeLlama.devices()}")
                log(NativeLlama.systemInfo())
            } catch (t: Throwable) {
                log("NATIVE INIT FAILED: $t")
            }
        }
        handleIntent()
    }

    private fun handleIntent() {
        val i = intent ?: return
        if (i.getBooleanExtra("bench", false)) { startBench(); return }
        val model = i.getStringExtra("model") ?: return
        val prompt = i.getStringExtra("prompt") ?: "Explain in two sentences what a CPU cache is."
        val nPredict = i.getIntExtra("n_predict", 128)
        val threads = i.getIntExtra("threads", 6)
        promptEdit.setText(prompt)
        worker.execute {
            if (!doLoad(model, threads, 2048)) return@execute
            doGenerate(prompt, nPredict, session = "adhoc")
        }
    }

    // ------------------------------------------------------------------ UI
    private fun buildUi() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun row(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; v.forEach { addView(it) } }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }
        fun num(hint: String, v: Int) = EditText(this).apply {
            this.hint = hint; setText(v.toString()); inputType = InputType.TYPE_CLASS_NUMBER; minEms = 3
        }

        header = TextView(this).apply { textSize = 13f; setTypeface(typeface, Typeface.BOLD) }
        modelSpinner = Spinner(this)
        threadsEdit = num("threads", 6)
        ctxEdit = num("n_ctx", 2048)
        promptEdit = EditText(this).apply { hint = "Prompt"; minLines = 2; setText("Explain in two sentences what a CPU cache is.") }
        output = TextView(this).apply { setTextIsSelectable(true); textSize = 15f }
        stats = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f }
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 10f }

        root.addView(header)
        root.addView(row(TextView(this).apply { text = "Model " }, modelSpinner, btn("↻") { refreshModels() }))
        root.addView(row(TextView(this).apply { text = "threads " }, threadsEdit, TextView(this).apply { text = "  ctx " }, ctxEdit,
            btn("Load") { onLoad() }, btn("Unload") { onUnload() }))
        root.addView(promptEdit)
        root.addView(row(btn("Generate") { onGenerate() }, btn("Stop") { NativeLlama.stop(); runner?.cancelled = true },
            btn("Run benchmark") { startBench() }))
        outScroll = ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; addView(output); addView(stats); addView(logView)
            })
        }
        root.addView(outScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refreshModels()
        refreshHeader()
    }

    private fun refreshHeader() {
        val net = probe.networkState()
        header.text = "100% on-device · llama.cpp CPU (arm64-v8a) · app has no INTERNET permission · phone network: $net" +
                (loadedModel?.let { "\nloaded: $it" } ?: "\nno model loaded")
    }

    private fun refreshModels() {
        val names = modelsDir.listFiles { f -> f.name.endsWith(".gguf") }?.map { it.name }?.sorted().orEmpty()
        modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            names.ifEmpty { listOf("(no .gguf in ${modelsDir.path})") })
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        android.util.Log.i("llmceiling", msg)
        runOnUiThread { logView.append("[$ts] $msg\n") }
    }

    // ------------------------------------------------------------------ actions
    private fun guard(): Boolean { if (busy) { log("busy"); return false }; return true }

    private fun onLoad() {
        if (!guard()) return
        val model = modelSpinner.selectedItem?.toString() ?: return
        val t = threadsEdit.text.toString().toIntOrNull() ?: 6
        val c = ctxEdit.text.toString().toIntOrNull() ?: 2048
        worker.execute { doLoad(model, t, c) }
    }

    private fun onUnload() {
        if (!guard()) return
        worker.execute { NativeLlama.unload(); loadedModel = null; runOnUiThread { refreshHeader() }; log("unloaded") }
    }

    private fun onGenerate() {
        if (!guard()) return
        val p = promptEdit.text.toString()
        worker.execute { doGenerate(p, 256, session = "interactive") }
    }

    private fun startBench() {
        if (!guard()) return
        val cfgFile = File(getExternalFilesDir(null), "bench-config.json")
        worker.execute {
            busy = true
            try {
                if (!cfgFile.exists()) { log("missing ${cfgFile.path}"); return@execute }
                val cfg = BenchConfig.parse(cfgFile.readText())
                log("benchmark '${cfg.session}': ${cfg.jobs.size} jobs")
                NativeLlama.unload(); loadedModel = null
                runner = BenchRunner(probe, modelsDir, store, ::log).also { it.run(cfg) }
            } catch (t: Throwable) {
                log("BENCH ERROR: $t")
            } finally {
                busy = false; runner = null
                runOnUiThread { refreshHeader() }
            }
        }
    }

    /** Worker thread only. */
    private fun doLoad(model: String, threads: Int, nCtx: Int): Boolean {
        val f = File(modelsDir, model)
        if (!f.exists()) { log("model not found: ${f.path}"); return false }
        busy = true
        try {
            log("loading $model …")
            store.markInflight("interactive", JSONObject().put("event", "load").put("model_file", model))
            val r = NativeLlama.load(f.absolutePath, threads, threads, nCtx, 512)
            store.clearInflight()
            store.append("interactive", JSONObject().put("event", "load").put("model_file", model)
                .put("file_size_bytes", f.length()).put("result", r).put("state", probe.dynamicState()))
            if (!r.optBoolean("ok")) {
                log("LOAD FAILED: ${r.optString("error")}")
                loadedModel = null
                return false
            }
            loadedModel = model
            log("loaded ${r.optString("desc")} in ${"%.2f".format(r.optDouble("load_ms") / 1000)} s, " +
                    "RSS ${r.getJSONObject("mem_after_ctx").optLong("vm_rss_kb") / 1024} MiB")
            return true
        } finally {
            busy = false
            runOnUiThread { refreshHeader() }
        }
    }

    /** Worker thread only. */
    private fun doGenerate(prompt: String, nPredict: Int, session: String) {
        if (loadedModel == null) { log("load a model first"); return }
        busy = true
        runOnUiThread { output.text = ""; stats.text = "" }
        try {
            val before = probe.dynamicState()
            store.markInflight(session, JSONObject().put("event", "generate").put("model_file", loadedModel))
            val r = NativeLlama.generate(prompt, true, nPredict, false, 0f, 42) { piece ->
                runOnUiThread { output.append(piece) }
                true
            }
            store.clearInflight()
            val pp = BenchMath.prefillTps(r.optInt("n_prompt"), r.optDouble("prefill_ms"))
            val tg = BenchMath.decodeTps(r.optInt("n_decode_calls"), r.optDouble("decode_ms"))
            store.append(session, JSONObject().put("event", "generate").put("model_file", loadedModel)
                .put("prompt", prompt).put("n_predict", nPredict).put("result", r)
                .put("prefill_tps", pp ?: JSONObject.NULL).put("decode_tps", tg ?: JSONObject.NULL)
                .put("state_before", before).put("state_after", probe.dynamicState()))
            val s = String.format(Locale.US,
                "prompt %d tok · prefill %.0f ms (%.1f t/s)\ngen %d tok · decode %.1f t/s · TTFT %.0f ms · total %.1f s · stop=%s\nRSS %d MiB · net=%s",
                r.optInt("n_prompt"), r.optDouble("prefill_ms"), pp ?: Double.NaN, r.optInt("n_gen"), tg ?: Double.NaN,
                r.optDouble("ttft_ms"), r.optDouble("wall_ms") / 1000, r.optString("stop_reason"),
                r.getJSONObject("mem_after").optLong("vm_rss_kb") / 1024, probe.networkState())
            runOnUiThread { stats.text = s }
            log(s.replace('\n', ' ') + if (r.optBoolean("ok")) "" else " ERROR ${r.optString("error")}")
        } finally {
            busy = false
            runOnUiThread { refreshHeader() }
        }
    }

    override fun onDestroy() {
        NativeLlama.stop()
        runner?.cancelled = true
        super.onDestroy()
    }
}
