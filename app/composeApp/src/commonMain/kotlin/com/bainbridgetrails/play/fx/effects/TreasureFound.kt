package com.bainbridgetrails.play.fx.effects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxAnchor
import com.bainbridgetrails.play.fx.FxArt
import com.bainbridgetrails.play.fx.FxBand
import com.bainbridgetrails.play.fx.FxEasing
import com.bainbridgetrails.play.fx.FxGlobalStateSink
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxLayerContext
import com.bainbridgetrails.play.fx.FxLayerIds
import com.bainbridgetrails.play.fx.FxLive
import com.bainbridgetrails.play.fx.FxOverlayContext
import com.bainbridgetrails.play.fx.FxPriority
import com.bainbridgetrails.play.fx.FxRenderer
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.FxTimeline
import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.play.fx.fxGeoJson
import com.bainbridgetrails.play.fx.fxPlacedAt
import com.bainbridgetrails.play.rng.SplitMix64
import com.bainbridgetrails.ui.theme.BtTheme
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.globalState
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * `treasure_found` (effects §3.7, HERO): sensed, provisional until Finish, so no flight and no pop. Overlay (900 ms,
 * from z14): the treasure pin as [TreasureGlint] draws it winds up (.9), pops (1.3, +10 dp, −8°), bursts 10 sea-glass
 * shards + 4 lamp stars (`ballistic`) and one water-line ring, then shrinks away in place; `confirm` at 100. Layer:
 * the found shell (`OVER_STATUS`, a spent stamp: the pebble in ink at 40 % α inside an ink ring, no fill or backing)
 * crossfades in over 600–900 through [KEY_SPENT] and stays as the [spentSpec] instance. The call site drops the
 * treasure's glint spec when it spawns these. Reduced: the shell is simply there (final frame).
 */
object TreasureFound : FxRenderer {
    override val kind: String = "treasure_found"
    const val KEY_SPENT = "fx.treasure_found.spent"
    private val SHELL = FxLayerIds.of(kind, "shell")
    private val SHELL_DOT = FxLayerIds.of(kind, "shell-dot")
    val layerIds: List<String> = listOf(SHELL_DOT, SHELL)

    /** 900 ms: wind-up 0–100 (SPAWN), pop + burst 100–600 (RUN), shell crossfade 600–900 (SETTLE). */
    val timeline = FxTimeline(spawnMs = 100, runMs = 500, settleMs = 300, despawnMs = 0, runLoopMs = null, reducedAtMs = 900)

    /** The burst (HERO, OVERLAY) at the treasure. */
    fun foundSpec(id: String, at: GeoPoint): FxSpec =
        FxSpec("$id#found", kind, FxAnchor.Point(at), 14.0..22.0, FxBand.OVERLAY, FxPriority.HERO, timeline, loop = false)

    /** The found shell that stays on the chart (AMBIENT, static). */
    fun spentSpec(id: String, at: GeoPoint): FxSpec =
        FxSpec("$id#spent", kind, FxAnchor.Point(at), 10.5..22.0, FxBand.OVER_STATUS, FxPriority.AMBIENT, loopTimeline(60_000), loop = true)

    /** Shell opacity at burst time [t]: 0 until 600, then → 1 by 900 (`standard`). */
    fun spentAt(t: Int): Float = timeline.phase(t, 600, 900, FxEasing.Standard)

    override fun writeGlobalState(live: List<FxLive>, nowMs: Long, out: FxGlobalStateSink) {
        val burst = live.lastOrNull { it.instance.spec.band == FxBand.OVERLAY && !it.reduced }
        if (burst == null) {
            out.put(KEY_SPENT, 1.0)
            return
        }
        val i = burst.instance
        out.put(KEY_SPENT, spentAt(i.spec.timeline.foldedMs(i.localMs(nowMs), i.dismissedAtMs)).toDouble())
    }

