package com.bainbridgetrails.play.fx.effects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.play.fx.FxAnchor
import com.bainbridgetrails.play.fx.FxBand
import com.bainbridgetrails.play.fx.FxGlobalStateSink
import com.bainbridgetrails.play.fx.FxLayerContext
import com.bainbridgetrails.play.fx.FxLive
import com.bainbridgetrails.play.fx.FxOverlayContext
import com.bainbridgetrails.play.fx.FxProjection
import com.bainbridgetrails.play.fx.FxRenderer
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.FxTapTarget
import com.bainbridgetrails.play.fx.GeoPoint
import com.bainbridgetrails.play.fx.KrakenTimeline
import com.bainbridgetrails.play.fx.fxPlacedAt
import com.bainbridgetrails.play.rng.SplitMix64
import com.bainbridgetrails.ui.theme.BtTheme
import kotlinx.coroutines.flow.first
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * `kraken_attack` = L2-4's Layer parts ([KrakenInk]: casing, pool, far marker, grip `LineLayer`) + this stage's
 * Overlay intro (effects §3.1, engine §2.4, PD-19): three water-line rings converge on the offshore anchor, tentacles
 * reach from the water to grip points on the road (25 m apart; 3 at z ≥ 15, 2 at z14, 1 at z13.5), overshoot and curl
 * back, splash, squeeze (`thud` at 1500) and sink, handing off to the Layer casing + lurk at 2600. The overlay never
 * draws geometry that must coincide with the road: the grip stroke is the Layer `LineLayer`; tentacle tips tolerate a
 * frame of slip and hide during gesture camera moves ([fxPlacedAt]). Register this instead of [KrakenInk] (one
 * renderer per kind). Reduced: no overlay motion, one static water-line ring at the anchor (the casing is in full).
 *
 * [onWaterTap] (+ [tapLabel]): an optional 48 dp [FxTapTarget] on the water point ("Face the kraken"); null = none.
 */
