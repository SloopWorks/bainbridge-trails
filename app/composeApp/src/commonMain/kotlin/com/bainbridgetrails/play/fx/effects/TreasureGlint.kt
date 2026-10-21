package com.bainbridgetrails.play.fx.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
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
import com.bainbridgetrails.play.fx.FxTimeline
import com.bainbridgetrails.play.fx.FxView
import com.bainbridgetrails.play.fx.fxGeoJson
import com.bainbridgetrails.play.geo.GeoPoint
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.globalState
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.plus
import org.maplibre.compose.expressions.dsl.times
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

/**
 * `treasure_glint` (effects §3.7, ux §2.2, plan §2): Layer tier only. Island zoom (< z13) a 6 dp sea-glass dot with an
 * ink ring; z13–14 a 22 dp pin lifted 26 dp; z ≥ 14 a 28 dp pin lifted 30 dp (`OVER_LABELS`, always shown). The glint
 * (halo + sparkle, `OVER_STATUS`) runs on the GPU from one `globalState` key ([KEY_T], a 0..1 sawtooth written ≤ 30 Hz)
 * plus a per-feature seeded `phase`; at most [MAX_GLINTING] glint, the rest stay matte. [KEY_T] is written only while
 * a glinting treasure is in view at z ≥ [GLINT_MIN_ZOOM] (the halo / sparkle layers' `minZoom`); at rest it is
 * [REST_T] (every phase lands before the first flash stop: matte). Reduced = matte (final frame). One source for both
 * bands ([rememberSource]).
 */
object TreasureGlint : FxRenderer {
    override val kind: String = "treasure_glint"
    const val KEY_T = "fx.treasure_glint.t"
    const val PERIOD_MS = 4_000
    const val MAX_GLINTING = 6
    const val GLINT_MIN_ZOOM = 14.0

    /** `t + phase` ∈ [−1, 0) is below the first flash stop: matte, whatever the phase. Also the absent-key default. */
    const val REST_T = -1.0

    val timeline: FxTimeline = loopTimeline(PERIOD_MS)
    private val DOT = FxLayerIds.of(kind, "dot")
    private val HALO = FxLayerIds.of(kind, "halo")
    private val SPARKLE = FxLayerIds.of(kind, "sparkle")
    private val PIN_SMALL = FxLayerIds.of(kind, "pin-small")
    private val PIN = FxLayerIds.of(kind, "pin")
    val layerIds: List<String> = listOf(DOT, HALO, SPARKLE, PIN_SMALL, PIN)

    fun spec(id: String, at: GeoPoint): FxSpec =
        FxSpec(id, kind, FxAnchor.Point(at), 10.5..22.0, FxBand.OVER_LABELS, FxPriority.AMBIENT, timeline, loop = true)

    /** The glint clock, 0..1 over [PERIOD_MS]. */
    fun globalT(nowMs: Long): Double = sawtooth(nowMs, PERIOD_MS.toLong())

    /** One Point per treasure: numeric id = scene seq (feature state), `phase` in [0, 1), `glint` 1 for ≤ 6 animated. */
    fun geoJson(live: List<FxLive>): String {
        var glinting = 0
        return fxGeoJson(live) { l, sb ->
            val g = !l.reduced && !l.demoted && !l.finalFrame && glinting < MAX_GLINTING
            if (g) glinting++
            sb.append("\"phase\":").append(seedPhase(l.instance.spec.seed)).append(",\"glint\":").append(if (g) 1 else 0)
        }
    }

    private fun glints(l: FxLive): Boolean = !l.reduced && !l.demoted && !l.finalFrame && !l.instance.spec.isStatic

    /** Drive [KEY_T]? Only while a glinting treasure is in [view] at z ≥ 14 (unknown view: yes). */
    fun drives(live: List<FxLive>, view: FxView?): Boolean {
        if (view != null && view.zoom < GLINT_MIN_ZOOM) return false
        return live.any { glints(it) && (view == null || view.contains(it.instance.spec.anchor)) }
    }

