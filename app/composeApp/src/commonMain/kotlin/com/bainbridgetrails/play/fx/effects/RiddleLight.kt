package com.bainbridgetrails.play.fx.effects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxAnchor
import com.bainbridgetrails.play.fx.FxArt
import com.bainbridgetrails.play.fx.FxBand
import com.bainbridgetrails.play.fx.FxEasing
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxLive
import com.bainbridgetrails.play.fx.FxOverlayContext
import com.bainbridgetrails.play.fx.FxPriority
import com.bainbridgetrails.play.fx.FxRenderer
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.FxTimeline
import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.play.fx.fxPlacedAt
import com.bainbridgetrails.ui.theme.PlayDark
import org.maplibre.compose.overlay.MapOverlayScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * `riddle_light` (effects §3.9, HERO, 1100 ms, from z14): the riddle was solved in the sheet, so the call site swaps
 * the [RiddleMarker] spec to lit and spawns this. The overlay draws the whole lantern pin on top of the Layer pin
 * (same 28 dp pin, same anchor) so the swap is hidden: a wick spark (0–150), the flame grows as the "?" fades
 * (150–500, the unlit → lit figure), a `playLamp` glow (300–900, α .6, dark .8 and ×1.6), 8 ink rays flick out
 * (500–1100, stagger 30), and 900–1100 the overlay fades onto the identical lit Layer pin (hand-off).
 * `confirm` at 150. Reduced: no overlay; the lit Layer pin and its static glow are the final frame.
 */
object RiddleLight : FxRenderer {
    override val kind: String = "riddle_light"

    /** 1100 ms: spark (SPAWN 0–150), flame + glow + rays (RUN 150–900), hand-off (SETTLE 900–1100). */
    val timeline = FxTimeline(spawnMs = 150, runMs = 750, settleMs = 200, despawnMs = 0, runLoopMs = null, reducedAtMs = 1_100)

    fun spec(id: String, at: GeoPoint): FxSpec =
        FxSpec("$id#light", kind, FxAnchor.Point(at), 14.0..22.0, FxBand.OVERLAY, FxPriority.HERO, timeline, loop = false)

    @Composable
    override fun MapOverlayScope.Overlay(item: FxLive, ctx: FxOverlayContext) {
        val spec = item.instance.spec
        val at = (spec.anchor as? FxAnchor.Point)?.at ?: return
        val p = ctx.palette
        val dark = p.ink == PlayDark.ink
        val unlitFig = rememberVectorPainter(remember(p) { FxArt.vector(FxGlyph.LanternUnlit, p.ink, p.lamp) })
        val litFig = rememberVectorPainter(remember(p) { FxArt.vector(FxGlyph.LanternLit, p.ink, p.lamp) })
        val unlit = remember(p, unlitFig) { FxPin(unlitFig, p.ink, p.paper, null, figureDp = 28f, liftDp = 30f) }
        val lit = remember(p, litFig) { FxPin(litFig, p.ink, p.paper, null, figureDp = 28f, liftDp = 30f) }
        FxHapticAt(item, ctx, atMs = 150, HapticFeedbackType.Confirm)
        Box(
            Modifier.fxPlacedAt(this, at, ctx).size(BOX_DP.dp).drawWithCache {
                val flame = Path()
                val glowR = 28f * (if (dark) 1.6f else 1f) * density
                val glowAt = Offset(size.width / 2f, size.height / 2f - PIN_FIGURE_CY * density)
                val glow = Brush.radialGradient(listOf(p.lamp, Color.Transparent), glowAt, glowR)
                onDrawBehind {
                    if (item.reduced || ctx.map.cameraPosition.zoom < spec.zoom.start) return@onDrawBehind
                    val t = item.overlayT(ctx)
                    val tl = timeline
                    val dp = density
                    val ground = center
                    val head = Offset(ground.x, ground.y - PIN_FIGURE_CY * dp)
                    val out = 1f - tl.phase(t, 900, 1_100) // hand-off fade
                    if (out <= 0f) return@onDrawBehind
                    // Glow behind the head.
                    val g = tl.phase(t, 300, 900, FxEasing.StandardDecelerate)
                    if (g > 0f) {
                        val a = (if (dark) 0.8f else 0.6f) * g * out
                        scale(g, g, head) { drawCircle(glow, glowR, head, alpha = a) }
                    }
                    // The pin: unlit, then the lit figure crossfades in as the flame grows and the "?" fades.
                    val f = tl.phase(t, 150, 500, FxEasing.EmphasizedDecelerate)
                    pinAt(ground, dp) { with(unlit.painter) { draw(Size(40f, 62f), alpha = out) } }
                    if (f > 0f) pinAt(ground, dp) { with(lit.painter) { draw(Size(40f, 62f), alpha = f * out) } }
                    // The growing teardrop over the glass (lamp, ink outline), up to the lit art's flame size.
                    if (f in 0.001f..0.999f) {
                        val h = 9f * f * dp
                        val w = h * 0.42f
                        val base = Offset(head.x, head.y + 3f * dp)
                        flame.rewind()
                        flame.moveTo(base.x, base.y - h)
                        flame.cubicTo(base.x + w, base.y - h * 0.55f, base.x + w, base.y - h * 0.1f, base.x, base.y)
                        flame.cubicTo(base.x - w, base.y - h * 0.1f, base.x - w, base.y - h * 0.55f, base.x, base.y - h)
                        flame.close()
                        drawPath(flame, p.lamp.copy(alpha = out))
                        drawPath(flame, p.ink.copy(alpha = out), style = Stroke(1f * dp))
                    }
                    // Wick spark: 3 lamp points jump 2–4 dp.
                    val sp = tl.phase(t, 0, 150)
                    if (sp in 0.001f..0.999f) for (i in 0 until 3) {
                        val a = -PI / 2 + (i - 1) * 0.7
                        val d = (2f + i) * sp * dp
                        drawCircle(p.lamp, 1.2f * dp, Offset(head.x + (cos(a) * d).toFloat(), head.y + 2f * dp + (sin(a) * d).toFloat()))
                        drawCircle(p.ink, 1.2f * dp, Offset(head.x + (cos(a) * d).toFloat(), head.y + 2f * dp + (sin(a) * d).toFloat()), style = Stroke(0.5f * dp))
                    }
                    // Rays: 8 short ink strokes flick out from the head, staggered 30 ms.
                    for (i in 0 until 8) {
                        val rp = tl.phase(t, 500 + 30 * i, 800 + 30 * i, FxEasing.EmphasizedDecelerate)
                        if (rp <= 0f || rp >= 1f) continue
                        val a = 2 * PI * i / 8
                        val r0 = (17f + 6f * rp) * dp
                        val ca = cos(a).toFloat()
                        val sa = sin(a).toFloat()
                        drawLine(
                            p.ink.copy(alpha = (1f - rp) * out),
                            Offset(head.x + ca * r0, head.y + sa * r0), Offset(head.x + ca * (r0 + 4f * dp), head.y + sa * (r0 + 4f * dp)),
                            strokeWidth = 1.25f * dp, cap = StrokeCap.Round,
                        )
                    }
                }
            },
        )
    }

    private const val BOX_DP = 160f

    /** Draw a 40 × 62 dp pin image whose ground dot sits on [ground]. */
    private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.pinAt(ground: Offset, dp: Float, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
        translate(ground.x - 20f * dp, ground.y - 58f * dp) { scale(dp, dp, Offset.Zero) { block() } }
    }
}
