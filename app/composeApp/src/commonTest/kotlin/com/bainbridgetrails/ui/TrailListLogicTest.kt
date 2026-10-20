package com.bainbridgetrails.ui

import com.bainbridgetrails.coverage.TrailStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrailListLogicTest {
    private fun item(id: String, name: String, park: String?, f: Double, len: Double, s: TrailStatus = status(f)) =
        TrailListItem(id, name, park, f, s, len)

    private fun status(f: Double) = when {
        f <= 0.0 -> TrailStatus.NOT_STARTED
        f >= 0.85 -> TrailStatus.COMPLETE
        f >= 0.75 -> TrailStatus.NEARLY_DONE
        else -> TrailStatus.PARTWAY
    }

    private val items = listOf(
        item("a", "Gazzam Lake Main Trail", "Gazzam Lake Preserve", 0.42, 1.4),
        item("b", "Arbor Trail", null, 0.0, 0.28),
        item("c", "Nute’s Pond Loop", "Nute's Pond", 0.9, 0.5),
        item("d", "T'Chookwop Path", "T'Chookwop Park", 0.8, 0.9),
        item("e", "Éagle Trail", "Grand Forest", 0.42, 2.0),
        item("f", "Zed Spur", null, 0.1, 0.05),
    )

    private fun ids(q: TrailListQuery) = TrailListLogic.apply(items, q).map { it.trailId }

    @Test
    fun defaultIsNameAToZAccentInsensitive() {
        assertEquals(listOf("b", "e", "a", "c", "d", "f"), ids(TrailListQuery()))
    }

    @Test
    fun searchMatchesNameAndParkIgnoringCaseAccentsAndApostrophes() {
        assertEquals(listOf("a"), ids(TrailListQuery(search = "gaz")))
        assertEquals(listOf("e"), ids(TrailListQuery(search = "EAGLE")))
        assertEquals(listOf("c"), ids(TrailListQuery(search = "nutes")))
        assertEquals(listOf("d"), ids(TrailListQuery(search = "tchook")))
        assertEquals(listOf("e"), ids(TrailListQuery(search = "  grand   forest ")))
        assertEquals(emptyList(), ids(TrailListQuery(search = "zzz")))
    }

    @Test
    fun statusFilterIsMultiSelect() {
        assertEquals(listOf("e", "a", "f"), ids(TrailListQuery(statuses = setOf(TrailStatus.PARTWAY))))
        assertEquals(listOf("c", "d"), ids(TrailListQuery(statuses = setOf(TrailStatus.COMPLETE, TrailStatus.NEARLY_DONE))))
    }

    @Test
    fun parkFilters() {
        assertEquals(listOf("b", "f"), ids(TrailListQuery(park = ParkFilter.NoPark)))
        assertEquals(listOf("e"), ids(TrailListQuery(park = ParkFilter.Named("Grand Forest"))))
        assertEquals(listOf("Gazzam Lake Preserve", "Grand Forest", "Nute's Pond", "T'Chookwop Park"), TrailListLogic.parks(items))
    }

    @Test
    fun progressSortIsHighToLowTiesByName() {
        assertEquals(listOf("c", "d", "e", "a", "f", "b"), ids(TrailListQuery(sort = TrailSort.PROGRESS)))
    }

    @Test
    fun lengthSortIsShortToLong() {
        assertEquals(listOf("f", "b", "c", "d", "a", "e"), ids(TrailListQuery(sort = TrailSort.LENGTH)))
    }

    @Test
    fun filtersCombineAndClear() {
        val q = TrailListQuery(search = "trail", statuses = setOf(TrailStatus.PARTWAY), park = ParkFilter.Named("Grand Forest"))
        assertEquals(listOf("e"), ids(q))
        assertTrue(q.hasFilters)
        assertFalse(q.withoutFilters().hasFilters)
        assertEquals("trail", q.withoutFilters().search)
    }

    @Test
    fun noProgressBanner() {
        assertTrue(TrailListLogic.noProgressYet(items.map { it.copy(fraction = 0.0) }))
        assertFalse(TrailListLogic.noProgressYet(items))
    }
}
