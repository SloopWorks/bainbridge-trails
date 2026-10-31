package com.bainbridgetrails.ui.play.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bainbridgetrails.data.Loadable
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.play.PlayRepository
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.fx.FxHostState
import com.bainbridgetrails.play.fx.FxRenderer
import com.bainbridgetrails.play.fx.FxRendererRegistry
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.effects.FerryCrossing
import com.bainbridgetrails.play.fx.effects.FrogRock
import com.bainbridgetrails.play.fx.effects.KrakenAttack
import com.bainbridgetrails.play.fx.effects.KrakenInk
import com.bainbridgetrails.play.fx.effects.KrakenLurk
import com.bainbridgetrails.play.fx.effects.KrakenRecede
import com.bainbridgetrails.play.fx.effects.RiddleLight
import com.bainbridgetrails.play.fx.effects.RiddleMarker
import com.bainbridgetrails.play.fx.effects.TreasureFound
import com.bainbridgetrails.play.fx.effects.TreasureGlint
import com.bainbridgetrails.play.geo.GeoMath
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.schedule.PlayAgenda
import com.bainbridgetrails.play.time.FerryTimetable
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.ui.components.IslandGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.time.Instant

/**
 * The map's ambient Play scene beyond the markers: each active kraken attack's ink casing + water pool and its lurk,
 * and the ferry. Exploration fog is showcase-only (2026-10-30 map cleanup); live geography stays clear regardless
 * of coverage. Built from the agenda and dataset's segment lines; pure. A quiet day spawns nothing.
 */
object PlayMapEffects {
    /** Roughly the island's middle: the water side of a shore stretch is away from it. */
    val ISLAND_CENTRE = GeoPoint(lon = -122.530, lat = 47.640)

    /** How far offshore the attack's water point sits from its stretches' middle (KrakenAttack pushes it ≥ 60 m). */
    const val WATER_OFFSET_M = 180.0

    /** One active attack with its geometry: [Attack.key] is the occurrence key; [coast] places its water point. */
    fun attacks(agenda: PlayAgenda?, lines: (String) -> List<GeoPoint>?, coast: Coast? = null): List<KrakenInk.Attack> {
        if (agenda == null || agenda.day.quietDay) return emptyList()
        return agenda.active.filter { it.occurrence.type == ItemType.obstacle }.mapNotNull { a ->
            val stretches = a.occurrence.placements.mapNotNull { p ->
                val id = p.featureId ?: return@mapNotNull null
                val ls = p.segmentIds.mapNotNull(lines).filter { it.size >= 2 }
                if (ls.isEmpty()) null else KrakenInk.Stretch(id, ls)
            }
            if (stretches.isEmpty()) return@mapNotNull null
            KrakenInk.Attack(a.occurrence.key, stretches, water(stretches, coast), active = true)
        }
    }

    /** Stretch (feature) ids of [agenda]'s attacks the user has repelled (their placement keys are found). */
    fun repelled(agenda: PlayAgenda?): Set<String> = agenda?.active.orEmpty()
        .filter { it.occurrence.type == ItemType.obstacle }
        .flatMap { a -> a.occurrence.placements.filter { it.key in a.found || a.occurrence.key in a.found }.mapNotNull { it.featureId } }
        .toSet()

    /**
     * The attack's water point (the kraken label, lurk and pool anchor; e2e run 1 F6): beside its longest stretch, never
     * the middle of the whole coast pool (K5's Manitou + Gertie Johnson middle lay inland, on Falk Rd NE). From the
     * stretch's middle, the nearest point in the water at least [Coast.MIN_OFFSHORE_M] off the [coast]; without a
     * coastline, the stretch's middle pushed [WATER_OFFSET_M] away from [ISLAND_CENTRE].
     */
    fun water(stretches: List<KrakenInk.Stretch>, coast: Coast? = null): GeoPoint {
        val main = stretches.maxBy { s -> s.lines.sumOf { GeoMath.lineLengthM(it) } }
        val pts = main.lines.flatten()
        val cLon = pts.sumOf { it.lon } / pts.size
        val cLat = pts.sumOf { it.lat } / pts.size
        val mid = nearestOn(main.lines, GeoPoint(lon = cLon, lat = cLat))
        return coast?.waterNear(mid) ?: offshore(mid.lon, mid.lat)
    }