class KrakenAttack(
    private val onWaterTap: ((attackKey: String) -> Unit)? = null,
    private val tapLabel: String? = null,
) : FxRenderer {
    override val kind: String = KrakenInk.kind

    /** Attack key → its water point, read from the attack's `UNDER_STATUS` water instance (the intro is a Line). */
    private var water by mutableStateOf<Map<String, GeoPoint>>(emptyMap())

    override fun writeGlobalState(live: List<FxLive>, nowMs: Long, out: FxGlobalStateSink) =
        KrakenInk.writeGlobalState(live, nowMs, out)

    override fun writeRestState(live: List<FxLive>, nowMs: Long, out: FxGlobalStateSink) =
        KrakenInk.writeRestState(live, nowMs, out)

    @Composable
    override fun rememberSource(live: List<FxLive>, ctx: FxLayerContext): GeoJsonSource = KrakenInk.rememberSource(live, ctx)

    @Composable
    @MaplibreComposable
    override fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext, source: GeoJsonSource?) {
        KrakenInk.Layers(band, live, ctx, source)
        if (band == FxBand.UNDER_STATUS) {
            val points = remember(live) {
                live.mapNotNull { l -> (l.instance.spec.anchor as? FxAnchor.Point)?.let { attackKey(l.instance.spec) to it.at } }.toMap()
            }
            SideEffect { if (water != points) water = points }
        }
    }

    @Composable
    override fun MapOverlayScope.Overlay(item: FxLive, ctx: FxOverlayContext) {
        val spec = item.instance.spec
        val road = (spec.anchor as? FxAnchor.Line)?.path ?: return
        val w = water[attackKey(spec)] ?: return
        val anchor = remember(w, road) { anchor(w, road) }
        val targets = remember(anchor, road) { targets(anchor, road, count = 3) }
        val extent = remember(anchor, targets) { extentDp(anchor, targets, zoom = 18.0) }
        val ring = BtTheme.colors.waterLine
        val p = ctx.palette
        val rig = remember(spec.seed) { TentacleArt(count = 3, seed = spec.seed) }
        FxHapticAt(item, ctx, atMs = 1_500, HapticFeedbackType.LongPress) // `thud` at the squeeze
        Box(
            Modifier.fxPlacedAt(this, anchor, ctx).size(extent.dp).drawWithCache {
                val off = FloatArray(targets.size * 2)
                onDrawBehind {
                    val zoom = ctx.map.cameraPosition.zoom
                    if (zoom < spec.zoom.start) return@onDrawBehind
                    val t = item.overlayT(ctx)
                    val c = center
                    val dp = density
                    if (item.reduced) {
                        drawCircle(ring.copy(alpha = 0.7f), 20f * dp, c, style = Stroke(1.5f * dp))
                        return@onDrawBehind
                    }
                    // 1 converge: three water-line rings close on the anchor (anticipation).
                    val conv = KrakenTimeline.converge(t)
                    val ca = KrakenTimeline.convergeAlpha(t)
                    if (ca > 0f) for (i in 0 until 3) {
                        val r = lerp(48f + 12f * i, 8f + 5f * i, conv) * dp
                        drawCircle(ring.copy(alpha = ca * (1f - 0.2f * i)), r, c, style = Stroke(1.5f * dp))
                    }
                    // 2 pool bloom: an ink edge spreading to the 60 m pool (the pool itself is the Layer).
                    val bloom = KrakenTimeline.poolBloom(t)
                    if (bloom in 0.001f..0.999f) {
                        val r = FxProjection.metresToDp(60.0, anchor.lat, zoom).toFloat() * bloom * dp
                        drawCircle(p.ink.copy(alpha = 0.35f * (1f - bloom)), r, c, style = Stroke(2f * dp))
                    }
                    // 3 tentacles + splash.
                    FxProjection.offsetsDp(anchor, targets, zoom, off)
                    val count = tentacleCount(zoom)
                    val squeeze = KrakenTimeline.squeeze(t)
                    val alpha = KrakenTimeline.tentacleAlpha(t)
                    for (k in 0 until count) {
                        val ti = targetIndex(k, count)
                        rig.draw(
                            this, k, c, off[2 * ti] * dp, off[2 * ti + 1] * dp,
                            from = 0f, to = KrakenTimeline.tentacleLength(t, k), curl = KrakenTimeline.curl(t, k),
                            scale = squeeze, alpha = alpha, ink = p.ink, fill = p.figureFill, paper = p.paper,
                        )
                    }
                    rig.splash(this, c, KrakenTimeline.splash(t), p.ink, p.fog)
                }
            },
        )
        val label = tapLabel
        val onTap = onWaterTap
        if (onTap != null && label != null) FxTapTarget(anchor, label, ctx) { onTap(attackKey(spec)) }
    }

    companion object {
        /** Minimum water offset of the anchor (effects §3.1: the offshore point is ≥ 60 m from the road). */
        const val OFFSHORE_M = 60.0

        /** Grip targets along the road (effects §3.1: target spacing 25 m). */
        const val SPACING_M = 25.0

        /** Room around the rig in the overlay box: half the 12 dp base width, the 8 dp curl and the ink rim. */
        const val MARGIN_DP = 24f

        /** Smallest half-extent: the converge rings (≤ 72 dp) and the splash apex (≈ 96 dp) fit at any zoom. */
        private const val MIN_HALF_DP = 120f

        /** The attack's key: the spec id before `#` (casing, water and intro share it, [KrakenInk]). */
        fun attackKey(spec: FxSpec): String = spec.id.substringBefore('#')

        /**
         * The overlay anchor: the attack's water point, pushed straight out from the road to [OFFSHORE_M] when content
         * placed it closer (the tentacles must rise from the water, never from the shore).
         */
        fun anchor(water: GeoPoint, road: List<GeoPoint>): GeoPoint {
            if (road.size < 2) return water
            val near = nearest(water, road)
            val kx = M_PER_DEG * cos(water.lat * PI / 180.0)
            val dx = (water.lon - near.point.lon) * kx
            val dy = (water.lat - near.point.lat) * M_PER_DEG
            val d = hypot(dx, dy)
            if (d >= OFFSHORE_M || d < 1e-6) return water
            val s = (OFFSHORE_M + 0.5) / d
            return GeoPoint(lon = near.point.lon + dx * s / kx, lat = near.point.lat + dy * s / M_PER_DEG)
        }

        /** [count] grip points on [road], [SPACING_M] apart, centred on the road point nearest [anchor]. */
        fun targets(anchor: GeoPoint, road: List<GeoPoint>, count: Int): List<GeoPoint> {
            if (road.size < 2) return List(count) { road.firstOrNull() ?: anchor }
            val cum = DoubleArray(road.size)
            for (i in 1 until road.size) cum[i] = cum[i - 1] + metres(road[i - 1], road[i])
            val length = cum.last()
            val near = nearest(anchor, road)
            val s0 = cum[near.segment] + metres(road[near.segment], near.point)
            val span = SPACING_M * (count - 1)
            val start = (s0 - span / 2).coerceIn(0.0, max(0.0, length - span))
            return List(count) { i -> along(road, cum, (start + SPACING_M * i).coerceAtMost(length)) }
        }

        /** Side (dp) of the square overlay box that holds the whole rig at [zoom] (z18 is the largest). */
        fun extentDp(anchor: GeoPoint, targets: List<GeoPoint>, zoom: Double): Float {
            var half = 0.0
            for (t in targets) {
                val x = abs(FxProjection.offsetXDp(anchor.lon, t.lon, zoom))
                val y = abs(FxProjection.offsetYDp(anchor.lat, t.lat, zoom))
                half = max(half, max(x, y) * KrakenTimeline.OVERSHOOT)
            }
            return 2f * max(MIN_HALF_DP, (half + MARGIN_DP).toFloat())
        }

        /** 3 tentacles from z15, 2 at z14, 1 at z13.5 (effects §3.1, §1.4). */
        fun tentacleCount(zoom: Double): Int = when {
            zoom >= 15.0 -> 3
            zoom >= 14.0 -> 2
            else -> 1
        }

        /** Which of the 3 targets tentacle [k] of [count] grips: the outer two at 2, the middle at 1. */
        internal fun targetIndex(k: Int, count: Int): Int = when (count) {
            1 -> 1
            2 -> if (k == 0) 0 else 2
            else -> k
        }

        private const val M_PER_DEG = 111_195.0

        private class Near(val point: GeoPoint, val segment: Int)

        private fun nearest(p: GeoPoint, line: List<GeoPoint>): Near {
            val kx = M_PER_DEG * cos(p.lat * PI / 180.0)
            var best = Double.MAX_VALUE
            var bestPoint = line.first()
            var bestSeg = 0
            for (i in 0 until line.size - 1) {
                val ax = (line[i].lon - p.lon) * kx
                val ay = (line[i].lat - p.lat) * M_PER_DEG
                val bx = (line[i + 1].lon - p.lon) * kx
                val by = (line[i + 1].lat - p.lat) * M_PER_DEG
                val sx = bx - ax
                val sy = by - ay
                val len2 = sx * sx + sy * sy
                val u = if (len2 < 1e-12) 0.0 else (-(ax * sx + ay * sy) / len2).coerceIn(0.0, 1.0)
                val d = hypot(ax + sx * u, ay + sy * u)
                if (d < best) {
                    best = d
                    bestSeg = i
                    bestPoint = GeoPoint(
                        lon = line[i].lon + (line[i + 1].lon - line[i].lon) * u,
                        lat = line[i].lat + (line[i + 1].lat - line[i].lat) * u,
                    )
                }
            }
            return Near(bestPoint, bestSeg)
        }

        private fun along(line: List<GeoPoint>, cum: DoubleArray, s: Double): GeoPoint {
            for (i in 1 until line.size) {
                if (s <= cum[i] || i == line.lastIndex) {
                    val seg = cum[i] - cum[i - 1]
                    val u = if (seg < 1e-9) 0.0 else ((s - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
                    return GeoPoint(
                        lon = line[i - 1].lon + (line[i].lon - line[i - 1].lon) * u,
                        lat = line[i - 1].lat + (line[i].lat - line[i - 1].lat) * u,
                    )
                }
            }
            return line.last()
        }

        internal fun metres(a: GeoPoint, b: GeoPoint): Double =
            hypot((a.lon - b.lon) * M_PER_DEG * cos(a.lat * PI / 180.0), (a.lat - b.lat) * M_PER_DEG)
    }
}

// ---- shared by the L2-5 overlay effects ----

/** Folded draw time of an overlay item at the clock's frame (draw phase only); the final frame when still. */
internal fun FxLive.overlayT(ctx: FxOverlayContext): Int {
    val tl = instance.spec.timeline
    return if (reduced || demoted || finalFrame) tl.reducedAtMs else tl.foldedMs(instance.localMs(ctx.clock.frameMs), instance.dismissedAtMs)
}

/**
 * One haptic (effects §1.8) when [item]'s local time passes [atMs] on the FX clock; under reduced motion or a still
 * item it fires at once (haptics are unchanged by reduced motion). Never from the draw phase.
 */
@Composable
internal fun FxHapticAt(item: FxLive, ctx: FxOverlayContext, atMs: Long, type: HapticFeedbackType) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(item.instance.seq) {
        val still = item.reduced || item.demoted || item.finalFrame
        if (!still) snapshotFlow { ctx.clock.frameMs }.first { item.instance.localMs(it) >= atMs }
        haptics.performHapticFeedback(type)
    }
}

