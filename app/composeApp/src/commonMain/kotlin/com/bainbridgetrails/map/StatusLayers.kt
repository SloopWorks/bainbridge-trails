package com.bainbridgetrails.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.all
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.contains
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.neq
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.sources.getBaseSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.MaplibreComposable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Layer ids a tap may hit on the active dataset (ux.md §6.1; roads: docs/roads/ux.md §2.5). */
val TappableTrailLayerIds: Set<String> get() = StatusLayerSpec.current.tappableLayerIds

internal fun statusLayerId(s: TrailStatus) = "app-trail-${s.name.lowercase()}"
internal fun namedNotCountedLayerId(s: TrailStatus) = "app-trail-nc-${s.name.lowercase()}"

/** Road-end pin layer id (docs/roads/ux.md §2.6). */
const val ROAD_END_LAYER = "app-road-ends"

/** The style layer the app's trail layers sit under, so trail names stay readable on top. */
internal const val LABEL_LAYER = "trail-label"

/**
 * Categories that only the roads dataset uses (docs/roads/data-spec.md); mirrors ROAD_CATS in
 * scripts/build-map-styles.py. Empty on the trail dataset, so these checks cost nothing there.
 */
private val RoadCategories = listOf("state_route", "arterial", "collector", "residential", "lane", "driveway")

/** A trail drawn with the selection treatment (ux.md §6.2): its segments' coordinates and its trail-level status. */
data class SelectedTrail(val id: String, val lines: List<List<List<Double>>>, val status: TrailStatus)

/**
 * Trail status drawn app-side (decisions.md TL-1) over the style's "trails" source (source-layer "segments"):
 * one layer per status bucket, filtered by segment id, so a coverage or selection change only changes layer
 * parameters and the base style never reloads. Segments are colored by their **own** covered fraction ([buckets],
 * ux.md §6.4).
 *
 * Encoding (styles.md, never color alone): not started = dots, partway = long dash + casing, nearly done = solid +
 * casing, complete = thick solid (+ glow on Dark). Widths, gates and which extras draw come from [spec] (one code
 * path for trails and roads, docs/roads/ux.md §2.2): on roads the style's category bed *is* "not started" below z15,
 * only progress gets a halo, and uncounted roads are the style's. With a [selected] trail every other line dims and
 * the selected one gets an outline + gap + its trail-level status line on top. [highlight] lines (e.g. the trails a
 * walk added to, P1-6) get the selection outline + gap under their own status lines, no dimming. [roadEnds] draw as
 * pins (roads). [casingFx] is Play's CASING band (engine PF-3, docs/play/plan.md L2-2): composed after the halos and
 * before the casings and status lines; null (pure mode) composes exactly today's layers ([statusLayerPlan]).
 */
