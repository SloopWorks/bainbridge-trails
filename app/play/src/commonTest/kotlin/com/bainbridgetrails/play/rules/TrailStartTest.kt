package com.bainbridgetrails.play.rules

import com.bainbridgetrails.play.config.Goal
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan D4 / ux § 6: the trails rules' share of the start balance, on every turn-on (brief AC 3, UX-7). */
class TrailStartTest {
    private val day = LocalDate(2026, 10, 31)
    private val later = LocalDate(2026, 11, 7)
    private var n = 0
    private val newId = { "e${n++}" }

    private val prior = setOf("climb:100", "high:trail-b", "park1:Alpha Park", "parkc:Alpha Park", "island")

    @Test
    fun `opt-in with prior progress writes start_balance then start_trails then island`() {
        val start = StartBalance.onEnable(Logbook(), WalkDerived(500, 200, 75), day, newId)
        val on = TrailStart.onEnable(Goal.trails, TrailLedger(start), prior, day, newId)
        assertEquals(listOf(LogKind.start_balance, LogKind.start_trails, LogKind.island), on.logbook.entries.map { it.kind })
        val trails = on.logbook.entries[1]
        assertEquals("Trail rewards so far", trails.what)
        assertEquals("Climb, trail miles, parks and high ground already walked", trails.why)
        assertEquals(50 + 50 + 50 + 150, trails.marks)
        assertNull(trails.walkId)
        assertNull(trails.contentId)
        val island = on.logbook.entries[2]
        assertEquals(1_000, island.marks)
        assertEquals("island", island.contentId)
        assertEquals("The whole island", island.what)
        assertEquals("All of the island's trails walked", island.why)
        assertNull(island.walkId)
        assertTrue(on.trailsStarted)
        assertEquals(prior, on.trailPaid)
        assertEquals(775 + 300 + 1_000, on.logbook.total)
        // the trails entries are not walk-derived: StartBalance's later catch-up does not count them
        assertEquals(on.logbook, StartBalance.onEnable(on.logbook, WalkDerived(500, 200, 75), later, newId))
    }

    @Test
    fun `off then on with nothing new writes nothing`() {
        val on = TrailStart.onEnable(Goal.trails, TrailLedger(Logbook()), prior, day, newId)
        assertEquals(on, TrailStart.onEnable(Goal.trails, on, prior, later, newId))
        // a player with nothing to credit yet: no entry, but the trails start is recorded
        val empty = TrailStart.onEnable(Goal.trails, TrailLedger(Logbook()), emptySet(), day, newId)
        assertEquals(TrailLedger(Logbook(), emptySet(), trailsStarted = true), empty)
        // only the island owed: its own entry, no start_trails
        val islandOnly = TrailStart.onEnable(Goal.trails, TrailLedger(Logbook()), setOf("island"), day, newId)
        assertEquals(listOf(LogKind.island), islandOnly.logbook.entries.map { it.kind })
    }

    @Test
    fun `off then on with new keys writes one Trail rewards caught up`() {
        val on = TrailStart.onEnable(Goal.trails, TrailLedger(Logbook()), prior, day, newId)
        val more = prior + setOf("mile:1", "park1:Beta Park")
        val again = TrailStart.onEnable(Goal.trails, on, more, later, newId)
        val added = again.logbook.entries.drop(on.logbook.entries.size)
        assertEquals(1, added.size)
        val e = added.single()
        assertEquals(LogKind.start_trails, e.kind)
        assertEquals("Trail rewards caught up", e.what)
        assertEquals("Trail rewards owed from earlier walks", e.why)
        assertEquals(100, e.marks)
        assertEquals(later, e.date)
        assertEquals(more, again.trailPaid)
    }

    @Test
    fun `keys in Logbook contentIds are paid even with empty trailPaid`() {
        val walked = Logbook(
            TrailRewards.entries(listOf("park1:Alpha Park", "parkc:Alpha Park", "high:trail-b"), { "Bee Trail" }, day, "w1", newId) +
                LogEntry("i", LogKind.island, "The whole island", "All of the island's trails walked", day, 1_000, contentId = "island"),
        )
        val ledger = TrailLedger(walked, trailPaid = emptySet(), trailsStarted = false)
        val on = TrailStart.onEnable(Goal.trails, ledger, setOf("park1:Alpha Park", "parkc:Alpha Park", "high:trail-b", "island"), later, newId)
        assertEquals(walked, on.logbook)
        val owed = TrailStart.onEnable(Goal.trails, ledger, setOf("park1:Alpha Park", "climb:100"), later, newId)
        assertEquals(50, owed.logbook.entries.single { it.kind == LogKind.start_trails }.marks)
    }

    @Test
    fun `roads never writes start_trails`() {
        val start = TrailLedger(StartBalance.onEnable(Logbook(), WalkDerived(500, 200, 75), day, newId))
        val on = TrailStart.onEnable(Goal.roads, start, prior, day, newId)
        assertEquals(start, on)
        assertFalse(on.trailsStarted)
        assertTrue(on.logbook.entries.none { it.kind == LogKind.start_trails || it.kind == LogKind.island })
    }
}