internal fun lerp(a: Float, b: Float, f: Float): Float = a + (b - a) * f

/**
 * Tentacle art (effects §3.1): a cubic Bézier from a root in the water to a grip point, drawn as [PIECES] tapered
 * pieces (12 → 2 dp, ink rim + `secondaryContainer` fill), paper sucker dots on the inner side, a curl at the tip and
 * seeded variation. Paths and measures are preallocated and rewound per frame (no per-frame allocation).
 */
internal class TentacleArt(val count: Int, seed: Long) {
    private val curve = Path()
    private val pieces = Array(PIECES) { Path() }
    private val measure = PathMeasure()
    private val bend = FloatArray(count)
    private val rootSpread = FloatArray(count)
    private val dropVx = FloatArray(DROPS)
    private val dropVy = FloatArray(DROPS)
    private val dropR = FloatArray(DROPS)
    private val dropFrom = IntArray(DROPS)

    init {
        val rng = SplitMix64(seed.toULong())
        for (k in 0 until count) {
            bend[k] = ((if (k % 2 == 0) 1 else -1) * (0.16 + 0.12 * rng.nextDouble())).toFloat()
            rootSpread[k] = ((k - (count - 1) / 2f) * 10f + (rng.nextDouble() - 0.5) * 6.0).toFloat()
        }
        for (j in 0 until DROPS) {
            dropVx[j] = ((rng.nextDouble() - 0.5) * 220.0).toFloat()
            dropVy[j] = (-(300.0 + 220.0 * rng.nextDouble())).toFloat()
            dropR[j] = (1.0 + rng.nextDouble()).toFloat() // radius 1–2 dp = 2–4 dp droplets
            dropFrom[j] = j % count
        }
    }

