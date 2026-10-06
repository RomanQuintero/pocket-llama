package dev.rq.llmceiling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BenchConfigTest {
    private val cfg = """
        {"session":"s","defaults":{"threads":6,"n_predict":128,"reps":3,"prompts":["short"]},
         "prompts":{"short":"hi","long":"long text"},
         "jobs":[{"model":"a.gguf"},{"model":"b.gguf","threads":4,"prompts":["short","long"],"reps":1}]}
    """.trimIndent()

    @Test fun defaultsAndOverrides() {
        val c = BenchConfig.parse(cfg)
        assertEquals(2, c.jobs.size)
        assertEquals(6, c.jobs[0].threads); assertEquals(listOf("short"), c.jobs[0].prompts); assertEquals(3, c.jobs[0].reps)
        assertEquals(4, c.jobs[1].threads); assertEquals(listOf("short", "long"), c.jobs[1].prompts); assertEquals(1, c.jobs[1].reps)
        assertEquals(0.0f, c.jobs[0].temp); assertEquals(true, c.jobs[0].ignoreEos)
    }

    @Test fun unknownPromptRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BenchConfig.parse("""{"session":"s","prompts":{"a":"x"},"jobs":[{"model":"m","prompts":["zzz"]}]}""")
        }
    }
}
