package dev.rq.llmceiling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchMathTest {
    @Test fun tpsBasic() = assertEquals(20.0, BenchMath.tps(10, 500.0)!!, 1e-9)
    @Test fun tpsZeroTime() = assertNull(BenchMath.tps(10, 0.0))
    @Test fun tpsNoTokens() = assertNull(BenchMath.tps(0, 100.0))
    @Test fun tpsNaN() = assertNull(BenchMath.tps(5, Double.NaN))

    @Test fun decodeUsesForwardPassesNotGeneratedTokens() {
        // 128 generated tokens -> 127 forward passes (first token comes from prefill logits)
        assertEquals(127.0, BenchMath.decodeTps(127, 1000.0)!!, 1e-9)
    }

    @Test fun summaryOdd() {
        val s = BenchMath.summarize(listOf(3.0, 1.0, 2.0))!!
        assertEquals(2.0, s.median, 1e-9); assertEquals(2.0, s.mean, 1e-9)
        assertEquals(1.0, s.sd, 1e-9); assertEquals(1.0, s.min, 1e-9); assertEquals(3.0, s.max, 1e-9)
    }

    @Test fun summaryEvenAndSingle() {
        assertEquals(2.5, BenchMath.summarize(listOf(1.0, 2.0, 3.0, 4.0))!!.median, 1e-9)
        assertEquals(0.0, BenchMath.summarize(listOf(7.0))!!.sd, 1e-9)
        assertNull(BenchMath.summarize(emptyList()))
    }
}
