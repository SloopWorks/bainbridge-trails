package com.bainbridgetrails.play.fx.showcase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxGlyphImage
import com.bainbridgetrails.play.fx.effects.ClaimPopOverlay
import com.bainbridgetrails.play.fx.effects.ClaimPopState
import com.bainbridgetrails.play.fx.effects.claimPopTarget
import com.bainbridgetrails.ui.play.PlayWake
import com.bainbridgetrails.ui.play.PlayWakeArt
import com.bainbridgetrails.ui.play.components.PlayFormat
import com.bainbridgetrails.ui.theme.BtTheme
import com.bainbridgetrails.ui.theme.PillShape
import kotlinx.coroutines.delay
import com.bainbridgetrails.data.Loadable
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.play.fx.effects.ExplorationFog
import com.bainbridgetrails.play.fx.effects.FerryCrossing
import com.bainbridgetrails.play.fx.effects.FrogRock
import com.bainbridgetrails.play.fx.effects.FrogRockStamp
import com.bainbridgetrails.play.fx.effects.KrakenInk
import com.bainbridgetrails.play.fx.effects.KrakenLurk
import com.bainbridgetrails.play.fx.effects.KrakenRecede
import com.bainbridgetrails.play.fx.effects.RiddleLight
import com.bainbridgetrails.play.fx.effects.RiddleMarker
import com.bainbridgetrails.play.fx.effects.TreasureFound
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
import kotlin.math.hypot
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
    /** Theme the preset is defined in (`dark`), unless the launch sets one. */
    val theme: String? = null,
) {
    val camera: CameraPosition get() = CameraPosition(center = Position(longitude = lon, latitude = lat), zoom = zoom)
}

/** Screen-anchored effect ids (no FxRenderer: chrome and moment). */
const val CLAIM_POP = "claim_pop"
const val PLAY_WAKE = "play_wake"

/** Bundled data the presets draw from (no PlayRepository): derived files, the pack's ferry block, the dataset. */
class FxShowcaseData(
    val squares: List<ChartSquare>,
    val quiet: List<List<GeoPoint>>,
    val stretchSegments: Map<String, List<String>>,
    val segmentLines: Map<String, List<GeoPoint>>,
    val ferry: FerryTimetable?,
)

/**
 * Every v1 effect's showcase presets (plan §2, effects §7, AC 7; FxShowcasePresetTest): L2-4's Layer-tier presets and
 * L2-5's Overlay / Moment presets. Owned by L2-5 from here on.
 */
object FxPresets {
    /** Effects drawn screen-anchored over the map (chrome / moment), not by FxHost. */
    val screenEffects: Set<String> = setOf(CLAIM_POP, PLAY_WAKE)

    private val manitouStretches = listOf("road-manitou-beach-drive-northeast", "road-northeast-gertie-johnson-road")
    private val manitouWater = GeoPoint(-122.5039, 47.6568)

