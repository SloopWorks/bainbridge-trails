package com.bainbridgetrails.ui.play.map

import com.bainbridgetrails.map.TapResult
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.config.TierName
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.effects.PIN_FIGURE_CY
import com.bainbridgetrails.play.geo.ChartSquare
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.schedule.ActiveItem
import com.bainbridgetrails.play.schedule.Gate
import com.bainbridgetrails.play.schedule.PlayAgenda
import com.bainbridgetrails.ui.play.components.PlayFormat
import kotlin.math.roundToInt

/**
 * What a Play map marker is (ux.md §2.2). Declaration order is the declutter priority (`symbol-sort-key`):
 * remembrance > kraken > riddle > treasure > lore > egg; found items sort after every unfound one.
 */
enum class MarkerKind { REMEMBRANCE, KRAKEN, RIDDLE, TREASURE, LORE, EGG }

/**
 * One tappable Play marker (ux.md §2.2): [key] is the claim id (the placement key, else the occurrence key) and the
 * EncounterSheet's key; [lon]/[lat] the ground dot (the figure is lifted above it). [found]: claimed (treasure shell,
 * met lore, found egg); [lit]: an armchair-solved riddle.
 */
data class PlayMarker(
    val key: String,
    val occurrenceKey: String,
    val itemId: String,
    val kind: MarkerKind,
    val lon: Double,
    val lat: Double,
    val square: String?,
    val found: Boolean,
    val gate: Gate,
    val tier: TierName? = null,
    val marks: Int = 0,
    val lit: Boolean = false,
    val lore: String? = null,
) {
    /** The zoom from which the marker is a pin (and its hit rect exists): ux.md §2.2 table. */
    val minZoom: Double
        get() = when (kind) {
            MarkerKind.TREASURE, MarkerKind.RIDDLE -> 13.0
            MarkerKind.LORE, MarkerKind.REMEMBRANCE -> 14.0
            MarkerKind.EGG -> 15.0
            MarkerKind.KRAKEN -> 0.0
        }

    /** The figure drawn in the pin (placeholder glyphs until the engraved set lands, ux.md §7.2 #4). */
    val glyph: FxGlyph
        get() = when (kind) {
            MarkerKind.TREASURE -> when (tier) {
                TierName.agate -> FxGlyph.Agate
                TierName.glass_float -> FxGlyph.GlassFloat
                TierName.ships_bell -> FxGlyph.ShipsBell
                else -> FxGlyph.SeaGlass
            }
            MarkerKind.RIDDLE -> if (lit) FxGlyph.LanternLit else FxGlyph.LanternUnlit
            MarkerKind.REMEMBRANCE -> FxGlyph.QuietRing
            MarkerKind.KRAKEN -> FxGlyph.KrakenEye
            MarkerKind.EGG -> FxGlyph.EggStamp
            MarkerKind.LORE -> when (lore) {
                "frog" -> FxGlyph.Frog
                "ferry" -> FxGlyph.Ferry
                "kraken" -> FxGlyph.KrakenEye
                else -> FxGlyph.Pennant
            }
        }
}

/** Island-zoom group (z < 13) of the treasure and riddle dots in one chart square; a tap zooms to z14 there. */
data class PlayCluster(val square: String, val count: Int, val lon: Double, val lat: Double)

/** A Play feature under the finger, from the `fx-marker-hit` query. */
sealed interface PlayHit {
    data class Pin(val key: String, val kind: MarkerKind) : PlayHit

    data class Cluster(val lon: Double, val lat: Double) : PlayHit
}

/** What MapTab does with one tap (ux.md §3.5.4). */
sealed interface MapTapAction {
    /** The pure behaviour (peek / chooser / clear); any Play selection is cleared. */
    data class Trail(val result: TapResult) : MapTapAction

    data class OpenEncounter(val key: String) : MapTapAction

    /** Cluster or island-zoom dot: camera to [zoom] centred there, no sheet. */
    data class ZoomTo(val lon: Double, val lat: Double, val zoom: Double) : MapTapAction

