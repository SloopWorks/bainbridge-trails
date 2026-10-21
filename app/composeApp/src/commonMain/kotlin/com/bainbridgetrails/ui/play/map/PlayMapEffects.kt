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
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.data.Loadable
import com.bainbridgetrails.graph.AppGraph
import com.bainbridgetrails.play.PlayDerived
import com.bainbridgetrails.play.PlayRepository
import com.bainbridgetrails.play.config.ItemType
import com.bainbridgetrails.play.fx.FxHostState
import com.bainbridgetrails.play.fx.FxRenderer
import com.bainbridgetrails.play.fx.FxRendererRegistry
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.effects.ExplorationFog
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
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.play.schedule.PlayAgenda
import com.bainbridgetrails.play.time.FerryTimetable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.time.Instant

/**
 * The map's ambient Play scene beyond the markers (plan L2-10; effects § 3.1–3.3, § 3.18): exploration fog over every
 * chart square without counted coverage (cut out of quiet zones), each active kraken attack's ink casing + water pool
 * and its lurk, and the ferry. Built from the agenda, the goal's derived table ([PlayDerived]: chart squares, quiet
 * zones) and the dataset's segment lines; pure. A quiet day spawns nothing (product § 4.6.3: no effects).
 */
object PlayMapEffects {
    /** Roughly the island's middle: the water side of a shore stretch is away from it. */
    val ISLAND_CENTRE = GeoPoint(lon = -122.530, lat = 47.640)

    /** How far offshore the attack's water point sits from its stretches' middle (KrakenAttack pushes it ≥ 60 m). */
    const val WATER_OFFSET_M = 180.0

    /** One active attack with its geometry: [Attack.key] is the occurrence key. */
    fun attacks(agenda: PlayAgenda?, lines: (String) -> List<GeoPoint>?): List<KrakenInk.Attack> {
        if (agenda == null || agenda.day.quietDay) return emptyList()
        return agenda.active.filter { it.occurrence.type == ItemType.obstacle }.mapNotNull { a ->
            val stretches = a.occurrence.placements.mapNotNull { p ->
                val id = p.featureId ?: return@mapNotNull null
                val ls = p.segmentIds.mapNotNull(lines).filter { it.size >= 2 }
                if (ls.isEmpty()) null else KrakenInk.Stretch(id, ls)
            }
            if (stretches.isEmpty()) return@mapNotNull null
            KrakenInk.Attack(a.occurrence.key, stretches, water(stretches), active = true)
        }
    }

    /** Stretch (feature) ids of [agenda]'s attacks the user has repelled (their placement keys are found). */
    fun repelled(agenda: PlayAgenda?): Set<String> = agenda?.active.orEmpty()
        .filter { it.occurrence.type == ItemType.obstacle }
        .flatMap { a -> a.occurrence.placements.filter { it.key in a.found || a.occurrence.key in a.found }.mapNotNull { it.featureId } }
        .toSet()

    /** The water point: the stretches' middle pushed [WATER_OFFSET_M] away from [ISLAND_CENTRE]. */
    fun water(stretches: List<KrakenInk.Stretch>): GeoPoint {
        val pts = stretches.flatMap { s -> s.lines.flatten() }
        return offshore(pts.sumOf { it.lon } / pts.size, pts.sumOf { it.lat } / pts.size)
    }

    /** Each active attack's water point by occurrence key (the kraken marker's anchor, ux.md §2.2). */
    fun waterPoints(agenda: PlayAgenda?, lines: (String) -> List<GeoPoint>?): Map<String, GeoPoint> =
        attacks(agenda, lines).associate { it.key to it.water }

    /** [lon]/[lat] pushed [WATER_OFFSET_M] away from [ISLAND_CENTRE] (the shore's seaward side). */
    fun offshore(lon: Double, lat: Double): GeoPoint {
        val kx = M_PER_DEG * cos(lat * PI / 180.0)
        val dx = (lon - ISLAND_CENTRE.lon) * kx
        val dy = (lat - ISLAND_CENTRE.lat) * M_PER_DEG
        val d = hypot(dx, dy).takeIf { it > 1e-6 } ?: return GeoPoint(lat = lat, lon = lon)
        return GeoPoint(lat = lat + dy / d * WATER_OFFSET_M / M_PER_DEG, lon = lon + dx / d * WATER_OFFSET_M / kx)
    }