    override fun writeGlobalState(live: List<FxLive>, nowMs: Long, view: FxView?, out: FxGlobalStateSink) {
        if (drives(live, view)) out.put(KEY_T, globalT(nowMs))
    }

    override fun writeRestState(live: List<FxLive>, nowMs: Long, out: FxGlobalStateSink) {
        out.put(KEY_T, REST_T)
    }

    @Composable
    override fun rememberSource(live: List<FxLive>, ctx: FxLayerContext): GeoJsonSource =
        rememberGeoJsonSource(GeoJsonData.JsonString(remember(live) { geoJson(live) }))

    @Composable
    @MaplibreComposable
    override fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext, source: GeoJsonSource?) {
        if (band != FxBand.OVER_STATUS && band != FxBand.OVER_LABELS) return
        if (source == null) return
        val p = ctx.palette
        if (band == FxBand.OVER_STATUS) {
            val local = globalState(KEY_T).asNumber(const(REST_T.toFloat())) + feature["phase"].asNumber(const(0f))
            val flash = if (ctx.reduced) {
                const(0f)
            } else {
                interpolate(
                    linear(), local,
                    0 to const(0f), 0.08 to const(1f), 0.22 to const(0f), 1.0 to const(0f), 1.08 to const(1f), 1.22 to const(0f), 2.0 to const(0f),
                ) * feature["glint"].asNumber(const(0f))
            }
            CircleLayer(
                id = HALO, source = source, minZoom = 14f,
                translate = offset(0.dp, (-PIN_FIGURE_CY).dp),
                color = const(p.seaGlass), blur = const(0.8f),
                radius = const(15.dp) + const(6.dp) * flash,
                opacity = const(0.16f) + const(0.34f) * flash,
            )
            val sparkle = remember(p) { sparklePainter(p.lamp, p.ink) }
            SymbolLayer(
                id = SPARKLE, source = source, minZoom = 14f,
                iconImage = image(sparkle, DpSize(14.dp, 14.dp)),
                iconOffset = offset((-12).dp, (-PIN_FIGURE_CY - 12f).dp),
                iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
                iconOpacity = flash,
            )
        } else {
            CircleLayer(
                id = DOT, source = source, maxZoom = 13f,
                color = const(p.seaGlass), radius = const(3.dp), strokeColor = const(p.ink), strokeWidth = const(1.5.dp),
            )
            val figure = rememberVectorPainter(remember(p) { FxArt.vector(FxGlyph.SeaGlass, p.ink) })
            val small = remember(p, figure) { FxPin(figure, p.ink, p.paper, p.seaGlass, figureDp = 22f, liftDp = 26f) }
            val full = remember(p, figure) { FxPin(figure, p.ink, p.paper, p.seaGlass, figureDp = 28f, liftDp = 30f) }
            SymbolLayer(
                id = PIN_SMALL, source = source, minZoom = 13f, maxZoom = 14f,
                iconImage = image(small.painter, small.size), iconOffset = offset(0.dp, small.offsetYDp.dp),
                iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
            )
            SymbolLayer(
                id = PIN, source = source, minZoom = 14f,
                iconImage = image(full.painter, full.size), iconOffset = offset(0.dp, full.offsetYDp.dp),
                iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
            )
        }
    }
}

// ---- shared by the L2-4 effects (placeholder art until the engraved FxArt set lands; layouts do not change) ----

/** Figure centre above the ground dot of a full pin (30 dp lift + half the 28 dp figure). */
internal const val PIN_FIGURE_CY = 44f

/**
 * A Layer-tier part that loops through `globalState`: RUN loops forever. A part with no motion of its own uses
 * [com.bainbridgetrails.play.fx.FxSpec.static] instead (it never animates or keeps the clock running).
 */
internal fun loopTimeline(periodMs: Int): FxTimeline = FxTimeline(0, periodMs, 0, 0, runLoopMs = periodMs, reducedAtMs = 0)