    /**
     * Draws tentacle [k] from its root beside [c] to the grip at ([tx], [ty]) px from [c]: the visible part is
     * [from]..[to] of the curve (to > 1 = the overshoot, extending the curve past the grip), [curl] bends the tip,
     * [scale] squeezes about the root.
     */
    fun draw(
        scope: DrawScope, k: Int, c: Offset, tx: Float, ty: Float,
        from: Float, to: Float, curl: Float, scale: Float, alpha: Float,
        ink: Color, fill: Color, paper: Color,
    ) = with(scope) {
        if (to <= from || alpha <= 0f) return
        val dp = density
        val len0 = sqrt(tx * tx + ty * ty).coerceAtLeast(1f)
        val ux = tx / len0
        val uy = ty / len0
        val nx = -uy
        val ny = ux
        val rx = c.x + nx * rootSpread[k] * dp
        val ry = c.y + ny * rootSpread[k] * dp
        val reach = max(1f, to)
        val ex = (c.x + tx - rx) * reach * scale
        val ey = (c.y + ty - ry) * reach * scale
        val b = bend[k] * len0 * scale
        val curlDp = curl * 8f * dp * (if (bend[k] > 0) -1f else 1f)
        curve.rewind()
        curve.moveTo(rx, ry)
        curve.cubicTo(
            rx + ex * 0.3f + nx * b, ry + ey * 0.3f + ny * b,
            rx + ex * 0.75f - nx * b * 0.5f + nx * curlDp, ry + ey * 0.75f - ny * b * 0.5f + ny * curlDp,
            rx + ex + nx * curlDp * 0.6f, ry + ey + ny * curlDp * 0.6f,
        )
        measure.setPath(curve, false)
        val total = measure.length
        val a = total * (from / reach)
        val z = total * (to / reach).coerceAtMost(1f)
        if (z - a < 0.5f) return
        for (i in 0 until PIECES) {
            pieces[i].rewind()
            val s0 = a + (z - a) * i / PIECES
            val s1 = a + (z - a) * (i + 1) / PIECES
            measure.getSegment(s0, s1, pieces[i], true)
        }
        for (i in 0 until PIECES) {
            val w = widthAt((i + 0.5f) / PIECES) * dp
            drawPath(pieces[i], ink.copy(alpha = alpha), style = Stroke(w + 2.5f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        for (i in 0 until PIECES) {
            val w = widthAt((i + 0.5f) / PIECES) * dp
            drawPath(pieces[i], fill.copy(alpha = alpha), style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // Suckers: paper dots on the inner (bend) side, along the middle of the visible part.
        val side = if (bend[k] > 0) 1f else -1f
        var f = 0.3f
        while (f < 0.86f) {
            val d = a + (z - a) * f
            val pos = measure.getPosition(d)
            val tan = measure.getTangent(d)
            val w = widthAt(f) * dp
            drawCircle(paper.copy(alpha = alpha), 1f * dp, Offset(pos.x - tan.y * side * w * 0.3f, pos.y + tan.x * side * w * 0.3f))
            f += 0.11f
        }
    }

    /** Splash droplets ([DROPS]: half ink, half fog), `ballistic` (g = 1400 dp/s²) from the roots, over [p] 0..1. */
    fun splash(scope: DrawScope, c: Offset, p: Float, ink: Color, fog: Color) = with(scope) {
        if (p <= 0f || p >= 1f) return
        val dp = density
        val s = p * 0.4f // medium4 = 400 ms of flight
        val a = 1f - p
        for (j in 0 until DROPS) {
            val x = c.x + rootSpread[dropFrom[j]] * dp + dropVx[j] * s * dp
            val y = c.y + (dropVy[j] * s + 0.5f * G * s * s) * dp
            drawCircle(if (j % 2 == 0) ink.copy(alpha = a) else fog.copy(alpha = fog.alpha * a), dropR[j] * dp, Offset(x, y))
        }
    }

    private fun widthAt(f: Float): Float = 12f + (2f - 12f) * f

    companion object {
        const val PIECES = 8
        const val DROPS = 12
        const val G = 1400f
    }
}
