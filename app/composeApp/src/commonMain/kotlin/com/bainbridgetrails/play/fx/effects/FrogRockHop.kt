package com.bainbridgetrails.play.fx.effects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxAnchor
import com.bainbridgetrails.play.fx.FxArt
import com.bainbridgetrails.play.fx.FxBand
import com.bainbridgetrails.play.fx.FxEasing
import com.bainbridgetrails.play.fx.FxGlobalStateSink
import com.bainbridgetrails.play.fx.FxGlyph
import com.bainbridgetrails.play.fx.FxLayerContext
import com.bainbridgetrails.play.fx.FxLive
import com.bainbridgetrails.play.fx.FxOverlayContext
import com.bainbridgetrails.play.fx.FxPalette
import com.bainbridgetrails.play.fx.FxPriority
import com.bainbridgetrails.play.fx.FxRenderer
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.FxTimeline
import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.play.fx.fxPlacedAt
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.util.MaplibreComposable
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * `frog_rock` = L2-4's stamp + blink ([FrogRockStamp], Layer) + this stage's hop and croak (effects §3.4, Overlay,
 * NOTICE, 1800 ms at rest): squash (anticipation, 0–150), rise 14 dp with a stretch while its SE shadow shrinks
 * (150–450), fall (450–650), land squash with a `tick` (650–750), the throat sac (900–1100) and two `secondary` croak
 * arcs (1000–1800, stagger 200). While a hop is live the Layer stamp steps aside and the overlay draws the whole stamp
 * (rock + frog) at the same size (`icon-size` table, [stampScale]), so nothing doubles; it ends on the rest pose and
 * hands back. Register this instead of [FrogRockStamp] (one renderer per kind). Reduced: no hop; the stamp shows the
 * croaking pose (sac out) for the instance's life.
 */
object FrogRock : FxRenderer {
    override val kind: String = FrogRockStamp.kind

    /** Squash 0–150 (SPAWN), hop + land 150–750 (RUN), throat + croak 750–1800 (SETTLE). */
    val hopTimeline = FxTimeline(spawnMs = 150, runMs = 600, settleMs = 1_050, despawnMs = 0, runLoopMs = null, reducedAtMs = 1_800)

    /** Encounter (sensed) hop; a tap's hop uses the same spec, the 3 s cooldown keeps taps from stacking. */
    fun hopSpec(id: String = "lore-frog#hop", at: GeoPoint = FrogRockStamp.LANDMARK): FxSpec =
        FxSpec(id, kind, FxAnchor.Point(at), 14.0..22.0, FxBand.OVERLAY, FxPriority.NOTICE, hopTimeline, loop = false, cooldownMs = 3_000)

    override fun writeGlobalState(live: List<FxLive>, nowMs: Long, out: FxGlobalStateSink) =
        FrogRockStamp.writeGlobalState(live.filter { it.instance.spec.band != FxBand.OVERLAY }, nowMs, out)

