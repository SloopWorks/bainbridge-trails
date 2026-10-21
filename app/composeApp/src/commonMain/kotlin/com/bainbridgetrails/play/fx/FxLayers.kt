package com.bainbridgetrails.play.fx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.bainbridgetrails.map.LABEL_LAYER
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

/**
 * Layer-tier rendering of one band (engine §2.2): every registered kind with live instances contributes its layers,
 * in registry order, on its one hoisted source ([LocalFxSources], from [PlayMapContent]). With no live instances (or no
 * renderers, L2-2) this composes nothing. Recomposes only when the scene's instance set changes, never per frame.
 */
@Composable
@MaplibreComposable
fun FxLayers(host: FxHostState, band: FxBand) {
    require(band.isLayerTier) { "$band is not a Layer-tier band" }
    val byKind = host.liveByKind
    if (byKind.isEmpty()) return
    val ctx = host.layerContext
    val sources = LocalFxSources.current
    for (kind in host.renderers.kinds) {
        val live = byKind[kind] ?: continue
        val renderer = host.renderers[kind] ?: continue
        key(kind) { renderer.Layers(band, live, ctx, sources[kind]) }
    }
}

/** One source per kind with live instances (not one per band, PF-5); recomposes on instance-set changes only. */
@Composable
private fun rememberFxSources(host: FxHostState): FxKindSources {
    val byKind = host.liveByKind
    if (byKind.isEmpty()) return FxKindSources.None
    val ctx = host.layerContext
    val out = LinkedHashMap<String, GeoJsonSource>()
    for (kind in host.renderers.kinds) {
        val live = byKind[kind] ?: continue
        val renderer = host.renderers[kind] ?: continue
        val source = key(kind) { renderer.rememberSource(live, ctx) }
        if (source != null) out[kind] = source
    }
    return FxKindSources(out)
}

/** One step of the map's style composition at the Play call sites (MapTab, RecordScreen). */
sealed interface MapContentPart {
    /** `FxLayers(host, band)`: UNDER_STATUS / OVER_STATUS in `Anchor.Below("trail-label")`, OVER_LABELS in `Anchor.Top`. */
    data class Fx(val band: FxBand) : MapContentPart

    /** `StatusLayers(…, casingFx = …)`: the CASING band rides inside it. */
    data object Status : MapContentPart

    /** `LocationIndicatorLayer` (MapTab) / `TrackLayer` (Record): composed last so it stays above every FX layer. */
    data object Location : MapContentPart
}

/**
 * Style-composition order at the Play call sites (engine PF-3, spike S3: composition order = z-order, and an
 * un-anchored layer composed later lands above an earlier `Anchor.Top` block). MapTab and RecordScreen compose their
 * content in exactly this order; [play] false is today's order.
 */
fun mapContentOrder(play: Boolean, location: Boolean): List<MapContentPart> = buildList {
    if (play) add(MapContentPart.Fx(FxBand.UNDER_STATUS))
    add(MapContentPart.Status)
    if (play) {
        add(MapContentPart.Fx(FxBand.OVER_STATUS))
        add(MapContentPart.Fx(FxBand.OVER_LABELS))
    }
    if (location) add(MapContentPart.Location)
}

/**
 * The map's style content at a Play call site (MapTab, RecordScreen), composed in [mapContentOrder] (PF-3, spike S3):
 * UNDER_STATUS and OVER_STATUS under the trail labels, [status] (handed the CASING band slot, null when Play is off),
 * OVER_LABELS on top, then [location] (puck / live track) above every FX layer. [fx] null = Play off = today's map.
 * Each part is keyed, so a Play toggle adds / removes the FX parts without recreating the status layers. Each kind's
 * source is created here once ([FxRenderer.rememberSource]) and provided to every band through [LocalFxSources].
 */