    /** Nothing under the finger: close any sheet, clear selections. */
    data object Clear : MapTapAction
}

/**
 * MapTab tap order with Play on (ux.md §3.5.4, PD-12 refined by PD-25, product PF3-5): a tap point inside a Play
 * marker's own 48 dp figure rect (`fx-marker-hit` queried at the point: pins, the kraken coast label, cluster discs)
 * is that marker's, whatever line runs underneath. Otherwise the pure `TappableTrailLayerIds` query decides: a hit is a
 * trail tap whatever Play draws there (an inked kraken stretch included), and only a miss queries `fx-marker-hit` over
 * the tap rect: a pin opens its EncounterSheet, a cluster zooms to z14, nothing clears.
 */
object MapTapLogic {
    const val CLUSTER_ZOOM = 14.0

    /** Whether MapTab queries the Play hit layer at the tap point (inside a figure rect, PD-25): whenever Play is on. */
    fun queriesFigure(playOn: Boolean): Boolean = playOn

    /** Whether MapTab queries the Play hit layer over the tap rect (a near miss): only when the pure query missed. */
    fun queriesPlay(pure: TapResult, playOn: Boolean): Boolean = playOn && pure is TapResult.None

    /**
     * [inFigure] = Play hits whose figure rect contains the tap point (they win, PD-25); [play] = hits over the tap rect,
     * used only on a pure miss.
     */
    fun decide(pure: TapResult, play: List<PlayHit>, inFigure: List<PlayHit> = emptyList()): MapTapAction {
        if (inFigure.isNotEmpty()) return pick(inFigure)
        if (pure !is TapResult.None) return MapTapAction.Trail(pure)
        return pick(play)
    }

    private fun pick(play: List<PlayHit>): MapTapAction {
        play.filterIsInstance<PlayHit.Cluster>().firstOrNull()?.let { return MapTapAction.ZoomTo(it.lon, it.lat, CLUSTER_ZOOM) }
        val pin = play.filterIsInstance<PlayHit.Pin>().minByOrNull { it.kind.ordinal } ?: return MapTapAction.Clear
        return MapTapAction.OpenEncounter(pin.key)
    }

    /** A hit feature's properties (as [PlayMarkerLogic.hitGeoJson] writes them) → the hit, or null. */
    fun hitOf(props: Map<String, String>): PlayHit? {
        if (props["c"] == "1") {
            val lon = props["lon"]?.toDoubleOrNull() ?: return null
            val lat = props["lat"]?.toDoubleOrNull() ?: return null
            return PlayHit.Cluster(lon, lat)
        }
        val key = props["k"] ?: return null
        val kind = props["t"]?.let { t -> MarkerKind.entries.firstOrNull { it.name == t } } ?: return null
        return PlayHit.Pin(key, kind)
    }
}

/** The map's Play markers and HUD numbers from the agenda (pure; ux.md §2.1, §2.2, §3.5). */
object PlayMarkerLogic {
    /** ux.md §2.2 density: at most this many Play markers render. */
    const val MAX_MARKERS = 40

    /**
     * Markers for [agenda]: treasures (each placement; found ones keep their pin as a shell), riddles (lit when
     * [solved]), lore pins (remembrance from the item's [lore] family), the kraken (one marker per attack, on the
     * water: the attack's own [water] point by occurrence key — the lurk / pool anchor, [PlayMapEffects.attacks] — else
     * its stretches' middle pushed offshore the same way, [PlayMapEffects.offshore]), found eggs only. Challenges and ambient items have none. A quiet day keeps only the
     * remembrance pin (PF7-6, product § 4.6.3). Sorted by declutter priority, unfound first; capped at [MAX_MARKERS].
     */
    fun markers(
        agenda: PlayAgenda,
        lore: Map<String, String?>,
        solved: Set<String>,
        water: Map<String, GeoPoint> = emptyMap(),
    ): List<PlayMarker> {
        val out = ArrayList<PlayMarker>()
        for (a in agenda.active) out += markersOf(a, lore[a.occurrence.itemId], a.occurrence.itemId in solved, water[a.occurrence.key])
        val shown = if (agenda.day.quietDay) out.filter { it.kind == MarkerKind.REMEMBRANCE } else out
        return shown.sortedWith(compareBy({ it.found }, { it.kind.ordinal }, { it.key })).take(MAX_MARKERS)
    }

