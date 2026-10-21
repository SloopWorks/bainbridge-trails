package com.bainbridgetrails.play.fx.effects

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaimPopTest {
    private val origin = Offset(200f, 600f)

    @Test
    fun `coalesces pops within 400 ms`() {
        val s = ClaimPopState(total = 1_000)
        s.pop(25, origin, nowMs = 0, reduced = false)
        s.pop(80, origin, nowMs = 300, reduced = false)
        assertEquals(1, s.inFlight.size, "a pop within 400 ms merges")
        assertEquals(105, s.inFlight.single().amount)
        s.pop(10, origin, nowMs = 800, reduced = false)
        assertEquals(2, s.inFlight.size, "800 ms later is a new pop")
        assertEquals(1_115, s.total)
        // A burst of 5 in 300 ms is one "+n".
        val b = ClaimPopState(total = 0)
        for (i in 0 until 5) b.pop(5, origin, nowMs = i * 75L, reduced = false)
        assertEquals(listOf(25), b.inFlight.map { it.amount })
        // Pops 401 ms apart fly separately (≤ 3 fit in one 900 ms flight); a later one within 400 ms joins the last.
        val m = ClaimPopState(total = 0)
        for (i in 0 until 3) m.pop(1 + i, origin, nowMs = i * 401L, reduced = false)
        m.pop(4, origin, nowMs = 880, reduced = false)
        assertEquals(listOf(1, 2, 3 + 4), m.inFlight.map { it.amount })
    }

    @Test
    fun `chip rolls to the new total when the pop lands`() {
        val s = ClaimPopState(total = 1_000)
        s.pop(50, origin, nowMs = 0, reduced = false)
        assertEquals(1_000, s.chipValue(nowMs = 600), "old value while the +n flies")
        assertEquals(1_050, s.chipValue(nowMs = ClaimPopState.LAND_MS))
        assertEquals(1f, s.chipScale(nowMs = 0))
        assertTrue(s.chipScale(nowMs = 800) > 1f, "bump on landing")
        assertEquals(1f, s.chipScale(nowMs = 900), 1e-3f)
        s.prune(nowMs = ClaimPopState.FLIGHT_MS)
        assertTrue(s.inFlight.isEmpty())
    }

    @Test
    fun `reduced shows +n beside HUD 1·5 s`() {
        val s = ClaimPopState(total = 1_000)
        s.pop(25, origin, nowMs = 0, reduced = true)
        s.pop(5, origin, nowMs = 200, reduced = true)
        assertTrue(s.inFlight.isEmpty(), "marks never fly under reduced motion")
        assertEquals(1_030, s.chipValue(nowMs = 0), "the chip crossfades straight to the new value")
        assertEquals(30, s.besideChip(nowMs = 0))
        assertEquals(30, s.besideChip(nowMs = 1_499))
        assertNull(s.besideChip(nowMs = 1_500), "gone after 1.5 s")
        assertEquals(1f, s.chipScale(nowMs = 800), "no bump")
        assertEquals(1_500L, ClaimPopState.REDUCED_LABEL_MS)
    }
}