    val all: List<FxPreset> = listOf(
        FxPreset("fay-5", TreasureGlint.kind, 47.7010, -122.5080, 15.5, listOf("present")),
        FxPreset("island-20of62", ExplorationFog.kind, 47.6450, -122.5350, 12.0, listOf("idle")),
        FxPreset("winslow-unlit", RiddleMarker.KIND, 47.6245, -122.5195, 16.0, listOf("unlit", "lit")),
        FxPreset("manitou-water", KrakenLurk.kind, 47.6625, -122.4985, 14.0, listOf("idle"), warp = 4.0),
        FxPreset("eagle-harbor-x60", FerryCrossing.KIND, 47.6195, -122.4990, 14.0, listOf("run"), warp = 60.0),
        FxPreset("frog-z17", FrogRockStamp.kind, FrogRockStamp.LANDMARK.lat, FrogRockStamp.LANDMARK.lon, 17.0, listOf("engaged", "idle")),
        FxPreset(
            "manitou", KrakenInk.kind, 47.66007, -122.50494, 15.5, listOf("intro", "idle", "repelled"),
            stretches = manitouStretches, water = manitouWater,
        ),
        FxPreset(
            "rockaway", KrakenInk.kind, 47.60070, -122.49995, 15.0, listOf("intro", "idle", "repelled"),
            stretches = listOf("road-rockaway-beach-road-northeast", "road-northeast-south-beach-drive-1", "road-northeast-seaborn-road"),
        ),
        FxPreset(
            "manitou-repelled", KrakenRecede.kind, 47.66007, -122.50494, 15.5, listOf("repelled"),
            stretches = manitouStretches, water = manitouWater,
        ),
        FxPreset("fay-found", TreasureFound.kind, 47.7010, -122.5080, 15.5, listOf("found")),
        FxPreset("winslow-solve", RiddleLight.kind, 47.6245, -122.5195, 16.0, listOf("solve")),
        FxPreset("winslow-dark-3lit", RiddleLight.kind, 47.6245, -122.5195, 16.0, listOf("solve"), theme = "dark"),
        FxPreset("map", CLAIM_POP, 47.6245, -122.5195, 15.0, listOf("pop", "burst")),
        FxPreset("results", CLAIM_POP, 47.7010, -122.5080, 15.0, listOf("results")),
        FxPreset("island-home", PLAY_WAKE, 47.645, -122.535, 12.0, listOf("enabled")),
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
        FrogRockStamp.kind -> listOfNotNull(FrogRockStamp.spec(), if (state == "engaged") FrogRock.hopSpec() else null)
        KrakenInk.kind -> {
            val attack = attack(preset, data)
            val repelled = if (state == "repelled") setOf(preset.stretches.first()) else emptySet()
            KrakenInk.specs(attack, repelled) + listOfNotNull(if (state == "intro") KrakenInk.introSpec(attack, repelled) else null)
        }
        KrakenRecede.kind -> {
            val attack = attack(preset, data)
            val main = preset.stretches.first()
            KrakenInk.specs(attack, setOf(main)) + KrakenRecede.specs(attack, main)
        }
        TreasureFound.kind -> {
            val found = FAY_TREASURES.minBy { hypot(it.lon - preset.lon, it.lat - preset.lat) }
            FAY_TREASURES.mapIndexedNotNull { i, p -> if (p == found) null else TreasureGlint.spec("treasure-fay@showcase#$i", p) } +
                TreasureFound.spentSpec("treasure-fay@showcase", found) + TreasureFound.foundSpec("treasure-fay@showcase", found)
        }
        RiddleLight.kind -> {
            val at = GeoPoint(preset.lon, preset.lat)
            val others = if (preset.theme == "dark") {
                listOf(GeoPoint(preset.lon - 0.0016, preset.lat + 0.0007), GeoPoint(preset.lon + 0.0014, preset.lat - 0.0006))
            } else {
                emptyList()
            }
            others.mapIndexed { i, p -> RiddleMarker.spec("riddle-winslow@showcase-$i", p, lit = true) } +
                RiddleMarker.spec("riddle-winslow@showcase", at, lit = true) + RiddleLight.spec("riddle-winslow@showcase", at)
        }
        else -> emptyList()
    }

    /** Fay Bainbridge Park shore, 5 treasures (effects §3.7). */
    private val FAY_TREASURES = listOf(
        GeoPoint(-122.5090, 47.7028), GeoPoint(-122.5072, 47.7018), GeoPoint(-122.5097, 47.7006),
        GeoPoint(-122.5079, 47.6996), GeoPoint(-122.5110, 47.7012),
    )

