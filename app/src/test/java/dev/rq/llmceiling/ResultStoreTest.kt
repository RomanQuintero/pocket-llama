package dev.rq.llmceiling

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResultStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun appendAndReadBack() {
        val s = ResultStore(tmp.newFolder())
        s.append("ladder v1", JSONObject().put("event", "run").put("text", "línea\n\"quoted\" 😀"))
        s.append("ladder v1", JSONObject().put("event", "run").put("x", 2))
        val all = s.readAll("ladder v1")
        assertEquals(2, all.size)
        assertEquals("línea\n\"quoted\" 😀", all[0].getString("text"))
        assertEquals("ladder v1", all[1].getString("session"))
        assertEquals("ladder_v1.jsonl", s.fileFor("ladder v1").name)
    }

    @Test fun corruptLineIsSkipped() {
        val dir = tmp.newFolder()
        val s = ResultStore(dir)
        s.append("a", JSONObject().put("ok", 1))
        s.fileFor("a").appendText("{truncated by kill\n")
        assertEquals(1, s.readAll("a").size)
    }

    @Test fun inflightRecoveryRecordsDeath() {
        val dir = tmp.newFolder()
        val s1 = ResultStore(dir)
        s1.markInflight("big", JSONObject().put("event", "load").put("model_file", "14B.gguf"))
        // simulate a new process
        val s2 = ResultStore(dir)
        val rec = s2.recoverInflight(now = 123L)
        assertNotNull(rec)
        val stored = s2.readAll("big").single()
        assertEquals("process_died", stored.getString("event"))
        assertEquals("14B.gguf", stored.getJSONObject("during").getString("model_file"))
        assertNull(s2.recoverInflight())
    }

    @Test fun clearedInflightIsNotReported() {
        val s = ResultStore(tmp.newFolder())
        s.markInflight("x", JSONObject().put("event", "generate"))
        s.clearInflight()
        assertNull(s.recoverInflight())
    }
}