    @Composable
    @MaplibreComposable
    override fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext) {
        if (band != FxBand.OVER_STATUS) return
        val json = remember(live) { fxGeoJson(live.filter { it.instance.spec.band == FxBand.OVER_STATUS }) }
        val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
        val p = ctx.palette
        val opacity = if (ctx.reduced) const(1f) else globalState(KEY_SPENT).asNumber(const(1f))
        CircleLayer(
            id = SHELL_DOT, source = source, maxZoom = 13f,
            color = const(p.ink.copy(alpha = 0f)), radius = const(3.dp), strokeColor = const(p.ink.copy(alpha = 0.4f)), strokeWidth = const(1.5.dp),
            opacity = opacity, strokeOpacity = opacity,
        )
        val figure = rememberVectorPainter(remember(p) { FxArt.vector(FxGlyph.SeaGlass, p.ink.copy(alpha = 0.4f)) })
        val shell = remember(p, figure) { stampPainter(figure, SHELL_DP, paper = null, ink = p.ink.copy(alpha = 0.4f)) }
        SymbolLayer(
            id = SHELL, source = source, minZoom = 13f,
            iconImage = image(shell, DpSize(SHELL_DP.dp, SHELL_DP.dp)),
            iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
            iconOpacity = opacity,
        )
    }

    @Composable
    override fun MapOverlayScope.Overlay(item: FxLive, ctx: FxOverlayContext) {
        val spec = item.instance.spec
        val at = (spec.anchor as? FxAnchor.Point)?.at ?: return
        val p = ctx.palette
        val ring = BtTheme.colors.waterLine
        val figure = rememberVectorPainter(remember(p) { FxArt.vector(FxGlyph.SeaGlass, p.ink) })
        val pin = remember(p, figure) { FxPin(figure, p.ink, p.paper, p.seaGlass, figureDp = 28f, liftDp = 30f) }
        val burst = remember(spec.seed) { Burst(spec.seed) }
        FxHapticAt(item, ctx, atMs = 100, HapticFeedbackType.Confirm)
        Box(
            Modifier.fxPlacedAt(this, at, ctx).size(BOX_DP.dp).drawWithCache {
                val quad = Path()
                onDrawBehind {
                    if (item.reduced || ctx.map.cameraPosition.zoom < spec.zoom.start) return@onDrawBehind
                    val t = item.overlayT(ctx)
                    val tl = timeline
                    val dp = density
                    val ground = center
                    val head = Offset(ground.x, ground.y - PIN_FIGURE_CY * dp)
                    // Pebble: anticipation → pop (scale, rise, tilt) → shrink in place.
                    val wind = tl.phase(t, 0, 100, FxEasing.EmphasizedAccelerate)
                    val pop = tl.phase(t, 100, 300, FxEasing.EmphasizedDecelerate)
                    val shrink = tl.phase(t, 300, 600, FxEasing.EmphasizedAccelerate)
                    val s = (1f - 0.1f * wind + 0.4f * pop) * (1f - shrink)
                    if (s > 0.01f) {
                        translate(0f, -10f * dp * pop) {
                            rotate(-8f * pop, head) {
                                scale(s, s, head) {
                                    translate(ground.x - 20f * dp, ground.y - 58f * dp) {
                                        scale(dp, dp, Offset.Zero) { with(pin.painter) { draw(Size(40f, 62f)) } }
                                    }
                                }
                            }
                        }
                    }
                    // Ring: one water line out from the pebble.
                    val rp = tl.phase(t, 250, 700, FxEasing.EmphasizedDecelerate)
                    if (rp in 0.001f..0.999f) drawCircle(ring.copy(alpha = 0.8f * (1f - rp)), lerp(8f, 44f, rp) * dp, head, style = Stroke(1.5f * dp))
                    // Shards + stars: ballistic, upward bias.
                    val bp = tl.phase(t, 200, 650)
                    if (bp in 0.001f..0.999f) burst.draw(this, head, bp, p.seaGlass, p.lamp, p.ink, quad)
                }
            },
        )
    }

    private const val SHELL_DP = 20f
    private const val BOX_DP = 160f

    /** 10 shards (3 tints of sea glass at α .9/.7/.5, 3–5 dp quads) + 4 lamp stars, seeded; drawn without allocation. */
    private class Burst(seed: Long) {
        private val n = SHARDS + STARS
        private val vx = FloatArray(n)
        private val vy = FloatArray(n)
        private val side = FloatArray(n)
        private val spin = FloatArray(n)

        init {
            val rng = SplitMix64(seed.toULong())
            for (i in 0 until n) {
                val a = -PI / 2 + (rng.nextDouble() - 0.5) * PI * 1.4 // upward fan
                val v = 140.0 + 120.0 * rng.nextDouble()
                vx[i] = (cos(a) * v).toFloat()
                vy[i] = (sin(a) * v).toFloat()
                side[i] = (3.0 + 2.0 * rng.nextDouble()).toFloat()
                spin[i] = ((rng.nextDouble() - 0.5) * 720.0).toFloat()
            }
        }

        fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, o: Offset, p: Float, glass: androidx.compose.ui.graphics.Color, lamp: androidx.compose.ui.graphics.Color, ink: androidx.compose.ui.graphics.Color, quad: Path) = with(scope) {
            val dp = density
            val s = p * 0.45f // long1 of flight
            val fade = 1f - p
            for (i in 0 until n) {
                val x = o.x + vx[i] * s * dp
                val y = o.y + (vy[i] * s + 0.5f * 1400f * s * s) * dp
                val h = side[i] / 2f * dp
                if (i < SHARDS) {
                    val tint = TINTS[i % 3]
                    rotate(spin[i] * p, Offset(x, y)) {
                        quad.rewind()
                        quad.moveTo(x - h, y - h * 0.4f); quad.lineTo(x + h * 0.3f, y - h)
                        quad.lineTo(x + h, y + h * 0.5f); quad.lineTo(x - h * 0.2f, y + h * 0.8f); quad.close()
                        drawPath(quad, glass.copy(alpha = tint * fade))
                    }
                } else {
                    val r = h * 1.2f
                    quad.rewind()
                    quad.moveTo(x, y - r); quad.lineTo(x + r * 0.28f, y - r * 0.28f); quad.lineTo(x + r, y)
                    quad.lineTo(x + r * 0.28f, y + r * 0.28f); quad.lineTo(x, y + r); quad.lineTo(x - r * 0.28f, y + r * 0.28f)
                    quad.lineTo(x - r, y); quad.lineTo(x - r * 0.28f, y - r * 0.28f); quad.close()
                    drawPath(quad, lamp.copy(alpha = fade))
                    drawPath(quad, ink.copy(alpha = fade), style = Stroke(0.75f * dp))
                }
            }
        }

        companion object {
            const val SHARDS = 10
            const val STARS = 4
            val TINTS = floatArrayOf(0.9f, 0.7f, 0.5f)
        }
    }
}
