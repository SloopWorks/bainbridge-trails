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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bainbridgetrails.play.fx.FxAnchor
import com.bainbridgetrails.play.fx.FxArt
import com.bainbridgetrails.play.fx.FxBand
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxLayerContext
import com.bainbridgetrails.play.fx.FxLayerIds
import com.bainbridgetrails.play.fx.FxLive
import com.bainbridgetrails.play.fx.FxRenderer
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.effects.FxDrawPainter
import com.bainbridgetrails.play.fx.effects.FxPin
import com.bainbridgetrails.play.fx.effects.PIN_FIGURE_CY
import com.bainbridgetrails.play.geo.GeoPoint
import com.bainbridgetrails.ui.theme.BtTheme
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.Case
import org.maplibre.compose.expressions.dsl.and
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.minus
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.plus
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.times
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.OffsetValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

/**
 * `PlayMarkers` (ux.md §2.2, PD-18, PD-19): the Layer-tier marker art L2-4's effects do not draw — lore, remembrance,
 * found-egg and found-treasure pins, the island-zoom cluster discs with their count, the kraken coast label, the
 * selected pin's sea-glass ring — and the one tap target, [HIT] (`fx-marker-hit`): a 48dp rect centred on each pin's
 * figure (on the disc / dot for clusters, on the label for the kraken), queried by MapTab only after the pure trail
 * query misses (ux.md §3.5.4). Never `onClick` (R7).
 *
 * Declutter (ux.md §2.2): pins never overlap each other (`icon-allow-overlap` false) and place by `symbol-sort-key`
 * ([PlayMarkerLogic.sortKey]: remembrance > kraken > riddle > treasure > lore > egg, found last). The selected pin is
 * its own layer, always placed, at 1.15× (the Layer-tier selected variant, PD-19); every other pin of this set dims to
 * 50 % while one is selected. Static ([spec], `FxSpec.static`): never keeps the clock running. The inputs are this
 * object's state, set by MapTab from the agenda; the one GeoJSON source is hoisted per map ([rememberSource]).
 */
class PlayMarkers : FxRenderer {
    override val kind: String = KIND
    var markers by mutableStateOf<List<PlayMarker>>(emptyList())
    var clusters by mutableStateOf<List<PlayCluster>>(emptyList())
    var selected by mutableStateOf<String?>(null)
    var coastLabel by mutableStateOf<String?>(null)

    @Composable
    override fun rememberSource(live: List<FxLive>, ctx: FxLayerContext): GeoJsonSource {
        val ms = markers
        val cs = clusters
        val sel = selected
        val json = remember(ms, cs, sel) { PlayMarkerLogic.hitGeoJson(ms, cs, sel) }
        return rememberGeoJsonSource(GeoJsonData.JsonString(json))
    }