@Composable
@MaplibreComposable
fun StatusLayers(
    buckets: Map<TrailStatus, List<String>>,
    style: MapStyle,
    selected: SelectedTrail? = null,
    reducedMotion: Boolean = false,
    highlight: List<List<List<Double>>> = emptyList(),
    spec: StatusLayerSpec = StatusLayerSpec.current,
    roadEnds: List<RoadEnd> = emptyList(),
    casingFx: (@Composable @MaplibreComposable () -> Unit)? = null,
) {
    val trails = getBaseSource<VectorSource>("trails") ?: return // style still loading
    val p = style.palette
    val fade = TransitionOptions(duration = if (reducedMotion) Duration.ZERO else 200.milliseconds)
    val dimF = if (selected != null) spec.dimOpacity else 1f
    val dim = const(dimF)

    val id = feature["id"].asString()
    val counted = feature["included"].asBoolean(const(false))
    val named = feature["trail_ids"].asString(const("")) neq const("")
    val covered = remember(buckets) { buckets.values.flatten() }
    val filters: Map<TrailStatus, Expression<BooleanValue>> = remember(buckets) {
        TrailStatus.entries.associateWith { s ->
            if (s == TrailStatus.NOT_STARTED) {
                if (covered.isEmpty()) const(true) else !(const(covered).contains(id))
            } else {
                const(buckets[s].orEmpty()).contains(id)
            }
        }
    }
    val hasHighlight = highlight.isNotEmpty()
    val hasSelection = selected != null
    val hasRoadEnds = roadEnds.isNotEmpty()
    val hasCasingFx = casingFx != null
    val plan = remember(buckets, spec, p, hasHighlight, hasSelection, hasRoadEnds, hasCasingFx) {
        statusLayerPlan(buckets, spec, p, hasHighlight, hasSelection, hasRoadEnds, hasCasingFx)
    }
    fun minZoom(s: TrailStatus) = if (s == TrailStatus.NOT_STARTED) spec.notStartedMinZoom else 0f
    // Layer filters carry every covered segment id as a literal: build them once per coverage change, not per recomposition.
    val countedFilters = remember(filters) { filters.mapValues { (_, f) -> all(counted, f) } }
    val namedNcFilters = remember(filters) { filters.mapValues { (_, f) -> all(!counted, named, f) } }

    Anchor.Below(LABEL_LAYER) {
        if (plan.hairlines) {
            // Unnamed paths outside the island total: faint hairlines, by category (styles.md). Uncounted *roads*
            // are drawn by the style's own muted roads-ds-private / roads-ds-holdout layers instead.
            val hair = all(!counted, !named, !(const(RoadCategories).contains(feature["category"].asString(const("")))))
            val hairWidth = interpolate(linear(), zoom(), 12 to const(0.4.dp), 15 to const(1.0.dp), 18 to const(1.8.dp))
            LineLayer(
                id = "app-nc-hairline",
                source = trails, sourceLayer = "segments", minZoom = 12f,
                filter = all(hair, feature["category"].asString(const("")) neq const("seasonal_closed"), feature["category"].asString(const("")) neq const("easement")),
                color = switch(feature["category"].asString(const("")), case("beach_access", const(p.notCountedBeach)), fallback = const(p.notCounted)),
                opacity = const(p.notCountedOpacity), width = hairWidth, cap = const(LineCap.Round), join = const(LineJoin.Round),
            )
            LineLayer(
                id = "app-nc-closed",
                source = trails, sourceLayer = "segments", minZoom = 12f,
                filter = all(hair, feature["category"].asString(const("")) eq const("seasonal_closed")),
                color = const(p.notCountedClosed), opacity = const(p.notCountedOpacity), width = hairWidth,
                dasharray = const(listOf(2, 1.5)),
            )
            LineLayer(
                id = "app-nc-easement",
                source = trails, sourceLayer = "segments", minZoom = 12f,
                filter = all(hair, feature["category"].asString(const("")) eq const("easement")),
                color = const(p.notCounted), opacity = const(p.notCountedOpacity), width = hairWidth,
                dasharray = const(listOf(5, 2.5)),
            )
        }

        // Land-colored halo under counted lines (roads: under progress only; the halo gap is what separates a
        // complete line from the steel bed under CVD, docs/roads/ux.md §8.2).
        for (s in plan.halos) LineLayer(
            id = "app-trail-halo-${s.name.lowercase()}",
            source = trails, sourceLayer = "segments", minZoom = minZoom(s),
            filter = countedFilters.getValue(s),
            color = const(p.halo), opacity = const(p.haloOpacity * dimF),
            opacityTransition = fade,
            width = width(spec, s, extra = spec.haloExtra),
            cap = const(LineCap.Round), join = const(LineJoin.Round),
        )
        if (plan.highlight) HighlightLayers(highlight, p, spec)
        // Play's CASING band (engine PF-3): after the halos, under the casings and status lines. Null = pure mode.
        if (casingFx != null) casingFx()
        // Dark casing under Partway and Nearly done.
        for (s in plan.casings) LineLayer(
            id = "app-trail-casing-${s.name.lowercase()}",
            source = trails, sourceLayer = "segments",
            filter = countedFilters.getValue(s),
            color = const(p.casing), opacity = dim, opacityTransition = fade,
            width = width(spec, s, extra = spec.casingExtra),
            cap = const(LineCap.Butt), join = const(LineJoin.Round),
        )
        if (plan.glow) LineLayer(
            id = "app-trail-glow",
            source = trails, sourceLayer = "segments",
            filter = countedFilters.getValue(TrailStatus.COMPLETE),
            color = const(p.complete), opacity = const(p.completeGlow * dimF),
            opacityTransition = fade,
            width = width(spec, TrailStatus.COMPLETE, extra = spec.glowExtra), blur = const(3.dp),
            cap = const(LineCap.Round), join = const(LineJoin.Round),
        )
        for (s in plan.lines) StatusLine(
            id = statusLayerId(s), source = trails, filter = countedFilters.getValue(s),
            status = s, color = p.of(s),
            opacity = const(dimF * if (s == TrailStatus.NOT_STARTED) spec.notStartedOpacity else 1f),
            fade = fade, spec = spec, minZoom = minZoom(s),
        )
        // TL-6: named trails that don't count toward the island: status colors, short even dash.
        for (s in plan.namedNotCounted) LineLayer(
            id = namedNotCountedLayerId(s),
            source = trails, sourceLayer = "segments",
            filter = namedNcFilters.getValue(s),
            color = const(p.of(s)), opacity = dim, opacityTransition = fade,
            width = width(spec, s, scale = StatusLayerSpec.NOT_COUNTED_NAMED_SCALE),
            dasharray = const(StatusLayerSpec.notCountedNamedDash),
            cap = const(LineCap.Butt), join = const(LineJoin.Round),
        )

        if (selected != null) SelectionLayers(selected, p, spec)
        // Pins on top of the selection: a road end sits on its road's last metres.
        if (plan.roadEnds) RoadEndPins(roadEnds, style)
    }
}