@Composable
@MaplibreComposable
fun PlayMapContent(
    fx: FxHostState?,
    status: @Composable @MaplibreComposable (casingFx: (@Composable @MaplibreComposable () -> Unit)?) -> Unit,
    location: (@Composable @MaplibreComposable () -> Unit)? = null,
) {
    val casingFx: (@Composable @MaplibreComposable () -> Unit)? =
        remember(fx) { if (fx != null) ({ FxLayers(fx, FxBand.CASING) }) else null }
    val sources = if (fx != null) rememberFxSources(fx) else FxKindSources.None
    CompositionLocalProvider(LocalFxSources provides sources) {
        for (part in mapContentOrder(play = fx != null, location = location != null)) {
            key(part) {
                when (part) {
                    is MapContentPart.Fx -> if (fx != null) FxBandLayers(fx, part.band)
                    MapContentPart.Status -> status(casingFx)
                    MapContentPart.Location -> location?.invoke()
                }
            }
        }
    }
}

/** One Layer-tier band at its anchor: OVER_LABELS on top of the style, the others below the trail labels. */
@Composable
@MaplibreComposable
private fun FxBandLayers(fx: FxHostState, band: FxBand) {
    if (band == FxBand.OVER_LABELS) {
        Anchor.Top { FxLayers(fx, band) }
    } else {
        Anchor.Below(LABEL_LAYER) { FxLayers(fx, band) }
    }
}

/**
 * FX layer ids: `fx-<kind>-<part>` with the kind's underscores as dashes (one layer set per kind; instances are
 * features). Never `app-…`, so `TappableTrailLayerIds` and StatusLayerSpec are untouched (PF-3, PF-9).
 */
object FxLayerIds {
    const val PREFIX = "fx-"

    fun of(kind: String, part: String): String {
        require(kind.isNotBlank() && part.isNotBlank()) { "kind and part must be non-blank" }
        return PREFIX + kind.replace('_', '-') + "-" + part
    }

    fun isFx(layerId: String): Boolean = layerId.startsWith(PREFIX)
}

/** The `fx.*` namespace for global-state keys (spike S1: re-written on every StyleLoaded). */
const val FX_GLOBAL_PREFIX = "fx."

/**
 * GeoJSON FeatureCollection of the geographic anchors of [live]: Point → Point, Line → LineString, Area → Polygon;
 * Screen anchors are skipped. Each feature's top-level numeric `id` is the instance `seq` (feature state works on
 * numeric ids without `promoteId`, spike S2). [properties] appends extra JSON members per feature (no braces).
 */
fun fxGeoJson(live: List<FxLive>, properties: ((FxLive, StringBuilder) -> Unit)? = null): String = buildString {
    append("""{"type":"FeatureCollection","features":[""")
    var first = true
    for (l in live) {
        val anchor = l.instance.spec.anchor
        if (anchor is FxAnchor.Screen) continue
        if (anchor is FxAnchor.Line && anchor.path.size < 2) continue
        if (!first) append(',')
        first = false
        append("""{"type":"Feature","id":""").append(l.instance.seq).append(""","properties":{""")
        properties?.invoke(l, this)
        append("""},"geometry":""")
        when (anchor) {
            is FxAnchor.Point -> { append("""{"type":"Point","coordinates":"""); appendPos(anchor.at); append('}') }
            is FxAnchor.Line -> { append("""{"type":"LineString","coordinates":"""); appendPath(anchor.path); append('}') }
            is FxAnchor.Area -> {
                append("""{"type":"Polygon","coordinates":[""")
                anchor.rings.forEachIndexed { i, r -> if (i > 0) append(','); appendPath(r) }
                append("]}")
            }
            is FxAnchor.Screen -> Unit
        }
        append('}')
    }
    append("]}")
}

private fun StringBuilder.appendPos(p: GeoPoint) {
    append('[').append(p.lon).append(',').append(p.lat).append(']')
}

private fun StringBuilder.appendPath(path: List<GeoPoint>) {
    append('[')
    path.forEachIndexed { i, p -> if (i > 0) append(','); appendPos(p) }
    append(']')
}
