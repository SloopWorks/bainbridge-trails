package com.bainbridgetrails.ui.play.map

import com.bainbridgetrails.map.TapResult
import com.bainbridgetrails.map.TappableTrailLayerIds
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.Scope
import com.bainbridgetrails.play.fx.FxLayerIds
import com.bainbridgetrails.play.fx.effects.KrakenInk
import com.bainbridgetrails.play.schedule.ActiveItem
import com.bainbridgetrails.play.schedule.DayInfo
import com.bainbridgetrails.play.schedule.Gate
import com.bainbridgetrails.play.schedule.GeoPoint
import com.bainbridgetrails.play.schedule.Occurrence
import com.bainbridgetrails.play.schedule.PlayAgenda
import com.bainbridgetrails.play.schedule.ResolvedPlacement
import com.bainbridgetrails.play.time.SunDay
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** MapTab tap order with Play on (ux.md §3.5.4, PD-12, product PF3-5): the pure trail hit test first, Play only on a miss. */
class MapTapPriorityTest {
    private val pin = PlayHit.Pin("treasure-weekly-trails@2026-10-26#03", MarkerKind.TREASURE)

    @Test
    fun `trail hit wins over play pin`() {
        val pure = TapResult.Select("trail-grand-forest-west")
        // The Play query is not even made when the pure query hit (PF3-5).
        assertFalse(MapTapLogic.queriesPlay(pure, playOn = true))
        assertEquals(MapTapAction.Trail(pure), MapTapLogic.decide(pure, listOf(pin)))
        val choose = TapResult.Choose(listOf("trail-a", "trail-b"))
        assertEquals(MapTapAction.Trail(choose), MapTapLogic.decide(choose, listOf(pin)))
    }

    @Test
    fun `pin hit opens sheet`() {
        assertTrue(MapTapLogic.queriesPlay(TapResult.None, playOn = true))
        assertFalse(MapTapLogic.queriesPlay(TapResult.None, playOn = false), "Play off: no fx query at all")
        assertEquals(MapTapAction.OpenEncounter(pin.key), MapTapLogic.decide(TapResult.None, listOf(pin)))
        // Several pins under one finger: the declutter priority picks (remembrance > kraken > riddle > treasure > lore).
        val riddle = PlayHit.Pin("riddle-frog@always", MarkerKind.RIDDLE)
        assertEquals(MapTapAction.OpenEncounter(riddle.key), MapTapLogic.decide(TapResult.None, listOf(pin, riddle)))
        // A hit feature's properties round-trip through the GeoJSON the hit layer draws.
        val marker = marker("treasure-weekly-trails@2026-10-26#03", MarkerKind.TREASURE, 47.65472, -122.55968)
        val json = PlayMarkerLogic.hitGeoJson(listOf(marker), emptyList())
        assertTrue("\"k\":\"treasure-weekly-trails@2026-10-26#03\"" in json, json)
        assertEquals(pin, MapTapLogic.hitOf(mapOf("k" to pin.key, "t" to "TREASURE")))
        assertEquals(MapTapAction.Clear, MapTapLogic.decide(TapResult.None, emptyList()))
    }

    @Test
    fun `cluster zooms to z14 no sheet`() {
        val a = marker("t@w#1", MarkerKind.TREASURE, 47.6505, -122.5505)
        val b = marker("t@w#2", MarkerKind.TREASURE, 47.6595, -122.5595)
        val lone = marker("t@w#3", MarkerKind.TREASURE, 47.7005, -122.5005)
        val clusters = PlayMarkerLogic.clusters(listOf(a, b, lone))
        val two = clusters.single { it.count == 2 }
        assertEquals(1, clusters.count { it.count == 1 }, "a single item is a dot, still a zoom target")
        val hit = PlayHit.Cluster(two.lon, two.lat)
        val action = MapTapLogic.decide(TapResult.None, listOf(hit))
        assertEquals(MapTapAction.ZoomTo(two.lon, two.lat, 14.0), action)
        // A cluster under the finger never opens a sheet, even when a pin hit is also reported (z < 13 has no pins).
        assertFalse(action is MapTapAction.OpenEncounter)
        assertEquals(MapTapAction.ZoomTo(two.lon, two.lat, 14.0), MapTapLogic.hitOf(mapOf("c" to "1", "lon" to "${two.lon}", "lat" to "${two.lat}"))?.let { MapTapLogic.decide(TapResult.None, listOf(it)) })
    }