    /**
     * Every ambient spec: fog ([covered] = segment ids with any coverage), the attacks' casing + pool + lurk, and the
     * ferry when [ferry]. Empty on a quiet day.
     */
    fun specs(
        agenda: PlayAgenda?,
        derived: PlayDerived,
        lines: (String) -> List<GeoPoint>?,
        covered: Collection<String>,
        ferry: Boolean,
    ): List<FxSpec> {
        if (agenda == null || agenda.day.quietDay) return emptyList()
        val out = ArrayList<FxSpec>()
        if (derived.squares.isNotEmpty()) out += ExplorationFog.specs(derived.squares, derived.lifted(covered), derived.quietZones.map { it.ring })
        val repelled = repelled(agenda)
        for (attack in attacks(agenda, lines)) {
            val casing = KrakenInk.specs(attack, repelled)
            out += casing
            if (casing.isNotEmpty()) out += KrakenLurk.spec("${attack.key}#lurk", attack.water)
        }
        if (ferry) out += FerryCrossing.spec()
        return out
    }

    /** The arrival intro (HERO) of each attack not yet introduced in this process. */
    fun intros(agenda: PlayAgenda?, lines: (String) -> List<GeoPoint>?, seen: Set<String>): List<FxSpec> {
        val repelled = repelled(agenda)
        return attacks(agenda, lines).filter { it.key !in seen }.mapNotNull { KrakenInk.introSpec(it, repelled) }
    }

    /** Covered segment ids of a progress snapshot's buckets (only covered buckets are present). */
    fun covered(buckets: Map<TrailStatus, List<String>>): Set<String> =
        buckets.filterKeys { it != TrailStatus.NOT_STARTED }.values.flatMapTo(HashSet()) { it }

    private const val M_PER_DEG = 111_195.0
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
            ExplorationFog, KrakenLurk, KrakenAttack(), KrakenRecede, FerryCrossing(ferry, now = now),
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
 * Keeps [fx]'s ambient scene ([PlayMapEffects.specs]) in step with [play]'s agenda and the coverage snapshot, plays each
 * attack's intro once per process, and attaches [fx] to [play]'s live find moments (only kinds [fx] can draw).
 * Nothing per frame: specs are rebuilt when the agenda or the covered set changes.
 */
@Composable
fun PlayMapEffectsSync(fx: FxHostState?, play: PlayRepository?, buckets: Map<TrailStatus, List<String>>, ferry: Boolean, holdIntros: Boolean = false) {
    if (fx == null || play == null) return
    val agenda by play.agenda.collectAsStateWithLifecycle()
    val derived = produceState(PlayDerived.Empty, play) { value = play.derivedTable() }.value
    val dataset = AppGraph.datasets.state.collectAsStateWithLifecycle().value as? Loadable.Ready
    // Built off Main (AC 9 P3): every segment's coordinates are converted once per dataset.
    val lines = produceState<(String) -> List<GeoPoint>?>({ null }, dataset) {
        value = withContext(Dispatchers.Default) {
            val segs = dataset?.value?.dataset?.segments?.associate { s -> s.id to s.coords.map { GeoPoint(lon = it[0], lat = it[1]) } }.orEmpty()
            val f: (String) -> List<GeoPoint>? = segs::get
            f
        }
    }.value
    val covered = remember(buckets) { PlayMapEffects.covered(buckets) }
    val specs = produceState(emptyList<FxSpec>(), agenda, derived, lines, covered, ferry) {
        value = withContext(Dispatchers.Default) { PlayMapEffects.specs(agenda, derived, lines, covered, ferry) }
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
    LaunchedEffect(fx, agenda, lines, holdIntros) {
        if (holdIntros) return@LaunchedEffect
        for (intro in PlayMapEffects.intros(agenda, lines, introduced)) {
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
