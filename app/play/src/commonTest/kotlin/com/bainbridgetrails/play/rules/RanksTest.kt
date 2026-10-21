package com.bainbridgetrails.play.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** product §3.7.1, PF12. */
class RanksTest {
    @Test
    fun `2291 → Salal Scout — 709 to Madrona Wanderer`() {
        assertEquals("Salal Scout", Ranks.of(2_291).title)
        assertEquals(4, Ranks.of(2_291).number)
        assertEquals("Madrona Wanderer", Ranks.next(2_291)?.title)
        assertEquals(709, Ranks.toNext(2_291))
    }

    @Test
    fun `the ladder`() {
        assertEquals(
            listOf(
                "Walk-on" to 0, "Dock Walker" to 250, "Beachcomber" to 750, "Salal Scout" to 1_500,
                "Madrona Wanderer" to 3_000, "Heron Watcher" to 5_000, "Fog Navigator" to 8_000, "Tide Reader" to 12_000,
                "Kraken Tamer" to 17_000, "Island Cartographer" to 23_000, "Keeper of the Chart" to 30_000,
            ),
            Ranks.LADDER.map { it.title to it.from },
        )
        assertEquals((1..11).toList(), Ranks.LADDER.map { it.number })
        assertEquals("Walk-on", Ranks.of(0).title)
        assertEquals("Dock Walker", Ranks.of(250).title)
        assertEquals("Walk-on", Ranks.of(249).title)
        assertEquals("Keeper of the Chart", Ranks.of(1_000_000).title)
        assertNull(Ranks.next(30_000))
        assertNull(Ranks.toNext(30_000))
        // rank-up on a walk (§3.9: Dana 2,920 → 3,115; Marcus 1,495 → 2,135)
        assertEquals("Madrona Wanderer", Ranks.rankUp(2_920, 3_115)?.title)
        assertEquals("Salal Scout", Ranks.rankUp(1_495, 2_135)?.title)
        assertNull(Ranks.rankUp(1_500, 2_000))
    }
}
