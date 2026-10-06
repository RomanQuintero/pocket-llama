package dev.rq.llmceiling

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Benchmark configuration (bench-config.json). Every job inherits `defaults` unless overridden. */
data class BenchJob(
    val model: String,
    val threads: Int,
    val nCtx: Int,
    val nBatch: Int,
    val nPredict: Int,
    val temp: Float,
    val seed: Int,
    val ignoreEos: Boolean,
    val chatTemplate: Boolean,
    val reps: Int,
    val warmup: Boolean,
    val prompts: List<String>,
    val cooldownS: Int,
    val tag: String,
)

data class BenchConfig(val session: String, val prompts: Map<String, String>, val jobs: List<BenchJob>) {
    companion object {
        fun parse(text: String): BenchConfig {
            val root = JSONObject(text)
            val d = root.optJSONObject("defaults") ?: JSONObject()
            val promptsObj = root.getJSONObject("prompts")
            val prompts = promptsObj.keys().asSequence().associateWith { promptsObj.getString(it) }
            val jobsArr = root.getJSONArray("jobs")
            val jobs = (0 until jobsArr.length()).map { i ->
                val j = jobsArr.getJSONObject(i)
                fun int(k: String, def: Int) = if (j.has(k)) j.getInt(k) else d.optInt(k, def)
                fun bool(k: String, def: Boolean) = if (j.has(k)) j.getBoolean(k) else d.optBoolean(k, def)
                fun dbl(k: String, def: Double) = if (j.has(k)) j.getDouble(k) else d.optDouble(k, def)
                val pr = (j.optJSONArray("prompts") ?: d.optJSONArray("prompts") ?: JSONArray(prompts.keys.toList()))
                val promptIds = (0 until pr.length()).map { pr.getString(it) }
                promptIds.forEach { require(it in prompts) { "job $i references unknown prompt '$it'" } }
                BenchJob(
                    model = j.getString("model"),
                    threads = int("threads", 6),
                    nCtx = int("n_ctx", 2048),
                    nBatch = int("n_batch", 512),
                    nPredict = int("n_predict", 128),
                    temp = dbl("temp", 0.0).toFloat(),
                    seed = int("seed", 42),
                    ignoreEos = bool("ignore_eos", true),
                    chatTemplate = bool("chat_template", true),
                    reps = int("reps", 3),
                    warmup = bool("warmup", true),
                    prompts = promptIds,
                    cooldownS = int("cooldown_s", 60),
                    tag = j.optString("tag", d.optString("tag", "ladder")),
                )
            }
            return BenchConfig(root.getString("session"), prompts, jobs)
        }
    }
}

/**
 * Runs a BenchConfig strictly sequentially on the calling (worker) thread.
 * Load, warm-up and measured runs are separate records; each is flushed to disk immediately.
 * Jobs already completed in the same session are skipped, so the suite can be relaunched
 * after the process is killed (the killed job is recorded by ResultStore.recoverInflight).
 */
