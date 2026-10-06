package dev.rq.llmceiling

import kotlin.math.sqrt

/** Pure timing arithmetic, unit-tested on the JVM. */
object BenchMath {
    /** tokens per second, or null when not computable. */
    fun tps(tokens: Int, ms: Double): Double? =
        if (tokens <= 0 || ms <= 0.0 || ms.isNaN()) null else tokens * 1000.0 / ms

    /** Prefill rate: all prompt tokens are processed by the prefill decode calls. */
    fun prefillTps(nPrompt: Int, prefillMs: Double) = tps(nPrompt, prefillMs)

    /** Decode rate: one forward pass per generated token after the first (the first token is
     *  sampled from the prefill logits), so it is n_decode_calls / time spent in those calls. */
    fun decodeTps(nDecodeCalls: Int, decodeMs: Double) = tps(nDecodeCalls, decodeMs)

    data class Summary(val n: Int, val mean: Double, val median: Double, val sd: Double, val min: Double, val max: Double)

    fun summarize(values: List<Double>): Summary? {
        val v = values.filter { !it.isNaN() }.sorted()
        if (v.isEmpty()) return null
        val mean = v.average()
        val median = if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2.0
        val sd = if (v.size > 1) sqrt(v.sumOf { (it - mean) * (it - mean) } / (v.size - 1)) else 0.0
        return Summary(v.size, mean, median, sd, v.first(), v.last())
    }
}
