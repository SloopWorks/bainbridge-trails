package com.bainbridgetrails.play.fx.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxAnchor
import com.bainbridgetrails.play.fx.FxArt
import com.bainbridgetrails.play.fx.FxBand
import com.bainbridgetrails.play.fx.FxGlobalStateSink
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxLayerContext
import com.bainbridgetrails.play.fx.FxLayerIds
import com.bainbridgetrails.play.fx.FxLive
import com.bainbridgetrails.play.fx.FxPriority
import com.bainbridgetrails.play.fx.FxRenderer
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.FxView
import com.bainbridgetrails.play.fx.fxGeoJson
import com.bainbridgetrails.play.geo.GeoPoint
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.globalState
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.minus
import org.maplibre.compose.expressions.dsl.plus
import org.maplibre.compose.expressions.dsl.times
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate

/**
 * `frog_rock` Layer part (effects §3.4): the painted frog stamped on its rock at the landmark (`OVER_STATUS`, printed
 * on the chart: no stem, no shadow), growing with the map (`icon-size` exponential 1.5: 0.6× at z12.5 → 1× z14–16 →
 * 1.4× z18), with an idle blink every 6–10 s (seeded) as an open/closed image pair driven by `globalState`. The hop and
 * croak are L2-5 (Overlay). Reduced: open eyes, no blink.
 */
object FrogRockStamp : FxRenderer {
    override val kind: String = "frog_rock"
    const val KEY_BLINK = "fx.frog_rock.blink"
    /** The product landmark (product §4.0.2). */
    val LANDMARK = GeoPoint(lon = -122.52347, lat = 47.69612)
    private val OPEN = FxLayerIds.of(kind, "stamp")
    private val SHUT = FxLayerIds.of(kind, "stamp-blink")
    val layerIds: List<String> = listOf(OPEN, SHUT)
    private val timeline = loopTimeline(8_000)

    fun spec(id: String = "lore-frog", at: GeoPoint = LANDMARK): FxSpec =
        FxSpec(id, kind, FxAnchor.Point(at), 10.5..22.0, FxBand.OVER_STATUS, FxPriority.AMBIENT, timeline, loop = true)

    /** 1 while the eyes are shut: a 320 ms blink once per seeded 6–10 s period. */
    fun blinkAt(nowMs: Long, seed: Long): Double {
        val period = 6_000L + (seed ushr 16).mod(4_000L)
        return if ((nowMs + (seed ushr 5).mod(period)).mod(period) < 320L) 1.0 else 0.0
    }

    override fun writeGlobalState(live: List<FxLive>, nowMs: Long, view: FxView?, out: FxGlobalStateSink) {
        val l = live.firstOrNull { !it.reduced } ?: return
        out.put(KEY_BLINK, blinkAt(nowMs, l.instance.spec.seed))
    }

    /** Rest: eyes open. */
    override fun writeRestState(live: List<FxLive>, nowMs: Long, out: FxGlobalStateSink) {
        out.put(KEY_BLINK, 0.0)
    }

    @Composable
    @MaplibreComposable
    override fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext, source: GeoJsonSource?) {
        if (band != FxBand.OVER_STATUS) return
        val json = remember(live) { fxGeoJson(live) }
        val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
        val p = ctx.palette
        val frog = rememberVectorPainter(remember(p) { FxArt.vector(FxGlyph.Frog, p.ink) })
        val open = remember(p, frog) { frogStamp(p.rock, p.figureFill, p.ink, p.paper, frog, shut = false) }
        val shut = remember(p, frog) { frogStamp(p.rock, p.figureFill, p.ink, p.paper, frog, shut = true) }
        val size = interpolate(exponential(1.5f), zoom(), 10.5 to const(0.35f), 12.5 to const(0.6f), 14 to const(1f), 16 to const(1f), 18 to const(1.4f))
        val blink = globalState(KEY_BLINK).asNumber(const(0f))
        SymbolLayer(
            id = OPEN, source = source,
            iconImage = image(open, DpSize(36.dp, 32.dp)), iconSize = size,
            iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
            iconOpacity = if (ctx.reduced) const(1f) else const(1f) - blink,
        )
        SymbolLayer(
            id = SHUT, source = source,
            iconImage = image(shut, DpSize(36.dp, 32.dp)), iconSize = size,
            iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
            iconOpacity = if (ctx.reduced) const(0f) else blink,
        )
    }

    private fun frogStamp(
        rock: androidx.compose.ui.graphics.Color,
        fill: androidx.compose.ui.graphics.Color,
        ink: androidx.compose.ui.graphics.Color,
        paper: androidx.compose.ui.graphics.Color,
        frog: androidx.compose.ui.graphics.painter.Painter,
        shut: Boolean,
    ) = FxDrawPainter(36f, 32f) {
        // Rock: tertiaryContainer with SE hatching.
        drawOval(rock, topLeft = Offset(2f, 18f), size = Size(32f, 12f))
        drawOval(ink, topLeft = Offset(2f, 18f), size = Size(32f, 12f), style = Stroke(1.5f))
        for (i in 0..3) drawLine(ink, Offset(20f + i * 3f, 28.5f), Offset(24f + i * 3f, 24.5f), strokeWidth = 1f)
        // Frog: one role fill under the ink figure.
        drawOval(fill, topLeft = Offset(9f, 9f), size = Size(18f, 12f))
        translate(6f, -1f) { with(frog) { draw(Size(24f, 24f)) } }
        if (shut) {
            // Lids over both eyes (paper, then an ink lid line).
            for (cx in listOf(14.3f, 21.7f)) {
                drawCircle(paper, 2.2f, Offset(cx, 7.8f))
                drawLine(ink, Offset(cx - 2f, 7.8f), Offset(cx + 2f, 7.8f), strokeWidth = 1.25f)
            }
        }
    }
}
