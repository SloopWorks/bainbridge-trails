package com.bainbridgetrails.ui.play.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.bainbridgetrails.play.config.PlayConfig
import com.bainbridgetrails.play.fx.FxHostState
import com.bainbridgetrails.play.fx.FxRendererRegistry
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.play.fx.effects.FrogRockStamp
import com.bainbridgetrails.play.fx.effects.RiddleMarker
import com.bainbridgetrails.play.fx.effects.TreasureGlint
import com.bainbridgetrails.play.schedule.PlaceRef
import com.bainbridgetrails.play.schedule.PoolRef
import com.bainbridgetrails.play.schedule.Ref
import com.bainbridgetrails.play.schedule.RoadRef
import com.bainbridgetrails.play.schedule.TrailRef

/**
 * The map's Play registry (MapTab): L2-4's marker effects, then [PlayMarkers] last so its ring, label and hit rects
 * sit above every other OVER_LABELS layer. (Fog, ferry and kraken ink need dataset geometry: wired in L2-10.)
 */
fun playMapRegistry(markers: PlayMarkers): FxRendererRegistry =
    FxRendererRegistry(listOf(FrogRockStamp, RiddleMarker(), TreasureGlint, markers))

/** The FX instances for [markers]: unfound treasure glints, riddle lanterns (lit when solved), Frog Rock, the marker set. */
fun playMapSpecs(markers: List<PlayMarker>): List<FxSpec> = buildList {
    for (m in markers) when {
        m.kind == MarkerKind.TREASURE && !m.found -> add(TreasureGlint.spec(m.key, GeoPoint(m.lon, m.lat)))
        m.kind == MarkerKind.RIDDLE && !m.found -> add(RiddleMarker.spec(m.key, GeoPoint(m.lon, m.lat), lit = m.lit))
        m.itemId == "lore-frog" -> add(FrogRockStamp.spec())
    }
    add(PlayMarkers.spec())
}

/** Where a solved riddle's Walk there goes: its answer's line, or the place's trigger point. */
fun riddleTarget(answer: String?, config: PlayConfig): WalkTarget? = when (val r = answer?.let(Ref::parse)) {
    is TrailRef -> WalkTarget.Feature(r.id)
    is RoadRef -> WalkTarget.Feature(r.id)
    is PlaceRef -> config.places[r.id]?.let { WalkTarget.Point(it.lat, it.lon) }
    is PoolRef -> config.pools[r.id]?.members?.firstNotNullOfOrNull { riddleTarget(it, config) as? WalkTarget.Feature }
    else -> null
}

/**
 * Keeps [fx]'s scene and [renderer] in step with the agenda's [markers] (Main): spawns new instances, dismisses
 * stale ones, and hands the marker set, the selection and the coast label to [PlayMarkers]. Nothing per frame.
 */
@Composable
fun PlayMapSync(fx: FxHostState?, renderer: PlayMarkers, markers: List<PlayMarker>, selected: String?, coastLabel: String?) {
    SideEffect {
        renderer.markers = markers
        renderer.clusters = PlayMarkerLogic.clusters(markers)
        renderer.selected = selected
        renderer.coastLabel = coastLabel
    }
    if (fx == null) return
    val spawned = remember(fx) { HashSet<String>() }
    val specs = remember(markers) { playMapSpecs(markers) }
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
}
