package com.bainbridgetrails.play.fx.effects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.map.StatusLayerSpec
import com.bainbridgetrails.play.fx.FxAnchor
import com.bainbridgetrails.play.fx.FxBand
import com.bainbridgetrails.play.fx.FxGlobalStateSink
import com.bainbridgetrails.play.fx.FxLayerContext
import com.bainbridgetrails.play.fx.FxLayerIds
import com.bainbridgetrails.play.fx.FxLive
import com.bainbridgetrails.play.fx.FxOverlayContext
import com.bainbridgetrails.play.fx.FxOverlayExtent
import com.bainbridgetrails.play.fx.FxPriority
import com.bainbridgetrails.play.fx.FxProjection
import com.bainbridgetrails.play.fx.FxRenderer
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.FxView
import com.bainbridgetrails.play.fx.KrakenTimeline
import com.bainbridgetrails.play.fx.fxGeoJson
import com.bainbridgetrails.play.fx.fxPlacedAt
import com.bainbridgetrails.ui.theme.BtTheme
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.globalState
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.minus
import org.maplibre.compose.expressions.dsl.plus
import org.maplibre.compose.expressions.dsl.times
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

/**
 * `kraken_recede` (effects §3.1, HERO, 2200 ms): the stretch was sensed as repelled (provisional until Finish; no
 * `claim_pop` here). Layer: the repelled stretch's casing (`CASING`, a copy of [KrakenInk]'s, which drops the stretch
 * at once) flickers as the grip loosens and un-draws from the far end back toward the water ([KEY_FLICKER],
 * [KEY_UNDRAW]; absent keys = gone). Overlay: the tentacles, re-spawned at full length, slide back into the water,
 * the pool dissipates and three water-line rings expand from the anchor ("the Sound is calm"); `ticks` ×4 on the
 * un-draw, `confirm` at 900. Reduced: the casing is gone and one static ring marks the anchor until DONE.
 *
 * The HERO's anchor is a Line `[water anchor, grip targets…]` (the rig, [KrakenAttack.anchor] / [KrakenAttack.targets]),
 * so the overlay needs no other instance; the casing instances are Lines of the stretch, one per segment line.
 */
object KrakenRecede : FxRenderer {
    override val kind: String = "kraken_recede"
    const val KEY_FLICKER = "fx.kraken_recede.flicker"
    const val KEY_UNDRAW = "fx.kraken_recede.undraw"
    private val CASING = FxLayerIds.of(kind, "casing")
    val layerIds: List<String> = listOf(CASING)

    /** The recede of [stretchId] in [attack]: the HERO (Overlay) + one CASING instance per stretch line. */
    fun specs(attack: KrakenInk.Attack, stretchId: String): List<FxSpec> {
        val stretch = attack.stretches.firstOrNull { it.id == stretchId } ?: return emptyList()
        val lines = stretch.lines.filter { it.size >= 2 }
        if (lines.isEmpty()) return emptyList()
        val road = lines.minBy { l -> l.minOf { KrakenAttack.metres(it, attack.water) } }
        val anchor = KrakenAttack.anchor(attack.water, road)
        val targets = KrakenAttack.targets(anchor, road, count = 3)
        val tl = KrakenTimeline.recede
        val key = "${attack.key}#recede:$stretchId"
        return listOf(FxSpec(key, kind, FxAnchor.Line(listOf(anchor) + targets), 13.5..22.0, FxBand.OVERLAY, FxPriority.HERO, tl, loop = false)) +
            lines.mapIndexed { i, l -> FxSpec("$key:casing:$i", kind, FxAnchor.Line(l), 0.0..22.0, FxBand.CASING, FxPriority.AMBIENT, tl, loop = false) }
    }

    override fun writeGlobalState(live: List<FxLive>, nowMs: Long, view: FxView?, out: FxGlobalStateSink) {
        val hero = live.lastOrNull { it.instance.spec.band == FxBand.OVERLAY && !it.reduced } ?: return
        val i = hero.instance
        val t = i.spec.timeline.foldedMs(i.localMs(nowMs), i.dismissedAtMs)
        out.put(KEY_FLICKER, KrakenTimeline.recedeFlicker(t).toDouble())
        out.put(KEY_UNDRAW, KrakenTimeline.recedeUndraw(t).toDouble())
    }