    @Test
    fun `inked stretch tap is a trail tap`() {
        // The ink casing is not a Play target: the only layer MapTab's Play query asks for is the marker hit layer.
        assertEquals(listOf(FxLayerIds.of("marker", "hit")), PlayMarkers.hitLayerIds)
        assertTrue(KrakenInk.layerIds.none { it in PlayMarkers.hitLayerIds })
        assertTrue(PlayMarkers.hitLayerIds.none { it in TappableTrailLayerIds }, "fx layers never join the pure query")
        // The line under the ink is in TappableTrailLayerIds, so the pure query hits it and the tap is a trail tap,
        // even with the kraken's own hit rect under the finger.
        val road = TapResult.Select("road-manitou-beach-drive-northeast")
        val kraken = PlayHit.Pin("kraken-roads@2026-10-29", MarkerKind.KRAKEN)
        assertEquals(MapTapAction.Trail(road), MapTapLogic.decide(road, listOf(kraken)))
    }

    @Test
    fun `markers from the agenda`() {
        val now = Instant.parse("2026-10-29T21:00:00Z")
        fun occ(key: String, type: ItemType, item: String, vararg p: ResolvedPlacement) = Occurrence(
            key, item, type, Scope.island, now, null, false, p.toList(), 0, null, true, emptyList(), emptyList(), false,
        )
        fun place(key: String, lat: Double, lon: Double) = ResolvedPlacement(key, 0, "place:x", null, emptyList(), GeoPoint(lat, lon), "1:1")
        val agenda = PlayAgenda(
            active = listOf(
                ActiveItem(occ("treasure-weekly-trails@2026-10-26", ItemType.treasure, "treasure-weekly-trails", place("t#1", 47.65, -122.55), place("t#2", 47.66, -122.56)), Gate.OPEN, found = setOf("t#2")),
                ActiveItem(occ("lore-memorial@always", ItemType.lore, "lore-memorial", place("m", 47.6156, -122.5102)), Gate.VIEW),
                ActiveItem(occ("egg-ladybug@always", ItemType.egg, "egg-ladybug", place("e", 47.696, -122.523)), Gate.OPEN),
                ActiveItem(occ("ch-sweep@2026-10-31", ItemType.challenge, "ch-sweep"), Gate.VIEW),
            ),
            upcoming = emptyList(),
            day = DayInfo(LocalDate(2026, 10, 29), SunDay(null, null, now, null, null), null, null, quietDay = false),
        )
        val m = PlayMarkerLogic.markers(agenda, lore = mapOf("lore-memorial" to "remembrance"), solved = emptySet())
        assertEquals(listOf("m", "t#1", "t#2"), m.map { it.key }, "remembrance first, found last, unfound eggs hidden, challenges unmarked")
        assertEquals(MarkerKind.REMEMBRANCE, m.first().kind)
        assertTrue(m.last().found)
        assertEquals(1, PlayMarkerLogic.challengesThisWeek(agenda))
        val quiet = agenda.copy(day = agenda.day.copy(quietDay = true))
        assertEquals(listOf("m"), PlayMarkerLogic.markers(quiet, mapOf("lore-memorial" to "remembrance"), emptySet()).map { it.key })
    }

    @Test
    fun `play chip text`() {
        assertEquals("1,240", PlayMarkerLogic.chipMarks(1_240))
        assertEquals("9,999", PlayMarkerLogic.chipMarks(9_999))
        assertEquals("12.4k", PlayMarkerLogic.chipMarks(12_400))
        assertEquals("10k", PlayMarkerLogic.chipMarks(10_000))
    }

    private fun marker(key: String, kind: MarkerKind, lat: Double, lon: Double) =
        PlayMarker(key, key.substringBefore('#'), "item", kind, lon, lat, null, found = false, gate = Gate.OPEN)
}
