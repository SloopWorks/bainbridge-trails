package com.bainbridgetrails.ui

import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.map.RoadReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoadListLogicTest {
    private fun road(
        id: String, name: String, cat: String, f: Double = 0.0, len: Double = 0.5,
        counted: Boolean = true, reason: RoadReason? = null,
    ) = RoadListItem(id, name, cat, counted, reason, f, if (f >= 0.85) TrailStatus.COMPLETE else if (f > 0) TrailStatus.PARTWAY else TrailStatus.NOT_STARTED, len)

    private val items = listOf(
        road("sr", "State Highway 305", "state_route", 0.9, 7.0),
        road("fb", "Fletcher Bay Road Northeast", "arterial", 0.2, 2.3),
        road("ma", "North Madison Avenue Northeast", "collector", 0.0, 0.8),
        road("aa", "Aaron Avenue Northeast", "residential", 1.0, 0.3),
        road("zz", "Zephyr Lane", "residential", 0.0, 0.05),
        road("pv", "Northeast Mabrey Lane", "residential", counted = false, reason = RoadReason.PRIVATE),
        road("ln", "Little Otter Lane", "lane", counted = false, reason = RoadReason.LANE),
    )

    @Test fun displayAbbreviatesWholeWordsButNotTheFirst() {
        assertEquals("Fletcher Bay Rd NE", RoadListLogic.displayName("Fletcher Bay Road Northeast"))
        assertEquals("North Madison Ave NE", RoadListLogic.displayName("North Madison Avenue Northeast"))
        assertEquals("Lane Rd", RoadListLogic.displayName("Lane Road"))
        assertEquals("Roadside Dr", RoadListLogic.displayName("Roadside Drive"))
    }

    @Test fun searchIsAbbreviationTolerantBothWays() {
        // RF3-2
        assertTrue(RoadListLogic.matchesSearch("North Madison Avenue Northeast", "madison ave ne"))
        assertTrue(RoadListLogic.matchesSearch("Fletcher Bay Rd NE", "fletcher bay road northeast"))
        assertTrue(RoadListLogic.matchesSearch("Fletcher Bay Road Northeast", "FLETCHER B"))
        assertTrue(RoadListLogic.matchesSearch("State Highway 305", "sr 305"))
        assertTrue(RoadListLogic.matchesSearch("State Highway 305", "305"))
        assertTrue(RoadListLogic.matchesSearch("State Highway 305", "hwy"))
        assertFalse(RoadListLogic.matchesSearch("Fletcher Bay Road Northeast", "bay fletcher"))
        assertFalse(RoadListLogic.matchesSearch("Aaron Avenue Northeast", "aaron st"))
    }

    @Test fun groupedViewOrdersGroupsAndCountsWholeGroups() {
        val s = RoadListLogic.sections(items, RoadListQuery())
        assertEquals(listOf(RoadGroup.MAIN, RoadGroup.COLLECTORS, RoadGroup.RESIDENTIAL, RoadGroup.NOT_COUNTED), s.map { it.group })
        val main = s[0]
        assertEquals(listOf("fb", "sr"), main.items.map { it.id }) // A–Z: Fletcher before State
        assertEquals(1, main.done)
        assertEquals(2, main.total)
        assertEquals(listOf("ln", "pv"), s[3].items.map { it.id })
        // A status filter hides uncounted roads; header counts stay whole-group.
        val partway = RoadListLogic.sections(items, RoadListQuery(statuses = setOf(TrailStatus.PARTWAY)))
        assertEquals(listOf(RoadGroup.MAIN), partway.map { it.group })
        assertEquals(2, partway[0].total)
    }

    @Test fun typeFilterAndSummaryCountCountedOnly() {
        // RF3-1 / RF3-3
        val all = RoadListLogic.summary(items, RoadListQuery())
        assertEquals(RoadListLogic.Summary(shown = 5, total = 5, complete = 2), all)
        val arterials = RoadListQuery(groups = setOf(RoadGroup.MAIN))
        assertEquals(2, RoadListLogic.summary(items, arterials).shown)
        assertEquals(listOf(RoadGroup.MAIN), RoadListLogic.sections(items, arterials).map { it.group })
    }

    @Test fun rankingSortsAreFlatCountedOnlyAndSearchAddsUncountedLast() {
        // RF3-4: shortest first, ties by name.
        val shortest = RoadListLogic.sections(items, RoadListQuery(sort = RoadSort.SHORTEST))
        assertEquals(1, shortest.size)
        assertNull(shortest[0].group)
        assertEquals(listOf("zz", "aa", "ma", "fb", "sr"), shortest[0].items.map { it.id })
        val progress = RoadListLogic.sections(items, RoadListQuery(sort = RoadSort.PROGRESS))
        assertEquals(listOf("aa", "sr", "fb", "ma", "zz"), progress[0].items.map { it.id })
        val lane = RoadListLogic.sections(items, RoadListQuery(search = "lane"))
        assertEquals(listOf("zz", "ln", "pv"), lane[0].items.map { it.id })
    }

    @Test fun groupOfMergesStateRoutesIntoMainAndUncountedAlwaysNotCounted() {
        assertEquals(RoadGroup.MAIN, RoadGroup.of("state_route", true))
        assertEquals(RoadGroup.MAIN, RoadGroup.of("arterial", true))
        assertEquals(RoadGroup.RESIDENTIAL, RoadGroup.of("residential", true))
        assertEquals(RoadGroup.NOT_COUNTED, RoadGroup.of("arterial", false))
    }

    @Test fun noProgressBannerIgnoresUncounted() {
        assertTrue(RoadListLogic.noProgressYet(listOf(road("a", "A", "residential"), road("p", "P", "residential", 0.5, counted = false))))
        assertFalse(RoadListLogic.noProgressYet(items))
    }
}