    /** Casing lines with `order` 0..0.6: 0.6 = farthest from the water (it lets go first). */
    fun casingGeoJson(live: List<FxLive>): String {
        val hero = live.firstOrNull { it.instance.spec.band == FxBand.OVERLAY }
        val water = (hero?.instance?.spec?.anchor as? FxAnchor.Line)?.path?.firstOrNull()
        val casing = live.filter { it.instance.spec.band == FxBand.CASING }
        val d = casing.associate { l ->
            l.instance.seq to (water?.let { w -> (l.instance.spec.anchor as FxAnchor.Line).path.minOf { KrakenAttack.metres(it, w) } } ?: 0.0)
        }
        val lo = d.values.minOrNull() ?: 0.0
        val span = ((d.values.maxOrNull() ?: 0.0) - lo).takeIf { it > 1.0 } ?: 1.0
        return fxGeoJson(casing) { l, sb -> sb.append("\"order\":").append(0.6 * ((d[l.instance.seq] ?: lo) - lo) / span) }
    }

    @Composable
    @MaplibreComposable
    override fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext, source: GeoJsonSource?) {
        if (band != FxBand.CASING) return
        val source = rememberGeoJsonSource(GeoJsonData.JsonString(remember(live) { casingGeoJson(live) }))
        val spec = StatusLayerSpec.current
        val stops = spec.widths.getValue(TrailStatus.COMPLETE).map { (z, w) -> z to const((w + 8f).dp) }
        val width = if (stops.size == 1) stops[0].second else interpolate(linear(), zoom(), *stops.toTypedArray())
        // Left of the un-draw edge: 1 − undraw·1.6 + order > 0 keeps a line; the far lines (order .6) go first.
        val left = const(1f) - globalState(KEY_UNDRAW).asNumber(const(1f)) * const(1.6f) + feature["order"].asNumber(const(0f))
        LineLayer(
            id = CASING, source = source,
            color = const(ctx.palette.inkCasing), width = width,
            opacity = if (ctx.reduced) {
                const(0f)
            } else {
                interpolate(linear(), left, 0 to const(0f), 0.25 to const(1f)) * globalState(KEY_FLICKER).asNumber(const(1f))
            },
            cap = const(LineCap.Round), join = const(LineJoin.Round),
        )
    }

    @Composable
    override fun MapOverlayScope.Overlay(item: FxLive, ctx: FxOverlayContext) {
        val spec = item.instance.spec
        val path = (spec.anchor as? FxAnchor.Line)?.path ?: return
        if (path.size < 2) return
        val anchor = path.first()
        val targets = remember(path) { path.drop(1) }
        val density = LocalDensity.current.density
        // F2: clamped to FxOverlayExtent.MAX_PX; a rig kilometres across is Layer-tier only.
        val extent = remember(anchor, targets, density) { FxOverlayExtent.partDp(KrakenAttack.extentDp(anchor, targets, zoom = 18.0), density) } ?: return
        val ring = BtTheme.colors.waterLine
        val p = ctx.palette
        val rig = remember(spec.seed) { TentacleArt(count = 3, seed = spec.seed) }
        for (n in 0 until 4) FxHapticAt(item, ctx, atMs = 150L + 150L * n, HapticFeedbackType.SegmentFrequentTick) // ticks ×4
        FxHapticAt(item, ctx, atMs = 900, HapticFeedbackType.Confirm)
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
                        if (item.instance.localMs(ctx.clock.frameMs) < 2_000) {
                            drawCircle(ring.copy(alpha = 0.7f), 20f * dp, c, style = Stroke(1.5f * dp))
                        }
                        return@onDrawBehind
                    }
                    // Pool dissipates (α .2 → 0, radius ×1.3).
                    val fade = KrakenTimeline.poolFade(t)
                    if (fade < 1f) {
                        val r = FxProjection.metresToDp(60.0, anchor.lat, zoom).toFloat() * (1f + 0.3f * fade) * dp
                        drawCircle(p.ink.copy(alpha = 0.2f * (1f - fade)), r, c)
                    }
                    // Tentacles slide back into the water.
                    FxProjection.offsetsDp(anchor, targets, zoom, off)
                    val count = KrakenAttack.tentacleCount(zoom)
                    val back = KrakenTimeline.retract(t)
                    for (k in 0 until count) {
                        val ti = KrakenAttack.targetIndex(k, count).coerceAtMost(targets.lastIndex)
                        rig.draw(
                            this, k, c, off[2 * ti] * dp, off[2 * ti + 1] * dp,
                            from = 0f, to = 1f - back, curl = 0f, scale = 1f, alpha = 1f,
                            ink = p.ink, fill = p.figureFill, paper = p.paper,
                        )
                    }
                    // Calm rings: the celebration motif expanding from the anchor.
                    for (i in 0 until 3) {
                        val a = KrakenTimeline.calmRingAlpha(t, i)
                        if (a <= 0f) continue
                        drawCircle(ring.copy(alpha = a), lerp(8f, 56f, KrakenTimeline.calmRing(t, i)) * dp, c, style = Stroke(1.5f * dp))
                    }
                }
            },
        )
    }
}
