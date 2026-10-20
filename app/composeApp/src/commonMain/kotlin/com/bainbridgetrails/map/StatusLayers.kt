package com.bainbridgetrails.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import org.maplibre.compose.expressions.ast.Expression
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
import org.maplibre.compose.expressions.dsl.all
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.sources.getBaseSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.MaplibreComposable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Layer ids a tap may hit (ux.md §6.1): counted status lines + named not-counted lines (TL-6), never hairlines/labels. */
val TappableTrailLayerIds: Set<String> =
    TrailStatus.entries.flatMap { listOf(statusLayerId(it), namedNotCountedLayerId(it)) }.toSet()

internal fun statusLayerId(s: TrailStatus) = "app-trail-${s.name.lowercase()}"
internal fun namedNotCountedLayerId(s: TrailStatus) = "app-trail-nc-${s.name.lowercase()}"

/** The style layer the app's trail layers sit under, so trail names stay readable on top. */
private const val LABEL_LAYER = "trail-label"

/** A trail drawn with the selection treatment (ux.md §6.2): its segments' coordinates and its trail-level status. */
data class SelectedTrail(val id: String, val lines: List<List<List<Double>>>, val status: TrailStatus)

/**
 * Trail status drawn app-side (decisions.md TL-1) over the style's "trails" source (source-layer "segments"):
 * one layer per status bucket, filtered by segment id, so a coverage or selection change only changes layer
 * parameters and the base style never reloads. Segments are colored by their **own** covered fraction ([buckets],
 * ux.md §6.4).
 *
 * Encoding (styles.md, never color alone): not started = dots, partway = long dash + casing, nearly done = solid +
 * casing, complete = thick solid (+ glow on Dark). Named trails outside the island total (TL-6) use the same colors
 * with a short even dash; unnamed not-counted paths are faint hairlines. With a [selected] trail every other trail
 * dims to 45% and the selected one gets an outline + gap + its trail-level status line on top.
 */
@Composable
@MaplibreComposable
fun StatusLayers(
    buckets: Map<TrailStatus, List<String>>,
    style: MapStyle,
    selected: SelectedTrail? = null,
    reducedMotion: Boolean = false,
) {
    val trails = getBaseSource<VectorSource>("trails") ?: return // style still loading
    val p = style.palette
    val fade = TransitionOptions(duration = if (reducedMotion) Duration.ZERO else 200.milliseconds)
    val dim = const(if (selected != null) TrailLineSpec.DIM_OPACITY else 1f)

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
    fun present(s: TrailStatus) = s == TrailStatus.NOT_STARTED || buckets[s].orEmpty().isNotEmpty()
    // Layer filters carry every covered segment id as a literal: build them once per coverage change, not per recomposition.
    val countedFilters = remember(filters) { filters.mapValues { (_, f) -> all(counted, f) } }
    val namedNcFilters = remember(filters) { filters.mapValues { (_, f) -> all(!counted, named, f) } }

    Anchor.Below(LABEL_LAYER) {
        // Unnamed paths outside the island total: faint hairlines, by category (styles.md).
        val hair = all(!counted, !named)
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

        val order = TrailStatus.entries // not started (bottom) → complete (top)
        // Land-colored halo under every counted line.
        for (s in order) if (present(s)) LineLayer(
            id = "app-trail-halo-${s.name.lowercase()}",
            source = trails, sourceLayer = "segments",
            filter = countedFilters.getValue(s),
            color = const(p.halo), opacity = const(p.haloOpacity * if (selected != null) TrailLineSpec.DIM_OPACITY else 1f),
            opacityTransition = fade,
            width = width(s, extra = TrailLineSpec.HALO_EXTRA),
            cap = const(LineCap.Round), join = const(LineJoin.Round),
        )
        // Dark casing under Partway and Nearly done.
        for (s in order) if (present(s) && TrailLineSpec.hasCasing(s)) LineLayer(
            id = "app-trail-casing-${s.name.lowercase()}",
            source = trails, sourceLayer = "segments",
            filter = countedFilters.getValue(s),
            color = const(p.casing), opacity = dim, opacityTransition = fade,
            width = width(s, extra = TrailLineSpec.CASING_EXTRA),
            cap = const(LineCap.Butt), join = const(LineJoin.Round),
        )
        if (p.completeGlow > 0f && present(TrailStatus.COMPLETE)) LineLayer(
            id = "app-trail-glow",
            source = trails, sourceLayer = "segments",
            filter = countedFilters.getValue(TrailStatus.COMPLETE),
            color = const(p.complete), opacity = const(p.completeGlow * if (selected != null) TrailLineSpec.DIM_OPACITY else 1f),
            opacityTransition = fade,
            width = width(TrailStatus.COMPLETE, extra = 4f), blur = const(3.dp),
            cap = const(LineCap.Round), join = const(LineJoin.Round),
        )
        for (s in order) if (present(s)) StatusLine(
            id = statusLayerId(s), source = trails, filter = countedFilters.getValue(s),
            status = s, color = p.of(s), opacity = dim, fade = fade,
        )
        // TL-6: named trails that don't count toward the island: status colors, short even dash.
        for (s in order) if (present(s)) LineLayer(
            id = namedNotCountedLayerId(s),
            source = trails, sourceLayer = "segments",
            filter = namedNcFilters.getValue(s),
            color = const(p.of(s)), opacity = dim, opacityTransition = fade,
            width = width(s, scale = TrailLineSpec.NOT_COUNTED_NAMED_SCALE),
            dasharray = const(TrailLineSpec.notCountedNamedDash),
            cap = const(LineCap.Butt), join = const(LineJoin.Round),
        )

        if (selected != null) SelectionLayers(selected, p)
    }
}

