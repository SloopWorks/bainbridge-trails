package com.bainbridgetrails.play.fx.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.map.StatusLayerSpec
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
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.globalState
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.minus
import org.maplibre.compose.expressions.dsl.times
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * `kraken_attack`, Layer-tier parts (effects §3.1, PD-19, PD-20; the Overlay tentacles + splash are L2-5 on the same
 * kind): the ink casing beside each attacked stretch (`CASING`, `FxPalette.inkCasing`, status width + 8 dp, the status
 * line stays on top), the ink pool in the water (`UNDER_STATUS`, α .2), the far-zoom marker (`OVER_LABELS`, < z13.5)
 * and the grip stroke (a `LineLayer` in `CASING` on the intro's grip section, never Canvas). The intro instance
 * ([introSpec], HERO, `OVERLAY`, 2.6 s) drives two `globalState` keys: [KEY_REVEAL] (casing draws on outward from the
 * grip by per-feature `order`) and [KEY_GRIP]. Absent keys = the final frame (casing in full, no grip). Casing and
 * water are static parts ([FxSpec.static]); every part is one feature of the kind's one source ([geoJson], `p` =
 * [PART_CASING] / [PART_GRIP] / [PART_WATER]), shared by the three bands.
 */
object KrakenInk : FxRenderer {
    override val kind: String = "kraken_attack"
    const val KEY_REVEAL = "fx.kraken_attack.reveal"
    const val KEY_GRIP = "fx.kraken_attack.grip"
    private val CASING = FxLayerIds.of(kind, "casing")
    private val GRIP = FxLayerIds.of(kind, "grip")
    private val POOL = FxLayerIds.of(kind, "pool")
    private val MARKER = FxLayerIds.of(kind, "marker")
    val layerIds: List<String> = listOf(CASING, GRIP, POOL, MARKER)
    const val PART_CASING = "c"
    const val PART_GRIP = "g"
    const val PART_WATER = "w"

    /** Intro: converge 0–500, tentacles 500–1100, reveal 1100–1700, squeeze ~1500, hand-off 1800–2600 (effects §3.1). */
    val introTimeline = FxTimeline(spawnMs = 500, runMs = 1_300, settleMs = 800, despawnMs = 0, runLoopMs = null, reducedAtMs = 2_600)

    /** One attacked shore stretch: its id and its segment lines (lon/lat). */
    data class Stretch(val id: String, val lines: List<List<GeoPoint>>)

    /** This week's attack on one coast: [active] = inside its clock window (Thu 06:00 → Sun 20:00). */
    data class Attack(val key: String, val stretches: List<Stretch>, val water: GeoPoint, val active: Boolean)

    /** Casing per line of every stretch the user has not repelled, plus the water instance; empty when inactive. */
    fun specs(attack: Attack, repelled: Set<String>): List<FxSpec> {
        if (!attack.active) return emptyList()
        val open = attack.stretches.filter { it.id !in repelled }
        if (open.isEmpty()) return emptyList()
        val out = ArrayList<FxSpec>()
        for (s in open) s.lines.forEachIndexed { i, line ->
            if (line.size >= 2) {
                out += FxSpec.static("${attack.key}#casing:${s.id}:$i", kind, FxAnchor.Line(line), 0.0..22.0, FxBand.CASING)
            }
        }
        out += FxSpec.static("${attack.key}#water", kind, FxAnchor.Point(attack.water), 0.0..22.0, FxBand.UNDER_STATUS)
        return out
    }

    /** The intro (HERO, OVERLAY band): anchored on the grip section, the open line nearest the water (±60 m). */
    fun introSpec(attack: Attack, repelled: Set<String>): FxSpec? {
        if (!attack.active) return null
        val lines = attack.stretches.filter { it.id !in repelled }.flatMap { it.lines }.filter { it.size >= 2 }
        if (lines.isEmpty()) return null
        val line = lines.minBy { l -> l.minOf { metres(it, attack.water) } }
        val near = line.minBy { metres(it, attack.water) }
        val section = line.filter { metres(it, near) <= 60.0 }.takeIf { it.size >= 2 } ?: line
        return FxSpec("${attack.key}#intro", kind, FxAnchor.Line(section), 13.5..22.0, FxBand.OVERLAY, FxPriority.HERO, introTimeline, loop = false)
    }

    /** The stretch id of a casing spec, else null. */
    fun stretchOf(spec: FxSpec): String? =
        if ("#casing:" in spec.id) spec.id.substringAfter("#casing:").substringBeforeLast(':') else null

    fun revealAt(t: Int): Float = ((t - 1_100) / 600f).coerceIn(0f, 1f)

    fun gripAt(t: Int): Float {
        val up = ((t - 900) / 200f).coerceIn(0f, 1f)
        val down = ((t - 1_800) / 400f).coerceIn(0f, 1f)
        return up * (1f - down)
    }

    override fun writeGlobalState(live: List<FxLive>, nowMs: Long, view: FxView?, out: FxGlobalStateSink) {
        val intro = live.lastOrNull { it.instance.spec.band == FxBand.OVERLAY && !it.reduced } ?: return
        val i = intro.instance
        val t = i.spec.timeline.foldedMs(i.localMs(nowMs), i.dismissedAtMs)
        out.put(KEY_REVEAL, revealAt(t).toDouble())
        out.put(KEY_GRIP, gripAt(t).toDouble())
    }

