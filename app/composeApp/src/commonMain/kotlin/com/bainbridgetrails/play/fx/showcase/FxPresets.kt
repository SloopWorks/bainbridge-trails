package com.bainbridgetrails.play.fx.showcase

import com.bainbridgetrails.data.Loadable
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.play.fx.effects.ExplorationFog
import com.bainbridgetrails.play.fx.effects.FerryCrossing
import com.bainbridgetrails.play.fx.effects.FrogRockStamp
import com.bainbridgetrails.play.fx.effects.KrakenInk
import com.bainbridgetrails.play.fx.effects.KrakenLurk
import com.bainbridgetrails.play.fx.effects.RiddleMarker
import com.bainbridgetrails.play.fx.effects.TreasureGlint
import com.bainbridgetrails.play.geo.ChartSquare
import com.bainbridgetrails.play.time.FerryTimetable
import com.bainbridgetrails.play.time.IslandTime
import com.bainbridgetrails.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position
import kotlin.math.PI
import kotlin.math.cos
import kotlin.time.Instant

/**
 * One showcase preset (plan §2, effects §3 "Showcase"): camera, the states the HUD offers, the default time-warp and,
 * for kraken presets, the coast's stretches ([main] is the one repelled in state `repelled`).
 */
data class FxPreset(
    val id: String,
    val effect: String,
    val lat: Double,
    val lon: Double,
    val zoom: Double,
    val states: List<String>,
    val warp: Double = 1.0,
    val stretches: List<String> = emptyList(),
    /** Kraken presets: the offshore anchor (≥ 60 m from the road); null = 180 m east of the camera. */
    val water: GeoPoint? = null,
) {
    val camera: CameraPosition get() = CameraPosition(center = Position(longitude = lon, latitude = lat), zoom = zoom)
}

/** Bundled data the presets draw from (no PlayRepository): derived files, the pack's ferry block, the dataset. */
class FxShowcaseData(
    val squares: List<ChartSquare>,
    val quiet: List<List<GeoPoint>>,
    val stretchSegments: Map<String, List<String>>,
    val segmentLines: Map<String, List<GeoPoint>>,
    val ferry: FerryTimetable?,
)

/** L2-4's presets; L2-5 owns this file from here on and adds its own (FxShowcasePresetTest). */
object FxPresets {
    val all: List<FxPreset> = listOf(
        FxPreset("fay-5", TreasureGlint.kind, 47.7010, -122.5080, 15.5, listOf("present")),
        FxPreset("island-20of62", ExplorationFog.kind, 47.6450, -122.5350, 12.0, listOf("idle")),
        FxPreset("winslow-unlit", RiddleMarker.KIND, 47.6245, -122.5195, 16.0, listOf("unlit", "lit")),
        FxPreset("manitou-water", KrakenLurk.kind, 47.6625, -122.4985, 14.0, listOf("idle"), warp = 4.0),
        FxPreset("eagle-harbor-x60", FerryCrossing.KIND, 47.6195, -122.4990, 14.0, listOf("run"), warp = 60.0),
        FxPreset("frog-z17", FrogRockStamp.kind, FrogRockStamp.LANDMARK.lat, FrogRockStamp.LANDMARK.lon, 17.0, listOf("idle")),
        FxPreset(
            "manitou", KrakenInk.kind, 47.66007, -122.50494, 15.5, listOf("intro", "idle", "repelled"),
            stretches = listOf("road-manitou-beach-drive-northeast", "road-northeast-gertie-johnson-road"),
            water = GeoPoint(-122.5039, 47.6568),
        ),
        FxPreset(
            "rockaway", KrakenInk.kind, 47.60070, -122.49995, 15.0, listOf("intro", "idle", "repelled"),
            stretches = listOf("road-rockaway-beach-road-northeast", "road-northeast-south-beach-drive-1", "road-northeast-seaborn-road"),
        ),
    )

    val effects: List<String> get() = all.map { it.effect }.distinct()

    fun byId(id: String?): FxPreset? = all.firstOrNull { it.id == id }

    fun forEffect(effect: String): List<FxPreset> = all.filter { it.effect == effect }

    /** `preset` wins, then the first preset of `fx`, then the first preset. */
    fun initial(fx: String?, preset: String?): FxPreset = byId(preset) ?: fx?.let { forEffect(it).firstOrNull() } ?: all.first()

    /** Island time the ferry preset starts at: a weekday 08:20, the 07:55 Seattle boat 10 min from the slip. */
    val FerryStart: Instant = IslandTime.instantOf(LocalDate(2026, 10, 21), LocalTime(8, 20))