    private fun markersOf(a: ActiveItem, lore: String?, solved: Boolean, water: GeoPoint?): List<PlayMarker> {
        val o = a.occurrence
        fun found(key: String) = key in a.found || o.key in a.found
        val points = o.placements.filter { it.point != null }
        return when (o.type) {
            ItemType.treasure -> points.map { p ->
                PlayMarker(p.key, o.key, o.itemId, MarkerKind.TREASURE, p.point!!.lon, p.point!!.lat, p.square, found(p.key), a.gate, p.tier, p.marks)
            }
            ItemType.riddle -> points.take(1).map { p ->
                PlayMarker(p.key, o.key, o.itemId, MarkerKind.RIDDLE, p.point!!.lon, p.point!!.lat, p.square, found(p.key), a.gate, marks = o.marks, lit = solved)
            }
            ItemType.lore -> points.take(1).map { p ->
                val kind = if (lore == "remembrance") MarkerKind.REMEMBRANCE else MarkerKind.LORE
                PlayMarker(p.key, o.key, o.itemId, kind, p.point!!.lon, p.point!!.lat, p.square, found(p.key), a.gate, marks = o.marks, lore = lore)
            }
            ItemType.egg -> points.filter { found(it.key) }.take(1).map { p ->
                PlayMarker(p.key, o.key, o.itemId, MarkerKind.EGG, p.point!!.lon, p.point!!.lat, p.square, true, a.gate, marks = o.marks)
            }
            ItemType.obstacle -> if (points.isEmpty()) {
                emptyList()
            } else {
                // The coast label sits on the water (ux.md §2.2), never inland over the stretches it inks.
                val at = water ?: PlayMapEffects.offshore(points.map { it.point!!.lon }.average(), points.map { it.point!!.lat }.average())
                listOf(PlayMarker(o.key, o.key, o.itemId, MarkerKind.KRAKEN, at.lon, at.lat, null, points.all { found(it.key) }, a.gate))
            }
            else -> emptyList()
        }
    }

    /**
     * Per chart square, the treasure and riddle markers drawn as island-zoom dots: one zoom target per square. A group
     * of ≥ 2 is a disc at the square's centre; a single item stays its own dot, so its target is the item's point.
     */
    fun clusters(markers: List<PlayMarker>): List<PlayCluster> =
        markers.filter { it.kind == MarkerKind.TREASURE || it.kind == MarkerKind.RIDDLE }
            .groupBy { it.square ?: ChartSquare.of(it.lat, it.lon).id }
            .map { (sq, ms) ->
                if (ms.size == 1) return@map PlayCluster(sq, 1, ms[0].lon, ms[0].lat)
                val centre = ChartSquare.parse(sq)?.southWest
                val lat = centre?.let { it.lat + ChartSquare.D_LAT / 2 } ?: ms.map { it.lat }.average()
                val lon = centre?.let { it.lon + ChartSquare.D_LON / 2 } ?: ms.map { it.lon }.average()
                PlayCluster(sq, ms.size, lon, lat)
            }
            .sortedBy { it.square }

    /** Pin figures are lifted this far above their ground dot (the hit rect is centred on the figure). */
    const val PIN_LIFT_DP = -PIN_FIGURE_CY

    /**
     * Where the 48dp hit rect sits relative to the point: on the lifted figure for pins, at the point for a cluster
     * disc / island-zoom dot and for the kraken, whose coast label is drawn at the point (no pin).
     */
    fun hitLiftDp(kind: MarkerKind, cluster: Boolean): Float = if (cluster || kind == MarkerKind.KRAKEN) 0f else PIN_LIFT_DP

