package com.bainbridgetrails.play.schedule

import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.Scope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** schedule.md §3.4 #4: ≤ 3 placements per chart square, ≤ 40 island-wide; fill order heroes … eggs, then personal. */
class DensityTest {
    private val t0 = Instant.parse("2027-01-07T14:00:00Z")

    private fun occ(id: String, type: ItemType, vararg squares: String, hero: Boolean = false, scope: Scope = Scope.island, hidden: Boolean = false) = Occurrence(
        key = "$id@2027-01-07", itemId = id, type = type, scope = scope, start = t0, end = null, hero = hero,
        placements = squares.mapIndexed { i, sq ->
            ResolvedPlacement("$id@2027-01-07#$i", i, "place:$id-$i", null, emptyList(), GeoPoint(47.6, -122.5), sq)
        },
        marks = 0, variant = null, sensable = true, daySpans = emptyList(), resolvable = emptyList(), tideGated = false, hidden = hidden,
    )

    private fun keptIds(r: Density.Result) = r.occurrences.flatMap { o -> o.placements.map { o.itemId } }

    @Test
    fun `4 placements in one cell keep 3 by fill order`() {
        val input = listOf(
            occ("egg-a", ItemType.egg, "5:5"),
            occ("treasure-w", ItemType.treasure, "5:5"),
            occ("riddle-r", ItemType.riddle, "5:5"),
            occ("kraken-k", ItemType.obstacle, "5:5", hero = true),
        )
        for (order in listOf(input, input.reversed(), listOf(input[2], input[0], input[3], input[1]))) {
            val r = Density.apply(order, maxPerCell = 3, maxVisible = 40)
            assertEquals(setOf("kraken-k", "riddle-r", "treasure-w"), keptIds(r).toSet())
            assertEquals(listOf("egg-a@2027-01-07#0"), r.dropped)
        }
    }

    @Test
    fun `a hero obstacle counts once per square`() {
        val kraken = occ("kraken-k", ItemType.obstacle, "5:5", "5:5", "5:5", hero = true)
        val r = Density.apply(listOf(kraken, occ("treasure-w", ItemType.treasure, "5:5"), occ("riddle-r", ItemType.riddle, "5:5")), 3, 40)
        assertEquals(5, keptIds(r).size)
        assertTrue(r.dropped.isEmpty())
    }

    @Test
    fun `island never displaced by personal`() {
        val personal = occ("lore-owl", ItemType.creature, "2:2", "2:2", scope = Scope.personal)
        val island = listOf(occ("egg-a", ItemType.egg, "2:2"), occ("egg-b", ItemType.egg, "2:2"), occ("lore-l", ItemType.lore, "2:2"))
        val r = Density.apply(listOf(personal) + island, 3, 40)
        assertEquals(setOf("egg-a", "egg-b", "lore-l"), keptIds(r).toSet())
        assertEquals(2, r.dropped.size)
        assertTrue(r.dropped.all { it.startsWith("lore-owl") })
    }

    @Test
    fun `at most 40 visible island wide`() {
        val many = occ("treasure-w", ItemType.treasure, *Array(45) { "$it:0" })
        val r = Density.apply(listOf(many, occ("riddle-r", ItemType.riddle, "99:99")), 3, 40)
        assertEquals(40, keptIds(r).size)
        assertEquals(6, r.dropped.size)
        assertTrue("riddle-r" in keptIds(r), "riddles fill before treasure")
    }

    @Test
    fun `placements without a square and hidden eggs and ambient decor are not counted`() {
        val ferry = occ("ferry-sprite", ItemType.ambient, "5:5", "5:5", "5:5")
        val hidden = occ("egg-h", ItemType.egg, "5:5", hidden = true)
        val r = Density.apply(listOf(ferry, hidden, occ("lore-a", ItemType.lore, "5:5"), occ("lore-b", ItemType.lore, "5:5"), occ("lore-c", ItemType.lore, "5:5")), 3, 40)
        assertTrue(r.dropped.isEmpty())
    }
}