    /** What [preset] spawns in [state]. Kraken presets need the roads dataset for their stretch geometry. */
    fun specs(preset: FxPreset, state: String, data: FxShowcaseData): List<FxSpec> = when (preset.effect) {
        TreasureGlint.kind -> listOf(
            GeoPoint(-122.5090, 47.7028), GeoPoint(-122.5072, 47.7018), GeoPoint(-122.5097, 47.7006),
            GeoPoint(-122.5079, 47.6996), GeoPoint(-122.5110, 47.7012),
        ).mapIndexed { i, p -> TreasureGlint.spec("treasure-fay@showcase#$i", p) }
        ExplorationFog.kind -> {
            val sorted = data.squares.sortedWith(compareBy({ it.row }, { it.col }))
            val lifted = sorted.indices.filter { it % 3 == 0 }.take(20).map { sorted[it].id }.toSet()
            ExplorationFog.specs(sorted, lifted, data.quiet)
        }
        RiddleMarker.KIND -> listOf(RiddleMarker.spec("riddle-winslow@showcase", GeoPoint(preset.lon, preset.lat), lit = state == "lit"))
        KrakenLurk.kind -> listOf(KrakenLurk.spec("kraken-lurk@showcase", GeoPoint(preset.lon, preset.lat)))
        FerryCrossing.KIND -> listOf(FerryCrossing.spec())
        FrogRockStamp.kind -> listOf(FrogRockStamp.spec())
        KrakenInk.kind -> {
            val attack = attack(preset, data)
            val repelled = if (state == "repelled") setOf(preset.stretches.first()) else emptySet()
            KrakenInk.specs(attack, repelled) + listOfNotNull(if (state == "intro") KrakenInk.introSpec(attack, repelled) else null)
        }
        else -> emptyList()
    }

    /** The preset's attack: the coast's stretches from the dataset; water at the preset's offshore anchor. */
    fun attack(preset: FxPreset, data: FxShowcaseData): KrakenInk.Attack {
        val stretches = preset.stretches.map { id ->
            KrakenInk.Stretch(id, data.stretchSegments[id].orEmpty().mapNotNull { data.segmentLines[it] })
        }.filter { it.lines.isNotEmpty() }
        val water = preset.water ?: GeoPoint(preset.lon + 180.0 / (111_195.0 * cos(preset.lat * PI / 180.0)), preset.lat)
        return KrakenInk.Attack("kraken@showcase-${preset.id}", stretches, water, active = true)
    }

    suspend fun load(): FxShowcaseData = withContext(Dispatchers.Default) {
        val trails = Json.parseToJsonElement(Res.readBytes("files/play/derived-trails.json").decodeToString()).jsonObject
        val roads = Json.parseToJsonElement(Res.readBytes("files/play/derived-roads.json").decodeToString()).jsonObject
        val pack = Json.parseToJsonElement(Res.readBytes("files/play/play-content.json").decodeToString()).jsonObject
        val squares = trails["segmentSquares"]!!.jsonObject.values.flatMap { v -> v.jsonArray.map { it.jsonPrimitive.content } }
            .toSet().mapNotNull { ChartSquare.parse(it) }
        val quiet = trails["quietZones"]!!.jsonArray.mapNotNull { z ->
            z.jsonObject["polygon"]?.jsonArray?.map { p -> GeoPoint(p.jsonArray[0].jsonPrimitive.double, p.jsonArray[1].jsonPrimitive.double) }
        }
        val stretchSegments = HashMap<String, List<String>>()
        for (d in listOf(trails, roads)) d["stretches"]?.jsonObject?.forEach { (id, s) ->
            stretchSegments[id] = s.jsonObject["segments"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content }
        }
        val wanted = stretchSegments.values.flatten().toSet()
        val ready = withTimeoutOrNull(15_000) { AppGraph.datasets.state.first { it is Loadable.Ready } } as? Loadable.Ready
        val lines = ready?.value?.dataset?.segments.orEmpty().filter { it.id in wanted }
            .associate { s -> s.id to s.coords.map { GeoPoint(lon = it[0], lat = it[1]) } }
        val ferry = runCatching {
            val f = pack["ferry"]!!.jsonObject
            val until = f["timetables"]!!.jsonArray.mapNotNull { it.jsonObject["to"]?.jsonPrimitive?.content }.max()
            FerryTimetable.parse(JsonObject(f + ("validUntil" to JsonPrimitive(until))).toString())
        }.getOrNull()
        FxShowcaseData(squares, quiet, stretchSegments, lines, ferry)
    }
}