class BenchRunner(
    private val probe: DeviceProbe,
    private val modelsDir: File,
    private val store: ResultStore,
    private val log: (String) -> Unit,
) {
    @Volatile var cancelled = false

    fun run(cfg: BenchConfig) {
        val session = cfg.session
        val done = store.readAll(session).filter { it.optString("event") == "job_done" || it.optString("event") == "job_failed" }
            .map { it.optInt("job_index", -1) }.toSet()
        store.append(session, JSONObject().apply {
            put("event", "suite_start"); put("device", probe.staticInfo()); put("state", probe.dynamicState())
            put("system_info", NativeLlama.systemInfo()); put("backend_devices", NativeLlama.devices())
            put("skipping_jobs", JSONArray(done.toList().sorted()))
        })

        for ((idx, job) in cfg.jobs.withIndex()) {
            if (cancelled) break
            if (idx in done) { log("job $idx (${job.model}) already recorded, skipping"); continue }
            runJob(session, idx, job, cfg.prompts)
            if (cancelled) break
            if (idx < cfg.jobs.size - 1) cooldown(session, idx, job.cooldownS)
        }
        store.append(session, JSONObject().apply { put("event", "suite_end"); put("cancelled", cancelled); put("state", probe.dynamicState()) })
        log("suite finished${if (cancelled) " (cancelled)" else ""}")
    }

    private fun base(event: String, idx: Int, job: BenchJob) = JSONObject().apply {
        put("event", event); put("job_index", idx); put("tag", job.tag); put("model_file", job.model)
        put("settings", JSONObject().apply {
            put("threads", job.threads); put("n_ctx", job.nCtx); put("n_batch", job.nBatch)
            put("n_predict", job.nPredict); put("temp", job.temp.toDouble()); put("seed", job.seed)
            put("ignore_eos", job.ignoreEos); put("chat_template", job.chatTemplate)
        })
    }

    private fun runJob(session: String, idx: Int, job: BenchJob, prompts: Map<String, String>) {
        val file = File(modelsDir, job.model)
        if (!file.exists()) {
            store.append(session, base("job_failed", idx, job).apply { put("reason", "model file not found: ${file.path}") })
            log("job $idx: model missing ${file.name}")
            return
        }
        log("job $idx: loading ${job.model} (${file.length() / 1_048_576} MiB, ${job.threads} threads)")
        val pre = probe.dynamicState()
        store.markInflight(session, base("load", idx, job).apply { put("file_size_bytes", file.length()) })
        val load = NativeLlama.load(file.absolutePath, job.threads, job.threads, job.nCtx, job.nBatch)
        store.clearInflight()
        store.append(session, base("load", idx, job).apply {
            put("file_size_bytes", file.length()); put("state_before", pre); put("state_after", probe.dynamicState())
            put("result", load)
        })
        if (!load.optBoolean("ok")) {
            store.append(session, base("job_failed", idx, job).apply { put("reason", load.optString("error")) })
            log("job $idx: LOAD FAILED: ${load.optString("error")}")
            return
        }
        log("job $idx: loaded in ${"%.1f".format(load.optDouble("load_ms") / 1000)} s; ${load.optString("desc")}")

        var runSeq = 0
        fun gen(promptId: String, rep: Int, warm: Boolean) {
            val st = probe.dynamicState()
            val rec = base(if (warm) "warmup" else "run", idx, job).apply {
                put("prompt_id", promptId); put("rep", rep); put("seq_in_job", runSeq++)
            }
            store.markInflight(session, JSONObject(rec.toString()))
            val r = NativeLlama.generate(
                prompts.getValue(promptId), job.chatTemplate,
                if (warm) minOf(16, job.nPredict) else job.nPredict,
                job.ignoreEos, job.temp, job.seed, null,
            )
            store.clearInflight()
            rec.put("state_before", st); rec.put("state_after", probe.dynamicState())
            rec.put("result", r)
            rec.put("prefill_tps", BenchMath.prefillTps(r.optInt("n_prompt"), r.optDouble("prefill_ms")) ?: JSONObject.NULL)
            rec.put("decode_tps", BenchMath.decodeTps(r.optInt("n_decode_calls"), r.optDouble("decode_ms")) ?: JSONObject.NULL)
            store.append(session, rec)
            if (!warm) log("job $idx $promptId#$rep: pp ${fmt(rec.opt("prefill_tps"))} t/s, tg ${fmt(rec.opt("decode_tps"))} t/s, " +
                    "${r.optInt("n_gen")} tok, ${r.optString("stop_reason")}${if (r.optBoolean("ok")) "" else " ERROR " + r.optString("error")}")
        }

        if (job.warmup && !cancelled) gen(job.prompts.first(), -1, true)
        // interleave prompts within each repetition so slow drift affects all prompts alike
        for (rep in 0 until job.reps) for (p in job.prompts) { if (cancelled) break; gen(p, rep, false) }

        NativeLlama.unload()
        store.append(session, base(if (cancelled) "job_cancelled" else "job_done", idx, job).apply {
            put("mem_after_unload", NativeLlama.procMem()); put("state", probe.dynamicState())
        })
    }


    private fun cooldown(session: String, idx: Int, seconds: Int) {
        if (seconds <= 0) return
        log("cooldown ${seconds}s")
        val start = probe.dynamicState()
        val until = System.currentTimeMillis() + seconds * 1000L
        while (!cancelled && System.currentTimeMillis() < until) Thread.sleep(1000)
        store.append(session, JSONObject().apply {
            put("event", "cooldown"); put("after_job", idx); put("seconds", seconds)
            put("state_before", start); put("state_after", probe.dynamicState())
        })
    }

    private fun fmt(v: Any?) = if (v is Number) "%.2f".format(v.toDouble()) else "n/a"
}