    /** The point of [lines] nearest [p] (local equirectangular metres). */
    private fun nearestOn(lines: List<List<GeoPoint>>, p: GeoPoint): GeoPoint {
        val kx = M_PER_DEG * cos(p.lat * PI / 180.0)
        var best = Double.MAX_VALUE
        var out = lines.first().first()
        for (l in lines) for (i in 0 until l.size - 1) {
            val ax = (l[i].lon - p.lon) * kx
            val ay = (l[i].lat - p.lat) * M_PER_DEG
            val sx = (l[i + 1].lon - l[i].lon) * kx
            val sy = (l[i + 1].lat - l[i].lat) * M_PER_DEG
            val len2 = sx * sx + sy * sy
            val u = if (len2 < 1e-12) 0.0 else (-(ax * sx + ay * sy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + sx * u, ay + sy * u)
            if (d < best) {
                best = d
                out = GeoPoint(lon = l[i].lon + (l[i + 1].lon - l[i].lon) * u, lat = l[i].lat + (l[i + 1].lat - l[i].lat) * u)
            }
        }
        return out
    }

    /** Each active attack's water point by occurrence key (the kraken marker's anchor, ux.md §2.2). */
    fun waterPoints(agenda: PlayAgenda?, lines: (String) -> List<GeoPoint>?, coast: Coast? = null): Map<String, GeoPoint> =
        attacks(agenda, lines, coast).associate { it.key to it.water }

    private var coastMemo: Coast? = null

    /** The bundled island coastline (`files/coastline.json`), read once per process (IO, then parsed on Default). */
    suspend fun loadCoast(): Coast {
        coastMemo?.let { return it }
        val json = withContext(Dispatchers.IO) { runCatching { Res.readBytes("files/coastline.json").decodeToString() }.getOrNull() }
        return withContext(Dispatchers.Default) { Coast(json?.let { IslandGeometry.parseCoastline(it) }.orEmpty()) }.also { coastMemo = it }
    }

    /** [lon]/[lat] pushed [WATER_OFFSET_M] away from [ISLAND_CENTRE] (the shore's seaward side). */
    fun offshore(lon: Double, lat: Double): GeoPoint {
        val kx = M_PER_DEG * cos(lat * PI / 180.0)
        val dx = (lon - ISLAND_CENTRE.lon) * kx
        val dy = (lat - ISLAND_CENTRE.lat) * M_PER_DEG
        val d = hypot(dx, dy).takeIf { it > 1e-6 } ?: return GeoPoint(lat = lat, lon = lon)
        return GeoPoint(lat = lat + dy / d * WATER_OFFSET_M / M_PER_DEG, lon = lon + dx / d * WATER_OFFSET_M / kx)
    }

    /**
     * Every ambient spec: the attacks' casing + pool + lurk and the ferry when [ferry]. Empty on a quiet day.
     */
    fun specs(
        agenda: PlayAgenda?,
        lines: (String) -> List<GeoPoint>?,
        ferry: Boolean,
        coast: Coast? = null,
    ): List<FxSpec> {
        if (agenda == null || agenda.day.quietDay) return emptyList()
        val out = ArrayList<FxSpec>()
        val repelled = repelled(agenda)
        for (attack in attacks(agenda, lines, coast)) {
            val casing = KrakenInk.specs(attack, repelled)
            out += casing
            if (casing.isNotEmpty()) out += KrakenLurk.spec("${attack.key}#lurk", attack.water)
        }
        if (ferry) out += FerryCrossing.spec()
        return out
    }

    /** The arrival intro (HERO) of each attack not yet introduced in this process. */
    fun intros(agenda: PlayAgenda?, lines: (String) -> List<GeoPoint>?, seen: Set<String>, coast: Coast? = null): List<FxSpec> {
        val repelled = repelled(agenda)
        return attacks(agenda, lines, coast).filter { it.key !in seen }.mapNotNull { KrakenInk.introSpec(it, repelled) }
    }

    private const val M_PER_DEG = 111_195.0
}


/**
 * The island's coastline ([rings] of [lon, lat], `files/coastline.json`; land = inside an odd number of rings), for
 * placing kraken water points in the water (e2e run 1 F6). Pure; build once and share.
 */
class Coast(private val rings: List<List<DoubleArray>>) {
    val isEmpty: Boolean get() = rings.isEmpty()

