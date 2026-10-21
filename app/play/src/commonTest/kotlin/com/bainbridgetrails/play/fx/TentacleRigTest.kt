package com.bainbridgetrails.play.fx

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TentacleRigTest {
    private val timeline = FxTimeline(spawnMs = 700, runMs = 1_300, settleMs = 600, despawnMs = 400, reducedAtMs = 3_000)
    private val road = floatArrayOf(-120f, 10f, -60f, 4f, 0f, 0f, 60f, -6f, 120f, -14f)

    @Test
    fun `deterministic by seed`() {
        val a = TentacleRig(count = 5, segments = 14, seed = 42L)
        val b = TentacleRig(count = 5, segments = 14, seed = 42L)
        val c = TentacleRig(count = 5, segments = 14, seed = 43L)
        for (t in listOf(0, 350, 700, 1_234, 2_100, 2_800)) {
            a.pose(t, timeline, road, waterDx = 0f, waterDy = 1f, reduced = false)
            b.pose(t, timeline, road, waterDx = 0f, waterDy = 1f, reduced = false)
            c.pose(t, timeline, road, waterDx = 0f, waterDy = 1f, reduced = false)
            assertContentEquals(a.spine, b.spine)
            assertContentEquals(a.width, b.width)
        }
        a.pose(1_234, timeline, road, 0f, 1f, false)
        c.pose(1_234, timeline, road, 0f, 1f, false)
        assertFalse(a.spine.contentEquals(c.spine), "another seed varies the pose")
        assertTrue(a.spine.all { it.isFinite() } && a.width.all { it.isFinite() && it >= 0f })
    }

    @Test
    fun `reduced pose is time independent`() {
        val r = TentacleRig(count = 4, segments = 10, seed = 7L)
        r.pose(100, timeline, road, 0f, 1f, reduced = true)
        val spine = r.spine.copyOf()
        val width = r.width.copyOf()
        for (t in listOf(0, 900, 1_700, 2_999, 50_000)) {
            r.pose(t, timeline, road, 0f, 1f, reduced = true)
            assertContentEquals(spine, r.spine)
            assertContentEquals(width, r.width)
        }
    }

    @Test
    fun `pose writes only into preallocated arrays`() {
        val r = TentacleRig(count = 3, segments = 10, seed = 1L)
        val spine = r.spine
        val width = r.width
        r.pose(1_000, timeline, road, 0f, 1f, reduced = false)
        assertSame(spine, r.spine)
        assertSame(width, r.width)
        assertTrue(spine.any { it != 0f }, "pose wrote the spine")
        assertTrue(width.any { it != 0f }, "pose wrote the widths")
        // An empty road (no vertices) still poses without allocating new arrays.
        r.pose(1_000, timeline, FloatArray(0), 0f, 1f, reduced = false)
        assertSame(spine, r.spine)
    }
}