/** Back-compat for the Record screen: Topo (or Dark) palette, no selection; [casingFx] as above (null = pure). */
@Composable
@MaplibreComposable
fun StatusLayers(
    buckets: Map<TrailStatus, List<String>>,
    dark: Boolean,
    casingFx: (@Composable @MaplibreComposable () -> Unit)? = null,
) = StatusLayers(buckets, if (dark) MapStyle.DARK else MapStyle.TOPO, casingFx = casingFx)

/** Placeholder id for the `casingFx` slot in [StatusLayerPlan.layerIds] (the slot's own ids are `fx-…`). */
internal const val CASING_FX_SLOT = "<casingFx>"

/**
 * What [StatusLayers] composes, in composition (= z) order, as plain data: the composable reads every "which layers"
 * decision from here, so [layerIds] is the layer list a host test can pin (PlayGateTest: `casingFx = null` is today's
 * list). Statuses run not started (bottom) → complete (top).
 */
internal data class StatusLayerPlan(
    val hairlines: Boolean,
    val halos: List<TrailStatus>,
    val highlight: Boolean,
    val casingFx: Boolean,
    val casings: List<TrailStatus>,
    val glow: Boolean,
    val lines: List<TrailStatus>,
    val namedNotCounted: List<TrailStatus>,
    val selection: Boolean,
    val roadEnds: Boolean,
) {
    fun layerIds(): List<String> = buildList {
        if (hairlines) addAll(listOf("app-nc-hairline", "app-nc-closed", "app-nc-easement"))
        halos.forEach { add("app-trail-halo-${it.name.lowercase()}") }
        if (highlight) addAll(listOf("app-highlight-outline", "app-highlight-gap"))
        if (casingFx) add(CASING_FX_SLOT)
        casings.forEach { add("app-trail-casing-${it.name.lowercase()}") }
        if (glow) add("app-trail-glow")
        lines.forEach { add(statusLayerId(it)) }
        namedNotCounted.forEach { add(namedNotCountedLayerId(it)) }
        if (selection) addAll(listOf("app-selected-outline", "app-selected-gap", "app-selected-line"))
        if (roadEnds) add(ROAD_END_LAYER)
    }
}

internal fun statusLayerPlan(
    buckets: Map<TrailStatus, List<String>>,
    spec: StatusLayerSpec,
    palette: MapPalette,
    highlight: Boolean,
    selection: Boolean,
    roadEnds: Boolean,
    casingFx: Boolean,
): StatusLayerPlan {
    val present = TrailStatus.entries.filter { it == TrailStatus.NOT_STARTED || buckets[it].orEmpty().isNotEmpty() }
    return StatusLayerPlan(
        hairlines = spec.drawUnnamedHairlines,
        halos = present.filter { it in spec.haloStatuses },
        highlight = highlight,
        casingFx = casingFx,
        casings = present.filter { spec.hasCasing(it) },
        glow = palette.completeGlow > 0f && TrailStatus.COMPLETE in present,
        lines = present,
        namedNotCounted = if (spec.drawNamedNotCounted) present else emptyList(),
        selection = selection,
        roadEnds = spec.drawRoadEnds && roadEnds,
    )
}

@Composable
@MaplibreComposable
private fun StatusLine(
    id: String,
    source: VectorSource,
    filter: Expression<BooleanValue>,
    status: TrailStatus,
    color: Color,
    opacity: Expression<FloatValue>,
    fade: TransitionOptions,
    spec: StatusLayerSpec,
    minZoom: Float,
) {
    val dash = spec.dash(status)
    LineLayer(
        id = id,
        source = source, sourceLayer = "segments", minZoom = minZoom,
        filter = filter,
        color = const(color), opacity = opacity, opacityTransition = fade,
        width = width(spec, status),
        dasharray = dash?.let { const(it) },
        cap = const(if (status == TrailStatus.PARTWAY) LineCap.Butt else LineCap.Round),
        join = const(LineJoin.Round),
    )
}

/**
 * Outline (selection color, +6), gap line (+2), then the trail-level status line, from a GeoJSON of the trail.
 * Widths follow [StatusLayerSpec.selectionWidths]; a not-started road (no status line below z15) gets a solid
 * not-started line at that width instead of dots, so the selection always has a body (docs/roads/ux.md §2.5).
 */