    @Composable
    @MaplibreComposable
    override fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext, source: GeoJsonSource?) {
        if (band != FxBand.OVER_LABELS || source == null) return
        val p = ctx.palette
        val chrome = BtTheme.colors.mapChrome
        val onChrome = BtTheme.colors.onMapChrome
        val isCluster = feature["c"].asString(const("0")) eq const("1")
        val isDisc = feature["m"].asString(const("0")) eq const("1")
        // Island zoom: ≥ 2 items in a chart square = a 20dp mapChrome disc with the count (singles stay L2-4's dots).
        CircleLayer(
            id = CLUSTER, source = source, maxZoom = 13f, filter = isDisc,
            color = const(chrome), radius = const(10.dp), strokeColor = const(p.ink), strokeWidth = const(1.5.dp),
        )
        SymbolLayer(
            id = CLUSTER_COUNT, source = source, maxZoom = 13f, filter = isDisc,
            // The count with the basemap's font stack: one formatted literal per count (a span's font list is literal-only).
            textField = switch(feature["n"].asString(const("")), (2..MAX_COUNT).map { n -> case("$n", format(span("$n", listOf(COUNT_FONT), onChrome))) }, fallback = format(span("${MAX_COUNT}+", listOf(COUNT_FONT), onChrome))),
            textSize = const(11.sp), textColor = const(onChrome),
            textAllowOverlap = const(true), textIgnorePlacement = const(true),
        )
        // Own pins (lore, remembrance, found egg, found treasure shell): one image per figure in use, chosen per feature.
        val ms = markers
        val own = remember(ms) { ms.filter { ownsPin(it) }.map { it.glyph to it.minZoom }.distinct() }
        if (own.isNotEmpty()) {
            val images = ArrayList<Case<StringValue, ImageValue>>()
            var firstImage: Expression<ImageValue>? = null
            val offsets = ArrayList<Case<StringValue, OffsetValue<androidx.compose.ui.unit.Dp>>>()
            for ((glyph, _) in own) key(glyph) {
                val quiet = glyph == FxGlyph.QuietRing
                val shell = glyph in FxGlyph.treasureTiers
                val fig = rememberVectorPainter(remember(p, glyph, shell) { FxArt.vector(glyph, if (shell) p.ink.copy(alpha = 0.4f) else p.ink, p.lamp) })
                val pin = remember(p, fig, quiet) { if (quiet) null else FxPin(fig, p.ink, p.paper, null, figureDp = if (glyph == FxGlyph.EggStamp) 20f else 28f, liftDp = 30f) }
                val quietPin = remember(p, quiet) { if (quiet) quietPainter(p.ink) else null }
                val img = if (pin != null) image(pin.painter, pin.size) else image(quietPin!!, quietPin.dpSize)
                if (firstImage == null) firstImage = img
                images += case(glyph.name, img)
                offsets += case(glyph.name, offset(0.dp, (pin?.offsetYDp ?: QUIET_OFFSET).dp))
            }
            val glyphKey = feature["g"].asString(const(""))
            val image = switch(glyphKey, images, fallback = firstImage!!)
            val iconOffset = switch(glyphKey, offsets, fallback = offset(0.dp, QUIET_OFFSET.dp))
            val notSelected = feature["sel"].asString(const("0")) eq const("0")
            val dim = const(1f) - const(0.5f) * feature["dim"].asNumber(const(0f))
            // One layer per min-zoom tier (z13 found shells, z14 lore + remembrance, z15 eggs); composed so the
            // higher-priority tier is on top and places first. Within a tier the sort key decides.
            for (z in own.map { it.second }.distinct().sortedBy { zoomTierOrder(it) }) key(z) {
                SymbolLayer(
                    id = FxLayerIds.of(KIND, "pin-z${z.toInt()}"), source = source, minZoom = z.toFloat(),
                    filter = notSelected and (feature["z"].asNumber(const(0f)) eq const(z.toFloat())),
                    sortKey = feature["p"].asNumber(const(0f)),
                    iconImage = image, iconOffset = iconOffset, iconOpacity = dim,
                    iconAllowOverlap = const(false), iconIgnorePlacement = const(false),
                )
            }
            // The selected variant (PD-19): always placed, 1.15×, full opacity.
            SymbolLayer(
                id = SELECTED, source = source, minZoom = 13f,
                filter = (feature["sel"].asString(const("0")) eq const("1")) and (feature["own"].asNumber(const(0f)) eq const(1f)),
                iconImage = image, iconOffset = iconOffset, iconSize = const(SELECTED_SCALE),
                iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
            )
        }
        // Selected pin: a 2dp sea-glass ring around the figure (Layer tier, never an overlay, PD-19); scaled with an
        // own pin's 1.15× (icon-offset scales with icon-size).
        val ring = remember(p) { ringPainter(p.seaGlass) }
        SymbolLayer(
            id = RING, source = source, minZoom = 13f, filter = feature["sel"].asString(const("0")) eq const("1"),
            iconImage = image(ring, ring.dpSize), iconOffset = offset(0.dp, (-PIN_FIGURE_CY).dp),
            iconSize = const(1f) + const(SELECTED_SCALE - 1f) * feature["own"].asNumber(const(0f)),
            iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
        )
        // Kraken coast label: water-label convention (italic), ink with a paper halo, on the attack's water point.
        val label = coastLabel
        if (label != null) {
            SymbolLayer(
                id = COAST, source = source, filter = feature["t"].asString(const("")) eq const(MarkerKind.KRAKEN.name),
                textField = format(span(label, listOf("Noto Sans Italic"), p.ink)), textSize = const(13.sp),
                textColor = const(p.ink), textHaloColor = const(p.paper), textHaloWidth = const(2.dp),
                textAllowOverlap = const(true), textIgnorePlacement = const(true),
            )
        }
        // The tap target: transparent, 48dp, on the figure (pins) or at the point (cluster discs / dots, the kraken label).
        val hit = remember { FxDrawPainter(48f, 48f) { drawRect(Color.Black.copy(alpha = 0.004f)) } }
        val atPoint = feature["t"].asString(const("")) eq const(MarkerKind.KRAKEN.name)
        SymbolLayer(
            id = HIT, source = source, iconImage = image(hit, hit.dpSize),
            iconOffset = switch(condition(isCluster, offset(0.dp, 0.dp)), condition(atPoint, offset(0.dp, 0.dp)), fallback = offset(0.dp, PlayMarkerLogic.PIN_LIFT_DP.dp)),
            iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
        )
    }

    companion object {
        const val KIND = "marker"
        private val CLUSTER = FxLayerIds.of(KIND, "cluster")
        private val CLUSTER_COUNT = FxLayerIds.of(KIND, "cluster-count")
        private val SELECTED = FxLayerIds.of(KIND, "pin-selected")
        private val RING = FxLayerIds.of(KIND, "ring")
        private val COAST = FxLayerIds.of(KIND, "coast")
        val HIT = FxLayerIds.of(KIND, "hit")

        /** The only layers MapTab's Play tap query asks for (ux.md §3.5.4): never a casing, line or stamp. */
        val hitLayerIds: List<String> = listOf(HIT)

        /** The selected variant's icon size (ux.md §2.2, PD-19). */
        const val SELECTED_SCALE = 1.15f

        /** The basemap's glyph stack for the cluster count (protomaps fonts; the coast label uses its italic). */
        private const val COUNT_FONT = "Noto Sans Medium"

        private const val QUIET_OFFSET = -27f

        /** Counts drawn as numbers; more than this in one chart square shows "40+" (≤ 40 markers render, ux.md §2.2). */
        private const val MAX_COUNT = PlayMarkerLogic.MAX_MARKERS

        /** The pins this renderer draws itself (unfound treasures and riddles are TreasureGlint's / RiddleMarker's). */
        fun ownsPin(m: PlayMarker): Boolean =
            m.kind == MarkerKind.LORE || m.kind == MarkerKind.REMEMBRANCE || m.kind == MarkerKind.EGG || (m.kind == MarkerKind.TREASURE && m.found)

        /** Composition order of the min-zoom tiers: the last composed is on top and places first (lore / remembrance). */
        private fun zoomTierOrder(z: Double): Int = when (z) {
            15.0 -> 0
            13.0 -> 1
            else -> 2
        }

        /** The one static instance (anchored mid-island; the features come from [markers]): never animates. */
        fun spec(): FxSpec = FxSpec.static("play-markers", KIND, FxAnchor.Point(GeoPoint(lat = 47.63, lon = -122.52)), 0.0..24.0, FxBand.OVER_LABELS)

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