    /** `symbol-sort-key` of a pin (lower places first, ux.md §2.2): the selected pin first, then [MarkerKind] order, found last. */
    fun sortKey(m: PlayMarker, selected: String?): Int = if (m.key == selected) -1 else m.kind.ordinal + if (m.found) MarkerKind.entries.size else 0

    /**
     * The `fx-marker-hit` source: one Point per pin (`k` key, `t` kind, `z` min zoom, `p` sort key, `sel` 1 for
     * [selected], `dim` 1 for every other pin while one is selected (50 %, PD-19), `lift` the hit rect's lift) and one
     * per island-zoom group (`c` 1, `n` count, `m` 1 for a disc, `lon`/`lat` its zoom target). Ids are the list index.
     */
    fun hitGeoJson(markers: List<PlayMarker>, clusters: List<PlayCluster>, selected: String? = null): String = buildString {
        append("""{"type":"FeatureCollection","features":[""")
        var i = 0
        fun feature(lon: Double, lat: Double, props: StringBuilder.() -> Unit) {
            if (i > 0) append(',')
            append("""{"type":"Feature","id":""").append(i++).append(""","properties":{""")
            props()
            append("""},"geometry":{"type":"Point","coordinates":[""").append(lon).append(',').append(lat).append("]}}")
        }
        for (m in markers) feature(m.lon, m.lat) {
            append("\"k\":").append(jsonString(m.key))
            append(",\"t\":\"").append(m.kind.name).append('"')
            append(",\"z\":").append(m.minZoom)
            append(",\"f\":").append(if (m.found) 1 else 0)
            append(",\"p\":").append(sortKey(m, selected))
            append(",\"sel\":").append(if (m.key == selected) 1 else 0)
            append(",\"own\":").append(if (PlayMarkers.ownsPin(m)) 1 else 0)
            append(",\"dim\":").append(if (selected != null && m.key != selected) 1 else 0)
            append(",\"lift\":").append(hitLiftDp(m.kind, cluster = false).toInt())
            append(",\"g\":\"").append(m.glyph.name).append('"')
        }
        for (c in clusters) feature(c.lon, c.lat) {
            append("\"c\":\"1\",\"n\":\"").append(c.count).append("\",\"m\":\"").append(if (c.count > 1) 1 else 0).append('"')
            append(",\"lon\":\"").append(c.lon).append("\",\"lat\":\"").append(c.lat).append('"')
        }
        append("]}")
    }

    /** The Play chip's marks (ux.md §2.1, product § 3.1): "1,240"; from 10,000 "12.4k" (one decimal, none when .0). */
    fun chipMarks(total: Int): String {
        if (total < 10_000) return PlayFormat.marks(total)
        val tenths = (total / 100.0).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}k" else "${tenths / 10}.${tenths % 10}k"
    }

    /** This week's challenges, started or not (ux.md §2.1 Count): the active challenge occurrences. */
    fun challengesThisWeek(agenda: PlayAgenda): Int =
        if (agenda.day.quietDay) 0 else agenda.active.count { it.occurrence.type == ItemType.challenge }

    /** Coast names for `play_kraken_coast` by the pack's coast pool id (product § 4.3.2 coast tables). */
    val COAST_NAMES: Map<String, String> = mapOf(
        "coast-k2" to "Point White", "coast-k3" to "Eagle Harbor", "coast-k4" to "the south shore", "coast-k5" to "Manitou",
        "coast-k6" to "Point Monroe", "coast-k7" to "the west shore", "coast-t2" to "Fort Ward", "coast-t3" to "Winslow",
        "coast-t4" to "the west shore", "coast-t5" to "the east shore",
    )

    /** The coast of an attack from its stretch refs (`stretch:<id>`) and the pack's `coast-*` pools. */
    fun coastOf(stretchRefs: List<String>, pools: Map<String, List<String>>): String? =
        pools.entries.firstOrNull { (id, members) -> id.startsWith("coast-") && stretchRefs.any { it in members } }?.key?.let { COAST_NAMES[it] }

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (ch in s) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> append(ch)
        }
        append('"')
    }
}
