package com.bainbridgetrails.ui.play.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bainbridgetrails.play.fx.FxAnchor
import com.bainbridgetrails.play.fx.FxArt
import com.bainbridgetrails.play.fx.FxBand
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxLayerContext
import com.bainbridgetrails.play.fx.FxLayerIds
import com.bainbridgetrails.play.fx.FxLive
import com.bainbridgetrails.play.fx.FxPriority
import com.bainbridgetrails.play.fx.FxRenderer
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.play.fx.effects.FxDrawPainter
import com.bainbridgetrails.play.fx.effects.FxPin
import com.bainbridgetrails.play.fx.effects.PIN_FIGURE_CY
import com.bainbridgetrails.play.fx.effects.loopTimeline
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

/**
 * `PlayMarkers` (ux.md §2.2, PD-18, PD-19): the Layer-tier marker art L2-4's effects do not draw — lore, remembrance,
 * found-egg and found-treasure pins, the island-zoom cluster discs, the kraken coast label, the selected pin's
 * sea-glass ring — and the one tap target, [HIT] (`fx-marker-hit`): a 48dp rect centred on each pin's figure (on the
 * disc for clusters), queried by MapTab only after the pure trail query misses (ux.md §3.5.4). Never `onClick` (R7).
 * Static: one AMBIENT instance ([spec]); the inputs are this object's state, set by MapTab from the agenda.
 */
class PlayMarkers : FxRenderer {
    override val kind: String = KIND
    var markers by mutableStateOf<List<PlayMarker>>(emptyList())
    var clusters by mutableStateOf<List<PlayCluster>>(emptyList())
    var selected by mutableStateOf<String?>(null)
    var coastLabel by mutableStateOf<String?>(null)

    @Composable
    @MaplibreComposable
    override fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext) {
        if (band != FxBand.OVER_LABELS) return
        val ms = markers
        val cs = clusters
        val sel = selected
        val json = remember(ms, cs, sel) { PlayMarkerLogic.hitGeoJson(ms, cs, sel) }
        val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
        val p = ctx.palette
        val isCluster = feature["c"].asString(const("0")) eq const("1")
        // Island zoom: ≥ 2 items in a chart square = a 20dp disc with the count (singles stay L2-4's dots).
        CircleLayer(
            id = CLUSTER, source = source, maxZoom = 13f, filter = feature["m"].asString(const("0")) eq const("1"),
            color = const(p.paper), radius = const(10.dp), strokeColor = const(p.ink), strokeWidth = const(1.5.dp),
        )
        // Own pins, one layer per figure in use (lore, remembrance, found egg, found treasure shell).
        val own = remember(ms) {
            ms.filter { it.kind == MarkerKind.LORE || it.kind == MarkerKind.REMEMBRANCE || it.kind == MarkerKind.EGG || (it.kind == MarkerKind.TREASURE && it.found) }
                .map { Triple(it.glyph, it.kind == MarkerKind.REMEMBRANCE, it.minZoom) }.distinct()
        }
        for ((glyph, quiet, minZoom) in own) key(glyph, quiet) {
            val shell = glyph in FxGlyph.treasureTiers
            val fig = rememberVectorPainter(remember(p, glyph, shell) { FxArt.vector(glyph, if (shell) p.ink.copy(alpha = 0.4f) else p.ink, p.lamp) })
            val pin = remember(p, fig, quiet) { if (quiet) null else FxPin(fig, p.ink, p.paper, null, figureDp = if (glyph == FxGlyph.EggStamp) 20f else 28f, liftDp = 30f) }
            val quietPin = remember(p, quiet) { if (quiet) quietPainter(p.ink) else null }
            SymbolLayer(
                id = FxLayerIds.of(KIND, "pin-" + glyph.name.lowercase()), source = source, minZoom = minZoom.toFloat(),
                filter = feature["g"].asString(const("")) eq const(glyph.name),
                iconImage = if (pin != null) image(pin.painter, pin.size) else image(quietPin!!, quietPin.dpSize),
                iconOffset = offset(0.dp, (pin?.offsetYDp ?: QUIET_OFFSET).dp),
                iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
            )
        }
        // Selected pin: a 2dp sea-glass ring around the figure (Layer tier, never an overlay, PD-19).
        val ring = remember(p) { ringPainter(p.seaGlass) }
        SymbolLayer(
            id = RING, source = source, minZoom = 13f, filter = feature["sel"].asString(const("0")) eq const("1"),
            iconImage = image(ring, ring.dpSize), iconOffset = offset(0.dp, (-PIN_FIGURE_CY).dp),
            iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
        )
        // Kraken coast label: water-label convention (italic), ink with a paper halo.
        val label = coastLabel
        if (label != null) {
            SymbolLayer(
                id = COAST, source = source, filter = feature["t"].asString(const("")) eq const(MarkerKind.KRAKEN.name),
                textField = format(span(label, listOf("Noto Sans Italic"), p.ink)), textSize = const(13.sp),
                textColor = const(p.ink), textHaloColor = const(p.paper), textHaloWidth = const(2.dp),
                textAllowOverlap = const(true), textIgnorePlacement = const(true),
            )
        }
        // The tap target: transparent, 48dp, on the figure (pins) or the disc (clusters).
        val hit = remember { FxDrawPainter(48f, 48f) { drawRect(Color.Black.copy(alpha = 0.004f)) } }
        SymbolLayer(
            id = HIT, source = source, iconImage = image(hit, hit.dpSize),
            iconOffset = switch(condition(isCluster, offset(0.dp, 0.dp)), fallback = offset(0.dp, (-PIN_FIGURE_CY).dp)),
            iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
        )
    }

    companion object {
        const val KIND = "marker"
        private val CLUSTER = FxLayerIds.of(KIND, "cluster")
        private val RING = FxLayerIds.of(KIND, "ring")
        private val COAST = FxLayerIds.of(KIND, "coast")
        val HIT = FxLayerIds.of(KIND, "hit")

        /** The only layers MapTab's Play tap query asks for (ux.md §3.5.4): never a casing, line or stamp. */
        val hitLayerIds: List<String> = listOf(HIT)

        private const val QUIET_OFFSET = -27f

        /** The one static instance (anchored mid-island; the features come from [markers]). */
        fun spec(): FxSpec = FxSpec(
            "play-markers", KIND, FxAnchor.Point(GeoPoint(-122.52, 47.63)), 0.0..24.0, FxBand.OVER_LABELS, FxPriority.AMBIENT,
            loopTimeline(60_000), loop = true,
        )

        /** Remembrance: 20dp ink ring with one rule, a stem and a ground dot; no fill, no shadow (ux.md §2.2). */
        private fun quietPainter(ink: Color) = FxDrawPainter(24f, 54f) {
            drawCircle(ink, 9.25f, Offset(12f, 10f), style = Stroke(1.5f))
            drawLine(ink, Offset(7f, 10f), Offset(17f, 10f), strokeWidth = 1.25f)
            drawLine(ink, Offset(12f, 19.5f), Offset(12f, 50f), strokeWidth = 1.25f)
            drawCircle(ink, 2f, Offset(12f, 50f))
        }

        private fun ringPainter(seaGlass: Color) = FxDrawPainter(40f, 40f) {
            drawCircle(seaGlass, 17f, Offset(20f, 20f), style = Stroke(2f))
        }
    }
}
