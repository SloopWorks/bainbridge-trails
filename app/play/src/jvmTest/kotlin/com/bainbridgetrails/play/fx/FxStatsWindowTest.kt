package com.bainbridgetrails.play.fx

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** jvmTest: the allocation check needs com.sun.management.ThreadMXBean. */
class FxStatsWindowTest {
    @Test
    fun `p50 p95 p99 over ring`() {
        val w = FxStatsWindow(capacity = 100)
        assertEquals(0f, w.p50)
        // 1..300 into a ring of 100 keeps 201..300.
        for (v in 1..300) w.add(v.toFloat())
        assertEquals(100, w.count)
        assertEquals(250f, w.p50)
        assertEquals(295f, w.p95)
        assertEquals(299f, w.p99)
        assertEquals(300f, w.percentile(100.0))
        // Partially filled ring.
        val small = FxStatsWindow(capacity = 100)
        listOf(5f, 1f, 3f, 2f, 4f).forEach(small::add)
        assertEquals(3f, small.p50)
        assertEquals(5f, small.p99)
    }

    @Test
    fun `no allocation on add`() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val w = FxStatsWindow(capacity = 240)
        val tid = Thread.currentThread().id
        repeat(10_000) { w.add(it * 0.01f) } // warm up (JIT)
        val before = bean.getThreadAllocatedBytes(tid)
        for (i in 0 until 100_000) w.add(i * 0.01f)
        val after = bean.getThreadAllocatedBytes(tid)
        assertEquals(0L, after - before, "bytes allocated by 100k add()")
    }
}
