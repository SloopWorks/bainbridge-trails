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
import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.play.fx.fxGeoJson
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
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import androidx.compose.ui.graphics.Color

/**
 * `kraken_lurk` (effects §3.2): two ink rings in the water at the attack's offshore anchor (`UNDER_STATUS`, radius
 * 6→34 dp, α .6→0, 3.2 s loop, half a period apart) and the eye (`OVER_STATUS`) that surfaces every 12–20 s (seeded),
 * holds 1.6 s, blinks once (open/closed image pair) and sinks. Everything is driven by `globalState` (≤ 30 Hz) so the
 * FxHost clock policy pauses it. Reduced: one static ring (r 20 dp, α .45), eye open at α .7. From z12.5.
 */
object KrakenLurk : FxRenderer {
    override val kind: String = "kraken_lurk"
    const val KEY_A = "fx.kraken_lurk.a"
    const val KEY_B = "fx.kraken_lurk.b"
    const val KEY_EYE = "fx.kraken_lurk.eye"
    const val KEY_BLINK = "fx.kraken_lurk.blink"
    const val RIPPLE_MS = 3_200L
    private val RING_A = FxLayerIds.of(kind, "ring-a")
    private val RING_B = FxLayerIds.of(kind, "ring-b")
    private val EYE = FxLayerIds.of(kind, "eye")
    private val EYE_SHUT = FxLayerIds.of(kind, "eye-shut")
    val layerIds: List<String> = listOf(RING_A, RING_B, EYE, EYE_SHUT)
    private val timeline = loopTimeline(RIPPLE_MS.toInt())

    fun spec(id: String, water: GeoPoint): FxSpec =
        FxSpec(id, kind, FxAnchor.Point(water), 12.5..22.0, FxBand.OVER_STATUS, FxPriority.AMBIENT, timeline, loop = true)

    /** Eye envelope 0..1 at scene ms: rise 400 ms, hold 1.6 s, sink 400 ms, once per seeded 12–20 s period. */
    fun eyeAt(nowMs: Long, seed: Long): Double {
        val period = 12_000L + (seed ushr 20).mod(8_000L)
        val t = (nowMs + (seed ushr 7).mod(period)).mod(period)
        return when {
            t < 400 -> t / 400.0
            t < 2_000 -> 1.0
            t < 2_400 -> (2_400 - t) / 400.0
            else -> 0.0
        }
    }

    /** 1 while the eye is shut (a 200 ms blink mid-hold). */
    fun blinkAt(nowMs: Long, seed: Long): Double {
        val period = 12_000L + (seed ushr 20).mod(8_000L)
        val t = (nowMs + (seed ushr 7).mod(period)).mod(period)
        return if (t in 1_100L until 1_300L) 1.0 else 0.0
    }

    override fun writeGlobalState(live: List<FxLive>, nowMs: Long, out: FxGlobalStateSink) {
        val l = live.firstOrNull { !it.reduced } ?: return
        out.put(KEY_A, sawtooth(nowMs, RIPPLE_MS))
        out.put(KEY_B, sawtooth(nowMs + RIPPLE_MS / 2, RIPPLE_MS))
        out.put(KEY_EYE, eyeAt(nowMs, l.instance.spec.seed))
        out.put(KEY_BLINK, blinkAt(nowMs, l.instance.spec.seed))
    }

    @Composable
    @MaplibreComposable
    override fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext) {
        if (band != FxBand.UNDER_STATUS && band != FxBand.OVER_STATUS) return
        val json = remember(live) { fxGeoJson(live) }
        val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
        val p = ctx.palette
        if (band == FxBand.UNDER_STATUS) {
            for ((id, key) in listOf(RING_A to KEY_A, RING_B to KEY_B)) {
                if (ctx.reduced && id == RING_B) continue
                val t = globalState(key).asNumber(const(0.5f))
                CircleLayer(
                    id = id, source = source, minZoom = 12.5f,
                    color = const(Color.Transparent), strokeColor = const(p.ink), strokeWidth = const(1.25.dp),
                    radius = if (ctx.reduced) const(20.dp) else const(6.dp) + const(28.dp) * t,
                    strokeOpacity = if (ctx.reduced) const(0.45f) else const(0.6f) - const(0.6f) * t,
                )
            }
            return
        }
        val eyeFig = rememberVectorPainter(remember(p) { FxArt.vector(FxGlyph.KrakenEye, p.ink) })
        val open = remember(p, eyeFig) { stampPainter(eyeFig, 26f, p.paper, null) }
        val shut = remember(p) {
            FxDrawPainter(26f, 26f) {
                drawCircle(p.paper, 12.5f, Offset(13f, 13f))
                drawLine(p.ink, Offset(4f, 13.5f), Offset(22f, 13.5f), strokeWidth = 2f)
                drawArc(p.ink, 20f, 140f, false, topLeft = Offset(5f, 6f), size = androidx.compose.ui.geometry.Size(16f, 14f), style = Stroke(1.25f))
            }
        }
        val eye = globalState(KEY_EYE).asNumber(const(0f))
        val blink = globalState(KEY_BLINK).asNumber(const(0f))
        SymbolLayer(
            id = EYE, source = source, minZoom = 12.5f,
            iconImage = image(open, DpSize(26.dp, 26.dp)),
            iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
            iconOpacity = if (ctx.reduced) const(0.7f) else eye - eye * blink,
        )
        SymbolLayer(
            id = EYE_SHUT, source = source, minZoom = 12.5f,
            iconImage = image(shut, DpSize(26.dp, 26.dp)),
            iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
            iconOpacity = if (ctx.reduced) const(0f) else eye * blink,
        )
    }
}
