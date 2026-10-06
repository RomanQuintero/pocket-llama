package dev.rq.llmceiling

import org.json.JSONObject
import java.io.File

/**
 * Append-only JSON Lines storage. Every record is flushed immediately, so if the process is
 * killed (e.g. by the low-memory killer while loading a large model) everything measured
 * before that point survives. An "inflight" marker records what was running; if it still
 * exists at the next start, the previous attempt died and that is written down as a result.
 */
class ResultStore(private val dir: File) {
    init { dir.mkdirs() }

    private val inflight = File(dir, "inflight.json")

    fun fileFor(session: String) = File(dir, "${sanitize(session)}.jsonl")

    @Synchronized
    fun append(session: String, record: JSONObject) {
        record.put("session", session)
        fileFor(session).appendText(record.toString() + "\n")
    }

    fun readAll(session: String): List<JSONObject> {
        val f = fileFor(session)
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
    }

    fun markInflight(session: String, what: JSONObject) {
        what.put("session", session)
        inflight.writeText(what.toString())
    }

    fun clearInflight() { inflight.delete() }

    /** If the previous process died mid-operation, log it and return the record written. */
    fun recoverInflight(now: Long = System.currentTimeMillis()): JSONObject? {
        if (!inflight.exists()) return null
        val prev = runCatching { JSONObject(inflight.readText()) }.getOrNull()
        inflight.delete()
        prev ?: return null
        val session = prev.optString("session", "unknown")
        val rec = JSONObject().apply {
            put("event", "process_died")
            put("detected_at_ms", now)
            put("during", prev)
            put("note", "app process ended while this operation was running (likely low-memory kill or crash); see logcat")
        }
        append(session, rec)
        return rec
    }

    companion object {
        fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "session" }
    }
}