    /** Rest: the casing in full, no grip — unless an intro is still in flight (it keeps its values). */
    override fun writeRestState(live: List<FxLive>, nowMs: Long, out: FxGlobalStateSink) {
        if (live.any { it.instance.spec.band == FxBand.OVERLAY && !it.reduced }) return writeGlobalState(live, nowMs, null, out)
        out.put(KEY_REVEAL, 1.0)
        out.put(KEY_GRIP, 0.0)
    }

    /**
     * The kind's one FeatureCollection: casing lines (`p` = c, with `order` 0..0.6 = distance from the water: the reveal spreads outward), the intro's grip section (g) and the water
     * points (w); ids are scene seqs, so feature state is set once per instance.
     */
    fun geoJson(live: List<FxLive>): String {
        val water = live.firstNotNullOfOrNull { (it.instance.spec.anchor as? FxAnchor.Point)?.at }
        val casing = live.filter { it.instance.spec.band == FxBand.CASING }
        val d = casing.associate { l -> l.instance.seq to (water?.let { w -> (l.instance.spec.anchor as FxAnchor.Line).path.minOf { metres(it, w) } } ?: 0.0) }
        val lo = d.values.minOrNull() ?: 0.0
        val span = ((d.values.maxOrNull() ?: 0.0) - lo).takeIf { it > 1.0 } ?: 1.0
        return fxGeoJson(live) { l, sb ->
            val spec = l.instance.spec
            when {
                spec.band == FxBand.CASING ->
                    sb.append("\"p\":\"$PART_CASING\",\"order\":").append(0.6 * ((d[l.instance.seq] ?: lo) - lo) / span)
                spec.band == FxBand.OVERLAY -> sb.append("\"p\":\"$PART_GRIP\"")
                else -> sb.append("\"p\":\"$PART_WATER\"")
            }
        }
    }

    @Composable
    override fun rememberSource(live: List<FxLive>, ctx: FxLayerContext): GeoJsonSource =
        rememberGeoJsonSource(GeoJsonData.JsonString(remember(live) { geoJson(live) }))

    @Composable
    @MaplibreComposable
    override fun Layers(band: FxBand, live: List<FxLive>, ctx: FxLayerContext, source: GeoJsonSource?) {
        if (source == null) return
        val p = ctx.palette
        val part = feature["p"].asString(const(""))
        when (band) {
            FxBand.CASING -> {
                val spec = StatusLayerSpec.current
                val stops = spec.widths.getValue(TrailStatus.COMPLETE).map { (z, w) -> z to const((w + 8f).dp) }
                val width = if (stops.size == 1) stops[0].second else interpolate(linear(), zoom(), *stops.toTypedArray())
                val reveal = globalState(KEY_REVEAL).asNumber(const(1f)) - feature["order"].asNumber(const(0f))
                LineLayer(
                    id = CASING, source = source, filter = part eq const(PART_CASING),
                    color = const(p.inkCasing), width = width,
                    opacity = if (ctx.reduced) const(1f) else interpolate(linear(), reveal, 0 to const(0f), 0.25 to const(1f)),
                    cap = const(LineCap.Round), join = const(LineJoin.Round),
                )
                val gripStops = spec.widths.getValue(TrailStatus.COMPLETE).map { (z, w) -> z to const((w + 12f).dp) }
                LineLayer(
                    id = GRIP, source = source, filter = part eq const(PART_GRIP),
                    color = const(p.ink),
                    width = if (gripStops.size == 1) gripStops[0].second else interpolate(linear(), zoom(), *gripStops.toTypedArray()),
                    opacity = if (ctx.reduced) const(0f) else const(0.6f) * globalState(KEY_GRIP).asNumber(const(0f)),
                    cap = const(LineCap.Round), join = const(LineJoin.Round),
                )
            }
            FxBand.UNDER_STATUS, FxBand.OVER_LABELS -> {
                val water = part eq const(PART_WATER)
                if (band == FxBand.UNDER_STATUS) {
                    // Geo 60 m at 47.66° N ≈ 37.3 dp at z15; exponential base 2 keeps it zoom-true.
                    CircleLayer(
                        id = POOL, source = source, filter = water,
                        color = const(p.inkPool), opacity = const(0.57f), blur = const(0.5f),
                        radius = interpolate(exponential(2f), zoom(), 10 to const((37.3f / 32f).dp), 20 to const((37.3f * 32f).dp)),
                    )
                } else {
                    val eye = rememberVectorPainter(remember(p) { FxArt.vector(FxGlyph.KrakenEye, p.ink) })
                    val glyph = remember(p, eye) { stampPainter(eye, 14f, p.paper, p.ink) }
                    SymbolLayer(
                        id = MARKER, source = source, filter = water, maxZoom = 13.5f,
                        iconImage = image(glyph, DpSize(14.dp, 14.dp)),
                        iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
                    )
                }
            }
            else -> Unit
        }
    }

    private fun metres(a: GeoPoint, b: GeoPoint): Double {
        val k = 111_195.0
        return hypot((a.lon - b.lon) * k * cos(a.lat * PI / 180.0), (a.lat - b.lat) * k)
    }
}