/** Deterministic per-instance phase in [0, 1) from the spec's fnv1a seed (never `hashCode`, PD-10). */
internal fun seedPhase(seed: Long): Double {
    // SplitMix64 finaliser: fnv1a seeds of ids that differ in one character differ mostly in low bits.
    var z = seed
    z = (z xor (z ushr 30)) * -4658895280553007687L
    z = (z xor (z ushr 27)) * -7723592293110705685L
    z = z xor (z ushr 31)
    return (z ushr 11).toDouble() / 9_007_199_254_740_992.0
}

internal fun sawtooth(nowMs: Long, periodMs: Long): Double = nowMs.mod(periodMs).toDouble() / periodMs

/** A painter drawn in dp units ([wDp] × [hDp]); MapLibre rasterises it at the screen density. */
internal class FxDrawPainter(private val wDp: Float, private val hDp: Float, private val block: DrawScope.() -> Unit) : Painter() {
    override val intrinsicSize: Size get() = Size(wDp, hDp)
    val dpSize: DpSize get() = DpSize(wDp.dp, hDp.dp)

    override fun DrawScope.onDraw() {
        val s = size.width / wDp
        scale(s, s, pivot = Offset.Zero) { block() }
    }
}

/**
 * The lifted pin (ux §2.2): a paper head with an ink ring and the figure, a 1.25 dp ink stem, a 4 dp ink ground dot and
 * an SE shadow. [offsetYDp] moves the centre-anchored image so the ground dot sits on the point.
 */
internal class FxPin(figure: Painter, ink: Color, paper: Color, fill: Color?, figureDp: Float, liftDp: Float) {
    private val w = 40f
    private val h = figureDp + liftDp + 4f
    private val dotY = figureDp + liftDp
    val offsetYDp: Float = h / 2f - dotY
    val painter = FxDrawPainter(w, h) {
        val cx = w / 2f
        val r = figureDp / 2f
        drawOval(ink.copy(alpha = 0.18f), topLeft = Offset(cx - 2.5f, dotY), size = Size(8f, 3f))
        drawLine(ink, Offset(cx, figureDp - 1f), Offset(cx, dotY), strokeWidth = 1.25f)
        drawCircle(ink, 2f, Offset(cx, dotY))
        drawCircle(paper, r - 0.5f, Offset(cx, r))
        if (fill != null) drawCircle(fill.copy(alpha = 0.85f), r * 0.42f, Offset(cx, r))
        drawCircle(ink, r - 0.75f, Offset(cx, r), style = Stroke(1.5f))
        val g = figureDp * 0.72f
        translate(cx - g / 2f, r - g / 2f) { with(figure) { draw(Size(g, g)) } }
    }
    val size: DpSize get() = painter.dpSize
}

/** The glint: a 4-point star, lamp core with a 1 dp ink edge. */
internal fun sparklePainter(lamp: Color, ink: Color) = FxDrawPainter(14f, 14f) {
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(7f, 0.5f); lineTo(8.4f, 5.6f); lineTo(13.5f, 7f); lineTo(8.4f, 8.4f)
        lineTo(7f, 13.5f); lineTo(5.6f, 8.4f); lineTo(0.5f, 7f); lineTo(5.6f, 5.6f); close()
    }
    drawPath(path, lamp)
    drawPath(path, ink, style = Stroke(1f))
}

/** A figure on an optional paper disc (stamps, eyes, glyphs). */
internal fun stampPainter(figure: Painter, sizeDp: Float, paper: Color?, ink: Color?) = FxDrawPainter(sizeDp, sizeDp) {
    val r = sizeDp / 2f
    if (paper != null) drawCircle(paper, r - 0.5f, Offset(r, r))
    if (ink != null) drawCircle(ink, r - 0.75f, Offset(r, r), style = Stroke(1.25f))
    val g = sizeDp * 0.8f
    translate(r - g / 2f, r - g / 2f) { with(figure) { draw(Size(g, g)) } }
}