/** Back-compat for the Record screen: Topo (or Dark) palette, no selection. */
@Composable
@MaplibreComposable
fun StatusLayers(buckets: Map<TrailStatus, List<String>>, dark: Boolean) =
    StatusLayers(buckets, if (dark) MapStyle.DARK else MapStyle.TOPO)

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
) {
    val dash = TrailLineSpec.dash(status)
    LineLayer(
        id = id,
        source = source, sourceLayer = "segments",
        filter = filter,
        color = const(color), opacity = opacity, opacityTransition = fade,
        width = width(status),
        dasharray = dash?.let { const(it) },
        cap = const(if (status == TrailStatus.PARTWAY) LineCap.Butt else LineCap.Round),
        join = const(LineJoin.Round),
    )
}

/** Outline (selection color, +6), gap line (+2), then the trail-level status line, from a GeoJSON of the trail. */
@Composable
@MaplibreComposable
private fun SelectionLayers(selected: SelectedTrail, p: MapPalette) {
    val json = remember(selected.id, selected.lines) { multiLineGeoJson(selected.lines) }
    val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
    val s = selected.status
    LineLayer(
        id = "app-selected-outline", source = source,
        color = const(p.selection), opacity = const(0.9f), width = width(s, extra = 6f),
        cap = const(LineCap.Round), join = const(LineJoin.Round),
    )
    LineLayer(
        id = "app-selected-gap", source = source,
        color = const(p.selectionGap), width = width(s, extra = 2f),
        cap = const(LineCap.Round), join = const(LineJoin.Round),
    )
    LineLayer(
        id = "app-selected-line", source = source,
        color = const(p.of(s)),
        width = width(s),
        dasharray = TrailLineSpec.dash(s)?.let { const(it) },
        cap = const(if (s == TrailStatus.PARTWAY) LineCap.Butt else LineCap.Round),
        join = const(LineJoin.Round),
    )
}

private fun width(status: TrailStatus, extra: Float = 0f, scale: Float = 1f): Expression<DpValue> {
    val stops = TrailLineSpec.widths.getValue(status).map { (z, w) -> z to const((w * scale + extra).dp) }
    return interpolate(linear(), zoom(), *stops.toTypedArray())
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