    @Composable
    @MaplibreComposable
    override fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext) {
        val stamps = remember(live) {
            if (live.any { it.instance.spec.band == FxBand.OVERLAY }) emptyList() else live.filter { it.instance.spec.band != FxBand.OVERLAY }
        }
        FrogRockStamp.Layers(band, stamps, ctx)
    }

    /** The stamp's `icon-size` at [zoom] (FrogRockStamp: exponential 1.5 through .35/.6/1/1/1.4 at z10.5/12.5/14/16/18). */
    fun stampScale(zoom: Double): Float {
        val stops = STOPS
        if (zoom <= stops[0].first) return stops[0].second
        for (i in 1 until stops.size) {
            val (z1, v1) = stops[i]
            val (z0, v0) = stops[i - 1]
            if (zoom <= z1) {
                val f = ((1.5.pow(zoom - z0) - 1) / (1.5.pow(z1 - z0) - 1)).toFloat()
                return v0 + (v1 - v0) * f
            }
        }
        return stops.last().second
    }

    private val STOPS = listOf(10.5 to 0.35f, 12.5 to 0.6f, 14.0 to 1f, 16.0 to 1f, 18.0 to 1.4f)

    @Composable
    override fun MapOverlayScope.Overlay(item: FxLive, ctx: FxOverlayContext) {
        val spec = item.instance.spec
        val at = (spec.anchor as? FxAnchor.Point)?.at ?: return
        val p = ctx.palette
        val croak = MaterialTheme.colorScheme.secondary
        val frog = rememberVectorPainter(remember(p) { FxArt.vector(FxGlyph.Frog, p.ink) })
        FxHapticAt(item, ctx, atMs = 650, HapticFeedbackType.SegmentTick)
        Box(
            Modifier.fxPlacedAt(this, at, ctx).size(BOX_DP.dp).drawWithCache {
                onDrawBehind {
                    val zoom = ctx.map.cameraPosition.zoom
                    val t = item.overlayT(ctx)
                    val s = stampScale(zoom) * density
                    // Stamp-local units (dp of the 36 × 32 stamp), origin at the stamp's centre (the anchor).
                    translate(center.x, center.y) {
                        scale(s, s, Offset.Zero) {
                            translate(-18f, -16f) { drawHop(t, item.reduced, p, frog, croak) }
                        }
                    }
                }
            },
        )
    }

    private const val BOX_DP = 200f

    /** One frame of the hop in stamp units (36 × 32, as FrogRockStamp's image). */
    private fun DrawScope.drawHop(t: Int, reduced: Boolean, p: FxPalette, frog: Painter, croak: Color) {
        val tl = hopTimeline
        // Rock: tertiaryContainer with SE hatching (static).
        drawOval(p.rock, topLeft = Offset(2f, 18f), size = Size(32f, 12f))
        drawOval(p.ink, topLeft = Offset(2f, 18f), size = Size(32f, 12f), style = Stroke(1.5f))
        for (i in 0..3) drawLine(p.ink, Offset(20f + i * 3f, 28.5f), Offset(24f + i * 3f, 24.5f), strokeWidth = 1f)
        var sx = 1f
        var sy = 1f
        var lift = 0f
        var sac = if (reduced) 1f else 0f
        if (!reduced) {
            val squash = tl.phase(t, 0, 150, FxEasing.EmphasizedAccelerate)
            val rise = tl.phase(t, 150, 450, FxEasing.EmphasizedDecelerate)
            val fall = tl.phase(t, 450, 650, FxEasing.StandardAccelerate)
            val land = sin(PI * tl.phase(t, 650, 750)).toFloat()
            lift = 14f * rise * (1f - fall)
            when {
                t < 150 -> { sy = 1f - 0.15f * squash; sx = 1f + 0.08f * squash }
                t < 450 -> { sy = lerp(0.85f, 1.1f, rise); sx = lerp(1.08f, 0.96f, rise) }
                t < 650 -> { sy = lerp(1.1f, 1f, fall); sx = lerp(0.96f, 1f, fall) }
                else -> { sy = 1f - 0.1f * land; sx = 1f + 0.05f * land }
            }
            sac = sin(PI * tl.phase(t, 900, 1_100, FxEasing.Standard)).toFloat()
        }
        // SE shadow under a lifted frog: shrinks to 60 % and fades .18 → .08.
        if (lift > 0.01f) {
            val k = lift / 14f
            val w = 18f * (1f - 0.4f * k)
            drawOval(p.ink.copy(alpha = 0.18f - 0.1f * k), topLeft = Offset(19.5f - w / 2f, 18.5f), size = Size(w, 3.5f))
        }
        // Frog: pivot = feet (18, 18.5).
        translate(0f, -lift) {
            scale(sx, sy, Offset(18f, 18.5f)) {
                drawOval(p.figureFill, topLeft = Offset(9f, 9f), size = Size(18f, 12f))
                translate(6f, -1f) { with(frog) { draw(Size(24f, 24f)) } }
                if (sac > 0.01f) {
                    val w = 7f * sac
                    drawOval(p.paper, topLeft = Offset(18f - w / 2f, 15.6f), size = Size(w, w * 0.55f))
                    drawOval(p.ink, topLeft = Offset(18f - w / 2f, 15.6f), size = Size(w, w * 0.55f), style = Stroke(0.75f))
                }
            }
        }
        if (reduced) return
        // Croak: two arcs east of the frog, 8 → 36 dp, α .8 → 0, stagger 200.
        for (i in 0 until 2) {
            val cp = tl.phase(t, 1_000 + 200 * i, 1_600 + 200 * i, FxEasing.EmphasizedDecelerate)
            if (cp <= 0f || cp >= 1f) continue
            val r = lerp(8f, 36f, cp)
            drawArc(
                croak.copy(alpha = 0.8f * (1f - cp)), startAngle = -40f, sweepAngle = 80f, useCenter = false,
                topLeft = Offset(24f - r, 11f - r), size = Size(2 * r, 2 * r), style = Stroke(1.25f, cap = StrokeCap.Round),
            )
        }
    }
}
