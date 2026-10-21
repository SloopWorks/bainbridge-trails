package com.bainbridgetrails.play.fx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.maplibre.compose.util.MaplibreComposable

/**
 * Layer-tier rendering of one band (engine §2.2): every registered kind with live instances contributes its layers,
 * in registry order. With no live instances (or no renderers, L2-2) this composes nothing. Recomposes only when the
 * scene's instance set changes, never per frame.
 */
@Composable
@MaplibreComposable
fun FxLayers(host: FxHostState, band: FxBand) {
    require(band.isLayerTier) { "$band is not a Layer-tier band" }
    val byKind = host.liveByKind
    if (byKind.isEmpty()) return
    val ctx = host.layerContext
    for (kind in host.renderers.kinds) {
        val live = byKind[kind] ?: continue
        val renderer = host.renderers[kind] ?: continue
        key(kind) { renderer.Layers(band, live, ctx) }
    }
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