    /** The 25 seeded items the wake's glyph wave pops (chart-square centres spread over the island). */
    fun wakeItems(data: FxShowcaseData): List<GeoPoint> {
        val sorted = data.squares.sortedWith(compareBy({ it.row }, { it.col }))
        if (sorted.isEmpty()) return emptyList()
        val step = maxOf(1, sorted.size / 25)
        return sorted.filterIndexed { i, _ -> i % step == 0 }.take(25).map {
            GeoPoint(lon = it.southWest.lon + ChartSquare.D_LON / 2, lat = it.southWest.lat + ChartSquare.D_LAT / 2)
        }
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

// ---- screen-anchored scenes (claim_pop, play_wake): drawn over the showcase map, not by FxHost ----

/** The [preset]'s screen-anchored scene over the map ([FxPresets.screenEffects]); [pausedAt] = a still frame. */
@Composable
fun FxScreenScene(preset: FxPreset, state: String, data: FxShowcaseData, pausedAt: Long?, modifier: Modifier = Modifier) {
    when (preset.effect) {
        CLAIM_POP -> ClaimPopScene(state, pausedAt, modifier)
        PLAY_WAKE -> WakeScene(preset, data, pausedAt, modifier)
    }
}

/**
 * `claim_pop`: a stand-in Play chip at its row-2 place (ux §3.5; the real chip is L2-8's PlayHud, which takes
 * `Modifier.claimPopTarget`), and "+n" from the map centre (`pop` +25, `burst` 5 × +5 in 300 ms = one +25 by
 * coalescing) or from a results sheet's reward chip (`results` +80).
 */
@Composable
private fun ClaimPopScene(state: String, pausedAt: Long?, modifier: Modifier) {
    val reduced = BtTheme.motion.reduced
    val pops = remember { ClaimPopState(total = 1_190) }
    var origin by remember { mutableStateOf<Offset?>(null) }
    val chrome = BtTheme.colors.mapChrome
    val onChrome = BtTheme.colors.onMapChrome
    Box(
        modifier.fillMaxSize().onGloballyPositioned { c ->
            if (state != "results" && origin == null) origin = c.positionInRoot() + Offset(c.size.width / 2f, c.size.height / 2f)
        },
    ) {
        Row(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, top = 8.dp).height(BtTheme.spacing.playChipHeight)
                .claimPopTarget(pops).shadow(3.dp, PillShape).background(chrome, PillShape).padding(start = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FxGlyphImage(FxGlyph.Marks, size = 18.dp, ink = BtTheme.colors.play.seaGlass)
            ChipValue(pops, onChrome)
        }
        if (state == "results") {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Play", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FxGlyphImage(FxGlyph.SeaGlass, size = 32.dp)
                    Text("Sea glass at Fay Bainbridge", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "+80",
                        Modifier.onGloballyPositioned { c -> if (origin == null) origin = c.positionInRoot() + Offset(c.size.width / 2f, c.size.height / 2f) }
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, PillShape).padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = BtTheme.colors.play.seaGlass,
                    )
                }
            }
        }
        ClaimPopOverlay(pops, frozenAtMs = pausedAt)
    }
    val fired = remember { booleanArrayOf(false) }
    LaunchedEffect(origin) {
        val o = origin ?: return@LaunchedEffect
        if (fired[0]) return@LaunchedEffect
        fired[0] = true
        delay(if (pausedAt != null) 0 else 600)
        val base = if (pausedAt != null) 0L else pops.now()
        when (state) {
            "burst" -> for (i in 0 until 5) {
                if (pausedAt == null && i > 0) delay(75)
                pops.pop(5, o, if (pausedAt != null) base + 75L * i else pops.now(), reduced)
            }
            "results" -> pops.pop(80, o, base, reduced)
            else -> pops.pop(25, o, base, reduced)
        }
    }
}

@Composable
private fun ChipValue(pops: ClaimPopState, color: androidx.compose.ui.graphics.Color) {
    val value = pops.chipValue(pops.frame.longValue)
    Text(
        PlayFormat.marks(value) + " · Salal Scout",
        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
        color = color,
    )
}

/** `play_wake` over the island camera, with 25 seeded items; plays once (or holds [pausedAt]). */
@Composable
private fun WakeScene(preset: FxPreset, data: FxShowcaseData, pausedAt: Long?, modifier: Modifier) {
    val art by produceState<PlayWakeArt?>(null, data) {
        delay(1_500) // let the base map draw first: the wake lands on it
        value = PlayWakeArt.load(FxPresets.wakeItems(data), GeoPoint(preset.lon, preset.lat), preset.zoom)
    }
    var shown by remember { mutableStateOf(true) }
    val a = art
    if (a != null && shown) PlayWake(a, onDone = { shown = false }, modifier = modifier, frozenAtMs = pausedAt)
}