@Composable
@MaplibreComposable
private fun SelectionLayers(selected: SelectedTrail, p: MapPalette, spec: StatusLayerSpec) {
    val json = remember(selected.id, selected.lines) { multiLineGeoJson(selected.lines) }
    val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
    val s = selected.status
    val stops = spec.selectionWidths(s)
    val solidNotStarted = s == TrailStatus.NOT_STARTED && spec.selectionMinStatus != null
    LineLayer(
        id = "app-selected-outline", source = source,
        color = const(p.selection), opacity = const(0.9f), width = width(stops, extra = 6f),
        cap = const(LineCap.Round), join = const(LineJoin.Round),
    )
    LineLayer(
        id = "app-selected-gap", source = source,
        color = const(p.selectionGap), width = width(stops, extra = 2f),
        cap = const(LineCap.Round), join = const(LineJoin.Round),
    )
    LineLayer(
        id = "app-selected-line", source = source,
        color = const(p.of(s)),
        width = width(stops),
        dasharray = if (solidNotStarted) null else spec.dash(s)?.let { const(it) },
        cap = const(if (s == TrailStatus.PARTWAY) LineCap.Butt else LineCap.Round),
        join = const(LineJoin.Round),
    )
}

/** Selection outline (+6) and gap (+2) along [lines], drawn under the status lines (walk detail). */
@Composable
@MaplibreComposable
private fun HighlightLayers(lines: List<List<List<Double>>>, p: MapPalette, spec: StatusLayerSpec) {
    val json = remember(lines) { multiLineGeoJson(lines) }
    val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
    val stops = spec.widths.getValue(TrailStatus.NEARLY_DONE)
    LineLayer(
        id = "app-highlight-outline", source = source,
        color = const(p.selection), opacity = const(0.9f), width = width(stops, extra = 6f),
        cap = const(LineCap.Round), join = const(LineJoin.Round),
    )
    LineLayer(
        id = "app-highlight-gap", source = source,
        color = const(p.selectionGap), width = width(stops, extra = 2f),
        cap = const(LineCap.Round), join = const(LineJoin.Round),
    )
}

/**
 * Public road ends at the shore (docs/roads/ux.md §2.6): contour-brown pins ringed in the map's paper color, from z12,
 * 4dp → 7dp. Tapping one selects its road (the map tap prefers a pin within 16dp).
 */
@Composable
@MaplibreComposable
private fun RoadEndPins(ends: List<RoadEnd>, style: MapStyle) {
    val json = remember(ends) { pointsGeoJson(ends) }
    val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
    val ring = if (style.isDark) Color(0xFF0B1410) else Color(0xFFFBFAF6)
    CircleLayer(
        id = ROAD_END_LAYER, source = source, minZoom = 12f,
        color = const(MaterialTheme.colorScheme.tertiary),
        radius = interpolate(linear(), zoom(), 12 to const(4.dp), 16 to const(7.dp)),
        strokeColor = const(ring),
        strokeWidth = const(2.dp),
    )
}

private fun width(spec: StatusLayerSpec, status: TrailStatus, extra: Float = 0f, scale: Float = 1f): Expression<DpValue> =
    width(spec.widths.getValue(status), extra, scale)

private fun width(stops: List<Pair<Int, Float>>, extra: Float = 0f, scale: Float = 1f): Expression<DpValue> {
    val s = stops.map { (z, w) -> z to const((w * scale + extra).dp) }
    // A single stop is a constant: interpolate needs two.
    return if (s.size == 1) s[0].second else interpolate(linear(), zoom(), *s.toTypedArray())
}

/** GeoJSON FeatureCollection of road-end points, each with its `road_id`. */
internal fun pointsGeoJson(ends: List<RoadEnd>): String = buildString {
    append("""{"type":"FeatureCollection","features":[""")
    ends.forEachIndexed { i, e ->
        if (i > 0) append(',')
        append("""{"type":"Feature","properties":{"road_id":"""").append(e.roadId).append('"').append("},")
        append(""""geometry":{"type":"Point","coordinates":[""").append(e.lon).append(',').append(e.lat).append("]}}")
    }
    append("]}")
}

/** GeoJSON FeatureCollection with one MultiLineString of [lines] (each a list of [lon, lat]). */
internal fun multiLineGeoJson(lines: List<List<List<Double>>>): String = buildString {
    append("""{"type":"FeatureCollection","features":[""")
    val drawable = lines.filter { it.size >= 2 }
    if (drawable.isNotEmpty()) {
        append("""{"type":"Feature","properties":{},"geometry":{"type":"MultiLineString","coordinates":[""")
        drawable.forEachIndexed { i, line ->
            if (i > 0) append(',')
            append('[')
            line.forEachIndexed { j, c ->
                if (j > 0) append(',')
                append('[').append(c[0]).append(',').append(c[1]).append(']')
            }
            append(']')
        }
        append("]}}")
    }
    append("]}")
}