    /** Inside the island (even-odd over every ring). False without a coastline. */
    fun isLand(p: GeoPoint): Boolean {
        var inside = false
        for (r in rings) {
            var j = r.lastIndex
            for (i in r.indices) {
                val xi = r[i][0]
                val yi = r[i][1]
                val xj = r[j][0]
                val yj = r[j][1]
                if ((yi > p.lat) != (yj > p.lat) && p.lon < (xj - xi) * (p.lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }

    /** Metres from [p] to the nearest coastline edge; +∞ without a coastline. */
    fun distanceM(p: GeoPoint): Double {
        val kx = M_PER_DEG * cos(p.lat * PI / 180.0)
        var best = Double.POSITIVE_INFINITY
        for (r in rings) {
            for (i in r.indices) {
                val a = r[if (i == 0) r.lastIndex else i - 1]
                val b = r[i]
                val ax = (a[0] - p.lon) * kx
                val ay = (a[1] - p.lat) * M_PER_DEG
                val sx = (b[0] - a[0]) * kx
                val sy = (b[1] - a[1]) * M_PER_DEG
                val len2 = sx * sx + sy * sy
                val u = if (len2 < 1e-12) 0.0 else (-(ax * sx + ay * sy) / len2).coerceIn(0.0, 1.0)
                best = min(best, hypot(ax + sx * u, ay + sy * u))
            }
        }
        return best
    }

    /**
     * The nearest water point to [p] at least [MIN_OFFSHORE_M] off the coast: rings of [BEARINGS] points every
     * [STEP_M] out to [MAX_SEARCH_M]; on the first ring with any, the one farthest from the coast. Null when none (or no
     * coastline).
     */
    fun waterNear(p: GeoPoint): GeoPoint? {
        if (rings.isEmpty()) return null
        val kx = M_PER_DEG * cos(p.lat * PI / 180.0)
        var r = STEP_M * 2
        while (r <= MAX_SEARCH_M) {
            var best: GeoPoint? = null
            var bestD = MIN_OFFSHORE_M
            for (k in 0 until BEARINGS) {
                val t = 2 * PI * k / BEARINGS
                val q = GeoPoint(lon = p.lon + r * cos(t) / kx, lat = p.lat + r * sin(t) / M_PER_DEG)
                if (isLand(q)) continue
                val d = distanceM(q)
                if (d >= bestD) {
                    bestD = d
                    best = q
                }
            }
            if (best != null) return best
            r += STEP_M
        }
        return null
    }

    companion object {
        /** A water point sits at least this far off the coastline (the label never touches the shore). */
        const val MIN_OFFSHORE_M = 40.0
        const val STEP_M = 20.0
        const val MAX_SEARCH_M = 1_000.0
        const val BEARINGS = 36
        private const val M_PER_DEG = 111_195.0
    }
}

/**
 * Attack keys whose intro played to its end in this process (a tab switch never replays it). An intro is marked only
 * once its instance has been live and is gone (DONE), so a registry rebuild or a held intro never loses it.
 */
private val introduced = HashSet<String>()

/** An intro that never becomes live (queued behind another HERO this long) is not marked; it plays next time. */
private const val INTRO_START_TIMEOUT_MS = 30_000L

/** True while [id] is a live instance in [fx] (snapshot state: readable from `snapshotFlow`). */
private fun FxHostState.isLive(id: String): Boolean = liveByKind.values.any { l -> l.any { it.instance.spec.id == id } }

/**
 * The Play renderers of a live map (MapTab, RecordScreen): one per kind (`KrakenAttack` carries the kraken_attack Layer
 * parts, `FrogRock` the frog stamp + hop). [ferry] is the pack's timetable (null: no sprite), [now] the Play clock.
 */
fun playLiveRegistry(markers: PlayMarkers?, ferry: FerryTimetable?, now: () -> Instant): FxRendererRegistry =
    FxRendererRegistry(
        listOfNotNull<FxRenderer>(
            KrakenLurk, KrakenAttack(), KrakenRecede, FerryCrossing(ferry, now = now),
            FrogRock, RiddleMarker(), RiddleLight, TreasureGlint, TreasureFound, markers,
        ),
    )

/** The running pack's ferry timetable for [play] (null while loading or when none). */
@Composable
fun rememberFerryTimetable(play: PlayRepository?): FerryTimetable? {
    val config = play?.config?.collectAsStateWithLifecycle()?.value?.config
    return produceState<FerryTimetable?>(null, play, config) { value = play?.ferryTimetable() }.value
}

/**
 * Keeps [fx]'s ambient scene ([PlayMapEffects.specs]) in step with [play]'s agenda, plays each
 * attack's intro once per process, and attaches [fx] to [play]'s live find moments (only kinds [fx] can draw).
 * Nothing per frame: specs are rebuilt when the agenda or its geometry changes.
 */
@Composable
fun PlayMapEffectsSync(fx: FxHostState?, play: PlayRepository?, ferry: Boolean, holdIntros: Boolean = false) {
    if (fx == null || play == null) return
    val agenda by play.agenda.collectAsStateWithLifecycle()
    val dataset = AppGraph.datasets.state.collectAsStateWithLifecycle().value as? Loadable.Ready
    // Built off Main (AC 9 P3): every segment's coordinates are converted once per dataset.
    val lines = produceState<(String) -> List<GeoPoint>?>({ null }, dataset) {
        value = withContext(Dispatchers.Default) {
            val segs = dataset?.value?.dataset?.segments?.associate { s -> s.id to s.coords.map { GeoPoint(lon = it[0], lat = it[1]) } }.orEmpty()
            val f: (String) -> List<GeoPoint>? = segs::get
            f
        }
    }.value
    val coast = produceState<Coast?>(null) { value = PlayMapEffects.loadCoast() }.value ?: return
    val specs = produceState(emptyList<FxSpec>(), agenda, lines, ferry, coast) {
        value = withContext(Dispatchers.Default) { PlayMapEffects.specs(agenda, lines, ferry, coast) }
    }.value
    val spawned = remember(fx) { HashSet<String>() }
    LaunchedEffect(fx, specs) {
        val want = specs.associateBy { it.id }
        for (id in spawned.toList()) if (id !in want) {
            fx.dismiss(id)
            spawned -= id
        }
        for (s in specs) if (s.id !in spawned) {
            fx.spawn(s)
            spawned += s.id
        }
    }
    // Intros wait while the wake plays (never under its wash) and are marked introduced only at DONE. The watcher
    // outlives an agenda tick (the host's scope), so a playing intro is never spawned twice.
    val introScope = rememberCoroutineScope()
    val playing = remember(fx) { HashSet<String>() }
    LaunchedEffect(fx, agenda, lines, holdIntros, coast) {
        if (holdIntros) return@LaunchedEffect
        for (intro in PlayMapEffects.intros(agenda, lines, introduced, coast)) {
            if (!playing.add(intro.id)) continue
            fx.spawn(intro)
            introScope.launch {
                withTimeoutOrNull(INTRO_START_TIMEOUT_MS) { snapshotFlow { fx.isLive(intro.id) }.first { it } } ?: return@launch
                snapshotFlow { fx.isLive(intro.id) }.first { !it }
                introduced += intro.id.substringBefore('#')
            }.invokeOnCompletion { playing -= intro.id }
        }
    }
    DisposableEffect(fx, play) {
        val detach = play.attachFx { spec -> if (fx.renderers[spec.kind] != null) fx.spawn(spec) }
        onDispose { detach() }
    }
}
