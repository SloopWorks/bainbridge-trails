package com.bainbridgetrails.ui

import com.bainbridgetrails.coverage.TrailProgress
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.AppSettings
import com.bainbridgetrails.data.Units
import com.bainbridgetrails.map.MapStyle
import com.bainbridgetrails.tracking.WalkRecord
import com.bainbridgetrails.tracking.WalkTrail
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeLogicTest {
    private fun tp(id: String, f: Double, s: TrailStatus) = TrailProgress(id, "Trail $id", f, s, 1.0)

    @Test
    fun inProgressExcludesUnstartedAndCompleteClosestFirst() {
        val trails = listOf(
            tp("a", 0.0, TrailStatus.NOT_STARTED),
            tp("b", 0.3, TrailStatus.PARTWAY),
            tp("c", 0.8, TrailStatus.NEARLY_DONE),
            tp("d", 0.9, TrailStatus.COMPLETE),
            tp("e", 0.3, TrailStatus.PARTWAY),
            // Sticky-complete trail below the threshold stays out of "in progress".
            tp("f", 0.5, TrailStatus.COMPLETE),
        )
        assertEquals(listOf("c", "b", "e"), MeLogic.inProgress(trails).map { it.trailId })
    }

    private fun walk(id: String, start: Long, matched: Double, trails: List<String> = emptyList()) =
        WalkRecord(id, start, start + 3_600_000, 2000.0, matched, verified = true, trails = trails.map { WalkTrail(it, 0.0, 0.5) })

    @Test
    fun statsSumMatchedMetresAndCountWalks() {
        val s = MeLogic.stats(0.124, 9, 114, listOf(walk("1", 0, 1000.0), walk("2", 10, 609.344)))
        assertEquals(1609.344, s.trailMetresWalked, 1e-9)
        assertEquals(2, s.walkCount)
        assertEquals(114, s.trailsTotal)
        assertFalse(s.isNew)
        assertTrue(MeLogic.stats(0.0, 0, 114, emptyList()).isNew)
    }

    @Test
    fun walkRowsNewestFirstWithTwoNames() {
        val rows = MeLogic.walkRows(
            listOf(walk("old", 1_000, 0.0, listOf("x")), walk("new", 2_000, 0.0, listOf("x", "gone", "y", "z"))),
        ) { id -> if (id == "gone") null else id.uppercase() }
        assertEquals(listOf("new", "old"), rows.map { it.sessionId })
        assertEquals(4, rows[0].trailCount)
        assertEquals(listOf("X", "Y"), rows[0].trailNames)
        assertEquals(3_600_000, rows[0].durationMs)
    }

    @Test
    fun walkDateAddsYearOnlyForOtherYears() {
        val utc = TimeZone.UTC
        val oct18 = 1_760_745_600_000L // 2025-10-18T00:00:00Z, a Saturday
        assertEquals("Sat, Oct 18", ListFormat.walkDate(oct18 + 3_600_000, oct18 + 86_400_000, utc))
        assertEquals("Sat, Oct 18, 2025", ListFormat.walkDate(oct18, oct18 + 365L * 86_400_000, utc))
    }

    @Test
    fun shortLengthsUseFeetOrMetres() {
        assertEquals(ListFormat.ShortLength.Feet(330), ListFormat.shortLength(100.0, Units.MILES))
        assertEquals(ListFormat.ShortLength.Regular("0.3 mi"), ListFormat.trailLength(0.282, Units.MILES))
        assertEquals(ListFormat.ShortLength.Metres(65), ListFormat.shortLength(66.5, Units.KILOMETRES))
        assertEquals(ListFormat.ShortLength.Regular("0.5 km"), ListFormat.shortLength(453.0, Units.KILOMETRES))
    }

    @Test
    fun walkDurations() {
        assertEquals("<1 min", ListFormat.walkDuration(30_000))
        assertEquals("40 min", ListFormat.walkDuration(40 * 60_000L + 59_000))
        assertEquals("1 hr 6 min", ListFormat.walkDuration(66 * 60_000L))
        assertEquals("2 hr", ListFormat.walkDuration(120 * 60_000L))
    }

    @Test
    fun ageBuckets() {
        assertEquals(ListFormat.Age.JustNow, ListFormat.age(0, 59_000))
        assertEquals(ListFormat.Age.Minutes(5), ListFormat.age(0, 5 * 60_000 + 10))
        assertEquals(ListFormat.Age.Hours(2), ListFormat.age(0, 125 * 60_000L))
        assertEquals(ListFormat.Age.Days(3), ListFormat.age(0, 3 * 86_400_000L + 1))
    }

    @Test
    fun mapStyleChoiceRoundTrips() {
        for (c in MapStyleChoice.entries) assertEquals(c, AppSettings().withStyleChoice(c).styleChoice())
        assertEquals(MapStyleChoice.AUTO, AppSettings().styleChoice())
        val auto = AppSettings().withStyleChoice(MapStyleChoice.AUTO)
        assertEquals(MapStyle.DARK, auto.effectiveMapStyle(systemDark = true))
        assertEquals(MapStyle.TOPO, auto.effectiveMapStyle(systemDark = false))
        assertEquals(MapStyle.MINIMAL_LIGHT, AppSettings().withStyleChoice(MapStyleChoice.LIGHT).effectiveMapStyle(systemDark = true))
    }
}
